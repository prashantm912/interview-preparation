# JWT (Advanced Patterns)

A deep, interview-focused reference on JSON Web Tokens: structure, signing and encryption, validation, key rotation, refresh-token rotation with reuse detection, revocation, and the security trade-offs that separate a junior implementation from a production-grade one. Examples are in Java (Spring Boot 3 / `java-jwt` & Nimbus where relevant), current through 2026.

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

### Q1. [Theory] What is a JWT and what are its three parts?

A JWT (JSON Web Token, RFC 7519) is a compact, URL-safe, self-contained token that encodes a set of *claims* as JSON. The most common serialization is a JWS (JSON Web Signature) consisting of three Base64URL-encoded segments separated by dots: `header.payload.signature`.

- **Header** — declares the token type (`typ: "JWT"`) and the signing algorithm (`alg`, e.g. `HS256`, `RS256`). It may also carry a key id (`kid`).
- **Payload** — the claims: standard registered claims (`sub`, `exp`, `iss`, …) plus any custom application claims (roles, tenant id).
- **Signature** — a MAC or digital signature over `base64url(header) + "." + base64url(payload)`, proving integrity and authenticity.

The crucial point for juniors: the header and payload are **encoded, not encrypted**. Anyone can decode them. The signature does not hide data; it only guarantees it has not been tampered with.

```
eyJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0   <- header  (Base64URL)
.
eyJzdWIiOiIxMjMiLCJleHAiOjE3...        <- payload (Base64URL)
.
NHVaYe26MbtOYhSKkoKYdFVomg4i...        <- signature
```

### Q2. [Theory] Is the JWT payload encrypted? Where should I NOT put sensitive data?

No. A standard signed JWT (JWS) is only Base64URL-encoded, which is trivially reversible — paste it into jwt.io or run `echo <payload> | base64 -d`. Never put passwords, full credit-card numbers, PII you wouldn't expose, or secrets in the payload. If you genuinely need confidentiality of the claims themselves, you must use **JWE** (JSON Web Encryption) which encrypts the payload. In practice most systems keep tokens as signed-only JWS and simply avoid putting secrets inside them.

### Q3. [Theory] What are the standard registered claims (exp, nbf, iat, aud, iss, sub, jti)?

These are the reserved claims defined by RFC 7519, each three letters for compactness:

| Claim | Meaning | Validation role |
|-------|---------|-----------------|
| `iss` | Issuer — who created the token | Must match an expected issuer |
| `sub` | Subject — the principal (user id) | Identity of the caller |
| `aud` | Audience — intended recipient(s) | Reject tokens not meant for you |
| `exp` | Expiration time (epoch seconds) | Reject if `now > exp` |
| `nbf` | Not before | Reject if `now < nbf` |
| `iat` | Issued at | Age checks, freshness |
| `jti` | JWT ID — unique identifier | De-dup / revocation / replay defense |

`exp`, `nbf`, and `iat` are NumericDate values (seconds since the Unix epoch, UTC). Always validate `exp` *and* `aud` *and* `iss` — skipping `aud` is a classic way to accept a token minted for a different service.

### Q4. [Practical] How do you create and verify a JWT in Java?

Using `com.auth0:java-jwt`, an HS256 example:

```java
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Instant;
import java.util.Date;

Algorithm alg = Algorithm.HMAC256(secret); // secret: >= 256-bit random

// Create
String token = JWT.create()
    .withIssuer("https://auth.example.com")
    .withSubject("user-123")
    .withAudience("orders-api")
    .withIssuedAt(Date.from(Instant.now()))
    .withExpiresAt(Date.from(Instant.now().plusSeconds(900))) // 15 min
    .withJWTId(java.util.UUID.randomUUID().toString())
    .sign(alg);

// Verify — verification enforces signature + exp + iss + aud
DecodedJWT decoded = JWT.require(alg)
    .withIssuer("https://auth.example.com")
    .withAudience("orders-api")
    .acceptLeeway(30) // 30s clock skew tolerance
    .build()
    .verify(token);

String userId = decoded.getSubject();
```

The single most important habit: **let the library verify**, and configure it with the expected issuer/audience. Never decode-without-verify and then trust claims.

### Q5. [Theory] What is the difference between authentication and authorization, and where does a JWT fit?

Authentication answers "who are you?"; authorization answers "what are you allowed to do?". A JWT issued after a successful login *asserts* an authenticated identity (`sub`) and can also carry authorization data (roles, scopes). A resource server reads those claims to make access decisions without calling back to the auth server. The JWT is the portable proof; the act of validating its signature and claims is how a downstream service re-establishes trust in that proof.

### Q6. [Practical] Where do access tokens typically come from in an OAuth2/OIDC flow?

In OpenID Connect, after the user authenticates, the authorization server returns an **ID token** (a JWT describing the user, meant for the client) and an **access token** (often a JWT, meant for resource servers, carrying scopes). The client then sends the access token on each API call via `Authorization: Bearer <token>`. The resource server validates it — usually by fetching the issuer's public keys (JWKS) and checking the signature, `iss`, `aud`, `exp`, and required scopes. The ID token should be consumed by the client, not used as an API access token; conflating them is a common mistake.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] HS256 vs RS256 vs ES256 — when do you use each?

These are the three workhorse JWS algorithms:

- **HS256** — HMAC with SHA-256. *Symmetric*: the same secret signs and verifies. Fast and simple, but every party that verifies must hold the signing secret, so it doesn't scale to many independent verifiers and is risky in microservices (any service that can verify can also forge).
- **RS256** — RSA signature with SHA-256. *Asymmetric*: a private key signs, a public key verifies. The auth server keeps the private key; resource servers fetch the public key (via JWKS) and can only verify, never forge. This is the default for OIDC and multi-service systems. Larger signatures (~2048-bit keys → 256-byte signatures).
- **ES256** — ECDSA with the P-256 curve and SHA-256. Also asymmetric, but with much smaller keys/signatures and faster signing for equivalent security (256-bit EC ≈ 3072-bit RSA). Increasingly the recommended default in 2026 for new systems because of compactness and performance. Caveat: ECDSA requires a unique per-signature nonce; a bad RNG leaks the private key, so use a vetted library.

Rule of thumb: single trust boundary, both ends trusted → HS256 is fine. Multiple verifiers or third parties → asymmetric (ES256 preferred, RS256 for compatibility).

```
HS256 (symmetric)            RS256 / ES256 (asymmetric)
+---------+   secret          +---------+  private key (sign)
| Issuer  |---------+         | Issuer  |------+
+---------+         |         +---------+      |
                    v                          v
+---------+   secret          +---------+  public key (verify, via JWKS)
| Verifier|<--------+         | Verifier|<-----+
+---------+ same key          +---------+ different key — cannot forge
```

### Q8. [Theory] Explain the `alg=none` / algorithm-confusion attacks and how to defend.

Two classic, devastating JWT attacks exploit trusting the token's own header:

1. **`alg: none`** — The attacker strips the signature and sets `alg` to `none` (or `None`, `NONE`). A naive verifier that honors the header's algorithm accepts an unsigned token, letting the attacker forge arbitrary claims. Defense: never let the incoming header choose the algorithm. Pin the expected algorithm server-side and reject `none`.

2. **Algorithm confusion (RS256 → HS256)** — The server is configured for RS256 with a public key. The attacker changes `alg` to `HS256` and signs the token using the *public key as the HMAC secret*. A verifier that picks the algorithm from the header will run HMAC-SHA256 with the (publicly known) RSA public key and validate the forgery. Defense: bind the verification key to a single, server-chosen algorithm family.

```java
// SAFE: algorithm is fixed by the verifier, not read from the token header.
Algorithm alg = Algorithm.RSA256(publicKey, null); // verify-only
JWT.require(alg).withIssuer(ISS).build().verify(token);
// java-jwt throws AlgorithmMismatchException if header alg != RSA256.
```

The meta-lesson: the JWT header is attacker-controlled input. Treat `alg` and `kid` as hints to be validated against an allow-list, never as instructions.

### Q9. [Theory] What is the complete server-side validation checklist for an incoming JWT?

A robust validator performs, in order:

1. **Parse** the three segments; reject malformed tokens.
2. **Resolve the key** — select the verification key by `kid` from a trusted JWKS, *not* by trusting embedded keys (`jwk`/`jku`/`x5u` headers are dangerous if honored).
3. **Verify the signature** using a *server-pinned* algorithm (defends `alg=none` and confusion).
4. **`exp`** — reject expired (with small leeway, ≤ 60s).
5. **`nbf`** — reject not-yet-valid.
6. **`iss`** — must equal the expected issuer exactly.
7. **`aud`** — must contain this service's audience.
8. **`iat`** — optional freshness / max-age checks.
9. **Required claims/scopes** — e.g. `scope` contains `orders:write`.
10. **Revocation** — if applicable, check `jti`/token-version against a denylist or cache.

Failing to validate `aud`/`iss` lets a token minted for service A be replayed against service B (a confused-deputy problem).

### Q10. [Coding] Implement an `alg=none`-proof validator with `kid`-based key selection.

**Problem:** Validate an RS256 JWT against a set of public keys keyed by `kid`, enforcing issuer, audience, expiry, and rejecting any token whose header algorithm isn't RS256.

```java
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

public class JwtValidator {
    private final Map<String, RSAPublicKey> keysByKid; // from JWKS
    private final String expectedIssuer;
    private final String expectedAudience;

    public JwtValidator(Map<String, RSAPublicKey> keys, String iss, String aud) {
        this.keysByKid = keys;
        this.expectedIssuer = iss;
        this.expectedAudience = aud;
    }

    public DecodedJWT validate(String token) {
        DecodedJWT unverified = JWT.decode(token);     // header only, NOT trusted yet
        String kid = unverified.getKeyId();
        RSAPublicKey key = keysByKid.get(kid);
        if (key == null) throw new JWTVerificationException("Unknown kid: " + kid);

        // Algorithm is pinned to RS256 here — header alg cannot downgrade it.
        Algorithm alg = Algorithm.RSA256(key, null);
        return JWT.require(alg)
                  .withIssuer(expectedIssuer)
                  .withAudience(expectedAudience)
                  .acceptLeeway(30)                    // clock skew
                  .build()
                  .verify(token);                      // throws on any failure
    }
}
```

- **Time:** O(1) for key lookup (hash map) + signature verify cost (RSA verify is fast, ~µs–ms). **Space:** O(K) for K keys.
- **Edge cases:** missing `kid` → reject; unknown `kid` → reject (could be a rotated-out key or forgery); header `alg=none`/`HS256` → `AlgorithmMismatchException`; expired/wrong audience → `JWTVerificationException`. Never fall back to "verify with any key" when `kid` is absent.

### Q11. [Theory] What is JWKS and how does key rotation work?

JWKS (JSON Web Key Set, RFC 7517) is a JSON document published by the issuer at a well-known URL (e.g. `https://issuer/.well-known/jwks.json`) listing its public keys, each with a `kid`. Resource servers fetch and cache this set, then select the right key by the token's `kid`.

**Rotation** lets you change signing keys without downtime:

```
Phase 1 (steady):     JWKS = { kid=k1 }            tokens signed with k1
Phase 2 (overlap):    JWKS = { kid=k1, kid=k2 }    new tokens use k2; old k1 tokens still verify
Phase 3 (cutover):    issuer signs only with k2
Phase 4 (retire):     JWKS = { kid=k2 }            after max token TTL, drop k1
```

The overlap window must exceed the longest-lived access token so in-flight tokens still validate. Verifiers cache JWKS but must **refresh on unknown `kid`** (with rate limiting to avoid a thundering herd / DoS against the JWKS endpoint). Caching should honor `Cache-Control`/`max-age`. Spring Security's `NimbusJwtDecoder` does this automatically when configured with `jwk-set-uri`.

### Q12. [Practical] How do you wire JWT validation in Spring Boot 3 / Spring Security 6?

Spring Boot 3 (Jakarta namespace, Spring Security 6) makes resource-server JWT validation declarative:

```yaml
# application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com   # discovers jwks-uri + validates iss
          audiences: orders-api                    # SB 3.1+ validates aud natively
```

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/admin/**").hasAuthority("SCOPE_admin")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

Differences vs Spring Boot 2 / Security 5: package moved from `javax.*` to `jakarta.*`; the lambda DSL is now the only supported style (the old `.and()` chaining and `WebSecurityConfigurerAdapter` are removed). `issuer-uri` triggers OIDC discovery so you don't hand-configure the JWKS URL, and audience validation became a first-class property rather than a custom `OAuth2TokenValidator` you wrote yourself.

### Q13. [Theory] Stateless vs stateful sessions — what does JWT actually buy you, and what does it cost?

A **stateful** session stores an opaque session id in a cookie; the server looks up session data in a store (Redis/DB) on every request. A **stateless** JWT carries the claims itself, so the server can authorize without a lookup.

| Dimension | Stateful (session id) | Stateless (JWT) |
|-----------|----------------------|-----------------|
| Server lookup per request | Yes (store hit) | No (verify locally) |
| Horizontal scaling | Needs shared store | Trivial — no shared state |
| Instant revocation | Easy (delete row) | Hard — token valid until `exp` |
| Token size on wire | Small id | Larger (claims) |
| Claim freshness | Always current | Stale until refresh |

The honest framing in an interview: JWTs trade **revocability and freshness** for **statelessness and scale**. The classic mistake is treating a JWT like a session you can instantly kill. If you need immediate revocation, you either reintroduce state (a denylist) or use very short TTLs — both of which erode the "stateless" benefit. Many mature systems land on a hybrid: short-lived stateless access tokens + a stateful refresh-token store.

### Q14. [Practical] Where should tokens be stored in a browser — cookie vs localStorage? Discuss XSS and CSRF.

This is a real production decision with no free lunch:

- **`localStorage` / `sessionStorage`** — JavaScript-accessible, so any XSS that runs script can read and exfiltrate the token. Not vulnerable to CSRF (the browser doesn't auto-attach it), but XSS exposure is severe because a stolen bearer token is replayable anywhere.
- **`HttpOnly` cookie** — not readable by JS, so XSS can't *read* it. But cookies are auto-sent by the browser, which opens **CSRF**. Mitigate with `SameSite=Strict|Lax`, the `Secure` flag, and (for cross-site needs) anti-CSRF tokens (double-submit or synchronizer pattern).

```
                         XSS risk        CSRF risk      Mitigation
localStorage             HIGH (readable) none           strict CSP, sanitize
HttpOnly cookie          LOW  (no JS)    YES            SameSite + CSRF token + Secure
```

**Production recommendation in 2026:** store the access/refresh tokens in `HttpOnly`, `Secure`, `SameSite=Strict` (or `Lax`) cookies, add CSRF protection for state-changing requests, and apply a strict Content-Security-Policy. The reasoning: XSS that can read a `localStorage` token gives an attacker a portable, exfiltratable credential; an `HttpOnly` cookie limits the attacker to acting *through the victim's browser*, and CSRF is well-understood and defensible. The BFF (Backend-for-Frontend) pattern — keep tokens server-side and hand the SPA only a session cookie — is the strongest option for high-value apps.

### Q15. [Coding] Parse a JWT payload safely without verifying (for logging/diagnostics) — and explain why it must never authorize.

**Problem:** Extract the `sub` and `exp` for a log line, given you only need the values for observability, not for an access decision.

```java
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

public final class JwtPeek {
    private static final ObjectMapper M = new ObjectMapper();

    /** Decode-only. The result is UNTRUSTED and must not drive authorization. */
    public static JsonNode peekPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Not a JWS");
        byte[] json = Base64.getUrlDecoder().decode(parts[1]); // payload segment
        return M.readTree(json);
    }
}
```

- **Time/Space:** O(n) in token length for the Base64 decode + JSON parse.
- **Why it must never authorize:** no signature check occurred, so every field is attacker-controlled. This is exactly the bug behind countless breaches — code that decodes and trusts `roles`/`sub`. Use this strictly for diagnostics, and ideally log only non-sensitive claims and a truncated `jti`, never the whole token.

### Q16. [Theory] Access token vs refresh token — why two tokens?

The two-token model separates *what you present often* from *what renews access*:

- **Access token** — short-lived (minutes), sent on every API call, ideally stateless JWT. Short TTL bounds the damage of theft.
- **Refresh token** — long-lived (days/weeks), presented only to the auth server to mint new access tokens. Usually opaque and stored server-side so it can be revoked.

The benefit is a security/usability balance: you get the scale of short stateless access tokens without forcing users to log in every 15 minutes, while retaining a revocation point (the refresh token store). If an access token leaks, it expires fast; if a refresh token leaks, you can detect and revoke it server-side — which leads directly to rotation and reuse detection.

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Explain refresh token rotation and reuse detection in detail.

**Rotation** means every time a refresh token is used, the server issues a *new* refresh token and invalidates the old one (one-time use). **Reuse detection** exploits this: if an already-rotated (used) refresh token is presented again, that's a strong signal the token was stolen — either the attacker or the legitimate client is replaying an old one. The server then revokes the *entire token family* (the chain descended from the original login), forcing re-authentication.

```
Login -> RT0 (family F)
RT0 used -> issue AT1 + RT1, mark RT0 used
RT1 used -> issue AT2 + RT2, mark RT1 used
...
If RT1 presented AGAIN after RT2 was issued:
   -> REUSE DETECTED -> revoke entire family F (RT0..RTn) -> force re-login
```

The elegance: the legitimate client and an attacker who both hold a snapshot of the chain will inevitably diverge, and the *second* user of any rotated token trips the alarm. You can't tell *who* is the thief, so you revoke the whole family — the conservative, correct response. This is the OAuth 2.0 best-practice (BCP, RFC 9700 / OAuth 2.1) recommendation for public clients (SPAs, mobile) that can't keep a client secret.

### Q18. [Coding] Implement refresh-token rotation with reuse detection.

**Problem:** Store refresh tokens hashed, rotate on use, detect reuse, and revoke the family.

```java
import java.time.Instant;
import java.util.*;

record RefreshRecord(String id, String familyId, String tokenHash,
                     boolean used, Instant expiresAt) {}

class RefreshTokenService {
    private final Map<String, RefreshRecord> store = new HashMap<>(); // id -> record
    private final Set<String> revokedFamilies = new HashSet<>();
    private final TokenHasher hasher;       // e.g. SHA-256 of the raw token
    private final TokenFactory factory;     // mints raw tokens + access JWTs

    RefreshTokenService(TokenHasher h, TokenFactory f) { this.hasher = h; this.factory = f; }

    /** Returns a new access JWT + new refresh token, or throws on reuse/expiry. */
    synchronized TokenPair rotate(String rawRefresh) {
        String id = factory.parseId(rawRefresh);          // opaque id embedded/looked up
        RefreshRecord rec = store.get(id);
        if (rec == null) throw new SecurityException("Unknown refresh token");
        if (revokedFamilies.contains(rec.familyId()))
            throw new SecurityException("Family revoked");

        // REUSE DETECTION: a token that was already rotated is being replayed.
        if (rec.used()) {
            revokeFamily(rec.familyId());                 // nuke the whole chain
            throw new SecurityException("Refresh token reuse detected");
        }
        if (!hasher.matches(rawRefresh, rec.tokenHash()))
            throw new SecurityException("Token mismatch");
        if (rec.expiresAt().isBefore(Instant.now()))
            throw new SecurityException("Refresh token expired");

        // Mark old as used, mint new refresh in the SAME family.
        store.put(rec.id(), new RefreshRecord(rec.id(), rec.familyId(),
                rec.tokenHash(), true, rec.expiresAt()));
        String newRaw = factory.newRefreshToken();
        String newId  = factory.parseId(newRaw);
        store.put(newId, new RefreshRecord(newId, rec.familyId(),
                hasher.hash(newRaw), false, Instant.now().plusSeconds(1_209_600))); // 14d

        String accessJwt = factory.newAccessJwt(rec.familyId());
        return new TokenPair(accessJwt, newRaw);
    }

    private void revokeFamily(String familyId) {
        revokedFamilies.add(familyId);
        store.values().removeIf(r -> r.familyId().equals(familyId));
    }
}
```

- **Time:** O(1) per rotation for the lookup; O(N) for `revokeFamily` over the family (acceptable — rare). In production back this with Redis/DB, not a `HashMap`, and index by `familyId`.
- **Space:** O(active refresh tokens).
- **Edge cases:** network retries can legitimately replay a refresh token (a client got the response but didn't persist it) — mitigate with a small grace window or idempotency key rather than instantly revoking, to avoid logging users out on flaky networks. Always store **hashes** of refresh tokens, never raw, so a DB leak doesn't hand out valid tokens.

### Q19. [Theory] Compare revocation strategies: denylist, short TTL, token versioning.

Because a stateless JWT can't be "deleted," you choose how to revoke:

1. **Denylist (blacklist)** — store revoked `jti`s (or token hashes) in a fast store (Redis) with a TTL equal to the token's remaining life. On each request, check membership. Cost: a lookup per request (partly reintroduces state) and memory proportional to revoked tokens. Precise but stateful.

2. **Short TTL** — make access tokens so short (1–5 min) that revocation is "good enough": you simply stop issuing refreshes for the compromised session, and existing access tokens die quickly. Cheapest, fully stateless, but revocation isn't immediate, and very short TTLs increase refresh traffic.

3. **Token versioning / `auth_time` epoch** — store a per-user (or per-credential) version number / `tokenValidAfter` timestamp in the DB. Embed the version in the JWT. To revoke *all* of a user's tokens (password change, "log out everywhere"), bump the version; tokens with a stale version are rejected. Requires a per-user lookup (or cached version), but is great for bulk/global revocation and far cheaper than per-token denylisting.

```
                  Immediacy   Statefulness   Granularity        Cost/req
Denylist          immediate   high           per-token          lookup
Short TTL         delayed     none           per-session(soft)  none
Token versioning  immediate   medium         per-user/global    lookup(cacheable)
```

Most production systems combine #2 (short TTL access tokens) with #3 (versioning for "log out everywhere" + password change) and reserve #1 for surgical, high-severity revocations.

### Q20. [Coding] Implement token versioning ("logout everywhere") validation.

**Problem:** Reject any access token whose embedded `ver` is older than the user's current token version. Bumping the version invalidates all prior tokens.

```java
import com.auth0.jwt.interfaces.DecodedJWT;

class VersionedTokenValidator {
    interface UserVersionStore { long currentVersion(String userId); } // cached read

    private final JwtValidator base;          // signature/iss/aud/exp (from Q10)
    private final UserVersionStore versions;

    VersionedTokenValidator(JwtValidator base, UserVersionStore versions) {
        this.base = base; this.versions = versions;
    }

    DecodedJWT validate(String token) {
        DecodedJWT jwt = base.validate(token);            // all standard checks first
        long tokenVer = jwt.getClaim("ver").asLong();
        long currentVer = versions.currentVersion(jwt.getSubject());
        if (tokenVer < currentVer)
            throw new SecurityException("Token revoked (stale version)");
        return jwt;
    }
}
// Revoke ALL of a user's tokens: versionStore.increment(userId).
```

- **Time:** O(1) plus a (cached) version lookup. Cache the version in Redis with a short TTL so the common path avoids a DB hit; on cache miss, read-through.
- **Space:** O(users) for the version counters — tiny.
- **Edge cases:** missing `ver` claim → treat as version 0 / reject depending on policy; clock-free design (no skew issues since it's a monotonic counter); the cache must invalidate promptly on bump or revocation is delayed by the cache TTL. This is exactly how "sign out of all devices" and "force re-login after password change" are implemented at scale.

### Q21. [Practical] A microservices fleet shares an HS256 secret. What's wrong and how do you migrate?

**What's wrong:** With HS256, the secret both signs and verifies. Sharing it across services means *any* service (and anyone who compromises any one of them) can **forge** tokens that the whole fleet trusts. There's no separation between issuer and verifier, the blast radius of a single secret leak is the entire system, and rotating a shared secret requires a coordinated redeploy.

**Migration to asymmetric (RS256/ES256):**

1. The auth service generates an asymmetric keypair; publish the public key(s) via JWKS with a `kid`.
2. Configure resource servers to verify via the JWKS URL (cache + refresh-on-unknown-kid), pinned to RS256/ES256.
3. Dual-issue during transition if needed: keep accepting HS256 only briefly, behind a flag, while clients roll over.
4. Cut the issuer over to private-key signing; resource servers now verify with the public key and **cannot forge**.
5. Remove the shared secret from all resource-service configs/secrets managers.

The end state: only the auth service holds a private key; everyone else holds public keys. A compromised resource service can no longer mint valid tokens. Pair this with key rotation (Q11) and you have a defensible posture.

### Q22. [Theory] How do you handle clock skew, and why is leeway a security trade-off?

Distributed systems have unsynchronized clocks; a verifier whose clock is slightly behind the issuer may reject a freshly minted token (`nbf`/`iat` in its future) or accept one a hair past `exp`. The fix is a small **leeway** (clock-skew tolerance), typically 30–60 seconds, applied to `exp`, `nbf`, and `iat`. The trade-off: leeway literally extends the window a token is accepted past expiry, so don't make it large (minutes) — that meaningfully widens the replay window for a stolen token. Best practice is NTP-synced hosts plus ≤ 60s leeway. Never "fix" intermittent expiry failures by cranking leeway to several minutes; fix the clocks.

### Q23. [Practical] Your access tokens are 4 KB and every request carries 40 claims. What problems arise and how do you fix it?

**Problems:** Bloated tokens inflate every request header (and JWTs are often duplicated into cookies, logs, and proxies). Header-size limits (e.g. ~8 KB default in many servers/proxies) can cause `431 Request Header Fields Too Large`. Large RS256 signatures compound it. Bandwidth and parse cost rise on hot paths, and putting 40 fine-grained permissions in the token makes them stale and hard to revoke.

**Fixes:**

- Keep only stable identity + coarse authorization in the token (`sub`, `roles`/`scope`, `tenant`); resolve fine-grained permissions server-side from a fast store.
- Use **ES256** instead of RS256 to shrink signatures.
- Avoid embedding large data (profile blobs, long lists) — reference them by id.
- Consider opaque tokens + token introspection (RFC 7662) when claims change frequently or must be revocable; introspection trades a network call for freshness and revocability.
- For internal east-west calls, a slim, narrowly-scoped service token beats forwarding the user's full token everywhere.

### Q24. [Theory] When would you use JWE (encryption) instead of JWS (signature)?

Use JWE when the *claims themselves are confidential* and must not be readable by intermediaries or the client. JWS gives integrity and authenticity but leaves the payload readable; JWE encrypts it (e.g. content encryption with AES-GCM, key wrapping with RSA-OAEP or ECDH-ES). Common patterns: tokens carrying sensitive attributes that pass through untrusted hops, or "nested JWT" (sign-then-encrypt) where you JWS-sign for authenticity and then JWE-encrypt for confidentiality. The costs: larger tokens, more CPU, key-management for the encryption keys *and* the signing keys, and more ways to misconfigure (e.g. weak `alg`/`enc` pairings). For the vast majority of API auth, JWS + "don't put secrets in the token" is the right call; reach for JWE only when confidentiality of claims is a real requirement.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] Walk through the OAuth 2.0 Security BCP (RFC 9700) / OAuth 2.1 changes that affect JWT usage in 2026.

OAuth 2.1 consolidates years of best-current-practice into the baseline. Key items an architect must enforce:

- **PKCE is mandatory** for all clients using the authorization code flow (not just public clients) — defends against authorization-code interception.
- **Implicit and Resource Owner Password Credentials grants are removed** — tokens-in-URL-fragment and password-handling-by-client are deprecated.
- **Refresh-token rotation with reuse detection is required for public clients** (Q17), or sender-constrained refresh tokens.
- **Sender-constrained access tokens** — DPoP (RFC 9449) or mTLS (RFC 8705) bind a token to a client's key so a stolen bearer token alone is useless. This is the single biggest hardening lever against token theft and is increasingly expected for high-value APIs in 2026.
- **Exact redirect-URI matching** and stricter `aud`/`iss` validation to stop mix-up and confused-deputy attacks.

The throughline: bearer tokens are "anyone who holds it can use it." Modern guidance pushes toward *constraining* tokens (DPoP/mTLS), *shortening* them, and *rotating* the renewal credential.

### Q26. [Theory] Explain DPoP and how it changes the threat model versus bearer tokens.

DPoP (Demonstrating Proof-of-Possession, RFC 9449) binds an access token to a key pair held by the client. The client generates a keypair, and on each request sends a `DPoP` header — a short-lived JWT signed by its private key, containing the HTTP method, URI, a nonce, and a hash of the public key (`jkt`). The access token embeds the public key thumbprint (`cnf.jkt`). The resource server checks that the DPoP proof is signed by the key matching the token's `cnf` claim.

```
Bearer:  steal token  -> attacker replays it anywhere. Game over.
DPoP:    steal token  -> useless without the private key, which never
         leaves the client. Attacker also needs to forge a fresh,
         method+URI-bound proof per request.
```

This converts a stolen token from a portable credential into one that's only usable by the holder of the private key — shrinking the impact of XSS exfiltration, leaked logs, and MITM. mTLS-bound tokens (RFC 8705) achieve the same proof-of-possession at the TLS layer and are common in B2B/server-to-server contexts. The trade-off is client complexity and per-request signing cost.

### Q27. [Practical] Design end-to-end auth for a multi-tenant SaaS with a SPA, mobile apps, and 30 internal microservices. Justify every choice.

A staff-level answer ties the pieces together:

```
        +-------------------+        OIDC + PKCE        +------------------+
SPA  -->|  BFF (per-app)    |<------------------------->|  Auth Server     |
Mobile->|  holds tokens     |   code flow, rotation     |  (issuer, JWKS)  |
        +-------------------+                            +------------------+
            | session cookie (HttpOnly, Secure, SameSite)     | publishes
            v                                                  v  JWKS (ES256)
        +-------------------+   Bearer/DPoP access JWT   +------------------+
        |  API Gateway      |--------------------------->|  30 microservices |
        |  verifies once    |  forwards slim claims      |  verify locally   |
        +-------------------+                            +------------------+
```

- **Token format:** ES256-signed JWS access tokens (small, fast); asymmetric so the 30 services verify-only via JWKS and can never forge. Issuer holds the private key.
- **Multi-tenancy:** `tenant_id` claim + `iss`/`aud` per environment; services enforce tenant isolation from the claim and re-check ownership server-side (never trust tenant from the URL alone).
- **Storage:** BFF pattern — tokens live server-side; the SPA/mobile gets an `HttpOnly`, `Secure`, `SameSite=Strict` cookie. Eliminates the localStorage XSS-exfiltration class entirely.
- **Lifetimes:** access tokens 5–15 min; refresh tokens long-lived, **rotated with reuse detection**, stored hashed.
- **Revocation:** short TTL + token versioning for "log out everywhere"/password change; a Redis denylist of `jti` for surgical revocation.
- **Hardening:** PKCE mandatory; DPoP for the highest-value APIs; strict CSP; key rotation with JWKS overlap.
- **Internal calls:** the gateway validates the user token once at the edge; east-west calls use slim, narrowly-scoped service identities (workload identity / mTLS) rather than forwarding the user's full token to all 30 services.

Each choice maps to a specific threat: asymmetric → forgery, BFF/cookies → XSS theft, rotation → refresh-token replay, versioning → bulk revocation, DPoP → bearer replay.

### Q28. [Theory] What are the subtle but catastrophic JWT mistakes you watch for in code review?

Beyond the obvious (`alg=none`, no `exp`), the ones that bite experienced teams:

- **Trusting `kid`/`jku`/`x5u` from the header** to fetch keys — SSRF and key-injection. Resolve keys only from a pre-trusted JWKS allow-list.
- **`aud` not validated**, so a token for service A works on service B (confused deputy). Equally, validating `iss` loosely (prefix/substring match) instead of exact.
- **Decode-without-verify** anywhere in the codebase, then trusting the claims (Q15).
- **Weak HS256 secrets** (short, low-entropy) that are brute-forceable offline once a token leaks.
- **No leeway → flaky auth**, or **huge leeway → wide replay window** (Q22).
- **JWT used as a session you think you can revoke** — but with no denylist/versioning, it can't be (Q13).
- **Refresh tokens stored raw** in the DB, or not rotated, so a DB leak = standing access.
- **Algorithm confusion** because the verifier reads `alg` from the token instead of pinning it (Q8).
- **Putting PII/secrets in the payload** because "it's signed" — signing ≠ encryption (Q2).

### Q29. [Behavioral] Tell me about a time you had to make a security/usability trade-off on auth, and how you drove the decision.

A strong answer follows situation → tension → action → outcome. Example framing: "We had reports of users being logged out mid-session on mobile due to flaky networks racing our strict refresh-token reuse detection — a legitimate retry was tripping the family-revocation alarm. The tension was real security (reuse detection is our defense against stolen refresh tokens) versus usability (spurious logouts hurt retention). I drove a data-informed decision: we measured the actual reuse-detection triggers, found ~95% correlated with network retries within a 10-second window, and introduced a short idempotency grace window plus a one-time replay allowance keyed by request id — preserving reuse detection for true replays while tolerating retries. I socialized the design with security in an RFC, we shipped behind a flag, watched the revocation-event metrics, and false logouts dropped ~90% with no measurable increase in genuine reuse incidents." The point being assessed: can you hold security and UX in tension, use data, get buy-in, and ship a *bounded* compromise rather than turning a control off.

### Q30. [Practical] How do you roll out a signing-key compromise response (incident) without taking the system down?

The incident playbook for "the signing private key may be compromised":

1. **Rotate immediately** — generate a new keypair (new `kid`), start signing with it, publish the new public key to JWKS *first* so verifiers can fetch it.
2. **Remove the compromised public key from JWKS** so any token forged with the old private key fails verification fleet-wide. (This is why JWKS + `kid` is operationally vital — you can drop a key everywhere by editing one document.)
3. **Force token version bump** (Q20) / clear refresh-token families so attacker-minted refresh tokens are dead.
4. **Shorten access-token TTL temporarily** to flush any in-flight forged access tokens faster.
5. **Invalidate sessions** at the BFF / session layer for affected users.
6. **Communicate**: trigger verifier JWKS refresh (some cache aggressively — push a cache-bust or rely on unknown-`kid` refresh), and monitor for a spike in verification failures (expected) vs. successful old-key tokens (must hit zero).
7. **Post-incident**: move the key into an HSM/KMS so the raw private key never leaves hardware, and review how it leaked.

The architectural prerequisite that makes this survivable: asymmetric keys + JWKS + `kid` + token versioning. If you'd shared an HS256 secret across 30 services (Q21), this same incident is a coordinated all-services redeploy under fire.

### Q31. [Theory] Stateless JWT sessions don't scale to "instant global logout." Argue both the naive and the nuanced position.

**Naive position:** "JWTs are stateless, so they scale infinitely and we never need a session store." True for *verification* throughput, but it ignores that statelessness is precisely what removes your ability to revoke — you've optimized for the wrong axis if your product needs immediate logout, banned-user enforcement, or compliance-driven session termination.

**Nuanced position:** Statelessness is a spectrum, not a binary. You keep stateless *verification on the hot path* (every API request verifies a signature locally — that's where scale matters), and you reintroduce a *small amount of state for the rare control operations* (version counters, denylists checked only when present, refresh-token store). The version counter is O(users) and cacheable; the denylist holds only currently-revoked `jti`s and self-expires. So you pay the statefulness cost only for the operations that genuinely need it, keeping the 99.9% read path stateless. The expert insight: "stateless vs stateful" is a false dichotomy — the right design is *stateless authentication with a thin stateful revocation layer*, sized to the revocation requirements, not a religious commitment to zero state.

### Q32. [Practical] A real-world case study: what can we learn from large-scale JWT/session incidents?

A recurring industry pattern (seen across multiple SaaS and identity-provider postmortems) is the **stolen-token replay via XSS or a logged token**, where the token was a long-lived bearer credential stored in `localStorage` or leaked into logs/error trackers. The attacker replayed it freely because (a) the token was long-lived, (b) it was bearer (no proof-of-possession), and (c) there was no fast revocation. The lessons that consistently appear in the fixes:

- Move tokens out of JS reach (HttpOnly cookies / BFF) — kills the XSS-read vector.
- Shorten access-token TTL drastically — bounds replay time.
- Add proof-of-possession (DPoP/mTLS) — a leaked token alone becomes useless.
- Scrub tokens from logs and third-party error trackers — a token in Sentry is a token in the breach.
- Add reuse detection on refresh tokens — turns a stolen refresh token into a tripwire.

The meta-lesson for a staff engineer: most "JWT breaches" aren't breaks of the crypto — they're operational and storage mistakes around an otherwise-sound primitive. Defense is layered (storage + lifetime + binding + revocation + logging hygiene), and no single control suffices.

---

## ✅ Key Takeaways

- A JWS is **signed, not encrypted** — anyone can read the payload; never put secrets in it (use JWE only when claim confidentiality is truly required).
- Always **pin the verification algorithm server-side**; never trust the token's `alg`/`kid`/`jku` header. This defeats `alg=none` and RS256→HS256 confusion.
- Validate the full checklist: signature, `exp`, `nbf`, `iss`, **`aud`**, and required scopes — skipping `aud`/`iss` enables confused-deputy replay.
- Prefer **asymmetric signing (ES256 > RS256)** for multi-verifier/microservice systems so resource servers can verify but never forge; reserve HS256 for a single trust boundary.
- Use **JWKS + `kid` with overlapping rotation** so you can roll or revoke keys without downtime.
- JWTs trade **revocability for statelessness** — pair short-lived access tokens with a thin stateful layer (refresh-token store, token versioning, optional `jti` denylist) when you need real revocation.
- **Rotate refresh tokens with reuse detection** (revoke the whole family on replay); store refresh tokens hashed, never raw.
- Store browser tokens in **`HttpOnly`, `Secure`, `SameSite` cookies (or a BFF)** plus CSRF defense; `localStorage` exposes tokens to XSS exfiltration.
- In 2026, harden with **PKCE everywhere, refresh rotation, and proof-of-possession (DPoP/mTLS)** per OAuth 2.1 / RFC 9700.

## ⚠️ Common Pitfalls

- Decoding a JWT and trusting its claims **without verifying** the signature.
- Honoring `alg: none` or letting the header pick the algorithm (algorithm confusion).
- Omitting `aud`/`iss` validation, or matching them with substring/prefix instead of exact equality.
- Treating a stateless JWT as a session you can instantly kill — with no denylist/versioning you cannot.
- Storing tokens in `localStorage` and assuming XSS "won't happen."
- Sharing an HS256 secret across many services (any service can forge for all).
- Weak/short HMAC secrets that are brute-forceable offline after a single token leak.
- No clock-skew leeway (flaky auth) or excessive leeway (wide replay window).
- Long-lived access tokens with no rotation, no reuse detection, and tokens leaking into logs/error trackers.
- Putting PII or secrets in the payload because "it's signed."

## 📚 Further Reading

- **RFC 7519 (JWT), RFC 7515 (JWS), RFC 7516 (JWE), RFC 7517 (JWK/JWKS), RFC 8725 (JWT BCP)** — the authoritative specs; RFC 8725 is the security best-current-practice for JWTs.
- **RFC 9700 (OAuth 2.0 Security Best Current Practice)** and the **OAuth 2.1** draft — modern hardening, rotation, and grant-type guidance.
- **RFC 9449 (DPoP)** and **RFC 8705 (mTLS)** — sender-constrained / proof-of-possession tokens.
- **OWASP** — *JSON Web Token for Java Cheat Sheet*, *Session Management Cheat Sheet*, and the OWASP Top 10 (Broken Access Control, Cryptographic Failures).
- **Auth0 / Okta developer docs** — *JWT Handbook* (free e-book) and practical guides on refresh-token rotation and reuse detection.
- **Spring Security 6 Reference — OAuth2 Resource Server** — production JWT validation with `NimbusJwtDecoder`, JWKS, and audience validation.
