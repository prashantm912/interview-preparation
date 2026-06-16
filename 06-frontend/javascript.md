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
