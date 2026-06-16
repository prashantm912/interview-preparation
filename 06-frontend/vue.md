# Vue.js Interview Preparation

A staff-engineer-level guide to Vue.js (focused on Vue 3, current through 2026), covering the reactivity system, Composition vs Options API, state management with Pinia, routing, performance, and the trade-offs that come up against React and Angular.

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

### Q1. [Theory] What is Vue.js and what problem does it solve?

Vue is a progressive JavaScript framework for building user interfaces. "Progressive" means you can adopt it incrementally — drop a `<script>` tag into a legacy page to sprinkle reactivity onto one widget, or scale up to a full single-page application (SPA) with the Vue CLI / Vite, Vue Router, and Pinia. Its core value is **declarative, reactive rendering**: you describe what the DOM should look like as a function of state, and Vue keeps the DOM in sync when state changes, instead of you imperatively poking at `document.getElementById`. Compared to React, Vue ships with first-party answers for routing, state, and tooling, which lowers decision fatigue for teams. The trade-off is a slightly larger conceptual surface (templates, directives, SFCs) versus "just JavaScript."

### Q2. [Theory] What is a Single File Component (SFC) and why is it useful?

An SFC is a `.vue` file that co-locates the template, script, and styles for one component in three blocks: `<template>`, `<script setup>`, and `<style>`. Co-location improves cohesion — everything about a component lives in one place — and `<style scoped>` automatically scopes CSS to that component by adding a data attribute, preventing global style leakage. SFCs are compiled at build time (by Vite or `@vue/compiler-sfc`) into optimized render functions, so there's no runtime template-parsing cost. The build step is the main "cost": you need a bundler, which is why pure CDN usage falls back to in-DOM or string templates.

```vue
<script setup>
import { ref } from 'vue'
const count = ref(0)
</script>

<template>
  <button @click="count++">Clicked {{ count }} times</button>
</template>

<style scoped>
button { font-weight: 600; }
</style>
```

### Q3. [Theory] Explain `ref` vs `reactive`.

Both create reactive state in the Composition API, but they differ in what they wrap. `reactive()` takes an object/array and returns a **deep Proxy**; you access properties directly (`state.count`). `ref()` wraps **any value** (including primitives) in an object with a `.value` property; in `<script>` you read/write `count.value`, but templates auto-unwrap refs so you write just `count`. Rule of thumb: use `ref` for primitives and as the default, and `reactive` for grouped object state. The big gotcha with `reactive` is that destructuring or reassigning it breaks reactivity, because you lose the Proxy reference — `let s = reactive({n:1}); s = {n:2}` drops reactivity. Refs survive destructuring because the reactivity lives inside the `.value` container.

### Q4. [Practical] How do you pass data into a component and emit events back out?

Data flows **down via props** and events flow **up via emits** — this one-way data flow keeps state predictable. Define props and emits explicitly so they're documented and validated:

```vue
<script setup>
const props = defineProps({
  label: { type: String, required: true },
  count: { type: Number, default: 0 }
})
const emit = defineEmits(['increment'])
</script>

<template>
  <button @click="emit('increment', props.count + 1)">{{ label }}</button>
</template>
```

In production I always type/validate props (or use TypeScript generics with `defineProps<{...}>()`), because silent prop-type mismatches are a common source of bugs. For two-way binding I use `v-model`, which is sugar for a `modelValue` prop plus an `update:modelValue` event.

### Q5. [Theory] What are the most common directives?

Directives are special `v-` attributes that apply reactive behavior to the DOM. The core set: `v-if`/`v-else-if`/`v-else` (conditional rendering — element is added/removed from the DOM), `v-show` (toggles CSS `display`, cheaper to toggle but always rendered), `v-for` (list rendering, always pair with a stable `:key`), `v-bind` (`:` shorthand, binds attributes/props), `v-on` (`@` shorthand, event listeners), and `v-model` (two-way binding on form inputs). Use `v-if` when the condition rarely flips and the subtree is expensive; use `v-show` when you toggle frequently. Never use `v-if` and `v-for` on the same element — `v-if` has higher priority in Vue 3 and the intent becomes ambiguous.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Options API vs Composition API — when do you choose each?

The **Options API** organizes a component by *option type*: `data`, `methods`, `computed`, `watch`, lifecycle hooks. It's approachable and self-documenting for small components, but for large components a single logical concern (say, "search") gets scattered across five different options, hurting readability and reuse. The **Composition API** (`setup` / `<script setup>`) organizes code by *logical concern* and lets you extract reusable stateful logic into **composables** (functions like `useSearch()`), which replaced mixins and renderless components as the canonical reuse pattern. Composition also gives dramatically better TypeScript inference. My guidance: default to Composition API with `<script setup>` for new Vue 3 code; keep Options API only in legacy Vue 2 codebases or for trivial components where the ceremony isn't worth it. They interoperate, so migration can be incremental.

```
Options API (by option type)         Composition API (by concern)
┌─────────────────────────┐          ┌─────────────────────────┐
│ data:    { search, ... }│          │ // search concern       │
│ computed:{ results, ... }│   vs     │ const search = ref('')  │
│ methods: { doSearch ... }│          │ const results = computed│
│ watch:   { search ... } │          │ watch(search, doSearch) │
│ // pagination scattered │          │ // pagination concern   │
│ data, computed, methods │          │ const { page } = usePage│
└─────────────────────────┘          └─────────────────────────┘
```

### Q7. [Theory] `computed` vs `watch` — what's the difference and when do you use each?

`computed` derives a **new reactive value** from other reactive sources; it's **cached** and only re-evaluates when a dependency changes, and it must be pure (no side effects). Use it when you need a value (filtered list, formatted total). `watch`/`watchEffect` are for **side effects** in response to state changes — firing an API call, writing to `localStorage`, imperative DOM work. `watch` is explicit about its source and gives you old and new values; `watchEffect` runs immediately and auto-tracks whatever it reads. A frequent anti-pattern is using a `watch` to copy one reactive value into another piece of state — that's almost always a `computed`. Reaching for `watch` to derive values leads to extra renders and synchronization bugs.

```javascript
import { ref, computed, watch } from 'vue'
const price = ref(100), qty = ref(2)

// Derived value → computed (cached, pure)
const total = computed(() => price.value * qty.value)

// Side effect → watch
watch(total, (newTotal) => {
  localStorage.setItem('lastTotal', String(newTotal))
})
```

### Q8. [Coding] Build a reusable `useDebouncedRef` composable.

**Problem:** Create a composable that returns a ref whose writes are debounced by `delay` ms — useful for search inputs to avoid hammering an API on every keystroke.

```javascript
import { customRef } from 'vue'

export function useDebouncedRef(initialValue, delay = 300) {
  let timeout
  return customRef((track, trigger) => ({
    get() {
      track()              // register this read as a dependency
      return value
    },
    set(newValue) {
      clearTimeout(timeout)
      timeout = setTimeout(() => {
        value = newValue
        trigger()          // notify dependents after the debounce window
      }, delay)
    }
  }))
  // hoisted closure variable
  var value = initialValue
}
```

Usage: `const query = useDebouncedRef('')` then `<input v-model="query">`. A `watch(query, fetchResults)` now fires at most once per `delay`.

- **Time complexity:** O(1) per read/write; effectively one trigger per quiet window.
- **Space complexity:** O(1) (one timer handle).
- **Edge cases:** component unmount mid-timeout — wrap with `onScopeDispose(() => clearTimeout(timeout))` to avoid setting state after teardown; ensure `delay` of 0 still defers via `setTimeout` so synchronous storms still collapse.

### Q9. [Practical] Explain slots and how you'd design a flexible card component.

Slots let a parent inject template content into a child's layout — Vue's content-distribution mechanism, analogous to React's `children` / render props. A **default slot** is unnamed; **named slots** (`<slot name="header">`) let consumers target regions; **scoped slots** pass data *back* from child to the slot content, enabling renderless/headless patterns where the child owns logic and the parent owns markup.

```vue
<!-- Card.vue -->
<template>
  <div class="card">
    <header><slot name="header">Default title</slot></header>
    <div class="body"><slot /></div>
    <footer><slot name="footer" :year="year" /></footer>
  </div>
</template>
```

```vue
<!-- usage with a scoped slot -->
<Card>
  <template #header>My Report</template>
  <p>Body content here</p>
  <template #footer="{ year }">© {{ year }}</template>
</Card>
```

In production I lean on scoped slots for table/list components (the list owns sorting/pagination; the caller owns row markup). The trade-off: heavy slot use can make a component's contract harder to reason about, so I document the slot API and provide sensible fallbacks.

### Q10. [Theory] Walk through the Vue 3 component lifecycle.

Lifecycle hooks let you run code at defined moments. The main ones in Composition API form: `onBeforeMount` (before first render), `onMounted` (DOM is available — do DOM measurement, init third-party libs, start fetches that need the element), `onBeforeUpdate`/`onUpdated` (around re-renders triggered by reactive changes), and `onBeforeUnmount`/`onUnmounted` (cleanup — remove listeners, clear timers, cancel requests). There's also `onErrorCaptured` for boundary-style error handling and `onActivated`/`onDeactivated` for components wrapped in `<KeepAlive>`. Critical rule: hooks must be registered **synchronously** during `setup`, not inside an `await` continuation or a callback, because Vue associates them with the currently-active component instance.

```
setup() ─► onBeforeMount ─► [render] ─► onMounted
                                          │
                         (state change)   ▼
            onBeforeUpdate ─► [re-render] ─► onUpdated
                                          │
                         (removed)        ▼
            onBeforeUnmount ─► [teardown] ─► onUnmounted
```

### Q11. [Practical] How does Pinia work and why is it preferred over Vuex?

Pinia is the official state-management library for Vue 3, replacing Vuex. A **store** is defined with `defineStore` and contains `state` (a function returning reactive state), `getters` (cached derived state, like `computed`), and `actions` (methods, which may be async — no separate "mutations" layer). Versus Vuex, Pinia is simpler (no mutations boilerplate), has excellent TypeScript inference, supports multiple stores naturally, is fully tree-shakeable, and works with the Composition API idioms developers already know.

```javascript
import { defineStore } from 'pinia'

export const useCartStore = defineStore('cart', {
  state: () => ({ items: [] }),
  getters: {
    total: (state) => state.items.reduce((s, i) => s + i.price * i.qty, 0)
  },
  actions: {
    async addItem(product) {
      this.items.push({ ...product, qty: 1 })
      await api.persistCart(this.items) // actions can be async
    }
  }
})
```

In components: `const cart = useCartStore()`. Important pitfall: destructuring (`const { total } = cart`) loses reactivity — use `storeToRefs(cart)` for state/getters while calling actions directly off the store.

### Q12. [Practical] How do you set up Vue Router with lazy loading and a navigation guard?

Vue Router maps URLs to components. For real apps I lazy-load route components so each route becomes its own bundle chunk (smaller initial load), and I add guards for auth.

```javascript
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('./views/Home.vue') },
    {
      path: '/dashboard',
      component: () => import('./views/Dashboard.vue'),
      meta: { requiresAuth: true }
    },
    { path: '/:pathMatch(.*)*', component: () => import('./views/NotFound.vue') }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
})
```

**Security note:** client-side guards are UX, not security — they prevent showing a route, but the real authorization must be enforced on the API. Never trust the front end for access control. `createWebHistory` (HTML5 mode) needs a server fallback so deep links return `index.html`.

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] How does Vue 3's reactivity system actually work under the hood?

Vue 3 reactivity is built on ES6 **`Proxy`**. When you call `reactive(obj)`, Vue wraps it in a Proxy with `get`/`set` traps. During rendering (or inside a `computed`/`watchEffect`), an "active effect" is running; the `get` trap calls `track()` to record that *this effect depends on this property* (dependency collection). The `set` trap calls `trigger()`, which re-runs every effect that read that property. `ref` uses the same machinery but via a `.value` getter/setter (class with `get value()`/`set value()`) since you can't proxy a primitive.

```
   read  obj.x  ──► Proxy get trap ──► track(target, 'x', activeEffect)
                                          │ builds dep map:
                                          │ target → { x: Set<effect> }
   write obj.x  ──► Proxy set trap ──► trigger(target, 'x')
                                          └─► re-run effects in that Set
```

Why this matters versus Vue 2's `Object.defineProperty`: Proxies intercept property **addition/deletion** and **array index/length** changes natively, eliminating Vue 2's `Vue.set`/`Vue.delete` workarounds and the inability to detect new keys. The cost: Proxies aren't supported in IE11, which is why Vue 3 dropped IE support. Effects are also batched and flushed asynchronously via a microtask queue, so multiple synchronous mutations cause a single re-render.

### Q14. [Coding] Implement a minimal `reactive` + `effect` to demonstrate the model.

**Problem:** Show the essence of dependency tracking in ~30 lines (interview whiteboard staple).

```javascript
let activeEffect = null
const targetMap = new WeakMap() // target -> (key -> Set<effect>)

function track(target, key) {
  if (!activeEffect) return
  let depsMap = targetMap.get(target)
  if (!depsMap) targetMap.set(target, (depsMap = new Map()))
  let dep = depsMap.get(key)
  if (!dep) depsMap.set(key, (dep = new Set()))
  dep.add(activeEffect)
}

function trigger(target, key) {
  const dep = targetMap.get(target)?.get(key)
  dep?.forEach((eff) => eff())
}

function reactive(target) {
  return new Proxy(target, {
    get(t, key, r) { track(t, key); return Reflect.get(t, key, r) },
    set(t, key, val, r) {
      const result = Reflect.set(t, key, val, r)
      trigger(t, key)
      return result
    }
  })
}

function effect(fn) {
  activeEffect = fn
  fn()                 // run once to collect dependencies
  activeEffect = null
}

// Demo
const state = reactive({ count: 0 })
effect(() => console.log('count is', state.count)) // logs "count is 0"
state.count++                                       // logs "count is 1"
```

- **Time complexity:** `track` O(1) amortized; `trigger` O(d) where d = number of subscribed effects.
- **Space complexity:** O(total tracked key→effect pairs); `WeakMap` lets unreferenced targets be GC'd.
- **Edge cases:** nested effects need an effect stack (not a single global); deep reactivity needs the `get` trap to recursively `reactive()` returned objects; deletions need a `deleteProperty` trap. The real Vue impl handles all of these plus scheduling.

### Q15. [Theory] What rendering optimizations does the Vue 3 compiler apply?

The Vue 3 template compiler does **compile-time analysis** that React's runtime model can't, because Vue templates are statically analyzable. Key techniques: **static hoisting** (nodes that never change are created once, outside the render function), **patch flags** (each dynamic node is tagged with a bitmask describing *what* can change — class, style, text, props — so the diff only checks those, skipping static attributes), **tree flattening / block tree** (dynamic descendants are collected into a flat array so the renderer skips entire static subtrees during diff), and **cached event handlers** (inline handlers are memoized to avoid breaking child memoization). The upshot: Vue's update cost scales with the amount of *dynamic* content, not total node count, which is why Vue often beats a naive React component that re-renders its whole subtree. The trade-off is that these optimizations depend on the template syntax; if you write manual `render()` functions or use `v-html`, you opt out.

### Q16. [Practical] A list of 10,000 rows is janky on update. How do you diagnose and fix it?

**Scenario → approach:** First reproduce and measure with the Vue DevTools timeline and the browser Performance panel — confirm whether the cost is in rendering, reactivity, or layout/paint. Common culprits and fixes:

1. **Missing/index keys in `v-for`** → use a stable unique id key so the diff reuses DOM nodes instead of re-creating them.
2. **Rendering all 10k DOM nodes** → virtualize. Render only the visible window with a virtual scroller (e.g. `vue-virtual-scroller` or TanStack Virtual). This is usually the single biggest win.
3. **Deep reactivity overhead** on large immutable datasets → wrap in `shallowRef`/`shallowReactive` or `markRaw` so Vue doesn't proxy every nested object.
4. **Expensive per-row computeds** recomputing → hoist computation out of the row, memoize, or precompute on data load.
5. **Frequent whole-list replacement** → mutate in place or use `Object.freeze` on rows that never change.

**What I'd actually ship:** virtual scrolling + stable keys + `shallowRef` for the dataset. In one real catalog table this took an update from ~400ms (visible jank) to under 16ms (one frame). I'd add a perf regression test asserting bounded render time.

### Q17. [Theory] Explain `nextTick` and the async update queue.

Vue batches reactive updates: when you mutate state, the affected effects aren't re-run synchronously — they're queued and flushed once in a **microtask** (a `Promise.then`), so changing ten properties in one tick produces one re-render. Consequently, the DOM does **not** reflect a state change on the very next line of synchronous code. `nextTick()` returns a promise that resolves *after* the DOM has been patched, so you use it when you need to read or act on the updated DOM (e.g., measure an element you just revealed, or focus an input that just rendered).

```javascript
import { ref, nextTick } from 'vue'
const show = ref(false)
async function reveal() {
  show.value = true
  // DOM not updated yet here
  await nextTick()
  inputEl.value.focus() // now the element exists
}
```

Understanding this also explains a subtle `watch` behavior: by default watchers run *before* the DOM updates (`flush: 'pre'`); pass `flush: 'post'` to run after the DOM patch.

### Q18. [Practical] How do you handle SSR / hydration concerns in a Vue app?

Server-side rendering (via Nuxt 3, which is the de-facto meta-framework, or `@vue/server-renderer`) renders components to HTML on the server for faster first paint and SEO, then **hydrates** that markup on the client by attaching event listeners and reactivity without re-creating the DOM. The classic failure mode is a **hydration mismatch**: the server-rendered HTML differs from what the client would render (e.g., using `Date.now()`, `window`, random values, or locale-dependent formatting during render). Vue warns and falls back to client rendering for that subtree, hurting performance. Production discipline: keep render output deterministic, guard browser-only code behind `onMounted` or `import.meta.client`, and use `<ClientOnly>` (Nuxt) for inherently client-side widgets. For very large apps, Nuxt's selective/island hydration and `defineAsyncComponent` reduce the JS shipped. Security-wise, never interpolate untrusted data into `v-html` server-side — SSR'd XSS is still XSS.

### Q19. [Theory] Compare Vue 3 with React and Angular — engineering trade-offs.

All three are component-based, but the philosophies differ. **Vue** offers reactive, compiler-optimized templates with batteries included (Router, Pinia, Vite, DevTools all first-party), giving a gentle learning curve and strong defaults — good for teams that want convention over assembling a stack. **React** is a leaner library (UI only) plus a vast ecosystem; its mental model is "re-render on state change and rely on the developer to memoize" (`useMemo`, `useCallback`, or the React Compiler), giving maximum flexibility but more footguns and decision overhead. **Angular** is a full opinionated framework with DI, RxJS, and TypeScript-first design — heavyweight but excellent for large enterprise apps needing strong structure. Performance: Vue's patch-flag/block-tree compiler and React's compiler both reduce wasted work; Vue's fine-grained reactivity historically needs less manual memoization than pre-compiler React. Hiring/ecosystem: React has the largest talent pool. My selection heuristic: React for hiring scale and ecosystem breadth, Angular for large structured enterprise teams, Vue when developer velocity and sane defaults matter most.

```
                Vue 3            React            Angular
View layer      Template+compile JSX (runtime)    Template+compile
Reactivity      Proxy fine-grain Re-render+memo   Zone/Signals
Bundled stack   Router/Pinia     pick-your-own    everything
Learning curve  gentle           moderate         steep
Best fit        velocity/SPA     ecosystem scale  enterprise
```

---

## 🔴 Expert (15+ yrs)

### Q20. [Theory] What changed architecturally from Vue 2 to Vue 3, and how do you run a large migration?

Vue 3 rewrote the core: **Proxy-based reactivity** (replacing `Object.defineProperty`, eliminating `Vue.set` and array-index caveats), a **new compiler** with patch flags/static hoisting/block trees, **tree-shakeable** APIs (e.g. `import { ref }` so unused features drop out — smaller bundles), **fragments** (multiple root nodes), **Teleport**, **Suspense**, and the **Composition API**. The global API changed too: `new Vue()` → `createApp()`, and filters were removed. For a large migration I'd (1) upgrade tooling to Vite and adopt `@vue/compat` (the Vue 2-compatible build) to run the existing app on the v3 runtime with warnings, (2) burn down compat warnings module by module, (3) migrate components incrementally — Options API still works on v3, so you don't need a big-bang rewrite to Composition, (4) replace Vuex with Pinia and update Vue Router to v4, and (5) gate behind a comprehensive test suite and feature flags. The key insight is that v3 was designed to allow incremental migration rather than forcing a rewrite.

### Q21. [Practical] How do you architect shared, testable business logic across a large Vue codebase?

**Approach:** extract logic into **composables** — plain functions using the Composition API that encapsulate state + behavior (`useFeatureFlags`, `usePagination`, `useAuth`). Composables compose (one can call another), are unit-testable without mounting a component (just call the function and assert on the returned refs), and replace the old mixin pattern which suffered from implicit name collisions and unclear data provenance. For cross-cutting dependencies I use `provide`/`inject` with typed injection keys to avoid prop-drilling, and Pinia for genuinely global state. **Trade-offs:** composables can leak side effects if they register lifecycle hooks or watchers without cleanup — I enforce `onScopeDispose`/`effectScope` for teardown and lint against calling composables conditionally (they must run at `setup` top level, same constraint as React hooks). **What I ship in production:** a `composables/` layer with strict input/output contracts, a `stores/` layer for global state, dumb presentational components, and an architectural lint rule (ESLint + custom) preventing components from importing API clients directly. This keeps logic portable and the view layer thin.

### Q22. [Theory] When would you reach for `effectScope`, `shallowRef`, `markRaw`, or `customRef`?

These are the escape hatches that separate fluent Vue engineers from intermediate ones. **`effectScope`** captures a group of reactive effects (watchers, computeds) so you can dispose them together — essential when building composables or libraries that create effects outside a component's lifecycle (e.g., a global store pattern or a detached subscription). **`shallowRef`/`shallowReactive`** make only the top level reactive, skipping deep Proxy creation — critical for large datasets, big third-party objects, or integrating non-Vue state (chart instances, maps) where deep tracking is wasteful and can even break the external library. **`markRaw`** permanently opts an object out of reactivity (e.g., a class instance, a WebSocket, a router instance) so Vue never proxies it. **`customRef`** gives you full control over track/trigger timing — the basis for debounced refs, validated refs, or refs synced to external sources. The meta-lesson: Vue's reactivity is "deep by default" for ergonomics, but at scale you trade some ergonomics for control to keep memory and CPU bounded.

### Q23. [Practical] You're seeing a memory leak in a long-lived Vue SPA. How do you find and fix it?

**Scenario → approach:** A dashboard left open for hours grows heap until it crashes. I take heap snapshots in Chrome DevTools at intervals and diff them to find detached DOM nodes and retained objects. In Vue apps the usual suspects: (1) **event listeners / timers** registered in `onMounted` but never cleaned in `onUnmounted` — `window.addEventListener`, `setInterval`, IntersectionObservers; (2) **watchers/effects created outside component scope** that never stop — fix with `effectScope` or by storing and calling the watcher's stop handle; (3) **global Pinia store accumulating data** (an array you push to forever) — cap it or use a ring buffer; (4) **third-party libraries** (maps, charts) not destroyed — call their `.destroy()` in `onUnmounted` and `markRaw` the instance; (5) closures in `provide` capturing large state. **What I'd actually do:** add an `onUnmounted` cleanup audit, wrap subscriptions in a composable that auto-disposes via `onScopeDispose`, and add a soak test (Playwright running for N minutes, asserting heap stays bounded). The discipline is "every subscription has a matching teardown registered synchronously next to it."

### Q24. [Behavioral] Tell me about a time you had to make a contentious framework or architecture decision on a Vue project.

Use a STAR structure. **Situation:** A team wanted to migrate a stable Vue 2 + Vuex app to React mid-roadmap because "React has more developers." **Task:** As tech lead I had to evaluate objectively rather than defend Vue tribally. **Action:** I ran a time-boxed spike: cost-modeled a full React rewrite (estimated 4–6 months, feature freeze, regression risk) against a Vue 3 + Pinia incremental migration using `@vue/compat` (estimated 6 weeks, no feature freeze, ship continuously). I quantified bundle size, measured render performance on our heaviest screens, and surveyed the team's actual proficiency. I presented data, not opinion, and explicitly listed where React *would* win (hiring pool, ecosystem). **Result:** We chose the incremental Vue 3 migration, shipped it without a feature freeze, and revisited hiring concerns separately. **Reflection:** The lesson I emphasize is separating the *technical* decision from *organizational* anxiety, and that "more popular" is a real but distinct axis from "right for this codebase right now." Owning the trade-off transparently built trust even with the React advocates.

### Q25. [Theory] How do you think about security in a Vue application?

Vue auto-escapes text interpolation (`{{ }}`) and bound attributes, which prevents most reflected XSS by default — that's a meaningful safety property. The danger zones: **`v-html`** renders raw HTML and will execute injected scripts/handlers, so never feed it untrusted input; sanitize with DOMPurify if you must render user HTML. **Dynamic `:href`/`:src`** can carry `javascript:` URIs — validate/allowlist schemes. **SSR** can serialize state into the page; ensure that serialized state is escaped to avoid breaking out of the script context. **Dependency supply chain** matters as much as your code — pin and audit npm dependencies, since a compromised transitive package runs with your app's privileges. And reiterating the router point: client-side guards are not authorization; enforce authz server-side and treat all client state as untrusted. CSP headers add defense-in-depth, though inline-style/scoped-style and some build outputs need a compatible CSP policy. Security is layered: framework escaping is the floor, not the ceiling.

---

## ✅ Key Takeaways

- Default to **Vue 3 + Composition API + `<script setup>`** for new code; Options API is fine for legacy and trivial components and fully interoperates.
- Reactivity is **Proxy-based**: `ref` for primitives/default, `reactive` for object groups — and never destructure a `reactive` (you lose tracking); use `storeToRefs` for Pinia state.
- **`computed` for derived values (cached, pure); `watch`/`watchEffect` for side effects.** Don't use `watch` to copy state.
- The compiler (patch flags, static hoisting, block trees) makes Vue's update cost scale with *dynamic* content — lean on it, and virtualize large lists.
- **Pinia** replaces Vuex (no mutations, great TS, tree-shakeable); **Vue Router** guards are UX, never security.
- Master the escape hatches — `shallowRef`, `markRaw`, `effectScope`, `customRef`, `nextTick` — for performance and integration at scale.
- Every subscription/timer/third-party instance needs a matching **`onUnmounted`/`onScopeDispose`** cleanup.

## ⚠️ Common Pitfalls

- Losing reactivity by destructuring `reactive()` or a Pinia store without `storeToRefs`.
- Using `v-if` and `v-for` on the same element, or using array **index** as `:key` in dynamic lists.
- Forgetting `ref.value` in `<script>` (templates auto-unwrap, so it "works" in template but not in JS).
- Registering lifecycle hooks asynchronously (after `await`) — they silently attach to the wrong/no instance.
- Reading the DOM immediately after a state change instead of awaiting `nextTick()`.
- Feeding untrusted content into `v-html` (XSS), or trusting client-side route guards as authorization.
- Mutating module-level state in `setup` (shared across instances) instead of returning fresh state from `data()`/composable.
- Deep-reactive-wrapping huge datasets or external library instances instead of `shallowRef`/`markRaw`.

## 📚 Further Reading

- **Official Vue 3 Documentation** — vuejs.org (Guide, especially "Reactivity in Depth" and "Rendering Mechanism").
- **Pinia Documentation** — pinia.vuejs.org.
- **Vue Router Documentation** — router.vuejs.org.
- **Nuxt 3 Documentation** — nuxt.com (SSR, hydration, islands).
- *Vue.js 3 By Example* and *Fullstack Vue* — for end-to-end project patterns.
- **Vue 3 Migration Guide** — v3-migration.vuejs.org (Vue 2 → 3 breaking changes and `@vue/compat`).
