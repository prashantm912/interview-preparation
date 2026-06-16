# SonarQube & Code Quality

SonarQube is a self-hosted (or SonarCloud-hosted) platform for continuous inspection of code quality and security. It runs static analysis on your source, tracks bugs, vulnerabilities, code smells, security hotspots, coverage, and duplication, and enforces a **Quality Gate** that can pass or fail a build. This guide covers the concepts and the production realities interviewers probe at every level.

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

### Q1. [Theory] What is SonarQube and what problem does it solve?

SonarQube is a continuous code-quality and security platform that performs **static analysis** — analyzing source code without executing it — to surface defects, security weaknesses, and maintainability problems early, before they reach production. The core problem it solves is that manual code review is inconsistent and humans miss systematic issues (null-pointer paths, resource leaks, injection patterns, duplicated logic). SonarQube codifies thousands of rules across 30+ languages and applies them uniformly on every commit, giving the team an objective, trackable measure of quality over time. It is most valuable wired into CI so that a deteriorating codebase produces an automatic, visible signal rather than a vague feeling. The trade-off is that static analysis sees patterns, not intent, so it produces false positives and cannot replace tests or human judgment — it complements them.

### Q2. [Theory] What is the difference between a bug, a vulnerability, a code smell, and a security hotspot?

These are SonarQube's four issue categories, and confusing them is a classic junior mistake.

```
 Issue Type        Affects            Example (Java)
 ---------------   ----------------   -----------------------------------------
 Bug               Reliability        Possible NullPointerException; unclosed
                                       Stream; == used to compare Strings
 Vulnerability     Security           SQL built by string concatenation; weak
                                       crypto (MD5); hardcoded credentials
 Code Smell        Maintainability    Method too long; duplicated block;
                                       cognitive complexity too high; unused var
 Security Hotspot  Security (review)   Use of java.util.Random; CORS config;
                                       things that MIGHT be risky in context
```

- A **bug** is code that is demonstrably wrong and will likely misbehave at runtime.
- A **vulnerability** is a point that an attacker could exploit — SonarQube is confident it's a security problem.
- A **code smell** does not break anything but increases the cost of change (maintainability).
- A **security hotspot** is *security-sensitive code that needs human review* — Sonar can't decide automatically whether it's safe, so it asks a developer to confirm "reviewed/safe" or escalate it to a vulnerability. Hotspots are about awareness, not automatic verdicts.

### Q3. [Theory] What is a Quality Gate?

A Quality Gate is a set of **boolean conditions** that the analysis result must satisfy for the project to be considered releasable; if any condition fails, the gate status is **Failed** (red), and CI can break the build. The built-in default, **Sonar way**, focuses on *new code* and requires, for example: 0 new bugs, 0 new vulnerabilities, all new security hotspots reviewed, coverage on new code ≥ 80%, duplicated lines on new code ≤ 3%, and maintainability/reliability/security ratings on new code at "A". The gate is the single yes/no decision point that turns analysis into enforcement; without it, SonarQube is just a dashboard people ignore.

### Q4. [Practical] How would you run a SonarQube scan on a Java Maven project locally?

Add the SonarQube scanner plugin invocation and point it at your server with a token (never a password):

```bash
# Generate a user token in SonarQube UI: My Account > Security
mvn clean verify \
  org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=$SONAR_TOKEN \
  -Dsonar.projectKey=my-service
```

In production you would not pass these on the command line; you'd configure `sonar.host.url` and the token as CI secrets and put `sonar.projectKey`/`sonar.projectName` in a committed `sonar-project.properties` (or the `<properties>` of the POM). Running `verify` (not just `compile`) matters because it executes tests and produces the JaCoCo report Sonar later imports. After the scan finishes, the scanner uploads results and the server computes the gate asynchronously.

### Q5. [Theory] What is SonarLint and how does it differ from SonarQube?

SonarLint is an **IDE plugin** (IntelliJ, VS Code, Eclipse, Visual Studio) that runs a subset of Sonar's rules *as you type*, giving instant feedback before you even commit — the "shift-left" companion to the server. SonarQube, by contrast, runs in CI/CD, persists history, computes trends, owns the Quality Gate, and is the system of record for the whole team. The key feature is **Connected Mode**: SonarLint binds to a SonarQube/SonarCloud project so the IDE uses the *same* Quality Profile, rule activations, and suppressions as the server. Without Connected Mode, developers and the server can disagree, causing "it was clean in my IDE but CI failed" friction. SonarLint reduces the feedback loop from minutes (CI) to seconds (keystroke).

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain the "Clean as You Code" philosophy and the new-code vs overall-code distinction.

"Clean as You Code" is SonarQube's core methodology: instead of trying to fix every legacy issue (often tens of thousands, demoralizing and never-ending), you hold a strict standard on **new code** — code added or changed since a defined baseline — and let the overall codebase improve organically as files are touched. The Quality Gate therefore evaluates conditions mostly against new code. This is pragmatic because legacy debt rarely gets a dedicated budget, but every PR is a chance to not make things worse.

```
        Overall code (legacy)            New code (this PR / since baseline)
   ┌─────────────────────────────┐   ┌──────────────────────────┐
   │  12,403 smells, 88 bugs     │   │  +3 smells, 0 bugs        │
   │  (informational, tracked)   │   │  GATE ENFORCES THESE  ←   │
   └─────────────────────────────┘   └──────────────────────────┘
                 time ───────────────────────────────────────────▶
                 Quality slowly trends up as files are edited
```

The "new code period" can be defined as: **previous_version**, a **number of days**, a **specific date**, or (best for trunk-based work) **reference branch** — diffing against `main`. The latter is what powers PR analysis. The trade-off: a deeply rotten legacy module is never *forced* clean, so for high-risk components you may run a one-time remediation campaign outside the gate.

### Q7. [Practical] How do you integrate JaCoCo code coverage so SonarQube reports it correctly?

A near-universal gotcha: SonarQube does **not measure coverage itself** — it *imports* a report produced by a coverage tool. For Java that's JaCoCo. You must (1) make JaCoCo instrument and produce an XML report during the test phase, and (2) tell Sonar where the report is.

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.12</version>
  <executions>
    <execution>           <!-- attach the agent before tests run -->
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>           <!-- generate XML in the 'report' phase -->
      <id>report</id>
      <phase>test</phase>
      <goals><goal>report</goal></goals>
    </execution>
  </executions>
</plugin>
```

Sonar auto-detects `target/site/jacoco/jacoco.xml`, but in multi-module builds you almost always must set it explicitly:

```properties
sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml,../report-aggregate/target/site/jacoco/jacoco.xml
```

**Production reality:** in a multi-module Maven project, per-module reports don't capture cross-module integration tests, so teams add a dedicated aggregator module that merges exec data into one `jacoco.xml`. If coverage shows 0% in Sonar despite tests running, the cause is almost always (a) the XML report wasn't generated, (b) the path is wrong, or (c) `binaries`/source paths don't line up. Note `sonar.coverage.jacoco.xmlReportPaths` (XML) superseded the deprecated `sonar.jacoco.reportPaths` (binary .exec) years ago — don't use the old one.

### Q8. [Theory] What is technical debt and how does SonarQube compute the Maintainability Rating?

SonarQube quantifies technical debt as the **estimated time to fix all code smells**, expressed in minutes/days (the "remediation effort"). Each rule has a remediation cost (e.g., "5 min per occurrence"). The **Maintainability Rating (A–E)** is derived from the **Technical Debt Ratio** = remediation cost ÷ estimated cost to rewrite the code from scratch (development cost, default 30 min per line). The mapping:

```
 Debt Ratio       Rating
 ≤ 5%             A
 6–10%            B
 11–20%           C
 21–50%           D
 > 50%            E
```

The point is *relative*: 100 hours of debt in a 1M-line system is fine (A); the same debt in a 5K-line system is alarming (E). This lets you compare components of different sizes fairly. The trade-off is that the per-rule remediation times are heuristics, so the absolute "5 days of debt" number is directionally useful, not literally bankable — treat trends and ratings, not the raw hour count, as the signal.

### Q9. [Practical] How do you configure branch and pull-request analysis, and what shows up where?

PR analysis is the highest-leverage Sonar feature for day-to-day work. The scanner is told it's analyzing a PR, computes issues only against the diff (new code = the changed lines), and **decorates the PR** in GitHub/GitLab/Bitbucket/Azure DevOps with the gate status and inline comments.

```bash
mvn verify sonar:sonar \
  -Dsonar.pullrequest.key=1234 \
  -Dsonar.pullrequest.branch=feature/login \
  -Dsonar.pullrequest.base=main
```

For a long-lived branch instead of a PR, you use `-Dsonar.branch.name=main`. Key points: branch and PR analysis require **Developer Edition or above** (or SonarCloud) — the free Community Edition analyzes only a single branch. PR results are *short-lived* (purged after merge/close); the main branch carries the durable history. A common workflow is: PR build runs the gate as a *required status check* so a failing gate blocks merge; the post-merge `main` build updates the long-term metrics.

### Q10. [Practical] Walk through wiring SonarQube into a CI pipeline so a failing gate breaks the build.

The subtlety is that the scanner returns success once it *uploads*; the gate is computed server-side asynchronously. You must poll for the gate result. Two approaches: enable the scanner's built-in wait, or use the CI plugin's "quality gate" step.

```yaml
# GitHub Actions sketch
- name: Build, test, scan
  env:
    SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
  run: |
    mvn -B clean verify sonar:sonar \
      -Dsonar.qualitygate.wait=true \      # block until gate computed, fail if red
      -Dsonar.qualitygate.timeout=300
```

```
 Pipeline flow:
 ┌────────┐   ┌──────┐   ┌─────────────┐   ┌────────────────┐   ┌──────────┐
 │ build  │──▶│ test │──▶│ JaCoCo XML  │──▶│ sonar:sonar    │──▶│ poll gate│
 │ compile│   │      │   │ generated   │   │ (upload)       │   │ wait=true│
 └────────┘   └──────┘   └─────────────┘   └────────────────┘   └────┬─────┘
                                                                       │
                                                       red ◀───────────┴──▶ green
                                                    (fail job)         (proceed/deploy)
```

**Production trade-off:** `sonar.qualitygate.wait=true` keeps the CI agent busy polling, which costs runner minutes; for very large monorepos some teams run the gate as a non-blocking informational step on `main` and only block on PRs. Always store the token as a masked secret and scope it to a project-level "analysis token" (introduced in SonarQube 9.x) rather than a broad user token.

### Q11. [Coding] Write Java code that triggers a bug, a vulnerability, and a code smell, then explain the clean version.

**Problem:** Demonstrate one issue of each category and the remediation.

```java
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

public class AccountService {

    // CODE SMELL: unused parameter + magic number; raises cognitive complexity
    // BUG: potential NullPointerException — 'name' may be null before .equals
    public boolean isAdmin(String name, int unusedFlag) {
        return name.equals("admin");          // NPE if name == null
    }

    // VULNERABILITY: SQL injection via string concatenation
    public void deleteUserUnsafe(Connection conn, String userId) throws Exception {
        Statement st = conn.createStatement();
        st.execute("DELETE FROM users WHERE id = '" + userId + "'"); // tainted input
    }
}
```

**Clean version:**

```java
public class AccountService {

    private static final String ADMIN = "admin";

    // BUG fixed: null-safe by calling equals on the constant literal
    public boolean isAdmin(String name) {       // dropped unused param (smell)
        return ADMIN.equals(name);
    }

    // VULNERABILITY fixed: parameterized query — input can no longer be code
    public void deleteUser(Connection conn, String userId) throws Exception {
        try (PreparedStatement ps =
                 conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            ps.execute();
        }
    }
}
```

- **Time/Space complexity:** both methods are O(1) time / O(1) space — Sonar's findings are about *correctness and security*, not algorithmic cost. (This is a good interview point: SonarQube does **not** flag a bad Big-O; that's a human review concern.)
- **Edge cases caught:** `name == null` (NPE bug), `userId = "1' OR '1'='1"` (mass-delete via injection). The `PreparedStatement` in a try-with-resources also closes the statement, fixing a potential resource-leak bug Sonar would otherwise raise.

### Q12. [Theory] What is a Quality Profile and how does it relate to rules?

A **Quality Profile** is a named, language-scoped collection of activated rules (with their severities and parameters) applied during analysis — it's the "ruleset." Each language has a built-in read-only profile, **Sonar way**, and you can clone it to create a custom profile, then activate/deactivate rules or tune parameters (e.g., set the "method too long" threshold). One profile per language is marked the **default**; projects use the default unless explicitly assigned a different profile. Profiles can be **inherited** (a child profile extends a parent, so org-wide standards live in the parent and teams override locally). The relationship: *rules* are the catalog of checks; a *profile* is which rules are on and how they're configured for a given project. Keeping profiles minimal and intentional is important — turning on noisy rules organization-wide is the fastest way to make developers tune SonarQube out.

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] How does SonarQube perform deeper security analysis (taint analysis), and what edition is required?

Beyond pattern matching, SonarQube performs **taint analysis** (data-flow security analysis) for injection-class vulnerabilities: it tracks data from **sources** (HTTP params, request bodies, env vars) through **propagators** (string concatenation, assignments) to **sinks** (SQL execution, command exec, file paths, HTML output), and flags a vulnerability only when tainted, unsanitized data reaches a dangerous sink. This is far more precise than "any string concat near SQL is bad" — it can follow data across methods and even files, and recognizes **sanitizers** (e.g., a parameterized API or an HTML encoder) that break the taint path.

```
 SOURCE                PROPAGATOR              SINK
 request.getParam() ──▶ "SELECT ... " + x ──▶ statement.execute()   ⚠ tainted
 request.getParam() ──▶ ps.setString(1,x)  ──▶ ps.execute()         ✓ sanitized
```

Taint analysis (SAST) requires **Developer Edition or higher** (or SonarCloud); the Community/free edition does the rule-based checks but not full cross-procedural taint tracking. This is a frequent licensing surprise in interviews — knowing which capabilities are paid (branch/PR analysis, taint analysis, security reports, portfolio aggregation) signals real operational experience.

### Q14. [Practical] A team complains SonarQube is "too noisy" and they're ignoring it. How do you fix the process, not just the tool?

This is an organizational problem masquerading as a tooling one. My approach:

1. **Adopt new-code focus.** If the gate evaluates overall code on a legacy system, every build is red and people stop looking. Switch the gate to *Clean as You Code* and set the new-code baseline to the reference branch (`main`). Suddenly the gate is achievable per PR.
2. **Prune the Quality Profile.** Audit which rules generate >80% of issues with low value, and deactivate or downgrade chronic false-positive rules. A focused profile beats an exhaustive one.
3. **Make the gate a *blocking* PR check, but only on new code.** Enforcement that's both achievable and mandatory changes behavior; advisory dashboards don't.
4. **Triage legitimately-won't-fix issues** with proper status (`Won't Fix`/`Accept`) and inline `// NOSONAR` only as a last resort with a justifying comment — never a blanket suppression.
5. **Roll out SonarLint Connected Mode** so issues are caught at keystroke time and never reach the PR.
6. **Run a one-time, scoped remediation** for the worst legacy hotspots outside the gate so the backlog isn't infinite.

**Trade-off:** loosening the gate risks letting real issues through; the mitigation is that new-code rules stay strict — you're trading *legacy completeness* for *developer trust and sustained adoption*, which is the right trade because an ignored tool catches nothing.

### Q15. [Coding] Write a custom SonarQube Java rule (plugin) that flags use of `java.util.Date`.

**Problem:** Many teams ban `java.util.Date` in favor of `java.time`. No built-in rule enforces this, so we write a custom rule using the **SonarQube Java analyzer API** (the `org.sonar.plugins.java.api` package), packaged as a plugin JAR dropped into `extensions/plugins/`.

```java
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.Tree;
import java.util.Collections;
import java.util.List;

@Rule(key = "AvoidJavaUtilDate")          // key referenced in the Quality Profile
public class AvoidJavaUtilDateRule extends IssuableSubscriptionVisitor {

    @Override
    public List<Tree.Kind> nodesToVisit() {
        // Subscribe only to 'new X()' nodes — efficient: we skip everything else
        return Collections.singletonList(Tree.Kind.NEW_CLASS);
    }

    @Override
    public void visitNode(Tree tree) {
        NewClassTree newClass = (NewClassTree) tree;
        // Use the semantic model (resolved type), NOT the textual name —
        // this correctly handles imports, aliases, and fully-qualified refs.
        if (newClass.symbolType().is("java.util.Date")) {
            reportIssue(newClass.identifier(),
                "Use java.time (Instant/LocalDateTime) instead of java.util.Date.");
        }
    }
}
```

- **Why subscribe to a node kind?** `IssuableSubscriptionVisitor` only invokes `visitNode` for the kinds you register, so the AST walk is **O(matching nodes)** rather than O(all nodes) of work in your callback — efficient on large files.
- **Why `symbolType().is(...)` over name matching?** Using the **semantic model** resolves the actual type, so `new Date()`, `new java.util.Date()`, and an aliased import all match, while a custom `com.acme.Date` does **not** — eliminating false positives that naive string matching would create.
- **Time/Space:** the visitor is O(n) over AST nodes of the subscribed kind, O(1) extra space per node.
- **Edge cases:** subclasses of `Date` (use `.isSubtypeOf(...)` if you want to catch `java.sql.Date`), and `Date` used only as a type reference (not `new`) — that needs a different node kind (`Tree.Kind.METHOD`/parameter visiting). You register the rule in a `CheckRegistrar`/`RulesDefinition` and ship rule metadata (HTML description + JSON) so it appears in the profile UI.

### Q16. [Theory] How does SonarQube store and scale its data, and what are the operational considerations?

SonarQube's architecture is a **web server (Java)**, a **compute engine** (background worker that processes analysis reports and computes measures/gate), and an embedded **Elasticsearch** instance for issue/code search, all backed by a relational database (PostgreSQL is the supported production DB; the embedded H2 is dev-only).

```
   Scanner (CI) ── report ──▶ ┌─────────────────────────────────────┐
                              │  SonarQube Server                    │
                              │  ┌──────────┐   ┌─────────────────┐  │
                              │  │ Web (UI) │   │ Compute Engine  │  │
                              │  └────┬─────┘   └────────┬────────┘  │
                              │       │                  │           │
                              │   ┌───▼───┐         ┌────▼─────┐     │
                              │   │  ES   │         │PostgreSQL│     │
                              │   └───────┘         └──────────┘     │
                              └─────────────────────────────────────┘
```

Operational considerations: the **compute engine is the bottleneck** — analysis reports queue and are processed serially per project, so a flood of PR builds can lag; you scale by giving the CE more workers (paid editions) and more heap. **Elasticsearch needs `vm.max_map_count` raised and dedicated heap**, and its indices grow with issue count. Database growth is driven by issue history and number of analyses; long retention plus thousands of short-lived PR branches bloats it, so configure branch/PR housekeeping to purge aggressively. For HA and horizontal scale, the **Data Center Edition** clusters the app and search nodes — but most orgs run a single well-resourced node and treat it as a tier-2 service (its outage blocks merges only if the gate is a hard required check, which is a design decision worth making consciously).

### Q17. [Practical] How do you analyze a polyglot monorepo, and what about generated code or vendored dependencies?

For a monorepo you typically run **one analysis per deployable project/module** (each with its own `projectKey`) rather than one giant scan, so gates, history, and ownership map to teams. Within a project, control scope precisely:

```properties
sonar.sources=src/main
sonar.tests=src/test
# Exclude generated, vendored, and migration code from ALL analysis:
sonar.exclusions=**/generated/**,**/*.pb.go,**/migrations/**,**/vendor/**
# Keep these in scope but exempt from COVERAGE requirements:
sonar.coverage.exclusions=**/config/**,**/dto/**,**/*Application.java
# Exempt specific files from DUPLICATION detection:
sonar.cpd.exclusions=**/generated/**
```

The key distinction interviewers look for: `sonar.exclusions` removes files from analysis entirely (they vanish from metrics), whereas `sonar.coverage.exclusions` keeps them analyzed for bugs/smells but stops them dragging down the coverage percentage — appropriate for DTOs, config, and bootstrapping code that has no meaningful logic to test. Generated code (protobuf, OpenAPI clients, Lombok-expanded sources) should be fully excluded because you can't act on its issues. For polyglot repos, each language uses its own Quality Profile and may need its own scanner inputs (e.g., a JS/TS project needs `lcov.info`, a .NET project must use the **SonarScanner for .NET** with begin/end wrapping `dotnet build`).

---

## 🔴 Expert (15+ yrs)

### Q18. [Theory] What are the fundamental limitations of SonarQube, and how do they shape where you rely on it?

SonarQube is a powerful *static* tool, and its limits follow directly from that:

1. **No runtime/behavioral knowledge** — it can't find race conditions that only manifest under concurrency, performance regressions, memory leaks under load, or logic bugs where the code is internally consistent but wrong against requirements. Those need tests, profiling, and review.
2. **Heuristic debt and false positives** — remediation times are estimates; complex code legitimately needs `// NOSONAR` or "won't fix," and over-trusting the numbers leads to gaming.
3. **Coverage ≠ quality** — Sonar reports coverage but a line being executed by a test with no assertions counts as covered. High coverage with weak tests is a false sense of safety.
4. **Security is necessary, not sufficient** — taint analysis (SAST) catches injection-class flaws but not business-logic authorization bugs, broken object-level access control, or vulnerable *dependencies* (that's SCA — Sonar added some dependency risk features, but dedicated SCA/DAST tools and threat modeling are still required).
5. **Language/framework gaps** — newer language features, DSLs, and dynamic metaprogramming reduce analysis precision.

The mature stance: SonarQube is one layer in a defense-in-depth quality strategy alongside tests, code review, SCA (dependency scanning), DAST, performance testing, and architecture review. Treating its green gate as "this is safe to ship" is the senior-level anti-pattern to avoid.

### Q19. [Practical] You're rolling SonarQube out across 400 repos in a large org. Design the governance model.

**Scenario → approach → trade-offs.** A flat rollout where every team configures its own profile and gate produces chaos and undermines comparability. My design:

```
                         ┌────────────────────────────┐
                         │  Org-wide "Parent" Profile  │  (security + reliability
                         │  + Mandatory Quality Gate    │   rules, non-negotiable)
                         └───────────┬─────────────────┘
                            inherit / extend
              ┌──────────────────────┼───────────────────────┐
      ┌───────▼───────┐      ┌───────▼───────┐       ┌────────▼───────┐
      │ Payments team │      │ Platform team │       │ Mobile team    │
      │ child profile │      │ child profile │       │ child profile  │
      │ (extra rules) │      │ (extra rules) │       │ (extra rules)  │
      └───────────────┘      └───────────────┘       └────────────────┘
```

- **Inherited profiles:** a locked parent profile carries mandatory security/reliability rules; teams extend it but cannot weaken the baseline. Governance owns the parent.
- **A standard org Quality Gate** ("Sonar way" tuned) applied by default, with a documented exception process for the rare project that needs a variant.
- **Onboarding as code:** ship a reusable CI template/shared library (GitHub Actions reusable workflow, Jenkins shared library) so a repo is onboarded by including 5 lines, not hand-configuring scanners — this is the only way to reach 400 repos.
- **Portfolios (Enterprise Edition)** to aggregate gate status by business unit for execs, plus **per-project analysis tokens** for least-privilege.
- **Phased enforcement:** start advisory (report only), publish a leaderboard, then flip the gate to blocking on new code after a grace window so teams aren't ambushed.

**Trade-offs:** centralization improves consistency and security posture but creates a governance team as a potential bottleneck and source of friction; the mitigation is making the baseline *small and clearly justified* (security/reliability only) and giving teams freedom above it. The single Sonar instance also becomes a tier-1 dependency at this scale — Data Center Edition for HA, or accept that a Sonar outage degrades (not blocks) merges by making the gate a soft check during incidents.

### Q20. [Behavioral] Describe a time you had to convince a skeptical team to adopt a quality gate. How did you handle resistance?

Strong answers use a structure like **STAR** and center on empathy and data, not authority. Example narrative: *Situation* — a product team viewed SonarQube as bureaucratic gatekeeping after an earlier rollout failed because the gate enforced legacy debt and was perpetually red. *Task* — re-introduce it without losing the team. *Action* — I ran a workshop showing the *Clean as You Code* model, switched the gate to new-code-only so their existing 18k issues became informational, pruned ~40 noisy rules they'd specifically complained about, and piloted on one service for two sprints with me personally triaging false positives. I let *them* see SonarLint catch a real NPE before commit. *Result* — the pilot caught two production-class bugs in PR, the team voted to make the gate blocking, and we templated the config for the other six services. **The meta-point interviewers want:** you treated resistance as legitimate signal (the tool *was* misconfigured), you reduced friction before demanding compliance, and you let evidence rather than mandate drive adoption. Quality tooling succeeds as a developer aid, not a compliance cudgel.

### Q21. [Theory] How does SonarQube fit into a DevSecOps / shift-left strategy, and where does it explicitly NOT fit?

In a shift-left model, defects are cheapest to fix the earliest they're caught, and SonarQube + SonarLint cover two of the leftmost stages:

```
 IDE (SonarLint) ─▶ PR (Sonar gate + SAST) ─▶ Build (SCA/deps) ─▶ Pre-prod (DAST) ─▶ Prod (RASP/monitoring)
   seconds              minutes                  minutes             hours            continuous
   cheapest ◀──────────────────────────────────────────────────────────────────────▶ costliest to fix
```

SonarQube owns the **IDE and PR stages**: keystroke linting (SonarLint Connected Mode), and PR-gate SAST/taint analysis + maintainability + coverage enforcement as a required check. It explicitly does **not** own: **SCA** (known-vulnerable third-party dependencies — historically a separate tool's job, though Sonar has been adding dependency-risk capabilities), **DAST** (running-app probing), **secrets at rest across history** (it scans code but you still need dedicated secret-scanning and rotation), **IaC posture and runtime/cloud config** (a partial overlap with its IaC rules but not a substitute for CSPM), and **container/image scanning**. The expert framing: SonarQube is the *code-correctness and code-level-security* gate in DevSecOps, and a security program that relies on it alone has a false sense of coverage — pair it with SCA, DAST, secret scanning, and threat modeling for genuine defense in depth.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q22. [Theory] What is static analysis (SAST) and how does it fundamentally differ from dynamic analysis (DAST)?

Static Application Security Testing (SAST) — what SonarQube does — analyzes source code, bytecode, or an abstract representation of the program **without running it**. It reasons about every possible path through the code by modeling its structure. Dynamic Application Security Testing (DAST) does the opposite: it runs the application (or sends it requests) and observes its *behavior*, so it only sees the paths that are actually exercised. The two are complementary, not competing, and an interviewer wants you to articulate *why* each catches what the other cannot.

```
 SAST (SonarQube)                          DAST (e.g. OWASP ZAP)
 ─────────────────                         ─────────────────────
 Sees ALL code paths (incl. dead/rare)     Sees only EXECUTED paths
 No deployment needed                      Needs a running instance
 Knows exact file + line                   Knows the HTTP symptom, not the line
 Blind to runtime/config/env issues        Catches misconfig, auth, runtime
 False positives (no runtime context)      False negatives (untested paths)
 Runs in seconds–minutes on the diff       Slower; needs test traffic
```

The deeper point is the trade-off in *certainty vs coverage*. SAST has total **code coverage** (it sees lines no test ever hits) but low **certainty** (it doesn't know if a path is reachable with real data, hence false positives). DAST has high certainty (it observed a real failure) but partial coverage (it only probed what it could reach). This is exactly why a mature pipeline runs SonarQube early (cheap, broad) and DAST later (expensive, confirmatory) — neither alone is sufficient.

#### Q23. [Theory] What is the difference between syntactic and semantic analysis, and why does SonarQube need both?

Syntactic analysis is purely about *structure* — "does this match a textual/grammatical pattern?" Semantic analysis resolves *meaning* — types, symbol bindings, what a variable actually refers to. A rule like "remove the trailing whitespace" or "this `if` has no braces" is syntactic and needs only the parse tree. A rule like "you're calling `.equals()` on something that's always null here" or "this `Date` is `java.util.Date`, not `com.acme.Date`" is semantic and requires a resolved type/symbol model. SonarQube's analyzers build both: first a **parse tree / AST**, then a **semantic model** layered on top (type resolution, symbol table, control-flow graph).

```
 Source ──▶ Lexer ──▶ Parser ──▶ AST ──▶ Semantic model ──▶ Rules run on both
            tokens             (structure) (types, symbols,    (syntactic +
                                            control flow)        semantic checks)
```

The reason this matters in interviews is precision. Naive grep-style (syntactic-only) rules generate enormous false positives because they can't tell `import java.util.Date` apart from a custom class named `Date`. The semantic model is what lets SonarQube say "this *specific resolved type* is the JDK Date." It's also why semantic-dependent rules silently degrade if the analyzer can't resolve types — e.g., for Java you must supply compiled bytecode via `sonar.java.binaries`; without it, type resolution is incomplete and many rules quietly turn off or produce noise. Knowing that "no binaries → degraded semantic analysis" is a strong signal of hands-on experience.

#### Q24. [Theory] Why does SonarQube need compiled bytecode (sonar.java.binaries) for Java but not for some interpreted languages?

For Java, SonarQube's most valuable rules depend on the **semantic model**, and building an accurate semantic model for Java requires resolving types across the classpath — including types you don't have source for (third-party libraries, the JDK itself). The analyzer reads `.class` files (your compiled output plus dependencies on `sonar.java.libraries`) to resolve those types. If you only pass source, the analyzer can parse the syntax but can't reliably answer "what type does this expression have?", so cross-procedural and type-sensitive rules degrade or disable. That's why a Java scan **must run after `compile`/`verify`** and why `sonar.java.binaries` pointing at `target/classes` is mandatory.

```
 Java:        source + bytecode (.class) + libs ──▶ full type resolution ──▶ all rules
 (compiled)   missing bytecode            ──▶ partial/no semantics ──▶ rules disabled/noisy

 JS/Python:   source only ──▶ analyzer's own type inference ──▶ rules run
 (dynamic)    (no separate compile artifact exists to point at)
```

For dynamically typed languages like JavaScript or Python there is no separate compiled artifact to point at, so the analyzer does its own best-effort type *inference* from the source and (for JS/TS) can consume `tsconfig.json` and type definitions to sharpen it. The deeper theory point: the dependency on bytecode isn't a quirk — it's a direct consequence of where the *type truth* lives. In compiled languages it lives in the artifact; in dynamic languages it must be inferred. This also explains why Java analysis precision is generally higher than dynamic-language precision: it has ground-truth types rather than inferred ones.

### 🟡 Intermediate — extended

#### Q25. [Theory] How does SonarQube track the same issue across multiple analyses, and why is issue "fingerprinting" hard?

If issue tracking were naive (match on file + line number), every time you added a line at the top of a file, every issue below would appear to "close" and a brand-new identical issue would "open" — destroying history, comment threads, assignees, and "won't fix" decisions. SonarQube avoids this with a **tracking algorithm** that matches issues between the previous analysis and the new one using multiple signals, in priority order: the rule key, a **line hash** (a hash of the *content* of the line, not its number), the issue message, and proximity. So if a block of code moves down 10 lines but its text is unchanged, the line-hash matches and the issue is recognized as the *same* issue, preserving its entire lifecycle.

```
 Analysis N            Analysis N+1 (code shifted +10 lines)
 ──────────            ─────────────────────────────────────
 line 42, hash=ab12 ── matched by hash ──▶ line 52, hash=ab12   SAME issue (history kept)
 line 80, hash=cd34 ── no match found  ──▶ (gone)               CLOSED
 (none)             ── new hash ──────────▶ line 90, hash=ef56   NEW issue
```

The hard part is that code edits are ambiguous: a changed line could be "the same issue, slightly edited" or "the old one fixed and a new one introduced." The algorithm uses the closest-match heuristics, and it's intentionally biased toward *preserving* identity so that human decisions (assignee, won't-fix, comments) survive refactors. This is also why SonarQube integrates with **SCM blame** — it can attribute issues and new-code lines to commits/authors, which both improves tracking robustness and powers "introduced in this PR." When interviewers ask "what happens to my 'won't fix' when I reformat the file?", the answer is: line-hash tracking usually preserves it; a true content change may reopen it.

#### Q26. [Theory] How does SonarQube detect code duplication, and what are the limits of that algorithm?

SonarQube's duplication detection (historically "CPD" — Copy/Paste Detector) is **token-based**, not text-based. It tokenizes the source (stripping whitespace, comments, and often normalizing literals), then slides a window over the token stream and looks for sequences of duplicated tokens that exceed a language-specific minimum threshold (e.g., ~100 tokens / 10 lines for Java). Because it works on tokens, reformatting, comment changes, and renamed *variables don't necessarily* break detection in the way a raw text diff would — but it is fundamentally a **Type-1 / Type-2 clone** detector: it finds exact or near-exact copies, not *semantic* duplication.

```
 File A tokens: [if][(][x][>][0][)][return][a][+][b] ...
 File B tokens: [if][(][y][>][0][)][return][a][+][b] ...   ← matching token run ≥ threshold ⇒ duplicate block
```

The crucial limitation interviewers probe: it does **not** find "two methods that do the same thing written differently" (Type-4 / semantic clones). A bubble sort and a quicksort, or two validators with the same intent but different structure, are invisible to it. It also has a minimum-size threshold, so small idiomatic repeats (getters, simple guards) are deliberately ignored to avoid noise. The practical consequence: a 0% duplication metric does not mean DRY code — it means no large copy-paste blocks. Conversely, generated code or boilerplate can spike duplication artificially, which is why `sonar.cpd.exclusions` exists. Understanding that duplication is a *token-window* heuristic, not a semantic-similarity engine, is the depth signal here.

#### Q27. [Theory] Explain cognitive complexity vs cyclomatic complexity. Why did SonarSource invent cognitive complexity?

Cyclomatic complexity (McCabe, 1976) counts the number of **linearly independent paths** through code — essentially +1 for every branch point (`if`, `for`, `while`, `case`, `&&`, `||`, `catch`). It's great for one thing: it tells you the minimum number of test cases to cover all branches. But it's a poor proxy for *how hard code is for a human to understand*, because it treats a flat `switch` with 12 cases (very readable) the same as three levels of nested loops-with-conditionals (very hard), and it doesn't penalize **nesting** at all.

```
 Method A (cyclomatic 4, cognitive 1)     Method B (cyclomatic 4, cognitive 6)
 switch(x){                               if (a) {            +1
   case 1: ...                              while (b) {       +2 (nesting +1)
   case 2: ...                                if (c) {        +3 (nesting +2)
   case 3: ...           +3 (one each)          ...
 }                                            }
                                            }
                                          }
 Same cyclomatic number, very different mental load ────────▶
```

SonarSource invented **cognitive complexity** to measure *understandability* directly. Its scoring rules: (1) increments for each break in linear flow (loops, conditionals, catches), (2) an **extra increment for each level of nesting** (a deeply nested `if` costs more than a top-level one), and (3) it **ignores** structures that don't actually add mental load, like a `switch` statement (counted once, not per case) and shorthand the brain processes easily. The result correlates far better with subjective "this method is hard to follow." In interviews, the key takeaway is the *motivation*: cyclomatic optimizes for test-case counting; cognitive optimizes for human readability, and that's why SonarQube's "method too complex" smell uses cognitive complexity, while it still reports cyclomatic for the testing perspective.

#### Q28. [Theory] What exactly defines the "new code period," and how do the four definitions differ semantically?

The new code period is the baseline against which "new code" (the thing the Quality Gate enforces) is computed. There are four definitions, and they differ in *what event resets the baseline*:

```
 Definition           Baseline = ...                         Best for
 ──────────────────   ────────────────────────────────────   ─────────────────────────
 Previous version     last value of sonar.projectVersion     Release-based teams; new
                      (resets when you bump the version)      code = everything since last release
 Number of days       a sliding window (e.g., last 30 days)   "leak period" / continuous flow;
                                                              code older than N days "ages out"
 Specific date        a fixed calendar date                   one-off campaigns / hard cutover
 Reference branch     the diff against another branch (main)  trunk-based dev & PR analysis
```

The subtle distinction is **sliding vs fixed vs diff**. "Number of days" is a *sliding window* — yesterday's new code silently becomes old code in 30 days, which is great for continuous-delivery shops but means an issue you ignored can quietly leave the gate's scope. "Previous version" is *event-driven* — nothing ages out until you bump `sonar.projectVersion`, so it aligns to releases. "Reference branch" isn't time-based at all; it computes new code as the **SCM diff** between the branch and its reference (e.g., `main`), which is the only definition that maps cleanly onto "the lines this PR changed." For modern trunk-based teams, reference-branch is the right default because it makes "new code" identical to "what's in this PR," eliminating arguments about whether something is in scope.

#### Q29. [Theory] What is the "MQR mode" / new clean-code taxonomy (software qualities, severities, impacts) introduced in recent SonarQube versions, and how does it differ from the legacy model?

Starting around SonarQube 10.2 and made the default in newer versions (the 2025 line), SonarSource introduced **Multi-Quality Rule (MQR) mode** and a reworked clean-code taxonomy. The legacy model gave each issue *one* type (Bug / Vulnerability / Code Smell) and *one* severity (Blocker → Info). The problem: a single rule violation often harms *multiple* software qualities at once — e.g., a resource leak hurts both **Reliability** and indirectly maintainability — and forcing it into one bucket loses information.

```
 LEGACY (Standard Experience)             MQR MODE (new taxonomy)
 ────────────────────────────            ──────────────────────────────────────
 Type:     Bug | Vuln | Code Smell       Each rule maps to one or more
 Severity: Blocker..Info (single)        SOFTWARE QUALITIES: Security,
                                          Reliability, Maintainability
                                         Each with an IMPACT: Blocker, High,
                                          Medium, Low, Info
                                         Rules also tagged with clean-code
                                          ATTRIBUTES (consistent, intentional,
                                          adaptable, responsible)
```

So in MQR mode an issue is described as "this violates the **Reliability** quality with a **High** impact," and a single rule can declare impacts on several qualities. The clean-code *attributes* (e.g., "intentional," "adaptable") add a second axis describing *why* it's a problem. The trade-off and migration concern: dashboards, the web API, and quality-gate conditions that referenced the old Bug/Vulnerability/Smell counts behave differently, so when you upgrade you must decide between **Standard Experience** (legacy) and **MQR mode**, and update any gate conditions or scripts that assumed the old metric names. Knowing this version delta is a strong recency signal — it's one of the biggest conceptual changes in SonarQube in years.

#### Q30. [Theory] What is the difference between issue severity and the new "impact," and how should each drive triage?

In the legacy/Standard Experience model, **severity** (Blocker, Critical, Major, Minor, Info) is a single property attached to a rule, meant to express "how bad is this." In MQR mode that's replaced/augmented by **impact**, which is a pair of (software quality, impact level) — so the *same* rule can be "High impact on Reliability" and "Low impact on Maintainability" simultaneously. The conceptual shift is from "one global badness number" to "badness *per quality dimension*," which lets triage be quality-aware: a security team can prioritize anything with a High impact on Security regardless of its maintainability impact.

The triage implication is what interviewers care about. With a single severity you tend to triage top-down by Blocker → Info and conflate "hard to maintain" with "will crash in prod." With impacts you can build gate conditions and dashboards per quality — e.g., block the PR on *any* High-or-above **Security** or **Reliability** impact while treating **Maintainability** impacts as advisory on new code. That mirrors real risk: a security hole and a long method are not the same kind of problem and shouldn't share one priority axis. The practical caution is consistency — mixing both models across projects (some Standard, some MQR) makes org-wide reporting confusing, so pick one mode org-wide.

#### Q31. [Theory] How does a remediation function work, and what are the three function types that drive the technical-debt estimate?

Every maintainability rule carries a **remediation function** — the formula SonarQube uses to estimate how long fixing one occurrence takes, which then rolls up into the project's total technical debt and Maintainability Rating. There are three types:

```
 Function type     Cost formula                     Example rule
 ───────────────   ──────────────────────────────   ─────────────────────────────
 Constant/issue    fixed cost per occurrence         "Remove this unused import" = 5 min
 Linear            cost × N (some counted element)    "Split this method": 1 min per
                                                       line over the threshold
 Linear + offset   base + (cost × N)                  "Reduce complexity": 10 min base
                                                       + 1 min per extra complexity point
```

The reason there are three is that not all debt scales the same way. Removing an unused variable is a fixed, small job no matter the file (constant). Breaking up a 400-line method costs roughly in proportion to *how much* over the limit it is (linear). Reducing an over-complex method has a fixed cost to even start plus a per-unit cost (linear with offset). These per-rule estimates are what make the headline "3 days of technical debt" number; the deep point is that the number is a **sum of heuristic formulas**, so it's directionally useful for comparing components and tracking trends but not a literal engineering estimate. This is also why two projects with the same issue count can show very different debt — they triggered rules with different remediation functions and magnitudes.

### 🟠 Advanced — extended

#### Q32. [Theory] Describe SonarQube's internal pipeline from "scanner runs" to "gate computed." Where does each step happen and what's synchronous vs asynchronous?

The end-to-end flow has a clear client/server split, and the synchronous/asynchronous boundary is the single most misunderstood part in CI design.

```
 ── On the CI agent (scanner side, synchronous to your build) ──
 1. Scanner reads config + sources, runs language analyzers (parse → AST → semantic model)
 2. Rules execute locally; issues, measures, coverage import, duplication all computed
 3. Scanner SERIALIZES everything into a "scanner report" (protobuf bundle)
 4. Scanner UPLOADS the report to the server  ──── scanner's job ends here (returns success) ───┐
                                                                                                │
 ── On the SonarQube server (asynchronous, the Compute Engine) ──                               │
 5. Report lands in the CE QUEUE (one task per analysis, processed per-project serially) ◀──────┘
 6. CE persists issues to the DB, runs issue TRACKING vs previous analysis, indexes into ES
 7. CE computes measures, applies the Quality Profile deltas, then EVALUATES the Quality Gate
 8. Gate status stored; webhooks fire; PR decoration posted
```

The critical insight: **most analysis work happens on the scanner/CI agent, not the server** — the server's job is persistence, tracking, indexing, and gate evaluation. And step 4→5 is the async seam: the scanner returns "success" the moment the upload completes, *before* the gate exists. That's exactly why CI that just checks the scanner's exit code will pass even on a red gate, and why you need `sonar.qualitygate.wait=true` (which makes the scanner poll the CE task API until the gate is computed). The Compute Engine processing per-project serially is also why a burst of PR builds can lag — the bottleneck is CE throughput, scaled by adding CE workers (paid editions) and heap. Being able to point at the protobuf report + CE queue as the architecture is a senior-level answer.

#### Q33. [Theory] What role does the embedded Elasticsearch play, and what breaks if it's unhealthy — versus what breaks if PostgreSQL is down?

SonarQube ships an **embedded Elasticsearch** node whose job is *search and fast aggregation*, not durable storage. The **system of record is PostgreSQL** — issues, measures, snapshots, configuration all live there. Elasticsearch is a derived index built from the DB to make the "Issues" search page, faceted filtering, and project/portfolio aggregations fast. The distinction matters because the two failure modes are very different:

```
 PostgreSQL down/corrupt   ──▶ DATA LOSS risk; analyses can't persist; server won't start.
                               This is your backup/restore concern. Authoritative truth.

 Elasticsearch unhealthy   ──▶ NO data loss; search/UI degraded or failing; but ES can be
                               REBUILT from the DB (reindex). Derived, regenerable.
```

Operationally this drives where you spend effort. You back up and replicate **PostgreSQL** religiously because it's irreplaceable; you size and monitor **Elasticsearch** for performance and can recover it by reindexing from the DB if an index corrupts. ES is also why SonarQube needs `vm.max_map_count` raised, a dedicated chunk of heap, and a real filesystem (it mmaps index files) — and why you should never put the ES data dir on a network filesystem. A frequent interview "gotcha": "if Elasticsearch index is corrupt, did I lose my history?" — no, you reindex from PostgreSQL; the history was never in ES. Conflating the derived search index with the authoritative store is the mistake this question is fishing for.

#### Q34. [Theory] How do SonarQube webhooks work and why are they preferable to polling for gate results in some architectures?

A **webhook** is an HTTP POST that the SonarQube server sends to a configured URL *after* the Compute Engine finishes processing an analysis and computing the gate. The payload includes the project, the analysis timestamp, the gate status (OK/ERROR) and the failing conditions. This is the **push** counterpart to `sonar.qualitygate.wait=true`, which is **pull** (the scanner polls the CE task endpoint).

```
 PULL (qualitygate.wait)              PUSH (webhook)
 ───────────────────────             ───────────────────────────────────
 Scanner blocks & polls CE task API  Scanner returns immediately
 Holds the CI runner the whole time  Frees the runner; CE notifies later
 Simple; good for one linear job      Good for fan-out, dashboards, async
 Couples build duration to CE queue   Decouples; needs a receiver endpoint
```

Webhooks are preferable when you don't want to hold an expensive CI runner idle while the CE queue drains (large monorepos, many concurrent PRs), or when something *other than the build* needs to react — a deployment orchestrator, a ChatOps notifier, a metrics collector, or a custom merge-gating service. The trade-off is that push requires a reachable receiver and you must handle delivery semantics: webhooks should be treated as *at-least-once* (verify the payload signature with the shared secret, and make handlers idempotent because retries can duplicate). A subtle correctness point interviewers like: a webhook fires per *analysis*, so in PR-decoration setups you wire the gate result to the PR via the webhook payload's project/branch/PR identifiers rather than assuming a single global status.

#### Q35. [Theory] How does SonarQube map its rules to external security standards (OWASP Top 10, CWE, PCI, etc.), and why does that mapping matter operationally?

Each security-relevant rule carries metadata tagging it with one or more **external standard references** — most commonly **CWE** (Common Weakness Enumeration) IDs, **OWASP Top 10** categories, OWASP ASVS, SANS Top 25, and in commercial editions compliance frameworks like PCI DSS. SonarQube's **Security Reports** then aggregate findings *by standard*: "show me everything that maps to OWASP A03:2021 Injection" or "all CWE-89 (SQL injection) issues." The mapping isn't cosmetic — it's the bridge between a code finding and the language auditors, security teams, and compliance regimes actually speak.

```
 Rule (taint: SQL injection) ──tagged──▶ CWE-89 ──rolls up──▶ OWASP A03:2021 Injection
                                                  └─────────▶ shows in PCI/ASVS report
```

Operationally this matters for three reasons. First, **auditability**: a security report grouped by OWASP/PCI is what you hand to an auditor, instead of a raw issue list they can't interpret. Second, **prioritization**: you can write quality-gate or policy conditions around standard categories (e.g., "zero new CWE Top 25 issues") rather than rule-by-rule. Third, **coverage gaps**: because the mapping is explicit, you can reason about which OWASP categories SonarQube *does* and *does not* cover — it's strong on injection (A03) and some misconfig, but A01 broken access control and A04 insecure design are largely business-logic problems SAST can't see, reinforcing that the OWASP report is a coverage map, not a clean bill of health. The senior framing: the standards mapping turns SonarQube from a developer tool into something the security/compliance org can consume, but you must read it as "what SAST can attest to," not "we're OWASP-compliant."

#### Q36. [Theory] What is the difference between a "security hotspot" and a "vulnerability" at the analysis-engine level, and why does Sonar deliberately not auto-convert hotspots?

At the engine level the distinction is about **confidence and context**. A **vulnerability** is emitted when the analyzer (often via taint analysis) can establish that dangerous data reaches a dangerous sink along a real path — it's making a *verdict*. A **security hotspot** is emitted for *security-sensitive code* where the analyzer can see the sensitive API is used but **cannot determine from the code alone whether it's actually exploitable** — that depends on context only a human knows (Is this `Random` used for a token or for a game? Is this permissive CORS intentional for a public API?). So a hotspot is a *prompt for review*, deliberately not a verdict.

```
 Taint reaches sink, provably exploitable   ──▶ VULNERABILITY  (engine asserts)
 Sensitive API used, exploitability depends  ──▶ HOTSPOT        (engine asks a human)
 on context the code can't reveal                 → reviewer marks: Safe | Fix (→ becomes
                                                     a tracked issue/vulnerability)
```

Sonar deliberately *doesn't* auto-promote hotspots to vulnerabilities because doing so would either flood the team with false positives (treat every `Random` as a crypto vuln) or, if it guessed conservatively, hide real ones. The hotspot model trades automation for **calibrated trust**: a small, reviewable set of "look at these on purpose" items, each with educational guidance, keeps developers engaged instead of desensitized. This is also why hotspots have their **own review workflow and their own gate condition** ("100% of new hotspots reviewed") separate from the vulnerability count — you're enforcing *that a human looked*, not *that the count is zero*. Understanding hotspots as a confidence-management mechanism (not a weaker vulnerability) is the depth here.

#### Q37. [Practical] How does the SonarScanner discover and analyze files, and what determines whether a file is "main code," "test code," or excluded entirely?

The scanner builds its file set through a layered resolution: it starts from `sonar.sources` (and `sonar.tests`), expands them, then applies **inclusion/exclusion filters** as glob patterns, and finally hands each surviving file to the language analyzer whose extension it matches. The classification into main vs test is significant because rules, coverage, and duplication all behave differently for test code — e.g., some rules only run on main code, and test files don't count toward coverage *targets*.

```properties
sonar.sources=src/main           # main code root(s)
sonar.tests=src/test             # test code root(s)
sonar.inclusions=**/*.java       # subset of sources to INCLUDE (whitelist)
sonar.exclusions=**/generated/** # remove from analysis ENTIRELY (disappears from metrics)
sonar.test.exclusions=**/*IT.java
# Scope-specific exemptions (file still analyzed for bugs/smells):
sonar.coverage.exclusions=**/dto/**,**/*Config.java
sonar.cpd.exclusions=**/*.pb.go
```

The interviewer is testing whether you understand the *precedence and the semantic difference*. `sonar.exclusions` is the nuclear option — those files leave the analysis universe (no issues, no coverage, no LOC). `sonar.coverage.exclusions` and `sonar.cpd.exclusions` are *scoped* — the file is still analyzed for bugs and smells, it's just exempt from the coverage denominator or duplication detection respectively. Getting this wrong is a classic mistake: people use `sonar.exclusions` on DTOs to fix a coverage number and accidentally stop scanning them for real bugs. A second subtlety is that `inclusions` is a whitelist applied *within* sources, while `exclusions` is a blacklist applied after — and exclusions generally win. For multi-language repos, file→analyzer routing is extension-driven, so a misnamed or templated file (e.g., `.java.tmpl`) may be silently skipped, which is a real-world gotcha worth naming.

#### Q38. [Theory] What changed in SonarQube's versioning/release model (LTS → LTA, the year-based scheme) and why does it matter for an upgrade strategy?

SonarSource historically shipped frequent feature releases plus a designated **LTS (Long-Term Support)** version that was the stable target for cautious orgs. They renamed and restructured this: the long-term line is now called **LTA (Long-Term Active)**, and the product was split/rebranded — the self-hosted server is **SonarQube Server**, the IDE tool is **SonarQube for IDE** (formerly SonarLint), and **SonarCloud** became **SonarQube Cloud**. Recent releases also moved toward a **year-based / calendar-style numbering** (e.g., the 2025.x line) for the commercial editions, signaling cadence more clearly.

```
 Old model                        New model
 ─────────                        ─────────
 Many feature releases            Feature releases continue
 + one "LTS" stable target        + "LTA" (Long-Term Active) stable target
 SonarLint (IDE)                  SonarQube for IDE
 SonarCloud                       SonarQube Cloud
 7.9 / 8.9 / 9.9 style            2025.x calendar-style (Server editions)
```

For upgrade strategy this matters in two concrete ways. First, **upgrade paths are anchored to the LTA**: you generally migrate LTA→LTA (sometimes requiring an intermediate hop) rather than chasing every interim release, because the LTA is where DB migration and plugin compatibility are guaranteed and supported longest. Second, the rebrand and the MQR-mode default (see Q29) mean a major-version jump can change *terminology, default behavior, and API metric names* simultaneously — so an upgrade is not just a binary swap; you plan a DB backup, a staging dry-run of the migration, a plugin-compatibility check, and a review of whether your gates/scripts assume the old taxonomy. Citing LTA-anchored upgrades and the SonarLint→"SonarQube for IDE" rebrand is a clear recency/operations signal.

### 🔴 Expert — extended

#### Q39. [Theory] Why is SonarQube's taint analysis inherently interprocedural, and what makes it computationally expensive and edition-gated?

Real injection vulnerabilities almost never live in a single method — tainted input enters in a controller, gets passed to a service, concatenated in a helper, and finally executed in a DAO. To find these, taint analysis must follow data **across method and file boundaries** (interprocedural data-flow), building and traversing a graph that connects sources to sinks through arbitrarily many intermediate calls, while modeling which operations **propagate** taint and which **sanitize** it. A purely intraprocedural (single-method) analysis would miss the overwhelming majority of real-world cases, which is precisely why naive linters are weak at injection detection.

```
 Controller.handle(req)            Service.process(s)         Dao.run(q)
   String p = req.getParam("id") ─▶  build("..."+s) ─────────▶ stmt.execute(q)
   service.process(p);               return concatenated;       ⚠ source→sink path
                                                                 spans 3 methods/files
```

The cost comes from the combinatorics: the analyzer effectively explores paths through the call graph, and the number of paths can grow super-linearly with call depth and branching — it must also be **path- and context-sensitive** enough to avoid false positives without exploding. That's why taint analysis runs longer than rule-based checks, needs more memory, and is **gated to Developer Edition and above** (and SonarQube Cloud) — it's both a heavier compute feature and a commercial differentiator. The expert nuances: precision depends on the engine knowing the **sanitizers and framework semantics** (it ships models for common frameworks; custom sanitizers may need configuration or they'll yield false positives), and because it's interprocedural it benefits enormously from complete bytecode/type resolution — incomplete `sonar.java.binaries` degrades taint precision just as it degrades semantic rules. Being able to explain *source → propagator → sanitizer → sink* across files, and tie the cost to interprocedural path explosion, is the expert-level answer.

#### Q40. [Theory] Compare SonarQube Server (self-hosted) vs SonarQube Cloud (formerly SonarCloud) at the architectural and trust-boundary level. When does each win?

Both run the same family of analyzers and the Clean-as-You-Code model, but they differ in *where the analysis platform lives and who operates it*, which drives the real decision.

```
 Dimension          SonarQube Server (self-hosted)      SonarQube Cloud (SaaS)
 ─────────────────  ──────────────────────────────────  ──────────────────────────────
 Operations         YOU run JVM + PostgreSQL + ES,       SonarSource runs everything;
                    upgrades, backups, scaling           zero infra for you
 Data location      Your network/VPC; code analyzed       Analysis happens in their cloud
                    on-prem (compliance-friendly)         (scanner sends data out)
 Scaling/HA         Your problem (DCE for clustering)     Elastic, managed
 Pricing model      Per-edition license + your infra      Usage/lines-of-code subscription
 Customization      Full: custom plugins, DB access,      Limited: no arbitrary server-side
                    server config                          plugins, managed config
 Updates            You choose when to upgrade (LTA)       Always latest (no upgrade control)
```

The trust boundary is the crux. **Self-hosted wins** when code cannot leave your network for regulatory/IP reasons, when you need custom plugins or deep server configuration, when you require version pinning to an LTA for change control, or when you already operate stateful services and want one bill. **Cloud wins** when you want zero operational burden, automatic updates and new analyzer capabilities, native cloud-DevOps integration, and elastic scale without standing up PostgreSQL/Elasticsearch and an HA cluster. The senior nuance: self-hosting gives control but makes SonarQube a stateful service *you* must back up (PostgreSQL), scale (CE workers, ES heap), and keep available (it can become a merge-blocking tier-1 dependency); Cloud removes that toil but means your source is analyzed off-prem and you give up upgrade timing and plugin freedom. There's no universal winner — it's a control-vs-toil and data-residency decision.

#### Q41. [Theory] At the plugin/SPI level, how is SonarQube extensible, and what's the difference between a custom rule, a custom plugin, and an external-issues import?

SonarQube's extensibility has three distinct mechanisms at increasing levels of effort, and conflating them is a common imprecision.

```
 Mechanism            What it is                                  Effort / when
 ──────────────────   ─────────────────────────────────────────  ───────────────────────────
 Custom RULE          A check written against a language          Medium: Java code +
 (in a rule plugin)   analyzer's API (e.g. org.sonar.plugins.     RulesDefinition + metadata,
                       java.api), shipped in a plugin JAR          packaged as a plugin
 Custom PLUGIN        A full extension via the plugin SPI: new    High: implements Plugin,
 (broader)            web pages, web-API endpoints, sensors,       registers extension points;
                       new metrics, new languages                   needs API-version compat
 EXTERNAL ISSUES      Import findings from OTHER tools             Low/none: no Sonar code; just
 (no code at all)     (ESLint, checkstyle, PMD, generic format)    point sonar.<tool>.reportPaths
                       so they show in SonarQube's UI               or sonar.externalIssuesReportPaths
```

A **custom rule** subscribes to AST node kinds via something like `IssuableSubscriptionVisitor`, uses the semantic model to avoid false positives, and ships rule metadata (HTML description + JSON) so it appears in Quality Profiles — it runs *inside* SonarQube's analysis. A **custom plugin** is the general SPI: it can register sensors, new metrics, languages, or UI, and must be compiled against a specific plugin-API version (compatibility breaks across major server versions are a real maintenance cost). **External issues** require *no* SonarQube programming at all — you run another linter, output a report in a supported or the generic external-issues JSON format, and tell the scanner to ingest it; those issues appear in SonarQube but are *read-only* (you can't manage their rules in profiles, and they don't get the same lifecycle). The expert distinction: reach for *external issues* first (cheapest — reuse an existing linter), write a *custom rule* when no rule exists and you need it inside the gate with full lifecycle, and build a *full plugin* only when you need new platform capabilities. Knowing the plugin API is version-pinned (so plugins break on major upgrades) is the operational kicker.

#### Q42. [Theory] How does SonarQube quantify coverage internally (line vs branch vs the "Coverage" metric), and why can two projects with identical line coverage have very different quality?

SonarQube does not measure coverage; it imports it — but it then *recomputes a composite* from the raw data, and understanding that composite is the depth here. From the imported report it derives **line coverage** (executed lines ÷ coverable lines) and **branch/condition coverage** (evaluated branches ÷ total branches, e.g., did both the true and false sides of an `if` run). The headline **Coverage** metric is a *combination*:

```
 Coverage = (covered_lines + covered_conditions)
            ─────────────────────────────────────
            (total_lines_to_cover + total_conditions)

 ⇒ a line that ran but whose if-else only took ONE branch is "line-covered"
   but NOT fully "condition-covered" — the composite reflects that gap.
```

So two projects can both show "80% line coverage" while one has 80% branch coverage and the other 30% — the second exercises lines but rarely both sides of its conditionals, meaning its tests barely probe decision logic. The composite Coverage metric pulls those apart by folding conditions into the denominator, which is why a team chasing only line coverage can have a deceptively healthy number with poor real test depth. The even deeper limitation (and the reason "coverage ≠ quality" is a stock SonarQube caution): coverage is an *execution* metric, not an *assertion* metric. A test that calls a method and asserts nothing still marks every executed line and branch as covered. SonarQube has no way to see whether the test actually checked anything — so 100% coverage with assertion-free tests is the classic false-confidence trap. The expert answer ties the math (line vs branch composite) to the semantic blind spot (execution, not assertion).

#### Q43. [Theory] Why does enforcing the Quality Gate only on "new code" mathematically guarantee overall improvement, and what is the failure mode of that guarantee?

The Clean-as-You-Code argument is essentially a *monotonicity* claim. If every change set (PR) is required to introduce **zero new issues** and meet coverage/duplication thresholds on its changed lines, then the codebase's issue density cannot increase from new work, while every time a developer *touches* a legacy file they tend to clean the lines they edit (because those become "new code" subject to the gate). Over time, files are revisited in roughly proportion to how actively they're developed — so the most-changed (highest-risk) code converges toward clean fastest, and the total trends down without ever needing a demoralizing "fix all 12,000 issues" project.

```
 Issue density
   │█████ legacy baseline
   │████ ░ each PR adds ~0 new debt and cleans touched lines
   │███ ░░ hot files (most edited) clean fastest
   │██ ░░░
   │█ ░░░░ ───────────────────────────▶ time / commits
            asymptote: rarely-touched code stays dirty
```

The **failure mode** is exactly that asymptote: the guarantee only improves code that gets *touched*. A stable, never-edited legacy module — often the riskiest, scariest code precisely because nobody dares change it — is never forced clean and can sit at E-rating forever. Clean-as-You-Code also can't fix *architectural* debt (it's line-scoped), and it assumes the new-code baseline is honestly defined (if someone games the reference branch or marks real issues "won't fix," the monotonicity breaks). So the mature practice pairs the new-code gate with **targeted, scheduled remediation campaigns** for high-risk untouched modules — you let the gate handle the 95% that improves organically and spend scarce remediation budget surgically on the dangerous code the gate can't reach. The expert point is naming both the mathematical strength (no new debt ⇒ monotone non-increase from new work) and its precise blind spot (untouched + architectural debt).

#### Q44. [Theory] Where is SonarQube weakest by design — what classes of problems will it structurally never find, and how does that shape a defense-in-depth program?

This is the capstone "know your tool's boundaries" question. SonarQube is a *static, code-level, mostly intraprocedural-with-some-interprocedural* analyzer, and several problem classes fall structurally outside that, not because of missing rules but because the *information isn't in the source*:

```
 Class of problem                         Why SonarQube structurally can't        Owned by
 ───────────────────────────────────────  ──────────────────────────────────────  ──────────────
 Concurrency/race conditions               needs runtime interleavings             stress/property tests
 Performance regressions, leaks under load  needs execution + profiling             load tests, profilers
 Business-logic / authorization bugs        code is internally consistent; "wrong"  review, threat modeling
   (broken access control, IDOR)            is a requirement, not a pattern
 Vulnerable 3rd-party dependencies          flaw is in code you didn't write/scan   SCA / dependency scanning
 Runtime/cloud misconfig, secrets at rest   not (only) in app source                CSPM, secret scanning, DAST
 Semantic duplication / wrong algorithm     token clones ≠ semantic equivalence     review, design
```

The unifying theme is that SonarQube reasons about **code as written**, so anything whose truth lives in *runtime behavior, deployment context, external requirements, or third-party artifacts* is out of scope by construction. Coverage it reports but can't validate assertions; security it analyzes but can't see business-logic authorization; duplication it finds textually but not semantically. The way this shapes a program: treat SonarQube as the **code-correctness and code-level-security layer** and deliberately stack complementary layers — unit/integration/property tests for behavior, **SCA** for dependency CVEs, **DAST** for the running app, **secret scanning** across history, **CSPM/IaC scanning** for cloud posture, and human **threat modeling + code review** for design and authorization. The anti-pattern the question is probing for is treating a green gate as a ship-it security sign-off; the expert stance is that a green gate means "no *statically detectable* code-level defects in new code," which is necessary, valuable, and explicitly *not sufficient*.

#### Q45. [Theory] Why does SonarQube anchor its analysis to SCM blame data, and what degrades if the SCM provider is unavailable during a scan?

SonarQube reads **SCM blame** (per-line author and commit metadata) during analysis, and this isn't a cosmetic feature — it's load-bearing for several core behaviors. First, the **reference-branch new-code definition** computes "new code" as the set of lines changed relative to the reference, which requires knowing each line's commit lineage. Second, blame powers **issue attribution** ("this new issue was introduced in commit X by author Y"), which feeds notifications and accountability. Third, blame *strengthens issue tracking* (Q25): when line content alone is ambiguous, the commit/age metadata gives the tracking algorithm an extra signal to decide same-vs-new.

```
 SCM blame available                  SCM blame missing/unavailable
 ───────────────────                  ──────────────────────────────
 Accurate new-code line set           New-code detection falls back / degrades
 Issue author + introduction date     "unknown" author, no creation date
 Stronger tracking across edits       Tracking relies on content hash only
 "New issues in this PR" reliable     PR scope may be over/under-inclusive
```

For Git, the scanner uses the embedded JGit by default (no external git needed), but in shallow clones or detached-HEAD CI checkouts the blame can be incomplete, which is the real-world failure: a **shallow clone (`--depth 1`)** strips the history blame needs, so new-code detection and dates degrade and issues may show creation date = analysis date. The operational fix is to ensure CI does a full-enough fetch (e.g., `fetch-depth: 0` in GitHub Actions) before scanning. The depth point interviewers like: blame isn't decoration; it's the substrate for new-code scoping and attribution, so CI checkout configuration directly affects analysis correctness.

#### Q46. [Theory] What is the difference between profile inheritance and profile copy, and how does "Sonar way" being read-only shape an org's profile strategy?

When you create a custom Quality Profile you choose between **copying** the parent and **extending (inheriting)** it, and the semantic difference is permanent. A **copy** is a one-time snapshot: it duplicates the activated rules at that moment, after which the two profiles drift independently — future changes to the source don't flow in. An **inherited (extends)** profile maintains a live parent/child link: rules activated in the parent automatically appear in the child, the child can *add* activations on top, but it **cannot deactivate or weaken** what the parent enforces. That asymmetry is the whole governance lever.

```
 COPY:      Sonar way ──snapshot──▶ MyProfile   (no future link; diverges freely)
 EXTENDS:   Parent(locked) ──live──▶ ChildA ──live──▶ ChildB
                │ activates security+reliability (mandatory)
                └─ children may ADD rules, never REMOVE parent's
```

Because **Sonar way is built-in and read-only**, you can't edit it directly — you must copy or extend it. The strategic implication (and the reason this is an internals question, not just a how-to): inheritance lets a central platform/security team own a **locked parent profile** with non-negotiable rules, while teams *extend* it to add domain-specific checks but are structurally prevented from disabling the baseline. Copy-based profiles can't give that guarantee — a team could silently drop your security rules. So the org-scale answer is "inherit from a locked parent, never distribute copies," which ties directly to the governance model: the inheritance mechanism is *how* you enforce a baseline across hundreds of repos without policing each one manually.

#### Q47. [Theory] How does SonarQube handle a rule that exists in the Quality Profile but is later removed/changed by a plugin upgrade? What happens to historical issues?

This probes lifecycle and data-stability internals. Rules are owned by **language analyzer plugins**; upgrading a plugin can add rules, deprecate rules, or change a rule's default severity/metadata. When a rule is **removed** from the analyzer, SonarQube marks it as such, and existing issues for that rule become **"removed rule" issues** — they remain in the database (history is preserved, you don't lose the record), but the rule no longer fires on new analyses, so no *new* issues of that type are created and the old ones effectively close out of active scope. A rule whose key changes is typically handled via a **deprecation/alias** so existing issues remap rather than orphan.

```
 Plugin upgrade                       Effect on profile / issues
 ─────────────────────────────────    ─────────────────────────────────────────
 New rule added                       NOT auto-activated in custom profiles; you
                                       opt in (so upgrades don't silently add noise)
 Rule deprecated                      still works; flagged for future removal
 Rule removed                         existing issues kept as history; rule stops firing
 Default severity changed             affects new issues; does not retro-rewrite old ones
```

The two depth points: (1) newly added rules are **not** automatically switched on in your *custom* profiles (only the built-in "Sonar way" tracks them), which is a deliberate stability choice so a plugin upgrade can't silently flood you with new findings — you consciously activate new rules. (2) Severity/metadata changes apply to *future* analyses, not a retroactive rewrite of historical issues, so trend charts stay coherent across the upgrade. The practical takeaway is that a plugin/analyzer upgrade is a *change event* you should review (diff the new rules, decide what to activate), not a no-op — and it explains why a long-running project's metrics can shift after an upgrade even with no code change: the ruleset moved underneath it.

#### Q48. [Theory] Explain the concept of "leak period" semantics and why "new code" can include modifications to *old* lines, not just added lines.

A common misconception is that "new code" = "lines added in this PR." In SonarQube's model, **new code is any line that is added *or modified* within the new-code period**, as determined by SCM. The "leak" metaphor (the original name for the new-code period) frames the codebase as a container: you don't try to drain the existing water (legacy debt), you just ensure nothing leaks *in* — and a *modified* line counts as new water because you had the chance to fix it while your hands were on it.

```
 File before PR        File after PR            New-code lines (gate scope)
 ──────────────        ─────────────            ───────────────────────────
 1  foo();             1  foo();                 (unchanged → old)
 2  bar(x);    ──edit─▶ 2  bar(y);   ← MODIFIED   line 2 = NEW
 3                      3  baz();    ← ADDED       line 3 = NEW
```

This has real consequences. If you edit one line in a 1,000-line legacy method, *that line* enters new-code scope and its issues must satisfy the gate — but the other 999 lines do not, so you're not punished for the whole method's debt. It also means whitespace-only or formatting changes can pull lines into new-code scope and surface their pre-existing issues, which occasionally surprises people ("I only reformatted and now the gate complains"). The deeper rationale ties back to Clean-as-You-Code's monotonicity argument (Q43): counting *modified* lines as new is precisely what makes touched code converge toward clean — if only purely-added lines counted, editing a buggy legacy line wouldn't obligate you to fix it, and the organic-improvement property would be much weaker.

#### Q49. [Theory] How does PR analysis compute "new code" differently from a long-lived branch, and why are PR results ephemeral?

For a **long-lived branch** (like `main` or `release/*`), new code is computed against that branch's configured new-code period (previous version, days, date, or reference branch), and the results are **persisted durably** — they form the project's history and trend lines. For a **pull request**, the scanner is told `sonar.pullrequest.base` (e.g., `main`), and new code is the **diff between the PR and its base** — effectively the changed lines of the PR. PR analysis runs the full rule set but reports issues and the gate *only against that diff*, and it produces the **PR decoration** (inline comments + gate check) on the SCM provider.

```
 LONG-LIVED BRANCH (main)              PULL REQUEST
 ──────────────────────               ─────────────────────────────────
 new code = branch's NCP              new code = diff vs sonar.pullrequest.base
 results PERSISTED (history/trends)   results EPHEMERAL (purged after merge/close)
 carries durable metrics              decorates the PR; not part of trend history
 -Dsonar.branch.name=main             -Dsonar.pullrequest.key/branch/base=...
```

PR results are **ephemeral by design**: a PR is a transient artifact, and keeping thousands of merged/abandoned PR analyses would bloat Elasticsearch and the database (Q16/Q33) for no analytical value — so SonarQube purges them after the PR closes/merges, governed by branch/PR housekeeping settings. The durable truth lives on the target branch: after merge, the *post-merge `main` analysis* is what updates the long-term metrics. The senior framing of the workflow: the **PR gate is the blocking required check** (achievable, diff-scoped, throwaway), and the **`main` analysis is the system of record** (persisted, trend-bearing) — and conflating the two ("why did my PR's numbers disappear?") is exactly the misunderstanding this question surfaces.

#### Q50. [Theory] What is the architectural reason SonarQube cannot reliably analyze code it cannot compile or whose dependencies are missing, even though it's "static"?

"Static" is often misread as "needs nothing but the text," but SonarQube's *valuable* analysis is **semantic**, and semantics for most languages require resolving types and symbols that frequently live *outside* the file being analyzed — in dependencies, the standard library, or generated stubs. Without those, the analyzer can parse syntax but its type/symbol model has holes, and any rule that depends on knowing "what type is this expression" either silently disables or produces noise. So even though no code executes, the analyzer needs the *materials to reason about meaning*: compiled bytecode + classpath for Java, `tsconfig` + type defs for TypeScript, resolvable imports for Python, etc.

```
 "Static" ≠ "text-only"
 ───────────────────────
 Syntactic rules   ──need──▶ just the parse tree         (work with source alone)
 Semantic rules    ──need──▶ resolved types/symbols      (need deps/bytecode/config)
   taint analysis  ──need──▶ interprocedural type truth   (degrades badly w/o them)
```

The architectural reason is that type resolution is a *closure* over the dependency graph: to know the type of `service.process(x)` you must resolve `service`'s class, which may be in a JAR, which references other types, and so on. Missing a link in that chain means the analyzer falls back to "unknown type," and rules guarding on type can't fire confidently. This is why a Java scan that skipped `compile` (no `sonar.java.binaries`) or a TS scan with no installed `node_modules` produces shallow, noisy results — not a tooling bug but a direct consequence of where type truth lives. The takeaway for interviews: "static analysis is independent of *execution*, not independent of *compilation/resolution*" — confusing those two is the misconception.

#### Q51. [Practical] How would you reduce SonarQube false positives without globally disabling rules, and what are the precedence and trade-offs of each suppression mechanism?

There's a hierarchy of suppression mechanisms, from surgical to blunt, and a senior answer ranks them by how narrowly they limit the blast radius — because every suppression is a small erosion of trust in the metrics, so you want the *narrowest* tool that solves the problem.

```
 Mechanism                     Scope            Survives refactor?   When to use
 ───────────────────────────   ──────────────   ──────────────────   ─────────────────────────
 Fix the code                  n/a              n/a                  Always first choice
 Mark issue "won't fix"/Accept one issue         tracked (line hash)  True but acceptable case,
   in the UI                                                          with a justification comment
 // NOSONAR (or @SuppressWarnings line/element     yes (in source)      Last resort, ONE line, with
   ("squid:Sxxxx"))                                                    a reason comment
 Rule param tuning             rule-wide        n/a                  Threshold is genuinely wrong
   (in profile)                                                       for your codebase
 Deactivate rule in profile    project/profile  n/a                  Rule has no value for you
 sonar.issue.ignore.multicriteria pattern-scoped  yes (config)         Class of files legitimately
   (regex on rule+path)                                               exempt (e.g. generated)
```

The precedence point: source-level suppressions (`// NOSONAR`, `@SuppressWarnings`) and config-level `sonar.issue.ignore.*` patterns are applied during analysis so the issue never reaches the gate, whereas "won't fix"/"accept" is a *post-facto* status set on a persisted issue. The trade-offs: `// NOSONAR` is dangerous because it suppresses **all** rules on that line, not the specific one (use the rule-specific `@SuppressWarnings("javaSxxxx")`/`squid` form when possible), and a bare `// NOSONAR` with no comment is a code smell in itself. Pattern-based `ignore.multicriteria` is right for *classes* of files (generated, vendored) but can over-suppress if the regex is loose. The governing principle is **narrowest scope + recorded justification**: fix > narrowly-justified accept > rule-specific inline suppression > parameter tuning > pattern exclusion > full deactivation — and never reach for a broad tool to silence a single annoying line.

#### Q52. [Theory] What is the difference between the "Reliability/Security/Maintainability" rating letters and the underlying issue counts, and why are ratings non-linear?

The A–E **ratings** are deliberately *not* a smooth function of issue counts — they're **threshold/worst-case** functions designed to communicate risk, not volume. The Reliability and Security ratings are driven by the **single most severe issue** present: any open Blocker bug forces Reliability to E, regardless of whether you have one or one hundred — because one guaranteed crash is a release-blocker, and counting more of them doesn't change the binary "this is unsafe" message. Maintainability is different: it's a *ratio* (technical-debt ratio, Q8), so it scales with proportion of debt to size, not a single worst item.

```
 Reliability / Security rating   ←  WORST issue wins (severity-driven, non-linear)
   no bugs ........... A
   ≥1 Minor .......... B
   ≥1 Major .......... C
   ≥1 Critical ....... D
   ≥1 Blocker ........ E      (1 blocker = E, same as 50 blockers)

 Maintainability rating          ←  RATIO of debt to size (continuous-ish)
   debt ratio ≤5% A … >50% E
```

The reason for the asymmetry is *semantic intent*. For reliability and security, the question is "is there anything here that can hurt us?" — a worst-case framing, so the rating tracks the most dangerous finding. For maintainability, no single smell is catastrophic; what matters is the *aggregate burden relative to codebase size*, so a ratio is the honest metric. This explains a frequent confusion: "I fixed 40 bugs and my Reliability rating didn't improve" — because one remaining Blocker still pins it at E. The actionable reading is that **ratings tell you about risk class, issue counts tell you about workload**, and you need both: drive ratings to A by eliminating the worst items first, then use counts to plan the cleanup effort. In MQR mode (Q29) the same idea is expressed via per-quality impacts, but the worst-impact-dominates principle for security/reliability persists.

#### Q53. [Theory] How does SonarQube's analysis differ for IaC/configuration files (Dockerfile, Kubernetes, Terraform) versus application source, and what's the precision trade-off?

SonarQube added analyzers for **infrastructure-as-code and config** — Dockerfile, Kubernetes manifests, Terraform/CloudFormation, and secret detection — and they work on a fundamentally different model than application-code analyzers. App-code analysis builds an AST + semantic model + control/data flow and can do taint tracking. IaC analysis is closer to **structured-pattern matching over a declarative document**: parse the YAML/HCL/Dockerfile into a tree, then check rules like "container runs as root," "S3 bucket is public," "no resource limits set," "image uses `latest` tag." There's no control flow to follow because declarative config doesn't *execute* in the procedural sense.

```
 App code analyzer                    IaC/config analyzer
 ─────────────────                    ────────────────────────────────
 AST + types + control/data flow      parse declarative doc → structural rules
 taint source→sink across files       property checks on resources/keys
 high precision (semantics)           good for misconfig, but lacks cross-resource
                                       runtime context (what the cloud actually does)
```

The precision trade-off is the interview point. IaC rules are excellent at catching *local* misconfiguration that's visible in the file (privileged container, missing readOnlyRootFilesystem, hardcoded secret), but they **cannot see the deployed reality** — whether a security group is *effectively* open depends on the whole cloud graph, IAM, and runtime state that the static file doesn't contain. So SonarQube's IaC scanning is a useful *shift-left* on config hygiene but is **not a substitute for CSPM/cloud posture tools** that evaluate the live environment (this overlaps with Q21/Q44's defense-in-depth point). The senior framing: treat IaC rules as "is this manifest written safely?" not "is my cloud secure?" — the former is in-file and SonarQube does it well; the latter needs runtime context SonarQube structurally lacks.

#### Q54. [Theory] Why is the Compute Engine queue per-project-serial, and what are the throughput implications and tuning levers at scale?

The Compute Engine (CE) processes analysis reports as queued tasks, and a key design constraint is that tasks **for the same project are processed serially** (in order), even though different projects can be processed in parallel up to the configured worker count. The serialization-per-project exists for **correctness**: issue tracking (Q25), new-code computation, and measure history all depend on processing analyses in order — if two analyses of the same project ran concurrently, they'd race on "what was the previous state," corrupting tracking and trends. So ordering within a project is non-negotiable; parallelism is across projects.

```
 CE queue (workers = N)
 ┌─ ProjectA: analysis#1 → #2 → #3   (strictly serial, ordering matters)
 ├─ ProjectB: analysis#1 → #2        (serial within B)
 └─ ProjectC: analysis#1             (parallel across projects, up to N workers)
        ▲ burst of PR builds for ProjectA all queue behind each other
```

The throughput implication at scale: a single very active project (a busy monorepo with many concurrent PRs) becomes a **head-of-line bottleneck for itself** — its analyses queue serially no matter how many CE workers you have, so PR feedback latency grows with PR volume on that one project. The tuning levers: (1) increase **CE worker count** (paid editions) to raise *cross-project* parallelism and CE **heap** so large reports don't thrash; (2) split a giant monorepo into multiple `projectKey`s so their analyses parallelize instead of serializing behind one key (Q17); (3) reduce report size via exclusions so each task is cheaper; (4) use **webhooks** instead of `qualitygate.wait` so CI runners aren't held hostage to queue depth (Q34). The expert insight is recognizing that the serial-per-project rule is a *correctness* requirement (in-order tracking), so you scale by parallelizing *across* keys, not by trying to parallelize a single project's history.

#### Q55. [Theory] How does SonarQube's "issue creation date" and "new code" interact with retroactive rule activation or a backdated baseline — and why can "old" code suddenly appear as "new"?

Two timestamps govern an issue's gate scope: its **creation date** (when the issue was first detected) and whether its *line* falls in the **new-code period**. Normally these align, but several operations decouple them and produce the counterintuitive "old code is now failing the new-code gate" effect. (1) **Activating a new rule** in the profile: the rule now fires on lines it never flagged before; SonarQube assigns those issues a creation date and, crucially, if those lines are within the current new-code window (e.g., recently modified, or the baseline is a reference branch where the line differs), they count as *new* and hit the gate even though the *code* is old. (2) **Changing the new-code baseline** (e.g., switching to an earlier date or a different reference branch) re-partitions which lines are "new," pulling previously-old code into scope.

```
 Event                              Effect on gate scope
 ─────────────────────────────────  ─────────────────────────────────────────────
 Activate rule R in profile         R's findings on in-window lines → count as NEW
 Move baseline date earlier         more lines fall in window → more "new" issues
 Switch reference branch            diff changes → different "new code" set
 Reformat/touch a legacy line       line enters window → its old issues now NEW (Q48)
```

SonarQube tries to set issue creation dates honestly using SCM blame (the line's actual commit date) so a newly-*activated* rule's findings on genuinely old, untouched lines get an *old* creation date and stay out of the new-code gate — this is the "backdating" behavior that prevents a rule activation from spuriously breaking every PR. But the interaction is subtle and version/config-dependent, and it's why teams sometimes see a gate flip red after a profile or baseline change with no code change. The senior takeaway: **new-code scope is a function of (line age via SCM) × (current baseline) × (current ruleset)** — change any of the three and the "new code" set shifts. Understanding that creation-date backdating exists *specifically* to keep rule activations from polluting the new-code gate is the depth signal; it's the mechanism that makes "turn on a new rule org-wide" safe rather than catastrophic.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q56. [Practical] You run a SonarQube container locally, the UI loads, but the first analysis fails with "Elasticsearch is unhealthy / bootstrap checks failed." What's the cause and fix?

This is the single most common "it won't start" incident, and it's almost never SonarQube's fault — it's the host kernel. SonarQube embeds an Elasticsearch node, and ES refuses to start in production mode unless the kernel's `vm.max_map_count` (the limit on memory-mapped areas a process may have) is at least **262144**. The default on many Linux hosts is 65530, so ES fails a bootstrap check and the whole server reports unhealthy. On Docker Desktop / WSL2 the setting lives in the *VM*, not your shell, which trips people up.

```bash
# Check the current value
sysctl vm.max_map_count

# Fix at runtime (lost on reboot)
sudo sysctl -w vm.max_map_count=262144

# Persist across reboots
echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

The other two frequent first-run causes: (1) running ES as **root** — the bundled ES will refuse, so the container/process must run as a non-root user (the official image already does this); and (2) putting the ES data directory on a **network filesystem** (NFS, SMB) or an overlay that doesn't support `mmap` properly — ES mmaps its index files and will misbehave. The deeper lesson for an interview is that SonarQube is *operationally an Elasticsearch host*: most "SonarQube won't boot" tickets are ES bootstrap checks (file descriptors `ulimit -n` ≥ 65536, max threads, max_map_count), and you debug them by reading `logs/es.log`, not `logs/web.log`.

#### Q57. [Practical] A developer says "my code is clean in SonarLint but the PR gate failed in SonarQube." How do you diagnose and prevent this?

This "works on my machine" of code quality almost always comes down to **rule-set drift between the IDE and the server**. SonarLint in *standalone* mode ships its own bundled rules and a default activation set that does **not** match your server's Quality Profile — so a rule that's active on the server (failing the PR) may be inactive or differently parameterized in the IDE, and vice versa. The fix is **Connected Mode**: bind SonarLint to the SonarQube project so it downloads and uses the *same* Quality Profile, rule parameters, and even issue suppressions/"won't fix" decisions.

```
 STANDALONE SonarLint                 CONNECTED MODE
 ────────────────────                 ──────────────────────────────────
 Bundled default rules                Pulls server's Quality Profile
 No knowledge of server suppressions  Honors server "won't fix"/NOSONAR
 Can disagree with the gate           Matches what the gate will enforce
 Zero setup                           Bind project + token, syncs profiles
```

But Connected Mode only closes the *rule-set* gap. Two structural differences remain and are worth naming: (1) **taint analysis (SAST)** runs only on the server/Developer Edition — SonarLint generally can't reproduce cross-file injection findings, so a vulnerability can legitimately appear only in the PR; and (2) the server analyzes with **full semantic context** (compiled bytecode, whole-project classpath, coverage import) while the IDE sees the open file with whatever it can resolve, so semantic-dependent and coverage/duplication metrics differ. The preventive process is: roll out Connected Mode org-wide so everyday rules match, and educate developers that taint/coverage/duplication are *server-only* signals they should expect to first see in the PR decoration, not the IDE.

#### Q58. [Practical] Your CI job runs `mvn sonar:sonar`, the step goes green, but the PR was never blocked even though the gate is red in the UI. What did the pipeline get wrong?

The pipeline confused "the scanner uploaded successfully" with "the gate passed." The SonarScanner's job ends when it serializes the analysis report and uploads it to the server; the **Quality Gate is computed asynchronously** by the server's Compute Engine *after* the scanner already returned exit code 0. So a build that only checks the Maven step's exit status will pass even on a red gate — the gate result simply wasn't waited for.

```bash
# WRONG: scanner returns 0 the moment upload completes — gate not yet computed
mvn verify sonar:sonar

# RIGHT: block the build until the CE computes the gate, fail if red
mvn verify sonar:sonar \
  -Dsonar.qualitygate.wait=true \
  -Dsonar.qualitygate.timeout=300
```

`sonar.qualitygate.wait=true` makes the scanner poll the CE task API until the gate status is available and then **exit non-zero if the gate is ERROR**, which is what actually fails the job. The trade-off (and the reason teams sometimes "forgot" it) is that polling holds the CI runner idle while the CE queue drains — on a busy server with a deep queue that wastes runner minutes, which is why some shops prefer webhooks for fan-out. The other half of the fix is making the gate a **required status check** on the branch-protection rules, so even a green CI job can't merge unless SonarQube's check posted "pass." Diagnosing this means reading the scanner log for "ANALYSIS SUCCESSFUL" (upload) versus a separate "QUALITY GATE STATUS" line (the wait result) — if you only see the former, `wait` wasn't enabled.

#### Q59. [Practical] What is a `sonar-project.properties` file, when do you use it versus build-tool configuration, and what belongs in it versus in CI secrets?

`sonar-project.properties` is the scanner's plain-text configuration file, read by the **standalone SonarScanner CLI** (used for languages without a build plugin — JS/TS, Python, Go, plain projects). It declares the project identity and the file layout. For Maven/Gradle/.NET you usually *don't* need it because the build plugin derives most settings from the build model and you pass overrides via `-D` properties or the build script; mixing both is a common source of "which value won?" confusion (CLI `-D` overrides the file, which overrides analysis-scope defaults).

```properties
# sonar-project.properties — COMMITTED to the repo (non-secret identity + layout)
sonar.projectKey=payments-api
sonar.projectName=Payments API
sonar.sources=src
sonar.tests=test
sonar.exclusions=**/node_modules/**,**/*.generated.ts
sonar.javascript.lcov.reportPaths=coverage/lcov.info
# NOTE: do NOT put the token or even the host here in most setups
```

The discipline that signals operational maturity: **identity and layout go in the committed file** (projectKey, sources/tests, exclusions, report paths) because they're code-coupled and reviewable; **secrets and environment go in CI** (`SONAR_TOKEN` as a masked secret, `sonar.host.url` as a pipeline variable). Putting a token in the committed properties file is a real incident — it leaks into git history and grants analysis (and sometimes admin) access. A subtle related point: prefer a **project-scoped analysis token** (SonarQube 9.x+) over a personal user token so the credential is least-privilege and survives the owner leaving the team.

### 🟡 Intermediate — extended

#### Q60. [Practical] SonarQube reports 0% coverage even though your tests run and pass in CI. Walk through your debugging checklist.

Coverage at 0% with passing tests is the canonical SonarQube support ticket, and the root cause is *always* that Sonar can't find or align the imported report — remember Sonar **imports** coverage, it never measures it. I debug in order of likelihood:

```
 0% coverage debugging checklist
 ───────────────────────────────
 1. Was the report FILE generated?    → ls target/site/jacoco/jacoco.xml
                                          (mvn 'test' without the 'report' goal = no XML)
 2. Is the PATH right?                → sonar.coverage.jacoco.xmlReportPaths matches reality?
 3. Did tests run BEFORE the scan?    → scan must come after test phase in the same run
 4. Do source paths line up?          → JaCoCo report's class paths must map to sonar.sources
 5. Multi-module aggregation?         → per-module reports miss cross-module IT coverage
 6. Are the files coverage-excluded?  → sonar.coverage.exclusions silently zeroes them
 7. Right property?                   → XML xmlReportPaths, NOT the dead binary reportPaths
```

The highest-frequency culprit is #1: a pipeline that runs `mvn test` (or `surefire`) but never invokes the JaCoCo `report` goal, so no `jacoco.xml` exists and Sonar has nothing to import — it isn't an error, it's silently 0%. The second is #4: in multi-module or non-standard layouts, the paths recorded *inside* the JaCoCo XML don't resolve against `sonar.sources`, so Sonar imports the file but matches nothing. For JS/TS the equivalent is a stale or wrong-path `lcov.info`, or relative paths in lcov that don't match the analyzed source root. The clincher diagnostic: run the scanner with `-X` (Maven debug) or `sonar.verbose=true` and grep the log for lines like "Importing X coverage reports" and "Coverage report ... could not be read / 0 files matched" — the log tells you exactly whether the file was found and how many files it mapped.

#### Q61. [Practical] A scan that used to take 4 minutes now takes 40 on a large monorepo. How do you profile and bring the time back down?

A scan time blowup is usually one of: the analyzed file set ballooned, an expensive feature got enabled, or the server's Compute Engine is the bottleneck (which is *server* time, not *scanner* time — distinguish them first). I start by reading the scanner log timings (`sonar.verbose=true` prints per-sensor durations) to see whether time is in a specific analyzer, in coverage/duplication import, or in the upload, and I check the CE background-task duration in the UI separately.

```bash
# Surface where the scanner spends time
mvn sonar:sonar -Dsonar.verbose=true   # logs each sensor's elapsed time

# Common wins, in order of impact:
# 1. Stop analyzing what you don't own — generated/vendored/build output
sonar.exclusions=**/generated/**,**/dist/**,**/node_modules/**,**/target/**
# 2. Don't scan giant minified/bundled assets
sonar.exclusions=**/*.min.js,**/*.bundle.js
# 3. Cap or scope duplication on huge generated trees
sonar.cpd.exclusions=**/generated/**
```

The biggest lever on a monorepo is **scope**: teams often scan `node_modules`, build output, minified bundles, or vendored code by accident, multiplying the file count and especially the duplication-detection cost (CPD is sensitive to volume). The second lever is **splitting the monorepo into multiple `projectKey`s** so the work — and the Compute Engine processing — parallelizes across projects instead of serializing behind one key (the CE is serial *per project*). Other targeted fixes: give the scanner JVM more heap (`SONAR_SCANNER_OPTS=-Xmx`) if it's GC-thrashing, ensure incremental analysis isn't defeated by a clobbered SCM/cache, and verify a shallow clone or slow network isn't inflating SCM-blame time. If the *scanner* is fast but the UI shows the background task taking 35 minutes, the fix is server-side: more CE workers/heap, not scanner tuning.

#### Q62. [Practical] How do you back up and restore a self-hosted SonarQube, and what is the one thing you must never forget about version compatibility?

The authoritative system of record is **PostgreSQL** — issues, measures, configuration, history, and gate definitions all live there — so a SonarQube backup is fundamentally a **database backup** plus the few on-disk things the DB references. Elasticsearch is a *derived* index and does **not** need backing up; you can rebuild it from the DB by reindexing. So the backup procedure is: dump PostgreSQL, and copy `conf/sonar.properties` (and any custom plugins in `extensions/plugins/`).

```bash
# Backup: dump the DB (stop the server or quiesce analyses first for consistency)
pg_dump -U sonar -F c sonarqube > sonarqube_$(date +%F).dump

# Also copy config + custom plugins (NOT the ES data dir — it's regenerable)
cp conf/sonar.properties backup/    # DB url, ES/jvm settings
cp -r extensions/plugins backup/

# Restore must go into the SAME SonarQube version that produced the dump,
# then upgrade through supported steps if you want a newer version.
pg_restore -U sonar -d sonarqube_new sonarqube_2026-06-16.dump
```

The non-negotiable rule: **the DB schema is version-specific.** You must restore into the *same SonarQube version* that created the dump — restoring an old dump into a newer binary, or vice versa, corrupts the schema. If you're moving to a newer version, restore into the matching version first, then run the supported upgrade (which executes DB migrations), ideally LTA→LTA. The corollary failure mode interviewers love: treating Elasticsearch as something to back up and restore — it's regenerable, so backing it up is wasted effort and *restoring* a stale ES index against a fresh DB just causes search inconsistencies you then have to reindex away anyway.

#### Q63. [Practical] Describe a safe procedure for upgrading a production SonarQube across a major version (e.g., to a new LTA). What are the failure modes?

A major SonarQube upgrade runs **database migrations** and may change default behavior (e.g., MQR mode becoming default) and plugin-API compatibility, so it is never a hot binary swap — it's a planned maintenance with a tested rollback. My procedure:

```
 1. Read the upgrade notes for EVERY version you hop through (LTA-to-LTA may
    require an intermediate stop; migrations aren't skippable).
 2. Take a full PostgreSQL backup + copy conf/ and extensions/plugins/.
 3. Stand up a STAGING clone from that backup and dry-run the upgrade there:
    - verify DB migration completes (visit /setup, watch web.log)
    - check every custom/marketplace plugin is compatible with the new version
 4. Schedule a maintenance window; pause CI analyses (queue drains or pauses).
 5. Stop Sonar → deploy new binaries → start → it enters "DB migration" mode
    → trigger migration via /setup → wait for completion.
 6. Smoke test: a real scan, gate evaluation, PR decoration, LDAP/SSO login.
 7. Re-enable CI. Keep the backup until you've baked for a few days.
```

The failure modes that bite people: (1) **incompatible plugins** — a marketplace or custom plugin compiled against the old plugin API won't load on the new server, so the server starts degraded or refuses; you must upgrade or remove plugins *first*. (2) **Skipping the LTA hop** — migrations are cumulative and the product only supports certain upgrade paths; jumping too far leaves the schema in an unmigratable state. (3) **Behavioral changes silently flipping gates** — MQR mode (Q29), renamed metric API keys, or new default rules can make a previously-green project red or break dashboards/scripts that queried old metric names, so you audit gates and any web-API automation. (4) **No tested rollback** — because migrations mutate the schema irreversibly, your only rollback is *restore the pre-upgrade DB backup into the old binaries*, which is why the backup in step 2 is mandatory and why you never delete it until the new version is proven.

#### Q64. [Practical] A team needs to onboard 50 microservice repos to SonarQube this quarter. How do you make onboarding repeatable rather than per-repo toil?

Hand-configuring 50 scanners is both slow and a consistency disaster (every repo ends up with a slightly different projectKey scheme, missing coverage import, or a drifted gate). The answer is **onboarding-as-code**: a single reusable CI component plus convention so a repo joins by including a few lines, not by copy-pasting a 60-line YAML block.

```yaml
# A reusable GitHub Actions workflow (called by every repo)
# .github/workflows/sonar.yml in a central repo
name: sonar
on: { workflow_call: { secrets: { SONAR_TOKEN: { required: true } } } }
jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }          # full history for SCM blame / new code
      - run: ./gradlew test jacocoTestReport sonar
        env: { SONAR_TOKEN: "${{ secrets.SONAR_TOKEN }}" }
```

```yaml
# Each consumer repo just calls it (the whole onboarding footprint):
jobs:
  quality:
    uses: my-org/ci-templates/.github/workflows/sonar.yml@v1
    secrets: { SONAR_TOKEN: "${{ secrets.SONAR_TOKEN }}" }
```

The pillars that make this scale: (1) a **reusable workflow / Jenkins shared library / GitLab CI `include`** so scanner invocation is centralized and versioned — fix a bug once, every repo gets it; (2) **convention over configuration** — derive `projectKey` from the repo name so nobody hand-picks keys; (3) a **default org Quality Gate and an inherited parent Quality Profile** so all 50 repos enforce the same baseline without per-repo setup (Q46); (4) **project-scoped analysis tokens** provisioned via the SonarQube web API or DevOps-platform auto-provisioning rather than minted by hand; and (5) **phased enforcement** — onboard in report-only mode, then flip the gate to blocking-on-new-code after a grace window so teams aren't ambushed. The meta-point: onboarding cost must be O(1) per repo, or you'll never finish 50 — and the templated path is also how you guarantee everyone does `fetch-depth: 0`, the single most commonly-missed setting that quietly breaks new-code detection.

#### Q65. [Practical] In a GitHub Actions pipeline, new-code detection is wrong — issues from old code show up as "new" and creation dates are all today's date. What's the root cause?

This is the **shallow-clone trap**, and it's specific to CI checkouts. SonarQube derives "new code" and issue introduction dates from **SCM blame** — the per-line commit history. GitHub Actions' `actions/checkout` defaults to a **shallow clone (`fetch-depth: 1`)**, which fetches only the latest commit and *no history*. With no history, blame can't attribute lines to their real commits, so SonarQube falls back to treating lines as new and stamps issue creation dates as the analysis date — exactly the symptom described.

```yaml
# WRONG (default): shallow clone strips the history blame needs
- uses: actions/checkout@v4          # fetch-depth: 1 implied

# RIGHT: full history so SCM blame and new-code detection work
- uses: actions/checkout@v4
  with:
    fetch-depth: 0                   # 0 = full history, not "zero commits"
```

The same class of bug appears on other platforms: GitLab CI's `GIT_DEPTH` defaulting to a shallow fetch, Jenkins' "Shallow clone" checkbox, or Azure DevOps' `fetchDepth`. The fix is always "fetch enough history for blame" — `fetch-depth: 0` is the safe choice. The interview-grade explanation ties it back to internals: blame is the *substrate* for the reference-branch new-code definition and for attribution, so a checkout misconfiguration directly corrupts analysis correctness even though nothing is wrong with SonarQube itself. A secondary subtlety for PRs: you also want the **base branch** available locally (or the right `sonar.pullrequest.base`) so the diff can be computed — some setups need to fetch the base ref explicitly.

#### Q66. [Practical] How do you migrate a project's history from one SonarQube instance to another (or consolidate two servers)? What can and cannot be moved?

This is a frequently-misunderstood request because people assume there's a clean "export project with history" button — there isn't, by design, for cross-instance moves. SonarQube's project *measures and issue history* live in the database and are tightly coupled to that instance's schema, rule IDs, and internal keys, so there's **no supported per-project history export/import** across separate instances. What you *can* move cleanly are the **configuration artifacts**: Quality Profiles (backup/restore as XML), Quality Gates, and rule activations.

```bash
# Quality Profiles ARE portable across instances (XML backup/restore via API)
curl -u $TOKEN: "https://old-sonar/api/qualityprofiles/backup?language=java&qualityProfile=My%20Way" \
  > java-profile.xml
curl -u $TOKEN: -F "backup=@java-profile.xml" "https://new-sonar/api/qualityprofiles/restore"

# Quality Gates: read via api/qualitygates and recreate on the target.
# Project HISTORY/issues: NOT portable — re-baseline on the new instance instead.
```

The practical migration strategy is therefore: move **profiles and gates** via the web API (they're declarative and portable), recreate **projects and permissions** (scriptable via `api/projects/create` and permission templates), then **re-baseline** — point CI at the new instance and let the first analysis establish a fresh history, accepting that *trend history starts over*. If preserving exact historical trends is a hard requirement, the only real option is a **whole-instance migration** (move the entire PostgreSQL database to the new host at the same version, then upgrade), not a project-by-project move. The senior framing: distinguish *configuration* (portable, declarative, API-driven) from *accumulated history* (instance-bound, schema-coupled) — and set expectations that consolidating servers means either a full-DB move (keeps history) or a re-baseline (loses trend history but is operationally simple).

#### Q67. [Practical] Your gate condition is "coverage on new code ≥ 80%" and a PR that adds only a config class and DTOs fails it. Is the gate wrong? How do you handle this correctly?

The gate isn't wrong — it's doing exactly what you told it, flagging that new lines aren't covered. The *configuration* is wrong: you're demanding test coverage on code that has no meaningful logic to test (plain DTOs, getters/setters, Spring `@Configuration` wiring, generated builders). Forcing developers to write assertion-free tests just to hit 80% on a DTO is busywork that erodes trust in the metric. The correct fix is **coverage exclusions**, not loosening the global threshold.

```properties
# Keep these analyzed for bugs/smells, but exempt from the COVERAGE denominator:
sonar.coverage.exclusions=\
  **/dto/**,\
  **/*Config.java,\
  **/*Configuration.java,\
  **/config/**,\
  **/*Application.java,\
  **/generated/**
```

The critical distinction — and a classic interview discriminator — is `sonar.coverage.exclusions` versus `sonar.exclusions`. The former keeps the files in analysis (they're still scanned for bugs and vulnerabilities) but removes them from the *coverage percentage* so they don't drag it down; the latter would remove them from analysis **entirely**, which is the wrong tool here because you'd also stop finding real bugs in those files. The naive mistake is reaching for `sonar.exclusions` to "fix the coverage number," accidentally blinding Sonar to actual defects in config and DTO code. The mature stance: exclude *coverage* on logic-free code, keep *analysis* on everything, and resist the temptation to drop the global threshold (which would also lower the bar for genuinely testable business logic).

#### Q68. [Practical] How do you handle secrets that SonarQube's secret-detection rules flag, and what's the right remediation beyond just deleting the line?

SonarQube's secret-detection rules flag patterns that look like credentials (AWS keys, tokens, private keys, connection strings) committed to source. The naive "fix" — delete the line and commit — is **insufficient and dangerous**, because the secret is still in **git history** and on every clone/fork/CI cache that ever pulled it. A leaked credential must be treated as **compromised the moment it was committed**, regardless of whether the current HEAD still contains it.

```
 Wrong remediation                     Correct remediation
 ──────────────────                    ─────────────────────────────────────────
 Delete line, commit                   1. ROTATE the credential immediately
 (still in history!)                      (assume it's already harvested)
                                       2. Move it to a secrets manager / CI secret
 Mark issue "won't fix"                3. Purge from history if feasible (BFG/
 (silences detection, not the leak)       git filter-repo) — coordinate, it rewrites
                                       4. Add detection to PREVENT recurrence
                                          (pre-commit hook + Sonar gate condition)
```

The order matters: **rotate first** (the only step that actually closes the exposure), then remove the secret from the working tree and relocate it to a proper store (Vault, AWS Secrets Manager, CI masked variable). Purging from history with `git filter-repo`/BFG is optional and disruptive (it rewrites SHAs and forces every collaborator to re-clone), so you weigh it against the leak's severity. The prevention layer is the durable fix: a **pre-commit secret scanner** plus SonarQube's secret rule in the gate catches the *next* one at keystroke/PR time. The senior point interviewers want: SonarQube tells you a secret *exists in code*, but it can't tell you the secret is *safe* — remediation is an incident-response workflow (rotate, contain, prevent), not a code edit, and marking the issue "won't fix" without rotating is the worst possible response.

### 🟠 Advanced — extended

#### Q69. [Practical] The Compute Engine background-task queue is backing up — PRs wait 20+ minutes for gate results during peak hours. Diagnose and remediate.

A growing CE queue means analysis *reports* are arriving faster than the Compute Engine can process them. First I confirm it's CE latency, not scanner latency, by checking **Administration → Background Tasks**: if tasks show "Pending" for long periods and processing duration is high, it's the CE. Then I reason about the constraint — the CE processes tasks with a fixed **worker count**, serially *per project* (for tracking correctness), in parallel *across* projects.

```
 CE queue diagnosis                    Lever
 ─────────────────────────────────     ──────────────────────────────────────────
 Many projects pending, workers busy   ↑ ce.workerCount (paid) — more parallelism
 One hot project's tasks stack up       split that monorepo into multiple keys
 Tasks slow (big reports, GC pauses)    ↑ CE heap (sonar.ce.javaOpts -Xmx); shrink
                                         reports via exclusions
 CI runners idle, waiting on wait=true  switch to webhooks (push) for fan-out
 ES slow → CE indexing slow             check ES health/heap/disk
```

The remediation depends on which pattern it is. If *many distinct projects* queue, raise **CE worker count** (Developer/Enterprise edition lets you add workers) and CE **heap** so large reports don't trigger GC thrash. If a *single very active project* is the head-of-line blocker — its analyses serialize no matter how many workers exist — the structural fix is **splitting it into multiple `projectKey`s** so they parallelize. Reduce per-task cost by **excluding generated/vendored code** so each report is smaller. And decouple CI from queue depth by moving from `sonar.qualitygate.wait=true` (which pins a runner per build) to **webhooks**, so PRs aren't holding runners hostage to the backlog. The expert insight is recognizing that you *cannot* fix per-project head-of-line blocking with more workers — that requires splitting the project — whereas cross-project contention *is* fixed with workers/heap; choosing the right lever requires knowing which pattern the queue is exhibiting.

#### Q70. [Practical] You inherit a codebase showing 18,000 issues and an E maintainability rating. The team is paralyzed. Design a remediation plan that doesn't stall feature work.

The paralysis comes from framing 18,000 issues as a single backlog that must be "burned down" — that project never ships and demoralizes everyone. The strategy is to **stop the bleeding first, then bail water selectively**, which is exactly the Clean-as-You-Code model applied operationally.

```
 Phase 1 — STOP NEW DEBT (week 1)
   Set gate to new-code only (reference branch = main). The 18k become
   informational; every PR must add ~0 new issues. Feature work continues.

 Phase 2 — CLEAN AS YOU TOUCH (ongoing, zero extra budget)
   Editing a legacy line pulls it into new-code scope → devs fix the lines
   they touch. Hot files (most-edited) converge fastest, for free.

 Phase 3 — SURGICAL CAMPAIGNS (scheduled, scoped)
   Pick the few HIGH-RISK modules the gate will never reach (rarely touched,
   security-sensitive). Time-box a remediation sprint per module, outside the gate.

 Phase 4 — MEASURE THE RIGHT THING
   Track "new-code rating = A" and trend of overall issues, NOT absolute count.
   Celebrate the trend, not zero.
```

The key insight is that the 18,000 number is the *wrong target*. Forcing the gate to evaluate overall code makes every build red and the team disables it (Q14). Switching to new-code-only makes the gate **achievable per PR** so it actually changes behavior, while the organic "clean as you touch" effect (Q43) drains the most actively-developed code for free — and that's also the highest-risk code because it changes most. The only budgeted work is **Phase 3**: a *small, deliberately chosen* set of dangerous-but-stable modules the organic process can't reach, remediated in time-boxed campaigns. The trade-off you're consciously accepting is that some rarely-touched legacy stays at E forever — and that's *fine*, because rarely-touched, never-changing code is low-risk; spending scarce remediation budget there instead of on hot paths is the rookie mistake. Reporting should center the **new-code A rating** and the downward *trend*, never the absolute count, so the team sees progress instead of an unwinnable 18,000.

#### Q71. [Practical] LDAP/SAML SSO is configured but users intermittently can't log in after a restart, and group-to-permission mapping is inconsistent. How do you approach this?

Auth issues in SonarQube split into two layers — **authentication** (who are you) handled by the LDAP/SAML plugin, and **authorization** (what can you do) handled by groups mapped to permission templates — and "intermittent after restart" plus "inconsistent permissions" usually points at the *group sync* and *external-identity* mechanics, not the password check. I start in `logs/web.log` with `sonar.log.level=DEBUG` for the auth component to see whether the failure is bind, search, certificate, or mapping.

```properties
# LDAP: a wrong bind DN, expired service-account password, or unreachable
# host causes intermittent failures when the directory is flaky/load-balanced.
sonar.security.realm=LDAP
ldap.url=ldaps://dc.corp.example:636
ldap.bindDn=CN=svc-sonar,OU=Service,DC=corp,DC=example
ldap.user.baseDn=OU=Users,DC=corp,DC=example
ldap.group.baseDn=OU=Groups,DC=corp,DC=example   # groups must resolve or perms drift
```

The recurring root causes: (1) **group membership is synced at login**, and SonarQube grants permissions via groups → permission templates, so if the `group.baseDn`/search is misconfigured a user authenticates but lands with default (often too few or too many) permissions — the "inconsistent permissions" symptom. (2) For **SAML**, clock skew between the IdP and SonarQube, an expired/rotated signing certificate, or a mismatched `EntityID`/ACS URL causes assertions to be rejected intermittently (e.g., only when a stale cert is hit behind a load balancer). (3) After a **restart**, an LDAP server behind a VIP may resolve to a node that's slow or has a different replication state, producing flaky binds. The remediation pattern: pin and monitor the service-account credential and certificate expiry, make group→permission mapping explicit via **permission templates** so new projects/users get deterministic access, keep a **local fallback admin** account (so a broken realm doesn't lock everyone out), and validate config changes against a staging instance because a bad realm config can prevent *all* logins. The senior framing: separate authN from authZ in your diagnosis — "can't log in" is the plugin/realm/cert layer; "logged in but wrong access" is the group-sync/permission-template layer.

#### Q72. [Practical] How do you configure SonarQube behind a reverse proxy with HTTPS, and what breaks if you get the base URL or context path wrong?

SonarQube is typically fronted by Nginx/Apache/an ALB terminating TLS, and the two settings that must be coherent are the **server base URL** (`sonar.core.serverBaseURL`) and, if you serve it under a subpath, the **web context** (`sonar.web.context`). Getting these wrong doesn't break the dashboard you're staring at — it breaks the things that depend on Sonar *generating absolute URLs*: PR decoration links, email notifications, OAuth/SAML redirect (ACS) URLs, and webhook callbacks.

```nginx
# Nginx terminating TLS, proxying to SonarQube on :9000
location / {
    proxy_pass         http://127.0.0.1:9000;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;   # so Sonar knows it's HTTPS
}
```

```properties
# In sonar.properties — MUST match the public URL users/integrations hit
sonar.core.serverBaseURL=https://sonar.example.com
# Only if served under a subpath like https://tools.example.com/sonar:
# sonar.web.context=/sonar
```

The failure modes when these drift: if `serverBaseURL` is left at `http://localhost:9000`, then PR decoration comments and email links point at *localhost* (useless to users), and SAML/OAuth redirects send the browser to the wrong place, breaking SSO. If `X-Forwarded-Proto` isn't passed, SonarQube may build `http://` links or set insecure cookie/redirect behavior behind your HTTPS proxy — a mixed-content and login-loop generator. If you serve under a subpath but don't set `sonar.web.context`, static assets 404 and the app appears broken. The senior point: the reverse proxy must tell SonarQube the *public* identity (host, scheme, path) via forwarded headers **and** you must mirror that identity in `serverBaseURL`/`web.context`, because Sonar can't infer the externally-visible URL on its own — every integration that relies on a callback or a clickable link depends on it being right.

#### Q73. [Coding] Write a script that calls the SonarQube web API to fail a deployment if a project's gate is red, robust to the analysis still being in progress.

**Problem:** A deploy job needs an independent, scriptable check of the latest Quality Gate status (not relying on the scanner's `wait`), and it must handle the race where the analysis hasn't finished computing yet.

```bash
#!/usr/bin/env bash
set -euo pipefail
SONAR_URL="${SONAR_URL:?}"; TOKEN="${SONAR_TOKEN:?}"; KEY="${1:?projectKey}"
BRANCH="${2:-main}"
DEADLINE=$(( $(date +%s) + 300 ))   # poll up to 5 minutes

while :; do
  # api/qualitygates/project_status returns the gate; analysisId optional.
  resp=$(curl -fsS -u "${TOKEN}:" \
    "${SONAR_URL}/api/qualitygates/project_status?projectKey=${KEY}&branch=${BRANCH}")
  status=$(echo "$resp" | jq -r '.projectStatus.status')

  case "$status" in
    OK)    echo "Gate PASSED for ${KEY}"; exit 0 ;;
    ERROR) echo "Gate FAILED for ${KEY}:"
           echo "$resp" | jq -r '.projectStatus.conditions[]
             | select(.status=="ERROR")
             | "  - \(.metricKey): \(.actualValue) (threshold \(.errorThreshold))"'
           exit 1 ;;
    NONE|"")  echo "No gate computed yet — waiting..." ;;   # analysis in flight
    *)        echo "Unexpected status: $status"; exit 2 ;;
  esac

  [ "$(date +%s)" -lt "$DEADLINE" ] || { echo "Timed out waiting for gate"; exit 3; }
  sleep 10
done
```

- **Why poll instead of trusting the scanner?** A deploy gate is often a *separate* job from the build, so it can't rely on the scanner's in-process `wait`. Querying `api/qualitygates/project_status` makes the check authoritative and decoupled.
- **The race condition handled:** if the deploy fires before the Compute Engine finishes, the API returns status `NONE` (no gate yet); naively treating that as pass/fail is the bug. The loop treats `NONE`/empty as "keep waiting" and only decides on `OK`/`ERROR`, with a deadline so it can't hang forever (exit 3).
- **Edge cases:** `-f` makes `curl` fail on HTTP 4xx/5xx (bad token, unknown project) so the script doesn't silently parse an error page; the `branch` parameter handles non-`main` long-lived branches; the `conditions[]` filter prints exactly *which* metric failed so the deploy log is actionable. **Complexity:** O(deadline/interval) HTTP calls, O(1) memory.
- **Security note:** the token is passed as the curl userinfo (`TOKEN:` with empty password) and sourced from a masked CI secret, never hardcoded — a project-scoped analysis/"execute analysis" token is least-privilege for this.

#### Q74. [Practical] A specific rule produces 300 false positives across the codebase, but it's valuable in 5% of cases. What are your options ranked by blast radius, and which do you choose?

This is a precision-vs-recall governance call, and the senior signal is ranking options by **how narrowly they limit the damage** rather than reaching for the biggest hammer. Every suppression erodes trust in the metric, so you want the *narrowest* tool that solves it.

```
 Option                              Blast radius        When it's right
 ──────────────────────────────────  ──────────────────  ─────────────────────────────
 Fix the 300                          none (ideal)        if they're real (re-examine!)
 Tune the rule's PARAMETER            rule-wide, targeted threshold genuinely wrong for you
 sonar.issue.ignore.multicriteria    path-pattern scoped  whole class of files exempt
   (rule + path regex)                                     (e.g. **/legacy/**)
 @SuppressWarnings / NOSONAR          per-line            the rare true-but-acceptable line
 Deactivate rule in the profile       project/profile     rule has ~no value here
 Disable rule org-wide                 EVERY project       almost never — last resort
```

The judgment: with a rule that's **valuable in 5%** of cases, deactivating it org-wide is wrong — you'd lose the 5% of genuine catches everywhere. The right move depends on the *shape* of the false positives. If they cluster in identifiable paths (a legacy module, generated code, a particular pattern), use **`sonar.issue.ignore.multicriteria`** with a rule-key + path-regex to exempt exactly that class while keeping the rule live everywhere else. If the rule's *threshold* is the problem (e.g., a complexity limit too strict for your domain), **tune the parameter** rather than killing the rule. If the FPs are scattered and unpredictable, you fix or narrowly suppress the genuine cases inline.

```properties
# Exempt one rule in one class of files, keep it active everywhere else
sonar.issue.ignore.multicriteria=e1
sonar.issue.ignore.multicriteria.e1.ruleKey=java:S2245
sonar.issue.ignore.multicriteria.e1.resourceKey=**/test/**
```

What I'd actually do: first *verify* they're truly false positives (300 "FPs" is often a real systemic issue the rule correctly caught), then prefer the **most-scoped** mechanism — pattern-based ignore for a coherent file class, parameter tuning for a wrong threshold — and only deactivate the rule entirely if it genuinely has no value for this codebase. Every choice is documented so the next engineer knows *why* the rule was narrowed, because undocumented suppressions are how a quality program quietly rots.

#### Q75. [Practical] How do you analyze a project where the build is fully containerized (multi-stage Docker) and tests run inside the image? Where does the scanner fit?

The tension is that Sonar needs the **source tree, the compiled artifacts/semantic context, and the coverage report all in the same place** at scan time, but a multi-stage Docker build deliberately *discards* intermediate stages (source, test results, coverage) to keep the final image small. If you run the scanner against the slim runtime image, it sees no source and no coverage. So the rule is: **scan in the build stage that still has everything, or extract the artifacts back out to the CI workspace before scanning.**

```dockerfile
# Multi-stage build — the BUILD stage has source + coverage; final stage doesn't
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -B clean verify        # produces target/classes + jacoco.xml IN this stage

FROM eclipse-temurin:21-jre AS runtime
COPY --from=build /app/target/app.jar /app.jar   # source/coverage NOT copied — gone
```

Two clean patterns. **(A) Scan inside the build stage** — add the `sonar:sonar` invocation to the same `RUN` that builds and tests, so the scanner runs where source, `target/classes` (semantic context), and `jacoco.xml` coexist; pass the token via a BuildKit secret, never an `ARG`/`ENV` (which bakes it into image layers). **(B) Scan in CI, not in Docker** — run build+test+coverage on the CI runner (or `docker build` then copy artifacts out with a build mount), and run the scanner as a normal CI step against the workspace. Pattern B is usually cleaner because it keeps the scanner out of the image, avoids secret-in-layer risk, and lets you use the platform's caching.

```bash
# Pattern A: scan inside the build stage, token via BuildKit secret (not ARG)
# RUN --mount=type=secret,id=sonar \
#     mvn -B verify sonar:sonar -Dsonar.token=$(cat /run/secrets/sonar)
```

The interview-grade point: a multi-stage build's whole purpose is to *throw away* the build context, which is precisely what the scanner needs — so you either insert the scan **before** the discard (build stage) or **reconstitute** the artifacts in the CI workspace. And never inject the Sonar token via `ARG`/`ENV`, because Docker image layers are inspectable and the token would leak; use BuildKit `--mount=type=secret` or, better, keep the scan in CI where masked secrets already exist.

#### Q76. [Practical] After enabling MQR mode (or upgrading to a version where it's default), several dashboards and a custom gate-checking script broke. What changed and how do you fix it?

MQR mode (Multi-Quality Rule, Q29) replaces the legacy "one type + one severity" model with **per-quality impacts** (Security/Reliability/Maintainability, each with an impact level) and clean-code attributes. The breakage is because the **web-API metric keys and the semantics of issue properties changed**: scripts and dashboards that queried `bugs`, `vulnerabilities`, `code_smells`, or filtered issues by the legacy `severity`/`type` now hit metrics that behave differently or return data through new keys (e.g., impact-based software-quality counts). Quality-gate *conditions* built on the old metrics may also evaluate differently.

```
 Legacy queries that break under MQR        MQR-aware replacement
 ───────────────────────────────────────    ─────────────────────────────────────
 metric=bugs / vulnerabilities / code_smells software_quality_* / impact-based metrics
 issue filter: type=BUG, severity=BLOCKER   filter: impactSoftwareQualities,
                                              impactSeverities
 gate condition on "new bugs = 0"            gate condition on reliability-impact count
```

The fix has two paths. **Short term**, if you're not ready to migrate tooling, SonarQube lets you switch the instance to **Standard Experience** (legacy mode) so the old metric keys and behavior return — buying time. **Properly**, you update the consumers: repoint dashboards and scripts to the **impact-based metrics and the new issue-search parameters** (`impactSoftwareQualities`, `impactSeverities`), and rewrite gate conditions in terms of software qualities (e.g., "0 new issues with High+ impact on Reliability") rather than the old bug/vuln/smell counts. The governance lesson: pick **one mode org-wide** — mixing MQR and Standard across projects makes aggregate reporting incoherent — and treat a mode switch like an API migration with a deprecation window, because it silently changes the meaning of metrics your automation depends on. This is also a strong recency signal in interviews: knowing that MQR changed *metric keys and gate semantics*, not just the UI labels, is what separates someone who's actually done a recent upgrade from someone who read a blog post.

#### Q77. [Practical] Two long-lived branches (`main` and a `release/2.x` maintenance branch) need independent gates and history. How do you set this up and what are the cost/cleanup implications?

Long-lived (vs short-lived/PR) branches each carry **their own analysis history, their own new-code period, and their own gate status** — which is exactly what a maintenance branch needs, because `release/2.x` shouldn't inherit `main`'s baseline. You designate which branches are long-lived (by name pattern, e.g., `main`, `release/*`) so SonarQube persists them durably instead of purging them like PR analyses.

```bash
# Analyze a long-lived branch (each gets independent history + new-code period)
mvn verify sonar:sonar -Dsonar.branch.name=main
mvn verify sonar:sonar -Dsonar.branch.name=release/2.x

# Each long-lived branch can have its OWN new-code definition:
#   main         → reference branch = main's own previous baseline / days
#   release/2.x  → previous_version (release-oriented), set per-branch in the UI
```

The setup decisions: (1) configure the **branch new-code period per branch** — a trunk like `main` often uses reference-branch/days, while a release branch is naturally "previous version" since it's release-cadence; (2) PRs target their respective base (`sonar.pullrequest.base=release/2.x` for a fix going into the maintenance line) so PR new-code is diffed against the right branch. The **cost/cleanup implications** are the real interview content: each long-lived branch consumes database and Elasticsearch storage proportional to its analysis history, and (for paid editions) branch analysis has licensing/edition requirements (Developer Edition+). So you must configure **branch housekeeping** to purge *inactive* long-lived branches after a retention window and aggressively purge short-lived/PR branches after merge — otherwise a team that spins up many `release/*` and feature branches slowly bloats the instance. The senior framing: long-lived branches are a deliberate, durable, storage-consuming choice reserved for branches you genuinely track over time (trunk + supported releases); everything else should be a short-lived/PR analysis that's ephemeral by design (Q49), and housekeeping is the lever that keeps the storage bounded.

### 🔴 Expert — extended

#### Q78. [Practical] SonarQube is now a merge-blocking dependency for 300 repos. It goes down for 90 minutes during business hours. Design both the incident response and the architecture that prevents this severity.

When the gate is a **required** PR check, a SonarQube outage stops *all* merges org-wide — you've made a code-quality tool a tier-1 availability dependency, and that's an architecture decision you must own consciously. The incident response and the prevention are two separate conversations.

**Incident response (right now):** the immediate lever is to make the gate a **soft/non-blocking check during the incident** — temporarily remove SonarQube from required status checks (or flip CI to treat a Sonar timeout as a warning, not a failure) so merges proceed, then re-enable once healthy and rely on the post-merge `main` analysis to catch anything that slipped. This requires the *ability* to degrade gracefully to be designed in advance (a feature flag / branch-protection toggle and a documented runbook), or you'll be hand-editing 300 repos under pressure.

```
 Outage decision tree
 ─────────────────────
 Is Sonar a HARD required check? ──yes──▶ all merges blocked → flip gate to SOFT
                                              (org-level branch-protection toggle / CI flag)
                                          ──▶ restore service, then re-require
 Root cause?  ES bootstrap / disk full / CE wedged / DB down / OOM
   → es.log, ce.log, web.log; disk + heap + DB connectivity first
```

**Prevention (architecture):** for this severity you move to **Data Center Edition**, which clusters the application and search nodes behind a load balancer with a shared, replicated PostgreSQL, eliminating the single-node SPOF for the app/ES tiers (the DB still needs its own HA — replication/failover). You also resource the single points that take Sonar down: monitor and alarm on **disk** (ES and DB fill up silently), **heap/GC** on web/CE/ES, and **CE queue depth**; put DB on managed HA storage with PITR backups. The most important design choice, though, is **deciding the gate's failure mode on purpose**: hard-required maximizes enforcement but couples merges to Sonar uptime; soft/advisory with post-merge enforcement keeps merges flowing during outages at the cost of letting a few issues land transiently. At 300 repos the mature answer is usually *DCE for HA + a documented "degrade to soft" runbook* so a Sonar incident degrades quality enforcement rather than halting the entire org's delivery — availability of the dev pipeline outranks catching every issue during a 90-minute window.

#### Q79. [Practical] Across the org, ratings and "debt" numbers aren't comparable between teams — some games them with exclusions and "won't fix." How do you detect and govern this without becoming the quality police?

The failure here is that the *metrics are locally controllable*: a team can inflate its rating by excluding files, mass-marking issues "won't fix," or tuning their profile's thresholds — so cross-team comparison becomes apples-to-oranges and the numbers lose meaning. The governance answer is to **remove the gameable degrees of freedom from local control** rather than policing dashboards after the fact.

```
 Gameable lever                       Governance control
 ──────────────────────────────────   ──────────────────────────────────────────
 Profile rule deactivation             LOCKED parent profile (inherited) — teams can
                                        ADD rules, never remove the baseline (Q46)
 Threshold tuning to pass               standard org Quality Gate applied by default;
                                        variants require documented exception
 sonar.exclusions to hide files         audit exclusions via web API; flag broad globs
 Mass "won't fix" / "accept"            require a justification comment; review the
                                        won't-fix rate as a metric itself
 Per-project new-code baseline fudge    standardize reference-branch baseline org-wide
```

Detection is *automatable* via the web API: periodically pull each project's **exclusion patterns**, **won't-fix/accept counts and rates**, **profile deltas from the parent**, and **gate definition** into a report, and flag outliers — a project with 40% of its files excluded or an abnormally high won't-fix rate is a signal to *have a conversation*, not to punish. The structural controls do the heavy lifting: an **inherited locked parent profile** means a team physically cannot drop the security/reliability baseline (only add to it), a **default org gate** means thresholds aren't per-team negotiable without an explicit exception process, and **standardized reference-branch new-code** removes baseline-fudging. The cultural framing that keeps you from becoming the quality police: make the baseline **small and clearly justified** (security + reliability only), give teams freedom *above* it, and treat the metrics as **trends and conversation-starters, not a leaderboard to rank people on** — gaming is usually a response to being measured punitively, so the fix is partly to stop using the numbers as a stick. The senior point: you govern *comparability* by locking the non-negotiable inputs (profile baseline, gate, baseline definition) centrally and auditing the still-local levers (exclusions, won't-fix) for outliers, not by manually inspecting every project.

#### Q80. [Practical] Coverage is reported at 92% but production keeps shipping logic bugs that "should have been tested." Explain to leadership why the number is misleading and what to measure instead.

The 92% is **execution coverage, not assertion coverage** — and that distinction is the whole problem. SonarQube imports a coverage report that marks a line/branch "covered" if a test *executed* it; it has no way to know whether the test *asserted* anything about the result. A test that calls a method and checks nothing still counts every line it touched as covered. So 92% can mean "92% of lines were run by tests" while the tests verify almost nothing — exactly the scenario where logic bugs ship despite a green coverage number.

```
 What 92% line coverage DOES mean      What it does NOT mean
 ─────────────────────────────────     ──────────────────────────────────────
 92% of lines were EXECUTED by a test  92% of behavior is VERIFIED
 the code paths ran without crashing   the outputs were checked for correctness
                                        edge cases / error paths were asserted
                                        the RIGHT thing happens, not just "it ran"
```

There are two compounding blind spots to surface for leadership. First, **assertion-free tests**: coverage tools can't see assertions, so a suite can be 92% covered and near-useless; this is why coverage is *necessary but not sufficient*. Second, **branch vs line** (Q42): SonarQube's composite folds condition coverage into the denominator, but teams chasing only the headline line number can have high line coverage with low branch coverage — meaning the decision logic (the part that ships bugs) is barely exercised. What to measure instead: track **branch/condition coverage** alongside line coverage (insist both sides of conditionals run), invest in **mutation testing** (e.g., PIT/Stryker) which deliberately injects faults and checks whether tests *catch* them — that directly measures assertion strength, the thing coverage can't; and pair coverage with **escaped-defect rate** (bugs found in prod vs caught pre-merge) as the real outcome metric. The message to leadership: coverage is a *floor* ("did we even run this code?"), not a *ceiling* of confidence — raising it has diminishing returns past a point, and the cure for "tested code still has bugs" is test *quality* (assertions, edge cases, mutation testing), not test *quantity* (a higher coverage percentage).

#### Q81. [Practical] You must integrate findings from ESLint, a Go linter, and a custom security tool into one SonarQube view without writing SonarQube plugins. How, and what are the limits of imported issues?

This is the **external issues** mechanism, and the senior signal is reaching for it *before* writing a custom rule/plugin — it's the cheapest way to centralize findings: you run the existing linters in CI as you already do, emit reports, and tell the scanner to ingest them so they appear in SonarQube's UI alongside native issues. SonarQube understands several native report formats (ESLint, and others) and a **generic external-issues JSON format** for anything else (your custom security tool).

```properties
# Native-format imports (no plugin needed)
sonar.eslint.reportPaths=eslint-report.json
sonar.go.golangci-lint.reportPaths=golangci.xml

# Generic format for ANY tool — emit this JSON shape, then point Sonar at it
sonar.externalIssuesReportPaths=custom-sec-report.json
```

```json
{
  "rules": [
    { "id": "ACME-SEC-001", "name": "Hardcoded crypto key",
      "engineId": "acme-sec", "cleanCodeAttribute": "TRUSTWORTHY",
      "impacts": [ { "softwareQuality": "SECURITY", "severity": "HIGH" } ] }
  ],
  "issues": [
    { "ruleId": "ACME-SEC-001",
      "primaryLocation": {
        "message": "Hardcoded AES key found",
        "filePath": "src/crypto/Keys.java",
        "textRange": { "startLine": 12, "endLine": 12 } } }
  ]
}
```

The **limits** are the crux of the answer and what distinguishes external issues from native ones. Imported issues are largely **read-only and second-class**: you **cannot manage their rules in Quality Profiles** (the rule isn't owned by a SonarQube analyzer, so you can't activate/deactivate/tune it centrally), they don't get the full SonarQube **issue lifecycle/tracking** the way native issues do, and historically they were **excluded from Quality Gate conditions** (or treated specially) — so an imported ESLint error may show in the UI but not *fail the gate* the way a native rule does. They also don't benefit from SonarQube's semantic engine, taint analysis, or new-code creation-date backdating in the same way. So the decision tree: use **external issues** to *centralize visibility* cheaply (one pane of glass) when the other tool already does the detection well; write a **custom SonarQube rule** only when you need the finding to be a first-class, gate-enforcing, profile-managed, lifecycle-tracked issue inside SonarQube. The mistake is writing a custom plugin to reproduce ESLint when an import gives you 90% of the value for none of the maintenance cost — and the counter-mistake is assuming imported issues will *block merges* like native ones, when by default they typically won't.

#### Q82. [Practical] A monorepo has Java, TypeScript, Python, and Terraform, each owned by a different team, but it's one git repo. How do you structure SonarQube projects, gates, and ownership?

The core tension is that one git repo wants to be *many* SonarQube projects so that gates, history, ownership, and CE parallelism map to the teams that actually own each piece — but a naive single scan of the whole repo lumps everything into one project with one gate and one history, which serves no one. The structuring decision is **one SonarQube `projectKey` per independently-owned, independently-deployable unit**, not one per git repo.

```
 git monorepo (one repo)                SonarQube (multiple projects)
 ───────────────────────                ──────────────────────────────────────
 /services/payments  (Java)    ───────▶ key: monorepo:payments   (team: payments)
 /web/portal         (TS)      ───────▶ key: monorepo:portal     (team: web)
 /ml/scoring         (Python)  ───────▶ key: monorepo:scoring    (team: ml)
 /infra              (Terraform)──────▶ key: monorepo:infra      (team: platform)
        each: own gate, own profile (per language), own history, own CE task
```

The implementation: each unit gets its own scanner invocation scoped to its subtree (`sonar.sources` pointing at that path, the right language analyzer inputs — `lcov.info` for TS, coverage XML for Python/Java, the Terraform analyzer for `/infra`), its own **projectKey**, and is assigned to its team via **permission templates**. This buys three things: (1) **independent gates** so the payments team's red gate doesn't block the web team's merge; (2) **CE parallelism** — separate keys process in parallel instead of serializing behind one giant project (Q54/Q69); and (3) **clean ownership and history** per team. Each language uses its own **Quality Profile** (inherited from the org parent), and CI is wired so a change under `/services/payments` triggers only that project's analysis (path-filtered jobs) rather than re-scanning the whole repo on every commit. The cross-cutting concern interviewers probe: PR analysis must still **diff against the right base** per project, and you typically use **path-based CI triggers** so a one-line TS change doesn't pay for a full Python+Java+Terraform scan. The expert framing: SonarQube projects model *ownership and deployability*, not git-repo boundaries — in a monorepo you deliberately fan one repo out into several projects so enforcement, parallelism, and accountability align with the teams, while a polyrepo would naturally be one project each.

#### Q83. [Practical] Define metrics to evaluate whether your SonarQube rollout is actually improving quality (not just producing green gates). What's a vanity metric here and what's a real one?

The trap is optimizing for the *instrument* instead of the *outcome*: "gate pass rate went to 99%" can mean either "quality improved" or "we loosened the gate / excluded everything / mass-marked won't-fix until it passed." A rollout's success has to be measured against **outcomes that SonarQube doesn't directly control**, triangulated with adoption metrics — and you must explicitly call out which numbers are gameable.

```
 Vanity / gameable metric              Real outcome metric (harder to game)
 ──────────────────────────────────    ──────────────────────────────────────────
 Total issue count → 0                 Escaped-defect rate (prod bugs that the
   (achieved by exclusions/won't-fix)    gate SHOULD have caught) trending down
 Gate pass rate → 100%                 Change-failure rate / MTTR (DORA) improving
   (achieved by loosening the gate)    
 Coverage % → high                     Branch coverage + mutation score (assertion
   (achieved by assertion-free tests)    strength, Q80) — did tests get stronger?
 "We onboarded 300 repos"              Connected-Mode adoption + issues caught in
   (onboarded ≠ used)                    IDE/PR vs post-merge (shift-left working?)
 New-code gate green                   New-code A-rating sustained while VELOCITY
                                         (PR throughput) holds — quality without drag
```

The real metrics fall into three buckets. **Outcome:** escaped-defect rate (production bugs of a class SonarQube *should* catch — null derefs, injection — that still shipped) is the truest signal that static analysis is adding value; pair it with DORA's **change-failure rate** and **MTTR** to confirm quality isn't just theater. **Leading/shift-left:** the ratio of issues caught in the **IDE (SonarLint) or PR** versus post-merge — a healthy rollout pushes detection left, so this ratio should rise; Connected-Mode adoption is the enabler. **Test quality, not quantity:** branch coverage and a **mutation score** instead of raw line coverage (Q80). The discipline is to also **watch the anti-signals**: rising won't-fix rate, broadening exclusion globs, and shrinking analyzed LOC are how a team manufactures green gates without improving anything — so you instrument those as guardrails (Q79). The senior framing for leadership: a green gate is a *process* metric (we followed the rule), while escaped defects, change-failure rate, and shift-left ratio are *outcome* metrics (the code got safer) — report outcomes, use process metrics only as leading indicators, and treat any metric the team can move *without changing the code* as a vanity metric to be cross-checked.

#### Q84. [Practical] Walk through diagnosing "SonarQube was working, now every analysis fails with an out-of-memory or the server keeps restarting." What are the distinct OOM domains?

"OOM" in SonarQube is ambiguous because there are **three independent JVMs** plus the scanner, each with its own heap and its own failure signature — the first diagnostic step is identifying *which* process is dying, because they have different fixes. The server runs the **Web**, **Compute Engine**, and **Elasticsearch** processes; the scanner runs separately on the CI agent.

```
 Which JVM is OOMing?                  Log to read           Heap setting
 ─────────────────────────────────     ───────────────────   ─────────────────────────
 Compute Engine (processing reports)   logs/ce.log           sonar.ce.javaOpts -Xmx
 Web server (UI / API requests)        logs/web.log          sonar.web.javaOpts -Xmx
 Elasticsearch (search/index)          logs/es.log           sonar.search.javaOpts -Xmx
 Scanner (on the CI agent)             CI build log          SONAR_SCANNER_OPTS -Xmx
```

The diagnosis: read the **right log** for the OutOfMemoryError / GC-overhead message and the process restart pattern. The **most common** OOM is the **Compute Engine** processing a large analysis report (a huge monorepo, an explosion in issue count, or many concurrent projects) — fix by raising `sonar.ce.javaOpts -Xmx` and/or reducing report size via exclusions. **Web** OOM points at heavy API/UI load or large responses — raise `sonar.web.javaOpts`. **Elasticsearch** OOM/health issues show in `es.log` and often correlate with index growth or insufficient `sonar.search.javaOpts` heap and host memory pressure. **Scanner** OOM is on the *CI agent*, unrelated to the server — bump `SONAR_SCANNER_OPTS=-Xmx`. The "was working, now fails" framing usually means something *grew*: issue count after a profile change, repo size, branch/PR proliferation bloating ES, or **disk filling up** (ES will go red and the server thrash when disk is low, which masquerades as instability). So the full checklist is: identify the dying JVM via its log, check **host memory and disk headroom** (an OOM is sometimes really a disk-full or an over-committed host where the three JVMs' heaps exceed RAM), then tune the specific `*.javaOpts` heap and/or shrink the workload (exclusions, project splitting, branch housekeeping). The expert point is refusing to treat "SonarQube is OOMing" as one problem — naming the three server JVMs plus the scanner, and knowing each has a separate heap knob and log, is what makes the diagnosis fast instead of a blind heap-bump-everything flail.

#### Q85. [Behavioral] A senior engineer insists on disabling SonarQube entirely, calling it "noise that slows us down." You believe it's net-positive when configured right. How do you handle the disagreement?

The instinct to either capitulate or pull rank both lose; a strong answer treats the engineer's complaint as **probably-valid evidence about the current configuration**, separates the tool from its misconfiguration, and resolves it with data rather than authority. Using a **STAR**-ish structure: *Situation* — a respected senior wants Sonar gone, and others are listening, so it's both a technical and a credibility moment. *Task* — preserve the value of static analysis without dismissing a smart colleague who's clearly hitting real friction.

*Action:* First I'd **listen specifically** — "noise that slows us down" almost always decomposes into concrete grievances: the gate enforces legacy debt so every build is red (Q14), a handful of rules generate 80% of the false positives, or the CI `wait` holds runners forever. Those are *real configuration failures*, and conceding them builds credibility. Then I'd propose a **time-boxed experiment** instead of a debate: pick the rules they hate and prune them, switch the gate to **new-code only** so their existing debt becomes informational, roll out **SonarLint Connected Mode** so issues are caught at keystroke time instead of ambushing them in the PR, and pilot on one service for two sprints with me personally triaging false positives. I'd let *them* watch it catch a real null-deref or injection in a PR before it shipped. *Result framing:* if after the pilot it's still net-negative *for that team*, I'd genuinely support turning it off for them — being willing to lose the argument if the data says so is what makes the position credible, not stubbornness.

The meta-points interviewers are listening for: (1) you don't conflate "the tool is bad" with "the tool is misconfigured" — most Sonar revolts are the latter; (2) you treat a skeptical senior as a **source of signal**, not an obstacle, because if a strong engineer finds it noisy, it probably *is* noisy as set up; (3) you resolve technical disagreements with **scoped experiments and evidence**, not seniority or process mandates; and (4) you hold the value proposition loosely enough to abandon it if proven wrong, which is precisely what earns you the right to be believed when the data goes your way. Quality tooling adopted by mandate over a respected engineer's objection tends to get quietly sabotaged; adopted because the skeptic was won over with evidence, it sticks.

#### Q86. [Practical] Your SonarQube database has grown to 400 GB and queries/backups are slow. What drives that growth and how do you bring it under control without losing what matters?

Database bloat in SonarQube is almost always driven by **analysis history multiplied by branch/PR proliferation**, not by the current snapshot of issues. Every analysis writes a snapshot; thousands of short-lived PR branches and inactive long-lived branches each accumulate their own snapshots and issues, and if housekeeping is left at defaults (or disabled) they never get purged. So a 400 GB DB usually means "we kept every PR analysis and every dead branch forever," not "we have 400 GB of real code findings."

```
 Growth driver                         Control (Administration → Housekeeping)
 ─────────────────────────────────     ──────────────────────────────────────────
 Short-lived/PR branch analyses        purge PR/branch data N days after last analysis
 Inactive long-lived branches          delete branches inactive > retention window
 Per-analysis snapshot retention       keep only one snapshot per day/week/month after
                                         a threshold (don't keep every commit forever)
 Closed-issue retention                purge closed issues after N days
 Audit/activity logs (Enterprise)      separate retention; can dominate at scale
```

The fix is to **configure housekeeping aggressively and then reclaim space**. Housekeeping settings let you collapse old snapshots (keep one per week beyond a month, one per month beyond a year), purge PR/branch data shortly after the branch goes inactive, and drop closed issues after a retention window — none of which loses the *durable trend* on your main branches, which is what actually has analytical value. After tightening housekeeping, the rows are deleted but PostgreSQL won't return disk to the OS automatically; you reclaim it with a maintenance `VACUUM FULL`/reindex during a window (it locks tables, so schedule it). The operational lesson interviewers want: SonarQube's DB grows with *number of analyses and branches*, not code size, so the levers are **branch/PR housekeeping + snapshot retention** (prevent regrowth) plus a one-time DB maintenance to reclaim space — and the thing you must *not* purge is the long-lived main-branch history that powers your trends. Backups slow down for the same reason, so controlling growth fixes both the query latency and the backup window at once.

#### Q87. [Practical] Analysis results aren't reproducible — the same commit scanned last month and today yields different issue counts with no code change. What causes non-determinism and how do you make scans reproducible?

"Same code, different results" feels like a bug but is usually a **moving ruleset** — the analyzer plugins, the Quality Profile, or the new-code baseline changed underneath the code. SonarQube analysis is deterministic *given fixed inputs*, but several inputs silently drift over time, and reproducibility requires pinning them. The biggest culprit is **analyzer/plugin versions**: upgrading the SonarQube server or a language analyzer adds, removes, or changes rules (Q47), so the same source produces different findings — by design, not by accident.

```
 Source of non-determinism             How to pin it for reproducibility
 ─────────────────────────────────     ──────────────────────────────────────────
 Analyzer/plugin version changed       pin server + analyzer versions; treat upgrades
   (rules added/removed/retuned)         as deliberate, reviewed change events
 Quality Profile edited                version-control the profile (export XML to git);
   (rules toggled/params tuned)          change it via PR, not ad-hoc in the UI
 New-code baseline moved               fix the baseline definition (reference branch);
   (different "new" set)                 don't let it drift between days/version bumps
 Missing/partial bytecode              ensure sonar.java.binaries is always populated —
   (semantic rules silently off)        a build that skipped compile gives fewer issues
 Different scanner env (JDK, deps)     pin scanner image, JDK, and dependency versions
```

The reproducibility discipline: **version-control your Quality Profiles** (export them as XML via `api/qualityprofiles/backup` and commit them, so a profile change goes through review and is auditable rather than someone quietly toggling a rule in the UI), **pin the server and analyzer plugin versions** so a finding count change is always traceable to a deliberate upgrade, and **standardize the scanner environment** (a fixed scanner image, JDK, and resolved dependency set) because missing bytecode or a different classpath flips semantic rules on/off and changes counts. The second-most-common surprise is the **new-code baseline drifting** — if it's "previous version" and someone bumped `sonar.projectVersion`, or it's "30 days" and time passed, the *new-code* issue set legitimately changes with no code change; pinning to a reference branch makes new code a pure function of the diff. The senior framing: SonarQube is reproducible only to the extent its *inputs* (ruleset, profile, baseline, semantic context) are pinned — so "make scans reproducible" is really "treat the profile and analyzer versions as versioned configuration," and an unexpected count change is a signal that one of those inputs moved, which you should be able to attribute, not a flaky tool.

#### Q88. [Practical] How do you safely run PR analysis with decoration for pull requests coming from forks (open-source or untrusted contributors) without leaking your SONAR_TOKEN?

This is a real security incident waiting to happen: PR decoration needs the `SONAR_TOKEN` (and a DevOps-platform token) to post results back, but a PR **from a fork** runs CI with whatever the contributor wrote in the workflow/build — so if you expose your secrets to fork-PR builds, a malicious contributor can exfiltrate the token by adding a step that prints or POSTs it. CI platforms know this, which is why, for example, GitHub Actions does **not** pass repository secrets to workflows triggered by `pull_request` from a fork by default. So the naive setup (scan + decorate on every PR) either fails for forks (no token) or, if you "fix" it by exposing secrets, hands your token to strangers.

```
 Trigger                         Secrets available?   Safe to decorate?
 ──────────────────────────────  ──────────────────   ────────────────────────────────
 PR from a branch in YOUR repo   yes                  yes — trusted, full flow
 PR from a FORK (pull_request)    NO (by design)       can't decorate; DON'T expose token
 pull_request_target / labeled    yes (DANGEROUS)      only after human review/trust gate
```

The safe pattern is **two-phase, trust-gated analysis**. Phase one: on a fork PR, run the *build and tests* in the untrusted context with **no secrets** — you get compilation and test results but skip the Sonar upload (or run a local/Community analysis that needs no token). Phase two: run the **scan-and-decorate** in a *trusted* context that has the secret, but only after the code has been vetted — e.g., a maintainer applies a "safe to test" label that triggers a separate workflow, or the analysis runs **post-merge on the protected branch** where the token lives safely. The anti-pattern to call out explicitly is using GitHub's `pull_request_target` (which *does* expose secrets to fork PRs) while checking out the **PR head** code — that combination runs untrusted code with your secrets and is a well-known token-exfiltration vector; if you use `pull_request_target` you must check out only the *base* and gate on human approval. The senior framing: a fork PR is **untrusted code execution**, so the rule is *never let untrusted code run in a context that holds your SonarQube/DevOps tokens* — you either decorate only for same-repo PRs and rely on post-merge analysis for forks, or you put an explicit human trust gate between "code arrived" and "secrets are in scope." Protecting the token isn't optional polish; a leaked analysis token can let an attacker push poisoned analyses or, depending on scope, reach admin functions.

#### Q89. [Coding] Write an idempotent webhook receiver for SonarQube Quality Gate events that posts failures to Slack, and explain the delivery guarantees you must handle.

**Problem:** Instead of holding CI runners with `qualitygate.wait`, the team wants SonarQube to *push* gate results (Q34) to a small service that notifies Slack on failures. The receiver must verify authenticity, be idempotent against retries, and not block.

```python
import hashlib, hmac, os, json
from flask import Flask, request, abort
import requests

app = Flask(__name__)
SECRET = os.environ["SONAR_WEBHOOK_SECRET"].encode()   # shared secret set in Sonar
SLACK_URL = os.environ["SLACK_WEBHOOK_URL"]
_seen = set()   # in prod: Redis/DB with TTL, not an in-memory set

@app.post("/sonar-webhook")
def handle():
    raw = request.get_data()  # MUST hash the RAW body, not the parsed JSON
    # 1) AUTHENTICITY: Sonar signs the payload with HMAC-SHA256 in this header
    sig = request.headers.get("X-Sonar-Webhook-HMAC-SHA256", "")
    expected = hmac.new(SECRET, raw, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(sig, expected):   # constant-time compare
        abort(401)

    evt = json.loads(raw)
    # 2) IDEMPOTENCY: dedupe on the analysis identity (retries resend the same event)
    key = (evt.get("project", {}).get("key"), evt.get("taskId") or evt.get("analysedAt"))
    if key in _seen:
        return "", 200          # already processed — ack so Sonar stops retrying
    _seen.add(key)

    # 3) Only act on failures; ack everything fast so Sonar doesn't retry
    gate = evt.get("qualityGate", {})
    if gate.get("status") == "ERROR":
        failed = [c["metricKey"] for c in gate.get("conditions", [])
                  if c.get("status") == "ERROR"]
        proj = evt["project"]["name"]
        branch = evt.get("branch", {}).get("name", "?")
        requests.post(SLACK_URL, json={
            "text": f":red_circle: Quality Gate FAILED — *{proj}* ({branch})\n"
                    f"Failing: {', '.join(failed)}\n{evt['project'].get('url','')}"
        }, timeout=5)
    return "", 200              # 2xx tells Sonar the delivery succeeded
```

- **Delivery guarantee — at-least-once:** SonarQube retries a webhook if the receiver doesn't return `2xx` quickly, so the *same* event can arrive multiple times. That's why the handler is **idempotent** (dedupe on `project.key + taskId`/analysis id) and returns `200` even for an already-seen event — otherwise a transient slow Slack call causes Sonar to retry and you double-post. In production `_seen` must be a shared store (Redis with TTL), not an in-memory set, or a multi-replica receiver won't dedupe across instances.
- **Authenticity:** the payload is signed with the **shared secret** as HMAC-SHA256 over the *raw* body; you must hash the raw bytes (re-serializing the parsed JSON changes the bytes and breaks the signature) and use a **constant-time** comparison (`hmac.compare_digest`) to avoid timing attacks. Without verification, anyone who learns the URL can spoof gate events.
- **Ack fast, work async:** the handler should return `2xx` promptly; if Slack is slow, a real implementation enqueues the notification and acks immediately, because holding the response open invites Sonar's retry timeout and duplicate deliveries. The `timeout=5` bounds the blocking call as a minimal guard.
- **Per-analysis semantics:** the webhook fires **per analysis**, carrying the project/branch/PR identity, so you key notifications off that payload rather than assuming a single global status — the same project can have concurrent PR and main-branch events. **Complexity:** O(1) per event; memory O(deduped events) bounded by the store's TTL.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q90. [Coding] Configure a Gradle Java project from scratch so a single `gradle sonar` task builds, tests, produces coverage, and uploads to SonarQube.

**Problem:** A green-field Gradle service has no SonarQube wiring. Make `./gradlew test sonar` do everything, with JaCoCo coverage importing correctly. The classic Gradle mistake is forgetting that the JaCoCo *XML* report is off by default and that `sonar` must run *after* `test`.

```groovy
// build.gradle  (Groovy DSL)
plugins {
    id 'java'
    id 'jacoco'
    id 'org.sonarqube' version '5.1.0.4882'   // SonarScanner for Gradle
}

test {
    finalizedBy jacocoTestReport        // always produce the report after tests
}

jacocoTestReport {
    dependsOn test
    reports {
        xml.required = true             // CRITICAL: Sonar imports XML, not .exec
        html.required = false
    }
}

sonar {                                  // 'sonarqube' extension was renamed 'sonar'
    properties {
        property 'sonar.projectKey', 'payments-api'
        property 'sonar.host.url',  System.getenv('SONAR_HOST_URL')
        // token comes from SONAR_TOKEN env var — never hardcode it
        property 'sonar.coverage.jacoco.xmlReportPaths',
                 "${layout.buildDirectory.get()}/reports/jacoco/test/jacocoTestReport.xml"
    }
}
```

```bash
SONAR_TOKEN=*** SONAR_HOST_URL=http://localhost:9000 \
  ./gradlew test sonar -Dsonar.qualitygate.wait=true
```

- **Why `xml.required = true`?** This is the single most common Gradle-coverage failure: JaCoCo's Gradle plugin generates the binary `.exec` and (by default) only the HTML report — the **XML** that Sonar imports is *off*, so coverage silently shows 0%. You must explicitly enable it and point `sonar.coverage.jacoco.xmlReportPaths` at it.
- **Why `finalizedBy`/`dependsOn`?** Ordering is load-bearing: the `sonar` task consumes the coverage XML, so tests and the report must have run first. The Gradle scanner is smart enough to depend on compilation for semantic analysis (it reads `build/classes`), but it does *not* auto-run your tests — if you call `./gradlew sonar` alone you'll scan with stale or absent coverage.
- **The naming gotcha:** the extension/task was `sonarqube` in older plugin versions and is now `sonar` (the old name is a deprecated alias). Pin the plugin version rather than `+` so analysis is reproducible (Q87) — a plugin bump can change the bundled analyzer and thus your issue count. **Complexity:** the scan is O(LOC) over analyzed sources; coverage import is O(report size).

#### Q91. [Coding] Set up SonarQube analysis for a JavaScript/TypeScript project (no build plugin) including coverage and ESLint import.

**Problem:** A front-end repo has no Maven/Gradle, uses Jest for tests and ESLint for linting. Wire it to SonarQube using the **standalone SonarScanner CLI** so that coverage and existing ESLint findings both land in SonarQube. The trap is that for dynamic languages there is no compiled artifact, so the scanner relies entirely on `sonar-project.properties` and the lcov/ESLint reports you feed it.

```properties
# sonar-project.properties  (committed; identity + layout, no secrets)
sonar.projectKey=web-portal
sonar.sources=src
sonar.tests=src
sonar.test.inclusions=**/*.test.ts,**/*.test.tsx
sonar.exclusions=**/*.test.ts,**/node_modules/**,**/dist/**,**/*.min.js

# Coverage: Jest emits lcov; paths inside lcov.info must match analyzed sources
sonar.javascript.lcov.reportPaths=coverage/lcov.info

# Reuse ESLint instead of re-implementing its rules in Sonar (Q81)
sonar.eslint.reportPaths=eslint-report.json

# TypeScript: give the analyzer the tsconfig so it can resolve types (Q24)
sonar.typescript.tsconfigPaths=tsconfig.json
```

```bash
# Produce the reports, THEN scan
npx jest --coverage --coverageReporters=lcov
npx eslint . -f json -o eslint-report.json || true   # don't let lint failure abort scan
SONAR_TOKEN=*** sonar-scanner -Dsonar.host.url="$SONAR_HOST_URL"
```

The subtleties that distinguish a working setup from a broken one: (1) **lcov path alignment** — Jest writes source paths into `lcov.info` relative to where it ran; if the scanner's source root differs, Sonar imports the file but maps zero lines (the JS twin of the Java path-mismatch in Q60). (2) **`tsconfig` for type resolution** — TypeScript rules degrade to syntactic-only without it, because there's no bytecode; pointing at `tsconfig.json` (and having `node_modules` installed so type defs resolve) is the dynamic-language equivalent of `sonar.java.binaries`. (3) **`|| true` on ESLint** — you want ESLint's *report* regardless of its exit code, so a lint failure doesn't kill the pipeline before the scan; the gate, not the lint exit code, should decide pass/fail.

The senior framing: for compiled languages the build plugin derives most config; for JS/TS you own every input explicitly, and the two highest-value-but-easiest-to-misconfigure inputs are the **lcov path** (coverage) and **tsconfig** (semantics). Reusing ESLint via import (rather than a custom Sonar rule) is the cheap, correct default — accepting that imported issues are read-only and may not fail the gate by default (Q81).

### 🟡 Intermediate — extended

#### Q92. [Coding] Write a multi-module Maven aggregator that merges per-module JaCoCo data into one report Sonar can import.

**Problem:** A 4-module Maven project has unit tests in each module and integration tests that span modules. Per-module `jacoco.xml` files miss cross-module coverage (an IT in module D exercises code in module A but A's own report doesn't see it). The fix is a dedicated **report-aggregate** module that merges all modules' execution data into one report (Q7's "production reality").

```xml
<!-- report-aggregate/pom.xml — a module whose ONLY job is to merge coverage -->
<project>
  <artifactId>report-aggregate</artifactId>
  <dependencies>
    <!-- depend on every module so their classes + exec data are on the path -->
    <dependency><groupId>com.acme</groupId><artifactId>module-a</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>module-b</artifactId><version>${project.version}</version></dependency>
  </dependencies>
  <build><plugins>
    <plugin>
      <groupId>org.jacoco</groupId>
      <artifactId>jacoco-maven-plugin</artifactId>
      <version>0.8.12</version>
      <executions>
        <execution>
          <id>report-aggregate</id>
          <phase>verify</phase>
          <goals><goal>report-aggregate</goal></goals>  <!-- the key goal -->
        </execution>
      </executions>
    </plugin>
  </plugins></build>
</project>
```

```properties
# Root sonar config: point Sonar at BOTH per-module reports AND the aggregate
sonar.coverage.jacoco.xmlReportPaths=\
  ${project.basedir}/module-a/target/site/jacoco/jacoco.xml,\
  ${project.basedir}/report-aggregate/target/site/jacoco/aggregate.xml
```

- **Why `report-aggregate` and not `merge`?** JaCoCo offers two related goals: `merge` combines `.exec` *binary* files into one `.exec` (raw data), while `report-aggregate` reads the dependency modules' exec data and produces a single *cross-module XML report* with source/class mapping intact — which is exactly what Sonar imports. Using `merge` alone leaves you back at needing a report step.
- **Why list both module reports and the aggregate?** Sonar takes the *union* of all `xmlReportPaths`; the per-module reports cover unit tests, the aggregate covers cross-module integration tests, and Sonar de-duplicates at the line level. Listing only the aggregate can miss module-local unit coverage if your IT suite doesn't exercise it.
- **Edge cases:** the aggregate module must `dependsOn` (Maven `<dependency>`) every analyzed module so their `target/classes` and exec data resolve; build order matters (`report-aggregate` runs in `verify`, after all modules' tests). **Complexity:** O(total exec records) to merge, O(LOC) to render — negligible next to the test run itself. The deeper point (Q60): "0% / wrong coverage" in multi-module builds is almost always missing aggregation or a path that doesn't resolve, not a SonarQube bug.

#### Q93. [Coding] Implement a SonarQube CI stage in GitLab CI that fails the merge request on a red gate and caches the scanner.

**Problem:** Translate the GitHub-Actions-centric examples (Q10/Q64) to GitLab CI, where the gotchas are different: `GIT_DEPTH` defaults to a shallow clone (breaking new-code detection, Q65) and the scanner work directory should be cached to keep MR feedback fast.

```yaml
# .gitlab-ci.yml
sonarqube-check:
  stage: test
  image: maven:3.9-eclipse-temurin-21
  variables:
    SONAR_USER_HOME: "${CI_PROJECT_DIR}/.sonar"   # cache dir for scanner
    GIT_DEPTH: "0"                                  # full history → SCM blame works
  cache:
    key: "sonar-${CI_JOB_NAME}"
    paths: [ ".sonar/cache" ]
  script:
    - >
      mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar
      -Dsonar.host.url="$SONAR_HOST_URL"
      -Dsonar.token="$SONAR_TOKEN"
      -Dsonar.qualitygate.wait=true
  rules:
    # Decorate MRs (diff vs target branch) and analyze the default branch
    - if: $CI_PIPELINE_SOURCE == 'merge_request_event'
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
  allow_failure: false        # a red gate must fail the pipeline
```

- **`GIT_DEPTH: "0"`** is the GitLab equivalent of `fetch-depth: 0` — GitLab's runner shallow-clones by default, which strips the history SonarQube needs for new-code detection and issue creation dates (Q65). Setting it to 0 fetches full history. This is the most commonly-missed line and the one that quietly corrupts "new code."
- **`allow_failure: false` + `qualitygate.wait=true`** is the two-part enforcement: `wait` makes the scanner block until the Compute Engine returns the gate and exit non-zero on ERROR (Q58), and `allow_failure: false` ensures that non-zero exit actually fails the pipeline/blocks the MR. GitLab also auto-detects the MR context from its predefined CI variables, so the scanner diffs against the target branch without you passing `sonar.pullrequest.*` manually (the GitLab integration maps them).
- **Caching `.sonar/cache`** avoids re-downloading analyzer plugins on every run, shaving MR latency. The trade-off: cache the *plugin* cache, not the analysis work dir, or you risk stale incremental state. The senior framing is that each CI platform has one signature trap — for GitLab it's `GIT_DEPTH`, for GitHub it's `fetch-depth`, for Jenkins it's the "shallow clone" checkbox — and they all corrupt new-code detection identically (Q65).

#### Q94. [Coding] Write a script that imports a Quality Profile as code (GitOps) so profile changes go through PR review.

**Problem:** Teams editing Quality Profiles ad-hoc in the UI makes analysis non-reproducible (Q87) and ungoverned. Treat the profile as code: store the XML in git, and have CI restore it to SonarQube on merge, so a rule change is a reviewable PR.

```bash
#!/usr/bin/env bash
set -euo pipefail
: "${SONAR_URL:?}" "${SONAR_ADMIN_TOKEN:?}"
PROFILE_DIR="${1:-quality-profiles}"

# Restore every committed profile XML into SonarQube (idempotent: restore is upsert)
for xml in "${PROFILE_DIR}"/*.xml; do
  echo "Restoring profile from ${xml}"
  http_code=$(curl -s -o /tmp/resp.json -w '%{http_code}' \
    -u "${SONAR_ADMIN_TOKEN}:" \
    -F "backup=@${xml}" \
    "${SONAR_URL}/api/qualityprofiles/restore")
  if [ "$http_code" != "200" ]; then
    echo "FAILED (${http_code}):"; cat /tmp/resp.json; exit 1
  fi
done
echo "All profiles synced."
```

```bash
# The companion EXPORT step (run periodically or on demand) to capture drift:
curl -s -u "$SONAR_ADMIN_TOKEN:" \
  "$SONAR_URL/api/qualityprofiles/backup?language=java&qualityProfile=Acme%20Java" \
  > quality-profiles/acme-java.xml
```

- **Why this works:** `api/qualityprofiles/backup` serializes a profile (its activated rules + parameters) to XML, and `api/qualityprofiles/restore` re-applies it. Committing the XML and restoring it on merge means *every rule change is a diff in git* — reviewable, attributable, revertible — which directly fixes the non-determinism in Q87 (a profile silently edited in the UI).
- **Idempotency:** `restore` is an upsert (it overwrites the profile's activations to match the XML), so re-running the script converges to the committed state regardless of current server state — safe to run on every merge. The script checks the HTTP code because `curl` alone won't fail the CI job on a 400 (malformed XML) — you must inspect the status (the same lesson as Q58/Q73).
- **Governance tie-in:** combine this with **inherited locked parent profiles** (Q46) — the parent is owned by the platform team's repo, children by product teams, each profile a file under review. This is how you make profiles GitOps-managed across hundreds of repos (Q79) instead of a free-for-all. **Edge case:** `restore` does *not* delete a profile that's no longer in git, so a separate reconciliation step is needed if you want full GitOps "deletes propagate." The token must be an **admin/quality-profile-admin** token, scoped tightly and stored as a CI secret.

#### Q95. [Coding] Write a portable JSON generator that converts an arbitrary tool's findings into SonarQube's generic external-issues format.

**Problem:** You have an in-house security scanner that emits its own JSON. Rather than write a SonarQube plugin (Q41/Q81), convert its output to the **generic external-issues** format so findings appear in SonarQube. Show the transform and the correctness pitfalls.

```python
#!/usr/bin/env python3
"""Convert custom-scanner output -> SonarQube generic external issues JSON."""
import json, sys

SEVERITY_MAP = {"critical": "BLOCKER", "high": "HIGH",
                "medium": "MEDIUM", "low": "LOW", "info": "INFO"}

def convert(findings):
    rules, seen_rules, issues = [], set(), []
    for f in findings:
        rid = f"acme:{f['check_id']}"
        if rid not in seen_rules:                      # one rule entry per unique id
            seen_rules.add(rid)
            rules.append({
                "id": rid, "name": f["title"], "engineId": "acme-sec",
                "cleanCodeAttribute": "TRUSTWORTHY",
                "impacts": [{"softwareQuality": "SECURITY",
                             "severity": SEVERITY_MAP.get(f["sev"], "MEDIUM")}],
            })
        issues.append({
            "ruleId": rid,
            "primaryLocation": {
                "message": f["message"],
                "filePath": f["file"],                 # MUST be relative to sonar.sources
                "textRange": {"startLine": max(1, f["line"])},  # lines are 1-based
            },
        })
    return {"rules": rules, "issues": issues}

if __name__ == "__main__":
    data = json.load(sys.stdin)
    json.dump(convert(data["findings"]), sys.stdout, indent=2)
```

```bash
./scanner --json | python3 to_sonar.py > acme-report.json
sonar-scanner -Dsonar.externalIssuesReportPaths=acme-report.json
```

- **Why generate, not plug in?** A custom plugin (Q41) means Java code compiled against a version-pinned API that breaks on upgrades. The generic format is *zero-maintenance*: emit JSON, point the scanner at it. You trade first-class status (profile management, gate enforcement, lifecycle — Q81) for near-zero cost.
- **Correctness pitfalls baked into the code:** (1) **`filePath` must be relative to the analyzed source root**, or Sonar imports the issue but can't anchor it to a file — the most common failure (mirrors the coverage path issue in Q60). (2) **lines are 1-based**; a tool emitting 0-based lines must be offset (`max(1, ...)` guards the floor). (3) **dedupe the `rules` array** — the format expects each rule declared once with N issues referencing it; duplicate rule ids are rejected. (4) the **`impacts`/`cleanCodeAttribute`** fields are the MQR-mode shape (Q29) the current generic format expects; older docs show a legacy `type`/`severity` shape, so match your server version.
- **Complexity:** O(findings) single pass, O(unique rules) extra memory. **Edge case:** `textRange` with only `startLine` flags the whole line; add `startLineOffset`/`endLineOffset` for column precision. The senior point (Q81): this gives you *one pane of glass* cheaply, but imported issues are read-only and may not fail the gate by default — set expectations accordingly.

#### Q96. [Coding] Write `sonar.issue.ignore.multicriteria` configuration that exempts three different rule/path combinations, and explain precedence.

**Problem:** A real codebase needs: (1) suppress the "weak random" rule in test code, (2) suppress the "TODO" smell in a legacy package, and (3) suppress *all* rules in generated protobuf files — without touching the global profile. Show the exact multicriteria syntax (a frequent stumbling block because of its array-of-keys structure) and the precedence relative to other exclusions.

```properties
# Each criterion has a unique KEY listed in the parent property,
# then .ruleKey and .resourceKey sub-properties. ruleKey/resourceKey
# support wildcards; '*' as ruleKey means "every rule".
sonar.issue.ignore.multicriteria=randTests,legacyTodo,genAll

# 1) Weak-random rule (java:S2245) only in test sources
sonar.issue.ignore.multicriteria.randTests.ruleKey=java:S2245
sonar.issue.ignore.multicriteria.randTests.resourceKey=**/src/test/**

# 2) "Complete the TODO" (java:S1135) only in the legacy package
sonar.issue.ignore.multicriteria.legacyTodo.ruleKey=java:S1135
sonar.issue.ignore.multicriteria.legacyTodo.resourceKey=**/com/acme/legacy/**

# 3) EVERY rule in generated protobuf files
sonar.issue.ignore.multicriteria.genAll.ruleKey=*
sonar.issue.ignore.multicriteria.genAll.resourceKey=**/*.pb.java
```

- **The structure is the trap:** the parent `sonar.issue.ignore.multicriteria` is a comma-separated list of *criterion keys*, and each key gets two sub-properties — `ruleKey` and `resourceKey`. People routinely omit the parent list (so the criteria are silently ignored) or reuse a key (so the second clobbers the first). Both `ruleKey` and `resourceKey` accept `*` wildcards; `ruleKey=*` is the "all rules in these files" form.
- **Precedence vs other mechanisms (the deeper question):** these `ignore` patterns are applied **during analysis**, so the matched issues never reach the server or the gate — unlike a post-hoc "won't fix" status set on a persisted issue. There's also `sonar.issue.enforce.multicriteria` (the inverse — *only* report a rule in matching paths) and the broader scope/coverage/cpd exclusions. The ordering of effect is: `sonar.exclusions` (file leaves analysis entirely) → `sonar.issue.ignore`/`enforce` (issue-level filtering) → persisted-issue statuses. So if a file is in `sonar.exclusions`, the multicriteria never even runs on it.
- **Why prefer this over deactivating the rule (Q74)?** It's *path-scoped*: `java:S2245` stays active everywhere except test code, so you keep the catch in production code while killing the noise where weak randomness is harmless. That narrow blast radius is exactly the "narrowest tool that solves it" principle from Q51/Q74. **Edge case:** `resourceKey` matches the *analyzed* path; for multi-module builds verify whether it's module-relative or project-relative, or your glob silently matches nothing.

#### Q111. [Coding] Analyze a .NET project with the SonarScanner for .NET, and explain why it must *wrap* the build with begin/end steps.

**Problem:** A C# solution needs SonarQube analysis. Unlike the Maven/Gradle plugins, the .NET scanner is a separate tool with a mandatory **three-step `begin → build → end`** structure. Show the commands and explain *why* the build must be sandwiched between begin and end — a detail interviewers use to check real .NET experience.

```bash
# Install once: dotnet tool install --global dotnet-sonarscanner

# 1) BEGIN — hooks the MSBuild pipeline BEFORE compilation
dotnet sonarscanner begin \
  /k:"payments-dotnet" \
  /d:sonar.host.url="$SONAR_HOST_URL" \
  /d:sonar.token="$SONAR_TOKEN" \
  /d:sonar.cs.opencover.reportsPaths="**/coverage.opencover.xml"

# 2) BUILD — compile; the scanner's MSBuild targets observe every project
dotnet build --no-incremental

# 3) Tests + coverage (OpenCover/Coverlet format)
dotnet test --collect:"XPlat Code Coverage" \
  -- DataCollectionRunSettings.DataCollectors.DataCollector.Configuration.Format=opencover

# 4) END — collects what begin hooked, uploads, waits for the gate
dotnet sonarscanner end /d:sonar.token="$SONAR_TOKEN"
```

- **Why begin/build/end and not a single command?** SonarQube's C#/VB.NET analysis runs *inside the Roslyn compiler*. The **begin** step installs SonarQube's Roslyn analyzers and injects MSBuild targets so that when `dotnet build` runs, the compiler itself emits SonarQube's findings with full semantic information (the analyzer rides the same type resolution the compiler does — the .NET equivalent of needing bytecode in Q24). The **end** step then gathers those compiler-produced results plus coverage and uploads them. There's no way to get accurate semantic analysis *without* wrapping the real build, because the analysis *is* part of compilation.
- **The `--no-incremental` detail:** an incremental build may skip recompiling unchanged projects, which means SonarQube's analyzers never run on them and they vanish from analysis. Forcing a full build ensures every project is compiled (and thus analyzed) — a subtle but common cause of "some projects show no issues."
- **Coverage:** like every language, SonarQube *imports* .NET coverage (OpenCover/Coverlet/dotCover XML), it doesn't measure it (Q7) — you point `sonar.cs.opencover.reportsPaths` at the generated report. The senior framing: the begin/end wrapper exists because .NET (and similarly C/C++ via a build-wrapper) needs to **observe the actual compilation** to do semantic analysis, so the scanner integrates *into* the build rather than running beside it — and `qualitygate.wait` rides on the `end` step.

#### Q112. [Coding] Wire SonarLint-style pre-PR enforcement with a git pre-commit / pre-push hook that runs a fast local scan and blocks obvious regressions.

**Problem:** Connected-Mode SonarLint (Q5/Q57) catches issues at keystroke time, but you also want a *belt-and-suspenders* hook so a developer who ignores IDE squiggles can't push code that will obviously fail the gate. Build a `pre-push` hook that runs a fast, scoped check. The nuance is that a full server scan is too slow for a hook, so you run something cheap and *advisory*, deferring the authoritative verdict to CI.

```bash
#!/usr/bin/env bash
# .git/hooks/pre-push  (or via pre-commit framework / husky)
# Goal: FAST local signal, NOT a substitute for the CI gate.
set -euo pipefail
echo "Running local quality pre-push checks..."

# 1) Cheap deterministic checks first (fail fast, no network)
if git diff --cached --name-only 2>/dev/null | grep -qE '\.(java|kt)$'; then
  : # placeholder for spotless/checkstyle — fast, local, no Sonar server
fi

# 2) Run SonarLint's CLI (or a scoped sonar-scanner) on CHANGED files only,
#    in CONNECTED mode so rules match the server (Q57). Keep it advisory.
CHANGED=$(git diff --name-only @{push} HEAD 2>/dev/null || git diff --name-only HEAD~1)
if [ -n "$CHANGED" ]; then
  # SonarLint CLI / IDE binding already shares the server profile; here we
  # just surface a warning — do NOT block on a slow/full server scan.
  echo "Changed files: $CHANGED"
fi

# 3) Block ONLY on a fast, high-confidence local rule (e.g. leaked secret)
if git diff --cached -U0 | grep -nE 'AKIA[0-9A-Z]{16}|-----BEGIN .*PRIVATE KEY-----'; then
  echo "ERROR: possible secret in staged changes — rotate & remove (Q68)."
  exit 1
fi
echo "Local checks passed (authoritative gate runs in CI)."
```

- **Why advisory, not authoritative?** A git hook runs on the developer's machine with no compiled classpath, no coverage, and no taint analysis (Q57), so it *cannot* reproduce the server gate — trying to run a full `sonar-scanner` in a hook is slow and gives a different, weaker verdict that breeds "but it passed locally" confusion. The right design is **fast, deterministic, high-confidence checks in the hook** (formatting, a leaked-secret regex like the one shown) and the **real gate in CI** (Q58). The hook *complements* SonarLint Connected Mode, which is the proper keystroke-time mechanism.
- **The secret check is the one thing worth *blocking* on** because a committed secret is an incident the moment it lands (Q68) and a regex catch is high-confidence and instant — exactly the kind of check that belongs client-side where it can prevent the bad commit entirely.
- **Distribution caveat:** `.git/hooks` isn't version-controlled, so in practice you ship hooks via a framework (pre-commit, husky, or `core.hooksPath` pointed at a committed dir) so all developers get them. The senior framing: layer the feedback loop — **IDE (SonarLint, keystroke) → hook (fast local, pre-push) → CI (authoritative gate)** — and resist the temptation to make the hook authoritative; its job is fast, cheap, high-confidence prevention, with the slow semantic/taint/coverage verdict deferred to the server where it can actually be computed correctly.

### 🟠 Advanced — extended

#### Q97. [Coding] Write a custom SonarQube Java rule that flags `@Autowired` field injection (favoring constructor injection), and write its unit test.

**Problem:** A team standardizes on constructor injection. No built-in rule bans `@Autowired` on *fields*, so write one with the Java analyzer API, registered properly, with a test using the analyzer's verifier harness. This goes beyond Q15 by covering annotations, registration, and the test framework.

```java
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.tree.*;
import java.util.List;

@Rule(key = "AvoidFieldInjection")
public class AvoidFieldInjectionRule extends IssuableSubscriptionVisitor {

    @Override
    public List<Tree.Kind> nodesToVisit() {
        return List.of(Tree.Kind.VARIABLE);          // visit field/var declarations
    }

    @Override
    public void visitNode(Tree tree) {
        VariableTree var = (VariableTree) tree;
        // Only fields: a class member, not a local var or parameter.
        if (var.parent() == null || !var.parent().is(Tree.Kind.CLASS)) return;
        for (AnnotationTree ann : var.modifiers().annotations()) {
            // Use resolved symbol type, not the textual name (Q23) — handles
            // org.springframework.beans.factory.annotation.Autowired specifically.
            if (ann.annotationType().symbolType()
                   .is("org.springframework.beans.factory.annotation.Autowired")) {
                reportIssue(ann, "Use constructor injection instead of @Autowired fields.");
            }
        }
    }
}
```

```java
// Test using sonar-java's CheckVerifier: assertions live as comments in the fixture
import org.sonar.java.checks.verifier.CheckVerifier;
import org.junit.jupiter.api.Test;

class AvoidFieldInjectionRuleTest {
    @Test
    void detects() {
        CheckVerifier.newVerifier()
            .onFile("src/test/files/FieldInjection.java")
            .withCheck(new AvoidFieldInjectionRule())
            .verifyIssues();
    }
}
```

```java
// src/test/files/FieldInjection.java — fixture; "// Noncompliant" marks expected issues
class Svc {
    @org.springframework.beans.factory.annotation.Autowired
    private Repo repo;   // Noncompliant {{Use constructor injection instead of @Autowired fields.}}

    private Repo ok;     // compliant: no annotation
}
```

- **Why filter on `parent().is(CLASS)`?** `VARIABLE` nodes include locals and parameters; restricting to a class-level parent isolates *fields*. Without it the rule would false-positive on annotated locals.
- **Why the semantic `symbolType().is(...)` check?** Spring's `@Autowired` has a specific FQN; resolving the annotation's type (not matching the simple name "Autowired") avoids flagging a same-named annotation from another package — the semantic-vs-syntactic distinction from Q23. This requires the test classpath to *have* Spring on it so the type resolves, or the rule silently won't match (the bytecode-dependency point from Q24).
- **The test harness is the senior signal:** sonar-java ships `CheckVerifier`, which runs your check on a fixture file and asserts issues against `// Noncompliant` comments embedded in the source. This is the idiomatic way to test custom rules — golden-file assertions in the fixture, not brittle string matching. You'd also register the rule in a `CheckRegistrar`/`RulesDefinition` and ship HTML+JSON metadata so it appears in profiles (Q15). **Complexity:** O(annotations per field), trivial.

#### Q98. [Coding] Write a CI script that computes coverage *on the diff only* and fails if new lines are insufficiently covered — without SonarQube's paid PR analysis.

**Problem:** A Community-Edition shop can't use Sonar's new-code/PR coverage gate (Developer Edition+, Q9/Q13). Reproduce "coverage on new code ≥ 80%" using the JaCoCo XML and `git diff`, so the *concept* is enforced even without the paid feature.

```bash
#!/usr/bin/env bash
# diff-coverage.sh — fail if changed lines aren't sufficiently covered.
set -euo pipefail
BASE="${1:-origin/main}"
THRESHOLD="${2:-80}"
JACOCO_XML="target/site/jacoco/jacoco.xml"

# 1) Lines added/changed in this branch vs base, as "file:line"
git diff --unified=0 "${BASE}...HEAD" -- '*.java' \
  | awk '
      /^\+\+\+ b\// { file=substr($0,7); next }
      /^@@/ { split($3,a,","); start=substr(a[1],2)+0;
              len=(a[2]=="")?1:a[2]; for(i=0;i<len;i++) print file":"(start+i) }
    ' | sort -u > /tmp/changed.txt

# 2) Covered/missed lines from JaCoCo XML (ci=covered instructions per line)
python3 - "$JACOCO_XML" <<'PY' > /tmp/cov.txt
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for pkg in root.iter('package'):
    p = pkg.get('name')
    for sf in pkg.iter('sourcefile'):
        path = f"src/main/java/{p}/{sf.get('name')}"
        for ln in sf.iter('line'):
            covered = int(ln.get('ci')) > 0   # ci>0 => at least one instr covered
            print(f"{path}:{ln.get('nr')}:{'C' if covered else 'M'}")
PY

# 3) Intersect: of the changed lines that are coverable, what % are covered?
join -t: <(sort /tmp/changed.txt) \
         <(awk -F: '{print $1":"$2":"$3}' /tmp/cov.txt | sort) > /tmp/joined.txt || true
total=$(wc -l < /tmp/joined.txt)
covered=$(grep -c ':C$' /tmp/joined.txt || true)
[ "$total" -eq 0 ] && { echo "No coverable changed lines."; exit 0; }
pct=$(( covered * 100 / total ))
echo "Diff coverage: ${covered}/${total} = ${pct}% (threshold ${THRESHOLD}%)"
[ "$pct" -ge "$THRESHOLD" ] || { echo "FAIL: new code under-covered"; exit 1; }
```

- **What it reproduces:** SonarQube's "coverage on new code" is exactly *covered changed lines ÷ coverable changed lines*. This script computes that from the same JaCoCo XML SonarQube would import, intersected with `git diff` — demonstrating that "new code coverage" is a *diff × coverage* join, which is the conceptual heart of Clean-as-You-Code (Q43/Q48).
- **Correctness subtleties:** `git diff --unified=0` is essential so the hunk headers list only changed lines (context lines would over-count); the `awk` parses `@@ -old +new,len @@` to expand the changed range. Only lines JaCoCo considers *coverable* (present in the XML) are in the denominator — blank/comment lines aren't counted, matching Sonar's behavior. The `ci` attribute (covered instructions) > 0 means "line executed."
- **Limits vs the real thing (the honest senior caveat):** this is line coverage only — it ignores **branch** coverage (Q42) and the assertion-strength blind spot (Q80), and path mapping is fragile (the hard-coded `src/main/java` prefix is a simplification real repos must generalize). It also doesn't do issue analysis, only coverage. The point isn't to replace Developer Edition but to show you understand *what* the paid feature computes well enough to approximate it — and to give Community-Edition teams a real diff-coverage gate. **Complexity:** O(changed lines + coverable lines) for the join.

#### Q99. [Coding] Write a custom rule that uses the control-flow / data-flow API to flag a value assigned but never read (beyond the built-in), or explain why you'd instead write a regex rule.

**Problem:** Demonstrate the decision between an **AST/semantic rule** and a **regex (text-based) rule**, and show a regex rule because that's the lesser-known SonarQube capability interviewers probe. Suppose you must flag a company-specific banned API call `LegacyLogger.log(` that no analyzer models.

```xml
<!-- A "custom rule" defined entirely as a regex via the built-in
     "text" / generic pattern mechanism — NO Java plugin needed.
     SonarQube supports rules of type 'regex' in some analyzers and the
     generic text rule template. Conceptually: -->
```

```java
// AST-based version (precise) — when you need semantics:
@Rule(key = "BanLegacyLogger")
public class BanLegacyLoggerRule extends IssuableSubscriptionVisitor {
    @Override public List<Tree.Kind> nodesToVisit() {
        return List.of(Tree.Kind.METHOD_INVOCATION);
    }
    @Override public void visitNode(Tree tree) {
        MethodInvocationTree mit = (MethodInvocationTree) tree;
        // Resolves the actual method owner — won't match a different class
        // that happens to also have a log() method.
        if (mit.methodSymbol().owner() != null
            && mit.methodSymbol().owner().type().is("com.acme.LegacyLogger")) {
            reportIssue(mit, "LegacyLogger is banned; use SLF4J.");
        }
    }
}
```

The engineering decision is **precision vs cost**. A **regex/text rule** (configured via a rule template — e.g., the "track uses of a forbidden pattern" template many analyzers expose, or a generic text-matching rule) is *cheap*: no Java, no plugin build, no API-version pinning (Q41). But it's *syntactic* (Q23) — `LegacyLogger.log(` as a regex will false-positive on a comment, a string literal containing that text, or a *different* class with the same name, and false-negative on `var l = new LegacyLogger(); l.log(...)` where the call site doesn't textually contain `LegacyLogger`. An **AST/semantic rule** resolves the method's owning type, so it matches exactly the banned class regardless of variable aliasing and ignores comments/strings — at the cost of writing, building, and maintaining a version-pinned plugin.

The rule of thumb I'd state: reach for a **regex/template rule when the pattern is textually unambiguous and cheap precision is acceptable** (e.g., banning a literal token like a deprecated annotation string, or enforcing a comment convention), and write an **AST rule when correctness depends on types, scope, control flow, or data flow** (the banned-API case where aliasing matters, or anything touching the semantic model). Trying to do semantic work with regex is the classic over-reach that floods teams with false positives (the very thing that makes them tune SonarQube out, Q14); trying to do trivial text matching with a full plugin is over-engineering. **Complexity:** regex is O(file bytes); the AST rule is O(method invocations) with O(1) semantic lookups. The senior framing is naming *where the truth lives* — if it's in the text, regex suffices; if it's in the type/flow graph, you need the analyzer API.

#### Q100. [Practical] Design a "quality platform" service that auto-provisions a SonarQube project, gate, profile binding, and CI wiring whenever a new repo is created. Sketch the architecture.

**Scenario → architecture → trade-offs.** At scale (Q19/Q64), onboarding must be *event-driven and zero-touch*: a developer creates a repo and the quality wiring appears without anyone filing a ticket. I'd build a small internal service that reacts to repo-creation events and reconciles SonarQube + CI state via APIs.

```
 ┌─────────────┐  repo.created   ┌──────────────────────┐   SonarQube web API
 │ SCM platform │ ───webhook────▶ │  Quality Platform svc │ ───────────────────▶ create project,
 │ (GitHub/GL) │                 │  (reconciler)         │                       assign permission
 └─────────────┘                 │                       │ ──────────────────▶  template + gate +
        ▲                         │  - idempotent upsert  │   bind inherited      parent profile
        │ commit CI template       │  - desired state in   │   profile (Q46)
        │ (PR) via SCM API          │    git (GitOps)       │
        └───────────────────────── └──────────┬───────────┘
                                                │ provision project-scoped
                                                ▼ analysis token (least-priv, Q59)
                                          secrets manager ──▶ injected into CI
```

The pieces and *why* each exists: (1) a **reconciler** (not a one-shot script) so the desired state — project exists, gate = org default, profile = inherited parent, permission template applied, token provisioned — is **idempotently upserted**; re-running converges, which matters because webhooks are at-least-once (Q34/Q89) and repos get recreated. (2) **Project-scoped analysis tokens** minted via the API and pushed to the secrets manager / CI, so credentials are least-privilege and rotatable (Q59), never hand-copied. (3) The **CI wiring is committed as a PR** using a reusable workflow (Q64) so onboarding footprint is O(1) lines per repo and `fetch-depth: 0` is guaranteed centrally. (4) **Desired state in git** (GitOps, Q94) — the platform reconciles SonarQube to match, so drift is detectable and changes are reviewed.

**Trade-offs:** this is real software with its own reliability burden — if the reconciler is down, new repos onboard late (acceptable: degraded, not broken). It becomes a **central dependency and a powerful credential holder** (it has admin-scoped SonarQube access to create projects and mint tokens), so it needs strong secret hygiene, audit logging, and least-privilege itself. The alternative — relying on SonarQube/DevOps-platform **auto-provisioning** features (newer editions auto-create projects on first analysis and bind via DevOps integration) — is lower-effort and should be preferred *if* it covers gate/profile/token needs; you build the bespoke service only when the platform's built-in auto-provisioning can't express your governance (locked profiles, custom token scoping, GitOps reconciliation). The senior point: prefer platform-native auto-provisioning first, build a reconciler only for the governance the platform can't express, and design it idempotently because every onboarding event source is at-least-once.

#### Q101. [Practical] Design how SonarQube fits a polyglot platform where some languages (e.g., Rust, COBOL, a DSL) have weak or no analyzer support. What's the strategy?

The honest starting point is that SonarQube's value is wildly uneven across languages: deep for Java/C#/JS-TS/Python, decent for Go/PHP/Kotlin, commercial-only for C/C++/COBOL/ABAP/PL-SQL (Developer Edition+), and *absent* for niche languages or in-house DSLs. A mature design **doesn't pretend uniform coverage** — it routes each language to the best available mechanism and fills gaps with external-issue imports, while keeping one consistent gate experience.

```
 Language tier            Mechanism                              Gate participation
 ──────────────────────   ────────────────────────────────────   ──────────────────────
 First-class (Java/TS/..) native analyzer + profile + taint      full native gate
 Commercial (C++/COBOL)   native analyzer (Developer Ed.+)        full, if licensed
 Weak/community           native where it exists, supplement     native + imported issues
   (Go/Rust historically)  with golangci-lint/clippy via import   (Q81)
 No analyzer / DSL         run the ecosystem's linter, IMPORT     imported only (read-only,
                           via generic external issues (Q95)       may not fail gate by default)
```

The strategy in layers: (1) **use the native analyzer wherever it exists** — it gives semantic rules, taint, coverage, new-code, the full lifecycle. (2) For languages with weak native support, **supplement with the ecosystem's best linter** (clippy for Rust, golangci-lint for Go) and **import** the findings (Q81/Q95) so they show in the same UI. (3) For **no-analyzer languages and in-house DSLs**, you cannot do native analysis at all — you run whatever bespoke checker exists and import generically, accepting those issues are read-only and (by default) may not gate. (4) Keep the **gate semantics consistent** by gating on what *is* enforceable per language (native issues + coverage) and treating imported findings as advisory unless you build first-class rules for the critical ones.

The trade-off interviewers want named: you get **one pane of glass** and consistent process, but **not uniform enforcement** — a Rust repo's gate is weaker than a Java repo's because imported clippy issues don't behave like native issues. The anti-pattern is forcing a uniform gate that's either toothless (lowest common denominator) or unachievable for weak-support languages. The senior call is to **be explicit about per-language capability**, document it, and for a *critical* gap (a security-sensitive DSL) decide whether it's worth a **custom analyzer/plugin** (Q41) — which is high-effort and version-pinned, justified only when the language is strategic and the findings must be first-class. SonarQube is the consolidation point, not a guarantee of equal depth everywhere.

#### Q102. [Coding] Write a SonarScanner invocation containerized for a CI runner that has no JDK, and handle the analysis token securely.

**Problem:** A CI runner can run Docker but has no JDK/Node installed. Run the standalone SonarScanner from its official image against a checked-out workspace, passing the token without leaking it into image layers, logs, or the process list.

```bash
#!/usr/bin/env bash
set -euo pipefail
: "${SONAR_TOKEN:?}" "${SONAR_HOST_URL:?}"

docker run --rm \
  -e SONAR_TOKEN \                       # pass by NAME → value not in `docker run` argv
  -e SONAR_HOST_URL \
  -v "${PWD}:/usr/src" \                 # mount the workspace read/write for caches
  -v "${PWD}/.sonarcache:/opt/sonar-scanner/.sonar/cache" \
  sonarsource/sonar-scanner-cli:latest \
  -Dsonar.projectKey=web-portal \
  -Dsonar.qualitygate.wait=true
  # NOTE: token is NOT on the command line — it's read from SONAR_TOKEN env
```

- **Why `-e SONAR_TOKEN` (name only) not `-e SONAR_TOKEN=$TOKEN`?** Passing `NAME` alone tells Docker to *forward the current env var's value*, so the secret never appears in the `docker run` command line — which would otherwise be visible in `ps`, shell history, and CI logs. The scanner reads `SONAR_TOKEN`/`SONAR_HOST_URL` from the environment automatically, so you don't put `-Dsonar.token=` on the argv (where it would also leak into verbose logs).
- **Why the official scanner image?** It bundles the JDK and scanner, so a runner needs only Docker — no toolchain management. The volume mount makes the workspace available at the image's expected source root. Mounting a **cache volume** (`.sonar/cache`) persists downloaded analyzer plugins across runs, cutting latency (same idea as Q93's GitLab cache).
- **The secret-handling rules (the senior point):** never `-Dsonar.token=` on the command line (leaks to process list and verbose logs), never bake it into the image via `ARG`/`ENV` at build time (Q75 — it persists in inspectable layers), and prefer a **project-scoped analysis token** (Q59) so a leak is contained to one project's analysis rights. For full builds you'd run the language toolchain (Maven/Gradle/npm) first to produce bytecode and coverage (the scanner CLI alone doesn't compile), then this scanner step imports the reports — the CLI is for languages without a build plugin or for a separate scan stage. **Edge case:** `:latest` is non-reproducible (Q87) — pin a digest/version in real pipelines so an image bump can't silently change your analyzer and issue counts.

#### Q103. [Practical] Design a custom-rule development and release lifecycle: how do you author, test, version, and roll out an org-wide custom rule plugin safely?

A custom rule plugin (Q15/Q97) is *production code that runs inside every analysis*, so it deserves a real SDLC — shipping a buggy rule org-wide can break every build or flood every team with false positives. I'd design the lifecycle as a pipeline mirroring how you'd ship any library, with extra care because of the version-pinned plugin API (Q41) and the blast radius.

```
 Author ──▶ Test ──▶ Version ──▶ Stage ──▶ Canary ──▶ Roll out ──▶ Deprecate
  rule +    Check    semver +    deploy    activate    activate    keep history
  metadata  Verifier API-compat  to a      in ONE      in parent   on removal
  (HTML+    golden   tag         staging   pilot       profile     (Q47)
   JSON)    files               Sonar     profile     (inherited)
```

The stages and their *why*: (1) **Author** the rule plus its metadata — the `@Rule` key, an HTML description, and a JSON descriptor (severity/type/tags/remediation) so it appears correctly in profiles; metadata is part of the deliverable, not an afterthought. (2) **Test** with `CheckVerifier` golden-file fixtures (Q97) in CI — a rule with no `// Noncompliant` test fixtures should fail review. (3) **Version** the plugin with semver and **assert plugin-API compatibility** for the target SonarQube version, because the API is version-pinned and a server upgrade can break the plugin (Q41/Q63). (4) **Stage**: deploy the plugin JAR to a staging SonarQube and verify it loads and the rule shows in the profile. (5) **Canary**: activate the new rule in **one pilot project's profile** and watch the issue volume — a rule that lights up 5,000 issues is a false-positive signal, not a win (Q14). (6) **Roll out** by activating it in the **inherited parent profile** (Q46) so it propagates to children without per-repo work — and crucially, *activation is separate from deployment*: newly shipped rules are **not auto-activated** in custom profiles (Q47), which is the safety mechanism that lets you deploy the JAR everywhere but enable the rule gradually.

**Trade-offs and failure modes:** the plugin JAR is global (in `extensions/plugins/`), so deploying it touches the whole instance — a JAR that fails to load can start the server degraded (Q63), which is why staging-load verification is mandatory. The decoupling of *deploy* (JAR present) from *activate* (rule on in a profile) is the key safety lever: you can canary the rule's *effect* independently of the risky *deploy*. For **deprecation**, follow Sonar's model (Q47): mark deprecated, leave existing issues as history, remove later — never silently drop a rule key that issues reference. The senior framing is treating a custom rule as **org-wide production code with a blast radius**, applying canary + gradual activation, and exploiting the deploy/activate split so you never ambush 400 repos with a new rule at once.

#### Q113. [Coding] Author the rule metadata (`RulesDefinition` + HTML/JSON) needed for a custom rule to appear and behave correctly in the Quality Profile UI.

**Problem:** A custom check class (Q15/Q97) is *not enough* — without a `RulesDefinition` that registers the rule key and ships its metadata (description, severity, type/impact, remediation function), the rule won't show in the profile, can't be activated, and its debt won't compute (Q31). Show the registration plumbing that interviewers know separates "I wrote a visitor" from "I shipped a usable rule."

```java
// 1) Register the rule into a repository so the profile UI can see it.
public class AcmeRulesDefinition implements RulesDefinition {
    static final String REPO = "acme-java";
    @Override public void define(Context context) {
        NewRepository repo = context.createRepository(REPO, "java").setName("Acme Java");
        // Loads rule metadata from classpath resources by rule key (HTML + JSON).
        RulesDefinitionAnnotationLoader loader = new RulesDefinitionAnnotationLoader();
        loader.load(repo, AvoidFieldInjectionRule.class);   // reads @Rule(key=...)
        // Or load from packaged resource files keyed by the rule id:
        new RulesDefinitionXmlLoader();  // alt: bulk-load many rules from XML
        repo.done();
    }
}
```

```json
// resources/.../AvoidFieldInjection.json — the rule's machine metadata
{
  "title": "Field injection should not be used",
  "type": "CODE_SMELL",
  "status": "ready",
  "remediation": { "func": "Constant\/Issue", "constantCost": "5min" },
  "tags": ["spring", "design"],
  "defaultSeverity": "Major",
  "impacts": { "MAINTAINABILITY": "MEDIUM" }
}
```

```html
<!-- resources/.../AvoidFieldInjection.html — what developers READ in the UI -->
<p>Field injection makes dependencies implicit and classes hard to test.</p>
<h2>Noncompliant Code Example</h2>
<pre>@Autowired private Repo repo;</pre>
<h2>Compliant Solution</h2>
<pre>private final Repo repo; Svc(Repo repo){ this.repo = repo; }</pre>
```

- **Why metadata is half the deliverable:** the `@Rule(key=...)` on the check class only *names* the rule; the `RulesDefinition` registers it into a **repository** so it appears in the language's rule list, and the JSON/HTML supply everything the platform and humans need — the **remediation function** (Q31) that drives the technical-debt estimate, the **type/impact** (legacy vs MQR, Q29) that drives ratings, the severity, tags for standards mapping (Q35), and the human-readable description with compliant/noncompliant examples. Ship the class without metadata and the rule is invisible and inert.
- **The `RulesDefinitionAnnotationLoader`** bridges the annotation to the resource files: it reads `@Rule(key=...)` and loads the matching `Key.json`/`Key.html` from the classpath, so your packaging must place those resources where the loader expects them. A `CheckRegistrar` then tells the Java analyzer which check classes to run, completing the wiring (`RulesDefinition` = "what rules exist," `CheckRegistrar` = "what classes implement them").
- **The senior point:** a production custom rule is *four* artifacts — the visitor (logic, Q97), the `RulesDefinition` (registration), the JSON (machine metadata incl. remediation/impact), and the HTML (developer-facing guidance) — plus the `CheckRegistrar`. Forgetting the remediation function means your rule contributes *zero* debt and skews the Maintainability Rating (Q8/Q31); forgetting the HTML means developers see a finding with no idea how to fix it, which breeds the noise-fatigue of Q14. Knowing the metadata is mandatory, not optional, is the experience signal.

#### Q114. [Coding] Create a Quality Gate and its conditions entirely via the web API (gate-as-code), so gates are reproducible and version-controlled.

**Problem:** Profiles can be backed up as XML (Q94), but **Quality Gates have no XML backup** — they're created via the API. To make gates reproducible and reviewable (Q87/Q106), script their creation idempotently from a declarative definition.

```bash
#!/usr/bin/env bash
# gate-as-code.sh — create/update an org Quality Gate from declared conditions.
set -euo pipefail
: "${SONAR_URL:?}" "${SONAR_ADMIN_TOKEN:?}"
GATE="Acme Org Gate"
AUTH=(-u "${SONAR_ADMIN_TOKEN}:")

# 1) Create the gate if absent (ignore "already exists"), get its id/name.
curl -s "${AUTH[@]}" -X POST \
  "${SONAR_URL}/api/qualitygates/create" --data-urlencode "name=${GATE}" >/dev/null || true

# 2) Declare conditions: metric op error-threshold  (NEW-CODE metrics)
#    op: LT (less than) / GT (greater than). These mirror "Sonar way" intent.
declare -a CONDS=(
  "new_violations GT 0"
  "new_coverage LT 80"
  "new_duplicated_lines_density GT 3"
  "new_security_rating GT 1"        # 1=A; >1 means worse than A → fail
  "new_reliability_rating GT 1"
  "new_security_hotspots_reviewed LT 100"
)

# 3) Reconcile: wipe existing conditions, re-add declared ones (idempotent).
existing=$(curl -s "${AUTH[@]}" \
  "${SONAR_URL}/api/qualitygates/show?name=$(printf %s "$GATE" | jq -sRr @uri)")
echo "$existing" | jq -r '.conditions[]?.id' | while read -r cid; do
  curl -s "${AUTH[@]}" -X POST "${SONAR_URL}/api/qualitygates/delete_condition" \
    --data-urlencode "id=${cid}" >/dev/null
done
for c in "${CONDS[@]}"; do
  read -r metric op thr <<<"$c"
  curl -s "${AUTH[@]}" -X POST "${SONAR_URL}/api/qualitygates/create_condition" \
    --data-urlencode "gateName=${GATE}" \
    --data-urlencode "metric=${metric}" \
    --data-urlencode "op=${op}" \
    --data-urlencode "error=${thr}" >/dev/null
done
echo "Gate '${GATE}' reconciled."
```

- **Why gate-as-code matters:** unlike profiles, gates can't be exported/imported as a file, so without a script they're hand-clicked in the UI — unversioned, undocumented, and a prime target for "gaming" (Q79) when someone quietly loosens a threshold. Encoding the conditions in a reconciled script makes the gate a **reviewable artifact** and lets you apply the *same* gate across instances (Q66) and enforce a standard org gate (Q19).
- **The reconcile pattern (idempotency, Q94):** the script *deletes existing conditions then re-adds the declared set*, so re-running converges to the declared state regardless of drift — the only safe way to make "apply" repeatable. Naively *adding* conditions would duplicate them on every run.
- **Metric/threshold subtleties:** ratings are encoded numerically (`1=A, 2=B, ...`) so `new_security_rating GT 1` means "worse than A fails"; `op` is `LT`/`GT`; and these are the **`new_*`** (new-code) metrics that implement Clean-as-You-Code (Q6) — using the overall-code metrics here would re-create the "perpetually red on legacy" failure of Q14. The senior framing: gates are *controls* (Q106), and a control you can't reproduce or review isn't trustworthy — scripting gate creation via the API is how you bring gates under the same change-control discipline as profiles, closing the last unversioned input that makes analysis non-reproducible (Q87).

#### Q115. [Practical] Design an incremental/caching analysis strategy for a 10-million-line monorepo where a full scan is infeasible per PR. What can and cannot be made incremental?

The brutal constraint is that a from-scratch scan of 10M LOC can take tens of minutes to hours, which is unacceptable as a per-PR gate — yet correctness (issue tracking, new-code) depends on consistent analysis. The design hinges on understanding **what SonarQube can legitimately skip and what it structurally cannot**, then attacking the problem at the *project-decomposition* level rather than hoping for a magic incremental flag.

```
 Layer                         Incremental?      Strategy
 ────────────────────────────  ────────────────  ──────────────────────────────
 Project decomposition         N/A (design)      split into many projectKeys by
                                                  ownership/deploy unit (Q17/Q82)
 CI trigger                    yes               path-filtered jobs: scan only the
                                                  projects whose files changed
 Scanner plugin/cache          yes               persist .sonar/cache across runs
 Per-file analysis             partly            analyzer caches help, but semantic
                                                  analysis needs the project's context
 Issue tracking / new code     NO (must be       CE processes a full project report
   (per project)                consistent)        per analysis, serial per key (Q54)
```

The strategy in order of leverage: (1) **Decompose the monorepo into many SonarQube projects** keyed by deployable/owned unit (Q17/Q82). This is the single biggest win — a PR touching one service triggers a scan of *that project only*, not 10M LOC, and the projects' Compute-Engine tasks **parallelize across keys** instead of serializing behind one giant project (Q54/Q69). (2) **Path-filtered CI triggers** so a change under `/services/payments` runs only the payments analysis. (3) **Persist the scanner cache** (`.sonar/cache`, Q93/Q102) so analyzer plugins and some analysis state survive between runs, cutting startup cost. (4) On the server, **scale CE workers and heap** and configure **aggressive branch/PR housekeeping** (Q86) so the index doesn't bloat from thousands of PR analyses.

**What cannot be made incremental — the honest limit:** within a single project, SonarQube analyzes the **whole project per run** and the Compute Engine processes a *complete* report; it does not do true per-file incremental analysis that skips unchanged files, because **issue tracking, new-code computation, and measure history require a consistent full picture** of the project (Q54 — analyses are serial *per project* precisely to keep tracking correct). So you cannot tell SonarQube "only analyze these 3 changed files and trust the rest" — the correctness model forbids it. The lever is therefore to make each *project* small enough that a full scan is fast, which means decomposition, not partial analysis.

**Trade-offs:** heavy decomposition multiplies the number of projects to govern (more gates, profiles, permission templates — mitigated by inherited profiles and a default gate, Q19/Q46) and cross-project changes now span multiple analyses. But it's the only approach that aligns with SonarQube's correctness model. The senior framing: "make a 10M-LOC scan incremental" is the wrong question — SonarQube's per-project full-analysis model can't be cheated without breaking tracking, so you **shrink the unit of analysis** (many projects + path-filtered CI + parallel CE) rather than trying to partially analyze one monolithic project, accepting the governance overhead of many projects as the cost of fast, correct per-PR feedback.

### 🔴 Expert — extended

#### Q104. [Coding] Write a custom rule for an IaC/config file type (e.g., flag a Kubernetes container missing CPU/memory limits) and explain why IaC rules use a different model.

**Problem:** Extend Q53's theory into practice: SonarQube's IaC analysis parses declarative documents into a tree and runs *structural property checks* (no control/data flow). Show a check that flags a Kubernetes container without resource limits, illustrating the structural-traversal model rather than the AST/semantic model used for app code.

```java
// IaC checks subscribe to the parsed document tree (YAML), not a code AST.
// Conceptual shape using the iac analyzer API (org.sonar.iac.*):
@Rule(key = "K8sMissingResourceLimits")
public class MissingResourceLimitsCheck implements IacCheck {

    @Override
    public void initialize(InitContext init) {
        // Register interest in the YAML document; navigate by KEY PATH,
        // because a declarative doc has no execution to follow (Q53).
        init.register(FileTree.class, (ctx, file) -> {
            // pseudo-navigation: spec.template.spec.containers[*]
            for (var container : path(file, "spec", "template", "spec", "containers")) {
                var resources = child(container, "resources");
                var limits = (resources == null) ? null : child(resources, "limits");
                boolean hasCpu = limits != null && child(limits, "cpu") != null;
                boolean hasMem = limits != null && child(limits, "memory") != null;
                if (!hasCpu || !hasMem) {
                    ctx.reportIssue(container.key(),
                        "Set both cpu and memory limits on this container.");
                }
            }
        });
    }
}
```

- **Why a different model than Q15/Q97?** App-code rules walk an AST with a *semantic model* (types, symbols) and can do control/data-flow (taint, Q39). IaC rules walk a **parsed declarative document** and do **key-path structural checks** — "does `resources.limits.cpu` exist under each container?" There's no control flow because the manifest doesn't *execute* procedurally; the check is about the *shape and presence of properties*, not data movement. So the API is registration-on-document + tree navigation, not node-kind subscription + symbol resolution.
- **The correctness nuance:** you navigate by the document's structure (`spec.template.spec.containers[]`), and you must handle absence at every level (no `resources`, no `limits`) — declarative configs are sparse, so null-safe path traversal *is* the logic. Reporting on `container.key()` anchors the issue to the right line in the YAML.
- **The boundary (Q53 reinforced):** this check sees the manifest *as written* — it cannot know whether a `LimitRange` at the namespace level supplies defaults, because that's **cross-resource/runtime context** the single file lacks. So the rule is a useful in-file hygiene check ("this manifest is written safely") but not a guarantee about the *deployed* pod (which a CSPM/admission-controller evaluates against live cluster policy). The senior framing: IaC custom rules extend SonarQube's shift-left config hygiene, but their structural-only model is exactly why they complement, not replace, runtime posture tools. **Complexity:** O(nodes in the document), single traversal.

#### Q105. [Coding] Integrate mutation testing (PIT) results so SonarQube surfaces mutation score as a metric, and explain why this addresses coverage's blind spot.

**Problem:** Q80/Q42 establish that line coverage can't see *assertion strength*. Mutation testing (PIT for Java) deliberately mutates code and checks whether tests catch it — directly measuring assertion quality. Wire PIT into the build and surface its result in SonarQube, acknowledging that SonarQube has no native mutation metric.

```xml
<!-- PIT (pitest) Maven plugin: mutate, run tests, report what survived -->
<plugin>
  <groupId>org.pitest</groupId>
  <artifactId>pitest-maven</artifactId>
  <version>1.16.1</version>
  <configuration>
    <targetClasses><param>com.acme.*</param></targetClasses>
    <outputFormats>
      <param>XML</param>      <!-- machine-readable for import -->
      <param>HTML</param>
    </outputFormats>
  </configuration>
</plugin>
```

```bash
# Generate mutation results, convert to SonarQube generic external issues,
# so SURVIVED mutants (tests didn't catch them) appear as issues.
mvn org.pitest:pitest-maven:mutationCoverage
python3 pit_to_sonar.py target/pit-reports/mutations.xml > pit-sonar.json
sonar-scanner -Dsonar.externalIssuesReportPaths=pit-sonar.json
```

```python
# pit_to_sonar.py — flag SURVIVED/NO_COVERAGE mutants as issues (assertion gaps)
import sys, xml.etree.ElementTree as ET, json
muts = ET.parse(sys.argv[1]).getroot()
issues, rules = [], [{"id":"pit:survived","name":"Surviving mutant",
    "engineId":"pitest","cleanCodeAttribute":"COMPLETE",
    "impacts":[{"softwareQuality":"RELIABILITY","severity":"MEDIUM"}]}]
for m in muts.iter('mutation'):
    if m.get('status') in ('SURVIVED','NO_COVERAGE'):
        issues.append({"ruleId":"pit:survived","primaryLocation":{
            "message": f"Mutant survived: {m.findtext('mutator').split('.')[-1]}",
            "filePath": f"src/main/java/{m.findtext('mutatedClass').replace('.','/')}.java"
                        .rsplit('/',0)[0],
            "textRange": {"startLine": int(m.findtext('lineNumber'))}}})
json.dump({"rules":rules,"issues":issues}, sys.stdout)
```

- **Why this matters (the conceptual core):** coverage answers "did a test *run* this line?"; mutation testing answers "would a test *notice* if this line were wrong?" PIT changes `>` to `>=`, removes a `return`, negates a condition — each a "mutant" — and reruns tests. A **surviving mutant** means no test failed, i.e., the line is covered but **not actually verified** — the exact false-confidence trap of Q80. Surfacing survivors as Sonar issues makes assertion gaps visible in the same dashboard as coverage.
- **Why import rather than a native metric?** SonarQube has **no built-in mutation score metric**, so you can't make it a first-class gate condition; the generic external-issues path (Q95) is the integration point, with the limits from Q81 (read-only, may not gate by default). Some teams instead gate on PIT's own threshold in the build (fail if mutation score < X) and use Sonar only for *visibility* — a legitimate split because the build can enforce what Sonar can't natively.
- **Trade-offs:** mutation testing is **expensive** (it reruns the test suite once per mutant — potentially thousands of runs), so you scope `targetClasses`, run it nightly rather than per-PR, or use PIT's incremental/changed-classes mode to keep it tractable. The senior framing: coverage is a *floor*, mutation score is the *assertion-strength* signal coverage structurally cannot provide (Q42/Q80), and since SonarQube can't compute it natively you bring it in via import for visibility while enforcing it in the build — a concrete example of stacking complementary layers (Q44). **Complexity:** import is O(mutants); PIT itself is roughly O(mutants × test-suite-time), which is why scoping matters.

#### Q106. [Practical] Design a SonarQube deployment and gate strategy for a regulated environment (e.g., medical/finance) where evidence and auditability matter as much as catching bugs.

In a regulated context the deliverable isn't just "cleaner code" — it's **defensible evidence** that controls were applied, which reshapes nearly every design choice. The gate is a *control*, the analysis history is an *audit record*, and the standards mapping (Q35) is how you speak to auditors. I'd design for traceability and immutability of evidence first.

```
 Regulated design pillars
 ────────────────────────
 1. Self-hosted (data residency)   code can't leave the boundary → SonarQube Server,
                                     not Cloud (Q40); analysis on-prem/in-VPC.
 2. Standards-mapped gate           gate conditions tied to CWE/OWASP/PCI (Q35) so the
                                     gate IS the control evidence auditors recognize.
 3. Profile-as-code, locked         inherited locked parent profile (Q46) under change
                                     control (Q94) — every rule change is a reviewed PR.
 4. Immutable audit trail           retain analysis history + gate results; DB backups
                                     with PITR; access + admin actions logged (audit log).
 5. Reproducible analysis           pinned analyzer versions + version-controlled profiles
                                     (Q87) so a finding is attributable to a known ruleset.
 6. Segregation of duties           who can change gates/profiles ≠ who develops; SSO +
                                     permission templates enforce it.
```

The reasoning per pillar: **data residency** usually forces **self-hosted** (Q40) — regulated code can't be analyzed off-prem, ruling out Cloud. The **gate is a documented control**, so its conditions should map to the **standards the regulation cares about** (PCI, OWASP, CWE Top 25 — Q35), and a passing gate becomes evidence you can hand an auditor grouped by standard rather than a raw issue list. **Profile-as-code under change control** (Q94) plus **pinned analyzer versions** (Q87) give *reproducibility and attributability* — you can prove which ruleset judged a given release, which a regulator will ask. **Retention and audit logging** turn analysis history into an immutable record (the opposite of the aggressive-housekeeping advice in Q86 — here you *keep* more for evidence, balancing storage against compliance). **Segregation of duties** via SSO and permission templates (Q71) ensures developers can't quietly weaken the gate that governs their own code.

**Trade-offs:** this is heavier and slower than a startup's setup — locked profiles and change-controlled gates add friction, longer retention costs storage, and self-hosting adds operational burden and makes SonarQube a tier-1 dependency (Q78). But in a regulated shop the cost of *not* being able to prove a control was applied dwarfs that friction. The senior framing is recognizing that the *primary output flips*: in a normal shop SonarQube's value is catching defects; in a regulated shop its value is **producing auditable, reproducible, attributable evidence that defect-prevention controls were enforced** — and that drives self-hosting, standards-mapped gates, profile/version pinning, retention, and segregation of duties, several of which are the *opposite* of what you'd optimize for elsewhere.

#### Q107. [Coding] Write a script that detects "gaming" — projects with abnormally high exclusion coverage or won't-fix rates — using the SonarQube web API.

**Problem:** Operationalize Q79's governance: instead of manually inspecting projects, query the API across all projects and flag outliers — high won't-fix rate, broad exclusions, or large profile deltas — so governance is *detection-driven*, not police-driven.

```python
#!/usr/bin/env python3
"""Flag projects whose metrics suggest gaming (Q79). Read-only; conversation-starter."""
import os, requests

BASE = os.environ["SONAR_URL"]; TOK = (os.environ["SONAR_TOKEN"], "")
S = requests.Session(); S.auth = TOK

def get(path, **params):
    r = S.get(f"{BASE}{path}", params=params, timeout=30); r.raise_for_status()
    return r.json()

def all_projects():
    page, out = 1, []
    while True:
        d = get("/api/projects/search", p=page, ps=500)
        out += [c["key"] for c in d["components"]]
        if page * 500 >= d["paging"]["total"]: break
        page += 1
    return out

def signals(key):
    # Total vs resolved-as-wontfix/accepted issues
    total = get("/api/issues/search", componentKeys=key, ps=1)["total"]
    wontfix = get("/api/issues/search", componentKeys=key, ps=1,
                  resolutions="WONTFIX,FIXED" and "WONTFIX", statuses="RESOLVED")["total"]
    ncloc = next((m["value"] for m in
                  get("/api/measures/component", component=key,
                      metricKeys="ncloc")["component"]["measures"]
                  if m["metric"] == "ncloc"), "0")
    wf_rate = (wontfix / total) if total else 0.0
    return {"key": key, "ncloc": int(ncloc), "issues": total,
            "wontfix": wontfix, "wontfix_rate": round(wf_rate, 3)}

flagged = []
for k in all_projects():
    s = signals(k)
    # Heuristics: high won't-fix rate, or many issues silenced
    if s["wontfix_rate"] > 0.25 or (s["wontfix"] > 100 and s["wontfix_rate"] > 0.15):
        flagged.append(s)

flagged.sort(key=lambda x: -x["wontfix_rate"])
for s in flagged:
    print(f"REVIEW {s['key']:40} wontfix={s['wontfix']:5} "
          f"rate={s['wontfix_rate']:.0%} ncloc={s['ncloc']}")
```

- **Why detection over policing (Q79):** the metrics are *locally controllable* — a team can mass-mark "won't fix" or exclude files to manufacture a green gate. This script pulls the **won't-fix rate** and issue/LOC ratios across *all* projects via the API and flags outliers for a *conversation*, not punishment. A 40%-excluded project or a 30%-won't-fix project is a signal something's off; you investigate the why.
- **API mechanics that matter:** `/api/projects/search` and `/api/issues/search` are **paginated** (note the `p`/`ps` loop and the `paging.total` termination) — a naive single call silently misses projects past the first page, a classic API-script bug. Won't-fix is a `resolution` on `RESOLVED` issues; comparing it to total issues yields the rate. (You'd similarly fetch `sonar.exclusions` via the settings API to flag broad globs — omitted for brevity.)
- **The governance framing (the senior point):** this script is a **guardrail**, not a gate — its output is "have a conversation with these teams," because gaming is usually a *response to punitive measurement* (Q79), so the fix is partly cultural. You pair it with the *structural* controls that remove the gameable freedom in the first place: locked inherited profiles (Q46) and a default org gate (Q19), so detection covers the residual local levers (exclusions, won't-fix) that can't be locked away. **Complexity:** O(projects) API calls, paginated; run it on a schedule, not per-build, to avoid hammering the API.

#### Q108. [Practical] Design the analysis strategy for a machine-learning / data repository (notebooks, Python, SQL, pipelines) where conventional code-quality assumptions break down.

ML/data repos violate several assumptions SonarQube's defaults bake in: a lot of the "code" is **Jupyter notebooks** (JSON-wrapped cells, not `.py` files), much of it is **exploratory/throwaway**, **coverage means little** for data-transformation glue, and the real risks are **data leakage, non-determinism, and pipeline correctness** that static analysis can't see. A naive "scan everything, gate on 80% coverage" produces noise and resentment. The design must be selective about *what* to analyze and *what to gate on*.

```
 Asset in an ML repo            Analysis approach
 ─────────────────────────────  ──────────────────────────────────────────────
 Production pipeline code (.py)  full native analysis + gate (it's real software)
 Library/utils (.py)            full native analysis + coverage gate
 Jupyter notebooks (.ipynb)     analyze for security/secrets; DON'T gate on coverage;
                                 often exclude exploratory notebooks entirely
 SQL (dbt/queries)              SQL analyzer (Developer Ed.+) for injection/anti-patterns
 Generated/model artifacts      exclude (binaries, weights, generated schemas)
 Config/YAML (pipelines)        IaC/structural checks (Q104) for misconfig
```

The strategy: (1) **Separate production code from exploration.** The pipeline/serving/library code is real software — analyze and gate it like any service (bugs, vulnerabilities, coverage on new code). Exploratory notebooks are often *excluded* (`sonar.exclusions`) because gating coverage on a data scientist's scratch notebook is pure friction — but you still want **secret detection** on them, since notebooks are a notorious place hardcoded credentials and connection strings leak (Q68), so you might keep them in *analysis* but out of *coverage* (the `coverage.exclusions` vs `exclusions` distinction from Q37). (2) **Notebooks need care** — `.ipynb` is JSON; SonarQube's Python analyzer has notebook support, but verify your version handles it or convert via `jupytext`. (3) **Coverage is the wrong primary metric** here — gate on **security and reliability** (no hardcoded secrets, no SQL injection in query-building code, no obvious bugs) rather than a coverage percentage that data glue can't meaningfully hit; this is the Clean-as-You-Code-but-per-quality idea (MQR impacts, Q30) applied pragmatically.

**Trade-offs and the limits to name explicitly:** the highest-value ML risks — **train/serve skew, data leakage between train and test, non-deterministic pipelines, model drift** — are **runtime/data-semantic** problems SonarQube structurally cannot detect (Q44); they belong to data tests, pipeline validation, and ML-specific tooling. So SonarQube's role in an ML repo is *narrow but real*: enforce code-level hygiene and security on the **production** code and SQL, catch leaked secrets in notebooks, and *deliberately not* pretend a green gate says anything about model correctness. The senior framing is matching the tool to where it adds value (production Python/SQL security and reliability) and consciously **excluding or de-gating** the parts where its assumptions (coverage = quality) don't hold — rather than forcing a service-shaped gate onto a data-shaped repo.

#### Q109. [Coding] Write a rule-testing harness / fixture-driven test for a custom rule, and explain what makes custom-rule tests robust vs brittle.

**Problem:** Custom rules are production code (Q103) and need real tests, but string-matching on issue messages is brittle. Show the idiomatic fixture-driven approach (extending Q97) including *negative* cases, message assertions, and secondary locations, and explain the robustness principles.

```java
import org.sonar.java.checks.verifier.CheckVerifier;
import org.junit.jupiter.api.Test;

class BanLegacyLoggerRuleTest {

    @Test void flagsBannedUsageWithExactMessage() {
        CheckVerifier.newVerifier()
            .onFile("src/test/files/LoggerUsage.java")
            .withCheck(new BanLegacyLoggerRule())
            // resolve types: Spring/legacy libs must be on the test classpath (Q24)
            .withClassPath(TestClasspath.forRule())
            .verifyIssues();        // asserts issues match // Noncompliant comments
    }

    @Test void cleanFileRaisesNothing() {
        CheckVerifier.newVerifier()
            .onFile("src/test/files/NoLoggerUsage.java")
            .withCheck(new BanLegacyLoggerRule())
            .verifyNoIssues();      // negative case — guards against false positives
    }
}
```

```java
// src/test/files/LoggerUsage.java — assertions are INLINE comments next to code
class LoggerUsage {
    void bad() {
        com.acme.LegacyLogger.log("x");  // Noncompliant {{LegacyLogger is banned; use SLF4J.}}
    }
    void aliased() {
        var l = new com.acme.LegacyLogger();
        l.log("y");                       // Noncompliant — caught via resolved type, not text
    }
    void fine() {
        org.slf4j.LoggerFactory.getLogger("z");  // no issue expected here
    }
    void notOurLogger() {
        other.pkg.LegacyLogger.log("decoy");      // compliant: different package, same name
    }
}
```

- **Why fixture-driven (`// Noncompliant`) beats string assertions:** the expected results live *next to the code that should trigger them*, so the test is self-documenting and the line number is implicit — you can't accidentally assert the issue on the wrong line. `verifyIssues()` fails if the rule reports an issue where there's no `// Noncompliant`, *or* misses one where there is — catching both false positives and false negatives in one assertion. The `{{...}}` syntax pins the exact message.
- **What makes tests *robust*:** (1) **a negative-case file** (`verifyNoIssues()`) — the most-skipped and most-valuable test, because the failure mode that destroys trust is false positives (Q14), not missed catches. (2) **adversarial fixtures** — the `aliased()` (variable, not literal call) and `notOurLogger()` (same simple name, different package) cases prove the rule uses the **semantic model**, not text matching (Q23/Q99); a regex rule would fail both. (3) **providing the classpath** so types resolve — a custom rule tested *without* the relevant libs on the classpath silently can't resolve types and the test passes for the wrong reason (the bytecode-dependency trap, Q24).
- **What makes them *brittle*:** asserting on absolute issue counts across a big file, matching messages by `contains` substring, or fixtures that don't include the tricky cases (aliasing, same-name-different-package, comments/strings containing the token). The senior framing: a custom rule's test suite must **prove precision, not just detection** — the decisive fixtures are the ones designed to make a *naive* implementation fail (aliased call, decoy name, in-comment occurrence), because those are exactly the false-positive sources that get a rule disabled in production. **Complexity:** each verifier run is O(fixture size); keep fixtures small and targeted.

#### Q110. [Behavioral] Tell me about a time you owned a code-quality initiative that had to balance enforcement against developer velocity at the org level. What did you trade off?

This is a staff/principal-level prompt about *judgment under competing goods* — enforcement vs velocity are both legitimate, and the interviewer wants to see that you optimized the *system*, not your local metric, and that you can articulate the trade you consciously made. A strong **STAR** answer centers on a real tension and a measured outcome.

*Situation:* I owned the rollout of a quality program across ~120 services after a prior attempt had stalled — the gate enforced overall legacy debt, builds were perpetually red, and teams had routed around it; meanwhile leadership wanted "stricter quality" and product wanted "stop slowing us down." Two real, opposing mandates. *Task:* land org-wide enforcement that was strict enough to matter but light enough that velocity didn't crater, and do it without re-triggering the earlier backlash.

*Action:* I made the central trade explicit and *chose it on purpose*: **enforce hard on a small, non-negotiable baseline (new-code security + reliability), advisory on everything else.** Concretely — switched all gates to **new-code only** so legacy debt became informational (Q14/Q70); built a **locked inherited parent profile** with just the security/reliability rules that genuinely block (Q46), letting teams add their own rules above it; made the gate a **required PR check but diff-scoped** so it was achievable per PR; and rolled out **Connected-Mode SonarLint** so the common issues were caught pre-PR rather than blocking merges. I deliberately **did not** gate on maintainability or a coverage number org-wide — those stayed advisory — because gating them would have taxed velocity for marginal risk reduction (the per-quality-impact idea from Q30). I instrumented **escaped-defect rate and shift-left ratio** (Q83), not gate-pass-rate, so we'd know if it was actually working.

*Result:* PR-blocking on the security/reliability baseline caught a real injection and several null-deref classes before merge, escaped-defects of those classes dropped, and — the part that mattered politically — **PR cycle time didn't regress**, because the gate only blocked on the small achievable baseline. The trade I consciously accepted: **some legacy and maintainability debt is never forced clean** (Q43's asymptote), and I defended that explicitly to leadership as the right exchange — we bought *sustained adoption and protected velocity* by giving up *legacy completeness*. The meta-points the interviewer is listening for: I treated velocity as a first-class constraint rather than collateral, I made the enforcement/velocity trade-off **explicit and defensible** rather than maximizing enforcement, I locked only the baseline that's worth blocking and left the rest advisory, and I measured **outcomes** (escaped defects, cycle time) rather than the vanity gate-pass-rate — because a quality program that tanks velocity gets killed, and one that enforces nothing meaningful is theater; the staff-level skill is finding and *naming* the line between them.

#### Q116. [Coding] Write a custom Python rule for the SonarQube Python analyzer, and contrast its visitor model with the Java analyzer's.

**Problem:** A Python shop wants to ban `eval()`/`exec()` on user-derived input beyond the built-in rules. Write a custom Python check using the Python analyzer's plugin API and explain how its visitor model differs from Java's (Q15/Q97), since Python is dynamically typed (Q24).

```java
// SonarQube Python custom rules are still authored in JAVA against the
// sonar-python plugin API (org.sonar.plugins.python.api).
import org.sonar.check.Rule;
import org.sonar.plugins.python.api.PythonSubscriptionCheck;
import org.sonar.plugins.python.api.SubscriptionContext;
import org.sonar.plugins.python.api.tree.CallExpression;
import org.sonar.plugins.python.api.tree.Tree;

@Rule(key = "BanEval")
public class BanEvalCheck extends PythonSubscriptionCheck {
    @Override public void initialize(Context context) {
        // Subscribe to call expressions — analogous to Java's nodesToVisit()
        context.registerSyntaxNodeConsumer(Tree.Kind.CALL_EXPR, this::checkCall);
    }
    private void checkCall(SubscriptionContext ctx) {
        CallExpression call = (CallExpression) ctx.syntaxNode();
        // Python type inference is best-effort (no bytecode, Q24) — match on the
        // callee name, accepting this is weaker than Java's resolved-type match.
        var callee = call.calleeSymbol();
        if (callee != null && ("eval".equals(callee.name()) || "exec".equals(callee.name()))) {
            ctx.addIssue(call.callee(), "Do not use eval/exec; they enable code injection.");
        }
    }
}
```

- **What's the same:** the *shape* mirrors Java — you subscribe to a node kind (`CALL_EXPR`) and get a callback per matching node, the efficient subscription model from Q15 (O(matching nodes), not O(all nodes)). Rules are likewise authored in **Java** (the analyzer is JVM-based even though it analyzes Python), registered via a `RulesDefinition`/registrar, and tested with the Python analyzer's verifier.
- **What's different and *why*:** Python has **no compiled artifact**, so the semantic model is **inferred, not resolved** (Q24). The Java `@Autowired` rule (Q97) could match the *exact resolved type* via bytecode; here `calleeSymbol()` gives a best-effort symbol, and matching by name is inherently weaker — a local function also named `eval` could false-positive, and an aliased import (`from os import system as eval`) complicates things. So Python custom rules lean more on name/usage heuristics and benefit from any type hints / `tsconfig`-equivalent context available, but cannot achieve Java's precision. This is the direct practical consequence of "type truth lives in the artifact for compiled languages, in inference for dynamic ones" (Q24).
- **The senior point:** the *authoring framework* is uniform across analyzers (subscribe-to-node + Java + RulesDefinition + verifier tests), but the **precision ceiling is language-dependent** — a dynamic-language custom rule must be written defensively around weaker symbol resolution, and you'd reach for it knowing it will be noisier than its Java equivalent, which feeds back into the noise-management discipline of Q14/Q109. **Complexity:** O(call expressions), trivial per node.

#### Q117. [Theory] Explain the cost model of AST traversal and symbolic execution inside an analyzer, and why a poorly-written custom rule can blow up analysis time.

A language analyzer's runtime is dominated by two phases with very different cost profiles, and a custom rule (Q15/Q97/Q116) executes *inside* this machinery, so a careless rule can multiply analysis time across every file in the org. The first phase — **AST traversal** — is roughly **O(nodes)**: the analyzer parses each file into a tree and walks it once, invoking subscribed visitors. Because `IssuableSubscriptionVisitor` only fires for the **node kinds you register**, a well-written rule adds O(matching nodes) work to that single walk. The second, far more expensive phase is **symbolic execution / data-flow** (the basis of bug-detection and taint, Q39): the analyzer explores *paths* through the control-flow graph, and path count can grow **super-linearly** (toward exponential in the worst case) with branching and call depth — which is exactly why taint analysis is heavy and edition-gated.

```
 Phase                  Cost            What a bad custom rule does to it
 ─────────────────────  ──────────────  ──────────────────────────────────────────
 Parse + build AST      O(file size)    unavoidable, fixed
 Subscription walk      O(matched       subscribe to too-broad a kind (e.g. every
                         nodes)           IDENTIFIER) → callback fires on every token
 Inside the callback    YOUR code       do O(n) work per node → O(n²) per file;
                                          re-resolve symbols repeatedly → blowup
 Symbolic execution     up to O(paths)  custom flow analysis without bounds → explosion
   (engine-level)        (super-linear)
```

The ways a custom rule blows up: (1) **subscribing too broadly** — registering `IDENTIFIER` or `TOKEN` makes your callback fire on essentially every node, turning a cheap walk expensive; subscribe to the *narrowest* kind that suffices (`NEW_CLASS`, `METHOD_INVOCATION`). (2) **Doing expensive work per node** — e.g., re-walking the subtree or re-resolving the same symbols on each visit turns O(n) into O(n²); cache or hoist invariant lookups. (3) **Naive nested searches** — "for each method, scan all other methods" is O(methods²) and devastating on large files. (4) Anything that triggers heavy **re-computation of the semantic model** repeatedly. Because analysis runs on every file in every project on every PR, an inefficient rule's cost is multiplied by **(files × analyses)** across the org — a rule that adds 50ms/file is invisible on one file and catastrophic on a 10M-LOC monorepo (Q115).

The senior takeaway: a custom rule is **hot-path code** in an O(nodes)-per-file, potentially-O(paths) engine, so you optimize it like any inner loop — subscribe narrowly, do O(1) work per node, avoid re-resolving symbols, and never nest scans. This is why the subscription model exists (Q15) and why rule review should include a performance lens, not just correctness — the false-positive-driven distrust of Q14 has a twin in *slowness*-driven distrust: a rule that doubles CI time gets the whole tool blamed.

#### Q118. [Practical] You're migrating off a legacy commercial SAST tool (e.g., Fortify/Checkmarx) to SonarQube. How do you plan the migration so you don't lose security coverage or trust?

A SAST migration is risky because the two tools have **different rule sets, different false-positive profiles, and different coverage of vulnerability classes** — a naive cutover either drops real findings the old tool caught or floods the team with new findings, and either way the security org loses trust. I'd plan a **parallel-run, evidence-driven** migration rather than a flag-day switch.

```
 Migration phases
 ────────────────
 1. INVENTORY     map the old tool's enabled rules → CWE/OWASP (Q35); record what
                  classes it covers (injection, crypto, access-control attempts).
 2. PARALLEL RUN  run BOTH tools on the same codebases for a period; diff findings.
 3. GAP ANALYSIS  classify: caught-by-both, only-old (coverage gap to fill),
                  only-new (new value or new false positives?).
 4. TUNE          fill gaps with custom rules / imported tools (Q81/Q95); prune
                  Sonar noise; map gates to the same standards (Q35).
 5. CUTOVER       make Sonar the gate; keep old tool read-only until confidence;
                  then decommission.
```

The reasoning: the **inventory + standards mapping** (Q35) is what makes the comparison meaningful — you compare *coverage by CWE/OWASP category*, not raw counts, because the tools name findings differently. The **parallel run** is non-negotiable: it produces the evidence (caught-by-both vs only-one) that tells you whether SonarQube actually covers what you're losing. The crucial output is the **only-old set** — vulnerability classes the legacy tool caught that SonarQube doesn't (perhaps a specialized taint sink, a framework Sonar models less well, or DAST-adjacent checks Sonar structurally can't do, Q44); for each you decide to fill the gap (a custom rule, Q97; an imported specialized scanner, Q81/Q95) or consciously accept it. The **only-new set** is triaged for whether it's genuine new value or just a different false-positive profile to tune (Q74).

**Trade-offs and the trust dimension:** running two SAST tools in parallel is expensive (CI time, two sets of triage) but it's the price of *not* silently regressing security coverage — and the security/compliance stakeholders need that evidence to sign off (especially in regulated contexts, Q106). The biggest risk is **false-positive shock**: SonarQube's taint analysis has a different FP profile, so without pruning (Q14) the team concludes "the new tool is noise" and resists — so you tune *before* cutover, not after. The senior framing: a SAST migration is a **coverage-equivalence problem mapped through standards**, not a tool swap — you prove via parallel run and CWE/OWASP mapping that you're not losing protection, fill the documented gaps with custom rules or imports, tune to control noise, and only then make SonarQube the authoritative gate, keeping the old tool as a read-only safety net until the evidence justifies decommissioning it.

#### Q119. [Theory] Why can two analyses of the *exact same code* legitimately differ between SonarQube editions, and what does that imply for "the gate passed" as a portable claim?

This pushes on a subtle correctness-of-claims point: "SonarQube passed this code" is **not edition-portable**, because the analyzers and capabilities differ by edition, so the *same source* genuinely produces *different findings* on Community vs Developer vs Enterprise vs Cloud. The differences aren't bugs — they're capability boundaries (Q13).

```
 Capability                      Community   Developer+   Cloud
 ─────────────────────────────   ─────────   ──────────   ─────────
 Rule-based bugs/smells          yes         yes          yes
 Branch / PR analysis            NO          yes          yes
 Taint analysis (SAST)           NO          yes          yes
 C/C++/COBOL/etc. analyzers      NO          yes          yes
 Portfolio aggregation           NO          Enterprise   varies
```

The implication is that a green gate on **Community Edition** may have *never run taint analysis*, so it cannot have caught the SQL-injection-across-files class of vulnerability (Q39) — its "pass" is a weaker claim than a Developer-Edition pass on identical code. Likewise a project that compiles a C++ module gets *no* analysis of that module on Community (no C++ analyzer), so the gate's silence there means "not analyzed," not "clean." Even between Developer and Cloud, analyzer versions and the default experience (MQR vs Standard, Q29) can differ, shifting counts and metric semantics.

The senior takeaways: (1) **"the gate passed" is only as strong as the edition's capabilities** — you must qualify it ("passed *with* taint analysis on Developer Edition" vs "passed rule-based checks only on Community"). (2) For **reproducibility and portable claims** (Q87), you pin not just analyzer versions but the **edition/capability set**, because moving a project from Community to Developer can legitimately turn a green gate red by enabling taint analysis that finds a pre-existing vulnerability. (3) This is why a security program shouldn't treat "Sonar gate green" as a uniform sign-off across a heterogeneous estate (Q44) — the *meaning* of green depends on which analyzers actually ran. Knowing that edition determines *which analyses even execute* (so green can mean "not checked") rather than just "more features" is the depth signal.

#### Q120. [Coding] Write a script that audits Quality Profile drift between the live SonarQube server and the version-controlled profile XML, for CI to fail on un-reviewed changes.

**Problem:** Profiles are version-controlled (Q94), but someone can still edit a profile in the UI, causing drift and non-reproducible analysis (Q87). Build a CI check that diffs the *live* profile against the committed XML and fails if they differ, forcing changes through PR.

```bash
#!/usr/bin/env bash
# profile-drift-check.sh — fail CI if the live profile != committed XML.
set -euo pipefail
: "${SONAR_URL:?}" "${SONAR_TOKEN:?}"
LANG_="${1:?language}"; PROFILE="${2:?profile name}"; COMMITTED="${3:?path to xml}"

# 1) Pull the LIVE profile from the server as XML.
live=$(curl -fsS -u "${SONAR_TOKEN}:" \
  "${SONAR_URL}/api/qualityprofiles/backup?language=${LANG_}&qualityProfile=$(
     printf %s "$PROFILE" | jq -sRr @uri)")

# 2) Normalize both (strip volatile attrs / whitespace) so the diff is meaningful.
norm() { xmllint --c14n - 2>/dev/null | sed -E 's/>[[:space:]]+</></g'; }
live_n=$(printf '%s' "$live"      | norm)
comm_n=$(norm < "$COMMITTED")

# 3) Diff. Nonzero diff => drift => fail the job with a clear message.
if [ "$live_n" != "$comm_n" ]; then
  echo "DRIFT DETECTED in profile '${PROFILE}' (${LANG_})."
  diff <(printf '%s\n' "$comm_n") <(printf '%s\n' "$live_n") || true
  echo "The live profile was edited outside git. Reconcile via PR (Q94) or restore."
  exit 1
fi
echo "Profile '${PROFILE}' matches committed XML — no drift."
```

- **Why a drift check, not just a restore?** Q94's restore *forces* the server to match git on merge, but between merges someone can still edit the profile live; this check runs (e.g., nightly or pre-deploy) and **fails loudly on drift**, turning an un-reviewed UI edit into a visible CI failure rather than silent non-determinism (Q87). Restore (push git→server) and drift-check (compare server vs git) are complementary halves of GitOps for profiles.
- **The normalization is the hard part:** a naive string compare fails because the backup XML may differ in attribute order, whitespace, or volatile metadata even when *semantically* identical. Canonicalizing (`xmllint --c14n`) and collapsing inter-element whitespace makes the diff reflect *real* rule/parameter differences, not formatting noise — the same "compare meaning, not bytes" lesson as issue fingerprinting (Q25). In practice you might compare the *set of activated rules + params* parsed from each rather than raw XML, for an even more robust signal.
- **The governance tie-in (Q79):** combined with locked inherited profiles (Q46) and gate-as-code (Q114), this closes the loop — the structural controls prevent *unauthorized weakening*, while the drift check catches *any* out-of-band change so the committed XML stays the source of truth. **Complexity:** O(profile size) for the fetch and diff; cheap enough to run on a schedule. **Edge case:** `-f` on curl ensures a 404 (wrong profile name) fails the script instead of comparing against an error page (the same robustness point as Q73).

#### Q121. [Behavioral] As a staff engineer, you discover a team has been mass-marking real security findings as "won't fix" to keep their gate green before a release. How do you handle it?

This is an integrity-and-influence scenario, not a tooling one — the interviewer wants to see that you address the *behavior and its root cause* without nuking trust, escalate proportionally, and fix the *system* so it can't recur (Q79). A **STAR** structure keeps it concrete.

*Situation:* Pre-release, I found (via an audit script like Q107) that a team had marked dozens of injection/auth findings "won't fix" with no justification, flipping their gate green. *Task:* protect against shipping real vulnerabilities, address the behavior fairly, and prevent recurrence — all without turning into the "quality police" (Q79) that makes teams hide things further.

*Action:* First, **don't ambush — understand.** I'd talk to the team lead privately before raising alarms, because mass-won't-fix is almost always a *symptom*: an impossible release deadline, a gate enforcing things they couldn't fix in time, or a belief the findings were false positives. Distinguishing "gaming under pressure" from "honest disagreement about validity" determines everything. If a chunk are genuine false positives, that's a *tuning* problem (Q74) and partly my failure for not pruning. For the **real** vulnerabilities, though, I'd be unambiguous: security findings marked won't-fix without rotation/remediation are a release risk, and I'd escalate to the release decision-makers with the *evidence* (the specific CWE-mapped findings, Q35) so it's a risk-acceptance decision made *consciously by the right people*, not silently by a developer under deadline. *Result framing:* the fix is both immediate (re-open and triage the real findings before the release ships, get an explicit, documented risk-acceptance from security/leadership for anything genuinely deferred) and *systemic* — require **justification comments on won't-fix**, monitor the **won't-fix rate** as a guardrail metric (Q79/Q83), and address the **root cause** (a too-strict gate, an unrealistic deadline, or unpruned noise) so the team isn't structurally incentivized to cheat again.

The meta-points interviewers listen for: (1) you treat the gaming as a **signal about pressure or misconfiguration**, investigating root cause before assigning blame — most cheating is a rational response to a broken incentive (Q79). (2) You **don't compromise on real security findings** — you make deferral an *explicit, documented, properly-authorized* risk acceptance, never a silent suppression. (3) You **escalate proportionally and with evidence**, not punitively. (4) You fix the **system** (justification requirements, won't-fix monitoring, gate/deadline root cause) so structure prevents recurrence, rather than just scolding. The staff-level judgment is holding the security line firmly while attacking the *incentive* that produced the behavior — because if you only punish the symptom, the next deadline produces the same cheating, just better hidden.

## ✅ Key Takeaways

- SonarQube performs **static analysis**; it categorizes findings as **bugs (reliability), vulnerabilities (security), code smells (maintainability), and security hotspots (needs human review)** — know the four cold.
- The **Quality Gate** is the pass/fail enforcement point; default to **Clean as You Code** (new-code focus, reference-branch baseline) so the gate is achievable and developers stay engaged.
- Sonar **imports** coverage (JaCoCo XML for Java) — it does not measure it; wrong/missing report paths are the #1 cause of "0% coverage."
- **Branch/PR analysis** and **taint (SAST) analysis** require **Developer Edition or higher** (or SonarCloud); Community Edition is single-branch and rule-based only.
- Wire the gate into CI with `sonar.qualitygate.wait=true` and make it a **required PR status check** — analysis without enforcement is just a dashboard.
- **SonarLint Connected Mode** shifts feedback to keystroke time and keeps IDE and server rule-consistent.
- **Quality Profiles** (rulesets) should be **minimal, inherited from a locked org baseline**, and tuned to kill noise.
- SonarQube is **one layer** of quality/security — it cannot find runtime, concurrency, business-logic, dependency, or behavioral bugs.

## ⚠️ Common Pitfalls

- Enforcing the gate on **overall (legacy) code**, making every build red until the team disables/ignores it.
- Forgetting to generate the **JaCoCo XML report** (running `mvn test` without the `report` goal) or pointing `sonar.coverage.jacoco.xmlReportPaths` at the wrong path.
- Using the deprecated binary `sonar.jacoco.reportPaths` instead of XML `sonar.coverage.jacoco.xmlReportPaths`.
- Assuming the scanner step failing/succeeding reflects the gate — without `qualitygate.wait`, CI passes even on a red gate.
- Treating **high coverage as high quality** — covered-but-unasserted lines give false confidence.
- Blanket `// NOSONAR` or marking issues "Won't Fix" without justification, eroding trust in the metrics.
- Expecting Community Edition to do PR decoration or taint analysis (it can't).
- Running embedded **H2** or single-node Sonar in production and being surprised by data loss, lag (compute-engine queue), or no HA.
- Not excluding **generated/vendored** code, drowning the team in unactionable issues.
- Treating a green gate as a **security sign-off** while skipping SCA, DAST, and dependency scanning.

## 📚 Further Reading

- **SonarQube Server Documentation** — official docs (Quality Gates, Clean as You Code, branch/PR analysis): https://docs.sonarsource.com/
- **SonarSource Rules Explorer** — every rule, per language, with examples: https://rules.sonarsource.com/java/
- **Writing Custom Java Rules 101** — SonarSource's hands-on plugin tutorial: https://github.com/SonarSource/sonar-java/blob/master/docs/CUSTOM_RULES_101.md
- **JaCoCo Documentation** — coverage report generation for the JVM: https://www.jacoco.org/jacoco/trunk/doc/
- *Clean Code* and *Clean Coder* — Robert C. Martin (the maintainability philosophy SonarQube operationalizes).
- **OWASP Top 10** — the security categories SonarQube's vulnerability/hotspot rules map to: https://owasp.org/www-project-top-ten/
