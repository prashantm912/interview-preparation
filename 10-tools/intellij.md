# IntelliJ IDEA

IntelliJ IDEA is JetBrains' flagship JVM IDE, known for deep static analysis, ergonomic refactoring, and a debugger that goes far beyond breakpoints. This guide covers it from first-day productivity through staff-level workflow design, JVM tuning, and team standardization, current through the 2025/2026 releases.

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

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the difference between IntelliJ IDEA Community and Ultimate editions?

Community Edition (CE) is free and open-source (Apache 2.0). It fully supports core JVM languages — Java, Kotlin, Groovy, Scala (via plugin) — plus Maven, Gradle, Git, JUnit, and the full refactoring/debugging engine. Ultimate is commercial and adds first-class web/enterprise frameworks: Spring/Spring Boot, Jakarta EE, JPA/Hibernate, a database tool window, HTTP client, JavaScript/TypeScript, profiler integration, and remote/cloud tooling. The decision usually comes down to whether you do Spring + database + web work daily (Ultimate pays for itself) or pure backend library/Android work (CE is often enough; Android Studio is built on CE). Note: since 2024 JetBrains also offers a free non-commercial license for Ultimate to hobbyists/students under restrictions.

### Q2. [Practical] How do you find anything in the IDE — files, classes, symbols, actions?

The single most important productivity habit is **Search Everywhere** — press `Shift` twice. It unifies classes, files, symbols, settings, and actions in one popup. Beyond it:

```
Double Shift        → Search Everywhere (everything)
Ctrl+N  / Cmd+O     → Go to Class
Ctrl+Shift+N        → Go to File
Ctrl+Alt+Shift+N    → Go to Symbol (method/field)
Ctrl+Shift+A        → Find Action (run any menu command by name)
Ctrl+E              → Recent Files (and recently changed)
```

`Find Action` (`Ctrl+Shift+A`) is the "I forgot the shortcut" escape hatch — type "reformat" or "toggle case sensitivity" and run it directly. In production work, learning to navigate without the mouse is the largest single speed gain a junior engineer can make.

### Q3. [Practical] How do you reformat, optimize imports, and auto-fix code as you type?

```
Ctrl+Alt+L          → Reformat code (per project code style)
Ctrl+Alt+O          → Optimize imports (remove unused, sort)
Alt+Enter           → Show intention actions / quick-fixes
```

`Alt+Enter` is the context-aware "fix it" key: on a red error it offers imports or signature changes; on a warning it offers refactors (e.g., "replace with lambda", "introduce variable"). The right production practice is to **not** rely on manual reformatting — configure **Actions on Save** (Settings → Tools → Actions on Save) to reformat and optimize imports automatically, so the committed diff is always clean and code-style noise never pollutes pull requests.

### Q4. [Theory] What are live templates and why use them?

Live templates are expandable code snippets triggered by an abbreviation + `Tab`. Built-in examples for Java:

```
sout  → System.out.println();
psvm  → public static void main(String[] args) { }
fori  → for (int i = 0; i < ; i++) { }
iter  → enhanced for loop over a collection
ifn   → if (x == null) { }
```

A *postfix* template is even smarter — type the expression first, then the action: `list.for` expands to a for-loop over `list`, and `value.nn` expands to a null check. They reduce keystrokes and, more importantly, enforce consistent patterns. You can define custom templates (Settings → Editor → Live Templates) with variables and context, which teams use to standardize logging or builder boilerplate.

### Q5. [Practical] How do you set a breakpoint and inspect state during debugging?

Click the gutter next to a line (or `Ctrl+F8`) to set a line breakpoint, then run with the debug action (`Shift+F9`). Once paused:

```
F8   → Step Over     F7   → Step Into
F9   → Resume        Shift+F8 → Step Out
Alt+F8 → Evaluate Expression (run arbitrary code in current frame)
```

The Variables pane shows the current frame's locals; the Frames pane shows the call stack. Hovering a variable in the editor shows its value inline. The key beginner insight is **Evaluate Expression** (`Alt+F8`): you can run any expression — call a method, build a stream, mutate a field — against the paused state, which is far faster than adding print statements and re-running.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Walk through the refactorings you use daily and why IntelliJ's are safe.

IntelliJ refactorings are AST- and type-aware, not text replacements, so they update every usage including reflection-free call sites, Javadoc references, and string-based references it can resolve. The workhorses:

```
Shift+F6            → Rename (across project, incl. comments/strings opt-in)
Ctrl+Alt+M          → Extract Method
Ctrl+Alt+V          → Extract Variable
Ctrl+Alt+F          → Extract Field
Ctrl+Alt+P          → Extract Parameter
F6 / Refactor menu  → Move class/member, Change Signature, Inline, Pull Up/Push Down
```

The safety comes from the IDE building a usage graph first and warning about conflicts (e.g., a rename that would shadow a field, or a move that breaks visibility). **Change Signature** (`Ctrl+F6`) is especially powerful: add/reorder/remove parameters and IntelliJ rewrites all call sites with default values you specify. The trade-off versus blind find/replace is that refactorings only touch resolvable references — dynamic reflection or Spring XML wiring referenced by string may still need manual checks, which is why "Search in comments and strings" and "Safe Delete" (which reports remaining usages) matter.

### Q7. [Practical] You have a `List<Order>` and a stream pipeline returns the wrong total. How do you debug it without rewriting the code?

Streams are notoriously hard to debug because the whole pipeline executes lazily in one expression. IntelliJ's **Stream Debugger** solves this. Set a breakpoint on the stream statement, and when paused click the **Trace Current Stream Chain** button in the debugger toolbar. It runs the pipeline and shows a visual, stage-by-stage table: input elements on the left, and how each `filter`/`map`/`flatMap`/`collect` transforms or drops them, with arrows connecting elements across stages.

```
orders.stream()
  .filter(o -> o.getStatus() == PAID)   // [12 in] → [7 pass]   ← see exactly which 5 dropped
  .map(Order::getAmount)                 // [7] → [7]
  .reduce(BigDecimal.ZERO, BigDecimal::add);
```

In production debugging this immediately reveals classic bugs: a `filter` predicate that's too strict, a `map` producing nulls, or a `distinct()` collapsing rows you needed. You confirm the faulty stage visually rather than scattering `peek(System.out::println)` calls. Combine with **Evaluate Expression** to re-run a fixed predicate against the same paused data.

### Q8. [Practical] How do you set a conditional breakpoint and why is that better than print debugging?

Right-click a breakpoint to open its settings and enter a boolean **Condition**, e.g. `order.getId() == 4242 && order.getAmount().signum() < 0`. The breakpoint only suspends when the condition is true. You can also set:

- **Hit count / pass count** — break only after the Nth hit (great for "fails on the 1000th iteration").
- **Log message instead of suspend** — uncheck "Suspend" and check "Evaluate and log" to emit a value without stopping (a non-invasive `println` that lives only in the IDE, not the source).
- **Field watchpoints** — break when a specific field is read or written, invaluable for "who mutated this?".
- **Exception breakpoints** — break the instant any `NullPointerException` (or a chosen subclass) is thrown, anywhere, before the stack unwinds.

This beats print debugging because it requires no recompile, captures the *exact* failing case in a huge loop, and preserves the full live stack/heap for inspection at the moment of failure.

### Q9. [Theory] How does IntelliJ handle Maven/Gradle projects, and what is the "reimport/sync" step doing?

When you open a build file, IntelliJ delegates to the build tool to resolve the dependency graph and module structure, then maps that into its internal project model (modules, content roots, library classpaths, language level). The **Sync** (Gradle) / **Reload** (Maven) step re-runs this resolution after you change dependencies or plugins. A common production gotcha is the difference between IntelliJ's own build and the build tool's: by default Gradle projects can be configured to build/run *through* Gradle or via IntelliJ's compiler (Settings → Build → Gradle → "Build and run using"). Building through Gradle matches CI exactly but is slower; IntelliJ's compiler is faster for tight inner loops. For reproducible behavior on a team, pin "Build and run using: Gradle" so local results match the pipeline.

### Q10. [Coding] Write a custom live template for a parameterized SLF4J logger declaration, and explain the variable mechanism.

**Problem:** Every class needs `private static final Logger log = LoggerFactory.getLogger(ClassName.class);`. Typing this by hand is error-prone (people copy-paste the wrong class name). Create a live template `logger` that auto-fills the enclosing class.

**Solution** (Settings → Editor → Live Templates → new template, abbreviation `logger`, applicable in Java → declaration):

```java
private static final org.slf4j.Logger log =
    org.slf4j.LoggerFactory.getLogger($CLASS$.class);
```

Then click **Edit variables** and bind `$CLASS$` to the built-in expression `className()`. Enable **Shorten FQ names** so IntelliJ auto-imports `Logger`/`LoggerFactory` and collapses the fully-qualified names on expansion.

```
Variable | Expression     | Result
$CLASS$  | className()     | the enclosing class name, auto-filled
$END$    | (implicit)      | where the caret lands after expansion
```

**Why it works:** `className()` is a template function evaluated at expansion time against the PSI (Program Structure Interface) tree, so it always resolves the *correct* enclosing class even after a rename. **Edge cases:** in a nested/inner class `className()` returns the innermost class — usually what you want; for a static nested logger you'd verify the level. **Time saved:** ~15 keystrokes and one entire class of copy-paste bugs eliminated per file. This is a real pattern teams ship in shared template sets.

### Q11. [Practical] How do you create and parameterize run/debug configurations, and share them with the team?

A run configuration captures *how* to launch something: main class, program args, VM options, env vars, working dir, active profiles, and the classpath module. Create them via **Run → Edit Configurations**, or let IntelliJ auto-create one from the gutter run icon. Production-relevant settings:

```
VM options:   -Xmx512m -Dspring.profiles.active=local -Dlogging.level.root=DEBUG
Env vars:     DB_URL=jdbc:postgresql://localhost:5432/app  (or "EnvFile" plugin for .env)
Program args: --server.port=8081
```

To share with the team, check **"Store as project file"** — IntelliJ writes the config to `.idea/runConfigurations/*.xml` (or a `.run/*.run.xml` file), which you commit. Now everyone gets the same "Run Backend (local)" config. Avoid hardcoding secrets in committed configs; use env files or IDE-level (non-shared) overrides for credentials. **Templates** (the gear → "Edit configuration templates") let you set defaults for all new JUnit/Application configs at once.

### Q12. [Theory] Compare IntelliJ IDEA with VS Code and Eclipse for Java work.

```
                IntelliJ IDEA        Eclipse              VS Code (+ Java ext)
Indexing/       Deep, project-wide   Good, sometimes      LSP-based; lighter,
analysis        semantic model       stale incremental    less holistic
Refactoring     Best-in-class,       Solid but fewer      Limited; basic rename/
                type-safe, broad     options              extract
Resource use    Heavy RAM/CPU        Moderate             Lightest, fast startup
Spring/JPA      First-class (Ult.)   Via STS plugins      Via extensions, weaker
Cost            CE free / Ult. paid  Free                 Free
Best for        Daily JVM dev,       Legacy/RCP, free     Polyglot, remote,
                large codebases      enterprise           quick edits, low-RAM
```

IntelliJ wins on *understanding* code — its semantic index powers smarter completion, safer refactors, and richer inspections. The cost is memory and a heavier feel. Eclipse is free and capable but its refactoring and Spring tooling lag. VS Code is excellent as a fast, multi-language, low-footprint editor and dominant for remote/container dev, but for a 2-million-line Java monolith with heavy refactoring needs, IntelliJ's analysis depth is the differentiator. The honest staff-engineer answer: pick per task — VS Code on a Chromebook-class machine or for a quick repo browse; IntelliJ for sustained JVM engineering.

### Q13. [Practical] How do you use the built-in Git integration effectively, including resolving merge conflicts?

The Git tool window (`Alt+9`) and the Commit tool window centralize VCS work. Key flows:

- **Commit dialog**: stage hunks selectively, run "Reformat/Optimize imports/Analyze" before commit via checkboxes, and amend.
- **Annotate/Blame** (right-click gutter → Annotate) shows who/when per line, with click-through to the commit.
- **Local History** is IDE-level versioning independent of Git — recover code you never committed (a lifesaver after a bad refactor).
- **Conflict resolution**: the 3-pane merge tool shows *Left (yours) | Result | Right (theirs)* with one-click accept-left/accept-right per change and a magic "auto-resolve non-conflicting" button.

```
   Yours (HEAD)        Merged Result        Theirs (incoming)
  ┌──────────┐        ┌──────────┐         ┌──────────┐
  │  line A  │ ──►►─► │  line A  │ ◄─◄◄──── │  line A  │
  │  line X  │ accept │  line X  │  accept │  line Y  │
  └──────────┘  left  └──────────┘  right  └──────────┘
```

The merge tool understands code semantically enough to highlight that two edits touched the same statement, reducing the "I accepted the wrong side" mistakes that plague raw CLI merges.

---

## 🟠 Advanced (8–12 yrs)

### Q14. [Practical] A microservice intermittently NPEs in production but you can't reproduce it locally. How do you use remote debugging safely?

Attach a remote debugger to the running JVM via the **JDWP** (Java Debug Wire Protocol) agent. Start the remote process with:

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

`suspend=n` means the app starts normally and you attach later; `address=*:5005` binds the agent. In IntelliJ create a **Remote JVM Debug** configuration with the host/port, set breakpoints (conditional ones keyed to the failing tenant/ID), and attach.

```
 IntelliJ  ──TCP 5005──►  JVM agent (JDWP)  ──►  running service
 (breakpoints, eval)        suspends thread on hit
```

**Production realities and security:** JDWP is *unauthenticated* — anyone who can reach port 5005 can execute arbitrary code in your JVM. Never expose it publicly. The safe pattern is an SSH tunnel: `ssh -L 5005:localhost:5005 user@host`, attach to `localhost:5005`. Prefer a staging replica with production-like data over breaking on the live service, since a suspended thread can stall request handling and a held lock can cascade. Once attached, an **exception breakpoint on `NullPointerException`** with "caught + uncaught" pinpoints the throw site before the stack unwinds. Detach cleanly; leaving JDWP enabled in prod is a standing vulnerability.

### Q15. [Theory] How do you profile a JVM application from IntelliJ, and what's the difference between sampling and instrumentation?

IntelliJ Ultimate bundles **async-profiler** and integrates **JFR (Java Flight Recorder)**. From a run config you can launch with a profiler attached, or "Attach Profiler to Process" on a running PID. The two modes:

- **Sampling** (async-profiler default): periodically captures the stack of running threads. Low overhead (single-digit %), safe for near-production use, gives statistically accurate hot-path data. Best first tool for "where is CPU going?".
- **Instrumentation**: injects timing into method entry/exit for *exact* call counts and durations. High overhead that can distort tight loops (the measurement changes the timing), but precise for "how many times is this called?".

Results render as **flame graphs** and a call tree. async-profiler also samples allocations (`alloc` mode) and locks/wall-clock, so you can profile a CPU-bound hot loop, a lock-contention stall, or GC pressure separately.

```
Flame graph (width = time spent):
  main ████████████████████████████████
   └ handleRequest ███████████████████
      ├ parseJson ████                 ← 12%
      └ renderTemplate ███████████████ ← 70%  ← optimize here
```

The interview-grade point: start with low-overhead **sampling** + flame graph to find the wide frame, switch to **allocation profiling** if GC is the issue, and only reach for instrumentation when you need exact counts on a known suspect.

### Q16. [Coding] Demonstrate using a non-suspending logging breakpoint plus Evaluate Expression to diagnose a concurrency bug, and explain why a plain breakpoint would hide it.

**Problem:** A `ConcurrentHashMap`-backed counter under-counts when multiple threads call `record()`. Pausing on a breakpoint serializes the threads and makes the race vanish (a Heisenbug).

```java
class HitCounter {
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    void record(String key) {
        Integer cur = counts.get(key);          // ← race: read
        counts.put(key, cur == null ? 1 : cur + 1); // ← then write (lost update)
    }
}
```

**Diagnosis without halting:** set a breakpoint on the `put` line, then in its settings **uncheck Suspend** and check **"Evaluate and log"** with the expression:

```java
Thread.currentThread().getName() + " key=" + key + " cur=" + cur
```

Because nothing suspends, threads keep interleaving at production speed and the lost-update pattern remains visible in the console: two threads log the same `cur` value, proving the read-modify-write isn't atomic.

**Fix** (verified live with `Alt+F8` Evaluate Expression before changing source):

```java
void record(String key) {
    counts.merge(key, 1, Integer::sum);  // atomic compute
}
```

**Why a suspending breakpoint fails:** halting one thread lets others "catch up," removing the interleaving window — the bug disappears under observation. **Complexity:** the fix is O(1) amortized per `record`. **Edge case:** under extreme contention `merge` may retry internally but stays correct, unlike the original. This non-invasive logging technique is the standard way to observe timing-sensitive bugs in IntelliJ.

### Q17. [Practical] What structural search/inspection tooling would you use to enforce an architecture rule across a large codebase?

**Structural Search and Replace (SSR)** (Edit → Find → Search Structurally) matches code by AST pattern, not text. For example, ban direct `new Date()` in favor of a clock abstraction, or find every controller method missing `@Transactional`:

```
Search template:  $Type$ $method$($params$);
With constraints: $Type$ matches "javax.persistence.EntityManager", etc.
```

You can save an SSR pattern as a **custom inspection** with a severity (warning/error) and a quick-fix replacement template. Combined with IntelliJ's **scope** feature you can run it across a module and treat violations as build-failing. For dependency-direction rules (e.g., `domain` must not import `web`), the **Dependency Matrix / DSM** and module dependency analysis surface illegal edges. In a real platform team this is how you codify "don't call the legacy DAO directly" so it shows up as a red squiggle for every developer, not a wiki page nobody reads. The trade-off: SSR patterns are powerful but brittle to syntactic variation, so for hard architecture gates teams pair them with ArchUnit tests in CI.

### Q18. [Theory] How do you tune the JVM that runs IntelliJ itself, and when does it actually matter?

IntelliJ runs on its own bundled JBR (JetBrains Runtime). Edit VM options via **Help → Edit Custom VM Options** (writes `idea64.vmoptions`). The settings that matter:

```
-Xmx4096m            # max heap — raise to 4–8g for large monorepos
-XX:+UseG1GC         # default modern collector; good for IDE pause profile
-XX:ReservedCodeCacheSize=512m   # JIT code cache; raise for big projects
-XX:SoftRefLRUPolicyMSPerMB=50   # how aggressively soft refs (caches) are dropped
```

It matters when indexing thrashes: if you see frequent "low memory" warnings or GC pauses freezing the UI on a multi-million-line project, bump `-Xmx`. But more memory is not free — an oversized heap means longer GC pauses and steals RAM from the build tool and running apps. The common mistake is cranking `-Xmx` to 16g on an 8g laptop and making everything *slower*. Right-size it: watch the memory indicator (enable it in settings), and prefer fixing the real cause (e.g., excluded build/output directories, disabled unused plugins) before throwing heap at the problem.

### Q19. [Practical] Indexing is slow and the IDE feels sluggish on a huge repo. Walk through your remediation.

Approach it as a triage, cheapest fix first:

1. **Exclude generated/output dirs** — mark `build/`, `target/`, `node_modules/`, large data dirs as *Excluded* (right-click → Mark Directory As). Indexing them is the #1 cause of slowness.
2. **Disable unused plugins** (Settings → Plugins) — each adds inspections and indexers. Trim language plugins you don't use.
3. **Shared indexes** — for very large projects, JetBrains supports prebuilt **shared indexes** so a new clone doesn't re-index from scratch; teams host them centrally.
4. **Power Save Mode** temporarily disables background inspection/indexing when you just need to read code.
5. **Profile the IDE itself**: Help → "Collect a CPU/Memory snapshot" or the built-in freeze reports tell you which plugin/inspection is the culprit; send to JetBrains or disable it.
6. **JVM heap** (`-Xmx`) only after the above, since more heap won't fix indexing the wrong directories.

In production at a company with a 4M-line monorepo, the single biggest win is usually excluding output directories and adopting shared indexes — clone-to-productive time drops from ~20 minutes of indexing to under 2.

---

## 🔴 Expert (15+ yrs)

### Q20. [Theory] How would you standardize IntelliJ across a 200-engineer org so local dev matches CI and onboarding is fast?

The goal is "clone and run in minutes, identical results for everyone." The levers:

- **`.editorconfig`** at repo root — the cross-IDE source of truth for indentation/line endings; IntelliJ honors it natively, so VS Code/Eclipse users stay consistent too.
- **Shared code style + inspection profiles** — export to `.idea/codeStyles/` and `.idea/inspectionProfiles/` and commit them, or distribute via a **Settings Repository** / **IDE Settings Sync** pointed at an internal Git repo.
- **Committed run configurations** (`.run/*.run.xml`) and **shared run-config templates**.
- **Build through the build tool** (Gradle/Maven), not IntelliJ's compiler, so local == CI.
- **Shared indexes** hosted internally for fast onboarding.
- **A required-plugins manifest** and a curated **`externalDependencies.xml`** so the IDE prompts to install/enable mandated plugins.
- **Pre-commit + CI parity**: the same Spotless/Checkstyle/Detekt rules run in Actions-on-Save locally and as a CI gate.

The trade-off is governance vs. autonomy — over-locking settings frustrates senior engineers, so freeze the things that affect the *committed artifact* (formatting, line endings, build) and leave editor ergonomics (keymap, theme, font) personal.

### Q21. [Behavioral] Tell me about a time tool standardization caused friction. How did you handle it?

A strong answer follows situation → action → result with real trade-offs. Example: *"We mandated a shared formatter and Actions-on-Save across teams. The first PR after rollout was a 4,000-line reformat-only diff that buried real changes and broke `git blame`. Engineers were upset. I (1) added the bulk-reformat commit to `.git-blame-ignore-revs` so blame skipped it, (2) split the reformat into one isolated, reviewed commit per module, and (3) gated future style purely in CI with auto-fix rather than failing builds. The friction was real and partly self-inflicted — I'd underestimated the blame disruption. The lesson I carry: roll out repo-wide tooling behind a flag, communicate the one-time churn, and never combine a mechanical change with semantic ones."* The behavioral signal interviewers want: you owned the misstep, used the IDE/VCS features (blame-ignore, Local History) to mitigate, and changed your rollout process.

### Q22. [Practical] How do you debug a problem that only manifests in a remote container or Kubernetes pod?

Layered approach. For a containerized JVM, expose JDWP inside the container and forward the port:

```
# container CMD
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
# locally
kubectl port-forward pod/my-svc-abc 5005:5005
```

Then attach a **Remote JVM Debug** config to `localhost:5005`. For deeper "the whole environment differs" problems, use **JetBrains Gateway** + **Remote Development**: the IDE backend runs *inside* the remote host/container next to the code, and your laptop runs only a thin client — so indexing, the JDK, env vars, and the filesystem are exactly the deployment environment. This eliminates "works on my machine" gaps that even remote debugging can't, at the cost of needing a beefy remote host. **Security:** mediate access with `kubectl port-forward`/SSH (never a public LoadBalancer on the debug port), prefer ephemeral debug pods over the live serving pod, and disable JDWP in production manifests. For non-JVM concerns, attach the profiler (async-profiler/JFR) the same way to get flame graphs from the real pod.

### Q23. [Theory] Beyond convenience, where are the security implications of IDE usage, and how do you govern them?

Several, and senior engineers are expected to think about them:

- **JDWP exposure** — an open debug port is unauthenticated remote code execution; govern via network policy and never in prod.
- **Plugins are arbitrary code** running with your permissions and repo access. A compromised or malicious marketplace plugin can exfiltrate source or credentials. Govern with an all-list, internal plugin repository, and review of plugin updates — treat them like any third-party dependency.
- **Secrets in committed configs** — VM options/env vars in `.idea`/`.run` files can leak DB passwords and tokens into Git history. Use env files outside VCS, the IDE keychain, or a secrets manager.
- **AI assistants** — code-completion/chat plugins may send source to third-party servers; enterprise policy must define what's allowed and use on-prem/zero-retention modes where required.
- **Untrusted projects** — opening a repo can trigger build scripts (Gradle tasks, Maven plugins) that execute code; IntelliJ's **Trusted Projects** prompt and "Safe Mode" (preview without running build) mitigate drive-by execution from cloned repos.

The governance model is the same as supply-chain security: minimize attack surface, allow-list third-party code, keep secrets out of artifacts, and default to least privilege.

### Q24. [Practical] When would you build a custom IntelliJ plugin or live-template/inspection bundle for your org, and what's the cost?

You build tooling when a recurring, org-specific pattern can't be expressed with config alone and the manual cost is high. Realistic cases: a custom **inspection + quick-fix** that flags use of a deprecated internal API and auto-migrates it; a **line-marker/gutter icon** that links a Spring endpoint to its OpenAPI spec or its dashboard; a **file template** wizard that scaffolds a new service with your conventions. Lighter-weight first: most needs are met by **shared live templates, SSR-based custom inspections, and file/code templates** — no plugin code, just committed config. Reach for a real plugin (IntelliJ Platform SDK, Gradle `intellij` plugin) only when you need UI, PSI manipulation, or build/run integration. The cost is ongoing: plugins must track IDE API changes across releases (the Platform API shifts yearly), need their own CI/publishing, and a broken plugin can destabilize everyone's IDE. The staff-engineer judgment is to exhaust config-level mechanisms first and only invest in a plugin when the leverage clearly exceeds the maintenance tax.

---

## ✅ Key Takeaways

- **Navigation first**: Double-`Shift` Search Everywhere, `Ctrl+Shift+A` Find Action, and `Alt+Enter` intentions are the highest-ROI habits at any level.
- **Refactorings are AST-aware and safe** — prefer Rename/Change Signature/Extract over find-and-replace; they update real usages and warn on conflicts.
- **The debugger is more than breakpoints**: conditional breakpoints, non-suspending log breakpoints, exception/field breakpoints, Evaluate Expression, and the Stream Debugger replace most print debugging.
- **Remote debug via JDWP** is powerful but unauthenticated — always tunnel it, never expose it, prefer staging, and disable it in prod.
- **Profile with sampling first** (async-profiler/JFR) and read the flame graph before optimizing; reserve instrumentation for exact counts.
- **Make local == CI**: build through Gradle/Maven, commit run configs, share code-style/inspection profiles and `.editorconfig`.
- **Right-size the IDE JVM**; exclude build/output dirs and trim plugins before adding heap.
- **Treat plugins, debug ports, and committed configs as supply-chain/security surface.**

## ⚠️ Common Pitfalls

- Cranking `-Xmx` higher than the machine can afford, causing more GC pauses and starving the build/app.
- Indexing `build/`, `target/`, or `node_modules/` because they weren't excluded — the top cause of "IntelliJ is slow."
- Committing secrets inside shared `.idea`/`.run` configurations.
- Leaving JDWP (`-agentlib:jdwp`) enabled in a production deployment — a standing RCE.
- Pausing a thread to debug a race condition and watching the bug vanish; use a non-suspending log breakpoint instead.
- Assuming IntelliJ's internal build matches CI when "Build and run using" is set to IntelliJ rather than Gradle/Maven.
- Shipping a single giant reformat commit that destroys `git blame` instead of adding it to `.git-blame-ignore-revs`.
- Relying on Rename to catch reflection/Spring-XML string references it can't resolve — verify with "Search in comments and strings" and Safe Delete.

## 📚 Further Reading

- JetBrains, *IntelliJ IDEA Documentation* — official help, including debugger, profiler, and remote development guides (jetbrains.com/help/idea).
- *IntelliJ IDEA Tips & Tricks* and the in-IDE *Productivity Guide* (Help → Productivity Guide) which tracks which features you under-use.
- JetBrains, *IntelliJ Platform SDK* docs — for building custom inspections, intentions, and plugins.
- async-profiler project README and the *Java Flight Recorder / JDK Mission Control* docs — for JVM profiling fundamentals.
- Oracle, *Java Platform Debugger Architecture (JPDA) / JDWP* specification — for understanding and securing remote debugging.
- Hadi Hariri (ed.), JetBrains blog and YouTube "IntelliJ IDEA" channel — release-by-release feature deep dives current through 2025/2026.
