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

## 🧩 Extended Questions — Set 1: Deeper Theory & Practical Operations

### 🟢 Basic — extended

#### Q36. [Theory] What is the difference between a React element and a component?

A **component** is a function (or class) that you write — a reusable template that accepts props and returns a description of UI. An **element** is the immutable plain-object output produced when you "use" a component or HTML tag in JSX — `{ type, props, key, ref }`. The relationship is like a class versus an instance, except elements are far cheaper: creating an element does **not** render anything; it just describes *what* should appear. React later calls the component function with the element's props to produce more elements, recursively, until it reaches host elements like `"div"`.

```javascript
// Greeting is a COMPONENT (a function)
function Greeting({ name }) { return <h1>Hi {name}</h1>; }

// <Greeting name="Ada" /> is an ELEMENT (a description, not a DOM node)
const el = <Greeting name="Ada" />;
// el === { type: Greeting, props: { name: "Ada" }, key: null, ... }
```

The distinction matters for performance reasoning: because elements are cheap immutable objects, React can create a whole new tree on every render and diff it. It also clarifies why `<Greeting />` (an element) differs from `Greeting()` (calling the function directly) — the former lets React own the lifecycle, keys, and reconciliation, while the latter inlines the output and loses React's identity tracking. Always render `<Greeting />`, not `{Greeting()}`.

#### Q37. [Practical] How do you conditionally render content, and what are the common gotchas?

React renders the result of JavaScript expressions, so conditional UI uses ordinary JS: ternaries, `&&`, early `return`, or extracting a variable. The most common production bug is the `&&` "zero leak": `{count && <Badge />}` renders the literal `0` when `count` is `0`, because `0` is falsy but is still a valid React child and gets printed. Strings like `""` behave the same way for `NaN`/`0`. The fix is to coerce to a real boolean: `{count > 0 && <Badge />}` or `{!!count && ...}` or use a ternary returning `null`.

```javascript
// ❌ renders "0" on screen when items.length === 0
{items.length && <List items={items} />}

// ✅ explicit boolean — renders nothing when empty
{items.length > 0 && <List items={items} />}

// ✅ ternary is unambiguous
{items.length ? <List items={items} /> : <Empty />}
```

The second gotcha is readability: deeply nested ternaries in JSX become unreadable fast. Prefer extracting a helper function or an early `return` at the top of the component for distinct states (loading, error, empty, data). Returning `null` from a component is legal and renders nothing — useful for components that sometimes shouldn't appear. Avoid putting heavy branching logic inside the JSX itself; compute a variable above the `return` so the markup stays scannable.

#### Q38. [Practical] What is the children prop and what are the composition patterns built on it?

`children` is a special prop holding whatever JSX you nest between a component's opening and closing tags. It enables **composition** — passing UI into a component rather than configuring it with many boolean flags. This is React's preferred answer to "how do I make a flexible, reusable layout": instead of `<Card hasHeader hasFooter title="..." />`, you pass the actual content as children and named slots as props.

```javascript
function Card({ header, children, footer }) {
  return (
    <section className="card">
      {header && <div className="card-head">{header}</div>}
      <div className="card-body">{children}</div>
      {footer && <div className="card-foot">{footer}</div>}
    </section>
  );
}

// usage: content is composed in, not configured via flags
<Card header={<h2>Profile</h2>} footer={<SaveButton />}>
  <Avatar /> <Bio />
</Card>
```

Patterns built on this include **slots** (passing JSX via named props like `header`/`footer` above), **container/presentational** splits, and the powerful trick of passing a Server Component's children *through* a Client Component (RSC) so the children stay server-rendered. `children` can be any renderable value — element, array, string, number, or a function (the "render prop" pattern, where `children` is a function the component calls with data). Composition over configuration keeps components from sprouting dozens of boolean props and avoids prop drilling, because a parent can inject exactly the subtree it wants.

#### Q39. [Theory] What does it mean that "state updates are asynchronous," and how should you read the latest state?

When you call `setState`, React does not change the state variable immediately — it **schedules** a re-render. The current render's `state` variable is a snapshot, frozen for that render; reading it right after `setState` gives you the **old** value. This trips up newcomers who write `setCount(count + 1); console.log(count)` and see the stale number. React behaves this way deliberately so it can batch multiple updates into a single render for performance and keep each render's variables consistent ("each render has its own props and state").

```javascript
function handleClick() {
  setCount(count + 1);   // schedules; `count` here is still the old value
  setCount(count + 1);   // both read the SAME old `count` -> net +1, not +2
  // console.log(count) -> old value
}

// to apply multiple updates based on prior value, use the updater form:
function handleClickFixed() {
  setCount(c => c + 1);  // +1
  setCount(c => c + 1);  // +1 -> total +2, each sees the latest queued value
}
```

To read the value *after* it updates, respond in the next render (e.g., a `useEffect` keyed on the value) rather than trying to read it synchronously. The practical rules: (1) use the functional updater `setX(prev => ...)` whenever the next value depends on the previous; (2) don't expect the variable to change within the same event handler; (3) if you need a value that survives renders but must be read synchronously and mutated imperatively, that's a job for `useRef`, not state.

### 🟡 Intermediate — extended

#### Q40. [Theory] How does the Context API actually propagate updates, and why does every consumer re-render?

A `Context.Provider` holds a single `value`. React subscribes every component that calls `useContext(SomeContext)` to that provider. When the provider's `value` **reference** changes (by `Object.is` comparison), React schedules a re-render of **all** consumers below it — there is no built-in mechanism to subscribe to only part of the value. This is by design: context is a propagation/injection primitive, not a reactive store with selectors. The frequent footgun is passing a fresh object literal as `value` on every render, which makes the reference change every time even when the data is identical, re-rendering all consumers needlessly.

```javascript
// ❌ new object identity each render -> all consumers re-render every time
<UserCtx.Provider value={{ user, setUser }}>

// ✅ memoize so identity is stable unless deps change
const ctx = useMemo(() => ({ user, setUser }), [user]);
<UserCtx.Provider value={ctx}>
```

Two production strategies mitigate the re-render cost. First, **split contexts** by what changes and how often — a separate context for the dispatcher (stable) versus the state (changing) so dispatch-only consumers never re-render. Second, when you genuinely need slice-level subscriptions, use an external store with `useSyncExternalStore` (Zustand/Jotai) instead of context, because those support selectors that re-render only the components reading the changed slice. Wrapping intermediate components in `React.memo` also stops re-render propagation through parts of the tree that don't consume the context.

#### Q41. [Coding] Write a custom useLocalStorage hook that syncs state to localStorage and across tabs.

**Problem:** Persist a piece of state to `localStorage`, initialize from it on mount, and stay in sync when another tab changes the same key.

```javascript
import { useState, useEffect, useCallback } from "react";

function useLocalStorage(key, initialValue) {
  const [value, setValue] = useState(() => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw != null ? JSON.parse(raw) : initialValue;
    } catch {
      return initialValue;           // corrupt JSON / disabled storage
    }
  });

  // write-through on change
  useEffect(() => {
    try { window.localStorage.setItem(key, JSON.stringify(value)); }
    catch { /* quota exceeded / private mode */ }
  }, [key, value]);

  // cross-tab sync: the 'storage' event fires in OTHER tabs only
  useEffect(() => {
    const onStorage = e => {
      if (e.key === key && e.newValue != null) {
        try { setValue(JSON.parse(e.newValue)); } catch {}
      }
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, [key]);

  const set = useCallback(v => {
    setValue(prev => (typeof v === "function" ? v(prev) : v));
  }, []);

  return [value, set];
}
```

**Key decisions:** the lazy initializer (`useState(() => ...)`) reads storage exactly once instead of on every render. The write-through effect serializes on change. The `storage` event is the standard cross-tab channel — note it fires in *other* tabs, not the one that wrote, so no echo loop. **Edge cases:** wrap all storage access in try/catch (Safari private mode throws, quota can exceed, JSON can be corrupt); SSR has no `window`, so guard or only call in effects. **Time/Space:** O(size) to serialize; negligible otherwise. For SSR frameworks, return `initialValue` on the server and hydrate the real value after mount to avoid a mismatch.

#### Q42. [Practical] How do you implement code-splitting with React.lazy and Suspense, and what are the failure modes?

`React.lazy(() => import('./X'))` defers loading a component's code until it first renders, returning a component that **suspends** while the dynamic `import()` chunk downloads. You wrap it in `<Suspense fallback={...}>` to show a loader during the fetch. This shrinks the initial bundle by deferring routes, modals, heavy charts, and admin-only screens that most users never open. Route-level splitting is the highest-ROI place to start.

```javascript
import { lazy, Suspense } from "react";
const Dashboard = lazy(() => import("./Dashboard"));

function App() {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <Dashboard />
    </Suspense>
  );
}
```

The main failure mode is the **chunk-load error after a deploy**: a user with an open tab requests an old chunk hash that no longer exists on the CDN, and the import rejects. Handle it with an error boundary that offers a reload, and consider a retry wrapper that re-imports once before failing. Other pitfalls: putting the `Suspense` boundary too high causes a full-page spinner for a tiny lazy widget (place boundaries close to the lazy content); lazy-loading something needed immediately on first paint hurts perceived performance (don't split above-the-fold critical UI); and forgetting that named exports need an adapter (`import('./X').then(m => ({ default: m.Named }))`). Pair lazy loading with route-based **prefetching** (load the chunk on link hover/focus) so the navigation feels instant.

#### Q43. [Theory] What is the difference between derived state and stored state, and why is "syncing" props into state an anti-pattern?

**Stored state** is the minimal source of truth you keep in `useState`/`useReducer`. **Derived state** is anything you can compute from stored state or props *during render* — totals, filtered lists, formatted strings, validity flags. The principle is to store the minimum and derive the rest, because duplicating derivable data into separate state creates two sources of truth that drift out of sync. A classic anti-pattern is copying a prop into state (`useState(props.value)`): the state captures the prop only on mount and silently ignores later prop changes, producing stale UI.

```javascript
// ❌ duplicated state that can drift from `items`
const [count, setCount] = useState(items.length);

// ✅ derive during render — always correct, no sync needed
const count = items.length;

// ❌ "syncing" a prop into state — ignores later prop changes
function Profile({ user }) {
  const [name, setName] = useState(user.name); // stale if user changes
}
```

When you truly need to *reset* internal state when a prop changes, the idiomatic React solution is not a syncing effect but **changing the `key`** on the component so React remounts it fresh, or — rarely — adjusting state during render based on a stored "previous prop" value. The mental rule from the React docs: "if you can calculate it during render, you don't need state for it." Following this eliminates a large class of bugs and unnecessary `useEffect` calls whose only job was to keep two pieces of state aligned.

#### Q44. [Coding] Build a custom useToggle and useArray-style hook, and explain the value of encapsulating state logic in hooks.

**Problem:** Show how custom hooks package stateful logic for reuse, with two small examples: a boolean toggle and an array helper with immutable updates.

```javascript
import { useState, useCallback } from "react";

function useToggle(initial = false) {
  const [on, setOn] = useState(initial);
  const toggle = useCallback(() => setOn(o => !o), []);
  const setTrue = useCallback(() => setOn(true), []);
  const setFalse = useCallback(() => setOn(false), []);
  return { on, toggle, setTrue, setFalse };
}

function useArray(initial = []) {
  const [arr, setArr] = useState(initial);
  return {
    arr,
    push:   useCallback(item => setArr(a => [...a, item]), []),
    removeAt: useCallback(i => setArr(a => a.filter((_, idx) => idx !== i)), []),
    update: useCallback((i, item) =>
      setArr(a => a.map((x, idx) => (idx === i ? item : x))), []),
    clear:  useCallback(() => setArr([]), []),
  };
}

// usage
function Panel() {
  const modal = useToggle();
  const { arr, push, removeAt } = useArray([]);
  return <button onClick={modal.toggle}>{modal.on ? "Hide" : "Show"}</button>;
}
```

**Why this matters:** custom hooks are React's composition mechanism for *behavior* (as opposed to `children`, which composes *UI*). They let you extract and name a stateful concern once — toggling, pagination, form handling, data fetching — and reuse it across components without the wrapper-nesting of old HOC/render-prop approaches. Crucially, each component that calls a custom hook gets its **own isolated state**; hooks share logic, not state. The functional updaters keep the returned callbacks stable (`[]` deps) so they're safe to pass to memoized children. Keep hooks focused and composable — a hook can call other hooks — and start the name with `use` so lint rules apply.

#### Q45. [Practical] What strategies do you use to test React components, and where do unit, integration, and E2E tests fit?

The modern default is **React Testing Library (RTL)** on top of Jest or Vitest, whose guiding philosophy is to test behavior the way a user experiences it — query by accessible role/label/text, interact via `userEvent`, and assert on visible output — rather than testing implementation details like state variables or instance methods. Testing implementation details makes tests brittle: a refactor that preserves behavior shouldn't break the suite. Avoid `enzyme`-style shallow rendering of internals; prefer rendering the component and asserting what the DOM shows.

```javascript
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

test("increments the counter", async () => {
  render(<Counter />);
  await userEvent.click(screen.getByRole("button", { name: /increment/i }));
  expect(screen.getByText("1")).toBeInTheDocument();
});
```

The testing pyramid still applies. **Unit tests** cover pure functions, reducers, and small hooks (`@testing-library/react`'s `renderHook`). **Integration tests** — the highest-value tier for UIs — render a feature with its real children and a mocked network layer (MSW, Mock Service Worker, to intercept fetch/XHR at the network boundary so you don't stub `fetch` by hand). **E2E tests** (Playwright/Cypress) drive a real browser against a running app for critical user journeys (login, checkout). Practical guidance: lean on integration tests with MSW for most coverage, keep E2E focused on a few money-path flows because they're slow and flaky, mock time and randomness, and assert on accessible queries so tests double as an accessibility check.

### 🟠 Advanced — extended

#### Q46. [Theory] Explain how lanes/priorities work in React 18's scheduler and what startTransition actually does.

React 18 assigns every update a **lane** — a bitmask-encoded priority. Urgent interactions (typing, clicking, hovering) get high-priority lanes; updates wrapped in `startTransition` get a low-priority **transition** lane. The scheduler processes higher-priority lanes first and can **interrupt** an in-progress low-priority render to handle an urgent one, then resume or restart the transition. This is what keeps an input responsive while a heavy list re-renders from a state change: the keystroke is urgent and preempts the transition render of the list.

```javascript
import { useTransition, useState } from "react";

function Search({ allItems }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState(allItems);
  const [isPending, startTransition] = useTransition();

  function onChange(e) {
    setQuery(e.target.value);                // urgent: input stays snappy
    startTransition(() => {                   // non-urgent: heavy filter
      setResults(allItems.filter(i => i.includes(e.target.value)));
    });
  }
  return <>{isPending && <Spinner />}{/* render results */}</>;
}
```

`startTransition` does **not** make work faster or move it off the main thread (there are no worker threads here); it changes the work's **priority** so React can keep the previous UI interactive and even throw away and restart the transition render if newer input arrives. `isPending` lets you show subtle pending feedback while keeping the old content visible (avoiding a jarring fallback). The constraint: only state updates can be marked as transitions, the update must be non-urgent by nature, and you should avoid marking updates that the user expects to feel instant. `useDeferredValue` is the related primitive when you don't own the `setState` call — it gives you a lagging copy of a value at lower priority.

#### Q47. [Practical] You're debugging a memory leak in a long-lived React SPA. How do you find and fix it?

Long-lived SPAs leak when references outlive the components that created them. The usual suspects: event listeners or subscriptions added without cleanup, timers (`setInterval`) never cleared, observers (`IntersectionObserver`, `ResizeObserver`) not disconnected, closures captured by a long-lived store/context, and detached DOM nodes kept alive by a stale ref or cache. The symptom is heap growth over time and eventually jank or a crash. **Diagnosis** starts with Chrome DevTools: take a **heap snapshot**, exercise the suspected flow (mount/unmount a route repeatedly), take another snapshot, and use the "Comparison" view or the "Allocation instrumentation on timeline" to see what classes keep growing and what retains them (the retainer path points at the leak).

```javascript
// ❌ leak: listener and interval never removed; closure retains `node`
useEffect(() => {
  window.addEventListener("resize", onResize);
  const id = setInterval(poll, 1000);
});

// ✅ cleanup returns the unsubscribe/teardown
useEffect(() => {
  window.addEventListener("resize", onResize);
  const id = setInterval(poll, 1000);
  return () => {
    window.removeEventListener("resize", onResize);
    clearInterval(id);
  };
}, []);
```

**Fixes** map to causes: return cleanup functions from every effect that subscribes or schedules; abort in-flight fetches with `AbortController`; disconnect observers; clear caches with a bounded size or weak references (`WeakMap`/`WeakRef`) so entries don't pin objects forever; and avoid capturing large objects in closures stored in a global store. React 18 Strict Mode's intentional double mount/unmount in dev is a free leak detector — if a subscription doubles up after the second mount, your cleanup is missing. For verification, repeat the mount/unmount cycle many times and confirm the heap returns to baseline after a forced GC; a flat retained size after settling means the leak is gone.

#### Q48. [Theory] Compare the trade-offs between CSR, SSR, SSG, ISR, and streaming SSR for a React app.

These are rendering strategies trading off time-to-first-byte, time-to-interactive, freshness, and server cost. **CSR** (client-side rendering) ships a near-empty HTML shell plus JS that renders in the browser — cheap to host (static), but slow first paint and poor SEO for content pages. **SSR** renders HTML per request on the server — good first paint and SEO, fresh data, but higher server cost and TTFB depends on your slowest data fetch. **SSG** (static generation) renders HTML at build time — fastest and cheapest to serve via CDN, but stale until rebuilt and impractical for millions of pages or per-user content. **ISR** (incremental static regeneration) serves static pages but revalidates them in the background on a TTL — combining SSG speed with eventual freshness. **Streaming SSR** sends HTML in chunks as data resolves (React 18 `renderToPipeableStream` + Suspense), so the shell paints immediately and slow sections stream in.

```
Strategy        First paint   Freshness        Server cost   Best for
CSR             slow          live (client)    low (static)  apps behind login, dashboards
SSR             fast          per-request      high          personalized, SEO-critical, dynamic
SSG             fastest       build-time       lowest        marketing, docs, blogs
ISR             fastest       TTL revalidate   low           large catalogs that change slowly
Streaming SSR   fast (shell)  per-request      high          data-heavy pages with slow sections
```

In practice modern frameworks (Next.js App Router, Remix) let you choose **per route** and even mix strategies in one page via Suspense streaming and RSC, so the real answer is "it depends on the route." A marketing home page is SSG/ISR; a personalized feed is SSR or streaming SSR with a fast shell; an authenticated internal dashboard can be CSR because SEO is irrelevant and the static shell caches well. The senior framing is to map each route to its constraints (SEO need, personalization, data freshness, traffic, cost) rather than picking one strategy for the whole app.

#### Q49. [Coding] Implement a useIntersectionObserver hook for lazy-loading / infinite scroll.

**Problem:** A reusable hook that tells you when a target element enters the viewport, used for lazy-loading images or triggering "load more" on infinite scroll — without scroll-event spam.

```javascript
import { useState, useEffect, useRef } from "react";

function useIntersectionObserver(options = {}) {
  const ref = useRef(null);
  const [entry, setEntry] = useState(null);

  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === "undefined") return;

    const observer = new IntersectionObserver(([e]) => setEntry(e), options);
    observer.observe(node);
    return () => observer.disconnect();      // cleanup: avoid leak
  }, [options.root, options.rootMargin, options.threshold]);

  return [ref, entry?.isIntersecting ?? false, entry];
}

// usage: infinite scroll sentinel
function Feed({ loadMore, hasMore }) {
  const [sentinelRef, isVisible] = useIntersectionObserver({ rootMargin: "200px" });
  useEffect(() => {
    if (isVisible && hasMore) loadMore();
  }, [isVisible, hasMore, loadMore]);
  return <div ref={sentinelRef} />;          // observed sentinel at list end
}
```

**Why IntersectionObserver over scroll handlers:** scroll listeners fire continuously and force layout reads, causing jank; the observer is asynchronous, runs off the main thread's critical path, and notifies you only on threshold crossings. The `rootMargin: "200px"` preloads content before it's actually visible for a smoother experience. **Edge cases:** guard for SSR (no `IntersectionObserver`), re-create the observer when options change (note: passing a fresh `options` object every render would re-run the effect, so destructure primitive deps as shown or memoize the options object), and disconnect on unmount. **Time/Space:** O(1) per observed node; the browser batches callbacks efficiently even with many targets.

#### Q50. [Practical] How do you profile and optimize a React app's runtime performance in production? Describe the workflow.

The workflow is **measure → attribute → fix → verify**, never guess. Start with the **React DevTools Profiler**, which records commits and shows each component's render duration and *why* it rendered (props changed, state changed, parent rendered, context changed). The "Ranked" view surfaces the most expensive components; the "why did this render" annotation (enable "Record why each component rendered") points straight at the cause — usually an unstable prop, an over-broad context, or a missing `memo`. Complement it with the browser's Performance panel to see long tasks, scripting vs layout time, and dropped frames, and with **Web Vitals** (LCP, INP, CLS) measured on real users via `web-vitals` reporting to your analytics — lab numbers lie about real devices.

```
1. Reproduce the slow interaction with the Profiler recording.
2. Find the expensive commit; read "why did this render".
3. Attribute: unstable props? big context? un-virtualized list? heavy compute in render?
4. Fix the specific cause (memoize/stabilize, virtualize, move state down, defer with transition).
5. Re-record; confirm the commit count/duration dropped. Watch INP in field data.
```

**Common fixes, matched to causes:** un-virtualized long lists → `react-window`/TanStack Virtual; whole-tree re-renders from context → split contexts or move to a selector store; expensive derived computation in render → `useMemo`; new function/object identities defeating `memo` → `useCallback`/`useMemo` (or adopt the React Compiler to do this automatically); heavy synchronous work blocking input → `startTransition`/`useDeferredValue`; large initial bundle → code-split with `React.lazy` and analyze with a bundle analyzer. The senior discipline is to attach a *number* to the problem and the fix — "INP dropped from 320ms to 90ms on the search interaction" — and to monitor field metrics continuously rather than declaring victory from a one-off local profile on a fast laptop.

#### Q51. [Theory] What problems do forwardRef and useImperativeHandle solve, and how does React 19 change refs?

By default a `ref` you pass to a component is not forwarded to any DOM node inside it — parents can't reach a child's internal element. `forwardRef` (React ≤18) lets a component receive a `ref` as a second argument and attach it to one of its own elements, enabling parents to focus an input, scroll a container, or measure a node inside a reusable component. `useImperativeHandle` goes further: it lets the child expose a **curated imperative API** through the ref instead of the raw DOM node — for example `{ focus(), scrollToTop(), validate() }` — so the parent gets a controlled surface area rather than full DOM access.

```javascript
import { useRef, useImperativeHandle, forwardRef } from "react";

const Field = forwardRef(function Field(props, ref) {
  const inputRef = useRef(null);
  useImperativeHandle(ref, () => ({
    focus: () => inputRef.current.focus(),
    clear: () => { inputRef.current.value = ""; },
  }), []);
  return <input ref={inputRef} {...props} />;
});

// parent
function Form() {
  const fieldRef = useRef(null);
  return <><Field ref={fieldRef} /><button onClick={() => fieldRef.current.focus()}>Focus</button></>;
}
```

**React 19 simplifies this:** `ref` becomes a regular prop, so function components can accept `ref` directly without `forwardRef` (which is now deprecated and slated for removal). You write `function Field({ ref, ...props })` and pass it straight through. `useImperativeHandle` remains useful when you want to expose a custom imperative API rather than a DOM node. The design guidance hasn't changed: imperative handles are an **escape hatch** — prefer declarative props/state, and reach for refs only for genuinely imperative operations (focus, scroll, media playback, integrating non-React libraries) where there's no declarative equivalent.

### 🔴 Expert — extended

#### Q52. [Theory] Deep-dive: what exactly happens during the render phase versus the commit phase, and why must render be pure?

A React update proceeds in two phases. In the **render (reconcile) phase**, React calls your component functions to produce a new fiber tree and diffs it against the current one, marking fibers with effect flags (placement, update, deletion). This phase is **interruptible**: the concurrent scheduler can pause it, yield to the browser, abort it if a higher-priority update arrives, and **restart it from scratch** later. Because of this, the render phase may run your component function **multiple times for a single visible update**, and any work you do there can be discarded. The **commit phase**, by contrast, is synchronous and runs to completion: React applies the marked DOM mutations, then runs `useLayoutEffect`/lifecycle effects synchronously before paint, and schedules `useEffect` to run asynchronously after paint.

```
RENDER PHASE (pure, interruptible)            COMMIT PHASE (sync, atomic)
  call components -> build fiber tree            mutate DOM (insert/update/delete)
  diff vs current -> mark effect flags           run refs + useLayoutEffect (pre-paint)
  may pause / abort / restart / re-run    -->    [browser paints]
  MUST have no side effects                      schedule useEffect (post-paint)
```

This architecture is *why* render must be pure: no mutating external variables, no DOM writes, no network calls, no `ref.current` mutation during render. If render had side effects, the renderer's ability to run it speculatively and throw the work away would produce duplicated requests, doubled mutations, and inconsistent state. Strict Mode deliberately double-invokes render (and mounts effects twice) in development to surface impurity early. Side effects belong in the commit-phase hooks (`useEffect`/`useLayoutEffect`) or in event handlers — places React guarantees run exactly when the UI actually commits, not during speculative rendering.

#### Q53. [Practical] You see a production hydration mismatch causing flicker and a console error. Walk through root-causing and fixing it systematically.

A hydration mismatch means the HTML the server rendered differs from what the client renders on its first pass, so React discards and re-renders the affected subtree — causing visible flicker, layout shift, and (pre-19) sometimes a fully client-rendered fallback for the whole tree. **Root-causing** starts with the console error, which in React 18+ names the mismatched element and often the differing text. Then enumerate the usual causes: non-deterministic values (`Date.now()`, `Math.random()`, `new Date().toLocaleString()` with server/client timezone or locale differences), reading browser-only APIs (`window`, `localStorage`, `matchMedia`, `navigator`) during render, user-locale or A/B-test branching that differs server vs client, and **invalid HTML nesting** (a `<div>` inside `<p>`, a `<table>` without `<tbody>`) that the browser auto-corrects, changing the DOM out from under React.

```javascript
// ❌ server renders one time, client renders another -> mismatch + flicker
function Clock() { return <span>{new Date().toLocaleTimeString()}</span>; }

// ✅ render a stable value on server, fill in client-only value after mount
function Clock() {
  const [time, setTime] = useState(null);            // null on server + first client render
  useEffect(() => { setTime(new Date().toLocaleTimeString()); }, []);
  return <span suppressHydrationWarning>{time ?? "--:--:--"}</span>;
}
```

**Systematic fix:** (1) make render deterministic — move any value that depends on the browser, current time, or randomness out of the initial render and set it in a `useEffect` after mount, so server and first client render agree; (2) for genuinely intentional differences (a timestamp), use `suppressHydrationWarning` on that specific node — but sparingly, as it silences real bugs too; (3) fix invalid nesting so the browser doesn't rewrite the DOM; (4) ensure feature flags / experiments resolve to the same value on both sides (read the cookie on the server and pass it down, rather than reading it only on the client). Verify by disabling JS to inspect the raw server HTML, diffing it mentally against the client's first render, and confirming the console error is gone with no flash on reload. The governing principle: the **first** client render must be a pure function of the same inputs the server had.

#### Q54. [Theory] In a React Server Components architecture, what crosses the server/client boundary, and what are the rules and pitfalls?

In an RSC app the tree is split: Server Components (the default in the App Router) run only on the server and Client Components (marked `"use client"`) run on the server for initial HTML *and* in the browser. The boundary has strict rules. **Props passed from a Server Component to a Client Component must be serializable** — plain objects, arrays, strings, numbers, dates, and (specially supported) functions that are Server Actions; you **cannot** pass non-serializable values like class instances, raw functions/event handlers, `Symbol`s, or Map/Set with cyclic refs. A Client Component cannot import a Server Component directly, but a Server Component can **pass a Server Component as `children`/props** to a Client Component, which is the key pattern for keeping server-rendered content inside a client interactive shell without shipping its code.

```
Server Component (no JS shipped)
  ├─ reads DB / secrets / fs directly (await)
  ├─ passes serializable props ─────────► "use client" Component (hydrates)
  └─ passes <ServerChild/> as children ─► rendered on server, slotted into client tree
        (the client component never imports ServerChild's code)
```

**Pitfalls and rules:** the `"use client"` directive marks an **entry point** — everything imported by that module becomes part of the client bundle, so a stray `"use client"` high in the tree can drag huge amounts of code to the client (keep the boundary as low as possible). Server-only secrets must never be imported into a client module; use the `server-only` package to fail the build if that happens. Hooks like `useState`/`useEffect`/`useContext` and browser APIs only work in client components. Data fetching belongs in server components (`async` components with `await`), and mutations use **Server Actions** (`"use server"`) which let client components call server functions without hand-writing an API route. The senior mental model: design from the server outward, push interactivity to small client leaves, and treat the serialization boundary as an API contract you version carefully.

#### Q55. [Coding] Implement a Suspense-compatible resource cache and the use() pattern for data fetching.

**Problem:** Show how Suspense-for-data works under the hood by building a minimal promise cache whose read either returns data, throws a promise (to suspend), or throws an error — the contract React's `use()` hook and Suspense-ready libraries implement.

```javascript
// A tiny cache that makes a promise "Suspense-readable"
function createResource(promise) {
  let status = "pending";
  let result;
  const suspender = promise.then(
    data => { status = "success"; result = data; },
    err  => { status = "error";   result = err;  }
  );
  return {
    read() {
      if (status === "pending") throw suspender;  // suspend: React waits on this promise
      if (status === "error")   throw result;     // surfaced to the nearest error boundary
      return result;                              // success: return data synchronously
    },
  };
}

// module-level cache so the same key reuses one in-flight promise (no waterfalls/dupes)
const cache = new Map();
function fetchUser(id) {
  if (!cache.has(id)) cache.set(id, createResource(fetch(`/api/users/${id}`).then(r => r.json())));
  return cache.get(id);
}

function Profile({ id }) {
  const user = fetchUser(id).read();   // reads synchronously or suspends
  return <h1>{user.name}</h1>;
}

// <Suspense fallback={<Skeleton/>}><Profile id={1} /></Suspense>
```

**The contract:** Suspense works because a component can **throw a promise** during render; React catches it, shows the nearest boundary's fallback, subscribes to the promise, and retries the render when it resolves. On error it throws to the nearest error boundary instead. React 19's `use(promise)` formalizes this — you call `use(somePromise)` and it suspends/returns/throws following the same rules, and it may be called conditionally (unlike other hooks). **Why a cache is mandatory:** without one, every render creates a new promise and you suspend forever; the cache ensures the same key yields the same promise so the second render finds the resolved value. **Edge cases:** real implementations need cache invalidation, request dedup, and integration with a transition so navigating doesn't flash fallbacks — which is exactly why production code uses TanStack Query or a framework's data layer rather than this hand-rolled version. This exercise is about understanding the *mechanism*, not shipping it.

#### Q56. [Theory] How does React batch updates internally, and how did automatic batching in React 18 change edge cases that apps relied on?

Batching means React collects multiple state updates and processes them in a single render+commit instead of one render per `setState`. In React 17, batching only happened inside React's own synthetic event handlers (a click, a change) because those ran inside React's `batchedUpdates` wrapper. Updates fired in `setTimeout`, native event listeners, promise callbacks, or after `await` escaped that wrapper and each triggered a separate synchronous render. React 18's **automatic batching** extends batching to *all* contexts by routing every update through the scheduler, so two `setState`s in a `fetch().then()` now cause one render, not two — eliminating intermediate renders and tearing-like flashes.

```javascript
// React 17: 2 renders (timeout escapes batching). React 18: 1 render (auto-batched).
setTimeout(() => {
  setCount(c => c + 1);
  setFlag(f => !f);
}, 0);

// opt OUT of batching when you must force a synchronous DOM update mid-event:
import { flushSync } from "react-dom";
flushSync(() => setSelected(id));   // commits now, before the next line reads the DOM
```

The migration edge case is code that *relied* on un-batched behavior — for example, setting state and then immediately reading the DOM or another state inside the same async tick, expecting the first update to have already committed. Under automatic batching that intermediate commit no longer happens, which can break layout-measurement code or sequencing assumptions. The escape hatch is **`flushSync`**, which forces React to commit a specific update synchronously (use it sparingly — it defeats batching and hurts performance). Another subtlety: automatic batching plus concurrent rendering means you should never assume a render happened between two `setState` calls; if you need to act *after* state commits, do it in an effect keyed on the value. Most apps benefit silently, but senior engineers auditing a 17→18 upgrade specifically look for DOM-measurement-after-setState and manual-flush assumptions.

#### Q57. [Practical] Describe building an accessible, production-grade modal/dialog in React. What are the hard parts?

A modal looks trivial but is one of the most commonly botched components because accessibility and focus management are subtle. The hard parts: **focus trapping** (Tab/Shift+Tab must cycle within the dialog, never escaping to the page behind it), **focus restoration** (return focus to the trigger element on close), **scroll locking** the background, **inert-ing** the background so screen readers and pointer events don't reach it, correct **ARIA** (`role="dialog"` or `alertdialog`, `aria-modal="true"`, `aria-labelledby`/`aria-describedby`), **Escape-to-close** and click-outside-to-close, and rendering via a **portal** so the dialog escapes parent `overflow:hidden`/stacking-context/`z-index` traps while staying logically inside the React tree for context and events.

```javascript
import { createPortal } from "react-dom";
import { useEffect, useRef } from "react";

function Modal({ open, onClose, labelId, children }) {
  const ref = useRef(null);
  const lastFocused = useRef(null);

  useEffect(() => {
    if (!open) return;
    lastFocused.current = document.activeElement;     // remember trigger
    ref.current?.focus();
    const onKey = e => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";          // scroll lock
    return () => {                                     // cleanup + restore focus
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
      lastFocused.current?.focus();
    };
  }, [open, onClose]);

  if (!open) return null;
  return createPortal(
    <div className="overlay" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-labelledby={labelId} tabIndex={-1}
           ref={ref} onClick={e => e.stopPropagation()}>
        {children}
      </div>
    </div>,
    document.body
  );
}
```

The snippet shows the skeleton, but a truly correct implementation also needs a real focus trap (a `<dialog>` element or a library handles the Tab-cycle edge cases), background `inert` (`<div inert>` on the app root, or `aria-hidden` plus pointer/scroll blocking), and care that the portal doesn't break event bubbling assumptions (React portals bubble events through the React tree, not the DOM tree — usually what you want, occasionally surprising). The pragmatic senior answer is to **use a vetted headless primitive** (Radix Dialog, React Aria, or the native HTML `<dialog>` with `showModal()`) rather than reimplement focus management, because the accessibility edge cases (nested dialogs, focus trap with no focusable children, mobile screen readers, `prefers-reduced-motion`) are extensive and easy to get subtly wrong. Reserve a hand-rolled version for when you need behavior no library offers, and even then build on the native `<dialog>`.

#### Q58. [Theory] What are the trade-offs of runtime CSS-in-JS versus zero-runtime styling in modern React, especially with RSC?

Runtime CSS-in-JS libraries (styled-components, Emotion) generate and inject styles *during render* in the browser. Their developer experience is excellent — co-located styles, dynamic theming via props, dead-code elimination per component — but they impose a **runtime cost**: serializing styles, generating class names, and injecting `<style>` tags on every relevant render, plus shipping the styling engine in the bundle. With **React Server Components and streaming SSR** this model is a poor fit: runtime CSS-in-JS depends on rendering to collect styles and on React context/hooks that don't exist in Server Components, so these libraries can't run in server components and complicate streaming (you can't always collect all styles before flushing the first chunk).

```
Approach            Runtime cost   Dynamic-by-prop   RSC/streaming fit   Example
Runtime CSS-in-JS   high           excellent         poor                styled-components, Emotion
Zero-runtime CSS-JS none (build)   limited           good                vanilla-extract, Linaria, PandaCSS
Utility CSS         none           via class toggles good                Tailwind
Plain CSS/Modules   none           via variables     good                CSS Modules + CSS custom props
```

The industry has shifted toward **zero-runtime** approaches for new RSC-based apps: **CSS Modules** and plain CSS with **CSS custom properties** for theming, utility frameworks like **Tailwind**, or compile-time CSS-in-JS (**vanilla-extract**, **Linaria**, **PandaCSS**) that extract static CSS files at build time and use CSS variables for the dynamic parts. These have no per-render cost, ship as cacheable static CSS, and work seamlessly in Server Components because styling becomes a build artifact rather than a render-time computation. The trade-off you give up is fully arbitrary per-render style computation, which you replace with CSS variables and `data-` attribute variants. For a senior recommendation: on a fresh App Router project, default to Tailwind or CSS Modules + custom properties (or vanilla-extract for typed CSS-in-JS ergonomics) and avoid runtime CSS-in-JS unless a legacy constraint forces it; theme via CSS variables so the dynamic parts cost nothing at runtime and SSR/RSC just work.

#### Q59. [Practical] A senior engineer must lead a React 17 → 18 → 19 upgrade across a large monorepo. Lay out the concrete plan, risks, and rollback strategy.

Treat it as a sequenced, reversible program, not a flag day. **React 17 → 18:** the mechanical change is swapping `ReactDOM.render(el, root)` for `createRoot(root).render(el)` (and `hydrate` → `hydrateRoot`), which opts you into the new renderer. The biggest behavioral shifts to audit are **automatic batching** (look for code reading state/DOM synchronously after `setState` in async callbacks; add `flushSync` only where genuinely needed) and **Strict Mode double-invocation** of effects in dev (which exposes missing cleanup). Bump all React-ecosystem libraries to 18-compatible versions in lockstep — anything reading external mutable state must have migrated to `useSyncExternalStore` or it can tear. **18 → 19:** adopt the new `ref`-as-prop (remove `forwardRef`), the `use()` hook, Actions/`useActionState`/`useOptimistic` for forms, and the **React Compiler** (incrementally — turn on its ESLint rule, fix rule-of-React violations, then enable it per-package and delete now-redundant `useMemo`/`useCallback`).

```bash
# representative sequencing in a monorepo
1. Pin & upgrade react/react-dom + types in one PR per package; CI must stay green.
2. Switch entrypoints to createRoot/hydrateRoot behind a build flag.
3. Run full E2E + visual-regression suite; enable Strict Mode in dev to surface impurity.
4. Roll out behind a feature flag / canary to a % of traffic; watch error rate + Web Vitals (INP).
5. Bake, then ramp to 100%. Repeat the cycle for 19. Adopt the Compiler last, package by package.
```

**Risks:** library incompatibility (mismatched React copies cause "invalid hook call" — dedupe React to a single version in the monorepo via resolutions/overrides); latent impurity that automatic batching or Strict Mode now exposes; third-party code that mutates state without `useSyncExternalStore`; and CSS-in-JS/SSR integrations that need updated React 18 adapters. **Rollback strategy:** keep each step behind a flag or a separate deployable so you can revert the renderer swap or the canary cohort instantly without a code revert; gate the rollout on real-user metrics (error rate, INP, hydration errors) with automatic rollback thresholds; and never combine the renderer upgrade and the Compiler adoption in the same release so a regression has a single attributable cause. The discipline that de-risks the whole thing is strong E2E + visual-regression coverage and a canary with fast rollback, so you learn from production gradually instead of betting the whole monorepo on one cutover.

#### Q60. [Theory] What is "lifting state up" versus "colocation," and how do you decide where a piece of state should live?

These are two halves of the same placement question. **Lifting state up** moves shared state to the closest common ancestor of the components that need it, so a single source of truth flows down as props — necessary when two siblings must stay in sync (a filter input and the list it filters). **Colocation** is the opposite pressure: keep state as close as possible to where it's used, pushing it *down* when only one subtree needs it. The two are not contradictory; the rule is "lift to the lowest common ancestor that needs it, and no higher." State lifted too high causes unrelated subtrees to re-render and turns the top component into a god-object; state buried too low forces awkward prop drilling or callback gymnastics when a sibling later needs it.

```
        App                      Decide by: who READS and who WRITES this state?
       /   \
   Sidebar  Main                 - read/written by one component  -> local useState there
            /  \                 - shared by siblings A & B        -> lift to their parent
          A     B                - needed app-wide, low-frequency  -> Context (split by freq)
                                 - server data                     -> TanStack Query (not "state")
                                 - frequent global w/ selectors    -> Zustand/Jotai store
```

The decision procedure I use: (1) identify every component that reads the value and every one that writes it; (2) the state must live at or above their lowest common ancestor; (3) if that ancestor is "the whole app" and the value changes rarely, use context (split state/dispatch to limit re-renders); if it changes frequently with many independent readers, use a selector-based store; (4) if the data actually originates from the server, it isn't really client state — model it as a cache with TanStack Query/SWR rather than lifting raw fetched data into a top-level `useState`. The deeper insight is recognizing that much "state" is either **derivable** (compute during render, don't store) or **server cache** (manage with a query library), so the genuinely-shared-client-state that needs lifting is a smaller set than beginners assume. Getting placement right is the single biggest lever on both re-render performance and maintainability.

#### Q61. [Practical] How do you implement optimistic UI updates correctly, including rollback on failure? Show the React 19 approach.

Optimistic UI updates the interface *immediately* as if a mutation succeeded, then reconciles with the server response — making the app feel instant. The correctness challenge is **rollback**: if the request fails, you must revert to the prior state and surface the error, and you must handle race conditions where multiple optimistic updates are in flight. The manual pattern snapshots the previous state, applies the optimistic change, fires the request, and on failure restores the snapshot. React 19 provides **`useOptimistic`**, which integrates with transitions/Actions so the optimistic value automatically reverts when the underlying action settles, eliminating most of the manual bookkeeping.

```javascript
import { useOptimistic, useState, startTransition } from "react";

function LikeButton({ post, like }) {
  const [likes, setLikes] = useState(post.likes);
  const [optimisticLikes, addOptimistic] = useOptimistic(likes, (cur, delta) => cur + delta);

  function onLike() {
    startTransition(async () => {
      addOptimistic(+1);                 // UI updates instantly
      try {
        const updated = await like(post.id);   // server action
        setLikes(updated.likes);               // commit real value
      } catch {
        // no manual rollback: when the action settles, useOptimistic
        // discards the optimistic delta and reverts to `likes`
        showToast("Couldn't like. Try again.");
      }
    });
  }
  return <button onClick={onLike}>♥ {optimisticLikes}</button>;
}
```

The mechanism: `useOptimistic` derives a display value by applying your reducer to optimistic actions on top of the real state; while the surrounding transition is pending it shows the optimistic value, and once the action completes (success or failure) it falls back to the actual state you set. This handles rollback automatically and composes with multiple concurrent optimistic updates. **Production considerations regardless of API:** debounce/coalesce rapid optimistic actions, key updates by entity so concurrent mutations don't clobber each other, reconcile with the authoritative server value rather than trusting the optimistic guess permanently, and always provide error feedback (a toast or inline message) so a silent revert doesn't confuse the user. With a data library like **TanStack Query**, the equivalent is `onMutate` (snapshot + optimistic write to the cache), `onError` (rollback to the snapshot), and `onSettled` (invalidate to refetch the truth) — the same three-step contract: optimistic apply, rollback on failure, reconcile on settle.

#### Q62. [Practical] What are the most common anti-patterns you look for in a React code review, and why are they harmful?

Beyond the well-known index-key and missing-dependency issues, here are the recurring anti-patterns I flag. **Effects that derive data** — `useEffect(() => setFiltered(items.filter(...)), [items])` — add an extra render and a sync bug surface where a plain `const filtered = items.filter(...)` during render is correct and simpler ("you might not need an effect"). **Storing props in state** to "initialize" them captures a stale snapshot. **Creating components inside render** (`function Row() {...}` defined in the parent body) gives the component a new identity every render, so React unmounts and remounts it, destroying its state and DOM on each parent render. **Mutating state directly** (`state.items.push(x); setState(state)`) defeats reference-equality bail-outs so nothing updates, or updates unpredictably.

```javascript
// ❌ component defined during render -> remounts every parent render
function Parent() {
  function Child() { return <input />; }   // new identity each render
  return <Child />;
}
// ✅ define components at module scope
function Child() { return <input />; }
function Parent() { return <Child />; }

// ❌ mutation: same reference -> React skips the update
setUser(u => { u.name = "Ada"; return u; });
// ✅ new reference
setUser(u => ({ ...u, name: "Ada" }));
```

Others I watch for: **giant single contexts** with fast-changing data (whole-tree re-renders), **inline object/array/function literals** passed to memoized children (defeats `React.memo`), **`useEffect` with no dependency array** doing data fetching (runs every render), **business logic crammed into JSX** instead of computed above the return, **side effects during render** (network calls, ref mutation — illegal under concurrent rendering), and **over-memoization** that adds comparison cost with no profiled benefit. The common thread is that each violates one of React's core contracts — purity of render, identity stability, single source of truth, or immutability — and the harm ranges from subtle stale UI to remount-induced data loss. In review I tie each comment to the contract it breaks and, where possible, to a concrete user-visible symptom, because "this re-renders the whole tree on every keystroke" lands better than "this is unidiomatic."

#### Q63. [Theory] How do React's synthetic events work, and what changed about event delegation in React 17+?

React wraps native DOM events in a **SyntheticEvent** — a cross-browser normalized wrapper with a consistent API (`e.preventDefault()`, `e.stopPropagation()`, `e.target`) regardless of browser quirks. Rather than attaching a listener to every element, React uses **event delegation**: it attaches a small number of listeners at a root and lets events bubble up to it, then dispatches synthetic events through the React component tree. This is far more memory-efficient than per-element native listeners for large trees and lets React control batching and priority around event handling.

```javascript
// You write handlers normally; React delegates under the hood.
<button onClick={e => { e.stopPropagation(); /* stops React-tree propagation */ }} />
```

The key change in **React 17** was moving event delegation from `document` to the **React root container** (the DOM node you render into). Before 17, all delegation happened at `document`, which broke when you had multiple React versions on one page or embedded a React app inside a non-React app — a `stopPropagation` in one React tree could interfere with another, and gradual upgrades were painful. Attaching at the root container isolates each React app's event system, enabling **incremental adoption** (multiple React versions coexisting) and cleaner integration with non-React code. Practical implications for senior engineers: `e.stopPropagation()` stops propagation within React's synthetic system but native listeners attached directly to the DOM may still fire (and vice versa), so mixing native `addEventListener` with React handlers requires care about ordering; and because delegation is at the root, a native listener on `document` sees events *after* React's root listener, not before. Note also that React 17 removed event pooling (you no longer need `e.persist()`), so synthetic events can be safely accessed asynchronously.

#### Q64. [Coding] Implement a useThrottle hook and explain when to choose throttling versus debouncing.

**Problem:** Return a value that updates at most once per `interval` ms (throttle), useful for scroll/resize/mousemove handlers where you want regular updates during continuous activity rather than only after it stops.

```javascript
import { useState, useRef, useEffect } from "react";

function useThrottle(value, interval = 200) {
  const [throttled, setThrottled] = useState(value);
  const lastRun = useRef(Date.now());

  useEffect(() => {
    const sinceLast = Date.now() - lastRun.current;
    if (sinceLast >= interval) {
      lastRun.current = Date.now();
      setThrottled(value);                 // leading-edge: update immediately
    } else {
      // trailing-edge: schedule the remaining time so we don't drop the last value
      const id = setTimeout(() => {
        lastRun.current = Date.now();
        setThrottled(value);
      }, interval - sinceLast);
      return () => clearTimeout(id);
    }
  }, [value, interval]);

  return throttled;
}
```

**Throttle vs debounce — the core difference:** debounce waits for a *pause* (fires once after activity stops for `delay` ms), while throttle fires at a *steady cadence* during continuous activity (at most once per `interval`). Use **debounce** for "act after the user finishes": search-as-you-type (don't query on every keystroke, query when they pause), autosave, validating a field after typing stops. Use **throttle** for "act regularly during a continuous stream": scroll position tracking, drag/resize handlers, mousemove, analytics sampling — where waiting for a full stop would mean no updates at all during a long scroll.

```
Continuous events:  | | | | | | | | | | | |   (e.g. scroll)
Debounce (300ms):                          ▲   (only after it stops)
Throttle (300ms):   ▲     ▲     ▲     ▲     ▲   (regular cadence during activity)
```

**Edge cases:** this implementation includes both leading-edge (immediate first update) and trailing-edge (a final scheduled update so the last value isn't dropped) behavior — naive throttles that only do leading-edge can miss the final value. **Time/Space:** O(1), one outstanding timer. For production, libraries like lodash's `throttle`/`debounce` offer configurable leading/trailing options, and for raw DOM scroll work `requestAnimationFrame` is often a better throttle than a time-based one because it aligns updates to frames.

#### Q65. [Theory] What is the actual cost model of the virtual DOM, and when is it slower than alternatives like fine-grained reactivity (Solid/Svelte)?

The virtual DOM is not free — its cost model is: on each update React re-runs the component functions in the affected subtree, allocates a new tree of element objects, diffs it against the previous tree, and then applies the minimal DOM mutations. The **DOM mutation** step is genuinely minimal, but the **re-render + diff** step is proportional to the size of the subtree that re-rendered, *whether or not* the output actually changed. This is why an unmemoized parent re-rendering cascades work through all descendants even when their props are identical, and why React relies on `memo`/`useMemo` (or the React 19 Compiler) to prune that work. The VDOM's win is a simple, predictable mental model (`UI = f(state)`, re-render the world and let the diff sort it out) and decoupling your code from imperative DOM updates.

```
React (VDOM):  state change -> re-run components -> build new tree -> diff -> patch DOM
               cost ∝ size of re-rendered subtree (even for unchanged output)

Solid/Svelte:  state change -> run ONLY the precise effects that depend on that state
               cost ∝ number of actual changes (no component re-run, no diff)
```

**Fine-grained reactivity** (SolidJS signals, Svelte's compiled reactivity, Vue's reactivity) takes a different approach: it tracks dependencies at the level of individual values, so changing one signal runs only the exact DOM updates that depend on it — no component function re-run, no tree diff. For update-heavy, high-frequency UIs (dense dashboards, real-time grids, animations driven by state), this is measurably faster and uses less memory because it does work proportional to *what changed*, not *what's on screen*. React's counter-moves are the Compiler (auto-memoization to skip unchanged subtrees) and concurrent rendering (slicing work to stay responsive), which narrow but don't erase the gap. The honest senior take: React's VDOM is "fast enough" for the vast majority of apps and its ecosystem, mental model, and team familiarity usually outweigh raw update throughput; you'd reach for Solid/Svelte specifically when you have a profiled, update-bound bottleneck (thousands of frequently-changing nodes) where eliminating re-render-and-diff overhead is the deciding factor. Choosing a framework on micro-benchmarks alone is a classic premature optimization.

## 🧩 Extended Questions — Supplemental Set: Mixed Depth

### 🟢 Basic — extended

#### Q66. [Theory] What is a Fragment and why does it exist?

A **Fragment** lets you return multiple sibling elements from a component without adding an extra wrapper DOM node. Because a component must return a single root, beginners reflexively wrap siblings in a `<div>`, which pollutes the DOM, breaks CSS layouts that rely on direct-child relationships (fl/grid, `table > tr`), and adds unnecessary nesting. A Fragment renders its children directly into the parent with **zero** DOM output of its own.

```javascript
import { Fragment } from "react";

// shorthand <>...</> — most common
function Row() {
  return (
    <>
      <td>Name</td>
      <td>Email</td>
    </>
  );
}

// long form is required when you need a key (e.g. in a list)
function List({ items }) {
  return items.map(i => (
    <Fragment key={i.id}>
      <dt>{i.term}</dt>
      <dd>{i.def}</dd>
    </Fragment>
  ));
}
```

The trade-off worth knowing: the shorthand `<>` syntax cannot take a `key` or any other prop, so when you render a Fragment inside `.map()` you must use the explicit `<Fragment key={...}>` form. Fragments matter most for valid HTML in tables and definition lists where an intervening `<div>` would be illegal, and for keeping CSS grid/flex parent-child relationships intact. They are a small feature that prevents a large category of "div soup" and broken-layout bugs.

#### Q67. [Practical] How do you handle forms with multiple fields without a form library?

For a handful of fields you can hold one state object and use a single `onChange` keyed by the input's `name` attribute, which scales far better than one `useState` per field. The `name`-based handler reads `e.target.name` and `e.target.value`, so adding a field is just adding markup. For checkboxes you read `e.target.checked` instead of `value`; for `<select multiple>` or number inputs you coerce appropriately.

```javascript
import { useState } from "react";

function SignupForm({ onSubmit }) {
  const [form, setForm] = useState({ email: "", password: "", remember: false });
  const [errors, setErrors] = useState({});

  const handleChange = e => {
    const { name, value, type, checked } = e.target;
    setForm(f => ({ ...f, [name]: type === "checkbox" ? checked : value }));
  };

  const validate = () => {
    const next = {};
    if (!/^[^@]+@[^@]+$/.test(form.email)) next.email = "Invalid email";
    if (form.password.length < 8) next.password = "Min 8 characters";
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const submit = e => {
    e.preventDefault();
    if (validate()) onSubmit(form);
  };

  return (
    <form onSubmit={submit} noValidate>
      <input name="email" value={form.email} onChange={handleChange} />
      {errors.email && <small>{errors.email}</small>}
      <input name="password" type="password" value={form.password} onChange={handleChange} />
      {errors.password && <small>{errors.password}</small>}
      <label><input name="remember" type="checkbox" checked={form.remember} onChange={handleChange} /> Remember me</label>
      <button type="submit">Sign up</button>
    </form>
  );
}
```

This approach is fine up to maybe a dozen simple fields. Beyond that — nested data, async validation, field arrays, per-keystroke re-render cost — reach for **React Hook Form** (uncontrolled + refs, so it re-renders far less) or a schema validator like **Zod/Yup** for declarative rules. The key production habits even without a library: always `e.preventDefault()` on submit, set `noValidate` if you handle validation yourself, validate on submit (and optionally on blur, but not aggressively on every keystroke), and keep error state separate from value state so you can show errors without coupling them to the inputs.

#### Q68. [Coding] Build a Tabs component using the children/composition pattern.

**Problem:** A reusable `Tabs` that takes a list of tab labels and renders the matching panel, keeping the selected index in state and exposing an accessible roving structure.

```javascript
import { useState } from "react";

function Tabs({ tabs }) {
  const [active, setActive] = useState(0);

  return (
    <div>
      <div role="tablist">
        {tabs.map((t, i) => (
          <button
            key={t.id}
            role="tab"
            aria-selected={i === active}
            tabIndex={i === active ? 0 : -1}     // roving tabindex
            onClick={() => setActive(i)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel">{tabs[active].content}</div>
    </div>
  );
}

// usage
<Tabs tabs={[
  { id: "a", label: "Profile", content: <Profile /> },
  { id: "b", label: "Settings", content: <Settings /> },
]} />
```

**Why this shape:** keeping a single `active` index as the source of truth is the minimal state — the rendered panel is *derived* from it, never stored separately. Using each tab's stable `id` as the key (not the array index) keeps panel state intact if the tab list is reordered. **Accessibility notes:** `role="tablist"/"tab"/"tabpanel"`, `aria-selected`, and a **roving tabindex** (only the active tab is in the natural tab order; arrow keys would move between tabs in a full implementation) are what make it usable with a keyboard and screen reader. **Edge cases:** clamp `active` if the tab list shrinks; for a fully correct widget add `ArrowLeft/ArrowRight` key handling and `aria-controls`/`id` wiring between each tab and its panel. **Time/Space:** O(1) per switch.

#### Q69. [Theory] What does `key` do when placed on a component to force a remount, and when is that the right tool?

Beyond list reconciliation, a `key` on any element controls its **identity** across renders. When the key changes, React treats it as a *different* element: it unmounts the old instance (running cleanup, discarding all internal state and refs) and mounts a fresh one. This "remount on key change" is the idiomatic React way to **reset a component's state** when a defining input changes — far cleaner than a `useEffect` that manually resets a dozen state variables.

```javascript
// Reset all internal form state when the edited user changes,
// without any reset effect — React remounts EditUserForm fresh.
function UserEditor({ userId }) {
  return <EditUserForm key={userId} userId={userId} />;
}
```

The classic use cases: resetting a form when you switch which record is being edited, restarting an animation, or forcing a re-fetch component to start fresh. The reason this beats a syncing effect is correctness and simplicity — you get a guaranteed clean slate (state, refs, effects all reset) in one declarative line, instead of remembering to reset each piece manually and risking a stale field. The trade-off is that remounting throws away *all* state and re-runs mount effects, so it's the right tool only when you genuinely want a full reset; if you want to preserve some state across the change, use the previous-prop-during-render pattern instead. This technique appears in the React docs as the recommended alternative to "reset state when a prop changes."

### 🟡 Intermediate — extended

#### Q70. [Coding] Write a `useOnClickOutside` hook for dropdowns and popovers.

**Problem:** Detect clicks (and touches) outside a referenced element so you can close a menu, popover, or dropdown — a near-universal UI need.

```javascript
import { useEffect, useRef } from "react";

function useOnClickOutside(handler) {
  const ref = useRef(null);

  useEffect(() => {
    const listener = e => {
      // ignore clicks inside the element (or on the element itself)
      if (!ref.current || ref.current.contains(e.target)) return;
      handler(e);
    };
    document.addEventListener("mousedown", listener);
    document.addEventListener("touchstart", listener);
    return () => {
      document.removeEventListener("mousedown", listener);
      document.removeEventListener("touchstart", listener);
    };
  }, [handler]);

  return ref;
}

// usage
function Dropdown() {
  const [open, setOpen] = useState(false);
  const ref = useOnClickOutside(() => setOpen(false));
  return (
    <div ref={ref}>
      <button onClick={() => setOpen(o => !o)}>Menu</button>
      {open && <ul>{/* items */}</ul>}
    </div>
  );
}
```

**Why `mousedown`/`touchstart` instead of `click`:** listening on the down event closes the menu before a `click` fires elsewhere, which feels snappier and avoids edge cases where the clicked element is removed before `click` completes. **Why `contains`:** it correctly handles clicks on nested children of the popover. **Edge cases worth handling in production:** clicks inside a portal (the popover content is in `document.body`, not a DOM descendant of `ref`, so you need a second ref or check the portal node); ignoring the toggle button itself so the same click doesn't open-then-close; and pairing with an `Escape` key handler for keyboard users. **Stability:** wrap `handler` in `useCallback` at the call site, or the effect re-subscribes every render. **Time/Space:** O(1).

#### Q71. [Practical] How do you handle data fetching race conditions and the "stale response wins" bug without a library?

The bug: a user types fast, you fire request A then request B; if A is slower it resolves *after* B and overwrites the correct, newer results with stale ones. The two robust fixes are **AbortController** (cancel the previous request) and an **"ignore" flag / sequence token** (discard the response of any request that is no longer the latest). The ignore-flag pattern is the most portable because it works even for non-abortable async work.

```javascript
useEffect(() => {
  let active = true;                       // closure flag per effect run
  setLoading(true);
  fetchResults(query)
    .then(data => { if (active) setResults(data); })   // only the latest run commits
    .catch(err => { if (active) setError(err); })
    .finally(() => { if (active) setLoading(false); });
  return () => { active = false; };        // mark this run stale on cleanup
}, [query]);
```

Each time `query` changes, the previous effect's cleanup sets its `active` to `false`, so when its slow promise finally resolves, the `if (active)` guard skips the `setResults` — the stale response is dropped. The `AbortController` version (shown earlier in this guide) goes further by actually canceling the network request, saving bandwidth and server load; combine both for the best result. **The senior point:** this is exactly the class of bug that data libraries (**TanStack Query**, **SWR**) solve for you — they key requests, dedupe, cancel superseded ones, and cache — which is why "just use a query library" is the right answer for any non-trivial app. Hand-rolling is acceptable for a one-off, but you must remember the cleanup-guard or you ship intermittent, hard-to-reproduce stale-data bugs.

#### Q72. [Theory] Compare callback refs and object refs (`useRef`), and when do you need a callback ref?

There are two ways to attach a ref. An **object ref** (`useRef`) gives you a stable `{ current }` container that React assigns the DOM node into on mount and `null` on unmount — the common case. A **callback ref** is a function React calls with the node on attach and `null` on detach; it runs at commit time and lets you react *the moment* a node is attached or removed. You need a callback ref when you must run logic exactly when the DOM node appears or disappears — measuring it immediately, attaching a non-React listener, or wiring up a third-party library — especially when the node may mount and unmount conditionally.

```javascript
import { useCallback, useState } from "react";

// callback ref: measure a node the instant it mounts (no useEffect timing dance)
function MeasuredBox() {
  const [height, setHeight] = useState(0);
  const measureRef = useCallback(node => {
    if (node !== null) setHeight(node.getBoundingClientRect().height);
  }, []);
  return <div ref={measureRef}>Measured: {height}px</div>;
}
```

The subtle gotcha: if you pass an **inline** callback ref (`ref={n => ...}`), React calls it with `null` then the node on **every** render because the function identity changes — usually harmless but wasteful, and occasionally a real bug. Wrapping it in `useCallback` with stable deps prevents the detach/reattach churn. **React 19** adds the ability for a callback ref to **return a cleanup function** (like an effect), which runs on detach — a cleaner API than the old `null`-argument convention. Use object refs by default; reach for callback refs when attachment timing matters or when the ref target is conditional and you want to respond to its lifecycle precisely.

#### Q73. [Coding] Implement a `useMediaQuery` hook that is SSR-safe and concurrent-safe.

**Problem:** Track whether a CSS media query matches (e.g., `(min-width: 768px)`) for responsive logic in JS, without hydration mismatches and without tearing under concurrent rendering.

```javascript
import { useSyncExternalStore } from "react";

function useMediaQuery(query) {
  const subscribe = callback => {
    const mql = window.matchMedia(query);
    mql.addEventListener("change", callback);
    return () => mql.removeEventListener("change", callback);
  };
  const getSnapshot = () => window.matchMedia(query).matches;
  const getServerSnapshot = () => false;   // no matchMedia on the server

  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}

// usage
function Layout() {
  const isWide = useMediaQuery("(min-width: 768px)");
  return isWide ? <TwoColumn /> : <Stack />;
}
```

**Why `useSyncExternalStore` rather than `useState` + `useEffect`:** `matchMedia` is **external mutable state** living outside React. The naive approach (`useState(false)` then update in an effect) flashes the wrong layout on first paint and can **tear** under concurrent rendering if the query changes mid-render. `useSyncExternalStore` reads a consistent snapshot for the whole render and forces a sync re-render if the value changes during a concurrent pass. **The `getServerSnapshot`** returns a deterministic value (`false`) so server and first client render agree — preventing a hydration mismatch — and the real value is picked up immediately after hydration. **Edge cases:** older Safari used `addListener`/`removeListener` instead of `addEventListener`; some teams render the SSR default and accept a one-frame correction after mount. **Time/Space:** O(1); one media-query listener per hook instance.

#### Q74. [Practical] How do you manage environment-specific configuration and secrets in a React/Vite/Next app?

The cardinal rule: **anything bundled into client code is public.** Minification is not encryption; users can read every string in your JS. So client-exposed config must contain *only* non-secret values (public API base URLs, feature flags, analytics keys meant to be public), and genuine secrets (database credentials, private API keys, signing secrets) must live exclusively on the server. Build tools enforce this with a **prefix convention** so you don't leak a secret by accident: Vite exposes only variables prefixed `VITE_`, Create React App only `REACT_APP_`, and Next.js only `NEXT_PUBLIC_` to the browser; everything else stays server-side.

```bash
# .env.production  (committed values are public-safe; secrets go in CI secret store)
VITE_API_URL=https://api.example.com        # shipped to the browser
VITE_FEATURE_NEW_NAV=true                   # shipped to the browser
DATABASE_URL=postgres://...                 # NOT shipped (no VITE_ prefix) — server only
STRIPE_SECRET_KEY=sk_live_...               # NOT shipped — server only
```

```javascript
// Vite: only VITE_-prefixed vars are available; the rest are undefined in the browser
const apiUrl = import.meta.env.VITE_API_URL;
```

Operational practices that matter in production: never commit real secrets — commit a `.env.example` with blank/placeholder values and load real ones from a secret manager (Vault, AWS Secrets Manager, Doppler) injected by CI/CD; use per-environment files (`.env.development`, `.env.production`) but keep the *secret* values out of the repo; and in Next.js App Router, prefer reading secrets inside **Server Components / Server Actions / route handlers** so they never enter a client bundle, optionally guarding with the `server-only` package which fails the build if a server module is imported into client code. If a secret ever does ship to the client, treat it as compromised and rotate it — you cannot "un-ship" a bundle that users already downloaded.

#### Q75. [Coding] Build a compound component (Accordion) using Context to share state implicitly.

**Problem:** Demonstrate the **compound component** pattern — a parent and children that coordinate via a shared internal context, giving a clean declarative API (`<Accordion><Item/><Item/></Accordion>`) without prop drilling.

```javascript
import { createContext, useContext, useState, useId } from "react";

const AccordionCtx = createContext(null);

function Accordion({ children, defaultOpen = null }) {
  const [openId, setOpenId] = useState(defaultOpen);
  const toggle = id => setOpenId(cur => (cur === id ? null : id));
  return <AccordionCtx.Provider value={{ openId, toggle }}>{children}</AccordionCtx.Provider>;
}

function AccordionItem({ title, children }) {
  const { openId, toggle } = useContext(AccordionCtx);
  const id = useId();                       // stable, SSR-safe unique id
  const isOpen = openId === id;
  return (
    <div>
      <h3>
        <button aria-expanded={isOpen} aria-controls={`${id}-panel`} onClick={() => toggle(id)}>
          {title}
        </button>
      </h3>
      <div id={`${id}-panel`} role="region" hidden={!isOpen}>
        {children}
      </div>
    </div>
  );
}

Accordion.Item = AccordionItem;             // namespaced API

// usage — clean, declarative, no wiring of open/onToggle on every item
<Accordion defaultOpen={null}>
  <Accordion.Item title="Shipping">…</Accordion.Item>
  <Accordion.Item title="Returns">…</Accordion.Item>
</Accordion>
```

**Why compound components:** the parent owns the coordinating state (which item is open) and shares it through context so children read/write it implicitly. The consumer writes natural markup without threading `isOpen`/`onToggle` props through every item — the API reads like HTML. **`useId`** generates a stable unique id that matches between server and client (avoiding hydration mismatch) and wires `aria-controls`/`aria-expanded` for accessibility. **Trade-offs:** the pattern adds a context and assumes children are used inside the parent (guard `useContext` returning `null` with a helpful error). It shines for design-system widgets — `Tabs`, `Menu`, `RadioGroup`, `Select` — where flexible composition matters; for a one-off it's overkill. **Time/Space:** O(1) per toggle; only consuming items re-render.

### 🟠 Advanced — extended

#### Q76. [Theory] What does React 19's `useActionState` (and `useFormStatus`) do, and how do form Actions change data mutations?

React 19 introduces **Actions** — a first-class way to handle form submissions and mutations that integrates with transitions, so pending state, errors, and optimistic updates are managed by React instead of hand-rolled `isSubmitting` flags. You pass a function to a `<form action={fn}>` (or to `useActionState`) and React runs it inside a transition, tracking pending status automatically. **`useActionState(actionFn, initialState)`** returns the latest result state, a wrapped action to pass to the form, and an `isPending` boolean — collapsing the classic "loading/error/result" trio into one primitive.

```javascript
import { useActionState } from "react";
import { useFormStatus } from "react-dom";

async function submitReview(prevState, formData) {
  const text = formData.get("review");
  if (!text) return { error: "Review is required" };
  try {
    await saveReview(text);                 // could be a Server Action
    return { ok: true, error: null };
  } catch {
    return { error: "Save failed, try again" };
  }
}

function ReviewForm() {
  const [state, formAction, isPending] = useActionState(submitReview, { error: null });
  return (
    <form action={formAction}>
      <textarea name="review" />
      {state.error && <p role="alert">{state.error}</p>}
      <SubmitButton />
    </form>
  );
}

// useFormStatus reads the PARENT form's pending state — no prop drilling
function SubmitButton() {
  const { pending } = useFormStatus();
  return <button disabled={pending}>{pending ? "Saving…" : "Submit"}</button>;
}
```

**Why this matters:** before React 19 every form reinvented `const [isSubmitting, setSubmitting] = useState(false)` plus try/catch/finally plus error state, and a nested submit button had to receive `isSubmitting` via props. Actions move all of that into React: the action runs in a transition (keeping the UI responsive), `useActionState` owns the returned state and pending flag, and **`useFormStatus`** lets *any descendant* of the form read its submission status without prop drilling — ideal for design-system submit buttons. Combined with **Server Actions** (`"use server"`), a form can call a server function directly with progressive-enhancement-friendly `FormData`, no manual `fetch` or API route. The trade-off is that this is React-19-and-framework-specific; the mental shift for senior engineers is to stop managing submission state manually and let the Action/transition machinery do it.

#### Q77. [Coding] Implement a finite state machine for an async request using `useReducer`.

**Problem:** Model a fetch lifecycle as an explicit state machine (`idle → loading → success | error`, with `retry`) so impossible states (e.g., "loading and error at once") are unrepresentable — a robust alternative to juggling independent booleans.

```javascript
import { useReducer, useCallback } from "react";

const machine = {
  idle:    { FETCH: "loading" },
  loading: { RESOLVE: "success", REJECT: "error" },
  success: { FETCH: "loading" },
  error:   { RETRY: "loading" },
};

function reducer(state, action) {
  const next = machine[state.status]?.[action.type];
  if (!next) return state;                          // ignore invalid transitions
  switch (action.type) {
    case "FETCH":
    case "RETRY":  return { status: next, data: null, error: null };
    case "RESOLVE": return { status: next, data: action.data, error: null };
    case "REJECT":  return { status: next, data: null, error: action.error };
    default:        return state;
  }
}

function useRequest(fetcher) {
  const [state, dispatch] = useReducer(reducer, { status: "idle", data: null, error: null });

  const run = useCallback(async () => {
    dispatch({ type: state.status === "error" ? "RETRY" : "FETCH" });
    try {
      const data = await fetcher();
      dispatch({ type: "RESOLVE", data });
    } catch (error) {
      dispatch({ type: "REJECT", error });
    }
  }, [fetcher, state.status]);

  return { ...state, run };
}
```

**Why a state machine:** representing async state as three independent booleans (`isLoading`, `isError`, `isSuccess`) allows 8 combinations, most of which are nonsense ("loading and success simultaneously"), and the bugs hide in the impossible-but-reachable combos. A single `status` enum with an explicit transition table makes invalid states **unrepresentable** and invalid transitions **no-ops** — the `machine` lookup rejects, say, a `RESOLVE` while `idle`. This is exactly the philosophy behind **XState** for complex flows (multi-step wizards, media players, checkout). **Trade-offs:** for trivial cases the enum is more ceremony than `useState`, but as soon as there are guards, retries, or more than two or three states, the explicit machine pays for itself in clarity and testability (the reducer is a pure function you can unit-test exhaustively). **Time/Space:** O(1) per transition.

#### Q78. [Practical] How do you offload heavy computation off the main thread in React using Web Workers?

React rendering, layout, paint, and your JavaScript all share the **single main thread**. A long synchronous computation (parsing a big CSV, image processing, cryptography, a heavy data transform) blocks that thread, freezing input, animation, and rendering — `startTransition` only re-prioritizes React work, it does **not** move work to another thread. The real fix for CPU-bound work is a **Web Worker**, which runs in a separate thread; you post a message in, get a message back, and the UI stays responsive the whole time.

```javascript
// worker.js — runs on its own thread
self.onmessage = e => {
  const result = expensiveTransform(e.data);   // heavy, synchronous, but off main thread
  self.postMessage(result);
};

// useWorker.js — a hook wrapping the worker lifecycle
import { useState, useEffect, useRef, useCallback } from "react";

function useWorker(workerFactory) {
  const workerRef = useRef(null);
  const [result, setResult] = useState(null);

  useEffect(() => {
    const worker = workerFactory();
    workerRef.current = worker;
    worker.onmessage = e => setResult(e.data);
    return () => worker.terminate();             // cleanup: kill the thread
  }, [workerFactory]);

  const run = useCallback(input => workerRef.current?.postMessage(input), []);
  return [result, run];
}

// usage (Vite: new Worker(new URL('./worker.js', import.meta.url), { type: 'module' }))
const [result, run] = useWorker(() => new Worker(new URL("./worker.js", import.meta.url)));
```

**Decision rule:** use `startTransition`/`useDeferredValue` when the bottleneck is React *rendering* a lot of components (keep input snappy by deprioritizing the render); use a **Web Worker** when the bottleneck is a *CPU-bound computation* that would block the thread regardless of React. **Trade-offs:** workers can't touch the DOM or React, communication is async message-passing (data is structured-cloned, which has a copy cost — use **Transferable** objects or `SharedArrayBuffer` for large binary payloads to avoid copying), and there's setup/teardown overhead so they're worth it only for genuinely heavy work. Libraries like **Comlink** wrap the `postMessage` protocol in a promise/proxy API that feels like calling a normal async function. In production I reach for a worker when profiling shows long tasks (>50ms) from a pure computation, and confirm INP improves afterward.

#### Q79. [Coding] Implement a `useInfiniteScroll` data hook with pagination, dedup, and an observer sentinel.

**Problem:** Combine paginated fetching with an IntersectionObserver sentinel so new pages load as the user scrolls, while guarding against duplicate fetches and out-of-order pages.

```javascript
import { useState, useEffect, useRef, useCallback } from "react";

function useInfiniteScroll(fetchPage) {
  const [items, setItems] = useState([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const loadingRef = useRef(false);          // guard against concurrent loads

  const loadMore = useCallback(async () => {
    if (loadingRef.current || !hasMore) return;   // dedup: one in-flight load at a time
    loadingRef.current = true;
    try {
      const { data, more } = await fetchPage(page);
      setItems(prev => {
        const seen = new Set(prev.map(i => i.id));
        return [...prev, ...data.filter(i => !seen.has(i.id))];   // dedup by id
      });
      setHasMore(more);
      setPage(p => p + 1);
    } finally {
      loadingRef.current = false;
    }
  }, [page, hasMore, fetchPage]);

  // sentinel via IntersectionObserver
  const sentinelRef = useRef(null);
  useEffect(() => {
    const node = sentinelRef.current;
    if (!node) return;
    const obs = new IntersectionObserver(([e]) => { if (e.isIntersecting) loadMore(); }, {
      rootMargin: "300px",
    });
    obs.observe(node);
    return () => obs.disconnect();
  }, [loadMore]);

  return { items, hasMore, sentinelRef };
}

// usage
function Feed() {
  const { items, hasMore, sentinelRef } = useInfiniteScroll(
    page => fetch(`/api/feed?page=${page}`).then(r => r.json())
  );
  return (
    <>
      {items.map(i => <Card key={i.id} {...i} />)}
      {hasMore && <div ref={sentinelRef}>Loading…</div>}
    </>
  );
}
```

**Key correctness details:** the `loadingRef` boolean prevents the observer from firing a second fetch while one is in flight (a ref, not state, so updating it doesn't re-render or lag behind the latest value). The **id-based dedup** `Set` guards against the same item appearing in overlapping pages — common with cursor pagination during inserts. `rootMargin: "300px"` triggers loading before the user hits the very bottom, hiding latency. **Edge cases:** reset everything if the underlying query/filter changes (use a `key` or include it in deps); handle errors with a retry button rather than silently stopping; and for very long lists combine this with **virtualization** since infinite scroll alone keeps mounting DOM nodes forever, eventually degrading performance. **Production note:** `TanStack Query`'s `useInfiniteQuery` provides this with caching, retries, and cursor handling — prefer it for real apps. **Time/Space:** O(n) accumulated items; the dedup `Set` is O(n) per page.

#### Q80. [Theory] What is the difference between `useDeferredValue` and `useTransition`, and when do you use each?

Both are React 18 concurrent primitives that let urgent updates (typing) stay responsive while expensive updates (re-rendering a big list) happen at lower priority — but they differ in **what you control**. `useTransition` wraps the **state update** you own: you call `startTransition(() => setState(...))` to mark *that update* as non-urgent, and you get an `isPending` flag. `useDeferredValue` wraps a **value** you receive (often a prop you don't control the setter for): it returns a copy that "lags behind," updating at low priority, so the expensive consumer renders against the deferred value while the urgent part of the UI updates immediately.

```javascript
// useTransition: you OWN the setState — mark it non-urgent
function Search() {
  const [query, setQuery] = useState("");
  const [isPending, startTransition] = useTransition();
  const onChange = e => {
    setQuery(e.target.value);                       // urgent: input stays snappy
    startTransition(() => runExpensiveSearch(e.target.value));  // deprioritized
  };
}

// useDeferredValue: you only RECEIVE a value (e.g. a prop) — lag the heavy consumer
function Results({ query }) {
  const deferredQuery = useDeferredValue(query);    // lags behind `query`
  const list = useMemo(() => filterHugeList(deferredQuery), [deferredQuery]);
  const stale = query !== deferredQuery;            // show subtle "updating" hint
  return <div style={{ opacity: stale ? 0.6 : 1 }}>{list}</div>;
}
```

**Decision rule:** if you control the `setState` that triggers the heavy work, use **`useTransition`** — it's the more direct expression of intent and gives you `isPending`. If you *don't* own the setter (the expensive value arrives as a prop, or comes from context/an external source), use **`useDeferredValue`** to deprioritize rendering against it. They're often interchangeable in a single-component search box, but `useDeferredValue` shines when the producer and consumer are decoupled — e.g., a parent updates `query` urgently and a deep child defers its expensive render. **Shared caveat:** neither makes work *faster* or moves it off-thread; they only reorder priority so urgent updates aren't blocked. Both require the deferred render to be interruptible (pure components), and you typically pair them with `React.memo`/`useMemo` so the deprioritized subtree actually skips work when the deferred value hasn't changed.

#### Q81. [Practical] How do you wire up error monitoring (e.g., Sentry) in a React app, including source maps and error boundaries?

Effective production error monitoring has three parts: **capturing** errors, **attributing** them to real source code, and **contextualizing** them. Capturing requires both an error boundary (for render-phase errors, which otherwise blank the screen) *and* global handlers for the errors boundaries miss — event handlers, async callbacks, and unhandled promise rejections. Sentry's React SDK provides an `ErrorBoundary` component and auto-instruments `window.onerror`/`onunhandledrejection`, but you should reason about the coverage explicitly because error boundaries famously do **not** catch async/event-handler errors.

```javascript
import * as Sentry from "@sentry/react";

Sentry.init({
  dsn: import.meta.env.VITE_SENTRY_DSN,
  environment: import.meta.env.MODE,
  release: import.meta.env.VITE_RELEASE,        // ties errors to a build for source maps
  tracesSampleRate: 0.1,                        // performance sampling
  replaysOnErrorSampleRate: 1.0,                // session replay on errors
});

// boundary catches render errors; global handlers catch the rest
const App = Sentry.withErrorBoundary(RootApp, { fallback: <ErrorScreen /> });

// event-handler / async errors are NOT caught by boundaries — report manually
async function onSave() {
  try {
    await save();
  } catch (err) {
    Sentry.captureException(err, { tags: { feature: "save" } });
    showToast("Save failed");
  }
}
```

The piece teams most often get wrong is **source maps**: minified production stack traces are unreadable (`a.b.c is not a function` at `index-4f9a.js:1:20481`). You must upload source maps to Sentry during CI (via `sentry-cli` or the bundler plugin) tagged with the same `release` you set in `init`, so Sentry can de-minify traces back to your original code — and you should **not** serve those maps publicly (upload-and-delete, or restrict access) to avoid exposing source. Beyond that: set a `release` and `environment` so you can tell which deploy regressed and filter staging noise; attach user/session context and breadcrumbs (Sentry records recent actions automatically) so you can reproduce; sample traces/replays to control cost; and **filter noise** (browser-extension errors, `ResizeObserver loop limit exceeded`, network blips) with `beforeSend`/`ignoreErrors` so real signal isn't drowned out. The senior workflow is alert → triage by frequency and affected-user count → correlate with the release → fix → confirm the error rate drops in the dashboard.

### 🔴 Expert — extended

#### Q82. [Theory] How does React's `cache()` function work in RSC, and how does it relate to request deduplication and the fetch cache?

In a React Server Components app, the same data is often needed in several places in one render — a layout reads the current user, a page reads it again, a component deep in the tree reads it once more. Without coordination that's three identical database/API calls per request. React's **`cache(fn)`** memoizes a function's result **for the lifetime of a single server request**: the first call with given arguments computes and stores; subsequent calls with the same arguments within that request return the cached value. This is *per-request* memoization (it does not persist across requests or users), which makes it safe for per-request data like the authenticated user.

```javascript
import { cache } from "react";

// one DB hit per request even if called in layout, page, and a nested component
export const getUser = cache(async (id) => {
  return db.user.findUnique({ where: { id } });
});

// Layout, Page, and Sidebar can each `await getUser(id)` — only ONE query runs.
```

It complements two other dedup layers. **`fetch` deduplication**: in the App Router, React/Next extends `fetch` so identical `fetch(url, opts)` calls during one render are automatically deduped and cached per request — so you often don't need `cache()` for plain HTTP `GET`s, only for non-`fetch` data sources like direct DB/ORM calls or expensive computations. **Persistent caching** (Next's Data Cache / `revalidate`) is a *different*, cross-request layer that survives between requests with a TTL or on-demand invalidation. The senior mental model is three tiers: `cache()`/`fetch`-dedup eliminate duplicate work **within one render**, the Data Cache eliminates work **across requests**, and the full-route cache serves prerendered routes. Misunderstanding which layer you're in causes both stale-data bugs (expecting `cache()` to persist) and over-fetching (not deduping). `cache()` also helps avoid prop-drilling fetched data: instead of fetching at the top and threading it down, each component fetches what it needs and dedup makes it free.

#### Q83. [Coding] Implement a polymorphic, ref-forwarding `Box`/`Text` component with proper typing semantics.

**Problem:** Build a reusable primitive that can render as any element via an `as` prop (`<Text as="h1">`, `<Text as="label">`), forwards refs correctly, and merges props — the foundation of most design systems.

```javascript
import { forwardRef } from "react";

// Polymorphic Text: renders as `as` (default "span"), forwards ref to the host node.
const Text = forwardRef(function Text({ as: Tag = "span", className, children, ...rest }, ref) {
  return (
    <Tag ref={ref} className={`txt ${className ?? ""}`} {...rest}>
      {children}
    </Tag>
  );
});

// usage: same component, different host element + correct ref type
function Demo() {
  const headingRef = useRef(null);   // points at the real <h1> DOM node
  return (
    <>
      <Text as="h1" ref={headingRef}>Title</Text>
      <Text as="label" htmlFor="email">Email</Text>
      <Text>Default span</Text>
    </>
  );
}
```

In TypeScript this gets interesting: a fully-typed polymorphic component derives its allowed props from `as` so `<Text as="a" href="…" />` type-checks `href` while `<Text as="div" href="…" />` errors. The standard technique uses generics with `ElementType` and `ComponentPropsWithoutRef<C>`, plus a ref type of `ComponentPropsWithRef<C>["ref"]`:

```typescript
type PolymorphicProps<C extends React.ElementType, P> =
  P & { as?: C } & Omit<React.ComponentPropsWithoutRef<C>, keyof P | "as">;

// <Text as="a" href="/x" /> ✅   <Text as="button" type="submit" /> ✅
```

**Why this pattern:** design systems need one styled primitive usable as many semantic elements (a `Button` rendered as `<a>` for links, a `Text` rendered as the correct heading level for accessibility) without duplicating the component per tag. **Trade-offs:** polymorphic typing is notoriously complex and can slow the TS compiler and produce cryptic errors; many teams cap it (a small allowlist of `as` values) or use the **`asChild`** / slot pattern (Radix) instead, which merges props onto a single child you provide rather than swapping the tag. **React 19** simplifies the runtime side since `ref` is a normal prop (no `forwardRef`), but the polymorphic *typing* remains the hard part. Ship the ref forwarding regardless — a primitive you can't attach a ref to can't be measured, focused, or animated.

#### Q84. [Theory] Why do effects run twice in development with Strict Mode, and what real bugs does this surface?

React 18+ **Strict Mode** intentionally **mounts each component, unmounts it, then mounts it again** in development only (and double-invokes render and certain other functions). This is not a bug or a measurement of production behavior — it's a deliberate stress test. The reasoning ties to the concurrent renderer: React reserves the right to mount, unmount, and *remount* a component while preserving its state (e.g., to instantly restore a hidden tab, or to discard and restart a speculative render). For that to be safe, every effect must be **resilient to being set up and torn down repeatedly** — which in practice means every effect that subscribes to something must return a cleanup that fully reverses it.

```javascript
// Strict Mode runs: mount -> setup, unmount -> CLEANUP, mount -> setup again.
// If cleanup is missing or incomplete, the double-run exposes it:
useEffect(() => {
  const sub = chatRoom.connect();   // ❌ no cleanup: TWO connections after the double-mount
}, []);

useEffect(() => {
  const sub = chatRoom.connect();
  return () => sub.disconnect();    // ✅ cleanup makes the double-run a no-op net effect
}, []);
```

The real bugs it surfaces: **missing cleanup** (doubled subscriptions, two WebSocket connections, two event listeners, leaked intervals), **non-idempotent setup** (an effect that appends to a global array grows it twice; one that increments a server-side counter fires twice — revealing that the *logic itself* assumes single-run), and **impure render** (since render is also double-invoked, code that mutates a module-level variable or a ref during render produces visibly wrong results). The fix is never to "disable Strict Mode" or add a ref-guard to skip the second run — that hides the latent bug rather than fixing it; the correct response is to make effects properly cleanable and render pure. A subtle real-world case it catches: a `fetch` in an effect without an abort/ignore guard fires twice and can commit the stale response, which is exactly the race condition you'd hit in production under fast re-renders. Strict Mode is essentially a free integration test for the React contracts, and senior engineers treat its warnings as production-bug previews.

#### Q84b. [Practical] How do you architect and integrate micro-frontends with React without shipping multiple React copies?

(Placeholder — superseded; see Q85.)

#### Q85. [Practical] How do you architect micro-frontends with React, and what are the hard problems (shared React, routing, state)?

Micro-frontends split a large app into independently deployable pieces owned by different teams, composed at runtime. The common implementation is **Module Federation** (Webpack/Rspack/Vite plugin), where a "host" loads remote bundles at runtime and they share dependencies. The single most important and most-botched concern is **sharing the React runtime**: if each micro-frontend bundles its own copy of React, you get "invalid hook call" errors and broken context because hooks rely on a single shared internal dispatcher and React identity must match across the tree. You must declare React (and `react-dom`) as **singleton shared dependencies** with compatible versions so exactly one copy loads.

```javascript
// webpack.config.js (host or remote) — Module Federation
new ModuleFederationPlugin({
  name: "checkout",
  remotes: { catalog: "catalog@https://cdn/.../remoteEntry.js" },
  shared: {
    react:       { singleton: true, requiredVersion: "^19.0.0", eager: false },
    "react-dom": { singleton: true, requiredVersion: "^19.0.0" },
  },
});
```

The other hard problems: **routing** (one app owns the router; remotes either mount under a route or use a shared history instance — avoid two routers fighting over the URL); **cross-app state and communication** (prefer a thin, framework-agnostic event bus or shared store over importing another team's React context, so teams can deploy independently — tight coupling defeats the purpose); **styling isolation** (CSS leaks across boundaries — use CSS Modules, scoped class prefixes, or Shadow DOM); **version skew** (a shared singleton means a major React upgrade must be coordinated across all teams, which is the central organizational tension); and **performance** (duplicated vendor code, multiple bundles, and waterfall remote loads can bloat the page — measure total JS shipped). The honest senior assessment: micro-frontends solve an **organizational** scaling problem (many teams shipping independently) at a real **technical** cost (shared-runtime coordination, version lockstep, integration complexity). For a single team they're usually over-engineering; reach for them when independent deployment by separate teams is a hard requirement, and even then keep the shared surface (React version, design system, auth) deliberately small and well-versioned.

#### Q86. [Coding] Implement a declarative `Portal` + `Tooltip` that positions itself and cleans up correctly.

**Problem:** Render a tooltip into `document.body` via a portal (to escape `overflow`/`z-index` traps) while positioning it relative to its anchor, updating on scroll/resize, and tearing down listeners.

```javascript
import { useState, useRef, useLayoutEffect, useCallback } from "react";
import { createPortal } from "react-dom";

function Tooltip({ label, children }) {
  const anchorRef = useRef(null);
  const [pos, setPos] = useState(null);     // null = hidden

  const place = useCallback(() => {
    const r = anchorRef.current?.getBoundingClientRect();
    if (r) setPos({ top: r.top - 8, left: r.left + r.width / 2 });
  }, []);

  // useLayoutEffect: measure & position BEFORE paint to avoid a visible jump
  useLayoutEffect(() => {
    if (pos === null) return;               // only attach listeners while visible
    place();
    window.addEventListener("scroll", place, true);   // capture: catch nested scrolls
    window.addEventListener("resize", place);
    return () => {
      window.removeEventListener("scroll", place, true);
      window.removeEventListener("resize", place);
    };
  }, [pos !== null, place]);

  return (
    <>
      <span
        ref={anchorRef}
        tabIndex={0}
        aria-describedby={pos ? "tt" : undefined}
        onMouseEnter={place}
        onFocus={place}
        onMouseLeave={() => setPos(null)}
        onBlur={() => setPos(null)}
      >
        {children}
      </span>
      {pos &&
        createPortal(
          <div id="tt" role="tooltip"
               style={{ position: "fixed", top: pos.top, left: pos.left, transform: "translate(-50%, -100%)" }}>
            {label}
          </div>,
          document.body
        )}
    </>
  );
}
```

**Why a portal:** a tooltip rendered inline is clipped by any ancestor with `overflow: hidden` and trapped under sibling stacking contexts; rendering into `document.body` escapes both while React portals keep it logically in the tree so context and event bubbling still work. **Why `useLayoutEffect`:** you must measure the anchor and set the tooltip position *before* the browser paints, or the tooltip flashes at the wrong location for a frame. **Why `scroll` with capture (`true`):** a tooltip anchored inside a scrollable container must reposition when *that* container scrolls, and scroll events don't bubble — capture phase catches them. **Accessibility:** `role="tooltip"` + `aria-describedby` links it to the anchor, and showing on `focus` (not just hover) makes it keyboard-accessible. **Edge cases a production version adds:** viewport collision detection / flipping (so it doesn't render off-screen), a small show/hide delay, and `prefers-reduced-motion`. **The senior caveat:** use a positioning library (**Floating UI**) rather than hand-rolling collision logic — getting flip/shift/arrow placement right across scroll containers is genuinely hard. **Time/Space:** O(1) per reposition.

#### Q87. [Theory] What changed in React 18's `renderToPipeableStream` / streaming SSR, and how do Suspense boundaries enable selective hydration?

Classic React SSR used `renderToString`, which is **synchronous and all-or-nothing**: the server must finish rendering the *entire* page (including waiting for every data dependency) before sending a single byte, then the client must download all JS and hydrate the *whole* tree before anything becomes interactive. A slow data fetch or a heavy component anywhere stalls the whole response (TTFB) and delays interactivity (TTI). React 18's **`renderToPipeableStream`** (Node) / `renderToReadableStream` (edge) replaces this with **streaming**: the server flushes the HTML shell immediately and streams the rest in chunks as it becomes ready.

```
renderToString (React 17):
  [wait for ALL data] ──► send full HTML ──► download ALL JS ──► hydrate WHOLE tree
        slow TTFB                                                 late, blocking TTI

renderToPipeableStream + Suspense (React 18):
  send shell now ──► stream <Suspense> chunks as data resolves
        fast TTFB        selective, out-of-order hydration as JS arrives
```

The mechanism is built on **`<Suspense>`** boundaries. On the server, when a component inside a boundary suspends (its data isn't ready), React immediately sends the boundary's **fallback** in the initial HTML and keeps the connection open; when the data resolves, it streams the real HTML for that boundary plus an inline script that swaps the fallback for the content — so the user sees the shell instantly and slow sections fill in progressively, with no client JS required for that swap. On the client this enables **selective hydration**: React doesn't have to wait for *all* JS to hydrate everything; it hydrates boundaries as their code arrives, and it **prioritizes hydrating whatever the user interacts with first** (a click on a not-yet-hydrated region jumps that boundary to the front of the queue). The net effect: a slow widget no longer blocks the rest of the page from being interactive, TTFB drops to "render the shell," and TTI is decoupled per-boundary. The senior framing: wrap each independent, potentially-slow region in its own Suspense boundary so the fast parts of the page aren't held hostage to the slowest data fetch — boundary placement is a performance design decision, not just an error/loading convenience.

#### Q88. [Behavioral] Tell me about a time you led a team through a high-stakes production incident or a hard technical turnaround on a frontend system.

Answer with **STAR**, emphasizing leadership, calm under pressure, and durable follow-through rather than heroics. *Situation:* our React dashboard started intermittently white-screening for a subset of users right after a routine deploy; support tickets spiked and the error rate in Sentry jumped, but it reproduced on no internal machine. *Task:* as the senior/staff engineer on call I owned both the immediate mitigation and the longer-term fix, and I had to coordinate across frontend, platform, and support while keeping leadership informed without spreading panic. *Action:* first I **stopped the bleeding** — I led the call to roll back the deploy behind our release flag rather than debug live, because mitigation precedes diagnosis when users are down; rollback restored service within minutes. Then I drove root-cause: the Sentry traces (with uploaded source maps) pointed at a chunk-load failure — users on old tabs were requesting hashed JS chunks that the new deploy had purged from the CDN, so a lazy-loaded route threw and there was no error boundary around it to recover. I reproduced it deterministically by deploying to staging and forcing a stale chunk request. *Action (fix):* we added an error boundary around lazy routes with a "new version available, reload" recovery, a retry-once wrapper on dynamic imports, and a CDN policy to retain old chunks for a grace window after deploy; I also added a synthetic check that loads a stale chunk so we'd catch a regression before users did. *Result:* white-screens went to zero, mean-time-to-recovery for similar incidents dropped because the reload-recovery is now automatic, and I wrote a blameless postmortem that turned three of the action items into standing engineering guidelines (boundaries around all lazy boundaries, chunk-retention policy, deploy canary on real-user INP/error rate). The meta-lessons I'd convey: **mitigate before you diagnose**, let **observability with source maps** do the heavy lifting, design for **graceful degradation** (an error boundary turns a white screen into a reload prompt), and convert each incident into a systemic guardrail so the same class of failure can't recur — and do it blamelessly so people surface problems early next time.

#### Q89. [Theory] How does reconciliation handle moving a stateful component between different parents, and what is the role of position vs key?

React's reconciliation identifies a component by its **position in the tree** combined with its **key** (and type) — *relative to its parent*, not globally. Two consequences follow that surprise even experienced engineers. First, **same position + same type = same instance**: if you conditionally render `condition ? <Counter/> : <Counter/>` at the same slot, React reuses the *same* `Counter` instance and its state survives the toggle, because from React's view nothing moved. Second, **a component cannot preserve state when it moves to a different parent** (or a different position the diff treats as different): React has no concept of a component identity that travels across the tree, so relocating a stateful subtree to another parent unmounts it at the old location and mounts a fresh one at the new — losing its state — even if you reuse the same element and key.

```javascript
// ❌ The two <Counter/>s are at the SAME position -> state is shared/preserved across toggle,
//    which is surprising if you expected a "fresh" counter for each branch.
return showA ? <Counter label="A" /> : <Counter label="B" />;

// ✅ Force distinct identities so each branch gets its own state:
return showA ? <Counter key="a" label="A" /> : <Counter key="b" label="B" />;

// State does NOT survive moving across parents:
return moved
  ? <div><Counter /></div>     // unmount here...
  : <section><Counter /></section>;  // ...remount fresh here (state lost)
```

The practical rules: (1) to **preserve** state across a conditional, keep the component at the **same position** with the **same key**; (2) to **reset** state, change the key (or render at a structurally different position); (3) you **cannot** carry component state from one parent to another via React identity — if state must survive a move, it has to be **lifted** to a common ancestor (or a store) and passed down, because then the state lives above the moving subtree and the moving component just reads it. This is why "lift state up" is not only about sharing but about *surviving* structural changes. Understanding position-plus-key identity explains a whole family of "why did my input clear / why did state leak between tabs" bugs and tells you precisely which lever (key, position, or lifting) to pull.

#### Q90. [Practical] How do you implement and roll out feature flags / A/B tests in a React app without causing hydration mismatches or layout shift?

Feature flags decouple deploy from release (ship code dark, enable it for a cohort later) and power A/B experiments. The two technical hazards specific to React are **hydration mismatch** (the server renders variant A but the client decides variant B, so React tears and re-renders, causing flicker) and **layout shift / flash of the wrong variant** (the user briefly sees the control before the experiment loads). Both stem from the flag value being resolved differently or later on the client than on the server.

```javascript
// The flag MUST resolve to the same value on server and first client render.
// Resolve it on the SERVER (cookie/edge), pass it down, and hydrate with that value.

// server (Next.js): read assignment from a cookie/edge middleware, render with it
export default async function Page() {
  const variant = await getVariant("checkout_v2");   // server-side decision, sticky per user
  return <Checkout variant={variant} />;              // serialized into HTML
}

// client provider just READS the already-decided value — no client-only flip
const FlagContext = createContext({});
function FlagProvider({ value, children }) {
  return <FlagContext.Provider value={value}>{children}</FlagContext.Provider>;
}
function useFlag(name) { return useContext(FlagContext)[name]; }
```

The principle that avoids mismatch: **decide the variant where the first render happens and make the decision sticky per user.** In SSR/RSC, resolve flags on the server (from a cookie or edge middleware) and pass them into the initial render so server and client agree; persist the assignment (cookie/user id hash) so a user keeps the same variant across requests and refreshes — random per-render assignment guarantees mismatch and breaks experiment integrity. In a pure CSR app where flags load from a client SDK, avoid layout shift by **gating on the SDK's "ready" state** (render a neutral skeleton until flags resolve, or server-bootstrap the initial flag set) rather than rendering the control then snapping to the variant. Other production concerns: **default safely** (if the flag service is down, fall back to the control so a failed flag fetch never breaks the page), **clean up stale flags** (dead `if (flag)` branches rot the codebase — treat flag removal as part of "done"), **avoid measurement bias** (don't let the variant change after the user has interacted), and **respect performance** (don't block first paint on a flag network call — bootstrap initial values into the HTML). Tools like LaunchDarkly, Statsig, or GrowthBook provide server + client SDKs precisely so you can resolve on the server and hydrate consistently; the senior move is to treat flag resolution as part of the render-determinism contract, not an afterthought bolted on in `useEffect`.

#### Q91. [Coding] Implement `useReducer`-driven undo/redo (time-travel) for an editor.

**Problem:** Add undo/redo to a stateful editor by wrapping any reducer in a history-tracking reducer that keeps past/present/future stacks.

```javascript
import { useReducer, useCallback } from "react";

// Higher-order reducer: wraps any (state, action) reducer with history.
function undoable(reducer) {
  return function (history, action) {
    const { past, present, future } = history;
    switch (action.type) {
      case "UNDO": {
        if (past.length === 0) return history;
        const previous = past[past.length - 1];
        return { past: past.slice(0, -1), present: previous, future: [present, ...future] };
      }
      case "REDO": {
        if (future.length === 0) return history;
        const next = future[0];
        return { past: [...past, present], present: next, future: future.slice(1) };
      }
      default: {
        const newPresent = reducer(present, action);
        if (newPresent === present) return history;        // no change -> don't record
        return { past: [...past, present], present: newPresent, future: [] };  // clears redo
      }
    }
  };
}

// a normal domain reducer
function textReducer(state, action) {
  switch (action.type) {
    case "type":  return { ...state, text: action.text };
    case "clear": return { ...state, text: "" };
    default:      return state;
  }
}

function Editor() {
  const [history, dispatch] = useReducer(undoable(textReducer), {
    past: [], present: { text: "" }, future: [],
  });
  const undo = useCallback(() => dispatch({ type: "UNDO" }), []);
  const redo = useCallback(() => dispatch({ type: "REDO" }), []);

  return (
    <>
      <textarea value={history.present.text}
                onChange={e => dispatch({ type: "type", text: e.target.value })} />
      <button onClick={undo} disabled={history.past.length === 0}>Undo</button>
      <button onClick={redo} disabled={history.future.length === 0}>Redo</button>
    </>
  );
}
```

**Why this design:** the `undoable` higher-order reducer is **generic** — it adds time-travel to *any* reducer without that reducer knowing about history, which keeps domain logic clean and testable. The three-stack model (`past`/`present`/`future`) maps directly to undo (pop past → present, push old present to future), redo (the mirror), and a normal change (push present to past, **clear future** because branching invalidates the redo stack). Returning the **same reference** when nothing changed avoids polluting history with no-op entries. **Edge cases / production concerns:** unbounded history grows memory — cap `past` to N entries; per-keystroke history is too granular — **debounce or coalesce** consecutive same-field edits into one undo step (most editors group typing); and for large documents store **diffs/patches** (e.g., with Immer patches) instead of full snapshots to bound memory. **Time/Space:** O(1) per action; O(N × stateSize) memory for N history entries — the main thing to bound. This is the core idea behind Redux DevTools time-travel and editor undo stacks.

#### Q92. [Theory] What are the correctness and performance implications of `Object.is`-based bail-outs, and how do `useState`/`useReducer` bail out of renders?

React uses **`Object.is`** comparison (not `===`, differing only for `NaN` and `+0/-0`) as the equality check in several hot paths, and understanding where determines whether your updates render at all. When you call a state setter (`useState`/`useReducer` dispatch) and the new state is `Object.is`-equal to the current state, React **bails out**: it skips re-rendering that component (it may still re-render once to compare, then bail, but it won't re-render children). This is why `setState(sameValue)` is effectively a no-op and why **mutating** an object and setting it back (`obj.x = 1; setObj(obj)`) fails to update — the reference is identical, `Object.is` returns `true`, and React bails out thinking nothing changed.

```javascript
// ❌ mutation: same reference -> Object.is(old, new) === true -> React bails out, no render
setUser(u => { u.name = "Ada"; return u; });

// ✅ new reference -> Object.is false -> React re-renders
setUser(u => ({ ...u, name: "Ada" }));

// Setting the identical primitive bails out (no render), by design:
setCount(count);    // same value -> no re-render
```

The same `Object.is` semantics govern **`useEffect`/`useMemo`/`useCallback` dependency comparison** (each dep is compared by `Object.is` to its previous value — a fresh object/array/function literal is never equal, re-running the effect or recomputing the memo) and **`React.memo`'s default shallow prop comparison** (each prop compared by `Object.is`). The performance implications cut both ways: it lets React cheaply skip enormous amounts of work when references are stable (the entire premise of memoization and immutable updates), but it also means **identity discipline is mandatory** — accidentally creating new object/array/function references each render silently defeats every `memo`, re-triggers every effect, and recomputes every `useMemo`, which is the single most common cause of "my memoization does nothing" and "my effect runs every render." The senior takeaway: React's optimization model is fundamentally **reference equality on immutable data**; you get speed by keeping references stable when data hasn't changed and creating new references exactly when it has — and the React 19 Compiler automates much of this identity bookkeeping, but the underlying `Object.is` contract is what it's optimizing around.

#### Q93. [Practical] How do you make a large React app accessible (a11y) systematically, beyond individual ARIA attributes?

Accessibility done well is an **architectural and process** discipline, not a checklist of ARIA attributes sprinkled at the end. The first principle is **use semantic HTML before reaching for ARIA**: a real `<button>`, `<nav>`, `<main>`, `<label>`, and heading hierarchy give you keyboard operability, focus, and screen-reader semantics for free, whereas a `<div onClick>` requires you to manually add `role`, `tabIndex`, and key handlers and will still be subtly wrong. The W3C maxim "no ARIA is better than bad ARIA" applies — incorrect `aria-*` actively misleads assistive tech. React-specific structural concerns: manage **focus** across route changes and modal open/close (SPA navigation doesn't move focus the way full-page loads do, so screen-reader users lose their place — move focus to the new page's heading on route change), announce **dynamic updates** via `aria-live` regions (so toasts, validation errors, and async results are spoken), and ensure every interactive custom component has a full **keyboard** story (Tab order, Enter/Space activation, arrow-key navigation for composite widgets).

```javascript
// Announce async/route changes to screen readers via a live region
function StatusAnnouncer({ message }) {
  return <div role="status" aria-live="polite" className="sr-only">{message}</div>;
}

// Move focus to the page heading on SPA route change (restores screen-reader context)
function RouteFocus() {
  const { pathname } = useLocation();
  const ref = useRef(null);
  useEffect(() => { ref.current?.focus(); }, [pathname]);
  return <h1 ref={ref} tabIndex={-1} />;
}
```

Systematizing it across a large app: **bake a11y into the design system** so every shared `Button`, `Input`, `Modal`, and `Menu` is accessible once and correct everywhere (build on headless primitives — Radix, React Aria — that have solved focus management and keyboard interaction); **enforce in CI** with `eslint-plugin-jsx-a11y` (catches missing alt text, invalid ARIA, non-interactive elements with handlers) and automated scans (`axe-core`/`jest-axe` in component tests, Playwright + axe in E2E); and **measure what automation can't** — automated tools catch only ~30-40% of issues, so add manual **keyboard-only** runs and **screen-reader testing** (VoiceOver/NVDA) of critical flows to QA. Don't forget **color contrast** (WCAG AA ratios, enforced in design tokens), **`prefers-reduced-motion`** for animations, visible **focus indicators** (never `outline: none` without a replacement), and respecting user zoom/text scaling (relative units). The senior framing: accessibility is cheapest when it's a property of your **primitives and pipeline** (so it can't regress), expensive and incomplete when retrofitted page-by-page, and it overlaps heavily with quality generally — accessible apps tend to have cleaner semantics, better keyboard support, and tests that assert on user-visible behavior.

#### Q94. [Coding] Implement a `useCountdown` / timer hook that is drift-free and pauses correctly.

**Problem:** A countdown timer hook that doesn't drift over time (naive `setInterval(..., 1000)` accumulates error), supports pause/resume, and cleans up — useful for OTP expiry, sale timers, quiz clocks.

```javascript
import { useState, useRef, useEffect, useCallback } from "react";

function useCountdown(durationMs) {
  const [remaining, setRemaining] = useState(durationMs);
  const [running, setRunning] = useState(false);
  const deadlineRef = useRef(0);            // absolute wall-clock target
  const rafRef = useRef(0);

  const tick = useCallback(() => {
    const left = Math.max(0, deadlineRef.current - Date.now());   // recompute from clock: no drift
    setRemaining(left);
    if (left > 0) rafRef.current = requestAnimationFrame(tick);
    else setRunning(false);
  }, []);

  const start = useCallback(() => {
    deadlineRef.current = Date.now() + remaining;   // anchor to absolute time
    setRunning(true);
  }, [remaining]);

  const pause = useCallback(() => {
    cancelAnimationFrame(rafRef.current);
    setRunning(false);
    setRemaining(Math.max(0, deadlineRef.current - Date.now()));  // freeze remaining
  }, []);

  const reset = useCallback(() => {
    cancelAnimationFrame(rafRef.current);
    setRunning(false);
    setRemaining(durationMs);
  }, [durationMs]);

  useEffect(() => {
    if (running) rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);   // cleanup on pause/unmount
  }, [running, tick]);

  return { remaining, running, start, pause, reset };
}
```

**Why anchor to an absolute deadline instead of decrementing:** a naive `setInterval(() => setRemaining(r => r - 1000), 1000)` drifts because the interval is never *exactly* 1000ms (the event loop is busy, the tab is throttled), and the errors accumulate — after a minute you can be seconds off. Recomputing `remaining = deadline - Date.now()` on every tick makes each frame **self-correcting**: drift never accumulates because the displayed value is always derived from the real clock. **Why `requestAnimationFrame` over `setInterval`:** it pauses automatically in background tabs (saving battery and avoiding a backlog of throttled callbacks the browser fires in a burst on refocus) and aligns updates to frames for smooth UI; for a 1-second display you'd render at frame rate but only the second-granularity text changes, so it's cheap. **Pause/resume** works by freezing `remaining` on pause and re-anchoring the deadline on the next start. **Edge cases:** background-tab throttling (rAF stops, so on refocus the recomputed value jumps to correct — which is the desired behavior, not a bug); clamp at zero; clear the rAF on unmount. **Production note:** for a coarse countdown a `setTimeout` that re-derives from the deadline each second is even lighter than rAF. **Time/Space:** O(1).

#### Q95. [Theory] When is React the wrong choice, and how do you reason about adopting it (or moving off it) as a staff engineer?

A staff-level answer resists treating React as the default for everything and reasons from constraints. React is the **wrong** (or at least suboptimal) choice in several cases. For **mostly-static content sites** (marketing, blogs, docs), shipping a React runtime and hydrating largely non-interactive HTML is pure overhead — a static-site generator (Astro, Eleventy, Hugo) or server-rendered HTML with sprinkles of JS delivers better performance, SEO, and simplicity; Astro's "islands" exist precisely because most pages don't need a full SPA framework. For **extremely update-heavy, high-frequency UIs** (real-time trading grids with thousands of cells changing per second, complex visualizations), React's re-render-and-diff cost model can be a genuine bottleneck where **fine-grained reactivity** (SolidJS, Svelte) does work proportional to what changed, not what's on screen. For **tiny widgets or progressive enhancement** of a server-rendered app, a 40KB+ framework to add a dropdown is overkill — vanilla JS, web components, or Alpine/htmx fit better. And for teams or constraints where **bundle size is paramount** (low-end devices, emerging markets, embedded), lighter alternatives (Preact as a drop-in, or Svelte's compiled output) may be decisive.

The reasoning framework I apply as a staff engineer: (1) **start from the requirements** — interactivity level, SEO/first-paint needs, update frequency, team size and existing expertise, ecosystem dependencies, hiring market — not from a favorite framework; (2) **weigh the ecosystem and organizational factors heavily**, because they often dominate raw technical metrics — React's vast library ecosystem, hiring pool, institutional knowledge, and battle-tested patterns are a real and legitimate advantage that frequently outweighs a competitor's benchmark edge; (3) **be honest that migration cost is enormous** — rewriting a large app off React is rarely justified by performance alone, so the realistic move is usually to optimize within React (the Compiler, virtualization, offloading to workers, islands via a meta-framework) or to adopt an alternative only for a *new, isolated* surface where its strengths are decisive; (4) **avoid résumé-driven and benchmark-driven decisions** — microbenchmarks rarely reflect real app bottlenecks, and novelty is not a business value. The mature conclusion is that React is an excellent **default for interactive applications** with strong ecosystem and team advantages, and the staff engineer's job is to recognize the specific situations where those advantages don't apply (static content, extreme update throughput, ultra-low bundle budgets) and to make the call with data, a clear-eyed view of migration cost, and respect for the organizational realities — documenting the trade-offs so the decision can be revisited when constraints change.

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
