# Compliance & Regulatory (GDPR, HIPAA, PCI-DSS, SOC 2)

A practical, interview-focused guide to the regulatory frameworks that shape how software engineers design data systems: GDPR, HIPAA, PCI-DSS, SOC 2, and ISO 27001. The emphasis is on how compliance constraints translate into concrete architecture, code, and operational decisions — not just memorizing acronyms.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is GDPR and what core rights does it grant to data subjects?

The **General Data Protection Regulation (GDPR)** is an EU regulation (in force since May 2018) governing the processing of personal data of people in the EU/EEA. It applies extraterritorially: any company anywhere that targets or monitors EU residents must comply, which is why it affects US-based engineers. The core **data subject rights** are: the right to **access** (a copy of their data), **rectification** (correct inaccuracies), **erasure** ("right to be forgotten"), **restriction** of processing, **data portability** (receive data in a machine-readable format), **objection** to processing, and rights regarding **automated decision-making/profiling**. The "why" matters in design: each right maps to a system capability — e.g., portability implies you can export a user's data as structured JSON/CSV, and erasure implies you can actually find and delete every copy. Penalties are severe (up to €20M or 4% of global annual turnover), so these are not optional features but architectural requirements.

### Q2. [Theory] Distinguish PII, personal data, and sensitive/special-category data.

**PII (Personally Identifiable Information)** is the US-centric term for data that identifies an individual (name, SSN, email). **Personal data** is GDPR's broader concept: *any* information relating to an identified or identifiable natural person — including online identifiers like IP addresses, cookie IDs, and device fingerprints. **Special-category (sensitive) data** under GDPR gets extra protection: racial/ethnic origin, political opinions, religious beliefs, trade-union membership, genetic data, biometric data used for ID, health data, and sex life/orientation. The distinction drives controls: special-category data generally requires explicit consent or another narrow lawful basis, stronger encryption, and tighter access logging. In practice, you classify fields at the schema level so that the same column never silently mixes sensitivities.

### Q3. [Theory] What is PHI under HIPAA, and who must comply?

**PHI (Protected Health Information)** is any individually identifiable health information held or transmitted by a covered entity or business associate — diagnoses, treatment, payment for care, plus 18 specific identifiers (name, dates, medical record numbers, biometric IDs, etc.). **HIPAA** (US, Health Insurance Portability and Accountability Act) applies to **covered entities** (health plans, providers, clearinghouses) and their **business associates** (vendors handling PHI on their behalf, e.g., a SaaS that stores patient records). If your startup processes PHI for a hospital, you are a business associate and must sign a **BAA (Business Associate Agreement)**. The HIPAA Security Rule requires administrative, physical, and technical **safeguards** (access control, audit controls, integrity, transmission security). Practically, "ePHI must be encrypted at rest and in transit, access must be role-based and logged" is the engineering summary.

### Q4. [Theory] What is "cardholder data" under PCI-DSS, and why is tokenization useful?

**PCI-DSS (Payment Card Industry Data Security Standard)** governs anyone who stores, processes, or transmits payment card data. **Cardholder data (CHD)** = PAN (Primary Account Number / the 16-digit card number), cardholder name, expiry, service code. **Sensitive Authentication Data (SAD)** = full magnetic stripe, CVV/CVC, and PIN — and you must **never** store SAD after authorization. **Tokenization** replaces the PAN with a non-sensitive surrogate (a token) so your systems handle tokens instead of real card numbers. This is powerful for **scope reduction**: if you never touch the raw PAN (e.g., you use Stripe/Braintree's hosted fields and only store their token), the bulk of your environment falls *out of PCI scope*, drastically cutting audit cost. The rule of thumb interviewers want: "the cheapest card data to secure is the card data you never store."

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Theory] What is SOC 2 and what are the Trust Services Criteria?

**SOC 2** is an attestation report (produced by a CPA firm under AICPA standards) describing how a service organization manages customer data against the **Trust Services Criteria (TSC)**: **Security** (the only mandatory one, also called the "Common Criteria"), **Availability**, **Processing Integrity**, **Confidentiality**, and **Privacy**. A **Type I** report assesses control *design* at a point in time; a **Type II** report assesses *operating effectiveness* over a period (typically 6–12 months) — buyers strongly prefer Type II. Unlike GDPR/HIPAA, SOC 2 is not a law; it's a trust signal demanded by enterprise customers during vendor security reviews. The engineering implication: SOC 2 forces you to *evidence* controls continuously — access reviews, change management, vulnerability scanning, incident response — so you build logging, ticketing, and IaC discipline that auditors can sample.

### Q6. [Theory] How does ISO 27001 relate to SOC 2, and when would you pursue each?

**ISO 27001** is an international standard for an **ISMS (Information Security Management System)** — a risk-based, process-oriented framework with a defined set of controls (Annex A). It is **certifiable** by an accredited body (pass/fail), whereas SOC 2 is an **attestation report** (a narrative + auditor opinion, not a pass/fail badge). ISO 27001 emphasizes the *management system* (risk assessment, Statement of Applicability, continual improvement / PDCA cycle); SOC 2 emphasizes *controls over a period* tied to the TSC. Choose **SOC 2** when selling to US enterprises (it's the de facto expectation there); choose **ISO 27001** when selling internationally, especially EU/APAC, where the certificate is more recognized. Many companies pursue both because the underlying controls overlap ~80%, and shared evidence (access control, encryption, vendor management) satisfies both.

### Q7. [Practical] A user invokes their GDPR "right to erasure." Walk through how you'd implement deletion across a real microservices system.

**Scenario:** A user submits a deletion request via a self-service portal in a system with 12 microservices, each with its own database, plus a data lake, search index, caches, backups, and a third-party email vendor.

**Approach:** Erasure is rarely a single `DELETE` — it's an orchestrated, auditable workflow.

```
[Deletion Request]
       |
       v
 +-----------------+      publishes "UserErasureRequested" event
 | Erasure         |------------------------------+
 | Orchestrator    |                              |
 +-----------------+                              v
       | tracks per-service status      +------------------+
       |                                | Event Bus (Kafka)|
       v                                +------------------+
 +-----------------+                       |    |    |    |
 | Audit Log       |                       v    v    v    v
 | (immutable)     |                   svc-A svc-B svc-C ... each ACKs
 +-----------------+                   (hard-delete or pseudonymize)
       |
       v
 Reconcile: all ACKed within SLA (30 days)? -> mark COMPLETE
```

**Key decisions / trade-offs:**
- **Hard delete vs. pseudonymize.** You can't always physically delete rows you have a legal obligation to keep (tax records, fraud investigation). For those, you **pseudonymize** — strip direct identifiers and keep only what's legally required. GDPR permits retention under specific lawful bases.
- **Backups.** You typically cannot rewrite backups on demand. Document a policy: backups age out within a defined window (e.g., 35 days) and any restore re-applies pending erasure requests (a "tombstone" / suppression list the restore process consults).
- **Search/cache/data lake.** These derived stores need explicit purge steps — a common audit finding is that data was deleted from the OLTP DB but lingered in Elasticsearch.
- **Third parties.** Propagate deletion to processors (email, analytics) via their APIs; track confirmation.
- **SLA:** GDPR requires response within **one month** (extendable to three for complex cases).

**What I'd do in production:** an idempotent, event-driven orchestrator with a per-service ledger, a global **suppression list** consulted on restore, and an immutable audit trail proving completion.

### Q8. [Coding] Implement a PAN tokenizer that masks card numbers for display and produces a reversible token. Discuss the security model.

**Problem:** Given a 16-digit PAN, produce (a) a masked version safe for UI/logs showing only the last 4 digits, and (b) a token suitable for storing in place of the PAN, where the real PAN can be retrieved only via a secured vault.

```java
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;

public class CardTokenizer {

    private final SecretKey key;            // pulled from a KMS / HSM, never hard-coded
    private final SecureRandom rng = new SecureRandom();
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    public CardTokenizer(SecretKey key) {
        this.key = key;
    }

    /** Safe-for-display masking: PCI allows showing first 6 (BIN) + last 4 max. */
    public static String mask(String pan) {
        if (pan == null || pan.length() < 4) {
            throw new IllegalArgumentException("invalid PAN");
        }
        String last4 = pan.substring(pan.length() - 4);
        return "**** **** **** " + last4;
    }

    /** Reversible token: AES-256-GCM ciphertext, base64-encoded with prepended IV. */
    public String tokenize(String pan) throws Exception {
        byte[] iv = new byte[GCM_IV_LEN];
        rng.nextBytes(iv);                                  // unique IV per encryption
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(pan.getBytes("UTF-8"));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    /** Detokenize only inside the secured vault boundary. */
    public String detokenize(String token) throws Exception {
        byte[] combined = Base64.getUrlDecoder().decode(token);
        byte[] iv = new byte[GCM_IV_LEN];
        byte[] ct = new byte[combined.length - GCM_IV_LEN];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
        System.arraycopy(combined, GCM_IV_LEN, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), "UTF-8");      // throws if tampered (auth tag)
    }
}
```

**Approaches & trade-offs:**
- **Encryption-based token (above):** stateless, but the token *is* derived from the PAN, so the token still carries the data — anyone with the key can reverse it. Use only when the key lives in an HSM and the token store is in PCI scope.
- **Vault/random-mapping token (preferred for scope reduction):** store a *random* token mapped to the PAN in an isolated token vault; the token has **no mathematical relationship** to the PAN, so systems holding only tokens are out of scope. This is what payment processors do.

**Time/Space:** `mask` is O(n) over PAN length, O(1) extra. `tokenize`/`detokenize` are O(n) for AES; space O(n) for the buffer.

**Edge cases:** null/short PAN (validated), IV reuse (must be unique per encryption — GCM is catastrophic if IVs repeat), key rotation (tag tokens with a key version), never log the PAN or token at the same site, and never store CVV at all.

### Q9. [Practical] How do you implement consent management that actually satisfies GDPR, and what's wrong with a "by using this site you agree" banner?

Under GDPR, consent must be **freely given, specific, informed, and unambiguous**, requiring a clear **affirmative action** (no pre-ticked boxes, no implied consent). A blanket "by using this site you agree" banner fails on multiple counts: it's not granular, not opt-in, and you can't withdraw it as easily as you gave it. A compliant design:

```
consent_record {
  user_id / pseudonymous_id
  purpose            // e.g. "analytics", "marketing_email", "personalization"
  status             // granted | denied | withdrawn
  version            // which privacy-policy/consent-text version
  source             // banner v3, settings page, API
  timestamp          // when, for proof
  expiry             // re-prompt cadence
}
```

Key engineering points: store consent **per purpose** (granular), keep it **versioned and timestamped** (you must *prove* consent later), make withdrawal **as easy as granting** (a toggle, not an email), and **gate processing on a consent check** — analytics/marketing code paths read the consent store before firing. For special-category data you need **explicit** consent. Consent is only one of six lawful bases (also: contract, legal obligation, vital interests, public task, legitimate interests) — for some processing, consent is the *wrong* basis (e.g., security logging is usually "legitimate interests," so you don't ask permission to keep audit logs).

### Q10. [Theory] What are the encryption requirements across these frameworks, and what's the difference between encryption at rest, in transit, and field-level?

None of the frameworks mandate a *specific* cipher, but the de-facto expectation in 2026 is **AES-256 at rest** and **TLS 1.2+ (prefer 1.3) in transit**. **At rest** = disk/volume/database encryption (e.g., transparent data encryption, KMS-managed envelope encryption) — protects against stolen disks/backups. **In transit** = TLS for every hop, including service-to-service inside the VPC (zero-trust). **Field-level (application-layer) encryption** = encrypting specific sensitive columns (SSN, PAN, PHI) *before* they hit the database, so a compromised DB admin or leaked dump still can't read them. The frameworks differ in emphasis: PCI-DSS is prescriptive about rendering PAN unreadable (encryption, truncation, tokenization, or hashing) and about **key management** (split knowledge, dual control, rotation); HIPAA treats encryption as an "addressable" (risk-justified, not strictly mandatory) safeguard but it's the standard "safe harbor" — encrypted breached data may not even be a reportable breach. The key insight: encryption shifts the security boundary to **key management**, so KMS/HSM design and rotation policy matter more than the cipher choice.

### Q11. [Practical] How do you design data classification and retention so it's enforceable rather than a wiki page nobody reads?

A retention policy in a Confluence doc is a *finding* in every audit. To make it enforceable, push classification into the data model and automate retention. Define tiers (e.g., **Public, Internal, Confidential, Restricted/Regulated**) and tag data at the **schema/column level** with metadata: classification, lawful basis, retention period, and residency. Then:

```
Table: orders
  email            CONFIDENTIAL  basis=contract     retain=7y  residency=EU
  card_token       RESTRICTED    basis=contract     retain=0   residency=EU
  marketing_optin  CONFIDENTIAL  basis=consent      retain=until_withdrawn
  server_log_ip    INTERNAL      basis=legit_int    retain=90d residency=any
```

Enforcement mechanisms: TTL indexes / partition-drop jobs for time-based deletion, a scheduled "retention sweeper" that deletes or pseudonymizes expired records, a **data catalog** (e.g., a tagging system or tools like OpenMetadata) that scanners reconcile against actual stores, and CI checks that fail if a new column lacks a classification tag. **Trade-off:** over-retention is a liability (more to breach, more to delete on request) while under-retention can violate legal-hold or tax-record obligations — so retention must reconcile competing legal requirements, which is why it's owned jointly by engineering and legal.

---

## 🟠 Advanced (8–12 yrs)

### Q12. [Practical] Design a multi-region architecture that enforces GDPR data residency while still offering a global product.

**Scenario:** A SaaS with users in the EU, US, and APAC. EU customer data must stay in the EU (residency / data-localization requirements and to avoid problematic international transfers post-Schrems II).

```
                +-------------------------+
                |   Global Control Plane  |  (no personal data;
                |  routing, billing meta, |   only pseudonymous IDs,
                |  feature flags, config) |   config, metrics)
                +------------+------------+
                             |
        region routing by user's "home region" (sticky)
        ____________________ | ____________________
       |                     |                      |
  +----v-----+         +-----v----+           +-----v----+
  | EU CELL  |         | US CELL  |           | APAC CELL|
  | DB (EU)  |         | DB (US)  |           | DB (AP)  |
  | KMS (EU) |         | KMS (US) |           | KMS (AP) |
  | backups  |         | backups  |           | backups  |
  |  in EU   |         |  in US   |           |  in AP   |
  +----------+         +----------+           +----------+
   PII never leaves the cell; cross-region calls carry only tokens/IDs
```

**Key design decisions:**
- **Cell / silo per region.** Each region is a self-contained stack: data store, backups, encryption keys (region-local KMS), and processing all stay in-region. Backups and DR replicas also stay in-region — a frequent miss.
- **Data-free control plane.** Global functionality (billing rollups, analytics, feature flags) operates on **pseudonymous identifiers and aggregates**, never raw PII. This lets you keep a single global service layer without violating residency.
- **Home-region routing.** A user is pinned to a home region; an EU user's requests are routed to the EU cell. Edge/CDN logs are scrubbed of PII or kept regionally.
- **International transfers.** Where transfer is unavoidable, use approved mechanisms: **SCCs (Standard Contractual Clauses)**, the **EU-US Data Privacy Framework**, and a **Transfer Impact Assessment**.

**Trade-offs:** Cellular architecture increases operational cost and complicates features that genuinely need global views (cross-region search, global leaderboards). You solve those with **aggregation/anonymization** pipelines that emit only non-personal data to the global plane. The win is that residency becomes a property of the topology rather than something enforced by hopeful policy.

### Q13. [Coding] Implement tamper-evident audit logging (hash-chained) suitable for HIPAA/SOC 2 evidence. Explain why naive logging fails.

**Problem:** Audit logs are evidence; an attacker (or insider) who can edit or delete log entries undermines the entire control. Build an append-only log where any tampering is detectable.

```java
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public class AuditLog {

    public record Entry(
        long seq, String actor, String action, String resource,
        String timestamp, String prevHash, String hash) {}

    private String lastHash = "GENESIS";
    private long seq = 0;

    /** Each entry's hash covers its content + the previous hash => a chain. */
    public synchronized Entry append(String actor, String action, String resource)
            throws Exception {
        long s = ++seq;
        String ts = Instant.now().toString();
        String payload = s + "|" + actor + "|" + action + "|" + resource
                       + "|" + ts + "|" + lastHash;
        String h = sha256(payload);
        Entry e = new Entry(s, actor, action, resource, ts, lastHash, h);
        lastHash = h;
        return e; // persist e to append-only, WORM-backed storage
    }

    /** Verify the whole chain: recompute each hash; any edit breaks the chain. */
    public static boolean verify(java.util.List<Entry> log) throws Exception {
        String prev = "GENESIS";
        for (Entry e : log) {
            String payload = e.seq() + "|" + e.actor() + "|" + e.action() + "|"
                           + e.resource() + "|" + e.timestamp() + "|" + prev;
            if (!sha256(payload).equals(e.hash()) || !e.prevHash().equals(prev)) {
                return false; // tampering or gap detected
            }
            prev = e.hash();
        }
        return true;
    }

    private static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes("UTF-8")));
    }
}
```

**Why naive logging fails:** plain rows in a mutable table can be `UPDATE`d/`DELETE`d by anyone with DB access, leaving no trace — so the log proves nothing in a forensic or audit context. The hash chain makes each entry depend on its predecessor, so altering or removing an entry breaks verification of all subsequent entries.

**Production hardening (beyond the snippet):**
- Ship logs to **WORM** (write-once-read-many) / immutable storage (e.g., object-lock buckets) so even the chain can't be silently truncated.
- Periodically **anchor** the latest hash to an independent system (a separate account, a notary, or a transparency log) so an attacker can't rewrite the chain wholesale.
- Sign entries with an HSM key for non-repudiation; ensure clock integrity (NTP) for trustworthy timestamps.
- Never put PHI/PAN in the log message itself — log the *event* and resource ID, not the sensitive payload.

**Time/Space:** append is O(1); `verify` is O(n) over the log. Space O(n) to store entries.

### Q14. [Theory] Compare pseudonymization, anonymization, hashing, and encryption. Why does GDPR treat them so differently?

These sit on a spectrum of reversibility, and GDPR's scope hinges on whether data can still identify someone:
- **Anonymization** = irreversibly stripping identifiability such that no one (not even you) can re-identify the person. Truly anonymized data is **out of GDPR scope**. The catch: true anonymization is hard — aggregation, k-anonymity, or differential privacy may be needed, and naive "remove the name" data is often re-identifiable via quasi-identifiers (the classic Netflix/AOL re-identification cases).
- **Pseudonymization** = replacing identifiers with a key/token while keeping a *separate* mapping that can reverse it. Pseudonymized data is **still personal data** (still in scope) but is an explicitly encouraged risk-reducing measure; it limits blast radius if the dataset leaks without the mapping.
- **Hashing** = one-way function. A *salted* hash of an email is pseudonymization at best, not anonymization, because the input space is small enough to brute-force/rainbow-table — so a hashed email is still personal data.
- **Encryption** = reversible with a key; encrypted personal data is still personal data, but is a primary security control and the basis for breach "safe harbor."

The **why**: GDPR is outcome-based — it cares whether a natural person *can be identified*. Reversibility (and who holds the means to reverse) determines scope and risk, which is why interviewers probe whether you understand that "we hashed it" does **not** mean "it's anonymous."

### Q15. [Practical] What is PCI-DSS scope reduction in concrete architectural terms, and how would you redesign a system that currently stores raw PANs?

PCI scope = every system component that stores, processes, transmits, or is **connected to** the **CDE (Cardholder Data Environment)**. The more systems in scope, the more expensive and slow your audit (potentially a full QSA assessment vs. a simple SAQ). **Scope reduction** means shrinking the CDE.

**Current (bad) state — broad scope:**
```
Browser --> App Server --(raw PAN)--> App DB (stores PAN)
                |                          ^
                +--> Logs (PAN leaks) -----+   entire stack in scope
```

**Redesigned — minimized scope:**
```
Browser --(card data goes straight to PSP iframe/hosted fields)--> Payment Processor
   |                                                                     |
   +--> App Server  <----------- token only -----------------------------+
                |
                +--> App DB (stores token, never PAN)   <-- mostly OUT of scope
```

**Concrete moves:**
- **Hosted fields / iframe / redirect** so the raw PAN goes browser→processor and never transits your servers (PCI **SAQ A** is the cheapest tier — you can reach it if you never touch CHD).
- **Network segmentation:** put any remaining CDE components in an isolated VLAN/subnet with strict firewall rules, so the rest of the estate is "not connected to" the CDE.
- **Tokenization vault** (random-mapping, not encryption-derived) isolated from app servers.
- **Eliminate CHD in logs/analytics/backups** — a top source of accidental in-scope sprawl.
- **Never store SAD/CVV** after authorization, period.

**Trade-off:** hosted fields reduce UI control and customization; some businesses (large merchants doing recurring billing, marketplaces) genuinely must handle PANs and accept a larger scope with compensating controls. The skill is matching the architecture to the *minimum* CHD exposure the business actually requires.

### Q16. [Theory] What are the breach notification obligations across GDPR, HIPAA, and PCI, and how do they shape incident-response engineering?

- **GDPR:** notify the supervisory authority within **72 hours** of becoming aware of a breach "likely to result in a risk" to individuals; notify affected individuals "without undue delay" if the risk is high. Encrypted data may avoid individual notification (it's not intelligible).
- **HIPAA Breach Notification Rule:** notify affected individuals **within 60 days**; breaches affecting **500+** individuals also require notifying HHS and the media; smaller breaches are logged and reported annually.
- **PCI:** contractual rather than statutory — you must notify your acquirer/card brands per your agreement, often immediately, and may trigger a forensic investigation (PFI).

**Engineering implications:** the 72-hour clock means you need **detection and forensics built in advance**, not improvised. Concretely: centralized tamper-evident audit logs (see Q13), the ability to quickly answer "*whose* data, *which* fields, *how many* records," automated alerting/SIEM, an incident runbook with pre-assigned roles, and data classification so you can scope impact fast. Encryption is your friend twice over: it can reduce or eliminate the notification obligation, which is a direct business reason to encrypt sensitive fields. The recurring lesson from real breaches (Equifax 2017, Capital One 2019) is that slow detection and unpatched/misconfigured components turn a contained incident into a headline.

---

## 🔴 Expert (15+ yrs)

### Q17. [Behavioral] As a staff/principal engineer, how do you balance compliance requirements against velocity and product pressure?

The framing I use is that compliance is **risk management, not a binary gate** — my job is to make the *cheapest correct thing* also the *default path*. Tactically: (1) I push controls **left and into platform** — encrypted-by-default data stores, a paved-road service template that already emits audit logs and enforces TLS, a data catalog that *requires* classification tags in CI — so individual teams get compliance "for free" rather than as a tax. (2) I distinguish **legal must-haves** (breach notification capability, lawful basis, BAAs) from **maturity items** we can stage, and I make that risk explicit to leadership with a written trade-off rather than silently cutting corners. (3) I bring **legal/security in early** as partners, framing requirements as design constraints during architecture review, not as a late-stage blocker. The behavioral signal interviewers want: you neither treat compliance as someone else's problem nor let it ossify into bureaucracy — you operationalize it so it scales with the org. A concrete example I'd cite: replacing a quarterly manual access-review spreadsheet with an automated IAM diff that files tickets — same SOC 2 control, a fraction of the human cost, and far better evidence.

### Q18. [Practical] Design a company-wide data governance platform that simultaneously serves GDPR, HIPAA, PCI, SOC 2, and ISO 27001 without five separate implementations.

The insight is that these frameworks **overlap heavily** — most map to a common set of capabilities. Build *one* control plane and map it to multiple frameworks, rather than five silos.

```
        +---------------------------------------------------+
        |              DATA GOVERNANCE PLATFORM             |
        +---------------------------------------------------+
        | 1. Data Catalog + Classification (tags: PII/PHI/  |
        |    CHD, lawful basis, residency, retention)       |
        | 2. Policy Engine (OPA-style): access, residency,  |
        |    masking decisions evaluated centrally          |
        | 3. Identity & Access (RBAC/ABAC, least privilege, |
        |    automated access reviews)                      |
        | 4. Crypto Service (KMS/HSM, envelope encryption,  |
        |    key rotation, per-region keys)                 |
        | 5. Tamper-evident Audit Log (who/what/when)       |
        | 6. DSAR/Erasure Orchestrator (access/export/delete)|
        | 7. Evidence Collector (continuous compliance)     |
        +-------------------------+-------------------------+
                                  |
              control mapping (one control -> many frameworks)
        ______________ | _______________________________________
       |        |         |            |               |
     GDPR     HIPAA     PCI-DSS      SOC 2          ISO 27001
   (erasure,(safeguards,(scope,    (TSC,           (Annex A,
    consent) audit)    tokenize)   evidence)        ISMS)
```

**Architecture principles:**
- **Single source of truth for classification.** Every store registers its schema with the catalog; the classification *drives* downstream policy (encryption, masking, retention, residency) so a field tagged `PHI` is automatically handled correctly everywhere.
- **Policy as code.** A central policy engine (OPA/Cedar-style) evaluates access and masking; auditors review *policy*, not scattered `if` statements. This gives you one place to prove "PHI requires authenticated, role-based, logged access."
- **Continuous compliance / evidence-as-code.** Instead of scrambling before an audit, an evidence collector continuously snapshots control state (MFA enforced, encryption on, access reviews completed) — this is the modern "compliance automation" pattern (Vanta/Drata-style) but built natively.
- **Shared crypto and audit primitives** so HIPAA's "audit controls," SOC 2's "Security" criterion, and PCI's logging requirements are all satisfied by *one* implementation.

**Trade-offs:** a central platform is a single point of failure and a potential bottleneck — so it must be highly available, well-versioned, and offer self-service. The payoff is enormous: adding a sixth framework becomes a *mapping exercise* rather than a new build, and a single audit finding gets fixed once everywhere.

### Q19. [Theory] How does compliance fundamentally reshape system architecture — name the structural patterns it forces and the second-order costs.

Compliance is an **architectural force**, not a feature backlog, and it pushes designs toward several recurring patterns: **data minimization** (don't collect what you don't need — the cheapest data to protect is data you never have); **purpose binding** (data tagged with why it was collected, gating what you can do with it); **cellular/regional isolation** for residency; **deletion as a first-class capability** (you must architect to *find and remove* an individual's data across every derived store, which is far harder than writing it); **isolation of sensitive data** (vaults, separate trust zones, field-level encryption shifting the boundary to key management); and **immutable, tamper-evident audit trails** as ground truth. The second-order costs are real: deletion requirements make aggressive denormalization and caching expensive (every copy is now a deletion obligation); residency fragments your data and complicates global features; immutable audit logs conflict with the "right to erasure" (you resolve this by logging events/IDs, not PII); and pseudonymization adds join complexity and latency. The expert recognition is that these constraints often *improve* architecture — minimization reduces blast radius, purpose binding clarifies data flows, and tracking every data copy is exactly what you'd want for reliability and debugging anyway. The danger is bolting compliance on late, when retrofitting deletion or residency into a system that assumed unlimited global replication can require a near-rewrite.

### Q20. [Practical] You inherit a 10-year-old monolith that's now in scope for HIPAA and a new EU launch (GDPR). It logs PHI in plaintext, has no deletion capability, and replicates all data to a US analytics warehouse. Prioritize and sequence the remediation.

**Triage by risk × legal exposure, not by ease.** I'd sequence:

1. **Stop the bleeding (days):** kill PHI/PII in plaintext logs immediately (redaction/scrubbing at the logging layer, rotate/purge existing log retention). Plaintext PHI in logs is both a HIPAA violation and a live breach risk — highest severity, often fixable fast at the log appender.
2. **Halt the unlawful EU→US data flow (weeks):** before the EU launch, stop replicating EU personal data to the US warehouse, or restrict the warehouse feed to anonymized/aggregated data. This is a hard legal blocker for go-live.
3. **Encryption + access controls (weeks):** enable at-rest encryption (KMS-managed), enforce TLS everywhere, introduce RBAC and tamper-evident audit logging — these satisfy HIPAA technical safeguards and SOC 2 simultaneously, and unblock the BAA.
4. **Build deletion capability (months):** retrofit a DSAR/erasure path. In a monolith this is often a phased "pseudonymize-then-delete" because true deletion across all tables/backups is complex; start with a documented manual process under SLA, then automate.
5. **Regional separation for residency (months/quarters):** stand up an EU cell or EU-region data store for new EU customers; this is the largest effort and is sequenced last but planned first so earlier steps don't paint you into a corner.

**Trade-offs & judgment:** I'd run steps 1–2 in parallel because they're the acute legal/security exposures, document a **risk register** with compensating controls and target dates (auditors and regulators respond well to a credible remediation plan), and resist the temptation to do the "fun" architecture (regionalization) before the unglamorous log scrubbing that actually stops an active breach. The staff-level move is making the **sequencing rationale explicit** to legal and leadership so everyone agrees on accepted interim risk.

---

## ✅ Key Takeaways

- **Compliance is architecture, not paperwork.** Data subject rights (erasure, portability), residency, and audit requirements translate directly into system capabilities you must design in — retrofitting them later can be a near-rewrite.
- **The cheapest data to secure is data you never store.** Data minimization and tokenization/scope-reduction (PCI) cut both risk and audit cost.
- **Know the scope triggers:** GDPR = personal data of EU residents (extraterritorial); HIPAA = PHI handled by covered entities/business associates (needs a BAA); PCI = anyone touching cardholder data; SOC 2/ISO 27001 = trust signals demanded by buyers.
- **Encryption shifts the boundary to key management** and provides breach "safe harbor" — design KMS/HSM, rotation, and per-region keys deliberately.
- **Pseudonymization ≠ anonymization.** Reversible/re-identifiable data is still in GDPR scope; only true anonymization leaves scope.
- **Frameworks overlap ~80%** — build one governance/control plane (classification, policy-as-code, crypto, audit, DSAR, continuous evidence) and *map* it to multiple frameworks.
- **Audit logs must be tamper-evident and PII-free** — hash-chained, WORM-backed, logging event IDs not sensitive payloads.

## ⚠️ Common Pitfalls

- Deleting data from the primary DB but leaving copies in search indexes, caches, the data warehouse, and backups (the classic incomplete-erasure audit finding).
- Logging PHI, PANs, or PII in application logs, traces, or exception messages.
- Treating a salted hash of an email as "anonymized" — it's still personal data.
- Storing CVV/SAD after authorization (an outright PCI prohibition), or storing raw PANs when hosted fields would have removed you from scope.
- Pre-ticked consent boxes, "by using this site you agree" banners, or making withdrawal harder than granting — all invalidate consent under GDPR.
- Assuming SOC 2 or ISO 27001 is a one-time certificate — both require *continuous* operating evidence (Type II covers a period; ISO requires an ongoing ISMS).
- Replicating EU personal data to US regions/warehouses without SCCs or the Data Privacy Framework, and forgetting that DR replicas and backups must also respect residency.
- Building compliance per-team instead of into the platform, producing inconsistent controls and N times the audit effort.

## 📚 Further Reading

- **GDPR full text** — eur-lex.europa.eu (the regulation itself; Articles 15–22 cover data subject rights, Article 33 covers breach notification).
- **HHS HIPAA Security Rule guidance** — hhs.gov/hipaa (Security Rule, Breach Notification Rule, sample BAA terms).
- **PCI Security Standards Council — PCI-DSS v4.0** — pcisecuritystandards.org (current standard, SAQ types, tokenization guidelines).
- **AICPA Trust Services Criteria (2017, updated)** — aicpa-cima.com (authoritative SOC 2 criteria).
- **ISO/IEC 27001:2022** — iso.org (ISMS standard and Annex A controls).
- *Designing Data-Intensive Applications* — Martin Kleppmann (not compliance-specific, but the canonical reference for the data-lifecycle, replication, and deletion problems compliance forces you to solve).
