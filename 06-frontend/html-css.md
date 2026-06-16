# HTML & CSS

A staff-level interview guide to HTML and CSS, covering semantics, accessibility, the box model, layout systems (Flexbox/Grid), the cascade, modern CSS features, and browser rendering performance. Knowledge current through 2026.

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

### Q1. [Theory] What is semantic HTML and why does it matter?

Semantic HTML means choosing elements based on the *meaning* of the content rather than its appearance — `<header>`, `<nav>`, `<main>`, `<article>`, `<section>`, `<aside>`, `<footer>`, `<button>`, `<time>` — instead of wrapping everything in `<div>` and `<span>`. The "why" is threefold. First, **accessibility**: screen readers build a document tree from semantics, so a `<nav>` becomes a navigation landmark a blind user can jump to, while a `<div onclick>` is invisible to assistive tech. Second, **SEO and machine parsing**: crawlers weight headings and article structure. Third, **maintainability**: `<button>` ships keyboard focus, Enter/Space activation, and ARIA role for free, whereas a clickable `<div>` requires you to re-implement all of it (and you usually get it wrong). The trade-off is essentially zero — semantic elements render identically once styled — so the default should always be the most meaningful element available.

### Q2. [Theory] Explain the CSS box model and the difference between `content-box` and `border-box`.

Every element is a rectangular box composed of four layers from inside out: **content**, **padding**, **border**, and **margin**. The `box-sizing` property decides what `width`/`height` measure. With the default `content-box`, `width` sets only the content area, so padding and border are *added on top* — a `width: 200px; padding: 20px; border: 5px` element is actually 250px wide. With `border-box`, `width` includes padding and border, so that same element stays 200px and the content area shrinks. `border-box` is overwhelmingly preferred because layout math becomes predictable, which is why most resets start with `*, *::before, *::after { box-sizing: border-box; }`. Margins are never part of either box dimension and collapse vertically between adjacent block elements.

### Q3. [Practical] How do you make an image responsive without distorting it?

The baseline is `max-width: 100%; height: auto;` so the image never overflows its container and scales proportionally. For art direction and bandwidth savings you use `srcset` + `sizes` to let the browser pick a resolution, or `<picture>` with multiple `<source>` elements to swap the actual image (e.g. a cropped portrait on mobile, a wide hero on desktop, or modern formats like AVIF/WebP with a JPEG fallback). In production I always pair this with `loading="lazy"` for below-the-fold images and explicit `width`/`height` attributes (or `aspect-ratio` in CSS) so the browser reserves space and avoids layout shift (CLS).

```html
<picture>
  <source type="image/avif" srcset="hero.avif" />
  <source type="image/webp" srcset="hero.webp" />
  <img src="hero.jpg" alt="Team at the summit"
       width="1200" height="600" loading="lazy"
       style="max-width:100%; height:auto;" />
</picture>
```

### Q4. [Theory] What is specificity and how is it calculated?

Specificity is the algorithm browsers use to decide which conflicting rule wins. It is a tuple `(a, b, c)` compared left to right: **a** = number of ID selectors, **b** = number of classes, attribute selectors, and pseudo-classes, **c** = number of element types and pseudo-elements. Inline `style` attributes outrank all selectors, and `!important` overrides everything except another `!important` of equal or higher origin. So `#nav .item` (1,1,0) beats `.menu .item a` (0,2,1) because the ID dominates the first position. The practical takeaway: keep specificity flat and low so styles stay easy to override. Reaching for IDs or `!important` to win a fight is a code smell — it just escalates the next fight. The newer `@layer` and `:where()` (which contributes zero specificity) are the modern tools for controlling this.

### Q5. [Coding] Center a `<div>` both horizontally and vertically.

**Problem:** Place a fixed-size card dead center in the viewport. Show multiple approaches.

```html
<style>
  /* Approach 1: Flexbox (most common, 1 container) */
  .flex-parent {
    display: flex;
    justify-content: center; /* main axis: horizontal */
    align-items: center;     /* cross axis: vertical */
    min-height: 100vh;
  }

  /* Approach 2: Grid (shortest, one line) */
  .grid-parent {
    display: grid;
    place-items: center;
    min-height: 100vh;
  }

  /* Approach 3: Absolute + transform (works without parent sizing) */
  .abs-parent { position: relative; min-height: 100vh; }
  .abs-child {
    position: absolute;
    top: 50%; left: 50%;
    transform: translate(-50%, -50%);
  }
</style>

<div class="grid-parent">
  <div class="card">Centered</div>
</div>
```

Flexbox and Grid are the modern defaults. The `translate(-50%, -50%)` trick is useful when you cannot control the parent's display mode but it requires the child to be `position: absolute`. **Time/Space:** all are O(1) layout passes; Grid's `place-items` is the most concise. **Edge cases:** if the child can be taller than the viewport, prefer Flexbox with `overflow: auto` so content stays scrollable instead of clipping.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Compare Flexbox and CSS Grid — when do you reach for each?

Flexbox is **one-dimensional**: it lays out items along a single axis (row *or* column) and excels at distributing space and aligning items within that line — toolbars, button groups, navbars, and "push this to the right" patterns. Grid is **two-dimensional**: it controls rows and columns simultaneously, making it the right tool for page-level layouts, card galleries, and any design where items must align across both axes. A useful heuristic: if you're describing the layout in terms of "a row of things" use Flex; if you're describing "a grid of cells" use Grid. They compose — a Grid cell often contains a Flex container. Grid also enables overlapping content (multiple items in the same cell), `grid-template-areas` for readable named layouts, and intrinsic sizing with `minmax()` and `fr` units that Flexbox can't express as cleanly.

```
Flexbox (1D)                 Grid (2D)
┌───┬───┬───┬─────►          ┌─────┬─────┬─────┐
│ A │ B │ C │  main axis     │  A  │  B  │  C  │
└───┴───┴───┘                ├─────┼─────┼─────┤
items flow on ONE axis       │  D  │  E  │  F  │
                             └─────┴─────┴─────┘
                             rows AND columns aligned
```

### Q7. [Coding] Build a responsive card grid that auto-fits columns with no media queries.

**Problem:** Cards should be at least 250px wide, fill available space, and reflow the column count automatically as the viewport changes — without writing breakpoints.

```html
<style>
  .auto-grid {
    display: grid;
    /* auto-fit collapses empty tracks; minmax sets the floor/ceiling */
    grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
    gap: 1rem;
  }
  .auto-grid > .card {
    padding: 1rem;
    border: 1px solid #ccc;
    border-radius: 8px;
  }
</style>

<div class="auto-grid">
  <div class="card">1</div><div class="card">2</div>
  <div class="card">3</div><div class="card">4</div>
</div>
```

The magic is `repeat(auto-fit, minmax(250px, 1fr))`: the browser fits as many 250px-minimum tracks as the row allows, then stretches each to share leftover space (`1fr`). **`auto-fit` vs `auto-fill`** is the key edge case — `auto-fit` collapses unused tracks so a single card stretches full width, while `auto-fill` keeps phantom empty tracks (the card stays 250px and leaves a gap). **Time/Space:** O(n) over the items for layout, zero JS, fully responsive. This single line replaces what used to need three or four media-query breakpoints.

### Q8. [Theory] What's the difference between `position: relative`, `absolute`, `fixed`, and `sticky`?

`static` is the default (in normal flow). `relative` keeps the element in flow but lets you offset it with `top/left` *relative to its own original position* — its main job is establishing a **positioning context** for absolute children. `absolute` removes the element from flow and positions it relative to the nearest *positioned* ancestor (one with non-`static` position), or the initial containing block if none exists. `fixed` removes it from flow and pins it to the viewport, so it ignores scrolling (great for sticky headers/modals, but watch out: a `transform`, `filter`, or `will-change` on an ancestor turns that ancestor into the containing block and breaks `fixed`). `sticky` is a hybrid — it behaves like `relative` until a scroll threshold is crossed, then "sticks" like `fixed` within its scroll container. Sticky is the modern, JS-free way to do floating section headers, but it silently fails if any ancestor has `overflow: hidden/auto/scroll`.

### Q9. [Practical] A designer hands you a layout. Walk me through your responsive strategy.

I default to **mobile-first**: write the base styles for the smallest screen, then use `min-width` media queries to *add* complexity as space grows. This keeps the cascade additive (you never have to unset desktop styles for mobile) and ships less CSS to the most constrained devices. I lean on **fluid primitives first** — `clamp()` for fluid typography (`font-size: clamp(1rem, 2vw + 0.5rem, 1.5rem)`), `fr`/`auto-fit` grids, and percentages — so the layout flexes *between* breakpoints rather than snapping. Breakpoints go where the *content* breaks, not at device widths. As of 2025–2026 I increasingly replace component-level media queries with **container queries** (`@container`) so a card adapts to *its parent's* width regardless of viewport — essential in design systems where the same component lands in a sidebar or a full-width hero. Trade-off: container queries require a `container-type` on the wrapper and create a containment context, which can interfere with intrinsic sizing, so I scope them deliberately.

### Q10. [Theory] Explain the cascade, inheritance, and how `@layer` changes the picture.

When multiple rules target an element, the cascade resolves them in this order: **(1) origin and importance** (user-agent < user < author, with `!important` flipping the order), **(2) cascade layers** (`@layer`), **(3) specificity**, **(4) source order** (last one wins on ties). **Inheritance** is separate: some properties (color, font, line-height) pass to children automatically; layout properties (margin, padding, border) do not — you can force either with the `inherit`, `initial`, `unset`, and `revert` keywords. `@layer` is the big modern addition: it lets you define explicit priority bands (e.g. `@layer reset, base, components, utilities;`) so a low-specificity utility in a later layer beats a high-specificity component selector in an earlier layer. This decouples *priority* from *specificity*, which historically forced teams into `!important` wars. It's transformative for integrating third-party CSS — wrap a vendor stylesheet in a low-priority layer and your own styles always win without touching specificity.

### Q11. [Theory] What are CSS custom properties (variables) and how do they differ from Sass variables?

CSS custom properties (`--brand: #0a7;` used via `var(--brand)`) are **runtime** values that live in the cascade: they inherit, can be redefined per-selector or per-media-query, and can be read/written from JavaScript (`element.style.setProperty`). Sass/Less variables are **compile-time** — they're substituted before any CSS ships and have no runtime presence. The practical consequences are big: CSS variables power runtime theming (dark mode by flipping variables on `:root` or `[data-theme]`), respond to media/container queries, and cascade into components you don't control. They're the backbone of modern design-token systems. Edge cases to know: custom properties are *not* type-checked (a bad value is invalid-at-computed-value-time and falls back to inherited/initial), and `var()` accepts a fallback: `var(--gap, 1rem)`. Use `@property` (Houdini) to register a variable with a type and default so it can animate and be validated.

### Q12. [Coding] Implement a "Holy Grail" layout (header, footer, fixed sidebars, fluid center) with CSS Grid.

**Problem:** Full-height page: header on top, footer on bottom, a left nav and right aside of fixed width, and a content area that fills remaining space — center column appears first in the DOM for accessibility.

```html
<style>
  .holy-grail {
    display: grid;
    grid-template:
      "header  header  header" auto
      "nav     main    aside"  1fr
      "footer  footer  footer" auto
      / 200px   1fr     200px;
    min-height: 100vh;
    gap: 0.5rem;
  }
  .hg-header { grid-area: header; }
  .hg-nav    { grid-area: nav; }
  .hg-main   { grid-area: main; }
  .hg-aside  { grid-area: aside; }
  .hg-footer { grid-area: footer; }

  /* Collapse to a single column on small screens */
  @media (max-width: 640px) {
    .holy-grail {
      grid-template:
        "header" auto
        "main"   1fr
        "nav"    auto
        "aside"  auto
        "footer" auto
        / 1fr;
    }
  }
</style>

<div class="holy-grail">
  <header class="hg-header">Header</header>
  <main   class="hg-main">Content first in DOM</main>
  <nav    class="hg-nav">Nav</nav>
  <aside  class="hg-aside">Aside</aside>
  <footer class="hg-footer">Footer</footer>
</div>
```

`grid-template-areas` makes the layout legible — you can literally see the structure in the CSS. The center column is placed first in source order (good for screen readers and keyboard order) but rendered in the middle via grid placement. **Time/Space:** O(1) layout, no JS, no floats, no clearfix hacks. **Edge case:** named areas must form a complete rectangle or the declaration is invalid; the `1fr` on the middle row pushes the footer to the bottom even on short pages.

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] Walk through the critical rendering path and the difference between reflow and repaint.

The critical rendering path is the sequence the browser runs to turn bytes into pixels:

```
HTML ──parse──► DOM ─┐
                     ├─► Render Tree ─► Layout ─► Paint ─► Composite
CSS  ──parse──► CSSOM┘   (only visible   (geometry  (pixels  (GPU layers
                          nodes)          x,y,w,h)   /colors)  combined)
```

CSS is **render-blocking** (the browser won't paint until the CSSOM is built) and synchronous `<script>` is **parser-blocking**. **Reflow (layout)** recalculates geometry — positions and sizes — and is expensive because it can cascade to ancestors, descendants, and siblings; it's triggered by changing `width`, `height`, `top`, `font-size`, adding/removing DOM nodes, or *reading* layout properties like `offsetHeight` mid-mutation (forced synchronous layout / "layout thrashing"). **Repaint** redraws pixels without changing geometry — changing `color`, `background`, `visibility`. **Composite** is cheapest: `transform` and `opacity` can be handled entirely on the GPU's compositor thread, skipping layout and paint. The performance rule that falls out of this: animate `transform`/`opacity`, batch DOM reads then writes (or use `requestAnimationFrame`), and promote animated layers with `will-change` sparingly (each layer costs memory).

### Q14. [Practical] Your page has a poor Cumulative Layout Shift (CLS) score. How do you diagnose and fix it?

CLS measures unexpected movement of visible elements. I start in **DevTools Performance panel** (or Lighthouse / `web-vitals` library / field data from CrUX) to see which elements shift and when. The usual culprits and fixes: **(1) Images/video/iframes without dimensions** — add `width`/`height` attributes or `aspect-ratio` so the browser reserves space before the resource loads. **(2) Web fonts causing FOUT/FOIT** — use `font-display: optional` or `swap` plus `size-adjust`/`ascent-override` on `@font-face` to match fallback metrics so the swap doesn't reflow. **(3) Injected content** (ads, banners, cookie bars) — reserve a `min-height` slot rather than pushing content down. **(4) Animations using `top`/`left`/`height`** — switch to `transform`. **(5) Late-loading CSS** that restyles already-painted content. In production I'd set a CLS budget in CI (Lighthouse-CI) so regressions fail the build, since CLS is a Core Web Vital that directly affects search ranking. Real-world: e-commerce teams have measured measurable conversion lifts after driving CLS below 0.1, because users mis-tap when the "Buy" button jumps under their finger.

### Q15. [Theory] What are ARIA landmarks, roles, and the "first rule of ARIA"?

ARIA (Accessible Rich Internet Applications) adds semantics that native HTML can't express, via `role`, `aria-*` state attributes (`aria-expanded`, `aria-checked`, `aria-hidden`), and properties (`aria-label`, `aria-describedby`). **Landmarks** (`banner`, `navigation`, `main`, `complementary`, `contentinfo`, `search`) let screen-reader users jump between page regions — most are implied by semantic elements (`<main>` ⇒ `role="main"`), which is why semantic HTML is the foundation. The **first rule of ARIA** is: *don't use ARIA if a native element does the job.* A `<button>` is better than `<div role="button" tabindex="0" aria-pressed>` because native gives you focus, keyboard handling, and form behavior for free, and ARIA only changes how assistive tech announces an element — it adds *zero* behavior. Other rules: don't change native semantics (`<h1 role="button">` is wrong), make all interactive ARIA controls keyboard-operable, and never give a focusable element `aria-hidden="true"` (a "ghost focus" trap). ARIA is powerful for custom widgets (comboboxes, tabs, tree views) where no native element exists — follow the WAI-ARIA Authoring Practices patterns rather than inventing your own.

### Q16. [Theory] Explain the `:has()` selector and why it's called the "parent selector". Give real use cases.

`:has()` matches an element that *contains* something matching the inner selector, finally giving CSS a relational/parent selector that was impossible for 25 years. `figure:has(figcaption)` styles figures that have captions; `form:has(input:invalid)` styles a whole form when any field is invalid; `:has(> img)` targets cards that contain a direct-child image. It also enables previously JS-only patterns: `label:has(+ input:checked)` styles a label based on its sibling's state, and `body:has(dialog[open])` can disable background scroll when a modal is open — entirely in CSS. It composes with `:not()` for powerful queries like `article:not(:has(h2))`. The trade-offs: `:has()` can be expensive on huge DOM trees because the engine must evaluate subtree state, and it's *forgiving* (an invalid argument doesn't break the whole rule). It reached cross-browser baseline support in 2023–2024, so by 2026 it's production-safe without polyfills. Its arrival has meaningfully reduced the amount of JavaScript needed for state-driven styling.

### Q17. [Coding] Build an accessible, CSS-only tabs/accordion that toggles content visibility.

**Problem:** A disclosure widget (FAQ accordion) that opens/closes without JavaScript, is keyboard accessible, and announces state to screen readers.

```html
<style>
  details.faq {
    border: 1px solid #ddd;
    border-radius: 6px;
    margin-block: 0.5rem;
  }
  details.faq > summary {
    cursor: pointer;
    padding: 0.75rem 1rem;
    font-weight: 600;
    list-style: none; /* hide default marker */
  }
  details.faq > summary::after {
    content: "＋";
    float: inline-end; /* logical property: right in LTR, left in RTL */
  }
  details.faq[open] > summary::after { content: "－"; }
  details.faq > .panel { padding: 0 1rem 1rem; }
</style>

<details class="faq">
  <summary>What is your refund policy?</summary>
  <div class="panel">Full refund within 30 days.</div>
</details>
<details class="faq">
  <summary>Do you ship internationally?</summary>
  <div class="panel">Yes, to 40 countries.</div>
</details>
```

The native `<details>`/`<summary>` pair gives keyboard support (Enter/Space toggles), focusability, and the correct `aria-expanded`-equivalent state announcement *for free* — this is the "first rule of ARIA" in action. **Edge cases:** to make it a true accordion (only one open at a time) you historically needed JS, but the `name` attribute on `<details>` (baseline 2024) now groups them so opening one closes its siblings — pure HTML. Note `list-style: none` plus the `::-webkit-details-marker` reset may be needed in older Safari to fully hide the disclosure triangle. **Time/Space:** O(1), zero JS payload, progressively enhanced — it works even if scripts fail.

### Q18. [Practical] How would you architect CSS for a large, multi-team design system to avoid specificity wars and bloat?

I'd combine a methodology and modern primitives. **(1) Cascade layers** (`@layer reset, tokens, base, layout, components, utilities`) to make priority explicit and immune to specificity accidents. **(2) Design tokens** as CSS custom properties at the root, with semantic aliases (`--color-surface` → `--gray-100`) so theming and dark mode are one-line overrides. **(3) A naming methodology** — BEM (`.card__title--featured`) for hand-written components to keep selectors flat (single class, specificity 0,1,0) and self-documenting, or utility-first (Tailwind) where churn is high. **(4) Scoping** via CSS Modules, Shadow DOM, or the native `@scope` rule to prevent leakage between teams. **(5) Tooling**: stylelint to forbid IDs and `!important`, a visual-regression suite, and a CSS budget in CI. **Trade-offs:** BEM is verbose but explicit and framework-agnostic; utility-first reduces CSS bloat dramatically (atomic reuse) but pushes complexity into markup and needs a build step. For a *platform* shared across teams, I bias toward layers + tokens + Shadow DOM scoping because encapsulation guarantees one team can't break another — the single most expensive failure mode at scale.

### Q19. [Theory] What are logical properties and why do they matter for internationalization?

Logical properties replace physical directions (`left`/`right`/`top`/`bottom`) with flow-relative ones (`inline-start`/`inline-end`/`block-start`/`block-end`): `margin-inline-start` instead of `margin-left`, `padding-block` instead of `padding-top/bottom`, `inset-inline-end` instead of `right`, and shorthands like `margin-inline`/`padding-block`. They matter because text direction varies: in left-to-right English, `inline-start` is left, but in right-to-left Arabic or Hebrew it flips to the right, and in vertical writing modes (Japanese) the inline axis becomes vertical. Using logical properties means a single stylesheet correctly mirrors for RTL languages automatically — no `[dir="rtl"]` override stylesheet, which historically doubled CSS and was bug-prone. This is the modern default for any product with international ambitions; the only reason to use physical properties is when you genuinely mean a fixed physical edge regardless of language (rare).

---

## 🔴 Expert (15+ yrs)

### Q20. [Theory] How do Shadow DOM, the cascade, and `::part()`/`@scope` interact — and what are the styling boundaries?

Shadow DOM creates an **encapsulation boundary**: styles defined inside a shadow root don't leak out, and most outer page styles don't leak in — only *inherited* properties (color, font) cross by default. This solves global-namespace collisions but creates a new problem: how does a consumer theme a web component they can't reach into? Three escape hatches: **(1) inherited custom properties** pierce the boundary, so exposing `var(--button-bg)` is the primary theming contract; **(2) `::part()`** lets the component author opt specific elements (`part="label"`) into external styling via `my-el::part(label)`; **(3) the deprecated `::theme`/`:host`/`:host-context`** for internal targeting. Cascade-wise, `:host` rules have low specificity and can be overridden by the page if you're not careful. The newer native **`@scope`** rule offers lighter-weight scoping without Shadow DOM — `@scope (.card) to (.content)` bounds rules to a subtree with proximity-based resolution. The expert judgment is choosing the right boundary strength: Shadow DOM for true black-box components shipped to unknown consumers, `@scope`/CSS Modules for internal app code where some leakage is acceptable and DevTools ergonomics matter.

### Q21. [Practical] Describe a time you diagnosed a subtle, high-impact CSS/rendering performance problem. (Behavioral + technical)

A representative case: a data-heavy dashboard janked badly while scrolling a long virtualized table. Profiling in the Performance panel showed the main thread pinned on "Recalculate Style" and "Layout" during scroll, with purple (layout) bars dominating each frame. The root cause was **layout thrashing**: a tooltip-positioning routine read `getBoundingClientRect()` inside a loop *after* writing inline styles, forcing a synchronous reflow on every iteration — N reads × N writes = O(N²) layouts. The fix had three parts: **(1)** batch all reads first, then all writes (read/write separation), dropping it to two layout passes; **(2)** move the animated highlight from `top`/`height` to `transform: translateY()` so it composited on the GPU instead of triggering layout; **(3)** add `content-visibility: auto` with `contain-intrinsic-size` to off-screen rows so the browser skips rendering work for them entirely. Frame time dropped from ~45ms to under 8ms. The broader lesson I emphasize to teams: the expensive part of CSS is rarely the selectors — it's the *interaction with JavaScript* and forced synchronous layout. I now add a `will-change`/containment review and a frame-budget check to performance-sensitive PRs.

### Q22. [Theory] Explain stacking contexts, the painting order, and why `z-index` "doesn't work" sometimes.

A **stacking context** is a 3D conceptual layer; `z-index` only orders elements *within the same stacking context* — it cannot lift a child out past its parent's context. This is why `z-index: 9999` on a nested modal can still render *behind* a sibling tree whose ancestor has a higher-stacked context. Stacking contexts are created by the root element, any positioned element with a `z-index` other than `auto`, *and* — the gotcha — by `opacity < 1`, `transform`, `filter`, `will-change`, `mix-blend-mode`, `isolation: isolate`, and `position: fixed/sticky`. So adding `opacity: 0.99` for a fade can silently trap all descendants in a new context and break a previously working `z-index`. Within a context, paint order is: background/borders → negative-z children → block descendants → floats → inline descendants → positive-z children. The expert fix for "z-index hell" is architectural: minimize positioned/z-indexed elements, use `isolation: isolate` to *deliberately* create a contained context for a component so its internal z-indexes can't fight the rest of the page, and render true overlays (modals, tooltips) into a top-level portal — or use the modern **top layer** via `<dialog>`/`popover` API, which renders above all stacking contexts regardless of z-index entirely.

### Q23. [Theory] What modern CSS features (2023–2026) have meaningfully changed how you write CSS, and what are their trade-offs?

Several have moved from "nice to have" to load-bearing: **Container queries** (`@container`) make components truly context-responsive, decoupling them from the viewport — the single biggest win for design systems, at the cost of needing `container-type` and a new containment context. **`:has()`** killed a large class of state-syncing JavaScript. **Cascade layers** (`@layer`) ended specificity wars and tamed third-party CSS. **Native nesting** (`&`) removed a major reason to reach for Sass. **`@property`** (registered custom properties) made variables typed and animatable, enabling gradient/angle animations. **Subgrid** lets nested grids align to their ancestor's tracks — finally solving card-internal alignment. **The Popover API and `<dialog>`** with the top layer eliminated z-index/portal hacks for overlays and provide built-in focus management and light-dismiss. **`color-mix()`** and OKLCH color enable perceptually uniform palettes and runtime tinting. The meta-trade-off is *capability vs. mental model*: CSS is now powerful enough to do logic that used to live in JS, which is great for performance and resilience, but it raises the bar for understanding containment, the cascade, and rendering — junior developers can now write CSS that is subtly expensive or behaviorally surprising. I treat baseline-availability (caniuse / web-platform "Baseline") as the gate before adopting any of these without fallbacks.

### Q24. [Behavioral] How do you drive CSS/accessibility quality across an org without becoming the bottleneck?

You can't manually review every line, so the goal is to make the right thing the default and the wrong thing hard. Concretely I've: **(1)** encoded standards as **automated gates** — stylelint configs (no IDs, no `!important`, enforce logical properties), axe-core/Lighthouse-CI accessibility checks in PR pipelines, and visual-regression tests so design tokens can't silently drift. **(2)** Built a **shared component library** so common patterns (modals, forms, tabs) ship accessibility correctly *once* and every team inherits it — this is the highest-leverage move because it converts a per-team discipline problem into a dependency. **(3)** Established **design tokens** as the single source of truth so brand/theme changes don't require touching components. **(4)** Invested in **enablement over enforcement** — internal docs, lunch-and-learns on the cascade and ARIA patterns, and pairing on the first hard case so knowledge spreads. The behavioral judgment: accessibility and CSS quality fail quietly and are expensive to retrofit, so the win is shifting them *left* into tooling and reusable primitives, and reserving my own time for the genuinely novel problems (a custom combobox, a tricky RTL layout) rather than policing the routine. Tie it to business metrics — Core Web Vitals affecting SEO, legal accessibility risk (ADA/EAA) — so it gets prioritized, not deferred.

---

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q25. [Theory] Explain the difference between block, inline, and inline-block display, and when each causes layout surprises.

`display` controls how a box participates in flow. **Block** elements (`<div>`, `<p>`, `<section>`) start on a new line, stretch to fill their container's inline width by default, and respect `width`, `height`, and all four margins/paddings. **Inline** elements (`<span>`, `<a>`, `<em>`) flow within text, sit on the line box, and — the key surprise — *ignore* `width`/`height` and vertical margins; only horizontal padding/margin apply (and even then the padding visually overlaps adjacent lines without pushing them). **Inline-block** is the hybrid: it flows inline like text (so several sit side by side) but accepts `width`/`height` and all box dimensions like a block.

The classic gotcha with inline and inline-block is the **whitespace gap**: because the elements are treated like words, the newlines/spaces between tags in your HTML render as a real space character (~4px), so three `inline-block` cards in a row won't sum to exactly 100% width. Historically people removed the markup whitespace, set `font-size: 0` on the parent, or used HTML comments between tags — all ugly. This is one of the strongest reasons Flexbox/Grid replaced inline-block for layout: flex items don't get whitespace gaps.

```css
/* inline-block whitespace gap workaround (legacy) */
.row { font-size: 0; }          /* kills the gap */
.row > .col { font-size: 1rem; display: inline-block; width: 33.333%; }

/* modern replacement — no gap, no hacks */
.row { display: flex; }
.row > .col { flex: 1; }
```

The other surprise is `vertical-align` on inline/inline-block: it aligns to the *baseline* by default, so an inline-block next to text often sits a few pixels too low until you set `vertical-align: middle` or `top`. Knowing that inline boxes live on a baseline-driven line box explains a whole category of "why is there a mysterious gap under my image?" bugs (the answer: images are inline by default and sit on the baseline, leaving descender space — fix with `display: block` or `vertical-align: bottom`).

#### Q26. [Practical] Walk through how you'd debug "my CSS isn't applying" — a rule you wrote has no effect.

This is a daily reality, and the systematic approach beats guessing. **First, confirm the rule is even reaching the element** — open DevTools, select the element, and look at the Styles pane. If your selector doesn't appear at all, the stylesheet isn't loading (404 in the Network tab), the selector doesn't match (typo, wrong nesting, the element is generated later), or a syntax error earlier in the file caused the parser to drop the rest of the block. A single missing `}` or an invalid property can silently kill everything after it.

**If the rule appears but is struck through, it's being overridden** — DevTools shows the winning rule and strikes out losers. Now it's a cascade question: is another selector more specific, is it `!important`, is it later in source order, or is it in a higher cascade layer? The Computed tab tells you the final value and which rule produced it. **If the rule appears and isn't struck through but still has no visual effect**, the property is being applied but something else is at play: the value is invalid (typo in the value, missing unit), the property doesn't apply to this element (e.g. `width` on an inline element, `vertical-align` on a block), or another property neutralizes it (`display: none`, `visibility: hidden`, zero height, `overflow: hidden` clipping it).

```
Decision tree:
Rule not in Styles pane?     → stylesheet/selector/syntax problem
Rule struck through?         → cascade override (specificity/!important/layer/order)
Rule applied but no effect?  → invalid value, property N/A to element, or neutralized
```

A few high-frequency culprits worth memorizing: inherited vs non-inherited confusion (setting `color` on a parent works; setting `border` doesn't inherit), specificity from an unexpected source like a framework reset in a cascade layer, and caching (a hard reload or disabling cache during dev rules out stale CSS). I treat "isn't applying" as a debugging protocol, not a vibe — the Styles pane answers the question in under 30 seconds.

#### Q27. [Theory] What is the purpose of the `<!DOCTYPE html>` declaration and the viewport meta tag?

`<!DOCTYPE html>` is not an HTML element or a tag with semantics — it's an instruction to the browser's *parser* about which rendering mode to use. With the modern doctype the browser uses **standards mode**, where layout follows the current specifications. Omit it (or use an old, malformed one) and the browser drops into **quirks mode**, emulating 1990s bugs for backward compatibility — most painfully, it reverts to the old `content-box`-but-broken box model where `width` included padding/border in a non-standard way, and various other inconsistencies. There's also "almost standards mode" which differs mainly in inline image table-cell spacing. The takeaway: always ship `<!DOCTYPE html>` as the very first line; quirks mode in 2026 is almost always an accident that produces baffling cross-browser layout bugs.

The **viewport meta tag** is essential for mobile. Without it, mobile browsers assume a desktop-width canvas (typically 980px) and then zoom the whole page out to fit the screen, so your carefully built responsive media queries never trigger and text is tiny. The standard incantation tells the browser to set the layout viewport to the device's actual width and start at 100% zoom:

```html
<meta name="viewport" content="width=device-width, initial-scale=1" />
```

Things to know and avoid: do *not* add `user-scalable=no` or `maximum-scale=1` — they disable pinch-zoom and are a serious accessibility (WCAG) failure for low-vision users. The newer `viewport-fit=cover` is used with `env(safe-area-inset-*)` to handle notched devices. The viewport tag is the single line that makes mobile-first CSS actually work; forgetting it is a common reason "my responsive site looks broken on a phone."

#### Q28. [Practical] When should you use the `alt` attribute, and how do you write good alt text vs. when to leave it empty?

`alt` provides a text alternative for images, consumed by screen readers, shown when the image fails to load, and indexed by search engines. The decision tree hinges on the image's *role*. **If the image conveys information** (a chart, a product photo, a meaningful diagram), `alt` must describe that information concisely and in context — not "image of" (screen readers already announce "image"), and not the filename. Good: `alt="Line chart: revenue grew 40% from Q1 to Q4"`. The alt text should let a non-sighted user get the same takeaway a sighted user gets, so describe the *meaning*, not every pixel.

**If the image is purely decorative** (a background flourish, an icon next to text that already says the same thing), give it an *empty* alt: `alt=""`. This is critical and often done wrong — an empty `alt` tells the screen reader to skip the image entirely, which is exactly right for decoration. Omitting the attribute *entirely* is different and worse: some screen readers then read out the filename (`hero_final_v2.jpg`), which is noise. So decorative images get `alt=""` (present but empty), never a missing attribute.

```html
<!-- Informative: describe the meaning -->
<img src="warning.svg" alt="Warning: payment failed" />

<!-- Decorative: explicitly empty so it's skipped -->
<img src="divider-swirl.svg" alt="" />

<!-- Icon + adjacent text already conveys it → decorative -->
<button><img src="trash.svg" alt="" /> Delete</button>

<!-- Icon-only button → alt carries the label -->
<button><img src="trash.svg" alt="Delete item" /></button>
```

The nuanced cases are functional images and redundancy. An image *inside a link or button* with no other text must have alt describing the *action/destination* ("Home", "Delete item"), because that becomes the accessible name. But if the icon sits next to a visible text label that already says "Delete," repeating it is redundant verbosity — make the icon `alt=""`. For complex images like infographics, a short `alt` plus a longer description nearby (or via `aria-describedby`) is the accessible pattern. Writing alt well is judgment, not boilerplate.

### 🟡 Intermediate — extended

#### Q29. [Theory] Compare CSS units: `px`, `em`, `rem`, `%`, `vw/vh`, `ch`, and `fr`. When does each shine?

CSS units split into **absolute** (`px`) and **relative** (everything else), and the art is picking a unit whose reference point matches the thing you're sizing. `px` is one device-independent pixel — predictable and fine for borders, small fixed details, and shadows, but it doesn't scale with user font-size preferences, so using it for type and spacing harms accessibility. `rem` is relative to the **root** font-size, so it scales globally when a user bumps their browser's default size; it's the modern default for typography and spacing because it's predictable (one fixed reference) *and* respects user settings. `em` is relative to the **element's own** font-size, which compounds when nested (an `em` inside an `em` inside an `em` multiplies) — that compounding is a footgun for layout but a feature for component-internal scaling like padding that should grow with the component's text.

```
Reference point per unit:
px  → fixed device pixel        (no scaling)
rem → root <html> font-size     (global, predictable)
em  → current element font-size (compounds when nested)
%   → parent's corresponding dimension
vw/vh → 1% of viewport width/height
ch  → width of the "0" glyph    (great for text measure)
fr  → fraction of free space in a grid container
```

`%` is relative to the parent's corresponding dimension (width % to parent width, but `padding`/`margin` percentages are *always* relative to the parent's *width*, even vertically — a classic surprise used for aspect-ratio hacks before `aspect-ratio` existed). Viewport units `vw`/`vh`/`vmin`/`vmax` size relative to the viewport — great for full-screen heroes and fluid type, but raw `vh` on mobile fights the disappearing address bar (the newer `svh`/`lvh`/`dvh` "small/large/dynamic viewport height" units fix this). `ch` (width of the `0` character) is perfect for capping line length at a readable measure (`max-width: 65ch`). `fr` exists only inside Grid and represents a fraction of *leftover* space after fixed tracks are allotted. My defaults: `rem` for type/spacing, `ch` for prose width, `fr`/`minmax` for grid tracks, `dvh` for full-height sections, `px` for hairline borders.

#### Q30. [Practical] How do you implement dark mode properly, respecting both system preference and a user override?

The robust pattern is two layers: **respect the OS preference by default** via the `prefers-color-scheme` media query, and **let the user override it** with an explicit toggle that persists. The mistake is doing only one — system-only ignores users who want a different choice on your site specifically; toggle-only ignores the user's OS setting on first visit. Drive everything through **CSS custom properties** so a theme switch is just swapping variable values, not rewriting component CSS.

```css
:root {
  color-scheme: light dark;          /* tells UA to theme form controls/scrollbars */
  --bg: #ffffff;
  --text: #111111;
}
@media (prefers-color-scheme: dark) {
  :root { --bg: #0d0d0d; --text: #e8e8e8; }
}
/* Explicit user override wins via a data attribute on <html> */
:root[data-theme="dark"]  { --bg: #0d0d0d; --text: #e8e8e8; }
:root[data-theme="light"] { --bg: #ffffff; --text: #111111; }

body { background: var(--bg); color: var(--text); }
```

The `data-theme` attribute is set by a tiny script that reads `localStorage` and falls back to the system preference; because the attribute selector and the media query both write the same variables, components never care which path set them. Two production details matter a lot. First, **the flash of wrong theme (FOWT)**: if you read `localStorage` in a deferred bundle, the page paints light then snaps to dark. The fix is a small *blocking* inline script in `<head>` that sets `data-theme` before first paint. Second, `color-scheme: light dark` is easy to forget but important — it tells the browser to render native widgets (form inputs, scrollbars, the default `<select>` dropdown) in the matching scheme, otherwise you get a white scrollbar on a black page.

Beyond plumbing, dark mode is a design problem: don't just invert colors. Pure `#000`/`#fff` causes halation and eye strain, so use very dark grays and slightly-off whites; reduce shadow intensity (shadows read poorly on dark) and instead convey elevation with lighter surface tints; and re-check contrast ratios because a color that passed WCAG on white can fail on dark. I keep a parallel set of semantic tokens (`--surface`, `--surface-raised`, `--border`) rather than raw colors so both themes stay consistent.

#### Q31. [Coding] Build a fluid, accessible "media object" (image left, text right) that wraps gracefully on small screens.

**Problem:** The classic media object — a fixed-size avatar/thumbnail beside a flexible text block — that stays on one row on wide screens but stacks on narrow ones, without media queries, and remains accessible.

```html
<style>
  .media {
    display: flex;
    flex-wrap: wrap;          /* lets the text drop below when space is tight */
    gap: 1rem;
    align-items: start;
  }
  .media__figure {
    flex: 0 0 auto;           /* never grow or shrink the image */
    margin: 0;
  }
  .media__figure img {
    display: block;           /* kills baseline gap under the image */
    width: 64px; height: 64px;
    border-radius: 50%;
    object-fit: cover;        /* crop, don't distort, non-square sources */
  }
  .media__body {
    flex: 1 1 16rem;          /* grow/shrink, but wrap once below ~16rem */
    min-width: 0;             /* allow long words/URLs to shrink & ellipsis */
  }
  .media__body h3 { margin: 0 0 0.25rem; }
</style>

<article class="media">
  <figure class="media__figure">
    <img src="avatar.jpg" alt="Jordan Lee" />
  </figure>
  <div class="media__body">
    <h3>Jordan Lee</h3>
    <p>A long bio that flows beside the avatar and stacks underneath on
       narrow screens without any media query.</p>
  </div>
</article>
```

The intrinsic-responsiveness trick is `flex: 1 1 16rem` on the body: its `flex-basis` is `16rem`, so as long as there's room for the image *plus* 16rem of text they stay on one line; when the container shrinks past that, `flex-wrap: wrap` drops the body to the next line and it expands to full width. No breakpoints — the component responds to *its own* available space, which is exactly the behavior container queries also give you but here it's free.

The two non-obvious correctness details are `min-width: 0` and `object-fit`. Flex items have a default `min-width: auto`, meaning they refuse to shrink below their content's intrinsic size — a long unbreakable URL or word will then *overflow* the container and break the layout. Setting `min-width: 0` lets the item shrink and lets `text-overflow: ellipsis`/`overflow-wrap` work. `object-fit: cover` ensures a non-square source image fills the circle without distortion. **Time/Space:** O(1) layout, zero JS, and it degrades gracefully if styles fail (it's just a figure and a text block).

#### Q32. [Theory] What's the difference between pseudo-classes and pseudo-elements, and what are the rendering implications of `::before`/`::after`?

A **pseudo-class** (single colon: `:hover`, `:focus`, `:nth-child()`, `:checked`, `:disabled`) selects an existing element based on its *state* or position — it doesn't create anything; it's a conditional match. A **pseudo-element** (double colon: `::before`, `::after`, `::first-line`, `::marker`, `::selection`, `::placeholder`) targets a *part* of an element or generates a sub-box that doesn't exist in the DOM. The single vs double colon convention was introduced in CSS3 to distinguish them, though browsers accept single colons on the original four pseudo-elements for legacy reasons. The practical difference: pseudo-classes are about "which element," pseudo-elements are about "which fragment or generated piece of an element."

`::before` and `::after` generate boxes that are children of the element, inside it, before/after its actual content — but they only render if `content` is set (even `content: ""` for a purely visual box). They're laid out as if they were real child elements: an inline `::before` sits on the line box, a `display: block` one stacks. This makes them perfect for decorative flourishes, icons, counters, and clearfix without polluting the HTML, and crucially they keep decoration out of the DOM so it stays non-semantic and invisible to screen readers (mostly — generated `content` text *can* be announced, which is a double-edged sword).

```css
/* Decorative quote marks — not in the DOM, not read as content */
blockquote::before { content: "\201C"; font-size: 3rem; color: var(--accent); }

/* Required-field asterisk generated from CSS, not hardcoded in markup */
.field--required label::after { content: " *"; color: crimson; }

/* Modern clearfix (rarely needed post-flex/grid) */
.clearfix::after { content: ""; display: block; clear: both; }
```

The rendering implications to know: pseudo-elements can't hold real interactive content (you can't put a focusable button in `::after`), they can't be selected by JavaScript as DOM nodes, and accessibility is subtle — never put *meaningful* information solely in generated content because support for announcing it is inconsistent and it vanishes if CSS fails. Also, only one `::before` and one `::after` per element (no stacking multiple). For performance they're cheap, but animating a `::before`'s `width`/`top` triggers layout just like a real element would.

#### Q33. [Practical] You need a 3-state toggle UI and the design uses an icon font. Argue for or against icon fonts vs inline SVG in 2026.

In 2026 I'd argue **against icon fonts and for inline SVG** in nearly all new code, and I'd frame it as a series of concrete trade-offs rather than dogma. Icon fonts (FontAwesome-style) had real advantages in their era: a single HTTP request for hundreds of glyphs, trivial sizing/coloring via `font-size` and `color`, and they inherited text styling. But they carry serious problems: glyphs are mapped to Private Use Area code points, so a screen reader or a failed font load can read out garbage characters or boxes; they only support a single color (no multi-tone icons without layering hacks); anti-aliasing makes them render slightly blurry because they're treated as text on a baseline; and they're an all-or-nothing payload unless you subset.

```html
<!-- Anti-pattern: icon font — semantics depend on a font loading correctly -->
<i class="icon-trash" aria-hidden="true"></i>

<!-- Preferred: inline SVG — accessible, multicolor, crisp, tree-shakeable -->
<button aria-label="Delete">
  <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <path fill="currentColor" d="M9 3h6l1 2h4v2H4V5h4l1-2zM6 9h12l-1 12H7L6 9z"/>
  </svg>
</button>
```

Inline SVG wins on the dimensions that matter now: it's **crisp at any resolution** (vector, no baseline blur), supports **multiple colors and gradients**, can be **animated and styled per-path** with CSS (including `currentColor` so it still inherits text color when you want), is **accessible** when you add `aria-label` on the control and `aria-hidden`/`focusable="false"` on the SVG, and with a build step you only ship the icons you actually use (no dead weight). The historical downside — repeating SVG markup bloats HTML — is solved by an SVG *sprite* (`<use href="#icon-trash">` referencing a symbol sheet) or by component frameworks that inline icons as components and dedupe them.

When would I still tolerate an icon font? Legacy codebases where ripping it out isn't worth the churn, or extremely constrained environments. But for a new 3-state toggle, inline SVG (or an SVG sprite) gives me accessible labels per state, the ability to tint each state differently, smooth transitions between icons, and no risk of the "all my icons turned into squares" failure mode when a font CDN hiccups. The performance argument also flipped: HTTP/2 multiplexing and sprite sheets erased the "one request" benefit fonts once had.

#### Q34. [Theory] Explain `prefers-reduced-motion` and how you'd build motion that respects accessibility without killing all animation.

`prefers-reduced-motion` is a media query exposing an OS-level user setting ("Reduce Motion") that signals the user is sensitive to motion — vestibular disorders, migraines, or simple preference. Large parallax, sliding panels, zooming transitions, and auto-playing motion can cause real nausea and disorientation for these users, so respecting this is a genuine accessibility requirement (WCAG 2.3.3), not a nicety. The query has two states: `reduce` (user wants less motion) and `no-preference` (default).

The naive approach — kill *all* animation — is too blunt; reduced motion doesn't mean *no* feedback, it means *no large/vestibular-triggering* motion. The good pattern is to keep essential, small, non-translational feedback (a fade, a quick color/opacity change) while removing big movement (slides, parallax, scale). I build animations **on by default** and then *opt down* under the media query, or better, gate the motion itself behind `no-preference`:

```css
/* Essential feedback stays (opacity is low-risk); large motion is removed */
.modal { transition: opacity 150ms ease; }

@media (prefers-reduced-motion: no-preference) {
  /* Only ADD the big movement when motion is welcome */
  .modal { transition: opacity 150ms ease, transform 250ms ease; }
  .modal[hidden] { transform: translateY(16px); }
}

/* Belt-and-suspenders global guard for anything that slipped through */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

The nuance worth articulating in an interview: the global "nuke everything" snippet (the second block) is a reasonable safety net, but the *better* engineering is to design each animation intentionally — decide which conveys necessary state (keep a fast fade) and which is pure delight (remove the slide/zoom). Also remember JavaScript-driven animations (`Web Animations API`, scroll-triggered libraries) don't see your CSS media query automatically — you must check `window.matchMedia('(prefers-reduced-motion: reduce)').matches` and branch in JS too. And `scroll-behavior: smooth` should be disabled under reduce, since smooth-scroll jumps are themselves vestibular triggers.

### 🟠 Advanced — extended

#### Q35. [Theory] Deep-dive container queries: how do `container-type`, containment, and `cqw`/`cqi` units actually work, and what breaks?

Container queries let a component respond to the size of an *ancestor container* rather than the viewport, which is the missing piece for true component reuse — the same card behaves correctly whether it lands in a 300px sidebar or a 900px main column. The mechanism: you declare an element a query container with `container-type`, optionally name it with `container-name`, and then children query it with `@container`. `container-type: inline-size` makes it queryable on the inline axis (the common case — width in horizontal writing modes); `size` queries both axes but requires the container to have an *explicit* size on the queried axis.

```css
.card-wrapper {
  container-type: inline-size;
  container-name: card;          /* optional; lets you target a specific container */
}
/* Query the NEAREST ancestor that is a container of this name/type */
@container card (min-width: 400px) {
  .card { display: grid; grid-template-columns: 120px 1fr; }
}
/* Container query units resolve against the container, not the viewport */
.card h3 { font-size: clamp(1rem, 4cqi, 1.5rem); }  /* cqi = 1% of container inline size */
```

The critical thing that surprises people is that **`container-type` establishes containment**, specifically `layout` and (for `inline-size`) `inline-size` containment, plus `style` containment. The consequence: a container with `container-type: inline-size` can no longer be sized by its *contents* on the block axis the way a normal block is — its size is "contained," so a container that depended on intrinsic height collapsing/expanding may behave differently, and you cannot query a container based on a dimension that the content itself determines (that would be a circular dependency, which is exactly why size containment is required — it cuts the loop). This is why `container-type: size` needs an explicit height: without containment cutting the cycle, "size the box from content, then resize content from the box" would be infinite.

What breaks in practice: (1) you can't query the element you're styling — the query target must be a *descendant* of the container, so you typically add a wrapper div whose only job is to be the container. (2) Container query units (`cqw`, `cqh`, `cqi`, `cqb`, `cqmin`, `cqmax`) resolve against the nearest container, so forgetting to declare one makes them fall back to the small viewport or behave unexpectedly. (3) Margins can collapse differently and some intrinsic-sizing behaviors (like `width: max-content` flowing past the container) interact oddly with containment. (4) Deeply nesting containers has a cost since each is a containment boundary. The mental model that keeps you out of trouble: a query container is a box that *promises* its size doesn't depend on the content you're about to measure — that promise is what makes the query resolvable.

#### Q36. [Practical] Describe a real strategy to ship critical CSS and eliminate render-blocking stylesheets without breaking the cascade.

The problem: a single large `<link rel="stylesheet">` in `<head>` is render-blocking — the browser won't paint until it's downloaded and the CSSOM is built — so a 150KB stylesheet on a slow connection delays first paint by hundreds of milliseconds even though only ~5KB is needed for the initial viewport. The strategy is **critical CSS inlining plus async loading the rest**, and the subtlety is doing it without causing a Flash of Unstyled Content (FOUC) or breaking cascade order.

Step one: extract the styles needed to render *above-the-fold* content (the visible viewport on load) and inline them in a `<style>` block in the `<head>`. Tools like `critical`, `critters`, or framework-integrated extractors automate this by rendering the page at target viewports and collecting matched rules. Step two: load the full stylesheet asynchronously so it doesn't block paint, then apply it once loaded:

```html
<head>
  <style>/* inlined critical CSS: layout, header, hero, fonts-display */</style>

  <!-- Async-load the full sheet so it isn't render-blocking; swap to stylesheet on load -->
  <link rel="preload" href="/main.css" as="style"
        onload="this.onload=null;this.rel='stylesheet'">
  <noscript><link rel="stylesheet" href="/main.css"></noscript>
</head>
```

The `media="print"` trick (or the `rel="preload"` + `onload` swap shown above) makes the browser fetch the sheet without it being render-blocking, then flip it to `all`/`stylesheet` when ready; the `<noscript>` fallback keeps it working without JS. The cascade danger is real: inlined critical CSS sits *before* the async full sheet in source order, so when the full sheet arrives it must not unexpectedly lose to or override the critical rules. Best practice is that critical CSS is a strict *subset* of the same authored rules (same selectors/specificity), so source order resolves identically — using **cascade layers** makes this bulletproof because you can pin layer order in the inline block (`@layer reset, base, components;`) and the async sheet appends to those same layers regardless of when it loads.

Operational caveats: critical CSS must be regenerated whenever above-the-fold markup changes or it goes stale (so wire it into the build, never hand-maintain it); inlining too much defeats the purpose (keep it to the initial viewport, a few KB); and for highly dynamic/personalized pages where "above the fold" varies, per-template extraction beats one global critical file. I measure success with Lighthouse's "Eliminate render-blocking resources" audit and the First Contentful Paint metric in field data, not just lab numbers.

#### Q37. [Theory] What is `content-visibility: auto` and CSS containment, and how do they improve rendering of long pages?

CSS **containment** (`contain: layout style paint size`) is a promise you make to the browser that an element's internals are isolated, so the engine can skip work outside its boundary. `contain: layout` means the element's layout doesn't affect anything outside it (and vice versa), so a change inside can't trigger reflow of the whole document. `contain: paint` means descendants won't paint outside the box, so if the box is off-screen the browser can skip painting it entirely. `contain: size` means the element's size doesn't depend on its contents (you must supply a size). `style` containment scopes certain counters/quotes. These let the browser **isolate subtrees** so a mutation in one widget doesn't cascade into a full-page reflow/repaint.

`content-visibility: auto` is the high-level, ergonomic application of containment for long pages. It tells the browser: "if this element is off-screen, skip its rendering work (layout, paint, and even styling of descendants) until it's about to scroll into view." For a page with thousands of comments, product rows, or chat messages, this can cut initial render time dramatically because the browser only does layout/paint for what's visible plus a margin, lazily rendering the rest on scroll — a built-in form of virtualization without JavaScript.

```css
.comment {
  content-visibility: auto;
  /* Tell the browser the assumed size of skipped content so the scrollbar
     and total page height are stable — without this you get scrollbar jump. */
  contain-intrinsic-size: auto 120px;
}
```

The crucial companion is `contain-intrinsic-size`: when content is skipped, the browser doesn't know its real height, so it uses this placeholder size to reserve space. Omit it and every off-screen item collapses to height 0, the scrollbar becomes meaningless, and scrolling causes constant jumpy reflows as items render and resize — a worse experience than no containment. The `auto` keyword (`contain-intrinsic-size: auto 120px`) is smart: once an element has been rendered once, the browser *remembers* its real size and reuses it, so the estimate only matters for never-yet-seen content.

The trade-offs and gotchas: off-screen content with `content-visibility: auto` is *not laid out*, so in-page find (Ctrl+F) still works (browsers special-case it) but JavaScript that measures `scrollHeight` or `getBoundingClientRect` of skipped elements gets the intrinsic-size estimate, not the real value — which can break scroll-spy or anchor-jump logic. Anchored links and focus into skipped content force it to render (browsers handle this). It's not a substitute for true virtualization when you have *hundreds of thousands* of nodes (the DOM still exists and costs memory), but for the common "long but bounded" page it's the cheapest possible win — one property, no JS.

#### Q38. [Coding] Implement scroll-snap for a horizontal carousel and explain the accessibility/UX considerations.

**Problem:** A horizontal, swipeable card carousel that snaps each card to the start of the viewport, works with touch/trackpad/keyboard, and needs no JavaScript for the core behavior.

```html
<style>
  .carousel {
    display: flex;
    gap: 1rem;
    overflow-x: auto;
    scroll-snap-type: x mandatory;   /* snap on the x axis, always snap */
    scroll-padding-inline: 1rem;     /* offset snap from the edge */
    -webkit-overflow-scrolling: touch;
    /* keyboard/trackpad smoothness; disabled under reduced-motion below */
    scroll-behavior: smooth;
  }
  .carousel > .slide {
    flex: 0 0 80%;                   /* each card ~80% of the viewport */
    scroll-snap-align: start;        /* snap this edge to the container start */
    scroll-snap-stop: always;        /* don't skip past a slide in one fling */
  }
  @media (prefers-reduced-motion: reduce) {
    .carousel { scroll-behavior: auto; }
  }
  /* Hide scrollbar visually but keep it operable (optional) */
  .carousel { scrollbar-width: thin; }
</style>

<div class="carousel" tabindex="0" role="region" aria-label="Featured products">
  <article class="slide" tabindex="0">Slide 1</article>
  <article class="slide" tabindex="0">Slide 2</article>
  <article class="slide" tabindex="0">Slide 3</article>
</div>
```

The core is three properties: `scroll-snap-type: x mandatory` on the scroll container declares the axis and strictness (`mandatory` always snaps to a point; `proximity` only snaps when you're already close, which is gentler for content of varying sizes), `scroll-snap-align: start` on each child says which edge snaps, and `scroll-snap-stop: always` prevents a fast swipe from flying past several slides — it forces one-at-a-time stepping, which is usually what carousels want. `scroll-padding`/`scroll-margin` fine-tune where the snap lands relative to sticky headers or gutters.

The accessibility considerations are where this question separates seniors from juniors. **Keyboard access:** a scroll container is not focusable or arrow-key scrollable by default, so I add `tabindex="0"` and `role="region"` with an `aria-label` so screen-reader and keyboard users can reach and operate it; making each slide focusable (`tabindex="0"` or a focusable element inside) lets `Tab` move through them and pulls each into view. **Don't trap or hide content:** if you visually hide the scrollbar, ensure the carousel is still operable by keyboard and that no content is permanently off-screen with no way to reach it. **Reduced motion:** `scroll-behavior: smooth` plus snapping can be a vestibular trigger, so I disable smooth scroll under `prefers-reduced-motion: reduce`. **Don't autoplay** without a pause control (WCAG 2.2.2). Finally, mandatory snapping can trap users if a slide is taller than the viewport or if `scroll-snap-stop: always` fights assistive scrolling, so I test with a screen reader and keyboard, and for complex carousels I layer on JS only to add visible prev/next buttons and a slide-position announcement — the CSS handles the smooth core, JS handles the affordances.

#### Q39. [Practical] A production incident: after a deploy, the site's layout is subtly broken only in Safari. How do you triage CSS cross-browser issues?

First I'd **stabilize** — confirm scope (only Safari? which versions? desktop and iOS? what percentage of traffic?), check whether a rollback is fast and safe, and if the breakage is high-impact (checkout, login), roll back first and diagnose second. Cross-browser CSS bugs are rarely worth keeping a broken experience live while you investigate. Then I'd reproduce deterministically: get the exact Safari version, ideally on real hardware or a service like a device lab, because Safari's bugs differ between macOS Safari and iOS WebKit, and iOS uses WebKit even in "Chrome."

Triage centers on **what's different about Safari**. The usual suspects, in rough order of frequency: (1) a **newly used CSS feature that Safari lags on or implements differently** — Safari has historically trailed on flexbox `gap`, `:has()` edge cases, some container query behaviors, `backdrop-filter` needing `-webkit-` prefix, and date/`<input>` styling. (2) **Vendor prefix regressions** — if the deploy changed the build, an Autoprefixer config or browserslist change may have dropped `-webkit-` prefixes Safari still needs. (3) **Viewport/100vh issues on iOS** — the dynamic toolbar makes `100vh` overflow; the fix is `100dvh`/`100svh`. (4) **Font rendering and `-webkit-font-smoothing`** differences shifting layout via metrics. (5) **Flexbox/grid sizing quirks** where Safari resolves `min-height`/intrinsic sizing differently.

```
Triage funnel:
1. Scope & impact  → rollback if high-impact, then diagnose
2. Reproduce       → exact Safari version, real device for iOS
3. Diff the deploy → what CSS/feature/build config changed? (git diff, browserslist)
4. Isolate         → binary-search the offending rule in Safari DevTools
5. Fix + guard     → prefix / feature-query fallback + add to test matrix
```

The technical method is **diff-driven**: what did this deploy change? `git diff` the CSS and the build config (browserslist/Autoprefixer, PostCSS plugins, a framework upgrade that changed emitted CSS). Then isolate in Safari's Web Inspector — toggle suspect rules off until the layout corrects, which pinpoints the property. The fix depends on cause: add the missing `-webkit-` prefix, wrap a risky feature in `@supports (property: value) { ... }` with a fallback so unsupported engines degrade instead of breaking, or swap `100vh` → `100dvh`. Critically, I close the loop by **adding the failing case to the cross-browser test matrix** (Playwright/WebKit, or a visual-regression run against Safari/WebKit) so the same class of bug fails CI next time — an incident that doesn't produce a regression test is an incident you'll have again. The cultural takeaway: "works in Chrome" is not "works," and a browserslist/`@supports` discipline plus a WebKit lane in CI is the systemic fix, not heroics.

#### Q40. [Theory] Explain `aspect-ratio`, `object-fit`, and `object-position` — how they replaced old hacks and their interaction with intrinsic sizing.

`aspect-ratio` lets you declare a box's width-to-height relationship so the browser computes the missing dimension automatically: `aspect-ratio: 16 / 9` on an element with a known width gives it a 16:9 height. Before this property, achieving a responsive fixed-ratio box required the infamous **padding-top hack** — an absolutely-positioned child inside a wrapper with `padding-top: 56.25%` (because percentage padding is relative to *width*, 9/16 = 56.25%). That hack worked but was unreadable, required an extra wrapper element, and made the actual content position absolute. `aspect-ratio` collapses all of that into one declarative line and applies directly to the element. Its main use today is **reserving space to prevent layout shift (CLS)**: pairing `width`/`height` attributes (or `aspect-ratio` in CSS) on images/embeds so the box's height is known before the resource loads.

```css
/* Old hack — extra wrapper, absolute child, magic number */
.embed { position: relative; padding-top: 56.25%; }
.embed > iframe { position: absolute; inset: 0; width: 100%; height: 100%; }

/* Modern — one line, no wrapper */
.embed { aspect-ratio: 16 / 9; width: 100%; }
.embed > iframe { width: 100%; height: 100%; }
```

`object-fit` and `object-position` solve a different problem: how a **replaced element's** content (an `<img>` or `<video>`) fills a box whose dimensions don't match the media's intrinsic ratio. `object-fit: cover` scales the media to cover the box, cropping overflow (like CSS `background-size: cover`, but on a real `<img>` so it stays accessible and printable); `contain` fits the whole media inside, letterboxing; `fill` (default) stretches and distorts; `none` keeps intrinsic size; `scale-down` picks the smaller of `none`/`contain`. `object-position` then controls *which part* shows when cropping — `object-position: top` keeps faces visible in a `cover` crop instead of centering on a chin. These replaced the old approach of setting images as CSS `background-image` purely to get `cover` behavior, which sacrificed semantics, alt text, and lazy-loading.

The intrinsic-sizing interaction is the subtle part. `aspect-ratio` only governs sizing when at least one dimension is *not* otherwise determined — if you set both an explicit `width` and `height`, those win and `aspect-ratio` is ignored; if content forces the box larger (and `min-height: auto` allows it), the ratio can be violated unless you also constrain overflow. For images, the modern browser behavior is that the `width`/`height` *attributes* establish an implicit `aspect-ratio`, so even with `width: 100%; height: auto` in CSS the browser reserves the correct space pre-load — which is precisely why putting real `width`/`height` attributes back on `<img>` tags (after years of being told to omit them) is now a CLS best practice. When combining them, the pattern is `aspect-ratio` to shape the box + `object-fit: cover` to fill it without distortion + `object-position` to choose the focal point.

### 🔴 Expert — extended

#### Q41. [Theory] Reason about the performance cost of CSS selectors and the browser's selector matching — does selector complexity actually matter at scale?

The counterintuitive expert answer is that **selector matching is rarely the bottleneck**, and optimizing selectors is one of the lowest-value CSS performance activities for most apps — yet understanding *why* matters because there are real exceptions. Browsers match selectors **right-to-left** (from the "key selector" — the rightmost compound — leftward), which is the opposite of how we read them. For `nav ul li a`, the engine doesn't find every `nav` and walk down; it finds every `<a>` (the key selector), then checks each one's ancestors for `li`, `ul`, `nav`, bailing as early as possible. This is why an overly broad key selector like `* {}` or `[class] {}` is the expensive part, not the depth of the ancestor chain.

```
Selector:  nav  ul  li  a
Reads L→R:  ───────────►   (how humans parse)
Matches R→L: ◄───────────  (how the engine evaluates: start at <a>, walk up)
Cost driver = how many elements match the KEY (rightmost) selector,
              not how long the chain is.
```

So the historical advice "don't nest selectors deeply for performance" is mostly a myth on modern engines for static stylesheets — the cost difference between a flat class and a 4-level descendant selector is negligible against layout/paint costs. Where selector cost *does* become real: (1) **enormous DOMs** (tens of thousands of nodes) combined with selectors that have huge candidate sets; (2) **`:has()` and complex relational selectors**, which can force the engine to evaluate subtree state and can invalidate widely — `:has()` is genuinely more expensive and can trigger broad style recalculation when descendants change; (3) **frequent style invalidation** — the cost isn't the *first* match, it's *re-matching* when the DOM mutates. A selector that's cheap to match once but invalidates a large subtree on every state change (e.g. a dynamic attribute high in the tree) hurts during interaction, which shows up as "Recalculate Style" time in the Performance panel, not as initial-load cost.

The expert framing: profile before optimizing selectors, because the real CSS performance levers are elsewhere — render-blocking CSS size, layout thrashing from JS, paint area, compositor layers, and animation properties. When selector cost *does* appear in a profile (long "Recalculate Style" during interaction on a big DOM), the fixes are reducing invalidation scope (flatter, class-based selectors so a mutation invalidates fewer elements), avoiding ultra-broad key selectors, being deliberate with `:has()` near the root, and using containment (`contain`) to bound how far invalidation propagates. In short: selector complexity matters at the *margins and at scale during interaction*, not in the average stylesheet on the average page — and conflating the two leads teams to micro-optimize the wrong thing.

#### Q42. [Practical] You're migrating a 200k-line legacy CSS codebase off `!important` and ID-selector specificity wars. Design the migration.

I'd treat this as a risk-managed, incremental migration, not a rewrite — a big-bang rewrite of 200k lines of CSS is how you ship a month of visual regressions for no incremental value. The foundation is **cascade layers (`@layer`)** because they let me change *priority* without touching *specificity*, which is exactly the lever needed to neutralize `!important` and ID wars non-destructively. The endgame is: legacy CSS lives in a low-priority layer, new CSS lives in higher layers, and over time we drain the legacy layer.

```css
/* Establish the priority spine ONCE, before any imports. */
@layer legacy, reset, tokens, base, components, utilities;

@layer legacy {
  @import "old/everything.css";   /* the entire existing codebase, demoted */
}
/* New work goes in higher layers and wins WITHOUT needing higher specificity
   or !important — even a 0,1,0 class here beats a #id .x !important in legacy. */
@layer components { .btn--primary { background: var(--brand); } }
```

The migration sequence: **(1) Characterize** — run stylelint and custom audits to inventory every `!important` and ID selector, and quantify (how many, where, which are dead code). Set up **visual-regression testing** (Playwright/Percy/BackstopJK screenshots across key pages and viewports) *first* — this is non-negotiable, because it's the safety net that lets you refactor aggressively and catch the inevitable pixel breakage. **(2) Wrap and demote** — put all legacy CSS into a `legacy` layer. Crucially, `!important` interacts with layers in a specific way: for *normal* (non-important) declarations, later layers win; but for `!important` declarations the layer order *reverses* (earlier important layers beat later important ones). So I can't just demote a layer and expect legacy `!important` to lose — I have to actually *remove* the `!important`s as I migrate rules out, which is the real work the layer structure makes safe to do incrementally.

**(3) Migrate by surface, not globally** — pick one feature/page at a time, lift its styles out of `legacy` into proper layers, strip `!important` and ID selectors down to flat classes, and let the visual-regression suite confirm zero pixel change. Add stylelint rules (`declaration-no-important`, `selector-max-id: 0`) scoped to the *new* layers and CI gates so the codebase can't regress while you migrate. **(4) Tokenize** in parallel — replace hardcoded colors/spacing with custom properties so theming stops being a specificity problem. **(5) Drain and delete** — track the shrinking legacy layer as a metric; when a surface is fully migrated, its legacy rules are deleted, not just overridden. The behavioral discipline: never let the migration block feature work (new features land in proper layers from day one), measure progress (lines in `legacy`, count of `!important`, IDs), and resist the urge to "just rewrite it" — the layered, regression-tested, surface-by-surface drain is slower but ships continuously without breaking production.

#### Q43. [Theory] Explain the CSS Houdini `@property` rule and why it enables animations that were previously impossible.

The fundamental limitation of plain CSS custom properties is that the browser treats every `var()` value as an **untyped string** until it's substituted — it has no idea whether `--angle` is an angle, a length, or gibberish. The consequence: you cannot *animate* or *transition* a custom property smoothly, because the engine can't interpolate between two strings. Transitioning `--progress` from `0%` to `100%` just flips at the midpoint (or doesn't transition at all) because there's no defined interpolation. This blocked a whole class of effects — animating gradient angles, conic-gradient pie charts, per-property staged animations — that had to be faked with JavaScript writing the variable on every frame.

`@property` (part of the CSS Houdini effort, now widely supported) lets you **register a custom property with a type, an initial value, and inheritance behavior**. Once a property has a `syntax` type, the browser knows how to interpolate it, so transitions and `@keyframes` work on it natively:

```css
@property --angle {
  syntax: "<angle>";       /* the type — now interpolatable */
  initial-value: 0deg;     /* required for non-inherited; used before set */
  inherits: false;
}

.spinner {
  background: conic-gradient(from var(--angle), var(--brand), transparent);
  transition: --angle 1s linear;   /* now animatable because it's typed */
}
.spinner:hover { --angle: 360deg; } /* smoothly rotates the gradient */
```

The mechanism unlocks several previously-impossible things: animated gradients (angle, color stops), animated `clip-path`/numeric counters, and staged/decomposed animations where you animate a single typed sub-value. `syntax` supports types like `<color>`, `<length>`, `<percentage>`, `<number>`, `<angle>`, `<image>`, `<url>`, plus combinators (`<length> | <percentage>`, `+` for lists, `#` for comma lists). Two correctness details: a registered non-inherited property *requires* an `initial-value` (so there's always a valid starting point), and registration adds **type validation** — an invalid value is rejected and falls back to the initial value rather than silently inheriting garbage, which makes design-token systems more robust.

The deeper significance is architectural: `@property` is part of giving developers **typed, first-class entry points into the CSS engine**, moving animation logic that used to require a `requestAnimationFrame` loop in JavaScript onto the compositor/CSS pipeline where it's cheaper and runs even when the main thread is busy. The trade-off is mental-model complexity and that the broader Houdini suite (the Paint API, Layout API, Typed OM) has uneven support, so I gate the more exotic pieces on Baseline — but registered properties themselves are production-safe by 2026 and I reach for them whenever I need to transition something a raw variable couldn't.

#### Q44. [Practical] Your team's CSS bundle has grown to 400KB and is hurting load time. Lay out a concrete reduction plan with measurement.

I'd run this as a measured engineering project, not a one-off cleanup, because CSS bloat regrows without guardrails. **Measure first**: capture the baseline — gzipped/Brotli transfer size (the only number users feel, not the raw 400KB), Coverage tab in DevTools to see what *percentage* of the shipped CSS is actually used on key pages (legacy bundles routinely show 70–90% unused), and the render-blocking impact on First Contentful Paint in field data (CrUX) and lab (Lighthouse). Without these I can't prioritize or prove the win.

The reduction levers, in order of typical ROI: **(1) Eliminate dead CSS** — the biggest single win in legacy codebases. Run a coverage-based tool (PurgeCSS / the framework's built-in purge, or a content-aware analysis) to drop selectors no live template references. The risk is false positives — dynamically constructed class names (`class={`btn-${color}`}`) get purged incorrectly — so I configure safelists and verify with visual-regression. **(2) Code-split CSS by route** so a user loading the marketing page doesn't download the dashboard's styles; modern bundlers extract per-route CSS chunks and load them on demand. **(3) Deduplicate** — large legacy bundles carry duplicate declarations, redundant vendor prefixes for browsers you no longer support (tighten browserslist!), and multiple copies of the same framework. **(4) Adopt atomic/utility CSS or stricter componentization** where churn is high, because atomic CSS has a sublinear growth curve — the 1000th component reuses existing utilities instead of adding new rules.

```
Plan & measurement loop:
Baseline → transfer size (Brotli) + Coverage % unused + FCP (field+lab)
   │
   ├─ Dead-code purge (coverage-driven)   → biggest win, watch dynamic classes
   ├─ Route-level code splitting          → ship only what the page needs
   ├─ Tighten browserslist                → drop dead prefixes/fallbacks
   ├─ Dedup + tokenize repeated values    → custom properties shrink repetition
   └─ Compression: Brotli, minify, split critical CSS inline
   │
Re-measure → assert ↓ transfer size & ↑ FCP; add CSS-size budget to CI
```

The crucial part is **locking in the gain**: add a **performance budget in CI** (e.g. fail the build if any route's CSS exceeds N KB gzipped, via bundlesize/Lighthouse-CI) so the bundle can't silently regrow — without this, it's back to 400KB in a year. I'd also split out **critical CSS** (inline the above-the-fold subset, async-load the rest, per the render-blocking discussion) so even the remaining CSS doesn't block first paint. Throughout, I gate every change behind the visual-regression suite, because the failure mode of aggressive CSS reduction is subtle visual breakage that no unit test catches. I report progress as transfer-size and FCP deltas tied to the budget, so the work stays accountable and the improvement is defended, not just achieved once.

#### Q45. [Theory] Discuss the top layer, the Popover API, and `<dialog>` — how do they escape stacking-context and z-index limitations entirely?

The classic overlay problem is structural: a modal, tooltip, or dropdown that lives deep in the DOM is *trapped* inside its ancestors' stacking contexts. No matter how high you crank `z-index`, the overlay can only compete *within* its parent's context, so a modal rendered inside a `transform`ed or `opacity < 1` ancestor renders *behind* unrelated content. For 20 years the workaround was **portals** — JavaScript that yanks the overlay's DOM node out and reparents it to `<body>` so it escapes the trapping contexts — which React/Vue/etc. all built (`createPortal`). It works but it's a hack: you've separated an element from its logical position in the tree, complicating event bubbling, focus, accessibility relationships, and SSR.

The **top layer** is a browser-native solution: a special rendering layer that sits *above the entire page* regardless of any stacking context or z-index. Elements promoted to the top layer paint on top of everything, painted in the order they were added, and `z-index` becomes irrelevant among them. You don't get there with CSS — you get there by using APIs that promote an element to the top layer: `<dialog>.showModal()` and the `popover` attribute. This is the architectural fix the earlier stacking-context question alluded to: instead of fighting stacking contexts, you opt out of them.

```html
<!-- Native modal: showModal() promotes it to the top layer + adds a backdrop -->
<dialog id="confirm">
  <form method="dialog">
    <p>Delete this item?</p>
    <button value="cancel">Cancel</button>
    <button value="ok">Delete</button>
  </form>
</dialog>

<!-- Popover: declarative, top-layer, light-dismiss, no JS for basic cases -->
<button popovertarget="menu">Menu</button>
<div id="menu" popover>…menu items…</div>

<script> document.getElementById('confirm').showModal(); </script>
```

The wins go beyond z-index. `<dialog>.showModal()` gives **built-in focus management** (focus moves into the dialog, is trapped while open, and returns to the trigger on close), `Escape`-to-close, the `::backdrop` pseudo-element for the dimming layer, and an inert background (the rest of the page becomes non-interactive and hidden from assistive tech) — all the things you previously hand-rolled and usually got subtly wrong (focus traps are notoriously hard to implement correctly). The Popover API adds **light-dismiss** (click outside or `Escape` closes it), declarative wiring via `popovertarget` with *zero JavaScript* for the common case, automatic top-layer promotion, and `auto` vs `manual` popover types that control whether opening one closes others. Combined with **anchor positioning** (`anchor()`/`position-anchor`, the newest piece), you can tether a top-layer popover to its trigger without JS measuring positions.

The expert takeaway: these features collapse a large, bug-prone category of UI infrastructure — portals, manual focus trapping, scroll locking, custom backdrops, z-index escalation — into platform primitives that are accessible by default. I now reach for `<dialog>`/`popover` first and only fall back to portals for legacy-browser support or cases the APIs don't yet cover (deeply custom positioning before anchor positioning is baseline). The trade-offs are that styling the top layer and `::backdrop` has its own learning curve, anchor positioning support is still maturing, and you must still manage the *content's* accessibility (labels, roles) — the platform handles the *plumbing*, not your semantics.

### 🟡 Intermediate — extended (continued)

#### Q46. [Theory] Explain margin collapsing — when it happens, when it doesn't, and why it surprises people.

Margin collapsing is the rule that **adjacent vertical margins combine into a single margin equal to the larger of the two**, rather than summing. It applies only to *block-level boxes in the normal flow*, only on the *block axis* (vertical in horizontal writing modes — horizontal margins never collapse), and in three situations: between **adjacent siblings** (the bottom margin of one and the top margin of the next collapse to the max), between a **parent and its first/last child** (a parent's top margin collapses with its first child's top margin if nothing separates them), and within an **empty block** (its own top and bottom margins collapse together). The "max, not sum" behavior is what trips people up — two stacked paragraphs with `margin: 20px` are separated by 20px, not 40px.

The parent/child collapse is the more insidious one: give a child `margin-top: 40px` and instead of pushing the child down *inside* the parent, the margin "escapes" and pushes the *parent* down, leaving the parent's top edge flush with the child. This produces the classic "my margin is leaking out of its container" bug. Collapsing is *prevented* by anything that separates the margins: padding or a border on the parent, `overflow` other than `visible` (creating a block formatting context), `display: flow-root` (the clean, purpose-built way to establish a BFC), or the parent/child being flex/grid items (flex and grid containers do **not** collapse their items' margins at all — another reason modern layout sidesteps the whole issue).

```css
/* Margin "leaks" out: parent has no border/padding/BFC */
.parent { background: #eee; }
.child  { margin-top: 40px; }   /* pushes .parent down, not .child */

/* Fix: establish a block formatting context — clean and side-effect-free */
.parent { display: flow-root; } /* now the 40px stays inside */
```

The interview-level insight is *why* this exists: it's a typographic convenience from CSS's document-layout origins — collapsing means consecutive paragraphs/headings get consistent spacing without authors doing arithmetic. But in app UIs it causes confusion, which is why many teams adopt a "single-direction margin" convention (only ever `margin-bottom` or only `margin-top`) or skip margins entirely in favor of `gap` on flex/grid parents, which never collapses and makes spacing explicit and predictable.

#### Q47. [Practical] Forms: how do you build accessible, well-validated HTML forms, and what native features replace JavaScript?

Modern HTML gives you a surprising amount of form capability for free, and the accessible baseline is mostly about *structure*. Every input needs an associated `<label>` — either wrapping the input or linked via `for`/`id` — because the label is the input's accessible name *and* expands the click target. Group related controls (radio sets, checkbox groups) in a `<fieldset>` with a `<legend>` so screen readers announce the group's purpose. Use the **right input `type`** (`email`, `tel`, `url`, `number`, `date`, `search`) because it triggers the correct mobile keyboard, gives free format validation, and conveys semantics; pair with `autocomplete` tokens (`autocomplete="email"`, `"current-password"`, `"one-time-code"`) so browsers and password managers autofill correctly — `autocomplete` is an accessibility and UX win, not just convenience.

```html
<form>
  <label for="email">Email</label>
  <input id="email" name="email" type="email" required
         autocomplete="email" aria-describedby="email-hint" />
  <p id="email-hint">We'll never share it.</p>

  <fieldset>
    <legend>Plan</legend>
    <label><input type="radio" name="plan" value="free" required> Free</label>
    <label><input type="radio" name="plan" value="pro"> Pro</label>
  </fieldset>

  <button type="submit">Sign up</button>
</form>

<style>
  /* Style validity WITHOUT punishing users before they've interacted */
  input:user-invalid { border-color: crimson; }   /* only after blur/submit */
  input:user-valid   { border-color: seagreen; }
</style>
```

Native **constraint validation** replaces a lot of JavaScript: `required`, `min`/`max`/`step`, `minlength`/`maxlength`, `pattern` (regex), and type-based checks run automatically on submit, blocking invalid submission and showing a built-in bubble. The CSS pseudo-classes `:required`, `:valid`, `:invalid`, and — crucially — `:user-invalid`/`:user-valid` let you style state. The `:user-invalid` distinction matters for UX: plain `:invalid` matches an empty required field *immediately on load*, so a freshly opened form lights up red before the user types anything; `:user-invalid` only matches *after* the user has interacted (blurred or submitted), which is the humane behavior. For custom messages you use the Constraint Validation API (`setCustomValidity`) in JS, but the validation *logic* stays declarative.

Where JavaScript is still needed: cross-field rules ("password confirmation matches"), async validation (username availability), and accessible live error summaries. The accessible pattern for errors is to associate the message with the field via `aria-describedby`, mark the field `aria-invalid="true"`, move focus to the first error on failed submit, and for a summary use a focusable error region. The anti-patterns I watch for: placeholder-as-label (placeholders vanish on input and have poor contrast — never a substitute for `<label>`), disabling the submit button until valid (confusing — users can't tell *why* it's disabled; better to let them submit and show errors), and validating aggressively on every keystroke. Native-first means less code, better mobile keyboards, and password-manager compatibility for free.

### 🟠 Advanced — extended (continued)

#### Q48. [Theory] Explain CSS gradients, `color-mix()`, and the move to OKLCH — why does color space matter for UI?

CSS gradients (`linear-gradient`, `radial-gradient`, `conic-gradient`) interpolate between color stops, and the *color space* that interpolation happens in dramatically affects the result. The historical default is sRGB, which is **not perceptually uniform**: interpolating between two vivid colors in sRGB often passes through a muddy, desaturated gray middle (the classic "ugly gradient" between blue and yellow going through gray). This happens because sRGB's numeric midpoint doesn't correspond to the *perceptual* midpoint. The fix is interpolating in a perceptually-oriented space, which CSS now lets you specify: `linear-gradient(in oklch, blue, yellow)` produces a vivid, even transition because OKLCH spaces colors the way human vision perceives them.

```css
/* sRGB interpolation can pass through gray; oklch stays vivid and even */
.bar { background: linear-gradient(in oklch, oklch(70% 0.2 250), oklch(85% 0.18 95)); }

/* color-mix: blend two colors at runtime — great for tints/shades from tokens */
:root { --brand: oklch(60% 0.18 260); }
.btn:hover  { background: color-mix(in oklch, var(--brand), white 15%); } /* lighten */
.btn:active { background: color-mix(in oklch, var(--brand), black 12%); } /* darken */
```

**OKLCH** (Lightness, Chroma, Hue) matters for UI systems for several concrete reasons. First, **predictable lightness**: the L channel maps to perceived brightness, so you can generate a tint/shade scale (50→900) by varying only L and get *visually even* steps — something impossible to do reliably by hand-picking hex values. Second, **consistent contrast**: because L is perceptual, two colors with the same L look equally bright, which makes building accessible, contrast-stable palettes far easier than juggling sRGB. Third, **wide gamut**: OKLCH can express colors outside sRGB (for P3 displays) that hex literally can't represent. Fourth, **intuitive manipulation**: "same color, slightly lighter" is just a bump to L, and "shift hue" doesn't unexpectedly change perceived brightness the way HSL does.

`color-mix()` is the runtime companion: it blends two colors in a chosen space (`color-mix(in oklch, A, B 30%)`), which lets a design system derive hover/active/disabled states, surface tints, and semantic colors *from a single token* rather than hand-defining every shade — and because it's runtime CSS, it responds to theme variable changes for free. The trade-offs: these need Baseline-level support (solid by 2025–2026) and a fallback for very old engines (provide an sRGB hex fallback before the `color-mix`/oklch line so unsupported browsers degrade gracefully). The mental-model shift is treating color as a *perceptual, computed* system rather than a list of hardcoded hex strings — which is what makes large, themeable, accessible palettes maintainable.

#### Q49. [Practical] How do you optimize web font loading to avoid invisible text, layout shift, and slow LCP?

Web fonts sit on the critical path for text rendering, and mishandling them causes two visible failures: **FOIT** (Flash of Invisible Text — text is blank while the font downloads, hurting perceived load and LCP because the largest text element can't paint) and **FOUT** (Flash of Unstyled Text — fallback shows then swaps, causing a reflow/layout shift if the fonts have different metrics). The orchestration knob is `font-display` in `@font-face`: `block` (short invisible period then swap — FOIT risk), `swap` (show fallback immediately, swap when ready — no invisible text but FOUT/CLS risk), `fallback` and `optional` (small block period, and `optional` won't swap at all if the font is slow, prioritizing stability). For body text I usually use `swap` plus metric matching; for non-critical fonts `optional` avoids both the invisible text and the shift.

```css
@font-face {
  font-family: "Inter";
  src: url("/fonts/inter.woff2") format("woff2");  /* woff2 only — smallest */
  font-display: swap;
  /* Match the fallback's metrics so the swap doesn't reflow (kills CLS) */
  size-adjust: 105%;
  ascent-override: 90%;
  descent-override: 22%;
  line-gap-override: 0%;
}
body { font-family: "Inter", "Inter-fallback", system-ui, sans-serif; }
```

```html
<!-- Preload the critical font so it starts downloading with the HTML, not after CSS -->
<link rel="preload" href="/fonts/inter.woff2" as="font" type="font/woff2" crossorigin>
```

The highest-leverage techniques: **(1) `woff2` only** — it's the most compressed format and universally supported now, so shipping `ttf`/`woff`/`eot` fallbacks is dead weight. **(2) Preload** the one or two fonts used in above-the-fold/LCP text so the browser fetches them in parallel with CSS instead of discovering them after the CSSOM is built (don't preload everything — preloading deprioritizes other resources). **(3) Subset** fonts to the characters/scripts you actually use (`unicode-range` lets the browser download only the subset a page needs), which can shrink a font from 200KB to 20KB. **(4) Metric override** — `size-adjust`, `ascent-override`, `descent-override`, `line-gap-override` tune the *fallback* font to occupy the same space as the web font, so when `swap` happens there's no reflow; this is the modern way to get `swap`'s fast text without `swap`'s layout shift, and it's what drives CLS to zero. **(5) Self-host** rather than third-party font CDNs to avoid an extra DNS/TLS handshake and the (now defunct for privacy reasons) shared-cache myth.

The judgment call is per-font: LCP-critical headline font → preload + `swap` + metric override; decorative/optional font → `optional` so a slow network never causes a shift or invisible text. I measure with LCP and CLS in field data and watch the "Ensure text remains visible during webfont load" Lighthouse audit. The variable-fonts angle is worth mentioning too: one variable font file can replace many static weights/styles, cutting requests and bytes dramatically for type-rich designs.

#### Q50. [Theory] What is a Block Formatting Context (BFC), how is one created, and what real problems does it solve?

A Block Formatting Context is a self-contained region of the page where block-level boxes are laid out according to a consistent set of rules, *isolated* from the outside. The key behaviors inside a BFC: block boxes stack vertically, **floats are contained** within it, **margins don't collapse** across its boundary, and its layout doesn't interfere with content outside it. The root element establishes the top-level BFC, and you create a *new* one with several triggers: `overflow` other than `visible` (`hidden`/`auto`/`scroll`), `display: flow-root` (the modern, purpose-built trigger with no side effects), `position: absolute/fixed`, `display: inline-block`/`table-cell`/`flex`/`grid` (these establish their own formatting contexts), or floats themselves.

The reason this concept earns its keep is that it solves three classic, otherwise-baffling layout problems. **(1) Containing floats (clearfix):** a parent with only floated children collapses to zero height because floats are removed from normal flow. Establishing a BFC on the parent forces it to contain its floats and grow to their height — `display: flow-root` is the clean one-property fix that replaced the `::after { clear: both }` clearfix hack. **(2) Preventing margin collapse:** as covered earlier, a child's margin can escape its parent; making the parent a BFC stops the collapse. **(3) Preventing text wrap around floats:** normally text flows around a float, but a sibling that establishes its own BFC will *not* overlap the float — it shrinks to fill the remaining space, which is exactly how you build a "media object" with a floated image and a text column that stays in its own box.

```css
/* Float containment: parent collapses without a BFC */
.cf { display: flow-root; }      /* contains floated children, no hack needed */

/* Two-column with float + BFC sibling: text won't wrap under the image */
.img  { float: left; width: 120px; }
.body { display: flow-root; }    /* establishes BFC → sits beside, doesn't wrap under */
```

The expert nuance is *which trigger to choose*, because they have side effects. `overflow: hidden` creates a BFC but also clips overflowing content (and breaks `position: sticky` on descendants and tooltips that need to overflow) — using it *just* to contain floats is a common accidental-clipping bug. `display: flow-root` exists precisely to create a BFC with **no other effects**, so it's the correct modern choice. That said, in 2026 you rarely *need* BFCs for layout because Flexbox and Grid (which establish their own formatting contexts and never collapse item margins or need clearfix) have made float-based layout obsolete — so I treat BFC primarily as *explanatory knowledge* for debugging legacy layouts and the occasional margin-collapse or float-containment situation, not as a daily tool.

### 🔴 Expert — extended (continued)

#### Q51. [Practical] An animation is janky on mobile but smooth on desktop. How do you diagnose and fix it, and what is layer promotion?

I'd reproduce on a representative low-end device (or throttle CPU 4–6× and use mobile emulation, but real hardware is the source of truth because GPU/memory limits differ) and profile in the Performance panel, looking specifically at the frame timeline. The diagnosis hinges on *which thread* the jank is on. If I see long **"Layout" and "Paint"** bars on the main thread per frame, the animation is animating a property that triggers reflow/repaint (`width`, `height`, `top`, `left`, `margin`, `box-shadow`) — every frame the main thread has to recompute geometry/pixels, and on a weak mobile CPU it can't finish within the ~16ms budget, so frames drop. If the main thread is busy with *unrelated* JavaScript (a scroll handler, React reconciliation), the animation starves even if it's cheap.

The primary fix is **animating only compositor-friendly properties: `transform` and `opacity`**. These can be handled entirely by the compositor thread on the GPU, skipping layout and paint, so they keep running smoothly even when the main thread is blocked. Converting `top: 0 → 100px` into `transform: translateY(100px)`, or animating `width` into a `transform: scaleX()`, moves the work off the critical path. **Layer promotion** is the related concept: the browser can promote an element to its own **compositor layer** (a separate GPU texture) so animating it doesn't force re-rasterizing its surroundings. Certain properties auto-promote (an animating `transform`/`opacity`, `position: fixed`, `<video>`, 3D transforms), and you can hint promotion explicitly with `will-change: transform` (or the legacy `transform: translateZ(0)` hack).

```css
/* Janky: animates layout-triggering properties on a weak GPU/CPU */
.box { transition: top 300ms, width 300ms; }

/* Smooth: compositor-only properties; promote to its own layer during animation */
.box {
  transition: transform 300ms ease;
  will-change: transform;     /* hint: promote to a GPU layer */
}
.box.open { transform: translateY(100px) scaleX(1.1); }
```

The critical caveat — and the reason this is an expert question — is that **`will-change` is not free and is widely misused**. Each promoted layer consumes GPU memory, and on memory-constrained mobile devices, promoting too many elements (or slapping `will-change: transform` on everything "to be safe") can *cause* jank or even crashes by exhausting GPU memory and forcing constant layer re-management. The discipline is: promote *only* the element actually animating, *only* while it's animating (add `will-change` on hover/before the animation, remove it after — or let the browser auto-promote during a `transition`/`animation` and avoid the manual hint entirely), and never leave it on statically. Other mobile-specific levers: reduce paint area (large `box-shadow`/`filter`/`border-radius` are expensive to rasterize per frame), avoid animating `filter`/`backdrop-filter` (paint-heavy), use `contain`/`content-visibility` to bound work, and ensure the main thread is free during the animation (defer non-urgent JS). I confirm the fix by re-profiling and checking the frame rate holds 60fps (or the device's refresh rate) on the low-end device — green frames in the timeline, no long purple/green bars per frame.

#### Q52. [Theory] Discuss subgrid and the limits of nested grids — what problem does it solve that plain Grid can't?

The problem subgrid solves is **alignment across grid boundaries**. With plain CSS Grid, each grid is an independent coordinate system: if you have a parent grid of cards and each card is itself a grid (image / title / description / footer), the *internal* rows of card A have no relationship to the internal rows of card B. So if one card's title wraps to two lines, its description starts lower than its neighbor's, and the footers don't line up across the row — a ragged, unprofessional look. Before subgrid you either accepted the misalignment, forced fixed heights (brittle, clips content), or pulled the children up into one giant grid and lost component encapsulation.

**Subgrid** lets a nested grid *opt into its parent's track definitions* on an axis: `grid-template-rows: subgrid` (or `grid-template-columns: subgrid`) means "don't define my own tracks on this axis — inherit the parent grid's lines for the area I span." Now every card shares the *same* row lines, so all titles align, all descriptions align, and all footers sit on a common baseline across the entire row, regardless of how much content each card holds — while each card remains a self-contained component.

```css
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  /* Define the shared internal rows ONCE at the parent */
  grid-template-rows: auto auto 1fr auto; /* image, title, body(grow), footer */
}
.card {
  display: grid;
  grid-row: span 4;                /* occupy all four parent rows */
  grid-template-rows: subgrid;     /* adopt the parent's row lines */
}
/* Result: titles/bodies/footers align across ALL cards in the row */
```

The limits and nuances worth articulating: subgrid is **per-axis** — you can subgrid rows, columns, or both independently, which is powerful (a card can inherit the parent's columns for label/value alignment while defining its own rows). The subgrid element must actually *span* the parent tracks it wants to share. `gap` can be inherited or overridden. Named lines and areas pass through, which keeps `grid-template-areas` legibility across levels. The constraint people hit: subgrid only relates to its *direct* grid parent's tracks, not arbitrary ancestors, so deeply nested alignment still needs thought. Browser support reached Baseline in 2023–2024 (Safari was last), so by 2026 it's production-ready, but I still provide a graceful fallback (the cards just align less perfectly without it) via `@supports (grid-template-rows: subgrid)` for the long tail. The bigger picture: subgrid is the piece that finally made *component-based* design fully compatible with *global* grid alignment — you no longer trade encapsulation for alignment, which was a real architectural tension in design systems.

#### Q53. [Behavioral] A designer and an engineer disagree on whether to match a pixel-perfect mockup that conflicts with accessibility (low contrast, tiny tap targets). How do you resolve it?

I treat this as a values-alignment problem, not a turf war, and I start by reframing it away from "design vs. engineering" toward "what serves users and the business." The first move is to **make the conflict concrete and non-abstract**: run the mockup through a contrast checker and show the actual numbers (e.g. "this gray-on-white is 2.8:1; WCAG AA requires 4.5:1 for body text"), and measure the tap targets against the 24×24 / 44×44px guidance. Turning "I think this is inaccessible" into "this fails a specific, named standard by a specific margin" depersonalizes it and gives the designer something objective to respond to, rather than my opinion against theirs.

Then I'd **establish the stakes and constraints we both operate under**, because designers often don't have visibility into them: accessibility isn't a preference, it's frequently a *legal requirement* (ADA in the US, the European Accessibility Act effective 2025, AODA, etc.) with real litigation and financial risk; it directly affects a measurable share of users (color vision deficiency ~8% of men, plus aging users, plus situational impairments like sunlight glare); and contrast/tap-target failures hurt conversion for *everyone*, not just disabled users. Framing it as "this protects the business and improves metrics for all users" usually shifts the designer from feeling overruled to being a partner in solving it.

Crucially, I'd **respect the designer's intent and collaborate on alternatives** rather than just vetoing. The designer wants a clean, light aesthetic — that's legitimate, and accessibility rarely requires abandoning it. I'd propose options that satisfy both: nudge the text color a few steps darker (often the contrast can be fixed with a change invisible to the casual eye), increase the tap target's *hit area* with padding while keeping the *visual* element small, or use a slightly heavier font weight (large/bold text has a lower contrast threshold). The goal is to find the version that is *both* on-brand and compliant, which almost always exists — pixel-perfect fidelity to a flawed mockup is a false constraint when the mockup itself can be corrected.

If we still can't agree, I'd **escalate to the shared standard, not to authority**: "we have an accessibility policy / Definition of Done that requires WCAG AA — let's check this against it" moves the decision from personalities to an agreed rule. The systemic fix, which I'd push for after resolving the immediate case, is to **shift accessibility left into the design process**: give designers contrast-checking plugins (Figma has them), bake accessible color/spacing tokens into the design system so non-compliant combinations aren't even available in the palette, and make accessibility part of design review — so this disagreement stops recurring per-feature. The behavioral signal I want to send: I'm not the accessibility police blocking design; I'm a collaborator who's accountable for users we can't see and risks the designer may not be measured on, and the best outcome is one neither of us has to compromise on because we caught it in the tooling.

#### Q54. [Practical] How do you set up an effective CSS regression and quality testing pipeline in CI for a large frontend team?

CSS fails in ways unit tests can't catch — a one-line specificity change can shift a button three pixels on one breakpoint in one browser — so the pipeline has to *see* the rendered output, not just parse the source. I'd build it in layers, cheapest-and-fastest first, so most defects are caught before the expensive visual stage. **Layer 1 — static analysis (stylelint):** runs in milliseconds on every commit, enforces the architectural rules from our design-system decisions: `selector-max-id: 0`, `declaration-no-important` (scoped to non-legacy layers), enforce logical properties over physical, no duplicate properties, consistent token usage (custom properties instead of raw hex). This catches *policy* violations and stops specificity wars from re-emerging.

**Layer 2 — accessibility checks:** `axe-core` (via Playwright or jest-axe) and Lighthouse-CI run against rendered key pages and component stories, failing the build on contrast violations, missing labels, focus-order problems, and ARIA misuse. Because accessibility regressions are silent and legally risky, gating them in CI is the highest-leverage automation — it converts a "remember to check" discipline into a hard gate. **Layer 3 — visual regression:** this is the core CSS safety net. Tools like Playwright's `toHaveScreenshot`, Percy, Chromatic (for Storybook), or BackstopJS render components/pages and diff screenshots against approved baselines across **multiple viewports and multiple browser engines** (Chromium, WebKit, Firefox — WebKit coverage is what catches the Safari-only bugs). A diff above a threshold fails the PR and surfaces a side-by-side image for human approval.

```
CI pipeline (fail fast, cheap → expensive):
 commit ─► stylelint (ms)            ─► policy: no !important/IDs, tokens, logical props
        ─► axe-core / Lighthouse-CI  ─► a11y: contrast, labels, focus, ARIA
        ─► visual regression          ─► Chromium + WebKit + Firefox × breakpoints
        ─► perf budget (bundlesize)   ─► CSS transfer size & render-blocking caps
                     │
            any gate red → block merge; visual diffs need human approve/reject
```

**Layer 4 — performance budgets:** bundlesize/Lighthouse-CI assertions on CSS transfer size and render-blocking metrics so the bundle and FCP can't silently regress (tying back to the bundle-reduction plan). The operational details that make or break adoption: visual regression must run on **deterministic renders** — pin fonts (or wait for `document.fonts.ready`), disable animations/caret blink, freeze dates/`Math.random`, and mask volatile regions (timestamps, avatars) — otherwise flaky diffs erode trust and people start rubber-stamping approvals, which defeats the whole system. Baselines must be reviewable and updatable through a clear workflow (a reviewer *approves* intended visual changes, turning the diff into the new baseline). I'd run the full visual/browser matrix on PRs to changed surfaces and nightly across everything to manage cost. The cultural point I'd emphasize: the pipeline's job is to make the *correct* outcome automatic and the *regression* loud, so engineers can move fast without a senior reviewer eyeballing every pixel — that's how CSS quality scales past the point where any one person can review it all.

## 🧩 Extended Questions — Set 2: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q55. [Coding] Build a responsive navbar with a logo on the left and links pushed to the right, that collapses link spacing gracefully.

**Problem:** A single-row header: brand on the left, a set of nav links on the right, vertically centered, with consistent spacing that doesn't break when the link text varies. No JavaScript.

```html
<style>
  .nav {
    display: flex;
    align-items: center;            /* vertical centering of all items */
    gap: 1rem;
    padding: 0.75rem 1.25rem;
  }
  .nav__brand { font-weight: 700; font-size: 1.25rem; }
  .nav__links {
    margin-inline-start: auto;      /* the magic: push links to the far end */
    display: flex;
    gap: 1.25rem;                   /* even spacing, never collapses */
    list-style: none;
    padding: 0;
  }
  .nav__links a { text-decoration: none; padding-block: 0.25rem; }
</style>

<nav class="nav">
  <a class="nav__brand" href="/">Acme</a>
  <ul class="nav__links">
    <li><a href="/features">Features</a></li>
    <li><a href="/pricing">Pricing</a></li>
    <li><a href="/docs">Docs</a></li>
  </ul>
</nav>
```

The load-bearing trick is `margin-inline-start: auto` on the links group. In a flex container, an `auto` margin absorbs *all* the free space on that side, so the brand stays pinned left and the links cluster right — this is the modern replacement for `float: right` and `justify-content: space-between` when you have exactly two groups. Using the *logical* `margin-inline-start` instead of `margin-left` means it automatically flips for RTL languages, so an Arabic version pushes links to the left without a separate stylesheet.

I deliberately used `<nav>` and a real `<ul>` of `<a>` elements rather than styled `<div>`s, because the navigation landmark and the link list are semantics screen readers rely on. `gap` handles spacing between links without margin-collapse surprises or trailing-margin hacks, and `padding-block` on the anchors enlarges the keyboard/tap target without affecting horizontal flow. **Time/Space:** O(1) layout, zero JS. **Edge case:** on very small screens the links can overflow; the next step is a `flex-wrap: wrap` fallback or a `@media`/container-query hamburger toggle, but for the core "logo left, links right" requirement the auto-margin is all you need.

#### Q56. [Theory] What's the difference between `visibility: hidden`, `display: none`, and `opacity: 0` — for layout, accessibility, and events?

These three all "hide" an element but differ on three axes that matter: whether the box still occupies space, whether it's reachable by assistive tech and keyboard, and whether it receives pointer events. `display: none` removes the element from the render tree entirely — it takes up **no space**, is **not announced** by screen readers, is **not focusable**, and receives **no events**. It's the strongest hide, but toggling it back triggers layout and it can't be transitioned (you can't animate to/from `display: none` directly, though the newer `transition-behavior: allow-discrete` and `@starting-style` finally make show/hide transitions possible).

`visibility: hidden` keeps the element's **box in the layout** (it still reserves space, so siblings don't reflow), but the element is invisible, **not announced** by screen readers, **not focusable**, and **doesn't receive events**. It's the right choice when you want to hide something without collapsing the layout around it — e.g. swapping content of equal size. Uniquely, a child can be made visible again with `visibility: visible` even while the parent is `hidden`.

`opacity: 0` is the weakest hide: the element is **fully present in layout**, **still announced** by screen readers, **still focusable**, and — the dangerous part — **still receives pointer events**, so an invisible `opacity: 0` button can be clicked, creating "ghost click" bugs and accessibility traps. The upside is `opacity` is GPU-composited and freely animatable, which is why fade transitions use it. The correct accessible-hide pattern depends on intent:

```css
/* Visually hidden but available to screen readers (skip-links, labels) */
.visually-hidden {
  position: absolute; width: 1px; height: 1px;
  padding: 0; margin: -1px; overflow: hidden;
  clip-path: inset(50%); white-space: nowrap; border: 0;
}
/* Hide from everyone, keep layout */     .a { visibility: hidden; }
/* Hide from everyone, remove from flow */ .b { display: none; }
/* Hide visually + from a11y tree, but it still takes space & gets events */
.c { opacity: 0; pointer-events: none; }  /* add aria-hidden to remove from a11y */
```

The interview-level insight is that "hidden" is three independent concerns — *visual*, *layout*, and *accessibility/interaction* — and these properties each toggle a different combination. Getting it wrong produces the two classic bugs: content hidden visually with `opacity`/`visually-hidden` but still clickable/focusable when it shouldn't be (add `pointer-events: none` and possibly `aria-hidden`), or content meant to be available to screen readers that you accidentally `display: none`'d (now it's gone for everyone). For a true accessible-but-invisible label, the `.visually-hidden` clip pattern is the canonical answer — never use `display: none` for that.

### 🟡 Intermediate — extended

#### Q57. [Coding] Implement a CSS-only tooltip that appears on hover and focus, positioned above the trigger.

**Problem:** A tooltip that shows on both `:hover` and `:focus-visible` (keyboard users matter), is positioned above its trigger, and is accessible — without JavaScript.

```html
<style>
  .tip { position: relative; }
  .tip__bubble {
    position: absolute;
    bottom: calc(100% + 8px);        /* above the trigger, 8px gap */
    left: 50%;
    transform: translateX(-50%);     /* horizontally center on trigger */
    background: #222; color: #fff;
    padding: 0.4rem 0.6rem; border-radius: 6px;
    white-space: nowrap; font-size: 0.85rem;
    opacity: 0; visibility: hidden;
    transition: opacity 120ms ease, visibility 0s linear 120ms;
  }
  .tip__bubble::after {                /* little arrow */
    content: ""; position: absolute; top: 100%; left: 50%;
    transform: translateX(-50%);
    border: 6px solid transparent; border-top-color: #222;
  }
  /* Show on hover OR keyboard focus of the trigger */
  .tip:hover .tip__bubble,
  .tip:focus-visible .tip__bubble {
    opacity: 1; visibility: visible;
    transition: opacity 120ms ease;    /* no visibility delay on show */
  }
</style>

<button class="tip" aria-describedby="tip1">
  Help
  <span class="tip__bubble" role="tooltip" id="tip1">Saves your progress</span>
</button>
```

The two correctness details interviewers look for are *focus* and *announcement*. Showing the tooltip only on `:hover` excludes keyboard and many touch users; pairing it with `:focus-visible` on the trigger means tabbing to the button reveals it too. The `aria-describedby` linking the button to the tooltip's `id` (and `role="tooltip"`) is what makes a screen reader announce the hint as the button's description — visual-only tooltips are inaccessible. The `visibility` in the transition (delayed on hide, immediate on show) lets the fade animate *and* keeps the bubble unfocusable/uninteractive while hidden, which `opacity: 0` alone wouldn't.

**Edge cases and limits:** a pure-CSS tooltip can't reposition itself when it would overflow the viewport edge (a real JS tooltip flips above/below) — the modern fix is **CSS anchor positioning** with `position-try` fallbacks, or the Popover API for dismissible hint popovers. WCAG 1.4.13 ("Content on Hover or Focus") also requires the tooltip be *dismissible* (Escape), *hoverable* (you can move the pointer onto it without it vanishing), and *persistent* — a CSS-only version satisfies hoverable/persistent but not Escape-to-dismiss, so for production-critical tooltips I add a small JS layer. **Time/Space:** O(1), GPU-composited fade.

#### Q58. [Coding] Style a checkbox/radio as a custom toggle while keeping the native input accessible.

**Problem:** Native checkboxes are nearly unstyleable historically, but you must not lose keyboard operability, focus, or screen-reader semantics. Build a custom-looking toggle that's still a real `<input>`.

```html
<style>
  .switch { display: inline-flex; align-items: center; gap: 0.5rem; cursor: pointer; }
  /* Hide the native box visually but keep it in the a11y tree & focusable */
  .switch input {
    position: absolute; opacity: 0; width: 0; height: 0;
  }
  .switch__track {
    width: 44px; height: 24px; border-radius: 999px;
    background: #ccc; position: relative; transition: background 150ms;
  }
  .switch__track::after {                 /* the knob */
    content: ""; position: absolute; top: 2px; inset-inline-start: 2px;
    width: 20px; height: 20px; border-radius: 50%;
    background: #fff; transition: transform 150ms;
  }
  /* Drive visuals from the REAL input's state */
  .switch input:checked + .switch__track { background: seagreen; }
  .switch input:checked + .switch__track::after { transform: translateX(20px); }
  /* Keyboard focus must be visible — :focus-visible on the hidden input */
  .switch input:focus-visible + .switch__track {
    outline: 2px solid #0a7; outline-offset: 2px;
  }
</style>

<label class="switch">
  <input type="checkbox" role="switch" />
  <span class="switch__track" aria-hidden="true"></span>
  <span>Email notifications</span>
</label>
```

The principle is **keep the real input, hide it visually, draw the toggle as a sibling, and drive the visuals from the input's `:checked` state via the adjacent-sibling combinator** (`input:checked + .switch__track`). Because the actual `<input>` is still there (just visually clipped, not `display: none` which would remove it from the tab order in some engines and break form submission), it retains native keyboard toggling (Space), form participation, and screen-reader semantics. Wrapping everything in a `<label>` means clicking the visual track toggles the input and the label text is the accessible name.

The accessibility-critical pieces: I hide the input with `opacity: 0` + zero size rather than `display: none`/`visibility: hidden` (both of which remove it from the accessibility tree and tab order), I add `role="switch"` so it's announced as a toggle rather than a checkbox, I mark the purely-decorative track `aria-hidden="true"`, and I surface keyboard focus with `:focus-visible` on the input projected onto the track — *never remove the focus indicator without replacing it*. As of 2025–2026 you can increasingly style the native control directly with `accent-color` (one line to recolor a checkbox/radio) and the experimental `appearance: base` / `::checkbox` pseudo-elements, but the sibling-driven custom pattern remains the most controllable and widely-supported approach. **Edge case:** for a tri-state checkbox use the `:indeterminate` pseudo-class (set via JS) to style the third visual state.

#### Q59. [Theory] How does `z-index` interact with Flexbox and Grid items, and what's the `order` property's accessibility trap?

A subtle, commonly-missed fact: **flex and grid items can use `z-index` even without being positioned**. Normally `z-index` only applies to positioned elements (`position` other than `static`), but the flexbox and grid specs explicitly allow `z-index` on items to control their paint order and stacking. Setting `z-index` on a flex/grid item also creates a **stacking context** for it — which means it can trap descendants the same way a positioned `z-index` element does, a gotcha when an item's child modal suddenly renders behind a sibling item. This is why you sometimes see `z-index` "work" on a flex child that has no `position` and are surprised.

The bigger trap is the **`order` property** (and `flex-direction: row-reverse`/`column-reverse`, and grid item placement). These let you visually reorder items independently of the DOM, which is tempting for responsive layouts ("on mobile, move the sidebar below the content"). The catastrophe is that `order` changes only the **visual** order — it does **not** change the **DOM/source order**, which is what determines **keyboard tab order** and **screen-reader reading order**. So a keyboard user tabbing through the page jumps around in a way that doesn't match what they see, and a screen reader reads the original source sequence. WCAG 2.4.3 (Focus Order) and 1.3.2 (Meaningful Sequence) are violated.

```css
/* DANGER: visual order no longer matches tab/reading order */
.toolbar { display: flex; }
.toolbar .primary  { order: 2; }   /* looks last, but tabbed FIRST */
.toolbar .secondary{ order: 1; }   /* looks first, but tabbed SECOND */
/* A keyboard user tabs primary → secondary while seeing secondary → primary */
```

The rule I follow: use `order`/`row-reverse`/grid placement **only for purely visual, non-interactive rearrangement, or when the visual change is small enough that the mismatch is harmless** — and *never* to reorder interactive controls in a way that diverges from logical flow. If the design genuinely needs a different order on mobile vs desktop, the correct fix is to change the **DOM order** (e.g. server-render or restructure markup) so source order matches both, or to accept that the source order should reflect the *most important* sequence and design the visual layout around it. The "center column first in the DOM, rendered in the middle via grid placement" pattern from the Holy Grail layout is the *safe* version — it moves a non-focus-trapping content region, and crucially the tab order (header → main content → nav → aside → footer) is actually a *reasonable* order. The discipline is to always tab through the page after using `order` and confirm the focus path still makes sense.

### 🟠 Advanced — extended

#### Q60. [Coding] Build a sticky table header that stays visible while the table body scrolls, with the first column also frozen.

**Problem:** A data table where the header row stays pinned to the top during vertical scroll *and* the first column stays pinned during horizontal scroll (a "frozen header + frozen first column" grid), CSS-only.

```html
<style>
  .table-wrap { max-height: 400px; overflow: auto; }   /* the scroll container */
  table { border-collapse: separate; border-spacing: 0; width: 100%; }
  th, td { padding: 0.5rem 0.75rem; border-bottom: 1px solid #ddd; background: #fff; }

  /* Pin the header row vertically */
  thead th { position: sticky; top: 0; z-index: 2; background: #f5f5f5; }

  /* Pin the first column horizontally */
  th:first-child, td:first-child {
    position: sticky; inset-inline-start: 0; z-index: 1; background: #fff;
  }
  /* The top-left corner must outrank both, so it stays on top of each */
  thead th:first-child { z-index: 3; background: #ececec; }
</style>

<div class="table-wrap">
  <table>
    <thead><tr><th>Name</th><th>Q1</th><th>Q2</th><th>Q3</th><th>Q4</th></tr></thead>
    <tbody>
      <tr><th>North</th><td>10</td><td>12</td><td>9</td><td>15</td></tr>
      <!-- many rows … -->
    </tbody>
  </table>
</div>
```

The core mechanism is `position: sticky` resolved *relative to the nearest scrolling ancestor* — here the `.table-wrap` with `overflow: auto`. The header cells stick to `top: 0` of that container, and the first-column cells stick to `inset-inline-start: 0`. The non-obvious requirements that make or break this: **(1)** sticky needs an explicit `background` on the cells, otherwise rows scroll *through* the pinned header/column and you see a transparent overlap. **(2)** `border-collapse: separate` is required — with the default `collapse`, borders are owned by the table and sticky cells lose their borders when they detach; people work around it with `box-shadow` insets instead of `border`. **(3)** the **z-index layering** is a three-tier stack: the frozen corner cell (top-left) must beat both the header row and the first column, so it gets the highest `z-index`, the header and column get middle values, and body cells stay at the default — get this wrong and the corner shows the wrong content during diagonal scroll.

**Edge cases and limits:** sticky silently fails if any ancestor between the cell and the scroll container has `overflow: hidden` (a frequent cause of "my sticky header doesn't stick"). Sticky also can't escape its element's parent, so the header sticks only while its `thead`/table is in view. For very large datasets this is fine for the *pinning* but you'd still pair it with virtualization (`content-visibility: auto` on rows, or a JS windowing library) for performance. **Time/Space:** O(1) for the pinning behavior, no JS, and it degrades to a normal scrollable table if `sticky` isn't supported.

#### Q61. [Coding] Implement a fully fluid type scale with `clamp()` and explain how to compute the middle term.

**Problem:** Typography should grow smoothly between a minimum size on small screens and a maximum on large screens — no stepped breakpoints — and respect user zoom. Build the scale and derive the `clamp()` math.

```css
:root {
  /* clamp(MIN, PREFERRED, MAX) — PREFERRED is a fluid value (vw + rem) */
  --step-0: clamp(1rem,    0.875rem + 0.5vw,  1.25rem);   /* body */
  --step-1: clamp(1.25rem, 1.06rem  + 0.95vw, 1.75rem);   /* h3   */
  --step-2: clamp(1.5rem,  1.1rem   + 2vw,    2.5rem);    /* h2   */
  --step-3: clamp(2rem,    1.3rem   + 3.5vw,  3.5rem);    /* h1   */
}
body { font-size: var(--step-0); }
h1 { font-size: var(--step-3); }  h2 { font-size: var(--step-2); }
```

`clamp(MIN, PREFERRED, MAX)` returns `PREFERRED` clamped to the `[MIN, MAX]` range. The whole trick is that the middle term must be a *fluid* expression — combining a viewport unit (`vw`) with a fixed unit (`rem`) — so the size scales with the viewport but with a controlled slope and a non-zero baseline. The derivation: you pick a min size at a min viewport and a max size at a max viewport, then solve the line `size = slope·vw + intercept` through those two points. For min 16px @ 320px viewport and max 24px @ 1280px: slope = (24−16)/(1280−320) = 0.00833px per px of viewport = **0.833vw**; intercept = 16 − 0.00833·320 = 13.33px = **0.833rem**. So `clamp(16px, 0.833rem + 0.833vw, 24px)` (you can keep px or convert to rem). Tools like Utopia automate this, but understanding the line is what lets you reason about it.

The accessibility nuance — and a frequent interview follow-up — is **why you should not use pure `vw`** for font sizing (`font-size: 5vw`). Pure viewport units **ignore the user's browser zoom/font-size preference** because they're tied to the viewport, not the root font size, which fails WCAG 1.4.4 (Resize Text). By including a `rem` component in the `clamp()` middle term (`0.833rem + 0.833vw`), the `rem` part *does* respond to user zoom, so the text still scales when a low-vision user increases their default font size — you keep fluidity *and* preserve user control. Some practitioners use `calc(... + ...)` inside clamp or convert the `vw` slope to be more conservative for the same reason.

**Edge cases:** clamp the **line-height** fluidly too (or use a unitless ratio so it tracks the font size), watch that the `MIN` is genuinely the smaller value (if MIN > MAX, clamp returns MIN — a silent bug), and remember `clamp()` works for any length — spacing, gaps, `max-width` — not just type, so a fluid spacing scale uses the same technique. **Time/Space:** O(1), recomputed by the engine on resize, zero JS, zero layout-shift breakpoint "snaps."

#### Q62. [Theory] Walk through how the browser builds and updates compositor layers, and how to read the Layers panel to debug overdraw.

After layout and paint, the browser runs **compositing**: it splits the page into one or more **layers** (GPU textures), rasterizes each, and the compositor thread assembles them into the final frame. Most content lives on a single base layer, but the browser promotes certain elements onto their own layers so they can be moved/faded independently without re-rasterizing everything beneath them. Promotion triggers include: animating `transform`/`opacity`, `will-change: transform/opacity`, 3D transforms (`translateZ`, `translate3d`), `<video>`/`<canvas>`/`<iframe>`, `position: fixed` (in some engines), and elements with certain `filter`/`backdrop-filter`/`mix-blend-mode`. The payoff is that animating a promoted layer's transform is a cheap compositor-thread operation — no layout, no paint — which is why `transform`/`opacity` animations stay at 60fps even under main-thread load.

```
Pipeline:   Style → Layout → Paint → Composite
                                       │
                  ┌────────────────────┼─────────────────────┐
            base layer            promoted layer A      promoted layer B
          (most content)         (animating modal)     (fixed header)
                  └──────── compositor thread assembles → frame ────────┘
Each layer = a GPU texture. Too many/too large = GPU memory pressure → jank.
```

To debug, I open DevTools' **Layers panel** (and the "Rendering" tab with "Layer borders" and "Paint flashing" overlays). Layer borders draw an orange/blue outline around every composited layer, so I can immediately *see* how many layers exist and how big they are — the failure mode is **layer explosion** (hundreds of unexpected layers from an over-broad `will-change` or many `translateZ(0)` hacks), which exhausts GPU memory and causes the very jank people were trying to prevent. The Layers panel shows each layer's memory size and *why it was composited* ("compositing reasons"), which is how you find an accidental promotion. **Paint flashing** highlights regions being repainted green on each frame — if large areas flash during an animation, you're triggering paint (not just composite), meaning you're animating the wrong property or a promoted layer is being invalidated every frame (**overdraw**).

The expert workflow: confirm the animation is composite-only (no green paint flashes, no purple layout bars in the Performance panel), check the Layers panel for *just the layers you intend* (the animating element, not its whole subtree), watch total layer memory on mobile-class budgets, and remove `will-change` once the animation ends so the layer is destroyed and its memory reclaimed. **Overdraw** specifically means the GPU is filling the same pixels multiple times because of stacked translucent/overlapping layers — reducing it means flattening unnecessary layers, avoiding huge translucent overlays, and not promoting elements that don't actually animate. The meta-point: layers are a *budget*, not a free speedup — promotion trades main-thread paint cost for GPU memory, and the art is promoting exactly what's animating and nothing else.

#### Q63. [Coding] Create a print stylesheet that produces a clean, paginated document from a web page.

**Problem:** The same HTML should render as a usable printed/PDF document — hiding chrome, controlling page breaks, expanding hidden link URLs, and avoiding wasted ink. Show a real print stylesheet.

```css
@media print {
  /* 1. Strip interactive chrome and non-content */
  nav, .sidebar, .cookie-banner, .ad, button, video { display: none !important; }

  /* 2. Reset to ink-friendly defaults: black text, white bg, no shadows */
  *, *::before, *::after {
    background: #fff !important; color: #000 !important;
    box-shadow: none !important; text-shadow: none !important;
  }

  /* 3. Page setup: margins and size via the @page rule */
  @page { margin: 2cm; size: A4; }
  @page :first { margin-top: 4cm; }       /* room for a title on page 1 */

  /* 4. Control pagination — avoid orphaned/split content */
  h1, h2, h3 { break-after: avoid; }      /* don't end a page right after a heading */
  tr, img, figure, blockquote { break-inside: avoid; }  /* keep these whole */
  p { orphans: 3; widows: 3; }            /* min lines kept together across pages */
  .chapter { break-before: page; }        /* force each chapter onto a new page */

  /* 5. Expand link destinations so URLs aren't lost on paper */
  a[href^="http"]::after { content: " (" attr(href) ")"; font-size: 0.85em; }
  /* …but not for in-page anchors or javascript: links */
  a[href^="#"]::after, a[href^="javascript:"]::after { content: ""; }
}
```

A print stylesheet is a real, often-forgotten deliverable (invoices, receipts, reports, recipes, boarding passes), and it tests whether you know the **paged-media** corner of CSS. The five concerns above are the standard checklist: hide screen-only chrome, neutralize ink-wasting backgrounds/shadows (printers can't render a dark theme economically and many drop backgrounds by default anyway), set physical page geometry with `@page` (margins, `size: A4`/`letter`, and `:first`/`:left`/`:right` variants for asymmetric binding margins), control where pages break, and surface link URLs that would otherwise be invisible on paper.

The pagination properties are the technical meat and the modern names matter: the logical `break-before`/`break-after`/`break-inside` (with values `avoid`, `page`, `avoid-page`) supersede the older `page-break-*` properties and also work in multicol/regions. `break-inside: avoid` on a table row, figure, or card prevents it from being sliced across a page boundary; `break-after: avoid` on headings stops a heading from stranding at the bottom of a page with its content overleaf; `orphans`/`widows` set the minimum number of lines of a paragraph that may sit alone at the bottom/top of a page (typographic quality). **Edge cases:** browsers vary in print fidelity, so I test actual print preview / "Save as PDF"; backgrounds are dropped unless the user enables "Background graphics," so never rely on background color to convey meaning; and `@page` margin boxes for running headers/footers (`@top-center { content: ... }`) are well-supported in PDF renderers (Prince, Paged.js) but spotty in browsers, so for pixel-perfect paginated PDFs I'd use a dedicated paged-media engine rather than the browser. **Time/Space:** trivial; the value is correctness and not shipping an unusable printout.

#### Q64. [Theory] Explain CSS nesting (native `&`), how it differs from Sass nesting, and the specificity/parsing gotchas.

Native CSS nesting (baseline 2023–2024) lets you write child and related rules *inside* a parent rule, using `&` to reference the parent selector — removing one of the last big reasons teams reached for Sass. It reads like Sass but has important behavioral differences because it's resolved by the *browser* at parse/cascade time, not by a preprocessor doing string substitution.

```css
.card {
  padding: 1rem;
  border: 1px solid #ddd;

  & .title { font-weight: 700; }      /* .card .title */
  &:hover { border-color: #999; }     /* .card:hover  */

  & > .body { color: #333; }          /* direct child */

  @media (min-width: 600px) {         /* nest at-rules too */
    & { padding: 2rem; }
  }
}
```

The first gotcha is the **`&` and the implicit relationship**. In modern CSS nesting, a nested selector that *doesn't* start with `&` (or a symbol) is treated as a descendant — `.card { .title {} }` compiles to `.card .title`, same as Sass. But there's a key difference from Sass: native nesting historically required the nested selector to begin with a symbol or be wrapped, and even now, a nested *type selector* like `div {}` must not be ambiguous with a declaration — early implementations needed `& div`. The robust habit is to always prefix with `&` when in doubt. Also, `&` in native CSS represents the parent selector *as a whole, with its specificity* — `&.active` means "this same element when it also has `.active`", concatenating onto the parent like Sass's `&`.

The **specificity gotcha** is the one that bites: native nesting wraps the parent reference in an `:is()`-like behavior, and `:is()` takes the specificity of its *most specific* argument. So if you nest under a selector list like `.card, #hero { & .title {} }`, the `.title` rule inherits the specificity of `#hero` (an ID!) even when matching inside `.card` — a surprising specificity inflation that Sass (pure text substitution) does not produce. This can cause a nested rule to win cascade battles you didn't expect. Sass also flattens at build time so there's *zero* runtime cost and no specificity surprise, whereas native nesting is computed live and carries the `:is()` specificity semantics.

The practical guidance: native nesting is great for co-locating related styles and cutting repetition, but **keep nesting shallow** (2–3 levels max) because deep nesting still produces long descendant selectors that raise specificity and hurt readability — the same anti-pattern that plagued Sass codebases. Use `&` explicitly, avoid nesting under ID-containing selector lists unless you *want* the inflated specificity, and remember that native nesting gives you the ergonomics without a build step but *not* Sass's mixins, functions, loops, or `@extend` — for those you still need a preprocessor or rely on custom properties and modern CSS features (`@property`, `color-mix`, container queries) that increasingly cover the same needs.

### 🔴 Expert — extended

#### Q65. [Coding] Implement CSS scroll-driven animations (`animation-timeline`) for a reading-progress bar and a reveal-on-scroll, with fallbacks.

**Problem:** A top-of-page reading-progress bar that fills as the user scrolls, plus elements that fade/slide in as they enter the viewport — driven entirely by CSS scroll-driven animations (no scroll-event JS), with graceful degradation.

```css
/* Register the animations */
@keyframes grow-x   { from { transform: scaleX(0); } to { transform: scaleX(1); } }
@keyframes reveal   { from { opacity: 0; transform: translateY(20px); }
                      to   { opacity: 1; transform: translateY(0); } }

/* 1. Reading-progress bar: a SCROLL timeline tracks the document scroller */
.progress-bar {
  position: fixed; inset-block-start: 0; inset-inline: 0; height: 4px;
  background: var(--brand); transform-origin: left; transform: scaleX(0);
  animation: grow-x linear;
  animation-timeline: scroll(root block);   /* progress = document scroll position */
}

/* 2. Reveal-on-scroll: a VIEW timeline tracks each element's own visibility */
.reveal {
  animation: reveal linear both;
  animation-timeline: view();                /* 0% as it enters, 100% as it passes */
  animation-range: entry 0% cover 40%;       /* play during the first 40% of crossing */
}

/* 3. Fallback for engines without scroll-driven animations: just show content */
@supports not (animation-timeline: view()) {
  .reveal { opacity: 1; transform: none; }   /* no animation, but fully visible */
}
/* 4. Respect reduced motion */
@media (prefers-reduced-motion: reduce) {
  .progress-bar, .reveal { animation: none; }
  .reveal { opacity: 1; transform: none; }
}
```

Scroll-driven animations (Chromium 2023, broadening through 2024–2026) are a major capability: they let an animation's *timeline* be driven by **scroll position** instead of wall-clock time, replacing the classic `scroll`-event JavaScript that ran on the main thread and caused jank. There are two timeline types. A **scroll timeline** (`scroll(root block)`) maps an animation's 0–100% progress to a scroll container's start-to-end position — perfect for a progress bar tied to the whole document. A **view timeline** (`view()`) maps progress to an *individual element's* travel through the scrollport, with `animation-range` keywords (`entry`, `exit`, `cover`, `contain`) describing *which part* of that travel the animation plays over — ideal for reveal-on-scroll where each element animates as it personally comes into view.

The performance significance is the headline: because these run on the **compositor**, scroll-linked effects that previously needed `requestAnimationFrame` + `getBoundingClientRect` (main-thread, layout-thrashing, the exact pattern that janks) now run off the main thread and stay smooth during heavy JS. That's why I animate only `transform`/`opacity` here — to keep the whole thing compositor-resident.

The expert requirements are the **fallbacks**, because this is newer than Baseline-safe in some browsers as of 2026. The `@supports not (animation-timeline: view())` block ensures that where the feature is unsupported, the content is simply *shown* (not stuck at `opacity: 0`, which would be a catastrophic "invisible content" bug — the #1 mistake with reveal animations). And `prefers-reduced-motion: reduce` disables the motion entirely while keeping content visible, satisfying accessibility. **Time/Space:** O(1) per animation, zero scroll handlers, GPU-driven. The judgment to articulate: progressive enhancement is mandatory here — the page must be fully readable with *no* scroll animation support, and the animations are pure delight layered on top.

#### Q66. [Theory] Design a multi-brand, multi-theme token architecture in pure CSS custom properties. How do you layer primitive, semantic, and component tokens?

A scalable theming system uses **three tiers of tokens**, each referencing the tier below, so a change at one level cascades predictably without rewriting components. **Tier 1 — primitive (global) tokens**: raw, context-free values — the full color ramp, spacing scale, radii, font sizes (`--blue-500: oklch(...)`, `--space-4: 1rem`). These never appear directly in component CSS. **Tier 2 — semantic (alias) tokens**: meaning-based names that *reference* primitives (`--color-surface: var(--gray-50)`, `--color-text-primary: var(--gray-900)`, `--color-action: var(--blue-500)`). This is the layer themes swap. **Tier 3 — component tokens**: per-component knobs that reference semantics (`--button-bg: var(--color-action)`, `--card-padding: var(--space-4)`), giving each component an override surface without leaking internals.

```css
/* Tier 1: primitives — never used directly in components */
:root {
  --gray-50: oklch(98% 0 0);   --gray-900: oklch(20% 0 0);
  --blue-500: oklch(60% 0.18 260);   --space-4: 1rem;
}
/* Tier 2: semantic aliases — the THEME swap layer */
:root, [data-theme="light"] {
  --color-surface: var(--gray-50);
  --color-text: var(--gray-900);
  --color-action: var(--blue-500);
}
[data-theme="dark"] {
  --color-surface: var(--gray-900);
  --color-text: var(--gray-50);
  --color-action: oklch(70% 0.16 260);
}
/* Brand override = remap primitives feeding the same semantics */
[data-brand="acme"]  { --blue-500: oklch(55% 0.2 280); }
[data-brand="globex"]{ --blue-500: oklch(62% 0.15 150); }

/* Tier 3: component tokens reference semantics only */
.btn {
  --btn-bg: var(--color-action);
  background: var(--btn-bg); color: var(--color-surface);
  padding: var(--space-3, 0.5rem) var(--space-4);
}
.btn--danger { --btn-bg: var(--color-danger); }  /* override one knob */
```

The architecture's power comes from **where each axis of variation is injected**. *Theme* (light/dark) swaps **semantic** tokens. *Brand* (multi-tenant white-labeling) remaps **primitives** that feed the semantics, so a single brand attribute restyles the whole product while every component keeps referencing the same semantic names. *Component customization* overrides **component** tokens locally. Because custom properties **inherit and cascade**, setting `[data-theme="dark"]` on `<html>` (or even on a subtree — you can have a dark widget inside a light page) re-resolves every dependent value live, and because they're runtime values, switching is instant with no stylesheet reload and no FOUC if you set the attribute before first paint.

The expert considerations: **(1)** keep components referencing *only* semantic/component tokens, never primitives — that indirection is what lets you re-theme without touching components, and it's the single most important discipline. **(2)** Use `@property` to register tokens that need type-safety or animation (a registered `<color>` token can transition smoothly during theme switch). **(3)** Provide fallbacks in `var(--x, fallback)` so a missing token degrades gracefully rather than rendering `unset`. **(4)** For true black-box web components, expose component tokens as the **public theming contract** (they pierce Shadow DOM) and document them like an API. **(5)** Generate the token files from a single source (Style Dictionary / design-tool export) so design and code can't drift, and emit per-platform outputs (CSS vars for web, equivalents for iOS/Android). The trade-off is one extra layer of indirection to trace when debugging (`--btn-bg` → `--color-action` → `--blue-500` → value), which good DevTools (the Computed pane shows the resolved chain) and naming conventions mitigate — and that cost buys you the ability to add a new brand or theme by editing one block instead of every component.

#### Q67. [Coding] Build an accessible modal dialog using `<dialog>` with focus management, backdrop, and scroll-locking — and note what you still must handle.

**Problem:** A production modal: opens centered with a dimmed backdrop, traps focus, restores focus on close, closes on Escape and backdrop click, and prevents background scroll — leveraging the native `<dialog>` element.

```html
<style>
  dialog {
    max-width: min(90vw, 480px);
    border: none; border-radius: 12px; padding: 1.5rem;
    /* showModal() auto-centers via the top layer; this styles the box */
  }
  dialog::backdrop { background: rgb(0 0 0 / 0.5); }  /* native dimming layer */

  /* Lock background scroll while a modal is open (CSS-only via :has) */
  html:has(dialog[open]) { overflow: hidden; }

  /* Optional entry animation, respecting reduced motion */
  @media (prefers-reduced-motion: no-preference) {
    dialog { opacity: 0; transform: translateY(8px);
             transition: opacity .15s, transform .15s, overlay .15s allow-discrete,
                         display .15s allow-discrete; }
    dialog[open] { opacity: 1; transform: none; }
    @starting-style { dialog[open] { opacity: 0; transform: translateY(8px); } }
  }
</style>

<button id="open">Edit profile</button>

<dialog id="dlg" aria-labelledby="dlg-title">
  <h2 id="dlg-title">Edit profile</h2>
  <form method="dialog">
    <label>Name <input name="name" autofocus /></label>
    <menu>
      <button value="cancel">Cancel</button>
      <button value="save">Save</button>
    </menu>
  </form>
</dialog>

<script>
  const dlg = document.getElementById('dlg');
  const opener = document.getElementById('open');
  opener.addEventListener('click', () => dlg.showModal());   // promotes to top layer
  // Close on backdrop click (clicks on the dialog's own padding area = the dialog;
  // the backdrop is outside the box, so target === dlg means the backdrop was hit)
  dlg.addEventListener('click', (e) => { if (e.target === dlg) dlg.close('cancel'); });
</script>
```

`dialog.showModal()` is doing an enormous amount for free, which is the whole point of the question. It promotes the dialog to the **top layer** (so it renders above all stacking contexts regardless of `z-index` — no portals needed), renders the `::backdrop` dimming layer, makes the rest of the page **inert** (background content becomes non-interactive *and* hidden from assistive tech), moves **focus into the dialog**, **traps focus** within it while open, closes on **Escape**, and **restores focus to the trigger** on close. Hand-rolling all of that — especially a correct focus trap and inert background — is notoriously bug-prone, so using the native element is the senior choice. `method="dialog"` on the form closes the dialog on submit and exposes the pressed button's `value` via `dlg.returnValue`. The `:has(dialog[open])` selector handles scroll-locking declaratively, and `@starting-style` + `transition-behavior: allow-discrete` (the `overlay`/`display` transitions) finally let the dialog animate in *and out* of the top layer.

What you **still must handle** (the part that separates a thorough answer): **(1)** `<dialog>` needs an **accessible name** — I add `aria-labelledby` pointing at the heading (or `aria-label`). **(2)** **Initial focus** — `autofocus` on the right control (or call `.focus()`), otherwise focus lands on the dialog itself which is acceptable but often not ideal; avoid autofocusing a destructive button. **(3)** **Backdrop-click-to-close** isn't automatic — the snippet detects it by checking `e.target === dlg` (clicks inside the content bubble from children; only a click on the dialog's own box edge/backdrop region matches), and some teams instead wrap content in an inner div and compare against that. **(4)** **Scroll-lock layout shift** — hiding the scrollbar with `overflow: hidden` can shift content; `scrollbar-gutter: stable` mitigates it. **(5)** **Light-dismiss semantics and nested dialogs** still need thought, and very old browsers need a dialog polyfill or a fallback. The takeaway: the platform now handles the *plumbing* (top layer, focus trap, inert, backdrop) that used to be 200 lines of fragile JS, but you remain responsible for the *content's* accessibility — labels, sensible initial focus, and not trapping the user in a destructive default.

#### Q68. [Behavioral] Tell me about a time you had to make a major CSS architecture decision under uncertainty that the team would live with for years. (STAR)

**Situation:** At a company scaling from one product to a multi-product suite, three teams had independently grown their own CSS — one on hand-written BEM, one on a CSS-in-JS library, one on a half-adopted utility framework — and shared UI (buttons, modals, form fields) was being reimplemented three times with subtly different behavior and accessibility bugs. Leadership wanted a unified design system, and as the senior frontend engineer I was asked to choose the CSS architecture the whole org would build on. The uncertainty was real: each approach had passionate advocates, the ecosystem was mid-transition (container queries and cascade layers were just reaching baseline), and getting it wrong meant years of migration debt.

**Task:** Choose a styling architecture and component-delivery model that would (a) guarantee accessibility and theming consistency across teams, (b) prevent one team from breaking another's styles, (c) not require a risky big-bang migration, and (d) still be a sound bet as the platform evolved — all without becoming a multi-month decision-paralysis exercise.

**Action:** Rather than declare a winner by fiat, I ran a structured, time-boxed evaluation. I wrote down the *decision criteria* first (encapsulation strength, accessibility-by-default, runtime theming, build complexity, migration path, hiring/onboarding cost, and longevity/Baseline support) and weighted them with input from all three teams, which turned a tribal argument into a comparison against shared goals. I built **spikes** of the same three components (button, modal, combobox) in each candidate approach and measured them against the criteria — including running axe and a focus-order test on each. The evidence shifted the debate: the CSS-in-JS option lost on runtime cost and SSR complexity; pure utilities lost on encapsulation for a *shared* library consumed by unknown teams. I landed on **design tokens as CSS custom properties + cascade layers for predictable priority + Shadow DOM (web components) for the truly shared primitives, with teams free to use any approach for their app-specific styling on top**. Critically, I chose an *additive* migration: the new library shipped alongside existing CSS in a low-priority `@layer`, so teams adopted component-by-component with zero forced rewrites, and I wrote an ADR documenting the criteria, the spike results, and explicitly the *reasons we rejected* each alternative — so the decision was legible and revisitable rather than a black box.

**Result:** Within two quarters all three teams had adopted the shared primitives for the high-traffic components, accessibility defects on those components dropped to near zero because they were fixed once in the library, and a rebrand that previously would have been a multi-week cross-team effort became a one-day token change. The layered, additive approach meant we never had a "migration freeze." The decision held up: when container queries and `@scope` matured, they slotted into the architecture rather than invalidating it, because tokens + layers + encapsulation were the *durable* bets and the specific syntax was layered on top. **Reflection:** The lesson I carry is that high-stakes architecture decisions under uncertainty are won by *making the criteria explicit and the trade-offs evidence-based* rather than by being the loudest advocate — and by choosing the option that's *reversible and additive* so you're not betting the company on being right the first time. I also learned that documenting *why you rejected* the alternatives is as valuable as documenting what you chose, because it stops the team from relitigating the decision every six months when a new framework trends.

### 🟢 Basic — extended (continued)

#### Q69. [Coding] Build a 3-column footer that stacks to a single column on small screens using only `flex-wrap` (no media queries).

**Problem:** A footer with three equal-ish columns of links that sit side by side on wide screens and stack vertically on narrow ones, with no breakpoints.

```html
<style>
  .footer {
    display: flex;
    flex-wrap: wrap;              /* columns drop to the next line when cramped */
    gap: 2rem;
    padding: 2rem;
  }
  .footer > .col {
    flex: 1 1 14rem;             /* grow, shrink, wrap once below ~14rem each */
    min-width: 0;                /* let long link text shrink instead of overflow */
  }
  .footer h4 { margin: 0 0 0.5rem; }
  .footer ul { list-style: none; margin: 0; padding: 0; line-height: 1.8; }
</style>

<footer class="footer">
  <nav class="col"><h4>Product</h4><ul><li><a href="#">Features</a></li><li><a href="#">Pricing</a></li></ul></nav>
  <nav class="col"><h4>Company</h4><ul><li><a href="#">About</a></li><li><a href="#">Careers</a></li></ul></nav>
  <nav class="col"><h4>Legal</h4><ul><li><a href="#">Privacy</a></li><li><a href="#">Terms</a></li></ul></nav>
</footer>
```

The whole responsiveness comes from `flex: 1 1 14rem` combined with `flex-wrap: wrap`. The `flex-basis` of `14rem` is the "ideal" width for each column; as long as three of them plus the gaps fit on a line, they share the row equally (the `1` grow factor stretches them to fill). When the container narrows so three `14rem` columns no longer fit, `flex-wrap` drops a column to the next line — first to two-up, then to one-up — and each wrapped column expands to the full width. The layout therefore responds to *available space* rather than a fixed device width, which is more robust than guessing breakpoints and handles in-between container sizes (a sidebar, a split view) for free.

The `min-width: 0` is the non-obvious correctness detail again: flex items default to `min-width: auto`, refusing to shrink below their content's intrinsic width, so a long link or unbreakable URL would force horizontal overflow; `min-width: 0` lets the column shrink and wrap text normally. I used `<footer>` with `<nav>` columns and real lists for semantics. **Time/Space:** O(1) layout, zero JS, zero media queries. **Edge case:** if you need *exactly* three columns that never become two (only three or one), `flex` wrapping can't express "skip the two-column state" — for that you'd use a grid with `repeat(auto-fit, minmax())` or a single container query; the flex version trades that control for simplicity.

#### Q70. [Theory] What is the difference between `<b>`/`<i>` and `<strong>`/`<em>`, and between `<section>` and `<div>`? Why does it matter?

These pairs look interchangeable in rendering but differ in *semantics*, which is the entire point of HTML elements beyond `<div>`. `<strong>` conveys **strong importance, seriousness, or urgency** and `<em>` conveys **stress emphasis** that changes the meaning of a sentence — both are semantic and a screen reader may alter intonation for them. `<b>` and `<i>` are **purely presentational** survivors kept for cases where text is *stylistically* offset without carrying extra importance: `<b>` for keywords/product names in a summary, `<i>` for a taxonomic term, a foreign-language phrase, or a ship name — text you'd traditionally bold/italicize by *convention*, not for emphasis. The practical rule: if removing the element would change what the sentence *means* or how it should be *spoken*, use `<strong>`/`<em>`; if it's just typographic styling with no semantic weight, `<b>`/`<i>` (or better, a class) is acceptable, though most of the time a styled `<span>` is clearer intent.

```html
<!-- Meaning/urgency → semantic -->
<p><strong>Warning:</strong> this <em>cannot</em> be undone.</p>
<!-- Stylistic offset, no added importance → presentational -->
<p>The <i>RMS Titanic</i> sank in 1912. Search for <b>“lifeboat”</b> below.</p>
```

`<section>` versus `<div>` is the same theme at the structural level. `<div>` is a **semantically neutral** generic container — it means "a box for styling/scripting" and contributes nothing to the document outline or accessibility tree. `<section>` represents a **thematic grouping of content**, typically with a heading, and it's a semantic region. The crucial nuance most people miss: `<section>` is only appropriate when the content is a *distinct, self-contained thematic unit that would logically have a heading* — using `<section>` purely as a styling wrapper is *wrong* because it pollutes the document structure and, when given an accessible name (via `aria-label`/`aria-labelledby`), becomes a `region` landmark that assistive tech exposes. A `<section>` without a heading is usually a code smell; if you just need a styling hook, use `<div>`.

Why it matters: semantics drive accessibility (landmarks, emphasis intonation), machine parsing (SEO, reader modes), and maintainability. The cost of choosing the meaningful element is zero — they render identically once styled — so the default should be the most semantic element, reserving `<div>`/`<span>` for genuinely presentational grouping and `<b>`/`<i>` for the narrow stylistic-offset cases the spec carved out. Reaching for `<div>` everywhere ("divitis") throws away free accessibility and structure; reaching for `<section>` everywhere creates a noisy, meaningless outline. The skill is matching the element to the content's actual role.

### 🟡 Intermediate — extended (continued)

#### Q71. [Coding] Implement a CSS-only "image comparison slider" (before/after) using `clip-path` and a range input.

**Problem:** Two overlaid images (before/after) where dragging a slider reveals more or less of the top image — minimal JS, driven by a custom property updated from a range input.

```html
<style>
  .compare {
    position: relative; max-width: 600px; aspect-ratio: 3 / 2;
    --pos: 50%;                      /* slider position, 0–100% */
  }
  .compare img {
    position: absolute; inset: 0; width: 100%; height: 100%;
    object-fit: cover; display: block;
  }
  .compare .after {                  /* top image clipped to the slider position */
    clip-path: inset(0 calc(100% - var(--pos)) 0 0);
  }
  .compare input[type="range"] {
    position: absolute; inset-block-end: 8px; inset-inline: 0;
    width: 100%;                     /* the visible control */
  }
  .compare .divider {                /* a visual handle line at the split */
    position: absolute; inset-block: 0; inset-inline-start: var(--pos);
    width: 2px; background: #fff; transform: translateX(-1px); pointer-events: none;
  }
</style>

<div class="compare" style="--pos:50%">
  <img class="before" src="before.jpg" alt="Before retouching" />
  <img class="after"  src="after.jpg"  alt="After retouching" />
  <div class="divider"></div>
  <input type="range" min="0" max="100" value="50"
         aria-label="Reveal after image"
         oninput="this.closest('.compare').style.setProperty('--pos', this.value + '%')" />
</div>
```

The technique layers both images absolutely, then **clips the top ("after") image with `clip-path: inset(...)`** so only a portion is visible. `inset(0 calc(100% - var(--pos)) 0 0)` insets the right edge by `100% − pos`, meaning when `--pos` is 30% the after-image shows only its left 30% and the before-image underneath shows through the rest. A single custom property `--pos` drives both the clip and the divider line, so updating one variable moves everything in sync. The only JavaScript is a one-liner on the range input writing `--pos` — everything else is declarative CSS, and the clip recomputes on the compositor cheaply.

The accessibility and correctness points worth raising: I used a real `<input type="range">` as the control rather than a `<div>` with drag handlers, so the component is **keyboard operable** (arrow keys move it) and **screen-reader announced** (with `aria-label`) for free — a custom drag-only slider would fail both. Both images keep meaningful `alt` text. `object-fit: cover` ensures mismatched-size sources don't distort, and `aspect-ratio` reserves the box so there's no layout shift while images load. The `divider` is `pointer-events: none` so it never intercepts the range input's interaction. **Edge cases:** for RTL you'd flip the inset side; for touch the native range already handles drag; and if you want pointer-anywhere dragging (not just the track) you'd add a small JS pointer handler writing `--pos` from the cursor X — but the range-input core keeps it accessible regardless. **Time/Space:** O(1), GPU-composited clip, near-zero JS.

#### Q72. [Theory] Explain how `currentColor`, the `accent-color` property, and `color-scheme` reduce the CSS you write for themeable UI.

These three are small properties that punch far above their weight for theming because each lets the *value cascade or the user agent do the work* instead of you hardcoding colors in many places. **`currentColor`** is a keyword that resolves to the element's computed `color`. Using it as the value of `border`, `background`, `fill` (on SVG), `box-shadow`, or `outline` means those follow the text color automatically — so an inline SVG icon with `fill="currentColor"` tints itself to match surrounding text in every theme without a single theme-specific rule. It's effectively a built-in "inherit my text color into this other property" and dramatically cuts the number of color declarations in a token system.

```css
.alert { color: var(--alert-text); border: 1px solid currentColor; }   /* border tracks text */
svg.icon { fill: currentColor; }                                       /* icon tracks text */

input[type="checkbox"], input[type="radio"], progress { accent-color: var(--brand); }

:root { color-scheme: light dark; }   /* UA themes form controls, scrollbars, etc. */
```

**`accent-color`** lets you recolor the *intrinsic* parts of native form controls — the check of a checkbox, the dot of a radio, the bar of a `<progress>` and `range` track — with a single property, while keeping the native control (and all its accessibility/keyboard behavior). Before it, matching brand color on these required the entire "hide the input, draw a custom one" dance from the custom-toggle question. For the common case where you just want the brand color on otherwise-native controls, `accent-color: var(--brand)` is one line and you keep every native affordance. It also automatically picks an accessible contrast for the checkmark against your accent.

**`color-scheme`** tells the browser which schemes the page supports (`light`, `dark`, or `light dark`), which makes the user agent render its *own* surfaces — form control chrome, scrollbars, the default `<select>` dropdown, spellcheck underlines, and the canvas default — in the matching scheme. Without it, you get a white scrollbar on a dark page and light-styled native widgets that clash. Combined: `color-scheme` handles UA-painted surfaces, `accent-color` handles the brand accent on native controls, and `currentColor` propagates your text color into borders/icons/shadows — together they eliminate dozens of explicit per-theme color rules. The trade-off is mostly awareness: these are easy to forget (especially `color-scheme`, whose absence produces the classic "dark page, white scrollbar/dropdown" bug), and `accent-color` only recolors the *intrinsic* control parts, so for fully bespoke control visuals you still fall back to custom styling.

#### Q73. [Coding] Build a pure-CSS responsive equal-height card row where cards align their footers regardless of body length.

**Problem:** A row of cards with varying body text; every card should be the same height and every card's call-to-action button should sit on the same bottom line, with no JavaScript and no fixed heights.

```html
<style>
  .deck {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 1rem;
    align-items: stretch;            /* grid default: cards stretch to row height */
  }
  .card {
    display: flex;
    flex-direction: column;          /* stack header → body → footer vertically */
    border: 1px solid #ddd; border-radius: 10px; padding: 1rem;
  }
  .card h3 { margin: 0 0 0.5rem; }
  .card .body { flex: 1 1 auto; }    /* body grows to absorb extra height */
  .card .footer { margin-block-start: 1rem; }  /* pinned to the bottom by the grown body */
</style>

<div class="deck">
  <article class="card"><h3>Basic</h3><p class="body">Short.</p><div class="footer"><button>Choose</button></div></article>
  <article class="card"><h3>Pro</h3><p class="body">A much longer description that wraps onto several lines and makes this card taller than its siblings.</p><div class="footer"><button>Choose</button></div></article>
  <article class="card"><h3>Team</h3><p class="body">Medium length copy here.</p><div class="footer"><button>Choose</button></div></article>
</div>
```

Two layout systems cooperate here. The **grid** with `auto-fit`/`minmax` handles the responsive column count and, because grid items `stretch` by default, every card in a row is forced to the **same height** as the tallest card — that's the equal-height part, free, with no `height` values. Then each **card is itself a flex column**, and `flex: 1 1 auto` on the body makes the body absorb all the leftover vertical space inside the card. Since the body grows, it pushes the footer to the bottom edge, so all footers align across the row regardless of how much body text each card has. This is the canonical "card with pinned footer" pattern and it composes Grid (outer, equal height) with Flexbox (inner, push footer down) — exactly the "Grid cell contains a Flex container" idiom.

The reason this is better than the alternatives is worth stating: fixed `height` clips overflowing content and breaks on translation/zoom; JavaScript height-matching causes layout thrash and a flash before it runs; and `subgrid` (a more advanced option) aligns internal rows across cards but is overkill when you only need the footer pinned. The flex-grow-on-body trick needs nothing but two display modes. **Edge cases:** if you need the *titles* and *bodies* to also align row-to-row (not just footers), that's the subgrid use case from the earlier question; here we only guarantee equal card height and bottom-aligned footers. `min-width: 0` on the body may be needed if it contains unbreakable content. **Time/Space:** O(n) layout over the cards, zero JS, fully responsive.

### 🟠 Advanced — extended (continued)

#### Q74. [Theory] Explain CSS anchor positioning (`anchor()`, `position-anchor`, `position-try`) and what JS-based positioning libraries it replaces.

Anchor positioning (Chromium 2024, broadening through 2025–2026) is the native answer to a problem that previously *required* JavaScript: tethering a floating element (tooltip, dropdown menu, popover) to a reference element and keeping it positioned correctly as the page scrolls, resizes, or the element approaches a viewport edge. For two decades this meant libraries like Popper/Floating UI that measure both elements with `getBoundingClientRect()` on every scroll/resize and recompute coordinates on the main thread — correct but costly and a common source of jank and reflow.

The native model has three parts. You name an **anchor** element and connect a **positioned** element to it, then express the positioned element's location *in terms of the anchor's edges* using the `anchor()` function, and finally declare **fallback positions** with `position-try` so the element flips to a different side when it would overflow the viewport.

```css
.trigger { anchor-name: --menu-btn; }      /* declare this element an anchor */

.menu {
  position: fixed;                          /* anchored elements are abs/fixed */
  position-anchor: --menu-btn;              /* bind to that anchor */
  /* place the menu's top-start at the anchor's bottom-start */
  inset-block-start: anchor(bottom);
  inset-inline-start: anchor(start);
  /* if it would overflow, try flipping above, then to the other side */
  position-try-fallbacks: flip-block, flip-inline;
}
```

The wins mirror what `<dialog>`/popover did for overlays: positioning runs in the **engine/compositor** rather than a JS scroll-loop, so it stays smooth and doesn't thrash layout; it's **declarative** (no measuring code to maintain); and it composes with the **top layer** so an anchored popover both escapes stacking contexts *and* stays tethered to its trigger — the exact combination tooltips/menus need and the thing portals alone couldn't give you (a portaled element loses its positional relationship to its trigger, which is why you needed Popper *on top of* a portal). `position-try` with `flip-block`/`flip-inline`/custom `@position-try` fallback rules replaces the "collision detection / auto-placement" feature that was the main reason to pull in a positioning library.

The trade-offs and current limits: browser support is the newest of all the features discussed here, so as of 2026 I gate it behind `@supports (anchor-name: --x)` and keep a Floating-UI fallback (or a simpler static placement) for non-supporting engines — shipping it without a fallback would leave menus mispositioned on those browsers. The mental model also takes adjustment (positioning relative to *another element's* edges rather than its own containing block), and complex multi-fallback chains can be fiddly to reason about. But the direction is clear: anchor positioning, the top layer, and scroll-driven animations are collectively migrating an entire category of main-thread UI JavaScript into the declarative CSS/compositor pipeline, which is faster, more resilient (works if the JS bundle fails), and less code to own.

#### Q75. [Coding] Diagnose and fix a layout-thrashing hot path in JS that animates a list. Show before/after.

**Problem:** A function highlights and measures a list of rows in a loop. It janks badly. Identify why and rewrite it to avoid forced synchronous layout.

```javascript
// BEFORE — O(n) forced reflows: writes then immediately reads inside the loop
function layoutRows(rows) {
  for (const row of rows) {
    row.classList.add('highlight');          // WRITE: invalidates layout
    const h = row.offsetHeight;              // READ: forces SYNC layout to flush
    row.style.setProperty('--h', h + 'px');  // WRITE: invalidates again
  }
}
// Each iteration's read forces the browser to recompute layout for the
// pending write → N reads × N writes interleaved = O(N) forced reflows
// (effectively quadratic work as the engine re-lays-out the growing dirty set).
```

The bug is **layout thrashing** (forced synchronous layout): the browser batches style/layout changes and only recomputes layout when it needs to — but *reading* a layout property like `offsetHeight`, `getBoundingClientRect()`, `scrollTop`, or `getComputedStyle()` forces it to *flush* all pending changes immediately so the read is accurate. Doing a write then a read every loop iteration defeats batching: each read flushes the prior write's layout, so you pay for N separate layout passes instead of one. This shows up in the Performance panel as a stack of "Recalculate Style"/"Layout" (purple) bars and a warning about "forced reflow." The fix is **read/write separation (batching)** — do *all* reads first (off the clean layout), then *all* writes:

```javascript
// AFTER — separate phases: one read pass, one write pass → ~2 layout passes total
function layoutRows(rows) {
  // Phase 1: READ everything (no writes in between → no forced flushes)
  const heights = rows.map(row => row.offsetHeight);

  // Phase 2: WRITE everything (batched; layout recomputes once afterward)
  rows.forEach((row, i) => {
    row.classList.add('highlight');
    row.style.setProperty('--h', heights[i] + 'px');
  });
}
```

By reading all heights into an array first, every read hits the same already-clean layout, so there's no per-iteration flush; then the batched writes leave the layout dirty exactly once, recomputed at the next frame. This drops the work from N layout passes to effectively 2. For animation-heavy code, wrapping the write phase in `requestAnimationFrame` (and reads in the preceding frame, or using a scheduler like FastDOM that auto-batches reads vs writes) further guarantees writes land once per frame aligned with the paint cycle.

The broader lessons for an interview: **(1)** the expensive part of DOM work is rarely the JavaScript — it's forcing the rendering engine to do synchronous layout mid-script. **(2)** Know the **layout-triggering read properties** (offset*, client*, scroll*, getBoundingClientRect, getComputedStyle, focus() in some cases) — touching any after a write forces a flush. **(3)** If the animation is just *moving/fading*, the deeper fix is to not animate from JS at all — use a CSS `transform`/`opacity` transition (compositor) or a scroll-driven animation, eliminating the measure-and-write loop entirely. **(4)** `content-visibility: auto` on off-screen rows reduces how much the forced layout costs even when one happens. I'd verify the fix by re-profiling and confirming the per-frame layout bars collapse and the frame rate holds. **Time/Space:** before ≈ O(n) reflows; after ≈ O(1) reflows with O(n) extra memory for the heights array — a good trade.

#### Q76. [Theory] How do `contain` values (`layout`, `paint`, `size`, `style`, `inline-size`) compose, and how do you choose between `contain`, `content-visibility`, and containment via `container-type`?

`contain` is a promise to the engine that an element is isolated in specific ways, letting it skip work that would otherwise cascade across the document. The values are independent capabilities you can combine. **`layout`**: the element's internal layout doesn't affect (and isn't affected by) anything outside it — a reflow inside can't dirty the rest of the page, and vice versa. **`paint`**: descendants are clipped to the element's box and won't paint outside it, so when the box is off-screen the browser can skip painting its subtree entirely (it also creates a stacking context and containing block for fixed/absolute descendants). **`size`**: the element's size is independent of its contents — you must give it an explicit size, because the engine will lay it out as if it has no children for sizing purposes. **`inline-size`**: like `size` but only on the inline axis (the basis for `container-type: inline-size`). **`style`**: scopes certain document-wide effects (counters, `quotes`) so they don't leak out.

```css
.widget   { contain: layout paint; }     /* isolate without claiming a fixed size */
.offscreen{ content-visibility: auto;    /* = contain: layout style paint + skip render */
            contain-intrinsic-size: auto 200px; }   /* reserve space for skipped content */
.cardwrap { container-type: inline-size; } /* = inline-size + layout + style containment, queryable */
```

The relationships are the key insight, because the higher-level features are *built on* `contain`. **`content-visibility: auto`** is essentially `contain: layout style paint` *plus* the ability to **skip rendering work entirely when off-screen** — it's the ergonomic, scroll-aware application for long pages, and it needs `contain-intrinsic-size` to reserve space for skipped content (covered earlier). **`container-type: inline-size`** applies `inline-size` + `layout` + `style` containment and *additionally* makes the element a query container — its containment exists specifically to break the circular dependency (the container's size mustn't depend on the content you're querying). So they're three doorways into the same containment machinery at different abstraction levels.

How I choose: use raw **`contain: layout paint`** when I want to *bound invalidation/paint* for a self-contained widget that updates frequently (so a mutation inside it can't trigger full-page reflow) but I *don't* want to fix its size — this is a surgical performance tool for interactive components. Use **`content-visibility: auto`** for *long, scrollable* pages of many similar items where most are off-screen — it's the biggest win there and largely automatic. Use **`container-type`** only when I actually need *container queries*; I don't reach for it as a generic performance tool because its size containment changes intrinsic sizing and can surprise layout. The pitfalls to avoid: applying `contain: size` (or `content-visibility` without `contain-intrinsic-size`) collapses elements that depend on intrinsic height to zero, causing scrollbar jump; `contain: paint` clips overflow, which can hide tooltips/shadows that need to escape; and over-applying containment everywhere adds bookkeeping cost. The mental model: `contain` is the primitive (declare what's isolated), `content-visibility` and `container-type` are purpose-built bundles of it for "skip off-screen rendering" and "be queryable," respectively.

#### Q77. [Coding] Implement a responsive data table that reflows into stacked cards on narrow screens, accessibly.

**Problem:** A wide data table is unusable on phones. Make each row collapse into a labeled "card" on narrow screens (label: value pairs), keeping it a real `<table>` for semantics and accessibility, using a container query.

```html
<style>
  .table-card { container-type: inline-size; }
  table { width: 100%; border-collapse: collapse; }
  th, td { padding: 0.5rem 0.75rem; text-align: start; border-bottom: 1px solid #ddd; }

  /* Narrow container → reflow rows into stacked cards */
  @container (max-width: 520px) {
    thead { position: absolute; width: 1px; height: 1px;
            overflow: hidden; clip-path: inset(50%); }   /* visually hide header row */
    tr { display: block; margin-block-end: 1rem; border: 1px solid #ccc; border-radius: 8px; }
    td { display: flex; justify-content: space-between; gap: 1rem; border: none; }
    td::before {
      content: attr(data-label);          /* show the column name as an inline label */
      font-weight: 600;
    }
  }
</style>

<div class="table-card">
  <table>
    <thead><tr><th>Name</th><th>Role</th><th>Status</th></tr></thead>
    <tbody>
      <tr><td data-label="Name">Ada</td><td data-label="Role">Engineer</td><td data-label="Status">Active</td></tr>
      <tr><td data-label="Name">Linus</td><td data-label="Role">Maintainer</td><td data-label="Status">Active</td></tr>
    </tbody>
  </table>
</div>
```

The reflow technique: on a narrow container, switch the table parts to `display: block`/`flex` so each `<tr>` becomes a card and each `<td>` becomes a label/value line, with the column name injected via `td::before { content: attr(data-label) }` reading a `data-label` attribute on each cell. The header row is **visually hidden** (using the clip pattern, not `display: none`) so sighted users see the per-cell labels instead, while the table structure remains intact. I used a **container query** (`@container`) rather than a media query so the table reflows based on *its own* width — correct when the same table appears in a wide main area on one page and a narrow sidebar on another.

The accessibility subtleties are the crux and where this question separates levels. Changing `display` on table elements (`table`, `tr`, `td` → `block`/`flex`) **removes their implicit table semantics in the accessibility tree** in many browsers — the element stops being exposed as a table/row/cell, so a screen reader no longer announces "row 2, column Role." That means the visual reflow can *break* the very semantics that made a table appropriate. Mitigations: keep the `data-label` text so the relationship is at least visually and textually present; consider re-asserting roles with ARIA (`role="table"`, `role="row"`, `role="cell"`) if you must override `display`, though that's verbose and fragile; or, for critical data, prefer a layout that *doesn't* destroy table display (e.g. horizontal scroll with a frozen first column, the sticky-table approach from earlier) so semantics survive. I never use `display: none` to hide the `<thead>` (it would drop the header from assistive tech and from the `data-label` source if I derived labels from it) — the visually-hidden clip keeps it available. **Edge cases:** very long values still need `min-width: 0`/wrapping; and I'd test with a screen reader on both the wide and reflowed states, because "looks fine" and "reads correctly" diverge here. **Time/Space:** O(rows) layout, zero JS; the `data-label` duplication is the maintenance cost (generate it from the header in templating to avoid drift).

### 🔴 Expert — extended (continued)

#### Q78. [Theory] Explain the `@scope` rule in depth — proximity-based resolution, donut scoping, and how it differs from Shadow DOM and BEM.

`@scope` (broadening support through 2024–2026) is native CSS scoping that bounds where a set of rules applies *without* the encapsulation cost of Shadow DOM. You declare a **scoping root** and optionally a **scope limit**, and rules inside apply only to elements between them in the tree. Its headline feature is a *new cascade tiebreaker*: **proximity** — when two scoped rules of equal specificity match, the one whose scoping root is the *nearest ancestor* of the element wins, which specificity and source order alone could never express.

```css
/* Rules apply to elements inside .card … */
@scope (.card) {
  :scope { border: 1px solid #ddd; }   /* :scope = the scoping root itself */
  a { color: var(--card-link); }       /* only <a> inside .card */
}

/* "Donut scoping": apply inside .content but STOP at any nested .card */
@scope (.content) to (.card) {
  p { line-height: 1.7; }              /* paragraphs in .content but NOT inside .card */
}
```

Two capabilities distinguish it. **Donut scoping** (`@scope (root) to (limit)`) styles a subtree *but excludes* inner regions — the classic example is "style everything in the article body, but not inside embedded widgets/cards," a hole in the middle of the donut that was awkward to express with descendant selectors and `:not()`. **Proximity-based resolution** solves the real-world theming conflict where a light-themed card sits inside a dark-themed section inside a light page: with `@scope`, the *closest* theme scope to a given element wins regardless of selector specificity or which stylesheet loaded last, so nested theme contexts "just work" — something BEM and plain selectors handle only with escalating specificity hacks.

How it compares: **Shadow DOM** gives *true* encapsulation — outer styles (except inherited properties and exposed custom properties) can't get in and inner styles can't leak out, at the cost of a hard boundary that complicates global theming and DevTools ergonomics and requires custom elements. **`@scope`** is *soft* scoping — it limits *which elements a stylesheet's rules target* and adds proximity resolution, but it does **not** create an encapsulation boundary: inherited properties and the global cascade still apply, and outer styles can still reach scoped elements. **BEM** is a *naming convention* that simulates scoping by making class names unique (`.card__title`) — zero runtime mechanism, framework-agnostic, but verbose and reliant on discipline. The expert decision: use Shadow DOM for black-box components shipped to unknown consumers where leakage must be impossible; use `@scope` for *internal app/design-system code* where you want locality and nested-theme proximity without losing the cascade or DevTools clarity; use BEM (or CSS Modules) where build-time class scoping suffices and you want maximum tooling/browser compatibility. The trade-offs of `@scope`: it's newer (gate on Baseline/`@supports` and have a fallback), the proximity rule is a new mental model to teach, and because it's *not* encapsulation, it won't protect you from outside styles — so it complements, rather than replaces, Shadow DOM for the strongest isolation needs.

#### Q79. [Coding] Build a CSS-only multi-step "wizard" progress indicator that highlights completed/current/upcoming steps from a single attribute.

**Problem:** A horizontal stepper (Step 1 → 2 → 3 → 4) where steps before the current are "complete," the current is "active," and later ones are "upcoming" — all driven by one `data-step` attribute on the container, no per-step classes, no JS beyond setting that attribute.

```html
<style>
  .stepper { --current: 1; display: flex; gap: 0; counter-reset: step; list-style: none; padding: 0; }
  .stepper > li {
    counter-increment: step; flex: 1; text-align: center; position: relative;
    color: #999;                                   /* default: upcoming */
  }
  .stepper > li::before {                          /* the numbered circle */
    content: counter(step); display: grid; place-items: center;
    width: 2rem; height: 2rem; margin: 0 auto 0.25rem; border-radius: 50%;
    background: #eee; color: inherit; position: relative; z-index: 1;
  }
  .stepper > li:not(:first-child)::after {         /* connector line to previous */
    content: ""; position: absolute; inset-block-start: 1rem;
    inset-inline-end: 50%; width: 100%; height: 2px; background: #ddd; z-index: 0;
  }
  /* Drive state purely from --current using nth-child against a CSS variable */
  .stepper > li:nth-child(-n + var(--current)) { color: #111; }            /* completed+current */
  .stepper > li:nth-child(-n + var(--current))::before { background: var(--brand); color: #fff; }
  .stepper > li:nth-child(-n + var(--current))::after  { background: var(--brand); }
</style>

<ol class="stepper" style="--current:3" aria-label="Checkout progress">
  <li>Cart</li><li>Shipping</li><li>Payment</li><li>Review</li>
</ol>
```

The design goal is **single source of truth**: one `--current` value (or `data-step`) decides every step's appearance, so advancing the wizard is one attribute change, not toggling N classes. CSS counters (`counter-reset`/`counter-increment` + `content: counter(step)`) generate the visible step numbers from the DOM order so the markup carries no hardcoded "1/2/3." The connector lines are `::after` pseudo-elements, and the circles are `::before`, keeping all decoration out of the HTML. Styling "all steps up to and including current" via `:nth-child(-n + var(--current))` highlights completed+current in one rule and leaves later steps in the default upcoming style.

The honest expert caveat — and a great thing to surface in an interview — is that **`:nth-child` does not yet universally accept `var()` in its argument** across all engines (it's a relatively new capability); where unsupported, the robust fallbacks are either generating `:nth-child(-n + 3)` rules for each possible step count, or (cleaner) using **sibling-state selectors**: mark the current step and use `:has()`/sibling combinators, or set `data-step` and select with attribute logic. The point of the question is the *architecture* (one variable drives derived visual state), and I'd note the support nuance and the fallback rather than present the `var()`-in-`nth-child` as universally shippable in 2026.

The accessibility layer is essential: a purely visual stepper tells a screen-reader user nothing about progress. I use an ordered list `<ol>` (steps are inherently ordered) with an `aria-label`, mark the current step with `aria-current="step"` (set alongside `--current`), and ensure the completed/current/upcoming distinction isn't conveyed by *color alone* (the filled circle + the number + `aria-current` give non-color cues, satisfying WCAG 1.4.1). I'd also make sure the contrast of the muted "upcoming" gray still meets minimums. **Edge cases:** RTL flips the connector side (using `inset-inline-end` handles it); for many steps the labels need wrapping/truncation. **Time/Space:** O(n) over steps, near-zero JS, all derived state.

#### Q80. [Theory] Discuss CSS performance at scale: style recalculation, invalidation sets, and why a single attribute toggle high in the DOM can be expensive.

The cost most teams under-appreciate isn't initial style application — it's **style recalculation during interaction**, driven by **invalidation**. When the DOM or a style changes, the engine must figure out *which elements' computed styles might now be wrong* and recompute them. Modern engines are smart: they build **invalidation sets** that map a change (a class added, an attribute toggled, a state pseudo-class flipping) to the minimal set of elements whose styles depend on it, so toggling `.active` on a leaf usually recomputes only that element and a few descendants. The expensive case is when a change has a **large invalidation set** — many elements' styles depend on it — forcing a big "Recalculate Style" pass that can blow the frame budget and cause interaction jank.

This is why **toggling an attribute high in the DOM can be surprisingly expensive**: if you flip `data-theme` or a class on `<html>`/`<body>` and many selectors are conditioned on an ancestor (`[data-theme="dark"] .card .title`, descendant selectors rooted near the top, `:has()` near the root), the engine may have to re-evaluate styles for a huge subtree because that ancestor change *could* affect all of them. The deeper the descendant chains that start high in the tree, and the more selectors that depend on root state, the larger the invalidation. Relational selectors are the sharpest edge: **`:has()`** can invalidate *upward and broadly* — a change to a descendant can change whether an ancestor matches `ancestor:has(.x)`, so the engine must track these dependencies and a single mutation can trigger recalculation across many candidate ancestors. Used near the root with broad subjects, `:has()` can be genuinely costly.

```
Cheap:   .btn.active {}             → toggling .active invalidates ~1 element
Costly:  html[data-theme] .a .b .c {}  → toggling theme can invalidate a huge subtree
Sharp:   :root:has(.menu-open) .page {} → descendant change re-checks root :has dependency
Read in DevTools: long "Recalculate Style" bars in Performance during interaction.
```

The levers to keep recalculation cheap at scale: **(1)** prefer **flat, class-based, leaf-targeted selectors** so a state change invalidates a small set — this is the *real* performance reason to keep specificity flat, more than match cost. **(2)** Scope state changes **low in the tree** when possible (toggle a class on the component, not the body) so the invalidation set is small; reserve root-level toggles (theme) for genuinely global changes and accept they're a heavier, infrequent recalculation. **(3)** Use **`contain`** to bound invalidation — `contain: layout style` on a widget stops some style/layout effects from propagating past it, shrinking invalidation sets. **(4)** Be deliberate with **`:has()`** near the root or with broad subjects; prefer it on bounded subtrees. **(5)** Avoid extremely broad key/ancestor selectors (`* `, `[class]`) that maximize candidate sets. The workflow is the same as always: **profile first** — long "Recalculate Style" during interaction (not load) is the signature of an invalidation problem, and the fix is reducing *how much* a change invalidates, not micro-optimizing how fast a single selector matches. At the scale of tens of thousands of DOM nodes and rich interactivity, this — not selector matching speed and not even paint — is frequently the dominant CSS cost.

#### Q81. [Coding] Implement an accessible, keyboard-navigable tab component — show the HTML/ARIA and the minimal JS, and explain why CSS-only tabs fall short.

**Problem:** A tabbed interface (WAI-ARIA Tabs pattern) where arrow keys move between tabs, the selected tab's panel shows, and screen readers announce roles and selection. Explain why the pure-CSS `:target`/radio approaches are insufficient.

```html
<style>
  [role="tablist"] { display: flex; gap: 0.25rem; border-bottom: 2px solid #ddd; }
  [role="tab"] {
    appearance: none; border: none; background: none; padding: 0.5rem 1rem;
    cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -2px;
  }
  [role="tab"][aria-selected="true"] { border-bottom-color: var(--brand); font-weight: 600; }
  [role="tab"]:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
  [role="tabpanel"]:not(.is-active) { display: none; }
</style>

<div class="tabs">
  <div role="tablist" aria-label="Account settings">
    <button role="tab" id="t1" aria-controls="p1" aria-selected="true" tabindex="0">Profile</button>
    <button role="tab" id="t2" aria-controls="p2" aria-selected="false" tabindex="-1">Security</button>
  </div>
  <div role="tabpanel" id="p1" aria-labelledby="t1" class="is-active" tabindex="0">Profile content…</div>
  <div role="tabpanel" id="p2" aria-labelledby="t2" tabindex="0" hidden>Security content…</div>
</div>

<script>
  const tabs = [...document.querySelectorAll('[role="tab"]')];
  function select(tab) {
    tabs.forEach(t => {
      const on = t === tab;
      t.setAttribute('aria-selected', on);
      t.tabIndex = on ? 0 : -1;                                  // roving tabindex
      document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
    });
    tab.focus();
  }
  document.querySelector('[role="tablist"]').addEventListener('keydown', e => {
    const i = tabs.indexOf(document.activeElement);
    if (e.key === 'ArrowRight') select(tabs[(i + 1) % tabs.length]);
    if (e.key === 'ArrowLeft')  select(tabs[(i - 1 + tabs.length) % tabs.length]);
  });
  tabs.forEach(t => t.addEventListener('click', () => select(t)));
</script>
```

The ARIA Tabs pattern has specific, testable requirements that this implements: a `tablist` containing `tab`s, each tab `aria-controls`-linked to its `tabpanel` and the panel `aria-labelledby` its tab; the selected tab carries `aria-selected="true"`; and — the part people miss — **roving tabindex**: only the active tab is in the tab sequence (`tabindex="0`), the others are `tabindex="-1"`, so pressing Tab moves *into and out of* the tablist as a single stop, while **Arrow keys** move *between* tabs. That arrow-key navigation, focus management, and `aria-selected`/`hidden` syncing are inherently *stateful behaviors*, which is precisely why a small amount of JavaScript is the correct, accessible choice here.

**Why CSS-only tabs fall short** is the real depth of the question. The two pure-CSS approaches each break an accessibility requirement. The **radio-button approach** (hidden `<input type="radio">` per tab, panels shown via `:checked`) gives you toggling and even keyboard arrow movement *between radios*, but the controls are announced as *radio buttons*, not tabs — the screen-reader user is told "radio button, 1 of 3," not "tab, selected," so the mental model and announced semantics are wrong, and you can't express `aria-controls`/`tabpanel` relationships correctly. The **`:target` approach** (anchor links toggling panels via `#id` matching) changes the URL hash, adds history entries on every tab switch (polluting the back button), and again lacks tab semantics and roving focus. Both also struggle to implement the *exact* WAI-ARIA keyboard contract (Home/End, optional automatic vs manual activation). CSS can *style* the states beautifully and even toggle visibility, but it cannot manage focus, set `aria-selected` dynamically, or announce the right roles — those are behaviors, and ARIA attributes that must change in response to interaction require script.

The senior takeaway: I reach for native semantics first and add the *minimum* JS to satisfy the interaction contract, following the WAI-ARIA Authoring Practices rather than inventing behavior. I'd also note progressive enhancement — render all panels visible (or as linked sections) without JS so content is reachable if the script fails, then enhance into tabs. **Time/Space:** O(1) per interaction; the JS is small and the heavy lifting (styling, layout) stays in CSS. The discipline is knowing the boundary: CSS owns appearance and simple state-driven visibility; JS owns focus management and dynamic ARIA state.

#### Q82. [Theory] Explain how the browser decides image decoding, lazy-loading, `fetchpriority`, and `decoding` — and how these affect LCP.

Image loading has several independent knobs that together determine how fast the **Largest Contentful Paint** element (often a hero image) appears, and conflating them is a common source of slow LCP. **`loading="lazy"`** defers fetching an image until it's near the viewport — excellent for below-the-fold images (saves bandwidth, reduces contention) but **catastrophic if applied to the LCP image**, because you've told the browser to *delay* the very thing that defines your LCP. The single most common LCP regression is a blanket `loading="lazy"` on all images including the hero. So: lazy-load below-the-fold, **never** the LCP/above-the-fold image (use `loading="eager"` or omit it).

**`fetchpriority`** (`high`/`low`/`auto`) hints the resource scheduler's priority. The browser already assigns priorities heuristically (images start at low/medium until layout proves they're in-viewport), so the high-value move is `fetchpriority="high"` on the **LCP image** to pull it ahead of less-critical resources, and optionally `fetchpriority="low"` on clearly non-critical images. Combined with a `<link rel="preload" as="image" fetchpriority="high">` for an LCP image that's discovered late (e.g. set via CSS `background-image`, which the preload scanner can't see), you ensure the LCP resource starts downloading as early as possible.

```html
<!-- LCP hero: fetch eagerly, high priority, async decode -->
<img src="hero.avif" alt="…" width="1200" height="600"
     loading="eager" fetchpriority="high" decoding="async" />

<!-- Below the fold: lazy, normal priority -->
<img src="thumb.avif" alt="…" width="320" height="200"
     loading="lazy" decoding="async" />

<!-- LCP image set via CSS background (preload scanner can't find it) → preload it -->
<link rel="preload" as="image" href="hero.avif" fetchpriority="high">
```

**`decoding`** controls *image decode* timing relative to rendering. Decoding (turning compressed bytes into a bitmap) is CPU work that, if done synchronously, can block the main thread and delay presenting the rest of the frame. `decoding="async"` lets the browser decode off the critical path and present surrounding content without waiting; `sync` forces it to decode before display (useful to avoid a flash when an image swaps in); `auto` lets the browser decide. For most content `async` is a safe default; the `Image.decode()` JS API is the imperative couson for "decode now, then insert with no jank."

How they interact for LCP: the browser's pipeline is *discover → fetch (priority) → decode → paint*. To minimize LCP you want the hero **discovered early** (in the HTML, not injected by JS/CSS; preload if unavoidable), **fetched at high priority and eagerly** (not lazy), in an **efficient format** (AVIF/WebP) and **correctly sized** (responsive `srcset`/`sizes` so you don't download a 4K image for a phone), with **dimensions set** (`width`/`height` so there's no layout shift competing with paint), and **decoded async** so decode doesn't stall the frame. The pitfalls that quietly wreck LCP: lazy-loading the hero, hiding the hero behind a CSS `background-image` (defeats the preload scanner), shipping an oversized image, and JS-injecting the hero after hydration. I measure with LCP in field data (CrUX) and the Lighthouse "Largest Contentful Paint image was lazily loaded" / "Preload LCP image" audits, and I treat the hero as a first-class performance resource distinct from the dozens of lazy images below it.

#### Q83. [Coding] Write CSS that gracefully handles user-generated/unknown-length content (long words, overflow, line clamping) without breaking layout.

**Problem:** UGC — usernames, comments, pasted URLs, titles — can be arbitrarily long or contain unbreakable strings and will blow out a layout. Write defensive CSS that wraps, truncates, and clamps safely.

```css
/* 1. Break long words / URLs so they wrap instead of overflowing the box */
.comment {
  overflow-wrap: break-word;     /* break only when a word is too long for the line */
  word-break: normal;            /* keep normal breaking otherwise (don't shred all words) */
  hyphens: auto;                 /* allow hyphenation where the language supports it */
}

/* 2. Single-line truncation with ellipsis (e.g. a title in a card) */
.title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  /* Requires a constrained width AND min-width:0 if it's a flex item */
}

/* 3. Multi-line clamp: show N lines then ellipsis (preview text) */
.preview {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;         /* clamp to 3 lines */
  line-clamp: 3;                 /* standard property, increasingly supported */
  overflow: hidden;
}

/* 4. Flex/grid children must be allowed to shrink or they overflow the container */
.flex-child { min-width: 0; }    /* the #1 fix for "my flex item overflows" */
```

Unknown-length content is one of the most common sources of "it looked fine with my test data, then broke in production," so defensive CSS is a real skill. The four tools map to four scenarios. **`overflow-wrap: break-word`** (the modern name for `word-wrap`) lets the browser break a word *only when it's too long to fit a line* — preferable to `word-break: break-all`, which aggressively breaks *every* word mid-character and shreds normal text; reserve `break-all` for CJK or when even normal words must break. Adding `hyphens: auto` improves the result for languages with hyphenation dictionaries. This combination keeps a pasted 200-character URL from pushing a container wider than the viewport.

**Single-line ellipsis** needs the trio `white-space: nowrap` + `overflow: hidden` + `text-overflow: ellipsis`, *and* a width constraint — the classic gotcha is applying it to a **flex item**, which has `min-width: auto` and refuses to shrink, so the ellipsis never triggers and the item overflows instead; `min-width: 0` on the flex child is the fix and is the single most common answer to "why is my flex item overflowing." **Multi-line clamp** uses the `-webkit-line-clamp` mechanism (with the `-webkit-box` display) to cut text to N lines with a trailing ellipsis — long the only option and still the most compatible, though the standardized `line-clamp` is rolling out. It's perfect for comment/preview snippets where you want a fixed-height teaser.

The broader defensive posture I'd describe: assume *every* user-controlled string is hostile to your layout — set `min-width: 0` on flex/grid children that contain text, use `overflow-wrap: break-word` on any free-text container, constrain widths (`max-width`) so a giant string can't dictate layout, and test with deliberately abusive fixtures (a 500-char no-spaces string, an emoji-only name, RTL text, a very tall pasted block). Also guard the *vertical* axis — a giant pasted block can blow out a card's height, so clamp or `max-height` + `overflow: auto` where appropriate. **Edge cases:** ellipsis truncation hides content from sighted users, so ensure the full text is available (a `title` attribute, expand-on-click, or it remains in the DOM for screen readers — which read the *full* text regardless of visual clamp, which is usually desirable but worth knowing). **Time/Space:** O(1), pure CSS; the value is resilience, and the discipline is testing with adversarial content rather than tidy mock data.

#### Q84. [Theory] What are the accessibility and UX trade-offs of `outline: none`, and how do you build a focus-indicator strategy that satisfies both designers and WCAG?

Removing focus outlines (`*:focus { outline: none }` or `button:focus { outline: 0 }`) is one of the most damaging-yet-common CSS choices because it silently breaks keyboard accessibility. The focus indicator is the *only* way a keyboard or switch-device user knows where they are on the page; remove it and the page becomes unusable for them — they tab and nothing visibly moves. Designers dislike the default outline because it appears on **mouse clicks** too (an unwanted ring after clicking a button) and because the browser default ring may clash with the design. So the naive "fix" is to kill it globally, which trades a minor aesthetic annoyance for a **WCAG 2.4.7 (Focus Visible)** failure and a real exclusion of users — never an acceptable trade.

The modern resolution is **`:focus-visible`**, which exposes the browser's own heuristic for *when a focus indicator is warranted* — roughly, when the user is navigating by **keyboard** (or otherwise non-pointer), not when they clicked with a mouse. This gives designers exactly what they wanted (no ring on mouse click) while preserving the ring for keyboard users. The correct pattern is: never blanket-remove `:focus`; instead style `:focus-visible` deliberately, and only suppress the *default* outline when you're providing a *replacement* visible indicator.

```css
/* WRONG: removes focus for everyone, fails WCAG 2.4.7 */
button:focus { outline: none; }

/* RIGHT: suppress the default ONLY when replacing it, and key off :focus-visible */
button:focus { outline: none; }                 /* remove default (mouse) ring */
button:focus-visible {                           /* keyboard focus → strong indicator */
  outline: 2px solid var(--focus, #1a73e8);
  outline-offset: 2px;
}
/* A custom, on-brand indicator using box-shadow (animatable, follows border-radius) */
.btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px color-mix(in oklch, var(--brand), white 40%);
}
```

A robust focus strategy has several requirements beyond just "show something." **(1) Sufficient contrast and size**: WCAG 2.2 added **2.4.11 Focus Appearance**, which expects the focus indicator to have adequate area and contrast against both the focused control and the adjacent background — a faint 1px outline that barely shows fails it, so I use a 2–3px ring with `outline-offset` for separation. **(2) Don't rely on color alone** and ensure the ring is visible against *every* background the control sits on (a blue ring vanishes on a blue header). **(3) `outline` vs `box-shadow`**: `outline` doesn't follow `border-radius` in older engines and doesn't affect layout (good), while `box-shadow` follows rounded corners and can be animated/branded but is clipped by `overflow: hidden` ancestors — I choose per case, and `outline` now respects `border-radius` in modern browsers, making it the cleaner default. **(4) Honor `prefers-reduced-motion`** if the indicator animates, and respect Windows **High Contrast / forced-colors mode** (don't remove outlines that forced-colors relies on; test with `forced-colors: active`). **(5) Focus management**, not just styling — after a route change or modal open, move focus somewhere sensible so the indicator appears where the user expects.

The way I align designers and accessibility: reframe it as "we keep the clean look on mouse interaction *and* show a beautiful, on-brand focus ring for keyboard users" — `:focus-visible` makes that a non-compromise, and a branded `box-shadow`/`outline` token means the indicator matches the design system rather than the generic browser ring. I bake an accessible `--focus-ring` token into the design system so every component inherits a compliant, on-brand indicator by default, and I add an automated check (axe/lint rule flagging `outline: none` without a `:focus-visible` replacement) so the anti-pattern can't slip back in. The principle: focus visibility is a hard requirement, but with `:focus-visible` and a design token it's also fully compatible with a polished aesthetic — the false trade-off ("accessible *or* clean") is what leads teams to remove it, and the senior move is showing it's "accessible *and* clean."

## ✅ Key Takeaways

- **Default to semantic HTML and native elements** — they bring accessibility, keyboard support, and SEO for free; ARIA is a last resort, not a starting point.
- **Use `box-sizing: border-box` everywhere** and keep specificity flat; reach for `@layer` and `:where()` instead of IDs and `!important` to control priority.
- **Flexbox for 1D, Grid for 2D**; `repeat(auto-fit, minmax())` and `clamp()` deliver responsiveness with far fewer media queries.
- **Performance lives in the rendering path**: animate `transform`/`opacity` (composite-only), avoid layout thrashing by batching DOM reads and writes, and reserve space to protect CLS.
- **Modern CSS (2023–2026)** — container queries, `:has()`, cascade layers, nesting, `@scope`, logical properties, the Popover/`<dialog>` top layer — replaces a lot of former JavaScript and hacks; gate adoption on Baseline support.
- **At scale, encapsulation and tokens beat discipline**: Shadow DOM/`@scope`, design tokens, and a shared component library prevent cross-team breakage better than code review alone.

## ⚠️ Common Pitfalls

- Clickable `<div>`/`<span>` instead of `<button>`/`<a>` — breaks keyboard, focus, and screen-reader support.
- Forgetting image `width`/`height` (or `aspect-ratio`), causing layout shift and a poor CLS score.
- Animating `width`, `height`, `top`, or `left` instead of `transform` — forces reflow and drops frames.
- `z-index` not working because an ancestor's `opacity`, `transform`, or `filter` silently created a new stacking context.
- `position: sticky` or `fixed` failing because an ancestor has `overflow: hidden` or a `transform`.
- Using physical properties (`margin-left`) instead of logical ones (`margin-inline-start`), breaking RTL layouts.
- `!important` and ID selectors escalating specificity wars instead of using cascade layers.
- Reading layout (`offsetHeight`, `getBoundingClientRect`) in a loop while mutating the DOM — layout thrashing.
- Putting `aria-hidden="true"` on a focusable element, creating an invisible focus trap.

## 📚 Further Reading

- **MDN Web Docs** — CSS and HTML references (the authoritative, continuously updated source): https://developer.mozilla.org
- **web.dev** (Google) — Learn CSS, Learn Accessibility, and Core Web Vitals guides: https://web.dev
- **WAI-ARIA Authoring Practices Guide (APG)** — canonical patterns for accessible widgets: https://www.w3.org/WAI/ARIA/apg/
- **"CSS: The Definitive Guide"** by Eric Meyer & Estelle Weyl (O'Reilly) — deep reference on the box model, cascade, and layout.
- **"Refactoring UI"** by Adam Wathan & Steve Schoger — practical visual design and spacing for engineers.
- **"Every Layout"** by Heydon Pickering & Andy Bell — resilient, intrinsic layout primitives with CSS.
- **Baseline / caniuse.com** — check cross-browser support before adopting modern features.
