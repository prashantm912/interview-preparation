# Node.js Backend

[← Back to master index](../README.md)

Node.js is a server-side JavaScript runtime built on V8 and libuv that pairs a single-threaded event loop with non-blocking I/O to handle high concurrency without a thread-per-request model. This deep-dive covers the runtime internals (event-loop phases, microtasks vs. macrotasks, libuv and its thread pool), the asynchronous programming model (callbacks, promises, async/await, streams, EventEmitter), the concurrency story (cluster, `worker_threads`, CPU-bound pitfalls), the module systems (CommonJS vs. ESM), and production concerns (Express middleware, error handling, memory leaks, security, graceful shutdown) at a depth suitable for interviews from junior through staff level. All content is current to Node.js 22/24 LTS (2026).

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is Node.js and what problem does it solve?

Node.js is a runtime that executes JavaScript outside the browser, built on Google's **V8** engine for JS execution and **libuv** for the event loop and asynchronous I/O. Its core proposition is **non-blocking, event-driven I/O on a single main thread**.

Traditional servers (e.g., classic Apache + PHP) spawn one thread or process per connection. Each thread consumes memory (often 1–8 MB of stack) and the OS spends time context-switching between them. Under thousands of concurrent connections this becomes the bottleneck.

Node flips the model: a single thread runs an **event loop**. When you start an I/O operation (read a file, query a database, fetch a URL), Node hands it off to the OS or to libuv's thread pool and immediately moves on to other work. When the operation completes, a callback is queued and run. This makes Node excellent for **I/O-bound, high-concurrency** workloads (APIs, proxies, real-time apps) where each request spends most of its time waiting on I/O rather than burning CPU.

```
Request ─► start I/O ─► (thread free to handle other requests) ─► I/O done ─► callback runs
```

The trade-off: because everything shares one main thread, **CPU-bound work blocks everyone**.

### Q2. [Theory] What is the event loop?

The event loop is the mechanism that lets a single-threaded runtime perform non-blocking I/O. It is an infinite loop (implemented by libuv) that, on each iteration ("tick"), processes a series of **phases** in a fixed order. Each phase has a FIFO queue of callbacks to execute.

The simplified flow per tick:

```
   ┌───────────────────────────┐
┌─►│           timers          │  setTimeout / setInterval callbacks due
│  ├───────────────────────────┤
│  │     pending callbacks     │  deferred I/O callbacks (e.g. some TCP errors)
│  ├───────────────────────────┤
│  │       idle, prepare       │  internal use only
│  ├───────────────────────────┤   ┌──────────────┐
│  │           poll            │◄──┤ incoming I/O │  retrieve completed I/O events,
│  ├───────────────────────────┤   └──────────────┘  run their callbacks (may block here)
│  │           check           │  setImmediate callbacks
│  ├───────────────────────────┤
│  │      close callbacks      │  e.g. socket.on('close')
└──┴───────────────────────────┘
```

Between every phase (and between individual macrotask callbacks), Node drains the **microtask queues**: first `process.nextTick`, then the promise/`queueMicrotask` queue. So the loop is event-driven: it sleeps in the **poll** phase when there is nothing to do and wakes when the OS reports completed I/O.

### Q3. [Theory] Is Node.js single-threaded or multi-threaded?

Both, depending on what you mean.

- **Your JavaScript runs on a single thread** — the main thread that hosts the event loop. There is exactly one place your application code executes (per process), which is why two lines of synchronous JS never run truly in parallel.
- **Node itself is multi-threaded under the hood.** libuv maintains a **thread pool** (default 4 threads) used for operations that have no async OS primitive: file system calls, DNS lookups via `getaddrinfo`, and CPU-heavy crypto/zlib. The OS kernel also handles network I/O asynchronously without using the pool.

So "single-threaded" refers to your JS execution model, not the whole process. You can also opt into real JS parallelism with **worker threads** or multiple processes via **cluster**.

### Q4. [Theory] What is the difference between blocking and non-blocking code?

**Blocking** code halts the event loop until the operation completes — nothing else (no other request, timer, or callback) can run in the meantime. Synchronous APIs (`fs.readFileSync`, `crypto.pbkdf2Sync`, a tight `for` loop) block.

**Non-blocking** code initiates the operation and returns immediately; the result arrives later via a callback, promise, or event. The event loop is free to do other work while waiting.

```js
// Blocking — the whole process waits here
const data = fs.readFileSync('big.log');   // nothing else runs until done
console.log('after');

// Non-blocking — returns immediately, callback runs later
fs.readFile('big.log', (err, data) => {
  console.log('file ready');
});
console.log('after');  // prints BEFORE 'file ready'
```

Rule of thumb: avoid `*Sync` APIs anywhere on the hot path of a server. They are fine in CLI scripts or one-time startup code.

### Q5. [Theory] What is a callback, and what is "callback hell"?

A callback is a function passed to another function to be invoked later — Node's original async pattern. Node uses the **error-first callback** convention: the first argument is an error (or `null`), and results follow.

```js
fs.readFile('a.txt', (err, data) => {
  if (err) return handle(err);
  // use data
});
```

"Callback hell" (the "pyramid of doom") is what happens when you nest dependent async operations, leading to deeply indented, hard-to-read, hard-to-error-handle code:

```js
getUser(id, (e, user) => {
  getOrders(user, (e, orders) => {
    getItems(orders, (e, items) => {
      // ...growing rightward
    });
  });
});
```

Error handling has to be repeated at every level, and control flow is hard to follow. Promises and `async/await` were introduced largely to solve this.

### Q6. [Practical] How do promises and async/await improve on callbacks?

A **promise** is an object representing the eventual result of an async operation, with states `pending → fulfilled | rejected`. Promises let you **chain** instead of nest, and `.catch()` handles errors for an entire chain in one place.

```js
getUser(id)
  .then(user => getOrders(user))
  .then(orders => getItems(orders))
  .then(items => render(items))
  .catch(err => handle(err));   // one handler for any step
```

`async/await` is syntactic sugar over promises that lets you write asynchronous code that *reads* synchronously. An `async` function always returns a promise; `await` pauses the function (not the event loop) until the awaited promise settles.

```js
async function loadPage(id) {
  try {
    const user   = await getUser(id);
    const orders = await getOrders(user);
    const items  = await getItems(orders);
    return render(items);
  } catch (err) {
    handle(err);          // try/catch works across all awaits
  }
}
```

Benefits: linear control flow, `try/catch` for errors, easy use of loops and conditionals, and the same value semantics. Under the hood `await` still schedules a microtask — it never blocks the event loop.

### Q7. [Coding] Run independent async operations concurrently instead of sequentially.

Awaiting in sequence when the operations don't depend on each other wastes time. Use `Promise.all` to run them concurrently and wait for all to finish.

```js
// ❌ Sequential: total time ≈ t1 + t2 + t3
const a = await fetchA();
const b = await fetchB();
const c = await fetchC();

// ✅ Concurrent: total time ≈ max(t1, t2, t3)
const [a, b, c] = await Promise.all([fetchA(), fetchB(), fetchC()]);
```

Variants:
- `Promise.allSettled` — waits for all, never short-circuits; returns `{status, value|reason}` for each. Use when you want every result regardless of individual failures.
- `Promise.race` — settles as soon as the first promise settles (useful for timeouts).
- `Promise.any` — resolves with the first *fulfilled* value, ignoring rejections until all fail.

Note: with `Promise.all`, one rejection rejects the whole thing immediately, but the other promises keep running in the background — handle that if they have side effects.

### Q8. [Theory] What is `package.json` and what do dependencies vs. devDependencies mean?

`package.json` is the manifest at a project's root. It declares metadata (name, version, type), scripts, and dependencies. Key fields:

- `"dependencies"` — packages required at **runtime** in production (e.g., `express`).
- `"devDependencies"` — packages needed only during development/build/test (e.g., `jest`, `eslint`, `typescript`). `npm install --omit=dev` (or `--production`) skips these.
- `"scripts"` — named commands runnable via `npm run <name>` (`start`, `test`, etc.).
- `"type"` — `"commonjs"` (default) or `"module"` to make `.js` files ESM.
- `"engines"` — declares supported Node versions.

`package-lock.json` pins the exact resolved versions of the whole dependency tree for reproducible installs; commit it. Use `npm ci` in CI for clean, lockfile-faithful installs.

### Q9. [Theory] What is the difference between `npm install` and `npm ci`?

- `npm install` resolves dependencies against the ranges in `package.json`, may **update** `package-lock.json`, and installs into an existing `node_modules`. It's what you run during development when adding/changing packages.
- `npm ci` ("clean install") installs **strictly** from `package-lock.json`, fails if the lockfile and `package.json` are out of sync, and **deletes `node_modules`** first for a clean, deterministic install. It's faster and reproducible — the right choice for CI/CD and production builds.

### Q10. [Practical] How do you read an environment variable, and why use them?

`process.env` exposes environment variables as a string-keyed object.

```js
const port = process.env.PORT || 3000;
const dbUrl = process.env.DATABASE_URL;
if (!dbUrl) throw new Error('DATABASE_URL is required');
```

Environment variables keep configuration and secrets **out of source code**, following the 12-factor app principle. Locally, tools like `dotenv` load a `.env` file (which must be git-ignored); modern Node (20.6+) can load it natively with `node --env-file=.env app.js`. Always validate required vars at startup and fail fast if they're missing.

### Q11. [Theory] What is the difference between `setTimeout`, `setImmediate`, and `process.nextTick`?

They schedule callbacks at different points relative to the event loop:

- **`process.nextTick(fn)`** — runs `fn` **before** the event loop continues, after the current operation completes, draining the entire nextTick queue before any promise microtask or loop phase. Highest priority; can starve the loop if used recursively.
- **`queueMicrotask` / promise `.then`** — microtask queue, drained right after nextTick, also between phases.
- **`setImmediate(fn)`** — runs in the **check** phase, i.e., after the current poll phase completes.
- **`setTimeout(fn, 0)`** — runs in the **timers** phase on a *future* tick, after at least the minimum delay.

```js
setTimeout(() => console.log('timeout'), 0);
setImmediate(() => console.log('immediate'));
process.nextTick(() => console.log('nextTick'));
Promise.resolve().then(() => console.log('promise'));
// Order: nextTick, promise, then timeout/immediate (relative order of the
// last two varies in the main module but is deterministic inside an I/O cycle)
```

Inside an I/O callback, `setImmediate` always fires before a `setTimeout(0)`, because you're already past timers and headed to check.

### Q12. [Theory] What is the difference between a macrotask and a microtask?

A **macrotask** (sometimes "task") is a unit of work scheduled into one of the event loop's phase queues: timer callbacks, I/O callbacks, `setImmediate`, close events. The loop runs **one macrotask, then drains all microtasks**, then the next macrotask.

A **microtask** is a higher-priority callback drained **completely** between macrotasks (and between loop phases). In Node, microtasks are `process.nextTick` callbacks (drained first) and then the promise/`queueMicrotask` queue.

```
[ macrotask ] → drain ALL microtasks → [ next macrotask ] → drain ALL microtasks → ...
```

The practical consequence: a flood of microtasks (e.g., a recursive `Promise.resolve().then(...)` or `process.nextTick`) can **starve** the event loop, preventing timers and I/O from ever running. Macrotasks yield between each other; microtasks do not.

### Q13. [Coding] Write a simple HTTP server with the built-in `http` module.

```js
const http = require('node:http');

const server = http.createServer((req, res) => {
  if (req.url === '/health' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
  }
});

server.listen(3000, () => console.log('listening on :3000'));
```

`createServer` takes a request handler called for every request; `req` is a readable stream, `res` is a writable stream. Most real apps use a framework (Express, Fastify) on top of this, but knowing the raw API clarifies what the frameworks abstract.

### Q14. [Theory] What is npm, and what is the `node_modules` folder?

**npm** (Node Package Manager) is the default registry and CLI for installing JavaScript packages. Alternatives include **yarn** and **pnpm** (pnpm uses a content-addressable store with hard links to save disk and speed installs).

`node_modules` is the local directory where installed dependencies live. Node's module resolution walks up from the current file looking for `node_modules` folders. It can grow huge and deep, so it's always git-ignored and rebuilt from the lockfile. pnpm notably uses a non-flat, symlinked layout to avoid the duplication and "phantom dependency" problems of npm's flattened tree.

### Q15. [Theory] What is the difference between `require` and `import`?

`require` is the **CommonJS (CJS)** mechanism; `import` is the **ES Modules (ESM)** mechanism.

- `require('x')` is **synchronous** and can be called anywhere (even conditionally inside a function). It returns `module.exports`.
- `import` is **static** (hoisted, declared at the top level), enables tree-shaking, and is the standard JavaScript module system shared with browsers. Dynamic `import('x')` returns a promise for conditional/lazy loading.

```js
// CommonJS
const fs = require('node:fs');
module.exports = { foo };

// ESM
import fs from 'node:fs';
export { foo };
```

A file is treated as ESM if it has a `.mjs` extension, or `.js` with `"type": "module"` in `package.json`. We cover the deeper interop story in the intermediate section.

## 🟡 Intermediate (3–7 yrs)

### Q16. [Theory] Walk through the event loop phases in detail.

libuv runs six phases per tick, each with its own callback queue:

1. **timers** — executes callbacks scheduled by `setTimeout`/`setInterval` whose threshold has elapsed.
2. **pending callbacks** — runs I/O callbacks deferred from a previous iteration (e.g., certain TCP socket errors like `ECONNREFUSED`).
3. **idle, prepare** — internal libuv bookkeeping.
4. **poll** — the heart of the loop: retrieves new I/O events and runs their callbacks. If there are no timers pending and no `setImmediate` callbacks queued, the loop **blocks here** waiting for I/O (this is how Node sleeps efficiently). If timers are due, it wraps around to the timers phase.
5. **check** — executes `setImmediate` callbacks.
6. **close callbacks** — handles `'close'` events (e.g., `socket.on('close', ...)`).

Crucially, **between each phase** (and after each individual callback within a phase), Node empties the **`process.nextTick` queue** and then the **microtask (promise) queue**. So microtasks interleave far more frequently than once per tick.

```
tick: timers → pending → idle/prepare → poll → check → close
       └─ after every callback: drain nextTick, then promises ─┘
```

### Q17. [Theory] What is libuv and what is the thread pool used for?

**libuv** is the C library that provides Node's event loop, asynchronous I/O abstractions, and the thread pool. It gives Node a uniform async API across platforms (epoll on Linux, kqueue on macOS, IOCP on Windows).

For operations the OS exposes asynchronously — primarily **network I/O** (TCP/UDP sockets) — libuv uses the kernel's event notification directly, no extra threads.

For operations with **no portable async kernel API**, libuv offloads to a **thread pool** (default size 4, set via `UV_THREADPOOL_SIZE`, max 1024). This includes:

- File system operations (`fs.*` — there's no good cross-platform async file API)
- DNS lookups via `dns.lookup` / `getaddrinfo`
- CPU-intensive `crypto` (pbkdf2, scrypt) and `zlib` (compression)

```
Network I/O   ──► kernel async (epoll/kqueue/IOCP), no pool thread
fs / dns / crypto / zlib ──► libuv thread pool (4 threads by default)
```

A common gotcha: if you fire 5 concurrent `crypto.pbkdf2` calls with a pool of 4, the 5th queues until a thread frees up. Tuning `UV_THREADPOOL_SIZE` matters for fs/crypto-heavy workloads.

### Q18. [Coding] Demonstrate and explain backpressure when piping streams.

**Backpressure** is the flow-control mechanism that prevents a fast producer from overwhelming a slow consumer. When you write to a writable stream and its internal buffer exceeds `highWaterMark`, `write()` returns `false`, signaling you to pause until the `'drain'` event.

`pipe()` (and `pipeline()`) handle backpressure automatically:

```js
const fs = require('node:fs');
const { pipeline } = require('node:stream/promises');
const zlib = require('node:zlib');

// pipeline manages backpressure AND propagates errors + cleans up
await pipeline(
  fs.createReadStream('big.log'),
  zlib.createGzip(),
  fs.createWriteStream('big.log.gz')
);
```

If you ignore backpressure by manually writing without honoring the return value, memory balloons:

```js
// ❌ ignores backpressure — buffers entire source in memory
readable.on('data', chunk => writable.write(chunk));

// ✅ respects it
readable.on('data', chunk => {
  if (!writable.write(chunk)) {
    readable.pause();
    writable.once('drain', () => readable.resume());
  }
});
```

Always prefer `pipeline()` over `pipe()` in production: `pipe()` does not forward errors or destroy the source on a downstream failure, which leaks file descriptors.

### Q19. [Theory] Explain the types of streams in Node.

Streams process data **incrementally** rather than loading it all into memory. Four base types:

- **Readable** — a source you read from (`fs.createReadStream`, an HTTP request, `process.stdin`).
- **Writable** — a sink you write to (`fs.createWriteStream`, an HTTP response, `process.stdout`).
- **Duplex** — both readable and writable, independent channels (a TCP socket).
- **Transform** — a duplex stream where output is a function of input (`zlib.createGzip`, a cipher, a CSV parser). What you write gets transformed and emerges on the readable side.

Streams operate in **object mode** or byte/string mode, and support two reading modes: **flowing** (data pushed via `'data'` events) and **paused** (pulled via `.read()`). Their big wins: constant memory usage regardless of data size, and composability via piping.

```
Readable ──pipe──► Transform ──pipe──► Writable
(file)            (gzip)              (file/socket)
```

### Q20. [Coding] Write a custom Transform stream.

```js
const { Transform } = require('node:stream');

// Uppercases every chunk of text passing through
const upper = new Transform({
  transform(chunk, encoding, callback) {
    try {
      this.push(chunk.toString().toUpperCase());
      callback();            // signal this chunk is done
    } catch (err) {
      callback(err);         // propagate errors
    }
  }
});

process.stdin.pipe(upper).pipe(process.stdout);
```

Key points: `transform(chunk, enc, cb)` is called per chunk; call `this.push(...)` to emit output (zero or more times) and `callback()` when done (or `callback(err)` on failure). Optionally implement `flush(cb)` to emit trailing data when the input ends (e.g., flushing a buffered line). Use object mode (`new Transform({ objectMode: true })`) to pass objects instead of buffers.

### Q21. [Theory] What is the EventEmitter, and how does it relate to the rest of Node?

`EventEmitter` (from `node:events`) is the foundation of Node's event-driven architecture. It implements the observer pattern: objects emit named events, and listeners registered with `.on()` are called synchronously in registration order when `.emit()` fires.

```js
const { EventEmitter } = require('node:events');
const bus = new EventEmitter();

bus.on('order', (id) => console.log('processing', id));
bus.once('ready', () => console.log('only once'));
bus.emit('order', 42);     // → processing 42
```

Many core objects *are* EventEmitters: streams (`'data'`, `'end'`, `'error'`), HTTP servers (`'request'`), sockets, the process object (`'exit'`, `'uncaughtException'`). Gotchas:

- Listeners run **synchronously** — a slow listener blocks the emitter.
- An `'error'` event with **no listener** throws and crashes the process — always handle `'error'`.
- Adding more than 10 listeners to one emitter logs a `MaxListenersExceededWarning` (a leak heuristic); raise it deliberately with `setMaxListeners` if needed.

### Q22. [Theory] Why is a CPU-bound task a problem in Node, and how do you fix it?

Because all your JS runs on one thread, a CPU-bound operation (image resizing, large JSON parsing, crypto, a long synchronous loop) **monopolizes the event loop**. While it runs, no other request is served, timers don't fire, and I/O callbacks queue up — the server appears frozen.

```js
// ❌ Blocks every other request for the duration
app.get('/report', (req, res) => {
  const result = heavySynchronousComputation();  // 2 seconds of CPU
  res.json(result);
});
```

Fixes, in order of preference:

1. **Offload to `worker_threads`** for true parallel CPU work within the process.
2. **Use a separate process / job queue** (e.g., BullMQ + Redis) so heavy work runs elsewhere.
3. **Use native async APIs** that offload to the libuv pool (e.g., async `crypto`, `zlib`).
4. **Chunk the work** and yield with `setImmediate` between chunks so the loop can breathe.
5. **Scale horizontally** with the cluster module to use more cores for many small requests.

The key insight: Node is for **I/O-bound** work; CPU-bound work needs to leave the main thread.

### Q23. [Theory] What is the cluster module and when do you use it?

A single Node process uses **one CPU core**. The `cluster` module forks the process into multiple **worker processes** that share the same server port (the primary process distributes incoming connections, round-robin by default on non-Windows). This lets a Node app utilize all cores for I/O-bound workloads.

```js
const cluster = require('node:cluster');
const os = require('node:os');

if (cluster.isPrimary) {
  for (let i = 0; i < os.availableParallelism(); i++) cluster.fork();
  cluster.on('exit', (worker) => cluster.fork()); // restart on crash
} else {
  require('./server');  // each worker runs the HTTP server
}
```

Each worker is a **separate process** with its own memory and event loop — they share nothing except the listening socket, so you need shared state (Redis, DB) and sticky sessions for WebSockets. In production, process managers like **PM2** or container orchestrators (one process per container, scaled by Kubernetes) often replace manual clustering.

### Q24. [Theory] What are worker threads and how do they differ from cluster?

`worker_threads` provides real multithreading **within a single process**. Each worker runs its own V8 isolate and event loop on a separate OS thread, but they can share memory via `SharedArrayBuffer` and communicate over a `MessagePort`.

```js
const { Worker } = require('node:worker_threads');
const worker = new Worker('./heavy-task.js', { workerData: { n: 1e9 } });
worker.on('message', (result) => console.log(result));
worker.on('error', console.error);
```

| | `cluster` | `worker_threads` |
|---|---|---|
| Unit | Separate **processes** | Threads in **one process** |
| Memory | Isolated per process | Can share via `SharedArrayBuffer` |
| Best for | Scaling **I/O-bound** servers across cores | **CPU-bound** tasks (parsing, crypto, compute) |
| Comms | IPC (slower, serialized) | `MessagePort` / shared memory (faster) |
| Overhead | Higher (full process) | Lower (thread) |

Use **cluster** to handle more concurrent connections; use **worker threads** to keep CPU-heavy computation off the main event loop. A worker **pool** (e.g., `Piscina`) is the standard way to reuse workers rather than paying startup cost per task.

### Q25. [Theory] Explain CommonJS vs. ESM, including interop.

| | CommonJS (CJS) | ES Modules (ESM) |
|---|---|---|
| Syntax | `require` / `module.exports` | `import` / `export` |
| Loading | Synchronous, runtime | Static, hoisted (async graph) |
| `this` at top level | `module.exports` | `undefined` |
| Available globals | `__dirname`, `__filename`, `require` | none by default (`import.meta.url`, `import.meta.dirname` in newer Node) |
| Tree-shaking | No | Yes |
| File detection | default | `.mjs`, or `"type":"module"` |

**Interop rules:**
- ESM can `import` a CJS module; its `module.exports` becomes the default export. Named exports are best-effort via static analysis.
- CJS historically **cannot** `require()` an ESM module — you had to use dynamic `import()`. As of Node 22+ (and default in 23+), `require()` of a synchronous ESM graph is supported, easing migration.
- ESM is now the ecosystem default; many packages ship ESM-only. Dual packages use the `"exports"` field with conditional `import`/`require` entry points.

### Q26. [Coding] Build an Express middleware chain with error handling.

```js
const express = require('express');
const app = express();

app.use(express.json());                 // body parser (built-in)

// Custom middleware: runs for every request
app.use((req, res, next) => {
  req.startTime = Date.now();
  next();                                 // pass control to the next middleware
});

app.get('/users/:id', async (req, res, next) => {
  try {
    const user = await db.getUser(req.params.id);
    if (!user) return res.status(404).json({ error: 'not found' });
    res.json(user);
  } catch (err) {
    next(err);                            // forward to the error handler
  }
});

// Error-handling middleware: FOUR args (err first) — must be last
app.use((err, req, res, next) => {
  console.error(err);
  res.status(err.status || 500).json({ error: 'internal error' });
});

app.listen(3000);
```

Middleware is the core Express abstraction: functions with the signature `(req, res, next)` executed in order. Calling `next()` proceeds; calling `next(err)` jumps to the error handler. **Error middleware is identified by its four parameters** and must be registered last. Note: in Express 4, errors thrown inside an `async` handler must be passed to `next()` manually; **Express 5** (now stable) automatically forwards rejected promises from async middleware to the error handler.

### Q27. [Theory] How does error handling differ for sync code, callbacks, promises, and async/await?

Each async style needs its own error mechanism:

- **Synchronous** — `try/catch` works directly.
- **Callbacks** — errors arrive as the first argument (error-first convention). `try/catch` will **not** catch them; you must check `err` in every callback.
- **Promises** — errors propagate as rejections; handle with `.catch()`. An unhandled rejection triggers the `'unhandledRejection'` event.
- **async/await** — `try/catch` works again (it wraps the awaited rejection), which is a major reason to prefer it.

```js
// callback — try/catch is useless here
fs.readFile('x', (err, data) => { if (err) handle(err); });

// async/await — try/catch works
try { const data = await fs.promises.readFile('x'); }
catch (err) { handle(err); }
```

A subtle trap: a `try/catch` around code that fires-and-forgets a promise won't catch that promise's rejection. And throwing inside a `setTimeout` callback escapes any surrounding `try/catch` because it runs on a later tick.

### Q28. [Practical] What happens on an uncaught exception or unhandled rejection?

- **`uncaughtException`** — a synchronous throw that no `try/catch` caught reaches the top of the stack. Node emits `process.on('uncaughtException')`; if unhandled, it prints the stack and exits with code 1. After this event the process is in an **undefined state** — you should log, attempt graceful cleanup, and **exit**, not resume.
- **`unhandledRejection`** — a rejected promise with no `.catch()`. Node emits `process.on('unhandledRejection')`. Since Node 15 the **default is to crash the process** (previously a warning), aligning rejections with exceptions.

```js
process.on('uncaughtException', (err) => {
  logger.fatal(err);
  // flush logs, then exit — do NOT keep serving
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  logger.error('unhandled rejection', reason);
  process.exit(1);
});
```

Best practice: treat these handlers as a **last-resort logger before a controlled exit**, and let a process manager restart you. Never use them as a substitute for proper local error handling.

### Q29. [Theory] What is the Buffer class and when do you need it?

A `Buffer` is a fixed-length chunk of binary data outside the V8 heap — Node's way of handling raw bytes (a subclass of `Uint8Array`). You need it for binary protocols, file contents, network packets, image processing, cryptography — anything that isn't text.

```js
const buf = Buffer.from('héllo', 'utf8');
console.log(buf.length);            // 6 (é is 2 bytes in UTF-8)
console.log(buf.toString('hex'));   // hex encoding
const alloc = Buffer.alloc(1024);   // zero-filled, safe
```

Critical safety point: use `Buffer.alloc(n)` (zero-filled) for new buffers. The legacy `new Buffer(n)` and `Buffer.allocUnsafe(n)` return **uninitialized** memory that may contain old data — a real security risk if exposed. `Buffer.from(...)` is the safe constructor for known data.

### Q30. [Coding] Implement a request timeout using `Promise.race`.

```js
function withTimeout(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('timeout')), ms);
  });
  return Promise.race([promise, timeout])
    .finally(() => clearTimeout(timer));   // avoid leaking the timer
}

// usage
const data = await withTimeout(fetch(url), 2000);
```

`Promise.race` settles with whichever input settles first. The `.finally(clearTimeout)` is essential — without it the timer keeps the event loop alive and can pile up under load. In modern Node you'd often prefer `AbortController` + `AbortSignal.timeout(ms)`, which actually **cancels** the underlying operation rather than just ignoring its result:

```js
const data = await fetch(url, { signal: AbortSignal.timeout(2000) });
```

### Q31. [Theory] What is the difference between `process.exit()` and letting the process exit naturally?

`process.exit(code)` terminates **immediately**. Any pending I/O — buffered `stdout`/`stderr` writes, in-flight log flushes, open DB connections — may be **truncated or lost**, because exit doesn't wait for the event loop to drain. This is a common cause of "my last log line is missing."

Letting the process exit naturally means the event loop empties (no more timers, sockets, or handles keeping it alive) and Node exits on its own with code 0. This is the clean path.

Best practice: instead of calling `process.exit()` to stop, **stop creating new work and let outstanding work finish** (close the server, drain connections), setting `process.exitCode` to signal the desired code. Reserve `process.exit(1)` for genuine fatal-error fast-fail after you've flushed what you can.

### Q32. [Practical] How do you handle and validate incoming request bodies safely?

Several layers:

1. **Parse with limits.** `express.json({ limit: '100kb' })` caps body size to prevent memory-exhaustion DoS. Without a limit, a huge payload can OOM the process.
2. **Validate schema.** Use a validator (Zod, Joi, AJV) to reject malformed input before it touches business logic.
3. **Sanitize.** Strip/escape data destined for HTML, SQL, shell, or NoSQL queries to prevent injection.

```js
import { z } from 'zod';
const CreateUser = z.object({
  email: z.string().email(),
  age: z.number().int().min(0).max(120),
});

app.post('/users', (req, res) => {
  const parsed = CreateUser.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ errors: parsed.error.issues });
  // parsed.data is now typed and trusted
});
```

Never trust client input — validate type, shape, range, and size at the boundary. Reject early with a 400 rather than letting bad data propagate.

## 🟠 Advanced (8–12 yrs)

### Q33. [Theory] How do you diagnose and fix a memory leak in a Node process?

A memory leak is memory that is retained but never released — typically references that outlive their usefulness. Common Node sources:

- **Unbounded caches / maps** that grow forever (use an LRU with a max size, or `WeakMap`/`WeakRef`).
- **Event listeners** added but never removed (each listener retains its closure; watch for `MaxListenersExceededWarning`).
- **Closures** capturing large objects, kept alive by long-lived callbacks or timers.
- **Global arrays** that only get pushed to.
- **Uncleared `setInterval`** timers holding references.

Diagnosis workflow:

1. Observe RSS/heap growth over time (`process.memoryUsage()`, APM, or `--max-old-space-size` crashes).
2. Take **heap snapshots** (`node --inspect` + Chrome DevTools, or `v8.writeHeapSnapshot()` / the `heapdump` module) at intervals.
3. Use the **Comparison** view between two snapshots to find object types whose retained count keeps growing.
4. Follow **retainer paths** to see what's holding the leaked objects, then break the reference.

```js
const v8 = require('node:v8');
process.on('SIGUSR2', () => v8.writeHeapSnapshot()); // trigger on demand
```

Fix by bounding growth: cap caches, remove listeners on teardown, clear timers, and use weak references where lifetime should follow another object.

### Q34. [Theory] How do you profile and find a performance bottleneck?

Match the tool to the symptom:

- **CPU bottleneck** — capture a CPU profile with `node --prof` (then `--prof-process`) or `--cpu-prof`, or attach Chrome DevTools / Clinic Flame to get a **flame graph**. Wide bars = functions consuming the most self time. This finds hot synchronous functions.
- **Event-loop lag** — measure with `perf_hooks.monitorEventLoopDelay()`. Rising delay means something is blocking the loop (sync work or microtask starvation).
- **Memory** — heap snapshots and `--inspect` (see leak question).
- **Holistic** — the **Clinic.js** suite (`clinic doctor`, `clinic flame`, `clinic bubbleprof`) classifies whether you're CPU-bound, I/O-bound, or event-loop-blocked.

```js
const { monitorEventLoopDelay } = require('node:perf_hooks');
const h = monitorEventLoopDelay();
h.enable();
setInterval(() => console.log('p99 loop delay ms:', h.percentile(99) / 1e6), 5000);
```

Method: measure first, find the dominant cost, fix it, re-measure. Don't optimize on intuition — Node bottlenecks are frequently surprising (a synchronous JSON parse, a regex, an accidental `*Sync` call).

### Q35. [Coding] Implement graceful shutdown for an HTTP server.

Graceful shutdown stops accepting new work while letting in-flight requests finish, then releases resources — critical for zero-downtime deploys and not dropping requests during a rolling restart.

```js
const server = app.listen(3000);

let shuttingDown = false;

function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`${signal} received, shutting down...`);

  // 1. Stop accepting new connections; callback fires when all are done
  server.close(async () => {
    try {
      await db.close();          // 2. close DB pool, message brokers, etc.
      await redis.quit();
      console.log('clean shutdown');
      process.exit(0);
    } catch (err) {
      console.error('error during shutdown', err);
      process.exit(1);
    }
  });

  // 3. Force-exit if connections don't drain in time
  setTimeout(() => {
    console.error('forced shutdown after timeout');
    process.exit(1);
  }, 10_000).unref();            // unref so this timer alone won't keep us alive
}

process.on('SIGTERM', () => shutdown('SIGTERM')); // sent by k8s/Docker
process.on('SIGINT', () => shutdown('SIGINT'));   // Ctrl-C
```

Key elements: handle `SIGTERM`/`SIGINT`, call `server.close()` to stop new connections, drain dependencies, and enforce a **hard timeout** so a stuck connection can't hang the deploy forever. For long-lived keep-alive connections you may also need to set `server.headersTimeout`/`requestTimeout` and actively close idle sockets (`server.closeIdleConnections()` in Node 18+).

### Q36. [Theory] What are the most important security practices for a Node backend?

A pragmatic checklist:

- **Keep dependencies patched** — run `npm audit` / Dependabot; the supply chain is the biggest attack surface (typosquatting, compromised packages). Pin versions via the lockfile.
- **Validate and sanitize all input** — prevent SQL/NoSQL/command injection; use parameterized queries (never string-concatenate SQL).
- **Set security headers** — use `helmet` (CSP, HSTS, `X-Content-Type-Options`, etc.).
- **Rate-limit and throttle** — protect against brute force and DoS (`express-rate-limit`).
- **Hash passwords** with `bcrypt`/`argon2`, never store plaintext or fast hashes.
- **Use HTTPS/TLS** and secure, `HttpOnly`, `SameSite` cookies; protect against CSRF for cookie auth.
- **Avoid leaking internals** — don't return stack traces to clients; sanitize error responses.
- **Don't run as root**, drop privileges, and avoid `eval`, `child_process` with unsanitized input, and dynamic `require` of user-controlled paths.
- **Limit body size and concurrency** to resist resource-exhaustion attacks.

Defense in depth: assume any single layer can fail.

### Q37. [Theory] Explain the `AsyncLocalStorage` / async context tracking. Why is it useful?

In a single-threaded async server, you can't use thread-local storage to carry per-request context (request ID, user, trace span) — many requests interleave on one thread. `AsyncLocalStorage` (from `node:async_hooks`) solves this by maintaining a **store that follows the asynchronous call chain** of a single logical operation, across `await`s, callbacks, and timers, without threading the context through every function signature.

```js
const { AsyncLocalStorage } = require('node:async_hooks');
const als = new AsyncLocalStorage();

app.use((req, res, next) => {
  als.run({ requestId: crypto.randomUUID() }, () => next());
});

function logger(msg) {
  const { requestId } = als.getStore() ?? {};
  console.log(`[${requestId}] ${msg}`);   // correct id even deep in async calls
}
```

It's the backbone of modern request-scoped logging and distributed tracing (OpenTelemetry uses async context under the hood). It has a small but real performance cost, so use one store per request and keep the payload lean.

### Q38. [Coding] Build a concurrency limiter (process N tasks at a time).

Unbounded `Promise.all` over thousands of tasks can exhaust sockets, DB connections, or memory. A pool/limiter caps in-flight work.

```js
async function mapWithConcurrency(items, limit, worker) {
  const results = new Array(items.length);
  let next = 0;

  async function run() {
    while (next < items.length) {
      const i = next++;                 // claim an index atomically (single-threaded)
      results[i] = await worker(items[i], i);
    }
  }

  // Start `limit` runners that each pull from the shared cursor
  const runners = Array.from({ length: Math.min(limit, items.length) }, run);
  await Promise.all(runners);
  return results;
}

// Process 500 URLs but never more than 10 fetches at once
const data = await mapWithConcurrency(urls, 10, (url) => fetch(url).then(r => r.json()));
```

The single-threaded model makes `next++` safe without locks. In production you'd reach for `p-limit` or `Piscina`, but being able to write this demonstrates understanding of the model. Complexity: O(n) work, O(limit) concurrent in-flight.

### Q39. [Theory] How does V8 garbage collection work, and how does it affect a Node app?

V8 uses a **generational, stop-the-world (but mostly incremental/concurrent) mark-sweep-compact** collector, based on the *generational hypothesis* (most objects die young).

- **New space (young generation)** — small, collected frequently by a fast **Scavenge** (copying) collector. Cheap, frequent, low pause.
- **Old space (old generation)** — objects that survived a couple of scavenges are promoted here, collected by the slower **Mark-Compact** major GC. Larger, less frequent, longer pauses — V8 mitigates with incremental marking and concurrent/parallel work to keep pauses small.

Implications for Node:

- GC pauses run **on the main thread**, briefly halting your JS — a source of latency spikes (p99 jitter). Allocating less and reusing objects reduces major-GC pressure.
- The default old-space cap is ~2 GB (historically; higher on modern 64-bit defaults); raise with `--max-old-space-size=4096` for heavy workloads.
- Short-lived allocations are cheap (scavenge); long-lived large structures cost more.

Practical levers: avoid leaks (which force constant major GC), pool buffers, prefer streaming over buffering large payloads, and watch GC time via `--trace-gc` or `perf_hooks` GC entries.

### Q40. [Behavioral] Tell me about a time you debugged a hard production issue in a Node service.

Structure the answer with **STAR** (Situation, Task, Action, Result) and emphasize methodology over heroics.

A strong example: "Our API's p99 latency climbed over a week until pods OOM-restarted nightly (Situation). I owned reliability for the service (Task). Rather than guess, I added event-loop-delay and `process.memoryUsage()` metrics, confirmed heap RSS grew monotonically, and took heap snapshots an hour apart in staging under load. The comparison view showed an ever-growing array of response objects retained by an event listener we added per request but never removed — a classic listener leak. I moved the listener registration out of the request handler and added a regression test asserting listener count stays flat (Action). RSS flattened, the nightly restarts stopped, and p99 dropped 40% (Result)."

What interviewers look for: you **measure before changing**, you isolate with the right tools (snapshots, profiles, loop-delay), you find **root cause** not just a band-aid, you add a regression guard, and you communicate the impact quantitatively.

### Q41. [Theory] What is the N-API / Node-API and when would you use a native addon?

**Node-API (N-API)** is a stable, **ABI-versioned** C API for building native addons that work across Node versions **without recompiling**. It decouples addons from V8's internal API, so an addon built once runs on future Node releases.

You reach for a native addon when:

- You need to wrap an existing **C/C++ library** (image codecs, ML runtimes, hardware SDKs).
- A hot path is genuinely CPU-bound and even worker threads in JS aren't fast enough.
- You need OS-level capabilities not exposed by Node's standard library.

Trade-offs: native addons add build complexity (`node-gyp`, platform toolchains, prebuilds), are harder to debug, and can crash the whole process (segfault) since they run in-process. Modern alternatives include **WebAssembly** (sandboxed, portable, no per-platform build) and writing addons in Rust via `napi-rs` or `neon`, which give memory safety over raw C++. Prefer Wasm or a well-maintained npm binding before writing your own.

## 🔴 Expert (15+ yrs)

### Q42. [Theory] At staff level, how do you decide between scaling Node with clustering, worker threads, multiple containers, or a different runtime?

This is an architecture decision driven by workload shape and operational model:

- **I/O-bound, many small requests** — a single Node process already handles huge concurrency. Scale **horizontally** with more **containers/pods** behind a load balancer (cluster module is largely redundant when the orchestrator already runs one process per core/container and gives you better isolation, rolling deploys, and health checks).
- **CPU-bound subtasks within request handling** (parsing, transforms, crypto) — use a **worker-thread pool** (Piscina) to keep the main loop responsive without leaving the process.
- **Heavy, bursty batch/CPU work** — move it **out of the request path** entirely into a **job queue** (BullMQ, SQS) with dedicated worker fleets you can scale independently.
- **Sustained, parallel CPU compute as the core product** (video transcoding, ML inference) — Node may be the wrong tool; consider Go/Rust/C++ for the compute core and keep Node as the orchestration/API layer.

The staff-level framing: don't reflexively reach for cluster; reason about the bottleneck (event-loop saturation vs. CPU vs. memory vs. downstream limits), the deployment substrate (k8s already gives you process-level scaling and isolation), failure isolation, and operational cost. Measure event-loop utilization to know whether you're even CPU-bound before adding complexity.

### Q43. [Theory] How would you design observability for a fleet of Node services?

Cover the three pillars plus Node-specific signals, vendor-neutral via **OpenTelemetry**:

- **Metrics** — RED (Rate, Errors, Duration) per endpoint, plus Node-specific: **event-loop delay/utilization**, heap used/RSS, GC pause time/frequency, active handles/requests, libuv thread-pool saturation. Export to Prometheus.
- **Tracing** — distributed traces propagated across services; use `AsyncLocalStorage`-based context (OTel auto-instruments http/express/db clients) so a request ID and trace context follow the async chain end-to-end.
- **Logging** — **structured** JSON logs (pino) with the request/trace ID injected from async context, sampled at scale, shipped to a central store. No `console.log` in hot paths (it's synchronous to a TTY/file).
- **Profiling in prod** — continuous profiling (Pyroscope / `--cpu-prof` on demand, `v8.writeHeapSnapshot` on signal) to catch regressions you can't reproduce locally.

Design principles: instrument at the boundaries (incoming HTTP, outgoing calls, DB), keep cardinality controlled, make the event-loop-delay metric a first-class SLI (it's the canary for blocking bugs), and ensure trace context survives every async hop. Tie alerts to user-facing SLOs (latency/error budgets), not raw resource counters.

### Q44. [Practical] How do you manage long-term dependency health and supply-chain risk for a large Node codebase?

A program, not a one-off:

- **Lockfiles + reproducible installs** (`npm ci`) everywhere; commit `package-lock.json`.
- **Automated scanning** — Dependabot/Renovate for updates, `npm audit`/Snyk/Socket for known vulns and malicious-package signals (install scripts, sudden maintainer changes).
- **Minimize the tree** — fewer/leaner dependencies = smaller attack surface. Audit transitive deps; prefer well-maintained, widely-used packages; vendor or replace tiny risky ones.
- **Pin and verify** — consider provenance/signatures (npm provenance, Sigstore), disable lifecycle scripts where feasible (`--ignore-scripts`), and use a private registry/proxy (Artifactory, Verdaccio) to control what enters.
- **Stay on supported Node** — track the LTS schedule; running EOL Node means unpatched security holes.
- **Update cadence** — small, frequent updates beat big-bang upgrades; gate behind CI with good test coverage so updates are low-risk.

Staff framing: treat dependencies as code you're responsible for. The 2021–2024 wave of supply-chain attacks (event-stream, colors, compromised maintainer accounts) shows a single transitive package can compromise your runtime; budget engineering time for dependency hygiene rather than treating it as free.

### Q45. [Behavioral] How do you lead a team through migrating a large CommonJS codebase to ESM (or TypeScript)?

A leadership/migration question — show incremental strategy and risk management:

"I'd avoid a big-bang rewrite. First, **establish the why** (ESM-only dependencies, tree-shaking, native `await` at top level, ecosystem direction) and align stakeholders on the cost/benefit. Then I'd **make it incremental**: enable dual-module interop (modern Node lets `require()` load ESM and ESM `import` CJS), migrate **leaf modules first** where blast radius is smallest, and gate each step behind the existing test suite. I'd **invest in tooling early** — codemods for `require`→`import`, lint rules to prevent backsliding, and CI checks. I'd convert package-by-package, keep the app shippable at every commit, and track progress with a visible metric (% modules migrated). Throughout I'd protect the team from migration fatigue by time-boxing it alongside feature work rather than freezing delivery."

What's evaluated: incrementalism over heroics, automated safety nets (tests, lint, codemods), keeping the system continuously shippable, managing stakeholders and team morale, and grounding the migration in concrete business value rather than novelty.

### Q46. [Theory] Explain event-loop utilization (ELU) and how you'd use it as a production signal.

**Event-loop utilization** (`perf_hooks.performance.eventLoopUtilization()`) measures the fraction of time the event loop spent **active** (executing JS/callbacks) versus **idle** (waiting in the poll phase for I/O), over an interval. It returns `{ active, idle, utilization }` where utilization ∈ [0, 1].

```js
const { performance } = require('node:perf_hooks');
let last = performance.eventLoopUtilization();
setInterval(() => {
  const now = performance.eventLoopUtilization();
  const elu = performance.eventLoopUtilization(now, last); // delta over interval
  console.log('ELU:', elu.utilization.toFixed(3));
  last = now;
}, 1000);
```

Why it's the best single saturation signal for Node:

- **CPU% lies** for Node — a process can show modest CPU yet have a saturated loop blocked by sync work; ELU captures "is the one thread that matters busy?"
- **ELU near 1.0** means the loop has no spare capacity — added load will translate directly into latency. It's a better **autoscaling / load-shedding** trigger and SLI than CPU or request count.
- Combined with **event-loop delay** (how long callbacks wait), you distinguish "busy but keeping up" (high ELU, low delay) from "blocked" (high delay).

Staff use: alert and autoscale on ELU, shed load when it crosses a threshold, and use it in canary analysis to catch a deploy that introduced blocking code before it causes an incident.

### Q47. [Theory] What are the deepest pitfalls of the single-threaded async model that even senior engineers miss?

Beyond "don't block the loop":

- **Microtask starvation** — an unbounded chain of promises or `process.nextTick` calls drains the microtask queue forever between phases, **starving timers and I/O** even though no synchronous code is "blocking." The loop never advances. This is subtler than a `for` loop because each individual callback is fast.
- **Hidden synchronous costs** — `JSON.parse`/`stringify` on large payloads, big synchronous `crypto`/`zlib`, complex regexes (**ReDoS** — catastrophic backtracking), and `require`-ing big modules at runtime all block the loop and don't *look* blocking.
- **Unhandled async error boundaries** — a throw inside a `setTimeout`/`setImmediate` callback or an event handler escapes surrounding `try/catch` and `.catch()`; only `uncaughtException` sees it. Errors don't propagate across tick boundaries.
- **Async context loss** — context propagated via `AsyncLocalStorage` can be lost across certain boundaries (some thenable libraries, manual `Promise` constructors that detach the chain), silently breaking request-scoped logging/tracing.
- **Backpressure ignored** — naive `stream.write()` loops or `for await` without respecting flow control buffer unbounded data and OOM under load.
- **`unref`/handle leaks** — timers, sockets, and watchers keep the process alive (or leak) if not `unref`'d or closed; a "graceful shutdown" that never exits is usually an un-`unref`'d handle.

The unifying insight: the model is **cooperative**. Everything shares one thread and yields voluntarily — any code that doesn't yield (sync CPU, microtask floods, ignored backpressure) degrades the *entire* process, and the failure modes are latency and starvation rather than obvious crashes.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q48. [Theory] What actually happens, step by step, when you run `node app.js`?

Several layers initialize in order:

1. The `node` binary starts a single OS process and initializes the **V8 isolate** (heap, stack, the JS VM) and the **libuv event loop**.
2. Node bootstraps its internal C++/JS layer: it sets up `process`, the global object, the module loader, and core modules.
3. It reads `app.js`, wraps it in the **module wrapper function** (`(function(exports, require, module, __dirname, __filename) { ... })`), compiles it with V8, and executes it **synchronously top to bottom**. This is the "main module" run.
4. As your top-level code runs it may schedule timers, start I/O, register listeners — these populate the event loop's queues and create **handles/requests** that keep the loop alive.
5. When the top-level script finishes, Node **enters the event loop**. It keeps ticking through phases as long as there are active handles or pending callbacks.
6. When the loop has no more work (no active handles, no pending timers/I/O), it exits naturally, emitting `'beforeExit'` then `'exit'`, and the process terminates with `process.exitCode` (default 0).

The mental model: top-level code is the "synchronous prelude"; the event loop is the long-running engine that takes over afterward.

#### Q49. [Theory] What is the module wrapper, and why does every module get its own `exports`, `require`, `module`, `__dirname`, and `__filename`?

Before executing a CommonJS file, Node wraps its source in a function:

```js
(function (exports, require, module, __dirname, __filename) {
  // your module code runs here
});
```

This wrapper is why those five identifiers appear "magically available" at the top of every CJS file — they're really **function parameters** Node passes in, not true globals. Consequences:

- Top-level `var`/`const` are **scoped to the module**, not leaked to the global object — modules get encapsulation for free.
- `__dirname` and `__filename` are computed per file, so they're path-correct in every module.
- `this` at the top level equals `module.exports` (the wrapper is called with that as the receiver).

ESM has no wrapper; instead each module has its own lexical scope by spec, and you use `import.meta.url` / `import.meta.dirname` instead of `__dirname`.

#### Q50. [Theory] What is the difference between `exports` and `module.exports`?

`exports` starts as a **reference to the same object** as `module.exports`. Node ultimately returns `module.exports` from `require`. So:

```js
// ✅ Works — you mutate the shared object
exports.foo = 1;
module.exports.bar = 2;   // both end up on the exported object

// ❌ Breaks the link — you reassign the local `exports` parameter only
exports = { foo: 1 };     // module.exports is still the original {} → exports nothing
```

Reassigning `exports` just repoints the local parameter; it does **not** change what `require` returns. To export a single value (a function or class), you must assign to `module.exports` directly:

```js
module.exports = function () { /* ... */ };
```

Rule: use `exports.x = ...` to attach named exports, but use `module.exports = ...` when replacing the whole export.

#### Q51. [Theory] How does Node's module resolution algorithm find a module?

When you `require('x')` (or `import 'x'`), Node resolves in this priority:

1. **Core module?** If `x` is a built-in (`fs`, `http`, `node:path`), return it immediately. The `node:` prefix forces core and is the modern best practice.
2. **Relative/absolute path** (`./`, `../`, `/`)? Resolve relative to the current file, trying the exact file, then `x.js`, `x.json`, `x.node`, then `x/` as a directory (its `package.json` `"main"`/`"exports"`, else `index.js`).
3. **Bare specifier** (`express`)? Walk **up the directory tree**, checking each `node_modules` folder (`./node_modules`, `../node_modules`, … up to root) until found.

```
/app/src/api.js requires 'lodash' →
  /app/src/node_modules/lodash?
  /app/node_modules/lodash?      ← found here
  /node_modules/lodash?
```

ESM additionally honors the package `"exports"` map (which can **block** deep imports), `"imports"` for `#`-prefixed internal specifiers, and conditional exports (`import`/`require`/`node`/`default`).

#### Q52. [Theory] What does Node's module cache do, and how can it surprise you?

`require` **caches** the resolved `module.exports` in `require.cache`, keyed by the absolute resolved filename. The first `require` of a file runs it; every subsequent `require` returns the **same cached object** without re-executing.

```js
// counter.js
let n = 0;
module.exports = { inc: () => ++n, get: () => n };

// elsewhere — both files share ONE counter instance
const a = require('./counter');
const b = require('./counter');
a.inc();
console.log(b.get());  // 1 — same module, same state
```

Implications and surprises:

- Modules are effectively **singletons** per resolved path — top-level code runs **once**.
- Two different paths to the "same" package (e.g., duplicated in `node_modules`) are **different** cache entries → two copies, which breaks `instanceof` and singletons.
- Deleting from `require.cache` to "hot reload" is fragile (stale closures, partial graphs) — fine for tooling, not production.
- ESM has its own module map with similar single-evaluation semantics, but it's not exposed/mutable like `require.cache`.

#### Q53. [Coding] Demonstrate the difference between `process.nextTick` and `setImmediate` ordering, and explain it.

```js
console.log('start');

setImmediate(() => console.log('setImmediate'));
process.nextTick(() => console.log('nextTick 1'));
Promise.resolve().then(() => console.log('promise'));
process.nextTick(() => console.log('nextTick 2'));

console.log('end');

// Output:
// start
// end
// nextTick 1
// nextTick 2
// promise
// setImmediate
```

Explanation: synchronous code (`start`, `end`) runs first. When the current operation completes, Node drains the **entire `process.nextTick` queue** (both ticks), then the **promise microtask queue**, *before* the event loop advances to any phase. `setImmediate` is a **macrotask** in the check phase, so it runs last. The key teaching point: `nextTick` and promises are microtasks that fire before the loop moves on; `setImmediate` waits for the next loop phase.

#### Q54. [Theory] Why is `process.nextTick` considered dangerous despite running "first"?

Because the nextTick queue is drained **completely** before the event loop is allowed to proceed to any phase — including before promise microtasks and before any timer or I/O. If a `nextTick` callback schedules another `nextTick`, which schedules another, the loop can be **starved indefinitely**:

```js
function loop() {
  process.nextTick(loop);   // ❌ event loop NEVER advances — timers/I/O never run
}
loop();
setTimeout(() => console.log('never prints'), 0);
```

`setImmediate` recursion does **not** starve the loop the same way, because each `setImmediate` waits for the next check phase, letting other phases (timers, poll/I/O) run in between. Guidance: prefer `setImmediate` for "run soon, but yield to I/O"; reserve `process.nextTick` for the narrow case of deferring a callback until after the current operation completes (e.g., to make an API consistently async) without yielding to I/O.

#### Q55. [Practical] What is the `node:` import prefix and why should you use it?

Prefixing core modules with `node:` (e.g., `require('node:fs')`, `import { readFile } from 'node:fs/promises'`) explicitly tells Node "this is a **built-in**, not a userland package." Benefits:

- **Security / clarity** — it cannot be shadowed by a malicious or accidental `node_modules/fs` package; resolution skips `node_modules` entirely.
- **Performance** — resolution is immediate (no directory walk).
- **Some newer core modules are `node:`-only** (e.g., `node:test`, `node:sea`) and can't be imported without the prefix.

It's now the recommended style in modern Node and is enforced by lint rules in many codebases. There's no downside — the prefix works for every built-in.

### 🟡 — extended

#### Q56. [Theory] Inside an I/O callback, why does `setImmediate` always fire before `setTimeout(fn, 0)`?

It comes down to **where in the loop you already are**. When code runs inside an I/O callback, you're in the **poll** phase. From poll, the loop proceeds to **check** (where `setImmediate` callbacks run) *before* it can wrap back around to the **timers** phase on the next tick.

```js
const fs = require('node:fs');
fs.readFile(__filename, () => {       // we are now in the poll phase
  setTimeout(() => console.log('timeout'), 0);
  setImmediate(() => console.log('immediate'));
});
// Reliable order: immediate, then timeout
```

In the **main module** (not inside I/O), the order of `setTimeout(0)` vs `setImmediate` is **non-deterministic** because it depends on whether the ~1 ms minimum timer threshold has elapsed by the time the loop reaches the timers phase. The determinism only appears once you're anchored at a known phase (inside an I/O callback).

#### Q57. [Theory] What is the actual minimum delay of `setTimeout(fn, 0)`, and why?

`setTimeout(fn, 0)` does not fire in zero time. Node clamps very small delays to a **minimum of 1 ms** (matching the HTML spec's clamping behavior; deeply nested timers are clamped further in browsers, but Node's floor is 1 ms). Beyond the clamp, the callback only runs when the loop **reaches the timers phase** and the threshold has elapsed — so under load it can be delayed far longer than 1 ms if other phases are busy.

Key takeaways:
- `setTimeout(fn, 0)` means "as soon as possible in a future timers phase," not "immediately."
- For "run after I/O but as soon as possible," prefer `setImmediate`.
- Timer delays are a **minimum, not a guarantee** — a blocked loop delays all timers (timer drift), which is why you should never rely on `setInterval` for precise scheduling.

#### Q58. [Coding] Promisify a Node-style error-first callback function manually, then with `util.promisify`.

```js
// Manual promisification
function readFilePromise(path) {
  return new Promise((resolve, reject) => {
    fs.readFile(path, (err, data) => {
      if (err) return reject(err);   // error-first → reject
      resolve(data);                  // success → resolve
    });
  });
}

// Using the built-in helper (handles the (err, result) convention for you)
const { promisify } = require('node:util');
const readFileP = promisify(fs.readFile);

await readFileP('a.txt');
```

`util.promisify` works on any function following the **error-first callback** convention `(err, result) => ...`. For multi-argument callbacks, a function can expose a `util.promisify.custom` symbol to control the wrapped result. Most core modules already ship promise variants (`fs/promises`, `dns/promises`, `stream/promises`), so reach for those before promisifying by hand.

#### Q59. [Theory] What is `Promise.allSettled` vs `Promise.all` vs `Promise.race` vs `Promise.any` — and when does each matter?

| Combinator | Settles when | Result | Short-circuits? |
|---|---|---|---|
| `Promise.all` | all fulfill, or first reject | array of values / first reason | rejects on first failure |
| `Promise.allSettled` | all settle | array of `{status, value\|reason}` | never |
| `Promise.race` | first to **settle** (fulfill or reject) | that value/reason | yes |
| `Promise.any` | first to **fulfill** | that value / `AggregateError` if all reject | ignores rejections until all fail |

Decision guide:
- **`all`** — fan-out where any failure should abort (load all required data or fail the request).
- **`allSettled`** — batch jobs where you want every outcome regardless of partial failures (notify 1000 users, report which failed).
- **`race`** — timeouts and "first responder wins" (primary vs replica).
- **`any`** — redundancy/fallback where you only need one success (try several mirrors).

Critical gotcha: with `all`, a rejection rejects the aggregate **immediately, but the other promises keep running** — handle their side effects (or use `AbortController` to actually cancel them).

#### Q60. [Theory] How does `async/await` map onto microtasks under the hood?

An `async` function executes synchronously up to the first `await`. At each `await`, the function **suspends**, returns control to the caller, and schedules its continuation as a **microtask** that runs when the awaited value settles. So `await` never blocks the thread — it yields and resumes via the microtask queue.

```js
async function f() {
  console.log('A');       // sync
  await null;             // suspend → continuation queued as microtask
  console.log('C');       // runs in a later microtask
}
console.log('1');
f();
console.log('2');
// Order: 1, A, 2, C
```

Implications:
- Code before the first `await` runs **synchronously** in the calling tick — useful for argument validation that should throw synchronously (it won't unless you split it out).
- `await` on a non-promise still defers the continuation to a microtask (it wraps with `Promise.resolve`).
- A long chain of `await`s in a hot loop adds microtask overhead and can interleave with other requests in surprising orders — reason about it as cooperative multitasking.

#### Q61. [Coding] Write an async iterator and consume it with `for await...of`.

```js
// Async generator that yields pages from a paginated API
async function* paginate(fetchPage) {
  let cursor = null;
  do {
    const { items, next } = await fetchPage(cursor);
    for (const item of items) yield item;   // stream items one at a time
    cursor = next;
  } while (cursor);
}

// Consumer — backpressure-friendly: one item processed at a time
for await (const item of paginate(fetchPage)) {
  await handle(item);
}
```

`for await...of` consumes any object implementing `Symbol.asyncIterator`, awaiting each yielded promise. This is the idiomatic way to process unbounded or paginated async sequences without loading everything into memory. Node streams are async iterables too, so `for await (const chunk of readable)` reads a stream with automatic backpressure. Pitfall: `for await` is **sequential** — it awaits each iteration before the next; for bounded parallelism you still need a concurrency limiter.

#### Q62. [Theory] What is `queueMicrotask` and when would you choose it over `process.nextTick` or `Promise.resolve().then`?

`queueMicrotask(fn)` schedules `fn` on the **promise microtask queue** — the same queue as `.then` callbacks, drained after the `process.nextTick` queue. It's the standardized, cross-platform (browser + Node) way to defer a callback to the microtask queue without creating a throwaway promise.

Choose it:
- Over `process.nextTick` when you want microtask semantics **without** nextTick's higher priority and starvation risk (queueMicrotask interleaves with promises, so it can't starve I/O as aggressively, and it matches platform behavior).
- Over `Promise.resolve().then(fn)` when you don't need a promise result — it avoids the extra promise allocation and reads more clearly as "defer this."

Use it to break up synchronous work into microtasks while keeping ordering predictable, or to defer side effects until after the current synchronous code completes but before the next macrotask.

#### Q63. [Practical] How do you correctly handle errors when using `stream.pipeline` vs `pipe`?

`pipe()` does **not** forward errors: if a downstream stream errors, the source is **not destroyed**, leaking file descriptors and memory, and you must wire `'error'` handlers on **every** stream manually. `pipeline()` (callback or promise form) propagates errors, destroys all streams in the chain on failure, and calls back/rejects once.

```js
const { pipeline } = require('node:stream/promises');

try {
  await pipeline(
    fs.createReadStream('in.txt'),
    transform,
    fs.createWriteStream('out.txt')
  );
} catch (err) {
  // ANY stage failing lands here; all streams already destroyed/cleaned up
  console.error('pipeline failed:', err);
}
```

Rule: in production, **always** use `pipeline` (or `stream.finished` for single streams) rather than `pipe` + manual error handling. `pipe` is acceptable only for trivial, error-tolerant glue where a leak on failure doesn't matter.

#### Q64. [Theory] What does `UV_THREADPOOL_SIZE` control, and what is and isn't affected by it?

`UV_THREADPOOL_SIZE` sets the number of threads in **libuv's thread pool** (default 4, max 1024). The pool services operations that have **no async OS primitive**:

- **`fs.*`** file operations
- **`dns.lookup`** (via `getaddrinfo`) — but **not** `dns.resolve*`, which uses async network queries
- CPU-heavy **`crypto`** (pbkdf2, scrypt, randomBytes) and **`zlib`**

It does **not** affect **network I/O** (TCP/UDP/HTTP sockets) — those use the kernel's async notification (epoll/kqueue/IOCP) directly, no pool thread. So raising the pool size won't help a network-bound server but **will** help an fs/crypto-heavy one where the default 4 threads queue up.

```js
// Set BEFORE the first pool use (ideally before requiring modules that touch fs/crypto)
process.env.UV_THREADPOOL_SIZE = '16';
```

Gotcha: it must be set **before** the pool is first used; changing it at runtime has no effect. Over-sizing wastes memory and causes context-switching; size it to your fs/crypto concurrency, not arbitrarily.

#### Q65. [Coding] Implement an in-memory LRU cache to bound memory growth.

```js
class LRUCache {
  constructor(maxSize) {
    this.maxSize = maxSize;
    this.map = new Map();           // Map preserves insertion order
  }
  get(key) {
    if (!this.map.has(key)) return undefined;
    const value = this.map.get(key);
    this.map.delete(key);           // re-insert to mark as most-recently-used
    this.map.set(key, value);
    return value;
  }
  set(key, value) {
    if (this.map.has(key)) this.map.delete(key);
    this.map.set(key, value);
    if (this.map.size > this.maxSize) {
      const oldest = this.map.keys().next().value;  // first key = LRU
      this.map.delete(oldest);                       // evict
    }
  }
}

const cache = new LRUCache(1000);  // bounded — won't grow without limit
```

This exploits `Map`'s **insertion-order iteration**: the first key is always the least-recently-used. Re-inserting on access moves an entry to the "newest" end. Bounding the cache is the standard fix for the most common Node memory leak (an unbounded cache/`Map`). In production use a battle-tested library (`lru-cache`) that adds TTLs, size-by-bytes, and stale-while-revalidate, but knowing the mechanism matters.

### 🟠 — extended

#### Q66. [Theory] How does `AsyncLocalStorage` actually work, and what are its failure modes?

`AsyncLocalStorage` is built on **async_hooks**, which fires lifecycle callbacks (`init`, `before`, `after`, `destroy`, `promiseResolve`) as **async resources** are created and run. Node tracks an "async context" that is propagated from a parent resource to the children it spawns. `als.run(store, cb)` associates `store` with the current async resource; `als.getStore()` reads whichever store is active for the **currently executing** async chain — so it survives `await`s, `.then`s, timers, and I/O callbacks within that chain.

Failure modes (context loss):
- **Detached promises / custom thenables** — some libraries or hand-rolled `Promise` constructors break the propagation chain, so `getStore()` returns `undefined` downstream.
- **Resource pooling / event emitters** — a callback registered on a long-lived emitter or a pooled connection may run in the context where it was *fired*, not where it was *registered*. `AsyncResource.bind(fn)` re-binds a callback to the current context to fix this.
- **`setInterval`/shared timers** — a single timer shared across requests carries one context, not per-fire context.

It also has a non-trivial cost (async_hooks overhead), so keep stores small and avoid enabling hooks you don't need. It's the foundation of OpenTelemetry context propagation and request-scoped logging.

#### Q67. [Theory] Explain how V8 hidden classes and inline caches affect Node performance.

V8 doesn't store objects as hash maps; it assigns each object a **hidden class** (a "shape" / "map") describing its property layout. Objects created with the **same properties in the same order** share a hidden class, letting V8 use **inline caches (ICs)** — fast, monomorphic property lookups compiled into machine code.

Performance consequences:
- **Initialize all properties in the constructor, in a consistent order.** Adding properties later, or in different orders, creates **divergent hidden classes**, turning monomorphic ICs into polymorphic/megamorphic ones that are far slower.
- **`delete obj.prop`** transitions the object to a slower dictionary mode — avoid `delete` on hot objects (set to `undefined` or `null` instead).
- **Don't mix types** in a property across instances; type-stable properties keep ICs monomorphic.

```js
// ✅ Same shape every time → fast
class Point { constructor(x, y) { this.x = x; this.y = y; } }

// ❌ Shape diverges → deoptimizes hot paths
const p = {}; p.x = 1; /* later, conditionally */ p.y = 2;
```

This is why "boring," consistently-shaped objects outperform dynamically-grown ones in hot loops — it's the difference between JIT-compiled fast paths and the interpreter.

#### Q68. [Theory] What is the difference between `dns.lookup` and `dns.resolve`, and why does it matter for a high-throughput service?

- **`dns.lookup`** uses the OS resolver (`getaddrinfo`), which runs on the **libuv thread pool**. It honors `/etc/hosts` and system config, but because it's synchronous-under-the-hood it consumes a pool thread per call. Under a burst of outbound connections it can **exhaust the 4-thread pool** and serialize DNS, adding latency to *unrelated* fs/crypto work that shares the pool. It's also what `http.request`/`fetch` use by default.
- **`dns.resolve` / `dns.Resolver`** performs an actual **async DNS network query** (via c-ares), bypassing the thread pool entirely and not touching `/etc/hosts`.

Why it matters: a service making many outbound HTTP calls can have its event-loop responsiveness degraded by `dns.lookup` saturating the pool. Mitigations: raise `UV_THREADPOOL_SIZE`, add a **DNS cache** (e.g., `cacheable-lookup`) to avoid repeated lookups, or use HTTP agents with keep-alive so connections (and their resolved addresses) are reused. At staff level this is a classic "mysterious latency under load" root cause.

#### Q69. [Coding] Implement a simple circuit breaker to protect a flaky downstream dependency.

```js
class CircuitBreaker {
  constructor(fn, { failureThreshold = 5, cooldownMs = 10_000 } = {}) {
    this.fn = fn;
    this.failureThreshold = failureThreshold;
    this.cooldownMs = cooldownMs;
    this.failures = 0;
    this.state = 'CLOSED';        // CLOSED → OPEN → HALF_OPEN
    this.nextTry = 0;
  }

  async call(...args) {
    if (this.state === 'OPEN') {
      if (Date.now() < this.nextTry) throw new Error('circuit open');
      this.state = 'HALF_OPEN';   // allow one trial request
    }
    try {
      const result = await this.fn(...args);
      this.success();
      return result;
    } catch (err) {
      this.recordFailure();
      throw err;
    }
  }

  success() { this.failures = 0; this.state = 'CLOSED'; }

  recordFailure() {
    if (++this.failures >= this.failureThreshold || this.state === 'HALF_OPEN') {
      this.state = 'OPEN';
      this.nextTry = Date.now() + this.cooldownMs;  // stop hammering the dependency
    }
  }
}

const breaker = new CircuitBreaker(callPaymentApi, { failureThreshold: 3 });
await breaker.call(order);  // fails fast once the downstream is unhealthy
```

The breaker **fails fast** when a dependency is unhealthy (OPEN), avoiding piling up requests/timeouts that exhaust your own resources, then probes recovery (HALF_OPEN) after a cooldown. In production use `opossum`, which adds metrics, fallbacks, and rolling-window stats — but the state machine above is the core idea every senior engineer should be able to reason about.

#### Q70. [Theory] How would you detect and prevent ReDoS (regular-expression denial of service) in Node?

ReDoS exploits **catastrophic backtracking**: a regex with nested/overlapping quantifiers (e.g., `(a+)+$`, `(\w+\s?)*`) can take **exponential** time on a crafted input, and because regex matching is **synchronous**, it blocks the single event loop — one malicious request can freeze the entire process.

```js
// ❌ Vulnerable: catastrophic backtracking on input like "aaaa...!"
const bad = /^(\w+\s?)*$/;
```

Detection and prevention:
- **Audit patterns** for nested quantifiers and ambiguous alternation; tools like `safe-regex`, `recheck`, or ESLint plugins flag risky regexes.
- **Use linear-time engines** — Node supports the **RE2** engine via the `re2` package (no backtracking, guaranteed linear), ideal for regexes applied to user input.
- **Bound input length** before matching and validate with non-regex parsers where possible.
- **Set timeouts** — V8 lacks a built-in regex timeout, so isolate untrusted matching in a **worker thread** you can terminate, or use RE2.

The deeper lesson: any synchronous operation whose cost is attacker-controlled (regex, `JSON.parse` of huge/deep payloads, decompression "zip bombs") is an availability vulnerability in a single-threaded runtime.

#### Q71. [Theory] What are the tradeoffs of HTTP keep-alive and connection pooling with the global Agent?

Node's `http`/`https` Agent manages a pool of sockets. With **keep-alive**, sockets are **reused** across requests instead of doing a fresh TCP (and TLS) handshake each time — eliminating handshake latency (often the dominant cost for short requests) and reducing CPU/file-descriptor churn.

```js
const { Agent } = require('node:https');
const agent = new Agent({ keepAlive: true, maxSockets: 50, maxFreeSockets: 10 });
await fetch(url, { agent });  // (undici/global fetch has its own pool config)
```

Tradeoffs and gotchas:
- **`maxSockets`** caps concurrency per host — too low throttles throughput; too high overwhelms the downstream. Tune to the dependency's capacity.
- **Stale sockets** — a kept-alive socket can be closed server-side; you need retry-on-`ECONNRESET` for idempotent requests.
- Historically Node's default agent had `keepAlive: false`; modern **undici** (the engine behind global `fetch`) pools and keeps connections alive by default with its own `Pool`/`Agent` knobs (`connections`, `pipelining`).
- Without keep-alive, a high-RPS client exhausts ephemeral ports and pays repeated TLS costs — a frequent cause of latency and `EADDRNOTAVAIL` under load.

#### Q72. [Coding] Use `worker_threads` with a `SharedArrayBuffer` and `Atomics` to share state safely.

```js
const { Worker, isMainThread, workerData } = require('node:worker_threads');

if (isMainThread) {
  const shared = new SharedArrayBuffer(4);        // 4 bytes = one Int32
  const counter = new Int32Array(shared);
  const worker = new Worker(__filename, { workerData: shared });
  worker.on('exit', () => {
    console.log('final:', Atomics.load(counter, 0)); // consistent read
  });
} else {
  const counter = new Int32Array(workerData);
  for (let i = 0; i < 1e6; i++) {
    Atomics.add(counter, 0, 1);                   // atomic increment — no races
  }
}
```

`SharedArrayBuffer` exposes the **same memory** to multiple threads (unlike normal message passing, which copies/transfers). Because true parallelism reintroduces **data races**, you must use **`Atomics`** (`add`, `load`, `store`, `compareExchange`, `wait`/`notify`) for race-free reads/writes and cross-thread signaling. This is the one place in Node where classic concurrency hazards apply. Use it for high-throughput shared counters, lock-free queues, or coordinating a worker pool — but reach for it sparingly; message passing is simpler and safe by default.

#### Q73. [Theory] What is the difference between `transferList` (transferring) and cloning when posting messages between threads?

When you `postMessage(value)` between a main thread and a worker, the value is serialized with the **structured clone algorithm** — a deep **copy**, which is expensive for large payloads.

To avoid the copy, pass a **transfer list**: ownership of certain objects (`ArrayBuffer`, `MessagePort`, streams) is **moved** to the receiver in O(1), and the sender's reference becomes **unusable** (detached).

```js
const buf = new ArrayBuffer(64 * 1024 * 1024);   // 64 MB
worker.postMessage(buf, [buf]);                   // transfer — zero-copy
// buf.byteLength is now 0 here; ownership moved to the worker
```

Distinctions:
- **Clone** — receiver gets an independent copy; both sides keep working; cost is proportional to size.
- **Transfer** — receiver takes ownership; sender loses access; near-zero cost.
- **Share** — `SharedArrayBuffer` is **neither** cloned nor transferred; both threads access the same memory simultaneously (needs `Atomics`).

For big binary payloads, transfer (or share) instead of clone to avoid serialization cost and GC pressure. Note: plain objects/arrays can only be cloned, not transferred — only specific transferable types qualify.

### 🔴 — extended

#### Q74. [Theory] How does V8 tier up code (Ignition → Sparkplug → Maglev → TurboFan), and how does that shape Node performance reasoning?

V8 uses a **multi-tier adaptive pipeline**:

1. **Ignition** — a bytecode **interpreter**; all code starts here. Fast to start, slower to run.
2. **Sparkplug** — a fast **baseline JIT** that compiles bytecode to machine code with minimal optimization, removing interpreter overhead for warm code.
3. **Maglev** — a newer mid-tier optimizing JIT (recent V8/Node) that produces decent code quickly for hot-ish functions, bridging the gap to TurboFan.
4. **TurboFan** — the **top-tier optimizing** compiler; aggressively speculates on observed types (from feedback/ICs) to emit highly optimized machine code for hot functions.

**Deoptimization**: TurboFan's speculation can be invalidated (e.g., a function that always saw integers suddenly gets a string), forcing a **bailout** back to a lower tier — a real cost in hot loops. Reasoning consequences:

- Code must be **warm** before it's fast; microbenchmarks that don't warm up mislead.
- **Type stability** keeps functions in TurboFan; polymorphic/megamorphic call sites and shape changes trigger deopts.
- Avoid constructs historically hostile to optimization (`arguments` leaks, `try/catch` in tight loops — much improved now, `with`, `eval`). The practical rule: keep hot functions small, monomorphic, and type-stable so they reach and stay at the top tier.

#### Q75. [Theory] Walk through the full lifecycle of a single HTTP request through the event loop, from socket to response.

1. **Connection** — the kernel accepts a TCP connection on the listening socket; libuv's poll phase is notified via epoll/kqueue/IOCP. No thread-pool thread is used (network I/O is kernel-async).
2. **Request parsing** — incoming bytes arrive in the **poll** phase; Node's HTTP parser (`llhttp`) parses headers/body incrementally, emitting `'request'` once headers are in. Your handler runs **on the main thread** in this poll-phase callback.
3. **Handler execution** — your JS runs synchronously until it hits an `await` (DB query, downstream fetch). At that point the handler **suspends** (continuation queued as a microtask), and the loop is free to serve other connections.
4. **Async I/O** — the DB call goes out as kernel-async network I/O (or fs/crypto via the thread pool). When it completes, libuv surfaces the result in a later poll phase.
5. **Continuation** — the awaited promise resolves → your handler's continuation runs as a **microtask**, building the response.
6. **Response write** — `res.end()` writes to the socket (a writable stream, subject to **backpressure** if the client is slow). The kernel sends bytes asynchronously.
7. **Cleanup** — `'finish'`/`'close'` events fire in the close-callbacks phase; the socket may be **kept alive** for reuse.

The throughput insight: thousands of requests interleave because each spends most of its life **suspended at `await`** while the single thread services others. Any handler that *doesn't* yield (sync CPU, blocking call) freezes **all** in-flight requests — the core scalability constraint.

#### Q76. [Theory] How do `--max-old-space-size`, `--max-semi-space-size`, and the heap layout interact, and how do you tune them?

V8's heap is split into **generations**:

- **New space (young)** — two **semi-spaces** (from/to) for the copying **Scavenge** collector; sized by `--max-semi-space-size` (in MB). Larger young space → fewer minor GCs (good for high-allocation-rate apps) but more memory and slightly longer scavenge pauses.
- **Old space** — survivors promoted from young, collected by **Mark-Compact**; capped by `--max-old-space-size` (in MB). This is the "heap limit" people usually mean.

Tuning reasoning:
- If the app throws **`FATAL ERROR: ... JavaScript heap out of memory`** legitimately (large working set), raise `--max-old-space-size` (e.g., `=4096`) — but only after ruling out a **leak** (raising the cap just delays an OOM caused by a leak).
- For **high-throughput, allocation-heavy** services, bumping `--max-semi-space-size` (e.g., `=64` or `=128`) reduces minor-GC frequency and can cut p99 GC jitter — measure with `--trace-gc`.
- Set the old-space cap **below the container memory limit** with headroom (V8's heap isn't the only memory — there are buffers, native, stack), or the OOM killer reaps the process before V8 can GC.

Method: `--trace-gc`/`--trace-gc-verbose`, GC entries via `perf_hooks`, and snapshots; tune one knob at a time and validate against pause-time and RSS, not guesswork.

#### Q77. [Coding] Build a minimal worker-thread pool that reuses workers across tasks.

```js
const { Worker } = require('node:worker_threads');
const os = require('node:os');

class WorkerPool {
  constructor(workerPath, size = os.availableParallelism()) {
    this.workerPath = workerPath;
    this.idle = [];
    this.queue = [];                      // pending {task, resolve, reject}
    for (let i = 0; i < size; i++) this.#spawn();
  }

  #spawn() {
    const worker = new Worker(this.workerPath);
    worker.current = null;
    worker.on('message', (result) => {
      worker.current.resolve(result);
      this.#release(worker);
    });
    worker.on('error', (err) => {
      if (worker.current) worker.current.reject(err);
      this.#release(worker);             // could also respawn on fatal error
    });
    this.idle.push(worker);
  }

  #release(worker) {
    worker.current = null;
    this.idle.push(worker);
    this.#drain();
  }

  #drain() {
    if (!this.queue.length || !this.idle.length) return;
    const worker = this.idle.pop();
    const job = this.queue.shift();
    worker.current = job;
    worker.postMessage(job.task);
  }

  run(task) {
    return new Promise((resolve, reject) => {
      this.queue.push({ task, resolve, reject });
      this.#drain();
    });
  }
}

// const pool = new WorkerPool('./worker.js');
// const result = await pool.run({ n: 1e9 });
```

The pool **amortizes worker startup cost** (spawning a worker spins up a fresh V8 isolate — expensive) by reusing a fixed set sized to available cores, queuing tasks when all workers are busy. This is the architecture behind **Piscina**, the production-grade pool you'd actually use (it adds task cancellation, timeouts, `transferList` handling, and resource limits). The key staff-level point: never spawn a worker per task on a hot path; pool and reuse.

#### Q78. [Theory] What are async_hooks, and why are they powerful yet discouraged for direct use?

`async_hooks` (the low-level API beneath `AsyncLocalStorage`) lets you register callbacks fired across the lifecycle of **every async resource**: `init` (resource created, with an async ID and a parent "trigger" ID), `before`/`after` (around its callback execution), `destroy`, and `promiseResolve`. This exposes the full **async causality graph** — which operation spawned which — enabling request-context propagation, leak detection (resources that never `destroy`), and tracing.

It's powerful because it sees *all* asynchrony with no code changes. It's discouraged for direct use because:

- **Performance** — enabling hooks adds overhead to **every** async operation process-wide; naive use can measurably slow the app (especially the `destroy`/promise hooks).
- **Correctness is hard** — writing a correct hook (handling every resource type, avoiding recursion when your hook itself does async work, not leaking) is subtle and error-prone.
- **Instability** — the API has been **experimental** for a long time and has shifted; `AsyncLocalStorage` is the stable, supported abstraction built on top.

Guidance: use `AsyncLocalStorage` for context and let OpenTelemetry/APM vendors handle low-level instrumentation. Reach for raw `async_hooks` only for diagnostics/tooling, and benchmark the overhead.

#### Q79. [Theory] How do you reason about and mitigate event-loop blocking introduced by `JSON.parse`/`JSON.stringify` on large payloads at scale?

`JSON.parse`/`stringify` are **synchronous and CPU-bound**, executing entirely on the main thread. For a multi-MB payload they can block the loop for tens of milliseconds — and at scale that's **per request**, so a few large bodies serialize the whole server, spiking p99 for *every* concurrent request (not just the big one).

Reasoning and mitigations, roughly in order:
- **Don't materialize huge JSON.** Stream it: use a **streaming parser** (`stream-json`, `JSONStream`) or `clarinet` so you process tokens incrementally with bounded memory and yielding, instead of one giant blocking call.
- **Bound input** — enforce `express.json({ limit })` and reject oversized bodies (also a DoS guard); deeply nested JSON can be a parser-bomb.
- **Offload** genuinely large transforms to a **worker thread** (or a worker pool) so the main loop stays responsive.
- **Avoid re-serializing** — cache serialized responses, use `res.json` once, and prefer pre-serialized buffers for hot, static responses.
- **Consider a faster serializer** for known shapes — `fast-json-stringify` compiles a schema-specific serializer that's far faster than generic `JSON.stringify`; or switch to a binary format (Protobuf/MessagePack/CBOR) for internal service-to-service traffic.

The unifying principle: in a single-threaded runtime, **any synchronous cost is shared by all concurrent work** — large JSON is a stealth source of tail-latency that doesn't show up as an obvious "blocking" call.

#### Q80. [Behavioral] As a staff engineer, how do you set and enforce performance/reliability standards for Node services across multiple teams?

Frame it as building **guardrails and culture**, not policing:

"I'd start by defining **org-wide SLIs/SLOs** grounded in user impact — latency, error rate, and the Node-specific canary, **event-loop delay/utilization** — so every service measures the same things. Then I'd **codify standards as tooling, not docs**: a shared service template/starter with structured logging (pino), OpenTelemetry tracing, graceful shutdown, health/readiness probes, sane HTTP agent/keep-alive defaults, and body-size limits baked in, so teams inherit good defaults instead of reinventing them. I'd add **CI gates** — lint rules banning `*Sync` on hot paths and unsafe `Buffer`, dependency/vuln scanning, and load-test/regression checks for p99 and event-loop delay on critical services. For enforcement I'd prefer **review and enablement over mandates**: production-readiness reviews for new services, a profiling runbook (heap snapshots, flame graphs, ELU dashboards), and brown-bags on the failure modes (microtask starvation, backpressure, thread-pool saturation). I'd track adoption with a scorecard and partner with the worst-affected team first to demonstrate value, then let success pull others in. The throughline: make the right thing the **easy default**, measure relentlessly, and treat reliability as a shared platform investment rather than each team's separate burden."

What's evaluated: systems thinking (standards as platform/tooling), use of the *right* Node signals (ELU, not just CPU), balancing autonomy with consistency, influence without authority across teams, and grounding standards in user-facing outcomes.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q81. [Practical] Your Express server prints "listening on :3000" but every request hangs and never responds. How do you diagnose it?

A request that connects but never completes almost always means a handler that **never calls `res.end()` / `res.json()` / `res.send()`** on some code path. Checklist:

1. **Forgot to send a response.** A route that does `await db.query(...)` and then nothing, or returns a value (Express ignores return values), leaves the socket open. Confirm every path terminates the response.
2. **An `await` that never settles** — a promise that's never resolved (e.g., you created `new Promise((resolve) => {...})` and forgot to call `resolve`), or a downstream call with no timeout that's stuck.
3. **A swallowed error before the response** — a `try/catch` that logs but then falls through without responding, or an async handler whose rejection isn't forwarded (Express 4 without `next(err)`).
4. **Missing `next()` in middleware** — a custom middleware that neither responds nor calls `next()` stalls the chain.

```js
// ❌ hangs — no response on the success path
app.get('/x', async (req, res) => {
  const data = await load();   // resolves, but nothing is sent
});

// ✅ always terminate the response
app.get('/x', async (req, res, next) => {
  try { res.json(await load()); }
  catch (err) { next(err); }
});
```

Practical tooling: add a request-logging middleware that logs on both entry and `res.on('finish')`; if you see "entry" without "finish," you've found the hanging route. A global `server.requestTimeout` (Node 18+) turns silent hangs into visible 408s.

#### Q82. [Practical] You see `EADDRINUSE` on startup. What does it mean and how do you fix it?

`EADDRINUSE` means the port you're trying to `listen()` on is **already bound** by another process (or a previous instance of your own app that didn't shut down cleanly).

Fix it:
- **Find and kill the holder.** On Linux/macOS: `lsof -i :3000` then `kill <pid>`; on Windows: `netstat -ano | findstr :3000` then `taskkill /PID <pid> /F`.
- **A zombie from your own dev loop** — nodemon or a crashed previous run still holds the socket; killing it (or `pkill -f node`) frees it.
- **Handle it gracefully** so the error is actionable instead of a raw stack trace:

```js
server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`Port ${PORT} is in use. Is another instance running?`);
    process.exit(1);
  } else {
    throw err;
  }
});
```

Root-cause prevention: implement **graceful shutdown** (`server.close()` on `SIGINT`/`SIGTERM`) so the socket is released when you stop the app, and avoid hardcoding ports — read `process.env.PORT` so multiple instances don't collide.

#### Q83. [Coding] Read a large file line by line without loading it all into memory.

```js
const fs = require('node:fs');
const readline = require('node:readline');

async function countErrors(path) {
  const rl = readline.createInterface({
    input: fs.createReadStream(path),       // streamed — constant memory
    crlfDelay: Infinity,                    // treat \r\n as a single line break
  });

  let errors = 0;
  for await (const line of rl) {            // backpressure-aware iteration
    if (line.includes('ERROR')) errors++;
  }
  return errors;
}
```

`fs.readFileSync`/`readFile` load the **entire file into memory** — fine for small files, fatal for a multi-GB log (it OOMs, and `readFileSync` also blocks the loop). `readline` over a read stream processes one line at a time with bounded memory, and `for await...of` gives you automatic backpressure. This is the canonical "process a huge log/CSV" pattern.

#### Q84. [Practical] A teammate wrote `if (process.env.DEBUG) enableVerbose()` and it's always on. Why, and how do you read booleans from env vars correctly?

**Environment variables are always strings.** `process.env.DEBUG` is the string `"false"`, not the boolean `false` — and a non-empty string is **truthy**, so the check passes even when you set `DEBUG=false`.

```js
// ❌ "false" is a truthy string
if (process.env.DEBUG) { /* always runs if the var is set to anything */ }

// ✅ compare explicitly
const debug = process.env.DEBUG === 'true' || process.env.DEBUG === '1';

// ✅ a small helper
const bool = (v, def = false) =>
  v == null ? def : ['1', 'true', 'yes', 'on'].includes(v.toLowerCase());
const debug2 = bool(process.env.DEBUG);
```

Same trap bites numbers (`process.env.PORT` is `"3000"`, so `PORT + 1` is `"30001"` — use `Number(process.env.PORT)`). Validate and **coerce** all env vars at startup, ideally through a schema (Zod/`envalid`) that turns them into typed config and fails fast on bad values.

#### Q85. [Coding] Fetch JSON from an API with a timeout and proper error handling using the built-in `fetch`.

```js
async function getJson(url, { timeoutMs = 5000 } = {}) {
  const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });

  if (!res.ok) {
    // fetch only rejects on network errors, NOT on 4xx/5xx — check status yourself
    throw new Error(`HTTP ${res.status} ${res.statusText} for ${url}`);
  }
  return res.json();
}

try {
  const data = await getJson('https://api.example.com/users');
} catch (err) {
  if (err.name === 'TimeoutError') console.error('request timed out');
  else console.error('request failed:', err.message);
}
```

Two things people miss with the global `fetch` (stable since Node 18, backed by undici): it **does not throw on HTTP error statuses** — a 404 or 500 still resolves, so you must check `res.ok`/`res.status`; and there's **no default timeout**, so a hung server can leak a pending request forever unless you pass an `AbortSignal` (`AbortSignal.timeout(ms)` is the clean modern idiom and actually aborts the connection).

#### Q86. [Practical] `npm start` works on your machine but fails in CI with "Cannot find module 'X'". What are the likely causes?

The classic "works on my machine" dependency drift. Likely causes, in order:

1. **`X` is in `devDependencies` (or not in `package.json` at all)** but CI installs with `--omit=dev` / `NODE_ENV=production`. You installed it locally so it's in `node_modules`, but it was never declared — add it to the right dependency section.
2. **Lockfile out of sync.** You added the package but didn't commit the updated `package-lock.json`; CI runs `npm ci`, which installs strictly from the lockfile and won't see it. Commit the lockfile.
3. **Case-sensitivity.** macOS/Windows filesystems are case-insensitive; Linux CI is case-sensitive. `require('./Utils')` resolving a file named `utils.js` works locally but fails on Linux.
4. **It was installed globally** on your machine (`npm i -g`) so it's on your PATH but not in the project.
5. **`.gitignore` excludes a needed file** (e.g., a generated file CI doesn't rebuild).

Prevention: use `npm ci` locally too, run a clean `rm -rf node_modules && npm ci` before pushing, and match Node/OS versions between local and CI (Docker or `engines` + `.nvmrc`).

#### Q87. [Coding] Retry a flaky async operation with exponential backoff.

```js
async function retry(fn, { retries = 3, baseMs = 200, factor = 2 } = {}) {
  let attempt = 0;
  for (;;) {
    try {
      return await fn();
    } catch (err) {
      if (attempt >= retries) throw err;        // give up, surface the last error
      const delay = baseMs * factor ** attempt;
      const jitter = Math.random() * delay;     // full jitter to avoid thundering herd
      await new Promise((r) => setTimeout(r, delay + jitter));
      attempt++;
    }
  }
}

// usage
const data = await retry(() => fetch(url).then((r) => r.json()), { retries: 5 });
```

Key practices: cap the number of retries, grow the delay **exponentially**, and add **jitter** so many clients retrying simultaneously don't synchronize into a thundering herd. Only retry **idempotent** operations (GETs, idempotent writes) — blindly retrying a non-idempotent POST can double-charge a customer. In production pair this with a circuit breaker and a per-attempt timeout.

### 🟡 — extended

#### Q88. [Practical] Production memory climbs steadily until the pod is OOM-killed every few hours. Walk through your investigation.

Treat it as a measure-isolate-fix loop, not guesswork:

1. **Confirm it's a leak, not just a big working set.** Plot `process.memoryUsage().heapUsed` and `rss` over time. A true leak grows **monotonically** and never recovers after GC; a large-but-stable cache plateaus. Force a GC (`node --expose-gc`, call `global.gc()`) in staging and see if it drops — if it doesn't, the memory is reachable (leaked).
2. **Heap snapshots, two points apart.** `v8.writeHeapSnapshot()` (trigger via a signal handler) at T0 and T+1h under load, load both into Chrome DevTools, use the **Comparison** view to find the constructor whose retained count keeps growing.
3. **Follow retainer paths** to what's holding the objects: an unbounded `Map`/cache, event listeners added per request but never removed (watch for `MaxListenersExceededWarning`), closures captured by a long-lived timer, or a growing global array.
4. **Fix by bounding lifetime** — LRU-cap caches, remove listeners on teardown, clear intervals, use `WeakMap`/`WeakRef` where lifetime should follow another object.
5. **Add a regression guard** — a test asserting listener counts / cache size stays flat, and ship the memory and ELU metrics as dashboards so it can't silently regress.

The tell that distinguishes a leak from undersized memory: after a manual GC, leaked memory **stays**; legitimate working-set memory **drops**.

#### Q89. [Coding] You need `__dirname` in an ESM file but it's not defined. Fix it.

```js
// ESM has no __dirname/__filename. Modern Node (20.11+ / 21.2+):
const here = import.meta.dirname;            // directory of this module
const self = import.meta.filename;           // full path of this module

// Portable fallback for older Node:
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// e.g. resolve a sibling file
import { readFile } from 'node:fs/promises';
const cfg = await readFile(new URL('./config.json', import.meta.url));
```

ESM modules don't get the CommonJS wrapper, so `__dirname`/`__filename`/`require` aren't injected. Newer Node exposes `import.meta.dirname` and `import.meta.filename` directly; for older runtimes derive them from `import.meta.url` via `fileURLToPath`. For reading sibling assets, `new URL('./x', import.meta.url)` is even cleaner and works without computing a string path.

#### Q90. [Practical] A user reports that a downloaded file is corrupted — text comes through but binary files (images, zips) are mangled. What's the bug?

Almost certainly an **encoding mistake**: somewhere binary data is being treated as a UTF-8 string. Reading a buffer `.toString()` (default UTF-8) and re-encoding it corrupts any byte sequence that isn't valid UTF-8 — text survives, binary doesn't.

```js
// ❌ corrupts binary: decodes bytes as UTF-8 text and back
const data = (await fs.readFile(path)).toString();   // lossy for non-text
res.send(data);

// ✅ keep it as a Buffer end to end
const data = await fs.readFile(path);                // Buffer, no decoding
res.setHeader('Content-Type', 'application/octet-stream');
res.send(data);

// ✅ best for large files — stream it, no full buffering
fs.createReadStream(path).pipe(res);
```

Other culprits to check: a missing/incorrect `Content-Type` or `Content-Length`, writing to a stream opened in the wrong mode, or middleware that JSON-stringifies the body. The rule: **never round-trip binary through a string**; keep it as a `Buffer`/stream, and only convert to a string when you know the data is text and you know its encoding.

#### Q91. [Coding] Two modules `require` each other (circular dependency) and one gets `undefined`. Show the problem and a fix.

```js
// a.js
const b = require('./b');          // b.js runs now, and b requires a back...
exports.aValue = 'A';
exports.useB = () => b.bValue;

// b.js
const a = require('./a');          // a is only PARTIALLY initialized here
console.log(a.aValue);             // undefined — a.js hasn't reached its exports yet
exports.bValue = 'B';
```

When `a` requires `b`, and `b` requires `a` back, Node returns `a`'s **partial `module.exports`** (whatever has been assigned so far) to break the cycle — so `b` sees `undefined` for exports defined later in `a`.

Fixes:
- **Defer the access.** Require lazily inside the function where it's used, not at module top level, so the dependency is fully initialized by call time:

```js
// b.js — require/read a only when actually needed
exports.bValue = 'B';
exports.useA = () => require('./a').aValue;   // a is fully loaded by now
```

- **Break the cycle structurally** — extract the shared piece into a third module both depend on. That's the cleaner long-term fix; circular deps are a design smell.

#### Q92. [Practical] How do you debug a running Node process — both locally and one that's stuck in production?

**Locally:** start with `node --inspect app.js` (or `--inspect-brk` to pause on the first line) and open `chrome://inspect` or VS Code's debugger — you get breakpoints, the call stack, scope inspection, and a console in the live process. For tests, `node --inspect-brk node_modules/.bin/jest`.

**A stuck/misbehaving process in production:**
- You can attach to an **already-running** process without restarting it: send `kill -SIGUSR1 <pid>`, which makes Node open the inspector, then connect a debugger. (Be deliberate — this exposes a debug port.)
- For a **frozen event loop**, capture *why* it's stuck: take a **CPU profile** (`node --cpu-prof`, or trigger `v8.writeHeapSnapshot()`/profiling on a signal) to see the hot synchronous function; check **event-loop delay** (`perf_hooks.monitorEventLoopDelay`) to confirm it's blocked.
- For a **crash/hang you can't reproduce**, enable a **diagnostic report** (`node --report-on-fatalerror --report-on-signal`) — Node writes a JSON report with the JS stack, native stack, heap stats, libuv handles, and env, invaluable for post-mortems.

```bash
node --report-on-fatalerror --report-uncaught-exception app.js
# on crash, writes report.<timestamp>.<pid>.json with full diagnostics
```

The production mindset: prefer **low-overhead, always-on signals** (structured logs, ELU/loop-delay metrics, diagnostic reports, on-demand snapshots) over attaching an interactive debugger to a live customer-facing process.

#### Q93. [Coding] Process an array of 10,000 items by calling an API for each, without overwhelming the API or your memory.

```js
async function mapLimit(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;

  async function runner() {
    while (cursor < items.length) {
      const i = cursor++;                 // claim an index (safe: single-threaded)
      results[i] = await worker(items[i], i);
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, runner)
  );
  return results;
}

// 10k items, but never more than 20 concurrent API calls
const results = await mapLimit(ids, 20, async (id) => {
  const res = await fetch(`/api/items/${id}`, { signal: AbortSignal.timeout(5000) });
  return res.json();
});
```

The naive `await Promise.all(items.map(callApi))` fires **all 10,000 requests at once**, which exhausts sockets/file descriptors, trips the API's rate limiter, and can OOM. A concurrency limiter caps in-flight work at `limit`; the single-threaded model makes `cursor++` race-free without locks. In production reach for `p-limit` or `p-map` (`{ concurrency }`), but writing this shows you understand the model. Add per-call timeouts and retries for resilience.

#### Q94. [Practical] Your logs are interleaved and useless under load — you can't tell which log line belongs to which request. How do you fix it?

The problem is **no request correlation**: with many requests interleaving on one thread, plain `console.log` lines from different requests mix together with no way to group them.

Fix with **structured logging + request-scoped context** via `AsyncLocalStorage`:

```js
const { AsyncLocalStorage } = require('node:async_hooks');
const pino = require('pino');
const als = new AsyncLocalStorage();
const log = pino();

app.use((req, res, next) => {
  const requestId = req.headers['x-request-id'] ?? crypto.randomUUID();
  als.run({ requestId }, () => next());     // context follows the async chain
});

function logInfo(msg, extra) {
  const { requestId } = als.getStore() ?? {};
  log.info({ requestId, ...extra }, msg);   // every line carries the id
}
```

Now every log line is **JSON** (machine-parseable, queryable in your log store) and carries the `requestId`, so you can filter all lines for one request even when they're physically interleaved. Additional wins: pino is **async and fast** (don't use synchronous `console.log` to a file/TTY on hot paths — it blocks), and propagating a trace/correlation ID across service calls lets you follow a request across the whole system.

#### Q95. [Coding] Stream-transform a large CSV (uppercase a column) without buffering the whole file.

```js
const fs = require('node:fs');
const { pipeline } = require('node:stream/promises');
const { Transform } = require('node:stream');

const upperFirstColumn = new Transform({
  transform(chunk, _enc, cb) {
    // (illustrative line-wise transform; a real parser handles quoted commas/newlines)
    const out = chunk
      .toString()
      .split('\n')
      .map((line) => {
        const [first, ...rest] = line.split(',');
        return [first?.toUpperCase(), ...rest].join(',');
      })
      .join('\n');
    cb(null, out);
  },
});

await pipeline(
  fs.createReadStream('in.csv'),
  upperFirstColumn,
  fs.createWriteStream('out.csv')
);
```

`pipeline` wires the stages, manages **backpressure** (the writable pauses the readable when its buffer fills), and **propagates errors** while destroying all streams on failure — so a multi-GB file processes in constant memory. Real-world caveat: chunk boundaries can split a line mid-way, so a production version uses a CSV stream parser (`csv-parse`, `papaparse` stream mode) or a line-splitter transform that buffers partial lines across chunks. The pattern — `createReadStream → Transform → createWriteStream` via `pipeline` — is the backbone of memory-safe file processing.

#### Q96. [Practical] After deploying, `SIGTERM` from Kubernetes kills your pod but in-flight requests get dropped (clients see 502s during every rollout). What's wrong and how do you fix it?

The app isn't handling `SIGTERM` gracefully — it's being **hard-killed** while requests are still in flight. During a rolling deploy, k8s sends `SIGTERM` and waits `terminationGracePeriodSeconds` before `SIGKILL`; if you don't trap `SIGTERM` and drain, the process dies mid-request.

Fix — drain on `SIGTERM`:

```js
const server = app.listen(PORT);

process.on('SIGTERM', () => {
  server.close(async () => {            // stop accepting new connections, finish in-flight
    await db.close();
    process.exit(0);
  });
  server.closeIdleConnections?.();       // Node 18+: drop idle keep-alive sockets
  setTimeout(() => process.exit(1), 10_000).unref();  // hard cap so a stuck conn can't hang the deploy
});
```

Two subtleties that cause 502s even *with* this code:
- **Readiness vs. liveness** — you should fail the **readiness probe** first so the load balancer stops sending new traffic *before* you start draining; otherwise new requests arrive at a closing server.
- **The race with endpoint removal** — k8s removes the pod from Service endpoints asynchronously, so add a short pre-drain delay (sleep a few seconds after SIGTERM before `server.close()`) so in-flight LB routing settles. With proper graceful shutdown + readiness gating, rollouts become zero-downtime.

### 🟠 — extended

#### Q97. [Practical] p99 latency spikes periodically even though average CPU and request rate look fine. How do you find the cause?

"Average looks fine but the tail spikes" is the signature of **intermittent event-loop blocking** — averages hide a few requests that hit a stall.

Investigation:
1. **Measure event-loop delay/utilization**, not CPU. `perf_hooks.monitorEventLoopDelay()` gives you the p99 of how long callbacks waited; a periodic spike there pinpoints blocking. CPU% can look modest while the one thread that matters is stalled.
2. **Correlate the spikes in time** with what's happening: GC pauses (`--trace-gc` — long major-GC pauses cause periodic p99 jitter), a cron-like job, large `JSON.parse`/`stringify` on occasional big payloads, a synchronous crypto/zlib call, or a ReDoS-prone regex hitting a pathological input.
3. **Flame-graph the blocked window** (`--cpu-prof` / Clinic Flame) to see the wide bar — the hot synchronous function.
4. **Check the thread pool** — bursts of `fs`/`crypto`/`dns.lookup` can saturate the 4-thread libuv pool, serializing and adding latency to unrelated work.

```js
const { monitorEventLoopDelay } = require('node:perf_hooks');
const h = monitorEventLoopDelay({ resolution: 10 });
h.enable();
setInterval(() => {
  console.log('loop delay p99 (ms):', (h.percentile(99) / 1e6).toFixed(1));
  h.reset();
}, 1000);
```

Common culprits behind "periodic" spikes specifically: major GC, a scheduled batch job, log rotation/flush, or one heavy request type that occasionally blocks everyone. Fix by offloading the blocking work (worker thread), streaming large payloads, tuning GC/semi-space, or bounding input.

#### Q98. [Coding] Your service makes thousands of outbound HTTP calls and you see latency from DNS and TLS handshakes. Configure connection reuse.

```js
const { Agent, setGlobalDispatcher } = require('undici');

// undici backs the global fetch; configure pooling + keep-alive once at startup
setGlobalDispatcher(new Agent({
  connections: 128,          // max sockets per origin (connection pool size)
  pipelining: 1,
  keepAliveTimeout: 60_000,  // reuse idle sockets for 60s
  keepAliveMaxTimeout: 600_000,
}));

// Now every global fetch reuses pooled, kept-alive connections:
const res = await fetch('https://api.example.com/v1/thing');
```

Without keep-alive, each request pays a fresh **TCP + TLS handshake** (often the dominant latency for small calls) and can exhaust ephemeral ports under high RPS (`EADDRNOTAVAIL`). Pooling reuses warm connections, amortizing handshakes and caching resolved addresses. Complementary fixes: add a **DNS cache** (`cacheable-lookup`) so `dns.lookup` doesn't hammer the libuv thread pool, and tune `connections`/`maxSockets` to the downstream's capacity (too high overwhelms it, too low throttles you). For the legacy `http`/`https` modules, the equivalent is `new Agent({ keepAlive: true, maxSockets })`.

#### Q99. [Practical] A `Promise.all` over many tasks rejects, but you notice some side effects (DB writes) still happened from the "cancelled" tasks. Explain and fix.

`Promise.all` **short-circuits on the first rejection** — but it does **not cancel** the other promises. Promises aren't cancellable by themselves; the operations they represent keep running to completion in the background, so their side effects (DB writes, emails, charges) still occur even though `Promise.all` already rejected.

```js
// Even after this rejects, the other fetches/writes are still in flight
await Promise.all(tasks.map(runTaskWithSideEffects));
```

Fixes depending on intent:
- **You want every outcome regardless of failures** → use `Promise.allSettled`, then inspect each result. Nothing is "abandoned" with surprising state.
- **You genuinely want to cancel the rest on first failure** → thread an `AbortController` through every task and abort it in a `.catch`, so in-flight operations that honor the signal (fetch, fs with `signal`, your own checks) actually stop:

```js
const ctrl = new AbortController();
const run = (t) => doWork(t, { signal: ctrl.signal });
try {
  await Promise.all(tasks.map(run));
} catch (err) {
  ctrl.abort();          // signal the rest to stop
  throw err;
}
```

- **The side effects must be all-or-nothing** → that's a transactionality problem; wrap them in a DB transaction or use a saga/compensation pattern so a partial failure rolls back. The key misconception to correct: rejection of the aggregate ≠ cancellation of the parts.

#### Q100. [Coding] Detect event-loop blocking in your own service and log a warning when a tick takes too long.

```js
const { monitorEventLoopDelay } = require('node:perf_hooks');

function watchEventLoop({ thresholdMs = 100, intervalMs = 1000 } = {}) {
  const h = monitorEventLoopDelay({ resolution: 20 });
  h.enable();
  const timer = setInterval(() => {
    const maxMs = h.max / 1e6;             // worst stall in this window
    const p99Ms = h.percentile(99) / 1e6;
    if (maxMs > thresholdMs) {
      console.warn(`event loop blocked: max=${maxMs.toFixed(0)}ms p99=${p99Ms.toFixed(0)}ms`);
    }
    h.reset();
  }, intervalMs);
  timer.unref();                            // don't keep the process alive just for this
  return () => { clearInterval(timer); h.disable(); };
}

watchEventLoop({ thresholdMs: 70 });
```

`monitorEventLoopDelay` uses a high-resolution histogram maintained by libuv — far cheaper and more accurate than the old "schedule a `setTimeout(fn, N)` and measure the drift" trick. A rising `max`/`p99` means something synchronous (CPU loop, big `JSON.parse`, sync crypto, ReDoS, microtask flood) is stalling the loop. Wiring this as a metric/alert turns invisible blocking into an observable SLI, and combined with event-loop *utilization* you can distinguish "busy but keeping up" from "blocked."

#### Q101. [Practical] You added `await` inside a `forEach` loop and it doesn't wait. Why, and what should you use instead?

`Array.prototype.forEach` is **not promise-aware** — it ignores the return value of its callback. Marking the callback `async` makes it return a promise that `forEach` simply throws away, so the loop fires all callbacks synchronously and moves on **without awaiting** any of them. Code after the loop runs before the async work finishes, and rejections become unhandled.

```js
// ❌ does NOT wait — forEach ignores the returned promises
ids.forEach(async (id) => { await save(id); });
console.log('done');   // prints BEFORE any save completes

// ✅ sequential — await each in order
for (const id of ids) { await save(id); }
console.log('done');   // now actually after all saves

// ✅ concurrent — await them all together
await Promise.all(ids.map((id) => save(id)));

// ✅ bounded concurrency for large arrays
await mapLimit(ids, 10, save);
```

Use a `for...of` loop when you need sequencing/backpressure, `Promise.all(map(...))` when the work is independent and the set is small, and a concurrency limiter (`p-map`, `p-limit`) when the array is large enough that unbounded parallelism would overwhelm a downstream. The same trap applies to `map`/`filter` when you forget they don't await.

#### Q102. [Coding] Safely run an external command from user-influenced input without shell-injection risk.

```js
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const execFileP = promisify(execFile);

// ❌ DANGEROUS: exec runs through a shell — `; rm -rf /` style injection
// const { stdout } = await exec(`convert ${userFile} out.png`);

// ✅ execFile: no shell, args passed as an array — metacharacters are inert
async function convertImage(inputPath) {
  const { stdout } = await execFileP('convert', [inputPath, 'out.png'], {
    timeout: 10_000,        // kill runaway processes
    maxBuffer: 1024 * 1024, // cap output to avoid memory blowups
  });
  return stdout;
}
```

`child_process.exec` (and `spawn`/`execFile` with `shell: true`) run the command through a **shell**, so any shell metacharacter in user input (`;`, `|`, `$()`, backticks) becomes a command-injection vulnerability. `execFile`/`spawn` **without a shell** pass arguments as a literal array straight to the program, so injection is impossible — there's no shell to interpret metacharacters. Additional hardening: **validate/allowlist** the input (e.g., ensure a path is within an expected directory), set a `timeout` and `maxBuffer`, never interpolate user input into a command string, and avoid `shell: true` unless you fully control every argument.

#### Q103. [Practical] An `async` function throws but your surrounding `try/catch` doesn't catch it — the process crashes with an unhandled rejection. What are the likely mistakes?

`try/catch` only catches rejections you actually **`await`** (or `return await`). Common ways the error escapes:

```js
// ❌ 1. Forgot to await — the rejection escapes the try block entirely
try { doAsync(); } catch (e) { /* never runs */ }

// ❌ 2. Fire-and-forget inside a sync context
setInterval(() => doAsync(), 1000);   // rejection has no catch → unhandledRejection

// ❌ 3. Returned without awaiting, so the catch frame is gone before it rejects
async function f() {
  try { return doAsync(); }            // returns the promise; rejection lands on the CALLER
  catch (e) { /* can't catch here */ }
}

// ✅ await (or return await) so the catch frame is on the stack when it rejects
try { await doAsync(); } catch (e) { handle(e); }
async function g() { try { return await doAsync(); } catch (e) { handle(e); } }
```

Also: an error thrown inside a `setTimeout`/`setImmediate`/event-emitter callback runs on a **later tick**, so no surrounding `try/catch` (or `.catch`) can see it — only `process.on('uncaughtException')` does. And a `.catch` attached to the wrong link in a promise chain can miss an earlier rejection. Rules: **always `await` promises you mean to guard**, attach a `.catch` to every fire-and-forget promise, and keep a last-resort `unhandledRejection` handler that logs and exits (it crashes by default since Node 15).

#### Q104. [Coding] Validate and parse environment config at startup so the app fails fast on misconfiguration.

```js
const { z } = require('zod');

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(3000),   // coerce: env is a string
  DATABASE_URL: z.string().url(),
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
  ENABLE_CACHE: z.coerce.boolean().default(false),
});

function loadConfig() {
  const parsed = EnvSchema.safeParse(process.env);
  if (!parsed.success) {
    console.error('Invalid environment configuration:');
    console.error(parsed.error.flatten().fieldErrors);
    process.exit(1);                 // fail fast — don't boot half-configured
  }
  return Object.freeze(parsed.data); // typed, coerced, immutable config object
}

const config = loadConfig();         // config.PORT is a real number, not a string
```

The win is **fail-fast at boot** instead of a mysterious runtime error hours later when some code path finally reads a missing/invalid var. The schema also **coerces types** (env vars are always strings — `z.coerce.number()`/`boolean()` fix the "everything is a string" trap), supplies **defaults**, and produces one **typed, frozen** config object you pass around instead of sprinkling `process.env.X` everywhere. Load `.env` first (`node --env-file=.env` natively, or `dotenv`) so local development populates the vars before validation.

### 🔴 — extended

#### Q105. [Practical] Under high load your service starts returning errors and CPU is pinned, but profiling shows the CPU is mostly idle in your own code. What's likely happening and how do you confirm it?

"CPU pinned but my code is idle" points away from your JS hot path and toward **the runtime, the thread pool, or GC** — places a naive CPU profile of application code under-attributes.

Likely causes and confirmation:
1. **GC thrash.** A high allocation rate or a near-full old space makes V8 spend most cycles in **major GC**. Confirm with `--trace-gc` (frequent long Mark-Compact pauses) and GC entries in `perf_hooks`; RSS sitting near `--max-old-space-size` is the tell. Often this is actually a **leak** forcing constant GC — heap-snapshot it.
2. **Thread-pool saturation.** A burst of `fs`/`crypto`/`dns.lookup` saturates the 4-thread libuv pool, so requests queue and tail latency explodes while the main thread looks idle. Confirm by counting concurrent pool operations and watching whether raising `UV_THREADPOOL_SIZE` or caching DNS helps.
3. **Event-loop blocked by occasional sync work** (ReDoS, big `JSON.parse`) — `monitorEventLoopDelay` p99 spikes pinpoint it; a flame graph over the blocked window shows the wide bar.
4. **Native addon / regex / V8 internals** — a self-time-attributing CPU profile (`--cpu-prof`, `--prof`) shows whether the time is in `node`/V8 builtins rather than your functions.

Method: layer the signals — ELU/loop-delay (is the loop blocked?), `--trace-gc` (is it GC?), thread-pool counters (is fs/crypto queuing?), and a CPU profile (where is self-time?). The staff move is to instrument the **runtime**, not just your application code, because the bottleneck is frequently below your code.

#### Q106. [Coding] Implement a token-bucket rate limiter to protect a downstream from your own bursts.

```js
class TokenBucket {
  constructor({ capacity, refillPerSec }) {
    this.capacity = capacity;
    this.tokens = capacity;
    this.refillPerSec = refillPerSec;
    this.last = Date.now();
  }

  #refill() {
    const now = Date.now();
    const elapsedSec = (now - this.last) / 1000;
    this.tokens = Math.min(this.capacity, this.tokens + elapsedSec * this.refillPerSec);
    this.last = now;
  }

  tryRemove(n = 1) {            // non-blocking: succeed or fail immediately
    this.#refill();
    if (this.tokens >= n) { this.tokens -= n; return true; }
    return false;
  }

  async remove(n = 1) {        // blocking: wait until enough tokens accrue
    for (;;) {
      if (this.tryRemove(n)) return;
      const deficit = n - this.tokens;
      const waitMs = (deficit / this.refillPerSec) * 1000;
      await new Promise((r) => setTimeout(r, Math.max(waitMs, 5)));
    }
  }
}

const bucket = new TokenBucket({ capacity: 100, refillPerSec: 50 });
async function callApi(req) {
  await bucket.remove();       // throttle ourselves to ~50 rps, bursts up to 100
  return fetch('/downstream', req);
}
```

Token bucket allows **bursts up to `capacity`** while enforcing a steady **average rate** (`refillPerSec`) — a better fit than a fixed window (which has boundary-burst problems) for protecting a downstream you must be a good citizen toward. Note this is **per-process**; with multiple instances/pods you need a **distributed** limiter (Redis with `INCR`+TTL or a Lua token-bucket script) so the global rate is bounded, not just each instance's. For inbound protection, `express-rate-limit` with a Redis store is the off-the-shelf equivalent.

#### Q107. [Practical] You're migrating a large monolith from CommonJS to ESM and hit `ERR_REQUIRE_ESM` and named-import failures. How do you reason through the interop and sequence the migration?

The errors are the two classic interop edges. `ERR_REQUIRE_ESM` is CJS trying to `require()` an ESM-only package (historically impossible; **Node 22+ supports `require()` of a synchronous ESM graph**, which removes much of this pain — but a package with top-level `await` still can't be `require`d). Named-import failures from a CJS package happen because Node statically analyzes CJS exports heuristically; some named exports aren't detected, so you must `import pkg from 'cjs'; const { x } = pkg;`.

Reasoning and sequencing:
- **Know the interop rules.** ESM can always `import` CJS (the `module.exports` becomes the default export). CJS↔ESM is the friction; dynamic `import()` is the universal escape hatch from CJS into ESM.
- **Migrate leaf modules first** (lowest blast radius), keep the app shippable at every commit, and lean on dual-mode interop so converted and unconverted modules coexist.
- **Set the package type deliberately** — flip `"type": "module"` per-package, or use `.mjs`/`.cjs` extensions to mix during transition; use the `"exports"` field with conditional `import`/`require` for dual packages.
- **Automate and guard** — codemods for `require`→`import`, ESLint rules to prevent backsliding and to enforce `node:` prefixes, and CI running the test suite on every step.
- **Watch the gotchas ESM introduces** — no `__dirname`/`require` (use `import.meta`), `"exports"` maps can block deep imports you relied on, and JSON imports need an import attribute (`with { type: 'json' }`).

The throughline: incremental, leaf-first, test-gated, with dynamic `import()` as the bridge — never a big-bang flip.

#### Q108. [Coding] Build a deduplicating in-flight request cache (single-flight) so concurrent identical requests share one downstream call.

```js
class SingleFlight {
  constructor() { this.inFlight = new Map(); }   // key -> Promise

  async do(key, fn) {
    const existing = this.inFlight.get(key);
    if (existing) return existing;               // piggyback on the in-flight call

    const promise = (async () => {
      try { return await fn(); }
      finally { this.inFlight.delete(key); }     // clear once settled (success OR failure)
    })();

    this.inFlight.set(key, promise);
    return promise;
  }
}

const sf = new SingleFlight();
// 100 concurrent requests for user 42 → ONE db/API call, all share the result
async function getUser(id) {
  return sf.do(`user:${id}`, () => db.query('SELECT * FROM users WHERE id=$1', [id]));
}
```

This collapses a **thundering herd** — when many requests arrive for the same uncached key simultaneously (e.g., a cache miss/expiry stampede), they'd otherwise each hit the database. Single-flight lets the first request make the call and every concurrent duplicate **awaits the same promise**, cutting load dramatically. Critical detail: delete the entry in `finally` (not just on success) so a failed call doesn't poison the cache forever, and so the next request after settlement triggers a fresh call. Pair it with a short-TTL result cache for the full stampede-protection pattern; libraries like `dataloader` apply the same idea with per-tick batching.

#### Q109. [Practical] A worker thread you spawned for CPU work is itself slow to start and your tail latency got worse, not better. What went wrong and how do you fix it?

Spawning a `Worker` is **not free**: each one boots a fresh **V8 isolate** (new heap, re-parsing/compiling the worker script, re-`require`ing its module graph) — often tens of milliseconds to hundreds. If you spawn **one worker per task** on a hot path, that startup cost is paid per request and can exceed the compute you offloaded, making tail latency worse.

Fixes:
- **Pool and reuse workers** sized to `os.availableParallelism()`, queuing tasks when all are busy — amortize the startup cost across many tasks instead of paying it each time. Use **Piscina** (production-grade: queueing, timeouts, `transferList`, resource limits) rather than hand-rolling.
- **Only offload work that's actually expensive enough** to beat the round-trip + serialization cost. Tiny tasks aren't worth a worker hop; the message-passing overhead (structured clone) dominates.
- **Avoid copying big payloads** — use `transferList` to move an `ArrayBuffer` (zero-copy) or `SharedArrayBuffer` + `Atomics` for shared state, rather than cloning megabytes per message.
- **Right-size the pool** — too many workers oversubscribe cores and add context-switching; too few queue tasks. Match it to physical cores and measure ELU on the main thread to confirm the offload actually freed the loop.

The staff-level principle: workers help **CPU-bound** work, but only when pooled and when the task is large relative to spawn + serialization overhead; otherwise you've added latency, not removed it.

#### Q110. [Coding] Trace a request end-to-end across async boundaries with `AsyncLocalStorage`, including a case where context is lost and how to restore it.

```js
const { AsyncLocalStorage, AsyncResource } = require('node:async_hooks');
const als = new AsyncLocalStorage();

// Establish per-request context at the edge
app.use((req, res, next) => {
  als.run({ traceId: req.headers['x-trace-id'] ?? crypto.randomUUID() }, () => next());
});

const trace = () => als.getStore()?.traceId ?? 'no-trace';

// ✅ Context follows awaits, .then, timers, and I/O callbacks automatically
async function handler() {
  console.log(trace());          // correct id
  await db.query('...');
  console.log(trace());          // STILL correct after the await
}

// ❌ Context is LOST when a callback is registered on a long-lived emitter/pool,
//    because it runs in the context where it FIRED, not where it was registered
const pool = getSharedEmitter();
pool.on('result', () => console.log(trace()));   // 'no-trace' — wrong/detached context

// ✅ Re-bind the callback to the current context with AsyncResource.bind
pool.on('result', AsyncResource.bind(() => console.log(trace())));  // restored
```

`AsyncLocalStorage` propagates a store along the async-resource chain, so `getStore()` returns the right value across `await`s, promises, timers, and I/O **within one logical request** — the foundation of request-scoped logging and OpenTelemetry trace propagation. The failure mode: callbacks attached to **shared, long-lived resources** (a pooled connection, a module-level `EventEmitter`, some custom thenables) execute in whatever context fired them, so the store is lost. `AsyncResource.bind(fn)` captures the current context and re-applies it when the callback later runs, restoring correct tracing. Knowing both the propagation guarantee and its boundaries is what separates "I used ALS" from "I understand why my trace IDs sometimes vanish."

#### Q111. [Practical] A native addon (or a dependency using one) occasionally segfaults and takes the whole process down. How do you contain the blast radius in production?

A segfault in native code (C/C++ addon, or a transitive dep with one) crashes the **entire process** — JS error handling (`try/catch`, `uncaughtException`) **cannot** catch a native crash, because it's below the V8 layer. Containment is architectural, not a code `catch`.

Strategies, roughly in order:
- **Isolate the native work in a separate process** (a child process or a dedicated worker service) communicating over IPC, so a segfault kills only that worker, not your request-serving process. A supervisor restarts it. This is the strongest blast-radius reduction.
- **Run one process per container** under an orchestrator (k8s) with liveness probes and automatic restarts, plus enough replicas that one crash doesn't drop availability — combined with **graceful-degradation** fallbacks for the feature the addon powers.
- **Capture forensics** — enable Node **diagnostic reports** (`--report-on-fatalerror`) so each crash writes a JSON post-mortem (native + JS stacks, libuv handles), and collect core dumps to file an upstream bug.
- **Reduce or replace the native surface** — prefer **WebAssembly** (sandboxed, a Wasm trap is catchable and can't segfault the host) or a Rust binding (`napi-rs`/`neon`, memory-safe) over a raw C++ addon; pin and vet versions, since a native crash is often a specific-version regression.

The principle: you cannot make an in-process native segfault recoverable, so you **move it out of process** and rely on supervision + redundancy to keep the service up, while collecting diagnostics to fix the root cause.

#### Q112. [Behavioral] You inherit a Node service with frequent production incidents — silent hangs, memory growth, and no observability. As the senior owner, how do you stabilize it?

Frame it as **stop the bleeding → see clearly → fix root causes → prevent recurrence**, with stakeholder communication throughout:

"First I'd **stop the bleeding** with low-risk operational guardrails that don't require understanding every bug yet: graceful shutdown on `SIGTERM`, request timeouts (`server.requestTimeout`) so silent hangs become visible 408s instead of stuck sockets, body-size limits, and a process manager / k8s liveness probe so a wedged process restarts automatically. These buy breathing room.

Then I'd **make the system observable**, because you can't fix what you can't see: structured logging (pino) with request/trace IDs via `AsyncLocalStorage`, RED metrics plus the Node-specific canaries — **event-loop delay/utilization**, heap/RSS, GC pause time, thread-pool saturation — and on-demand heap snapshots and diagnostic reports. The memory growth and hangs that were 'mysterious' usually become obvious once ELU and heap trends are on a dashboard.

With visibility, I'd **attack root causes by impact**: heap-snapshot the leak (almost always an unbounded cache or un-removed listener), find what blocks the loop (sync CPU/JSON/regex) from the loop-delay spikes and flame graphs, and fix the highest-frequency incidents first — each with a **regression guard** (a test or an alert) so it can't silently return.

Finally I'd **prevent recurrence and share the load**: CI gates (lint banning `*Sync` on hot paths, dependency scanning), a production-readiness checklist, a profiling runbook so the whole team can debug, and a blameless post-incident review per incident to feed fixes back. Throughout, I'd communicate a clear before/after — incident rate, p99, restart frequency — so leadership sees stabilization as measurable progress, not invisible firefighting."

What's evaluated: prioritizing **operational safety nets before deep fixes**, treating **observability as the prerequisite** to fixing anything, using the **right Node signals** (ELU, heap, GC) rather than generic CPU, methodical root-cause work with regression guards, and the leadership dimensions — communicating impact, building team capability, and instituting process so the service stays stable after you move on.

## ✅ Key Takeaways

- Node runs your JS on a **single thread** with an **event loop** (libuv), achieving high concurrency via **non-blocking I/O** — ideal for I/O-bound workloads, poor for unoffloaded CPU work.
- Know the **phase order** (timers → pending → poll → check → close) and that **microtasks** (`process.nextTick`, then promises) drain between every callback and phase.
- Prefer **promises/async-await** over callbacks; run independent work with `Promise.all`; bound concurrency to protect downstream resources.
- Use **streams + `pipeline()`** for large data to keep memory constant and **respect backpressure**.
- Offload CPU work to **worker threads** (or a job queue); scale connections with **cluster** or, more often, container orchestration.
- Handle errors per async style, never ignore `unhandledRejection`/`uncaughtException`, and implement **graceful shutdown** on `SIGTERM`.
- Profile with **heap snapshots**, **flame graphs**, **event-loop delay/utilization**; measure before optimizing.

## ⚠️ Common Pitfalls

- Using `*Sync` APIs or doing heavy CPU/regex/JSON work on the main thread, blocking every request.
- **Microtask starvation** from recursive `process.nextTick`/promise chains stalling timers and I/O.
- Ignoring stream backpressure (manual `write()` loops, `pipe()` instead of `pipeline()`), causing memory blowups and leaked FDs on errors.
- Unbounded caches/maps, un-removed event listeners, and uncleared `setInterval`s causing memory leaks.
- Assuming `try/catch` catches callback or cross-tick errors; forgetting to handle `'error'` on streams/emitters (which crashes the process).
- Calling `process.exit()` and truncating buffered logs/in-flight I/O instead of draining and setting `process.exitCode`.
- Treating `cluster` workers as if they share memory, or skipping sticky sessions for WebSockets.
- Leaving `unhandledRejection` unhandled (crashes by default since Node 15) and using `Buffer.allocUnsafe` where uninitialized memory can leak.

## 📚 Further Reading

- Official Node.js docs — Event Loop, Timers, and `process.nextTick` guide — https://nodejs.org/en/learn
- libuv design overview — https://docs.libuv.org/en/v1.x/design.html
- Node.js Streams documentation and the "Backpressuring in Streams" guide — https://nodejs.org/api/stream.html
- *Node.js Design Patterns* (Casciaro & Mammino) — async patterns, streams, scalability.
- V8 blog on garbage collection (Orinoco) and the "Trash Talk" series — https://v8.dev/blog
- OpenTelemetry JS and `pino` for production observability — https://opentelemetry.io/docs/languages/js/
- Node.js security best practices (official) and the OWASP Node.js guidance — https://nodejs.org/en/learn/getting-started/security-best-practices
