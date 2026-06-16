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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q29. [Theory] What is HTTP Digest authentication, and why did it never replace Basic auth despite being "more secure"?

HTTP Digest auth (RFC 7616) was designed to fix Basic auth's most obvious flaw: it never sends the password over the wire. Instead, the server issues a `nonce` (a one-time challenge) and the client returns a hash — `H(H(username:realm:password) : nonce : H(method:uri))` using MD5 (or SHA-256 in the modern RFC). Because only the digest travels, an eavesdropper on plaintext HTTP can't trivially read the password the way they can with Base64. The nonce also provides limited replay protection, and the `qop=auth-int` mode can integrity-protect the body.

So why did it lose? Three structural problems. First, it forces the server to store the password (or `H(username:realm:password)`) in a *recoverable/reversible-equivalent* form — you cannot store a salted bcrypt/argon2 hash, because the server needs that exact intermediate value to recompute the digest. That single requirement makes Digest *worse* for credential-at-rest security than Basic-over-TLS where you store argon2. Second, MD5 is cryptographically broken, and the modern SHA-256 variant has near-zero client/library support. Third, and decisively: once **TLS became universal**, Digest's whole reason for existing (protecting the password on a plaintext channel) evaporated — Basic-over-TLS is simpler and gives you proper salted storage.

The interview takeaway: "more cryptographic ceremony" is not the same as "more secure." Digest optimized for a threat model (plaintext wire) that TLS solved more cleanly, while introducing a worse one (weak password storage). In 2026 you will essentially never deploy Digest; recognizing *why* it's a historical dead-end shows you reason about threat models, not buzzwords.

#### Q30. [Practical] A developer put an API key in a query string (`?api_key=...`) and it's now showing up everywhere. Walk through the cleanup and the proper fix.

This is one of the most common real incidents. Credentials in URLs leak into far more places than people expect: web-server access logs, reverse-proxy and CDN logs, browser history, the `Referer` header sent to third-party sites, APM/observability traces, and shared bookmarks. The moment a key lands in a query string, you should treat it as **compromised**, not "potentially exposed" — you don't know who has read those logs.

```
Cleanup order of operations
1. ROTATE the key immediately (issue a new one, the leaked one is burned).
2. REVOKE the old key — do not just "stop using it."
3. SCRUB where feasible: purge log retention, invalidate CDN logs; you usually
   cannot un-send a Referer header to a third party, hence step 1 is mandatory.
4. SCAN git history (the key is often also committed): git filter-repo / BFG,
   then rotate again because history rewrites don't reach existing clones/forks.
5. AUDIT usage logs for the leaked key for any anomalous calls.
```

The proper fix is to stop accepting credentials in the URL at all. Move the key to a header (`Authorization: Bearer ...` or `X-API-Key`) and have the gateway *reject* requests that carry a key in the query string, so a regressing client fails loudly instead of leaking silently. Add a secret-scanner (GitGuardian, `gitleaks`, GitHub push protection) to CI so the next attempt is blocked before merge, and prefix issued keys (`sk_live_...`) so scanners can recognize them.

The why: leaked credentials are not fixed by deletion — they're fixed by **rotation plus revocation**, because you must assume the secret was already read. Scrubbing reduces future exposure but never undoes past exposure. This is exactly why short-lived tokens beat static keys: a 10-minute token in a log is far less dangerous than a forever-valid key.

#### Q31. [Theory] What is the difference between authentication, a "bearer" token, and "proof-of-possession"? Why does the distinction matter for security?

Authentication is the act of establishing identity. A **token** is the artifact you carry afterward to prove you already authenticated. The critical security property is *how the token is bound to its rightful holder*. A **bearer token** has no binding at all: possession *is* authorization. Whoever physically holds the string — the legitimate client, an attacker who scraped it from a log, a malicious proxy — can use it. An API key, a normal JWT, and a session cookie are all bearer credentials.

A **proof-of-possession (PoP)** token, by contrast, is cryptographically bound to a key the client holds privately. Using the token requires *proving* you possess that key on each request, not merely presenting the token. The two main standards are **mTLS-bound tokens (RFC 8705)**, where the token carries a thumbprint of the client's TLS cert, and **DPoP (RFC 9449)**, where the client signs each request with a private key and the token carries the matching public-key thumbprint (`cnf` claim). A stolen PoP token is inert without the private key, which never leaves the client.

```
BEARER:  token == authorization        steal token  -> full access
PoP:     token + proof(privKey)         steal token  -> useless (no key)
```

Why it matters: the dominant real-world auth failure is not signature forgery — it's **token theft and replay** (XSS, SSRF, log leakage, compromised intermediaries). Bearer tokens make theft catastrophic; PoP tokens make it nearly worthless. The trade-off is operational: bearer is trivially simple, PoP requires client-side key handling (DPoP) or transport PKI (mTLS). The senior move is to use short-lived bearer tokens for low-risk paths and PoP binding for high-value flows (payments, admin, FAPI).

### 🟡 Intermediate — extended

#### Q32. [Theory] OAuth2 vs OIDC: what exactly does OIDC add, and why is using a raw OAuth access token as "proof of login" an anti-pattern?

OAuth2 is an **authorization** framework: it answers "may this client access this resource on the user's behalf?" and yields an *access token* meant to be presented to APIs. It deliberately says nothing about *who the user is* or even *that a user is present* — the access token is opaque to the client by design, and its format and audience are the resource server's concern, not the client's. **OIDC (OpenID Connect)** is a thin identity layer *on top* of OAuth2 that adds authentication: a standardized **ID token** (always a JWT) with identity claims (`sub`, `iss`, `aud`, `nonce`, `auth_time`), a `/userinfo` endpoint, and discovery (`/.well-known/openid-configuration`).

The anti-pattern — using an *access token* to prove a user logged in — is subtle and dangerous. An access token is meant for an *API audience*, not for the client app, and it carries no guarantee about the end user or even that authentication just happened. The classic exploit is the **confused-deputy / token-substitution attack**: an attacker obtains a valid access token for *their own* account at some other app that shares the same provider, and injects it into your login flow. If your app just calls `/userinfo` or trusts the token's `sub`, it logs the attacker in as... whoever the token belongs to, with no verification that the token was issued *to your client* or that the user authenticated *for your app*.

```
WRONG:  client gets access_token -> calls /userinfo -> "logged in"   (no aud/nonce check)
RIGHT:  client gets id_token (JWT) -> verify iss, aud==my client_id, nonce, exp, signature
        -> THEN you have a verified authentication event
```

The ID token fixes this because it is *audience-bound to your client* (`aud == your client_id`) and `nonce`-bound to your specific login request, both of which you must validate. The rule: **access tokens are for calling APIs; ID tokens are for proving a login happened.** Conflating them is how "Sign in with X" implementations get popped.

#### Q33. [Practical] Your client-credentials calls intermittently fail with 401 right after a token-endpoint deploy. How do you diagnose and fix it?

Intermittent 401s clustered around an auth-server deploy almost always point at one of three causes: **key rotation the resource servers haven't picked up, clock skew, or token caching across the rotation boundary.** Start by correlating: do the failures come *only* from instances holding a token minted *before* the deploy? If yes, your cached tokens were signed with a key (`kid`) that the new JWKS no longer publishes — the issuer rotated its signing key and dropped the old one too aggressively.

```
Diagnosis checklist
- Decode a failing token (jwt.io offline / library): note its `kid`, `iss`, `exp`, `aud`.
- curl the issuer JWKS: does the token's `kid` still appear?
    curl -s https://auth.example.com/realms/prod/protocol/openid-connect/certs | jq '.keys[].kid'
- Check resource-server JWKS cache TTL — is it serving a stale key set, or did the
  issuer remove the old key before max-token-TTL elapsed?
- Compare clocks: a fast issuer + slow verifier makes brand-new tokens look not-yet-valid (nbf).
- Check whether the client cached a token across the rotation and is replaying an
  orphaned-kid token.
```

The fix depends on root cause. If the issuer dropped the old key too early, the durable fix is **overlap**: JWKS must publish both old and new keys until `max-token-TTL` after the new key goes active (the rotation discipline from Q25). If it's verifier-side cache staleness, ensure the resource server refreshes JWKS on an unknown `kid` (Spring's `NimbusJwtDecoder` does a forced JWKS refresh when it sees a `kid` it doesn't have, so confirm you didn't pin a static key set). If it's clock skew, fix NTP and allow a small leeway (60s). As a stopgap, having clients **discard cached tokens and re-fetch on a 401** (once, with backoff) turns a hard failure into a self-healing retry — but that masks the real bug, so fix the rotation overlap too.

#### Q34. [Theory] What is the difference between OAuth2 scopes and JWT claims, and how should authorization decisions use each?

Scopes and claims are often conflated but answer different questions. A **scope** is a coarse, *client-facing* delegation grant: it expresses "this token is permitted to perform this category of action" (`orders.read`, `payments.write`). Scopes are requested by the client, consented to (for user flows), and are about *what the token may do*. A **claim** is any assertion *about the subject or context* carried inside the token: identity (`sub`, `email`), org/tenant (`tenant_id`), roles/groups, authentication context (`acr`, `amr`, `auth_time`), and so on. Claims describe *who/what*; scopes describe *permitted operations*.

The practical guidance: use **scopes for coarse-grained gating at the API edge** ("does this token even claim the right to write orders?") and **claims for fine-grained, contextual authorization** ("this token has scope `orders.write`, but may *this tenant's* user modify *this specific* order, and was the login MFA-backed?"). Scopes should stay few and stable; you do not want a scope per resource ID — that explodes the token and couples issuance to your data model. Fine-grained checks belong in the resource server (or an external policy engine like OPA/Cedar) using claims plus the request context.

```
Edge gate (scope):     SCOPE_orders.write present?            coarse yes/no
Resource logic (claims): token.tenant_id == order.tenant_id   contextual
                         AND token.sub owns/admins the order
                         AND token.acr == "mfa" for high-value
```

A common anti-pattern is trying to encode all authorization as scopes (`order:123:write`) — it bloats tokens, leaks data identifiers into the token, and forces re-issuance whenever permissions change. The senior framing: scopes are a *capability filter granted at issuance*; claims feed *runtime, data-aware decisions*. Keep policy out of the token where it changes frequently.

#### Q35. [Practical] How do you configure and debug a Spring Boot SOAP client to send a WS-Security UsernameToken? What are the failure modes?

In the Spring/Java world, WS-Security on SOAP is handled by **Apache WSS4J**, wired in via Spring-WS's `Wss4jSecurityInterceptor` (client) and validated server-side by the same interceptor configured for validation. For a UsernameToken you configure the security *actions*, the username, and how the password is sent — `PasswordDigest` (a hash of nonce+created+password, the WS-Security equivalent of Digest) or `PasswordText` (plaintext, only acceptable inside TLS).

```java
@Bean
Wss4jSecurityInterceptor securityInterceptor() {
    Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();
    interceptor.setSecurementActions("UsernameToken Timestamp"); // order matters
    interceptor.setSecurementUsername("svc-orders");
    interceptor.setSecurementPassword("${vault.soap.password}");
    interceptor.setSecurementPasswordType(WSConstants.PW_DIGEST); // digest preferred
    interceptor.setSecurementUsernameTokenElements("Nonce Created"); // anti-replay
    return interceptor;
}
```

The resulting envelope carries the credential in the SOAP header, not the transport:

```xml
<soapenv:Header>
  <wsse:Security soapenv:mustUnderstand="1" xmlns:wsse="...oasis...wss-...secext-1.0.xsd">
    <wsse:UsernameToken>
      <wsse:Username>svc-orders</wsse:Username>
      <wsse:Password Type="...#PasswordDigest">3eF...=</wsse:Password>
      <wsse:Nonce>qB...=</wsse:Nonce>
      <wsu:Created>2026-06-16T10:00:00Z</wsu:Created>
    </wsse:UsernameToken>
  </wsse:Security>
</soapenv:Header>
```

Classic failure modes and how to read them: (1) **`mustUnderstand` faults** — the server received a `<wsse:Security>` header it didn't recognize/expect; usually a namespace mismatch or the server isn't configured to process WSS. (2) **Timestamp/expired or clock-skew faults** — the `Created`/`Expires` window is too tight or the clocks differ; WSS4J enforces a TTL on timestamps, so sync NTP and widen `timeToLive` slightly. (3) **Replay rejections** — a reused `Nonce` (good, that's anti-replay working) or, conversely, replay *not* being caught because you didn't enable nonce caching server-side. (4) **Digest mismatch** — server stores the password differently than the client hashes it, or one side normalizes the timestamp/nonce differently. When debugging, dump the raw SOAP envelope (Spring-WS `PayloadLoggingInterceptor` plus a SOAP-message logger) — almost every WSS bug is visible in the literal XML, and the digest/nonce/timestamp triple is where to look first.

#### Q36. [Theory] Compare API gateway-level authentication (offloading auth to the edge) versus per-service authentication. What are the trade-offs?

Gateway-offloaded auth puts a single chokepoint — the API gateway / ingress — in charge of validating credentials (JWT signature, expiry, scopes; or terminating mTLS) so that backend services receive *already-authenticated* requests, often with identity passed downstream via a trusted header (`X-User-Id`, `X-Scopes`) or a re-minted internal token. Per-service auth makes every service independently validate the incoming token itself. They are not mutually exclusive, and the right answer is usually "both, in layers."

The appeal of gateway offload is real: one place to upgrade crypto, rotate trust, enforce rate limits and WAF rules, and keep auth libraries out of every service. The danger is the **trusted-header anti-pattern**: if the gateway strips and re-injects `X-User-Id` but a service is *also* reachable directly (mesh, debugging port, SSRF, a misrouted internal call), an attacker who can hit the service directly can forge the header and impersonate anyone, because the service blindly trusts it. Gateway-only auth assumes a hard perimeter — exactly the assumption zero-trust rejects.

```
Gateway-only:    [client]--JWT-->[GW: verify]--X-User-Id-->[svc trusts header]
                 risk: any path that bypasses GW forges identity
Defense in depth: GW verifies + coarse gate; svc ALSO verifies a (re-minted,
                 audience-restricted) token; mTLS ensures only GW can reach svc
```

The trade-off summary: gateway offload optimizes for simplicity and central control but concentrates risk and weakens internal boundaries; per-service auth optimizes for zero-trust and blast-radius containment at the cost of duplicated validation logic and per-service JWKS fetching (cache it). The production answer for anything regulated: terminate external auth at the gateway *and* have services validate a narrowly-scoped internal token (token exchange, Q17), with mTLS ensuring services only accept traffic from the gateway/mesh — never trust a plaintext identity header on its own.

#### Q37. [Practical] Walk through debugging a failing mTLS handshake. What do the common errors actually mean?

mTLS handshake failures are notoriously opaque because the connection dies *before* any application-level error can be returned — you get a TLS alert, not an HTTP status. The single best tool is `openssl s_client`, which lets you reproduce the handshake outside your application and read exactly where it breaks.

```bash
# Test presenting YOUR client cert to the server, verifying against a CA bundle:
openssl s_client -connect peer.internal:8443 \
  -cert client.crt -key client.key \
  -CAfile ca-bundle.pem -tls1_3 -servername peer.internal

# Inspect what CAs the server will ACCEPT for client certs (the CertificateRequest):
openssl s_client -connect peer.internal:8443 2>/dev/null | grep -A20 "Acceptable client"

# Check a cert's chain, dates, and SAN:
openssl x509 -in client.crt -noout -subject -issuer -dates -ext subjectAltName
```

Mapping symptoms to causes: **`tlsv13 alert certificate required` / `peer did not return a certificate`** means the client didn't present a cert at all — wrong keystore, key not loaded, or the client's TLS stack didn't include the cert in the chain. **`unknown ca` / `unable to verify the first certificate`** means a trust-chain problem: the verifier doesn't trust the CA that signed the presented cert, or an **intermediate CA is missing** from the chain the presenter sent (the single most common mTLS bug — the leaf is fine but the intermediate wasn't bundled). **`certificate expired`/`not yet valid`** is date/clock skew (or, genuinely, an unrotated cert). **`bad certificate` / hostname mismatch** means the cert's SAN doesn't match the expected identity, or the server checks CN/SAN against an allow-list.

The deeper lessons: always send the *full chain* (leaf + intermediates), not just the leaf; verify both directions independently with `s_client` (client-trusts-server and server-trusts-client are separate failures); and never "fix" an `unknown ca` by switching to a trust-all manager — that silently disables the entire point of mTLS (the Q18 catastrophic anti-pattern). In meshes (Istio/Linkerd) these errors usually mean the sidecar didn't get its SVID or the trust domains differ between namespaces — check the sidecar's cert with `istioctl proxy-config secret` rather than your application config.

#### Q38. [Theory] What is the SAML "XML Signature Wrapping" (XSW) attack, and why does it make hand-rolling SAML validation so dangerous?

XML Signature Wrapping is a class of attack that exploits the gap between *what gets signature-verified* and *what gets consumed* in an XML document. A SAML response is XML with a digital signature over a specific element (the assertion or the response), referenced by an `ID`. The attack works because many SAML libraries verify the signature over the *original, legitimate* assertion, but the application logic then reads a *different*, attacker-injected assertion elsewhere in the document. The attacker keeps the validly-signed assertion present (so signature check passes) but *wraps* or relocates it and inserts a malicious unsigned assertion that the consuming code actually reads.

```
Attacker takes a legit signed response and restructures it:
<Response>
  <ForgedAssertion id="evil">  user = admin   </ForgedAssertion>   <-- app READS this
  <Wrapper>
    <OriginalAssertion id="x"> ...valid Signature over id="x"... </OriginalAssertion>
  </Wrapper>                                                       <-- verifier CHECKS this
</Response>
```

The root causes are deep in XML's flexibility: signature references by `ID` can be satisfied by an element anywhere in the tree; XPath/`getElementsByTagName` lookups grab the *first* match which may not be the signed one; and XML canonicalization/parsing differences mean the verifier and the consumer can disagree about document structure. Layer on **XXE (XML External Entity)** and DTD-based attacks and SAML's XML attack surface is enormous and historically devastating (Google, GitHub, and many SSO libraries have all shipped XSW-vulnerable code).

This is the concrete reason the Q13 advice — "don't hand-roll SAML, broker it" — is not laziness but security engineering. Mitigations that *must* be in place: verify the signature and then operate **only on the exact signed element** (resolve the reference, extract that subtree, and feed only it to the consumer — "verify then extract," never "verify then re-query"); disable DTDs/external entities in the XML parser; pin the expected `Issuer` and `Audience`; and use a battle-tested library (OpenSAML, Spring Security SAML) rather than DOM-walking yourself. The interview point: SAML's vulnerabilities are mostly *parser/structure* bugs, not crypto bugs — the signature can be perfectly valid while you authenticate the wrong subject.

### 🟠 Advanced — extended

#### Q39. [Theory] Compare JWS-signed vs JWE-encrypted vs nested JWTs. When do you actually need encryption rather than just signing?

A JWT can be protected two ways. **JWS (JSON Web Signature)** signs the token: anyone can *read* the claims (they're Base64, not secret), but no one can *tamper* with them without invalidating the signature. This is the overwhelmingly common case — `eyJ...` tokens are signed, not encrypted. **JWE (JSON Web Encryption)** encrypts the token: the claims are confidential and only the intended recipient (holding the decryption key) can read them, but JWE alone does not, by itself, give you the same author-authentication guarantee you reason about with a signature. A **nested JWT** is the rigorous combination: you *sign* the claims (JWS) and then *encrypt* the result (JWE) — "sign then encrypt" — giving both integrity/authenticity and confidentiality.

The key realization most candidates miss: **a normal JWT is not confidential.** Putting PII, internal role names, tenant topology, or anything sensitive directly in a signed-only JWT means it's readable by the client, by any proxy, and by anyone who pulls it from a log. Signing protects *integrity*, not *secrecy*.

```
JWS:   header.payload.signature           readable + tamper-evident      (default)
JWE:   header.encKey.iv.ciphertext.tag    confidential to recipient
Nested: JWE( JWS(claims) )                 confidential AND authenticated  (sign-then-encrypt)
```

So when do you actually need JWE? Rarely for typical web/API tokens — there you keep sensitive data *out* of the token (reference it by an opaque `sub`/ID the resource server resolves). You reach for JWE/nested when the token *must* traverse untrusted intermediaries while carrying confidential claims it cannot externalize — e.g., certain B2B token-exchange or healthcare contexts, or OIDC ID tokens carrying sensitive attributes through a less-trusted front channel. The trade-offs: encryption adds key-management complexity (now both signing *and* encryption keys must be rotated), larger tokens, and CPU. The default-correct posture is "**sign always, encrypt only when you genuinely cannot keep secrets out of the token,** and if you encrypt, do nested sign-then-encrypt — never rely on encryption for authenticity."

#### Q40. [Practical] Design and operate refresh-token rotation with reuse detection. What breaks if you get it wrong?

Refresh tokens are long-lived by design, which makes a *stolen* refresh token devastating — it mints fresh access tokens indefinitely. **Rotation with reuse detection** (the OAuth 2.1 / BCP-recommended pattern) mitigates this: every time a refresh token is used, the auth server issues a *new* refresh token and invalidates the old one. The whole family of tokens forms a chain. The security magic is **reuse detection**: if an *already-rotated* (old) refresh token is ever presented again, the server treats it as a compromise signal and revokes the entire token family, forcing re-authentication.

```
Normal:   RT1 --use--> {AT2, RT2}    (RT1 now dead)
          RT2 --use--> {AT3, RT3}    (RT2 now dead)

Theft:    attacker steals RT2, uses it -> {AT_x, RT_x}, RT2 dead
          legit client later uses RT2 (its copy) -> REUSE DETECTED
          -> revoke whole family (RT2, RT3, RT_x...) -> both parties re-auth
```

What breaks if you get it wrong, in practice: (1) **No reuse detection** — rotation alone is near-useless; a thief just keeps the chain going and the victim never notices. (2) **Race conditions / network retries** — a client that fires two refreshes concurrently, or retries after a dropped response where the server *did* rotate, will present an "old" token and get its session nuked. You must build a short **grace window** (accept the immediately-previous token for a few seconds) or idempotency on the refresh endpoint, or you'll log out legitimate users constantly — a real and common production pain. (3) **Multi-device sharing** — if two devices share a refresh token (bad client design), rotation makes them fight and trigger false reuse-detection logouts; each device needs its own token family. (4) **Storage/atomicity** — rotation must be atomic (compare-and-swap on the token record) or concurrent refreshes double-issue.

Operationally: store refresh tokens hashed, scope each family to a device/session, keep a tight grace window, log reuse-detection events to your SIEM (they're genuine compromise indicators), and pair short access-token TTLs with this so a leaked *access* token also has a short life. The senior point: refresh-token rotation is a *detection* mechanism as much as a prevention one — its value is turning silent theft into a loud, actionable revocation event, but only if you tune the grace window so normal concurrency doesn't masquerade as attack.

#### Q41. [Theory] How does TLS session resumption interact with mTLS client identity, and what's the security pitfall?

TLS session resumption (session IDs in TLS 1.2, **session tickets / PSK resumption** in TLS 1.3, and 0-RTT early data) exists to skip the expensive full handshake on reconnection by reusing previously negotiated parameters. With ordinary one-way TLS this is purely a performance optimization. With **mTLS**, it has a subtle identity implication: a resumed session *inherits the client identity that was authenticated during the original full handshake*, because the resumption "remembers" the negotiated security context — the client does **not** re-present its certificate on a resumed connection. The server trusts the resumed connection as the same authenticated peer.

```
Full handshake:   client presents cert -> identity = CN/SAN(cert), bound to session ticket
Resumed:          PSK/ticket -> identity reused, NO new cert exchange
0-RTT (TLS1.3):   early app data BEFORE handshake completes -> replayable
```

The pitfalls are real and bite production systems. First, **0-RTT early data is replayable by design** — TLS 1.3's spec explicitly warns that early data has no replay protection, so you must never send non-idempotent, identity-sensitive requests (a payment, a "delete account") in 0-RTT over mTLS; the request can be captured and replayed. Disable 0-RTT for mutating endpoints. Second, **revocation lag**: if you revoke a client's certificate but a session ticket from before revocation is still valid, the client can resume and keep its authenticated identity past the revocation — so session-ticket lifetime must be bounded and aligned with your revocation expectations. Third, **ticket-key management**: the server's session-ticket encryption keys, if long-lived or shared insecurely across a fleet, can let an attacker forge/resume sessions; these keys need rotation too.

The senior framing: resumption and mTLS both touch *identity continuity*, and the danger is assuming "the cert is checked on every connection" when resumption means it isn't. Mitigations: short session-ticket lifetimes, rotate ticket keys, disable 0-RTT for non-idempotent operations, and don't rely solely on per-connection cert checks for authorization of sensitive actions — re-assert identity at the application layer (a token) for the highest-value calls.

#### Q42. [Practical] Production incident: at 03:00 every service starts returning 503 and logs show TLS errors. Root cause is an expired intermediate CA. How do you respond and prevent recurrence?

An expired **intermediate CA** is a uniquely nasty outage because it fails *everything signed by it simultaneously* and the error messages point at leaf certs, not the real culprit. The first move is correct diagnosis under pressure: the symptom (`unable to get local issuer certificate` / `certificate has expired` across many unrelated services at once) plus a clean clock points away from individual leaf certs toward a *shared* trust-chain element. Pull the chain and read the dates at each level.

```bash
# Walk the served chain and print validity for EVERY cert in it:
openssl s_client -connect svc.internal:8443 -showcerts </dev/null 2>/dev/null \
  | openssl crl2pkcs7 -nocrl -certfile /dev/stdin \
  | openssl pkcs7 -print_certs -noout -text | grep -E "Subject:|Not After"
# Check the intermediate specifically:
openssl x509 -in intermediate.pem -noout -subject -dates
```

Immediate response (incident mode): if the intermediate truly expired, you cannot "wait it out" — you must **reissue the intermediate** (or have the CA do so) and **redistribute the new chain** to every serving endpoint and truststore, then reload TLS without full restarts where possible. If reissuance is slow, short-term mitigations are ugly but real: temporarily extend trust to a backup chain you pre-provisioned, or (last resort, time-boxed, internal-only) relax verification on internal hops while you push the fix — never on external/edge. Communicate: this is a fleet-wide outage; declare the incident, assign a comms owner, and capture timeline for the postmortem.

Prevention is the part interviewers care about most, because expiry outages are 100% predictable and therefore 100% preventable: (1) **Monitor certificate expiry as a first-class SLO** — alert at 30/14/7 days remaining for *every* cert *including intermediates and roots* (intermediates are the ones everyone forgets). (2) **Automate renewal** — ACME/cert-manager for leaf certs, and for internal PKI move to **short-lived auto-rotated certs (SPIRE)** so "expiry" stops being a manual event. (3) **Inventory** — you cannot monitor certs you don't know exist; maintain a discovered inventory, not a spreadsheet. (4) **Chaos/game-day** the failure: deliberately test expiry handling in staging. The deeper lesson is cultural: cert expiry is an *operational* failure, not a security one, and the durable fix is making certificate lifecycle a non-human, monitored, automated process — exactly why SPIFFE/SPIRE and ACME exist.

#### Q43. [Theory] In a JWT-based zero-trust system, how do you propagate end-user context to deep downstream services without forwarding the front-door token? Compare the options.

The naive approach — forward the user's original front-door token through every hop — creates the confused-deputy and over-privilege problems covered in Q17. There are three principled alternatives, each with different trust and operational properties. The goal is to give a deep service (a) verifiable user identity, (b) only the privilege that hop needs, and (c) an auditable delegation chain.

```
Option A: TOKEN EXCHANGE (RFC 8693)  -- per-hop re-mint at the auth server
  Order svc presents incoming token -> AS returns new token{aud=payment, scope-down, act:user}
  + strongest: audience-bound, scope-attenuated, central audit, revocable
  - cost: round-trip(s) to AS per exchange (cache), config heavy

Option B: SIGNED IDENTITY PROPAGATION  -- gateway mints an internal "identity assertion"
  Gateway -> short-lived signed JWT carrying {sub, tenant, acr} verified by each service
  + cheap (no per-hop AS call), good user-context fidelity
  - scope attenuation is weaker unless you also constrain audience per call

Option C: TRUSTED-HEADER + mTLS  -- pass X-User-Id over a mesh-authenticated channel
  + cheapest; - ONLY safe if mTLS guarantees the header source AND services never
    reachable off-mesh; the Q36 anti-pattern if that assumption breaks
```

The decision hinges on your threat model and scale. **Token exchange** is the most defensible for regulated/high-value systems because it enforces least privilege *per hop* and gives central revocation and audit — at the cost of latency you mitigate with caching. **Signed identity propagation** (a gateway-minted internal token, distinct from the external one) is a strong middle ground: you stop forwarding the powerful front-door token, every service still cryptographically verifies user context, and you avoid an auth-server round-trip per hop — but you must still constrain *what each hop can do*, typically by combining it with mesh authorization or per-service scopes. **Trusted-header over mTLS** is fine *only* when the mesh genuinely guarantees no off-mesh path to the service; otherwise it's forgeable.

The senior synthesis: never forward the front-door token deep into the backend; mint a *new, attenuated, audience-bound* credential at the trust boundary. Use token exchange where per-hop least privilege and central audit are required, signed identity propagation where latency matters more and the call graph is well-controlled, and reserve trusted-headers for inside a strongly-authenticated mesh with defense in depth behind it.

#### Q44. [Practical] Your JWKS-based JWT verification adds latency spikes and occasional outages tied to the issuer's `/jwks` endpoint. How do you make verification resilient?

If your resource server's availability is coupled to the issuer's JWKS endpoint being reachable on the hot path, you've built a hidden hard dependency: every JWKS fetch on a cache miss (or an over-eager refresh) blocks request handling, and if the issuer has a blip, *your* API throws 5xx even though the tokens themselves are perfectly valid. The fix is to treat the JWKS as **cached, asynchronously refreshed, and gracefully degrading** rather than fetched synchronously per surprise.

```
Resilient JWKS strategy
- CACHE keys with a sane TTL (minutes-hours), keyed by `kid`.
- Refresh PROACTIVELY in the background before TTL expiry (no request blocks on it).
- On an UNKNOWN `kid`: do ONE forced refresh (rate-limited, e.g. max 1/5min) to
  pick up a just-rotated key -- but cap it so a flood of bad-kid tokens can't
  hammer the issuer (a DoS amplification vector).
- On issuer UNREACHABLE: keep serving with the last-known-good key set
  (stale-while-error). Valid tokens keep working through an issuer outage.
- NEVER fail-open on signature/expiry -- only the *key fetch* degrades gracefully.
```

The concrete pitfalls each map to a guardrail. **Latency spikes** come from synchronous fetches on cache miss — move refresh to a background task with proactive renewal. **Outage coupling** comes from no stale fallback — keep last-known-good keys and serve through transient issuer downtime (a 5-minute issuer blip should never take down your API). **DoS amplification** comes from forced-refresh-on-unknown-kid with no rate limit — an attacker sending tokens with random `kid` values triggers unbounded JWKS fetches; cap the refresh rate and treat unknown-kid floods as suspicious. **Stale-key correctness** is the counterweight: your cache TTL plus the issuer's rotation overlap (Q25/Q33) must be consistent so you never *need* a hot fetch under normal operation.

In Spring, `NimbusJwtDecoder` with a `JWKSource` that uses caching plus a rate-limited remote source covers most of this; the operational discipline is to (a) verify your decoder refreshes on unknown `kid`, (b) confirm it doesn't fail-closed on a transient JWKS fetch error if a cached key is still valid, and (c) alert on JWKS fetch error rate. The principle: **signature verification is local and should stay local** — key *distribution* is the network dependency, so cache it hard, refresh it ahead of time, and degrade it gracefully, while never relaxing the actual cryptographic checks.

### 🔴 Expert — extended

#### Q45. [Theory] What is the OAuth2 "confused deputy" attack and how do `aud`, `azp`, the `nonce`, PKCE, and resource indicators each defend against a different variant?

"Confused deputy" is the umbrella for attacks where a trusted intermediary is tricked into using its authority on behalf of an attacker. In OAuth/OIDC it manifests in several distinct variants, and the elegant thing is that each standard defense closes a *specific* one — naming them precisely is the expert signal.

- **Token redirection / wrong-audience replay** (a token issued for service A is replayed at service B): defended by the **`aud` claim** and audience validation. Service B rejects any token whose `aud` isn't B. Without it, any service that obtains a valid token can call any other — the exact Q26 incident.
- **Authorization-code interception** (a malicious app on the same device intercepts the redirect with the auth code): defended by **PKCE (RFC 7636)**. The legitimate client sends a `code_challenge` up front and must present the matching `code_verifier` to redeem the code, so a stolen code is useless without the verifier. Now mandatory for *all* clients in OAuth 2.1, not just public/mobile ones.
- **ID-token replay / injection into a login flow** (replaying a valid ID token from another session): defended by the **`nonce`**, which binds the ID token to one specific authentication request the client initiated.
- **Client substitution** (which client was a token actually issued to?): the **`azp` (authorized party)** claim names the client the token was issued for, distinguishing it when a token has multiple audiences.
- **Token leakage across resource servers when one client talks to many APIs**: defended by **Resource Indicators (RFC 8707)**, where the client names the target resource at issuance so the AS mints a token audience-bound to *that* resource, not a broadly-valid one.

```
Variant                         Defense          Mechanism
wrong-audience replay           aud              reject tokens not for me
which client got it             azp              bind token to authorized party
auth-code interception          PKCE             code_verifier proves redeemer
id-token injection/replay       nonce            bind id_token to this login req
over-broad token across APIs    resource ind.    audience-bind at issuance
```

The unifying expert point: every one of these defenses is a form of **binding** — binding a token to its audience, to its client, to the request that produced it, or to a possession proof. Confused-deputy attacks all exploit *missing binding* somewhere in the chain. A mature OAuth deployment is one where each token can answer "who am I for, who got me, and which request created me?" — and the resource server actually *checks* all three. The most common real-world failure is shipping a verifier that checks the signature and expiry but silently skips `aud`/`nonce`, which is how otherwise-correct systems get walked through laterally.

#### Q46. [Practical] You must migrate a 200-service estate from a shared static API key (and some legacy WS-Security SOAP) to mTLS + OAuth2 token exchange. Design the phased rollout, the dual-stack period, and the rollback story.

A migration this size lives or dies on **dual-stack coexistence and observability**, not on the target design (which is the easy part). The cardinal rule: you never flip identity mechanisms atomically across 200 services; you run old and new in parallel, measure, then retire the old. Every phase must be independently reversible.

```
Phase 0  Inventory & baseline
  - Discover every caller->callee edge (mesh telemetry / access logs). You cannot
    migrate a call graph you can't see. Tag the WS-Security SOAP estate separately.
  - Stand up: AS (client-credentials + RFC 8693 exchange), mesh/SPIRE for mTLS,
    cert lifecycle automation. NO traffic uses them yet.

Phase 1  Transport identity (mTLS), permissive mode
  - Roll out sidecars/SPIRE issuing SVIDs. Mesh in PERMISSIVE mTLS: accept both
    mTLS and plaintext. Zero behavior change; you're just provisioning identity.
  - Verify every workload has a valid SVID before tightening anything.

Phase 2  App identity (OAuth), dual-accept at resource servers
  - Resource servers accept (a) the old shared API key OR (b) a valid bearer JWT.
  - Migrate callers one edge at a time to fetch client-creds tokens (cached, Q9).
  - Run audience validation in LOG-ONLY first (Q26) to find mis-scoped callers.

Phase 3  Least privilege via token exchange
  - Replace front-door-token forwarding with per-hop exchange (RFC 8693).
  - Tighten scopes/audiences per edge as each is verified clean in logs.

Phase 4  SOAP/WS-Security bridge (don't rewrite, adapt)
  - Front the SOAP estate with an STS/adapter that converts a brokered OIDC identity
    into the <wsse:Security> token the SOAP services already accept. FREEZE the SOAP
    side (no new integrations); migrate callers to the adapter, not to the SOAP auth.

Phase 5  Tighten + retire
  - Mesh STRICT mTLS (reject plaintext) edge-by-edge once each is verified.
  - Remove API-key acceptance per resource server only after its callers send JWTs.
  - Decommission the shared key last, on a published deprecation date.
```

The **dual-stack period** is where discipline matters: each resource server must, for a window, accept *both* credentials and *log which one was used* with the caller identity — that log is your migration burndown ("how many edges still use the old key?") and your safety net. Drive cutover per *edge*, not per service, because one service may be called by many. Gate each tightening behind a metric: 0% legacy usage on an edge for N days before you remove legacy acceptance there.

The **rollback story** must be per-phase and cheap. Mesh permissive→strict is the riskiest step, so it's the one you roll back most readily: revert to permissive (instant) if good traffic drops. Token-exchange and audience tightening roll back to log-only mode. Crucially, *don't* delete the old credential acceptance until well after the new path is proven — keeping the old path warm is your rollback. Anti-patterns to call out: big-bang strict-mTLS (locks out anything you missed in inventory), removing the shared key before all callers migrated (outage), and rewriting the SOAP services (huge risk for zero auth benefit — adapt, don't rewrite). The throughline: **migrate transport identity and app identity in separate, independently-reversible tracks, drive by per-edge telemetry, and let the legacy path stay alive as your rollback until the new path is boringly reliable.**

#### Q47. [Theory] Synthesize the security and operational trade-offs of putting authentication state in (a) a stateless JWT, (b) a server-side opaque token / session, and (c) a token-introspection model. When does each win at scale?

These three represent fundamentally different answers to "where does the truth about a credential live?" and the choice ripples into latency, revocation, privacy, and failure modes. **(a) Stateless JWT** puts the truth *in the token* — the resource server verifies a signature locally and reads claims, no lookup. **(b) Opaque token / server-side session** puts the truth *in a store* — the token is a meaningless reference, and every request resolves it against Redis/DB. **(c) Introspection (RFC 7662)** puts the truth *at the issuer* — the resource server calls the auth server's `/introspect` endpoint to ask "is this token still valid, and what are its claims?"

```
                  Verify cost     Revocation        Claims privacy    Failure mode
(a) JWT           local, O(1)     hard (TTL/denylist) claims exposed   issuer-independent
(b) opaque/sess   1 store lookup  instant (delete)    opaque to client store outage = down
(c) introspection 1 AS call/req   instant (AS knows)  opaque to client AS outage = down
                  (cache to fix)
```

The trade-offs sharpen at scale. **JWT wins** when you need horizontal scalability and issuer-independence: any node verifies offline, the auth server isn't on the hot path, and an issuer outage doesn't take down request handling (you already have the public keys). Its cost is revocation — you cannot un-issue it, so you live with short TTLs + refresh, and a denylist reintroduces state. It also leaks its claims to anyone holding it (Q39). **Opaque tokens/sessions win** when revocation and freshness dominate (first-party browser apps, the Q24 conclusion): delete one row to log a user out everywhere, claims never leave the server, and a leaked token reveals nothing. The cost is a mandatory store lookup per request and a hard dependency on that store's availability and latency — you've recreated the scaling problem JWTs solved. **Introspection wins** when resource servers must defer to a central authority for real-time validity (the token might be a JWT *or* opaque, and the AS is the source of truth) — common in API-gateway and partner-API setups. Its naive cost is an auth-server round-trip *per request*; you make it viable by **caching introspection results for a short window**, which trades a little revocation latency for huge throughput gains.

The expert synthesis is that this is not "pick one" but "match per surface, and combine": short-lived JWTs for internal M2M and stateless APIs (scale + issuer-independence), opaque server-side sessions behind a BFF for first-party browser auth (instant revocation + XSS safety), and cached introspection at gateways where central, real-time validity matters. The cross-cutting principle is that **revocation latency, verify-path latency, and availability coupling form a trilemma** — pure JWT minimizes latency/coupling but worsens revocation; pure introspection/session maximizes revocation but adds a hot-path dependency. Modern designs (short TTLs, the Shared Signals Framework/CAEP from Q28, cached introspection) are all attempts to buy back revocation on the JWT model without paying full per-request lookup cost.

#### Q48. [Theory] How do you authenticate and constrain AI agents acting on a user's behalf, and why do classic OAuth scopes fall short for agentic workloads?

Agentic AI is the genuinely new authentication problem of this era because it breaks an assumption baked into OAuth: that the entity holding a token exercises *bounded, predictable* behavior chosen at design time. An autonomous agent acting "on behalf of" a user is simultaneously a *delegate* (it carries the user's authority), a *machine* (no human is present per-action to consent), and *non-deterministic* (it decides at runtime which tools/APIs to call). Granting it the user's full token is wildly over-privileged; granting it a static client-credentials identity loses the "on behalf of *this* user, for *this* task" binding that audit and least-privilege require.

The building blocks already exist but get stretched: **token exchange (RFC 8693)** with the `act`/`may_act` claims expresses "agent acting on behalf of user U," and **scope attenuation** narrows what the delegated token can do. The gaps that make agents hard: (1) **scopes are too coarse and static** — `email.send` either lets the agent send *any* email or none; agentic work needs *task-bounded* constraints ("send up to 3 emails, only to recipients in this thread, within the next 10 minutes"). (2) **No native notion of a budget/quota or step count** in a token — an agent in a loop can exhaust authority catastrophically. (3) **Chained delegation** — agent calls agent calls tool — needs each hop to *attenuate further*, never re-broaden, and remain auditable end to end. (4) **Consent and revocation UX** — the user must be able to see and yank an agent's standing authority, and high-impact actions should require fresh human-in-the-loop confirmation rather than standing scope.

```
User --(consent: task T, bounded)--> Agent
  token{ sub:user, act:agent, scope: attenuated, aud: tool,
         constraints: { max_actions, expiry: short, resource_pattern } }
Agent --(must attenuate, never broaden)--> Sub-agent/Tool
  every hop: narrower scope, shorter life, full act-chain for audit
High-impact action -> step-up: re-confirm with the human, don't rely on standing grant
```

The senior framing: treat an agent as a **dynamically-attenuated delegate**, not a service account. Mint short-lived, audience-bound, *task-scoped* tokens via token exchange; enforce least privilege *and* runtime budgets (action counts, time windows, resource patterns) ideally via an external policy engine (OPA/Cedar) that can reason about request context the token can't encode; preserve a full, auditable delegation chain (`act` claims) so every action traces back to "agent X acting for user U under task T"; and gate genuinely high-impact operations behind fresh human confirmation. The honest caveat for an interview: the standards here are *still maturing* in 2026 (emerging agent-authorization profiles, MCP-style scoped tool access), so the defensible answer combines proven primitives (RFC 8693, PoP binding, external policy, short TTLs) with explicit acknowledgment that "standing broad authority for an autonomous, non-deterministic delegate" is the core risk every design must minimize.

### 🟢 Basic — extended (continued)

#### Q49. [Theory] What exactly is a "bearer token," and what is the single most important rule when handling one?

A bearer token is any credential where **possession alone grants access** — the protocol attaches no further proof that the presenter is the rightful owner. The HTTP `Authorization: Bearer <token>` scheme (RFC 6750) is the canonical form, used for OAuth2 access tokens and most JWTs, but conceptually an API key and a session cookie are bearer credentials too. "Bearer" is literally banking language: a bearer bond pays whoever physically holds it, no questions asked. That is precisely the security model — and the risk.

The single most important handling rule follows directly from that definition: **treat a bearer token like cash and never let it touch anything that persists or forwards it.** Concretely that means TLS always (it's plaintext-equivalent on the wire), never in URLs/query strings (logs, history, `Referer`), never in `localStorage` if XSS is a concern (prefer `HttpOnly` cookies for browser contexts), never logged, and always short-lived so a leak self-heals. Because there is no holder-binding, your *only* meaningful defenses are confidentiality in transit/at rest and minimizing lifetime and scope.

The natural follow-up an interviewer probes: "how do you make a token *not* purely bearer?" — and the answer connects to proof-of-possession (mTLS-bound RFC 8705, DPoP RFC 9449), which adds the missing holder-binding so a stolen token is inert. Showing you understand bearer tokens as the *default-but-fragile* model, and PoP as the upgrade, demonstrates you grasp why so much modern auth engineering is about *binding* credentials to their holder.

#### Q50. [Practical] A frontend team is about to store a JWT in `localStorage` "because it's easy." What do you tell them, and what do you recommend instead?

The concern is **XSS exposure**. Anything in `localStorage` (or `sessionStorage`) is readable by *any* JavaScript running on the page — including a single injected malicious script from a compromised dependency, a vulnerable ad, or a stored-XSS payload. If that script can read the JWT, it can exfiltrate it and the attacker now holds a fully valid bearer token (Q49) until expiry, replayable from anywhere. The "easy" choice trades a small dev convenience for turning every XSS bug into a full account takeover.

```
localStorage JWT:  any JS on page can read it -> 1 XSS = token theft = ATO
HttpOnly cookie:   JS CANNOT read it (no document.cookie access) -> XSS can't exfiltrate
                   but cookies are auto-sent -> CSRF risk -> needs SameSite + CSRF token
```

The recommendation, in order of preference. Best for first-party browser apps: the **BFF (Backend-for-Frontend) pattern** — the actual OAuth tokens live server-side, and the browser holds only an opaque `HttpOnly; Secure; SameSite` session cookie. XSS can't read the cookie, and the real tokens never reach the browser at all (this is the Q24 conclusion). If you must keep a token in the browser, an **`HttpOnly` cookie** beats `localStorage` because script can't read it — at the cost of CSRF, which you mitigate with `SameSite=Lax/Strict` plus a CSRF token. If the architecture truly requires JS to hold the token (some SPA + cross-domain API setups), keep it **in memory only** (a variable, lost on refresh) with a short TTL and silent refresh, never persisted.

The framing that lands: "`localStorage` doesn't make you vulnerable to XSS — but it makes XSS *catastrophic* instead of contained. We can't guarantee zero XSS across our whole dependency tree, so we design the token storage to survive an XSS bug." That's defense-in-depth reasoning, and it reframes the decision from "easy vs. annoying" to "contained vs. catastrophic blast radius."

### 🟡 Intermediate — extended (continued)

#### Q51. [Theory] What is PKCE, why was it created for mobile/SPA clients, and why is it now mandatory for *all* OAuth2 clients including confidential ones?

PKCE (Proof Key for Code Exchange, RFC 7636, pronounced "pixy") hardens the OAuth2 **authorization code** flow against code interception. The client generates a random `code_verifier`, hashes it into a `code_challenge`, and sends the *challenge* on the initial authorization request; when it later redeems the authorization code at the token endpoint, it must present the original `code_verifier`. The auth server checks `SHA256(verifier) == challenge`. The effect: an authorization code stolen in transit is worthless, because the thief doesn't possess the verifier needed to redeem it.

It was created for **public clients** — mobile apps and SPAs — because they cannot keep a `client_secret` (it's embedded in shipped binaries/JS that anyone can extract). The acute threat there is *authorization-code interception*: on mobile, the redirect comes back via a custom URI scheme or app link that a malicious app on the same device can register and hijack, grabbing the code. Without a client secret to authenticate the redemption, the stolen code alone gets tokens. PKCE substitutes a *per-request, dynamically-generated secret* (the verifier) for the missing static client secret, plugging the gap without shipping any long-lived secret.

```
Client: verifier = random(); challenge = SHA256(verifier)
  /authorize?...&code_challenge=<challenge>&code_challenge_method=S256
  <- code (an attacker might intercept THIS)
  /token  code=<code>&code_verifier=<verifier>     <- thief lacks verifier -> fails
```

Why mandatory for *everyone* now (OAuth 2.1): defense in depth doesn't stop at public clients. Even a confidential client benefits — PKCE defends against code injection/substitution attacks and adds protection if the client secret is somehow weak or leaked, at essentially zero cost. The OAuth Security BCP (RFC 9700) and OAuth 2.1 therefore require PKCE for the code flow universally and *deprecate* the implicit flow entirely. The interview point: PKCE started as a mobile/SPA patch but proved valuable as a *general* binding of the code to the client that initiated the flow — another instance of the "bind the credential to the request" theme that runs through modern OAuth.

#### Q52. [Practical] How do you correctly validate an incoming user-context propagation header (`X-User-Id` / JWT) at a downstream service, and what's the dangerous mistake teams make?

The dangerous mistake is **trusting the header because it "comes from inside."** A team puts a gateway in front that authenticates users and injects `X-User-Id: alice` for downstream services, and the services read it directly. This is safe *only* if there is provably no path to reach the service except through the gateway. The moment a service is reachable by any other route — a service-mesh peer, a debugging port, an SSRF-able internal endpoint, a misrouted internal call — an attacker who can hit it directly just sends `X-User-Id: admin` and is instantly anyone. Plaintext identity headers are forgeable by definition; the network is not a trust boundary (zero-trust, Q16/Q36).

```
WRONG: gateway -> X-User-Id: alice -> svc reads it raw
       (any non-gateway path can forge X-User-Id)
RIGHT: gateway mints SIGNED short-lived JWT carrying {sub, tenant, acr, aud=svc}
       svc VERIFIES signature + aud + exp before trusting identity
       AND mTLS ensures only the gateway/mesh can open a connection to svc
```

The correct pattern is to make the propagated identity **cryptographically verifiable** and **transport-restricted**. The gateway mints a signed, short-lived, audience-bound internal token (not a forwarded front-door token — Q43) and every downstream service *verifies* it: signature against the gateway's key, `aud` equals this service, and `exp` fresh. Layer mTLS so the service only accepts connections from the gateway or mesh, and have it strip any inbound `X-User-Id`/`X-Scopes` headers from external callers so they can never be smuggled in. In Spring, this is the same `oauth2ResourceServer().jwt()` machinery (Q10) pointed at the internal issuer's keys.

The senior framing: "*propagating* identity and *trusting* identity are different operations." A header is fine as a *transport* for identity, but trust must come from a cryptographic check or a guaranteed-exclusive channel — never from the header's mere presence. The forged-header impersonation is one of the most common real internal-pentest findings precisely because it's invisible in normal traffic and only surfaces when someone deliberately bypasses the gateway.

### 🟠 Advanced — extended (continued)

#### Q53. [Theory] Compare CRL, OCSP, OCSP stapling, and short-lived certificates as revocation strategies for mTLS at scale. Why do large fleets converge on short-lived certs?

Certificate revocation answers "this cert was valid but must no longer be trusted (key compromise, decommission)." The classic mechanisms scale poorly in different ways. A **CRL (Certificate Revocation List)** is a CA-signed list of revoked serials that verifiers download periodically; it grows unbounded, is stale between refreshes, and is bandwidth-heavy. **OCSP (Online Certificate Status Protocol)** lets a verifier query the CA's responder for a single cert's status in real time — fresher than CRLs, but it puts the CA responder on the *hot path* of every handshake (latency + a hard availability dependency + a privacy leak, since the CA learns who talks to whom). **OCSP stapling** fixes the hot-path problem: the *server* periodically fetches a signed, time-stamped OCSP response and "staples" it into the TLS handshake, so the verifier gets freshness without contacting the CA itself — but stapling client certs in mTLS is awkward and patchily supported.

```
CRL:            verifier downloads big list, stale, bandwidth-heavy
OCSP:           per-handshake call to CA -> latency + availability + privacy cost
OCSP stapling:  server attaches fresh signed status -> no per-verify CA call
Short-lived:    cert TTL = minutes/hours -> revocation ≈ "just let it expire"
```

Large fleets converge on **short-lived certificates** because they make revocation *largely unnecessary*: if a cert lives for an hour, a compromised key is only useful for at most that hour, and you "revoke" by simply not renewing (and, for active compromise, by removing the workload's attestation so it can't get a fresh cert). This sidesteps the entire scaling/availability/privacy mess of CRL/OCSP. The trade-off is that short-lived certs demand **fully automated issuance and rotation** — which is exactly what SPIFFE/SPIRE and service-mesh CAs (istiod) provide, minting and hot-swapping SVIDs with no human involvement (Q15). The cost moves from "operate a revocation infrastructure" to "operate an automated issuance infrastructure," and the latter is far more reliable because rotation is a continuous, tested code path rather than a rare emergency procedure.

The expert synthesis: revocation is a *negative* (deny something previously allowed), which is intrinsically hard to propagate quickly and reliably across a large system; short lifetimes turn it into a *positive* (keep renewing what's still allowed), which is easy to automate and self-correcting. That inversion — "make the safe state the default and let trust *expire* rather than be *revoked*" — is why "short-lived everything" is the dominant modern posture for both certs and tokens.

#### Q54. [Practical] Your service mesh runs mTLS in PERMISSIVE mode and someone proposes flipping the whole fleet to STRICT in one change. Why is that dangerous, and how do you roll it out safely?

Permissive mTLS accepts *both* mTLS and plaintext connections; strict mTLS *rejects* plaintext. Flipping the entire fleet to strict in one change is dangerous because permissive mode has been silently *masking* every workload that isn't actually doing mTLS yet — a sidecar that failed to get its SVID, a legacy client outside the mesh, a healthcheck or scrape from a non-mesh source, a cross-namespace call with a trust-domain mismatch. In permissive mode all of those keep working over plaintext, so you have **no signal** that they'd break. Strict mode turns every one of those into an instant connection refusal, fleet-wide, at once — a self-inflicted outage with a wide and hard-to-diagnose blast radius.

```
Permissive: accepts mTLS OR plaintext  -> non-mTLS callers "work" (hidden debt)
STRICT (big bang): rejects plaintext    -> every hidden non-mTLS path breaks NOW
Safe path: observe -> per-namespace strict -> widen -> verify each step
```

The safe rollout is **incremental and observation-driven**. First, *measure*: mesh telemetry (Istio metrics, access logs) reports the ratio of mTLS vs plaintext connections per workload — you cannot tighten what you haven't measured. Drive that plaintext ratio to zero by fixing the stragglers (provision missing SVIDs, bring legacy clients into the mesh or front them with a gateway, reconfigure healthchecks). Then flip to strict **one namespace/workload at a time**, starting with low-risk services, using a `PeerAuthentication` policy scoped to that namespace rather than mesh-wide. Verify good traffic holds after each step before widening. Keep the rollback trivial: reverting a namespace's policy back to permissive is instant and non-destructive, which is exactly why you do it per-namespace — a mesh-wide strict policy has a mesh-wide rollback, defeating the point.

The senior framing mirrors the Q46 migration discipline: **never flip a security posture atomically across a fleet that has hidden state.** Permissive-to-strict is a *tightening* operation, and tightening should always be staged behind telemetry with a per-unit, instantly-reversible rollout. The anti-pattern — "just turn on strict everywhere, it's more secure" — is technically correct about the end state and operationally reckless about the path to it.

#### Q55. [Theory] How would you design authentication for a multi-tenant SaaS so that tenant isolation can never be bypassed via a token from another tenant?

The defining risk in multi-tenant SaaS is **cross-tenant access** — a valid token from tenant A being accepted to read tenant B's data. This is almost always an *authorization* bug riding on a correctly-*authenticated* request: the token is genuinely valid (right signature, right issuer, not expired), it just belongs to the wrong tenant, and the code forgot to check. The architecture must make the tenant a first-class, *always-enforced* dimension of every access decision, not an afterthought.

The foundation is putting tenant identity *in the verified token* and *checking it on every data access*. Every token carries a verified `tenant_id` (or `org_id`) claim, minted by the IdP based on the authenticated user's org membership — the client can never assert its own tenant. Then every resource access enforces `token.tenant_id == resource.tenant_id`, ideally not in scattered hand-written checks (which someone will forget) but structurally: a query layer that *automatically* scopes every query by tenant (row-level security in Postgres, or a mandatory tenant predicate injected by the data-access layer), so forgetting the check fails closed rather than open.

```
Token:   { sub, tenant_id: T_alice, scope }   <- tenant_id is VERIFIED, not client-asserted
Access:  resource.tenant_id MUST == token.tenant_id   (enforced in data layer, not ad hoc)
Defense in depth:
  - DB row-level security keyed on tenant_id (fail-closed)
  - separate signing keys / realms per tenant tier for blast-radius segmentation
  - tenant-scoped audience so a token literally can't be replayed cross-tenant
```

Defense in depth strengthens this. Use **row-level security** at the database so even a code path that forgets the check can't return another tenant's rows — the safe behavior is the default. For high-isolation requirements, **segment trust domains**: per-tenant (or per-tier) realms/issuers, or even per-tenant signing keys, so a token from one tenant is *structurally* unable to validate against another's resources (audience/issuer mismatch), turning an authorization check into an authentication impossibility. Add tenant-scoped audit logging so cross-tenant *attempts* are visible. The most dangerous anti-patterns to call out: trusting a client-supplied tenant identifier (header or request body) instead of the token claim — that's a direct cross-tenant escalation; and relying solely on per-endpoint authorization checks, which are one forgotten line away from a breach. The expert principle: **make tenant isolation a property of the system's structure (RLS, scoped queries, segmented trust domains), not a discipline you hope every developer remembers** — because the failure mode of forgotten isolation is silent and catastrophic.

### 🔴 Expert — extended (continued)

#### Q56. [Theory] Explain CAEP / the Shared Signals Framework and how it closes the JWT revocation gap. What does adopting it change about your architecture?

The structural weakness of stateless JWTs is that they're valid until expiry — you can't un-issue one (Q24), so a logout, a fired employee, a hijacked session, or a device that just became non-compliant doesn't take effect until the token expires. The traditional patches (very short TTLs, denylists, introspection) each reintroduce the state or hot-path dependency JWTs were meant to avoid. **CAEP (Continuous Access Evaluation Profile)**, part of the OpenID **Shared Signals Framework (SSF)**, attacks the problem differently: instead of the resource server *polling* validity, the IdP and relying parties form a **publish/subscribe network of security events**. The IdP pushes signals — "session revoked," "credential changed," "device compliance changed," "assurance level changed," "token claims changed" — to subscribers in near-real-time, so a resource server can react *mid-session* rather than waiting for token expiry.

```
Classic JWT:   issue (TTL 1h) ----------- valid no matter what ----------> expiry
               logout/compromise at t+5min has NO effect until expiry

CAEP/SSF:      IdP --event: "session_revoked for sub=alice"--> [subscribers]
               resource servers drop/re-challenge alice's session NOW
               continuous evaluation: trust is re-assessed on signals, not just at issue
```

Adopting it changes the architecture from **"authenticate once, trust for the token lifetime"** to **"continuously evaluate trust against a live signal stream."** Concretely you add a transmitter (the IdP/event source), a transport (the SSF push/pull delivery), and receivers in your resource servers or gateways that act on events — typically by invalidating a cached session, forcing re-authentication, or downgrading what the session may do. This lets you keep the *performance* benefits of stateless or cached verification (no per-request introspection) while regaining *near-real-time revocation* — you only pay the cost when a real event fires, not on every request. It also enables richer reactions than binary revoke/allow: a "device became non-compliant" signal can trigger step-up rather than a hard logout.

The expert framing connects to the Q28 throughline: the industry is moving from *standing* trust to *continuous* verification. CAEP/SSF is the standardized mechanism that finally lets the convenient stateless-token model coexist with enterprise revocation requirements, without bolting on a per-request lookup. The honest caveat: it adds an event-delivery system you must operate and secure (the signal channel itself is security-critical and must be authenticated), and adoption is still maturing — so in 2026 you'd describe it as the strategic direction for high-assurance, long-session systems rather than a universal default.

#### Q57. [Practical] An external security audit flags that your microservices accept any JWT signed by your issuer regardless of audience. Walk through assessing blast radius, remediating safely, and preventing recurrence across many teams.

Missing audience validation means **any service that can legitimately obtain a token for *any* purpose can call *any* other service** — the lateral-movement / confused-deputy failure (Q26, Q45). The first job is sober blast-radius assessment, not panic. Enumerate which clients can obtain tokens from the issuer and what each *should* be able to reach; then mine access logs for *actual* cross-audience usage — did service A ever successfully call service B with a token minted for A? If logs show no such misuse, you likely have an exposure, not an active breach, which shapes your disclosure obligations. Decode a sample of live tokens to confirm they even *carry* an `aud` claim to validate against (if they don't, the issuer side needs fixing too).

```
Remediation in safe stages (per resource server):
1. Add aud validation in LOG-ONLY mode: log "would-reject: aud=X expected=Y", reject nothing.
2. Watch logs for legitimate callers that would break -> fix their token requests
   (correct resource indicator / scope) so they get the right audience.
3. Flip to ENFORCE per service once its log-only window is clean.
4. Add a CONTRACT TEST: a token for another audience is rejected (locks the fix).
```

Remediating safely is the same disciplined pattern as the Q26 story, scaled to many teams. You never flip enforcement blindly across the fleet — a misconfigured-but-currently-working caller would break instantly. Instead, deploy audience validation in **log-only mode** first on each resource server, observe which real callers *would* be rejected, fix those callers' token acquisition (often they were getting an over-broad token and should request a resource-specific audience via RFC 8707 / token exchange), and only then enforce. Drive it per service and per caller-edge, gated on a clean log-only window.

Preventing recurrence across many teams is the part that separates senior from staff-level answers: the fix must be **systemic, not per-service**. Ship a hardened, shared `JwtDecoder`/resource-server starter (the Q10 component) that validates `iss`, `aud`, `exp`, and pins the algorithm *by default*, and make adopting it the paved road so a team can't accidentally build a permissive verifier. Add a platform-level **contract/conformance test** (or a CI policy check) asserting every service rejects wrong-audience tokens. Consider a gateway-level backstop that also checks audience, so even a regressing service has a second line of defense. And capture the lesson in an architecture guardrail / lint rule. The meta-point auditors and interviewers both want: the durable remediation isn't "we fixed the 30 services," it's "**we made it structurally hard to ship a service that gets this wrong again**" — the vulnerability class is closed at the platform level, not whack-a-moled per team.

#### Q58. [Theory] Argue both sides: should authentication and authorization be centralized in one platform, or distributed/owned by each service? What's the senior position?

This is a genuine architectural tension with no universally correct answer, and the interview value is in articulating *both* forces honestly before landing a nuanced position. **Centralization** (one IdP for authN, one policy engine like OPA/Cedar for authZ) argues: a single place to enforce consistent crypto, rotate trust, audit decisions, and upgrade standards; uniform policy that's externalized from code and reviewable; no per-team reinvention of security-critical logic (which is where vulnerabilities breed — Q38, Q57). The classic failures of decentralization — one team accepting `alg: none`, another forgetting `aud` — vanish when there's one paved road. **Distribution** (each service owns its authZ logic) argues: services know their own domain rules best, a central policy service becomes a *hot-path dependency* and a single point of failure/latency, central policy can't capture every fine-grained, data-aware decision without becoming a sprawling mess, and team autonomy/velocity suffers when every authorization change needs a central team.

```
Centralized authZ          Distributed authZ
+ consistency, audit       + domain fidelity, autonomy
+ no reinvented crypto      + no central hot-path SPOF
- SPOF / hot-path latency   - inconsistency, reinvented bugs
- coarse for data rules     - hard to audit fleet-wide
```

The senior position is **not** "pick one" but **split by altitude**: centralize *authentication* and *coarse, cross-cutting authorization*; distribute *fine-grained, data-aware authorization* to the services that own the data — under a centrally-governed framework. AuthN should be centralized almost unconditionally: there's no good reason for each service to verify identity differently, and a shared issuer + shared hardened verifier (Q57) eliminates a whole vulnerability class. Coarse authZ — "does this token have scope X, is this tenant allowed this feature tier" — belongs at the edge/gateway or a shared library. But the decision "may *this* user edit *this specific* order, given its state and ownership" is intrinsically local to the order service's domain model; forcing it into a central engine either leaks domain data to the engine or produces an unmaintainable rule sprawl.

The reconciling pattern that resolves most of the tension: **centralized policy *authoring and governance*, distributed *enforcement*.** Tools like OPA (policy as code, distributed as sidecars/libraries evaluated locally) and Cedar let you write and review policy centrally while *evaluating* it at each service — no central hot-path call, yet consistent and auditable. That gives you centralization's consistency and audit without its SPOF and latency, and distribution's domain fidelity and autonomy without its inconsistency and reinvention. The expert framing: the real question isn't "central vs. distributed" but "**which decisions need global consistency (authN, crypto, coarse policy) versus local domain knowledge (fine-grained data authZ), and how do you govern the latter centrally while enforcing it locally.**" Answering that — rather than dogmatically picking a side — is the staff-level signal.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q59. [Theory] What are the `HttpOnly`, `Secure`, and `SameSite` cookie attributes, and what attack does each one mitigate?

A session or auth cookie is only as safe as the flags you set on it, and each flag targets a distinct attack class. **`HttpOnly`** removes the cookie from JavaScript's reach (`document.cookie` cannot read it), which means an XSS payload that runs on your page still cannot exfiltrate the session — it converts a script injection into something far less catastrophic (Q50). **`Secure`** tells the browser to send the cookie *only over HTTPS*, preventing a passive network attacker from sniffing it off a plaintext request (e.g. a stray `http://` link or a downgrade). **`SameSite`** controls whether the cookie is attached to *cross-site* requests, which is the core defense against **CSRF**: with `SameSite=Lax` (the modern browser default) the cookie is omitted from cross-site POST/`fetch`, so a malicious site can't ride the user's session to perform state-changing actions.

The "why" behind combining them is that they defend *different layers*: `Secure` is about the wire, `HttpOnly` is about script access, and `SameSite` is about cross-origin request inclusion. None substitutes for another — a `Secure` cookie still leaks to XSS without `HttpOnly`, and an `HttpOnly` cookie is still CSRF-able without `SameSite`. The senior nuance: `SameSite=Strict` blocks the cookie even on top-level navigations from other sites (so a user clicking a link to your app arrives logged out), which is why `Lax` is the usual default and `Strict` is reserved for the most sensitive cookies. For cross-site contexts that genuinely need the cookie (embedded widgets, some OAuth flows), `SameSite=None` is required *and* must be paired with `Secure`.

```
Set-Cookie: SID=...; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=3600
            └─XSS can't read   └─HTTPS only   └─CSRF defense
```

The interview takeaway: reciting the flags is table stakes; the signal is explaining that they form a *defense-in-depth triad* against three orthogonal threats (network sniffing, XSS exfiltration, CSRF), and that the BFF pattern (Q24/Q50) leans on exactly this cookie hardening to keep real OAuth tokens off the browser entirely.

#### Q60. [Practical] A login endpoint is getting hammered by credential-stuffing. Walk through the layered defenses you'd deploy, and why a single one isn't enough.

Credential stuffing replays username/password pairs leaked from *other* breaches against your login, betting on password reuse. It looks like normal login traffic — real usernames, plausible passwords, distributed across many IPs via botnets — so no single signal cleanly separates attacker from user. That's why the answer is *layered* rate-limiting and friction that escalates with suspicion, never one silver bullet.

```
Defense layers (escalating friction)
1. Per-account throttle:   N failed attempts/account -> exponential backoff/lockout
2. Per-IP / per-ASN limit:  blunt, but botnets rotate IPs -> not sufficient alone
3. Device/behavioral signals: new device, impossible travel, headless-browser fingerprint
4. CAPTCHA / proof-of-work: triggered on risk, not every login (UX cost)
5. Breached-password check: reject known-leaked passwords (HaveIBeenPwned k-anonymity API)
6. MFA / passkeys:          the real fix -- a stuffed password alone can't log in
```

The reasoning behind the ordering: **per-account throttling with backoff** directly limits how fast any single account can be brute-forced, but attackers spreading one attempt across thousands of accounts evade it — hence **per-IP/ASN limits** as a complement, which botnets then evade by rotating IPs, hence **behavioral/device risk scoring** to catch the automation patterns IP limits miss. CAPTCHAs and proof-of-work add cost *to the attacker* but also to legitimate users, so you gate them on risk rather than applying them universally. Checking submitted passwords against breach corpora (via a k-anonymity range query so you never send the full hash) attacks the root cause — reused leaked passwords. But the durable fix is **MFA/passkeys**: with a phishing-resistant second factor, a correct-but-stuffed password simply isn't enough to authenticate.

What I'd actually do in production: account lockout with exponential backoff and a generic error message (never reveal whether the *username* exists — that's an enumeration leak), an adaptive risk engine feeding step-up MFA, breached-password rejection at registration and password-change, and passkey enrollment as the strategic endgame. The senior framing: credential stuffing is an *economics* problem — you can't make it impossible, you make it expensive enough that attackers move on, while keeping friction off legitimate users via risk-based escalation.

#### Q61. [Theory] Why should you hash passwords with bcrypt/scrypt/argon2 instead of SHA-256, and what is a "work factor"?

Fast cryptographic hashes like SHA-256 are *designed to be fast* — billions of hashes per second on a GPU — which is exactly wrong for password storage. If your database leaks, an attacker with `SHA-256(password)` values can run an offline brute-force/dictionary attack at enormous speed, cracking weak and medium-strength passwords in minutes. Password hashing functions (**bcrypt, scrypt, Argon2**) are deliberately *slow and tunable*: they apply a configurable amount of work per hash so that a single legitimate login (one hash) is cheap, but an offline attack (billions of hashes) is economically infeasible.

The **work factor** (bcrypt's `cost`, Argon2's time/memory/parallelism parameters) is the knob controlling that cost. bcrypt's cost is logarithmic — `cost=12` means `2^12` iterations; bump it as hardware improves and you double the attacker's work per increment. Argon2 and scrypt add **memory-hardness**: they require large amounts of RAM per hash, which specifically defeats GPUs and ASICs that have lots of compute but limited fast memory per core — closing the gap that made bcrypt (CPU-bound, low-memory) increasingly GPU-crackable. **Argon2id** is the modern OWASP-recommended default precisely because it's both compute- *and* memory-hard.

```
SHA-256(pw):        ~billions/sec on GPU  -> offline crack is trivial
bcrypt(cost=12):    ~tens/sec             -> per-hash cost defeats brute force
argon2id(m,t,p):    slow + memory-hard    -> defeats GPU/ASIC parallelism too
```

Two non-negotiable companions to the slow hash: a **per-password random salt** (defeats precomputed rainbow tables and ensures identical passwords hash differently — bcrypt/argon2 handle this internally) and ideally a **server-side pepper** (a secret added before hashing, stored separately from the DB, so a DB-only leak still can't be cracked). The interview point: password storage security comes from *making each guess expensive*, not from the hash being "stronger" cryptographically — and the work factor is the dial you raise over the years to keep pace with attacker hardware.

### 🟡 Intermediate — extended

#### Q62. [Theory] Explain the OAuth2 Device Authorization Grant (RFC 8628). What problem does it solve and where is it used?

The Device Authorization Grant exists for clients that **can't easily display a browser or accept text input** — smart TVs, streaming sticks, CLIs, IoT devices, game consoles. The standard authorization-code flow assumes the device can render a login page and handle a redirect; a TV with a remote control or a headless CLI can't do that well. The device flow decouples the *authorizing* surface (the device) from the *authentication* surface (the user's phone or laptop browser).

The flow: the device asks the auth server for a `device_code` and a short, human-friendly `user_code`, and displays "go to example.com/activate and enter WDJB-MJHT." The user opens that URL on a *different* device with a real browser, authenticates (with MFA/passkeys, fully), and approves. Meanwhile the device **polls** the token endpoint with its `device_code`, receiving `authorization_pending` until the user finishes, then getting real tokens.

```
Device (TV/CLI)                 Auth Server              User's phone browser
  | POST /device_authorization      |                         |
  |<-- device_code, user_code,       |                         |
  |    verification_uri, interval ---|                         |
  | show: "go to .../activate,       |                         |
  |        enter WDJB-MJHT"          |                         |
  |                                  |<-- user visits + logs in + approves
  | POST /token (poll, device_code)->| authorization_pending   |
  | POST /token (poll) ------------->| --> access + refresh token
```

Why it's the right tool: the device never handles the password — authentication happens entirely on a capable, trusted device, so even a public, input-poor client gets full-strength auth including MFA. The trade-offs and pitfalls: you must **respect the polling `interval`** and back off on `slow_down` (hammering the token endpoint is a common bug), `user_code`s must be short but have enough entropy and a tight expiry to resist guessing, and there's a **phishing risk** — an attacker can display a `user_code` and trick a victim into approving the *attacker's* device, so the consent screen must clearly show what's being authorized. It's the canonical answer to "how does `aws sso login` or logging into Netflix on a TV work."

#### Q63. [Coding] Implement HMAC request signing (AWS SigV4-style) for an API client and the server-side verifier. Why sign the request instead of sending a bearer key?

**Problem:** Instead of sending a raw API key as a bearer credential (replayable if leaked), the client *signs* each request with a shared secret, proving possession of the secret *without transmitting it*, and binds the signature to the request contents + a timestamp so it can't be replayed or tampered with.

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

public final class HmacRequestSigner {

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    // Canonical string: method, path, timestamp, and a hash of the body.
    // Both sides MUST build this identically or signatures won't match.
    public static String canonical(String method, String path,
                                   String isoTimestamp, String bodyHashHex) {
        return method + "\n" + path + "\n" + isoTimestamp + "\n" + bodyHashHex;
    }

    public static String sign(byte[] secret, String method, String path,
                              String bodyHashHex, Instant now) throws Exception {
        String ts = now.toString();                      // RFC 3339, sent as a header
        String c  = canonical(method, path, ts, bodyHashHex);
        return HexFormat.of().formatHex(hmac(secret, c));
    }

    // Server side: recompute and compare in constant time, enforce a freshness window.
    public static boolean verify(byte[] secret, String method, String path,
                                 String bodyHashHex, String tsHeader,
                                 String sigHeader, Instant now) throws Exception {
        Instant ts = Instant.parse(tsHeader);
        if (Math.abs(java.time.Duration.between(ts, now).toSeconds()) > 300) {
            return false;                                // reject stale/future (replay window)
        }
        byte[] expected = hmac(secret, canonical(method, path, tsHeader, bodyHashHex));
        byte[] presented = HexFormat.of().parseHex(sigHeader);
        return java.security.MessageDigest.isEqual(expected, presented); // constant-time
    }
}
```

**Why sign instead of send:** a bearer key in a header is replayable forever if it leaks from a log or proxy (Q49). An HMAC signature proves the client *holds* the secret without ever putting the secret on the wire, and because the signature covers the method, path, body hash, and a timestamp, an attacker who captures one request **cannot replay it** (timestamp expires) or **tamper with it** (any change invalidates the signature). This is the model AWS SigV4, Stripe webhooks, and many partner APIs use. **Time:** O(body size) for the body hash, O(1) HMAC. **Edge cases:** clock skew (hence the 5-minute window — keep it tight), canonicalization mismatches (the #1 SigV4 bug: header casing, trailing slashes, query-param ordering must be normalized identically on both sides), and you still need a `nonce` cache if you require *exactly-once* within the freshness window. **Trade-off vs. bearer:** much stronger against theft/replay, but more client complexity and shared-secret distribution — for the strongest guarantee use asymmetric signing (`private_key_jwt`) so the server never holds the signing key.

#### Q64. [Coding] Implement token introspection (RFC 7662) on a Spring resource server with short-lived caching. When do you choose introspection over local JWT validation?

**Problem:** Some tokens are *opaque* (not JWTs) or you need the *issuer's real-time opinion* on validity (instant revocation), so the resource server calls the auth server's `/introspect` endpoint to resolve the token — but a network call per request is too slow, so you cache the result briefly.

```java
@Bean
SecurityFilterChain api(HttpSecurity http,
                        OpaqueTokenIntrospector introspector) throws Exception {
    http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.opaqueToken(t -> t.introspector(introspector)));
    return http.build();
}

// Wrap Spring's introspector with a short TTL cache so we don't hit the AS per request.
@Bean
OpaqueTokenIntrospector cachingIntrospector(
        @Value("${introspection.uri}") String uri,
        @Value("${introspection.client-id}") String id,
        @Value("${introspection.client-secret}") String secret) {

    SpringOpaqueTokenIntrospector delegate =
        new SpringOpaqueTokenIntrospector(uri, id, secret);

    // Cache by the token value; short TTL trades a little revocation latency for throughput.
    Cache<String, OAuth2AuthenticatedPrincipal> cache = Caffeine.newBuilder()
            .expireAfterWrite(java.time.Duration.ofSeconds(30))
            .maximumSize(50_000)
            .build();

    return token -> {
        OAuth2AuthenticatedPrincipal hit = cache.getIfPresent(token);
        if (hit != null) return hit;
        OAuth2AuthenticatedPrincipal p = delegate.introspect(token); // throws if inactive
        cache.put(token, p);
        return p;
    };
}
```

**When introspection over local JWT validation:** choose introspection when (a) tokens are opaque reference tokens (the issuer keeps claims server-side — better privacy, smaller tokens, nothing leaks if the token is read from a log), or (b) you need **near-real-time revocation** and can't tolerate a stateless JWT remaining valid until expiry. Choose **local JWT validation** (Q10) when you need maximum throughput and issuer-independence — verification is offline, no auth-server round-trip, and an issuer outage doesn't take your API down.

**The caching trade-off is the crux:** introspection's naive cost is one AS call *per request*, which is a latency and availability dependency (Q47). A 30-second cache cuts that to roughly one call per token per 30s, trading a bounded *revocation-latency* (a revoked token may be honored for up to the cache TTL) for huge throughput gains. **Edge cases:** cache the *token value* not the subject (different tokens for the same user have different validity); never cache an *inactive* result long (or you'd keep rejecting a token that was reinstated — usually you don't cache failures); size-bound the cache to avoid memory blowup; and on AS unreachability decide your posture (fail-closed for high-security, or serve cached-valid through a transient blip). This is exactly the "buy back revocation without paying full per-request lookup" pattern from Q47.

#### Q65. [Theory] What is Kerberos / SPNEGO, and how does Windows Integrated Authentication actually achieve single sign-on inside a corporate network?

Kerberos is the ticket-based authentication protocol underpinning Active Directory SSO on corporate networks. Its core insight is to avoid sending the password (or even a password-derived secret) to each service; instead a central trusted third party — the **Key Distribution Center (KDC)**, part of the domain controller — vouches for identity via time-limited, encrypted **tickets**. The user authenticates *once* at login to get a **Ticket-Granting Ticket (TGT)**, then exchanges that TGT for per-service **service tickets** without re-entering credentials — that's the SSO.

```
1. Login: client proves identity to KDC (AS-REQ) -> gets TGT (encrypted with KDC key)
2. Access svc: client presents TGT to KDC (TGS-REQ) -> gets a SERVICE TICKET for svc
3. client presents service ticket to svc; svc decrypts with its own key -> trusts identity
   (no password ever sent to svc; tickets are time-stamped to limit replay)
```

**SPNEGO** (Simple and Protected GSSAPI Negotiation Mechanism) is the HTTP wrapper that lets browsers do this transparently: when a server responds `WWW-Authenticate: Negotiate`, a domain-joined browser silently obtains a Kerberos service ticket for that server's **SPN (Service Principal Name)** and sends it in the `Authorization: Negotiate <token>` header — the user sees no prompt at all. That seamless, prompt-free experience inside the corporate LAN is "Windows Integrated Authentication." The whole thing leans on three pillars: a shared trusted KDC, **synchronized clocks** (tickets are timestamped, so Kerberos is notoriously sensitive to clock skew — a classic failure mode), and correct **SPN registration** (the #1 real-world breakage: `Server not found in Kerberos database` / `SPN not found`).

In 2026 this is enterprise-legacy you *integrate with*, not greenfield: it requires being on the corporate network/domain (it doesn't work for arbitrary internet clients), and the modern path is to front Kerberos with a federation broker that converts it to OIDC for cloud apps (Q23). The interview-relevant points: it's mutual and passwordless-per-service by design, it predates and conceptually resembles token-based SSO (the TGT is "a token you exchange for scoped tokens"), and its operational pain is clocks + SPNs + needing line-of-sight to a domain controller. NTLM is the older challenge-response fallback you should disable where possible because it lacks Kerberos's mutual auth and is vulnerable to relay attacks.

#### Q66. [Practical] Design a passwordless "magic link" email login. What are the security pitfalls and how do you avoid them?

A magic-link flow emails the user a one-time URL containing a high-entropy token; clicking it authenticates them and creates a session. It's popular for low-friction consumer apps because it removes the password entirely (no password to phish, reuse, or breach). But "no password" doesn't mean "no security design" — magic links concentrate the entire authentication strength into one emailed token, so the token's generation, transport, and consumption must all be hardened.

```
1. User enters email -> server generates token = CSPRNG(32 bytes), stores HASH(token)
   with {user, expiry ~10min, single_use=true}
2. Email link: https://app.example.com/auth/verify?token=<token>
3. Click -> server hashes presented token, looks up, checks not-expired & not-used,
   marks used, creates session. Constant-time compare; generic errors.
```

The pitfalls, each with its fix: **(1) Long-lived or reusable tokens** — a link sitting in an inbox forever is a standing credential; make tokens short-lived (5–15 min) and strictly single-use, invalidated on first click. **(2) Storing the raw token** — store only a hash, so an email-system or DB leak doesn't hand over working links (treat it like a password). **(3) Token in the URL** — URLs leak via `Referer`, browser history, and email-scanner prefetch; mitigate by making it single-use (a prefetch consuming it just forces a resend) and avoiding third-party scripts on the verify page. **(4) Email-scanner prefetch** consuming the link before the user clicks — a real operational headache; common mitigations are requiring a same-device confirmation click on a landing page rather than auto-authenticating on GET, or detecting bot prefetch. **(5) User/host enumeration** — always say "if that email exists, we sent a link," never confirm account existence. **(6) Open-redirect / link injection** — never reflect a `next=` parameter into the redirect without allow-listing. **(7) Login CSRF / link forwarding** — bind the token to the initiating device/session where possible so a forwarded link can't silently log someone else in.

What I'd actually do: short-lived single-use hashed tokens, rate-limit issuance per email and per IP, generic responses, an allow-listed post-login redirect, and treat email as a *possession* factor — meaning magic links alone are roughly equivalent to "email account = identity," so for anything sensitive I'd layer a second factor and never use magic links as the *recovery* path for an account whose recovery email is the same inbox (circular trust). The honest framing: magic links trade password risk for *email-account* risk and *link-handling* risk — fine for low-stakes consumer login, insufficient as sole auth for high-value accounts.

### 🟠 Advanced — extended

#### Q67. [Coding] Implement DPoP (RFC 9449) proof generation on the client and validation on the resource server. How does it differ from mTLS-bound tokens?

**Problem:** Turn a bearer token into a proof-of-possession token *without* requiring transport-layer mTLS (so it works for SPAs and mobile). The client holds a key pair, sends its public key (JWK) in a per-request signed JWT (the "DPoP proof"), and the token is bound to that key's thumbprint via a `cnf` claim — so a stolen token is useless without the private key.

```java
// CLIENT: build a DPoP proof JWT for a specific method+URL, signed by the client's key.
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.*;

String buildDpopProof(ECKey clientKey, String httpMethod, String httpUri,
                      String accessTokenHashB64Url /* "ath", optional per spec */)
        throws JOSEException {
    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
        .type(new JOSEObjectType("dpop+jwt"))      // typ MUST be dpop+jwt
        .jwk(clientKey.toPublicJWK())              // embed PUBLIC key only
        .build();
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .claim("htm", httpMethod)                  // bound to HTTP method
        .claim("htu", httpUri)                     // bound to target URL
        .jwtID(UUID.randomUUID().toString())       // jti -> server replay cache
        .issueTime(Date.from(Instant.now()))
        .claim("ath", accessTokenHashB64Url)       // binds proof to the access token
        .build();
    SignedJWT jwt = new SignedJWT(header, claims);
    jwt.sign(new ECDSASigner(clientKey));          // private key never leaves client
    return jwt.serialize();
}
```

```java
// RESOURCE SERVER: validate the DPoP proof against the request and the token's cnf.
boolean validateDpop(String dpopProof, String method, String uri,
                     String tokenCnfJktThumbprint, ReplayCache jtiCache) throws Exception {
    SignedJWT proof = SignedJWT.parse(dpopProof);
    if (!"dpop+jwt".equals(proof.getHeader().getType().getType())) return false;
    ECKey jwk = (ECKey) proof.getHeader().getJWK();
    if (!proof.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(jwk))) return false; // self-signed by client key

    var c = proof.getJWTClaimsSet();
    if (!method.equals(c.getStringClaim("htm"))) return false;       // method bound
    if (!uri.equals(c.getStringClaim("htu")))    return false;       // URL bound
    if (Math.abs(java.time.Duration.between(c.getIssueTime().toInstant(),
            Instant.now()).toSeconds()) > 60) return false;          // freshness window
    if (!jtiCache.firstSeen(c.getJWTID())) return false;             // replay protection

    // The KEY BINDING: the proof key's thumbprint must equal the access token's cnf.jkt.
    String proofJkt = jwk.computeThumbprint().toString();
    return proofJkt.equals(tokenCnfJktThumbprint);
}
```

**How DPoP differs from mTLS-bound (RFC 8705):** both make a stolen token inert without the private key, but they bind at *different layers*. mTLS-bound binds the token to the client's **TLS certificate** (transport layer) — strong, but requires a PKI and a full mTLS connection, awkward for browsers and mobile. DPoP binds at the **application layer** via a per-request signed JWT, needing only client-side key handling and *no* PKI or mTLS — which is why it's the practical PoP choice for SPAs, mobile apps, and public clients (Q19). **Edge cases / pitfalls:** the `jti` replay cache is mandatory (a captured proof is otherwise replayable within the freshness window), the `htm`/`htu` must match exactly (canonicalize the URL), clocks must be reasonably synced, and you must reject proofs whose embedded JWK doesn't match the token's `cnf.jkt`. **Trade-off:** DPoP needs no transport PKI but does need careful client key storage and a server-side replay cache; mTLS-bound offloads binding to the TLS stack but needs cert infrastructure.

#### Q68. [Theory] Explain the `jku`, `x5u`, `jwk`, and `kid` JWT header parameters and the attack classes that arise from blindly trusting them. How do you validate keys safely?

A JWT's *header* can carry hints about which key signed it: **`kid`** (key id, names which key from a known set), **`jwk`** (an *embedded* public key), **`jku`** (a *URL* to fetch a JWK Set from), and **`x5u`** (a URL to an X.509 cert chain). The dangerous property is that all of these are **attacker-controllable** — they live in the unauthenticated part of the token the attacker constructs. If your verifier *trusts the header to tell it which key to use*, the attacker just tells you to use *their* key.

```
ATTACK: attacker forges header with jwk = THEIR public key, signs token with THEIR private key
        naive verifier: "header says use this jwk" -> verifies -> ACCEPTS forged token
ATTACK: attacker sets jku = https://attacker.com/keys.json -> verifier fetches attacker's key
ATTACK: kid = "../../dev/null" or kid = "key' OR '1'='1" -> path traversal / SQLi in key lookup
```

The concrete attack classes: **embedded-key trust** (trusting `jwk`/`x5u` *in* the token means the attacker supplies both the key and the signature — total forgery); **`jku`/`x5u` SSRF + key injection** (the verifier fetches a key from an attacker-controlled URL, both forging tokens *and* turning your verifier into an SSRF gadget against internal services); and **`kid` injection** (since `kid` is often used to look up a key in a file path or database, an unsanitized `kid` enables path traversal, SQL injection, or even command injection). These sit alongside the classic **`alg:none`** (verifier accepts an unsigned token) and **HS/RS algorithm confusion** (attacker switches an RS256 verifier to HS256 and signs with the *public* key as the HMAC secret).

Safe validation rules: **never trust the token to choose its own trust anchor.** Pin the acceptable algorithm(s) server-side and reject everything else (defeats `alg:none` and HS/RS confusion). Resolve keys *only* from a **pre-configured, allow-listed source** — your issuer's known JWKS URL fetched out-of-band — and use `kid` merely to *select* among those already-trusted keys, never to fetch or construct a key. Ignore or reject `jwk`/`jku`/`x5u` unless you have an explicit, allow-listed reason to honor them (and if you must, allow-list the exact host). Sanitize `kid` as untrusted input if it indexes any lookup. The unifying expert point (echoing Q45): JWT security failures are overwhelmingly *trust-establishment* bugs, not crypto-math bugs — the signature can be cryptographically perfect while being made by a key the attacker chose. The trust anchor must come from your configuration, never from the token.

#### Q69. [Practical] How do you implement OIDC single logout across multiple relying parties? Compare RP-initiated, front-channel, and back-channel logout and their failure modes.

Single logout (SLO) is deceptively hard because SSO created the inverse problem: a user logged into the IdP once and got sessions at *many* relying parties (RPs), so "log out" must now terminate sessions across all of them — but those sessions are independent and the IdP doesn't directly control RP cookies. OIDC defines three mechanisms, each trading reliability against complexity.

```
RP-initiated logout:  user clicks logout at RP -> RP redirects to IdP end_session_endpoint
                      -> IdP clears its session -> redirects back (post_logout_redirect_uri)
                      kills IdP session + the initiating RP; OTHER RPs unaffected unless...

Front-channel logout: IdP renders hidden <iframe> per RP pointing at each RP's logout URI
                      -> browser GETs them -> each RP clears its cookie
                      FRAGILE: blocked by 3rd-party-cookie restrictions, iframe failures silent

Back-channel logout:  IdP sends a signed Logout Token (JWT) directly server-to-server to
                      each RP's backchannel_logout_uri -> RP invalidates the session server-side
                      RELIABLE: no browser/cookie dependency, but RP must look up & kill sessions
```

**RP-initiated** (`end_session_endpoint`) is the baseline: the user explicitly logs out at one RP, which sends them to the IdP's logout endpoint to clear the *IdP* session and optionally redirect back. It reliably ends the IdP session and the initiating RP, but doesn't by itself propagate to the *other* RPs. **Front-channel logout** propagates via the browser — the IdP loads each RP's logout URL in hidden iframes so every RP clears its own cookie. Its failure modes are serious in 2026: third-party-cookie blocking (Safari ITP, Chrome's restrictions) means the iframed RP request often *won't carry the RP's session cookie*, so the logout silently no-ops; iframe load failures are invisible; and it depends on the browser staying on the page. **Back-channel logout** is the robust answer: the IdP sends each RP a signed **Logout Token** (a JWT with `sub`/`sid` and a `events` claim) directly server-to-server, and the RP invalidates the corresponding session in its store — no browser, no cookies, no iframes.

The catch with back-channel — and the senior insight — is that it **only works if RPs hold server-side session state** keyed by `sub`/`sid` that they can actually find and kill. A purely stateless-JWT RP has nothing to invalidate (Q24's revocation gap again): receiving a logout token is useless if the user's access token remains valid until expiry. So robust SLO pushes you toward server-side sessions (or short TTLs + a denylist, or CAEP/SSF signals from Q56 as the modern superset of back-channel logout). What I'd actually do: implement RP-initiated for the explicit-logout UX, back-channel for reliable propagation, store RP sessions server-side keyed by `sid` so logout tokens have something to act on, keep front-channel only as a best-effort supplement, and for high-assurance estates adopt Shared Signals (CAEP) which generalizes "session revoked" beyond just logout. The throughline: SLO reliability is bounded by whether each RP has *revocable session state* — logout is only as good as your ability to invalidate, which is the same constraint that haunts stateless JWTs everywhere.

#### Q70. [Theory] Compare delegation vs impersonation in token exchange (RFC 8693). When must you use each, and how do the `act` and `sub` claims encode the difference?

Token exchange (Q17) supports two semantically distinct ways for service A to act with respect to user U downstream, and conflating them is a real authorization and audit failure. In **impersonation**, the resulting token looks as if it *belongs to the user* — service A effectively *becomes* U for the call, and downstream services see only U with no record that A was involved. In **delegation**, the token explicitly records that A is acting *on behalf of* U — both parties are present in the token, preserving the chain "A, acting for U."

The claims encode exactly this distinction. In **impersonation**, the new token's `sub` is the *user* and there is no `act` claim — the composite actor identity is erased. In **delegation**, `sub` remains the user but an **`act` (actor) claim** names the delegate, and `act` can *nest* to represent a multi-hop chain (`act: { sub: serviceA, act: { sub: gateway } }`), giving a complete, auditable "who actually performed this" trail.

```
Impersonation:  token { sub: "user:U" }                         <- A vanishes; looks like U
Delegation:     token { sub: "user:U", act: { sub: "svc:A" } }  <- "A acting for U"
Nested chain:   token { sub: "user:U",
                        act: { sub: "svc:order", act: { sub: "gateway" } } }
```

When to use each: **delegation is the default and the safer choice** because it preserves accountability — for audit, forensics, and least privilege you almost always want downstream to know a service is acting for a user, not that the user "did" it directly. Reserve **impersonation** for the narrow cases where the downstream system genuinely must see only the user (e.g. legacy systems that can't reason about an actor chain, or admin "log in as user" support tooling) — and even then it's higher-risk because it destroys the audit trail and makes a compromised middle service indistinguishable from the user. The senior framing: impersonation answers "the user did this," delegation answers "this service did this *for* the user" — and in a zero-trust, auditable system you want the latter almost everywhere, with impersonation as a deliberate, logged exception. RFC 8693 makes the choice explicit via the request (`actor_token` + the requested semantics), so it's a *decision*, not an accident — treat it as one.

#### Q71. [Practical] You're moving service-to-cloud auth from long-lived static cloud access keys to workload identity federation. Explain the problem with static keys and design the federated approach.

Long-lived cloud access keys (an AWS access-key/secret, a GCP service-account JSON key, an Azure client secret) are the cloud-era equivalent of the shared static API key anti-pattern (Q7): they're standing, often broadly-privileged credentials that frequently end up committed to git, baked into CI config, copied into developer laptops, and never rotated. A single leak grants persistent access until someone notices and rotates — and the highest-profile cloud breaches repeatedly trace back to a leaked static key. The core problem is that the credential is **long-lived, exfiltratable, and disconnected from any verifiable runtime identity**.

**Workload identity federation** removes the static key entirely by establishing *trust between identity systems* so a workload proves *what it is* (via a token its platform already issues) and exchanges that for short-lived cloud credentials on demand. A Kubernetes pod, a GitHub Actions job, or a workload in another cloud already possesses a signed OIDC token attesting its identity; the cloud provider is configured to trust that token's issuer and, on presentation, mints short-lived credentials scoped to a specific role.

```
Static key (bad):   secret AKIA... stored in CI/repo -> persistent, leakable, rarely rotated

Federation (good):
  GitHub Actions job  --OIDC token (signed by GitHub, claims: repo, ref, environment)-->
  AWS STS AssumeRoleWithWebIdentity  (trust policy: issuer=token.actions.github.com,
                                      sub matches repo:org/repo:ref:main)
  <-- short-lived AWS credentials (minutes), scoped to one role, NO static secret stored
```

The design specifics that make it secure: configure the cloud trust policy to accept *only* the expected issuer **and** constrain on the token's claims (the GitHub `sub` must match a specific repo/branch/environment — not just "any GitHub token," which would let any repo assume your role, a classic misconfiguration). The exchanged credentials are short-lived (minutes), so a leak self-heals, and there's **no secret to store or rotate** because the workload's platform identity *is* the credential. This is the cloud manifestation of the recurring themes: short-lived over static (Q53), bind credentials to a verifiable identity, and let trust *expire* rather than be *revoked*. The trade-offs: you depend on the platform's OIDC issuer and must get the trust-policy claim constraints exactly right (over-broad `sub` matching is the main footgun), but you eliminate the entire class of leaked-long-lived-key incidents. What I'd actually do: federation everywhere it's supported (CI, K8s via IRSA/Workload Identity, cross-cloud), reserve static keys only for the rare legacy integration that can't federate, and alert on any remaining static key's age and usage.

### 🔴 Expert — extended

#### Q72. [Theory] Walk through the full WebAuthn/passkey registration and authentication ceremonies. What exactly does the server verify, and why is each check load-bearing?

Q20 covered *why* passkeys are phishing-resistant; the expert depth is *what the relying party (RP) server actually verifies* in each ceremony, because skipping any check reopens a specific attack. There are two ceremonies: **registration** (`navigator.credentials.create`) which enrolls a new public-key credential, and **authentication** (`navigator.credentials.get`) which proves possession of an enrolled one. Both are challenge-response, and in both the *browser* — not the RP — binds the assertion to the origin, which is the root of phishing resistance.

```
REGISTRATION (create)
 server -> challenge (random, single-use), rp.id, user info
 authenticator: generate keypair (priv stays in TPM/secure enclave), sign attestation
 client -> {credentialId, publicKey, attestationObject, clientDataJSON}
 server verifies:
   - clientDataJSON.type == "webauthn.create"
   - clientDataJSON.challenge == the one we issued (single-use)   <- replay defense
   - clientDataJSON.origin in our allow-list                      <- PHISHING defense
   - authData.rpIdHash == SHA256(our rp.id)                       <- wrong-RP defense
   - user-present / user-verified flags as required               <- presence/biometric
   - (optional) attestation statement -> authenticator provenance
   - store: credentialId, publicKey, signCount

AUTHENTICATION (get)
 server -> challenge, allowCredentials
 authenticator: sign(authData || SHA256(clientDataJSON)) with private key
 client -> {credentialId, authenticatorData, clientDataJSON, signature}
 server verifies:
   - challenge matches & single-use, type == "webauthn.get"
   - origin in allow-list, rpIdHash matches
   - signature verifies against the STORED public key for that credentialId
   - signCount strictly increased (or 0)                          <- cloned-key detection
```

Why each check is load-bearing: the **challenge match** stops replay of a captured assertion; the **origin check** is *the* phishing defense — `examp1e.com` produces an origin the RP rejects, and crucially the *browser* refuses to even produce an assertion for a mismatched `rp.id`, so there's nothing to phish; the **rpIdHash** ensures a credential registered for one RP can't be used at another; the **signature against the stored public key** is the actual proof of possession; and the **signCount** monotonicity detects a *cloned* authenticator — if two copies of a credential exist, their counters diverge and the RP can flag the clone. **Attestation** (registration only) lets high-assurance RPs verify the authenticator's make/model/provenance (e.g. require a certified hardware key), though many consumer deployments skip it for privacy/UX. The expert nuances: synced passkeys (iCloud/Google) often report `signCount = 0` (no clone detection, an accepted trade-off for sync); `userVerification` "required" forces the biometric/PIN (true MFA in one gesture: possession of the device + inherence/knowledge), while "preferred" may yield only presence. The throughline: WebAuthn's security is a *protocol of server-side checks* — the cryptography is simple, but every one of those verifications closes a specific door, and a verifier that skips the origin or challenge check throws away the very properties that make passkeys worth adopting.

#### Q73. [Practical] Design authentication and authorization for a gRPC-based internal platform. How does it differ from REST/HTTP-header auth, and what do you use for per-call identity and streaming?

gRPC changes several assumptions that REST auth takes for granted, so porting "put a JWT in a header and validate per request" naively misses gRPC-specific concerns. gRPC runs over **HTTP/2 with multiplexed long-lived connections**, carries auth in **metadata** (not "headers" semantically, though they map to HTTP/2 headers), and supports **streaming** RPCs where a single call may live for minutes or hours — which breaks the "credential is fresh per short request" assumption. It also has a strong, idiomatic fit with **mTLS** because connections are persistent and typically service-to-service.

```
Identity layers for gRPC internal platform
 L1 transport: mTLS (mesh/SPIFFE SVID) -> workload identity per CONNECTION
 L2 per-call:  JWT in metadata "authorization: Bearer ..." validated by a server INTERCEPTOR
 L3 policy:    interceptor -> OPA/Cedar check (method-level: which caller may call which RPC)
 Streaming:    validate at stream OPEN; for long streams, re-check token expiry mid-stream
               or require periodic re-auth / react to CAEP revocation signals
```

The key differences and how to handle them: **(1) Per-call vs per-connection identity.** mTLS authenticates the *connection* (workload identity), but because HTTP/2 multiplexes many calls — potentially carrying *different end-user contexts* — over one connection, you still need a **per-call** application token in metadata for user/scope identity. Don't conflate "the connection is from service A" with "this call is authorized for user U." **(2) Interceptors are the enforcement point.** Use a server-side `ServerInterceptor` (Java) / unary+stream interceptor to extract and validate the metadata token *before* the handler runs — the gRPC analog of a Spring Security filter — and reject with `UNAUTHENTICATED`/`PERMISSION_DENIED` status codes (not HTTP 401/403, which gRPC clients don't see). **(3) Streaming lifetime.** A token validated at stream-open may *expire mid-stream* on a long-lived server-streaming or bidi RPC; for high-assurance flows, either cap stream duration to under the token TTL, re-validate periodically, or wire in CAEP/Shared-Signals (Q56) so a revocation terminates the stream. **(4) Method-level authorization.** gRPC's method names (`/pkg.Service/Method`) are a natural authorization granularity — externalize "may caller X invoke method Y" to a policy engine rather than scattering checks in handlers.

What I'd actually do: mesh-provided mTLS for L1 workload identity, a per-call JWT in metadata validated by a shared interceptor for L2 user/scope context (token exchange to attenuate per hop, Q17/Q70), OPA at L3 for method-level authz, and explicit handling of streaming token expiry. The framing that matters: gRPC's persistent multiplexed connections make mTLS a natural transport-identity fit, but they *increase* (not decrease) the need for per-call tokens, because connection identity and call identity are genuinely different things — and streaming forces you to think about credential freshness *during* a call, not just at its start, which REST's short request/response model lets you ignore.

#### Q73b. [Theory] You're asked to choose between a stateless JWT and an opaque-token-plus-introspection design for a brand-new partner-facing API platform. Reason through the decision against revocation, latency, privacy, and operational coupling.

(Renumbered below — see Q74.)

#### Q74. [Theory] For a brand-new partner-facing API platform, reason through choosing a stateless JWT model vs an opaque-token + cached-introspection model. What decides it?

Partner-facing platforms sharpen the trade-offs from Q47 because *partners hold the tokens* — you don't control the clients, you must reason about leakage and revocation conservatively, and you owe partners predictable latency. The decision turns on four axes — revocation urgency, latency budget, claim privacy, and operational coupling — and the honest answer is that one model rarely wins on all four, so you decide by which axis dominates *your* risk.

```
Axis                  Stateless JWT                 Opaque + cached introspection
revocation urgency    weak (valid to expiry)        strong (AS is source of truth)
hot-path latency      best (local verify)           good IF cached; AS call on miss
issuer-outage impact  none (verify offline)         degraded (AS on path) unless cached
claim privacy         claims readable by holder     opaque -> nothing leaks from token
token-in-log danger   claims exposed + replayable   reference only -> far less to leak
ops coupling          low (publish JWKS)            higher (run + scale /introspect)
```

For a **partner** platform specifically, two axes usually dominate. First, **revocation urgency is high**: if a partner is offboarded, their integration is compromised, or you must cut access *now*, a stateless JWT that stays valid until expiry is a liability — you'd be forced into short TTLs (more refresh traffic for partners) plus a denylist (reintroducing state). Opaque + introspection lets you revoke a partner instantly at the source of truth. Second, **claim privacy and token-leakage blast radius**: partner tokens end up in *their* logs, *their* proxies, *their* code — out of your control — and an opaque reference token leaks nothing if exposed, whereas a JWT spills its claims and is replayable. These two push partner platforms toward **opaque tokens with cached introspection**, accepting the bounded revocation-latency of the cache TTL (Q64) in exchange for instant revocation capability and privacy.

The countervailing case for **stateless JWT** is when *latency and partner-side simplicity* dominate and revocation is rarely urgent: JWTs verify offline so partners and your edge get the lowest latency and your platform's availability isn't coupled to an introspection service surviving partner traffic spikes. What decides it, concretely: if you can articulate a realistic "we must kill this partner's access within seconds" requirement or you carry sensitive claims, choose opaque + cached introspection. If the dominant requirements are raw throughput, issuer-independence, and you're comfortable with short-TTL + refresh as your revocation story, choose JWT. The staff-level move is refusing the false binary: many mature platforms issue **opaque reference tokens to partners** (control, revocation, privacy at the edge) while using **short-lived internal JWTs** behind the gateway (scale, issuer-independence in the backend) — matching each surface to the axis that dominates it, exactly the Q47 synthesis applied to an untrusted-client boundary.

#### Q75. [Behavioral] Tell me about a time you had to make a hard authentication trade-off under organizational or business pressure, where the "secure" answer conflicted with delivery. How did you handle it? (Senior/Staff)

Interviewers asking this want to see **engineering judgment, influence without authority, and ownership of risk** — not a story where security dogmatically "won" or was recklessly abandoned. The strongest answers show you held the line on what's non-negotiable, made a *principled, time-boxed* compromise on the rest, and made the risk explicit and owned. A representative STAR example:

**Situation:** A major customer's go-live was gated on SSO, and their IdP only spoke SAML; our platform spoke OIDC. Sales had committed a date two weeks out, and an engineer proposed hand-rolling SAML assertion parsing in the core service to hit it. **Task:** deliver SSO for the deal without taking on the catastrophic, hard-to-undo risk of bespoke XML signature validation in our most sensitive code path (the XSW/XXE attack surface from Q38). I owned the decision and had to align sales, the customer, and my team. **Action:** I separated *non-negotiable* from *negotiable*. Non-negotiable: we would not ship hand-rolled SAML signature validation — the failure mode is silent authentication bypass, not a bug we'd "fix later." Negotiable: *how* we delivered SAML on the timeline. I proposed routing SAML through an identity broker (the Q13 pattern) so our core never parses XML, scoped a two-day spike to prove the broker integration, and gave sales a concrete revised commitment: SSO via broker in ~10 days, lower risk, no core changes. I wrote a one-page risk note stating explicitly what we were *not* doing and why, and got the eng director and security to co-sign it so the trade-off was an organizational decision, not a silent engineering one. **Result:** we shipped two days *ahead* of the hand-rolled estimate because the broker did the hard part, the customer onboarded with zero auth incidents, and the broker pattern became the paved road for every subsequent SAML customer — turning a one-off pressure moment into reusable platform capability.

The meta-points that signal seniority: I distinguished *reversible* trade-offs (timeline, build-vs-buy) from *irreversible* ones (shipping an auth-bypass risk), and refused only the irreversible one. I **influenced through alternatives**, not refusal — I didn't say "no," I said "here's a faster *and* safer path." I made the risk **explicit and co-owned** rather than absorbing it silently or escalating it as an obstruction. And I made the fix **systemic**. The anti-pattern answer — "I pushed back and we slipped the date" (no alternative offered) or "we shipped it and added a ticket to fix later" (owned no risk) — is exactly what this question is designed to filter out. Staff-level authentication work is as much about *which risks are acceptable to defer and which never are* as it is about the protocols.

#### Q76. [Practical] Your auth system must survive a regional outage of the identity provider with graceful degradation rather than a total login outage. Design for IdP failure modes.

Treating the IdP as always-available is the blind spot that turns an IdP regional outage into a *total* outage of every product that depends on it — no logins, and depending on your token model, possibly no API calls either. The design goal is **graceful degradation**: existing authenticated sessions keep working, the failure radius is bounded, and you recover automatically. This requires separating the IdP's roles (issuing new tokens vs. verifying existing ones) because they have very different availability requirements.

```
IdP roles and their outage sensitivity
 ISSUANCE (login, token mint, refresh): needs IdP up. Outage here -> no NEW logins.
 VERIFICATION (validate existing tokens): should NOT need the IdP up.
   - JWT model: verify offline with cached JWKS -> survives IdP outage cleanly
   - introspection model: IdP on hot path -> outage breaks verification too (unless cached)
```

The architecture that degrades gracefully: **(1) Make verification IdP-independent.** Prefer locally-verifiable JWTs with **aggressively cached JWKS** and stale-while-error behavior (Q44) so existing tokens keep validating right through an IdP outage — an outage should affect *new* logins, not *current* sessions. If you use introspection, cache it (Q64) so a blip doesn't break the hot path. **(2) Multi-region / HA the IdP itself.** Run the IdP active-active across regions with health-checked failover; for SaaS IdPs, understand their SLA and multi-region story and design to it. **(3) Extend session/token lifetime under degradation** carefully — longer-lived sessions ride out short outages, but that's a direct revocation trade-off, so it's a tuned balance, not "make everything long-lived." **(4) Cache login decisions / refresh proactively** so clients holding valid tokens refresh *before* expiry and aren't forced to hit a downed token endpoint at the worst moment. **(5) Fail-safe posture by sensitivity:** for most reads, *fail-open to existing-valid-session* (don't log people out because the IdP blipped); for high-value mutations, you may choose *fail-closed* (deny if you can't re-verify), but make that an explicit per-endpoint decision, never a blanket one.

What I'd actually do: JWT verification with cached JWKS and stale-while-error so existing sessions are immune to IdP downtime, a multi-region/HA IdP (or a SaaS one with a credible multi-region SLA), proactive client-side token refresh with backoff, and an explicit degradation policy — existing sessions and reads keep working, new logins and the most sensitive actions degrade or queue. The senior framing: **decouple "can existing users keep working" from "can new users log in,"** because the former should *never* depend on the IdP being up. The classic failure is an introspection-on-every-request design (Q47) where an IdP outage instantly 5xxs the entire fleet despite every token being perfectly valid — the architecture, not the IdP's reliability, is what determined the blast radius.

#### Q77. [Theory] What is "downgrade" or "mechanism confusion" in multi-mechanism auth (e.g. supporting both Kerberos and NTLM, or both passkeys and passwords), and how do you prevent an attacker forcing the weaker path?

When a system supports multiple authentication mechanisms for usability or compatibility, it creates a **downgrade attack** surface: the system is only as strong as its *weakest enabled* mechanism, because an attacker (or a man-in-the-middle) can often *force negotiation down* to that weakest option. This is a recurring pattern across auth: SPNEGO negotiating Kerberos but falling back to NTLM (relay-vulnerable, Q65); a passwordless app that still allows a password fallback; TLS cipher/version negotiation; MFA flows that let the user "skip" to a weaker factor. The strength you *designed for* (passkey, Kerberos, TLS 1.3) is irrelevant if the *floor* (password, NTLM, TLS 1.0) is still reachable.

```
Strong path:  passkey / Kerberos / TLS1.3  <- what you intend
Weak floor:   password / NTLM / TLS1.0      <- what attacker forces
Downgrade:    MITM strips/refuses strong option -> client falls back -> weak path used
              system security == security of the FLOOR, not the ceiling
```

The defenses share a principle: **don't let the negotiation be silently driven by the untrusted party, and remove or hard-gate the weak floor.** Concretely: (1) **Disable weak mechanisms entirely** where possible — the only fully reliable defense (turn off NTLM, remove password fallback for users who've enrolled passkeys, drop old TLS versions). A fallback you've disabled can't be forced. (2) **Bind/authenticate the negotiation** so a downgrade is detectable — TLS 1.3's downgrade-protection sentinel in the server random, signed negotiation, or a post-handshake confirmation of the agreed mechanism. (3) **Make the floor conditional on risk and identity** — allow password fallback only for accounts *without* a stronger factor, require step-up and extra friction to use a weaker path, and log/alert when a strong-capable identity authenticates via a weak mechanism (a strong downgrade-attack signal). (4) **Per-account capability tracking** — once a user enrolls a passkey, treat password login for that user as suspicious, not normal.

The expert framing: usability and backward-compatibility *demand* multiple mechanisms, but every retained fallback is a potential downgrade target, so the security posture must be **min over enabled mechanisms, weighted by how easily each can be forced** — and the durable fix is *removing* the weak floor on a deprecation schedule, not just preferring the strong path. This is why "we added passkeys" provides limited security benefit if password login remains fully available for the same accounts (an attacker just phishes the password and ignores the passkey), and why mature rollouts pair passkey enrollment with *disabling* the password for that account. The same logic retired NTLM, SSLv3, and SMS-only MFA: you don't get the strong mechanism's guarantees until the weak one is *gone*, not merely *deprioritized*.

#### Q78. [Practical] An audit requires every authentication and authorization decision to be tamper-evidently logged and queryable for compliance. Design the audit pipeline and call out what's hard.

Compliance regimes (SOC 2, PCI-DSS, HIPAA, SOX) increasingly require that you can answer, months later and defensibly, "who accessed what, when, under what authentication assurance, and was it authorized?" — and prove the log itself wasn't altered. This is deceptively hard because the data is high-volume, privacy-sensitive, spread across many services, and must be *tamper-evident*, not just stored. The design separates capture, transport, integrity, and query.

```
Audit pipeline
 CAPTURE  every authN event (login, MFA, token issue/refresh, step-up) and authZ
          decision (allow/deny, policy id, resource, actor chain) as STRUCTURED events
          -> include: actor (sub + act-chain), action, resource, decision, acr/amr,
             request id, timestamp (trusted/synced), reason/policy-id
 TRANSPORT ship async (don't block the request path) via a durable buffer (Kafka)
 INTEGRITY append-only store + tamper-evidence: hash-chain each record (h_n = H(h_{n-1}||event))
            or a Merkle/transparency-log structure; periodically anchor a signed checkpoint
 QUERY    index for compliance queries (per-subject access history, denied attempts,
          step-up events) with access CONTROLLED and ITSELF audited
```

What's genuinely hard, and where teams get it wrong: **(1) Tamper-evidence, not just access control.** "Only admins can edit logs" is insufficient — an attacker (or insider) who *becomes* admin can rewrite history. You need cryptographic tamper-*evidence*: a hash-chain or Merkle transparency log where altering any record breaks the chain, with periodically published/anchored signed checkpoints so even the log operator can't silently rewrite the past. **(2) Completeness / no gaps.** An auditor cares as much about *missing* events as present ones; you must guarantee the path can't silently drop events (durable async buffer, at-least-once delivery, gap detection) — which is why audit logging shouldn't be a best-effort `log.info`. **(3) Capturing the right semantics.** Logging "200 OK" is useless for audit; you must capture the *decision* and its *basis* — the policy/rule that allowed it, the authentication assurance (`acr`/`amr` — was it MFA?), and the *full actor chain* (delegation, Q70) so "service A acting for user U" is reconstructable. **(4) Privacy vs. retention tension.** Audit logs accumulate PII and access patterns; you must reconcile long compliance retention with data-minimization/GDPR (pseudonymize where possible, control and *audit access to the audit log itself*). **(5) Not on the hot path.** Audit writes must be asynchronous and durable so they neither slow nor fail the actual auth decision, while still being guaranteed-eventually-persisted.

What I'd actually do: emit structured auth events with the decision basis and actor chain from a shared platform library (so every service logs consistently — the systemic fix, cf. Q57), ship via Kafka to an append-only store with hash-chained tamper evidence and periodic signed checkpoints, index for the specific compliance queries auditors run, and tightly control + audit access to the audit store itself. The senior insight: audit isn't "turn on logging" — it's a *security-critical subsystem* with its own integrity, completeness, and privacy requirements, and its hardest property is **tamper-evidence**, because a log an insider can quietly rewrite provides false assurance, which is worse than none.

#### Q79. [Theory] Reason about quantum-resistant / post-quantum considerations for authentication systems. What breaks, what doesn't, and what should you do now?

A large-scale quantum computer running Shor's algorithm would break the **asymmetric** cryptography underpinning most authentication: RSA and elliptic-curve signatures (RS256/ES256 JWT signatures, TLS/mTLS certificate signatures, SAML XML signatures) and key exchange all rely on integer factorization or discrete logarithms that Shor's solves efficiently. The honest framing for 2026: cryptographically-relevant quantum computers don't exist *yet*, but the threat is real for two specific reasons that demand action now, not later.

The two reasons are **"harvest now, decrypt later"** and **long-lived trust anchors**. First, an adversary can record encrypted/signed traffic today and decrypt it once quantum capability arrives — so anything that must stay confidential for many years is *already* at risk even though the quantum computer doesn't exist. For *authentication* specifically this matters less than for encryption (a signature verified today and discarded has little future value), but it matters enormously for **long-lived signing keys and root CAs**: a root CA or signing key with a 10–20 year lifetime issued today might still be in service when quantum attacks become feasible, at which point an attacker who factored its key could forge certificates/tokens at will. Symmetric primitives (HMAC, AES, bcrypt/Argon2) are **largely safe** — Grover's algorithm only halves their effective strength, addressed by doubling key sizes (HS512 instead of HS256, AES-256), so password hashing and HMAC request signing (Q63) are not the urgent concern.

```
At risk (Shor's):     RSA/ECDSA signatures, RSA/ECDH key exchange
                      -> TLS/mTLS certs, JWT RS256/ES256, SAML XML-DSig, root CAs
Largely safe (Grover):HMAC (HS*), AES, Argon2/bcrypt -> just increase sizes
Urgent now:           long-lived asymmetric keys & roots (harvest-now / decrypt-later)
```

What to do now — the realistic, non-alarmist program: **(1) Crypto-agility first.** The single most valuable investment isn't deploying PQC tomorrow, it's ensuring your systems can *change algorithms* without re-architecting — abstract the signing/verification layer, support algorithm negotiation, and make key/algorithm rotation a routine, tested operation (the Q25/Q53 rotation discipline generalized). Systems that hard-coded RS256 everywhere will suffer most. **(2) Shorten lifetimes** of asymmetric keys and certs (short-lived certs from Q53 already help — a cert that lives an hour has no harvest-later value). **(3) Inventory** your asymmetric crypto and especially long-lived roots/signing keys — you can't migrate what you haven't catalogued. **(4) Track and pilot PQC standards** — NIST's selected post-quantum signature algorithms (ML-DSA/Dilithium, SLH-DSA) and hybrid (classical+PQC) certificate/TLS modes are how the industry is rolling out; adopt hybrid first so you keep classical security while gaining PQC, rather than betting everything on a new algorithm. The expert framing: post-quantum auth is **not** a "rewrite everything now" emergency, it's a **crypto-agility and long-lived-key-hygiene** problem — the teams who'll migrate smoothly are those who already treat algorithms and keys as rotatable configuration, not baked-in constants, and who've already moved to short-lived certs and tokens that give attackers nothing worth harvesting.

#### Q80. [Practical] Two companies merge and you must unify their identity systems (different IdPs, overlapping user accounts, different MFA postures) with no big-bang cutover. Design the integration.

A merger is the hardest real-world identity problem because you have *two production IdPs*, *overlapping and conflicting accounts* (the same person may exist in both with different attributes, and worse, two *different* people may share an email or username across the orgs), *different MFA and assurance postures*, and an absolute requirement of **no big-bang cutover** — both companies must keep operating throughout. The strategy mirrors the federation-hub and phased-migration patterns (Q23/Q46): federate first, reconcile identities carefully, then converge, with every step reversible.

```
Phase 0  Federate, don't migrate (immediate interop, zero data move)
  - Stand up a broker / hub OR cross-federate the two IdPs (IdP-A trusts IdP-B and vice versa)
  - Users keep logging into THEIR home IdP; cross-org apps accept tokens from either
    via the broker normalizing to one internal format (OIDC/JWT)

Phase 1  Identity reconciliation (the genuinely hard part)
  - Match accounts across orgs: deterministic (verified corporate email/employee id)
    THEN probabilistic for the rest, with HUMAN review for conflicts
  - Resolve collisions: same email/two people, same person/two accounts/diff attributes
  - Build an identity-mapping table; do NOT auto-merge on weak signals (account takeover risk)

Phase 2  Level up the weaker MFA posture
  - Whichever org has weaker MFA gets brought up to the stronger baseline BEFORE merging
    auth, so unification doesn't lower anyone's assurance

Phase 3  Converge onto the target IdP, per-cohort
  - Migrate user cohorts (by org unit / app) to the chosen IdP with link-and-verify flows
  - Old IdP stays live (rollback) until each cohort is verified on the new one

Phase 4  Decommission the retired IdP on a published schedule
```

The parts that are genuinely hard and where this differs from a normal migration: **(1) Account reconciliation is a security-sensitive operation, not a data-cleanup task.** Auto-merging two accounts because they share an email is an *account-takeover vector* — if the match is wrong, you've just given one person access to another's account. So matching must be deterministic on *verified* identifiers (corporate email you control, employee ID) first, with human-in-the-loop review for any ambiguous or conflicting case, and *re-verification* (re-auth/email confirmation) before any actual merge. **(2) Never lower assurance during unification.** If company A uses passkeys and company B uses SMS OTP, the merged baseline must be the *stronger* posture — bring B up *before* unifying, so the merger doesn't create a weak-link cohort attackers target (the downgrade logic of Q77). **(3) Collision handling.** Overlapping namespaces (two `jsmith`s, shared external emails) need an explicit disambiguation and namespacing strategy, decided before federation, or you'll authenticate the wrong person. **(4) Rollback per cohort.** Keep the source IdP authoritative for a cohort until that cohort is proven on the target, so any reconciliation or migration error affects a bounded set and reverts cleanly.

What I'd actually do: federate immediately via a broker for day-one interop (no data movement, lowest risk), invest heavily in *careful* identity reconciliation with verified-identifier matching plus human review, raise the weaker org's MFA to the stronger baseline before converging, migrate cohort-by-cohort with link-and-verify and per-cohort rollback, and decommission the retired IdP only on a published deprecation date once every cohort is verified. The senior framing: the protocol federation (Q23) is the *easy* 20%; the hard 80% is **reconciling two populations of real human identities without creating account-takeover or assurance-downgrade risks** — which is why the answer leads with "federate to buy time, then reconcile carefully," never "pick one IdP and migrate everyone this weekend."

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
