# API Gateway Patterns

An API Gateway is the single, managed entry point that sits between clients and a fleet of backend services, centralizing cross-cutting concerns such as routing, authentication, rate limiting, aggregation, and observability. This guide covers gateway patterns from fundamentals through staff-level architecture, with Java/Spring Cloud Gateway examples and production trade-offs current through 2026.

[← Back to master index](../README.md)

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is an API Gateway and what core problem does it solve?

An API Gateway is a server that acts as the single entry point for all client requests into a microservices system. Instead of clients calling each microservice directly, they call the gateway, which routes the request to the appropriate downstream service and returns the response. The core problem it solves is the explosion of cross-cutting concerns: in a system with dozens of services, every service would otherwise have to independently implement authentication, TLS, rate limiting, logging, and CORS. The gateway centralizes these concerns at the edge so individual services can focus on business logic.

The "why" is both operational and organizational. Operationally, you get one place to enforce policy and one stable contract for clients even as the internal topology churns. Organizationally, the gateway decouples client teams from service teams — services can be split, merged, or re-addressed without breaking the public API. The trade-off is that the gateway becomes a critical shared component that must be highly available and carefully governed to avoid becoming a bottleneck or a single point of failure.

```
        Without Gateway                    With Gateway
   Client ──► Service A              Client ──► ┌─────────┐ ──► Service A
   Client ──► Service B                         │ Gateway │ ──► Service B
   Client ──► Service C              Client ──► │ (auth,  │ ──► Service C
   (N clients × M services            Client ──►│  rate,  │ ──► Service D
    knowledge of topology)                       │  route) │
                                                  └─────────┘
```

### Q2. [Theory] List the main responsibilities of an API Gateway.

The principal responsibilities are: (1) **Routing** — mapping an incoming path/host/header to a backend service; (2) **Authentication & authorization offload** — validating credentials/tokens at the edge so backends trust pre-authenticated requests; (3) **Rate limiting & throttling** — protecting backends from abuse and noisy neighbors; (4) **Request/response transformation** — rewriting paths, headers, or bodies, and adapting protocols (e.g., REST-to-gRPC); (5) **Aggregation** — composing multiple backend calls into one client response; (6) **TLS termination** — decrypting HTTPS at the edge; (7) **Observability** — emitting metrics, logs, and traces; and (8) **Resilience** — circuit breaking, retries, and timeouts.

The reason these belong at the gateway rather than in each service is consistency and reduced duplication. A subtle point: not everything should be pushed to the gateway. Business-specific authorization (e.g., "can this user edit this document?") usually stays in the service because it needs domain context, while coarse-grained authentication (is this a valid token?) is ideal for the edge.

### Q3. [Theory] What is the difference between an API Gateway and a Load Balancer?

A load balancer (L4 or L7) distributes traffic across identical instances of a service to balance load and provide redundancy; it primarily cares about connection/request distribution and health checking. An API Gateway operates at L7 and is application-aware: it understands routes, parses tokens, applies per-route policy, transforms payloads, and aggregates responses. A load balancer answers "which instance should serve this?"; a gateway answers "what should happen to this request, and where should it go?"

In practice they are layered, not mutually exclusive. A typical edge has a load balancer (e.g., AWS ALB/NLB) in front of multiple gateway instances, and the gateway itself load-balances across each service's instances (often delegating to a service registry or the platform's service mesh). Conflating them is a common interview mistake; the key distinction is that the gateway has rich, policy-driven application logic while the load balancer is a comparatively dumb traffic distributor.

### Q4. [Theory] What does "TLS termination at the gateway" mean and what are its security implications?

TLS termination means the gateway holds the server certificate and private key, decrypts the inbound HTTPS connection, and forwards the request to backends — either as plaintext HTTP or via a new TLS connection (re-encryption). Centralizing TLS at the gateway simplifies certificate management (one place to rotate certs, one place to enforce TLS 1.3 and strong cipher suites) and offloads expensive crypto from backend services.

The security implication is that traffic between the gateway and backends may be unencrypted on the internal network. In a zero-trust posture this is unacceptable, so production systems use **mutual TLS (mTLS)** for gateway-to-service traffic — frequently provided automatically by a service mesh. You must also protect the gateway's private keys (use a KMS/HSM, never bake keys into images), and be aware that terminating TLS gives the gateway plaintext visibility into all traffic, which is powerful for inspection but also a high-value attack target.

### Q5. [Practical] You have a Spring Boot app exposing three microservices. How would you put a basic gateway in front of them?

I'd use **Spring Cloud Gateway** because it integrates naturally with the Spring ecosystem and is reactive (Netty-based), so it handles high concurrency with few threads. The approach: create a dedicated gateway application, define routes that match by path predicate, and strip the path prefix before forwarding. In production I'd add service discovery (Eureka/Consul/Kubernetes DNS) instead of hard-coded URIs, plus a global filter for auth and a redis-backed rate limiter.

```java
// build.gradle: implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
@Configuration
public class GatewayRoutes {
    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
            .route("orders", r -> r.path("/api/orders/**")
                .filters(f -> f.stripPrefix(2)        // drop /api/orders
                               .addRequestHeader("X-Gateway", "scg"))
                .uri("lb://order-service"))            // lb:// = discovery-based LB
            .route("users", r -> r.path("/api/users/**")
                .filters(f -> f.stripPrefix(2))
                .uri("lb://user-service"))
            .route("catalog", r -> r.path("/api/catalog/**")
                .filters(f -> f.stripPrefix(2))
                .uri("lb://catalog-service"))
            .build();
    }
}
```

The trade-off: a single gateway is a SPOF and a deployment coupling point. In production I'd run at least 2–3 stateless gateway replicas behind a load balancer so any instance can serve any request.

### Q6. [Practical] How do you configure simple rate limiting at the gateway?

Spring Cloud Gateway ships a `RequestRateLimiter` filter backed by Redis implementing a token-bucket algorithm. You configure a `replenishRate` (tokens added per second = steady-state RPS), a `burstCapacity` (bucket size = max burst), and a `KeyResolver` that decides the rate-limit key (per user, per IP, per API key). Redis is used so the limit is enforced consistently across all gateway replicas rather than per-instance.

```java
@Bean
KeyResolver userKeyResolver() {
    // Rate-limit per authenticated user; fall back to IP if anonymous
    return exchange -> Mono.just(
        Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("X-User-Id"))
            .orElse(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()));
}
```

```yaml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 100   # 100 req/s sustained
      redis-rate-limiter.burstCapacity: 200    # allow short bursts to 200
      key-resolver: "#{@userKeyResolver}"
```

In production I'd return `429 Too Many Requests` with a `Retry-After` header and emit a metric so we can alert on sustained throttling, which usually signals either abuse or an under-provisioned backend.

### Q7. [Theory] What is the difference between authentication and authorization, and where does each belong relative to the gateway?

Authentication ("authn") answers *who are you?* — verifying the caller's identity, typically by validating a JWT, opaque token, or API key. Authorization ("authz") answers *what are you allowed to do?* — checking whether the authenticated principal may perform a specific action on a specific resource. The gateway is the ideal place for authentication and **coarse-grained** authorization (e.g., "this token has the `orders:read` scope"), because that logic is uniform across services.

**Fine-grained** authorization — "can user 42 read invoice 99 because they belong to the owning org?" — generally belongs in the service, since it needs domain data the gateway shouldn't have to know about. Pushing fine-grained authz into the gateway creates tight coupling and a god-object. A common 2026 pattern is to keep authn + scope checks at the edge and delegate resource-level decisions to a policy engine (OPA/Cedar) invoked by the service.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Compare edge gateway, internal gateway, and the Backend-for-Frontend (BFF) pattern.

An **edge gateway** is the public-facing gateway exposed to untrusted clients (browsers, mobile apps, third parties); it handles WAF, DDoS protection, TLS, authentication, and coarse rate limiting. An **internal gateway** sits inside the trust boundary and routes east-west or partner traffic between internal domains, often with lighter security but richer routing/governance. A **BFF (Backend-for-Frontend)** is a specialized gateway dedicated to one client type — e.g., a Mobile BFF and a Web BFF — that shapes responses precisely for that UI, reducing chattiness and over-fetching.

```
                 ┌──────────────┐
   Web App ─────►│   Web BFF    │──┐
                 └──────────────┘  │   ┌────────────┐
   Mobile App ──►┌──────────────┐  ├──►│  Services  │
                 │  Mobile BFF  │──┤   │ A,B,C,D... │
                 └──────────────┘  │   └────────────┘
   Partners ────►┌──────────────┐  │
                 │ Partner GW   │──┘
                 └──────────────┘
```

The trade-off with BFFs is that you avoid a "one gateway to rule them all" that accumulates conditional logic for every client, at the cost of more deployable units. The classic guidance (from SoundCloud/Netflix, who popularized BFFs) is: one BFF per UI, owned by the team that owns that UI, so client teams can iterate without cross-team coordination.

### Q9. [Theory] API Gateway vs Service Mesh — when do you need each, and can they coexist?

An API Gateway handles **north-south** traffic (clients → system) and focuses on the public API contract: authn, rate limiting, monetization, transformation, aggregation. A service mesh (Istio, Linkerd, Consul Connect) handles **east-west** traffic (service ↔ service inside the cluster) and provides mTLS, fine-grained traffic shaping, retries, and observability transparently via sidecar proxies (or, increasingly in 2026, sidecar-less/ambient meshes like Istio Ambient).

They are complementary and frequently coexist: the gateway is the front door; the mesh is the internal nervous system. A common architecture uses the gateway for ingress and the mesh for service-to-service security and traffic policy. Some products blur the line — e.g., Istio's ingress gateway, or Gloo/Kong running as a mesh ingress — but conceptually the distinction holds: gateway = API-product concerns at the edge, mesh = uniform connectivity/security between many services.

```
   ┌──────────┐   north-south   ┌──────────────────────────────┐
   │  Client  │────────────────►│  API Gateway (edge: authn,    │
   └──────────┘                 │  rate limit, WAF, TLS)        │
                                 └──────────────┬───────────────┘
                                                │
                       ┌────────────────────────┴───────────────────┐
                       │              Service Mesh                    │
                       │  ┌────────┐ mTLS  ┌────────┐ mTLS ┌────────┐ │
                       │  │ Svc A  │◄─────►│ Svc B  │◄────►│ Svc C  │ │
                       │  │+sidecar│       │+sidecar│      │+sidecar│ │  east-west
                       │  └────────┘       └────────┘      └────────┘ │
                       └──────────────────────────────────────────────┘
```

### Q10. [Practical] Compare Kong, Apigee, AWS API Gateway, NGINX, and Spring Cloud Gateway. How would you choose?

- **Kong** — open-source (built on NGINX/OpenResty, with a Rust-based `kong-go`/WASM plugin path in newer versions), plugin-rich, runs anywhere (DB-less declarative or with Postgres), strong for Kubernetes via Kong Ingress Controller. Choose when you want self-hosted control and extensibility.
- **Apigee** (Google) — full API-management platform: monetization, developer portal, analytics, governance. Choose for enterprise API programs where the *business* of APIs (quotas, monetization, partner onboarding) matters as much as routing.
- **AWS API Gateway** — fully managed, deep AWS integration (Lambda, IAM, Cognito, WAF). REST APIs are feature-rich but pricier/higher-latency; HTTP APIs are cheaper and lower-latency. Choose for serverless-first AWS stacks; beware per-request pricing at high volume.
- **NGINX** (and NGINX Plus / Kubernetes Gateway API) — extremely fast, low-level, great as a reverse proxy and L7 LB. Choose when you need raw performance and are comfortable scripting policy (Lua/njs) yourself.
- **Spring Cloud Gateway** — code-first, reactive, ideal when your team is Java/Spring and wants gateway logic in the same language/CI as services. Choose for tight Spring integration and custom filters in Java.

The choice hinges on: build vs buy (managed vs self-host), ecosystem (AWS/GCP/k8s/Spring), feature depth (do you need a developer portal and monetization?), latency budget, and team skills. A bank doing partner API monetization leans Apigee; a Kubernetes shop wanting open-source control leans Kong; a serverless AWS team leans AWS API Gateway; a Java microservices team building custom edge logic leans Spring Cloud Gateway.

### Q11. [Coding] Implement a JWT validation filter as a Spring Cloud Gateway global filter.

**Problem:** Reject requests without a valid, unexpired, correctly-signed JWT, and propagate the user id/roles to downstream services as trusted headers (after stripping any client-supplied copies to prevent spoofing).

```java
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final JwtParser parser; // built once from the signing key / JWKS

    public JwtAuthFilter(@Value("${security.jwt.secret}") String secret) {
        this.parser = Jwts.parserBuilder()
            .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
            .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();

        // 0) Strip any spoofed identity headers BEFORE we set trusted ones
        ServerHttpRequest sanitized = req.mutate()
            .headers(h -> { h.remove("X-User-Id"); h.remove("X-Roles"); })
            .build();

        String auth = sanitized.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing bearer token");
        }
        try {
            Claims claims = parser.parseClaimsJws(auth.substring(7)).getBody();
            // expiry/signature already verified by the parser; add issuer/audience checks:
            if (!"my-issuer".equals(claims.getIssuer())) {
                return unauthorized(exchange, "Bad issuer");
            }
            ServerHttpRequest mutated = sanitized.mutate()
                .header("X-User-Id", claims.getSubject())
                .header("X-Roles", String.valueOf(claims.get("roles")))
                .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "Token expired");
        } catch (JwtException e) {
            return unauthorized(exchange, "Invalid token");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange ex, String reason) {
        ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        ex.getResponse().getHeaders().add("WWW-Authenticate", "Bearer error=\"" + reason + "\"");
        return ex.getResponse().setComplete();
    }

    @Override public int getOrder() { return -100; } // run early, before routing
}
```

**Time/Space:** O(1) per request (HMAC verification is constant-time relative to token size); negligible memory. **Edge cases:** missing/malformed header, expired token, wrong issuer/audience, `alg: none` downgrade (the `parserBuilder` with a fixed key prevents this), and — critically — **stripping client-supplied identity headers** so a caller cannot forge `X-User-Id`. For RS256/asymmetric keys you'd verify against a cached JWKS endpoint and honor key rotation via `kid`.

### Q12. [Coding] Implement a response-aggregation endpoint (gateway/BFF composition) reactively.

**Problem:** A mobile "home" screen needs the user profile, their recent orders, and product recommendations. Calling three services from the device causes 3 round-trips over mobile networks. Aggregate them server-side in one response, in parallel, with a per-call timeout and graceful degradation.

```java
@RestController
public class HomeAggregator {
    private final WebClient web; // reactive, non-blocking HTTP client

    public HomeAggregator(WebClient.Builder b) { this.web = b.build(); }

    @GetMapping("/bff/home")
    public Mono<HomeView> home(@RequestHeader("X-User-Id") String userId) {
        Mono<Profile> profile = get("lb://user-service/users/" + userId, Profile.class)
            .timeout(Duration.ofMillis(300))
            .onErrorReturn(Profile.unknown());            // degrade, don't fail whole page

        Mono<List<Order>> orders = get("lb://order-service/orders?user=" + userId, OrderList.class)
            .map(OrderList::items)
            .timeout(Duration.ofMillis(400))
            .onErrorReturn(List.of());

        Mono<List<Item>> recs = get("lb://reco-service/recommend?user=" + userId, ItemList.class)
            .map(ItemList::items)
            .timeout(Duration.ofMillis(250))
            .onErrorReturn(List.of());                    // recs are optional

        // Mono.zip fans out in parallel and joins when all complete
        return Mono.zip(profile, orders, recs)
            .map(t -> new HomeView(t.getT1(), t.getT2(), t.getT3()));
    }

    private <T> Mono<T> get(String uri, Class<T> type) {
        return web.get().uri(uri).retrieve().bodyToMono(type);
    }
}
```

**Time/Space:** wall-clock latency ≈ max(individual call latencies) instead of the sum, because `Mono.zip` runs them concurrently; O(n) memory for the joined payload. **Edge cases:** partial failure (each call degrades independently via `onErrorReturn`), per-call timeouts so one slow service can't stall the page, and the optional vs required distinction (a failed profile may still warrant a 200 with a placeholder, while a hard dependency might warrant a 503). Avoid blocking calls inside the reactive chain — that would starve the Netty event loop.

### Q13. [Practical] How do you do request and response transformation at the gateway, and when should you avoid it?

Transformation covers path rewriting (`/v1/users` → internal `/users`), header injection/stripping, query manipulation, and body transformation (e.g., camelCase↔snake_case, REST↔gRPC, XML↔JSON for a legacy backend). In Spring Cloud Gateway you use built-in filters (`RewritePath`, `AddRequestHeader`, `SetResponseHeader`, `ModifyRequestBody`, `ModifyResponseBody`) or custom `GatewayFilter`s. The legitimate use cases are protocol adaptation, API versioning facades, and hiding internal naming from clients.

```java
.route("legacy", r -> r.path("/api/v2/**")
    .filters(f -> f
        .rewritePath("/api/v2/(?<seg>.*)", "/${seg}")
        .modifyResponseBody(String.class, String.class,
            (ex, body) -> Mono.just(body.replace("internal_id", "id"))))
    .uri("lb://legacy-service"))
```

You should **avoid** heavy body transformation at the edge because: it forces the gateway to buffer the full payload (hurting streaming and memory), couples the gateway to backend schemas, and embeds business logic in infra. A common production rule: do lightweight header/path transforms freely, but if you find yourself doing complex body mapping, that logic likely belongs in a dedicated adapter service or a BFF, not the shared edge gateway.

### Q14. [Theory] How does service discovery integrate with an API Gateway?

Rather than hard-coding backend IPs/URLs (which change as instances scale, restart, or reschedule), the gateway resolves logical service names to live instances at request time via a service registry — Eureka, Consul, etcd, or Kubernetes DNS/Endpoints. In Spring Cloud Gateway, the `lb://service-name` URI scheme delegates resolution and client-side load balancing to Spring Cloud LoadBalancer, which pulls the instance list from the discovery client. On Kubernetes, the gateway often just targets a `Service` DNS name and lets kube-proxy/CNI handle distribution.

The benefit is dynamic topology: you can autoscale and roll deployments without reconfiguring the gateway. The trade-offs are an added dependency on the registry's availability and consistency, plus the need for health checks so the gateway stops routing to unhealthy instances quickly. Stale registry data is a classic cause of routing traffic to dead pods, so tuning health-check and cache-refresh intervals matters.

### Q15. [Practical] A downstream service is slow and threatens to take down the whole gateway. What patterns do you apply?

This is the cascading-failure / resource-exhaustion problem: slow responses pile up, exhaust the gateway's connection/thread pool, and starve healthy routes. I'd apply a layered resilience strategy:

1. **Timeouts** — aggressive per-route response timeouts so a hung backend frees resources fast.
2. **Circuit breaker** — Resilience4j (`spring-cloud-circuitbreaker-reactor-resilience4j`) to open the circuit after a failure-rate threshold, failing fast and shedding load instead of queuing.
3. **Bulkheads** — isolate connection pools per backend so one slow service can't consume all gateway resources.
4. **Fallbacks** — serve cached or degraded responses when the circuit is open.
5. **Load shedding / rate limiting** — drop excess traffic with 429/503 before it overwhelms the system.

```java
.route("inventory", r -> r.path("/api/inventory/**")
    .filters(f -> f
        .circuitBreaker(c -> c.setName("invCB").setFallbackUri("forward:/fallback/inventory"))
        .retry(retry -> retry.setRetries(2).setMethods(HttpMethod.GET)))
    .uri("lb://inventory-service"))
```

In production I'd retry only idempotent methods, cap retries to avoid retry storms (which *amplify* load on a struggling service), and pair the circuit breaker with good dashboards so an open circuit pages the right team. The principle: fail fast and isolate, rather than letting slow calls queue and propagate.

### Q16. [Theory] How do you handle API versioning at the gateway?

Common strategies: **URI versioning** (`/v1/orders`, `/v2/orders`) — explicit and cache-friendly but pollutes URLs; **header versioning** (`Accept: application/vnd.myapi.v2+json`) — clean URLs but harder to test/debug; and **query-param versioning** (`?version=2`) — simple but easy to misuse. The gateway is the right place to *route* by version because it can map a version to a backend service or transform between versions, letting old and new implementations run side by side.

The strategic value is decoupling client migration from backend evolution: you can route v1 traffic to a stable service and v2 to a new one, then deprecate v1 on a schedule. A useful gateway pattern is a **versioning facade** — the gateway presents v2 externally but translates to an internal v1 backend during migration, so clients move on their own timeline. The trade-off is that supporting many versions multiplies test surface and operational burden, so most teams enforce a deprecation policy (e.g., support N and N-1 only).

---

## 🟠 Advanced (8–12 yrs)

### Q17. [Theory] Explain canary and blue-green deployments at the edge gateway and the trade-offs.

**Blue-green** runs two complete environments — blue (current) and green (new) — and the gateway flips 100% of traffic from blue to green atomically once green is verified, with instant rollback by flipping back. **Canary** shifts a small percentage (e.g., 1% → 5% → 25% → 100%) of traffic to the new version while watching error rates and latency, halting/rolling back automatically if SLOs regress. The gateway implements both via weighted routing.

```
   Blue-Green:   Gateway ──100%──► Blue v1      (then flip)  Gateway ──100%──► Green v2
   Canary:       Gateway ──95%──► v1
                         └─5%───► v2  ──(metrics OK? ramp; bad? rollback)──►
```

```java
.route("checkout-stable", r -> r.path("/api/checkout/**")
    .filters(f -> f.weight("checkout-grp", 95))
    .uri("lb://checkout-v1"))
.route("checkout-canary", r -> r.path("/api/checkout/**")
    .filters(f -> f.weight("checkout-grp", 5))
    .uri("lb://checkout-v2"))
```

Trade-offs: blue-green gives instant cutover/rollback but doubles infrastructure cost and risks a "big bang" exposure (100% hit the new bug at once); it also struggles with non-backward-compatible database migrations. Canary limits blast radius and enables data-driven promotion but is slower, needs solid metric-based gating (e.g., via Argo Rollouts/Flagger), and requires both versions to be backward/forward compatible during the overlap. For sticky behavior I'd pin a user to one version via a consistent hash on user id to avoid flapping between versions mid-session.

### Q18. [Practical] How do you mitigate the API Gateway being a single point of failure (SPOF)?

A gateway centralizes traffic, so its failure can take down the entire API surface — the mitigation is to eliminate every single point in the path. Concretely: (1) run **multiple stateless gateway replicas** (≥2 per AZ) behind a redundant L4/L7 load balancer; (2) spread replicas across **multiple availability zones / regions** with health-based DNS or global load balancing (e.g., Route 53 / Global Accelerator / Anycast); (3) keep the gateway **stateless** — push rate-limit and session state to Redis so any replica can serve any request and you can scale horizontally; (4) externalize config so a bad config push can be rolled back instantly.

Beyond redundancy, you protect against *correlated* failure: a single bad config, a poisoned plugin, or a control-plane outage can fail all replicas at once. So: validate and canary config changes, decouple the data plane from the control plane (the data plane must keep serving with last-known-good config if the control plane is down — Envoy/Kong support this), enforce graceful degradation, and run game-days/chaos tests killing gateway instances. The honest framing in an interview: you can't make the SPOF disappear, but you reduce both the probability and blast radius of failure, and you ensure rapid, automated recovery.

```
        ┌─────────── Region A ───────────┐   ┌─────────── Region B ───────────┐
 DNS/   │  AZ1: GW  GW    AZ2: GW  GW     │   │  AZ1: GW  GW    AZ2: GW  GW     │
 GLB ──►│        └── shared Redis ──┘     │   │        └── shared Redis ──┘     │
        └─────────────────────────────────┘   └─────────────────────────────────┘
            (active)                                (active or failover)
```

### Q19. [Theory] Compare a centralized monolithic gateway with the micro-gateway / sidecar gateway pattern.

A **centralized gateway** is one shared cluster handling all edge traffic — simple to govern and observe, but it can become a deployment bottleneck (every team's route changes flow through one config), a shared blast radius, and an organizational chokepoint. The **micro-gateway** pattern deploys small, purpose-scoped gateways closer to teams/domains (often one per bounded context or as a sidecar next to the service), so teams own their edge logic independently and failures are isolated to a domain.

The trade-off is governance vs autonomy. Centralized gives consistent policy and one place to audit but couples teams; decentralized gives autonomy and isolation but risks policy drift, duplicated effort, and inconsistent security posture. The modern compromise (2026) is a **federated** model: a thin shared edge for truly global concerns (TLS, WAF, DDoS, top-level routing) plus team-owned micro-gateways/BFFs behind it, with policy distributed declaratively (e.g., Kubernetes Gateway API + central policy-as-code via OPA/Kyverno) so you get consistency without a single config bottleneck.

### Q20. [Coding] Implement a Redis-backed sliding-window rate limiter (more accurate than fixed-window).

**Problem:** Fixed-window limiting allows a burst of up to 2× the limit at window boundaries (end of one window + start of the next). Implement a sliding-window-log limiter shared across gateway replicas, executed atomically in Redis to avoid race conditions.

```java
public class SlidingWindowRateLimiter {
    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<Long> script;

    public SlidingWindowRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
        // Atomic Lua: trim old entries, count, conditionally add. Runs server-side in Redis.
        String lua = """
            local key   = KEYS[1]
            local now   = tonumber(ARGV[1])
            local window= tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)   -- drop entries older than window
            local count = redis.call('ZCARD', key)
            if count < limit then
                redis.call('ZADD', key, now, now .. ':' .. math.random())
                redis.call('PEXPIRE', key, window)
                return 1                                            -- allowed
            else
                return 0                                            -- rejected
            end
            """;
        this.script = RedisScript.of(lua, Long.class);
    }

    /** @return Mono<true> if allowed, Mono<false> if over the limit */
    public Mono<Boolean> isAllowed(String clientKey, int limit, Duration window) {
        long nowMs = System.currentTimeMillis();
        return redis.execute(script,
                List.of("rl:" + clientKey),
                List.of(String.valueOf(nowMs),
                        String.valueOf(window.toMillis()),
                        String.valueOf(limit)))
            .single(0L)
            .map(allowed -> allowed == 1L);
    }
}
```

**Approaches & trade-offs:**
- *Fixed window* (counter + TTL): O(1), cheap, but allows boundary bursts up to 2×.
- *Sliding-window log* (above): accurate, but O(N) memory per key proportional to the limit (stores a timestamp per request).
- *Sliding-window counter* (weighted blend of current + previous fixed windows): O(1) memory, ~accurate approximation — best default for very high traffic.

**Time/Space:** the Lua script is O(log N) for the sorted-set ops; memory O(limit) per key for the log variant. **Edge cases:** clock skew across gateway nodes (use Redis `TIME` instead of node clock for correctness), atomicity (the Lua script guarantees no read-modify-write race), key expiry to reclaim memory, and choosing the rate-limit key (per-user vs per-IP vs per-API-key) — IP-based limiting breaks behind shared NAT/CDN.

### Q21. [Coding] Implement an idempotency-key filter at the gateway for safe POST retries.

**Problem:** Mobile clients retry POSTs on flaky networks, causing duplicate orders/payments. Provide exactly-once *effect* by honoring a client-supplied `Idempotency-Key`: the first request executes and its response is cached; retries with the same key return the cached response instead of re-executing.

```java
@Component
public class IdempotencyFilter implements GlobalFilter, Ordered {
    private final ReactiveStringRedisTemplate redis;
    private static final Duration TTL = Duration.ofHours(24);

    public IdempotencyFilter(ReactiveStringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        if (req.getMethod() != HttpMethod.POST) return chain.filter(exchange);

        String key = req.getHeaders().getFirst("Idempotency-Key");
        if (key == null) return chain.filter(exchange);   // optional; could 400 if required

        String redisKey = "idem:" + key;
        // setIfAbsent = atomic "claim" of this key. true => first time, proceed.
        return redis.opsForValue().setIfAbsent(redisKey, "IN_PROGRESS", TTL)
            .flatMap(claimed -> {
                if (Boolean.TRUE.equals(claimed)) {
                    return chain.filter(exchange);        // first request: let it through
                }
                // Replay: a previous request with this key already ran (or is running)
                return redis.opsForValue().get(redisKey).flatMap(cached -> {
                    if ("IN_PROGRESS".equals(cached)) {
                        exchange.getResponse().setStatusCode(HttpStatus.CONFLICT); // 409, retry later
                        return exchange.getResponse().setComplete();
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    exchange.getResponse().getHeaders().add("Idempotency-Replayed", "true");
                    byte[] bytes = cached.getBytes(StandardCharsets.UTF_8);
                    return exchange.getResponse()
                        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                });
            });
        // (Response capture-and-store of the real body would be done via a ModifyResponseBody filter.)
    }
    @Override public int getOrder() { return -50; }
}
```

**Time/Space:** O(1) Redis lookups per request; memory = cached responses × TTL. **Edge cases:** concurrent in-flight retries (the `IN_PROGRESS` marker + 409 prevents double execution — a race that naive implementations miss), key collisions (scope the key per user/endpoint, e.g., `idem:{userId}:{key}`), TTL tuning (long enough to cover client retry windows), and storing the *real* downstream response (production code uses a response-decorating filter to capture and cache the body, omitted for brevity). This pattern is how Stripe and other payment APIs guarantee that a retried charge doesn't double-bill.

### Q22. [Practical] How do you implement end-to-end observability for an API Gateway?

I treat the gateway as the highest-leverage observability point because every request passes through it. The three pillars: **Metrics** — RED metrics (Rate, Errors, Duration) per route, plus p50/p95/p99 latency, 4xx/5xx rates, rate-limit rejections, and circuit-breaker state, exported via Micrometer to Prometheus and visualized in Grafana. **Logs** — structured access logs with a correlation/trace id, latency, route, status, and (carefully scrubbed) identity, shipped to a central store (ELK/Loki). **Traces** — the gateway should *start* a distributed trace (W3C `traceparent`) and propagate it to all downstreams so you get end-to-end spans in an OpenTelemetry backend (Jaeger/Tempo).

```
 Client ─[traceparent]─► Gateway ─[traceparent]─► Svc A ─► Svc B
              │ metrics+logs       │ span            │ span    │ span
              ▼                     ▼                 ▼          ▼
        Prometheus / Grafana   OpenTelemetry Collector ──► Jaeger/Tempo
```

In production: generate a trace id at the edge if the client didn't send one, propagate identity context for audit, set up SLO-based alerting (alert on error-budget burn, not just raw thresholds), and be deliberate about **PII** — scrub tokens, passwords, and personal data from logs to stay compliant (GDPR/PCI). The gateway is also the natural place to detect anomalies (sudden 401 spikes = credential stuffing; 429 spikes = abuse), so I wire its metrics into the security monitoring pipeline too.

### Q23. [Theory] What is a WAF, how does it relate to the API Gateway, and what are its limits?

A Web Application Firewall inspects HTTP traffic to block common attacks — SQL injection, XSS, path traversal, and known bad signatures — typically using rule sets like the OWASP Core Rule Set, plus reputation/IP blocking and bot mitigation. It usually sits *in front of or integrated with* the edge gateway (e.g., AWS WAF on API Gateway/CloudFront, Cloudflare WAF, ModSecurity with NGINX). The gateway handles API-aware policy (authn, routing, rate limits); the WAF handles generic threat filtering at the HTTP layer.

The limits are important to articulate at a senior level: a WAF is signature/heuristic-based, so it produces false positives (blocking legitimate traffic) and false negatives (missing novel or obfuscated attacks), and it cannot understand business-logic abuse (e.g., a valid user enumerating accounts within rate limits). Therefore a WAF is one layer of defense-in-depth, not a substitute for secure coding, input validation in services, proper authz, and rate limiting. Modern WAFs add ML-based anomaly detection and API-schema validation (rejecting requests that violate the OpenAPI contract), which narrows the gap but doesn't close it.

### Q24. [Practical] You're migrating a monolith to microservices behind a gateway (Strangler Fig). How do you route traffic during the migration?

The Strangler Fig pattern uses the gateway as the seam: initially the gateway routes everything to the monolith, then you incrementally carve out endpoints, routing those specific paths to new microservices while the rest still hits the monolith. Over time the new services "strangle" the monolith until it can be decommissioned. The gateway is what makes this invisible to clients — they keep calling the same URLs.

```
  Phase 1            Phase 2                     Phase 3
  Client            Client                       Client
    │                 │                             │
  Gateway          Gateway                       Gateway
    │           ┌─────┴──────┐               ┌──────┼───────┐
  Monolith   /orders→OrderSvc  rest→Monolith  →Svc1 →Svc2 →Svc3
                                              (monolith gone)
```

In production I'd: route by path/feature flag so I can cut over and roll back per endpoint; run new services in **shadow/mirror mode** first (the gateway duplicates traffic to the new service without using its response) to validate correctness under real load; keep the data layer carefully managed (often the hardest part — dual writes or change-data-capture during the overlap); and gate each cutover behind canary + metrics. The trade-off is a longer migration with a hybrid system to operate, but vastly lower risk than a big-bang rewrite. This is exactly how teams like Amazon and many banks decomposed their monoliths.

---

## 🔴 Expert (15+ yrs)

### Q25. [Theory] How would you design a multi-tenant API gateway platform serving thousands of internal teams?

The core tension is **central governance vs team autonomy at scale**. I'd build a federated platform: a thin, highly-available shared data plane (Envoy-based, e.g., via the Kubernetes Gateway API or a managed control plane) for global concerns — TLS, WAF, DDoS, top-level host routing, and platform-wide rate limits — and delegate per-team routing/policy to declarative config the teams own (GitOps), validated and reconciled by a central control plane. Tenant isolation is enforced at multiple layers: per-tenant rate limits and quotas, per-tenant auth contexts, resource bulkheads, and (for hard isolation) dedicated gateway pools for high-blast-radius tenants.

Key design decisions: **(1) Data/control-plane split** — the data plane must keep serving on last-known-good config if the control plane is down, so a control-plane outage never blacks out traffic. **(2) Policy-as-code** — OPA/Cedar/Kyverno so security and compliance teams set guardrails (mTLS required, no `alg:none`, mandatory rate limits) that teams can't violate. **(3) Self-service with safety** — teams ship route changes via PRs that are linted, security-scanned, and canaried automatically. **(4) Noisy-neighbor protection** — fair-share scheduling and per-tenant circuit breakers so one tenant's traffic spike or bad backend can't starve others. **(5) Multi-region active-active** with global load balancing. The honest staff-level point: the platform's value is in the *paved road* — making the secure, observable, resilient path the easiest path — not in centralizing every decision.

### Q26. [Theory] Discuss the performance and architectural implications of where you place authentication: gateway, sidecar, or in-service.

Placing authn at the **gateway** centralizes token validation, gives one audit point, and offloads work from services — but for opaque-token introspection it can add a network hop to the auth server per request (mitigated by caching introspection results / using stateless JWTs validated locally against cached JWKS). Placing it in a **sidecar** (mesh) gives per-service enforcement with mTLS identity and keeps the trust boundary tight even for east-west traffic, at the cost of sidecar resource overhead per pod (ambient/sidecar-less meshes in 2026 reduce this). Placing it **in-service** gives maximum context for fine-grained decisions but duplicates logic and risks inconsistency.

The architecturally sound pattern is layered: **authentication and coarse scope checks at the gateway**, **mTLS identity via the mesh** for service-to-service trust, and **fine-grained resource authorization in-service** (often via a local policy engine). A crucial subtlety: even with edge authn, you should not blindly trust gateway-injected identity headers inside the cluster — defense-in-depth means services validate the mesh mTLS identity too, because a single compromised pod shouldn't be able to impersonate any user by forging an `X-User-Id`. Performance-wise, prefer stateless JWT validation at the edge (local, ~microseconds) over per-request introspection; cache JWKS and revocation lists; and watch the JWT-size vs header-overhead trade-off on high-RPS paths.

### Q27. [Practical] Tell me about a time you had to make a hard architectural trade-off involving a gateway under production pressure. [Behavioral]

In a prior role our single centralized Spring Cloud Gateway cluster became a bottleneck: a Black-Friday-class traffic event combined with one slow downstream (a third-party fraud-check API) caused connection-pool exhaustion that degraded *every* route, not just checkout. The pressure was real — revenue was actively dropping. The hard trade-off was speed of recovery vs correctness of the long-term fix.

**Approach:** First, the incident response — I made the on-call decision to (a) cut the fraud-check timeout aggressively and (b) trip a manual circuit breaker to a degraded-but-functional fallback (queue-for-async-review instead of synchronous block), restoring checkout within minutes. That was a deliberate trade-off: we accepted a small fraud-risk window to stop bleeding revenue, with sign-off from the risk owner. **Then the durable fix:** I led work to add per-backend bulkheads (isolated connection pools), Resilience4j circuit breakers per route, and split the monolithic gateway into domain-scoped gateways so a single bad backend couldn't take down unrelated traffic. **Outcome:** the next peak event saw the same third-party API degrade, but blast radius was contained to one route and auto-recovered with no human intervention. **Lesson I emphasize:** the SPOF risk of a shared gateway isn't just about replica count — it's about *failure isolation*, and resilience patterns must be designed in before the incident, not bolted on during it. I also instituted game-days specifically simulating slow downstreams so the team built the muscle memory.

### Q28. [Theory] How do you evolve a gateway architecture for gRPC, GraphQL, WebSockets, and event streaming — not just REST?

A modern edge must handle heterogeneous protocols. **gRPC:** the gateway terminates HTTP/2, can do gRPC-Web translation for browsers (which can't speak raw gRPC), and may offer transcoding (REST↔gRPC) so external clients use JSON while internal services use protobuf — Envoy and Kong support this natively. **GraphQL:** a GraphQL gateway (or federated supergraph via Apollo Federation / GraphQL Mesh) becomes the aggregation layer, resolving a single query across many subgraph services — but this moves significant logic to the edge and requires query-cost analysis and depth limiting to prevent expensive/abusive queries (a real DoS vector). **WebSockets/SSE:** the gateway must support long-lived connections, which breaks the stateless request/response assumption — you need sticky-ish routing, connection-aware load balancing, and careful timeout/keepalive tuning. **Event streaming:** for client-facing streaming you may front Kafka/Pulsar with a gateway that bridges to WebSocket/SSE or a protocol like MQTT.

The architectural implication is that "API Gateway" is becoming a multi-protocol L7 mediation layer, and a single product rarely does all of these equally well. The senior decision is whether to use one converged gateway (operational simplicity, possible feature compromise) or specialized gateways per protocol (best-of-breed, more operational surface). I generally keep REST/gRPC at a converged edge, but treat GraphQL federation and high-volume streaming as specialized layers with their own scaling and security models — especially GraphQL, whose flexible query model makes cost-based rate limiting and persisted queries mandatory rather than optional.

### Q29. [Practical] A new compliance mandate requires per-region data residency and full audit of every API call. How does this reshape your gateway strategy?

Data residency (e.g., EU data must stay in the EU) and full auditability are gateway-shaped problems because the gateway is the chokepoint where you can enforce and record everything. **Residency:** I'd deploy region-pinned gateway+backend stacks and route at the edge based on tenant/data-classification — a global front (Anycast/GLB) directs each request to the correct regional gateway, and the gateway *refuses* to proxy a request whose data class doesn't match the region. Crucially, no cross-region data flow for regulated data, which constrains aggregation (a BFF can't naively join EU + US data) and shapes the whole topology. **Audit:** the gateway emits a tamper-evident, immutable audit record per request (who, what, when, from where, result), shipped to an append-only store (e.g., WORM storage / a hash-chained log), with strict PII handling.

Trade-offs and pitfalls I'd call out: audit logging at full fidelity has cost and latency implications, so I'd log asynchronously with guaranteed delivery (not best-effort) since *losing* an audit record is a compliance failure. Residency complicates global features and DR (your failover region must also be compliant). And the gateway's own access and config changes must be audited (privileged access). The strategic reframe: compliance pushes you from "one global gateway" toward a **regionally-federated** architecture with policy-as-code enforcing residency invariants that engineers cannot accidentally violate — turning a regulatory requirement into an architectural guardrail.

### Q30. [Coding] Design and implement a pluggable filter pipeline (chain-of-responsibility) for a custom gateway core.

**Problem:** Build the extensibility backbone of a gateway: an ordered chain of filters (auth, rate-limit, transform, route) where each filter can short-circuit (e.g., reject) or pass control onward, and new filters can be added without modifying existing ones (open/closed principle). This is the pattern underlying Spring Cloud Gateway, Zuul, and Envoy's HTTP filter chain.

```java
// --- Core abstractions ---
public interface GatewayFilter {
    Mono<Void> filter(RequestContext ctx, FilterChain chain);
    int order();                       // lower runs first
}

public interface FilterChain {
    Mono<Void> next(RequestContext ctx);   // invoke the rest of the chain
}

// --- Chain implementation (chain-of-responsibility) ---
public class DefaultFilterChain implements FilterChain {
    private final List<GatewayFilter> filters;  // pre-sorted by order()
    private final int index;
    private final Function<RequestContext, Mono<Void>> terminal; // the actual proxy call

    public DefaultFilterChain(List<GatewayFilter> filters,
                              Function<RequestContext, Mono<Void>> terminal) {
        this(filters, 0, terminal);
    }
    private DefaultFilterChain(List<GatewayFilter> f, int i,
                               Function<RequestContext, Mono<Void>> t) {
        this.filters = f; this.index = i; this.terminal = t;
    }

    @Override
    public Mono<Void> next(RequestContext ctx) {
        if (index < filters.size()) {
            GatewayFilter current = filters.get(index);
            FilterChain rest = new DefaultFilterChain(filters, index + 1, terminal);
            return current.filter(ctx, rest);   // each filter decides whether to call rest.next()
        }
        return terminal.apply(ctx);             // end of chain: forward to backend
    }
}

// --- Example filters ---
public class AuthFilter implements GatewayFilter {
    public Mono<Void> filter(RequestContext ctx, FilterChain chain) {
        if (!ctx.hasValidToken()) {
            ctx.setStatus(401);
            return Mono.empty();                 // SHORT-CIRCUIT: do not call chain.next()
        }
        return chain.next(ctx);                  // pass control onward
    }
    public int order() { return 0; }
}

public class RateLimitFilter implements GatewayFilter {
    private final SlidingWindowRateLimiter limiter;
    public RateLimitFilter(SlidingWindowRateLimiter l) { this.limiter = l; }
    public Mono<Void> filter(RequestContext ctx, FilterChain chain) {
        return limiter.isAllowed(ctx.clientKey(), 100, Duration.ofSeconds(1))
            .flatMap(ok -> {
                if (!ok) { ctx.setStatus(429); return Mono.empty(); }
                return chain.next(ctx);
            });
    }
    public int order() { return 10; }
}

// --- Wiring ---
List<GatewayFilter> filters = Stream.of(new AuthFilter(), new RateLimitFilter(limiter))
    .sorted(Comparator.comparingInt(GatewayFilter::order))
    .collect(Collectors.toList());

FilterChain chain = new DefaultFilterChain(filters, ctx -> proxyToBackend(ctx));
Mono<Void> result = chain.next(requestContext);
```

**Why this design:** chain-of-responsibility gives O(1) extensibility (add a filter, no edits elsewhere), explicit ordering, and clean short-circuiting. **Time/Space:** O(F) per request where F = number of filters (each runs at most once); O(F) memory for the chain nodes (here implemented immutably for thread-safety, which trades a little allocation for no shared mutable state). **Edge cases:** a filter that forgets to call `chain.next()` silently drops the request (mitigate with framework conventions/tests); ordering bugs (auth must precede routing); exception handling (one filter throwing must not corrupt the chain — wrap with `onErrorResume` to map errors to responses); and reactive correctness — never block inside a filter or you starve the event loop. This mirrors how Envoy composes HTTP filters and how Spring Cloud Gateway orders its `GlobalFilter`s.

### Q31. [Theory] What are the failure modes of putting too much logic in the gateway, and how do you keep it from becoming a distributed monolith?

When a gateway accumulates business logic — orchestration, complex body transformation, domain validation, per-feature conditionals — it becomes a "distributed monolith at the edge": a shared component that every team must change, with a single release pipeline, a shared blast radius, and creeping coupling to every backend's schema. The failure modes are organizational (the gateway team becomes a bottleneck approving everyone's changes), operational (one risky deploy can break unrelated routes), and architectural (business logic is split awkwardly between gateway and services, making reasoning and testing hard).

To prevent this I enforce a clear contract: the gateway owns **cross-cutting infrastructure concerns** (authn, TLS, routing, rate limiting, observability, resilience) and nothing domain-specific. Domain logic and client-specific shaping go into **BFFs or services**, which teams own independently. I treat any request to "just add this one business rule to the gateway" as a smell requiring justification. Structurally, I prefer many small team-owned gateways/BFFs over one fat shared gateway, use policy-as-code to keep global concerns declarative rather than coded ad-hoc, and measure gateway change frequency — if the central gateway's commit rate tracks feature work rather than infra work, logic is leaking in and it's time to push it back out.

---

## ✅ Key Takeaways

- An API Gateway is the single managed entry point that centralizes cross-cutting concerns — routing, authn offload, rate limiting, aggregation, transformation, TLS termination, and observability — so services stay focused on business logic.
- Know the layering: **load balancer** (dumb traffic distribution) < **API gateway** (north-south, API-product concerns) and **service mesh** (east-west, uniform connectivity + mTLS) are complementary, not interchangeable.
- Keep **authentication + coarse authz at the edge**, **mTLS identity in the mesh**, and **fine-grained resource authz in services**; never blindly trust gateway-injected identity headers inside the cluster.
- Resilience is mandatory: timeouts, circuit breakers, bulkheads, retries (idempotent only), and load shedding prevent one slow backend from cascading into a total outage.
- The gateway is a SPOF — mitigate with stateless, multi-AZ/region replicas behind redundant LBs, externalized state in Redis, data/control-plane separation, and canaried config changes.
- Canary and blue-green deployments via weighted routing let you ship safely at the edge; canary limits blast radius, blue-green gives instant rollback.
- BFFs solve client-specific shaping; one BFF per UI, owned by the UI team, avoids a fat shared gateway.
- The gateway is your highest-leverage observability and security point: RED metrics per route, distributed tracing started at the edge, structured audit logs, WAF as defense-in-depth (not a substitute for secure services).
- Guard against the gateway becoming a distributed monolith — it owns infrastructure concerns only; domain logic belongs in services/BFFs.

## ⚠️ Common Pitfalls

- **Trusting client-supplied identity headers** (`X-User-Id`) — always strip and re-inject them after authn at the gateway, and validate mesh identity in-service.
- **Allowing `alg: none` or unverified JWTs** — pin the algorithm and key; validate issuer, audience, and expiry.
- **Putting business logic / heavy body transformation in the shared gateway**, turning it into a deployment bottleneck and distributed monolith.
- **Fixed-window rate limiting** allowing 2× bursts at window boundaries; per-instance (non-shared) limits that don't hold across replicas.
- **Retry storms** — unbounded or non-idempotent retries that amplify load on an already-struggling backend.
- **Blocking calls inside reactive filters** (Spring Cloud Gateway / Netty), starving the event loop and tanking throughput.
- **Treating the gateway as automatically HA** — a single config push, poisoned plugin, or control-plane outage can fail all replicas at once; isolate failures and validate config.
- **IP-based rate limiting behind NAT/CDN/proxies**, which lumps many users under one address; prefer authenticated keys.
- **Logging tokens/PII** in gateway access logs — a compliance and security liability; scrub aggressively.
- **No idempotency support for POSTs**, causing duplicate orders/payments on client retries.
- **GraphQL at the edge without query-cost/depth limits or persisted queries** — an open DoS vector.

## 📚 Further Reading

- *Building Microservices*, 2nd ed. — Sam Newman (chapters on API gateways, BFFs, and service decomposition).
- *Microservices Patterns* — Chris Richardson (API Gateway and BFF patterns; richardson's microservices.io pattern catalog).
- [Spring Cloud Gateway Reference Documentation](https://docs.spring.io/spring-cloud-gateway/reference/) — routes, predicates, filters, and reactive model.
- [Kong Gateway Documentation](https://docs.konghq.com/gateway/) and [AWS API Gateway Developer Guide](https://docs.aws.amazon.com/apigateway/) — concrete managed/self-hosted implementations.
- [Kubernetes Gateway API](https://gateway-api.sigs.k8s.io/) and [Envoy Proxy Documentation](https://www.envoyproxy.io/docs) — the modern, vendor-neutral foundation for L7 routing and filter chains.
- *Release It!*, 2nd ed. — Michael Nygard (stability patterns: circuit breakers, bulkheads, timeouts — the resilience foundation of any production gateway).
