# Concurrency & Parallelism Patterns

[← Back to master index](../README.md)

A practical, interview-focused tour of concurrency and parallelism for backend engineers — threads vs processes, race conditions and critical sections, the full lock family, deadlock/livelock/starvation, lock-free and wait-free algorithms, the Java Memory Model and happens-before, false sharing, and higher-level models (actors, CSP/channels, fork-join, thread pools). The answers favor the mental models and trade-offs that come up in real interviews, with Java examples where the JVM exposes the concept directly. Content is accurate to 2026 practice on the JVM (Java 21+ with virtual threads) and modern multicore hardware.

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the difference between concurrency and parallelism?

**Concurrency** is about *dealing with* many things at once — structuring a program as independent tasks that can make progress in overlapping time windows. **Parallelism** is about *doing* many things at once — literally executing multiple computations simultaneously on multiple cores.

```
Concurrency (1 core, interleaved):   |--A--|--B--|--A--|--B--|--A--|
Parallelism  (2 cores, simultaneous): core0 |---A---A---A---|
                                       core1 |---B---B---B---|
```

Concurrency is a *structuring* concern; parallelism is an *execution* concern. You can have concurrency without parallelism (a single-core machine time-slicing between tasks) and parallelism is a way to *run* concurrent tasks faster. Rob Pike's summary: "Concurrency is about dealing with lots of things at once. Parallelism is about doing lots of things at once." A well-structured concurrent program can be run in parallel when cores are available, but the two ideas are independent.

### Q2. [Theory] How do threads differ from processes?

A **process** is an instance of a running program with its own isolated address space, file descriptors, and OS resources. A **thread** is a unit of execution *inside* a process; threads in the same process share the heap, globals, and file descriptors, but each has its own stack, program counter, and registers.

| | Process | Thread |
|---|---|---|
| Address space | Private, isolated | Shared with siblings |
| Creation cost | High (new page tables) | Low |
| Context switch | Expensive (TLB flush) | Cheaper |
| Communication | IPC (pipes, sockets, shared mem) | Shared memory directly |
| Failure blast radius | Isolated | A crash can kill the whole process |

In Java, every platform `Thread` maps to one OS thread inside the single JVM process — that shared heap is exactly why synchronization is needed. The trade-off: threads are cheap to communicate but dangerous (shared mutable state); processes are safe by isolation but expensive to coordinate.

### Q3. [Theory] What is a race condition?

A **race condition** occurs when the correctness of a program depends on the relative timing or interleaving of multiple threads accessing shared mutable state, and at least one access is a write. The result becomes nondeterministic — it depends on which thread "wins the race."

The classic example is a non-atomic increment. `count++` is actually three operations:

```
read count → add 1 → write count
```

If two threads both read `5`, both compute `6`, and both write `6`, one increment is lost.

```java
class Counter {
    private int count = 0;
    void increment() { count++; }   // NOT atomic — race condition
    int get() { return count; }
}
```

Run two threads each incrementing a million times and you'll reliably see fewer than two million. The fix is to make the read-modify-write atomic (a lock, `AtomicInteger`, etc.).

### Q4. [Theory] What is a critical section?

A **critical section** is a region of code that accesses shared resources and must not be executed by more than one thread at a time. The goal of mutual exclusion is to ensure that while one thread is inside its critical section, no other thread enters a critical section that touches the same data.

A correct mutual-exclusion solution must satisfy three properties:

1. **Mutual exclusion** — at most one thread in the critical section at a time.
2. **Progress** — if no thread is in the section, a thread wanting to enter must eventually be allowed (no needless blocking).
3. **Bounded waiting** — a thread can't be starved forever waiting to enter.

Keep critical sections **as small as possible**: hold the lock only around the shared-state access, not around slow I/O or expensive computation, to reduce contention.

### Q5. [Practical] How do you fix the race condition in a counter? Show three ways.

```java
// 1. synchronized (intrinsic monitor lock)
class SyncCounter {
    private int count = 0;
    synchronized void increment() { count++; }
    synchronized int get() { return count; }
}

// 2. AtomicInteger (lock-free CAS under the hood)
class AtomicCounter {
    private final java.util.concurrent.atomic.AtomicInteger count =
        new java.util.concurrent.atomic.AtomicInteger();
    void increment() { count.incrementAndGet(); }
    int get() { return count.get(); }
}

// 3. Explicit ReentrantLock
class LockCounter {
    private int count = 0;
    private final java.util.concurrent.locks.ReentrantLock lock =
        new java.util.concurrent.locks.ReentrantLock();
    void increment() {
        lock.lock();
        try { count++; } finally { lock.unlock(); }
    }
}
```

For a single counter, `AtomicInteger` is best — it's lock-free and scales well under moderate contention. Always release locks in a `finally` so an exception can't leave the lock held.

### Q6. [Theory] What is a mutex, and how does it differ from a binary semaphore?

A **mutex** (mutual-exclusion lock) protects a critical section: a thread *acquires* it, enters the section, then *releases* it. A key property is **ownership** — only the thread that locked a mutex may unlock it.

A **binary semaphore** is a counter capped at 1 with `acquire`/`release` (P/V) operations, but it has **no ownership**: any thread can signal it. That makes a binary semaphore suitable for *signaling between threads* (one thread waits, another signals), whereas a mutex is for *protecting data*.

```
Mutex:     owned by the locker → use for mutual exclusion
Semaphore: no owner, just a count → use for signaling / resource counting
```

Using a semaphore where you meant a mutex is a common bug: it compiles and often works, but loses the ownership guarantee and can mask logic errors.

### Q7. [Theory] What is a spinlock and when is it appropriate?

A **spinlock** is a lock where a thread that fails to acquire it **busy-waits** ("spins") in a tight loop re-checking the lock, instead of blocking and yielding the CPU.

```
Mutex (blocking):  fail → sleep → woken by OS → expensive context switch
Spinlock:          fail → loop checking → no context switch, burns CPU
```

Spinlocks are appropriate only when:

- The critical section is **very short** (a few instructions), so the expected spin time is less than the cost of a context switch (~1–5 µs).
- You're on a **multiprocessor** — spinning on a single core is pointless because the lock holder can't run while you spin.
- You **must not block** (e.g., inside an interrupt handler or a non-preemptible context).

For anything held longer, a blocking mutex is better. Hybrid "adaptive" locks spin briefly, then fall back to blocking — the JVM does this internally for `synchronized`.

### Q8. [Theory] What is a read-write lock and what problem does it solve?

A **read-write lock** (`ReadWriteLock`) allows either many concurrent **readers** *or* one exclusive **writer**, but never both. It exploits the fact that concurrent reads of immutable-during-read data are safe; only writes need exclusivity.

```
Readers:  R R R R  (all concurrent — shared lock)
Writer:        W   (exclusive — blocks all readers and writers)
```

It pays off when reads vastly outnumber writes and the critical section is non-trivial. The risk is **writer starvation**: a continuous stream of readers can keep a writer waiting forever, so most implementations (including Java's `ReentrantReadWriteLock`) offer a fair mode or give waiting writers priority. For very short read-heavy sections, `StampedLock` with *optimistic reads* often beats `ReentrantReadWriteLock`.

### Q9. [Practical] Demonstrate a ReentrantReadWriteLock guarding a cache.

```java
import java.util.*;
import java.util.concurrent.locks.*;

class Cache<K, V> {
    private final Map<K, V> map = new HashMap<>();
    private final ReadWriteLock rw = new ReentrantReadWriteLock();

    V get(K key) {
        rw.readLock().lock();
        try { return map.get(key); }       // many readers concurrently
        finally { rw.readLock().unlock(); }
    }

    void put(K key, V value) {
        rw.writeLock().lock();
        try { map.put(key, value); }        // exclusive
        finally { rw.writeLock().unlock(); }
    }
}
```

A reader holds the shared read lock; a writer takes the exclusive write lock, blocking all readers. Note you **cannot upgrade** a read lock to a write lock in Java's implementation (that would deadlock); you must release the read lock first.

### Q10. [Theory] What is a deadlock, and what four conditions are required for it?

A **deadlock** is a state where a set of threads are each waiting for a resource held by another in the set, so none can ever proceed. The Coffman conditions — all four must hold simultaneously:

1. **Mutual exclusion** — resources are non-shareable.
2. **Hold and wait** — a thread holds one resource while waiting for another.
3. **No preemption** — resources can't be forcibly taken away.
4. **Circular wait** — a cycle of threads each waiting on the next.

```
Thread 1: holds A, wants B
Thread 2: holds B, wants A
    A ──held by──► T1 ──wants──► B ──held by──► T2 ──wants──► A   (cycle!)
```

Break **any one** condition to prevent deadlock. The most practical: impose a **global lock-ordering** so every thread acquires locks in the same order, eliminating circular wait.

### Q11. [Practical] Show a deadlock and then fix it with lock ordering.

```java
// DEADLOCK: thread 1 locks a→b, thread 2 locks b→a
void transferBad(Account a, Account b, int amt) {
    synchronized (a) {
        synchronized (b) { a.debit(amt); b.credit(amt); }
    }
}

// FIX: always acquire in a canonical order (e.g., by id)
void transfer(Account a, Account b, int amt) {
    Account first  = a.id < b.id ? a : b;
    Account second = a.id < b.id ? b : a;
    synchronized (first) {
        synchronized (second) { a.debit(amt); b.credit(amt); }
    }
}
```

By always locking the lower-id account first, no two threads can form a circular wait. An alternative is `tryLock` with a timeout and back-off, which breaks the *no-preemption* condition by giving up and retrying.

### Q12. [Theory] What are livelock and starvation, and how do they differ from deadlock?

- **Deadlock** — threads are *blocked* forever, doing nothing.
- **Livelock** — threads are *actively running* but make no progress because they keep reacting to each other. Classic analogy: two people in a hallway repeatedly stepping the same way to let each other pass.
- **Starvation** — a thread is perpetually denied a resource it needs (e.g., a low-priority thread that never gets scheduled, or a writer starved by a stream of readers), even though the system as a whole makes progress.

```
Deadlock:   nobody moves, nobody runs
Livelock:   everybody runs, nobody progresses
Starvation: system progresses, one thread is left behind
```

Livelock often arises from naive deadlock-recovery (everyone backs off and retries in lockstep); randomized back-off breaks the symmetry. Starvation is addressed with fairness policies and priority aging.

### Q13. [Theory] What is the producer-consumer problem?

The **producer-consumer** pattern decouples threads that *produce* work items from threads that *consume* them, using a shared **bounded buffer** (queue). Producers block when the buffer is full; consumers block when it's empty.

```
Producers ──► [ □ □ □ □ ]  ──► Consumers
              bounded queue
   block if full        block if empty
```

It provides **backpressure** (a slow consumer naturally throttles fast producers via the full buffer) and smooths bursty load. In Java you almost never hand-roll it — `BlockingQueue` (e.g., `ArrayBlockingQueue`, `LinkedBlockingQueue`) encapsulates all the wait/signal logic.

### Q14. [Practical] Implement producer-consumer with a BlockingQueue.

```java
import java.util.concurrent.*;

BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(100);

Runnable producer = () -> {
    try {
        for (int i = 0; ; i++) queue.put(i);   // blocks when full
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
};

Runnable consumer = () -> {
    try {
        while (true) {
            int item = queue.take();           // blocks when empty
            process(item);
        }
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
};
```

`put`/`take` handle all the blocking and signaling. Always restore the interrupt flag (`Thread.currentThread().interrupt()`) when catching `InterruptedException` so higher-level code can observe the cancellation.

### Q15. [Theory] What is a thread pool and why use one?

A **thread pool** maintains a set of pre-created worker threads that pull tasks from a queue, rather than spawning a new OS thread per task. Benefits:

- **Amortized cost** — thread creation/teardown is expensive; reuse avoids it.
- **Resource bounding** — caps the number of concurrent threads so you don't exhaust memory or thrash the scheduler under load.
- **Decoupling** — submitting work (`Runnable`/`Callable`) is separated from how it runs.

In Java, `ExecutorService` from `Executors` or a directly configured `ThreadPoolExecutor`:

```java
ExecutorService pool = Executors.newFixedThreadPool(8);
Future<Integer> f = pool.submit(() -> compute());
pool.shutdown();
```

Avoid the unbounded `Executors.newCachedThreadPool()` for untrusted load — it can create thousands of threads. Prefer a `ThreadPoolExecutor` with an explicit bounded queue and a rejection policy.

### Q16. [Theory] What is a semaphore and what is it used for?

A **semaphore** maintains a set of permits. `acquire()` takes a permit (blocking if none are available); `release()` returns one. A **counting semaphore** (permits > 1) is ideal for limiting concurrent access to a pool of N identical resources.

```java
Semaphore connections = new Semaphore(10);   // 10 DB connections

void useConnection() throws InterruptedException {
    connections.acquire();        // wait for a free permit
    try { doWork(); }
    finally { connections.release(); }
}
```

This caps concurrency at 10 regardless of how many threads call in. A binary semaphore (1 permit) approximates a lock but without ownership semantics. Unlike a lock, a semaphore can be released by a different thread than the one that acquired it.

### Q17. [Theory] What does the `volatile` keyword guarantee in Java?

`volatile` gives a variable two guarantees:

1. **Visibility** — a write by one thread is immediately visible to reads by other threads (no caching in registers/CPU cache; reads and writes go to main memory).
2. **Ordering** — it establishes a *happens-before* edge: everything that happened before a volatile write is visible to a thread that subsequently reads that volatile.

It does **not** provide atomicity for compound operations. `volatile int x; x++;` is still a race because `x++` is read-modify-write. Use `volatile` for a simple flag read by many threads and written by one:

```java
private volatile boolean running = true;
void stop() { running = false; }          // visible immediately
void loop() { while (running) { ... } }   // sees the update, never caches
```

Without `volatile`, the loop might cache `running` in a register and spin forever.

### Q18. [Practical] What's the difference between `Runnable` and `Callable`?

```java
// Runnable: no return value, cannot throw checked exceptions
Runnable r = () -> System.out.println("work");

// Callable: returns a value, may throw checked exceptions
Callable<Integer> c = () -> {
    if (failed()) throw new IOException();   // allowed
    return 42;
};

ExecutorService pool = Executors.newFixedThreadPool(2);
Future<Integer> future = pool.submit(c);     // submit a Callable
int result = future.get();                   // blocks until done; may rethrow
```

Use `Runnable` for fire-and-forget side effects, and `Callable<V>` when you need a result or want checked-exception propagation through the returned `Future`. `future.get()` blocks and wraps any thrown exception in `ExecutionException`.

### Q19. [Theory] What is the difference between `wait()`/`notify()` and `sleep()`?

- `Thread.sleep(ms)` pauses the current thread for a duration but **holds any locks** it owns. It's a timing mechanism, not a coordination one.
- `Object.wait()` **releases** the object's monitor lock and parks the thread until another thread calls `notify()`/`notifyAll()` on the same object. It's used for condition-based coordination and **must** be called inside a `synchronized` block on that object.

Always call `wait()` in a loop checking the condition (to handle *spurious wakeups* and missed signals):

```java
synchronized (lock) {
    while (!conditionMet()) {   // loop, not if!
        lock.wait();
    }
    // condition is now true
}
```

In modern code, prefer higher-level `Condition`, `BlockingQueue`, or `CountDownLatch` over raw `wait`/`notify`.

### Q20. [Practical] How do you create and use a virtual thread in Java 21+?

Virtual threads (Project Loom, final in Java 21) are lightweight threads scheduled by the JVM onto a small pool of OS *carrier* threads. You can have millions of them; a blocking call unmounts the virtual thread from its carrier instead of blocking an OS thread.

```java
// One virtual thread
Thread vt = Thread.ofVirtual().start(() -> handleRequest());

// An executor that spawns a new virtual thread per task
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 1_000_000; i++) {
        executor.submit(() -> { blockingIo(); return null; });
    }
}   // close() waits for all tasks
```

This makes the simple "thread-per-request" model scale to massive concurrency for I/O-bound workloads, without the complexity of reactive/async code. Don't pool virtual threads — create one per task. Avoid pinning them by holding `synchronized` locks across blocking calls (use `ReentrantLock` instead).

## 🟡 Intermediate (3–7 yrs)

### Q21. [Theory] What is the Java Memory Model (JMM) and why does it exist?

The **Java Memory Model** specifies the rules by which writes to memory by one thread become visible to other threads, and what reorderings the compiler, JVM, and CPU are allowed to perform. Without it, "shared memory" would be meaningless across platforms, because compilers and hardware aggressively reorder and cache operations for speed.

The JMM is defined in terms of the **happens-before** relation. If action A happens-before action B, then A's effects are guaranteed visible to B. If two accesses to the same data are *not* ordered by happens-before and at least one is a write, you have a **data race** and the result is undefined.

The JMM gives the **DRF-SC guarantee**: a *correctly synchronized* (data-race-free) program will appear to execute with sequential consistency, even though the underlying machine reorders freely. Your job is to introduce enough happens-before edges (via locks, volatiles, etc.) to eliminate data races.

### Q22. [Theory] Explain the happens-before relation and list the main edges.

**Happens-before** is a partial order over memory actions. If A happens-before B, the memory effects of A are visible to B. The key edges the JMM provides:

- **Program order** — within a single thread, each action happens-before later actions.
- **Monitor lock** — unlocking a monitor happens-before any subsequent lock of the same monitor.
- **Volatile** — a write to a volatile happens-before every subsequent read of that volatile.
- **Thread start** — `Thread.start()` happens-before any action in the started thread.
- **Thread join** — all actions in a thread happen-before another thread returns from `join()` on it.
- **Transitivity** — if A hb B and B hb C, then A hb C.

```
Thread 1: write data; volatile flag = true;   ──┐ (volatile write)
                                                 │ happens-before
Thread 2: if (flag) read data;  ◄────────────────┘ (volatile read sees data)
```

This is the foundation of nearly every safe-publication idiom in Java.

### Q23. [Theory] What is memory ordering, and what reorderings can hardware/compilers do?

Modern compilers and CPUs reorder loads and stores to hide latency and keep pipelines full, as long as single-threaded semantics are preserved. The four basic reorderings:

```
LoadLoad, LoadStore, StoreLoad, StoreStore
```

The dangerous one is **StoreLoad** (a store followed by a load to a different location can be reordered so the load appears to execute first) — most weak memory models (ARM, POWER) permit it. x86 has a relatively strong **TSO** model that only allows StoreLoad reordering; ARM/POWER allow almost all of them.

**Memory barriers** (fences) restrict reordering. A volatile write in Java emits a StoreStore + StoreLoad fence; a volatile read emits LoadLoad + LoadStore. You rarely write barriers directly in Java — `volatile`, locks, and `VarHandle` access modes insert the right ones for you.

### Q24. [Theory] What is Compare-And-Swap (CAS) and how does lock-free programming use it?

**Compare-And-Swap** is an atomic hardware instruction (`CMPXCHG` on x86, `LL/SC` on ARM) that takes (memory location, expected value, new value) and atomically: *if the current value equals expected, write new and return true; else return false*.

```
CAS(addr, expected, new):
    atomically { if (*addr == expected) { *addr = new; return true; }
                 else return false; }
```

Lock-free algorithms use CAS in a retry loop: read the current value, compute the new value, then CAS; if CAS fails (someone else changed it), re-read and retry.

```java
AtomicInteger counter = new AtomicInteger();
int prev, next;
do {
    prev = counter.get();
    next = prev + 1;
} while (!counter.compareAndSet(prev, next));  // retry on contention
```

This is what `incrementAndGet()` does internally. No thread can block another by holding a lock — at least one thread always makes progress.

### Q25. [Theory] What does lock-free vs wait-free mean?

These are **non-blocking progress guarantees**:

- **Lock-free** — *system-wide* progress: at least one thread makes progress in a bounded number of steps, even if others stall. Individual threads may starve (retry forever under heavy contention), but the system never deadlocks.
- **Wait-free** — *per-thread* progress: *every* thread completes its operation in a bounded number of steps, regardless of contention. The strongest guarantee, and the hardest to implement.
- **Obstruction-free** — the weakest: a thread makes progress if it runs in isolation (no contention).

```
obstruction-free  ⊂  lock-free  ⊂  wait-free   (strength increases →)
```

CAS-loop counters are lock-free (a losing thread retries indefinitely under contention). Wait-free structures often need helping schemes or fetch-and-add. `AtomicInteger.getAndIncrement()` on modern hardware uses fetch-and-add and is effectively wait-free.

### Q26. [Theory] What is the ABA problem and how do you solve it?

The **ABA problem** is a subtle bug in CAS-based algorithms: a value changes from A to B and back to A between a thread's read and its CAS. The CAS *succeeds* because the value matches, but the world actually changed underneath — for example, a node was freed and a different node reused the same address.

```
T1 reads head = A
            T2: pop A, pop B, push A again  (head is A again, but list changed)
T1 CAS(head, A, ...) succeeds — WRONG, the list is not what T1 thinks
```

Solutions:

- **Versioned/tagged pointers** — pair the value with a monotonically increasing counter and CAS both together. Java's `AtomicStampedReference` does exactly this.
- **`AtomicMarkableReference`** for a single boolean mark.
- **Hazard pointers** or epoch-based reclamation to ensure memory isn't reused while a reference is live (common in C++; the GC mostly hides the memory-reuse flavor of ABA in Java).

```java
AtomicStampedReference<Node> head = new AtomicStampedReference<>(null, 0);
int[] stampHolder = new int[1];
Node cur = head.get(stampHolder);
int stamp = stampHolder[0];
head.compareAndSet(cur, newNode, stamp, stamp + 1);  // version bump
```

### Q27. [Theory] What is false sharing and how do you avoid it?

**False sharing** occurs when two threads modify *different* variables that happen to live on the **same CPU cache line** (typically 64 bytes). Even though there's no logical sharing, the cache-coherence protocol invalidates the whole line on each write, causing the threads to ping-pong the line between cores — a silent, severe performance killer.

```
Cache line (64 bytes): [ counterA | counterB | ... ]
Core 0 writes counterA → invalidates line on Core 1
Core 1 writes counterB → invalidates line on Core 0   (ping-pong!)
```

Mitigations:

- **Padding** — separate hot fields onto different cache lines.
- Java's `@Contended` annotation (with `-XX:-RestrictContended`) tells the JVM to pad a field.
- Use per-thread state and combine at the end (e.g., `LongAdder`, which spreads counts across padded cells to avoid contention *and* false sharing).

```java
import java.util.concurrent.atomic.LongAdder;
LongAdder adder = new LongAdder();   // striped, padded — beats AtomicLong under contention
adder.increment();
long total = adder.sum();
```

### Q28. [Practical] When would you use LongAdder over AtomicLong?

Use **`LongAdder`** for high-contention counters where you write often and read occasionally (metrics, request counts). `AtomicLong` uses a single CAS target, so under heavy contention all threads contend on one location and most CAS attempts fail and retry. `LongAdder` maintains an array of **striped cells**; each thread updates a different cell, dramatically reducing contention and false sharing.

```java
// High write contention → LongAdder
LongAdder hits = new LongAdder();
hits.increment();          // hits a thread-local cell, minimal contention
long total = hits.sum();   // sums all cells (not atomic snapshot)
```

Trade-off: `sum()` is a slightly more expensive, non-atomic aggregate read, and `LongAdder` uses more memory. Use `AtomicLong` when you need a precise atomic value frequently (e.g., generating IDs) or contention is low.

### Q29. [Theory] What is the actor model?

The **actor model** is a concurrency model where the unit of computation is an **actor**: an isolated entity with private state that communicates *only* by sending asynchronous **messages**. An actor processes one message at a time from its mailbox, so its internal state never needs locks.

```
        msg            msg
Actor A ───► [mailbox] Actor B ───► [mailbox] Actor C
   (private state, single-threaded message processing each)
```

Key properties: no shared mutable state (so no data races), location transparency (actors can be local or remote), and "let it crash" supervision hierarchies for fault tolerance. Examples: Erlang/Elixir processes, Akka/Pekko on the JVM, Microsoft Orleans. It trades the difficulty of locks for the difficulty of reasoning about asynchronous message flow and at-most-once/at-least-once delivery semantics.

### Q30. [Theory] What is CSP and how do channels differ from actor mailboxes?

**Communicating Sequential Processes (CSP)** is Tony Hoare's model where independent processes communicate through **channels** rather than by name. The defining feature is that channels (especially *unbuffered* ones) provide a **rendezvous**: the sender blocks until a receiver is ready, synchronizing the two.

```
CSP:    process → [channel] → process   (channel is the named entity)
Actors: actor   → mailbox  → actor      (the actor/address is named)
```

Differences from actors:

- In CSP, **channels are first-class and named**; processes are anonymous. In actors, the **actor address** is named and the mailbox is intrinsic.
- CSP channels are often **synchronous** (rendezvous); actor mailboxes are **asynchronous** (fire-and-forget).
- Go's goroutines + channels are the most popular CSP-style implementation; `select` lets a process wait on multiple channels.

### Q31. [Theory] Explain the fork-join framework and work-stealing.

**Fork-join** is a divide-and-conquer parallelism framework: a task **forks** into subtasks that run in parallel, then **joins** (waits for and combines) their results. It targets CPU-bound, recursively decomposable problems (sorting, tree traversal, parallel sums).

**Work-stealing** is the scheduling trick that makes it efficient: each worker thread has its own **double-ended queue (deque)** of tasks. A worker pushes/pops subtasks from the *head* of its own deque (LIFO, cache-friendly). When a worker runs out, it **steals** from the *tail* of another worker's deque (FIFO, stealing older/larger tasks).

```
Worker 0 deque: [t1 t2 t3]  ← own end (push/pop)
Worker 1 deque: []  → steals t1 from Worker 0's far end
```

This balances load automatically without central coordination. Java's `ForkJoinPool` (which also backs parallel streams and `CompletableFuture`'s default executor) implements this.

### Q32. [Practical] Implement a parallel sum with the fork-join framework.

```java
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.ForkJoinPool;

class SumTask extends RecursiveTask<Long> {
    private static final int THRESHOLD = 10_000;
    private final long[] arr; private final int lo, hi;
    SumTask(long[] arr, int lo, int hi) { this.arr = arr; this.lo = lo; this.hi = hi; }

    protected Long compute() {
        if (hi - lo <= THRESHOLD) {              // small enough: do it directly
            long sum = 0;
            for (int i = lo; i < hi; i++) sum += arr[i];
            return sum;
        }
        int mid = (lo + hi) >>> 1;
        SumTask left  = new SumTask(arr, lo, mid);
        SumTask right = new SumTask(arr, mid, hi);
        left.fork();                              // run left asynchronously
        long rightResult = right.compute();       // compute right in this thread
        long leftResult  = left.join();           // wait for left
        return leftResult + rightResult;
    }
}

long total = ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length));
```

The idiom — `fork()` one half, `compute()` the other inline, then `join()` — keeps the current thread busy instead of forking both and idling, which improves throughput.

### Q33. [Theory] What is the readers-writers problem and its variants?

The **readers-writers problem** asks how to coordinate threads that *read* shared data (safe concurrently) with threads that *write* it (must be exclusive). The challenge is the policy when both are waiting:

- **Readers-preference** — readers never wait if another reader is active → can **starve writers**.
- **Writers-preference** — a waiting writer blocks new readers → can **starve readers**.
- **Fair / no-starvation** — serve in arrival order (e.g., FIFO), bounding waiting for both.

This is exactly what a `ReadWriteLock` solves. Java's `ReentrantReadWriteLock` defaults to a non-fair mode (favoring throughput) but offers a fair constructor. `StampedLock` adds an **optimistic read** mode: read without locking, then *validate* the stamp; if a writer intervened, fall back to a real read lock.

### Q34. [Theory] What is the dining philosophers problem?

Five philosophers sit around a table; between each pair is one fork. A philosopher needs **both** adjacent forks to eat. If every philosopher grabs their left fork simultaneously, each holds one and waits forever for the right — **deadlock**.

```
        P0
   f0        f4
 P1            P4
   f1        f3
      P2  f2  P3
```

It illustrates deadlock and starvation in resource allocation. Classic solutions:

- **Resource ordering** — number the forks; each philosopher picks up the lower-numbered fork first. This breaks the symmetry (one philosopher reaches for the right fork first), eliminating circular wait.
- **Limit concurrency** — a semaphore allowing at most 4 philosophers to attempt eating at once guarantees at least one can get both forks.
- **Arbitrator/waiter** — a central mutex serializes fork pickup.
- **`tryLock` with back-off** — pick up one fork, try the other; if it fails, release the first and retry.

### Q35. [Practical] What's the difference between CountDownLatch and CyclicBarrier?

Both coordinate multiple threads at a synchronization point, but:

- **`CountDownLatch`** — a one-shot gate. Initialized to N; threads call `countDown()`; waiters in `await()` are released when the count hits 0. **Not reusable** — once it reaches 0 it stays there.
- **`CyclicBarrier`** — a reusable rendezvous. N threads each call `await()`; when the Nth arrives, *all* are released together and the barrier **resets** for the next round. It can run an optional barrier action when tripped.

```java
// Latch: main thread waits for 3 workers to finish startup
CountDownLatch latch = new CountDownLatch(3);
// in each worker: latch.countDown();
latch.await();   // main proceeds once all 3 are done

// Barrier: 4 threads sync each iteration of a simulation
CyclicBarrier barrier = new CyclicBarrier(4, () -> System.out.println("phase done"));
// in each worker, each round: barrier.await();
```

Rule of thumb: latch for "wait for N events to happen once"; barrier for "N threads repeatedly meet at a checkpoint."

### Q36. [Practical] How do you safely publish an object so other threads see it fully constructed?

**Safe publication** ensures that when one thread makes an object visible to others, they see it fully constructed (all its fields). Without it, a reader can see a non-null reference but stale/default field values, due to reordering. Safe idioms:

```java
// 1. final fields — JMM guarantees they're visible after construction completes
class Point { final int x, y; Point(int x, int y){ this.x=x; this.y=y; } }

// 2. volatile reference
private volatile Config config;
void set() { config = new Config(...); }  // readers see a fully-built Config

// 3. Static initializer (class-init lock guarantees publication)
private static final Helper H = new Helper();

// 4. Storing into a concurrent collection / AtomicReference
map.put(key, new Value(...));             // ConcurrentHashMap publishes safely
```

The danger is *unsafe* publication — e.g., assigning to a plain non-final, non-volatile field where another thread might see the reference before the constructor's writes are visible.

### Q37. [Practical] Why is double-checked locking broken without volatile, and how do you fix it?

The classic broken lazy singleton:

```java
class Broken {
    private static Broken instance;            // NOT volatile — bug
    static Broken get() {
        if (instance == null) {                // 1st check (no lock)
            synchronized (Broken.class) {
                if (instance == null)          // 2nd check (locked)
                    instance = new Broken();   // can be seen half-constructed!
            }
        }
        return instance;
    }
}
```

The problem: `instance = new Broken()` is (1) allocate, (2) run constructor, (3) assign reference. The JMM allows (3) to be reordered before (2), so another thread can read a non-null `instance` whose constructor hasn't finished. The fix is **`volatile`**, which forbids that reordering:

```java
private static volatile Broken instance;   // correct
```

Even better, prefer the **initialization-on-demand holder** idiom, which is simpler and relies on class-init semantics:

```java
class Singleton {
    private Singleton() {}
    private static class Holder { static final Singleton INSTANCE = new Singleton(); }
    static Singleton get() { return Holder.INSTANCE; }  // lazy + thread-safe, no volatile
}
```

### Q38. [Theory] What is the difference between async and parallel?

- **Asynchronous** means a call returns *before* the work finishes; you get a handle (`Future`/`CompletableFuture`/callback) and continue, picking up the result later. It's about **not blocking** — the work might run on the same thread later, on another thread, or via non-blocking I/O.
- **Parallel** means work runs **literally simultaneously** on multiple cores.

```
Async (1 thread, non-blocking I/O):  submit → do other work → result arrives
Parallel (N cores):                  task split → run at the same time
```

They're orthogonal. Async I/O can be single-threaded (Node.js event loop) — not parallel. Parallel CPU work can be synchronous from the caller's view (a blocking `parallelStream().sum()`). You use async to *avoid wasting threads on waiting* (I/O-bound); you use parallelism to *use more cores* (CPU-bound).

### Q39. [Theory] What's the difference between data parallelism and task parallelism?

- **Data parallelism** — the *same* operation applied to *different pieces of data* in parallel. SIMD, parallel `map`/`reduce`, GPU kernels, `array.parallelStream().map(f)`. Scales with data size.
- **Task parallelism** — *different* operations run in parallel, possibly on the same or different data. Running the auth check, fraud check, and inventory check for an order concurrently. Scales with the number of independent tasks.

```
Data parallel:  f(d0) f(d1) f(d2) f(d3)   (same f, different data)
Task parallel:  taskA  taskB  taskC       (different work)
```

Many real systems mix both: a pipeline of stages (task parallel) where each stage processes a partitioned dataset (data parallel).

### Q40. [Practical] Compose asynchronous work with CompletableFuture.

```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<User>  user  = CompletableFuture.supplyAsync(() -> fetchUser(id));
CompletableFuture<Stats> stats = CompletableFuture.supplyAsync(() -> fetchStats(id));

// Combine two independent async results (run in parallel, join when both done)
CompletableFuture<Profile> profile = user.thenCombine(stats, Profile::new);

// Chain a dependent step without blocking a thread
profile.thenApply(Profile::summary)
       .thenAccept(System.out::println)
       .exceptionally(ex -> { log(ex); return null; });   // error handling
```

`supplyAsync` runs on the common `ForkJoinPool` (pass an `Executor` to control it). `thenCombine` waits for both inputs; `thenApply`/`thenCompose` chain dependent steps. Nothing blocks a thread until you call `join()`/`get()` — this is async composition, and the two fetches above run in parallel.

### Q41. [Theory] What guarantees does ConcurrentHashMap provide, and how does it scale?

`ConcurrentHashMap` provides thread-safe access without locking the whole map. Modern implementations (Java 8+) lock at the **bin (bucket) level** — synchronizing on the first node of a bucket only during writes — and use CAS for the common cases (empty-bin insert, size counter). Reads are typically **lock-free**.

Guarantees:

- Each individual operation (`get`, `put`, `computeIfAbsent`) is atomic and thread-safe.
- It does **not** lock the whole map, so reads and writes to different buckets proceed concurrently.
- Iterators are **weakly consistent**: they don't throw `ConcurrentModificationException` and reflect *some* state between creation and traversal, but not necessarily a single snapshot.
- **Compound** operations still need care: `if (!map.containsKey(k)) map.put(k, v)` is a race; use atomic `putIfAbsent` or `computeIfAbsent` instead.

```java
map.computeIfAbsent(key, k -> expensiveLoad(k));  // atomic, computed at most once per key
```

## 🟠 Advanced (8–12 yrs)

### Q42. [Theory] Explain sequential consistency, TSO, and weak memory models. Why does it matter for Java?

A **memory consistency model** defines the order in which one core's memory operations become visible to others.

- **Sequential consistency (SC)** — operations appear in a single global order consistent with each thread's program order. Intuitive but expensive; no real mainstream CPU provides it by default.
- **Total Store Order (TSO)** — x86's model: stores are buffered in a per-core FIFO store buffer, so a core's own later loads can pass its earlier stores (StoreLoad reordering). Other reorderings are forbidden.
- **Weak/relaxed models** — ARM, POWER, RISC-V allow nearly all reorderings unless fences are inserted; even independent loads can be reordered.

```
SC:   strongest, slowest
TSO:  x86 — only StoreLoad reordering
Weak: ARM/POWER — almost anything without barriers
```

This matters because Java must run identically on all of them. The JMM is the *portable abstraction*: by reasoning in happens-before, your code is correct everywhere, and the JVM emits the right (possibly different) barriers per architecture. Code that "works on x86" can break on ARM (e.g., Apple Silicon, Graviton) precisely because x86's TSO accidentally masks missing synchronization.

### Q43. [Theory] How would you implement a lock-free stack (Treiber stack), and what are its pitfalls?

A **Treiber stack** is a lock-free LIFO using a CAS loop on the head pointer:

```java
import java.util.concurrent.atomic.AtomicReference;

class TreiberStack<E> {
    private static final class Node<E> { final E item; Node<E> next; Node(E i){item=i;} }
    private final AtomicReference<Node<E>> head = new AtomicReference<>();

    void push(E item) {
        Node<E> n = new Node<>(item);
        Node<E> cur;
        do {
            cur = head.get();
            n.next = cur;
        } while (!head.compareAndSet(cur, n));   // retry if head moved
    }

    E pop() {
        Node<E> cur, next;
        do {
            cur = head.get();
            if (cur == null) return null;
            next = cur.next;
        } while (!head.compareAndSet(cur, next));
        return cur.item;
    }
}
```

Pitfalls:

- **ABA**: in a manual-memory language, a popped-and-reused node can make `pop`'s CAS wrongly succeed. Java's GC prevents the *reuse* form (the node stays alive while referenced), but logical ABA can still bite tagged structures — use `AtomicStampedReference` when identity matters.
- **Contention**: under heavy load the single `head` CAS becomes a hotspot (and a false-sharing/cache-line bottleneck). An *elimination backoff stack* pairs concurrent push/pop to relieve it.
- It's lock-free, **not** wait-free — a thread can retry indefinitely.

### Q44. [Theory] What is Amdahl's Law and what does it imply for scaling?

**Amdahl's Law** bounds the speedup from parallelizing a program when a fraction *p* of the work is parallelizable (and *1−p* is inherently serial), on *N* processors:

```
            1
Speedup = ───────────────
          (1 − p) + p / N
```

As N → ∞, speedup is capped at **1 / (1 − p)**. If 10% of the work is serial (p = 0.9), the maximum possible speedup is **10×**, no matter how many cores you throw at it.

```
p = 0.95 → max speedup 20×
p = 0.90 → max speedup 10×
p = 0.50 → max speedup  2×
```

Implication: the **serial fraction dominates** at scale, so optimization effort should target shrinking the serial part, not just adding cores. It also explains diminishing returns and why coordination/contention (which adds to the serial fraction) is so costly. The optimistic counterpart, **Gustafson's Law**, observes that in practice we scale the *problem size* with cores, so the parallel portion grows and effective speedup is more favorable for large workloads.

### Q45. [Practical] How do you diagnose and fix a contended lock that's hurting throughput?

**Diagnose:**

- Profile with a sampling/async profiler; look for threads parked in `lock`/`park` and high time in `BLOCKED`/`WAITING`.
- JFR (Java Flight Recorder) has `jdk.JavaMonitorEnter`/`jdk.ThreadPark` events showing lock contention and the contended monitors.
- Watch for cores under-utilized despite a saturated thread pool — a hallmark of serialization on a lock.

**Fix (in rough order of preference):**

1. **Shrink the critical section** — move I/O, logging, and allocation outside the lock.
2. **Reduce sharing** — partition data (lock striping), use per-thread state combined later (`ThreadLocal`, `LongAdder`).
3. **Switch to non-blocking** structures — `ConcurrentHashMap`, atomic/`VarHandle`-based algorithms.
4. **Use read-write or optimistic locks** (`StampedLock`) if reads dominate.
5. **Change the algorithm** to avoid the shared resource (e.g., sharded counters, message passing).

```java
// Lock striping: split one lock into N to reduce contention
int stripe = key.hashCode() & (locks.length - 1);
synchronized (locks[stripe]) { buckets[stripe].add(key); }
```

### Q46. [Theory] Compare optimistic and pessimistic concurrency control.

- **Pessimistic** — assume conflicts are likely; acquire a lock *before* touching the data so no one else can interfere. Safe but serializes access and risks deadlock/contention. (Mutexes, `SELECT ... FOR UPDATE`.)
- **Optimistic** — assume conflicts are rare; do the work without locking, then *validate* at commit time that nothing changed (via a version number/timestamp/stamp). If validation fails, **retry**. No locks held during the work, so high throughput under low contention, but wasted work and retries under high contention.

```
Pessimistic: lock → work → unlock
Optimistic:  read+version → work → CAS/validate version → commit or retry
```

Examples: `StampedLock.tryOptimisticRead()`, CAS loops, JPA/Hibernate `@Version`, MVCC databases. Choose pessimistic when contention is high or retries are expensive (long transactions); optimistic when conflicts are rare and you want to avoid lock overhead.

### Q47. [Practical] Use StampedLock's optimistic read mode correctly.

```java
import java.util.concurrent.locks.StampedLock;

class Point {
    private double x, y;
    private final StampedLock sl = new StampedLock();

    double distanceFromOrigin() {
        long stamp = sl.tryOptimisticRead();        // no lock acquired
        double cx = x, cy = y;                       // read fields
        if (!sl.validate(stamp)) {                   // a writer intervened?
            stamp = sl.readLock();                   // fall back to real read lock
            try { cx = x; cy = y; }
            finally { sl.unlockRead(stamp); }
        }
        return Math.sqrt(cx * cx + cy * cy);
    }

    void move(double dx, double dy) {
        long stamp = sl.writeLock();
        try { x += dx; y += dy; }
        finally { sl.unlockWrite(stamp); }
    }
}
```

The optimistic path takes **no lock** and is extremely cheap when writes are rare; `validate` checks whether a write happened since the stamp. Caveats: `StampedLock` is **not reentrant**, doesn't support `Condition`, and you must copy fields into locals *before* validating (don't act on torn reads).

### Q48. [Behavioral] Tell me about a time you debugged a difficult concurrency bug in production.

Use a structured **STAR** answer that demonstrates rigor under uncertainty:

- **Situation** — name the symptom: intermittent, low-rate corruption or a hang that only appeared under production load, not in tests. Emphasize *non-determinism* (Heisenbug) as the core difficulty.
- **Task** — your responsibility and the stakes (data integrity, an SLA, customer impact).
- **Action** — the *method*, which is what interviewers grade:
  - Gathered evidence: thread dumps (`jstack`) showing the deadlock cycle or stuck threads, JFR/async-profiler captures, increased logging around the suspect section.
  - Formed a hypothesis (e.g., a check-then-act race, a missing `volatile`, an unsafe iteration) and reproduced it deterministically — stress tests, `jcstress`, fault injection, or pinning thread scheduling.
  - Fixed the *root cause* (lock ordering, atomic compound op, immutability) rather than papering over it with a `sleep` or retry.
  - Verified with a stress harness and added a regression test.
- **Result** — quantify (incidents → zero, throughput unchanged), and the durable lesson (e.g., adopted `jcstress` for concurrent code, or moved the hot path to immutable/message-passing design).

The signal interviewers want: you reason from evidence, reproduce before fixing, and fix causes not symptoms.

### Q49. [Theory] What is priority inversion and how is it solved?

**Priority inversion** happens when a high-priority task is blocked waiting for a resource held by a low-priority task, while a medium-priority task (that doesn't need the resource) preempts the low-priority holder — so the low task can't finish and release the lock, and the high task is stuck behind the medium one. It famously nearly doomed the 1997 Mars Pathfinder mission.

```
High  ──wants lock──► blocked
Low   ──holds lock──► preempted by Medium (can't release!)
Medium ── runs, unrelated, indefinitely
```

Solutions:

- **Priority inheritance** — the low-priority holder *temporarily inherits* the priority of the highest-priority waiter, so it can run, finish, and release the lock.
- **Priority ceiling** — each lock has a ceiling priority (the max of any task that uses it); a task holding it runs at that ceiling, preventing the inversion entirely.

Relevant mostly in real-time systems (RTOS); standard JVM threads on a fair OS scheduler are less exposed but the concept still informs lock-design on latency-critical paths.

### Q50. [Practical] How do you cancel and time out asynchronous tasks cleanly?

Cancellation in Java is **cooperative** — `Thread.interrupt()` sets a flag; the task must check it (or be in a blocking call that throws `InterruptedException`).

```java
// Future cancellation
Future<?> f = pool.submit(task);
boolean cancelled = f.cancel(true);   // true = interrupt if running

// A long task must cooperate:
while (!Thread.currentThread().isInterrupted()) {
    doChunk();                        // check the flag periodically
}

// Timeout on a blocking get
try {
    result = f.get(2, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    f.cancel(true);                   // give up and interrupt
}

// CompletableFuture timeout (Java 9+)
cf.orTimeout(2, TimeUnit.SECONDS)
  .exceptionally(ex -> fallbackValue());
```

Rules: never swallow `InterruptedException` — restore the flag with `Thread.currentThread().interrupt()` or propagate it. Don't use `Thread.stop()` (deprecated, unsafe — it can leave invariants broken). Make tasks check for interruption at reasonable granularity.

### Q51. [Theory] What is structured concurrency and what problem does it solve?

**Structured concurrency** (Java 21+ preview `StructuredTaskScope`, stable in later releases) treats a group of concurrent subtasks as a single unit of work with a defined lifetime: subtasks are spawned within a scope and the scope **does not return until all of them complete** (or are cancelled together). This mirrors how structured *control flow* tamed `goto`.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User>  user  = scope.fork(() -> fetchUser(id));
    Subtask<Order> order = scope.fork(() -> fetchOrder(id));
    scope.join();                 // wait for both
    scope.throwIfFailed();        // propagate the first failure
    return new Response(user.get(), order.get());
}   // scope auto-closes; any still-running child is cancelled
```

It solves the problems of unstructured `ExecutorService` use: **leaked threads** (a task outliving its caller), **lost errors** (a child failure swallowed), and **no automatic cancellation** (if one subtask fails, siblings keep running uselessly). Errors and cancellation propagate predictably along the call hierarchy, and thread relationships become observable.

### Q52. [Theory] How do hazard pointers and epoch-based reclamation enable safe memory reclamation in lock-free code?

In lock-free structures, a thread may hold a pointer to a node another thread wants to free — freeing it would cause use-after-free (and is the root of the memory-reuse ABA problem). Managed languages dodge this with GC, but lock-free libraries (and native code) need explicit **safe memory reclamation (SMR)**:

- **Hazard pointers** — each thread publishes the pointers it's currently using into a per-thread "hazard" slot. A thread wanting to free a node first scans all hazard slots; if the node is hazarded, it defers reclamation. Bounds memory, fine-grained, but adds a per-access store + fence.
- **Epoch-based reclamation (EBR)** — threads announce a global *epoch* when they enter a critical region. Memory retired in epoch *e* is freed only once all threads have advanced past *e*. Lower per-operation overhead than hazard pointers, but a stalled thread can pin an epoch and balloon memory.
- **RCU (read-copy-update)** — readers run lock-free; writers copy, update, and defer reclamation until a *grace period* (all pre-existing readers finished). Ubiquitous in the Linux kernel.

On the JVM you rarely implement these — the GC subsumes them — but they're essential context for off-heap/native lock-free design and explain *why* Java's lock-free structures are simpler than C++'s.

## 🔴 Expert (15+ yrs)

### Q53. [Theory] When would you choose shared-memory threading vs message-passing (actors/CSP) for a large system, and what are the trade-offs?

The decision hinges on the **coupling and contention profile** of your state:

**Shared-memory threading** (locks, atomics, concurrent collections):

- Pros: lowest latency for fine-grained, frequently-shared state; no serialization/copy overhead; direct.
- Cons: data races, deadlock, and visibility bugs; hardest to reason about and test; doesn't scale across machines.
- Fits: tight CPU-bound kernels, hot in-process caches, where shared state is unavoidable and small.

**Message-passing** (actors, CSP, queues):

- Pros: no shared mutable state → no data races by construction; isolation enables "let it crash" supervision; the *same* model scales from threads to machines (location transparency); easier to test (deterministic per-actor).
- Cons: serialization/copy cost; reasoning about asynchronous flows and delivery semantics (ordering, at-least-once vs at-most-once); harder to express tightly-coupled invariants spanning many actors.
- Fits: distributed systems, fault-tolerant services, anything with natural entity boundaries (a session, a device, an order).

A pragmatic architecture is **message-passing at the boundaries** (between services/components, for isolation and scaling) and **carefully-bounded shared-memory concurrency inside** a component's hot path. The anti-pattern is fine-grained locking sprawled across a large codebase — it doesn't compose and becomes unmaintainable.

### Q54. [Theory] How do modern CPU features (store buffers, cache coherence, speculation, NUMA) shape the performance of concurrent code?

Concurrency performance is dominated by the memory hierarchy, not the ALU:

- **Cache coherence (MESI)** — every write to a shared line invalidates copies on other cores; a contended line ping-pongs between cores at tens-to-hundreds of cycles per transfer. This is why false sharing and a single hot atomic are so costly.
- **Store buffers** — writes retire into a buffer and drain asynchronously; this is the hardware source of StoreLoad reordering and why fences (which drain/serialize the buffer) are expensive.
- **Speculative execution** — branch prediction and out-of-order execution hide latency but interact subtly with memory ordering; this is also the root of Spectre/Meltdown-class side channels.
- **NUMA** — on multi-socket servers, memory attached to a remote socket has higher latency and lower bandwidth. A lock or data structure accessed across sockets pays a steep penalty; NUMA-aware allocation and thread pinning matter.

```
register < L1 < L2 < L3 < local DRAM < remote DRAM (NUMA)
  ~1cyc   ~4    ~12   ~40    ~100         ~200+
```

Design implications: minimize shared writes, keep hot data on one cache line *per writer* (and pad to avoid false sharing), prefer per-core/per-thread structures combined lazily (`LongAdder`-style), batch to amortize coherence traffic, and on big iron, be NUMA-aware (pin threads, allocate locally). The fastest concurrent code is the code that shares the least.

### Q55. [Behavioral] How do you establish concurrency-correctness standards across a large engineering org?

This probes technical leadership, not just coding. A strong answer covers:

- **Make the safe path the easy path** — provide vetted, higher-level abstractions (bounded executors, structured concurrency, immutable DTOs, message queues, well-reviewed concurrent utilities) so most engineers never hand-write locks. Most concurrency bugs come from people inventing their own primitives.
- **Codify rules** — guidelines like "favor immutability," "document each class's thread-safety contract (`@ThreadSafe`/`@GuardedBy`)," "no shared mutable static," and "all blocking has a timeout."
- **Tooling and CI** — static analysis (Error Prone, SpotBugs concurrency detectors), `jcstress` for low-level primitives, stress/soak tests, ThreadSanitizer for native code, and race-detecting tests in CI — not relying on code review to catch races by eye.
- **Review and education** — require a second reviewer with concurrency expertise for shared-state changes; run internal training; build a library of postmortems so lessons (the missing `volatile`, the lock-ordering deadlock) become institutional memory.
- **Design reviews** — push concurrency decisions upstream: pick the model (shared-memory vs message-passing) and ownership boundaries at design time, where they're cheap to change.

The meta-point: at scale you can't review your way to correctness — you reduce the *surface area* where engineers can get it wrong, and you make failures observable and reproducible.

### Q56. [Theory] What memory-ordering tools does Java give you beyond `volatile` and `synchronized`, and when would you reach for them?

Java 9+ exposes fine-grained ordering through **`VarHandle`** access modes (and the legacy `Atomic*` lazy/weak methods), letting experts choose the *minimum* barrier needed:

- **Plain** — no ordering guarantees (like a normal field access).
- **Opaque** — guarantees the access happens (no out-of-thin-air, progress visibility) but no ordering w.r.t. other variables. Useful for progress flags where you don't need happens-before.
- **Release/Acquire** — a *release* store pairs with an *acquire* load to form a one-directional happens-before edge, **cheaper than full volatile** because it omits the StoreLoad fence. The standard tool for lock-free publication.
- **Volatile** — full sequential-consistency-style ordering (the strongest, and the default for `volatile` fields).

```java
class Node {
    int val;
    static final VarHandle VAL;   // bound to 'val' via MethodHandles.lookup()
    void publish(int v) { VAL.setRelease(this, v); }   // release store
    int read()          { return (int) VAL.getAcquire(this); }  // acquire load
}
```

Also: `compareAndExchange`, `getAndAdd`, `weakCompareAndSet` (allowed to fail spuriously, cheaper in a retry loop), and `VarHandle.fullFence()`/`acquireFence()`/`releaseFence()` for explicit barriers. Reach for these only in carefully-benchmarked, contention-critical lock-free code — the readability cost is high and the correctness bar (validate with `jcstress`) is unforgiving. For 99% of code, `volatile`/locks/concurrent collections are the right call.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q57. [Theory] What actually happens at the OS level when a thread "blocks" on a lock?

A blocked thread doesn't spin on the CPU — it is **descheduled**. When a thread fails to acquire a contended lock (after any brief adaptive spin), the runtime makes a syscall (e.g., `futex` on Linux) that moves the thread from the **RUNNABLE** state into a **wait queue** associated with the lock, and the OS scheduler picks another runnable thread to run on that core.

```
RUNNABLE ──fail to acquire──► BLOCKED/WAITING (off-CPU, in a futex wait queue)
   ▲                                  │
   └────── lock released, woken ──────┘
```

The cost is a **context switch** (~1–5 µs): save the blocked thread's registers/stack pointer, load another thread's, often flush parts of the TLB/cache. When the lock holder releases, it issues a wake (`futex_wake`) that moves one (or all) waiters back to RUNNABLE; they then compete to be scheduled. This is why blocking is "expensive" — not the waiting itself, but the two context switches (out and back in) plus the cache pollution. It's also why a very short critical section is sometimes better protected by a spinlock: spinning avoids the switch entirely when the wait is shorter than ~2 context switches.

#### Q58. [Theory] What is a memory barrier (fence) really doing at the hardware level?

A **memory barrier** is an instruction that constrains the order in which memory operations become globally visible — it does *not* (by itself) move data; it restricts reordering and forces buffered writes to drain. Concretely:

- A **store barrier** (e.g., `sfence`) ensures all prior stores are flushed from the **store buffer** to cache/memory before later stores.
- A **load barrier** (e.g., `lfence`) ensures prior loads complete before later ones.
- A **full barrier** (e.g., `mfence`, or a `lock`-prefixed instruction on x86) serializes both — crucially it drains the store buffer so a subsequent load can't be satisfied early. This is the expensive **StoreLoad** fence.

```
Without fence:  store X; (sits in store buffer) ; load Y  → load Y may execute first
With StoreLoad: store X; [drain store buffer] ; load Y    → X is globally visible first
```

In the JMM, a `volatile` write compiles (on x86) to a normal store followed by a `lock add $0,(%rsp)` or `mfence` — that's the StoreLoad fence, the priciest part of `volatile`. On weak architectures (ARM) the compiler emits explicit `dmb` barriers. The takeaway: fences cost cycles primarily by *stalling the pipeline and draining the store buffer*, defeating the very out-of-order machinery that hides latency.

#### Q59. [Theory] How does an uncontended `synchronized` lock avoid OS involvement (biased/thin locks)?

The JVM optimizes the overwhelmingly common case where a lock is acquired with **no contention**, so it never touches the OS. Each Java object header has a **mark word** that encodes its lock state, and the JVM uses a hierarchy of progressively heavier representations:

- **Thin / lightweight lock** — on lock entry the JVM CASes a pointer to a stack-allocated lock record into the object's mark word. Acquire and release are a single CAS each, entirely in user space — no syscall.
- **Inflated / heavyweight (monitor) lock** — only when *actual contention* occurs does the JVM "inflate" the lock into a full OS-backed monitor (`ObjectMonitor`) with a wait queue and futex usage.

```
no contention   → thin lock (one CAS, user space)
contention seen → inflate → heavyweight monitor (OS wait queue)
```

Historically there was also **biased locking** (bias the object toward the first thread so even the CAS was skipped), but it was **deprecated in JDK 15 and removed/disabled by default in later JDKs** because modern CAS is cheap and biased locking's revocation cost and complexity no longer paid off. So in 2026 the realistic model is: thin lock by CAS when uncontended, inflated monitor when contended. This is why "just use `synchronized`" is fine for low-contention paths — you pay roughly one CAS.

#### Q60. [Theory] Why is `Thread.sleep(0)` or `Thread.yield()` not a substitute for proper synchronization?

`yield()` and `sleep(0)` are **scheduling hints**, not memory-visibility or mutual-exclusion mechanisms. `yield()` merely suggests the scheduler may run another thread of equal priority; the JVM and OS are free to ignore it entirely. Neither establishes a **happens-before** edge, so they provide **no visibility guarantee** — a write made before a `yield()` is not guaranteed visible to another thread after it.

People reach for them to "fix" a busy-wait or a flaky race, and it sometimes *appears* to work because changing timing perturbs the interleaving that exposed the bug. But it's pure luck: the data race is still there and will resurface under different load, on different hardware, or after a JIT recompile. The correct tools are the ones that create happens-before edges (`volatile`, locks, `CountDownLatch`, `BlockingQueue`) or that block efficiently (`wait`/`Condition`/`park`). If you find yourself adding `yield`/`sleep` to make concurrent code "work," that's a strong signal of a real, unfixed race.

#### Q61. [Practical] What's the difference between `notify()` and `notifyAll()`, and why is `notify()` dangerous?

`notify()` wakes **one** arbitrary thread waiting on the monitor; `notifyAll()` wakes **all** of them (they then re-contend for the lock). The danger with `notify()` arises when **multiple threads wait on the same monitor for different conditions** (or different "kinds" of progress). `notify()` may wake a thread whose condition still isn't satisfiable; that thread re-checks, goes back to `wait()`, and the thread that *could* have proceeded is never woken — a **lost wakeup / stuck system**.

```java
synchronized (lock) {
    while (!myConditionHolds()) {   // always a while-loop
        lock.wait();
    }
    // proceed
}
// signal side:
synchronized (lock) {
    state = ...;
    lock.notifyAll();   // safe default; notify() only if exactly one condition & one waiter kind
}
```

Use `notify()` only when **all waiters are interchangeable** and wait for the **same** condition and you wake at most as many as can proceed. Otherwise `notifyAll()` is the safe default. The modern fix is to use distinct `Condition` objects (one per `ReentrantLock`) so you can `signal()` exactly the right wait set — giving you `notify()`'s efficiency without its hazard.

#### Q62. [Theory] What is the difference between cooperative and preemptive scheduling, and which does the JVM use?

- **Preemptive scheduling** — the scheduler can **forcibly suspend** a running thread at any point (typically on a timer interrupt / time-slice expiry) and switch to another. The running code doesn't have to cooperate.
- **Cooperative scheduling** — a task runs until it **voluntarily yields** (at an `await`/`yield`/blocking point). If a task never yields, it monopolizes the core.

```
Preemptive:   timer tick → forced switch (fairness even for CPU-bound loops)
Cooperative:  switch only at explicit yield points
```

Java's **platform threads** are scheduled **preemptively** by the OS — a tight `while(true){}` loop will still be time-sliced off the core. But Java's **virtual threads** (Loom) are scheduled **cooperatively** onto carrier threads: a virtual thread unmounts at blocking points (I/O, `park`, etc.). This is why a CPU-bound virtual thread that never blocks won't yield its carrier — you can starve other virtual threads, and why Loom is aimed at *I/O-bound* workloads where yield points are frequent. Async/coroutine systems (Node, Python asyncio, Go pre-1.14 to a degree) are largely cooperative, which is also why a single blocking call can stall an event loop.

#### Q63. [Practical] Why must `wait()` always be called inside a `while` loop checking the condition?

Three distinct hazards force the `while` (never `if`):

1. **Spurious wakeups** — the JVM/OS is permitted to wake a `wait()`ing thread *without any `notify()`*. The spec explicitly allows this (it mirrors POSIX `pthread_cond_wait`). Re-checking the condition catches it.
2. **Stolen / stale conditions** — between being notified and re-acquiring the monitor, another thread may have run and consumed the state that made the condition true. By the time you wake and re-lock, the condition can be false again.
3. **`notifyAll()` with multiple conditions** — you may be woken for a condition that isn't yours.

```java
synchronized (queue) {
    while (queue.isEmpty()) {   // re-test on every wakeup
        queue.wait();
    }
    return queue.removeFirst(); // guaranteed non-empty here
}
```

With `if`, the thread proceeds on a wakeup assuming the condition holds — and corrupts state when it doesn't. The `while` loop turns `wait()` into "block until the condition is *actually* true," which is the only correct contract. This is one of the most common concurrency bugs in code reviews.

#### Q64. [Theory] What is the thread lifecycle in Java, and what are the distinct states?

A Java `Thread` moves through six states defined in `Thread.State`:

```
NEW ──start()──► RUNNABLE ⇄ (BLOCKED | WAITING | TIMED_WAITING) ──► TERMINATED
```

- **NEW** — created but `start()` not yet called.
- **RUNNABLE** — eligible to run; note Java collapses "running" and "ready" into one state, and a thread blocked on *I/O* is still RUNNABLE from the JVM's view (the OS knows it's blocked).
- **BLOCKED** — waiting to acquire a **monitor lock** (entering a `synchronized` block held by another thread).
- **WAITING** — indefinitely waiting after `Object.wait()`, `Thread.join()`, or `LockSupport.park()` with no timeout.
- **TIMED_WAITING** — same but with a deadline: `sleep(ms)`, `wait(ms)`, `join(ms)`, `parkNanos`.
- **TERMINATED** — `run()` has completed (normally or via exception).

A key interview subtlety: **BLOCKED** specifically means contention on an intrinsic monitor, whereas waiting on a `ReentrantLock` shows as **WAITING/TIMED_WAITING** (because it parks via `LockSupport`), not BLOCKED. Reading a thread dump correctly requires knowing this distinction.

### 🟡 — extended

#### Q65. [Theory] How does `ConcurrentHashMap` resize without blocking all readers and writers?

Java 8+ `ConcurrentHashMap` performs an **incremental, cooperative resize**. When the table needs to grow, a thread doesn't lock everything and copy; instead:

- A new table of double the size is allocated. Threads that perform writes **help transfer** buckets from the old table to the new one — work is split into stride-sized ranges claimed via a CAS on a shared `transferIndex`.
- A bucket that has been migrated is marked with a special **`ForwardingNode`** whose hash is `MOVED`. A reader hitting a `ForwardingNode` is transparently redirected to look in the new table.
- Writers that land on a not-yet-moved bin help move it first; readers never block — they either read the old bin (still valid) or follow the forwarding pointer.

```
old[ ] ──migrate stride──► new[ ]
old[i] = ForwardingNode(MOVED) → read/write follows it to new table
```

This means resize cost is **shared across the writer threads** that triggered it, reads stay lock-free throughout, and there's no stop-the-world rehash. It's a beautiful example of "help-the-work-in-progress" lock-free design and is why `ConcurrentHashMap` scales far better than `Collections.synchronizedMap`.

#### Q66. [Theory] How does a CLH/MCS-style queue lock work, and why does `AbstractQueuedSynchronizer` use one?

Naive spinlocks have every waiter spinning on the **same** memory location, so each lock handoff invalidates that cache line on *all* cores — coherence-traffic storm. **Queue locks** (CLH and MCS) fix this by having each waiter spin on a **different, local node**, so a handoff touches only one other core's cache line.

- **CLH lock** — waiters form an implicit linked list via a `tail` CAS; each thread spins on its **predecessor's** node's status. Handoff = predecessor flips its own flag → only the successor's cache line is touched.
- **MCS lock** — explicit `next` pointers; each thread spins on **its own** node, and the releaser explicitly signals the successor. Better on NUMA/non-cache-coherent because spinning is purely local.

```
tail → [n3] → [n2] → [n1] (holder)   each spins on its predecessor (CLH) / own node (MCS)
```

Java's **`AbstractQueuedSynchronizer` (AQS)** — the backbone of `ReentrantLock`, `Semaphore`, `CountDownLatch`, `ReentrantReadWriteLock` — uses a **CLH-variant** FIFO queue. Instead of pure spinning, AQS parks blocked threads (`LockSupport.park`) and uses the queue only to decide who to unpark next, combining the cache-friendly, fair, FIFO ordering of CLH with efficient blocking. That's why all those `java.util.concurrent` synchronizers share consistent fairness and interruptibility behavior — they're all thin state machines over one AQS.

#### Q67. [Practical] Show how to implement a custom synchronizer with AbstractQueuedSynchronizer.

`AQS` manages a single `int` **state** plus a wait queue; you implement the `tryAcquire`/`tryRelease` (exclusive) or `tryAcquireShared`/`tryReleaseShared` hooks, and AQS handles queuing, parking, and unparking.

```java
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/** A simple non-reentrant mutual-exclusion lock built on AQS. */
final class Mutex {
    private final Sync sync = new Sync();

    private static final class Sync extends AbstractQueuedSynchronizer {
        @Override protected boolean tryAcquire(int unused) {
            // state 0 = free, 1 = held; CAS 0→1 to acquire
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
        @Override protected boolean tryRelease(int unused) {
            if (getState() == 0) throw new IllegalMonitorStateException();
            setExclusiveOwnerThread(null);
            setState(0);          // release; AQS will unpark the next waiter
            return true;
        }
        @Override protected boolean isHeldExclusively() {
            return getState() == 1;
        }
    }

    public void lock()   { sync.acquire(1); }   // AQS: tryAcquire, else enqueue+park
    public void unlock() { sync.release(1); }   // AQS: tryRelease, then unpark successor
    public boolean tryLock() { return sync.tryAcquire(1); }
}
```

You write only the *state-transition policy*; AQS supplies the hard parts — the lock-free CLH queue, parking/unparking, cancellation, and interruptible/timed variants (`acquireInterruptibly`, `tryAcquireNanos`). This is exactly how `ReentrantLock` (with a reentrancy count in `state`) and `Semaphore` (permits in `state`) are built.

#### Q68. [Theory] What is lock coarsening and lock elision, and how does the JIT apply them?

These are **JIT optimizations** that remove or merge synchronization the compiler can prove is unnecessary or redundant:

- **Lock elision (via escape analysis)** — if the JIT proves a locked object **never escapes** the current thread (it's thread-local, e.g., a `StringBuffer` allocated in a method), the lock can't be contended, so the JIT **removes the locking entirely**.
- **Lock coarsening** — if a method repeatedly locks and unlocks the **same** monitor in a tight sequence (e.g., several `synchronized` calls in a loop), the JIT may **merge them into one larger lock region**, avoiding repeated acquire/release overhead.

```java
// JIT may coarsen these three lock/unlock cycles into one:
sb.append(a);   // each append is synchronized on sb (StringBuffer)
sb.append(b);
sb.append(c);
// Elision: if sb never escapes the method, locking is removed outright.
```

These optimizations are why micro-benchmarks of `synchronized` can be misleading — the cost you measure may have been optimized away. They also explain a guideline: don't manually "optimize" by widening lock scope; let the JIT coarsen, and keep your *source* critical sections minimal for clarity and correctness. (Conversely, coarsening can slightly *increase* contention latency in rare cases, which is why it's heuristic.)

#### Q69. [Theory] What is the difference between a fair and an unfair lock, and why is unfair usually the default?

A **fair lock** grants acquisition in **FIFO arrival order** — the longest-waiting thread goes next. An **unfair (barging) lock** lets a thread that requests the lock at the moment it becomes free **acquire it immediately**, even if other threads have been queued longer.

```
Fair:   strict FIFO → no starvation, but more context switches (hand-off to a parked thread)
Unfair: barging allowed → higher throughput, but a queued thread can be skipped repeatedly
```

`ReentrantLock`, `Semaphore`, and `ReentrantReadWriteLock` default to **unfair**, and `synchronized` is also unfair. The reason is **throughput**: with a fair lock, releasing means you must wake (unpark) the head-of-queue thread and wait for it to be scheduled before it can run — during that gap the lock sits idle. An unfair lock lets a *currently running* thread grab the just-freed lock without a context switch, keeping cores busy. The cost is potential starvation of queued threads under heavy contention. Choose `new ReentrantLock(true)` (fair) only when starvation is a real, observed concern and you accept the throughput hit.

#### Q70. [Practical] How does `ThreadLocal` work internally, and why can it cause memory leaks?

Each `Thread` holds a `ThreadLocal.ThreadLocalMap` — a custom open-addressed hash map whose **keys are `ThreadLocal` instances and values are the per-thread data**. `get()`/`set()` look up the current thread's map, so there's no sharing and no synchronization needed.

The leak hazard comes from how the map references keys: the map's **entry key is a `WeakReference` to the `ThreadLocal`**, but the **value is a strong reference**. If the `ThreadLocal` object becomes unreachable, the key is cleared (becomes `null`) — but the **value is still strongly held** by the thread's map until that slot is reused or the thread dies.

```
Thread → ThreadLocalMap → Entry{ key=WeakRef(ThreadLocal), value=STRONG ref }
                                  key cleared, value stranded = leak
```

In a **thread pool**, threads live for the application's lifetime, so stranded values accumulate forever — a classic production leak (especially with large values or classloader references, which can also leak entire classloaders in app servers). The fix: **always call `remove()`** in a `finally` when done with a pooled thread's thread-local, rather than relying on `set(null)` or GC.

```java
threadLocal.set(expensiveContext);
try { doWork(); }
finally { threadLocal.remove(); }   // mandatory in pooled threads
```

#### Q71. [Theory] How does `CompletableFuture` avoid blocking threads, and what is the risk of the common pool?

`CompletableFuture` models work as a **dependency graph of callbacks**. When you write `cf.thenApply(f)`, you don't block a thread waiting — you **register `f` as a continuation** to run when the upstream stage completes. Completion of one stage triggers its dependents; nothing parks a thread between stages. Blocking only happens if you explicitly call `get()`/`join()`.

The risk is the **default executor**: `*Async` methods without an explicit `Executor` run on the **common `ForkJoinPool`** (`ForkJoinPool.commonPool()`), sized to roughly `availableProcessors() - 1`. If you run **blocking** work there (a JDBC call, `Thread.sleep`, a synchronous HTTP request), you can **exhaust the common pool**, stalling *everything* that shares it — including parallel streams across the whole JVM.

```java
// BAD: blocking I/O on the common pool starves all parallel work
CompletableFuture.supplyAsync(() -> blockingJdbcCall());

// GOOD: give blocking work its own bounded pool
Executor io = Executors.newFixedThreadPool(32);
CompletableFuture.supplyAsync(() -> blockingJdbcCall(), io);
```

Rules: keep the common pool for **CPU-bound, non-blocking** tasks; pass a dedicated `Executor` for anything that blocks; and in 2026, prefer running blocking stages on **virtual-thread executors** so blocking is cheap.

#### Q72. [Practical] What is the difference between `parallelStream()` and a manually managed thread pool, and when does `parallelStream()` hurt?

`parallelStream()` splits a stream's source via a `Spliterator` and processes chunks on the **common `ForkJoinPool`** using fork-join. It's a one-liner for **CPU-bound, easily-splittable, side-effect-free** data parallelism over large collections.

It **hurts** in several cases:

- **Blocking or I/O in the pipeline** — ties up common-pool threads (see Q71), stalling unrelated parallel work JVM-wide. There's no way to pass a custom pool to `parallelStream()` directly (the common trick of wrapping in a `ForkJoinPool.submit().get()` is fragile and discouraged).
- **Small or cheap workloads** — fork/join split + merge overhead exceeds the gain; a sequential stream is faster.
- **Poorly splittable sources** — `LinkedList`, `IntStream.iterate`, or sources without a balanced `Spliterator` parallelize badly.
- **Ordering / stateful lambdas** — order-sensitive operations add merge cost; stateful/shared-mutable lambdas are outright bugs.

```java
// Good fit: large array, pure CPU work
double sum = Arrays.stream(bigArray).parallel().map(Math::sqrt).sum();

// Bad fit: blocking call per element on the shared common pool
urls.parallelStream().map(this::httpGet).collect(toList());  // starves common pool
```

For blocking work or when you need pool control/isolation, use an explicit `ExecutorService` (ideally virtual threads) instead of `parallelStream()`.

#### Q73. [Theory] What guarantees does `final` field semantics provide for safe publication, and how do they differ from `volatile`?

When a constructor finishes normally, the JMM guarantees that any thread which reads the object through a **correctly published reference** will see the **correctly initialized values of its `final` fields** (and anything reachable from them at construction time) — even *without* synchronization on the reading side. This is a special **freeze** action at the end of the constructor that orders the `final`-field writes before the constructor returns.

```java
final class ImmutablePoint {
    final int x, y;
    ImmutablePoint(int x, int y) { this.x = x; this.y = y; }  // final-field freeze
}
// Any thread seeing a published ImmutablePoint sees x,y correctly, no locks needed.
```

Differences from `volatile`:

- `final` guarantees are **one-time, at construction**, and apply only to the `final` fields (and their reachable state at freeze). `volatile` gives an ongoing happens-before edge on **every** read/write of that field.
- `final` does **not** require the *reader* to do anything; `volatile` requires the field to be declared `volatile` and read as such.
- The `final` guarantee can be **defeated** if `this` escapes the constructor (publishing a partially-built object), or for objects mutated after construction.

This is why **immutable objects with `final` fields are inherently thread-safe and freely shareable** — the single most reliable concurrency tool in Java.

### 🟠 — extended

#### Q74. [Theory] What is sequential consistency for data-race-free programs (DRF-SC), and why is it the JMM's central promise?

**DRF-SC** is the foundational guarantee of the Java (and C++11) memory model: *if a program is **data-race-free** — every pair of conflicting accesses (same location, at least one write) is ordered by happens-before — then the program behaves as if it executed under **sequential consistency***, i.e., as some single global interleaving of the threads' operations consistent with each thread's program order.

```
data-race-free  ⇒  observable behavior = some sequentially-consistent interleaving
                   (the messy hardware/compiler reordering becomes invisible)
```

Why it's the central promise: it lets ordinary programmers **reason as if the hardware were simple** (no store buffers, no reordering) *provided* they synchronize enough to eliminate races. You don't have to understand TSO vs ARM weak ordering; you only have to make your program data-race-free using happens-before edges, and the JMM + JVM guarantee SC behavior on every platform. The flip side is the **cliff**: the moment you have *one* data race, you lose SC entirely and get the full weak-memory semantics (including bizarre, non-SC outcomes). There is no "slightly racy but mostly fine" — DRF-SC is all-or-nothing, which is precisely why "it works on my machine" is no defense for a racy program.

#### Q75. [Theory] What is the "out-of-thin-air" (OOTA) problem and why does it complicate relaxed-memory semantics?

**Out-of-thin-air** values are results that appear in a relaxed execution with **no causal justification** — a value that seems to come from nowhere through a self-fulfilling speculative cycle. Consider two threads with relaxed (non-volatile) accesses:

```
Initially x = y = 0
T1: r1 = x;  y = r1;
T2: r2 = y;  x = r2;
```

A naive "any reordering is allowed" model would permit `r1 == r2 == 42`: T1 speculates `x` is 42, writes 42 to `y`, T2 reads 42 from `y` and writes it to `x`, "justifying" T1's speculation. The 42 came from thin air — there's no write of 42 anywhere in the program. Such values are catastrophic because they break **type safety and security invariants** (a reference could materialize from nothing).

The JMM **forbids OOTA**, but — and this is the deep difficulty — **no fully satisfactory formal model both forbids OOTA and permits all the optimizations real compilers do**. The original JSR-133 causality rules are known to be flawed/over-restrictive in edge cases, and C++'s `memory_order_relaxed` has the same open problem. This is an active research area (e.g., the "Promising Semantics" line of work). The practical upshot: OOTA is why you should **never** reason informally about relaxed/plain accesses across threads — use at least release/acquire — and why language memory models remain genuinely hard.

#### Q76. [Practical] How would you implement a lock-free Michael-Scott queue, and what makes its enqueue/dequeue subtle?

The **Michael-Scott queue** is the canonical lock-free FIFO, using two pointers (`head`, `tail`) and a sentinel/dummy node, with CAS-based linking. The subtlety is that `tail` can **lag** behind the true last node, so every operation must be prepared to **help advance `tail`**.

```java
import java.util.concurrent.atomic.AtomicReference;

class MSQueue<E> {
    private static final class Node<E> {
        final E item;
        final AtomicReference<Node<E>> next = new AtomicReference<>();
        Node(E item) { this.item = item; }
    }
    private final AtomicReference<Node<E>> head, tail;

    MSQueue() { Node<E> dummy = new Node<>(null); head = new AtomicReference<>(dummy); tail = new AtomicReference<>(dummy); }

    void enqueue(E item) {
        Node<E> n = new Node<>(item);
        while (true) {
            Node<E> last = tail.get();
            Node<E> next = last.next.get();
            if (last == tail.get()) {                 // tail still consistent?
                if (next == null) {                   // tail really is last
                    if (last.next.compareAndSet(null, n)) {  // link new node
                        tail.compareAndSet(last, n);  // try to swing tail (may fail; that's ok)
                        return;
                    }
                } else {
                    tail.compareAndSet(last, next);   // tail lagged: help advance it
                }
            }
        }
    }

    E dequeue() {
        while (true) {
            Node<E> first = head.get(), last = tail.get(), next = first.next.get();
            if (first == head.get()) {
                if (first == last) {
                    if (next == null) return null;    // empty
                    tail.compareAndSet(last, next);   // help advance a lagging tail
                } else {
                    E item = next.item;
                    if (head.compareAndSet(first, next)) return item;  // dequeue
                }
            }
        }
    }
}
```

The subtleties: (1) enqueue is **two CAS steps** (link the node, then swing `tail`) and another thread may observe the in-between state — hence the "help advance tail" logic; (2) the **dummy node** lets `head` and `tail` move independently and avoids special-casing the empty queue; (3) in a non-GC language this design is the textbook source of **ABA** and needs hazard pointers/tagged pointers — Java's GC removes the reuse hazard. `ConcurrentLinkedQueue` is essentially this algorithm.

#### Q77. [Theory] What is the consensus number of a synchronization primitive, and why does it prove CAS is universal?

From Herlihy's **wait-free hierarchy**, the **consensus number** of a primitive is the maximum number of threads for which that primitive can solve **wait-free consensus** (all threads agree on one value, every thread decides in a bounded number of steps). It's a precise measure of a primitive's synchronization power.

```
atomic registers (read/write)      : consensus number 1   (can't even solve 2-thread consensus)
test-and-set, fetch-and-add, swap  : consensus number 2
compare-and-swap (CAS), LL/SC      : consensus number ∞   (universal)
```

The pivotal results:

- **Atomic read/write registers have consensus number 1** — you provably **cannot** build a wait-free consensus for two threads from loads and stores alone (this is why pure `volatile` flags can't replace CAS for agreement).
- **CAS has consensus number ∞** — it can solve consensus for **any** number of threads.

Because consensus is **universal** (a wait-free consensus object can be used to build a wait-free implementation of *any* sequential object via a universal construction), CAS being ∞ means **CAS is a universal primitive**: any concurrent data structure has a lock-free/wait-free implementation using CAS. This is the theoretical justification for why modern hardware exposes CAS (or LL/SC) and why `java.util.concurrent` is built on `compareAndSet` — it's the minimum hardware primitive sufficient to build everything else.

#### Q78. [Theory] How do RCU (read-copy-update) and its grace-period mechanism achieve near-zero-cost reads?

**RCU** is a synchronization technique optimized for **read-mostly** data where readers must be extremely cheap (ideally zero atomic operations) and writers are rare. Readers never lock and never write shared state; writers publish updates by **copying**, modifying the copy, atomically swapping a pointer, and **deferring reclamation** of the old version until a **grace period** has elapsed.

```
Reader:  rcu_read_lock() [no atomic on most archs] ; deref pointer ; rcu_read_unlock()
Writer:  copy → modify copy → publish (swap pointer) → wait grace period → free old
```

The **grace period** is the crux: it's a window after which **every reader that could possibly have held a reference to the old version has finished**. Once that's guaranteed, the old version can be freed with no risk of use-after-free. The classic implementation detects a grace period by ensuring **every CPU has passed through a quiescent state** (e.g., a context switch or returning to the scheduler) since the update — at which point no pre-existing reader can still be inside a read-side critical section.

Why reads are nearly free: read-side markers compile to little or nothing (no memory barriers on strongly-ordered archs; just a preempt-disable on the classic Linux variant), so reads scale **perfectly** with cores — no shared cache line is written. The cost is shifted entirely onto **writers** (which must wait for grace periods and may briefly keep multiple versions) and onto **memory** (deferred frees). RCU is ubiquitous in the Linux kernel; on the JVM the GC plus copy-on-write (`CopyOnWriteArrayList`) capture the read-mostly idea, with the GC effectively providing the deferred-reclamation guarantee.

#### Q79. [Practical] What is `LockSupport.park()`/`unpark()`, and why is it the foundation of all JUC blocking?

`LockSupport.park()` blocks the current thread; `LockSupport.unpark(thread)` makes a specific thread runnable. They are the **low-level, per-thread blocking primitive** that `AbstractQueuedSynchronizer` (and thus `ReentrantLock`, `Semaphore`, `CountDownLatch`, `CompletableFuture`'s waiters, etc.) is built on. Their defining feature is a **permit** model that eliminates the lost-wakeup race that plagues `wait`/`notify`:

```java
// park consumes a permit if available; otherwise blocks until unpark grants one
// unpark grants a permit (at most one — it doesn't accumulate)
Thread t = Thread.currentThread();
// thread A:
LockSupport.park();          // blocks unless a permit is already available
// thread B (any time, even BEFORE A parks):
LockSupport.unpark(t);       // pre-issued permit → A's park returns immediately
```

Key properties that make it superior to `wait`/`notify` for building synchronizers:

- **No lock required** — unlike `Object.wait()`, `park()` doesn't need to hold a monitor, so it composes with lock-free queue logic.
- **`unpark` before `park` is safe** — the permit "remembers" the signal, so there's no missed-wakeup window. (With `notify` before `wait`, the signal is lost.)
- **Spurious wakeups allowed** — so callers loop on a condition, exactly like `wait`.
- **Targeted** — you unpark a *specific* thread, enabling FIFO hand-off in AQS's queue.

This permit-based, lock-free-friendly, targeted blocking is precisely what AQS needs to implement fair queues: enqueue a node lock-free, `park`, and have the predecessor `unpark` exactly the right successor on release.

#### Q80. [Theory] Why can't you build a correct mutual-exclusion lock for two threads from plain reads and writes without fences (Dekker/Peterson caveat)?

**Peterson's and Dekker's algorithms** are famous proofs that mutual exclusion is *theoretically* achievable using only atomic reads and writes (no special instructions) on a **sequentially consistent** machine:

```
// Peterson, simplified (thread i, other j)
flag[i] = true;          // I want in
turn   = j;              // but you go first
while (flag[j] && turn == j) { /* spin */ }
// critical section
flag[i] = false;
```

The catch — and the reason this is a deep interview question — is that the correctness proof **assumes sequential consistency**, which **no real CPU provides** for plain accesses. On a machine with a **store buffer** (every modern CPU), the write `flag[i] = true` can sit in the store buffer while the subsequent **load** of `flag[j]` executes — the classic **StoreLoad reordering**. Both threads then read a stale `false` for the other's flag and **both enter the critical section**. Peterson's algorithm is *broken* on real hardware unless you insert a **StoreLoad memory fence** between the flag-store and the flag-load.

```
flag[i] = true;
storeLoadFence();        // <-- REQUIRED on real hardware (e.g., volatile semantics in Java)
while (flag[j] && turn == j) ...
```

In Java you'd make `flag`/`turn` **`volatile`**, which inserts the needed barriers — but then you're relying on the JMM's fences anyway. The lesson: classic shared-memory mutex algorithms are correct *only* under SC; on weakly-ordered hardware they require explicit fences, which is exactly why real systems use hardware atomics (CAS, test-and-set) or `volatile`-with-fences rather than pure read/write protocols.

### 🔴 — extended

#### Q81. [Theory] How does a generational/concurrent GC (G1, ZGC, Shenandoah) interact with concurrency, and what are read/write barriers in this context?

Modern low-pause collectors do most of their work **concurrently with application ("mutator") threads**, which creates its own concurrency problem: the heap is being *changed* while it's being *traced*. They solve it with **GC barriers** — small snippets the JIT injects around heap reads/writes (distinct from *memory* barriers, though related):

- **Write barriers** (G1) — on each reference store, record cross-region pointers in **remembered sets** and feed the **SATB (snapshot-at-the-beginning)** marking to ensure no live object is missed when references are overwritten during concurrent marking.
- **Load/read barriers** (ZGC, Shenandoah) — on each reference **load**, check **colored pointers** (ZGC) or a **forwarding pointer / Brooks pointer** (Shenandoah) so the mutator transparently follows objects that the concurrent collector is **relocating**. This lets the GC move objects without stopping the world.

```
ZGC load barrier: ref = load(field); if (bad-color(ref)) ref = heal/relocate(ref);
```

Concurrency implications for application authors:

- **Throughput tax** — every reference load/store carries a small barrier cost; this is the price of sub-millisecond pauses. Reference-heavy, highly-concurrent code pays more.
- **Pauseless ≠ free** — ZGC/Shenandoah trade ~10–15% throughput for pause times independent of heap size, which is usually the right trade for latency-sensitive concurrent services.
- **Allocation is a contention point** — concurrent allocators use **thread-local allocation buffers (TLABs)** so threads bump-allocate without coordinating; TLAB sizing matters under many-threaded allocation-heavy load.

In 2026, **generational ZGC** (default-capable, generational since JDK 21) is the typical choice for large-heap, highly-concurrent, latency-critical JVM services; the mental model is "the GC is just another set of concurrent threads coordinating with yours via barriers."

#### Q82. [Theory] What is the difference between linearizability and sequential consistency as correctness conditions for concurrent objects?

Both are **consistency conditions** for concurrent objects, but they differ in whether they respect **real-time ordering**:

- **Sequential consistency (SC)** — there exists a single sequential order of all operations that (a) is consistent with each thread's **program order**, and (b) every operation sees the results of that order. It does **not** require respecting real time across threads — an operation that finished before another *began* (in wall-clock time) may still be ordered after it.
- **Linearizability** — stronger: each operation appears to take effect **instantaneously at some point (the "linearization point") between its invocation and its response**, and the resulting order is consistent with **real time**. If operation A completes before operation B starts, A *must* be ordered before B.

```
SC:            respects per-thread program order; ignores cross-thread real time
Linearizable:  respects program order AND real-time precedence (stronger)
```

The crucial practical differences:

- **Linearizability is *local* (composable)**: a system composed of individually linearizable objects is itself linearizable. **SC is *not* composable** — combining two SC objects can yield a non-SC system. This composability is why linearizability is the gold standard for concurrent data-structure correctness (and what `ConcurrentHashMap` operations, atomics, etc. aim for).
- Linearizability matches programmer intuition ("once a write returns, everyone sees it"), which is why it underpins strong consistency in **distributed systems** too (it's the single-object case of strong/strict consistency; in distributed terms, linearizability ≈ the "C" in CAP at the register level).

Interview signal: knowing that **linearizability ⊃ sequential consistency**, that linearizability is composable while SC is not, and that linearization points are *the* way to argue a lock-free structure is correct.

#### Q83. [Theory] How do transactional memory (HTM/STM) and primitives like Intel TSX change the lock-free landscape, and why hasn't HTM taken over?

**Transactional memory** lets you mark a block of code as a **transaction** that executes **atomically and in isolation**; the system either **commits** all its effects at once or **aborts** and rolls back, retrying or falling back to a lock. It promises lock-like simplicity with optimistic-concurrency performance.

- **HTM (hardware)** — e.g., Intel **TSX** (`RTM`/`HLE`), IBM POWER. The CPU tracks a transaction's read/write set in cache; a conflicting access from another core triggers an **abort**. Best-known interface: speculate in a transaction; on abort, fall back to a real lock (**lock elision**).
- **STM (software)** — implements transactions in a runtime/library (versioned locks, read/write logs). Flexible and unbounded, but high overhead (instrumented reads/writes), which has kept it largely in research/Haskell-STM niches.

Why HTM hasn't taken over despite ~15 years of availability:

- **Bounded by cache** — a hardware transaction's footprint must fit in cache; touch too much memory, take an interrupt, or execute certain instructions (syscalls, some faults) and it **aborts unconditionally**, so you *always* need a non-transactional fallback path. You can't rely on transactions alone.
- **Abort costs and pathologies** — high-conflict workloads can **livelock** between speculative retries, often performing *worse* than a plain lock; tuning the fallback policy is subtle.
- **Security and reliability fallout** — Intel TSX became a vehicle for side-channel attacks (TAA/ZombieLoad-class) and microcode bugs, leading Intel to **disable TSX by default** on many parts via microcode. That eroded its dependability as a platform primitive.
- **CAS/JUC already "good enough"** — fine-grained lock-free structures and well-engineered locks capture most of the benefit without HTM's fragility.

The net 2026 picture: HTM is a useful *accelerator* for lock elision in specific high-read, low-conflict cases (and ARM's TME is emerging), but the universal CAS-based lock-free toolkit plus low-overhead locks remain the mainstream. STM survives mostly where its composability (e.g., `retry`/`orElse` in Haskell) is uniquely valuable.

#### Q84. [Theory] What is the memory_order spectrum (relaxed/acquire/release/acq_rel/seq_cst) and how does it map onto Java's VarHandle access modes?

C++11 introduced an explicit **memory-order taxonomy** for atomics that the rest of the industry (including Java's `VarHandle`) now mirrors. From weakest to strongest:

```
relaxed   : atomicity only, no inter-thread ordering            (Java: Plain/Opaque)
consume   : data-dependency ordering (practically deprecated)
acquire   : a load that prevents later ops moving before it     (Java: getAcquire)
release   : a store that prevents earlier ops moving after it   (Java: setRelease)
acq_rel   : both, for read-modify-write atomics                 (Java: compareAndExchange*)
seq_cst   : single total order across all seq_cst ops (strongest)(Java: Volatile mode)
```

The **release/acquire pairing** is the workhorse: a `release` store **publishes** everything sequenced before it; a matching `acquire` load on the same variable **acquires** that published state, forming a one-directional happens-before edge — **without** the expensive **StoreLoad** fence that `seq_cst`/`volatile` requires. That's why release/acquire is cheaper than full `volatile` and is the right tool for lock-free publication.

Java mapping via `VarHandle` (JDK 9+):

| C++ memory_order | Java VarHandle mode | Guarantee |
|---|---|---|
| relaxed | `get`/`set` (Plain), `getOpaque`/`setOpaque` (Opaque) | atomicity / progress, no cross-var ordering |
| acquire | `getAcquire` | acquire load |
| release | `setRelease` | release store |
| acq_rel | `compareAndExchangeAcquire/Release` | RMW with one-sided ordering |
| seq_cst | `getVolatile`/`setVolatile`, `compareAndSet` | full SC ordering |

**Opaque** (Java-specific) sits between relaxed and acquire/release: it guarantees the access is a single coherent operation visible in finite time (no out-of-thin-air, useful for progress/cancel flags) but imposes no ordering on *other* variables. The expert lesson: choose the **weakest mode that still establishes the happens-before edges your invariant needs** — and validate with **jcstress**, because reasoning about anything below `seq_cst` is error-prone and the OOTA pitfalls (Q75) are real.

#### Q85. [Practical] How do virtual threads get scheduled, what is "pinning," and how do you diagnose and avoid it?

**Virtual threads** (Loom) are scheduled by a dedicated **`ForkJoinPool` in FIFO mode** (the default scheduler), whose worker threads are the **carrier** (platform) threads — by default `Runtime.availableProcessors()` of them. A virtual thread runs by being **mounted** on a carrier; when it hits a blocking point that Loom understands (most `java.util.concurrent`/NIO/socket blocking), it **unmounts**, freeing the carrier to run another virtual thread, and is remounted (possibly on a different carrier) when ready.

**Pinning** is when a virtual thread **cannot unmount** while blocked, so it **holds its carrier hostage** — defeating the scalability point. The two classic causes:

```
1. Blocking inside a `synchronized` block/method  (the JVM cannot unmount across a monitor it owns)
2. Blocking inside a native frame / foreign (JNI/FFM downcall) stack
```

If enough virtual threads pin simultaneously, you can **exhaust all carriers** and effectively deadlock or serialize the system, even though "millions of virtual threads" should be cheap.

Diagnosis and avoidance:

- **Diagnose**: run with `-Djdk.tracePinnedThreads=full` (older builds) to log pinning stacks, or use **JFR's `jdk.VirtualThreadPinned` event** (the modern, lower-overhead path) to find exactly which `synchronized`/native frame pinned and for how long.
- **Avoid (synchronized case)**: replace `synchronized` guarding a blocking call with a **`ReentrantLock`** (which Loom *can* unmount across), or move the blocking I/O outside the `synchronized` region so the monitor is held only briefly and non-blockingly.
- **2026 note**: **JDK 24 (JEP 491)** largely **eliminated `synchronized`-induced pinning** by reworking monitor handling so virtual threads can unmount while holding a monitor in most cases — so on recent JDKs the dominant remaining pinning cause is **native/FFM frames**. Still avoid long-held monitors around blocking calls, and never pool virtual threads.

```java
// Pre-JDK-24 hazard (pins the carrier across the blocking call):
synchronized (lock) { blockingIo(); }

// Loom-friendly: ReentrantLock unmounts cleanly while blocked
lock.lock();
try { blockingIo(); }
finally { lock.unlock(); }
```

#### Q86. [Theory] What is "performance asymmetry" between scalability and latency in concurrent systems, and how do contention and coherence costs create it?

A deep systems-design point: making a concurrent system **scale** (more throughput with more cores) and making it **low-latency** (fast individual operations) are often **in tension**, because the techniques that add cores add **coordination**, and coordination is fundamentally a **serial, coherence-bound** cost.

The mechanism:

- Every synchronization point that touches a **shared cache line** (a lock word, an atomic counter, a queue head) forces **cache-coherence traffic** — the line ping-pongs between cores at tens-to-hundreds of cycles each transfer (Q54). This cost **grows with core count** even when the logical work doesn't.
- **Amdahl + coherence**: contention adds to the *serial fraction* (Q44), so beyond some core count, adding threads **increases** latency (queueing, coherence storms) while throughput **plateaus or regresses** — the classic "scalability collapse" / retrograde throughput curve seen with a single hot lock or atomic.

```
throughput
   ▲          ____ plateau
   │        /      \___ collapse (coherence storm / lock convoy)
   │      /
   └──────────────────────►  threads
```

Design responses that resolve the asymmetry by **eliminating sharing rather than synchronizing it faster**:

- **Sharding / partitioning** state so each core owns its data (no shared line) — `LongAdder`'s striped cells, per-core queues, sharded counters.
- **Batching / combining** (flat combining, elimination) to amortize coherence traffic across many operations.
- **Replication for reads** (RCU, copy-on-write) so reads never write shared lines.
- **Backpressure and admission control** to keep the system left of the collapse point rather than pushing past it.
- **NUMA-aware placement** so hot data and the threads using it sit on the same socket.

The expert framing: throughput scalability is won by **reducing the cardinality of shared, mutated cache lines**, and latency is won by **keeping critical sections and coherence round-trips off the hot path**. The fastest *and* most scalable concurrent code is, once again, the code that **shares the least** — and recognizing when you've hit a coherence-bound wall (rather than a CPU-bound one) is what separates senior performance work from naive "add more threads."

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q87. [Practical] Your service's request count is wrong under load — multiple threads do `count++` on a plain `int`. Walk through diagnosing and fixing it.

The symptom — a counter that reads *lower* than the number of increments — is the textbook fingerprint of a lost-update race on a non-atomic read-modify-write. Diagnosis: confirm the field is a plain `int`/`long` shared across threads with no synchronization, and that the under-count grows with thread count (more interleavings, more lost updates).

```java
// BROKEN: count++ is read → add → write, not atomic
class Metrics { int count; void hit() { count++; } }   // loses updates

// FIX 1 (best for a pure counter): LongAdder — striped, low contention
import java.util.concurrent.atomic.LongAdder;
class Metrics { final LongAdder count = new LongAdder();
    void hit() { count.increment(); } long total() { return count.sum(); } }

// FIX 2 (need an exact atomic value frequently): AtomicLong
import java.util.concurrent.atomic.AtomicLong;
class Metrics2 { final AtomicLong count = new AtomicLong();
    void hit() { count.incrementAndGet(); } }
```

For a hot metrics counter prefer `LongAdder`: it spreads writes across padded cells, avoiding both the single-CAS hotspot and false sharing. Reach for `AtomicLong` only when you need a precise value on every read (e.g., ID generation). Never "fix" it with `volatile` alone — `volatile` gives visibility, not atomicity, so `count++` is still racy.

#### Q88. [Practical] You suspect a deadlock in a running JVM but have no profiler attached. How do you confirm it from a thread dump?

Take a thread dump (`jstack <pid>`, `jcmd <pid> Thread.print`, or `kill -3 <pid>` to stdout). The JVM's deadlock detector scans monitor and `AbstractQueuedSynchronizer` ownership and prints an explicit `Found one Java-level deadlock:` section listing the cycle of threads, each waiting to lock a monitor held by the next.

```
Found one Java-level deadlock:
=============================
"worker-1": waiting to lock monitor 0x... (a Account), which is held by "worker-2"
"worker-2": waiting to lock monitor 0x... (a Account), which is held by "worker-1"
```

If there's no explicit deadlock report, look for many threads `BLOCKED` on the same lock (a *convoy*, not a deadlock) or all `WAITING`/`TIMED_WAITING` (possibly a missed signal or a thread-pool starvation deadlock the detector can't see — e.g., a task in a fixed pool blocked waiting on another task that can never be scheduled). Take **two dumps a few seconds apart**: if the same threads sit on the same locks with unchanged stacks, it's stuck, not slow.

#### Q89. [Practical] A `while (!stopped) { ... }` loop never exits after another thread sets `stopped = true`. Why, and how do you fix it?

Without `volatile`, the JIT may hoist the read of `stopped` out of the loop (a legal optimization for a non-shared field) so the thread spins on a cached value forever, never observing the other thread's write. There is no happens-before edge making the write visible.

```java
// BROKEN: no visibility guarantee
private boolean stopped = false;
void run()  { while (!stopped) { work(); } }   // may loop forever
void stop() { stopped = true; }

// FIX: volatile establishes a happens-before edge on each read
private volatile boolean stopped = false;
```

`volatile` forces each read to go to main memory and forbids hoisting. For cancellation specifically, prefer the **interrupt** mechanism (`Thread.interrupt()` + checking `Thread.currentThread().isInterrupted()`), which also wakes blocking calls like `take()`/`sleep()` that a plain flag cannot interrupt.

#### Q90. [Practical] You get `ConcurrentModificationException` iterating an `ArrayList` while another thread (or the same loop) modifies it. How do you fix it?

`ConcurrentModificationException` comes from the fail-fast iterator's modCount check — the list changed structurally during iteration. It's not strictly a *threading* exception (a single thread removing inside an enhanced-for triggers it too), but concurrent writers reliably cause it.

```java
// BROKEN: structural modification during iteration
for (String s : list) { if (bad(s)) list.remove(s); }   // CME

// FIX A (single thread): Iterator.remove()
for (var it = list.iterator(); it.hasNext(); )
    if (bad(it.next())) it.remove();

// FIX B (single thread): removeIf
list.removeIf(s -> bad(s));

// FIX C (concurrent, read-heavy): CopyOnWriteArrayList — iterators see a snapshot
List<String> list = new java.util.concurrent.CopyOnWriteArrayList<>();
```

For genuinely concurrent access pick a concurrent collection: `CopyOnWriteArrayList` for read-mostly lists (writers copy the array; iterators are snapshot and never throw), or `ConcurrentHashMap`/`ConcurrentLinkedQueue` for maps/queues. Wrapping with `Collections.synchronizedList` makes individual ops atomic but you must still `synchronized` around iteration manually.

#### Q91. [Coding] Write a thread-safe bounded counter that never exceeds a maximum, returning whether the increment succeeded.

```java
import java.util.concurrent.atomic.AtomicInteger;

class BoundedCounter {
    private final AtomicInteger value = new AtomicInteger(0);
    private final int max;

    BoundedCounter(int max) { this.max = max; }

    /** Atomically increment if below max; return true on success. */
    boolean tryIncrement() {
        int cur;
        do {
            cur = value.get();
            if (cur >= max) return false;        // at capacity, give up
        } while (!value.compareAndSet(cur, cur + 1));  // retry if someone raced us
        return true;
    }

    int get() { return value.get(); }
}
```

The CAS loop is the key: a naive `if (get() < max) incrementAndGet()` has a check-then-act race where two threads both pass the check and overshoot `max`. The `compareAndSet` makes the "still below max" test and the increment a single atomic step; on contention we re-read and re-test. Java 9+ offers `value.accumulateAndGet(1, (v,d) -> v < max ? v+d : v)` as a one-liner equivalent.

#### Q92. [Practical] A `HashMap` shared between threads occasionally returns wrong values, throws, or even (historically) spins at 100% CPU. What's happening and what do you use instead?

`java.util.HashMap` is not thread-safe. Concurrent `put`s can corrupt the bucket structure: lost entries, `NullPointerException`, or — on pre-Java-8 implementations during resize — a circular linked list that makes `get` spin forever, pinning a CPU at 100%. Java 8 changed resize to use tree-bins/tail-insertion so the infinite-loop is gone, but corruption and lost updates remain.

```java
// BROKEN: HashMap under concurrent writes
Map<K,V> m = new HashMap<>();

// FIX: ConcurrentHashMap — bin-level locking + CAS, lock-free reads
Map<K,V> m = new java.util.concurrent.ConcurrentHashMap<>();
m.computeIfAbsent(k, this::load);   // atomic, computed at most once per key
```

Use `ConcurrentHashMap` for concurrent access. Don't reach for `Collections.synchronizedMap` unless you need a single global lock — it serializes everything and you *still* must synchronize manually around iteration. And remember: even with `ConcurrentHashMap`, compound sequences like `if (!m.containsKey(k)) m.put(k,v)` race — use `putIfAbsent`/`computeIfAbsent`.

#### Q93. [Practical] Your `ExecutorService`-based app won't exit at shutdown; the JVM hangs. What's the likely cause and the correct shutdown sequence?

A `ThreadPoolExecutor` from `Executors` uses **non-daemon** threads by default, so the JVM won't exit while the pool is alive and you never called `shutdown()`. The fix is an explicit, ordered shutdown that also handles in-flight tasks.

```java
void shutdownGracefully(ExecutorService pool) {
    pool.shutdown();                       // stop accepting new tasks
    try {
        if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
            pool.shutdownNow();            // interrupt running tasks
            if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                System.err.println("Pool did not terminate");
        }
    } catch (InterruptedException e) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

`shutdown()` lets queued tasks finish; `shutdownNow()` interrupts running tasks and returns the un-started queue — but it only *interrupts*, so tasks must actually respond to interruption to stop. Tasks that swallow `InterruptedException` will keep the pool alive. Using a try-with-resources `ExecutorService` (Java 19+) or virtual-thread-per-task executor auto-closes and joins on block exit.

#### Q94. [Coding] Implement a thread-safe lazy singleton, and explain why the simplest correct version needs no `volatile`.

```java
// Initialization-on-demand holder idiom: lazy, thread-safe, no volatile, no lock in get()
class Config {
    private Config() { /* expensive load */ }

    private static class Holder {                 // not loaded until first use
        static final Config INSTANCE = new Config();
    }

    static Config get() { return Holder.INSTANCE; }  // class-init lock publishes safely
}
```

The JVM guarantees a class is initialized **lazily** (on first active use of `Holder`) and **exactly once** under an internal class-initialization lock, with a happens-before edge so the fully-constructed `INSTANCE` is safely published. That's why no `volatile` and no double-checked locking are needed — the classloader does the synchronization. Use double-checked locking with a `volatile` field only when the instance depends on runtime parameters and can't be a static holder.

#### Q95. [Practical] How do you reproduce an intermittent race condition reliably enough to debug it?

Races are timing-dependent, so the goal is to widen and vary the interleaving window:

- **Stress with many threads and iterations** — run the contested operation from dozens of threads, millions of times, and assert an invariant (e.g., counter equals expected). Failures that are 1-in-10⁶ become near-certain.
- **Inject scheduling jitter** — sprinkle `Thread.yield()` or tiny randomized sleeps around the suspect critical region in a debug build to expose interleavings.
- **Use a `CyclicBarrier`** to release all threads at the exact same instant, maximizing the chance of a simultaneous collision.
- **Run on weak hardware** — ARM (Apple Silicon, Graviton) exposes reordering bugs that x86's strong TSO model hides; many "works on my machine" races only fail on ARM.
- **Deterministic tools** — `jcstress` (the JVM concurrency stress harness) is purpose-built to probe memory-model corner cases; thread sanitizers and `-Xcomp` force-compile to change JIT timing.

```java
CyclicBarrier start = new CyclicBarrier(THREADS);
Runnable t = () -> { start.await(); for (int i=0;i<N;i++) shared.op(); };
// ... start THREADS, join all, then assert invariant
```

The mindset: a race that "can't be reproduced" usually just hasn't been stressed; build a harness that asserts an invariant under load rather than eyeballing logs.

#### Q96. [Practical] Two threads call `synchronized` methods on the *same* object and you expected parallelism but see serialization. Why, and is that a bug?

That's not a bug — it's the contract. `synchronized` instance methods all lock on `this`, so any two `synchronized` methods of the same object are mutually exclusive: only one thread runs *any* of them at a time. If you wanted concurrency between unrelated operations, they're sharing a lock they shouldn't.

```java
// Both methods lock on `this` → callers serialize even though they touch different data
class Service {
    synchronized void updateA() { a++; }   // contends with updateB
    synchronized void updateB() { b++; }
}

// FIX: separate lock objects for independent state (lock splitting)
class Service2 {
    private final Object lockA = new Object(), lockB = new Object();
    private int a, b;
    void updateA() { synchronized (lockA) { a++; } }
    void updateB() { synchronized (lockB) { b++; } }
}
```

This is **lock splitting**: give independent invariants independent locks so they don't contend. Conversely, if `a` and `b` must change together to maintain an invariant, they *should* share one lock — serialization is then correct. Choose lock granularity to match the data's consistency boundaries.

### 🟡 — extended

#### Q97. [Practical] A fixed thread pool deadlocks even though there's no lock cycle — tasks just hang. What is "thread-pool starvation deadlock" and how do you avoid it?

If a task submitted to a bounded pool **blocks waiting on the result of another task submitted to the same pool**, and the pool has no free thread to run that dependency, you get a starvation deadlock no lock-cycle detector will report. With a pool of N threads, N tasks all blocking on subtasks that need a free thread will hang forever.

```java
// DEADLOCK with newFixedThreadPool(1): outer task blocks on inner task
//   that can never be scheduled (the single thread is busy with outer).
ExecutorService pool = Executors.newFixedThreadPool(1);
Future<Integer> outer = pool.submit(() -> {
    Future<Integer> inner = pool.submit(() -> 42);
    return inner.get();   // blocks forever — no thread left for inner
});
```

Fixes: (1) **never block in a pooled task on another task in the same pool** — restructure with `CompletableFuture` composition (`thenCompose`) so dependent stages don't hold a thread while waiting; (2) use **separate pools** for the producing and consuming stages so they can't starve each other; (3) use **virtual threads** (`newVirtualThreadPerTaskExecutor`) where blocking unmounts the carrier thread, sidestepping the problem entirely. ForkJoinPool's managed blocking (`ForkJoinPool.ManagedBlocker`) is another escape hatch for legitimate blocking inside the pool.

#### Q98. [Practical] CPU shows 100% but throughput is terrible. How do you tell a lock convoy / busy-spin from genuine CPU-bound work?

Distinguish *useful* CPU from *coordination* CPU:

- **Thread dump pattern** — many threads `RUNNABLE` but stuck in the same `compareAndSet`/spin loop, or many `BLOCKED` on one monitor, points to contention, not computation.
- **Profiler / JFR** — an async profiler flame graph dominated by `LockSupport.park`, `Unsafe.compareAndSwap`, or a single hot `synchronized` frame signals a convoy; CPU in your actual business methods signals real work.
- **`perf`/PMU counters** — high cache-coherence traffic (`L2/L3 misses`, `MESI` invalidations) with low IPC (instructions-per-cycle) means cores are stalling on cache-line ping-pong (false sharing or a hot atomic), not crunching.
- **Scaling test** — if adding threads *reduces* throughput (retrograde curve), you've hit a coherence/contention wall, not a CPU wall.

A lock convoy looks like 100% CPU because spinning and context-switch churn burn cycles, but IPC is low and the work isn't progressing. The fix is to reduce sharing (striping, `LongAdder`, partitioning), not to add cores — adding cores makes a coherence-bound bottleneck *worse*.

#### Q99. [Practical] A `BlockingQueue`-based producer/consumer hangs at shutdown — consumers never stop. How do you shut it down cleanly?

The consumers are parked in `queue.take()` (blocking until an element arrives), so simply setting a `running = false` flag does nothing — they're asleep inside `take()`, not checking the flag. You need to either interrupt them or send an explicit sentinel.

```java
// Approach A: poison-pill sentinel — one per consumer
static final Object POISON = new Object();
void stop(int consumers) throws InterruptedException {
    for (int i = 0; i < consumers; i++) queue.put(POISON);   // each consumer eats one and exits
}
// consumer loop:
while (true) { Object item = queue.take(); if (item == POISON) break; process(item); }

// Approach B: interrupt the consumer threads; take() throws InterruptedException
consumerThreads.forEach(Thread::interrupt);
// consumer: catch InterruptedException → restore flag, exit loop
```

Use a **poison pill** when you want in-flight queued work drained first (graceful) — push exactly one sentinel per consumer so each wakes, sees it, and exits. Use **interruption** for immediate cancellation; the blocked `take()` throws `InterruptedException`, so catch it, restore the interrupt flag, and exit. The anti-pattern is a plain boolean flag: a thread blocked in `take()`/`put()` can't observe it.

#### Q100. [Coding] Implement a simple thread-safe object pool with a fixed capacity that blocks when exhausted.

```java
import java.util.concurrent.*;

class ObjectPool<T> {
    private final BlockingQueue<T> pool;

    ObjectPool(java.util.Collection<T> items) {
        // Bounded queue pre-loaded with all reusable objects
        this.pool = new ArrayBlockingQueue<>(items.size(), false, items);
    }

    /** Borrow an object, blocking up to timeout if none free. */
    T borrow(long timeout, TimeUnit unit) throws InterruptedException {
        T obj = pool.poll(timeout, unit);
        if (obj == null) throw new IllegalStateException("pool exhausted");
        return obj;
    }

    /** Always return in a finally block. */
    void release(T obj) {
        pool.offer(obj);   // non-blocking; capacity == number of objects so never full
    }
}
```

Usage must be `try { obj = pool.borrow(...); use(obj); } finally { pool.release(obj); }` — a leaked object permanently shrinks the pool and eventually starves all callers (the classic connection-pool exhaustion bug). `BlockingQueue` gives the wait/signal and capacity bound for free; the timeout on `borrow` converts "wait forever" into a diagnosable failure. A `Semaphore`-guarded `ConcurrentLinkedQueue` is the equivalent hand-rolled approach.

#### Q101. [Practical] You see `RejectedExecutionException` under load from a `ThreadPoolExecutor`. What does it mean and how do you handle it correctly?

The executor rejected a task because its **bounded queue is full and all core+max threads are busy** (or the pool is shutting down). This is actually backpressure working as designed — it's telling you submission outpaces completion. The wrong fix is an unbounded queue (hides the problem until OOM); the right fix is a deliberate rejection policy.

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    8, 16, 60, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(1000),                 // bounded → real backpressure
    new ThreadPoolExecutor.CallerRunsPolicy());      // submitter runs the task → throttles producers
```

`CallerRunsPolicy` makes the submitting thread execute the task itself, naturally slowing producers (a self-tuning throttle). Alternatives: `AbortPolicy` (default — throw, let the caller retry/shed), `DiscardPolicy`/`DiscardOldestPolicy` (drop work — only acceptable for lossy telemetry). Tune queue size and max threads to your latency budget; an unbounded `LinkedBlockingQueue` (what `newFixedThreadPool` uses) means tasks *never* get rejected but memory grows without limit — a far worse failure mode.

#### Q102. [Practical] A method reads two related fields without locking and occasionally sees an inconsistent pair (e.g., balance and currency mismatched). What's wrong and how do you fix it?

Even with each field individually `volatile`, reading them in two separate steps is **not atomic**: a writer can update field B between your read of A and your read of B, so you observe a torn, never-actually-valid combination. Visibility is not the same as atomicity-of-a-compound-read.

```java
// BROKEN: two volatiles don't give an atomic snapshot
volatile long balance; volatile String currency;
Money read() { return new Money(balance, currency); }  // can mix old+new

// FIX A: immutable value object behind one volatile/atomic reference
record Money(long balance, String currency) {}
private final AtomicReference<Money> state = new AtomicReference<>(...);
Money read()  { return state.get(); }                  // atomic, consistent pair
void update(java.util.function.UnaryOperator<Money> f) { state.updateAndGet(f); }

// FIX B: StampedLock optimistic read with validation
```

The clean pattern is to make the *invariant* (the pair) the unit of atomicity: bundle the related fields into an **immutable object** and publish it atomically through a single `AtomicReference` (copy-on-write), or guard the multi-field read with one lock / `StampedLock` optimistic read + `validate`. Per-field `volatile` only works when fields are truly independent.

#### Q103. [Coding] Implement a fixed-rate limiter that allows at most N operations per second, thread-safe.

```java
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class RateLimiter {
    private final Semaphore permits;

    RateLimiter(int perSecond) {
        this.permits = new Semaphore(perSecond);
        // Refill all permits once per second.
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-refill"); t.setDaemon(true); return t;
        }).scheduleAtFixedRate(() -> {
            permits.drainPermits();          // discard leftovers (no burst carry-over)
            permits.release(perSecond);      // grant a fresh second's worth
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** Returns true if allowed now, false if the second's budget is spent. */
    boolean tryAcquire() { return permits.tryAcquire(); }

    /** Block until a permit is available. */
    void acquire() throws InterruptedException { permits.acquire(); }
}
```

A `Semaphore` is a natural fit: permits represent the per-second budget, `tryAcquire` is the non-blocking "shed if over limit" path, and a daemon scheduled task refills it. This is a simple **fixed-window** limiter (can allow a 2N burst across a window boundary); for smoother shaping use a **token-bucket** that adds permits continuously based on elapsed time, or use Guava's `RateLimiter` / Resilience4j in production.

#### Q104. [Practical] A `CompletableFuture` pipeline silently swallows exceptions — failures vanish with no log. Why, and how do you fix it?

If you never attach `exceptionally`/`handle`/`whenComplete` and never call `join()`/`get()`, an exception completes the future exceptionally but **nothing observes it**, so it's silently lost (no stack trace, no log). Async pipelines don't propagate to a `try/catch` in the calling thread.

```java
// BROKEN: exception in fetch() disappears
CompletableFuture.supplyAsync(this::fetch).thenAccept(this::store);

// FIX: terminate every pipeline with error handling
CompletableFuture.supplyAsync(this::fetch)
    .thenAccept(this::store)
    .exceptionally(ex -> { log.error("pipeline failed", ex); return null; });

// or observe via whenComplete (runs on success AND failure)
.whenComplete((result, ex) -> { if (ex != null) log.error("failed", ex); });
```

Rules: (1) **always terminate** a `CompletableFuture` chain with `exceptionally`/`handle`/`whenComplete`; (2) remember exceptions propagate *down* the chain, so a single terminal handler catches earlier stages; (3) `thenApply` after a failure is skipped — only `handle`/`exceptionally`/`whenComplete` see the error; (4) if you fan out with `allOf`, call `join()` on it or attach a handler, or sibling failures vanish.

#### Q105. [Practical] A read-heavy cache uses `synchronized` on every access and is a throughput bottleneck. Walk through the optimization options.

The lock serializes reads that don't conflict with each other. Progressively relax, measuring at each step:

1. **`ReentrantReadWriteLock`** — many concurrent readers, exclusive writers. Wins when reads dominate and the critical section is non-trivial. Watch for writer starvation; use fair mode if writes must make progress.
2. **`StampedLock` optimistic read** — read fields without acquiring any lock, then `validate(stamp)`; fall back to a real read lock only if a writer intervened. Best for very short, extremely read-heavy sections — no cache-line write on the read path.
3. **`ConcurrentHashMap`** — if it's really a map, drop the explicit lock entirely; reads are lock-free, writes are bin-level. Usually the right answer for a cache.
4. **Immutable snapshot + `AtomicReference`** (copy-on-write) — readers see an immutable map with zero coordination; writers build a new map and CAS the reference. Ideal when writes are rare and reads are the hot path.

```java
// Copy-on-write config cache: lock-free reads, rare writes swap the whole map
private final AtomicReference<Map<K,V>> ref =
    new AtomicReference<>(Map.of());
V get(K k) { return ref.get().get(k); }        // no lock at all
void put(K k, V v) {                           // rare
    ref.updateAndGet(old -> { var m = new HashMap<>(old); m.put(k,v); return Map.copyOf(m); });
}
```

The general principle: move from *mutual exclusion* → *reader/writer separation* → *optimistic validation* → *no coordination on the read path*. Each step trades a bit of write cost for far cheaper reads.

#### Q106. [Coding] Write a method that runs several tasks in parallel and returns when the first one succeeds, cancelling the rest.

```java
import java.util.*;
import java.util.concurrent.*;

<T> T firstSuccess(List<Callable<T>> tasks, ExecutorService pool)
        throws InterruptedException, ExecutionException {
    // ExecutorCompletionService yields futures in completion order.
    var ecs = new ExecutorCompletionService<T>(pool);
    List<Future<T>> futures = new ArrayList<>();
    try {
        for (Callable<T> t : tasks) futures.add(ecs.submit(t));
        ExecutionException last = null;
        for (int i = 0; i < tasks.size(); i++) {
            try {
                return ecs.take().get();        // first to COMPLETE; rethrow if it failed
            } catch (ExecutionException e) {
                last = e;                        // this one failed, keep waiting for others
            }
        }
        throw last;                              // all failed
    } finally {
        for (Future<T> f : futures) f.cancel(true);  // cancel the losers / stragglers
    }
}
```

`ExecutorCompletionService` is the right tool: `take()` returns futures **in the order they finish**, so you get the first result without polling. The `finally` cancels every remaining task (interrupting them) so losers don't waste resources. On JDK 21+, structured concurrency's `StructuredTaskScope.ShutdownOnSuccess` expresses this same "race, take first win, cancel siblings" pattern with automatic cleanup.

#### Q107. [Practical] An `AtomicInteger`-based CAS loop is burning CPU under heavy contention with little forward progress. What's happening and what are the options?

Under high contention many threads read the same value, compute, and CAS — but only one wins each round; the rest fail and retry, so most CPU goes to *failed* CAS attempts and cache-line ping-pong on the single hot word. It's lock-free (system makes progress) but individual throughput collapses, and it can resemble a livelock of wasted work.

Options:
- **Striping** — replace the single hot atomic with per-thread/per-stripe cells and combine on read. `LongAdder`/`LongAccumulator` do exactly this and dramatically cut contention and false sharing.
- **Backoff** — add exponential or randomized backoff between retries to reduce simultaneous collisions (the JDK's own contended structures use backoff internally).
- **Batching / combining** — accumulate locally and flush periodically instead of CAS-ing per operation.
- **Reconsider the design** — if every thread *must* touch one number atomically every operation, that number is a serialization point no lock-free trick removes; partition the work so threads don't share it.

```java
// Replace a hot AtomicLong counter with a striped LongAdder
LongAdder counter = new LongAdder();
counter.increment();        // hits a thread-local cell
long total = counter.sum(); // aggregate (non-atomic snapshot)
```

The lesson: lock-free isn't automatically *fast* — a single contended cache line is a bottleneck regardless of locking. Throughput comes from **reducing sharing**, not from the CAS itself.

#### Q108. [Practical] Tasks submitted to a `ThreadLocal`-using component leak memory in a long-lived thread pool. Diagnose and fix.

`ThreadLocal` entries live in a map owned by the **thread**, keyed by a weak reference to the `ThreadLocal` object but with a **strong** reference to the value. In a pool, worker threads live forever, so values set during one task are never cleared and pile up across millions of tasks — a classic slow leak (and a correctness bug: task B sees task A's leftover value).

```java
// LEAK / cross-task contamination: value persists on the pooled thread
private static final ThreadLocal<HeavyContext> CTX = new ThreadLocal<>();
void handle() { CTX.set(buildContext()); try { work(); } finally { CTX.remove(); } }
//                                                              ^^^^^^^^^^^^ essential
```

Always `remove()` in a `finally` so the value is cleared before the thread returns to the pool. For request-scoped data this both prevents the leak and stops one request's context bleeding into the next. On JDK 21+, prefer **`ScopedValue`** (immutable, automatically bounded to a dynamic scope, no manual cleanup) for the carry-context-down-a-call-tree use case — it's leak-proof by construction and works cleanly with virtual threads.

### 🟠 — extended

#### Q109. [Practical] In production you observe occasional stale reads of a field on ARM servers (Graviton) that never reproduced on x86. What's the root cause and the fix?

The code has a **latent data race** — a shared field read/written without a happens-before edge. x86's strong **TSO** memory model forbids most reorderings, so the missing synchronization accidentally "worked." ARM's **weak** memory model permits load/store reorderings that expose the bug: a reader can see a stale value or fields out of order. It's not an ARM bug; the code was always wrong and x86 was hiding it.

```java
// Latent race: writer publishes data then a ready flag with no ordering
int[] data; boolean ready;                 // plain fields
void publish() { data = compute(); ready = true; }   // reorderable on ARM
void consume() { if (ready) use(data); }   // may see ready=true, data=null/stale

// FIX: a release/acquire edge via volatile on the flag
volatile boolean ready;                    // write happens-before subsequent read
```

The fix is to introduce the missing happens-before edge: make `ready` `volatile` (its write release-publishes everything before it, including `data`), or guard both under a lock, or use `final` fields / a `VarHandle` release-store. The broader lesson: **test concurrency on weak-memory hardware** (ARM, in CI) and rely on jcstress — "passes on x86" proves nothing about correctness, only that TSO masked the defect.

#### Q110. [Practical] A lock-free queue intermittently corrupts under stress. You suspect ABA. How do you confirm and fix it?

ABA strikes CAS algorithms when a location goes A→B→A between a thread's read and its CAS: the CAS succeeds on stale assumptions because only the *value* matched, not the *history*. Symptoms: lost or duplicated nodes, a node reappearing after removal, crashes under high churn. In Java the GC prevents the *memory-reuse* flavor (a referenced node can't be freed), but **logical ABA** on indices, tags, or recycled pool objects still bites.

Confirm: it reproduces only under high concurrency with rapid add/remove of the *same* logical values; adding a version counter and logging makes the A→B→A transition visible; jcstress can target the CAS.

```java
// Pair the pointer with a monotonic stamp so A→B→A changes the stamp.
import java.util.concurrent.atomic.AtomicStampedReference;
AtomicStampedReference<Node> head = new AtomicStampedReference<>(null, 0);

int[] s = new int[1];
Node cur = head.get(s);
int stamp = s[0];
// ... compute next ...
head.compareAndSet(cur, next, stamp, stamp + 1);  // fails if anything cycled through
```

Fixes: **versioned references** (`AtomicStampedReference`) so the stamp differs even when the pointer returns to A; **`AtomicMarkableReference`** for a one-bit mark (used in lock-free Harris linked lists for logical deletion); or **epoch-based reclamation / hazard pointers** when you manage memory manually. The Michael-Scott queue uses stamping/careful invariants precisely to dodge ABA.

#### Q111. [Coding] Implement a Treiber-style lock-free stack and explain the one place ABA could still hurt you.

```java
import java.util.concurrent.atomic.AtomicReference;

class LockFreeStack<E> {
    private static final class Node<E> { final E item; Node<E> next; Node(E i){ item=i; } }
    private final AtomicReference<Node<E>> head = new AtomicReference<>();

    void push(E item) {
        Node<E> n = new Node<>(item), cur;
        do { cur = head.get(); n.next = cur; }
        while (!head.compareAndSet(cur, n));        // retry if head moved
    }

    E pop() {
        Node<E> cur, next;
        do {
            cur = head.get();
            if (cur == null) return null;
            next = cur.next;
        } while (!head.compareAndSet(cur, next));    // ABA-sensitive line
        return cur.item;
    }
}
```

In `pop`, between reading `cur` and the CAS, other threads could pop `cur`, pop more, and push a node that **reuses the same object** as the head — the CAS then succeeds against an `cur.next` that's no longer valid. Java's GC keeps `cur` alive while you reference it, so the address-reuse form can't happen with fresh nodes; but if you **pool/recycle `Node` objects**, you reintroduce ABA and must use `AtomicStampedReference`. The stack is lock-free, not wait-free: a thread can be forced to retry indefinitely, and under heavy contention the single `head` becomes a coherence hotspot (an *elimination-backoff* stack relieves it by pairing concurrent pushes and pops).

#### Q112. [Practical] Your virtual-thread service scales worse than expected — throughput stalls under load. What's "pinning" and how do you find and fix it?

A virtual thread normally **unmounts** from its carrier (platform) thread on a blocking call, freeing the carrier. **Pinning** happens when it *can't* unmount — most commonly while holding a `synchronized` monitor across a blocking operation, or inside a native/`Object.wait` frame. The carrier stays stuck, so a small carrier pool is exhausted and concurrency collapses to roughly the carrier count.

Diagnose: run with `-Djdk.tracePinnedThreads=full` (or capture `jdk.VirtualThreadPinned` JFR events) to print stack traces where pinning occurred; flame graphs show carriers blocked in `synchronized` frames.

```java
// PINS the carrier: synchronized held across blocking I/O
synchronized (lock) { result = blockingDbCall(); }   // carrier can't unmount

// FIX: use a ReentrantLock — Loom unmounts cleanly while it's held
private final ReentrantLock lock = new ReentrantLock();
lock.lock();
try { result = blockingDbCall(); } finally { lock.unlock(); }
```

Fix by replacing `synchronized`-across-blocking with `ReentrantLock`, or by not holding any lock across the blocking call. (Recent JDKs — JEP 491, JDK 24 — largely eliminate `synchronized` pinning, but `ReentrantLock` remains the safe, portable choice and native frames can still pin.) Also: **don't pool virtual threads** and don't cap them with a small executor — create one per task so blocking is cheap.

#### Q113. [Practical] A distributed counter / "exactly-once" requirement is being implemented with in-process locks. Why is that wrong, and what's the correct approach?

A JVM lock (`synchronized`, `ReentrantLock`) only provides mutual exclusion **within one process**. Across multiple instances behind a load balancer, each JVM has its own lock, so two nodes happily run the "critical section" simultaneously — double-charges, duplicate side effects, lost updates. In-process synchronization is invisible to other machines.

Correct approaches escalate the coordination boundary to where the data lives:
- **Push atomicity into the datastore** — a single `UPDATE ... SET n = n + 1` or a DB-native atomic counter / `SELECT ... FOR UPDATE` makes the database the serialization point.
- **Optimistic concurrency** — version column / `@Version`; the update's `WHERE version = ?` fails the loser, who retries. Scales well under low conflict.
- **Distributed lock** — a lease in Redis (Redlock-style, with fencing tokens), ZooKeeper, or etcd — but only with **fencing tokens** so a stalled lock-holder whose lease expired can't corrupt state on resume.
- **Idempotency keys** — for "exactly-once" effects, make the operation idempotent (dedupe by a unique request key) rather than relying on locks at all — far more robust against retries and partial failures.

The principle: **correctness boundaries must match deployment boundaries.** If state is shared across processes, coordination must live in the shared store (DB, Redis, consensus system), not in any single JVM's heap.

#### Q114. [Practical] How do you design and verify the correctness of a non-trivial concurrent data structure before shipping it?

Hand-written lock-free/fine-grained code is where the deepest bugs hide, so layer the defenses:

- **State an invariant and a linearization point** — define what "consistent" means and identify the single atomic step at which each operation appears to take effect (linearizability). If you can't name the linearization point, the design isn't ready.
- **Stress with jcstress** — the JVM's concurrency stress harness exhaustively probes interleavings and memory-model corner cases that ordinary unit tests never hit; it's the de-facto standard for JMM-sensitive code.
- **Model-check the algorithm** — TLA+/PlusCal or SPIN to prove the protocol (not the code) is free of deadlock and respects the invariant across all interleavings, before implementing.
- **Run on weak hardware in CI** — exercise on ARM to surface reordering bugs TSO hides.
- **Property/invariant tests under load** — assert the invariant after millions of randomized concurrent operations from many threads; compare against a simple lock-based reference implementation (linearizability checking / a "sequential spec" oracle, e.g., Lincheck).
- **Prefer not to** — the strongest verification is using `java.util.concurrent`'s already-verified structures. Roll your own only with a measured reason and this full test apparatus.

The mindset: concurrency correctness is *adversarial* — you must actively hunt interleavings, not hope tests stumble onto them. "It passed our tests" is necessary but nowhere near sufficient.

#### Q115. [Coding] Implement a thread-safe memoizing cache that computes each value at most once even under concurrent requests for the same key.

```java
import java.util.concurrent.*;

class Memoizer<K, V> {
    private final ConcurrentMap<K, Future<V>> cache = new ConcurrentHashMap<>();
    private final Function<K, V> compute;

    Memoizer(Function<K, V> compute) { this.compute = compute; }

    V get(K key) throws InterruptedException {
        while (true) {
            Future<V> f = cache.get(key);
            if (f == null) {
                FutureTask<V> ft = new FutureTask<>(() -> compute.apply(key));
                f = cache.putIfAbsent(key, ft);   // atomic: only one task wins
                if (f == null) { f = ft; ft.run(); }  // we won → run the computation
            }
            try {
                return f.get();                   // all callers for key wait on the one Future
            } catch (CancellationException e) {
                cache.remove(key, f);             // failed/cancelled → let someone retry
            } catch (ExecutionException e) {
                cache.remove(key, f);
                throw launderThrowable(e.getCause());
            }
        }
    }
}
```

The trick (from *Java Concurrency in Practice*) is caching a **`Future<V>` rather than the value**: `putIfAbsent` atomically ensures exactly one `FutureTask` is installed per key, so a second concurrent caller finds the in-flight `Future` and *waits* on it instead of launching a duplicate computation. Removing the entry on failure prevents caching a permanent error. In modern code, `cache.computeIfAbsent(key, compute)` gives the at-most-once semantics directly — but note `computeIfAbsent` holds the bin lock during the mapping function, so a slow or reentrant computation can stall other keys' updates; the `Future` approach keeps the computation outside any map lock.

#### Q116. [Practical] A `StampedLock` optimistic read occasionally returns garbage / NPEs. What is the misuse?

`tryOptimisticRead()` does **not** acquire a lock — it returns a stamp and lets you read fields *speculatively*. If you use those fields **before validating**, a concurrent writer may have changed them mid-read, so you can observe a torn or null intermediate state. Optimistic reads are only valid after a successful `validate(stamp)`.

```java
double distance() {
    long stamp = sl.tryOptimisticRead();   // NO lock held
    double cx = x, cy = y;                  // speculative reads — may be torn
    if (!sl.validate(stamp)) {              // did a writer intervene?
        stamp = sl.readLock();              // fall back to a real read lock
        try { cx = x; cy = y; }
        finally { sl.unlockRead(stamp); }
    }
    return Math.sqrt(cx*cx + cy*cy);         // use ONLY validated values
}
```

Rules: (1) copy fields into locals, then `validate`; (2) **never dereference** a speculatively-read reference before validation — it could be null/stale and crash; (3) on validation failure, fall back to a real `readLock`; (4) `StampedLock` is **not reentrant** and its read lock can't upgrade to write — re-acquiring will deadlock. Used correctly it's the fastest read path (no cache-line write); used carelessly it's a source of heisenbugs.

### 🔴 — extended

#### Q117. [Practical] Lead a postmortem: a once-a-week production hang was traced to a concurrency bug. How do you structure the investigation and the systemic fix?

Structure it as evidence-gathering → root cause → systemic prevention, not a one-off patch:

1. **Capture state before recovery** — mandate that on-call grabs **two thread dumps a few seconds apart**, a heap dump, and JFR before restarting. A hang with no artifacts is unfixable; the dumps reveal deadlock cycles, convoys, or pool starvation.
2. **Classify the failure** — explicit deadlock (detector finds the cycle), pool-starvation deadlock (all pool threads blocked on each other, no cycle reported), livelock (threads RUNNABLE but no progress), or a missed-signal/lost-wakeup (everyone WAITING). Each has a distinct dump signature.
3. **Find the true root cause, not the trigger** — "once a week" usually means a rare interleaving or a load threshold. Reproduce with a stress harness and jcstress; reason in happens-before, not in "add a sleep."
4. **Fix at the right altitude** — replace ad-hoc locks with vetted `java.util.concurrent` constructs, enforce **global lock ordering**, separate pools to remove starvation, add timeouts to every blocking wait so a hang becomes a logged, recoverable error instead of an infinite stall.
5. **Systemic prevention** — add invariant/stress tests to CI (including on ARM), introduce timeouts + circuit breakers as a safety net, add observability (lock-contention metrics, JFR always-on, deadlock-detector alerts), and codify lock-ordering and "no blocking in pooled tasks" as reviewable standards.

The leadership framing: an intermittent concurrency hang is a *latent class* of defect, not a single bug. The deliverable is a guardrail — tooling, standards, and observability that make this whole category visible and recoverable — so the team isn't relying on luck or x86's accidental ordering to stay up.

#### Q118. [Practical] Define an org-wide strategy to prevent concurrency defects across many teams and services. What are the pillars?

Make the safe path the easy path; make dangerous primitives rare and reviewed:

- **Default to high-level abstractions** — mandate `java.util.concurrent` (executors, concurrent collections, `CompletableFuture`, structured concurrency) and message-passing/actors over hand-rolled locks and `wait`/`notify`. Raw `synchronized` on shared mutable state and any custom lock-free code require explicit review.
- **Immutability and confinement by default** — prefer immutable value objects, thread confinement, and copy-on-write so most code has *no* shared mutable state to get wrong. The cheapest concurrency bug is the one that can't exist.
- **Enforced conventions** — documented global lock-ordering, "no blocking call inside a pooled task," "always `remove()` ThreadLocal," "every blocking wait has a timeout," checked in code review and where possible by static analysis (ErrorProne/SpotBugs concurrency checks, `@GuardedBy` annotations).
- **Verification infrastructure** — jcstress and invariant-under-load tests in CI for any concurrent data structure; run the suite on **ARM** so weak-memory bugs surface; model-check critical protocols (TLA+).
- **Observability and safety nets** — standardized lock-contention/queue-depth metrics, always-on JFR, deadlock-detector alerting, and circuit breakers/timeouts so latent defects degrade gracefully instead of hanging.
- **Education and ownership** — JMM/happens-before training, a concurrency review checklist, and a small set of "concurrency-aware" reviewers for the rare hand-rolled code.

The thesis: you don't prevent concurrency bugs by asking engineers to be more careful — you prevent them by **removing shared mutable state from most code**, funneling the unavoidable cases through vetted abstractions, and backing it with verification and observability so the residual risk is visible and bounded.

#### Q119. [Practical] When does adding more threads make a system *slower*, and how do you decide the right level of parallelism for a workload?

Past a point, threads add coordination cost faster than they add useful work — the classic retrograde throughput curve. Causes: (1) **contention** on a shared lock/atomic/cache line adds to the serial fraction (Amdahl), so beyond some N throughput plateaus then *collapses* (lock convoy, coherence storm); (2) **oversubscription** beyond core count adds context-switch and scheduler overhead with no parallelism gain; (3) **resource saturation** — threads queue on a bounded downstream (DB connections, disk, NIC) so more threads just deepen queues and latency; (4) **memory pressure** — each platform thread costs ~1 MB of stack.

Sizing rules of thumb:
- **CPU-bound:** pool size ≈ number of cores (or cores + 1). More just thrashes the scheduler.
- **I/O-bound (platform threads):** `cores × (1 + waitTime/computeTime)` (Little's Law / Brian Goetz's formula) — size to keep cores busy while others wait, bounded by the downstream's capacity.
- **I/O-bound (virtual threads):** create one per task; concurrency is then bounded by the *downstream resource* (use a `Semaphore` to cap DB hits), not by thread count.

```java
int nCores = Runtime.getRuntime().availableProcessors();
// I/O-bound platform pool: cores × (1 + wait/compute), wait/compute ≈ 9 here
int size = nCores * (1 + 9);
```

The decision process: identify whether you're CPU-, I/O-, or contention-bound (profile, don't guess); size to the **binding constraint**; and verify by measuring the throughput-vs-threads curve so you stay left of the collapse point. The right answer is frequently *fewer, well-fed threads* plus *less sharing* — not more threads.

#### Q120. [Practical] Choose between shared-memory threading, the actor model, and CSP/channels for a new large concurrent system. How do you decide and what are the failure modes of each?

Match the model to the workload's coupling and the team's tolerance for each model's hazards:

- **Shared-memory threading (locks/atomics/JUC):** lowest latency and overhead for fine-grained CPU-bound work on one machine where threads must touch common data fast. Failure modes: data races, deadlock, lock convoys, subtle memory-model bugs — the hardest class to get right and to verify. Choose when performance demands direct shared state and you have the expertise/verification to manage it.
- **Actor model (Akka/Pekko, Orleans, Erlang):** no shared mutable state (each actor is single-threaded over its mailbox), location transparency, and "let it crash" supervision — excellent for **distributed, stateful, fault-tolerant** systems with naturally entity-shaped state (per-user, per-device, per-session). Failure modes: mailbox unbounded growth/backpressure, message-ordering and at-least-once/at-most-once delivery reasoning, and harder end-to-end flow tracing. Choose for resilient distributed systems and entity-centric domains.
- **CSP/channels (Go goroutines, JVM channel libs):** processes are anonymous and synchronize through named channels (often rendezvous); great for **pipelines and fan-in/fan-out dataflow** with natural backpressure via bounded channels. Failure modes: channel deadlock (no receiver/sender), goroutine leaks (blocked forever on a channel), and `select` complexity. Choose for streaming/pipeline architectures.

```
Shared memory: fastest, most dangerous — one machine, expert-managed
Actors:        isolation + supervision — distributed, fault-tolerant, stateful
CSP/channels:  dataflow + backpressure — pipelines, fan-in/fan-out
```

The decision rubric: **how coupled is the shared state, does it span machines, and what's the dominant fault model?** Tightly-coupled single-machine CPU work → shared memory; distributed stateful resilience → actors; staged dataflow with backpressure → CSP. Most large systems are **hybrids**: actors or services at the macro scale for isolation and resilience, with shared-memory JUC inside each component for hot-path performance. The unifying principle across all three: **minimize shared mutable state** — the models differ mainly in *how* they help you avoid it.

### 🟡 — extended

#### Q121. [Coding] Implement a `CountDownLatch`-style one-shot gate from scratch using `wait`/`notifyAll`, and note why you'd normally just use the JDK class.

```java
class OneShotLatch {
    private int count;
    private final Object lock = new Object();

    OneShotLatch(int count) { this.count = count; }

    void countDown() {
        synchronized (lock) {
            if (count > 0 && --count == 0)
                lock.notifyAll();              // release ALL waiters once at zero
        }
    }

    void await() throws InterruptedException {
        synchronized (lock) {
            while (count > 0)                  // loop guards spurious wakeups + missed signals
                lock.wait();                   // releases monitor, parks until notified
        }
    }
}
```

The two non-negotiables: **wait in a `while` loop** checking the condition (a bare `if` breaks on spurious wakeups and on a `countDown` that fires before `await` is reached), and **`notifyAll` not `notify`** (a one-shot gate must release *every* waiter, and `notify` wakes only one, risking a permanently-stuck thread). In real code use `java.util.concurrent.CountDownLatch` — it's built on `AbstractQueuedSynchronizer`, is far more efficient (no monitor contention, FIFO wait queue), supports timed/interruptible `await`, and is correct by construction. Hand-rolling `wait`/`notify` is a learning exercise and an interview probe, not production practice.

## ✅ Key Takeaways

- **Concurrency ≠ parallelism**: one is about structure (dealing with many things), the other about simultaneous execution. Async and parallel are likewise orthogonal.
- A **data race** is undefined behavior; correctness comes from establishing **happens-before** edges via locks, `volatile`, or higher-level constructs — not from luck on a particular CPU.
- Prefer **immutability and higher-level abstractions** (concurrent collections, `BlockingQueue`, executors, structured concurrency, message passing) over hand-rolled locks and `wait`/`notify`.
- Break deadlock with **global lock ordering**; relieve contention by **shrinking critical sections** and **reducing sharing** (striping, per-thread state, `LongAdder`).
- **Lock-free** ≠ wait-free; CAS loops give system-wide progress but can starve a thread and suffer the **ABA problem** (fix with versioned references).
- **Amdahl's Law**: the serial fraction caps your speedup — optimize the serial part, and remember coordination *is* serial work.
- The fastest concurrent code **shares the least**; the memory hierarchy and cache coherence, not the ALU, dominate performance (mind **false sharing** and NUMA).

## ⚠️ Common Pitfalls

- Using `volatile` for compound actions (`count++`) — it gives visibility, not atomicity.
- Check-then-act races: `if (!map.containsKey(k)) map.put(k, v)` — use `putIfAbsent`/`computeIfAbsent`.
- Double-checked locking **without** `volatile` on the field (publishes a half-constructed object).
- Forgetting to release a lock on the exception path — always `lock()` … `try { } finally { unlock(); }`.
- Calling `wait()` with `if` instead of a `while` loop — spurious wakeups and missed signals corrupt state.
- Swallowing `InterruptedException` — restore the interrupt flag or propagate; never just log and continue.
- Holding a lock across slow I/O, logging, or allocation — bloats the critical section and serializes the system.
- Pinning **virtual threads** by holding `synchronized` across a blocking call — use `ReentrantLock` instead.
- Assuming "works on x86" means correct — x86's TSO masks missing synchronization that breaks on ARM (Graviton, Apple Silicon).
- Unbounded thread pools / queues — leads to memory exhaustion and thread thrash under load; bound them and set a rejection policy.

## 📚 Further Reading

- *Java Concurrency in Practice* — Goetz, Peierls, Bloch, et al. (still the canonical JVM concurrency text).
- *The Art of Multiprocessor Programming* — Herlihy & Shavit (lock-free/wait-free theory, consensus, ABA, hazard pointers).
- JSR-133 and the *Java Memory Model FAQ* — the authoritative happens-before specification.
- JEP 444 (Virtual Threads), JEP 453/structured concurrency, and the `java.util.concurrent` / `VarHandle` Javadocs.
- *Is Parallel Programming Hard?* — Paul McKenney (RCU, memory barriers, real-world SMP).
- OpenJDK **jcstress** — the harness for testing concurrency primitives against the JMM.
- Doug Lea, *The JSR-166 / java.util.concurrent* design notes and the `LongAdder`/`ForkJoinPool` source.
