# Web Performance & Browser Internals

[← Back to master index](../README.md)

A practitioner's interview guide to making web pages fast: how the browser turns bytes into pixels (the critical rendering path), how Google's Core Web Vitals measure real-user experience, and the concrete techniques — caching, code splitting, image optimization, resource hints, virtualization — that move the numbers. Knowledge is current through 2026, including the INP metric that replaced FID in March 2024.

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

### Q1. [Theory] What are the Core Web Vitals, and what does each measure?

Core Web Vitals (CWV) are the three user-centric metrics Google uses as the headline measure of real-world page experience. They are designed to capture three distinct dimensions of how a page *feels*: loading, interactivity, and visual stability.

```
Metric   Measures            "Good" threshold (75th percentile of real users)
------   -----------------   -----------------------------------------------
LCP      Loading             ≤ 2.5 s
INP      Interactivity       ≤ 200 ms   (replaced FID in March 2024)
CLS      Visual stability    ≤ 0.1      (unitless)
```

- **LCP (Largest Contentful Paint)** — time until the largest image or text block in the viewport is rendered. A proxy for "the page looks loaded."
- **INP (Interaction to Next Paint)** — measures responsiveness across *all* interactions during the page's life, reporting (roughly) the worst latency from a user input to the next visual update.
- **CLS (Cumulative Layout Shift)** — sums up unexpected layout shifts; high CLS is the "the button jumped and I clicked the wrong thing" feeling.

A page passes Core Web Vitals only if all three are in the "good" band at the 75th percentile of real user visits. These are field metrics — measured on actual users, not in a lab.

### Q2. [Theory] What is the critical rendering path?

The **critical rendering path (CRP)** is the sequence of steps the browser must complete to turn HTML, CSS, and JavaScript into rendered pixels on screen. Optimizing performance is largely about shortening this path.

```
HTML  ──parse──►  DOM  ─┐
                        ├──►  Render Tree  ──►  Layout  ──►  Paint  ──►  Composite
CSS   ──parse──►  CSSOM ┘     (visible nodes   (geometry:   (pixels:   (GPU layers
                              + styles)         x,y,w,h)     colors)     to screen)
```

1. **Parse HTML → DOM** (Document Object Model): a tree of element nodes.
2. **Parse CSS → CSSOM** (CSS Object Model): a tree of style rules.
3. **DOM + CSSOM → Render Tree**: only the nodes that will be displayed, each with its computed style (e.g. `display:none` nodes are excluded).
4. **Layout (reflow)**: compute the exact size and position of every node.
5. **Paint**: fill in pixels — text, colors, borders, shadows.
6. **Composite**: combine painted layers in the right order, often on the GPU.

The key interview insight: **CSS is render-blocking** (the render tree needs the CSSOM) and **synchronous JS is parser-blocking** (it can modify the DOM/CSSOM, so the parser must wait). Minimizing and prioritizing what's on this path is the heart of load performance.

### Q3. [Theory] What is the difference between the DOM, CSSOM, and the render tree?

- **DOM** — the object representation of the parsed HTML. Every element, attribute, and text node becomes a node. It includes nodes that are *not* visible (e.g. `<head>`, `display:none` elements).
- **CSSOM** — the object representation of all CSS (external, internal, inline, and user-agent defaults), resolved into computed styles per node.
- **Render tree** — the combination of the two, containing **only the nodes that will actually be painted**, each annotated with its visual style. Nodes with `display:none` are omitted entirely; nodes with `visibility:hidden` are *included* (they occupy space) but painted invisibly.

The render tree is what feeds Layout and Paint. The distinction matters because `display:none` removes a node from the render tree (no layout cost), while `visibility:hidden` keeps it.

### Q4. [Theory] What is the difference between reflow and repaint?

- **Reflow (layout)** — recalculating the geometry (position and size) of elements. Triggered by anything that changes layout: adding/removing DOM nodes, changing `width`/`height`/`margin`/`padding`/`font-size`, reading `offsetHeight`, resizing the window. Reflow is **expensive** because it can cascade — changing one element may shift its siblings, children, and ancestors.
- **Repaint** — redrawing pixels *without* changing layout. Triggered by visual-only changes: `color`, `background-color`, `visibility`, `box-shadow`. Cheaper than reflow, but still costs.

```
Cost (roughly):  composite-only  <  repaint  <  reflow
Cheapest props:  transform, opacity   (GPU-composited, skip layout AND paint)
```

The performance rule of thumb: animate with `transform` and `opacity` because they can be handled purely on the compositor thread, skipping both reflow and repaint. Animating `top`/`left`/`width` forces reflow on every frame and is the classic cause of janky animations.

### Q5. [Practical] How do render-blocking resources hurt performance, and how do you fix them?

A **render-blocking resource** prevents the browser from painting anything until it's downloaded and processed. The two big ones are **CSS in `<head>`** (blocks rendering because the render tree needs the CSSOM) and **synchronous `<script>` in `<head>`** (blocks the HTML parser).

Fixes:

```html
<!-- Synchronous: blocks the parser. Avoid in <head>. -->
<script src="app.js"></script>

<!-- defer: download in parallel, execute after HTML is parsed, in order. -->
<script src="app.js" defer></script>

<!-- async: download in parallel, execute as soon as ready (order not guaranteed).
     Good for independent scripts like analytics. -->
<script src="analytics.js" async></script>

<!-- Inline critical CSS, then load the rest non-blocking: -->
<style>/* above-the-fold critical CSS */</style>
<link rel="preload" href="full.css" as="style" onload="this.rel='stylesheet'">
```

```
Parser timeline:
  <script src>          [parse]──STOP──[fetch+exec]──[parse]
  <script defer>        [parse────────────────────]──[exec]
  <script async>        [parse──]──[exec]──[parse]   (interrupts whenever ready)
```

Best practices: put `<script>` with `defer` (or at the end of `<body>`), inline above-the-fold critical CSS, and lazy-load the rest. This lets the browser paint meaningful content sooner, improving FCP and LCP.

### Q6. [Theory] What is TTFB and what does it tell you?

**TTFB (Time To First Byte)** is the time from the start of a navigation request to the moment the first byte of the response arrives. It rolls up several phases:

```
TTFB = DNS lookup + TCP connect + TLS handshake + request send + server "think time" + first byte
```

TTFB is your **server and network** signal — it's everything *before* the browser has any HTML to work with. A high TTFB (say > 800 ms) points at slow backend processing, a missing/cold CDN, slow database queries, or no caching. Because LCP can't happen until the document arrives, a bloated TTFB sets a floor under every downstream metric. Common fixes: a CDN to terminate connections near the user, server-side caching, HTTP/2 or HTTP/3, edge rendering, and keeping initial HTML small.

### Q7. [Theory] What is lazy loading and when should you use it?

**Lazy loading** defers loading a resource until it's actually needed — typically when it's about to enter the viewport. It saves bandwidth and speeds up the initial load by not fetching things the user may never scroll to.

```html
<!-- Native image lazy loading — supported in all modern browsers -->
<img src="photo.jpg" loading="lazy" width="800" height="600" alt="...">

<!-- Native iframe lazy loading -->
<iframe src="map.html" loading="lazy"></iframe>
```

Key rule: **never lazy-load your LCP image / above-the-fold content.** `loading="lazy"` on the hero image delays the very thing LCP measures, hurting your score. Lazy load below-the-fold images, offscreen iframes (maps, embeds), and route-level JS bundles. Always set explicit `width`/`height` (or `aspect-ratio`) so lazy images don't cause layout shift when they pop in.

### Q8. [Practical] How does HTTP caching work with Cache-Control and ETag?

HTTP caching lets the browser (and intermediaries) reuse responses instead of refetching. Two mechanisms work together: **freshness** (`Cache-Control`) and **validation** (`ETag`/`Last-Modified`).

```http
# Freshness: serve from cache for 1 year without contacting the server.
Cache-Control: public, max-age=31536000, immutable

# Validation: must revalidate every time, but skip the body if unchanged.
Cache-Control: no-cache
ETag: "abc123"
```

```
Fresh (within max-age):    browser serves from cache      → 0 network
Stale + ETag:              browser sends If-None-Match     → 304 Not Modified (no body)
                           or 200 with new body if changed
```

The standard production pattern is **cache-busting with content hashes**: name files `app.4f3a2b.js` and serve them `Cache-Control: immutable, max-age=31536000`. When the content changes, the hash (and thus the URL) changes, so the browser fetches a fresh file — you get aggressive caching with instant invalidation. HTML itself is usually `no-cache` (always revalidated) so users get new asset references promptly.

### Q9. [Practical] What are debounce and throttle, and when do you use each?

Both limit how often a function runs in response to rapid events, but differently:

- **Debounce** — wait until events *stop* for N ms, then run once. Use for: search-as-you-type, resize-then-recalculate, autosave.
- **Throttle** — run at most once per N ms while events keep firing. Use for: scroll handlers, mousemove, drag, infinite-scroll triggers.

```js
function debounce(fn, delay) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function throttle(fn, limit) {
  let waiting = false;
  return (...args) => {
    if (waiting) return;
    fn(...args);
    waiting = true;
    setTimeout(() => (waiting = false), limit);
  };
}

// Search box: only fire after the user pauses typing for 300 ms.
input.addEventListener('input', debounce(e => search(e.target.value), 300));

// Scroll: react at most every 100 ms.
window.addEventListener('scroll', throttle(onScroll, 100));
```

```
Events:   x x x x x          x x x x
Debounce: ............|run    .........|run   (fires after the burst ends)
Throttle: |run...|run...|run  |run...|run     (fires on a fixed cadence)
```

These reduce wasted work, prevent main-thread saturation, and directly help INP by keeping handlers from piling up.

### Q10. [Theory] What is minification and why does it help?

**Minification** removes everything from source code that the parser doesn't need: whitespace, comments, and (for JS) long variable names get shortened to single letters. The result is byte-for-byte smaller files that download and parse faster.

```js
// Before (readable source)
function calculateTotalPrice(itemPrice, quantity) {
  const subtotal = itemPrice * quantity;   // multiply
  return subtotal;
}

// After (minified)
function c(a,b){return a*b}
```

Minification is purely a build step (Terser for JS, cssnano/Lightning CSS for CSS, html-minifier for HTML) and pairs with compression (gzip/brotli) on the wire. Minification reduces the *uncompressed* size (helping parse time), while compression reduces the *transferred* size — they stack. Modern bundlers do this automatically in production mode.

### Q11. [Theory] What is a CDN and how does it improve performance?

A **CDN (Content Delivery Network)** is a geographically distributed network of edge servers that cache and serve your content from a location physically close to each user.

```
Without CDN:  User (Sydney) ──── 15,000 km ────► Origin (Virginia)   high latency
With CDN:     User (Sydney) ── 50 km ► Edge (Sydney, cached) ──► Origin (only on miss)
```

Benefits:
- **Lower latency** — shorter physical distance means faster round trips (the speed of light is a hard limit).
- **Offloaded origin** — cached responses never hit your servers, reducing load and TTFB.
- **Connection termination at the edge** — TLS handshakes complete near the user.
- **Resilience and scale** — absorbs traffic spikes and DDoS; provides redundancy.

CDNs cache static assets (JS, CSS, images, fonts) by default and can also cache HTML and do edge compute. They're foundational to good TTFB and LCP for a global audience.

### Q12. [Theory] What is the difference between gzip and brotli compression?

Both are lossless compression algorithms applied to text-based responses (HTML, CSS, JS, JSON, SVG) before transfer. The browser advertises support via `Accept-Encoding` and the server responds with `Content-Encoding`.

```
Algorithm   Typical ratio vs raw   Notes
---------   --------------------   ---------------------------------------------
gzip        ~70% smaller           Universal support, fast, the baseline
brotli      ~75–80% smaller        Better ratio (esp. at high levels), built-in
                                    dictionary; standard for static assets in 2026
```

Brotli usually wins on compression ratio, especially for text, because of its shared dictionary and better entropy coding. The catch: high brotli levels (10–11) are slow to compress, so they're best done **at build time** for static assets. For dynamic responses, use a lower brotli level or gzip to avoid CPU latency. Images and already-compressed formats (PNG, JPEG, WebP, video) should **not** be re-compressed — it wastes CPU and barely shrinks them.

### Q13. [Practical] How do you optimize images for the web?

Images are usually the largest part of a page's weight and the most common LCP element, so image optimization has outsized impact. Four levers:

1. **Modern formats** — prefer **AVIF** (best compression), then **WebP**, falling back to JPEG/PNG. Use `<picture>` for fallbacks:

```html
<picture>
  <source srcset="hero.avif" type="image/avif">
  <source srcset="hero.webp" type="image/webp">
  <img src="hero.jpg" alt="..." width="1200" height="600">
</picture>
```

2. **Responsive images** — serve appropriately sized images per device with `srcset`/`sizes` so phones don't download desktop-sized files:

```html
<img src="img-800.jpg"
     srcset="img-400.jpg 400w, img-800.jpg 800w, img-1600.jpg 1600w"
     sizes="(max-width: 600px) 400px, 800px"
     alt="..." width="800" height="600">
```

3. **Lazy load** offscreen images with `loading="lazy"` (but never the LCP image).
4. **Prevent layout shift** — always set `width`/`height` or `aspect-ratio` so the browser reserves space (protects CLS), and use `fetchpriority="high"` on the LCP image to load it sooner.

### Q14. [Theory] What is the difference between preload, prefetch, and preconnect?

These are **resource hints** that tell the browser to do work ahead of time. They target different priorities and use cases:

```html
<!-- preload: fetch a resource needed for THIS page, with high priority. -->
<link rel="preload" href="hero.woff2" as="font" type="font/woff2" crossorigin>

<!-- prefetch: fetch a resource likely needed for a FUTURE navigation, low priority. -->
<link rel="prefetch" href="/next-page.js">

<!-- preconnect: warm up the connection (DNS + TCP + TLS) to a third-party origin. -->
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>

<!-- dns-prefetch: just the DNS lookup; lighter-weight fallback for preconnect. -->
<link rel="dns-prefetch" href="https://api.example.com">
```

```
preload    → "I need this NOW for the current page"      (high priority fetch)
prefetch   → "I'll probably need this for the NEXT page"  (idle-time fetch)
preconnect → "set up the pipe to this origin in advance"  (saves handshake time)
```

Use **preload** for critical late-discovered resources (a font referenced in CSS, the LCP image). Use **preconnect** for known third-party origins (fonts, CDN, API). Use **prefetch** to speculatively load the next likely route. Overusing preload backfires — it competes for bandwidth with genuinely critical resources.

### Q15. [Theory] What is the difference between client-side rendering (CSR) and server-side rendering (SSR)?

- **CSR** — the server sends a near-empty HTML shell plus a JS bundle; the browser downloads and runs the JS, which fetches data and builds the DOM. The user sees a blank screen until the bundle executes.
- **SSR** — the server renders the full HTML for the page and sends it ready-to-display; the browser shows content immediately, then "hydrates" it with JS to make it interactive.

```
CSR:  [shell] → download JS → run JS → fetch data → render   (slow first paint)
SSR:  [full HTML] → paint immediately → hydrate                (fast first paint)
```

SSR generally wins on **FCP/LCP** and SEO because content arrives in the initial HTML. CSR can feel snappier *after* load (no server round trip per navigation) and is simpler to host (static files). The tradeoff is SSR's hydration cost and server compute. Modern frameworks (Next.js, Remix, Astro, Angular SSR) blend these with streaming, partial hydration, and static generation.

---

## 🟡 Intermediate (3–7 yrs)

### Q16. [Theory] What is code splitting and how does it improve performance?

**Code splitting** breaks a large JS bundle into smaller chunks that load on demand, so the user only downloads the code needed for the current view instead of the entire app upfront. This shrinks the initial bundle, speeding up parse/compile/execute time and improving FCP, LCP, and INP.

```js
// Route-based splitting with dynamic import() — the standard pattern.
const Dashboard = React.lazy(() => import('./Dashboard'));

// The bundler emits Dashboard as a separate chunk, fetched only when rendered.
<Suspense fallback={<Spinner />}>
  <Dashboard />
</Suspense>

// Component/feature-level splitting: load a heavy editor only when opened.
button.addEventListener('click', async () => {
  const { Editor } = await import('./HeavyEditor.js');
  new Editor().mount();
});
```

```
Monolith:    main.js [================ 800 KB ================]  parse all upfront
Split:       main.js [== 150 KB ==]  +  dashboard.js (on route)
                                     +  editor.js   (on click)
```

The two main strategies are **route-based** (split per page) and **component-based** (split heavy/rarely-used widgets). The dynamic `import()` is the underlying primitive that bundlers like Webpack, Vite, and esbuild key off.

### Q17. [Theory] What is tree shaking and what does it require to work?

**Tree shaking** is dead-code elimination at the module level: the bundler statically analyzes your import graph and drops exports that are never used, so unused library code doesn't ship.

```js
// utils.js
export function used() { /* ... */ }
export function neverImported() { /* huge, but dropped by tree shaking */ }

// app.js
import { used } from './utils.js';   // only `used` ends up in the bundle
```

Requirements for it to work:
1. **ES modules (`import`/`export`)** — tree shaking relies on the *static* structure of ESM. CommonJS (`require`) is dynamic and can't be reliably shaken.
2. **No (or marked) side effects** — set `"sideEffects": false` in `package.json`, or list the files that do have side effects (like CSS imports). This lets the bundler safely drop unused modules.
3. **Avoid patterns that defeat static analysis** — re-exporting everything, namespace imports used dynamically, or libraries that aren't ESM-friendly.

The payoff: importing `{ debounce } from 'lodash-es'` ships ~2 KB instead of the whole library. Always prefer ESM builds of dependencies for this reason.

### Q18. [Theory] What replaced FID with INP, and why?

In March 2024, **INP (Interaction to Next Paint)** officially replaced **FID (First Input Delay)** as the Core Web Vitals responsiveness metric.

- **FID** only measured the *input delay* of the **first** interaction — the time before the handler *started*. It ignored how long the handler took to run and how long the *rest* of the page's life took to respond. It was easy to pass while still feeling sluggish.
- **INP** measures the **full latency** (input delay + processing time + presentation delay) of **all** interactions throughout the page's lifetime, and reports a representative high value (close to the worst). It captures the responsiveness of the *whole session*, not just the opening moment.

```
FID:  | input delay |  (only this, only the first click)
INP:  | input delay | processing | presentation |   (all of it, every interaction)
```

INP is a much truer measure of "does this page respond when I click things?" The practical consequence: you now optimize *every* interaction — break up long tasks, yield to the main thread, defer non-urgent work — not just the first.

### Q19. [Practical] How do you diagnose and improve a poor LCP score?

LCP is the time to render the largest content element in the viewport. Improvement starts by identifying *which phase* is slow. LCP decomposes into four subparts:

```
LCP = TTFB + Resource load delay + Resource load time + Element render delay
      ^^^^   ^^^^^^^^^^^^^^^^^^^   ^^^^^^^^^^^^^^^^^^   ^^^^^^^^^^^^^^^^^^^^
      server  time until the       download time of    time from loaded to
              LCP resource starts   the LCP resource    actually painted
              loading
```

Tactics per phase:
- **High TTFB** → CDN, server caching, edge rendering, smaller HTML.
- **Load delay** (resource discovered late) → `<link rel="preload">` the LCP image, set `fetchpriority="high"`, avoid lazy-loading it, reference it in the initial HTML (not injected by JS).
- **Load time** → optimize the image (AVIF/WebP, responsive sizes), compress, serve from CDN.
- **Render delay** → reduce render-blocking CSS/JS, inline critical CSS, avoid blocking the main thread.

```js
// Measure LCP in the field with the web-vitals library.
import { onLCP } from 'web-vitals';
onLCP(metric => console.log('LCP', metric.value, metric.attribution?.element));
```

The single highest-leverage fix is usually getting the LCP image discovered and prioritized early (`preload` + `fetchpriority="high"`).

### Q20. [Practical] How do you diagnose and fix Cumulative Layout Shift (CLS)?

CLS sums *unexpected* layout shifts — content moving after it's already been painted. Each shift's score is `impact fraction × distance fraction`. The common culprits and fixes:

1. **Images/videos without dimensions** → always set `width`/`height` or `aspect-ratio` so the browser reserves space:

```css
img { aspect-ratio: 16 / 9; width: 100%; height: auto; }
```

2. **Ads/embeds/iframes that load late** → reserve a fixed-size container up front.
3. **Web fonts causing FOUT/FOIT reflow** → use `font-display: optional` or `swap` with a well-matched fallback and `size-adjust` to minimize the shift when the web font swaps in.
4. **Dynamically injected content** (banners, cookie notices) → insert it in reserved space or above the viewport, never pushing existing content down.
5. **Animating layout properties** → animate `transform`, not `top`/`height`.

```
Bad:   [text]                Good:  [text]
       (image loads)                [reserved box]
       [text shifts DOWN] ✗         (image fills box, no shift) ✓
```

Measure with the Web Vitals extension or `onCLS` from the web-vitals library, which reports the largest shifting elements so you can target them.

### Q21. [Practical] What is list virtualization (windowing) and when do you need it?

**Virtualization** renders only the DOM nodes currently visible in the viewport (plus a small buffer), instead of rendering every item in a long list. For a 10,000-row list, you might have only ~20 DOM nodes at any time.

```
Full render:        [row 0]...[row 9999]   10,000 DOM nodes → slow layout, huge memory
Virtualized:        [spacer top]
                    [row 142][row 143]...[row 161]   ~20 nodes in the "window"
                    [spacer bottom]
```

```js
// Conceptual core: compute which slice of items is visible from scrollTop.
function getVisibleRange(scrollTop, rowHeight, viewportHeight, total, overscan = 5) {
  const start = Math.max(0, Math.floor(scrollTop / rowHeight) - overscan);
  const visibleCount = Math.ceil(viewportHeight / rowHeight) + overscan * 2;
  return { start, end: Math.min(total, start + visibleCount) };
}
// Render only items[start..end], and pad with spacer divs so the scrollbar
// stays the correct total height.
```

You need it when lists/tables/grids grow into the thousands, where the DOM node count itself becomes the bottleneck (memory, layout, paint, and slow scroll). Libraries: TanStack Virtual, react-window, react-virtuoso. The cost is complexity — variable row heights, scroll restoration, and accessibility (screen readers, Ctrl+F) need care.

### Q22. [Practical] How does a service worker enable caching, and what strategies exist?

A **service worker** is a script that runs in a separate thread and sits between the page and the network as a programmable proxy. It can intercept `fetch` events and serve responses from the Cache Storage API — enabling offline support and fine-grained caching control beyond HTTP caching.

```js
// Cache-first: serve from cache, fall back to network (great for static assets).
self.addEventListener('fetch', event => {
  event.respondWith(
    caches.match(event.request).then(cached =>
      cached || fetch(event.request)
    )
  );
});
```

Common strategies:
```
Cache-first             cache → network    static assets, fonts (fast, may be stale)
Network-first           network → cache    API data, HTML (fresh, offline fallback)
Stale-while-revalidate  cache now,         best of both: instant + refresh in bg
                        update in bg
Cache-only / Network-only                  precached app shell / non-cacheable calls
```

**Stale-while-revalidate** is the workhorse: return the cached copy immediately for speed, then fetch a fresh copy in the background for next time. Tooling like Workbox abstracts these. Pitfalls: cache versioning/invalidation, the SW update lifecycle (a new SW waits until all tabs close by default), and never caching responses you can't safely serve stale.

### Q23. [Practical] What is the difference between bundling and tree shaking and minification?

These are three distinct build-time optimizations that work together — interviewers ask this to check you don't conflate them:

```
Bundling       Combine many modules/files into fewer files.
               Goal: fewer requests, manage the dependency graph.

Tree shaking   Remove exports/modules that are never imported (dead code).
               Goal: drop unused code paths entirely.

Minification   Shrink the remaining code (whitespace, comments, rename vars).
               Goal: fewer bytes for the same code.
```

```
Source modules ──bundle──► one graph ──tree-shake──► used code only ──minify──► tiny output
                                                              └──► + compression (gzip/brotli) on the wire
```

Order conceptually: bundle and resolve the graph, shake out dead code, minify what remains, then compress in transit. A modern toolchain (Vite/Rollup, Webpack, esbuild) does all of this in one production build. Confusing minification ("shrink the bytes") with tree shaking ("delete unused branches") is a common red flag.

### Q24. [Theory] What is hydration and why is it a performance concern?

**Hydration** is the process where client-side JS "attaches" to server-rendered HTML: it re-runs the framework's render, rebuilds the component tree in memory, and wires up event listeners so the static markup becomes interactive.

```
SSR sends HTML  →  user SEES content (fast FCP/LCP)
                →  but it's NOT interactive yet (clicks do nothing)
JS downloads    →  hydration runs  →  NOW interactive (INP clock matters here)
                   ^^^^^^^^^^^^^^^
                   This gap is the "uncanny valley" of SSR.
```

The cost: hydration re-does work the server already did, blocks the main thread (hurting INP / TBT), and requires downloading the full JS bundle. A page can *look* ready but be unresponsive — the classic "I clicked and nothing happened." Modern mitigations:
- **Partial / selective hydration** — hydrate only interactive components.
- **Islands architecture** (Astro) — ship JS only for interactive "islands," static HTML elsewhere.
- **Progressive/streaming hydration** — hydrate visible/important parts first.
- **Resumability** (Qwik) — serialize state so the client *resumes* instead of re-executing, avoiding hydration almost entirely.
- **React Server Components** — render non-interactive components on the server with zero client JS.

### Q25. [Practical] How do you measure web performance in the lab and in the field?

You need both: **lab** (controlled, reproducible, for debugging) and **field/RUM** (real users, the source of truth for Core Web Vitals).

```
LAB (synthetic)                       FIELD (Real User Monitoring)
-------------------------------       -------------------------------------
Lighthouse / PageSpeed Insights       web-vitals JS library → your analytics
Chrome DevTools Performance panel      Chrome User Experience Report (CrUX)
WebPageTest                            PageSpeed Insights (shows CrUX data)
Pros: reproducible, deep traces        Pros: real devices/networks, what Google ranks
Cons: one device/network, may differ   Cons: noisy, can't step through
      from real users
```

```js
// Field measurement: send each CWV to your analytics endpoint.
import { onLCP, onINP, onCLS, onTTFB, onFCP } from 'web-vitals';
function report(metric) {
  navigator.sendBeacon('/analytics', JSON.stringify(metric));
}
[onLCP, onINP, onCLS, onTTFB, onFCP].forEach(fn => fn(report));
```

Golden rule: **lab metrics are for diagnosing, field metrics are for judging.** A great Lighthouse score on your fast laptop means nothing if real users on mid-tier phones over 4G are struggling. Use the DevTools Performance panel (with CPU/network throttling) to find *why* something is slow, and RUM to know *whether* it's actually slow for users.

### Q26. [Theory] Why is JavaScript often the most expensive resource, byte for byte?

Unlike an image, which is just decoded and painted, JavaScript must be **downloaded, parsed, compiled, and executed** — and execution happens on the main thread, blocking everything else.

```
Image (100 KB):  download → decode → paint                (cheap, off main thread)
JS (100 KB):     download → parse → compile → execute     (all on the main thread)
```

100 KB of JS costs far more than 100 KB of image because:
- **Parse + compile** scale with code size and happen on the main thread.
- **Execution** runs your framework boot, data fetching, and hydration — also main-thread.
- On a **mid-tier mobile CPU**, parse/compile/execute can be 4–8× slower than a developer's laptop, so JS-heavy pages hit real users hardest.
- It directly inflates **TBT** (lab) and **INP** (field) by occupying the main thread with long tasks.

This is why the modern performance ethos is "ship less JavaScript": code splitting, tree shaking, server components, and islands all aim to reduce the amount of JS the main thread must process.

### Q27. [Practical] What is a long task and how do you break one up to help INP?

A **long task** is any block of main-thread work that runs for **more than 50 ms**, during which the page can't respond to input or paint. Long tasks are the primary cause of poor INP and high TBT.

```
Main thread:  [-------- long task 180 ms --------] (click ignored this whole time)
              user clicks here ↑           paint here ↑   → 180 ms INP, feels frozen
```

To fix, break the work into smaller chunks and **yield to the main thread** so the browser can handle input and paint between chunks:

```js
// Yield helper using the modern scheduler API (falls back to setTimeout).
function yieldToMain() {
  if ('scheduler' in window && scheduler.yield) return scheduler.yield();
  return new Promise(resolve => setTimeout(resolve, 0));
}

async function processLargeArray(items) {
  for (let i = 0; i < items.length; i++) {
    doWork(items[i]);
    if (i % 100 === 0) await yieldToMain();  // let the browser breathe
  }
}
```

Other techniques: move heavy computation to a **Web Worker** (off the main thread entirely), use `requestIdleCallback` for non-urgent work, debounce/throttle handlers, and split rendering so input handlers return quickly and visual updates happen on the next frame.

### Q28. [Theory] What is the difference between FCP, LCP, and TTI?

These are three points on the loading timeline, often confused:

```
Navigation ──► TTFB ──► FCP ──────► LCP ──────► TTI
                       (first       (largest     (reliably
                        pixel)       element)     interactive)
```

- **FCP (First Contentful Paint)** — when *any* content (text, image, SVG) first paints. The user knows something is happening.
- **LCP (Largest Contentful Paint)** — when the *largest* viewport element renders. The user perceives the main content as loaded. (A Core Web Vital.)
- **TTI (Time To Interactive)** — when the page has displayed content *and* the main thread is quiet enough to reliably handle input. (No longer a Core Web Vital; INP superseded the interactivity story, and TBT is the lab proxy.)

The relationship: FCP ≤ LCP always (the largest element can't paint before the first). A page can have a fast LCP but poor TTI/INP if heavy JS keeps the main thread busy — looks loaded, feels frozen.

### Q29. [Practical] How would you optimize web font loading?

Web fonts are a common source of both layout shift and delayed text rendering. A robust strategy:

```css
@font-face {
  font-family: 'Inter';
  src: url('/fonts/inter.woff2') format('woff2');  /* woff2: best compression */
  font-display: swap;     /* show fallback immediately, swap when font loads */
  font-weight: 100 900;   /* one variable font file covers all weights */
}
```

```html
<!-- Preload the critical font so it's discovered early (it's referenced in CSS). -->
<link rel="preload" href="/fonts/inter.woff2" as="font" type="font/woff2" crossorigin>
<!-- Warm the connection if fonts come from a third-party origin. -->
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
```

Key points:
- **Use WOFF2** — the most compressed font format, universal support.
- **Subset** fonts to the characters/languages you actually use.
- **`font-display`** controls the swap behavior: `swap` (show fallback, then swap — risks CLS), `optional` (use the font only if it's fast — best for CLS), `fallback` (compromise).
- **Match the fallback metrics** with `size-adjust`, `ascent-override`, etc. so the swap doesn't shift layout.
- **Self-host** when possible to avoid third-party connection cost and the `crossorigin` requirement nuances.

### Q30. [Theory] What is HTTP/2 and HTTP/3, and how do they improve performance?

Both are newer transport protocols that fix HTTP/1.1's limitations:

- **HTTP/1.1** — one request per connection at a time; browsers open ~6 parallel connections per origin (head-of-line blocking, connection overhead). This is why we used to "bundle everything" and "domain shard."
- **HTTP/2** — **multiplexing**: many requests/responses share one connection concurrently. Adds header compression (HPACK) and server push (now largely deprecated). Reduces the cost of many small files, weakening the case for aggressive bundling.
- **HTTP/3** — runs over **QUIC (UDP)** instead of TCP. Eliminates **TCP head-of-line blocking** (a lost packet no longer stalls all streams), has a faster connection setup (0-RTT/1-RTT combining transport + TLS), and survives network changes (connection migration — switching Wi-Fi to cellular without reconnecting).

```
HTTP/1.1:  [req1][resp1][req2][resp2]   serial per connection
HTTP/2:    [req1,req2,req3 multiplexed over ONE TCP connection]   TCP HoL still bites
HTTP/3:    [streams over QUIC/UDP]   independent streams, no TCP HoL, fast handshake
```

Practical impact: with HTTP/2/3, many small cacheable chunks are cheap, so granular code splitting and long-term caching pay off without the old per-request penalty.

---

## 🟠 Advanced (8–12 yrs)

### Q31. [Practical] Walk through how you'd performance-budget and enforce it in CI.

A **performance budget** is a set of quantitative limits (on metrics and/or resource sizes) that the team agrees not to exceed, enforced automatically so regressions are caught before they ship.

```
Budget categories:
  Metric-based:    LCP < 2.5s, INP < 200ms, CLS < 0.1, TBT < 200ms
  Quantity-based:  total JS < 170 KB gzipped, images < 500 KB, requests < 50
  Rule-based:      no render-blocking 3rd-party scripts in <head>
```

Enforcement pipeline:
1. **Bundle-size gates** — `size-limit` or `bundlesize` fail the PR if a bundle grows past its cap. Cheap, deterministic, runs on every PR.
2. **Lighthouse CI** — run Lighthouse on a preview deployment, assert score/metric thresholds, and surface a report comment on the PR.
3. **Field RUM dashboards** — track real CWV (via CrUX or your own web-vitals beacon) and alert on regressions in production.

```yaml
# Lighthouse CI assertion sketch
assert:
  assertions:
    largest-contentful-paint: ["error", { maxNumericValue: 2500 }]
    total-blocking-time:      ["error", { maxNumericValue: 200 }]
    cumulative-layout-shift:  ["error", { maxNumericValue: 0.1 }]
```

The cultural piece matters as much as the tooling: budgets must be visible, owned, and treated as a build failure — not a dashboard nobody looks at. Lab gates catch regressions early; field RUM confirms real-user impact and catches what synthetic tests miss (real devices, networks, geographies).

### Q32. [Theory] Explain forced synchronous layout (layout thrashing) and how to avoid it.

**Forced synchronous layout** (a.k.a. layout thrashing) happens when JS *reads* a layout property (forcing the browser to flush a pending reflow to give an up-to-date answer) right after *writing* one — repeatedly, in a loop. Each read invalidated by a prior write forces a synchronous recalculation.

```js
// BAD: read → write → read → write ... forces layout on every iteration. O(n) reflows.
for (const el of boxes) {
  const w = el.offsetWidth;      // READ: forces layout flush
  el.style.width = w + 10 + 'px'; // WRITE: invalidates layout
}

// GOOD: batch all reads, then all writes. One layout flush total.
const widths = boxes.map(el => el.offsetWidth);  // all READS
boxes.forEach((el, i) => {                        // all WRITES
  el.style.width = widths[i] + 10 + 'px';
});
```

```
Thrash:  R W R W R W R W   →  layout flush on every R   (n reflows)
Batched: R R R R | W W W W →  one flush before reads     (1 reflow)
```

The fix is the **read-then-write** pattern: separate the measure phase from the mutate phase. Properties that force layout when read include `offsetTop/Width/Height`, `getBoundingClientRect()`, `getComputedStyle()`, `scrollTop`, etc. Libraries like FastDOM formalize batching reads and writes into separate animation-frame phases. This is a top cause of jank that profilers flag as "Recalculate Style / Layout" spikes.

### Q33. [Theory] How does the browser's compositor thread enable smooth 60fps animations?

Modern browsers split rendering across threads. The **main thread** runs JS, style, layout, and paint; the **compositor thread** (with the GPU) assembles painted layers into the final frame. Crucially, the compositor can transform and blend existing layers **without** involving the main thread.

```
Main thread:      JS → Style → Layout → Paint → (commit layers)
Compositor thread:                              Composite → screen (GPU)
                  ^ if blocked, the WHOLE pipeline stalls
                                                ^ can keep running even if main is busy
```

This is why `transform` and `opacity` animations stay smooth even under main-thread load: the browser can promote the element to its own **compositor layer** and animate it entirely on the compositor/GPU, skipping layout and paint each frame.

```css
/* Promote to a layer so the animation runs on the compositor. */
.card { will-change: transform; }       /* hint — use sparingly */
.card { animation: slide 300ms ease; }  /* animating transform = compositor-only */
```

Caveats: each layer costs GPU memory, so overusing `will-change` (or creating too many layers) backfires. The 60fps budget is **~16.6 ms per frame** (and ~8.3 ms for 120 Hz displays); blow it and you drop frames. Animating layout properties (`width`, `top`) forces main-thread work every frame and is the classic cause of jank.

### Q34. [Practical] You're told "the app feels janky during scroll." How do you systematically diagnose it?

Janky scroll means the browser is dropping frames — failing to produce a new frame within the ~16.6 ms budget. I'd work top-down:

1. **Reproduce and capture** — open the DevTools Performance panel, enable CPU throttling (4–6×) to mimic a mid-tier phone, record a scroll, and look for long frames (red bars) and "long tasks."
2. **Classify the bottleneck** in the flame chart:
   - **Scripting-heavy** → expensive scroll handlers (not throttled), forced synchronous layout, heavy React re-renders.
   - **Rendering-heavy** → large/expensive `Recalculate Style` or `Layout` (layout thrashing, huge DOM).
   - **Painting-heavy** → large paint areas, expensive effects (`box-shadow`, `filter`, `border-radius` on big regions).
3. **Check the usual suspects:**
   - Unthrottled `scroll`/`resize` handlers → throttle or use `IntersectionObserver` / `content-visibility` instead.
   - Non-passive listeners blocking scroll → add `{ passive: true }`.
   - Layout thrashing in handlers → batch reads/writes.
   - Huge DOM → virtualize long lists; use `content-visibility: auto` to skip offscreen rendering.
   - Animating layout props → switch to `transform`.

```js
// Passive listener so the browser doesn't wait to see if you preventDefault.
window.addEventListener('scroll', onScroll, { passive: true });
```

The discipline is **measure first, then fix the dominant cost** — don't guess. The Performance panel's "Frames" track and the Rendering tab's "Paint flashing"/"Layer borders" overlays make the culprit visible.

### Q35. [Theory] What is `content-visibility` and how does it improve rendering performance?

`content-visibility: auto` tells the browser it can **skip rendering work** (style, layout, and paint) for an element's subtree while it's offscreen, then render it just-in-time as it scrolls into view. It's like built-in virtualization for arbitrary content, without JS.

```css
.section {
  content-visibility: auto;
  contain-intrinsic-size: 0 600px;  /* estimated size so the scrollbar is correct */
}
```

```
Without:  browser lays out + paints ALL sections on load   (slow initial render)
With:     browser skips offscreen sections, rendering them as they approach view
```

The critical companion is `contain-intrinsic-size`: it provides a placeholder size for skipped content so the scrollbar height and layout are stable (avoiding CLS and scroll jumps). Use it for long pages with many independent sections (articles, feeds, dashboards) to cut initial layout/paint time dramatically. Caveats: it can affect in-page find (Ctrl+F), anchor scrolling, and accessibility tooling in edge cases, and a wrong `contain-intrinsic-size` causes scrollbar jumpiness — so test carefully.

### Q36. [Practical] How do you optimize a third-party-script-heavy page without removing the scripts?

Third-party scripts (analytics, tag managers, chat widgets, ads, A/B tools) are often the biggest performance offenders, yet the business won't let you delete them. Strategies that reduce their impact:

1. **Load them off the critical path** — `async`/`defer`, and inject non-essential scripts *after* the page is interactive or on user interaction (e.g. load the chat widget only when the user scrolls or clicks).
2. **`preconnect`** to their origins so the connection is warm when they do load.
3. **Facade pattern** — render a lightweight placeholder (a static "play" thumbnail for a YouTube embed, a fake chat button) and only load the heavy script on interaction. This can save hundreds of KB.
4. **Move execution off the main thread** with **Partytown**, which runs third-party scripts in a Web Worker so they don't block your INP.
5. **Self-host or proxy** where licensing allows, to control caching and avoid extra connections.
6. **Set a third-party budget** and audit with Lighthouse's "Reduce the impact of third-party code" and the DevTools "third-party" filter.

```html
<!-- Facade: load the real widget only on first interaction. -->
<button id="chat">Chat with us</button>
<script>
  document.getElementById('chat').addEventListener('click', () => {
    const s = document.createElement('script');
    s.src = 'https://thirdparty.example/chat.js';
    document.head.appendChild(s);
  }, { once: true });
</script>
```

The governing principle: you can't always control *what* loads, but you can control *when* and *where* (which thread) it runs.

### Q37. [Behavioral] Tell me about a time you improved the performance of a slow page. How did you approach it?

This is a STAR-format question; the interviewer wants a measurement-driven story, not heroics. A strong answer follows this arc:

- **Situation/Task** — "Our product listing page had a field LCP of ~4.5s at p75, and we were seeing bounce-rate correlation in analytics. I was asked to get it into the 'good' band."
- **Action — and emphasize the *method*:**
  - "I started with **data, not assumptions**: pulled CrUX/RUM to confirm it was real users (mid-tier Android, 4G), not just my machine."
  - "Profiled with Lighthouse and the Performance panel and found the LCP was a hero image that was (a) lazy-loaded by mistake and (b) an unoptimized 1.2 MB JPEG, plus a render-blocking 200 KB CSS file."
  - "Fixed the highest-leverage items first: removed `loading=lazy` from the LCP image, added `preload` + `fetchpriority=high`, converted to AVIF with responsive `srcset`, and inlined critical CSS."
  - "Added a `size-limit` gate and Lighthouse CI so it wouldn't regress."
- **Result — quantify:** "LCP dropped to ~2.1s at p75, we passed Core Web Vitals, and the CI gate has caught two regressions since."

The signal interviewers reward: **measure before optimizing, prioritize by impact, verify in the field, and prevent regression.** Avoid the anti-pattern of listing micro-optimizations with no measurement.

### Q38. [Practical] How do you reduce the JavaScript hydration cost in an SSR app?

Hydration cost comes from shipping and executing JS to make server-rendered HTML interactive. Reducing it is about **shipping and running less JS**:

1. **Server Components (React) / server-only rendering** — components with no interactivity render on the server and ship **zero** client JS. This is the biggest lever in modern React.
2. **Islands architecture (Astro, Fresh)** — the page is static HTML except for explicitly marked interactive "islands," each hydrated independently.
3. **Selective / progressive hydration** — hydrate above-the-fold and interactive components first; defer the rest (e.g. hydrate on idle or on viewport entry).
4. **Resumability (Qwik)** — serialize the framework state into HTML so the client *resumes* execution lazily on interaction instead of re-running everything. Effectively near-zero upfront hydration.
5. **Reduce bundle size** at the root — code split, tree shake, and avoid pulling heavy libraries into the initial chunk.
6. **Stream SSR** (React 18+ `renderToPipeableStream`, Suspense) so HTML flushes progressively and hydration can begin sooner on the parts that arrived.

```jsx
// React Server Component: runs on the server, ships no JS to the client.
async function ProductDetails({ id }) {
  const data = await db.product(id);     // server-side
  return <article>{data.description}</article>;  // static HTML, zero client JS
}
```

```
Classic SSR:   full HTML + full JS bundle → hydrate everything   (high INP risk)
Islands/RSC:   full HTML + tiny JS for interactive bits only      (low hydration cost)
```

The decisive insight: the cheapest hydration is the one you never do — render statically whatever doesn't need interactivity.

### Q39. [Theory] How do you decide between SSR, SSG, ISR, and CSR for a given page?

Each rendering strategy trades freshness, server cost, and time-to-content differently. The decision is per-route, driven by how the data changes:

```
Strategy   Rendered      Best for                         Tradeoff
--------   -----------   ------------------------------   -----------------------------
SSG        build time    marketing, docs, blogs           stale until rebuild; cheapest,
           (static)      (content rarely changes)         fastest TTFB (CDN-served)
ISR        build + bg    e-commerce catalogs, news        near-static speed with periodic
           revalidate    (changes occasionally)           background refresh
SSR        per request   dashboards, personalized,        fresh + SEO; server cost per
                         auth-gated content               request, higher TTFB
CSR        in browser    highly interactive app shells,   poor FCP/SEO unless prerendered;
                         private apps behind login        cheap hosting (static)
```

Decision heuristics:
- **Public + rarely changes + SEO matters** → SSG (or ISR if it changes occasionally).
- **Public + personalized or always-fresh + SEO matters** → SSR (consider edge/streaming).
- **Private app, SEO irrelevant, very interactive** → CSR (or SSR for first paint then SPA).
- **Huge catalog you can't fully prebuild** → ISR / on-demand revalidation.

Modern frameworks (Next.js App Router, Remix, SvelteKit, Nuxt) let you **mix these per route**, which is the real answer: choose per page based on data volatility, personalization, SEO need, and server budget — don't pick one strategy for the whole app.

### Q40. [Practical] How does the Speculation Rules API improve perceived navigation speed?

The **Speculation Rules API** (the modern successor to `<link rel=prefetch/prerender>`) lets you declaratively tell the browser to **prefetch** or **prerender** likely next navigations, so they feel instant when the user clicks.

```html
<script type="speculationrules">
{
  "prerender": [{
    "where": { "href_matches": "/product/*" },
    "eagerness": "moderate"
  }],
  "prefetch": [{
    "where": { "href_matches": "/*" },
    "eagerness": "conservative"
  }]
}
</script>
```

- **Prefetch** downloads the next document (and optionally subresources) in advance.
- **Prerender** goes further — it fully renders the next page in a hidden tab; activating it on click is effectively instantaneous (near-zero LCP for that navigation).
- **`eagerness`** (`conservative` / `moderate` / `eager`) tunes how aggressively the browser speculates (e.g. on hover/pointerdown vs. immediately), balancing speed against wasted bandwidth and server load.

```
User hovers "/product/42"  →  browser prerenders it in the background
User clicks                →  instant activation, page already rendered
```

This is one of the highest-impact modern techniques for **perceived** performance on multi-page sites, effectively giving SPA-like navigation speed to traditional MPAs. The cost to manage is wasted prerenders (bandwidth/CPU/analytics double-counting), which `eagerness` and tight `where` rules control.

### Q41. [Theory] What is the PRPL pattern, and is it still relevant?

**PRPL** is an acronym for a performance pattern from the early PWA era:

```
P — Preload (or Push) the most critical resources for the initial route.
R — Render the initial route as fast as possible.
P — Pre-cache remaining routes (via a service worker).
L — Lazy-load and create remaining routes on demand.
```

The core ideas remain very relevant in 2026 — it's essentially a checklist for: ship the minimum for the first view, preload what's critical, cache the rest with a service worker, and lazy-load everything else. What's changed is the *mechanics*: HTTP/2 Server Push (the original "P") is deprecated and replaced by `preload`/`103 Early Hints`; route-level lazy-loading is now standard via dynamic `import()`; and frameworks automate much of it. So I'd present PRPL as a still-sound *philosophy* (critical-first, lazy-rest, cache aggressively) while noting that Server Push is dead and the modern toolkit (resource hints, Early Hints, service workers, code splitting, RSC/islands) is how you actually implement it.

### Q42. [Practical] How would you use a Web Worker to keep the main thread responsive?

A **Web Worker** runs JS on a separate thread with no DOM access, communicating with the main thread via message passing. It's the tool for moving CPU-heavy work off the main thread so the UI stays responsive (protecting INP).

```js
// main.js — offload an expensive computation, keep the UI interactive.
const worker = new Worker('worker.js');
worker.postMessage({ rows: bigDataset });
worker.onmessage = e => render(e.data);  // result comes back, main thread stayed free

// worker.js — runs off the main thread.
self.onmessage = e => {
  const result = heavyAggregation(e.data.rows);  // would have blocked the UI
  self.postMessage(result);
};
```

```
Without worker:  main thread [---- 2s heavy compute ----]  UI frozen, INP terrible
With worker:     main thread [--- UI stays responsive ---]
                 worker thread [---- 2s heavy compute ----]  → postMessage back
```

Good candidates: large data parsing/aggregation, image/video processing, encryption, parsing big JSON/CSV, search indexing, and running untrusted third-party scripts (Partytown). Costs to weigh: the structured-clone serialization cost of passing data (mitigate with **Transferable** objects or `SharedArrayBuffer`), no DOM access (the worker computes, the main thread renders), and added complexity. For very large transfers, transferring an `ArrayBuffer` avoids the copy entirely.

---

## 🔴 Expert (15+ yrs)

### Q43. [Theory] Critique Core Web Vitals as a performance measurement system. Where do they fall short?

Core Web Vitals are an excellent *forcing function* — they gave the industry a shared, field-based, user-centric vocabulary and tied it to search ranking, which finally got performance funded. But a staff engineer should hold a nuanced view of their limits:

- **They're a lossy proxy for business outcomes.** Passing CWV correlates with better UX but isn't the goal — conversion, engagement, and revenue are. It's possible to game CWV (e.g. defer everything past the metric window) without truly helping users.
- **LCP measures one element, not "loaded."** A page can have a great LCP while critical *interactive* content or the actual thing the user came for loads later. LCP ignores below-the-fold and post-LCP content.
- **CLS is windowed and can miss real pain** — it uses session windows and ignores shifts within 500 ms of user input, so some genuinely annoying shifts don't count, and some harmless ones do.
- **INP is better than FID but still a single representative value** — it doesn't capture the *distribution* or *which* interactions are slow without attribution tooling.
- **75th-percentile field framing** hides the worst-served users (the p95/p99 tail on low-end devices), who may be exactly the ones you're losing.
- **No metric for perceived smoothness of animation, scroll jank, or "feel"** beyond what INP/CLS approximate.

The mature stance: **use CWV as a floor and a shared language, but instrument your own user-journey-specific metrics** (custom element timing, time-to-first-meaningful-action, business funnels) and watch the tail, not just p75. CWV tells you if you're broadly okay; it doesn't tell you if *your* users are succeeding.

### Q44. [Behavioral] How do you build a culture of performance in an organization that keeps regressing?

The technical fixes are the easy part; sustaining performance is an organizational problem. My approach:

- **Make it measurable and visible** — stand up RUM (real field CWV + custom business metrics) on a dashboard everyone sees, tied to revenue/conversion so leadership cares. "What gets measured and shown gets managed."
- **Make regressions cost something at the right moment** — automated budgets in CI (bundle-size gates, Lighthouse CI on PRs) so a regression blocks the merge, not a retrospective three sprints later. The feedback must be immediate and owned by the author.
- **Assign ownership** — a performance budget with no owner is a dashboard nobody reads. Either a performance guild/champion model or clear per-team budgets.
- **Tie it to business impact** — translate "LCP +400ms" into "we estimate X% conversion / $Y." Performance work competes with features for prioritization, and only the business framing wins that fight.
- **Lower the cost of doing the right thing** — provide an optimized image pipeline, a perf-budgeted starter, lint rules, and golden-path components so engineers fall into the pit of success by default rather than relying on discipline.
- **Educate and celebrate** — post-incident reviews for major regressions, share wins with their business numbers, and build shared vocabulary (CWV, long tasks, hydration).

The meta-point interviewers look for at this level: **you can't out-engineer a culture that doesn't value performance.** The durable fix is process, ownership, visibility, and business alignment — not a one-time optimization sprint.

### Q45. [Practical] Design an image-delivery pipeline for a high-traffic product with global users.

The goal is to deliver the right format, the right size, optimized, and cached close to every user — automatically, so engineers can't ship unoptimized images. Architecture:

```
Origin images (source of truth, lossless masters)
        │
        ▼
On-the-fly image CDN / service  ──►  Edge cache (per region)  ──►  User
(resize, format-negotiate,           (CDN PoPs)
 compress, strip metadata)
```

Key design decisions:
1. **Dynamic transformation at the edge** — an image service (Cloudinary, imgix, Thumbor, or a CDN's native image optimizer) generates variants on demand from a single master, keyed by URL params (`?w=800&f=auto&q=auto`). Avoids pre-generating a combinatorial explosion of sizes.
2. **Content negotiation** — serve **AVIF → WebP → JPEG** based on the `Accept` header (`f=auto`), so each browser gets its best supported format. Vary the cache on `Accept`.
3. **Responsive delivery** — emit `srcset`/`sizes` (or `<picture>`) so devices fetch the right resolution; cap the largest variant to sane bounds and honor DPR.
4. **Aggressive, immutable caching** — content-hash or version the URLs so transformed outputs cache for a year at the edge and in the browser (`immutable, max-age=31536000`). Invalidate by changing the URL.
5. **LCP prioritization** — preload + `fetchpriority="high"` for the hero image; lazy-load the rest; always set dimensions/`aspect-ratio` to protect CLS.
6. **Quality/perf guardrails** — automatic quality (`q=auto`), strip EXIF, apply perceptual quality limits, and set per-team byte budgets enforced in CI so unoptimized images literally can't merge.
7. **Resilience** — fall back to the master/origin on transform failure; monitor cache-hit ratio, p75 image LCP, and bytes-per-image as SLOs.

```
Request:  /img/master.jpg?w=800&f=auto&q=auto   Accept: image/avif,...
Edge:     cache hit? → serve AVIF@800           cache miss? → transform, store, serve
```

The architectural principle: **one master, many derivatives generated and cached at the edge, format/size negotiated per request, with optimization enforced by the platform rather than left to discipline.** This gives global low latency, minimal bytes per user, and makes the fast path the default path.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q46. [Theory] What exactly is the "preload scanner" and why does it matter?

The **preload scanner** (a.k.a. speculative or lookahead parser) is a secondary HTML parser the browser runs in parallel with the main parser. When the main parser hits a blocking `<script>` and stalls, the preload scanner keeps reading ahead through the raw HTML, finds resources (`<img>`, `<link>`, `<script src>`), and starts fetching them *early* — before the main parser even reaches them.

```
Main parser:      [...]──hits <script>──STOP (waiting for fetch+exec)
Preload scanner:  [keeps scanning ahead]──finds hero.jpg, app.css──starts FETCH now
```

Why it matters for performance: the scanner only sees resources that exist **in the static HTML markup**. Resources injected by JavaScript (`document.createElement('img')`, dynamic `import()` of a CSS-in-JS chunk, background images set via JS) are **invisible** to it, so they're discovered late. This is the deep reason "reference your LCP image in the initial HTML, don't inject it with JS" is a rule — late discovery adds a full network round trip to LCP. It's also why `<link rel="preload">` exists: to make the scanner aware of resources it otherwise couldn't see early (e.g. a font referenced only inside CSS).

#### Q47. [Theory] Walk through the full lifecycle of a single 16.6 ms frame.

A frame is the browser's unit of visual update. At 60 Hz you have **~16.6 ms**; whatever doesn't finish in time means a dropped frame (jank). The pipeline within a frame:

```
1. Input handlers    (pointer/touch/key events dispatched)
2. requestAnimationFrame callbacks (your JS animation logic)
3. Style             (recalculate computed styles for invalidated nodes)
4. Layout            (reflow: compute geometry)
5. Pre-paint         (build/update the property trees, compositing inputs)
6. Paint             (record paint operations into display lists)
7. Composite         (raster layers + assemble on the compositor/GPU thread)
```

The key insight is that you don't always pay for every stage. Changing a compositor-only property (`transform`, `opacity`) skips Style/Layout/Paint and only re-composites — that's why those animations are cheap. Changing `color` skips Layout but pays Paint + Composite. Changing `width` pays everything. `requestAnimationFrame` runs *before* Style/Layout, which is the right place to make visual changes; `requestIdleCallback` runs in leftover time *after* the frame is committed, for non-urgent work.

#### Q48. [Theory] What is the difference between `requestAnimationFrame`, `setTimeout`, and `requestIdleCallback` for scheduling?

All three schedule a callback, but they hook into different points of the event loop and have different timing guarantees:

```
setTimeout(fn, 0)        → runs as a macrotask "soon", NOT frame-aligned. Can fire
                           mid-frame or be clamped to 4ms in nested timers. Jittery
                           for animation.
requestAnimationFrame    → runs right BEFORE the next paint, frame-aligned. The
                           correct place for visual updates. Pauses in background tabs.
requestIdleCallback      → runs in idle time AFTER the frame is committed, with a
                           deadline budget. For non-urgent, deferrable work.
```

```js
// Animate with rAF so updates are frame-aligned and pause when tab is hidden.
function tick() {
  element.style.transform = `translateX(${x}px)`;
  x += 2;
  requestAnimationFrame(tick);
}
requestAnimationFrame(tick);

// Do non-urgent work without blocking interactions; respect the deadline.
requestIdleCallback(deadline => {
  while (deadline.timeRemaining() > 0 && queue.length) {
    processOne(queue.shift());
  }
});
```

The interview-grade point: `setTimeout` for animation is an anti-pattern (it desyncs from the display refresh, causing visible stutter), `requestAnimationFrame` is for things the user will *see this frame*, and `requestIdleCallback` is for things that can wait (analytics, prefetching, cache warming).

#### Q49. [Theory] What does the event loop's task vs. microtask distinction mean for performance?

The event loop processes **macrotasks** (one per loop iteration: a timer callback, an event handler, a message) and, after each one, **drains the entire microtask queue** (promise callbacks, `queueMicrotask`, `MutationObserver`) before rendering.

```
[macrotask] → drain ALL microtasks → (maybe render) → [next macrotask] → ...
```

The performance trap: microtasks run to completion *before the browser can paint or handle input*. A promise chain that schedules more microtasks can **starve rendering indefinitely** — the browser never gets to the render step because the microtask queue never empties.

```js
// DANGER: this microtask loop blocks rendering forever — the queue never drains.
function recurse() { Promise.resolve().then(recurse); }
recurse();   // page is frozen; no paint, no input

// To yield to rendering/input, you need a MACROTASK boundary, not a microtask.
await new Promise(r => setTimeout(r, 0));   // gives the browser a chance to paint
```

This is why `await yieldToMain()` for breaking up long tasks must cross a *macrotask* boundary (`setTimeout`, `scheduler.yield()`) — awaiting an already-resolved promise only queues a microtask and does **not** let the browser render or process input.

#### Q50. [Practical] How do `passive` event listeners improve scroll performance?

By default, a `touchstart`/`touchmove`/`wheel` listener *might* call `preventDefault()` to cancel scrolling. So the browser must **wait for your handler to finish** before it knows whether it's allowed to scroll — blocking the compositor and adding scroll latency.

```js
// Non-passive (default for these events historically): browser waits for the handler.
el.addEventListener('touchmove', onMove);            // can delay scroll

// Passive: you PROMISE not to call preventDefault, so the browser scrolls immediately
// on the compositor thread without waiting for your JS.
el.addEventListener('touchmove', onMove, { passive: true });   // smooth scroll
```

Declaring `{ passive: true }` tells the browser "this handler will never call `preventDefault`," so it can start scrolling on the compositor thread in parallel with running your handler. If you *do* call `preventDefault()` inside a passive listener, it's ignored (with a console warning). Modern browsers already treat `touchstart`/`touchmove`/`wheel` on document-level targets as passive by default, but being explicit is best practice and required when you attach to specific elements where the default may differ.

#### Q51. [Theory] What is the difference between `defer` and `type="module"` script loading?

Both load without blocking the parser, but with subtle differences:

```
<script>               parser-blocking; fetch + execute immediately, in place
<script defer>         fetch in parallel; execute after parse, in document order
<script async>         fetch in parallel; execute ASAP, order NOT guaranteed
<script type=module>   deferred BY DEFAULT (like defer); runs in module scope,
                       strict mode, fetched with CORS, executed once
<script type=module async>  module that executes ASAP (not order-preserving)
```

Key details an interviewer probes: `type="module"` scripts are **automatically deferred** — you don't need (and can't meaningfully use) the bare `defer` attribute on them. Modules also have their own scope (no global leakage), are always in strict mode, dedupe (the same module URL is fetched and evaluated only once even if imported many times), and resolve their full dependency graph before execution. The dependency-graph resolution means a module entry point can trigger a *waterfall* of imports; bundling or `modulepreload` hints mitigate that. There's also a `nomodule` attribute to ship a legacy fallback bundle to older browsers — though in 2026 that's rarely needed.

#### Q52. [Theory] Why is `<img>` with `decoding="async"` useful, and what does image decoding cost?

A compressed image (JPEG/WebP/AVIF) must be **decoded** into raw bitmap pixels before it can be painted — and by default that decode can happen **synchronously on the main thread**, briefly blocking it for large images.

```html
<!-- Hint the browser to decode off the main thread / before presenting. -->
<img src="hero.avif" decoding="async" alt="...">
```

```
Synchronous decode:  main thread [-- decode 4MP image --] UI hitches
Async decode:        decode happens off the critical path; image presents when ready
```

`decoding="async"` tells the browser it may decode the image asynchronously so it doesn't block the main thread when inserting the image. For images you control imperatively, the `img.decode()` promise lets you pre-decode before insertion to avoid a paint hitch:

```js
const img = new Image();
img.src = 'big.avif';
await img.decode();        // decode fully before we attach it
container.append(img);     // now appending won't cause a decode stall
```

This matters most for large images on lower-end devices, and for image carousels/galleries where a synchronous decode on swap causes visible jank.

#### Q53. [Practical] What is `fetchpriority` and how does it interact with the browser's priority model?

Every resource the browser fetches gets an internal **priority** (Highest → Low) that determines fetch ordering over a constrained connection. `fetchpriority` is an explicit override hint.

```html
<!-- Boost the LCP image above the browser's default for in-viewport images. -->
<img src="hero.avif" fetchpriority="high" alt="...">

<!-- Deprioritize a below-the-fold or non-critical resource. -->
<img src="footer-logo.png" fetchpriority="low" alt="...">

<link rel="preload" href="critical.js" as="script" fetchpriority="high">
```

```
Default browser priorities (roughly):
  CSS in <head>           Highest
  Sync/async scripts      High / Low
  Images in viewport      High (after layout knows they're visible)
  Images out of viewport  Low
  Preload                 inherits the `as` type's priority
```

The nuance: images start at **Low** priority and only get boosted *after layout* determines they're in the viewport — which is late. `fetchpriority="high"` on the LCP image fixes this by signalling importance *before* layout, so it competes with CSS/JS for early bandwidth. Conversely, `fetchpriority="low"` on a `preload` prevents an over-eager preload from stealing bandwidth from genuinely critical resources. It's a scalpel: use it on the one or two resources that truly matter, not everywhere.

#### Q54. [Theory] What is the difference between `visibility:hidden`, `display:none`, `opacity:0`, and `content-visibility:hidden`?

All four can "hide" an element, but they cost different amounts and behave differently in the rendering pipeline:

```
Property                  In render tree?  Takes space?  Paints?  Cost to toggle
-----------------------   ---------------   -----------   ------   ---------------------
display:none              NO                NO            NO       reflow (re-add to tree)
visibility:hidden         YES               YES           NO       repaint (still laid out)
opacity:0                 YES               YES           YES*      composite-only (cheap)
content-visibility:hidden YES (skipped)     uses          NO       skips subtree render,
                          but subtree         intrinsic-           keeps state; fast to show
                          rendering skipped   size
```

\* `opacity:0` still paints and still receives pointer events (unless `pointer-events:none`); it's the only one that's a compositor-friendly animation target. The deep distinction: `content-visibility:hidden` is like `display:none` for *rendering cost* (it skips style/layout/paint of the subtree) but **preserves the rendered state and layout containment**, making it far cheaper to re-show than `display:none` (which throws away and rebuilds the subtree). That makes it ideal for show/hide of expensive subtrees like tabs and offscreen panels.

#### Q55. [Practical] How does `IntersectionObserver` work and why is it better than scroll-listener-based visibility detection?

`IntersectionObserver` asynchronously notifies you when a target element enters or leaves a root's viewport, computed by the browser **off the main thread** — without you running JS on every scroll event.

```js
const io = new IntersectionObserver((entries) => {
  for (const entry of entries) {
    if (entry.isIntersecting) {
      loadImage(entry.target);     // element is now visible
      io.unobserve(entry.target);  // one-shot: stop watching
    }
  }
}, { rootMargin: '200px', threshold: 0 });   // start loading 200px early

document.querySelectorAll('[data-lazy]').forEach(el => io.observe(el));
```

```
Scroll-listener approach:  scroll fires 100s of times → each runs getBoundingClientRect()
                           (forces layout) → main-thread heavy, janky
IntersectionObserver:      browser computes intersections off-thread → callback only
                           when crossing a threshold → cheap, no forced layout
```

The performance win is twofold: (1) the intersection math runs in the browser's internals, not your scroll handler, and (2) you avoid calling `getBoundingClientRect()` in a hot loop, which forces synchronous layout. `rootMargin` lets you pre-trigger (load images before they're visible for a seamless experience), and `threshold` controls what fraction of visibility fires the callback. It's the right primitive for lazy loading, infinite scroll, and visibility-based analytics (impression tracking).

#### Q56. [Theory] What is the `Save-Data` header and adaptive loading?

`Save-Data: on` is a **Client Hint** the browser sends when the user has enabled a data-saver mode, signalling they want reduced data usage. **Adaptive loading** is the practice of tailoring what you ship based on the user's device and network conditions.

```js
// JS-side detection via the Network Information API.
const conn = navigator.connection;
if (conn?.saveData || conn?.effectiveType === '2g') {
  // serve low-res images, skip autoplay video, defer non-essential JS
  loadLiteExperience();
} else {
  loadFullExperience();
}
```

```
Signals you can adapt to:
  navigator.connection.saveData        user opted into data saving
  navigator.connection.effectiveType   '4g' | '3g' | '2g' | 'slow-2g'
  navigator.deviceMemory               approximate device RAM (GB)
  navigator.hardwareConcurrency        logical CPU cores
```

The philosophy: rather than shipping one heavy bundle to everyone, **adapt** — serve lighter images, fewer fonts, disabled autoplay, and deferred third-parties to constrained users. The honest caveat for 2026: these signals have **patchy and shrinking browser support** (the Network Information API's `effectiveType`/`saveData` are largely Chromium-only and partly being deprecated for privacy reasons), so treat them as progressive enhancement, not a foundation. Server-side, the `Save-Data` request header can drive the same adaptation at the CDN/origin.

### 🟡 — extended

#### Q57. [Theory] Explain how style/layout invalidation works and why a small DOM change can be expensive.

When you mutate the DOM or styles, the browser doesn't immediately recompute everything — it **marks affected nodes dirty** (invalidation) and defers the actual recalculation until something needs the result (a paint, or a forced read). The cost depends on *how far the invalidation propagates*.

```
Cheap:   change a leaf node's color        → invalidate 1 node's paint
Costly:  change a container's font-size     → invalidate descendants' layout (cascades down)
Costly:  insert a node mid-list             → may shift all following siblings (cascades sideways)
Worst:   change something on <html>/<body>  → can invalidate the whole tree
```

Two deeper mechanics: **(1) Recalc Style** is scoped by selector matching — descendant/sibling selectors and broad rules force the engine to re-match more nodes; modern engines optimize with invalidation sets but complex selectors still cost. **(2) Layout** is inherently cascading because one element's size can affect siblings, parents (if they size to content), and children. CSS **containment** (`contain: layout`, `contain: strict`) lets you tell the browser "changes inside this element can't affect anything outside it," cutting the invalidation boundary so a change in a widget doesn't re-lay-out the whole page. This is the engine-level reason large flat DOMs and deeply nested layouts are slow to update.

#### Q58. [Practical] What is CSS containment (`contain`) and when does it pay off?

The `contain` property promises the browser that an element's subtree is independent of the rest of the page, letting the browser **scope** style, layout, paint, and size calculations to that subtree.

```css
/* Each card is a self-contained island: internal changes can't affect siblings. */
.card {
  contain: content;   /* = layout paint style (but not size) */
}

/* Strict: also size — the element's size is independent of its children. */
.widget {
  contain: strict;            /* layout + paint + style + size */
  contain-intrinsic-size: 300px 200px;   /* needed since size is contained */
}
```

```
contain value   What it isolates
-------------    ------------------------------------------------------------
layout          this element's layout can't affect outside, and vice versa
paint           descendants never paint outside this box (enables paint culling)
style           certain style effects (counters) don't escape
size            element sizes itself WITHOUT measuring children
strict          = layout + paint + style + size (the strongest)
content         = layout + paint + style (size still derived from content)
```

It pays off in component-heavy UIs (cards, list rows, widgets) where you want a mutation in one component to invalidate only that component, not trigger a whole-page reflow. `paint` containment also enables the browser to **skip painting offscreen contained elements**. It's the foundation `content-visibility: auto` builds on. The cost is correctness: if a child legitimately needs to overflow or influence outside layout, containment will clip or break it — so apply it only to genuinely independent subtrees.

#### Q59. [Theory] How do source maps work, and what is their performance/security tradeoff?

A **source map** is a JSON file (`.map`) that maps positions in your minified/bundled/transpiled output back to the original source, so DevTools can show readable code and stack traces despite shipping minified code.

```
//# sourceMappingURL=app.4f3a2b.js.map      ← comment at the end of the bundle
{
  "version": 3,
  "sources": ["src/index.ts", "src/utils.ts"],
  "names": ["calculateTotal", ...],
  "mappings": "AAAA,SAASA,..."   ← VLQ-encoded position mapping
}
```

Performance angle: source maps are **only downloaded when DevTools is open**, so they don't affect normal users' load time — *unless* you inline them (`inline-source-map`), which bloats the bundle and ships to everyone (avoid in production). The `mappings` field uses **Base64 VLQ** encoding of relative position deltas to stay compact. Security/IP angle: publicly hosting source maps exposes your original source; many teams either omit the `sourceMappingURL` comment in production and upload maps privately to their error-tracking service (Sentry, Datadog), or restrict map access. The right production setup is "generate maps, ship the comment only to authorized contexts or upload them to your monitoring backend, keep them off the public CDN."

#### Q60. [Practical] How would you implement an efficient virtualized list with variable row heights?

Fixed-height virtualization is easy (position = index × rowHeight). Variable heights are harder because you don't know an item's offset until it's measured. The standard solution is a **measured-offset cache with estimation**.

```js
class VariableListVirtualizer {
  constructor(itemCount, estimateHeight) {
    this.count = itemCount;
    this.estimate = estimateHeight;     // fallback for unmeasured rows
    this.measured = new Map();          // index -> actual measured height
    this.offsets = [];                  // prefix-sum cache of cumulative offsets
    this.dirtyFrom = 0;                 // first index whose offset is stale
  }

  height(i) { return this.measured.get(i) ?? this.estimate; }

  // Lazily rebuild the prefix-sum of offsets from the first dirty index.
  getOffset(i) {
    for (let j = this.dirtyFrom; j <= i; j++) {
      this.offsets[j] = (this.offsets[j - 1] ?? 0) + this.height(j - 1);
    }
    this.dirtyFrom = Math.max(this.dirtyFrom, i + 1);
    return this.offsets[i] ?? 0;
  }

  // Binary-search the offset array to find the first visible row for scrollTop.
  findStart(scrollTop) {
    let lo = 0, hi = this.count - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (this.getOffset(mid) < scrollTop) lo = mid + 1; else hi = mid;
    }
    return Math.max(0, lo - 1);
  }

  // After a row renders, record its real height and invalidate downstream offsets.
  measure(i, realHeight) {
    if (this.measured.get(i) !== realHeight) {
      this.measured.set(i, realHeight);
      this.dirtyFrom = Math.min(this.dirtyFrom, i);   // offsets after i are now stale
    }
  }

  totalHeight() { return this.getOffset(this.count - 1) + this.height(this.count - 1); }
}
```

The total scroll height starts as `count × estimate` and self-corrects as rows are measured. Real libraries (TanStack Virtual) add: measuring via `ResizeObserver`, smoothing the scrollbar so corrections don't cause jumps, and an overscan buffer. The remaining hard problems are **scroll anchoring** (keeping the viewport stable when an above-the-fold row's measured height differs from its estimate) and accessibility (Ctrl+F and screen readers only see rendered rows).

#### Q61. [Theory] What is the difference between TBT (lab) and INP (field), and why don't they always agree?

**TBT (Total Blocking Time)** is a *lab* metric: the sum of the blocking portion (the part over 50 ms) of all long tasks between FCP and TTI. **INP** is a *field* metric: the latency of actual user interactions across the page's whole life.

```
TBT:  sum over long tasks of (taskDuration − 50ms), measured during LOAD, no user needed
INP:  worst-ish observed (input delay + processing + presentation) over REAL interactions,
      across the entire session, not just load
```

They correlate (a busy main thread hurts both) but diverge because:
- **TBT only covers the load window** (FCP→TTI); INP covers the *entire session*, including interactions long after load when TBT is "done."
- **TBT needs no user** — it measures main-thread *occupancy*; INP measures *actual interaction* latency, which also includes input delay (event waiting behind other work) and presentation delay (time to paint the response).
- A page can have **great TBT but poor INP** if a specific interaction (opening a heavy modal, filtering a big list) triggers a long task that the load-time lab trace never exercised.
- Conversely, **poor TBT but okay INP** if the load is busy but users don't interact during that window.

The practical consequence: optimize TBT to catch load-time main-thread hogging in CI, but you must *also* profile real interactions (or use INP attribution in RUM) because TBT will never see an interaction that doesn't happen during load.

#### Q62. [Practical] How do you profile and fix a specific slow interaction flagged by INP attribution?

INP attribution (from the `web-vitals` attribution build) tells you *which* interaction was slow and *which phase* dominated. The fix is phase-specific.

```js
import { onINP } from 'web-vitals/attribution';
onINP(metric => {
  const a = metric.attribution;
  console.log(a.interactionTarget);     // e.g. "button#filter"
  console.log(a.inputDelay);            // queued behind other main-thread work
  console.log(a.processingDuration);    // your event handler's own runtime
  console.log(a.presentationDelay);     // time from handler done → next paint
}, { reportAllChanges: true });
```

```
Phase dominant       Likely cause                  Fix
------------------   ---------------------------   --------------------------------------
inputDelay           main thread busy when click   break up long tasks, defer load-time
                     arrived                        work, yield with scheduler.yield()
processingDuration   handler does too much sync     move heavy work off-thread (Worker),
                                                    or split + yield mid-handler
presentationDelay    huge DOM update / layout       reduce nodes touched, use CSS
                     after handler                  containment, avoid forced reflow,
                                                    virtualize, transition off-main-thread
```

The workflow: reproduce the interaction in the DevTools Performance panel with the **Interactions track** (it shows input delay / processing / presentation as a breakdown), find the dominant phase, and apply the matching fix. The most common real-world culprit is `processingDuration` from a synchronous re-render of a large component tree — the fixes are yielding mid-update, `startTransition` (React) to mark the update non-urgent, virtualization, and trimming the DOM the update has to touch.

#### Q63. [Theory] What is `scheduler.yield()` and how does it differ from `setTimeout(0)` for breaking up tasks?

`scheduler.yield()` is a modern API (part of the Prioritized Task Scheduling spec) that yields control back to the browser to handle higher-priority work (input, rendering), then **resumes your code with priority** — unlike `setTimeout(0)`, which puts you at the back of the task queue.

```js
async function processChunks(items) {
  for (let i = 0; i < items.length; i++) {
    doWork(items[i]);
    if (shouldYield()) await scheduler.yield();   // yield, but resume soon
  }
}
```

```
setTimeout(0):       yields, but your continuation goes to the BACK of the task queue —
                     other tasks (and a 4ms+ clamp) can jump ahead of you. You yield
                     "fairly" but may wait a while to resume.
scheduler.yield():   yields to let input/render run, then RESUMES your work ahead of
                     other similarly-scheduled tasks. Best of both: responsive AND
                     you don't lose your place in line.
```

The deep distinction is **priority of the continuation**. With `setTimeout(0)` you risk yielding so generously that your own work gets starved by unrelated tasks the browser queued. `scheduler.yield()` returns a promise that resumes at a higher priority than freshly-posted tasks, so you stay responsive without sacrificing throughput. Since it's not universal yet, the production pattern is feature-detect and fall back: `'scheduler' in window && 'yield' in scheduler ? scheduler.yield() : new Promise(r => setTimeout(r))`.

#### Q64. [Theory] Why does a large DOM node count hurt performance even when nothing changes?

A large DOM (the rule of thumb flagged by Lighthouse is roughly **>800–1,500 nodes**, or excessive depth/children) is a standing cost, not just a per-change cost:

```
Standing costs of a huge DOM:
  Memory          every node + its computed style + layout box occupies RAM
  Style recalc    any global style change must re-match selectors across all nodes
  Layout          a single reflow must compute geometry for the whole tree
  Paint/Composite larger trees = more/bigger layers and paint records
  Hydration       SSR frameworks must walk and attach to every node
  querySelector   DOM traversals and event delegation scan more nodes
  Memory pressure GC pauses grow with retained DOM
```

The deep reason "nothing changed" still costs: invalidation is *lazy*, but when *any* recalc is forced (a class toggle on `<body>`, a viewport resize, a font load), the engine's work scales with tree size — so a bloated DOM makes *every* future reflow/recalc more expensive, even ones triggered by unrelated code. This is the core justification for **virtualization** (cap the live node count regardless of dataset size) and `content-visibility`/containment (let the browser skip work on offscreen subtrees). It's also why frameworks fight to minimize wrapper divs and why "div soup" is a genuine performance smell, not just an aesthetic one.

#### Q65. [Practical] How do you measure and optimize Time to Interactive / main-thread availability in practice?

TTI is "when the page reliably responds to input" — operationally, the first 5-second window after FCP with no long tasks and ≤2 in-flight network requests. Even though it's no longer a Core Web Vital, the underlying goal — a quiet main thread — drives INP.

```js
// Observe long tasks live to see what's monopolizing the main thread.
new PerformanceObserver(list => {
  for (const task of list.getEntries()) {
    if (task.duration > 50) {
      console.warn('Long task', task.duration, 'ms', task.attribution);
    }
  }
}).observe({ type: 'longtask', buffered: true });
```

```
Diagnosis ladder:
  1. PerformanceObserver('longtask')  → which tasks exceed 50ms, and how often
  2. DevTools Performance panel        → flame chart: scripting vs rendering vs painting
  3. Coverage tab                      → unused JS/CSS bytes shipped on load
  4. Bottom-Up / Call Tree             → which functions dominate (often framework boot,
                                          hydration, third-party init)
```

```
Optimization levers (in impact order):
  Ship less JS           code split, tree shake, RSC/islands, drop heavy deps
  Defer load-time work   lazy-init below-the-fold widgets, idle-callback non-urgent setup
  Offload                Web Workers for parsing/computation; Partytown for 3rd parties
  Break up long tasks    yield with scheduler.yield(); chunk hydration
```

The practical discipline: the single biggest TTI/INP lever is almost always **reducing the JavaScript executed during and just after load**, because parse+compile+execute+hydrate all land on the main thread in that window. Profile to find the dominant long tasks, then attack them in order rather than micro-optimizing.

#### Q66. [Theory] What are the performance characteristics of CSS-in-JS vs. static CSS vs. zero-runtime CSS?

How you author styles has real runtime cost, and the landscape splits into three buckets:

```
Approach              Runtime cost                         Examples
------------------    ----------------------------------   ----------------------------
Static CSS / CSS      None at runtime — plain stylesheet,  CSS files, CSS Modules,
Modules               parsed once by the browser           plain Sass output
Runtime CSS-in-JS     Serializes styles, injects <style>   styled-components (classic),
                      tags DURING render → main-thread      Emotion (runtime mode)
                      work on every render, hurts INP/
                      hydration; can cause style recalc
Zero-runtime          Extracts CSS to a static file at     Tailwind, Linaria, vanilla-
CSS-in-JS / atomic    BUILD time → no runtime, often       extract, Panda, StyleX,
                      atomic class dedupe                   CSS Modules
```

The performance problem with classic runtime CSS-in-JS is that style computation happens **during component render on the main thread**: the library serializes the CSS object, generates a class name, and injects a `<style>` rule, which can also trigger style recalculation — multiplied across every styled component and every re-render, and paid again during hydration. This is a measurable INP/hydration tax in large apps. The 2026 consensus has shifted decisively toward **zero-runtime / build-time extraction** (Tailwind, StyleX, vanilla-extract, Panda, Linaria): you get the colocated authoring DX without the runtime cost, often with **atomic** CSS that dedupes declarations so the stylesheet stops growing linearly with component count. React Server Components also pushed this shift, since runtime CSS-in-JS interacts poorly with server rendering.

### 🟠 — extended

#### Q67. [Theory] Explain the Speculation Rules prerendering lifecycle and its constraints (Chrome 2026).

Modern prerendering (via the Speculation Rules API) renders the next page in a **hidden, throttled prerendering browsing context**, then *activates* it instantly on navigation. Understanding its lifecycle is what separates "I've heard of it" from "I've shipped it."

```
1. Speculation rule matches  → browser creates a prerender of the target URL
2. Page renders in background → but in a restricted state:
     - document.prerendering === true
     - NO autoplay, NO permission prompts, NO modal dialogs
     - some APIs deferred until activation (e.g. anything intrusive)
3. User navigates             → the prerender is ACTIVATED (swapped to foreground)
     - prerenderingchange event fires; document.prerendering becomes false
     - activationStart marks the boundary; metrics rebase to it
4. Mismatch / disqualifying API → prerender is DISCARDED, normal nav happens
```

Critical constraints to handle in code: scripts run **during** prerendering, so anything with side effects (analytics beacons, ad impressions, A/B bucketing, autoplay) must be **gated** until activation to avoid counting a view the user never saw.

```js
if (document.prerendering) {
  document.addEventListener('prerenderingchange', sendAnalytics, { once: true });
} else {
  sendAnalytics();   // already activated (or prerendering unsupported)
}
```

Other constraints: cross-origin prerendering is heavily restricted (mostly same-origin, with same-site allowances), the page must avoid APIs that force a fallback, and you should rebase performance timings to `activationStart`. The payoff is dramatic — an activated prerender has near-zero LCP because it's already painted — but the correctness burden (not double-counting, not running side effects early) is real and is exactly what a senior engineer is expected to manage.

#### Q68. [Practical] How do you architect a performance-monitoring (RUM) pipeline that's both accurate and cheap?

A production RUM pipeline must capture real CWV + custom metrics without itself harming performance or costing a fortune. The design:

```
Browser (web-vitals + custom marks)
   │  batch + sendBeacon on visibilitychange:hidden  (survives unload)
   ▼
Collection endpoint (edge function / lightweight ingest)
   │  sample, validate, enrich (geo, device, connection, route)
   ▼
Time-series / OLAP store (ClickHouse, BigQuery, Honeycomb)
   │
   ▼
Dashboards + alerting (p75 AND p95 per route, regression alerts)
```

Key engineering decisions:
1. **Send at the right time** — buffer metrics and flush with `navigator.sendBeacon()` (or `fetch(..., {keepalive:true})`) on `visibilitychange === 'hidden'`, because some CWV (INP, CLS) are only final when the page is being hidden/unloaded. Never block unload with synchronous XHR.
2. **Sample intelligently** — you don't need 100% of traffic; sample (e.g. 10%) but **keep the tail** — over-sample slow sessions and errors so you don't lose the p95/p99 signal that matters most.
3. **Attribute, don't just aggregate** — store the attribution (LCP element, INP interaction target, CLS source) so a regression is *actionable*, not just a number that went up.
4. **Segment** — break down by route, device class, country, connection, and release version; an overall p75 hides that one route or one country regressed.
5. **Keep the client agent tiny** — the `web-vitals` library is ~2 KB; resist bolting on a heavy third-party RUM SDK that ironically degrades the metrics you're measuring.

The principle: **measure the field cheaply, keep the tail, and store attribution so every metric points to a fix.** Aggregate p75 tells you if you pass CWV; attribution + p95 tells you *what to do about it*.

#### Q69. [Theory] How does the browser cache actually work across memory cache, disk cache, HTTP cache, service worker cache, and the bfcache?

"The cache" is really a stack of distinct caches, each with different scope, lifetime, and lookup order. A senior answer enumerates them:

```
Layer              Scope/Lifetime              Notes
----------------   -------------------------   ------------------------------------------
Memory cache       per-tab, very short-lived   fastest; holds recently-used resources for
                                               the current page session (e.g. an image
                                               referenced twice). Gone on tab close.
Service Worker     programmable, persistent    your code decides; checked BEFORE HTTP
Cache Storage      (until you evict)           cache via fetch handler. Powers offline.
HTTP (disk) cache  per-origin, persistent      governed by Cache-Control/ETag; survives
                                               restarts; the "normal" browser cache.
bfcache            whole-page snapshot,         back/forward cache: freezes the entire
(back-forward)     in-memory                   page (DOM+JS heap) so back/forward is
                                               INSTANT, not a reload.
Push/Prefetch/     short-lived staging          103 Early Hints, prefetch, prerender
Preload caches                                  results land here until used.
```

Lookup order on a fetch (roughly): **Service Worker → memory cache → HTTP disk cache → network**, with the **bfcache** sitting outside this for history navigations. The two that trip people up: (1) the **memory cache** is why a resource hit twice on one page doesn't re-fetch even with `no-store` sometimes, and (2) the **bfcache** is the highest-impact "cache" for perceived speed on back/forward — but it's *disabled* by things like `unload` handlers, `Cache-Control: no-store`, and open IndexedDB transactions. Knowing what evicts a page from bfcache (and testing it in DevTools → Application → Back/forward cache) is a frequently-missed senior optimization.

#### Q70. [Practical] What disables the back/forward cache (bfcache), and how do you make a page bfcache-eligible?

The **bfcache** stores a complete in-memory snapshot of a page (DOM + JS heap) when the user navigates away, so pressing Back restores it **instantly** — no re-fetch, no re-parse, no re-execution. It's one of the biggest perceived-performance wins, and it's free *if* you don't disqualify your page.

```
Common bfcache disqualifiers:
  - `unload` event listeners            (use `pagehide`/`visibilitychange` instead)
  - `Cache-Control: no-store` on the    (the document itself can't be restored)
    main document
  - open IndexedDB transaction / open    in-flight connection at navigation time
    WebSocket / in-flight fetch
  - `window.opener` relationships / certain cross-origin popups
  - permission grants like a held lock or certain device APIs in use
```

```js
// Use pagehide/pageshow instead of unload, and detect bfcache restore.
window.addEventListener('pagehide', flushAnalytics);      // safe; bfcache-friendly
window.addEventListener('pageshow', (e) => {
  if (e.persisted) {
    // Page was restored from bfcache — re-sync anything time-sensitive
    // (clocks, auth tokens, real-time data) since JS did NOT re-run.
    refreshStaleState();
  }
});
```

To make a page eligible: **remove `unload` handlers** (the single most common offender), avoid `no-store` on the document where possible, close idle connections on `pagehide`, and test in DevTools → Application → Back/forward cache, which lists the exact reasons a page was blocked. The subtle correctness consideration is that on bfcache restore **your JS does not re-execute** — so anything that should refresh (tokens, timestamps, live data) must be re-synced in the `pageshow`/`persisted` handler.

#### Q71. [Theory] How do React's concurrent features (`startTransition`, `useDeferredValue`, Suspense) help performance?

React 18+'s concurrent renderer can **interrupt and prioritize** rendering work, which directly targets INP by keeping urgent updates (typing, clicking) responsive while heavy updates render in the background.

```jsx
function Search() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);

  function onChange(e) {
    setQuery(e.target.value);              // URGENT: keeps the input responsive
    startTransition(() => {
      setResults(filterHugeList(e.target.value));  // NON-URGENT: interruptible
    });
  }
  return <><input value={query} onChange={onChange} /><List items={results} /></>;
}
```

```
Without concurrency:  keystroke → filter 50k items synchronously → input lags (bad INP)
With startTransition: keystroke → input updates NOW; the heavy filter renders as a
                      LOW-priority, interruptible task — a newer keystroke aborts the
                      stale render
```

The mechanics:
- **`startTransition`** marks a state update as non-urgent; React can interrupt/abandon that render if a higher-priority update (a new keystroke) arrives, avoiding wasted work on stale results.
- **`useDeferredValue`** is the value-level equivalent: it lets a value "lag behind," so an expensive child renders against a deferred copy while the input stays live.
- **Suspense** lets React stream/await parts of the tree and show fallbacks without blocking the rest, enabling progressive/streaming SSR and avoiding all-or-nothing loading.

The key insight for an interview: these don't make rendering *faster* in raw CPU terms — they make it **interruptible and prioritized**, so the work that affects the user *right now* (input latency = INP) isn't blocked behind bulk work. The classic win is a search box filtering a huge list staying perfectly responsive.

#### Q72. [Practical] How do you optimize the performance of a long, complex form or data grid?

Large forms and grids are notorious INP/render hotspots because every keystroke can trigger a re-render of the whole tree and a layout of a huge DOM. The optimization stack:

```
Layer            Technique
--------------   ----------------------------------------------------------------
DOM size         Virtualize rows/columns (only render visible cells). A 10k-row
                 grid should have ~hundreds of live cells, not 10k×N.
Re-render scope  Isolate state so typing in one field doesn't re-render the grid:
                 uncontrolled inputs / per-field state / memoized rows / selectors.
Update priority  Mark filtering/sorting as transitions (startTransition) so typing
                 stays responsive; debounce expensive validation.
Layout cost      CSS containment per row/cell; fixed column widths to avoid full-
                 table reflow; avoid `table-layout: auto` on huge tables.
Validation       Validate on blur / debounced, off the keystroke path; run heavy
                 cross-field validation in a Worker if it's expensive.
```

```jsx
// Isolate each row so editing one cell doesn't re-render the whole grid.
const Row = React.memo(function Row({ row, onChange }) {
  return cells.map(c => <Cell key={c} value={row[c]} onChange={onChange} />);
}, (a, b) => a.row === b.row);   // re-render a row only when ITS data changes
```

The two highest-leverage moves are almost always: **(1) virtualize** so the live DOM is bounded regardless of dataset size, and **(2) narrow the re-render** so a keystroke in one cell doesn't reconcile thousands of others (memoized rows, stable callbacks, per-field state, or moving the form to uncontrolled inputs). For the heaviest grids, established libraries (AG Grid, TanStack Table + Virtual) bake in cell virtualization, controlled re-render boundaries, and column pinning — reaching for them is often the right senior call rather than re-deriving all of this.

#### Q73. [Theory] What is "JavaScript hydration mismatch" and what are its performance and correctness costs?

A **hydration mismatch** occurs when the HTML React (or any SSR framework) renders on the *client* during hydration doesn't match the HTML the *server* sent. The framework detects the divergence and must reconcile it.

```
Server HTML:  <span>Good evening</span>     (rendered at 18:00 server time)
Client render:<span>Good morning</span>     (hydrated at 06:00 user local time)
              → mismatch → React discards server HTML for that subtree and re-renders
                on the client (and warns)
```

Costs:
- **Performance** — on mismatch the framework can't reuse the server markup; it throws it away and **client-renders that subtree from scratch**, doing the work SSR was supposed to save, and can cause a visible flash/reflow (CLS) as content changes post-hydration. In React 18 a mismatch can de-opt to a full client render of the affected boundary.
- **Correctness** — the user briefly sees the server version, then it changes; for interactive widgets the event wiring may attach to the wrong nodes.

Common causes: rendering `Date.now()` / `Math.random()` / locale-dependent formatting / `window`-dependent values during render, or branching on `typeof window`. The fixes: render deterministic output on both sides and defer client-only values to **after** mount (`useEffect`), use stable IDs (`useId`), and for genuinely client-only content use a `suppressHydrationWarning` or a mounted-flag pattern so the first client render matches the server, then updates. The performance lesson: a mismatch silently converts your fast SSR path back into slow CSR for that subtree — so eliminating mismatches is a real perf fix, not just a console-warning cleanup.

#### Q74. [Practical] How do you optimize Largest Contentful Paint when the LCP element is a web font text block?

When the LCP element is a heading or paragraph (not an image), LCP is gated by **when the text becomes visible**, which depends on font loading and `font-display`. This is a subtle case many engineers get wrong by focusing only on images.

```
LCP-text bottlenecks:
  1. The font is render-blocking text → with font-display:block, text is INVISIBLE
     (FOIT) until the font loads, so LCP waits for the font download.
  2. The font is discovered late (referenced only in CSS) → preload scanner misses it.
  3. The CSS itself is render-blocking → text can't paint until CSSOM is ready.
```

```html
<!-- Make the font discoverable early so the preload scanner fetches it immediately. -->
<link rel="preload" href="/fonts/inter.woff2" as="font" type="font/woff2" crossorigin>
```
```css
@font-face {
  font-family: 'Inter';
  src: url('/fonts/inter.woff2') format('woff2');
  font-display: swap;          /* paint fallback text immediately → LCP fires on fallback */
  size-adjust: 98%;            /* match fallback metrics to minimize the swap-in shift */
  ascent-override: 90%;
}
```

The decisive techniques: (1) use **`font-display: swap`** (or `optional`) so the browser paints **fallback text immediately** — with `swap`, the LCP candidate paints on the fallback font right away rather than waiting for the web font (a huge LCP win vs. `block`/FOIT); (2) **preload** the critical font so it's not discovered late; (3) **match fallback metrics** with `size-adjust`/`ascent-override` so when the real font swaps in, it doesn't shift layout (protecting CLS — the tradeoff partner of using `swap`); and (4) inline critical CSS so the text isn't blocked on a stylesheet. Note: Chrome counts the *fallback* text paint as the LCP, so `swap` both speeds LCP and, if metrics are matched, keeps CLS low — the best of both.

#### Q75. [Theory] What are Client Hints and how do they enable adaptive, performant content negotiation?

**Client Hints** are a set of HTTP request headers (opt-in via the `Accept-CH` response header or a `<meta>` tag) through which the browser tells the server about the device and preferences, so the **server/CDN** can deliver appropriately-optimized content without client-side JS branching.

```http
# Server opts in: "send me these hints on future requests."
Accept-CH: DPR, Width, Viewport-Width, Save-Data, Sec-CH-UA, Sec-CH-Prefers-Color-Scheme

# Browser then sends, e.g.:
DPR: 2
Viewport-Width: 412
Save-Data: on
Sec-CH-Prefers-Color-Scheme: dark
```

```
Hint                          Lets the server...
---------------------------   ---------------------------------------------------
DPR / Width / Viewport-Width  serve a correctly-sized image without srcset round-trips
Save-Data                     drop to a lite experience for data-saver users
Sec-CH-UA*                    do UA-based feature/format negotiation (privacy-preserving)
Sec-CH-Prefers-*              honor color-scheme/reduced-motion server-side
```

The performance value is **doing content negotiation at the edge instead of in the browser**: an image CDN can read `DPR` and `Width` and return the exact pixel-perfect, optimally-compressed variant on the *first* request, avoiding the "ship a big image then swap" or heavy client-side logic. The 2026 caveats: hints are **opt-in** (you must send `Accept-CH`, and high-entropy hints require explicit request), they're scoped/delegated for privacy (third-party origins need delegation via `Permissions-Policy`), and **support varies by browser** (the `Sec-CH-UA` family is Chromium-centric). So they're a powerful CDN-side optimization where supported, layered under `srcset`/`<picture>` as the universal fallback.

### 🔴 — extended

#### Q76. [Theory] At a browser-architecture level, how does Chrome's multi-process / multi-threaded model shape what "performance" even means?

A staff-level answer reframes "page performance" in terms of which process/thread is the bottleneck. Chrome (and modern browsers) split work across processes and threads:

```
Browser process     UI, address bar, network service coordination, GPU dispatch
Renderer process    ONE per site (site isolation): runs your page
  ├─ Main thread     JS, DOM, Style, Layout, Paint-record, hydration  ← usual bottleneck
  ├─ Compositor      assembles layers, scrolls, runs transform/opacity animations
  ├─ Raster threads  turn paint records into bitmaps
  └─ Worker threads  Web Workers, Worklets
GPU process         final composite to screen
Network process     fetches, cache, connection pooling (shared)
```

This model reframes optimization:
- **"Jank" = the main thread missed a frame**, but the compositor thread can keep scrolling/animating *independently* — which is the whole reason `transform`/`opacity` stay smooth under main-thread load.
- **Site isolation** means each origin gets its own renderer process (Spectre mitigation), so a heavy iframe can't directly steal your main thread — but it costs memory, which is itself a performance dimension on low-RAM devices.
- **The network process is shared**, so connection warming (`preconnect`) and HTTP/2/3 connection coalescing happen there, decoupled from your renderer.
- **Worklets** (paint, animation, audio) and Workers let you run code on threads *other* than the bottleneck main thread.

The staff insight: performance is not one number — it's "which thread/process is saturated for this user's symptom?" Load problems are often network-process + main-thread-parse bound; jank is main-vs-compositor; memory pressure is per-process. You diagnose by identifying the saturated resource, not by applying a generic checklist.

#### Q77. [Practical] Design a performance strategy for a micro-frontend architecture where teams ship independently.

Micro-frontends optimize *team* autonomy at the direct expense of *page* performance — multiple independently-built bundles, duplicated dependencies, uncoordinated third-parties, and no single owner of the whole-page budget. Designing for performance here is a governance + architecture problem.

```
Core tensions:
  Duplication      each MFE bundles its own React/lodash → multi-MB of dupes
  No global budget no one owns total page weight; each team passes its own gate
  Runtime cost     loading N independent apps = N hydration passes, N init costs
  Layout shift     async-loaded MFEs popping in → CLS; competing for main thread
```

The strategy:
1. **Share the heavy commons** — use Module Federation (or import maps) to **share a single copy** of the framework and big libraries across MFEs, with version negotiation, so you don't ship React five times.
2. **Allocate a per-MFE budget out of a fixed whole-page budget** — the page has one total (e.g. 200 KB JS); each team gets a slice, enforced in *each* team's CI, with a platform dashboard summing actuals so the total can't silently blow up.
3. **Reserve layout space** for each MFE slot (skeletons with fixed dimensions) to protect CLS as they load asynchronously.
4. **Coordinate the critical path centrally** — the shell owns the document, critical CSS, font loading, and resource hints; MFEs hydrate progressively (islands-style) so they don't all fight for the main thread at once.
5. **Govern third-parties at the shell** — a single tag-manager/analytics policy instead of every team adding their own scripts (the classic MFE perf killer).
6. **Measure attribution per MFE in RUM** — tag long tasks / CWV by owning MFE so a regression is traceable to a team, not lost in the aggregate.

The meta-point: micro-frontends move the performance problem from *technical* to *organizational*. The shell must act as the **performance authority** (shared deps, total budget, critical-path ownership, third-party gatekeeping), or the architecture trades autonomy for a slow page no one is accountable for. Sometimes the right senior answer is to push back: if the page is performance-critical and the team boundaries don't require runtime composition, build-time integration (one bundle) avoids the whole class of problems.

#### Q78. [Theory] Critique `will-change` and the broader idea of "layer promotion" — when does optimizing backfire?

`will-change` (and tricks like `transform: translateZ(0)`) promote an element to its own **compositor layer**, so animations on it skip layout/paint and run on the GPU. It's a genuine optimization that's also one of the most *misused* — a staff engineer should articulate the failure modes.

```
The promise:  will-change: transform  → element gets its own layer → transform/opacity
              animations run on the compositor, smooth even under main-thread load.

The costs (why blanket use backfires):
  GPU memory      each layer is a texture in VRAM; thousands of layers = OOM, especially
                  on low-end mobile GPUs → the browser may FALL BACK to slower paths.
  Upload cost     promoting/demoting a layer costs a texture upload; toggling it
                  repeatedly is worse than not promoting.
  Persistent hint will-change kept ON permanently forces the browser to hold the layer
                  forever — the spec explicitly says set it just BEFORE the animation
                  and remove it after.
  Text rendering  promoted layers can change subpixel anti-aliasing of text (blurriness).
  Stacking/paint  creates a stacking context, which can subtly change z-order/overflow.
```

The disciplined model: `will-change` is a **just-in-time hint**, not a decoration. Set it right before an animation starts (e.g. on hover/focus or via JS), remove it when the animation ends, and never apply it to large numbers of elements or "just in case." The deeper lesson — applicable beyond this property — is that **every optimization that pre-allocates a resource (a layer, a preload, a prerender, a cache entry) has a cost paid by everyone, including users who never benefit**. Layer promotion, `preload`, `prerender`, and `content-visibility` placeholders all share this shape: speculative resource commitment that backfires when over-applied, especially on the low-end devices you're nominally trying to help. The senior judgment is matching the cost to a *measured* need, not sprinkling hints.

#### Q79. [Practical] How would you debug a memory leak that degrades performance over a long session in a SPA?

In long-lived SPAs (dashboards, editors, chat) memory leaks don't crash immediately — they cause *gradual* slowdown: growing GC pauses (jank), eventual swapping, and on mobile, tab termination. Debugging is methodical.

```
Detection ladder:
  1. Reproduce growth   → DevTools Performance Monitor: watch JS heap + DOM node count
                          while repeating the suspect flow (open/close a modal 20×).
                          A leak = heap/nodes climb and never return to baseline.
  2. Heap snapshots      → Memory tab: take snapshot, perform action repeatedly, snapshot
                          again. Compare → "objects allocated between snapshots" still
                          retained reveals the leak.
  3. Detached nodes      → filter snapshot for "Detached" DOM trees: nodes removed from
                          the document but still referenced (the #1 SPA leak).
  4. Retainer path       → select a leaked object → "Retainers" shows WHAT is holding it
                          alive (the closure/listener/cache keeping the reference).
```

```
Common SPA leak sources & fixes:
  Event listeners not removed on unmount   → remove in cleanup/useEffect return / AbortController
  Timers/intervals left running             → clearInterval/clearTimeout on teardown
  Subscriptions (stores, websockets, RxJS)  → unsubscribe on unmount
  Closures capturing large objects/DOM      → null out refs; avoid capturing the whole props
  Detached DOM held by JS (caches, maps)    → use WeakMap/WeakRef for DOM-keyed caches
  Global caches that only grow              → bound them (LRU) and evict
```

```js
// AbortController makes listener cleanup foolproof across many listeners.
const ac = new AbortController();
window.addEventListener('resize', onResize, { signal: ac.signal });
socket.addEventListener('message', onMsg, { signal: ac.signal });
// On teardown — removes EVERY listener registered with this signal at once:
ac.abort();
```

The performance framing matters: a leak is a *perf* bug because retained memory inflates **GC pause times** (each major GC must scan more live objects → longer main-thread stalls → INP/jank regressions) and pushes low-RAM devices into swapping or tab kills. The disciplined workflow is **reproduce the growth, snapshot-diff to find the retained objects, follow the retainer path to the offending reference, then null/unsubscribe/WeakRef it** — and prevent recurrence with `AbortController`-based cleanup and bounded (LRU/WeakMap) caches. `performance.measureUserAgentSpecificMemory()` can monitor total memory in production for trend alerting.

#### Q80. [Theory] Explain the full anatomy of LCP attribution and the subtleties that make LCP misleading.

LCP looks simple ("largest element paints") but its definition has enough subtlety that a staff engineer should be able to explain *why two pages with the same LCP value can have very different real experiences*, and how the metric can be both gamed and misread.

```
LCP candidate rules (what counts):
  - largest of: <img>, <image> in <svg>, <video> poster, block-level text, bg-image
  - measured WITHIN the viewport, by rendered (not intrinsic) size
  - the candidate keeps UPDATING as larger elements paint, until the FIRST user
    interaction (scroll/keypress/click) FREEZES the LCP value
  - elements that are removed, or have opacity:0, are excluded/handled specially
```

The subtleties and failure modes:
- **First-interaction freeze** — LCP stops updating at the first interaction. A page can *defer* its real largest element until just after a likely early interaction and report an artificially good LCP (a way LCP gets gamed). Conversely, a user who scrolls immediately freezes LCP early, which can make a genuinely slow page *look* fast in that session.
- **Rendered size, not importance** — LCP rewards the *biggest* element, which may be a decorative hero, not the content the user came for. You can have a great LCP while the meaningful content (a price, a search result) loads later — invisible to LCP.
- **Background images vs. content images** — a CSS `background-image` is discovered late (only after CSSOM + layout) and can't be `preload`-prioritized as naturally; the same visual as an `<img>` is discoverable by the preload scanner. So *how you mark up* the same pixels changes LCP.
- **Layout-dependent candidacy** — which element is "largest in viewport" depends on viewport size, so LCP differs across devices for the same page, and a late layout shift can change the LCP element retroactively.
- **`opacity:0` → fade-in trap** — an element animating from `opacity:0` may have its LCP timed to when it *starts* fading in (still invisible), so animations can make LCP *look* worse or behave unintuitively.

The mature takeaway: LCP is a **proxy**, and proxies leak. Use **LCP attribution** (which element, and the TTFB / load-delay / load-time / render-delay breakdown) to optimize the real bottleneck, but pair it with **custom element-timing** (`elementtiming` attribute) on the element that *actually* represents "useful content for this user" — because the largest element and the meaningful element are frequently not the same. That gap is precisely what separates a checkbox-passing LCP from an actually-fast experience.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q81. [Practical] Lighthouse gives your page 95 on your laptop, but real users complain it's slow. Walk through what you'd check.

This is the single most common performance disconnect, and the answer is "lab score ≠ field experience." Lighthouse runs once, on the machine and network you point it at; real users span low-end Android phones on congested 4G in distant geographies. A 95 on a developer laptop with a fast connection tells you almost nothing about p75 field reality.

What I'd check, in order:
1. **Pull the field data (CrUX / your own RUM)** — open PageSpeed Insights and look at the *field* section, not the lab section. If CrUX shows LCP at p75 of 4s while your lab LCP is 1.2s, the gap is real users on slower hardware/networks.
2. **Re-run Lighthouse with realistic throttling** — Lighthouse's "Mobile" preset applies a slow-4G + 4× CPU slowdown, but the default DevTools run might be on "No throttling." Confirm you're comparing apples to apples.
3. **Check the device/network distribution** in your RUM — segment metrics by `effectiveType`, device memory, and country. The aggregate p75 often hides a brutal p95 tail.
4. **Look for variability the single lab run misses** — A/B tests, personalization, logged-in vs. logged-out, cold vs. warm cache, third-party scripts that load non-deterministically.

```js
// Field-first: this is what actually decides whether you pass CWV.
import { onLCP, onINP, onCLS } from 'web-vitals';
function send(metric) {
  navigator.sendBeacon('/rum', JSON.stringify({
    name: metric.name, value: metric.value,
    // segment so you can find WHO is slow, not just the average
    conn: navigator.connection?.effectiveType,
    mem: navigator.deviceMemory,
  }));
}
[onLCP, onINP, onCLS].forEach(fn => fn(send));
```

The principle: **lab to diagnose, field to judge.** A green Lighthouse score is necessary but not sufficient; the truth lives in RUM segmented by real device and network conditions.

#### Q82. [Coding] Write a throttle that fires on both the leading and trailing edge, and explain why the naive version drops the final event.

The naive throttle in Q9 drops the last event in a burst: if events stop right after a `setTimeout` is scheduled but before it fires, the final state never runs. For things like a resize handler that must end on the *final* dimensions, you need a trailing call.

```js
function throttle(fn, limit, { leading = true, trailing = true } = {}) {
  let lastRun = 0;          // timestamp of the last actual invocation
  let timer = null;         // pending trailing call
  let lastArgs = null;      // args captured for the trailing call

  return function throttled(...args) {
    const now = Date.now();
    // First call in a window with leading disabled: pretend we just ran.
    if (!lastRun && !leading) lastRun = now;

    const remaining = limit - (now - lastRun);
    lastArgs = args;

    if (remaining <= 0) {
      // Enough time has passed — run on the leading edge.
      if (timer) { clearTimeout(timer); timer = null; }
      lastRun = now;
      fn.apply(this, args);
    } else if (trailing && !timer) {
      // Within the window — schedule a trailing call for the window's end.
      timer = setTimeout(() => {
        lastRun = leading ? Date.now() : 0;
        timer = null;
        fn.apply(this, lastArgs);
      }, remaining);
    }
  };
}

// Resize: react immediately AND settle on the final size when resizing stops.
window.addEventListener('resize', throttle(onResize, 150));
```

The naive version only schedules `waiting = false` and never re-invokes with the latest arguments, so the final event in a fast burst is silently lost — visible as a layout that's "one resize behind." The leading+trailing version guarantees both the first response (snappy) and the last (correct final state).

#### Q83. [Practical] A page's CLS is 0.0 in your lab test but 0.25 in the field. What's going on and how do you find it?

CLS is notoriously lab-invisible because the biggest shift sources only appear under real conditions:
- **Late-loading personalized content** — ads, recommendation widgets, A/B-test banners, cookie/consent bars that your clean lab run doesn't trigger or that load instantly from your warm cache.
- **Web fonts swapping** — on a fast connection the font arrives before first paint (no shift); on a slow connection the fallback paints first, then the web font swaps in and reflows text.
- **Images without dimensions** that are cached in your lab run (instant, no shift) but stream in slowly for real users, pushing content down.
- **User interaction** — CLS keeps accumulating over the session; lab tests usually don't scroll or click, so they miss shifts triggered by interaction (which only get excluded within 500ms of input).

How to find it:
```js
import { onCLS } from 'web-vitals/attribution';
onCLS(metric => {
  // attribution.largestShiftTarget points at the element that moved most.
  const a = metric.attribution;
  navigator.sendBeacon('/rum', JSON.stringify({
    value: metric.value,
    el: a.largestShiftTarget,        // e.g. "div#consent-banner"
    time: a.largestShiftTime,        // when in the session it happened
  }));
});
```

The attribution build reports the *element* and *time* of the largest shift, which is what turns "CLS is 0.25 somewhere" into "the consent banner inserted at 1.8s is shoving the article down." Then reserve space for it (fixed-height container, render above the viewport, or use `position: fixed`).

#### Q84. [Coding] Write a function that measures the real LCP element and its timing using PerformanceObserver.

You don't need a library to observe LCP — the browser exposes it via `PerformanceObserver` with the `largest-contentful-paint` entry type. This is useful for custom dashboards or debugging which element the browser actually picked.

```js
function observeLCP(callback) {
  let lcp = null;
  const observer = new PerformanceObserver((list) => {
    // LCP can update multiple times; the LAST entry before interaction wins.
    const entries = list.getEntries();
    lcp = entries[entries.length - 1];
  });
  observer.observe({ type: 'largest-contentful-paint', buffered: true });

  // LCP is finalized at the first interaction or when the page is hidden.
  function finalize() {
    if (!lcp) return;
    observer.disconnect();
    callback({
      value: lcp.startTime,              // time in ms from navigation start
      element: lcp.element,              // the actual DOM node chosen as LCP
      url: lcp.url,                      // resource URL if it's an image
      size: lcp.size,                    // rendered size (px²)
      renderTime: lcp.renderTime || lcp.loadTime,
    });
  }

  ['keydown', 'click'].forEach(t =>
    addEventListener(t, finalize, { once: true, capture: true }));
  addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') finalize();
  }, { once: true });
}

observeLCP(({ value, element }) => {
  console.log(`LCP ${Math.round(value)}ms`, element);
});
```

The two subtleties an interviewer wants: `buffered: true` captures entries that fired *before* you registered the observer (so you don't miss early paints), and LCP must be *finalized* on the first interaction or page-hide — reading it too early gives you an intermediate candidate, not the final value.

#### Q85. [Practical] Your hero image is correctly `preload`ed but LCP is still slow. List the things that could still be wrong.

A correct `preload` only solves the *discovery* problem. LCP has four phases (TTFB, load delay, load time, render delay), and preload only attacks load delay. Things still in play:

1. **High TTFB** — if the HTML itself takes 1.5s to arrive, the preload can't even start until then. Check the server/CDN.
2. **The image is huge or unoptimized** — preloading a 2 MB JPEG just downloads 2 MB faster; it's still 2 MB. Convert to AVIF/WebP, size it responsibly, compress.
3. **Bandwidth contention** — the preload is competing with render-blocking CSS/JS and other preloads. Add `fetchpriority="high"` and audit whether you're over-preloading.
4. **Render delay** — the image is downloaded but can't *paint* because the main thread is blocked by a long task, or it's gated behind render-blocking CSS, or it's inside a container that hasn't laid out yet.
5. **The `preload` doesn't match the actual fetch** — wrong `imagesrcset`/`media`/`type`, or a different URL than what `<img>` requests, causing a double-download (the preload is wasted and the real fetch is late). DevTools warns "preloaded but not used."
6. **It's a CSS `background-image`** — discovered only after CSSOM + layout, so it's intrinsically late even with effort; switch to an `<img>`.

```html
<!-- The preload MUST exactly match the responsive image the browser picks. -->
<link rel="preload" as="image"
      imagesrcset="hero-800.avif 800w, hero-1600.avif 1600w"
      imagesizes="100vw" fetchpriority="high">
```

The diagnostic move: open the Performance panel, find the LCP marker, and look at which of the four phases dominates — preload only helps load delay.

#### Q86. [Coding] Implement a simple idle-until-urgent lazy initializer that loads a heavy module on idle or on first interaction, whichever comes first.

A common pattern: you want a non-critical feature (a chat widget, a heavy chart library) ready, but not at the cost of initial load. Load it during idle time, but if the user interacts first, load it immediately.

```js
function idleUntilUrgent(loader) {
  let promise = null;
  let cleanup = () => {};

  function load() {
    if (promise) return promise;
    cleanup();                       // remove all triggers once we commit
    promise = loader();              // e.g. () => import('./HeavyWidget.js')
    return promise;
  }

  // Trigger 1: browser idle time (with a timeout fallback for busy pages).
  const idleId = 'requestIdleCallback' in window
    ? requestIdleCallback(load, { timeout: 4000 })
    : setTimeout(load, 4000);

  // Trigger 2: any user intent — load eagerly if they engage first.
  const events = ['pointerdown', 'keydown', 'touchstart'];
  const onIntent = () => load();
  events.forEach(e => addEventListener(e, onIntent, { once: true, passive: true }));

  cleanup = () => {
    ('cancelIdleCallback' in window ? cancelIdleCallback : clearTimeout)(idleId);
    events.forEach(e => removeEventListener(e, onIntent));
  };

  return { load };          // expose load() for explicit triggering too
}

// Usage: the editor is ready by the time the user clicks "Edit".
const editor = idleUntilUrgent(() => import('./RichTextEditor.js'));
```

The "idle-until-urgent" pattern (named by Philip Walton) balances two failure modes: loading too eagerly (wastes bandwidth, competes with critical work) and too lazily (the feature isn't ready when the user wants it). The `once: true` listeners and `cleanup` prevent double-loading and leaks.

#### Q87. [Practical] A user reports the page "freezes for a second" right after it loads. How do you confirm and fix it?

"Freezes after load" almost always means a **long task** on the main thread right after the page paints — the page looks ready but ignores input. To confirm and fix:

1. **Reproduce in the Performance panel** with 4–6× CPU throttling. Record the load. Look for a solid red-cornered task in the main-thread track immediately after FCP/LCP — that's your long task. The "Total Blocking Time" and "Long Tasks" markers point right at it.
2. **Identify the cause** in the flame chart — the most common culprits are: hydration of the whole app at once, a synchronous JSON parse of a large payload, framework bootstrap, third-party analytics/tag-manager init, or a giant initial render.
3. **Confirm in the field** with the Long Tasks API so you know it's real users, not just your throttled lab:

```js
new PerformanceObserver(list => {
  for (const task of list.getEntries()) {
    if (task.duration > 50) {
      navigator.sendBeacon('/rum', JSON.stringify({
        type: 'longtask', duration: task.duration, start: task.startTime,
      }));
    }
  }
}).observe({ type: 'longtask', buffered: true });
```

4. **Fix by chunking and deferring**: break the long task with `await scheduler.yield()`, defer non-critical init (analytics, widgets) past first interaction, move heavy parsing/computation to a Web Worker, and adopt selective/progressive hydration so the whole tree doesn't hydrate in one blocking task.

The mental model: a frozen-feeling page is a busy main thread. The fix is always "do less synchronous work, and yield so the browser can handle input between chunks."

#### Q88. [Coding] Write a function that detects whether the current connection is slow and returns a "data budget" tier for adaptive loading.

```js
function getLoadingTier() {
  const conn = navigator.connection;       // Network Information API (Chromium)
  const mem = navigator.deviceMemory ?? 8; // GB, coarse buckets
  const cores = navigator.hardwareConcurrency ?? 8;

  // No signal available (Safari/Firefox) — assume capable, enhance progressively.
  if (!conn) return 'full';

  if (conn.saveData) return 'lite';                 // user opted into data saving
  if (['slow-2g', '2g'].includes(conn.effectiveType)) return 'lite';
  if (conn.effectiveType === '3g' || mem <= 2 || cores <= 2) return 'reduced';
  return 'full';
}

// Drive concrete decisions off the tier.
const tier = getLoadingTier();
const config = {
  lite:    { images: 'low',    video: false, prefetch: false, fonts: 'system' },
  reduced: { images: 'medium', video: false, prefetch: false, fonts: 'subset' },
  full:    { images: 'high',   video: true,  prefetch: true,  fonts: 'full'   },
}[tier];

if (config.images === 'low') document.documentElement.dataset.imgQuality = 'low';
if (config.prefetch) enableSpeculativeNavigation();
```

The honest caveat for 2026: `navigator.connection`'s `effectiveType`/`saveData` are largely **Chromium-only**, and `deviceMemory`/`hardwareConcurrency` are coarse and somewhat privacy-restricted. So this is **progressive enhancement** — default to the full experience when signals are absent, and only *downgrade* when you have a positive slow-network signal. Never block the capable majority because a minority might be slow.

### 🟡 — extended

#### Q89. [Practical] Your bundle grew 40% after a routine dependency update. How do you find what caused it and decide what to do?

A bundle-size regression from a dependency bump is a classic. The workflow:

1. **Confirm and quantify** — your CI bundle-size gate (`size-limit`) should have flagged it; if not, add one. Compare the before/after gzipped sizes per chunk.
2. **Diff the bundle visually** — run a bundle analyzer (`rollup-plugin-visualizer`, `webpack-bundle-analyzer`, or `source-map-explorer` on the built map). Compare the treemap before and after; the new fat box is your culprit.
3. **Find the actual cause** — common ones: the library dropped its ESM build (breaking tree shaking), pulled in a heavy transitive dep (a new `moment`/`lodash`/polyfill), shipped both CJS and ESM, or started bundling its own copy of a shared dep (duplicate React, duplicate `tslib`).

```bash
# Why is this package in the tree, and is it duplicated?
npm ls some-heavy-dep
# Inspect what the built bundle actually contains, by original source.
npx source-map-explorer dist/assets/*.js
```

4. **Decide the fix by impact**:
   - Import only what you use (`import debounce from 'lodash/debounce'` or `lodash-es`).
   - Pin to the last good version if the new one regressed its packaging.
   - Replace with a lighter alternative (`date-fns`/`dayjs` over `moment`, native `Intl` over a formatting lib).
   - Code-split it so it's not in the initial chunk if it's only used on one route.
   - Dedupe with `resolutions`/`overrides` if it's a duplicated transitive dep.

The discipline: **never let a bundle regression land silently.** A size gate in CI turns "we got slowly fatter over a year" into "this PR added 40 KB, justify it."

#### Q90. [Coding] Implement a request waterfall fixer: a `loadInParallel` utility that runs dependent and independent fetches optimally.

A common performance bug is an accidental request *waterfall* — `await`ing fetches sequentially when they could run in parallel. The fix is to *start* all independent requests immediately and only `await` where there's a true data dependency.

```js
// BAD: sequential waterfall — total time = user + posts + comments.
async function loadBad(userId) {
  const user = await fetchUser(userId);
  const posts = await fetchPosts(userId);        // didn't need user
  const comments = await fetchComments(userId);  // didn't need posts
  return { user, posts, comments };
}

// GOOD: fire all independent requests at once; await together.
async function loadGood(userId) {
  // Start all three immediately — no await yet, so they run concurrently.
  const userP = fetchUser(userId);
  const postsP = fetchPosts(userId);
  const commentsP = fetchComments(userId);
  const [user, posts, comments] = await Promise.all([userP, postsP, commentsP]);
  return { user, posts, comments };
}

// MIXED dependencies: parallelize what you can, chain only the real edge.
async function loadProfile(userId) {
  const userP = fetchUser(userId);               // independent — start now
  const settingsP = fetchSettings(userId);       // independent — start now
  const user = await userP;
  // friends genuinely needs user.orgId — this edge must be sequential.
  const friends = await fetchFriends(user.orgId);
  const settings = await settingsP;              // already in flight, likely resolved
  return { user, settings, friends };
}
```

The key insight: **`await` should mark a real data dependency, not be sprinkled by habit.** Calling the async function (without `await`) starts the request; you only pay the serialization cost when you actually `await`. Use `Promise.all` for fan-out, and only chain where one response feeds the next request. On the server (RSC, loaders), the same rule prevents server-side waterfalls.

#### Q91. [Practical] A React list re-renders the whole list on every keystroke in an unrelated input. How do you diagnose and fix it?

This is a state-colocation / referential-stability problem. Diagnosis and fix:

1. **Profile with the React DevTools Profiler** — record a keystroke, look at the flame graph. If the whole list lights up (re-rendered) when only the input changed, you've confirmed over-rendering. The "why did this render" feature names the trigger.
2. **Common causes**:
   - The input's state lives in a **common ancestor** that also renders the list, so every keystroke re-renders the subtree. Fix: **colocate** the input state in a small child component so the list isn't in its render path.
   - List items aren't **memoized** (`React.memo`) and receive **new prop references** every render (inline `onClick={() => ...}`, fresh object/array literals). Fix: `memo` the row, `useCallback`/`useMemo` the handlers and derived data.
   - A **context value** changes identity every render, re-rendering all consumers.

```jsx
// Fix 1: colocate input state so typing doesn't touch the list.
function SearchPage({ items }) {
  return (
    <>
      <SearchBox />        {/* owns its own state; list is not in its render path */}
      <List items={items} />
    </>
  );
}

// Fix 2: memoize rows + stable callbacks so unrelated updates skip them.
const Row = React.memo(function Row({ item, onSelect }) {
  return <li onClick={() => onSelect(item.id)}>{item.name}</li>;
});

function List({ items }) {
  const onSelect = useCallback(id => dispatch(select(id)), []);
  return <ul>{items.map(it => <Row key={it.id} item={it} onSelect={onSelect} />)}</ul>;
}
```

3. **If it's a genuinely large list**, also **virtualize** it so even a real re-render only touches ~20 visible rows. The combination — colocated state, memoized stable-prop rows, and virtualization — is the standard cure for "typing is laggy because the list re-renders."

#### Q92. [Coding] Write a batched DOM updater that avoids layout thrashing by separating reads from writes across animation frames.

This formalizes the read-then-write discipline (Q32) into a reusable scheduler, the core idea behind FastDOM.

```js
const fastdom = (() => {
  let reads = [];
  let writes = [];
  let scheduled = false;

  function flush() {
    // Snapshot and clear so tasks added during flush run next frame.
    const r = reads; reads = [];
    const w = writes; writes = [];
    scheduled = false;

    // ALL reads first (forces at most one layout), THEN all writes.
    for (const task of r) task();
    for (const task of w) task();

    // If new work was queued during flush, schedule another frame.
    if (reads.length || writes.length) schedule();
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(flush);
  }

  return {
    measure(fn) { reads.push(fn); schedule(); },
    mutate(fn)  { writes.push(fn); schedule(); },
  };
})();

// Usage: equalize card heights WITHOUT thrashing.
const cards = [...document.querySelectorAll('.card')];
const heights = [];
cards.forEach((card, i) => {
  fastdom.measure(() => { heights[i] = card.offsetHeight; });   // batched reads
});
cards.forEach((card, i) => {
  fastdom.mutate(() => { card.style.height = Math.max(...heights) + 'px'; }); // batched writes
});
```

By collecting every read into one phase and every write into another, the browser flushes layout at most once per frame instead of once per read/write interleave. This converts O(n) forced reflows into O(1), which is the difference between janky and smooth when manipulating many elements.

#### Q93. [Practical] How would you investigate and fix slow Time to First Byte that only happens for some users?

Intermittent TTFB is a backend/edge problem masquerading as a frontend one. Investigation:

1. **Segment the field data** — break TTFB down by geography, cache status, route, and authenticated vs. anonymous. Patterns reveal the cause: slow only in one region (CDN PoP gap), slow only on cache miss (origin is slow), slow only for logged-in users (uncacheable personalized rendering).
2. **Read the Server-Timing header** — instrument the backend to expose where the time goes, surfaced right in DevTools and accessible from JS:

```js
// Server emits: Server-Timing: db;dur=320, render;dur=210, cache;desc=MISS
const nav = performance.getEntriesByType('navigation')[0];
nav.serverTiming.forEach(t => console.log(t.name, t.duration, t.description));
```

3. **Distinguish the phases** — `PerformanceNavigationTiming` splits TTFB into DNS, TCP, TLS, request, and `responseStart`. A slow TLS/DNS points at connection setup (preconnect, HTTP/3, fewer origins); a slow `responseStart` after the request points at server think-time.
4. **Common fixes by cause**:
   - Cache miss → improve cache-hit ratio, edge caching, ISR/SSG for cacheable routes.
   - Slow DB/render for some users → query optimization, caching the expensive computation, streaming SSR so the first byte flushes before the whole page renders.
   - Regional → ensure a CDN PoP near those users, or move compute to the edge.
   - Cold starts (serverless) → provisioned concurrency / keep-warm.

The key realization: **TTFB sets a floor under every other metric** — LCP can't beat it — so "slow for some users" TTFB is worth chasing even though it's not strictly frontend.

#### Q94. [Coding] Implement a yielding scheduler that processes a large array while keeping the page responsive and respecting a frame budget.

This combines yielding (Q27) with a per-frame time budget so you do as much work as fits in a frame, then yield — maximizing throughput without blowing the 16.6ms frame.

```js
async function processWithBudget(items, processItem, { frameBudget = 8 } = {}) {
  let i = 0;
  while (i < items.length) {
    const deadline = performance.now() + frameBudget;   // work for ~8ms, then yield
    // Drain as many items as fit in this frame's budget.
    while (i < items.length && performance.now() < deadline) {
      processItem(items[i], i);
      i++;
    }
    if (i < items.length) await yieldToMain();           // let input + paint happen
  }
}

function yieldToMain() {
  // scheduler.yield resumes with priority; setTimeout goes to the back of the queue.
  if (globalThis.scheduler?.yield) return scheduler.yield();
  return new Promise(resolve => setTimeout(resolve, 0));
}

// Process 50k records without ever freezing the UI for more than ~8ms.
await processWithBudget(records, (rec) => indexRecord(rec), { frameBudget: 8 });
```

The two-level loop is the trick: an inner loop that does work until the frame budget is spent, and an outer loop that yields between budgets. An 8ms budget leaves headroom in the 16.6ms frame for the browser's own style/layout/paint and for handling input. This is how you process tens of thousands of items while INP stays green — versus a naive `for` loop that produces one giant long task.

#### Q95. [Practical] The DevTools Performance panel shows a long "Recalculate Style" block. What causes it and how do you fix it?

A heavy "Recalculate Style" means the browser is re-matching CSS selectors and recomputing styles for many nodes. Causes and fixes:

1. **A large DOM + a style change near the root** — changing a class on `<body>` or a high ancestor invalidates style for the whole subtree. Fix: scope the change lower, or use CSS containment (`contain: style/layout`) to bound invalidation.
2. **Expensive selectors** — deep descendant selectors, universal selectors, `:nth-child` on huge lists, and complex `:has()` chains force more matching work. Fix: flatten/simplify selectors, prefer single-class selectors (BEM-style).
3. **Animating a property via class toggles in a loop** that re-triggers style recalc each frame. Fix: animate compositor properties (`transform`/`opacity`) and use `will-change` sparingly.
4. **Reading a computed style mid-mutation** (`getComputedStyle`) forcing a synchronous recalc — the layout-thrashing cousin. Fix: batch reads/writes.
5. **CSS custom properties on a high ancestor** — changing a `--var` on `:root` can invalidate every element that references it. Fix: scope the variable to the smallest subtree that needs it.

```css
/* Bound style + layout invalidation to each widget so a change inside
   one doesn't trigger a whole-page Recalculate Style. */
.widget { contain: content; }
```

The diagnostic move: in the Performance panel, click the "Recalculate Style" block — it tells you the **element count** affected and often the originating change. A high element count = invalidation is too broad; narrow its scope with containment and lower-placed changes.

#### Q96. [Coding] Write an image lazy-loader using IntersectionObserver with a low-quality placeholder swap, handling load errors.

```js
function createLazyImageLoader({ rootMargin = '300px' } = {}) {
  const observer = new IntersectionObserver((entries, obs) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const img = entry.target;
      obs.unobserve(img);                 // one-shot per image
      loadFull(img);
    }
  }, { rootMargin, threshold: 0 });       // start loading before it's in view

  function loadFull(img) {
    const fullSrc = img.dataset.src;
    const fullSrcset = img.dataset.srcset;
    // Preload into a detached Image so we only swap once it's actually ready
    // (prevents a flash of broken/half-loaded image over the LQIP).
    const loader = new Image();
    loader.onload = () => {
      if (fullSrcset) img.srcset = fullSrcset;
      img.src = fullSrc;
      img.classList.add('is-loaded');     // CSS can fade out the blur here
    };
    loader.onerror = () => {
      img.classList.add('is-error');      // keep the LQIP or show a fallback
      img.src = '/img/fallback.svg';
    };
    if (fullSrcset) loader.srcset = fullSrcset;
    loader.src = fullSrc;
  }

  return {
    observe: (img) => observer.observe(img),
    observeAll: () => document.querySelectorAll('img[data-src]').forEach(i => observer.observe(i)),
    disconnect: () => observer.disconnect(),
  };
}

const lazy = createLazyImageLoader();
lazy.observeAll();
```

```html
<!-- LQIP (tiny blurred placeholder) shows instantly; full image swaps in. -->
<img src="data:image/svg+xml,...tiny-blur..." data-src="photo-1600.avif"
     data-srcset="photo-800.avif 800w, photo-1600.avif 1600w"
     width="800" height="600" alt="..." class="lazy-img">
```

Two production details: `rootMargin` pre-triggers loading before the image scrolls into view (no visible pop-in), and loading into a **detached `Image`** before swapping means the user never sees a partially decoded image flicker over the placeholder. Note that native `loading="lazy"` covers the simple case — reach for this when you need LQIP blur-up, error fallbacks, or fine control over the trigger margin. Always keep explicit `width`/`height` to protect CLS.

#### Q97. [Practical] How do you decide whether a slow page is network-bound, CPU-bound, or render-bound? Walk through the signals.

This classification determines your entire fix strategy, so it's the first question to answer. The signals:

```
Bottleneck     Symptom in the Performance panel        Field signal           Fix direction
------------   -------------------------------------   --------------------   -----------------------
Network-bound  long gaps in the Network track;         high TTFB / load        CDN, compression,
               main thread IDLE waiting for bytes      time in LCP             caching, fewer/smaller
                                                       attribution             requests, HTTP/3
CPU-bound      main thread SOLID with scripting;       high TBT/INP; long      ship less JS, code
               long tasks; "Evaluate Script",          tasks in field          split, Web Workers,
               "Recalculate Style/Layout"                                      yield, hydrate less
Render-bound   heavy "Layout"/"Paint"/"Composite";     poor INP on             reduce DOM, CSS
               dropped frames during scroll/animation  interaction; jank       containment, content-
                                                                               visibility, transform
```

The decisive observation: **is the main thread busy or idle while the page is slow?**
- **Idle main thread + waiting on the network** → network-bound. The fix is getting bytes to the user faster.
- **Busy main thread running JS** → CPU-bound. The fix is doing less work on the main thread.
- **Busy main thread in Layout/Paint** → render-bound. The fix is making the rendering work cheaper (fewer nodes, containment, compositor-only animation).

Practically: record a trace, look at the main-thread track. Gaps = network; solid yellow (scripting) = CPU; solid purple/green (layout/paint) = render. A page can be more than one, so fix the *dominant* cost first, re-measure, and reassess — the bottleneck often shifts after the first fix.

### 🟠 — extended

#### Q98. [Practical] You inherit a 5 MB JavaScript bundle. Lay out a concrete, prioritized plan to cut it down.

A 5 MB bundle is a multi-week effort; the key is sequencing by impact-per-effort and measuring at every step.

1. **Measure and baseline first** — bundle analyzer treemap (what's big), and field RUM for LCP/INP/TBT (what users feel). Set a target and a CI size gate *now* so it can't get worse while you work.
2. **Quick wins (days)**:
   - Confirm production mode (minification, no dev builds of React/etc.) — a surprising number of "huge bundles" are accidentally dev builds.
   - Enable compression (brotli) if not already.
   - Replace heavyweight libs: `moment` → `date-fns`/`dayjs`, full `lodash` → `lodash-es` with named imports, big icon sets → tree-shaken per-icon imports.
   - De-duplicate transitive deps (one React, one `tslib`).
3. **Code splitting (weeks)**:
   - **Route-level splitting** first — the single biggest lever; users download only the current route.
   - **Component-level splitting** for heavy, rarely-used widgets (editors, charts, modals, maps) behind dynamic `import()`.
   - Split vendor chunks so app code changes don't bust the cache on stable dependencies.
4. **Structural (longer)**:
   - Ensure tree shaking works (ESM, `"sideEffects"` set correctly).
   - Consider Server Components / islands to move non-interactive code off the client entirely.
   - Audit polyfills — ship modern syntax to modern browsers (`module`/`nomodule` or a modern-only build).
5. **Lock it in** — the CI size gate plus a per-route budget so the gains don't erode.

The sequencing principle: **route splitting and removing/replacing the few biggest offenders usually recover the majority of the weight cheaply.** Don't start with micro-optimizations; start with the analyzer's biggest boxes and the highest-traffic routes.

#### Q99. [Coding] Implement an LRU cache for API responses with a TTL, suitable for client-side request deduplication.

A client-side cache that dedupes in-flight requests and bounds memory is a common performance primitive for SPAs.

```js
class LRUTTLCache {
  constructor({ maxSize = 100, ttl = 60_000 } = {}) {
    this.max = maxSize;
    this.ttl = ttl;
    this.map = new Map();        // insertion order = recency (Map preserves order)
    this.inflight = new Map();   // dedupe concurrent requests for the same key
  }

  _isFresh(entry) { return Date.now() - entry.time < this.ttl; }

  get(key) {
    const entry = this.map.get(key);
    if (!entry) return undefined;
    if (!this._isFresh(entry)) { this.map.delete(key); return undefined; }
    // Touch: move to most-recently-used position.
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.value;
  }

  set(key, value) {
    if (this.map.has(key)) this.map.delete(key);
    this.map.set(key, { value, time: Date.now() });
    // Evict least-recently-used (the first key) when over capacity.
    if (this.map.size > this.max) this.map.delete(this.map.keys().next().value);
  }

  // Fetch with dedupe: concurrent callers for the same key share one request.
  async fetch(key, fetcher) {
    const cached = this.get(key);
    if (cached !== undefined) return cached;
    if (this.inflight.has(key)) return this.inflight.get(key);

    const promise = fetcher(key)
      .then(value => { this.set(key, value); return value; })
      .finally(() => this.inflight.delete(key));
    this.inflight.set(key, promise);
    return promise;
  }
}

const cache = new LRUTTLCache({ maxSize: 200, ttl: 30_000 });
const user = await cache.fetch(`/user/${id}`, k => fetch(k).then(r => r.json()));
```

The three performance behaviors that matter: **LRU eviction** bounds memory (no unbounded growth in a long SPA session), **TTL** prevents serving stale data, and the **inflight map** dedupes — if ten components request the same user simultaneously, you fire one network request, not ten. This is essentially a minimal version of what SWR/React Query do.

#### Q100. [Practical] After enabling SSR, your TTFB got worse and INP didn't improve. How do you reason about whether SSR was the right call?

This is a nuanced trade-off question. SSR moves rendering work to the server, which *helps* FCP/LCP (content arrives in HTML) but can *hurt* TTFB (the server now does work before sending the first byte) and does *nothing* for INP if you still ship and hydrate the same heavy bundle.

How to reason about it:
1. **Separate the metrics SSR affects.** SSR's win is faster *FCP/LCP* and SEO — content is in the initial HTML. Its costs are *server compute* (higher TTFB) and *hydration* (INP risk). If your TTFB went up but LCP came down and net-net LCP is better, SSR may still be winning *on loading*.
2. **TTFB regression** — if SSR made TTFB much worse, the server render is too slow (slow data fetching, no caching, blocking render). Fixes: cache the rendered output (ISR/edge caching), **stream** the response (`renderToPipeableStream` + Suspense) so the first byte flushes before the whole page renders, and parallelize server data fetching to avoid server-side waterfalls.
3. **INP didn't improve — and it wouldn't.** SSR doesn't reduce client JS; you still download and hydrate the same app. If anything, hydration is a *new* INP risk (the page looks ready but isn't interactive). To improve INP you need to ship *less* JS: Server Components (zero client JS for static parts), islands, or selective hydration.
4. **Decide per route.** A static marketing page wants SSG (best TTFB, no server cost). A personalized dashboard wants SSR (fresh + SEO) — but only if you stream and cache. A private, highly interactive app may be fine as CSR.

The mature conclusion: **SSR is not a blanket performance win.** It trades TTFB and hydration cost for first-paint and SEO. If you adopted it expecting INP gains, that was the wrong reason — the INP fix is *less JavaScript* (RSC/islands), and the TTFB regression means you need streaming and caching. Re-evaluate per route by what each one actually needs.

#### Q101. [Coding] Write a performance-instrumentation wrapper that measures and reports the duration of any function using the User Timing API.

The User Timing API (`performance.mark`/`measure`) integrates with the DevTools Performance panel (marks show in the timeline) and is the standard way to instrument custom timings for RUM.

```js
function instrument(name, fn) {
  return function instrumented(...args) {
    const startMark = `${name}-start`;
    const endMark = `${name}-end`;
    performance.mark(startMark);

    const finish = () => {
      performance.mark(endMark);
      const measure = performance.measure(name, startMark, endMark);
      // Report to RUM; also visible in the DevTools "Timings" track.
      navigator.sendBeacon?.('/rum', JSON.stringify({
        name, duration: measure.duration,
      }));
      // Clean up so the entry buffer doesn't grow unbounded.
      performance.clearMarks(startMark);
      performance.clearMarks(endMark);
      performance.clearMeasures(name);
      return measure.duration;
    };

    const result = fn.apply(this, args);
    // Handle both sync and async functions correctly.
    if (result instanceof Promise) {
      return result.finally(finish);
    }
    finish();
    return result;
  };
}

// Usage — these timings appear in the Performance panel AND your RUM.
const renderDashboard = instrument('render-dashboard', () => buildDashboard(data));
const loadData = instrument('load-data', async () => (await fetch('/api')).json());
```

Two things interviewers check: handling **both sync and async** functions (await/`.finally` for promises, immediate finish otherwise), and **cleaning up** marks/measures so the `performance` entry buffer doesn't leak over a long session. The payoff is that `performance.measure` entries show up natively in the DevTools "Timings" track *and* can be observed via `PerformanceObserver({ type: 'measure' })` for field reporting — one instrumentation, two consumers.

#### Q102. [Practical] A single-page app gets progressively slower the longer a user keeps the tab open. What are the likely causes and how do you confirm?

Progressive slowdown over a session is almost always a **leak** (memory or listener) or **unbounded accumulation**. Likely causes:

1. **Detached DOM nodes** — components unmount but references (in closures, caches, event handlers, observers) keep their DOM subtrees alive. The heap grows; GC pauses lengthen; jank increases.
2. **Event listeners / observers never removed** — every route change adds listeners (`scroll`, `resize`, `IntersectionObserver`, `setInterval`, WebSocket handlers) without cleanup, so they pile up and all fire.
3. **Unbounded caches/arrays** — a log array, a message list, or a memoization cache that only ever grows.
4. **Timers / animation loops** that aren't cancelled, multiplying with each navigation.

How to confirm:
```js
// 1) Watch the trend in production for the cohort that keeps tabs open.
const m = await performance.measureUserAgentSpecificMemory();
console.log(m.bytes);   // sample periodically; a steady climb = leak

// 2) In DevTools Memory panel: take heap snapshots over time, use the
//    "Comparison" view between snapshots, and filter for "Detached" nodes.
//    A growing count of Detached HTMLDivElement = unmounted-but-retained DOM.
```

The workflow: reproduce by exercising the app (navigate routes repeatedly), take heap snapshots at intervals, and **diff** them — objects that keep accumulating are your leak. Follow the **retainer path** to the reference holding them alive, then fix with proper teardown:

```js
useEffect(() => {
  const ctrl = new AbortController();
  window.addEventListener('resize', onResize, { signal: ctrl.signal });
  const id = setInterval(poll, 5000);
  return () => { ctrl.abort(); clearInterval(id); };   // cleanup on unmount
}, []);
```

The framing: a leak is a **performance** bug, not just a memory bug — retained memory inflates GC pause times (more live objects to scan → longer main-thread stalls → INP regressions) and eventually triggers swapping or tab kills on low-RAM devices.

#### Q103. [Coding] Implement a function that splits a heavy synchronous computation across `requestIdleCallback` so it never blocks user input.

Unlike the frame-budget approach (Q94), `requestIdleCallback` runs work *only in the browser's leftover idle time* — ideal for genuinely non-urgent background work (prefetching, indexing, analytics) that must never compete with user interactions.

```js
function runInIdle(workItems, processItem, { timeout = 2000 } = {}) {
  return new Promise((resolve) => {
    let i = 0;
    function step(deadline) {
      // Work while there's idle time AND items remain.
      while (i < workItems.length &&
             (deadline.timeRemaining() > 1 || deadline.didTimeout)) {
        processItem(workItems[i], i);
        i++;
      }
      if (i < workItems.length) {
        // Yield; resume in the next idle period (timeout guarantees progress
        // even if the browser never goes idle on a busy page).
        requestIdleCallback(step, { timeout });
      } else {
        resolve();
      }
    }
    if ('requestIdleCallback' in window) {
      requestIdleCallback(step, { timeout });
    } else {
      // Fallback: time-sliced setTimeout for browsers without rIC (Safari).
      (function fallback() {
        const end = performance.now() + 8;
        while (i < workItems.length && performance.now() < end) {
          processItem(workItems[i], i); i++;
        }
        if (i < workItems.length) setTimeout(fallback, 0);
        else resolve();
      })();
    }
  });
}

// Build a client-side search index without ever janking the UI.
await runInIdle(documents, doc => searchIndex.add(doc));
```

The critical detail is the **`timeout` option**: on a busy page the browser may never report idle time, so `requestIdleCallback` could starve. The timeout forces the callback to run (with `didTimeout = true`) after the deadline, guaranteeing the work eventually completes. Use `requestIdleCallback` for "nice to have, never urgent" work and the frame-budget approach (Q94) for "needs to finish soon but mustn't freeze the UI."

#### Q104. [Practical] Your INP regressed from 150ms to 400ms after a release, but no code looks obviously slow. How do you hunt it down?

A non-obvious INP regression needs attribution data, not guesswork. The hunt:

1. **Confirm it's real and find which interaction** — use INP attribution in RUM to identify the *interaction target* and the *dominant phase*. "INP got worse" is useless; "the filter dropdown's processing time doubled" is actionable.

```js
import { onINP } from 'web-vitals/attribution';
onINP(m => navigator.sendBeacon('/rum', JSON.stringify({
  value: m.value,
  target: m.attribution.interactionTarget,
  inputDelay: m.attribution.inputDelay,
  processing: m.attribution.processingDuration,
  presentation: m.attribution.presentationDelay,
  loadState: m.attribution.loadState,
})), { reportAllChanges: true });
```

2. **Read the phase breakdown**:
   - **inputDelay up** → the main thread is busier when interactions arrive — likely a new long task (a third-party script, more hydration work, an eager `useEffect`). Check the Long Tasks track.
   - **processingDuration up** → an event handler now does more — a new synchronous re-render, a heavier computation in the handler, a context that now re-renders more consumers.
   - **presentationDelay up** → the DOM update after the handler got bigger/more expensive — more nodes, a layout-thrashing change, a removed `content-visibility`.
3. **Bisect the release** — diff the deploy. Common silent INP killers: a new third-party tag, a dependency bump that added sync work, an accidental removal of `React.memo`/virtualization, a new global effect, or a CSS change that made layout more expensive.
4. **Reproduce in the lab** — once you know the interaction, record it in the Performance panel with the Interactions track and CPU throttling to see the long task directly.

The insight: **INP is per-interaction**, so a regression often lives in *one* interaction that a release subtly made heavier — and only attribution data (which target, which phase) makes it findable. Aggregate INP tells you *that* it regressed; attribution tells you *where*.

#### Q105. [Coding] Write a debounce that supports a `maxWait` so a continuously-typing user still gets periodic updates.

Plain debounce has a starvation problem: if the user never pauses, the function *never* runs. `maxWait` guarantees it fires at least every `maxWait` ms regardless — the behavior real search-as-you-type needs.

```js
function debounce(fn, wait, { maxWait } = {}) {
  let timer = null;
  let lastCall = 0;       // time of the most recent invocation request
  let lastRun = 0;        // time fn last actually ran
  let lastArgs, lastThis;

  function run() {
    lastRun = Date.now();
    timer = null;
    fn.apply(lastThis, lastArgs);
  }

  function debounced(...args) {
    const now = Date.now();
    lastArgs = args; lastThis = this;
    if (!lastRun) lastRun = now;

    // If maxWait elapsed since the last run, fire NOW instead of waiting.
    const forcedByMax = maxWait != null && (now - lastRun) >= maxWait;
    if (forcedByMax) {
      if (timer) clearTimeout(timer);
      run();
      return;
    }
    // Otherwise: classic debounce — reset the trailing timer.
    if (timer) clearTimeout(timer);
    timer = setTimeout(run, wait);
  }

  debounced.cancel = () => { if (timer) clearTimeout(timer); timer = null; lastRun = 0; };
  debounced.flush  = () => { if (timer) { clearTimeout(timer); run(); } };
  return debounced;
}

// Search: update 300ms after a pause, but at least every 1s of continuous typing.
input.addEventListener('input', debounce(e => search(e.target.value), 300, { maxWait: 1000 }));
```

Without `maxWait`, a fast continuous typist gets *zero* search results until they stop — a real UX failure for autocomplete. With `maxWait: 1000`, they get a refreshed result set at least once per second even while typing nonstop. The `cancel`/`flush` methods matter too: `cancel` on unmount (avoid a setState-after-unmount leak/warning), `flush` to force the pending call (e.g. on form submit). This is the Lodash debounce contract, which exists precisely because the naive version starves.

#### Q106. [Practical] How would you set up automated performance regression detection in CI so a bad PR can't merge?

A layered gate strategy, cheapest/most-deterministic first:

1. **Bundle-size gate (every PR, seconds)** — `size-limit` or `bundlesize` fails the build if any chunk exceeds its byte budget. Deterministic, no flakiness, catches the most common regression (bundle bloat) instantly.

```yaml
# size-limit config: hard caps per entry, checked on every PR.
[ { "path": "dist/main.*.js", "limit": "170 KB" },
  { "path": "dist/vendor.*.js", "limit": "120 KB" } ]
```

2. **Lighthouse CI on a preview deploy (every PR, minutes)** — run Lighthouse against a real preview URL, assert metric thresholds, and comment the report on the PR.

```yaml
assert:
  assertions:
    largest-contentful-paint: ["error", { maxNumericValue: 2500 }]
    total-blocking-time:      ["error", { maxNumericValue: 200 }]
    cumulative-layout-shift:  ["error", { maxNumericValue: 0.1 }]
    "resource-summary:script:size": ["error", { maxNumericValue: 180000 }]
```

3. **Handle lab variance** — Lighthouse numbers are noisy, so run **3–5 times and take the median**, and set thresholds with margin to avoid flaky failures that erode trust in the gate.
4. **Field RUM alerting (post-merge)** — lab gates can't catch everything (real devices, networks, third parties), so track production CWV (CrUX or your own web-vitals beacon) and alert on p75 regressions so you catch what slipped through within hours, not a quarter later.

The cultural requirement, which matters as much as the tooling: the gate must be a **blocking build failure owned by the PR author**, with budgets that are visible and occasionally revisited — not a dashboard nobody reads. Feedback at PR time, on the author, is what actually prevents regressions; everything detected later is cleanup.

### 🔴 — extended

#### Q107. [Practical] You're the perf lead. A team wants to add a 300 KB third-party personalization script that marketing insists on. How do you handle it technically and organizationally?

This is the realistic staff-level scenario: you can't just say no, and you can't let it tank INP/LCP. Handle both dimensions:

**Technical mitigation — control *when* and *where* it runs:**
1. **Quantify the cost first** — measure its real impact in a controlled test: added bytes, main-thread time, INP delta, LCP delta. Bring data, not opinions, to the negotiation.
2. **Get it off the critical path** — `async`, and ideally delay loading until after the page is interactive or on first interaction. Personalization rarely needs to run before first paint.
3. **Move execution off the main thread** — run it in a Web Worker via **Partytown** so its JS doesn't block your INP. Many tag-manager/personalization scripts work this way.
4. **Facade / lazy-trigger** — if it powers a specific widget, load it only when that widget is needed.
5. **`preconnect`** to its origin so when it does load, the connection is warm.
6. **Sandbox and budget it** — give third parties a strict performance budget and monitor them separately in RUM so you can prove which one regressed if metrics slip.

**Organizational — make the trade-off explicit and owned:**
1. **Translate cost to business terms** — "this script adds ~250ms to INP, which our data correlates with ~X% conversion drop; the personalization needs to lift conversion by more than that to be net-positive." Now it's a business decision, not a perf-vs-marketing fight.
2. **Set acceptance criteria** — agree up front on a budget the script must fit within and a kill-switch if field metrics regress past a threshold.
3. **A/B test it** — ship to a fraction with the perf cost measured *and* the personalization benefit measured, and let the data decide.

The mature stance: **your job isn't to block features, it's to make the cost visible and the fast path the default.** Mitigate technically (off-thread, deferred, budgeted, monitored) so the cost is minimized, and frame the residual cost as a business trade-off the owners consciously accept — with a measurable kill-switch if reality diverges from the pitch.

#### Q108. [Theory] Design the architecture for a real-user-monitoring system that captures CWV, custom metrics, and attribution at scale without itself hurting performance.

A RUM system that degrades the page it measures is self-defeating, so the design constraints are *low overhead, sampled, and resilient*.

```
Browser (web-vitals + custom marks)
   │  batch + sendBeacon on visibilitychange (survives unload)
   ▼
Edge collector (cheap, regional)  ──►  Stream/queue (Kafka/Kinesis)
   │                                       │
   ▼                                       ▼
Sampling + validation                 Aggregation (p75/p95 by segment)
   │                                       │
   ▼                                       ▼
Cheap columnar store (ClickHouse/BigQuery)  ──►  Dashboards + alerting
```

Design decisions:
1. **Collect on the client cheaply** — use the `web-vitals` library (it handles the metric quirks), batch events, and flush with `navigator.sendBeacon()` on `visibilitychange → hidden` (the only reliable "page is leaving" signal; `unload` is unreliable and breaks bfcache). Never send synchronously or block the main thread.
2. **Capture attribution, not just values** — store *which* LCP element, *which* INP interaction and phase, *which* CLS element. Aggregate values tell you something's wrong; attribution tells you what.
3. **Segment richly** — device class, `effectiveType`, country, route, release version, A/B bucket, logged-in state. The p75 average hides the stories; segments reveal them.
4. **Sample intelligently** — you don't need 100% for stable percentiles; sample (e.g. 10%) to control cost, but **over-sample errors and the slow tail** so you keep visibility into p95/p99 where the worst-served users live.
5. **Watch the tail, not just p75** — CWV judges p75, but your *at-risk* users are in the p95/p99 tail on low-end devices; alert on both.
6. **Keep the agent tiny** — the RUM script itself must be small, async, and never render-blocking; ironically a heavy RUM agent is a common perf regression.
7. **Correlate with business metrics** — join CWV to conversion/bounce so you can quantify the dollar impact of a regression, which is what gets perf work funded.

The architectural principle: **measure real users at the percentiles and segments that matter, store attribution so regressions are debuggable, sample to stay cheap, and make the agent itself featherweight** — a RUM system that hurts performance has failed at its one job.

#### Q109. [Practical] A critical user flow is fast on its own but slow in production because of everything else competing for resources. How do you reason about and fix resource contention?

This is the "death by a thousand cuts" scenario — each piece is individually reasonable but together they starve the critical flow. The reasoning framework:

1. **Identify the contended resource** — contention happens on a finite resource:
   - **Main thread** — too many scripts (analytics, A/B, widgets, hydration) all wanting CPU, so the critical interaction queues behind them (inflated INP input delay).
   - **Network/bandwidth** — too many concurrent fetches competing, so the LCP image arrives late even though it's prioritized.
   - **Connections** — many third-party origins each needing DNS/TCP/TLS.
   - **Memory/GPU** — too many compositor layers or retained objects causing GC pauses.

2. **Establish priority** — the critical flow's resources must win. Tools:
   - **`fetchpriority`** to push the critical resource ahead and demote the rest.
   - **Deferral** — load non-critical scripts/widgets *after* the critical flow completes (post-interaction, on idle).
   - **Off-main-thread** — move competing JS to Web Workers (Partytown for third parties) so it can't steal main-thread time from the critical interaction.
   - **Connection budget** — `preconnect` only the origins on the critical path; lazy-connect the rest.

3. **Reproduce the *contended* condition** — the trap is testing the flow in isolation (where it's fast). You must profile it **with everything else running** — full third-party load, real cache state, real concurrency — to see the contention. Use the Performance panel with the actual production script set, not a clean page.

4. **Measure in the field** — RUM with attribution will show the critical interaction's input delay rising when other work is heavy, confirming contention rather than the handler itself being slow.

The governing insight: **performance is a system property, not a per-feature property.** A fast feature in a slow page is still slow to the user. The fix is *prioritization and isolation* — guarantee the critical flow's share of the main thread and network by deferring, off-loading, and de-prioritizing everything that competes with it, then verify under realistic contention, not in isolation.

#### Q110. [Coding] Implement a priority-based task scheduler (like a userland version of scheduler.postTask) that runs high-priority work first and yields to the browser.

When you have many deferred tasks of varying importance, a priority queue that yields between tasks keeps urgent work first while never blocking input.

```js
class PriorityScheduler {
  constructor() {
    // Higher number = higher priority. 3=user-blocking, 2=user-visible, 1=background.
    this.queues = { 3: [], 2: [], 1: [] };
    this.running = false;
  }

  postTask(fn, { priority = 2 } = {}) {
    return new Promise((resolve, reject) => {
      this.queues[priority].push({ fn, resolve, reject });
      this._schedule();
    });
  }

  _next() {
    for (const p of [3, 2, 1]) {                 // drain highest priority first
      if (this.queues[p].length) return this.queues[p].shift();
    }
    return null;
  }

  async _schedule() {
    if (this.running) return;
    this.running = true;
    let task;
    while ((task = this._next())) {
      try { task.resolve(await task.fn()); }
      catch (e) { task.reject(e); }
      // Yield after each task so input/render can interleave. Prefer the
      // platform scheduler so the browser can re-prioritize around input.
      await (globalThis.scheduler?.yield?.() ??
             new Promise(r => setTimeout(r, 0)));
    }
    this.running = false;
  }
}

const sched = new PriorityScheduler();
sched.postTask(() => renderVisibleRows(), { priority: 3 });   // user-blocking
sched.postTask(() => prefetchNextPage(),  { priority: 1 });   // background
sched.postTask(() => updateAnalytics(),   { priority: 1 });
```

This mirrors the native **`scheduler.postTask()`** (Prioritized Task Scheduling API), which you should prefer when available — it integrates with the browser's own scheduler so input and rendering can preempt your tasks. The userland version is the fallback and illustrates the model: a multi-level queue drained highest-first, **yielding across a macrotask boundary between tasks** so the browser can paint and process input. The yield is what keeps it from becoming one long task; the priority ordering is what keeps urgent work ahead of background work.

#### Q111. [Practical] Walk through a complete performance audit of an unfamiliar production web app, from first load to a prioritized remediation plan.

A staff-level audit is systematic and ends in a *prioritized, measured* plan — not a list of observations.

1. **Establish the baseline from field data first** — pull CrUX / RUM for the real CWV at p75 *and* the p95 tail, segmented by device, network, geography, and key routes. This tells you *whether* there's a problem and *for whom*, before you touch a lab tool.
2. **Identify the critical user journeys** — audit the flows that matter (landing → key conversion), not every page. Performance is about the journeys that drive the business.
3. **Lab-profile each critical page** with realistic throttling (mobile, slow-4G, 4× CPU):
   - **Network** — waterfall: request count, sizes, render-blocking resources, third parties, compression, caching headers, HTTP version, TTFB breakdown via Server-Timing.
   - **Main thread** — long tasks, TBT, scripting vs. rendering split, hydration cost.
   - **Rendering** — layout/paint cost, CLS sources, scroll jank.
   - **Assets** — image formats/sizes, font loading, JS bundle composition (analyzer).
4. **Attribute the headline metrics** — for each failing CWV, get attribution: LCP element + phase breakdown, INP interaction + phase, CLS element + time. This converts "LCP is bad" into "the hero is a lazy-loaded unoptimized JPEG behind render-blocking CSS."
5. **Classify each finding** as network-, CPU-, or render-bound (Q97) so the fix direction is clear.
6. **Prioritize by impact × effort** — build a ranked list: high-impact/low-effort first (fix the LCP image, enable compression, defer a third party), structural changes later (code splitting, RSC/islands, render-strategy changes per route).
7. **Quantify expected impact and tie to business** — estimate the metric movement and, where possible, the conversion/revenue implication, so the plan competes for prioritization.
8. **Lock in gains** — propose CI budgets (size-limit, Lighthouse CI) and RUM alerting so fixes don't regress.

The deliverable is a **prioritized remediation roadmap**: each item has the evidence (which metric, which attribution), the expected impact, the effort, and a way to verify and prevent regression. The discipline throughout is **field-first to know what's real, lab to diagnose why, attribution to pinpoint, and impact-ordering to sequence** — never a flat checklist of micro-optimizations.

#### Q112. [Theory] Make the case for and against adopting a "zero client-side JavaScript by default" architecture (RSC / islands / Qwik). What does a staff engineer weigh?

This is the defining frontend-architecture debate of the mid-2020s, and a staff engineer should hold both sides.

**The case for (the performance argument):**
- JavaScript is the most expensive resource byte-for-byte (parse + compile + execute on the main thread). Shipping *zero* JS for non-interactive content eliminates the biggest INP/TBT risk entirely.
- Hydration is pure overhead — re-doing on the client what the server already did. Architectures that avoid it (RSC ships no JS for server components; islands hydrate only interactive bits; Qwik resumes instead of hydrating) attack the root cause rather than mitigating it.
- It scales the right way: the cost is proportional to *interactivity*, not page size. A content-heavy page stays cheap.
- It makes the fast path the default — engineers opt *into* client JS rather than shipping it by accident.

**The case against (the cost argument):**
- **Complexity and mental model** — the server/client boundary (what runs where, serialization constraints, what can't cross the boundary) is genuinely hard; teams ship bugs at the seams.
- **Ecosystem maturity** — many libraries assume client execution; the server-first world has gaps, footguns, and rapidly-changing APIs.
- **Infrastructure cost** — server rendering (especially per-request RSC/SSR) shifts cost from the client's CPU to *your* servers and adds operational complexity; SSG/edge mitigates but not for everything.
- **Not always the bottleneck** — for a small, simple, or already-fast app, the migration cost dwarfs the benefit. For a private internal tool where SEO and cold-load don't matter, CSR is fine.
- **Debugging and observability** span two environments now.

**What a staff engineer weighs:**
- *Where is the actual bottleneck?* If field INP/LCP are fine, this is a solution looking for a problem.
- *What's the interactivity profile?* Content-heavy, public, SEO-sensitive sites benefit most; highly interactive app-shells benefit least.
- *Team capability and ecosystem fit* — can the team absorb the boundary model and live with the library constraints?
- *Incremental adoption* — you rarely need to bet the whole app; islands and RSC support per-route adoption, so you can apply server-first where it pays (content/marketing) and keep CSR where it doesn't.

The mature conclusion: **"zero JS by default" is the right *direction* for content-and-commerce sites where loading and INP are real business metrics, applied incrementally where it pays — and an over-correction for small, private, or already-fast apps where the complexity and infra cost outweigh the win.** The staff move is to decide from field data and interactivity profile, adopt per-route rather than all-or-nothing, and not cargo-cult an architecture because it's fashionable.

## ✅ Key Takeaways

- **Core Web Vitals are the scoreboard**: LCP (loading ≤ 2.5s), INP (interactivity ≤ 200ms, replaced FID in 2024), CLS (stability ≤ 0.1), judged at p75 of *real* users.
- **The critical rendering path** (DOM + CSSOM → render tree → layout → paint → composite) is the mental model; CSS is render-blocking and synchronous JS is parser-blocking.
- **Ship less JavaScript** — it's the most expensive resource byte-for-byte (parse + compile + execute on the main thread). Code split, tree shake, and prefer server rendering / islands / RSC.
- **Animate `transform`/`opacity`** (compositor-only); avoid animating layout properties, which force reflow every frame.
- **Caching is layered**: content-hashed `immutable` assets + `ETag` revalidation for HTTP caching, and service workers (stale-while-revalidate) for programmable/offline caching.
- **Optimize images and fonts first** — they're usually the heaviest assets and the most common LCP element. Modern formats (AVIF/WebP), responsive `srcset`, WOFF2, and always reserve space to protect CLS.
- **Resource hints** (`preload`/`preconnect`/`prefetch`) and Speculation Rules let you do critical work early and make navigations feel instant.
- **Measure first**: lab tools (Lighthouse, DevTools) to *diagnose*, field RUM (web-vitals + CrUX) to *judge*. Enforce budgets in CI to prevent regression.
- **Keep the main thread free**: break up long tasks, yield, debounce/throttle, virtualize long lists, use Web Workers, and `content-visibility` to skip offscreen work — all directly improving INP.

## ⚠️ Common Pitfalls

- Lazy-loading the LCP / hero image (`loading="lazy"`), which delays the very metric you're trying to improve.
- Optimizing on a fast laptop and trusting Lighthouse scores while real users on mid-tier phones over 4G struggle — always validate in the field.
- Images and embeds without `width`/`height`/`aspect-ratio`, causing layout shift (CLS) when they load.
- Layout thrashing — interleaving DOM reads (`offsetHeight`, `getBoundingClientRect`) and writes in a loop, forcing synchronous reflow repeatedly.
- Animating `top`/`left`/`width`/`height` instead of `transform`, forcing reflow on every frame and dropping below 60fps.
- Overusing `will-change` or `preload`, which respectively waste GPU memory and steal bandwidth from genuinely critical resources.
- Treating FID-era thinking ("just make the first click fast") as sufficient — INP measures *every* interaction across the whole session.
- Assuming minification = tree shaking, or that bundling everything is still best (HTTP/2/3 multiplexing weakened that case).
- Storing/serving HTML with long `max-age` so users get stale asset references; HTML should be revalidated while hashed assets are `immutable`.
- Letting third-party scripts run synchronously on the main thread; defer, facade, or offload (Partytown) them instead.
- Mis-sizing `contain-intrinsic-size` with `content-visibility`, causing scrollbar jumpiness; ignoring its find-in-page/accessibility edge cases.
- Shipping a fully client-rendered app where SEO and first paint matter, then bolting on SSR as an afterthought instead of choosing rendering strategy per route.

## 📚 Further Reading

- **web.dev / Core Web Vitals** (web.dev/vitals) — Google's authoritative, continually-updated guides on LCP, INP, CLS, and how to optimize each. The single best starting point.
- **MDN Web Docs — Performance** (developer.mozilla.org/en-US/docs/Web/Performance) — reference on the critical rendering path, resource hints, the Performance APIs, and rendering internals.
- **"High Performance Browser Networking"** by Ilya Grigorik — the definitive deep dive into TCP/TLS/HTTP, HTTP/2 & HTTP/3/QUIC, and how the network shapes performance (free online).
- **Chrome DevTools & Lighthouse documentation** (developer.chrome.com) — how to profile with the Performance panel, run Lighthouse/Lighthouse CI, and interpret traces.
- **The `web-vitals` library** (github.com/GoogleChrome/web-vitals) — the canonical way to measure CWV in the field, including the attribution build for debugging.
- **"Inside look at modern web browser"** (developer.chrome.com series) and **Paul Lewis / Surma's rendering performance articles** — how the browser's threads, layers, and compositor actually work.
- **Patterns.dev** (patterns.dev) — rendering patterns (SSR/SSG/ISR/islands/RSC), loading patterns, and performance-oriented design patterns with examples.
- **Chrome User Experience Report (CrUX)** and **PageSpeed Insights** — real-world field data for any origin, and the lab+field combined report Google uses.
