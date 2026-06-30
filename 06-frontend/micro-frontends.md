# Micro-Frontends

[← Back to master index](../README.md)

A tier-based interview guide to micro-frontends (MFEs) — the architectural style that extends microservice thinking to the browser, letting independent teams build, test, and deploy slices of a single web application autonomously. It covers motivation and trade-offs, integration approaches (build-time, server-side, and run-time), Webpack and Vite Module Federation, Web Components, routing across MFEs, shared dependencies and state, styling isolation, cross-MFE communication, independent deployment, performance pitfalls, and when the pattern is overkill. Content is current through 2026, reflecting Module Federation 2.0, native Federation for Vite/esbuild, and modern import-map tooling.

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

### Q1. [Theory] What is a micro-frontend?

A **micro-frontend** is an architectural style where a web application's frontend is decomposed into smaller, independently deliverable units, each owned end-to-end by a separate team. The term, popularized around 2016 and formalized in the ThoughtWorks Technology Radar, applies microservice principles — independent deployment, team autonomy, technology flexibility — to the browser tier.

Instead of one large single-page application (a "frontend monolith") built and deployed as a unit, the page a user sees is **composed** from multiple frontends at build time, on the server, or at run time in the browser. Each piece can be developed in its own repository, tested in isolation, and released on its own cadence.

```
        Monolith SPA                     Micro-frontends
   ┌────────────────────┐        ┌──────────┬──────────┬──────────┐
   │  One build         │        │  Header  │  Search  │  Cart    │
   │  One deploy        │   vs   │  team A  │  team B  │  team C  │
   │  One team owns all │        │  deploy  │  deploy  │  deploy  │
   └────────────────────┘        └──────────┴──────────┴──────────┘
```

The defining goal is **organizational**: enabling many teams to ship features to one product without stepping on each other.

### Q2. [Theory] What problems do micro-frontends try to solve?

Micro-frontends address the pains that emerge when a single frontend codebase grows beyond what one team can comfortably own:

- **Scaling teams, not just code.** When 5+ teams commit to one repo, merge conflicts, coupled release trains, and coordination overhead slow everyone down. MFEs give each team a deployable boundary.
- **Independent deployment.** A bug fix in the checkout flow shouldn't require redeploying the entire app or waiting for a shared release window.
- **Incremental migration / strangler-fig upgrades.** You can replace a legacy AngularJS section with React one route at a time, without a big-bang rewrite.
- **Clear ownership.** Each business domain (search, catalog, payments) maps to a team that owns its frontend top to bottom.
- **Limited blast radius.** A broken build or runtime error in one MFE ideally degrades only that feature, not the whole page.

The key insight: the problem MFEs solve is primarily about **people and delivery**, not raw technical performance.

### Q3. [Theory] What are the main integration approaches for composing micro-frontends?

There are three broad categories, distinguished by *when* composition happens:

1. **Build-time integration** — each MFE is published as a versioned npm package; the container app installs them as dependencies and bundles everything together at build time.
2. **Server-side composition** — a server (or edge worker / CDN) stitches HTML fragments from multiple services into one response before it reaches the browser. Examples: SSI, Edge Side Includes (ESI), Tailor/Podium, or a Backend-for-Frontend that assembles markup.
3. **Run-time (client-side) integration** — the browser loads the container shell, which then fetches and mounts MFE bundles on demand. Techniques include **Module Federation**, **Web Components**, **iframes**, and import maps with a registry/loader like single-spa.

```
 Build time  ──▶  npm install + bundle  ──▶  one artifact
 Server side ──▶  fragments stitched in HTML response
 Run time    ──▶  shell loads remote bundles in the browser
```

Most modern MFE setups use run-time integration because it preserves true independent deployment.

### Q4. [Theory] What is the difference between build-time and run-time integration?

**Build-time integration** publishes each MFE as a package (e.g. `@acme/product-card`) that the container imports. It is simple and gives you a single optimized bundle, but it **defeats the core promise of MFEs**: to update one MFE you must reinstall, rebuild, and redeploy the container. Teams are coupled to the container's release cadence.

**Run-time integration** loads MFE code in the browser (or server) at the moment it's needed, from independently deployed URLs. Team A can deploy a new version of their MFE and users get it on the next page load — no container rebuild required.

| Aspect | Build-time | Run-time |
|---|---|---|
| Independent deploy | ❌ (rebuild container) | ✅ |
| Bundle optimization | ✅ easy | ⚠️ needs shared-deps care |
| Coupling | High | Low |
| Setup complexity | Low | Higher |

Build-time is acceptable for slow-moving shared widgets; run-time is the norm when independent deployment is the goal.

### Q5. [Theory] What is a "container" or "shell" application?

The **container** (also called the **shell**, **host**, or **app shell**) is the top-level application the browser loads first. Its responsibilities are deliberately thin:

- Render the global chrome (header, footer, nav) — or delegate even those to MFEs.
- Provide **routing** that decides which MFE owns the current URL.
- **Load, mount, and unmount** MFEs as the user navigates.
- Provide cross-cutting infrastructure: authentication, a shared event bus, error boundaries, and shared dependency configuration.

The shell should contain **as little business logic as possible**. If it accumulates feature code, it becomes a bottleneck — every team has to coordinate changes through it, recreating the monolith problem it was meant to avoid.

### Q6. [Practical] How do you compose micro-frontends with iframes? What are the trade-offs?

An `<iframe>` embeds an entirely separate document, giving the strongest isolation available in a browser — separate DOM, separate CSS, separate JavaScript global scope.

```html
<div id="app">
  <iframe
    src="https://cart.acme.com/widget"
    title="Shopping cart"
    style="width:100%; height:480px; border:0;"
  ></iframe>
</div>
```

**Pros:**
- Bulletproof style and script isolation — no CSS bleed, no global collisions.
- Each MFE can use any framework/version with zero conflict.
- Simple, ancient, universally supported.

**Cons:**
- Hard to make responsive; sizing is awkward.
- Communication only via `postMessage` (no shared memory).
- Poor for SEO and accessibility; focus management and deep linking are painful.
- Duplicate framework downloads per iframe; heavier memory footprint.
- Routing/history integration is clumsy.

Iframes are a reasonable choice for **embedding a third-party or legacy widget** where isolation matters more than seamless UX, but they're rarely the backbone of a modern MFE architecture.

### Q7. [Theory] What are Web Components and how do they relate to micro-frontends?

**Web Components** are a set of native browser standards — **Custom Elements**, **Shadow DOM**, and **HTML Templates** — that let you define reusable, encapsulated HTML tags. An MFE can be packaged as a custom element so the container mounts it declaratively:

```html
<product-recommendations category="shoes"></product-recommendations>
```

They fit MFEs well because:

- **Custom Elements** give a framework-agnostic mounting contract (`connectedCallback` / `disconnectedCallback` map cleanly to mount/unmount).
- **Shadow DOM** provides built-in style isolation, so one MFE's CSS can't leak into another.
- They are a **web standard**, so a React MFE and an Angular MFE can both expose themselves as custom elements and interoperate.

The catch: passing rich data (objects, not just string attributes) and wiring events across the Shadow DOM boundary requires care, and SSR of Shadow DOM (Declarative Shadow DOM) is newer territory.

### Q8. [Practical] Write a minimal Web Component that wraps a micro-frontend.

```javascript
class GreetingWidget extends HTMLElement {
  constructor() {
    super();
    // Attach an isolated shadow root for style encapsulation.
    this.attachShadow({ mode: 'open' });
  }

  // Re-render when the observed attribute changes.
  static get observedAttributes() {
    return ['user'];
  }

  connectedCallback() {
    this.render();
  }

  attributeChangedCallback() {
    this.render();
  }

  render() {
    const user = this.getAttribute('user') ?? 'guest';
    this.shadowRoot.innerHTML = `
      <style>
        /* Scoped to this component only — cannot leak out. */
        .box { font: 16px system-ui; padding: 12px; border: 1px solid #ddd; }
      </style>
      <div class="box">Hello, ${user}!</div>
    `;
  }
}

customElements.define('greeting-widget', GreetingWidget);
```

Usage: `<greeting-widget user="Ada"></greeting-widget>`. The container doesn't need to know which framework built it.

### Q9. [Theory] What does "independent deployment" mean and why is it the core promise of MFEs?

**Independent deployment** means a team can release their micro-frontend to production **without coordinating a build or deploy of any other MFE or the container**. Team B ships a new search UI at 2 PM; users get it on their next navigation; no one else does anything.

It's the core promise because it's what actually unblocks teams. Without it, you have the *visual* decomposition of MFEs but still the *delivery* coupling of a monolith — the worst of both worlds (extra complexity, no autonomy benefit). Achieving it requires run-time (or at least server-side) integration plus **versioned, immutable artifacts** referenced by stable URLs, so a deploy is just publishing a new bundle and updating a pointer.

### Q10. [Theory] What are the main downsides and trade-offs of micro-frontends?

MFEs are not free. The major costs:

- **Payload size and duplication.** Multiple MFEs may each ship React, a date library, a design system — bloating total bytes unless dependencies are deduplicated.
- **Operational complexity.** More repos, more pipelines, more deploy artifacts, more monitoring surface.
- **Consistency drift.** Without governance, MFEs diverge in look, UX patterns, and dependency versions.
- **Cross-team contracts.** Shared events, props, and routing become integration points that need versioning and discipline.
- **Harder end-to-end testing.** The fully assembled app spans multiple deployables.
- **Performance coordination.** Initial load and Core Web Vitals require attention because composition happens at run time.

The honest summary: MFEs trade **technical simplicity** for **organizational scalability**. If you don't have the organizational problem, you're paying the cost for nothing.

### Q11. [Theory] When are micro-frontends overkill?

MFEs are overkill — and usually a net negative — when:

- **You have one team, or a small one.** A single team gains nothing from deploy boundaries it doesn't need; it just inherits the overhead.
- **The app is small or early-stage.** Premature decomposition slows you down before you understand the domain boundaries.
- **You need a highly cohesive, design-heavy UX** with lots of cross-feature interaction — seams between MFEs add friction.
- **Performance is paramount and the app is simple.** A monolith ships fewer bytes with less duplication.
- **Your "problem" is just code organization.** A well-structured modular monolith (clear module boundaries, a monorepo, good lint rules) solves that without distributed-system tax.

Rule of thumb: adopt MFEs when **independent team delivery** is your bottleneck — not because the architecture is fashionable.

### Q12. [Practical] How do you load a remote micro-frontend script at run time without any framework?

The simplest run-time integration is to inject a `<script>` that exposes a global mount function, then call it.

```javascript
// Container code: load and mount an MFE on demand.
function loadScript(url) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = url;
    s.onload = resolve;
    s.onerror = () => reject(new Error(`Failed to load ${url}`));
    document.head.appendChild(s);
  });
}

async function mountCart(el) {
  // The remote bundle defines window.cartApp = { mount, unmount }.
  await loadScript('https://cart.acme.com/bundle.js');
  window.cartApp.mount(el);
}

mountCart(document.getElementById('cart-slot'));
```

This is the conceptual foundation that frameworks like single-spa and Module Federation automate and harden (with error handling, lifecycle, and shared deps).

### Q13. [Theory] What is single-spa?

**single-spa** is a JavaScript framework (router/orchestrator) for composing micro-frontends at run time. It defines a **lifecycle contract** — each MFE exports `bootstrap`, `mount`, and `unmount` functions — and the single-spa **root config** registers each "application" with an **activity function** that says which URLs it should be active for.

```javascript
import { registerApplication, start } from 'single-spa';

registerApplication({
  name: '@acme/checkout',
  app: () => System.import('@acme/checkout'),
  activeWhen: ['/checkout'],
});

start();
```

single-spa is framework-agnostic (adapters exist for React, Angular, Vue, Svelte) and historically paired with **SystemJS** and import maps for module loading, though it now works alongside Module Federation too. It excels at orchestrating *route-level* MFEs.

### Q14. [Practical] Implement the single-spa lifecycle exports for a tiny vanilla MFE.

```javascript
let el;

export async function bootstrap() {
  // One-time setup before first mount.
  el = document.createElement('div');
  el.textContent = 'Profile MFE';
}

export async function mount(props) {
  // Called when the route becomes active. Render into the DOM.
  props.domElement.appendChild(el);
}

export async function unmount(props) {
  // Called when leaving the route. Clean up to avoid leaks.
  props.domElement.removeChild(el);
}
```

The orchestrator calls `bootstrap` once, then `mount`/`unmount` as routing dictates. Forgetting to remove listeners and DOM in `unmount` is a classic source of memory leaks.

---

## 🟡 Intermediate (3–7 yrs)

### Q15. [Theory] What is Webpack Module Federation and what problem does it solve?

**Module Federation** (introduced in Webpack 5) lets a JavaScript application **load code from another independently built and deployed application at run time**, as if it were a local module. It solves the central run-time-integration problem: how to share modules across separately compiled bundles without bundling them together or duplicating dependencies.

Key concepts:

- **Host (consumer)** — an app that loads modules from remotes.
- **Remote (producer)** — an app that exposes modules for others to consume.
- **Exposes** — the modules a remote makes available (e.g. `./ProductList`).
- **Remotes** — the map of remote names to their `remoteEntry.js` URLs in the host.
- **Shared** — dependencies (React, etc.) that hosts and remotes negotiate to load **once**, at compatible versions.

The magic is the **shared scope**: at run time, federated apps negotiate which copy of a shared dependency to use, deduplicating React instead of shipping it five times.

### Q16. [Practical] Configure a Module Federation remote and host.

**Remote (`webpack.config.js`):**

```javascript
const { ModuleFederationPlugin } = require('webpack').container;

module.exports = {
  plugins: [
    new ModuleFederationPlugin({
      name: 'catalog',
      filename: 'remoteEntry.js',
      exposes: {
        './ProductList': './src/ProductList',
      },
      shared: {
        react: { singleton: true, requiredVersion: '^18.0.0' },
        'react-dom': { singleton: true, requiredVersion: '^18.0.0' },
      },
    }),
  ],
};
```

**Host (`webpack.config.js`):**

```javascript
const { ModuleFederationPlugin } = require('webpack').container;

module.exports = {
  plugins: [
    new ModuleFederationPlugin({
      name: 'shell',
      remotes: {
        catalog: 'catalog@https://catalog.acme.com/remoteEntry.js',
      },
      shared: {
        react: { singleton: true, requiredVersion: '^18.0.0' },
        'react-dom': { singleton: true, requiredVersion: '^18.0.0' },
      },
    }),
  ],
};
```

**Consuming it in the host:**

```javascript
// Dynamic import — code is fetched from the remote at run time.
const ProductList = React.lazy(() => import('catalog/ProductList'));
```

### Q17. [Theory] Explain the `singleton`, `eager`, `requiredVersion`, and `strictVersion` options in Module Federation's `shared` config.

These control how a shared dependency is negotiated in the shared scope:

- **`singleton: true`** — only one instance of the package may exist across all federated apps. Essential for libraries that break with multiple instances (React, due to hooks; any library using module-level state/context). The highest compatible version wins.
- **`eager: true`** — bundle the dependency into the initial chunk rather than loading it asynchronously. Useful for the host's bootstrap, but overuse defeats the deduplication benefit and bloats the entry. Eager singletons require care so the version is available synchronously.
- **`requiredVersion`** — the semver range this app needs. Used to pick a compatible shared version; mismatches trigger warnings or fallbacks.
- **`strictVersion: true`** — turn version mismatches into hard errors instead of warnings (with a fallback). Helps catch incompatibilities early but can break the app at run time if not managed.

```javascript
shared: {
  react: { singleton: true, requiredVersion: '^18.2.0', strictVersion: false },
}
```

### Q18. [Theory] How does Module Federation handle shared dependencies and avoid duplicate downloads?

Each federated build advertises which shared packages it *can* provide and which versions it *needs*, registering them in a global **shared scope** keyed by package name and version. When an app needs a shared module:

1. It checks the shared scope for an already-loaded compatible version.
2. If a compatible version exists, it **reuses** it — no second download.
3. If not, it loads its own copy (or, for a `singleton`, negotiates the highest compatible version and warns if incompatible).

```
 Shared scope:  react@18.2.0 ──┐
                               ├── host uses it
 catalog needs react@^18 ──────┘  (no duplicate download)
 reviews needs react@^18  ─────────  reuses the same instance
```

This run-time negotiation is what makes a 5-MFE app ship one React instead of five — provided everyone marks React as `shared`/`singleton` with compatible ranges.

### Q19. [Theory] What is the difference between Module Federation in Webpack and Vite?

Webpack 5 ships Module Federation natively via `ModuleFederationPlugin`. Vite (built on Rollup/esbuild) does not have it built in, so the ecosystem fills the gap:

- **`@originjs/vite-plugin-federation`** — a community plugin that emulates Webpack-style federation for Vite. Works, but historically had rough edges (dev-mode behavior, shared-deps nuances).
- **Module Federation 2.0 / `@module-federation/vite`** — the official Module Federation project (now framework-and-bundler agnostic, maintained under its own org) provides first-class Vite support, runtime APIs, type sharing, and a manifest, aligning Vite's behavior closely with Webpack's.
- **Native Federation** (popular in the Angular world) — a bundler-agnostic approach built on **import maps** and the **es-module-shims** polyfill, designed to work with esbuild/Vite without Webpack internals.

In 2026, **Module Federation 2.0** is the convergence point: one runtime that works across Webpack, Rspack, and Vite with consistent semantics, dynamic remotes, and type safety.

### Q20. [Practical] How do you set up Module Federation with Vite?

Using the official `@module-federation/vite` plugin:

```javascript
// vite.config.js (remote)
import { defineConfig } from 'vite';
import { federation } from '@module-federation/vite';

export default defineConfig({
  plugins: [
    federation({
      name: 'catalog',
      filename: 'remoteEntry.js',
      exposes: {
        './ProductList': './src/ProductList.jsx',
      },
      shared: ['react', 'react-dom'],
    }),
  ],
  build: { target: 'esnext' }, // top-level await support
});
```

```javascript
// vite.config.js (host)
import { federation } from '@module-federation/vite';

export default defineConfig({
  plugins: [
    federation({
      name: 'shell',
      remotes: {
        catalog: { type: 'module', entry: 'https://catalog.acme.com/remoteEntry.js' },
      },
      shared: ['react', 'react-dom'],
    }),
  ],
  build: { target: 'esnext' },
});
```

Note the `target: 'esnext'` — federation relies on top-level `await` to resolve remotes.

### Q21. [Theory] How does routing work across multiple micro-frontends?

Routing in MFEs is **two-tiered**:

1. **Top-level (shell) routing** decides *which MFE owns the current URL prefix*. E.g. `/checkout/*` → checkout MFE, `/catalog/*` → catalog MFE. The shell mounts the right MFE and unmounts others.
2. **Nested (MFE-internal) routing** handles routes *within* an MFE. The checkout MFE manages `/checkout/cart`, `/checkout/payment`, `/checkout/confirm` using its own router.

```
 /catalog/123     ──▶ shell picks catalog MFE ──▶ catalog router → product page
 /checkout/payment──▶ shell picks checkout MFE ──▶ checkout router → payment step
```

The hard part is **sharing one history/URL** so the browser back button, deep links, and the address bar stay consistent. Approaches: a single shared router instance, the History API with the shell as coordinator, or libraries (single-spa's routing, Module Federation + a shared router). Owning the URL prefix per team keeps the contract clean.

### Q22. [Practical] How do you coordinate the browser History API between the shell and an MFE?

The shell owns navigation; MFEs request navigation through a shared abstraction rather than calling `history.pushState` independently (which would desync the shell's router).

```javascript
// Shell exposes a navigation API on a shared bus.
const navigation = {
  navigate(path) {
    window.history.pushState({}, '', path);
    // Notify all MFEs that the route changed.
    window.dispatchEvent(new PopStateEvent('popstate'));
  },
};
window.__shellNav = navigation;

// MFE uses it instead of touching history directly.
function goToCheckout() {
  window.__shellNav.navigate('/checkout/cart');
}

// MFEs listen for route changes to re-render or mount/unmount.
window.addEventListener('popstate', () => {
  renderForRoute(window.location.pathname);
});
```

Centralizing through one navigation API prevents the classic bug where the shell's router and an MFE's router disagree about the current route.

### Q23. [Theory] How should micro-frontends communicate with each other?

The guiding principle is **loose coupling** — MFEs should not import each other's internals. Common patterns, from loosest to tightest:

1. **Custom DOM events / a pub-sub event bus** — fire-and-forget messages on `window` or a shared emitter. Best for decoupled notifications ("item added to cart").
2. **Shared state store** — a singleton store (e.g. a small observable) read/written by multiple MFEs. Convenient but creates coupling to the store's shape.
3. **Props / inputs** — when the shell mounts an MFE, it passes data and callbacks down. Explicit and typed.
4. **URL / query params** — state encoded in the route; naturally shareable and bookmarkable.
5. **`postMessage`** — required across iframe boundaries.

Prefer events and URL for cross-MFE state, props for parent→child, and avoid a big shared store unless the coupling is genuinely warranted.

### Q24. [Practical] Implement a simple pub/sub event bus for cross-MFE communication.

```javascript
// shared-bus.js — published as a tiny singleton, loaded once by the shell.
function createEventBus() {
  const listeners = new Map(); // event -> Set<handler>

  return {
    on(event, handler) {
      if (!listeners.has(event)) listeners.set(event, new Set());
      listeners.get(event).add(handler);
      return () => listeners.get(event).delete(handler); // unsubscribe
    },
    emit(event, payload) {
      listeners.get(event)?.forEach((h) => {
        try { h(payload); } catch (e) { console.error(e); }
      });
    },
  };
}

// Expose a single instance globally so every MFE shares it.
window.__eventBus ||= createEventBus();
```

```javascript
// Cart MFE publishes:
window.__eventBus.emit('cart:item-added', { id: 'sku-42', qty: 1 });

// Header MFE subscribes to update the badge:
const off = window.__eventBus.on('cart:item-added', ({ qty }) => {
  updateCartBadge(qty);
});
// Call off() on unmount to avoid leaks.
```

### Q25. [Practical] Use the native CustomEvent API for cross-MFE communication.

When you'd rather not ship a shared bus, browser-native `CustomEvent` on `window` works across MFEs without any shared dependency.

```javascript
// Publisher (any MFE):
window.dispatchEvent(
  new CustomEvent('user:logged-in', {
    detail: { userId: 'u-123', name: 'Ada' },
  })
);

// Subscriber (another MFE):
function onLogin(e) {
  console.log('Welcome', e.detail.name);
}
window.addEventListener('user:logged-in', onLogin);

// Always remove on unmount:
// window.removeEventListener('user:logged-in', onLogin);
```

Trade-off: you lose type safety and discoverability. Document a versioned event contract (names + payload shapes) so teams don't break each other.

### Q26. [Theory] How do you achieve style isolation between micro-frontends?

CSS is global by default, so two MFEs can clobber each other's styles. Isolation techniques, strongest to weakest:

- **Shadow DOM** — true encapsulation; styles inside a shadow root don't leak out and outer styles (mostly) don't leak in. Strongest, but complicates global theming and SSR.
- **CSS Modules** — compile class names to unique hashes (`.btn` → `.btn_a1b2c3`), eliminating collisions at build time.
- **CSS-in-JS** — generates scoped, unique class names at runtime/build (styled-components, Emotion).
- **BEM / naming conventions + team prefixes** — namespace every class (`.cart-button`); discipline-based, no tooling guarantee.
- **`@scope` rule (modern CSS)** — native scoping of styles to a DOM subtree without Shadow DOM.

```css
/* Native CSS @scope — isolate without Shadow DOM */
@scope (.cart-mfe) {
  button { background: teal; }
}
```

In practice, teams combine a prefix convention with CSS Modules or Shadow DOM, plus a **shared design-system package** to keep visuals consistent.

### Q27. [Theory] What are the pros and cons of using Shadow DOM for style isolation in MFEs?

**Pros:**
- Real encapsulation — no CSS bleed in either direction, the most reliable isolation in the browser.
- Lets incompatible MFEs coexist without global-CSS warfare.
- DOM is encapsulated too, so structure is hidden from outer queries.

**Cons:**
- **Global theming is harder** — design tokens must be passed in deliberately, typically via **CSS custom properties** (which *do* pierce shadow boundaries) or `::part`/`::slotted`.
- Third-party libraries that inject styles into `document.head` (some component libs, tooltips, modals) may not style inside the shadow root.
- **SSR** of Shadow DOM needs **Declarative Shadow DOM**, which is newer and not uniformly supported by all tooling.
- Focus, accessibility, and event retargeting have subtle behaviors to learn.

Use CSS custom properties to bridge the theming gap:

```css
:host { --brand: var(--global-brand, #06c); }
button { background: var(--brand); }
```

### Q28. [Practical] How do you share a design system across micro-frontends without duplicating it in every bundle?

Treat the design system as a **shared singleton dependency**, the same way you share React:

```javascript
// In every MFE's Module Federation config:
shared: {
  '@acme/design-system': { singleton: true, requiredVersion: '^4.0.0' },
  react: { singleton: true },
  'react-dom': { singleton: true },
}
```

Combine that with:

- **CSS custom properties (design tokens)** defined once at the shell level (`:root { --color-brand: ... }`) so all MFEs theme consistently without re-shipping token CSS.
- A **versioned package** with a clear semver and a deprecation policy, so teams can upgrade independently within a compatible range.
- Web Components or framework-agnostic primitives if MFEs use different frameworks.

This keeps the design system loaded **once**, themed centrally, while letting teams adopt new versions on their own schedule within the shared range.

### Q29. [Theory] What is the "shared singleton" problem and why does it matter for libraries like React?

Some libraries break catastrophically if **more than one instance** is loaded on the page. React is the canonical example: its hooks and reconciler rely on **module-level state**. If two React copies exist, a component rendered by one React but using context/hooks resolved against another React throws the infamous *"Invalid hook call"* / *"hooks can only be called inside a component"* errors, and context returns `undefined`.

Other singleton-sensitive cases: a single router instance, a state library with a global store, an i18n instance, or anything using a module-level registry.

The fix is to mark such packages `singleton: true` in Module Federation (or use import maps to resolve them to one URL), so the page loads exactly **one** instance shared by all MFEs.

### Q30. [Theory] How do you handle dependency version mismatches across MFEs?

Strategies, roughly in order of preference:

1. **Agree on shared ranges.** Coordinate a compatible semver range for singletons (e.g. all MFEs on React `^18`). Module Federation negotiates the highest compatible version.
2. **Use `requiredVersion` + `strictVersion` deliberately.** Strict catches incompatibilities early; loose tolerates minor drift with a fallback.
3. **Allow non-singletons to differ.** Stateless utilities (lodash, date-fns) can safely load multiple versions if duplication cost is acceptable.
4. **Provide a migration window.** Support N and N-1 major versions of shared libs so teams upgrade gradually, not in lockstep.
5. **Automate with a monorepo / dependency dashboard.** Tools (Renovate, syncpack, the MF manifest) surface drift before it ships.

The tension is always **autonomy vs. consistency**: total independence breeds version chaos; total lockstep recreates the monolith. Singletons need agreement; everything else can be looser.

### Q31. [Practical] How do you lazy-load a remote MFE in React with error handling?

```javascript
import React, { Suspense } from 'react';

// Remote module resolved at run time via Module Federation.
const RemoteCart = React.lazy(() => import('cart/CartWidget'));

class RemoteErrorBoundary extends React.Component {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    if (this.state.failed) {
      // Degrade gracefully — don't take down the whole page.
      return <div>Cart is temporarily unavailable.</div>;
    }
    return this.props.children;
  }
}

export function CartSlot() {
  return (
    <RemoteErrorBoundary>
      <Suspense fallback={<div>Loading cart…</div>}>
        <RemoteCart />
      </Suspense>
    </RemoteErrorBoundary>
  );
}
```

The **error boundary** is essential: a failed remote (network error, bad deploy) should isolate to its slot, not crash the shell.

### Q32. [Theory] What are import maps and how do they enable micro-frontends?

An **import map** is a native browser feature that tells the browser how to resolve bare module specifiers to URLs:

```html
<script type="importmap">
{
  "imports": {
    "react": "https://cdn.acme.com/react@18.2.0/index.js",
    "@acme/catalog": "https://catalog.acme.com/v3/entry.js"
  }
}
</script>
<script type="module">
  import { ProductList } from '@acme/catalog'; // resolved via the map
</script>
```

They enable MFEs by providing a **central, run-time-editable registry** of where each MFE and shared dependency lives. To deploy a new MFE version, you update one URL in the import map — no rebuild of consumers. Pointing `react` to a single URL also deduplicates it across MFEs (a poor man's singleton). This is the foundation of single-spa's classic setup and of **Native Federation**.

### Q33. [Practical] How do you independently deploy a micro-frontend and roll it out / back?

A clean independent-deploy pipeline:

1. **Build an immutable, versioned artifact** — `cart/v1.4.2/remoteEntry.js`, content-hashed, never overwritten.
2. **Upload to CDN/object storage.** Old versions stay live so in-flight sessions don't break.
3. **Flip a pointer.** Update the import map entry (or remotes manifest) from `v1.4.1` to `v1.4.2`. This is the actual "deploy" — atomic and instant.

```json
// remotes-manifest.json (fetched by the shell at startup)
{ "cart": "https://cdn.acme.com/cart/v1.4.2/remoteEntry.js" }
```

4. **Rollback** = flip the pointer back to the previous version — seconds, no rebuild.
5. **Canary** = serve the new pointer to a fraction of traffic (cookie/header-based) before full rollout.

The key: **immutable artifacts + a mutable pointer** give instant, safe, independent deploys and rollbacks.

### Q34. [Theory] How do you handle shared authentication and session state across MFEs?

Auth should be a **cross-cutting concern owned by the shell**, not re-implemented per MFE:

- The **shell handles login/logout** and obtains tokens (OIDC/OAuth flow), storing them securely (preferably httpOnly cookies; if using JS-accessible storage, mind XSS).
- The shell exposes the **auth state and a token accessor** to MFEs via a shared service/context or event bus (`auth:changed`), so MFEs never each run their own login.
- **APIs trust the token**, not the MFE; each MFE's backend validates the JWT/cookie independently.
- On token refresh or logout, the shell **broadcasts** the change so all MFEs react (e.g. redirect to login).

```javascript
window.__auth = {
  getToken: () => currentToken,
  onChange: (cb) => bus.on('auth:changed', cb),
};
```

Centralizing auth avoids inconsistent session handling and reduces the attack surface.

---

## 🟠 Advanced (8–12 yrs)

### Q35. [Theory] What are the main performance pitfalls of micro-frontends and how do you mitigate them?

The biggest pitfalls and their fixes:

- **Duplicate dependencies.** Each MFE shipping its own React/lodash/design system. *Fix:* mark them `shared`/`singleton`; audit the network tab and bundle stats for duplicates.
- **Bundle bloat & waterfall loading.** The shell loads, then fetches a remote entry, then the actual chunk — serial round trips. *Fix:* preload/prefetch critical remotes, use HTTP/2-3 multiplexing, and inline a small critical shell.
- **Render-blocking remote fetches** hurting LCP/FCP. *Fix:* SSR or stream the above-the-fold MFE; lazy-load below-the-fold ones.
- **Uncoordinated polyfills/runtimes** loaded multiple times. *Fix:* share the runtime; one polyfill bundle.
- **No shared caching strategy.** *Fix:* long-cache immutable hashed artifacts; cache-bust via the pointer.
- **Too-granular MFEs.** Dozens of tiny remotes multiply overhead. *Fix:* align MFE boundaries with team/domain boundaries, not components.

The recurring theme: **measure total assembled-page cost**, not each MFE in isolation.

### Q36. [Theory] How does server-side rendering (SSR) work with micro-frontends?

SSR with MFEs means each MFE can render HTML on the server, and a composition layer stitches the fragments before sending the page — improving FCP/LCP and SEO over client-only assembly.

Approaches:

- **Fragment composition at the edge/server.** A composer (Tailor, Podium, Ara, or a custom Node service / edge worker) requests HTML fragments from each MFE service and inlines them. ESI/SSI are the classic primitives.
- **Federated SSR (Module Federation on the server).** The Node server uses MF to import remote MFE components and renders them with `renderToString`/streaming, then the client **hydrates** the corresponding federated modules.
- **Streaming SSR.** Flush the shell and above-the-fold MFEs first, stream the rest, so the user sees content fast.

```
 Request ─▶ Composer ─▶ asks each MFE for HTML ─▶ stitches ─▶ streams to browser
                                                          └─▶ client hydrates
```

Challenges: **hydration mismatches**, ensuring the same shared-dep versions on server and client, coordinating data fetching, and per-fragment timeouts/fallbacks so one slow MFE doesn't stall the whole page.

### Q37. [Practical] How do you prevent JavaScript global-scope and runtime collisions between MFEs?

Multiple independently built bundles can collide on `window` globals, CSS, event listeners, and even the same library's module state. Defenses:

- **Webpack `output.uniqueName`** (set per build) so federated runtimes and chunk-loading globals don't clash.
- **Avoid leaking to `window`.** Communicate through a single namespaced object (`window.__acme`) or an event bus, never ad-hoc globals.
- **Scope CSS** (Shadow DOM / CSS Modules) — covered earlier.
- **Clean up on unmount** — remove event listeners, timers, and DOM nodes; clear singletons' subscriptions. single-spa's lifecycle exists precisely for this.
- **Sandbox aggressively when needed** — iframes or proxy-based sandboxes (e.g. qiankun's `proxy`/snapshot sandbox) intercept `window` access per MFE for legacy or untrusted code.

```javascript
// Namespace, don't pollute:
window.__acme ||= {};
window.__acme.cart = cartApi; // not window.cartApi
```

### Q38. [Theory] How do you test micro-frontends end-to-end given they're independently deployed?

A layered strategy, because no single layer is sufficient:

- **Unit/component tests per MFE** (fast, owned by the team) — bulk of coverage.
- **Contract tests** for the integration points: event names/payloads, exposed module APIs, props, and shared-dependency versions. Tools like **Pact** verify producer/consumer expectations so a team can't silently break the contract.
- **Integration tests of the composed shell** in CI, loading remotes (mocked or real staging versions) to catch wiring issues.
- **End-to-end tests** (Playwright/Cypress) against a fully assembled environment, ideally with **deployment previews** that pin specific MFE versions.
- **Smoke/synthetic monitoring in production** since independent deploys mean the live combination changes outside any single pipeline.

The hard truth: with independent deployment, the **assembled** app is never fully tested by one team's CI — contract tests + production monitoring fill that gap.

### Q39. [Practical] How do you implement a typed contract for cross-MFE events to avoid breakage?

Publish a **shared, versioned contract package** that defines event names and payload types; every MFE imports it so TypeScript enforces compatibility at build time.

```typescript
// @acme/mfe-contracts (versioned npm package)
export interface EventMap {
  'cart:item-added': { sku: string; qty: number };
  'auth:changed': { userId: string | null };
}

export function emit<K extends keyof EventMap>(type: K, detail: EventMap[K]) {
  window.dispatchEvent(new CustomEvent(type, { detail }));
}

export function on<K extends keyof EventMap>(
  type: K,
  handler: (detail: EventMap[K]) => void
) {
  const wrapped = (e: Event) => handler((e as CustomEvent).detail);
  window.addEventListener(type, wrapped);
  return () => window.removeEventListener(type, wrapped);
}
```

```typescript
// Producer — type-checked:
emit('cart:item-added', { sku: 'sku-42', qty: 1 }); // ✅
emit('cart:item-added', { sku: 'sku-42' });         // ❌ missing qty

// Consumer:
const off = on('cart:item-added', ({ sku, qty }) => updateBadge(qty));
```

Bump the contract package's major version for breaking changes; support N and N-1 during migration. Pair with Pact/contract tests for runtime guarantees.

### Q40. [Theory] How do you decide the boundaries between micro-frontends?

Boundaries should follow **business domains and team ownership**, not technical layers or component granularity — a direct application of Conway's Law and Domain-Driven Design.

Good heuristics:

- **One MFE per bounded context / team** (search, catalog, checkout, account) — each maps to a deployable a team fully owns.
- **High cohesion inside, loose coupling across.** Things that change together belong in the same MFE; cross-MFE chatter is a smell.
- **Align with URL structure** so routing ownership is clean (`/checkout/*`).
- **Avoid "nano-frontends."** A single button or component as an MFE adds run-time and operational cost far exceeding its value.
- **Watch for shared-state hotspots.** If two MFEs constantly share mutable state, they may really be one MFE.

If a boundary forces constant cross-team coordination, it's wrong — redraw it so teams can move independently.

### Q41. [Theory] What is "Conway's Law" and how does it relate to micro-frontend architecture?

**Conway's Law** states that organizations design systems that mirror their communication structure. Applied to MFEs: your architecture *will* end up reflecting your team boundaries, so you should **deliberately design teams and MFE boundaries together** (the "Inverse Conway Maneuver").

Implications:

- If you want autonomous, independently deployable MFEs, you need **autonomous teams** with end-to-end ownership (frontend, backend-for-frontend, deploy pipeline). MFEs without team autonomy are just complexity.
- **Cross-cutting concerns** (design system, auth, shell) need an owner too — often a platform/enablement team — or they rot.
- If two teams must coordinate constantly, the boundary between their MFEs is misdrawn; realign teams or merge the MFEs.

The architecture is a tool to give teams autonomy — if the org isn't structured for autonomy, MFEs won't deliver their promised benefits.

### Q42. [Behavioral] Tell me about a time you had to convince a team to adopt — or *not* adopt — micro-frontends.

This probes architectural judgment and influence. A strong answer follows a structure:

- **Situation:** Describe the context — e.g. a 4-team product where everyone shared one repo and release train, causing weekly merge conflicts and blocked releases; *or* a small team eyeing MFEs because they were trendy.
- **Analysis:** Show you evaluated the *real* driver. "I asked whether our bottleneck was delivery coupling (an MFE problem) or just code organization (a modular-monolith problem)." Quantify: deploy frequency, conflict rate, coordination meetings.
- **Recommendation:** State your call and trade-offs honestly — including the costs (duplicate deps, ops overhead, contract discipline). For the small team, the answer might be "**not** yet — a modular monorepo first."
- **Outcome & reflection:** What happened, what you'd measure (deploy lead time, change-failure rate), and what you learned about matching architecture to organizational need.

The interviewer wants to see you treat MFEs as a means to team autonomy, not a resume keyword — and that you can say "no" when it's the right call.

### Q43. [Practical] How do you handle a slow or failing remote MFE so it doesn't degrade the whole page?

Apply resilience patterns at the integration boundary:

```javascript
// 1. Timeout the remote load.
function loadWithTimeout(importFn, ms = 4000) {
  return Promise.race([
    importFn(),
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error('remote timeout')), ms)
    ),
  ]);
}

// 2. Wrap in error boundary + fallback (React shown; same idea elsewhere).
const Reviews = React.lazy(() =>
  loadWithTimeout(() => import('reviews/Widget')).catch(() => ({
    default: () => <div>Reviews unavailable right now.</div>, // graceful fallback
  }))
);
```

Additional measures:

- **Error boundaries** around each slot so one failure stays local.
- **Circuit breaking / retries with backoff** for flaky remotes.
- **Skeletons/placeholders** to keep layout stable (avoid CLS).
- **SSR fallback** so above-the-fold content survives a failed client remote.
- **Monitoring** per-remote error/latency, alerting on a bad deploy.

The principle: **partial failure should mean partial degradation**, never a blank page.

### Q44. [Theory] Compare micro-frontends to a well-structured modular monolith. When is the monolith the better choice?

A **modular monolith** is a single deployable with strong internal module boundaries (clear public APIs, enforced via lint rules / project references / a monorepo). It delivers much of the *code-organization* benefit of MFEs without the distributed-system tax.

| | Modular monolith | Micro-frontends |
|---|---|---|
| Deploy | One unit | Independent per MFE |
| Team autonomy | Limited (shared deploy) | High |
| Bundle/perf | Simpler, less duplication | Risk of duplication |
| Operational cost | Low | High |
| Tech diversity | One stack | Mixed possible |

**Choose the monolith when** you have one or few teams, a shared release cadence is acceptable, performance/simplicity matters, or you're early and boundaries are still fuzzy. **Choose MFEs when** independent deployment by autonomous teams is the actual bottleneck. Many successful orgs run a modular monolith for years and adopt MFEs only when team count and delivery coupling force the issue — and even then, only for the parts that need it.

---

## 🔴 Expert (15+ yrs)

### Q45. [Theory] How would you design a micro-frontend platform for 50+ teams across multiple products?

At this scale you're building an **internal platform**, not just an app. Key elements:

- **A thin, well-governed shell/runtime** (often one per product surface) owned by a platform team, providing routing, MFE loading, auth, telemetry, and the event/contract layer.
- **A central MFE registry/manifest** mapping MFE name → versioned artifact URL, with environments (dev/stage/prod), canary pointers, and an API/CLI teams use to publish.
- **Module Federation 2.0** (with the manifest + dynamic remotes + type sharing) for run-time composition and deduped shared deps across the org.
- **Golden-path tooling:** a scaffolding CLI, paved-road CI/CD that produces immutable artifacts and flips pointers, and contract-test gates.
- **A platform design system** as a shared singleton with a clear versioning/deprecation policy and design tokens.
- **Org-wide observability:** per-MFE RUM, error budgets, bundle-size budgets enforced in CI, and dependency-drift dashboards.
- **Governance** for shared-dependency major versions, security, and accessibility — enabling autonomy with guardrails, not gatekeeping.

The success metric is **team lead time and autonomy** while keeping the assembled product fast, consistent, and observable.

### Q46. [Theory] How do you enforce performance budgets and prevent dependency drift across many independently deployed MFEs?

You make it **automated and blocking**, because manual coordination doesn't scale:

- **Per-MFE bundle-size budgets** enforced in CI (`size-limit`, Webpack `performance.maxAssetSize`, or bundlewatch). A PR that exceeds budget fails.
- **Shared-dependency policy** declared centrally (allowed singleton ranges); CI validates each MFE's `shared` config against it and **rejects incompatible ranges**.
- **The MF manifest / dependency dashboard** surfaces what version each MFE actually loads in prod, flagging duplicates and drift (e.g. two React majors live simultaneously).
- **Renovate/Dependabot org-wide** to keep shared libs converging, with grouped PRs for the shared set.
- **Production RUM budgets** (LCP/INP/CLS per MFE slot) with error budgets; breaching triggers alerts and can block further rollout.
- **Synthetic assembly checks** that load the real composed page and assert total bytes, duplicate-module count, and Core Web Vitals.

The philosophy: **autonomy within guardrails** — teams ship freely, but budgets and policies are enforced by tooling, not meetings.

### Q47. [Behavioral] Describe a situation where a micro-frontend architecture caused a production incident. How did you handle it, and what changed afterward?

This assesses incident leadership and systemic thinking. Frame it as:

- **Incident:** Concrete failure mode — e.g. a team deployed an MFE that bundled a second, incompatible React (forgot `singleton`), causing "Invalid hook call" crashes for users on pages composing both MFEs; *or* a bad remote pointer flip took down checkout.
- **Response:** How you detected it (per-remote error alerting/RUM), the **fast mitigation** (flip the pointer back — instant rollback is an MFE superpower), and how you contained blast radius.
- **Root cause:** The systemic gap — no CI check on shared-dep config, or no canary on pointer flips, or a missing contract test.
- **Remediation:** What you changed so it can't recur — automated `shared` validation in CI, mandatory canary + automated rollback on error-budget breach, contract tests in the pipeline, better per-MFE observability.
- **Reflection:** The broader lesson — independent deployment needs **automated guardrails**, and "partial failure" only stays partial if you've engineered for it.

Strong candidates emphasize blameless analysis and turning the incident into durable platform safeguards.

### Q48. [Theory] What are the long-term governance and consistency challenges of micro-frontends, and how do you manage them?

Over years, the dominant risks shift from "can we build it" to "can we keep it coherent":

- **Visual/UX drift.** Teams diverge in components, spacing, and interaction patterns. *Manage:* a strong, well-adopted design system (shared singleton), design tokens, and periodic UX audits; make the paved road the easy path.
- **Dependency entropy.** N MFEs slowly spread across many framework/lib versions. *Manage:* enforced shared-range policies, drift dashboards, and supported-version windows (N, N-1).
- **Accessibility and security inconsistency.** Each team re-solving a11y/CSP/XSS differently. *Manage:* shared primitives, automated a11y/security gates, central CSP owned by the shell.
- **Contract erosion.** Events/APIs change and silently break consumers. *Manage:* versioned contract packages + contract tests.
- **Orphaned MFEs.** Teams reorg; MFEs lose owners. *Manage:* an ownership registry, lifecycle/deprecation process, and decommissioning playbooks.
- **Platform team as bottleneck or as neglected.** *Manage:* fund a real platform/enablement team; treat the shell and design system as products with roadmaps.

The meta-lesson: MFEs trade upfront coordination for **ongoing governance**. Without an enabling platform team and automated guardrails, the architecture slowly decays back toward inconsistency — the very problem it was meant to escape.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q49. [Theory] What actually lives inside a `remoteEntry.js` file, and why is it the entry point of every federated remote?

`remoteEntry.js` is the **container manifest** a remote produces — not the application code itself, but a tiny bootstrap that knows how to *deliver* it. When Webpack/Rspack/Module Federation builds a remote, it generates this file as the public handshake interface.

It exposes (historically, in MF1) two functions on a global named after the remote (`window.catalog`):

- **`get(moduleName)`** — returns a promise resolving to a factory for an exposed module (e.g. `./ProductList`). This is how the host pulls a specific exposed module.
- **`init(sharedScope)`** — receives the host's **shared scope** object so the remote can register the shared dependencies it can provide and discover what's already loaded, before any exposed module runs.

```javascript
// Conceptually, after loading remoteEntry.js the host does:
await window.catalog.init(__webpack_share_scopes__.default); // negotiate shared deps
const factory = await window.catalog.get('./ProductList');    // fetch the module factory
const ProductList = factory();                                 // instantiate it
```

The actual component chunks are *separate* hashed files the entry references. So `remoteEntry.js` stays small and rarely changes shape — the host fetches it first, negotiates shared deps, then lazily loads only the chunks for the modules actually used. In MF2 this is wrapped by a runtime + a JSON `mf-manifest.json`, but the underlying `get`/`init` contract is the same.

#### Q50. [Theory] What is the difference between a "host", a "remote", and a "bidirectional" host in Module Federation?

These describe a build's **role** in the federation graph, and a single build can play more than one:

- **Host (consumer)** — declares `remotes` and consumes exposed modules from them. The shell is the classic host.
- **Remote (producer)** — declares `exposes` and publishes modules for others. A feature MFE is a classic remote.
- **Bidirectional host** — a build that is *both*: it `exposes` modules **and** consumes other remotes. This enables **MFE-to-MFE** consumption without routing everything through the shell — e.g. the checkout MFE consuming a shared `address-form` exposed by the account MFE.

```javascript
// A bidirectional build: both remotes and exposes set.
new ModuleFederationPlugin({
  name: 'checkout',
  filename: 'remoteEntry.js',
  exposes: { './CheckoutFlow': './src/CheckoutFlow' },
  remotes: { account: 'account@https://account.acme.com/remoteEntry.js' },
  shared: { react: { singleton: true } },
});
```

The caution: bidirectional graphs can create **circular remote dependencies** (A consumes B, B consumes A), which complicate load ordering and versioning. Most architectures keep the graph mostly a tree (shell → MFEs) and use bidirectional links sparingly for genuinely shared building blocks.

#### Q51. [Theory] What does "lifecycle" mean for a micro-frontend, and what are the canonical lifecycle phases?

A micro-frontend's **lifecycle** is the contract of phases the orchestrator drives as the MFE comes onto and leaves the page. The single-spa formalization (now an industry reference point) defines:

- **`bootstrap`** — runs **once**, the first time the MFE is needed. One-time setup: create roots, initialize singletons, warm caches. Not for rendering.
- **`mount`** — runs each time the MFE becomes active for the current route. It renders into a provided DOM node and wires up listeners.
- **`unmount`** — runs each time the MFE leaves. It must **fully tear down**: remove DOM, detach listeners/timers, cancel in-flight requests, unsubscribe from buses.
- **`update`** (optional) — for re-passing props without a full unmount/mount cycle.

```
 first activation:   bootstrap ─▶ mount
 leave route:        unmount
 return to route:    mount        (bootstrap is NOT run again)
```

The reason this matters: because MFEs mount/unmount many times in a long-lived SPA, **asymmetry between mount and unmount is the #1 source of memory leaks** — every listener added in mount must be removed in unmount.

#### Q52. [Theory] Why is "Invalid hook call" the signature failure of a broken shared-React setup, internally?

React stores the **current dispatcher** (the object that backs `useState`, `useEffect`, etc.) on a module-level mutable field of the `react` package — `ReactCurrentDispatcher` (or its modern equivalent inside React internals). During a render, the reconciler from `react-dom` sets this dispatcher; when render finishes it resets it to a stub that throws.

If a component is rendered by **React-DOM instance #1** but its hooks resolve the `react` import to **instance #2**, the hook reads instance #2's dispatcher — which is still the throwing stub, because *that* React was never told a render is in progress. Hence "Invalid hook call: hooks can only be called inside the body of a function component."

```
 react-dom #1 renders <Cmp/>  →  sets dispatcher on react #1
 <Cmp/> calls useState        →  reads dispatcher on react #2 (the throwing stub) 💥
```

The internal invariant is: **`react` and `react-dom` must be the exact same module instances**. That is why both must be `singleton: true` with overlapping `requiredVersion`, not just `react` alone — a mismatched `react-dom` reintroduces the split.

#### Q53. [Practical] Demonstrate a correct mount/unmount pair that avoids the classic listener-leak, and show the broken version for contrast.

```javascript
// ❌ Leaky: listener added on every mount, never removed.
export async function mount(props) {
  window.addEventListener('resize', onResize); // new closure each mount
  render(props.domElement);
}
export async function unmount(props) {
  clear(props.domElement); // listener stays attached → grows unbounded
}

// ✅ Correct: symmetric setup/teardown, stable handler reference per instance.
export function createApp() {
  let onResize;
  let timer;
  return {
    async mount(props) {
      onResize = () => layout(props.domElement);
      window.addEventListener('resize', onResize);
      timer = setInterval(poll, 5000);
      render(props.domElement);
    },
    async unmount(props) {
      window.removeEventListener('resize', onResize); // exact same ref
      clearInterval(timer);                            // kill timers
      clear(props.domElement);                         // remove DOM
      onResize = timer = null;                         // drop references
    },
  };
}
```

The fix is to keep a **stable reference** to every handler/timer at instance scope so `unmount` can remove exactly what `mount` added. A fresh inline closure each mount cannot be removed and accumulates.

#### Q54. [Theory] What is the difference between "composition" and "integration" in micro-frontend vocabulary?

They are often blurred but describe two distinct axes:

- **Composition** answers *"where are the pieces assembled into one page?"* — the **place**: client-side (browser), server-side, or edge. It is about *who does the stitching*.
- **Integration** answers *"when and how is each piece wired in?"* — the **mechanism and timing**: build-time (npm packages), run-time (Module Federation, import maps, Web Components), or via fragments.

A single architecture picks a coordinate on each axis. For example: *client-side composition* (the browser assembles the page) using *run-time integration* (Module Federation loads remotes). Or *server-side composition* (an edge worker stitches HTML) using *fragment integration* (each MFE returns an HTML fragment). Keeping the two axes separate prevents the common confusion of treating "Module Federation" and "client-side rendering" as the same decision — they are orthogonal choices.

### 🟡 — extended

#### Q55. [Theory] How does the Module Federation shared scope negotiation algorithm actually pick a version?

The shared scope is a global registry: `__webpack_share_scopes__.default[packageName][version] = { get, loaded, from, ... }`. Every participating build, during its `init`, **registers** each shared package version it can provide and records what it *requires*.

When a module needs a shared dependency, the runtime resolves it like this:

1. Collect all **registered versions** of that package in the scope that **satisfy** the consumer's `requiredVersion` semver range.
2. Among satisfying versions, pick the **highest**.
3. For a **`singleton`**, there must be exactly one chosen instance for the whole page; if a loaded version does **not** satisfy a consumer's range, MF emits a **warning** (or throws if `strictVersion`) and the consumer falls back to the singleton anyway.
4. If **no** registered version satisfies and it's not a singleton, the consumer loads its **own** bundled copy (a fallback chunk).

```
 react versions registered:  18.2.0 (shell), 18.3.1 (catalog)
 consumer requires ^18.0.0  → both satisfy → highest wins → 18.3.1 used by all
```

The subtle part: "highest satisfying version wins" means a remote built against an older React can end up *running* against a newer shared React — which is fine within a major but is exactly why **major-version drift on singletons is dangerous**.

#### Q56. [Theory] What is `eager: true` really doing to the loading graph, and when is it justified?

By default a shared module is **async** — Module Federation splits it into its own chunk and loads it via a promise, so the shared scope can be negotiated *before* the module is needed. `eager: true` instead **includes the dependency synchronously in the initial chunk**, so it is available the instant the bundle executes — no extra round trip, no async boundary.

The cost: an eager shared dep is in the entry chunk of **every** build that marks it eager, so if it isn't actually deduplicated at runtime you ship it multiple times, defeating sharing. Eager also forces the value to be resolvable **synchronously**, which constrains version negotiation.

It is justified mainly for:

- The **host's bootstrap path** where you cannot tolerate an async boundary before first paint (e.g. React needed synchronously to render the shell).
- Avoiding the "**Shared module is not available for eager consumption**" error, which appears when something tries to *synchronously* consume a shared module that was set up async. The canonical fix is the **async boundary pattern**: a tiny synchronous `index.js` that does `import('./bootstrap')`, moving all real imports behind one dynamic import so negotiation can complete first — letting you *avoid* eager in most cases.

#### Q57. [Practical] Show the "async boundary" bootstrap pattern and explain what it fixes.

```javascript
// index.js — the real entry. Keep it intentionally tiny and SYNCHRONOUS.
// The dynamic import creates an async boundary so Module Federation can
// negotiate the shared scope BEFORE any shared module is consumed.
import('./bootstrap');

// bootstrap.js — everything that touches shared deps lives here.
import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

createRoot(document.getElementById('root')).render(<React.StrictMode><App /></React.StrictMode>);
```

Without this, the entry chunk would `import React` **synchronously** at the top level. But React is a `shared` module loaded asynchronously, so at the moment the entry runs the shared scope isn't initialized yet — Webpack throws **"Shared module react is not available for eager consumption."** Deferring all real imports behind `import('./bootstrap')` gives the runtime a chance to run each remote's `init` and populate the shared scope first. It is the single most common boilerplate fix in real Module Federation projects.

#### Q58. [Theory] What is a "dynamic remote" and how does it differ from a statically configured remote?

A **static remote** is hard-coded in the build config (`remotes: { catalog: 'catalog@https://.../remoteEntry.js' }`). The URL is baked into the host bundle at build time — to change it you rebuild the host. That couples the host's deploy to remote URLs, which fights the independent-deploy goal.

A **dynamic remote** is resolved **at run time**: the host fetches a manifest/registry to discover the remote's current URL, then loads and initializes it programmatically. Nothing about the remote is in the host bundle.

```javascript
// MF runtime API (MF 2.0) — register remotes from a fetched manifest.
import { init, loadRemote } from '@module-federation/enhanced/runtime';

const manifest = await fetch('/remotes-manifest.json').then(r => r.json());
init({
  name: 'shell',
  remotes: Object.entries(manifest).map(([name, entry]) => ({ name, entry })),
});

const Cart = (await loadRemote('cart/CartWidget')).default;
```

Dynamic remotes are what make **environment promotion** (dev/stage/prod), **canary pointers**, and **per-tenant remote sets** possible without rebuilding the host — the host learns where remotes live from data, not from compiled config.

#### Q59. [Theory] How do import maps and Module Federation differ in *how* they deduplicate a shared dependency?

Both can ensure one React on the page, but the mechanism is fundamentally different:

- **Import maps** deduplicate by **identity of URL**. If every MFE imports the bare specifier `react` and the import map resolves `react` to one URL, the browser's module cache loads that URL **once** and hands the same module namespace to everyone. There is no version *negotiation* — whoever controls the map picks the single version; a consumer needing a different version simply gets the mapped one (or must use a scoped map).
- **Module Federation** deduplicates by **run-time negotiation** in the shared scope. Multiple versions can be *registered*; the runtime *chooses* a compatible one per the semver ranges, with warnings/fallbacks on mismatch.

```
 Import maps:      "react" → one URL → browser caches once   (policy = the map author)
 Module Fed:       react@A, react@B registered → negotiate highest satisfying range
```

Consequence: import maps are simpler and bundler-agnostic but **centralize** version control; Module Federation is more flexible and tolerant of drift but more complex. Native Federation deliberately combines them — import maps for resolution, an MF-style manifest for orchestration.

#### Q60. [Practical] How do you wire CSS custom properties so a design-token theme pierces Shadow DOM boundaries into every MFE?

CSS custom properties **inherit through shadow boundaries** (unlike most styles), which makes them the standard bridge for theming Shadow-DOM-isolated MFEs.

```javascript
// 1. Define tokens once at the shell, on :root (the document, outside any shadow).
const tokens = document.createElement('style');
tokens.textContent = `:root {
  --color-brand: #0066cc;
  --space-md: 12px;
  --radius: 8px;
}`;
document.head.appendChild(tokens);

// 2. Inside an MFE's Shadow DOM, CONSUME the inherited variables.
class CartButton extends HTMLElement {
  connectedCallback() {
    this.attachShadow({ mode: 'open' }).innerHTML = `
      <style>
        /* These custom props pierce the shadow boundary from :root. */
        button {
          background: var(--color-brand, #333); /* fallback if shell didn't set it */
          padding: var(--space-md, 8px);
          border-radius: var(--radius, 4px);
        }
      </style>
      <button><slot>Add to cart</slot></button>`;
  }
}
customElements.define('cart-button', CartButton);
```

To **re-theme at run time** (dark mode, white-label tenant), the shell just updates the `:root` custom properties; every shadow root recomputes automatically because they reference the inherited variables. No MFE rebuild, no per-MFE CSS reship — the token layer is the contract.

#### Q61. [Theory] What is the role of `output.uniqueName` and how do federated runtimes avoid colliding on Webpack's runtime globals?

Webpack injects chunk-loading machinery onto a global array, historically `window.webpackJsonp` and now keyed by `output.uniqueName` (e.g. `self["webpackChunk_acme_catalog"]`). If two independently built bundles share the **same** uniqueName, their JSONP callbacks, module registries, and `__webpack_require__` runtimes collide — chunks from one app get fed into the other's registry, causing "module not found" and duplicate-execution chaos.

Module Federation sets `uniqueName` from the federation `name` by default, so each remote's runtime is namespaced. The internals you rely on:

- **`__webpack_require__.l`** — the script-loading function each runtime uses to fetch chunks; namespaced per build.
- **`__webpack_share_scopes__`** — the shared dependency registry; this one is *deliberately global* so builds can dedupe, but it is keyed by package + version, not by build.
- **`__webpack_require__.S`** — the share-scope API used during `init`.

The practical rule: **never give two federated builds the same `name`/`uniqueName`**, and don't manually reset these runtime globals — collisions here produce baffling, non-local failures.

#### Q62. [Practical] Implement run-time remote discovery from a manifest with a typed fallback when a remote is missing.

```javascript
// remotes-manifest.json (served by the shell, mutable per environment):
// { "cart": "https://cdn.acme.com/cart/v2.3.1/remoteEntry.js",
//   "reviews": "https://cdn.acme.com/reviews/v1.0.4/remoteEntry.js" }

const registry = new Map();

async function getManifest() {
  const res = await fetch('/remotes-manifest.json', { cache: 'no-store' });
  if (!res.ok) throw new Error(`manifest ${res.status}`);
  return res.json();
}

// Load a remote container, init the shared scope, and return an exposed module.
async function loadRemoteModule(remote, exposed) {
  if (!registry.has(remote)) {
    const manifest = await getManifest();
    const url = manifest[remote];
    if (!url) throw new Error(`Unknown remote: ${remote}`); // missing pointer
    await __webpack_init_sharing__('default');             // ensure shared scope ready
    await import(/* webpackIgnore: true */ url);           // loads remoteEntry → window[remote]
    const container = window[remote];
    await container.init(__webpack_share_scopes__.default); // negotiate shared deps
    registry.set(remote, container);
  }
  const factory = await registry.get(remote).get(exposed);
  return factory();
}

// Usage with graceful degradation:
export async function mountCart(el) {
  try {
    const { mount } = await loadRemoteModule('cart', './CartWidget');
    mount(el);
  } catch (err) {
    console.error('cart unavailable', err);
    el.textContent = 'Cart is temporarily unavailable.'; // partial degradation
  }
}
```

This is the un-sugared form of what MF 2.0's `loadRemote` does: fetch manifest → load `remoteEntry` → `init` shared scope → `get` the exposed factory — with a hard fallback when the pointer is absent or the network fails.

### 🟠 — extended

#### Q63. [Theory] How does Declarative Shadow DOM make Shadow-DOM micro-frontends server-renderable, and what was the blocker before it?

Before **Declarative Shadow DOM (DSD)**, a shadow root could only be created **imperatively in JavaScript** (`element.attachShadow()`). That meant a server could not emit a shadow tree in HTML — the encapsulated content didn't exist until client JS ran. For SSR'd Web-Component MFEs this was fatal: the server produced an empty custom element, so there was no above-the-fold content and no SEO text until hydration.

DSD lets the **server serialize a shadow root directly in markup** using a `<template shadowrootmode>`:

```html
<cart-widget>
  <template shadowrootmode="open">
    <style>button { background: var(--color-brand); }</style>
    <button>Checkout</button>
  </template>
</cart-widget>
```

The browser parser sees `shadowrootmode` and **attaches the shadow root during HTML parsing**, before any script — so the encapsulated, styled content is present on first paint and is real DOM for crawlers. This unblocks streaming SSR of isolated MFEs. The remaining caveats: hydration must adopt (not recreate) the existing shadow root, and tooling/framework support for emitting DSD is still maturing in 2026 though now broadly available across evergreen browsers.

#### Q64. [Theory] What are the consistency hazards of federated SSR, and how do hydration mismatches arise specifically in MFEs?

Federated SSR renders remote MFE components on the Node server and re-hydrates them on the client. Hydration requires the client to produce **byte-identical** markup to what the server emitted; MFEs add several distributed failure modes:

- **Version skew across the network boundary.** The server pinned `cart@2.3.0` at render time, but the client manifest already flipped to `cart@2.4.0` mid-deploy. Server HTML and client component disagree → mismatch.
- **Divergent shared-dep versions** on server vs. client (different negotiated React) producing subtly different output.
- **Non-deterministic content** (dates, A/B flags, `Math.random`, locale) resolved differently in the two environments.
- **Independent remote timeouts** — a remote that timed out on the server renders a fallback, but succeeds on the client, so the trees differ.

Mitigations: **pin the exact remote+shared versions used for a given server render and ship that pin to the client** (e.g. embed the resolved manifest in the HTML), make rendering deterministic, treat each fragment's hydration with its **own error boundary** so a single mismatch re-renders just that island rather than discarding the whole page, and version the server and client artifacts together for a render.

#### Q65. [Practical] How do you implement a per-fragment timeout-with-fallback in a server-side composition layer?

```javascript
// Edge/Node composer: request each MFE fragment, but never let one slow
// remote stall the whole page. Each fragment gets its own deadline + fallback.
async function fetchFragment(name, url, ms = 600) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), ms);
  try {
    const res = await fetch(url, { signal: ctrl.signal });
    if (!res.ok) throw new Error(`${name} ${res.status}`);
    return await res.text();
  } catch (err) {
    // Degrade THIS fragment only; the rest of the page proceeds.
    return `<div data-mfe="${name}" data-fallback="1">${name} unavailable</div>`;
  } finally {
    clearTimeout(t);
  }
}

async function composePage(fragments) {
  // Fire all fragment requests in parallel; bounded by each one's own timeout.
  const parts = await Promise.all(
    fragments.map(f => fetchFragment(f.name, f.url, f.budgetMs))
  );
  return `<!doctype html><html><body>
    <div id="header">${parts[0]}</div>
    <main>${parts[1]}</main>
    <aside>${parts[2]}</aside>
  </body></html>`;
}
```

Two principles encoded here: **fan out in parallel** so total latency is the slowest *within budget*, not the sum; and a **per-fragment fallback** so the page's blast radius for one failing remote is exactly that fragment's slot. For streaming, you'd flush the shell and ready fragments first and stream the slower ones as they resolve.

#### Q66. [Theory] How do proxy-based sandboxes (e.g. qiankun's JS sandbox) isolate a micro-frontend's global-scope mutations, and what are their limits?

Frameworks like **qiankun** wrap each MFE's execution in a **JavaScript sandbox** so that an MFE's writes to `window` don't pollute the shared global or other MFEs. Two implementations exist:

- **Snapshot sandbox** (legacy, single-instance): before mount, record a snapshot of `window`'s enumerable props; on unmount, **diff and restore** — undo every property the MFE added/changed. Cheap but only supports one MFE active at a time and can't catch non-enumerable or deep mutations.
- **Proxy sandbox** (multi-instance): give each MFE a `Proxy` over a *fake* window. Reads fall through to the real `window`; **writes are trapped and stored on the per-MFE fake**, never touching the real global. Multiple MFEs each get their own isolated overlay, so they can run concurrently.

```javascript
// Simplified proxy-sandbox idea:
function createSandbox(realWindow) {
  const fake = {};
  return new Proxy(realWindow, {
    get: (t, k) => (k in fake ? fake[k] : t[k]),
    set: (t, k, v) => { fake[k] = v; return true; }, // write stays local
    has: (t, k) => k in fake || k in t,
  });
}
```

Limits: the MFE code must actually *run against* the proxy (qiankun rewrites the global reference / uses `with`-style scoping), so it requires loading the MFE as a string and evaluating it in the sandbox — incompatible with native ESM remotes and with code that grabs the real `window` directly. It also can't sandbox truly destructive APIs (prototype mutation, shared DOM). For untrusted code, an **iframe remains the only real security boundary**; proxy sandboxes are about *accidental* collisions, not adversarial isolation.

#### Q67. [Theory] What is the difference between "fragment composition" via ESI/SSI and modern edge-side composition with workers?

Both stitch HTML fragments server-side, but they differ in *where* and *how programmable* the stitching is:

- **SSI (Server Side Includes)** — directives like `<!--#include virtual="/header" -->` processed by a web server (Apache/nginx). Simple, static, no logic beyond include.
- **ESI (Edge Side Includes)** — `<esi:include src="..."/>` tags processed by a CDN/reverse proxy (Akamai, Varnish). Adds caching per fragment, fallbacks (`onerror`), and conditionals, but it's a **fixed markup vocabulary** — limited programmability.
- **Edge workers** (Cloudflare Workers, Fastly Compute, Lambda@Edge) — run **arbitrary JavaScript at the edge**. They fetch fragments, apply auth, A/B logic, per-fragment timeouts, streaming, and assemble using the full language — effectively a programmable BFF at the CDN tier.

```
 SSI/ESI:  declarative include tags, fixed semantics, CDN-cached fragments
 Worker:   full JS — fetch, transform, stream, auth, per-fragment policy
```

The trajectory in 2026 is toward **edge workers with streaming HTML rewriting** (e.g. `HTMLRewriter`) because they keep ESI's caching/locality benefits while removing its expressiveness ceiling — you can implement per-fragment circuit breakers, personalization, and partial-streaming that ESI never could.

#### Q68. [Practical] How do you detect duplicate shared dependencies actually shipping in production, programmatically?

You instrument the **runtime**, because build stats lie about what *actually* loads after negotiation. Two complementary checks:

```javascript
// 1. Count distinct copies of a singleton at runtime by tagging the module.
//    Each loaded React copy stamps a unique symbol; >1 distinct id = duplication.
function assertSingleReact(React) {
  const KEY = '__react_instance_id__';
  React[KEY] ??= Math.random().toString(36).slice(2);
  (window.__reactIds ??= new Set()).add(React[KEY]);
  if (window.__reactIds.size > 1) {
    console.error('Duplicate React instances:', [...window.__reactIds]);
  }
}

// 2. Inspect the MF shared scope to see how many versions were registered.
function reportSharedScope(scopeName = 'default') {
  const scope = __webpack_share_scopes__?.[scopeName] ?? {};
  for (const [pkg, versions] of Object.entries(scope)) {
    const loaded = Object.entries(versions).filter(([, v]) => v.loaded);
    if (loaded.length > 1) {
      console.warn(`${pkg}: ${loaded.length} versions loaded`, loaded.map(([v]) => v));
    }
  }
}
```

Feed both into **RUM**: emit a metric when `__reactIds.size > 1` or any shared package has >1 loaded version, tagged by which remotes are on the page. This catches the case where a team forgot `singleton`, or where major-version drift forced two copies — *in production*, where the assembled combination is the only place the truth is visible. Pair with a **synthetic assembly check** in CI that loads the real composed page and asserts duplicate-module count is zero.

### 🔴 — extended

#### Q69. [Theory] How would you architect shared run-time state across MFEs without coupling every team to one store's shape?

The anti-pattern is a single global Redux/Zustand store imported by every MFE — it recreates the monolith's coupling (every team depends on the store's schema; a shape change breaks everyone). Better architectures invert the dependency:

- **Event-sourced bus + local projections.** MFEs publish **domain events** (`cart:item-added`) on a versioned contract bus; each MFE keeps its **own** local state, projecting from events it cares about. No shared schema — only a shared *event vocabulary*. This is the loosest coupling.
- **Per-domain owned slices behind an interface.** If shared state is unavoidable (e.g. the cart), **one MFE owns** that state and exposes a **narrow, versioned API** (`getCart()`, `subscribe()`, `addItem()`); others consume the interface, never the raw store. The owner can refactor internals freely.
- **URL/query as shared state** for anything bookmarkable/navigable — naturally decoupled and the shell coordinates it.

```javascript
// Owned-slice pattern: cart MFE owns state, exposes a stable interface.
window.__acme ??= {};
window.__acme.cart = {
  get: () => structuredClone(state),        // read-only copy, hides internals
  subscribe: (cb) => bus.on('cart:changed', cb),
  addItem: (item) => { /* validated mutation */ bus.emit('cart:changed', state); },
};
```

The governing principle: **share contracts, not data structures.** Events and narrow interfaces version cleanly; a shared mutable object's shape becomes an unversioned dependency the moment two teams read it.

#### Q70. [Theory] What does a comprehensive versioning strategy for a federated platform look like across artifacts, shared deps, and contracts?

Three *independent* versioning planes must each be governed, because they fail differently:

1. **Artifact versioning** — every remote build is an **immutable, content-hashed** artifact (`cart/v2.4.1/`). Deploy = flip a pointer in the manifest; rollback = flip back. Old artifacts stay live so in-flight sessions don't break. Never overwrite a version.
2. **Shared-dependency versioning** — singletons (React, design system) follow an **org-wide supported-range policy** (e.g. "React `^18`, N and N-1 majors supported for a 2-quarter window"). CI validates each remote's `shared` config against the policy and rejects incompatible ranges. Majors are migrated org-wide on a schedule, not ad hoc.
3. **Contract versioning** — cross-MFE events/APIs live in **semver'd contract packages**; breaking changes bump major and the platform supports N and N-1 during migration, with **contract tests (Pact)** as the runtime gate.

```
 Artifact:   immutable hashed bundle  + mutable manifest pointer   (instant deploy/rollback)
 Shared dep: org policy + CI range validation + supported-version window
 Contract:   semver'd package + consumer-driven contract tests
```

The platform's job is to make the **safe path the default**: scaffolding sets compliant `shared` config, CI blocks policy violations, and the manifest enforces that only validated artifacts can be pointed to in prod. Versioning that relies on humans coordinating across 50 teams will drift; it must be encoded in tooling.

#### Q71. [Theory] How do you reason about and budget the loading waterfall in a deeply federated page?

Federation introduces serial dependencies that can stack into a waterfall: shell HTML → shell JS → fetch `remoteEntry.js` → negotiate shared scope → fetch the exposed module's chunk → fetch *its* shared chunks → render. Each arrow is a potential round trip. The reasoning framework:

- **Identify the critical chain** for above-the-fold content and count its round trips. Anything off the critical path (below-the-fold MFEs) should be deferred, not in the chain.
- **Collapse round trips** with **`<link rel="preload"/modulepreload">`** for the critical `remoteEntry` and its first chunk, emitted in the shell's initial HTML so the browser fetches them in parallel with shell JS rather than after it.
- **Prefetch on intent** (hover/route-prediction) for likely-next MFEs so navigation feels instant.
- **Exploit HTTP/2-3 multiplexing** — many small chunks over one connection is cheaper than it used to be, but TLS/connection setup to *different remote origins* still costs; consider **serving remotes from one origin** (or a CDN with connection coalescing) to avoid per-origin handshakes.
- **SSR/stream the above-the-fold MFE** so its content arrives in the first HTML, removing it from the client waterfall entirely.

```
 Critical chain (bad):  shell.js → remoteEntry → moduleChunk → sharedChunk  (4 RTTs)
 Optimized:             modulepreload remoteEntry+chunk in initial HTML     (parallel)
                        + SSR above-the-fold fragment                       (0 client RTT)
```

The budget is expressed as **total critical-path round trips and bytes for the assembled page**, enforced by synthetic checks — not per-MFE numbers, which hide the cumulative cost.

#### Q72. [Practical] Implement intent-based prefetching of a remote so navigation to it is instant.

```javascript
// Prefetch a remote's entry + first chunk when the user signals intent
// (hovers/focuses a link), so the actual navigation has nothing to fetch.
const prefetched = new Set();

function prefetchRemote(url) {
  if (prefetched.has(url)) return;
  prefetched.add(url);
  const link = document.createElement('link');
  link.rel = 'modulepreload';   // parse + fetch as a module, populate module cache
  link.href = url;
  link.crossOrigin = 'anonymous';
  document.head.appendChild(link);
}

// Wire it to intent signals on the shell's nav.
function wirePrefetch(anchor, remoteEntryUrl) {
  const trigger = () => prefetchRemote(remoteEntryUrl);
  anchor.addEventListener('mouseenter', trigger, { once: true });
  anchor.addEventListener('focus', trigger, { once: true });
  // Also prefetch when the link scrolls into view, throttled:
  new IntersectionObserver((entries, obs) => {
    for (const e of entries) if (e.isIntersecting) { trigger(); obs.disconnect(); }
  }).observe(anchor);
}

// Example:
wirePrefetch(document.querySelector('a[href="/checkout"]'),
             'https://cdn.acme.com/checkout/v3/remoteEntry.js');
```

The pattern trades a small amount of speculative bandwidth for the **removal of the remote-fetch round trip from the navigation's critical path**. Tune aggressiveness by signal (hover is high-intent; viewport visibility is lower) and respect `navigator.connection.saveData` / slow connections to avoid wasting metered bandwidth.

#### Q73. [Theory] How do you migrate from Webpack Module Federation 1.x to Module Federation 2.0 across many remotes without a lockstep cutover?

MF 2.0 is designed for **incremental** adoption because the underlying `get`/`init` container contract is preserved — a 2.0 host can consume a 1.x remote and vice versa within compatible runtime ranges. The migration plan:

- **Adopt the runtime first on the host.** Replace static `remotes` with the **MF 2.0 runtime** (`init`/`loadRemote`) and a **manifest (`mf-manifest.json`)**, keeping existing remote URLs. Now the host can load both old and new remotes from data.
- **Migrate remotes one at a time** to emit the 2.0 manifest and types. Each migrated remote gains **type sharing** (consumers get `.d.ts` for exposed modules) and richer runtime hooks. Unmigrated remotes keep working via the legacy entry.
- **Layer in runtime plugins** (the 2.0 plugin system) for cross-cutting concerns — custom shared-scope resolution, error handling, telemetry — without touching each remote.
- **Validate continuously** with contract tests and a synthetic assembly check that loads the real composed page, so a half-migrated graph is provably still consistent.
- **Roll back per remote** by flipping its manifest pointer — the immutable-artifact + pointer model means migration risk is per-remote and reversible, never a big-bang.

The strategic point: because federation versions are negotiated and the container contract is stable, **you migrate the platform plane (host runtime + manifest) first, then drain remotes over time** — there is no day where everything must switch at once.

#### Q74. [Theory] How do you defend a federated platform against a malicious or compromised remote, given remotes execute in the host's origin?

This is the uncomfortable truth of run-time JS integration: **a federated remote runs with the host's full privileges** — same origin, same DOM, same cookies, same `localStorage`. A compromised remote (supply-chain attack, hijacked CDN path) can exfiltrate tokens or inject UI. Defenses, layered:

- **Subresource Integrity (SRI) on remote entries.** Pin the expected hash of each `remoteEntry.js` in the manifest; the browser refuses a tampered bundle. Requires immutable, hashed artifacts (which you already have) and tooling to keep SRI hashes in the manifest.
- **A strict Content Security Policy owned by the shell** — `script-src` allowlisting only known remote origins, `connect-src` limiting exfiltration endpoints, nonce/hash-based inline-script control. This caps what even a compromised remote can reach.
- **Provenance + signing in CI.** Sign artifacts (e.g. Sigstore/SLSA provenance); the manifest only accepts pointers to signed builds from the paved-road pipeline.
- **Strong isolation for genuinely untrusted code** — third-party or low-trust MFEs go in an **iframe** (separate origin) with `postMessage` contracts, *not* same-origin federation. Federation's convenience assumes a **trusted first-party** remote set.
- **Least-privilege token handling** — httpOnly cookies over JS-readable tokens, so a script injection can't trivially read the session.

The governing rule: **same-origin federation is a trust decision.** It is appropriate for first-party teams under one security org with a paved-road pipeline; for anything outside that trust boundary, the only real isolation is an iframe/separate origin, accepting the UX cost.

#### Q75. [Practical] How do you add Subresource Integrity to dynamically loaded federated remotes?

```javascript
// Manifest carries the expected SRI hash per remote, produced by the build.
// { "cart": { "url": "https://cdn.acme.com/cart/v2.4.1/remoteEntry.js",
//             "integrity": "sha384-Base64Hash..." } }

async function loadRemoteWithSRI(name) {
  const manifest = await fetch('/remotes-manifest.json', { cache: 'no-store' })
    .then(r => r.json());
  const { url, integrity } = manifest[name] ?? {};
  if (!url) throw new Error(`Unknown remote ${name}`);

  // Load via a <script> tag so the browser enforces integrity before executing.
  await new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = url;
    if (integrity) s.integrity = integrity;        // browser verifies hash
    s.crossOrigin = 'anonymous';                   // required for SRI on cross-origin
    s.onload = resolve;
    s.onerror = () => reject(new Error(`integrity/load failure for ${name}`));
    document.head.appendChild(s);
  });

  await __webpack_init_sharing__('default');
  await window[name].init(__webpack_share_scopes__.default);
  return window[name];
}
```

Two requirements make this work: the remote must be served with **`Access-Control-Allow-Origin`** (SRI on cross-origin scripts needs `crossorigin="anonymous"` + CORS), and the build pipeline must **emit the hash into the manifest** atomically with the artifact so the pinned hash always matches the immutable bundle. If a CDN object is tampered with, the hash check fails and the remote refuses to execute — converting a silent supply-chain compromise into a contained, observable load failure.

#### Q76. [Theory] What observability signals are unique to micro-frontends that a monolith's telemetry would miss?

Because the assembled app is a **run-time combination never fully tested by one pipeline**, MFE observability needs signals tied to *composition*, not just to code:

- **Per-remote version-in-production.** Emit which version of each remote actually loaded, per page view. This is the only way to know the **live combination** and to correlate an error spike with a specific remote deploy/pointer flip.
- **Shared-scope duplication metric.** Count distinct loaded versions of each singleton at run time (see the duplicate-detection technique) and alert on >1 — catches a forgotten `singleton` *in prod*.
- **Per-slot error attribution.** Errors caught by each MFE's error boundary, tagged by remote name + version, so a failing remote's blast radius and ownership are immediately visible.
- **Per-remote Core Web Vitals.** Attribute LCP/INP/CLS to the MFE slot that caused them, so you can hold the owning team to a budget rather than blaming "the page."
- **Pointer-flip events.** Treat each manifest pointer change as a deploy marker in telemetry, so dashboards can overlay "cart v2.4.1 → v2.4.2 at 14:03" against error/latency, enabling fast automated rollback on error-budget breach.
- **Composition latency breakdown.** Time spent in manifest fetch → remoteEntry → shared negotiation → first render, per remote, to find waterfall regressions.

The unifying idea: monolith telemetry assumes **one versioned artifact**; MFE telemetry must make the **assembled, multi-version combination** observable, because that combination is what users actually run and no single CI ever validated it.

#### Q77. [Theory] When does the "distributed system" nature of micro-frontends bite hardest, and how does that shape design?

MFEs are a **distributed system rendered in one viewport**, and the classic distributed-systems hazards reappear in browser form — usually underestimated because "it's just frontend." Where it bites:

- **Partial failure is the normal case, not the exception.** Any remote can be slow, down, or mid-deploy on any given load. Designs that assume all parts are present (synchronous cross-MFE calls, shared mutable state read eagerly) shatter; you must engineer **independent degradation per slot** from day one.
- **No global transaction.** There's no atomic "deploy the whole app" — versions skew across remotes constantly, so every cross-MFE contract is effectively a **network protocol** that must be versioned and backward-compatible (N, N-1).
- **Eventual consistency of the assembled app.** A pointer flip propagates to users over time (caching, in-flight sessions); two users can be running different combinations simultaneously. Features must tolerate **mixed-version coexistence**.
- **Cascading latency.** A serial load waterfall is a chain of remote calls; one slow hop delays everything downstream — demanding timeouts, prefetch, and budgets like any RPC chain.

This reframes the design rules: **contracts over shared memory, idempotent/decoupled communication, per-slot resilience, version tolerance, and observability of the live combination** are not nice-to-haves — they are the same disciplines that make backend microservices survivable, applied to the browser. The teams that fail with MFEs are usually the ones who treated it as a build-tool feature rather than a distributed-systems commitment.

#### Q78. [Practical] How do you implement an automated rollback that triggers on a per-remote error-budget breach after a pointer flip?

```javascript
// A control-loop (runs in your deploy/monitoring service, not the browser):
// after a pointer flip, watch the new version's error rate; auto-revert on breach.

async function deployWithGuardedRollout({ remote, fromVersion, toVersion, manifestApi, metrics }) {
  // 1. Flip the manifest pointer to the new version (the actual "deploy").
  await manifestApi.setPointer(remote, toVersion);
  const deployedAt = Date.now();

  const ERROR_BUDGET = 0.02;      // 2% error rate ceiling for the new version
  const WINDOW_MS = 5 * 60_000;   // observe for 5 minutes
  const POLL_MS = 15_000;

  // 2. Poll per-remote, per-version RUM error rate during the bake window.
  while (Date.now() - deployedAt < WINDOW_MS) {
    await sleep(POLL_MS);
    const { errorRate, sampleCount } = await metrics.errorRate({
      remote, version: toVersion, sinceMs: deployedAt,
    });
    if (sampleCount < 50) continue;            // not enough signal yet
    if (errorRate > ERROR_BUDGET) {
      // 3. Breach → instant rollback by flipping the pointer back.
      await manifestApi.setPointer(remote, fromVersion);
      await alert(`Auto-rolled back ${remote} ${toVersion}→${fromVersion}: `
                + `errorRate=${(errorRate * 100).toFixed(1)}%`);
      return { rolledBack: true, version: fromVersion };
    }
  }
  // 4. Survived the bake window → promote to full traffic.
  await alert(`${remote} ${toVersion} passed bake window, fully rolled out.`);
  return { rolledBack: false, version: toVersion };
}
```

This works **only because of the immutable-artifact + mutable-pointer model**: rollback is a pointer flip (seconds), not a rebuild, so the control loop can react in real time. The error rate must be attributed **per remote and per version** (the MFE-specific telemetry from the prior question) — otherwise you can't tell whether the new `cart` deploy caused the spike or some other remote did. Combine with **canary** (flip the pointer for a traffic fraction first) to shrink blast radius before the full bake.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q79. [Practical] A teammate reports "Invalid hook call" only on the page that composes both the `cart` and `reviews` MFEs, but each works fine in isolation. Walk through how you'd diagnose it.

This is the textbook symptom of **two React copies on one page**. In isolation each MFE bundles its own React and everything is internally consistent; composed together, a component rendered by one React calls a hook resolved against the *other* React, and the dispatcher is `null`.

Diagnosis steps:

1. **Confirm the duplicate empirically** in the browser console:

```javascript
// Run on the composed page. If both MFEs share React there should be ONE.
const reacts = performance.getEntriesByType('resource')
  .filter((r) => /react(-dom)?(\.production)?[.\-]/.test(r.name))
  .map((r) => r.name);
console.log('React-ish resources loaded:', reacts);
```

2. **Check the shared scope** — many MF builds expose it:

```javascript
console.log(Object.keys(__webpack_share_scopes__.default.react ?? {}));
// More than one version key => negotiation failed; someone isn't sharing.
```

3. **Inspect each MFE's `shared` config.** The culprit almost always forgot `singleton: true` on `react`/`react-dom`, or pinned an incompatible `requiredVersion` so negotiation fell back to a private copy.

The fix is to mark React/ReactDOM `singleton: true` with a compatible range in **every** participating build. The lesson: a federation bug is invisible until two specific MFEs co-render — which is exactly why contract/assembly tests matter.

#### Q80. [Practical] Your remote loads fine locally but in production the browser console shows a CORS error fetching `remoteEntry.js`. What's going on and how do you fix it?

`remoteEntry.js` and the chunks it pulls are fetched **cross-origin** when the remote lives on a different domain/subdomain/port than the host. The browser blocks the response unless the remote's server sends permissive CORS headers.

Checklist:

- The remote's CDN/server must return `Access-Control-Allow-Origin` covering the host origin (a specific origin, or `*` for public assets) on **both** `remoteEntry.js` and the lazily loaded JS chunks.
- The dynamic `<script>`/`fetch` that loads chunks must use the right `crossorigin` mode. In Webpack set the cross-origin loading policy so chunk requests are CORS-aware:

```javascript
// Remote webpack.config.js
module.exports = {
  output: {
    crossOriginLoading: 'anonymous', // emit <script crossorigin> for chunks
    // publicPath must be the remote's absolute URL so chunks resolve there,
    // not relative to the host:
    publicPath: 'https://catalog.acme.com/',
  },
};
```

- If you later add Subresource Integrity, `crossorigin` is **required** for SRI to work at all.

A frequent secondary bug: `publicPath: 'auto'` resolves chunk URLs relative to the *host* page, so the host tries to fetch the remote's chunks from its own origin and 404s. Pin `publicPath` to the remote's absolute URL (or compute it at runtime from `document.currentScript`).

#### Q81. [Practical] After deploying a new version of the shell, users on the old shell start getting 404s for chunk files. Why, and how do you prevent it?

This is the **stale-chunk / long-session** problem. The shell's HTML references content-hashed chunk filenames (`main.4f3a.js`). When you deploy a new build, the new chunks have new hashes; if your deploy **overwrites or deletes** the old files, any browser still running the previous HTML (a long-open tab, a slow navigation) requests chunks that no longer exist → 404 → white screen.

Prevention:

- **Keep old hashed assets live** for a rollover window (retain N previous builds on the CDN). Immutable, content-hashed filenames never collide, so there's no reason to delete them immediately.
- **Long-cache the hashed assets** (`Cache-Control: immutable, max-age=31536000`) and **never cache the HTML/manifest** (or cache it very briefly), so the entry document is always fresh while assets are sticky.
- **Detect chunk-load failures and recover** by forcing a reload to pick up the new HTML:

```javascript
window.addEventListener('error', (e) => {
  const msg = e?.message || '';
  if (/Loading chunk \d+ failed|ChunkLoadError|error loading dynamically imported module/i.test(msg)) {
    // New deploy invalidated this session's chunks — reload once to get fresh HTML.
    if (!sessionStorage.getItem('chunk-reloaded')) {
      sessionStorage.setItem('chunk-reloaded', '1');
      location.reload();
    }
  }
});
```

The `sessionStorage` guard prevents an infinite reload loop if the failure is something other than a stale deploy.

#### Q82. [Theory] Why is `publicPath: 'auto'` a footgun for federated remotes, and what does it actually compute?

`publicPath` tells the runtime the base URL it should prefix onto chunk filenames when it injects `<script>` tags. `'auto'` makes Webpack infer it at runtime from the location of the **currently executing script**. For a normal app that's correct. For a federated **remote** consumed by a host, the inference can resolve against the *host's* document context, so the remote tries to load *its* chunks from the *host's* origin — which doesn't have them.

The safe options:

- **Hard-code the remote's absolute origin** (`publicPath: 'https://catalog.acme.com/'`) — simplest when the URL is stable per environment.
- **Compute it from the remote entry at runtime** so the same artifact works across environments:

```javascript
// At the very top of the remote's entry, before any chunk loads.
const cs = document.currentScript;
if (cs) {
  // e.g. https://catalog.acme.com/remoteEntry.js -> https://catalog.acme.com/
  __webpack_public_path__ = cs.src.replace(/\/[^/]+$/, '/');
}
```

The rule of thumb: a remote must always know its own absolute home, because someone else's page is loading it.

#### Q83. [Practical] A non-React MFE needs to be mounted by a React shell. Show a clean integration boundary that doesn't leak framework details across the seam.

Wrap the foreign MFE behind a stable **mount/unmount contract** and adapt it with a thin React component that just manages the DOM node and lifecycle.

```javascript
import { useEffect, useRef } from 'react';

// The remote exposes a framework-agnostic contract:
//   mount(el, props) -> returns an unmount function
// (could be Vue, Svelte, vanilla — the shell never knows).
import mountLegacyCart from 'cart/mount';

export function CartHost(props) {
  const hostRef = useRef(null);
  const unmountRef = useRef(null);

  useEffect(() => {
    // Mount once when the node is attached.
    unmountRef.current = mountLegacyCart(hostRef.current, props);
    return () => {
      // Always clean up so the foreign framework tears down listeners/timers.
      unmountRef.current?.();
      unmountRef.current = null;
    };
  }, []); // mount once; pass updates via the imperative API below

  // Forward prop changes imperatively rather than remounting.
  useEffect(() => {
    unmountRef.current && props.onUpdate /* optional update channel */;
  }, [props]);

  return <div ref={hostRef} />;
}
```

The key discipline: the contract is **just DOM + plain data + a teardown function**. React owns the wrapper node's lifecycle; the foreign framework owns everything inside it. Neither imports the other's runtime.

#### Q84. [Practical] How would you debug "my event bus message is sometimes missed by the header MFE"?

Intermittently-missed messages on a pub/sub bus almost always come from a **subscribe-after-emit race**: the publisher MFE mounted and emitted before the subscriber MFE finished mounting and called `on(...)`. Fire-and-forget buses have no replay, so the early message is simply lost.

How to confirm and fix:

1. **Add ordering visibility** — log a timestamp + a monotonically increasing id on every emit and every subscribe, and you'll see the emit precede the subscribe.
2. **Give the bus "last value" / replay semantics** for state-like events (as opposed to true one-shot events):

```javascript
function createReplayBus() {
  const listeners = new Map();
  const lastValue = new Map(); // event -> most recent payload

  return {
    on(event, handler, { replay = true } = {}) {
      (listeners.get(event) ?? listeners.set(event, new Set()).get(event)).add(handler);
      // Deliver the latest payload immediately so late subscribers aren't starved.
      if (replay && lastValue.has(event)) handler(lastValue.get(event));
      return () => listeners.get(event)?.delete(handler);
    },
    emit(event, payload) {
      lastValue.set(event, payload);
      listeners.get(event)?.forEach((h) => { try { h(payload); } catch (e) { console.error(e); } });
    },
  };
}
```

For genuine one-shot events (a click), prefer an explicit request/response or have the late MFE **pull current state** on mount (`window.__cart.getCount()`) rather than relying on having heard the past emit.

#### Q85. [Theory] A PM asks "can we just put each feature in its own iframe — isn't that simpler?" Give a balanced, practical answer.

Yes, it's simpler for *isolation*, and for some cases it's genuinely right — but be honest about where it bites:

- **Where iframes win:** bulletproof CSS/JS isolation, embedding a third-party or legacy app you don't control, security sandboxing of untrusted code (`sandbox` attribute), and zero risk of dependency collisions.
- **Where they hurt the product:** responsive sizing is awkward (you must postMessage heights), communication is `postMessage`-only with no shared memory, each iframe re-downloads its framework (no shared React), deep linking / shared routing / the back button are clumsy, and focus management, accessibility, and modals that need to overflow the frame are painful.

The practical framing for the PM: iframes optimize for *isolation* at the cost of *seamless UX and performance*. If the features are largely independent panels (a dashboard of embeds), iframes can be the pragmatic choice. If users expect one cohesive app with shared navigation, shared design, and good Core Web Vitals, run-time composition (Module Federation / Web Components) gives the seamless experience iframes can't. Recommend iframes for true embeds, not as the backbone of an integrated product.

#### Q86. [Practical] Show how to size an iframe-based MFE to its content so it doesn't have an internal scrollbar.

An iframe can't auto-size to its content; the embedded document must measure itself and tell the parent via `postMessage`, and the parent sets the height.

```javascript
// Inside the embedded MFE document:
function reportHeight() {
  const h = document.documentElement.scrollHeight;
  parent.postMessage({ type: 'mfe:resize', source: 'cart', height: h }, 'https://shell.acme.com');
}
new ResizeObserver(reportHeight).observe(document.documentElement);
window.addEventListener('load', reportHeight);
```

```javascript
// In the shell (parent):
const frame = document.getElementById('cart-frame');
window.addEventListener('message', (e) => {
  // ALWAYS validate origin — never trust arbitrary postMessage senders.
  if (e.origin !== 'https://cart.acme.com') return;
  if (e.data?.type === 'mfe:resize' && e.data.source === 'cart') {
    frame.style.height = e.data.height + 'px';
  }
});
```

Two non-negotiables: **validate `e.origin`** on the receiving side (otherwise any page can spoof resize messages), and target a **specific origin** in the child's `postMessage` rather than `'*'`.

### 🟡 — extended

#### Q87. [Practical] You need to share a single Redux/Zustand store instance across MFEs without coupling every team to its exact shape. How?

Don't share the *store*; share a **narrow, versioned facade** over it. The platform owns the store as a singleton and exposes selectors + actions as a typed contract; MFEs depend on the contract, not on the store's internal structure.

```javascript
// @acme/app-state (versioned singleton package owned by the platform)
import { createStore } from 'zustand/vanilla';

const store = createStore(() => ({ user: null, cartCount: 0 }));

// Public, stable API — internal shape can change behind these.
export const appState = {
  getCartCount: () => store.getState().cartCount,
  subscribeCartCount(cb) {
    let prev = store.getState().cartCount;
    return store.subscribe((s) => { if (s.cartCount !== prev) { prev = s.cartCount; cb(prev); } });
  },
  incrementCart(by = 1) {
    store.setState((s) => ({ cartCount: s.cartCount + by }));
  },
};
```

```javascript
// In Module Federation config, share it as a singleton so all MFEs get ONE store:
shared: { '@acme/app-state': { singleton: true, requiredVersion: '^2.0.0' } }
```

MFEs call `appState.getCartCount()` / `subscribeCartCount(...)`; they never read `store.getState().someInternalField`. You can refactor the store's internals freely as long as the facade's contract holds, and a breaking facade change is a major version bump teams adopt on their own schedule.

#### Q88. [Practical] An MFE works in dev but the host can't resolve `import('catalog/ProductList')` in production. Walk through the failure modes.

This dynamic import goes through MF's runtime, so trace it in order:

1. **Remote entry not reachable.** The host's `remotes` URL is wrong for prod (still pointing at `localhost`), or returns 404/CORS. Check the Network tab for `remoteEntry.js`.
2. **Exposed key mismatch.** The remote exposes `'./ProductList'` but you imported `catalog/Product` (typo) — the keys must match exactly, including casing.
3. **`name` mismatch.** The host references the remote as `catalog@...` but the remote's `ModuleFederationPlugin.name` is `catalogApp`. The global the host looks up won't exist.
4. **Shared-scope init order.** You imported a remote *before* the shared scope was initialized (missing the async bootstrap boundary), so negotiation throws.
5. **`publicPath` wrong** so `remoteEntry.js` loads but its chunks 404 (see Q81b).

A fast triage script in the host:

```javascript
try {
  const container = window.catalog;            // does the global exist?
  await __webpack_init_sharing__('default');   // shared scope ready?
  await container.init(__webpack_share_scopes__.default);
  const factory = await container.get('./ProductList'); // exposed key correct?
  console.log('Resolved:', factory());
} catch (e) {
  console.error('MF resolution failed at:', e.message);
}
```

Each line that throws pinpoints which stage broke.

#### Q89. [Practical] Implement a resilient dynamic-remote loader with retry, timeout, and a cached promise so concurrent slots don't double-load.

```javascript
const cache = new Map(); // url -> Promise<container>

function loadScriptOnce(url, { timeout = 5000, retries = 2 } = {}) {
  if (cache.has(url)) return cache.get(url);

  const attempt = (left) =>
    new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = url;
      s.type = 'text/javascript';
      const timer = setTimeout(() => { cleanup(); reject(new Error('timeout')); }, timeout);
      const cleanup = () => { clearTimeout(timer); s.onload = s.onerror = null; };
      s.onload = () => { cleanup(); resolve(); };
      s.onerror = () => { cleanup(); s.remove(); reject(new Error('load error')); };
      document.head.appendChild(s);
    }).catch((err) => {
      if (left > 0) return new Promise((r) => setTimeout(r, 300)).then(() => attempt(left - 1));
      throw err;
    });

  const p = attempt(retries).catch((err) => { cache.delete(url); throw err; }); // don't cache failure
  cache.set(url, p);
  return p;
}

export async function loadRemote(scope, module, url) {
  await loadScriptOnce(url);
  await __webpack_init_sharing__('default');
  const container = window[scope];
  await container.init(__webpack_share_scopes__.default);
  const factory = await container.get(module);
  return factory();
}
```

Caching the *promise* (not just the result) means two slots requesting the same remote concurrently share one in-flight load; deleting the cache entry on failure lets a later attempt retry cleanly.

#### Q90. [Practical] How do you make a remote's TypeScript types available to the host so `import('catalog/ProductList')` is typed?

Federated imports are resolved at runtime, so TypeScript has no idea what `catalog/ProductList` is by default. Three approaches, best first:

1. **Module Federation 2.0 type sharing.** The MF 2.0 plugin (`@module-federation/enhanced`) generates a `@mf-types` bundle from each remote and the host downloads it, giving real types automatically — no manual declarations.
2. **Publish a types-only package** (`@acme/catalog-types`) the host depends on, and re-map the federated module to it:

```typescript
// types/remotes.d.ts in the host
declare module 'catalog/ProductList' {
  import type { ComponentType } from 'react';
  export interface ProductListProps { category: string; limit?: number }
  const ProductList: ComponentType<ProductListProps>;
  export default ProductList;
}
```

3. **Hand-written ambient declarations** (the snippet above without a published package) — fine for a couple of remotes, unscalable for many.

Prefer #1 on a modern stack; it keeps types in lockstep with the actual exposed module instead of drifting like hand-maintained `.d.ts` files.

#### Q91. [Practical] A shared singleton warns "No satisfying version found" at runtime. What does it mean and how do you resolve it?

It means the shared-scope negotiation couldn't find any loaded version of the package that satisfies a consumer's `requiredVersion` range, so MF either loaded a fallback copy or (with `strictVersion`) threw. Typical causes:

- One MFE requires `react@^18` while another provides only `react@17` — no overlap.
- A `requiredVersion` is over-pinned (`18.2.0` exact) and the provided version is `18.3.1`.
- A remote was built without marking the package as `shared`, so it never registered a provider.

Resolution path:

1. **Align ranges.** Move all consumers to a compatible range (`^18`) and ensure at least one build *provides* a satisfying version.
2. **Loosen over-pins** — use caret ranges for singletons unless you truly need an exact pin.
3. **Decide strictness deliberately.** `strictVersion: false` degrades to a fallback copy + warning (app keeps running, possibly with a duplicate); `strictVersion: true` fails fast so you catch it in staging. For singleton-sensitive libs, strict-in-CI + lenient-in-prod fallback is a common compromise.
4. **Provide a migration window** by having the host advertise it can *provide* both N and N-1 of the shared lib during a transition.

#### Q92. [Practical] Show how to lazily load a remote only when it scrolls into view, to keep it off the critical path.

Use `IntersectionObserver` so below-the-fold MFEs don't compete with the initial render.

```javascript
import { useEffect, useRef, useState } from 'react';

export function LazyRemoteSlot({ load, fallback = null, rootMargin = '200px' }) {
  const ref = useRef(null);
  const [Comp, setComp] = useState(null);

  useEffect(() => {
    const el = ref.current;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          io.disconnect();
          // load() returns a promise resolving to a React component.
          load().then((mod) => setComp(() => mod.default ?? mod));
        }
      },
      { rootMargin } // start loading slightly before it's visible
    );
    io.observe(el);
    return () => io.disconnect();
  }, [load, rootMargin]);

  return <div ref={ref}>{Comp ? <Comp /> : fallback}</div>;
}

// Usage:
// <LazyRemoteSlot load={() => import('reviews/Widget')} fallback={<Skeleton />} />
```

The `rootMargin` prefetches just before the slot enters the viewport so the content is ready by the time the user scrolls to it, avoiding a jarring pop-in while still keeping it off the critical path.

#### Q93. [Practical] Two MFEs each register a global keyboard shortcut and they conflict. How do you resolve cross-MFE global concerns like this?

Global, page-level concerns (keyboard shortcuts, focus trapping, scroll locking, toasts, modals that overflow a slot) must be **owned by the shell as a coordinated service**, not registered ad hoc by each MFE on `document`.

Pattern: the shell exposes a shortcut registry that arbitrates conflicts and respects the active/focused MFE.

```javascript
// Shell-owned shortcut service (singleton).
const bindings = new Map(); // 'ctrl+k' -> { owner, handler, scope }

window.__shortcuts = {
  register(combo, owner, handler, { scope = 'global' } = {}) {
    if (bindings.has(combo) && bindings.get(combo).scope === 'global') {
      console.warn(`Shortcut ${combo} already owned by ${bindings.get(combo).owner}`);
      // Policy: first-come wins for global; scoped shortcuts only fire when their MFE is active.
    }
    bindings.set(`${owner}:${combo}`, { owner, combo, handler, scope });
    return () => bindings.delete(`${owner}:${combo}`); // unregister on unmount
  },
};

document.addEventListener('keydown', (e) => {
  const combo = `${e.ctrlKey ? 'ctrl+' : ''}${e.key.toLowerCase()}`;
  for (const b of bindings.values()) {
    if (b.combo === combo && (b.scope === 'global' || b.owner === activeMfe())) {
      e.preventDefault();
      b.handler(e);
    }
  }
});
```

The shell can then apply a policy (first-come-wins, or scoped shortcuts only fire when their MFE is focused) and surface conflicts in dev. The anti-pattern is each MFE blindly calling `document.addEventListener('keydown', ...)` and silently shadowing each other.

#### Q94. [Theory] Your federated app's first load is slow because of a request waterfall: HTML → host JS → remoteEntry → remote chunk. How do you flatten it?

Each arrow is a serial round trip; the goal is to overlap them.

- **Preload the remote entries** the first view needs, so they download in parallel with the host's own JS instead of after it:

```html
<link rel="preload" as="script" href="https://catalog.acme.com/remoteEntry.js" crossorigin>
<link rel="modulepreload" href="https://catalog.acme.com/remoteEntry.js">
```

- **Prefetch likely-next remotes** (`rel="prefetch"`) at low priority during idle time.
- **Use a manifest the host fetches early** (or inline it into the HTML) so the host knows all remote URLs without waiting for separate config round trips.
- **SSR / stream the above-the-fold MFE** so the user sees content before any remote JS executes, then hydrate.
- **HTTP/2-3 multiplexing + a shared CDN origin** so parallel chunk fetches don't pay per-connection cost.
- **Don't over-eager-share** — eager deps bloat the host entry, lengthening the very first arrow.

Measure with the Network waterfall and the `resourceTiming` API; the win is converting a 4-hop serial chain into 2 overlapping waves.

#### Q95. [Practical] How do you set up a local dev environment where you run one MFE locally against the rest deployed in staging?

You want "my MFE local, everyone else's from staging" so you don't have to run the whole org's frontends. Use **environment-driven remote URLs** plus a runtime manifest override.

```javascript
// host webpack.config.js — remotes resolved from a manifest, not hard-coded.
new ModuleFederationPlugin({
  name: 'shell',
  remotes: {
    // Promise-based dynamic remote: read URL from a runtime-injected manifest.
    catalog: `promise new Promise(resolve => {
      const url = window.__MFE_MANIFEST__.catalog; // staging URL by default
      const s = document.createElement('script');
      s.src = url; s.onload = () => resolve(window.catalog);
      document.head.appendChild(s);
    })`,
  },
});
```

```javascript
// During local dev of the catalog MFE, override just that one entry:
window.__MFE_MANIFEST__ = {
  ...stagingManifest,
  catalog: 'http://localhost:3002/remoteEntry.js', // point only catalog at local
};
```

Practical extras: serve your local MFE with permissive CORS for the staging shell origin, and gate the override behind a dev-only query param or localStorage flag so it never leaks to production. This gives a fast inner loop without spinning up every MFE.

### 🟠 — extended

#### Q96. [Practical] A remote deploy went out with a subtle bug and error rates spiked. Production is the *composed* app no single CI tested. Walk through detection-to-recovery.

The MFE superpower is that recovery is a pointer flip, but you only get there if attribution is per-remote.

1. **Detect with per-remote telemetry.** RUM and error tracking must tag every event with the remote name **and version** so the spike is attributable to `cart@2.4.0`, not "the app." Alert on a per-remote error-budget breach.
2. **Confirm causation, not correlation.** Check the deploy timeline: did errors begin within seconds of the `cart` pointer flip? Compare error rate on sessions that loaded `cart@2.4.0` vs. the canary fraction still on `2.3.9`.
3. **Mitigate instantly.** Flip the manifest pointer back to `cart@2.3.9`. Because artifacts are immutable and still on the CDN, this is seconds and needs no rebuild. In-flight sessions on the bad version recover on next navigation.
4. **Contain blast radius retroactively.** Confirm the failure stayed in the cart slot (error boundary) and didn't blank the page; if it did, that's a separate gap to fix.
5. **Post-incident.** Add the missing guardrail: a contract/assembly test that would have caught it, a mandatory canary on pointer flips, and automated rollback on error-budget breach. The systemic lesson: independent deploys mean **the live combination is the only real test**, so production monitoring + instant rollback are first-class, not afterthoughts.

#### Q97. [Practical] How do you wire automated canary + rollback into the pointer-flip deploy so a bad remote is reverted without a human?

```javascript
// Deploy controller invoked after publishing the immutable artifact.
async function promoteWithCanary({ remote, version, prev, bakeMs = 600000 }) {
  // 1. Flip 5% of traffic to the new version via a weighted manifest.
  await setManifestWeights(remote, { [version]: 0.05, [prev]: 0.95 });

  // 2. Watch the per-remote error rate during the bake window.
  const deadline = Date.now() + bakeMs;
  while (Date.now() < deadline) {
    const { errorRate, baseline } = await metrics.remoteErrorRate(remote, version, prev);
    if (errorRate > baseline * 3 && errorRate > 0.02) {
      // 3. Breach: revert weights entirely — instant rollback, no rebuild.
      await setManifestWeights(remote, { [prev]: 1.0 });
      await alert(`Auto-rolled-back ${remote} ${version}: errorRate=${errorRate}`);
      return { rolledBack: true };
    }
    await sleep(30000);
  }

  // 4. Passed bake → full rollout.
  await setManifestWeights(remote, { [version]: 1.0 });
  return { rolledBack: false };
}
```

The control loop works only because **rollback is a pointer (weight) change**, not a build. Attribution must be per remote *and* per version, or you can't tell whether the spike came from this deploy or a neighbor's. Canary-first shrinks blast radius before the full bake.

#### Q98. [Theory] How do you safely roll out a breaking change to a shared cross-MFE event/prop contract that many teams depend on?

You can't flip a breaking contract atomically across independently deployed MFEs, so you **expand-then-contract** (parallel-change):

1. **Expand.** Release a new major of the contract package that supports **both** the old and new shapes — e.g. emit both the legacy `cart:item-added {id}` and the new `cart:item-added:v2 {sku, qty}`, or accept both payload shapes in handlers. Nothing breaks; old consumers keep working.
2. **Migrate.** Each consuming team upgrades to the new shape on their own schedule within the support window (support N and N-1). Track adoption via telemetry on which event version is actually consumed.
3. **Contract.** Once telemetry shows no one uses the old shape, release the next major that **removes** it.

Reinforce with **consumer-driven contract tests** (Pact) so the producer's CI fails if it would break a still-active consumer, and **deprecation logging** that warns when the legacy shape is used. The principle: in a system without lockstep deploys, every breaking change must pass through a backward-compatible intermediate state.

#### Q99. [Practical] Implement a backward-compatible event emitter that supports the old and new payload shapes during a contract migration.

```typescript
// @acme/mfe-contracts v3 — expand phase: emit both shapes, accept both.
type LegacyAdded = { id: string };          // v1/v2 shape
type AddedV3 = { sku: string; qty: number }; // new shape

export function emitItemAdded(p: AddedV3) {
  // New consumers listen here.
  window.dispatchEvent(new CustomEvent('cart:item-added:v3', { detail: p }));
  // Bridge: also emit the legacy event so un-migrated consumers keep working.
  const legacy: LegacyAdded = { id: p.sku };
  window.dispatchEvent(new CustomEvent('cart:item-added', { detail: legacy }));
}

export function onItemAdded(handler: (p: AddedV3) => void) {
  const v3 = (e: Event) => handler((e as CustomEvent<AddedV3>).detail);
  // Adapter for any producer still emitting only the legacy shape.
  const legacy = (e: Event) => {
    const d = (e as CustomEvent<LegacyAdded>).detail;
    handler({ sku: d.id, qty: 1 }); // best-effort upgrade of old payloads
    if (process.env.NODE_ENV !== 'production')
      console.warn('[contracts] received legacy cart:item-added; upgrade producer to v3');
  };
  window.addEventListener('cart:item-added:v3', v3);
  window.addEventListener('cart:item-added', legacy);
  return () => {
    window.removeEventListener('cart:item-added:v3', v3);
    window.removeEventListener('cart:item-added', legacy);
  };
}
```

The bridge keeps both directions working during migration; the deprecation `console.warn` surfaces stragglers; once telemetry shows the legacy path is unused you delete it in the next major.

#### Q100. [Practical] How do you reproduce a production-only MFE bug that depends on a *specific combination* of remote versions?

The bug exists only in an assembled combination, so you must reconstruct that exact combination:

1. **Capture the production manifest at the time of the incident.** Because pointers are versioned, you can read exactly which immutable artifact each remote was serving (`cart@2.4.0`, `catalog@5.1.2`, `shell@8.0.1`).
2. **Pin those versions locally.** Point a local/staging shell's manifest at those exact `remoteEntry.js` URLs (they're still on the CDN — immutable artifacts aren't deleted). Now you're running the precise combination users saw.
3. **Reproduce with the same shared-scope outcome.** Verify the *negotiated* shared versions match prod (a duplicate-React bug only appears with the same negotiation result), using the duplicate-detection script from earlier.
4. **Bisect the combination.** Swap one remote at a time back to the previous version to isolate which remote (or which pairwise interaction) triggers it.
5. **Add a regression assembly test** pinning that combination so CI catches a recurrence.

This is only possible because the platform keeps **immutable, addressable artifacts** — without them, "what exactly was live" is unanswerable.

#### Q101. [Theory] How do you keep accessibility coherent (focus order, landmarks, live regions) when independent MFEs each render part of the page?

Accessibility is a **page-level property** that no single MFE can guarantee, so the shell must own the global a11y contract and MFEs must conform to it:

- **One set of landmarks, owned by the shell.** The shell renders the single `<header>`/`<nav>`/`<main>`/`<footer>` structure; MFEs render *into* `main` and must not each emit their own top-level landmarks (which create duplicate/competing regions for screen readers).
- **Coordinated focus management on navigation.** When the shell swaps the active MFE, *it* moves focus to the new region's heading and announces the route change, rather than each MFE fighting over focus.
- **A shared live-region service.** One `aria-live` region owned by the shell that MFEs post announcements to via the event bus — multiple independent live regions step on each other.
- **Heading hierarchy governance.** Define which heading level each MFE slot starts at so `h1→h2→h3` order stays valid across the composed page.
- **Shared a11y lint/test gates** in every MFE's CI (axe), plus an **assembly-level** axe run on the composed page to catch cross-MFE issues (duplicate landmarks, broken focus order) no single MFE's CI sees.

The recurring theme mirrors performance and routing: cross-cutting page properties need a platform owner; per-MFE testing is necessary but not sufficient.

#### Q102. [Practical] An MFE's modal/tooltip is clipped because it renders inside a slot with `overflow: hidden` (or a Shadow DOM). How do you fix layering across MFEs?

Overlays that must escape their slot need to render at the page root, not inside the MFE's constrained container.

- **Provide a shell-owned portal target.** The shell renders a top-level `<div id="overlay-root">` and exposes it; MFEs portal their overlays there so they're not clipped by a slot's `overflow` or stacking context:

```javascript
import { createPortal } from 'react-dom';
function Modal({ children }) {
  const target = document.getElementById('overlay-root'); // shell-owned, page-level
  return createPortal(children, target);
}
```

- **Govern z-index centrally.** Define z-index *tokens* in the design system (`--z-modal: 1000; --z-toast: 1100`) so MFEs don't engage in an escalating z-index war.
- **For Shadow DOM MFEs**, a portal can't cross the shadow boundary into light DOM directly — expose a slot or have the MFE call a shell-provided overlay API (`window.__overlays.open(node)`) that renders in light DOM at the root.
- **Avoid `overflow: hidden` on slot wrappers** where overlays are expected, or pair it with the portal approach.

The principle: page-level layering is a shared concern; centralize the portal target and the z-index scale rather than letting each MFE improvise.

#### Q103. [Theory] How do you handle shared data fetching so the shell and three MFEs don't each fetch `/me` four times on load?

Independent MFEs each fetching the same `/me` (user, feature flags, cart count) creates a redundant-request storm and inconsistent state. Centralize the fetch and share the result:

- **Shell pre-fetches cross-cutting data once** and exposes it via a shared service/context (`window.__session.getUser()`), so MFEs read it instead of fetching.
- **Request deduplication / shared cache.** If MFEs must fetch independently, route them through a shared client (React Query / SWR instance shared as a singleton, or a small request-coalescing wrapper) so concurrent identical requests collapse into one:

```javascript
const inflight = new Map();
export function dedupedFetch(url, opts) {
  const key = url + JSON.stringify(opts ?? {});
  if (inflight.has(key)) return inflight.get(key);          // coalesce
  const p = fetch(url, opts).then((r) => r.json())
    .finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}
```

- **Hydrate from SSR.** If the page is server-rendered, inline the `/me` payload so no MFE fetches it client-side at all.
- **Invalidate via the bus.** On login/logout the shell broadcasts `auth:changed` so all MFEs refresh, keeping the shared cache coherent.

The shared HTTP cache must be a **singleton** (one React Query client), or each MFE gets its own cache and the dedup benefit evaporates.

### 🔴 — extended

#### Q104. [Theory] You inherited a federated platform where two React majors (17 and 18) are live simultaneously and causing intermittent hook crashes. Design the remediation.

This is the duplicate-singleton problem at org scale: negotiation is failing because consumers span incompatible ranges, so two React instances coexist.

Remediation, sequenced to avoid a big-bang:

1. **Make it visible.** Add the duplicate-detection assembly check (count distinct React versions in the share scope on the real composed page) and per-remote telemetry tagging which React version each remote negotiated. You can't fix drift you can't see.
2. **Set the target singleton range** centrally (e.g. `react@^18`) as platform policy, enforced by a CI gate that rejects any MFE whose `shared` config can't satisfy it.
3. **Provide a migration window.** Have the host advertise it can *provide* both 17 and 18 temporarily so stragglers keep working while they migrate, but bound the window with a deadline.
4. **Migrate remotes incrementally** to React 18, verifying each on the composed page (not just in isolation, where the bug hides).
5. **Contract the window.** Once telemetry shows no remote negotiates 17, drop 17 from the provided set and tighten the gate to reject it.
6. **Prevent recurrence** with org-wide Renovate grouping the shared set, a shared-dependency policy file validated in CI, and a dependency-drift dashboard.

The meta-point: cross-org dependency convergence is a *governance + tooling* problem, solved with automated guardrails and expand/contract migration, not a one-time code fix.

#### Q105. [Practical] How do you protect the host when a remote could be compromised, given remotes execute JavaScript in the host's origin?

A federated remote runs **in the host's origin** with full access to its DOM, cookies, and storage — so a compromised remote is effectively XSS against the whole app. Defenses, layered:

- **Subresource Integrity on dynamically injected remotes** so a tampered `remoteEntry.js` won't execute:

```javascript
function loadRemoteWithSRI(url, integrity) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = url;
    s.integrity = integrity;        // sha384-... pinned per published artifact
    s.crossOrigin = 'anonymous';    // required for SRI on cross-origin scripts
    s.onload = resolve;
    s.onerror = () => reject(new Error('SRI/load failure: ' + url));
    document.head.appendChild(s);
  });
}
```

- **A strict Content-Security-Policy** allow-listing exactly the origins remotes may load from, blocking inline scripts and unexpected connect targets.
- **Build-pipeline provenance** — only artifacts produced by the paved-road CI (signed, integrity recorded in the manifest) are deployable; the manifest carries the SRI hash so the host can verify at load.
- **Strong isolation for untrusted/third-party remotes** — sandboxed iframes (`sandbox`, separate origin) rather than same-origin federation, accepting the UX cost for the security gain.
- **Least privilege at the API tier** — backends validate the user's token, never trusting "the cart MFE said so," so a rogue remote can't escalate beyond the user's own permissions.

The honest caveat: same-origin federation fundamentally trusts the remote's code; SRI + CSP + provenance reduce the supply-chain risk but true isolation of untrusted code still means an iframe/origin boundary.

#### Q106. [Practical] Implement an integrity-verified manifest loader that refuses to mount a remote whose published hash doesn't match.

```javascript
// Manifest is signed/served by the platform and carries an SRI hash per remote.
// { "cart": { "url": ".../cart/2.4.0/remoteEntry.js", "integrity": "sha384-..." } }

async function loadVerifiedRemote(name, manifest) {
  const entry = manifest[name];
  if (!entry) throw new Error(`Unknown remote: ${name}`);

  // 1. Fetch with CORS so we can both verify and let the browser enforce SRI.
  const res = await fetch(entry.url, { mode: 'cors', integrity: entry.integrity });
  if (!res.ok) throw new Error(`Fetch failed for ${name}: ${res.status}`);

  // 2. Defense-in-depth: also verify the hash ourselves before executing.
  const buf = await res.clone().arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-384', buf);
  const b64 = btoa(String.fromCharCode(...new Uint8Array(digest)));
  if (`sha384-${b64}` !== entry.integrity) {
    throw new Error(`Integrity mismatch for ${name} — refusing to mount`);
  }

  // 3. Inject as an SRI-protected script so the browser re-verifies on execution.
  await new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = entry.url;
    s.integrity = entry.integrity;
    s.crossOrigin = 'anonymous';
    s.onload = resolve;
    s.onerror = () => reject(new Error(`Script load failed for ${name}`));
    document.head.appendChild(s);
  });

  return window[name]; // the federated container, now trusted
}
```

The manifest itself must be served over HTTPS from a trusted, signed source; otherwise an attacker who controls the manifest just swaps in a matching malicious hash. SRI protects the *artifact*; manifest signing protects the *pointer*.

#### Q107. [Theory] Leadership wants to merge the platform team into product teams to "move faster." Argue the organizational case for keeping cross-cutting ownership.

This is a Conway's Law argument. Cross-cutting concerns in an MFE platform — the shell/runtime, design system, auth, the contract layer, CI golden paths, observability budgets — have **no natural product-team owner**, so dissolving the platform team doesn't distribute that work, it **orphans** it.

The case to make:

- **Tragedy of the commons.** Shared assets that everyone depends on but no one owns rot: the design system fragments, shared-dep versions drift into duplicate-React incidents, the shell accumulates feature code and becomes the bottleneck MFEs were meant to remove.
- **Autonomy needs guardrails, and guardrails need an owner.** The platform's value is precisely *enabling* product-team autonomy (paved roads, budgets enforced by tooling). Remove the enabler and either every team reinvents it (waste) or no one does (decay).
- **The Inverse Conway Maneuver.** You deliberately structured teams so the architecture is independently deployable. Re-merging recreates the coordination overhead and the architecture will drift back toward a coupled monolith to match.
- **What "faster" really costs.** Short-term you free a few engineers; medium-term you pay in cross-team incidents, inconsistent UX, and re-coupled releases — the exact pains MFEs were adopted to solve.

The constructive counter-offer: keep a *small* platform/enablement team focused on paved roads and governance, measured by **product-team lead time and autonomy**, not by features shipped. If the platform team is too large or gatekeeping, right-size and re-scope it — but don't orphan the commons.

#### Q108. [Practical] Design an assembly/integration test that runs in CI and catches cross-MFE breakage no single team's CI would see.

The gap is that each MFE's CI tests it in isolation; nobody tests the *composed* page. Add an assembly stage:

```javascript
// assembly.test.js — runs in a platform pipeline against pinned remote versions.
import { test, expect } from '@playwright/test';

const MANIFEST = {
  shell: 'https://staging.cdn/shell/canary/remoteEntry.js',
  cart: process.env.CART_URL ?? 'https://staging.cdn/cart/latest/remoteEntry.js',
  reviews: 'https://staging.cdn/reviews/latest/remoteEntry.js',
};

test('composed page mounts all remotes with one React and no console errors', async ({ page }) => {
  const errors = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(e.message));

  await page.addInitScript((m) => (window.__MFE_MANIFEST__ = m), MANIFEST);
  await page.goto('https://staging.shell.acme.com/catalog/123');

  // 1. Every slot actually mounted (not just the shell chrome).
  await expect(page.getByTestId('cart-slot')).toBeVisible();
  await expect(page.getByTestId('reviews-slot')).toBeVisible();

  // 2. Exactly one React in the shared scope (catches duplicate-singleton).
  const reactVersions = await page.evaluate(() =>
    Object.keys(window.__webpack_share_scopes__?.default?.react ?? {})
  );
  expect(reactVersions.length).toBe(1);

  // 3. The cross-MFE event contract works end to end.
  await page.getByTestId('add-to-cart').click();
  await expect(page.getByTestId('cart-badge')).toHaveText('1');

  // 4. No "Invalid hook call" / chunk-load errors surfaced.
  expect(errors.filter((e) => /Invalid hook call|ChunkLoadError/.test(e))).toHaveLength(0);
});
```

Wire it so a remote's PR can run the assembly test with **its** version pinned and everyone else from staging (`CART_URL=<pr-preview>`), giving each team a way to verify the composed app before merging. Pair with consumer-driven contract tests (Pact) for the API/event contracts and per-remote production monitoring for what CI still can't cover.

#### Q109. [Theory] How would you incrementally strangle a legacy AngularJS monolith into micro-frontends without a big-bang rewrite?

Use the **strangler-fig** pattern: stand up a thin new shell in front of the legacy app and migrate one route/capability at a time until the legacy app withers.

1. **Introduce a routing shell** at the edge that owns the URL space. Initially it forwards everything to the legacy AngularJS app (rendered in an iframe or mounted as a single legacy "MFE").
2. **Carve the first vertical slice** along a bounded context (say, `/account/*`) — a route the team fully owns end to end and that has a clean data boundary.
3. **Build that slice as a new MFE** (React/whatever) and point the shell's `/account/*` prefix at it; everything else still goes to legacy. Users see no big-bang change.
4. **Bridge state during coexistence.** Where the new MFE and the legacy app must share session/auth, route it through the shell-owned auth service and a thin event bridge rather than reaching into AngularJS internals.
5. **Repeat route by route**, always keeping the app shippable. Track the shrinking legacy surface as a migration metric.
6. **Delete the legacy app** once the last route is strangled; remove the iframe/adapter.

Risk controls: migrate the **highest-pain or highest-change-rate** slices first for ROI, keep each slice independently deployable so a bad migration rolls back to legacy via a pointer flip, and resist re-coupling by keeping the shell thin. The point of MFEs here is precisely to *enable* incremental migration that a single SPA rewrite can't.

#### Q110. [Practical] Implement a feature-flagged remote-version override so you can dark-launch / A-B a remote version per user segment.

Route the manifest resolution through a flag service so different cohorts get different immutable artifacts of the same remote.

```javascript
// Resolve each remote's URL per request/user using flag evaluation.
async function resolveManifest(user) {
  const base = await fetchBaseManifest(); // current prod pointers
  const overrides = {};

  // Dark launch: only internal users get cart@next; everyone else cart@stable.
  if (await flags.isEnabled('cart-next', { user })) {
    overrides.cart = base.cartCandidates['next']; // immutable next-version URL
  }

  // A/B: deterministic bucketing so a user is sticky to one variant.
  const bucket = hashToBucket(user.id, 100);
  if (bucket < 10) overrides.reviews = base.reviewsCandidates['experiment'];

  return { ...base, ...overrides };
}

// Shell consumes the resolved manifest before mounting any remote.
window.__MFE_MANIFEST__ = await resolveManifest(currentUser);
```

```javascript
// Tag telemetry with the resolved version per remote so A/B metrics are attributable.
analytics.setContext({
  remoteVersions: Object.fromEntries(
    Object.entries(window.__MFE_MANIFEST__).map(([k, v]) => [k, versionFromUrl(v)])
  ),
});
```

Because every version is an immutable artifact already on the CDN, an experiment or dark launch is just *which pointer a cohort resolves to* — no special builds. Deterministic bucketing keeps users sticky to a variant, and tagging telemetry with the resolved version makes the experiment measurable and instantly reversible (drop the flag → everyone snaps back to stable).

#### Q111. [Behavioral] Six months after adopting micro-frontends, velocity hasn't improved and engineers are complaining about complexity. As the lead, how do you respond?

This probes whether you treat architecture as a means, not an identity. A strong answer is diagnostic and unsentimental:

- **Re-examine the original premise.** MFEs pay off only when **independent team delivery** was the bottleneck. Measure: has deploy lead time, deploy frequency, or change-failure rate actually moved? If the real problem was code organization, MFEs added distributed-system tax without the matching benefit — and the honest move may be to **consolidate** some MFEs back toward a modular monolith.
- **Look for boundary misdraws.** Constant cross-team coordination, chatty cross-MFE state, and shared-hotspot churn signal boundaries that don't match team/domain lines. Redraw or merge MFEs that always change together.
- **Check whether the org actually has autonomy.** MFEs without end-to-end team ownership (BFF, pipeline, deploy) are just complexity. If teams still gate through a shared release, you have the costs and none of the autonomy.
- **Audit the platform investment.** Complaints often trace to missing paved roads — no scaffolding, weak observability, manual dependency coordination. The fix may be *more* platform enablement, not abandoning the architecture.
- **Decide with data and say it plainly.** Present the metrics, name what's working and what isn't, and be willing to **partially reverse** — keep MFEs where independent delivery genuinely helps, collapse them where it doesn't.

The signal interviewers want: you measure outcomes (lead time, change-failure rate, developer experience), you're not emotionally attached to the architecture, and you'll reverse a decision when the evidence says so — including admitting MFEs were the wrong call for parts of the system.

## ✅ Key Takeaways

- Micro-frontends solve an **organizational** problem — independent delivery by autonomous teams — not primarily a technical one.
- **Independent deployment** is the core promise; achieve it with run-time (or server-side) integration plus immutable, versioned artifacts referenced by a mutable pointer.
- **Module Federation 2.0** is the modern default for run-time composition across Webpack, Rspack, and Vite, with shared-dependency negotiation and type/manifest support.
- Treat singleton-sensitive libraries (React, routers, design system) as **shared singletons** to avoid duplication and "Invalid hook call" failures.
- Keep the **shell thin**, draw MFE boundaries along **team/domain** lines (Conway's Law), and communicate via loosely coupled events, URLs, and versioned contracts.
- Isolate styles (Shadow DOM / CSS Modules / `@scope`) and engineer for **partial failure** so one MFE degrading never blanks the page.
- At scale, success depends on a **platform team**, automated performance/dependency budgets, and governance — autonomy within guardrails.

## ⚠️ Common Pitfalls

- **Adopting MFEs without the org problem** — one or few teams pay all the cost for none of the autonomy benefit; a modular monolith is usually better.
- **Build-time "MFEs"** that still require a container rebuild to deploy — you get the complexity but not independent deployment.
- **Forgetting `singleton`** on React/state libs, causing duplicate instances and runtime crashes.
- **Uncontrolled dependency drift** — every MFE shipping its own React/lodash/design system, bloating total bytes.
- **A fat shell** that accumulates business logic and becomes the coordination bottleneck — the monolith reborn.
- **No isolation** — global CSS bleed and `window` collisions between MFEs.
- **Untyped, unversioned cross-MFE contracts** that silently break consumers on deploy.
- **Nano-frontends** — making every component an MFE, multiplying run-time and operational overhead.
- **No production monitoring** — since the assembled app is never fully tested by one team's CI, missing per-MFE RUM/error alerting lets bad combinations reach users unseen.

## 📚 Further Reading

- Cam Jackson, "Micro Frontends" — martinfowler.com (the canonical introduction and integration taxonomy).
- Luca Mezzalira, *Building Micro-Frontends*, 2nd ed. (O'Reilly) — architecture decisions, frameworks, DDD boundaries.
- Module Federation documentation and Module Federation 2.0 (module-federation.io) — runtime, manifest, Vite/Rspack support.
- single-spa documentation (single-spa.js.org) — lifecycle contract and route-level orchestration.
- Native Federation & `@module-federation/vite` — import-map-based and Vite-first federation approaches.
- MDN Web Components (Custom Elements, Shadow DOM, Declarative Shadow DOM) and the CSS `@scope` rule.
- micro-frontends.org — patterns, integration approaches, and demos.
