# JavaScript Interview Preparation Guide

A deep, authoritative reference for JavaScript interviews — from language fundamentals (scope, closures, prototypes, `this`) through the event loop, async patterns, performance, and the coding problems that show up again and again. Current through 2026 (ES2025/ES2024, modern runtimes).

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

### Q1. [Theory] What is the difference between `var`, `let`, and `const`, and what is hoisting?

`var` is function-scoped and is **hoisted** with an initial value of `undefined`, so referencing it before its declaration returns `undefined` rather than throwing. `let` and `const` are **block-scoped** (introduced in ES6) and are also hoisted, but they live in the **Temporal Dead Zone (TDZ)** from the top of the block until their declaration is evaluated — accessing them in the TDZ throws a `ReferenceError`. `const` additionally forbids reassignment of the binding (though the referenced object can still be mutated).

"Hoisting" is not the engine physically moving code; it is a consequence of the engine's two-phase processing: during the **creation phase** of an execution context it registers declarations, then in the **execution phase** it runs the code. Function *declarations* are fully hoisted (you can call them before they appear); function *expressions* and arrow functions assigned to `var`/`let` are not.

```javascript
console.log(a); // undefined (var hoisted, initialized to undefined)
console.log(b); // ReferenceError: Cannot access 'b' before initialization (TDZ)
var a = 1;
let b = 2;
```

Use `const` by default, `let` when you must reassign, and avoid `var` in modern code.

### Q2. [Theory] Explain the difference between `==` and `===`.

`===` is **strict equality**: it compares value *and* type with no coercion, so `1 === '1'` is `false`. `==` is **loose equality**: it applies the abstract equality algorithm, coercing operands to a common type before comparing, so `1 == '1'` is `true`. Loose equality has surprising rules: `null == undefined` is `true` (but neither equals anything else loosely), `NaN == NaN` is `false`, and `[] == ![]` evaluates to `true`. In production, always prefer `===` to avoid coercion bugs; the one common idiom where `==` is acceptable is `x == null` to check for "null or undefined" in a single test.

### Q3. [Theory] What are the primitive types in JavaScript, and how do they differ from objects?

JavaScript has seven primitives: `string`, `number`, `bigint`, `boolean`, `undefined`, `symbol`, and `null`. Primitives are **immutable** and compared **by value**; objects (including arrays and functions) are **mutable** and compared **by reference**. When you call a method on a primitive like `"abc".toUpperCase()`, the engine transparently wraps it in a temporary wrapper object (`String`), calls the method, and discards the wrapper — this is "autoboxing." A common gotcha: `typeof null` returns `"object"` (a historical bug preserved for compatibility), and `typeof NaN` returns `"number"`.

### Q4. [Coding] Implement a function to reverse a string and explain the approaches.

**Problem:** Given a string, return it reversed. JavaScript strings are immutable, so you cannot reverse in place.

```javascript
// Approach 1: Built-in array methods (concise, idiomatic)
function reverseString(str) {
  return [...str].reverse().join('');
}

// Approach 2: Manual loop (no intermediate array beyond output)
function reverseManual(str) {
  let out = '';
  for (let i = str.length - 1; i >= 0; i--) {
    out += str[i];
  }
  return out;
}

console.log(reverseString('héllo 🚀')); // handles surrogate pairs correctly
```

Use `[...str]` (spread / iterator) rather than `str.split('')` because the iterator splits by Unicode code points, correctly handling emoji and surrogate pairs; `split('')` would break a 🚀 into two broken code units.

- **Time:** O(n). **Space:** O(n) for the result (strings are immutable).
- **Edge cases:** empty string (`''`), single char, multi-byte Unicode/emoji.

### Q5. [Practical] How do you copy an array, and what is the difference between shallow and deep copy?

A **shallow copy** duplicates the top-level structure but shares nested object references; a **deep copy** recursively duplicates everything so the copy is fully independent. For a flat array, `arr.slice()`, `[...arr]`, or `Array.from(arr)` all produce a shallow copy. For objects of nested data you need a deep copy.

```javascript
const original = [{ id: 1 }, { id: 2 }];
const shallow = [...original];
shallow[0].id = 99;
console.log(original[0].id); // 99 — nested object was shared!

const deep = structuredClone(original); // built-in, modern runtimes
deep[0].id = 7;
console.log(original[0].id); // 99 — independent
```

In production, prefer **`structuredClone()`** (available in all modern browsers and Node 17+) over the old `JSON.parse(JSON.stringify(x))` trick, because the JSON approach silently drops `undefined`, functions, and `Symbol`s, mangles `Date` into strings, and throws on circular references. `structuredClone` handles `Date`, `Map`, `Set`, `ArrayBuffer`, and circular refs, but it cannot clone functions or DOM nodes.

### Q6. [Theory] What is the difference between `null` and `undefined`?

`undefined` means a variable has been declared but not assigned, or a property/return value is absent — it is the engine's default "no value." `null` is an intentional, programmer-assigned "empty value." They are loosely equal (`null == undefined`) but not strictly equal (`null === undefined` is `false`). Function parameters default only for `undefined`, not `null`: `function f(x = 5)` gives `x = 5` when called as `f()` or `f(undefined)`, but `x = null` when called as `f(null)`. JSON supports `null` but not `undefined`.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain closures and give a real-world use case.

A **closure** is the combination of a function and the lexical environment in which it was declared. When an inner function references variables from an outer scope, it retains access to those variables even after the outer function has returned — the engine keeps that environment alive on the heap rather than discarding it. Closures are the foundation of data privacy, function factories, memoization, and the module pattern.

```javascript
function createCounter() {
  let count = 0;                  // private state captured by closure
  return {
    increment: () => ++count,
    get: () => count,
  };
}
const c = createCounter();
c.increment();
console.log(c.get()); // 1 — `count` is inaccessible from outside
```

The classic interview trap is closures in loops with `var`:

```javascript
for (var i = 0; i < 3; i++) {
  setTimeout(() => console.log(i), 0); // 3, 3, 3 — all share one `i`
}
for (let i = 0; i < 3; i++) {
  setTimeout(() => console.log(i), 0); // 0, 1, 2 — `let` creates a fresh binding per iteration
}
```

**Real-world:** React's `useState`/`useCallback` rely on closures to capture state and props; a stale closure capturing an old value is the single most common React bug. **Memory implication:** closures can hold large objects alive longer than expected, causing leaks if you store the closure long-term (e.g., in an event listener you never remove).

### Q8. [Theory] How does `this` binding work? Walk through all the rules.

`this` is determined by **how a function is called**, not where it is defined (except arrow functions). The precedence, highest to lowest:

```
1. `new` binding      → new Foo()        → `this` is the freshly created object
2. Explicit binding   → fn.call/apply/bind(obj) → `this` is obj
3. Implicit binding   → obj.method()      → `this` is obj (the receiver)
4. Default binding    → fn()              → `this` is undefined (strict) or globalThis (sloppy)
```

**Arrow functions** ignore all of the above — they have no own `this` and lexically inherit it from the enclosing scope, which is why they are perfect for callbacks where you want to preserve the surrounding `this`. The classic bug:

```javascript
const obj = {
  name: 'API',
  regular() { return function () { return this.name; }; },
  arrow()   { return () => this.name; },
};
console.log(obj.regular()()); // undefined — inner fn called with default binding
console.log(obj.arrow()());   // 'API'     — arrow inherits obj as `this`
```

`bind` returns a permanently bound copy that cannot be re-bound; `class` methods are not auto-bound, so passing `this.handleClick` to an event listener loses `this` unless you bind it or use a class field arrow (`handleClick = () => {}`).

### Q9. [Theory] Explain the prototype chain and prototypal inheritance.

Every object has an internal `[[Prototype]]` link (accessible via `Object.getPrototypeOf(obj)` or the legacy `__proto__`). When you read a property, the engine checks the object itself, then walks up the prototype chain until it finds the property or reaches `null`. This is **prototypal inheritance** — objects inherit directly from other objects, unlike classical class-based inheritance.

```
myArray ──[[Prototype]]──▶ Array.prototype ──▶ Object.prototype ──▶ null
  (own:                      (push, map,          (toString,
   indices, length)           filter, …)           hasOwnProperty)
```

A function's `prototype` property is the object that becomes the `[[Prototype]]` of instances created with `new`. ES6 `class` syntax is **syntactic sugar** over this mechanism — `class extends` sets up the prototype chain under the hood. Key methods: `Object.create(proto)` makes an object with a chosen prototype; `obj.hasOwnProperty('x')` checks own (non-inherited) properties; `Object.getPrototypeOf` reads the chain. Avoid mutating built-in prototypes (`Array.prototype.foo = ...`) — it pollutes every array globally and breaks `for...in`.

### Q10. [Theory] Explain the event loop, call stack, microtasks, and macrotasks.

JavaScript is single-threaded: one **call stack** runs synchronous code to completion ("run-to-completion"). Asynchronous work is handled by the host (browser/Node), which places callbacks into queues. The **event loop** repeatedly: (1) runs the oldest task in the **macrotask queue** (timers, I/O, `setTimeout`, message/DOM events), then (2) **drains the entire microtask queue** (Promise callbacks, `queueMicrotask`, `MutationObserver`) before rendering or taking the next macrotask.

```
   ┌──────────────┐
   │  Call Stack  │ ◀── runs sync code to completion
   └──────┬───────┘
          │ when empty
          ▼
   ┌──────────────────────┐
   │ Drain ALL microtasks │ ◀── Promise .then, queueMicrotask
   └──────┬───────────────┘
          │ then
          ▼
   ┌──────────────────────┐
   │ Take ONE macrotask   │ ◀── setTimeout, I/O, events
   └──────┬───────────────┘
          └──────▶ (loop back to drain microtasks)
```

This explains output ordering:

```javascript
console.log('1');
setTimeout(() => console.log('2'), 0);  // macrotask
Promise.resolve().then(() => console.log('3')); // microtask
console.log('4');
// Output: 1, 4, 3, 2
```

Microtasks always run before the next macrotask. A pitfall: an infinite chain of microtasks can **starve** the macrotask queue and freeze rendering. In Node, `process.nextTick` runs even before the Promise microtask queue.

### Q11. [Coding] Implement `debounce` and `throttle`.

**Problem:** `debounce(fn, wait)` delays invoking `fn` until `wait` ms have passed since the *last* call (good for search-as-you-type, resize). `throttle(fn, wait)` invokes `fn` at most once per `wait` ms (good for scroll, mousemove).

```javascript
function debounce(fn, wait) {
  let timer;
  function debounced(...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), wait);
  }
  debounced.cancel = () => clearTimeout(timer);
  return debounced;
}

function throttle(fn, wait) {
  let last = 0, timer = null;
  return function throttled(...args) {
    const now = Date.now();
    const remaining = wait - (now - last);
    if (remaining <= 0) {              // leading edge
      if (timer) { clearTimeout(timer); timer = null; }
      last = now;
      fn.apply(this, args);
    } else if (!timer) {               // trailing edge
      timer = setTimeout(() => {
        last = Date.now();
        timer = null;
        fn.apply(this, args);
      }, remaining);
    }
  };
}
```

- **Why `apply(this, args)`:** preserves the caller's `this` and arguments so it works as a method/event handler.
- **Time/Space:** O(1) per call, O(1) extra state (closure over `timer`/`last`).
- **Edge cases:** rapid bursts (debounce only fires once at the end), trailing call needed after the last throttle window, and providing a `cancel()` for cleanup on unmount to prevent calling on a destroyed component.

### Q12. [Coding] Implement `Promise.all` from scratch.

**Problem:** `Promise.all(iterable)` resolves with an array of results when *all* promises resolve, in input order, and rejects immediately if *any* rejects.

```javascript
function promiseAll(promises) {
  return new Promise((resolve, reject) => {
    const items = [...promises];
    const results = new Array(items.length);
    let remaining = items.length;
    if (remaining === 0) return resolve([]); // empty input

    items.forEach((p, i) => {
      // Promise.resolve normalizes non-promise values and thenables
      Promise.resolve(p).then(
        (value) => {
          results[i] = value;            // preserve order by index
          if (--remaining === 0) resolve(results);
        },
        reject                            // first rejection wins
      );
    });
  });
}
```

- **Order:** results are placed by index, not by completion time, so output order matches input order even though resolution is concurrent.
- **Time:** bounded by the slowest promise. **Space:** O(n) for results.
- **Edge cases:** empty iterable resolves to `[]`; non-promise values are wrapped via `Promise.resolve`; one rejection rejects the whole thing (compare with `Promise.allSettled`, which never short-circuits).

### Q13. [Theory] Compare `Promise.all`, `allSettled`, `race`, and `any`.

`Promise.all` resolves with all values or rejects on the first rejection (fail-fast). `Promise.allSettled` (ES2020) always resolves with an array of `{status, value | reason}` objects — use it when you want every result regardless of failures (e.g., dashboard widgets where one failing API shouldn't blank the page). `Promise.race` settles as soon as the *first* promise settles, whether fulfilled or rejected — useful for timeouts. `Promise.any` (ES2021) resolves with the first *fulfillment* and only rejects (with an `AggregateError`) if *all* reject — useful for hitting redundant mirrors and taking the fastest success. The trade-off is fail-fast vs. resilience: choose `all` when partial data is useless, `allSettled` when you can render partial data.

### Q14. [Practical] What is event delegation and why use it?

Event delegation attaches a **single listener to a common ancestor** and uses `event.target` to determine which descendant triggered it, leveraging event bubbling. Instead of N listeners on N rows, you put one listener on the container.

```javascript
document.querySelector('#list').addEventListener('click', (e) => {
  const item = e.target.closest('li[data-id]');
  if (!item) return;                 // ignore clicks outside items
  handleSelect(item.dataset.id);
});
```

**Why in production:** (1) far less memory and fewer listeners for large or virtualized lists; (2) it works for **dynamically added** elements with no re-binding; (3) easier teardown. Trade-offs: events that don't bubble (`focus`, `blur`, `scroll`) can't be delegated directly (use `focusin`/capture phase), and you must guard with `closest()` so clicks on nested elements still resolve to the right target.

### Q15. [Coding] Implement a deep clone that handles circular references.

**Problem:** Recursively copy nested objects/arrays without infinite loops on cycles.

```javascript
function deepClone(value, seen = new WeakMap()) {
  if (value === null || typeof value !== 'object') return value; // primitives
  if (value instanceof Date) return new Date(value);
  if (value instanceof RegExp) return new RegExp(value.source, value.flags);
  if (seen.has(value)) return seen.get(value);                   // cycle guard

  const result = Array.isArray(value) ? [] : {};
  seen.set(value, result);                                       // register before recursing

  if (value instanceof Map) {
    value.forEach((v, k) => result.set?.(deepClone(k, seen), deepClone(v, seen)));
  }
  for (const key of Reflect.ownKeys(value)) {                    // includes symbols
    result[key] = deepClone(value[key], seen);
  }
  return result;
}
```

- **`WeakMap` (`seen`)** maps already-cloned source objects to their clones so circular references resolve to the existing clone instead of recursing forever; `WeakMap` also lets those entries be garbage-collected.
- **Time:** O(n) in total nodes. **Space:** O(n) for clones + O(depth) call stack.
- **Edge cases:** cycles, `Date`/`RegExp`/`Map`/`Set`, symbol keys, and the fact that functions/DOM nodes can't be meaningfully cloned. In production, prefer `structuredClone()` unless you need custom behavior.

### Q16. [Theory] Explain `async`/`await` and how it relates to Promises and generators.

`async`/`await` is syntactic sugar over Promises that lets you write asynchronous code in a synchronous-looking style. An `async` function always returns a Promise; `await` pauses the function until the awaited Promise settles, scheduling the continuation as a **microtask**. Under the hood it is modeled on generators — `await` is analogous to `yield`, and the engine resumes the function when the Promise resolves. Errors propagate as rejected Promises, so you catch them with `try/catch`. A key performance mistake is **awaiting in a loop sequentially** when calls are independent:

```javascript
// Slow: sequential (sum of all latencies)
for (const id of ids) results.push(await fetchUser(id));

// Fast: concurrent (max of all latencies)
const results = await Promise.all(ids.map(fetchUser));
```

Use sequential `await` only when each step depends on the previous one.

### Q17. [Theory] What are generators and iterators, and when would you use them?

A **generator** is a function declared with `function*` that can pause and resume via `yield`, returning an **iterator** that produces values lazily on each `.next()` call. Iterators implement the **iterable protocol** (`Symbol.iterator`), which is what powers `for...of`, spread, and destructuring. Generators are ideal for **lazy/infinite sequences**, custom iteration, and processing streams without materializing everything in memory.

```javascript
function* idGenerator() {
  let id = 1;
  while (true) yield id++;   // infinite, but lazy — only computes on demand
}
const gen = idGenerator();
console.log(gen.next().value, gen.next().value); // 1 2
```

`yield*` delegates to another iterable. Async generators (`async function*` with `for await...of`) are the modern way to consume paginated APIs or streamed data chunk by chunk. They were the historical foundation for async/await before native support.

### Q18. [Practical] How do you find and prevent memory leaks in JavaScript?

The common leak sources are: (1) **forgotten timers/intervals** that keep closures alive; (2) **event listeners not removed** when a component unmounts; (3) **detached DOM nodes** still referenced by JS; (4) growing **global** caches/arrays; (5) **closures** capturing large objects. To diagnose, use Chrome DevTools **Memory** tab: take **heap snapshots** before/after an action and compare retained size, or record an **Allocation timeline** to see what survives GC. A telltale sign is a sawtooth memory graph that trends upward — memory not returning to baseline after GC.

**Prevention:** always pair `addEventListener` with `removeEventListener` (or use `AbortController` + `{ signal }`), clear timers in cleanup, prefer **`WeakMap`/`WeakSet`** for metadata keyed by objects so entries are GC'd when the key dies, and bound cache sizes (LRU). In frameworks, return a cleanup function from `useEffect` / use `ngOnDestroy`.

```javascript
const controller = new AbortController();
window.addEventListener('resize', onResize, { signal: controller.signal });
// later, one call removes all listeners registered with this signal:
controller.abort();
```

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Coding] Implement function currying with support for partial application.

**Problem:** `curry(fn)` transforms `fn(a, b, c)` so it can be called as `curry(fn)(a)(b)(c)`, `curry(fn)(a, b)(c)`, or `curry(fn)(a, b, c)` — collecting arguments until `fn.length` are supplied.

```javascript
function curry(fn) {
  return function curried(...args) {
    if (args.length >= fn.length) {
      return fn.apply(this, args);          // enough args → invoke
    }
    return (...next) => curried.apply(this, [...args, ...next]); // gather more
  };
}

const sum = (a, b, c) => a + b + c;
const cs = curry(sum);
console.log(cs(1)(2)(3));   // 6
console.log(cs(1, 2)(3));   // 6
console.log(cs(1)(2, 3));   // 6
```

Currying enables **partial application** — pre-filling arguments to build specialized functions (e.g., `const add5 = cs(5)`), which is heavily used in functional pipelines and configuration. **Time/Space:** O(arity) accumulation; each partial creates a small closure. **Edge cases:** functions with rest params or default params have `fn.length` that excludes them, so currying by arity needs an explicit arity argument in those cases.

### Q20. [Theory] Explain how garbage collection works and what "mark-and-sweep" means.

Modern engines (V8) use **reachability-based** garbage collection: an object is collectable if it is no longer reachable from a set of **roots** (the global object, the current call stack, active closures). The classic algorithm is **mark-and-sweep**: starting from roots, the GC *marks* every reachable object, then *sweeps* (frees) everything unmarked. V8 refines this with a **generational** model — a small "young generation" (Scavenger, fast copying collector) for short-lived objects, and an "old generation" (Mark-Compact, incremental/concurrent) for survivors — because most objects die young (the "generational hypothesis"). It runs concurrently and incrementally to avoid long pauses. The practical takeaway: you don't manually free memory, but you *do* control reachability — anything still referenced (in a global, a long-lived closure, or a non-weak collection) will never be collected, which is exactly how leaks happen.

### Q21. [Practical] Walk through diagnosing a production performance issue caused by JavaScript.

**Scenario:** A data-heavy dashboard janks (drops frames) when users scroll a large table. **Approach:** First, reproduce and profile with the DevTools **Performance** panel; look for long tasks (>50 ms) blocking the main thread, forced synchronous layout ("layout thrashing" from reading `offsetHeight` then writing styles in a loop), and excessive GC. **Findings → fixes:**

- Long main-thread tasks → **virtualize** the list (render only visible rows), and offload heavy computation to a **Web Worker**.
- Layout thrashing → batch DOM reads then writes; use `requestAnimationFrame` and `IntersectionObserver` instead of scroll-handler measurements.
- Excessive event firing → **throttle** scroll handlers.
- Excessive re-renders (in React) → memoize, and check for new object/array literals in props.

**Real-world case study:** Many teams have cut Time-to-Interactive dramatically by code-splitting bundles (dynamic `import()`), deferring non-critical JS, and moving parsing/hashing work into workers — the principle is the same: keep the main thread free so the 16.6 ms-per-frame budget (60 fps) is met. **What I'd do in production:** establish a performance budget, add real-user monitoring (Core Web Vitals: LCP, INP, CLS), and gate regressions in CI with Lighthouse.

### Q22. [Theory] What are `Symbol`s and well-known symbols, and why do they matter?

A `Symbol` is a unique, immutable primitive often used as a **non-colliding property key** — two symbols are never equal even with the same description, which makes them safe for adding "hidden" metadata to objects without clashing with string keys or being exposed by `for...in`/`Object.keys`. **Well-known symbols** let you hook into language internals: `Symbol.iterator` makes an object iterable (`for...of`), `Symbol.asyncIterator` enables `for await...of`, `Symbol.toPrimitive` customizes coercion, and `Symbol.hasInstance` customizes `instanceof`. `Symbol.for(key)` uses a global registry for cross-realm sharing. They matter for library authors building protocols and for understanding how built-ins like arrays and maps integrate with language syntax.

### Q23. [Coding] Implement an LRU (Least Recently Used) cache.

**Problem:** Build a cache with `get(key)` and `put(key, value)` in O(1), evicting the least-recently-used entry when capacity is exceeded. JavaScript's `Map` preserves insertion order, which we exploit.

```javascript
class LRUCache {
  constructor(capacity) {
    this.capacity = capacity;
    this.map = new Map();
  }
  get(key) {
    if (!this.map.has(key)) return -1;
    const value = this.map.get(key);
    this.map.delete(key);      // re-insert to mark as most-recently-used
    this.map.set(key, value);
    return value;
  }
  put(key, value) {
    if (this.map.has(key)) this.map.delete(key);
    else if (this.map.size >= this.capacity) {
      this.map.delete(this.map.keys().next().value); // evict oldest (first) key
    }
    this.map.set(key, value);
  }
}
```

- **Why `Map`:** iteration order = insertion order, and the first key from `.keys()` is the oldest. `delete`+`set` re-orders to most-recent in O(1).
- **Time:** O(1) for both ops. **Space:** O(capacity).
- **Edge cases:** capacity of 0, updating an existing key (must refresh recency, not evict), and missing keys. A purely textbook version uses a doubly linked list + hash map; the `Map` approach is cleaner and idiomatic in JS.

### Q24. [Theory] Explain `Proxy` and `Reflect` and a use case.

A `Proxy` wraps a target object and intercepts fundamental operations ("traps") like `get`, `set`, `has`, `deleteProperty`, and `apply`. `Reflect` provides the default implementations of those operations as functions (`Reflect.get`, `Reflect.set`, …), so inside a trap you can perform the normal behavior and then add your own logic. Together they enable powerful metaprogramming: reactive frameworks (Vue 3's reactivity system is built on `Proxy`), validation, logging, negative-index arrays, and lazy/observable objects.

```javascript
const validated = new Proxy({}, {
  set(target, key, value, receiver) {
    if (key === 'age' && (typeof value !== 'number' || value < 0)) {
      throw new TypeError('age must be a non-negative number');
    }
    return Reflect.set(target, key, value, receiver); // default behavior + correct `receiver`
  },
});
validated.age = 30;     // ok
// validated.age = -1;  // throws
```

Trade-off: proxies add per-operation overhead and can't be polyfilled, so don't wrap hot-path objects indiscriminately.

### Q25. [Theory] How do JavaScript modules (ESM) differ from CommonJS, and what is the impact?

**ES Modules (ESM)** use `import`/`export`, are **statically analyzable** (imports are resolved before execution), are **strict mode by default**, have **live bindings** (an imported value reflects later changes in the exporter), and load **asynchronously**. **CommonJS (CJS)** uses `require`/`module.exports`, is **synchronous and dynamic** (you can `require` conditionally), and exports a **copied value snapshot** at the time of require. Static structure is what enables **tree-shaking** (dead-code elimination) in bundlers — a major bundle-size win that CJS can't reliably do. The trade-offs and interop pain: CJS can't `require()` an ESM module synchronously (it must use dynamic `import()`), and `__dirname`/`require` aren't defined in ESM (use `import.meta.url`). Node now supports both; in 2024–2026 the ecosystem has largely shifted to ESM-first, with `"type": "module"` and conditional `exports` in `package.json`.

### Q26. [Coding] Implement an `EventEmitter` (pub/sub).

**Problem:** Build `on`, `off`, `once`, and `emit` for a simple event system — the backbone of decoupled architectures.

```javascript
class EventEmitter {
  constructor() { this.listeners = new Map(); }

  on(event, fn) {
    if (!this.listeners.has(event)) this.listeners.set(event, new Set());
    this.listeners.get(event).add(fn);
    return () => this.off(event, fn); // return an unsubscribe handle
  }
  off(event, fn) { this.listeners.get(event)?.delete(fn); }

  once(event, fn) {
    const wrapper = (...args) => { this.off(event, wrapper); fn(...args); };
    return this.on(event, wrapper);
  }
  emit(event, ...args) {
    // copy to a snapshot so handlers that add/remove during emit don't break iteration
    [...(this.listeners.get(event) ?? [])].forEach((fn) => fn(...args));
  }
}
```

- **Why `Set`:** O(1) add/remove and natural dedup of identical listeners.
- **Why snapshot in `emit`:** prevents bugs when a handler unsubscribes itself or others mid-emit.
- **Time:** `emit` is O(k) in listeners for that event; others O(1). **Space:** O(total listeners).
- **Edge cases:** removing during emit, `once` cleanup, emitting an event with no listeners, and avoiding leaks by returning/honoring unsubscribe functions.

---

## 🔴 Expert (15+ yrs)

### Q27. [Theory] How does the V8 engine optimize JavaScript (JIT, hidden classes, inline caches)?

V8 compiles JavaScript through a tiered pipeline: **Ignition** (a fast bytecode interpreter) runs code immediately, while the **TurboFan** (and the newer **Maglev** mid-tier) **JIT** compilers optimize "hot" functions into machine code based on runtime type feedback. To make property access fast, V8 builds **hidden classes** (a.k.a. "maps" or "shapes") — internal descriptors of an object's layout. Objects created with the same property set *in the same order* share a hidden class, enabling **inline caches** that turn dynamic lookups into near-direct memory offsets. If you later add properties in a different order or change a field's type, you cause **shape transitions** or **deoptimization** ("deopt"), falling back to slow generic code. **Practical guidance for hot paths:** initialize all object fields in the constructor in a consistent order, keep arrays monomorphic (don't mix numbers and objects in a packed array — avoid creating "holey" arrays), and keep functions monomorphic (called with consistently shaped arguments). This is why ergonomic patterns sometimes conflict with peak performance, and why micro-optimizing matters only on genuinely hot code.

### Q28. [Theory] Explain JavaScript concurrency beyond the event loop: Web Workers, SharedArrayBuffer, and Atomics.

The event loop gives **concurrency** but not **parallelism** — everything still runs on one thread. **Web Workers** (and Node's `worker_threads`) provide true parallelism by running scripts on separate OS threads with isolated heaps; they communicate via `postMessage`, which **structured-clones** data by default (copy), or **transfers** ownership of `ArrayBuffer`/`MessagePort` (zero-copy). For genuinely shared memory, **`SharedArrayBuffer`** exposes the same bytes to multiple threads, and **`Atomics`** provides lock-free atomic operations (`Atomics.add`, `compareExchange`, `wait`/`notify`) to coordinate safely without data races. **Security note:** because `SharedArrayBuffer` + high-resolution timers enabled the Spectre side-channel attack, browsers re-gated it behind **cross-origin isolation** (`Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy: require-corp`). In practice you reach for workers when CPU-bound work (parsing, crypto, image processing, large data transforms) would otherwise block the UI; the trade-off is serialization cost and added complexity.

### Q29. [Practical] Describe the security implications you watch for in JavaScript applications.

The headline risk is **XSS (cross-site scripting)**: never build DOM from untrusted input via `innerHTML`, `document.write`, or `eval`/`new Function`. Use `textContent`, framework escaping, and **Trusted Types** + a strict **Content-Security-Policy** to block inline scripts. **Prototype pollution** is a JavaScript-specific vulnerability where attacker-controlled keys like `__proto__` in a deep-merge or `JSON.parse`-then-assign flow mutate `Object.prototype`, affecting every object — mitigate by using `Object.create(null)` for maps, `Map` instead of plain objects for user-keyed data, and reputable libraries that guard against `__proto__`/`constructor`/`prototype` keys. Watch **supply-chain risk**: a single malicious npm dependency runs with your app's privileges, so pin versions, audit (`npm audit`, lockfile integrity), and minimize the dependency surface. Other concerns: avoid leaking secrets into client bundles, set `SameSite`/`HttpOnly` cookies and CSRF tokens server-side, and treat `postMessage` origins strictly (always check `event.origin`).

### Q30. [Coding] Implement a Promise-based retry with exponential backoff and a timeout.

**Problem:** Wrap an async operation so it retries on failure with exponentially increasing delays (plus jitter to avoid thundering herds) and aborts each attempt after a timeout.

```javascript
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function withTimeout(promise, ms) {
  return Promise.race([
    promise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error(`Timeout after ${ms}ms`)), ms)
    ),
  ]);
}

async function retry(task, { retries = 5, baseDelay = 200, timeout = 3000 } = {}) {
  let lastError;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await withTimeout(task(attempt), timeout);
    } catch (err) {
      lastError = err;
      if (attempt === retries) break;
      const backoff = baseDelay * 2 ** attempt;          // 200, 400, 800, …
      const jitter = Math.random() * baseDelay;          // decorrelated jitter
      await sleep(backoff + jitter);
    }
  }
  throw lastError;
}
```

- **Why backoff + jitter:** prevents synchronized retries (the "thundering herd") from hammering a recovering service.
- **Time/Space:** O(retries) attempts; O(1) state. **Edge cases:** distinguish retryable errors (5xx, network) from non-retryable (4xx) so you don't retry a `400`; honor an external `AbortController`; cap total elapsed time. In production this pairs with a **circuit breaker** to stop retrying a hard-down dependency.

### Q31. [Behavioral] Tell me about a time you led the resolution of a hard JavaScript performance or reliability problem.

**Situation:** Use the STAR structure. Example: "Our SPA's interaction latency (INP) regressed badly after a feature launch; users reported the UI freezing on input." **Task:** I owned restoring responsiveness without rolling back the feature. **Action:** I profiled with the Performance panel and found a synchronous, recursive data-normalization step running on every keystroke on the main thread, plus a memory leak from un-removed listeners in a frequently mounted component. I moved the normalization into a Web Worker, debounced the input, and standardized listener cleanup with `AbortController`; I also added a CI performance budget with Lighthouse so regressions would be caught pre-merge. **Result:** INP dropped from ~600 ms to under 100 ms, the leak (visible as upward-trending heap snapshots) was eliminated, and we prevented recurrence with the budget. **What I'd emphasize as a leader:** I framed it around user-facing metrics, brought the team along with a shared profiling playbook, and treated prevention (CI gating, monitoring) as part of "done," not just the one-off fix.

### Q32. [Theory] What is Temporal Dead Zone really protecting against, and how does it interact with closures and module evaluation order?

The TDZ exists to make `let`/`const` behave like true lexical constants — it turns "use before initialization" from a silently-`undefined` bug (`var`'s behavior) into a loud `ReferenceError`, which is essential for `const` correctness and for catching ordering mistakes. It interacts subtly with **closures** and **circular module imports**: a closure can *reference* a TDZ variable as long as it's only *called* after initialization, which is exactly how mutual recursion across module boundaries works under ESM. With **circular ESM imports**, the live-binding model means an imported binding may be in its TDZ if module A runs code at load time that uses an export of module B before B has finished initializing it — accessing it throws. Senior engineers design module graphs to avoid top-level circular dependencies, or defer cross-module access into functions that run after all modules have evaluated, precisely because TDZ + live bindings make the failure explicit rather than silent.

### Q33. [Practical] When would you deliberately avoid `async/await` or Promises, and what alternatives do you reach for?

Despite their ergonomics, Promises aren't always the right tool. For **streams of values over time** (user input, WebSocket messages, server-sent events), a single-shot Promise is the wrong shape — I reach for **async iterators** (`for await...of`) or an observable/stream abstraction (`ReadableStream`, RxJS) that supports backpressure and cancellation. For **cancellation**, Promises have no built-in abort, so I pair `fetch` with an **`AbortController`** rather than racing a discarded Promise that keeps running in the background. For **fire-and-forget** work that shouldn't block, I avoid awaiting and instead schedule it, but I always attach a `.catch` to prevent unhandled-rejection crashes. And in extremely hot synchronous loops, introducing microtask scheduling via `await` adds overhead and can fragment a tight computation — there I keep code synchronous. The meta-point: choose the concurrency primitive that matches the *cardinality* (one value vs. many) and *lifecycle* (cancelable, backpressured) of the work.

### Q34. [Theory] Explain `WeakMap`, `WeakSet`, `WeakRef`, and `FinalizationRegistry` and where each fits.

`WeakMap`/`WeakSet` hold **weak references to their keys/values** — entries don't prevent garbage collection and vanish automatically when the only remaining reference is the weak collection. They are ideal for associating metadata or caches with objects you don't own (e.g., DOM nodes) without causing leaks, and they are non-enumerable (no size, no iteration) by design. `WeakRef` (ES2021) wraps a single object weakly; calling `.deref()` returns the object or `undefined` if it's been collected — useful for caches that should not keep large objects alive but can recompute on a miss. `FinalizationRegistry` lets you register a cleanup callback to run *after* an object is collected (e.g., to release an associated native resource). The strong caution: GC timing is non-deterministic, so `WeakRef`/`FinalizationRegistry` must never be relied upon for program correctness or prompt cleanup — they are best-effort optimizations, and over-reliance on them is a common senior-level anti-pattern.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q35. [Theory] Why is `0.1 + 0.2 !== 0.3` in JavaScript, and how do you compare floats safely?

JavaScript has a single numeric type for non-BigInt values: the IEEE 754 **double-precision (64-bit) binary floating point** number. The problem is that `0.1`, `0.2`, and `0.3` cannot be represented exactly in *binary* — just as `1/3` has no finite decimal representation, `1/10` has no finite *binary* representation. The 64-bit format stores 1 sign bit, 11 exponent bits, and 52 fraction bits, so `0.1` is stored as the closest representable value (slightly more than 0.1). When you add the rounded `0.1` and rounded `0.2`, the result rounds to `0.30000000000000004`, which is a different representable value than the rounded `0.3`.

```javascript
console.log(0.1 + 0.2);            // 0.30000000000000004
console.log(0.1 + 0.2 === 0.3);   // false
console.log((0.1 + 0.2).toFixed(2)); // "0.30" (string, for display)
```

The correct comparison uses an **epsilon tolerance** — checking whether two numbers are "close enough" rather than bit-identical. `Number.EPSILON` (≈ 2.22e-16) is the smallest gap between 1 and the next representable double, which makes it a reasonable tolerance for values near 1:

```javascript
function nearlyEqual(a, b, epsilon = Number.EPSILON) {
  return Math.abs(a - b) < epsilon;
}
nearlyEqual(0.1 + 0.2, 0.3); // true
```

The deeper trade-off is **why JavaScript chose binary doubles at all**: they are fast (hardware-native), cover a huge range, and match C/Java conventions. The cost is that decimal-exact arithmetic (money, especially) is unsafe. In production, represent money as **integer cents** (or use a decimal library / the upcoming `Decimal` proposal), never as floats, and reserve epsilon comparison for physical/geometric quantities where small error is acceptable. A fixed-epsilon comparison is itself naive for very large or very tiny magnitudes — a robust version scales epsilon relative to the magnitude of the operands.

#### Q36. [Theory] What exactly is "truthy" and "falsy," and how does `Boolean()` coercion differ from `==`?

JavaScript defines exactly **eight falsy values**: `false`, `0`, `-0`, `0n` (BigInt zero), `""` (empty string), `null`, `undefined`, and `NaN`. *Everything else* is truthy — including `"0"`, `"false"`, `[]`, `{}`, and `function(){}`. "Truthiness" is the result of the **ToBoolean** abstract operation, which the engine applies in boolean contexts: `if`, `while`, `?:`, `!`, and the operands of `&&`/`||`. ToBoolean never throws and never coerces to an intermediate type — it is a direct table lookup on the value's type.

```javascript
if ([]) console.log('arrays are truthy');   // logs — empty array is truthy
if ("0") console.log('"0" is truthy');       // logs — non-empty string
console.log(Boolean(new Boolean(false)));    // true — it's an object!
```

This is fundamentally different from **loose equality (`==`)**, which runs the *abstract equality* algorithm and may coerce both sides to numbers. The classic confusion is that `[] == false` is `true` (both coerce to `0`) yet `if ([])` is truthy — because the two operations use entirely different rules. ToBoolean asks "is this value falsy?"; `==` asks "are these two values equal after coercion?" They are not the same question.

```
ToBoolean([])  → true   (object → always true)
[] == false    → true   ([] → "" → 0, false → 0, 0 === 0)
```

The practical guidance: use truthiness checks (`if (value)`) deliberately, and beware guarding numeric inputs with it — `if (count)` wrongly skips when `count` is `0`, and `if (name)` wrongly skips an empty string. When `0`/`""` are valid values, test explicitly (`if (value != null)` or `if (value !== undefined)`) instead of relying on truthiness.

#### Q37. [Theory] How do `Map`/`Set` differ from plain objects internally, and when should you prefer each?

A plain object is, semantically, a string/symbol-keyed property bag tied into the prototype chain, optimized by V8 with **hidden classes** for objects whose shape is stable. A `Map` is a purpose-built **keyed collection** that allows **any value as a key** (objects, functions, `NaN`), preserves **insertion order** deterministically, exposes `size` in O(1), and is directly iterable. The internal data structures differ: objects are optimized for a fixed, known set of named properties (records/structs); `Map`/`Set` are optimized as **hash tables for dynamic, arbitrary keys** that grow and shrink at runtime.

```javascript
const m = new Map();
const keyObj = { id: 1 };
m.set(keyObj, 'meta');          // object key — impossible with plain objects
m.set(NaN, 'works');            // NaN as key works; m.get(NaN) → 'works'

const o = {};
o[keyObj] = 'meta';             // key becomes the STRING "[object Object]"
console.log(Object.keys(o));    // ["[object Object]"] — collision-prone
```

| Aspect | Plain Object | `Map` |
|---|---|---|
| Key types | string, symbol only | any value |
| Order | insertion (ints sorted first) | strict insertion |
| Size | `Object.keys(o).length` O(n) | `.size` O(1) |
| Prototype keys | inherits `Object.prototype` | none (no pollution) |
| JSON | native | needs conversion |
| Perf for frequent add/delete | shape churn / deopt | designed for it |

Prefer `Map` when keys are **dynamic, non-string, or user-controlled** (the latter also dodges prototype-pollution because `Map` has no `__proto__` key footgun), when you frequently add/delete entries, or when you need ordered iteration and a fast `size`. Prefer a plain object for **fixed, known-shape records** that you serialize to JSON, destructure, or pass as configuration — objects are lighter to allocate, ergonomically destructurable, and JSON-native. Using `Object.create(null)` gives you a prototype-less "dictionary object" that avoids inherited-key surprises but still lacks `Map`'s ordering and `size` guarantees.

### 🟡 Intermediate — extended

#### Q38. [Theory] Walk through the abstract `==` coercion algorithm step by step. Why does `[] == ![]` equal `true`?

Loose equality (`==`) follows the **Abstract Equality Comparison** algorithm from the spec, applied recursively until both sides share a type. The key steps: (1) if types match, defer to strict equality; (2) `null == undefined` is `true` and they equal nothing else; (3) number vs. string → coerce the string to a number; (4) boolean → coerce the boolean to a number first; (5) object vs. primitive → call **ToPrimitive** on the object (via `Symbol.toPrimitive`, then `valueOf`, then `toString`); (6) BigInt/number comparisons compare mathematical values.

Tracing `[] == ![]`:

```
[] == ![]
  ![]  →  ! (truthy)  →  false           // step: evaluate RHS unary NOT first
[] == false
  false → ToNumber(false) → 0             // boolean operand coerced to number
[] == 0
  ToPrimitive([]) → [].toString() → ""    // array → "" (empty join)
"" == 0
  ToNumber("") → 0                        // string vs number → string to number
0 == 0  →  true
```

So `[]` is truthy as a *value* (ToBoolean), yet `[] == false` is `true` because the comparison path coerces both operands to the number `0`. This is the canonical demonstration that **ToBoolean and abstract equality are independent algorithms** — the same value can be "truthy" and also loosely-equal to `false`.

```javascript
console.log([] == ![]);     // true  (both → 0)
console.log([] == []);      // false (two distinct object references)
console.log("" == 0);       // true
console.log(null == 0);     // false (null only loosely-equals undefined)
```

The lesson for production: `==` invokes a multi-step coercion machine with non-obvious object-to-primitive conversions, which is why linters flag it. Use `===` to skip the entire algorithm, and reserve `== null` as the single idiomatic exception (it cleanly tests "null or undefined").

#### Q39. [Theory] What is `ToPrimitive`, and how do `Symbol.toPrimitive`, `valueOf`, and `toString` interact during coercion?

`ToPrimitive(input, hint)` is the internal operation that converts an object to a primitive whenever the language needs one — string concatenation, arithmetic, template literals, `==` with a primitive, or using an object as a property key. It takes a **hint**: `"string"` (e.g., template literals, `String(obj)`), `"number"` (e.g., `-obj`, `obj > 1`), or `"default"` (e.g., `obj + x`, `obj == primitive`). The hint determines the *order* in which the engine tries the object's conversion methods.

The resolution order is: (1) if the object has a `Symbol.toPrimitive` method, call it with the hint and use its result; (2) otherwise, for hint `"string"` try `toString()` then `valueOf()`; (3) for hint `"number"` or `"default"` try `valueOf()` then `toString()`. Whichever returns a primitive first wins; if neither does, it throws a `TypeError`.

```javascript
const money = {
  amount: 100,
  [Symbol.toPrimitive](hint) {
    if (hint === 'string') return `$${this.amount}`;
    if (hint === 'number') return this.amount;
    return `Money(${this.amount})`;     // "default"
  },
};
console.log(`${money}`);   // "$100"        (hint: string)
console.log(+money);       // 100           (hint: number)
console.log(money + '!');  // "Money(100)!" (hint: default)
```

This explains long-standing puzzles: `[] + []` is `""` (both arrays' `toString()` yield `""`), `[] + {}` is `"[object Object]"`, and `({}).valueOf()` returns the object itself (not a primitive) so the engine falls through to `toString()`. Knowing the hint/order is what lets library authors build ergonomic value-objects (money, dates, big-decimals) that behave correctly in template strings, comparisons, and arithmetic — and lets you debug "why did my object stringify to `[object Object]`" (it has no useful `toString`/`Symbol.toPrimitive`).

#### Q40. [Theory] Explain the difference between `function` declarations, function expressions, and arrow functions across hoisting, `this`, `arguments`, and constructability.

These three forms differ in four independent dimensions, and conflating them is a frequent source of bugs. **Hoisting:** a *function declaration* (`function f(){}`) is fully hoisted — both name and body are available before the line executes. A *function expression* (`const f = function(){}`) hoists only the binding (subject to TDZ for `let`/`const`, `undefined` for `var`), so calling it early throws. An *arrow function* assigned to a variable behaves like an expression for hoisting.

**`this` binding:** declarations and expressions get a *dynamic* `this` determined by call-site (`new` > explicit > implicit > default). Arrow functions have **no own `this`** — they capture it lexically from the enclosing scope, permanently. **`arguments`:** declarations/expressions have an `arguments` object; arrows do not (they inherit the enclosing one), so you use rest parameters `(...args)` instead. **Constructability:** declarations/expressions are constructable with `new` (they have an internal `[[Construct]]` and a `.prototype`); arrows are **not constructable** — `new (()=>{})` throws.

```javascript
console.log(decl());           // "ok" — declaration hoisted
function decl() { return 'ok'; }

const obj = {
  val: 42,
  method() { return [1].map(function () { return this.val; })[0]; },  // undefined
  arrowMethod() { return [1].map(() => this.val)[0]; },               // 42
};
console.log(obj.method());      // undefined — inner fn has default `this`
console.log(obj.arrowMethod()); // 42        — arrow captures obj

const Arrow = () => {};
// new Arrow();                 // TypeError: Arrow is not a constructor
```

| Dimension | Declaration | Expression | Arrow |
|---|---|---|---|
| Hoisted (callable early) | yes | no | no |
| Own `this` | dynamic | dynamic | none (lexical) |
| `arguments` object | yes | yes | no |
| `new`-constructable | yes | yes | no |
| Named (for stack traces) | yes | optional | no (inferred) |

The decision rule: use **arrows for callbacks** where you want to preserve the surrounding `this` (event handlers in classes, array iteration, Promise chains); use **declarations/expressions** for methods that need a dynamic receiver, for constructors, and for generators (`function*` has no arrow form). The single most common real bug is reaching for an arrow as an *object method* and finding `this` points at the module/global scope rather than the object.

#### Q41. [Theory] How do `for...in`, `for...of`, `Object.keys`, and `forEach` differ in what they iterate and their handling of prototypes and order?

These four are easy to mix up but iterate fundamentally different things. **`for...in`** enumerates **enumerable string-keyed properties including inherited ones** up the prototype chain — which is why it's dangerous on arrays (it can pick up added prototype properties and yields *string* indices). **`for...of`** iterates **values of any iterable** (arrays, strings, `Map`, `Set`, generators) via the `Symbol.iterator` protocol — it does *not* work on plain objects (they aren't iterable) and never touches the prototype chain's property names. **`Object.keys(obj)`** returns an array of the object's **own enumerable string keys** (not inherited, not symbols). **`Array.prototype.forEach`** is a method that runs a callback per element of an array (own indexed elements), skipping holes in sparse arrays and offering no `break`.

```javascript
Array.prototype.customHelper = () => {};   // pollutes the chain (don't do this)
const arr = ['a', 'b'];

for (const k in arr) console.log(k);   // "0", "1", "customHelper"  ← inherited!
for (const v of arr) console.log(v);   // "a", "b"
Object.keys(arr).forEach(k => k);      // ["0","1"] — own enumerable only
arr.forEach((v, i) => console.log(i, v)); // 0 "a", 1 "b"
```

| Construct | Iterates | Inherited? | Symbols? | `break`? | Works on |
|---|---|---|---|---|---|
| `for...in` | keys (strings) | yes | no | yes | objects/arrays |
| `for...of` | values | n/a | n/a | yes | iterables only |
| `Object.keys` | own keys | no | no | n/a | objects |
| `forEach` | values | no | n/a | no | arrays/Map/Set |

**Order:** for objects, integer-like keys come first in **ascending numeric order**, then string keys in **insertion order**, then symbols (`Reflect.ownKeys` exposes all three groups) — this ordering is now standardized. Arrays via `for...of`/`forEach` go in index order. Practical guidance: never use `for...in` on arrays (use `for...of`, `forEach`, or a classic `for`); use `for...in` only on plain objects and guard with `Object.hasOwn(obj, key)` (ES2022) to skip inherited keys; reach for `for...of` when you need `break`/`await` inside the loop, since `forEach` supports neither.

#### Q42. [Theory] What is the difference between `slice`/`splice`/`concat`/`spread` and the ES2023 "change array by copy" methods (`toSorted`, `toReversed`, `with`)?

The historical array API is split between **mutating** methods that change the receiver in place (`splice`, `sort`, `reverse`, `push`, `pop`, `fill`, `copyWithin`) and **non-mutating** methods that return a new array (`slice`, `concat`, `map`, `filter`). The confusing pairs are `slice` (copy, non-mutating) vs. `splice` (insert/delete in place, mutating), and `sort`/`reverse` which **mutate** — a frequent bug in code that assumes `arr.sort()` leaves `arr` untouched, especially in React/immutable-state contexts where mutating shared state breaks change detection.

```javascript
const a = [3, 1, 2];
const b = a.slice(0, 2);    // [3, 1]  — a unchanged
const c = a.splice(1, 1);   // c = [1], a is now [3, 2]  ← mutated!
const d = [3, 1, 2].sort(); // sorts in place AND returns the same array
```

**ES2023** added immutable counterparts that *always return a new array and never touch the original*: `toSorted()`, `toReversed()`, `toSpliced(start, deleteCount, ...items)`, and `with(index, value)` (an immutable single-element replacement). They eliminate the defensive `[...arr].sort()` boilerplate.

```javascript
const orig = [3, 1, 2];
const sorted = orig.toSorted((x, y) => x - y); // [1, 2, 3]
console.log(orig);                              // [3, 1, 2] — untouched
const replaced = orig.with(0, 99);             // [99, 1, 2], orig untouched
```

| Mutating | Immutable equivalent |
|---|---|
| `arr.sort(cmp)` | `arr.toSorted(cmp)` |
| `arr.reverse()` | `arr.toReversed()` |
| `arr.splice(s, d, ...x)` | `arr.toSpliced(s, d, ...x)` |
| `arr[i] = v` | `arr.with(i, v)` |

`concat` and spread (`[...a, ...b]`) both produce shallow copies, but spread also works for inserting elements mid-array and merging arbitrary iterables, while `concat` flattens one level of array arguments. The senior takeaway: in **immutable/state-management code** (Redux, React state, signals), prefer the copy methods (`toSorted`, `with`, `map`, `filter`, spread) so you never mutate shared references; reserve in-place mutation for local, performance-sensitive code where the array is provably not shared, since copying is O(n) per operation.

#### Q43. [Practical] Explain `bind`, `call`, and `apply` — their differences, and implement a polyfill for `bind`.

All three set `this` explicitly, but they differ in *when* the function runs and *how* arguments are passed. **`call(thisArg, a, b, c)`** invokes immediately with arguments listed individually. **`apply(thisArg, [a, b, c])`** invokes immediately with arguments as an array (handy when args are already in an array, though spread has largely replaced this need). **`bind(thisArg, ...partials)`** does *not* invoke — it returns a **new function** permanently bound to `thisArg`, optionally pre-filling leading arguments (partial application). A bound function's `this` can never be re-bound, and calling it with `new` ignores the bound `this` but keeps the bound partials.

```javascript
function greet(greeting, punct) { return `${greeting}, ${this.name}${punct}`; }
const user = { name: 'Ada' };
greet.call(user, 'Hi', '!');           // "Hi, Ada!"
greet.apply(user, ['Hello', '.']);     // "Hello, Ada."
const boundHi = greet.bind(user, 'Hey');
boundHi('?');                          // "Hey, Ada?"  — punct supplied later
```

A correct `bind` polyfill must handle three subtleties: preserve `this` for normal calls, support partial application by concatenating bound and call-time args, and — critically — behave correctly when the bound function is used as a **constructor** (`new`), in which case the freshly-constructed object's `this` must win over the bound `thisArg`:

```javascript
Function.prototype.myBind = function (thisArg, ...boundArgs) {
  const targetFn = this;                       // the function being bound
  function bound(...callArgs) {
    // If called with `new`, `this instanceof bound` is true → use new object
    const ctx = this instanceof bound ? this : thisArg;
    return targetFn.apply(ctx, [...boundArgs, ...callArgs]);
  }
  // Preserve the prototype chain so `instanceof` works on `new bound(...)`
  if (targetFn.prototype) {
    bound.prototype = Object.create(targetFn.prototype);
  }
  return bound;
};
```

The `this instanceof bound` check is the heart of a spec-faithful implementation: it detects `new` invocation and lets the constructed instance override the bound receiver, matching native `bind`. In production you rarely write this, but the polyfill demonstrates deep understanding of how `new`, prototypes, and explicit binding interact — a favorite senior screening question.

#### Q44. [Theory] What is the difference between shallow and structural equality, and why does `NaN === NaN` return `false` while `Object.is(NaN, NaN)` returns `true`?

JavaScript exposes **four** distinct equality operations, each with subtly different rules. `==` (loose, with coercion), `===` (strict, no coercion), `Object.is` (SameValue), and the internal SameValueZero (used by `Array.prototype.includes`, `Map`/`Set` keys). The two edge cases where `===` and `Object.is` disagree are precisely `NaN` and signed zero.

`NaN === NaN` is `false` because IEEE 754 defines `NaN` ("not a number") as **unordered** — it is, by specification, not equal to anything, including itself. This is intentional: `NaN` represents the result of an undefined operation (`0/0`, `Math.sqrt(-1)`), and two such undefined results shouldn't be considered "the same." Strict equality faithfully implements IEEE 754 here. Conversely, `+0 === -0` is `true` even though they're distinct bit patterns, because IEEE comparison treats the two zeros as numerically equal.

`Object.is` implements the **SameValue** algorithm, which differs from `===` in exactly these two cases — it treats `NaN` as the same as `NaN` (useful for detecting `NaN` without `Number.isNaN`), and it distinguishes `+0` from `-0`:

```javascript
NaN === NaN;            // false
Object.is(NaN, NaN);    // true   ← SameValue treats NaN as equal to itself
+0 === -0;              // true
Object.is(+0, -0);      // false  ← SameValue distinguishes signed zeros
[NaN].includes(NaN);    // true   ← includes uses SameValueZero
[NaN].indexOf(NaN);     // -1     ← indexOf uses strict ===, so misses NaN
```

| Pair | `==` | `===` | `Object.is` (SameValue) | SameValueZero |
|---|---|---|---|---|
| `NaN`, `NaN` | false | false | **true** | **true** |
| `+0`, `-0` | true | true | **false** | true |
| `1`, `"1"` | true | false | false | false |

None of these operations do **structural (deep) equality** — `{a:1}` is never `===` or `Object.is` to a *different* `{a:1}` object, because all four compare object **references**, not contents. Deep equality must be implemented manually (recursively comparing keys/values, handling cycles) or via a library. The practical guidance: use `===` by default; use `Number.isNaN(x)` or `Object.is(x, NaN)` to detect `NaN`; reach for `Object.is` when signed-zero distinction matters (rare — physics/graphics); and never assume any built-in operator compares object contents.

### 🟠 Advanced — extended

#### Q45. [Theory] Explain `process.nextTick`, `queueMicrotask`, `setImmediate`, and `setTimeout(fn, 0)` ordering in Node.js, and how it differs from the browser.

Node's event loop is more granular than the browser's two-queue (macro/micro) model. Node runs in **phases** per loop iteration — `timers` (expired `setTimeout`/`setInterval`), `pending callbacks`, `poll` (I/O), `check` (`setImmediate`), `close callbacks` — and *between every phase and every callback* it drains two special queues: first the **`process.nextTick` queue**, then the **microtask queue** (Promises, `queueMicrotask`). So Node has effectively two microtask-like tiers, with `nextTick` having strictly higher priority than Promise jobs.

```javascript
setTimeout(() => console.log('timeout'), 0);
setImmediate(() => console.log('immediate'));
Promise.resolve().then(() => console.log('promise'));
process.nextTick(() => console.log('nextTick'));
queueMicrotask(() => console.log('queueMicrotask'));
console.log('sync');

// Node output:
// sync
// nextTick          ← nextTick queue drains first
// promise           ← then the Promise/queueMicrotask queue
// queueMicrotask
// timeout / immediate (order between these two is non-deterministic from main module)
```

The `setTimeout(0)` vs. `setImmediate` ordering is **non-deterministic when called from the main module** because it depends on how long process startup took relative to the 1 ms timer floor — but inside an **I/O callback**, `setImmediate` *always* fires before a `setTimeout(0)`, because the loop is already past the `poll` phase and hits the `check` phase next. This is a classic Node interview gotcha.

The browser has **no `process.nextTick` or `setImmediate`** (the `setImmediate` proposal was never standardized; only old IE/Edge shipped it). Browsers expose `queueMicrotask` (microtask), `setTimeout`/`setInterval` (macrotask, with a 4 ms minimum clamp after 5 nested timers), `requestAnimationFrame` (before paint), and `requestIdleCallback` (idle). The portable, spec-blessed way to schedule a microtask in both environments is **`queueMicrotask`**; `process.nextTick` is Node-only and, because it can starve I/O if used recursively, the Node docs themselves recommend `queueMicrotask` for most cases.

#### Q46. [Theory] What is the difference between lexical scope and dynamic scope, and is JavaScript's `this` an exception to lexical scoping?

**Lexical (static) scope** means a variable's scope is determined by **where it is written in the source code** — the nesting of functions and blocks at *authoring time* — and is fixed regardless of how or from where the function is later called. **Dynamic scope** (used by some Lisps, Bash, older Perl) resolves free variables by walking the **call stack at runtime**, so the same function can see different variables depending on who called it. JavaScript uses **lexical scope for variable resolution**: when a function references a free variable, the engine looks it up through the *scope chain* established by the function's textual position, which is precisely what makes closures predictable.

```javascript
const x = 'outer';
function printX() { console.log(x); }   // resolves `x` lexically → "outer"
function caller() {
  const x = 'inner';
  printX();                             // still prints "outer", NOT "inner"
}
caller();                               // lexical: definition site wins
```

The crucial nuance: **`this` is the one place JavaScript behaves dynamically**. Unlike variable lookup, a regular function's `this` is *not* lexical — it is bound at the **call site** based on how the function is invoked (`new`, `call`/`apply`/`bind`, method receiver, or default). This split is the source of endless confusion: people expect `this` to follow the same "where it's written" rule as variables, but it follows the call stack instead, which is dynamic-scope-like behavior.

```javascript
const obj = { id: 1, get() { return this.id; } };
const fn = obj.get;
fn();          // undefined (or throws in strict) — `this` from call-site, not definition
obj.get();     // 1 — `this` is obj because obj is the receiver
```

**Arrow functions reconcile the two**: they deliberately make `this` *lexical*, capturing it from the surrounding scope like any other variable, which is why arrows are the fix for "I lost `this` in a callback." So the complete mental model is: variables and arrow-`this` are lexical (definition-site); regular-function `this` and `arguments` are dynamic (call-site). Understanding this asymmetry is what separates engineers who *memorize* the four `this` rules from those who understand *why* the rules exist.

#### Q47. [Theory] How does V8 represent arrays internally (packed vs. holey, SMI vs. double vs. object elements), and how do these "element kinds" affect performance?

V8 does not store every array as a generic object map; it tracks an **elements kind** — a hidden tag describing the array's contents — and transitions it as you mutate the array. The kinds form a one-way lattice from most-specific/fastest to most-general/slowest: `PACKED_SMI_ELEMENTS` (dense array of small integers) → `PACKED_DOUBLE_ELEMENTS` (dense doubles) → `PACKED_ELEMENTS` (dense, any values) → and the **holey** variants `HOLEY_SMI` → `HOLEY_DOUBLE` → `HOLEY_ELEMENTS`. "Packed" means contiguous with no gaps; "holey" means the array has holes (missing indices), forcing every read to walk the prototype chain to check whether the slot is a genuine `undefined` or an inherited property.

```javascript
const a = [1, 2, 3];        // PACKED_SMI_ELEMENTS — fastest
a.push(4.5);                // → PACKED_DOUBLE_ELEMENTS (widened)
a.push('x');                // → PACKED_ELEMENTS (boxed, general)

const b = [1, 2, 3];
b[100] = 4;                 // → HOLEY_ELEMENTS — creates holes, slower reads
const c = new Array(3);     // → HOLEY (pre-allocated but empty) — avoid
```

Transitions are **irreversible within the array's lifetime** — once an array becomes `HOLEY` or `PACKED_ELEMENTS`, it never transitions *back* to the faster SMI/double representation, even if you remove the offending element. Holey arrays are slower because element access can't be a simple bounds-checked memory load; the engine must also consult the prototype chain for absent indices. Double arrays box less than general arrays but more than SMI arrays.

```
PACKED_SMI ─→ PACKED_DOUBLE ─→ PACKED_ELEMENTS
    │              │                  │
    ▼              ▼                  ▼
 HOLEY_SMI ──→ HOLEY_DOUBLE ──→ HOLEY_ELEMENTS   (one-way down/right only)
```

Practical guidance for hot paths: build arrays by `push`ing onto an empty literal `[]` rather than `new Array(n)` (which starts holey); keep arrays **monomorphic** (don't mix integers, floats, and objects if you can avoid it); never create sparse arrays by assigning to a far-out index; and avoid `delete arr[i]` (it punches a hole — use `splice` or set to a sentinel). These matter only on genuinely hot, large-array code; for ordinary application logic, clarity wins and the engine's defaults are fine.

#### Q48. [Coding] Implement `Promise.allSettled` from scratch, and explain how it differs structurally from `Promise.all`.

`Promise.allSettled` (ES2020) waits for **every** input promise to settle — fulfilled *or* rejected — and **never short-circuits**. It resolves (it essentially never rejects from the inputs) with an array of result descriptor objects: `{ status: 'fulfilled', value }` for successes and `{ status: 'rejected', reason }` for failures, preserving input order. This is the right tool when partial results are useful: a dashboard where one failing widget shouldn't blank the others, or a batch job where you want a full success/failure report rather than aborting on the first error.

```javascript
function promiseAllSettled(promises) {
  const items = [...promises];
  return new Promise((resolve) => {
    const results = new Array(items.length);
    let remaining = items.length;
    if (remaining === 0) return resolve([]);   // empty input → resolves to []

    items.forEach((p, i) => {
      Promise.resolve(p).then(
        (value)  => { results[i] = { status: 'fulfilled', value }; },
        (reason) => { results[i] = { status: 'rejected', reason }; }
      ).finally(() => {
        if (--remaining === 0) resolve(results); // resolve only when ALL settle
      });
    });
  });
}
```

The structural difference from `Promise.all` is in the **rejection handler**. In `Promise.all`, a rejection calls the outer `reject` immediately (fail-fast), so the aggregate rejects as soon as any input does, and later results are discarded. In `allSettled`, the rejection path **records** the reason into the results array instead of rejecting, and the counter only triggers `resolve` once *every* promise has settled. There is no path that rejects the aggregate based on input failures.

```
Promise.all:        any reject  ──▶ reject(reason)         [stops early]
Promise.allSettled: each settle ──▶ record {status,...}    [waits for all]
                    counter hits 0 ──▶ resolve(results[])
```

Edge cases that distinguish a strong answer: the **empty iterable** resolves to `[]`; **non-promise values** are normalized via `Promise.resolve` (so a raw `5` becomes `{status:'fulfilled', value:5}`); results are stored **by index** so order matches input regardless of settle timing; and using `.finally` for the counter ensures the decrement happens exactly once per settled promise whether it fulfilled or rejected. The companion methods round out the family: `Promise.all` (all-or-nothing), `Promise.race` (first to settle, either way), and `Promise.any` (first fulfillment, rejects only if all reject).

#### Q49. [Theory] Explain how `class` private fields (`#x`) work under the hood and why they differ from `Symbol`-based or closure-based privacy.

The `#`-prefixed **private fields** (ES2022) provide *hard* privacy enforced by the language itself, not by convention or obscurity. Unlike a `_name` underscore convention (purely social) or a `Symbol`-keyed property (discoverable via `Object.getOwnPropertySymbols`), a `#field` is **completely inaccessible** outside the class body — there is no reflection API, no proxy trap, and no string/symbol that can reach it. Internally the spec models them not as ordinary properties but as entries in a per-instance **PrivateName** slot map; access compiles to a brand check against the lexically-scoped private name, which is why `obj.#x` from outside the class is a **syntax error at parse time**, not a runtime `undefined`.

```javascript
class Account {
  #balance = 0;                          // truly private, not a property
  deposit(n) { this.#balance += n; return this; }
  get balance() { return this.#balance; }
  static isAccount(obj) {
    return #balance in obj;              // brand check — no throw, returns boolean
  }
}
const a = new Account();
// a.#balance;                           // SyntaxError — can't even reference it
console.log(Object.keys(a));            // [] — invisible to reflection
console.log(Account.isAccount({}));     // false — lacks the #balance brand
```

A key semantic is the **brand check**: accessing `this.#x` on an object that wasn't constructed by this class throws a `TypeError` (the object lacks the "brand"). This is genuinely useful — `#field in obj` (the `in` operator with a private name) safely tests membership without throwing, enabling robust type guards. By contrast, closure-based privacy (returning methods that close over a `let balance`) also achieves true privacy but creates a **new copy of every method per instance** (methods live in the closure, not on the prototype), costing memory; private fields keep methods on the shared prototype while still hiding state.

| Approach | True privacy | Methods on prototype | Reflection-discoverable | Per-instance cost |
|---|---|---|---|---|
| `_name` convention | no | yes | yes | low |
| `Symbol` key | weak (discoverable) | yes | yes (`getOwnPropertySymbols`) | low |
| Closure | yes | **no** (per-instance) | no | higher (method copies) |
| `#field` | **yes** | yes | **no** | low |

The trade-offs and gotchas: private fields are **not inherited-accessible** across unrelated classes (each class's `#x` is distinct even with the same name), they don't work with `Proxy` transparently (a proxy can't forward private-field access to its target — a known limitation that requires returning the real instance), and they're a relatively recent feature so very old runtimes need transpilation (Babel emulates them with `WeakMap`). For new code targeting modern runtimes, `#fields` are the correct default for encapsulation: hard privacy, prototype-shared methods, and a built-in brand check — superior to both the underscore convention and the historical `WeakMap`/closure patterns.

#### Q50. [Practical] What are tagged template literals, and how would you use them to build a safe SQL or HTML interpolation helper?

A **tagged template literal** is a function call where the function (the "tag") receives the template's static string parts and its interpolated values **separately**, rather than a pre-joined string. The tag is invoked as `tag(strings, ...values)` where `strings` is an array of the literal segments (with a `.raw` property for un-escaped text) and `...values` are the evaluated `${}` expressions. This separation is the entire point: because the tag sees *which* parts are trusted static template and *which* are dynamic interpolations, it can **sanitize or escape only the dynamic values** — the foundation of injection-safe helpers.

```javascript
function html(strings, ...values) {
  const escape = (s) => String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  // strings is static (trusted); only values (dynamic) get escaped
  return strings.reduce((out, str, i) =>
    out + str + (i < values.length ? escape(values[i]) : ''), '');
}
const userInput = '<img src=x onerror=alert(1)>';
console.log(html`<div>${userInput}</div>`);
// <div>&lt;img src=x onerror=alert(1)&gt;</div>  — neutralized
```

For SQL, the same structure powers **parameterized queries**: instead of string-concatenating user input into SQL (the cause of SQL injection), the tag collects values into a bound-parameter array and emits placeholders, so the database driver — not string manipulation — separates code from data:

```javascript
function sql(strings, ...values) {
  // Build "SELECT ... WHERE id = $1 AND name = $2" with a separate params array
  const text = strings.reduce((q, s, i) =>
    q + s + (i < values.length ? `$${i + 1}` : ''), '');
  return { text, params: values };       // pass to driver.query(text, params)
}
const id = 42, name = "O'Brien";
sql`SELECT * FROM users WHERE id = ${id} AND name = ${name}`;
// { text: "SELECT * FROM users WHERE id = $1 AND name = $2", params: [42, "O'Brien"] }
```

This is exactly how libraries like `styled-components` (scoping CSS), `graphql-tag` (parsing queries), and `@vercel/postgres` / `sql-template-tag` (safe SQL) work. The deeper insight for interviews: tagged templates give you a **compile-time-distinguished trust boundary** for free — the static `strings` array is author-controlled and the `values` are runtime data, and a well-designed tag never lets runtime data influence the *structure* of the output, only its escaped leaf values. The `strings` array is also **cached and frozen** per call-site, enabling tags to memoize parsing work (graphql-tag relies on this identity-stability for caching parsed ASTs).

### 🔴 Expert — extended

#### Q51. [Theory] Explain the complete module resolution and evaluation lifecycle of an ES module: construction, instantiation/linking, and evaluation. How do live bindings and circular imports actually work?

An ES module is processed in **three distinct phases**, which is what distinguishes it from CommonJS's single eval-on-require model. **(1) Construction (parse/fetch):** the loader fetches each module file, parses it to find its `import`/`export` statements (without executing any code), and recursively does the same for dependencies, building the full **module graph** depth-first. **(2) Instantiation/linking:** the engine allocates a **Module Environment Record** for each module and wires up the bindings — every `import` is connected to the corresponding `export` *slot* in the source module. Crucially, this creates a **live binding**: the importer doesn't get a copied value, it gets a read-only reference to the *same variable cell* in the exporter. **(3) Evaluation:** the engine runs each module's top-level code exactly once, depth-first (dependencies before dependents), populating those binding slots with actual values.

```javascript
// counter.js
export let count = 0;
export function inc() { count++; }      // mutates the exporter's variable

// main.js
import { count, inc } from './counter.js';
console.log(count);  // 0
inc();
console.log(count);  // 1  ← live binding reflects the change; NOT a snapshot
// count = 5;        // TypeError: Assignment to constant variable (imports are read-only)
```

This is the opposite of CommonJS, where `const { count } = require('./counter')` copies the *value at require time*, so a later `inc()` wouldn't change the importer's `count`. Live bindings are why ESM can support some circular dependencies that CJS handles awkwardly: because linking happens *before* evaluation, both modules already have their binding slots wired up, so a circular reference resolves to a real (possibly not-yet-initialized) binding rather than a partial `module.exports` object.

```
Phase 1 Construction:   parse graph, find imports/exports  (no code runs)
Phase 2 Linking:        create binding slots, connect import→export cells
Phase 3 Evaluation:     run top-level code depth-first, fill the slots
                        ── live bindings observe later writes ──
```

The circular-import gotcha that separates experts: if module A's **top-level code** *uses* a value imported from B before B has finished evaluating, the binding exists but its slot is still in the **TDZ** (for `let`/`const` exports) or `undefined`, so you get a `ReferenceError` or a stale value. The fix is to defer cross-module access into a *function* that runs after all modules have evaluated, rather than reading the import at module top-level. With `function` *declarations* (which are hoisted and initialized during instantiation), circular imports work cleanly because the binding is already populated before evaluation begins. Designing acyclic graphs — or limiting cycles to hoisted function declarations — is the senior-level discipline here.

#### Q52. [Theory] What problems do labels and the comma operator, `void`, and `with` solve, and why are some of them considered anti-patterns today?

These are four lesser-known operators/statements that appear in legacy code, minified output, and tricky interview questions. **Labeled statements** (`outer: for (...)`) let `break outer;` / `continue outer;` jump out of *nested* loops in one statement — the only structured way to break a specific outer loop without a flag variable or extracting a function. They're legitimate but rare; clearer alternatives (early `return` from an extracted function) usually win for readability.

The **comma operator** evaluates its left operand, discards the result, evaluates the right, and returns the right value. It's mostly seen in minified code and `for`-loop headers (`for (let i = 0, j = n; i < j; i++, j--)`), and occasionally in concise arrow bodies for side effects. It's an anti-pattern in normal code because it hides multiple evaluations behind one expression, hurting readability.

```javascript
const x = (console.log('side effect'), 42);  // logs, then x = 42
const f = () => (count++, count);             // increment then return — terse but obscure

outer:
for (let i = 0; i < 3; i++)
  for (let j = 0; j < 3; j++)
    if (grid[i][j] === target) break outer;   // exits BOTH loops
```

**`void`** evaluates its operand and always returns `undefined`. Historically used for `javascript:void(0)` links and to guarantee a genuine `undefined` (back when `undefined` was reassignable in older engines); in modern code its main legitimate use is **explicitly marking a fire-and-forget promise** (`void doAsync();`) so linters know you're intentionally not awaiting, and in arrow functions to discard a return value (`onClick={() => void mutate()}`).

**`with(obj) { ... }`** adds an object to the scope chain so `prop` resolves to `obj.prop`. It is **forbidden in strict mode** (and thus in ESM/classes) because it makes scope **non-statically-analyzable** — the engine can't know at compile time whether `x` refers to a local variable or `obj.x`, which defeats optimization and creates security/correctness hazards. It's the clearest example of a feature removed for breaking lexical guarantees. The unifying theme: each trades clarity or static analyzability for terseness or dynamism, and modern best practice favors **statically analyzable, explicit code** — keep labels for genuine nested-loop breaks, use `void` only to signal intentional promise-ignoring, and never use `with`.

#### Q53. [Theory] How does the optimizing JIT's speculative optimization and deoptimization work, and what triggers a "deopt"? Give concrete patterns that cause megamorphic call sites.

V8's optimizing compilers (Maglev, TurboFan) perform **speculative optimization**: they observe a hot function's runtime type feedback (gathered by the Ignition interpreter via inline caches) and compile machine code that *assumes* the observed types continue to hold — for example, that a property access always sees objects of one hidden class, or that an arithmetic operand is always an integer. These assumptions are guarded by cheap runtime **type checks**. If a check fails (an unexpected shape or type appears), the engine **deoptimizes ("bails out"/"deopt")**: it discards the optimized code, reconstructs the interpreter's stack frame, and resumes in slower bytecode — and if it deopts repeatedly, it may give up optimizing that function entirely.

The fastest case is a **monomorphic** call/access site — one that has only ever seen a single hidden class. As it sees more shapes it degrades to **polymorphic** (2–4 shapes, a small inline-cache check) and then **megamorphic** (5+ shapes), at which point the inline cache is abandoned for a slow generic dictionary lookup. Concrete patterns that cause this:

```javascript
// 1. Inconsistent object shapes → polymorphic/megamorphic property access
function getX(p) { return p.x; }
getX({ x: 1, y: 2 });           // shape A
getX({ x: 1, z: 3 });           // shape B
getX({ a: 0, x: 1 });           // shape C (x at different offset) — degrading

// 2. Adding properties after construction / different order → shape transitions
function Point(x, y) { this.x = x; this.y = y; }  // good: consistent shape
const p = {}; p.x = 1; p.y = 2;                   // ok, but order matters
const q = {}; q.y = 2; q.x = 1;                   // DIFFERENT hidden class than p

// 3. Type-unstable arithmetic / arguments
function add(a, b) { return a + b; }
add(1, 2);          // integer feedback
add('a', 'b');      // now strings too → deopt of the integer-optimized version

// 4. Mutating arrays into holey/general element kinds (see elements-kind question)
```

| Call-site state | Shapes seen | Mechanism | Relative speed |
|---|---|---|---|
| Monomorphic | 1 | direct inline cache | fastest |
| Polymorphic | 2–4 | small IC dispatch | fast |
| Megamorphic | 5+ | generic hash lookup | slow |

Other deopt triggers worth naming: reading an `arguments` object in ways that prevent its optimization, `try/catch` historically blocking optimization (largely fixed in modern V8), `eval`/`with` poisoning scope analysis, and changing a function's argument *count* or types across calls. The senior takeaway is calibrated: **these effects are real but only matter on genuinely hot code** (tight loops over large data, framework cores, hot render paths). The actionable discipline is to keep objects to a **stable shape** (initialize all fields in the constructor in a fixed order, avoid adding/deleting properties later) and keep functions **type-stable** (don't call the same function with integers one moment and strings the next). For ordinary application code, readability dominates and the engine's adaptive optimization handles the rest; premature micro-optimization here is itself an anti-pattern. You can inspect this in practice by running Node with `--trace-deopt` and `--trace-ic`.

#### Q54. [Theory] Explain how `async`/`await` is desugared into a state machine over Promises, and what the exact microtask scheduling is for `await` of a non-promise vs. a thenable.

`async`/`await` is not magic — the engine transforms an `async` function into a **resumable state machine** driven by the Promise microtask queue. Conceptually (this mirrors the historical generator-based polyfills), each `await` is a suspension point: the function's execution is split into segments, and reaching an `await` (1) wraps the awaited operand via the equivalent of `Promise.resolve`, (2) attaches a continuation (`.then`) that will resume the next segment, and (3) **returns control to the caller immediately**, with the `async` function's own returned Promise still pending. When the awaited Promise settles, the continuation is scheduled as a **microtask**, restoring local state and resuming after the `await`.

The subtle, frequently-tested part is the **exact number of microtask ticks** `await` introduces. For an `await` of an **already-resolved promise or a non-promise value**, modern engines (post the ES2020/V8 optimization that removed the extra wrapper ticks) schedule the resumption in **one microtask tick**. But for a **thenable** (a non-native object with a `.then` method), the spec requires extra ticks because the engine must call the user-defined `.then`, which itself enqueues a job to invoke the resolve callback — so a thenable can add **additional ticks** compared to a native promise.

```javascript
async function f() {
  console.log('A');
  await null;             // await non-promise: resumes in 1 microtask tick
  console.log('C');
}
console.log('1');
f();                      // logs A synchronously, then suspends at await
console.log('2');
Promise.resolve().then(() => console.log('B'));
// Output: 1, A, 2, C, B   (C resumes before B due to single-tick await of non-promise)
```

Before the V8 "faster async functions" change (2018) and the corresponding spec fix, `await x` where `x` was a value involved wrapping it in a promise and then awaiting *that*, costing **three** microtask ticks; the optimization collapsed `await` of a native promise/value to a single tick. This is why the ordering of mixed `await`/`then` chains differs between very old and modern runtimes — a genuine version-difference gotcha. The practical implications: (1) interleaving order between `await` continuations and bare `.then` callbacks is observable and depends on tick counts, so don't rely on fragile ordering; (2) a thenable from a non-native promise library can resume *later* than a native promise even when "already resolved," which can surprise tests; (3) the state-machine model explains why `try/catch` around `await` works (the rejection is rethrown at the resume point) and why a `for await...of` loop suspends once per iteration.

#### Q55. [Theory] What is the difference between structural typing, nominal typing, and JavaScript's runtime "duck typing," and how do `instanceof`, `Symbol.hasInstance`, and brand checks fit in?

JavaScript at runtime has **no static type system at all** — it does **duck typing**: an operation succeeds if the value *has the needed members at the moment of use* ("if it walks like a duck and quacks like a duck"), regardless of its constructor or declared type. Code like `if (typeof obj.then === 'function')` (the actual thenable check used by the Promise machinery) is duck typing: it cares about *behavior present now*, not lineage. This is maximally flexible but offers no compile-time guarantees, which is what TypeScript's **structural typing** layers on top — TS considers two types compatible if their *shapes* match, irrespective of names. **Nominal typing** (Java, C#, partly via TS branding tricks) instead requires an explicit declared relationship (same class/interface name), so two structurally identical types are *not* interchangeable unless one declares it extends the other.

At runtime, JavaScript offers a few *lineage* checks that approximate nominal typing. **`instanceof`** walks the prototype chain asking "is `Constructor.prototype` anywhere in this object's `[[Prototype]]` chain?" — so it tests *prototype lineage*, not shape. This is why `instanceof` **fails across realms** (an array from an `<iframe>` or a Node `vm` context isn't `instanceof` the local `Array`, since each realm has its own `Array.prototype`), which is precisely why `Array.isArray` exists as a realm-safe alternative.

```javascript
function thenable(x) { return x && typeof x.then === 'function'; } // duck typing

class Animal {}
class Dog extends Animal {}
new Dog() instanceof Animal;   // true — prototype-chain lineage check

// Customizing instanceof with the well-known symbol:
class Even {
  static [Symbol.hasInstance](n) { return Number.isInteger(n) && n % 2 === 0; }
}
console.log(4 instanceof Even);  // true  — no prototype involved at all!
console.log(3 instanceof Even);  // false
```

**`Symbol.hasInstance`** lets a class override what `instanceof` means entirely, decoupling it from the prototype chain — useful for "abstract" or structural checks. The most robust runtime "nominal" check is a **brand check**: testing for a private field (`#brand in obj`, ES2022) or a unique symbol property that only your constructor sets, which can't be faked by an arbitrary same-shaped object and works regardless of prototype tampering. The senior synthesis: use **duck typing** (`typeof obj.method === 'function'`) for maximum interoperability with foreign objects; use **`Array.isArray` / `Number.isNaN`-style** realm-safe predicates instead of `instanceof` for built-ins; use **brand checks** (`#field in obj`) when you need to *prove* an object came from your code; and remember TypeScript's structural compatibility is erased at runtime, so security- or correctness-critical type decisions must be re-validated with a runtime check, never assumed from the static type.

#### Q56. [Practical] How would you architect cancellation and timeouts for `fetch`-based code, given Promises have no native cancel? Compare `AbortController`, `Promise.race`, and `AbortSignal.timeout`/`any`.

Promises are **not cancelable** by design — once a Promise is created its executor runs to completion, and "canceling" a Promise only means *ignoring* its eventual result, not stopping the underlying work. This is a deliberate design decision (a Promise represents a value that *will* exist, not a controllable operation), and it's why naive `Promise.race([fetchPromise, timeoutPromise])` is a **leak**: the losing `fetch` keeps running in the background, holding the connection and eventually firing its `.then`. The correct primitive is the **`AbortController`/`AbortSignal`** pair, which propagates an *abort intent* into operations that opt in (notably `fetch`, and increasingly Node streams, timers, and event listeners).

```javascript
async function fetchWithTimeout(url, ms) {
  // AbortSignal.timeout(ms) (2022+) auto-aborts after ms — no manual timer/cleanup
  const res = await fetch(url, { signal: AbortSignal.timeout(ms) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

// Combining a user-cancel button AND a timeout with AbortSignal.any (2024):
function makeRequest(url, userController, timeoutMs) {
  const signal = AbortSignal.any([
    userController.signal,                 // fires if user clicks "cancel"
    AbortSignal.timeout(timeoutMs),        // fires on timeout
  ]);
  return fetch(url, { signal });           // aborts on WHICHEVER fires first
}
```

The architectural advantages of `AbortController` over `Promise.race`: (1) it **actually stops the work** — the browser/Node tears down the TCP connection and the `fetch` rejects with an `AbortError` — rather than leaking a still-running request; (2) one signal can cancel **multiple** linked operations (several `fetch`es, a stream read, event listeners registered with `{ signal }`); (3) it composes — `AbortSignal.any([...])` (2024) settles on the first of several signals, and `AbortSignal.timeout(ms)` (2022) gives a self-cleaning timeout without a dangling `setTimeout` to clear.

```
Promise.race([fetch, timeout]):  timeout wins → fetch STILL RUNNING (leak)
AbortController + signal:         abort() → fetch torn down → AbortError thrown
AbortSignal.any([user, timeout]): first signal to fire aborts the linked fetch
```

The production pattern: thread a `signal` through every async layer (data-fetching hook → API client → `fetch`), tie it to component lifecycle (abort in React's `useEffect` cleanup, or on route change), distinguish `AbortError` from genuine failures in `catch` (an abort is usually not an error to report to the user), and prefer `AbortSignal.timeout`/`any` over hand-rolled timer-and-race code. The meta-lesson: cancellation is a **cooperative protocol** — it only works if every layer forwards the signal and the leaf operation honors it; a `Promise.race` "timeout" that doesn't abort is a common, subtle resource leak in real codebases.

#### Q57. [Theory] Explain the semantics of `Symbol.iterator` vs `Symbol.asyncIterator`, the full iteration protocol (including `return`/`throw`), and how `for...of` / `for await...of` use them.

The **iterable protocol** is the contract that makes `for...of`, spread, destructuring, `Array.from`, and `yield*` work on arbitrary objects. An object is **iterable** if it has a `[Symbol.iterator]()` method returning an **iterator** — an object with a `next()` method that returns `{ value, done }`. `for...of` calls `[Symbol.iterator]()` once to get the iterator, then repeatedly calls `next()` until `done` is `true`. Two often-forgotten parts of the protocol are the optional **`return(value)`** method (called for *early termination* — `break`, `throw`, or an exception inside the loop body — so the iterator can release resources) and **`throw(err)`** (used mainly by generators for injecting exceptions).

```javascript
const range = {
  from: 1, to: 3,
  [Symbol.iterator]() {
    let current = this.from;
    const last = this.to;
    return {
      next: () => current <= last
        ? { value: current++, done: false }
        : { value: undefined, done: true },
      return() {                       // called on `break` — cleanup hook
        console.log('iterator closed early');
        return { done: true };
      },
    };
  },
};
for (const n of range) { if (n === 2) break; }  // logs "iterator closed early"
```

The **async iteration protocol** mirrors this but for values that arrive *over time*: an object is **async-iterable** if it has `[Symbol.asyncIterator]()` returning an iterator whose `next()` returns a **Promise** of `{ value, done }`. `for await...of` awaits each `next()` result, suspending the loop until the next chunk is ready — this is the idiomatic way to consume paginated APIs, Node streams (which implement `Symbol.asyncIterator`), `ReadableStream`, and `async function*` generators. It also `await`s the `return()` cleanup on early exit, which matters for releasing stream/file handles.

```javascript
async function* pages(url) {
  let next = url;
  while (next) {
    const res = await fetch(next);
    const data = await res.json();
    yield* data.items;                 // yield each item lazily
    next = data.nextPageUrl;           // fetch next page only when consumer asks
  }
}
for await (const item of pages('/api/items')) {
  if (shouldStop(item)) break;         // stops fetching further pages — backpressure
}
```

| Aspect | Sync (`Symbol.iterator`) | Async (`Symbol.asyncIterator`) |
|---|---|---|
| `next()` returns | `{value, done}` | `Promise<{value, done}>` |
| Consumed by | `for...of`, spread, destructure | `for await...of` |
| Cleanup hook | `return()` | `return()` (awaited) |
| Use case | in-memory collections | streams, paginated/network data |

The deep points an expert raises: (1) the protocol's `return()` hook gives **lazy iterators backpressure and resource safety** — breaking out of `for await...of` stops fetching further pages, which a Promise-array approach can't do; (2) sync iterables are *not* automatically async-iterable, but `for await...of` will fall back to a sync iterator and await each value (so you can `for await` over an array of promises); (3) generators implement *both* `next` and `return`/`throw`, which is how `try/finally` inside a generator runs its `finally` block when the consumer breaks early. Understanding the full protocol — not just `next()` — is what enables building cancelable, resource-safe streaming abstractions in production.

#### Q58. [Theory] Compare error-handling models in JavaScript: synchronous `throw`/`try-catch`, Promise rejection, `unhandledrejection`/`uncaughtException`, and `Error.cause`/`AggregateError`. What are the propagation and observability differences?

JavaScript has **two parallel error channels** that don't automatically bridge, and conflating them causes silent failures. **Synchronous errors** propagate by unwinding the call stack until a `try/catch` catches them or they reach the top (terminating the script / triggering `window.onerror` / Node's `uncaughtException`). **Asynchronous errors** in Promise-based code don't throw on the stack — they become **rejected Promises** that propagate down the `.then`/`.catch` *chain*, not up the call stack. A `try/catch` only catches a Promise rejection if you `await` inside the `try`; a bare `somePromise()` that rejects without a `.catch` or `await` escapes synchronous `try/catch` entirely.

```javascript
try {
  Promise.reject(new Error('async'));    // NOT caught — rejection isn't a sync throw
} catch (e) { /* never runs */ }

try {
  await Promise.reject(new Error('async')); // caught — await bridges the channels
} catch (e) { console.log('caught:', e.message); }
```

Unhandled errors in each channel surface through different **global hooks**, which is critical for observability/monitoring. A rejected Promise with no handler fires **`unhandledrejection`** (browser `window`/`self`, Node `process`); a synchronous error escaping all frames fires **`error`/`window.onerror`** (browser) or **`uncaughtException`** (Node). A robust app wires *both* into its error reporter, because missing the `unhandledrejection` hook means async failures vanish silently. Note that whether a rejection is "unhandled" is determined *after a microtask turn* — attaching a `.catch` later in the same tick can prevent the event (and `rejectionhandled` fires if you handle it belatedly).

```
SYNC:  throw ──▶ unwind stack ──▶ try/catch | window.onerror / uncaughtException
ASYNC: reject ─▶ down .then/.catch chain ─▶ unhandledrejection (if no handler)
       await bridges ASYNC → SYNC try/catch
```

Modern error *composition* features improve diagnosability. **`Error.cause`** (ES2022) lets you wrap a low-level error while preserving the original (`new Error('failed to load user', { cause: dbError })`), so you keep a causal chain instead of swallowing the root cause — far better than re-throwing a string. **`AggregateError`** (ES2021) bundles *multiple* errors into one, which is exactly what `Promise.any` throws when all inputs reject (`err.errors` holds each reason). The expert-level guidance: always `await` or `.catch` every Promise (lint with `no-floating-promises`); wrap-and-rethrow with `{ cause }` to preserve stack/context across layers; register both global handlers for telemetry; treat an unhandled rejection in Node as fatal (the default since Node 15 is to crash, which is correct — a process in an unknown state should restart) rather than swallowing it; and use `AggregateError` when you genuinely have a *set* of failures to report rather than collapsing them to the first one.

#### Q59. [Theory] What is the difference between `Number`, `BigInt`, typed arrays, and `ArrayBuffer`, and when do you need each? Explain the precision and interop boundaries.

JavaScript's numeric story has several distinct representations for different needs. The default `Number` is an **IEEE 754 double** — it can represent integers *exactly* only up to `Number.MAX_SAFE_INTEGER` (2^53 − 1); beyond that, consecutive integers collide (`2**53 === 2**53 + 1` is `true`). **`BigInt`** (ES2020), written with an `n` suffix or `BigInt(x)`, is an **arbitrary-precision integer** for values exceeding the safe range — database IDs, financial calculations needing exact large integers, cryptography, high-resolution timestamps. The hard boundary is that **`BigInt` and `Number` cannot be mixed in arithmetic** without explicit conversion (`1n + 1` throws a `TypeError`), and `BigInt` has no fractional part, so it isn't a general decimal-math solution.

```javascript
console.log(Number.MAX_SAFE_INTEGER);     // 9007199254740991
console.log(9007199254740991 + 1 === 9007199254740991 + 2); // true — precision lost!
const big = 9007199254740993n;            // exact via BigInt
// big + 1;        // TypeError: Cannot mix BigInt and other types
big + 1n;          // 9007199254740994n — fine
console.log(typeof 10n);                  // "bigint"
```

For **binary data**, `ArrayBuffer` is a fixed-length block of **raw bytes** with no inherent interpretation; you read/write it through a **view**: a **TypedArray** (`Uint8Array`, `Int32Array`, `Float64Array`, `BigInt64Array`, etc.) interprets the buffer as a homogeneous array of one numeric type, while a **`DataView`** allows mixed types and explicit **endianness** control. TypedArrays back `fetch`/`Response` bodies, `FileReader`, WebGL, WebAudio, WebSockets (binary frames), crypto APIs, and `SharedArrayBuffer` for cross-thread memory. They store numbers in their native machine representation (not boxed `Number` objects), which is both compact and fast.

| Type | Represents | Precision/Range | Primary use |
|---|---|---|---|
| `Number` | double float | exact ints ≤ 2^53−1; fractions inexact | general math |
| `BigInt` | arbitrary-precision int | unlimited integer, no fractions | large IDs, exact big ints |
| `ArrayBuffer` | raw bytes | n/a (byte container) | binary backing store |
| `TypedArray` | typed view of buffer | per element type (8–64 bit) | binary I/O, WebGL, perf |
| `DataView` | mixed-type view | per-read type + endianness | parsing binary formats |

The interop boundaries that trip people up: **`JSON` has no `BigInt`** — `JSON.stringify(1n)` throws, so you must serialize BigInts as strings and revive them; reading a 64-bit integer from a binary protocol requires `BigInt64Array`/`DataView.getBigInt64` because a plain `Number` would lose precision above 2^53; and mixing TypedArray element types over the same buffer means a write through one view is visible (reinterpreted) through another, which is powerful for parsing but a footgun for endianness. The decision rule: use plain `Number` for everyday arithmetic and small integers; reach for `BigInt` the moment integer values can exceed 2^53 *and* must stay exact; use `ArrayBuffer`/TypedArrays whenever you handle raw binary (network frames, files, media, WASM memory, GPU buffers); and use `DataView` specifically when parsing heterogeneous binary formats where endianness matters.

#### Q60. [Theory] Explain "run-to-completion" semantics and how they guarantee atomicity, plus the failure modes (starvation, blocking, long tasks) and the modern scheduling APIs that mitigate them.

**Run-to-completion** is the foundational concurrency guarantee of JavaScript's single-threaded model: once a task (a synchronous block of code — an event handler, a timer callback, the initial script) begins executing, it **runs to its end without being preempted**. No other task, timer, or event handler can interrupt it midway; they wait their turn in the queue. This is hugely simplifying — it means **shared state is never mutated concurrently**, so you never need locks/mutexes for ordinary JS data, and any single function executes atomically with respect to other tasks. A variable you read at the top of a function can't be changed by another "thread" before you write it back (the canonical lost-update race is impossible within one task).

```javascript
let counter = 0;
function handler() {
  const local = counter;     // read
  // ... no other JS can run here and change `counter` ...
  counter = local + 1;       // write — atomic relative to other tasks; no race
}
```

The cost of this guarantee is the **flip side: a single long task blocks everything** — rendering, input handling, other callbacks, the entire UI freezes until it finishes, because nothing can preempt it. This produces the classic failure modes: **long tasks** (>50 ms blocks, hurting INP/responsiveness), **blocking** (a synchronous heavy computation or — worse — synchronous I/O like `alert`, a sync XHR, or a tight CPU loop), and **microtask starvation** (an unbounded chain of microtasks that keeps re-enqueueing prevents the event loop from ever reaching the next macrotask or a render, since the loop fully drains microtasks before continuing).

```
GOOD: [task A runs fully][render][task B runs fully][render]  ← responsive
BAD:  [================ one 2-second task ================]    ← frozen UI
STARVED: microtask→microtask→microtask→…                       ← never renders
```

The modern mitigations are about **yielding the main thread** so the browser can interleave rendering and input: (1) **`scheduler.yield()`** (a newer API) and the `await new Promise(r => setTimeout(r, 0))` idiom break a long task into chunks, letting the browser paint and handle input between them; (2) **`scheduler.postTask(fn, { priority })`** offers prioritized task scheduling (`user-blocking` / `user-visible` / `background`); (3) **`requestIdleCallback`** defers non-urgent work to idle periods; (4) **Web Workers** move CPU-bound work off the main thread entirely (true parallelism), eliminating the blocking rather than just chunking it; (5) for animations, **`requestAnimationFrame`** aligns work with the paint cycle. The senior framing: run-to-completion is a *contract you both rely on and must respect* — you get lock-free atomicity for free, but in exchange you owe the platform short tasks. The discipline is to keep each task under ~50 ms by chunking, yielding, or offloading, and to never write an unbounded synchronous loop or recursive microtask chain on the main thread. This directly drives the Core Web Vital **INP** (Interaction to Next Paint), which measures exactly how long tasks delay the UI's response to user input.

#### Q61. [Practical] How do source maps work end to end, and why might production stack traces still be unreadable despite shipping source maps?

A **source map** is a JSON file (the `.map`) that records a bidirectional mapping between positions in the **generated, transformed output** (minified/bundled/transpiled JS) and positions in the **original source** (your TypeScript/ESNext/JSX). It uses **VLQ (Variable-Length Quantity) Base64-encoded segments** in its `mappings` field to compactly encode, for many output positions, the corresponding original file, line, column, and (optionally) original symbol name. The output file references it via a trailing `//# sourceMappingURL=app.js.map` comment (or an `X-SourceMap`/`SourceMap` HTTP header). When DevTools (or an error-reporting service like Sentry) loads the map, it can present the *original* source in the debugger and **symbolicate** stack traces — translating `app.min.js:1:24837` back into `UserList.tsx:42:9`.

```
ORIGINAL UserList.tsx  ──compile/minify──▶  app.min.js  (line 1, col 24837)
        ▲                                          │
        └────────── app.min.js.map (VLQ mappings) ─┘
   DevTools/Sentry reads the map → shows UserList.tsx:42 in the stack trace
```

The end-to-end flow in production typically *uploads* source maps to the error tracker at build time (rather than serving them publicly, to avoid exposing source), so symbolication happens server-side when an error report arrives. The build embeds a **`debugId`/build identifier** so the right map is matched to the exact deployed bundle.

Why traces stay unreadable despite "having" source maps — the real-world failure modes: (1) **version mismatch** — the deployed bundle and the uploaded map are from different builds (cache, partial deploy, no `debugId`), so offsets don't line up and you get garbage or "no source available"; (2) **the map wasn't actually uploaded/served**, or `sourcesContent` was stripped so only filenames (not the source text) are known; (3) **multi-stage transforms without map composition** — TS → Babel → bundler → minifier each produce a map, and if any stage doesn't *compose* its map with the previous one, the chain breaks and you map back only to an intermediate artifact, not the original; (4) **inlined/optimized code** — aggressive minification inlines functions and mangles names so a single output line corresponds to many original locations, and async stack frames are reconstructed by the engine (the `await` boundary can lose frames); (5) **eval/`Function`-constructed or dynamically-injected code** has no stable URL to map. The practical hardening: enable `sourcesContent`, use a stable `debugId`, ensure every transform stage emits and *composes* maps, upload maps to your error tracker keyed to the release, keep maps **off** the public CDN (or behind auth) to avoid leaking source, and verify symbolication on a real production error before trusting it — a source map that exists but isn't matched to the build is worse than none, because it gives confidently-wrong line numbers.

#### Q62. [Theory] What changed in ECMAScript across recent yearly editions (ES2020 through ES2025), and how do TC39 stages and feature detection inform what you can safely ship?

Since ES2015 (ES6), ECMAScript ships **yearly editions** named by year, each a relatively small, incremental batch of finished proposals — a deliberate shift away from the decade-long gap before ES6. The governance is **TC39's stage process**: Stage 0 (strawperson) → 1 (proposal) → 2 (draft) → 2.7 (the newer "ready, awaiting tests") → 3 (candidate — spec-complete, implementations begin shipping behind flags or in releases) → 4 (finished — merged into the spec, shipping). **Stage 3** is the practical "you may start using it with a transpiler/polyfill and feature detection" line; **Stage 4** means it's standardized.

A rough map of notable additions by edition:

```
ES2020: optional chaining ?., nullish coalescing ??, BigInt,
        Promise.allSettled, globalThis, dynamic import(), String.matchAll
ES2021: Promise.any + AggregateError, String.replaceAll,
        logical assignment &&= ||= ??=, WeakRef, numeric separators 1_000
ES2022: top-level await, class fields + #private methods, static blocks,
        Object.hasOwn, Error.cause, Array.prototype.at(), RegExp /d indices
ES2023: Array find-from-last (findLast/findLastIndex),
        change-by-copy (toSorted/toReversed/toSpliced/with), Hashbang grammar
ES2024: Object.groupBy / Map.groupBy, Promise.withResolvers,
        ArrayBuffer.prototype.resize, well-formed Unicode (toWellFormed), Atomics.waitAsync
ES2025: Iterator Helpers (map/filter/take on iterators), Set methods
        (union/intersection/difference), Promise.try, RegExp.escape,
        import attributes (import ... with { type: 'json' }), Float16Array
```

The senior point isn't memorizing the list — it's the **decision framework for shipping**. Whether you can use a feature depends on your **target runtimes**, not the spec year: check `caniuse`/MDN baseline and your Node version, then decide between (1) **transpilation** (Babel/TS lowers syntax like optional chaining to older equivalents — works for *syntax*), (2) **polyfilling** (core-js/explicit shims add *missing APIs* like `Object.groupBy` — works for *library features* but not new syntax), and (3) **feature detection** at runtime (`if (typeof Object.groupBy === 'function')` or `'at' in Array.prototype`) for progressive enhancement.

```javascript
// Prefer capability detection over version/UA sniffing:
const groupBy = typeof Object.groupBy === 'function'
  ? Object.groupBy
  : (items, fn) => items.reduce((acc, x) => {       // polyfill fallback
      const k = fn(x); (acc[k] ??= []).push(x); return acc;
    }, {});
```

Two distinctions experts emphasize: **syntax vs. API** features need *different* tooling (you cannot polyfill new syntax — it must be transpiled, because old engines throw a `SyntaxError` at parse time before any code runs), and **Stage 3 ≠ shippable-as-is** — a Stage 3 proposal can still change, so guard it behind transpilation/polyfills you control rather than relying on raw runtime support. The discipline is to pin a target baseline (e.g., "Baseline Widely Available" or specific Node/browser versions), let the build target it, and use runtime feature detection only where a graceful fallback genuinely matters.

#### Q63. [Theory] Explain `globalThis`, realms, and the cross-realm identity problem. Why does `[] instanceof Array` fail across an iframe or `vm` context, and how do you handle it?

A **realm** is an isolated execution environment with its **own complete set of intrinsics** — its own `Object`, `Array`, `Function`, `Promise`, `Object.prototype`, etc. Every browsing context creates a realm: the main page, each same-or-cross-origin `<iframe>`, each Web Worker, and in Node each `vm.createContext()` / `worker_thread`. Critically, **the built-in constructors and prototypes are *not shared* between realms** — the iframe's `Array.prototype` is a *different object* than the parent page's `Array.prototype`, even though they're structurally identical.

This breaks **`instanceof` across realms**, because `instanceof` checks whether a specific `Constructor.prototype` object appears in the value's prototype chain. An array created inside an iframe has the *iframe's* `Array.prototype` in its chain, which is `!==` the parent's `Array.prototype`, so `iframeArray instanceof parentArray` (i.e., the parent's `Array`) is `false` even though it is genuinely an array.

```javascript
const iframe = document.createElement('iframe');
document.body.appendChild(iframe);
const ForeignArray = iframe.contentWindow.Array;
const arr = new ForeignArray(1, 2, 3);

arr instanceof Array;        // false! — different realm's Array.prototype
Array.isArray(arr);          // true  — realm-agnostic, checks the [[Class]]/brand
arr instanceof ForeignArray; // true  — matches its own realm's constructor
```

The robust solutions are **realm-agnostic checks** that don't depend on prototype identity: `Array.isArray()` (purpose-built for exactly this), `Object.prototype.toString.call(x)` returning `"[object Array]"`/`"[object Date]"` etc. (reads the internal brand), or duck-typing on behavior. This is precisely *why* `Array.isArray` exists in the language — it predates and solves the cross-frame array-detection problem that plagued older libraries.

**`globalThis`** (ES2020) is the cross-environment way to reach the **global object** of the *current* realm — unifying `window` (browser main), `self` (workers), `global` (Node), and `frames`/`this`-at-top-level, each of which only worked in some environments. Note `globalThis` gives you *your* realm's global, not a shared one — each realm still has its own. The senior synthesis: never use `instanceof` (or naive prototype checks) on values that **might cross a realm boundary** — anything from an iframe, an opened window via `postMessage`, a worker message, a `vm` sandbox, or a deserialization library; instead use `Array.isArray`, `Object.prototype.toString.call`, structural/duck checks, or a brand you control. Also remember that `postMessage` **structured-clones** data across realms, so the received objects are reconstructed with the *receiving* realm's prototypes anyway — which both avoids and masks the problem depending on how the value travels. The deeper design lesson is that realm isolation is a *security and encapsulation* feature (the Realms/ShadowRealm proposal formalizes creating fresh isolated realms for plugin sandboxing), and identity-based type checks are inherently realm-fragile.

#### Q64. [Theory] How do getters/setters, property descriptors, and `Object.defineProperty` work, and what is the difference between data properties and accessor properties?

Every object property in JavaScript is described by a **property descriptor** — an internal record with attributes that control its behavior. There are two mutually exclusive kinds. A **data property** has `value`, `writable`, `enumerable`, and `configurable`. An **accessor property** replaces `value`/`writable` with `get` and `set` functions (plus `enumerable`/`configurable`). When you read an accessor property the engine calls its getter; when you assign, it calls the setter — so `obj.x` can run arbitrary logic while *looking* like a plain field. `Object.defineProperty` (and `Object.getOwnPropertyDescriptor`) let you set these attributes explicitly, which literal syntax can't fully do (literals always create `enumerable: true, writable: true, configurable: true` data properties).

```javascript
const obj = {};
Object.defineProperty(obj, 'id', {
  value: 42, writable: false, enumerable: false, configurable: false,
});
obj.id = 99;                 // silently ignored (sloppy) / throws (strict) — not writable
console.log(Object.keys(obj)); // [] — not enumerable

const temp = {
  celsius: 0,
  get fahrenheit() { return this.celsius * 9 / 5 + 32; },   // computed on read
  set fahrenheit(f) { this.celsius = (f - 32) * 5 / 9; },   // back-converts on write
};
temp.fahrenheit = 212;
console.log(temp.celsius);   // 100 — setter ran
```

The three boolean attributes each disable something specific: **`writable: false`** blocks reassigning a data property's value; **`enumerable: false`** hides the property from `for...in`, `Object.keys`, spread, and `JSON.stringify` (this is how built-in methods stay invisible — `Array.prototype.push` is non-enumerable, so `for...in` over an array doesn't list it); **`configurable: false`** is the strongest — it prevents deleting the property, changing its descriptor, or converting between data/accessor (and it's a one-way latch; you can't re-enable it). `Object.freeze` works by setting `writable: false` + `configurable: false` on every own property (shallowly).

| Attribute | Controls | Default (literal) | Default (`defineProperty`) |
|---|---|---|---|
| `value`/`get`/`set` | the property's content | as written | `undefined` |
| `writable` | reassignment (data only) | `true` | `false` |
| `enumerable` | visibility to enumeration/JSON/spread | `true` | `false` |
| `configurable` | delete/redefine | `true` | `false` |

The senior caveats: descriptors created via `defineProperty` **default to `false`** for all booleans (the opposite of literal syntax), a frequent surprise; getters/setters are powerful but add per-access function-call overhead and can break V8's shape optimizations on hot paths, so don't sprinkle them on performance-critical objects; and an accessor with only a getter makes a read-only computed property, while reactivity systems (Vue 2's `Object.defineProperty`-based reactivity, before Vue 3 switched to `Proxy`) historically used accessors to intercept reads/writes for change tracking — which is why Vue 2 couldn't detect property *additions* (no descriptor existed yet to intercept).

#### Q65. [Theory] Explain regex internals in JavaScript: backtracking and catastrophic backtracking (ReDoS), the `u`/`v`/`y`/`d`/`s` flags, and when to prefer non-regex parsing.

JavaScript's `RegExp` uses a **backtracking** engine (NFA-based), not a DFA. When a pattern with alternation or quantifiers fails to match at some point, the engine *backtracks* — it returns to the last choice point and tries another path. For most patterns this is fine, but certain shapes cause **catastrophic backtracking**: the number of paths the engine explores grows **exponentially** with input length, hanging the (single-threaded!) main thread. The classic trigger is **nested quantifiers** over overlapping alternatives, like `(a+)+$` or `(\w+\s?)*$` against a long non-matching string — this is the basis of **ReDoS** (Regular-expression Denial of Service), a real attack vector when regexes run on untrusted input.

```javascript
// CATASTROPHIC — exponential paths on a long string that ultimately fails:
const bad = /^(a+)+$/;
bad.test('a'.repeat(30) + '!');   // can hang for seconds — exponential backtracking

// Fixes: avoid nested/overlapping quantifiers; anchor; use possessive-like rewrites
const good = /^a+$/;              // linear — no nested quantifier
```

The modern **flags** each change matching semantics: **`g`** (global, stateful via `lastIndex`), **`i`** (case-insensitive), **`m`** (multiline `^`/`$`), **`s`** (dotAll — `.` matches newlines, ES2018), **`u`** (Unicode mode — treats the pattern as code points, enables `\u{...}` and `\p{...}` property escapes, ES2015), **`v`** (the newer Unicode-sets mode, ES2024 — adds set operations and string properties, a superset of `u`), **`y`** (sticky — matches only at exactly `lastIndex`, no skipping ahead), and **`d`** (hasIndices, ES2022 — exposes match start/end offsets via `match.indices`). The `u`/`v` flags matter for correctness: without them, `.` and quantifiers operate on UTF-16 *code units*, so emoji and astral characters (surrogate pairs) are mishandled.

```javascript
'café'.match(/\p{Letter}+/u);     // works — needs `u` for property escapes
/^\d+$/v.test('123');             // `v` mode (Unicode sets), superset of `u`
```

The expert guidance is twofold. First, **defend against ReDoS**: never run user-controlled patterns; audit your own patterns for nested quantifiers and overlapping alternation; prefer linear constructs (specific character classes over `.*`, anchoring, atomic rewrites); set timeouts or run regex on untrusted input in a worker; and consider engines/libraries with linear guarantees (RE2-style) for hostile input. Second, **know when regex is the wrong tool**: regex cannot correctly parse *recursive/nested* grammars (HTML, JSON, balanced parentheses, nested comments) — these are not regular languages, and trying produces fragile, exploitable patterns. For those, use a real parser (`DOMParser`, `JSON.parse`, a tokenizer). The famous principle applies: if you parse HTML with regex, you'll have *two* problems. Regex is excellent for *lexical* tasks (validating a token's shape, extracting flat fields) and a liability for *structural* ones.

#### Q66. [Theory] What is "monkey patching" and prototype extension, and why are they discouraged? Contrast with `Symbol`-based extension and the `Array.prototype.includes` vs `contains` history.

**Monkey patching** is modifying built-in objects or third-party code at runtime — typically by adding or replacing methods on built-in prototypes (`String.prototype.trimStart = function(){...}`) or overwriting existing functions. **Prototype extension** is the specific case of adding methods to `Array.prototype`, `Object.prototype`, etc., so all instances gain the method. Both were common in the jQuery/MooTools era and remain a serious anti-pattern for several concrete reasons.

The first hazard is **`for...in` pollution**: a method added to `Object.prototype` (or `Array.prototype`) is *enumerable by default* if assigned with plain syntax, so it shows up in every `for...in` loop across the entire program, silently breaking code that iterates object keys. The second is **collision with future standards** — and this actually happened. MooTools shipped `Array.prototype.contains`. When TC39 later tried to standardize the method, sites using MooTools broke, so the committee was forced to *rename the standard method* to **`includes`** (and the proposal was nicknamed "SmooshGate" after a related `flatten`→`flat` rename driven by the same web-compatibility concern). This is a permanent scar on the language API caused directly by prototype extension.

```javascript
// DON'T — enumerable, global, collision-prone:
Array.prototype.last = function () { return this[this.length - 1]; };
for (const k in [1, 2]) console.log(k); // "0","1","last" — pollutes every loop

// If you MUST extend a prototype, at least make it non-enumerable and guard:
if (!Array.prototype.last) {
  Object.defineProperty(Array.prototype, 'last', {
    value() { return this[this.length - 1]; },
    enumerable: false, writable: true, configurable: true,
  });
}
```

Beyond pollution and collisions: monkey patching creates **action-at-a-distance** (a method's behavior changes depending on which library loaded last, making bugs non-local), defeats **tree-shaking** (the patch must run for side effects), risks **breaking on engine upgrades** (your patch and a new native method fight), and complicates **multiple versions** of a dependency coexisting. The accepted alternatives, in order of preference: (1) **standalone functions / utility modules** (`last(arr)` instead of `arr.last()`) — explicit, tree-shakeable, no global mutation, the functional-programming norm; (2) **subclassing or composition** for your own types; (3) if extension is truly required (e.g., a controlled polyfill), use `Object.defineProperty` with `enumerable: false` and a presence guard, and ideally key the addition with a **`Symbol`** rather than a string so it cannot collide with future standard names or other libraries' string keys. The principled stance: treat built-in prototypes as **read-only shared global state** — extending them is mutating a global everyone depends on, and the `includes`/`contains` saga is the canonical proof of why the platform itself learned this lesson the hard way.

#### Q67. [Practical] Compare strict mode vs sloppy mode comprehensively. What does `"use strict"` change, and why are ES modules and class bodies always strict?

**Strict mode** (`"use strict"`, ES5) is an opt-in variant of JavaScript that removes error-prone "sloppy" (legacy/non-strict) behaviors and forbids syntax likely to be problematic, trading a bit of permissiveness for safety and optimizability. It can be enabled per-file (directive at the top) or per-function (directive at the function's top); critically, **ES modules and `class` bodies are *always* strict** with no opt-out, which is why much modern code is strict without an explicit directive. The reason classes/modules force it: they're new contexts where the committee could mandate the cleaner semantics without breaking legacy code, and strict mode's static guarantees (no `with`, predictable scoping) enable better analysis and align with the lexical model these features assume.

The concrete behavioral changes fall into a few buckets:

```javascript
'use strict';
// 1. Assignment to undeclared variable THROWS (sloppy: creates an implicit global)
undeclared = 5;            // ReferenceError (sloppy: silently makes window.undeclared)

// 2. `this` in a plain function call is `undefined` (sloppy: globalThis)
function f() { return this; }
f();                       // undefined (sloppy: window/global) — prevents accidental globals

// 3. Silent failures become THROWS:
const frozen = Object.freeze({ x: 1 });
frozen.x = 2;              // TypeError (sloppy: silently ignored)
delete Object.prototype;   // TypeError (sloppy: silently fails)

// 4. Duplicate param names, octal literals (0777), `with` → SyntaxError
// 5. `eval` gets its own scope (can't leak vars into the caller)
// 6. `arguments`/`caller`/`callee` are restricted; arguments no longer aliases params
```

| Behavior | Sloppy mode | Strict mode |
|---|---|---|
| Undeclared assignment | creates global | `ReferenceError` |
| `this` in plain call | `globalThis` | `undefined` |
| Write to read-only/frozen | silent no-op | `TypeError` |
| `with` statement | allowed | `SyntaxError` |
| Duplicate param names | allowed | `SyntaxError` |
| `arguments` aliases params | yes | no (decoupled) |
| `eval` scope | leaks to caller | isolated |
| Octal literal `0777` | allowed | `SyntaxError` |

The most consequential change in practice is **`this` being `undefined`** in unbound function calls — this is what surfaces the "lost `this`" bug *loudly* (a `TypeError: Cannot read property of undefined`) instead of silently writing onto the global object, which is far safer and is why class methods, when detached, throw rather than corrupting globals. The senior framing: strict mode isn't a feature you toggle for fun — it's the **baseline correctness contract** of modern JS. Because modules and classes are always strict, the real-world advice is to write everything as ES modules (getting strict mode for free), never rely on sloppy-mode behaviors, and understand that strict mode also enables certain engine optimizations by eliminating the dynamic-scope escape hatches (`with`, leaky `eval`) that would otherwise force conservative compilation. There is essentially no reason to write new sloppy-mode code.

#### Q68. [Coding] Implement a memoization utility with a configurable cache key and TTL, and explain the trade-offs of memoization (correctness, memory, equality of arguments).

**Memoization** caches a pure function's results keyed by its arguments, so repeated calls with the same inputs return instantly instead of recomputing. The implementation challenges are all about the **key**: how do you turn arbitrary arguments into a cache key, when do you consider two argument sets "the same," and how do you avoid the cache growing without bound. A production-grade memoizer needs a configurable key resolver (because deep-equality of objects can't be free), and often a **TTL** (time-to-live) so stale results expire — important when the underlying data can change.

```javascript
function memoize(fn, { keyFn = JSON.stringify, ttlMs = Infinity, maxSize = Infinity } = {}) {
  const cache = new Map();                 // Map preserves insertion order → LRU eviction
  function memoized(...args) {
    const key = keyFn(args);
    const hit = cache.get(key);
    if (hit && (ttlMs === Infinity || Date.now() - hit.t < ttlMs)) {
      // refresh recency for LRU
      cache.delete(key); cache.set(key, hit);
      return hit.v;
    }
    const value = fn.apply(this, args);
    cache.set(key, { v: value, t: Date.now() });
    if (cache.size > maxSize) cache.delete(cache.keys().next().value); // evict oldest
    return value;
  }
  memoized.clear = () => cache.clear();
  return memoized;
}

const slowSquare = (n) => { /* expensive */ return n * n; };
const fast = memoize(slowSquare, { ttlMs: 5000, maxSize: 100 });
fast(9); fast(9);   // second call is a cache hit
```

The crux is the **key strategy and its trade-offs**. `JSON.stringify` is a convenient default but is wrong or dangerous in several cases: it **ignores key order** mismatches as distinct keys (`{a:1,b:2}` vs `{b:2,a:1}` stringify differently → false cache misses), it **throws on circular references**, it **drops `undefined`/functions/symbols**, and it can be expensive for large arguments. Alternatives trade precision for cost: a single-object-argument cache can use a **`WeakMap`** keyed by the object identity (the React/reselect approach), giving automatic GC of entries when the argument object dies and avoiding serialization entirely — but it only works when "same input" means "same reference" (reference equality), which is exactly the model libraries like `reselect` and `useMemo` use. For multiple primitive args, a **nested Map (trie)** keyed by each arg avoids stringification and respects reference identity.

```
KEY STRATEGY              SAME-INPUT MEANS        COST            FOOTGUN
JSON.stringify(args)      structural (serialized) O(arg size)     order/circular/undefined
WeakMap by object arg     reference identity      O(1), auto-GC   misses on new equal object
nested Map (per-arg trie) per-arg reference/value O(arity)        manual eviction needed
```

The broader trade-offs an expert names: (1) **correctness requires purity** — memoizing an impure function (one that reads mutable external state, the clock, or randomness) returns stale/wrong results; the TTL exists precisely to bound that staleness when inputs *look* the same but the world changed. (2) **memory vs. speed** — an unbounded cache is a memory leak by construction (it strongly retains every result and every key forever), so any long-lived memoizer needs eviction (LRU via `Map` ordering, max size) or weak references. (3) **equality semantics** — the single biggest source of bugs is the mismatch between what the caller *thinks* is "the same input" (deep/structural equality) and what the cache actually keys on (usually reference or serialized equality); `useMemo`/`reselect` recompute when a *new* array/object literal is passed even if its contents are identical, which is the canonical "my memo never hits" React bug. The decision rule: memoize only **pure, hot, expensive** functions; choose reference-keyed (`WeakMap`/per-arg `Map`) caches for object arguments and serialized keys only for small primitive arguments; and always bound the cache (size and/or TTL) unless the input domain is provably tiny and fixed.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q69. [Practical] How do you debug JavaScript effectively beyond `console.log`? Cover breakpoints, `debugger`, conditional/logpoints, and the console API.

`console.log` is the universal first tool, but it has real costs: it serializes the value *at log time* (objects logged by reference may show their *later* mutated state when you expand them in DevTools, which is confusing), it clutters production code, and it forces a recompile/reload cycle for each change. The professional baseline is **breakpoints** set in the DevTools Sources panel (or the `debugger;` statement in code), which pause execution and let you inspect the *live* scope, the call stack, and `this` at the exact frame — no guessing which variable to log in advance.

The under-used power features are **conditional breakpoints** (right-click a line → "Add conditional breakpoint" → `id === 42`) so you only pause on the iteration that matters instead of stepping through thousands, and **logpoints** (a breakpoint that logs an expression and continues *without* pausing) — these inject logging *without editing source*, so you can instrument third-party or minified code and remove it instantly. **DOM breakpoints** (break on subtree/attribute changes), **XHR/fetch breakpoints** (break when a URL is requested), and **event listener breakpoints** (break on any `click`, `resize`, etc.) let you catch *what triggered* a change when you don't know where to look.

```javascript
// Richer console API than just .log:
console.table(users);                 // tabular view of array of objects
console.group('request'); console.log('url', url); console.groupEnd();
console.assert(total >= 0, 'total went negative', { total }); // logs only if false
console.time('parse'); /* work */ console.timeEnd('parse');    // duration
console.trace();                      // prints the call stack to this point
console.dir(domNode);                 // object view (not the rendered HTML)
console.count('render');              // how many times this line ran
```

The senior workflow combines these: set a conditional breakpoint at the suspicious line, inspect the call stack to find *who* called with the bad value, use "step into / over / out" to trace control flow, watch expressions in the Watch pane, and use the **"Pause on caught/uncaught exceptions"** toggle to stop exactly where an error is thrown rather than after it's swallowed. For async code, enable **async stack traces** (on by default in modern DevTools) so the stack shows the logical chain across `await` boundaries, not just the microtask that resumed. The meta-point: `console.log` answers "what is this value?"; breakpoints answer "how did we get here and what else is true right now?" — the latter is what actually resolves most bugs.

#### Q70. [Practical] What is `package.json`, and what do `dependencies` vs `devDependencies` vs `peerDependencies`, semver ranges, and lockfiles actually control?

`package.json` is the manifest that defines a JavaScript project: its name, version, entry points (`main`/`module`/`exports`), scripts, and — most importantly for builds — its **dependency declarations**. The three dependency buckets answer different questions. **`dependencies`** are packages your code needs *at runtime* (React, lodash) — they're installed for anyone who installs your package. **`devDependencies`** are needed only to *build/test* the project (TypeScript, Jest, ESLint, bundlers) — they're skipped when your package is installed as someone else's dependency, keeping their `node_modules` lean. **`peerDependencies`** declare "I need the *host* project to provide this" (a React plugin declares `react` as a peer so it uses the app's single React instance rather than bundling a duplicate that breaks hooks).

```json
{
  "dependencies":     { "react": "^18.2.0" },
  "devDependencies":  { "vitest": "^1.0.0", "typescript": "~5.4.0" },
  "peerDependencies": { "react": ">=17" },
  "scripts": { "build": "tsc && vite build", "test": "vitest run" }
}
```

The **semver range prefix** controls how much auto-upgrade you accept on `npm install`: `^1.2.3` allows any `1.x.x` (compatible, no major bump — the default), `~1.2.3` allows only patch (`1.2.x`), an exact `1.2.3` pins it, and `*`/`latest` accepts anything (dangerous). The model is **MAJOR.MINOR.PATCH**: major = breaking, minor = backward-compatible feature, patch = backward-compatible fix. Caret ranges are why two `npm install`s on different days can resolve *different* transitive versions — which is exactly the problem the **lockfile** solves.

The **lockfile** (`package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`) records the *exact* resolved version and integrity hash of every package in the entire dependency tree, so `npm ci` reproduces a byte-identical `node_modules` across machines and CI. The rule: **commit the lockfile**, use `npm ci` (not `npm install`) in CI for reproducibility, and treat lockfile changes in PRs as security-relevant (a changed integrity hash means a dependency's contents changed). The common production incident is "works on my machine, fails in CI" caused by a floating `^` range pulling a new transitive minor that introduced a regression — the lockfile + `npm ci` is the fix, and `npm audit`/`overrides` handle pinning vulnerable transitive deps.

#### Q71. [Theory] What is `JSON.stringify`/`JSON.parse` actually doing, and what are its silent data-loss and edge-case behaviors?

`JSON.stringify(value, replacer, space)` serializes a value to a JSON *string*, and `JSON.parse(text, reviver)` does the inverse. JSON is a strict subset of JavaScript literal syntax, so the conversion is **lossy and asymmetric** in ways that cause real bugs. `stringify` **silently drops** `undefined`, functions, and symbols when they're object *property values* (the key vanishes entirely), but converts them to `null` when they're *array elements* (to preserve positions). `NaN`, `Infinity`, and `-Infinity` all become `null`. `Date` objects are converted to ISO strings via their `toJSON` method — and crucially, `JSON.parse` does *not* turn them back into `Date`s; you get a string.

```javascript
JSON.stringify({ a: undefined, b: () => {}, c: Symbol() }); // "{}" — all dropped
JSON.stringify([undefined, function(){}, NaN]);             // "[null,null,null]"
JSON.stringify({ d: new Date(0) });        // '{"d":"1970-01-01T00:00:00.000Z"}'
JSON.parse('{"d":"1970-01-01T00:00:00.000Z"}').d; // a STRING, not a Date
JSON.stringify(10n);                       // TypeError — BigInt can't be serialized
JSON.stringify({ x: 1, self: null });      // ok, but a real cycle throws TypeError
```

The **`replacer`** (a function or allow-list array) and **`reviver`** (a function run on each parsed key/value) are the escape hatches for these limitations. A reviver can rehydrate Dates (`(k, v) => dateKeys.has(k) ? new Date(v) : v`), and a replacer can serialize BigInts as strings or strip sensitive fields. An object's own **`toJSON()`** method, if present, is called first and its return value is serialized instead — this is how `Date` controls its own format and how you can make class instances serialize cleanly.

The production guidance: never assume a round-trip (`JSON.parse(JSON.stringify(x))`) preserves your data — it's the cause of the broken deep-clone trick (dropped fields, stringified Dates, thrown cycles), so use `structuredClone` for cloning. For API boundaries, define explicit serialize/deserialize logic (or a schema library like Zod) rather than trusting JSON's defaults; serialize `BigInt`/`Date`/`Map` deliberately with replacers/revivers; and remember `JSON.parse` of untrusted input is *safe* (unlike `eval`) but the *resulting object* can carry a `__proto__` key that a careless deep-merge turns into prototype pollution.

### 🟡 Intermediate — extended

#### Q72. [Theory] How does tree-shaking work, and what concrete code patterns silently defeat it in a bundler?

**Tree-shaking** is dead-code elimination at the module level: a bundler (Rollup, esbuild, webpack, Vite) statically analyzes the **ESM import/export graph**, marks which exports are actually *used* by the entry points, and drops the rest from the output bundle. It works *only* because ES modules have **static, statically-analyzable structure** — `import { a } from 'x'` names exactly what's needed at parse time, before any code runs. CommonJS `require()` is dynamic (you can `require(someVariable)` or `require` conditionally), so bundlers generally can't tree-shake CJS reliably and include the whole module.

The patterns that **silently defeat** tree-shaking are subtle because the code still works — it's just fat:

```javascript
// 1. Side-effectful import (whole module retained for its top-level effects)
import './analytics';                 // no bindings used, but kept for side effects

// 2. Namespace import that's dynamically indexed (analyzer can't prove what's used)
import * as utils from './utils';
utils[methodName]();                  // dynamic key → entire namespace retained

// 3. Re-export barrels that pull in everything
export * from './hugeLibrary';        // a barrel file can drag in the whole lib

// 4. Default-exporting a big object (no granular dead-code analysis possible)
export default { fn1, fn2, /* ...50 helpers... */ }; // import one → get all
```

The biggest real-world culprit is the **`sideEffects` flag** in `package.json`. By default a bundler must *assume* every module might have side effects (a top-level `window.x = ...` or polyfill install) and therefore can't drop an imported-but-unused module. Declaring `"sideEffects": false` (or an allow-list like `["*.css", "./polyfill.js"]`) tells the bundler "importing my modules is pure — if you don't use the export, drop the module entirely." Libraries that omit this lose tree-shaking for their consumers even with perfect ESM.

```json
{ "sideEffects": false }              // "my modules are pure" — enables aggressive shaking
```

The practical discipline: ship **ESM** (`"module"`/`"exports"` with an `import` condition), set `sideEffects` honestly, prefer **named exports of standalone functions** over default-exported god-objects, import granularly (`import debounce from 'lodash-es/debounce'` or use `lodash-es`, not the CJS `lodash`), avoid dynamic namespace indexing on hot import paths, and verify with a **bundle analyzer** (`source-map-explorer`, `rollup-plugin-visualizer`) rather than assuming — the number-one cause of "why is my bundle 800 KB" is a single CJS or non-`sideEffects` dependency that couldn't be shaken.

#### Q73. [Practical] How do `localStorage`, `sessionStorage`, cookies, and IndexedDB differ, and how do you choose between them?

The browser offers several client-side storage mechanisms with very different capacities, lifetimes, and access models, and choosing wrong causes either data loss or performance/security problems. **`localStorage`** and **`sessionStorage`** share the simple synchronous `getItem`/`setItem` string-only API; the difference is lifetime: `localStorage` persists until explicitly cleared (survives restarts), `sessionStorage` is scoped to the tab/session and dies when the tab closes. Both are **origin-scoped**, capped at roughly **5–10 MB**, and — critically — **synchronous and main-thread-blocking**, so writing large blobs janks the UI.

```javascript
localStorage.setItem('theme', JSON.stringify({ mode: 'dark' })); // strings only
const theme = JSON.parse(localStorage.getItem('theme') ?? '{}'); // manual JSON
// Synchronous: a 4MB write here blocks rendering until it completes.
```

**Cookies** are different in kind: they're tiny (~4 KB each), and they're **automatically sent to the server on every matching HTTP request**, which makes them the mechanism for *server-readable* session state (auth tokens), but also means putting client-only data in them wastes bandwidth on every request. Security attributes matter: `HttpOnly` (inaccessible to JS — defends against XSS token theft), `Secure` (HTTPS only), and `SameSite` (`Lax`/`Strict`/`None` — CSRF defense). Auth tokens belong in `HttpOnly` cookies, *not* `localStorage`, precisely because `localStorage` is readable by any injected script (XSS).

**IndexedDB** is the only option for *large, structured* client data: it's an asynchronous (Promise/event-based), transactional, object-store database supporting indexes, hundreds of MB to GB (subject to quota), and storage of structured-clonable values (objects, Blobs, ArrayBuffers) without manual JSON. Its raw API is verbose, so wrappers like `idb` or `Dexie` are standard.

| Mechanism | Capacity | Lifetime | Sync/Async | Sent to server | Stores |
|---|---|---|---|---|---|
| `localStorage` | ~5–10 MB | until cleared | sync (blocks) | no | strings |
| `sessionStorage` | ~5–10 MB | tab/session | sync (blocks) | no | strings |
| Cookies | ~4 KB | configurable | sync | **yes (every req)** | strings |
| IndexedDB | 100s MB–GB | until cleared | **async** | no | structured |

The decision rule: tiny UI prefs (theme, last route) → `localStorage`; per-tab transient state → `sessionStorage`; anything the *server* needs per request or any auth credential → `HttpOnly`/`Secure`/`SameSite` cookie; large datasets, offline caches, files, or anything you'd otherwise stuff into `localStorage` as a giant JSON string → IndexedDB. Two senior cautions: never store secrets or tokens in `localStorage`/`sessionStorage` (XSS-readable), and never do large synchronous `localStorage` writes in a hot path or on every keystroke — batch/debounce them, or move to async IndexedDB.

#### Q74. [Theory] How does `fetch` work, and what do you need to know about CORS, credentials, response streaming, and error semantics?

`fetch(url, options)` returns a Promise that resolves to a `Response` as soon as the **headers** arrive — *not* when the body is downloaded. A crucial and frequently-missed semantic: **`fetch` only rejects on network failure** (DNS, connection, CORS block), **not** on HTTP error status. A `404` or `500` *resolves* successfully with `res.ok === false`, so you must check `res.ok`/`res.status` yourself — assuming `fetch` throws on `500` is a classic bug.

```javascript
const res = await fetch('/api/user');
if (!res.ok) throw new Error(`HTTP ${res.status}`);   // fetch does NOT throw on 4xx/5xx
const user = await res.json();   // body read is a SECOND async step (and one-shot)
```

The body is a **one-shot stream**: you can call `res.json()` / `res.text()` / `res.arrayBuffer()` *once*. To read it twice (e.g., log raw text and also parse), call `res.clone()` first. Because the body is a `ReadableStream`, you can also process huge responses incrementally via `res.body.getReader()` (or `for await...of res.body`) for progress bars or streaming parsing without buffering the whole payload in memory.

**CORS** governs cross-origin requests at the *browser* level: for a cross-origin call the browser enforces the **Same-Origin Policy** unless the server opts in with `Access-Control-Allow-Origin` (and friends). "Non-simple" requests (custom headers, methods like `PUT`/`DELETE`, certain content types) trigger a **preflight `OPTIONS`** request the server must answer before the real request is sent. CORS is enforced by the browser — it does *not* protect the server, and a CORS error in the console means the *server* didn't grant access, not that your code is wrong. Credentials (cookies) are **not** sent cross-origin unless you set `credentials: 'include'` *and* the server returns `Access-Control-Allow-Credentials: true` with a specific (non-`*`) origin.

```javascript
await fetch('https://api.other.com/data', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' }, // triggers preflight
  body: JSON.stringify(payload),
  credentials: 'include',          // send cookies cross-origin (server must allow)
  signal: AbortSignal.timeout(5000),
});
```

The production checklist: always check `res.ok`; distinguish `AbortError` from real failures in `catch`; set `Content-Type` correctly (and remember `FormData` sets its own multipart boundary — don't set it manually); use `credentials` deliberately (`same-origin` is the default — cookies on same-origin only); stream large bodies instead of buffering; and understand that CORS failures, opaque responses (`mode: 'no-cors'`), and mixed-content blocks are *browser* policies you resolve with server headers and HTTPS, not client retries. Compared to legacy `XMLHttpRequest`, `fetch` is Promise-based and streaming-capable but historically lacked upload progress and cancellation — cancellation is now solved by `AbortController`.

#### Q75. [Theory] Why are JavaScript dates notoriously buggy, and how do `Date`, time zones, UTC, and the `Temporal` API address it?

The legacy `Date` object is one of JavaScript's worst-designed APIs, and its pitfalls cause real production incidents. The core problems: it's **mutable** (methods like `setMonth` mutate in place, enabling spooky action), months are **0-indexed** (January is `0`) while days are 1-indexed, parsing is **implementation-dependent** for non-ISO strings, and — the biggest one — a `Date` is internally just a UTC millisecond timestamp, but its accessor methods (`getHours`, `getDate`) silently apply the **runtime's local time zone**, so the same code gives different results on a server in UTC vs. a user in Tokyo.

```javascript
new Date(2024, 0, 15);          // Jan 15 — month is 0-indexed (0 = January!)
new Date('2024-03-10');         // parsed as UTC midnight (date-only ISO)
new Date('2024-03-10T00:00');   // parsed as LOCAL midnight — different instant!
const d = new Date();
d.setDate(d.getDate() + 1);     // mutates d in place; also DST/month-overflow traps
```

The classic bugs: **off-by-one dates** when a UTC timestamp displayed in a negative-offset zone rolls back a day; **DST transitions** where adding "24 hours" doesn't equal "tomorrow at the same wall-clock time"; and **parsing ambiguity** where `new Date('03/04/2024')` is March 4 or April 3 depending on locale/engine. For *formatting and zone-aware display*, the right legacy tool is **`Intl.DateTimeFormat`**, which formats correctly for any locale and an explicit `timeZone` (`{ timeZone: 'America/New_York' }`) without relying on the machine's zone — far better than manual offset math.

```javascript
new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Tokyo', dateStyle: 'long', timeStyle: 'short',
}).format(new Date());          // zone-correct, locale-correct display
```

The real fix is **`Temporal`** (a TC39 proposal reaching browsers/runtimes in 2024–2026), which replaces `Date` with a family of **immutable, explicit** types: `Temporal.Instant` (an exact UTC point), `Temporal.ZonedDateTime` (an instant *with* a named time zone, DST-aware), `Temporal.PlainDate`/`PlainTime`/`PlainDateTime` (calendar/wall-clock values with *no* zone), and `Temporal.Duration`. It makes the zone explicit, does correct DST-aware arithmetic, parses strict ISO-8601, and never mutates. The production guidance for *today's* code: **store and transmit timestamps in UTC (ISO-8601 / epoch millis)**, do all arithmetic in UTC, and convert to local zone *only at the display layer* with `Intl.DateTimeFormat`; never build dates by string concatenation or manual offset arithmetic; reach for a battle-tested library (`date-fns`, `Luxon`) until `Temporal` is broadly available, then migrate to `Temporal` for new code. The meta-lesson: time-zone bugs are *data-modeling* bugs — keep a single canonical UTC representation and treat local time as a presentation concern.

#### Q76. [Coding] Implement an `async` task queue / pool that runs at most N promises concurrently. Why is naive `Promise.all` over a huge array a production hazard?

Calling `Promise.all(urls.map(fetch))` over thousands of items launches *all* of them simultaneously — which in production means **exhausting the connection pool, hitting rate limits / 429s, spiking memory, and getting throttled or banned**. The fix is a **concurrency-limited pool**: run at most N tasks at once, starting the next as each finishes, so you bound resource usage while still parallelizing. This is one of the most practically important async patterns and a common senior coding question.

```javascript
async function pool(tasks, concurrency) {
  const results = new Array(tasks.length);
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < tasks.length) {
      const i = nextIndex++;               // claim an index (atomic in single-thread)
      try { results[i] = { status: 'fulfilled', value: await tasks[i]() }; }
      catch (e) { results[i] = { status: 'rejected', reason: e }; }
    }
  }
  // Spin up `concurrency` workers that drain the shared task list
  const workers = Array.from({ length: Math.min(concurrency, tasks.length) }, worker);
  await Promise.all(workers);
  return results;                          // order preserved by index
}

// Usage: tasks are FUNCTIONS returning promises, so they start lazily inside the pool
const tasks = urls.map((u) => () => fetch(u).then((r) => r.json()));
const results = await pool(tasks, 5);      // never more than 5 in flight
```

The key design choices: tasks are **thunks** (`() => promise`), not already-started promises — if you pass live promises, they've *already* begun running and the pool can't limit them. The workers share a single `nextIndex` cursor; because JavaScript is single-threaded with run-to-completion, the `nextIndex++` claim is race-free (no two workers grab the same index). Results are stored by original index so output order matches input regardless of completion order, and wrapping each in `{status, value/reason}` makes it `allSettled`-like so one failure doesn't abort the batch (swap in a re-throw if you want fail-fast).

```
concurrency = 3, 9 tasks:
worker1: [t0]──▶[t3]──▶[t6]
worker2: [t1]──▶[t4]──▶[t7]    ← at most 3 in flight at any instant
worker3: [t2]──▶[t5]──▶[t8]
```

In production this pattern underpins crawlers, bulk API clients, batch image processing, and database migrations. Enhancements a strong answer mentions: thread an `AbortSignal` so the whole pool can be canceled; add **retry with backoff** per task (composing with the retry helper); respect server `Retry-After` headers on 429; and consider libraries like `p-limit`/`p-map` (which implement exactly this) rather than hand-rolling. The anti-pattern to call out explicitly is unbounded `Promise.all` over user-controlled or large collections — it's the canonical way to accidentally DOS your own dependency or get rate-limited, and it's invisible in testing with small datasets.

### 🟠 Advanced — extended

#### Q77. [Practical] Walk through diagnosing a memory leak in a long-running Node.js process. What tools, signals, and code patterns are involved?

A leaking Node service shows a characteristic signature: **Resident Set Size (RSS) and heap-used trend upward over hours/days**, garbage collection runs more frequently and for longer (visible as rising event-loop latency), and eventually the process hits the V8 heap limit and crashes with `FATAL ERROR: Reached heap limit — Allocation failed - JavaScript heap out of memory`, or the OOM killer terminates it. The first diagnostic step is **observability**: log `process.memoryUsage()` (`rss`, `heapTotal`, `heapUsed`, `external`, `arrayBuffers`) periodically and chart it — a sawtooth that returns to baseline after GC is healthy; a staircase that never returns to baseline is a leak.

```javascript
setInterval(() => {
  const m = process.memoryUsage();
  console.log(JSON.stringify({                 // ship to your metrics system
    rss: m.rss, heapUsed: m.heapUsed, external: m.external,
  }));
}, 60_000);
```

To find *what* is retained, take **heap snapshots** and diff them. In Node you can `require('v8').writeHeapSnapshot()` (or send `SIGUSR2` with the `--heapsnapshot-signal` flag, or use `--inspect` + Chrome DevTools Memory tab). Take a snapshot, exercise the suspect operation many times, force GC, take another, and **compare retained size** by constructor — the objects whose count/retained size grows monotonically are the leak. The **retainer path** (the chain of references keeping an object alive) tells you *which* container is holding it: a global array, a `Map` that's only ever `set` (never `delete`d), an `EventEmitter` accumulating listeners, or a closure.

The most common Node-specific leak patterns: (1) **`EventEmitter` listener accumulation** — adding a listener per request without removing it; Node warns with `MaxListenersExceededWarning` at 11 listeners, which is your early signal. (2) **Unbounded module-level caches** — a `const cache = new Map()` at module scope that grows forever (fix: LRU bound or `WeakMap`). (3) **Closures captured by long-lived timers/intervals** that hold large request-scoped objects. (4) **Promises that never settle**, keeping their `.then` chains and captured scope alive. (5) **Per-request state stored on a singleton** (a global request context array).

```bash
node --inspect --max-old-space-size=512 server.js   # cap heap to surface leaks faster
node --trace-gc server.js                            # log every GC (frequency/duration)
node --heapsnapshot-signal=SIGUSR2 server.js         # kill -USR2 <pid> to dump a snapshot
```

The senior approach is methodical: reproduce under load (autocannon/k6), capture before/after snapshots, follow retainer paths to the owning structure, and fix the *root* (remove listeners with `off`/`AbortController`, bound caches, clear timers, null out request-scoped refs). Prevention is architectural: prefer `WeakMap`/`WeakSet` for object-keyed metadata so entries GC automatically, set `emitter.setMaxListeners` thresholds as tripwires, bound every cache, and add heap metrics + alerting so a slow leak is caught in staging, not at 3 a.m. in production. The contrast with browser leaks: the diagnostic tooling (heap snapshots, retainer paths) is the same, but Node leaks compound over *days* of uptime and the failure mode is a process crash/restart loop rather than a sluggish tab.

#### Q78. [Theory] What is the difference between `Object.freeze`, `Object.seal`, `Object.preventExtensions`, and deep immutability? How do you implement true deep-freeze, and what are the performance implications?

JavaScript offers three levels of object lock-down, each strictly stronger than the last, all operating only on a single object's *own* properties. **`Object.preventExtensions(obj)`** stops *new* properties from being added but allows modifying and deleting existing ones. **`Object.seal(obj)`** does that *plus* makes all existing properties non-configurable (can't delete or reconfigure) — but values are still **writable**. **`Object.freeze(obj)`** does all of the above *and* makes every property non-writable, so the object becomes fully read-only. All three are **shallow** — they lock the top level only; nested objects remain fully mutable.

```javascript
const o = Object.freeze({ a: 1, nested: { b: 2 } });
o.a = 99;          // silently ignored (sloppy) / TypeError (strict) — frozen
o.c = 3;           // ignored — non-extensible
o.nested.b = 99;   // SUCCEEDS — freeze is SHALLOW; nested object isn't frozen
console.log(o.nested.b); // 99
```

| Operation | Add props | Delete props | Modify values | Reconfigure |
|---|---|---|---|---|
| `preventExtensions` | ❌ | ✅ | ✅ | ✅ |
| `seal` | ❌ | ❌ | ✅ | ❌ |
| `freeze` | ❌ | ❌ | ❌ | ❌ |

True **deep immutability** requires recursively freezing, with a guard against cycles:

```javascript
function deepFreeze(obj, seen = new WeakSet()) {
  if (obj === null || typeof obj !== 'object' || seen.has(obj)) return obj;
  seen.add(obj);                                   // cycle guard
  for (const key of Reflect.ownKeys(obj)) {
    deepFreeze(obj[key], seen);                     // freeze children first
  }
  return Object.freeze(obj);                        // then freeze self
}
```

The trade-offs are real. Freezing is **enforced at runtime** with a cost: every property *write* to a frozen object must be checked and rejected, and in strict mode it *throws* — which is actually desirable (loud failure beats silent no-op). Deep-freezing a large object graph is O(n) up front and can prevent some V8 optimizations, so it's typically reserved for **development-mode invariants** (catch accidental mutation of constants/config/Redux state during dev) and stripped or skipped in production hot paths. For *application-level* immutability at scale, libraries like **Immer** (copy-on-write via Proxies — you "mutate" a draft and get a new frozen tree) or **Immutable.js** (persistent data structures with structural sharing) are preferred because they give immutability *guarantees* plus *efficient updates* (sharing unchanged subtrees) rather than freezing everything. The senior synthesis: `freeze`/`seal` are runtime *enforcement* tools, best used to assert "this must not change" during development and for genuinely constant data (lookup tables, enums via `Object.freeze`); for managing evolving immutable state, reach for copy-on-write/persistent structures so you get immutability without the cost of re-freezing huge graphs on every update — and never forget that all three built-ins are shallow, which is the single most common immutability bug.

#### Q79. [Practical] How do polyfills, transpilation, and `core-js`/`@babel/preset-env` with browserslist actually decide what code ships? Walk through the pipeline.

Shipping modern JavaScript to a mix of old and new browsers is a *build-time targeting* problem, and the tooling answers it by separating two orthogonal concerns: **syntax** (new grammar like optional chaining, class fields, arrow functions) must be **transpiled** (rewritten to equivalent older syntax) because old engines throw a `SyntaxError` at parse time — *before any polyfill could run*; **APIs** (new methods/globals like `Array.prototype.flat`, `Promise`, `Object.fromEntries`) must be **polyfilled** (a runtime shim that defines the missing function). You cannot polyfill syntax, and you cannot transpile away a missing API — this distinction drives the whole pipeline.

The single source of truth for "what is old" is **`browserslist`** (in `package.json` or `.browserslistrc`), a declarative query of target environments (`"> 0.5%, last 2 versions, not dead"`). `@babel/preset-env` reads this query, looks up each language feature in compatibility data (`compat-table`/`caniuse`), and includes **only** the syntax transforms and **only** the `core-js` polyfills that your *targets* actually lack — so tightening your browserslist (dropping IE11) automatically shrinks the bundle by removing now-unnecessary transforms and shims.

```jsonc
// .browserslistrc — drives BOTH transpilation and polyfilling
> 0.5%
last 2 versions
not dead
// NOT IE 11  ← removing this can cut significant transform/polyfill weight
```

```javascript
// babel.config.js
module.exports = {
  presets: [['@babel/preset-env', {
    useBuiltIns: 'usage',   // inject ONLY the core-js polyfills each file actually uses
    corejs: '3.37',         // pin core-js version so the right shims are available
  }]],
};
```

The `useBuiltIns` setting is the crux. `'entry'` requires a single `import 'core-js'` and includes all polyfills your targets need (broad, simpler, larger). `'usage'` is smarter: Babel analyzes each file and injects *only* the specific polyfills for features it sees used (`[].flat()` → import the `flat` shim), giving the smallest bundle but depending on accurate static analysis. A third tier, **`@babel/plugin-transform-runtime` with `core-js@3`**, provides *non-global* (sandboxed) polyfills — essential for **libraries**, because a library must not pollute the global `Array.prototype` of the host app (that's the monkey-patching hazard); it imports private helpers instead.

The modern refinement is **differential serving / `<script type="module">`**: ship a lean ES-module bundle (minimal transpilation, few polyfills) to modern browsers and a transpiled+polyfilled `nomodule` bundle to legacy ones, so modern users don't pay for old-browser support. The senior guidance: **set browserslist deliberately** (it's the lever for the entire size/compat trade-off, and shared by Babel, Autoprefixer, and bundlers), prefer `useBuiltIns: 'usage'` for apps and `transform-runtime` for libraries, **pin the `core-js` version**, audit the output with a bundle analyzer, and revisit the targets periodically — many teams ship hundreds of KB of dead transforms/polyfills for browsers no one uses anymore. The meta-point: "what ships" is decided by your *targets*, not the language version — and the most impactful performance win is often simply dropping a long-dead browser from browserslist.

#### Q80. [Theory] Explain how `requestAnimationFrame`, `requestIdleCallback`, microtasks, and `setTimeout` relate to the browser's rendering pipeline (frames, paint, the 16.6 ms budget).

The browser runs a **rendering loop** targeting the display's refresh rate — typically 60 Hz, giving a **~16.6 ms budget per frame** (8.3 ms at 120 Hz). Within each frame the browser must run JS tasks, then **style → layout → paint → composite**. Understanding *where* each scheduling API runs relative to this pipeline is what lets you write jank-free animations and avoid layout thrashing. The ordering within a turn is: run a task → **drain all microtasks** → (if it's time to render) run **`requestAnimationFrame` callbacks** → recalculate style/layout → paint → composite → possibly run **`requestIdleCallback`** if idle time remains before the next frame.

```
┌─ one frame (~16.6 ms) ─────────────────────────────────────────────┐
│ [task: timer/event] → drain microtasks → rAF callbacks →            │
│ style → layout → paint → composite → [requestIdleCallback if idle]  │
└─────────────────────────────────────────────────────────────────────┘
```

**`requestAnimationFrame(cb)`** runs `cb` **right before the next paint**, which is exactly where you want to mutate the DOM/styles for animation: changes are batched into the upcoming frame, and the callback receives a high-resolution timestamp for time-based motion. Animating with `setTimeout`/`setInterval` instead is wrong because timers aren't synchronized to the refresh rate — they fire at arbitrary points, causing dropped or doubled frames (visible stutter) and continuing to run in background tabs (wasting battery), whereas `rAF` is throttled/paused when the tab is hidden.

```javascript
function animate(ts) {
  el.style.transform = `translateX(${(ts / 10) % 300}px)`; // write inside rAF
  requestAnimationFrame(animate);     // schedule next frame; auto-pauses when hidden
}
requestAnimationFrame(animate);
```

**`requestIdleCallback(cb, { timeout })`** runs low-priority work during the *leftover* time at the end of a frame (or when the browser is idle), passing a `deadline` object whose `timeRemaining()` tells you how much idle budget is left — ideal for non-urgent work like prefetching, analytics, or processing a backlog in chunks without stealing time from rendering/input. **Microtasks** (Promises) drain *fully* after every task and before rendering, so an unbounded microtask chain **starves the render step** — the page freezes even though "nothing is blocking." **`setTimeout(fn, 0)`** is a macrotask clamped to a ~4 ms minimum (after nesting) and runs *between* frames at the loop's discretion, not aligned to paint.

The practical rules: animate visual properties inside `rAF` (and prefer compositor-only properties — `transform`/`opacity` — that skip layout/paint); batch DOM **reads then writes** to avoid *layout thrashing* (interleaving `el.offsetHeight` reads with style writes forces synchronous re-layout every iteration); offload non-urgent work to `requestIdleCallback` (or `scheduler.postTask` with a `background` priority); keep each task under ~50 ms (chunk/yield) so you don't blow the frame budget and tank **INP**; and never rely on `setTimeout` for smooth animation. The unifying mental model: the main thread serializes *everything* — JS, microtasks, layout, paint — so responsiveness is a budgeting problem, and these APIs are the tools for placing work in the right slot of the frame.

#### Q81. [Coding] Implement a function that flattens a deeply nested array to a specified depth without using `Array.prototype.flat`, both recursively and iteratively. Discuss stack-overflow risk.

Flattening is a classic that tests recursion, iteration, and awareness of call-stack limits on deep inputs. The recursive solution is the most readable: for each element, if it's an array and we have remaining depth, recurse and spread; otherwise keep the element.

```javascript
function flattenRecursive(arr, depth = 1) {
  return arr.reduce((acc, item) =>
    Array.isArray(item) && depth > 0
      ? acc.concat(flattenRecursive(item, depth - 1))   // recurse with one less depth
      : (acc.push(item), acc),
    []);
}
flattenRecursive([1, [2, [3, [4]]]], Infinity); // [1, 2, 3, 4]
flattenRecursive([1, [2, [3, [4]]]], 1);        // [1, 2, [3, [4]]]
```

The recursive version is elegant but has a **stack-overflow risk**: each level of array nesting adds a frame, so an array nested tens of thousands deep (`[[[[...]]]]`) throws `RangeError: Maximum call stack size exceeded` — JS engines cap the call stack at roughly 10k–15k frames and do **not** do tail-call optimization in practice (despite the spec permitting it, only Safari ever shipped it). For adversarial or machine-generated input, an **iterative** version with an explicit stack avoids the engine call stack entirely:

```javascript
function flattenIterative(arr) {              // full flatten (depth = Infinity)
  const stack = [...arr];
  const result = [];
  while (stack.length) {
    const next = stack.pop();
    if (Array.isArray(next)) stack.push(...next);  // push children back for processing
    else result.push(next);
  }
  return result.reverse();                    // pop reverses order → reverse at end
}
```

```
Recursive:  depth N nesting → N stack frames → RangeError on pathological input
Iterative:  uses a heap-allocated array as the stack → bounded only by memory
```

The trade-offs: the recursive form is clearer and fine for normal data (nesting is usually shallow), and `concat`-based accumulation is O(n²) in the worst case (each `concat` copies) so a `push`-based or generator approach is better for large arrays. The iterative form trades a little readability and an extra `reverse()` for **safety on unbounded depth** and no recursion limit. For depth-limited iterative flattening you'd track depth alongside each element on the stack (push `[item, remainingDepth]` tuples). **Time:** O(n) in total elements for both; **Space:** O(n) output plus O(depth) call stack (recursive) or O(n) explicit stack (iterative). The senior point: prefer the built-in `Array.prototype.flat(depth)` in real code (it's optimized and correct), reach for the **iterative** version specifically when input depth is untrusted or could be enormous (parsing, user uploads, generated data), and recognize call-stack depth as a real, exploitable limit in recursive algorithms — the same reasoning applies to recursive deep-clone, tree traversal, and JSON parsing of deeply nested input.

### 🔴 Expert — extended

#### Q82. [Theory] Explain how `eval`, `new Function`, dynamic `import()`, and the `with` statement each interact with scope, performance (JIT deopt), and security (CSP). When is each acceptable?

These four are the language's dynamic-code mechanisms, and each has distinct scope, performance, and security characteristics that a senior must distinguish precisely. **`eval(str)`** executes a string as code **in the current lexical scope** — it can read and write the surrounding local variables, which is exactly what makes it dangerous and deoptimizing: because the engine can't know at compile time what an `eval` will reference or create, it must abandon scope optimizations for the entire containing function (a hard JIT deopt), and the executed code runs with the page's full privileges, making it a prime XSS/code-injection vector. (Strict-mode `eval` at least gets its *own* scope and can't leak new variables into the caller, slightly limiting the damage.)

**`new Function(args, body)`** also compiles a string to a function, but **only in the global scope** — it *cannot* see local variables, which makes it less dangerous than `eval` (no access to surrounding state) and less of a global deopt (it doesn't poison the enclosing function's scope analysis). It's still arbitrary code execution from a string, so it carries the same injection risk if the string is attacker-influenced, and it's blocked by the same CSP directive.

```javascript
const secret = 42;
eval('secret');                 // 42 — reads local scope; deopts the function
new Function('return secret')();// ReferenceError — global scope only, can't see `secret`
new Function('a', 'b', 'return a + b')(1, 2); // 3 — sandboxed from locals
```

Both `eval` and `new Function` (and string-form `setTimeout('code')`) are blocked by a **Content-Security-Policy** without `'unsafe-eval'` — a `script-src` CSP that omits `'unsafe-eval'` makes them throw `EvalError`, which is a deliberate hardening many production apps enable. By contrast, **dynamic `import(specifier)`** is *completely different*: it's not "code from a string" — it loads an actual **module** by URL asynchronously, returning a Promise of the module namespace. It runs through the normal, statically-trusted module loader (subject to `script-src` for the URL, not `'unsafe-eval'`), is the standard mechanism for **code-splitting / lazy-loading**, and has none of the scope-injection or deopt problems — it's a fully legitimate, recommended tool.

```javascript
// Legitimate: lazy-load a heavy feature only when needed (code-splitting)
button.addEventListener('click', async () => {
  const { renderChart } = await import('./chart.js'); // network fetch + module eval
  renderChart(data);
});
```

**`with(obj){}`** (covered earlier as an anti-pattern) is the fourth: it injects an object into the scope chain, making the function un-analyzable and forcing deopt, and is outright **forbidden in strict mode/ESM**. The decision framework: **never** use `eval`/`new Function`/`with` on any data that could be influenced by users or third parties (the XSS surface is total). `eval` is essentially never justified in application code; `new Function` has rare *legitimate* uses (high-performance template/expression compilers, JIT-ing a hot computed function *from trusted, developer-authored input* — some chart/spreadsheet/template libraries do this knowingly), and even then it must be gated by a CSP exception you consciously accept. **Dynamic `import()` is the one you should reach for freely** — it's the standard answer to "I need to load code conditionally/lazily" and is safe, async, and bundler-aware. The unifying principle: prefer *static, analyzable* code; if you need dynamism, prefer the **module loader** (`import()`) over string-eval, and treat any string-to-code path as a security boundary requiring trusted input and CSP review.

#### Q83. [Practical] You're told "the app is slow." Describe a complete, metric-driven performance investigation for a JavaScript-heavy web app, from field data to root cause to verification.

"Slow" is meaningless until quantified, so the first move is to **replace the anecdote with metrics** and split the problem into *load* performance vs. *runtime/interaction* performance. Start with **field data (RUM)** — real users on real devices/networks — via the **Core Web Vitals**: **LCP** (Largest Contentful Paint — how fast the main content renders), **INP** (Interaction to Next Paint — responsiveness to clicks/typing, the successor to FID), and **CLS** (Cumulative Layout Shift — visual stability). Field data (Chrome UX Report, `web-vitals` library, your RUM provider) tells you *which* metric is bad, *for which* segment (slow 3G? low-end Android? a specific route?), which prevents optimizing the wrong thing on your fast dev machine.

```javascript
import { onLCP, onINP, onCLS } from 'web-vitals';
onLCP(sendToAnalytics); onINP(sendToAnalytics); onCLS(sendToAnalytics);
// p75 across real users is the target Google uses — not your localhost number.
```

Then **reproduce in the lab** with the matching condition: DevTools **Performance** panel with **CPU throttling (4–6×)** and **network throttling** to emulate the affected segment, plus a **Lighthouse** run for a prioritized diagnostic. Now diagnose by metric. **Bad LCP** → usually *load*: oversized JS bundles blocking the main thread, render-blocking resources, slow server TTFB, unoptimized images, or the LCP element loaded late — investigate with the network waterfall and a **bundle analyzer** to find the heavy modules. **Bad INP** → usually *runtime*: **long tasks (>50 ms)** on the main thread during interaction — open the Performance flame chart, find the long task, and read what it's doing (a giant synchronous render, a non-virtualized list, an expensive synchronous handler, layout thrashing, or excessive React re-renders). **Bad CLS** → images/ads/fonts without reserved space, or content injected above existing content.

```
Field (RUM) → which metric/segment is bad
   ↓
Lab repro (throttled Performance + Lighthouse) → reproduce it
   ↓
Diagnose: LCP→load (bundle/network/server) | INP→long tasks (main thread) | CLS→layout
   ↓
Fix → measure again in lab → ship behind a flag → confirm in field RUM → gate in CI
```

For a JS-heavy app the usual root causes and fixes: **ship less JS** (code-split with dynamic `import()`, lazy-load routes/components, tree-shake, drop dead browserslist targets that bloat polyfills); **break up long tasks** (chunk/yield with `scheduler.yield`, offload CPU work to Web Workers, virtualize long lists, debounce/throttle handlers); **fix layout thrashing** (batch reads then writes, use `transform`/`opacity`, `content-visibility`); **reduce re-renders** (memoization, stable references). The discipline that separates senior work: **measure before and after each change** (a fix you can't measure is a guess), **verify in the field** not just the lab (your fix must move the p75 RUM metric for real users), and **prevent regression** by adding a Lighthouse/bundle-size budget gate in CI so the next feature can't silently re-bloat the bundle. The meta-lesson: performance work is a measurement loop — quantify with field data, reproduce under matching constraints, attribute to a specific metric and a specific long task or byte cost, fix the root, and prove the win with the same metric you started from.

#### Q84. [Theory] How does the structured clone algorithm work, what can and cannot it clone, and how does it relate to `postMessage`, `structuredClone()`, and IndexedDB?

The **structured clone algorithm** is a built-in, spec-defined deep-copy mechanism that the platform uses wherever it must duplicate a JavaScript value *across a boundary* without sharing references. It's exposed directly as **`structuredClone(value)`** (2022) and used implicitly by **`postMessage`** (to Workers, iframes, other windows), **IndexedDB** (to persist values), the **History API** (`pushState` state), the Cache API, and `BroadcastChannel`. Unlike `JSON.parse(JSON.stringify(x))`, it handles a much richer set of types and supports **cycles**, which is why it's the correct modern deep-clone primitive.

It **can** clone: primitives (including `BigInt`), `Array`, plain `Object`, `Date`, `RegExp`, `Map`, `Set`, `ArrayBuffer`, `TypedArray`/`DataView`, `Blob`, `File`, `ImageData`, and crucially **circular references** (it maintains an internal memory of already-cloned objects, like a `WeakMap`-based deep clone). It **cannot** clone: **functions** (and class methods), **DOM nodes**, **`Error`** objects' full fidelity historically (now partially supported), accessor **getters/setters** (it copies the *resulting value*, not the accessor), the object's **prototype** (a cloned class instance becomes a plain object — it loses its class identity), and anything holding a non-cloneable member — attempting these throws a **`DataCloneError`**.

```javascript
const original = { d: new Date(), m: new Map([['k', 1]]), buf: new Uint8Array([1,2]) };
original.self = original;                    // a cycle
const copy = structuredClone(original);
copy.self === copy;                          // true — cycle preserved, fully independent
copy.m.set('k', 99);
original.m.get('k');                         // 1 — Map deep-cloned

structuredClone({ fn: () => {} });           // DataCloneError — functions can't clone
structuredClone(document.body);              // DataCloneError — DOM nodes can't clone
class P { constructor(){ this.x = 1; } greet(){} }
structuredClone(new P()) instanceof P;       // false — prototype/class identity lost
```

The relationship to `postMessage` reveals the design intent: when you `postMessage` data to a Worker, the value is **structured-cloned** into the other thread's heap — the two threads do **not** share the object (no shared mutable state, preserving the isolation that makes the threading model safe). For large payloads this *copy* can be expensive, which is why `postMessage` also supports **transferables**: passing `[arrayBuffer]` as the transfer list **moves** ownership of the buffer's memory to the receiver in O(1) (the sender's reference becomes detached/unusable) — zero-copy, but the sender loses access. `SharedArrayBuffer` is the third option (genuinely shared memory, not cloned).

```javascript
worker.postMessage({ pixels: buffer }, [buffer]); // TRANSFER buffer — zero-copy move
// buffer is now detached in this thread (byteLength === 0); the worker owns it.
```

The senior synthesis: use **`structuredClone()`** as the default deep-clone (it beats the JSON trick on Dates, Maps, typed arrays, and cycles), but know its limits — it won't preserve class prototypes or clone functions/DOM, so for class instances you implement a custom `clone()`/`fromJSON`, and for functions/closures there's no copy at all. Understand that *the same algorithm* governs what you can send across threads and store in IndexedDB, so "can I `structuredClone` this?" answers "can I `postMessage`/persist this?" too. And reach for **transferables** (or `SharedArrayBuffer` + `Atomics`) when the clone cost of large binary data across `postMessage` dominates — the trade-off is copy-with-independence (clone) vs. move-with-loss (transfer) vs. share-with-coordination (SAB).

#### Q85. [Theory] Explain the security model and pitfalls of `postMessage` and cross-window/iframe/Worker communication. What must every receiver check, and why?

`postMessage` is the sanctioned channel for communication across **origin/realm boundaries** — between a page and an iframe, an opened window (`window.open`), a parent/child frame, a Web Worker, or a `BroadcastChannel`. Because it deliberately *crosses* the Same-Origin Policy (its whole purpose is controlled cross-origin messaging), it is a **security boundary**, and the two most common, most dangerous mistakes are on opposite ends: sending too broadly and trusting incoming messages blindly.

On the **sending** side, `targetWindow.postMessage(data, targetOrigin)` takes a **`targetOrigin`** argument that restricts *which* origin is allowed to receive the message. Using `'*'` (wildcard) means *any* origin currently in that frame can read your message — if the frame navigated to an attacker's page, you just leaked the data to them. **Always specify the exact expected origin** (`'https://app.example.com'`) so the browser refuses delivery if the target isn't who you expect.

```javascript
// SENDER: never '*' for sensitive data — pin the exact target origin
iframe.contentWindow.postMessage({ token }, 'https://trusted.example.com');
```

On the **receiving** side — the more frequently exploited end — every handler **must validate `event.origin`** (and often `event.source`) before acting on the message, because *any* page can `postMessage` to your window. A handler that trusts arbitrary incoming messages is a direct vector for XSS, auth bypass, or data exfiltration: an attacker embeds your page in an iframe (or opens it) and posts crafted messages that your handler dutifully executes.

```javascript
// RECEIVER: the three mandatory checks
window.addEventListener('message', (event) => {
  if (event.origin !== 'https://trusted.example.com') return; // 1. WHO sent it
  if (event.source !== expectedFrame) return;                  // 2. exact frame (optional)
  if (!isValidShape(event.data)) return;                       // 3. WHAT they sent
  handle(event.data);                                          // only now act
});
```

The three mandatory receiver checks are: (1) **origin** — reject any `event.origin` that isn't your explicit allow-list (never accept `'*'` semantics on receipt); (2) **structure/content** — validate the message shape and never feed `event.data` into `eval`, `innerHTML`, `Function`, or a privileged action without sanitization (treat it as fully untrusted input, exactly like a network payload); (3) optionally **source** — verify `event.source` is the specific window/port you expect, defending against a different frame spoofing messages. Additional pitfalls a senior raises: messages are **structured-cloned**, so you can't pass functions and the receiver's objects have the *receiver's* prototypes (mitigating some prototype-identity attacks but enabling prototype-*pollution* if you deep-merge `event.data`); **`MessageChannel`/`MessagePort`** gives a private two-ended channel that avoids the broadcast-to-window problem entirely (preferred for sustained communication); and for clickjacking/embedding control you pair this with `X-Frame-Options`/`frame-ancestors` CSP and (for `SharedArrayBuffer`) cross-origin isolation headers. The meta-principle: `postMessage` is a *trust boundary*, not a function call — pin the target origin when sending, and on receipt verify **who** sent it and **what** they sent before doing anything with the data, the same discipline you'd apply to any untrusted network input.

#### Q86. [Coding] Implement an async retry-with-backoff wrapper that distinguishes retryable from non-retryable errors and honors an `AbortSignal`. Contrast with a circuit breaker.

A production retry helper must do more than loop — it must **classify errors** (retrying a `400 Bad Request` or a validation error is pointless and can be harmful), **back off exponentially with jitter** (to avoid synchronized retry storms), **respect cancellation** (an `AbortSignal` so a user navigation or timeout stops the retry chain), and ideally **honor `Retry-After`** on `429`/`503`. Retrying indiscriminately turns a transient blip into a self-inflicted load amplifier.

```javascript
const sleep = (ms, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) return reject(signal.reason);
  const t = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => { clearTimeout(t); reject(signal.reason); },
                           { once: true });
});

async function retry(task, {
  retries = 4, baseDelay = 200, maxDelay = 10_000, signal,
  isRetryable = (e) => e.retryable !== false,   // pluggable classification
} = {}) {
  let attempt = 0;
  for (;;) {
    signal?.throwIfAborted();                    // bail before each attempt
    try {
      return await task({ attempt, signal });
    } catch (err) {
      if (++attempt > retries || !isRetryable(err)) throw err; // give up / non-retryable
      const backoff = Math.min(maxDelay, baseDelay * 2 ** (attempt - 1));
      const jitter = Math.random() * backoff;    // full jitter — decorrelate clients
      const wait = err.retryAfterMs ?? jitter;    // honor server Retry-After if present
      await sleep(wait, signal);
    }
  }
}

// classification example for HTTP
const isRetryable = (e) => e.status >= 500 || e.status === 429 || e.code === 'ECONNRESET';
```

The **classification** is the part juniors miss: only retry *transient* failures — network errors (`ECONNRESET`, `ETIMEDOUT`), `5xx`, and `429` (rate-limited, with `Retry-After`) — and **never** retry deterministic failures (`4xx` other than 429: bad request, unauthorized, not found) because the same input will fail identically while you waste time and load. **Jitter** matters because if a downstream service blips and 10,000 clients all retry at exactly `200ms, 400ms, 800ms`, they hammer the recovering service in synchronized waves (the *thundering herd*); randomizing each client's delay spreads the load. The **`AbortSignal`** threading ensures that when the caller cancels (component unmount, route change, parent timeout), the pending `sleep` and the loop terminate immediately rather than firing a stale request later.

A **circuit breaker** is the complementary, *higher-level* pattern, and the distinction is important. Retry handles *individual* transient failures; a circuit breaker prevents *systemic* overload by tracking the failure rate across many calls and, once a threshold is crossed, **"opening"** — failing fast (rejecting immediately without even attempting the call) for a cooldown period, then allowing a few **"half-open"** trial requests to test recovery before **"closing"** again.

```
Retry:           one call fails → wait/backoff → try again (bounded attempts)
Circuit breaker: many calls failing → OPEN (fail fast, don't call) → cooldown →
                 HALF-OPEN (probe) → success? CLOSE : reopen
```

The two compose: retry the *occasional* transient error, but wrap the dependency in a circuit breaker so that when it's *hard down*, you stop retrying entirely and fail fast — otherwise your retries *add* load to a service that's already drowning, turning a partial outage into a total one (a classic cascading-failure incident). The senior framing: retries improve success rate for *independent transient* faults; circuit breakers protect *system stability* under *sustained* faults; combine them with timeouts (`AbortSignal.timeout`), bulkheads (concurrency limits via the pool pattern), and idempotency keys (so a retried write doesn't double-charge). Blind retries without classification, jitter, a cap, and a circuit breaker are a leading cause of self-inflicted outages.

#### Q87. [Theory] What are the failure modes of floating-point and integer arithmetic in real systems (money, IDs, bitwise ops), and how do you choose representations to avoid them?

Beyond the textbook `0.1 + 0.2`, floating-point and integer limits cause concrete production incidents, and the fix is always *choosing the right representation for the domain*. The headline category is **money**: representing currency as a `Number` (float) means accumulation errors compound (summing thousands of transactions drifts), rounding is ambiguous, and `toFixed` rounds inconsistently (banker's rounding isn't applied). The standard fixes are **integer minor units** (store cents as integers: `$19.99` → `1999`, do integer arithmetic, format only at display), a **decimal library** (`decimal.js`, `big.js`) for exact decimal math, or **`BigInt`** for large exact integer counts (sub-cent fixed-point). Never sum floats for money.

```javascript
0.1 + 0.2;                       // 0.30000000000000004
(0.1 + 0.2).toFixed(20);         // "0.30000000000000004441" — the real stored value
// Money done right: integer cents
const cents = [1999, 500, 1234].reduce((a, b) => a + b, 0); // exact integer sum
const display = (cents / 100).toFixed(2);                    // "37.33" only for display
```

The second category is **large integer IDs**: `Number` is exact only up to `Number.MAX_SAFE_INTEGER` (2^53 − 1 ≈ 9×10^15). Database BIGINT primary keys, Twitter/X snowflake IDs, and 64-bit timestamps **exceed** this, so receiving them as JSON numbers **silently corrupts** them — `JSON.parse('{"id":9007199254740993}')` rounds to `9007199254740992`, two different records collapse to one ID, and you get data-integrity bugs that are invisible until a collision. The fix: **transmit and store large IDs as strings** (or `BigInt`), and never let a 64-bit identifier pass through a JS `Number`. Note `JSON.parse` has no `BigInt` support, so a numeric reviver or a JSON-bigint library is needed if the wire format uses raw numbers.

```javascript
JSON.parse('{"id": 9007199254740993}').id;  // 9007199254740992 — CORRUPTED
// Safe: server sends "id": "9007199254740993" (string), parse to BigInt if needed
BigInt('9007199254740993');                  // 9007199254740993n — exact
```

The third category is **bitwise operations**, which silently coerce operands to **32-bit signed integers** via `ToInt32`. So `<<`, `>>`, `|`, `&`, `^` on values above 2^31 wrap/overflow unexpectedly: `2 ** 31 | 0` is `-2147483648` (sign flip), and using `| 0` as a "fast floor" breaks for numbers ≥ 2^31. Bit manipulation on flags/masks beyond 32 bits, or on large integers, requires `BigInt` (which supports bitwise ops at arbitrary width) or a different approach. The unsigned-right-shift `>>>` is the one that produces an unsigned 32-bit result, a frequent source of confusion.

```javascript
(2 ** 31) | 0;          // -2147483648 — bitwise ops are 32-bit SIGNED
0xFFFFFFFF | 0;         // -1
(2n ** 40n) & 0xFFn;    // BigInt bitwise — arbitrary width, no overflow
```

| Domain | Wrong representation | Failure | Right representation |
|---|---|---|---|
| Money | `Number` (float) | accumulation/rounding drift | integer minor units / decimal lib |
| Large IDs (64-bit) | `Number` | silent rounding above 2^53 → collisions | string or `BigInt`, never raw JSON number |
| Bit flags > 32 bits | `Number` bitwise | 32-bit signed overflow/wrap | `BigInt` bitwise |
| General fractions | exact-equality compare | `0.1+0.2 !== 0.3` | epsilon tolerance (scaled) |

The senior principle: **arithmetic correctness is a data-modeling decision**, made *before* the math, not patched after. Ask "what is the exact domain — counts, currency, identifiers, fractions, bit masks?" and pick a representation whose precision covers the full range: integers (or `BigInt`) for exact whole quantities and IDs, fixed-point/decimal for money, floats only where small relative error is acceptable (physics, graphics, statistics) and always compared with a scaled epsilon. The recurring real-world incident — corrupted 64-bit IDs from JSON and drifting money totals from float sums — is almost always traced to using the default `Number` where the domain demanded exact integer or decimal semantics.

#### Q88. [Practical] How do you architect feature detection, progressive enhancement, and graceful degradation in JavaScript so a single codebase serves a wide range of clients?

The goal is one codebase that delivers a working baseline to *every* client and a richer experience to *capable* ones — without UA sniffing (brittle, spoofable, perpetually outdated) and without shipping breakage to older runtimes. The foundational technique is **feature detection**: test for the *capability* you need at runtime, not the browser identity, then branch to an enhancement or a fallback. This is robust because it asks the only question that matters ("does this API exist *here, now*?") and degrades automatically as the landscape changes.

```javascript
// Detect the capability, not the browser:
if ('IntersectionObserver' in window) {
  useObserverBasedLazyLoad();              // enhanced path
} else {
  useScrollHandlerLazyLoad();              // fallback path (or a polyfill)
}
const supportsWebP = await checkWebP();    // probe, then choose image format
const canShare = typeof navigator.share === 'function'; // Web Share API
```

**Progressive enhancement** (build up) and **graceful degradation** (build down) are two directions toward the same robustness. Progressive enhancement starts from a **functional baseline that works with minimal/no JS** — semantic HTML, a real `<form>` that submits to the server, server-rendered content — and *layers on* JS-driven improvements (client-side validation, instant updates, animations) only where supported. This is why a well-built form still submits if JS fails to load, a flaky network drops the bundle, or the user is on a restrictive environment: the core task never depended on the enhancement. Graceful degradation starts from the full-featured version and ensures it *falls back* sanely when a capability is missing.

```javascript
// Progressive enhancement: the link/form works without JS; JS just improves it.
// HTML:  <a href="/search?q=...">Search</a>  (works server-side)
document.querySelector('#search')?.addEventListener('submit', (e) => {
  if (!window.fetch) return;               // no fetch? let the native form submit
  e.preventDefault();                      // enhance: do it client-side instead
  doInstantSearch();
});
```

The architectural layers that make this manageable at scale: (1) **differential serving** — ship a modern ES-module bundle to capable browsers and a transpiled/polyfilled bundle to legacy ones (`<script type="module">` / `nomodule`), so modern users don't carry old-browser weight (ties back to browserslist/preset-env); (2) **lazy enhancement** via dynamic `import()` — load the heavy enhanced module only after confirming support and need; (3) **polyfill *on demand*** — detect the gap and conditionally load a shim (`if (!window.fetch) await import('whatwg-fetch')`) rather than bundling polyfills everyone downloads; (4) **CSS-first** for visual enhancements (`@supports`, feature queries) so layout degrades without JS at all.

```
Baseline (HTML/CSS, server-rendered, works everywhere)
   └─ + JS detected?        → client interactivity
        └─ + IntersectionObserver? → lazy-load images (else scroll fallback)
             └─ + WebGL/Worker?    → rich enhanced features (else simpler UI)
```

The senior synthesis and trade-offs: feature-detect capabilities (`'x' in obj`, `typeof fn === 'function'`, probe-and-fallback) and **never** UA-sniff except as a last resort for known-unfixable bugs; treat JS as an *enhancement* over a server-functional baseline so a dropped bundle or unsupported API never leaves users stranded (especially critical for forms, navigation, and content); use differential serving + on-demand polyfills so capability variance doesn't bloat the common case; and define a **support matrix** (your browserslist) that draws an explicit line between "enhanced," "baseline-functional," and "unsupported," then test all three. The meta-point: resilience comes from *layering*, not from detecting devices — design so the most fragile parts (JS, new APIs, the network) are the *enhancements*, and the essential task survives their absence.

#### Q89. [Theory] Explain the Web Crypto API and the security pitfalls of doing cryptography in JavaScript (`Math.random`, timing, key storage). What should client-side JS never do?

Cryptography in the browser must use the **Web Crypto API** (`crypto.subtle`) — a native, audited, constant-time implementation of standard primitives (AES-GCM, RSA-OAEP, ECDSA/ECDH, HMAC, SHA-2, PBKDF2, HKDF) — and **never** a hand-rolled or pure-JS crypto library, because JS-implemented crypto is slow, hard to make constant-time, and easy to get subtly, catastrophically wrong. The API is **async** (Promise-based, so the heavy math doesn't block the main thread) and works with `CryptoKey` objects and `ArrayBuffer`s rather than strings.

The single most common pitfall is **using `Math.random()` for anything security-sensitive**. `Math.random()` is a *non-cryptographic* PRNG (typically xorshift128+ in V8): it's fast and well-distributed for simulations/shuffles, but its output is **predictable** — given enough samples an attacker can recover its internal state and predict future values, and it's seeded non-securely. Using it for tokens, session IDs, password-reset codes, nonces, or salts is a real vulnerability. The correct source is **`crypto.getRandomValues()`** (cryptographically secure) or **`crypto.randomUUID()`** for IDs.

```javascript
// WRONG — predictable, recoverable internal state:
const token = Math.random().toString(36).slice(2);          // NEVER for security

// RIGHT — CSPRNG:
const bytes = crypto.getRandomValues(new Uint8Array(32));    // 256 bits of secure randomness
const id = crypto.randomUUID();                              // RFC 4122 v4, CSPRNG-backed

// Hashing/keys via subtle (async, native, constant-time):
const digest = await crypto.subtle.digest('SHA-256', data);
const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 },
                                            false, ['encrypt', 'decrypt']);
```

The deeper, architectural pitfalls of *client-side* crypto: (1) **timing attacks** — comparing secrets with `===` or `==` short-circuits on the first differing byte, leaking length/content via timing; security comparisons must be **constant-time** (compare full length regardless), and you generally shouldn't be doing secret comparison in client JS at all. (2) **Key storage** — the browser has no truly secure key vault for JS; `localStorage` is XSS-readable, and even `CryptoKey` objects (which can be marked **non-extractable** so the raw bytes never enter JS) live in a process an XSS payload can *use* even if it can't *exfiltrate* the bytes. (3) **The fundamental trust problem** — the *server delivers the JS*, so a compromised server (or a malicious dependency, or an injected script) can swap the crypto code; client-side crypto cannot protect against an attacker who controls the page. This is why **end-to-end encryption in a browser is inherently weaker** than in a native app with code signing.

What client-side JS should **never** do: implement its own ciphers/hashes; use `Math.random` for tokens, salts, nonces, or IDs; store long-lived secrets or private keys where XSS can reach them (no secrets in `localStorage`); treat client-side validation/encryption as a *security boundary* (it's a UX nicety — the server must re-validate and is the real trust boundary); or roll its own password hashing (use the server with bcrypt/argon2 — `PBKDF2` in Web Crypto is for key derivation, not a substitute for server-side password storage). The senior synthesis: use `crypto.subtle` + `getRandomValues`/`randomUUID` for the legitimate client-side cases (encrypting data the *user* controls, E2EE messaging with appropriate threat-model caveats, generating secure nonces, hashing for integrity), mark keys non-extractable, never compare secrets non-constant-time, and remember that **the server is the trust boundary** — anything the client "secures" can be undone by whoever controls the delivered code, so secrets, authorization, and final validation always live server-side.

#### Q90. [Practical] How do you set up and reason about source maps, error monitoring, and release tracking so production JavaScript errors are actually debuggable at scale?

Catching production errors well is an *architecture* problem, not just a try/catch problem, because minified bundles, async stacks, and thousands of users make raw errors useless without infrastructure. The foundation is a **global error pipeline** that captures *both* error channels: synchronous errors via `window.onerror`/`addEventListener('error')` and **unhandled Promise rejections** via `addEventListener('unhandledrejection')` (missing the latter is the #1 reason async failures vanish silently). These feed an error-monitoring service (Sentry, Rollbar, Bugsnag, or a homegrown endpoint) with the error, stack, URL, user/session context, and breadcrumbs.

```javascript
window.addEventListener('error', (e) => report(e.error ?? e.message, e));
window.addEventListener('unhandledrejection', (e) => report(e.reason, e)); // CRITICAL
// In frameworks also wire error boundaries (React) / errorHandler (Vue/Angular).
```

The second pillar is **source-map symbolication**. Production ships minified code, so a raw stack is `app.min.js:1:48213` — meaningless. You generate source maps at build time and **upload them privately to the error tracker** (rather than serving them on the public CDN, which would expose your source), so when an error arrives the service maps the minified frame back to `UserList.tsx:42:9` with the original code shown. The make-or-break detail is **matching the map to the exact build**: embed a **release version / `debugId`** in the bundle *and* tag the uploaded source maps with the same identifier, so the symbolicator pairs the right map with the right deployed bundle. Mismatched maps produce *confidently wrong* line numbers — worse than no maps.

```bash
# Build with a release id, upload maps privately, then strip them from the CDN:
sentry-cli releases new "$RELEASE"
sentry-cli sourcemaps upload --release "$RELEASE" ./dist     # private upload
# deploy ./dist WITHOUT the .map files (or behind auth) so source isn't public
```

The third pillar is **release tracking and grouping**: tag every event with the **release/commit SHA** and environment so you can see *which deploy introduced a spike* (regression detection), associate errors with the responsible change, and verify a fix actually dropped the error rate after the next release. Good services **fingerprint/group** similar errors so 50,000 occurrences collapse into one issue with a count, trend, affected-user count, and first/last-seen release — turning noise into a prioritizable list. Add **breadcrumbs** (recent user actions, network requests, console logs leading up to the error) and **user/session context** so you can reproduce, plus **release health** (crash-free sessions/users) as the top-line metric.

```
Build: emit source maps + embed releaseId/debugId
   ↓
Deploy: ship minified bundle; upload maps PRIVATELY tagged with releaseId
   ↓
Runtime: global error + unhandledrejection handlers → send {error, stack, release, user, breadcrumbs}
   ↓
Service: symbolicate via matching map → group/fingerprint → trend by release → alert on spikes
   ↓
Fix → new release → confirm error rate drops (regression closed)
```

The senior practices and pitfalls: **capture both channels** (sync + unhandledrejection — and treat an unhandled rejection in Node as fatal/restart, since the process is in an unknown state); **enable `sourcesContent`** in maps so the source text travels with the map (otherwise you get filenames but no code); **ensure every transform stage composes its maps** (TS→Babel→bundler→minifier — a broken chain maps only to an intermediate artifact); **keep maps off the public web** (or behind auth) to avoid leaking proprietary source; **scrub PII** before sending (don't ship tokens/passwords in breadcrumbs or URLs); **sample/rate-limit** high-volume errors so one bug doesn't DOS your own ingest or blow your quota; and **alert on rate, not occurrence** (a baseline of noise is normal; a *spike* tied to a release is the signal). The meta-lesson: a production error is only debuggable if you can (a) see it at all (both channels), (b) read it (build-matched source maps with content), and (c) attribute it (release tracking + grouping) — invest in all three or you'll have dashboards full of `app.min.js:1:0 Script error.` that tell you nothing.

#### Q91. [Theory] Compare the major JavaScript module/loading strategies — bundling vs. native ESM, code-splitting, dynamic import, import maps, and HTTP/2 — and the trade-offs that drive the choice.

How JavaScript reaches the browser has evolved through distinct strategies, each a response to the previous one's limits, and choosing among them is a real architectural decision. **Bundling** (webpack/Rollup/esbuild/Vite) concatenates many modules into a few optimized files: it enables **tree-shaking**, minification, and — historically critical under HTTP/1.1 — avoids the per-request overhead of fetching hundreds of small modules (each blocked by the 6-connection limit). The cost is build complexity and that a small change can invalidate a large bundle's cache. **Native ESM in the browser** (`<script type="module">`, no bundler) loads modules directly via `import`: zero build step, perfect cache granularity (change one module, only it re-downloads), but historically slow for deep dependency graphs because each `import` is a separate request with **request waterfalls** (module A must download before the browser discovers it imports B).

```html
<!-- Native ESM: no build, but a deep import graph = request waterfall -->
<script type="module" src="/app.js"></script>
```

**Code-splitting** is the middle path that dominates production: bundle, but into *multiple* chunks split along **dynamic `import()`** boundaries (per route, per heavy feature), so the initial load ships only what's needed and the rest loads on demand. This directly improves LCP/TTI by shrinking the critical-path JS.

```javascript
// Route-based code-splitting: the dashboard chunk loads only when navigated to
const Dashboard = lazy(() => import('./routes/Dashboard.js'));
// Feature-based: load a heavy editor only on interaction
button.onclick = async () => (await import('./richEditor.js')).mount();
```

**Import maps** (a browser standard) let you use bare specifiers (`import { x } from 'lodash'`) in *native* ESM without a bundler resolving them, by declaring the mapping in HTML — enabling no-build workflows and CDN-based dependency loading, and decoupling specifier from URL (useful for versioning/swapping implementations).

```html
<script type="importmap">
{ "imports": { "lodash": "https://cdn.example.com/lodash-es@4/lodash.js" } }
</script>
```

**HTTP/2 (and HTTP/3)** changed the calculus that made bundling mandatory: with **multiplexing** (many concurrent requests over one connection) and header compression, the per-request penalty that made "hundreds of small files" catastrophic under HTTP/1.1 is greatly reduced — so finer-grained, less-bundled delivery (better cache granularity) became viable. But it didn't make bundling obsolete: tree-shaking, minification, and avoiding deep *waterfalls* (latency, not connection count, is now the enemy) still favor *some* bundling.

| Strategy | Build step | Cache granularity | Tree-shaking | Best for |
|---|---|---|---|---|
| Single bundle | yes | coarse (1 change → big invalidation) | yes | small apps, HTTP/1.1 |
| Code-split bundles | yes | per-chunk | yes | most production apps |
| Native ESM | no | per-module (finest) | no (unless bundled) | small/no-build, prototypes |
| ESM + import maps | no | per-module | no | no-build + CDN deps |

The senior synthesis: for **production apps**, bundle *and* code-split — ship a small critical bundle plus lazy chunks via dynamic `import()` keyed to routes/features, tree-shaken and minified, served over HTTP/2+ with long-cache hashed filenames; this minimizes critical-path bytes (LCP/INP) while keeping cache invalidation localized. Reserve **native ESM/import maps** for prototypes, internal tools, or genuinely no-build scenarios where the deploy simplicity outweighs the waterfall/optimization cost. The trade-off axes to reason about are: **initial bytes on the critical path** (favor splitting), **cache efficiency** (favor finer granularity), **request waterfalls** (favor bundling related modules together so the browser doesn't discover dependencies serially), and **build complexity** (favor native ESM). Modern tools (Vite) blur the line — native ESM in dev for instant HMR, bundled+split for production — which is the pragmatic best-of-both default.

#### Q92. [Theory] Explain prototype pollution in depth: the exact attack mechanics, the sink/source patterns, real-world impact, and the layered defenses.

**Prototype pollution** is a JavaScript-specific vulnerability where an attacker injects properties into **`Object.prototype`** (or another shared prototype), and because *every* ordinary object inherits from it, those injected properties suddenly appear on objects throughout the entire application — including objects the developer never touched. The attack exploits that `__proto__`, `constructor`, and `prototype` are special keys: assigning to `obj.__proto__.x` (or navigating `obj.constructor.prototype`) reaches the *shared* prototype, not the object's own property.

The vulnerable **sink** pattern is any code that takes attacker-controlled *keys* and assigns them into an object via a path — classic culprits are **recursive deep-merge / `extend`**, `lodash.set`-style **path assignment**, and **`JSON.parse`-then-merge** flows where the JSON contains a `__proto__` key:

```javascript
// VULNERABLE deep-merge sink:
function merge(target, source) {
  for (const key in source) {
    if (typeof source[key] === 'object' && source[key] !== null) {
      if (!target[key]) target[key] = {};
      merge(target[key], source[key]);     // recurse — reaches __proto__!
    } else {
      target[key] = source[key];           // assignment into prototype
    }
  }
  return target;
}
// Attacker-controlled JSON (e.g., a request body):
const malicious = JSON.parse('{"__proto__": {"isAdmin": true}}');
merge({}, malicious);
// Now EVERY object is polluted:
({}).isAdmin;                              // true  ← global contamination!
```

The **impact** is severe and varied: (1) **privilege escalation / auth bypass** — a polluted `isAdmin`/`role` default flips authorization checks that read `user.isAdmin` (falling through to the polluted prototype when the own property is absent); (2) **logic corruption** — injected `toString`, `length`, or option defaults break unrelated code application-wide; (3) **RCE in some contexts** — on the server, polluting properties consumed by template engines, `child_process` options, or `require` can escalate to remote code execution (multiple real CVEs in popular libraries — lodash, jQuery `$.extend`, various merge utilities — have done exactly this). Because the contamination is *global and non-local*, the resulting bugs are extremely hard to trace back to the source.

The **layered defenses**:

```javascript
// 1. Prototype-less objects for user-keyed data (no __proto__ to pollute):
const safe = Object.create(null);          // has no prototype at all
// 2. Use Map for arbitrary/user-controlled keys (no prototype key semantics):
const m = new Map(); m.set(userKey, value);
// 3. Block dangerous keys in any merge/assign that touches untrusted input:
const FORBIDDEN = new Set(['__proto__', 'constructor', 'prototype']);
function safeMerge(target, source) {
  for (const key of Object.keys(source)) {
    if (FORBIDDEN.has(key)) continue;       // reject the special keys
    // ... safe recursion ...
  }
}
// 4. Freeze the prototype as a tripwire (defense in depth):
Object.freeze(Object.prototype);           // pollution attempts now throw in strict mode
```

The senior synthesis of layered defense: (1) **don't use plain objects as maps for untrusted keys** — use `Map` (no prototype key footguns) or `Object.create(null)` (no prototype to pollute); (2) **harden every merge/clone/path-set** that ingests external data by rejecting `__proto__`/`constructor`/`prototype` (or use `Object.defineProperty` semantics, or vetted libraries that guard — modern lodash, `secure-json-parse`); (3) **validate input against a schema** (Zod/JSON Schema) so unexpected keys never reach a sink; (4) **`Object.freeze(Object.prototype)`** as a runtime tripwire that converts a silent pollution into a loud throw; (5) **keep dependencies patched** (`npm audit`) since most real exploits come through a vulnerable transitive merge/set utility. The meta-lesson: prototype pollution is the dark side of prototypal inheritance and the property-lookup-falls-through-to-prototype model — any time attacker-controlled *keys* flow into object *assignment*, treat it as a sink, and prefer data structures (`Map`, null-prototype objects) that don't share a mutable global prototype in the first place.

#### Q93. [Practical] How do you choose and configure a JavaScript testing strategy — unit vs integration vs e2e, mocking, async testing, flakiness, and the testing pyramid?

A testing strategy is a *portfolio* decision balancing confidence, speed, and maintenance cost, organized by the **testing pyramid**: many fast **unit tests** (pure functions, single modules, mocked dependencies), fewer **integration tests** (multiple units together — a component with its store, an API client against a mock server), and a small number of **end-to-end (e2e) tests** (the real app in a real browser, full stack). The pyramid shape reflects a fundamental trade-off: unit tests are fast, deterministic, and pinpoint failures but don't prove the pieces *work together*; e2e tests give the highest confidence (they exercise what users actually do) but are slow, expensive to maintain, and **flaky** (timing, network, environment). Inverting the pyramid — lots of e2e, few unit — produces a slow, brittle suite that's expensive to run and trust.

```
        ╱╲        e2e (Playwright/Cypress): few, slow, high-confidence, flaky
       ╱──╲       integration (Testing Library + MSW): some, medium
      ╱────╲      unit (Vitest/Jest): many, fast, deterministic, precise
```

The key choices: **what to mock**. Mock at *boundaries you don't own or that are slow/non-deterministic* — the network (intercept `fetch` with **MSW**, which mocks at the network layer so your code under test is unchanged), the clock (fake timers for `setTimeout`/debounce/`Date`), and randomness (`crypto`/`Math.random`). **Don't over-mock**: mocking your own internal modules couples tests to implementation and lets integration bugs slip through — prefer testing real collaborators together (the "test behavior, not implementation" principle, embodied by Testing Library's "query by what the user sees" approach). For components, render and assert on **user-visible behavior** (roles, text, interactions) rather than internal state, so refactors that preserve behavior don't break tests.

```javascript
// Async testing done right — await the assertion, fake the clock for timers:
import { vi, test, expect } from 'vitest';
test('debounced search fires once after typing stops', async () => {
  vi.useFakeTimers();
  const search = vi.fn();
  const debounced = debounce(search, 300);
  debounced('a'); debounced('ab'); debounced('abc');
  vi.advanceTimersByTime(300);              // control time deterministically
  expect(search).toHaveBeenCalledTimes(1);
  expect(search).toHaveBeenCalledWith('abc');
});

// Network: mock at the boundary (MSW), not the function under test
// findBy* queries auto-retry → handles async render without arbitrary sleeps
const el = await screen.findByText('Loaded');  // awaits; no setTimeout/sleep hacks
```

**Flakiness** — tests that pass and fail nondeterministically — is the silent killer of suite trust, and its causes are specific: **arbitrary `sleep`/fixed timeouts** (use auto-retrying assertions like `findBy`/`waitFor` instead of `setTimeout`), **real network/time/randomness** (mock them), **shared/leaked state between tests** (reset mocks, DB, DOM in `afterEach`; never depend on test execution order), **race conditions in the app surfaced under load**, and **animation/transition timing** in e2e. The senior discipline: quarantine flaky tests immediately (a flaky test that's ignored erodes trust in *all* tests), fix the root cause (usually a timing assumption or shared state), and make tests deterministic by controlling every nondeterministic input (clock, network, random, time zone).

The configuration synthesis: pick **Vitest/Jest** for unit+integration (fast, JSDOM or happy-dom for component tests), **Testing Library** for user-centric component testing, **MSW** for network mocking, and **Playwright/Cypress** for a *thin* layer of critical-path e2e (login, checkout, the few flows that *must* work). Set **coverage as a floor not a goal** (100% coverage of trivial code while integration paths are untested is false confidence — prioritize covering branches and error paths over getter/setter lines). Run unit/integration on every commit (seconds), e2e on merge/nightly (minutes), and gate CI on both. The meta-lesson: optimize for **confidence per unit of maintenance** — most value comes from a broad base of fast deterministic unit/integration tests plus a few high-value e2e flows; mock at boundaries (network, time, randomness) not internals; test observable behavior so the suite survives refactors; and treat flakiness as a P1 bug because an untrusted suite is worse than no suite.

#### Q94. [Theory] Explain how iterators, generators, and `for await...of` enable backpressure and lazy streaming, and contrast with eager array processing and Node streams.

The deep value of iterators and generators is **laziness**: values are produced **on demand**, one `next()` call at a time, rather than computed and materialized all at once. This enables processing data that is **infinite, expensive, or larger than memory**, and — critically — **backpressure**: because the *consumer* drives production (it asks for the next value only when ready), a slow consumer naturally throttles a fast producer without buffering everything. Eager array processing (`arr.map().filter().slice()`) is the opposite: each step **materializes a full intermediate array**, so processing a 10-million-element source allocates multiple 10-million-element arrays even if you only need the first 5 results.

```javascript
// EAGER: builds full intermediate arrays, processes ALL elements
hugeArray.map(expensive).filter(pred).slice(0, 5);  // computes expensive() for ALL

// LAZY (generator pipeline): computes only until 5 results are pulled
function* map(iter, fn)    { for (const x of iter) yield fn(x); }
function* filter(iter, fn) { for (const x of iter) if (fn(x)) yield x; }
function* take(iter, n)    { let i = 0; for (const x of iter) { if (i++ >= n) return; yield x; } }

const pipeline = take(filter(map(hugeArray, expensive), pred), 5);
[...pipeline];   // expensive() runs only as many times as needed to find 5 matches
```

This is exactly what the **ES2025 Iterator Helpers** (`.map`, `.filter`, `.take`, `.drop`, `.flatMap` on iterators) standardize — lazy, composable transformations that don't build intermediates, finally giving the language built-in lazy pipelines. The `take` short-circuit demonstrates the power: the generator `return`s early, and the iteration protocol's **`return()` hook** propagates cleanup back up the chain, so upstream generators can release resources — something eager array methods can't do (they've already computed everything).

For values arriving **over time** — network pages, file chunks, WebSocket messages — **async iterators** (`Symbol.asyncIterator`) and **`for await...of`** extend laziness to asynchrony: each `next()` returns a Promise, and the loop *suspends* until the next chunk is ready, providing backpressure across the network. This is the idiomatic way to consume paginated APIs (fetch the next page only when the consumer asks) and is why **Node streams implement `Symbol.asyncIterator`**, letting you `for await...of` a readable stream:

```javascript
// Process a huge file line-by-line WITHOUT loading it into memory:
import { createReadStream } from 'node:fs';
import { createInterface } from 'node:readline';
const rl = createInterface({ input: createReadStream('huge.log') });
for await (const line of rl) {            // backpressure: reads as you process
  if (isError(line)) handle(line);        // break here → stream stops reading
}
```

The contrast with **Node streams** specifically: streams are the lower-level, event-driven (`data`/`end`/`error`) abstraction with explicit backpressure via the `highWaterMark`/`pause`/`resume`/`pipe` mechanism, built for raw byte/object throughput, transforms, and piping (`source.pipe(transform).pipe(dest)`). Async iteration (`for await...of`) is a higher-level, more ergonomic *consumer* interface over the same backpressure model — easier to read and reason about, with native `try/catch`/`break` semantics, at some overhead cost. The browser's equivalent is **`ReadableStream`** (also async-iterable), used for `fetch` response bodies. The senior synthesis: reach for **lazy iterators/generators** when the data is infinite, when you only need a prefix of a large computation (short-circuiting saves work), or when intermediate arrays would waste memory; use **`for await...of` / async iterators** for streaming network/file data with natural backpressure and clean cancellation (`break` stops upstream via `return()`); use **Node streams / `ReadableStream`** when you need byte-level throughput, transforms, piping, or fine `highWaterMark` control. The unifying principle is **consumer-driven pull** — laziness lets the consumer's pace govern the producer, which is what gives you memory bounds, short-circuiting, and backpressure that eager `.map().filter()` over a materialized array fundamentally cannot.

#### Q95. [Practical] How do you reason about and fix common production incidents caused by JavaScript: a memory-leaking SPA tab, an event-loop-blocking handler, and a runaway recursive microtask? Give the symptom, diagnosis, and fix for each.

These three are the canonical JS-caused production incidents, each with a distinct symptom signature, diagnostic path, and fix — recognizing the *signature* is what lets a senior triage fast under pressure.

**Incident 1 — Memory-leaking SPA tab.** *Symptom:* the tab's memory grows the longer it's open (especially as users navigate between routes without full page reloads), the UI gets progressively sluggish, and eventually the tab crashes ("Aw, Snap"/out-of-memory) or the OS swaps. *Diagnosis:* DevTools **Memory** tab → take a heap snapshot, navigate through the app several times (mount/unmount the suspect view repeatedly), force GC, take another snapshot, and **compare by retained size** — objects that grow monotonically and whose count tracks navigation count are the leak; follow the **retainer path** to the holder. *Root causes & fix:* the classic SPA leak is **listeners/subscriptions/timers not cleaned up on unmount** — an `addEventListener`, a store subscription, a `setInterval`, or a `ResizeObserver` registered on mount but never removed, keeping the entire component (and its captured state, often large) alive across navigations. Fix: pair every subscription with teardown (`removeEventListener`, `clearInterval`, `unsubscribe()`, `observer.disconnect()`), ideally via `AbortController` + `{ signal }` (one `abort()` removes all) and the framework's cleanup hook (`useEffect` return, `onUnmounted`, `ngOnDestroy`).

```javascript
useEffect(() => {
  const ctrl = new AbortController();
  window.addEventListener('resize', onResize, { signal: ctrl.signal });
  const id = setInterval(poll, 1000);
  return () => { ctrl.abort(); clearInterval(id); };  // ← without this: leak per mount
}, []);
```

**Incident 2 — Event-loop-blocking handler.** *Symptom:* clicking a button or typing freezes the entire UI for hundreds of ms to seconds — no scroll, no input, no animation — then it unfreezes; **INP is terrible**; DevTools shows a **long task** (red-flagged, >50 ms) on the main thread. *Diagnosis:* Performance panel → record the interaction → find the long task in the flame chart → read the call tree to see *what* synchronous work dominates (a giant synchronous loop, JSON-parsing a huge payload, a non-virtualized list rendering 50k rows, a synchronous crypto/compression call, or layout thrashing). *Fix:* keep the task under ~50 ms by (a) **offloading CPU-bound work to a Web Worker** (parsing, hashing, image processing — true parallelism, main thread stays free), (b) **chunking/yielding** (`scheduler.yield()` or processing in `requestIdleCallback`/`setTimeout` batches so the browser paints between chunks), (c) **virtualizing** long lists, and (d) fixing **layout thrashing** (batch DOM reads then writes). The principle: the main thread is shared by JS, layout, paint, and input — a long synchronous task starves all of them (run-to-completion guarantees it can't be preempted).

```javascript
// Before: blocks for seconds. After: offload to a worker, UI stays responsive.
const worker = new Worker('./parse.worker.js');
worker.postMessage(hugePayload);
worker.onmessage = (e) => render(e.data);   // heavy parse happened off-thread
```

**Incident 3 — Runaway recursive microtask.** *Symptom:* the page freezes *completely and permanently* — no rendering ever happens, even though no single task looks long, and CPU is pinned at 100%; it can be subtler than a blocking handler because the *event loop never gets to render or to the macrotask queue at all*. *Diagnosis:* Performance recording shows the microtask queue never draining to a render; common code smell is a Promise chain or `queueMicrotask`/`.then` that **re-enqueues itself unconditionally** (a recursive `Promise.resolve().then(loop)` with no termination, or an effect that triggers a state change that triggers the same effect). *Fix:* the event loop **fully drains the microtask queue before each render/macrotask**, so an infinite microtask chain *starves* everything — break the recursion (add a termination condition), or if you genuinely need a repeating loop that yields to rendering, **schedule via a macrotask** (`setTimeout`) or `requestAnimationFrame` instead of a microtask so the loop yields the thread between iterations.

```javascript
// BUG: microtask re-enqueues itself → starves render forever
function loop() { Promise.resolve().then(loop); }  // never yields to the event loop
// FIX: yield via macrotask/rAF so rendering + input get a turn
function loop() { doWork(); if (!done) requestAnimationFrame(loop); }
```

| Incident | Symptom | Diagnostic | Root fix |
|---|---|---|---|
| SPA memory leak | memory grows per navigation; crash | heap snapshot diff + retainer path | clean up listeners/timers/subs on unmount |
| Blocking handler | UI freezes on interaction; long task | Performance flame chart | Worker / chunk-yield / virtualize / fix thrashing |
| Runaway microtask | total permanent freeze; never renders | microtask queue never drains | terminate recursion / yield via macrotask/rAF |

The senior meta-framing: each incident maps to a property of the runtime model — leaks are **reachability** failures (something still references what should be GC'd), blocking is a **run-to-completion** consequence (no preemption, so long tasks freeze everything), and microtask starvation is the **event-loop ordering** rule (microtasks drain fully before render/macrotasks). Knowing *which* property is violated tells you both the diagnostic tool (Memory tab / Performance long-tasks / microtask drain) and the class of fix (release references / offload-or-chunk / break-the-recursion-and-yield). Prevention is the same trio: standardize cleanup (`AbortController`), budget task length (offload/chunk, monitor INP), and never write unbounded self-enqueuing microtasks.

#### Q96. [Theory] What is the difference between concurrency and parallelism in JavaScript, and how do the event loop, Web Workers, `worker_threads`, and `SharedArrayBuffer`/`Atomics` each fit? When does each actually help?

**Concurrency** is *dealing with* many things at once — interleaving multiple in-progress tasks on potentially one thread; **parallelism** is *doing* many things at once — literally executing on multiple cores simultaneously. JavaScript's core model gives you **concurrency without parallelism**: the single-threaded **event loop** interleaves I/O-bound work (network, timers, file reads) extremely well — while one request awaits the network, others proceed — but it runs all *your* JS on one thread, so **CPU-bound** work (parsing, crypto, image/video processing, large transforms) **blocks** everything, because run-to-completion forbids preemption. The critical diagnostic question is therefore *I/O-bound or CPU-bound?* — they need opposite tools.

For **I/O-bound** work, the event loop with `async`/`await` is already optimal — issuing many concurrent `fetch`es (bounded by a pool) overlaps their latencies, achieving high throughput on one thread because the *waiting* is done by the OS/host, not your JS. Adding threads here helps little; the bottleneck is the network/disk, not the CPU.

```javascript
// I/O-bound: concurrency on one thread is the right tool — overlap the waits
const results = await Promise.all(urls.map((u) => fetch(u)));  // latencies overlap
```

For **CPU-bound** work, you need actual **parallelism**, which JS provides via separate threads with **isolated heaps**: **Web Workers** (browser) and **`worker_threads`** (Node). They run on other OS threads/cores, so heavy computation there doesn't block the main thread's rendering/input. They communicate by **`postMessage`**, which **structured-clones** data by default (a *copy* — preserving heap isolation and thus the lock-free model), or **transfers** ownership of an `ArrayBuffer` in O(1) (zero-copy move, sender loses access). The trade-off is **serialization/transfer cost** and added complexity — workers only pay off when the computation outweighs the messaging overhead.

```javascript
// CPU-bound: parallelize across cores so the UI stays responsive
const worker = new Worker(new URL('./crunch.js', import.meta.url), { type: 'module' });
worker.postMessage(bigArrayBuffer, [bigArrayBuffer]); // transfer (zero-copy)
worker.onmessage = (e) => useResult(e.data);
```

When even copying is too expensive, or threads must coordinate on *shared* state, **`SharedArrayBuffer`** exposes the *same* bytes to multiple threads (no copy, no transfer — genuinely shared memory), and **`Atomics`** provides the lock-free atomic operations (`Atomics.add`, `compareExchange`, `wait`/`notify`/`waitAsync`) needed to coordinate safely **without data races** — because once memory is truly shared, JS's "no concurrent mutation" guarantee no longer holds, and you need atomics/locks exactly like any multithreaded language. This is the domain of WASM threads, high-performance shared ring buffers, and parallel numeric kernels.

```
                 helps when…                         mechanism
event loop       I/O-bound (network/disk/timers)     interleave waits, 1 thread
Web/worker_thread CPU-bound (parse/crypto/image)     true parallelism, isolated heaps, postMessage
SharedArrayBuffer shared state / copy-cost too high  same bytes across threads
Atomics          coordinating SAB safely             lock-free atomic ops, wait/notify
```

| | Concurrency (event loop) | Parallelism (workers) |
|---|---|---|
| Threads | 1 | N (cores) |
| Best for | I/O-bound | CPU-bound |
| State | shared, lock-free (run-to-completion) | isolated heaps (copy/transfer) or SAB (shared) |
| Cost | ~none | serialization/transfer + complexity |
| Race conditions | impossible within a task | possible with SharedArrayBuffer → need Atomics |

The senior synthesis and decision rule: **first classify the bottleneck.** If it's **I/O-bound**, the event loop + `async`/`await` + a bounded concurrency pool is the answer — adding workers is wasted complexity. If it's **CPU-bound** and blocking the UI/main thread, move it to a **Web Worker / `worker_thread`** (true parallelism), choosing **transfer** over clone for large buffers to avoid copy cost. Reach for **`SharedArrayBuffer` + `Atomics`** only when threads must share large mutable state and the copy/transfer cost dominates — accepting that you've re-entered the world of data races and must reason about memory ordering (and that browsers gate SAB behind **cross-origin isolation** headers due to Spectre). The recurring mistake is reaching for workers to speed up I/O (no benefit — the thread was waiting, not computing) or trying to fix a CPU-bound freeze with `async`/`await` (which doesn't add a thread — `await` of a synchronous-heavy function still blocks). Concurrency overlaps *waiting*; parallelism overlaps *computing* — match the tool to which one is your bottleneck.

#### Q97. [Practical] How do you configure and reason about ESLint, Prettier, and TypeScript together in a real project, and what classes of bugs does each actually prevent? Where do they overlap or conflict?

These three tools cover **distinct, complementary** layers of code quality, and confusing their roles causes friction (the classic ESLint-vs-Prettier formatting fight). The clean mental model: **Prettier** owns *formatting* (whitespace, quotes, line length, semicolons — purely how code *looks*), **ESLint** owns *code quality / bug-catching* (logic patterns, likely mistakes, project conventions — what code *does*), and **TypeScript** owns *type correctness* (whether values flow through the program with compatible shapes — what code *means*). They operate at different times and catch different bug classes, so a mature project runs all three.

**TypeScript** catches the largest class of real bugs *before runtime*: passing the wrong shape to a function, accessing a property that might be `undefined`, calling something that isn't a function, typos in property names, and — with `strict` mode — null/undefined dereferences (the "billion-dollar mistake") via `strictNullChecks`. It's a *compile-time* guarantee that the dynamic language can't give at runtime. Critically, `strict: true` (which bundles `strictNullChecks`, `noImplicitAny`, etc.) is where most of the value is — a non-strict TS config catches far fewer bugs.

```jsonc
// tsconfig.json — strictness is where the bug-prevention value lives
{ "compilerOptions": {
    "strict": true,                    // null checks, no implicit any, etc.
    "noUncheckedIndexedAccess": true,  // arr[i] is T | undefined — catches OOB bugs
    "noImplicitReturns": true,
    "exactOptionalPropertyTypes": true
} }
```

**ESLint** catches what types *can't*: it's an AST-based linter for **logic and convention** bugs — `no-unused-vars`, `no-undef`, `eqeqeq` (forbid `==`), `no-floating-promises` (an un-awaited Promise that swallows rejections — a huge async-bug class), `react-hooks/exhaustive-deps` (stale-closure bugs in React), `no-await-in-loop`, accessibility rules, and project-specific rules. With `@typescript-eslint` and **type-aware rules** (`parserOptions.project`), it catches things even TS doesn't flag as errors, like awaiting a non-Promise or unsafe `any` usage. The key configuration insight: **ESLint should not do formatting** — let Prettier own that, and use **`eslint-config-prettier`** to *turn off* all of ESLint's stylistic rules so the two don't conflict (the most common setup mistake is leaving formatting rules on in both, producing fights where ESLint and Prettier "fix" each other).

```javascript
// eslint.config.js (flat config) — ESLint for logic, Prettier for formatting
import js from '@eslint/js';
import ts from 'typescript-eslint';
import prettier from 'eslint-config-prettier';   // ← disables ESLint formatting rules
export default [
  js.configs.recommended,
  ...ts.configs.recommendedTypeChecked,            // type-aware lint rules
  { rules: { 'eqeqeq': 'error', '@typescript-eslint/no-floating-promises': 'error' } },
  prettier,                                        // MUST be last to win conflicts
];
```

| Tool | Layer | Catches | Runtime/build time |
|---|---|---|---|
| Prettier | formatting | inconsistent style (zero logic) | format/save/commit |
| ESLint | code quality | likely bugs, bad patterns, conventions | lint/CI |
| TypeScript | types | wrong shapes, null derefs, type errors | compile/CI |

The **overlap/conflict** points to get right: (1) **formatting** — only Prettier should touch it; disable ESLint's stylistic rules via `eslint-config-prettier` (overlap resolved by deferring to Prettier). (2) **some lint rules duplicate TS** (e.g., `no-undef` is redundant when TS already checks references) — disable the redundant ESLint rules in TS files to avoid noise. (3) ESLint and TS *complement* on async/types: TS verifies the Promise's type, ESLint's `no-floating-promises` verifies you *handled* it — neither alone is sufficient. The senior workflow: run **Prettier on save / via a pre-commit hook** (lint-staged + husky) so formatting never appears in diffs or reviews; run **ESLint + `tsc --noEmit`** in **CI as blocking gates** (and ideally in the editor for fast feedback); keep TS **strict**; and treat each tool as covering a layer the others can't — formatting consistency (Prettier removes an entire category of review nitpicks), likely-bug and convention enforcement (ESLint), and type safety (TS). The meta-point: they're not competing — the friction people feel is almost always a *misconfiguration* (formatting rules left on in ESLint) rather than a real overlap. Properly composed, they form a cheap, automated defense-in-depth that catches bugs at three different levels before code ever runs, freeing human review for logic and design rather than style and typos.

#### Q98. [Theory] Explain the complete lifecycle and trade-offs of a `WeakMap`-based cache versus an LRU cache versus a `WeakRef`/`FinalizationRegistry` cache. When does each leak, and when does each lose data unexpectedly?

Caching in JavaScript forces a trade-off between **two failure modes**: a cache that holds too much **leaks memory**, and a cache that releases too eagerly **loses data and recomputes** (or worse, returns stale or wrong results). The three weak/bounded caching strategies sit at different points on this spectrum, and choosing wrong causes exactly one of those failures in production.

A **`WeakMap`-based cache** keys entries by **object identity**, and holds the *key* weakly: when the only remaining reference to a key object is the `WeakMap` itself, the entry becomes eligible for GC and **vanishes automatically**. This makes it *leak-proof by construction* for object-keyed metadata (associating computed data with DOM nodes, request objects, or component instances) — you never have to remember to evict, and entries die exactly when their key dies. The constraints: keys **must be objects** (not primitives), it's **not enumerable** (no `size`, no iteration — by design, because entries can disappear at any GC), and "cache hit" means **same reference** — passing a *new, structurally-equal* object misses. So it *loses data* (misses) precisely when callers create fresh objects with identical contents (the `useMemo`/`reselect` "my memo never hits" problem), and it can't be used to cache by value (e.g., by a string ID).

```javascript
const metaCache = new WeakMap();          // keyed by object identity, auto-GC'd
function getMeta(node) {                   // node is a DOM element / object
  if (metaCache.has(node)) return metaCache.get(node);
  const meta = computeExpensive(node);
  metaCache.set(node, meta);               // entry dies when `node` is GC'd — no leak
  return meta;
}
// Footgun: getMeta({id:1}) then getMeta({id:1}) → two different objects → two misses
```

An **LRU (Least-Recently-Used) cache** keys by **value** (typically a string/serialized key), holds entries **strongly**, and bounds size by **evicting the least-recently-used** entry when capacity is exceeded (often implemented via `Map` insertion order, as in Q23/Q68). It's the right tool when you cache by *value* (an API response by URL, a computed result by primitive args) and need a *hit rate* with bounded memory. Its failure modes are the inverse of `WeakMap`: it **leaks** if you forget to bound it (an unbounded `Map` cache grows forever — every key and value retained strongly), and it **loses data** when the working set exceeds capacity (thrashing — evicting entries you're about to need again) or when entries go **stale** (cached value no longer matches reality — hence the optional TTL). It gives you `size`, enumeration, and value-keying that `WeakMap` can't.

A **`WeakRef` + `FinalizationRegistry` cache** holds the *value* weakly (`new WeakRef(obj)`), so the cached object can be GC'd under memory pressure even while the cache entry exists; `.deref()` returns the object or `undefined` (a miss → recompute), and `FinalizationRegistry` lets you clean up the now-dangling cache slot after collection. This suits caching **large objects that are expensive but recomputable**, where you want the GC to reclaim them when memory is tight rather than pinning them. The severe caveat: **GC timing is non-deterministic** — you cannot predict *when* (or whether) a `WeakRef` clears, so the cache's behavior is unpredictable (it might hold for a long time or drop immediately), `FinalizationRegistry` callbacks may run late, never, or in a different order, and **nothing about correctness may depend on them**. It "loses data" unpredictably (any deref can miss at any time) and is purely a *best-effort* optimization, never a guarantee.

| Strategy | Keyed by | Holds | Auto-evicts | Leaks if… | Loses data when… |
|---|---|---|---|---|---|
| `WeakMap` | object identity | key weakly | yes (on key GC) | (won't — leak-proof) | caller passes new equal object |
| LRU (`Map`) | value/string | strongly | on capacity | unbounded / no TTL | working set > capacity; stale w/o TTL |
| `WeakRef`+`FinalizationRegistry` | either | value weakly | yes (on GC) | (rarely) | any deref, unpredictably (GC timing) |

The senior decision framework: use a **`WeakMap`** when you cache metadata **keyed by an object you don't own** and want zero-maintenance, leak-proof lifetime tied to the key (the canonical: per-object computed data, DOM-node annotations, memoizing a single-object-argument function by reference — what `reselect` does) — accept that it only does reference-keyed hits and gives no `size`. Use an **LRU** when you cache **by value** and need predictable hit rates with **bounded, deterministic memory** (API responses, results of primitive-argument functions) — and *always* bound it (size and/or TTL), because an unbounded value cache is a memory leak by definition and the most common caching incident. Reserve **`WeakRef`/`FinalizationRegistry`** for the narrow case of **large, recomputable objects** you want the GC free to reclaim under pressure — and *never* rely on its timing for correctness or prompt cleanup (over-reliance on `FinalizationRegistry` is a recognized senior-level anti-pattern). The unifying principle: every cache is a **deliberate reachability decision** — `WeakMap` ties lifetime to a key's lifetime (no policy needed), LRU imposes an explicit size/recency policy (you own eviction), and `WeakRef` delegates the policy to the non-deterministic GC (best-effort only). Pick the one whose lifetime semantics match your data, and the leak-vs-lose-data trade-off resolves itself.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q99. [Coding] Implement `groupBy(array, keyFn)` without using `Object.groupBy`, and explain why `Object.create(null)` is the safer accumulator.

**Problem:** Partition an array into buckets keyed by the result of `keyFn(item)`, returning a plain object mapping each key to the array of items that produced it. This is the classic "group records by category" operation that precedes most reporting and aggregation work.

The interesting decision is the accumulator. A naive `{}` literal inherits from `Object.prototype`, so if `keyFn` ever returns the string `"__proto__"`, `"constructor"`, or `"hasOwnProperty"` (entirely possible with user-supplied data), you either corrupt the prototype chain or collide with inherited members. Using `Object.create(null)` produces a "dictionary object" with **no prototype**, so every key is a safe own property and there is no pollution surface. The trade-off is that the result has no `toString`/`hasOwnProperty` of its own — which is exactly what you want for a pure map.

```javascript
function groupBy(array, keyFn) {
  const out = Object.create(null);          // prototype-free dictionary
  for (let i = 0; i < array.length; i++) {
    const item = array[i];
    const key = keyFn(item, i);
    (out[key] ??= []).push(item);           // create bucket lazily on first hit
  }
  return out;
}

const orders = [
  { id: 1, status: 'paid' }, { id: 2, status: 'pending' }, { id: 3, status: 'paid' },
];
console.log(groupBy(orders, (o) => o.status));
// [Object: null prototype] { paid: [ {id:1...}, {id:3...} ], pending: [ {id:2...} ] }
```

- **Time:** O(n) single pass. **Space:** O(n) for the buckets.
- **`??=`** (logical-nullish-assignment, ES2021) only assigns `[]` when the bucket is `undefined`, so it doubles as "get-or-create" in one expression.
- **Edge cases:** keys coerced to strings (numeric keys `1` and string `"1"` collide), `keyFn` returning `undefined`, and the prototype-pollution safety above. The native `Object.groupBy` / `Map.groupBy` (ES2024) do exactly this; reimplementing it demonstrates you understand the pollution hazard.

#### Q100. [Coding] Implement `chunk(array, size)` and `range(start, end, step)`, the two most common lodash utilities, and discuss generator-based laziness.

**Problem:** `chunk` splits an array into sub-arrays of length `size` (the last may be shorter); `range` produces an arithmetic sequence. Both look trivial but expose subtle off-by-one and validation bugs in interviews.

```javascript
function chunk(array, size) {
  if (size < 1) throw new RangeError('size must be >= 1');
  const out = [];
  for (let i = 0; i < array.length; i += size) {
    out.push(array.slice(i, i + size));     // slice clamps past the end automatically
  }
  return out;
}

function range(start, end, step = 1) {
  if (step === 0) throw new RangeError('step must be non-zero');
  const out = [];
  if (step > 0) for (let n = start; n < end; n += step) out.push(n);
  else          for (let n = start; n > end; n += step) out.push(n);
  return out;
}

console.log(chunk([1, 2, 3, 4, 5], 2)); // [[1,2],[3,4],[5]]
console.log(range(0, 10, 3));           // [0, 3, 6, 9]
console.log(range(5, 0, -2));           // [5, 3, 1]
```

When the sequence could be huge or infinite, eagerly materializing an array wastes memory. A **lazy generator** version yields on demand and composes with `for...of`, `take`, and spread:

```javascript
function* lazyRange(start, end, step = 1) {
  for (let n = start; step > 0 ? n < end : n > end; n += step) yield n;
}
const first3 = [];
for (const n of lazyRange(0, Infinity)) { if (n === 3) break; first3.push(n); }
// first3 === [0, 1, 2] — never built an infinite array
```

- **`slice` self-clamps**, so `chunk` needs no special-case for the final short chunk. **Time:** O(n), **Space:** O(n).
- **Edge cases:** `size < 1` (throw), empty input (`[]`), negative step in `range`, and the eager-vs-lazy memory trade-off that the generator resolves.

#### Q101. [Coding] Implement a `once(fn)` higher-order function that guarantees a function runs at most one time and caches its return value.

**Problem:** `once` is the building block for idempotent initialization — lazy singletons, one-time setup handlers, "fire this analytics event exactly once." Calling the wrapped function repeatedly must invoke the original only on the first call and return the **same cached result** thereafter, while preserving `this` and arguments.

```javascript
function once(fn) {
  let called = false;
  let result;
  function wrapped(...args) {
    if (!called) {
      called = true;
      result = fn.apply(this, args);  // preserve receiver + arguments
      fn = null;                      // release the closure for GC
    }
    return result;
  }
  wrapped.reset = () => { called = false; }; // optional: allow re-arming
  return wrapped;
}

const init = once(() => { console.log('initializing'); return { ready: true }; });
const a = init();   // logs "initializing"
const b = init();   // (silent) — fn not called again
console.log(a === b); // true — same cached object
```

The subtle correctness point is the `called` flag rather than checking `result === undefined`, because a function that legitimately returns `undefined` must still be treated as "already called." Setting `fn = null` after the first call lets the garbage collector reclaim any large objects the original closure captured — important when `once` guards a heavyweight initializer.

- **Time:** O(1) per call after the first. **Space:** O(1) plus whatever the cached result retains.
- **Edge cases:** the wrapped function throwing on first call (here it would mark `called` *before* throwing — a design choice; some variants only set `called` after success so a failed init can retry), and concurrent invocation, which in single-threaded JS is safe because run-to-completion prevents interleaving.

#### Q102. [Coding] Implement deep equality (`isEqual`) for objects, arrays, `Date`, `RegExp`, `Map`, and `Set`, handling `NaN` and circular references.

**Problem:** Structural deep comparison underpins memoization, test assertions, and change detection. The naive `JSON.stringify(a) === JSON.stringify(b)` fails on key order, `undefined`, `NaN`, `Map`/`Set`, and circular refs — so a proper recursive comparator is the senior answer.

```javascript
function isEqual(a, b, seen = new WeakMap()) {
  if (Object.is(a, b)) return true;                       // handles NaN, +0/-0, identity
  if (typeof a !== 'object' || a === null ||
      typeof b !== 'object' || b === null) return false;

  if (seen.get(a) === b) return true;                     // cycle already paired
  seen.set(a, b);

  if (a.constructor !== b.constructor) return false;      // Date !== plain object, etc.
  if (a instanceof Date)   return a.getTime() === b.getTime();
  if (a instanceof RegExp) return a.source === b.source && a.flags === b.flags;

  if (a instanceof Map) {
    if (a.size !== b.size) return false;
    for (const [k, v] of a) if (!b.has(k) || !isEqual(v, b.get(k), seen)) return false;
    return true;
  }
  if (a instanceof Set) {
    if (a.size !== b.size) return false;
    for (const v of a) if (!b.has(v)) return false;       // note: deep-Set is harder (see below)
    return true;
  }

  const ka = Reflect.ownKeys(a), kb = Reflect.ownKeys(b);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) &&
                         isEqual(a[k], b[k], seen));
}
```

`Object.is` is the right base case because it equates `NaN` with `NaN` and distinguishes `+0` from `-0`, which `===` gets wrong for deep-equality purposes. The `WeakMap` of `a → b` pairings prevents infinite recursion on cycles. Comparing `constructor` cheaply rejects mismatched types before the expensive recursion.

- **Time:** O(n) in total nodes (assuming O(1) key lookups). **Space:** O(depth) stack + O(n) for `seen`.
- **Edge cases / caveats:** `Set` membership of **objects** is by reference, so true deep-Set equality (matching structurally-equal but non-identical members) requires an O(n²) bipartite match — most libraries (including lodash) only do reference equality for Set members, which I've followed here. Symbol keys are included via `Reflect.ownKeys`.

### 🟡 Intermediate — extended

#### Q103. [Coding] Implement `Array.prototype.reduce` from scratch as a polyfill, handling the no-initial-value case and sparse arrays correctly.

**Problem:** `reduce` is the most misunderstood array method. Writing a spec-faithful polyfill forces you to handle the two distinct modes (with and without an initial value) and the "no elements + no initial value" error — exactly the edge cases people get wrong in production.

```javascript
Array.prototype.myReduce = function (callback, ...rest) {
  if (typeof callback !== 'function') throw new TypeError(callback + ' is not a function');
  const O = Object(this);
  const len = O.length >>> 0;                 // ToUint32, like the spec
  let k = 0;
  let acc;

  if (rest.length >= 1) {
    acc = rest[0];                            // initial value supplied
  } else {
    // find the first present index (skip holes in sparse arrays)
    while (k < len && !(k in O)) k++;
    if (k >= len) throw new TypeError('Reduce of empty array with no initial value');
    acc = O[k++];
  }

  for (; k < len; k++) {
    if (k in O) acc = callback(acc, O[k], k, O); // skip holes; pass (acc, val, idx, arr)
  }
  return acc;
};

console.log([1, 2, 3, 4].myReduce((s, n) => s + n));      // 10 (no init)
console.log([1, 2, 3].myReduce((s, n) => s + n, 100));    // 106 (with init)
// console.log([].myReduce((a, b) => a + b));             // TypeError
```

Two details separate a real polyfill from a toy: (1) `rest.length >= 1` rather than `initial !== undefined`, because a caller may legitimately pass `undefined` as the seed; (2) the `k in O` checks, which skip **holes** in sparse arrays (`[1, , 3]`) just like the native method, rather than calling the callback with `undefined`.

- **Time:** O(n). **Space:** O(1) beyond the accumulator.
- **Edge cases:** empty array with no seed (throws), `undefined` seed, sparse arrays, and the four-argument callback signature `(accumulator, currentValue, index, array)`.

#### Q104. [Coding] Implement a `pipe` and `compose` for function composition, including an async-aware variant.

**Problem:** `pipe(f, g, h)(x)` computes `h(g(f(x)))` (left-to-right); `compose` is the same right-to-left. These are the backbone of functional data pipelines, Redux middleware, and Express-style handlers. An async-aware variant must thread a value through functions that may return promises.

```javascript
const pipe    = (...fns) => (x) => fns.reduce((acc, fn) => fn(acc), x);
const compose = (...fns) => (x) => fns.reduceRight((acc, fn) => fn(acc), x);

const double = (n) => n * 2;
const inc    = (n) => n + 1;
console.log(pipe(double, inc)(5));    // 11  -> inc(double(5))
console.log(compose(double, inc)(5)); // 12  -> double(inc(5))

// Async pipe: each fn may be sync or return a Promise; await threads them in order.
const pipeAsync = (...fns) => (x) =>
  fns.reduce((accP, fn) => accP.then(fn), Promise.resolve(x));

const fetchUser = (id) => Promise.resolve({ id, name: 'Ada' });
const greet     = (u) => `Hello, ${u.name}`;
pipeAsync(fetchUser, greet)(42).then(console.log); // "Hello, Ada"
```

The elegance is that `reduce`/`reduceRight` *are* function composition — the accumulator is the running value. The async version replaces the accumulator with a promise chain: `accP.then(fn)` automatically awaits the previous step and flattens any promise `fn` returns (because `.then` unwraps thenables), so sync and async steps mix freely.

- **Time:** O(k) in the number of functions; **Space:** O(1) (sync) / O(k) promise chain (async).
- **Edge cases:** zero functions (`pipe()(x)` returns `x` — identity), a step that throws (sync version propagates synchronously; async version rejects the chain so a single `.catch` handles any stage), and preserving argument count (these single-argument pipes are the standard; multi-arg requires the first function to accept the spread).

#### Q105. [Coding] Implement an async semaphore / concurrency limiter `mapLimit(items, limit, asyncFn)` that preserves output order.

**Problem:** Given thousands of items and an async operation (HTTP call, DB query), run at most `limit` in flight at once — never all at once (which exhausts sockets/memory) and never one-at-a-time (too slow). Results must come back **in input order** even though completion order varies.

```javascript
async function mapLimit(items, limit, asyncFn) {
  const results = new Array(items.length);
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < items.length) {
      const i = nextIndex++;              // claim an index atomically (single-threaded)
      results[i] = await asyncFn(items[i], i);
    }
  }

  // Spin up `limit` workers that pull from the shared cursor until exhausted.
  const pool = Array.from({ length: Math.min(limit, items.length) }, worker);
  await Promise.all(pool);
  return results;
}

// Demo
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
mapLimit([300, 100, 200, 50], 2, async (ms, i) => {
  await sleep(ms);
  return `item ${i} done`;
}).then(console.log); // ['item 0 done','item 1 done','item 2 done','item 3 done']
```

The "worker pool over a shared cursor" pattern is cleaner than the alternative of tracking a `Set` of in-flight promises and racing them. Because JavaScript is single-threaded, `nextIndex++` is atomic — no two workers can grab the same index. Writing into `results[i]` by the claimed index preserves order regardless of which worker finishes first.

- **Time:** wall-clock ≈ total work / `limit`. **Space:** O(n) for results + O(limit) live promises.
- **Edge cases:** `limit >= items.length` (degenerates to full `Promise.all`), empty input, and **error handling** — here a rejection propagates through `Promise.all` and aborts the batch; production variants collect errors per-item (like `allSettled`) or accept an `AbortSignal` to cancel remaining work.

#### Q106. [Coding] Implement a `flattenObject(obj)` that turns a nested object into a single-level object with dotted-path keys, and the inverse `unflatten`.

**Problem:** Flattening nested config/state into `{ 'a.b.c': value }` is ubiquitous in form libraries, i18n files, and query builders. The inverse rebuilds the tree. Getting array handling and the recursion right is the challenge.

```javascript
function flattenObject(obj, prefix = '', out = {}) {
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value) &&
        !(value instanceof Date)) {
      flattenObject(value, path, out);     // recurse into plain objects only
    } else {
      out[path] = value;                   // leaf: primitive, array, Date, or null
    }
  }
  return out;
}

function unflatten(flat) {
  const out = {};
  for (const [path, value] of Object.entries(flat)) {
    const keys = path.split('.');
    let node = out;
    keys.forEach((k, i) => {
      if (i === keys.length - 1) node[k] = value;
      else node = node[k] ??= {};
    });
  }
  return out;
}

const nested = { user: { name: 'Ada', addr: { city: 'London' } }, tags: ['a', 'b'] };
const flat = flattenObject(nested);
// { 'user.name': 'Ada', 'user.addr.city': 'London', tags: ['a','b'] }
console.log(unflatten(flat)); // structurally equal to `nested`
```

The key design decision is **what counts as a leaf**. Here arrays, `Date`, and `null` are treated as terminal values rather than recursed into, because dotted paths through array indices (`tags.0`) are usually undesirable and `Date` would explode into internal slots. This matches how libraries like `flat` behave by default.

- **Time:** O(n) total properties. **Space:** O(n) output + O(depth) stack.
- **Edge cases:** keys that themselves contain a dot (the round-trip breaks — needs a delimiter escape or a different separator), circular references (would infinite-loop; guard with a `WeakSet` if input is untrusted), and the array-as-leaf decision above.

#### Q107. [Coding] Build a tiny reactive state store with `subscribe`, `getState`, and `dispatch` — a minimal Redux — and explain immutability's role.

**Problem:** Implement the core of a predictable state container: a single immutable state tree, a pure reducer `(state, action) => newState`, subscriber notification on change, and `dispatch`. This demonstrates closures, the observer pattern, and why immutability enables cheap change detection.

```javascript
function createStore(reducer, initialState) {
  let state = initialState;
  const listeners = new Set();

  function getState() { return state; }

  function dispatch(action) {
    const next = reducer(state, action);   // reducer MUST be pure and return new refs
    if (next !== state) {                   // reference check is enough with immutability
      state = next;
      for (const l of [...listeners]) l(state); // snapshot to survive mid-notify unsubscribe
    }
    return action;
  }

  function subscribe(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener); // unsubscribe handle
  }

  dispatch({ type: '@@INIT' });             // prime state from the reducer's default
  return { getState, dispatch, subscribe };
}

const counter = (state = { n: 0 }, action) => {
  switch (action.type) {
    case 'INC': return { ...state, n: state.n + 1 }; // new object — new reference
    case 'DEC': return { ...state, n: state.n - 1 };
    default:    return state;                         // unchanged — same reference
  }
};

const store = createStore(counter);
const off = store.subscribe((s) => console.log('state:', s));
store.dispatch({ type: 'INC' }); // state: { n: 1 }
store.dispatch({ type: 'INC' }); // state: { n: 2 }
off();
```

The single most important line is `if (next !== state)`. Because the reducer returns a **new reference** only when something changed (and the *same* reference otherwise), a downstream consumer — including React's `useSyncExternalStore` or a memoized selector — can detect change with a single `===` comparison instead of a deep diff. That is the entire performance argument for immutability: O(1) change detection in exchange for allocating new objects on update (mitigated by **structural sharing**, where unchanged sub-trees are reused by reference).

- **Time:** dispatch is O(reducer) + O(subscribers). **Space:** O(state) per version (sharing unchanged branches).
- **Edge cases:** subscriber added/removed during notification (the `[...listeners]` snapshot handles it), reducers that accidentally mutate state in place (breaks the `!==` optimization — the canonical Redux bug), and re-entrant dispatch.

#### Q108. [Coding] Implement a `retry`-free, leak-free `EventEmitter.once` returning a Promise, plus `waitFor(emitter, event, { signal, timeout })`.

**Problem:** Bridging event-based APIs into `async/await` is a daily task (waiting for a socket to `open`, a stream to `end`). The naive bridge leaks listeners if the event never fires or if a timeout/abort intervenes. A correct `waitFor` must remove **every** listener on every exit path.

```javascript
function waitFor(emitter, event, { signal, timeout } = {}) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);

    let timer;
    const cleanup = () => {                 // single teardown for ALL paths
      emitter.off(event, onEvent);
      emitter.off('error', onError);
      signal?.removeEventListener('abort', onAbort);
      if (timer) clearTimeout(timer);
    };
    const onEvent = (...args) => { cleanup(); resolve(args.length > 1 ? args : args[0]); };
    const onError = (err)     => { cleanup(); reject(err); };
    const onAbort = ()        => { cleanup(); reject(signal.reason); };

    emitter.on(event, onEvent);
    emitter.on('error', onError);
    signal?.addEventListener('abort', onAbort);
    if (timeout != null) {
      timer = setTimeout(() => { cleanup(); reject(new Error(`Timeout waiting for "${event}"`)); }, timeout);
    }
  });
}

// Usage
const ac = new AbortController();
waitFor(socket, 'open', { timeout: 5000, signal: ac.signal })
  .then(() => console.log('connected'))
  .catch((e) => console.error('failed:', e.message));
```

The bug this prevents is the classic **listener leak**: register `on(event)` to resolve a promise, then a timeout fires and rejects — but the original listener is still attached, so when the event *does* eventually fire it runs on a settled promise (a no-op) while the closure (and anything it captured) stays alive. Centralizing teardown in a single `cleanup()` invoked on resolve, reject, timeout, *and* abort guarantees exactly-one settlement and zero residual listeners. This mirrors Node's built-in `events.once(emitter, name, { signal })`.

- **Time:** O(1). **Space:** O(1) — and critically, bounded because listeners are always removed.
- **Edge cases:** already-aborted signal (reject synchronously), `'error'` events (must reject, since Node throws on unhandled `'error'`), multi-argument events, and ensuring the timeout/abort don't double-settle.

### 🟠 Advanced — extended

#### Q109. [Coding] Implement an `Observable` with `map`, `filter`, and `take` operators, and explain how it differs from a Promise and from an async iterator.

**Problem:** Build a minimal push-based Observable (the Rx/TC39 "Observable" shape): a producer that emits zero-or-more values over time to a subscriber, with composable operators and proper teardown. This is the abstraction Promises *can't* express because a Promise is a single, eager, uncancelable value.

```javascript
class Observable {
  constructor(subscribe) { this._subscribe = subscribe; } // subscribe(observer) -> teardown

  subscribe(observer) {
    const safe = typeof observer === 'function' ? { next: observer } : observer;
    let closed = false;
    const teardown = this._subscribe({
      next:     (v) => { if (!closed) safe.next?.(v); },
      error:    (e) => { if (!closed) { closed = true; safe.error?.(e); } },
      complete: ()  => { if (!closed) { closed = true; safe.complete?.(); } },
    });
    return () => { closed = true; teardown?.(); };          // unsubscribe
  }

  map(fn) {
    return new Observable((obs) => this.subscribe({
      next: (v) => obs.next(fn(v)), error: (e) => obs.error(e), complete: () => obs.complete(),
    }));
  }
  filter(pred) {
    return new Observable((obs) => this.subscribe({
      next: (v) => { if (pred(v)) obs.next(v); }, error: (e) => obs.error(e), complete: () => obs.complete(),
    }));
  }
  take(n) {
    return new Observable((obs) => {
      let count = 0;
      const sub = this.subscribe({
        next: (v) => { obs.next(v); if (++count >= n) { obs.complete(); sub(); } },
        error: (e) => obs.error(e), complete: () => obs.complete(),
      });
      return sub;
    });
  }
}

const interval = new Observable((obs) => {
  let i = 0; const id = setInterval(() => obs.next(i++), 100);
  return () => clearInterval(id);                          // teardown stops the timer
});

interval.map((x) => x * 10).filter((x) => x % 20 === 0).take(3)
  .subscribe({ next: console.log, complete: () => console.log('done') });
// 0, 20, 40, done — and the interval is cleared automatically
```

The defining differences: a **Promise** is *single-value, eager* (runs at creation), and *uncancelable*; an **async iterator** is *multi-value, lazy, pull-based* (the consumer asks for the next value, giving natural backpressure); an **Observable** is *multi-value, push-based* (the producer decides timing) with explicit **teardown** for cancellation. Observables shine for event streams (clicks, websockets) where the producer drives; async iterators shine when the consumer should pace the producer.

- **Lazy & cancelable:** nothing runs until `subscribe`, and unsubscribing invokes the teardown (clearing the interval), which is exactly what Promises lack.
- **Edge cases:** `take(0)`, completing/erroring after unsubscribe (guarded by `closed`), and ensuring teardown propagates through every operator in the chain.

#### Q110. [Coding] Implement a trie (prefix tree) supporting `insert`, `search`, and `startsWith`, and analyze it against a `Set` for autocomplete.

**Problem:** Build a trie for prefix queries — the data structure behind autocomplete and spell-check. Each node holds a map of next-character to child node plus an end-of-word flag.

```javascript
class TrieNode {
  constructor() { this.children = new Map(); this.isEnd = false; }
}

class Trie {
  constructor() { this.root = new TrieNode(); }

  insert(word) {
    let node = this.root;
    for (const ch of word) {                 // iterates code points (Unicode-safe)
      if (!node.children.has(ch)) node.children.set(ch, new TrieNode());
      node = node.children.get(ch);
    }
    node.isEnd = true;
  }

  _walk(prefix) {                            // returns the node at the end of `prefix` or null
    let node = this.root;
    for (const ch of prefix) {
      node = node.children.get(ch);
      if (!node) return null;
    }
    return node;
  }

  search(word)        { const n = this._walk(word); return !!n && n.isEnd; }
  startsWith(prefix)  { return this._walk(prefix) !== null; }

  *autocomplete(prefix) {                    // yield every word under `prefix`
    const start = this._walk(prefix);
    if (!start) return;
    const stack = [[start, prefix]];
    while (stack.length) {
      const [node, str] = stack.pop();
      if (node.isEnd) yield str;
      for (const [ch, child] of node.children) stack.push([child, str + ch]);
    }
  }
}

const t = new Trie();
['car', 'card', 'cart', 'dog'].forEach((w) => t.insert(w));
console.log(t.search('car'));        // true
console.log(t.search('ca'));         // false (prefix, not a full word)
console.log(t.startsWith('ca'));     // true
console.log([...t.autocomplete('car')].sort()); // ['car', 'card', 'cart']
```

The trade-off versus a `Set<string>` plus linear scan: a `Set` gives O(1) **exact** membership but O(n·L) prefix search (scan every word). The trie gives **prefix** search in O(L + k) where L is prefix length and k is the number of matches — it never touches unrelated words. The cost is memory: many small node objects and `Map`s. For pure exact-match lookups a `Set` wins; for prefix/autocomplete the trie is the right structure.

- **Time:** insert/search/startsWith O(L); autocomplete O(size of subtree). **Space:** O(total characters), worst case.
- **Edge cases:** empty string insert/search, shared prefixes (`car`/`card`), Unicode (iterating with `for...of` handles surrogate pairs correctly, unlike indexing), and case sensitivity (normalize before insert if needed).

#### Q111. [Coding] Implement a token-bucket rate limiter usable for client-side API throttling, and contrast it with a sliding window.

**Problem:** Limit an operation to N actions per interval while allowing short bursts. The **token bucket** refills tokens at a steady rate up to a capacity; each action consumes a token, and when the bucket is empty the caller waits (or is rejected). It is the standard algorithm for API rate limiting because it tolerates bursts up to the bucket size while bounding the average rate.

```javascript
class TokenBucket {
  constructor({ capacity, refillPerSec }) {
    this.capacity = capacity;
    this.tokens = capacity;
    this.refillPerSec = refillPerSec;
    this.last = Date.now();
  }

  _refill() {
    const now = Date.now();
    const elapsed = (now - this.last) / 1000;
    this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.refillPerSec);
    this.last = now;
  }

  tryRemove(n = 1) {                          // non-blocking: succeed or fail immediately
    this._refill();
    if (this.tokens >= n) { this.tokens -= n; return true; }
    return false;
  }

  async remove(n = 1) {                       // blocking: wait until tokens are available
    this._refill();
    while (this.tokens < n) {
      const deficit = n - this.tokens;
      const waitMs = (deficit / this.refillPerSec) * 1000;
      await new Promise((r) => setTimeout(r, waitMs));
      this._refill();
    }
    this.tokens -= n;
  }
}

// Allow bursts of 5, then steady 2/sec
const bucket = new TokenBucket({ capacity: 5, refillPerSec: 2 });
async function callApi(i) { await bucket.remove(); console.log('call', i, 'at', Date.now()); }
[...Array(8)].forEach((_, i) => callApi(i)); // first 5 immediate, rest spaced ~500ms
```

The clever part is **lazy refilling**: instead of a `setInterval` adding tokens (which wastes timers and drifts), we compute how many tokens *would* have accrued based on elapsed wall-clock time on each access. Contrast with a **sliding-window log**, which stores the timestamp of every recent request and counts those within the window — more precise (no burst beyond the limit) but O(requests) memory and trickier under clock skew. Token bucket is O(1) memory and naturally allows controlled bursts, which is usually what you want for UX (let the user fire a few actions quickly, then smooth out).

- **Time:** O(1) per check. **Space:** O(1). **Edge cases:** clock going backwards (`elapsed` could be negative — clamp to 0), requesting more than `capacity` tokens (would block forever — validate), and fairness across concurrent `remove` callers.

#### Q112. [Coding] Implement a recursive-descent parser/evaluator for arithmetic expressions with operator precedence (`+ - * / ( )`).

**Problem:** Parse and evaluate strings like `"2 + 3 * (4 - 1)"` respecting precedence and parentheses. This demonstrates tokenization, grammar, and recursion — a favorite for senior roles because it separates people who can only call libraries from those who understand parsing.

```javascript
function evaluate(input) {
  const tokens = input.match(/\d+\.?\d*|[()+\-*/]/g) ?? [];
  let pos = 0;
  const peek = () => tokens[pos];
  const next = () => tokens[pos++];

  // Grammar (precedence climbing):
  //   expr   := term   (('+' | '-') term)*
  //   term   := factor (('*' | '/') factor)*
  //   factor := number | '(' expr ')' | '-' factor
  function expr() {
    let value = term();
    while (peek() === '+' || peek() === '-') {
      const op = next();
      value = op === '+' ? value + term() : value - term();
    }
    return value;
  }
  function term() {
    let value = factor();
    while (peek() === '*' || peek() === '/') {
      const op = next();
      value = op === '*' ? value * factor() : value / factor();
    }
    return value;
  }
  function factor() {
    const tok = next();
    if (tok === '(') { const v = expr(); next(); /* consume ')' */ return v; }
    if (tok === '-') return -factor();        // unary minus
    return parseFloat(tok);
  }

  const result = expr();
  if (pos !== tokens.length) throw new SyntaxError('Unexpected token: ' + peek());
  return result;
}

console.log(evaluate('2 + 3 * (4 - 1)')); // 11
console.log(evaluate('-5 + 10 / 2'));      // 0
console.log(evaluate('2 * 3 + 4 * 5'));    // 26
```

Precedence falls out of the **grammar structure**: `expr` handles the lowest-precedence operators (`+`/`-`) and delegates to `term` for the next level (`*`/`/`), which delegates to `factor` for atoms and parentheses. Because `factor` recursively calls `expr` inside parentheses, nesting works to any depth. This is why recursive descent is so popular — the call hierarchy *is* the precedence hierarchy, with no precedence tables.

- **Time:** O(n) tokens (each consumed once). **Space:** O(depth) recursion.
- **Edge cases / hardening:** the final `pos !== tokens.length` check rejects trailing garbage like `"2 + 3 )"`; division by zero yields `Infinity` (JS semantics — decide whether to throw); and **never** reach for `eval()` here, because it executes arbitrary code and is a classic injection vector.

#### Q113. [Coding] Implement `JSON.stringify` from scratch (core subset), surfacing the spec behaviors people forget.

**Problem:** Reimplement the essential `JSON.stringify` to internalize its many silent behaviors: `undefined`/function/symbol omission in objects but conversion to `null` in arrays, `toJSON` invocation, and circular-reference throwing.

```javascript
function jsonStringify(value, seen = new WeakSet()) {
  // toJSON hook (Date relies on this in real engines)
  if (value && typeof value.toJSON === 'function') value = value.toJSON();

  const t = typeof value;
  if (value === null) return 'null';
  if (t === 'number') return Number.isFinite(value) ? String(value) : 'null'; // NaN/Inf -> null
  if (t === 'boolean') return String(value);
  if (t === 'string') return quote(value);
  if (t === 'undefined' || t === 'function' || t === 'symbol') return undefined; // omitted

  if (typeof value === 'object') {
    if (seen.has(value)) throw new TypeError('Converting circular structure to JSON');
    seen.add(value);
    let result;
    if (Array.isArray(value)) {
      const parts = value.map((el) => jsonStringify(el, seen) ?? 'null'); // holes/undef -> null
      result = `[${parts.join(',')}]`;
    } else {
      const parts = [];
      for (const [k, v] of Object.entries(value)) {
        const sv = jsonStringify(v, seen);
        if (sv !== undefined) parts.push(`${quote(k)}:${sv}`);            // skip undefined props
      }
      result = `{${parts.join(',')}}`;
    }
    seen.delete(value);
    return result;
  }
}
function quote(s) {
  const escapes = { '"': '\\"', '\\': '\\\\', '\n': '\\n', '\t': '\\t', '\r': '\\r' };
  return '"' + s.replace(/[\\"\n\t\r]/g, (c) => escapes[c]) + '"';
}

console.log(jsonStringify({ a: 1, b: undefined, c: () => {}, d: [1, undefined, 3] }));
// {"a":1,"d":[1,null,3]}   <- b and c omitted; array undefined -> null
```

The behaviors this surfaces — which bite people in production — are: **object** properties with `undefined`/function/symbol values are *dropped*, but in **arrays** those positions become `null` (so array length is preserved); `NaN` and `Infinity` serialize to `null`; a `toJSON` method (how `Date` produces an ISO string) is honored; and circular references **throw** rather than loop. A real implementation also handles the `replacer` function/array and the `space` indentation argument, omitted here for focus.

- **Time:** O(n) in total nodes + string length. **Space:** O(output) + O(depth).
- **Edge cases:** the array-vs-object `undefined` asymmetry, non-finite numbers, circular structures, and string escaping (a frequent source of malformed output).

#### Q114. [Coding] Implement a virtualized-list "visible range" calculator and explain why windowing is essential for large lists.

**Problem:** Given a scroll position, viewport height, and a (possibly variable) row height, compute exactly which item indices to render. Rendering 50,000 DOM rows freezes the browser; **windowing** renders only the visible slice plus a small overscan buffer.

```javascript
// Fixed-height windowing
function visibleRange({ scrollTop, viewportHeight, rowHeight, itemCount, overscan = 3 }) {
  const first = Math.floor(scrollTop / rowHeight);
  const visibleCount = Math.ceil(viewportHeight / rowHeight);
  const start = Math.max(0, first - overscan);
  const end = Math.min(itemCount - 1, first + visibleCount + overscan);
  return {
    start, end,
    offsetY: start * rowHeight,            // translateY to position the window
    totalHeight: itemCount * rowHeight,    // spacer height so the scrollbar is correct
  };
}

console.log(visibleRange({ scrollTop: 1000, viewportHeight: 600, rowHeight: 40, itemCount: 50000 }));
// { start: 22, end: 43, offsetY: 880, totalHeight: 2000000 }
```

The two non-obvious pieces are `totalHeight` and `offsetY`. You render a tall **spacer** of `totalHeight` so the native scrollbar reflects the full list, then absolutely position (or `translateY`) the small rendered window at `offsetY` so the visible rows land under the scroll position. Without this, the scrollbar would size to only the rendered rows. **Overscan** renders a few extra rows above/below to avoid blank flashes during fast scrolling.

For **variable heights**, you replace the multiplication with a cumulative-offset array (a prefix sum) and binary-search it for `scrollTop` — O(log n) per scroll. Libraries like `react-window`/`@tanstack/virtual` implement exactly this, optionally measuring rows after first paint and patching the offset array.

- **Time:** O(1) fixed-height, O(log n) variable-height (binary search). **Space:** O(visible) DOM nodes instead of O(n).
- **Edge cases:** clamping at list ends, sub-pixel `rowHeight`, dynamic resize (recompute on `ResizeObserver`), and keeping keys stable so React reuses DOM nodes during scroll.

#### Q115. [Coding] Implement structural sharing for an immutable nested update (`setIn(obj, path, value)`) and explain its O(log n) advantage.

**Problem:** Update one deeply-nested value while keeping the original object untouched and **reusing** every unchanged sub-tree by reference. This is how Immer, Redux Toolkit, and React state achieve cheap immutability — only the nodes on the path from the root to the changed leaf are cloned.

```javascript
function setIn(obj, path, value) {
  if (path.length === 0) return value;
  const [key, ...rest] = path;
  const clone = Array.isArray(obj) ? obj.slice() : { ...obj }; // shallow-copy this level only
  clone[key] = setIn(obj?.[key], rest, value);                 // recurse into the rest of path
  return clone;
}

const state = {
  user: { name: 'Ada', prefs: { theme: 'dark', lang: 'en' } },
  items: [1, 2, 3],
};
const next = setIn(state, ['user', 'prefs', 'theme'], 'light');

console.log(next.user.prefs.theme);        // 'light'
console.log(state.user.prefs.theme);       // 'dark'  — original untouched
console.log(next.items === state.items);   // true    — unchanged branch SHARED by reference
console.log(next.user.prefs === state.user.prefs); // false — on the change path, cloned
```

The crucial assertion is `next.items === state.items`: the `items` array was *not* on the update path, so it is the **exact same reference** in both versions. Only `state`, `state.user`, and `state.user.prefs` (the spine from root to the changed leaf) are cloned. For a balanced tree of depth d that's O(d) ≈ O(log n) allocations per update instead of O(n) for a full deep clone — and the shared branches make `===` reference comparison a valid, O(1) "did this sub-tree change?" check for memoized selectors and `React.memo`.

- **Time:** O(path length) ≈ O(depth). **Space:** O(depth) new nodes; everything else shared.
- **Edge cases:** path into a non-existent branch (the `obj?.[key]` creates new objects/arrays — decide whether missing intermediate keys should become `{}` or throw), updating array indices vs object keys (the `Array.isArray` branch handles both), and not mutating the input.

### 🔴 Expert — extended

#### Q116. [Coding] Implement a from-scratch Promise (`MyPromise`) that is Promises/A+ compliant in spirit: states, `then` chaining, and async resolution.

**Problem:** Build a Promise that supports the three states, asynchronous `then` callbacks, value/error propagation through chains, and resolving with a thenable. Writing this proves you understand microtask scheduling and the resolution procedure that makes `.then` chains flatten.

```javascript
class MyPromise {
  constructor(executor) {
    this.state = 'pending';
    this.value = undefined;
    this.callbacks = [];                       // queued {onF, onR, resolve, reject}
    const resolve = (value) => this._settle('fulfilled', value);
    const reject  = (reason) => this._settle('rejected', reason);
    try { executor(resolve, reject); } catch (e) { reject(e); }
  }

  _settle(state, value) {
    if (this.state !== 'pending') return;       // settle once
    // If resolved with a thenable, adopt its state (the A+ resolution procedure)
    if (state === 'fulfilled' && value && typeof value.then === 'function') {
      try { value.then((v) => this._settle('fulfilled', v), (r) => this._settle('rejected', r)); }
      catch (e) { this._settle('rejected', e); }
      return;
    }
    this.state = state;
    this.value = value;
    this.callbacks.forEach((cb) => this._schedule(cb));
    this.callbacks = [];
  }

  _schedule(cb) {
    queueMicrotask(() => {                       // callbacks ALWAYS run async (microtask)
      const handler = this.state === 'fulfilled' ? cb.onF : cb.onR;
      try {
        if (typeof handler !== 'function') {     // pass-through (value/error propagation)
          this.state === 'fulfilled' ? cb.resolve(this.value) : cb.reject(this.value);
        } else {
          cb.resolve(handler(this.value));       // resolve next promise with handler's return
        }
      } catch (e) { cb.reject(e); }
    });
  }

  then(onF, onR) {
    return new MyPromise((resolve, reject) => {
      const cb = { onF, onR, resolve, reject };
      if (this.state === 'pending') this.callbacks.push(cb);
      else this._schedule(cb);
    });
  }
  catch(onR) { return this.then(undefined, onR); }
  static resolve(v) { return new MyPromise((res) => res(v)); }
  static reject(r)  { return new MyPromise((_, rej) => rej(r)); }
}

MyPromise.resolve(1)
  .then((v) => v + 1)
  .then((v) => MyPromise.resolve(v * 10)) // returning a promise flattens
  .then(console.log);                     // 20
```

Three subtleties make or break compliance: (1) **`queueMicrotask`** ensures `then` callbacks never run synchronously, preserving the "always async" guarantee that prevents Zalgo (release-the-zalgo) bugs; (2) the **resolution procedure** in `_settle` — if you resolve with a thenable, you adopt *its* eventual state, which is what flattens `then` chains so you never get a `Promise<Promise<T>>`; (3) **value/error propagation** — when `then` is called without the relevant handler, the value or error must pass through to the next promise unchanged.

- **Edge cases:** resolving with itself (full A+ requires a `TypeError`; omitted here), double-settle (guarded by the pending check), and synchronous throw in the executor (caught and rejected).

#### Q117. [Coding] Implement a cooperative scheduler that yields to the event loop to keep long tasks from blocking the UI (`scheduler.yield`-style time-slicing).

**Problem:** A CPU-heavy synchronous loop (processing 100k records) blocks the main thread and freezes input. Without moving to a Worker, you can **time-slice**: process work in chunks, yielding control back to the browser whenever the current chunk exceeds a frame budget so the UI stays responsive.

```javascript
// Yield to the event loop, letting the browser paint and handle input.
function yieldToMain() {
  // Prefer the modern scheduler API; fall back to a macrotask.
  if (globalThis.scheduler?.yield) return scheduler.yield();
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function processInSlices(items, work, { budgetMs = 5 } = {}) {
  let sliceStart = performance.now();
  for (let i = 0; i < items.length; i++) {
    work(items[i], i);
    if (performance.now() - sliceStart >= budgetMs) {  // exceeded this frame's budget?
      await yieldToMain();                             // let the browser breathe
      sliceStart = performance.now();                  // start a fresh budget window
    }
  }
}

// 100k items, never blocking longer than ~5ms at a stretch
await processInSlices(bigArray, (item) => heavyTransform(item));
```

The principle is the browser's ~16.6 ms frame budget (60 fps) and the "**long task**" threshold of 50 ms after which input responsiveness visibly degrades (this directly drives the **INP** Core Web Vital). By measuring elapsed time per slice and yielding once you cross a sub-frame budget (5 ms here), you let the browser interleave painting and event handling between slices, so a click registers in tens of milliseconds instead of after the whole batch. Yielding via `setTimeout(0)` schedules a **macrotask**, which (unlike a microtask) lets rendering and input occur; the newer `scheduler.yield()` returns a microtask-priority continuation that resumes *sooner* than `setTimeout` while still allowing the browser to do critical work — better latency without starving the UI.

- **Trade-off:** yielding too often adds scheduling overhead and slows total throughput; too rarely and you reintroduce jank. The budget is the tuning knob. For truly heavy CPU work, a **Web Worker** is still preferable because it doesn't touch the main thread at all.
- **Edge cases:** work that must observe consistent state between slices (yielding allows other code to mutate it), cancellation between slices (check an `AbortSignal` at the slice boundary), and SSR/Node where `performance.now`/`scheduler` differ.

#### Q118. [Coding] Implement an async generator that paginates an API with `for await...of`, including cancellation, and explain backpressure.

**Problem:** Consume a cursor-paginated endpoint as a flat async stream of records, fetching the next page only when the consumer asks for more — never buffering all pages in memory. This is the canonical, production-correct way to iterate large remote datasets.

```javascript
async function* paginate(fetchPage, { signal } = {}) {
  let cursor = undefined;
  do {
    if (signal?.aborted) throw signal.reason;
    const { items, nextCursor } = await fetchPage(cursor, signal);
    for (const item of items) yield item;     // hand items to the consumer one by one
    cursor = nextCursor;
  } while (cursor);                            // stop when the API returns no next cursor
}

// Mock paginated API
const db = Array.from({ length: 25 }, (_, i) => i);
async function fetchPage(cursor = 0) {
  const page = db.slice(cursor, cursor + 10);
  return { items: page, nextCursor: cursor + 10 < db.length ? cursor + 10 : null };
}

// Consumer controls the pace; breaking early stops fetching further pages.
const ac = new AbortController();
let seen = 0;
for await (const record of paginate(fetchPage, { signal: ac.signal })) {
  if (++seen >= 12) break;                     // we only needed 12 — page 3 is never fetched
  process(record);
}
```

The key property is **backpressure**: because async iteration is *pull-based*, the next page is fetched only when the `for await...of` loop requests another value and the current page is exhausted. The producer cannot outrun the consumer, so memory stays bounded to one page regardless of dataset size — the opposite of eagerly `Promise.all`-ing every page (unbounded memory and connection use). When the consumer `break`s, the generator's `return()` is invoked automatically, the loop ends, and no further pages are requested — that's built-in, structured cancellation; the `AbortSignal` adds explicit cancellation for the in-flight request.

- **Time:** O(records consumed), not O(total). **Space:** O(one page).
- **Edge cases:** an empty first page, a server that never returns a null cursor (infinite — needs a max-pages guard), errors mid-stream (propagate out of the `for await`), and ensuring `break`/`throw` clean up the in-flight fetch via the signal.

#### Q119. [Coding] Implement a memoizing selector with reference-equality dependency tracking (a mini `reselect`), and explain why naive memoization breaks with new object args.

**Problem:** Build `createSelector(...inputs, combiner)` that recomputes the derived value only when one of its input selectors returns a **new reference**, caching the last result otherwise. This is the standard optimization for expensive derived state in Redux/React, and it exposes the central pitfall of memoization: argument identity.

```javascript
function createSelector(...args) {
  const combiner = args.pop();
  const inputs = args;
  let lastInputs = null;
  let lastResult;

  return function (state, ...rest) {
    const values = inputs.map((fn) => fn(state, ...rest));
    const changed = !lastInputs ||
      values.length !== lastInputs.length ||
      values.some((v, i) => v !== lastInputs[i]);     // shallow reference compare
    if (changed) {
      lastResult = combiner(...values);               // recompute only when an input ref changed
      lastInputs = values;
    }
    return lastResult;
  };
}

const selectItems   = (s) => s.items;
const selectFilter  = (s) => s.filter;
const selectVisible = createSelector(selectItems, selectFilter,
  (items, filter) => { console.log('recompute'); return items.filter((i) => i.type === filter); });

const s1 = { items: [{ type: 'a' }, { type: 'b' }], filter: 'a' };
selectVisible(s1); // logs "recompute"
selectVisible(s1); // (cached) — same input references, no recompute
```

The trap that this design reveals: memoization caches keyed on **argument identity**, and a function that receives a freshly-allocated object/array each call has a 0% hit rate. The classic React bug is `useMemo(() => f(opts), [opts])` where `opts = { x: 1 }` is created inline in render — a new reference every render — so the memo *never* hits and silently does nothing. The fix is to memoize on the **stable primitive/reference inputs** (as reselect does by tracking each input selector's output) rather than on a composite object literal. Reselect's default cache size of 1 is also load-bearing: it assumes you call the selector with one consistent state tree, so alternating between two states thrashes the cache (hence parameterized selectors need per-instance memoization).

- **Time:** O(inputs) comparison per call + O(combiner) on miss. **Space:** O(1) (cache size 1) or O(k) for an LRU-backed variant.
- **Edge cases:** new object args defeating the cache (the core lesson), `NaN` inputs (use `Object.is` if relevant), and cache-size-1 thrashing across alternating inputs.

#### Q120. [Coding] Implement `Function.prototype.bind` fully, including support for `new` on a bound function and partial application.

**Problem:** A faithful `bind` polyfill must do more than fix `this`: it must support **partial application** (pre-filling leading arguments), and when the bound function is used as a **constructor** with `new`, the bound `this` must be *ignored* in favor of the newly created instance — a spec detail almost everyone misses.

```javascript
Function.prototype.myBind = function (boundThis, ...boundArgs) {
  if (typeof this !== 'function') throw new TypeError('Bind must be called on a function');
  const targetFn = this;

  function bound(...callArgs) {
    // If invoked with `new`, `this instanceof bound` is true: use the new instance,
    // NOT boundThis. Otherwise it's a normal call: use boundThis.
    const isConstruct = this instanceof bound;
    return targetFn.apply(isConstruct ? this : boundThis, [...boundArgs, ...callArgs]);
  }

  // Preserve the prototype chain so `instanceof` works on `new bound()`.
  if (targetFn.prototype) {
    bound.prototype = Object.create(targetFn.prototype);
  }
  return bound;
};

// Partial application
const add = (a, b, c) => a + b + c;
console.log(add.myBind(null, 1, 2)(3)); // 6

// `new` on a bound constructor ignores boundThis
function Point(x, y) { this.x = x; this.y = y; }
const BoundPoint = Point.myBind({ ignored: true }, 10);
const p = new BoundPoint(20);
console.log(p.x, p.y);              // 10 20
console.log(p instanceof Point);   // true — prototype chain preserved
```

The `this instanceof bound` check is the linchpin: when you call `new bound()`, the engine creates a fresh object whose prototype is `bound.prototype`, so inside `bound`, `this instanceof bound` is `true` and we route to that new object instead of the captured `boundThis`. Recreating `bound.prototype` from `targetFn.prototype` ensures `new BoundPoint() instanceof Point` holds. The leading `boundArgs` concatenated with `callArgs` gives partial application for free.

- **Time/Space:** O(1) per call + O(boundArgs) closure. **Edge cases:** binding a non-function (throws), `new` ignoring bound `this`, `instanceof` across the bound function, and that the native `bind` produces a function with `length` reduced by the number of bound args and no `prototype` of its own (minor deviations in most polyfills).

#### Q121. [Theory] Explain the exact ordering guarantees and pitfalls when mixing `await`, `queueMicrotask`, `setTimeout`, and synchronous code inside a single function. Trace a non-trivial example.

This question separates people who memorized "microtasks before macrotasks" from those who can trace real interleaving. The rules: synchronous code in the current call runs to completion first; each `await` (and each `.then`/`queueMicrotask`) schedules its continuation as a **microtask**; the entire microtask queue drains before the **next macrotask** (`setTimeout`, message events) and before rendering. Crucially, every `await` introduces *at least one* microtask tick even when awaiting a non-promise value, because the spec wraps the value and resumes on the microtask queue.

```javascript
async function demo() {
  console.log('A');                              // sync
  setTimeout(() => console.log('timeout'), 0);   // macrotask
  await null;                                    // await of non-promise -> 1 microtask tick
  console.log('B');                              // microtask (continuation 1)
  queueMicrotask(() => console.log('mt'));       // microtask queued AFTER B's tick
  await Promise.resolve();                        // another microtask tick
  console.log('C');                              // microtask (continuation 2)
}
console.log('start');
demo();
console.log('end');
// Order: start, A, end, B, mt, C, timeout
```

The trace: `start` and `A` are synchronous; `demo()`'s first `await` suspends it and returns control, so `end` prints. The microtask queue then drains: the continuation after `await null` prints `B`, which queues `mt`; the second `await` schedules continuation 2; the queue continues draining, so `mt` runs, then `C`. Only after the microtask queue is fully empty does the event loop take the `setTimeout` macrotask and print `timeout`. The senior-level pitfalls: (1) an unbounded chain of microtasks (e.g., a recursive `queueMicrotask` or `await` loop) **starves** macrotasks and freezes rendering forever — the loop never gets to a paint; (2) people assume `await somethingSync()` is "free" and synchronous, but it always defers the rest of the function by a tick, which can reorder code relative to surrounding synchronous statements and cause subtle race conditions in tests.

#### Q122. [Coding] Implement a generic `pollUntil(predicate, { interval, timeout, backoff })` and discuss why polling needs jitter, caps, and an abort path.

**Problem:** Many real systems lack push notifications, so you must poll a status endpoint until a condition holds (a job finishes, a resource becomes ready). A naive `setInterval` loop ignores in-flight overlap, has no timeout, and hammers the server. A robust poller awaits each check, applies backoff with a cap, and supports cancellation.

```javascript
const sleep = (ms, signal) => new Promise((resolve, reject) => {
  const id = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => { clearTimeout(id); reject(signal.reason); }, { once: true });
});

async function pollUntil(predicate, {
  interval = 1000, maxInterval = 15000, backoff = 1.5, timeout = 60000, signal,
} = {}) {
  const deadline = Date.now() + timeout;
  let delay = interval;
  while (true) {
    if (signal?.aborted) throw signal.reason;
    const result = await predicate();              // await: no overlapping in-flight checks
    if (result) return result;                     // truthy result == done
    if (Date.now() >= deadline) throw new Error('pollUntil timed out');
    const jitter = Math.random() * (delay * 0.2);  // ±20% jitter
    await sleep(Math.min(delay + jitter, maxInterval), signal);
    delay = Math.min(delay * backoff, maxInterval); // exponential, capped
  }
}

// Usage
const ac = new AbortController();
const job = await pollUntil(async () => {
  const { status } = await checkJob(jobId);
  return status === 'complete' ? status : null;
}, { interval: 500, timeout: 30000, signal: ac.signal });
```

Awaiting each `predicate()` (rather than firing on a fixed `setInterval`) guarantees **no overlapping requests** — if one check takes 3 s, the next starts only after it resolves, preventing a pileup against a slow server. **Backoff** reduces load as waits lengthen; the **cap** (`maxInterval`) keeps latency bounded so you don't end up polling once an hour. **Jitter** decorrelates many clients so they don't all poll on the same tick and create synchronized thundering-herd spikes against a recovering service. The **deadline** prevents infinite polling, and the **`AbortSignal`** threaded into `sleep` makes the wait itself cancelable (otherwise an abort during a 15 s sleep wouldn't take effect until the sleep ended).

- **Time:** bounded by `timeout`. **Space:** O(1).
- **Edge cases:** abort during a long sleep (handled by wiring the signal into `sleep`), predicate throwing (propagates — wrap if transient errors should be tolerated), and clock skew affecting the deadline.

#### Q123. [Coding] Implement a binary search and its `lowerBound`/`upperBound` variants correctly, and explain the off-by-one traps that make most hand-written binary searches buggy.

**Problem:** Plain binary search finds *an* index of a target; the more useful variants find the **insertion point** — `lowerBound` (first index ≥ target) and `upperBound` (first index > target) — which power range queries, `Array.prototype.splice`-based insertion into sorted arrays, and the offset arrays used by variable-height virtualization. Binary search is notorious for off-by-one bugs; the half-open-interval discipline below eliminates them.

```javascript
// Standard: returns an index of `target`, or -1.
function binarySearch(arr, target) {
  let lo = 0, hi = arr.length - 1;             // inclusive bounds
  while (lo <= hi) {
    const mid = lo + ((hi - lo) >> 1);         // avoids (lo+hi) overflow; >>1 floors
    if (arr[mid] === target) return mid;
    if (arr[mid] < target) lo = mid + 1;
    else hi = mid - 1;
  }
  return -1;
}

// lowerBound: first index where arr[i] >= target (half-open [lo, hi)).
function lowerBound(arr, target) {
  let lo = 0, hi = arr.length;                 // hi is EXCLUSIVE here
  while (lo < hi) {
    const mid = lo + ((hi - lo) >> 1);
    if (arr[mid] < target) lo = mid + 1;       // mid too small -> discard left half incl. mid
    else hi = mid;                             // arr[mid] >= target -> keep mid as candidate
  }
  return lo;                                   // 0..length (length means "all smaller")
}
function upperBound(arr, target) {
  let lo = 0, hi = arr.length;
  while (lo < hi) {
    const mid = lo + ((hi - lo) >> 1);
    if (arr[mid] <= target) lo = mid + 1;      // <= : skip equal elements too
    else hi = mid;
  }
  return lo;
}

const a = [1, 2, 2, 2, 5, 8];
console.log(binarySearch(a, 5));  // 4
console.log(lowerBound(a, 2));    // 1  (first 2)
console.log(upperBound(a, 2));    // 4  (one past the last 2)
console.log(upperBound(a, 2) - lowerBound(a, 2)); // 3 == count of 2s
```

Three traps cause most bugs. (1) **Mid computation:** `(lo + hi) / 2` can overflow in fixed-width languages and isn't floored in JS — `lo + ((hi - lo) >> 1)` is overflow-safe and integer-flooring. (2) **Interval convention:** mixing inclusive `hi = length - 1` with exclusive updates (or vice versa) produces infinite loops or skipped elements. The bound functions above use a **half-open `[lo, hi)`** interval consistently (`hi = length`, loop `while (lo < hi)`, never `mid - 1`), which is the most robust convention. (3) **The `<` vs `<=` choice** is the *only* difference between `lowerBound` and `upperBound`, and their difference yields the count of equal elements — a neat invariant that confirms correctness.

- **Time:** O(log n). **Space:** O(1). **Edge cases:** empty array (returns 0 / -1 cleanly), target smaller than all (returns 0) or larger than all (returns `length`), and duplicate runs (the whole point of the two bounds).

#### Q124. [Coding] Implement an iterative graph traversal (BFS/DFS) with cycle detection over an adjacency structure, and explain why recursion risks a stack overflow in JS.

**Problem:** Traverse a directed graph (dependency graph, social network, DOM-like tree) visiting each node once. Recursive DFS is elegant but blows the call stack on deep or large graphs; iterative versions with an explicit stack/queue and a `visited` set are production-safe.

```javascript
// graph: Map<node, node[]>  (adjacency list)
function bfs(graph, start) {
  const visited = new Set([start]);
  const queue = [start];                       // FIFO
  const order = [];
  while (queue.length) {
    const node = queue.shift();                // (use a head pointer for O(1) in hot paths)
    order.push(node);
    for (const next of graph.get(node) ?? []) {
      if (!visited.has(next)) { visited.add(next); queue.push(next); }
    }
  }
  return order;
}

function dfsIterative(graph, start) {
  const visited = new Set();
  const stack = [start];                        // LIFO
  const order = [];
  while (stack.length) {
    const node = stack.pop();
    if (visited.has(node)) continue;            // mark on pop; cheap dedup of re-pushed nodes
    visited.add(node);
    order.push(node);
    const neighbors = graph.get(node) ?? [];
    for (let i = neighbors.length - 1; i >= 0; i--) stack.push(neighbors[i]); // preserve order
  }
  return order;
}

const g = new Map([
  ['a', ['b', 'c']], ['b', ['d']], ['c', ['d', 'a']], ['d', []], // c->a is a cycle
]);
console.log(bfs(g, 'a'));          // ['a','b','c','d']
console.log(dfsIterative(g, 'a')); // ['a','b','d','c'] (order depends on push direction)
```

The `visited` set is what makes this safe on **cyclic** graphs — the `c -> a` edge would otherwise loop forever. Marking nodes as visited (on enqueue for BFS, on pop for DFS) ensures each node is processed exactly once, giving O(V + E). The reason to prefer iteration over recursive DFS in JavaScript is the **call-stack limit**: engines cap recursion depth (typically a few thousand to ~10–15k frames), and JS has no guaranteed tail-call elimination (only Safari implements proper tail calls), so a deep dependency chain or a long linked path triggers `RangeError: Maximum call stack size exceeded`. An explicit stack lives on the **heap**, which is bounded by available memory, not the much smaller call-stack limit — so it scales to arbitrarily deep graphs.

- **Time:** O(V + E). **Space:** O(V) for `visited` + frontier. **Edge cases:** disconnected components (iterate all roots), self-loops, cycles (the `visited` guard), and `Array.prototype.shift` being O(n) — use a ring buffer or head index for very large BFS frontiers.

#### Q125. [Coding] Implement `deepFreeze` and a copy-on-write wrapper, and explain the difference between immutability by convention, by `Object.freeze`, and by `Proxy`.

**Problem:** Enforce immutability so accidental mutation of shared state throws (in strict mode) instead of silently corrupting data. `Object.freeze` is shallow; true protection requires recursive freezing, and a `Proxy` can give copy-on-write semantics where reads pass through but writes produce a new version.

```javascript
function deepFreeze(obj) {
  if (obj === null || typeof obj !== 'object' || Object.isFrozen(obj)) return obj;
  // Freeze children first to avoid revisiting (and to handle cycles via isFrozen).
  for (const key of Reflect.ownKeys(obj)) deepFreeze(obj[key]);
  return Object.freeze(obj);
}

const config = deepFreeze({ db: { host: 'x', ports: [5432] } });
// 'use strict' assumed (modules/classes are strict): the next line THROWS.
try { config.db.host = 'evil'; } catch (e) { console.log(e.message); } // Cannot assign...

// Copy-on-write: reads pass through; a write returns a NEW object, original untouched.
function copyOnWrite(target) {
  return new Proxy(target, {
    set(t, key, value) {
      throw new TypeError(`Immutable: use produce() to update "${String(key)}"`);
    },
    get(t, key, receiver) {
      const v = Reflect.get(t, key, receiver);
      return (v && typeof v === 'object') ? copyOnWrite(v) : v; // freeze deeply via proxy
    },
  });
}
```

The three immutability strategies trade off cost and guarantees. **By convention** (just "don't mutate", enforced by lint rules / TypeScript `readonly`) is zero runtime cost but provides *no* runtime safety — a single rogue `.push` slips through. **`Object.freeze`** gives real runtime enforcement (writes throw in strict mode, silently fail in sloppy mode) but is **shallow**, so `deepFreeze` must recurse; the cost is paid once at freeze time and frozen objects can also miss some V8 fast paths. **`Proxy`-based** immutability (the foundation of Immer's `produce`) intercepts writes lazily and can implement **structural-sharing copy-on-write** — you "mutate" a draft and get back a new immutable tree with unchanged branches shared — at the cost of per-access proxy overhead. The senior takeaway: freeze for *defensive guarantees on shared constants*, use Proxy/Immer for *ergonomic immutable updates of large trees*, and rely on TypeScript `readonly` for *compile-time intent* with no runtime cost.

- **Edge cases:** `deepFreeze` on cyclic structures (the `Object.isFrozen` short-circuit prevents infinite recursion), frozen objects still allowing mutation of non-own inherited state, and sloppy-mode silent failure (always run strict).

#### Q126. [Coding] Implement an LRU cache with TTL expiry and explain how it composes the recency and freshness policies without leaking.

**Problem:** Extend a plain LRU with **time-to-live** so entries expire by age *and* by capacity pressure. This is the realistic shape of an API/response cache: you want both "evict the least-recently-used when full" and "never serve data older than N ms." Composing the two policies correctly — without ever leaking expired-but-not-yet-evicted entries — is the senior nuance.

```javascript
class TTLCache {
  constructor({ capacity = 100, ttl = 60000 } = {}) {
    this.capacity = capacity;
    this.ttl = ttl;
    this.map = new Map();                       // key -> { value, expires }
  }

  get(key) {
    const entry = this.map.get(key);
    if (!entry) return undefined;
    if (Date.now() > entry.expires) {           // lazy expiry on read
      this.map.delete(key);
      return undefined;
    }
    this.map.delete(key);                       // refresh recency (re-insert = most recent)
    this.map.set(key, entry);
    return entry.value;
  }

  set(key, value) {
    if (this.map.has(key)) this.map.delete(key);
    else if (this.map.size >= this.capacity) this._evictOldest();
    this.map.set(key, { value, expires: Date.now() + this.ttl });
  }

  _evictOldest() {
    // Prefer evicting an already-expired entry; else evict the LRU (first key).
    for (const [k, e] of this.map) {
      if (Date.now() > e.expires) { this.map.delete(k); return; }
      break;                                    // first (oldest) non-expired -> that's the LRU
    }
    this.map.delete(this.map.keys().next().value);
  }
}
```

The composition has a subtle correctness point: **TTL alone does not bound memory**, because an entry that is never read again sits in the `Map` past its expiry forever (lazy expiry only fires on access). The LRU capacity is therefore the actual memory bound — TTL governs *freshness*, capacity governs *size*. Eviction first tries to reclaim an expired entry (free, and frees stale memory sooner) before evicting a live LRU entry. For caches with low read rates you'd add **active** sweeping (a periodic timer or a min-heap keyed by expiry) so memory is reclaimed promptly rather than only on access — but a timer keeps the cache object alive, so in long-lived processes you `unref()` it (Node) or tie it to a lifecycle to avoid a leak.

- **Time:** O(1) `get`/`set` (eviction's expired-scan is amortized O(1) in practice). **Space:** O(capacity).
- **Edge cases:** clock skew/`Date.now` going backwards, refreshing recency on read (controversial — some caches don't, treating reads as non-promoting), TTL of 0 / Infinity, and the timer-keeps-object-alive leak with active sweeping.

#### Q127. [Theory] Walk through, with code, the most subtle `this`/closure/hoisting bugs that show up in real codebases, and the precise mechanism behind each.

Beyond textbook examples, four bugs recur in production and reveal whether someone truly understands the runtime. First, the **stale-closure / loop-`var`** bug, now most visible in React: `useEffect(() => { const id = setInterval(() => setCount(count + 1), 1000); }, [])` increments to 1 and sticks, because the interval callback closes over the `count` from the first render (the effect ran once, capturing that binding); the fix is the functional updater `setCount(c => c + 1)` so it doesn't read the captured value.

```javascript
// 1. Lost `this` when destructuring or passing a method
class Api { constructor() { this.base = '/v1'; } get(p) { return this.base + p; } }
const api = new Api();
const { get } = api;
// get('/users');  // TypeError: cannot read 'base' of undefined — `this` is undefined (strict)
const bound = api.get.bind(api);            // fix: bind, or use a class field arrow

// 2. Arrow as a method loses the object as `this`
const counter = { n: 0, inc: () => { this.n++; } }; // `this` is module/undefined, NOT counter
counter.inc(); console.log(counter.n);      // 0 — never touched counter

// 3. Function-declaration hoisting shadowing inside a block
function f() {
  console.log(typeof g);                     // 'function' — declaration hoisted to top of f
  { function g() {} }                        // block-scoped fn decl: messy, engine-dependent
}
```

The second bug — an **arrow function used as an object method** — fails because arrows have no own `this` and inherit it lexically from the *defining* scope (module top-level or `undefined`), never from the calling object; the rule "method = regular function, callback = arrow" prevents it. The third — losing `this` when a method is **detached** (`const { get } = api` or `setTimeout(api.get, 0)`) — happens because method binding is established only at the *call site* via the receiver, and detaching removes the receiver, so default binding (`undefined` in strict mode) applies. The fourth involves **hoisting interactions**: a `var` declared inside a block is hoisted to the function top (so it shadows an outer same-named variable for the *entire* function, returning `undefined` before assignment), and function declarations inside blocks have historically inconsistent scoping. The unifying mechanism is that `this` is resolved dynamically by call-site (except arrows), closures capture *bindings* not *values* (so later mutations are visible, and captured values can be stale relative to re-execution), and hoisting reflects the two-phase creation/execution of an execution context.

#### Q128. [Behavioral] Tell me about a time you had to make a difficult architectural call on a large JavaScript codebase under conflicting constraints, and how you drove the decision.

**Situation (STAR):** "We maintained a 400k-line JavaScript SPA that had grown organically with mixed module systems (some CommonJS, some early ESM), a homegrown state layer, and a 9 MB main bundle. Initial load was over 12 seconds on mid-tier devices, churn was rising, and two senior engineers strongly disagreed on the path: one wanted an incremental refactor in place, the other a from-scratch rewrite on a modern framework." **Task:** "As the staff engineer, I owned producing a decision the whole team would commit to, balancing user-facing performance, a hard product roadmap (we couldn't pause feature work for a quarter), and team morale after a previous failed rewrite."

**Action:** "I refused to let it be a vibes debate. I instrumented the real cost: RUM data showed the bundle size and the synchronous state-hydration step were the dominant LCP/INP contributors, not the framework. I prototyped both paths for two weeks against the *same* measured bottleneck — the rewrite delivered a clean architecture but couldn't ship incrementally and reintroduced the very migration risk that had burned us before, while a **strangler-fig** approach (route-level code-splitting with dynamic `import()`, an ESM-first build via `\"type\": \"module\"` and conditional `exports`, and migrating state slice-by-slice behind a stable facade) cut the critical-path bundle to 1.4 MB in the prototype. I wrote a one-page decision doc with the numbers, the risks of each, and an explicit reversibility analysis, then ran a design review where both advocates could challenge the data rather than each other. I deliberately gave the rewrite advocate ownership of defining the target architecture that the incremental path would converge toward, so the decision wasn't a loss for anyone."

**Result:** "We shipped the incremental migration: LCP dropped from ~12 s to ~3.5 s within two release cycles, we never paused the roadmap, and we caught regressions with a Lighthouse CI budget. Eighteen months later ~80% of the codebase had reached the target architecture without a big-bang cutover." **Reflection:** "The leadership lesson I emphasize is that the hardest part wasn't technical — it was converting a polarized opinion fight into a measured, reversible decision, and structuring ownership so the dissenting engineer was invested in the outcome. I now default to 'instrument before you argue,' time-boxed prototypes against the *measured* bottleneck, and written decision docs with explicit reversibility, because architectural calls at that scale are judged as much by how the team commits to them as by the technical merits."

## ✅ Key Takeaways

- **Scope & hoisting:** `let`/`const` are block-scoped with a TDZ; `var` is function-scoped and pre-initialized to `undefined`. Prefer `const` by default.
- **Closures** capture their lexical environment — the basis of privacy, factories, and React hooks, but also a leak source if held too long.
- **`this`** is set by call-site (`new` > explicit > implicit > default); arrow functions inherit `this` lexically.
- **Prototype chain** drives inheritance; `class` is sugar over it. Never mutate built-in prototypes.
- **Event loop:** sync stack runs to completion → drain *all* microtasks → take *one* macrotask. Promises/`await` are microtasks; `setTimeout` is a macrotask.
- **Async patterns:** `Promise.all` (fail-fast), `allSettled` (resilient), `race` (timeouts), `any` (first success). Run independent awaits concurrently with `Promise.all`.
- **Copies:** prefer `structuredClone()`; understand shallow vs. deep and circular-reference handling.
- **Performance:** keep the main thread free (virtualize, throttle/debounce, Web Workers); write monomorphic, consistently-shaped objects for V8.
- **Security:** guard against XSS (CSP/Trusted Types), prototype pollution (`Object.create(null)`/`Map`), and supply-chain risk.

## ⚠️ Common Pitfalls

- Using `var` in loops with async callbacks → all callbacks share one variable (`let` fixes it).
- Assuming `setTimeout(fn, 0)` runs before Promise callbacks — microtasks always win.
- `JSON.parse(JSON.stringify(x))` for deep clone — silently drops `undefined`/functions/symbols, breaks `Date` and circular refs.
- Losing `this` when passing a class method as a callback without binding.
- Sequential `await` in a loop for independent calls — needlessly serializes latency.
- Forgetting to remove event listeners / clear timers → detached-DOM and closure leaks.
- Awaiting fire-and-forget Promises without `.catch` → unhandled rejections.
- Relying on `WeakRef`/`FinalizationRegistry` timing for correctness — GC is non-deterministic.
- Mutating `Array.prototype`/`Object.prototype` — global breakage and `for...in` pollution.
- Comparing with `==` and getting bitten by coercion (`[] == ![]` is `true`).

## 📚 Further Reading

- **You Don't Know JS Yet** (2nd ed.) by Kyle Simpson — the definitive deep dive on scope, closures, `this`, and types.
- **MDN Web Docs — JavaScript Reference & Guide** ([developer.mozilla.org](https://developer.mozilla.org/en-US/docs/Web/JavaScript)) — authoritative, version-accurate API and semantics.
- **ECMAScript Language Specification (ECMA-262)** ([tc39.es/ecma262](https://tc39.es/ecma262/)) — the source of truth for behavior, plus TC39 proposals for upcoming features.
- **"What the heck is the event loop anyway?"** by Philip Roberts (JSConf talk) — the clearest mental model of the loop.
- **V8 Blog** ([v8.dev/blog](https://v8.dev/blog)) — hidden classes, inline caches, and JIT/GC internals straight from the engine team.
- **JavaScript: The Definitive Guide** (7th ed.) by David Flanagan — comprehensive coverage of the modern language and standard library.
