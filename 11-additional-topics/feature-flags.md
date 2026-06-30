# Feature Flags

[← Back to master index](../README.md)

A practical, Java-centric guide to feature flags (a.k.a. feature toggles): the technique of guarding code paths with runtime configuration so you can decouple **deploy** from **release**, ship safely with trunk-based development, run experiments, and kill misbehaving features in seconds. Covers flag types, progressive rollout, targeting, evaluation models, lifecycle/cleanup, governance, the major tooling (LaunchDarkly, Unleash, Flagsmith), and the testing strategies that keep flagged code sane — all current to 2026.

## Table of Contents
- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a feature flag and what core problem does it solve?
A **feature flag** (feature toggle) is a conditional in your code whose value is controlled by external configuration rather than hard-coded, letting you turn functionality on or off **at runtime without redeploying**. The core problem it solves is the tight coupling between **deploying code** and **releasing a feature**. Without flags, a feature becomes live the instant its code reaches production, so you can only ship when the feature is fully done and you must roll back the whole deploy if anything is wrong. With a flag, you can merge and deploy incomplete or risky code in an **off** state, then flip it on independently — for a few internal users, then 1%, then everyone — and flip it off instantly if it misbehaves. The flag turns release into a **runtime decision** instead of a build-time one.

```
Without flags:   deploy ─────────────► release   (same moment, all-or-nothing)
With flags:      deploy ──► [flag off] ──► flip 1% ──► 50% ──► 100%   (or kill switch back to off)
```

### Q2. [Theory] What are the four canonical categories of feature flags (Martin Fowler's taxonomy)?
1. **Release toggles** — hide in-progress or not-yet-finished work so you can merge to trunk and deploy continuously. They are **short-lived** (days to weeks) and should be removed once the feature is fully rolled out.
2. **Experiment toggles** — split users into cohorts for A/B tests; the flag value is the experiment variant. Lifetime equals the experiment duration; decisions must be **consistent per user** over the test window.
3. **Ops toggles** (operational) — let operators control system behavior in production: enable/disable an expensive feature under load, switch to a degraded fallback, or act as a **kill switch**. Some are long-lived "control surfaces."
4. **Permission toggles** (a.k.a. entitlements) — gate features by user, plan, or role (e.g., premium-only features, beta cohorts). These are often **long-lived by design** because they encode product/business rules, not temporary state.

The key axis is **dynamism** (how often the value changes) × **longevity** (how long the flag lives). Release toggles are dynamic-but-short; permission toggles are static-but-long. Misclassifying a flag is a common source of tech debt.

### Q3. [Practical] Show the simplest possible feature flag in Java and explain its weaknesses.
The crudest form is a boolean read from configuration:

```java
public class CheckoutService {
    private final FeatureConfig config;   // reads application.properties / env

    public Receipt checkout(Cart cart) {
        if (config.isEnabled("new-tax-engine")) {
            return newTaxEngine.process(cart);
        }
        return legacyTaxEngine.process(cart);   // fallback path stays alive
    }
}
```

Weaknesses of a config-file boolean: (1) **no runtime change** — flipping it needs a redeploy or at least a restart, defeating the main benefit; (2) **global only** — you can't target 1% of users or a specific segment; (3) **no audit/governance** — who flipped it, when, why? (4) **no consistency guarantees** for experiments. These limitations are exactly why dedicated flag systems (a management UI + an SDK + a streaming/poll update channel) exist. But the *code pattern* — branch on a flag, keep both paths working — is the same at any scale.

### Q4. [Theory] What does "decouple deploy from release" mean and why is it valuable?
**Deploy** = the act of pushing a new binary/artifact to production servers. **Release** = the act of making a feature *visible/active* to users. Without flags these are the same event. Decoupling them means you can deploy code many times a day with all new features dark, then choose **when** and **to whom** each feature becomes live as a separate, low-risk, reversible business decision. Value: (1) deploys become boring and frequent (smaller diffs, easier debugging); (2) release timing aligns with marketing/ops needs, not engineering schedules; (3) rollback of a *feature* is a flag flip (seconds) instead of a redeploy (minutes); (4) it enables progressive delivery — canary, percentage rollout, ring-based release — because "live" is now a dial, not a switch.

### Q5. [Practical] Refactor a config boolean into an SDK-style flag client with a user context. Sketch it.
The upgrade is to pass an **evaluation context** (who is asking) and let the flag system decide per-user:

```java
public Receipt checkout(User user, Cart cart) {
    EvalContext ctx = EvalContext.builder()
        .key(user.id())                 // stable identifier → consistent bucketing
        .attribute("plan", user.plan())
        .attribute("country", user.country())
        .build();

    if (flags.boolVariation("new-tax-engine", ctx, /*default*/ false)) {
        return newTaxEngine.process(cart);
    }
    return legacyTaxEngine.process(cart);
}
```

Two things matter here: the **stable key** (so the same user always gets the same answer) and the **default value** (`false`) — the value returned if the flag service is unreachable or the flag is unknown. Always pick a default that is the *safe* behavior, because the SDK returns it on any failure.

### Q6. [Theory] What is a kill switch and how does it differ from a normal release toggle?
A **kill switch** is an ops toggle whose sole job is to **instantly disable** a feature, integration, or expensive code path in production — typically during an incident. It differs from a release toggle in intent and lifecycle: a release toggle is *off by default* and you flip it *on* to gradually launch; a kill switch is usually *on by default* (the feature is live) and exists so an operator can flip it *off* under duress, even at 3 a.m., without a deploy. Good kill switches: (1) default to the safe state if the flag system is down, (2) are wired to fast, low-dependency evaluation (often cached locally so they work even if the network is degraded), and (3) are documented in the runbook. Classic uses: disable a flaky third-party call, shed load by turning off recommendations, or stop a runaway batch job.

### Q7. [Theory] What is a percentage rollout (percentage targeting)?
A **percentage rollout** exposes a feature to a defined fraction of users — 1%, 5%, 25%, 100% — and lets you ramp that number up over time. Under the hood, the SDK hashes a **stable bucketing key** (usually the user ID, often combined with the flag key and a salt) into a value in `[0, 100)`; if that value falls under the rollout percentage, the user gets the feature. Because the hash is deterministic, a user who is "in" at 5% stays "in" at 25% and beyond — the cohort only grows, never reshuffles. This **stickiness** is essential: it prevents a user from seeing the feature flicker on and off across requests and ensures monotonic, observable rollout.

```
hash(userId + flagKey) % 100  →  bucket
bucket < rolloutPercent       →  ON
   5%:  ████░░░░░░░░░░░░░░░░░░░░  (buckets 0–4)
  25%:  ████████████░░░░░░░░░░░░  (buckets 0–24, superset of the 5% cohort)
```

### Q8. [Coding] Implement deterministic percentage bucketing in Java (the core of a rollout).
**Problem:** Given a user key and a target percentage, decide consistently whether the user is in the rollout. Same input must always give the same answer, and the distribution must be uniform.

```java
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;   // demo; real SDKs use MurmurHash3 / SHA for better uniformity

public final class PercentageRollout {

    /** Returns true if the user falls within `percent` (0..100) of the flag's rollout. */
    public static boolean isIn(String flagKey, String userKey, int percent) {
        if (percent <= 0)   return false;
        if (percent >= 100) return true;

        // Combine flag + user so the same user gets a *different* bucket per flag
        // (otherwise the "unlucky" users are always the same across all features).
        String seed = flagKey + ":" + userKey;
        CRC32 crc = new CRC32();
        crc.update(seed.getBytes(StandardCharsets.UTF_8));

        // Map the 32-bit hash uniformly into [0, 100_000) for fine-grained ramps.
        long bucket = Long.remainderUnsigned(crc.getValue(), 100_000L);
        return bucket < (long) percent * 1_000L;     // percent% of 100_000
    }
}
```

**Time/Space:** O(L) in key length, O(1) memory — cheap enough to evaluate per request. **Edge cases:** (1) include the flag key in the seed so unrelated flags don't all punish the same users; (2) use 100,000 buckets, not 100, so a "0.5% canary" is expressible; (3) production SDKs use MurmurHash3 for better avalanche/uniformity than CRC32 — use a real hash, not `String.hashCode()`, which clusters badly.

### Q9. [Theory] What is user/segment targeting and how does it differ from a percentage rollout?
**Percentage targeting** is anonymous and statistical — "any 10% of users." **User/segment targeting** is rule-based on attributes — "users in Canada," "accounts on the Enterprise plan," "internal employees," "beta opt-ins," "this specific user ID." A **segment** is a named, reusable collection of users defined by rules (e.g., `country == "CA" AND plan == "pro"`) or an explicit list, so you can reference it across many flags. The two combine: a typical rollout rule reads *"serve `true` to the `internal-staff` segment; otherwise serve `true` to 5% of everyone else; otherwise `false`."* Targeting is evaluated **top-down, first match wins**, and a final default applies if no rule matches.

### Q10. [Practical] Where do you put the flag check in your code? Discuss placement and the "fallback path" rule.
Place the flag check at the **highest sensible decision point** and keep both branches as cleanly separated code paths. Anti-pattern: sprinkling the same flag check in 15 deep helper methods — that scatters the toggle point and makes cleanup miserable. Better: branch once at a service boundary (a strategy/factory selection) and let each branch call cohesive code:

```java
TaxEngine engine = flags.boolVariation("new-tax-engine", ctx, false)
        ? newTaxEngine
        : legacyTaxEngine;
return engine.process(cart);
```

The **fallback path rule**: until a flag is removed, the **old path must keep working and keep being tested**. A flag is only safe because either branch is valid. The moment the legacy branch rots (compiles but is broken), your flag is no longer a real toggle — flipping it off won't actually save you. This is why short-lived flags must be deleted promptly: dead fallback paths give a false sense of safety.

### Q11. [Theory] What is trunk-based development and how do feature flags enable it?
**Trunk-based development (TBD)** is a branching model where everyone commits to a single shared branch (`main`/trunk) frequently — at least daily — with very short-lived (or no) feature branches. The problem TBD creates: how do you merge *incomplete* features to trunk without breaking production? **Feature flags are the answer.** You wrap the in-progress work in a release toggle that's **off in production**, merge to trunk continuously, and the unfinished code ships dark every deploy. This avoids long-lived branches and the "big bang merge hell" of GitFlow, keeps integration continuous (so conflicts are small and frequent rather than huge and rare), and lets the team deploy trunk at any time. TBD + flags + CI is the backbone of continuous delivery.

### Q12. [Practical] You're told to "ship a half-finished checkout redesign to trunk." How do you do it safely?
Wrap every entry point of the new design behind a single release flag that defaults **off** in production:

```java
if (flags.boolVariation("checkout-redesign", ctx, false)) {
    return redesignController.handle(request);   // new, half-finished — never reached in prod yet
}
return legacyCheckoutController.handle(request);
```

Then: (1) **merge to trunk daily** behind the off flag — your code integrates continuously but is invisible to users; (2) turn the flag **on in dev/staging** (or for the internal-staff segment) so you and QA can exercise it; (3) when ready, ramp the flag in production (internal → 1% → 25% → 100%); (4) once 100% and stable, **delete the flag and the legacy path**. The key discipline: the new path must never be reachable in production until the flag is intentionally turned on, and the unfinished code must not break the build or the off-path behavior.

### Q13. [Theory] What does it mean for a flag to have a "default value," and why does it matter operationally?
The **default value** (a.k.a. fallback/fail value) is what the SDK returns when it *cannot* get a real answer: the flag service is unreachable, the flag doesn't exist, the SDK hasn't finished initializing, or evaluation throws. It matters because the flag system is now in your request path — if you don't handle its failure, an outage in the flag provider becomes an outage in your app. The rule: **the default must be the safe behavior**, usually "feature off / legacy path." Most SDKs are designed to *never throw* from a `variation()` call — they return the default and log instead — precisely so a flag-service hiccup degrades gracefully rather than 500-ing your users.

### Q14. [Theory] Name three popular feature-flag tools and what distinguishes them.
- **LaunchDarkly** — the dominant commercial SaaS. Mature SDKs for ~30 languages, real-time **streaming** updates (server-sent events) so flag changes propagate in milliseconds, strong targeting/segments, experimentation, governance (approvals, audit log, roles), and enterprise features. You pay per seat/MAU; data plane is their cloud (with relay-proxy options for on-prem evaluation).
- **Unleash** — leading **open-source** option (with a paid hosted/enterprise tier). Self-hostable, so your flag data and evaluation stay in your infrastructure; uses a poll-based SDK model with an optional edge/proxy for client-side. Good for teams that want control and to avoid per-MAU pricing.
- **Flagsmith** — open-source and SaaS, similar self-host story to Unleash, with remote config (flags can carry string/JSON values, not just booleans), good multi-environment support, and a friendly UI. Also offers edge proxies for low-latency client-side eval.

Distinguishing axes to mention: **SaaS vs self-host**, **streaming vs polling** updates, **per-MAU pricing vs open-source**, depth of **experimentation/governance**, and **SDK language coverage**.

---

## 🟡 Intermediate (3–7 yrs)

### Q15. [Theory] Compare client-side vs server-side flag evaluation. What are the trade-offs?
**Server-side evaluation**: the SDK runs inside your backend, which holds the full ruleset and all targeting logic, and computes the variation. The browser/mobile app never sees the rules. **Client-side evaluation**: the flag SDK runs in the user's browser or mobile app.

| Dimension | Server-side | Client-side |
|---|---|---|
| Rule secrecy | Rules stay private | Rules/targeting can leak to the client |
| Trust | Backend is trusted | Client is untrusted → never gate *security* with a client flag |
| Latency | One network hop server↔provider | Needs a proxy/edge; evaluated values pushed to client |
| Bootstrapping | Easy | Must avoid flicker (FOUC) on first paint |
| Use cases | Anything sensitive, server features | UI rendering, web/mobile experiments |

The cardinal rule: **client-side flags are for presentation, never for authorization**. A permission flag that controls access to data or money must be enforced server-side, because anyone can edit client state. Many architectures use a **server-evaluated → client-delivered** model: the backend evaluates flags and hands the client the *resolved values* (not the rules), getting the privacy of server-side with the rendering convenience of client-side.

### Q16. [Theory] Why must flag SDKs typically be initialized as long-lived singletons, and what is "streaming vs polling"?
Flag SDKs cache the entire ruleset **in memory** and evaluate locally (no network call per `variation()`), which is why evaluation is microsecond-fast and resilient. To keep that cache fresh, the SDK maintains a background connection to the provider:
- **Streaming** (SSE/websocket): the provider pushes changes the instant a flag is edited → propagation in ~ms. Best for kill switches.
- **Polling**: the SDK fetches the ruleset every N seconds → simpler, firewall-friendly, but changes lag by up to the poll interval.

Because the SDK owns a persistent connection and an in-memory store, you create **one client per process** (a singleton) and reuse it everywhere. Creating a client per request would open thousands of connections and never warm the cache — a classic mistake. Always `close()` the client on shutdown.

### Q17. [Coding] Configure a LaunchDarkly-style server SDK singleton in Java and evaluate a flag.
**Problem:** Wire up a single shared client, evaluate a boolean and a string (multivariate) flag with a context, and handle graceful shutdown.

```java
import com.launchdarkly.sdk.*;
import com.launchdarkly.sdk.server.*;

public final class Flags {
    private static final LDClient CLIENT =
        new LDClient(System.getenv("LD_SDK_KEY"));   // ONE instance per process

    public static boolean bool(String key, LDContext ctx, boolean def) {
        return CLIENT.boolVariation(key, ctx, def);
    }
    public static String string(String key, LDContext ctx, String def) {
        return CLIENT.stringVariation(key, ctx, def);   // multivariate flag
    }

    public static void shutdown() throws Exception { CLIENT.close(); }
}

// usage
LDContext ctx = LDContext.builder(user.id())
        .set("plan", user.plan())
        .set("country", user.country())
        .build();

boolean newCheckout = Flags.bool("checkout-redesign", ctx, false);
String layout       = Flags.string("homepage-layout", ctx, "control");  // "control"|"variantA"|"variantB"
```

**Notes:** the `boolVariation`/`stringVariation` calls never throw — on any error they return your `def`. The `LDContext` key drives bucketing, so it must be a **stable** identifier. Initialize the client at startup (block briefly for the first ruleset fetch or accept defaults until ready), and reuse it for the app's lifetime.

### Q18. [Theory] Explain multivariate flags. How do they differ from boolean flags?
A **boolean flag** has two states (on/off). A **multivariate flag** can return one of several typed values — strings, numbers, or JSON objects — e.g., `"control" | "variantA" | "variantB"`, or a numeric rollout limit like `maxBatchSize = 500`. This matters for: (1) **A/B/n experiments** with more than two arms; (2) **remote configuration**, where the flag *value* is a parameter you tune live (timeout in ms, feature copy, a JSON config blob) rather than just an on/off; (3) **gradual variant rollouts**. Conceptually a multivariate flag is "remote config with targeting" — you can change a constant's value in production, for specific segments, without a deploy. Beware: JSON-valued flags need schema validation on read, since a bad value can crash the consuming path.

### Q19. [Theory] What is progressive delivery / canary release, and how do flags implement it?
**Progressive delivery** is the practice of rolling out a change to an expanding audience while watching health metrics, so problems are caught at small blast radius. A **canary release** is the first tiny slice (e.g., 1% or one internal ring) that you observe before widening. Flags implement this as a **dial**: start the flag at the `internal-staff` segment, then 1%, watch error rate / latency / business KPIs, then 5%, 25%, 50%, 100% — automatically ramping or halting on metric regressions (some platforms automate this with "guarded rollouts" tied to your metrics). Crucially, if the canary looks bad you **flip the flag back to 0%** (seconds), versus a deployment-based canary that requires rolling the binary back (minutes and more risk). Flags give you *audience* control; deployment canaries give you *infrastructure* control — mature setups use both.

### Q20. [Practical] How do you keep a user's variant *consistent* across requests and devices in an experiment?
You need **sticky bucketing**. Two requirements: (1) bucket on a **stable key** that identifies the same human everywhere, and (2) make the bucketing **deterministic** so no server-side state is required. For logged-in users, key on the **account/user ID** so the same person gets the same variant on web, mobile, and across sessions. For anonymous users, mint a **stable anonymous ID** (a cookie/device ID) and persist it; otherwise each visit re-buckets and pollutes your experiment. When a user logs in, you may need to **reconcile** the anonymous ID with the account ID (and decide whether to honor the pre-login variant) to avoid "variant jumping." Some platforms also offer **persisted bucketing** (storing the assignment) for cases where the rules change mid-experiment but you want existing users to stay in their original arm.

### Q21. [Coding] Implement targeting rules with first-match-wins ordering in Java.
**Problem:** Build a tiny rule evaluator: serve `true` to internal staff, else `true` to a country-based segment at 50%, else `false`.

```java
public record Rule(java.util.function.Predicate<EvalContext> match, int rolloutPercent) {}

public class FlagEvaluator {
    private final String flagKey;
    private final java.util.List<Rule> rules;   // ordered: first match wins
    private final boolean defaultValue;

    public FlagEvaluator(String flagKey, java.util.List<Rule> rules, boolean def) {
        this.flagKey = flagKey; this.rules = rules; this.defaultValue = def;
    }

    public boolean evaluate(EvalContext ctx) {
        for (Rule r : rules) {
            if (r.match().test(ctx)) {                          // first matching rule decides
                return PercentageRollout.isIn(flagKey, ctx.key(), r.rolloutPercent());
            }
        }
        return defaultValue;                                    // no rule matched
    }
}

// wiring
var evaluator = new FlagEvaluator("checkout-redesign", java.util.List.of(
    new Rule(c -> "internal".equals(c.attr("plan")), 100),     // staff: always on
    new Rule(c -> "CA".equals(c.attr("country")),     50),     // Canada: 50%
    new Rule(c -> true,                                5)       // everyone else: 5%
), false);
```

**Time/Space:** O(R) in rule count per evaluation, O(1) memory — fine for the dozens of rules a flag realistically has. **Edge case:** order matters — the staff rule must come *before* the catch-all, or staff would be diluted into the 5% bucket. The final `true`-predicate rule acts as an explicit default rollout.

### Q22. [Practical] What is a "stale flag" and how do you detect them?
A **stale flag** is one that no longer changes behavior because its rollout is effectively permanent — it's at 100% (the feature won), at 0% forever (the feature was abandoned), or hasn't been evaluated/touched in months. They are pure **tech debt**: dead conditionals, untested fallback paths, cognitive load, and risk (someone flips a "100% everywhere" flag and resurrects long-rotted code). Detection signals: (1) flag at 100% or 0% with no recent rule changes; (2) **age** beyond the flag's intended lifetime (release toggles older than, say, 60 days); (3) **evaluation telemetry** showing one variant served 100% of the time, or the flag not evaluated at all; (4) provider "code references" scanning that links the flag to the source lines still using it. LaunchDarkly, Unleash, and Flagsmith all surface stale-flag reports; many teams fail CI or open auto-PRs when a flag exceeds its TTL.

### Q23. [Theory] Describe the lifecycle of a release flag from creation to removal.
1. **Create** — define the flag in the management system *and* add the code branch, default off. Record an owner, a purpose, and an **expected removal date / TTL**.
2. **Test** — turn on in dev/staging and for internal segments; verify both branches.
3. **Roll out** — ramp in production (canary → percentages → 100%) while watching metrics.
4. **Stabilize** — feature at 100% and proven; flag is now redundant.
5. **Clean up** — **delete the flag from the management system and remove the dead code branch** (and the now-unused legacy path), then redeploy. This is the most-skipped step and the source of flag debt.

A flag without a removal plan is a future liability. The discipline "no flag is created without a deletion ticket / expiry" is what keeps the flag count from exploding.

### Q24. [Practical] How do you actually test code that contains feature flags?
You generally test **both sides of the flag**, not just the current production value:
- **Unit tests**: inject a test double / in-memory flag provider and run the test **once per variant** (flag on and flag off), asserting each path's behavior. Most SDKs ship a `TestData` or file-based data source for exactly this.
- **Integration/e2e**: point the SDK at a test environment with known flag states, or override flags per test.
- **Combinatorial caution**: N independent flags create up to 2^N combinations. You don't test all of them — you test each flag's two states in isolation plus the **specific combinations that interact**.

```java
// LaunchDarkly TestData source — deterministic flags in unit tests
TestData td = TestData.dataSource();
td.update(td.flag("new-tax-engine").variationForAll(true));   // force ON

LDConfig cfg = new LDConfig.Builder().dataSource(td).build();
try (LDClient client = new LDClient("sdk-test", cfg)) {
    assertEquals(expectedNewBehavior, service.checkout(user, cart));
}
```

The rule: a flagged code path you can't easily force in a test is a path you can't trust. Keep flag checks shallow and inject the provider so tests can pin variants.

### Q25. [Theory] How do feature flags interact with A/B testing and experimentation?
A multivariate flag is the **delivery mechanism** for an experiment: it randomly but consistently assigns each user a variant. Experimentation adds the **measurement** layer on top: you (1) define a hypothesis and a **primary metric** (e.g., checkout conversion), (2) use the flag to split traffic 50/50 (or n-way) with sticky bucketing, (3) emit an **exposure/impression event** when a user is actually assigned a variant, (4) join exposures to outcome metrics, and (5) run a **statistical test** (frequentist with p-values/confidence intervals, or Bayesian) to decide if a variant truly beat control. Platforms like LaunchDarkly and Optimizely bundle this; Unleash/Flagsmith give you the flagging and you wire metrics to your own analytics. Key correctness concerns: randomization unit must match the analysis unit, log exposure at the point of evaluation (not page load), and avoid **peeking** (don't stop the test the moment it looks significant).

### Q26. [Theory] What is the difference between a feature flag and remote configuration?
They overlap and the line is fuzzy. A **feature flag** classically answers a yes/no *behavioral* question with targeting ("is the new checkout on for this user?"). **Remote config** sets a *value* live ("API timeout = 800ms", "max upload size = 50MB", "banner text = ..."). The modern view: a multivariate/JSON flag *is* remote config with targeting — same infrastructure (management UI + SDK + streaming updates). Practical distinction is intent and lifetime: flags tend to be temporary branches you'll delete; remote config tends to be a long-lived tunable knob. Treat them with the same governance, but classify them so cleanup tooling doesn't try to "expire" a permanent config value.

### Q27. [Practical] A flag controls an expensive recommendation service. How do you wire it as a load-shedding ops toggle?
Make it an **ops kill switch** that defaults to the safe (cheap) behavior and is evaluated with minimal dependencies:

```java
public List<Item> recommendations(EvalContext ctx) {
    // Default false → if flag service is down OR we flip it off under load, we shed cleanly.
    if (!flags.boolVariation("recommendations-enabled", ctx, false)) {
        return cheapPopularItemsFallback();      // degraded but functional
    }
    return expensiveMlRecommender.recommend(ctx);
}
```

Wire it so: (1) the **default is the cheap path**, so a provider outage sheds load instead of hammering the service; (2) the flag is in the runbook and on the on-call dashboard; (3) ideally it's backed by a **locally cached / streaming** value so an operator's flip propagates in seconds even during an incident; (4) optionally automate it — trip the switch when a circuit breaker or latency SLO breaches. This converts "page someone to deploy a fix" into "flip a switch."

### Q28. [Coding] Write a Spring Boot integration that reads a flag and exposes both an evaluation and a kill-switch fallback.
**Problem:** Provide a `@Service` that wraps flag evaluation with a sane default and a circuit-breaker-style fallback.

```java
@Service
public class FeatureService {

    private final LDClient ld;
    public FeatureService(LDClient ld) { this.ld = ld; }   // singleton bean

    public boolean isOn(String flag, Authentication auth, boolean def) {
        LDContext ctx = LDContext.builder(auth.getName())
            .set("roles", auth.getAuthorities().toString())
            .build();
        try {
            return ld.boolVariation(flag, ctx, def);
        } catch (RuntimeException e) {       // SDK shouldn't throw, but be defensive
            log.warn("flag {} eval failed, using default {}", flag, def, e);
            return def;
        }
    }
}

@Configuration
class FlagConfig {
    @Bean(destroyMethod = "close")           // closed on app shutdown
    LDClient ldClient(@Value("${ld.sdk-key}") String key) {
        return new LDClient(key);
    }
}
```

**Notes:** the `LDClient` is a Spring singleton bean (`destroyMethod="close"` handles graceful shutdown). The `def` parameter is the kill-switch-safe value. For a true kill switch, choose `def=false` for "feature off" so unavailability fails safe. **Edge case:** don't create the client per request; let Spring manage the single bean.

### Q29. [Theory] What is "flag debt" and what does it cost a codebase?
**Flag debt** is the accumulated cost of flags that should have been removed but weren't: each lingering flag means a live conditional, a fallback branch that must (in theory) keep working, extra test combinations, and a management entry. Costs: (1) **readability** — code reads as a maze of `if (flag)`; (2) **risk** — a stale "100%" flag whose off-path rotted can be re-enabled and break production; (3) **combinatorial explosion** — N flags → exponential states no one fully tested; (4) **operational confusion** — incident responders can't tell which flags are live levers vs dead weight; (5) **vendor cost** — per-flag/per-MAU pricing. The cure is lifecycle discipline: TTLs, owners, stale-flag reports, "delete after 100%" tickets, and CI that flags expired toggles.

---

## 🟠 Advanced (8–12 yrs)

### Q30. [Theory] How do you architect flag evaluation to survive a flag-provider outage?
Defense in depth, because the provider is now in your request path:
1. **Local in-memory evaluation** — the SDK caches the full ruleset and evaluates locally, so steady-state requests don't call the provider at all. A provider outage only stops *updates*, not *evaluation* — you keep serving the last-known ruleset.
2. **Persistent fallback store** — configure the SDK to use a durable store (Redis/DynamoDB/file) so a process that restarts during an outage can bootstrap the last-known flags instead of cold-starting to defaults.
3. **Safe defaults everywhere** — every `variation()` call passes a default that is the safe behavior, so even a total miss degrades gracefully.
4. **Relay/edge proxy** — run the provider's relay proxy in your own network; your SDKs connect to it, decoupling you from internet/provider blips and centralizing the connection.
5. **Daemon/offline mode** — SDKs can read flags from the shared store written by the relay, so app instances never talk to the provider directly.

The architectural mantra: the flag system should **fail open to the last-known-good state, then to safe defaults** — never hard-fail your app because flags are unreachable.

### Q31. [Theory] Discuss flag governance at organizational scale. What controls do you put in place?
At scale (hundreds of flags, many teams), governance prevents chaos and outages:
- **Ownership & metadata** — every flag has an owner, a description, a type (release/ops/experiment/permission), and a TTL/removal plan. Orphaned flags are reaped.
- **Environments & promotion** — separate dev/staging/prod flag states; promote configurations through environments rather than editing prod by hand.
- **Change controls** — **approval workflows** (a second person approves a prod change to a high-risk flag), **role-based access** (who can edit prod, who can only read), and scheduled/maintenance-window changes.
- **Audit log** — immutable record of who changed what, when, and why — essential for incident forensics and compliance (SOC 2, change management).
- **Naming conventions** — `team-area-purpose` so flags are discoverable.
- **Stale-flag policy** — automated detection + enforcement (reports, CI gates, auto-PRs to delete).
- **Guardrails for permission flags** — entitlement flags that gate access/money get stricter approval and server-side enforcement.

Governance is what turns "flags" from a foot-gun into a reliable release control plane.

### Q32. [Practical] Two flags interact and a 25% × 25% combination caused an outage no one tested. How do you prevent recurrence?
This is the **combinatorial blast radius** problem. Mitigations:
1. **Minimize concurrent risky rollouts** — coordinate so two high-risk flags aren't ramping in the same window; serialize their rollouts.
2. **Model dependencies explicitly** — declare prerequisite relationships (flag B only meaningful when A is on) so the system enforces valid combinations; LaunchDarkly supports flag **prerequisites**.
3. **Test the interacting pairs** — you can't test 2^N, but you *can* identify flags that touch the same code/data and add targeted combination tests.
4. **Shared canary cohort** — route the same internal/canary segment through *all* in-flight flags so the interaction surfaces at 1%, not 25%×25%.
5. **Observability by flag** — emit the active flag set as span/log attributes so when an incident hits, you can immediately see which flag combination the failing requests had.
6. **Reduce flag count** — fewer live flags = fewer interactions; aggressive cleanup is a reliability measure, not just hygiene.

### Q33. [Coding] Implement a flag-aware structured log/trace enrichment so incidents reveal active flags.
**Problem:** When an error occurs, on-call must know which variant the request was on. Attach evaluated flags to the trace/log context.

```java
public List<EvaluatedFlag> evaluateAll(EvalContext ctx, List<String> flags) {
    List<EvaluatedFlag> results = new ArrayList<>();
    for (String key : flags) {
        boolean v = client.boolVariation(key, toLdContext(ctx), false);
        results.add(new EvaluatedFlag(key, v));
        // Enrich the active trace span so it shows up in every downstream log/trace.
        Span.current().setAttribute("flag." + key, v);
        MDC.put("flag." + key, Boolean.toString(v));   // appears in structured logs
    }
    return results;
}
```

**Notes:** Tag the **span** (so traces are filterable by variant) and the **MDC** (so log lines carry the variant). Now an incident query like *"all 500s where `flag.checkout-redesign=true`"* instantly confirms or clears a flag as the culprit. **Caution:** don't log high-cardinality experiment variants for *every* flag if you have hundreds — enrich the handful relevant to the request path, and clear the MDC at request end to avoid thread-pool bleed.

### Q34. [Theory] When should you NOT use a feature flag? Discuss the failure modes of over-flagging.
Flags are not free; avoid them when the cost exceeds the benefit:
- **Trivial/low-risk changes** — a typo fix or a tiny safe change doesn't need a flag; just ship it. Flagging everything bloats flag count.
- **Schema/data migrations** — you can flag the *read/write path*, but the irreversible migration itself isn't a flag; use expand-contract migration patterns, with flags only for the cutover behavior.
- **Security boundaries on the client** — never use a *client-side* flag as the authorization mechanism.
- **Permanent architectural forks** — if you'll *never* delete one branch, that's not a toggle, it's a config or a strategy pattern; model it as such.
- **As a substitute for testing** — "we'll flag it and see" is not a QA strategy.

Over-flagging failure modes: combinatorial state explosion, flag debt, "every line is conditional" unreadability, and a false sense of safety from untested fallback paths. The mature stance: flag *risky, reversible, audience-controlled* changes; don't flag everything.

### Q35. [Practical] How do you migrate a hard-coded behavior to a flag, validate it, and then remove the flag — without risk?
A safe **three-phase** approach:
1. **Introduce (dark)** — add the flag with the *new* path, default off, keeping the old path identical to today's behavior. Deploy. Production behavior is unchanged because the flag is off. Add tests for both branches.
2. **Validate (ramp)** — turn on for internal/canary, then ramp percentages, watching metrics. If anything is wrong, flip to 0% (instant rollback). Optionally run **both paths and compare** (a "scientist"/dark-launch pattern: run old + new, serve old, log diffs) to catch discrepancies before exposing users.
3. **Remove (cleanup)** — once at 100% and stable for a soak period, **delete the flag and the old branch** in a dedicated PR, run the full test suite, deploy. Update the management system to retire the flag.

The discipline that makes this risk-free: never skip phase 3, keep the diff in phase 1 minimal, and have an explicit owner and removal ticket from day one.

### Q36. [Behavioral] Tell me about a time a feature flag (or its absence) caused a production incident. What did you change afterward?
Structure the answer with **situation → action → result → systemic fix**. A strong example: *"We shipped a pricing change behind a release flag but never wired a kill switch into the relevant ops dashboard, so when the new path started returning wrong totals at 50% rollout, the on-call engineer didn't realize a single flag could revert it and instead started a 12-minute redeploy rollback. Customers saw bad prices the whole time."* The point is to show **ownership** and **systemic learning**, not blame: afterward I (1) added a documented kill-switch entry to the runbook for every customer-facing flag, (2) instituted flag enrichment in traces so incidents surface the active variant, (3) added a 'flags in flight' panel to the on-call dashboard, and (4) made 'kill-switch verified' a checklist item in our rollout template. The interviewer is listening for: did you treat flags as a reliability *control plane* with operational maturity, and did you fix the process, not just the bug?

### Q37. [Theory] How does flag evaluation work in client-side / mobile contexts to avoid flicker and protect rules?
On web/mobile you face two problems: **flicker** (the UI renders the default, then jumps when real flags arrive) and **rule leakage** (you don't want targeting logic on an untrusted client). Solutions:
- **Server-evaluated bootstrap** — the backend evaluates flags for this user and ships the *resolved values* (not the rules) embedded in the initial HTML/SSR payload or first API response, so the client renders the correct variant on first paint. No flicker, no rules exposed.
- **Edge/proxy SDKs** — Flagsmith/Unleash/LaunchDarkly edge proxies evaluate near the user and return values, keeping latency low and rules server-side.
- **Sticky anonymous IDs** — persist a stable client ID so re-renders and revisits don't re-bucket.
- **Cache + streaming** — the client caches last-known values and updates via streaming, so a brief network blip doesn't reset the UI.

The guiding principle: evaluate where the data can be trusted, deliver *values* to the client, and never put authorization decisions in client-evaluated flags.

### Q38. [Practical] Your org has 400 flags and no one knows which are safe to delete. How do you drive a cleanup program?
Treat it as a **debt-reduction program**, not a one-off:
1. **Inventory & classify** — pull the full flag list with last-modified, last-evaluated, owner, and type. Tag each: stale (100%/0% & old), active rollout, permanent (permission/config), unknown.
2. **Telemetry-driven detection** — use evaluation analytics to find flags serving a single variant 100% of the time, and **code-reference scanning** to find flags no longer referenced in source (or referenced but never flipped).
3. **Safe-delete the obvious** — flags at 100% with one code path → remove the dead branch; flags with **no code references** → delete from the management system. Do these in small, reviewed PRs.
4. **Assign owners for the rest** — route ambiguous flags to owning teams with a deadline.
5. **Prevent recurrence** — enforce TTLs, require a removal ticket at creation, add CI that fails on flags past expiry, and schedule a recurring "flag hygiene" review.

Communicate the *risk* angle (stale flags are incident risk, not just clutter) to get buy-in, and measure success by **flag count trend**, not a single sweep.

### Q39. [Theory] Compare build-time/compile-time toggles, deploy-time config, and runtime feature flags. When is each appropriate?
- **Build/compile-time toggles** — decided when the artifact is built (e.g., a build profile, conditional compilation). Zero runtime overhead and the disabled code can be stripped entirely, but you **lose all runtime control** — changing the value needs a new build. Appropriate for platform/edition differences (community vs enterprise build) where the choice is truly static.
- **Deploy-time config** — set via env vars/config at deploy (e.g., `FEATURE_X=true` in the manifest). Changeable per environment without a code change, but still requires a redeploy/restart and is **global**, with no per-user targeting.
- **Runtime feature flags** — evaluated per request against a live-updatable ruleset; support targeting, percentage rollout, and instant change with no restart.

Choose the **least dynamic option that meets the need**: don't pay for a runtime flag system for a static edition difference, but don't try to do canary/percentage rollout with deploy-time config. The dynamism you need (per-user? change-without-deploy? instant kill?) dictates the tier.

### Q40. [Coding] Build a typed, testable flag abstraction so flags don't leak vendor APIs across the codebase.
**Problem:** Calling `client.boolVariation("magic-string", ...)` everywhere couples you to one vendor and to stringly-typed keys. Introduce a typed facade.

```java
public interface FeatureFlags {
    boolean enabled(Flag flag, EvalContext ctx);
    <T> T value(TypedFlag<T> flag, EvalContext ctx);
}

// Flags as typed constants — discoverable, refactor-safe, with their own default.
public enum Flag {
    CHECKOUT_REDESIGN("checkout-redesign", false),
    RECOMMENDATIONS_ENABLED("recommendations-enabled", false);
    final String key; final boolean def;
    Flag(String k, boolean d) { this.key = k; this.def = d; }
}

// Production impl wraps the vendor SDK; tests use an in-memory impl.
public class LdFeatureFlags implements FeatureFlags {
    private final LDClient ld;
    public LdFeatureFlags(LDClient ld) { this.ld = ld; }
    public boolean enabled(Flag f, EvalContext ctx) {
        return ld.boolVariation(f.key, toLd(ctx), f.def);
    }
    public <T> T value(TypedFlag<T> f, EvalContext ctx) { /* ... */ return null; }
}

// Test impl — no network, deterministic.
public class FakeFeatureFlags implements FeatureFlags {
    private final Map<String, Boolean> on = new HashMap<>();
    public FakeFeatureFlags on(Flag f)  { on.put(f.key, true);  return this; }
    public boolean enabled(Flag f, EvalContext ctx) { return on.getOrDefault(f.key, f.def); }
    public <T> T value(TypedFlag<T> f, EvalContext ctx) { /* ... */ return null; }
}
```

**Benefits:** vendor isolation (swap LaunchDarkly → Unleash by writing one impl), refactor-safe enum keys (no magic strings), per-flag defaults colocated with the key, and trivially testable code (`new FakeFeatureFlags().on(Flag.CHECKOUT_REDESIGN)`). **Trade-off:** an enum of flags can itself become stale — pair it with cleanup tooling so retired flags are removed from the enum too.

---

## 🔴 Expert (15+ yrs)

### Q41. [Theory] At platform scale, how would you design the flag delivery system for consistency, low latency, and resilience across thousands of services?
Treat flags as a **distributed configuration / control plane** with these properties:
- **Single source of truth** with a versioned ruleset; every change produces a new immutable version (for audit and rollback).
- **Edge-cached evaluation** — push the ruleset to local in-process stores via a fan-out layer (streaming SSE/websocket from a relay tier), so evaluation is local and microsecond-fast; the network is only for *updates*.
- **Consistent propagation** — include a ruleset version in evaluations and logs so you can reason about *which version* served a request; accept that propagation is eventually consistent (a flip reaches all nodes within seconds) and design features to tolerate brief skew.
- **Deterministic bucketing** — a well-distributed hash (MurmurHash3) on a stable key with the flag key + salt, so the *same* user buckets identically on *every* node without shared state.
- **Resilience tiers** — local cache → persistent fallback store → safe defaults; relay proxies inside each region to avoid internet dependence.
- **Multi-region** — regional relays with a global control plane; tolerate cross-region replication lag; never make a request block on a cross-region flag fetch.

The core insight: evaluation must be **local, stateless, and deterministic**; the only thing that crosses the network is the *ruleset*, asynchronously.

### Q42. [Theory] How do experimentation correctness concerns (SRM, peeking, interference) shape a flag platform's design?
A serious experimentation platform must engineer against statistical foot-guns:
- **Sample Ratio Mismatch (SRM)** — if you target 50/50 but observe 53/47, your randomization or logging is broken and results are invalid. The platform should **automatically run an SRM check** and alarm; deterministic, well-distributed bucketing prevents the common cause.
- **Peeking / early stopping** — stopping a test the moment p<0.05 inflates false positives. The platform should support **sequential testing** (always-valid p-values) or fixed-horizon designs and discourage ad-hoc peeking.
- **Exposure logging** — log the assignment **at the point of evaluation**, not page load, so analysis counts only users who actually hit the code; mis-timed exposure biases results.
- **Interference / network effects** — in marketplaces/social graphs, one user's variant affects another, violating independence; may require **cluster randomization**.
- **Carryover & sticky bucketing** — reusing the same users across sequential experiments, or letting a user switch arms mid-test, contaminates results; persisted bucketing and washout periods help.
- **Multiple comparisons** — many metrics × many variants inflate false discoveries; apply corrections.

The design takeaway: the flag layer provides *consistent assignment + reliable exposure events*; the experimentation layer provides *valid statistics*, and they must be co-designed.

### Q43. [Behavioral] How have you led an organization to adopt trunk-based development with flags when teams were attached to long-lived branches?
Frame this as **change leadership**, not a tooling rollout. A credible narrative: *I started by quantifying the pain of the status quo — merge-hell metrics, days-long integration, rollback-by-redeploy incidents — to build a shared "why."* Then a **phased adoption**: pick one willing team as a lighthouse, give them a flag platform and CI gates, and make the new path *easier* than the old (templates, a typed flag facade, examples). Critically, pair the flag mandate with **cleanup discipline from day one** (TTLs, removal tickets) so the org doesn't trade merge debt for flag debt. Address fears directly: "incomplete code in trunk is unsafe" → demonstrate off-by-default flags and both-path testing; "we'll drown in flags" → show stale-flag automation. Measure adoption (deploy frequency, integration time, change-failure rate, flag count trend) and publicize wins. The interviewer wants to hear **influence without authority**, attention to the *socio-technical* system, and that you anticipated the flag-debt failure mode rather than learning it the hard way.

### Q44. [Theory] How do feature flags relate to (and differ from) blue-green and canary *deployment* strategies? When do you combine them?
They operate at different layers and are complementary:
- **Blue-green deployment** — two identical environments (blue=current, green=new); you cut traffic over at the **load-balancer/infrastructure** level and can switch back wholesale. It's coarse (whole-build, all features at once) and is about *infrastructure rollout*.
- **Canary deployment** — route a small % of traffic to new *instances*, watch, widen. Again infrastructure-level and **build-granular**.
- **Feature flags** — per-feature, per-user **application-level** control, independent of which build is running.

Differences: flags give **feature** and **audience** granularity (one feature, specific segment) and instant in-app reversal, while blue-green/canary give **build** granularity and infra-level reversal. You **combine** them: deploy the new build via canary/blue-green (de-risking the *binary*), with new features dark behind flags (de-risking each *feature* independently). Then a feature problem is a flag flip (no redeploy), while an infra/build problem is a deployment rollback. Using both gives you two independent, fast rollback levers at different layers.

### Q45. [Theory] What are the data-privacy, security, and compliance considerations of a flag/experimentation system?
Flags and experiments touch user data and control behavior, so they carry real obligations:
- **Targeting attributes are PII** — country, plan, email, user IDs sent to the flag SDK/provider may be personal data. Minimize what you send, hash/pseudonymize identifiers where possible, and confirm the provider's data residency and DPA terms (GDPR). Self-hosted (Unleash/Flagsmith) keeps this data in your infrastructure.
- **Client-side leakage** — never ship targeting rules or sensitive flags to untrusted clients; deliver only resolved values, and never gate authorization client-side.
- **Audit & change control** — flags change production behavior, so an **immutable audit log**, approvals, and RBAC are needed for SOC 2 / ISO 27001 change-management evidence.
- **Experimentation consent** — A/B testing on certain attributes or in certain jurisdictions may require consent and must respect opt-outs; avoid experimenting on protected attributes in ways that create unfair/discriminatory outcomes.
- **Permission flags as access control** — entitlement flags that gate paid/regulated features must be enforced server-side and treated as security-relevant code, with stricter review.
- **Kill-switch safety** — a flag that can disable security controls is itself a sensitive control surface and must be access-restricted and audited.

The framing for a staff/principal answer: the flag system is **privileged infrastructure** — it can change prod behavior and handle PII — so it inherits security, privacy, and compliance requirements equal to any other production control plane.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q46. [Theory] What actually happens inside `boolVariation()` — walk through the evaluation pipeline of a server SDK.
A `variation()` call is **pure local computation against an in-memory snapshot** — no network call in the steady state. The pipeline is: (1) **look up the flag** by key in the in-memory store; if missing, return the caller's default and emit a "flag not found" diagnostic. (2) **Check the flag's on/off "kill" state** — if globally off, serve the flag's configured *off variation* (not your code default). (3) **Evaluate prerequisites** — if any prerequisite flag fails, serve the off variation. (4) **Evaluate targeting in order**: individual target lists (explicit user IDs) → rule list (first match wins) → the fallthrough/default rollout. (5) For a matched rule with a percentage rollout, **hash the bucketing key** into a bucket and pick the variation. (6) **Type-check** the resulting variation against the requested type (`bool`); on mismatch, return the default. (7) **Emit an evaluation/exposure event** (sampled) for analytics and flag-status telemetry. The whole thing is microseconds because every input — ruleset, segments, prerequisites — is already in memory. The only thing that crossed the network earlier was the *ruleset*, delivered asynchronously by streaming/polling.

#### Q47. [Theory] Why do SDKs return a *default* on unknown flags but an *off variation* on known-but-disabled flags? Aren't both "off"?
They are different states with different sources of truth, and conflating them hides bugs. The **default** you pass in code is a *last-resort* value used when the SDK has **no opinion**: the flag doesn't exist, the SDK hasn't initialized, or the requested type doesn't match. The **off variation** is a value the *flag owner configured in the dashboard* for when the flag is toggled off — it is a deliberate product decision and may not be `false` (an off multivariate flag might serve `"control"`). The practical consequence: if you see your **code default** being served, that's a *signal something is wrong* (typo in the flag key, SDK not ready, flag deleted), whereas the off variation being served is normal operation. Good SDKs expose a "reason" in a detailed evaluation (`boolVariationDetail`) — `FLAG_NOT_FOUND`, `CLIENT_NOT_READY`, `OFF`, `FALLTHROUGH`, `RULE_MATCH`, `PREREQUISITE_FAILED` — precisely so you can distinguish "configured off" from "fell back to default."

#### Q48. [Practical] How can you tell *why* a flag returned the value it did, in production, for one specific user?
Use the SDK's **evaluation-with-detail** API rather than the plain `variation()`. It returns the value plus an **evaluation reason** and the **variation index**, so you can see the exact decision path:

```java
EvaluationDetail<Boolean> detail =
    client.boolVariationDetail("checkout-redesign", ctx, false);

boolean value   = detail.getValue();
EvaluationReason reason = detail.getReason();   // OFF, FALLTHROUGH, RULE_MATCH(i), PREREQUISITE_FAILED, ERROR(kind)
int variationIndex      = detail.getVariationIndex();  // which variation in the flag's list, -1 if default
```

Pair this with **flag enrichment in logs/traces** (attach `flag.checkout-redesign` and its reason to the span) so a support query like "user X says they didn't get the new checkout" becomes answerable: you can confirm whether they hit a `RULE_MATCH`, were excluded by the rollout bucket, or got `CLIENT_NOT_READY` (an init race). Don't call the `*Detail` variant on every request in a hot path — it allocates a reason object; reserve it for debugging endpoints, sampled requests, or error paths.

#### Q49. [Theory] What is a "bucketing key" vs the "context key", and when do they differ?
The **context key** is the stable identifier of the entity being evaluated (usually the user ID). The **bucketing key** is the value actually fed into the rollout hash to decide the percentage bucket. By default they're the same, but they diverge when you **bucket by a different attribute** than you key by. Example: you want a percentage rollout that's consistent **per organization**, not per user, so all users in the same account flip together — you set the rollout to bucket by `org_id` while the context key stays the user ID. Multi-context SDKs make this explicit: a context can have multiple **kinds** (`user`, `org`, `device`), and a rule can say "roll out 10% bucketed by the `org` kind." Getting this wrong is a classic experiment bug: bucketing per user when the feature has org-level side effects means users in the same org see inconsistent behavior and complain.

#### Q50. [Theory] What does "the SDK is eventually consistent" mean for your application logic, and what must you tolerate?
Because evaluation reads a **local in-memory snapshot** updated asynchronously, different nodes (and the same node across a flip) can briefly serve **different ruleset versions**. A flag flipped at the dashboard reaches all instances within milliseconds (streaming) to seconds (polling), but **not atomically**. You must tolerate: (1) **brief skew** — during a flip, some requests see old, some new; design features so a momentary mix isn't catastrophic (e.g., don't assume *all* nodes turned a feature on at the same instant before relying on it). (2) **read-your-writes gaps** — a user who triggers a flag change won't necessarily see it on their very next request if it lands on a not-yet-updated node. (3) **startup races** — an instance that just booted may serve defaults for a few hundred ms until its first ruleset arrives; block on init or accept defaults knowingly. The mental model: flags are an **eventually-consistent config plane**, not a strongly-consistent database — never build logic that requires a globally-atomic flip.

#### Q51. [Coding] Implement a "wait for initialization" guard so you never serve defaults during a cold start.
**Problem:** On boot the SDK store is empty; calls return code defaults until the first ruleset arrives. Block (bounded) for readiness, then proceed, and expose a health signal.

```java
import java.time.Duration;

public final class FlagsBootstrap {

    public static LDClient initOrFailSafe(String sdkKey) {
        // The constructor blocks up to the timeout for the first ruleset fetch.
        LDClient client = new LDClient(sdkKey, Duration.ofSeconds(5));

        if (!client.isInitialized()) {
            // We did NOT get a ruleset in time. Two valid choices:
            //  (a) proceed anyway — every variation() returns safe code defaults; OR
            //  (b) fail the readiness probe so this instance gets no traffic yet.
            log.warn("LD SDK not initialized after 5s; serving safe defaults until it catches up");
        }
        return client;
    }

    /** Wire into the readiness/health endpoint. */
    public static boolean ready(LDClient client) {
        return client.isInitialized();   // true once the first ruleset is in memory
    }
}
```

**Notes:** The key API is `isInitialized()` — it tells you whether evaluations reflect real rules or just defaults. Choosing (b) (fail readiness) is best when serving defaults would be wrong (e.g., a kill switch must reflect the real state before taking traffic). Choosing (a) is fine when your defaults *are* the safe behavior. A persistent fallback store (Redis/file) shortens this window because a restart can bootstrap last-known flags instead of cold-starting empty.

#### Q52. [Theory] Streaming vs polling — what are the actual wire-level mechanics and failure modes of each?
**Streaming** uses **Server-Sent Events (SSE)** — a long-lived HTTP connection over which the provider pushes a `put` (full ruleset) on connect and `patch`/`delete` events on each change. Mechanics: the SDK holds the connection open, applies deltas to its in-memory store, and on disconnect **reconnects with backoff + jitter**. Failure modes: corporate proxies that buffer or kill long connections, idle-timeout intermediaries (mitigated by heartbeat comments), and reconnection storms after a provider blip (mitigated by jittered backoff). **Polling** issues a periodic `GET` of the ruleset (often with **ETag/If-None-Modified** so unchanged fetches are cheap 304s). Mechanics: every N seconds the SDK pulls and swaps the store. Failure modes: change latency up to the poll interval (bad for kill switches), and load proportional to fleet size × poll frequency. Rule of thumb: **streaming for anything you need to flip *now*** (kill switches, incident response); polling when firewalls forbid long connections or you want dead-simple, debuggable behavior and can tolerate seconds of lag.

### 🟡 — extended

#### Q53. [Theory] How are segments evaluated internally, and why can a single segment change ripple across many flags at once?
A **segment** is a named, reusable set of targeting rules (or an explicit include/exclude list) stored in the ruleset alongside flags. When a flag's rule references a segment ("serve true to the `beta-users` segment"), evaluation **expands the segment in-line**: it runs the segment's own rules/lists against the context to get a boolean "is this context in the segment?", then the flag rule uses that result. Because segments are **shared by reference**, editing one segment instantly changes the evaluation of *every flag that references it* — that's the power (define `enterprise-accounts` once, gate 40 features with it) and the risk (a fat-fingered segment edit can flip behavior across dozens of features simultaneously). This is why mature governance treats **segment edits as high-blast-radius changes** deserving approvals, and why some platforms support "big segments" (millions of IDs) backed by an external store rather than inlined rules, with the SDK querying membership from a side store.

#### Q54. [Theory] Explain prerequisite flags. How do they prevent invalid flag combinations, and what's the evaluation semantics?
A **prerequisite** declares that flag B is only meaningful when flag A is on (and possibly serving a specific variation). Evaluation semantics: before applying B's own targeting, the SDK **evaluates each prerequisite flag for the same context**; if any prerequisite is off or serves a non-required variation, flag B short-circuits to its **off variation** regardless of B's own rules. This models **dependency graphs** between features — e.g., "new-checkout-analytics" requires "checkout-redesign" to be on for that user, so you can't accidentally enable a child feature while its parent is off. It directly attacks the combinatorial-outage problem from the advanced section: instead of hoping no one creates the bad combination, you **make the bad combination unrepresentable**. Caveats: prerequisite chains are evaluated recursively (watch for depth and cycles — platforms forbid cycles), and a prerequisite's off-variation determines the child's result, so design the off variation deliberately.

#### Q55. [Coding] Implement prerequisite (dependency) evaluation with cycle detection in Java.
**Problem:** Evaluate a flag whose result depends on prerequisite flags being on; detect and reject cycles so evaluation can't infinite-loop.

```java
import java.util.*;

public class PrereqEvaluator {
    record Flag(String key, boolean on, boolean offVariation,
                List<String> prerequisites, boolean fallthrough) {}

    private final Map<String, Flag> flags;
    public PrereqEvaluator(Map<String, Flag> flags) { this.flags = flags; }

    public boolean evaluate(String key) {
        return eval(key, new HashSet<>());
    }

    private boolean eval(String key, Set<String> visiting) {
        Flag f = flags.get(key);
        if (f == null) return false;                 // unknown → default off
        if (!visiting.add(key))                       // key already on the current path
            throw new IllegalStateException("Prerequisite cycle through " + key);
        try {
            if (!f.on()) return f.offVariation();     // flag itself off → off variation
            for (String p : f.prerequisites()) {      // every prerequisite must be ON
                if (!eval(p, visiting)) return f.offVariation();
            }
            return f.fallthrough();                    // all prereqs satisfied → normal result
        } finally {
            visiting.remove(key);                     // backtrack so siblings can re-visit shared deps
        }
    }
}
```

**Time/Space:** O(V + E) over the prerequisite DAG per top-level evaluation; the `visiting` set bounds recursion depth. **Edge cases:** (1) a cycle is a configuration error, not a runtime expectation — fail loud (platforms reject cycles at save time). (2) Remove the key from `visiting` on the way out (not a global `visited` set) so a diamond dependency evaluated via two paths isn't falsely flagged as a cycle. (3) A missing prerequisite flag should be treated as *not satisfied* (fail safe), not as an exception that 500s the request.

#### Q56. [Theory] What is "context kind" / multi-context evaluation, and why did flag platforms move beyond a single "user"?
Older SDKs modeled the world as a single **user** object. Real targeting needs **multiple entity kinds at once**: you might target by `user`, by `organization`/`account`, by `device`, by `request`/`tenant`, or by an anonymous `visitor`. A **multi-context** evaluation passes several contexts of different **kinds** simultaneously, and rules/segments can target any kind ("on for the `pro` plan **org** AND `country=CA` **user**", or "bucket the rollout by `org` so whole accounts flip together"). Why it matters: (1) **org-level rollouts** — flip a feature for an entire account, not per-seat, avoiding the "half my team has it" complaint; (2) **device-level** targeting for app-version or OS gating independent of who's logged in; (3) **proper anonymous→identified transitions** by carrying both a stable device context and the user context. The migration from "user" to "context" is one of the bigger recent SDK evolutions and is why current bucketing must specify *which kind* it buckets by.

#### Q57. [Practical] How do you reconcile an anonymous visitor with a logged-in user so their experiment variant doesn't jump on login?
The problem: pre-login you bucket on a **device/cookie ID**; post-login you have an **account ID**. If you naively re-bucket on the account ID at login, the user can jump from variant A to B mid-session — corrupting the experiment and confusing the user. Approaches: (1) **Carry both contexts** (multi-context: a `user` kind that's anonymous, plus a `device`/`anonymous` kind) and **bucket the experiment on the stable anonymous/device key** so login doesn't change the bucket. (2) **Identity aliasing / context association** — tell the platform "this anonymous key is now this account" so events and assignment are stitched and the original assignment is honored. (3) **Persisted bucketing** — store the variant assignment keyed by a stable identity so subsequent evaluations return the same arm even if the keying attribute changes. The principle: choose **one stable randomization unit per experiment** and make it survive the login boundary; never let the unit of randomization silently change underneath an active assignment.

#### Q58. [Coding] Implement a stable anonymous-ID strategy with login reconciliation in Java.
**Problem:** Produce an evaluation key that is stable for anonymous users and remains stable after they log in, so experiment buckets don't reshuffle.

```java
public final class StableIdentity {

    /** Build the bucketing identity. The experiment always buckets on `anonId`
     *  (stable from first visit), while the logged-in user id is attached for
     *  targeting/analytics but NOT used as the rollout bucket key. */
    public static EvalContext forRequest(HttpServletRequest req, User userOrNull) {
        String anonId = readOrMintAnonCookie(req);   // persisted cookie/device id, stable across login

        EvalContext.Builder b = EvalContext.builder()
            .key(anonId)                              // <-- rollout buckets on this, login or not
            .attribute("anonId", anonId);

        if (userOrNull != null) {
            b.attribute("userId", userOrNull.id())    // available for targeting & analytics
             .attribute("plan", userOrNull.plan());
        }
        return b.build();
    }

    private static String readOrMintAnonCookie(HttpServletRequest req) {
        String existing = CookieUtil.get(req, "anon_id");
        return existing != null ? existing : CookieUtil.mintAndSet(req, "anon_id");
    }
}
```

**Notes:** Bucketing on the **stable anonymous id** is what prevents variant jumping at login; the user id rides along for targeting rules and for joining exposure events to outcomes. **Edge cases:** (1) if the anon cookie is lost (incognito, cleared cookies) the user re-buckets — acceptable but logged as a known leakage source. (2) For cross-device consistency you *do* want to bucket on the account id once logged in for *long-lived* features — choose the unit per use case: anonymous-stable for funnel experiments, account-stable for entitlement-style rollouts.

#### Q59. [Theory] Why is `String.hashCode()` (or modulo-100) a bad bucketing function, and what properties does a good one need?
A bucketing hash must give a **uniform** distribution and good **avalanche** (one input bit flips ~half the output bits) so that adjacent or similar keys (`user1`, `user2`) land in unrelated buckets. `String.hashCode()` fails both: it's a weak polynomial hash with poor avalanche and visible clustering for similar strings, so sequential IDs bucket non-uniformly and your "10%" may be skewed. Plain `% 100` is too coarse to express a 0.5% canary and amplifies any non-uniformity. Good bucketing needs: (1) a **well-distributed hash** — production SDKs use **MurmurHash3** (fast, excellent avalanche) or a SHA variant; (2) a **wide bucket space** (e.g., 100,000) for fine-grained ramps; (3) a **salt + flag key in the seed** so the same user isn't perpetually unlucky across every flag (decorrelating flags); (4) **determinism** — pure function of `(salt, flagKey, key)` with no state, so every node agrees. The combination guarantees that "in at 5% ⇒ in at 25%" stickiness holds with a genuinely uniform, decorrelated split.

#### Q60. [Coding] Show MurmurHash3-style fine-grained bucketing matching how real SDKs map a key to a rollout slot.
**Problem:** Map `(flagKey, salt, contextKey)` to a bucket in `[0, 1)` (or `[0, 100000)`) deterministically with good uniformity, the way LaunchDarkly/Unleash-style rollouts do.

```java
public final class Bucketing {

    private static final long BUCKET_SCALE = 100_000L;   // fine-grained: 0.001% resolution

    /** Deterministic bucket in [0, BUCKET_SCALE) for a context within a flag's rollout. */
    public static long bucket(String flagKey, String salt, String contextKey) {
        String seed = flagKey + "." + salt + "." + contextKey;   // decorrelates flags & allows re-randomization via salt
        int h = murmur3_32(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
        long unsigned = Integer.toUnsignedLong(h);
        return unsigned % BUCKET_SCALE;
    }

    public static boolean inRollout(String flagKey, String salt, String contextKey, double percent) {
        return bucket(flagKey, salt, contextKey) < (long) (percent / 100.0 * BUCKET_SCALE);
    }

    // Compact MurmurHash3 x86 32-bit — strong avalanche, what real SDKs reach for.
    static int murmur3_32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51, c2 = 0x1b873593;
        int h = seed, i = 0, len = data.length;
        while (len - i >= 4) {
            int k = (data[i] & 0xff) | (data[i+1] & 0xff) << 8
                  | (data[i+2] & 0xff) << 16 | (data[i+3] & 0xff) << 24;
            i += 4;
            k *= c1; k = Integer.rotateLeft(k, 15); k *= c2;
            h ^= k; h = Integer.rotateLeft(h, 13); h = h * 5 + 0xe6546b64;
        }
        int k = 0;                                   // tail
        switch (len - i) {
            case 3: k ^= (data[i+2] & 0xff) << 16;
            case 2: k ^= (data[i+1] & 0xff) << 8;
            case 1: k ^= (data[i] & 0xff);
                    k *= c1; k = Integer.rotateLeft(k, 15); k *= c2; h ^= k;
        }
        h ^= len;                                    // finalization (fmix)
        h ^= h >>> 16; h *= 0x85ebca6b;
        h ^= h >>> 13; h *= 0xc2b2ae35; h ^= h >>> 16;
        return h;
    }
}
```

**Time/Space:** O(L) in key length, O(1) memory. **Why this shape:** the `salt` lets you **re-randomize** a rollout (new experiment, fresh assignment) without changing the flag key; including `flagKey` decorrelates which users are "in" across different flags; MurmurHash3's avalanche gives uniform buckets so a 10% rollout is genuinely ~10%. Stickiness ("in at 5% ⇒ in at 25%") follows automatically because raising `percent` only widens the accepted bucket range.

### 🟠 — extended

#### Q61. [Theory] How do "big segments" (millions of targeted IDs) change the SDK's evaluation architecture?
Normal segments are **inlined** in the ruleset and evaluated entirely in memory. That breaks when a segment must contain **millions of individual user IDs** (e.g., "all users who purchased before date X") — you can't ship a multi-megabyte ID list to every SDK on every change. The architecture shifts to an **externalized membership store**: the big-segment membership lives in a side database (Redis/Dynamo), and during evaluation the SDK **queries membership** for the specific context key rather than scanning an in-memory list. Implications: (1) evaluation is no longer purely local for those flags — it incurs a (cached) store lookup, so latency and the store's availability now matter; (2) the SDK caches membership results and may need a **persistent store integration** configured; (3) you trade the "everything is in memory" purity for the ability to target huge cohorts. Mature platforms keep small/rule-based segments inlined and reserve big-segment machinery for genuinely large explicit lists, because the lookup cost and new failure mode aren't free.

#### Q62. [Theory] What is a relay/edge proxy in flag delivery, and what specific problems does it solve at scale?
A **relay proxy** is a service you run **inside your own network** that maintains the streaming connection to the provider, holds the ruleset, and serves it to your fleet's SDKs. Problems it solves: (1) **connection fan-in** — instead of 10,000 SDK instances each holding a streaming connection to the provider (and 10,000 reconnect storms after a blip), they connect to a handful of regional relays, which hold a few connections upstream. (2) **Network isolation / egress control** — only the relay needs outbound internet; app instances talk to the relay internally, satisfying security teams. (3) **Resilience** — the relay can persist the ruleset to a shared store (Redis) that SDKs read in **daemon mode**, so even a relay restart or provider outage doesn't cold-start your apps. (4) **Lower latency & cost** for client-side: an edge proxy evaluates near the user and returns resolved values, keeping rules server-side. (5) **Consistent version** across a region. The relay turns a many-to-one internet dependency into a controlled, cacheable, in-network tier.

#### Q63. [Coding] Implement a two-tier flag store (local cache → persistent fallback → safe default) for outage resilience.
**Problem:** Survive a provider outage *and* process restarts: read from in-memory, fall back to a durable store, finally to the code default — never throw.

```java
public class ResilientFlagStore {
    private final Map<String, Boolean> memory;       // updated by streaming/polling
    private final PersistentStore durable;           // e.g., Redis/Dynamo, written by relay or SDK
    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ResilientFlagStore.class);

    public ResilientFlagStore(Map<String, Boolean> memory, PersistentStore durable) {
        this.memory = memory; this.durable = durable;
    }

    public boolean eval(String flagKey, boolean codeDefault) {
        Boolean v = memory.get(flagKey);             // tier 1: hot path, microseconds
        if (v != null) return v;
        try {
            Boolean d = durable.get(flagKey);        // tier 2: last-known-good across restarts
            if (d != null) { memory.put(flagKey, d); return d; }
        } catch (RuntimeException e) {
            log.warn("durable flag store unavailable for {}, using default", flagKey, e);
        }
        return codeDefault;                          // tier 3: documented safe default
    }
}
```

**Notes:** This encodes the "**fail open to last-known-good, then to safe defaults**" mantra. Tier 1 keeps steady-state evaluation local and fast; tier 2 means a pod that restarts *during* a provider outage bootstraps real flags instead of cold defaults; tier 3 guarantees no exception escapes. **Edge cases:** the durable store must itself be optional (an outage there only loses tier 2, not the request); populate tier 1 from tier 2 to avoid hammering the durable store; and pick `codeDefault` as the *safe* behavior since it's the floor of the fallback chain.

#### Q64. [Theory] Sample Ratio Mismatch (SRM): what is it, what causes it in a flag platform, and how do you detect it?
**SRM** is a statistically significant discrepancy between the **expected** and **observed** allocation of an experiment — you targeted 50/50 but logged 52/48 with millions of samples. It's a red flag that the experiment is **invalid**, because if assignment is broken, any "win" may be an artifact. A chi-squared goodness-of-fit test on the counts (expected vs observed) detects it; a tiny p-value (e.g., < 0.001) means SRM. Causes specific to flag platforms: (1) **broken/biased bucketing** (a weak hash that doesn't split 50/50 — see the `String.hashCode()` pitfall); (2) **exposure logged at the wrong point** so one arm under-logs (e.g., logging on page load but the variant gates a component that fails to render for one arm); (3) **inconsistent identity** — anonymous re-bucketing inflates one arm; (4) **filtering after assignment** (bots/dedup applied unevenly); (5) **redirect/latency differences** where one variant loses more users before logging. A serious platform runs an **automatic SRM check** on every experiment and refuses to report results until it passes — deterministic, well-distributed bucketing plus exposure-at-evaluation is the structural defense.

#### Q65. [Theory] Why must exposure/impression events be logged "at the point of evaluation," and what bias appears if you log at page load?
An **exposure event** records "this user was actually assigned and could experience variant V." The analysis joins exposures to outcomes; only *exposed* users should count. If you log exposure at **page load** instead of at the moment the flagged code actually runs, you include users who **never reached the flagged surface** — e.g., the experiment changes a checkout step, but you logged everyone who loaded the homepage. This **dilutes the effect** (most "exposed" users never saw the change, washing out a real difference) and can **introduce SRM** if one variant causes more pre-exposure drop-off. The correct pattern: emit the exposure **inside the same evaluation that returns the variant**, gated so it fires once per user per experiment when they truly hit the code path. This is why SDKs tie exposure emission to `variation()` calls (with sampling/dedup) rather than asking you to log separately — co-locating assignment and exposure is the only way to count the right denominator.

#### Q66. [Coding] Implement once-per-user exposure deduplication tied to evaluation.
**Problem:** Fire exactly one exposure event per (user, experiment) even though `variation()` may be called many times per request/session, to keep the analysis denominator correct and the event volume sane.

```java
import java.util.concurrent.ConcurrentHashMap;

public class ExposureTracker {
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
    private final EventSink sink;

    public ExposureTracker(EventSink sink) { this.sink = sink; }

    /** Call from inside the evaluation; emits at most one exposure per user+experiment. */
    public String assignAndExpose(String experiment, String userKey, String variant) {
        String dedupKey = experiment + "|" + userKey;
        // putIfAbsent returns null only the first time → emit exactly once.
        if (seen.putIfAbsent(dedupKey, Boolean.TRUE) == null) {
            sink.record(new ExposureEvent(experiment, userKey, variant, System.currentTimeMillis()));
        }
        return variant;
    }
}
```

**Time/Space:** O(1) per call; memory grows with distinct (user, experiment) pairs — bound it. **Edge cases:** (1) the in-process map only dedups within one instance; across a fleet you also dedup downstream in the analytics pipeline (idempotent on `dedupKey`), since the *same* user can hit different nodes. (2) Evict entries (size cap / TTL) so the map doesn't grow unbounded over a long-running process. (3) Emit the **variant the user actually got** — if you later change the assignment, the first exposure still reflects what they experienced, which is what the analysis needs.

#### Q67. [Theory] What is the "peeking problem" and how do sequential / always-valid tests fix it at the platform level?
Classic fixed-horizon statistics assume you decide the sample size up front and **look once** at the end. **Peeking** — checking significance repeatedly and stopping the instant p < 0.05 — massively inflates the false-positive rate, because with enough looks a null result will *eventually* cross the threshold by chance. Since flag/experiment dashboards update continuously, experimenters peek constantly, so the platform must defend against it. Two structural fixes: (1) **fixed-horizon discipline** — compute the required sample/duration in advance and only read the result at the end (hard to enforce socially). (2) **Sequential testing / always-valid p-values and confidence sequences** — statistics specifically designed so you *can* look as often as you like and stop early without inflating error; the confidence interval is valid at every moment, widening to pay for continuous monitoring. Modern experimentation platforms implement always-valid inference precisely so the natural behavior (watching the dashboard) is also the statistically correct behavior. The flag layer supplies consistent assignment + exposure; the stats layer must supply peeking-safe inference.

#### Q68. [Practical] How do you safely change targeting *mid-experiment* without contaminating results?
Changing rules while an experiment runs is dangerous because it can **re-bucket** users (variant jumping) or change the population, breaking the independence and consistency the analysis assumes. Safe practices: (1) **Don't touch the randomization** — never change the bucketing key, salt, or split percentages of a live experiment; that reshuffles assignments. (2) Use **persisted/sticky bucketing** so even if rules change, already-assigned users keep their arm. (3) If you must expand audience, **add** a new eligible segment rather than re-keying existing users, and analyze the new cohort's exposure window separately. (4) Prefer **starting a fresh experiment** (new salt) over mutating a running one when the change is substantive — clean assignment beats a contaminated continuation. (5) **Annotate the change** in the experiment timeline so analysis can segment before/after and detect discontinuities. The rule of thumb: a running experiment's assignment mechanism is **frozen**; you may grow the audience carefully, but you never re-randomize the people already in it.

#### Q69. [Theory] How does flag evaluation order (targets → rules → fallthrough) interact with percentage rollouts inside a single flag?
Within one flag the evaluation is strictly ordered and **first-decision-wins**, and a rollout can live at multiple layers: (1) **Individual targets** — explicit user IDs mapped to a variation; these win outright (used for "force this exact user into variant B" for QA/support). (2) **Rule list** — evaluated top-down; the first rule whose clauses match decides. A matching rule can serve a **fixed variation** *or* a **percentage rollout among variations** (e.g., "for CA users: 50% A / 50% B"). (3) **Fallthrough** — if no rule matches, the flag's default rollout applies, which itself can be a fixed variation or a percentage split. The subtlety: a percentage rollout **inside a matched rule** buckets only the **subpopulation that matched that rule**, not the global population — "50% of Canadian users" is 50% of CA, computed by hashing within that matched set. Get the order wrong (catch-all rule before a specific one) and you dilute or misroute cohorts. Detailed evaluation reasons (`RULE_MATCH` with the rule index, vs `FALLTHROUGH`) let you verify which layer actually decided.

### 🔴 — extended

#### Q70. [Theory] Design the consistency and versioning model for a global flag control plane. How do you reason about "which version served this request"?
Treat the ruleset as an **append-only, versioned artifact**: every change (flag edit, segment edit, rule reorder) produces a **new immutable ruleset version** with a monotonically increasing version id, authored, timestamped, and diff-able. Properties to engineer: (1) **Version stamping** — every evaluation can report the **ruleset version** it used; emit it on exposure events, logs, and traces so you can answer "request R was served by version 4821." (2) **Eventual propagation with bounded skew** — versions fan out via regional relays; you accept that at any instant the fleet spans a small range of versions and you make features tolerant of that window. (3) **Monotonic per-node application** — a node never goes backwards in version (ignore stale patches), preventing flip-flop. (4) **Atomic swap per node** — apply a new ruleset as a whole-snapshot swap, not field-by-field, so no request sees a half-applied change. (5) **Rollback = re-publish a prior version** (a new version that equals an old one), preserving the append-only audit chain. (6) **Cross-region replication lag** is explicit and monitored; never block a request on a cross-region fetch. The mental model is a **versioned config CDN**: strongly-consistent authoring, eventually-consistent global distribution, with version provenance attached to every decision so incidents and experiments are explainable.

#### Q71. [Theory] What are the consistency guarantees you can and cannot offer for a "global instant kill switch," and how do you make it as fast as physics allows?
You **cannot** offer a globally atomic, strongly-consistent flip — distributed nodes, network, and the speed of light mean some instances apply the change microseconds-to-seconds before others. What you *can* offer is **bounded, observable propagation**: "all healthy nodes reflect the flip within T seconds, and we can measure T." To minimize T: (1) **streaming (SSE/websocket)** push, not polling, so the change leaves the control plane immediately; (2) **regional relays** so the fan-out hop is in-network and short; (3) **prioritize kill-switch flags** on the wire and keep their evaluation **dependency-free and locally cached** so applying the patch is just a memory write; (4) **persistent fallback store** so even reconnecting/restarting nodes pick up the killed state; (5) **default-safe** design so any node that *hasn't* heard yet still fails toward off. What you must communicate to stakeholders: a kill switch is **eventually consistent but fast and measurable**, not instantaneous everywhere — so a feature that *must* be off everywhere atomically (e.g., a legal cutoff) needs a stronger mechanism than a flag, or must tolerate a brief tail. Engineering honesty about this bound is itself a staff-level signal.

#### Q72. [Theory] How would you make flag evaluation auditable and reproducible for compliance — "prove what flag X returned for user U on date D"?
Auditability of *configuration* is table stakes (immutable change log: who/what/when/why, versioned ruleset). The harder requirement is **reproducible evaluation** — proving the *result* a user got. Build it from three records: (1) **Versioned ruleset history** — every version persisted and retrievable, so you can reload the exact ruleset in effect at date D. (2) **Deterministic, pure evaluation** — because bucketing is a pure function of `(salt, flagKey, contextKey)` and rules are data, replaying ruleset version V against the recorded context for user U **reproduces the exact decision** — no hidden state. (3) **Captured evaluation context** — log (or be able to reconstruct) the context attributes used, since the result depends on them; store evaluation events with the ruleset version, variation index, and reason. With these you can answer a regulator or an incident review precisely: "On date D, ruleset version 4821 was live; user U's context bucketed to slot 73,402 in flag X's CA rule, yielding variation 1 (`true`), reason `RULE_MATCH[0]`." Determinism is what turns "we think it was on" into a **provable replay**. Pitfalls to close: non-deterministic inputs (wall-clock-dependent rules, random tie-breaks), un-versioned segment edits, and PII-in-context retention limits that conflict with replay — pseudonymize but keep the bucketing key.

#### Q73. [Practical] A staff engineer inherits 1,200 flags across 30 services with no metadata. Design a measurable, multi-quarter remediation program.
Treat it as a **socio-technical debt program** with metrics and guardrails, not a cleanup sprint. (1) **Instrument first** — turn on evaluation telemetry and **code-reference scanning** across all repos so every flag gets: last-evaluated, served-variation distribution, source references, last-modified, owning team. (2) **Classify** — auto-bucket into *dead* (no code refs OR not evaluated in 90d), *stale* (single variation served 100%), *active rollout*, *permanent* (permission/config), *unknown*. (3) **Stop the bleeding** — enforce at creation: mandatory owner + type + TTL + removal ticket; **CI gate** failing builds that introduce a flag without metadata or reference a flag past its TTL. (4) **Burn down by tier** — auto-PR removal of no-reference flags (safest), then 100%-stable release flags (remove dead branch), routed to owning teams; track a **flag-count + flag-age** dashboard as the north-star metric. (5) **Govern segments/prerequisites** with approvals (high blast radius). (6) **Recurring hygiene review** so the curve stays down. Success criteria are **trends** (median flag age, count, % with owners/TTLs), not a one-time number — and you frame stale flags as **incident risk** (a stale "100%" flag re-enabling rotted code) to win prioritization. This demonstrates influence-without-authority, automation over heroics, and prevention baked into the platform.

#### Q74. [Theory] How do feature flags fit into a progressive-delivery control loop with automated metric-based rollback ("guarded rollouts")?
A **guarded rollout** closes the loop between the flag dial and your observability stack so ramps advance or abort **automatically**. The control loop: (1) the flag exposes the feature to a slice (canary segment / 1%); (2) the platform **monitors guardrail metrics** for the exposed vs control cohort — error rate, latency, and business KPIs, using the **exposure events** to define cohorts correctly; (3) a **statistical comparison** (often sequential, to allow continuous monitoring without peeking inflation) decides if the treatment is degrading a guardrail beyond a threshold; (4) if healthy, the platform **auto-advances** to the next ramp step after a soak; if a guardrail regresses, it **auto-rolls back to 0%** and alerts. This makes the flag not just a manual lever but the **actuator in a feedback controller**: percentage is the control output, guardrail metrics are the measured signal, and the comparison is the controller. Design requirements: trustworthy exposure logging (right denominator), peeking-safe stats (so auto-decisions aren't false alarms), low-latency metric pipelines (so rollback is fast), and a hard **safe default of 0%** so any ambiguity halts the ramp. It fuses the rollout (flags) and experimentation (stats) layers into one automated, reversible delivery mechanism.

#### Q75. [Theory] Argue both sides: is a permission/entitlement flag a feature flag at all, or is it authorization in disguise — and what does the answer change architecturally?
**Case for "it's a flag":** it uses the same infrastructure — management UI, SDK, targeting, instant change without deploy — and lets product/ops grant or revoke a capability per user/plan/segment in seconds. Treating entitlements as flags gives non-engineers a governed lever and unifies tooling. **Case for "it's authorization":** an entitlement gates **access to data, money, or regulated capability**, so it's a **security decision**, and security decisions must be **enforced server-side, fail closed, be audited, and resist tampering** — exactly the opposite of the convenience-oriented, fail-toward-default posture of a release toggle. The reconciliation that a staff engineer should land on: **classify entitlement flags as security-relevant**, and that classification changes architecture: (1) **enforce server-side only** — never let a client-evaluated entitlement be the gate; (2) **fail closed** (default deny), unlike a kill switch that fails to a safe *operational* state; (3) **stricter governance** — mandatory approvals, RBAC on who can edit, immutable audit for compliance; (4) **long-lived by design** — exclude them from stale-flag reaping and TTL automation, or cleanup tooling will try to "expire" a permanent business rule; (5) **treat as part of the authZ surface** in threat models and pen tests. So the honest answer is "**both**": it's delivered *via* the flag platform but *governed as* authorization — and conflating its lifecycle and trust model with a release toggle's is a real source of security incidents.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q76. [Practical] A teammate says "the flag is on in the dashboard but my code still takes the old path." Walk through how you debug it.
Work top-down from "what value is the SDK actually returning here," not from the dashboard. Checklist: (1) **Right environment?** SDK keys are per-environment — a dashboard showing the flag on in *Production* means nothing if the running app holds the *Staging* SDK key. Print the key's environment. (2) **Right flag key string?** A typo in the `"checkout-redesign"` string returns your code default silently — switch to `boolVariationDetail` and check for reason `FLAG_NOT_FOUND`. (3) **Is the SDK initialized?** A cold instance serves defaults until its first ruleset arrives; check `isInitialized()` and reason `CLIENT_NOT_READY`. (4) **Context targeting** — the flag may be on *globally* but the rule excludes this user (wrong/empty context key, missing attribute), giving reason `FALLTHROUGH` to the off variation. (5) **Caching/propagation lag** — on polling SDKs the change can be up to the poll interval old; on streaming, a dropped connection means stale data. (6) **A prerequisite** is off, short-circuiting to the off variation. The single most useful move is to log `boolVariationDetail`'s value, reason, and variation index for that exact context — it collapses six guesses into one fact.

#### Q77. [Practical] Your local tests pass but the flagged feature behaves differently in CI. What are the likely flag-related causes?
The usual root cause is **non-deterministic or environment-coupled flag state** leaking into tests. Likely causes: (1) tests hit a **real flag provider** (or a shared test environment) instead of an in-memory data source, so a flag someone toggled elsewhere changes behavior — pin flags with a `TestData`/file data source. (2) **Initialization race** — CI is slower, so the SDK hasn't loaded the ruleset before the assertion runs; the test sees defaults. Block on `isInitialized()` or use a synchronous test source. (3) **Order-dependent state** — a prior test set a flag and didn't reset it; shared singleton client carries state across tests. (4) **Different default** between machines because of an env var (`FEATURE_X`) set locally but not in CI. (5) **Time/clock-dependent rules** (a date-based targeting rule) evaluating differently on the CI date. The fix pattern: tests must **own the flag state deterministically** (inject an in-memory provider, force each variation explicitly) and never depend on a live ruleset, the network, or wall-clock dates.

#### Q78. [Coding] Write a JUnit test that exercises both branches of a flag using an injected fake provider.
**Problem:** Verify a service's behavior with the flag both on and off, with zero network and full determinism.

```java
class CheckoutServiceTest {

    private final FakeFeatureFlags flags = new FakeFeatureFlags();   // in-memory, from Set 1
    private final CheckoutService service = new CheckoutService(flags, newEngine, legacyEngine);

    @Test
    void usesNewEngine_whenFlagOn() {
        flags.on(Flag.CHECKOUT_REDESIGN);                 // force ON
        Receipt r = service.checkout(user, cart);
        assertEquals(EngineType.NEW, r.engineUsed());
    }

    @Test
    void usesLegacyEngine_whenFlagOff() {
        // default is off → do not enable it
        Receipt r = service.checkout(user, cart);
        assertEquals(EngineType.LEGACY, r.engineUsed());
    }
}
```

**Notes:** Each test pins exactly one variant, so both paths are covered and neither depends on a live provider. Inject the `FeatureFlags` interface (not a concrete vendor client) so the fake drops in cleanly. The off-path test deliberately relies on the flag's *declared default* being off — a good extra assertion is that the default itself is off, so a future default change is caught. This is the "test both sides of the flag" rule made concrete.

#### Q79. [Practical] You flipped a kill switch and nothing changed for 30 seconds. What's wrong and how do you make it instant?
A 30-second lag almost always means you're on **polling**, not streaming, and the change is waiting for the next poll. Diagnose and fix: (1) confirm the SDK's update mode — if polling, the interval (often 30s) *is* your worst-case lag; switch the kill-switch-bearing services to **streaming (SSE)** so flips propagate in milliseconds. (2) Check for a **relay proxy / cache TTL** in front — an edge cache with a 30s TTL adds the same lag; lower the TTL or ensure the relay streams. (3) Verify the SDK's streaming connection isn't being **buffered/killed by a corporate proxy**, silently degrading to stale data. (4) Ensure the kill switch is **dependency-free and locally cached** so applying the patch is just a memory write, not a re-fetch. The structural lesson: kill switches must be on the **fastest update channel**, and you should *measure* propagation time (emit ruleset version in logs) so "instant" is a number you can prove, not a hope.

#### Q80. [Practical] How do you give QA a way to force a specific flag variation for their own account without affecting real users?
Use **individual targeting** (explicit user targets), which sits *above* rules in evaluation order and wins outright. In the dashboard, add the QA user's context key to the flag's "serve variation B to these specific users" list — that user gets B regardless of the percentage rollout, and no one else is affected. Code-side, nothing changes; the SDK already evaluates individual targets first. Complementary techniques: (1) a **`beta-testers` segment** containing QA accounts, referenced by a top rule, so you manage them in one place across many flags; (2) an **override mechanism for non-prod** (a query param or header that, *only in dev/staging*, forces a variation) — never enable client-controllable overrides in production, as that's a trivially abusable bypass. The principle: individual targets and a QA segment let you give testers deterministic access without touching the global rollout.

#### Q81. [Coding] Implement a safe dev-only flag override via HTTP header that is ignored in production.
**Problem:** Let developers force a variation with a header in non-prod, but make the override impossible in production so it can't be abused.

```java
public class OverridableFlags implements FeatureFlags {
    private final FeatureFlags delegate;
    private final boolean allowOverrides;   // true ONLY in dev/staging

    public OverridableFlags(FeatureFlags delegate,
                            @Value("${flags.allow-header-overrides:false}") boolean allow) {
        this.delegate = delegate;
        this.allowOverrides = allow;        // defaults to false → prod is safe even if misconfigured
    }

    public boolean enabled(Flag flag, EvalContext ctx) {
        if (allowOverrides) {
            String override = ctx.attr("x-flag-override-" + flag.key);  // from request header
            if ("on".equals(override))  return true;
            if ("off".equals(override)) return false;
        }
        return delegate.enabled(flag, ctx);   // normal evaluation
    }
    // value(...) similar
}
```

**Notes:** The override is gated by `allowOverrides`, which **defaults to false** so a forgotten config can never accidentally open it in production. The header is read from the eval context (populated by a filter only in non-prod profiles). **Edge cases:** never let this path gate security/entitlement flags even in dev (it trains bad habits); log every override so test results are explainable; and keep the flag name in the header so one header can't flip everything.

#### Q82. [Practical] A flag's default value is being served in production even though the flag exists and is on. What does that tell you, and how do you confirm?
Serving your **code default** (not the flag's configured off variation) is a strong signal that **the SDK has no real answer for this evaluation** — it's not "the flag is off," it's "the SDK couldn't decide." The usual causes: the **SDK isn't initialized** (cold start / failed connection, reason `CLIENT_NOT_READY`), the **flag key is wrong** (`FLAG_NOT_FOUND`), the **requested type doesn't match** the flag's variations (`WRONG_TYPE` / `MALFORMED_FLAG`), or an **exception in evaluation** (`ERROR`). Confirm with `boolVariationDetail` and read `getReason()` — it names exactly which of these occurred. Then act: a `CLIENT_NOT_READY` flood points to an init/connectivity problem (check SDK key, network egress, relay health); `FLAG_NOT_FOUND` points to a typo or a flag deleted out from under the code; `WRONG_TYPE` means a boolean flag was changed to multivariate (or vice versa). The key mental model from Set 1: **code default served = something is wrong**, while **off variation served = normal**.

### 🟡 — extended

#### Q83. [Practical] Production error rate spiked right after a 10% rollout. Give your step-by-step incident response.
Treat the flag as the prime suspect and the fastest lever. (1) **Stop the bleeding first** — flip the flag to **0%** (kill switch). This is seconds; do it before deep investigation. (2) **Confirm causation** — query errors filtered by the flag attribute on traces/logs (`flag.checkout-redesign=true`); if essentially all the new errors carry the treatment variant, the flag is the cause. (3) **Capture evidence** — note the ruleset version, exact rollout %, time of flip, and a sample of failing requests *before* the state scrolls away. (4) **Verify recovery** — error rate should return to baseline within the propagation window; if it doesn't, the flag wasn't the (only) cause — widen the search. (5) **Communicate** — update the incident channel that the feature is disabled and customer impact is bounded. (6) **Postmortem & systemic fix** — why did 10% break when canary didn't? Often the canary cohort didn't exercise the failing path; add the affected segment to the shared canary, add tests for the broken case, and verify the kill switch was in the runbook. The discipline: **flip first, diagnose second** — the flag's whole value is that mitigation precedes root cause.

#### Q84. [Practical] After flipping a flag off, some users still see the feature. Enumerate the possible reasons.
"Off but still showing" almost always means a **cached or pre-evaluated value somewhere downstream of the flip**. Possible reasons: (1) **Client-side bootstrap** — the browser/mobile app got the resolved value at page load/session start and won't re-evaluate until reload or the next streaming push; the user is holding a stale value. (2) **CDN/HTTP caching** — a server-rendered page or API response that embedded the feature was cached and is still being served. (3) **Polling lag** — backend instances haven't fetched the new ruleset yet. (4) **A second flag or prerequisite** still enables the path (you turned off the wrong toggle). (5) **Individual targeting / a segment** still serves them the feature explicitly, overriding the global off. (6) **Sticky/persisted bucketing** keeping an assigned variant despite the rule change. (7) **A deploy-time config or hard-coded path** that the flag never actually controlled. Debug by checking `boolVariationDetail` server-side for an affected user (is it actually off there?) — if the server says off but the user sees on, it's a client/cache layer; if the server still says on, it's targeting/prerequisite.

#### Q85. [Coding] Implement a "scientist" dark-launch wrapper that runs old and new paths, serves the old, and logs mismatches.
**Problem:** Before exposing a new code path to users, validate it in production by running both, returning the **old** result, and recording where new disagrees with old.

```java
public final class Experiment<T> {
    private final FeatureFlags flags;
    private final MismatchSink sink;

    public Experiment(FeatureFlags flags, MismatchSink sink) {
        this.flags = flags; this.sink = sink;
    }

    /** Always returns control's result; runs candidate only when the dark-launch flag is on. */
    public T run(Flag darkLaunch, EvalContext ctx,
                 Supplier<T> control, Supplier<T> candidate) {
        T controlResult = control.get();                 // this is what the user gets

        if (flags.enabled(darkLaunch, ctx)) {
            try {
                T candidateResult = candidate.get();      // executed but NOT served
                if (!Objects.equals(controlResult, candidateResult)) {
                    sink.record(darkLaunch.key, ctx.key(), controlResult, candidateResult);
                }
            } catch (RuntimeException e) {
                sink.recordError(darkLaunch.key, ctx.key(), e);  // candidate threw → log, user unaffected
            }
        }
        return controlResult;                             // user always sees the trusted path
    }
}
```

**Notes:** This is the GitHub "Scientist" pattern. The user is **never** exposed to the candidate's output, so a wrong/exception-throwing new path can't hurt them — you only collect divergence data. **Edge cases:** (1) only run the candidate for a **sampled %** (another flag) to bound the extra cost; (2) make the candidate **side-effect-free** in dark-launch mode (don't double-charge a card!) — compare computed values, not actions with external effects; (3) wrap candidate in try/catch so its failure is data, not an incident. Once mismatches drop to zero, you can confidently promote the candidate behind a real release flag.

#### Q86. [Practical] How do you A/B test a change that requires a database schema migration, where you can't just branch on a flag at request time?
Separate the **irreversible data change** from the **reversible behavior change**, and flag only the latter. Use **expand-contract (parallel-change)**: (1) **Expand** — migrate the schema additively so both old and new code work (add the new column/table, backfill, keep the old one). No flag yet; this is a safe, non-breaking migration. (2) **Dual-write / branch the read path behind a flag** — the new code path reads/writes the new structure; the old path the old structure. Now a **flag at the read/write boundary** picks which path a user gets, so you can A/B and ramp safely because *both* schemas are valid simultaneously. (3) **Roll out & measure** via the flag as usual. (4) **Contract** — once the new path wins and is at 100% and stable, remove the flag, the old code, and finally drop the old column/table in a later deploy. The key insight: you don't flag the migration; you make the schema **forward-and-backward compatible first**, which turns the behavior into something a runtime flag *can* toggle. Never let a flag flip require a schema that only one branch can read.

#### Q87. [Practical] An experiment shows a "win," but you suspect the result is bogus. What flag/instrumentation issues would you check before trusting it?
Distrust first; validate the mechanics before the statistics. Check: (1) **Sample Ratio Mismatch** — are the arm sizes what you targeted (50/50)? A chi-squared SRM failure means assignment or logging is broken and the result is invalid regardless of p-value. (2) **Bucketing quality** — is it a real hash (MurmurHash3) on a stable key, or `String.hashCode()`/`% 100` that splits non-uniformly? (3) **Exposure timing** — is the impression logged **at evaluation** (when the user truly hit the surface) or at page load (diluting/biasing)? (4) **Identity stability** — do anonymous users re-bucket on revisit or at login, contaminating arms? (5) **Peeking** — did someone stop the test the moment it crossed p<0.05 without a sequential/fixed-horizon design? (6) **Interference** — do users in one arm affect the other (marketplace/social)? (7) **Mid-experiment changes** — were rules/splits edited while running, re-randomizing people? (8) **Multiple comparisons** — many metrics tested, one "won" by chance. Only after the **assignment + exposure machinery** is proven sound should you trust the lift — most "too good" results die at the SRM check.

#### Q88. [Coding] Implement a chi-squared Sample Ratio Mismatch (SRM) check you can run on experiment counts.
**Problem:** Given observed counts per arm and the expected split, flag whether the allocation is suspiciously off (SRM), which would invalidate the experiment.

```java
public final class SrmCheck {

    /** @param observed counts per arm, @param expectedRatio target proportions (sum to 1).
     *  Returns the chi-squared statistic; compare to a critical value (or convert to p-value). */
    public static double chiSquared(long[] observed, double[] expectedRatio) {
        long total = 0;
        for (long o : observed) total += o;

        double chi = 0.0;
        for (int i = 0; i < observed.length; i++) {
            double expected = expectedRatio[i] * total;          // expected count for this arm
            double diff = observed[i] - expected;
            chi += (diff * diff) / expected;                     // (O-E)^2 / E
        }
        return chi;
    }

    /** For a 2-arm 50/50 test, df=1; chi > 10.83 ⇒ p < 0.001 ⇒ strong SRM signal. */
    public static boolean isSrm(long[] observed, double[] expectedRatio) {
        return chiSquared(observed, expectedRatio) > 10.83;      // p<0.001 threshold, df=1
    }
}

// usage: 50/50 targeted, but logged 51000 vs 49000
boolean broken = SrmCheck.isSrm(new long[]{51000, 49000}, new double[]{0.5, 0.5});
```

**Time/Space:** O(k) in arm count. **Notes:** The `10.83` critical value is for **1 degree of freedom** (a 2-arm test); for `k` arms use the chi-squared critical value at `k-1` df. A very small p-value means the observed split is far enough from expected that random chance is implausible — the *experiment*, not the feature, is broken. **Edge case:** guard against `expected == 0` (an arm targeted at 0%) to avoid divide-by-zero, and only run SRM once you have enough samples for the asymptotic test to be valid.

#### Q89. [Practical] A flag SDK upgrade changed the bucketing and re-shuffled everyone's variants overnight. How do you prevent and recover from this?
This happens when an SDK changes its **hash function or bucketing algorithm** between versions, so the same key maps to a different bucket — silently re-randomizing live rollouts and experiments. Recovery: (1) **identify scope** — which flags/experiments depend on stickiness; experiments are the worst hit because arms reshuffled mid-flight (likely invalidating them). (2) **For experiments**, treat the reshuffle as a discontinuity — segment analysis before/after, and likely restart contaminated tests with a fresh salt. (3) **For rollouts**, the cohort changed but the *percentage* is the same, so impact is usually cosmetic (different users, same fraction) — communicate but rarely an incident. Prevention going forward: (1) **read the SDK changelog for bucketing changes** before upgrading and test in staging by comparing pre/post assignments for a sample of keys; (2) pin SDK versions and upgrade deliberately, not via floating ranges; (3) for critical sticky experiments use **persisted bucketing** so the assignment survives algorithm changes; (4) keep the **salt** explicit so you control re-randomization rather than the SDK doing it implicitly. The lesson: bucketing determinism is a contract, and an SDK upgrade can quietly break it.

#### Q90. [Coding] Write an integration-style test that verifies the SAFE DEFAULT is returned when the flag provider is unreachable.
**Problem:** Prove that an outage of the flag service degrades to the documented safe default instead of throwing or hanging.

```java
class FlagOutageTest {

    @Test
    void returnsSafeDefault_whenProviderUnreachable() throws Exception {
        // Point the SDK at a black-hole/offline data source so it can never load a ruleset.
        LDConfig offline = new LDConfig.Builder()
            .offline(true)               // SDK makes no network calls, stays uninitialized
            .build();

        try (LDClient client = new LDClient("sdk-irrelevant", offline)) {
            FeatureService svc = new FeatureService(client);

            // recommendations default is FALSE (cheap fallback path) — must hold under outage
            boolean on = svc.isOn("recommendations-enabled", anonAuth(), /*def*/ false);

            assertFalse(on, "must fall back to safe default when provider is unreachable");
        }
    }
}
```

**Notes:** Using the SDK's **offline mode** simulates "provider unreachable" without real network flakiness, so the test is deterministic and fast. The assertion encodes the contract: **unreachable ⇒ safe default**, never an exception or a hang. **Edge cases:** also test the cold-start window (uninitialized but not offline) returns the default, and add a test that `variation()` does not throw even when given a deliberately malformed context — defensive code in the request path must be proven, not assumed.

#### Q91. [Practical] You need to roll out a feature to "whole organizations at a time," but your bucketing is per-user, causing half a team to get it. Fix it.
The bug is that the **randomization unit is the user** while the feature's blast radius is the **org** — so a 30% rollout flips ~30% of *each* org's members, and teammates see inconsistent behavior. Fix by **bucketing on the org, not the user**: (1) with a multi-context SDK, pass both a `user` and an `org` context and set the rollout to **bucket by the `org` kind**, so every member of an org buckets identically and the org flips as a unit. (2) Without multi-context, set the **bucketing key** (distinct from the context key) to the `org_id` for this flag's rollout — same effect. Now "30% rollout" means 30% of *organizations* (all-or-nothing per org), which is what an org-level feature needs. This is the **bucketing-key vs context-key** distinction from Set 1 made practical: the unit of randomization must match the unit of feature impact. Watch for users in multiple orgs (pick a primary org or the request's active org as the bucket key) and for the analytics implication — your experiment's randomization unit is now the org, so analyze at the org level too.

### 🟠 — extended

#### Q92. [Practical] Design a flag rollout plan for a high-risk payments change. What gates and safeguards do you put at each step?
A high-risk, money-touching change needs **guarded, observable, reversible** ramps with explicit gates. Plan: (1) **Dark launch** — deploy behind an off flag; run a **scientist** comparison (old vs new totals) in production with results served from the old path, until mismatch rate is zero. (2) **Internal-only** — enable for the `internal-staff` segment; dogfood real transactions in a controlled cohort. (3) **Canary 1%** — enable for 1%, with **guardrail metrics** wired (payment success rate, chargeback/decline rate, latency, reconciliation discrepancies) and a hard rule: any guardrail regression auto- or manually-flips to 0%. (4) **Soak + ramp** — 1% → 5% → 25% → 50% → 100%, each step gated on a soak period with metrics green and, for payments, a **reconciliation check** that ledgers match. (5) **Approvals** — prod ramp steps on a payments flag require a second approver (change control). (6) **Kill switch in the runbook** — on-call knows the exact flag and that flipping it to 0% reverts instantly. (7) **Audit** — every change logged for compliance. The safeguards reflect that payments fail *closed* and need stronger governance than a UI tweak: never auto-advance past a reconciliation mismatch, and never let the new path have side effects the old path can't reverse.

#### Q93. [Practical] How do you safely remove a flag that's been at 100% for months, when you're not sure what still depends on it?
Removing a "permanent 100%" flag is risky because the **off path may have rotted** and something unexpected might still read it. Safe removal: (1) **Find all references** — use code-reference scanning (provider feature) plus a repo-wide search across *all* services for the flag key (including string concatenation and config). Don't trust one repo. (2) **Confirm it's truly always-on** — check evaluation telemetry that it has served only the `true` variation for the soak period across every environment; a rare `false` (a forgotten targeting rule, a specific segment) means it's not actually 100%. (3) **Make the constant explicit first** — in a PR, replace `flags.enabled(X)` with the constant `true` and **delete the dead else-branch**, keeping the change mechanical and reviewable; run the full test suite. (4) **Deploy the code change, then retire the flag** in the management system (not the reverse — if you delete the flag first, any lingering eval returns the *code default*, which may be the wrong value). (5) **Soak** before removing the management entry, so you can re-add if telemetry shows a straggler. The ordering rule: **remove code dependence first, retire the flag second**, so a missed reference fails toward the value the flag was actually serving.

#### Q94. [Coding] Implement a CI check that fails the build when a flag exceeds its TTL or is referenced without metadata.
**Problem:** Enforce lifecycle discipline automatically: every flag must have an owner/type/TTL, and a flag past its removal date fails the build.

```java
public final class FlagLintCheck {
    record FlagMeta(String key, String owner, String type, LocalDate createdAt, int ttlDays) {}

    /** Returns lint violations; a non-empty list should fail the CI step (exit non-zero). */
    public static List<String> lint(List<FlagMeta> declared, Set<String> referencedInCode,
                                    LocalDate today) {
        List<String> violations = new ArrayList<>();

        for (FlagMeta f : declared) {
            if (f.owner() == null || f.owner().isBlank())
                violations.add("Flag '" + f.key() + "' has no owner.");
            if (f.type() == null)
                violations.add("Flag '" + f.key() + "' has no type (release/ops/experiment/permission).");
            if (f.createdAt().plusDays(f.ttlDays()).isBefore(today))
                violations.add("Flag '" + f.key() + "' is past its TTL ("
                    + f.ttlDays() + "d) — remove it or extend with justification.");
        }
        // Flags used in code but never declared with metadata.
        for (String key : referencedInCode)
            if (declared.stream().noneMatch(m -> m.key().equals(key)))
                violations.add("Flag '" + key + "' referenced in code but has no metadata entry.");

        return violations;
    }
}
```

**Notes:** Wire this into CI so the step exits non-zero on any violation — that's what makes lifecycle rules *enforced* rather than aspirational. The `referencedInCode` set comes from a source scan (or the provider's code-reference API). **Edge cases:** allow an explicit, reviewed **TTL extension** (a metadata field with a reason) so legitimately long-lived flags aren't blocked, but make extending *visible* in review; treat permanent permission/config flags as a distinct type exempt from TTL but still requiring an owner. This turns "we should clean up flags" into a gate the codebase enforces on every PR.

#### Q95. [Practical] An on-call engineer pages you: a flag flip didn't propagate to one region. How do you diagnose a partial propagation failure?
A change live in three regions but not a fourth points to a **regional delivery-tier problem**, not the flag config itself. Diagnose: (1) **Confirm the config is correct globally** — the dashboard/version shows the new ruleset version; this rules out "you didn't actually save it." (2) **Check that region's relay/edge proxy** — if it lost its upstream streaming connection or is stuck on an old version, every SDK behind it serves stale data; check the relay's reported ruleset version vs the global one. (3) **Network egress** in that region — a firewall/proxy change may be buffering or blocking the SSE stream, so SDKs there silently fell back to last-known data. (4) **SDK health** — are instances in that region logging stream reconnect failures? (5) **Persistent store skew** — if SDKs read from a regional shared store (daemon mode) and that store didn't get the update, they're pinned to old flags. Mitigation while diagnosing: if it's a kill switch and one region is stuck on the dangerous value, you may need to **fail that region's traffic away** or restart its relay to force a fresh fetch. The structural defenses: emit **ruleset version per node** so skew is visible on a dashboard, and monitor relay-to-control-plane lag per region so partial propagation alarms *before* an incident.

#### Q96. [Coding] Implement propagation-lag observability: stamp each evaluation with the ruleset version and expose a per-node freshness metric.
**Problem:** Make "which version served this request" and "how stale is this node" measurable, so partial-propagation problems are visible.

```java
public class VersionedFlagEvaluator {
    private volatile long rulesetVersion;          // updated atomically on each ruleset swap
    private volatile long rulesetAppliedAtMillis;  // when this node applied that version
    private final Map<String, Boolean> store;

    /** Called by the streaming/polling layer when a new ruleset snapshot is applied. */
    public void applyRuleset(long version, Map<String, Boolean> snapshot) {
        this.store.putAll(snapshot);
        this.rulesetVersion = version;             // monotonic; never go backwards
        this.rulesetAppliedAtMillis = System.currentTimeMillis();
    }

    public boolean eval(String key, boolean def, Span span) {
        boolean v = store.getOrDefault(key, def);
        span.setAttribute("flag." + key, v);
        span.setAttribute("flag.ruleset_version", rulesetVersion);   // provenance per request
        return v;
    }

    /** Gauge for monitoring: how long since this node last applied an update. */
    public long stalenessMillis() {
        return System.currentTimeMillis() - rulesetAppliedAtMillis;
    }
}
```

**Notes:** Stamping `ruleset_version` on the span turns "request R was served by version 4821" into a queryable fact — essential for explaining incidents and experiments. Exposing `stalenessMillis()` as a gauge (per node/region) lets you **alert on propagation lag** before it causes a partial-flip incident. **Edge cases:** enforce monotonic version application (ignore an out-of-order older patch) so a node never flip-flops; swap the snapshot **atomically** so no request sees a half-applied ruleset; and keep the version comparison cross-region so you can see the *spread* of versions across the fleet, which is the real signal of propagation health.

#### Q97. [Practical] Two teams independently created flags that touch the same checkout code, and a combination broke. How do you make such interactions discoverable before they bite?
The problem is **invisible coupling** — two flags that touch the same code/data with no declared relationship. Make it discoverable: (1) **Code-reference scanning by file/module** — surface that flag A and flag B both modify `CheckoutService`, so a reviewer or a dashboard can see the overlap and flag the risk. (2) **Shared canary cohort** — route the same internal/canary segment through *all* in-flight flags so any interaction surfaces at 1%, not at the product of two percentages in prod. (3) **Flag-set enrichment on traces** — emit the full active flag set per request so an incident query reveals *which combination* the failing requests carried. (4) **Prerequisites for true dependencies** — if B is only valid when A is on, declare it so the bad combination is unrepresentable. (5) **Rollout coordination** — a shared calendar/registry of in-flight rollouts on shared subsystems so two high-risk ramps aren't scheduled to overlap; serialize them. (6) **Reduce flag count** so there are simply fewer pairs. The cultural piece: ownership of *shared* code (like checkout) should include a "what flags are live here right now?" check in the review template, turning an invisible coupling into a visible one.

#### Q98. [Coding] Implement a guarded-rollout controller that auto-advances or auto-rolls-back based on a guardrail metric.
**Problem:** Close the loop: ramp the flag step by step, advancing only if a guardrail metric stays healthy, and reverting to 0% if it regresses.

```java
public class GuardedRollout {
    private final int[] steps = {1, 5, 25, 50, 100};   // rollout percentages
    private int current = 0;
    private final FlagAdmin admin;       // sets the flag's rollout %
    private final Metrics metrics;       // per-cohort guardrail metrics

    public GuardedRollout(FlagAdmin admin, Metrics metrics) {
        this.admin = admin; this.metrics = metrics;
    }

    /** Called once per soak interval. Returns true while the rollout is still progressing. */
    public boolean tick(String flagKey) {
        double treatmentErr = metrics.errorRate(flagKey, "treatment");
        double controlErr   = metrics.errorRate(flagKey, "control");

        if (treatmentErr > controlErr * 1.5 && metrics.hasEnoughSamples(flagKey)) {
            admin.setRollout(flagKey, 0);        // guardrail breached → instant safe rollback
            log.error("Guarded rollout of {} ABORTED: treatment err {} vs control {}",
                      flagKey, treatmentErr, controlErr);
            return false;
        }

        if (current < steps.length - 1) {        // healthy → advance one step after the soak
            current++;
            admin.setRollout(flagKey, steps[current]);
            return true;
        }
        return false;                            // reached 100% and healthy → done
    }
}
```

**Notes:** The flag percentage is the **control output**, the guardrail metric is the **measured signal**, and the comparison is the **controller** — the flag is the actuator in a feedback loop. The **safe default of 0% on any breach** means ambiguity halts the ramp. **Edge cases:** require `hasEnoughSamples` before judging (don't abort on noise from 1%); use a **peeking-safe / sequential** comparison rather than a raw threshold so continuous checking doesn't false-alarm; define cohorts from **exposure events** (right denominator); and add a manual override so a human can pause or force-rollback. This is the mechanism behind "guarded rollouts."

#### Q99. [Practical] Your flag provider bill is growing with per-MAU pricing and flag count. How do you reduce cost without losing capability?
Attack both cost drivers — **MAU** and **flag count** — without giving up the control plane. (1) **Cull stale flags** — every removed flag reduces management overhead and, on per-flag plans, direct cost; run the cleanup program (telemetry + code-reference scanning) and enforce TTLs so the count stops growing. (2) **Reduce billed MAU** — use a **relay/edge proxy** so you control evaluation and event volume; deduplicate and **sample analytics/exposure events** (you rarely need every evaluation event); avoid sending high-cardinality contexts that inflate counts; and don't double-count the same human across anonymous + identified contexts (reconcile identities). (3) **Right-size environments** — non-prod traffic shouldn't be billed as production MAU; separate keys/plans. (4) **Evaluate self-hosting** for the highest-volume use cases — open-source Unleash/Flagsmith remove per-MAU pricing entirely at the cost of running infrastructure; a hybrid (self-host the high-volume flags, SaaS for experimentation) is common. (5) **Consolidate vendors** if you're paying for overlapping tools. The framing: cost is a *symptom of flag debt and uncontrolled event volume*, so the same hygiene that improves reliability (fewer flags, controlled evaluation, sampled events) also reduces the bill.

### 🔴 — extended

#### Q100. [Practical] Design a migration from a SaaS flag provider to self-hosted Unleash with zero behavior change and no big-bang cutover. 
Migrate incrementally behind your own abstraction, validating equivalence at each step. (1) **Pre-req: vendor-isolating facade** — if every call already goes through a `FeatureFlags` interface (Set 1), the provider is swappable behind one implementation; if not, introduce it first. (2) **Replicate config** — export flags, segments, and targeting rules from the SaaS provider and recreate them in Unleash (its activation-strategy model differs, so map percentage rollouts, segments, and prerequisites carefully; some advanced experimentation features may not map 1:1 — decide their fate explicitly). (3) **Shadow/compare** — run a **dual-evaluation** mode where each request evaluates the flag in *both* providers, serves the **incumbent's** value, and logs any divergence (a scientist pattern at the provider level). Drive divergence to zero; mismatches reveal mis-mapped rules or bucketing differences. (4) **Bucketing parity** — confirm Unleash's hashing produces the same (or an acceptably different) split; if stickiness must be preserved for live experiments, plan for the reshuffle or freeze experiments during cutover. (5) **Cut over per service / per flag** — flip the facade implementation behind a config (ironically, a flag), one service at a time, watching metrics; keep the SaaS as fallback until confidence is high. (6) **Decommission** — once all services run on Unleash and the divergence log is clean for a soak, remove the SaaS dependency. The principles: **one swappable seam, dual-run to prove equivalence, incremental cutover with rollback, explicit handling of features that don't port** — never a flag-day switch of a request-path dependency.

#### Q101. [Theory] How would you architect flag evaluation for an edge/serverless environment where there's no long-lived process to hold an SDK singleton?
The singleton-with-streaming model assumes a long-lived process; serverless/edge breaks that (cold starts, no persistent connection, short-lived isolates). Architectures: (1) **Edge-evaluated via a proxy/CDN** — run the provider's **edge SDK** at the CDN/edge worker, which keeps flags warm at the edge and evaluates near the user with sub-millisecond latency; the serverless function calls the edge for resolved values, or the edge itself renders the decision. (2) **Bootstrapped/pushed config** — push the **ruleset to a low-latency store** (KV at the edge, e.g., a Workers KV / edge config) that functions read on each invocation; the store is updated out-of-band by the control plane, so functions never hold a streaming connection. (3) **Resolved-values payload** — a gateway evaluates flags once and passes resolved values into the function via headers/context, so the function does no evaluation at all. (4) **Aggressive local cache with short TTL** — accept brief staleness in exchange for not fetching per invocation, and rely on the edge KV's fast propagation. Key constraints to respect: **no per-invocation network call to the provider** (cold-start latency and connection storms), **deterministic bucketing still works** because it's a pure function (no shared state needed), and **safe defaults** matter even more because cold isolates may briefly lack config. The general pattern: move evaluation to a **warm edge tier or a fast config store**, and feed serverless functions resolved values or a cached ruleset rather than a live SDK connection.

#### Q102. [Coding] Implement a thread-safe, atomically-swappable in-memory flag store suitable for a high-throughput evaluator.
**Problem:** Evaluations happen on every request across many threads; ruleset updates arrive concurrently. Reads must be lock-free and fast; an update must apply atomically (no request sees a half-updated ruleset).

```java
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class AtomicFlagStore {

    /** Immutable snapshot: the whole ruleset + its version, swapped as a unit. */
    public record Snapshot(long version, Map<String, Object> flags) {}

    private final AtomicReference<Snapshot> ref =
        new AtomicReference<>(new Snapshot(0L, Map.of()));   // empty until first update

    /** Hot path: lock-free read of a consistent snapshot. */
    public Object get(String key, Object def) {
        Snapshot snap = ref.get();                 // single volatile read → consistent view
        return snap.flags().getOrDefault(key, def);
    }

    public long version() { return ref.get().version(); }

    /** Update path: replace the entire snapshot atomically; monotonic versions only. */
    public void apply(long newVersion, Map<String, Object> newFlags) {
        Snapshot next = new Snapshot(newVersion, Map.copyOf(newFlags));  // immutable copy
        ref.updateAndGet(cur -> newVersion > cur.version() ? next : cur); // ignore stale/older updates
    }
}
```

**Time/Space:** read is O(1) and lock-free (one `AtomicReference.get` + map lookup); update is O(N) to copy the snapshot but happens rarely. **Why this shape:** because the whole ruleset is an **immutable snapshot swapped behind an `AtomicReference`**, every evaluation sees either the fully-old or fully-new ruleset — never a torn, half-applied state — without any read-side locking, which is what high-throughput evaluation needs. The `updateAndGet` guard enforces **monotonic versions** so an out-of-order older patch can't roll a node backwards. **Edge cases:** make the inner map truly immutable (`Map.copyOf`) so a reader holding an old snapshot is unaffected by the next update; if flags carry complex objects, ensure those are immutable too.

#### Q103. [Practical] A regulator asks you to prove what flag value user U received on date D. Walk through producing a defensible answer.
This requires **reproducible evaluation**, not just a config audit log. Steps: (1) **Retrieve the ruleset version live on date D** — from the immutable, versioned ruleset history (every change produced a new version with timestamp/author), load the exact version in effect at that moment, including the segments and prerequisites it referenced. (2) **Reconstruct user U's evaluation context** for that time — the attributes that drove targeting (plan, country, etc.); ideally these were captured on the recorded evaluation event, otherwise reconstruct from your systems of record as of date D. (3) **Replay deterministically** — because bucketing is a **pure function** of `(salt, flagKey, bucketingKey)` and rules are data, re-running ruleset version V against U's context **reproduces the exact decision** with no hidden state: the same bucket slot, the same matched rule, the same variation. (4) **Present provenance** — "On date D, ruleset version 4821 was live; user U's context matched the `country==CA` rule (index 0); the rollout hash placed them in slot 73,402 < the 50% threshold; variation 1 (`true`) was served; reason `RULE_MATCH[0]`." (5) **Corroborate with the recorded evaluation/exposure event** if one was logged, showing the value and version actually served. The defensibility comes from **determinism + versioned history + captured context** — that triad turns "we believe it was on" into a provable replay. Pitfalls to have closed in advance: non-deterministic inputs (wall-clock rules, random tie-breaks), un-versioned segment edits, and PII-retention limits that delete the context needed to replay (pseudonymize but retain the bucketing key).

#### Q104. [Practical] You're seeing intermittent, hard-to-reproduce flag flicker for a subset of users. Give a systematic root-cause approach.
Flicker (a user seeing a feature on, then off, then on) means the **evaluated value is changing when it shouldn't** — attack the determinism and consistency assumptions methodically. (1) **Pin a reproduction** — collect the affected user keys and, for each, log `boolVariationDetail` (value, reason, variation index, ruleset version) on every request; flicker with a *changing reason* or *changing version* localizes the layer. (2) **Unstable bucketing key** — if the context key changes between requests (anonymous ID re-minted, key derived from something volatile like session ID or a load-balancer-assigned value), the user re-buckets each time; this is the most common cause — confirm the key is identical across the flickering requests. (3) **Ruleset version skew across nodes** — if requests hit different instances with different ruleset versions during/after a flip, the user oscillates depending on which node served them; the logged version will differ — fix by bounding propagation and not depending on globally-atomic flips. (4) **Anonymous↔identified transitions** — bucketing on anon ID some requests and account ID others (e.g., before/after a token refresh) causes jumps; carry a single stable randomization unit. (5) **Multiple SDK instances / clients** with different state (e.g., a per-request client bug, or two services disagreeing). (6) **Cache vs live disagreement** — a CDN/client cache serving an old resolved value interleaved with fresh server evaluations. The systematic principle: flicker is a **determinism or consistency violation**; instrument value+reason+version+bucketing-key per request, and the dimension that changes when the value flickers points straight at the culprit (usually an unstable key).

#### Q105. [Coding] Implement deterministic, salted, multi-context bucketing that buckets by a chosen context kind (e.g., org) — the core of org-level sticky rollouts.
**Problem:** Given a multi-context evaluation (user + org + device), bucket a rollout by a *configurable* kind so, e.g., whole orgs flip together and the assignment is stable and decorrelated across flags.

```java
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class MultiContextBucketing {

    private static final long SCALE = 100_000L;

    /** @param contexts kind -> stable key (e.g. {"user":"u123","org":"o42","device":"d7"})
     *  @param bucketBy which kind's key drives the rollout hash (e.g. "org"). */
    public static boolean inRollout(String flagKey, String salt,
                                    Map<String, String> contexts, String bucketBy,
                                    double percent) {
        String key = contexts.get(bucketBy);
        if (key == null) return false;                  // can't bucket → fail safe (off)

        String seed = flagKey + "." + salt + "." + bucketBy + ":" + key;  // kind in seed → org-stable & decorrelated
        long bucket = Integer.toUnsignedLong(
                Bucketing.murmur3_32(seed.getBytes(StandardCharsets.UTF_8), 0)) % SCALE;

        return bucket < (long) (percent / 100.0 * SCALE);
    }
}

// usage: roll out to 30% of ORGS — every user in an included org gets it, deterministically.
boolean on = MultiContextBucketing.inRollout(
    "checkout-redesign", "2026-q3",
    Map.of("user", user.id(), "org", user.orgId(), "device", deviceId),
    "org", 30.0);
```

**Time/Space:** O(L) in the bucketing key length, O(1) memory; reuses the MurmurHash3 from Set 1. **Why this shape:** putting the **kind and its key** in the seed makes the rollout stable *per org* (all members hash identically) and the `salt` + `flagKey` keep it **decorrelated across flags** and **re-randomizable** per experiment. Choosing `bucketBy = "user"` instead would give per-user stickiness from the same function — the unit of randomization is now a parameter, matching the unit of feature impact. **Edge cases:** a missing bucketing key fails **safe (off)** rather than throwing; users belonging to multiple orgs need a defined primary/active org as the key; and analyze experiments at whatever kind you bucketed by (org-bucketed ⇒ org-level analysis).

#### Q106. [Theory] Reflect: across everything, what is the single highest-leverage practice that separates teams who succeed with flags from teams who drown in them, and why?
The highest-leverage practice is **treating flag lifecycle (especially removal) as a first-class, automated, non-optional part of the workflow** — every flag is born with an owner, a type, and a removal plan, and the system *enforces* cleanup rather than relying on goodwill. Why this single practice dominates: nearly every flag failure mode downstream is a symptom of skipped lifecycle. **Flag debt**, the maze of untested conditionals, comes from never deleting. **The rotted fallback path** that gives false safety comes from a flag living long past its purpose. **Combinatorial outages** scale with the *number of live flags* — fewer flags, fewer interactions. **The provider bill** grows with flag count. **Incident confusion** ("which of these 400 flags is a live lever?") is a lifecycle-metadata gap. **Security risk** from a stale "100%" flag re-enabling rotted code is a removal failure. Teams that succeed make the *easy* path the *disciplined* path: a typed facade so flags are discoverable, TTLs and a removal ticket at creation, CI that fails on expired or metadata-less flags, telemetry + code-reference scanning to find dead flags, and stale-flag reports turned into auto-PRs. The deep reason it's highest-leverage is that flags are **debt-by-default**: a flag is a temporary `if` that the codebase will keep paying interest on forever unless something forces its removal. The technical mechanics (bucketing, streaming, targeting) are solved by the tooling; the thing the *team* must own is making sure flags **die on schedule**. Master that, and the rest of the flag system stays a reliable control plane instead of becoming a liability.

## ✅ Key Takeaways
- Feature flags **decouple deploy from release**, turning "go live" into a reversible, audience-controlled runtime decision and a per-feature rollback that takes seconds.
- Know the four flag **types** (release, experiment, ops/kill-switch, permission) and that the real axes are **dynamism × longevity** — misclassification causes debt.
- **Deterministic bucketing on a stable key** is the foundation of percentage rollout, canary, and sticky A/B assignment.
- **Evaluate where you can trust the data**: server-side for anything sensitive; deliver only resolved *values* to untrusted clients; never gate authorization client-side.
- Flags + **trunk-based development** + CI enable continuous integration of incomplete work behind off-by-default toggles.
- **Lifecycle discipline is mandatory**: every flag needs an owner, a type, and a removal plan; stale flags are reliability risk, not just clutter.
- The flag system is **request-path infrastructure** — design it to fail safe (local cache → fallback store → safe defaults) and govern it (audit, approvals, RBAC) like any production control plane.

## ⚠️ Common Pitfalls
- **Never deleting flags** — accumulating flag debt until the codebase is a maze of untested conditionals and a stale "100%" flag re-enables rotted code.
- **Letting the fallback path rot** — a flag is only safe if *both* branches work and are tested; an unmaintained legacy branch gives false safety.
- **Hard-failing on provider outage** — not passing safe defaults (or not caching the ruleset) so a flag-service blip becomes your outage.
- **Client-side authorization** — using an untrusted client-evaluated flag to gate access to data or money.
- **Per-request SDK clients / magic-string keys** — creating a flag client per request (no cache, connection storm) and scattering stringly-typed keys instead of a typed facade.
- **Unstable bucketing** — keying on something non-stable (or re-minting anonymous IDs) so users flicker between variants and experiments are invalid.
- **Combinatorial blind spots** — ramping multiple risky flags simultaneously and never testing the interacting combination.
- **Flagging everything** — paying flag cost for trivial changes, exploding flag count and state space.

## 📚 Further Reading
- Martin Fowler / Pete Hodgson — "Feature Toggles (aka Feature Flags)" (martinfowler.com) — the canonical taxonomy and lifecycle guidance.
- *Trunk-Based Development* (trunkbaseddevelopment.com) — branching model and how flags enable continuous integration.
- LaunchDarkly docs & blog — SDK models, streaming, targeting, experimentation, guarded rollouts, and governance features.
- Unleash docs (getunleash.io) — open-source/self-hosted flag architecture, activation strategies, and edge proxy.
- Flagsmith docs (flagsmith.com) — open-source remote config + flags, multi-environment, and edge evaluation.
- *Accelerate* (Forsgren, Humble, Kim) — why decoupling deploy from release correlates with elite delivery performance.
- Kohavi, Tang, Xu — *Trustworthy Online Controlled Experiments* — the rigorous reference for A/B testing correctness (SRM, peeking, interference).
