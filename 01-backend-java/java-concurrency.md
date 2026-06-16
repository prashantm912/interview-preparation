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
