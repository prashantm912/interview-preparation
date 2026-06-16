# Java Concurrency & Multithreading

A deep, interview-focused tour of the JVM concurrency model — from thread lifecycle and the Java Memory Model (JMM) to virtual threads, structured concurrency, lock-free data structures, and the classic race-condition coding problems. Knowledge current through Java 21/23 and 2026 production practice.

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

### Q1. [Theory] Walk through the Java thread lifecycle and the `Thread.State` values.

A Java thread moves through six states defined in `Thread.State`:

```
            start()                  scheduler picks it
 NEW ─────────────────► RUNNABLE ◄──────────────────┐
  │                       │  │                       │
  │              wait()/  │  │ blocked on monitor    │ notify()/
  │              join()/  │  └──────────────► BLOCKED │ lock released
  │              park()   │                           │
  │                       ▼                           │
  │                  WAITING / TIMED_WAITING ──────────┘
  │                       │
  └───────────────────────┴──────────► TERMINATED (run() returns)
```

- **NEW** — created but `start()` not yet called.
- **RUNNABLE** — eligible to run; the OS scheduler decides when it actually executes on a core (Java does not distinguish "ready" from "running").
- **BLOCKED** — waiting to acquire a monitor lock to enter a `synchronized` block.
- **WAITING** — indefinitely parked via `Object.wait()`, `Thread.join()`, or `LockSupport.park()`.
- **TIMED_WAITING** — same but with a timeout (`sleep(ms)`, `wait(ms)`).
- **TERMINATED** — `run()` has completed or thrown.

The "why" that matters in interviews: `BLOCKED` is specifically about monitor contention, whereas waiting on a `ReentrantLock` shows up as `WAITING`/`TIMED_WAITING` via `LockSupport`, not `BLOCKED`. Knowing this distinction helps you read thread dumps correctly.

### Q2. [Theory] What is the difference between `Runnable` and `Callable`?

`Runnable.run()` returns `void` and cannot throw checked exceptions; `Callable<V>.call()` returns a value `V` and may throw checked exceptions. You submit a `Callable` to an `ExecutorService` and get back a `Future<V>` to retrieve the result. `Runnable` predates the `java.util.concurrent` framework (it existed since Java 1.0); `Callable` arrived with the executor framework in Java 5 precisely to fix the "no return value, no checked exceptions" limitations. Use `Runnable` for fire-and-forget side-effecting tasks, `Callable` when you need a computed result or error propagation.

### Q3. [Practical] Why should you almost never call `Thread.run()` directly, and why is `Thread.stop()` deprecated?

Calling `run()` directly executes the body **on the current thread** — no new thread is created — so you get no concurrency at all, a classic beginner bug. You must call `start()`, which asks the JVM/OS to allocate a new thread and then invoke `run()` on it. `Thread.stop()` is deprecated (and removed/degraded in modern JDKs) because it asynchronously throws `ThreadDeath` at an arbitrary point, which can release monitor locks while shared objects are in an inconsistent half-updated state — corrupting data with no recovery. The correct cooperative pattern is an interruption flag:

```java
class Worker implements Runnable {
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            // do a unit of work; check the flag each iteration
        }
    }
}
// elsewhere: workerThread.interrupt();
```

### Q4. [Theory] What does `volatile` guarantee, and what does it NOT guarantee?

`volatile` guarantees **visibility** and **ordering** but not **atomicity** for compound actions. Specifically: a write to a volatile variable is immediately visible to all threads (no caching in a register/CPU cache), and it establishes a happens-before edge — everything before the write is visible to a thread that subsequently reads it. What it does *not* give you is atomic read-modify-write: `volatile int count; count++;` is still a race because `++` is read-modify-write (three operations). For a single flag toggled by one thread and read by others (e.g., a `volatile boolean running`), `volatile` is perfect and far cheaper than a lock.

### Q5. [Coding] Demonstrate a race condition on a shared counter and fix it three ways.

**Problem:** Two threads each increment a shared counter 1,000,000 times. The final value is often less than 2,000,000 due to lost updates.

```java
// BROKEN: ++ is read-modify-write, not atomic
class Broken { int count; void inc() { count++; } }

// FIX 1: synchronized — mutual exclusion via monitor
class SyncCounter {
    private int count;
    synchronized void inc() { count++; }
    synchronized int get() { return count; }
}

// FIX 2: AtomicInteger — lock-free CAS, best for a single hot counter
class AtomicCounter {
    private final java.util.concurrent.atomic.AtomicInteger count =
        new java.util.concurrent.atomic.AtomicInteger();
    void inc() { count.incrementAndGet(); }
    int get() { return count.get(); }
}

// FIX 3: LongAdder — best under HIGH contention (Java 8+)
class AdderCounter {
    private final java.util.concurrent.atomic.LongAdder count =
        new java.util.concurrent.atomic.LongAdder();
    void inc() { count.increment(); }
    long get() { return count.sum(); }
}
```

- **Time/Space:** All operations are O(1). `synchronized` serializes threads (throughput drops under contention). `AtomicInteger` uses a CAS retry loop — great with low contention, but the loop spins under high contention. `LongAdder` shards the count across cells to reduce contention, trading more memory (O(number of contending threads)) for far higher write throughput; `sum()` is only eventually consistent during concurrent updates.
- **Edge cases:** `get()` must also be synchronized/atomic, otherwise a reader may see a stale value. Choose `LongAdder` for write-heavy metrics counters, `AtomicInteger` when you frequently need the exact current value.

### Q6. [Theory] What is a daemon thread?

A daemon thread is a background thread that does **not** prevent the JVM from exiting — when all non-daemon (user) threads finish, the JVM shuts down and abruptly terminates any remaining daemon threads (their `finally` blocks may not run). You set it with `t.setDaemon(true)` *before* `start()`. The `main` thread is a user thread; GC and JIT compiler threads are daemons. Use daemons for housekeeping (heartbeats, metric flushers) where abrupt termination on shutdown is acceptable, but never for work that must complete cleanly (e.g., flushing a write buffer) since you cannot guarantee it finishes.

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] Explain the Java Memory Model and the happens-before relationship.

The JMM defines when a write by one thread becomes visible to a read by another. Without it, the compiler, JIT, and CPU are free to reorder and cache operations, so a thread could see stale or out-of-order values. The model is built on the **happens-before** partial order: if action A happens-before B, then A's effects are visible to B. Key happens-before edges:

```
Program order:   within one thread, earlier statement HB later statement
Monitor lock:    unlock(m) HB subsequent lock(m)
Volatile:        write(v) HB subsequent read(v)
Thread start:    t.start() HB everything in t
Thread join:     everything in t HB t.join() returning
Final fields:    constructor end HB read of properly-published final field
Transitivity:    A HB B and B HB C  ⇒  A HB C
```

The practical payoff: you do not reason about caches or fences directly — you establish a happens-before edge (lock, volatile, etc.) and the JMM guarantees visibility. Data races (conflicting accesses with no happens-before ordering) produce undefined, platform-dependent behavior.

### Q8. [Theory] `synchronized` vs `ReentrantLock` — when do you choose each?

Both are reentrant mutual-exclusion locks, but `ReentrantLock` is more flexible:

| Feature | `synchronized` | `ReentrantLock` |
|---|---|---|
| Acquire | Implicit (block/method) | Explicit `lock()`/`unlock()` |
| Try-with-timeout | No | `tryLock(time, unit)` |
| Interruptible acquire | No | `lockInterruptibly()` |
| Fairness option | No | `new ReentrantLock(true)` |
| Condition variables | One implicit (`wait/notify`) | Multiple `Condition` objects |
| Release on exception | Automatic (JVM) | Must use `finally` |

Default to `synchronized` for simplicity — it auto-releases on exceptions, the JVM can biased-lock/optimize it, and it is harder to leak a lock. Reach for `ReentrantLock` when you need a timeout (to avoid indefinite deadlock), interruptibility, fairness, or multiple wait-sets (e.g., separate "not full" / "not empty" conditions in a bounded buffer). Always release in `finally`:

```java
lock.lock();
try { /* critical section */ }
finally { lock.unlock(); }
```

### Q8b. [Coding] Implement a thread-safe bounded blocking queue with `ReentrantLock` and two `Condition`s.

```java
import java.util.concurrent.locks.*;

class BoundedQueue<T> {
    private final Object[] items;
    private int head, tail, count;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    BoundedQueue(int capacity) { items = new Object[capacity]; }

    public void put(T x) throws InterruptedException {
        lock.lock();
        try {
            while (count == items.length) notFull.await(); // guard in WHILE, not IF
            items[tail] = x;
            tail = (tail + 1) % items.length;
            count++;
            notEmpty.signal();
        } finally { lock.unlock(); }
    }

    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) notEmpty.await();
            T x = (T) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            notFull.signal();
            return x;
        } finally { lock.unlock(); }
    }
}
```

- **Why `while` not `if`:** guards must be re-checked on wakeup because of **spurious wakeups** and because another thread may have grabbed the slot between signal and reacquire.
- **Two conditions:** lets producers wait only on "not full" and consumers only on "not empty", avoiding the thundering-herd of a single condition. **Time/Space:** O(1) per op, O(capacity) space. In production you would just use `ArrayBlockingQueue`, which is exactly this pattern.

### Q9. [Theory] Compare the thread pools created by `Executors` factory methods. Why prefer a raw `ThreadPoolExecutor`?

```
newFixedThreadPool(n)      → n core threads,  UNBOUNDED LinkedBlockingQueue   (OOM risk!)
newCachedThreadPool()      → 0..Integer.MAX threads, SynchronousQueue          (thread explosion!)
newSingleThreadExecutor()  → 1 thread, unbounded queue
newScheduledThreadPool(n)  → delayed/periodic tasks
newWorkStealingPool()      → ForkJoinPool, one queue per worker (Java 8+)
newVirtualThreadPerTaskExecutor() → one virtual thread per task (Java 21+)
```

The hidden danger: `newFixedThreadPool` and `newSingleThreadExecutor` use an **unbounded** queue — under overload, tasks pile up until you hit `OutOfMemoryError` with no backpressure. `newCachedThreadPool` can spawn unbounded threads. In production, construct `ThreadPoolExecutor` directly so you control `corePoolSize`, `maxPoolSize`, a **bounded** `workQueue`, a `ThreadFactory` (for named threads + uncaught-exception handler), and a `RejectedExecutionHandler` (e.g., `CallerRunsPolicy` for natural backpressure). This makes overload behavior explicit instead of catastrophic.

### Q10. [Practical] How do you size a thread pool?

Start from the workload type:

- **CPU-bound:** threads ≈ number of cores (`Runtime.getRuntime().availableProcessors()`), maybe +1 to cover occasional page faults. More threads just add context-switch overhead.
- **I/O-bound:** use Little's Law / Brian Goetz's formula: `threads = cores × targetUtilization × (1 + waitTime/computeTime)`. If a request spends 90% waiting on the DB and 10% computing, the ratio is 9, so you can run roughly `cores × 10` threads to keep cores busy.

In practice you measure: instrument queue depth, latency, and CPU, then tune. The bigger 2026 shift: for I/O-bound request handling, **virtual threads** (Java 21) let you skip pool sizing entirely — you create a thread per task and the platform multiplexes them onto a small carrier pool, so the "I/O-bound sizing math" largely disappears for blocking-style code.

### Q11. [Theory] `Future` vs `CompletableFuture` — what does `CompletableFuture` add?

`Future` (Java 5) is a one-shot handle: you can `get()` (blocking), `cancel()`, and check `isDone()` — but you **cannot** compose, chain, or attach callbacks; you are forced to block. `CompletableFuture` (Java 8) implements `CompletionStage` and adds a fluent, non-blocking pipeline: `thenApply`, `thenCompose` (flat-map for nested futures), `thenCombine` (join two), `allOf`/`anyOf`, `exceptionally`/`handle` for error recovery, and explicit executor control via the `*Async` variants. This lets you build asynchronous dependency graphs without blocking threads.

```java
CompletableFuture
    .supplyAsync(() -> fetchUser(id), ioPool)
    .thenCompose(user -> fetchOrdersAsync(user))      // flat-map
    .thenApply(orders -> summarize(orders))
    .exceptionally(ex -> fallbackSummary(ex))         // recover
    .thenAccept(this::render);
```

Gotcha: the default executor is the common `ForkJoinPool`, which is shared JVM-wide and sized to cores — never run blocking I/O on it; pass your own executor to `*Async`.

### Q11b. [Coding] Fan out N async calls, combine results, and apply a global timeout.

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

List<CompletableFuture<Quote>> futures = vendors.stream()
    .map(v -> CompletableFuture.supplyAsync(() -> v.getQuote(req), ioPool)
                               .exceptionally(ex -> Quote.unavailable(v))) // isolate failures
    .collect(Collectors.toList());

CompletableFuture<Void> all =
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

List<Quote> quotes = all
    .orTimeout(800, TimeUnit.MILLISECONDS)                 // Java 9+ global deadline
    .handle((v, ex) -> futures.stream()
            .filter(f -> f.isDone() && !f.isCompletedExceptionally())
            .map(CompletableFuture::join)
            .collect(Collectors.toList()))
    .join();
```

- **Pattern:** scatter/gather with per-call isolation (`exceptionally` per future so one slow vendor does not poison the batch) plus a hard `orTimeout` deadline. **Time:** wall-clock ≈ max(call latencies) bounded by 800 ms. **Edge cases:** decide whether timed-out vendors count as "unavailable" or are dropped; `orTimeout` does not cancel the in-flight HTTP call, so also set a per-client read timeout to actually free resources.

### Q12. [Theory] How does `ForkJoinPool` work and what is work-stealing?

`ForkJoinPool` targets recursively divisible CPU-bound tasks. Each worker thread has its own **double-ended deque** (work-stealing queue). A worker pushes/pops its own subtasks from the *head* (LIFO, cache-friendly), but when its deque is empty it **steals** from the *tail* of a busy worker's deque (FIFO). This keeps all cores busy with minimal contention and naturally balances uneven splits.

```
Worker-0 deque: [A1 A2 A3]   Worker-1 deque: [ ]  ← idle, steals from tail
                   ▲ pop head      ▲ steal pulls A1 from the other end
```

You subclass `RecursiveTask<V>` (returns a value) or `RecursiveAction` (void), `fork()` one half and compute the other inline, then `join()`. Parallel streams use the common `ForkJoinPool` under the hood. Pitfall: a `join()` that blocks on external I/O starves the pool, because there are only `cores` workers by default — use `ManagedBlocker` or a dedicated pool for blocking work.

### Q13. [Coding] Sum a large array using `ForkJoinPool` / `RecursiveTask`.

```java
import java.util.concurrent.*;

class SumTask extends RecursiveTask<Long> {
    private static final int THRESHOLD = 10_000;
    private final long[] a; private final int lo, hi;
    SumTask(long[] a, int lo, int hi) { this.a = a; this.lo = lo; this.hi = hi; }

    protected Long compute() {
        if (hi - lo <= THRESHOLD) {                 // base case: compute sequentially
            long sum = 0;
            for (int i = lo; i < hi; i++) sum += a[i];
            return sum;
        }
        int mid = (lo + hi) >>> 1;
        SumTask left = new SumTask(a, lo, mid);
        left.fork();                                // schedule async
        SumTask right = new SumTask(a, mid, hi);
        long r = right.compute();                   // compute one half inline
        long l = left.join();                       // wait for the forked half
        return l + r;
    }
}
// long total = ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length));
```

- **Why fork one + compute the other:** forking both and joining both wastes a thread; the "fork one, compute the other, join" idiom keeps the current thread productive. **Time:** O(n) total work, O(log n) critical-path depth → ideal speedup ≈ cores. **Edge cases:** tune `THRESHOLD` (too small = scheduling overhead dominates; too large = poor parallelism); for plain summation `Arrays.stream(a).parallel().sum()` is simpler and uses the same machinery.

### Q14. [Theory] Explain `ConcurrentHashMap` internals (Java 8+) vs the old segmented design.

Pre-Java 8 it used lock striping: an array of ~16 `Segment`s, each its own lock, so up to 16 writers proceeded concurrently. Java 8 redesigned it: a single `Node[]` table where each **bucket** is locked independently (CAS for empty buckets, `synchronized` on the bin's head node for occupied ones), giving per-bucket concurrency instead of per-segment. Highlights:

- **Reads are lock-free** — nodes have `volatile` `val`/`next`, so `get()` never locks.
- Buckets convert from a linked list to a **red-black tree** when they exceed 8 entries (and the table is ≥ 64), bounding worst-case lookup at O(log n) and mitigating hash-collision DoS.
- Resizing is **concurrent**: multiple threads cooperatively transfer buckets, using a `ForwardingNode` sentinel to redirect lookups to the new table.
- `size()` uses a `LongAdder`-style striped counter (`baseCount` + `CounterCell[]`), so it is approximate under concurrent writes.

Crucially, `null` keys/values are forbidden (ambiguous with "absent" in lock-free reads), and aggregate operations are weakly consistent — iterators reflect state at/after creation and never throw `ConcurrentModificationException`.

### Q15. [Theory] When do you use `CountDownLatch` vs `CyclicBarrier` vs `Semaphore` vs `Phaser`?

- **`CountDownLatch`** — one-shot gate. Threads `await()` until `countDown()` reaches zero. Cannot be reset. Use: "start all workers once setup is done" or "main waits for N tasks to finish."
- **`CyclicBarrier`** — reusable rendezvous. N threads call `await()`; when all arrive, an optional barrier action runs and all proceed; then it resets. Use: iterative/phased parallel algorithms where threads sync each round.
- **`Semaphore`** — counting permits for resource throttling. `acquire()`/`release()`. Use: limit concurrent access (e.g., max 10 DB connections, rate limiting).
- **`Phaser`** — the flexible successor: dynamic registration/deregistration of parties and multi-phase coordination, combining latch + barrier semantics. Use: variable number of participants across phases (e.g., a dynamic worker set in a multi-stage pipeline).

```
CountDownLatch: ──countDown×N──► [0] gate opens, never resets
CyclicBarrier:  N arrive ─► barrierAction ─► all go ─► resets ─► repeat
Semaphore(3):   [● ● ●] permits; acquire blocks at 0, release returns a permit
Phaser:         parties can register()/arriveAndDeregister() between phases
```

### Q16. [Practical] You see threads stuck and CPU near zero. How do you diagnose a deadlock in production?

Capture a **thread dump**: `jstack <pid>`, `jcmd <pid> Thread.print`, or a kill -3 to stdout. The JVM's deadlock detector explicitly prints `Found one Java-level deadlock:` listing the cycle (Thread-A holds lock X waiting for Y; Thread-B holds Y waiting for X). For lock-ordering bugs that have not yet deadlocked, tools like `jconsole`/`VisualVM` (Detect Deadlock button) and async-profiler help. The structural cause is always the four Coffman conditions all holding simultaneously:

```
1. Mutual exclusion   — resource held exclusively
2. Hold and wait      — hold one, wait for another
3. No preemption      — cannot force-release
4. Circular wait      — A→B→…→A cycle
```

Break **any one** to prevent deadlock. The most practical: impose a **global lock ordering** (always acquire locks in a consistent order, e.g., by `System.identityHashCode` or a unique ID) to eliminate circular wait, or use `tryLock(timeout)` with backoff to break hold-and-wait.

### Q16b. [Coding] Fix a deadlock-prone transfer between two accounts.

```java
// DEADLOCK: thread 1 transfers A→B, thread 2 transfers B→A; lock order differs.
void transfer(Account from, Account to, long amt) {
    synchronized (from) { synchronized (to) { from.debit(amt); to.credit(amt); } }
}

// FIX: impose a consistent global lock order by a unique id.
void transferSafe(Account from, Account to, long amt) {
    Account first  = from.id < to.id ? from : to;
    Account second = from.id < to.id ? to   : from;
    synchronized (first) {
        synchronized (second) {
            from.debit(amt);
            to.credit(amt);
        }
    }
}
```

- **Why it works:** ordering locks by `id` removes the *circular wait* condition — every thread acquires locks in the same total order, so no cycle can form. **Edge case:** if `from.id == to.id` (same account), add an early guard, and consider `tryLock` with timeout + retry if you cannot derive a stable ordering key. **Time/Space:** O(1).

### Q17. [Theory] What is `ThreadLocal` good for, and what is the classic memory leak?

`ThreadLocal<T>` gives each thread its own independent copy of a value, avoiding sharing entirely — common for per-request context (user/trace IDs), non-thread-safe-but-expensive objects (`SimpleDateFormat`), and propagating MDC logging context. The leak: a `ThreadLocal`'s value is stored in the *thread's* `ThreadLocalMap`, keyed by a **weak reference** to the ThreadLocal but a **strong reference** to the value. In a thread pool, threads live forever, so if you forget `remove()`, the value (and everything it transitively holds, e.g., a heavy classloader or DTO) stays reachable — a slow leak and even a cross-request data bleed (one request seeing another's stale context). Always `remove()` in a `finally`, ideally in a servlet filter / interceptor.

```java
private static final ThreadLocal<RequestCtx> CTX = new ThreadLocal<>();
try { CTX.set(ctx); /* handle request */ }
finally { CTX.remove(); }   // mandatory in pooled threads
```

### Q18. [Theory] Differentiate deadlock, livelock, and starvation.

- **Deadlock** — threads block forever in a circular wait; none progresses and CPU is idle.
- **Livelock** — threads are *active* (not blocked) but keep responding to each other and never make progress — e.g., two people stepping aside in a hallway in sync. Often arises from naive deadlock-avoidance that releases and retries in lockstep; CPU is busy but useless work.
- **Starvation** — a thread is perpetually denied resources/CPU because others (e.g., higher-priority or greedy threads) monopolize them. A reader-writer lock that always favors readers can starve writers.

Fixes: deadlock → lock ordering/timeouts; livelock → randomized backoff to break the symmetry; starvation → fairness policies (`new ReentrantLock(true)`, fair semaphores) at the cost of throughput.

---

## 🟠 Advanced (8–12 yrs)

### Q19. [Theory] Explain the double-checked locking idiom and why `volatile` is mandatory.

Double-checked locking lazily initializes a singleton while locking only on the first call:

```java
class Holder {
    private static volatile Config instance;     // volatile is NOT optional
    static Config get() {
        Config local = instance;                  // read volatile once (perf)
        if (local == null) {
            synchronized (Holder.class) {
                local = instance;
                if (local == null) instance = local = new Config();
            }
        }
        return local;
    }
}
```

Without `volatile`, the JVM may **reorder** so that the reference is published *before* the constructor finishes — `new Config()` is (1) allocate, (2) construct, (3) assign reference, and 2 and 3 can be reordered. A second thread skipping the lock could then see a non-null but **partially constructed** object. `volatile` forbids that reordering and establishes happens-before so the fully constructed object is visible. Note: the cleaner idiom is the **initialization-on-demand holder** (a static nested class), which leverages JVM class-init guarantees and needs no `volatile`:

```java
class Lazy {
    private static class H { static final Config INSTANCE = new Config(); }
    static Config get() { return H.INSTANCE; }   // JVM guarantees thread-safe lazy init
}
```

### Q20. [Theory] What is false sharing and how does `@Contended` / padding fix it?

CPUs move memory in **cache lines** (typically 64 bytes). If two independent variables written by two different threads happen to live on the *same* cache line, each write invalidates the other core's cached copy via the cache-coherence protocol (MESI), causing "ping-pong" and silently destroying scalability even though there is no logical sharing — hence *false* sharing.

```
Cache line (64B): [ counterA | counterB | ... ]
Core 0 writes counterA → invalidates Core 1's line → Core 1 must refetch counterB. Repeat = stall.
```

Fixes: pad fields so each hot variable owns its own line, or use `@jdk.internal.vm.annotation.Contended` (JDK internal; `sun.misc.Contended` pre-9) with `-XX:-RestrictContended`. This is exactly why `LongAdder` shards into separate, padded `Cell`s. It is a real production concern in lock-free ring buffers — the LMAX Disruptor famously pads its sequence counters for this reason.

### Q21. [Theory] How do `compareAndSet` / CAS, the ABA problem, and `AtomicStampedReference` relate?

CAS (`compareAndSet(expected, new)`) atomically sets a value only if it still equals `expected`, otherwise fails — the hardware primitive (`LOCK CMPXCHG`/`LDREX/STREX`) underpinning all lock-free algorithms. You loop: read, compute, CAS, retry on failure. The **ABA problem**: a CAS sees the value is still `A` and succeeds, but in between it changed `A→B→A` — the pointer looks unchanged yet the world moved (a node was freed and reused). This corrupts lock-free stacks/queues. The fix is a **version stamp**: `AtomicStampedReference<V>` pairs the reference with an int stamp, so CAS checks `(ref, stamp)` together and the recycled-but-stamped value fails the check. `AtomicMarkableReference` is the cheaper one-bit variant for logical deletion.

### Q22. [Coding] Implement a lock-free Treiber stack with CAS.

```java
import java.util.concurrent.atomic.AtomicReference;

class LockFreeStack<T> {
    private static final class Node<T> { final T item; Node<T> next; Node(T i){ item = i; } }
    private final AtomicReference<Node<T>> head = new AtomicReference<>();

    public void push(T item) {
        Node<T> n = new Node<>(item);
        Node<T> cur;
        do {
            cur = head.get();
            n.next = cur;
        } while (!head.compareAndSet(cur, n));   // retry until CAS succeeds
    }

    public T pop() {
        Node<T> cur, next;
        do {
            cur = head.get();
            if (cur == null) return null;        // empty
            next = cur.next;
        } while (!head.compareAndSet(cur, next));
        return cur.item;
    }
}
```

- **Why lock-free:** no thread can block another; a slow thread cannot stall the system (no held lock). The CAS loop retries on contention. **Time:** amortized O(1), worst-case unbounded retries under heavy contention. **Edge cases / ABA:** because each `push` allocates a *new* Node (Java GC keeps freed nodes from being reused while referenced), classic ABA is largely sidestepped here; in manual-memory languages you would need a stamp or hazard pointers. Under extreme contention a lock or `LongAdder`-style sharding can outperform this. **Space:** O(n).

### Q23. [Practical] A `parallelStream()` in a Spring service is slow and starves other requests. What is happening and how do you fix it?

`parallelStream()` (and `CompletableFuture.*Async` without an explicit executor) runs on the **shared common `ForkJoinPool`**, sized to `cores − 1` and shared by the entire JVM. If your stream does blocking work (HTTP/DB calls) or a few large streams hog it, you starve every other parallel stream and async task process-wide — a notorious source of latency cliffs in microservices. Fixes, in order of preference:

1. Don't parallelize blocking I/O streams at all; use an explicit bounded executor with `CompletableFuture` instead.
2. If you must parallelize CPU work on a custom pool, run the stream inside `customForkJoinPool.submit(() -> stream.parallel()...).get()` — a documented (if hacky) way to bind the common-pool default to a private pool.
3. On Java 21, prefer **virtual threads** for the blocking-I/O fan-out; they don't consume the FJP at all.

The deeper lesson: `parallelStream` is only appropriate for CPU-bound, splittable, side-effect-free work over large datasets — measure before assuming it helps.

### Q24. [Theory] How do virtual threads work (Java 21) and what is "pinning"?

Virtual threads (Project Loom) are lightweight threads scheduled by the JVM, not the OS. Millions can exist because each costs a small heap object plus a growable stack, not a ~1 MB OS-thread stack. The JVM mounts a virtual thread onto a **carrier** (a platform thread in a `ForkJoinPool`); when the virtual thread hits a blocking call (socket read, `sleep`, `BlockingQueue`), the runtime **unmounts** it, frees the carrier to run another virtual thread, and remounts it when the call completes.

```
Carrier pool (≈ #cores platform threads)
   ┌── unmount on blocking I/O ──┐
VT-1 ─► carrier-A                │
VT-2 ───────────────► carrier-A ◄┘  (VT-1 parked, VT-2 runs same carrier)
…millions of VTs multiplexed onto few carriers…
```

This makes the simple **thread-per-request blocking style** scale like async/reactive code — without the callback complexity. **Pinning** is the catch: if a virtual thread blocks while inside a `synchronized` block or a native/JNI call, it cannot unmount and *pins* the carrier, defeating the benefit. In Java 21 the guidance was to replace `synchronized` around I/O with `ReentrantLock`; **JDK 24 (JEP 491) largely removed `synchronized` pinning**, but pinning on native frames remains. Also: don't pool virtual threads (create one per task) and avoid `ThreadLocal` heavy caching since you may have millions of them.

### Q25. [Coding] Use structured concurrency (Java 21 preview, `StructuredTaskScope`) to run two calls and fail fast.

```java
import java.util.concurrent.StructuredTaskScope;   // preview in 21–23

record UserPage(User user, List<Order> orders) {}

UserPage load(long id) throws InterruptedException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var userTask   = scope.fork(() -> fetchUser(id));     // child threads
        var ordersTask = scope.fork(() -> fetchOrders(id));

        scope.join();                 // wait for both
        scope.throwIfFailed();        // propagate first failure, cancels siblings

        return new UserPage(userTask.get(), ordersTask.get());
    }
}
```

- **What it buys you:** the scope owns the subtasks as a unit — if one fails, `ShutdownOnFailure` cancels the others (no orphaned/leaked threads), and the try-with-resources guarantees all children finish before the method returns (no thread leaks). This restores the parent/child clarity that raw `ExecutorService` + `Future` lost. `ShutdownOnSuccess` is the dual (first success wins, e.g., racing replicas). **Edge cases:** subtasks are cancelled via interruption, so they must be interruptible; the API is still evolving (preview), so pin your JDK version.

### Q26. [Theory] Explain `ReadWriteLock` and `StampedLock`, including optimistic reads.

`ReentrantReadWriteLock` allows multiple concurrent readers OR one writer — great for read-heavy data, but readers still pay locking overhead and writers can starve under constant reads. `StampedLock` (Java 8) is faster and adds an **optimistic read** mode: you `tryOptimisticRead()` to get a stamp *without* locking, read the fields, then `validate(stamp)` to check no writer intervened — if validation fails, fall back to a real read lock. This avoids cache-line contention entirely on the happy path.

```java
double distanceFromOrigin() {            // classic StampedLock example
    long stamp = sl.tryOptimisticRead(); // no lock acquired
    double cx = x, cy = y;               // read shared fields
    if (!sl.validate(stamp)) {           // a writer happened? retry under read lock
        stamp = sl.readLock();
        try { cx = x; cy = y; } finally { sl.unlockRead(stamp); }
    }
    return Math.sqrt(cx*cx + cy*cy);
}
```

Caveats: `StampedLock` is **not reentrant**, does not support `Condition`, and misuse (forgetting to validate, or holding an optimistic read across writes) silently returns torn reads. Use it for short, read-dominated critical sections on hot paths; otherwise `ReadWriteLock` or even plain `synchronized` is safer.

### Q27. [Practical] How do you make a legacy non-thread-safe component safe under load without rewriting it?

Several escalating strategies, choosing by contention and ownership:

1. **Confinement** — give each thread its own instance (`ThreadLocal`) or route all access through a single owner thread (actor/event-loop style). No locks needed; this is how Netty's `EventLoop` and many UI toolkits stay correct.
2. **Immutability** — wrap state in immutable snapshots and swap references via `AtomicReference` (copy-on-write). Readers never lock; writers replace. Great for rarely-changing config.
3. **Coarse external lock** — wrap calls in a single `synchronized`/`ReentrantLock` if contention is low; simplest, but a scalability bottleneck under load.
4. **Replace with a concurrent type** — swap `HashMap`→`ConcurrentHashMap`, `ArrayList`→`CopyOnWriteArrayList` (read-heavy), `StringBuilder`→`StringBuffer` only if truly shared.

Real-world example: a team wrapped a non-thread-safe `SimpleDateFormat` (a perennial bug source) in a `ThreadLocal` to fix intermittent date-parsing corruption under load, then later moved to immutable `java.time.DateTimeFormatter`, which is thread-safe by design — eliminating the workaround entirely. Always prefer "designed for concurrency" over "bolted-on locking."

---

## 🔴 Expert (15+ yrs)

### Q28. [Theory] Memory ordering: explain acquire/release semantics and how `VarHandle` exposes them in Java 9+.

Beyond volatile's sequential-consistency-for-volatiles, the JMM (aligned with the C++11 model) distinguishes ordering modes that map to hardware fences:

- **Plain** — no ordering guarantees; max optimization.
- **Opaque** — bitwise atomic + coherent (per-variable ordering) but no inter-variable ordering.
- **Acquire/Release** — a release store is not reordered with prior loads/stores; an acquire load is not reordered with subsequent ones. A release in one thread that is *read-acquire* by another establishes happens-before — cheaper than full volatile because it needs only one-directional fences.
- **Volatile (sequentially consistent)** — full bidirectional fence; a global total order across all volatile accesses.

`VarHandle` (Java 9, the safe replacement for `sun.misc.Unsafe`) exposes these via `getAcquire`/`setRelease`, `getOpaque`/`setOpaque`, `compareAndExchangeAcquire`, etc. Expert use: in a single-producer ring buffer you can publish with `setRelease` and consume with `getAcquire` instead of `volatile`, shaving fence cost on weakly-ordered CPUs (ARM) where full barriers are expensive. This is the level the Disruptor and JCTools operate at.

### Q29. [Theory] Walk through how a `ForkJoinPool` `ManagedBlocker` prevents pool starvation, and why it matters for Loom.

A `ForkJoinPool` has a fixed parallelism target (≈ cores). If a worker blocks (I/O, `get()` on a future computed elsewhere), the pool loses effective parallelism and can deadlock if all workers block waiting on tasks only other workers can run. `ForkJoinPool.ManagedBlocker` tells the pool "I'm about to block" via `block()`/`isReleasable()`; the pool responds by **compensating** — temporarily spawning/activating an extra worker so the parallelism target is maintained while you block. `CompletableFuture.get()` inside a FJP task and the `Phaser`/`SynchronousQueue` internals use this. The conceptual through-line to Loom: virtual threads generalize this — instead of *compensating* for a blocked carrier, the runtime *unmounts* the virtual thread so the carrier stays productive, which is strictly more scalable for I/O. Knowing this lineage shows you understand why Loom is a continuation of FJP's design, not a replacement of all concurrency.

### Q30. [Practical] You're architecting a high-throughput, low-latency trading/matching engine. Defend your concurrency design choices.

The principle: **avoid contention and GC pauses entirely** rather than synchronize faster.

```
Inbound ─► [ Ring Buffer (Disruptor) ] ─► single-writer business logic ─► Outbound
              ↑ pre-allocated slots          ↑ no locks, no shared mutable state
              ↑ sequence barriers (CAS)       (mechanical sympathy)
```

- **Single-writer principle** — route all order-book mutations through one thread so there is *no* lock contention on the hot path; CPUs are happiest writing memory they own exclusively.
- **LMAX Disruptor / ring buffer** — a pre-allocated, padded ring buffer with sequence counters avoids per-message allocation (no GC churn), false sharing (padding), and lock contention (CAS-published sequences). LMAX famously processed ~6M orders/sec on one thread this way.
- **Mechanical sympathy** — pin threads to cores (`taskset`/`isolcpus`), keep working sets in L1/L2, prefer arrays of primitives over object graphs to stay cache-friendly, and avoid autoboxing.
- **GC strategy** — pre-allocate and reuse objects (object pools / flyweight) and choose a low-pause collector (ZGC / Shenandoah, sub-ms pauses by 2026) so a stop-the-world pause never blows the latency SLA.
- **Backpressure** — bounded buffers with explicit overflow policy (reject/throttle) so a downstream slowdown cannot cause unbounded memory growth.

Trade-off honesty: this design sacrifices simplicity and general-purpose flexibility for predictable tail latency — only justified when p99.9 latency is the product. For ordinary CRUD services it would be massive over-engineering; there I'd reach for virtual threads + a bounded executor.

### Q31. [Behavioral] Tell me about a concurrency bug that reached production. How did you find and prevent it?

Strong answers follow situation → investigation → fix → systemic prevention. Example narrative: *"An intermittent NPE appeared only under load in a payment reconciliation job. Logs were useless because it was timing-dependent. I captured thread dumps and heap dumps during the spike and found two threads mutating a shared `HashMap` during resize, which can corrupt the bucket array and cause infinite loops or nulls. Short term I swapped it for `ConcurrentHashMap` and added a regression test using a `CyclicBarrier` to force concurrent access. Longer term I instituted three things: (1) a code-review checklist flagging shared mutable state, (2) static analysis (SpotBugs/Error Prone `@GuardedBy`) in CI to catch unguarded fields, and (3) load tests with the Java Concurrency Stress tool (jcstress) for our core data structures. The cultural lesson was that 'it passed unit tests' means nothing for concurrency — you must test under contention."* The interviewer is assessing rigor under uncertainty, instrumentation skill, and whether you fix the class of bug, not just the instance.

### Q32. [Theory] How would you design and reason about a correct lock-free SPSC/MPMC queue, and how do you verify its correctness?

Design dimensions: number of producers/consumers (SPSC is the simplest — a single-producer/single-consumer ring buffer needs only release/acquire on head and tail, no CAS), wait-free vs lock-free vs obstruction-free progress guarantees, and bounded vs unbounded. Key techniques: cache-line padding between head and tail indices (avoid false sharing), publishing the slot with `setRelease` and reading with `getAcquire`, and for MPMC, per-slot sequence numbers (the Vyukov bounded-MPMC algorithm) so producers/consumers claim slots via CAS on a monotonically increasing sequence rather than contending on a single tail.

Verification is the hard part — you cannot prove correctness by running it:

- **jcstress** (OpenJDK Java Concurrency Stress) — writes tiny actors and enumerates observed outcomes against the set the JMM *permits*, catching reordering bugs that appear once in 10^9 runs.
- **Linearizability checking** (e.g., Lincheck) — generates concurrent histories and checks they are equivalent to *some* sequential order.
- **Formal modeling** — TLA+/model checkers for the algorithm before coding.

The expert posture: lock-free code is "write once, verify exhaustively, never touch casually." Use a vetted library (JCTools, Agrona, Disruptor) in production rather than rolling your own — the cost of a subtle memory-ordering bug at scale dwarfs the dependency.

### Q33. [Practical] Reactive/CompletableFuture vs virtual threads in 2026 — how do you advise a team choosing for a new service?

By 2026 the decision has shifted decisively. **Virtual threads** let you write straightforward blocking, debuggable, stack-traced, thread-per-request code that scales to hundreds of thousands of concurrent I/O operations — the readability of synchronous code with the scalability formerly requiring reactive frameworks. I default new services to virtual threads (Spring Boot 3.2+ enables them with one property; the servlet stack, JDBC, and most blocking libraries work as-is) because they slash cognitive load, give clean stack traces, and integrate with existing imperative code and debuggers.

I keep **reactive (Reactor/RxJava)** when the team genuinely needs: rich streaming operators (backpressure, windowing, `flatMap` concurrency control), an end-to-end non-blocking stack already in place, or fine-grained flow-control semantics that thread-per-request does not express. The migration caveats I flag: audit for `synchronized`-around-I/O pinning (mitigated but verify your JDK ≥ 24 per JEP 491), avoid `ThreadLocal`-based caching that assumed few threads, and don't pool virtual threads. The anti-pattern to kill is teams adopting reactive purely for scalability they could now get for free — paying a permanent complexity tax for a problem Loom solved.

### Q34. [Theory] What security implications arise from concurrency bugs?

Concurrency defects are a real security surface, not just correctness bugs:

- **TOCTOU (time-of-check-to-time-of-use)** races — checking a permission/file/balance and then acting on it in two steps lets an attacker change state in between (privilege escalation, double-spend). Mitigate by making check-and-act atomic (single locked section or atomic CAS).
- **`ThreadLocal` leakage in pools** — forgetting `remove()` can leak one user's security/auth context into another request served by the same pooled thread — a cross-tenant data exposure. Always clear context in a `finally`.
- **Unbounded queues / thread creation** → resource-exhaustion DoS; a flood of requests fills an unbounded `ThreadPoolExecutor` queue until OOM. Bound queues and apply backpressure.
- **Hash-collision DoS** — pre-Java 8 `HashMap` degraded to O(n) lists on crafted colliding keys; the red-black-tree treeification (also in `ConcurrentHashMap`) bounds this to O(log n).
- **Improper publication** — a partially constructed object visible to another thread (the double-checked-locking-without-volatile bug) can bypass validation invariants set in the constructor.

The mindset: any shared mutable state crossing a trust boundary needs the same scrutiny as input validation.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q35. [Theory] Why is `++` on a `long` or `double` field potentially non-atomic at the bit level, and what does the JLS actually guarantee?

The JLS (§17.7) guarantees that reads and writes of **all reference types and all primitive types except `long` and `double`** are atomic. For `long` and `double`, a 64-bit value may legally be stored as **two separate 32-bit writes** on a 32-bit-oriented VM, so a concurrent reader (with no synchronization) can observe a "word-torn" value — the high 32 bits from one write and the low 32 bits from another, a number that was never actually assigned. This is independent of the `++` problem (which is a read-modify-write race); torn reads are about the *write itself* not being a single indivisible store.

In practice every mainstream 64-bit HotSpot build performs 64-bit loads/stores atomically, so you rarely see tearing today — but the *specification* still permits it, which is why the correct, portable rule is to declare shared `long`/`double` fields `volatile` (which forces 64-bit atomicity per the JLS) or use `AtomicLong`/`AtomicDouble`. Note the layering: `volatile` fixes the *tearing* of the store, but `volatile long x; x++;` is still a lost-update race because the increment is three operations.

```java
long sharedCounter;             // BAD: non-volatile long, may tear on read
volatile long safeRead;         // OK: 64-bit read/write is atomic, but ++ still races
AtomicLong both = new AtomicLong(); // both atomic read/write AND atomic increment
```

The interview "why" is to show you separate three distinct guarantees that beginners conflate: **store atomicity** (no tearing), **visibility** (no stale caching), and **compound-action atomicity** (no lost updates). Only the third needs CAS or a lock; the first two are what `volatile` buys you.

#### Q36. [Theory] What does `Thread.interrupt()` actually do, and why is it a cooperative signal rather than a forced stop?

`interrupt()` does not stop a thread — it sets the thread's internal **interrupt status flag** to true. That is the entire mechanism. What makes it useful is that blocking methods in the JDK *poll* this flag: `Object.wait`, `Thread.sleep`, `Thread.join`, `BlockingQueue.take`, `Lock.lockInterruptibly`, and NIO interruptible channels all check it, and if set they throw `InterruptedException` (or return early) **and clear the flag**. A pure CPU-bound loop that never calls a blocking method and never checks `isInterrupted()` will simply ignore the interrupt forever — there is no preemption.

This design is deliberate. `Thread.stop()` was deprecated precisely because asynchronously aborting a thread can leave shared state half-mutated and locks released mid-invariant. Interruption instead delivers a *request* to a thread at a point where the thread itself decides it is safe to unwind — cooperative cancellation. The crucial subtlety interviewers probe is the flag-clearing behavior:

```java
try {
    queue.take();                       // throws InterruptedException, CLEARS the flag
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // RESTORE the flag so callers up the stack see it
    return;                             // ...and unwind cooperatively
}
```

Swallowing `InterruptedException` without restoring the flag is one of the most common concurrency bugs: code above you on the stack (e.g., a thread pool's worker loop deciding whether to shut down) loses the information that cancellation was requested. The rule: either propagate the exception, or restore the flag and stop doing work. `isInterrupted()` reads without clearing; the static `Thread.interrupted()` reads **and clears** — a footgun if you call it accidentally.

#### Q37. [Theory] What is the difference between `notify()` and `notifyAll()`, and why is `notify()` so dangerous?

Both wake threads waiting on a monitor's wait-set, but `notify()` wakes **exactly one arbitrary** waiting thread while `notifyAll()` wakes **all** of them (they then re-contend for the lock). The danger with `notify()` is that the JVM chooses *which* waiter to wake with no regard to *why* it was waiting. If multiple threads wait on the same monitor for **different conditions**, `notify()` may wake a thread whose condition is still false — it rechecks its `while` guard, goes back to waiting, and the thread that *could* have proceeded is never woken. This is the classic **lost wakeup**, and it can wedge an otherwise-correct program.

```
Monitor wait-set: [Producer waiting "not full"] [Consumer waiting "not empty"]
A consumer does put() and calls notify() → JVM happens to wake the *Producer*,
whose "not full" is irrelevant → Producer rechecks, re-waits. Consumer that needed
"not empty" was never woken → stall.
```

`notify()` is only safe when **all** waiters wait for the identical condition and are interchangeable (a uniform worker pool), and the notification is "one slot freed → wake one waiter." Otherwise use `notifyAll()`. The cost of `notifyAll()` is a thundering-herd wake-and-recontend, which is why `ReentrantLock` with **multiple `Condition` objects** is the better design — `notFull.signal()` and `notEmpty.signal()` wake only the relevant wait-set, giving you `notify()`-level efficiency with `notifyAll()`-level safety. Note `Condition.signal()` maps to `notify()` semantics and `signalAll()` to `notifyAll()`.

#### Q38. [Theory] Why must `wait()`, `notify()`, and `notifyAll()` be called while holding the object's monitor?

Calling `wait()` without owning the monitor throws `IllegalMonitorStateException`. The requirement exists to close a fundamental race called the **lost-wakeup window**. Conceptually, a waiter does "check condition; if false, wait," and a notifier does "make condition true; notify." If these weren't serialized by the same lock, the notifier could slip its `notify()` into the gap *between* the waiter's condition-check and its actual `wait()` call — the notification fires while nobody is waiting yet, is lost, and the waiter then blocks forever.

The monitor solves this atomically: `wait()` is specified to **atomically release the lock and enqueue the caller into the wait-set** as a single step. Because both the checker and the notifier hold the same lock, the notifier cannot run between the check and the enqueue — it must wait for the checker to release the lock, which only happens *inside* `wait()` after the thread is already parked.

```java
synchronized (lock) {
    while (!conditionHolds()) {  // check
        lock.wait();             // atomically: release lock + enqueue, no gap
    }
    // proceed; lock re-acquired before wait() returns
}
```

This is also why the guard is a `while`, not an `if`: when `wait()` returns the thread has merely re-acquired the lock — it must re-verify the condition because (a) spurious wakeups are permitted by the spec, and (b) another thread may have consumed the condition between the `notify()` and this thread re-acquiring the lock.

### 🟡 Intermediate — extended

#### Q39. [Theory] Explain the JMM's "as-if-serial" semantics and how reordering can still break multithreaded code while preserving single-threaded correctness.

The compiler, JIT, and CPU are all permitted to reorder instructions as long as the result is indistinguishable from sequential execution **within a single thread** — the *as-if-serial* (or *intra-thread*) semantics. Operations with a data dependency cannot be reordered (`a = 1; b = a;` must keep order), but independent operations can be freely shuffled, and writes can sit in store buffers before becoming globally visible. This is why a *single-threaded* program never observes reordering: every dependency it can see is respected.

The trap is that as-if-serial says nothing about what *other* threads observe. Two independent writes in thread 1 can become visible to thread 2 in the opposite order:

```java
// Thread 1            // Thread 2
data = 42;             if (ready)            // may see ready==true
ready = true;              use(data);        //   but data==0 (stale)!
```

Within thread 1, `data=42` and `ready=true` are independent, so the JIT/CPU may publish `ready` first. Thread 2 then sees `ready==true` but still reads the old `data`. Single-threaded correctness is intact; the multithreaded program is broken. The fix is to establish a **happens-before** edge — make `ready` `volatile`, which forbids the reordering (the write of `data` cannot move after the volatile write of `ready`) and guarantees that a thread reading `ready==true` sees `data==42`. The lesson interviewers want: reasoning about "the code as written" is wrong; you reason about the *partial order* the JMM permits, and you add synchronization edges to constrain it.

#### Q40. [Theory] What is the relationship between `volatile` and the four memory barriers (LoadLoad, StoreStore, LoadStore, StoreLoad)?

Underneath the happens-before abstraction, the JMM is implemented by inserting **memory barriers** (fences) that constrain reordering. Doug Lea's JMM cookbook describes four:

| Barrier | Prevents reordering of |
|---|---|
| LoadLoad | a load before it with a load after it |
| StoreStore | a store before it with a store after it |
| LoadStore | a load before it with a store after it |
| StoreLoad | a store before it with a load after it (the most expensive) |

A `volatile` **write** is fenced as `[StoreStore] write [StoreLoad]`: the StoreStore ensures all prior normal writes are flushed before the volatile store (so a reader who sees the volatile value sees those writes too), and the StoreLoad prevents the volatile store from being reordered with subsequent loads. A `volatile` **read** is fenced as `read [LoadLoad][LoadStore]`: nothing after the read can be hoisted above it.

```
volatile write:  ...prior stores...  [StoreStore]  V = x  [StoreLoad]  ...later loads...
volatile read:   ...   V_value = V   [LoadLoad][LoadStore]   ...later loads/stores...
```

The practical insight is **why volatile is asymmetric in cost**: the `StoreLoad` after a volatile write is the only barrier that requires draining the store buffer and is by far the costliest (on x86 it compiles to a `lock`-prefixed instruction or `mfence`). This is exactly why acquire/release modes (`VarHandle.setRelease`/`getAcquire`) are cheaper than full `volatile` — they omit the bidirectional `StoreLoad`, needing only one-directional fences, and on strongly-ordered x86 acquire/release loads and stores are essentially free (plain `mov`), with the cost only appearing on weakly-ordered ARM.

#### Q41. [Theory] Describe the `ThreadPoolExecutor` lifecycle states and how `execute()` decides between core threads, the queue, and max threads.

`ThreadPoolExecutor` packs its run-state and worker count into a single `AtomicInteger` (`ctl`) — the top 3 bits are the state, the low 29 bits the live worker count — so both can be read/CAS'd atomically. The states form a one-way ratchet:

```
RUNNING ──shutdown()──► SHUTDOWN ──queue empty & pool empty──► TIDYING ──terminated()──► TERMINATED
   │                       ▲
   └──shutdownNow()────► STOP (interrupts workers, drains queue) ┘
```

- **RUNNING** — accepts new tasks and processes the queue.
- **SHUTDOWN** — rejects new tasks but finishes the queue (graceful).
- **STOP** — rejects new tasks, interrupts running workers, does not drain the queue.
- **TIDYING** → **TERMINATED** — all workers gone; `terminated()` hook runs.

The `execute()` decision logic is a precise three-step ladder that interviewers love to ask the *order* of:

```
1. If workerCount < corePoolSize       → start a NEW core worker for this task.
2. Else try to OFFER the task to the workQueue (if RUNNING & queue accepts).
3. Else (queue full) try to start a NEW worker up to maximumPoolSize.
4. Else                                 → invoke the RejectedExecutionHandler.
```

The counter-intuitive consequence is step 2 *before* step 3: the pool prefers to **queue** rather than grow beyond core size. With an **unbounded** queue (the `Executors.newFixedThreadPool` default) step 2 *never fails*, so `maximumPoolSize` is dead code and the pool never exceeds core size — which is exactly why an overloaded fixed pool grows its queue to OOM instead of shedding load. To get "grow under load, then reject," you need a **bounded** queue so step 2 can fail and unlock step 3, plus a deliberate `RejectedExecutionHandler` (e.g., `CallerRunsPolicy` for backpressure).

#### Q42. [Theory] Compare `ConcurrentLinkedQueue`, `LinkedBlockingQueue`, `ArrayBlockingQueue`, and `SynchronousQueue`. When does each win?

These are the workhorses behind executors and producer/consumer pipelines, and they make very different trade-offs:

| Queue | Bounded? | Blocking? | Locking | Internal structure |
|---|---|---|---|---|
| `ConcurrentLinkedQueue` | No | No (returns `null`) | Lock-free (CAS) | Michael-Scott linked nodes |
| `LinkedBlockingQueue` | Optional | Yes | **Two locks** (put/take) | Linked nodes |
| `ArrayBlockingQueue` | Yes (fixed) | Yes | **One lock** | Circular array |
| `SynchronousQueue` | 0 capacity | Yes | Lock-free handoff | No storage; direct handoff |

`ConcurrentLinkedQueue` is the **Michael-Scott non-blocking queue** — fully lock-free, ideal when you want high-throughput, non-blocking enqueue/dequeue and can poll (it never blocks; an empty `poll()` returns `null`). `LinkedBlockingQueue` uses **separate `putLock` and `takeLock`**, so a producer and consumer can proceed truly in parallel — higher throughput than `ArrayBlockingQueue` under mixed load, at the cost of node allocation per element and a slightly weaker `size()`. `ArrayBlockingQueue` uses a **single lock** for both ends, so put and take contend, but it pre-allocates a fixed array (no per-element garbage, predictable memory, better cache locality) — preferred for bounded, latency-sensitive pipelines.

`SynchronousQueue` has **zero capacity**: every `put` blocks until a `take` rendezvouses with it (and vice versa) — it is a direct hand-off, not a buffer. That is exactly why `Executors.newCachedThreadPool` uses it: an incoming task is handed straight to a waiting idle thread, and if none is available the pool spawns a new one — there is nowhere to queue, which produces the "unbounded thread growth" behavior. The selection rule: lock-free non-blocking → `ConcurrentLinkedQueue`; bounded predictable memory → `ArrayBlockingQueue`; high producer/consumer parallelism → `LinkedBlockingQueue`; pure hand-off with backpressure → `SynchronousQueue`.

#### Q43. [Theory] What does "weakly consistent" mean for concurrent-collection iterators, and how does it differ from fail-fast?

Legacy collections (`ArrayList`, `HashMap`) return **fail-fast** iterators: they track a `modCount`, and if the collection is structurally modified during iteration they throw `ConcurrentModificationException` on the next `next()`/`hasNext()`. This is a best-effort *bug detector*, not a guarantee — even single-threaded `list.remove()` inside a for-each trips it, and it can both miss real concurrent modifications and fire spuriously.

Concurrent collections (`ConcurrentHashMap`, `ConcurrentLinkedQueue`, `CopyOnWriteArrayList`) instead provide **weakly consistent** iterators, which make three promises: (1) they **never throw `ConcurrentModificationException`**; (2) they traverse elements as they existed at or after iterator creation; (3) they reflect *some* but not necessarily all modifications made after creation. In other words, the iterator is allowed to see, or not see, concurrent updates, but it will never corrupt or throw.

```java
ConcurrentHashMap<String,Integer> m = new ConcurrentHashMap<>();
// Thread A iterates while Thread B puts/removes — A sees a valid but
// not-necessarily-current snapshot; NO exception is ever thrown.
for (var e : m.entrySet()) { /* may or may not observe B's concurrent writes */ }
```

The design rationale: a strongly consistent (point-in-time snapshot) iterator over a large concurrent map would require either locking the whole map or copying it — both kill the scalability the structure exists to provide. `CopyOnWriteArrayList` is the exception that takes the snapshot approach: its iterator operates over an immutable array snapshot taken at creation, so it is fully consistent *for that snapshot* and ignores all later mutations — which is why COW is only appropriate for read-mostly, small collections (every write copies the whole backing array, O(n)).

#### Q44. [Practical] When is `CopyOnWriteArrayList` the right choice, and what is the precise cost model?

`CopyOnWriteArrayList` (and `CopyOnWriteArraySet`) achieves thread safety by making **every mutating operation allocate a brand-new copy of the entire backing array** under a lock, then atomically swap the `volatile` array reference. Readers never lock and never block — they read the current immutable array via a single volatile read — which makes reads completely contention-free and consistent.

```
write: lock → copy whole array (O(n)) → mutate copy → volatile-publish new array → unlock
read:  volatile-read array reference → index into immutable array  (O(1), lock-free)
```

The cost model is stark: reads are O(1) and lock-free; **writes are O(n) in time and transiently O(n) in extra memory** (two arrays exist during the copy). It is therefore correct *only* when the collection is **read-dominated and small/rarely-mutated**. The canonical fit is an **event-listener/observer list**: it changes a handful of times at startup, is iterated constantly on the hot path, and iteration must never throw or see a torn list even while a listener (de)registers.

The classic anti-pattern is using it as a general-purpose concurrent list under a write-heavy workload — appending N items one-by-one is O(N²) total copying and floods the allocator/GC. For write-heavy concurrent access, prefer a `ConcurrentLinkedQueue`/`ConcurrentLinkedDeque`, or guard a plain `ArrayList` with a `ReentrantLock`, or use a concurrent map keyed appropriately. The mental model: COW trades write cost for zero-cost, snapshot-consistent reads — only worthwhile when reads vastly outnumber writes.

#### Q45. [Theory] What guarantees do `final` fields provide under the JMM, and how does that enable safe publication without synchronization?

The JMM gives `final` fields a special **freeze** semantics (JLS §17.5): when a constructor finishes, all `final` fields written in that constructor are *frozen*, and any thread that obtains a reference to the object — through a *properly published* reference — is guaranteed to see the correctly initialized values of those final fields **without any synchronization**. This is what makes immutable objects like `String` and the `java.time` types safe to share freely across threads with no locks or `volatile`.

```java
final class Point {
    final int x, y;                 // frozen at constructor end
    Point(int x, int y) { this.x = x; this.y = y; }
}
// Any thread that sees a non-null Point reference is guaranteed to see x,y set —
// even if the reference itself was published via a data race.
```

The two critical caveats interviewers probe: (1) the guarantee covers only **final** fields — non-final fields of the same object can still be seen stale, so a "mostly immutable" object with one mutable field loses the protection for that field; and (2) the object must not **leak `this`** from its constructor (e.g., registering itself with a listener or starting a thread that captures `this` before construction completes), because that publishes a reference *before* the freeze, voiding the guarantee. There is also a transitive subtlety: if a final field points to a mutable object (e.g., `final int[] arr`), the *reference* and the array contents written in the constructor are safely published, but later mutations to `arr[i]` are *not* covered. This is the deep reason "make it immutable" is the strongest concurrency advice: it converts a synchronization problem into a publication problem the JMM solves for free.

#### Q59. [Theory] What is `InheritableThreadLocal`, how does propagation actually work, and why does it interact badly with thread pools?

`ThreadLocal` values are *not* inherited by threads you spawn — a child thread starts with empty thread-locals. `InheritableThreadLocal<T>` changes that: at the moment a child thread is **created** (in `Thread`'s constructor), the parent's `inheritableThreadLocals` map is copied into the child, so the child starts with the parent's values. The propagation happens **once, at thread-creation time**, by copying references (you can override `childValue(parentValue)` to deep-copy if needed).

```java
static final InheritableThreadLocal<String> TRACE = new InheritableThreadLocal<>();
TRACE.set("req-42");
new Thread(() -> System.out.println(TRACE.get())).start();  // prints "req-42"
```

The pitfall is precisely the "once at creation" semantics colliding with **thread pools**, where threads are created early and reused for many unrelated tasks. A pooled worker inherited whatever the *pool-creating* thread had at construction — typically nothing useful — and crucially it does **not** re-inherit from the thread that submits each task. So the trace/context you set on the submitting thread does *not* flow to the pooled worker, and worse, a stale value captured at pool-creation time lingers across tasks. This is the core reason context-propagation libraries (Spring's `TaskDecorator`, Micrometer's context-propagation, MDC adapters) exist — they explicitly capture the submitter's context at `submit()` time and re-install it on the worker, then clear it afterward, rather than relying on `InheritableThreadLocal`. In the Loom era, `ScopedValue` (Q54) supersedes this for structured concurrency because its scope-based, copy-free inheritance flows correctly to forked virtual threads without the pool-reuse staleness.

#### Q60. [Theory] Explain why `double-checked locking` was actually broken in Java before JDK 5, and what JSR-133 changed to make `volatile` fix it.

Double-checked locking (Q19) is famous for being *broken* in Java 1.4 and earlier — and the reason is a JMM history lesson interviewers use to test depth. Under the **old (pre-JSR-133) memory model**, `volatile` was weaker: it guaranteed that volatile reads/writes weren't reordered *with each other*, but it did **not** prevent reordering of volatile writes with *surrounding normal (non-volatile) reads/writes*. So even declaring the singleton field `volatile` did not stop the `instance = new Config()` publication from being reordered relative to the constructor's writes to the object's fields — a second thread could still see a non-null reference to a half-initialized object. DCL was genuinely unfixable with `volatile` in 1.4; the only safe idioms were full synchronization or the static-holder pattern.

```
Pre-JSR-133 volatile write:  could be reordered with the object's field initializations → DCL broken
Post-JSR-133 (JDK 5+):       volatile write has StoreStore before it → constructor writes
                             are flushed before the reference is published → DCL works
```

**JSR-133 (Java 5)** strengthened `volatile` exactly to fix this class of bug: it added the rule that a volatile write *cannot* be reordered with **any** prior read or write (the `StoreStore`+`StoreLoad` fencing of Q40), and a volatile read cannot be reordered with subsequent operations. It also gave `final` fields their freeze semantics (Q45). The net effect: from JDK 5 onward, a `volatile` field establishes happens-before with full-program-order respect on its side of the fence, so DCL with `volatile` became correct. The takeaway is twofold — (1) "DCL is broken" is a *version-specific* statement that stopped being true in 2004, and saying so without the JDK-5 caveat reveals stale knowledge; and (2) it illustrates that the JMM is not eternal — the *semantics* of `volatile` itself changed, which is why citing the model version matters.

### 🟠 Advanced — extended

#### Q46. [Theory] Explain the internals of `AbstractQueuedSynchronizer` (AQS) — what is the `state` field and the CLH queue?

AQS is the engine behind `ReentrantLock`, `Semaphore`, `CountDownLatch`, `ReentrantReadWriteLock`, and `CompletableFuture`'s waiters. It provides two things: a single `volatile int state` (its meaning defined by the subclass) and a **FIFO wait queue** of blocked threads. Subclasses implement `tryAcquire`/`tryRelease` (exclusive) or `tryAcquireShared`/`tryReleaseShared` (shared) by manipulating `state` with CAS, and AQS handles the hard part: enqueueing, parking, and unparking threads.

```
state semantics per subclass:
  ReentrantLock      → 0 = free, n = hold count (reentrancy)
  Semaphore          → number of available permits
  CountDownLatch     → remaining count; acquire blocks until state == 0
  ReentrantRWLock    → high 16 bits = shared (read) count, low 16 = exclusive (write)
```

The wait queue is a variant of a **CLH (Craig-Landin-Hagersten) lock queue** — a doubly linked list of nodes, one per blocked thread, each with a `waitStatus`. A thread that fails `tryAcquire` appends a node via CAS, then spins briefly and finally **parks** itself with `LockSupport.park()`. On release, the owner sets `state` and `LockSupport.unpark()`s the successor node. The elegance: the heavy concurrency logic (queueing, fairness, cancellation, condition queues) is written once in AQS; a new synchronizer just defines what `state` means and how to CAS it. This is why building a custom synchronizer (e.g., a one-shot gate or a counting latch with extra semantics) is usually 30 lines extending AQS rather than a from-scratch lock.

#### Q47. [Theory] How does an "unfair" `ReentrantLock` differ from a "fair" one at the AQS level, and why is unfair the default?

The difference is whether a newly arriving thread is allowed to **barge** ahead of threads already waiting in the AQS queue. An **unfair** lock (the default) lets an incoming `lock()` immediately attempt a CAS on `state` *before* checking the queue — if the lock just became free, the barging thread grabs it even though others have been waiting longer. A **fair** lock (`new ReentrantLock(true)`) first calls `hasQueuedPredecessors()` and refuses to acquire if anyone is ahead in line, enforcing strict FIFO.

```
Unfair: free lock + arriving thread → CAS-grab immediately (ignores queue)  → high throughput, possible starvation
Fair:   free lock + arriving thread → if queue non-empty, go to back of line → FIFO, lower throughput
```

Unfair is the default because of **throughput**, and the reason is mechanical. Lock hand-off involves unparking the queued successor, which takes time (the OS must reschedule it). If a barging thread can acquire-and-release the lock entirely within that wake-up latency window, the lock stays "hot" and total throughput is far higher — the just-unparked thread simply finds the lock free or re-queues. Fairness forces an expensive context switch on *every* hand-off and forbids this overlap, often cutting throughput by an order of magnitude. The trade-off: unfair locks can in theory starve a thread indefinitely under sustained contention, but in practice the statistical fairness is acceptable, and you only pay for strict fairness when starvation would violate a requirement (e.g., a fairness SLA). Note `synchronized` is always unfair and offers no fair option at all.

#### Q48. [Theory] What is lock elision, lock coarsening, and biased/thin/fat lock inflation in HotSpot?

HotSpot applies several JIT and runtime optimizations to `synchronized` so that uncontended locking is nearly free. **Lock elision** uses escape analysis: if the JIT proves a lock object never escapes the current thread (e.g., a `StringBuffer` created and used entirely inside one method), the synchronization is provably useless and the locks are removed outright. **Lock coarsening** merges adjacent synchronized regions on the same object — a loop that locks/unlocks every iteration may be rewritten to take the lock once around the whole loop, eliminating repeated acquire/release.

At the object-header level, every Java object has a **mark word** that encodes its lock state, and an intrinsic lock *inflates* through tiers as contention rises:

```
Object mark word lock states (classic HotSpot):
  Biased        → header records ONE owning thread; that thread re-locks with no CAS at all
  Thin/Light    → CAS a pointer to a stack-allocated lock record into the header (uncontended fast path)
  Fat/Heavy     → real OS monitor (ObjectMonitor) with a wait-set; used once threads actually contend
```

**Biased locking** assumed one thread repeatedly locks the same object and skipped even the CAS — historically a big win for single-threaded-pattern code. The critical version fact: **biased locking was deprecated and disabled by default in JDK 15 (JEP 374)** and effectively removed thereafter, because modern CAS is cheap, the bookkeeping (bias revocation on contention) hurt increasingly common multi-threaded workloads, and it complicated the VM. So in 2026 HotSpot you reason about thin → fat inflation plus elision/coarsening, not biased locking. The practical upshot: uncontended `synchronized` is extremely cheap (a single CAS or eliminated entirely), so "synchronized is slow" is outdated folklore — the cost only materializes under real contention when the lock inflates to an OS monitor.

#### Q49. [Theory] What is a JVM safepoint, and how does it interact with concurrency, GC, and "time-to-safepoint" pauses?

A **safepoint** is a point in execution where all of a thread's data structures (stack, registers, references) are in a known, consistent state so the JVM can safely inspect or modify them. Many global operations require *all* application threads to be simultaneously at a safepoint: stop-the-world GC phases, deoptimization, biased-lock revocation (historically), `Thread.getAllStackTraces`, and class redefinition. The JVM requests a safepoint by flipping a global flag (or arming a polling page); each thread checks for the safepoint request at **safepoint polls** the JIT inserts — typically at method returns and loop back-edges — and parks itself when it sees the request.

```
JVM: "global safepoint requested" ──► every thread runs to its next poll point and parks
   ◄── once ALL threads parked, the VM operation runs (e.g., GC) ──►
   ──► VM resumes threads
```

The concurrency-relevant subtlety is **time-to-safepoint (TTSP)**: the global operation cannot begin until the *slowest* thread reaches a poll. A thread executing a long **counted loop** that the JIT optimized by *removing* the back-edge poll (a real optimization for tight `int` loops) can delay the safepoint for milliseconds while every *other* thread sits frozen — a "GC pause" the GC logs blame on collection but is actually TTSP. This is why a single rogue thread in a long array-crunch can cause mysterious latency spikes across an entire service. Diagnosing it requires `-XX:+PrintSafepointStatistics` (older) / safepoint logging via `-Xlog:safepoint` and sometimes `-XX:+UseCountedLoopSafepoints`. The lesson: pauses attributed to GC are sometimes really coordination pauses, and understanding safepoints is what separates "the GC is slow" from "thread X delayed the safepoint."

#### Q50. [Theory] How does `CompletableFuture` decide which thread runs a continuation, and why can a non-`Async` callback run on a surprising thread?

The thread that executes a `CompletableFuture` stage depends on **timing** for non-`Async` methods and is **explicit** for `*Async` methods. For a non-async stage like `thenApply(fn)`, there is a race: if the upstream future is **already complete** when you attach `thenApply`, the callback runs **on the calling thread** (the thread attaching it); if the upstream is **not yet complete**, the callback runs **on whatever thread completes the upstream future** (e.g., the I/O thread that called `complete()`). So the same line of code can run on three different threads run to run — a notorious source of "why is my UI callback on a Netty I/O thread" and accidental blocking of completion threads.

```java
cf.thenApply(x -> heavy(x));          // runs on: caller IF cf done, ELSE the completing thread
cf.thenApplyAsync(x -> heavy(x));     // runs on: the common ForkJoinPool (predictable)
cf.thenApplyAsync(x -> heavy(x), ex); // runs on: your executor 'ex' (predictable + isolated)
```

The `*Async` variants remove the ambiguity by always dispatching the callback to an executor — the common `ForkJoinPool` by default, or one you supply. The expert guidance: (1) never do blocking or heavy work in a non-async callback, because you may unknowingly tie up the thread that completed the future (often a scarce I/O or scheduler thread); (2) always pass an **explicit executor** to `*Async` for blocking work, since the default common pool is sized to cores and shared JVM-wide; and (3) be aware that long non-async chains can pile their work onto the single thread that completed the root future. This timing-dependent dispatch is the most misunderstood part of the `CompletableFuture` model.

#### Q51. [Theory] `ThreadLocalRandom` and `Atomic*FieldUpdater` — what problems do these specialized utilities solve?

`ThreadLocalRandom` (Java 7) exists because the shared `java.util.Random` uses a single `AtomicLong` seed updated by CAS on **every** `nextInt()`. Under heavy multithreaded use, every thread contends on that one seed, the CAS loop spins, and the random generator becomes a scalability bottleneck (the irony: a "random" call serializing all threads). `ThreadLocalRandom.current()` gives each thread its own seed state stored in the `Thread` object itself, so there is zero contention and no shared CAS — it is the correct choice for any concurrent random-number use (load generators, randomized backoff, sampling).

```java
// BAD under contention: shared seed, CAS per call
static final Random R = new Random();
int x = R.nextInt(100);

// GOOD: per-thread seed, no contention
int y = ThreadLocalRandom.current().nextInt(100);
```

`AtomicIntegerFieldUpdater` / `AtomicLongFieldUpdater` / `AtomicReferenceFieldUpdater` solve a **memory-footprint** problem. If you have millions of objects each needing one atomically-updatable counter, giving each an `AtomicInteger` means millions of *extra wrapper objects* (each ~16 bytes of header + the int + a reference). A field updater lets you keep a plain `volatile int` field on the object and perform CAS on it via a single static, shared updater — saving the per-object wrapper allocation entirely:

```java
class Node {
    volatile int status;            // plain field, no wrapper object
    static final AtomicIntegerFieldUpdater<Node> S =
        AtomicIntegerFieldUpdater.newUpdater(Node.class, "status");
    void activate() { S.compareAndSet(this, 0, 1); }  // CAS on the field
}
```

This is exactly how the JDK itself implements many internal classes (e.g., AQS node status, `CompletableFuture`). The trade-off is reflection-based setup cost and the field must be `volatile` and accessible; in Java 9+ `VarHandle` is the more modern, faster equivalent and is generally preferred for new code.

#### Q52. [Practical] Diagnose why a thread pool's tasks silently disappear — no exception, no result. What are the usual causes?

Silently vanishing tasks in an `ExecutorService` almost always trace to **swallowed exceptions**, and the precise cause depends on how the task was submitted. When you `submit()` a task, the executor wraps it in a `FutureTask`; any exception thrown is **captured inside the `Future`** and only rethrown when you call `future.get()`. If your code submits-and-forgets without ever calling `get()`, the exception is invisible — the task "disappeared" but actually failed.

```java
// Exception is trapped in the Future and never surfaces unless you call get():
Future<?> f = pool.submit(() -> { throw new RuntimeException("boom"); });
// ... no f.get() anywhere → silent failure

// With execute(), an uncaught exception DOES propagate to the thread's
// UncaughtExceptionHandler — but the default handler may just print to stderr,
// which is easy to miss in a log flood.
pool.execute(() -> { throw new RuntimeException("boom"); });
```

The diagnostic checklist: (1) confirm whether tasks are submitted via `submit` (exception trapped) vs `execute` (goes to the uncaught handler); (2) install a `Thread.UncaughtExceptionHandler` via a custom `ThreadFactory` and/or override `ThreadPoolExecutor.afterExecute(Runnable, Throwable)` to log every failure; (3) check the `RejectedExecutionHandler` — the default `AbortPolicy` throws on the *submitting* thread when the queue is full, but a custom `DiscardPolicy`/`DiscardOldestPolicy` will silently drop tasks with no trace; (4) verify the pool wasn't `shutdown()` mid-stream (post-shutdown submits are rejected). The robust fix is to always either consume `Future.get()`, or override `afterExecute` to surface both the thrown-exception path and the wrapped-`Future` path:

```java
protected void afterExecute(Runnable r, Throwable t) {
    super.afterExecute(r, t);
    if (t == null && r instanceof Future<?> f && f.isDone()) {
        try { f.get(); } catch (ExecutionException e) { t = e.getCause(); }
        catch (CancellationException ce) { t = ce; }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
    if (t != null) log.error("task failed", t);
}
```

#### Q61. [Theory] How does `ReentrantReadWriteLock` encode read and write counts in a single AQS `state`, and how can a write lock starve or be downgraded?

`ReentrantReadWriteLock` is a single AQS whose `state` int is **split**: the high 16 bits hold the **shared (read) hold count** and the low 16 bits hold the **exclusive (write) hold count**. This packing lets one CAS atomically reason about both. Acquiring the write lock requires `state == 0` for the exclusive portion *and* no readers (or only the reentrant writer's own reads). Acquiring a read lock increments the shared count and is allowed concurrently with other readers but blocked while a writer holds it.

```
ReentrantReadWriteLock state (32 bits):
  [ 16 bits: shared/read count | 16 bits: exclusive/write count ]
  → caps each at 65535; SHARED_SHIFT = 16
```

Two expert behaviors matter. First, **writer starvation**: a *non-fair* RRWL lets a continuous stream of readers keep the read count above zero, so a waiting writer never sees `state`'s read portion hit zero and waits indefinitely. The fair variant (`new ReentrantReadWriteLock(true)`) and the reader's policy of *not* barging when a writer is queued mitigate this, trading throughput for writer progress. Second, **lock downgrading is supported but upgrading is not**: a thread holding the write lock may acquire the read lock and then release the write lock — *downgrading* to a read lock without ever fully releasing, so no other writer can interleave. But a reader cannot *upgrade* to a writer (two readers both trying would deadlock waiting for each other to release), which is why the API forbids it and you must release the read lock first.

```java
rwl.writeLock().lock();
try {
    mutate();
    rwl.readLock().lock();     // acquire read BEFORE releasing write → downgrade
} finally { rwl.writeLock().unlock(); }
try { read(); } finally { rwl.readLock().unlock(); }  // now only holding read
```

The practical guidance: RRWL only pays off when reads are *long* and vastly outnumber *short* writes; for short critical sections the bookkeeping overhead and writer-starvation risk often make plain `synchronized` or a `StampedLock` optimistic read the better choice.

#### Q62. [Theory] What exactly is `Phaser`'s phase/party model, and how does it generalize both `CyclicBarrier` and `CountDownLatch`?

`Phaser` is the most general of the JDK's coordination primitives, and the interview goal is to articulate *why* it subsumes the others. It tracks two things: a set of **registered parties** and a monotonically increasing **phase number**. Each cycle, every registered party calls `arrive()` (or `arriveAndAwaitAdvance()`); when the number of arrivals equals the number of registered parties, the phaser **advances** — it runs the optional `onAdvance(phase, parties)` hook, increments the phase number, and releases everyone waiting. Unlike `CyclicBarrier`, the number of parties is **dynamic**: threads can `register()`/`bulkRegister(n)` to join and `arriveAndDeregister()` to leave *between* phases.

```
Phaser:  parties=3 ──arrive×3──► phase 0 advances → onAdvance() → phase 1
         a party can register()  (parties→4) or arriveAndDeregister() (parties→2)
         between phases — neither CyclicBarrier nor CountDownLatch allows that
```

It generalizes the other two cleanly: a `Phaser` with a fixed party set used over one phase behaves like a **`CountDownLatch`** (terminate via `onAdvance` returning true); reused across phases with a fixed party set it behaves like a **`CyclicBarrier`** (with `onAdvance` as the barrier action); and with dynamic registration it does what neither can — coordinate a *varying* number of participants across multiple stages (e.g., a fork/join-style pipeline where workers spin up and retire each phase). It also supports **tiered phasers** (a tree of phasers) to reduce contention when thousands of parties would otherwise hammer one atomic counter, and non-blocking `arrive()` for fire-and-forget phase progression. The cost is conceptual complexity — for a fixed N-thread one-shot or fixed-round rendezvous, the simpler latch/barrier is clearer; reach for `Phaser` specifically when *party count changes over time* or you need tiered scalability.

### 🔴 Expert — extended

#### Q53. [Theory] Contrast Sequential Consistency, the data-race-free (DRF) guarantee, and why the JMM gives "out-of-thin-air safety" instead of full SC.

**Sequential Consistency (SC)** is the strongest intuitive model: all operations appear in a single global total order consistent with each thread's program order. SC is wonderful to reason about but catastrophic for performance — it would forbid almost all compiler and CPU optimizations (store buffers, reordering, register caching). No mainstream hardware or language provides full SC for ordinary memory accesses.

The JMM instead provides the **DRF (data-race-free) guarantee**: *if a program is correctly synchronized — every conflicting access pair is ordered by happens-before — then it behaves as if it were sequentially consistent.* This is the bargain at the heart of the JMM (and C++11): you get the easy SC mental model **for free**, but *only* if you eliminate data races by establishing happens-before edges with `volatile`, locks, etc. Race-free code: reason as if SC. Racy code: all bets formally off.

```
correctly synchronized (race-free)  ⇒  observable behavior == some SC execution
contains a data race                ⇒  behavior is whatever the JMM's weak rules permit
```

But the JMM cannot simply say "racy programs have undefined behavior" the way C++ does, because Java must remain **memory-safe and secure even for buggy/malicious code** — a data race must never fabricate a pointer or violate type safety. So the JMM adds the famous **out-of-thin-air (OOTA) safety**: even a racy read may only return a value that some write *actually wrote* (or the default), never an invented value. This is why a data race in Java corrupts your *logic* but can't break the *sandbox*. The expert nuance: the formal causality rules that pin down OOTA are notoriously subtle (JSR-133 is known to be imperfect here, and the "OOTA problem" is still researched), but the practical takeaway is firm — write race-free code and you live in the simple SC world; rely on the weak rules and you're in expert-only territory.

#### Q54. [Theory] Explain `ScopedValue` (JDK 21+ preview) versus `ThreadLocal`, especially in the context of millions of virtual threads.

`ThreadLocal` has three problems that virtual threads make acute. First, **mutability** — anyone with the `ThreadLocal` can `set()` it anytime, so the value's lifetime is unbounded and hard to reason about. Second, **inheritance cost** — `InheritableThreadLocal` *copies* the parent's map to each child thread, which with millions of structured-concurrency child virtual threads is a serious memory and time cost. Third, **leak risk** — pooled platform threads require disciplined `remove()` (Q17), and with potentially millions of virtual threads, any per-thread heavy caching multiplies catastrophically.

`ScopedValue` (JEP 446/464, preview) is the Loom-era replacement: an **immutable**, dynamically-scoped binding that is set only for the dynamic extent of a `run`/`call` and automatically torn down when that scope exits — no `set`, no `remove`, no leak.

```java
final static ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

ScopedValue.where(CURRENT_USER, user).run(() -> {
    // CURRENT_USER.get() is visible to everything called transitively here,
    // including child threads forked in a StructuredTaskScope — share by reference,
    // no per-thread copy. Binding is gone automatically when run() returns.
    handleRequest();
});
```

The key internal difference: a `ScopedValue` binding is **shared by reference and immutable**, so structured-concurrency children can *inherit it without copying* — the child just sees the same immutable binding through the scope, eliminating the `InheritableThreadLocal` per-child clone. Because it is immutable and scope-bounded, the JVM can also optimize lookups (it's effectively a stack-walk of bindings, often cached). The guidance for 2026: for request-scoped context (user, trace id, transaction) propagated down a call tree — *especially* across `StructuredTaskScope` forks — prefer `ScopedValue`; reserve `ThreadLocal` for genuinely per-thread mutable caches (and even then, beware with virtual threads).

#### Q55. [Practical] A virtual thread workload shows poor scalability under load. Walk through how you'd diagnose pinning and carrier-thread starvation.

Virtual threads scale only if they *unmount* on blocking. When they don't, the symptoms look paradoxical: throughput plateaus far below expectation, latency spikes, yet CPU is *not* saturated and the carrier pool (default ≈ `availableProcessors()` `ForkJoinPool` workers) is fully occupied. The two failure modes are **pinning** (a VT blocks while mounted and can't unmount, holding its carrier) and **carrier starvation** (all carriers busy with mounted VTs, so runnable VTs can't get scheduled).

Diagnostic steps, in order:

```bash
# 1. Detect pinning directly. Java 21–23: the JFR event jdk.VirtualThreadPinned
#    fires whenever a VT blocks while pinned. Enable and inspect:
java -XX:StartFlightRecording=filename=app.jfr,settings=profile ...
jfr print --events jdk.VirtualThreadPinned app.jfr   # shows the pinning stack frames

# (Pre-JDK 24) the legacy flag printed pinning stacks to stdout:
#   -Djdk.tracePinnedThreads=full
# Removed in JDK 24 (JEP 491) — use the JFR event instead.

# 2. Check how many carriers exist / are configured:
#   -Djdk.virtualThreadScheduler.parallelism=N  (default = #cores)
```

The classic root causes the JFR stacks reveal: (1) blocking I/O inside a `synchronized` block or method — pre-JDK 24 this pins; **JEP 491 in JDK 24 removed `synchronized` pinning**, so confirming JDK ≥ 24 often fixes it outright, otherwise replace the `synchronized` around I/O with a `ReentrantLock`; (2) blocking in a **native/JNI** frame, which still pins even in JDK 24+ (no fix but to avoid blocking there); (3) `Object.wait()` historically pinning. For *carrier starvation* without pinning, the cause is usually **CPU-bound work running on virtual threads** — VTs are for blocking I/O, not computation; long CPU tasks should run on a dedicated platform-thread pool so they don't monopolize the small carrier pool. The anti-pattern to rule out first: someone **pooled** virtual threads or sized a `newFixedThreadPool` of platform threads as carriers; the correct model is one VT per task via `Executors.newVirtualThreadPerTaskExecutor()`.

#### Q56. [Theory] Why is `Thread.sleep()` semantically different from `Object.wait()`, and why is `Thread.yield()` / `Thread.onSpinWait()` almost never the right tool?

`Thread.sleep(ms)` and `Object.wait(ms)` look similar (both pause the thread for a time) but differ in the single most important way for correctness: **`sleep` does NOT release any locks**, while `wait` **atomically releases the monitor** it was called on and re-acquires it on wakeup. A thread that calls `sleep()` inside a `synchronized` block keeps holding the lock the entire time — every other thread needing that lock is blocked for the full sleep duration, a common cause of mysterious stalls. `wait()` exists precisely so a thread can pause *and let others use the lock* to change the condition it's waiting for. Also, `sleep` is a static method acting on the current thread and is woken only by timeout or interrupt; `wait` is an instance method tied to a monitor and is woken by `notify`/`notifyAll`, timeout, or interrupt.

```java
synchronized (lock) {
    Thread.sleep(1000);   // STILL HOLDS lock — other threads blocked 1s
    lock.wait(1000);      // RELEASES lock for up to 1s, others can proceed
}
```

`Thread.yield()` is a *hint* to the scheduler that the current thread is willing to give up the CPU — but it's non-binding (the OS may ignore it entirely), platform-dependent, and provides **no happens-before or correctness guarantee**. Using `yield()` to "fix" a race or coordinate threads is broken: it only changes timing probabilistically, masking the bug rather than fixing it (the bug reappears under different scheduling/load). The legitimate use is essentially benchmarking or coarse busy-wait politeness, and even there it's discouraged.

`Thread.onSpinWait()` (Java 9) is the *one* defensible busy-wait primitive: it emits a CPU `PAUSE`/`YIELD` instruction telling the core "I'm spin-waiting," which reduces power, avoids memory-order mis-speculation penalties on exit, and frees pipeline resources for a hyperthread sibling. It's the right tool **only** inside a genuine, very short, lock-free spin loop on a hot path (where the awaited condition is expected to flip in nanoseconds) before falling back to parking — e.g., the kind of adaptive spin inside high-performance lock or queue implementations. For ordinary application code, the answer is almost always a proper blocking primitive (a lock, a `BlockingQueue`, a `Condition`), never `yield`/`onSpinWait`.

#### Q57. [Theory] Walk through the happens-before reasoning for thread interruption and for `Thread.join()`, and how `Future.get()` fits the same publication pattern.

These three are all about **safe publication of a result/signal across threads**, and the JMM specifies happens-before edges for each so you don't need extra synchronization. For **`join()`**: all actions in a thread `t` *happen-before* `t.join()` returns in another thread. So if `t` writes to (non-volatile, even) fields during its `run()` and the joining thread reads them only *after* `join()` completes, those writes are guaranteed visible — `join()` is itself a synchronization point. This is why the canonical "spawn workers, join all, then aggregate their results" pattern is correct without volatile on the result fields.

```java
int[] result = new int[1];
Thread t = new Thread(() -> result[0] = compute());  // plain write
t.start();          // start() HB everything in t
t.join();           // everything in t HB join() returning
use(result[0]);     // GUARANTEED to see compute()'s write — no volatile needed
```

For **interruption**: a call to `interrupt()` on a thread happens-before the interrupted thread *observes* the interruption (either via `InterruptedException` from a blocking method or by reading `isInterrupted()`/`Thread.interrupted()`). This means any writes the interrupting thread made before calling `interrupt()` are visible to the interrupted thread once it detects the interrupt — so you can publish a "reason for cancellation" via a plain field before interrupting and the cancelled thread will see it. For **`Future.get()`**: the actions of the task (in the worker thread) that produced the result happen-before `get()` returns in the consumer thread; `ExecutorService.submit()` happens-before the task executes. So the entire executor framework gives you publication safety for task inputs and outputs for free.

The unifying principle: `start`, `join`, `interrupt`-observation, and `Future.get` are all **happens-before edges**, just like lock release/acquire and volatile write/read. The expert framing is that "passing data between threads safely" reduces to "is there a happens-before edge between the write and the read?" — and the JDK's coordination primitives are deliberately designed to *be* those edges, so idiomatic use is automatically correct without you sprinkling `volatile` everywhere.

#### Q58. [Theory] Why does `LongAdder` outperform `AtomicLong` under contention, and what is the exact mechanism of its striped `Cell` array and `Striped64` base?

`AtomicLong.incrementAndGet()` is a CAS loop on a **single** memory location. Under high contention, N threads all attempt CAS on the same word; all but one fail and retry, so throughput collapses to roughly serialized — worse, the contended cache line ping-pongs between cores (the MESI invalidation storm of Q20). The fundamental problem is that *every* writer targets the *same* cache line.

`LongAdder` (built on the package-private `Striped64`) solves this by **spreading writes across multiple cells**. It keeps a `base` field plus a dynamically-grown `Cell[]` array; each `Cell` is a padded (`@Contended`) holder of a partial sum on its **own cache line**. A thread increments by hashing its thread-specific probe to a cell index and CAS-ing *that* cell — so different threads usually hit different cells on different cache lines, and their CASes don't contend at all. The total value is `base + Σ cells[i]`, computed lazily by `sum()`.

```
AtomicLong:   all threads ──CAS──► [ single long ]     (contended, serialized)

LongAdder:    thread A ─► [Cell0]  ┐
              thread B ─► [Cell1]  ├─ each on its own cache line, independent CAS
              thread C ─► [Cell2]  ┘   sum() = base + Cell0 + Cell1 + Cell2
```

The growth mechanism is adaptive: it starts with just `base`, and only when a CAS on `base` *fails* (signalling contention) does it allocate the `Cell[]` and grow it (up to a power-of-two near the core count) under a spin-lock guarded `cellsBusy` flag, rehashing a thread's probe on collision. This is why `LongAdder` is the textbook choice for **write-heavy, read-rare** counters (metrics, request counts): writes scale linearly with cores, at the cost of (a) O(#cores) memory for the cells, and (b) `sum()` being only *eventually consistent* — it reads cells one at a time without a global lock, so a concurrent update may or may not be included. When you need a single exact value frequently *and* atomically (e.g., a sequence generator where every reader must see a precise monotonic count), `AtomicLong` is still correct and `LongAdder` is not, because `sum()` is not a linearizable snapshot. `LongAccumulator` generalizes the same striping to any associative function (max, min, custom merge), not just addition.

#### Q63. [Theory] Define linearizability, sequential consistency, and serializability as correctness conditions — how do they differ and which applies to concurrent objects vs transactions?

These three are often conflated but answer different questions. **Linearizability** is the gold standard for a *single concurrent object*: every operation appears to take effect **atomically at some instant between its invocation and its response**, and that instant respects real-time ordering — if operation A completes before B begins (in wall-clock time), A's effect is ordered before B's. It is a *local* (composable) property: a system built from individually linearizable objects is itself linearizable. This is the property `ConcurrentHashMap`'s individual operations, `AtomicLong.incrementAndGet`, and a correct lock-free queue's `offer`/`poll` aim for.

**Sequential consistency** is weaker: operations appear in *some* total order consistent with each thread's program order, but that order need **not** respect real-time across threads — an operation can appear to take effect before it was even invoked relative to other threads, as long as per-thread order holds. SC is *not* composable, which is one reason the JMM uses it only as the "DRF reward" (Q53) rather than as an object-correctness criterion.

```
Real-time order A-before-B respected?   Per-thread order respected?   Composable?
Linearizability        YES                         YES                    YES
Sequential consistency  NO                         YES                    NO
Serializability         (about transactions, not single ops)
```

**Serializability** is a *database/transaction* condition: a concurrent execution of multi-operation transactions is equivalent to *some* serial execution of those transactions. It says nothing about real-time order and operates at transaction granularity, whereas linearizability is about single operations with real-time constraints. **Strict serializability** combines both (serializable *and* respecting real-time transaction order). The expert framing: when reasoning about a `java.util.concurrent` data structure you want **linearizability** (and you verify it with tools like Lincheck, Q32); when reasoning about a transactional store you want **serializability**. Confusing them — e.g., claiming a structure is "serializable" when you mean its operations are linearizable — signals a gap.

#### Q64. [Theory] What is the difference between wait-free, lock-free, and obstruction-free progress guarantees, and why does the distinction matter in practice?

These are the three **non-blocking progress guarantees**, ordered from strongest to weakest, and the distinction is about *which* threads are guaranteed to make progress and under what conditions:

- **Wait-free** — *every* thread completes its operation in a **bounded number of steps**, regardless of contention or the speed/failure of other threads. No thread can ever be starved. This is the strongest and hardest to achieve; examples include a single-writer `AtomicLong.getAndIncrement` (on hardware with a wait-free fetch-and-add) and carefully designed wait-free queues.
- **Lock-free** — the **system as a whole** makes progress (at least one thread completes in a bounded number of steps), but an *individual* thread may be starved forever, repeatedly losing its CAS and retrying. A Treiber stack (Q22) and most CAS-loop algorithms are lock-free, not wait-free.
- **Obstruction-free** — a thread makes progress only if it runs **in isolation** for long enough (no contention); concurrent threads may cause each other to repeatedly abort and retry indefinitely (livelock is possible). The weakest; STM and `StampedLock`'s optimistic read have this flavor.

```
Wait-free       ⊃  Lock-free  ⊃  Obstruction-free
(every thread)     (some thread)   (a thread alone)
strongest                              weakest
```

The practical relevance: stronger guarantees give better **worst-case latency** and immunity to thread stalls (a thread descheduled by the OS mid-operation cannot block others in any of these — that's the shared "non-blocking" benefit over locks), but they cost more in complexity and often per-operation overhead. Hard-real-time and tail-latency-critical systems (trading, Q30) value wait-freedom for *bounded* latency; most general-purpose concurrent collections settle for lock-free because it's far simpler and the starvation risk is statistically negligible. Crucially, **all three avoid the deadlock/priority-inversion/convoy pathologies of locks** because no thread ever holds a resource that blocks another — a descheduled thread is never holding a lock. Knowing where a given structure sits tells you its worst-case behavior under adversarial scheduling.

#### Q65. [Practical] You must guarantee a callback runs exactly once even under concurrent triggers and failures. How do you build a robust idempotent one-shot latch?

The naive `if (!done) { done = true; run(); }` has a race (two threads both read `done==false`) and a `synchronized` version serializes every check forever. The idiomatic lock-free solution is a **CAS-based one-shot guard** on an `AtomicBoolean`, so exactly one thread wins the transition and runs the action:

```java
class OneShot {
    private final AtomicBoolean fired = new AtomicBoolean(false);
    private final Runnable action;
    OneShot(Runnable action) { this.action = action; }

    void trigger() {
        if (fired.compareAndSet(false, true)) {   // exactly ONE thread wins
            action.run();                          // guaranteed run-once
        }
        // losers return immediately — no blocking, no double-run
    }
}
```

The subtleties an interviewer wants addressed. (1) **Failure semantics**: if `action.run()` throws *after* the CAS succeeded, `fired` is already `true`, so no retry happens — if you need "exactly once *successfully*", wrap in try/finally and reset `fired` to `false` on failure (but that reopens a race window, so usually you instead record success in a separate state and let a single owner retry). (2) **Visibility/ordering**: callers that need to *observe the result* of the action must synchronize-on something the action publishes; the `AtomicBoolean` only orders the *firing*, not arbitrary result fields — pair it with a `volatile` result or a `CompletableFuture` if consumers await the outcome. (3) **Waiting consumers**: if other threads must *block until* the one-shot completes (not just skip), `AtomicBoolean` is insufficient — use a `CountDownLatch(1)` (the winner runs then `countDown()`s; everyone else `await()`s) or complete a shared `CompletableFuture`. The decision tree: skip-if-already-done → `AtomicBoolean` CAS; block-until-done → `CountDownLatch`/`CompletableFuture`; the JDK's own `CompletableFuture.complete()` is itself a CAS-based one-shot and is often the cleanest off-the-shelf answer.

#### Q66. [Theory] Explain the convoy effect and priority inversion — two pathologies unique to lock-based (blocking) concurrency.

Both are failure modes that **lock-free algorithms structurally cannot suffer**, which is part of why they're attractive despite their complexity. The **convoy effect** occurs when a thread holding a hot lock is **preempted or stalls** (page fault, GC pause, descheduled by the OS) *while holding the lock*. Every other thread needing that lock now piles up behind it, blocked, even though they could otherwise run — they form a "convoy." When the lock holder finally resumes and releases, the released threads acquire and release in lockstep, often re-forming the convoy, and the system throughput collapses to the speed of the unluckiest lock holder. The hot lock effectively serializes the whole system around the worst-case stall, and short critical sections become disproportionately damaging because the *acquire/release scheduling overhead* dominates.

```
Convoy:  T1 holds L, gets descheduled ──► T2,T3,T4 all block on L (can't proceed)
         T1 resumes, releases ──► T2 acquires...releases...T3...  (serialized convoy)
```

**Priority inversion** is the related danger in priority-scheduled systems: a **low**-priority thread holds a lock that a **high**-priority thread needs, so the high-priority thread is blocked — and meanwhile a **medium**-priority thread (needing neither lock) preempts the low-priority holder, preventing it from ever releasing. The high-priority thread is now effectively blocked by an unrelated medium-priority thread — its priority is *inverted*. The famous real-world case is the 1997 Mars Pathfinder, which kept resetting due to exactly this on its VxWorks RTOS. The classic fix is **priority inheritance** (the lock holder temporarily inherits the priority of the highest-priority waiter so it can run and release) or **priority ceiling** protocols.

The expert point: both pathologies arise *because a thread can hold a resource while not running*. **Non-blocking (lock-free/wait-free) algorithms are immune** — a thread descheduled mid-operation holds nothing, so others proceed via their CAS retries; this is the deeper reason "lock-free" matters for latency-critical and real-time systems, beyond just avoiding deadlock. The mitigation in plain Java: keep critical sections tiny, avoid blocking/allocation/I/O under a lock (so the holder can't stall), and on the JVM avoid relying on thread priorities at all (they're advisory and OS-dependent), which sidesteps inversion.

#### Q67. [Practical] How would you detect and reason about memory-ordering bugs that "work on x86 but fail on ARM"? Walk through the hardware model difference.

This is the trap that bites teams who develop/test on x86 servers and deploy to ARM (Graviton, Apple silicon, mobile). The root cause is that **x86 is a strongly-ordered (TSO — Total Store Order) architecture** while **ARM/Power are weakly-ordered**. On x86-TSO, the only reordering the hardware permits is **StoreLoad** (a later load bypassing an earlier store via the store buffer) — loads are never reordered with loads, stores never with stores. ARM, by contrast, permits **LoadLoad, LoadStore, StoreStore, and StoreLoad** reordering freely. So a program with a missing happens-before edge may *accidentally appear correct on x86* because the dangerous reordering simply can't happen there, then fail intermittently on ARM where it can.

```
Reorderings the hardware allows:
  x86 (TSO):   only StoreLoad                 → racy code often "looks fine"
  ARM/Power:   StoreStore, LoadLoad, LoadStore, StoreLoad  → the bug manifests
```

Concretely: the `data=42; ready=true` publication bug of Q39, if `ready` is *not* volatile, may *never* misbehave on x86 (stores stay ordered, so a reader seeing `ready` sees `data`), but on ARM the `StoreStore` reordering lets `ready` become visible first and the reader sees stale `data`. The compiler/JIT reorderings are platform-independent, but the *hardware* layer adds ARM-only reorderings on top.

The detection strategy: (1) you **cannot** rely on testing on x86 — run stress tests on the actual target architecture, and ideally use **jcstress** (Q32), which enumerates JMM-*permitted* outcomes and runs on the target hardware, surfacing reorderings that occur once in 10^9 iterations; (2) audit for shared mutable state lacking a happens-before edge — every cross-thread field access should be reachable through a `volatile`, lock, or other synchronization, *regardless* of whether tests pass; (3) treat "passes on my x86 laptop" as **zero evidence** of correctness for a weakly-ordered target. The fix is never "add a sleep" or "it works in prod" — it's to establish the missing happens-before edge (`volatile`, `VarHandle.setRelease`/`getAcquire`, or a lock), which the JMM then guarantees the JIT *and* the hardware will honor by inserting the right fences (an ARM `dmb`/`stlr`/`ldar`) on that platform automatically. This is the entire value proposition of programming to the JMM rather than to a CPU: write to the *model* and correct fences are emitted per-architecture.

#### Q68. [Theory] What is the "balking", "guarded suspension", and "two-phase termination" concurrency design patterns, and when do you reach for each?

Beyond the low-level primitives, classic concurrency *design patterns* (from Doug Lea / POSA2) give names to recurring structures, and naming them signals design maturity in an interview.

**Guarded Suspension** — a thread that needs a precondition *waits* until it becomes true (the producer/consumer `while(!cond) wait()` of Q38). The operation **blocks** until it can proceed. Use when the caller *must* eventually get the result and waiting is acceptable (a `take()` on an empty queue).

**Balking** — the dual of guarded suspension: if the precondition isn't met, the operation **returns immediately doing nothing** (it "balks") rather than waiting. Use when retrying later is the caller's job and blocking would be wrong — e.g., a `start()` method that returns silently if already started, or a `save()` that no-ops if nothing changed:

```java
synchronized void save() {
    if (!changed) return;          // BALK — don't block, just bail out
    doSave(); changed = false;
}
synchronized void put(T x) throws InterruptedException {
    while (full) wait();           // GUARDED SUSPENSION — block until room
    enqueue(x);
}
```

**Two-Phase Termination** — graceful shutdown in two steps: phase one *requests* termination (set a `volatile` flag and/or `interrupt()` the worker), phase two lets the thread *observe* the request at a safe point, run cleanup, and exit — never an abrupt `stop()`. This is the cooperative-cancellation pattern (Q3, Q36) elevated to a named structure, and it's exactly what `ExecutorService.shutdown()` (drain then stop) + `awaitTermination()` implement.

```java
class Worker extends Thread {
    private volatile boolean shutdownRequested = false;
    void shutdown() { shutdownRequested = true; interrupt(); }   // phase 1: request
    public void run() {
        try {
            while (!shutdownRequested && !isInterrupted()) doUnit();
        } finally { cleanup(); }                                  // phase 2: graceful exit
    }
}
```

The selection logic: **block-and-wait** → Guarded Suspension; **skip-if-not-ready** → Balking (same decision as the one-shot latch of Q65, generalized); **orderly shutdown** → Two-Phase Termination. Recognizing that "should this block or balk?" is a deliberate design decision — not an accident of how you happened to write the `if`/`while` — is the hallmark these patterns are meant to instill.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q69. [Practical] How do you give threads meaningful names and a default uncaught-exception handler, and why does it matter in production?

Anonymous threads named `Thread-23` or `pool-2-thread-7` are a recurring operational tax: in a thread dump, a heap dump, a profiler flame graph, or an APM trace you cannot tell *which* subsystem a stuck thread belongs to. The fix is a custom `ThreadFactory` that assigns a descriptive prefix and installs an `UncaughtExceptionHandler`. This is the single highest-leverage hygiene step for any pool you create, and it costs almost nothing.

```java
AtomicInteger seq = new AtomicInteger();
ThreadFactory tf = r -> {
    Thread t = new Thread(r, "order-ingest-" + seq.incrementAndGet());
    t.setUncaughtExceptionHandler((thr, ex) ->
        log.error("uncaught in {}", thr.getName(), ex));
    t.setDaemon(true);                       // optional, per use case
    return t;
};
ExecutorService pool = new ThreadPoolExecutor(
    8, 8, 0L, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(1000), tf, new ThreadPoolExecutor.CallerRunsPolicy());
```

The reason the handler matters specifically: for tasks submitted via `execute()`, an uncaught `RuntimeException` propagates to the thread's `UncaughtExceptionHandler`; without one set, it goes to the *group's* default, which just prints to `System.err` — easy to lose in a log flood, and worse, the worker thread *dies* and the pool quietly replaces it, masking a recurring failure as a mysterious slow leak of thread churn. (Note: for `submit()`, the exception is trapped in the `Future` and never reaches the handler at all — see the `afterExecute` discussion in Q52.) Libraries like Guava's `ThreadFactoryBuilder` or `BasicThreadFactory` (Apache Commons) give you this in one fluent call; in 2026 most teams standardise on a shared factory so every pool is named and observable by default.

#### Q70. [Theory] What is the difference between concurrency and parallelism, and why does the distinction change your design choices?

**Concurrency** is a *structuring* property — composing a program as multiple independent logical tasks that can be in progress at overlapping times; it is about *dealing with* many things at once. **Parallelism** is an *execution* property — actually running multiple computations *simultaneously* on multiple cores; it is about *doing* many things at once. Rob Pike's framing is canonical: concurrency is about the *structure* of a program, parallelism is about the *execution*. A single-core machine can be highly concurrent (a web server interleaving thousands of requests) but never parallel; a parallel array-sum on 16 cores need not be "concurrent" in the structural sense at all.

The design consequence is which problem you are actually solving. If your bottleneck is **latency from waiting** (I/O, network, DB), you want **concurrency**: keep many tasks in flight so the CPU isn't idle while one blocks — and the right tools are non-blocking I/O, async pipelines, or *virtual threads*, where adding "threads" beyond core count genuinely helps because they're mostly parked. If your bottleneck is **throughput of computation** (image processing, a big reduction), you want **parallelism**: split the work across cores — and the right tools are `parallelStream`, `ForkJoinPool`, sized to `availableProcessors()`, where adding threads beyond core count *hurts* (context-switch overhead).

```
Concurrency (structure):   [task A]──wait──[A]    interleaved, even on 1 core
                           [task B]─[B]──wait──    → fixes "idle while waiting"
Parallelism (execution):   core0: [chunk1]        truly simultaneous, needs N cores
                           core1: [chunk2]        → fixes "compute is the bottleneck"
```

Conflating them produces the two classic mistakes: throwing 200 threads at a CPU-bound job (thrashing) or sizing an I/O pool to core count (idle cores while requests wait). The 2026 nuance is that virtual threads make *concurrency* nearly free and decoupled from parallelism — you express huge concurrency (a VT per request) while the runtime maps it onto a small *parallel* carrier pool, cleanly separating the two concerns the language used to entangle.

#### Q71. [Practical] How do you correctly shut down an `ExecutorService`, and what is the standard graceful-then-forceful pattern?

Failing to shut down an `ExecutorService` is a real leak: its worker threads are (by default) non-daemon, so the JVM will *not* exit while they're alive — a common cause of a process that "won't stop" or a test suite that hangs. The JDK's documented idiom is a two-phase shutdown: `shutdown()` to stop accepting new tasks and let the in-flight queue drain, then `awaitTermination()` to bound the wait, then `shutdownNow()` to interrupt stragglers.

```java
void shutdownGracefully(ExecutorService pool) {
    pool.shutdown();                                  // phase 1: no new tasks, drain queue
    try {
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();                       // phase 2: interrupt running tasks
            if (!pool.awaitTermination(10, TimeUnit.SECONDS))
                log.error("pool did not terminate");
        }
    } catch (InterruptedException ie) {
        pool.shutdownNow();                           // we got interrupted: force it
        Thread.currentThread().interrupt();           // restore the flag
    }
}
```

The semantics interviewers probe: `shutdown()` is *graceful* — already-submitted tasks (running and queued) still complete; it just rejects new submissions. `shutdownNow()` is *forceful* — it `interrupt()`s the worker threads and returns the list of tasks that were never started (still in the queue), but it can only *interrupt*, not kill: a task that ignores interruption (a tight CPU loop that never checks `isInterrupted()`, or a non-interruptible blocking call) will keep running regardless. That's why robust tasks must be written to respond to interruption (Q36). On Java 19+, `ExecutorService` implements `AutoCloseable`, so `try (var pool = Executors.newVirtualThreadPerTaskExecutor()) { ... }` does the graceful `shutdown()` + `awaitTermination()` for you on block exit — the modern preferred form. In Spring, annotate the bean so the container drives this on context close (`@Bean(destroyMethod = "shutdown")` or `ThreadPoolTaskExecutor` with `setWaitForTasksToCompleteOnShutdown(true)`).

#### Q72. [Practical] A scheduled task using `ScheduledThreadPoolExecutor` stops firing after an exception. Why, and how do you make it resilient?

This is one of the most common production surprises with `scheduleAtFixedRate`/`scheduleWithFixedDelay`: if the scheduled task throws an **uncaught exception**, the executor **silently suppresses all future executions** of that task. The `ScheduledFutureTask` catches the throwable, stores it in its `Future`, and — because a periodic task that failed cannot meaningfully be rescheduled — cancels itself. No further runs, no log, no alert: a heartbeat or cache-refresh job just stops, and you find out hours later when downstream data is stale.

```java
// FRAGILE: one throw kills the schedule forever, silently.
ses.scheduleAtFixedRate(() -> refreshCache(), 0, 1, TimeUnit.MINUTES);

// RESILIENT: never let the exception escape the scheduled task body.
ses.scheduleAtFixedRate(() -> {
    try {
        refreshCache();
    } catch (Throwable t) {                      // catch Throwable, not just Exception
        log.error("cache refresh failed; will retry next tick", t);
        // swallow so the schedule survives; surface via metrics/alerts
    }
}, 0, 1, TimeUnit.MINUTES);
```

The fix is to **wrap the task body in a try/catch that never propagates** (catch `Throwable` so even an `Error`-ish issue or a `NullPointerException` from a transient dependency doesn't kill the schedule), and route the failure to logging + metrics instead. The second subtlety is `scheduleAtFixedRate` vs `scheduleWithFixedDelay`: *fixed-rate* tries to maintain a constant period and, if a run overruns the period, will fire the next one immediately (and they can never overlap — the executor serialises them on its threads), which can cause a backlog "catch-up" storm; *fixed-delay* always waits the full delay *after* the previous run finishes, which is usually what you want for jobs whose duration varies. For anything mission-critical, teams in 2026 typically prefer a real scheduler (Quartz, Spring `@Scheduled` with an error handler, or a distributed scheduler) precisely because raw `ScheduledThreadPoolExecutor` has this silent-death footgun and no built-in misfire handling.

### 🟡 Intermediate — extended

#### Q73. [Practical] How do you read a thread dump to find a thread that is hot (burning CPU) versus blocked, and correlate it with `top`?

The workhorse technique combines OS-level CPU attribution with a JVM thread dump, matching them by **thread ID in hex**. On Linux, `top -H -p <pid>` (or `ps -L`) shows per-*thread* CPU; note the decimal thread IDs (`nid` in the dump is the OS thread id). Convert the high-CPU tid to hex and grep the `jstack` output for `nid=0x<hex>` to find exactly which Java thread is burning the core.

```bash
# 1. find the hottest threads (per-thread CPU view)
top -H -p $(pgrep -f myapp.jar)        # note the TID of a thread pinned at ~100%
printf '%x\n' 31337                    # convert TID 31337 -> 0x7a69

# 2. capture a thread dump and locate that thread by nid
jstack <pid> > dump.txt
#   then search dump.txt for:  nid=0x7a69
```

In the dump, you read the **thread state line** plus the stack. A thread `"worker-3" ... RUNNABLE` sitting at the top of a tight loop or in a regex/JSON-parse frame is your CPU hog. A thread in `BLOCKED (on object monitor)` with `- waiting to lock <0x...>` paired with another thread showing `- locked <0x...>` reveals lock contention (and the JVM prints `Found one Java-level deadlock` if it's a true cycle). `WAITING`/`TIMED_WAITING (parking)` on a pool's `take()` is a normal idle worker — not a problem. The practical discipline: take **three dumps a few seconds apart** — a thread that stays RUNNABLE at the same frame across all three is genuinely stuck/hot, whereas one that moves is just busy doing real work. Async-profiler (`-e cpu`) or JFR give the same answer with less manual hex math and are preferred for continuous profiling, but the `top -H` + `jstack` + hex-match technique works on any box with zero extra tooling, which is why it's still the on-call standard.

#### Q74. [Theory] What thread-safety problem does the Singleton-via-enum pattern solve, and why is it considered the best singleton in Java?

The enum singleton leverages the JVM's class-initialization guarantees to get thread-safe lazy instantiation with zero explicit synchronization, and it additionally defends against two attacks that the field- and holder-based singletons cannot: **reflection** and **serialization**.

```java
public enum ConnectionPool {
    INSTANCE;
    private final DataSource ds = buildDataSource();
    public Connection get() { return ds.getConnection(); }
}
// usage: ConnectionPool.INSTANCE.get();
```

The thread-safety comes from the **JLS class-initialization rule** (the same one behind the holder idiom of Q19): the JVM initializes a class exactly once, under an internal lock, with full happens-before guarantees, so the single `INSTANCE` is created lazily on first reference and safely published to all threads — no `volatile`, no `synchronized`, no double-checked locking needed. What makes enum *superior* to the static-holder pattern is the two extra protections: (1) the JVM forbids reflective instantiation of enum constants (`Constructor.newInstance` on an enum throws `IllegalArgumentException`), closing the reflection backdoor that can otherwise create a second instance of a normal-class singleton; and (2) enum serialization is special-cased — only the *name* is serialized and `readObject`/`readResolve` machinery is handled by the JVM so deserialization always returns the existing constant, never a fresh copy. A normal `Serializable` singleton must manually implement `readResolve()` to avoid spawning duplicates on deserialization.

Josh Bloch (Effective Java) recommends it as the default singleton for exactly these reasons. The trade-offs to acknowledge: an enum can't extend a class (it implicitly extends `Enum`), and "lazy" here means lazy at *class-init* time, which for a heavy singleton referenced early is effectively eager — if you need true on-demand laziness with inheritance, the holder pattern is the alternative. But for a stateless or eagerly-needed singleton, enum is the simplest correct answer.

#### Q75. [Practical] When and how do you use `BlockingQueue`-based producer/consumer with backpressure, and how do you size and bound it?

The producer/consumer pattern decouples work *production* rate from *consumption* rate via a bounded `BlockingQueue`, and the bounding is the whole point: it provides **backpressure**. When consumers fall behind, the queue fills, and the producer's `put()` **blocks** — automatically throttling the producer to the consumers' throughput instead of letting an unbounded queue grow until OOM. This is the simplest correct flow-control mechanism in the JDK.

```java
BlockingQueue<Task> q = new ArrayBlockingQueue<>(1000);   // BOUNDED → backpressure

// producer(s)
q.put(task);                       // BLOCKS when full → throttles producer

// consumer(s)
while (running) {
    Task t = q.take();             // BLOCKS when empty → no busy-wait
    process(t);
}
```

The sizing and bounding decisions: the **capacity** trades latency for burst-absorption — a larger queue smooths bursts but adds queuing latency and memory, a smaller queue applies backpressure sooner and keeps latency low but may reject/block under bursts. Set it from your latency SLA and burst profile, not a round number. The **number of consumers** follows the pool-sizing logic (Q10): core-count for CPU-bound processing, higher for I/O-bound. Choose the queue implementation per Q42: `ArrayBlockingQueue` for fixed, predictable memory and tight latency; `LinkedBlockingQueue` for higher producer/consumer parallelism (separate put/take locks). The two pitfalls to call out: (1) **never use an unbounded queue** for this — it silently removes the backpressure that justifies the pattern (the same trap as `newFixedThreadPool`'s default); and (2) for *non-blocking* producers that must not stall (e.g., an event-loop thread), use `offer(task, timeout, unit)` returning `false` and apply an explicit overflow policy (shed load, sample, or route to a spill buffer) rather than blocking the hot thread. In 2026, for I/O-bound consumers many teams skip the explicit consumer pool and instead use a VT-per-task executor fed by the queue — but the *bounded queue for backpressure* remains essential regardless of thread model.

#### Q76. [Theory] Explain `CompletableFuture` exception handling: `exceptionally`, `handle`, `whenComplete`, and how exceptions wrap and propagate down a chain.

`CompletableFuture` propagates failures down the pipeline by **short-circuiting**: if a stage completes exceptionally, dependent `thenApply`/`thenCompose`/`thenAccept` stages are *skipped* and the exception passes straight through to the next stage that can handle it. The three recovery operators differ in what they receive and return:

| Operator | Sees | Can transform result? | Can recover from failure? | Runs on success? | Runs on failure? |
|---|---|---|---|---|---|
| `exceptionally(fn)` | only the exception | replaces only on failure | yes → new value | no | yes |
| `handle(bi)` | (result, throwable) | yes (either branch) | yes → new value | yes | yes |
| `whenComplete(bi)` | (result, throwable) | **no** (observe only) | no (re-throws original) | yes | yes |

```java
cf.thenApply(this::parse)                       // skipped if cf failed
  .handle((val, ex) -> ex != null               // sees both outcomes
        ? fallback(ex) : val)                    // can recover AND transform
  .whenComplete((val, ex) -> metrics.record(ex)) // side-effect only; does NOT alter outcome
  .exceptionally(ex -> defaultValue);            // last-resort recovery
```

The wrapping subtlety that trips people up: the throwable delivered to these handlers is usually a **`CompletionException`** (or `ExecutionException` from `get()`) **wrapping** your original cause — so you almost always need `ex.getCause()` to inspect the real exception, and naive `instanceof MyException` checks on the wrapper fail. Two more behavioural facts worth stating: `whenComplete` is *observe-only* — it cannot swallow or change the outcome, so if the stage failed, the failure (the original, *unwrapped* cause from the prior stage's perspective) continues downstream even after `whenComplete` runs; and a throwable from inside `whenComplete`'s own body, if the stage was otherwise successful, *will* fail the result. The guidance: use `whenComplete` for logging/metrics/cleanup (it doesn't disturb the value), `exceptionally` for a simple "on failure, substitute a value," and `handle` when you must branch on success-vs-failure and produce a unified result — and always unwrap `getCause()` before pattern-matching the exception type.

#### Q77. [Practical] How do you implement a per-key lock (lock striping) so that operations on different keys run concurrently but operations on the same key serialize?

A single global lock serialises *everything*; a lock-per-key with a naive `ConcurrentHashMap<K, Lock>` leaks locks forever (the map grows unboundedly as keys come and go). The two standard solutions are **lock striping** (a fixed array of locks, keys hashed to a stripe) and Guava's `Striped<Lock>`/`Striped.lazyWeakLock`.

```java
// Lock striping: fixed N locks; different keys *usually* get different locks,
// same key ALWAYS maps to the same lock. Bounded memory, no leak.
final Lock[] stripes = new Lock[64];
{ for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock(); }

Lock lockFor(Object key) {
    int h = key.hashCode();
    h ^= (h >>> 16);                              // spread bits (like HashMap)
    return stripes[h & (stripes.length - 1)];     // power-of-two mask
}

void updateAccount(String id, Op op) {
    Lock l = lockFor(id);
    l.lock();
    try { op.apply(id); } finally { l.unlock(); }
}
```

The core trade-off is **concurrency vs collision**: with N stripes, up to N distinct keys run truly in parallel, but two *different* keys that hash to the same stripe will falsely serialise (a hash collision on the stripe, not the key). You size N to balance memory against the false-contention rate — typically a small multiple of core count or expected concurrency. This is exactly the technique pre-Java-8 `ConcurrentHashMap` used internally (16 segments). Guava's `Striped<Lock>` packages this with a clean API, and `Striped.lazyWeakLock(n)` even lets locks be GC'd when unused, trading a bit of overhead for not pinning N lock objects. 

The alternative for *truly* per-key (no collisions) with automatic cleanup is `ConcurrentHashMap.compute(key, ...)` — the map locks the *bin* for the duration of the remapping function, giving you per-key atomic read-modify-write without managing any lock objects yourself, *provided* the operation fits inside the lambda and is short (you must not do blocking I/O or call back into the same map inside `compute`, or you risk stalling the bin / deadlock). The decision: bounded striping for general per-key serialisation across arbitrary code; `compute`/`merge` when the critical section is a short in-map update; a real distributed lock (Redis/ZooKeeper) when the keys must serialise across *multiple JVMs*.

#### Q78. [Theory] What is the "thread-confinement" strategy and its variants (ad-hoc, stack, ThreadLocal), and why is it often safer than locking?

Thread confinement makes data thread-safe by **never sharing it** — if an object is only ever touched by one thread, no synchronization is needed at all, because there are no concurrent accesses to order. It is the cheapest correctness strategy because it eliminates the problem rather than managing it, and *Java Concurrency in Practice* names three variants by how the confinement is enforced:

- **Ad-hoc confinement** — confinement maintained purely by *convention/discipline*; nothing in the code enforces that only one thread touches the object (e.g., "by agreement, only the GUI event thread mutates this model"). Fragile, because a single stray access from another thread silently breaks it — the weakest form.
- **Stack confinement** — the object is reachable only through **local variables** of one thread's call stack, so by construction no other thread can have a reference. A `StringBuilder` created, used, and discarded inside a method is stack-confined; primitives are always stack-confined. This is *enforced by the language* (locals aren't shared), making it robust as long as you don't *publish* the reference (return it, store it in a field, pass it to another thread).
- **`ThreadLocal` confinement** — each thread gets its own copy via `ThreadLocal<T>`, formally associating per-thread state with a long-lived object (Q17). Useful for per-request context or non-thread-safe-but-reusable helpers (`SimpleDateFormat`), with the pooled-thread leak caveat.

The reason confinement is often *safer* than locking is that locking is an ongoing, error-prone obligation — every access must remember to take the right lock in the right order, and a single missed access is a race; confinement removes that obligation entirely. The classic real-world embodiment is the **single-threaded event loop** (Netty's `EventLoop`, Node.js, Swing/JavaFX EDT, the Disruptor's single-writer of Q30): all mutable state is confined to one thread, so the business logic is written as if single-threaded — no locks, no visibility worries — while concurrency lives only at the boundary (the queue handing events into the loop). The trade-off is that confinement requires a *design commitment* (the object must genuinely never escape), and it doesn't help when data legitimately must be shared and mutated by many threads — there you fall back to immutability or locking. The mental hierarchy interviewers like: **don't share (confinement) > share immutable > share mutable with synchronization**, in increasing order of difficulty and bug surface.

#### Q79. [Practical] A field marked `volatile` still produces wrong results in your code. What kinds of bugs does `volatile` NOT fix, and how do you decide it is the wrong tool?

`volatile` fixes exactly two things — **visibility** (a write is seen promptly by other threads, no stale cache) and **ordering** (it establishes happens-before and forbids reordering across it). It fixes *nothing else*, and the bugs that survive `volatile` all share one root cause: a sequence of operations that must be **atomic as a unit** but isn't.

```java
volatile int count;
count++;                       // STILL BROKEN: read-modify-write is 3 ops, can interleave

volatile boolean inited;
if (!inited) { init(); inited = true; }   // STILL BROKEN: check-then-act race; two
                                          //   threads both see false and both init()

volatile List<String> list = new ArrayList<>();
list.add(x);                   // volatile guards the REFERENCE, not the list's internals;
                               //   concurrent add() still corrupts the ArrayList
```

The three categories `volatile` cannot fix: (1) **compound read-modify-write** (`count++`, `x = x + 1`) — needs `AtomicInteger`/`LongAdder` or a lock; (2) **check-then-act / invariants spanning multiple fields** — e.g., keeping `lower < upper` consistent when two volatile fields must change together; a reader can observe a torn combination even though each field is individually volatile, so you need a lock or an atomic snapshot object swapped via `AtomicReference`; (3) **operations on the referenced object's internal state** — `volatile` makes the *reference* visible/ordered, but does nothing for mutations *inside* the object it points to (a `volatile ArrayList` is not a thread-safe list).

The decision rule: `volatile` is the right tool only when there is a **single variable**, written by possibly one or more threads, where **each write is independent of the current value** (you're publishing a new value, not deriving it from the old) — the textbook cases being a `boolean running`/`shutdown` flag, a "latest config" reference swapped wholesale (`volatile Config`), or a one-way state transition published once. The moment the new value *depends on* the old one, or *multiple fields must change atomically together*, or you're mutating *inside* a shared object, `volatile` is insufficient and you escalate to atomics (single compound variable) or a lock / immutable-snapshot swap (multi-field invariant). The interview tell is being able to say "`volatile` gives me visibility and ordering but not *atomicity of a compound action*" and then map the specific symptom to which of those three guarantees is missing.

### 🟠 Advanced — extended

#### Q80. [Practical] How do you tune `ForkJoinPool` and the common pool in production, and what are the key system properties and pitfalls?

The common `ForkJoinPool` is a **single JVM-wide resource** used by `parallelStream`, `CompletableFuture.*Async` (without an explicit executor), and `Arrays.parallelSort` — so misusing it has process-global blast radius (Q23). Its parallelism defaults to `availableProcessors() - 1`, and you can override it, though that's usually the wrong lever:

```bash
# Resize the COMMON pool (affects every parallel stream / default-async in the JVM):
-Djava.util.concurrent.ForkJoinPool.common.parallelism=8

# In containers/cgroups, verify the JVM sees the right CPU count:
#   availableProcessors() honors cgroup CPU limits on modern JDKs (JDK 8u191+/10+),
#   but a misconfigured --cpus or quota can yield parallelism=1 (no parallelism!) or
#   over-provision. Check with: Runtime.getRuntime().availableProcessors()
java -XX:+PrintFlagsFinal -version | grep -i activeprocessor   # ActiveProcessorCount
```

The most important production fact is **container CPU detection**: in Kubernetes/Docker with CPU quotas, the JVM derives `availableProcessors()` from the cgroup limit. If the quota rounds down to 1, *every* parallel stream in the app silently runs single-threaded (no parallelism, pure overhead); if it's misread high, you over-subscribe cores and thrash. Always confirm `availableProcessors()` matches intent, and consider `-XX:ActiveProcessorCount=N` to pin it explicitly. 

The tuning pitfalls: (1) **don't resize the common pool to "fix" blocking** — enlarging it to absorb blocking I/O is a hack that bloats threads globally; instead run blocking work on a *dedicated* executor and keep the common pool for CPU-bound splitting. (2) For genuinely parallel CPU work that needs isolation from the common pool, construct a **private `ForkJoinPool(parallelism)`** and submit the parallel stream into it (`myPool.submit(() -> stream.parallel()...).get()`), so a heavy job can't starve unrelated parallel streams. (3) Use `ForkJoinPool.ManagedBlocker` (Q29) if you *must* block inside FJP tasks, so the pool compensates by spawning a temporary worker rather than losing parallelism. (4) Tune the **task granularity / threshold** (Q13) before touching pool size — too-fine splitting drowns in scheduling overhead, and no amount of pool tuning fixes that. In 2026, the cleaner answer for blocking fan-out is to sidestep FJP entirely with virtual threads, reserving `ForkJoinPool` tuning for CPU-bound divide-and-conquer.

#### Q81. [Theory] Explain memory-consistency effects of the `java.util.concurrent` package — what happens-before guarantees do collections, executors, and synchronizers provide for free?

A frequently-missed part of the JMM is that the entire `java.util.concurrent` package documents specific happens-before edges in its class-level "Memory Consistency Effects" sections, so correctly using these constructs gives you publication safety **without any extra `volatile` or synchronization on the data you pass through them**. This is by design: the synchronizers *are* the happens-before edges (Q57), so idiomatic use is automatically race-free.

The guaranteed edges, paraphrased from the JDK javadoc:

```
Collections (BlockingQueue, ConcurrentMap, etc.):
   actions in a thread BEFORE put/offer/add  HB  actions AFTER take/poll/get of that element
Executors:
   submit(task)/execute(task)  HB  the task begins running
   actions in the task          HB  Future.get() returns that result
   submission to a pool         HB  beforeExecute; afterExecute  HB  the pool sees completion
Synchronizers:
   CountDownLatch:  countDown()  HB  a returning await()
   CyclicBarrier:   await() before the barrier  HB  the barrier action  HB  await() returns
   Semaphore:       release()    HB  a subsequent acquire()
   Locks:           unlock()     HB  subsequent lock()  (and Condition.signal HB await returns)
```

The practical payoff is large: you can put a *plain, non-volatile* object into a `BlockingQueue` on the producer thread and the consumer that `take()`s it is guaranteed to see all the writes the producer made *before* the `put` — no `volatile` fields on the object, no defensive copy needed for visibility (you may still want immutability for *correctness*, but not for *visibility*). Likewise, results computed in an executor task are safely published to whoever calls `Future.get()`. This is why the canonical advice is to pass data *through* these constructs rather than sharing it alongside them: a thread that builds an object and hands it off via a concurrent collection or a future has, by the documented contract, already established the happens-before edge. The expert framing: the `java.util.concurrent` types are not just convenient — they are *memory-model citizens* whose contracts let you reason about cross-thread data handoff purely in terms of "did it go through a j.u.c handoff point?", collapsing a whole class of visibility bugs.

#### Q82. [Practical] You suspect a thread-pool task is leaking the `ThreadLocal` / MDC context across requests. How do you confirm it and fix context propagation?

The symptom is insidious: a log line or audit record shows the *wrong* user/trace/tenant id — request B sees request A's context — because a pooled worker reused a `ThreadLocal`/MDC value that a prior task set and never cleared (Q17, Q59). To **confirm** it: (1) reproduce under concurrency (single-threaded tests never show it); (2) take a **heap dump** and inspect a worker `Thread`'s `threadLocals` (`ThreadLocalMap`) — a populated entry on an *idle* pooled thread between requests is the smoking gun; (3) add a temporary assertion at task *entry* that the context is empty (`assert MDC.get("traceId") == null`) — if it fails, a previous task leaked.

The robust fix has two layers. First, **always clear in `finally`** at the task/request boundary (a servlet filter or interceptor is the right place):

```java
try {
    MDC.put("traceId", id);
    handle();
} finally {
    MDC.clear();                    // mandatory in pooled threads
}
```

Second — and this is the part most teams miss — context set on the *submitting* thread does **not** automatically flow to the pooled worker (the worker was created long ago; `InheritableThreadLocal` only copies at thread *creation*, Q59). To propagate the submitter's context to the task, **capture at submit time and re-install on the worker** via a decorator:

```java
// Capture the submitter's MDC, restore it inside the worker, clear afterward.
Executor decorated = task -> pool.execute(() -> {
    Map<String,String> ctx = MDC.getCopyOfContextMap();   // captured at submit time
    Runnable r = () -> {
        Map<String,String> prev = MDC.getCopyOfContextMap();
        if (ctx != null) MDC.setContextMap(ctx); else MDC.clear();
        try { task.run(); }
        finally { if (prev != null) MDC.setContextMap(prev); else MDC.clear(); }
    };
    r.run();
});
```

In Spring this is exactly what a `TaskDecorator` does on `ThreadPoolTaskExecutor`; the modern, framework-agnostic answer is **Micrometer's context-propagation** library (`ContextSnapshot` / `ContextExecutorService`), which captures *all* registered thread-local contexts (MDC, tracing, security) at submit and restores+clears them around the task. For the Loom era, migrating request context to `ScopedValue` (Q54) eliminates the leak class entirely because the binding is scope-bounded and torn down automatically. The discipline to articulate: a pooled thread is a *shared, reused* resource, so per-request thread-local state must be both *propagated in* at submit and *cleared out* in a finally — set-and-forget is a correctness *and* a security bug (cross-tenant bleed, Q34).

#### Q83. [Theory] Explain how `CompletableFuture.allOf` / `anyOf` actually behave with results and exceptions, and how to collect results correctly.

`allOf(cfs...)` and `anyOf(cfs...)` are deliberately *minimal* combinators whose return types surprise people: `allOf` returns `CompletableFuture<Void>` — it completes when **all** inputs complete but yields **no aggregated result**; `anyOf` returns `CompletableFuture<Object>` — it completes when **the first** input completes, carrying that one result as a raw `Object` (you lose the static type). To get the actual values you must join the *original* futures after `allOf` completes.

```java
List<CompletableFuture<Quote>> fs = ...;
CompletableFuture<List<Quote>> all =
    CompletableFuture.allOf(fs.toArray(new CompletableFuture[0]))
        .thenApply(v -> fs.stream()
                          .map(CompletableFuture::join)   // safe: all are done here
                          .collect(Collectors.toList()));
```

The exception semantics are the subtle part. `allOf` completes **exceptionally if *any* input fails**, and it does so with the *first* encountered failure — but critically it still waits for *all* inputs to finish before completing (a failure in one does not cancel or short-circuit the others; they all run to completion). So if you want partial results (the successful ones) when some fail, you must **not** rely on `allOf`'s result — instead attach a per-future `exceptionally`/`handle` *before* `allOf` so each future completes "successfully" with a sentinel, or after `allOf` filter `fs` by `f.isCompletedExceptionally()` (the scatter/gather pattern of Q11b). `anyOf` completes with the first input to finish **whether it succeeds or fails** — so the "winner" might be a *failure*, which is rarely what you want; for "first *successful* result, ignoring failures" you need `ShutdownOnSuccess` structured concurrency (Q25) or a manual approach attaching to each future and completing a shared result on first success.

```
allOf:  completes when ALL done; exceptional if ANY failed (first failure); never short-circuits
anyOf:  completes when FIRST done; carries that result/failure — even a fast FAILURE can "win"
```

The collection pitfalls to state: (1) calling `.get()`/`.join()` on each future *before* `allOf` completes would block sequentially, defeating the parallelism — always join *inside* the `thenApply` after `allOf`; (2) `join()` throws an *unchecked* `CompletionException` wrapper (vs `get()`'s checked `ExecutionException`), so unwrap `getCause()` (Q76); (3) neither combinator cancels the in-flight work of the others, so pair with `orTimeout` and per-client timeouts to actually bound resources.

#### Q84. [Practical] Your service intermittently exhausts its DB connection pool and threads pile up. Walk through diagnosing whether it is a leak, contention, or sizing problem.

Thread-pile-up with connection-pool exhaustion is a classic *resource starvation* incident, and the diagnostic discipline is to distinguish three distinct root causes that present identically (threads `WAITING`/`TIMED_WAITING` on `getConnection`): a **leak** (connections borrowed and never returned), **contention/slow queries** (connections held too long), or **undersizing** (pool simply too small for offered load).

```bash
# 1. Thread dump: where are threads parked?  Many threads here = pool starved:
jstack <pid> | grep -A5 "HikariPool" 
#   typical:  "...WAITING (parking) ... at com.zaxxer.hikari.pool.HikariPool.getConnection"

# 2. Pool metrics (HikariCP exposes via JMX / Micrometer):
#    hikaricp.connections.active   (borrowed right now)
#    hikaricp.connections.pending  (threads WAITING for a connection)
#    hikaricp.connections.usage    (how long connections are held)
```

The decision tree from the evidence: 

- **Leak** — `active` stays pinned at max and never drops even when traffic falls; `pending` grows; query metrics show *normal* execution times. The connections aren't slow, they're *lost* — a code path borrows a connection (or starts a transaction) and fails to close it on an exception path (missing try-with-resources / `finally`). HikariCP's `leakDetectionThreshold` (e.g., 30s) logs a stack trace of the borrow site that hasn't returned — the fastest way to find the offending code. Fix: ensure `try (Connection c = ds.getConnection())` everywhere and that the framework (JPA/JDBC template) is closing sessions.
- **Contention / slow holds** — `active` is high and `usage` (hold time) is high *because queries are slow* (lock waits on the DB, missing index, N+1, a long transaction holding a connection across an external HTTP call). The connection isn't leaked, it's *occupied too long*. Fix the query / transaction scope (never do remote I/O inside a DB transaction), not the pool size.
- **Undersizing** — `active` regularly hits max during normal healthy operation, hold times are *short and correct*, and `pending` spikes only at peak. The pool genuinely can't serve the concurrency. But the fix is *not* "just make it huge" — DB connections are expensive and a too-large pool overwhelms the database; the well-known guidance (HikariCP) is that the *right* pool size is often surprisingly small (`connections ≈ ((core_count × 2) + effective_spindle_count)` as a starting point), and you scale the *DB* or add read replicas rather than inflate the pool.

The overarching lesson: the symptom (threads waiting on `getConnection`) is identical across all three, so you **must look at active-vs-held-vs-pending metrics and query latency together** — sizing up the pool to "fix" a *leak* or *slow query* just delays the same outage and can topple the database. Enable leak detection, instrument hold time, and bound the transaction scope before ever touching pool size.

#### Q85. [Theory] What is the cost model of context switching and thread creation, and how do virtual threads change the arithmetic?

A **platform thread** maps 1:1 to an OS thread, and that carries two costs people underestimate. **Creation/footprint**: each thread reserves a large *stack* (default ~512 KB–1 MB, set by `-Xss`), committed lazily but reserved in address space, plus kernel thread-control-block overhead — so tens of thousands of platform threads exhaust memory long before they exhaust CPU (the "can't create more than ~few thousand threads" wall). **Context switching**: when the OS scheduler swaps one thread off a core for another, it saves/restores registers, switches the memory-management context, and — most expensively — *pollutes the CPU caches and TLB*, so the newly-scheduled thread starts cold and the descheduled thread's warm working set is gone. A context switch is on the order of *microseconds* of direct cost plus a longer tail of cache-refill cost, which is why oversubscribing cores (far more runnable threads than cores) collapses throughput into switching overhead — the thrashing of Q70.

```
Platform thread:  ~1 MB stack reserve + OS TCB  → ~few thousand max; switch = kernel + cache pollution
Virtual thread:   small heap object + growable stack (continuation)  → millions; "switch" = mount/unmount, no kernel
```

Virtual threads change the arithmetic on **both** axes. Footprint: a VT is a *heap object* with a *resizable* stack (a continuation) that starts tiny and grows only as deep as the call stack needs — hundreds of bytes to a few KB — so *millions* fit in the heap of an ordinary service. Switching: when a VT blocks, the JVM **unmounts** it from its carrier (copying its continuation stack to the heap) and mounts another VT — this is a *user-mode* operation with **no kernel transition and no full cache flush**, dramatically cheaper than an OS context switch. The net effect: the cost of "having a thread blocked on I/O" drops from ~1 MB + a scheduler slot to ~a few KB of heap and nothing else, which is precisely why thread-per-request blocking code can now scale to the concurrency levels that previously demanded async/reactive (Q33). The caveats that preserve the cost intuition: VTs do *not* make CPU-bound work faster (you still have only N cores; parallelism is unchanged — Q70), pinning (Q24/Q55) reintroduces carrier-blocking costs, and very deep or very many simultaneously-*mounted* stacks still consume heap. The expert summary: VTs cheapen *concurrency* (waiting), not *parallelism* (computing), by replacing an expensive kernel-scheduled OS thread per blocked task with a cheap heap-resident continuation.

#### Q86. [Practical] How do you write a deterministic, reproducible test for a race condition, and what tools force the interleavings that matter?

The core problem with concurrency tests is that the buggy interleaving is rare and timing-dependent — a normal unit test that spins up two threads "passes" 999 times out of 1000 and proves nothing (Q31). Effective concurrency testing means **deliberately forcing the dangerous interleavings**, not hoping the scheduler stumbles onto them. Several escalating techniques:

```java
// 1. Maximise the collision window: many threads, a barrier to release them
//    simultaneously, and a high iteration count to widen the race window.
int N = 100;
CyclicBarrier start = new CyclicBarrier(N);
AtomicInteger errors = new AtomicInteger();
List<Thread> ts = IntStream.range(0, N).mapToObj(i -> new Thread(() -> {
    try { start.await(); doRacyOperation(); }      // all fire at once
    catch (Throwable t) { errors.incrementAndGet(); }
})).toList();
ts.forEach(Thread::start);
for (Thread t : ts) t.join();
assertEquals(0, errors.get());
```

The barrier is the key trick: it parks all worker threads until the last one arrives, then releases them in a tight burst, maximising the probability of a true collision instead of letting them start staggered. But even this is *probabilistic*. For *correctness proofs* you escalate to purpose-built tools:

- **jcstress** (OpenJDK Java Concurrency Stress) — you write tiny `@Actor` methods and declare the set of *acceptable* observed results; jcstress runs them billions of times with aggressive JIT/memory-model stressing and fences, and flags any outcome the JMM forbids. This is the only tool that reliably surfaces *memory-ordering* bugs (the x86-vs-ARM reordering of Q67) — it's what the JDK itself uses to validate `java.util.concurrent`.
- **Lincheck** (JetBrains) — you declare operations on your data structure; it *generates* concurrent scenarios and checks the observed histories are **linearizable** (Q63) against a sequential spec, and in *model-checking mode* it deterministically **enumerates interleavings** (bounded), so a failure is reproducible with an exact schedule, not a flaky one-in-a-billion.
- **Deterministic schedulers / instrumentation** — tools that intercept thread scheduling to replay a specific interleaving, or `Thread.sleep`/`CountDownLatch`-based "rendezvous" injected at the exact line you want to interleave (a controlled test seam) to force a specific check-then-act window.

The honest framing for an interviewer: you *cannot* prove thread-safety by running the program (passing is not evidence — Q67), so the strategy is "maximise collision probability in CI stress tests, *and* model-check the core data structures with jcstress/Lincheck." For application-level logic (not data structures), the higher-leverage move is often to **eliminate the race by design** (confinement/immutability/a concurrent collection) so there's nothing to test, rather than to chase a flaky interleaving forever.

### 🔴 Expert — extended

#### Q87. [Practical] You are migrating a large reactive (Project Reactor) codebase to virtual threads. What is your phased strategy and what specifically breaks?

A wholesale rewrite is reckless; the credible answer is an **incremental, measured migration** that targets the parts where blocking-style code with VTs is a clear win and leaves genuinely-streaming reactive code alone (Q33). Phase it:

1. **Audit and classify.** Separate code that is reactive *for scalability it could now get for free* (request/response handlers, fan-out-then-join service calls) from code that is reactive *for its semantics* (true event streams, backpressure-sensitive pipelines, windowing/`flatMap`-concurrency, server-sent events). Only the first category benefits from VT migration; the second stays reactive.
2. **Flip the platform substrate first.** On Spring Boot 3.2+, `spring.threads.virtual.enabled=true` puts the servlet request-handling on virtual threads with one property — a low-risk change that immediately lets blocking handlers scale, *before* touching any business code.
3. **Migrate leaf blocking calls.** Replace `Mono.fromCallable(...).subscribeOn(boundedElastic())` wrappers around blocking JDBC/HTTP with straight blocking calls on virtual threads — the code gets shorter and the stack traces become real.
4. **Replace fan-out combinators.** `Mono.zip` / `Flux.merge` over independent service calls become `StructuredTaskScope` forks (Q25) — clearer lifecycle, real cancellation, debuggable stacks.

What specifically **breaks** or needs care: (1) **`synchronized`-around-I/O pinning** (Q24/Q55) — audit for it; on JDK < 24 replace with `ReentrantLock`, on JDK ≥ 24 (JEP 491) `synchronized` no longer pins but verify; (2) **thread-pool assumptions** — code that pooled threads or sized executors to core count, or used `ThreadLocal` caching tuned for "few threads," misbehaves with millions of VTs (don't pool VTs; migrate caches off per-thread storage) (Q55); (3) **context propagation** — Reactor's `Context` (carried in the subscription, not thread-locals) does not map onto thread-locals/MDC, so you must re-wire context to `ScopedValue` or Micrometer context-propagation (Q82); (4) **backpressure semantics are lost** — reactive `flatMap(fn, concurrency)` *bounds* in-flight work; the naive VT equivalent (a VT per item) has *no* bound and can overwhelm a downstream — you must reintroduce a `Semaphore` or bounded executor to cap concurrency; (5) **`Schedulers.parallel()` CPU work** stays on a bounded pool — don't move CPU-bound stages to VTs (Q70/Q85). The disciplined posture: migrate for *readability and debuggability* where blocking-style fits, keep reactive where its operators earn their keep, measure latency/throughput at each phase, and treat "lost backpressure" as the highest-risk regression.

#### Q88. [Theory] Explain how garbage collection interacts with concurrency: safepoints, concurrent collectors (G1/ZGC/Shenandoah), and the impact on tail latency.

GC and application threads are fundamentally a *concurrency problem between the collector and the mutators* (the application threads, so-called because they mutate the heap). The collector must observe a consistent view of object references, which historically meant a **stop-the-world (STW)** pause: every mutator thread is brought to a **safepoint** (Q49) so the collector can scan/move objects without the graph changing underneath it. The duration of that pause — and crucially the *time-to-safepoint* before it can even start — directly inflates request **tail latency** (p99/p99.9), because any request unlucky enough to be in flight during a pause eats the full pause time on top of its normal latency.

Modern collectors attack this by doing most work **concurrently** with the mutators, shrinking STW pauses to the parts that genuinely require a stable snapshot:

```
Pause behavior (typical 2026 large-heap service):
  Parallel/Throughput GC : long STW pauses (100s of ms+), best throughput, worst tail
  G1                     : mostly concurrent mark; STW evacuation pauses (~tens of ms), tunable
  ZGC / Shenandoah       : concurrent mark AND concurrent compaction; STW pauses ~sub-millisecond,
                           largely independent of heap size
```

The concurrency mechanisms are sophisticated: G1 does concurrent marking with **SATB (snapshot-at-the-beginning)** write barriers so mutator writes during marking don't lose objects; ZGC and Shenandoah do **concurrent compaction** — moving live objects *while the application runs* — using **load barriers / colored pointers (ZGC) or Brooks/forwarding pointers (Shenandoah)** so a mutator that dereferences a reference being relocated is transparently redirected to the moved object. These barriers add a small per-access cost to *every* mutator (a concurrency tax on the application threads), traded for near-elimination of STW pauses. 

The practical, latency-engineering consequences to articulate: (1) for a **low-latency/SLA-bound service** (Q30), choose a concurrent low-pause collector (ZGC/Shenandoah) so a GC pause never blows the p99.9 budget — you trade a bit of throughput and a per-access barrier cost for *bounded* pauses; (2) for a **batch/throughput** job, the parallel collector's longer pauses are fine and it has the best raw throughput; (3) a pause attributed to "GC" in your APM may actually be **time-to-safepoint** caused by one thread in a long counted loop (Q49), so always check safepoint logs (`-Xlog:safepoint`) before blaming the collector; and (4) the deepest interaction with the *application's* concurrency design — pre-allocating and reusing objects, avoiding allocation in hot paths, and the single-writer/ring-buffer design — is precisely to *avoid generating garbage* so the collector rarely runs at all, which is why allocation-elimination is a core low-latency technique, not a micro-optimization.

#### Q89. [Theory] Define and contrast atomicity, visibility, and ordering as the three independent pillars of the memory model, and map each Java construct to which pillars it provides.

Almost every Java concurrency bug is a failure of one of exactly **three independent guarantees**, and the expert skill is decomposing any symptom into which pillar is missing — because the constructs provide *different subsets*, and choosing the wrong tool means you fix one pillar while leaving another broken (the recurring theme of Q4, Q79).

- **Atomicity** — an operation (or compound sequence) is *indivisible*: no other thread can observe a partial/intermediate state. `count++` lacks it (3 sub-operations); a 64-bit `long` write can even lack *store* atomicity on a 32-bit VM (Q35).
- **Visibility** — a write by one thread becomes *observable* by another (no indefinite caching in a register/core cache). A plain field write may never be seen by another thread.
- **Ordering** — the *order* in which one thread's operations appear to another is constrained (no surprising reordering by compiler/CPU). The `data=42; ready=true` reordering (Q39) is an ordering failure.

```
Construct                    Atomicity            Visibility   Ordering (HB edge)
-------------------------------------------------------------------------------
plain field                  no (except ≤32-bit)  NO           NO
volatile                     single read/write    YES          YES (full fence)
AtomicInteger/Long (CAS)     YES (incl. RMW)      YES          YES
synchronized / Lock          YES (the section)    YES          YES
final field (safe publish)   n/a (immutable)      YES          YES (freeze)
VarHandle acquire/release    single op            YES          YES (one-directional)
```

Reading the table is the whole point: **`volatile` gives visibility + ordering but NOT compound atomicity** — so `volatile count++` fixes the wrong pillar and stays broken (Q79). **`AtomicInteger` gives all three for a single variable** — so it fixes `count++`, but cannot make *two* fields change atomically together. **`synchronized`/`Lock` gives all three for the whole critical section** — the only tool for multi-field invariants. **`final` gives visibility + ordering via the freeze rule** but only for immutable state. **acquire/release (`VarHandle`)** gives visibility + *one-directional* ordering more cheaply than full volatile (Q40), for experts shaving fence cost. The diagnostic procedure: take the symptom → ask "is a *partial state* observed? (atomicity), a *stale* value? (visibility), or an *out-of-order* value? (ordering)" → pick the construct whose row covers the missing pillar(s). Stating that these are *orthogonal* — you can have visibility without atomicity (`volatile long`), atomicity without multi-field consistency (`AtomicInteger`), etc. — is what distinguishes a precise mental model from "just add `synchronized` everywhere."

#### Q90. [Practical] Design the concurrency model for a high-throughput rate limiter shared across many threads. Compare token-bucket implementations and their contention characteristics.

A rate limiter is a *shared mutable counter on the hot path* — every request touches it — so its concurrency design directly bounds the throughput of whatever it protects, and the central tension is **accuracy vs contention**. Walk the options from simplest to most scalable:

1. **`synchronized`/lock around a token-bucket** — simplest and exactly correct, but every request serialises on one lock; under high QPS the limiter *itself* becomes the bottleneck and the convoy effect (Q66) kicks in. Fine for moderate rates.
2. **`AtomicLong` token count with a CAS refill loop** — lock-free; each request CAS-decrements available tokens, periodically refilling based on elapsed time. Far better than a lock at moderate contention, but under *extreme* contention the single CAS location ping-pongs its cache line (the MESI storm of Q20) and the retry loop spins — the same single-hot-location problem as `AtomicLong` counters (Q58).
3. **Sharded / striped buckets** — give each shard (e.g., per-core or per-thread-group via a striped index, Q77) a fraction of the rate and let requests hit their shard's bucket, amortising contention across cache lines (the `LongAdder` philosophy). Trade-off: *approximate* global rate — a shard can be exhausted while another has spare tokens, so the aggregate limit is fuzzy at the edges. Usually acceptable for protective limiting.

```
Single AtomicLong:   all requests ─CAS─► [tokens]        exact, contended hot line
Sharded buckets:     req → hash → [shard0|shard1|shard2]  scalable, approximate aggregate
```

The deeper design decisions an expert raises: (1) **single-JVM vs distributed** — the above is per-process; a cluster needs a *distributed* limiter (Redis with atomic Lua `INCR`+TTL, or a token service), where the concurrency problem moves to the datastore and you trade a network round-trip per request (or a *local* cache of leased tokens to amortise it — lease a batch of tokens from Redis, spend them locally lock-free, refill when low — combining shard-style local speed with global accuracy). (2) **algorithm choice** — token bucket allows bursts up to bucket size (good for bursty APIs); a sliding-window log is more accurate but stores timestamps (memory); a fixed-window counter is cheapest but has the boundary-spike problem (2× burst at the window edge). (3) **time source contention** — calling `System.nanoTime()` on every request for refill is itself a (mild) shared-resource cost; a common trick is a background thread that refills buckets on a tick (`ScheduledExecutorService`) so request threads only do a cheap CAS-decrement and never read the clock. The summary trade-off to state: you choose where on the **accuracy ↔ scalability** axis you sit — an exact global count forces a single contended location (or a network hop), while sharding/leasing buys lock-free local speed at the price of approximate aggregate enforcement; for *protective* limiting approximate-but-scalable almost always wins, for *billing/quota* limiting you pay for exactness.

#### Q91. [Theory] Walk through the lifecycle of a lock from uncontended to contended at the OS level — spinning, adaptive spinning, parking, and futexes.

A modern JVM lock is *not* a single mechanism but a **graduated response to contention**, designed so the common case (uncontended or briefly-contended) never touches the expensive OS-level blocking. Trace the escalation:

1. **Uncontended fast path** — acquiring a free lock is a single **CAS** on the object header (the thin-lock fast path of Q48) or, with elision, removed entirely. No kernel involvement, nanoseconds. This is the overwhelmingly common case, which is why "synchronized is slow" is folklore (Q48).
2. **Brief contention → spinning** — if the CAS fails (someone holds the lock), instead of immediately blocking (an expensive kernel transition + context switch, Q85), the thread **spins** — busy-waits, repeatedly retrying the CAS — on the bet that the holder will release *very soon* (most critical sections are tiny). Spinning burns CPU but avoids the ~microsecond+ cost of parking and the cache-cold restart on wakeup. Modern spins use `Thread.onSpinWait()`/`PAUSE` (Q56) to reduce power and pipeline waste.
3. **Adaptive spinning** — HotSpot doesn't spin a fixed count; it **adapts** based on history: if this lock's holder *usually* releases quickly (and the holder is currently running on another core), it spins longer; if spins on this lock have historically been fruitless (the holder is itself blocked, or critical sections are long), it gives up spinning sooner and parks. This heuristic targets the sweet spot between "wasted spinning" and "premature expensive parking."
4. **Sustained contention → parking** — when spinning won't pay off, the thread **parks** via `LockSupport.park()` → the OS de-schedules it so it consumes no CPU, and it's woken by `unpark()` on release. Under the hood on Linux this is a **futex** (fast userspace mutex): the *uncontended* lock/unlock happens entirely in userspace (a CAS on a memory word, no syscall), and only when a thread must actually *block* or *wake a waiter* does it make a `futex()` syscall into the kernel. This is the genius of the futex design — you pay the kernel cost *only* when you genuinely need to block, not on every lock operation.

```
free ──CAS (userspace, ns)──► held, uncontended
contended ──spin (burn CPU, bet on quick release)──► acquired, no kernel
spin fails ──adaptive decision──► park (futex syscall, de-scheduled, 0 CPU) ──unpark──► runnable
```

The expert payoff is understanding *why* the layering exists: each level trades CPU for latency differently — spinning wastes CPU to save the parking latency (worth it for short holds), parking wastes latency to save CPU (worth it for long holds), and the futex ensures the userspace-only fast path stays cheap. This is also why **fairness** (Q47) and **critical-section length** matter so much: a long critical section pushes waiters past spinning into parking (expensive wakeups, convoy risk Q66), whereas keeping sections tiny lets contenders resolve via cheap spinning. And it's why `ReentrantLock` (built on AQS + `LockSupport.park`, Q46) and `synchronized` (HotSpot's adaptive spin + ObjectMonitor) ultimately bottom out at the same OS primitives — the differences are in policy (fairness, interruptibility, timeout), not in the fundamental spin-then-park-via-futex mechanism.

#### Q92. [Practical] How do you profile and reduce lock contention in a production service without taking it down? Walk through the tooling and the fixes.

Lock contention rarely shows as a crash — it shows as *throughput that won't scale with cores* and *latency that grows under load* while CPU is *not* saturated (threads are blocked, not working). The diagnostic goal is to find *which* lock is hot and *why*, with minimal production impact, then attack contention structurally. The tooling, from lowest to highest overhead:

```bash
# 1. Thread dumps (zero install, near-zero overhead): take 3–5, seconds apart.
#    Count how many threads are BLOCKED on the SAME monitor address — that's the hot lock.
jstack <pid> | grep -E "BLOCKED|- waiting to lock" | sort | uniq -c | sort -rn

# 2. async-profiler in LOCK mode (low overhead, safe in prod): attributes wall-clock
#    blocked time to the exact lock and call site, as a flame graph.
./profiler.sh -e lock -d 30 -f lock-profile.html <pid>

# 3. JFR (built-in, continuous, ~1% overhead): jdk.JavaMonitorEnter /
#    jdk.ThreadPark events show monitor contention and park durations.
jcmd <pid> JFR.start name=lock settings=profile duration=60s filename=lock.jfr
jfr print --events jdk.JavaMonitorEnter,jdk.ThreadPark lock.jfr
```

The discipline: a single thread dump can mislead (you catch one instant); **multiple dumps** showing the *same* set of threads blocked on the *same* monitor across samples confirms sustained contention rather than a momentary blip. async-profiler's lock mode is the production workhorse because it's sampling-based (safe at scale) and points at the exact source line and the holder's stack. JFR is ideal when you want *always-on* low-overhead recording so you already have the data when an incident happens.

The fixes, in order of leverage (cheapest structural win first): (1) **shrink the critical section** — move everything that doesn't need the lock (I/O, allocation, logging, computation) *outside* it; a lock held across a remote call is the cardinal sin (it turns a network latency into lock-hold time, Q66/Q84). (2) **reduce lock scope / split the lock** — replace one coarse lock with **lock striping** (Q77) or per-shard locks so independent keys don't contend; replace a `synchronized` map with `ConcurrentHashMap` (per-bin locking). (3) **replace the lock with a lock-free structure** — `AtomicLong`/`LongAdder` for counters (Q58), a `ConcurrentLinkedQueue` for handoff, `StampedLock` optimistic reads for read-dominated state (Q26). (4) **eliminate sharing** — confine the state to one thread (single-writer/event-loop, Q78) so there's no lock at all. (5) **read/write split** — `ReadWriteLock`/`StampedLock` when reads vastly outnumber writes (Q26/Q61). The expert framing: contention is a *design* signal, not a tuning knob — you don't "make the lock faster," you *reduce how much shared mutable state crosses thread boundaries*, and you verify the fix by re-profiling under the same load and confirming the blocked-time flame graph shrank and throughput now scales with cores.

#### Q93. [Theory] What are the concurrency hazards specific to `HashMap` under concurrent access (beyond simple lost updates), including the infamous resize infinite loop?

A plain `HashMap` shared across threads without synchronization is *catastrophically* unsafe in ways that go far beyond a lost `put` — the failure modes are **structural corruption**, and naming the resize-loop bug specifically signals deep familiarity. The hazards:

1. **The resize infinite loop (pre-Java-8).** When a `HashMap` grows past its load factor it **rehashes** entries into a larger bucket array. In Java 7 and earlier, the transfer reversed each bucket's linked list, and if two threads resized *concurrently*, the list pointers could be wired into a **cycle** (A→B→A). A *subsequent* `get()` traversing that bucket then **spins forever** — a thread pinned at 100% CPU inside `HashMap.get`, the classic "one core stuck at 100% with a stack trace in `HashMap.getEntry`/`transfer`" production incident. It's especially nasty because the corruption happens during a `put` (the resize) but the *symptom* (infinite loop) appears later in an unrelated `get`, on a *different* thread. Java 8 changed the transfer to preserve order (no list reversal), which removes *this specific* loop — **but Java 8 `HashMap` is still not thread-safe**: concurrent puts can still lose data and corrupt the tree/list structure.
2. **Lost updates and visibility.** Concurrent `put`s to the same bucket can drop entries (two threads writing the same bucket head); and without a happens-before edge, one thread may not see another's writes at all (stale `size`, stale entries).
3. **Corrupted size / treeification races.** The `size` counter and the list↔red-black-tree conversion (Q14) are not atomic, so concurrent structural changes can leave the map in an inconsistent internal state — `NullPointerException`, wrong `size()`, or lost nodes.

```
Java 7 concurrent resize:  bucket list A→B  gets wired  A→B→A (cycle)
                           → later get(k) on that bucket loops forever (100% CPU)
Java 8+:                   no resize-loop, BUT still: lost puts, corrupt tree, bad size
```

The correct responses, and *why* each works: (1) use **`ConcurrentHashMap`** — designed for concurrency with per-bin locking and lock-free reads (Q14), the default answer; (2) `Collections.synchronizedMap(map)` wraps every method in a *single* lock — correct but serialises all access (a scalability bottleneck, and *iteration* still needs manual external synchronization on the wrapper); (3) confine the map to one thread (Q78); or (4) make it effectively immutable (build once, publish safely, never mutate). The interview-grade point: the danger of a shared plain `HashMap` is not merely "you might lose a write" — it's that it can **silently corrupt its internal structure**, producing an infinite loop or NPE *elsewhere and later*, which is exactly the kind of timing-dependent, non-reproducible production bug (Q31) that's brutal to diagnose. That severity is the reason "never share a `HashMap` across threads" is an absolute rule, not a style preference.

#### Q94. [Practical] How do you design graceful degradation and bounded resource usage for an async pipeline so one slow dependency cannot cascade into total failure?

A slow or failing downstream dependency is the textbook cause of a **cascading failure**: requests pile up waiting on the slow dependency, threads/connections/queues fill, and the *entire* service falls over even though only one dependency was sick. The concurrency-design goal is **isolation and bounded resource usage** so a failure stays contained — the principles behind the bulkhead and circuit-breaker patterns.

The mechanisms, layered:

1. **Bounded everything (bulkheads).** Every resource pool must be *bounded* and ideally *isolated per dependency*: a separate bounded thread pool / semaphore for each downstream so that calls to slow dependency A consume only *A's* budget and cannot starve calls to healthy dependency B. This is the **bulkhead** pattern — partition resources so a flood in one compartment can't sink the ship. An unbounded queue or a shared global pool is the anti-pattern that lets one slow dependency consume all capacity (Q9/Q75).

```java
// Bulkhead: cap concurrent calls to a flaky dependency with a Semaphore.
final Semaphore depA = new Semaphore(20);   // at most 20 in-flight calls to A
boolean ok = depA.tryAcquire(50, TimeUnit.MILLISECONDS);   // fail fast if saturated
if (!ok) return fallbackA();                // shed load instead of queuing forever
try { return callDependencyA(); } finally { depA.release(); }
```

2. **Timeouts everywhere.** Every async call needs a *hard* timeout (`orTimeout` on `CompletableFuture`, a per-client read/connect timeout, a `StructuredTaskScope` deadline) so a hung dependency releases its resources instead of holding a thread/connection forever. Critically, `orTimeout` alone doesn't cancel the underlying I/O (Q11b), so pair the logical deadline with a *transport-level* timeout that actually frees the socket.
3. **Circuit breaker.** Track failure/latency rates per dependency; when they breach a threshold, **open** the circuit — fail fast (return a fallback) *without* even attempting the call — so you stop pouring requests (and threads) into a known-sick dependency, giving it room to recover. After a cooldown, a **half-open** probe tests recovery. Resilience4j/Sentinel implement this; the concurrency point is that the breaker's state is *shared mutable state on the hot path*, so it's implemented with atomics/lock-free counters (sliding-window of outcomes) to avoid becoming a contention point itself.
4. **Graceful degradation / fallbacks.** When a call is shed, times out, or the breaker is open, return *degraded but valid* output — a cached value, a default, a partial result (the per-future `exceptionally` of Q11b), an empty recommendation list — rather than propagating the failure. The product decision is "what's the least-bad answer when this dependency is down?"
5. **Backpressure at the edge.** Bound the *intake* (a bounded request queue, a rate limiter Q90) so the service sheds load at the front door under overload instead of accepting work it can't complete — admission control beats collapsing.

```
Request ─► [rate limit / bounded intake] ─► [per-dependency bulkhead semaphore]
            ─► [circuit breaker: open? → fallback] ─► [timed async call] ─► result
            └────────────── any gate fails → graceful fallback, never block forever ─┘
```

The unifying expert principle: cascading failure is fundamentally a **resource-exhaustion** problem (Q34), so the defense is to make *every* resource bounded, *isolate* dependencies so failures don't share a budget, *fail fast* (timeout + breaker) instead of queuing indefinitely, and *degrade gracefully* instead of propagating. The trade-off is that you deliberately *reject or degrade* some requests during a partial outage — accepting *partial* unavailability to preserve *overall* availability — which is almost always the right call for a service whose alternative is total collapse.

#### Q95. [Theory] Explain the ABA problem in depth with a concrete failure scenario, and compare the mitigations: tagged pointers, hazard pointers, RCU, and GC.

The ABA problem (introduced in Q21) is the subtle trap at the heart of CAS-based lock-free programming: a `compareAndSet(A, C)` checks only that the value is *still* `A` and succeeds — but it **cannot tell that the value changed A→B→A** in between. The pointer *looks* unchanged, so the CAS "succeeds," yet the world moved underneath it. A concrete failure on a lock-free stack:

```
Treiber stack, top → A → B → C
Thread T1: reads top=A, plans CAS(top, A→B) to pop A (it read A.next == B)
T1 is PREEMPTED here.
Thread T2: pops A, pops B (top→C), then pushes A back (reusing the node).  Now top → A → C.
T1 RESUMES: CAS(top, A → B) — top IS still A, so CAS SUCCEEDS!
            But B was already popped and is no longer in the stack.
            top now → B (a freed/dangling node) → corruption.
```

T1's CAS succeeded against a *recycled* `A` whose `.next` it had read as `B` long ago, splicing a removed node back in. The mitigations, with their trade-offs:

- **Tagged pointers / version stamps (`AtomicStampedReference`)** — pair the reference with a monotonically-incrementing counter; CAS checks *(ref, stamp)* together, so the recycled `A` carries a *different* stamp and the CAS fails correctly (Q21). Simple and effective, but: needs a double-width CAS (or a wrapper object allocation), and the counter can *theoretically* wrap around (ABA on the stamp itself) — mitigated by a wide enough counter. The standard JDK answer.
- **Hazard pointers** — each thread publishes the pointers it's *currently* using ("hazardous"); memory can't be reclaimed/reused while any thread's hazard pointer references it. This *prevents the reuse* that causes ABA rather than detecting it. Powerful and used in C++ lock-free libraries, but complex bookkeeping and per-access overhead.
- **RCU (Read-Copy-Update)** — readers proceed with zero synchronization; writers create a new version and defer reclaiming the old one until a *grace period* passes (all pre-existing readers have finished), so a reader never sees freed/reused memory. Excellent for read-mostly data (heavily used in the Linux kernel); the cost is deferred reclamation (memory held longer) and grace-period tracking.
- **Garbage collection (the Java advantage)** — this is *why ABA is largely a non-issue for idiomatic Java lock-free code* (Q22). As long as `T1` holds a reference to node `A`, the GC *cannot reclaim or reuse* that exact object, so a "recycled A" is a *different* object with a different identity — the classic manual-memory ABA (reusing the same address) simply can't arise. Java's ABA risk reappears only when you reuse *values* yourself (object pools, recycling nodes, or ABA on a *primitive*/index like a counter or array slot), which is exactly when you reach for `AtomicStampedReference`.

```
Mitigation        Mechanism                       Cost                    Java relevance
Tagged pointer    detect via version stamp        double-CAS / wrapper    AtomicStampedReference
Hazard pointers   prevent reuse while referenced  per-access bookkeeping  rare in pure Java
RCU               defer reclaim past grace period writer-side deferral    read-mostly structures
GC                no reuse while reachable         GC overhead             default; sidesteps ABA
```

The expert synthesis: ABA is fundamentally a **memory-reuse** hazard, so every mitigation either *detects* reuse (stamps), *prevents* reuse while in use (hazard pointers, RCU grace periods), or *delegates* reuse-prevention to a reclaimer (GC). Java's tracing GC silently provides the strongest protection for the common case, which is the unsung reason lock-free programming is meaningfully *easier* in Java than in C/C++ — and the precise boundary where you must still care is when *you* recycle identities the GC isn't managing for you.

#### Q96. [Practical] How do you debug a "works locally, deadlocks/hangs only in production" intermittent concurrency issue when you cannot easily reproduce it?

The defining difficulty is non-reproducibility (Q31): the bug depends on a specific timing/load/scheduling that your local box never hits, so you cannot iterate with a debugger. The strategy is **observability-first** — instrument so that when it *does* happen in production, you capture enough to diagnose it from a single occurrence, because you may not get a second chance.

The playbook:

```bash
# 1. AUTOMATE capture on the symptom. If threads hang, grab dumps automatically:
#    - On OOM:        -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/dumps
#    - Periodic / on-alert thread dumps (cron or APM-triggered) during the incident window:
jcmd <pid> Thread.print > /var/dumps/td-$(date +%s).txt   # repeat 3–5x, seconds apart
#    - jcmd <pid> GC.heap_dump for a heap dump if state inspection is needed.

# 2. Always-on low-overhead recording so the evidence pre-exists the incident:
jcmd <pid> JFR.start name=cont maxage=30m maxsize=200m settings=profile
#    JFR's jdk.JavaMonitorEnter / jdk.ThreadPark / safepoint events reconstruct what
#    happened in the minutes BEFORE the hang.
```

The deadlock case is the *easiest* once you have dumps: the JVM's deadlock detector prints **`Found one Java-level deadlock`** with the exact cycle and lock addresses (Q16) — three dumps confirming the *same* threads stuck on the *same* locks across all samples is conclusive. The harder cases — a *livelock* (threads busy but not progressing, CPU high — Q18), *starvation*, or a hang on an external resource (a `getConnection` that never returns — Q84) — require correlating thread states across multiple dumps and pairing with pool/connection metrics.

Beyond capture, the structural tactics for non-reproducible bugs: (1) **make it reproducible by amplifying timing** — in a staging/load environment, add load, inject latency/jitter (a proxy like Toxiproxy to slow a dependency, or `Thread.sleep` at suspected interleaving seams), and run *chaos*/soak tests; the bug that's 1-in-a-million locally is frequent at production QPS, so reproduce *the load*, not the box. (2) **Stress the data structures with jcstress/Lincheck** (Q86) if you suspect a custom concurrent component — that *deterministically* enumerates interleavings. (3) **Reason from the code with the dump as ground truth** — once a dump shows threads X and Y on locks L1/L2, you trace the two acquisition orders and usually *see* the lock-ordering violation or the held-lock-across-I/O without ever reproducing live. (4) **Add defensive instrumentation for next time** — `tryLock(timeout)` instead of `lock()` so a would-be deadlock becomes a *logged timeout with a stack trace* rather than a silent hang, and structured logging of lock-acquisition order on suspect paths. 

The mindset to convey: you do not "step through" a production concurrency bug — you **set up observability so a single occurrence yields a thread dump + JFR recording + metrics**, then diagnose *offline* from that evidence, *and* simultaneously try to reproduce by recreating the *load and timing* (not the environment) in staging. And the durable fix is always systemic (Q31): eliminate the lock-ordering or shared-mutable-state class of bug (lock ordering, confinement, immutability), add the timeout/instrumentation seam so the next instance is *loud* instead of silent, and add a stress/soak test so it can't silently return.

#### Q97. [Theory] Explain the concept and trade-offs of "elastic" vs "fixed" concurrency, and how work-stealing, queue choice, and rejection policies shape an executor's behavior under load.

An executor's behavior under load is determined not by one knob but by the *interaction* of four design choices — pool elasticity, queue type, work distribution, and rejection policy — and an expert reasons about them as a system because they constrain each other (the counter-intuitive `execute()` ladder of Q41 is exactly this interaction).

**Fixed vs elastic pools.** A *fixed* pool (`corePoolSize == maxPoolSize`) gives **predictable resource usage and stable latency** — you know exactly how many threads exist, so memory and context-switch overhead are bounded, at the cost of no burst absorption beyond the queue. An *elastic* pool (`max > core`, with idle threads reaped after `keepAlive`) **absorbs bursts** by spawning temporary threads, trading predictability for adaptability — but with the trap that, as Q41 explains, the pool only grows past core size *after the queue rejects*, so elasticity is meaningless with an unbounded queue (it never grows; it just OOMs).

**Queue choice shapes the load curve:**

```
Queue type             Behavior under load                         Use when
SynchronousQueue       no buffering → forces immediate thread       elastic pool that should
  (0 capacity)         creation (up to max), else reject            grow aggressively (cachedThreadPool)
Bounded ArrayBQ        buffer N, then grow to max, then reject      backpressure + bounded burst
Unbounded LinkedBQ     buffer forever (max is dead code)            NEVER in prod (OOM risk)
```

The `SynchronousQueue` + large max is the "grow first" strategy (a task that can't be handed to an idle thread *immediately* forces a new thread); a *bounded* queue + modest max is the "buffer, then grow, then shed" strategy — the production default because it has *backpressure built in*.

**Work-stealing (`ForkJoinPool`) vs a shared queue.** A classic `ThreadPoolExecutor` has *one shared queue* all workers pull from — simple, but the queue head is a contention point and there's no load balancing of *subtasks*. A work-stealing pool gives each worker its **own deque** and lets idle workers **steal** from busy ones (Q12) — far better for *recursive/uneven* workloads and *many small tasks* because it minimizes contention (workers mostly touch their own deque) and self-balances. The trade-off: work-stealing assumes tasks are independent and ideally splittable; it's not a drop-in for ordered or strongly-FIFO workloads, and stealing has its own overhead for coarse, uniform tasks where a shared queue is fine.

**Rejection policy is the overload contract:**

```
AbortPolicy (default)  → throw RejectedExecutionException on the submitter   (fail fast, loud)
CallerRunsPolicy       → run the task on the SUBMITTING thread                (natural backpressure!)
DiscardPolicy          → silently drop the new task                          (lossy, dangerous)
DiscardOldestPolicy    → drop the oldest queued task, enqueue the new one     (lossy, freshness-biased)
```

`CallerRunsPolicy` is the subtle expert favorite: when the pool and queue are full, the *submitting thread* executes the task itself, which (a) slows the producer down to the pool's throughput — *automatic backpressure* — and (b) never loses work. The discard policies are *silent data loss* (the vanishing-task bug of Q52) and are appropriate only when dropping work is genuinely acceptable (e.g., sampling).

The synthesis to articulate: these four choices are *coupled* — "elastic" only works with a queue that can reject; "backpressure" comes from a *bounded* queue plus `CallerRunsPolicy`; "burst absorption" comes from queue depth *or* pool elasticity (you choose which); work-stealing suits many small independent tasks while a shared bounded queue suits steady throughput with explicit backpressure. So designing an executor is choosing a *coherent overload behavior* — "buffer a bounded burst, then apply backpressure to the producer, never lose work, with predictable thread count" maps to *bounded `ArrayBlockingQueue` + modest max + `CallerRunsPolicy`*, whereas "grow aggressively for spiky independent work" maps to *`SynchronousQueue` + high max* — and stating that the knobs are interdependent (not independently tunable) is the mark of someone who's operated these in production.

#### Q98. [Practical] Your team must choose between `synchronized`, `ReentrantLock`, `StampedLock`, `Atomic*`, and a concurrent collection for a hot shared data structure. Give the decision framework you'd actually apply.

The honest senior answer rejects the premise that there's a single "best" primitive — the right choice is a function of the **access pattern** (read/write ratio, granularity), **contention level**, **what invariants must hold atomically**, and **operational constraints** (debuggability, fairness needs). The framework I apply, as a decision tree:

```
1. Is the shared state a SINGLE counter / flag / reference?
     → flag (independent writes): volatile
     → counter, read-often & exact:        AtomicLong
     → counter, write-heavy, read-rare:     LongAdder            (Q58)
     → ref swapped wholesale:               AtomicReference / VarHandle

2. Is it a standard COLLECTION accessed concurrently?
     → map:        ConcurrentHashMap                              (Q14)
     → queue/handoff: ConcurrentLinkedQueue / (Array|Linked)BlockingQueue (Q42)
     → read-mostly small list (listeners): CopyOnWriteArrayList   (Q44)
     (i.e., don't hand-roll locking around a HashMap — use the concurrent type)

3. Does correctness need a MULTI-FIELD invariant or a compound action no atomic covers?
     → reads ≈ writes, simple, low contention:   synchronized     (default: simplest, auto-release)
     → need timeout / interruptible / fairness / multiple conditions: ReentrantLock
     → reads VASTLY outnumber writes, short critical sections, hot path: StampedLock optimistic read (Q26)
     → reads outnumber writes, LONG read sections:                     ReentrantReadWriteLock (Q61)
```

The reasoning behind the defaults: **start with `synchronized`** — it's the simplest, auto-releases on exceptions (no `finally` leak), the JIT optimizes uncontended cases to near-free (Q48), and it's the most debuggable (deadlock detector reports it, dumps show `BLOCKED` clearly). You **escalate to `ReentrantLock` only for a capability `synchronized` lacks**: a timeout to break potential deadlock (`tryLock`), interruptible acquisition, fairness for an SLA, or multiple `Condition` wait-sets (the bounded-buffer of Q8b). You reach for **`StampedLock` only on a measured hot, read-dominated path** where its optimistic read eliminates read-side cache contention (Q26) — and accept its sharp edges (not reentrant, no conditions, easy to misuse into torn reads). You prefer **`Atomic*`/`LongAdder` whenever the state collapses to a single variable** because lock-free avoids the convoy/priority-inversion pathologies entirely (Q66) and scales better. And you **always prefer a purpose-built concurrent collection** over locking around a plain one — it's both faster (fine-grained/lock-free internally) and more correct (no forgotten lock on some access path, Q93).

The meta-rules I'd state explicitly: (1) **measure, don't guess** — choose the simplest correct option first (`synchronized` / a concurrent collection), profile under realistic load (Q92), and only escalate to `StampedLock`/lock-free *after* contention shows up, because the complex options have real correctness risk and the simple ones are often fast enough; (2) **prefer eliminating sharing over synchronizing it** — confinement (single-writer/event-loop, Q78) and immutability (Q45) beat *every* locking choice when the design allows, because they remove the problem rather than manage it; (3) **the cost of the wrong sophisticated choice is high** — a misused `StampedLock` or hand-rolled lock-free structure causes subtle, hard-to-reproduce bugs (Q67/Q96), so sophistication must be *earned by measurement*, not adopted speculatively. The one-line summary: *simplest correct primitive first, escalate on measured need, and eliminate sharing wherever the design permits* — choosing a concurrency primitive is an exercise in matching the tool to the access pattern and contention, not in reaching for the cleverest mechanism.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q99. [Coding] Implement a clean producer/consumer shutdown using a "poison pill" sentinel.

A bounded `BlockingQueue` decouples producers from consumers, but the hard part is *stopping* cleanly: consumers block forever on `take()` once producers stop. Interrupting consumers works but is blunt (you lose the "drain remaining items first" guarantee). The idiomatic alternative is a **poison pill** — a unique sentinel object the producer enqueues exactly once per consumer to signal "no more work after this point." Because the pill flows through the same FIFO queue as real items, every item enqueued *before* it is guaranteed processed first — graceful drain for free.

```java
import java.util.concurrent.*;

class PoisonPillPipeline {
    private static final Object POISON = new Object();   // unique sentinel
    private final BlockingQueue<Object> q = new ArrayBlockingQueue<>(1000);
    private final int consumers;

    PoisonPillPipeline(int consumers) { this.consumers = consumers; }

    void produce(Iterable<String> items) throws InterruptedException {
        for (String s : items) q.put(s);
        for (int i = 0; i < consumers; i++) q.put(POISON);  // one pill per consumer
    }

    Runnable consumer() {
        return () -> {
            try {
                while (true) {
                    Object item = q.take();
                    if (item == POISON) return;             // graceful exit, no interrupt
                    process((String) item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
    void process(String s) { /* ... */ }
}
```

The two correctness subtleties an interviewer will probe: (1) **one pill per consumer** — a single pill would be consumed by one worker and the others would block forever; you must enqueue exactly `N` pills for `N` consumers, and each consumer that sees a pill re-`return`s without re-enqueueing it (re-enqueueing is an alternative design for an unknown consumer count, but then the last consumer must not loop). (2) **bounded-queue deadlock risk** — if the queue is full when the producer tries to `put` the pills, and consumers have already exited, `put` blocks forever; in practice the pills are added after all real work, and consumers only exit *on* a pill, so the invariant holds. The poison-pill pattern is preferred over interruption when you must process every already-queued item before stopping (e.g., flushing a write buffer); use interruption when you want to abandon in-flight work immediately. **Time/space:** O(1) per item, O(capacity) queue.

#### Q100. [Coding] Use `CountDownLatch` two ways: a start gate that releases all workers at once, and a done gate that lets the coordinator wait for completion.

`CountDownLatch` is a one-shot counter; a single class often uses *two* latches together to bracket a parallel section — a **start latch** (count 1) that all workers `await()` so they begin simultaneously (useful for benchmarking to maximize contention), and a **done latch** (count N) that the coordinator `await()`s so it knows every worker finished. This is the canonical pattern from *Java Concurrency in Practice* for timing a concurrent task.

```java
import java.util.concurrent.*;

long timeTasks(int nThreads, Runnable task) throws InterruptedException {
    CountDownLatch startGate = new CountDownLatch(1);     // released once, by us
    CountDownLatch endGate   = new CountDownLatch(nThreads);

    for (int i = 0; i < nThreads; i++) {
        new Thread(() -> {
            try {
                startGate.await();                         // all workers block here
                try { task.run(); }
                finally { endGate.countDown(); }           // count down even on failure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    long t0 = System.nanoTime();
    startGate.countDown();                                 // fire the start gun → all go at once
    endGate.await();                                       // wait for every worker
    return System.nanoTime() - t0;
}
```

The "why" is twofold. The **start gate** removes thread-creation skew: without it, the first thread might finish before the last is even created, so your measured contention is unrealistically low; with it, all threads are parked and released in one burst, maximizing the overlap you want to stress-test. The **end gate** gives the coordinator a clean happens-before edge — every action in every worker *happens-before* the coordinator's `endGate.await()` returns, so any results the workers wrote are visible without extra synchronization (Q57). The critical bug to avoid is calling `countDown()` outside a `finally`: if `task.run()` throws, a missed `countDown()` leaves `endGate` stuck above zero and the coordinator hangs forever. Unlike `CyclicBarrier`, a latch **cannot be reset** — it is strictly one-shot; reach for `CyclicBarrier`/`Phaser` if you need to repeat the rendezvous across rounds.

#### Q101. [Theory] What is the difference between `Executor`, `ExecutorService`, and `ScheduledExecutorService`, and why is the layered interface design useful?

The three form a deliberate **interface hierarchy** of increasing capability, and recognizing the layering shows you understand programming-to-an-interface. `Executor` is the minimal contract — a single method `void execute(Runnable)` that decouples *task submission* from *task execution mechanics* (you don't know or care whether it runs inline, in a new thread, or in a pool). `ExecutorService` extends it with **lifecycle and result management**: `submit()` returning a `Future`, `invokeAll`/`invokeAny` for batches, and the `shutdown()`/`shutdownNow()`/`awaitTermination()` graceful-stop protocol (Q71). `ScheduledExecutorService` extends *that* with **time-based** scheduling: `schedule(delay)`, `scheduleAtFixedRate`, and `scheduleWithFixedDelay`.

```
Executor                 → execute(Runnable)                      (fire-and-forget)
  └ ExecutorService      → + submit/Future, invokeAll/Any, shutdown lifecycle
      └ ScheduledExecutorService → + schedule / scheduleAtFixedRate / scheduleWithFixedDelay
```

The design value is that callers can **depend on the narrowest interface they need**: a library method that just needs to run tasks should accept an `Executor`, not a concrete `ThreadPoolExecutor`, so the caller is free to supply a direct executor, a pool, a virtual-thread executor, or a test double that runs tasks synchronously. This is the Interface Segregation and Dependency Inversion principles applied to concurrency. A subtle but important point: returning an `ExecutorService` from a factory and exposing it as `Executor` to consumers lets you retain shutdown control while preventing callers from shutting your pool down. In 2026, `Executors.newVirtualThreadPerTaskExecutor()` returns an `ExecutorService` that also implements `AutoCloseable`, so the same abstraction now seamlessly covers the virtual-thread model — code written against `ExecutorService` migrates to Loom with no signature change.

### 🟡 Intermediate — extended

#### Q102. [Coding] Implement a thread-safe lazy memoizer (the *Java Concurrency in Practice* `Computable` cache) that never computes the same key twice.

A cache that wraps an expensive pure function must guarantee that concurrent callers for the *same* key share a single computation rather than racing to compute it N times. The naive `ConcurrentHashMap<K,V>` with `if (map.get(k)==null) map.put(k, compute(k))` still allows two threads to both miss and both compute. The classic JCiP solution stores **`Future<V>` instead of `V`**, and uses `putIfAbsent` so exactly one thread's `FutureTask` wins and the losers `get()` that same future:

```java
import java.util.concurrent.*;

class Memoizer<K, V> {
    private final ConcurrentMap<K, Future<V>> cache = new ConcurrentHashMap<>();
    private final Function<K, V> fn;
    Memoizer(Function<K, V> fn) { this.fn = fn; }

    V get(K key) throws InterruptedException {
        while (true) {
            Future<V> f = cache.get(key);
            if (f == null) {
                FutureTask<V> ft = new FutureTask<>(() -> fn.apply(key));
                f = cache.putIfAbsent(key, ft);       // atomic: only one ft wins
                if (f == null) { f = ft; ft.run(); }   // we won → compute
            }
            try { return f.get(); }                    // losers block on the SAME future
            catch (CancellationException e) { cache.remove(key, f); }  // retry
            catch (ExecutionException e) { throw launder(e.getCause()); }
        }
    }
    private RuntimeException launder(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        throw new IllegalStateException(t);
    }
}
```

The cleverness is **caching the *future*, not the *value***: between the moment thread A starts computing and the moment it finishes, the map already holds a `Future` for that key, so thread B finds it and blocks on `get()` instead of starting a second computation — collapsing N concurrent requests for a key into one computation. `putIfAbsent` makes the "claim the key" step atomic so there's no window where two threads both insert. The two refinements interviewers expect: (1) **cache the failure?** — by default you should *not* cache exceptions; the `ExecutionException`/`CancellationException` branches `remove` the failed future so a later call retries rather than permanently serving a cached error; (2) the modern one-liner is `cache.computeIfAbsent(key, fn)`, which the JDK implements with bin-level locking — but note `computeIfAbsent`'s mapping function must **not** modify the same map or call back recursively (it can deadlock/throw), so the explicit `Future` version is still relevant when the computation itself touches the cache. **Time:** O(1) amortized lookup; one computation per key.

#### Q103. [Coding] Use `Semaphore` to build a bounded resource pool (e.g., a connection pool) with fair access and timeout.

A `Semaphore` with N permits is the textbook way to bound concurrent access to N identical resources — acquire a permit before taking a resource, release it after returning the resource. Combining the semaphore with a thread-safe container of the actual resources gives a minimal but correct pool, with `tryAcquire(timeout)` providing the all-important "fail fast instead of hang forever when exhausted" behavior.

```java
import java.util.concurrent.*;

class BoundedPool<R> {
    private final Semaphore permits;
    private final BlockingQueue<R> available;

    BoundedPool(java.util.List<R> resources, boolean fair) {
        this.permits = new Semaphore(resources.size(), fair);  // fair → FIFO, no starvation
        this.available = new ArrayBlockingQueue<>(resources.size());
        available.addAll(resources);
    }

    R acquire(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        if (!permits.tryAcquire(timeout, unit))                // bounds the wait
            throw new TimeoutException("pool exhausted");
        return available.poll();                               // a permit guarantees one is here
    }

    void release(R r) {
        if (r == null) return;
        available.offer(r);                                    // return resource FIRST
        permits.release();                                     // then the permit
    }
}
```

The ordering in `release` is load-bearing: you must return the resource to the queue **before** releasing the permit, otherwise a thread woken by the permit could `poll()` an empty queue (a permit would promise a resource that isn't back yet). The `tryAcquire(timeout)` is what makes this production-grade — an exhausted pool should reject or shed load with a clear `TimeoutException`, never block a request thread indefinitely (the classic cause of cascading thread pile-ups, Q84). The **fairness** flag matters when starvation would violate an SLA: an unfair semaphore can let a barging thread repeatedly jump the queue, indefinitely starving a waiter; fair mode enforces FIFO at a throughput cost (Q47). The honest caveat: a real connection pool (HikariCP) adds health checks, leak detection, validation, and eviction — but the *concurrency core* is exactly this semaphore-bounded checkout with timeout, which is why understanding it explains why every pool needs a `connectionTimeout` and a `maximumPoolSize`.

#### Q104. [Coding] Use `CyclicBarrier` to coordinate a multi-phase parallel simulation, and handle `BrokenBarrierException`.

`CyclicBarrier` shines for **iterative parallel algorithms** where N workers each compute a chunk of a step, then must all synchronize before the next step (cellular automata, particle simulation, iterative solvers). Unlike a latch it *resets* after each rendezvous, and its optional **barrier action** runs once, on the last-arriving thread, when all parties reach the barrier — the perfect place to aggregate or check a convergence/termination condition.

```java
import java.util.concurrent.*;

class GameOfLife {
    private final int n;
    private volatile boolean done = false;
    private final CyclicBarrier barrier;

    GameOfLife(int workers, Runnable mergeAndCheck) {
        this.n = workers;
        // barrier action runs ONCE when all workers arrive, before they proceed:
        this.barrier = new CyclicBarrier(workers, mergeAndCheck);
    }

    Runnable worker(int id) {
        return () -> {
            try {
                while (!done) {
                    computeMyRegion(id);          // phase 1: each worker computes its chunk
                    barrier.await();              // rendezvous; mergeAndCheck() runs here
                    // after await() returns, the barrier action has completed for this round
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (BrokenBarrierException e) {
                // another party failed/was interrupted/timed out → barrier is broken
                done = true;                      // tear down the whole cohort
            }
        };
    }
    void computeMyRegion(int id) { /* ... */ }
    void stop() { done = true; }
}
```

The subtlety unique to `CyclicBarrier` is the **broken-barrier** contract: a barrier is "all-or-nothing." If *any* waiting thread is interrupted, times out (`await(timeout)`), or the barrier action throws, the barrier **breaks** — every other thread waiting at `await()` is released with a `BrokenBarrierException` rather than proceeding, and the barrier becomes unusable until `reset()`. This is intentional: in a lock-step algorithm, if one worker can't continue, none of them should silently proceed on stale partial state. You must therefore handle `BrokenBarrierException` to tear down or restart the cohort, not ignore it. The barrier action gives you a clean **single-threaded "commit point"** between phases — only one thread runs it, with a happens-before edge from all workers' phase-1 writes — which is where you flip the front/back buffers, log progress, or set `done` when the simulation converges. Contrast with `Phaser` (Q62), which handles a *dynamic* party count; use `CyclicBarrier` when N is fixed for the algorithm's lifetime.

#### Q105. [Coding] Implement a non-blocking retry with exponential backoff and jitter for a flaky operation.

Retrying a failed call immediately and in lockstep across many threads causes a **thundering herd / retry storm** that can keep a recovering dependency down. The robust pattern is **exponential backoff with jitter**: each attempt waits a base delay that doubles, capped at a maximum, plus a *random* component so retries from different threads spread out in time rather than synchronizing into spikes.

```java
import java.util.concurrent.*;

class Retry {
    static <T> T withBackoff(Callable<T> op, int maxAttempts,
                             long baseMillis, long capMillis) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return op.call();
            } catch (Exception e) {
                last = e;
                if (!isRetryable(e) || attempt == maxAttempts - 1) throw e;
                long exp = Math.min(capMillis, baseMillis * (1L << attempt)); // 2^attempt, capped
                long jitter = ThreadLocalRandom.current().nextLong(exp + 1);  // full jitter
                try { Thread.sleep(jitter); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;                                  // honor cancellation during backoff
                }
            }
        }
        throw last;
    }
    static boolean isRetryable(Exception e) { return e instanceof java.io.IOException; }
}
```

The three design points an interviewer wants: (1) **jitter is not optional** — "full jitter" (`random(0, exp)`, per AWS's well-known analysis) or "equal jitter" decorrelates the retries of many concurrent clients, which is the whole reason the backoff helps the *server* recover, not just the client; pure exponential backoff without jitter still synchronizes herds. (2) **classify errors** — retry only *transient* failures (timeouts, 503s, `IOException`); retrying a deterministic error (400, auth failure, `IllegalArgumentException`) wastes attempts and can amplify load — `isRetryable` encodes that policy. (3) **respect interruption during the sleep** — a backoff `sleep` must propagate `InterruptedException` (restore the flag) so the operation is cancellable and so a shutdown isn't delayed by a long backoff. In production you'd combine this with a **circuit breaker** (Resilience4j) so that after a streak of failures you stop retrying entirely for a cooldown — backoff handles transient blips, the breaker handles sustained outages. For async pipelines, the same logic is expressed without blocking a thread via a scheduled `CompletableFuture` re-submission rather than `Thread.sleep`.

#### Q106. [Coding] Implement an `Exchanger`-based pipeline where two threads swap full/empty buffers (double buffering).

`Exchanger<V>` is the least-used JDK synchronizer but is exactly right for a **two-party buffer swap**: each thread arrives at `exchange(myBuffer)` and blocks until the *other* party also arrives, at which point they atomically swap objects and both proceed. The canonical use is **double buffering** — a producer fills buffer A while a consumer drains buffer B, then they swap, so neither ever waits on allocation and there's no shared queue to lock.

```java
import java.util.concurrent.*;
import java.util.*;

class DoubleBufferPipeline {
    private final Exchanger<List<String>> exchanger = new Exchanger<>();

    Runnable filler() {
        return () -> {
            List<String> buf = new ArrayList<>(1024);
            try {
                while (true) {
                    while (buf.size() < 1024) buf.add(readOne());  // fill private buffer
                    buf = exchanger.exchange(buf);                 // swap full → get empty
                    buf.clear();                                   // reuse the one we got back
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
    }

    Runnable drainer() {
        return () -> {
            List<String> buf = new ArrayList<>(1024);
            try {
                while (true) {
                    buf = exchanger.exchange(buf);                 // swap empty → get full
                    for (String s : buf) process(s);              // drain private buffer
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
    }
    String readOne() { return "x"; }
    void process(String s) { }
}
```

The reason `Exchanger` beats a `BlockingQueue` here is **zero per-element handoff and buffer reuse**: rather than enqueuing/dequeuing 1024 items one at a time (1024 lock operations and queue-node allocations), the two threads exchange ownership of an entire pre-allocated buffer in a single rendezvous, then each works on its private buffer with *no synchronization at all* until the next swap. This is mechanical-sympathy-friendly — large contiguous work between rare synchronization points. The constraints to call out: `Exchanger` is strictly **two-party** (it pairs threads; a third caller waits for a fourth), so it doesn't generalize to N producers/consumers — use a queue for that. You must also handle the case where one side stalls: `exchange(v, timeout, unit)` lets a thread give up rather than block forever if its partner died. The happens-before guarantee is clean: actions before `exchange` in each thread happen-before the partner's actions after it returns, so the swapped buffer's contents are fully visible without extra fences.

### 🟠 Advanced — extended

#### Q106b. [Coding] Solve the dining philosophers problem without deadlock or starvation.

Dining philosophers is the canonical lock-ordering exercise: five philosophers around a table, one fork between each pair, each needs *both* adjacent forks to eat. The naive "pick up left then right" deadlocks if all five grab their left fork simultaneously (circular wait — Coffman condition #4, Q16). The cleanest deadlock-free fix is **resource ordering**: number the forks and always acquire the lower-numbered fork first, which breaks the cycle because at least one philosopher reaches for forks in the opposite order.

```java
import java.util.concurrent.locks.*;
import java.util.concurrent.*;

class DiningPhilosophers {
    private final ReentrantLock[] forks;
    DiningPhilosophers(int n) {
        forks = new ReentrantLock[n];
        for (int i = 0; i < n; i++) forks[i] = new ReentrantLock();
    }

    void dine(int id) throws InterruptedException {
        int left = id, right = (id + 1) % forks.length;
        int first = Math.min(left, right), second = Math.max(left, right);  // global order
        forks[first].lock();
        try {
            forks[second].lock();
            try { eat(id); }
            finally { forks[second].unlock(); }
        } finally { forks[first].unlock(); }
    }

    // Alternative: tryLock with backoff also prevents deadlock and avoids starvation better.
    void dineTryLock(int id) throws InterruptedException {
        int left = id, right = (id + 1) % forks.length;
        while (true) {
            forks[left].lock();
            if (forks[right].tryLock()) {                 // don't block holding left
                try { eat(id); } finally { forks[right].unlock(); forks[left].unlock(); }
                return;
            }
            forks[left].unlock();                          // release left → break hold-and-wait
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5)); // jitter avoids livelock
        }
    }
    void eat(int id) { /* ... */ }
}
```

The two solutions break *different* Coffman conditions, which is the teaching point. **Resource ordering** (`dine`) eliminates *circular wait* — with a global total order on forks, no cycle of "A waits for B waits for ... waits for A" can form, so deadlock is impossible; it's simple and starvation is statistically rare but not formally guaranteed. **`tryLock` + release + backoff** (`dineTryLock`) eliminates *hold-and-wait* — a philosopher never holds one fork while blocking on the other; if it can't get the second, it drops the first and retries. But naive retry risks **livelock** (all release and re-grab in lockstep forever, Q18), which is exactly why the random backoff is essential to break the symmetry. For guaranteed fairness you'd add an arbiter (a `Semaphore` permitting at most N-1 philosophers to *attempt* eating at once, which provably prevents both deadlock and starvation). The interview signal is naming *which* condition each technique attacks rather than just "it works."

#### Q107. [Coding] Build a parallel merge sort with `ForkJoinPool` and explain when it beats sequential sort.

Merge sort is naturally recursive and divide-and-conquer, mapping directly onto `RecursiveAction`: split the range, fork the two halves, join, then merge. The key engineering decision is the **sequential cutoff threshold** — below it, recursing in parallel costs more in task-scheduling overhead than it saves, so you fall back to a fast sequential sort.

```java
import java.util.concurrent.*;
import java.util.*;

class ParallelMergeSort extends RecursiveAction {
    private static final int THRESHOLD = 1 << 13;   // ~8192: tune empirically
    private final int[] a, tmp; private final int lo, hi;

    ParallelMergeSort(int[] a, int[] tmp, int lo, int hi) {
        this.a = a; this.tmp = tmp; this.lo = lo; this.hi = hi;
    }

    protected void compute() {
        if (hi - lo <= THRESHOLD) { Arrays.sort(a, lo, hi); return; }  // base case
        int mid = (lo + hi) >>> 1;
        ParallelMergeSort left  = new ParallelMergeSort(a, tmp, lo, mid);
        ParallelMergeSort right = new ParallelMergeSort(a, tmp, mid, hi);
        invokeAll(left, right);                       // fork both, join both (balanced split)
        merge(mid);
    }

    private void merge(int mid) {
        System.arraycopy(a, lo, tmp, lo, hi - lo);
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) a[k++] = (tmp[i] <= tmp[j]) ? tmp[i++] : tmp[j++];
        while (i < mid) a[k++] = tmp[i++];
        while (j < hi)  a[k++] = tmp[j++];
    }
    static void sort(int[] a) {
        ForkJoinPool.commonPool().invoke(new ParallelMergeSort(a, new int[a.length], 0, a.length));
    }
}
```

Why `invokeAll(left, right)` rather than the `left.fork(); right.compute(); left.join()` idiom of Q13? For a *balanced* split where both halves are equal-cost, `invokeAll` is clean and lets the framework optimize; the "fork one, compute the other" idiom matters more for unbalanced or single-tail recursion. **Complexity:** O(n log n) total work, O(log n) critical-path depth, so ideal speedup ≈ number of cores. The honest trade-offs: (1) parallel sort only wins for **large arrays** — for small inputs the fork/join and merge-buffer overhead makes it *slower* than `Arrays.sort`, which is why the threshold exists and why `Arrays.parallelSort` (which uses exactly this approach internally) falls back to sequential below ~8192 elements; (2) it needs **O(n) extra memory** for the merge buffer (the `tmp` array), unlike an in-place quicksort; (3) it runs on the **common pool**, so the Q23/Q80 caveat applies — don't mix it with blocking work. In practice you'd just call `Arrays.parallelSort(a)`; rolling your own is the *interview* exercise that demonstrates you understand the cutoff and the work/depth analysis.

#### Q108. [Coding] Implement a custom AQS-based synchronizer: a one-shot `Latch` and a simple non-reentrant `Mutex`.

The interview test of "do you really understand AQS" (Q46) is to write a small synchronizer with it. AQS does the heavy lifting (queueing, parking, fairness, cancellation); you just define what the `volatile int state` *means* and how to transition it via `tryAcquire`/`tryRelease` (exclusive) or `tryAcquireShared`/`tryReleaseShared` (shared). A one-shot gate that releases *all* waiters is **shared mode**; a mutex is **exclusive mode**.

```java
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.*;

// 1) A one-shot latch: state 0 = closed (block), 1 = open (let everyone through).
class OneShotLatch {
    private final Sync sync = new Sync();
    private static final class Sync extends AbstractQueuedSynchronizer {
        protected int tryAcquireShared(int ignored) {
            return getState() == 1 ? 1 : -1;          // >=0 means "proceed"; <0 means "block"
        }
        protected boolean tryReleaseShared(int ignored) {
            setState(1);                              // open the gate
            return true;                              // true → unpark ALL queued waiters
        }
    }
    public void await()  { sync.acquireSharedInterruptibly(1); }  // or acquireShared
    public void open()   { sync.releaseShared(1); }
}

// 2) A non-reentrant mutex: state 0 = free, 1 = held.
class Mutex {
    private final Sync sync = new Sync();
    private static final class Sync extends AbstractQueuedSynchronizer {
        protected boolean tryAcquire(int ignored) {
            return compareAndSetState(0, 1);          // CAS free → held; fail if held
        }
        protected boolean tryRelease(int ignored) {
            setState(0);                              // free it; AQS unparks one successor
            return true;
        }
        protected boolean isHeldExclusively() { return getState() == 1; }
    }
    public void lock()   { sync.acquire(1); }
    public void unlock() { sync.release(1); }
    public boolean tryLock() { return sync.tryAcquire(1); }
}
```

The design insight is **shared vs exclusive mode maps to the propagation behavior**. `tryReleaseShared` returning `true` causes AQS to *propagate* the release down the queue, waking **all** waiting threads — which is exactly the latch's "open the gate for everyone" semantics. Exclusive mode (`tryRelease`) wakes only the **single** next successor — mutual exclusion. Note the latch is **idempotent and monotonic**: once `state` flips to 1 it never goes back, so late `await()` callers see `tryAcquireShared` return ≥0 immediately and don't block (this is precisely `CountDownLatch` with count 1). For the mutex, using `acquireSharedInterruptibly`/`acquireInterruptibly` gives you cancellable acquisition for free — AQS handles the interrupt-during-park logic. The payoff this demonstrates: building a correct, fair, interruptible, queue-backed synchronizer is ~15 lines because all the genuinely hard concurrency (CLH queue management, parking, cancellation races) lives once in AQS — which is why you should extend AQS rather than hand-roll `wait/notify` for any non-trivial coordination primitive.

#### Q109. [Coding] Implement a thread-safe, bounded LRU cache and discuss why `LinkedHashMap` + lock vs `ConcurrentHashMap` trade off.

An LRU cache must evict the least-recently-used entry on overflow, which requires tracking access *order* — inherently stateful and mutated on *every read*, making it surprisingly hard to make concurrent. The simplest correct approach wraps `LinkedHashMap` (access-ordered) in a lock; the higher-throughput approach accepts approximate LRU.

```java
import java.util.*;
import java.util.concurrent.locks.*;

class LruCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;
    private final ReentrantLock lock = new ReentrantLock();

    LruCache(int capacity) {
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(capacity, 0.75f, true /* accessOrder */) {
            protected boolean removeEldestEntry(Map.Entry<K, V> e) { return size() > LruCache.this.capacity; }
        };
    }
    V get(K k) { lock.lock(); try { return map.get(k); } finally { lock.unlock(); } }
    void put(K k, V v) { lock.lock(); try { map.put(k, v); } finally { lock.unlock(); } }
}
```

The critical subtlety: with `accessOrder=true`, **`get()` structurally modifies the map** (it moves the accessed entry to the tail to mark it most-recently-used). That means you cannot use a `ReadWriteLock` to let reads run concurrently — every `get` is really a *write* to the ordering list, so reads must take the *exclusive* lock too. This is why a naive concurrent LRU is a global-lock bottleneck: every access serializes. The escalation path: (1) **shard** the cache into N independent `LruCache` segments keyed by `hash(key) % N` so accesses to different shards run in parallel (each shard keeps strict LRU; global LRU is approximated) — this is roughly what Guava `Cache`/Caffeine do at a coarse level; (2) use **`ConcurrentHashMap` + approximate-LRU metadata** (access timestamps or a sampling-based / second-chance eviction) so reads stay lock-free and only eviction coordinates — Caffeine's W-TinyLFU does this with read/write ring buffers that batch the bookkeeping off the hot path. The honest interview answer: for a small per-thread or low-contention cache, `LinkedHashMap` + lock is correct and clear; for a hot shared cache, **don't hand-roll it — use Caffeine**, because exact LRU and high concurrency are fundamentally in tension and Caffeine resolves it with a near-optimal hit rate and lock-free reads. **Time:** O(1) get/put under the lock; the question is contention, not asymptotics.

#### Q110. [Coding] Implement a fixed-rate concurrent task scheduler that runs N workers pulling from a `DelayQueue`/`PriorityBlockingQueue`.

When tasks must run at specific future times or in priority order, an ordinary FIFO `BlockingQueue` isn't enough — you need a queue ordered by a key. `PriorityBlockingQueue` orders by a comparator (unbounded, blocking on empty); `DelayQueue` is a specialization where each element has a `getDelay()` and `take()` only returns an element once its delay has elapsed, blocking otherwise — exactly a "run at time T" scheduler primitive.

```java
import java.util.concurrent.*;

class DelayScheduler {
    record Job(Runnable task, long runAtNanos) implements Delayed {
        public long getDelay(TimeUnit u) {
            return u.convert(runAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }
        public int compareTo(Delayed o) {                  // order by due time
            return Long.compare(runAtNanos, ((Job) o).runAtNanos);
        }
    }
    private final DelayQueue<Job> queue = new DelayQueue<>();
    private final ExecutorService workers;
    private volatile boolean running = true;

    DelayScheduler(int nWorkers) {
        workers = Executors.newFixedThreadPool(nWorkers);
        for (int i = 0; i < nWorkers; i++) workers.submit(this::loop);
    }
    void schedule(Runnable task, long delay, TimeUnit unit) {
        queue.put(new Job(task, System.nanoTime() + unit.toNanos(delay)));
    }
    private void loop() {
        while (running) {
            try {
                Job job = queue.take();                    // blocks until a job is DUE
                job.task().run();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (Exception biz) { log(biz); }            // isolate task failures (Q72)
        }
    }
    void shutdown() { running = false; workers.shutdownNow(); }
    void log(Exception e) { }
}
```

`DelayQueue` does the time-based gating for you: `take()` internally uses a leader-follower optimization — one waiting consumer becomes the "leader" and parks until exactly the head element's delay expires, while other consumers park indefinitely, so you don't get N threads all spin-waiting on timers (an elegant detail worth mentioning). This pulls multiple workers off one time-ordered queue, giving concurrency that `ScheduledThreadPoolExecutor` (Q72) also offers but with full control over the worker model and the crucial **per-task exception isolation** that raw `scheduleAtFixedRate` lacks (one throw won't kill the scheduler). Use `PriorityBlockingQueue` instead when ordering is by *priority* rather than *time* (note it's unbounded — a flood of high-priority jobs can OOM, so add an explicit size guard). The trade-off vs `ScheduledThreadPoolExecutor`: that class is battle-tested and handles fixed-rate/fixed-delay semantics; you'd hand-roll a `DelayQueue` scheduler only when you need custom worker behavior (priority + delay combined, dynamic worker scaling, or per-job error routing).

#### Q111. [Coding] Implement timeout-with-cancellation on a `CompletableFuture` that actually frees the underlying resource.

`orTimeout` (Q11b) completes the *future* exceptionally after a deadline but does **not** interrupt or cancel the work still running underneath — the thread keeps executing, holding its connection/CPU, which is a resource leak under load. Doing timeout *properly* means propagating cancellation to the task so the real work stops. Here's a pattern that ties a `Future`'s cancellation to the timeout.

```java
import java.util.concurrent.*;

class CancellingTimeout {
    private final ScheduledExecutorService timer =
        Executors.newSingleThreadScheduledExecutor();

    <T> CompletableFuture<T> callWithTimeout(ExecutorService pool, Callable<T> task,
                                             long timeout, TimeUnit unit) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> work = pool.submit(() -> {
            try { result.complete(task.call()); }
            catch (Throwable t) { result.completeExceptionally(t); }
        });
        // schedule a deadline that cancels BOTH the future and the running task
        ScheduledFuture<?> killer = timer.schedule(() -> {
            if (result.completeExceptionally(new TimeoutException())) {
                work.cancel(true);                 // mayInterruptIfRunning=true → interrupts worker
            }
        }, timeout, unit);
        result.whenComplete((v, ex) -> killer.cancel(false));  // success → cancel the deadline timer
        return result;
    }
}
```

The point this drives home is that **cancellation only works if the task is interruptible** (Q36). `work.cancel(true)` sets the worker thread's interrupt flag; if `task.call()` is doing an interruptible blocking call (`Socket` read with a configured timeout, `BlockingQueue.take`, `Thread.sleep`), it throws `InterruptedException` and unwinds, freeing the resource. If the task is a tight CPU loop that never checks `isInterrupted()`, `cancel(true)` does nothing and the work runs to completion despite the timeout — interruption is cooperative, not preemptive. The second subtlety is **avoiding the double-completion race**: `completeExceptionally` returns `false` if the future already completed normally, so we only cancel the work when *we* won the timeout race; and `whenComplete` cancels the scheduled killer on success so it doesn't fire spuriously and leak a timer task. In 2026 with virtual threads, the cleaner answer is often `StructuredTaskScope` with a deadline (`joinUntil(Instant)`), which cancels all forked subtasks via interruption automatically when the deadline passes (Q25) — but understanding the manual `Future.cancel(true)` + interruptible-task requirement is what proves you know *why* `orTimeout` alone leaks resources.

#### Q112. [Coding] Write a `CompletableFuture`-based combinator that returns the first *successful* result and ignores failures (hedged requests).

`CompletableFuture.anyOf` returns the first stage to *complete* — but that includes the first to **fail**, which is wrong for "hedged requests" / "racing replicas" where you want the first *success* and want to tolerate some failures. You must build a combinator that completes on the first success but only fails if *all* inputs fail. This is the asynchronous analog of `StructuredTaskScope.ShutdownOnSuccess` (Q25).

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

class Combinators {
    static <T> CompletableFuture<T> firstSuccessOf(List<CompletableFuture<T>> futures) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicInteger remaining = new AtomicInteger(futures.size());
        for (CompletableFuture<T> f : futures) {
            f.whenComplete((value, error) -> {
                if (error == null) {
                    result.complete(value);                    // first success wins (idempotent)
                } else if (remaining.decrementAndGet() == 0) {
                    result.completeExceptionally(error);       // all failed → propagate the last
                }
            });
        }
        return result;
    }
}
```

The correctness hinges on two atomic primitives doing the coordination. `result.complete(value)` is itself a **CAS-based one-shot** (Q65) — many futures may succeed, but only the first `complete` "takes," and subsequent calls return `false` and are silently ignored, so the result is exactly the first success with no race. The `AtomicInteger remaining` counts failures so that we only complete *exceptionally* when the **last** outstanding future fails — meaning we never give up early just because one replica errored. This is precisely the pattern behind tail-latency reduction via **hedging**: fire the same request to two or three backends, take whichever responds first and ignore the slowpokes/failures, trading extra load for a tighter p99. The refinements a senior would add: (1) **cancel the losers** — once `result` completes, loop and `f.cancel(true)` the others to stop wasting their resources (with the interruptibility caveat of Q111); (2) **bound the hedging** — only send the second request after a delay (e.g., the p50 latency) rather than always racing N, so you don't double your backend load for the common fast case ("deferred hedging"). The off-the-shelf equivalent on Loom is `StructuredTaskScope.ShutdownOnSuccess`, which does the first-success-cancel-rest natively and with structured lifetime guarantees.

### 🔴 Expert — extended

#### Q113. [Coding] Implement a lock-free Michael-Scott concurrent queue (MPMC) with CAS and explain the tail-lag invariant.

The Michael-Scott queue is the algorithm behind `ConcurrentLinkedQueue` — a lock-free FIFO using a singly linked list with separate `head` and `tail` `AtomicReference`s and a **dummy sentinel node**. The genius is that `tail` is allowed to "lag" one node behind the true end, and every operation *helps* advance it, so no single CAS has to atomically update two pointers.

```java
import java.util.concurrent.atomic.*;

class MSQueue<T> {
    private static final class Node<T> {
        final T item; final AtomicReference<Node<T>> next = new AtomicReference<>();
        Node(T item) { this.item = item; }
    }
    private final AtomicReference<Node<T>> head, tail;
    MSQueue() { Node<T> dummy = new Node<>(null); head = new AtomicReference<>(dummy); tail = new AtomicReference<>(dummy); }

    public void enqueue(T item) {
        Node<T> n = new Node<>(item);
        while (true) {
            Node<T> last = tail.get(), next = last.next.get();
            if (last == tail.get()) {                         // tail still consistent?
                if (next == null) {                           // tail really points to last node
                    if (last.next.compareAndSet(null, n)) {   // 1) link the new node
                        tail.compareAndSet(last, n);          // 2) try to swing tail (may fail → helped later)
                        return;
                    }
                } else {
                    tail.compareAndSet(last, next);           // tail lagged → help advance it
                }
            }
        }
    }

    public T dequeue() {
        while (true) {
            Node<T> first = head.get(), last = tail.get(), next = first.next.get();
            if (first == head.get()) {
                if (first == last) {                          // queue empty OR tail lagging
                    if (next == null) return null;            // truly empty
                    tail.compareAndSet(last, next);           // help advance lagging tail
                } else {
                    T item = next.item;                       // read BEFORE the CAS
                    if (head.compareAndSet(first, next)) return item;  // swing head past dummy
                }
            }
        }
    }
}
```

The crucial invariant is the **two-step enqueue**: linking the new node (`last.next.compareAndSet(null, n)`) and swinging the tail (`tail.compareAndSet(last, n)`) are *separate* CASes, so between them the queue is in a legal "tail lags by one" state. Any thread that observes `tail.next != null` knows the tail is stale and **helps** by CAS-ing it forward before proceeding — this *helping* is what makes the algorithm lock-free (a thread stalled between its two steps can't block others; they fix the tail themselves). The `if (last == tail.get())` re-reads are **consistency checks** guarding against the value changing mid-operation. On dequeue, the **sentinel** node means `head` always points to a dummy whose `next` is the real front, which avoids the empty/one-element corner cases that plague head==tail logic. ABA (Q21/Q95) is handled in Java by the **GC**: a dequeued node isn't reused while any thread still references it, so a CAS can't be fooled by a recycled-address collision — in C/C++ you'd need hazard pointers or tagged pointers here. This is the textbook example of why you reach for `ConcurrentLinkedQueue` rather than writing this yourself: the algorithm is correct but its memory-ordering details (the read-before-CAS, the helping, the consistency re-reads) are exactly where hand-rolled versions get subtle, verification-requiring bugs (Q32).

#### Q114. [Coding] Use `VarHandle` to implement a lock-free single-producer/single-consumer ring buffer with release/acquire semantics.

For the highest-performance SPSC case, full `volatile` is overkill — you only need **release on publish, acquire on consume** (Q28/Q40), which on weakly-ordered hardware (ARM) saves the expensive bidirectional `StoreLoad` fence. `VarHandle` (Java 9) exposes exactly these modes. An SPSC ring buffer needs no CAS at all: one producer owns the write index, one consumer owns the read index, and they synchronize only through the published indices.

```java
import java.lang.invoke.*;

class SpscRingBuffer<T> {
    private final Object[] buf;
    private final int mask;                              // capacity is a power of two
    @SuppressWarnings("unused") private volatile long writeIdx = 0, readIdx = 0;

    private static final VarHandle WRITE, READ;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            WRITE = l.findVarHandle(SpscRingBuffer.class, "writeIdx", long.class);
            READ  = l.findVarHandle(SpscRingBuffer.class, "readIdx",  long.class);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    SpscRingBuffer(int capacity) {                       // capacity must be power of two
        buf = new Object[capacity]; mask = capacity - 1;
    }

    public boolean offer(T item) {                       // SINGLE producer only
        long w = (long) WRITE.get(this);                 // plain read: producer owns writeIdx
        long r = (long) READ.getAcquire(this);           // acquire: see consumer's progress
        if (w - r >= buf.length) return false;           // full
        buf[(int) (w & mask)] = item;
        WRITE.setRelease(this, w + 1);                   // release: publish item before index
        return true;
    }

    @SuppressWarnings("unchecked")
    public T poll() {                                    // SINGLE consumer only
        long r = (long) READ.get(this);                  // plain read: consumer owns readIdx
        long w = (long) WRITE.getAcquire(this);          // acquire: see producer's progress
        if (r >= w) return null;                         // empty
        T item = (T) buf[(int) (r & mask)];
        buf[(int) (r & mask)] = null;                    // help GC
        READ.setRelease(this, r + 1);                    // release: publish consumption
        return item;
    }
}
```

The performance logic: because there is exactly one producer and one consumer, each *owns* its own index and can read it with a **plain** (unordered) load — no synchronization needed for your own variable. The cross-thread coordination is minimal: the producer publishes the item into the array slot and *then* does `setRelease(writeIdx)`, which guarantees (via a one-directional StoreStore fence) that the slot write is visible to any consumer that does `getAcquire(writeIdx)` and sees the new value — the consumer can never read a slot before the item landed in it. Symmetrically, the consumer's `setRelease(readIdx)` tells the producer a slot is free. This is strictly cheaper than `volatile` on both indices because it omits the StoreLoad barrier that volatile writes require (Q40) — on x86 it's the same cost (TSO already gives release/acquire for free), but on ARM/Graviton it eliminates real `dmb` fences, which is why the Disruptor and JCTools operate at this level. The hard caveat: this is **provably correct only for exactly one producer and one consumer** — add a second producer and the plain read of `writeIdx` races, silently corrupting the buffer. For MPMC you need the Vyukov per-slot-sequence algorithm (Q32) with CAS. To eliminate **false sharing** (Q20) you'd also pad `writeIdx` and `readIdx` onto separate cache lines (`@Contended` or manual long padding), since the producer and consumer hammering adjacent fields would otherwise ping-pong the cache line.

#### Q115. [Coding] Implement a `Phaser`-coordinated multi-stage pipeline with dynamic worker registration.

`Phaser` (Q62) is the right tool when the number of participants **changes between phases** — something `CyclicBarrier` and `CountDownLatch` cannot express. A concrete scenario: a multi-stage data pipeline where each stage spins up a variable number of workers, all stages must synchronize at phase boundaries, and the pipeline terminates when a global condition holds (via `onAdvance`).

```java
import java.util.concurrent.*;

class PhasedPipeline {
    private final Phaser phaser;

    PhasedPipeline(int maxPhases) {
        // onAdvance returns true to TERMINATE the phaser (no more phases)
        phaser = new Phaser(1) {                          // register the controller itself
            protected boolean onAdvance(int phase, int registeredParties) {
                System.out.println("phase " + phase + " complete, parties=" + registeredParties);
                return phase >= maxPhases - 1 || registeredParties == 0;
            }
        };
    }

    void addWorker(Runnable stageWork) {
        phaser.register();                                // dynamically join (parties + 1)
        new Thread(() -> {
            try {
                while (!phaser.isTerminated()) {
                    stageWork.run();                      // do this phase's work
                    phaser.arriveAndAwaitAdvance();       // rendezvous; block until all arrive
                }
            } finally {
                phaser.arriveAndDeregister();             // leave cleanly (parties - 1)
            }
        }).start();
    }

    void run() {
        // controller drives phases; each loop = one synchronized round
        while (!phaser.isTerminated()) {
            phaser.arriveAndAwaitAdvance();               // controller participates in each phase
        }
    }
    void close() { phaser.arriveAndDeregister(); }        // controller leaves
}
```

The mechanics that make `Phaser` uniquely suited: `register()` and `arriveAndDeregister()` let parties **join and leave between phases**, so a stage that finishes its work can deregister and the phaser advances on the *remaining* parties — impossible with a fixed-N `CyclicBarrier`. The overridable **`onAdvance(phase, parties)`** hook is the single-threaded "commit point" run by the last arriving party (like the barrier action) but it also *controls termination*: returning `true` terminates the phaser, after which `arriveAndAwaitAdvance` returns immediately and `isTerminated()` is true — giving a clean, race-free shutdown signal to all workers. Registering the controller (`new Phaser(1)`) ensures the phaser doesn't auto-terminate the instant workers transiently hit zero during setup. Two expert notes: (1) for **thousands of parties**, a flat phaser makes every arrival CAS the same state word (contention, Q58) — use a **tiered phaser** (`new Phaser(parent)`) to build a tree that fans out the contention; (2) `arrive()` (non-blocking) vs `arriveAndAwaitAdvance()` (blocking) lets a party signal progress without waiting, enabling fire-and-forget phase advancement. The cost is conceptual complexity — for a fixed-N fixed-round rendezvous, `CyclicBarrier` is clearer; reach for `Phaser` specifically when party count is dynamic or you need tiered scalability.

#### Q116. [Coding] Build a concurrent web-crawler skeleton with bounded concurrency, dedup, and clean termination — using `Phaser` or `CompletableFuture`.

The crawler is a classic "recursive work generates more work" problem where the hard parts are (1) **deduplication** so a URL is fetched once, (2) **bounded concurrency** so you don't open 100k sockets, and (3) **termination detection** — knowing when *all* transitively-discovered work is done, which is subtle because new work appears dynamically.

```java
import java.util.concurrent.*;
import java.util.*;

class Crawler {
    private final Set<String> seen = ConcurrentHashMap.newKeySet();   // lock-free dedup
    private final Semaphore concurrency = new Semaphore(50);          // bound in-flight fetches
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Phaser phaser = new Phaser(1);                      // 1 = the seed registration

    void crawl(String seed) {
        submit(seed);
        phaser.arriveAndAwaitAdvance();   // wait until EVERY discovered task has completed
        pool.shutdown();
    }

    private void submit(String url) {
        if (!seen.add(url)) return;                    // already seen → skip (atomic add+test)
        phaser.register();                             // count this unit of work
        pool.execute(() -> {
            try {
                concurrency.acquire();                 // bound concurrent fetches
                try {
                    for (String link : fetchAndExtract(url)) submit(link);  // discover → recurse
                } finally { concurrency.release(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                phaser.arriveAndDeregister();          // this unit done; may trigger advance
            }
        });
    }
    List<String> fetchAndExtract(String url) { return List.of(); }
}
```

The elegance is that **`Phaser` solves termination detection** for dynamically-spawned work: every `submit` that claims a new URL `register()`s a party, and every completed task `arriveAndDeregister()`s. The seed registration (`new Phaser(1)`) keeps the count from hitting zero prematurely during startup; once the seed and all transitively discovered tasks have deregistered, the party count reaches zero, the phaser advances, and `arriveAndAwaitAdvance()` returns — *that's* the signal that the entire crawl (however deeply it branched) is complete. This is far cleaner than trying to track a counter manually and racing on "is it really done or is another task about to spawn more?" The **`ConcurrentHashMap.newKeySet().add()`** is the dedup primitive: `add` returns `false` if the URL was already present, atomically, so exactly one task ever processes a given URL even under concurrent discovery — no separate check-then-act race. The **`Semaphore(50)`** decouples the *number of tasks* (unbounded, one per URL) from the *number of simultaneous fetches* (bounded), which is essential politeness/resource control — with virtual threads you can have a million parked tasks, but you still must not hammer the network with a million live connections. The alternative formulation uses `CompletableFuture` recursion with `allOf` to detect completion, but that's harder to get right for unbounded fan-out; the phaser register/deregister pattern is the idiomatic termination detector for this shape of problem.

#### Q117. [Behavioral] Tell me about a time you led the resolution of a severe, hard-to-reproduce concurrency incident across teams. (Staff-level, STAR)

This question probes staff-level scope: not just *fixing a bug* but driving an organization-wide response, influencing without authority, and changing the system so the class of failure can't recur. Strong answers use STAR and emphasize leadership, communication under pressure, and systemic prevention — not just technical cleverness.

> **Situation** — *"Our payments platform began intermittently double-charging a tiny fraction of transactions during peak traffic — roughly one in fifty thousand, only under load, never reproducible in staging. It was a Sev-1: customer-facing financial harm, regulatory exposure, and three teams (payments, ledger, and the shared idempotency service) each convinced the bug was in someone else's code."*
>
> **Task** — *"As the staff engineer on payments I was made incident commander. My job was both technical — find the root cause in a distributed, timing-dependent failure — and organizational — stop the finger-pointing, coordinate the three teams, and keep leadership and compliance informed with honest status while we had no clear answer."*
>
> **Action** — *"I first stopped the bleeding: we added a reconciliation job to detect and auto-refund duplicates within minutes, which bought us time and contained customer impact while we investigated. Then I ran the technical investigation as a disciplined hunt rather than guesswork. I instrumented the idempotency check with distributed tracing and high-resolution timestamps and captured thread dumps and ledger-service heap dumps during a reproduction we forced with a load test that replayed production traffic at 3x. The data showed a **TOCTOU race** (Q34): the idempotency service did a check-then-act — read 'has this key been processed?', then later write 'mark processed' — with a non-atomic gap, so two concurrent retries of the same payment both read 'not processed' and both proceeded. It only surfaced under load because the gap had to align with a concurrent retry. I made the check-and-set atomic using a conditional write (a CAS-style `INSERT ... ON CONFLICT DO NOTHING` on the idempotency key, the database analog of `putIfAbsent`/`compareAndSet`, Q65), so exactly one of the racing requests wins the key and the other is rejected. I drove a blameless post-mortem across all three teams focused on the systemic gap, not the individual who wrote the check-then-act."*
>
> **Result** — *"The fix eliminated the duplicates entirely — zero recurrences in the following year. More importantly, I turned the incident into systemic prevention: we (1) made 'idempotency keys must be enforced by an atomic conditional write, never check-then-act' a documented platform standard and added a lint/architecture-review gate for it; (2) built the forced-load 'race reproduction' harness into CI for the idempotency service so this class of bug fails a test before shipping; and (3) ran a short internal talk on TOCTOU and atomic check-and-act so the lesson spread beyond the three teams. The meta-lesson I emphasize is that a one-in-fifty-thousand concurrency bug is a *design* defect, not a fluke — the fix is to make the incorrect pattern impossible to write, not to patch the one instance."*

The interviewer is assessing: incident leadership and communication under ambiguity (the containment-first instinct, honest status to compliance), rigorous data-driven debugging of a non-deterministic failure rather than speculation, the depth to recognize TOCTOU and the atomic-CAS fix, and — the staff differentiator — converting a single incident into standards, tooling, and cultural change so the *class* of bug is prevented across the org. Weak answers stop at "I found the race and fixed it"; staff answers show influence across teams and a durable systemic outcome.

#### Q118. [Theory] Explain how `synchronized` and `ReentrantLock` interact with virtual-thread pinning in JDK 21 vs JDK 24+ (JEP 491), and how you'd audit a codebase.

Pinning (Q24/Q55) is the central performance gotcha of virtual threads, and the answer has a sharp **version dependency** that signals whether you're current. The mounting model: a virtual thread runs on a platform "carrier" thread and *unmounts* when it blocks, freeing the carrier. The problem in **JDK 21–23** was that the implementation could not unmount a virtual thread while it was inside a `synchronized` block/method — the monitor was tied to the carrier — so a virtual thread that **blocked on I/O while holding a monitor** would *pin* the carrier, and enough simultaneous pins would exhaust the small carrier pool (≈ #cores) and collapse throughput even though CPU was idle.

```java
// JDK 21–23: this PINS the carrier for the duration of the blocking call.
synchronized (lock) {
    response = httpClient.send(request, ofString());   // blocking I/O under a monitor → pin
}

// JDK 21–23 mitigation: use ReentrantLock, which DOES unmount cleanly.
lock.lock();
try { response = httpClient.send(request, ofString()); }  // VT unmounts here, frees carrier
finally { lock.unlock(); }
```

**JEP 491 (JDK 24, 2025)** changed the runtime so that `synchronized` blocking **no longer pins** — monitors were reworked so a virtual thread can unmount while owning a monitor. The practical upshot for 2026: on JDK 24+, the long-standing advice "replace `synchronized` around I/O with `ReentrantLock`" is **largely obsolete** for the pinning reason (you might still prefer `ReentrantLock` for its features, but not to avoid pinning). What still pins even on JDK 24+ is blocking inside a **native frame** (a JNI call or certain `Object.wait` paths in older library code) — those remain unmount-blockers because the JVM can't relocate a native stack. The audit procedure: (1) confirm the **JDK version** first — JDK ≥ 24 eliminates the dominant `synchronized` cause for free; (2) on older JDKs, grep for `synchronized` on hot/IO paths and convert to `ReentrantLock`; (3) regardless of version, run the **`jdk.VirtualThreadPinned` JFR event** under load (Q55) — it fires with a stack trace whenever a VT blocks while pinned, telling you the *actual* offending frames rather than guessing; (4) check for the anti-patterns that aren't pinning but look similar: pooling virtual threads, or running CPU-bound work on them (carrier starvation). The senior framing: "pinning is a real but shrinking concern — name the JDK version, measure with the JFR event, and don't blindly refactor `synchronized` on JDK 24+ where it no longer helps."

#### Q119. [Theory] What are the concurrency correctness and performance pitfalls of Java streams, and when is `parallel()` actually safe and beneficial?

Parallel streams (Q23/Q80) are the most-misused concurrency feature because the API makes `.parallel()` look like a free speedup, while correctness and performance both have non-obvious preconditions. The **correctness** rule: a parallel stream's lambdas must be **stateless, non-interfering, and associative** for reductions. *Stateless* means the lambda's result can't depend on mutable state that changes during execution; *non-interfering* means you must not mutate the *source* collection during the stream; and reductions/collectors must be *associative* because the framework splits, processes sub-ranges in arbitrary order on different threads, and combines — a non-associative combiner (like subtraction, or a `collect` whose combiner isn't a proper merge) yields nondeterministic wrong answers.

```java
// BROKEN: shared mutable state mutated from parallel threads → lost updates / corruption
List<Integer> out = new ArrayList<>();
nums.parallelStream().forEach(out::add);          // ArrayList isn't thread-safe → data race

// CORRECT: use a collector (the framework handles thread-safe accumulation + merge)
List<Integer> ok = nums.parallelStream().collect(Collectors.toList());

// BROKEN: stateful lambda — order-dependent, undefined under parallelism
int[] running = {0};
nums.parallelStream().map(n -> running[0] += n);  // race + meaningless result
```

The classic correctness bug is doing a **side-effecting `forEach`** into a non-thread-safe accumulator (the `ArrayList` above), which races; the fix is to express the accumulation as a **collector or reduction** so the framework does the splitting and thread-safe merging for you. On **performance**, `.parallel()` only helps when *all* of these hold: the dataset is **large** (the per-element work × element count must dwarf the fork/join scheduling overhead), the work per element is **CPU-bound and non-trivial**, the source **splits efficiently** (arrays and `ArrayList` split in O(1); `LinkedList`, `Stream.iterate`, and I/O-backed sources split poorly and can be *slower* parallel than sequential), and there's **no blocking I/O** in the pipeline (which would starve the shared common `ForkJoinPool`, Q23). The decision heuristic from Brian Goetz is roughly "N × Q" — the number of elements times the cost per element should be on the order of 10,000+ before parallelism pays. The senior posture: treat `.parallel()` as a *measured optimization for CPU-bound bulk computation over splittable in-memory data*, never as a default, never for I/O, and always verify the output is computed via associative collectors rather than racy side effects — then profile to confirm it actually beat sequential, because for the common small/IO-bound case it usually loses.

#### Q120. [Practical] How would you design a graceful, zero-loss in-flight request drain for a service shutting down (Kubernetes SIGTERM) under concurrency?

Graceful shutdown is where concurrency theory meets production ops: when the orchestrator sends SIGTERM (rolling deploy, scale-down), the pod must **stop accepting new work, finish in-flight requests, and release resources** without dropping or corrupting anything — all while new requests may still be arriving for a few seconds due to load-balancer lag. Getting this wrong causes 502s on every deploy.

```java
class GracefulShutdown {
    private final ExecutorService requestPool;
    private volatile boolean accepting = true;            // gate for new work

    void onSigterm() {
        accepting = false;                                // 1) stop accepting new requests
        // 2) keep serving in-flight + drain the queue gracefully:
        requestPool.shutdown();                           // no new tasks; finish queued/running
        try {
            // 3) bound the drain to the orchestrator's grace period (e.g., terminationGracePeriodSeconds)
            if (!requestPool.awaitTermination(25, TimeUnit.SECONDS)) {
                requestPool.shutdownNow();                // 4) interrupt stragglers as last resort
            }
        } catch (InterruptedException e) {
            requestPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closeResources();                                 // 5) flush buffers, close pools/connections
    }
}
```

The ordering and timing are the whole game. **First**, flip the readiness gate so the load balancer / Kubernetes readiness probe starts returning "not ready" and routes new traffic elsewhere — but you keep *serving* requests; there's a deliberate window (often 5–10s) where you're un-ready but still alive, because the LB takes time to notice and stop sending you traffic. **Second**, `shutdown()` (not `shutdownNow()`) so already-accepted requests and queued tasks complete — this is the two-phase termination pattern (Q68/Q71). **Third**, the `awaitTermination` budget must be **less than** Kubernetes' `terminationGracePeriodSeconds` (default 30s), or the orchestrator SIGKILLs you mid-drain and you lose the in-flight work you were trying to save — so you size the drain timeout to leave headroom for resource cleanup. **Fourth**, only after the grace budget do you `shutdownNow()` to interrupt stragglers (which only works if tasks are interruptible, Q36). The concurrency subtleties: the `accepting`/readiness flag must be `volatile` (visible immediately across threads); long-running streaming responses may need their own deadline; and stateful resources (DB connection pools, Kafka producers) must be flushed/closed *after* requests drain but *before* the JVM exits, in dependency order. In Spring Boot, `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase` wires most of this, and the readiness-probe flip is handled by the Actuator availability state — but understanding the *ordering* (un-ready → drain → bounded wait → force → cleanup, all within the grace period) is what prevents deploy-time error spikes.

#### Q121. [Coding] Demonstrate and fix a subtle visibility bug where a worker thread never sees a flag change set by `main`, and explain all the ways to fix it.

This is the canonical "missing happens-before" bug (Q4/Q39) and writing it out forces you to articulate *every* correct fix and *why* each works. A worker loops on a plain `boolean` flag; `main` sets it to stop the worker — but without synchronization the worker may **hoist the read out of the loop** (the JIT proves the loop body doesn't change `running`, so it caches `true` in a register) and spin forever, never seeing `main`'s write.

```java
class VisibilityBug {
    private boolean running = true;                       // BUG: no volatile / no sync

    void start() {
        new Thread(() -> {
            while (running) { /* work */ }                // JIT may cache running=true forever
        }).start();
    }
    void stop() { running = false; }                      // write may never be seen by the worker
}
```

The failure is not guaranteed — it depends on JIT optimization level and timing, so it often "works" in dev and hangs in production under a server JIT (a hallmark of visibility bugs, Q67). Here are the fixes, each establishing a happens-before edge between the write and the read:

```java
private volatile boolean running = true;     // FIX 1: volatile — write HB subsequent read (cheapest, idiomatic)

private final AtomicBoolean running = new AtomicBoolean(true);  // FIX 2: atomic — same visibility + CAS if needed
// worker: while (running.get())   ;  stop: running.set(false)

// FIX 3: synchronized accessors — lock release HB lock acquire
private boolean running = true;
synchronized boolean isRunning() { return running; }
synchronized void stop() { running = false; }

// FIX 4: interruption — interrupt() HB the worker observing it (Q57)
// worker: while (!Thread.currentThread().isInterrupted())  ;  stop: workerThread.interrupt()
```

The interview "why" is mapping each fix to *which* happens-before edge it creates: `volatile` (write→read edge, the right answer 95% of the time for a simple flag — Q4); `AtomicBoolean` (same visibility, plus atomic CAS when the new value depends on the old, which a flag doesn't, so volatile is sufficient and cheaper here); `synchronized` (unlock→lock edge — correct but heavier and overkill for a single flag, and *both* the read and write must be synchronized or you reintroduce the race); and **interruption** (the JDK-blessed cancellation mechanism, whose happens-before guarantee Q57 means a plain field written before `interrupt()` is even visible to the interrupted thread). The wrong "fixes" to call out as traps: making *only* the writer synchronized (the reader still races), adding a `Thread.sleep` in the loop (changes timing, doesn't establish an edge — masks the bug), or assuming it "works on my machine" (x86 TSO may hide it; ARM won't — Q67). The takeaway: a flag toggled by one thread and read by others is the textbook `volatile` use case, and being able to enumerate the alternatives *and their relative cost* demonstrates you reason in happens-before, not folklore.

#### Q122. [Coding] Maintain a multi-field invariant atomically without a lock using `AtomicReference` and an immutable snapshot.

When two related fields must change *together* (e.g., a `min`/`max` range where `min <= max` must always hold, or a balance plus a last-updated timestamp), a `volatile` on each field is insufficient — a reader can observe a *torn combination* where one field is updated and the other isn't (Q79). The lock-free fix is to bundle the related fields into an **immutable value object** and swap the whole object atomically via `AtomicReference.updateAndGet`, so any reader sees either the entire old state or the entire new state, never a mix.

```java
import java.util.concurrent.atomic.AtomicReference;

class NumberRange {
    private record Range(int lower, int upper) {
        Range { if (lower > upper) throw new IllegalArgumentException(); }  // invariant in ctor
    }
    private final AtomicReference<Range> range = new AtomicReference<>(new Range(0, 0));

    void setLower(int n) {
        range.updateAndGet(cur -> {                 // CAS-loop retry, lock-free
            if (n > cur.upper()) throw new IllegalArgumentException("lower > upper");
            return new Range(n, cur.upper());        // build a NEW consistent snapshot
        });
    }
    void setUpper(int n) {
        range.updateAndGet(cur -> {
            if (n < cur.lower()) throw new IllegalArgumentException("upper < lower");
            return new Range(cur.lower(), n);
        });
    }
    Range snapshot() { return range.get(); }        // atomic read of BOTH fields together
}
```

The mechanism is **copy-on-write of a small immutable record**: `updateAndGet` reads the current `Range`, runs your function to compute a new immutable `Range`, and CAS-publishes it — retrying the whole function if another thread swapped in between (the standard CAS loop, Q21). Because the published object is immutable and built atomically, the `min <= max` invariant is enforced at construction and can never be observed half-applied; a `snapshot()` reader gets a single coherent `(lower, upper)` pair with no lock. This is the lock-free analog of the multi-field problem that would otherwise force a `synchronized` block around both fields, and it's strictly better under read-heavy load because readers never block and never contend. The cost model: each update allocates a small object (GC pressure if updates are extremely frequent — `LongAdder`-style striping or a lock may win at extreme write rates), and the update function **must be side-effect-free and idempotent** because the CAS loop may invoke it multiple times before one attempt wins. The general principle: *promote a multi-field invariant into a single immutable reference and swap it atomically* — this converts a hard "multiple mutable fields" synchronization problem into the trivially-correct "swap one reference" problem the JMM and CAS solve cleanly.

#### Q123. [Coding] Implement a `StampedLock`-backed cache with optimistic reads and correct fallback, and explain the validation discipline.

`StampedLock` (Q26) gives the cheapest possible read path — an **optimistic read** acquires *no lock at all*, just a stamp, reads the fields, then validates the stamp; if no writer intervened, the read is valid with zero contention. The discipline that makes this correct (and the place most people get it wrong) is: copy fields into locals *first*, then validate, and only *use* the locals if validation passed — never use values mid-read before validating.

```java
import java.util.concurrent.locks.StampedLock;

class Point {
    private double x, y;
    private final StampedLock sl = new StampedLock();

    void move(double dx, double dy) {
        long stamp = sl.writeLock();                 // exclusive
        try { x += dx; y += dy; } finally { sl.unlockWrite(stamp); }
    }

    double distanceFromOrigin() {
        long stamp = sl.tryOptimisticRead();         // 0 means a write is in progress
        double cx = x, cy = y;                       // copy into locals FIRST
        if (!sl.validate(stamp)) {                   // a writer happened since the stamp?
            stamp = sl.readLock();                   // fall back to a real (pessimistic) read lock
            try { cx = x; cy = y; } finally { sl.unlockRead(stamp); }
        }
        return Math.sqrt(cx * cx + cy * cy);         // use the (now-validated) locals
    }

    // Conditional upgrade: read, and only if needed, upgrade to a write lock.
    void moveToOriginIfFar() {
        long stamp = sl.readLock();
        try {
            while (x * x + y * y > 100) {
                long ws = sl.tryConvertToWriteLock(stamp);   // try to upgrade without releasing
                if (ws != 0L) { stamp = ws; x = 0; y = 0; break; }
                else { sl.unlockRead(stamp); stamp = sl.writeLock(); }  // fall back: release+reacquire
            }
        } finally { sl.unlock(stamp); }
    }
}
```

The validation discipline is the entire correctness story. Between `tryOptimisticRead()` and `validate()`, a writer may run and *tear* your read (you might read the new `x` and the old `y`). That's *acceptable* precisely because you copied into locals and haven't acted on them yet — if `validate` returns `false`, you discard the possibly-torn locals and re-read under a real read lock. The bug is using a field's value *before* validating, or validating then reading (the read must happen *before* the validate so the validate covers it). `StampedLock` shines on **hot, read-dominated** paths because the optimistic path is a couple of plain reads plus a cheap validate — no CAS, no cache-line contention from a shared lock counter (unlike `ReentrantReadWriteLock`, where even readers write the shared read-count, Q61). The sharp edges to state: it is **not reentrant** (re-locking deadlocks), has **no `Condition` support**, and `tryConvertToWriteLock` enables conditional upgrade that RRWL forbids (Q61) — but you must handle the conversion-failed branch (release the read lock and acquire the write lock, accepting a window where another writer could interleave, hence the re-check loop). Use it only after measurement shows read-side lock contention is the bottleneck; otherwise `synchronized` or an `AtomicReference` snapshot (Q122) is safer.

#### Q124. [Coding] Show the `thenApply` vs `thenCompose` pitfall — nested `CompletableFuture` (`CompletableFuture<CompletableFuture<T>>`) and how to flatten it.

A frequent `CompletableFuture` bug is using `thenApply` when the mapping function *itself returns a future*, producing a nested `CompletableFuture<CompletableFuture<T>>` that you then have to `.get().get()` or `.join().join()` — losing all composition and usually blocking. `thenCompose` is the **flat-map** that unwraps the inner future, chaining two asynchronous steps into one flat pipeline.

```java
import java.util.concurrent.*;

CompletableFuture<User>  fetchUser(long id)        { return CompletableFuture.supplyAsync(() -> loadUser(id)); }
CompletableFuture<Order> fetchLatestOrder(User u)  { return CompletableFuture.supplyAsync(() -> loadOrder(u)); }

// WRONG: thenApply with a future-returning fn → nested future
CompletableFuture<CompletableFuture<Order>> nested =
    fetchUser(42).thenApply(user -> fetchLatestOrder(user));   // type smell: CF<CF<Order>>
// to get the Order you'd have to nested.join().join()  ← blocks, defeats async

// RIGHT: thenCompose flattens CF<CF<Order>> → CF<Order>
CompletableFuture<Order> flat =
    fetchUser(42).thenCompose(user -> fetchLatestOrder(user)); // sequential dependent async steps

// thenApply is correct ONLY when the fn returns a plain value, not a future:
CompletableFuture<String> name = fetchUser(42).thenApply(User::name);  // User -> String, fine
```

The rule maps directly to functional-programming intuition: **`thenApply` is `map`** (`A -> B`, wrap the `B` in a future for you) and **`thenCompose` is `flatMap`** (`A -> CompletableFuture<B>`, don't double-wrap). You use `thenCompose` whenever the next step is *itself asynchronous and depends on the previous result* — fetch a user, then asynchronously fetch their orders. Using `thenApply` there compiles fine (Java infers `CompletableFuture<CompletableFuture<Order>>`) but is almost always wrong: the outer future completes as soon as the *inner future is created*, not when it *completes*, so you've broken the dependency and any `join` is now a blocking `.join().join()`. The parallel pair for combining *independent* futures is **`thenCombine`** (run two unrelated futures and merge both results, `(A, B) -> C`), versus `thenCompose` for *sequential dependent* steps. The senior tell is recognizing the `CompletableFuture<CompletableFuture<T>>` type as a code smell on sight and reaching for `thenCompose` — the same instinct as preferring `flatMap` over `map` when the mapper returns a `Stream`/`Optional`.

#### Q125. [Theory] How does a `ConcurrentHashMap` provide safe publication, and why can you rely on its operations for happens-before between threads?

Beyond being thread-safe internally, `ConcurrentHashMap` (and the other `java.util.concurrent` collections) provides documented **memory-consistency / happens-before guarantees** that let you use it as a *publication channel* between threads with no extra synchronization (Q81). The specified guarantee: actions in a thread **prior to placing an object into** any concurrent collection *happen-before* actions subsequent to the **access or removal of that element** from the collection in another thread. In other words, `put` then `get` of the same key behaves like a release/acquire pair on the value.

```java
ConcurrentHashMap<String, Config> registry = new ConcurrentHashMap<>();

// Thread A: fully build the object, then publish it
Config c = new Config();
c.setHost("db1"); c.setPort(5432);     // plain writes during construction
registry.put("db", c);                  // PUBLISH: all prior writes HB the get below

// Thread B: read it back
Config seen = registry.get("db");       // ACCESS: guaranteed to see host/port fully set
if (seen != null) connect(seen.host(), seen.port());  // no torn/stale fields
```

This matters because it means you do **not** need to make `Config`'s fields `volatile` or synchronize its construction — the `put`/`get` through the concurrent map *is* the happens-before edge that safely publishes the fully-constructed object to the reading thread. It's the same publication safety that lock release/acquire, `volatile` write/read, and `Future.get` provide (Q57), just delivered through the collection's API. The practical consequences: (1) a common idiom is to build a complex object on one thread and hand it to workers via a `ConcurrentHashMap`/`BlockingQueue` — correct *because* of this guarantee, not by luck; (2) it does **not** protect against logical races on the *value after publication* — if Thread B mutates `seen` while Thread A still holds a reference and mutates it too, you're back to needing synchronization on `Config` itself; the guarantee only covers the *handoff*, not subsequent shared mutation. The deeper point interviewers want: "thread-safe collection" means two distinct things — its own internal operations are safe (linearizable, Q63), *and* it acts as a happens-before edge that safely publishes the elements you put through it — and you should lean on the latter to avoid sprinkling `volatile` on every shared DTO.

#### Q126. [Coding] Implement a sharded/striped atomic counter that scales better than a single `AtomicLong` but lets you read an exact total when quiesced.

`LongAdder` (Q58) is the go-to for write-heavy counters, but sometimes you need its scalability *plus* control over the shard layout, or you want to understand exactly how striping defeats cache-line contention. Here's a hand-rolled striped counter that hashes each thread to its own padded cell, demonstrating the mechanism `LongAdder` automates.

```java
import java.util.concurrent.atomic.*;

class StripedCounter {
    // Pad each cell onto its own cache line to avoid false sharing (Q20).
    @jdk.internal.vm.annotation.Contended
    static final class Cell { final AtomicLong v = new AtomicLong(); }

    private final Cell[] cells;
    private final int mask;

    StripedCounter(int stripes) {
        int n = Integer.highestOneBit(Math.max(1, stripes - 1) << 1);  // round up to power of two
        cells = new Cell[n];
        for (int i = 0; i < n; i++) cells[i] = new Cell();
        mask = n - 1;
    }

    void increment() {
        // hash the current thread to a stripe so different threads hit different cache lines
        int idx = (int) (Thread.currentThread().threadId() & mask);
        cells[idx].v.incrementAndGet();           // CAS on a *private* cell → low contention
    }

    long sum() {                                   // exact only when no concurrent writes
        long total = 0;
        for (Cell c : cells) total += c.v.get();
        return total;
    }
}
```

The scalability comes from **spreading writes across independent cache lines** so concurrent incrementers rarely touch the same line — eliminating the MESI invalidation storm that serializes a single `AtomicLong` under contention (Q20/Q58). Hashing by `threadId()` (rather than re-hashing per call) keeps a given thread pinned to one cell, maximizing per-cell cache locality; `@Contended` (requires `-XX:-RestrictContended` or the `jdk.internal` module export) pads each `Cell` so two cells never share a 64-byte line. The honest trade-offs versus `LongAdder`: (1) `LongAdder` **adapts** — it starts with a single `base` and only allocates cells when it *detects* contention, so it uses less memory under low contention, whereas this fixed-stripe version always allocates N cells; (2) `LongAdder` rehashes a thread's probe on collision to reduce clustering, which a static `threadId & mask` doesn't. The shared caveat is **`sum()` is not a linearizable snapshot** — it reads cells one at a time without a global lock, so a concurrent increment may or may not be included; it's exact only when writes have quiesced. The takeaway: for production, use `LongAdder`/`LongAccumulator` (battle-tested, adaptive); hand-roll striping only when you need a custom merge function or a specific shard count — and the exercise proves you understand *why* sharding scales (independent cache lines) and *why* the read is approximate (no atomic snapshot across shards).

#### Q127. [Practical] How do you correctly propagate `MDC`/trace context and security context across `CompletableFuture` async boundaries and thread pools?

Logging context (SLF4J `MDC`), trace IDs, and security/tenant context live in `ThreadLocal`s (Q17/Q59), so they **do not automatically follow work that hops threads** — a `CompletableFuture.*Async` callback runs on a *different* pool thread that has empty (or worse, stale, leaked-from-a-prior-task) context. The symptom is logs missing the trace ID once you go async, or — far more dangerous — one request's auth/tenant context bleeding into another's because the pooled thread retained it (a security issue, Q34). The fix is to **capture context at submission time and restore it on the worker**, then clear it.

```java
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.*;

class ContextPropagation {
    // Wrap a task so it runs with the SUBMITTER's context, then cleans up.
    static Runnable wrap(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();   // snapshot at submit time
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (captured != null) MDC.setContextMap(captured); else MDC.clear();
            try { task.run(); }
            finally {                                               // ALWAYS restore/clear (Q17)
                if (previous != null) MDC.setContextMap(previous); else MDC.clear();
            }
        };
    }
    // Decorate the executor so EVERY task gets context propagation transparently.
    static Executor contextAware(Executor delegate) {
        return r -> delegate.execute(wrap(r));
    }
}
// usage: CompletableFuture.supplyAsync(() -> work(), ContextPropagation.contextAware(pool));
```

The core principle is **capture-on-submit, restore-on-execute, clear-in-finally**. You snapshot the submitting thread's `MDC` *at the moment you create the task* (not when it runs), copy it onto the worker thread before the body, and crucially **clear it in a `finally`** so the pooled worker doesn't leak that context to the *next* task it picks up — the same `remove()`-in-`finally` discipline that prevents the `ThreadLocal` leak/bleed of Q17, applied at the pool boundary. Why not `InheritableThreadLocal`? Because it copies only at *thread creation* and pooled threads are created once and reused, so it captures the pool-creator's context, never the per-task submitter's (Q59) — it's the wrong tool here. In practice you don't hand-roll this: **Micrometer's `context-propagation`** library, Spring's **`TaskDecorator`** (`executor.setTaskDecorator(...)`), and SLF4J MDC adapters package exactly this capture/restore/clear, and Reactor/`ContextSnapshot` does it for reactive chains. For `CompletableFuture` specifically, wrap the **executor** you pass to `*Async` (as above) so propagation is automatic for every stage rather than relying on each callback to remember. The 2026 note: `ScopedValue` (Q54) propagates correctly across `StructuredTaskScope` forks *by design* (immutable, scope-bound, inherited by child virtual threads without copying), which is why it's the preferred context mechanism for new Loom-era code — but for existing `CompletableFuture`/thread-pool code, the wrap-the-executor pattern is the robust answer.

#### Q128. [Theory] What is the difference between blocking, non-blocking, and asynchronous, and how do these axes combine in Java's I/O and concurrency APIs?

These three terms are routinely conflated, and disentangling them clarifies the whole landscape of Java I/O and concurrency choices. **Blocking vs non-blocking** describes whether a *call returns immediately or waits*: a blocking call (`InputStream.read`, `BlockingQueue.take`) parks the calling thread until the operation can complete; a non-blocking call (`SocketChannel.read` in non-blocking mode returning `0`, `Queue.poll` returning `null`) returns right away, possibly indicating "nothing yet, try later." **Synchronous vs asynchronous** describes *who waits for the result and how it's delivered*: synchronous means the result is returned to the caller inline (you have it when the call returns); asynchronous means the operation runs in the background and the result is delivered later via a callback, `Future`, or completion handler — the caller does not wait inline at all.

```
                 SYNCHRONOUS (result inline)        ASYNCHRONOUS (result later/callback)
BLOCKING      | InputStream.read() — wait, return | (rare/contradictory combination)
NON-BLOCKING  | selector poll loop (NIO),         | CompletableFuture, AsynchronousFileChannel,
              | check-and-return, you re-poll     | Netty, callbacks — fire and be notified
```

The combinations matter for design. **Blocking + synchronous** is classic thread-per-request I/O — simple to read and debug, but each in-flight request consumes a thread, which historically capped scalability (and is exactly what *virtual threads* now make cheap, Q24). **Non-blocking + synchronous** is the NIO `Selector` event loop: one thread `select()`s over many channels, handling whichever is ready — scalable but you manage the readiness loop yourself. **Asynchronous** (`CompletableFuture`, `AsynchronousSocketChannel`/NIO.2, Netty, reactive) delivers results via callbacks/completion handlers so no thread waits — maximally scalable but with the callback-complexity and context-propagation costs (Q127) that make stack traces and debugging harder. The 2026 framing that ties it together: **virtual threads let you write *blocking, synchronous* code that the runtime executes with *non-blocking* efficiency** — when a virtual thread does a "blocking" `read`, the JVM unmounts it (non-blocking under the hood) so the carrier stays busy. This collapses the old forced trade-off — you no longer pay readability to get scalability, which is why the recommendation for I/O-bound services shifted from "go async/reactive for scale" to "write simple blocking code on virtual threads" (Q33). Knowing the three axes lets you place any API (`InputStream`, NIO `Selector`, `CompletableFuture`, virtual threads) on the grid and reason about its thread cost and complexity precisely.

## ✅ Key Takeaways

- Reason about correctness through **happens-before**, not caches/fences — establish an edge (lock, volatile, thread start/join, final fields) and the JMM guarantees visibility.
- `volatile` = visibility + ordering, **not** atomicity; use atomics/locks for read-modify-write.
- Prefer high-level `java.util.concurrent` constructs (`ConcurrentHashMap`, `BlockingQueue`, latches/barriers/semaphores) over hand-rolled `wait/notify`; always guard waits in a `while`.
- Construct `ThreadPoolExecutor` explicitly with **bounded** queues and a rejection policy; the `Executors` factory defaults hide OOM and thread-explosion risks.
- Choose the right tool: `synchronized` for simplicity, `ReentrantLock` for timeouts/fairness/conditions, `StampedLock` for hot read-dominated paths, atomics/`LongAdder` for counters.
- Prevent deadlock with **global lock ordering** or `tryLock` timeouts; break any one Coffman condition.
- **Virtual threads (Java 21+)** make thread-per-request blocking code scale like reactive — default to them for I/O-bound services in 2026, watching for pinning.
- Lock-free code (CAS, ABA, ring buffers, false sharing) is powerful but must be **verified exhaustively** (jcstress/Lincheck) — prefer vetted libraries (JCTools, Disruptor).

## ⚠️ Common Pitfalls

- Calling `Thread.run()` instead of `start()` — runs on the current thread, zero concurrency.
- Using `if` instead of `while` to guard `wait()`/`await()` — broken by spurious wakeups and stolen conditions.
- `count++` on a `volatile` or unguarded field and assuming it is atomic.
- Forgetting `ThreadLocal.remove()` in pooled threads — memory leak and cross-request data bleed.
- Running blocking I/O on the common `ForkJoinPool` (parallel streams, default `CompletableFuture.*Async`) — starves the whole JVM.
- Double-checked locking without a `volatile` field — exposes a partially constructed object.
- Unbounded `newFixedThreadPool`/`newCachedThreadPool` in production — OOM or thread explosion under load.
- Pooling virtual threads or blocking inside `synchronized`/native frames (pinning) — defeats Loom's benefit.
- Catching `InterruptedException` and swallowing it — always restore the flag (`Thread.currentThread().interrupt()`) or propagate.
- Assuming unit tests prove thread-safety — concurrency bugs surface only under contention.

## 📚 Further Reading

- **Brian Goetz et al. — *Java Concurrency in Practice*** — the canonical reference (JMM, publication, the executor framework); still essential in 2026.
- **Doug Lea — *Concurrent Programming in Java*** and his [JMM cookbook](https://gee.cs.oswego.edu/dl/jmm/cookbook.html) — fences and the model from the author of `java.util.concurrent`.
- **JSR-133 (Java Memory Model) FAQ** — the authoritative explanation of happens-before and reordering.
- **JEPs 425/444 (Virtual Threads), 453 (Structured Concurrency), 491 (synchronized pinning)** — primary sources on Project Loom.
- **OpenJDK jcstress** and **Lincheck** — tools to actually verify concurrent code correctness.
- **Martin Thompson — *Mechanical Sympathy* blog & the LMAX Disruptor technical paper** — low-latency, lock-free, cache-aware design in industry.
