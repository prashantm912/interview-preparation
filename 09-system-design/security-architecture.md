# Security Architecture

A staff-level interview guide to designing secure systems at scale: zero-trust, defense in depth, identity and access management, cryptography, secret management, threat modeling, and the secure SDLC. Knowledge current through 2026.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the CIA triad and why does every security design decision map back to it?

The **CIA triad** is Confidentiality, Integrity, and Availability — the three properties every security control ultimately protects.

- **Confidentiality**: only authorized parties can read data (encryption, access control).
- **Integrity**: data is not tampered with undetectably (hashing, signatures, checksums, immutable logs).
- **Availability**: the system is usable when needed (DDoS protection, redundancy, rate limiting).

The reason it matters in design is that controls trade off against each other: aggressive integrity checks add latency (hurting availability), and strict confidentiality (e.g., full disk encryption with HSM-backed keys) adds cost and operational complexity. A senior engineer frames every decision as "which leg of the triad am I strengthening, and what am I spending to get it?" Some frameworks add **Authenticity** and **Non-repudiation** (the "Parkerian hexad"), which matter for audit and compliance contexts like financial transactions.

### Q2. [Theory] What is the difference between authentication and authorization?

**Authentication (AuthN)** answers *"who are you?"* — verifying identity via passwords, MFA, certificates, or biometrics. **Authorization (AuthZ)** answers *"what are you allowed to do?"* — deciding whether an authenticated principal can perform an action on a resource.

They are distinct phases and must not be conflated: a valid login (AuthN succeeds) does not imply permission to delete another user's data (AuthZ must still pass). A classic vulnerability — OWASP's **Broken Object Level Authorization (BOLA/IDOR)** — happens precisely when developers authenticate the user but forget to check that *this* user owns *that* object. In code terms, AuthN happens once per session/request (token validation); AuthZ happens at every protected operation.

### Q3. [Theory] What does "encryption in transit" vs "encryption at rest" mean?

**In transit** protects data moving across a network (TLS 1.3 between client and server, mTLS between microservices). **At rest** protects data sitting on disk or in a database (disk-level encryption, column/field encryption, transparent data encryption).

The two address different threat models: in transit defends against network eavesdroppers and man-in-the-middle attackers; at rest defends against someone who steals a disk, a backup, or a database snapshot. Neither covers **data in use** (in memory), which is why confidential computing / enclaves (Intel SGX, AWS Nitro Enclaves) exist. A common mistake is assuming TLS termination at the load balancer means data is encrypted everywhere — traffic *behind* the LB is often plaintext unless you add mTLS or a service mesh.

### Q4. [Practical] You're storing user passwords. Walk through how you'd do it correctly.

Never store plaintext or reversible encryption. Use a **slow, salted, adaptive hash**: Argon2id (preferred in 2026), scrypt, or bcrypt. Each password gets a unique random salt (stored alongside the hash) to defeat rainbow tables, and a high work factor to slow brute force.

```java
// Spring Security 6 (Spring Boot 3) — Argon2 is the modern default choice
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
String stored = encoder.encode(rawPassword);          // salt + params embedded in the string
boolean ok    = encoder.matches(rawPassword, stored); // constant-time comparison internally
```

Production trade-offs: tune memory/iterations so a hash takes ~250–500ms on your hardware (balancing user latency vs attacker cost), add a server-side **pepper** stored in a secret manager (so a DB-only breach is insufficient), enforce MFA, and never log the raw password. `DelegatingPasswordEncoder` lets you migrate algorithms over time using the `{argon2}`/`{bcrypt}` prefix.

### Q5. [Coding] Implement a constant-time string comparison and explain why `String.equals` is dangerous for secrets.

`String.equals` and `Arrays.equals` short-circuit on the first mismatching byte. An attacker measuring response time can recover a secret (HMAC token, API key) byte-by-byte — a **timing side-channel attack**.

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class SecureCompare {

    // Preferred: JDK provides a constant-time comparison.
    public static boolean equalsConstantTime(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb); // length-safe + constant-time since Java 6u17
    }

    // Manual version to show the technique (length must not leak meaningfully).
    public static boolean manualConstantTime(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;            // fold length into result
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[(i < b.length) ? i : 0]; // never break early
        }
        return diff == 0;
    }
}
```

**Time:** O(n) always (that's the point — no early exit). **Space:** O(1). **Edge cases:** differing lengths still leak a tiny amount, so for high-value secrets compare fixed-length HMACs of both inputs. Always prefer `MessageDigest.isEqual` over hand-rolling.

### Q6. [Theory] What is the principle of least privilege and how does it show up in a real system?

**Least privilege** means every user, service, and process gets the minimum permissions required to do its job — nothing more. It limits **blast radius**: if a component is compromised, the attacker inherits only its narrow rights.

In practice this is everywhere: an IAM role for a Lambda that can read one S3 bucket (not `s3:*`), a database account that has `SELECT` on three tables instead of `db_owner`, a Kubernetes pod with a scoped ServiceAccount and a restrictive NetworkPolicy, and short-lived scoped OAuth tokens instead of long-lived god-mode credentials. The hardest part operationally is **privilege creep** — permissions accumulate over years and are never revoked — which is why mature orgs run automated access reviews and "just-in-time" elevation (e.g., request admin for 1 hour, auto-revoked).

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain the OAuth2 Authorization Code flow with PKCE and why PKCE exists.

OAuth2 **Authorization Code with PKCE** (Proof Key for Code Exchange) is the recommended flow for browser, mobile, and SPA clients as of OAuth 2.1.

```
 User        Client (SPA/App)        Authorization Server      Resource Server
  |                |                          |                       |
  | clicks login   |                          |                       |
  |--------------->| 1. generate code_verifier (random)               |
  |                |    code_challenge = SHA256(verifier)             |
  |                |--- 2. /authorize?challenge,redirect_uri -------->|
  |                |                          | 3. user authenticates |
  |                |<-- 4. redirect w/ auth code ---------------------|
  |                |--- 5. /token (code + code_verifier) ------------>|
  |                |                          | 6. verify SHA256(verifier)==challenge
  |                |<-- 7. access_token (+ refresh, id_token) --------|
  |                |--- 8. API call w/ Bearer access_token --------------------->|
  |                |<-- 9. protected resource ---------------------------------- |
```

**Why PKCE:** the original implicit flow returned tokens directly in the URL fragment — leaky and interceptable. The plain auth-code flow used a static `client_secret`, which can't be safely embedded in a public client (anyone can decompile a mobile app). PKCE binds the token exchange to a one-time, client-generated secret (`code_verifier`) that never travels in the redirect, so a stolen authorization code is useless without it. This defeats **authorization code interception attacks**. OAuth 2.1 mandates PKCE for *all* clients, even confidential ones.

### Q8. [Theory] OAuth2 vs OIDC vs SAML — when do you reach for each?

- **OAuth2** is an *authorization* framework: it issues access tokens that grant a client delegated access to resources ("let this app read my calendar"). It is **not** an authentication protocol — using a raw access token to "log in" is an anti-pattern.
- **OIDC (OpenID Connect)** is a thin identity layer *on top of* OAuth2. It adds an **ID token** (a signed JWT with claims about the user) and a standard `/userinfo` endpoint, so it properly answers "who is this user?" Use OIDC for modern SSO and "Login with Google/Okta."
- **SAML 2.0** is an older XML-based SSO standard, still dominant in enterprise B2B and legacy IdPs. Heavier (XML signatures, SOAP-era tooling) but deeply entrenched.

Rule of thumb in 2026: new web/mobile/API systems use **OIDC + OAuth2**; you support **SAML** when integrating with enterprise customers whose IdP only speaks SAML. Many gateways (Keycloak, Okta, Auth0/Entra) bridge both so your apps only speak OIDC.

### Q9. [Practical] Your microservices currently trust each other on a private VPC. Move them to mTLS. What changes and what breaks?

**Current state:** "soft interior" — once inside the VPC, any service can call any other. This violates zero-trust (a single compromised pod can pivot freely).

**Target:** mutual TLS where both client and server present X.509 certs, so identity is cryptographically proven on every hop, not assumed from network location.

**Approach (production):**
1. Introduce a **service mesh** (Istio, Linkerd) or sidecar that auto-issues short-lived certs (SPIFFE/SPIRE identities). Avoid hand-managing certs per service.
2. Roll out in **permissive mode** first (accept both mTLS and plaintext), observe, then flip to **strict**.
3. Wire mesh authorization policies for service-to-service AuthZ ("`orders` may call `payments`, nobody else").

```
Before (implicit trust):          After (mTLS, zero-trust):
 [svc A] --plaintext--> [svc B]    [svc A]<->[sidecar]==mTLS==>[sidecar]<->[svc B]
   any pod can call any pod          identity + policy enforced per call
```

**What breaks / trade-offs:** cert rotation must be automated (1-hour certs) or you get outages; debugging gets harder (everything is encrypted — you need mesh observability); ~5–15% latency/CPU overhead from the sidecars; health checks and load balancers may need re-config. The payoff: lateral movement is blocked and you get an audit trail of who-called-what.

### Q10. [Theory] RBAC vs ABAC vs ReBAC — how do you choose?

- **RBAC (Role-Based)**: permissions attach to roles, users get roles. Simple, auditable, scales poorly to fine-grained rules ("only during business hours, only for your region"). Leads to **role explosion** when you encode every nuance as a new role.
- **ABAC (Attribute-Based)**: decisions are computed from attributes of subject, resource, action, and environment via policies (`allow if user.dept == resource.dept AND time < 18:00`). Flexible, but harder to reason about and audit ("why was this allowed?").
- **ReBAC (Relationship-Based)**: authorization derives from relationship graphs ("user is editor of doc, which is in folder they own"). This is Google's **Zanzibar** model, behind Drive/YouTube and now products like OpenFGA and SpiceDB.

Choose RBAC for coarse, role-shaped domains; ABAC when context (time, location, risk) drives decisions; ReBAC for deeply hierarchical sharing (documents, orgs, repos). Mature systems combine them: RBAC for the baseline, ABAC conditions on top. Externalize the decision into a **Policy Decision Point** (OPA/Rego, Cedar) so apps just ask "may I?" rather than embedding logic.

### Q11. [Practical] Map the OWASP API Security Top 10 (2023) to concrete defenses. Which one bites teams hardest?

| Risk | What it is | Defense |
|------|-----------|---------|
| API1 BOLA | Accessing other users' objects via guessable IDs | Per-object ownership check on **every** request; don't trust IDs from the client |
| API2 Broken Auth | Weak/missing token validation | Validate signature, `exp`, `aud`, `iss`; short-lived tokens; MFA |
| API3 Broken Object Property Auth | Mass assignment / leaking fields | Explicit DTOs; never bind request body straight to entity |
| API4 Unrestricted Resource Consumption | No rate/size limits | Rate limiting, pagination caps, payload size limits, timeouts |
| API5 Broken Function Level Auth | Calling admin endpoints as a user | Deny-by-default; check role per endpoint |
| API6 Unrestricted Access to Business Flows | Bots abusing legit flows (scalping) | Device fingerprinting, CAPTCHA, behavioral limits |
| API7 SSRF | Server fetches attacker-supplied URL | Allowlist egress; block link-local/metadata IPs |
| API8 Misconfiguration | Verbose errors, open CORS, default creds | Hardening baselines, IaC scanning |
| API9 Improper Inventory | Forgotten `v1`/staging APIs | API gateway catalog, kill old versions |
| API10 Unsafe Consumption of 3rd-party APIs | Blindly trusting upstream data | Validate/sanitize upstream responses too |

**Bites hardest: API1 BOLA.** It's the #1 cause of real API breaches because it's an application-logic flaw no WAF or scanner reliably catches — every endpoint must individually verify the caller owns the resource. A `GET /api/orders/{id}` that returns *any* order to *any* authenticated user is the textbook breach.

### Q12. [Coding] Implement a JWT validation filter for a Spring Boot 3 resource server (signature, expiry, audience, issuer).

```java
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.JWTVerifier;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

public class JwtAuthFilter implements Filter {

    private final JWTVerifier verifier;

    public JwtAuthFilter(RSAPublicKey publicKey) {
        // Asymmetric (RS256): resource server holds ONLY the public key.
        this.verifier = JWT.require(Algorithm.RSA256(publicKey, null))
                .withIssuer("https://idp.example.com")     // iss
                .withAudience("orders-api")                 // aud — reject tokens minted for other services
                .acceptLeeway(5)                            // 5s clock skew tolerance
                .build();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws java.io.IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        String header = http.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                DecodedJWT jwt = verifier.verify(token); // checks signature + exp + iss + aud
                var authorities = jwt.getClaim("roles").asList(String.class).stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                var auth = new UsernamePasswordAuthenticationToken(
                        jwt.getSubject(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                ((HttpServletResponse) res).sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return; // fail closed
            }
        }
        chain.doFilter(req, res);
    }
}
```

**Why RS256 over HS256:** with the symmetric HS256 the verifier needs the *signing* secret, so every resource server can also *mint* tokens — a privilege leak. RS256 splits keys: only the IdP signs (private key), services verify (public key, often fetched via JWKS).

**Critical edge cases / pitfalls:** (1) Reject `alg: none` and don't let the token dictate the algorithm — the classic **JWT algorithm-confusion** attack tricks a server expecting RS256 into verifying an HS256 token using the *public* key as the HMAC secret. Pinning the algorithm in `JWT.require(...)` prevents this. (2) Always verify `aud` or a token for service B works on service A. (3) Validate `exp`; keep access tokens short (5–15 min) and use refresh tokens. (4) For revocation, JWTs can't be un-issued — use short TTLs plus a denylist of `jti` for emergency revocation.

### Q13. [Practical] Design rate limiting / DDoS protection for a public API. What layers and what algorithm?

DDoS defense is **layered (defense in depth)**:

```
Internet
  │
[ Anycast CDN / scrubbing ]  L3/L4 volumetric absorption (Cloudflare/Shield/Akamai)
  │
[ WAF ]                       L7 — OWASP rules, bot mgmt, geo/IP reputation
  │
[ API Gateway ]               authN, per-key + per-IP rate limits, quotas
  │
[ Service ]                   app-level limits on expensive endpoints
```

**Algorithm choice:** use a **token bucket** (allows controlled bursts) or **sliding-window counter** (smooth, no boundary spikes). Implement distributed limits in Redis so all gateway nodes share state:

```java
// Token bucket via Redis (atomic Lua keeps refill+consume race-free)
private static final String LUA = """
  local tokens = tonumber(redis.call('get', KEYS[1]) or ARGV[1])
  local refill = tonumber(ARGV[2]) * (tonumber(ARGV[4]) - tonumber(redis.call('get', KEYS[2]) or ARGV[4]))
  tokens = math.min(tonumber(ARGV[1]), tokens + math.max(0, refill))
  if tokens < 1 then return 0 end
  redis.call('set', KEYS[1], tokens - 1)
  redis.call('set', KEYS[2], ARGV[4])
  return 1
  """;
// returns 1 = allow, 0 = throttle (HTTP 429 + Retry-After)
```

**Trade-offs / production reality:** rate limit per API key *and* per IP (one compromised key shouldn't sink the tenant; IP catches keyless abuse). Return `429` with `Retry-After` and `X-RateLimit-*` headers. Fail **open** for limiter outages on read paths (availability) but **closed** on sensitive writes (security) — decide per endpoint. Layer 7 attacks (slowloris, expensive-query floods) need app awareness the CDN can't provide, so don't rely on a single tier.

### Q14. [Theory] What is envelope encryption and why not just encrypt everything directly with a KMS key?

**Envelope encryption** uses two key tiers: a **Data Encryption Key (DEK)** encrypts the actual data, and a **Key Encryption Key (KEK)** — held in KMS/HSM and never exported — encrypts the DEK. You store the ciphertext alongside the *encrypted* DEK.

```
Plaintext --AES-256-GCM--> Ciphertext      (encrypted with DEK, locally, fast)
   DEK    --KMS(KEK)------> Encrypted DEK   (the only KMS round-trip)
Stored together: [ Ciphertext | Encrypted DEK | IV | KEK id ]
```

**Why not encrypt directly with KMS:** (1) **Performance** — KMS calls are network round-trips with size limits (~4KB on AWS KMS), useless for a 2GB file; you encrypt bulk data locally with the DEK and only call KMS to wrap/unwrap the tiny DEK. (2) **Key rotation** — rotate the KEK without re-encrypting petabytes; just re-wrap DEKs. (3) **Blast radius & separation of duties** — the master key stays in the HSM, never touches your app memory long-term. This is how AWS S3 SSE-KMS, GCP, and Vault transit all work under the hood.

### Q15. [Theory] HSM vs cloud KMS vs Vault — what problem does each solve?

- **HSM (Hardware Security Module)**: tamper-resistant hardware where keys are generated and used but **never leave** in plaintext. Gold standard for root keys, certificate authorities, and FIPS 140-2/3 Level 3 compliance (PCI-DSS, financial). Expensive and operationally heavy.
- **Cloud KMS** (AWS KMS, GCP KMS, Azure Key Vault): managed key management, usually HSM-backed under the hood, exposed via API with IAM policies and CloudTrail audit. The default for most cloud-native systems — you get envelope encryption, rotation, and access logging without running hardware.
- **HashiCorp Vault** (or cloud secret managers): broader **secrets management** — not just keys but DB credentials, API tokens, PKI issuance, and **dynamic secrets** (generate a 1-hour DB password on demand, auto-revoked). Vault's transit engine also does encryption-as-a-service so apps never see keys.

They compose: Vault/KMS for application secrets and DEK wrapping, an HSM as the hardware root of trust beneath the KEK. Use a **CloudHSM/dedicated HSM** when regulators demand sole control of key material; otherwise managed KMS is the pragmatic choice.

### Q16. [Practical] An engineer committed an AWS secret key to GitHub. Walk through your incident response.

The key is **compromised the instant it hits a public (or even private) repo** — bots scan GitHub within seconds. Steps:

1. **Revoke first, investigate second.** Immediately deactivate/delete the IAM access key. Rotation beats forensics here — assume it's already harvested.
2. **Scope the blast radius.** Pull CloudTrail for that key: what was called, from what IPs, was anything unusual (new IAM users, `s3 sync`, crypto-mining EC2 spin-ups)?
3. **Rotate the real secret** and update consumers via the secret manager, not by re-committing.
4. **Purge from history** — `git filter-repo` / BFG — though treat the value as permanently burned regardless.
5. **Prevent recurrence:** add pre-commit secret scanning (gitleaks, trufflehog), enable GitHub push protection / secret scanning, and — the real fix — **stop using static keys**. Move to IAM roles / OIDC federation (GitHub Actions can assume a role with no long-lived secret) or short-lived dynamic credentials from Vault.

The lesson for the interview: long-lived static credentials are the root cause. The mature answer isn't "scan better," it's "make leaked secrets useless" via short TTLs and workload identity.

### Q17. [Coding] Implement envelope encryption (AES-GCM data key wrapped by a master key) in Java.

```java
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public class EnvelopeCrypto {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;            // 96-bit nonce: NIST-recommended for GCM
    private static final SecureRandom RNG = new SecureRandom();

    /** Generate a fresh per-object Data Encryption Key. */
    static SecretKey newDek() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return kg.generateKey();
    }

    /** Encrypt with AES-256-GCM; returns IV || ciphertext+tag. */
    static byte[] encrypt(SecretKey key, byte[] plaintext) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        RNG.nextBytes(iv);                              // unique IV per encryption — reuse breaks GCM!
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plaintext);
        byte[] out = new byte[IV_BYTES + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
        return out;
    }

    static byte[] decrypt(SecretKey key, byte[] ivAndCt) throws Exception {
        byte[] iv = Arrays.copyOfRange(ivAndCt, 0, IV_BYTES);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return c.doFinal(Arrays.copyOfRange(ivAndCt, IV_BYTES, ivAndCt.length)); // throws if tag invalid
    }

    /** Envelope: encrypt data with a fresh DEK, then wrap the DEK with the KEK (KMS in prod). */
    static byte[][] sealEnvelope(SecretKey kek, byte[] plaintext) throws Exception {
        SecretKey dek = newDek();
        byte[] ciphertext = encrypt(dek, plaintext);
        byte[] wrappedDek = encrypt(kek, dek.getEncoded()); // in prod: kmsClient.encrypt(dek)
        return new byte[][]{ ciphertext, wrappedDek };
    }

    static byte[] openEnvelope(SecretKey kek, byte[] ciphertext, byte[] wrappedDek) throws Exception {
        byte[] dekBytes = decrypt(kek, wrappedDek);         // in prod: kmsClient.decrypt(wrappedDek)
        SecretKey dek = new SecretKeySpec(dekBytes, "AES");
        return decrypt(dek, ciphertext);
    }
}
```

**Why GCM:** it's **AEAD** — gives confidentiality *and* integrity (the auth tag detects tampering), so you don't bolt on a separate HMAC. **Complexity:** encryption is O(n) in data size; the KMS wrap/unwrap is O(1) per object. **Edge cases that actually break security:** (1) **Never reuse an IV with the same key** — nonce reuse in GCM leaks the auth key and the XOR of plaintexts; generate a fresh random IV every time. (2) GCM has a ~64GB-per-key data limit; rotate DEKs. (3) Decrypt throws `AEADBadTagException` on tampering — treat that as an attack, not a parse error. In production replace the `kek` ops with KMS so the master key never enters JVM heap.

### Q18. [Theory] What is tokenization and how does it differ from encryption? Give a payments example.

**Tokenization** replaces sensitive data with a non-sensitive surrogate (**token**) that has no mathematical relationship to the original — the mapping lives in a secured **token vault**. **Encryption** transforms data with a key such that anyone with the key can reverse it.

```
Encryption:  PAN 4111-1111-1111-1111  --AES+key-->  9f3a... (reversible with key, key is the risk)
Tokenization: PAN 4111-1111-1111-1111  --vault lookup-->  tok_8842  (token is meaningless off-vault)
```

**Payments example (PCI-DSS scope reduction):** a merchant tokenizes the card PAN at the point of capture. Downstream systems (order service, analytics, CRM) store only `tok_8842`, which is worthless if breached and **out of PCI scope**. Only the token vault / payment processor holds the real PAN. Format-preserving tokenization keeps `tok` looking like a 16-digit number so legacy systems don't break. The key win is **scope minimization**: fewer systems touch real cardholder data, so audit cost and breach impact shrink dramatically. Encryption alone doesn't reduce PCI scope because the data is still recoverable wherever the key reaches.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] Define zero-trust architecture precisely. How is it different from a VPN-perimeter model, and what are the core pillars?

**Zero trust** (NIST SP 800-207) is the principle **"never trust, always verify"**: no implicit trust is granted based on network location, ownership of the device, or asset position. Every access request is authenticated, authorized, and encrypted **per request**, using dynamic policy informed by identity, device posture, and risk signals.

The contrast with the legacy **castle-and-moat / VPN** model is fundamental:

```
Perimeter model:                       Zero Trust model:
  [ firewall / VPN ]                     every request independently verified
   trusted interior  ← once in,           identity + device + context → policy → allow/deny
   everything trusted   pivot freely       micro-segmentation, no implicit interior trust
```

Core pillars (the CISA Zero Trust Maturity Model): **Identity** (strong MFA, phishing-resistant), **Devices** (posture/compliance checks), **Networks** (micro-segmentation, encrypt everything), **Applications/Workloads** (per-workload identity, e.g. SPIFFE), and **Data** (classification + protection). A **Policy Decision Point (PDP)** evaluates each request; **Policy Enforcement Points (PEPs)** at every resource enforce it. The shift matters because VPNs collapsed under remote work and cloud — a flat trusted network means one phished laptop owns the data center. Zero trust assumes breach and contains it.

### Q20. [Practical] You're the architect for a multi-tenant SaaS. Design tenant isolation and prevent cross-tenant data leakage.

The core threat is **cross-tenant access** — tenant A reading tenant B's data — which is a catastrophic, trust-ending breach. Defense in depth across layers:

```
              ┌──────────────────────────────────────────┐
Request → JWT (tenant_id claim, signed by IdP)            │
   │         │                                            │
   ▼         ▼                                            │
[Gateway] propagate tenant_id, never trust client-sent id │
   │                                                       │
[Service] tenant context bound to request scope           │
   │                                                       │
[Data]  Row-Level Security (Postgres RLS) OR schema/DB     │
        per tenant + KMS key per tenant (envelope)         │
              └──────────────────────────────────────────┘
```

**Isolation model spectrum (trade-offs):**
- **Pool (shared schema, `tenant_id` column)** — cheapest, densest, but one missing `WHERE tenant_id = ?` leaks everything. Mitigate with **Postgres Row-Level Security** so isolation is enforced by the DB, not application discipline.
- **Bridge (schema per tenant)** — middle ground.
- **Silo (DB/cluster per tenant)** — strongest isolation, used for regulated/enterprise tiers; expensive and operationally heavy.

**Production answer:** start pooled with RLS for the long tail, offer silo for premium/regulated tenants. Derive `tenant_id` **only from the verified token**, never a request parameter or header (that's IDOR at the tenancy level). Add **per-tenant encryption keys** so even a DB dump doesn't yield cross-tenant plaintext, and run automated tests that attempt cross-tenant access in CI. Tag logs/metrics with tenant for forensic isolation. The defining mistake juniors make: relying solely on app-layer `WHERE` clauses — one buggy query or ORM scope and you're on the front page.

### Q21. [Theory] Walk through STRIDE threat modeling on a payment API. How does it drive design?

**STRIDE** (Microsoft) is a structured threat taxonomy; each letter maps to a violated security property and a countermeasure:

| Threat | Violates | Payment API example | Mitigation |
|--------|----------|--------------------|------------|
| **S**poofing | Authenticity | Attacker impersonates a merchant | mTLS, OIDC, strong API-key auth |
| **T**ampering | Integrity | Altering amount in transit/at rest | TLS 1.3, request signing (HMAC), AEAD |
| **R**epudiation | Non-repudiation | "I never authorized that refund" | Signed, immutable audit logs |
| **I**nformation disclosure | Confidentiality | Leaking PANs in logs | Tokenization, field encryption, log redaction |
| **D**enial of service | Availability | Flooding the charge endpoint | Rate limiting, WAF, idempotency |
| **E**levation of privilege | Authorization | User calling admin refund-all | Least privilege, deny-by-default AuthZ |

**Process:** draw a **Data Flow Diagram**, mark **trust boundaries** (where data crosses privilege levels — client→gateway, service→DB, your-system→processor), then enumerate STRIDE threats at each boundary, rate them (DREAD or CVSS), and assign mitigations. It drives design by making security **requirements** rather than afterthoughts: the threat model says "tampering at the charge endpoint" → you add request signing and idempotency keys *before* coding. Do it during design review; re-do it when architecture changes. In 2026 many teams pair STRIDE with **threat modeling as code** (e.g., pytm, OWASP Threat Dragon) so the model lives in version control beside the system.

### Q22. [Practical] Design key rotation for a system encrypting PII at rest, with zero downtime and the ability to re-encrypt old data.

**Goal:** rotate keys regularly (compliance + limiting exposure of any single key) without downtime or a giant re-encrypt-everything outage. Envelope encryption makes this tractable.

```
Each record:  [ ciphertext | wrapped_DEK | kek_version | iv ]

Rotation (KEK rotation — cheap, instant):
  1. KMS generates KEK v2; v1 stays available for decrypt only.
  2. New writes wrap their DEK with KEK v2.
  3. Reads: dispatch on kek_version → decrypt with the right KEK.
  4. Background job lazily re-wraps old DEKs with v2 (no data re-encryption!).

DEK rotation (true re-encryption — only when a DEK is compromised or for crypto-shredding):
  background, batched, idempotent re-encrypt; track per-record state.
```

**Why this works:** rotating the **KEK** only re-wraps tiny DEKs — O(number of DEKs), not O(data volume) — so it's near-instant. You rarely need to touch the bulk ciphertext. Store a **key version** with every record so old and new coexist; decommission an old KEK version only after the lazy re-wrap completes.

**Production considerations:** make the re-encryption job **idempotent and resumable** (it will be interrupted on petabyte datasets), throttle it to protect KMS quota and DB load, keep old KEKs in `decrypt-only` state during transition, and emit metrics on "% records on current key version." A powerful side benefit: **crypto-shredding** — to "delete" a tenant's data for GDPR, destroy their DEK/KEK and the ciphertext is permanently unrecoverable, even from backups. Trade-off: a versioning/dispatch layer and a long-running migration job add complexity, but it's the only way to rotate at scale without downtime.

### Q23. [Coding] Implement an HMAC-signed request scheme (like AWS SigV4-lite) to prevent tampering and replay.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public class RequestSigner {

    private static final long MAX_SKEW_SECONDS = 300; // 5-min replay window

    /** Canonical string: method, path, timestamp, body hash — order & content must be deterministic. */
    private static String canonicalString(String method, String path, long ts, byte[] body) {
        String bodyHash = sha256Hex(body);
        return method + "\n" + path + "\n" + ts + "\n" + bodyHash;
    }

    public static String sign(String secret, String method, String path, long ts, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(canonicalString(method, path, ts, body)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Server side: verify signature, freshness (anti-replay), and nonce uniqueness. */
    public static boolean verify(String secret, String method, String path, long ts,
                                 byte[] body, String providedSig, NonceStore nonces, String nonce) {
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) return false;   // stale/future → reject (replay defense)
        if (!nonces.firstUse(nonce, MAX_SKEW_SECONDS)) return false; // each nonce usable once
        String expected = sign(secret, method, path, ts, body);
        // constant-time compare to avoid timing leaks
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSig.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Redis-backed in prod: SET nonce NX EX ttl. */
    interface NonceStore { boolean firstUse(String nonce, long ttlSeconds); }
}
```

**Design rationale:** signing a canonical string (including a **hash of the body**) detects any tampering with method, path, or payload — STRIDE's *Tampering* mitigation. The **timestamp + nonce** combination defeats **replay attacks**: even a perfectly valid captured request is rejected once outside the 5-minute window or once its nonce is seen. **Time:** O(n) over the body for hashing. **Space:** O(active nonces) in the store. **Edge cases:** clock skew (allow a small window, require NTP), nonce store outage (fail closed for writes), and HMAC key rotation (support a key-id header so you can roll keys without breaking in-flight clients). Always use constant-time comparison on the signature.

### Q24. [Theory] What is mutual TLS, how does the handshake establish identity, and what are SPIFFE/SPIRE for?

**mTLS** extends ordinary TLS so **both** sides present and validate X.509 certificates — the server proves its identity to the client *and* the client proves its identity to the server. In TLS 1.3 the abbreviated handshake:

```
Client                                   Server
  | --- ClientHello (supported groups) -->|
  | <-- ServerHello, Certificate,         |
  |     CertificateRequest, Finished -----|
  | --- Certificate (client cert),        |
  |     CertificateVerify (signs handshake|
  |       with client private key),       |
  |     Finished ------------------------>|
  |  Both verify the other's cert chains  |
  |  to a trusted CA; identity = cert SAN |
```

Identity is established because each party signs handshake data with the **private key** corresponding to its certificate; the peer validates the chain up to a trusted CA and reads the identity from the cert's **SAN**. You can't forge it without the private key.

**SPIFFE/SPIRE** solve the operational nightmare of mTLS at scale: **who issues, distributes, and rotates thousands of short-lived service certs?** SPIFFE defines a universal workload identity (`spiffe://trust-domain/ns/payments/sa/orders`) embedded in an **SVID** (an X.509 cert or JWT). **SPIRE** is the runtime that **attests** workloads (proves a pod really is `orders` via node + workload attestation) and auto-issues/rotates short-lived SVIDs (minutes). This replaces hardcoded API keys with cryptographically attested, auto-rotating identity — the foundation of workload-level zero trust and what powers service-mesh mTLS.

### Q25. [Practical] Design network segmentation for a regulated workload (e.g., handling health/financial data) across cloud and Kubernetes.

The goal is to **minimize lateral movement and shrink the compliance/audit boundary** (HIPAA/PCI). Segment by sensitivity, default-deny everywhere.

```
        ┌─ Public subnet ─┐  ┌─ App subnet (private) ─┐  ┌─ Data subnet (private) ─┐
Internet→ ALB / WAF / NAT  →   app services (no PII at rest) →  DB / KMS / token vault
        └─────────────────┘  └────────────────────────┘  └─────────────────────────┘
            DMZ                  egress via NAT only          NO internet egress
                                 default-deny SGs             accessible only from app SG
```

**Layers:**
- **VPC / subnet tiers:** public (LB/WAF only), private app, private data. The data tier has **no route to the internet** (no IGW/NAT), so even a compromised DB can't exfiltrate outbound.
- **Security groups / NACLs:** reference-based, least-privilege — app SG may reach DB SG on 5432 only; nothing else can. Default deny.
- **Kubernetes:** default-deny **NetworkPolicies**, then explicitly allow `orders → payments`. Put the regulated workload in its own **namespace/node pool** (or even a separate cluster) so it isn't co-tenant with general services.
- **Egress control:** all outbound through an egress proxy/NAT with an **allowlist** of destinations — blocks SSRF exfil and C2 callbacks.
- **Private connectivity:** reach KMS/S3 via **VPC endpoints (PrivateLink)** so traffic never traverses the public internet.

**Trade-offs:** tight segmentation increases operational friction (every new dependency needs an explicit rule) and can cause confusing connectivity bugs — invest in good observability (flow logs, mesh telemetry). But for regulated data this is non-negotiable: segmentation is what lets you tell an auditor "the cardholder data environment is these three subnets, and here are the controls at every boundary."

### Q26. [Theory] Compare session-cookie auth, stateless JWTs, and opaque tokens with introspection. When does each win?

| Aspect | Session cookie | Stateless JWT | Opaque token + introspection |
|--------|---------------|---------------|------------------------------|
| State | Server-side session store | None (self-contained) | Server-side (auth server) |
| Validation | Lookup per request | Verify signature locally | Call `/introspect` per request |
| Revocation | Instant (delete session) | Hard (must wait for expiry) | Instant |
| Scalability | Needs shared store | Excellent (no lookup) | Network hop per check |
| Leakage impact | Bounded by session | Valid until `exp` | Bounded |

- **Session cookies** win for classic server-rendered web apps and anywhere **instant revocation** matters (banking "log out everywhere"); pair with `HttpOnly`, `Secure`, `SameSite`.
- **Stateless JWTs** win for high-throughput microservices and APIs where you can't afford a lookup per call — the cost is weak revocation, mitigated by **short TTLs (5–15 min) + refresh tokens**.
- **Opaque tokens + introspection** win when you need both revocation *and* not exposing claims to clients (common in OAuth deployments via an API gateway that introspects once and forwards a verified context).

The mature hybrid: **short-lived JWTs for service-to-service** + an **emergency `jti` denylist** for revocation + **opaque refresh tokens** rotated on use. There's no universally right answer — it's a revocation-vs-scalability trade-off, and the senior signal is naming that trade-off explicitly.

---

## 🔴 Expert (15+ yrs)

### Q27. [Behavioral] You discover a critical auth vulnerability in production that's likely been exploitable for months. Walk me through your first 48 hours as the responsible architect.

This is about **leadership under pressure, honesty, and process** — not heroics.

- **Contain without tipping off / without taking down the business.** Assess: is active exploitation happening (check logs/SIEM)? If yes, mitigate immediately (feature flag the path, tighten WAF rule, force re-auth) while preserving forensic evidence. Decide deliberately between "patch quietly" and "emergency shutdown" based on active-exploitation evidence.
- **Activate the incident process, don't freelance.** Declare an incident, pull in security, legal, and comms early. Assign a single incident commander (even if it isn't me) so technical work and decision-making don't collide.
- **Preserve evidence before remediating** — snapshot logs and affected systems; you can't reconstruct the breach scope after you've wiped it.
- **Scope the blast radius:** what data, which tenants, how far back. This drives the legal/regulatory clock — **GDPR's 72-hour breach notification** and similar mean comms can't wait for a perfect root cause.
- **Communicate honestly.** I'd push for transparent disclosure to affected customers; trying to bury it is both unethical and usually worse for the business when it surfaces. Internally, no blame on the engineer — focus on the system that let it ship.
- **Then the durable fix and blameless postmortem:** root cause, why our threat modeling/tests missed it, and concrete controls (e.g., authz integration tests in CI) so the class of bug can't recur.

The signal an interviewer wants: you balance speed with evidence, you escalate rather than hero-code alone, you know the regulatory clock, and you treat it as a systems/process failure — not a person's fault.

### Q28. [Theory] How do you build a secure SDLC and "shift left" without grinding delivery to a halt? Where do you put each control?

A secure SDLC embeds security into every phase so vulnerabilities are caught when they're cheapest (a design flaw caught in review costs orders of magnitude less than one caught in prod).

```
Design ──► Code ──► Build/CI ──► Deploy ──► Runtime
  │          │         │           │           │
threat     SAST,     SCA (deps),  IaC scan,   WAF, RASP,
modeling,  secret    container    signed      anomaly
sec reqs   scanning  image scan,  artifacts   detection,
(STRIDE)   in IDE/PR DAST, SBOM   (SLSA)       SIEM, bug bounty
```

- **Design:** threat modeling, security requirements, paved-road architecture.
- **Code:** SAST and secret scanning as pre-commit/PR checks; secure-by-default frameworks.
- **Build/CI:** software composition analysis (CVEs in dependencies — the post-Log4Shell mandate), container scanning, **SBOM** generation, DAST against ephemeral envs, **artifact signing + provenance (SLSA, Sigstore/cosign)** to defend the supply chain.
- **Runtime:** WAF, RASP, runtime anomaly detection, SIEM, and a **bug bounty / responsible disclosure** program.

**The "without halting delivery" part is the real expertise:** (1) **paved roads** — golden libraries/templates that are secure by default so devs don't make security decisions ad hoc; (2) **break the build only on high-confidence, high-severity findings**, surface the rest as non-blocking; (3) **risk-tier** so a marketing page doesn't get the same gate as the payments service; (4) **security champions** embedded in teams to scale the security org. Tooling that drowns devs in false positives gets ignored or disabled — friction *is* a security risk because it pushes teams to route around controls. As of 2026, frameworks like the **SLSA** supply-chain levels and SBOM mandates (US EO 14028 lineage) make build-time provenance a baseline expectation, not a nice-to-have.

### Q29. [Theory] How does post-quantum cryptography change your encryption architecture, and what should you do today?

Large quantum computers would break the asymmetric crypto underpinning TLS, code signing, and key exchange: **Shor's algorithm** defeats RSA and ECC. Symmetric crypto (AES-256) and hashes are far more resilient — **Grover's algorithm** only halves effective strength, so AES-256 stays ~128-bit secure.

**The immediate threat is "harvest now, decrypt later":** adversaries record encrypted traffic today to decrypt once quantum is viable. So **long-lived secrets (medical records, state secrets, identity data) are already at risk**, even if quantum is years out.

What to do now (2026):
- **Adopt the NIST-standardized PQC algorithms** finalized in 2024: **ML-KEM (Kyber)** for key encapsulation and **ML-DSA (Dilithium)** / **SLH-DSA (SPHINCS+)** for signatures.
- **Deploy hybrid key exchange** (classical ECDHE **+** ML-KEM) in TLS — major browsers/CDNs already support `X25519MLKEM768`. Hybrid means you're no worse off if either algorithm has a flaw.
- **Build crypto-agility:** abstract crypto behind interfaces and key-version metadata so you can swap algorithms without rewrites — most orgs' real weakness is hardcoded crypto, not the algorithm choice.
- **Inventory your cryptography** (a "cryptographic bill of materials") to know where RSA/ECC live, then prioritize migrating long-lived-secret systems first.

The architectural lesson: the specific algorithm matters less than **agility** — assume any algorithm will eventually need replacing, and design so that swap is a config change, not a re-architecture.

### Q30. [Practical] You're consolidating authn/authz across 200 microservices accreted over a decade — mixed JWTs, API keys, and bespoke session logic. Design the migration.

This is a brownfield identity-platform problem; the failure mode is a multi-year "big bang" that never lands. Strategy: **externalize, standardize, strangle.**

```
Target architecture:
  Clients → [ API Gateway / mesh ]  ← authN (validate OIDC tokens), coarse authZ
                   │ injects verified identity context (mTLS / signed headers)
                   ▼
            [ services ]  → ask PDP "may subject do action on resource?"
                   │
            [ Policy Decision Point: OPA/Cedar ]  ← centralized, version-controlled policy
```

**Approach:**
1. **Stand up a central IdP** (Keycloak/Okta/Entra) speaking **OIDC**; make it the single source of identity. Federate existing user stores into it rather than migrating users in one shot.
2. **Push authN to the edge** (gateway/mesh): it validates tokens once and injects a **verified, signed identity context** downstream. Services stop re-implementing token parsing.
3. **Externalize authZ to a PDP** (OPA/Rego or AWS Cedar). Services call "may I?"; policy lives in version control, reviewed and tested like code.
4. **Strangler pattern, not big bang:** route new and migrated services through the new path; legacy services keep working behind an adapter that translates old API keys/sessions into the new identity context. Migrate service-by-service, highest-risk or highest-traffic first depending on goals.
5. **mTLS + SPIFFE** for service-to-service identity so internal calls aren't "trusted because internal."

**Trade-offs & realities:** a central IdP/PDP is a new **availability-critical dependency** — it must be HA, cached, and degrade gracefully (cache last-known-good policy; decide fail-open vs fail-closed per endpoint). Expect 12–24 months. The biggest risk isn't technical — it's **org alignment**: 200 services means dozens of teams, so you need executive sponsorship, a migration scorecard, and security champions, or the long tail never migrates. Measure progress ("% traffic through the new identity plane") and ruthlessly **decommission** old paths or you'll run both forever.

### Q31. [Behavioral] Security and product velocity are in tension. A VP wants to ship a feature you believe is a serious risk. How do you handle it?

The expert move is to **make risk a business decision with a clear owner — not to be the "department of no."**

- **Quantify the risk in business terms**, not jargon: "this exposes customer PII to cross-tenant access; a breach means GDPR fines up to 4% of revenue, mandatory 72-hour disclosure, and likely churn" — far more persuasive than "it's insecure."
- **Offer options, not a veto.** Present the risky path, a mitigated path (maybe a phased launch, a feature flag, behind extra auth, or a scoped beta), and the cost/timeline of each. Give the VP a real decision.
- **Right-size the control to the risk.** If it's genuinely low-impact, I might accept it with monitoring — credibility comes from *not* crying wolf on everything. Save the hard line for genuine show-stoppers.
- **If they still want to ship a serious risk**, document it as an explicit, signed **risk acceptance** with an owner and a remediation date. That converts an argument into accountability and an audit trail.
- **Escalate only for true show-stoppers** (legal/regulatory exposure, likely catastrophic breach) — and even then through the risk framework, calmly, with data.

The signal: a staff/principal security architect partners with the business, translates technical risk into impact, offers paths to "yes," and reserves hard escalation for the rare genuine emergency. Being reflexively obstructive destroys the influence you need when it actually matters.

### Q32. [Coding] Implement a fail-closed authorization check with a policy decision point and a safe cache. Show the subtle bugs you're avoiding.

```java
import java.time.Duration;
import java.util.concurrent.*;

public class AuthorizationService {

    record Decision(boolean allowed, long evaluatedAtMs) {}
    record Request(String subject, String action, String resource, String tenantId) {}

    private final PolicyDecisionPoint pdp;                         // remote OPA/Cedar
    private final ConcurrentHashMap<Request, Decision> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = Duration.ofSeconds(30).toMillis();

    public AuthorizationService(PolicyDecisionPoint pdp) { this.pdp = pdp; }

    public boolean isAllowed(Request req) {
        // BUG AVOIDED #1: tenant must be part of the cache key, else cross-tenant cache poisoning.
        Decision cached = cache.get(req);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.evaluatedAtMs()) < CACHE_TTL_MS) {
            return cached.allowed();
        }
        try {
            Decision fresh = pdp.evaluate(req);                   // remote call
            // BUG AVOIDED #2: never cache a deny forever; short TTL so grants propagate.
            cache.put(req, new Decision(fresh.allowed(), now));
            return fresh.allowed();
        } catch (Exception e) {
            // BUG AVOIDED #3: FAIL CLOSED. A PDP outage must DENY, not allow.
            // (Optionally use a still-fresh cached ALLOW for availability — but never default-allow.)
            if (cached != null && (now - cached.evaluatedAtMs()) < CACHE_TTL_MS * 4) {
                return cached.allowed();                          // graceful degradation, bounded staleness
            }
            return false;                                         // deny by default
        }
    }

    interface PolicyDecisionPoint { Decision evaluate(Request req); }
}
```

**The subtle, security-critical bugs this guards against:**
1. **Incomplete cache key** — omitting `tenantId`/`resource` lets one principal's decision leak to another. The cache key must be the *full* authorization context.
2. **Default-allow on error** — the single most dangerous authz bug. A `try/catch` that returns `true` (or an uninitialized `boolean` defaulting effectively to permissive) turns a PDP blip into a full authz bypass. **Always fail closed.**
3. **Stale grants/denials** — caching forever means revocations never take effect and new grants don't propagate; bound staleness with a short TTL.
4. **Caching errors as decisions** — never persist a failed evaluation as a deny *or* allow; only cache real PDP results.

**Complexity:** O(1) cache hit; O(network) on miss. **Space:** O(distinct requests) — add an eviction/size bound (e.g., Caffeine with maximum size) in production or the map grows unbounded (a DoS via cache-key explosion). The graceful-degradation branch is a deliberate availability-vs-security trade-off: serving a slightly stale *previously-evaluated* decision is acceptable; defaulting to allow is never.

### Q33. [Theory] Compare detective, preventive, and corrective controls, and explain "assume breach." How does this shape monitoring architecture?

Security controls fall into three complementary classes:
- **Preventive** — stop the bad thing (firewalls, authn, encryption, input validation). Necessary but never sufficient; a determined adversary eventually gets through.
- **Detective** — notice it happening (SIEM, IDS, anomaly detection, audit logs, EDR). 
- **Corrective** — limit/recover from impact (automated isolation, key revocation, backups, runbooks).

**"Assume breach"** is the zero-trust mindset that says: *prevention will fail eventually, so design as if the attacker is already inside.* That fundamentally reweights investment toward detection and containment — because dwell time (how long an attacker operates undetected, historically months) is the real damage multiplier.

How it shapes monitoring architecture:
```
Sources → logs/metrics/traces/audit → SIEM (correlation) → detection rules / UEBA
                                              │
                                      SOAR (automated response) → isolate / revoke / page
```
- **Centralized, tamper-evident logging** (append-only, separate trust domain) so an attacker can't erase their tracks — covers STRIDE *Repudiation*.
- **Behavioral/UEBA detection**, not just signatures, to catch novel attacks and insider threats.
- **Micro-segmentation + least privilege** as containment so a breach in one cell can't reach the crown jewels.
- **Automated response (SOAR)** to compress mean-time-to-contain from hours to seconds.
- **Honeypots/canary tokens** as high-signal tripwires.

The expert framing: spend on prevention *and* detection *and* containment, because the question is never "will we be breached?" but "how fast do we detect it and how small is the blast radius?"

---

## ✅ Key Takeaways

- **Zero trust = "never trust, always verify"**: authenticate, authorize, and encrypt every request based on identity and context, never network location. Assume breach and contain blast radius.
- **Defense in depth**: layer CDN/scrubbing → WAF → gateway → app → data controls. No single control is allowed to be the only thing standing between attacker and crown jewels.
- **Identity is the new perimeter**: OIDC for authN, OAuth2 + PKCE for delegation, mTLS/SPIFFE for workload identity, externalized PDP (OPA/Cedar) for authZ. Derive tenant/identity from verified tokens, never client input.
- **BOLA/IDOR is the #1 real API breach** — check object ownership on every request; WAFs won't save you from authz logic bugs.
- **Envelope encryption + KMS/HSM** gives performant encryption, cheap KEK rotation, crypto-shredding, and master keys that never leave hardware. Tokenization shrinks PCI/compliance scope by removing real data from systems.
- **Fail closed** on authz and security-critical paths; fail open only on deliberately chosen availability paths.
- **Shift left with paved roads**, not friction: threat model in design, SAST/SCA/secret-scanning in CI, SBOM + signed artifacts (SLSA) for supply chain, runtime detection + SIEM after deploy.
- **Crypto-agility now** for the post-quantum transition: hybrid ML-KEM key exchange, algorithm abstraction, and a crypto inventory — "harvest now, decrypt later" already threatens long-lived secrets.

## ⚠️ Common Pitfalls

- Conflating authentication with authorization — a valid login does not imply permission; check authz on every protected operation.
- Trusting the network interior ("it's behind the VPN/VPC, so it's safe") — the root cause of catastrophic lateral movement.
- Using `alg: none` or letting the JWT header dictate the verification algorithm (algorithm-confusion bypass); not validating `aud`/`iss`/`exp`.
- Reusing a GCM IV/nonce with the same key — silently destroys confidentiality and leaks the auth key.
- Long-lived static credentials (API keys, IAM access keys) in code/CI — the answer is workload identity and short-lived dynamic secrets, not "scan harder."
- Relying solely on application-layer `WHERE tenant_id = ?` for multi-tenant isolation — one missed clause leaks everything; enforce with RLS and per-tenant keys.
- Default-allow on authorization errors, and authz caches missing the full context (tenant/resource) in the key.
- Treating security tooling as a gate that breaks every build — drowning devs in false positives makes them route around controls, which is itself a vulnerability.
- Hardcoding cryptographic algorithms with no version/abstraction layer, making the eventual PQC (or any algorithm) migration a rewrite.
- Storing secrets/PII in plaintext logs; not redacting tokens, PANs, and credentials.

## 📚 Further Reading

- **NIST SP 800-207, *Zero Trust Architecture*** — the authoritative definition and reference architecture.
- **OWASP API Security Top 10 (2023)** and the **OWASP Cheat Sheet Series** (Authentication, Authorization, JWT, Transport Layer, Secrets Management).
- **Adam Shostack, *Threat Modeling: Designing for Security*** — the definitive STRIDE/DFD reference.
- **OAuth 2.1 draft & RFC 8252 / RFC 7636 (PKCE)**, plus the **OpenID Connect Core** spec.
- **Google's Zanzibar paper** (relationship-based authorization) and the **OPA/Rego** and **AWS Cedar** policy docs.
- **NIST PQC standards** — FIPS 203 (ML-KEM), 204 (ML-DSA), 205 (SLH-DSA) — and the **SLSA** supply-chain framework.
