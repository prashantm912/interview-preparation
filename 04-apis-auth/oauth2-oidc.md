# OAuth 2.0 & OpenID Connect

OAuth 2.0 is the industry-standard framework for **delegated authorization** (granting an app limited access to your resources without sharing your password), while OpenID Connect (OIDC) layers **authentication** (proving who you are) on top of it. Together they power "Sign in with Google/Microsoft/Apple", API access tokens, and machine-to-machine auth across virtually every modern system.

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

### Q1. [Theory] What problem does OAuth 2.0 solve, and what are the four roles it defines?

OAuth 2.0 solves the **delegated access** problem: how can a third-party application access a subset of your data on another service **without** you handing over your username and password. Before OAuth, apps asked you to type your Gmail password so they could read your contacts — a catastrophic anti-pattern that gives away full account control forever. OAuth replaces that with scoped, revocable, time-limited tokens.

The four roles:

```
+------------------+        wants to use         +------------------+
|  Resource Owner  |  --------------------------> |     Client       |
|  (the human/user)|                              |  (the app)       |
+------------------+                              +------------------+
                                                          |
                          requests token with consent     |
                                                          v
+------------------+   issues tokens   +-------------------------------+
| Resource Server  | <---------------- |   Authorization Server (AS)   |
| (the API holding |   validates token |  (issues access/refresh/ID    |
|  the data)       |                   |   tokens, runs login + consent)|
+------------------+                   +-------------------------------+
```

- **Resource Owner** — the user who owns the data.
- **Client** — the application requesting access (web app, SPA, mobile app, backend service).
- **Authorization Server** — authenticates the user, gets consent, issues tokens.
- **Resource Server** — the API that holds protected resources and accepts access tokens.

The key insight is **separation of concerns**: the resource server never sees the user's credentials, only a token it can validate.

### Q2. [Theory] What is the difference between OAuth 2.0 and OpenID Connect?

OAuth 2.0 is **authorization** ("this app may read your calendar"); OpenID Connect is **authentication** ("this user is alice@example.com, verified just now"). A frequent and dangerous mistake is using a raw OAuth access token as proof of identity — access tokens are opaque to the client and were never meant to convey "who". OIDC fixes this by adding:

- An **ID Token** (a signed JWT containing user identity claims like `sub`, `email`, `name`).
- A standardized `/userinfo` endpoint.
- A **discovery** document at `/.well-known/openid-configuration`.
- The `openid` scope, which signals you want OIDC behavior.

Rule of thumb: if you want to *log a user in*, use OIDC. If you want to *call an API on the user's behalf*, use OAuth. Most real "Sign in with X" buttons use both at once.

### Q3. [Theory] What are scopes and consent, and why do they matter?

A **scope** is a space-delimited string that names a permission, e.g. `read:contacts profile email openid`. The client requests scopes; the authorization server shows the user a **consent screen** ("App X wants to read your contacts") and the user approves or denies. The issued access token is then limited to the granted scopes, enforcing the **principle of least privilege**.

Scopes matter because they bound the blast radius if a token leaks: a token scoped to `read:profile` cannot delete your account. They also drive the consent UX, which is a legal and trust requirement (GDPR, app-store policies). Note that scopes are *coarse-grained* by design — fine-grained, per-resource authorization (e.g. "only invoice #42") is usually enforced by the resource server using claims, not by scopes alone.

### Q4. [Theory] Access token vs. refresh token vs. ID token — what is each for?

| Token | Audience | Purpose | Lifetime | Format |
|-------|----------|---------|----------|--------|
| **Access token** | Resource Server (API) | Authorize API calls | Short (5–60 min) | Opaque or JWT |
| **Refresh token** | Authorization Server | Obtain new access tokens silently | Long (days–months) | Opaque (secret) |
| **ID token** | Client | Prove user identity / authentication | Short | Always a signed JWT |

- The **access token** is presented to the API in the `Authorization: Bearer <token>` header.
- The **refresh token** is *never* sent to the resource server — only to the AS's token endpoint to mint fresh access tokens, so the user does not re-login every 15 minutes.
- The **ID token** is consumed *by the client itself* to establish a session; it should not be sent to APIs as an access token.

Short access-token lifetimes plus long-lived refresh tokens is the standard trade-off: limited damage if an access token leaks, while keeping good UX.

### Q5. [Practical] Walk through the Authorization Code flow with PKCE at a high level.

This is the **default, recommended flow for nearly all clients** in 2026 (web apps, SPAs, mobile, even confidential servers).

```
 User      Client (app)                    Authorization Server      Resource Server
  |             |                                   |                       |
  |  click login|                                   |                       |
  |------------>| 1. generate code_verifier (random)|                       |
  |             |    code_challenge = S256(verifier)|                       |
  |             | 2. redirect to /authorize?        |                       |
  |             |    client_id, redirect_uri, scope,|                       |
  |             |    state, code_challenge, S256 --->|                       |
  |  login + consent <------------------------------>|  (user authenticates) |
  |             | 3. 302 redirect_uri?code=XYZ&state|                       |
  |<------------|<----------------------------------|                       |
  |             | 4. POST /token: code=XYZ,         |                       |
  |             |    code_verifier, client_id ------>| verifies S256(verifier|
  |             |                                   |  == challenge)        |
  |             | 5. {access_token, id_token,       |                       |
  |             |     refresh_token} <--------------|                       |
  |             | 6. Bearer access_token ---------------------------------->|
  |             |                                   |   validate + respond  |
  |<------------|<---------------------------------------------------------|
```

The two-step "code then token" exchange means the access token never travels through the browser address bar or browser history; only a one-time `code` does, and that code is useless without the `code_verifier` (PKCE).

### Q6. [Practical] How do you validate a Bearer access token in a Spring Boot 3 resource server?

In production you do **not** write token parsing by hand — you configure Spring Security as an OAuth2 Resource Server and let it validate signature, issuer, audience, and expiry.

```java
// build.gradle: implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'

// application.yml
// spring.security.oauth2.resourceserver.jwt.issuer-uri: https://idp.example.com/realms/myrealm

@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin")
                .anyRequest().authenticated())
            // Validates JWT signature via JWKS, checks iss/exp/nbf automatically:
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

Spring fetches the JWKS (public keys) from the discovery document, caches them, validates the signature and standard claims, and maps scopes to `SCOPE_*` authorities. **Edge cases to remember:** always also validate the `aud` (audience) claim so a token minted for another API cannot be replayed against yours, and pin the expected `issuer`.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Why are the Implicit grant and Resource Owner Password Credentials (ROPC) grant deprecated?

Both are removed/forbidden in OAuth 2.1 because they are insecure by construction.

**Implicit grant** returned the access token *directly in the URL fragment* (`#access_token=...`) from the `/authorize` endpoint, with no code exchange. Problems: the token leaks into browser history, `Referer` headers, and logs; there is no client authentication; and it predated PKCE so it was vulnerable to token injection. It existed only because old browsers lacked CORS for SPAs to call the token endpoint — that constraint is gone, so SPAs now use **Authorization Code + PKCE**.

**ROPC** has the client collect the user's actual username and password and POST them to the token endpoint. This completely defeats OAuth's purpose: the app sees the raw credentials, it cannot support MFA/SSO/federation, and it trains users to type passwords into third-party apps. It only ever existed for migrating legacy apps and is now strongly discouraged.

### Q8. [Theory] Explain PKCE in depth — what attack does it prevent and how?

PKCE (Proof Key for Code Exchange, RFC 7636, pronounced "pixy") defends against the **authorization code interception attack**. On mobile, the redirect happens via a custom URI scheme (`myapp://callback`) that a *malicious app installed on the same device* could register and hijack, stealing the `code`. Without PKCE, the attacker could exchange that code for tokens.

PKCE binds the code to a per-request secret:

```
1. Client generates: code_verifier = high-entropy random string (43–128 chars)
2. code_challenge = BASE64URL( SHA-256( code_verifier ) )    // "S256" method
3. /authorize?...&code_challenge=<challenge>&code_challenge_method=S256
   --> AS stores the challenge alongside the issued code
4. /token?...&code=<code>&code_verifier=<verifier>
   --> AS checks: BASE64URL(SHA256(verifier)) == stored challenge ?
       if not, REJECT.
```

Because only the legitimate client knows the `code_verifier` (it never left the device until the back-channel token call), a stolen `code` is useless. Always use `S256`, never `plain`. In OAuth 2.1, PKCE is **mandatory for all clients**, including confidential server-side clients, as defense in depth.

### Q9. [Coding] Implement a correct PKCE code_verifier/code_challenge generator in Java.

**Problem:** Generate a cryptographically random `code_verifier` and its `S256` `code_challenge` per the spec.

```java
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class Pkce {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER =
            Base64.getUrlEncoder().withoutPadding(); // base64url, no '='

    /** RFC 7636: verifier is 43–128 chars of unreserved set; 32 random bytes -> 43 chars. */
    public static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** code_challenge = BASE64URL(SHA-256(ASCII(code_verifier))) */
    public static String challengeS256(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static void main(String[] args) {
        String verifier = generateCodeVerifier();
        String challenge = challengeS256(verifier);
        System.out.println("code_verifier  = " + verifier);   // keep secret, store in session
        System.out.println("code_challenge = " + challenge);  // send to /authorize
    }
}
```

**Time/Space complexity:** O(n) over the verifier length for the hash (n ≤ 128), O(1) space. **Edge cases:** Must use `SecureRandom`, not `Math.random()` or `Random` (predictable). Must use base64**url** (`-`/`_`), not standard base64 (`+`/`/`), and strip padding `=`. The verifier must be stored server-side (HTTP session) or in secure storage so the same instance can present it at the token endpoint; never recompute it.

### Q10. [Practical] When would you use the Client Credentials grant, and how is it different from user flows?

Use **Client Credentials** for **machine-to-machine (M2M)** access where there is *no user* — a backend cron job calling another internal API, a service-mesh sidecar, a CI pipeline pulling artifacts. There is no resource owner to consent and no browser redirect.

```
Service A                          Authorization Server
   | POST /token                          |
   |   grant_type=client_credentials      |
   |   client_id=svcA                     |
   |   client_secret=*** (or mTLS / JWT)  |
   |   scope=orders.read ---------------->|
   |   <-- { access_token } --------------|
   | Bearer access_token --> Service B (Resource Server)
```

Key differences: no `id_token` and no `refresh_token` (the client just re-requests when needed since it holds the credentials); the token's subject (`sub`) is the *client itself*, not a person. **Production note:** prefer **mTLS client authentication** or **private_key_jwt** over a shared `client_secret`, because secrets leak via logs and config. Rotate secrets, and scope M2M tokens tightly.

### Q11. [Practical] Explain the Device Authorization Grant (device code flow) and when to use it.

Use it for **input-constrained devices** with no browser/keyboard: smart TVs, CLIs, IoT, gaming consoles. The device can't show a login form, so it offloads auth to the user's phone.

```
 Device                         Auth Server                    User's Phone
   | POST /device_authorization       |                            |
   |   client_id, scope ------------->|                            |
   |<-- device_code, user_code,       |                            |
   |    verification_uri, interval    |                            |
   |                                  |                            |
   | shows: "Go to example.com/device |                            |
   |         and enter ABCD-1234"  ---------------------- user reads & goes -->|
   |                                  |  user logs in + approves   |
   |  --- polls POST /token ------>   |  (enters ABCD-1234)        |
   |  grant_type=device_code          |<---------------------------|
   |  (authorization_pending...)      |                            |
   |  ... keeps polling at interval...|                            |
   |<-- { access_token, refresh } ----|  (after approval)          |
```

The device **polls** the token endpoint with the `device_code`; the AS returns `authorization_pending` until the user approves, then returns tokens. The device must honor the `interval` and back off on `slow_down`. Security note: the short `user_code` is human-typed, so display it clearly and bind verification to a logged-in session to resist phishing.

### Q12. [Theory] How does the Refresh Token grant work, and what is refresh token rotation?

The refresh token grant lets a client get a new access token without re-prompting the user:

```
POST /token
  grant_type=refresh_token
  refresh_token=<old RT>
  client_id=...  (+ client auth if confidential)
--> { access_token (new), refresh_token (maybe new), expires_in }
```

**Refresh token rotation** means the AS issues a *new* refresh token on each use and invalidates the old one (one-time-use). This is critical for public clients (SPAs, mobile) that can't keep a secret. The detection trick: if a *stolen* refresh token is replayed *after* the legitimate client already rotated it, the AS sees an **already-used** token and revokes the entire token family — both attacker and victim are logged out, signaling a breach. This is called **automatic reuse detection** and is mandated for SPAs in OAuth 2.1. Trade-off: rotation requires server-side state and careful handling of races (e.g. a client retrying after a network blip can accidentally trigger reuse detection, so allow a small grace window).

### Q13. [Theory] What is the OIDC discovery document and JWKS, and why do they matter operationally?

OIDC discovery is a JSON document at `https://issuer/.well-known/openid-configuration` that advertises the provider's endpoints and capabilities:

```json
{
  "issuer": "https://idp.example.com",
  "authorization_endpoint": "https://idp.example.com/authorize",
  "token_endpoint": "https://idp.example.com/token",
  "userinfo_endpoint": "https://idp.example.com/userinfo",
  "jwks_uri": "https://idp.example.com/.well-known/jwks.json",
  "response_types_supported": ["code"],
  "id_token_signing_alg_values_supported": ["RS256"]
}
```

The `jwks_uri` exposes the **JSON Web Key Set** — the public keys used to verify JWT signatures, each tagged with a `kid` (key ID). Operationally this enables **zero-touch key rotation**: the IdP can rotate signing keys and publish new ones at the JWKS endpoint, and resource servers automatically pick them up by matching the token's `kid` header. **Pitfall:** clients should cache the JWKS (respecting cache headers) but be able to refresh on an unknown `kid`, and must restrict accepted algorithms (never accept `alg: none` or unexpected algorithms — a classic JWT bypass).

### Q14. [Coding] Validate an OIDC ID token's signature and standard claims in Java (Nimbus JOSE+JWT).

**Problem:** Given a raw ID token JWT and the IdP's JWKS URL, verify the signature and the required OIDC claims (`iss`, `aud`, `exp`, `nonce`).

```java
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.*;
import java.net.URL;
import java.util.*;

public class IdTokenValidator {

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
    private final String expectedIssuer;
    private final String expectedAudience; // == your client_id

    public IdTokenValidator(URL jwksUrl, String issuer, String clientId) throws Exception {
        this.expectedIssuer = issuer;
        this.expectedAudience = clientId;

        // Cached, auto-refreshing JWKS source keyed by 'kid'
        JWKSource<SecurityContext> keySource =
                JWKSourceBuilder.create(jwksUrl).retrying(true).build();

        var processor = new DefaultJWTProcessor<SecurityContext>();
        // Pin algorithm: only accept RS256 — reject 'none' and HS* (key-confusion) attacks
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));

        // Require and verify standard claims
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(clientId)
                        .build(),
                new HashSet<>(Arrays.asList("sub", "iat", "exp")))); // required claims
        this.jwtProcessor = processor;
    }

    /** @param expectedNonce the nonce you sent in /authorize; null if not used */
    public JWTClaimsSet validate(String idToken, String expectedNonce) throws Exception {
        JWTClaimsSet claims = jwtProcessor.process(idToken, null); // throws on bad sig/exp/iss/aud
        if (expectedNonce != null && !expectedNonce.equals(claims.getStringClaim("nonce"))) {
            throw new SecurityException("nonce mismatch — possible replay/CSRF");
        }
        return claims; // safe to trust: claims.getSubject(), getStringClaim("email"), ...
    }
}
```

**Time/Space complexity:** Signature verification is O(1) with respect to your data (fixed RSA op); JWKS fetch is amortized via caching, O(1) per validation after warm-up. **Edge cases:** clock skew (allow ~60s leeway on `exp`/`iat`); `aud` may be an array — ensure your `client_id` is present; reject if the JWT `alg` header doesn't match what you pinned; verify `nonce` to tie the token to your original request. **Never** decode-and-trust without verifying the signature.

### Q15. [Practical] What are token introspection and revocation, and when do you need them?

**Introspection** (RFC 7662) is the resource server asking the AS "is this token still valid, and what's in it?" via `POST /introspect`. It's needed for **opaque** (non-JWT) access tokens, or when you need *real-time* validity (a JWT is valid until `exp` even if revoked).

```
Resource Server                     Authorization Server
   | POST /introspect                       |
   |   token=<access_token>                 |
   |   (client auth) -------------------->  |
   |  <-- { "active": true, "scope": "...", |
   |        "sub": "...", "exp": ... } ------|
```

**Revocation** (RFC 7009) is the client/IdP invalidating a token before it expires via `POST /revoke` — used on logout, password change, or suspected compromise.

**The trade-off:** JWTs are *self-contained* (no network call to validate → fast, scalable) but *can't be instantly revoked* (valid until `exp`). Introspection gives instant revocation but adds a network hop + load on the AS per request. The common production answer: **short-lived JWT access tokens (5–15 min)** so the revocation window is small, plus introspection only on high-value operations, or a hybrid where the gateway introspects/caches.

### Q16. [Practical] How do you configure scope-to-role mapping and method security in Spring Boot 3?

By default Spring maps the JWT `scope`/`scp` claim to `SCOPE_*` authorities. For role-based APIs you often want to map a custom claim (e.g. Keycloak's `realm_access.roles`) to `ROLE_*` authorities.

```java
@Bean
JwtAuthenticationConverter jwtAuthConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        Collection<GrantedAuthority> auths = new ArrayList<>();
        // standard scopes -> SCOPE_*
        String scope = jwt.getClaimAsString("scope");
        if (scope != null) for (String s : scope.split(" "))
            auths.add(new SimpleGrantedAuthority("SCOPE_" + s));
        // Keycloak realm roles -> ROLE_*
        Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
        if (realm != null && realm.get("roles") instanceof List<?> roles)
            for (Object r : roles) auths.add(new SimpleGrantedAuthority("ROLE_" + r));
        return auths;
    });
    return converter;
}

@Service
class OrderService {
    @PreAuthorize("hasAuthority('SCOPE_orders.write') and hasRole('MANAGER')")
    public void cancelOrder(String id) { /* ... */ }
}
```

Wire `jwtAuthConverter()` into `oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(...)))` and add `@EnableMethodSecurity`. **Trade-off:** baking roles into the JWT is fast but the token can go stale if a user's role changes mid-session — keep access tokens short or re-check sensitive permissions against a live store.

### Q17. [Theory] What is the `state` parameter and what attack does it prevent?

`state` is an opaque, unguessable value the client generates before redirecting to `/authorize`, then verifies on the callback. It prevents **CSRF on the OAuth callback**: without it, an attacker could trick a victim's browser into hitting the client's `redirect_uri` with the *attacker's* authorization code, causing the victim to be silently logged into the *attacker's* account (or to link the attacker's identity to the victim's session). By binding `state` to the user's session and checking it on return, the client rejects unsolicited callbacks. In OIDC, `nonce` plays an analogous anti-replay role for the ID token. With PKCE now mandatory, PKCE also provides CSRF protection on the token request, but `state` is still required to protect the *redirect* itself and to carry app context (e.g. the return URL).

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] Explain the OAuth "mix-up" attack and its mitigations.

The **mix-up attack** targets clients that support **multiple authorization servers** (e.g. "log in with Google OR with our own IdP"). The attacker, who controls a malicious/compromised AS that the client also trusts, manipulates the flow so the client sends the authorization `code` (or token request) to the **honest AS's endpoints but believes it's talking to the attacker's AS** — or vice versa — causing the client to deliver a code/token issued by one AS to the wrong party.

```
1. Client lets user pick AS. Attacker steers user to start with Attacker-AS.
2. Attacker-AS responds but redirects so the code actually comes from Honest-AS,
   while the client still thinks the response came from Attacker-AS.
3. Client sends Honest-AS's code to Attacker-AS's token endpoint -> leaked code.
```

**Mitigations:**
- **`iss` parameter in the authorization response** (RFC 9207): the AS returns its own issuer identifier on the callback; the client verifies it matches the AS it *initiated* with. This is the primary, mandated fix in OAuth 2.1.
- Use **distinct `redirect_uri` per AS** so the client can tell which AS a response is for.
- Bind the `state` to the chosen AS.
- **PKCE** helps but does not fully solve mix-up alone.

### Q19. [Theory] JWT vs. opaque access tokens at scale — how do you decide?

```
                 JWT (self-contained)          Opaque (reference)
Validation       local sig check (fast)        introspection call (network)
Revocation       hard — valid until exp         instant (AS is source of truth)
Payload exposure  claims readable by anyone     no info leaks; just a handle
AS load          low (only at issuance)         high (per-request introspection)
Token size       large (KBs in headers)         small
Best for         high-throughput microservices  centralized control, sensitive data
```

The mature answer is a **hybrid / Phantom Token pattern**: external clients receive **opaque** tokens (no claims leakage, instant revocation at the edge); an **API gateway** introspects the opaque token, caches the result briefly, and forwards a short-lived **JWT** to internal microservices so they validate locally without hammering the AS. This combines opaque-token control at the perimeter with JWT performance internally. Decision drivers: revocation latency requirements, claim sensitivity, number of services, and AS capacity.

### Q20. [Practical] Design SSO and single logout across 10 microservices and 3 SPAs. What are the pitfalls?

**Approach:** Centralize on one IdP (Keycloak/Auth0/Azure AD/Entra ID). All apps are OIDC clients of one realm/tenant. Browser apps use Authorization Code + PKCE; the IdP maintains an SSO session cookie on its own domain, so after the first login other apps get tokens silently (no re-prompt) via the same session.

```
                         +------------------+
                         |   IdP (Keycloak) |  <-- single SSO session cookie
                         +------------------+
                          /     |       \
            +------------+   +---------+   +-----------+
            |   SPA-1    |   |  SPA-2  |   |   SPA-3   |   (OIDC clients, PKCE)
            +------------+   +---------+   +-----------+
                  |  Bearer access tokens (short-lived)
        +---------------- API Gateway --------------------+
        |    introspect/validate, then route to services   |
        +--------------------------------------------------+
          svc1  svc2  svc3 ... svc10  (resource servers)
```

**Single Logout (SLO) pitfalls** — this is the hardest part:
- **Front-channel logout** (IdP loads hidden iframes to each app's logout URL) is fragile: third-party cookie blocking (Safari ITP, Chrome) breaks the iframes, and any one app failing silently leaves a session alive.
- **Back-channel logout** (IdP POSTs a signed logout token to each app's server endpoint) is more robust but requires every app to maintain a server-side session registry keyed by `sid`.
- Local API sessions are stateless JWTs that **can't be force-killed** mid-session — so on logout you revoke refresh tokens and rely on short access-token TTLs.

**What I'd actually do:** OIDC RP-Initiated Logout + back-channel logout for server apps, short access tokens (5–10 min), refresh-token rotation with reuse detection, and a centralized session/revocation list at the gateway for emergency kill. Accept that there's a bounded window where an already-issued JWT stays valid.

### Q21. [Coding] Implement refresh-token rotation with reuse detection (server-side logic).

**Problem:** A public client refreshes tokens. Implement the AS-side logic: rotate the refresh token on each use, and if a *previously rotated* token is presented again, revoke the whole family (breach detected).

```java
record RefreshToken(String id, String familyId, String userId, boolean used, Instant exp) {}

public class RefreshService {

    private final Map<String, RefreshToken> store;      // token id -> token
    private final Set<String> revokedFamilies;          // familyIds that are killed

    public RefreshService(Map<String, RefreshToken> store, Set<String> revokedFamilies) {
        this.store = store; this.revokedFamilies = revokedFamilies;
    }

    /** Returns a NEW refresh token; throws and burns the family on reuse/abuse. */
    public synchronized RefreshToken rotate(String presentedId) {
        RefreshToken rt = store.get(presentedId);
        if (rt == null) throw new SecurityException("unknown_token");
        if (revokedFamilies.contains(rt.familyId()))
            throw new SecurityException("family_revoked");
        if (Instant.now().isAfter(rt.exp()))
            throw new SecurityException("expired");

        if (rt.used()) {
            // REUSE DETECTED: an old, already-rotated token was replayed -> breach.
            revokedFamilies.add(rt.familyId());                 // kill all descendants
            store.values().removeIf(t -> t.familyId().equals(rt.familyId()));
            throw new SecurityException("reuse_detected_family_revoked");
        }

        // mark current as used, mint successor in same family
        store.put(rt.id(), new RefreshToken(rt.id(), rt.familyId(), rt.userId(), true, rt.exp()));
        String newId = UUID.randomUUID().toString();
        RefreshToken next = new RefreshToken(newId, rt.familyId(), rt.userId(),
                                             false, Instant.now().plus(Duration.ofDays(30)));
        store.put(newId, next);
        return next;
    }
}
```

**Time/Space complexity:** `rotate` is O(1) for the happy path (map lookups/puts); reuse cleanup is O(F) where F = tokens in the family. Space O(N) for active tokens. **Edge cases:** legitimate retries after a dropped response can present a just-rotated token — production systems add a short **grace window** (accept the immediate predecessor for a few seconds) to avoid false-positive family revocations; the `synchronized`/transaction boundary is essential to prevent a race where two refreshes both succeed.

### Q22. [Coding] Detect an expired or about-to-expire JWT without a library (parse exp).

**Problem:** Given a JWT string, decode the payload and determine seconds until expiry, returning negative if expired. (For signature checks use a library — this is just claim inspection.)

```java
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JwtExpiry {
    private static final ObjectMapper M = new ObjectMapper();

    /** @return seconds until exp (negative = already expired); throws on malformed input. */
    public static long secondsUntilExpiry(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("not a JWS compact token");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]); // base64url, tolerant of no pad
        try {
            JsonNode node = M.readTree(new String(payload, StandardCharsets.UTF_8));
            if (!node.has("exp")) throw new IllegalArgumentException("no exp claim");
            long exp = node.get("exp").asLong();                  // NumericDate = epoch seconds
            return exp - (System.currentTimeMillis() / 1000L);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad JWT payload", e);
        }
    }

    public static boolean needsRefresh(String jwt, long skewSeconds) {
        return secondsUntilExpiry(jwt) <= skewSeconds;            // refresh proactively
    }
}
```

**Time/Space complexity:** O(n) in token length for split + base64 decode + JSON parse; O(n) space for the decoded payload. **Edge cases:** `exp` is in **seconds** (NumericDate), not millis — a classic bug is comparing against `System.currentTimeMillis()` directly; tokens may use base64**url** without padding (use `getUrlDecoder`); always apply a clock-skew buffer (e.g. 30–60s) so you refresh *before* the API rejects the token. **Security caveat:** this only reads claims — it does **not** verify the signature, so never make trust decisions on the decoded payload alone.

### Q23. [Practical] How do real providers differ: Keycloak vs. Auth0 vs. Azure AD (Entra ID)?

All three implement OAuth 2.0/OIDC, but the operational reality differs:

- **Keycloak** (open-source, self-hosted): organizes everything into **realms** and **clients**. You run and patch it yourself — full control, no per-MAU cost, supports fine-grained authorization (UMA), custom SPIs. Discovery at `/realms/{realm}/.well-known/openid-configuration`. Realm roles appear under `realm_access.roles`. Best when you need data residency, customization, or want to avoid vendor lock-in; the cost is you own HA, upgrades, and key rotation.

- **Auth0** (SaaS, Okta-owned): developer-friendly, **Rules/Actions** pipeline to enrich tokens, Universal Login, extensive social connections. Pricing is per **MAU** which can surprise at scale. You must use a **custom API audience** to get a JWT access token (otherwise you get an opaque one). Great for fast product launches.

- **Azure AD / Microsoft Entra ID** (SaaS, enterprise): the default for Microsoft-shop SSO, deep Conditional Access / MFA / device-compliance policies. Quirks: the **v1 vs v2 endpoint** distinction matters, access tokens for **Microsoft Graph are not meant to be validated by your APIs**, and you register **app registrations** with explicit scopes/`api://` audiences. Strong for B2B/B2C with `tenant` isolation.

**What I'd actually pick:** Entra ID if the org already lives in Microsoft 365; Auth0 for a startup that wants speed and rich social login; Keycloak for cost-sensitive, regulated, or highly customized internal platforms.

### Q24. [Practical] An access token leaked in server logs. Walk through incident response.

**Immediate containment:** Revoke the leaked token (RFC 7009 `/revoke`) and, critically, **revoke the associated refresh token family** so it can't mint new tokens. If the token is a JWT (not instantly revocable), add it to a deny-list at the gateway and rely on the short TTL to age it out. Force re-authentication for the affected user/session.

**Scope the blast radius:** What scopes did the token carry? What did the attacker access during the validity window? Pull resource-server access logs filtered by that `jti`/subject. If a `client_secret` also leaked (M2M), rotate it immediately and audit all tokens issued under that client.

**Root cause + remediation:** Stop logging the `Authorization` header — scrub Bearer tokens in log appenders and tracing (OpenTelemetry redaction). Shorten access-token TTL. Move M2M auth from shared secrets to **mTLS / private_key_jwt**. Consider **sender-constrained tokens (DPoP or mTLS)** so a leaked token is useless without the client's proof-of-possession key — this is the strongest structural fix.

**Real-world echo:** This mirrors numerous breaches where bearer tokens or API keys ended up in logs, error trackers, or public repos — the lesson industry learned is to treat tokens as secrets in observability pipelines and to prefer short-lived, sender-constrained credentials.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] What are sender-constrained tokens (DPoP and mTLS), and why are they the future?

The fundamental weakness of **bearer** tokens is in the name: *whoever bears it, can use it* — a stolen token works for the attacker. **Sender-constrained** (proof-of-possession) tokens bind the token to a cryptographic key the legitimate client holds, so a stolen token is worthless without the private key.

- **mTLS-bound tokens (RFC 8705):** the access token is bound to the client's TLS client certificate. The resource server checks the token's `cnf.x5t#S256` confirmation claim against the cert on the TLS connection. Robust but requires PKI/cert management — common in banking/Open Banking (FAPI).
- **DPoP (RFC 9449):** the client generates a key pair and signs a per-request **DPoP proof JWT** (including the HTTP method, URI, and a nonce) with its private key; the access token carries a `cnf.jkt` thumbprint of the public key. The resource server verifies the proof matches. No PKI needed — ideal for SPAs and mobile.

```
Bearer:           Authorization: Bearer <token>            (replayable if stolen)
DPoP:             Authorization: DPoP <token>
                  DPoP: <signed proof JWT bound to method+uri+key>   (not replayable)
```

They're "the future" because token theft (XSS, log leaks, MITM on a misconfigured proxy) is the dominant residual risk after PKCE/short TTLs; sender-constraining neutralizes it. **FAPI 2.0** and high-assurance profiles now require it. The trade-off is added client complexity and key management.

### Q26. [Theory] Critically assess OAuth 2.1 — what it consolidates and what it deliberately leaves unsolved.

OAuth 2.1 is a **consolidation**, not a new protocol: it folds the original RFC 6749, the Security BCP (RFC 9700), PKCE, and bearer-token usage into one coherent document and removes footguns. Key mandates: **PKCE for all clients**, **exact redirect-URI string matching** (no wildcards/substring), **Implicit and ROPC removed**, **refresh-token rotation or sender-constraining for public clients**, and the **`iss` response parameter** against mix-up.

What it *deliberately leaves out*: it does **not** force DPoP/mTLS (those remain separate profiles), it doesn't standardize **fine-grained authorization** (that's RAR — Rich Authorization Requests, RFC 9396 — and the broader policy world of OPA/Cedar/AuthZEN), and it punts on **session management/logout** consistency (still a patchwork of front/back-channel). My critical take: 2.1 hardens the *authorization* layer well, but real systems still must layer on PoP tokens, RAR for transactional consent, and a coherent authZ engine — OAuth was always an *authorization framework*, not a complete security solution, and 2.1 doesn't change that boundary.

### Q27. [Practical] How would you design fine-grained, transactional authorization beyond scopes (e.g. "pay exactly £50 to account X")?

Scopes are too coarse for transactional consent — `payments` can't express *amount* or *payee*. The 2026 answer is **Rich Authorization Requests (RAR, RFC 9396)**: the client sends a structured `authorization_details` JSON array describing the exact operation, and the AS shows the user a precise consent ("Approve a payment of £50 to ACME Ltd").

```json
"authorization_details": [{
  "type": "payment_initiation",
  "actions": ["initiate"],
  "instructedAmount": { "currency": "GBP", "amount": "50.00" },
  "creditorAccount": { "iban": "GB29NWBK..." }
}]
```

The granted details are echoed into the token as a claim; the resource server enforces them. Combine with:
- **PAR (Pushed Authorization Requests, RFC 9126):** the client pushes the (large, sensitive) request to the AS back-channel first and only passes a `request_uri` reference through the browser — prevents tampering and avoids URL length limits. Mandatory in FAPI 2.0.
- **A policy engine** (OPA/Cedar) at the resource server for relationship-based checks ("is this user an owner of account X?") that don't belong in a token.

This is exactly how **Open Banking / PSD2 / FAPI** ecosystems implement payment consent today — the combination of RAR + PAR + sender-constrained tokens is the high-assurance baseline.

### Q28. [Theory] Why is using OAuth 2.0 directly for authentication an anti-pattern, and what historically went wrong?

OAuth 2.0 is an *authorization* protocol; using its access token as an *authentication* assertion is the "**OAuth as login**" anti-pattern. The core flaws:

1. **The confused-deputy / token-substitution problem:** an access token says nothing about *who* requested it or *for which client* it was issued. A malicious app can take a token a user granted to *it* and replay it to *your* "login with OAuth" endpoint; if you naively call `/userinfo` and trust the result, you log in as that user — the classic flaw that early "Login with Facebook" implementations using the implicit flow suffered.
2. **No audience binding:** bearer access tokens lack a verifiable audience tying them to your client.
3. **No standard identity contract:** there was no agreed shape for "who is this user".

OIDC fixes all three: the **ID token is audience-bound** (`aud` = your `client_id`), **signed** (you verify, not just receive), **nonce-bound** to your request, and contains a stable `sub`. The historical lesson — codified after numerous social-login breaches — is: *authenticate with OIDC ID tokens you validate; authorize APIs with access tokens; never infer identity from a bearer access token.*

### Q29. [Behavioral] Tell me about a time you had to push back on a team that wanted to take an OAuth shortcut.

**(Situation/Task)** A product team under launch pressure wanted to ship a SPA that stored the **refresh token in `localStorage`** and used the long-deprecated implicit flow "because it was simpler and the SDK examples did it." **(Action)** Rather than just vetoing, I quantified the risk: I demonstrated in a staging environment how a single XSS could exfiltrate the `localStorage` refresh token and how that token, being long-lived and non-rotating, gave persistent account takeover with no detection. I then presented a concrete, *low-friction* alternative: Authorization Code + PKCE with short-lived access tokens, **rotating refresh tokens with reuse detection**, and storing tokens in memory with a silent-renew iframe / BFF (backend-for-frontend) pattern so nothing sensitive sat in JS-readable storage. I scoped it as a two-day change and pair-programmed the first integration. **(Result)** We shipped on time minus one day, passed the security review without exceptions, and the reuse-detection alarm later actually fired during a pen-test, proving the value. **(Reflection)** The lesson I carry: security pushback lands far better when you bring a *cheaper, working alternative* and make the abstract risk concrete, rather than citing a spec. I also turned the BFF pattern into a reusable internal library so the "secure path" became the *default, easy path* for future teams — making the right thing the easy thing is how you scale security across an org.

### Q30. [Practical] Design token strategy for a multi-region, high-throughput platform (millions of req/s) with strict revocation needs.

**Constraints in tension:** validation must be cheap (no per-request AS call at millions of req/s), yet revocation must be near-real-time for compliance. **Architecture:**

```
 Clients --opaque token--> [Regional API Gateway]
                               | introspect ONCE, cache (≤ TTL), then issue
                               v
                          short-lived JWT (5 min, RS256, aud=internal)
                               |
                   +-----------+-----------+
                   v           v           v
                 svc-A       svc-B       svc-C   (validate JWT locally, no AS call)
                               ^
            JWKS replicated read-only to every region; signing keys in HSM at primary
            Revocation list (jti / family) pushed via pub/sub to all gateways (sub-second)
```

Decisions and trade-offs:
- **Phantom-token at the edge:** external = opaque (no claim leakage, central control); internal = JWT (local validation, zero AS load). Gateway introspects once and caches for the access-token TTL.
- **Revocation:** a distributed, replicated **deny-list keyed by `jti`/family**, propagated to all regional gateways via a low-latency pub/sub (e.g. Redis/Kafka), gives sub-second revocation even for JWTs. Combine with 5-minute TTLs to bound exposure.
- **Key management:** signing keys in an **HSM/KMS** at the primary region; **public** JWKS replicated read-only everywhere so validation is local and a region partition doesn't break auth. Automate key rotation with overlapping `kid`s.
- **Latency vs. consistency:** accept eventual consistency on revocation propagation (sub-second) rather than strong consistency, because a synchronous global check would dominate latency.
- **Sender-constraining (DPoP/mTLS)** for the highest-value flows so a leaked token is unusable cross-region.

This is the shape large platforms (banks, big SaaS, cloud IdPs) converge on: cheap local validation, central-but-replicated revocation, HSM-backed keys, and short TTLs as the universal safety net.

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q31. [Theory] What is the difference between a public client and a confidential client, and why does it matter?

The distinction is about **whether the client can keep a secret**. A **confidential client** runs in a trusted environment — a server-side web app, a backend service — where a `client_secret` (or private key) can be stored away from end users. A **public client** runs somewhere the user (or an attacker) can inspect: a SPA's JavaScript bundle, a mobile app's binary, a desktop app. Anything shipped to the device can be decompiled or read in the browser, so a "secret" baked into it is not a secret at all.

This classification drives almost every other security decision. Confidential clients authenticate themselves to the token endpoint (with `client_secret_basic`, `client_secret_post`, `private_key_jwt`, or mTLS), so the AS knows the token request genuinely came from that client. Public clients cannot prove their identity this way, which is precisely why **PKCE** (binding the code to a per-request secret) and **refresh-token rotation with reuse detection** exist — they substitute for the missing client authentication.

```
Confidential:  server holds client_secret  -> can authenticate at /token
Public:        SPA/mobile, no real secret  -> rely on PKCE + RT rotation
```

A common mistake is registering a SPA as a confidential client and shipping the secret in the front-end — auditors will (rightly) flag it. The correct move is to register it as public, enable PKCE, and if you need real client authentication, introduce a **backend-for-frontend (BFF)** that holds the secret server-side and brokers tokens to the browser via a session cookie.

#### Q32. [Practical] You configured "Sign in with Google" and get `redirect_uri_mismatch`. How do you debug it?

This is the single most common OAuth integration error, and it is almost always an **exact-string-matching** problem. The AS compares the `redirect_uri` you send on the `/authorize` request against the list you pre-registered, character-for-character. Any difference — trailing slash, `http` vs `https`, `localhost` vs `127.0.0.1`, a different port, an extra query parameter, uppercase in the host — causes a hard rejection.

Debug systematically:

```bash
# 1. Capture the EXACT redirect_uri your app is sending (decode it!)
#    Look at the browser's network tab on the /authorize request:
#    .../authorize?client_id=...&redirect_uri=https%3A%2F%2Fapp.example.com%2Fcallback&...
#    URL-decode -> https://app.example.com/callback

# 2. Compare byte-for-byte against the registered value in the Google Cloud Console
#    "Authorized redirect URIs". Watch for:
#      - trailing slash:      /callback   vs  /callback/
#      - scheme:              http://     vs  https://
#      - host form:           localhost   vs  127.0.0.1
#      - port:                :3000       vs  (none)
```

The reason providers are this strict is security: lax matching enables **open-redirect token theft**, where an attacker registers `https://app.example.com.evil.com/callback` or appends `?next=//evil.com` and steals codes. OAuth 2.1 mandates exact matching for exactly this reason. The fix is never to loosen matching — it is to register every legitimate redirect URI explicitly (including each environment) and ensure your app constructs the identical string. For local development, register the precise dev URL rather than trying to wildcard it.

#### Q33. [Theory] What is the `nonce` parameter in OIDC, and how does it differ from `state`?

Both are anti-attack nonces the client generates and later verifies, but they protect **different things at different stages**. `state` protects the **authorization response / redirect** against CSRF — the client binds it to the user's session and rejects any callback whose `state` doesn't match, so an attacker can't inject their own code into the victim's browser session. `nonce` protects the **ID token** against replay — the client puts a random `nonce` in the `/authorize` request, the IdP embeds that exact value as a `nonce` claim inside the signed ID token, and the client verifies it on return.

```
state  -> sent on /authorize, echoed on the callback URL  -> protects the REDIRECT (CSRF)
nonce  -> sent on /authorize, embedded INSIDE the ID token -> protects the ID TOKEN (replay)
```

The key difference is *where the value lives on the way back*. `state` comes back as a URL parameter (anyone who sees the callback can read it), so it guards against unsolicited callbacks. `nonce` comes back **inside a signed token**, so an attacker cannot forge it without the IdP's signing key — it cryptographically ties a specific ID token to a specific login attempt, defeating token replay and certain mix-up variants. In a complete OIDC client you use both: `state` for CSRF on the redirect, `nonce` for binding the ID token. Skipping `nonce` is a frequent omission because the flow appears to work without it — until someone replays a captured ID token.

#### Q34. [Practical] How do you read what is inside a JWT during debugging without trusting it?

For debugging you only need the **header** and **payload**, which are base64url-encoded JSON separated by dots. You can decode them in seconds without any library — but the rule is that decoding is for *inspection only*, never for trust decisions.

```bash
# A JWT is header.payload.signature — decode the first two segments.
TOKEN="eyJhbGciOiJSUzI1Ni␪...header...␪.eyJzdWIiOi␪...payload...␪.sig"

# Decode the payload (2nd segment). base64url may lack padding, so pad it:
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | \
  awk '{ while (length($0)%4) $0=$0"="; print }' | base64 -d | jq .

# Decode the header (1st segment) to see alg + kid:
echo "$TOKEN" | cut -d. -f1 | tr '_-' '/+' | \
  awk '{ while (length($0)%4) $0=$0"="; print }' | base64 -d | jq .
```

What to look at when debugging: the header's `alg` and `kid` (does the `kid` exist in the IdP's JWKS?), the payload's `iss`, `aud`, `exp`, `iat`, `scope`, and `sub`. The most common "why is my token rejected" causes show up immediately — `aud` pointing at the wrong API, `iss` not matching what the resource server pins, or an already-past `exp`.

The critical caveat: **base64-decoding proves nothing about authenticity.** The payload is not encrypted and not verified by this process — anyone can mint a JWT with any claims. Tools like jwt.io are fine for *your own* dev tokens, but never paste production tokens into a third-party website (you are handing them a live credential). For any code path that makes an authorization decision, you must verify the signature against the JWKS and validate claims, as in Q14.

#### Q35. [Theory] What does the `aud` (audience) claim do, and why is validating it non-negotiable?

The `aud` (audience) claim names **who the token is for** — the resource server(s) intended to accept it. When the AS mints an access token, it stamps `aud` with the identifier of the target API (e.g. `https://api.orders.example.com` or an `api://` URI). A resource server must check that its own identifier is in the token's `aud` before honoring it; otherwise it is trusting a credential that was never meant for it.

Skipping `aud` validation opens a **token redirection / confused-deputy attack**. Imagine two APIs behind the same IdP: a low-value `analytics` API and a high-value `payments` API. A malicious or compromised client legitimately obtains a token for `analytics` (low scrutiny to get), then replays that same token against `payments`. If `payments` only checks the signature and `exp` but not `aud`, it accepts a token minted for a completely different service.

```
Token issued for:   aud = "https://api.analytics.example.com"
Attacker presents it to:  https://api.payments.example.com
payments must reject:  "analytics" ∉ my expected audience  -> 401
```

`aud` can be a single string or an array, so validation logic must handle both (membership test, not equality). This is why every resource-server framework — Spring's `JwtIssuerValidator`/audience validator, Auth0's middleware, etc. — makes audience a configurable, expected value, and why the audit checklist for any new API always includes "is `aud` pinned?". Combined with pinning `iss`, audience validation is what stops cross-service token replay within a single IdP.

#### Q53. [Practical] What is the `prompt` parameter (`none`, `login`, `consent`, `select_account`) and when do you use each?

`prompt` is a space-delimited authorization-request parameter that tells the IdP **how to handle the user-interaction part of login**. It lets the client control whether the IdP may show UI, must re-authenticate, must re-ask for consent, or must offer account choice — which is essential for both silent flows and security-sensitive flows.

```bash
GET /authorize?...&prompt=none            # NO UI: succeed silently if SSO session exists, else error
GET /authorize?...&prompt=login           # force credential re-entry even if session exists
GET /authorize?...&prompt=consent         # re-show the consent screen even if previously granted
GET /authorize?...&prompt=select_account  # let the user pick among multiple signed-in accounts
```

The most operationally important value is **`prompt=none`**: it performs a *silent* authentication used for token renewal and SSO checks — the IdP either returns a code (if a valid SSO session exists and no interaction is needed) or returns an **error** (`login_required`, `interaction_required`, `consent_required`) instead of showing a page. Clients use it in a hidden iframe or background request to refresh tokens without disturbing the user; the catch (per Q38) is that third-party-cookie blocking now breaks the iframe variant, pushing teams toward refresh tokens or a BFF.

The others map to specific needs: **`prompt=login`** forces fresh authentication for step-up or sensitive actions (it's the blunt cousin of `max_age`, per Q51); **`prompt=consent`** re-obtains consent when you've added scopes or need a fresh `authorization_details` approval; **`prompt=select_account`** is the "switch account" experience when a user has several identities at the IdP. A subtlety: `prompt=none` cannot be combined with the others (you can't both forbid and force UI), and a well-behaved client treats the `*_required` errors as "fall back to an interactive request," not as a hard failure.

### 🟡 Intermediate — extended

#### Q36. [Practical] Walk through configuring an API gateway (e.g. Kong/NGINX/Spring Cloud Gateway) to validate tokens centrally. What are the trade-offs?

Centralizing token validation at the gateway means individual services don't each re-implement JWKS fetching, claim validation, and audience checks — the gateway terminates the auth concern and forwards a trusted, lightweight identity downstream. A typical Spring Cloud Gateway config:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: orders
          uri: lb://orders-service
          predicates: [ Path=/api/orders/** ]
          filters:
            - TokenRelay=            # forward the bearer token downstream
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com/realms/prod
          audiences: [ "orders-api" ]   # gateway validates aud
```

The big design question is **what the gateway forwards**. Three patterns: (1) **pass-through** — forward the original token unchanged (simple, but every service still must trust it and ideally re-validate); (2) **header injection** — the gateway validates, then strips the token and injects trusted headers like `X-User-Id`/`X-Scopes` (services trust the gateway, must be on a private network so nothing else can inject those headers); (3) **phantom token / token exchange** — the gateway swaps an opaque external token for a short-lived internal JWT (best isolation, more moving parts; see Q19/Q30).

```
Client --token--> [Gateway: validate sig/iss/aud, rate-limit, log]
                      |-- pass original token  (services re-validate)
                      |-- inject X-User-Id      (services trust gateway; needs network isolation)
                      |-- swap to internal JWT  (phantom token; strongest)
```

Trade-offs: centralizing reduces per-service complexity and gives one place for rate limiting, logging, and emergency revocation deny-lists — but it makes the gateway a **single point of failure and a juicy target**, and the header-injection pattern is dangerous if a service is ever reachable directly (an attacker spoofs `X-User-Id`). Defense-in-depth says even with a validating gateway, internal services should still verify the (internal) JWT rather than blindly trust headers, unless you have hard network segmentation (mTLS service mesh) guaranteeing only the gateway can reach them.

#### Q37. [Theory] Compare the four standard client authentication methods: client_secret_basic, client_secret_post, private_key_jwt, and mTLS.

These are the ways a **confidential client** proves its identity to the token endpoint. They differ in how the secret material is transmitted and how resistant they are to leakage.

| Method | How it works | Secret leaves the client? | Strength |
|--------|--------------|---------------------------|----------|
| `client_secret_basic` | secret in `Authorization: Basic` header | yes (every request) | weak — shared secret in transit/logs |
| `client_secret_post` | secret in the POST body params | yes (every request) | weak — same risk, slightly worse (body logging) |
| `private_key_jwt` | client signs a short-lived JWT assertion with its **private key**; AS verifies with the public key | **no** — only a signed assertion crosses the wire | strong — no shared secret to leak |
| mTLS | client presents a TLS **client certificate**; AS binds to it | no — private key never leaves; PKI proves identity | strongest — also enables sender-constrained tokens |

The two `client_secret_*` methods rely on a **shared symmetric secret**, which is the weak link: it sits in config, gets copied into CI, and shows up in logs or env dumps. They're acceptable for low-risk internal M2M but are the first thing to upgrade.

`private_key_jwt` (RFC 7523) is a major step up because the AS only ever sees a *signature*, never the key — there is no shared secret that both sides hold, so a breach of AS-side storage doesn't compromise the client. **mTLS** (RFC 8705) is the gold standard for high assurance: it not only authenticates the client but can **sender-constrain the issued tokens** to that certificate (so a stolen token is useless without the cert), which is why FAPI/Open Banking mandate it. The practical migration path is `client_secret_basic` → `private_key_jwt` (no PKI needed) → mTLS (when you have certificate infrastructure).

#### Q38. [Practical] A user complains they get logged out every few minutes. How do you diagnose and fix silent token renewal?

"Logged out every few minutes" almost always means the **access token expired and silent renewal isn't working**, so the app falls back to a full login. The access token TTL (often 5–15 min) is supposed to be invisible to the user because the client silently obtains a fresh one before or just after expiry — when that machinery breaks, the short TTL becomes painfully visible.

Diagnose by isolating which mechanism is failing:

```bash
# 1. Confirm the symptom: decode the access token, check its lifetime.
#    exp - iat == 300?  -> 5-minute tokens, so renewal MUST be working.

# 2. Which renewal mechanism is in use?
#    SPA: silent-renew iframe (prompt=none) OR refresh-token in memory
#    Mobile/server: refresh_token grant

# 3. Watch the network tab for the renewal call:
#    - /authorize?prompt=none failing with 'login_required'  -> IdP SSO cookie missing/blocked
#    - /token grant_type=refresh_token returning invalid_grant -> RT expired/rotated/reused
```

Common root causes and fixes: (a) **Third-party cookie blocking** (Safari ITP, Chrome) breaks the hidden-iframe `prompt=none` silent-renew because the IdP's SSO cookie is treated as third-party — the fix is to stop relying on iframes and use **rotating refresh tokens held in memory** or a **BFF** that holds the session server-side. (b) **Refresh-token reuse-detection false positives** — two tabs or a retry both spend the same RT, the AS sees a reused token and revokes the whole family, logging the user out; the fix is a small **grace window** on rotation (Q21) and coordinating renewal across tabs (a shared worker / lock). (c) **Refresh token simply too short or rotation disabled** — bump the RT lifetime / sliding window and confirm rotation is configured. (d) **Clock skew** causing the client to think the token is already expired and over-refreshing.

The structural fix that prevents most of these: a **BFF pattern** where the browser holds only a same-site session cookie and the backend manages the OAuth tokens and renewal — it sidesteps third-party cookie blocking entirely and removes token-juggling from JavaScript.

#### Q39. [Theory] What is token exchange (RFC 8693) and the delegation/impersonation problem it solves?

Token exchange addresses a real architectural need: **a service that received a token needs a *different* token to call a downstream service on the original user's behalf.** Service A holds a token scoped for itself; to call Service B it shouldn't just forward A's token (wrong audience, wrong scopes, over-privileged), nor should it use raw client credentials (it would lose the user's identity and act as itself). RFC 8693 defines a standard `grant_type=urn:ietf:params:oauth:grant-type:token-exchange` to swap one token for another at the AS.

```bash
POST /token
  grant_type=urn:ietf:params:oauth:grant-type:token-exchange
  subject_token=<the user's token A presented>      # who we're acting for
  subject_token_type=urn:ietf:params:oauth:token-type:access_token
  audience=https://api.b.example.com                # new audience (Service B)
  scope=b.read                                       # narrowed scopes
  # optionally actor_token=<service A's own token>   # who is acting
-> { access_token: <token for B, sub=user, narrowed scopes, aud=B> }
```

The spec distinguishes two semantics. **Delegation** keeps both identities visible: the new token says "the user is the subject, but Service A is the actor" (via an `act` claim), so B knows a service is acting *on behalf of* the user and can audit the chain. **Impersonation** drops the actor — the new token looks exactly as if the user obtained it directly, with no trace that A was involved. Delegation is generally preferred because it preserves the audit trail and least-privilege ("A may act for the user, but only with these narrowed scopes for B").

This pattern is the clean answer to deep service-to-service call chains in microservices: instead of forwarding an over-broad user token through five hops (where any hop could misuse it), each boundary exchanges for a **down-scoped, correctly-audienced** token. It's also how identity propagation works in service meshes and how a public-facing API safely calls internal high-value services.

#### Q40. [Coding] Implement a thread-safe, cached JWKS key resolver that refreshes on an unknown `kid`.

**Problem:** Resource servers must fetch the IdP's public keys (JWKS) to verify JWT signatures, cache them for performance, but transparently refresh when a token arrives signed by a newly-rotated key (unknown `kid`). Implement the cache + refresh-on-miss logic.

```java
import java.security.PublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JwksKeyResolver {

    private final JwksFetcher fetcher;                  // does the HTTP GET + JWK->PublicKey parse
    private final Duration minRefreshInterval;          // rate-limit refreshes (e.g. 5 min)
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile Map<String, PublicKey> keysByKid = Map.of();
    private volatile Instant lastRefresh = Instant.EPOCH;

    public JwksKeyResolver(JwksFetcher fetcher, Duration minRefreshInterval) {
        this.fetcher = fetcher;
        this.minRefreshInterval = minRefreshInterval;
    }

    /** Resolve the public key for a token's kid; refresh once if it's unknown. */
    public PublicKey resolve(String kid) {
        PublicKey key = read(kid);
        if (key != null) return key;

        // Cache miss: a key may have been rotated in. Refresh — but rate-limited
        // so an attacker spamming bogus 'kid's can't make us hammer the JWKS endpoint.
        refreshIfAllowed();

        key = read(kid);
        if (key == null) throw new SecurityException("unknown kid: " + kid);
        return key;
    }

    private PublicKey read(String kid) {
        lock.readLock().lock();
        try { return keysByKid.get(kid); }
        finally { lock.readLock().unlock(); }
    }

    private void refreshIfAllowed() {
        lock.writeLock().lock();
        try {
            if (Instant.now().isBefore(lastRefresh.plus(minRefreshInterval))) return; // throttle
            Map<String, PublicKey> fresh = fetcher.fetch();    // GET jwks_uri, parse keys
            this.keysByKid = Map.copyOf(fresh);
            this.lastRefresh = Instant.now();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public interface JwksFetcher { Map<String, PublicKey> fetch(); }
}
```

**Time/Space complexity:** `resolve` is O(1) on a cache hit (concurrent map read under a read lock). A miss triggers at most one network fetch per `minRefreshInterval`, parsing K keys in O(K); space is O(K) for the cached key set. **Edge cases:** the **rate-limit on refresh is a security control**, not just an optimization — without it an attacker sends tokens with random `kid`s to force unbounded JWKS fetches (a DoS amplification against both your server and the IdP). During **key rotation overlap** the IdP publishes both old and new keys, so a refresh picks up the successor seamlessly. Use a read/write lock (or an immutable map swapped via `volatile`) so verification under load never blocks on a refresh. In production prefer a battle-tested source (Nimbus `JWKSourceBuilder`, Spring's `NimbusJwtDecoder`) which implements exactly this with retry and outage caching — this code shows the logic those libraries encapsulate.

#### Q41. [Practical] How do you handle multi-tenancy in OAuth/OIDC — one issuer per tenant vs. a shared issuer with a tenant claim?

There are two dominant models, and the choice shapes your isolation guarantees and operational overhead. **Issuer-per-tenant** gives each tenant its own realm/tenant on the IdP (Keycloak realms, Auth0 organizations/tenants, Entra ID tenants), each with a distinct `issuer`, distinct signing keys, and distinct discovery document. **Shared issuer with a tenant claim** uses one issuer for everyone and stamps a `tenant_id`/`org_id` claim into each token, with the application enforcing tenant boundaries.

```
Issuer-per-tenant:                      Shared issuer + claim:
  iss = idp/realms/acme                   iss = idp/realms/app   (everyone)
  iss = idp/realms/globex                 token claim: tenant_id = "acme"
  -> separate keys, separate config       -> one config, app enforces tenant
```

| Dimension | Issuer-per-tenant | Shared issuer + claim |
|-----------|-------------------|------------------------|
| Isolation | strong (keys, blast radius per tenant) | logical only (app must enforce) |
| Key compromise | affects one tenant | affects all tenants |
| Resource-server config | must validate dynamically per `iss` | one fixed issuer |
| Scale (10k tenants) | heavy (10k realms, JWKS, discovery) | light |
| Custom branding/policy per tenant | easy | harder |

Issuer-per-tenant is the stronger isolation story (a leaked signing key or misconfiguration is contained to one tenant) and is required when tenants demand data residency or distinct auth policies — but it does not scale to tens of thousands of tenants, where managing that many realms, JWKS endpoints, and discovery documents becomes an operational nightmare, and the resource server must resolve the issuer **dynamically** (an `JwtIssuerAuthenticationManagerResolver` keyed by `iss`, validating against an allow-list to prevent a forged-issuer SSRF). The shared-issuer model scales effortlessly but pushes the entire isolation burden into the application: **every query must be scoped by the `tenant_id` claim**, and a single missing `WHERE tenant_id = ?` is a cross-tenant data leak. Most large multi-tenant SaaS land on shared-issuer for B2C/long-tail tenants and reserve dedicated issuers for enterprise customers who pay for hard isolation — a hybrid that matches isolation cost to tenant value.

#### Q54. [Theory] What is the `/userinfo` endpoint, and when should you use it instead of (or alongside) the ID token claims?

The `/userinfo` endpoint is an OIDC-defined, OAuth-protected API that returns claims about the **currently authenticated user**, called with the access token: `GET /userinfo` with `Authorization: Bearer <access_token>`. It returns the same kind of identity claims an ID token carries (`sub`, `email`, `name`, etc.), gated by the granted scopes (`profile`, `email`, `address`, `phone`).

The reason it exists alongside the ID token is a separation of *delivery time* and *freshness*. The **ID token** is a snapshot delivered *at login*, embedded and signed, meant to be consumed by the client to establish a session — it's authentication evidence frozen at that moment. **`/userinfo`** is a *live query* you can make any time the access token is valid, returning current claims — useful when profile data may have changed since login, or when you deliberately keep the ID token lean (small) and fetch fuller profile data separately.

```
ID token:    issued once at login, signed, embedded   -> "who logged in, verified, at time T"
/userinfo:   queried with access token, any time       -> "current claims for this user, now"
```

When to use which: take the **`sub` (and core identity) from the verified ID token** to establish the session — that's the authoritative, audience-bound, signed assertion of *who logged in*. Use **`/userinfo`** to enrich or refresh profile attributes, especially if you minimized the ID token. A correctness rule: the `sub` returned by `/userinfo` **must match** the `sub` in the ID token (the spec requires this check) — otherwise an attacker who substituted an access token could splice another user's profile onto your session. And remember `/userinfo` consumes the *access* token and incurs a network call, so it's not free — many apps fetch it once post-login and cache, rather than per request.

### 🟠 Advanced — extended

#### Q55. [Practical] How do you load-test and capacity-plan an authorization server and JWKS/introspection path?

The AS and its validation paths have sharply different scaling profiles, so capacity planning starts by identifying which calls are **per-login** (relatively rare, expensive) versus **per-request** (potentially enormous volume). Token *issuance* (`/authorize` + `/token`) happens at human login rates and involves signing (a CPU cost); **introspection** (`/introspect`) happens *per API request* if used naively and can dwarf everything else; **JWKS** (`/jwks`) is read per validation only if uncached — which is exactly why caching it matters.

```bash
# Model the load by endpoint, not as one aggregate:
#   /authorize, /token   ~ logins/sec  (e.g. 100k logins/hr -> ~28/s, spiky at 9am)
#   /introspect          ~ API req/sec IF opaque tokens validated per-request  (the killer)
#   /jwks                ~ near-zero if RS caches; a burst on key rotation

# Load test the real bottleneck — issuance signing throughput and introspection:
oha -z 60s -c 200 -m POST \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=svc&client_secret=..." \
  https://idp.example.com/token
```

The dominant capacity decision is **avoiding per-request load on the AS**. With **JWTs validated locally** against a cached JWKS, the AS sees traffic only at issuance — it scales with logins, not with API calls, so a modest AS handles a huge API tier. With **opaque tokens + per-request introspection**, the AS is now on the hot path of *every* API call and must scale with total request volume — orders of magnitude more, and a single point of failure. The phantom-token/gateway-caching pattern (Q19, Q30) exists precisely to cap this: introspect once at the edge, cache for the token TTL, validate JWTs locally downstream.

Capacity-planning checklist: measure **signing throughput** (RSA is slower than EC — consider ES256 for high issuance rates), confirm **JWKS is cached** at every resource server (an uncached JWKS fetch per validation silently turns "local validation" back into a network dependency and can stampede the AS on cache expiry), **load-test the login spike** (auth traffic is bursty — Monday 9am, post-deploy re-auth storms), size the **introspection cache hit-rate** if you use opaque tokens, and ensure **JWKS/discovery are highly available and CDN-frontable** (read-only public data). The failure you're planning against: a thundering herd where short token TTLs cause synchronized refreshes, or a JWKS cache expiry, stampedes the AS — mitigate with jittered TTLs, request coalescing, and serving stale JWKS during an outage.

#### Q56. [Theory] Explain the security model of consent and the "confused deputy" risk in third-party OAuth ecosystems (e.g. open platforms).

In an open platform where many third-party apps can register as OAuth clients (think a public API marketplace), **consent is the primary security boundary between users and apps** — and it's also the most-abused one. The model: the user grants a specific client specific scopes; the AS records this grant; the resource server enforces it. The trust assumption is that the consent screen accurately and intelligibly conveys *what* the user is authorizing *whom* to do — and that's where real ecosystems repeatedly fail.

The **confused-deputy** risk here is the platform itself (the AS/resource server) being tricked into using its authority on behalf of a malicious app in ways the user didn't truly intend. Concrete attack patterns from real platforms: (1) **scope over-reach disguised by vague consent** — an app requests broad scopes (`read_all_emails`) while presenting itself as needing something narrow; users click through; (2) **redirect/open-redirect abuse** to capture codes (Q32) — a confused-deputy where the AS issues a code that flows to the attacker; (3) **app impersonation** — a malicious app named "Google Docs" tricks users into consenting (the OAuth phishing that hit Google in 2017, where a fake app used a legitimate-looking name to obtain Gmail scopes en masse); (4) **token/grant persistence** — consent, once granted, persists, so a malicious app retains access long after the user forgot about it.

```
Defenses an open OAuth platform must enforce:
  - App verification / publisher review before sensitive scopes are allowed
  - Granular, plain-language consent showing the EXACT app + EXACT scopes
  - Exact redirect_uri matching + per-app allow-lists (no wildcards)
  - Scope tiering: high-risk scopes need extra review / step-up / restricted use
  - Visible grant management: users can see and revoke every app's access
  - Anomaly detection on consent grants (mass-consent campaigns = phishing)
  - Rate-limited / reviewed app registration to stop name-squatting impersonation
```

The deeper lesson is that **OAuth's consent model assumes an informed, attentive user, which is empirically false at scale** — users habituate to consent prompts and click through. So mature platforms shift trust *off* the consent click: they verify publishers, gate sensitive scopes behind manual review, enforce exact redirect matching, surface and make-revocable all grants, and run anomaly detection on consent patterns to catch mass-phishing. The 2017 Google Docs worm and subsequent platform hardening (app verification programs, restricted scopes requiring security assessment) are the canonical case study: the protocol was working as designed, but the *consent UX* was the exploitable layer, and the fix was operational and policy controls around it, not a protocol change.

#### Q42. [Theory] Explain the JWT `alg` confusion / key-substitution attack family in detail and the full set of defenses.

The `alg` header field in a JWT is attacker-controllable, and naive verification libraries historically *trusted it to choose the verification algorithm* — which is the root of an entire attack family. Two classic variants:

**(1) `alg: none` bypass.** The JWT spec defines an "unsecured" `none` algorithm with an empty signature. If a verifier reads `alg: none` and concludes "no signature to check," an attacker forges any payload with no signature at all and is trusted. The fix is to **reject `none` outright** and require a specific expected algorithm.

**(2) RS256 → HS256 key confusion.** The server expects asymmetric RS256 (verify with the RSA *public* key). The attacker changes the header to `alg: HS256` (symmetric) and signs the forged token using the **RSA public key bytes as the HMAC secret**. A vulnerable library, seeing `HS256`, calls `HMAC-verify(token, <the configured key>)` — and since the configured key *is* the public key (which is, by definition, public), the HMAC verifies. The attacker forged a valid token using only public information.

```
Server config:   verify with RSA PUBLIC key,  expects RS256
Attacker sends:  header { "alg": "HS256" }   payload { admin: true }
                 signature = HMAC_SHA256( publicKeyBytes, header.payload )
Vulnerable lib:  "alg says HS256" -> HMAC-verify with publicKey -> VALID  (!!)
```

Defenses, layered: (a) **pin the algorithm explicitly** in the verifier configuration and reject anything else — do not let the token's `alg` header select the algorithm (Spring's `NimbusJwtDecoder` and Nimbus's `JWSVerificationKeySelector` take the expected algorithm as a parameter, exactly as shown in Q14); (b) **never share key material between symmetric and asymmetric contexts** — a public key should only ever be usable for asymmetric verification; (c) **validate the full claim set** (`iss`, `aud`, `exp`, `nbf`); (d) keep libraries patched, since this class of bug has been found and fixed in many JWT libraries over the years. The meta-lesson: treat every byte of an untrusted token, including its headers, as adversarial input, and make the *server's* policy — not the token's self-description — decide how to verify.

#### Q43. [Practical] Plan a zero-downtime migration of signing-key rotation and an issuer/IdP change across many resource servers.

Rotation and issuer changes are dangerous because tokens minted *before* the change are still in flight (valid until `exp`) while resource servers may already expect the *new* state — a naive cutover invalidates live sessions. The principle for both is **overlap, never flip**.

**Signing-key rotation (routine, same issuer):** the safety net is the `kid` header plus a multi-key JWKS. The IdP publishes the new public key to the JWKS *before* it starts signing with it; resource servers (which cache JWKS and refresh on unknown `kid`, per Q40) pick it up automatically. Old tokens still verify against the old key, which remains in the JWKS until every token signed with it has expired.

```
Phase 1: JWKS = { kid_old }                       sign with kid_old
Phase 2: JWKS = { kid_old, kid_new }  (publish)   still sign with kid_old   <- wait for propagation
Phase 3: JWKS = { kid_old, kid_new }              switch signing to kid_new
Phase 4: JWKS = { kid_new }            (retire old, after old TTL fully elapsed)
```

**Issuer / IdP migration (e.g. old IdP -> new IdP):** harder, because `iss` is pinned. Make resource servers **multi-issuer aware** first — configure them (via a `JwtIssuerAuthenticationManagerResolver` / multi-issuer validator) to accept tokens from *both* the old and new issuer simultaneously, each validated against its own JWKS and audience. Only after every resource server trusts both do you switch clients to authenticate against the new IdP. Once the old IdP's longest-lived tokens have expired and traffic to it is zero, remove it from the accepted list.

Operationally: stage it (dev → staging → prod), **monitor 401 rates and the distribution of `iss`/`kid`** during each phase to confirm propagation before advancing, and always wait at least one max-token-lifetime between "start signing with new" and "retire old." Have a rollback: because both states are accepted during overlap, reverting the *signing* choice is instant and non-breaking. The whole strategy rests on the fact that OAuth was designed for this — `kid`-based JWKS and per-issuer validation exist precisely to make rotation a non-event.

#### Q44. [Theory] Compare front-channel, back-channel, and RP-initiated logout in OIDC. Why is single logout so hard?

Logout in OIDC is genuinely hard because the architecture spreads session state across many independent parties — the IdP, each Relying Party (RP/client), and (for stateless APIs) tokens that can't be recalled. There is no single "log out everywhere" switch; instead there are three mechanisms with different reliability.

**RP-Initiated Logout** is the user-facing trigger: the RP redirects the browser to the IdP's `end_session_endpoint` (with `id_token_hint` and `post_logout_redirect_uri`), the IdP clears its own SSO session cookie and redirects back. This logs the user out *of the IdP*, but does nothing about the *other* RPs that still have live local sessions.

**Front-channel logout** propagates to other RPs through the browser: the IdP's logout page loads a hidden `<iframe>` pointing at each RP's front-channel logout URI, and each RP clears its session when its iframe loads. It's simple but **fragile** — modern browsers block third-party cookies (Safari ITP, Chrome), so the iframe request often arrives *without* the RP's session cookie and can't identify which session to kill. One slow or failing RP also breaks the chain silently.

**Back-channel logout** is the robust mechanism: the IdP sends a server-to-server POST of a signed **logout token** (a JWT with `sub`/`sid` and a special `events` claim) directly to each RP's back-channel endpoint. No browser, no cookies, no iframes — so it survives cookie blocking. The cost is that each RP must run a server endpoint and maintain a **session registry keyed by `sid`** so it can find and destroy the right session when the logout token arrives.

```
RP-initiated:   browser -> IdP end_session  (kills IdP SSO only)
Front-channel:  IdP page -> hidden iframes -> each RP   (breaks under 3p-cookie blocking)
Back-channel:   IdP -> signed logout_token POST -> each RP server  (robust, needs sid registry)
```

And even with all three working, **stateless JWT access tokens cannot be force-killed mid-flight** — they remain valid until `exp` regardless of logout. So a complete logout story is: RP-initiated to clear the IdP session, back-channel to clear server-side RP sessions, **revoke the refresh tokens** so no new access tokens can be minted, and rely on **short access-token TTLs** (plus optionally a gateway deny-list) to bound the window where an already-issued access token still works. The hardness is fundamental: you're trying to impose a synchronous "stop" on an intentionally decentralized, partly-stateless system.

#### Q45. [Coding] Implement an OAuth callback handler that validates `state`, the `iss` response param, and exchanges the code.

**Problem:** Implement the security-critical callback (redirect_uri) handler for an Authorization Code + PKCE client: verify `state` (CSRF), verify the `iss` response parameter (mix-up defense, RFC 9207), then exchange the code with the stored `code_verifier`.

```java
public class CallbackHandler {

    private final AuthClientConfig cfg;     // tokenEndpoint, clientId, redirectUri, expectedIssuer
    private final HttpTokenClient http;     // does the POST /token
    private final AuthSessionStore sessions;// per-user: state, codeVerifier, nonce

    /** Handle GET {redirect_uri}?code=...&state=...&iss=... */
    public TokenResponse handleCallback(HttpRequest req, String sessionId) {
        AuthSession s = sessions.get(sessionId);
        if (s == null) throw new SecurityException("no auth session");

        String error = req.param("error");
        if (error != null) throw new SecurityException("authorize error: " + error);

        // 1. CSRF: state must match what we generated and stored, then is single-use.
        String state = req.param("state");
        if (state == null || !constantTimeEquals(state, s.state()))
            throw new SecurityException("state mismatch — possible CSRF");

        // 2. Mix-up defense (RFC 9207): if the AS returned iss, it MUST match the AS we started with.
        String iss = req.param("iss");
        if (iss != null && !iss.equals(cfg.expectedIssuer()))
            throw new SecurityException("iss mismatch — possible mix-up attack");

        // 3. Exchange code for tokens, proving PKCE possession with the stored verifier.
        String code = req.param("code");
        if (code == null) throw new SecurityException("missing code");
        TokenResponse tokens = http.exchange(Map.of(
            "grant_type",    "authorization_code",
            "code",          code,
            "redirect_uri",  cfg.redirectUri(),     // must match the one sent to /authorize
            "client_id",     cfg.clientId(),
            "code_verifier", s.codeVerifier()));     // PKCE: never recompute, use the stored one

        // 4. Invalidate single-use auth session so state/verifier can't be replayed.
        sessions.remove(sessionId);

        // 5. (OIDC) validate id_token signature + nonce == s.nonce()  [see Q14]
        return tokens;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
```

**Time/Space complexity:** O(1) work plus one network round trip for the token exchange; O(1) extra space. **Edge cases:** `state` must be **single-use** — remove the session after handling so a captured callback URL can't be replayed; use a **constant-time** comparison for `state` to avoid timing leaks. The `iss` check is conditional ("if returned") for backward compatibility, but in OAuth 2.1 you should require it when talking to multiple ASs. The `redirect_uri` sent to `/token` must be byte-identical to the one sent to `/authorize` or the AS rejects the exchange. And the `code_verifier` must be the **stored** value from session — recomputing it would defeat PKCE. Finally, always handle the `error`/`error_description` callback path; a user who denies consent lands here, not just success.

#### Q46. [Practical] Production incident: 401s spike across all services after an IdP deploy. How do you triage?

A correlated 401 spike immediately after an IdP change points at something **global to token validation** — keys, issuer, clock, or discovery — rather than any one service. The triage strategy is to bisect by "what does every resource server share," using the failing tokens themselves as evidence.

```bash
# 1. Grab a freshly-issued, currently-failing token and inspect it (decode, don't trust):
#    - kid in header: does it exist in the IdP's CURRENT jwks_uri?
curl -s https://idp.example.com/.well-known/openid-configuration | jq .jwks_uri
curl -s "$(...)jwks_uri" | jq '.keys[].kid'      # compare against the token's kid
#    - iss claim: does it match what resource servers pin?  (a trailing-slash change is classic)
#    - exp/iat/nbf: is the IdP's clock skewed vs. the resource servers'?
```

The usual culprits, in rough order of likelihood after an IdP deploy: **(1) signing-key rotated without overlap** — the IdP started signing with a `kid` not yet in (or no longer in) the JWKS, so every signature fails to resolve a key; **(2) issuer string changed** — a config or version bump altered `iss` (e.g. added/removed a trailing slash, or switched v1→v2 endpoint as Entra ID does), so the pinned-issuer check rejects everything; **(3) JWKS endpoint URL or cache poisoning** — discovery now points at a different `jwks_uri`, or resource servers are serving a stale cached JWKS and the rotation outran their refresh; **(4) clock skew** — the IdP host's time jumped, making fresh tokens look not-yet-valid (`nbf`) or already-expired.

Mitigation and confirmation: the token decode usually identifies it in minutes — if the token's `kid` isn't in the live JWKS, it's a rotation/overlap failure; if `iss` differs from the pinned value, it's an issuer change. **Fastest rollback** is to revert the IdP deploy (restoring the prior keys/issuer), since resource servers were working against the old state. If rollback isn't possible, force resource servers to refresh JWKS, and as a stopgap widen the accepted issuer/clock-skew leeway. The durable fix is process: enforce **key-rotation overlap** and **multi-issuer acceptance during cutover** (Q43), and add monitoring that alerts when the IdP signs with a `kid` absent from the published JWKS — that one check catches the most common cause before users do.

#### Q47. [Theory] What are PAR and JAR, and what concrete problems do they solve over plain front-channel authorization requests?

A plain authorization request stuffs all its parameters into the **front-channel URL** — the browser-visible `/authorize?client_id=...&scope=...&redirect_uri=...&authorization_details=...`. That has three weaknesses: the parameters are **tamperable** (a malicious browser extension or MITM on a misconfigured proxy can alter `scope` or `redirect_uri` before the user consents), they are **not integrity-protected or authenticated** (the AS can't be sure the request truly came from the registered client), and they hit **URL length limits** once requests get large (rich `authorization_details`, multiple resources).

**JAR (JWT-Secured Authorization Request, RFC 9101)** fixes tampering and authenticity by wrapping the entire request in a **signed JWT** (the `request` object): the client signs all parameters with its key, so the AS can verify integrity and origin, and nothing can be silently altered in transit. **PAR (Pushed Authorization Requests, RFC 9126)** fixes the channel itself: the client **POSTs the request directly to the AS back-channel first** (authenticated as a confidential client), the AS stores it and returns a short-lived `request_uri` handle, and the browser request becomes just `/authorize?client_id=...&request_uri=...`.

```
Plain:  browser -> /authorize?client_id&scope&redirect_uri&...   (long, tamperable, unauthenticated)

PAR:    client  -> POST /par (authenticated)  { full request }  -> { request_uri, expires_in }
        browser -> /authorize?client_id=...&request_uri=urn:...  (tiny, opaque, integrity-protected)
```

PAR is the stronger and more practical of the two because the sensitive request never traverses the front channel at all — it can't be inspected or tampered with in the browser, it sidesteps URL-length limits entirely, and because the push is **client-authenticated**, the AS gains assurance the request is genuine *before* the user ever sees a consent screen (which also lets it reject bogus requests early). The two compose: PAR can carry a JAR-signed request object for end-to-end integrity. This is why **FAPI 2.0 mandates PAR** for high-assurance flows (Open Banking, payments) — combined with sender-constrained tokens and RAR (Q27), it closes the front-channel as an attack surface.

#### Q57. [Coding] Implement a token cache with proactive refresh and single-flight (no thundering herd) for a client SDK.

**Problem:** A client SDK calls an API repeatedly and needs a valid access token. Implement an in-memory token cache that (a) refreshes *proactively* before expiry, and (b) ensures that when many concurrent callers find the token expired, only **one** refresh happens (single-flight), not a stampede against the AS.

```java
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class TokenCache {

    private record Cached(String token, Instant expiresAt) {}

    private final Supplier<TokenResponse> refresher;  // performs the /token call
    private final Duration skew;                       // refresh this early (e.g. 30s)
    private final AtomicReference<Cached> current = new AtomicReference<>();
    private volatile CompletableFuture<Cached> inflight; // single-flight guard

    public TokenCache(Supplier<TokenResponse> refresher, Duration skew) {
        this.refresher = refresher; this.skew = skew;
    }

    public String getToken() {
        Cached c = current.get();
        if (c != null && Instant.now().isBefore(c.expiresAt().minus(skew)))
            return c.token();                          // fast path: still fresh
        return refreshSingleFlight().token();
    }

    /** Only ONE thread performs the refresh; others await the same future. */
    private Cached refreshSingleFlight() {
        CompletableFuture<Cached> f = inflight;
        if (f != null && !f.isDone()) return f.join(); // join the in-progress refresh

        synchronized (this) {
            // Re-check inside the lock (another thread may have just refreshed).
            Cached c = current.get();
            if (c != null && Instant.now().isBefore(c.expiresAt().minus(skew))) return c;
            if (inflight != null && !inflight.isDone()) return inflight.join();

            CompletableFuture<Cached> nf = new CompletableFuture<>();
            inflight = nf;
            try {
                TokenResponse r = refresher.get();     // the single network call
                Cached fresh = new Cached(r.accessToken(),
                        Instant.now().plusSeconds(r.expiresIn()));
                current.set(fresh);
                nf.complete(fresh);
                return fresh;
            } catch (RuntimeException e) {
                nf.completeExceptionally(e);
                throw e;
            } finally {
                inflight = null;
            }
        }
    }
}
```

**Time/Space complexity:** the fast path is O(1) lock-free (atomic read). Under expiry, N concurrent callers collapse to **one** network refresh — O(1) AS calls instead of O(N); space is O(1) (a single cached entry + one in-flight future). **Edge cases:** the **skew** (refresh ~30–60s before `exp`) avoids the race where a token passes mid-flight on the wire and the API rejects it. The **single-flight** pattern is the whole point — without it, every short-TTL expiry triggers a stampede where hundreds of threads simultaneously hit `/token`, hammering the AS (the same thundering-herd risk as JWKS in Q55); coalescing them into one refresh is essential at scale. **Refresh failure** must propagate to all waiters (don't leave them hanging) and the in-flight reference must be cleared in `finally` so a transient failure doesn't permanently wedge the cache. In a multi-process deployment, add **jitter** to `skew` across instances so they don't all refresh at the same wall-clock instant, and for refresh-token flows coordinate rotation carefully (Q21) so concurrent refreshes don't trip reuse detection.

### 🔴 Expert — extended

#### Q58. [Practical] You must support an offline-capable mobile app that occasionally syncs. How do you design token lifetime, refresh, and revocation?

An offline-first mobile app inverts the usual short-TTL assumption: the app may be disconnected for hours or days, then sync — so it needs credentials that *survive offline periods* yet remain revocable, on a device you don't fully control. The design balances **usability offline** against **the device being a theft/loss risk**.

The core structure is a **long-lived, rotating refresh token** that the app uses to mint short-lived access tokens whenever it regains connectivity, requested with the `offline_access` scope (which signals the IdP to issue a refresh token for use without the user present). Access tokens stay short (minutes); the refresh token carries the durable grant. Crucially the refresh token must be **rotated with reuse detection** (Q12/Q21) — but with a **grace window**, because a flaky mobile network is the textbook cause of "refresh succeeded server-side but the response was lost," which naive reuse detection would misread as theft and log the user out.

```
Offline period -----------------------------> reconnect/sync
  app holds: refresh_token (rotating, in secure storage)
             + last access_token (likely expired)
  on reconnect:  POST /token grant_type=refresh_token -> new access + new refresh (rotated)
                 retry-safe: grace window tolerates a lost-response retry
Storage:  refresh_token in Keychain / Keystore (hardware-backed), NEVER plaintext/localStorage-equiv
Binding:  DPoP/mTLS with a key in Secure Enclave -> stolen token unusable off-device
```

Key decisions and trade-offs: (1) **Secure storage** — the refresh token lives in the platform secure store (iOS Keychain / Android Keystore), ideally backed by hardware and gated by device unlock/biometrics, never in app-readable plaintext. (2) **Sender-constrain it** — bind tokens to a **Secure-Enclave/TEE key via DPoP** so a token extracted from a stolen device is useless without the hardware key; this is the strongest mitigation for the "lost device" threat. (3) **Sliding vs. absolute lifetime** — use a *sliding* refresh-token window (extends on use) for active users but cap it with an *absolute* maximum (force full re-auth every, say, 30–90 days) so a dormant grant can't live forever. (4) **Revocation despite offline** — you cannot reach a disconnected device, so revocation is enforced at *reconnect*: revoke the refresh-token family server-side (lost-device flow, password change), and the next sync's refresh attempt fails, forcing re-auth; combine with a server-side **deny-list / CAEP signal** (Q52) so any access token also dies on next validation. (5) **Graceful expiry UX** — detect refresh failure and queue local writes so the user can keep working offline and re-auth on reconnect without data loss.

The honest limitation: while offline, a token already on the device *will* keep working until it expires — you fundamentally cannot revoke against a disconnected device. So the design *bounds* that exposure (short access TTLs, sender-constraining so a copied token is inert, hardware-gated storage, absolute refresh caps) rather than eliminating it, and concentrates real-time revocation at the reconnect/sync boundary. This is the standard shape for offline mobile (sync apps, field-service, point-of-sale): durable rotating refresh token in hardware-backed storage, DPoP binding, sliding-with-cap lifetimes, and revoke-on-reconnect.

#### Q59. [Theory] Critically assess passkeys / WebAuthn and FIDO2 in relation to OAuth/OIDC — do they replace it, and how do they fit together?

There's a common misconception that passkeys "replace OAuth" — they don't, because they operate at a **different layer**. WebAuthn/FIDO2/passkeys are an **authentication mechanism** (how a user proves identity to an IdP, using public-key cryptography and a hardware/platform authenticator instead of a password). OAuth/OIDC is a **token and federation framework** (how, once authenticated, tokens are issued and delegated to apps and APIs). Passkeys answer "how does the user log in to the IdP"; OIDC answers "how does that login become a portable, scoped credential my app and APIs can consume." They're complementary, and the modern stack uses both: **passkey as the first factor at the IdP, OIDC to federate that login out to relying parties.**

```
   User --passkey/WebAuthn--> [ IdP / Authorization Server ] --OIDC ID token--> Relying Party app
        (phishing-resistant                                  (federated, scoped,
         public-key auth, no                                  audience-bound tokens)
         shared secret)                  --OAuth access token--> APIs
```

What passkeys *do* materially improve is the part of the threat model OAuth never addressed: **the credential the user presents to the IdP.** Passwords are phishable, reusable, breachable; WebAuthn is **phishing-resistant by construction** — the authenticator signs a challenge bound to the *origin*, so a fake login page on a look-alike domain simply can't produce a valid assertion, and there's no shared secret to steal from the IdP's database. This closes the single biggest real-world attack vector (credential phishing) at the front door of the IdP. It also pairs naturally with sender-constrained tokens (Q25): a passkey's private key lives in a TPM/Secure Enclave and never leaves, the same hardware that can hold a DPoP key — so the *authentication* key and the *token-binding* key both become hardware-bound and unexfiltratable.

My critical take: passkeys are the most significant *authentication* upgrade in years and should be the default first factor, but they don't change the *authorization/federation* problem OAuth/OIDC solves — you still need scoped, revocable, audience-bound tokens to call APIs across services, and you still need all the OAuth machinery (PKCE, rotation, audience validation, sender-constraining) downstream of the login. The real synthesis a senior engineer should articulate: **WebAuthn/passkeys harden the user→IdP step (phishing-resistant, hardware-bound, passwordless), OIDC federates that strong authentication to apps, and OAuth delegates scoped access to APIs** — and binding the token's proof-of-possession key into the same hardware that holds the passkey gives you an end-to-end, theft-resistant chain. The limitations to acknowledge: passkey *recovery/account-recovery* is the new weak link (a lost device flow that falls back to email/SMS reintroduces phishability), and cross-ecosystem passkey portability/sync trust is still maturing — so passkeys raise the floor dramatically but don't make the rest of the OAuth security program optional.

#### Q60. [Behavioral] Tell me about a time you led a migration or remediation of an authentication/authorization system under real constraints.

**(Situation/Task)** We inherited a platform where a dozen services each validated tokens differently — some only checked the signature, several skipped `aud`, one still accepted the deprecated implicit-flow tokens, and a couple shared a single static M2M `client_secret` that had been in a wiki page for years. A security audit flagged it as high risk ahead of a compliance deadline, and I was asked to lead the remediation without breaking live traffic for paying customers.

**(Action)** I refused to do a big-bang cutover — the blast radius was too large. I started by **making the risk legible**: I built a small dashboard scraping each service's validation behavior and the IdP's token logs, which turned "we think it's bad" into a concrete inventory (which services skipped `aud`, which `iss` values were in use, which clients shared the secret). Then I sequenced the work by *reversibility and dependency*: first, introduce a shared, audited resource-server library so every service validated signature + `iss` + `aud` + algorithm identically (eliminating the bespoke validators); second, migrate M2M from the shared secret to **per-client `private_key_jwt`** using an overlap window (provision new credentials, accept both, retire the old only after telemetry showed zero use — Q49); third, kill the implicit-flow path by moving the SPA to **Authorization Code + PKCE with a BFF**. Every step was dual-state (old and new accepted simultaneously), staged dev→staging→prod, and gated on a monitoring signal — I watched 401 rates and the `iss`/`kid`/`aud` distributions before advancing each phase (Q43, Q46). I also pair-programmed the first integration of the shared library so the secure path became the easy, copy-paste default rather than a chore.

**(Result)** We closed every audit finding before the deadline with **zero customer-facing auth outages**. The shared library meant `aud` validation and algorithm pinning became automatic for new services, so the class of bug couldn't recur. When we retired the shared secret, the telemetry showed one forgotten cron job still using it — which the overlap window caught gracefully instead of taking down a nightly billing run, vindicating the no-big-bang approach.

**(Reflection)** Three lessons I carry: (1) **make risk measurable before you make it a project** — the inventory dashboard did more to get buy-in than any threat write-up; (2) **always migrate with overlap and a rollback** — dual-state acceptance plus monitoring turns a scary auth change into a non-event, and it caught the one thing we didn't know about; and (3) **make the secure path the default path** — shipping a shared library and pairing on the first integration is how a remediation *stays* remediated instead of decaying the moment the audit is over. The hardest part wasn't the protocol; it was the change management around a system you can't take offline.

#### Q48. [Theory] Critically evaluate the "BFF (backend-for-frontend) for SPAs" pattern vs. tokens-in-the-browser. When is each justified?

The browser is a hostile environment for tokens: any XSS gives an attacker access to whatever JavaScript can reach. The two competing models for SPA auth differ in **whether OAuth tokens ever live in the browser at all**.

**Tokens-in-the-browser** (Authorization Code + PKCE, tokens held in JS memory, rotating refresh tokens): the SPA is a public OAuth client, obtains tokens directly, and calls APIs with them. Modern and standards-blessed, but the residual XSS risk is real — even in-memory tokens are reachable by injected script, and a rotating refresh token in memory can be stolen in the window before rotation. Mitigations (CSP, no token in `localStorage`, short TTLs, RT rotation with reuse detection) shrink but don't eliminate the exposure.

**BFF** flips the model: a lightweight server-side component is the *confidential* OAuth client. It performs the code exchange, **holds the access and refresh tokens server-side**, and gives the browser only a **same-site, HttpOnly, Secure session cookie**. The SPA calls *its own* BFF (same origin), and the BFF attaches the real token when proxying to downstream APIs.

```
Tokens-in-browser:  SPA --(holds tokens in JS memory)--> APIs        XSS reaches tokens
BFF:                SPA --(HttpOnly cookie, same origin)--> BFF --(holds tokens)--> APIs
                                                            ^ XSS cannot read HttpOnly cookie or tokens
```

| | Tokens-in-browser | BFF |
|---|---|---|
| Token XSS exposure | yes (JS memory) | no (server-side + HttpOnly) |
| 3rd-party cookie issues | yes (silent renew iframes) | no (same-site cookie) |
| Infra cost | none (static hosting) | a stateful/serverful component + session store |
| CSRF surface | low (bearer in header) | reintroduces cookie CSRF — needs SameSite + CSRF token |
| Best for | low-risk, cost-sensitive, fully static apps | high-value (banking, health, admin) apps |

My critical take: BFF is the **stronger security posture** and is now the recommended default for sensitive applications precisely because it takes tokens out of XSS reach and dodges the third-party-cookie breakage that plagues silent renewal (Q38). But it isn't free — you reintroduce a server tier, a session store, and a cookie-based CSRF surface (mitigated with `SameSite=strict/lax` + a CSRF token), and you lose the "just host static files" simplicity. The honest decision rule: if a token leak means real-world harm (money, PII, admin access), pay for the BFF; for a low-stakes internal dashboard, well-hardened tokens-in-browser (PKCE + RT rotation + strict CSP) is a reasonable, cheaper choice. What's *not* defensible in 2026 is the old middle ground — refresh tokens in `localStorage` — which combines the costs of neither with the risks of both.

#### Q49. [Practical] Design a credential-rotation and secret-management strategy for thousands of M2M clients without downtime.

At thousands of M2M clients, the failure mode isn't a single rotation — it's that **manual, big-bang secret rotation guarantees outages** (some client always has a stale secret in a config you forgot), and shared static secrets are a standing breach risk. The strategy is to make rotation **continuous, automated, and overlapping**, and ideally to **eliminate long-lived shared secrets** entirely.

The first structural move is to prefer **non-shared credentials**: `private_key_jwt` (the AS only stores the client's *public* key — a breach of AS storage leaks nothing usable) or **mTLS** with certificates issued by an internal CA / SPIFFE-style workload identity. Where the platform supports it, **workload identity federation** (the cloud platform attests the workload's identity — IAM role, Kubernetes service-account token, SPIFFE SVID — and the AS trusts that) removes the secret altogether: the client proves *what it is*, not *what it knows*.

```
Anti-pattern:  one static client_secret per service, rotated by hand, no overlap
                 -> stale-config outages + standing leak risk

Target:        overlapping dual-credential rotation, automated, secret-manager driven
  Phase 1: client has secret_A (active)
  Phase 2: provision secret_B alongside A; AS accepts BOTH  <- overlap window
  Phase 3: clients pick up secret_B from the secret manager (rolling)
  Phase 4: deactivate secret_A once telemetry shows zero use of A
```

Operational pillars: (1) **Dual-credential overlap** — every client supports two active credentials at once (most IdPs allow N secrets/keys per client), so you provision the new one, let clients roll to it, then retire the old one only after telemetry confirms zero use of it; never invalidate the old credential before the new one is in place. (2) **A secret manager as source of truth** (Vault, AWS Secrets Manager, GCP Secret Manager) with **dynamic/short-lived secrets** and clients fetching at startup/refresh rather than baking secrets into images or config. (3) **Automation + telemetry** — rotation is a scheduled pipeline, and you instrument *per-credential last-used* so you can prove a credential is dead before killing it and detect a leaked one still in use. (4) **Tight scoping and per-client identity** — each workload is its own client with least-privilege scopes, so a compromise is contained and revocation is surgical (kill one client, not a shared secret used by hundreds).

The end-state large platforms converge on: **no human ever handles a secret**, credentials are short-lived and workload-attested (mTLS/SPIFFE/workload identity federation), rotation is a continuous background process with overlap windows, and the "leaked secret in a log" incident (Q24) becomes far less impactful because there's no durable shared secret to leak. The trade-off is significant upfront investment in PKI/secret-management infrastructure — justified at scale precisely because manual rotation doesn't survive thousands of clients.

#### Q50. [Theory] OAuth/OIDC vs. SAML vs. plain API keys vs. session cookies — when is each the right tool, and how do they interoperate?

These are often posed as competitors, but they occupy different niches; the senior skill is matching the mechanism to the constraint rather than defaulting to whatever's familiar.

| | What it is | Best for | Key weakness |
|---|---|---|---|
| **Session cookies** | server-side session, opaque cookie | single web app, same domain | doesn't span domains/APIs; CSRF surface |
| **API keys** | a static shared secret string | simple server-to-server, low-risk | no expiry/scoping/user context; leaks badly |
| **SAML** | XML-based federated SSO assertions | enterprise/B2B web SSO, legacy IdPs | XML complexity; browser-only, poor for APIs/mobile |
| **OAuth 2.0** | delegated authorization, tokens | API access on a user's behalf, M2M | not an auth protocol by itself |
| **OIDC** | authentication on top of OAuth | modern login/SSO, mobile, SPA, API era | needs correct token validation |

**Session cookies** are right when everything lives behind one web origin and you control both ends — they're simple and battle-tested, but they don't cross domains and don't help an API or mobile app. **API keys** are acceptable for low-risk, internal, server-to-server calls where simplicity wins, but they have no built-in expiry, scoping, rotation, or user identity, and they leak catastrophically (they're just strings) — for anything user-facing or high-value, Client Credentials with rotating secrets (or mTLS) is the upgrade. **SAML** still dominates **enterprise B2B web SSO** because that's where it has 20 years of IdP support; but it's XML-heavy, assertion-based, and designed for browser SSO — it's a poor fit for mobile apps, SPAs, and API authorization, which is exactly the gap **OIDC** filled. **OAuth** is the right tool for *API authorization* (delegated, scoped access), and **OIDC** for *authentication/SSO* in the modern (mobile + API + SPA) world.

Interoperability is the real-world story: these layer rather than replace. A very common enterprise topology is **SAML (or OIDC) at the corporate IdP** for federated workforce login, fronting an **OIDC/OAuth authorization server** that issues tokens to apps and APIs — so a user authenticates once via SAML to the IdP, and downstream apps consume OIDC ID tokens and OAuth access tokens without ever speaking SAML. Likewise, a BFF (Q48) bridges worlds: the *browser* uses a **session cookie** to the BFF, while the BFF uses **OAuth tokens** to call APIs — cookie on the front, token on the back. The mature design picks the right mechanism *per boundary* (cookie within an origin, OIDC for SSO, OAuth for API delegation, mTLS/keys for trusted M2M) and uses token exchange / federation to stitch them, rather than forcing one mechanism everywhere.

#### Q51. [Practical] How do you implement step-up authentication and enforce authentication context (acr/amr, max_age) in OAuth/OIDC?

Step-up authentication is the requirement that **certain operations demand stronger or fresher authentication** than the ambient session provides — viewing a dashboard might need only a password login, but transferring money or changing security settings should force MFA *now*. OAuth/OIDC supports this through **authentication context claims** and request parameters that let the client demand, and the IdP attest, *how* and *how recently* the user authenticated.

The relevant pieces: **`acr`** (Authentication Context Class Reference) — a claim in the ID token stating the assurance level achieved (e.g. a value meaning "MFA was used"); **`amr`** (Authentication Methods References) — which methods were used (`pwd`, `otp`, `hwk`, `face`); **`auth_time`** — when the user actually authenticated; and on the request side **`acr_values`** (ask the IdP to satisfy a given assurance level), **`max_age`** (require authentication no older than N seconds — forcing a re-prompt if the session is staler), and **`prompt=login`** (force a fresh credential entry).

```bash
# Sensitive operation -> demand fresh MFA via a new /authorize request:
GET /authorize?
    client_id=app&response_type=code&scope=openid&...
    &acr_values=urn:mace:incommon:iap:silver      # require this assurance level
    &max_age=300                                   # auth must be <=5 min old, else re-prompt
    &prompt=login                                  # (optionally) force credential re-entry

# Returned ID token attests what actually happened:
#   { "acr": "...silver", "amr": ["pwd","otp"], "auth_time": 1718500000, ... }
```

The enforcement flow: when a user hits a protected action, the resource/app checks the current token's `acr`/`amr`/`auth_time`; if it doesn't meet the operation's policy (e.g. MFA required, or auth older than `max_age`), the app **triggers a new authorization request with `acr_values`/`max_age`/`prompt`**, the IdP performs the step-up (prompting for the second factor), and returns a new token with the elevated `acr`. The app must **verify the IdP actually satisfied the request** — a critical subtlety is that `max_age`/`acr_values` are *requests*, and a compliant client re-checks `auth_time`/`acr` in the *response* rather than assuming the IdP honored them (a non-conforming or malicious IdP, or a misconfiguration, might not). Standardizing the `acr` values across the org (CAEP / vectors-of-trust style) keeps policy coherent.

This composes with **RAR** (Q27) for transactional step-up ("approve this specific £50 payment with MFA") and with **CAEP/continuous access evaluation** for revoking elevated context when risk changes. The design principle is that authentication assurance is **not binary or permanent** — it's a contextual, time-bounded property the token carries, and high-value actions re-establish it on demand rather than trusting a login that happened hours ago.

#### Q52. [Theory] What threats remain even after PKCE, short TTLs, and rotation, and how do DPoP/mTLS, CAEP, and risk signals address them?

PKCE, short access-token TTLs, and refresh-token rotation harden the *issuance and exchange* of tokens — but they leave a residual surface: **a valid token, in the hands of an attacker, during its (short but nonzero) lifetime, is still a usable bearer credential.** The dominant remaining threats are (1) **token theft after issuance** — XSS reading an in-memory token, malware on the device, a token leaked into logs/telemetry (Q24), or a MITM on a misconfigured TLS-terminating proxy; (2) **session/refresh-token theft** despite rotation (the window before rotation, or theft of the cookie/session in a BFF); and (3) **change of circumstances mid-session** — the user's account is compromised, disabled, or their device falls out of compliance *after* a token was issued, yet the token stays valid until `exp`.

**Sender-constrained tokens (DPoP/mTLS, Q25)** directly neutralize threat (1): binding the token to a key the client must prove possession of on every request means a stolen token is inert without the private key. This is the single biggest structural improvement, which is why FAPI 2.0 mandates it for high-assurance flows. It doesn't help if the attacker steals the *key too* (full device compromise), but it eliminates the entire class of "token leaked into a log/URL/proxy" replay.

For threats (2) and (3) — where the credential is technically valid but *shouldn't* be honored anymore — the answer is **continuous, risk-aware evaluation** rather than one-time issuance checks:

```
Issuance-time controls (PKCE, TTL, rotation, PoP)  ->  bound how tokens are minted/exchanged
Runtime/continuous controls:
  CAEP / Shared Signals (OpenID SSF)  ->  IdP pushes "session revoked / credential changed /
                                          device non-compliant" events to RPs in near-real-time
  Continuous Access Evaluation        ->  RP/gateway re-checks risk on each (or sensitive) request,
                                          not just at login
  Risk signals (impossible travel,    ->  feed step-up (Q51) or revocation when anomaly detected
    new device, anomalous volume)
```

**CAEP (Continuous Access Evaluation Protocol)** and the broader **Shared Signals Framework (OpenID SSF)** let the IdP and resource servers exchange security events out-of-band — "this session was revoked," "this user's credentials changed," "this device is no longer compliant" — so a token can be invalidated *in seconds* rather than waiting for `exp`. This closes the gap that short TTLs only *narrow*: instead of accepting a 5-minute window where a compromised account's token still works, the IdP pushes a revocation signal and gateways drop the session immediately. **Risk-based signals** (impossible travel, new-device, abnormal request volume) feed into this — triggering either **step-up auth** (Q51) to re-establish assurance or outright revocation.

The synthesis for a 2026 high-assurance design: PKCE + short TTLs + rotation as the baseline, **sender-constrained tokens** to kill replay of leaked tokens, and **CAEP/continuous evaluation + risk signals** to handle the "valid token that shouldn't be" case — moving from a static, issuance-time trust model to a **dynamic, continuously-evaluated** one. The honest limitation: continuous evaluation needs the IdP and RPs to implement SSF (still maturing adoption), and full device compromise defeats most software controls — at which point the answer shifts to hardware-backed keys (TPM/Secure Enclave, passkeys/WebAuthn) so the proof-of-possession key itself can't be exfiltrated.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q61. [Theory] What is the difference between the front channel and the back channel in OAuth/OIDC, and why does it matter?

The **front channel** is communication that passes *through the user's browser* via redirects and URL parameters — the `/authorize` request and its redirect back to `redirect_uri`. The **back channel** is a *direct server-to-server* HTTPS call that the browser never sees — the `/token` exchange, introspection, the JWKS fetch, back-channel logout, and PAR. The distinction is foundational because the two channels have completely different trust properties.

Anything on the front channel is **visible and potentially tamperable**: it lands in browser history, server access logs, `Referer` headers, and is exposed to browser extensions and any MITM on a misconfigured proxy. That is precisely why the modern design pushes *secrets* off the front channel — the Authorization Code flow deliberately sends only a short-lived, single-use `code` through the browser (front channel) and then exchanges it for tokens over the back channel, so the access/refresh tokens never appear in a URL. The old Implicit flow's fatal flaw was returning the *token itself* on the front channel.

```
Front channel (via browser, visible):   /authorize?...  -->  redirect ?code=XYZ&state=...
Back channel (server-to-server, hidden): POST /token (code+verifier) --> {access,refresh,id}
                                          POST /introspect, GET /jwks, back-channel logout
```

The practical takeaway: treat every front-channel value as public and tamperable (hence `state` for CSRF, exact `redirect_uri` matching, the `iss` response param, and PAR to move sensitive params to the back channel), and reserve confidential material (tokens, client secrets, large/sensitive request objects) for the back channel where you have a private, authenticated connection.

#### Q62. [Practical] Using only curl, how do you run a full Client Credentials flow and call a protected API?

The Client Credentials grant (no user, M2M) is the easiest flow to exercise end-to-end from a shell, which makes it the go-to for smoke-testing an IdP and a resource server. You hit the discovery doc to find the token endpoint, POST your credentials, capture the access token, then call the API with a Bearer header.

```bash
# 1. Find the token endpoint from discovery (don't hardcode it):
TOKEN_URL=$(curl -s https://idp.example.com/.well-known/openid-configuration | jq -r .token_endpoint)

# 2. Request an access token with client_credentials:
ACCESS_TOKEN=$(curl -s -X POST "$TOKEN_URL" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=svc-reporting" \
  -d "client_secret=$CLIENT_SECRET" \
  -d "scope=orders.read" | jq -r .access_token)

# 3. Call the protected resource server with the Bearer token:
curl -s https://api.example.com/orders \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 4. If you get 401, inspect WHY before retrying — decode the token's aud/scope/exp:
echo "$ACCESS_TOKEN" | cut -d. -f2 | tr '_-' '/+' \
  | awk '{ while (length($0)%4) $0=$0"="; print }' | base64 -d | jq '{aud,scope,exp,iss}'
```

A few production-grade habits: never put `client_secret` directly on the command line in shared shells (it lands in `~/.bash_history` and `ps` output) — read it from an env var or a secrets file. Prefer `--data-urlencode` if any value contains special characters. And when a call 401s, the first move is always to *decode the token you got* (step 4) rather than guessing — the overwhelmingly common causes are a missing/wrong `scope`, an `aud` that doesn't match the API, or an `iss` the resource server doesn't trust, all of which the decode reveals instantly.

#### Q63. [Theory] What does an OAuth/OIDC error response look like, and what are the most common `error` codes you must handle?

OAuth defines a **standardized error format** so clients can react programmatically rather than scraping prose. Errors arrive in two places: on the **authorization response** (as query/fragment params on the `redirect_uri`) and on the **token endpoint** (as a JSON body with an HTTP 400, mostly). Each carries a machine-readable `error` code, plus optional human-facing `error_description` and `error_uri`.

```
# Authorization-endpoint error (on the redirect, front channel):
GET {redirect_uri}?error=access_denied&error_description=User+denied&state=abc

# Token-endpoint error (JSON, HTTP 400):
{ "error": "invalid_grant",
  "error_description": "Authorization code expired",
  "error_uri": "https://idp.example.com/docs/errors#invalid_grant" }
```

The codes you must handle correctly:

| Code | Where | Meaning / correct reaction |
|------|-------|----------------------------|
| `access_denied` | authorize | user said no / policy blocked — show a friendly "you declined" page, not a stack trace |
| `invalid_grant` | token | code expired/reused, refresh token revoked/rotated — re-initiate login |
| `invalid_request` | both | malformed request (missing param) — a bug in *your* client; fix and log |
| `invalid_client` | token | client auth failed (bad secret/assertion) — config/credential problem |
| `invalid_scope` | both | requested a scope you can't have — drop it or request consent |
| `unauthorized_client` | both | this client isn't allowed this grant type — registration issue |
| `login_required` / `interaction_required` / `consent_required` | authorize (with `prompt=none`) | silent flow can't proceed — fall back to an interactive request |

The behavioral rule that separates robust clients from fragile ones: `invalid_grant` on a refresh means *the session is genuinely over* (revoked, rotated-and-reused, expired) — the correct reaction is to clear local state and start a fresh login, **not** to retry the same refresh token in a loop (which can trip reuse detection). And the `*_required` family from a `prompt=none` silent renewal is **expected, not exceptional** — it just means "I need to show the user something," so you re-issue an interactive authorization request rather than surfacing an error.

#### Q64. [Practical] How do you authenticate a native mobile/desktop app correctly — and why is an embedded WebView the wrong choice?

The correct pattern for native apps is codified in **RFC 8252 (OAuth 2.0 for Native Apps)**: use the **system browser** (or an in-app browser tab — `SFAuthenticationSession`/`ASWebAuthenticationSession` on iOS, *Custom Tabs* on Android), **Authorization Code + PKCE**, and a redirect back to the app via either a **claimed HTTPS URL** (App Links / Universal Links) or a custom scheme. The app is a **public client** (no usable secret), so PKCE is doing the heavy lifting.

```
[Native App] --launch system browser/Custom Tab--> [/authorize + PKCE]
     ^                                                     |
     | redirect: https://app.example.com/cb  (App Link)    | user logs in (sees real URL bar,
     |        or  com.example.app:/callback  (scheme)       | reuses existing IdP SSO session,
     |                                                       | password manager / passkeys work)
     +---------------------- code ---------------------------+
     POST /token (code + code_verifier) --> tokens (back channel)
```

An **embedded WebView is an anti-pattern** for several reasons that matter both for security and UX. Security: a WebView is fully controlled by the host app, so the app could read the user's keystrokes, cookies, and the password they type into the IdP — which defeats OAuth's central promise that the client never sees the user's credentials, and trains users to enter passwords inside arbitrary apps (a phishing enabler). UX: a WebView has no access to the system's SSO cookies, so the user can't reuse an existing IdP session (they must log in again), and platform features like the system password manager, passkeys/WebAuthn, and enterprise MFA browser extensions don't work inside it. Major IdPs (Google, Microsoft, Apple) actively **block or warn on WebView-based logins** for exactly these reasons.

The other native-specific concerns: **claimed HTTPS redirects (App/Universal Links) are preferred over custom schemes** because custom schemes can be hijacked by a malicious app that registers the same scheme (PKCE mitigates the resulting code theft, but verified HTTPS links remove the ambiguity entirely), and the redirect must be received by *your* app instance that still holds the matching `code_verifier`. Use the platform secure store (Keychain/Keystore) for the refresh token (Q58), and consider DPoP binding to a Secure-Enclave key so a token extracted from the device is unusable elsewhere.

### 🟡 Intermediate — extended

#### Q65. [Coding] Implement a correct authorization-request URL builder (state, nonce, PKCE) for a client.

**Problem:** Build the `/authorize` URL for an Authorization Code + PKCE + OIDC client. It must generate cryptographically-random `state` and `nonce`, attach the PKCE `code_challenge`, persist the per-request secrets so the callback (Q45) can validate them, and correctly URL-encode every parameter.

```java
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

public class AuthorizationUrlBuilder {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public record AuthRequest(String url, String state, String nonce, String codeVerifier) {}

    /** @param sessionStore where to persist {state, nonce, codeVerifier} keyed by state/session. */
    public AuthRequest build(String authorizeEndpoint, String clientId, String redirectUri,
                             String scope) {
        String state    = randomToken();
        String nonce    = randomToken();
        String verifier  = randomToken();                 // PKCE code_verifier (43 chars from 32 bytes)
        String challenge = Pkce.challengeS256(verifier);   // reuse the S256 helper from Q9

        // Order doesn't matter to the AS, but encode EVERY value.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", scope);                        // e.g. "openid profile email orders.read"
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");

        StringBuilder sb = new StringBuilder(authorizeEndpoint);
        sb.append(authorizeEndpoint.contains("?") ? '&' : '?');
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            first = false;
        }
        // Persist secrets server-side; the callback validates against these (Q45).
        return new AuthRequest(sb.toString(), state, nonce, verifier);
    }

    private static String randomToken() {
        byte[] b = new byte[32]; RNG.nextBytes(b); return B64.encodeToString(b);
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```

**Time/Space complexity:** O(P) in the number of parameters for string building and O(1) per random token; O(P) space for the parameter map and the URL. **Edge cases:** every value **must** be percent-encoded — a `redirect_uri` or `scope` with reserved characters (`:`, `/`, spaces) silently corrupts the request otherwise (`URLEncoder` encodes space as `+`, which is valid in `application/x-www-form-urlencoded` query context). `state`, `nonce`, and the `code_verifier` must each be **independent** high-entropy values from `SecureRandom` (never derive one from another). The secrets must be **persisted before redirecting** and bound to the user's session, or the callback can't validate them — and they must be single-use. Note `scope` must include `openid` to get OIDC behavior (an ID token + `nonce` honoring); omit it and you get a plain OAuth flow where `nonce` is ignored.

#### Q66. [Theory] What are Resource Indicators (RFC 8707), and how do they differ from scopes for audience control?

The problem Resource Indicators solve: in a system with **multiple APIs behind one authorization server**, a token's *audience* (`aud`) determines which API may accept it (Q35), but plain OAuth gives the client no standard way to *ask for a token targeted at a specific API*. Without it, ASs often mint broadly-audienced tokens (or you abuse scopes to imply audience), which inflates blast radius — a token usable at every API. **RFC 8707** adds a `resource` parameter (a URI naming the target API) to the authorization and token requests, so the client explicitly requests a token *for* a given resource server, and the AS stamps the matching `aud`.

```bash
# Request a token specifically for the payments API:
POST /token
  grant_type=authorization_code  code=...  code_verifier=...
  resource=https://api.payments.example.com    # <-- the target resource server (audience)
  scope=payments.write
-> access_token with aud = "https://api.payments.example.com", narrowly scoped
```

The distinction from scopes is a **what-vs-where** split: a **scope** says *what action* is permitted (`payments.write`), while a **resource indicator** says *which service* the token is for (`https://api.payments.example.com`). They're orthogonal and complementary — the same scope name might exist at two APIs, and the resource indicator disambiguates which one this token targets. Conflating them (encoding the API into the scope string, e.g. `payments-api:write`) works but is non-standard, doesn't populate `aud` cleanly, and forces every resource server to parse your scope convention.

Why it matters at scale: resource indicators enable **least-audience tokens** — the gateway/client requests narrowly-targeted tokens per downstream call (pairing naturally with token exchange, Q39, to down-audience a token for the next hop), so a token leaked from one service can't be replayed against another. It also lets the AS apply per-resource policy (different lifetimes, different required assurance) and gives the resource server a clean, standard `aud` to validate. FAPI and high-assurance profiles lean on resource indicators precisely to keep audiences tight rather than minting one over-broad token for everything.

#### Q67. [Practical] How do you make the token endpoint and JWKS callable from a browser SPA — what CORS pitfalls arise?

A SPA that holds tokens in the browser (the public-client model, Q48) calls the AS's `/token` endpoint and the IdP's `/jwks` and `/userinfo` directly from JavaScript — which makes them **cross-origin requests** subject to the browser's CORS policy. The historical reason the Implicit flow existed was that ASs *didn't* support CORS, so SPAs couldn't POST to `/token`; that constraint is gone, but it means the IdP must be configured to return the right CORS headers or the browser silently blocks the response.

```
SPA at https://app.example.com  -->  POST https://idp.example.com/token   (cross-origin)

Browser sends preflight:   OPTIONS /token   Origin: https://app.example.com
IdP must respond:          Access-Control-Allow-Origin: https://app.example.com
                           Access-Control-Allow-Methods: POST, OPTIONS
                           Access-Control-Allow-Headers: Content-Type
(No Access-Control-Allow-Credentials: true is needed — public clients don't send cookies here.)
```

The pitfalls that bite teams: (1) **The IdP must allow-list the SPA's exact origin** for `/token`, `/jwks`, and `/userinfo` — many IdPs have a separate "Web Origins"/"Allowed CORS Origins" setting *distinct* from the redirect-URI list, and people configure the redirect URI but forget the CORS origin, getting an opaque "CORS error" in the console while the network tab shows the request was made. (2) **A confidential client's secret must never be sent from the browser** — if `/token` requires `client_secret`, you cannot call it safely from JS at all; that's a sign you should be a public client (PKCE, no secret) or use a BFF. (3) **Preflight caching and credentials mode** — sending `credentials: 'include'` triggers stricter rules (the IdP can't use `*` for `Allow-Origin` and must echo the exact origin with `Allow-Credentials: true`), so prefer not sending cookies on these calls. (4) **JWKS from the browser** — if the SPA verifies ID tokens client-side it fetches JWKS cross-origin too, which also needs CORS; many SPAs instead rely on the library validating via the discovery doc.

The cleaner structural answer for anything sensitive is the **BFF** (Q48): the browser talks same-origin to its own backend, the backend (a confidential client) calls `/token`/`/jwks` server-side where CORS is irrelevant, and no token or secret is exposed to JavaScript — sidestepping the entire CORS surface along with the XSS-exfiltration risk.

#### Q68. [Coding] Implement a `private_key_jwt` client-assertion generator for confidential-client authentication.

**Problem:** Implement client authentication via `private_key_jwt` (RFC 7523): the client signs a short-lived JWT *assertion* with its private key and sends it to the token endpoint, so no shared secret crosses the wire (Q37). Build the signed assertion with the required claims.

```java
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;

public class ClientAssertionFactory {

    private final String clientId;       // also the assertion's 'iss' and 'sub'
    private final String tokenEndpoint;  // the 'aud' — MUST be the AS token endpoint URL
    private final RSAPrivateKey privateKey;
    private final String keyId;          // 'kid' so the AS picks the right public key

    public ClientAssertionFactory(String clientId, String tokenEndpoint,
                                  RSAPrivateKey privateKey, String keyId) {
        this.clientId = clientId; this.tokenEndpoint = tokenEndpoint;
        this.privateKey = privateKey; this.keyId = keyId;
    }

    /** Build a single-use, ~60s-lived signed JWT proving control of the private key. */
    public String createAssertion() throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientId)                            // iss = client_id
                .subject(clientId)                           // sub = client_id
                .audience(tokenEndpoint)                     // aud = token endpoint (anti-replay scoping)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .jwtID(UUID.randomUUID().toString())         // jti — unique, lets AS reject replays
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(),
                claims);
        jwt.sign(new RSASSASigner(privateKey));              // sign with the PRIVATE key
        return jwt.serialize();
    }
}

// Posting it to the token endpoint:
//   grant_type=client_credentials & scope=orders.read
//   client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
//   client_assertion=<the serialized JWT above>
```

**Time/Space complexity:** O(1) — a single RSA signature over a tiny payload; O(1) space. **Edge cases:** the `aud` claim **must be the token endpoint URL** (or the issuer, per the AS's expectation) — getting this wrong is the #1 cause of `invalid_client`, and it's a deliberate anti-replay control so an assertion captured for one AS can't be replayed elsewhere. Keep the lifetime **short (≈60s)** and include a unique `jti` so the AS can detect and reject a replayed assertion within its window. The `kid` in the header must match a public key the AS has registered for this client (via JWKS or static upload), or signature verification fails. Use a tight clock-skew margin. The win over `client_secret` is structural: a breach of the AS's stored client metadata exposes only the *public* key, which is useless to an attacker — there is no shared secret to steal.

#### Q69. [Practical] Compare opaque-token introspection with JWT self-validation in a gateway, with caching strategy.

When a gateway must decide whether a token is valid, the mechanism depends on the token type, and the right answer is usually a **caching layer** that gives you opaque-token control without paying the introspection cost on every request. With **JWT self-validation**, the gateway verifies the signature against a cached JWKS and checks `iss`/`aud`/`exp` locally — zero network calls per request after the JWKS is warm, but no instant revocation (valid until `exp`). With **opaque-token introspection**, the gateway calls the AS's `/introspect` per request — authoritative and instantly revocable, but a network hop and AS load on *every* request, which is unsustainable at high volume (Q55).

```
JWT path:       request --> [verify sig vs cached JWKS, check iss/aud/exp] --> route   (no AS call)
Opaque path:    request --> [introspect at AS] --> route                               (AS call/req)
Opaque+cache:   request --> [check local cache by token hash]
                              hit  -> route
                              miss -> introspect once, cache result for min(TTL, exp) --> route
```

The mature middle ground is **introspection with a short-lived cache keyed by the token (its hash, never the raw token)**. The gateway introspects once, caches the `{active, scope, sub, exp, aud}` result for a bounded TTL — typically `min(configured_cache_ttl, exp - now)` and capped at a few seconds to tens of seconds — and serves subsequent requests from cache. This collapses per-request AS load by the cache hit ratio (often 95 %+) while keeping the revocation window small (bounded by the cache TTL, not the token's full `exp`). The cache TTL is the explicit **revocation-latency vs. AS-load knob**: shorter TTL = faster revocation, more introspection traffic.

Key implementation details and trade-offs: **cache the result keyed by a hash of the token, not the token itself** (so a memory dump or cache leak doesn't expose live credentials), and **never cache `active: false`** results aggressively (or cache them only very briefly) so a token that becomes valid isn't wrongly rejected — though typically tokens go valid→invalid, not the reverse. Add **request coalescing / single-flight** (Q57) so a burst of requests for the *same* uncached token triggers one introspection, not a stampede. For very high throughput, layer the **phantom-token pattern** (Q19): introspect the opaque token once at the edge, then forward a short-lived JWT internally so downstream services do pure local validation — combining opaque-token revocation control at the perimeter with JWT scalability inside.

#### Q70. [Theory] Why must the authorization `code` be single-use and short-lived, and what does an AS do if a code is replayed?

The authorization `code` is the value that travels the **front channel** (through the browser) in the Authorization Code flow, and it is deliberately the *only* sensitive-ish value to do so. Because it transits an exposed channel — it can land in browser history, logs, `Referer` headers, or be intercepted on a hijacked custom-scheme redirect (Q8) — it is designed to be **near-useless if stolen**: it is **single-use** (redeemable exactly once at `/token`) and **short-lived** (typically 30–60 seconds), and with PKCE it additionally requires the matching `code_verifier` that never left the legitimate client. These three properties together mean an intercepted code is very likely already expired, already spent, or unusable without the verifier.

The AS's behavior on **code replay** is a defined security control: if a code that has *already been redeemed* is presented again, the AS must not only reject the second request but, per the OAuth Security BCP, **revoke all tokens previously issued from that code**. The reasoning is that a code being redeemed twice means *something is wrong* — either the legitimate client is buggy, or an attacker stole the code and is racing the real client to exchange it. Since the AS can't tell which redemption was the attacker's, the safe response is to assume compromise and invalidate the resulting tokens, forcing a fresh, clean login.

```
Legitimate:  client --code(XYZ)+verifier--> /token  -> {tokens}, code XYZ now BURNED
Replay:      attacker --code(XYZ)--> /token  -> error invalid_grant
             AND the AS revokes the tokens already issued from XYZ (assume compromise)
```

This mirrors **refresh-token reuse detection** (Q12/Q21): in both cases, reuse of a one-time credential is treated as a breach signal that triggers revocation of the associated family/tokens, not merely a soft rejection. The design philosophy is consistent — front-channel and long-lived secrets are made single-use so that *reuse itself becomes a detectable, self-revoking anomaly*. The practical implication for client authors: never retry a `/token` call with the same code after a network error without understanding it may already have succeeded server-side; a blind retry can look like a replay and burn the session.

#### Q71. [Coding] Parse a `WWW-Authenticate: Bearer` challenge and react to `error="invalid_token"` vs `insufficient_scope`.

**Problem:** Per RFC 6750, when a resource server rejects a Bearer token it returns `401`/`403` with a `WWW-Authenticate: Bearer` header carrying an `error` and optionally `scope`. A client SDK should parse this to decide whether to **refresh the token** (`invalid_token`) or **request additional scopes / step-up** (`insufficient_scope`), rather than blindly retrying.

```java
import java.util.*;
import java.util.regex.*;

public class BearerChallengeParser {

    public record Challenge(String error, String errorDescription, String requiredScope) {}

    // Matches key="value" pairs inside a Bearer challenge.
    private static final Pattern PARAM =
        Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");

    /** Parse a WWW-Authenticate header value; returns null if it isn't a Bearer challenge. */
    public static Challenge parse(String headerValue) {
        if (headerValue == null) return null;
        String trimmed = headerValue.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer", 0, "Bearer".length())) return null;

        Map<String, String> params = new HashMap<>();
        Matcher m = PARAM.matcher(trimmed);
        while (m.find()) params.put(m.group(1).toLowerCase(), m.group(2));

        return new Challenge(
            params.get("error"),
            params.get("error_description"),
            params.get("scope"));      // space-delimited required scopes, if any
    }

    /** Decide the reaction. */
    public static String react(int status, Challenge c) {
        if (c == null) return status == 401 ? "REAUTHENTICATE" : "FAIL";
        return switch (Objects.requireNonNullElse(c.error(), "")) {
            case "invalid_token"      -> "REFRESH_TOKEN";        // expired/revoked -> get a new one
            case "insufficient_scope" -> "REQUEST_SCOPES:" + c.requiredScope(); // re-consent / step-up
            case "invalid_request"    -> "FIX_CLIENT_BUG";       // malformed Authorization header
            default                    -> "REAUTHENTICATE";
        };
    }
}
```

**Time/Space complexity:** O(n) in header length for the regex scan; O(k) space for the parsed params. **Edge cases:** the three error codes drive **fundamentally different reactions** and conflating them wastes round trips or loops forever — `invalid_token` (HTTP 401) means *the token itself is bad* (expired/revoked/malformed), so refresh and retry **once**; `insufficient_scope` (HTTP 403) means *the token is valid but lacks a permission*, so refreshing the same token is pointless — you must start a new authorization request asking for the `scope` the header names (often a **step-up**, Q51). Blindly retrying on `insufficient_scope` causes an infinite 403 loop. Also guard against a refresh loop on persistent `invalid_token` (cap retries). The header may contain `realm`, `error`, `error_description`, and `scope`; be tolerant of ordering and extra params, and treat a 401 with *no* `WWW-Authenticate` (or a non-Bearer scheme) as "re-authenticate."

### 🟠 Advanced — extended

#### Q72. [Theory] What is the OIDC Hybrid flow and the `at_hash`/`c_hash` claims, and what problem do they address?

OIDC defines three flows via `response_type`: **Authorization Code** (`code`), the deprecated **Implicit** (`id_token` / `id_token token`), and the **Hybrid** flow (`code id_token`, `code token`, or `code id_token token`), where *some* artifacts come back on the front channel immediately and the `code` is still exchanged on the back channel. The hybrid flow's original purpose was to let a client get an **ID token immediately** on the redirect (to establish a session or render UI fast) while still doing a secure back-channel code exchange for the access/refresh tokens — a middle ground between the all-front-channel Implicit flow and the all-back-channel Code flow.

This creates a subtle integrity problem: when the access token (or code) arrives on the *front channel* alongside the ID token, how does the client know the front-channel value wasn't swapped by an attacker for a different one? OIDC answers with **`at_hash`** and **`c_hash`** claims embedded *inside the signed ID token*. `at_hash` is the base64url of the left-half of the hash of the **access token**; `c_hash` is the same for the **code**. Because they live inside the IdP-signed ID token, the client can verify that the access token / code it received on the front channel **matches the one the IdP actually issued** — defeating a token-substitution/injection attack where an attacker splices in their own access token.

```
Hybrid: response_type=code id_token
  redirect: #id_token=<signed JWT containing at_hash, c_hash, nonce>&code=XYZ
  Client checks:  at_hash == BASE64URL(leftHalf(SHA256(access_token)))
                  c_hash  == BASE64URL(leftHalf(SHA256(code)))
  -> binds the front-channel artifacts to the signed ID token
```

The modern verdict: with **Authorization Code + PKCE** as the universal default (Q5), the hybrid flow has largely fallen out of favor — PKCE already binds the code to the client, and there's little reason to expose tokens on the front channel. OAuth 2.1 and current security guidance steer everyone to plain `code` flow. So `at_hash`/`c_hash` are mostly relevant for understanding legacy hybrid integrations and the *general principle* they embody: **when a value must travel an untrusted channel, bind it cryptographically to a signed artifact so tampering is detectable** — the same principle behind `nonce`, the `iss` response param, and JAR.

#### Q73. [Coding] Implement server-side DPoP proof validation (RFC 9449) for a resource server.

**Problem:** A client sends `Authorization: DPoP <token>` plus a `DPoP: <proof JWT>` header. The resource server must validate the proof: it's a well-formed JWS signed by the key in its own header, the `htm`/`htu` match the actual request, it's fresh (`iat`), the `jti` hasn't been replayed, and (if the access token carries a `cnf.jkt`) the proof's key thumbprint matches it. Implement the core checks.

```java
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DPoPValidator {

    private final Set<String> seenJti = ConcurrentHashMap.newKeySet(); // replay cache (evict by time in prod)
    private final long maxAgeSeconds = 60;

    /**
     * @param dpopProof the DPoP header JWT
     * @param method actual HTTP method, @param uri actual request URI (no query/fragment)
     * @param expectedJkt cnf.jkt thumbprint from the access token, or null if bearer-style
     */
    public void validate(String dpopProof, String method, String uri, String expectedJkt)
            throws Exception {
        SignedJWT jwt = SignedJWT.parse(dpopProof);

        // 1. typ must be dpop+jwt; the public key travels in the header 'jwk'.
        if (!"dpop+jwt".equals(jwt.getHeader().getType().toString()))
            throw new SecurityException("wrong typ");
        JWK jwk = jwt.getHeader().getJWK();
        if (jwk == null || jwk.isPrivate()) throw new SecurityException("missing/invalid jwk");

        // 2. Signature must verify against the embedded public key (proof of possession).
        if (!jwt.verify(jwk.toRSAKey().toRSAPublicKey() instanceof java.security.PublicKey
                ? new com.nimbusds.jose.crypto.RSASSAVerifier(jwk.toRSAKey().toRSAPublicKey())
                : null))
            throw new SecurityException("bad proof signature");

        var claims = jwt.getJWTClaimsSet();

        // 3. htm/htu must match THIS request (binds proof to method + URL).
        if (!method.equalsIgnoreCase(claims.getStringClaim("htm")))
            throw new SecurityException("htm mismatch");
        if (!uri.equals(claims.getStringClaim("htu")))
            throw new SecurityException("htu mismatch");

        // 4. Freshness: iat within window.
        Instant iat = claims.getIssueTime().toInstant();
        if (Math.abs(Instant.now().getEpochSecond() - iat.getEpochSecond()) > maxAgeSeconds)
            throw new SecurityException("proof too old/future");

        // 5. Anti-replay: jti must be unique within the window.
        String jti = claims.getJWTID();
        if (jti == null || !seenJti.add(jti)) throw new SecurityException("replayed jti");

        // 6. Bind to the access token: thumbprint of the proof key must equal cnf.jkt.
        String jkt = jwk.computeThumbprint().toString();
        if (expectedJkt != null && !expectedJkt.equals(jkt))
            throw new SecurityException("proof key != token cnf.jkt — token not bound to this key");
    }
}
```

**Time/Space complexity:** O(1) verification (one signature) plus an O(1) replay-cache insert; space O(R) for the `jti` replay cache over the freshness window. **Edge cases:** the **`jti` replay cache must be time-bounded** (evict after `maxAgeSeconds`) or it grows unbounded — and in a multi-instance deployment it must be **shared** (Redis) or an attacker replays a proof against a different node; some deployments instead use a server-issued **DPoP nonce** (`DPoP-Nonce` header) to bind freshness without distributed `jti` state. The **`cnf.jkt` binding is the whole point**: without step 6 the token is just a bearer token; with it, a stolen access token is useless unless the attacker also has the private key. Clock skew applies to `iat`. Pin the accepted JWS algorithms (reject `none`, Q42). This is intentionally illustrative — in production use a vetted DPoP library; the value here is seeing exactly which bindings (`htm`, `htu`, `iat`, `jti`, `jkt`) make a proof non-replayable.

#### Q74. [Practical] Explain CIBA (Client-Initiated Backchannel Authentication) and a decoupled-authentication use case.

**CIBA** (Client-Initiated Backchannel Authentication, an OpenID Foundation spec) flips the usual flow: instead of the *user's* browser being redirected to the IdP, the **client initiates authentication out-of-band** and the user approves on a **separate, decoupled device** — typically their phone. There is no front-channel redirect at all; the whole flow runs over the back channel plus a push to the user's authentication device. This solves scenarios where the user isn't sitting at the device that needs authorization.

The canonical use case is a **call-center or point-of-sale "decoupled" approval**: a bank agent (or a store terminal) initiates a high-value action, and the *customer* receives a push notification on their banking app to approve it — the customer's phone is the authentication device, the agent's terminal is the consumption device, and the two are completely separate. Other fits: smart-speaker/voice transactions, payment confirmations (Open Banking heavily uses CIBA for "confirmation of payee" style approvals), and IoT where a backend must get a human's approval without a browser.

```
[Client / agent terminal]  --POST /bc-authorize (login_hint=customer, scope, binding_message)-->  [IdP]
        |                                                                                            |
        |  <-- { auth_req_id, expires_in, interval } -------------------------------------------------|
        |                                          push approval prompt --> [Customer's phone app]
        |  poll: POST /token (grant_type=ciba, auth_req_id) --> authorization_pending ...             |
        |                                          customer approves on phone --------------------->  |
        |  <-- { access_token, id_token } (after approval) -------------------------------------------|
```

Mechanically it resembles the **Device flow** (Q11) — the client gets an `auth_req_id` and then either **polls** the token endpoint, gets a **ping** callback, or a full **push** of the tokens, depending on the configured delivery mode — but the key differences are: the client identifies the user up front via a `login_hint`/`id_token_hint`/`login_hint_token` (CIBA targets a *known* user, unlike Device flow's anonymous start), and it supports a **`binding_message`** — a short human-readable code shown on *both* the terminal and the phone so the user can confirm they're approving *this* transaction (anti-phishing: "Approve transfer ref 7F3K?"). CIBA is a confidential-client, high-assurance flow (it requires strong client authentication and is part of FAPI-CIBA profiles), and its trade-offs are the same family as Device flow: you must honor the polling `interval`/`slow_down`, handle `expired_token`, and design for the user simply never approving.

#### Q75. [Theory] What are JWE (encrypted) tokens and nested JWTs, and when do you encrypt a token rather than just sign it?

A **JWS** (JSON Web Signature) — the usual JWT — is *signed but not encrypted*: anyone who holds it can read every claim (it's just base64url JSON, Q34). A **JWE** (JSON Web Encryption) is *encrypted*: the payload is confidential and only a holder of the decryption key can read it. A **nested JWT** is the high-assurance combination — you **sign then encrypt** (a JWS wrapped inside a JWE), giving both integrity/authenticity *and* confidentiality. The token then has five segments (JWE structure) rather than three.

```
JWS (signed):     header.payload.signature                 -> integrity, but claims are READABLE
JWE (encrypted):  header.encKey.iv.ciphertext.tag          -> claims CONFIDENTIAL
Nested (sign+enc): JWE whose plaintext IS a JWS            -> integrity + confidentiality
```

You **sign** by default (you almost always need to prove a token wasn't forged), and you **additionally encrypt** when the token's *claims themselves are sensitive and must not be readable by intermediaries or the end user/browser*. Concrete cases: an ID token containing PII (national ID, health flags, precise location) that you don't want exposed in browser history, logs, or to a malicious browser extension; a token that passes through proxies or is cached where readable claims would leak data; regulatory regimes (some financial/health profiles) that mandate confidentiality of identity assertions. OIDC explicitly supports **encrypted ID tokens** and the `request` object can be a JWE (encrypted JAR), and FAPI profiles use encryption for the most sensitive flows.

The trade-offs make signing-only the right default for most systems: encryption adds **key-management complexity** (now the *recipient* needs a decryption key pair and the issuer needs the recipient's public key, on top of the signing keys), **performance cost**, and **debuggability loss** (you can no longer just decode-and-inspect a token, Q34 — it's opaque without the key, which is sometimes exactly the point). It also doesn't replace signing — encryption alone gives confidentiality but a naive scheme may not give *authenticity*, which is why the secure pattern is **sign-then-encrypt** (nested). The pragmatic rule: keep access tokens signed and lean (no sensitive claims), prefer **opaque access tokens** if you want zero claim exposure (Q19), and reach for JWE only when you have a genuine confidentiality requirement on the claims and a clear key-distribution story — most systems get confidentiality "for free" by simply *not putting sensitive data in the token* and fetching it from `/userinfo` or an API instead.

#### Q76. [Practical] What metrics, logs, and alerts do you put on an OAuth/OIDC system to detect attacks and outages?

Observability for auth has two jobs that pull in different directions: detect **outages** (legitimate users can't log in) and detect **attacks** (illegitimate activity looks like success), and you must do it **without logging the credentials themselves** (Q24). The instrumentation splits naturally across the AS, the resource servers/gateway, and the token validation path.

```
Golden signals to emit (NEVER log raw tokens/secrets — use jti/sub/client_id):
  Issuance:   /token success vs error rate, by grant_type & client_id; issuance latency (signing)
  Validation: 401/403 rate by reason (invalid_token | insufficient_scope | bad aud | unknown kid)
  JWKS:       fetch rate & errors, cache hit ratio, unknown-kid lookups (rotation signal/attack)
  Refresh:    refresh success/fail rate; reuse-detection events (breach signal!)
  Logins:     interactive login rate, MFA/step-up rate, consent grants per client
```

For **outage detection**, the highest-signal alerts are: a **spike in 401s across all services** (the IdP-deploy incident, Q46 — almost always key rotation without overlap, an `iss` change, or clock skew), a **drop in `/token` success rate**, **JWKS fetch failures** (resource servers about to fail closed), and **introspection-endpoint latency/error** (if you use opaque tokens, this is on the critical path of every request, Q55). A practical alert that catches the most common cause early: fire when **the IdP signs with a `kid` not present in the published JWKS** — that single check pre-empts the most frequent global 401 storm before users notice.

For **attack detection**, the signals are different and easy to miss because the requests often *succeed*: a surge in **`invalid_grant` / refresh failures** or **refresh-token reuse-detection events** (token theft, Q12); **`insufficient_scope` / `invalid_audience` 403s** spiking (someone replaying tokens across APIs, Q35); **abnormal consent-grant volume for one client** (a mass-phishing campaign, Q56); **authorization requests with mismatched/novel `redirect_uri`s** (open-redirect probing, Q32); **impossible-travel / new-device patterns** feeding step-up or revocation (Q51/Q52); and **bursts of unknown-`kid` token submissions** (JWKS-stampede DoS probing, Q40). Tie these into the **CAEP/Shared-Signals** pipeline (Q52) so a detected anomaly can push a revocation event rather than just raising a ticket.

Two cross-cutting rules: **redact the `Authorization`/`DPoP` headers and any `code`/`client_secret`/`refresh_token` everywhere** (log appenders, tracing spans, error trackers) and instead correlate on non-secret identifiers (`jti`, `sub`, `client_id`, `iss`, `kid`); and **emit structured, queryable fields** (not free text) so during an incident you can pivot by `kid`, `iss`, `aud`, and `client_id` in seconds — which is exactly what turns the Q46 triage from hours into minutes.

#### Q77. [Coding] Implement a token-bucket rate limiter for the `/token` and `/introspect` endpoints keyed by client_id.

**Problem:** Auth endpoints are prime targets for credential-stuffing and DoS. Implement a thread-safe token-bucket rate limiter keyed by `client_id` (and usable per-IP) so each client gets a sustained rate plus a burst allowance, protecting the AS's signing/introspection capacity (Q55).

```java
import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketRateLimiter {

    private final long capacity;          // max burst (tokens)
    private final double refillPerSecond;  // sustained rate
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(long capacity, double refillPerSecond) {
        this.capacity = capacity; this.refillPerSecond = refillPerSecond;
    }

    /** @return true if the request is allowed; false if the client exceeded its rate. */
    public boolean tryAcquire(String key) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, System.nanoTime()));
        synchronized (b) {
            long now = System.nanoTime();
            double elapsedSec = (now - b.lastRefillNanos) / 1_000_000_000.0;
            b.tokens = Math.min(capacity, b.tokens + elapsedSec * refillPerSecond); // lazy refill
            b.lastRefillNanos = now;
            if (b.tokens >= 1.0) { b.tokens -= 1.0; return true; }
            return false;                                   // -> respond 429 Too Many Requests
        }
    }

    private static final class Bucket {
        double tokens; long lastRefillNanos;
        Bucket(double t, long n) { this.tokens = t; this.lastRefillNanos = n; }
    }
}
```

**Time/Space complexity:** O(1) per request (a map lookup + arithmetic under a per-bucket lock); space O(K) for K distinct keys. **Edge cases:** **lazy refill** (compute tokens from elapsed time on access) avoids a background timer thread and is exact. The per-bucket `synchronized` block keeps it correct under concurrency without a global lock, so unrelated clients don't contend. Choose the **key carefully**: keying on `client_id` alone lets one noisy tenant's traffic be isolated, but for *unauthenticated* abuse (credential stuffing at `/token`) you must also rate-limit by **source IP** and by **target username** (for password-style grants), since the attacker controls the client_id field. The bucket map needs **eviction** (idle TTL or an LRU/Caffeine cache) or it leaks memory under a flood of distinct keys — itself a DoS vector. In a multi-node AS, a per-node limiter under-counts; for hard global limits back it with a **distributed counter** (Redis `INCR`+TTL or a Lua token-bucket script). Return `429` with a `Retry-After` header so well-behaved clients back off (mirroring the Device/CIBA `slow_down` discipline). Always pair rate limiting with **exponential backoff + lockout** on repeated `invalid_client`/`invalid_grant` to blunt brute force.

#### Q78. [Theory] What is OAuth 2.0 Dynamic Client Registration (RFC 7591/7592), and where is it essential?

**Dynamic Client Registration (DCR)** lets a client **register itself with the authorization server programmatically** by POSTing its metadata (redirect URIs, grant types, token-auth method, JWKS) to a registration endpoint, receiving back a `client_id` (and possibly `client_secret`) — instead of a human clicking through an admin console. **RFC 7591** defines the registration request; **RFC 7592** adds management (read/update/delete a registration via a returned `registration_access_token`). It turns client onboarding from a manual, out-of-band step into an API.

```bash
POST /register
  { "redirect_uris": ["https://app.example.com/cb"],
    "grant_types": ["authorization_code"],
    "token_endpoint_auth_method": "private_key_jwt",
    "jwks_uri": "https://app.example.com/jwks" }
-> { "client_id": "abc123",
     "registration_access_token": "...",       # for RFC 7592 management
     "registration_client_uri": "https://idp.example.com/register/abc123" }
```

DCR is **essential in three settings**. (1) **Open ecosystems / federations** where you can't manually register every client ahead of time — most importantly **UK/EU Open Banking and FAPI**, where hundreds of third-party providers must onboard against many banks; here DCR is paired with a **software statement** (a signed JWT, RFC 7591's `software_statement`, issued by a trust framework/federation operator) that vouches for the client's identity and allowed metadata, so the bank's AS can trust a registration it never manually approved. (2) **Large multi-tenant SaaS** that provisions an OAuth client per customer/integration automatically. (3) **IoT/device fleets and CI systems** that spin clients up and down at scale.

The trade-off is squarely **security**: an *open* registration endpoint is a self-service way for attackers to mint clients (for phishing apps, open-redirect probing, or resource exhaustion). So in practice DCR is almost never fully open — it's gated by an **initial access token** (only pre-authorized parties may register), a **signed software statement** from a trusted federation, and **server-side validation/normalization** of the submitted metadata (the AS clamps redirect URIs, disallowed grant types, etc.). The senior framing: DCR is the mechanism that makes *large federated OAuth ecosystems* operationally possible, but it shifts the trust question from "did an admin approve this client" to "do we trust the software statement / initial token that authorized this self-registration" — which is why it lives inside trust frameworks rather than as a wide-open endpoint.

#### Q79. [Practical] How do you write integration tests for OAuth-protected endpoints without depending on a live IdP?

The goal is to test your **resource server's authorization logic** (does it accept valid tokens, reject bad `aud`/`iss`/`exp`/scope, enforce method security) **deterministically and offline**, without flakiness from a real IdP. The key realization: a resource server validates JWTs against a **public key (JWKS)** and a set of expected claims — so in tests you control the *signing key* and *mint your own tokens*, or you bypass token minting entirely with test-support helpers.

There are three layers, fastest to most realistic:

```java
// (A) Spring Security test support — inject a fake authentication, NO real JWT at all.
//     Fast unit/slice test of authorization rules.
mockMvc.perform(get("/api/orders")
        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.read"))))
       .andExpect(status().isOk());

mockMvc.perform(get("/api/admin")          // wrong scope -> must be 403
        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_orders.read"))))
       .andExpect(status().isForbidden());
```

```java
// (B) Self-signed real JWTs verified against a test JWKS served by a mock HTTP server.
//     Exercises the REAL signature/iss/aud/exp validation path.
RSAKey rsa = new RSAKeyGenerator(2048).keyID("test").generate();      // test key pair
// Serve {"keys":[rsa.toPublicJWK()]} from WireMock at the issuer's jwks_uri,
// point spring.security.oauth2.resourceserver.jwt.jwk-set-uri at WireMock,
// then sign tokens with rsa for happy-path AND craft tokens with wrong aud/expired exp/alg=none.
String token = signWith(rsa, claims().issuer(ISS).audience("orders-api")
                                      .expirationTime(in5min()).claim("scope","orders.read"));
```

```
(C) A containerized IdP (Testcontainers + Keycloak) for end-to-end confidence:
    spin up Keycloak, import a realm, run the actual /token + /authorize flow.
    Slowest; reserve for a few smoke tests, not every assertion.
```

The strategy and trade-offs: use **(A)** for the bulk of authorization-rule tests — it's milliseconds-fast and tests *your* policy (scopes, roles, method security) without any crypto, but it **does not** test that token *validation* itself is configured correctly. Use **(B)** to cover the security-critical negative cases that (A) skips: **wrong `aud`**, **wrong `iss`**, **expired token**, **`alg: none`/unexpected algorithm**, **unknown `kid`** — these are exactly the misconfigurations that cause real breaches (Q35/Q42), and a self-signed-key + mock-JWKS setup lets you assert each returns 401. Reserve **(C)** (Testcontainers/Keycloak) for a handful of full-flow smoke tests because it's slow and heavier to maintain. A common mistake is testing *only* the happy path with valid tokens; the high-value tests are the **negative** ones proving you reject tokens you must reject — and those are precisely what (B) makes cheap and deterministic.

### 🔴 Expert — extended

#### Q80. [Theory] Compare GNAP (the OAuth successor effort) with OAuth 2.0/2.1 — what does it rethink and is it worth adopting?

**GNAP** (Grant Negotiation and Authorization Protocol, RFC 9635) is an IETF effort to design a *successor* to OAuth 2.0 from a clean sheet rather than patching it. Where OAuth 2.0 accreted dozens of extension RFCs (PKCE, PAR, RAR, DPoP, token exchange, resource indicators) to fix structural gaps, GNAP folds those concerns into the **core protocol** with a different shape: a single, **JSON-based, back-channel "grant request"** that the client sends to a *grant endpoint*, negotiating access, identity, and interaction in one extensible message — instead of OAuth's redirect-with-query-params `/authorize` front-channel design.

The things GNAP deliberately rethinks:

```
OAuth 2.0/2.1                              GNAP
  front-channel /authorize, query params     back-channel JSON grant request (rich, signed)
  bearer tokens by default                    key-bound (proof-of-possession) by default
  flows bolted on (code/CC/device/CIBA)       one negotiation model covers all interaction modes
  scopes (+ RAR extension) for fine grain     rich access-rights objects native to the request
  PKCE/PAR/DPoP/RAR as separate RFCs           those concerns built into the core
```

The conceptual improvements are real: **proof-of-possession by default** (no bearer footgun, Q25), a **back-channel-first** design that closes the front-channel attack surface (mix-up, tampering, open redirect — Q18/Q32/Q47) by construction, **native rich authorization** without bolting RAR onto scopes (Q27), and **first-class support for multiple interaction modes** (redirect, app-to-app, CIBA-like decoupled, no-user) under one model rather than separate grant types. It also separates the client instance's *key* from its identity more cleanly, fitting modern key-bound, ephemeral workloads.

My critical take and adoption advice: GNAP is **conceptually cleaner but not where the ecosystem is.** OAuth 2.0/2.1 + OIDC has overwhelming inertia — every IdP, library, and gateway speaks it, and OAuth 2.1 + the Security BCP + DPoP + PAR + RAR collectively reach *most* of GNAP's security posture while remaining interoperable. GNAP's library/IdP support and battle-testing are immature, and OIDC's identity layer has no finished GNAP equivalent yet. So the honest senior answer in 2026 is: **adopt OAuth 2.1 + OIDC + the relevant extensions (PKCE, PAR, DPoP, RAR) for anything you ship today**, and watch GNAP as the likely long-term direction — it's the right model to understand for where the field is heading, but choosing it now means betting on a thin ecosystem for marginal gains over a well-hardened OAuth 2.1 stack.

#### Q81. [Practical] Design identity propagation through a deep service mesh: SPIFFE/workload identity + on-behalf-of token exchange.

The problem at depth: a request enters at the edge carrying a *user* token, then fans out through many internal services (A→B→C→D). You must answer two distinct questions at every hop — **"who is the workload making this call"** (service identity) and **"on whose behalf is it acting"** (user identity) — and naively forwarding the original over-broad user token through all hops is both over-privileged and a leak magnet (any compromised hop can replay it anywhere). The clean design **separates workload identity from user delegation**: SPIFFE/mTLS for the former, OAuth token exchange for the latter.

```
[User] --user token--> [Edge/Gateway]
   |  exchange: down-scope + re-audience for the next hop (RFC 8693, Q39)
   v
 svc-A --(mTLS, SPIFFE SVID = "who I am")--> svc-B
   |   carries: token for aud=B, sub=user, act={A}   (delegation: B knows A acts for user)
   v
 svc-B --token-exchange for aud=C, narrowed scope--> svc-C ...
Transport identity:  SPIFFE SVID via mTLS in the mesh  (Istio/Linkerd issue + rotate certs)
Delegation identity: OAuth on-behalf-of token per hop  (each hop down-scoped & re-audienced)
```

**Layer 1 — workload identity (SPIFFE/SPIRE + mTLS service mesh).** Every workload gets a short-lived, automatically-rotated **SVID** (X.509 cert or JWT-SVID) attesting *what it is* (`spiffe://cluster/ns/payments/sa/charger`). The mesh (Istio/Linkerd) enforces mutual TLS so each service cryptographically knows the *calling service's* identity, independent of any user token. This replaces shared M2M secrets entirely (Q49) and gives the mesh-level authorization ("only svc-A may call svc-B") plus automatic cert rotation.

**Layer 2 — user delegation (token exchange, RFC 8693).** Workload identity says nothing about *the user*. So at each boundary where user context matters, the service performs **on-behalf-of token exchange** (Q39): swap the inbound user token for a new one **audienced for the next service and down-scoped** to exactly what that hop needs, carrying the user as `sub` and the calling service in the `act` (actor) claim for **delegation semantics**. This means a token leaked at hop C is audienced only for C's downstream and scoped minimally — it can't be replayed at B or the edge — and the `act` chain gives an **auditable record** of which services touched the request on the user's behalf.

The synthesis and trade-offs: **mTLS/SPIFFE answers "which workload," exchanged delegation tokens answer "for which user, with what rights"** — and you need *both* because each alone is insufficient (mTLS proves the service but loses the user; a forwarded user token proves the user but is over-broad and says nothing trustworthy about the workload). The cost is operational: token exchange adds an AS round trip per boundary (mitigate by exchanging only at trust boundaries, not every hop, and caching the exchanged token for its short lifetime, Q57), and running SPIRE + a mesh is real infrastructure. The payoff is **least-privilege at depth, no standing secrets, automatic rotation, and a full audit trail** — which is why large platforms and zero-trust architectures converge on exactly this split (often with the gateway doing the first exchange and the mesh handling transport identity transparently).

#### Q82. [Theory] Critically assess the security and privacy trade-offs of putting authorization data (roles/permissions) inside the access token vs. fetching it at the resource server.

This is a genuine architectural fork with no universally right answer; it trades **performance and autonomy** against **freshness, token size, and privacy**. **Token-embedded authZ** stamps roles/permissions/entitlements directly into the JWT at issuance, so the resource server reads them locally with zero extra calls. **Externalized authZ** keeps the token thin (just identity + coarse scopes) and the resource server queries a policy/entitlement source (a database, or a policy engine like OPA/Cedar/AuthZEN) at request time.

```
Embedded (claims in token):        Externalized (query at RS):
  RS: read jwt.roles -> decide       RS: ask policy engine / DB at request time
  + fast, no dependency              + always fresh, fine-grained, no stale window
  + RS autonomous (offline-ish)      + small token, no permission leakage in token
  - STALE until exp (revoke lag)     - per-request dependency + latency
  - token BLOAT (large headers)      - policy service is a hot dependency / SPOF
  - permissions VISIBLE in token     - more moving parts
```

The **security** axis centers on the **staleness window**: embedded permissions are frozen at issuance, so if you revoke a user's admin role or fire an employee, their existing token *still grants admin until `exp`* (Q16/Q52). For low-risk reads that's fine; for high-value or compliance-sensitive actions it's unacceptable — you don't want a just-fired user holding a valid admin token for another 30 minutes. Externalized authZ has no such window (it re-checks live), which is why mature designs **embed only coarse, slow-changing claims** (broad roles/scopes) and **re-check fine-grained, volatile, high-value permissions live** at the resource server — short access TTLs narrow the embedded-staleness gap as a safety net, and CAEP/continuous evaluation (Q52) can push revocation to close it further.

The **privacy** axis is underappreciated: a JWT is **readable by anyone who holds it** (Q34), so embedding a user's roles, entitlements, group memberships, or org structure **leaks that data** into the browser, logs, the client, and any intermediary — a real concern under GDPR/data-minimization and an information-disclosure risk (it tells an attacker who stole a token exactly what it can do and reveals internal authorization structure). Externalized authZ (or **opaque tokens**, Q19) keeps that data server-side. There's also **token bloat**: rich embedded entitlements push JWTs into multiple KB, inflating every request header and sometimes blowing HTTP header limits — another reason to keep tokens lean. **Relationship-based** authorization ("is this user the *owner* of invoice #42?") simply *cannot* live in a token at all — it's per-resource and dynamic — so it must be externalized regardless.

My critical synthesis: **identity and coarse, stable authorization belong in the token** (fast, autonomous, audience-bound); **fine-grained, volatile, high-value, relationship-based, or privacy-sensitive authorization belongs at the resource server** behind a policy engine, re-evaluated per request. The anti-patterns at both extremes: stuffing *everything* into the token (stale, bloated, leaky) or querying a policy service for *every trivial read* (latency, a SPOF on the hot path). The senior move is to **match the placement to the data's volatility, sensitivity, and the action's value** — and to treat the access token as an *authentication + coarse-authorization* artifact, not the authoritative store of every permission.

#### Q83. [Behavioral] Tell me about a time you had to weigh a security improvement against developer experience or delivery speed, and how you decided.

**(Situation/Task)** After a security review, we needed to move our entire microservice estate to **sender-constrained (DPoP) access tokens** to eliminate the standing risk that any leaked bearer token (XSS, a log leak, a misconfigured proxy) could be replayed by an attacker — the residual threat that PKCE and short TTLs don't fully close (Q52). The catch: DPoP adds real client-side complexity (per-request proof signing, key management, replay/nonce handling — Q73), and we had ~40 client teams of wildly varying sophistication and a quarter of committed feature work. A naive mandate "everyone implement DPoP now" would have stalled delivery org-wide and produced dozens of subtly-wrong implementations (the worst outcome — security theater that *looks* protected but isn't).

**(Action)** Rather than treat it as security-vs-speed, I reframed it as a **paving problem**: make the secure path the *easy default* so individual teams pay near-zero DX cost. Concretely: (1) I **scoped the rollout by value** — we did *not* DPoP-bind everything on day one; we identified the high-value flows (payments, admin, anything touching PII) and made those first, accepting that low-risk internal read APIs could stay bearer for now (matching control cost to risk, not gold-plating uniformly). (2) I **built the complexity into shared infrastructure**: a client SDK that handled DPoP key generation, proof signing, and nonce retries transparently (teams just called `httpClient.get(url)` as before), and centralized **validation at the gateway/mesh** so most resource servers needed *no* change at all. (3) I **measured the DX cost honestly** — I piloted with two teams, timed their integration, and used that to set realistic expectations and fix rough edges before the wide rollout, rather than asserting "it's easy." (4) I made the trade-off **explicit and documented** to stakeholders: here's the residual risk we close, here's the cost, here's why we're sequencing high-value flows first.

**(Result)** High-value flows were DPoP-bound within the quarter with *no* slip to those teams' feature work, because the SDK and gateway absorbed the complexity — most teams' "migration" was a dependency bump. The piloting caught a nonce-handling bug that would have caused intermittent failures across 40 teams had we mandated it blind. We deferred low-risk APIs explicitly (with a tracked follow-up), which kept the program from becoming a delivery blocker. A later red-team exercise confirmed a deliberately-leaked token from a high-value service was unusable without the key — the control worked in practice, not just on paper.

**(Reflection)** Two lessons I hold onto: first, **"security vs. DX/speed" is usually a false binary — the real lever is investing in shared tooling so the secure path costs individual teams almost nothing**, which converts a 40-team mandate into a 2-team infrastructure project. Second, **sequence security work by the value of what it protects, and make the trade-off explicit**: gold-plating every endpoint uniformly burns goodwill and delivery time for marginal risk reduction, whereas closing the high-value gaps first — and being honest that the low-risk long tail is a deliberate, tracked deferral — is both more defensible and more likely to actually ship. The decision wasn't "security wins" or "speed wins"; it was "buy down the DX cost so we don't have to choose."

#### Q84. [Practical] How do you handle token and key management across a multi-region, active-active deployment with regional IdP failover?

Active-active multi-region auth has a core tension: token *validation* must be **local and fast in every region** (no cross-region calls on the hot path), yet **signing keys, revocation state, and sessions** are inherently shared concerns that you cannot freely replicate everywhere without security or consistency cost. The design principle is **replicate public/idempotent data everywhere, centralize/coordinate secret and mutable state, and degrade gracefully on partition.**

```
Region US                         Region EU                        Region APAC
  [AS replica] sign w/ regional    [AS replica]                     [AS replica]
   or shared kid (HSM/KMS)          ...                              ...
  [JWKS] <-- read-only replica of ALL regions' public keys (every kid, everywhere) -->
  [RS/gateway] validate JWT locally against the global JWKS (no cross-region call)
  Revocation deny-list (jti/family) <== pub/sub replicated to ALL regions (sub-second) ==>
  Signing private keys: in regional HSM/KMS; NEVER cross regions in the clear
```

**Keys (the crux).** Public verification keys are *not secret*, so the cleanest model is a **union JWKS**: every region publishes its public keys and every resource server's JWKS view contains **all regions' keys**, matched by `kid`. That way a token signed in EU validates in US *locally* — critical for active-active where a user's request can land in any region and tokens roam. Private signing keys, by contrast, must **never traverse regions in plaintext**; either each region has its own HSM/KMS-held signing key (and its `kid` is published globally) or you use a replicated KMS with regional key material. Rotation follows the overlap discipline (Q43) but **globally**: publish the new public key to *every* region's JWKS before any region signs with it, and retire old keys only after the longest token TTL has elapsed across all regions.

**Tokens, sessions, revocation, failover.** Favor **short-lived JWTs** so validation is local and a region partition doesn't break auth (the JWKS is replicated read-only and CDN-frontable — Q55). **Revocation** is the hard part in active-active: a deny-list keyed by `jti`/family must propagate to **every** region's gateways via low-latency pub/sub (Kafka/Redis), and you must consciously accept **eventual consistency** (sub-second propagation) rather than a synchronous global check that would dominate latency — bounding the exposure with short TTLs as the safety net (Q30). For **SSO sessions**, either replicate the IdP session store (with its own consistency cost) or scope sessions regionally and let failover re-establish them; **sticky routing** of a user to their "home" region reduces cross-region session churn but complicates failover. On **regional IdP failover**, because public keys are globally known and JWKS is replicated, *existing tokens keep validating everywhere* even if one region's AS is down — only *new logins* in the failed region need to route to a healthy region. Issuer strategy matters: a **single logical `iss`** behind a global anycast/GeoDNS endpoint (with regional backends) keeps resource-server `iss` pinning simple and lets failover be transparent, rather than per-region issuers that would force multi-issuer validation everywhere (Q43/Q41).

The trade-offs to state explicitly: you trade **strong revocation consistency for availability and latency** (CAP applied to auth) — accepting a sub-second revocation-propagation window and short TTLs instead of a synchronous global authority. You add operational weight (per-region HSMs, global JWKS replication, a revocation pub/sub fabric). The payoff is that **auth survives a region loss with zero cross-region calls on the validation hot path** — which is the entire point of active-active — and the safety net for every consistency gap is the same universal one: **short access-token lifetimes**.

#### Q85. [Theory] What is the threat model of the consent/authorization phase itself, and how do binding messages, transaction confirmation, and exact matching defend it?

Most OAuth security writing focuses on *tokens* — but a whole class of attacks targets the **moment of authorization/consent itself**, before any token exists. The threat model here is that an attacker manipulates *what the user thinks they are approving* versus *what is actually being authorized*, exploiting the gap between the human's mental model and the protocol's parameters. The core threats: **request tampering** (altering `scope`/`redirect_uri`/`authorization_details` in the front channel so the user consents to one thing but a different grant is issued — Q47/Q61), **session fixation / CSRF on the callback** (injecting the attacker's code/grant into the victim's session — Q17), **consent phishing** (a malicious app with a deceptive name/icon tricking the user into granting scopes — Q56), and **transaction confusion** (the user approves "a payment" but the amount/payee was swapped).

```
What the user SEES        vs.   What is actually AUTHORIZED   <- the attacker's leverage
  "Approve £50 to ACME"          £5000 to attacker-account     (tampered authorization_details)
  "Log in to RealApp"            grant to look-alike client    (consent phishing / name squat)
  "Read my profile"              read_all_emails scope         (vague/over-broad consent)
```

The defenses map directly onto the threats. **Exact `redirect_uri` matching** (Q32) closes the open-redirect/code-exfiltration vector at the callback. **`state` + `nonce`** (Q17/Q33) bind the authorization round trip to the user's session and the resulting ID token to *this* request, defeating CSRF/session-fixation and replay. **PAR + JAR** (Q47) move the request to a *signed, back-channel* object so its parameters **cannot be tampered with** between the client and the AS — the user consents to exactly what the client signed. **Rich Authorization Requests (RAR)** plus a **binding message / transaction confirmation** (Q27/Q74) are the defense against *transaction confusion*: instead of a vague scope, the AS shows the user the precise operation ("Approve a payment of £50 to ACME Ltd, ref 7F3K") and — in decoupled flows like CIBA — displays the **same `binding_message` on both the initiating device and the approving device** so the user can confirm the terminal and their phone refer to the *same* transaction (defeating a man-in-the-middle who initiates a different transaction than the one the user believes they're approving). At the platform level, **app verification, scope tiering, and anomaly detection on consent grants** (Q56) defend against consent phishing, since the protocol can't tell a deceptive-but-valid client from an honest one.

The deeper point a senior engineer should articulate: **consent is a security control whose enforcement point is a human**, and humans are the weakest verifier — they habituate to prompts and don't read scope lists (Q56). So the high-assurance answer doesn't *rely* on the user catching the discrepancy; it **removes the attacker's ability to create one**: sign the request so it can't be tampered (PAR/JAR), state the exact transaction so there's nothing ambiguous to confuse (RAR + binding message), bind both devices to one transaction (CIBA binding message), and exact-match the redirect so codes can't be diverted. Where you *must* lean on human judgment (which app to trust), you back it with platform controls (publisher verification, restricted scopes) rather than the consent click alone. The authorization phase is, in short, its own attack surface — and the modern high-assurance profiles (FAPI 2.0) are largely a coordinated hardening of *exactly this phase*.

## ✅ Key Takeaways

- **Authorization Code + PKCE is the one flow to default to** for web, SPA, mobile, and (in 2.1) confidential clients alike. Implicit and ROPC are dead.
- **Distinguish the three tokens:** access (to the API, short-lived), refresh (to the AS only, rotate it), ID (to the client, must be signature-verified). Never use an access token to prove identity — that's OIDC's ID token's job.
- **OAuth = authorization, OIDC = authentication.** "Login with X" almost always uses both.
- **Validate JWTs properly:** verify signature via JWKS, pin the algorithm, check `iss`, `aud`, `exp`, and `nonce`; reject `alg: none`.
- **PKCE, `state`, and (for multi-AS) the `iss` response param** together defend against code interception, CSRF, and mix-up attacks.
- **Refresh-token rotation with reuse detection** is mandatory for public clients; it turns token theft into a self-revoking event.
- **JWT vs. opaque** is a revocation-vs-scalability trade-off; the phantom-token hybrid (opaque outside, JWT inside) is the scalable production answer.
- **Sender-constrained tokens (DPoP/mTLS)** are the structural fix for token theft and the baseline for high-assurance (FAPI 2.0).
- **OAuth 2.1** consolidates the security BCP and removes footguns but leaves fine-grained authZ (RAR), PoP, and logout as separate concerns.

## ⚠️ Common Pitfalls

- **Storing refresh tokens in `localStorage`** in a SPA — XSS-exfiltratable; use a BFF or in-memory + rotation instead.
- **Trusting a JWT without verifying the signature** (just base64-decoding the payload) — a complete auth bypass.
- **Accepting `alg: none` or allowing HS256 where RS256 is expected** — the JWT key-confusion attack.
- **Skipping `aud` validation** — lets a token minted for another API be replayed against yours.
- **Using `exp` as milliseconds** — it's NumericDate (epoch seconds); off-by-1000 bugs cause tokens to look perpetually valid.
- **Wildcard or substring `redirect_uri` matching** — enables open-redirect token theft; require exact string match.
- **Treating logout as "delete the cookie"** — stateless JWTs stay valid until `exp`; you must revoke refresh tokens and use short TTLs / deny-lists.
- **Logging the `Authorization` header** — tokens leak into log aggregators and error trackers; redact them.
- **Putting volatile authorization (roles/permissions) only in long-lived tokens** — stale-permission window; keep access tokens short or re-check sensitive ops live.
- **Confusing scopes with fine-grained authorization** — scopes are coarse; use RAR + a policy engine for "this exact resource/amount".

## 📚 Further Reading

- **RFC 6749** — The OAuth 2.0 Authorization Framework (the canonical spec) and **RFC 6750** (Bearer Token Usage).
- **OAuth 2.1 draft** and **RFC 9700** — OAuth 2.0 Security Best Current Practice (the must-read modern guidance).
- **RFC 7636 (PKCE)**, **RFC 9449 (DPoP)**, **RFC 8705 (mTLS)**, **RFC 9207 (`iss` response param)**, **RFC 9396 (RAR)**, **RFC 9126 (PAR)**.
- **OpenID Connect Core 1.0** and **Discovery 1.0** specs (openid.net) — the authoritative OIDC reference.
- *OAuth 2 in Action* — Justin Richer & Antonio Sanso (Manning) — the best book-length, hands-on treatment.
- **oauth.net**, the **Auth0 docs/blog**, and the **Keycloak documentation** for practical, provider-grounded guidance and the **FAPI 2.0** security profile for high-assurance designs.
