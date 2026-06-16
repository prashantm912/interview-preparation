# DevSecOps & Secure SDLC

DevSecOps embeds security as a continuous, automated, shared responsibility across the entire software delivery lifecycle — from a developer's IDE to runtime — rather than bolting it on as a late, manual gate. This guide covers shift-left security, the SAST/DAST/IAST/SCA toolchain, supply-chain integrity (SBOM, SLSA, sigstore), policy as code, and how to build security gates into CI/CD without grinding delivery to a halt.

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

### Q1. [Theory] What is DevSecOps and how does it differ from traditional DevOps and classic "security at the end"?

DevSecOps extends DevOps by making security a first-class, automated concern that every team member owns, instead of a phase that a separate security team performs right before release. In the classic waterfall/"security-gate-at-the-end" model, a penetration test or security review happens after code is written and frozen, so any finding is expensive to fix and often delays the release or ships as accepted risk. DevOps optimized for fast, automated delivery but historically treated security as someone else's problem; DevSecOps closes that gap by inserting fast, automated security checks into the same pipelines that already run builds and tests. The cultural shift is as important as the tooling: developers get security feedback in seconds (in the IDE and on pull requests) so they can fix issues while the context is fresh. The trade-off is that you must invest in tuning tools to avoid drowning developers in false positives, which erodes trust and gets security checks ignored or disabled.

### Q2. [Theory] What does "shift-left security" mean, and why does it save money?

Shift-left means moving security activities earlier ("to the left") in the timeline — into design, coding, and commit stages — instead of only at testing or production. The economic argument is the well-documented cost curve: a defect found in design might cost \$1 to fix, in QA \$10–100, and in production \$1,000+ once you add incident response, customer impact, and emergency patching. Shifting left also shifts *responsibility* to developers, who are the only people who can cheaply fix a vulnerability the moment they introduce it. Concretely, this looks like IDE plugins that flag insecure code, pre-commit hooks that block secrets, and SAST/SCA running on every pull request. The caveat is that "shift-left" must not become "dump-left": you cannot just hand developers ten scanners and expect security to improve — the checks must be fast, accurate, and accompanied by remediation guidance.

### Q3. [Theory] Define SAST, DAST, IAST, and SCA. When does each run?

These are the four pillars of automated application security testing:

```
            Source code?   Running app?   Inside the app?   Third-party deps?
SAST  ......  YES            no             no                no   (white-box, static)
DAST  ......  no             YES            no                no   (black-box, dynamic)
IAST  ......  partial        YES            YES (agent)       no   (instruments runtime)
SCA   ......  manifest        no            no                YES  (dependency/license scan)
```

- **SAST** (Static Application Security Testing) analyzes source/bytecode without executing it; it finds injection, hardcoded secrets, and insecure APIs early but is prone to false positives. Tools: SonarQube, Semgrep, Checkmarx, CodeQL.
- **DAST** (Dynamic) attacks a *running* application from the outside like an attacker would (e.g., OWASP ZAP, Burp). It finds real, exploitable issues with few false positives but runs late and gives no line-level location.
- **IAST** (Interactive) places an agent *inside* the running app during functional/integration tests, correlating runtime data flow with code; it combines static precision with dynamic accuracy (e.g., Contrast Security).
- **SCA** (Software Composition Analysis) inventories third-party/open-source dependencies and matches them against vulnerability databases (CVE/NVD, GitHub Advisory). Tools: Snyk, Dependabot, OWASP Dependency-Check. Since 80–90% of a modern app is open-source code, SCA is often where the highest-severity, easiest-to-fix issues live.

### Q4. [Practical] How would you stop secrets (API keys, passwords) from ever reaching your Git history?

Defense in depth across three layers. First, **pre-commit** prevention with a tool like `gitleaks` (or `trufflehog`) wired into a pre-commit hook so secrets are caught on the developer's machine before they are ever committed. Second, a **CI gate** that re-runs the scanner on the server side (developers can bypass local hooks), failing the build on any finding — scan the *full* history of the branch, not just the diff, since secrets sneak in via rebases. Third, **provider-side push protection** like GitHub Secret Scanning, which blocks the push at the platform and notifies partners (AWS, Stripe) to auto-revoke leaked tokens.

```bash
# .pre-commit-config.yaml entry + manual run
gitleaks detect --source . --redact --no-banner
# Scan only what's staged, fast feedback at commit time:
gitleaks protect --staged --redact
```

The critical operational point: if a secret *does* leak, scanning is necessary but not sufficient — you must **rotate/revoke the credential**, because rewriting Git history does not un-leak a value that was already cloned or cached. Treat any committed secret as compromised.

### Q5. [Practical] A teammate adds a Java dependency. How do you check it for known vulnerabilities before merging?

Run Software Composition Analysis in CI on the build manifest (Maven `pom.xml` / Gradle). OWASP Dependency-Check is the common free option for the JVM:

```xml
<!-- pom.xml: fail the build if any dependency has a CVSS >= 7 (High) -->
<plugin>
  <groupId>org.owasp</groupId>
  <artifactId>dependency-check-maven</artifactId>
  <version>10.0.4</version>
  <configuration>
    <failBuildOnCVSS>7</failBuildOnCVSS>
    <nvdApiKey>${env.NVD_API_KEY}</nvdApiKey> <!-- avoids NVD rate limits -->
  </configuration>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

In production I would: (1) gate the PR so a new High/Critical CVE blocks merge, (2) prefer the suggested fixed version (often a patch bump), and (3) where no fix exists, evaluate whether the vulnerable code path is actually reachable — Snyk and modern SCA tools do *reachability analysis* so you do not block a release over a CVE in a method you never call. Always cache the NVD database and use an API key, or the scan becomes painfully slow and flaky.

### Q5b. [Theory] What is an SBOM and why does every shipped artifact need one?

An SBOM (Software Bill of Materials) is a machine-readable, complete inventory of every component, library, and version inside a build artifact — the "ingredients label" for software. The two dominant standards are **CycloneDX** (OWASP, security-focused) and **SPDX** (Linux Foundation, license/compliance-focused). Its value is reactive speed: when the next Log4Shell-class CVE drops, an organization with SBOMs can answer "are we affected, and where?" in minutes by querying stored SBOMs, instead of spending days grepping codebases. SBOMs are now effectively mandatory for vendors selling to the US federal government (per Executive Order 14028) and underpin supply-chain frameworks like SLSA. Generate the SBOM at build time from the actual resolved dependency graph, attach it to the artifact, and store it so you can scan it continuously against new advisories — not just once at build.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Practical] Design a set of CI/CD security gates for a Java microservice. What blocks the build vs. what just warns?

The guiding principle is **risk-tiered gating**: block on high-confidence, high-severity, low-false-positive signals; warn (or auto-file a ticket) on everything else so you do not destroy developer flow.

```
 commit ──▶ [pre-commit] gitleaks (block on secret)
   │
   ▼
 PR ──────▶ [fast gates, < 5 min, BLOCK]
   │          • SCA: new High/Critical CVE w/ fix available  → fail
   │          • Secret scan (full history)                   → fail
   │          • SAST (Semgrep, changed files): Critical rule → fail
   │
   ▼
 merge ───▶ [build + sign]
   │          • SBOM generation (CycloneDX)
   │          • container image scan (Trivy): block Critical w/ fix
   │          • cosign sign image + attest SBOM
   │
   ▼
 staging ─▶ [slow gates, WARN/async]
   │          • DAST (OWASP ZAP baseline) → ticket, not block
   │          • full SAST deep scan → dashboard
   │
   ▼
 prod ────▶ [admission control] OPA/Kyverno verifies signature + policy
```

The rationale for tiering: PR gates must be fast and near-zero false positive or developers route around them. DAST is slow and needs a deployed environment, so it runs post-merge against staging and files tickets rather than blocking the merge it cannot keep pace with. The hard *blocking* gates are the supply-chain ones (signature verification at admission) because they are deterministic and catch the most damaging class of attack.

### Q7. [Theory] Compare SAST and DAST in depth — strengths, weaknesses, and why you need both.

SAST reads code (white-box) and excels at finding issues with a precise location: hardcoded credentials, SQL string concatenation, weak crypto, insecure deserialization patterns. Its weaknesses are **false positives** (it cannot always tell if tainted data is sanitized downstream) and an inability to find runtime/config issues like a misconfigured TLS cipher or a broken auth flow. DAST attacks the running app (black-box) and finds *exploitable* issues with low false positives — it sees what an attacker sees — but it runs late, requires a deployed environment, gives no source location (so triage is slow), and has poor coverage of code paths it never exercises. They are complementary along the "false positive vs. false negative" and "early vs. late" axes:

```
            Coverage     False+    Location   Stage      Finds config/auth bugs?
SAST        broad code   high      precise    early      no
DAST        exercised    low       none       late       yes
```

You need both because each finds what the other misses; IAST is a hybrid that narrows the gap by instrumenting the running app to get both runtime confirmation *and* code location. The mature answer is layered coverage, not picking a winner.

### Q8. [Practical] Walk through container image scanning with Trivy in a pipeline. What do you scan and what do you do with results?

Trivy scans OS packages, language dependencies, IaC misconfigurations, and secrets in an image. The key practice is scanning at **two points**: at build (so you fail fast on a bad base image) and **continuously in the registry** (because a CVE published *tomorrow* affects an image you built *today*).

```bash
# Build-time gate: fail only on fixable Critical/High to avoid noise from
# unfixable base-OS CVEs you cannot act on.
trivy image --severity HIGH,CRITICAL --ignore-unfixed \
  --exit-code 1 --format sarif -o trivy.sarif myorg/payments:1.4.2
```

Production decisions: (1) Use **minimal/distroless base images** — most image CVEs come from OS packages you do not even use, and distroless can eliminate 80%+ of findings. (2) `--ignore-unfixed` so you do not block releases on CVEs with no available patch (track them, do not gate on them). (3) Output **SARIF** so findings surface natively in GitHub/GitLab security tabs rather than buried in logs. (4) Use a `.trivyignore` with **expiry dates and justifications** for accepted risks, reviewed periodically — never an open-ended ignore. (5) Generate an SBOM (`trivy image --format cyclonedx`) so the same image can be re-scanned later without rebuilding.

### Q9. [Coding] Write a CI gate that parses an OWASP Dependency-Check / SCA JSON report and fails only on High/Critical vulnerabilities that have a known fix.

**Problem:** SCA tools emit a JSON report listing dependencies and their vulnerabilities (severity + whether a fixed version exists). We want a gate that exits non-zero only when there is at least one HIGH or CRITICAL vulnerability *with a fix available*, so we do not block on unfixable noise. Print a readable summary either way.

**Approach 1 — brute force (nested loops):** iterate every dependency × every vuln, collect the blockers.

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

public class ScaGate {
    static final Set<String> BLOCKING = Set.of("HIGH", "CRITICAL");

    public static void main(String[] args) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of(args[0])));
        List<String> blockers = new ArrayList<>();
        int total = 0;

        for (JsonNode dep : root.path("dependencies")) {
            String name = dep.path("fileName").asText("unknown");
            for (JsonNode v : dep.path("vulnerabilities")) {
                total++;
                String sev = v.path("severity").asText("").toUpperCase();
                boolean fixable = hasFix(v);          // see helper below
                if (BLOCKING.contains(sev) && fixable) {
                    blockers.add(name + " :: " + v.path("name").asText() + " [" + sev + "]");
                }
            }
        }

        System.out.printf("Scanned vulns: %d | Blockers (fixable High/Critical): %d%n",
                total, blockers.size());
        blockers.forEach(b -> System.out.println("  BLOCK -> " + b));
        System.exit(blockers.isEmpty() ? 0 : 1);     // exit code drives the CI gate
    }

    /** A fix exists if any "fixed in"/upgrade target is reported. */
    private static boolean hasFix(JsonNode vuln) {
        for (JsonNode sw : vuln.path("vulnerableSoftware")) {
            if (sw.path("software").path("versionEndExcluding").isTextual()) return true;
        }
        return vuln.path("fixedVersions").isArray() && vuln.path("fixedVersions").size() > 0;
    }
}
```

- **Time:** O(D × V) where D = dependencies, V = avg vulns per dependency — unavoidable, you must inspect each finding.
- **Space:** O(B) for the blocker list (B ≤ total findings).
- **Edge cases:** empty report (`dependencies` absent) → 0 blockers, exit 0; missing/unknown severity → treated as non-blocking but still counted; malformed JSON → Jackson throws, the gate fails loud (good — a broken scan must not silently pass). In a real pipeline, also support a suppression file with **expiring** exceptions so you can accept a specific CVE temporarily without weakening the global rule.

### Q10. [Coding] Implement a minimal secret detector that flags high-entropy strings and common key patterns in a diff.

**Problem:** Catch obvious leaked secrets (AWS keys, generic high-entropy tokens) in text before commit. Real tools (gitleaks) are far richer, but the entropy + regex core is the same idea and a common whiteboard question.

```java
import java.util.*;
import java.util.regex.*;

public class SecretScanner {
    // Known-format patterns: AWS access key, generic 32+ hex/base64 token.
    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("AKIA[0-9A-Z]{16}"),                 // AWS access key id
        Pattern.compile("(?i)(secret|token|passwd|password)\\s*[:=]\\s*\\S{8,}")
    );

    /** Shannon entropy in bits/char — high entropy ~ random ~ likely a key. */
    static double entropy(String s) {
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : s.toCharArray()) freq.merge(c, 1, Integer::sum);
        double h = 0;
        for (int count : freq.values()) {
            double p = (double) count / s.length();
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    static boolean looksSecret(String token) {
        for (Pattern p : PATTERNS) if (p.matcher(token).find()) return true;
        // Long, mixed-charset, high-entropy strings are suspicious.
        return token.length() >= 20 && entropy(token) > 4.0
               && token.matches(".*[A-Za-z].*") && token.matches(".*\\d.*");
    }

    public static void main(String[] args) {
        String[] lines = {
            "String region = \"us-east-1\";",
            "aws_key = AKIAIOSFODNN7EXAMPLE",
            "password: hunter2supersecretvalue99",
            "int retryCount = 5;"
        };
        boolean found = false;
        for (int i = 0; i < lines.length; i++) {
            for (String tok : lines[i].split("[\\s\"']+")) {
                if (looksSecret(tok)) {
                    System.out.printf("LEAK line %d: %s%n", i + 1, redact(tok));
                    found = true;
                }
            }
        }
        System.exit(found ? 1 : 0);
    }

    static String redact(String s) {
        return s.length() <= 6 ? "******" : s.substring(0, 3) + "***" + s.substring(s.length() - 2);
    }
}
```

- **Time:** O(N × L) over N tokens of length L (entropy is O(L) per token). **Space:** O(unique chars) per token, effectively O(1).
- **Why entropy + patterns:** patterns catch known formats with near-zero false positives; entropy catches *unknown* random-looking secrets. Used alone, entropy floods you with false positives (UUIDs, hashes, minified JS), so production tools add allow-lists and contextual rules.
- **Edge cases:** empty string (guard the divide); base64 of an image (high entropy → false positive, hence path/extension allow-lists); a real secret split across lines (this naive scanner misses it — gitleaks reads the raw blob).
- **Security note:** never *print* the matched secret — always redact, or your scanner's own logs become the new leak.

### Q11. [Theory] What is policy as code, and how do OPA/Rego and Conftest fit a DevSecOps pipeline?

Policy as code expresses governance and security rules as version-controlled, testable code instead of wiki pages and human review. **Open Policy Agent (OPA)** is a general-purpose policy engine using the **Rego** language; it takes structured input (JSON/YAML — a Kubernetes manifest, a Terraform plan, an API request) and returns allow/deny decisions plus violation messages. **Conftest** wraps OPA to test config files (Dockerfiles, K8s YAML, Terraform) in CI. The win is consistency and auditability: the same `deny` rule that fails a PR ("no privileged containers") is the same rule enforced at the Kubernetes admission controller (OPA Gatekeeper / Kyverno) at runtime, so policy cannot be bypassed by deploying outside the pipeline.

```rego
# policy/deny_privileged.rego — fail any container running privileged
package main
deny[msg] {
    c := input.spec.template.spec.containers[_]
    c.securityContext.privileged == true
    msg := sprintf("container %q must not run privileged", [c.name])
}
```

```bash
conftest test deployment.yaml --policy policy/   # exits non-zero on any deny
```

The trade-off is that Rego has a learning curve and policies need their own tests (OPA supports unit tests) — an untested policy can either silently pass everything or block all deploys.

### Q12. [Practical] Your SAST tool reports 400 findings and developers are ignoring all of them. How do you fix the process?

This is the single most common DevSecOps failure: a noisy tool destroys trust and gets disabled. My approach: **first triage to establish a true baseline**. (1) Suppress/baseline all *existing* findings so the tool only fails the build on *newly introduced* issues — "stop the bleeding," then burn down debt separately. (2) Tune rules to the stack: disable rules irrelevant to your language/framework and drop confidence-low rules from the blocking set. (3) Rank by **severity × reachability × exploitability** and gate only on the top tier; route the rest to a dashboard/backlog. (4) Make findings actionable — every blocking finding must come with a code location, an explanation, and a fix example, ideally as an inline PR comment, not a separate portal nobody logs into. (5) Measure **false-positive rate as a KPI** and treat a high FP rate as a bug in the security process. The cultural point: a security gate's job is to be trusted, and trust is built by being *right*, fast, and few. A tool firing 400 times is providing zero security because the signal is unread.

### Q13. [Theory] What is the difference between Dependabot and Snyk, and what does "transitive dependency" mean for SCA?

**Dependabot** (GitHub-native, free) watches your manifests and opens PRs to bump dependencies with known vulnerabilities or new versions; it is great for *keeping current* and automating the upgrade PR. **Snyk** is a commercial platform with a deeper vulnerability database, **reachability analysis** (does your code actually call the vulnerable function?), license compliance, container/IaC scanning, and fix-PR generation with broader ecosystem support. A **transitive (indirect) dependency** is one you did not declare but is pulled in by something you did declare — e.g., your app depends on a web framework that depends on a JSON parser that has the CVE. This matters enormously because the majority of vulnerable code in a typical Java app lives in *transitive* dependencies the developer never chose and may not know exists. Good SCA resolves the full dependency *graph* (not just direct `pom.xml` entries) and tells you the **fix path** — sometimes you must bump a direct dependency to a version that itself pulls a patched transitive one, since you cannot always override the transitive version directly.

### Q14. [Practical] How do you implement least-privilege for CI/CD pipelines, and why is the CI system a top attack target?

CI/CD is a high-value target because it has broad write access (to prod, to package registries, to cloud accounts) and runs untrusted code (every PR). Least-privilege measures: (1) **No long-lived cloud credentials** — use **OIDC federation** so the pipeline exchanges a short-lived, workload-scoped token with AWS/GCP/Azure per run; nothing static to steal. (2) **Scope tokens narrowly** — a build job needs read on the repo and write on *one* artifact path, not org-admin. (3) **Separate trust zones**: builds triggered by untrusted forks/PRs must not have access to production secrets (use `pull_request` not `pull_request_target` carelessly in GitHub Actions — the latter runs with write tokens and is a classic exploit vector). (4) **Pin third-party actions to a commit SHA, not a tag** — a tag can be re-pointed to malicious code, a SHA cannot. (5) **Ephemeral runners** so a compromised job cannot persist. (6) **Protected branches + required reviews on the pipeline definition itself**, because whoever can edit the pipeline can exfiltrate every secret it touches. The mental model: assume any PR may be hostile and design so a malicious PR cannot reach production secrets.

---

## 🟠 Advanced (8–12 yrs)

### Q15. [Theory] Explain the software supply chain attack surface using SolarWinds and Log4Shell as case studies. What changed in industry practice?

These two 2020–2021 incidents reshaped supply-chain security. **SolarWinds (2020)** was a *build-system* compromise: attackers (SUNBURST) injected a backdoor into SolarWinds Orion **during the build**, so customers received a validly-signed, trusted update containing malware. The lesson: signing the artifact is worthless if the *build process producing it* is compromised — you must guarantee build *integrity and provenance*, not just authenticity of the final blob. **Log4Shell (CVE-2021-44228, Dec 2021)** was a critical RCE in Log4j, an open-source logging library buried as a transitive dependency in millions of apps; the global scramble exposed that most organizations could not even answer "do we use Log4j, and where?" The lessons jointly drove: (1) **SBOMs** so you can answer the "where" question in minutes; (2) **SLSA** to attest *how* an artifact was built; (3) **reproducible/hermetic builds** to detect tampering; (4) **artifact signing with provenance** (sigstore/cosign). US Executive Order 14028 and the resulting NIST SSDF guidance codified much of this for federal suppliers. The mindset shift: trust must be *verifiable and end-to-end*, from source commit to deployed artifact, not assumed.

### Q16. [Theory] What is SLSA, and what do its levels protect against?

**SLSA** (Supply-chain Levels for Software Artifacts, pronounced "salsa") is a framework for incrementally hardening the build pipeline, focused on **provenance** — verifiable metadata about *how, where, and from what source* an artifact was built. The current v1.0 build track levels:

```
SLSA Build L1: provenance exists (you can show how it was built)
SLSA Build L2: provenance is signed + build runs on a hosted build service
SLSA Build L3: build runs in a hardened, isolated, non-forgeable environment
               (build secrets isolated; provenance is tamper-resistant)
```

Each level defends against a stronger adversary: L1 against accidental/undocumented builds; L2 against tampered provenance and "built on my laptop"; L3 against a build that another tenant or a malicious build step can corrupt or whose provenance can be forged — directly the SolarWinds threat. Crucially SLSA is about the *process*, not the code's vulnerabilities: a SLSA L3 artifact can still contain a Log4Shell-class bug. It complements (not replaces) SCA/SAST. In practice you reach L2–L3 by using a hosted, isolated builder (GitHub Actions with hardened runners, or `slsa-github-generator`) that emits signed provenance.

### Q17. [Practical] How would you implement keyless artifact signing and verified deployment with sigstore/cosign?

The goal: cryptographically prove an artifact came from your pipeline and was not tampered with, then *enforce* that at deploy. **Sigstore/cosign keyless signing** is elegant because it eliminates long-lived signing keys: the pipeline authenticates via OIDC to **Fulcio**, which issues a short-lived (≈10-minute) certificate bound to the workload identity; the signature and certificate are recorded in **Rekor**, a public tamper-evident transparency log.

```bash
# In CI (OIDC identity), sign the image keylessly:
cosign sign --yes ghcr.io/acme/payments@sha256:abc123...

# Attach the SBOM as a signed attestation:
cosign attest --yes --predicate sbom.cdx.json \
  --type cyclonedx ghcr.io/acme/payments@sha256:abc123...
```

```yaml
# Kyverno admission policy: only run images signed by OUR pipeline identity.
apiVersion: kyverno.io/v1
kind: ClusterPolicy
spec:
  rules:
  - name: verify-cosign
    verifyImages:
    - imageReferences: ["ghcr.io/acme/*"]
      attestors:
      - keyless:
          subject: "https://github.com/acme/payments/.github/workflows/release.yml@refs/tags/*"
          issuer: "https://token.actions.githubusercontent.com"
```

The cluster admission controller now **rejects any image** not signed by the exact pipeline identity, closing the gap where someone could push a hand-built image straight to the registry. The trade-off: keyless relies on a public transparency log (fine for the signature metadata, but you must be comfortable that the *existence* of the artifact is public) and on the Fulcio/Rekor services' availability; some regulated shops run their own sigstore instance.

### Q18. [Coding] Implement a verifier that confirms an artifact's SHA-256 digest matches its signed provenance/SBOM before deployment.

**Problem:** Before deploying, confirm the artifact you are about to run is byte-for-byte the one your pipeline attested. A mismatch means tampering or a swapped artifact — block the deploy. This is the conceptual core of supply-chain verification.

```java
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public class ProvenanceVerifier {

    /** Compute SHA-256 of the artifact on disk. */
    static String sha256(Path artifact) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(artifact)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Constant-time compare so we don't leak digest bytes via timing. */
    static boolean digestsMatch(String a, String b) {
        return MessageDigest.isEqual(
            a.toLowerCase().getBytes(), b.toLowerCase().getBytes());
    }

    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(args[0]);
        String attested = args[1].replace("sha256:", "");   // from the signed provenance
        String actual = sha256(artifact);

        System.out.println("attested: " + attested);
        System.out.println("actual:   " + actual);
        if (!digestsMatch(actual, attested)) {
            System.err.println("FAIL: digest mismatch — artifact tampered or swapped. Blocking deploy.");
            System.exit(1);
        }
        System.out.println("OK: artifact integrity verified.");
    }
}
```

- **Time:** O(F) over the file size F (single streaming pass). **Space:** O(1) — fixed 8 KB buffer, never loads the whole artifact into memory (matters for multi-GB images).
- **Why `MessageDigest.isEqual`:** it is a **constant-time** comparison in modern JDKs; a naive `String.equals` can short-circuit and theoretically leak information via timing. Small but exactly the kind of detail a security review expects.
- **Edge cases:** missing file (IOException → fail closed); case differences in hex (normalized); the bigger real-world gap — this verifies *integrity* (not tampered) but not *authenticity* (signed by us); production code must also verify the signature over the provenance (cosign/sigstore) before trusting the attested digest. Verifying a digest from an *unsigned* source proves nothing.

### Q19. [Theory] How does threat modeling (e.g., STRIDE) fit into a DevSecOps process, and how do you keep it from becoming a one-time document?

Threat modeling is structured reasoning about *what can go wrong* in a design before code exists — the ultimate shift-left, because it prevents whole classes of vulnerability rather than catching instances. **STRIDE** is a mnemonic for threat categories mapped to security properties:

```
S poofing            → Authentication
T ampering           → Integrity
R epudiation         → Non-repudiation (audit/logging)
I nformation disclos → Confidentiality
D enial of service   → Availability
E levation of priv   → Authorization
```

You draw a data-flow diagram, mark **trust boundaries** (where data crosses privilege levels — the highest-risk spots), and walk each element through STRIDE. The classic failure is the "threat model as a 60-page Word doc done once at project start and never opened again." To keep it alive in DevSecOps: do **incremental, lightweight threat modeling per feature/epic** (15-minute "what could go wrong here?" sessions at design review), keep it as code/diagrams next to the repo (e.g., `threat-model.md`, OWASP Threat Dragon, pytm), and translate the top threats into concrete, *testable* requirements — a tampering threat becomes an integrity test, a spoofing threat becomes an authN test. The model should drive your SAST rules and abuse-case tests, not sit in a drawer.

### Q20. [Practical] How do you map the OWASP Top 10 onto automated pipeline checks? Give concrete examples.

The OWASP Top 10 is a risk-awareness list, not a checklist, but most categories have a pipeline analogue:

```
A01 Broken Access Control       → DAST/IAST + custom authZ integration tests
A02 Cryptographic Failures      → SAST (weak ciphers, hardcoded keys) + secret scan
A03 Injection                   → SAST (taint analysis) + DAST (SQLi/XSS payloads)
A04 Insecure Design             → threat modeling (not automatable — design review)
A05 Security Misconfiguration   → IaC scan (Trivy/Checkov), Conftest/OPA on manifests
A06 Vulnerable Components       → SCA (Snyk/Dependabot/Dependency-Check) ← biggest ROI
A07 Auth Failures               → DAST auth tests + dependency checks on auth libs
A08 Software/Data Integrity     → supply chain: SBOM, SLSA, cosign signature verify
A09 Logging/Monitoring Failures → policy checks that logging exists; runtime alerting
A10 SSRF                        → SAST patterns + DAST + egress network policy
```

The honest production answer: tools cover A02/A03/A05/A06/A08/A10 well; **A01 (broken access control) and A04 (insecure design) are largely *not* automatable** — they require human design review, threat modeling, and authZ-specific tests, which is exactly why they sit at the top of the list and cause the most breaches. A04 was *added* in the 2021 revision precisely to emphasize that you cannot scan your way out of a bad design. So the pipeline catches the mechanical issues cheaply, freeing human review for the design-level risks machines miss.

### Q21. [Coding] Implement a parameterized-query helper and a unit test that proves it defeats SQL injection (A03).

**Problem:** Show, in code, the difference between an injectable query and a safe one, and write a test that an attacker's payload cannot break out. This is the bread-and-butter of "secure coding" interviews.

```java
import java.sql.*;
import java.util.*;

public class UserDao {
    private final Connection conn;
    public UserDao(Connection conn) { this.conn = conn; }

    // ❌ VULNERABLE — string concatenation lets input become SQL.
    //    input "' OR '1'='1" turns the WHERE clause always-true.
    public boolean loginInsecure(String user, String pass) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE name='" + user +
                     "' AND pass='" + pass + "'";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }

    // ✅ SAFE — PreparedStatement sends SQL and data on separate channels;
    //    the driver binds parameters so input is never parsed as SQL.
    public boolean loginSecure(String user, String pass) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE name = ? AND pass = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user);   // ' OR '1'='1 is treated as a literal value
            ps.setString(2, pass);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
}
```

```java
// JUnit 5 — the injection payload must NOT log in.
@Test
void injectionPayloadFailsOnSecurePath() throws Exception {
    UserDao dao = new UserDao(testConnectionWithUser("alice", "secret"));
    String payload = "' OR '1'='1";
    assertFalse(dao.loginSecure(payload, payload),
        "parameterized query must treat payload as a literal, not SQL");
}
```

- **Time/Space:** dominated by the DB round-trip, O(1) client-side; the security property is what matters, not Big-O.
- **Why it works:** a `PreparedStatement` separates the query *structure* from the *data*; the parameter value is bound by the driver and never reparsed as SQL syntax, so `' OR '1'='1` becomes a search for a user literally named `' OR '1'='1`. This also enables query-plan caching, so it is faster too.
- **Edge cases / deeper notes:** parameterization does **not** protect identifiers (table/column names cannot be bound — allow-list those), and `LIKE` patterns still need escaping of `%`/`_`. ORMs (JPA/Hibernate) parameterize by default, but `@Query` with string concatenation or native queries built by hand reintroduce the hole — which is exactly what SAST taint analysis is tuned to flag.

### Q22. [Practical] A Critical CVE drops in a transitive dependency at 5pm Friday (a Log4Shell-style event). Walk me through your response using your DevSecOps tooling.

This is an incident-response runbook that leans on the investments above. (1) **Scope it — minutes, not days:** query stored **SBOMs** across all services to instantly list which artifacts contain the affected library and version. This is the entire payoff of generating SBOMs. (2) **Assess exploitability:** is the vulnerable code path reachable and is the service internet-exposed? Triage internet-facing, reachable services first. (3) **Mitigate immediately** where a code fix is slow — e.g., for Log4Shell the stopgap was a JVM flag / config to disable JNDI lookups, plus WAF rules to block the exploit string — buying time without a redeploy. (4) **Remediate:** bump the dependency (or the direct dependency that pulls the fixed transitive version), let SCA confirm the new version clears, rebuild, **re-sign with cosign**, and re-scan the image with Trivy. (5) **Deploy with the supply-chain gates intact** — do not panic-disable signature verification; the rushed-deploy moment is exactly when a malicious artifact slips in. (6) **Post-incident:** add a regression check, tighten egress network policy (Log4Shell needed outbound LDAP — restricting egress would have blunted it), and feed the gap back into threat models. The whole drill is faster and calmer *because* SBOMs, SCA gating, and signing were already in place; organizations without them spent the weekend grepping.

---

## 🔴 Expert (15+ yrs)

### Q23. [Behavioral] As a staff/principal engineer, how do you roll out DevSecOps across many teams without becoming the bottleneck or the "department of no"?

The anti-pattern is a central security team that gates every release and gets resented and routed around. My model is **security as a platform / paved road**: build the secure path into the golden pipeline and templates so the *easy* way is the *secure* way, and teams opt into security by using the standard tooling rather than doing extra work. Concretely: (1) ship reusable pipeline templates with scanning, SBOM, and signing already wired, so a new service is secure by default on day one; (2) embed **security champions** within product teams — engineers who get deeper training and act as the local first responder, scaling the central team's reach without owning every decision; (3) publish clear, tiered policies (what blocks vs. warns) and *measure* outcomes — mean-time-to-remediate, % of services with SBOMs, false-positive rate — to drive the program with data, not edicts; (4) make exceptions a first-class, **time-boxed, auditable** workflow rather than an informal "just skip it." The behavioral key is reframing security from gatekeeper to enabler: the central team's job is to make secure delivery *frictionless*, and success is measured by adoption and reduced risk, not by how many releases you blocked.

### Q24. [Theory] How do you reconcile DevSecOps automation with regulatory frameworks like SOC 2, PCI-DSS, FedRAMP, or the EU CRA? How does "compliance as code" change audits?

Traditional compliance is evidence-gathering theatre: screenshots, spreadsheets, and a frantic scramble before the annual audit. The DevSecOps approach is **compliance as code / continuous compliance**: encode control requirements as automated, version-controlled policies (OPA/Rego, Kyverno, IaC scanners) that run continuously, and emit *machine-generated, immutable evidence* (signed pipeline logs, attestations, Rekor transparency entries) as a byproduct of normal operation. Examples: PCI-DSS "no default credentials" and "encryption in transit" become Conftest policies on infra; SOC 2 "changes are reviewed" is satisfied by enforced branch protection + signed commits whose logs *are* the audit trail; FedRAMP leans on NIST SSDF practices that map directly to SBOM/SLSA. The shift is from *point-in-time* attestation to *continuous* enforcement, and from manually produced evidence to evidence that exists because the control physically cannot be bypassed. The EU **Cyber Resilience Act** (phasing in through 2027) pushes this further — mandating vulnerability handling, SBOMs, and security-by-design for products with digital elements sold in the EU, with real penalties — which is accelerating supply-chain tooling adoption industry-wide. The trade-off: encoding controls is upfront work and requires auditors who accept automated evidence, but it converts audits from a quarterly fire drill into a query.

### Q25. [Practical] Design end-to-end supply-chain security for a large org publishing both internal services and public OSS libraries. What are the layered controls?

I would architect defense across the whole chain, source-to-runtime, because attackers will hit the weakest link:

```
 SOURCE        BUILD                ARTIFACT            DEPLOY            RUNTIME
 ─────         ─────                ────────            ──────            ───────
 signed        hermetic/isolated    SBOM (CycloneDX)    admission ctrl    eBPF/runtime
 commits       builder (SLSA L3)    cosign signature    verifies sig +    detection,
 branch        pinned deps (SHA)    provenance attest   provenance        egress policy,
 protection    no untrusted PR      stored in OCI       (Kyverno/OPA)     drift detection
 2FA + SSO     access to secrets    registry            policy            anomaly alerts
```

Layer-by-layer rationale: **source** — signed commits + protected branches + 2FA stop the "stolen developer credential" entry point. **Build** — a hermetic, isolated builder (SLSA L3) with dependency SHA-pinning and *no* prod-secret access for fork PRs prevents the SolarWinds build-injection class. **Artifact** — every build emits a CycloneDX SBOM and a keyless cosign signature + provenance attestation, stored in the OCI registry. **Deploy** — admission control (Kyverno) *cryptographically verifies* signature and provenance, so nothing unsigned or built outside the trusted pipeline ever runs. **Runtime** — eBPF-based detection (Falco/Tetragon), egress restrictions, and drift detection catch what slipped through, since prevention is never perfect. For the **public OSS** side, additionally: publish provenance and SBOMs *with* releases, enable package-registry 2FA and trusted publishing (OIDC, no long-lived npm/Maven tokens), and run continuous typosquat/dependency-confusion monitoring — because as a *publisher* you are now part of *others'* supply chains and your compromise becomes their incident. The org-level discipline: a single signed-provenance standard enforced at admission, so trust is verifiable rather than assumed at every hop.

### Q26. [Theory] What is dependency confusion, and how is it different from typosquatting? How do you defend at scale?

Both are supply-chain attacks via the package ecosystem, but the mechanism differs. **Typosquatting** publishes a malicious package with a name *close to* a popular one (`reqeusts` vs `requests`, `jackson-databnd`) hoping a developer typos the dependency. **Dependency confusion** (Alex Birsan's 2021 research, which breached Apple, Microsoft, and others) exploits **resolver precedence**: if an org uses an *internal* package name like `acme-internal-utils` that exists only in a private registry, an attacker publishes a package with that *exact name* and a *higher version number* on the *public* registry; a misconfigured build tool then pulls the attacker's public package because it prefers the higher version or searches public first. Defenses at scale: (1) **scoped/namespaced packages** (`@acme/utils`) so internal names cannot be claimed publicly; (2) configure the build to resolve internal names *only* from the internal registry (Maven `mirrorOf`, repository routing) and never fall through to public for those names; (3) **pin and verify** dependencies (lockfiles + checksum/signature verification); (4) **defensively register** your internal names on public registries as empty placeholders; (5) use a single trusted **proxy/virtual repository** (Artifactory/Nexus) as the only egress for dependencies, with policy controlling what it will and will not resolve. This is a configuration-precedence problem first, a scanning problem second.

### Q27. [Practical] How do you secure the DevSecOps tooling itself, and how would you red-team your own pipeline?

The meta-risk: the security tools and the pipeline have privileged access, so they are prime targets — "who watches the watchmen." Hardening: (1) treat the **pipeline definition as production code** — protected branches, mandatory review, signed commits, because edit access to the pipeline equals access to every secret it holds; (2) **least-privilege + ephemeral** runners and OIDC short-lived tokens (no static cloud keys to steal); (3) **pin all third-party actions/plugins to commit SHAs** and run an SCA on your *tooling's* own dependencies (a compromised SAST plugin is a backdoor with code-read access to everything); (4) isolate the trust zone so untrusted fork PRs cannot reach prod secrets; (5) immutable, centralized **audit logs** of all pipeline and policy changes. To **red-team it**, I would run adversarial exercises: attempt to (a) merge a PR that exfiltrates `secrets.*` via a benign-looking build step, (b) push a hand-built unsigned image straight to the registry and see if admission control blocks it, (c) introduce a malicious transitive dependency and verify SCA gates catch it, (d) re-point a dependency action tag to confirm SHA-pinning protects us, (e) commit a fake secret and verify both pre-commit and server-side scanning fire *and* that rotation is triggered. Each failed attempt validates a control; each success is a finding with a fix. The principle: a security pipeline you have not tried to break is a pipeline you only *hope* is secure.

### Q28. [Behavioral] Tell me about a time you had to push back on shipping when security and delivery pressure collided. How do you make that call?

The framing I use: I do not say a flat "no" — I make the **risk explicit, quantified, and owned**. When a Critical, internet-facing, exploitable vulnerability surfaced near a committed launch date, I laid out for leadership the concrete blast radius (what data was exposed, regulatory exposure, likelihood given it was already being scanned by attackers in the wild) against the cost of a short slip, and proposed options: a quick *mitigation* (WAF rule + config flag) that let us ship on time with the risk reduced to acceptable while the proper fix landed days later. The key behaviors: (1) bring **data and options**, not just objections — leaders can make a good risk decision only if I quantify it honestly; (2) distinguish **must-block** (critical, exploitable, exposed) from **accept-with-plan** (low severity, not reachable) so I spend my credibility only where it matters; (3) make sure the accepted risk is **explicitly signed off by the accountable owner** and logged, never silently absorbed; (4) follow up so "we'll fix it next sprint" is a real ticket with a date, not a permanent open wound. The judgment that separates senior from staff: security's goal is to enable the business to take *informed* risk, not to achieve zero risk by blocking everything. Being the person who can say "here is the risk, here are the options, here is my recommendation" — rather than just "no" — is what earns a seat at the table for the next decision.

---

## ✅ Key Takeaways

- **Shift left, but don't dump left:** earlier feedback is cheaper, but only if checks are fast, accurate, and paired with remediation guidance — a noisy gate provides zero security because nobody reads it.
- **Use the full toolchain, layered:** SAST (early, code, false-positive-prone), DAST (late, exploitable, low-false-positive), IAST (hybrid), and SCA (dependencies — usually the highest ROI since most code is third-party).
- **Tier your gates by risk:** block only on high-confidence, high-severity, deterministic signals (secrets, fixable Critical CVEs, signature failures); warn/ticket on the rest.
- **Secrets: prevent, detect, and rotate** — a leaked secret is compromised the instant it lands; rewriting history is not remediation.
- **Supply chain is the frontier:** SBOMs answer "where am I affected?" in minutes (Log4Shell lesson); SLSA + sigstore/cosign + admission control guarantee *how* an artifact was built and that only your pipeline's artifacts run (SolarWinds lesson).
- **Policy and compliance as code** turn governance into version-controlled, testable, continuously-enforced rules that generate audit evidence as a byproduct.
- **Least-privilege CI** with OIDC short-lived tokens, SHA-pinned actions, and isolated trust zones — the pipeline is a top-tier attack target.
- **Some risks aren't automatable:** broken access control (A01) and insecure design (A04) need threat modeling and human review — that's why they top the OWASP list.

## ⚠️ Common Pitfalls

- Treating DevSecOps as buying tools rather than changing process and ownership; ten scanners with no triage strategy just generates 400 ignored findings.
- Blocking builds on **unfixable** CVEs (no patch exists) — you train developers to disable the gate. Gate on *fixable* High/Critical; track the rest.
- Scanning only direct dependencies and ignoring the **transitive** graph, where most vulnerable code actually lives.
- Scanning an image **once at build** and never re-scanning in the registry — tomorrow's CVE affects today's image.
- Confusing artifact **signing** (authenticity) with build **integrity/provenance** — SolarWinds shipped a validly-signed backdoor.
- Using `pull_request_target` / privileged tokens on untrusted fork PRs, or pinning third-party CI actions to **tags** instead of commit SHAs.
- Letting threat modeling be a one-time document; it must be incremental, per-feature, and drive testable requirements.
- Panic-disabling supply-chain verification during an incident — the rushed deploy is exactly when a malicious artifact slips in.
- Forgetting to **rotate** a leaked credential because "we removed it from Git."

## 📚 Further Reading

- *Securing DevOps* — Julien Vehent (Manning) — practical pipeline security patterns.
- *Building Secure and Reliable Systems* — Google (free O'Reilly book) — design-level security and reliability.
- **OWASP** resources: Top 10, SAMM (Software Assurance Maturity Model), ASVS, and the OWASP Dependency-Check / CycloneDX projects — [owasp.org](https://owasp.org).
- **SLSA framework** — [slsa.dev](https://slsa.dev) — supply-chain levels, threats, and provenance specification.
- **Sigstore** docs (cosign/Fulcio/Rekor) — [sigstore.dev](https://www.sigstore.dev) — keyless signing and transparency logs.
- **NIST SSDF (SP 800-218)** and **CISA Secure-by-Design** guidance — the regulatory backbone for US supply-chain practice; plus the EU Cyber Resilience Act for EU market obligations.
