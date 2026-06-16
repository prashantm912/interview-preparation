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
