# React — Interview Preparation Guide

React is a declarative, component-based JavaScript library for building user interfaces, maintained by Meta and a large open-source community. This guide covers React from fundamentals through expert-level architecture, reconciliation internals, concurrent rendering, and React Server Components — current through React 19 (2024–2026).

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

### Q1. [Theory] What is JSX and how does it relate to JavaScript?

JSX is a syntax extension that lets you write HTML-like markup inside JavaScript. It is **not** valid JavaScript — a compiler (Babel, SWC, or the TypeScript compiler) transpiles it. Before React 17, JSX compiled to `React.createElement(type, props, ...children)`, which is why `React` had to be in scope. Since React 17 the "automatic runtime" imports `jsx`/`jsxs` from `react/jsx-runtime`, so you no longer need to import React just to use JSX. The key insight is that JSX produces plain JavaScript objects (React elements) — lightweight descriptions of what the UI should look like — not actual DOM nodes. Because it is just function calls, JSX expressions can be assigned to variables, passed as props, and returned from functions.

```javascript
const el = <h1 className="title">Hello</h1>;
// compiles (automatic runtime) to roughly:
import { jsx as _jsx } from "react/jsx-runtime";
const el = _jsx("h1", { className: "title", children: "Hello" });
// el === { type: "h1", props: { className, children }, key, ... }
```

### Q2. [Theory] What is the difference between props and state?

**Props** are inputs passed *into* a component by its parent; they are read-only from the child's perspective and flow one way (top-down). **State** is data a component *owns* and manages internally; it can change over time in response to user actions or async events, and changing it triggers a re-render. The mental model: props are like function arguments, state is like local variables that persist across renders. A component should never mutate its own props; if it needs to "change" a prop it must lift that data up to the parent's state and receive an updater callback. Choosing where state lives ("lifting state up") is one of the core design decisions in React.

```
   Parent  ──props──▶  Child
     ▲                   │
     └──── callback ──────┘   (child requests change, parent owns state)
```

### Q3. [Coding] Build a controlled counter with useState.

**Problem:** Render a number with increment, decrement, and reset buttons, never letting it go below zero.

```javascript
import { useState } from "react";

function Counter() {
  const [count, setCount] = useState(0);

  // functional updater avoids stale-closure bugs when batching
  const inc = () => setCount(c => c + 1);
  const dec = () => setCount(c => Math.max(0, c - 1));
  const reset = () => setCount(0);

  return (
    <div>
      <output>{count}</output>
      <button onClick={inc}>+</button>
      <button onClick={dec} disabled={count === 0}>-</button>
      <button onClick={reset}>Reset</button>
    </div>
  );
}
```

**Why the functional updater?** When several updates queue in one event, `setCount(count + 1)` called twice would both read the same stale `count`. `setCount(c => c + 1)` always operates on the latest queued value. **Time/Space:** O(1) per update. **Edge cases:** clamping at zero; disabling the decrement button so the UI communicates the constraint.

### Q4. [Theory] What are the Rules of Hooks and why do they exist?

There are two rules: (1) only call hooks at the **top level** of a function component or custom hook — never inside loops, conditions, or nested functions; and (2) only call hooks from **React functions**, not regular JS functions or class components. The reason is that React tracks hook state by **call order**, not by name. Internally each component has an ordered list of hook "slots"; on every render React walks that list in sequence. If you conditionally skip a hook, the indices shift and React associates the wrong state with the wrong hook. The `eslint-plugin-react-hooks` lint rule enforces these statically. This is also why custom hooks must start with `use` — the linter uses that convention to know it should apply the rules.

### Q5. [Practical] When should you use a key, and why is using the array index as a key risky?

Keys let React identify which list items changed, were added, or were removed during reconciliation, so it can reuse DOM nodes and component state instead of recreating them. Keys must be **stable, unique, and tied to the data's identity** — ideally a database ID. Using the array index works only for static lists that never reorder, insert, or delete in the middle. If you use the index and then reorder or splice the list, React thinks item-at-index-2 is "the same" item even though the underlying data changed, so it keeps the old component state (form input values, focus, animation state) attached to the wrong row. In production this shows up as a checkbox staying checked on the wrong item after a sort. **Rule of thumb:** index keys are acceptable only for append-only, never-reordered, stateless lists.

### Q6. [Coding] Build a controlled text input and show its live length.

```javascript
import { useState } from "react";

function NameField() {
  const [name, setName] = useState("");
  return (
    <label>
      Name:
      <input
        value={name}                       // React owns the value (controlled)
        onChange={e => setName(e.target.value)}
        maxLength={50}
      />
      <small>{name.length}/50</small>
    </label>
  );
}
```

In a **controlled** input the React state is the single source of truth — the DOM value mirrors state. **Edge case:** never set `value={undefined}` then later a string, or React warns about switching between uncontrolled and controlled. Initialize with `""`, not `undefined`. **Time/Space:** O(1) per keystroke.

### Q7. [Theory] What is the virtual DOM at a basic level?

The virtual DOM is an in-memory tree of plain JavaScript objects (React elements) that represents what the UI should look like. When state changes, React builds a new virtual tree, **diffs** it against the previous one, and computes the minimal set of real DOM mutations needed. Direct DOM manipulation is expensive (layout, reflow, repaint), so batching changes and touching the real DOM as little as possible is what makes React fast enough for complex UIs. Importantly, the virtual DOM is a *means*, not magic — it is React's strategy for turning a declarative `UI = f(state)` model into efficient imperative DOM updates without you writing them by hand.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Explain useEffect's dependency array and the cleanup function.

`useEffect(fn, deps)` runs `fn` after the browser has painted, and re-runs it whenever any value in `deps` changes (shallow `Object.is` comparison). Omitting `deps` runs the effect after every render; an empty `[]` runs it once after mount (and cleanup on unmount). The function `fn` may return a **cleanup** function, which React calls before the next effect run *and* on unmount — this is where you remove event listeners, cancel timers, abort fetches, or close subscriptions. The most common bug is omitting a dependency the effect actually reads, causing it to close over stale values. In React 18+ Strict Mode, effects run **twice** in development (mount → unmount → mount) specifically to surface missing cleanup. The "why": effects are for synchronizing your component with **external systems** (DOM, network, subscriptions), not for transforming data during render.

```javascript
useEffect(() => {
  const id = setInterval(() => tick(), 1000);
  return () => clearInterval(id);   // cleanup prevents leaked intervals
}, []);                              // [] -> set up once
```

### Q9. [Theory] Compare useMemo, useCallback, and useRef.

All three help manage values across renders but solve different problems. `useMemo(fn, deps)` **caches a computed value** so an expensive calculation re-runs only when deps change. `useCallback(fn, deps)` **caches a function identity** so the same function reference is passed to children — essentially `useMemo(() => fn, deps)`. `useRef(initial)` returns a **mutable container** (`{ current }`) whose identity is stable for the component's whole life and whose mutation does **not** trigger a re-render; it is used for DOM access and for storing values that should persist without affecting rendering (timers, previous values). The trade-off with `useMemo`/`useCallback` is that memoization itself has a cost (storing deps, comparing them), so applying it everywhere is premature optimization. Note: with the **React Compiler** (stable in React 19), much manual memoization becomes unnecessary because the compiler auto-memoizes.

```
useMemo     -> remembers a VALUE        -> recompute when deps change
useCallback -> remembers a FUNCTION     -> new identity when deps change
useRef      -> a stable mutable BOX     -> changing .current never re-renders
```

### Q10. [Coding] Write a custom useDebounce hook.

**Problem:** Return a value that only updates after the input has stopped changing for `delay` ms — used to throttle expensive search calls.

```javascript
import { useState, useEffect } from "react";

function useDebounce(value, delay = 300) {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(id);   // cancel pending timer on each change
  }, [value, delay]);

  return debounced;
}

// usage
function Search() {
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebounce(query, 400);

  useEffect(() => {
    if (debouncedQuery) fetchResults(debouncedQuery);
  }, [debouncedQuery]);

  return <input value={query} onChange={e => setQuery(e.target.value)} />;
}
```

**How it works:** every change schedules a timer; the cleanup clears the previous timer, so the state only updates once typing pauses. **Time/Space:** O(1) per keystroke, one outstanding timer. **Edge cases:** changing `delay` mid-flight resets the timer; on unmount the timer is cleared so there is no setState-after-unmount.

### Q11. [Theory] Controlled vs uncontrolled components — when to choose each?

A **controlled** component drives its value from React state via `value`/`checked` + `onChange`; React is the source of truth. An **uncontrolled** component lets the DOM hold its own state and you read it on demand via a `ref` (or `defaultValue` for the initial value). Controlled gives you instant access to the value for validation, conditional UI, and formatting, at the cost of a re-render per keystroke. Uncontrolled is lighter and integrates well with non-React code and file inputs (`<input type="file">` is *always* uncontrolled because its value can't be set programmatically for security reasons). In practice, prefer controlled for forms needing live validation; reach for uncontrolled (or a form library like React Hook Form, which uses refs to avoid per-keystroke renders) for large or performance-sensitive forms.

```javascript
// Uncontrolled: read on submit, no re-render per keystroke
function Form() {
  const ref = useRef(null);
  const onSubmit = e => { e.preventDefault(); console.log(ref.current.value); };
  return <form onSubmit={onSubmit}><input ref={ref} defaultValue="" /></form>;
}
```

### Q12. [Theory] When would you reach for useReducer over useState?

`useReducer` is preferable when state logic is **complex** — multiple sub-values that change together, the next state depends on the previous, or transitions follow well-defined rules. It centralizes update logic in a pure `reducer(state, action)` function, which is easier to test in isolation and to reason about than scattered `setState` calls. It also improves performance in deep trees because you can pass a stable `dispatch` down via context instead of new callbacks. The trade-off is boilerplate: for one or two independent values, `useState` is simpler. A good heuristic: if you find yourself calling several `setState`s together or your update logic has branches, switch to a reducer.

```javascript
const initial = { count: 0, step: 1 };
function reducer(state, action) {
  switch (action.type) {
    case "inc":  return { ...state, count: state.count + state.step };
    case "step": return { ...state, step: action.value };
    default:     throw new Error("unknown action " + action.type);
  }
}
const [state, dispatch] = useReducer(reducer, initial);
```

### Q13. [Practical] Context vs a state-management library (Redux/Zustand/Jotai) — how do you decide?

Context is a **dependency-injection** mechanism, not a state manager: it broadcasts a value down the tree and re-renders **every** consumer whenever the value's reference changes. That is perfect for low-frequency, app-wide values (theme, locale, current user, auth token). It becomes a performance problem for high-frequency updates because there is no built-in selector — you can't subscribe to "just this slice." When you have frequently changing global state with many independent consumers, reach for a library: **Zustand/Jotai** give fine-grained subscriptions with minimal boilerplate; **Redux Toolkit** adds structure, devtools time-travel, and middleware for large teams. In production I split concerns: server state goes to **TanStack Query** (caching, refetch, dedup), genuinely global client state goes to Zustand, and Context handles static config. A common anti-pattern is putting fast-changing data in one giant context and watching the whole tree re-render.

```
Static, app-wide, rarely changes ........ Context
Server/async data, caching needed ........ TanStack Query / SWR
Frequent global client state, selectors .. Zustand / Jotai / Redux Toolkit
Local component state .................... useState / useReducer
```

### Q14. [Coding] Build an error boundary and explain its limits.

**Problem:** Catch render-time errors in a subtree and show a fallback instead of a blank white screen. Error boundaries must currently be **class** components (there is no hook equivalent yet).

```javascript
import { Component } from "react";

class ErrorBoundary extends Component {
  state = { error: null };

  static getDerivedStateFromError(error) {
    return { error };                 // render fallback on next render
  }
  componentDidCatch(error, info) {
    logToService(error, info.componentStack);  // side effect: report it
  }
  render() {
    if (this.state.error) {
      return this.props.fallback ?? <p>Something went wrong.</p>;
    }
    return this.props.children;
  }
}
// <ErrorBoundary fallback={<Retry />}><Dashboard /></ErrorBoundary>
```

**Limits:** boundaries do **not** catch errors in event handlers (use try/catch there), async code (`setTimeout`, promises), SSR, or errors thrown in the boundary itself. They catch errors during rendering, lifecycle methods, and constructors of the components below them. **Production tip:** wrap distinct regions (sidebar, main, widget) in separate boundaries so one failing widget doesn't take down the page.

### Q15. [Coding] Write a custom usePrevious hook to track the previous value of a prop or state.

```javascript
import { useRef, useEffect } from "react";

function usePrevious(value) {
  const ref = useRef(undefined);
  useEffect(() => {
    ref.current = value;   // runs AFTER render, so reads see the prior value
  }, [value]);
  return ref.current;      // returns value from the previous render
}

// usage: detect direction of a changing score
function Score({ score }) {
  const prev = usePrevious(score);
  const arrow = prev === undefined ? "" : score > prev ? "▲" : score < prev ? "▼" : "";
  return <span>{score} {arrow}</span>;
}
```

**Why it works:** the effect updates `ref.current` only *after* the render commits, so during render `ref.current` still holds the value from the previous render. **Time/Space:** O(1). **Edge case:** on the first render `prev` is `undefined` — handle that to avoid a false arrow.

### Q16. [Theory] What is reconciliation, and how do keys and component type affect it?

Reconciliation is React's diffing algorithm that compares the new element tree to the previous one to decide what to change in the DOM. To keep it O(n) instead of O(n³), React uses heuristics: (1) elements of **different types** (e.g., `<div>` → `<span>`, or `ComponentA` → `ComponentB`) cause React to tear down the old subtree entirely and rebuild it, discarding its state; (2) elements of the **same type** are updated in place, preserving DOM nodes and component state; (3) within lists, **keys** match children across renders. This is why conditionally swapping between two component types resets their state, and why a stable key preserves an input's value across reorders. Understanding this explains a whole class of "my input lost focus / my state reset" bugs.

### Q17. [Practical] A list of 10,000 rows renders slowly and re-renders on every keystroke. How do you fix it?

First, **measure** with the React DevTools Profiler to confirm where time goes — never optimize blind. The two usual culprits are (a) rendering all 10,000 DOM nodes at once and (b) the parent re-rendering all rows on each keystroke. For (a), apply **windowing/virtualization** (`react-window` or TanStack Virtual) so only the ~30 visible rows mount. For (b), wrap each row in `React.memo` and pass **stable** props — memoize callbacks with `useCallback` and avoid creating new object/array literals inline. Also move the fast-changing input state down so typing doesn't re-render the list, and debounce any derived filtering. In production this combination typically takes a sluggish 200ms+ keystroke down to sub-frame. With React 19's compiler, the `memo`/`useCallback` step is largely automated, but virtualization is still required because the compiler can't reduce raw DOM-node count.

```
Before: keypress -> parent render -> 10,000 row renders -> 10,000 DOM diffs
After:  keypress -> local input render only
        scroll   -> virtualizer mounts ~30 visible memoized rows
```

### Q18. [Theory] What is React.memo and when does it actually help (or hurt)?

`React.memo` is a higher-order component that memoizes a component's rendered output, skipping re-render when its props are **shallowly equal** to the previous props. It helps when a component is expensive to render and its parent re-renders frequently with the same props. It **hurts** (or does nothing) when props change on every render anyway — for example, a parent passing a new inline arrow function or object literal each time defeats the shallow comparison, so React pays the comparison cost *and* re-renders. To benefit you must stabilize props with `useCallback`/`useMemo`. The mental trap is sprinkling `memo` everywhere as "free speed"; in reality each `memo` adds a props comparison that can exceed the cost of a cheap render. Profile first.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] Explain the Fiber architecture and how it enables concurrent rendering.

Fiber is React's reconciliation engine, introduced in React 16, that replaced the old recursive stack reconciler. A **fiber** is a JavaScript object representing a unit of work for one element — it stores the element type, props, state, and pointers (`child`, `sibling`, `return`) forming a linked tree. The crucial change is that work is broken into **interruptible units**: instead of recursing the whole tree synchronously, React processes fibers in a loop that can yield back to the browser between units. This makes rendering **interruptible, resumable, and prioritizable**. React 18's concurrent renderer builds on this: high-priority updates (typing) can interrupt a low-priority render (filtering a big list) using `startTransition`. Rendering happens in two phases — a **render/reconcile** phase (can be paused, aborted, restarted, and must be side-effect-free) and a **commit** phase (synchronous, applies DOM mutations and runs effects). Because the render phase may run more than once, your render logic must be pure — a major reason effects exist for side effects.

```
        render phase (interruptible, pure)        commit phase (sync)
  ┌──────────────────────────────────────┐   ┌──────────────────────┐
  │ build/diff fiber tree, can yield to   │ → │ mutate DOM, run       │
  │ browser, can be aborted & restarted   │   │ layout & passive fx   │
  └──────────────────────────────────────┘   └──────────────────────┘
```

### Q20. [Theory] What are React Server Components (RSC) and how do they differ from SSR?

Server Components, stabilized through the App Router era and React 19, are components that render **only on the server** and never ship their code to the client. They can directly access the database, filesystem, or secrets, and they emit a serialized description (the RSC payload) that the client merges into the tree. The distinction from classic **SSR**: SSR renders a *client* component to an HTML string on the server, then **hydrates** it — the same component code runs on both sides and ships to the browser. RSC components never hydrate and never ship JS, dramatically cutting bundle size; only components marked `"use client"` are interactive on the client. The model is a tree where Server Components form the static/data-fetching shell and Client Components are interactive leaves/islands. Trade-offs: Server Components can't use state or effects or browser APIs, and you must think carefully about the server/client boundary and what data crosses it (it must be serializable).

```
Server Component (no JS to client)
   ├─ fetches data, reads secrets
   └─ <ClientComponent>  ("use client" -> hydrates, has state/handlers)
        └─ Server Component children can still be passed as props
```

### Q21. [Practical] How does Suspense work and how do you use it with data fetching?

Suspense lets a component "suspend" — tell React it isn't ready to render yet — by throwing a promise (the framework/library does this for you; you rarely throw manually). React then shows the nearest `<Suspense fallback={...}>` boundary's fallback until the promise resolves, then renders the real content. Originally for code-splitting via `React.lazy`, in React 18/19 it powers **streaming SSR** and integrates with RSC and the `use()` hook to suspend on data. The practical win is declarative loading states without manual `isLoading` flags scattered everywhere, plus the ability to stream HTML so the user sees the shell instantly while slow data streams in. In production you compose boundaries: a coarse boundary for the page shell and finer boundaries around independent slow widgets so one slow query doesn't block the whole page. Pair with an error boundary for the failure case — together they handle pending/success/error declaratively.

```javascript
import { Suspense } from "react";

function Page() {
  return (
    <Suspense fallback={<Skeleton />}>
      <SlowProfile />     {/* suspends on data via use() / a Suspense-ready lib */}
    </Suspense>
  );
}
```

### Q22. [Coding] Implement a generic useFetch hook with abort, loading, and error states.

**Problem:** A reusable data-fetching hook that cancels the in-flight request when the URL changes or the component unmounts, avoiding race conditions and setState-after-unmount.

```javascript
import { useState, useEffect } from "react";

function useFetch(url) {
  const [state, setState] = useState({ data: null, error: null, loading: true });

  useEffect(() => {
    const controller = new AbortController();
    setState({ data: null, error: null, loading: true });

    fetch(url, { signal: controller.signal })
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => setState({ data, error: null, loading: false }))
      .catch(err => {
        if (err.name !== "AbortError") {        // ignore intentional aborts
          setState({ data: null, error: err, loading: false });
        }
      });

    return () => controller.abort();            // cancel on url change / unmount
  }, [url]);

  return state;
}
```

**Race-condition fix:** without `AbortController`, a slow first request resolving *after* a fast second one would overwrite newer data with stale data. Aborting the old request prevents this. **Time/Space:** O(1) hook overhead; one outstanding request. **Edge cases:** non-2xx responses become errors; aborts are swallowed; unmount cancels cleanly. **Production note:** for real apps prefer **TanStack Query** — it adds caching, dedup, retries, and background refetch that you'd otherwise reinvent.

### Q23. [Coding] Implement useEventCallback (a stable callback that always sees fresh values).

**Problem:** You want a callback whose **identity never changes** (so memoized children don't re-render) but that always reads the **latest** props/state — solving the stale-closure problem without listing every dependency.

```javascript
import { useRef, useCallback, useInsertionEffect } from "react";

function useEventCallback(fn) {
  const ref = useRef(fn);
  // keep ref current before any DOM mutation / effect reads it
  useInsertionEffect(() => { ref.current = fn; });
  // stable identity; reads latest fn at call time
  return useCallback((...args) => ref.current(...args), []);
}

// usage: stable handler that always sees the latest `count`
function Widget({ count, onSync }) {
  const stableSync = useEventCallback(() => onSync(count));
  // pass stableSync to a memoized child without causing re-renders
}
```

This is the pattern behind React's experimental `useEffectEvent` (the "useEvent" RFC). **Why `useInsertionEffect`:** it fires before layout effects, ensuring the ref is fresh before consumers run. **Time/Space:** O(1). **Edge case:** do **not** call the returned function during render — it's meant for event handlers and effects, where the "latest value" semantics are correct.

### Q24. [Theory] What is hydration, and what causes hydration mismatches?

Hydration is the process where React takes server-rendered HTML and attaches event listeners and internal state to the existing DOM nodes, rather than recreating them — making static markup interactive. A **mismatch** occurs when the HTML React produces on the client during hydration differs from what the server sent. Common causes: rendering `Date.now()`/`Math.random()`/locale-dependent formatting that differs between server and client, reading `window`/`localStorage` during render, browser extensions mutating the DOM, or invalid HTML nesting that the browser "fixes" (e.g., `<div>` inside `<p>`). React 18 logs a warning and (in 19) recovers by re-rendering the mismatched subtree on the client, but mismatches still cause flicker and lost performance. The fix for genuinely client-only values is to render a stable placeholder on the server and update after mount via `useEffect`, or use the `suppressHydrationWarning` escape hatch for known, intentional differences like timestamps.

### Q25. [Practical] Your team's app suffers from "prop drilling" and excessive re-renders from a global context. How do you architect a fix?

The two problems are related but distinct. **Prop drilling** (passing props through many intermediate components that don't use them) is a *readability/maintainability* issue; **context-induced re-renders** are a *performance* issue. The naive fix — one big context — solves drilling but worsens performance because every consumer re-renders on any change. My production approach: **split contexts by update frequency and by read/write**. Put rarely-changing config (theme, user) in one context. Split state and its dispatcher into **two** contexts so components that only dispatch don't re-render when state changes. For high-frequency or selector-based needs, move that slice into Zustand/Jotai, which support subscribing to a derived slice so only components reading that slice re-render. Compose with `React.memo` on intermediate components to break re-render propagation. The architectural principle: **co-locate state as low as it can live**, and only lift to global stores what genuinely is global.

```
❌ One context: theme + cart + cursor position  -> everything re-renders
✅ ThemeContext (rare)  | CartContext (medium) | useCursorStore() (selector, fast)
   StateContext + DispatchContext split so dispatch-only consumers stay stable
```

### Q26. [Theory] Explain the difference between useEffect, useLayoutEffect, and useInsertionEffect.

All three run after render, but at different points relative to the browser paint. **`useLayoutEffect`** fires **synchronously after DOM mutations but before the browser paints** — use it when you must read layout (measure a node) and synchronously re-render to avoid a visible flicker (e.g., positioning a tooltip). Because it blocks paint, overusing it hurts performance, and it can't run during SSR (it warns). **`useEffect`** fires **after paint**, asynchronously — the default for data fetching, subscriptions, logging, anything not affecting the visual layout of the current frame. **`useInsertionEffect`** fires **before** layout effects and is intended for CSS-in-JS libraries to inject `<style>` tags before layout reads occur; application code rarely uses it. The decision rule: reach for `useEffect` by default; escalate to `useLayoutEffect` only when you observe a flicker caused by measuring/mutating layout.

```
DOM mutated → useInsertionEffect → useLayoutEffect → [browser paints] → useEffect
```

### Q27. [Practical] Walk through diagnosing and fixing an infinite re-render loop caused by useEffect.

The classic loop: an effect that sets state, with a dependency that changes every render. **Diagnosis:** the React DevTools "highlight updates" feature flashes the component continuously; the console may warn about "Maximum update depth exceeded." Then inspect the effect's dependency array. The usual root cause is a **non-primitive dependency created fresh each render** — an object, array, or function literal — so its reference always differs, re-triggering the effect, which sets state, which re-renders, which recreates the dependency. **Fixes, in order of preference:** (1) remove the dependency if you can derive the value during render instead; (2) memoize the object/function with `useMemo`/`useCallback`; (3) use a functional state updater so the effect doesn't depend on the current state value; (4) if you're synchronizing to a value but shouldn't react to it, store it in a ref. Often the deeper fix is recognizing the effect shouldn't exist at all — "you might not need an effect" — and computing the value during render or in an event handler instead.

---

## 🔴 Expert (15+ yrs)

### Q28. [Theory] How do automatic batching and the concurrent renderer change React 18's update semantics versus React 17?

In React 17, state updates were batched only inside React event handlers; updates in promises, `setTimeout`, or native event callbacks each triggered a separate synchronous render. React 18 introduced **automatic batching everywhere** — multiple `setState` calls in any context are grouped into one re-render — reducing wasted renders. More fundamentally, React 18 ships the **concurrent renderer**: rendering can be interrupted, and updates carry **priorities (lanes)**. `startTransition`/`useTransition` mark updates as non-urgent so React can keep the UI responsive to urgent input while a heavy render proceeds in the background, even discarding and restarting it if a newer urgent update arrives. `useDeferredValue` lets you render a stale value at high priority and the fresh one at low priority. The subtle consequence: because the render phase can run multiple times and be thrown away, **render must be pure** — Strict Mode's double-invocation in dev exists to catch impurity. Migrating from 17 sometimes surfaces latent bugs that the old eager rendering masked.

### Q29. [Theory] Explain the React 19 Compiler and what it makes obsolete.

The React Compiler (formerly "React Forget"), stable in React 19, is a build-time optimizing compiler that analyzes your components and **automatically memoizes** values and components — effectively inserting the equivalent of `useMemo`, `useCallback`, and `React.memo` where they're provably safe. It works by understanding React's rules and the data-flow within a component, producing fine-grained memoization at a granularity humans rarely write by hand. This makes most **manual** memoization obsolete: you write straightforward code and the compiler ensures children don't re-render needlessly. The critical caveat is that it relies on your code following the Rules of React — **no mutation of props/state during render, pure render functions** — which is why the compiler ships with an ESLint rule and bails out (safely leaving code un-memoized) when it can't prove safety. It does **not** replace virtualization or algorithmic improvements; it removes re-render overhead, not raw work. Senior teams should adopt it incrementally, fix lint violations, and then delete now-redundant manual memo hooks.

### Q30. [Coding] Implement a render-prop + hook-based observable store with selector subscriptions (mini-Zustand).

**Problem:** Build a tiny global store where components subscribe to a **selected slice** and re-render only when that slice changes — demonstrating `useSyncExternalStore`, the React 18 primitive for external stores that is concurrent-safe.

```javascript
import { useSyncExternalStore } from "react";

function createStore(initial) {
  let state = initial;
  const listeners = new Set();

  const getState = () => state;
  const setState = partial => {
    const next = typeof partial === "function" ? partial(state) : partial;
    state = { ...state, ...next };
    listeners.forEach(l => l());      // notify all subscribers
  };
  const subscribe = listener => {
    listeners.add(listener);
    return () => listeners.delete(listener);
  };

  // hook with selector + equality check
  const useStore = (selector = s => s, isEqual = Object.is) => {
    return useSyncExternalStore(
      subscribe,
      () => selector(getState()),     // getSnapshot
      () => selector(initial)         // getServerSnapshot (SSR)
    );
  };

  return { getState, setState, subscribe, useStore };
}

// usage
const store = createStore({ count: 0, user: null });
function Count() {
  const count = store.useStore(s => s.count);  // re-renders only on count change
  return <button onClick={() => store.setState(s => ({ count: s.count + 1 }))}>{count}</button>;
}
```

**Why `useSyncExternalStore`:** in concurrent rendering, reading mutable external state during a render that may be torn between two values causes **tearing** (different parts of the UI showing different store values). This hook guarantees a consistent snapshot. **Time/Space:** subscribe/notify is O(n) in listeners; selector is O(1). **Edge cases:** the snapshot function must return a **stable reference** for unchanged data, or you re-render every time; a real implementation memoizes selector output or supports a custom equality function.

### Q31. [Theory] What is "tearing" and how does React 18 prevent it?

Tearing is a class of bug unique to concurrent rendering: because React 18 can pause and resume rendering, an external mutable source (a Redux store, a global variable) might change **mid-render**, so components rendered before the change show the old value while components rendered after show the new value — the UI is internally inconsistent ("torn"). In React 17's fully synchronous render this couldn't happen because a render ran to completion atomically. React 18 prevents tearing for external stores via **`useSyncExternalStore`**, which forces a consistent snapshot for the whole render pass and, if the store changes during a concurrent render, makes React fall back to a synchronous re-render to maintain consistency. This is why every external state library (Redux, Zustand, Jotai) migrated to this hook for React 18 compatibility. The takeaway for senior engineers: any state held *outside* React's own state mechanisms must be read through `useSyncExternalStore` to be concurrent-safe.

### Q32. [Practical] Describe migrating a large legacy React 16/17 class-based app to modern React (hooks, 18/19, RSC). What's your strategy and what breaks?

I'd treat it as **incremental, not big-bang**. Phase 1: upgrade dependencies and switch to `createRoot` (the React 18 root API), which enables the new renderer; fix Strict Mode double-invocation issues that surface impure effects. Phase 2: migrate class components to hooks **opportunistically** — when touching a file, not in a dedicated rewrite sprint — because the behavior is equivalent and a mass rewrite risks regressions for little user-facing gain. Keep error boundaries as classes (still required). Phase 3: adopt automatic batching and audit code that *relied* on un-batched updates. Phase 4: introduce concurrent features (`useTransition`, Suspense for data) where UX benefits. RSC migration is the biggest leap and usually means adopting a framework (Next.js App Router); it requires drawing the server/client boundary, marking interactive leaves `"use client"`, and moving data fetching server-side — so I'd pilot it on one new route, not the whole app. **What breaks:** `componentWillMount`/`componentWillReceiveProps` (deprecated), string refs, `ReactDOM.render` warnings, libraries that read mutable state without `useSyncExternalStore` (tearing), and effects that assumed single-invocation. Risk is managed with feature flags, robust E2E tests, and the React DevTools Profiler to catch regressions.

### Q33. [Theory] What are the security implications you must manage in a React application?

React's JSX auto-escapes string content, which prevents the most common XSS vector by default — interpolated text is treated as text, not HTML. The principal danger is **`dangerouslySetInnerHTML`**, which bypasses escaping; any user-controlled HTML passed there must be sanitized (e.g., with DOMPurify) or it becomes a stored/reflected XSS hole. Other vectors: setting `href`/`src` to a user-supplied `javascript:` URL (validate the protocol), spreading untrusted props onto DOM elements, and SSR that interpolates unsanitized data into the HTML stream. Beyond rendering, secrets must **never** ship to the client — anything in client bundle code (including "private" env vars not prefixed correctly) is visible to users; with RSC this improves because server-only code stays on the server, but you must be careful what you pass across the serialization boundary. Finally, dependency supply-chain risk (a compromised npm package can exfiltrate data) demands lockfiles, audits, and minimizing transitive dependencies. A real-world reminder: several high-profile breaches trace to XSS via unsanitized rich-text fields rendered with `dangerouslySetInnerHTML`.

### Q34. [Behavioral] Tell me about a time you had to make a significant frontend architecture decision under disagreement.

Use a structured **STAR** answer. *Situation:* a team was debating adopting Redux for an app whose state was mostly server data, with a vocal faction wanting "one store to rule them all." *Task:* as the senior engineer I had to land a decision the team would commit to, without steamrolling. *Action:* I ran a short spike comparing Redux Toolkit, TanStack Query + Zustand, and plain Context across our real screens, measuring boilerplate, re-render counts in the Profiler, and onboarding time for a junior dev. I shared a written RFC with the data, explicitly steelmanning the Redux view (devtools, predictability) before recommending TanStack Query for server state plus a thin Zustand store for genuine client state. *Result:* the team aligned because the decision was evidence-based and acknowledged the trade-offs rather than dismissing them; re-render counts dropped measurably and feature velocity improved. The meta-lesson I'd convey to an interviewer: senior frontend decisions are won with **data, prototypes, and respect for dissent**, not authority — and you should document the trade-offs so the decision can be revisited if assumptions change.

### Q35. [Theory] How would you architect a design system / component library used across many React apps for long-term maintainability?

I'd separate **primitives** (unstyled, accessible behavior — think headless components like Radix/Ark) from **styled components** (your brand layer) from **patterns** (composed templates). Components should be **controlled-first with sensible uncontrolled defaults**, expose `ref` forwarding (`forwardRef`, or props in React 19 where `ref` is a regular prop), and follow a consistent prop API (e.g., `asChild`/polymorphic `as` for composition). Theming via CSS variables (not JS-in-render) keeps runtime cost low and supports SSR/RSC, since CSS-in-JS-at-runtime is a poor fit for Server Components. Accessibility (ARIA, focus management, keyboard nav) must be baked into primitives, not bolted on per app. For distribution, version with semver, ship typed (`.d.ts`), document with Storybook, and treat visual regression tests as a release gate. The architectural tension is **flexibility vs consistency**: too rigid and teams fork the library; too flexible and you lose the design system's value. I resolve it by making the common case effortless and the escape hatch explicit (slots, style overrides) so deviation is visible in review. Tracking adoption metrics and a deprecation policy keeps the system healthy over years.

---

## ✅ Key Takeaways

- **`UI = f(state)`**: React is declarative; you describe the UI for a given state and React reconciles the DOM. Keep render functions **pure** — the concurrent renderer may run them multiple times.
- **Hooks are positional**: call them unconditionally at the top level; the linter and the compiler both depend on this.
- **Keys are identity**, not just "a warning to silence" — stable keys preserve state and DOM across reorders; index keys are safe only for static lists.
- **Effects synchronize with external systems**; don't use them to transform data you can compute during render. "You might not need an effect."
- **Context is DI, not a fast state manager** — split by update frequency, or use selector-based stores (Zustand/Jotai) and TanStack Query for server state.
- **Fiber → concurrency**: interruptible rendering enables `useTransition`, `useDeferredValue`, streaming Suspense, and requires `useSyncExternalStore` for external state to avoid tearing.
- **RSC** removes client JS and runs data-fetching on the server; mark interactive leaves `"use client"` and mind the serialization boundary.
- **React 19 Compiler** auto-memoizes — write clean, rule-following code and delete most manual `useMemo`/`useCallback`/`memo`.

## ⚠️ Common Pitfalls

- Using the **array index as a key** in a reorderable/insertable list → wrong state attaches to wrong row.
- **Missing dependencies** in `useEffect`/`useCallback` → stale closures; or unstable deps (inline objects/functions) → infinite loops.
- Forgetting effect **cleanup** → leaked intervals, listeners, and setState-after-unmount; Strict Mode's double-run exists to catch this.
- Sprinkling `React.memo`/`useMemo` everywhere without profiling → comparison cost with no benefit, especially when props change anyway.
- Putting **fast-changing data in one large Context** → whole subtree re-renders.
- **`dangerouslySetInnerHTML`** with unsanitized input → XSS. Sanitize with DOMPurify; validate URL protocols.
- Reading **browser-only APIs** (`window`, `localStorage`) during render in SSR/RSC → hydration mismatches.
- Mutating state directly (`state.items.push(...)`) instead of producing new references → React's bail-out doesn't detect the change.
- Reading external mutable stores without **`useSyncExternalStore`** under concurrent rendering → tearing.

## 📚 Further Reading

- **Official React Docs** — react.dev (Learn + Reference; the Server Components, `useSyncExternalStore`, and "You Might Not Need an Effect" pages are essential).
- **React 18 & 19 release notes and the React Compiler docs** — react.dev/blog (automatic batching, concurrent features, compiler).
- *"The Fiber architecture"* — Andrew Clark's React Fiber Architecture notes (github.com/acdlite/react-fiber-architecture).
- **"A Complete Guide to useEffect"** — Dan Abramov, overreacted.io (the definitive deep dive on effects and closures).
- *Learning React, 2nd ed.* — Alex Banks & Eve Porcello (O'Reilly), and *Fluent React* — Tejas Kumar (O'Reilly) for internals.
- **TanStack Query docs** (tanstack.com/query) and **Zustand docs** (zustand-demo.pmnd.rs) for production state-management patterns.
