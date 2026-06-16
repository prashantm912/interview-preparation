# Authentication Patterns (Basic, M2M, mTLS, WS-Security)

A deep, interview-focused guide to authentication patterns for APIs and distributed systems — covering Basic auth, API keys, machine-to-machine (M2M) auth, mutual TLS, enterprise SSO (SAML / WS-Federation / WS-Security), session vs token auth, MFA, passkeys, and zero-trust. Examples are in Java (Spring Boot 3 / Java 17–21 era).

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

> **Authentication vs Authorization.** Throughout this guide, *authentication* (AuthN) answers "who are you?" and *authorization* (AuthZ) answers "what may you do?" Most "auth patterns" below establish identity (AuthN); they pair with authorization models (RBAC/ABAC/OAuth scopes) downstream.

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is HTTP Basic Authentication, and when (if ever) is it acceptable?

HTTP Basic Auth sends credentials in an `Authorization: Basic <base64(username:password)>` header on every request. The crucial point candidates miss: **Base64 is encoding, not encryption** — anyone who intercepts the request reads the password trivially. It is therefore *only* acceptable over TLS, and even then it has structural weaknesses: the raw password is transmitted on every call (so it lives in proxy logs, browser memory, and server access logs if you are careless), there is no built-in expiry or revocation, and it cannot carry MFA.

When is it acceptable? For *server-to-server* internal calls behind TLS where the alternative is over-engineering, for quick prototypes, for tooling/healthchecks, and as the transport for things like the OAuth2 token endpoint (where the client secret is the "password"). It is **not** acceptable for end-user-facing production APIs in 2026 — use token or session auth instead. The "why" is risk surface: a long-lived, replayable secret sent on every request maximizes exposure.

```
Client                         Server
  | Authorization: Basic       |
  | base64("alice:pa55w0rd")   |
  |--------------------------->| decode -> "alice:pa55w0rd"
  |                            | verify against store (bcrypt/argon2 hash)
  |<---------- 200 / 401 ------|
```

### Q2. [Theory] What is an API key, and how does it differ from Basic auth?

An API key is a single opaque secret string (e.g. `sk_live_4eC39...`) identifying a *caller/application*, not a human user. Unlike Basic auth it carries no username/password pair — it is one bearer credential. API keys are simple to issue and rotate, easy to scope (read-only vs read-write keys), and easy to attach rate limits and quotas to. Their weaknesses: they are bearer tokens (whoever holds it can use it), typically long-lived, and frequently leaked in client-side code, git history, or logs.

Best practice in 2026: prefix keys so secret scanners (GitHub, GitGuardian) can detect leaks (`sk_live_…`), store only a hash of the key server-side (treat it like a password), support multiple active keys per account to enable zero-downtime rotation, and never put keys in URLs (they end up in logs and browser history) — put them in headers like `X-API-Key` or `Authorization: Bearer`.

### Q3. [Practical] You're exposing an internal admin endpoint. Walk through securing it with Basic auth in Spring Boot 3.

Scenario: a low-traffic internal `/admin/**` endpoint, TLS-terminated at the gateway, used only by ops tooling. Basic auth is a reasonable pragmatic choice here. Approach: enforce HTTPS, store the credential hashed (never plaintext), restrict by IP/network where possible, and lean on Spring Security's built-in support.

```java
@Configuration
@EnableWebSecurity
public class AdminSecurityConfig {

    @Bean
    SecurityFilterChain adminChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/admin/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())          // stateless API; no browser forms
            .sessionManagement(s -> s.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
            .requiresChannel(c -> c.anyRequest().requiresSecure()); // force HTTPS
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // never store plaintext
    }
}
```

Trade-offs / what I'd actually do: I'd terminate TLS at the load balancer, put the endpoint on an internal-only listener (mTLS or network ACLs), store the bcrypt hash in a secret manager (not in `application.yml`), and add `requiresSecure()` so a misconfigured plaintext port can't leak credentials. For anything user-facing I'd switch to OAuth2/OIDC.

### Q4. [Coding] Write a constant-time API-key verifier. Why is constant-time comparison important?

**Problem:** Verify a presented API key against the stored secret without leaking information through timing. A naive `String.equals` short-circuits on the first mismatched byte, so an attacker measuring response latency can recover the key byte-by-byte (a *timing side-channel*).

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class ApiKeyVerifier {

    // Store only a SHA-256 (or better, a salted KDF) of the key, never the raw key.
    private final byte[] storedKeyHash;

    public ApiKeyVerifier(byte[] storedKeyHash) {
        this.storedKeyHash = storedKeyHash.clone();
    }

    public boolean verify(String presentedKey) {
        if (presentedKey == null) return false;
        byte[] presentedHash = sha256(presentedKey);
        // Constant-time: compares the FULL length regardless of mismatches.
        return MessageDigest.isEqual(storedKeyHash, presentedHash);
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                       .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

**Why constant-time:** `MessageDigest.isEqual` (Java 6u17+) compares all bytes, eliminating the timing oracle. Hashing first also means both inputs are fixed length, so length never leaks. **Time:** O(n) over hash length (fixed 32 bytes). **Space:** O(1). **Edge cases:** null/empty key → reject; never log the key; rotate by storing multiple valid hashes.

### Q5. [Theory] Session-based vs token-based authentication — what's the core difference?

Session auth is **stateful**: on login the server creates a session record (in memory, Redis, or DB) and hands the client an opaque session ID in a cookie. Every request, the server looks up that ID to recover identity. Token auth (typically JWT) is **stateless**: the server signs a token containing the claims, and on each request it just *verifies the signature* — no server-side lookup needed.

The trade-off is revocation vs scalability. Sessions are trivially revocable (delete the row) but require shared session storage to scale horizontally. JWTs scale beautifully (any node can verify with the public key, no lookup) but are hard to revoke before expiry — which is why JWTs should be short-lived and paired with refresh tokens. Cookies (sessions) are vulnerable to CSRF and need `SameSite`/CSRF tokens; bearer JWTs in headers avoid CSRF but are vulnerable to XSS if stored in `localStorage`.

```
SESSION (stateful)                 TOKEN/JWT (stateless)
client: Cookie: SID=abc            client: Authorization: Bearer eyJ...
server: lookup SID in Redis        server: verify signature with public key
        -> identity                        -> identity (claims in token)
revoke: delete row (instant)       revoke: hard; rely on short TTL + denylist
```

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain the OAuth2 Client Credentials grant. When is it the right choice for M2M auth?

The Client Credentials grant is the canonical OAuth2 flow for **machine-to-machine** authentication where there is *no human user* — one service authenticating as itself to call another. The client authenticates to the authorization server (token endpoint) using its `client_id` + `client_secret` (or a private-key JWT), and receives a short-lived access token (typically a JWT) scoped to specific permissions. There is no `redirect_uri`, no user consent, no refresh token (you just request a new token when it expires).

It's the right choice when: a backend job/cron/service calls an API, you want centralized issuance and scoping, and you want short-lived credentials instead of static API keys. Compared to API keys, OAuth2 client credentials give you expiry (limits blast radius of a leak), fine-grained scopes, and centralized revocation at the token endpoint. The trade-off is operational complexity (you need an authorization server like Keycloak, Auth0, Okta, or AWS Cognito).

```
Service A (client)                 Auth Server            Service B (resource)
  | POST /token                       |                        |
  | grant_type=client_credentials     |                        |
  | client_id + client_secret         |                        |
  | scope=orders.read                 |                        |
  |---------------------------------->| issue signed JWT       |
  |<-------- access_token (JWT) ------|  (exp ~5-15 min)        |
  |                                                             |
  | Authorization: Bearer <JWT>  ------------------------------>| verify sig + scope
  |<------------------------------------------- 200 ------------|
```

### Q7. [Practical] Your microservices currently pass a shared static API key between every service. Walk through migrating to proper service-to-service auth.

Current state: a single shared secret in every service's config — a textbook anti-pattern (one leak compromises everything, no per-service identity, no rotation without a coordinated outage, no audit trail of *which* service called).

Approach (incremental, zero-downtime):
1. **Stand up an authorization server** (Keycloak/Okta/internal). Register each service as a confidential client with its own `client_id`/secret and scopes.
2. **Dual-accept** at resource servers: accept either the old API key *or* a valid bearer JWT during migration.
3. **Migrate callers** one at a time to fetch and cache short-lived tokens via client credentials, refreshing before expiry.
4. **Flip the switch**: once all callers send JWTs, remove API-key acceptance.
5. **Layer mTLS** for transport identity (defense in depth) if you're in a zero-trust environment or service mesh.

What I'd actually do in production: adopt a **service mesh (Istio/Linkerd) with mTLS** for transport-layer service identity (SPIFFE/SVID), and layer OAuth2 JWTs for application-layer authorization scopes. The mesh handles cert issuance/rotation automatically (no human touches certs). Trade-off: mesh adds operational and latency overhead, so for small fleets a plain client-credentials + JWT setup is often enough.

### Q8. [Theory] Compare the three main M2M authentication mechanisms: shared secret (client_secret), mTLS/client certs, and signed JWT assertions (private_key_jwt).

All three prove "I am client X" to a token endpoint or peer, but with different secret-handling models:

- **Shared `client_secret`** (symmetric): simplest; both sides know the secret. Weakness: the secret travels to the auth server on every token request and must be stored by both parties — a leak at either end is total compromise. Good enough behind TLS for many internal cases.
- **mTLS / client certificates** (asymmetric, transport layer): the client presents an X.509 cert during the TLS handshake; the private key *never leaves the client*. Strong, but requires PKI: a CA, cert issuance, rotation, and revocation (CRL/OCSP). OAuth2 also supports **certificate-bound access tokens** (RFC 8705) so a stolen token can't be replayed without the cert.
- **Private-key JWT (`private_key_jwt`, RFC 7523)** (asymmetric, application layer): the client signs a short-lived JWT assertion with its private key and sends *that* instead of a secret. The auth server verifies with the registered public key. Best of both: no shared secret transmitted, no full PKI/TLS-handshake plumbing needed. Widely used in FAPI (Financial-grade API) and high-assurance B2B integrations.

Rule of thumb: shared secret = convenience; mTLS = transport identity + token binding; private_key_jwt = strong client auth without a full mesh PKI.

### Q9. [Coding] Implement a thread-safe client-credentials token cache in Java that refreshes the token before it expires.

**Problem:** Repeatedly hitting the token endpoint on every outbound call is slow and hammers the auth server. Cache the token and refresh it proactively (with a safety skew) before expiry, in a thread-safe way.

```java
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class ClientCredentialsTokenProvider {

    private record CachedToken(String token, Instant expiresAt) {
        boolean valid(Instant now) { return now.isBefore(expiresAt); }
    }

    private final AtomicReference<CachedToken> cache = new AtomicReference<>();
    private final Duration skew = Duration.ofSeconds(30); // refresh early
    private final TokenEndpointClient client;             // calls /token

    public ClientCredentialsTokenProvider(TokenEndpointClient client) {
        this.client = client;
    }

    public String getToken() {
        Instant now = Instant.now();
        CachedToken current = cache.get();
        if (current != null && current.valid(now)) {
            return current.token();
        }
        // Double-checked: only one thread refreshes; others reuse the result.
        synchronized (this) {
            current = cache.get();
            if (current != null && current.valid(Instant.now())) {
                return current.token();
            }
            TokenResponse resp = client.requestToken(); // grant_type=client_credentials
            Instant exp = Instant.now()
                    .plusSeconds(resp.expiresInSeconds())
                    .minus(skew);
            cache.set(new CachedToken(resp.accessToken(), exp));
            return resp.accessToken();
        }
    }
}
```

**Approaches:** (1) Naive — fetch every call: O(network) per call, simplest, terrible throughput. (2) This cached version — refresh only near expiry. (3) Background refresh — a scheduled task pre-warms the token so no request ever blocks on a refresh (best for high-throughput, more moving parts). **Time:** O(1) amortized read. **Space:** O(1). **Edge cases:** clock skew (hence the 30s buffer), token-endpoint failure (retry with backoff; serve stale only if your policy allows), concurrent first-call stampede (handled by the `synchronized` block).

### Q10. [Coding] Validate a JWT (signature, expiry, issuer, audience) using Java and Spring Security's resource-server support.

**Problem:** A resource server must reject any request whose bearer token is unsigned by the trusted issuer, expired, or not intended for this audience. Hand-rolling JWT parsing is dangerous (the classic `alg: none` and algorithm-confusion attacks). Use a vetted library and let the framework do it.

Config-driven validation (preferred — Spring Boot 3, Spring Security 6):

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/prod   # fetches JWKS automatically
          audiences: orders-api
```

```java
@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/orders/**").hasAuthority("SCOPE_orders.read")
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
}

// Add audience validation explicitly (issuer + expiry are validated by default).
@Bean
JwtDecoder jwtDecoder(OAuth2ResourceServerProperties props) {
    String issuer = props.getJwt().getIssuerUri();
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    OAuth2TokenValidator<Jwt> withAudience = new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtClaimValidator<List<String>>("aud",
            aud -> aud != null && aud.contains("orders-api")));
    decoder.setJwtValidator(withAudience);
    return decoder;
}
```

**Key security points:** never accept `alg: none`; pin the expected algorithm (RS256/ES256) to prevent HS/RS confusion; always validate `iss`, `aud`, and `exp` (and `nbf`); fetch the issuer's JWKS (public keys) over HTTPS and cache with rotation support. **Edge cases:** clock skew (Spring allows a default 60s leeway), key rotation (JWKS endpoint returns multiple `kid`s), missing `kid` header. **Time/Space:** signature verify is O(1) crypto; JWKS cached so no per-request network call.

### Q11. [Theory] What is SAML 2.0 and how does the SSO flow work? Where does it still dominate in 2026?

SAML 2.0 (Security Assertion Markup Language) is an XML-based standard for browser SSO between an **Identity Provider (IdP)** and a **Service Provider (SP)**. The IdP authenticates the user and issues a digitally signed XML *assertion* containing identity and attributes; the SP trusts that assertion based on a pre-exchanged signing certificate. The most common flow is **SP-initiated, HTTP-POST binding**:

```
User Browser            SP (your app)           IdP (Okta/AD FS/Azure AD)
  | GET /protected         |                        |
  |----------------------->| 302 + SAMLRequest       |
  |<-- redirect to IdP ----|                         |
  | GET /sso?SAMLRequest   |------------------------>| authenticate user (+MFA)
  |                        |                         | build signed Assertion
  |<------- HTML auto-POST form (SAMLResponse) ------|
  | POST /acs (SAMLResponse) ----------------------->|  (back to SP's ACS URL)
  |                        | verify XML signature,   |
  |                        | conditions, audience    |
  |<------ session cookie -|  -> logged in           |
```

SAML still dominates **enterprise/B2B SSO**: legacy on-prem apps, government, healthcare, education, and any SaaS that must integrate with Active Directory Federation Services (AD FS) or Azure AD/Entra. For new consumer and mobile apps, OIDC (OAuth2-based, JSON/JWT, far simpler) has won — but you'll still be asked to integrate SAML for big customers for years to come.

### Q12. [Theory] "Wendi auth" was mentioned — clarify what's likely meant, and explain WS-Federation and WS-Security.

"Wendi auth" is almost certainly a mishearing/typo for **WS-*** (pronounced "WS-star") — the family of WS-Federation / WS-Security / WS-Trust standards used for enterprise SOAP-based SSO and message security. Worth clarifying this with the interviewer rather than guessing.

- **WS-Security** (WSS): a SOAP *message-level* security standard. Instead of relying solely on transport TLS, it embeds security directly in the SOAP envelope's `<wsse:Security>` header — username tokens, X.509 binary security tokens, SAML assertions, plus XML Signature and XML Encryption applied to message parts. Its strength is **end-to-end** security that survives intermediaries (a message can be signed by the sender and verified by the final recipient even after passing through SOAP routers), unlike TLS which only protects point-to-point.
- **WS-Federation**: an SSO/identity-federation protocol (Microsoft-led, used heavily by AD FS) layered on WS-Trust, letting security realms broker identity. It's the SOAP-world counterpart to SAML web SSO.
- **WS-Trust**: defines the **Security Token Service (STS)** — a service that issues, validates, and exchanges security tokens.

In 2026 these are **legacy** — you maintain them, you don't greenfield them. They live in established enterprise (banking, insurance, government) SOAP estates. New work goes to OIDC/OAuth2 or SAML. Knowing they exist and what `<wsse:Security>` / XML-Signature do is the realistic interview expectation.

### Q13. [Practical] Compare SAML vs OIDC for a new B2B SaaS that must onboard both modern and legacy enterprise customers. What do you build?

Scenario: you're the SaaS; customers bring their own IdP. Modern customers (Okta, Entra ID, Google Workspace) speak OIDC fluently; older enterprises only do SAML 2.0 or even WS-Federation via AD FS.

Approach: **don't implement protocol plumbing per customer.** Put an identity broker / "SSO gateway" in front — Keycloak, Auth0/Okta, WorkOS, or your own SSO abstraction. Your app speaks *one* protocol (OIDC) to the broker; the broker translates outward to each customer's SAML/OIDC/WS-Fed. This is exactly what WorkOS productized and why it became popular.

Trade-offs: building it yourself means owning XML signature validation (a notorious source of vulnerabilities — signature wrapping, XML external entity attacks), metadata exchange, and per-tenant config. Buying it (WorkOS/Okta) costs money but offloads the security-critical, low-differentiation work. What I'd actually do: app speaks OIDC only; use a broker for federation; support SAML through the broker; treat WS-Federation as a special-case adapter only if a marquee customer demands it. This keeps the blast radius of XML-parsing bugs out of the core app.

### Q14. [Theory] What is mutual TLS (mTLS) and how does it differ from standard one-way TLS?

In standard (one-way) TLS, only the *server* proves its identity with a certificate; the client verifies it and an encrypted channel is established, but the client is unauthenticated at the transport layer. In **mutual TLS**, *both* sides present X.509 certificates during the handshake — the server requests the client's cert via a `CertificateRequest`, and authenticates the client against a trusted CA. So mTLS gives you bidirectional, cryptographically strong, transport-layer identity.

The "why" for M2M: the client's private key never crosses the wire (unlike a shared secret), credentials can't be replayed without the key, and you get strong identity *before any application data flows*. The cost is **PKI operations**: issuing certs, distributing them, rotating them before expiry, and revoking compromised ones (CRL/OCSP/short-lived certs). This operational burden is why service meshes (Istio/Linkerd) and SPIFFE/SPIRE exist — to automate cert lifecycle so humans never touch them.

```
mTLS handshake (simplified)
Client                                Server
  |-- ClientHello --------------------->|
  |<-- ServerHello, Server Cert,        |
  |    CertificateRequest --------------|
  | verify server cert (CA)             |
  |-- Client Cert + CertVerify -------->| verify client cert (CA), check CN/SAN
  |<-- Finished ------------------------|  -> both identities established
  |======== encrypted app data ========|
```

---

## 🟠 Advanced (8–12 yrs)

### Q15. [Theory] Deep dive: how do you operate mTLS at scale across hundreds of microservices? Cover identity, rotation, and revocation.

The hard part of mTLS at scale is **not** the handshake — it's the certificate *lifecycle*. Three pillars:

1. **Identity issuance.** You need a workload identity standard so each service gets a verifiable identity, not just "a cert." **SPIFFE** defines the identity format (`spiffe://trust-domain/ns/payments/sa/checkout`) and **SVID** (SPIFFE Verifiable Identity Document, typically an X.509 cert). **SPIRE** (or a mesh's built-in CA like Istio's istiod) attests the workload (via Kubernetes service account, AWS instance identity, etc.) and mints the SVID. No human ever generates a cert.
2. **Rotation.** Certs are deliberately **short-lived** (hours, sometimes minutes). The agent/sidecar auto-renews well before expiry and hot-swaps without dropping connections. Short lifetimes make revocation largely unnecessary — a compromised cert expires fast.
3. **Revocation.** Classic CRL/OCSP scale poorly and add latency. The modern answer is *short-lived certs + rotating the CA / removing the workload's attestation*. If you must revoke, OCSP stapling reduces the per-request hit.

```
              ┌─────────────┐
              │ SPIRE Server│  (root/intermediate CA, attestation policy)
              └──────┬──────┘
        attest+mint  │  SVID (x509, short TTL)
   ┌─────────────────┼─────────────────┐
┌──┴───┐          ┌──┴───┐          ┌──┴───┐
│agent │          │agent │          │agent │   (per node)
└──┬───┘          └──┬───┘          └──┬───┘
┌──┴───┐          ┌──┴───┐          ┌──┴───┐
│svcA  │== mTLS ==│ svcB │== mTLS ==│ svcC │
└──────┘          └──────┘          └──────┘
```

Production reality: most teams get this for free by adopting a service mesh (Istio, Linkerd) which runs mTLS between sidecars transparently — services emit plaintext locally, the sidecar wraps it in mTLS. You then layer JWT/OAuth scopes for *authorization* on top of the mesh's *authentication*.

### Q16. [Practical] Design service-to-service authN/AuthZ for a zero-trust microservices platform. What layers do you put in place?

Zero-trust principle: **never trust the network**; authenticate and authorize every request, every hop, regardless of being "inside" the perimeter. I'd layer defense in depth:

```
┌────────────────────────────────────────────────────────┐
│ L1 Transport identity:  mTLS (SPIFFE SVID via mesh)      │  who is the workload?
│ L2 Token / app identity: short-lived JWT (client creds   │  what service/scope?
│     or token exchange RFC 8693 to propagate user context)│
│ L3 Authorization policy: OPA / Cedar / mesh AuthZ policy  │  is this call allowed?
│ L4 Observability:        every call logged w/ identity    │  audit + anomaly detect
└────────────────────────────────────────────────────────┘
```

- **L1 (transport):** mTLS gives strong workload identity and encryption. The mesh enforces "service A may even *open a connection* to service B."
- **L2 (token):** Carry application identity and the *original user* context downstream. Don't let service A impersonate the user blindly — use **OAuth2 Token Exchange (RFC 8693)** to mint a downstream token that preserves "acting on behalf of user U" with reduced scope.
- **L3 (policy):** Externalize authorization with a policy engine (Open Policy Agent / AWS Cedar). Services ask "may identity X do action Y on resource Z?" — policy lives outside code and is auditable.
- **L4:** Structured audit logs keyed on workload + user identity feed anomaly detection.

What I'd actually do: mesh-provided mTLS for L1, propagate a narrowly-scoped JWT for L2 (never forward the user's original front-door token deep into the backend — exchange it), OPA sidecar/Cedar for L3. Trade-off: each layer adds latency and ops cost; for a small system, L1+L2 may suffice, but regulated environments (PCI, HIPAA) usually mandate all four.

### Q17. [Theory] What is the OAuth2 token-exchange pattern (RFC 8693) and why is it critical in microservices?

The problem: a user calls the API gateway with their token; the gateway calls Order Service, which calls Payment Service, which calls Ledger. If every hop forwards the *same* user token, you've created a confused-deputy and over-privilege nightmare — Ledger receives a token with the user's *full* front-door scopes, and a compromised middle service can replay it anywhere.

**Token Exchange (RFC 8693)** lets a service present its incoming token to the authorization server and receive a *new* token that is (a) scoped down to only what the next hop needs, (b) audience-restricted to that next service, and (c) optionally carries delegation/impersonation semantics (`act` claim — "Order Service acting on behalf of user U"). This implements **least privilege per hop** and gives you an auditable delegation chain.

```
User --(token: aud=gateway, scope=all)--> Gateway
Gateway --exchange--> token{aud=order,   scope=orders.write, act:gateway}
Order   --exchange--> token{aud=payment, scope=payment.charge, act:user via order}
```

Why critical: it prevents token-replay lateral movement and over-scoping in deep call chains — central to zero-trust. Trade-off: more round-trips to the auth server (mitigate with caching) and more config.

### Q18. [Coding] Implement an mTLS-enabled HTTP client in Java that presents a client certificate. Note version differences.

**Problem:** A service must call a peer that requires client certs. Load a keystore (your cert + private key) and a truststore (the CA you trust for the server), build an `SSLContext`, and use it. Show both legacy and modern (Java 11+) HTTP clients.

```java
import javax.net.ssl.*;
import java.io.FileInputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;

public class MtlsClientFactory {

    public static SSLContext buildContext(String keyStorePath, char[] keyPass,
                                          String trustStorePath, char[] trustPass)
            throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");           // PKCS12 preferred
        try (var in = new FileInputStream(keyStorePath)) { ks.load(in, keyPass); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPass);                                  // client identity

        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(trustStorePath)) { ts.load(in, trustPass); }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);                                           // who we trust

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");     // pin modern TLS
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    // Java 11+ built-in client (preferred in Java 17/21):
    public static HttpClient modernClient(SSLContext ctx) {
        return HttpClient.newBuilder().sslContext(ctx).build();
    }
}
```

**Version differences:** Java 8 had no built-in fluent HTTP client — you used `HttpsURLConnection` (set `setSSLSocketFactory(ctx.getSocketFactory())`) or Apache HttpClient. The `java.net.http.HttpClient` arrived in Java 11 and takes an `SSLContext` directly, which is what you use in Java 17/21. TLS 1.3 is the default since Java 11; explicitly pinning `TLSv1.3` and disabling renegotiation is the secure choice in 2026. **Edge cases:** never disable hostname verification or use a trust-all `TrustManager` (a catastrophic and shockingly common bug); rotate keystores before cert expiry; load secrets from a vault, not the filesystem in plaintext.

### Q19. [Theory] How do certificate-bound access tokens (RFC 8705 / OAuth2 mTLS) defeat token theft?

A normal bearer JWT is a "bearer" credential — *anyone* who steals it (from a log, a compromised proxy, an SSRF) can replay it until it expires. **RFC 8705 (OAuth 2.0 Mutual-TLS)** binds the token to the client's certificate: when the client gets the token over an mTLS connection, the auth server embeds a thumbprint of the client cert in the token (`cnf: { "x5t#S256": ... }`). The resource server then checks that the token was presented over an mTLS connection whose client cert matches that thumbprint.

Result: a stolen token is **useless without the corresponding private key**, which never left the legitimate client. This converts a bearer token into a *holder-of-key* token. It's mandated in high-assurance profiles like FAPI (open banking). The alternative is **DPoP (RFC 9449)** — proof-of-possession via an application-layer signature, which works without mTLS (better for browser/SPA and mobile where full mTLS is impractical). Trade-off: cert-bound needs mTLS infra; DPoP needs client-side key handling but no PKI at the transport layer.

### Q20. [Theory] Explain passkeys / WebAuthn and why they're considered phishing-resistant.

Passkeys are the consumer-facing branding of **FIDO2 / WebAuthn** credentials: public-key credentials where the **private key never leaves the user's device** (secure enclave/TPM) and the server only ever stores a *public* key. Authentication is a challenge-response: the server sends a random challenge, the authenticator signs it with the private key (gated by a local biometric or PIN), and the server verifies with the stored public key.

Why phishing-resistant — the killer property: the signature is **bound to the origin (the relying-party ID / domain)** by the browser and the authenticator. A phishing site at `examp1e.com` cannot get a valid assertion for `example.com`, because the authenticator refuses to sign for the wrong origin and there is *no shared secret to phish* in the first place. This defeats credential phishing, replay, and password database breaches simultaneously. **Synced passkeys** (iCloud Keychain, Google Password Manager) solved the historic "lost device = locked out" problem by syncing the credential across a user's devices, which is what made passkeys finally viable for mass consumer adoption.

```
Registration:  device generates keypair -> server stores PUBLIC key only
Login:         server -> challenge
               device: sign(challenge, privKey) gated by biometric, bound to origin
               server: verify(signature, pubKey)  -- no secret ever transmitted
```

### Q21. [Practical] A regulated fintech mandates MFA. Design the authentication system: factors, step-up, and account recovery.

MFA = proving identity with two+ *categories*: knowledge (password), possession (phone/passkey/hardware token), inherence (biometric). Common mistake: SMS OTP is possession-ish but weak (SIM-swap, SS7 interception) — acceptable as a fallback, not the primary in 2026.

Design:
- **Primary factor:** passkey (WebAuthn) — phishing-resistant, best UX. Fallback to TOTP (authenticator app) over SMS.
- **Step-up authentication:** don't force MFA on every action; require it *contextually*. Login may need one factor; a high-risk action (wire transfer, changing payout account) triggers a *step-up* challenge (re-prompt for the strong factor). Encode this with OIDC `acr`/`amr` claims so downstream services can assert "this token was minted with strong, recent MFA."
- **Risk-based / adaptive:** new device, impossible-travel geolocation, or anomalous behavior elevates the required assurance.
- **Account recovery** (the real weak point — attackers target recovery, not the front door): pre-registered backup factors, recovery codes stored offline, and identity-proofing for high-risk resets, never an unauthenticated "email a reset link" for fund-moving accounts.

What I'd actually do: passkey-first with TOTP fallback, step-up via `acr_values` on sensitive endpoints, adaptive risk engine, and a hardened recovery flow with manual review thresholds. Regulatory drivers: PSD2/PSD3 SCA in the EU mandates strong customer authentication with dynamic linking for payments.

### Q22. [Coding] Implement a TOTP (RFC 6238) generator/verifier in Java with clock-skew tolerance.

**Problem:** Generate and verify 6-digit time-based one-time passwords compatible with Google Authenticator/Authy. Must tolerate small clock skew by checking adjacent time windows, but not so wide as to weaken security.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

public class Totp {
    private static final long STEP = 30;     // seconds per window (RFC 6238)
    private static final int DIGITS = 6;
    private static final int SKEW = 1;        // accept +/- 1 window (~30s each side)

    public static int generate(byte[] secret, long timeSeconds) throws Exception {
        long counter = timeSeconds / STEP;
        byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
        Mac mac = Mac.getInstance("HmacSHA1");           // SHA1 is the RFC default
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] hash = mac.doFinal(msg);
        int offset = hash[hash.length - 1] & 0x0F;       // dynamic truncation
        int binary = ((hash[offset]     & 0x7F) << 24)
                   | ((hash[offset + 1] & 0xFF) << 16)
                   | ((hash[offset + 2] & 0xFF) << 8)
                   |  (hash[offset + 3] & 0xFF);
        return binary % (int) Math.pow(10, DIGITS);
    }

    public static boolean verify(byte[] secret, int code, long now) throws Exception {
        for (int w = -SKEW; w <= SKEW; w++) {
            if (generate(secret, now + w * STEP) == code) return true;
        }
        return false;
    }
}
```

**Time:** O(SKEW) HMAC computations per verify — O(1) effectively. **Space:** O(1). **Edge cases:** leading-zero codes (compare as zero-padded strings in production, not ints, so `001234` matches); replay within the same window (track last-used counter per user to enforce single use); secret must be stored encrypted; wider `SKEW` improves UX but enlarges the attack window — keep it at ±1. **Security note:** rate-limit verification attempts to prevent brute force of the 10^6 space.

---

## 🔴 Expert (15+ yrs)

### Q23. [Theory] You're designing the authentication strategy for a company with on-prem SOAP (WS-Security), legacy SAML SaaS, and new cloud-native OIDC services. How do you create one coherent identity fabric?

The goal is a single source of identity truth without a forced big-bang migration. I'd architect a **federation hub** model:

```
                         ┌──────────────────────────┐
                         │  Identity Broker / IdP     │
                         │  (Keycloak / Entra / Ping)  │
                         │  - canonical user store     │
                         │  - issues OIDC tokens        │
                         └───┬───────┬───────┬─────────┘
              OIDC/JWT ──────┘       │       └────── WS-Fed / STS adapter
            (cloud services)         │              (on-prem SOAP via WS-Trust STS)
                          SAML 2.0 ──┘
                       (legacy SaaS SPs)
```

The broker speaks every dialect outward (OIDC, SAML, WS-Federation via an STS) but normalizes to **one canonical token format internally (OIDC/JWT)**. Cloud services only ever validate JWTs. The WS-Trust STS bridges the SOAP estate: it converts a brokered identity into a `<wsse:Security>` SAML token the SOAP services already understand. Trade-offs: the STS/WS-Fed adapter is legacy-tech maintenance you'd love to retire; XML signature handling carries known vuln classes (signature wrapping, XXE) so you sandbox and patch that adapter aggressively. The strategic move is to **freeze** the legacy adapters (no new integrations), route all new work through OIDC, and decommission SOAP/SAML on a depreciation schedule.

### Q24. [Theory] Critique the "JWT for everything, sessions are obsolete" stance. When are stateful sessions actually the better choice?

This is a litmus test for senior depth — the "stateless JWT everywhere" hype has real costs. JWTs are excellent for *service-to-service* and *short-lived API access*, but for **interactive user sessions in a browser**, stateful sessions are frequently *better*:

- **Revocation:** You cannot un-issue a JWT. If a user logs out, is fired, or their token leaks, the JWT remains valid until expiry. Workarounds (denylists, short TTL + refresh) reintroduce the very server-side state JWTs were meant to eliminate — so you get the worst of both. A session is revoked by deleting one row.
- **Size & exposure:** JWTs are sent on every request and bloat headers; storing them in `localStorage` exposes them to XSS. A session cookie (`HttpOnly`, `Secure`, `SameSite`) is small and unreadable by JS.
- **Data freshness:** JWT claims are a *snapshot*. If a user's roles change, the old token still carries stale roles until refresh.

The honest senior answer: use **opaque session cookies backed by Redis for first-party browser apps** (instant revocation, freshness, XSS-safe), and **short-lived JWTs for M2M and stateless APIs**. The modern OAuth BFF (Backend-for-Frontend) pattern explicitly does this — the browser holds an `HttpOnly` cookie, the BFF holds the actual OAuth tokens server-side. "Sessions are obsolete" is cargo-culting; the right answer is "match the mechanism to the trust and revocation requirements."

### Q25. [Practical] Design an enterprise secret-rotation strategy covering API keys, client secrets, and signing keys with zero downtime.

The non-negotiable principle for zero-downtime rotation: **support multiple valid credentials simultaneously** during the overlap window. You never atomically swap a single secret — you introduce the new one, migrate, then retire the old.

```
SIGNING KEY ROTATION (JWT issuer)
t0: keys = [K1(active)]                       JWKS publishes K1
t1: keys = [K1(active), K2(next)]             JWKS publishes K1,K2   <-- verifiers cache both
t2: keys = [K2(active), K1(retiring)]         sign with K2; K1 still verifies old tokens
t3: keys = [K2(active)]                        K1 removed after max token TTL elapses
```

- **Signing keys (JWT/JWKS):** publish multiple keys in the JWKS endpoint, each with a `kid`. Resource servers fetch JWKS and pick the key by `kid`, so they verify tokens signed by old *or* new keys during overlap. Only remove the old key after `max-token-TTL` has passed. Rotate routinely (e.g. quarterly) and immediately on suspected compromise.
- **Client secrets / API keys:** allow N active secrets per client. Issue new, deploy to callers, then revoke old. This needs the verification path (Q4) to check against *a set* of valid hashes.
- **Automation:** secrets live in a manager (Vault, AWS Secrets Manager) with automatic rotation and lease/TTL. Dynamic secrets (Vault's database creds) are generated per-use and expire — the gold standard, since there's no long-lived secret to leak.
- **mTLS certs:** short-lived auto-rotated certs (SPIRE) — rotation becomes a non-event.

What I'd actually do: Vault with dynamic secrets where possible, JWKS multi-key rotation for signing, dual-secret overlap for static credentials, alerting on secrets nearing expiry, and break-glass procedures for emergency rotation. Crucially, **build the dual-credential capability before you need it** — you cannot rotate gracefully if the system only ever accepts one secret.

### Q26. [Behavioral] Tell me about a time you discovered an authentication vulnerability in production. How did you handle it?

Strong answers follow the STAR structure and demonstrate judgment under pressure, not just technical knowledge. A representative example:

**Situation:** During a security review I found a service validating JWTs but *not* checking the `aud` (audience) claim — so a token minted for the analytics API was being accepted by the payments API. Any service that legitimately got a token could call payments. **Task:** assess blast radius, fix without breaking traffic, and prevent recurrence. **Action:** I first quantified exposure (which clients had valid tokens, whether any cross-service misuse appeared in logs — there was none, so no breach disclosure was triggered). I added audience validation behind a feature flag in *log-only* mode first to find any legitimate callers that would break, fixed the one misconfigured client, then enforced. I added a contract test asserting tokens for other audiences are rejected. **Result:** closed the gap with zero downtime and added a reusable hardened `JwtDecoder` (Q10) to the shared platform library so no team could repeat the mistake.

The meta-point interviewers want: you balanced **speed vs. caution** (didn't break prod by flipping enforcement blindly), **communicated** (involved security, assessed disclosure obligations), and made the fix **systemic** (shared library + test), not local.

### Q27. [Theory] How would you architect authentication to be resilient to a compromise of the central identity provider itself?

A common blind spot: teams treat the IdP as infallible, but if your IdP is breached, *everything* trusting it is compromised. Defense-in-depth for the IdP itself:

1. **Key isolation:** signing keys live in an HSM / cloud KMS; even an IdP application compromise shouldn't yield the raw private key — the attacker can request signatures but you can detect anomalous signing volume and revoke.
2. **Short token TTLs + token binding:** short-lived, cert-bound (RFC 8705) or DPoP-bound tokens limit how much a stolen token or signing capability is worth and for how long.
3. **Independent verification at resource servers:** validate `iss`, `aud`, `exp`, and *expected* `kid`; reject unexpected algorithms. Some high-assurance designs require *two* independent signatures (IdP + a separate attestation) for the most sensitive actions.
4. **Blast-radius segmentation:** separate trust domains/realms per sensitivity tier, so compromising the consumer realm doesn't grant admin/infra access.
5. **Rapid key rotation & revocation playbook (Q25):** rehearsed break-glass to rotate JWKS and force re-auth fleet-wide within minutes.
6. **Detection:** anomalous-issuance monitoring (sudden spike in tokens for an unusual audience), and out-of-band integrity monitoring of the JWKS endpoint.

The expert framing: you cannot make the IdP un-hackable, so you **minimize the value and lifetime of what a compromise yields** and **maximize detection + recovery speed**. This is zero-trust applied to your own trust anchor.

### Q28. [Theory] Where is authentication heading post-2025? Synthesize the major trends.

Several converging trends define the 2026 landscape:

- **Passwordless goes mainstream:** passkeys (synced WebAuthn) are now default on major consumer platforms; the password is increasingly a legacy fallback. Phishing-resistant auth is becoming a baseline expectation, partly driven by regulation and cyber-insurance requirements.
- **Continuous / session-aware auth:** the industry is moving from "authenticate once, trust for an hour" toward **CAEP / Shared Signals Framework (SSF)** — IdPs and resource servers exchange real-time signals ("this session was hijacked," "this device is now non-compliant") to revoke sessions *mid-stream*, closing the JWT-revocation gap natively.
- **Proof-of-possession over bearer:** DPoP (RFC 9449) and mTLS-bound tokens reduce reliance on replayable bearer tokens.
- **Workload identity standardization:** SPIFFE/SPIRE and cloud workload identity federation (OIDC trust between clouds, no static cloud keys) are the norm for M2M.
- **Agentic / AI identity:** a genuinely new problem — autonomous AI agents acting on a user's behalf need scoped, attenuated, auditable delegation. Token exchange (RFC 8693) and emerging agent-authorization patterns are being stretched to cover "agent acting for user with these bounded permissions."
- **Verifiable credentials / decentralized identity** continue to mature for cross-org identity, though enterprise adoption remains gradual.

The throughline: **less standing trust, more continuous verification, and stronger binding of credentials to the holder** — zero-trust principles becoming the default rather than the exception.

---

## ✅ Key Takeaways

- **Match the mechanism to the use case:** Basic auth/API keys for simple internal or app identity; OAuth2 client credentials, mTLS, or `private_key_jwt` for M2M; OIDC/SAML for human SSO; passkeys for phishing-resistant user login.
- **Base64 is not encryption.** Basic auth and API keys demand TLS, hashed storage, and constant-time comparison.
- **Stateless JWT ≠ always better.** Stateful sessions (or the BFF pattern with `HttpOnly` cookies) win for first-party browser apps because of instant revocation and XSS resistance. Keep JWTs short-lived.
- **mTLS gives transport identity; OAuth scopes give application authorization** — layer them. At scale, automate the cert lifecycle (SPIFFE/SPIRE, service mesh) so humans never touch certs.
- **Least privilege per hop:** propagate user context downstream with token exchange (RFC 8693), not by forwarding the raw front-door token.
- **Bind tokens to the holder** (mTLS-bound RFC 8705 or DPoP RFC 9449) to neutralize token theft.
- **SAML and WS-* are legacy you maintain, not greenfield** — broker them behind a single OIDC-speaking identity hub.
- **Design rotation in from day one:** always accept multiple valid credentials so rotation is zero-downtime; prefer dynamic, short-lived secrets.

## ⚠️ Common Pitfalls

- Treating Base64 in Basic auth as if it provided confidentiality; sending it over plain HTTP.
- Putting API keys or tokens in URLs (they leak into logs, browser history, referrers) instead of headers.
- Hand-rolling JWT parsing and accepting `alg: none` or being vulnerable to RS/HS algorithm-confusion; forgetting to validate `aud`, `iss`, and `exp`.
- Using `String.equals` for secret comparison (timing side-channel) instead of `MessageDigest.isEqual`.
- Storing JWTs in `localStorage` (XSS-exposed) when an `HttpOnly` cookie + BFF would be safer.
- Forwarding the user's original broad-scope token through every microservice hop (confused deputy, over-privilege, lateral replay).
- Disabling hostname verification or using a trust-all `TrustManager` to "make mTLS work" — silently destroys the security guarantee.
- Relying on long-lived static secrets/certs with no rotation plan, then facing an outage when one expires or leaks.
- Assuming the IdP can never be compromised; no break-glass key-rotation playbook.
- Treating SMS OTP as strong MFA (SIM-swap, SS7) for high-value/regulated flows.

## 📚 Further Reading

- **OAuth 2.0 Security Best Current Practice** (RFC 9700) and **OAuth 2.1** drafts — the authoritative modern OAuth guidance.
- **RFCs:** 7523 (JWT client assertions), 8693 (token exchange), 8705 (mTLS / cert-bound tokens), 9449 (DPoP), 6238 (TOTP).
- **OWASP** — Authentication Cheat Sheet, REST Security Cheat Sheet, and the OWASP API Security Top 10 (2023).
- **WebAuthn Level 3 / FIDO2** specifications (W3C / FIDO Alliance) and the **passkeys.dev** developer guide.
- **SPIFFE/SPIRE** documentation (spiffe.io) for workload identity and automated mTLS.
- *OAuth 2 in Action* (Richer & Sanderson, Manning) and the **NIST SP 800-63B** Digital Identity Guidelines for assurance levels and MFA.
