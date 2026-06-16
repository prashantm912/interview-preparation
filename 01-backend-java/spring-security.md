# Spring Security (Advanced)

A staff-level deep dive into Spring Security 6 / Spring Boot 3+: the filter chain architecture, authentication/authorization internals, OAuth2 & JWT, method security, reactive security, and the misconfigurations that turn into CVEs in production.

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

### Q1. [Theory] What is the difference between authentication and authorization in Spring Security?

**Authentication (AuthN)** answers *"who are you?"* — it verifies identity by validating credentials (password, token, certificate) and produces an `Authentication` object stored in the `SecurityContext`. **Authorization (AuthZ)** answers *"what are you allowed to do?"* — it decides whether the now-known principal may access a resource, based on roles/authorities/permissions.

In Spring Security the two concerns are deliberately separated: an `AuthenticationManager` handles AuthN, while `AuthorizationManager` (Spring Security 6) handles AuthZ. The reason this separation matters is that you can swap one without the other — e.g., switch from form login to OAuth2 (AuthN change) while keeping the same `@PreAuthorize` rules (AuthZ unchanged). A common interview trap: a 401 means *not authenticated*, a 403 means *authenticated but not authorized*. Confusing the two leads to broken UX and security holes (returning 403 when you should challenge with 401 leaks that a resource exists).

### Q2. [Theory] How does the Spring Security filter chain work?

Spring Security plugs a single `DelegatingFilterProxy` into the servlet container, which delegates to a `FilterChainProxy` bean. That proxy holds one or more `SecurityFilterChain` instances; each chain has a `RequestMatcher` and an ordered list of filters. For an incoming request, the first chain whose matcher matches handles it (chains are evaluated top-down, **first match wins** — order is critical).

```
HTTP request
   │
   ▼
┌─────────────────────┐
│ DelegatingFilterProxy│  (registered in servlet container)
└─────────┬───────────┘
          ▼
┌─────────────────────┐
│   FilterChainProxy   │  (Spring bean)
└─────────┬───────────┘
          ▼  picks FIRST matching SecurityFilterChain
┌──────────────────────────────────────────────┐
│ SecurityContextHolderFilter                    │  restore context
│ CsrfFilter                                     │
│ LogoutFilter                                    │
│ UsernamePasswordAuthenticationFilter / JWT etc │  AuthN
│ ...                                            │
│ ExceptionTranslationFilter                     │  401/403 handling
│ AuthorizationFilter                            │  AuthZ (last)
└──────────────────────────────────────────────┘
          ▼
   DispatcherServlet → your controller
```

The key insight is that authentication happens *early* and authorization *late* (`AuthorizationFilter` is near the end), with `ExceptionTranslationFilter` sitting just above it to convert `AuthenticationException` → 401 and `AccessDeniedException` → 403.

### Q3. [Practical] How do you configure a `SecurityFilterChain` in Spring Security 6 with the lambda DSL?

In Spring Security 6 the old `WebSecurityConfigurerAdapter` is **removed**. You now expose a `SecurityFilterChain` bean and use the lambda DSL (the `.and()` chaining style is deprecated).

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/public/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .permitAll())
            .logout(LogoutConfigurer::permitAll)
            .csrf(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

Trade-offs: lambda DSL is more verbose for trivial configs but far clearer for complex ones, and it makes the "first match wins" ordering of `requestMatchers` explicit. In production I always put `.anyRequest().authenticated()` (or `.denyAll()`) **last** as a deny-by-default backstop — forgetting it is the single most common cause of accidentally public endpoints.

### Q4. [Theory] What is `UserDetailsService` and when do you implement a custom one?

`UserDetailsService` is a single-method interface: `UserDetails loadUserByUsername(String username)`. Spring Security calls it during username/password authentication to fetch the stored principal (username, encoded password, authorities, account-status flags). The default `DaoAuthenticationProvider` then compares the submitted password against the stored hash via the `PasswordEncoder`.

You implement a custom one whenever your users live somewhere Spring doesn't know about — a JPA `users` table, an LDAP directory, or an external API. The classic mistake is loading the password from the DB and comparing it yourself; you should return the *encoded* hash in the `UserDetails` and let the provider/encoder do the comparison (constant-time, algorithm-aware). Throw `UsernameNotFoundException` for missing users — but note Spring deliberately maps it to `BadCredentials` by default to avoid username enumeration.

### Q5. [Coding] Implement a custom `UserDetailsService` backed by JPA.

**Problem:** Load users from a database table and expose roles as authorities.

```java
@Service
public class JpaUserDetailsService implements UserDetailsService {

    private final UserRepository repo;

    public JpaUserDetailsService(UserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = repo.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        List<GrantedAuthority> authorities = user.getRoles().stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
            .collect(Collectors.toList());

        return User.builder()
            .username(user.getUsername())
            .password(user.getPasswordHash())     // already BCrypt-encoded
            .authorities(authorities)
            .accountLocked(!user.isActive())
            .build();
    }
}
```

**Edge cases:** (1) Always store the *hash*, never plaintext — `password()` expects an encoded value matching your `PasswordEncoder`. (2) Spring prefixes roles with `ROLE_` for `hasRole()` but NOT for `hasAuthority()` — mismatches here silently break authorization. (3) Map `findByUsername` to a single result; a duplicate-username DB lets the first row win and is a real security bug.
**Time/Space:** O(1) DB lookup + O(r) over `r` roles; space O(r).

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Walk through the `AuthenticationManager` / `AuthenticationProvider` flow.

`AuthenticationManager` is the entry point with one method: `Authentication authenticate(Authentication)`. The default implementation, `ProviderManager`, holds a list of `AuthenticationProvider`s and tries each one whose `supports(Class)` matches the token type until one succeeds, throws, or all are exhausted.

```
Authentication (unauthenticated)
        │
        ▼
  AuthenticationManager
   (ProviderManager)
        │ iterate providers
        ├── DaoAuthenticationProvider ──► UserDetailsService + PasswordEncoder
        ├── LdapAuthenticationProvider
        └── (parent ProviderManager as fallback)
        │
        ▼
Authentication (authenticated, with authorities)  → stored in SecurityContext
```

Each provider returns a *fully authenticated* token (credentials erased) on success or throws an `AuthenticationException`. The design is a chain-of-responsibility: it lets you stack multiple auth mechanisms (DB + LDAP + API key) in one app. `ProviderManager` also supports a *parent* manager so child chains can fall back to a shared global one. A subtlety: by default `ProviderManager` *erases credentials* after success to prevent them lingering in memory — disable `eraseCredentials` only if a downstream provider genuinely needs them.

### Q7. [Theory] Why BCrypt over SHA-256, and when would you choose Argon2?

Fast hashes like SHA-256/MD5 are designed for speed, which is exactly wrong for passwords — an attacker with a leaked DB can compute billions of guesses per second on a GPU. **BCrypt** is deliberately slow and has a tunable *work factor* (cost), so you can raise difficulty as hardware improves; it also salts automatically (the salt is embedded in the hash string). The trade-off is BCrypt is CPU-bound only and caps input at 72 bytes.

**Argon2** (id variant) is the modern Password Hashing Competition winner and is *memory-hard*, defending against GPU/ASIC attacks far better than BCrypt; you tune memory, iterations, and parallelism. Choose Argon2id for new high-value systems if you can afford the memory cost and tune it correctly; BCrypt remains a perfectly defensible, battle-tested default. **PBKDF2** is the choice when FIPS compliance is mandated. In Spring, prefer the *delegating* encoder so you can migrate algorithms over time:

```java
@Bean
PasswordEncoder encoder() {
    // stores hashes prefixed like {bcrypt}$2a$10$... or {argon2}$argon2id$...
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

The `{id}` prefix lets old BCrypt hashes and new Argon2 hashes coexist, enabling gradual rehash-on-login migration without forcing a password reset.

### Q8. [Practical] How do you implement stateless JWT authentication as a custom filter?

**Scenario:** A REST API with no server-side session; clients send `Authorization: Bearer <jwt>`. Approach: a `OncePerRequestFilter` that validates the token and populates the `SecurityContext`.

```java
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtDecoder decoder;          // validates signature + exp + iss
    private final UserDetailsService uds;

    public JwtAuthFilter(JwtDecoder decoder, UserDetailsService uds) {
        this.decoder = decoder; this.uds = uds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Jwt jwt = decoder.decode(header.substring(7));
                UserDetails user = uds.loadUserByUsername(jwt.getSubject());
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException ex) {
                // leave context empty → AuthorizationFilter will 401 later
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
```

**Trade-offs / production reality:** I rarely hand-roll this anymore — Spring's OAuth2 Resource Server (Q12) gives signature/issuer/audience validation, JWKS rotation, and `JwtAuthenticationConverter` out of the box. I write a custom filter only for non-standard token formats. Register the filter with `http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)` and set `sessionManagement(s -> s.sessionCreationPolicy(STATELESS))`. Critical: never trust claims without verifying the signature, always check `exp`, and validate `iss`/`aud` to prevent token-substitution attacks.

### Q9. [Theory] How does method-level security (`@PreAuthorize`) work and how does it differ from URL security?

URL security (in the `SecurityFilterChain`) is coarse-grained and pattern-based; it runs in a servlet filter before the request reaches your code. **Method security** is fine-grained: `@EnableMethodSecurity` activates Spring AOP proxies that intercept annotated methods and evaluate SpEL expressions against the current `Authentication`.

```java
@EnableMethodSecurity            // prePostEnabled=true by default in SS6
@Configuration
class MethodSecurityConfig {}

@Service
class AccountService {

    @PreAuthorize("hasRole('ADMIN') or #account.owner == authentication.name")
    public void close(Account account) { /* ... */ }

    @PostAuthorize("returnObject.owner == authentication.name")
    public Account get(Long id) { /* ... */ }

    @PreFilter("filterObject.amount < 10000")     // filters input collection
    public void process(List<Transfer> transfers) { /* ... */ }
}
```

`@PreAuthorize` evaluates *before* the method (can reference arguments), `@PostAuthorize` *after* (can reference `returnObject`, useful but the method already ran — never use it for write side-effects), and `@PreFilter`/`@PostFilter` mutate collections in/out. Because it is AOP-proxy-based, a self-invocation (calling an annotated method from another method in the same bean) bypasses the proxy and the check **does not run** — a frequent and dangerous gotcha. In SS6, prefer `@EnableMethodSecurity` (uses `AuthorizationManager`) over the legacy `@EnableGlobalMethodSecurity`.

### Q10. [Theory] Explain CSRF: the attack, Spring's defense, and when you can disable it.

CSRF (Cross-Site Request Forgery) tricks an authenticated user's browser into submitting an unwanted state-changing request. Because the browser auto-attaches the session cookie, a malicious `<form>` on `evil.com` can POST to your bank using the victim's session — without the attacker ever reading a response. Spring's defense is the **synchronizer token pattern**: the server issues an unpredictable token, requires it on every state-changing request (POST/PUT/DELETE/PATCH), and rejects mismatches. An attacker can't read the token due to the same-origin policy.

You may safely disable CSRF **only** for stateless APIs that authenticate with a `Authorization` header (Bearer/Basic) rather than a cookie — there's no ambient cookie for the browser to abuse. But the moment you store auth in a cookie (even an HttpOnly JWT cookie), CSRF is back on the table. In SS6 the token-repository changed to `CookieCsrfTokenRepository` patterns with `CsrfTokenRequestAttributeHandler`; the old deferred-loading behavior bites SPAs:

```java
http.csrf(csrf -> csrf
    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
```

### Q11. [Practical] How do you configure CORS correctly, and how does it interact with the security filter chain?

CORS controls which *browser origins* may call your API; it's a browser-enforced mechanism (not a server-side security boundary). The pitfall is configuring it in two places or in the wrong order. In Spring, register a `CorsConfigurationSource` and enable it inside the security chain so the `CorsFilter` runs early (before authentication) and correctly handles preflight `OPTIONS`.

```java
@Bean
SecurityFilterChain chain(HttpSecurity http) throws Exception {
    http.cors(Customizer.withDefaults())          // picks up the bean below
        .authorizeHttpRequests(a -> a.anyRequest().authenticated());
    return http.build();
}

@Bean
CorsConfigurationSource corsSource() {
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedOrigins(List.of("https://app.example.com"));  // NEVER "*" with credentials
    cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cfg.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/**", cfg);
    return src;
}
```

**Production reality:** the most common bug is `setAllowedOrigins("*")` combined with `setAllowCredentials(true)` — the spec forbids it and browsers reject it; use `setAllowedOriginPatterns` if you need wildcards with credentials. Also remember CORS is *not* a substitute for authorization — it only stops browsers, not curl/Postman/server-to-server callers.

### Q12. [Coding] Configure an OAuth2 Resource Server validating JWTs with role mapping.

**Problem:** Secure a REST API so it accepts JWTs from an external IdP (e.g., Keycloak/Auth0), validating signature via JWKS and mapping a custom claim to authorities.

```java
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter())))
            .sessionManagement(s -> s.sessionCreationPolicy(
                SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());   // OK: stateless, header-based
        return http.build();
    }

    private JwtAuthenticationConverter converter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            // map Keycloak "realm_access.roles" → ROLE_*
            Map<String, Object> realm = jwt.getClaim("realm_access");
            Collection<GrantedAuthority> auths = new ArrayList<>(scopes.convert(jwt));
            if (realm != null && realm.get("roles") instanceof List<?> roles) {
                roles.forEach(r -> auths.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return auths;
        });
        return conv;
    }
}
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com/realms/myrealm   # auto-discovers JWKS
          audiences: my-api                                    # validate aud (SS6.1+)
```

**Edge cases:** issuer-uri auto-fetches and caches the JWKS, rotating keys transparently. Always validate `aud` so a token minted for another service can't be replayed against yours. For *opaque* tokens use `.opaqueToken()` + introspection instead of `.jwt()`.
**Complexity:** signature verification is O(1) per request (cached keys); a JWKS refresh is rare and amortized.

### Q13. [Theory] Resource Server vs Client vs Authorization Server — what's the difference?

In OAuth2 terms: the **Authorization Server** issues tokens (login, consent, token endpoint) — e.g., Keycloak, Auth0, or the separate `spring-authorization-server` project. The **Client** is your app acting *on behalf of a user* to call protected resources — Spring's `oauth2Client`/`oauth2Login` handles the redirect/authorization-code flow and stores access/refresh tokens. The **Resource Server** is the API that *consumes* tokens and protects data — it only validates incoming tokens (Q12), it never logs anyone in.

```
 Browser ──login──► Authorization Server (issues code → tokens)
    │                      ▲
    │ authorization code   │ token exchange
    ▼                      │
  Client app ──Bearer token──► Resource Server (validates, serves data)
```

A monolith can play multiple roles, but in microservices you typically have one Authorization Server, a BFF/gateway as Client, and many Resource Servers. Confusing Client (needs `oauth2Login`, sessions, redirect URIs) with Resource Server (stateless, validates bearer tokens, no login UI) is a classic architecture-interview slip.

### Q14. [Practical] How do you manage HTTP sessions and prevent session fixation?

**Session fixation** is an attack where the attacker obtains a session ID *before* the victim logs in (e.g., via a planted cookie or URL), tricks the victim into authenticating on that same ID, then reuses it as the now-authenticated victim. Spring's default and correct defense is to **create a new session and migrate attributes on authentication**, invalidating the pre-auth ID.

```java
http.sessionManagement(session -> session
    .sessionFixation(SessionFixationConfigurer::newSession)  // or changeSessionId (default)
    .maximumSessions(1)                       // one session per user
    .maxSessionsPreventsLogin(false)          // newest login wins, evicts oldest
    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
```

`changeSessionId` (Servlet 3.1+, the default) keeps attributes but rotates the ID; `newSession` starts clean. For concurrency control you register an `HttpSessionEventPublisher`. In production I also set cookies `HttpOnly`, `Secure`, and `SameSite=Lax/Strict`, and for clustered deployments back sessions with Spring Session + Redis so no sticky-session load balancing is required. For pure APIs I go `STATELESS` and skip sessions entirely.

### Q15. [Coding] Write a method-security expression with a custom `PermissionEvaluator` for domain-object checks.

**Problem:** Authorize `@PreAuthorize("hasPermission(#doc, 'EDIT')")` based on per-document ownership.

```java
@Component
public class DocumentPermissionEvaluator implements PermissionEvaluator {

    private final DocumentAclRepository acl;

    public DocumentPermissionEvaluator(DocumentAclRepository acl) { this.acl = acl; }

    @Override
    public boolean hasPermission(Authentication auth, Object target, Object permission) {
        if (auth == null || !(target instanceof Document doc)) return false;
        return acl.findPermissions(doc.getId(), auth.getName())
                  .contains(permission.toString());   // e.g. "EDIT"
    }

    @Override
    public boolean hasPermission(Authentication auth, Serializable id,
                                 String type, Object permission) {
        return acl.findPermissions((Long) id, auth.getName())
                  .contains(permission.toString());
    }
}

@Configuration
@EnableMethodSecurity
class MethodSecurityConfig {
    @Bean
    MethodSecurityExpressionHandler expressionHandler(DocumentPermissionEvaluator pe) {
        var handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(pe);
        return handler;
    }
}
```

**Edge cases:** return `false` (deny) on null/unknown types — never throw, and never default-allow. Cache ACL lookups if `hasPermission` is hot, since it runs per method call. For complex hierarchical permissions, prefer Spring Security ACL (Q19) over hand-rolled lookups.
**Complexity:** O(p) over the user's permission set per check; add caching to avoid an N+1 DB hit per secured call.

---

## 🟠 Advanced (8–12 yrs)

### Q16. [Theory] How does `ExceptionTranslationFilter` decide between 401 and 403, and how do you customize each?

`ExceptionTranslationFilter` wraps the downstream filters in a try/catch. If it catches an `AuthenticationException`, it means the user is *unauthenticated* → it invokes the `AuthenticationEntryPoint` (typically 401 + a `WWW-Authenticate` challenge or a redirect to a login page). If it catches an `AccessDeniedException` from an *already-authenticated* user, it invokes the `AccessDeniedHandler` → 403. If the user is anonymous when access is denied, it *upgrades* the 403 into an authentication challenge (401/redirect) — this is why anonymous users get sent to login rather than a flat 403.

```java
http
  .exceptionHandling(e -> e
    .authenticationEntryPoint((req, res, ex) -> {
        res.setStatus(401);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.getWriter().write("{\"error\":\"unauthorized\"}");
    })
    .accessDeniedHandler((req, res, ex) -> {
        res.setStatus(403);
        res.getWriter().write("{\"error\":\"forbidden\"}");
    }));
```

For pure JSON APIs you almost always override both so you don't get HTML login redirects on a 401. A subtle production point: returning a 403 for resources the user can't *see* can leak existence; some systems intentionally return 404 to mask. The entry point also runs *after* `RequestCache` saves the request, enabling post-login redirect-back.

### Q17. [Practical] You need a custom authentication filter for an API-key/HMAC scheme. How do you integrate it cleanly?

**Scenario:** Partner services authenticate via `X-API-Key` + an HMAC signature of the body, no users/sessions. Approach: implement a filter that builds a custom `Authentication` token and delegates to an `AuthenticationManager`/`AuthenticationProvider`, rather than authenticating inline in the filter — this keeps AuthN logic testable and reusable.

```java
public class ApiKeyFilter extends OncePerRequestFilter {
    private final AuthenticationManager manager;
    public ApiKeyFilter(AuthenticationManager m) { this.manager = m; }

    @Override protected void doFilterInternal(HttpServletRequest req,
            HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        if (key != null) {
            try {
                Authentication result = manager.authenticate(
                    new ApiKeyAuthenticationToken(key, req.getHeader("X-Signature")));
                SecurityContextHolder.getContext().setAuthentication(result);
            } catch (AuthenticationException ex) {
                SecurityContextHolder.clearContext();
                res.sendError(401); return;
            }
        }
        chain.doFilter(req, res);
    }
}
// register: http.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
```

**Trade-offs:** Delegating to an `AuthenticationProvider` (where you verify the HMAC in constant time and load the partner's authorities) separates *parsing the request* from *verifying credentials* — the same pattern Spring's own filters use. Pitfalls: validate HMAC with `MessageDigest.isEqual` to avoid timing attacks, rate-limit by key, and ensure this chain doesn't accidentally also accept session cookies (use a dedicated `securityMatcher("/partner/**")`).

### Q18. [Theory] How do you run multiple `SecurityFilterChain` beans, and why does order matter?

When you need different rules for different URL spaces — e.g., a stateless `/api/**` chain and a session-based `/**` web chain — you declare multiple `SecurityFilterChain` beans, each scoped with `securityMatcher(...)`, and order them with `@Order`. `FilterChainProxy` evaluates chains **in order and stops at the first whose matcher matches**, so a broad matcher placed first will shadow everything below it.

```java
@Bean @Order(1)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .csrf(c -> c.disable());
    return http.build();
}

@Bean @Order(2)
SecurityFilterChain webChain(HttpSecurity http) throws Exception {
    http   // matches everything else
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .formLogin(Customizer.withDefaults());
    return http.build();
}
```

The classic bug: forgetting `@Order` so the web chain (no `securityMatcher`, matches `/**`) ends up first and swallows `/api/**`, applying form-login + CSRF to your JSON API. Put the most specific matcher with the lowest `@Order`. This separation is also how you give the API stateless/CSRF-off semantics while the UI keeps sessions and CSRF on.

### Q19. [Theory] Explain Spring Security ACL (domain object security). When is it worth the complexity?

Spring Security **ACL** provides fine-grained, per-instance authorization: rather than "ADMIN can edit documents," it expresses "user *alice* has WRITE on document #42." It stores ACLs in four tables (`acl_sid`, `acl_class`, `acl_object_identity`, `acl_entry`), supports permission inheritance (a child object inherits a parent's ACL), and integrates via `hasPermission(...)` in SpEL.

```
acl_class ── identifies the domain type (e.g. Document)
acl_object_identity ── one row per secured instance (+ parent pointer for inheritance)
acl_sid ── the principal or role (Security IDentity)
acl_entry ── (objectIdentity, sid, mask, granting) → the actual permission bits
```

It's powerful for collaborative apps (think Google-Docs-style per-document sharing) but heavy: extra tables, cache tuning (`EhCacheBasedAclCache`), and ACL maintenance on every create/delete. In practice I reserve full ACL for genuinely instance-level, user-shareable resources; for everything else a custom `PermissionEvaluator` (Q15) or attribute-based checks are simpler and faster. The trade-off is flexibility vs. operational weight — ACL tables can become a performance and consistency bottleneck at scale.

### Q20. [Practical] How does reactive security (WebFlux) differ from servlet security, and how do you configure it?

WebFlux security is non-blocking and built on `WebFilter`/`SecurityWebFilterChain` instead of servlet `Filter`/`SecurityFilterChain`. The `SecurityContext` is *not* a `ThreadLocal` (a reactive request hops threads) — it lives in the **Reactor `Context`** and is accessed via `ReactiveSecurityContextHolder`. Interfaces are reactive: `ReactiveUserDetailsService`, `ReactiveAuthenticationManager`, `ServerHttpSecurity`.

```java
@Bean
SecurityWebFilterChain springSecurity(ServerHttpSecurity http) {
    return http
        .authorizeExchange(ex -> ex
            .pathMatchers("/public/**").permitAll()
            .anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .build();
}

// accessing the principal reactively:
Mono<String> name = ReactiveSecurityContextHolder.getContext()
    .map(ctx -> ctx.getAuthentication().getName());
```

**Trade-offs:** never call blocking JDBC inside a reactive `ReactiveUserDetailsService` — it stalls the event loop; use R2DBC or offload with `subscribeOn(Schedulers.boundedElastic())`. Method security uses `@EnableReactiveMethodSecurity`, and `@PreAuthorize` works but expressions returning `Mono<Boolean>` are supported. The biggest conceptual shift is that `ThreadLocal`-based `SecurityContextHolder` is unusable — code ported from MVC that reads it directly will silently see `null`.

### Q21. [Coding] Implement a per-user, in-memory rate limiter as a security filter.

**Problem:** Throttle authenticated callers to N requests/second using a token-bucket, returning 429 when exceeded.

```java
public class RateLimitFilter extends OncePerRequestFilter {

    private final int capacity;            // bucket size
    private final double refillPerSec;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int capacity, double refillPerSec) {
        this.capacity = capacity; this.refillPerSec = refillPerSec;
    }

    @Override protected void doFilterInternal(HttpServletRequest req,
            HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String key = (auth != null) ? auth.getName() : req.getRemoteAddr();
        if (buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillPerSec)).tryConsume()) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(429);
            res.setHeader("Retry-After", "1");
        }
    }

    static final class Bucket {
        private final int capacity; private final double refillPerSec;
        private double tokens; private long lastNanos = System.nanoTime();
        Bucket(int cap, double refill) { this.capacity = cap; this.refillPerSec = refill; this.tokens = cap; }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastNanos) / 1e9 * refillPerSec);
            lastNanos = now;
            if (tokens >= 1) { tokens -= 1; return true; }
            return false;
        }
    }
}
```

**Approaches:** This in-memory token bucket is correct for a single node. For a cluster, move to a distributed store (Redis with `INCR`+`EXPIRE` or a Lua token-bucket, or the Bucket4j library) so limits are global. **Edge cases:** unauthenticated requests keyed by IP can be spoofed via `X-Forwarded-For` — only trust it behind a known proxy. Unbounded `ConcurrentHashMap` growth needs eviction (e.g., Caffeine with TTL).
**Complexity:** O(1) per request; space O(k) for k distinct keys (bound it).

### Q22. [Theory] What is `SecurityContextHolder`'s strategy, and what breaks with async / `@Async` / thread pools?

`SecurityContextHolder` stores the current `SecurityContext` and defaults to `MODE_THREADLOCAL` — the context is bound to the request thread and cleared by `SecurityContextHolderFilter` at the end of the request. The problem: any work that hops to a *different* thread (a `@Async` method, a manually submitted `Runnable`, a `CompletableFuture` on a custom executor) does **not** inherit the context, so `SecurityContextHolder.getContext()` returns an empty context downstream and authorization silently fails.

Fixes: (1) `MODE_INHERITABLETHREADLOCAL` propagates to *child* threads created from the request thread — but it does **not** work with pooled threads (the pool's threads are created once, before any auth exists). (2) For thread pools, wrap your executor with `DelegatingSecurityContextExecutor`/`DelegatingSecurityContextExecutorService`, or for `@Async` use a `DelegatingSecurityContextAsyncTaskExecutor`. (3) In reactive code, the context lives in the Reactor context, not here. The interview discriminator is knowing that `INHERITABLETHREADLOCAL` is a trap with pools — it works in dev (fresh threads) and fails under load (reused pool threads carry a stale or empty context).

### Q23. [Practical] A pen-test flags that your app exposes actuator endpoints and stack traces. Walk through hardening.

In production I treat this as defense-in-depth. **Actuator:** move endpoints to a separate management port, expose only `health`/`info` over HTTP, and secure the rest behind a dedicated `SecurityFilterChain` requiring an admin role — never `management.endpoints.web.exposure.include=*` in prod. **Error handling:** disable stack traces (`server.error.include-stacktrace=never`, `include-message=never`) so internal class names and SQL don't leak. **Headers:** Spring Security sends sensible defaults (`X-Content-Type-Options`, `Cache-Control`); I add a strict `Content-Security-Policy`, `Strict-Transport-Security`, and `Referrer-Policy`.

```java
http.headers(h -> h
    .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
    .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny));
```

I also ensure cookies are `Secure`/`HttpOnly`/`SameSite`, force HTTPS (`requiresChannel`), pin dependency versions and run OWASP dependency-check in CI, and verify deny-by-default authorization. The mindset is: every error response, header, and exposed endpoint is an information-disclosure surface; minimize it.

---

## 🔴 Expert (15+ yrs)

### Q24. [Theory] Discuss the security and operational trade-offs of stateful sessions vs. stateless JWTs at scale, including the revocation problem.

The fundamental tension is **revocation vs. statelessness**. Server sessions are trivially revocable — delete the record and the user is logged out instantly — but they require shared session storage (sticky sessions or Redis) and add a lookup per request, which is a horizontal-scaling and latency cost. Stateless JWTs need no server lookup and scale beautifully across services, but you *cannot un-issue a signed token*; until it expires it remains valid even if the user is compromised, fired, or has their roles changed.

Real systems resolve this with a hybrid: short-lived access tokens (5–15 min) + longer refresh tokens, plus a **revocation/denylist** for the access window (a small Redis set of revoked `jti` claims, or a per-user "tokens-issued-after" timestamp checked cheaply). This reintroduces *some* state but bounds it. Other levers: rotating refresh tokens with reuse detection (a replayed refresh token signals theft → revoke the family), audience/issuer scoping to limit blast radius, and asymmetric signing (RS256/ES256) so resource servers verify with a public key and never hold the signing secret. The staff-level answer is that "stateless" is rarely truly stateless once you require real-world revocation — the architecture decision is *where* and *how much* state to keep, traded against latency and revocation SLAs.

### Q25. [Theory] Compare RBAC, ABAC, and ReBAC and how you'd implement each in Spring Security. What does the industry use?

**RBAC** (Role-Based) maps users→roles→permissions; simple, auditable, and what `hasRole`/`@PreAuthorize` natively expresses. It breaks down with role explosion ("contractor-EU-readonly-projectX"). **ABAC** (Attribute-Based) decides from attributes of subject, resource, action, and environment (e.g., "managers can approve expenses < $10k during business hours") — in Spring this maps to rich SpEL in `@PreAuthorize` or a custom `AuthorizationManager`, or externalizing to a policy engine (OPA/Rego, Cedar) queried at runtime. **ReBAC** (Relationship-Based, popularized by Google Zanzibar / and tools like OpenFGA, SpiceDB) answers "is there a path *user → owner → document*?" via a relationship graph — ideal for nested sharing (folders, orgs, teams).

```
RBAC : user ─has─► role ─grants─► permission
ABAC : decide(subject.attrs, resource.attrs, action, env) → permit/deny
ReBAC: check tuple (user, relation, object) over a relationship graph
```

In Spring 6 the clean integration point for ABAC/ReBAC is a custom `AuthorizationManager<RequestAuthorizationContext>` (URL) or a `MethodSecurityExpressionHandler`/policy call (method level) that delegates to OPA/OpenFGA. Industry trend (2024–2026): large multi-tenant SaaS increasingly externalizes authorization into dedicated services (Zanzibar-style) for consistency across polyglot microservices, while keeping coarse RBAC at the edge/gateway. The expert point is to keep the *policy decision point* pluggable so Spring is an enforcement point, not the policy owner.

### Q26. [Practical] Design a zero-downtime migration from BCrypt to Argon2 and from an opaque-token system to JWT, across 200 microservices.

I'd split this into two independent, reversible migrations behind feature flags. **Password migration** uses the `DelegatingPasswordEncoder`: it already stores an `{id}` prefix, so new/changed passwords hash with `{argon2}` while existing `{bcrypt}` hashes still verify. I add *rehash-on-successful-login* (verify with the old encoder, then transparently re-encode with Argon2 and persist) so the corpus migrates organically with zero user impact; a background job can force-expire stragglers after a deadline. No downtime, fully reversible.

**Token migration** is harder across 200 services. Strategy: introduce the new JWT issuance at the Authorization Server *alongside* opaque tokens, and make every Resource Server accept **both** during a transition window — a composite `AuthenticationManager` that tries JWT validation, then falls back to opaque-token introspection. Roll out new issuance gradually (canary by client), monitor, then flip the default. Keep the introspection path until token TTLs guarantee no opaque tokens remain, then remove it. Cross-cutting concerns: centralize the JWKS/issuer config (so key rotation is one change, not 200), add audience scoping per service to limit blast radius, and put the whole thing behind a kill-switch flag. The staff lens here is *backward compatibility windows + observability + reversibility* — never a flag day across that many services.

### Q27. [Behavioral] Tell me about a time a security design decision created friction with product/engineering. How did you handle it?

I anchor this in the STAR format around a real trade-off. **Situation:** product wanted "stay logged in for 90 days" for retention; security wanted short-lived tokens for revocation. **Task:** reconcile UX retention with a defensible revocation SLA. **Action:** rather than win by authority, I quantified the risk — modeled the exposure window of a stolen long-lived token, then proposed short-lived access tokens (15 min) + rotating refresh tokens with reuse detection, which gave product the "stays logged in" feel while bounding compromise. I built a quick prototype and shared the threat model with both sides so the decision was data-driven, not a turf war. **Result:** we shipped the hybrid; when a refresh-token reuse alert fired months later, the family-revocation logic auto-contained an actual token theft.

The meta-point I'd convey: security at staff level is mostly *influence and trade-off articulation*, not gatekeeping. I make the risk legible, offer a menu of options with costs, and let the team own the choice within guardrails — that earns the latitude to hold firm on the few non-negotiables (e.g., never logging credentials, deny-by-default).

### Q28. [Theory] What are the most dangerous and subtle Spring Security misconfigurations you've seen, and how do you systematically prevent them?

The subtle ones share a theme: *security that looks present but isn't*. (1) **`@PreAuthorize` on a self-invoked method** — the AOP proxy is bypassed, so the check silently never runs. (2) **Permit-all ordering** — a broad `permitAll()` or a `SecurityFilterChain` with no `securityMatcher` placed first shadows stricter rules, exposing endpoints. (3) **CSRF disabled on cookie-authenticated flows** — common copy-paste from API examples into a session-based app. (4) **`hasRole` vs `hasAuthority` prefix mismatch** — `ROLE_` confusion makes a rule that never matches, defaulting to deny *or*, worse, a forgotten `anyRequest()` defaulting to permit. (5) **Trusting `X-Forwarded-For`/`X-Forwarded-Proto`** without a trusted proxy, enabling IP/scheme spoofing. (6) **JWT `alg:none` or unvalidated `aud`/`iss`** accepting forged or cross-service tokens. (7) **`INHERITABLETHREADLOCAL` with pooled executors** (Q22) leaking or losing context.

Systematic prevention: deny-by-default (`anyRequest().denyAll()` then open up), integration tests with `@WithMockUser`/`spring-security-test` asserting 401/403 on *every* sensitive endpoint, fail the build if a route lacks a test, static analysis / security linters in CI, OWASP dependency-check for known CVEs, and periodic threat-modeling reviews. The principle: don't rely on humans to remember — encode the invariant as a test or a build gate. A real-world cautionary tale is the class of Spring CVEs (e.g., the 2022 "Spring4Shell" RCE and various `authorizeHttpRequests` migration regressions) where defaults or migration changed behavior subtly — which is why pinning versions and reading the migration guide line-by-line is non-negotiable.

### Q29. [Practical] How would you architect authentication/authorization for a multi-tenant SaaS with per-tenant identity providers (SSO)?

I'd separate *tenant resolution*, *authentication*, and *authorization*. **Tenant resolution** happens first (subdomain, header, or token claim) and pins a tenant context for the request. **Authentication** uses Spring's OAuth2 client/login with a *dynamic* `ClientRegistrationRepository` so each tenant can bring its own IdP (Okta, Azure AD, Google) — I implement a custom repository that resolves the registration by tenant at runtime rather than hard-coding registrations. For tenants without SSO, a fallback local IdP. **Authorization** must be tenant-scoped: every domain query is filtered by tenant (defense-in-depth at the data layer, e.g., Hibernate filters or row-level security), and `@PreAuthorize` checks both role *and* tenant membership so a token from tenant A can never act on tenant B's data.

```
request → resolve tenant → select tenant's ClientRegistration → OAuth2 login/JWT
        → token carries tenant claim → AuthorizationManager checks role + tenant
        → data layer enforces tenant_id filter (belt-and-suspenders)
```

Critical pitfalls at scale: the **confused-deputy / tenant-bleed** bug where a missing tenant filter on one query leaks cross-tenant data — I defend with mandatory tenant scoping at the persistence layer (PostgreSQL RLS or a Hibernate `@Filter` applied globally), not just in service code. JWTs carry a `tenant_id` claim validated alongside `aud`/`iss`. For key management each tenant's IdP has its own JWKS, cached and rotated independently. The expert insight is that in multi-tenant systems *authorization correctness is a data-isolation problem*, so I never trust application-layer checks alone — the database is the last line of defense.

### Q30. [Theory] How do passkeys / WebAuthn and the post-password era change Spring Security architecture?

WebAuthn/FIDO2 (passkeys) replaces shared secrets with public-key cryptography: the authenticator holds a private key, the server stores only the public key, and authentication is a signed challenge — phishing-resistant by design (the credential is origin-bound, so it can't be replayed on a look-alike domain). Spring Security 6.4+ ships first-class WebAuthn support (`webAuthn()` DSL) handling registration and assertion ceremonies. Architecturally this shifts you from "verify a password against a hash" to "verify a signature against a stored credential," eliminating the entire password-storage attack surface (no hashes to leak, no rehash migrations).

The interview-grade nuance: passkeys are *AuthN only* — your authorization model (roles/ABAC/ReBAC) is unchanged, and you still need account recovery, multi-device sync (platform vs. roaming authenticators), and a fallback for legacy clients. Step-up auth (re-prompt for sensitive actions) maps cleanly to WebAuthn assertions. As of 2026 the industry trajectory is passkeys for consumer flows + OIDC federation for enterprise SSO, with passwords relegated to legacy fallback. The staff takeaway: treat the authentication mechanism as pluggable behind a stable authorization core — adopting passkeys should be a filter/provider swap, not a rewrite of your security model, which is exactly the separation Spring Security's `AuthenticationManager`/`AuthorizationManager` split enables.

---

## ✅ Key Takeaways

- **AuthN ≠ AuthZ**: identity (401) vs. permission (403). Keep them separable so mechanisms can evolve independently.
- **Spring Security 6**: no `WebSecurityConfigurerAdapter` — expose `SecurityFilterChain` beans with the lambda DSL; chains are matched first-match-wins, so order and `securityMatcher` are load-bearing.
- **Deny by default**: always end with `anyRequest().authenticated()`/`denyAll()`; never leave the fallthrough open.
- **Passwords**: use a `DelegatingPasswordEncoder` (BCrypt today, Argon2id for high-value), and rehash-on-login to migrate without downtime.
- **JWT/OAuth2**: prefer the OAuth2 Resource Server over hand-rolled filters; always validate signature, `exp`, `iss`, and `aud`; plan for revocation (short TTL + refresh rotation + denylist).
- **Method security is AOP**: self-invocation bypasses `@PreAuthorize`; `@PostAuthorize` runs after side effects.
- **CSRF** matters for cookie auth; disable it only for stateless header-authenticated APIs. **CORS** is a browser control, not an authorization boundary.
- **Reactive** security uses the Reactor context, not `ThreadLocal` — and `INHERITABLETHREADLOCAL` is a trap with thread pools (use `DelegatingSecurityContext*`).
- At scale, externalize the *policy decision* (OPA/OpenFGA/Cedar) and enforce tenant isolation at the data layer, not just in service code.

## ⚠️ Common Pitfalls

- Leaving `management.endpoints.web.exposure.include=*` or stack traces enabled in production (information disclosure).
- `setAllowedOrigins("*")` together with `setAllowCredentials(true)` — invalid and rejected by browsers; use origin patterns.
- `hasRole("ROLE_ADMIN")` (double-prefix) vs `hasAuthority("ROLE_ADMIN")` confusion silently breaking rules.
- Comparing API keys/HMACs with `==`/`.equals()` instead of constant-time `MessageDigest.isEqual` (timing attacks).
- Disabling CSRF on a session-cookie app because an API tutorial said so.
- Forgetting `@Order` on multiple filter chains, letting a broad chain shadow the API chain.
- Assuming `@PreAuthorize` runs on self-invoked or non-proxied calls.
- Reading `SecurityContextHolder` from a pooled/async thread and getting an empty or stale context.
- Treating JWTs as truly stateless while needing instant revocation — design the revocation path up front.
- Trusting `X-Forwarded-For`/`X-Forwarded-Proto` without a trusted reverse proxy.

## 📚 Further Reading

- *Spring Security in Action, 2nd Ed.* — Laurențiu Spilcă (Manning) — the definitive Spring Security 6 book.
- [Spring Security Reference Documentation](https://docs.spring.io/spring-security/reference/) — authoritative, version-specific (read the 5→6 migration guide).
- [OWASP Cheat Sheet Series](https://cheatsheetseries.owasp.org/) — Authentication, Session Management, JWT, CSRF, CORS, and Password Storage cheat sheets.
- *OAuth 2.0 / OpenID Connect* — RFC 6749, RFC 9700 (OAuth 2.0 Security Best Current Practice), and the OpenID Connect Core spec.
- [Google Zanzibar paper](https://research.google/pubs/pub48190/) and OpenFGA / SpiceDB docs — for ReBAC and planet-scale authorization.
- [WebAuthn / FIDO2 specs](https://www.w3.org/TR/webauthn-2/) and the Spring Security WebAuthn guide — for the passkey/post-password era.
