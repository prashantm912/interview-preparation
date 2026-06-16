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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q26. [Theory] What is the difference between `v-if` and `v-show`, and what is the real cost of each?

Both control visibility, but they work at different layers and have different cost profiles. `v-if` is **conditional rendering**: when the condition is false the element (and its component subtree, listeners, and reactive effects) is not created at all — it's removed from the virtual DOM and the real DOM. When the condition flips true, Vue mounts the whole subtree fresh (running `onMounted`, re-fetching, re-initializing). `v-show` always renders and mounts the element; it merely toggles the CSS `display` property between `none` and its original value. So the component stays alive, keeps its state, and its lifecycle hooks fire only once.

The practical decision rule: use `v-if` when the condition rarely changes and the subtree is expensive to keep around (a heavyweight modal, a tab you may never open), because you pay nothing while it's hidden. Use `v-show` when you toggle frequently (a tooltip, a dropdown, a hover panel), because re-mounting on every toggle is wasteful and you want to preserve internal state like scroll position or form input.

```
                v-if                        v-show
hidden state    not in DOM at all           in DOM, display:none
toggle cost     mount/unmount (expensive)   CSS flip (cheap)
initial cost    none if false               always renders
preserves state no (fresh mount each time)  yes (stays alive)
best for        rare flips, heavy subtree   frequent toggles
```

A subtle gotcha: `v-show` does not work on `<template>` tags or with `v-else`, because there's no single element to apply `display` to. And because `v-if` is "lazy," an `onMounted` fetch inside a `v-if`-gated component will re-run every time the condition toggles back on — sometimes desired, sometimes a bug.

#### Q27. [Practical] How do you conditionally apply classes and styles, and what are the binding forms?

Vue special-cases `class` and `style` bindings so that `:class` and `:style` accept objects and arrays in addition to strings, and it intelligently merges them with any static `class`/`style` on the same element. This is far cleaner than manual string concatenation and avoids the classic bug of clobbering a static class when you bind a dynamic one.

```vue
<template>
  <!-- object syntax: key is the class, value is the truthy condition -->
  <div :class="{ active: isActive, 'text-danger': hasError }"></div>

  <!-- array syntax: mix static and dynamic -->
  <div :class="[baseClass, isActive ? 'on' : 'off', { disabled }]"></div>

  <!-- static + dynamic merge automatically -->
  <div class="card" :class="{ 'card--selected': selected }"></div>

  <!-- style object with auto-prefixing and unit-aware values -->
  <div :style="{ color: textColor, fontSize: size + 'px' }"></div>
</template>
```

For maintainability I prefer the object syntax for toggles and reserve the array syntax for combining a computed base with conditional modifiers. When the logic gets complex (more than two or three conditions), I move it into a `computed` that returns the class object, keeping the template declarative and the conditional logic unit-testable.

```javascript
const rowClasses = computed(() => ({
  'row--selected': props.selected,
  'row--disabled': props.disabled,
  [`row--${props.status}`]: true   // dynamic key for BEM-style modifiers
}))
```

One production note: avoid binding deeply computed inline `:style` objects on thousands of elements — each is a new object every render and forces a style patch. Prefer CSS classes for static styling and reserve `:style` for genuinely dynamic numeric values (positions, transforms, progress widths).

#### Q28. [Theory] What is `v-model` really, and how do you put it on a custom component?

`v-model` on a native input is syntactic sugar for binding `value` and listening to `input`. On a **custom component** in Vue 3 it desugars to a `modelValue` prop plus an `update:modelValue` event. So `<MyInput v-model="name" />` is equivalent to `<MyInput :modelValue="name" @update:modelValue="name = $event" />`. This is what makes two-way binding composable: the child never mutates the parent's state directly — it requests an update via an event, preserving one-way data flow under the hood.

In Vue 3.4+, the idiomatic way to author this is the `defineModel()` macro, which collapses the prop+emit boilerplate into a single writable ref. It's a huge ergonomic win and what I use in all new code.

```vue
<!-- Vue 3.4+ : defineModel -->
<script setup>
const model = defineModel()         // a ref wired to v-model
// named + multiple models also supported:
const first = defineModel('firstName')
const last  = defineModel('lastName')
</script>

<template>
  <input :value="model" @input="model = $event.target.value" />
</template>
```

```vue
<!-- pre-3.4 : explicit prop + emit -->
<script setup>
const props = defineProps(['modelValue'])
const emit = defineEmits(['update:modelValue'])
</script>
<template>
  <input :value="props.modelValue"
         @input="emit('update:modelValue', $event.target.value)" />
</template>
```

You can have multiple `v-model`s on one component using named models (`v-model:firstName`, `v-model:lastName`), which is great for components like date-range or name-pair inputs. Modifiers (`v-model.trim`, or custom ones) are exposed via `defineModel`'s modifier object, letting you transform the value on the way in or out.

### 🟡 Intermediate — extended

#### Q29. [Theory] What is `provide`/`inject`, and when is it the right tool versus props or a store?

`provide`/`inject` is Vue's **dependency injection** mechanism: an ancestor calls `provide(key, value)` and any descendant — at any depth — calls `inject(key)` to read it, without the value being threaded through every intermediate component as props. It solves **prop drilling**, where a value needed five levels down would otherwise have to be passed (and re-declared) through four components that don't care about it.

The right use cases are cross-cutting concerns scoped to a subtree: a theme, a form context shared between a `<Form>` and its `<Field>` children, an i18n locale, or a configuration object. For genuinely global, app-wide state (auth, cart, user) I reach for **Pinia** instead, because a store is more discoverable, debuggable in DevTools, and testable in isolation. The heuristic: `provide`/`inject` for *implicit subtree context*, Pinia for *global app state*, props for *explicit direct parent-child data*.

```javascript
// ancestor
import { provide, ref, readonly } from 'vue'
const theme = ref('dark')
provide('theme', readonly(theme))          // expose read-only to descendants
provide('setTheme', (t) => (theme.value = t)) // expose a mutator explicitly

// descendant (any depth)
import { inject } from 'vue'
const theme = inject('theme', 'light')     // second arg = default fallback
```

Two production disciplines: (1) use **`InjectionKey<T>`** symbols (TypeScript) instead of string keys to get type safety and avoid collisions across libraries; (2) provide values as **`readonly`** and expose a separate mutator function so descendants can't silently mutate ancestor state — otherwise you lose the "where did this change come from?" traceability that makes DI maintainable. Reactivity flows through `provide`/`inject` as long as you provide a ref/reactive object, not a plain unwrapped value.

#### Q30. [Practical] How do you handle async components and `Suspense`, and what are the failure modes?

`defineAsyncComponent` lets you load a component lazily — Vue only fetches its chunk when it's first rendered, which shrinks the initial bundle. In its expanded form it also accepts `loadingComponent`, `errorComponent`, `delay` (how long before showing the loader, to avoid a flash on fast connections), and `timeout` (after which the error component shows). This is the production-grade way to code-split below the route level — for a heavy chart or editor that only some users open.

```javascript
import { defineAsyncComponent } from 'vue'

const HeavyEditor = defineAsyncComponent({
  loader: () => import('./HeavyEditor.vue'),
  loadingComponent: Spinner,
  errorComponent: LoadFailed,
  delay: 200,        // wait 200ms before showing Spinner (avoid flash)
  timeout: 10000     // show LoadFailed after 10s
})
```

`<Suspense>` is a higher-level coordinator: it lets a subtree containing `async setup()` components (or async dependencies) show fallback content until all of them resolve, then swaps in the real content atomically — avoiding a cascade of individual spinners.

```vue
<Suspense>
  <template #default><UserDashboard /></template>   <!-- has async setup() -->
  <template #fallback><LoadingScreen /></template>
</Suspense>
```

Failure modes I watch for: `<Suspense>` is still marked experimental, so I use it deliberately and pair it with an error boundary (`onErrorCaptured` on a parent) because `<Suspense>` itself does not catch errors from rejected async setups — an unhandled rejection will propagate. Another trap is putting a network fetch in `async setup()` without a timeout, which leaves the fallback up forever if the request hangs. And avoid wrapping the whole app in one `<Suspense>` — that recreates the "blank screen until everything loads" problem SSR/streaming is meant to fix; scope it to meaningful sections.

#### Q31. [Practical] What is `Teleport` and what real problems does it solve?

`<Teleport>` renders a component's markup at a different place in the DOM tree than where the component is declared, while keeping it logically and reactively part of the declaring component. The canonical use cases are **modals, dialogs, toasts, and tooltips** — UI that conceptually belongs to a deeply nested component but must escape `overflow: hidden`, `transform`, or `z-index` stacking contexts created by ancestors. Without Teleport, a modal nested inside a transformed or clipped container gets visually trapped; you'd otherwise resort to fragile global event buses or manual `appendChild` to `document.body`.

```vue
<template>
  <button @click="open = true">Open</button>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop">
      <div class="modal">{{ message }}</div>     <!-- still reactive here -->
    </div>
  </Teleport>
</template>
```

The key insight is that the teleported content is moved in the **real DOM** (it becomes a child of `<body>`), but it remains a child in the **virtual DOM** — so props, slots, reactive state, and `provide`/`inject` all work exactly as if it hadn't moved. Event listeners and reactivity stay intact.

Production notes: the `to` target must exist when the Teleport mounts (in SSR, target `body` or an element rendered earlier). Use `:disabled="true"` to conditionally render in place (e.g., render inline on mobile, teleport on desktop). Multiple Teleports to the same target append in order. And for accessibility, teleporting a modal to `body` is actually the recommended pattern because it makes focus-trapping and `aria` semantics cleaner than a deeply nested dialog.

#### Q32. [Theory] Explain `watchEffect` versus `watch`, including cleanup and flush timing.

`watch` is **explicit**: you name the source(s) to observe, and the callback receives `(newValue, oldValue)` and runs only when those sources change (lazily — not on setup, unless `immediate: true`). `watchEffect` is **implicit**: it runs the function immediately, automatically tracks every reactive value read during that run, and re-runs when any of them change. Use `watch` when you need the old value, want to watch specific sources, or want lazy execution; use `watchEffect` for "run this side effect whenever any of its inputs change" where listing dependencies would be tedious.

Both support a **cleanup callback** (`onCleanup` / `onWatcherCleanup` in 3.5+) that runs before the next invocation and on stop — essential for cancelling in-flight async work to prevent race conditions where a stale response overwrites a newer one.

```javascript
import { watch, watchEffect } from 'vue'

watchEffect((onCleanup) => {
  const controller = new AbortController()
  fetch(`/api/search?q=${query.value}`, { signal: controller.signal })
    .then(r => r.json()).then(d => (results.value = d))
  onCleanup(() => controller.abort())   // cancel previous request
})
```

Flush timing matters: by default both run with `flush: 'pre'` (before the DOM updates), so reading the DOM in the callback gives you stale values. Pass `flush: 'post'` to run after the DOM patch (when you need to measure or interact with updated elements), or `flush: 'sync'` for synchronous firing (rarely needed, can hurt performance). Also note `watch` is shallow on reactive *refs* but deep on a `reactive` object source; for deep watching of a ref-to-object, pass `{ deep: true }` — but deep watchers on large structures are a known performance pitfall, so watch a narrow `computed` instead when you can.

#### Q33. [Coding] Write a `useFetch` composable with loading, error, and cancellation.

**Problem:** Build a reusable data-fetching composable that exposes reactive `data`, `error`, and `loading`, refetches when the URL changes, and cancels the previous request to avoid race conditions.

```javascript
import { ref, watchEffect, toValue, onScopeDispose } from 'vue'

export function useFetch(url) {
  const data = ref(null)
  const error = ref(null)
  const loading = ref(false)

  let controller
  const stop = watchEffect(() => {
    const u = toValue(url)            // accept ref, getter, or plain string
    if (!u) return
    controller?.abort()              // cancel any in-flight request
    controller = new AbortController()
    loading.value = true
    error.value = null
    fetch(u, { signal: controller.signal })
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json()
      })
      .then((json) => (data.value = json))
      .catch((e) => { if (e.name !== 'AbortError') error.value = e })
      .finally(() => (loading.value = false))
  })

  onScopeDispose(() => { controller?.abort(); stop() })
  return { data, error, loading }
}
```

Usage: `const { data, error, loading } = useFetch(() => `/api/users/${id.value}`)`. Because the URL is passed as a getter and read through `toValue`, the `watchEffect` re-tracks it and refetches automatically when `id` changes.

- **Time complexity:** O(1) bookkeeping per fetch; network cost dominates.
- **Space complexity:** O(1) plus the size of the response held in `data`.
- **Edge cases:** the `toValue` helper (3.3+) normalizes refs/getters/raw values so the API is flexible; `AbortError` is swallowed so a deliberate cancel isn't surfaced as an error; `onScopeDispose` guarantees teardown if the owning component unmounts mid-flight, preventing a state write after unmount. In production I'd add retry/backoff and a shared request cache, and consider TanStack Query for Vue if the app needs caching, deduping, and stale-while-revalidate.

#### Q34. [Practical] How do you test Vue components and composables?

The modern stack is **Vitest** (Vite-native test runner, fast HMR-style watch mode) plus **@vue/test-utils** for component mounting and **@testing-library/vue** when you prefer user-centric queries. The most important principle is to test **behavior and the public contract** (props in, rendered output and emitted events out), not internal implementation details like a private ref's name — otherwise tests break on every refactor and provide false confidence.

```javascript
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import Counter from './Counter.vue'

it('emits increment with the next value', async () => {
  const wrapper = mount(Counter, { props: { count: 5 } })
  await wrapper.find('button').trigger('click')   // await flushes reactivity
  expect(wrapper.emitted('increment')[0]).toEqual([6])
})
```

Composables are even easier — since they're plain functions, you often don't need to mount anything; just call them and assert on the returned refs. For composables that use lifecycle hooks (`onMounted`, `onScopeDispose`), wrap them in a throwaway test component or use `effectScope` so the hooks have a context.

```javascript
import { withSetup } from './test-helpers' // mounts a dummy component
it('useCounter increments', () => {
  const [result, app] = withSetup(() => useCounter())
  result.increment()
  expect(result.count.value).toBe(1)
  app.unmount()
})
```

Practical disciplines: always `await` after triggering events or changing props (reactivity is async — flushing requires awaiting `nextTick` or the trigger promise); stub child components with `shallowMount` or the `stubs` option when testing a parent in isolation; mock Pinia with `createTestingPinia()` which lets you set initial state and spy on actions; and run component tests in `jsdom` or `happy-dom` for the DOM environment. For genuinely visual/interaction-heavy flows, complement unit tests with Playwright or Cypress component testing.

#### Q35. [Practical] How do you use TypeScript effectively with Vue 3, especially for props and emits?

Vue 3 with `<script setup>` and `<script setup lang="ts">` gives best-in-class inference. The key macros are **type-only `defineProps` and `defineEmits`**, which let the compiler generate runtime declarations from types — you get full IDE autocomplete and compile-time checks without duplicating the shape.

```vue
<script setup lang="ts">
interface Props {
  label: string
  count?: number
  items: Array<{ id: number; name: string }>
}
// type-based props with defaults via withDefaults
const props = withDefaults(defineProps<Props>(), { count: 0 })

// type-based emits (3.3+ shorthand)
const emit = defineEmits<{
  increment: [value: number]      // tuple = event payload args
  remove: [id: number]
}>()
</script>
```

For **generic components** (a reusable `<List>` whose item type the caller decides), Vue 3.3+ supports `defineProps<T>` with a `generic` attribute, giving full type flow from the bound array to the slot props:

```vue
<script setup lang="ts" generic="T extends { id: number }">
defineProps<{ items: T[] }>()
defineSlots<{ row(props: { item: T }): any }>()
</script>
```

Production tips: type `provide`/`inject` with `InjectionKey<T>` for safe DI; type Pinia stores automatically by using the setup-store syntax or typed state; use `PropType<T>` only if you're stuck on the runtime (object) props API; and enable `vue-tsc` in CI (`vue-tsc --noEmit`) so template type errors fail the build — the regular `tsc` doesn't check `.vue` templates. The biggest win is that typed emits catch the very common bug of emitting the wrong event name or payload shape, which is otherwise a silent runtime failure.

### 🟠 Advanced — extended

#### Q36. [Theory] How does Vue's scheduler batch and flush updates, and why is the queue a microtask?

When a reactive dependency changes, Vue doesn't synchronously re-run the affected effects. Instead it pushes each effect's associated **job** into a scheduler queue (deduplicated by job id, so the same component never queues twice), and schedules a single flush via `Promise.resolve().then()` — a **microtask**. This means all synchronous mutations within the same tick are coalesced: mutating ten properties triggers exactly one component re-render, not ten. This is the foundation of Vue's efficiency and the reason `nextTick()` (which appends to the same microtask) lets you observe the post-update DOM.

```
sync code:  a.x = 1; a.y = 2; a.z = 3
              │ each schedules the same component job (deduped)
              ▼
queue:      [ componentJob ]           ← one entry, not three
              │ flushed in a microtask, after current call stack
              ▼
flush:      run job → patch DOM once → resolve nextTick promises
```

A microtask (rather than `setTimeout`, a macrotask) is chosen because microtasks run **after the current synchronous code but before the browser paints and before any macrotasks/IO** — so the user never sees an intermediate state, and updates land in the same frame without an extra event-loop turn of latency. The queue is also sorted by component id so parents update before children (a child created during the parent's render shouldn't be patched before it exists), and watchers with `flush: 'pre'` run before the render job while `flush: 'post'` jobs run after the DOM patch.

The practical consequence: if you mutate state and immediately read `el.offsetHeight`, you get the stale layout — you must `await nextTick()`. Conversely, this batching is why tight loops of state changes (e.g., updating a progress value 1000 times synchronously) don't cause 1000 renders — but it also means you can't rely on intermediate renders for animation; for that you need `requestAnimationFrame` or CSS transitions, not reactive state ticks.

#### Q37. [Practical] How do you tune a Vite build for a large Vue app heading to production?

Vite is fast in dev because it serves native ESM unbundled, but the **production build uses Rollup**, and that's where you tune. The first lever is **manual chunk splitting**: by default everything from `node_modules` can land in one giant vendor chunk, which busts cache on every dependency change and blocks first paint. I split large, stable libraries into their own chunks so they cache independently.

```javascript
// vite.config.ts
export default defineConfig({
  build: {
    target: 'es2020',
    sourcemap: true,                    // for production error mapping
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          charts: ['echarts'],          // heavy, rarely changing
        }
      }
    },
    chunkSizeWarningLimit: 600
  }
})
```

Beyond chunking: route-level **lazy loading** (`() => import('./View.vue')`) so each route is its own chunk; **`build.cssCodeSplit`** (on by default) so each async chunk's CSS loads with it; tree-shaking verified with `rollup-plugin-visualizer` to find accidental whole-library imports (`import _ from 'lodash'` → import per-function or use `lodash-es`); and gzip/brotli compression at the CDN/server. I also set `define` to inline feature flags and strip dev-only code, and use `import.meta.env` with a `.env.production` for environment config (never commit secrets — only `VITE_`-prefixed vars are exposed to the client, by design).

```bash
# analyze the bundle to find bloat
npx vite build && npx vite-bundle-visualizer
# preview the production build locally before deploying
npx vite preview
```

The discipline I enforce in CI: a bundle-size budget check that fails the build if the main chunk exceeds a threshold, plus `vue-tsc --noEmit` for type safety. The biggest real-world wins are almost always route-level code splitting and pulling a few heavyweight dependencies (chart libs, date libraries, editors) into async chunks so they don't bloat the initial load.

#### Q38. [Theory] What is `v-memo` and how does it differ from `computed` memoization?

`v-memo` is a template directive that **memoizes a subtree of the virtual DOM**: you give it an array of dependencies, and Vue skips re-rendering and re-diffing that subtree entirely as long as every dependency is unchanged (shallow equality) since the last render. It operates at the *render/patch* level, whereas `computed` memoizes a *derived value*. They solve different problems: `computed` avoids recomputing a value; `v-memo` avoids the VDOM diff work for a chunk of template.

```vue
<template>
  <!-- skip re-rendering this row unless id or selected changed -->
  <div v-for="item in list" :key="item.id"
       v-memo="[item.id === selectedId]">
    <ExpensiveCell :item="item" />
    <span>{{ item.id === selectedId ? '✓ selected' : '' }}</span>
  </div>
</template>
```

This is a **niche, advanced optimization** — the docs explicitly say you almost never need it, because Vue's compiler patch flags already make normal updates cheap. The legitimate use case is very large lists (thousands of rows) where each row is moderately expensive to diff and only a tiny fraction change per update; memoizing rows whose dependencies didn't change can cut diff time dramatically. The danger is correctness: if you under-specify the dependency array, the row will *not* update when something it actually depends on changes, producing stale UI — a far worse class of bug than slowness.

My rule: reach for virtualization first (render fewer nodes), and only add `v-memo` when profiling proves the diff of a large *fully-rendered* list is the bottleneck and virtualization isn't applicable. It's the Vue analog of React's `memo` but applied to template subtrees, and like all manual memoization it must be maintained as the template's real dependencies evolve.

#### Q39. [Practical] How do you build a production-grade error-handling strategy in a Vue app?

Vue has several error-capture layers, and a robust app uses all of them. At the **component-boundary** level, `onErrorCaptured(hook)` lets an ancestor catch errors thrown during a descendant's render, lifecycle, or event handlers — the basis for an error-boundary component that shows a fallback UI instead of a white screen. Returning `false` from the hook stops the error from propagating further up.

```vue
<!-- ErrorBoundary.vue -->
<script setup>
import { ref, onErrorCaptured } from 'vue'
const failed = ref(false)
onErrorCaptured((err, instance, info) => {
  failed.value = true
  reportToSentry(err, { info })   // info = lifecycle/render context string
  return false                    // contain the error to this boundary
})
</script>
<template>
  <slot v-if="!failed" />
  <FallbackUI v-else @retry="failed = false" />
</template>
```

At the **app-global** level, `app.config.errorHandler` is the catch-all for anything not handled by a boundary — wire it to your monitoring (Sentry, Datadog). Crucially, neither of these catches errors inside `async` callbacks that aren't awaited in a tracked context, nor unhandled promise rejections — for those you also need `window.addEventListener('unhandledrejection', ...)` and `window.onerror`.

```javascript
app.config.errorHandler = (err, instance, info) => {
  logger.error({ err, info, component: instance?.$options.name })
}
window.addEventListener('unhandledrejection', (e) => logger.error(e.reason))
```

The production architecture I ship: a global handler feeding the monitoring backend, route-level error boundaries around each major view so one broken screen doesn't crash the app, retry affordances on transient failures, and source maps uploaded to the error tracker so stack traces are readable. I also distinguish *expected* errors (a 404 from an API → show a friendly empty state) from *unexpected* ones (a thrown `TypeError` → boundary + alert), because routing all errors through the same crash UI trains users to ignore it and buries real bugs.

#### Q40. [Theory] How does scoped CSS actually work, and what are `:deep`, `:slotted`, and `:global`?

`<style scoped>` doesn't truly isolate styles via a shadow DOM — it works by **attribute injection at compile time**. The compiler adds a unique data attribute (e.g., `data-v-7ba5bd90`) to every element in the component's template, and rewrites every selector in the scoped block to include an attribute selector (`.title[data-v-7ba5bd90]`). So the CSS only matches elements belonging to that component. This is lighter than shadow DOM (styles still cascade normally, no boundary issues with global resets) but it's *encapsulation by convention*, not true isolation.

```css
/* you write */            /* compiles to */
.title { color: red; }     .title[data-v-7ba5bd90] { color: red; }
```

Because of this mechanism, a scoped style **cannot reach into a child component's internals** — the child's elements have a *different* data attribute. To intentionally style a child (or content rendered by a third-party library) you use the **`:deep()`** combinator, which tells the compiler to drop the scoping attribute on the part after it:

```css
.wrapper :deep(.child-internal-class) { color: blue; }
/* compiles to: .wrapper[data-v-x] .child-internal-class { ... } */
```

The companions: **`:slotted(selector)`** targets content passed *into* this component via slots (which by default is scoped to the *parent*, not the child rendering it), and **`:global(selector)`** escapes scoping entirely for a one-off global rule without needing a separate non-scoped block. Production guidance: scoped styles are great for leaf components, but overusing `:deep()` to reach into children is a code smell — it couples you to the child's internal class names, which can break on the child's refactor. For design-system theming I prefer **CSS custom properties** (which pierce scoping naturally because they cascade) over `:deep()`, and reserve `:deep()` for styling third-party components I don't control.

#### Q41. [Practical] How do you persist Pinia state and write a Pinia plugin?

Pinia stores are in-memory and reset on reload, so persistence (to `localStorage`/`sessionStorage`/IndexedDB) is a common requirement — for things like cart contents, UI preferences, or an auth token (though tokens are better in httpOnly cookies for security). The clean way is a **Pinia plugin**, which runs for every store and can extend it or hook into its lifecycle via `store.$subscribe` (fires on any state mutation).

```javascript
// persistence plugin
export function persistPlugin({ store, options }) {
  if (!options.persist) return          // opt-in per store
  const key = `pinia-${store.$id}`
  const saved = localStorage.getItem(key)
  if (saved) store.$patch(JSON.parse(saved))     // hydrate on init
  store.$subscribe((_mutation, state) => {
    localStorage.setItem(key, JSON.stringify(state))  // persist on change
  })
}
// main.ts
const pinia = createPinia()
pinia.use(persistPlugin)
```

```javascript
// store opts in
export const usePrefs = defineStore('prefs', {
  state: () => ({ theme: 'dark', sidebar: true }),
}, { persist: true })   // 3rd arg carries plugin options
```

In practice I use the maintained **`pinia-plugin-persistedstate`** library rather than hand-rolling, because it handles serialization edge cases, partial persistence (pick specific paths), storage choice, and SSR safety. The trade-offs to manage: (1) **`$subscribe` fires on every mutation**, so for hot stores I debounce the write to avoid thrashing storage; (2) **hydration mismatches in SSR** — only persist on the client, guarded by `import.meta.client`, or you'll serialize server state into HTML and mismatch on hydrate; (3) **schema migration** — persisted state from an old app version may not match the new state shape, so I version the stored payload and discard/migrate incompatible data on load; and (4) **never persist secrets** to `localStorage` (XSS-readable) — keep auth tokens in httpOnly cookies and only persist non-sensitive UI state.

#### Q42. [Theory] What are render functions and JSX in Vue, and when are they better than templates?

Templates are Vue's default and compile to render functions — functions that return a virtual DOM tree built with the `h()` (hyperscript) helper. You can also **write render functions directly**, or use **JSX/TSX** (via `@vue/babel-plugin-jsx`). A render function gives you the full power of JavaScript to construct the VDOM, which is occasionally more expressive than template syntax for highly dynamic structures.

```javascript
import { h } from 'vue'
// dynamic heading level — awkward in templates, trivial in a render fn
export default {
  props: { level: Number },
  setup(props, { slots }) {
    return () => h(`h${props.level}`, {}, slots.default?.())
  }
}
```

The legitimate cases for render functions / JSX: (1) **programmatically generated structure** where the tag or shape depends on data (a dynamic heading, a recursive tree renderer, a component that picks among many element types); (2) **higher-order / wrapper components** that manipulate or clone children VNodes; (3) **library code** where template compilation isn't available; and (4) teams coming from React who strongly prefer JSX's "it's just JavaScript" model.

The trade-offs are significant, which is why templates remain the recommendation for app code. Render functions **opt out of the compiler's optimizations** — patch flags, static hoisting, and block-tree flattening are derived from analyzing template syntax, so a hand-written render function diffs more like React (checking everything) unless you manually apply optimization hints. You also lose the readability and tooling (template type-checking via `vue-tsc`, DevTools clarity) that templates provide. My rule: use templates by default for their performance and ergonomics; drop to render functions/JSX only for the genuinely dynamic component-construction cases where templates become contorted, and isolate that complexity to a small number of components.

### 🔴 Expert — extended

#### Q43. [Theory] Compare Vue's reactivity model with Signals (Solid/Angular/Preact). Where does Vue sit?

Vue 3's reactivity is, in essence, a **signals system** — `ref` is a signal (read/write through `.value`), `computed` is a derived signal, and `watchEffect` is an effect that auto-tracks reads. The conceptual lineage is shared: fine-grained dependency tracking where reads subscribe and writes notify, contrasted with React's coarse-grained "re-run the component and diff" model. So Vue and Solid/Angular Signals/Preact Signals are in the same family, and Vue's team explicitly acknowledges this.

The differences are in **granularity of updates**. SolidJS compiles templates so that a signal change updates *only the exact DOM node* bound to it — there is no component re-render and no virtual DOM diff at all. Vue, by contrast, uses signals to determine *which components* are dirty, then re-runs that component's render function and diffs its VDOM (made cheap by patch flags). So Vue's reactivity is fine-grained at the *component* level but still does a (heavily optimized) VDOM diff within a component, whereas Solid is fine-grained at the *DOM-node* level with no diff.

```
                  Vue 3              SolidJS            React
unit of update    component (diff)   DOM node           component (diff)
primitive         ref/reactive       signal             useState
VDOM diff         yes (patch-flagged)none               yes (full subtree)
auto-tracking     yes                yes                no (deps arrays)
re-render on set  re-run component   no re-run           re-run component
```

The practical implication: Solid can edge out Vue on micro-benchmarks because it skips the diff entirely, but Vue's model is a deliberate trade — the VDOM gives portability (custom renderers, SSR, native targets) and a simpler mental model, while patch flags reclaim most of the theoretical loss. Vue also experimented with "Vapor Mode," a compilation strategy that drops the VDOM for Solid-like fine-grained DOM updates while keeping Vue's authoring model — evidence the team sees value in moving further toward signal-fine-grained output where it pays off. The meta-point for an interview: Vue chose signals + VDOM as a pragmatic balance of performance, portability, and developer ergonomics, not because finer granularity is impossible.

#### Q44. [Practical] How do you ship a Vue component as a native Web Component / custom element?

Vue can compile a component into a standards-based **custom element** via `defineCustomElement`, which wraps it so it registers with `customElements.define` and works in any framework or plain HTML — the way to ship a design-system widget consumed by React, Angular, or no framework at all. The wrapped element renders into a **shadow DOM** by default, which gives true style encapsulation (unlike scoped CSS), and props become element attributes/properties while emits become native `CustomEvent`s.

```javascript
import { defineCustomElement } from 'vue'
import MyButton from './MyButton.ce.vue'   // .ce.vue convention

const MyButtonEl = defineCustomElement(MyButton)
customElements.define('my-button', MyButtonEl)
// now usable anywhere: <my-button label="Save"></my-button>
```

The important subtleties that bite teams: (1) **styles must be inlined** — because of the shadow DOM, the component's `<style>` is injected into the shadow root, so you use `.ce.vue` files (or `customElement: true` in the compiler) which inline CSS rather than extracting it; global styles and Tailwind utilities won't pierce the shadow boundary, so design-system tokens must come in via CSS custom properties or be inlined. (2) **Attributes are strings** — HTML attributes are always strings, so passing complex objects requires setting them as DOM *properties* in JS, not attributes; consumers in plain HTML can only pass primitives via attributes. (3) **Events are native `CustomEvent`s**, so React consumers (pre-19) need a ref + `addEventListener` rather than `onMyEvent` props. (4) **provide/inject and app-level plugins** don't automatically span custom-element boundaries, so each custom element is more island-like.

The trade-off analysis I give: custom elements are the right tool for a **framework-agnostic design system or for embedding a Vue widget into a non-Vue host** (a legacy app, a CMS, micro-frontends with mixed stacks). But within a pure Vue app you give up Vue's ergonomics (slots are more limited, DI doesn't flow, tooling is weaker) for encapsulation you don't need — so I keep components as normal SFCs internally and only compile the *published* surface to custom elements at the package boundary.

#### Q45. [Practical] Post-incident: a deploy caused chunk-load failures and stale clients. What happened and how do you prevent it?

**Scenario:** After a deploy, users with the app already open started getting `ChunkLoadError` / `Failed to fetch dynamically imported module`, and some saw blank screens. This is one of the most common Vue/Vite SPA production incidents, and the mechanism is specific: Vite emits content-hashed chunk filenames (e.g., `Dashboard.a1b2c3.js`) and lazy routes fetch those names on demand. When you deploy a new build, the old hashed files may be **purged from the CDN/server**, but a user whose browser already loaded the *old* `index.html` still holds references to the *old* chunk names. When they navigate to a lazy route, the browser requests a chunk that no longer exists → 404 → `ChunkLoadError`.

```
old client (index.html v1) ──navigates──► requests Dashboard.OLDHASH.js
                                                  │ deploy purged it
                                                  ▼
                                            404  →  ChunkLoadError → blank
```

The fixes, layered: (1) **Keep old chunks available** for a grace period — don't hard-purge; let the CDN serve old hashed assets for, say, 24–48h (they're immutable and content-addressed, so caching them forever is safe). This alone resolves most cases. (2) **Catch the error and recover**: add a global handler for `router.onError` (and `vite:preloadError`) that detects a chunk-load failure and triggers a `window.location.reload()` to pull the fresh `index.html` and new chunk map.

```javascript
router.onError((error) => {
  if (/ChunkLoadError|dynamically imported module/.test(error.message)) {
    window.location.reload()   // fetch fresh index.html + new chunks
  }
})
window.addEventListener('vite:preloadError', () => window.location.reload())
```

(3) **Notify long-lived sessions of a new version** — poll a `version.json` (or use a WebSocket/SSE) and prompt the user to refresh when the build hash changes, so dashboards left open for hours don't drift. (4) **Set caching headers correctly**: `index.html` with `no-cache` (always revalidate) so clients pick up the new chunk map quickly, hashed assets with `immutable, max-age=31536000`. The root-cause discipline is treating the SPA as a *distributed cache-coherence problem* between the CDN and N long-lived clients, and the cheapest durable fix is "don't delete old chunks immediately + reload on chunk-load failure."

#### Q46. [Theory] How would you architect micro-frontends with Vue, and what are the trade-offs?

Micro-frontends split a frontend into independently deployable pieces owned by different teams. With Vue the main approaches are **Module Federation** (Webpack 5 / `@module-federation/vite`), **native Web Components** (each MFE compiled via `defineCustomElement`), and **route-level composition** (a shell app lazy-loads team apps). Module Federation lets a host load remote Vue components at runtime and *share* singleton dependencies (one copy of `vue`, `vue-router`, `pinia`) so you don't ship the framework N times.

```
            ┌──────────── Shell (host) ────────────┐
            │  shared singletons: vue, router, pinia │
            │   ┌────────┐  ┌────────┐  ┌────────┐   │
   remotes  │   │ Cart   │  │ Search │  │ Account│   │  ← independently
            │   │ (teamA)│  │ (teamB)│  │ (teamC)│   │     deployed
            │   └────────┘  └────────┘  └────────┘   │
            └────────────────────────────────────────┘
```

The hard problems are not loading the code — it's the **shared state and consistency** concerns. Vue singletons must be deduplicated and **version-aligned** (two copies of Vue means two reactivity systems that can't share refs; a Pinia store instantiated twice means two separate states), so I enforce a shared dependency contract across teams. Cross-MFE communication should go through a **narrow, explicit contract** — custom events, a shared event bus, or URL/query state — not by reaching into each other's Pinia stores, which recreates the tight coupling MFEs are meant to avoid. Styling needs isolation discipline (scoped CSS or shadow DOM) so one team's reset doesn't break another's layout.

The honest trade-off: micro-frontends solve an **organizational** scaling problem (many teams shipping independently) at a real **technical** cost — duplicated tooling, harder end-to-end testing, version-skew bugs, larger total bytes, and complex shared-state coordination. My guidance is to adopt them only when team-independence pain is concrete (multiple teams blocked on one monolith's release train), prefer the simplest viable approach (route-level lazy loading of separately-built apps before reaching for Module Federation), keep the shared surface tiny, and version the contract between shell and remotes explicitly. For a single team, a well-modularized monolith with route-based code splitting gives most of the benefit without the coordination tax.

#### Q47. [Practical] How do you implement and enforce a strong Content Security Policy with a Vue/Vite app?

A Content Security Policy is defense-in-depth against XSS: it tells the browser which sources of scripts, styles, etc., are allowed, so even if an attacker injects a `<script>`, the browser refuses to run it. The friction with Vue/Vite is mostly around **inline styles and scripts**. In dev, Vite injects inline scripts and uses `eval`-based HMR, so dev needs a looser policy than production. In production, Vue's scoped-style mechanism and any `:style` bindings produce inline `style` attributes, which a strict `style-src` would block unless you allow them.

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'nonce-<random-per-request>';
  style-src 'self' 'nonce-<random>';   # or 'unsafe-inline' as a fallback
  img-src 'self' data: https:;
  connect-src 'self' https://api.example.com;
  object-src 'none'; base-uri 'self'; frame-ancestors 'none';
```

The robust approach is a **nonce-based policy**: the server generates a fresh random nonce per response, injects it into the CSP header *and* onto the legitimate `<script nonce>`/`<style nonce>` tags, so only those run. This requires server-side rendering or an edge worker that can rewrite the HTML per request (Nuxt supports this; a static SPA served from a CDN can't easily do per-request nonces, so it often falls back to hashes or `'unsafe-inline'` for styles). Vite has plugins (`vite-plugin-csp`-style) and Nuxt has built-in CSP/nonce support to wire this up.

The practical trade-offs and disciplines: (1) **avoid `'unsafe-eval'`** in production — it's only needed for some dev tooling, and the Vue runtime build (not the full build with the template compiler) doesn't need `eval`, so ship the runtime-only build; (2) **`'unsafe-inline'` for `style-src` is a common pragmatic compromise** because eliminating all inline styles in a Vue app is costly, but `'unsafe-inline'` for `script-src` should never be allowed — that's the one that actually matters for XSS; (3) **report-only mode first** (`Content-Security-Policy-Report-Only` with a reporting endpoint) so you find violations before enforcing and breaking the app; (4) pair CSP with the framework's auto-escaping and DOMPurify on any `v-html` — CSP is the last line of defense, not the first. The key insight is that CSP and Vue's build modes interact, and a static-hosted SPA fundamentally can't do per-request nonces, so the security posture depends partly on whether you SSR.

#### Q48. [Theory] What is `effectScope`, and how do you use it to manage reactivity outside a component?

`effectScope()` creates a container that captures all reactive effects (`watch`, `watchEffect`, `computed`) created inside its `.run()` callback, so they can all be disposed together with a single `.stop()` call. This solves a real problem: inside a component, effects are automatically owned by the component and cleaned up on unmount — but effects created *outside* a component (in a global store, a shared service, a manually-instantiated composable, or a library) have **no owner** and will leak (keep running, keep references alive) unless you manually track and stop each one. `effectScope` is the primitive that gives those effects a lifecycle.

```javascript
import { effectScope, ref, watch, computed } from 'vue'

function createSharedFeature() {
  const scope = effectScope()
  const state = scope.run(() => {
    const count = ref(0)
    const double = computed(() => count.value * 2)   // owned by scope
    watch(count, (n) => console.log('count', n))      // owned by scope
    return { count, double }
  })
  return {
    ...state,
    dispose: () => scope.stop()    // tears down computed + watch together
  }
}
```

This is exactly the machinery Pinia uses internally so that a store's getters and watchers are scoped and disposable, and it's what `onScopeDispose(fn)` registers into — `onScopeDispose` works inside *any* active scope (component or `effectScope`), which is why it's the correct cleanup primitive for composables that must work both in and out of a component.

The advanced patterns: **detached scopes** (`effectScope(true)`) don't nest under a parent scope, useful when you want a long-lived global scope that isn't accidentally torn down by an enclosing component; and **scope nesting**, where stopping a parent scope recursively stops its children. The expert-level use cases are building reactive libraries, implementing a global state pattern without leaks, creating reactive objects that outlive any single component (a shared WebSocket store, a feature-flag service), and writing composables that need deterministic teardown. The mental model: `effectScope` decouples "where effects are created" from "a component's lifecycle," which is essential once your reactivity lives in a service layer rather than the component tree.

#### Q49. [Practical] Production reactivity bug: a `watch` on a `reactive` object isn't firing (or fires too much). How do you diagnose it?

**Scenario:** A watcher on a nested property of a `reactive` object either never fires, fires on unrelated changes, or fires constantly causing a render storm. This is a classic Vue reactivity-debugging situation with several distinct root causes, and the diagnosis is about identifying *which* tracking rule was violated.

The common root causes: (1) **Watching a non-reactive source** — `watch(state.user.name, cb)` passes the *current string value*, not a reactive source, so it never tracks; you must pass a **getter**: `watch(() => state.user.name, cb)`. (2) **Lost reactivity from destructuring** — `const { user } = reactive(state)` (or a Pinia store without `storeToRefs`) detaches `user` from the proxy, so mutations aren't tracked. (3) **Need for `deep`** — `watch(() => state.user, cb)` watches the *reference*; mutating `state.user.name` won't fire it unless you add `{ deep: true }` (and on a directly-passed `reactive` object, deep is implicit, which surprises people the other way). (4) **Over-firing from `deep: true` on a large object** — every nested mutation triggers it; narrow the source to a getter of the exact field. (5) **Mutating a `shallowRef`/`shallowReactive`'s nested value** — only top-level changes are tracked by design.

```javascript
// ❌ never fires: passes a plain string, not a reactive source
watch(state.user.name, cb)
// ✅ getter is reactive and re-tracked each run
watch(() => state.user.name, cb)

// ❌ over-fires on any nested change
watch(() => state.bigObject, cb, { deep: true })
// ✅ watch exactly the field that matters
watch(() => state.bigObject.status, cb)
```

The diagnostic toolkit: enable **`onTrack`/`onTrigger`** options on the watcher (dev-only debug hooks that log exactly which dependency was tracked and which mutation triggered a run) — this immediately reveals whether tracking happened at all and what fired it.

```javascript
watch(source, cb, {
  onTrack(e) { debugger /* what dependency got collected */ },
  onTrigger(e) { debugger /* what mutation fired the watcher */ }
})
```

I also use Vue DevTools' timeline to see render/effect activity and confirm whether the issue is "not firing" vs "firing too much." The structural fix and prevention: prefer `computed` over `watch` for derived values (eliminates a whole class of these bugs), always pass getters as watch sources, use `storeToRefs` for Pinia, reserve `deep: true` for genuinely necessary cases and narrow the source otherwise, and reach for `shallowRef`/`markRaw` deliberately so you know which parts of a structure are tracked. The meta-lesson: most "reactivity isn't working" bugs are really "I handed Vue a value instead of a reactive source, or I broke the proxy chain by destructuring."

#### Q50. [Theory] How do `toRef`, `toRefs`, `toValue`, and `unref` work, and why do they matter for composable contracts?

These helpers exist because Vue's reactivity has two container shapes — refs (`.value`) and reactive proxies (property access) — and composables need to bridge them without losing tracking. **`toRefs(reactiveObj)`** converts every property of a reactive object into a ref that stays *synced* to the source, which is the canonical way to **return reactive state from a composable so the caller can destructure it** without breaking reactivity. **`toRef(obj, 'key')`** does the same for a single property, and (3.3+) also normalizes a value/ref/getter into a ref.

```javascript
function useMouse() {
  const pos = reactive({ x: 0, y: 0 })
  // ...track mousemove into pos...
  return toRefs(pos)   // caller can: const { x, y } = useMouse()  ✅ stays reactive
}
// Without toRefs, destructuring `pos` would copy plain numbers and lose tracking.
```

**`unref(x)`** returns `x.value` if `x` is a ref, otherwise `x` itself — a safe unwrap. **`toValue(x)`** (3.3+) is the more powerful version: it unwraps refs *and* invokes getters, returning the underlying value for refs, getters, or plain values alike. `toValue` is the modern idiom for **composable inputs**: accepting `MaybeRefOrGetter<T>` so a caller can pass a static value, a ref, or a getter, and the composable reads it reactively inside a `watchEffect`.

```javascript
import { toValue, watchEffect } from 'vue'
function useFeature(input) {              // input: value | ref | getter
  watchEffect(() => {
    const val = toValue(input)            // unwraps all three forms, re-tracks
    /* ...use val... */
  })
}
useFeature(5)                  // static
useFeature(someRef)            // ref
useFeature(() => state.x)      // getter — stays reactive
```

Why this matters for **composable API design**: the convention `MaybeRefOrGetter` inputs + `toValue` reads on the inside, and `toRefs`-style ref outputs, makes composables maximally ergonomic — callers can pass whatever they have and destructure the result safely. Getting this wrong is the source of two of the most common composable bugs: inputs that "don't react" because the composable captured a plain value once (fixed by `toValue` inside a tracking scope), and outputs that "lose reactivity on destructure" (fixed by `toRefs`). The deep point is that these helpers are the glue that lets reactive state cross function boundaries cleanly, which is exactly what separates a robust composable library from a brittle one.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q51. [Theory] What are `<component :is>` dynamic components and when do you use them?

The built-in `<component>` element with `:is` lets you render *which* component to use at runtime, decided by a reactive value. `:is` can be bound to a component's registered name (string), an imported component object, or even an HTML tag name. This is Vue's answer to "switch on a type to pick a renderer" without a chain of `v-if`/`v-else-if` blocks, which become unreadable past three or four branches.

```vue
<script setup>
import TextField from './TextField.vue'
import SelectField from './SelectField.vue'
import CheckboxField from './CheckboxField.vue'

const fieldMap = { text: TextField, select: SelectField, checkbox: CheckboxField }
const props = defineProps({ field: Object })   // { type: 'select', ... }
</script>

<template>
  <component :is="fieldMap[field.type]" :config="field" />
</template>
```

The canonical use cases are dynamic form renderers (a JSON schema drives which input components appear), tab interfaces (render the active tab's component), and content/CMS systems where the backend decides which block component to use. The key insight is that `:is` keeps the parent open for extension — adding a new field type is a one-line map entry, not a new `v-if` branch.

Two production notes. First, when a dynamic component is swapped out it is **unmounted and remounted**, losing internal state — if you need to preserve state across swaps (a half-filled tab the user might return to), wrap the `<component>` in `<KeepAlive>`. Second, if `:is` resolves from a string name, that name must be globally registered or available in scope; binding the component object directly (as above) is safer and tree-shakeable because the bundler sees the import.

#### Q52. [Practical] What are event and key modifiers, and how do they reduce boilerplate?

Vue's `v-on` (`@`) supports **modifiers** — suffixes that handle common event-management chores declaratively instead of you writing imperative code in the handler. Event modifiers include `.stop` (`event.stopPropagation()`), `.prevent` (`event.preventDefault()`), `.self` (only fire if the event target is the element itself, not a child), `.once` (fire at most once then auto-remove the listener), `.capture` (use capture phase), and `.passive` (mark the listener passive for scroll performance). They chain, and order matters — `@click.prevent.self` prevents default on all clicks but only runs the handler for self-clicks, whereas `@click.self.prevent` only prevents default for self-clicks.

```vue
<template>
  <!-- submit without a full page reload, no handler boilerplate -->
  <form @submit.prevent="save">
    <!-- close only when clicking the backdrop itself, not the modal inside -->
    <div class="backdrop" @click.self="close">
      <div class="modal">...</div>
    </div>
    <!-- Enter to submit, Escape to cancel, declaratively -->
    <input @keyup.enter="save" @keyup.esc="cancel" />
    <!-- passive improves scroll jank on touch devices -->
    <div class="scroller" @scroll.passive="onScroll">...</div>
  </form>
</template>
```

Key modifiers (`.enter`, `.esc`, `.tab`, `.delete`, `.space`, `.up`/`.down`/`.left`/`.right`) map to keyboard keys, and system modifiers (`.ctrl`, `.alt`, `.shift`, `.meta`, plus the `.exact` modifier to require an exact combination) handle shortcuts. The `.passive` modifier deserves emphasis in production — for high-frequency events like `scroll` and `touchmove`, a passive listener tells the browser you will not call `preventDefault`, so it can scroll without waiting for your handler, which measurably improves scroll smoothness on mobile. The trade-off to teach juniors: modifiers are great for the common cases, but anything conditional ("prevent default only when the form is valid") belongs in the handler, not in modifier soup.

#### Q53. [Theory] How do template refs work in Vue 3, including `useTemplateRef`?

A **template ref** gives you a handle to a real DOM element or a child component instance, for the cases where declarative rendering isn't enough — focusing an input, measuring an element, integrating a non-Vue library that needs a DOM node, or calling a method exposed by a child. In `<script setup>` the classic pattern is to declare a `ref(null)` whose variable name matches the `ref` attribute in the template; Vue assigns the element to `.value` after mount.

```vue
<script setup>
import { ref, onMounted, useTemplateRef } from 'vue'

// classic (3.x): variable name must match the ref="" attribute
const inputEl = ref(null)
onMounted(() => inputEl.value.focus())

// Vue 3.5+: explicit, name-decoupled binding
const dialog = useTemplateRef('dialogEl')
</script>

<template>
  <input ref="inputEl" />
  <dialog ref="dialogEl"></dialog>
</template>
```

Vue 3.5 introduced **`useTemplateRef(key)`**, which decouples the JS variable name from the `ref` attribute string and reads more clearly, especially when the ref name is dynamic. The two critical rules are timing and nullability: a template ref is `null` until the component is mounted, so you read it in `onMounted` or later (in `setup` it does not yet exist), and after the element is conditionally removed (`v-if` false) the ref becomes `null` again — so always guard with optional chaining for conditionally rendered targets.

For refs inside `v-for`, binding `ref` on a repeated element collects the elements into an array (3.5 supports binding a function or ref that receives each element). To expose a *method or value* from a child to a parent via a template ref, the child must call `defineExpose({ ... })` — `<script setup>` is closed by default, so without `defineExpose` the parent's ref to the child sees nothing useful. The design principle is that template refs are an escape hatch: prefer props/events/state, and reach for refs only for genuinely imperative DOM/instance interactions.

### 🟡 Intermediate — extended

#### Q54. [Coding] Build an animated list with `<Transition>` and `<TransitionGroup>`.

**Problem:** Animate a single element entering/leaving, and animate items being added, removed, and reordered in a list, using Vue's built-in transition system.

`<Transition>` wraps a single element (or component) toggled by `v-if`/`v-show`/dynamic component and applies enter/leave CSS classes (`v-enter-from`, `v-enter-active`, `v-enter-to`, and the leave equivalents) at the right moments. `<TransitionGroup>` does the same for a list rendered by `v-for`, and additionally animates **move** transitions (via the `v-move` class and the FLIP technique) when items reorder.

```vue
<script setup>
import { ref } from 'vue'
let nextId = 3
const items = ref([{ id: 1, t: 'A' }, { id: 2, t: 'B' }])
const add = () => items.value.splice(
  Math.floor(Math.random() * (items.value.length + 1)), 0,
  { id: nextId++, t: 'X' }
)
const remove = (id) => (items.value = items.value.filter(i => i.id !== id))
const shuffle = () => items.value.sort(() => Math.random() - 0.5)
</script>

<template>
  <button @click="add">add</button>
  <button @click="shuffle">shuffle</button>

  <TransitionGroup name="list" tag="ul">
    <li v-for="item in items" :key="item.id" @click="remove(item.id)">
      {{ item.t }}
    </li>
  </TransitionGroup>
</template>

<style scoped>
.list-enter-active, .list-leave-active { transition: all 0.4s ease; }
.list-enter-from, .list-leave-to { opacity: 0; transform: translateX(30px); }
/* animate reordering — the magic class for FLIP-based move */
.list-move { transition: transform 0.4s ease; }
/* keep leaving items out of layout flow so others slide smoothly */
.list-leave-active { position: absolute; }
</style>
```

- **Why it works:** Vue measures each item's position before and after the data change and uses a transform to animate from old to new (FLIP: First, Last, Invert, Play). The `:key` must be stable and unique or move animations break — index keys defeat the whole mechanism because Vue can't tell which DOM node moved where.
- **Edge cases:** the `position: absolute` on `.list-leave-active` is the standard trick so a removed item doesn't hold its space and lets remaining items slide into the gap; for enter/leave of a *single* element prefer `<Transition>` with `mode="out-in"` to avoid both elements overlapping during a swap; respect `prefers-reduced-motion` by disabling transitions in a media query for accessibility.
- **Complexity:** O(n) per data change for the layout measurement across n visible items; for very long animated lists, combine with virtualization rather than animating thousands of nodes.

#### Q55. [Practical] What does `<KeepAlive>` do and what are its caching controls?

`<KeepAlive>` is a built-in component that **caches** the instances of components it wraps instead of destroying them when they're toggled away, so when they return their state, scroll position, and DOM are preserved and `onMounted` does not re-run. It's most often paired with dynamic components (tabbed interfaces) and with `<RouterView>` to keep route components alive across navigation. Without it, switching tabs re-creates the component each time, re-running fetches and losing form input — usually the wrong UX.

```vue
<template>
  <!-- only cache these two; everything else is fresh each time -->
  <KeepAlive :include="['SearchTab', 'ResultsTab']" :max="10">
    <component :is="currentTab" />
  </KeepAlive>
</template>
```

The controls are `include`/`exclude` (cache only / never cache, matched against component `name`) and `max` (an LRU cap so the cache can't grow unbounded — the least-recently-used instance is destroyed when the limit is hit). Cached components get two extra lifecycle hooks: **`onActivated`** (fired when re-inserted from cache) and **`onDeactivated`** (fired when cached away rather than unmounted). You use these for work that should pause/resume — stop a polling timer in `onDeactivated`, resume it in `onActivated` — because the component is alive but hidden, so `onUnmounted` never fires to clean up.

The production trade-offs: caching costs memory (every kept instance plus its DOM stays resident), so `max` matters for long sessions, and a kept-alive component holding event listeners or timers it didn't pause in `onDeactivated` keeps doing work invisibly. There's also a correctness subtlety — because `onMounted` doesn't re-run on return, a kept-alive tab won't auto-refresh stale data; if the tab must show fresh data each time it's shown, put the refresh in `onActivated`, not `onMounted`. The decision is essentially a memory-vs-recompute trade, and `<KeepAlive>` is the right call when re-creation is expensive or destroys valuable user state.

#### Q56. [Coding] Write a custom directive `v-click-outside` and explain the directive lifecycle.

**Problem:** Detect clicks outside an element (to close dropdowns, popovers, menus) using a reusable custom directive, with correct setup and teardown.

Custom directives are low-level DOM-access primitives for behavior that can't be expressed with components — focus management, scroll behavior, third-party DOM library integration. A directive is an object of lifecycle hooks (`mounted`, `updated`, `beforeUnmount`, etc.) that each receive the element and a `binding` object with the bound value.

```javascript
// directives/clickOutside.js
export const vClickOutside = {
  mounted(el, binding) {
    el.__handler = (event) => {
      // fire the callback only if the click landed outside this element
      if (!(el === event.target || el.contains(event.target))) {
        binding.value(event)        // binding.value is the handler fn passed in
      }
    }
    // capture phase + a microtask guard avoids the opening click closing it
    document.addEventListener('click', el.__handler, true)
  },
  beforeUnmount(el) {
    document.removeEventListener('click', el.__handler, true)  // critical cleanup
  }
}
```

```vue
<script setup>
import { ref } from 'vue'
import { vClickOutside } from './directives/clickOutside'
const open = ref(false)
</script>

<template>
  <div class="dropdown">
    <button @click="open = !open">Menu</button>
    <ul v-if="open" v-click-outside="() => (open = false)">...</ul>
  </div>
</template>
```

- **Lifecycle:** `created` (before attributes/listeners applied), `beforeMount`, `mounted` (element in DOM — attach listeners here), `beforeUpdate`/`updated` (around the host component's re-render), `beforeUnmount`, `unmounted`. In `<script setup>`, a directive named `vClickOutside` is auto-available as `v-click-outside`.
- **Edge cases:** the `beforeUnmount` removal is mandatory — a directive that adds a `document` listener and never removes it is a textbook memory leak that also keeps firing on a removed element. Storing the handler on `el.__handler` (or a `WeakMap`) is how you keep a reference to remove the exact function later. Using the **capture** phase (`true`) makes the outside-click fire before inner handlers and is more robust against `stopPropagation` inside the tree.
- **When NOT to use a directive:** if the behavior needs its own template or composes state, a composable (`useClickOutside(elRef, cb)`) or component is usually clearer; directives are best for thin, imperative, reusable DOM behaviors.

#### Q57. [Theory] What is attribute fallthrough (`$attrs`), and how do `inheritAttrs` and `defineExpose` fit in?

**Attribute fallthrough** is the rule that any attribute or event listener placed on a component which is *not* declared as a prop or emit automatically lands on the component's single root element. So `<MyButton class="primary" id="save" @focus="...">` passes `class`, `id`, and the `focus` listener straight through to the root `<button>` even though `MyButton` never declared them. This is what makes wrapper components ergonomic — consumers can pass standard HTML attributes (`aria-*`, `data-*`, `title`, event listeners) without the wrapper enumerating every possible one.

The complication arises with **multiple root nodes** or when the attributes should land on a *non-root* element (e.g., a wrapper `<div>` containing the real `<input>`). With multiple roots Vue can't guess where to put them and warns, so you set `inheritAttrs: false` and bind `$attrs` explicitly to the intended target.

```vue
<script setup>
defineOptions({ inheritAttrs: false })   // stop auto-applying to root
const props = defineProps(['label'])
</script>

<template>
  <label>
    {{ label }}
    <!-- forward all non-prop attrs/listeners to the actual input -->
    <input v-bind="$attrs" />
  </label>
</template>
```

`$attrs` contains all fallthrough attributes, class, style, and `onXxx` listeners (in Vue 3 listeners are merged into `$attrs`, unlike Vue 2 where `$listeners` was separate). **`defineExpose`** is the complementary concept on the instance side: because `<script setup>` is sealed, a parent holding a template ref to the child sees an empty proxy unless the child explicitly `defineExpose({ focus, validate })`s a public API. The mental model: fallthrough controls how *DOM attributes* flow into a component, `defineExpose` controls what *instance methods/state* flow out of it — both are about defining a clean, intentional public contract for a reusable component rather than leaking internals or forcing prop enumeration.

#### Q58. [Practical] How do you build internationalization (i18n) into a Vue app?

The standard solution is **Vue I18n** (`vue-i18n@9+` for Vue 3), installed as a plugin that provides a `$t()` translation function and a reactive `locale`. You define message catalogs per locale, interpolate variables and handle pluralization, and switch locale reactively so the whole UI re-renders in the new language. The reactivity is the key benefit over a hand-rolled solution — changing `locale.value` updates every translated string on screen instantly because `$t` reads the reactive locale.

```javascript
import { createI18n } from 'vue-i18n'

const i18n = createI18n({
  legacy: false,                 // use Composition API mode
  locale: 'en',
  fallbackLocale: 'en',
  messages: {
    en: { cart: { items: 'no items | one item | {count} items' } },
    de: { cart: { items: 'keine Artikel | ein Artikel | {count} Artikel' } }
  }
})
app.use(i18n)
```

```vue
<script setup>
import { useI18n } from 'vue-i18n'
const { t, locale } = useI18n()
</script>
<template>
  <p>{{ t('cart.items', { count: n }, n) }}</p>   <!-- pluralized -->
  <select v-model="locale"><option>en</option><option>de</option></select>
</template>
```

Production concerns that separate a real i18n setup from a toy one: (1) **lazy-load locale messages** so you don't ship every language's catalog in the initial bundle — dynamically `import()` the catalog when the user picks a language and call `i18n.global.setLocaleMessage`. (2) **Pluralization and gender** rules differ per language (Slavic languages have multiple plural forms); Vue I18n's pipe syntax and `Intl.PluralRules` integration handle this — never assume English's two-form (one/many) rule. (3) **Date, number, and currency formatting** should use the `Intl` API (`Intl.NumberFormat`, `Intl.DateTimeFormat`) wired through i18n's `n()`/`d()` helpers, not string templates, because formats are locale-specific. (4) **RTL languages** (Arabic, Hebrew) require `dir="rtl"` on the document and logical CSS properties (`margin-inline-start` not `margin-left`). (5) **SSR/SEO** needs the correct `lang`/`hreflang` and locale-aware routing (Nuxt i18n handles localized routes). The deeper lesson is that i18n is not just string lookup — it's formatting, pluralization, direction, and routing, and underestimating that scope is the classic mistake.

#### Q59. [Coding] Implement a `useIntersectionObserver` composable for lazy-loading and infinite scroll.

**Problem:** Wrap the browser `IntersectionObserver` API in a composable that reports when a target element enters the viewport — the foundation for lazy-loading images, infinite scroll, and reveal-on-scroll animations — with automatic cleanup.

```javascript
import { ref, watch, onScopeDispose, toValue } from 'vue'

export function useIntersectionObserver(target, callback, options = {}) {
  const isIntersecting = ref(false)
  let observer = null

  const cleanup = () => { observer?.disconnect(); observer = null }

  // re-create the observer if the target element changes (e.g., v-if remount)
  const stopWatch = watch(
    () => toValue(target),
    (el) => {
      cleanup()
      if (!el) return
      observer = new IntersectionObserver(([entry]) => {
        isIntersecting.value = entry.isIntersecting
        callback?.(entry)
      }, options)
      observer.observe(el)
    },
    { immediate: true, flush: 'post' }   // post: element exists in DOM
  )

  onScopeDispose(() => { cleanup(); stopWatch() })
  return { isIntersecting }
}
```

Usage for infinite scroll — observe a sentinel element at the list's end and fetch the next page when it appears:

```vue
<script setup>
import { ref } from 'vue'
import { useIntersectionObserver } from './useIntersectionObserver'
const sentinel = ref(null)
useIntersectionObserver(sentinel, (entry) => {
  if (entry.isIntersecting) loadNextPage()
}, { rootMargin: '200px' })   // prefetch 200px before it's visible
</script>
<template>
  <ul><li v-for="row in rows" :key="row.id">{{ row.name }}</li></ul>
  <div ref="sentinel" />
</template>
```

- **Why a composable:** the observe/disconnect lifecycle and the "re-observe when the element remounts" logic are exactly the kind of repeated, leak-prone boilerplate that belongs in one tested place. `flush: 'post'` ensures the watcher runs after the DOM patch so the element actually exists.
- **Edge cases:** `IntersectionObserver` is async and won't fire synchronously, so don't assume immediate callbacks; `rootMargin` lets you prefetch before the element is strictly visible (smoother infinite scroll); `disconnect()` in `onScopeDispose` prevents the classic leak of an observer outliving its component. For SSR, `IntersectionObserver` is undefined on the server — guard with `import.meta.client` or only create it in `onMounted`/the watcher (which runs client-side).
- **Complexity:** O(1) per observed element; the browser handles intersection computation off the main thread, which is why this is dramatically cheaper than a `scroll` listener computing `getBoundingClientRect` on every scroll tick.

### 🟠 Advanced — extended

#### Q60. [Theory] How do you author and register a Vue plugin, and what belongs in one?

A Vue **plugin** is an object with an `install(app, options)` method (or a function treated as that method) that you register with `app.use(plugin, options)`. Plugins are the canonical way to add **app-wide functionality** at bootstrap: registering global components/directives, providing values via `app.provide` for app-wide injection, attaching properties to `app.config.globalProperties`, installing other libraries (Router, Pinia, Vue I18n are all plugins), and wiring global error handlers. `app.use` is idempotent — the same plugin installed twice is ignored — which prevents double-registration bugs.

```javascript
// plugins/analytics.js
export default {
  install(app, options) {
    const tracker = createTracker(options.writeKey)
    app.provide('analytics', tracker)            // inject anywhere
    app.config.globalProperties.$track = tracker.track  // Options API access
    app.directive('track', {                     // <button v-track="'cta'">
      mounted(el, binding) {
        el.addEventListener('click', () => tracker.track(binding.value))
      }
    })
  }
}
// main.js
createApp(App).use(analyticsPlugin, { writeKey: 'abc' }).mount('#app')
```

What *belongs* in a plugin versus a composable is a real design question. A plugin is for **install-time, app-singleton concerns** that need access to the `app` instance — global registration, dependency provision, and integrating external systems. A composable is for **per-component reactive logic** consumed where needed. The anti-pattern is overusing `globalProperties` to dump everything onto `this` (Options) or `getCurrentInstance` (Composition), because it creates implicit global dependencies that are hard to test and tree-shake, and it bypasses TypeScript's import-based discoverability. The modern preference is `provide`/`inject` over `globalProperties`, because injected values are explicit, typeable with `InjectionKey`, and scoped to the app rather than monkey-patched onto every instance. My rule: write a plugin when you genuinely need the `app` lifecycle (one-time setup, global wiring); otherwise prefer an explicitly-imported composable or store.

#### Q61. [Practical] How do you offload heavy computation to a Web Worker from a Vue component?

When a component does CPU-heavy work — parsing a large file, running a data transform, image processing, a search index — doing it on the main thread freezes the UI (no rendering, no input, no scrolling) because JavaScript is single-threaded. A **Web Worker** runs the work on a separate thread and posts results back, keeping the UI responsive. Vite has first-class worker support via the `?worker` import suffix or `new Worker(new URL(...), { type: 'module' })`, so you don't need extra bundler config.

```javascript
// heavy.worker.js
self.onmessage = (e) => {
  const result = expensiveTransform(e.data)   // runs off the main thread
  self.postMessage(result)
}
```

```javascript
// useWorker.js — composable that wraps a worker reactively
import { ref, shallowRef, onScopeDispose } from 'vue'
import HeavyWorker from './heavy.worker.js?worker'

export function useHeavyTransform() {
  const result = shallowRef(null)
  const running = ref(false)
  const worker = new HeavyWorker()
  worker.onmessage = (e) => { result.value = e.data; running.value = false }

  function run(payload) {
    running.value = true
    worker.postMessage(payload)
  }
  onScopeDispose(() => worker.terminate())   // kill the thread on unmount
  return { result, running, run }
}
```

The production considerations: (1) workers communicate by **message passing with structured clone**, so data is copied (potentially expensive for large payloads) unless you use **transferable objects** (`ArrayBuffer`, passed as the second arg to `postMessage`) which move ownership with zero copy — essential for big binary data. (2) Workers **cannot touch the DOM or Vue reactivity** — they're pure compute; you marshal plain data in and out, and the composable bridges results back into refs. (3) Use `shallowRef` for worker results since they're typically large plain objects you replace wholesale, not deeply mutate — deep reactivity would waste cycles proxying data you only read. (4) `worker.terminate()` in `onScopeDispose` prevents an orphaned thread leaking. (5) For request/response patterns across many calls, a library like **Comlink** turns the worker into an async-callable proxy, removing the manual `postMessage`/`onmessage` correlation boilerplate. The decision rule: reach for a worker when a synchronous task exceeds ~16ms and would otherwise drop frames; for merely async I/O (network), workers add no value since `fetch` is already non-blocking.

#### Q62. [Coding] Implement optimistic UI updates with rollback in a Pinia action.

**Problem:** A "like" button (or todo toggle) should feel instant — update the UI immediately, send the request, and **roll back** if the server rejects, all while handling concurrent clicks safely.

```javascript
import { defineStore } from 'pinia'

export const useTodosStore = defineStore('todos', {
  state: () => ({ todos: [] }),
  actions: {
    async toggleDone(id) {
      const todo = this.todos.find(t => t.id === id)
      if (!todo) return
      const previous = todo.done            // capture for rollback

      todo.done = !todo.done                // 1. optimistic update (instant UI)
      try {
        const res = await api.patch(`/todos/${id}`, { done: todo.done })
        // 2. reconcile with server truth (server may normalize fields)
        Object.assign(todo, res.data)
      } catch (err) {
        todo.done = previous                // 3. rollback on failure
        this.lastError = 'Could not save — reverted.'
        throw err                           // let the component surface it
      }
    }
  }
})
```

- **Why optimistic:** perceived latency dominates UX; waiting for a round-trip before flipping a checkbox feels broken on slow networks. Optimistic UI trades a small chance of a visible rollback for a consistently snappy feel — the right trade when success is the common case (a like/toggle almost always succeeds).
- **Edge cases:** capturing `previous` *before* mutating is the crux — if you read it after, you've lost the original. For fields the server may transform (timestamps, derived counts), reconcile with the response (`Object.assign`) rather than trusting the optimistic guess. **Concurrent clicks** on the same item can interleave (double-toggle then a failure rolls back to the wrong state) — guard with a per-item in-flight flag or cancel/supersede the prior request, and key the rollback to a request id so a late failure doesn't clobber a newer successful state.
- **Production hardening:** for lists, snapshot the whole affected slice (or use `structuredClone`) so a multi-field rollback is clean; surface a non-blocking toast on rollback so users learn their change didn't persist; and consider a queue (mutate, enqueue sync, retry with backoff) for offline-capable apps. Libraries like TanStack Query for Vue formalize this with `onMutate`/`onError`/`onSettled` optimistic-update hooks, which is what I'd use in a larger app to avoid hand-rolling rollback in every action.

#### Q63. [Theory] What is Vue's Vapor Mode and what problem does it solve?

**Vapor Mode** is an alternative compilation strategy for Vue 3 (stabilizing through 2024–2025, opt-in) that compiles components to direct, fine-grained DOM operations **without a virtual DOM**. In normal Vue, a reactive change re-runs a component's render function to produce a new VDOM tree, which is then diffed (cheaply, thanks to patch flags) against the old tree to compute DOM mutations. Vapor instead compiles the template into code that, on a reactive change, **updates exactly the affected DOM nodes directly** — much like SolidJS — eliminating the VDOM allocation and diff entirely.

```
Normal Vue:   state change → re-run render → new VDOM → diff vs old → patch DOM
Vapor Vue:    state change → run the precise DOM update bound to that signal
```

The benefits are reduced memory (no VDOM trees retained), faster updates (no diff step), and a smaller runtime (Vapor components can ship without the VDOM runtime, shrinking bundles for Vapor-only apps). It targets the scenarios where Vue's VDOM overhead is most visible: very large/complex UIs, highly interactive dashboards, and performance-sensitive embedded widgets. Crucially, Vapor keeps **the same authoring model** — you still write SFCs with `<script setup>`, `ref`/`computed`, and templates — so it's a compile-target switch, not a new API to learn, which is the whole point versus telling people to rewrite in Solid.

The trade-offs and current limits worth stating in an interview: Vapor components and VDOM components can interoperate but with boundaries, and not every feature is supported identically in early versions, so it's adopted selectively (a hot dashboard, a perf-critical embed) rather than wholesale. The strategic context is the broader signals movement — Vue already *had* signals-style reactivity (`ref` is a signal); Vapor closes the remaining gap by making the *output* fine-grained too, getting Solid-like performance while preserving Vue's ergonomics, ecosystem, and the option of VDOM where its portability (custom renderers, SSR) still matters. The meta-lesson: the VDOM was always an implementation detail, and Vapor demonstrates Vue can shed it where it doesn't pay for itself.

#### Q64. [Practical] How do you fetch data in Nuxt 3, and how do `useFetch`/`useAsyncData` avoid double-fetching?

Nuxt 3 is the meta-framework for Vue with SSR, file-based routing, and server routes. Its data-fetching composables — **`useFetch`** (URL-based) and **`useAsyncData`** (arbitrary async function) — are designed around a specific SSR problem: if you fetch in `setup` naively, the request runs **on the server** during SSR *and again on the client* during hydration, doubling load and risking a hydration mismatch. Nuxt's composables solve this by running the fetch on the server, **serializing the result into the HTML payload** (`__NUXT__`), and **rehydrating it on the client** so the client does *not* re-fetch on first load — the data is already there.

```vue
<script setup>
// runs on server during SSR; result is transferred to client, no refetch
const { data, pending, error, refresh } = await useFetch('/api/products', {
  key: 'products',            // dedup key — same key = shared request
  query: { category: cat },   // reactive: refetches when `cat` changes
  lazy: false,                // false = block navigation until resolved
  server: true,               // run during SSR (set false for client-only)
})
</script>
```

The mechanics that matter: (1) the **`key`** deduplicates — two components calling `useFetch` with the same key share one request and one cached payload, preventing N identical fetches. (2) **`pending`/`error`/`refresh`** give you loading and error state and a manual re-run without re-implementing the wrapper. (3) Reactive options (a ref in `query`/`params`) make it **auto-refetch** when inputs change, like a server-aware `watch`. (4) **`lazy: true`** lets navigation proceed and shows the page with `pending` true while data streams in (good for non-critical data); **`server: false`** skips SSR for purely client-side data (a user-specific widget). (5) `useAsyncData(key, () => $fetch(...))` is the lower-level form when you need a custom async function (e.g., combining several calls) rather than a single URL.

The production discipline: use **`$fetch`** (Nuxt's `ofetch`) directly only inside event handlers/actions (where the double-fetch problem doesn't apply), and reserve `useFetch`/`useAsyncData` for *setup-time* data that must be SSR'd and hydrated. The classic bug is calling `$fetch` in `setup` thinking it's the same — it isn't payload-transferred, so it double-fetches and can mismatch. Also pick keys deliberately: auto-generated keys based on call site can collide or fail to dedup across components, so explicit keys are safer for shared data. Server-only secrets must go through **server routes** (`/server/api/*`) so the API key never reaches the client bundle.

#### Q65. [Coding] Build a `useFocusTrap` composable for accessible modals.

**Problem:** When a modal opens, keyboard focus must be trapped inside it (Tab cycles through the modal's focusable elements, not the page behind), focus should move into the modal on open, and return to the trigger on close — core WCAG accessibility for dialogs.

```javascript
import { watch, onScopeDispose, toValue, nextTick } from 'vue'

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),' +
  'select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'

export function useFocusTrap(containerRef, active) {
  let previouslyFocused = null

  const onKeydown = (e) => {
    if (e.key !== 'Tab') return
    const el = toValue(containerRef)
    const items = [...el.querySelectorAll(FOCUSABLE)].filter(i => i.offsetParent)
    if (!items.length) return
    const first = items[0], last = items[items.length - 1]
    // wrap focus at the boundaries
    if (e.shiftKey && document.activeElement === first) {
      last.focus(); e.preventDefault()
    } else if (!e.shiftKey && document.activeElement === last) {
      first.focus(); e.preventDefault()
    }
  }

  watch(() => toValue(active), async (isActive) => {
    const el = toValue(containerRef)
    if (isActive && el) {
      previouslyFocused = document.activeElement       // remember trigger
      await nextTick()
      el.querySelector(FOCUSABLE)?.focus()             // move focus in
      document.addEventListener('keydown', onKeydown)
    } else {
      document.removeEventListener('keydown', onKeydown)
      previouslyFocused?.focus()                       // restore focus out
    }
  }, { immediate: true })

  onScopeDispose(() => document.removeEventListener('keydown', onKeydown))
}
```

- **Why this matters:** keyboard and screen-reader users can otherwise Tab out of a modal into the page behind it, which is disorienting and a common accessibility audit failure. Restoring focus to the trigger on close is equally important — losing focus to `<body>` strands keyboard users.
- **Edge cases:** the `offsetParent` filter excludes hidden elements (a focusable element inside a `display:none` subtree shouldn't be in the cycle); `nextTick` before focusing ensures the modal content is in the DOM; the `onScopeDispose` removal is mandatory to avoid a leaked global keydown listener. For full compliance, also set `aria-modal="true"`, `role="dialog"`, an `aria-labelledby`, and make the background inert (`inert` attribute or `aria-hidden` on siblings) — focus trapping alone isn't the whole accessibility story.
- **Build-vs-buy:** in production I'd use a vetted library (`focus-trap`, or a headless UI kit like Headless UI / Radix Vue) because edge cases (iframes, shadow DOM, dynamically added focusables, the `inert` polyfill) are numerous; this composable shows the core mechanism and is fine for simple cases.

### 🔴 Expert — extended

#### Q66. [Theory] Explain hydration in depth — full hydration, mismatches, and partial/island hydration.

**Hydration** is the process where client-side JavaScript takes over server-rendered static HTML: rather than re-creating the DOM, Vue walks the existing server markup and *attaches* event listeners, reactive bindings, and component instances to it. This is what makes SSR fast for first paint (HTML arrives ready to display) while still becoming interactive. The cost is that the entire component tree's JS must download and execute to hydrate — so a large app can be visually complete but **non-interactive** for a stretch (poor Time-to-Interactive), and you pay to hydrate even static content that will never change.

A **hydration mismatch** occurs when the DOM Vue expects (from re-rendering on the client) differs from the server-rendered DOM. Causes: non-deterministic render (`Date.now()`, `Math.random()`, `Date` formatting that depends on timezone/locale), reading `window`/`localStorage` during render, invalid HTML the browser "fixed" (a `<div>` inside a `<p>`), or server/client state divergence. Vue detects the mismatch, warns in dev, and discards the server DOM for that subtree to re-render on the client — which negates the SSR benefit for that part and can flash. The fixes: keep render pure, defer browser-only reads to `onMounted`/`import.meta.client`, use `<ClientOnly>` for inherently client-side widgets, and ensure valid nesting.

```
Full hydration:     server HTML → ship ALL component JS → hydrate everything
Island hydration:   server HTML → ship JS only for interactive "islands"
                    (static content never hydrates → less JS, faster TTI)
```

**Partial / island hydration** is the architectural response to full hydration's waste: most of a page (header, article body, footer) is static and never needs JS, so why ship and execute hydration code for it? The islands model renders the whole page on the server but only hydrates the genuinely interactive pieces (a cart widget, a search box), shipping far less JS. Nuxt supports this via **server components** (`.server.vue`, rendered on the server and never hydrated) and lazy/visible hydration strategies (Nuxt 3.9+ added `hydrate-on-visible`, `hydrate-on-idle`, `hydrate-on-interaction` for delayed hydration of components). The expert framing: hydration is a spectrum from "hydrate nothing" (static) through "hydrate islands" to "hydrate everything (SPA-after-SSR)," and the right point depends on how interactive the page actually is — content sites lean islands, app-like dashboards lean full hydration. The trend (islands, resumability à la Qwik, Vapor) is all about reducing the hydration tax.

#### Q67. [Practical] Design a resilient real-time store backed by a WebSocket (reconnect, backpressure, cleanup).

**Scenario:** A trading/chat/monitoring dashboard streams updates over a WebSocket into Pinia. The naive version (open a socket, push every message into reactive state) breaks in production: it leaks sockets, hammers reactivity under high message rates, and dies silently on disconnect. A resilient design addresses connection lifecycle, backpressure, and teardown.

```javascript
import { defineStore } from 'pinia'
import { shallowRef, markRaw } from 'vue'

export const useFeedStore = defineStore('feed', () => {
  const rows = shallowRef([])          // shallow: we replace the array wholesale
  let socket = null
  let retries = 0
  let buffer = []                      // batch incoming messages
  let flushTimer = null

  function flush() {                   // coalesce bursts into one update
    if (!buffer.length) return
    rows.value = [...rows.value, ...buffer].slice(-5000)  // cap = backpressure
    buffer = []
  }

  function connect() {
    socket = markRaw(new WebSocket(import.meta.env.VITE_WS_URL))
    socket.onopen = () => { retries = 0 }
    socket.onmessage = (e) => {
      buffer.push(JSON.parse(e.data))
      flushTimer ??= setTimeout(() => { flush(); flushTimer = null }, 50)
    }
    socket.onclose = () => {
      const delay = Math.min(1000 * 2 ** retries++, 30000)   // exp backoff + cap
      setTimeout(connect, delay + Math.random() * 1000)      // jitter
    }
  }
  function disconnect() {
    socket?.close(); clearTimeout(flushTimer); flushTimer = null
  }
  return { rows, connect, disconnect }
})
```

The design decisions and why: (1) **`shallowRef` + wholesale replacement** — at high message rates, deep-reactive per-message mutation would re-proxy and re-track constantly; a shallow ref replaced in batches triggers reactivity once per flush. (2) **Batching/coalescing** with a 50ms timer is **backpressure** — if 500 messages arrive in a frame, you do one reactive update and one render, not 500, which is the single biggest stability win for high-throughput feeds. (3) A **bounded buffer** (`.slice(-5000)`) caps memory — an unbounded array is the classic long-lived-dashboard heap leak. (4) **Exponential backoff with jitter and a cap** on reconnect prevents a thundering-herd reconnect storm when a server restarts and avoids tight reconnect loops. (5) **`markRaw`** on the socket keeps Vue from trying to proxy a non-plain object. (6) **`disconnect()`** must be called from the consuming component's `onUnmounted` (or wrapped so the store cleans up when no subscribers remain) — a socket and its reconnect timer that outlive the page are a leak that also keeps doing network work.

The harder production concerns I'd raise: **message ordering and gap detection** (sequence numbers so you can request a replay after a reconnect), **auth token refresh** for long-lived sockets (re-auth before the token expires, since a socket opened with a now-expired token will be dropped), **visibility-aware throttling** (pause or downsample updates when the tab is hidden via the Page Visibility API to save CPU/battery), and **idempotent application** of messages so a reconnect-replay doesn't double-apply. The meta-point: a real-time store is a small distributed-systems problem — connection reliability, flow control, and consistency — not just "wire `onmessage` to state."

#### Q68. [Coding] Implement a typed, generic `useAsyncState` composable with TypeScript.

**Problem:** Generalize async data handling into a strongly-typed composable that tracks `data`/`error`/`loading`, supports immediate or lazy execution, exposes a re-runnable `execute`, and infers types end to end.

```typescript
import { ref, shallowRef, type Ref } from 'vue'

interface UseAsyncStateOptions<T> {
  immediate?: boolean
  initialData?: T | null
  onError?: (e: unknown) => void
}

interface UseAsyncStateReturn<T, Args extends any[]> {
  data: Ref<T | null>
  error: Ref<unknown>
  loading: Ref<boolean>
  execute: (...args: Args) => Promise<T | undefined>
}

export function useAsyncState<T, Args extends any[] = []>(
  fn: (...args: Args) => Promise<T>,
  options: UseAsyncStateOptions<T> = {}
): UseAsyncStateReturn<T, Args> {
  const { immediate = false, initialData = null, onError } = options
  const data = shallowRef<T | null>(initialData)   // shallow: replace wholesale
  const error = ref<unknown>(null)
  const loading = ref(false)
  let callId = 0

  async function execute(...args: Args): Promise<T | undefined> {
    const id = ++callId                 // guard against stale resolutions
    loading.value = true
    error.value = null
    try {
      const result = await fn(...args)
      if (id === callId) data.value = result    // ignore superseded calls
      return result
    } catch (e) {
      if (id === callId) { error.value = e; onError?.(e) }
    } finally {
      if (id === callId) loading.value = false
    }
  }

  if (immediate) execute(...([] as unknown as Args))
  return { data, error, loading, execute }
}
```

```typescript
// fully inferred: data is Ref<User | null>, execute requires (id: string)
const { data, loading, execute } = useAsyncState(
  (id: string) => api.get<User>(`/users/${id}`)
)
await execute('42')   // type error if you pass a number
```

- **Type design:** the two generics `T` (resolved type) and `Args` (the argument tuple) flow from the passed function's signature, so callers get autocomplete on `execute`'s arguments and a correctly-typed `data` with zero annotations — this is the payoff of typed composables over loosely-typed wrappers.
- **Edge cases:** the **`callId` monotonic guard** is the race-condition fix — if `execute` is called twice and the first resolves after the second (out-of-order network), only the latest call's result is committed, preventing stale data from overwriting fresh data. `shallowRef` for `data` avoids deep-proxying large response objects you only read. `unknown` (not `any`) for `error` forces callers to narrow before use — better type safety than the common `Error` assumption (a rejected promise can throw anything).
- **Production extension:** add `cancel()` (an `AbortController` threaded into `fn`), retry/backoff, and an `onScopeDispose` to abort in-flight work on unmount — at which point you're approaching what VueUse's `useAsyncState` and TanStack Query provide, which is what I'd adopt rather than maintaining this in-house for a large app.

#### Q69. [Theory] What are the strategies for theming and design tokens in Vue, and how do CSS variables interact with scoped styles and SSR?

Theming (light/dark, brand variants, density) is best built on **CSS custom properties (variables)** rather than JS-driven conditional classes, for one decisive reason: CSS variables **cascade and pierce Vue's scoped-style boundary naturally**, because scoping rewrites *selectors*, not the variable resolution, which happens at the CSS engine level via the cascade. So a `--color-primary` defined on `:root` (or a theme class on `<html>`) is readable by every scoped component's styles without `:deep()`, making variables the cleanest design-token transport across a component tree.

```css
/* tokens.css — single source of truth */
:root { --bg: #fff; --fg: #111; --accent: #2563eb; --space: 8px; }
:root[data-theme='dark'] { --bg: #0b0b0b; --fg: #eee; --accent: #60a5fa; }
```

```vue
<style scoped>
.card { background: var(--bg); color: var(--fg); padding: calc(var(--space) * 2); }
/* compiles to .card[data-v-x] but var() still resolves from :root cascade */
</style>
```

Vue adds a sharper tool for *component-local, reactive* theming: the **`v-bind()` in `<style>`** feature, which lets a scoped style reference a component's reactive JS state, compiled into an inline CSS variable that updates when the state changes — bridging reactive JS into CSS without a `:style` binding on every element.

```vue
<script setup>
const accent = ref('#2563eb')
</script>
<template><button class="cta">Buy</button></template>
<style scoped>
.cta { background: v-bind(accent); }   /* reactive: changes when accent changes */
</style>
```

The architecture and trade-offs: (1) **switching themes** is then just setting `data-theme` on `<html>` (one attribute) — instant, no component re-render needed, since the variables re-resolve in CSS. (2) For **SSR**, you must render the initial theme attribute on the server from a cookie/header (not from `localStorage`, which is client-only) to avoid a flash of the wrong theme (FOUC) on first paint and a hydration mismatch — the classic dark-mode flash bug. A common pattern is an inline `<script>` in `<head>` that sets `data-theme` before first paint based on a cookie and `prefers-color-scheme`. (3) **Design tokens** (spacing, radii, typography scale) live as variables in one file so designers and engineers share a contract, and component libraries consume tokens rather than hard-coded values, enabling whole-system re-theming. (4) Avoid the anti-pattern of toggling many conditional classes in JS for theming — it bloats templates, causes re-renders, and fragments the token source of truth; CSS variables centralize it. The expert nuance is recognizing that theming is fundamentally a *CSS-cascade* problem that Vue's scoping deliberately doesn't break, with `v-bind()` available for the narrow cases where a token genuinely derives from reactive component state.

#### Q70. [Practical] How do you profile and optimize the runtime performance of a slow Vue page beyond list virtualization?

**Scenario:** A page feels sluggish — slow interactions, dropped frames during typing or scrolling — but it isn't a giant list, so virtualization isn't the answer. The disciplined approach is **measure first, in this order**: the Vue DevTools **Performance/Timeline** to see component render frequency and duration, the Chrome **Performance** panel to attribute time to scripting vs. layout vs. paint, and the **Components** tab to spot components re-rendering more than expected. Optimizing without measuring usually "fixes" the wrong thing.

The common runtime culprits and their fixes, roughly in order of frequency:

```
Symptom                          Likely cause                  Fix
─────────────────────────────────────────────────────────────────────────
typing lags                      sync expensive computed/watch debounce; move work
                                 on every keystroke            off the hot path
unrelated children re-render     parent passes new inline      hoist objects; v-memo;
                                 objects/handlers each render  split component
whole list re-renders on 1 edit  unstable :key / replacing     stable id key; mutate
                                 the array reference           in place
slow scroll                      heavy work in scroll handler  IntersectionObserver;
                                 + non-passive listener        @scroll.passive
deep watcher fires constantly    deep:true on big object       watch a narrow getter
janky animation                  animating layout props        animate transform/opacity
                                 (top/left/width)              (GPU-composited)
```

The Vue-specific levers beyond the generic ones: (1) **prefer `computed` over `watch`** for derived values — caching means it recomputes only when dependencies change, whereas a misused `watch` can fire and do work on every related mutation. (2) **`shallowRef`/`shallowReactive`/`markRaw`** for large or external data (chart instances, big read-only datasets) so Vue isn't proxying and tracking thousands of nested properties you never mutate reactively — deep reactivity overhead is a frequently-missed cause of slowness. (3) **`v-once`** for content that renders once and never updates (a static legal footer interpolated from config), and **`v-memo`** for expensive subtrees with narrow dependencies. (4) **Split large components** — a single huge component re-renders entirely on any of its reactive deps; breaking it into smaller components localizes re-renders to the part that actually changed (Vue's reactivity is per-component). (5) **Debounce/throttle** high-frequency inputs and use `flush: 'post'` watchers only when DOM measurement is genuinely needed.

The meta-discipline I bring: establish a **performance budget** (interaction-to-next-paint target, e.g. <200ms) and a **regression guard** (a Playwright/Lighthouse-CI check that fails the build if a key interaction exceeds the budget), because performance silently regresses as features accrete. And I distinguish *render* cost (Vue's job — the levers above) from *layout/paint* cost (CSS's job — avoid layout thrash, animate compositor-friendly properties, reduce paint areas) and from *script* cost (offload to a Web Worker, code-split), because the fix is completely different for each and the Performance panel's flame chart tells you which one you're paying for.

#### Q71. [Behavioral] Describe a time you led the adoption of a new pattern or standard across multiple Vue teams.

**Situation:** Across four product teams sharing a Vue 3 monorepo, every team had reinvented data fetching — bespoke `try/catch`/loading flags in components, inconsistent error handling, no request cancellation, and three different ways to cache. Bugs from race conditions (stale responses overwriting fresh ones) and duplicated network calls were recurring in incident reviews, and onboarding engineers had to learn four conventions.

**Task:** As the staff engineer on the platform team, I was asked to drive convergence on a single data-fetching approach without halting feature work or imposing a top-down mandate that teams would resent and quietly ignore.

**Action:** I deliberately avoided "announce the standard and enforce it." First I ran a short discovery — read each team's patterns and quantified the cost (counted the race-condition bugs over two quarters, measured duplicated requests in production traces). Then I prototyped two options on a real feature: a thin in-house composable versus adopting TanStack Query for Vue, and I wrote a one-page decision doc with the trade-offs (in-house = full control, more maintenance; library = caching/dedup/cancellation for free, a dependency and a learning curve). I brought the two prototypes to an architecture review with one engineer from each team rather than deciding alone, so the teams co-owned the choice. We chose the library. I then wrote a migration guide with before/after examples from their *own* code, built a codemod for the most mechanical parts, set up a shared ESLint rule to flag the old pattern (warning, not error, initially), and — critically — migrated the two highest-traffic features myself so there were real, reviewed examples in the codebase, not just a doc. I held a 45-minute brown-bag and offered to pair with anyone on their first migration.

**Result:** All four teams migrated their new features within a quarter and backfilled hot paths over the next two; the race-condition bug class disappeared from incident reviews, and duplicated requests on key pages dropped measurably because of automatic dedup. Onboarding feedback specifically called out that there was now "one way to fetch data."

**Reflection:** The lesson I emphasize to other senior engineers is that *technical* standardization is mostly a *social* problem. The pattern only stuck because teams helped choose it, because I lowered the adoption cost (codemod, examples, pairing) instead of just documenting the ideal, and because I migrated real code myself to prove it worked and to absorb the first round of sharp edges. A mandate without those things produces malicious compliance and shadow patterns; co-ownership plus a paved road produces genuine adoption.

#### Q72. [Theory] What are the SSR streaming and edge-rendering options for Vue/Nuxt, and what do they trade off?

Beyond classic "render the whole page on the server, then send it," modern Vue/Nuxt SSR has several rendering modes that trade time-to-first-byte, infrastructure, and freshness differently. The main ones: **SSR (on-demand)** renders per request on a server; **SSG (static generation)** pre-renders pages to HTML at build time; **ISR (incremental static regeneration)** serves cached static pages and regenerates them in the background after a TTL; **streaming SSR** flushes HTML to the browser in chunks as it renders rather than buffering the whole page; and **edge rendering** runs the SSR on a CDN edge worker (e.g., Cloudflare Workers) close to the user instead of a central origin.

```
Mode      First byte   Freshness        Infra cost        Best for
─────────────────────────────────────────────────────────────────────────
SSG       fastest      build-time only  cheapest (CDN)    marketing, docs
ISR       fast         stale-then-fresh CDN + regen        catalogs, blogs
SSR       slower       always fresh     server always-on  dashboards, auth
Edge SSR  fast (near)  always fresh     edge workers       global low-latency
Streaming faster TTFB  fresh            server/edge        big pages, slow data
```

**Streaming SSR** is the subtle one: instead of waiting for all data to render the full HTML, the server sends the shell and earlier content immediately and streams later sections as their data resolves. This improves time-to-first-byte and lets the browser start parsing/painting sooner, which matters when one slow data dependency would otherwise block the entire page. The trade-off is complexity — you need your async boundaries (`<Suspense>`-style) arranged so independent sections can flush independently, and error handling mid-stream is trickier (you've already sent a 200 and some HTML, so you can't switch to a clean error page).

**Edge rendering** (Nuxt's Nitro presets target Cloudflare/Vercel/Deno edge) reduces latency by running SSR geographically near the user, but the edge runtime is constrained — limited Node APIs, execution time/memory caps, no persistent connections or local filesystem — so not every server-side dependency works there, and you may need edge-compatible alternatives. The strategic framing for an interview: there is no single "best" mode — a content site wants SSG/ISR (cheapest, fastest, freshness rarely critical), a personalized authenticated dashboard wants SSR/edge SSR (always fresh, per-user), and a large page with one slow widget benefits from streaming. Nuxt's **hybrid rendering** (`routeRules`) is the pragmatic answer: configure per-route — static for marketing, ISR for the catalog, SSR for the account area — within one app, choosing the right trade per page rather than one global mode.

#### Q73. [Coding] Implement a recursive tree component that renders arbitrarily nested data.

**Problem:** Render a tree of arbitrary depth (file explorer, org chart, nested comments) where each node may have children of the same shape — a component that references itself.

```vue
<!-- TreeNode.vue -->
<script setup>
import { ref } from 'vue'
defineProps({
  node: { type: Object, required: true },  // { id, label, children?: [] }
  depth: { type: Number, default: 0 }
})
const emit = defineEmits(['select'])
const open = ref(true)
</script>

<template>
  <li>
    <div :style="{ paddingLeft: depth * 16 + 'px' }">
      <span v-if="node.children?.length"
            @click="open = !open">{{ open ? '▾' : '▸' }}</span>
      <span @click="emit('select', node)">{{ node.label }}</span>
    </div>
    <ul v-if="open && node.children?.length">
      <!-- a component renders itself: recursion -->
      <TreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        @select="emit('select', $event)"
      />
    </ul>
  </li>
</template>
```

- **Why it works:** an SFC can reference itself by its filename-derived name (`TreeNode`) without explicit registration — Vue resolves recursive self-reference automatically. Each level passes `depth + 1` for indentation and **re-emits `select` upward** so the event bubbles from any depth to the root consumer, since events don't auto-propagate through component layers.
- **Edge cases:** the **base case** is implicit — a node with no `children` simply renders no nested `<ul>`, terminating the recursion. The critical risk is **cyclic data** (a node that references an ancestor) causing infinite recursion and a stack overflow; with untrusted/graph data, track visited ids and stop, or cap depth. Stable `:key` per node is essential so toggling/reordering doesn't re-create entire subtrees.
- **Performance:** recursion renders all expanded nodes; for very large trees (thousands of nodes) this is expensive, so combine with **lazy expansion** (only render children when a node is opened — which this already does via `v-if="open"`) and consider virtualizing the flattened visible node list. Re-emitting through every level is O(depth) per event, which is negligible; the dominant cost is the number of rendered nodes, so keeping collapsed subtrees unrendered (not just hidden) is the main lever.

#### Q74. [Practical] How do you set up E2E and component testing in CI for a Vue app, and what belongs at each layer?

A mature Vue testing strategy is a **pyramid**: many fast **unit tests** (composables, pure functions, Pinia logic with Vitest), a solid layer of **component tests** (a component mounted in a real-ish DOM via `@vue/test-utils`/Testing Library or Cypress/Playwright Component Testing), and a smaller set of **end-to-end tests** (the whole app in a real browser via Playwright or Cypress). The principle that decides *what goes where* is cost vs. confidence: unit tests are milliseconds and pinpoint failures but don't prove the app works; E2E tests prove real user flows but are slow and flakier, so you reserve them for critical journeys (login, checkout, the core workflow), not every permutation.

```yaml
# .github/workflows/ci.yml (sketch)
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: 'npm' }
      - run: npm ci
      - run: npx vue-tsc --noEmit          # template + TS type checking
      - run: npx vitest run --coverage      # unit + component (jsdom)
      - run: npx playwright install --with-deps
      - run: npm run build && npx playwright test   # E2E against built app
```

What belongs at each layer in practice: **unit** — composable logic (call the function, assert refs), store actions/getters (with `createTestingPinia`), formatters and guards; no DOM needed for most. **Component** — does this component render the right output for given props, emit the right events, handle slots, and react to user interaction; stub children (`shallowMount`/`stubs`) to test in isolation, and `await` after interactions because reactivity is async. **E2E** — multi-page flows, real routing and navigation, auth, third-party integration points, and "does the built artifact actually work in a browser" (which catches build/config issues unit tests can't).

The CI disciplines that matter: (1) **type-check templates with `vue-tsc --noEmit`** (plain `tsc` doesn't see `.vue` templates) so type errors fail the build, not production. (2) Run **E2E against the production build** (`vite build` + `preview`/serve), not the dev server, so you catch build-only failures (chunk splitting, env handling, minification breaking something). (3) **Tame flakiness** — Playwright's auto-waiting and web-first assertions (`expect(locator).toBeVisible()` retries) beat manual sleeps; isolate test data; retry only E2E (never mask a flaky unit test with retries). (4) **Parallelize and shard** E2E across CI runners to keep the pipeline fast as the suite grows. (5) Add **coverage thresholds** and a **bundle-size/Lighthouse budget** as gates. The judgment I bring is resisting both extremes — neither "E2E everything" (slow, flaky, expensive to maintain) nor "100% unit coverage with no E2E" (green tests, broken app) — and instead putting the heaviest coverage where logic lives (unit) and a thin, reliable safety net on the journeys that lose money if they break (E2E).

#### Q75. [Theory] How does `v-for` keying and the DOM-diff/reuse algorithm actually work, and why are index keys dangerous?

When Vue diffs two lists rendered by `v-for`, it uses the `:key` to identify which old VNode corresponds to which new one, so it can **reuse and patch existing DOM/component instances** rather than destroy and recreate them. Internally Vue's diff handles the common cases cheaply (same-position matches, head/tail trimming) and falls back to a key-based map plus a **longest-increasing-subsequence** computation to figure out the minimal set of DOM moves for reorders. The whole optimization hinges on keys being **stable and unique per item identity** — the key answers "is this the same logical item as before?"

Using the **array index as the key** breaks this because the index reflects *position*, not *identity*. When the list reorders, inserts, or deletes, the item at index 2 is now a *different* item, but Vue sees "key 2 still exists" and patches the old DOM node in place with the new item's data instead of moving it. For pure display this merely causes unnecessary patching, but it becomes a real **correctness bug** the moment the rows hold internal state or uncontrolled DOM state:

```
list: [A, B, C]  keyed by INDEX (0,1,2)
delete A  →  new list [B, C]
  Vue sees: key 0 was A, now B → patch A's node to show B  (reuses A's DOM)
            key 1 was B, now C → patch B's node to show C
            key 2 gone → remove C's node
  Result: B and C's DOM/internal state got SHUFFLED onto wrong items.
```

```vue
<!-- ❌ index key: checkbox state, focus, and transitions attach to position -->
<li v-for="(todo, i) in todos" :key="i"><input type="checkbox" />{{ todo.text }}</li>
<!-- ✅ stable identity key: DOM follows the item, not the slot -->
<li v-for="todo in todos" :key="todo.id"><input type="checkbox" />{{ todo.text }}</li>
```

The concrete failures from index keys: a checked checkbox or focused input "jumps" to the wrong row after a delete/reorder (because the DOM node with that state was reused for a different item), `<TransitionGroup>` move animations break (Vue can't tell what moved), and component-instance state (a half-filled form in a row) lands on the wrong row. The rule is therefore: **always key by a stable unique identity** (a database id, a UUID) for any dynamic list; an index key is only acceptable for a list that never reorders, never has items inserted/removed except at the end, and whose items hold no internal state — and even then, using a real id is the safer default that costs nothing. The deeper point is that the key is a *contract about identity*, and feeding Vue position-based keys lies about identity, which is why the bugs are so confusing — the framework is faithfully reusing nodes based on the (wrong) information you gave it.

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
