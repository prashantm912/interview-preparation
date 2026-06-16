# Thymeleaf

Thymeleaf is a server-side Java template engine for web and standalone environments, built around the idea of **natural templating** — templates that are valid, browser-renderable HTML even before any data is bound. It is the default view technology recommended by Spring Boot for server-rendered HTML.

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

### Q1. [Theory] What is Thymeleaf and what does "natural templating" mean?

Thymeleaf is a Java-based server-side template engine that turns templates (usually HTML) plus a context (model data) into rendered output. Its defining feature is **natural templating**: every Thymeleaf instruction lives inside HTML attributes prefixed with `th:` (e.g. `th:text`, `th:href`), so the raw template file is still valid, well-formed HTML. You can open the unprocessed `.html` file directly in a browser or hand it to a designer and it renders as a static mock-up, with the `th:` attributes simply ignored. When the engine processes it, those attributes overwrite the placeholder content with real data.

This contrasts sharply with JSP, where `<% %>` scriptlets or custom `<c:forEach>` tags break the file as standalone HTML. The "why" is collaboration and maintainability: front-end designers and back-end developers can work on the same artifact without a build step, and the prototype-to-production gap shrinks. The trade-off is that Thymeleaf is strictly server-rendered and slightly more verbose than some engines for purely programmatic output.

### Q2. [Theory] Explain the four main expression types: `${}`, `*{}`, `#{}`, and `@{}`.

These are the core of the Standard Dialect's Standard Expression Syntax:

```text
${...}   Variable expressions      -> read from the model/context (OGNL/SpringEL)
                                       e.g. ${user.name}
*{...}   Selection expressions     -> evaluated against the object chosen by th:object
                                       e.g. *{firstName}  (relative to selected object)
#{...}   Message (i18n) expressions-> resolve externalized text from messages.properties
                                       e.g. #{home.welcome}
@{...}   Link (URL) expressions    -> build context-aware URLs, add params, rewrite paths
                                       e.g. @{/users/{id}(id=${user.id})}
```

`${}` is the workhorse for reading model attributes. `*{}` is shorthand used inside a `th:object` scope so you do not repeat the parent path. `#{}` decouples display text from templates for internationalization. `@{}` is critical because it prepends the servlet context path and URL-encodes parameters, which is the only safe way to build links and form actions. In Spring, `${}` and `*{}` are evaluated using **Spring Expression Language (SpEL)** rather than plain OGNL.

### Q3. [Coding] Render a user's name and a list of orders.

**Problem:** Given a `user` object and a `List<Order>` in the model, display the name and a table of order rows, with a fallback when the list is empty.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <!-- th:text escapes HTML; th:utext does NOT (XSS risk) -->
  <h1 th:text="${'Hello, ' + user.name}">Hello, Placeholder</h1>

  <table>
    <thead><tr><th>Item</th><th>Total</th></tr></thead>
    <tbody>
      <!-- th:each iterates; the placeholder row shows in static preview -->
      <tr th:each="order : ${orders}">
        <td th:text="${order.item}">Sample Item</td>
        <td th:text="${#numbers.formatCurrency(order.total)}">$0.00</td>
      </tr>
      <!-- th:if renders this block only when the condition is true -->
      <tr th:if="${#lists.isEmpty(orders)}">
        <td colspan="2">No orders yet.</td>
      </tr>
    </tbody>
  </table>
</body>
</html>
```

**Edge cases:** `th:each` over a `null` collection renders nothing (no NPE). Always prefer `th:text` over `th:utext` for user-supplied data to avoid stored XSS. `#numbers`, `#lists`, `#strings` are built-in utility objects.

### Q4. [Practical] How do you configure Thymeleaf in a Spring Boot project?

Add `spring-boot-starter-thymeleaf` and place templates under `src/main/resources/templates/`. Spring Boot auto-configures a `SpringTemplateEngine`, a `ThymeleafViewResolver`, and sensible defaults: prefix `classpath:/templates/`, suffix `.html`, `HTML` mode, UTF-8. A controller returning the string `"users/list"` resolves to `templates/users/list.html`.

```properties
# application.properties
spring.thymeleaf.cache=false          # disable in dev for hot reload
spring.thymeleaf.prefix=classpath:/templates/
spring.thymeleaf.suffix=.html
spring.thymeleaf.encoding=UTF-8
```

In production you leave caching on (the default `true`). The single most common dev-experience mistake is leaving the cache enabled locally and wondering why edits do not appear; pairing `spring.thymeleaf.cache=false` with `spring-boot-devtools` gives instant reloads.

---

## 🟡 Intermediate (3–7 yrs)

### Q5. [Practical] Walk through form binding with `th:object` and `th:field`, including how errors surface.

`th:object` binds a form to a backing command object (the model attribute), and `th:field` wires each input to a property — it simultaneously generates the `id`, `name`, and `value` attributes and pre-populates the field on re-render. This is what makes round-tripping a form after a validation failure painless.

```html
<form th:action="@{/users}" th:object="${userForm}" method="post">
  <input type="text" th:field="*{email}">
  <!-- th:errors prints the messages bound to this field -->
  <span class="error" th:if="${#fields.hasErrors('email')}"
        th:errors="*{email}">Email error</span>

  <input type="text" th:field="*{age}" th:errorclass="invalid-field">
  <button type="submit">Save</button>
</form>
```

```java
@PostMapping("/users")
public String save(@Valid @ModelAttribute("userForm") UserForm form,
                   BindingResult result) {
    if (result.hasErrors()) {
        return "users/form";   // re-render; th:field repopulates entered values
    }
    // persist...
    return "redirect:/users";
}
```

**Flow:**

```text
GET /users/new --> controller adds empty UserForm to model --> render form
POST /users    --> Spring binds + runs @Valid (JSR-380) --> BindingResult
   has errors? --> return view; #fields.hasErrors / th:errors show messages
   no errors?  --> persist --> redirect (PRG pattern, avoids double submit)
```

The key production detail: the `@ModelAttribute` name (`userForm`), the `th:object` name, and `BindingResult` ordering (it must immediately follow the validated argument) all have to line up, or binding silently fails.

### Q6. [Theory] What are fragments, and how do `th:fragment`, `th:insert`, `th:replace`, and `th:include` differ?

Fragments are reusable chunks of markup defined with `th:fragment` and pulled into other templates — the foundation of DRY layouts (headers, footers, nav, modals).

```html
<!-- fragments/header.html -->
<header th:fragment="siteHeader(title)">
  <h1 th:text="${title}">Title</h1>
</header>
```

```html
<!-- consumer -->
<div th:insert="~{fragments/header :: siteHeader('Dashboard')}"></div>
<div th:replace="~{fragments/header :: siteHeader('Home')}"></div>
```

- `th:insert` inserts the fragment **inside** the host tag (host tag is kept).
- `th:replace` **replaces** the host tag entirely with the fragment.
- `th:include` (deprecated since Thymeleaf 3) inserted only the fragment's *contents*, dropping its wrapper tag.

`~{...}` is the explicit **fragment expression** syntax introduced in Thymeleaf 3. Prefer `th:replace` for layout composition because it avoids leftover wrapper `<div>`s. Fragments can take parameters, making them effectively reusable components.

### Q7. [Practical] How do you build a shared page layout? Compare the Layout Dialect vs. fragment-based decorators.

Two mainstream approaches:

**1. Standard fragment + `th:replace` (no extra dependency):** define a `layout.html` with named slots, and each page replaces the body fragment. Simple, but the page must reference the layout, which inverts the natural "content extends layout" mental model.

**2. Thymeleaf Layout Dialect (`nz.net.ultraq.thymeleaf:thymeleaf-layout-dialect`):** the page *decorates* a layout, much like Apache Tiles or SiteMesh.

```html
<!-- layouts/main.html -->
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
<head><title layout:title-pattern="$CONTENT_TITLE - MyApp">Default</title></head>
<body>
  <nav>...shared nav...</nav>
  <section layout:fragment="content"><!-- page content slot --></section>
</body>
</html>
```

```html
<!-- pages/dashboard.html -->
<html layout:decorate="~{layouts/main}">
<head><title>Dashboard</title></head>
<body>
  <section layout:fragment="content"><p>Real content</p></section>
</body>
</html>
```

**Trade-off:** the Layout Dialect gives the cleanest inheritance model (each page is a complete, previewable HTML file that declares which layout it extends, and `<head>` sections merge intelligently), at the cost of an extra dependency. For anything beyond a couple of pages, the Layout Dialect is the production-standard choice.

### Q8. [Coding] Conditionally render a status badge with multiple branches.

**Problem:** Show a colored badge based on an `order.status` enum (`PAID`, `PENDING`, `CANCELLED`), defaulting gracefully.

```html
<!-- Approach 1: th:if / th:unless chain (verbose, but explicit) -->
<span th:if="${order.status.name() == 'PAID'}" class="badge green">Paid</span>
<span th:if="${order.status.name() == 'PENDING'}" class="badge amber">Pending</span>

<!-- Approach 2: th:switch / th:case (cleaner for enums) -->
<div th:switch="${order.status.name()}">
  <span th:case="'PAID'"      class="badge green">Paid</span>
  <span th:case="'PENDING'"   class="badge amber">Pending</span>
  <span th:case="'CANCELLED'" class="badge red">Cancelled</span>
  <span th:case="*"           class="badge grey">Unknown</span>  <!-- default -->
</div>

<!-- Approach 3: inline ternary for a single attribute (most concise) -->
<span th:classappend="${order.status.name() == 'PAID'} ? 'green' : 'grey'"
      class="badge" th:text="${order.status}">STATUS</span>
```

**Complexity:** All three are O(1) per element at render time (constant string comparisons). **Approach 2** is the most maintainable for a fixed enum set because `th:case="*"` guarantees a default branch. **Edge case:** if `order` could be `null`, guard the whole block with `th:if="${order != null}"` or use the safe-navigation operator `${order?.status}` to avoid a `SpelEvaluationException`.

### Q9. [Theory] How does Thymeleaf integrate with Spring Security, and what does it offer over manual checks?

The `thymeleaf-extras-springsecurity6` module (use the `6` artifact for Spring Boot 3 / Spring Security 6; `springsecurity5` for Boot 2) adds the `sec` dialect. It lets you conditionally render based on authentication and authorization directly in the view:

```html
<html xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<div sec:authorize="isAuthenticated()">
  Welcome, <span sec:authentication="name">user</span>!
</div>
<a sec:authorize="hasRole('ADMIN')" th:href="@{/admin}">Admin Console</a>
<span sec:authorize="hasAuthority('order:write')">Edit</span>
```

`sec:authorize` accepts SpEL security expressions; `sec:authentication` exposes principal details. The advantage over hand-rolled `th:if="${#authorization.expression(...)}"` is readability and correctness — the dialect plugs into the same `SecurityExpressionHandler` the server uses, so view-layer rules match the back-end. **Security caveat:** view-side hiding is purely cosmetic. You must still enforce authorization on the server (method security / request matchers); never treat a hidden button as a security boundary. Also ensure CSRF tokens are present — `th:action` on a `<form>` auto-injects the hidden `_csrf` field when Spring Security is on the classpath.

### Q10. [Practical] How do `th:inline`, JavaScript inlining, and passing model data to client-side scripts work safely?

Thymeleaf can inline expressions into text and JavaScript blocks. For JS, `th:inline="javascript"` is essential because it **JavaScript-escapes** values, preventing script injection:

```html
<script th:inline="javascript">
  /*<![CDATA[*/
  const userId = /*[[${user.id}]]*/ 0;          // number, safely inlined
  const userName = /*[[${user.name}]]*/ "guest"; // string, auto-escaped + quoted
  /*]]>*/
</script>
```

The `/*[[...]]*/ fallback` pattern keeps the script valid in static preview (the `0`/`"guest"` are the prototype values, replaced at render). The big win is automatic escaping: a username containing `</script>` or quotes will not break out of the JS context. For larger payloads, serialize a DTO to JSON. **Production guidance:** keep inlining minimal — large data dumps into the page bloat HTML and couple view to controller; prefer a dedicated REST endpoint that the script fetches. Never inline secrets, and remember inlined data is visible in page source.

---

## 🟠 Advanced (8–12 yrs)

### Q11. [Theory] Compare server-side rendering with Thymeleaf vs. a SPA (React/Angular/Vue). When do you pick which?

```text
            Thymeleaf (SSR/MPA)           SPA (React/Vue/Angular)
First paint  Fast; HTML arrives complete  Slower; download+hydrate JS bundle first
SEO          Excellent out of the box     Needs SSR/prerender to match
Interactivity Page reloads / partial      Rich, app-like, client routing
              fragment swaps (htmx)
Team skill   Java/back-end heavy           Front-end / JS heavy
State        Lives on server (session)     Lives in browser
Payload      HTML per request              JSON API + cached JS bundle
Complexity   Lower for content/CRUD apps   Higher; build tooling, API contracts
```

Pick **Thymeleaf** for content-driven sites, internal tools, admin panels, CRUD dashboards, and anything where SEO, fast first paint, and a small front-end team matter — you avoid maintaining a separate API and a JS build pipeline. Pick a **SPA** for highly interactive, app-like experiences (real-time editors, dashboards with rich client state). A modern middle ground in 2026 is **Thymeleaf + htmx (or Hotwire-style fragments)**: the server returns small HTML fragments that swap into the DOM, delivering SPA-like interactivity while keeping rendering and state server-side. This is increasingly popular precisely because it sidesteps SPA complexity for the 80% of apps that do not need it.

### Q12. [Theory] How does Thymeleaf compare with JSP and FreeMarker? Why did Spring Boot default to Thymeleaf?

```text
              Thymeleaf            JSP                      FreeMarker
Natural       Yes (valid HTML)     No (scriptlets/tags      No (#-directives
templating                          break standalone HTML)    break HTML)
Engine type   Attribute-based      Servlet compiled to .java Text macro engine
Spring Boot   Recommended default  Discouraged (no support   Supported starter
                                    in embedded jars)
Browseable    Yes                  No                       No
prototype
Performance   Good (cached)        Very fast (compiled)     Very fast
Error report  Template line refs   Generated servlet lines  Decent
```

**Why Thymeleaf became the Spring Boot default:** JSP is fundamentally awkward with executable JARs and embedded servlet containers (the standard Boot packaging) — JSPs require a real servlet container's JSP compiler and cannot live in a fat-jar `/WEB-INF` cleanly. JSP also could not be previewed statically. FreeMarker is fast and capable but its `<#...>` directives also break natural HTML preview. Thymeleaf hit the sweet spot: valid-HTML natural templates, first-class Spring integration (SpEL, security, validation), and clean fat-jar packaging. JSP is effectively legacy for new Spring Boot apps; FreeMarker remains a reasonable choice especially for non-HTML output (emails, text) where its terseness shines.

### Q13. [Practical] A page renders slowly under load. How do you diagnose and tune Thymeleaf performance?

Approach it methodically:

1. **Confirm caching is on.** In prod, `spring.thymeleaf.cache=true` is the single biggest lever — it caches the *parsed template model* so each request only re-evaluates expressions, not re-parses HTML. A misconfigured cache (left `false`) can cause order-of-magnitude regressions.
2. **Profile expression cost.** Heavy logic in `${}` (method calls hitting the DB lazily, large `th:each` loops calling services) runs per element. Move computation into the controller/service; the template should only display prepared data. Beware lazy JPA collections triggering N+1 queries *during rendering* — fetch eagerly or map to DTOs before the view.
3. **Reduce DOM work.** Deeply nested `th:each` with per-row fragment inclusion multiplies work. Flatten where possible; precompute view models.
4. **Use the right template mode.** `HTML` mode validates less than legacy `XHTML`; ensure you are not on a stricter mode than needed.
5. **Stream / paginate.** For huge tables, paginate server-side rather than rendering 50k rows.
6. **Add a `ICacheManager` size limit** and monitor cache hit ratio; default cache is unbounded-ish per resolver.

**Real-world case:** a common production incident is a report page that worked in dev (cache off, small data) but melted in prod because rendering each of thousands of rows triggered a lazy `order.getCustomer().getName()` call — an N+1 storm hidden inside the template. The fix is to project to a flat DTO with a single join query in the service layer, leaving the template to only display strings. This pattern — "do data work in the service, display in the view" — resolves the majority of Thymeleaf performance complaints.

### Q14. [Coding] Build a reusable, parameterized pagination fragment.

**Problem:** Create a fragment that renders Prev/Next and numbered page links for a Spring `Page<T>`, reusable across list pages.

```html
<!-- fragments/pagination.html -->
<nav th:fragment="pager(page, baseUrl)" th:if="${page.totalPages > 1}"
     xmlns:th="http://www.thymeleaf.org">
  <ul class="pagination">
    <!-- Prev: disabled on first page -->
    <li th:classappend="${page.first} ? 'disabled'">
      <a th:href="@{${baseUrl}(p=${page.number - 1})}"
         th:unless="${page.first}">Prev</a>
      <span th:if="${page.first}">Prev</span>
    </li>

    <!-- Numbered links 0..totalPages-1 -->
    <li th:each="i : ${#numbers.sequence(0, page.totalPages - 1)}"
        th:classappend="${i == page.number} ? 'active'">
      <a th:href="@{${baseUrl}(p=${i})}" th:text="${i + 1}">1</a>
    </li>

    <!-- Next: disabled on last page -->
    <li th:classappend="${page.last} ? 'disabled'">
      <a th:href="@{${baseUrl}(p=${page.number + 1})}"
         th:unless="${page.last}">Next</a>
      <span th:if="${page.last}">Next</span>
    </li>
  </ul>
</nav>
```

```html
<!-- usage on any list page -->
<div th:replace="~{fragments/pagination :: pager(${userPage}, '/users')}"></div>
```

**Complexity:** rendering is O(n) in the number of page links (`totalPages`). **Edge cases:** the wrapping `th:if="${page.totalPages > 1}"` hides the pager for single-page results; for very large `totalPages` you should pass a windowed range (e.g. current ±3) instead of `0..totalPages-1` to keep it O(window) and avoid rendering hundreds of links. Passing `baseUrl` as a parameter is what makes the fragment reusable across `/users`, `/orders`, etc.

### Q15. [Practical] How do you return only an HTML fragment from a controller (e.g. for an htmx/AJAX partial update)?

Thymeleaf 3.x supports rendering a single fragment from a controller, which is the backbone of progressive-enhancement patterns:

```java
@GetMapping("/users/search")
public String search(@RequestParam String q, Model model) {
    model.addAttribute("results", userService.search(q));
    // return view + fragment selector -> only this fragment is rendered
    return "users/list :: resultsTable";
}
```

The `view :: fragment` syntax tells the `ThymeleafViewResolver` to render just the `th:fragment="resultsTable"` block, not the whole page. Combined with an htmx attribute on the client (`hx-get="/users/search" hx-target="#results"`), the browser swaps in just the updated table without a full reload. **Trade-offs:** this keeps all rendering logic server-side (one template language, no duplicated client templates) and is far lighter than a SPA, but it does increase the number of round-trips and you must design endpoints to return both full pages and fragments cleanly. A common refinement is the `HX-Request` header check to decide whether to return the full page or just the fragment from the same handler.

---

## 🔴 Expert (15+ yrs)

### Q16. [Theory] Explain Thymeleaf's processing architecture: dialects, processors, and the template-resolution pipeline.

Thymeleaf is built on a **chained, event-based processing model**. The pipeline:

```text
TemplateResolver(s)  -> locate the template (classpath, file, URL, string)
        |
ResourceParsing      -> parse into an immutable, cacheable in-memory model
        |
TemplateEngine       -> apply Dialects' Processors to the model events
        |   Dialect = a named set of (Processors + ExpressionObjects + ...)
        |   Processor = handles a specific attribute/tag (e.g. StandardTextTagProcessor
        |               handles th:text), each with a numeric precedence
        |
ITemplateWriter      -> serialize processed model to the output stream
```

A **Dialect** contributes processors (and an optional namespace prefix like `th`, `sec`, `layout`). When the engine walks the parsed events, it matches attributes to **processors** and applies them in **precedence order** — this is why, on a single element, `th:each` (low precedence number, runs first) executes before `th:text`, and `th:if` before `th:text`. Understanding precedence is what lets you reason about combined attributes on one tag. Templates are parsed once and the parsed model cached; only processor execution happens per request. You can register **custom dialects/processors** to add domain-specific attributes (e.g. `th:money` for currency formatting) — a powerful extension point for large codebases that want first-class, reusable rendering primitives.

### Q17. [Theory] What are the precedence rules when multiple `th:` attributes appear on one element, and why does it matter?

Each processor has a fixed precedence; lower numbers run first. The canonical order on a single element:

```text
1. Fragment inclusion   th:insert, th:replace
2. Fragment iteration    th:each
3. Conditional eval      th:if, th:unless, th:switch, th:case
4. Local var definition   th:object, th:with
5. General attribute mod  th:attr, th:attrprepend, th:attrappend
6. Specific attributes    th:value, th:href, th:src ...
7. Text/value             th:text, th:utext
8. Fragment specification th:fragment
9. Fragment removal       th:remove
```

This ordering is not arbitrary — it makes the common cases "just work." `th:each` (2) runs before `th:if` (3), so you can write `th:each="x : ${xs}" th:if="${x.active}"` and the condition is evaluated *per iteration*. If precedence were reversed, the `th:if` would evaluate once before iteration and break. The practical consequence: when two attributes on one tag interact in a surprising way, the fix is usually to split them across nested elements (e.g. use `<th:block th:each>` wrapping an inner element with `th:if`) so execution order is explicit rather than relying on memorizing precedence.

### Q18. [Practical] Design the rendering layer for a high-traffic, internationalized e-commerce site using Thymeleaf. What architectural decisions matter?

Key decisions and the reasoning:

1. **Template caching + CDN for static assets.** Keep `spring.thymeleaf.cache=true`; the HTML itself is dynamic but versioned static assets (CSS/JS/images) go behind a CDN with long cache headers and content-hashed filenames built via `@{...}` + a resource versioning strategy.
2. **i18n strategy.** Externalize all copy into `messages_xx.properties` resolved via `#{}`. Use a `LocaleResolver` (cookie or `Accept-Language`) and consider per-locale message bundles loaded from a database for marketing teams to edit without redeploys. Cache resolved bundles.
3. **View-model projection.** Controllers assemble flat, render-ready DTOs — no lazy entities reaching the template — to eliminate in-render N+1 queries and keep rendering deterministic.
4. **Fragment library as a component system.** Build a curated set of parameterized fragments (product card, price block, breadcrumb) treated like a design system; this enforces consistency and centralizes accessibility/markup fixes.
5. **Hybrid interactivity.** Use server-rendered pages for catalog/SEO-critical pages and htmx fragment swaps for cart/filtering, reserving a SPA only for genuinely app-like areas (checkout wizard) if needed.
6. **Security.** Default escaping everywhere (`th:text`, never `th:utext` on user data), CSRF via Spring Security auto-injected hidden fields, and `sec:authorize` mirrored by server-side method security.
7. **Caching layers.** For expensive, mostly-static fragments (footer, mega-menu), consider an application-level fragment/HTML cache (e.g. Caffeine) keyed by locale, or edge-side includes. Measure cache hit ratios.

The overarching principle: **the template is a pure projection of a prepared view model**; all data, security, and computation decisions are made before rendering. That separation is what keeps a high-traffic Thymeleaf layer fast, secure, and maintainable.

### Q19. [Behavioral] Your team is split on migrating a stable Thymeleaf MPA to a React SPA. How do you lead that decision?

I would resist treating it as a binary technology preference and instead frame it around concrete drivers. First, I gather data: what user-facing problems are we actually solving — interactivity gaps, perceived slowness, designer velocity, hiring? I would quantify the cost of a SPA migration honestly (new API surface, two codebases, build pipeline, SEO/SSR work, retraining) against the cost of incremental improvement (htmx fragments, Alpine.js sprinkles, better caching). I have seen teams burn quarters rewriting a perfectly serviceable server-rendered admin app into a SPA, only to recreate features they already had, with worse SEO and more moving parts.

My approach is to run a small spike on the two or three most painful screens using a progressive-enhancement path first, measure the result against the SPA hypothesis, and let evidence settle the debate. If genuinely app-like requirements emerge (offline, complex client state, real-time collaboration), a SPA — or a hybrid where only those areas are React islands embedded in Thymeleaf pages — becomes justified. The leadership skill here is depersonalizing the choice, aligning on success metrics, and being willing to say "the boring server-rendered solution is still the right one" when that is what the data shows. Architecture decisions should follow requirements, not résumé-driven development.

### Q20. [Theory] What are the most important version-specific differences a Thymeleaf expert should track (Thymeleaf 2 vs 3, Spring Boot 2 vs 3)?

Several migration-critical differences:

- **Thymeleaf 2 → 3:** Version 3 introduced **decoupled template logic**, full **HTML5 support without forcing XML well-formedness** (no more strict XHTML mode required), the **`~{...}` fragment expression** syntax, and a major performance/memory overhaul of the parsing model. `th:include` was deprecated in favor of `th:insert`/`th:replace`. Template modes were renamed (`HTML5` → `HTML`). Most modern apps are on 3.1.x.
- **Thymeleaf 3.0 → 3.1:** 3.1 **removed/restricted access to some objects** for security and servlet-decoupling reasons — notably the `#request`, `#response`, `#session`, and `#servletContext` expression objects were deprecated/removed from direct use, pushing you to expose what you need via the model instead. This is a frequent upgrade gotcha.
- **Spring Boot 2 → 3:** Boot 3 moved to **Spring Framework 6, Java 17 baseline, and Jakarta EE (`jakarta.*` namespace replacing `javax.*`)**. For Thymeleaf this means swapping the security extras artifact from `thymeleaf-extras-springsecurity5` to `springsecurity6`, and ensuring JSR-380 validation uses `jakarta.validation`. Servlet-coupled patterns get tighter.

Tracking these matters because silent behavior changes (removed expression objects, namespace swaps) cause runtime template errors that compile fine, and the fixes are mechanical once you know the cause.

---

## ✅ Key Takeaways

- **Natural templating** is Thymeleaf's identity: templates stay valid, previewable HTML, enabling designer/developer collaboration without a build step.
- Master the four expression types — `${}` (variables/SpEL), `*{}` (selection within `th:object`), `#{}` (i18n messages), `@{}` (context-aware, encoded URLs).
- `th:object` + `th:field` + `BindingResult` + `th:errors` give clean, round-tripping form binding and validation; follow the Post-Redirect-Get pattern.
- Compose UIs with **fragments** (`th:fragment`, `th:replace`, `~{...}`) and the **Layout Dialect**; treat fragments as a component/design system.
- Integrate **Spring Security** via the `sec` dialect for view-side rendering, but always enforce authorization on the server — view hiding is cosmetic.
- Keep `spring.thymeleaf.cache=true` in production; do data/computation in services and pass flat view models to templates.
- Thymeleaf is the Spring Boot SSR default; JSP is legacy for fat-jar apps, FreeMarker is a fine alternative (esp. for non-HTML output), and **Thymeleaf + htmx** is the modern lightweight alternative to a full SPA.

## ⚠️ Common Pitfalls

- **Leaving `spring.thymeleaf.cache=false` in production** — causes per-request re-parsing and severe slowdowns; conversely, leaving it `true` in dev blocks hot reload.
- **Using `th:utext` on user-supplied data** — bypasses HTML escaping and opens stored/reflected **XSS**. Default to `th:text`.
- **In-render N+1 queries** — lazy JPA collections accessed inside `th:each` trigger query storms during rendering; project to DTOs first.
- **Mismatched `@ModelAttribute` / `th:object` names or `BindingResult` not immediately following the validated arg** — binding and validation fail silently.
- **Relying on hidden buttons (`sec:authorize`) as security** — always back it with server-side authorization.
- **Forgetting `@{...}` for links/forms** — hardcoded URLs break under a non-root context path and skip CSRF/URL encoding.
- **Upgrade gotchas** — Thymeleaf 3.1 removed `#request`/`#session` expression objects; Spring Boot 3 requires the `springsecurity6` extras artifact and `jakarta.*` validation.
- **Overusing JavaScript inlining** — dumping large payloads into pages couples view to controller and bloats HTML; fetch from an endpoint instead, and only ever use `th:inline="javascript"` (never raw `th:text`) for JS to get proper escaping.

## 📚 Further Reading

- *Thymeleaf official documentation* — "Tutorial: Using Thymeleaf" and "Tutorial: Thymeleaf + Spring" (thymeleaf.org/documentation.html) — the authoritative, version-pinned reference.
- *Spring Boot Reference Documentation* — "Web → Template Engines" section, covering auto-configuration and `spring.thymeleaf.*` properties.
- *Thymeleaf Layout Dialect documentation* (ultraq.github.io/thymeleaf-layout-dialect) — for production layout/decorator patterns.
- *thymeleaf-extras-springsecurity6* GitHub README — the `sec` dialect reference for Spring Security 6 / Boot 3.
- *htmx documentation* (htmx.org) — the modern progressive-enhancement companion to server-side Thymeleaf rendering.
- Baeldung's Thymeleaf series — practical, example-driven articles on forms, fragments, security, and Spring integration.
