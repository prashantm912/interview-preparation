# Operating Systems

[← Back to master index](../README.md)

A practical, interview-focused tour of operating-system fundamentals every backend engineer is expected to know — processes and threads, CPU scheduling, virtual memory and paging, synchronization and deadlock, IPC, system calls, and the kernel's role in I/O. The answers favor the mental models and trade-offs that come up in real interviews, with Java examples where the JVM exposes the OS concept directly. Content is accurate to 2026 practice on Linux/Windows/macOS-class systems.

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

### Q1. [Theory] What is a process, and what is a thread? How do they differ?

A **process** is an instance of a running program: an address space (code, data, heap, stack), plus OS-managed resources (open file descriptors, sockets, the page table, security context). A **thread** is the unit of CPU scheduling — a single sequential flow of execution *inside* a process, with its own stack, program counter, and registers.

The key distinction is **what is shared**:

```
 Process A                          Process B
 ┌──────────────────────────┐      ┌──────────────────────────┐
 │ Address space (heap/code) │      │ Address space (heap/code) │
 │  ┌───────┐  ┌───────┐     │      │  ┌───────┐                │
 │  │Thread1│  │Thread2│ ... │      │  │Thread1│                │
 │  │ stack │  │ stack │     │      │  │ stack │                │
 │  └───────┘  └───────┘     │      │  └───────┘                │
 │  shared: heap, globals,   │      │                          │
 │  file descriptors         │      │                          │
 └──────────────────────────┘      └──────────────────────────┘
   isolation boundary (MMU)  ◄── different page tables ──►
```

- Threads in one process **share** the heap, global data, and file descriptors; each has its **own** stack and registers.
- Processes are isolated from each other by the MMU/page tables; one process cannot touch another's memory without explicit IPC.
- Thread creation and context switching are cheaper than process creation (no new address space, no page-table setup).
- A crash (segfault) in one thread typically takes down the whole process; a process crash does not directly affect others.

In Java, every `Thread` you create maps (for platform threads) to one OS thread inside the single JVM process, which is exactly why they share the same heap and why you need synchronization.

### Q2. [Theory] What are the typical process states and what causes the transitions?

A process moves through a small state machine. The classic five-state model:

```
            admit            dispatch
   NEW ───────────► READY ───────────► RUNNING ───────► TERMINATED
                      ▲                  │   │
          I/O done /  │                  │   │ exit()
          event occurs│         timer    │   │ I/O request /
                      │         interrupt │   │ wait on event
                      └──────── READY ◄───┘   ▼
                                          BLOCKED (WAITING)
```

- **New** — process is being created (PCB allocated, not yet admitted to the ready queue).
- **Ready** — runnable, waiting for a CPU core to be assigned by the scheduler.
- **Running** — currently executing on a core.
- **Blocked/Waiting** — cannot proceed until some event completes (disk I/O, lock, signal).
- **Terminated** — finished execution; PCB lingers briefly as a "zombie" on Unix until the parent reaps the exit status via `wait()`.

Transitions: *Ready→Running* is **dispatch** (scheduler picks it); *Running→Ready* happens on a **timer/preemption**; *Running→Blocked* on an **I/O or wait** request; *Blocked→Ready* when the **event completes**. Note a blocked process goes back to *Ready*, not directly to *Running* — it must be re-scheduled.

### Q3. [Theory] What is a context switch, and why is it expensive?

A **context switch** is the act of saving the state of the currently running thread/process and restoring the state of another so the CPU can run it instead. The OS saves the registers, program counter, and stack pointer into the old task's PCB/TCB, then loads the new task's saved state.

It is expensive for two reasons:

1. **Direct cost** — saving/restoring registers and (for a process switch) reloading the page-table base register (e.g. CR3 on x86), which forces a TLB flush. This is hundreds to low-thousands of nanoseconds.
2. **Indirect cost** — the new task starts with **cold caches and an empty TLB**, so its first instructions suffer cache and TLB misses until the working set is reloaded. This indirect cost often dominates.

A **thread** switch within the same process is cheaper than a **process** switch because the address space (and thus the page table / TLB) does not change. This is a core reason threads are favored over processes for fine-grained concurrency.

### Q4. [Theory] What is the difference between user mode and kernel mode?

CPUs provide at least two privilege levels. **User mode** (ring 3 on x86) restricts the instructions a program may execute — it cannot directly touch hardware, modify page tables, or run privileged instructions. **Kernel mode** (ring 0) has full access to hardware and all memory.

```
   ┌──────────────────────────────┐
   │   User mode (ring 3)         │  ← apps: limited, sandboxed
   │   - your code, libc, JVM     │
   └──────────────┬───────────────┘
        system call / trap (controlled gate)
   ┌──────────────▼───────────────┐
   │   Kernel mode (ring 0)       │  ← full hardware access
   │   - scheduler, drivers, FS   │
   └──────────────────────────────┘
```

Application code runs in user mode. When it needs a privileged operation (read a file, send a packet), it makes a **system call**, which traps into the kernel, switches to kernel mode, performs the operation, and returns. This boundary is what enforces isolation and protection — a buggy app cannot crash the machine because it physically cannot execute privileged instructions.

### Q5. [Theory] What is a system call? Give examples.

A **system call** is the controlled entry point through which a user-mode program requests a service from the kernel. The program puts the call number and arguments in registers and executes a trap instruction (`syscall` on x86-64), which transfers control to a fixed kernel handler at the higher privilege level.

Common categories and examples (POSIX names):

- **Process control** — `fork`, `execve`, `exit`, `wait`, `clone`.
- **File management** — `open`, `read`, `write`, `close`, `lseek`, `stat`.
- **Device/IO** — `ioctl`, `mmap`, `poll`/`epoll`.
- **Communication** — `pipe`, `socket`, `connect`, `send`, `recv`, `shmget`.
- **Info/time** — `getpid`, `gettimeofday`, `nanosleep`.

System calls are **orders of magnitude more expensive than a normal function call** because of the mode switch and the loss of cache/branch-prediction state. That cost is why batching I/O (larger `read`/`write` buffers, `writev`, `io_uring`) matters for performance.

### Q6. [Practical] In Java, how do you start a new process vs a new thread, and what does each cost?

```java
// New thread — cheap, shares the JVM heap, same process.
Thread t = new Thread(() -> System.out.println("hi from " +
        Thread.currentThread().getName()));
t.start();        // start() → OS creates a thread; run() executes on IT
t.join();         // wait for it to finish

// New OS process — expensive, fully isolated, separate address space.
Process p = new ProcessBuilder("git", "status")
        .inheritIO()
        .start();     // forks/execs a brand-new process
int exitCode = p.waitFor();
```

A thread reuses the existing address space — creation is on the order of microseconds and a few hundred KB of stack. A process via `ProcessBuilder` triggers `fork`/`exec` (or `posix_spawn`), a new address space, a new page table, and a fresh runtime — milliseconds and far more memory. Rule of thumb: use threads for in-JVM concurrency, processes for isolation or to run external programs.

### Q7. [Practical] What is a daemon thread in Java, and how does it relate to OS thread lifecycle?

A **daemon thread** is a background JVM thread that does not prevent the JVM from exiting: the JVM terminates when only daemon threads remain, abruptly stopping them. Non-daemon (user) threads keep the process alive.

```java
Thread t = new Thread(() -> {
    while (true) { /* background housekeeping */ }
});
t.setDaemon(true);   // must be set BEFORE start()
t.start();
// main() can return; the JVM exits even though t is still "running"
```

At the OS level all of these are real kernel threads; "daemon" is purely a JVM bookkeeping flag controlling shutdown semantics, not an OS concept. Use it for things like background flushers or schedulers that should never block process exit — but never for work that must complete (a daemon can be killed mid-write).

### Q8. [Theory] Why do we need CPU scheduling, and what are the common goals?

On a machine with fewer cores than runnable threads, the OS must decide *who runs next*. The **scheduler** multiplexes the CPU among ready tasks to create the illusion of concurrency and to meet system goals. Competing objectives:

- **Throughput** — jobs completed per unit time.
- **Turnaround time** — total time from submission to completion.
- **Response time / latency** — time from request to first response (critical for interactive systems).
- **Fairness** — every task gets a reasonable share; no starvation.
- **CPU utilization** — keep cores busy.

These goals conflict: optimizing turnaround (run short jobs first) can starve long jobs; optimizing fairness (round-robin) can hurt average turnaround. Real schedulers (e.g. Linux's CFS / the newer EEVDF used since kernel 6.6) target *fairness with good interactive latency* rather than any single metric.

### Q9. [Theory] Explain FCFS, SJF, Round Robin, and priority scheduling.

| Algorithm | Idea | Pros | Cons |
|-----------|------|------|------|
| **FCFS** (First-Come, First-Served) | Run in arrival order, non-preemptive | Simple, fair by arrival | **Convoy effect**: one long job delays everyone |
| **SJF** (Shortest Job First) | Run the shortest next burst first | Provably optimal average waiting time | Needs burst prediction; can **starve** long jobs |
| **Round Robin** | Each task gets a fixed time quantum, then rotate | Great response time, fair | Throughput drops if quantum too small (switch overhead); behaves like FCFS if too large |
| **Priority** | Run highest priority first | Supports importance levels | Starvation of low-priority tasks; needs **aging** |

A worked example with bursts P1=24, P2=3, P3=3 (all arrive at t=0):

```
FCFS:  | P1 (24) | P2 | P3 |   avg wait = (0+24+27)/3 = 17
SJF:   | P2 | P3 | P1 (24) |   avg wait = (0+3+6)/3   = 3
```

SJF crushes the average wait, illustrating why "short jobs first" is the theoretical sweet spot — but it requires knowing burst lengths, which you usually estimate via an exponential moving average of recent bursts.

### Q10. [Theory] What is preemptive vs non-preemptive (cooperative) scheduling?

In **non-preemptive (cooperative)** scheduling a task keeps the CPU until it voluntarily yields, blocks, or exits. In **preemptive** scheduling the OS can forcibly take the CPU away — typically on a timer interrupt or when a higher-priority task becomes ready.

- Non-preemptive is simpler and avoids mid-operation context switches, but a misbehaving task can hog the CPU forever, and interactive latency suffers.
- Preemptive guarantees responsiveness and prevents monopolization but introduces the need for synchronization (a task can be interrupted at an inconvenient point) and adds switch overhead.

Modern general-purpose OSes are preemptive. Note the analogy to language runtimes: classic Go goroutines and early cooperative threading relied on yield points; Java platform threads are preemptively scheduled by the OS, while Java *virtual* threads are cooperatively scheduled by the JVM and yield at blocking points.

### Q11. [Practical] What is the time quantum (time slice), and how does its size affect Round Robin?

The **quantum** is the maximum continuous CPU time a task gets before the scheduler preempts it and moves to the next ready task.

- **Too small** → frequent context switches, so a large fraction of CPU time is spent switching rather than doing work (overhead dominates).
- **Too large** → Round Robin degenerates toward FCFS; interactive response suffers because short tasks wait behind long ones.

A common rule is to pick a quantum so that ~80% of CPU bursts are shorter than the quantum — most tasks finish within their slice, while the few long ones still get preempted to preserve responsiveness. Typical OS quanta are in the low single-digit to tens of milliseconds.

### Q12. [Theory] What is virtual memory and why is it useful?

**Virtual memory** is an abstraction that gives each process its own large, contiguous **virtual address space**, which the OS+MMU map to physical RAM (and disk) transparently. Each process believes it owns the whole address space starting at address 0.

Benefits:

- **Isolation/protection** — a process can only access its own mapped pages; the MMU faults on anything else.
- **Use more memory than RAM** — inactive pages can be **swapped** to disk; physical RAM is just a cache of the larger virtual space.
- **Simpler programming and relocation** — programs are linked against virtual addresses; the OS places them anywhere physically.
- **Sharing** — read-only pages (shared libraries) and copy-on-write pages can be shared across processes.

The cost is a layer of address translation on every memory access, mitigated by the TLB and hardware page-walkers.

### Q13. [Theory] What is paging, and what is a page table?

**Paging** divides the virtual address space into fixed-size **pages** (commonly 4 KB) and physical memory into equal **frames**. The OS maintains a **page table** per process that maps each virtual page number to a physical frame number (or marks it not-present).

A virtual address splits into a page number and an offset:

```
 32-bit example, 4KB pages:
 ┌──────────────────────┬───────────────┐
 │  page number (20)    │  offset (12)  │
 └──────────┬───────────┴───────┬───────┘
            │ index into page table
            ▼
   [ frame number ] + offset  ───►  physical address
```

Each page-table entry (PTE) holds the frame number plus control bits: **valid/present**, **read/write/execute permissions**, **user/supervisor**, **dirty** (written), and **accessed/referenced**. Paging eliminates external fragmentation (any free frame fits any page) at the cost of some internal fragmentation in the last partial page.

### Q14. [Theory] What is the TLB and why does it matter?

The **TLB (Translation Lookaside Buffer)** is a small, fast hardware cache inside the MMU that stores recent virtual-page → physical-frame translations. Without it, every memory access would require walking the (multi-level) page table in RAM — potentially several extra memory accesses per access.

```
 CPU access:
   virtual addr ─► TLB lookup
                    ├─ HIT  → frame number directly (1 cycle-ish)
                    └─ MISS → page-table walk (slow) → fill TLB → retry
```

A **TLB hit** is essentially free; a **TLB miss** triggers a page-table walk (hardware or software). TLBs are small (tens to low-thousands of entries), so programs with poor locality or huge working sets thrash the TLB. This is why **huge pages** (2 MB / 1 GB) help memory-heavy apps (databases, JVMs with large heaps): one TLB entry now covers far more memory, drastically cutting TLB misses.

### Q15. [Theory] What is fragmentation? Distinguish internal and external.

**Fragmentation** is wasted memory that cannot be used effectively.

- **Internal fragmentation** — memory allocated to a request but unused *inside* the allocated block. With 4 KB pages, a 5 KB allocation uses two pages (8 KB), wasting ~3 KB. Fixed-size allocation always rounds up.
- **External fragmentation** — enough total free memory exists but it is split into non-contiguous pieces too small to satisfy a request. Common with variable-size contiguous allocation.

```
 External:  [used][free 2K][used][free 3K][used]   ← 5K free total,
                                                      but no 4K contiguous block
 Internal:  [ request 5K ──► allocated 8K page-pair ] wasted 3K inside
```

Paging eliminates *external* fragmentation (any frame fits any page) but introduces minor *internal* fragmentation. Segmentation and contiguous allocation suffer *external* fragmentation, addressed by compaction or buddy/slab allocators.

---

## 🟡 Intermediate (3–7 yrs)

### Q16. [Theory] Explain multilevel page tables and why single-level tables don't scale.

A single flat page table for a 48-bit address space with 4 KB pages would need 2^36 entries per process — terabytes — most of it empty. **Multilevel (hierarchical) page tables** solve this by paging the page table itself: only the portions that map actual memory are allocated.

x86-64 uses a 4-level walk (and optionally 5-level):

```
 virtual addr → [PML4 idx][PDPT idx][PD idx][PT idx][offset]
                   │         │         │       │
   CR3 ─► PML4 ─►  PDPT ─►   PD ─►     PT ─►  frame + offset
```

Each level indexes the next table; unused regions simply have no lower-level tables allocated. The trade-off: a TLB miss now costs up to 4 (or 5) sequential memory accesses to walk, which is exactly why the TLB and hardware page-walk caches are so important. Alternatives include **inverted page tables** (one entry per physical frame, hashed) which scale with RAM size rather than address-space size.

### Q17. [Theory] What is demand paging, and what is a page fault?

**Demand paging** loads a page into RAM only when it is first accessed, rather than loading the whole program up front. Pages start marked not-present; the first access triggers a **page fault**.

A **page fault** is a trap the MMU raises when a program accesses a page that is not currently mapped to a frame. The kernel handles it:

```
 1. CPU accesses virtual page → PTE present-bit = 0 → TRAP to kernel
 2. Is the access legal (within a valid VMA)?  no → SIGSEGV
 3. yes → find a free frame (or evict one via replacement)
 4. read the page from disk/backing store into the frame
 5. update the PTE (present=1, frame=...)
 6. restart the faulting instruction
```

Distinguish a **minor fault** (page already in RAM, e.g. shared or in page cache — just fix the mapping) from a **major fault** (must read from disk — slow). Demand paging speeds startup and saves RAM, at the cost of latency on first touch.

### Q18. [Theory] Compare FIFO, LRU, and Clock page-replacement algorithms.

When RAM is full and a new page must come in, a **victim** must be evicted. The goal is to evict the page least likely to be used soon (Belady's optimal, OPT, is the unachievable ideal).

- **FIFO** — evict the oldest-loaded page. Simple, but ignores usage; suffers **Belady's anomaly** (more frames can mean *more* faults).
- **LRU** — evict the least-recently-used page. Excellent approximation of OPT, but true LRU requires updating a timestamp/list on **every** access — too costly in hardware.
- **Clock (Second-Chance)** — a practical LRU approximation. Pages sit in a circular buffer; each has a **reference bit** set by hardware on access. A "hand" sweeps: if ref bit = 1, clear it and skip (second chance); if 0, evict.

```
 Clock:        ┌──[ref=1]──[ref=0]◄ hand
               │                  │
            [ref=1]            [ref=1]
               │                  │
               └──[ref=0]──[ref=1]┘
   hand stops at first ref=0 → evict; otherwise clears bit and advances
```

Real kernels use enhanced clock variants with active/inactive LRU lists (Linux) because true LRU is impractical at scale.

### Q19. [Coding] Implement an LRU cache with O(1) get and put.

The classic interview problem — used by OSes and CDNs alike. Combine a hash map (for O(1) lookup) with a doubly linked list (for O(1) recency reordering). Java's `LinkedHashMap` gives this for free, but interviewers usually want the manual version.

```java
class LRUCache {
    private static class Node {
        int key, val;
        Node prev, next;
        Node(int k, int v) { key = k; val = v; }
    }

    private final int capacity;
    private final java.util.Map<Integer, Node> map = new java.util.HashMap<>();
    private final Node head = new Node(0, 0); // MRU sentinel
    private final Node tail = new Node(0, 0); // LRU sentinel

    LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public int get(int key) {
        Node n = map.get(key);
        if (n == null) return -1;
        moveToFront(n);          // mark as most-recently used
        return n.val;
    }

    public void put(int key, int value) {
        Node n = map.get(key);
        if (n != null) { n.val = value; moveToFront(n); return; }
        if (map.size() == capacity) {            // evict LRU
            Node lru = tail.prev;
            remove(lru);
            map.remove(lru.key);
        }
        Node fresh = new Node(key, value);
        map.put(key, fresh);
        insertFront(fresh);
    }

    private void remove(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void insertFront(Node n) {
        n.next = head.next; n.prev = head;
        head.next.prev = n; head.next = n;
    }
    private void moveToFront(Node n) { remove(n); insertFront(n); }
}
```

Both `get` and `put` are **O(1)** time, **O(capacity)** space. The doubly linked list keeps eviction order; the map keeps lookups constant. For a one-liner, `new LinkedHashMap<>(cap, 0.75f, true)` with an overridden `removeEldestEntry` does the same thing.

### Q20. [Theory] What is thrashing and how do you detect/fix it?

**Thrashing** is a pathological state where a system spends more time paging (servicing page faults / swapping) than doing useful work. CPU utilization collapses while disk I/O saturates.

```
 CPU util
   │        ___
   │       /   \      ← past this point, adding more processes
   │      /     \        causes thrashing: utilization crashes
   │     /       \____________
   └─────────────────────────► degree of multiprogramming
```

It happens when the combined **working sets** of running processes exceed physical RAM, so every process constantly evicts pages another process needs. Detection: high major-fault rate, near-100% swap I/O, low CPU utilization. Fixes:

- **Working-set model** — keep each process's recently-used pages resident; suspend processes if the sum exceeds RAM.
- **Page-Fault Frequency (PFF)** control — give a process more frames if its fault rate is too high, fewer if too low.
- **Reduce the load** — swap out / suspend whole processes, or add RAM.

In practice on Linux, the OOM killer or aggressive process suspension steps in; for a JVM, an oversized heap relative to RAM is a classic cause.

### Q21. [Theory] What is the working set of a process?

The **working set** W(t, Δ) is the set of distinct pages a process has referenced in the most recent time window of size Δ. It approximates the pages the process *currently needs* to run without excessive faulting.

- If the OS keeps each process's working set resident, page-fault rates stay low.
- The sum of all working sets is the memory pressure; if Σ|W| > physical frames, thrashing is imminent.
- Δ matters: too small and you miss pages soon to be reused; too large and you over-allocate.

The working-set model drives admission control: only admit/keep a process resident if its working set fits. It is the theoretical basis for PFF and for why JVM/database memory should be sized to fit the hot data in RAM.

### Q22. [Theory] What is segmentation, and how does it differ from paging?

**Segmentation** divides a program's address space into **variable-length, logically meaningful segments** — code, data, stack, heap — each with a base and limit. A logical address is `(segment selector, offset)`; the MMU adds the segment base and checks the limit.

| Aspect | Paging | Segmentation |
|--------|--------|--------------|
| Unit | Fixed-size page | Variable-size logical segment |
| Visible to programmer? | No (transparent) | Yes (semantic units) |
| Fragmentation | Internal | External |
| Protection granularity | Per page | Per segment (natural for code vs data) |

Pure segmentation suffers external fragmentation and is largely historical. Modern x86-64 essentially **disables segmentation** (flat model, segment bases = 0) and relies entirely on paging; segment registers survive mostly for thread-local storage (FS/GS) and legacy. Some systems combined both (**segmented paging**) to get logical segments paged for flexibility.

### Q23. [Theory] What are mutexes, semaphores, and monitors — and how do they differ?

All three are synchronization primitives, but at different abstraction levels:

- **Mutex (mutual exclusion lock)** — a binary lock with an **owner**. Only the thread that locked it may unlock it. Protects a critical section so one thread is inside at a time.
- **Semaphore** — a counter with atomic `wait`/`P` (decrement, block if zero) and `signal`/`V` (increment, wake a waiter). A **counting** semaphore (count N) allows up to N concurrent holders — good for resource pools. A **binary** semaphore (count 1) resembles a mutex but has **no ownership** (any thread may signal it), making it usable for signaling between threads.
- **Monitor** — a higher-level language construct bundling shared data + the lock + **condition variables**, where mutual exclusion is automatic. Java's `synchronized` + `wait`/`notify` is a monitor: entering a synchronized method acquires the implicit lock.

Rule of thumb: a mutex is for **mutual exclusion** (ownership matters), a semaphore is for **counting/signaling** (no ownership), a monitor is for **structured exclusion plus condition-based waiting**.

### Q24. [Coding] Implement a bounded blocking queue (producer/consumer) using locks and condition variables.

The producer-consumer problem is the canonical monitor exercise. Use one lock and two condition variables — `notFull` and `notEmpty`.

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

class BoundedBlockingQueue<E> {
    private final Queue<E> q = new LinkedList<>();
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    BoundedBlockingQueue(int capacity) { this.capacity = capacity; }

    public void put(E item) throws InterruptedException {
        lock.lock();
        try {
            while (q.size() == capacity)   // while, not if — guard against spurious wakeups
                notFull.await();
            q.add(item);
            notEmpty.signal();             // a consumer may now proceed
        } finally { lock.unlock(); }
    }

    public E take() throws InterruptedException {
        lock.lock();
        try {
            while (q.isEmpty())
                notEmpty.await();
            E item = q.remove();
            notFull.signal();              // a producer may now proceed
            return item;
        } finally { lock.unlock(); }
    }
}
```

Two interview-critical points: (1) **always re-check the condition in a `while` loop**, never `if`, because of spurious wakeups and the fact that another thread may grab the slot first; (2) signal the *opposite* condition after mutating state. `java.util.concurrent.ArrayBlockingQueue` is the production-ready version of exactly this.

### Q25. [Theory] What are the four Coffman conditions for deadlock?

A deadlock can occur **only if all four hold simultaneously**:

1. **Mutual exclusion** — at least one resource is held in non-shareable mode.
2. **Hold and wait** — a thread holding ≥1 resource is waiting to acquire more.
3. **No preemption** — resources cannot be forcibly taken; only released voluntarily.
4. **Circular wait** — a cycle of threads exists, each waiting for a resource held by the next.

```
   T1 ──holds──► R1 ──wanted by──► T2
    ▲                                │
    │ wanted by                holds │
    │                                ▼
   R2 ◄──────────────holds────────── T2   (cycle ⇒ deadlock)
```

Because all four are necessary, **breaking any one prevents deadlock** — which is exactly how prevention strategies work (e.g. impose a global lock-ordering to break circular wait).

### Q26. [Theory] Contrast deadlock prevention, avoidance, and detection-and-recovery.

- **Prevention** — structurally negate one Coffman condition. E.g. break *circular wait* by enforcing a **global lock acquisition order**; break *hold-and-wait* by requiring a thread to grab all resources at once. Cheap at runtime, but rigid and can reduce concurrency.
- **Avoidance** — allow the conditions but never enter an **unsafe state**. Requires knowing maximum resource demands in advance; the **Banker's algorithm** grants a request only if a safe sequence still exists. Conservative and rarely used in general-purpose OSes due to the need for a priori knowledge.
- **Detection and recovery** — let deadlocks happen, periodically run a cycle-detection algorithm on the resource-allocation graph, then recover by aborting a victim or preempting/rolling back. Databases do this (deadlock detector picks a victim transaction to abort).

In application code, **lock ordering (prevention)** is overwhelmingly the practical answer; databases lean on **detection**.

### Q27. [Coding] Two threads deadlock by locking A then B vs B then A. Show the bug and the fix.

```java
// BUG: thread 1 locks a→b, thread 2 locks b→a → circular wait → deadlock
final Object a = new Object(), b = new Object();

Runnable r1 = () -> { synchronized (a) { synchronized (b) { /* work */ } } };
Runnable r2 = () -> { synchronized (b) { synchronized (a) { /* work */ } } }; // reversed!
```

The fix is **prevention via global lock ordering**: every thread acquires locks in the same canonical order (e.g. by identity hash), breaking the circular-wait condition.

```java
static void doWork(Object x, Object y) {
    // Order locks by identity hash so all threads agree on the order.
    Object first  = System.identityHashCode(x) <= System.identityHashCode(y) ? x : y;
    Object second = (first == x) ? y : x;
    synchronized (first) {
        synchronized (second) {
            /* safe critical section — no thread can form a cycle */
        }
    }
}
```

Alternatively use `ReentrantLock.tryLock(timeout)` and back off (release all, retry) if you can't get both — that breaks the *no-preemption* condition cooperatively. Lock ordering is preferred when feasible because it has no livelock risk.

### Q28. [Theory] Explain the Banker's algorithm.

The **Banker's algorithm** is a deadlock-*avoidance* technique. It treats the OS like a banker who only grants a loan (resource) if it can still satisfy everyone's maximum claims afterward — i.e. the system stays in a **safe state**.

State it tracks for `n` processes and `m` resource types:

- **Available[m]** — free instances of each resource.
- **Max[n][m]** — each process's maximum claim.
- **Allocation[n][m]** — currently held.
- **Need = Max − Allocation**.

When process *i* requests resources, the algorithm tentatively grants them and runs the **safety check**: is there an ordering of processes such that each can obtain its remaining `Need` from the running total of `Available + freed allocations` and finish? If yes, grant; if no, the requester waits.

```
 Safe state ⇔ ∃ a sequence <P1,P2,...> where each Pi's Need ≤ Work,
              then Work += Allocation[Pi]  (Pi finishes and releases)
```

It guarantees no deadlock but requires **knowing maximum demands up front**, which is unrealistic for general workloads — hence it's mostly a teaching tool, though the safe-state idea informs resource managers.

### Q29. [Theory] What is a race condition, and what is a critical section?

A **race condition** occurs when the correctness of a computation depends on the **relative timing/interleaving** of concurrent threads accessing shared state, and at least one access is a write. The classic example is `count++`, which is read-modify-write — two threads can both read the same value and one increment is lost.

A **critical section** is the region of code that accesses shared resources and must not be executed by more than one thread at a time. The solution is to protect it so accesses are **mutually exclusive** (lock, atomic, or single-owner design). A correct critical-section solution must satisfy three properties:

1. **Mutual exclusion** — at most one thread inside.
2. **Progress** — if no thread is inside, one of the waiting threads must be allowed in (no indefinite postponement by non-contenders).
3. **Bounded waiting** — a bound exists on how many times others enter before a waiting thread gets its turn (no starvation).

In Java, `synchronized`, `ReentrantLock`, or `java.util.concurrent.atomic` classes provide the mutual exclusion / atomicity.

### Q30. [Practical] What forms of IPC exist, and when would you use each?

**Inter-Process Communication** lets isolated processes exchange data. Main mechanisms:

| Mechanism | Model | Use when |
|-----------|-------|----------|
| **Pipe / named pipe (FIFO)** | Byte stream, unidirectional | Simple parent-child or shell pipelines |
| **Message queue** | Discrete messages, kernel-buffered | Decoupled, typed messages between processes |
| **Shared memory** | Common memory region | **Highest throughput** — large data, no copying |
| **Socket (Unix domain / TCP)** | Bidirectional stream/datagram | Local or networked services |
| **Signal** | Async notification (no data) | Events/interrupts (`SIGTERM`, `SIGCHLD`) |

**Shared memory** is the fastest (no kernel copy after setup) but you must synchronize access yourself (e.g. with a shared semaphore). **Pipes/queues/sockets** copy data through the kernel — slower but the kernel handles synchronization and buffering. The classic trade-off: shared memory = speed + manual sync; message passing = safety + copy overhead.

### Q31. [Practical] Why does shared memory need separate synchronization, but a pipe does not?

A **pipe** (or message queue/socket) is mediated entirely by the **kernel**: each `write` and `read` is a system call, and the kernel serializes access to the pipe buffer and provides blocking semantics (block on full/empty). The data transfer itself is already mutually exclusive — you can't observe a half-written record because the kernel manages the buffer.

**Shared memory** is just a region mapped into multiple processes' address spaces. After setup there is **no kernel involvement** on each access — that is precisely why it's fast. But it also means the kernel isn't serializing anything: two processes can write the same bytes concurrently and tear the data. You must layer your own synchronization — typically a **named semaphore** or a futex/mutex placed in the shared region — to coordinate readers and writers. Speed comes at the price of doing the locking yourself.

### Q32. [Practical] In Java, how would you do shared-memory-style IPC and inter-process locking?

Java exposes memory-mapped files and file locks, which map onto OS shared memory and advisory locking.

```java
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

try (RandomAccessFile raf = new RandomAccessFile("shared.dat", "rw");
     FileChannel ch = raf.getChannel()) {

    // mmap a region: this is OS shared memory if multiple processes map the same file
    MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, 4096);

    // Cross-process advisory lock to coordinate access
    try (FileLock lock = ch.lock()) {          // blocks until exclusive lock acquired
        int counter = buf.getInt(0);
        buf.putInt(0, counter + 1);            // safe under the file lock
        buf.force();                           // flush to backing store
    }
}
```

`FileChannel.map` corresponds to `mmap`; multiple JVM processes mapping the same file share the same physical pages — true shared-memory IPC. `FileChannel.lock()` is the OS advisory lock used to synchronize across processes (in-process `synchronized` won't help across JVMs). For pure stream IPC, `ProcessBuilder` pipes or Unix-domain sockets (`java.net.UnixDomainSocketAddress`, since Java 16) are the alternatives.

### Q33. [Theory] What is an interrupt, and how does the CPU handle one?

An **interrupt** is an asynchronous signal to the CPU that an event needs attention — typically from hardware (disk done, NIC packet arrived, timer fired) or, for software interrupts/exceptions, from the running program. It lets the CPU avoid wasteful **polling**.

Handling sequence:

```
 1. Device asserts IRQ on the interrupt controller (e.g. APIC)
 2. CPU finishes the current instruction, then:
 3. saves minimal state, looks up the vector in the IDT (interrupt descriptor table)
 4. switches to kernel mode, jumps to the Interrupt Service Routine (ISR/handler)
 5. ISR services the device (often a fast "top half"), acks the controller
 6. CPU restores state, returns to the interrupted code (or reschedules)
```

Key ideas: handlers should be **short** (Linux splits work into a fast *top half* and a deferred *bottom half*/softirq/tasklet) because interrupts may be disabled while running. **Timer interrupts** are how preemptive scheduling reclaims the CPU. Contrast with **traps/exceptions**, which are synchronous (page fault, divide-by-zero, system call).

### Q34. [Theory] Distinguish polling, interrupts, and DMA for I/O.

Three ways the CPU coordinates with devices, in increasing efficiency:

- **Polling (programmed I/O)** — the CPU repeatedly reads a status register until the device is ready, then transfers data word by word. Simple but burns CPU cycles busy-waiting; fine only for very fast/cheap devices.
- **Interrupt-driven I/O** — the CPU issues the request and continues other work; the device raises an **interrupt** when done. The CPU still moves the data itself, one transfer per interrupt — costly for high-throughput devices.
- **DMA (Direct Memory Access)** — a DMA controller transfers a whole block **directly between device and RAM** without CPU involvement, raising a single interrupt when the entire block is done. This frees the CPU for the duration of the transfer and is how disks/NICs work today.

```
 Polling:     CPU ⇄ device (CPU spins)            — worst
 Interrupt:   CPU issues, device IRQs per word     — better
 DMA:         DMA engine moves block, 1 IRQ at end — best for bulk I/O
```

### Q35. [Theory] What are the basic components of a file system?

A file system organizes bytes on a block device into named, navigable files. Core abstractions:

- **File** — a named sequence of bytes plus metadata.
- **Inode (index node)** — the per-file metadata structure: size, owner, permissions, timestamps, and **pointers to data blocks** (direct, indirect, double-indirect). The *name* is NOT in the inode — it's in the directory.
- **Directory** — a special file mapping **names → inode numbers** (a table of directory entries). This indirection is why hard links work: multiple names point to one inode.
- **Data blocks** — fixed-size units (e.g. 4 KB) holding file contents.
- **Superblock** — describes the whole filesystem: block size, total/free counts, locations of inode and block bitmaps.
- **Free-space management** — bitmaps or free lists tracking which blocks/inodes are available.

```
 path "/home/x.txt":
   directory "/" → inode of "home" → directory "home" → inode of "x.txt"
                                                   │
                                    inode ─► [direct blocks][indirect block ─► more blocks]
```

Modern filesystems (ext4, XFS, NTFS, APFS, ZFS) add **journaling/CoW** for crash consistency, extents instead of block pointers, and B-trees for large directories.

---

## 🟠 Advanced (8–12 yrs)

### Q36. [Theory] Explain MLFQ (Multi-Level Feedback Queue) scheduling and how it balances response and throughput.

**MLFQ** uses multiple ready queues at different priority levels, each with its own (usually round-robin) quantum, and **moves jobs between queues based on observed behavior** — it learns whether a job is interactive or CPU-bound without prior knowledge.

Core rules:

1. Higher-priority queue runs before lower; within a queue, round-robin.
2. A new job enters at the **top** priority.
3. If a job **uses its entire quantum** (CPU-bound), demote it one level (longer quantum, lower priority).
4. If a job **yields/blocks before the quantum ends** (interactive/I/O-bound), keep it at its level.
5. **Periodic priority boost** — move all jobs back to the top to prevent starvation and adapt to phase changes.

```
 Q0 (high prio, quantum 8ms):  [interactive jobs]   ← short bursts stay here
 Q1 (med  prio, quantum 16ms): [mixed]
 Q2 (low  prio, quantum 32ms): [CPU-bound batch]    ← long jobs sink here
        demote on full-quantum use ▼   boost ▲ periodically
```

This approximates SJF (short/interactive jobs naturally float to the top) without needing to know burst lengths, while the periodic boost provides fairness/anti-starvation. The boost period and per-queue quanta are the key tuning knobs; getting them wrong allows gaming (a job that yields just before the quantum to stay high-priority — solved by *accounting total CPU time* per level, as in Solaris/modern variants).

### Q37. [Theory] How does Linux CFS / EEVDF scheduling work conceptually?

Linux's **CFS (Completely Fair Scheduler)** modeled an ideal "perfectly multitasking" CPU where N runnable tasks each get 1/N of the CPU. It tracked each task's **virtual runtime (vruntime)** — actual runtime weighted by `nice` priority — and always ran the task with the **smallest vruntime**, stored in a red-black tree keyed by vruntime (O(log n) pick).

```
 vruntime grows as a task runs; scheduler picks min-vruntime task
   ┌─ low vruntime (ran least) ─ picked next
   RB-tree keyed by vruntime
   └─ high vruntime (ran most) ─ waits
   nice value scales how fast vruntime accrues → weighted fairness
```

Since kernel **6.6 (2023)**, CFS was replaced by **EEVDF (Earliest Eligible Virtual Deadline First)**, which adds an explicit notion of **latency/deadline**: each task has a virtual deadline derived from its requested time slice (`sched_runtime`/latency-nice), and the scheduler runs the *eligible* task with the earliest virtual deadline. This gives better latency guarantees for interactive tasks than pure vruntime fairness while preserving long-run fairness. The takeaway for interviews: modern general-purpose scheduling is fairness-based with deadline-aware latency tuning, not strict priorities.

### Q38. [Theory] What is priority inversion, and how is it solved?

**Priority inversion** occurs when a **high-priority** task is blocked waiting for a lock held by a **low-priority** task, and a **medium-priority** task (not needing the lock) preempts the low-priority holder — indirectly blocking the high-priority task indefinitely.

```
 Hprio:  ──wants lock──► BLOCKED ........................ (stuck!)
 Mprio:                  ████ runs, preempts Lprio ████
 Lprio:  holds lock ──► preempted, can't release ──►
```

The famous real-world case was NASA's **Mars Pathfinder** (1997), which repeatedly reset. Solutions:

- **Priority inheritance** — while a low-priority task holds a lock a high-priority task wants, it **temporarily inherits** the high priority, so medium tasks can't preempt it. Priority is restored on release.
- **Priority ceiling** — each lock has a ceiling = the highest priority of any task that may acquire it; a task holding the lock runs at that ceiling, preventing inversion and certain deadlocks.

Real-time OSes (and Linux's RT-mutexes / `PTHREAD_PRIO_INHERIT`) implement priority inheritance for exactly this reason.

### Q39. [Coding] Implement the dining philosophers without deadlock.

Five philosophers, five forks; each needs both adjacent forks to eat. Naively grabbing left-then-right deadlocks (everyone holds left, waits for right — circular wait). The clean fix is **resource ordering**: make one philosopher pick up forks in the opposite order, breaking the cycle.

```java
import java.util.concurrent.locks.ReentrantLock;

class DiningPhilosophers {
    private final ReentrantLock[] forks;

    DiningPhilosophers(int n) {
        forks = new ReentrantLock[n];
        for (int i = 0; i < n; i++) forks[i] = new ReentrantLock();
    }

    void dine(int id) throws InterruptedException {
        int n = forks.length;
        int left = id, right = (id + 1) % n;
        // Break circular wait: acquire the lower-indexed fork first.
        int first = Math.min(left, right), second = Math.max(left, right);
        forks[first].lockInterruptibly();
        try {
            forks[second].lockInterruptibly();
            try {
                eat(id);
            } finally { forks[second].unlock(); }
        } finally { forks[first].unlock(); }
    }

    private void eat(int id) { /* ... */ }
}
```

By always locking the lower-indexed fork first, no global cycle can form, so deadlock is impossible. Alternative solutions: an **arbitrator** (a semaphore permitting at most N−1 philosophers to attempt at once), or `tryLock` with backoff. The ordering approach is the most idiomatic and has no livelock.

### Q40. [Theory] What is copy-on-write (CoW), and where is it used?

**Copy-on-write** is an optimization where multiple consumers share the same physical pages **read-only** until one tries to **write** — at which point the kernel transparently copies the page so the writer gets a private copy, leaving others' view unchanged.

```
 fork():  parent & child share pages, all marked read-only + CoW
          ┌─ both PTEs → frame F (R/O)
   child writes → page fault → kernel copies F→F', remaps child to F' (R/W)
          └─ now parent→F, child→F'  (lazy, only the touched page copied)
```

Uses:

- **`fork()`** — child shares the parent's address space until either writes; avoids copying gigabytes only to immediately `exec()`.
- **Memory dedup / KSM**, snapshots in **CoW filesystems** (ZFS, Btrfs) and VM images.
- **`mmap` private mappings** and language runtimes (e.g. some GC/heap-fork tricks).

CoW makes `fork()` cheap and is why a 10 GB process can fork near-instantly — only the page table is copied, and pages are duplicated lazily on first write.

### Q41. [Theory] What is a futex, and why is it efficient?

A **futex (fast userspace mutex)** is the Linux primitive underlying modern mutexes/semaphores. Its insight: **the uncontended case should never enter the kernel.** The lock state is an integer in shared user memory; threads use an atomic compare-and-swap to acquire it in user space. Only when there is **contention** (a thread must block) does it make the `futex()` system call to sleep, and only on **unlock with waiters** does it syscall to wake one.

```
 lock:   CAS(state 0→1) succeeds in userspace → done, NO syscall (fast path)
         CAS fails (held)  → futex_wait syscall → sleep in kernel
 unlock: CAS(state 1→0); if waiters flagged → futex_wake syscall
```

This makes the common (uncontended) path a handful of instructions with zero kernel transitions, while still providing efficient blocking under contention. `pthread_mutex`, `std::mutex`, and the JVM's lock fast paths all build on futexes (or equivalents like Windows `WaitOnAddress`). It's the reason "locks are cheap when uncontended."

### Q42. [Theory] How does the JVM's lock optimization (biased/thin/fat locks) relate to OS primitives?

The JVM avoids OS-level (heavyweight, futex/monitor-backed) locking whenever possible, because syscalls and parking threads are expensive:

- **Thin/lightweight locks** — for uncontended `synchronized`, the JVM uses an atomic CAS on a mark word in the object header (pure userspace, no OS mutex). This is the common case.
- **Biased locking** — historically, if only one thread ever locked an object, the JVM "biased" the lock to it, skipping even the CAS. (Deprecated/disabled by default since JDK 15 and removed in later releases because the bookkeeping cost outweighed benefits on modern hardware.)
- **Fat/heavyweight locks** — only under real contention does the JVM inflate the monitor into an OS-backed structure where threads actually **park** (block in the kernel via a futex/condvar).

```
 uncontended → CAS on header (userspace)         ← thin lock
 contended   → inflate → OS monitor / futex park ← fat lock
```

The lesson mirrors futexes: stay in userspace for the fast path, fall to the kernel only when you must block. This is why uncontended `synchronized` is nearly free but heavily contended locks are slow.

### Q43. [Practical] How would you diagnose whether an application is CPU-bound, I/O-bound, or paging/thrashing?

Use OS observability tools and reason from the symptoms:

- **CPU-bound** — high user CPU%, low `iowait`, low context-switch-on-block rate. `top`/`mpstat` show cores saturated in user space; `pidstat -u` confirms. Fix: profile hot paths, parallelize, optimize algorithms.
- **I/O-bound** — high `iowait`, low user CPU, threads frequently in `D`/blocked state. `iostat -x` shows high disk `%util`/await; `pidstat -d` shows heavy read/write. Fix: batch I/O, async/`epoll`/`io_uring`, add caching, faster storage.
- **Paging/thrashing** — high **major** page-fault rate and swap activity. `vmstat 1` shows nonzero `si`/`so` (swap in/out) and high `wa`; `sar -B` shows `majflt/s` climbing while throughput drops. Fix: reduce working set, right-size heap, add RAM.

```
 vmstat 1
  r  b   swpd  free   si  so   us sy id wa
  9  0      0  2.1G    0   0   95  3  2  0   ← CPU-bound (us high, id/wa ~0)
  1  6      0  4.0G    0   0    8 12  0 80   ← I/O-bound (wa high)
  2  4  3.2G  120M  500 700   10  8  0 72   ← thrashing (si/so nonzero, free low)
```

For a JVM specifically, also check GC logs (a "CPU-bound" symptom is often GC), and use `async-profiler` (CPU vs wall-clock + off-CPU) to separate on-CPU work from blocking.

### Q44. [Practical] Explain how `mmap` works and when memory-mapped I/O beats `read`/`write`.

`mmap` maps a file (or anonymous memory) directly into a process's virtual address space. Accessing the mapped region triggers **demand paging**: the first touch of each page faults it in from the file via the **page cache**, and writes go back lazily (or on `msync`).

Advantages over `read`/`write`:

- **No explicit syscall per access and no extra copy** — `read` copies kernel page cache → user buffer; `mmap` lets you access the page cache pages directly (one fewer copy).
- **Lazy, on-demand loading** — only touched pages are read in; great for random access over large files (databases, search indexes).
- **Easy sharing** — multiple processes mapping the same file share physical pages.

When `read`/`write` wins:

- **Sequential streaming** — `read` with large buffers (or `sendfile`/`io_uring`) can be simpler and avoids page-fault overhead and TLB pressure.
- **Small files / one-shot** — mmap setup/teardown overhead isn't amortized.
- **Predictable error handling** — `read` errors are returns; mmap I/O errors surface as `SIGBUS`, which is awkward.

In Java this is `FileChannel.map(...)` → `MappedByteBuffer`. Many high-performance systems (Kafka, Lucene, LMDB-style stores) lean on mmap to let the OS page cache do the heavy lifting.

### Q45. [Behavioral] Describe a production incident you diagnosed that turned out to be an OS-level resource problem (memory, file descriptors, threads). How did you find and fix it?

Use a STAR structure and pick something concrete. A strong example:

- **Situation** — A Java service started throwing `Too many open files` and then failing health checks under load.
- **Task** — Restore service and find the root cause without simply bumping limits blindly.
- **Action** — Confirmed the OS file-descriptor count climbing via `ls /proc/<pid>/fd | wc -l` and `lsof -p`, correlated it with a code path that opened HTTP connections / streams without closing them (no try-with-resources). Verified the **soft `ulimit -n`** was the binding constraint. Short-term: raised the limit and restarted to stop the bleeding; longer-term: fixed the leak by wrapping the resource in try-with-resources and added a connection-pool with bounded size, plus a Micrometer gauge on open FDs to alert before exhaustion.
- **Result** — FD count flattened, no recurrence, and we caught a similar leak earlier in another service thanks to the new metric.

What interviewers look for: that you **distinguished symptom from cause**, used OS-level evidence (`/proc`, `lsof`, `ulimit`, `vmstat`), applied a safe mitigation *and* a real fix, and added observability to prevent regression — rather than just increasing limits.

---

## 🔴 Expert (15+ yrs)

### Q46. [Theory] How do NUMA architectures affect scheduling and memory allocation, and how do you optimize for them?

In a **NUMA (Non-Uniform Memory Access)** system, each CPU socket has its own local memory; accessing a remote socket's memory is slower (higher latency, lower bandwidth) because it traverses the interconnect.

```
 ┌── Node 0 ──┐         ┌── Node 1 ──┐
 │ CPUs 0-15  │◄═══════►│ CPUs 16-31 │   interconnect (slower)
 │ local RAM  │  remote │ local RAM  │
 └────────────┘  access └────────────┘
   local access fast        remote access ~1.5-2x slower
```

OS and app implications:

- **Scheduler NUMA-awareness** — keep a thread on the node where its memory lives; Linux does **NUMA balancing** (migrates pages toward the accessing node or threads toward their pages).
- **First-touch allocation** — physical pages are allocated on the node of the thread that *first writes* them, so initialize data on the thread that will use it.
- **Pinning** — use `numactl`/`taskset` or `mbind` to pin threads and memory to a node for predictable latency.
- **JVM** — large heaps span nodes; G1/ZGC and `-XX:+UseNUMA` make allocation NUMA-aware so each GC thread/region favors local memory.

Getting this wrong on a big multi-socket box can cost 30–50% throughput on memory-bound workloads — a classic deep-systems optimization.

### Q47. [Theory] What memory-consistency / ordering guarantees does hardware provide, and how do memory barriers relate to the OS and the JVM?

Modern CPUs and compilers **reorder** memory operations for performance, so the order one core writes is not necessarily the order another core observes. The **memory consistency model** defines what reorderings are allowed:

- **x86 (TSO — Total Store Order)** — relatively strong: only store-load reordering is permitted (a store can be delayed past a later load to a different address).
- **ARM/POWER (weak ordering)** — far more reordering allowed; you need explicit barriers for almost any cross-thread ordering.

**Memory barriers (fences)** are instructions that constrain reordering: `LoadLoad`, `StoreStore`, `LoadStore`, `StoreLoad` (the most expensive). They're how locks and lock-free code get correctness.

This connects upward to the **Java Memory Model (JMM)**: `volatile`, `synchronized`, and `final` impose **happens-before** ordering, which the JIT lowers to the appropriate hardware fences for the target ISA. A `volatile` write emits a StoreStore + StoreLoad fence on the write and a LoadLoad/LoadStore on the read, so writes before it are visible to a thread that reads it. The expert point: correctness of concurrent code rests on a stack of models — hardware MCM → compiler → language MM — and "it works on x86" can break on ARM precisely because x86's TSO hides missing barriers.

### Q48. [Theory] How do modern kernels reduce syscall and I/O overhead (io_uring, vDSO, zero-copy)?

Syscalls and copies are the dominant cost in I/O-heavy systems; modern kernels attack this on several fronts:

- **vDSO (virtual dynamic shared object)** — maps certain "syscalls" (e.g. `gettimeofday`, `clock_gettime`) into user space so they execute **without a mode switch**, reading a kernel-maintained page directly.
- **`io_uring`** (Linux 5.1+) — a pair of **shared ring buffers** (submission + completion) between user and kernel. Apps batch many I/O requests and reap completions **without a syscall per operation**, optionally with kernel-side polling (`SQPOLL`) for *zero* syscalls on the hot path. Hugely reduces overhead vs `epoll`+`read`.
- **Zero-copy** — `sendfile`, `splice`, and `MSG_ZEROCOPY` move data from page cache to socket **without copying through user space**. A file→socket transfer avoids the kernel→user→kernel double copy.

```
 classic read+write: disk → page cache → user buf → socket buf → NIC  (2 copies, 2 syscalls)
 sendfile/splice:    disk → page cache ───────────► socket/NIC        (0 user copies, 1 syscall)
```

The throughput wins are large (web/file servers, message brokers). Java exposes `FileChannel.transferTo` (sendfile) and, via newer libraries/JDK work, access to io_uring-style async I/O.

### Q49. [Theory] Compare the threading models: 1:1, N:1, and M:N — and where do Java virtual threads and goroutines fit?

The mapping between user-level threads and kernel threads defines the model:

- **1:1 (kernel-level)** — each user thread is a kernel thread. True parallelism, OS scheduling, blocking is fine, but threads are heavy (MB stacks, syscall to create). This is Java **platform** threads, `std::thread`, pthreads.
- **N:1 (user-level/green)** — many user threads on one kernel thread, scheduled in user space. Cheap and fast to switch, but **a single blocking syscall blocks all of them** and can't use multiple cores. Old Java green threads, early Ruby.
- **M:N (hybrid)** — M user threads multiplexed over N kernel threads. Cheap threads *and* multicore + non-blocking — but the runtime scheduler is complex.

```
 1:1   user─┬─►kthread (each)         heavy, simple, blocks fine
 N:1   users┴─►one kthread            light, but no parallelism, blocking kills all
 M:N   users──►pool of kthreads       light + parallel (complex runtime)
```

**Java virtual threads (Loom, stable in JDK 21)** are effectively **M:N**: millions of cheap virtual threads are mounted on a small pool of carrier (platform) kernel threads, and a blocking call **unmounts** the virtual thread and frees the carrier. **Goroutines** are similar M:N with the Go runtime scheduler. The big win is writing simple blocking-style code that scales like async — the runtime, not the kernel, schedules the cheap threads, and blocking I/O no longer wastes a kernel thread.

### Q50. [Coding] Implement a counting semaphore from a mutex and a condition variable.

A common deep-dive: build a higher-level primitive from lower-level ones. A counting semaphore is a non-negative counter with blocking `acquire` and waking `release`.

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

class CountingSemaphore {
    private int permits;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();

    CountingSemaphore(int initialPermits) {
        if (initialPermits < 0) throw new IllegalArgumentException();
        this.permits = initialPermits;
    }

    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (permits == 0)        // wait until a permit is free
                available.await();      // releases lock, blocks; re-checks on wake
            permits--;
        } finally { lock.unlock(); }
    }

    public void release() {
        lock.lock();
        try {
            permits++;
            available.signal();         // wake one waiter
        } finally { lock.unlock(); }
    }
}
```

Critical correctness points: (1) the **`while` loop** re-checks `permits` after waking — `await` can return spuriously, and another thread may have taken the permit first; (2) `await` atomically releases the lock and sleeps, then re-acquires before returning, which is what makes the wait/signal handoff race-free. This is essentially how `java.util.concurrent.Semaphore` works internally (it actually builds on `AbstractQueuedSynchronizer`, but the semantics are identical). Note signaling under the lock is correct, if slightly less optimal than signaling after unlock.

### Q51. [Theory] How does a page cache work, and what is the writeback/dirty-page mechanism?

The **page cache** is the kernel's in-RAM cache of file data: reads populate it, and subsequent reads hit RAM instead of disk. It uses otherwise-free memory, which is why "free" RAM on Linux is low — it's caching files.

Writes are typically **buffered (write-back)**: a `write()` marks pages **dirty** in the page cache and returns immediately, *before* data hits disk. Background flusher threads (`pdflush`/`bdi` writeback) later persist dirty pages based on thresholds and age.

```
 write() → page cache page marked DIRTY → returns fast
                         │ (later)
   flusher threads ──────► write dirty pages to disk (writeback)
   triggers: dirty_ratio / dirty_background_ratio, expiry, fsync(), sync()
```

Consequences and trade-offs:

- **Durability** — data in dirty pages is **not** on disk until flushed; a crash loses it. `fsync`/`fdatasync` force persistence (databases call it on commit; the `O_DSYNC`/`force()` path in Java).
- **Throughput** — write-back batches and reorders writes, hugely improving throughput, and absorbs bursts.
- **Tuning** — `vm.dirty_ratio`/`dirty_background_ratio` control how much dirty data accumulates before throttling/flushing; set too high and a flush stall causes latency spikes.

This is central to database and message-broker design: the durability-vs-throughput knob *is* fsync policy on top of the page cache.

### Q52. [Theory] Walk through what happens, end to end, when a process reads a file that isn't in memory.

Tracing a `read()` of a cold file exercises most of the OS stack:

```
 1. App calls read(fd, buf, n) → syscall → trap to kernel (user→kernel mode)
 2. Kernel resolves fd → open file → inode; computes file offset → which pages
 3. Checks the PAGE CACHE for those pages → MISS (cold file)
 4. Filesystem maps file offset → logical block numbers (extents/inode pointers)
 5. Block layer queues a request; I/O scheduler orders it
 6. Device driver programs a DMA transfer: disk → RAM (page cache pages)
 7. Disk completes → raises an INTERRUPT → ISR acks, marks I/O done
 8. Kernel copies (or maps) page-cache data into the user buffer
 9. read() returns; mode switches back to user; bytes are now also cached
```

Key OS concepts touched: **system call** + mode switch (1), **VFS/inode** lookup (2,4), **page cache** (3,9), **DMA** (6), **interrupt** handling (7), and the user/kernel **copy** (8). A second `read` of the same file is a page-cache hit and skips steps 4–7 entirely — which is why warm reads are orders of magnitude faster. If instead the file were `mmap`ed, step 1 becomes a page fault on first touch and step 8 disappears (the user accesses the cache pages directly).

### Q53. [Behavioral] Tell me about a time you had to make a hard trade-off between throughput and latency (or memory vs speed) at the OS/runtime level. How did you decide?

Pick a real decision and show structured judgment. Example using STAR:

- **Situation** — A high-throughput ingestion service was hitting periodic multi-hundred-ms latency spikes. Root cause: large dirty-page buildup plus full GC pauses correlating with flush stalls.
- **Task** — Cut tail latency (p99) without tanking overall throughput, on fixed hardware.
- **Action** — Quantified the trade-off rather than guessing: lowered `vm.dirty_background_ratio` so writeback started earlier (smaller, more frequent flushes → lower spikes but slightly less batching), switched the JVM to a low-pause collector (ZGC) accepting a small throughput/footprint cost, and pinned the I/O threads to NUMA-local cores. Measured each change against p50/p99/throughput on a load replay before shipping.
- **Result** — p99 dropped ~4x with a ~5% throughput reduction we deemed acceptable given the SLA was latency-driven. I documented the trade-off and the tuning knobs so on-call understood *why* throughput was slightly lower by design.

What interviewers want: that you **made the trade-off explicit and measurable**, tied it to the actual business/SLA priority (latency here), changed one variable at a time with evidence, and communicated the reasoning so the decision was defensible and reversible — not that you found a magic setting with no downside.

### Q54. [Theory] How do hardware huge pages and transparent huge pages (THP) help and hurt, and when would you disable THP?

**Huge pages** use larger page sizes (2 MB or 1 GB on x86-64) instead of 4 KB. The benefit is **fewer TLB entries cover more memory**, slashing TLB misses and page-walk costs for large working sets — valuable for databases and large-heap JVMs.

- **Explicit huge pages** (`hugetlbfs`, `-XX:+UseLargePages`) are reserved up front and pinned — predictable, used deliberately by databases/JVMs.
- **Transparent Huge Pages (THP)** — the kernel *automatically* promotes ranges of 4 KB pages to 2 MB and runs a background `khugepaged` to defragment/coalesce. Zero app changes.

The catch with THP:

- **Latency spikes** — promotion and defragmentation can cause stalls, and allocating a 2 MB page may trigger expensive memory **compaction**, producing the very tail-latency jitter latency-sensitive systems hate.
- **Memory bloat / internal fragmentation** — a sparsely used region gets a full 2 MB page.

This is why **databases (MongoDB, Redis, Oracle) and many low-latency services recommend disabling THP** (`madvise` mode or off) while still using *explicit* huge pages where they help. The expert nuance: huge pages = good (deterministic, reserved); THP = often bad for tail latency despite good average throughput.

### Q55. [Theory] What is the difference between a trap, a fault, and an abort (exception classes), and why does the distinction matter?

These are the three classes of synchronous CPU **exceptions**, distinguished by *what address the saved instruction pointer holds* and whether the faulting instruction can be restarted:

- **Fault** — a *correctable* exception reported **before** the instruction completes; the saved IP points at the **faulting instruction**, so after the handler fixes the condition the instruction is **restarted**. Example: a **page fault** — the kernel pages in the data, then the load re-executes successfully.
- **Trap** — reported **after** the instruction; the saved IP points at the **next** instruction. Used for things that should continue, like **breakpoints**, debug single-step, and **system calls** (`int 0x80`/`syscall` are trap-like — you don't re-run the instruction, you proceed).
- **Abort** — a **severe, unrecoverable** error where precise instruction restart isn't possible (e.g. machine-check / hardware error, double fault). The program/kernel typically cannot continue.

Why it matters: the **fault** semantics — saved IP = faulting instruction, restartable — are *exactly* what makes **demand paging** and **copy-on-write** work: the kernel handles the fault, fixes the mapping, and transparently re-runs the instruction as if nothing happened. If page faults were traps (skipped the instruction), demand paging would be impossible. Understanding this is what separates "I memorized page faults" from "I understand why the architecture lets them be transparent."

## 🧩 Extended Questions — Set 1: Deeper theory & internals

These go below the surface answers above into the mechanisms the kernel and hardware actually use — PTE bits, TLB shootdowns, lock-free internals, memory-reclaim plumbing, and scheduler accounting. The lens is *how it really works*, not *what it is*.

### 🟢 — extended

#### Q56. [Theory] What exactly lives inside a Process Control Block (PCB), and where does the kernel keep it?

The **PCB** (on Linux, `struct task_struct`) is the kernel's per-task bookkeeping record. It holds everything the OS needs to suspend and later resume a task:

- **Identity** — PID, parent PID, thread-group ID, user/group IDs, security context.
- **State** — RUNNING / INTERRUPTIBLE / UNINTERRUPTIBLE / STOPPED / ZOMBIE, plus the exit code.
- **CPU context** — saved registers, program counter, and stack pointer (filled in on a context switch; while running, the live values are in the hardware registers, not the PCB).
- **Memory** — a pointer to the `mm_struct` (address space: page-table root, VMAs); kernel threads share the kernel `mm` and have this NULL.
- **Scheduling** — priority/nice, scheduling class, `vruntime`/deadline, accumulated CPU time, the run-queue node.
- **Resources** — the open-file table (`files_struct`), signal handlers, pending signals, namespaces, cgroup membership, working directory, root directory.

It lives in **kernel memory**, never directly addressable from user space — you observe a curated view through `/proc/<pid>/`. The PCB is what makes a task a *first-class kernel object*: a context switch is essentially "save live registers into PCB A, load PCB B's saved registers, switch the page-table root if the `mm` differs."

#### Q57. [Theory] Walk through every field in a page-table entry (PTE) and what each bit does.

A PTE is more than a frame number; the control bits are where most of the OS's memory machinery lives. On x86-64 a 4 KB-page PTE contains:

| Bit / field | Meaning |
|-------------|---------|
| **Present (P)** | 1 = mapped to a frame; 0 = fault on access (used for demand paging, swapped-out pages, guard pages). |
| **Read/Write (R/W)** | 0 = read-only. Clearing this on a shared page is how **copy-on-write** triggers a fault on write. |
| **User/Supervisor (U/S)** | 0 = kernel-only; faults if user code touches it. Enforces the user/kernel boundary per page. |
| **Accessed (A)** | Set by hardware on any access. The basis for **clock/second-chance** replacement — the kernel reads and clears it to estimate recency. |
| **Dirty (D)** | Set by hardware on a write. Tells the kernel a page must be **written back** before eviction; clean pages can be dropped for free. |
| **PWT / PCD** | Page-level write-through / cache-disable — control cacheability (e.g. for memory-mapped device registers). |
| **PS (Page Size)** | At higher levels, 1 = this entry maps a **huge page** (2 MB / 1 GB) directly instead of pointing to a lower table. |
| **Global (G)** | The translation survives a TLB flush on `CR3` reload (kernel mappings shared across all processes). |
| **NX (bit 63)** | No-Execute — data pages marked NX can't run code, the hardware foundation of W^X / DEP. |

When the present bit is 0, the *rest* of the entry is free for the OS to store a swap slot identifier — that's how the kernel remembers where a swapped-out page went. So a PTE is simultaneously a translation, a permission set, and (when not present) a pointer into swap.

#### Q58. [Theory] How does a thread actually go to sleep and get woken — what is a wait queue?

"Blocked" is not busy-waiting; the thread is removed from the run queue entirely. The mechanism is a **wait queue** (Linux `wait_queue_head_t`):

```
 sleep:  set state = INTERRUPTIBLE
         add task to the resource's wait queue
         schedule()  ← voluntarily gives up the CPU; scheduler picks someone else
 wake:   another task/ISR calls wake_up(&queue)
         → each waiter set back to RUNNABLE, placed on a run queue
         → it resumes inside schedule(), re-checks its condition
```

The subtle correctness rule is the **condition re-check after waking** (the `while` loop in `wait`/`await`), because a wake-up only means "the condition *might* now hold," not that it does — another thread may have consumed the resource first. The kernel pattern (`wait_event(queue, condition)`) bakes this in: it atomically checks the condition, adds to the queue, and sleeps, avoiding the lost-wakeup race where the event fires between your check and your sleep. This is exactly the kernel analogue of `Object.wait()` / `Condition.await()` in Java.

#### Q59. [Practical] In a Linux thread dump or `ps`, what's the difference between the `S`, `D`, `R`, and `Z` states?

These are the kernel task states surfaced by `ps`/`top`, and telling them apart is core to diagnosis:

- **R (Running/Runnable)** — on a CPU now, or on a run queue waiting for one. High `R` count vs core count means CPU saturation.
- **S (Interruptible Sleep)** — blocked waiting for an event, and **signals can wake it** (the common idle state: waiting on a socket, a lock, `sleep()`). Healthy.
- **D (Uninterruptible Sleep)** — blocked in the kernel and **cannot be interrupted by signals**, almost always waiting on I/O (disk, NFS). A pile of `D` tasks is the classic **I/O-bound / stuck-storage** signature; you can't even `kill -9` a truly `D` task because it won't process the signal until the I/O returns.
- **Z (Zombie)** — finished, but the parent hasn't `wait()`ed to reap the exit status. The PCB lingers holding just the exit code. Many zombies = a parent that isn't reaping children (a `SIGCHLD`/`wait` bug).

The practical tell: lots of `D` → investigate storage/NFS; lots of `R` → CPU bound; growing `Z` → a reaping bug, not a resource shortage.

#### Q60. [Theory] What is the difference between concurrency and parallelism at the OS level?

**Concurrency** is a structuring property — multiple tasks are *in progress* over the same period, making independent progress by interleaving. **Parallelism** is an execution property — multiple tasks literally run at the *same instant* on different cores.

```
 Concurrency (1 core):  A─B─A─B─A─B   interleaved by the scheduler (time-sliced)
 Parallelism (2 cores): A─A─A─A
                        B─B─B─B        simultaneous
```

You can have concurrency without parallelism (one core, many time-sliced threads — an event loop) and parallelism is just concurrency that the hardware happens to execute simultaneously. The OS provides the **mechanism for both**: the scheduler interleaves runnable threads (concurrency), and on a multicore machine it places them on different cores (parallelism). The reason the distinction matters: concurrency is about *correctness of shared state* (you need synchronization the moment two flows interleave, even on one core, because a context switch can land mid-operation), whereas parallelism is about *throughput*. A program can be perfectly concurrent and still get zero speedup if it's serialized by a global lock (Amdahl's law).

#### Q61. [Practical] How can you observe context-switch and page-fault counts for a running process?

Several layered sources, from coarse to precise:

- **`/proc/<pid>/status`** — `voluntary_ctxt_switches` (gave up the CPU by blocking) vs `nonvoluntary_ctxt_switches` (preempted). A high *non-voluntary* count means heavy CPU contention/preemption; high *voluntary* means the task blocks a lot (I/O or lock waits).
- **`/proc/<pid>/stat`** — `minflt` and `majflt` (minor vs major page faults) — rising `majflt` is the paging/thrashing tell.
- **`pidstat -w 1`** (context switches/sec) and **`pidstat -r 1`** (faults/sec) — the per-process time series.
- **`vmstat 1`** — system-wide `cs` (context switches), `in` (interrupts), and `si`/`so` (swap).
- **`perf stat -e context-switches,page-faults,minor-faults,major-faults <cmd>`** — hardware/kernel counters for a precise run.

```
 grep ctxt /proc/<pid>/status
   voluntary_ctxt_switches:    150123   ← blocks often (I/O or locks)
   nonvoluntary_ctxt_switches:   4021   ← occasionally preempted
```

Reading these is how you turn a vague "it's slow" into "it's doing 200k voluntary switches/sec because of lock contention."

#### Q62. [Theory] What is a guard page, and how does the OS detect a stack overflow?

A **guard page** is a page deliberately mapped **not-present** (or with no access permission) placed just past the end of a region — most importantly at the bottom of each thread's stack. When the stack grows into it, the access triggers a **page fault** the kernel recognizes as an overflow rather than a normal grow-the-stack fault, and it delivers `SIGSEGV` (or raises `StackOverflowError` in the JVM, which sizes a guard/yellow/red zone for exactly this).

```
 high addr  ┌─────────────┐
            │ stack (grows│
            │   downward) │
            ├─────────────┤
            │ GUARD PAGE  │  ← not-present; touching it = fault = overflow caught
            ├─────────────┤
            │  (heap/...)  │
 low addr   └─────────────┘
```

Without the guard page a runaway recursion would silently scribble over adjacent memory (the heap or another thread's stack) — a memory-corruption bug instead of a clean, immediate fault. This is why the JVM can throw a recoverable `StackOverflowError` precisely at the moment of overflow: the guard page turns silent corruption into a deterministic trap. The same trick implements *automatic stack growth* — the kernel intercepts the fault on the guard, allocates a fresh page, and moves the guard down.

### 🟡 — extended

#### Q63. [Theory] What is a TLB shootdown and why is it expensive?

The TLB is **per-core** and the hardware does not keep TLBs coherent with each other. So when one core changes a mapping (unmaps a page, changes permissions, e.g. during `munmap`, CoW, or page migration), every other core that might have the *stale* translation cached must flush it. The originating core sends an **inter-processor interrupt (IPI)** to those cores; each handles the IPI by invalidating the affected TLB entry (`invlpg`) and acknowledges. The initiator **spins until all acknowledge** — this synchronous, cross-core handshake is the **TLB shootdown**.

```
 Core 0: munmap(page) → must invalidate everywhere
   ├─IPI─► Core 1: invlpg, ack
   ├─IPI─► Core 2: invlpg, ack
   └─IPI─► Core 3: invlpg, ack
 Core 0 waits for all acks, then proceeds   ← O(cores) latency, all stalled
```

It's expensive because it's an interrupt-driven barrier across cores: the more cores share the address space, the more IPIs and the longer the stall. This is a real scalability wall for big multithreaded processes that frequently `mmap`/`munmap` or trigger CoW. Mitigations: batch unmaps, use **PCID/ASID** tagging so a context switch needn't flush, lazy-TLB tricks for idle cores, and avoiding gratuitous remapping. It's also why frequent small `mmap`/`munmap` can be slower than reusing an arena.

#### Q64. [Theory] How does `fork()` actually work step by step, and what does CoW copy vs share?

`fork()` creates a near-duplicate child but does **not** copy the address space eagerly:

```
 1. Allocate a new task_struct (PCB) + PID for the child.
 2. Copy the mm_struct structure and the VMA list (the *map* of regions).
 3. Copy the PAGE TABLES, but mark every writable private page READ-ONLY in BOTH
    parent and child, and flag the VMAs copy-on-write.
 4. Increment refcounts on the shared physical frames (no data copied).
 5. Child returns 0, parent returns the child PID.
```

After fork, parent and child **share every physical page read-only**. The moment either writes a CoW page, a **write-protection fault** fires; the kernel allocates a fresh frame, copies that single page, marks both copies writable (decrementing the shared refcount), and restarts the write. So what's copied eagerly: the *metadata* (PCB, VMA list, page tables). What's shared until touched: the *actual data pages*. What stays shared even after writes: genuinely read-only pages (the executable text, read-only mmaps) — they never need copying.

This is why forking a 10 GB process is fast, but also why a fork **doubles page-table memory** and why a child that writes a lot can suddenly balloon RSS as CoW faults duplicate pages. It's also the classic "fork in a multithreaded program is dangerous" caveat: only the calling thread survives in the child, but locks held by other threads stay locked forever.

#### Q65. [Coding] Implement a read-write lock (multiple readers, single writer) with writer preference.

A reader-writer lock allows concurrent readers but exclusive writers. Writer preference avoids writer starvation under a steady reader stream. The classic monitor implementation tracks active readers, an active-writer flag, and waiting-writer count.

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

class WriterPreferredRWLock {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition okToRead  = lock.newCondition();
    private final Condition okToWrite = lock.newCondition();

    private int activeReaders = 0;
    private int waitingWriters = 0;
    private boolean writerActive = false;

    public void lockRead() throws InterruptedException {
        lock.lock();
        try {
            // Yield to writers: wait while a writer holds or any writer is queued.
            while (writerActive || waitingWriters > 0)
                okToRead.await();
            activeReaders++;
        } finally { lock.unlock(); }
    }

    public void unlockRead() {
        lock.lock();
        try {
            if (--activeReaders == 0)
                okToWrite.signal();      // last reader out lets a writer in
        } finally { lock.unlock(); }
    }

    public void lockWrite() throws InterruptedException {
        lock.lock();
        try {
            waitingWriters++;
            while (writerActive || activeReaders > 0)
                okToWrite.await();
            waitingWriters--;
            writerActive = true;
        } finally { lock.unlock(); }
    }

    public void unlockWrite() {
        lock.lock();
        try {
            writerActive = false;
            // Prefer waking a queued writer; otherwise release all readers.
            if (waitingWriters > 0) okToWrite.signal();
            else                    okToRead.signalAll();
        } finally { lock.unlock(); }
    }
}
```

The writer-preference policy is the `waitingWriters > 0` guard in `lockRead`: new readers stop entering as soon as a writer is queued, so a continuous stream of readers can no longer starve writers. The trade-off is the mirror risk — readers can starve under continuous writers — so production locks (Java's `ReentrantReadWriteLock`) make the policy configurable (fair vs non-fair) and add reentrancy and lock downgrading on top of this skeleton.

#### Q66. [Theory] How do spinlocks differ from blocking mutexes, and when is each correct?

A **spinlock** busy-waits — it loops on an atomic test until the lock is free, never sleeping. A **blocking mutex** parks the thread (the futex slow path) so the CPU runs something else.

The decision is about the **expected wait time vs the cost of a context switch (~1–5 µs)**:

- **Spinlock wins** when the critical section is *very short* and the holder runs on *another core* — spinning a few hundred nanoseconds is cheaper than two context switches to sleep and wake. This is why **kernels use spinlocks** to protect tiny critical sections, especially where you *can't* sleep (interrupt context).
- **Blocking mutex wins** when the critical section can be long or the holder might be preempted — spinning then wastes a whole core doing nothing.

The catastrophic case is **spinning on a single core**: the spinner holds the only CPU, so the holder can never run to release the lock — a livelock. That's why pure user-space spinlocks are dangerous (the holder can be descheduled) and real implementations use an **adaptive mutex**: spin briefly (betting the holder is running on another core and will release soon), then fall back to blocking if it doesn't. The kernel even has special spinlock variants that disable preemption/interrupts while held to bound the wait.

#### Q67. [Theory] What does `volatile` guarantee at the hardware level, and what does it NOT?

In Java, `volatile` provides two things: **visibility** (a write is published to main memory and subsequent reads see the latest value, not a cached register/local copy) and **ordering** (it establishes happens-before — everything before a volatile write is visible to a thread after it reads that volatile). The compiler lowers this to memory fences: roughly a StoreStore+StoreLoad fence around the write and LoadLoad+LoadStore around the read, becoming real barriers (or no-ops on TSO) per ISA.

What it **does not** give you is **atomicity of compound operations**. `volatile int x; x++;` is still a race — the read-modify-write is three steps, and volatile only makes each individual read and write visible, not the whole sequence indivisible.

```
 volatile flag:  one writer sets, many readers see it      ✅ (visibility/ordering)
 volatile count: count++ from N threads                    ❌ (lost updates — not atomic)
```

For atomic compound updates you need `AtomicInteger`/`getAndIncrement` (a hardware CAS / `lock xadd`) or a lock. The crisp interview line: *volatile is for visibility and ordering of a single variable; it is not a substitute for atomicity.* (The C/C++ `volatile` is even weaker — it's about not optimizing away memory accesses to device registers and carries **no** cross-thread ordering guarantee, which is a common cross-language confusion.)

#### Q68. [Practical] Why is `ulimit -n` (max open file descriptors) often the first limit a server hits, and how do FDs map to kernel objects?

Every socket, pipe, open file, `epoll` instance, `eventfd`, and even `timerfd` consumes a **file descriptor** — a small integer index into the process's file-descriptor table. A descriptor points to a kernel **open file description** (offset, flags), which points to the underlying object (inode, socket). A busy server holds one FD *per concurrent connection* plus listening sockets, log files, and config — so a connection-heavy service exhausts FDs long before it runs out of RAM or CPU.

```
 fd table (per-process)        open file descriptions       objects
   0 ─► ──────────────────────► (offset, flags) ─────────► tty
   3 ─► ──────────────────────► ──────────────────────────► socket (conn 1)
   4 ─► ──────────────────────► ──────────────────────────► socket (conn 2)
   ...                                                       inode / pipe / epoll
```

`ulimit -n` (the **soft** `RLIMIT_NOFILE`) caps the table size; exceeding it makes `open`/`accept`/`socket` return `EMFILE` ("Too many open files"), which typically surfaces as dropped connections or failed health checks. Two further nuances: the **system-wide** cap is `/proc/sys/fs/file-max`, and FD leaks (not closing in a `finally`/try-with-resources) make a service slowly walk into the limit under load. The right fix is usually *find the leak and bound the pool* before raising the limit — raising it alone just delays the crash.

#### Q69. [Theory] How does an epoll-based event loop scale to many connections compared to select/poll?

`select`/`poll` are **O(n)** per call: you pass the *entire* set of fds every time, and the kernel scans all of them to find which are ready. With 50k idle connections and a handful ready, you still walk 50k fds on every loop iteration — and copy that whole set across the user/kernel boundary each call.

`epoll` is **O(ready)**, not O(total). You register interest *once* (`epoll_ctl`), and the kernel maintains a callback-driven ready list internally; `epoll_wait` returns only the fds that actually became ready:

```
 select/poll:  every call → copy all N fds in → kernel scans all N → O(N)
 epoll:        register once; kernel keeps a ready-list via per-fd callbacks
               epoll_wait returns only the M ready fds → O(M), M ≪ N
```

This is the **C10K → C10M** enabler: cost scales with *activity*, not with the number of connections. Edge-triggered (`EPOLLET`) mode notifies only on *transitions*, cutting wakeups further but requiring you to drain the socket fully each time. `io_uring` goes a step beyond by removing even the per-operation syscall. The same evolution exists elsewhere: BSD/macOS `kqueue`, Windows IOCP/registered I/O. Java's NIO `Selector` is backed by `epoll`/`kqueue`/IOCP under the hood.

#### Q70. [Coding] Implement a non-blocking counter using compare-and-swap (CAS) and explain the ABA problem.

CAS is the hardware atomic the whole lock-free world is built on: atomically, "if this location still equals *expected*, set it to *new* and report success." A lock-free counter retries until its CAS wins.

```java
import java.util.concurrent.atomic.AtomicInteger;

class LockFreeCounter {
    private final AtomicInteger value = new AtomicInteger(0);

    public int increment() {
        int cur, next;
        do {
            cur  = value.get();           // read current
            next = cur + 1;               // compute new
            // CAS: only succeeds if no one changed `value` since we read it.
        } while (!value.compareAndSet(cur, next));
        return next;                      // someone else won? loop and retry
    }
}
```

The loop is the essence of optimistic concurrency: assume no contention, and on conflict (CAS fails because another thread moved the value) just retry. There are no locks, so no deadlock and no priority inversion — but under heavy contention threads burn CPU retrying (livelock-ish), which is why this shines at low/medium contention.

The **ABA problem**: CAS only checks *value equality*, not whether the value *changed and changed back*. Thread 1 reads A, stalls; thread 2 changes A→B→A; thread 1's CAS(A→…) succeeds even though the world moved underneath it. For a plain integer counter this is harmless, but for pointer-based structures (a lock-free stack reusing a freed node) it corrupts state. The fix is a **version/stamp**: pair the value with a monotonically increasing tag (`AtomicStampedReference` in Java, or a double-width CAS) so A-with-tag-1 ≠ A-with-tag-3.

### 🟠 — extended

#### Q71. [Theory] How does Linux's memory reclaim work — kswapd, the LRU lists, direct reclaim, and the OOM killer?

Under memory pressure the kernel reclaims pages along an escalating path:

1. **Watermarks** — each memory zone has `low`/`min`/`high` watermarks. Crossing `low` wakes **`kswapd`**, the background reclaim daemon, which scans and frees pages back up to `high` *without blocking allocators*.
2. **Active/Inactive LRU lists** — pages live on per-zone **active** and **inactive** lists (separately for file-backed and anonymous pages). Reclaim moves pages active→inactive, and evicts from the inactive tail. Clean file pages are dropped for free; **dirty** pages must be written back first; **anonymous** pages must be **swapped** out (needs swap space).
3. **Direct reclaim** — if an allocation can't be satisfied and kswapd is behind, the allocating thread itself **synchronously** reclaims before its allocation returns. This stalls the application — a latency spike that looks like a random pause.
4. **OOM killer** — if reclaim can't free enough and there's no swap headroom, the **OOM killer** picks a victim by `oom_score` (roughly proportional to RSS, tunable via `oom_score_adj`) and SIGKILLs it to recover.

```
 free RAM falls ─► kswapd wakes (async) ─► reclaim from inactive LRU
   still short ─► DIRECT RECLAIM (caller stalls) ─► still short ─► OOM kill
```

The interview-grade insights: **direct reclaim is the hidden latency villain** (your p99 spike during a memory squeeze); `vm.swappiness` tunes the balance between evicting file cache vs swapping anonymous pages; and in containers the **cgroup memory controller** runs this whole machinery *per-cgroup*, so a container can OOM-kill its own processes while the host has free RAM — the cause of mysterious `OOMKilled` pod restarts in Kubernetes.

#### Q72. [Theory] What is RCU (Read-Copy-Update) and why is it central to kernel scalability?

**RCU** is a synchronization technique optimized for **read-mostly** data where readers must be extremely cheap. Readers take **no locks and write nothing** — on most architectures a read-side critical section is essentially free (just disabling preemption). Writers don't mutate in place; they **copy** the object, modify the copy, and **atomically swap a pointer** to publish it. Old readers keep seeing the old version; new readers see the new one.

The hard part is *when can the old version be freed?* It must wait until **every reader that could be holding a reference to it has finished** — a **grace period**. RCU defines this via *quiescent states*: once every CPU has passed through a point where it can't be in a read-side section (a context switch, idle, or user mode), all pre-existing readers are guaranteed gone, and the old copy can be reclaimed (`synchronize_rcu()` / `call_rcu()`).

```
 reader:  rcu_read_lock(); use ptr; rcu_read_unlock();   ← no locks, no writes
 writer:  new = copy(old); modify(new);
          rcu_assign_pointer(ptr, new);     ← publish atomically
          synchronize_rcu();                ← wait out existing readers
          free(old);                        ← now safe
```

Why it matters: it gives **wait-free, zero-contention reads** that scale linearly with cores, with the entire cost shifted to writers. That's exactly right for kernel structures read constantly and updated rarely — the dentry cache, routing tables, network device lists. It eliminates the reader-side cache-line bouncing that makes even read-write locks scale poorly at high core counts.

#### Q73. [Theory] How do per-CPU run queues and load balancing work in a modern multicore scheduler?

A single global run queue would be a scalability disaster: every scheduling decision on every core would contend on one lock and bounce its cache line across the machine. So modern schedulers (Linux CFS/EEVDF) use **per-CPU run queues** — each core has its own runnable-task structure (its own red-black tree) and schedules from it **lock-free with respect to other cores** in the common case.

The cost of partitioning is **imbalance**: one core's queue can pile up while another idles. A periodic **load balancer** migrates tasks to even things out, but it's *hierarchy-aware* to respect cache and NUMA topology, described by **scheduling domains**:

```
 balance cheaply & often within an SMT pair / shared-L2  (migration nearly free)
 balance less often across an LLC / cache cluster        (loses L2/L3 warmth)
 balance rarely across NUMA nodes                        (loses memory locality — costly)
```

Migrating a task across cores throws away its warm caches and (across NUMA) makes its memory remote, so the balancer weighs imbalance against migration cost, and it pulls work toward **idle** cores aggressively (idle balancing) while being conservative about cross-node moves. There's also **CPU affinity** (`taskset`/`sched_setaffinity`) to pin tasks, and the scheduler tracks per-task "cache hotness" to avoid bouncing a task that just ran. The big idea: scalability comes from per-CPU locality, and the balancer's whole job is correcting the resulting imbalance *without* destroying that locality.

#### Q74. [Coding] Implement a thread-safe lazy singleton three ways and explain the memory-model bug each fixes.

Lazy initialization under concurrency is a memory-model minefield. Here are the three correct idioms.

```java
// 1) Double-checked locking — needs `volatile` or it is BROKEN.
class DclSingleton {
    private static volatile DclSingleton instance;   // volatile is mandatory
    private DclSingleton() {}
    static DclSingleton get() {
        DclSingleton local = instance;               // read volatile once
        if (local == null) {
            synchronized (DclSingleton.class) {
                local = instance;
                if (local == null)                   // re-check under lock
                    instance = local = new DclSingleton();
            }
        }
        return local;
    }
}

// 2) Initialization-on-demand holder — JVM guarantees class-init safety, no locking.
class HolderSingleton {
    private HolderSingleton() {}
    private static class Holder { static final HolderSingleton INSTANCE = new HolderSingleton(); }
    static HolderSingleton get() { return Holder.INSTANCE; }   // lazy + thread-safe
}

// 3) Enum — the simplest correct singleton; serialization- and reflection-safe.
enum EnumSingleton { INSTANCE; public void work() { /* ... */ } }
```

The bug double-checked locking fixes is **publication via reordering**: `instance = new DclSingleton()` is *not* atomic — it (a) allocates, (b) runs the constructor, (c) assigns the reference. Without `volatile`, the compiler/CPU may reorder so the reference is assigned *before* the constructor finishes; a second thread sees a non-null `instance` and uses a **half-constructed object**. `volatile` inserts the release/acquire fences that forbid that reordering and publish the fully built object. Idiom (2) sidesteps the issue entirely — the JVM's **class initialization lock** guarantees `Holder` is initialized exactly once, lazily, on first use, with correct happens-before, and *without* any synchronization in `get()` on the hot path. Idiom (3) is Josh Bloch's recommended default: the JVM guarantees a single enum instance and it's immune to the reflection and serialization attacks that can clone the others.

#### Q75. [Theory] What is false sharing, and how do you detect and eliminate it?

**False sharing** happens when two threads update *different* variables that happen to sit on the **same cache line** (typically 64 bytes). The cache-coherence protocol (MESI) works at cache-line granularity, so even though the threads touch logically independent data, each write **invalidates the whole line in the other core's cache**, forcing it to re-fetch — the line "ping-pongs" between cores. You get the coherence cost of sharing with none of the sharing.

```
 cache line (64B): [ counterA | counterB | ... ]
   Core 0 writes counterA → invalidates line in Core 1
   Core 1 writes counterB → invalidates line in Core 0   → endless bounce
```

Symptoms: a multithreaded loop that *slows down* as you add threads, with high cache-miss / coherence-traffic counters in `perf` (`L1-dcache-load-misses`, or `perf c2c` which is purpose-built to find false sharing). The fix is **padding/alignment** so each hot variable owns its own cache line:

- In Java, **`@jdk.internal.vm.annotation.Contended`** (or manual padding fields) spaces hot fields a cache line apart; the JDK uses `@Contended` on `LongAdder`'s cells and `Thread`'s RNG seeds for exactly this.
- In C/C++, `alignas(64)` or per-thread/per-CPU variables.

`LongAdder` outperforming `AtomicLong` under contention is the canonical example: it **spreads** the counter across multiple padded cells so different threads hit different lines, trading a slightly more expensive `sum()` for the elimination of false sharing on the hot increment path.

#### Q76. [Theory] How does swap and the swap cache interact with the page cache, and what does swappiness control?

There are two distinct kinds of reclaimable pages, and they reclaim differently:

- **File-backed pages** (the page cache) — backed by a file on disk. To reclaim a *clean* one, just drop it (re-readable from the file); a *dirty* one is written back to its file first. No swap needed.
- **Anonymous pages** — heap, stack, private mappings with no file behind them. To reclaim these the kernel must write them to **swap** (a swap file/partition); their PTEs are updated to point at a swap slot, and a fault later swaps them back in.

`vm.swappiness` (0–200 on modern kernels, default ~60) is the **knob that biases reclaim between these two pools**: higher = more willing to swap out anonymous pages to keep file cache; lower = prefer evicting file cache and avoid swapping anonymous memory. It is *not* an on/off switch for swap.

```
 reclaim pressure ─┬─ high swappiness → swap out anon, keep file cache
                   └─ low  swappiness → drop file cache, avoid swapping anon
```

The **swap cache** is a small bridge that prevents races and double-I/O: a page being swapped out (or in) is briefly tracked there so that if it's faulted back before the write completes, or two faults race on the same slot, the kernel reuses the in-flight page instead of issuing duplicate disk I/O. Practical guidance: databases often set low swappiness (swapping the heap murders latency), but **swappiness=0 doesn't disable the OOM-avoiding swap**, and disabling swap entirely removes a pressure-relief valve and can make OOM kills *more* abrupt.

#### Q77. [Theory] What guarantees does `fsync` give, and why is "fsync the file" sometimes not enough for durability?

`fsync(fd)` forces the file's **dirty data pages and its metadata** from the page cache through to stable storage, and (on a correct stack) flushes the **drive's volatile write cache** so the data survives a power loss — not just a process crash. `fdatasync` is the cheaper cousin: it flushes data and only the metadata needed to read it back (size), skipping non-essential metadata (timestamps) to save an I/O.

The trap is that **fsyncing the file is not enough if the file's directory entry isn't durable**. When you create a new file (or rename one), the *name → inode* link lives in the **parent directory**, which is a separate object with its own dirty metadata. After a crash you can have a perfectly fsync'd file that the directory doesn't point to — it's effectively lost. The durable pattern is:

```
 write(tmp); fsync(tmp);            // data + the file's own metadata are stable
 rename(tmp, final);               // atomic swap into place
 fsync(parent_directory_fd);       // make the directory entry itself durable
```

This **write-to-temp, fsync, atomic-rename, fsync-the-dir** dance is how editors and databases get crash-safe atomic file replacement. Further subtleties that bite in practice: a failed `fsync` may **not be retryable** and can mark pages clean (the Linux "fsyncgate" issue that hit PostgreSQL), lying hardware/caches can ignore the flush, and on some filesystems ordering depends on mount options — which is why serious databases manage their own WAL with `O_DIRECT` rather than trusting page-cache writeback semantics.

#### Q78. [Practical] How would you bound and isolate a process's CPU, memory, and I/O using cgroups, and what actually enforces each limit?

**cgroups v2** is the kernel mechanism behind containers; each controller enforces a different resource with a different mechanism:

- **CPU** — `cpu.max` ("quota period", e.g. `50000 100000` = 50 ms per 100 ms = half a core) is enforced by the **scheduler via throttling**: when a cgroup exhausts its quota in a period, its tasks are *throttled* (taken off the run queue) until the next period. `cpu.weight` instead gives a *proportional* share under contention. The gotcha: a multithreaded app can exhaust quota early and suffer **throttling latency spikes** even though average CPU is low.
- **Memory** — `memory.max` (hard cap) and `memory.high` (soft throttle) are enforced by the **per-cgroup memory controller**: hitting `high` triggers reclaim and throttles the cgroup; hitting `max` with no reclaimable pages triggers the **cgroup OOM killer**, killing a task *inside that cgroup* while the host has free RAM (the `OOMKilled` container).
- **I/O** — `io.max` (throttle to IOPS/bandwidth) and `io.weight` (proportional) are enforced by the **block-layer I/O scheduler / throttling**, accounting bytes and operations per device.

```
 cgroup "svc-a"
   cpu.max    50000 100000   → scheduler throttles after 50ms/100ms
   memory.max 2G             → reclaim, then cgroup OOM kill at the cap
   io.max     8:0 wbps=10M   → block layer caps write bandwidth
```

The unifying point: cgroups don't invent new enforcement — they **scope the existing kernel subsystems** (scheduler, reclaim, block layer) to a group of tasks. That's why container limits manifest as ordinary OS symptoms (throttling, OOM kills, I/O stalls), just bounded per-container. **PSI** (`pressure stall information`) per cgroup is how you *observe* whether a group is actually starved for CPU/memory/IO.

#### Q79. [Theory] What is lock convoying, and how does it differ from a deadlock or livelock?

**Lock convoying** is a throughput collapse — not a hang — that happens when many threads repeatedly contend for the same lock and a holder gets **descheduled while holding it** (preempted, or it blocks). All the waiters pile up behind the lock; when the holder finally runs and releases, the woken waiters stampede, one acquires, the rest go back to sleep, and the pattern repeats. The system spends its time **parking and unparking** threads and doing context switches rather than work, and throughput tanks even though no one is permanently stuck.

```
 deadlock:  threads stuck forever, no progress, CPU idle      (cycle of waits)
 livelock:  threads active, changing state, but no net progress (e.g. retry storms)
 convoy:    progress happens, but slowly — serialized + drowning in
            context-switch / wakeup overhead behind one hot lock
```

It differs from **deadlock** (permanent, a wait cycle, zero progress) and **livelock** (busy but net-zero progress, e.g. two threads endlessly yielding to each other) in that a convoy *does* make progress — just terribly inefficiently, gated at the speed of one critical section plus scheduling overhead. Fixes attack the contention itself: **shrink the critical section**, replace the lock with **lock-free/atomic** ops or **per-thread/sharded state** (`LongAdder`, striped locks), use **read-write locks** if reads dominate, or back off so threads don't all wake and stampede. It's the runtime cousin of "your scaling is limited by one global lock" — Amdahl's serial fraction made of scheduling overhead.

### 🔴 — extended

#### Q80. [Theory] Explain the meltdown-class speculative-execution vulnerabilities and the OS mitigation (KPTI) — what changed in the kernel/user boundary?

**Meltdown** (2018) exploited **out-of-order/speculative execution**: a CPU would speculatively execute a load of kernel memory from user mode *before* the permission check retired. The architectural result (the fault) was correct — the access was rolled back — but the speculative load left a **microarchitectural side effect**: it pulled data into the cache. An attacker then used a **cache timing side channel** (Flush+Reload) to read out the secret byte-by-byte, defeating the user/kernel page-permission boundary entirely.

The OS mitigation is **KPTI (Kernel Page Table Isolation)**. Historically the kernel was mapped (as supervisor-only pages) into *every* process's page table so syscalls/interrupts didn't need a page-table switch — fast, but it meant kernel addresses were *present* in the user page table and thus speculatively reachable. KPTI **splits the page tables**: while in user mode, only a tiny stub of the kernel (entry trampolines) is mapped; entering the kernel switches `CR3` to the full kernel page table, and exiting switches back.

```
 pre-KPTI:  user PT contains all kernel mappings (supervisor)  → speculatively reachable
 KPTI:      user PT ≈ user pages + minimal entry stub
            syscall/interrupt → switch CR3 → full kernel PT → switch back on return
```

The cost is real: an **extra page-table switch (and TLB pressure) on every kernel entry/exit**, hurting syscall-heavy workloads measurably — which is exactly why reducing syscalls (io_uring, batching, vDSO) got even more valuable. **PCID/ASID** tagging softens the TLB-flush cost of those `CR3` switches. The deeper lesson for a staff engineer: an isolation boundary enforced only *architecturally* can leak through *microarchitectural* state, so defense sometimes requires changing the OS memory layout itself — and the whole class (Spectre, L1TF, MDS) blurred the once-clean line between "the result was rolled back" and "the system stayed secret."

#### Q81. [Theory] How does a microkernel differ from a monolithic kernel, and what are the real performance and reliability trade-offs?

A **monolithic kernel** (Linux, classic Unix, Windows is hybrid-leaning-mono) runs the scheduler, memory manager, filesystems, network stack, and **device drivers all in kernel mode**, sharing one address space. Calls between subsystems are ordinary function calls — fast — but a bug in any driver can corrupt the whole kernel.

A **microkernel** (seL4, QNX, Mach core) keeps only the bare minimum in kernel mode — address spaces, threads/scheduling, and **IPC** — and pushes drivers, filesystems, and network stacks out into **user-space server processes**. Subsystems talk via **message-passing IPC** instead of function calls.

```
 Monolithic:  [ user apps ]
              ───────────────  one syscall
              [ scheduler | VM | FS | net | DRIVERS ]  ← all in kernel mode

 Microkernel: [ apps ][ FS server ][ net server ][ driver server ]  ← user mode
              ──────── IPC ────────
              [ microkernel: addr spaces, threads, IPC ]            ← tiny kernel
```

Trade-offs:

- **Reliability/security** — a crashed driver in a microkernel takes down *one user-space server*, which can be restarted, not the machine; the trusted computing base shrinks to a few thousand lines (seL4 is **formally verified** — proven free of certain bug classes). This is why microkernels dominate safety-critical/real-time (QNX in cars, seL4 in defense/avionics).
- **Performance** — the historical knock is **IPC overhead**: what was a function call becomes a message round-trip with context switches and copies. Mach's slow IPC gave microkernels a bad name in the '90s. Modern microkernels (seL4) attacked this hard — fast-path IPC is now a few hundred cycles — but a chatty path that crosses several user-space servers still costs more than in-kernel calls.

In practice the industry largely settled on **monolithic-with-modules** (Linux) for general computing (raw throughput, huge driver ecosystem) and **microkernels** where isolation/verifiability outweigh peak performance. Hybrids (Windows NT, macOS XNU) keep performance-critical pieces in kernel while borrowing microkernel structure — and the *spirit* of microkernels lives on in **user-space drivers** (DPDK, SPDK, FUSE, user-space network stacks) that pull hot paths *out* of the kernel for both isolation and, paradoxically, speed by bypassing kernel overhead.

#### Q82. [Theory] How do timers, tickless kernels, and high-resolution timers actually drive scheduling and timeouts?

Classically the kernel ran on a **periodic tick** — a timer interrupt at `HZ` (e.g. 250/1000 Hz) that fired every 1–4 ms to update time, charge CPU accounting, check if the running task's slice expired, and fire due timeouts. Simple, but it means **waking every CPU constantly even when idle**, wasting power and adding jitter.

Modern Linux is **tickless (`NOHZ`)**:

- **`NOHZ_IDLE`** — an idle CPU stops the periodic tick entirely and sleeps until the *next actual event* (a programmed one-shot timer), saving power and letting cores reach deep C-states.
- **`NOHZ_FULL`** — even a CPU running a *single* busy task can run tickless, eliminating the periodic interrupt's jitter — critical for **HPC and low-latency/real-time** workloads where a 1 ms tick interrupt is an unacceptable perturbation.

The enabling mechanism is **high-resolution timers (`hrtimers`)** backed by per-CPU **one-shot** hardware timers (local APIC timer, HPET): instead of "interrupt every 1 ms," the kernel programs the timer for "interrupt at exactly the next deadline" (nanosecond granularity). Timeouts, `nanosleep`, scheduler slice expiry, and posix timers all hang off this.

```
 periodic tick:  ──tick──tick──tick──tick──  fires even when nothing's due
 tickless:       ─────────────────[next deadline]── program one-shot, sleep till then
```

Why a staff engineer cares: the tick is a hidden source of **latency jitter and wakeups**; `NOHZ_FULL` + CPU isolation (`isolcpus`) + IRQ affinity is the standard recipe for **low-jitter pinned threads** (trading/telecom/real-time). It also reframes timeouts — a `select`/`epoll_wait` timeout, a `ScheduledExecutorService` task, and a TCP retransmit timer are all just hrtimer deadlines the kernel arms and the one-shot hardware timer delivers; their precision and overhead come straight from this machinery.

#### Q83. [Coding] Implement a hierarchical timer wheel for O(1) timer insertion and expiry, and explain why kernels prefer it over a heap.

For *millions* of timers (one per TCP connection, per request deadline), a min-heap costs **O(log n)** per insert/delete — too slow at scale. A **timing wheel** gives **O(1)** insert and amortized O(1) tick by bucketing timers by expiry into a circular array, the way a clock hand sweeps slots. Hierarchical wheels (like a clock's seconds/minutes/hours hands) cover a large range without a huge single array — this is essentially the model behind the kernel's classic timer implementation and Netty's `HashedWheelTimer`.

```java
class HashedWheelTimer {
    private final java.util.List<Runnable>[] wheel;   // circular buckets
    private final int wheelSize;
    private long currentTick = 0;                      // advanced by the tick thread

    @SuppressWarnings("unchecked")
    HashedWheelTimer(int wheelSize) {
        this.wheelSize = wheelSize;
        this.wheel = new java.util.List[wheelSize];
        for (int i = 0; i < wheelSize; i++) this.wheel[i] = new java.util.ArrayList<>();
    }

    /** Schedule `task` to fire `ticksFromNow` ticks in the future. O(1). */
    void schedule(Runnable task, int ticksFromNow) {
        // Slot is where the hand will be after `ticksFromNow` ticks.
        int slot = (int) ((currentTick + ticksFromNow) % wheelSize);
        // Rounds remaining = how many full wheel revolutions before it fires.
        int rounds = ticksFromNow / wheelSize;
        wheel[slot].add(new RoundTask(task, rounds));
    }

    /** Called once per tick by the timer thread. Fires due tasks in this slot. */
    void tick() {
        int slot = (int) (currentTick % wheelSize);
        java.util.List<Runnable> bucket = wheel[slot];
        bucket.removeIf(r -> {
            RoundTask rt = (RoundTask) r;
            if (rt.rounds <= 0) { rt.task.run(); return true; } // due now → fire & remove
            rt.rounds--;                                        // not yet → wait a revolution
            return false;
        });
        currentTick++;
    }

    private static class RoundTask implements Runnable {
        final Runnable task; int rounds;
        RoundTask(Runnable task, int rounds) { this.task = task; this.rounds = rounds; }
        public void run() { task.run(); }
    }
}
```

Insertion is O(1) (compute a slot, append). Each tick only scans **one bucket**, not all timers. The `rounds` counter handles timeouts longer than one full revolution within a single-level wheel; a true **hierarchical** wheel instead cascades a timer down from a coarse wheel (e.g. "hours") into a finer wheel ("minutes") as it nears expiry, avoiding the per-tick `rounds` decrement and keeping the firing scan tight. Kernels and high-scale servers prefer this because timer **churn** (most timers are *cancelled* before firing — a TCP timeout that never trips because the ACK arrived) makes O(1) insert/cancel matter far more than the heap's ordered-extract strength, which timers rarely need.

#### Q84. [Theory] How does a modern garbage collector cooperate with the OS virtual memory system (safepoints, page protection, mapped/unmapped regions, madvise)?

A modern low-pause GC (ZGC, Shenandoah, G1) is, under the hood, a heavy *user* of OS virtual-memory primitives:

- **Safepoints via page protection** — to stop all application threads at a known-good state, the JVM uses a **polling page**. Threads cheaply check (read) a special page at safepoint polls; to trigger a global safepoint the JVM **`mprotect`s that page to no-access**, so the next poll **faults** (SIGSEGV), and the JVM's signal handler parks the thread. A page-protection trick turns "stop all threads" into a single `mprotect` instead of signaling each thread — elegant OS/runtime cooperation.
- **Colored pointers + multi-mapping** — ZGC stores metadata bits *in pointers* (load barriers test them) and **maps the same physical heap at multiple virtual addresses** (`mmap` the same backing memory at different views) so a pointer's color bits don't change which object it names. That's pure virtual-memory aliasing.
- **Concurrent relocation & remapping** — compacting collectors move objects; they use page-table/mapping tricks and load/store barriers so the mutator and collector can run concurrently, remapping references lazily on access (a software echo of CoW faulting).
- **Returning memory to the OS** — after collection shrinks the live set, GCs use **`madvise(MADV_FREE`/`MADV_DONTNEED)`** to hand idle pages back so the OS can reclaim physical frames (the "uncommit" that makes container RSS actually drop). `MADV_FREE` is lazy (reclaimed only under pressure, cheaper); `MADV_DONTNEED` frees immediately.
- **Huge pages** — large heaps opt into `-XX:+UseLargePages` so the heap's working set needs far fewer TLB entries (see THP caveats — explicit huge pages preferred for predictable latency).

```
 safepoint:   threads poll a page → JVM mprotect(page, NONE) → poll faults → park
 ZGC heap:    one physical region ── mmap'd at 3 virtual views ── colored pointers
 shrink heap: madvise(MADV_FREE/DONTNEED) → frames returned to OS → RSS drops
```

The staff-level point: GC pause time, throughput, and *whether memory is actually returned to the OS* are all functions of how cleverly the collector leverages MMU features — page faults, `mprotect`, `mmap` aliasing, and `madvise`. A "low-pause GC" is largely a story about replacing stop-the-world copying with concurrent work guarded by **load barriers and virtual-memory remapping**, and "my container RSS won't go down after GC" is almost always a `madvise`/uncommit-policy question (`-XX:+ShenandoahUncommit`, `-XX:GCTimeRatio`, etc.).

#### Q85. [Theory] What is memory overcommit, and how do `vm.overcommit_memory`, the OOM killer, and `MAP_NORESERVE` interact?

Linux **overcommits** by default: `malloc`/`mmap` can succeed for **more virtual memory than physical RAM + swap exists**, betting that programs reserve far more than they touch (sparse heaps, `fork` CoW, large guard regions). Physical frames are only allocated **on first touch** (the demand-paging fault), so reservation ≠ commitment.

`vm.overcommit_memory` picks the policy:

- **0 (heuristic, default)** — allow "reasonable" overcommit, reject obviously insane single allocations.
- **1 (always)** — never refuse; `malloc` essentially never returns NULL. Used by workloads that legitimately map huge sparse regions (some databases, sparse arrays).
- **2 (strict)** — never overcommit; total committed address space is capped at `swap + overcommit_ratio% of RAM`. Allocations fail *up front* (NULL/ENOMEM) instead of succeeding and later getting OOM-killed.

The consequence of overcommit (modes 0/1) is that the shortfall is discovered **at access time, not allocation time**: when touched pages outrun RAM+swap, the **OOM killer** picks a victim by `oom_score` and SIGKILLs it. So with overcommit, a successful `malloc` is a *promise the kernel may not keep* — your process can die touching memory it "already allocated," with no errno to catch.

```
 overcommit ON:   malloc(8G) succeeds on a 4G box → frames assigned on touch
                  touch more than RAM+swap → OOM killer SIGKILLs a victim
 overcommit STRICT: malloc(8G) → returns NULL up front (catchable), no OOM surprise
```

`MAP_NORESERVE` is the per-mapping lever: it tells the kernel **don't reserve swap backing** for this mapping (allow it to overcommit even under strict accounting) — useful for large sparse mmaps you won't fully touch; conversely its absence (or `MAP_POPULATE`) reserves/prefaults so you fail early instead of OOMing late. The staff trade-off: overcommit maximizes density and makes `fork`/sparse allocation cheap, but converts a catchable `ENOMEM` into an **uncatchable async kill** — which is why latency-critical and safety-critical systems often run **strict overcommit** (or carefully tuned `oom_score_adj` / cgroup memory limits) so failure is *deterministic and handleable* rather than a surprise SIGKILL, and why container platforms set per-cgroup `memory.max` to localize the blast radius.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

This set is hands-on: things you actually do at a terminal or in code when something is wrong — reading `top`/`vmstat`/`strace` output, decoding `EAGAIN`/`EMFILE`, writing the small concurrency utilities interviewers ask for live, and reasoning from symptom to root cause. The lens is *given this symptom, what's happening and what do I type next*, not textbook definitions.

### 🟢 — extended

#### Q86. [Practical] `top` shows a process at 100% CPU but your code "isn't doing anything." How do you find out what it's actually doing?

100% of one core means *something* is on-CPU continuously — usually a busy-wait/spin loop, a tight retry loop, or runaway GC. Walk down from coarse to precise:

1. **Is it one core or all of them?** Press `1` in `top` (or use `mpstat -P ALL 1`). One pegged core suggests a single hot thread; all cores pegged suggests genuine parallel work or many spinning threads.
2. **User vs system time.** In `top`, high `%us` = your code/library; high `%sy` = syscalls (often a tight syscall loop — `strace -f -p <pid> -c` will show *which* syscall dominates). High user time with no useful progress is the classic spin.
3. **Which thread?** `top -H -p <pid>` shows per-thread CPU. Note the hot thread's TID, then map it to a stack:
   - C/native: `perf top -p <pid>` or `perf record`/`perf report` to see the hot function.
   - JVM: take 3 thread dumps a second apart (`jstack <pid>`), convert the hot TID to hex, and find the matching `nid=0x...` frame — the method appearing in every dump is the culprit. `async-profiler` (`./profiler.sh -d 30 -f flame.html <pid>`) gives a flame graph directly.

```
 top -H -p 4123      → TID 4137 at 99% CPU
 printf '%x\n' 4137  → 102 9
 jstack 4123 | grep -A20 nid=0x1029   → the loop / hot method
```

The most common "doing nothing at 100%" answers: a `while(!done){}` spin without a sleep/yield, a non-blocking I/O loop that never blocks (`EAGAIN` ignored), or a regex/JSON parse on a pathological input. The tool tells you *which function*; the fix is to make the wait actually block (condition variable, `epoll_wait`, backoff) rather than burn the core.

#### Q87. [Practical] A `write()` to a socket returns fewer bytes than you asked for, or returns -1 with `EAGAIN`. What's going on and how do you handle it correctly?

These are normal, expected behaviors of the I/O API, not errors to retry blindly.

- **Short write** (returns `k < n`): on a blocking socket this is rare but legal; on any stream it means the kernel accepted only `k` bytes (the socket send buffer filled). You must **loop**, advancing the buffer pointer, until all bytes are sent. Treating the return as "all or nothing" silently truncates data.
- **`EAGAIN`/`EWOULDBLOCK`** (returns -1): on a **non-blocking** fd it means "the operation would block right now — the send buffer is full." It is *not* a failure; you should register the fd for writability (`EPOLLOUT`) and retry when the event loop says it's writable.

```c
ssize_t sent = 0;
while (sent < len) {
    ssize_t k = write(fd, buf + sent, len - sent);
    if (k > 0) { sent += k; continue; }
    if (k < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        wait_for_writable(fd);   // epoll EPOLLOUT, then retry
        continue;
    }
    if (k < 0 && errno == EINTR) continue;  // interrupted by a signal — retry
    return -1;                              // a real error
}
```

The three you must always handle on I/O syscalls: **partial transfer** (loop), **`EAGAIN`** (non-blocking would-block — wait for the event), and **`EINTR`** (a signal interrupted the call — just retry). In Java these are abstracted: a blocking `OutputStream.write` loops internally, but with NIO `SocketChannel.write` you again get short writes (`write` returns the count) and must loop and re-register interest in `OP_WRITE`. Forgetting the partial-write loop is one of the most common networking bugs.

#### Q88. [Practical] Your service logs "Cannot allocate memory" / `ENOMEM` but `free -h` shows gigabytes free. What could be wrong?

Free RAM isn't the only thing that limits allocation. Run through the non-obvious causes:

- **`vm.overcommit_memory=2` (strict)** — the kernel caps *committed* address space at `swap + ratio% of RAM`, so a large `malloc`/`mmap` can be refused even with free pages. Check `/proc/meminfo` `CommitLimit` vs `Committed_AS`.
- **Per-process / cgroup limits** — `ulimit -v` (address space, `RLIMIT_AS`) or a container's `memory.max` can cap you well below host RAM. In a container, `free` may show the *host's* memory while your cgroup limit is much smaller.
- **Out of a specific resource, not RAM** — `ENOMEM` is overloaded: `mmap` can fail because you hit `vm.max_map_count` (too many distinct mappings — common with many `mmap`ed files or threads), or `fork` fails because there's not enough *contiguous* committed headroom even with free RAM (a 30 GB process forking under strict overcommit).
- **Address-space exhaustion on 32-bit** — virtual address space, not physical, is the limit.

```
 cat /proc/sys/vm/overcommit_memory       # 2 = strict accounting
 grep -E 'Commit' /proc/meminfo            # CommitLimit vs Committed_AS
 cat /sys/fs/cgroup/memory.max             # container cap (cgroup v2)
 cat /proc/sys/vm/max_map_count            # mapping-count ceiling
```

The mental shift: "out of memory" can mean out of *commit budget*, out of *cgroup quota*, out of *mappings*, or out of *address space* — not necessarily out of physical pages. The diagnosis is to identify *which* accounting limit you hit, then raise that specific knob or reduce the demand.

#### Q89. [Practical] How do you list every file/socket a process has open, and use that to find a leak?

The per-process FD table is exposed at `/proc/<pid>/fd/` (each entry is a symlink to the underlying object) and via `lsof`. To find a *leak*, you watch the count grow over time and look at *what* the leaking FDs point to.

```
 ls /proc/<pid>/fd | wc -l            # current FD count — sample it over time
 lsof -p <pid> | awk '{print $5}' | sort | uniq -c | sort -rn   # group by type
 lsof -p <pid> | grep -c 'TCP'        # how many are sockets
 ls -l /proc/<pid>/fd | grep deleted  # FDs to unlinked files (classic disk-space leak)
```

A leak shows as a monotonically rising count where one *type/target* dominates — e.g. thousands of `CLOSE_WAIT` sockets to one host (you're not closing connections), or many FDs to the same log file (re-opening without closing). Two especially sneaky patterns this surfaces:

- **`deleted` files still held open** — `df` says the disk is full but `du` can't find the space, because a process holds an FD to an unlinked-but-open file; the blocks free only when the FD closes (or the process dies). `lsof | grep deleted` finds the holder.
- **`CLOSE_WAIT` pileup** — the peer closed but your app never called `close()`; each is a leaked FD *and* a leaked socket.

The fix is always source-side (close in `finally`/try-with-resources, bound the pool), with `ulimit -n` raised only to buy time. The metric to alert on is `open_fds / fd_limit` so you catch it before `EMFILE`.

#### Q90. [Coding] Write a program that spawns a child process, captures its stdout, and returns its exit code — without deadlocking on a full pipe.

The classic trap: a parent that writes the child's output to a fixed buffer can **deadlock** if the child produces more output than the pipe buffer (~64 KB) holds while the parent is blocked in `waitFor()` *before* reading — the child blocks on a full pipe, the parent blocks waiting for the child. You must drain the streams concurrently with waiting.

```java
import java.io.*;
import java.util.concurrent.*;

class RunProcess {
    static int run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)   // merge stderr into stdout to drain one pipe
                .start();

        // Drain stdout on a separate thread so a large output can't fill the pipe
        // and block the child while we wait.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> output = pool.submit(() -> {
            try (var in = p.getInputStream()) {
                return new String(in.readAllBytes());
            }
        });

        int exit = p.waitFor();              // safe: a reader is draining concurrently
        try {
            System.out.print(output.get());  // join the drainer
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } finally {
            pool.shutdown();
        }
        return exit;
    }
}
```

Why this is correct: the child's stdout is drained by a dedicated thread the entire time, so the pipe never fills and the child never blocks; meanwhile `waitFor()` reaps the exit status. The naive version — `waitFor()` first, then `readAllBytes()` — deadlocks the moment output exceeds the pipe buffer. Merging stderr (`redirectErrorStream`) avoids needing a *second* drainer thread for the stderr pipe (which would otherwise have the same deadlock). This is the Java echo of the Unix rule: when you both write to and read from a child, you need concurrency (threads or `select`/`poll`) or you risk pipe deadlock.

#### Q91. [Practical] Your single-threaded program runs fine, but the multithreaded version sometimes prints wrong results and sometimes is fine. What category of bug is this and how do you confirm it?

Non-deterministic, timing-dependent wrong answers are the signature of a **data race / race condition** — unsynchronized concurrent access to shared mutable state where at least one access is a write. "Sometimes right, sometimes wrong, never reproduces in the debugger" is almost diagnostic, because the bug depends on a specific interleaving that the scheduler only occasionally produces (and a debugger's timing perturbs).

How to confirm and locate it:

- **Run it under a race detector**, which finds the race even when the bad interleaving didn't happen this run: ThreadSanitizer (`-fsanitize=thread` in C/C++/Go), Java's `-Xcheck:jni` won't help but tools like **Java Flight Recorder**, the `jcstress` harness, or running with assertions and stress (many threads, `Thread.yield()` injected) raise the hit rate. Helgrind/DRD (Valgrind) for C.
- **Look for the shared write without a lock** — a counter `x++`, a lazily-initialized field, a shared `HashMap` mutated from multiple threads (which can even infinite-loop on resize), a check-then-act (`if (!map.containsKey) map.put`).
- **Reproduce by amplifying contention** — pin to one core, add more threads, insert `yield`s, loop the test thousands of times.

```java
// Smells: read-modify-write on shared state with no synchronization
counter++;                       // not atomic
if (cache == null) cache = ...;  // racy lazy init
sharedHashMap.put(k, v);         // HashMap is not thread-safe
```

The fix matches the access pattern: a lock/`synchronized`, an `Atomic*`/CAS for simple counters, a concurrent collection (`ConcurrentHashMap`), or — best — confining the mutable state to one thread (no sharing, no race). The key interview signal is naming it a *data race*, explaining *why* it's intermittent (interleaving-dependent), and reaching for a *detector* rather than trying to "debug it live," which Heisenbergs the timing.

#### Q92. [Practical] What does `strace` show you, and how would you use it to debug a program that "hangs"?

`strace` traces the **system calls** a process makes (and the signals it receives), printing each call, its arguments, and its return value. Because almost everything interesting a program does to the outside world is a syscall — open a file, read a socket, acquire a futex, wait on a child — `strace` shows you *exactly where a process is stuck* when it hangs.

```
 strace -f -p <pid>                 # attach to a running (hung) process, follow threads (-f)
 strace -f -T -tt ./prog            # -T = time in each call, -tt = wall-clock timestamps
 strace -f -e trace=network ./prog  # filter to a syscall group
 strace -f -c ./prog                # summary: counts/time per syscall (find the hot one)
```

For a hang, attach and read the **last line** — it's the call the process is blocked in:

- `futex(...)` with no return → blocked on a **lock/condition** (likely a deadlock or lock contention; cross-check a thread dump).
- `read(fd, ...)` / `recvfrom(...)` hanging → waiting on **input that never arrives** (a peer that won't send, a missing newline, a TCP connection silently dropped).
- `connect(...)` / `poll(...)` slow → **network/DNS** stall; check the address and timeout.
- `wait4(...)` → blocked **reaping a child** that itself is stuck (loop back to the child).
- *Nothing prints at all* → the process is genuinely idle/blocked in a single syscall (consistent with the above) — confirm with `cat /proc/<pid>/wchan` (the kernel function it's sleeping in) and `/proc/<pid>/stack`.

The power move is `strace -f -c` on a slow run to get a per-syscall *profile* — if 90% of time is in `read` you're I/O-bound waiting on a peer; if it's in `futex` you're lock-bound. (`ltrace` does the same for library calls; on macOS the equivalent is `dtruss`.) The caveat: `strace` uses `ptrace` and **massively slows the traced process**, so use it to find *where*, not to measure production throughput.

#### Q111. [Practical] What's the difference between load average and CPU utilization, and why can a box show load average 30 on 8 cores yet feel fine?

They measure different things and are routinely conflated. **CPU utilization** is the *percentage of time cores are busy* right now. **Load average** (the 1/5/15-minute figures from `uptime`/`top`) on Linux is the average number of tasks **either running or in uninterruptible (`D`) sleep** — crucially it *includes I/O-blocked tasks*, not just CPU demand.

That inclusion explains the paradox: a load average of 30 on 8 cores can mean either "30 threads fighting for 8 cores" (genuine CPU saturation — bad) *or* "8 cores idle while 22 threads sit in `D` waiting on a slow disk/NFS" (CPU is fine; storage is the problem). The number alone can't tell you which — you must cross-check:

```
 uptime                     # load average: 30.0, 28.5, 25.1  on 8 cores
 mpstat -P ALL 1            # are cores actually busy (%idle low)? or idle?
 vmstat 1                   # r column = CPU-runnable; b column = blocked on I/O
```

- If `vmstat`'s **`r`** (runnable) is ~30 and `%idle` is near 0 → real CPU oversubscription; scale out or reduce threads.
- If `r` is small but **`b`** (blocked) is large and `%iowait` is high → the load is **I/O wait**, the cores are fine, fix the storage.

So "high load average" is *not* synonymous with "CPU overloaded" on Linux — it's a queue depth that mixes CPU and uninterruptible I/O. (This is a Linux-ism; many other Unixes count only runnable tasks.) The modern, less-ambiguous signal is **PSI** (`/proc/pressure/{cpu,io,memory}`), which separately reports the fraction of time tasks were *stalled* on each resource — telling you directly whether the pressure is CPU or I/O without the load-average conflation.

#### Q112. [Coding] Given a stream of events, print the count over a sliding 1-second window efficiently. (Sliding-window counter.)

A frequent practical/coding ask (rate dashboards, sliding-rate limits). The naive approach stores every timestamp and discards old ones; the efficient approach **buckets by time** so memory and per-event cost are O(window size in buckets), independent of event volume.

```java
class SlidingWindowCounter {
    private final long windowMillis;
    private final int buckets;
    private final long[] counts;           // ring of per-slot counts
    private final long[] bucketStart;      // the time slice each slot currently represents
    private final long sliceMillis;

    SlidingWindowCounter(long windowMillis, int buckets) {
        this.windowMillis = windowMillis;
        this.buckets = buckets;
        this.sliceMillis = windowMillis / buckets;
        this.counts = new long[buckets];
        this.bucketStart = new long[buckets];
    }

    /** Record one event at time `now` (ms). */
    synchronized void record(long now) {
        int slot = (int) ((now / sliceMillis) % buckets);
        long thisSlice = now / sliceMillis * sliceMillis;
        if (bucketStart[slot] != thisSlice) {  // slot was reused from an old revolution
            counts[slot] = 0;                  // reset stale bucket lazily
            bucketStart[slot] = thisSlice;
        }
        counts[slot]++;
    }

    /** Count of events within the last `windowMillis`. */
    synchronized long count(long now) {
        long cutoff = now - windowMillis;
        long total = 0;
        for (int i = 0; i < buckets; i++)
            if (bucketStart[i] > cutoff) total += counts[i];   // only fresh buckets
        return total;
    }
}
```

The trick is the **circular array of time buckets**: each event increments exactly one slot in O(1), and a slot is *lazily reset* when time advances past its slice (detected by a mismatch between the slot's stored slice and the current one) — no background sweeping, no per-event cleanup of a list. `count` sums only buckets newer than the cutoff. The accuracy/cost trade-off is the **bucket granularity**: more buckets = a smoother, more precise window edge but more memory and a longer sum loop. This bucketed design is exactly what production metrics systems (and sliding-window rate limiters in API gateways) use, because storing one timestamp per event is O(events) memory and collapses under load. It's the same hierarchical-bucketing idea as the timer wheel, applied to counting.

### 🟡 — extended

#### Q93. [Coding] Implement a fixed-size thread pool with a bounded task queue (your own mini ExecutorService) and explain the rejection/backpressure choices.

A thread pool decouples *task submission* from *thread management*: N worker threads pull from a shared blocking queue. Bounding the queue is what gives you **backpressure** — without it, a fast producer can OOM the process by queueing unbounded work.

```java
import java.util.concurrent.*;

class FixedThreadPool {
    private final BlockingQueue<Runnable> queue;
    private final Thread[] workers;
    private volatile boolean running = true;

    FixedThreadPool(int nThreads, int queueCapacity) {
        this.queue = new ArrayBlockingQueue<>(queueCapacity);   // BOUNDED → backpressure
        this.workers = new Thread[nThreads];
        for (int i = 0; i < nThreads; i++) {
            workers[i] = new Thread(this::workerLoop, "worker-" + i);
            workers[i].start();
        }
    }

    /** Returns false if the queue is full (caller-handled backpressure). */
    boolean submit(Runnable task) {
        if (!running) throw new IllegalStateException("pool shut down");
        return queue.offer(task);          // non-blocking; false = rejected (full)
    }

    /** Blocking variant: applies backpressure by making the producer wait. */
    void submitBlocking(Runnable task) throws InterruptedException {
        if (!running) throw new IllegalStateException("pool shut down");
        queue.put(task);                   // blocks the caller when full
    }

    private void workerLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Runnable task = queue.poll(100, TimeUnit.MILLISECONDS);
                if (task != null) task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException ex) {
                // never let a task's exception kill the worker thread
                System.err.println("task failed: " + ex);
            }
        }
    }

    void shutdown() {
        running = false;
        for (Thread w : workers) w.interrupt();
    }
}
```

The design decisions interviewers probe:

- **Bounded vs unbounded queue** — unbounded (`LinkedBlockingQueue` with no cap) hides backpressure and risks OOM; bounded forces a *rejection policy*. `ThreadPoolExecutor` exposes exactly this via `RejectedExecutionHandler` (abort / caller-runs / discard / discard-oldest). **CallerRuns** is a clever self-throttle: the submitting thread executes the task itself, slowing the producer.
- **`offer` vs `put`** — `offer` rejects immediately (fail fast / shed load); `put` blocks the producer (propagate backpressure upstream). Pick based on whether dropping or slowing is correct for your system.
- **Worker resilience** — a task throwing must not kill the worker thread (catch inside the loop), or the pool silently shrinks.
- **Graceful shutdown** — stop accepting, drain the queue, interrupt blocked workers.

The takeaway: a thread pool's hard part isn't running tasks, it's *what happens when you can't keep up* — and the answer is a bounded queue plus an explicit, chosen rejection/backpressure policy.

#### Q94. [Practical] `vmstat 1` shows `r` (run-queue) at 20 on an 8-core box with `cs` (context switches) sky-high. What does that mean and what do you do?

The `r` column is the number of **runnable** processes/threads (on a CPU or waiting for one). With `r = 20` on 8 cores, you have ~2.5x more runnable work than cores — sustained **CPU oversubscription**. The high `cs` (context switches) confirms the cores are thrashing between too many ready threads, and time is being spent *switching* rather than *running*.

```
 vmstat 1
  r  b   swpd  free  ...  in    cs  us sy id wa
 20  0      0  3.0G  ... 9000 85000  70 25  0  5   ← r≫cores, huge cs, high sy
```

Interpretation and actions:

- **Confirm it's saturation, not blocking** — high `r` (not `b`) plus high `us`+`sy` means runnable threads contending for CPU. If `sy` is disproportionately high, the context-switch/scheduling overhead itself is eating CPU (the classic "too many threads" tax).
- **Find the thread explosion** — a common cause is an app sizing its pool to *requests* instead of *cores* (e.g. 500 threads doing CPU-bound work on 8 cores). `ps -eLf | wc -l`, or per-process thread counts, reveal it.
- **Fix by reducing concurrency to ~cores for CPU-bound work** — size compute pools to `Runtime.getRuntime().availableProcessors()`. For I/O-bound work the right number is higher (threads are mostly blocked), which is exactly the case for **virtual threads / async**, where you *don't* tie up a kernel thread per blocked task.
- **Distinguish from involuntary preemption** — `pidstat -w` splitting voluntary vs non-voluntary switches tells you whether threads are blocking (voluntary) or being preempted because there are too many (non-voluntary). High non-voluntary confirms oversubscription.

The crisp rule: **CPU-bound parallelism should match core count**; piling on more threads past that point *reduces* throughput because context-switch overhead and cache-thrash grow while useful work doesn't. `r` persistently above core count is the number that proves it.

#### Q95. [Coding] Implement a rate limiter (token bucket) that's correct under concurrent access.

A token bucket allows bursts up to a capacity while enforcing a long-run average rate: tokens refill at a steady rate, each request consumes one, and a request is allowed only if a token is available. The concurrency challenge is computing the refill and the take **atomically**.

```java
class TokenBucketRateLimiter {
    private final long capacity;          // max burst
    private final double refillPerNano;   // tokens added per nanosecond
    private double tokens;
    private long lastRefillNanos;

    TokenBucketRateLimiter(long capacity, double tokensPerSecond) {
        this.capacity = capacity;
        this.refillPerNano = tokensPerSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Returns true if a token was granted. Thread-safe. */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double added = (now - lastRefillNanos) * refillPerNano;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }
}
```

Key correctness points: refill is **lazy and time-based** (compute tokens accrued since the last call from elapsed nanos) rather than a background thread ticking — that's both simpler and exact. The whole `refill + take` must be atomic, here via `synchronized`; a lock-free version would `compareAndSet` a packed `(tokens, timestamp)` state in a loop to avoid the lock. Capping at `capacity` enforces the maximum burst. For distributed rate limiting this same logic moves to Redis (an atomic Lua script over `tokens`/`timestamp` keys) so all app instances share one bucket. The contrast worth mentioning: **leaky bucket** smooths output to a constant rate (no bursts), while **token bucket** permits bursts up to capacity — token bucket is the usual choice for APIs because it tolerates legitimate spikes.

#### Q96. [Practical] You see threads stuck in `BLOCKED` and `WAITING` in a JVM thread dump. How do you read it to find a deadlock or a lock-contention hotspot?

A thread dump (`jstack <pid>`, `jcmd <pid> Thread.print`, or a `kill -3` to stdout) is the primary tool. First, learn the states:

- **`RUNNABLE`** — executing or in a syscall (note: a thread blocked in a *socket read* shows RUNNABLE, not BLOCKED — a common confusion).
- **`BLOCKED`** — waiting to *acquire a monitor* (`synchronized`) another thread holds. This is lock contention.
- **`WAITING`/`TIMED_WAITING`** — in `Object.wait`, `Condition.await`, `park`, `sleep`, `join` — waiting to be signaled, not contending for a lock.

To find a **deadlock**, the JVM does the work for you — `jstack` prints a `Found one Java-level deadlock:` section with the cycle. Otherwise, look at the `- waiting to lock <0x...>` and `- locked <0x...>` annotations and build the wait-for graph: thread A waits to lock an address that thread B has locked, and B waits for an address A holds → cycle.

```
 "worker-1" BLOCKED
    waiting to lock <0x00000000d6a1>   (held by worker-2)
    locked          <0x00000000d6b2>
 "worker-2" BLOCKED
    waiting to lock <0x00000000d6b2>   (held by worker-1)   ← cycle = deadlock
```

For **contention without deadlock**, take **3–5 dumps a few seconds apart** and look for the *same lock address* with many threads `BLOCKED` on it across all dumps — that monitor is your hotspot, and the single thread `locked`-ing it is on the critical path (often doing I/O or a long computation inside a `synchronized` block). The fix is to shrink that critical section, move I/O out of the lock, or shard the lock. The multi-dump technique distinguishes a transient blip (different threads each dump) from a real bottleneck (the same lock, persistently).

#### Q97. [Coding] Two threads must alternate printing (e.g. FooBar: thread A prints "foo", thread B prints "bar", repeating). Implement it.

A canonical synchronization exercise testing condition-based handoff. The cleanest correct solution uses a lock + condition (or two semaphores) so each thread waits its turn.

```java
import java.util.concurrent.Semaphore;

class FooBar {
    private final int n;
    private final Semaphore fooTurn = new Semaphore(1);  // foo goes first
    private final Semaphore barTurn = new Semaphore(0);

    FooBar(int n) { this.n = n; }

    public void foo(Runnable printFoo) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            fooTurn.acquire();      // wait for my turn
            printFoo.run();         // prints "foo"
            barTurn.release();      // hand the turn to bar
        }
    }

    public void bar(Runnable printBar) throws InterruptedException {
        for (int i = 0; i < n; i++) {
            barTurn.acquire();      // wait until foo released me
            printBar.run();         // prints "bar"
            fooTurn.release();      // hand the turn back to foo
        }
    }
}
```

The two semaphores form a **ping-pong**: `fooTurn` starts with 1 permit (so `foo` runs first), `barTurn` with 0. Each side acquires its own permit, does its work, and releases the *other* side's — guaranteeing strict alternation regardless of thread scheduling. This generalizes: the same pattern with one lock and a turn variable + `Condition.signal` works too, and a "print 1,2,3,... in order across N threads" variant just rotates through N semaphores. The interview point is recognizing it as a **handoff/turn-passing** problem (signal the *next* actor) rather than a mutual-exclusion problem — a plain lock alone wouldn't enforce ordering.

#### Q98. [Practical] A file delete didn't free disk space (`df` still full), or `df` and `du` disagree. Explain the OS reasons and how to resolve it.

Filesystem space accounting has several traps that produce a "deleted but still full" or "`df` ≠ `du`" symptom:

- **An open file descriptor to a deleted file.** On Unix, `unlink()` removes the *name*, but the inode and its data blocks survive until the **last open FD closes** (and the link count hits zero with no openers). A process logging to a file you `rm`'d keeps the blocks pinned. `df` counts them; `du` (which walks the directory tree) can't see the now-nameless file, so they disagree. Find the holder with `lsof | grep deleted`, then restart/HUP that process (or truncate via `/proc/<pid>/fd/<n>`) to release the space — no reboot needed.
- **Reserved blocks.** ext4 reserves ~5% for root by default (`tune2fs -m`); non-root writes fail with the disk "full" while `df` shows a few percent free.
- **Inode exhaustion.** You can have free *blocks* but zero free *inodes* (millions of tiny files); writes fail with `ENOSPC` though `df -h` shows space. Check `df -i`.
- **Mount shadowing.** Files written under a mount point *before* something was mounted there are hidden by the mount; `du` of the live tree won't see them, but they consume space on the underlying filesystem.

```
 df -h /data            # space view (counts open-but-deleted files)
 du -sh /data           # tree view (can't see nameless/shadowed files)
 lsof +L1 | grep /data  # files with link count 0 still held open → the leak
 df -i /data            # inode exhaustion check
```

The most common real-world answer is the **open-deleted-file** case — log rotation that deletes the old log while the app still has it open. The lesson: on Unix, *space is freed when the last reference (name or FD) goes away*, not when you delete the name.

#### Q99. [Coding] Implement a connection pool that hands out and reclaims a fixed number of resources, blocking when exhausted.

A connection pool bounds expensive resources (DB connections, sockets) and reuses them. The core is a semaphore (capacity) plus a thread-safe collection of idle resources; `borrow` blocks when all are in use, and callers must `release` (ideally via try-with-resources) to return them.

```java
import java.util.concurrent.*;

class ConnectionPool<T> {
    private final BlockingQueue<T> idle;
    private final int size;

    ConnectionPool(java.util.List<T> connections) {
        this.size = connections.size();
        this.idle = new ArrayBlockingQueue<>(size);
        idle.addAll(connections);          // pre-create; pool never grows past `size`
    }

    /** Blocks until a connection is free, or times out. */
    T borrow(long timeout, TimeUnit unit) throws InterruptedException {
        T conn = idle.poll(timeout, unit);
        if (conn == null) throw new TimeoutException("pool exhausted");
        return conn;
    }

    void release(T conn) {
        if (conn == null) return;
        // Returning must not block; pool is sized so this always has room.
        if (!idle.offer(conn))
            throw new IllegalStateException("releasing a connection the pool didn't own");
    }

    /** Convenience: borrow, run, always release — even on exception. */
    <R> R withConnection(java.util.function.Function<T, R> work,
                         long timeout, TimeUnit unit) throws InterruptedException {
        T conn = borrow(timeout, unit);
        try {
            return work.apply(conn);
        } finally {
            release(conn);                 // guaranteed return — no leak on exception
        }
    }

    private static class TimeoutException extends RuntimeException {
        TimeoutException(String m) { super(m); }
    }
}
```

The blocking `ArrayBlockingQueue` *is* the bounded semaphore: `poll(timeout)` blocks when empty (all connections in use) and `offer` returns them. The two failure modes a real pool must handle, beyond this skeleton: a **borrow timeout** (don't block forever — fail fast so a slow DB doesn't hang every thread), and the **leak guarantee** that every borrow is matched by a release even on exception (the `withConnection`/try-finally wrapper, exactly how HikariCP-style pools enforce it). Production pools add liveness/validation (test-on-borrow, evict dead connections), max-lifetime, and a leak detector that logs a borrow held too long — but the heart is "bounded resources + block when exhausted + guaranteed return."

### 🟠 — extended

#### Q100. [Practical] After deploying, latency p99 spiked but p50 is unchanged and CPU is normal. Walk through how you'd diagnose this tail-latency problem.

A clean p50 with a blown p99 means *most* requests are fine but a tail hits a periodic, correlated stall — the hallmark of a **shared, bursty pause**, not a steady slowdown. Normal CPU rules out raw saturation. Systematically suspect the things that pause *everything* at once:

- **GC pauses** — the #1 cause in a JVM. Enable GC logging (`-Xlog:gc*`) and overlay pause times on the latency timeline; a stop-the-world pause of 200 ms shows up as a p99 spike on every in-flight request. Fix: switch to a low-pause collector (ZGC/Shenandoah), right-size the heap, reduce allocation rate.
- **Lock convoying / a hot lock** — periodic contention serializes a burst of requests (see the convoy question). Thread dumps under load + `async-profiler` *off-CPU/lock* profiling reveal it.
- **Memory reclaim / direct reclaim & THP compaction** — a memory squeeze triggers synchronous reclaim or huge-page compaction that stalls the allocating thread. `sar -B` (faults), `/proc/vmstat` `compact_stall`, and `vmstat` `si/so` confirm; disabling THP or lowering memory pressure fixes it.
- **Noisy neighbor / cgroup CPU throttling** — in containers, `cpu.max` quota exhaustion *throttles* your threads for the rest of the period even at "normal" average CPU. Check `nr_throttled`/`throttled_time` in `cpu.stat` — this is a top cause of containerized tail latency.
- **Downstream tail amplification** — a request that fans out to many backends is as slow as its *slowest* dependency; one slow shard turns into a p99 spike (the "tail at scale" effect). Trace it (distributed tracing) to find the slow hop.

```
 grep -i 'pause' gc.log | sort -k3 -rn | head      # worst GC pauses
 cat /sys/fs/cgroup/cpu.stat                       # nr_throttled, throttled_usec
 grep compact_stall /proc/vmstat                   # THP compaction stalls
```

The method is the message: a tail-only regression is about **correlated pauses** (GC, reclaim, throttling, lock convoys, slow dependencies), so you look for *something that periodically stops a batch of requests together*, using time-aligned logs/traces — not a CPU profile, which would look normal because the median path is healthy.

#### Q101. [Coding] Implement a futex-style wait/wake using `AtomicInteger` and `LockSupport.park/unpark`, and explain the lost-wakeup hazard.

Futexes embody "atomic in userspace, block only on contention." We can mirror that in Java: an atomic state for the fast path, and `LockSupport.park`/`unpark` (which map to the OS park/futex) for blocking. The subtle bug to avoid is the **lost wakeup** — `unpark` happening *between* your condition check and your `park`, so you sleep forever.

```java
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** A one-permit gate: waiters block until state becomes 1. */
class AtomicGate {
    private final AtomicInteger state = new AtomicInteger(0);   // 0 = closed, 1 = open
    private volatile Thread waiter;

    public void awaitOpen() {
        if (state.get() == 1) return;          // fast path: already open, no parking
        waiter = Thread.currentThread();       // publish who is waiting BEFORE re-checking
        // Re-check after publishing: closes the lost-wakeup window.
        while (state.get() == 0) {
            LockSupport.park(this);            // park() returns spuriously too → loop
            if (Thread.interrupted()) { /* handle interrupt */ }
        }
        waiter = null;
    }

    public void open() {
        state.set(1);                          // 1) make the condition true FIRST
        Thread w = waiter;                     // 2) then read & wake the waiter
        if (w != null) LockSupport.unpark(w);
    }
}
```

The lost-wakeup hazard and why this order fixes it: if `open()` could `unpark` *before* the waiter parked, the wake is lost and the waiter sleeps forever. The defense is the same as kernel `wait_event`: **publish intent (register the waiter) and re-check the condition under that publication *before* parking**, and on the waker side, **make the condition true before signaling**. `LockSupport.park`/`unpark` also help structurally — `unpark` "pre-issues" a permit, so an `unpark` that arrives *before* `park` makes the subsequent `park` return immediately rather than blocking (unlike `Object.wait`/`notify`, where a `notify` before `wait` is genuinely lost). The mandatory **`while` loop** also absorbs spurious `park` returns. This is exactly the machinery `AbstractQueuedSynchronizer` (and thus every `java.util.concurrent` lock) is built on.

#### Q102. [Practical] `perf top` shows most time in `__lock_text_start`/spin or `native_queued_spin_lock_slowpath`. What does kernel-side spinning indicate and how do you act on it?

Seeing the CPU burning in a **kernel spinlock slow path** (`queued_spin_lock_slowpath`, `_raw_spin_lock`, or high `%sy` with spin symbols) means threads are contending on a **kernel-internal lock** — the kernel is busy-waiting because many cores hit the same kernel data structure simultaneously. It's a scalability wall *inside* the kernel, not in your code.

Common triggers and responses:

- **`mmap`/`munmap`/`brk` storms → mmap_lock / TLB-shootdown contention.** Many threads mutating one address space's mappings serialize on the per-`mm` lock and generate TLB shootdowns. Fix: pool/reuse memory arenas instead of frequent map/unmap; reduce thread count sharing one address space; use huge pages to cut mapping count.
- **`futex` contention in the kernel** — a wildly contended user lock spills into kernel futex queues. Fix the *user-space* hot lock (shard it, shrink the critical section, go lock-free).
- **Filesystem/inode or dentry locks** — many threads hammering the same directory/file (e.g. all creating temp files in one dir). Spread across directories; use `O_TMPFILE`.
- **Networking locks** — a single listening socket's accept lock, or one RX queue, under high connection rate. Fix: `SO_REUSEPORT` (multiple accept queues), RSS/RPS to spread across cores, multiple listeners.

```
 perf record -g -p <pid>; perf report   # callstack → which kernel path leads to the spin
 perf lock record / perf lock report     # kernel lock contention specifically
 cat /proc/<pid>/stack                    # what kernel function a thread sits in
```

The reasoning pattern: kernel spin time = **too many cores contending on one kernel object**, so the fix is *partitioning* — give each core/thread its own resource (per-CPU arenas, `SO_REUSEPORT`, separate directories, separate sockets) so they stop colliding on a shared kernel lock. It mirrors the user-space rule (shard the hot lock), just one layer down.

#### Q103. [Coding] Implement graceful shutdown for a worker pool: stop accepting work, drain in-flight tasks, and force-cancel after a timeout.

Graceful shutdown is a recurring production requirement (SIGTERM handling in containers/k8s). The pattern is three phases: **stop intake → drain with a deadline → force-cancel stragglers**, mapping directly onto `ExecutorService`'s `shutdown`/`awaitTermination`/`shutdownNow`.

```java
import java.util.concurrent.*;

class GracefulPool {
    private final ExecutorService exec = Executors.newFixedThreadPool(8);
    private volatile boolean accepting = true;

    void submit(Runnable task) {
        if (!accepting) throw new RejectedExecutionException("shutting down");
        exec.submit(wrap(task));
    }

    /** Phase 1–3 graceful shutdown with a hard deadline. */
    void shutdownGracefully(long graceSeconds) {
        accepting = false;            // 1) stop accepting NEW work
        exec.shutdown();              // 2) no new tasks; let queued + running finish
        try {
            // 2b) wait up to the grace period for in-flight tasks to drain
            if (!exec.awaitTermination(graceSeconds, TimeUnit.SECONDS)) {
                // 3) deadline exceeded → interrupt running tasks, drop the queue
                exec.shutdownNow();   // sends interrupts; returns undrained tasks
                if (!exec.awaitTermination(5, TimeUnit.SECONDS))
                    System.err.println("pool did not terminate after force");
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            try { task.run(); }
            // tasks must respond to interruption to make shutdownNow effective
            catch (RuntimeException ex) { /* log */ }
        };
    }
}
```

The non-obvious correctness requirements: (1) `shutdown()` is *non-blocking* and only stops new submissions — you need `awaitTermination` to actually wait for drain; (2) `shutdownNow()` only **interrupts** workers, so it's effective only if your tasks honor interruption (check `Thread.interrupted()` in loops, propagate `InterruptedException`) — a task ignoring interrupts will run to completion regardless; (3) the **grace deadline** prevents shutdown from hanging on a stuck task. Wiring this to a `Runtime.getRuntime().addShutdownHook(...)` makes the JVM drain in-flight requests on SIGTERM before exiting — exactly what a Kubernetes `terminationGracePeriodSeconds` expects. The whole thing is the lifecycle realization of "stop the front door, finish what's inside, then evict whoever's left after closing time."

#### Q104. [Practical] You suspect memory corruption / a use-after-free / a buffer overflow in a native program. What OS-level tools and protections help you catch it?

Memory-safety bugs (use-after-free, buffer overflow, double-free, uninitialized read) often manifest *far* from their cause — a crash in unrelated code, or intermittent corruption. The toolbox turns "random crash" into "exact line and access":

- **AddressSanitizer (ASan, `-fsanitize=address`)** — compile-time instrumentation that **poisons** freed and out-of-bounds memory with redzones; any access into poisoned memory aborts with the allocating *and* freeing stack traces. Catches heap/stack overflow, use-after-free, double-free. The first thing to reach for; ~2x slowdown.
- **Valgrind/Memcheck** — no recompile needed; shadows every byte's addressability/definedness. Slower (10–50x) but finds uninitialized reads and leaks ASan can miss. **MemorySanitizer (MSan)** specializes in uninitialized reads.
- **OS-level hardening that *catches* (not diagnoses)** — these turn corruption into a clean crash: **guard pages** (touching them faults), **ASLR** (randomized layout makes exploitation harder), **stack canaries** (`-fstack-protector` detects stack-buffer overflow on function return), **NX/W^X** (data pages non-executable), and **glibc hardening** (`MALLOC_CHECK_`, `glibc.malloc.check`, tcache double-free detection).
- **Core dumps + the fault address** — a `SIGSEGV` core (`coredumpctl`, `gdb`) gives the faulting address; comparing it to mapped regions (`/proc/<pid>/maps`) tells you whether you ran off the heap, smashed the stack, or dereferenced a freed/NULL pointer.

```
 cc -fsanitize=address -g prog.c && ./a.out     # ASan: poisoned-memory access → exact trace
 valgrind --leak-check=full ./a.out             # uninitialized reads + leaks
 gdb ./a.out core   # then: info registers, x/i $pc, info proc mappings
```

The strategy: reproduce under a **sanitizer** to get the precise allocation/free/access stacks (cause), and lean on **OS protections** (guard pages, canaries, NX, ASLR) to ensure that in production the corruption *crashes deterministically* rather than silently spreading. The interview-grade point is distinguishing *diagnosis tools* (ASan/Valgrind — find the bug) from *mitigation mechanisms* (canaries/NX/ASLR/guard pages — contain the damage), and knowing both are OS/compiler-provided, not free.

#### Q105. [Coding] Implement a barrier (all N threads must arrive before any proceeds) and explain the generation/reuse problem.

A cyclic barrier blocks each arriving thread until all N have arrived, then releases them all — useful for phased parallel algorithms (every thread finishes phase k before any starts k+1). The reuse subtlety is the **generation problem**: a fast thread released from one phase mustn't race ahead and re-enter the barrier, getting confused with stragglers from the previous round.

```java
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

class CyclicBarrier {
    private final int parties;
    private int count;                 // remaining threads to arrive this generation
    private int generation = 0;        // bumped each time the barrier trips → reuse-safe
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition trip = lock.newCondition();

    CyclicBarrier(int parties) { this.parties = parties; this.count = parties; }

    public void await() throws InterruptedException {
        lock.lock();
        try {
            int myGen = generation;            // snapshot the round I belong to
            if (--count == 0) {                // I'm the last to arrive → trip it
                generation++;                  // open a NEW generation
                count = parties;               // reset for reuse
                trip.signalAll();              // release everyone waiting on THIS round
                return;
            }
            // Wait until the generation advances (the barrier trips).
            while (myGen == generation)        // guard on generation, not on count!
                trip.await();
        } finally { lock.unlock(); }
    }
}
```

Why guard on **generation** rather than `count`: if waiters looped on `count != 0`, a thread released and immediately re-entering for the next phase would decrement `count` again and a still-waking thread from the previous round could observe the new `count` and misbehave (a spurious early release or a missed release). Bumping `generation` when the barrier trips gives each round a distinct identity — a waiter blocks until *its* generation is over, immune to the next round's bookkeeping. This is precisely how `java.util.concurrent.CyclicBarrier` works (plus a `barrierAction` run by the last arriver, and *breaking* the barrier on interrupt/timeout so all parties fail together rather than deadlocking). The lesson generalizes to any reusable sync primitive: **version the state** so reuse can't be confused with the prior cycle — the same idea as the ABA stamp.

#### Q106. [Practical] A request occasionally takes exactly ~1 second (or some round number) longer than usual. What OS/network behaviors produce suspiciously "round" latencies?

Suspiciously *round, fixed* extra latencies (≈1s, ≈3s, ≈5s, ≈200ms, ≈40ms) are a fingerprint: they come from **timers and retransmission constants**, not from variable work. The roundness is the clue — real computation produces noisy, continuous distributions; protocol timers produce spikes at exact values.

- **TCP retransmission timeout (RTO)** — a lost SYN or data segment waits for a fixed RTO before retransmit. Linux's initial RTO is ~1s; a dropped packet adds *exactly* ~1s (then exponential backoff: 1s, 3s, 7s…). A request stuck at "+1s" almost always means a **dropped packet / lossy path / SYN drop** (e.g. an overfull accept queue dropping SYNs).
- **Nagle's algorithm + delayed ACK interaction** — Nagle holds a small write waiting for an ACK; delayed-ACK holds the ACK up to ~40–200ms. The deadlock between them adds a fixed ~40ms to small request/response exchanges. Fix: `TCP_NODELAY`.
- **DNS timeout** — a failed/slow DNS lookup retries at fixed intervals (commonly ~5s per the resolver), so a flaky resolver adds round multiples of 5s.
- **Connection/socket timeouts** — app or library default timeouts (3s, 30s) firing on a stalled hop produce exactly-that-value latencies.
- **Scheduler/timer granularity** — historically `select`/`sleep` rounding to a tick, now mostly gone with hrtimers, but quota-period throttling (`cpu.max` 100ms periods) can add ~100ms multiples.

```
 ss -ti                      # per-socket RTO, retransmits, rtt — see retrans counts
 nstat -az | grep -i retrans # TCP retransmit counters
 dig +trace / time nslookup  # DNS latency
```

The diagnostic instinct: **a fixed, round latency = a timer fired**, so ask "what protocol/OS timer equals that number?" A "+1s" tail screams TCP RTO / packet loss; a "+40ms" on tiny payloads screams Nagle/delayed-ACK; "+5s" screams DNS. You confirm with `ss -ti`/`nstat` retransmit counters or a `tcpdump`, then fix the *cause* (loss, `TCP_NODELAY`, resolver), not the symptom.

### 🔴 — extended

#### Q107. [Practical] Design the OS-level tuning and architecture for a service that must hold 1,000,000 concurrent TCP connections on one box. What are the real limits and knobs?

A million connections (C1M) is bounded less by CPU than by **per-connection memory and a stack of kernel limits**; the answer is mostly OS tuning plus an event-driven architecture.

**The binding constraints, in order:**

- **Memory per connection** — each socket carries kernel send/recv buffers (default tens of KB each). At 1M connections, default buffers alone would need *tens of GB*. You must **shrink and autotune buffers** (`tcp_rmem`/`tcp_wmem` minimums low, rely on autotuning) and accept that memory, not CPU, is the wall: 1M × ~10KB ≈ 10GB just for socket buffers.
- **File descriptors** — every connection is an FD. Raise the **system-wide** `fs.file-max` and `fs.nr_open`, and the process **`RLIMIT_NOFILE`** (`ulimit -n`) to >1M. This is the first hard stop.
- **Ephemeral ports / connection identity** — on the *server* side a single listening port serves all 1M (the 4-tuple is unique per client), so server ports aren't the limit; but a *client/proxy* making 1M outbound connections needs `ip_local_port_range` widened and multiple destination IPs (a single (srcIP→dstIP:port) only yields ~64K 4-tuples).
- **conntrack table** — if a firewall/NAT tracks connections, `nf_conntrack_max` and its hashsize must be raised or you drop connections silently.
- **Accept backlog** — `somaxconn` and the app's `listen()` backlog must be large, plus `tcp_max_syn_backlog`, or SYNs drop under burst (and you get the +1s RTO tail).

**Architecture:**

- **Event-driven, not thread-per-connection** — `epoll` (edge-triggered) with a small number of event-loop threads (≈ cores), because 1M kernel threads is impossible (stacks alone = TB). This is the C10K→C10M lineage.
- **Multi-queue scaling** — `SO_REUSEPORT` so multiple listener sockets/threads spread accept load across cores; RSS/RPS to spread NIC interrupts; pin event loops to cores and align IRQ affinity (avoid one core handling all softirqs).
- **`io_uring`** to cut per-op syscall overhead at this scale.

```
 fs.nr_open / fs.file-max         → 2,000,000+        (FD ceilings)
 ulimit -n                        → 2000000           (per-process)
 net.ipv4.tcp_rmem/tcp_wmem       → small min, autotune (cap per-conn memory)
 net.core.somaxconn               → 65535             (accept backlog)
 net.netfilter.nf_conntrack_max   → raised or conntrack bypassed
 net.ipv4.ip_local_port_range     → widened (client side only)
```

The staff-level framing: **C1M is a memory and limits problem, not a throughput problem** — the architecture (epoll/io_uring, few threads, SO_REUSEPORT, IRQ affinity) is well-trodden; the work is finding and raising every per-resource ceiling (FDs, buffers, backlog, conntrack, ports) so none of them silently caps you first, and budgeting RAM for ~10KB×1M of unavoidable socket buffers.

#### Q108. [Coding] Implement a lock-free single-producer/single-consumer (SPSC) ring buffer and explain the memory-ordering requirements.

An SPSC ring (bounded queue with one producer thread and one consumer thread) can be made **lock-free and even wait-free** because each index is written by only one side. The correctness rests entirely on **memory ordering** (acquire/release), not locks — get the fences wrong and the consumer reads stale or torn data on a weakly-ordered CPU.

```java
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

class SpscRingBuffer<E> {
    private final Object[] buffer;
    private final int mask;                 // capacity must be a power of two
    private final AtomicLong head = new AtomicLong(0);  // consumer writes (read index)
    private final AtomicLong tail = new AtomicLong(0);  // producer writes (write index)

    SpscRingBuffer(int capacityPow2) {
        this.buffer = new Object[capacityPow2];
        this.mask = capacityPow2 - 1;
    }

    /** Producer only. Returns false if full. */
    boolean offer(E e) {
        long t = tail.get();                       // plain read: only we write tail
        if (t - head.get() == buffer.length)       // ACQUIRE read of head: is it full?
            return false;
        buffer[(int)(t & mask)] = e;               // 1) publish the element
        tail.lazySet(t + 1);                       // 2) RELEASE: store-store fence so the
        return true;                               //    element write is visible before the
    }                                              //    index bump the consumer reads

    /** Consumer only. Returns null if empty. */
    @SuppressWarnings("unchecked")
    E poll() {
        long h = head.get();                       // plain read: only we write head
        if (h == tail.get()) return null;          // ACQUIRE read of tail: is it empty?
        E e = (E) buffer[(int)(h & mask)];         // read the element published by producer
        buffer[(int)(h & mask)] = null;            // help GC
        head.lazySet(h + 1);                       // RELEASE so producer sees the freed slot
        return e;
    }
}
```

The memory-ordering crux: the producer must ensure the **element store happens-before the index increment** the consumer observes — otherwise the consumer sees the new `tail`, reads the slot, and gets garbage (the element write not yet visible). `lazySet`/release-store gives exactly that store-store ordering cheaply (no expensive `StoreLoad` fence, unlike `volatile` write). Symmetrically the consumer's `head` release lets the producer see slots freed. Each index has a **single writer**, so no CAS/retry is needed — that's why SPSC can be wait-free, far cheaper than a multi-producer queue (which needs CAS on the shared index and hits the ABA/contention issues). Real implementations (JCTools `SpscArrayQueue`, the LMAX **Disruptor**) add **cache-line padding** around `head` and `tail` so the producer's and consumer's hot indices live on *different* cache lines — without it, false sharing makes the two cores bounce the line and destroys throughput (tying directly back to the false-sharing question).

#### Q109. [Practical] A process is stuck in `D` (uninterruptible sleep) and won't die even with `kill -9`. Explain why, and what you can actually do.

A task in state **`D`** is blocked **inside the kernel in an uninterruptible wait** — almost always on I/O it issued (disk, NFS, a device). It's uninterruptible by design: the kernel is mid-operation holding resources/buffers, and delivering a signal there could corrupt state, so signals (including `SIGKILL`) are *queued, not delivered* until the I/O completes and the task returns toward user space. That's why `kill -9` appears to "not work" — the signal is pending; the task simply can't process it yet.

What's actually happening and what you can do:

- **Find what it's waiting on** — `cat /proc/<pid>/stack` and `/proc/<pid>/wchan` show the kernel function it's sleeping in (e.g. `nfs_wait_on_request`, `io_schedule`, `bit_wait`). `/proc/<pid>/status` confirms `State: D`. This tells you the *device/subsystem* at fault.
- **The usual culprit is stuck storage** — a hung NFS mount (server unreachable), a failing disk timing out, or a dead SAN/iSCSI path. The task will unwedge the *instant* the I/O completes, errors out, or times out — then the pending SIGKILL is delivered and it dies.
- **You cannot force-kill it from userspace** — there is no signal that preempts an uninterruptible kernel wait. Realistic options: **fix the I/O source** (restore the NFS server / network path, so the wait completes), wait for the **device timeout** (some block I/O has bounded timeouts; many NFS mounts are `hard` and wait forever — `soft`/`intr` mounts can time out), or as a last resort **reboot**. Newer kernels added `TASK_KILLABLE` (`D` that *does* accept fatal signals) for some paths (notably NFS), which is why some modern `D`-state tasks *can* be killed — but legacy/`hard`-mount paths still cannot.

```
 cat /proc/<pid>/stack    # kernel call stack → which wait
 cat /proc/<pid>/wchan     # the sleeping function name
 # NFS hang? → restore the server or remount soft,intr; many will then unblock
```

The expert framing: `D` is **not a bug in your process** — it's the kernel protecting an in-flight I/O operation, and the cure is at the **I/O layer** (storage/network), not the process layer. A *pile* of `D`-state tasks is the canonical "stuck storage" incident signature; the action is to triage the device/mount, knowing the tasks will free themselves once I/O resolves and the queued SIGKILLs then take effect.

#### Q110. [Theory] How does CPU cache coherence (MESI) interact with your concurrent code's performance, and how do atomics and locks map to coherence traffic?

Every core has private L1/L2 caches; **MESI** (Modified/Exclusive/Shared/Invalid) keeps them coherent. A cache line is **Shared** when multiple cores hold it read-only; to *write*, a core must gain **Exclusive/Modified** ownership, which requires **invalidating every other core's copy** (a "Request For Ownership" broadcast). That invalidation traffic — not the arithmetic — is what makes contended concurrent code slow.

How your primitives translate to coherence cost:

- **A contended atomic / `lock`-prefixed instruction (CAS, `lock xadd`)** must take the line **Exclusive** to do its read-modify-write atomically. Under contention, the line **ping-pongs**: each core RFOs the line away from the last writer, so throughput is gated by the **inter-core cache-line transfer latency** (tens to ~100+ ns), not instruction speed. N threads incrementing one `AtomicLong` serialize on one line — this is why `LongAdder` (per-thread padded cells) wins: it spreads writes across *many* lines so cores stop fighting over one.
- **Taking/releasing a lock** is itself a write to the lock's cache line (the futex word / monitor), so even an *uncontended* lock costs a coherence transaction if the line was last touched by another core; a *contended* lock adds the queue/park machinery on top.
- **False sharing** is the pathological case (covered earlier): logically-independent variables on one line suffer this RFO ping-pong as if they were the same variable.
- **Read-mostly data** stays in **Shared** on every core with zero coherence traffic — until one write invalidates all of them. That's the entire economic argument for **RCU** and immutable/`final` data: keep the hot path in Shared state.

```
 N cores incrementing ONE counter:
   each ++ → RFO → invalidate others → line bounces core→core  (serialized, ~100ns each)
 N cores incrementing N padded counters (LongAdder):
   each ++ stays Exclusive on its owner core → no bouncing → scales linearly
```

The mental model a staff engineer carries: **shared mutable state has a hardware cost measured in cache-line transfers**, and scalable concurrency is the art of *minimizing writes to shared lines* — shard counters (`LongAdder`/striping), keep read-mostly data immutable so it lives in Shared (RCU, copy-on-write), pad to avoid false sharing, and prefer per-CPU/per-thread state. You can even *see* it: `perf c2c` (cache-to-cache) and `perf stat` HITM (modified-line hits) quantify exactly which lines are bouncing between cores. Lock-free isn't automatically fast — a contended CAS bounces a line just like a lock does; the win comes only when you also remove the *sharing*.

#### Q113. [Practical] A multithreaded program works perfectly on your x86 laptop but corrupts data on an ARM (Graviton/Apple Silicon) server. What's the most likely cause and how do you fix it?

This is the **weak-memory-ordering trap**, and it's increasingly common as production moves to ARM (AWS Graviton, Apple Silicon, Ampere). x86 implements **TSO (Total Store Order)** — a relatively *strong* model where the only reordering allowed is store→load. ARM and POWER are **weakly ordered**: loads and stores to *different* addresses can be reordered freely by the hardware unless you insert explicit barriers. Code with a **missing synchronization barrier** (a forgotten `volatile`, an unsynchronized publish, a hand-rolled lock-free structure without the right fences) often "works" on x86 purely because TSO *accidentally* provides the ordering the code needed — and then breaks on ARM, which actually exercises the reorderings the code failed to forbid.

```
 publish:  data = 42;        // write A
           ready = true;     // write B — on ARM, B can become visible BEFORE A
 consume:  if (ready)        // sees B...
               use(data);    // ...but reads STALE data (A not yet visible) → corruption
```

The classic failures: a flag-based publish without `volatile`/release-store (the consumer sees the flag set but the payload not yet written), double-checked locking without `volatile` (half-constructed object — fine on x86, broken on ARM), and DIY ring buffers/SPSC queues missing acquire/release. The fix is to **make the ordering explicit** rather than relying on the platform:

- In Java, use `volatile`/`final`/`synchronized` or the `VarHandle` acquire/release modes — the JMM is the *same* on every ISA, and the JIT emits the correct ARM `dmb`/`ldar`/`stlr` barriers. Code correct under the JMM is portable by construction.
- In C/C++, use `std::atomic` with the right `memory_order` (don't rely on plain `volatile`, which carries no cross-thread ordering).
- **Test on the target architecture**, and use stress harnesses (`jcstress` for Java) that specifically hunt these races; ThreadSanitizer flags missing synchronization regardless of host.

The staff-level point is the line *"it works on x86" is not evidence of correctness — it's evidence that TSO hid your bug.* Concurrency correctness must be argued from the **language memory model**, not from the behavior of one strongly-ordered CPU; the migration to weakly-ordered ARM is exactly where latent missing-barrier bugs surface in production.

#### Q114. [Coding] Implement a deadlock detector: given the current "thread T holds lock L / thread T waits for lock L" graph, report whether a deadlock exists and which threads are involved.

Databases and some runtimes detect deadlocks rather than prevent them. A deadlock is a **cycle in the wait-for graph** (an edge T1→T2 means "T1 is waiting for a lock currently held by T2"). Detection is cycle-detection in a directed graph via DFS with a recursion stack.

```java
import java.util.*;

class DeadlockDetector {
    // wait-for graph: thread -> set of threads it is (transitively) blocked behind
    private final Map<String, Set<String>> waitsFor = new HashMap<>();

    /** Call when `holder` currently owns a lock that `waiter` is blocked trying to acquire. */
    void addWaitEdge(String waiter, String holder) {
        waitsFor.computeIfAbsent(waiter, k -> new HashSet<>()).add(holder);
    }

    /** Returns the cycle of threads forming a deadlock, or empty if none. */
    List<String> findDeadlock() {
        Set<String> visited = new HashSet<>();     // fully explored, known acyclic
        Set<String> onStack = new HashSet<>();     // current DFS path (gray set)
        Deque<String> path = new ArrayDeque<>();
        for (String t : waitsFor.keySet()) {
            List<String> cycle = dfs(t, visited, onStack, path);
            if (cycle != null) return cycle;
        }
        return List.of();                          // no cycle → no deadlock
    }

    private List<String> dfs(String node, Set<String> visited,
                             Set<String> onStack, Deque<String> path) {
        if (onStack.contains(node)) {              // back-edge → cycle found
            List<String> cycle = new ArrayList<>();
            boolean started = false;
            for (String n : path) {                // extract the threads in the cycle
                if (n.equals(node)) started = true;
                if (started) cycle.add(n);
            }
            cycle.add(node);
            return cycle;
        }
        if (visited.contains(node)) return null;   // already proven acyclic
        visited.add(node);
        onStack.add(node);
        path.addLast(node);
        for (String next : waitsFor.getOrDefault(node, Set.of())) {
            List<String> cycle = dfs(next, visited, onStack, path);
            if (cycle != null) return cycle;
        }
        path.removeLast();
        onStack.remove(node);                      // leave the recursion stack
        return null;
    }
}
```

The algorithm is **O(V + E)** DFS: a node currently on the recursion stack (`onStack`) that we reach again means a **back edge**, i.e. a cycle = deadlock; the cycle is the slice of the path from the repeated node onward. This is exactly how a database's deadlock detector works on its lock table — except the graph is built from waits over *resources* (a thread waits for a lock held by another), and once a cycle is found the recovery step is to **pick a victim** (usually the cheapest-to-rollback transaction by work done / locks held) and abort it to break the cycle, then let the others proceed. The JVM does a one-shot version of this when you run `jstack` (the "Found one Java-level deadlock" report). The extension for real lock tables: a lock can have *multiple holders* (shared/read locks), so the wait-for edges fan out to all holders, and you detect a cycle through *any* of them.

#### Q115. [Practical] You need to understand exactly why a production binary is slow, but you can't attach a debugger or add print statements. What modern Linux observability would you reach for, and how do you keep overhead near zero?

The modern answer is **eBPF** plus **`perf`** — kernel-supported, low-overhead, production-safe tracing that needs no recompile, no restart, and no debugger attach. The strategy is to *sample* and *trace selectively* so overhead stays in the low single-digit percent.

- **`perf` for CPU profiling** — `perf record -F 99 -p <pid> -g` samples stacks at 99 Hz (cheap; the odd frequency avoids lock-stepping with periodic timers), then `perf report`/a **flame graph** shows where on-CPU time goes. 99 Hz sampling is near-free vs. instrumenting every call.
- **Off-CPU analysis** — on-CPU profiling misses time spent *blocked* (waiting on locks, I/O, futexes). eBPF `offcputime`/`offwaketime` (bcc/bpftrace) record *why and how long* threads were off-CPU, turning "it's slow but CPU is idle" into "it spent 3s blocked in `futex_wait` called from this stack."
- **eBPF for targeted, dynamic tracing** — `bpftrace` one-liners attach to kernel tracepoints, kprobes (any kernel function), and **uprobes** (any userspace function — even in a stripped-but-symboled binary) *without modifying the binary*. Example: histogram of `read()` latency, count of which functions allocate, syscall latency by process. The in-kernel BPF program aggregates in maps so only summaries cross to userspace — that's what keeps overhead minimal vs. `strace`'s per-syscall `ptrace` stop.
- **The BCC/bpftrace toolkit** — ready-made tools: `biolatency` (block-I/O latency histogram), `runqlat` (scheduler run-queue latency — how long threads wait for a CPU), `execsnoop`, `tcpconnect`, `funclatency`. Brendan Gregg's toolkit covers most "why is it slow" questions out of the box.

```
 perf record -F 99 -g -p <pid> -- sleep 30; perf script | flamegraph.pl > cpu.svg
 bpftrace -e 'tracepoint:syscalls:sys_enter_read { @[comm] = count(); }'   # who reads most
 /usr/share/bcc/tools/offcputime -p <pid> 30   # WHERE it blocks (off-CPU stacks)
 /usr/share/bcc/tools/runqlat 10               # scheduler wait latency → CPU contention?
```

The discipline that keeps overhead near zero: **sample, don't trace everything** (99 Hz `perf` vs instrumenting every call); **aggregate in the kernel** (eBPF maps) so you don't ship every event to userspace (the `strace`/`ptrace` mistake); and **scope to the one process/function** in question rather than system-wide. The decision tree: high CPU and you don't know where → `perf` flame graph; idle CPU but slow → eBPF **off-CPU** / `runqlat` to find the blocking; a specific syscall/function suspected → a targeted `bpftrace` latency histogram. This is the 2026-standard way to debug production performance — safe enough to run on live traffic, precise enough to pinpoint a single slow function or a scheduler-queue stall, all without touching the binary.

## ✅ Key Takeaways

- A **process** is an isolated address space; a **thread** is a schedulable flow that shares the process's heap and file descriptors. Thread switches are cheaper than process switches because the page table/TLB don't change.
- **Scheduling** trades throughput vs latency vs fairness; know FCFS/SJF/RR/priority and that modern kernels (CFS→EEVDF, MLFQ) approximate fairness with good interactive latency without prior burst knowledge.
- **Virtual memory** via paging gives isolation and over-commit; the **TLB** caches translations, **demand paging** loads lazily on page faults, and **replacement** (LRU≈Clock) decides victims. **Thrashing** is when working sets exceed RAM.
- **Synchronization** primitives differ by ownership/level: mutex (owned), semaphore (counting/signaling), monitor (`synchronized` + condition vars). Always re-check conditions in a `while` loop.
- **Deadlock** needs all four Coffman conditions; the practical defense is **lock ordering** (prevention). Banker's algorithm (avoidance) and detection-and-recovery are alternatives.
- **System calls** cross the user/kernel boundary expensively; **interrupts + DMA** drive efficient I/O; the **page cache** + write-back balances throughput and durability (`fsync`).
- Modern performance comes from staying in **user space** (futexes, vDSO, JVM thin locks), reducing copies/syscalls (**io_uring, zero-copy**), and respecting hardware (**NUMA, huge pages, memory barriers**).

## ⚠️ Common Pitfalls

- Confusing `BLOCKED` (monitor contention) with `WAITING` in thread dumps, or thinking calling `run()` starts a thread (it runs on the current thread — use `start()`).
- Using `if` instead of `while` to re-check a wait condition — spurious wakeups and lost races will bite you.
- Assuming `synchronized`/`volatile` is unnecessary "because it works on x86" — x86's strong TSO hides missing barriers that break on weakly-ordered ARM.
- Believing a `write()` that returned means data is on disk — it's in the dirty page cache until flushed; durability requires `fsync`/`force()`.
- Treating shared memory like a pipe — shared memory needs *your own* synchronization (semaphore/lock); only kernel-mediated IPC serializes for you.
- "Fixing" `Too many open files` by only raising `ulimit` instead of finding the FD leak; same anti-pattern as bumping heap to mask a memory leak.
- Enabling THP everywhere for "free" speed — it can cause tail-latency spikes; databases often recommend disabling it while still using explicit huge pages.
- Sizing a JVM heap larger than available RAM, inviting swap and thrashing that looks like a CPU problem in dashboards.

## 📚 Further Reading

- *Operating Systems: Three Easy Pieces* (Arpaci-Dusseau) — free online; the best modern OS textbook for these exact topics.
- *Operating System Concepts* (Silberschatz, Galvin, Gagne) — the classic "dinosaur book," strong on scheduling, deadlock, and the Banker's algorithm.
- *Modern Operating Systems* (Tanenbaum) — broad and rigorous; good on memory management and file systems.
- *The Linux Programming Interface* (Kerrisk) — definitive reference for system calls, IPC, signals, and process/thread APIs on Linux.
- *What Every Programmer Should Know About Memory* (Ulrich Drepper) — deep dive on caches, TLB, and NUMA.
- Linux kernel docs on **scheduler (EEVDF)**, **io_uring**, **transparent huge pages**, and **NUMA** — current to recent kernels.
- *Java Concurrency in Practice* (Goetz) and the JEP for **virtual threads (JEP 444)** — for how the JVM maps onto OS threading and the memory model.
