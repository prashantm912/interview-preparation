# Java Core (8 / 11 / 17 / 21)

A deep, interview-grade reference for Java language and platform fundamentals spanning Java 8 through Java 21 (LTS) — covering OOP, generics, collections, the memory model, the functional revolution of Java 8, and the modern language (records, sealed types, pattern matching, virtual threads). Every answer explains the *why* and the trade-offs, not just the definition.

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

### Q1. [Theory] What is the difference between `==` and `.equals()`, and how do `equals()` and `hashCode()` relate?

`==` compares *references* for objects (do two variables point to the same object on the heap?) and *values* for primitives. `.equals()` compares *logical equality* as defined by the class. By default `Object.equals()` is reference equality, so you must override it for value semantics (e.g. `String`, `Integer`, custom DTOs).

The critical contract: **if `a.equals(b)` is true, then `a.hashCode() == b.hashCode()` must also be true.** The reverse is not required (hash collisions are legal). If you override `equals()` but not `hashCode()`, hash-based collections (`HashMap`, `HashSet`) break — two "equal" objects land in different buckets and you get duplicate keys or failed lookups. `equals()` must also be reflexive, symmetric, transitive, consistent, and `x.equals(null)` must be false.

```java
record Point(int x, int y) {} // records auto-generate equals/hashCode/toString correctly
// Pre-records, you'd hand-write both using Objects.equals / Objects.hash:
@Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Point p)) return false; // pattern matching, Java 16+
    return x == p.x && y == p.y;
}
@Override public int hashCode() { return Objects.hash(x, y); }
```

### Q2. [Theory] Explain the four pillars of OOP with a Java example for each.

- **Encapsulation** — hide internal state behind methods; expose a controlled API. `private` fields + getters/setters, or immutable records.
- **Inheritance** — `class SavingsAccount extends Account` reuses and specializes behavior. Prefer composition over deep inheritance hierarchies (fragile base class problem).
- **Polymorphism** — one interface, many implementations. `List<String> l = new ArrayList<>();` then later swap to `LinkedList` without changing callers. Runtime dispatch picks the right `add()`.
- **Abstraction** — model what matters and hide the rest via `interface` or `abstract class`.

The interview follow-up is usually *"composition vs inheritance"*: inheritance creates tight coupling to the parent's implementation; composition (`has-a`) is more flexible and is the modern default ("favor composition over inheritance," Effective Java Item 18).

### Q3. [Theory] What is the difference between a checked and an unchecked exception?

Checked exceptions (`extends Exception` but not `RuntimeException`) must be declared with `throws` or caught — the compiler enforces it. They model *recoverable* conditions a caller can reasonably handle (e.g. `IOException`). Unchecked exceptions (`extends RuntimeException`) signal *programming errors* (`NullPointerException`, `IllegalArgumentException`) and need no declaration. `Error` (e.g. `OutOfMemoryError`, `StackOverflowError`) represents unrecoverable JVM problems you should not catch.

Modern practice leans toward unchecked exceptions for most application code (Spring, JPA, and the wider ecosystem use them) because checked exceptions don't compose well with lambdas/streams and pollute signatures. Use checked exceptions only when the caller genuinely has a recovery path.

### Q4. [Practical] When would you choose `ArrayList` vs `LinkedList` vs `HashMap` vs `TreeMap`?

```
ArrayList   → backed by a resizable array. O(1) random access, O(1) amortized append,
              O(n) insert/remove in the middle. THE default list. Cache-friendly.
LinkedList  → doubly-linked nodes. O(1) insert/remove at ends, O(n) random access.
              Rarely the right choice; use ArrayDeque for queue/stack semantics.
HashMap     → O(1) average get/put, no ordering, allows one null key.
TreeMap     → red-black tree, O(log n) ops, keeps keys sorted (NavigableMap range queries).
LinkedHashMap → HashMap + predictable iteration order (insertion or access order, great for LRU).
```

In production I default to `ArrayList` and `HashMap`. I reach for `ArrayDeque` instead of `LinkedList`/`Stack` for stacks and queues (faster, no synchronization overhead of legacy `Stack`). I use `TreeMap` only when I need sorted iteration or range queries (`headMap`/`tailMap`/`subMap`).

### Q5. [Coding] Reverse a string and check if it is a palindrome.

**Problem:** Given a string, return it reversed; then determine whether the original is a palindrome (ignoring case).

```java
static String reverse(String s) {
    return new StringBuilder(s).reverse().toString();
}

// O(n) time, O(1) extra space — two-pointer, avoids building a second string
static boolean isPalindrome(String s) {
    if (s == null) return false;            // edge case
    int i = 0, j = s.length() - 1;
    while (i < j) {
        if (Character.toLowerCase(s.charAt(i++)) != Character.toLowerCase(s.charAt(j--)))
            return false;
    }
    return true;                            // empty / single char → true
}
```

**Complexity:** reverse is O(n) time / O(n) space; the two-pointer palindrome check is **O(n) time, O(1) space** and is preferred because it short-circuits and allocates nothing. **Edge cases:** `null`, empty string, single character, mixed case, and (in a stricter version) stripping non-alphanumeric characters.

### Q6. [Theory] What does `final` mean on a variable, method, and class?

- **`final` variable** — assigned exactly once. For references, the *reference* is fixed but the object may still be mutable (`final List<String> l` can still be `.add()`-ed to). It also signals intent and enables certain JIT optimizations.
- **`final` method** — cannot be overridden by subclasses; useful for invariants and template-method "fixed steps."
- **`final` class** — cannot be extended (e.g. `String`, `Integer`). This is a security and immutability tool.

Local variables used in lambdas/anonymous classes must be `final` or *effectively final* (assigned once, never reassigned).

---

## 🟡 Intermediate (3–7 yrs)

### Q7. [Theory] What is type erasure in generics, and what are its practical consequences?

Java generics are a **compile-time** feature. After compilation, type parameters are *erased* — `List<String>` and `List<Integer>` are both just `List` at runtime, with the compiler inserting casts. This was a deliberate choice for backward compatibility with pre-generics bytecode.

Consequences you will hit in interviews:

- `new T()` and `new T[]` are illegal — the type isn't known at runtime.
- You cannot do `if (obj instanceof List<String>)` — only `instanceof List<?>`.
- You cannot have two overloads that differ only by generic type (`m(List<String>)` and `m(List<Integer>)` collide).
- `List<String>.class` doesn't exist; only `List.class`.
- Unchecked-cast warnings appear when bridging generic and raw code.

The workaround for runtime type info is passing a `Class<T>` token (the "type token" pattern, used heavily in Jackson, Spring `getBean(Class)`, etc.) or `TypeReference` for parameterized types.

### Q8. [Theory] Explain PECS and bounded wildcards (`? extends` vs `? super`).

**PECS = Producer Extends, Consumer Super.** Use `? extends T` when a structure *produces* T values you read (covariance); use `? super T` when it *consumes* T values you write (contravariance).

```java
// Producer: we READ Numbers out, so extends
double sum(List<? extends Number> nums) {        // accepts List<Integer>, List<Double>
    double t = 0; for (Number n : nums) t += n.doubleValue(); return t;
}
// Consumer: we WRITE Integers in, so super
void fill(List<? super Integer> dst) {            // accepts List<Integer>, List<Number>, List<Object>
    for (int i = 0; i < 10; i++) dst.add(i);
}
```

You can read from a `? extends` list (as `T`) but not add to it (except `null`); you can add to a `? super` list but only read elements as `Object`. `Collections.copy(dest, src)` is the canonical real-world signature: `copy(List<? super T> dest, List<? extends T> src)`.

### Q9. [Theory] What makes a class immutable, and why does it matter for concurrency?

An immutable class: (1) declares the class `final` (or uses sealed/private constructors) so it can't be subclassed to add mutability; (2) makes all fields `private final`; (3) provides no setters; (4) performs *defensive copies* of mutable inputs in the constructor and mutable outputs in getters; (5) ensures `this` doesn't escape during construction.

Immutability matters because immutable objects are **inherently thread-safe** — no synchronization needed, they can be freely shared and cached, they make great map keys, and they eliminate a whole class of aliasing bugs. `String`, `Integer`, `LocalDate`, and `record` types are immutable. The cost is allocation churn (every "change" creates a new object), mitigated by techniques like the builder pattern or persistent data structures.

### Q10. [Coding] Implement an LRU cache.

**Problem:** Build a fixed-capacity cache that evicts the least-recently-used entry on overflow, with O(1) `get`/`put`.

**Approach 1 — `LinkedHashMap` (production-simple):**

```java
class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;
    LRUCache(int capacity) {
        super(16, 0.75f, true);          // access-order = true is the key
        this.capacity = capacity;
    }
    @Override protected boolean removeEldestEntry(Map.Entry<K, V> e) {
        return size() > capacity;        // auto-evict on overflow
    }
}
```

**Approach 2 — HashMap + doubly-linked list (the "show me the internals" answer):** maintain a `HashMap<K, Node>` for O(1) lookup and a doubly-linked list ordered by recency; `get`/`put` unlink the node and move it to the head, evict from the tail.

```
HEAD <-> [most recent] <-> ... <-> [least recent] <-> TAIL
              ^ move here on access            ^ evict from here
```

**Complexity:** both are **O(1)** average for get/put. **Edge cases:** capacity 0/negative (reject), updating an existing key (must refresh recency, not just overwrite), thread-safety (neither is thread-safe — wrap with `Collections.synchronizedMap` or use Caffeine in production). Note `LinkedHashMap`'s access-order mode is *not* thread-safe even for reads, since `get` mutates order.

### Q11. [Coding] Group a list of employees by department and compute average salary using Streams.

```java
record Employee(String name, String dept, double salary) {}

Map<String, Double> avgByDept = employees.stream()
    .collect(Collectors.groupingBy(
        Employee::dept,
        Collectors.averagingDouble(Employee::salary)));

// Highest-paid per department:
Map<String, Optional<Employee>> top = employees.stream()
    .collect(Collectors.groupingBy(
        Employee::dept,
        Collectors.maxBy(Comparator.comparingDouble(Employee::salary))));

// Count + names joined, multi-level downstream:
Map<String, String> namesByDept = employees.stream()
    .collect(Collectors.groupingBy(
        Employee::dept,
        Collectors.mapping(Employee::name, Collectors.joining(", "))));
```

**Complexity:** O(n) single pass. **Edge cases:** empty stream (`groupingBy` returns empty map; `averagingDouble` over an empty group can't happen since the group only exists if it has a member), `null` department keys throw on `groupingBy` in some collectors — filter or use a sentinel. **Why streams:** declarative, parallelizable (`.parallelStream()`), but for hot tight loops a plain `for` loop is often faster and allocation-free.

### Q12. [Practical] How do `Optional` and the Java 8 `java.time` API improve on what came before?

`Optional<T>` makes the *absence of a value explicit in the type*, pushing null-handling to compile-time-visible code (`map`, `filter`, `orElseGet`, `orElseThrow`) instead of runtime NPEs. The rules of good usage: use it as a **return type** for "maybe absent," **never** as a field or method parameter, and never call `.get()` without first checking. `orElseGet(supplier)` is lazy; `orElse(value)` always evaluates its argument — a common performance bug.

`java.time` (JSR-310) replaced the broken, mutable, non-thread-safe `Date`/`Calendar`/`SimpleDateFormat`. Use `LocalDate`/`LocalTime`/`LocalDateTime` for human time without zones, `Instant` for machine timestamps (UTC epoch), `ZonedDateTime` when zones matter, `Duration`/`Period` for amounts, and `DateTimeFormatter` (immutable, thread-safe — unlike `SimpleDateFormat`, a classic source of production corruption under concurrency). Always store timestamps in UTC and convert at the edges.

### Q13. [Theory] Default and static methods on interfaces — why were they added, and how are conflicts resolved?

Default methods (Java 8) let interfaces ship method *implementations*, enabling API evolution without breaking implementers — that's how `Collection.stream()`, `removeIf()`, and `forEach()` were added without breaking the millions of existing `List` implementations. Static interface methods provide factory/utility helpers co-located with the type (`Comparator.comparing(...)`).

Conflict resolution (the "diamond problem"):

1. **Class wins** — a concrete method in a class beats any interface default.
2. **More specific interface wins** — a sub-interface's default overrides a super-interface's.
3. **Otherwise it's a compile error** — you must override and can disambiguate with `InterfaceName.super.method()`.

### Q14. [Practical] Walk through the modern Java features that reduce boilerplate (Java 11→17→21).

```java
// Java 10/11: local-variable type inference
var users = new HashMap<String, List<Order>>();   // infers the verbose type

// Java 11: built-in HttpClient (replaces clunky HttpURLConnection / Apache HttpClient for basics)
var client = HttpClient.newHttpClient();
var resp = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                       HttpResponse.BodyHandlers.ofString());

// Java 15+: text blocks for multi-line strings (JSON, SQL, HTML) without escaping
String sql = """
    SELECT id, name FROM users
    WHERE active = true
    """;

// Java 16+: pattern matching for instanceof — bind and test in one step
if (obj instanceof String s && !s.isBlank()) { use(s); }

// Java 17: records + sealed + switch expressions
sealed interface Shape permits Circle, Square {}
record Circle(double r) implements Shape {}
record Square(double side) implements Shape {}
```

In production these matter for *readability and correctness*: `var` cuts noise (use it where the RHS makes the type obvious), text blocks eliminate escaping bugs in SQL/JSON, and records remove ~40 lines of equals/hashCode/toString/constructor boilerplate per DTO while guaranteeing correctness.

### Q15. [Theory] Records and sealed classes (Java 17) — what problems do they solve, and how do they combine?

A **record** is a transparent, immutable data carrier: `record Point(int x, int y) {}` auto-generates a canonical constructor, private final fields, accessors (`x()`, `y()`), and correct `equals`/`hashCode`/`toString`. You can add a *compact constructor* for validation. Records are implicitly `final`, cannot extend classes, and can't add instance fields beyond the components — they are deliberately constrained to be "just data."

A **sealed** class/interface restricts which types may extend/implement it via `permits`. This gives the compiler a *closed* type hierarchy, enabling **exhaustive** `switch` with no `default` branch. Sealed + records = **algebraic data types** in Java, the foundation for safe pattern matching:

```java
sealed interface Expr permits Num, Add {}
record Num(double v) implements Expr {}
record Add(Expr l, Expr r) implements Expr {}

double eval(Expr e) {
    return switch (e) {                         // exhaustive — no default needed
        case Num n -> n.v();
        case Add(Expr l, Expr r) -> eval(l) + eval(r);  // record deconstruction (Java 21)
    };
}
```

If you later add `record Mul(...) implements Expr`, every non-exhaustive switch fails to compile — the compiler points you at every place that needs updating. That's a huge maintainability win over the visitor pattern.

---

## 🟠 Advanced (8–12 yrs)

### Q16. [Theory] Explain the Java Memory Model (JMM): happens-before, visibility, and the role of `volatile`.

The JMM defines *when* a write by one thread becomes *visible* to a read by another, and which reorderings the compiler/CPU may perform. Without synchronization, there is **no guarantee** another thread ever sees your write — caches, registers, and instruction reordering can hide it.

The core abstraction is **happens-before**: if action A happens-before B, A's effects are visible to B. Edges include: program order within a thread; unlock happens-before subsequent lock of the same monitor; a `volatile` write happens-before every subsequent read of that variable; `Thread.start()` happens-before the thread's actions; a thread's actions happen-before another thread's successful `join()`.

`volatile` guarantees visibility and prevents reordering across the access (it establishes a happens-before edge) but does **not** provide atomicity for compound operations — `volatile int i; i++` is still a race (read-modify-write). For atomic compound updates use `synchronized`, `java.util.concurrent.locks`, or `AtomicInteger`/`VarHandle` CAS. The classic JMM bug:

```java
// BROKEN: flag may never be seen by the worker thread; loop can hoist the read.
boolean running = true;            // needs to be volatile
void stop() { running = false; }
void run()  { while (running) { /* work */ } }   // may spin forever
```

### Q17. [Theory] Virtual threads (Java 21, Project Loom) — what are they, and when do they help (or not)?

Virtual threads are lightweight threads scheduled by the JVM (not the OS) onto a small pool of **carrier** (platform) threads. Millions can exist because each is just a heap-allocated continuation, not a ~1 MB OS thread stack. When a virtual thread blocks on I/O, the JVM *unmounts* it from its carrier and parks the continuation, freeing the carrier to run another virtual thread.

```
Platform threads:  1 OS thread per task → thousands max, expensive to block.
Virtual threads:   millions of tasks → carrier pool (≈ #cores) runs them;
                   blocking I/O unmounts the VT, carrier stays busy.

  VT1 (blocked on socket) --unmount--> carrier free --> mount VT2 (runnable)
```

They make the simple **thread-per-request, blocking** programming model scale to high concurrency — you write straightforward synchronous code and get reactive-like throughput, without the cognitive cost of `CompletableFuture`/reactive pipelines. Use them for **I/O-bound** workloads (microservices calling other services/DBs). They do **not** speed up CPU-bound work (you still have only N cores). Two pitfalls: **`synchronized` blocks can "pin"** a virtual thread to its carrier (use `ReentrantLock` instead in hot paths — largely mitigated in newer builds), and thread-pool sizing logic and `ThreadLocal`-heavy code may need rethinking. Don't pool virtual threads — create one per task with `Executors.newVirtualThreadPerTaskExecutor()`.

### Q18. [Coding] Use structured concurrency / virtual threads to fan out parallel calls and aggregate.

**Problem:** Fetch a user and their orders from two services concurrently; fail fast if either fails; never leak threads.

```java
// Java 21 preview: StructuredTaskScope ties subtask lifetimes to a scope.
Response handle(String userId) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        Supplier<User>        user   = scope.fork(() -> userService.find(userId));
        Supplier<List<Order>> orders = scope.fork(() -> orderService.findByUser(userId));

        scope.join();            // wait for both
        scope.throwIfFailed();   // propagate the first failure, cancel siblings

        return new Response(user.get(), orders.get());
    }
}
// Pre-21 equivalent with virtual threads + CompletableFuture:
var exec = Executors.newVirtualThreadPerTaskExecutor();
var uF = CompletableFuture.supplyAsync(() -> userService.find(userId), exec);
var oF = CompletableFuture.supplyAsync(() -> orderService.findByUser(userId), exec);
return uF.thenCombine(oF, Response::new).join();
```

**Why structured concurrency:** subtasks form a tree bound to the enclosing scope — if the block exits (success, failure, or cancellation), all forked tasks are guaranteed cancelled and joined, eliminating thread leaks and orphaned work. **Complexity:** wall-clock is `max(t_user, t_orders)` instead of the sum. **Edge cases:** timeouts (`ShutdownOnFailure` with a deadline via `joinUntil`), partial success policies (`ShutdownOnSuccess`), and ensuring idempotent cancellation.

### Q19. [Theory] Pattern matching for `switch` and record patterns (Java 21) — how do they change idiomatic Java?

Java 21 finalized pattern matching for `switch`, adding **type patterns**, **record deconstruction patterns**, **guards** (`when`), and **null handling** inside switch. Combined with sealed types it replaces sprawling `if/else instanceof` chains and the visitor pattern:

```java
String describe(Object o) {
    return switch (o) {
        case null               -> "nothing";
        case Integer i when i<0 -> "negative int";
        case Integer i          -> "int " + i;
        case Point(int x, int y)-> "point " + x + "," + y;   // record pattern, deconstruction
        case String s           -> "string len " + s.length();
        default                 -> "other";
    };
}
```

This is a *structural* shift: behavior dispatched on shape and content, exhaustively checked by the compiler for sealed hierarchies, with destructuring that reads like the data. It moves Java toward expression-oriented, data-oriented programming (Brian Goetz's term) — pushing logic out of polymorphic methods scattered across subclasses into focused, reviewable switch expressions.

### Q20. [Theory] What are sequenced collections (Java 21) and what gap did they fill?

Before Java 21 there was no common type expressing "a collection with a defined encounter order and accessible ends." `List` had `get(0)`/`get(size-1)`, `Deque` had `getFirst`/`getLast`, `LinkedHashSet` had neither cleanly, and there was no way to reverse-view uniformly. **`SequencedCollection`**, **`SequencedSet`**, and **`SequencedMap`** unify this with `addFirst`/`addLast`, `getFirst`/`getLast`, `removeFirst`/`removeLast`, and `reversed()` (a view, O(1) to obtain). `List`, `Deque`, `LinkedHashSet`, `LinkedHashMap`, `SortedSet`, and `SortedMap` were retrofitted to implement them.

```java
SequencedCollection<String> sc = new ArrayList<>(List.of("a","b","c"));
sc.getFirst();      // "a"   — no more get(0)
sc.getLast();       // "c"
sc.reversed();      // view: [c, b, a] without copying
LinkedHashMap<K,V> m = ...;  m.firstEntry(); m.pollLastEntry();
```

### Q21. [Practical] How does `HashMap` resize, and what changed regarding treeification and the Java 7→8 concurrency bug?

`HashMap` stores entries in an array of buckets indexed by `(n-1) & hash` (where `hash` spreads the key's `hashCode` by XORing high bits down). When `size > capacity * loadFactor` (default 0.75), capacity doubles and entries rehash. In Java 8, when a single bucket exceeds **8** colliding entries *and* the table is ≥64, that bucket converts from a linked list to a **red-black tree**, dropping worst-case lookup from O(n) to O(log n) — a hardening against hash-collision DoS attacks (especially relevant when keys come from untrusted input).

The famous **Java 7 infinite-loop bug**: under concurrent `put`, the old resize used head-insertion which could create a circular linked list, causing `get` to spin at 100% CPU forever. Java 8 changed resize to preserve order (tail-style split into "lo"/"hi" lists), eliminating the cycle — but `HashMap` is **still not thread-safe**; concurrent modification can lose updates or throw. For concurrency use `ConcurrentHashMap` (lock-striping/CAS per-bucket, no global lock).

**Security note:** never trust externally-controlled keys without bounded sizes; treeification mitigates but does not eliminate collision-based algorithmic-complexity attacks. Prefer randomized or strong hashing for adversarial inputs.

### Q22. [Coding] Find the top-K frequent elements in a stream of numbers.

**Problem:** Given `int[] nums` and `k`, return the `k` most frequent elements.

**Approach 1 — count + sort:** O(n + m log m) where m = distinct count.

**Approach 2 — min-heap of size k (optimal for k ≪ n):**

```java
static List<Integer> topK(int[] nums, int k) {
    Map<Integer, Integer> freq = new HashMap<>();
    for (int n : nums) freq.merge(n, 1, Integer::sum);   // count

    // min-heap by frequency; keep only the k largest
    PriorityQueue<Map.Entry<Integer,Integer>> heap =
        new PriorityQueue<>(Map.Entry.comparingByValue());
    for (var e : freq.entrySet()) {
        heap.offer(e);
        if (heap.size() > k) heap.poll();                // evict smallest freq
    }
    List<Integer> res = new ArrayList<>();
    while (!heap.isEmpty()) res.add(heap.poll().getKey());
    Collections.reverse(res);                            // most frequent first
    return res;
}
```

**Stream form:** `freq.entrySet().stream().sorted(comparingByValue(reverseOrder())).limit(k).map(Entry::getKey).toList();`

**Complexity:** heap approach is **O(n + m log k)** time, O(m) space — better than full sort when k is small. Bucket-sort by frequency gives **O(n)** if needed. **Edge cases:** k ≥ distinct count (return all), ties (spec-dependent), empty input.

### Q23. [Practical] How do you diagnose and reason about a memory leak in a long-running Java service?

```
Symptom: heap grows over hours, GC pauses lengthen, eventual OutOfMemoryError.
Toolchain:
  1. Confirm it's a leak vs just high load: GC logs (-Xlog:gc*) →
     does old-gen occupancy after Full GC keep rising? That's a leak signature.
  2. Capture a heap dump: jcmd <pid> GC.heap_dump, or -XX:+HeapDumpOnOutOfMemoryError.
  3. Analyze in Eclipse MAT / VisualVM → "dominator tree" + "leak suspects".
  4. Find the GC root holding the growing object set.
```

The usual culprits: unbounded caches (use Caffeine with eviction, not a raw `HashMap`), `ThreadLocal`s not removed on thread-pool threads (they outlive the request and pin objects), static collections that only grow, unclosed resources (use try-with-resources), and listener/callback registrations never deregistered. The fix is structural — bound the cache, `remove()` ThreadLocals in a `finally`, and prefer `WeakReference`/`SoftReference` only when you genuinely understand reachability. **Real case:** a well-known pattern is a `ThreadLocal<SimpleDateFormat>` (also non-thread-safe!) on a request thread pool leaking formatter state and memory; the modern fix is a `static final DateTimeFormatter` (immutable, shared, no leak).

---

## 🔴 Expert (15+ yrs)

### Q24. [Theory] Compare the GC landscape (G1, ZGC, Shenandoah) and how you'd choose for a latency-sensitive service.

```
Serial/Parallel → throughput-first, stop-the-world; fine for batch.
G1 (default 9+)  → region-based, predictable pause targets (-XX:MaxGCPauseMillis),
                   pauses scale with live-set/region count; good general default.
ZGC             → concurrent, colored-pointers + load barriers; sub-millisecond
                   pauses largely INDEPENDENT of heap size (multi-TB heaps).
                   Generational ZGC (Java 21) adds young/old separation → far
                   better throughput than the original single-gen ZGC.
Shenandoah      → concurrent compaction via Brooks/load-reference barriers;
                   similar low-pause goals, different barrier strategy.
```

For a latency-sensitive service (trading, ad-serving, low p99.9 APIs) I choose **Generational ZGC** on Java 21: pauses stay sub-millisecond regardless of a large heap, which collapses tail latency. The trade-off is barrier overhead reducing peak throughput vs G1 and higher memory footprint. I'd validate with production-representative load, watch allocation rate (the real driver of GC frequency), and tune heap so the GC isn't constantly racing the mutator. For pure batch throughput I'd stay on Parallel GC.

### Q25. [Theory] What is escape analysis, and how do JIT optimizations (inlining, scalar replacement, deopt) affect how you write and benchmark code?

Escape analysis is a JIT optimization that proves an object never escapes the current thread/method. If so, the JIT can do **scalar replacement** (allocate the object's fields in registers/stack instead of the heap — effectively no allocation), eliminate locks (**lock elision** on provably-unshared objects), and stack-allocate. Combined with aggressive **inlining** (the JIT inlines hot small methods, then optimizes across the boundary) and speculative optimizations guarded by **deoptimization** (if a speculative assumption like "this call site is monomorphic" breaks, the JIT bails back to the interpreter and recompiles), the runtime behavior diverges wildly from naive bytecode reading.

The practical lesson: **don't micro-optimize by reading source; measure.** And benchmark correctly — use **JMH**, because hand-rolled `System.nanoTime()` loops are routinely destroyed by dead-code elimination, constant folding, and JIT warmup. JMH `Blackhole`s consume results to prevent DCE, runs warmup iterations to reach steady state, and forks JVMs to avoid profile pollution. I've seen "10x faster" micro-benchmarks that simply measured the JIT removing the entire loop.

### Q26. [Theory] Project Panama (Foreign Function & Memory API) and Vector API — what do they replace and when are they worth it?

The **Foreign Function & Memory (FFM) API** (finalized Java 22, preview in 21) replaces JNI for calling native code and `sun.misc.Unsafe`/`ByteBuffer` for off-heap memory. It offers `Arena`-scoped, bounds-checked, deterministically-freed native memory and `Linker`/method-handle-based native calls — safer (no manual JNI glue, leak-resistant arenas) and often faster than JNI. The **Vector API** (incubating) exposes SIMD: you express data-parallel computations that the JIT maps to AVX/NEON hardware vector instructions, with a scalar fallback.

When worth it: FFM for integrating native libraries (e.g. high-performance crypto, ML kernels, OS APIs) or large off-heap buffers without GC pressure; Vector API for numeric kernels (signal processing, analytics, similarity search) where SIMD gives multi-x speedups. The cost is complexity and API churn while features incubate — I'd isolate them behind a clean interface and keep a portable fallback. **Security note:** FFM arenas and confined scopes are a major safety upgrade over `Unsafe`, which could corrupt the heap and is being removed; migrating off `Unsafe` is now a real obligation.

### Q27. [Practical] You must migrate a large Java 8 monolith to Java 21. How do you plan and de-risk it?

I treat it as an incremental, reversible program, not a big-bang. The sequence:

1. **Inventory & gate** — enumerate dependencies; many old libs break on the module system, removed APIs (`javax` → `jakarta`), and stricter encapsulation (`--illegal-access` is gone in 17+). Build a compatibility matrix.
2. **Compile and run on 21 with the old `--release 8` semantics first**, fixing reflective access into JDK internals (the #1 breakage — Hibernate, Lombok, mocking libs, serialization frameworks). Use `--add-opens` only as a temporary bridge.
3. **Address removals**: `Nashorn` JS engine, CMS GC, `sun.misc.Unsafe` reliance, deprecated finalizers, security manager.
4. **CI on multiple JDKs** during the transition; ship the *runtime* upgrade (run on 21, compile to an older bytecode level) before adopting *language* features — decoupling these two reduces risk.
5. **Then modernize incrementally**: records for DTOs, pattern matching, switch expressions, and — high value — swap blocking thread pools for **virtual threads** to lift throughput with minimal code change.
6. **Validate GC and performance** with production load; Java 21's GC and JIT improvements often give free wins.

The behavioral key is sequencing and reversibility: every step independently deployable and rollback-able, with feature flags and canary deploys, so a 200-service estate moves without a freeze.

### Q28. [Behavioral] Tell me about a time you made a controversial technical decision about a core platform choice. How did you drive alignment?

Structure the answer with **STAR** and emphasize *judgment under ambiguity and stakeholder management*, which is what's actually being assessed at staff/principal level.

*Situation:* a team wanted to adopt a fully reactive stack (WebFlux/Reactor) for a high-concurrency service. *Task:* as the senior engineer I had to decide the concurrency model for a system 30 engineers would maintain for years. *Action:* I ran a spike comparing reactive vs Java 21 virtual threads under representative load, measured p99 latency and throughput (comparable), then weighed the *maintenance* cost — reactive's steep learning curve, hard debugging (no readable stack traces), and viral `Mono`/`Flux` types — against virtual threads' familiar blocking model. I wrote a one-page decision record, presented trade-offs (not opinions) to the team and architecture review, and explicitly named the reversibility plan. *Result:* we chose virtual threads; onboarding time dropped, incident debugging stayed simple, and throughput met SLOs. The lesson I emphasize: **the best technical choice is often the one the team can operate at 3 a.m.**, and alignment comes from data + a written, falsifiable rationale, not authority.

### Q29. [Theory] How does Java's serialization mechanism create security risk, and what are modern alternatives?

Java's built-in serialization (`Serializable`/`ObjectInputStream`) is a notorious attack surface: `readObject` can instantiate arbitrary classes on the classpath and trigger "gadget chains" (e.g. via Commons-Collections) leading to remote code execution from a crafted byte stream — the root of many high-profile CVEs. The problems are deep: it bypasses constructors, couples your wire format to private class structure, and trusts incoming bytes.

Mitigations and alternatives: enable **serialization filters** (`ObjectInputFilter` / `jdk.serialFilter`, Java 9+) to allow-list classes and bound depth/array sizes; never deserialize untrusted data with native serialization; prefer explicit, schema-driven formats — **JSON (Jackson), Protocol Buffers, Avro** — which don't instantiate arbitrary types. The modern guidance (Effective Java Item 85) is blunt: *avoid Java serialization entirely* for new systems. Records help by giving a transparent, constructor-validated representation, and JEP work on a successor serialization model continues. **Security takeaway:** treat any `readObject` over a network boundary as a potential RCE until proven otherwise.

### Q30. [Practical] Design a thread-safe, high-throughput in-memory rate limiter. Walk through the concurrency choices.

```
Goal: allow N requests / window per key, p99 < 100µs, millions of keys, no lock contention.

Design: token-bucket per key.
  ConcurrentHashMap<Key, Bucket>            // sharded, lock-free reads
  Bucket = { volatile long tokens; volatile long lastRefillNanos; }
  acquire(): CAS-loop using a VarHandle / AtomicLong on tokens after lazy refill
             based on (now - lastRefill) * rate, capped at capacity.
```

Key concurrency choices and trade-offs: use `ConcurrentHashMap.computeIfAbsent` to create buckets (atomic, but mind that the mapping function shouldn't be expensive/reentrant). Inside a bucket, prefer **lock-free CAS** (`AtomicLong`/`VarHandle`) over `synchronized` to avoid blocking under contention — and on Java 21, CAS also avoids pinning virtual threads. Refill *lazily* on access rather than a background timer thread per key (which wouldn't scale to millions of keys). For multi-instance correctness you'd push state to Redis (with Lua for atomic check-and-decrement) accepting network latency, or accept per-node approximate limiting. Watch for **memory growth**: evict idle buckets (Caffeine with expiry) so the map doesn't leak keys. I'd benchmark with JMH and a contended multi-thread harness, and validate fairness and burst behavior, because the subtle bugs here are clock skew, integer overflow in the refill math, and starvation under heavy contention.

### Q31. [Theory] Explain `false sharing` and how you'd detect and eliminate it.

False sharing occurs when independent variables updated by different threads sit on the **same CPU cache line** (typically 64 bytes). Even though the threads touch *different* variables, the cache-coherence protocol invalidates the whole line on every write, ping-ponging it between cores' caches and tanking throughput — a silent killer in lock-free counters, queues, and per-thread accumulators.

Detection: it shows up as high cache-miss / coherence-traffic counters in `perf`/VTune, or as throughput that *worsens* with more threads despite no logical sharing. Elimination: pad/align hot fields to occupy their own cache line. Java's `@jdk.internal.vm.annotation.Contended` (and `-XX:-RestrictContended`) tells the JVM to pad — `LongAdder` uses exactly this internally, which is why `LongAdder` beats `AtomicLong` under high write contention (it stripes counters across padded cells). The interview signal here is knowing that *correct* concurrent code can still be slow for purely microarchitectural reasons, and that you reach for `LongAdder`/`@Contended` rather than reinventing padding.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q32. [Theory] How does autoboxing work, and why can `Integer a = 127; Integer b = 127; a == b` be `true` but `128` be `false`?

Autoboxing is the compiler silently converting between a primitive and its wrapper: `Integer i = 5;` is rewritten to `Integer i = Integer.valueOf(5);`, and `int x = i;` becomes `int x = i.intValue();`. The subtle behavior comes from `Integer.valueOf`, which consults the **`IntegerCache`** — a flyweight cache of boxed `Integer` objects for the range −128 to 127 (the upper bound is tunable via `-XX:AutoBoxCacheMax`). Values in that range return the *same cached instance*, so `==` (reference equality) is `true`; values outside it allocate a fresh object each time, so `==` is `false`.

```java
Integer a = 127, b = 127;   a == b;  // true  — same cached object
Integer c = 128, d = 128;   c == d;  // false — two distinct objects
c.equals(d);                          // true  — value comparison, always correct
```

The interview point is twofold: (1) **never compare wrappers with `==`** — always use `.equals()` or unbox to a primitive — because the cache makes `==` accidentally work for small numbers and silently fail for large ones, a classic production bug. (2) Autoboxing in hot loops (`Long sum = 0L; for(...) sum += x;`) creates millions of throwaway wrapper objects, hammering the allocator and GC; use primitives or primitive streams (`LongStream`) there. `Boolean`, `Byte`, `Short`, `Character` (0–127), and `Long` (−128–127) have similar caches; `Float`/`Double` do not.

#### Q33. [Theory] What actually happens when the JVM "loads a class"? Walk through loading, linking, and initialization.

Class lifecycle has three phases. **Loading**: a `ClassLoader` reads the `.class` bytes and creates a `Class` object in the heap, with the class metadata stored in **Metaspace** (native memory, since Java 8 — it replaced the fixed-size PermGen). Loading is delegated up the parent chain (the **parent-delegation model**): bootstrap → platform → application loader, so core JDK classes can't be spoofed by an application class of the same name. **Linking** has three sub-steps: *verification* (bytecode is type-safe and well-formed — a key security gate), *preparation* (static fields get default zero values), and *resolution* (symbolic references in the constant pool are resolved to direct references, possibly lazily). **Initialization**: static initializers and static field assignments run, exactly once, in textual order.

```
[bootstrap CL]  → java.* core classes (no parent)
      ↑ delegates up
[platform CL]   → JDK modules
      ↑
[application CL] → your classpath
```

Initialization is **lazy and triggered** by first active use: `new`, a static method call, a static (non-constant) field access, or reflection. The JVM guarantees initialization is **thread-safe** — the classloader holds a per-class lock, which is precisely why the *initialization-on-demand holder idiom* is a correct, lock-free singleton:

```java
class Singleton {
    private Singleton() {}
    private static class Holder { static final Singleton INSTANCE = new Singleton(); }
    static Singleton get() { return Holder.INSTANCE; }   // Holder loads/inits only on first call
}
```

This matters in interviews for understanding `ClassNotFoundException` (loading-time, you asked for it) vs `NoClassDefFoundError` (it was present at compile time but failed to load/initialize at runtime — often a *static initializer threw*), and for diagnosing classloader leaks in app servers.

#### Q34. [Theory] Why is `String` immutable, and what are the string pool, interning, and compact strings?

`String` is `final` with a `private final byte[]` backing (a `char[]` before Java 9) and exposes no mutators, so its value can never change after construction. Immutability buys: **safe sharing** (strings are used everywhere as keys, in security checks, class names, file paths — mutability would be catastrophic), **thread-safety with zero synchronization**, **safe caching of `hashCode`** (computed once, lazily, and stored), and the **string pool**. String *literals* are automatically interned into a pool (stored in the heap since Java 7, formerly PermGen), so identical literals share one object — that is why `"hi" == "hi"` is `true` but `new String("hi") == "hi"` is `false` (the `new` forces a distinct object). `String.intern()` lets you manually canonicalize a runtime-built string into the pool.

```java
String a = "java";              // pooled literal
String b = new String("java");  // heap object, NOT pooled
a == b;            // false
a == b.intern();   // true  — intern() returns the pooled canonical instance
```

Since Java 9, **compact strings** (JEP 254) store Latin-1 (one byte/char) strings as `byte[]` with a `coder` flag, falling back to UTF-16 (two bytes/char) only when needed — roughly halving heap usage for typical ASCII-heavy applications, a large free win. The interview trap: never rely on `==` for string content (use `.equals()`); interning aggressively can *backfire* by retaining strings forever in the pool, so it's only worth it for high-duplication, long-lived keys.

#### Q35. [Theory] Explain the difference between `String`, `StringBuilder`, and `StringBuffer` and when each is appropriate.

`String` is immutable, so every concatenation produces a *new* object — fine for a few joins, disastrous in a loop because it's O(n²) in copying. `StringBuilder` is a mutable, resizable `char`/`byte` buffer with **no synchronization**, so it's the default for building strings; `StringBuffer` is the legacy thread-safe version with every method `synchronized`, which you almost never need (a `StringBuilder` is local to a method 99% of the time, and synchronization on an unshared object is pure overhead).

```java
// BAD: O(n^2) — each += allocates and copies the whole accumulated string
String s = "";
for (String part : parts) s += part;

// GOOD: O(n) — single growable buffer, one final toString()
StringBuilder sb = new StringBuilder();
for (String part : parts) sb.append(part);
String s = sb.toString();
```

A nuance worth raising: simple compile-time-constant concatenation like `"a" + "b"` is folded by `javac` into the literal `"ab"`, and modern `javac` (Java 9+) compiles runtime `+` concatenation via `invokedynamic` and `StringConcatFactory`, which the JVM can optimize better than the old "new StringBuilder per expression" bytecode. So a single expression `a + b + c` is already efficient; the real win from `StringBuilder` is in **loops** where you concatenate repeatedly. Pre-size the builder (`new StringBuilder(expectedLen)`) when you can estimate the size to avoid internal array doublings.

### 🟡 Intermediate — extended

#### Q36. [Theory] Why are arrays covariant but generics invariant, and what bug does that prevent?

Java arrays are **covariant**: `String[]` is a subtype of `Object[]`. This was a 1.0 design decision (before generics) to allow polymorphic array methods, but it's a hole in the type system — it lets you store the wrong type and only fails at *runtime*:

```java
Object[] arr = new String[3];   // compiles — covariance
arr[0] = 42;                     // compiles, but throws ArrayStoreException at runtime
```

Generics are deliberately **invariant**: `List<String>` is *not* a subtype of `List<Object>`. This is what makes the analogous mistake a *compile-time* error instead of a runtime surprise:

```java
List<Object> list = new ArrayList<String>();  // COMPILE ERROR — invariance forbids it
```

The reason the two differ is **type erasure** (Q7): arrays carry their element type at runtime and can enforce it with a store check, but generic collections don't — at runtime a `List<String>` is just a `List`, so the JVM couldn't perform an analogous `ArrayStoreException`. Forbidding the covariant assignment at compile time is how Java keeps generics sound. This also explains why you can't create generic arrays (`new T[]`, `new List<String>[]`): combining covariant arrays with erased generics would let you defeat type safety silently, so the language bans it and you use `List<List<String>>` or an `@SuppressWarnings` cast instead. When you *want* covariance with generics, you use bounded wildcards (PECS, Q8).

#### Q37. [Theory] How does the enhanced for-loop work under the hood, and what makes a collection "fail-fast"?

The enhanced for-loop (`for (T x : coll)`) is pure syntactic sugar. Over an `Iterable`, the compiler desugars it into an explicit `Iterator` loop; over an array, into an indexed loop:

```java
for (String s : list) { use(s); }
// compiles to:
for (Iterator<String> it = list.iterator(); it.hasNext(); ) { String s = it.next(); use(s); }
```

Because there's a hidden iterator, you **cannot structurally modify the collection** (add/remove) inside the loop via the collection's own methods — that's where **fail-fast** comes in. Collections like `ArrayList`/`HashMap` keep an internal `modCount`; the iterator snapshots it on creation and checks it on every `next()`, throwing `ConcurrentModificationException` if it changed. This is a *best-effort bug detector*, not a concurrency guarantee — the name is misleading; it catches single-threaded mid-loop mutation just as readily as concurrent mutation.

```java
for (String s : list) if (s.isEmpty()) list.remove(s);  // ConcurrentModificationException
// Correct options:
list.removeIf(String::isEmpty);                          // Java 8, cleanest
Iterator<String> it = list.iterator();
while (it.hasNext()) if (it.next().isEmpty()) it.remove();  // iterator.remove() is allowed
```

Contrast with **fail-safe** iterators on `CopyOnWriteArrayList` and `ConcurrentHashMap`, which iterate over a snapshot/weakly-consistent view and never throw `CME` but may not reflect concurrent updates. The signal here is knowing that `CME` is a *programming-error detector*, why `removeIf`/`Iterator.remove` are the fixes, and the trade-off of weakly-consistent iteration in concurrent collections.

#### Q38. [Theory] How is a `switch` on `String` and on `enum` implemented in bytecode, and why does that matter?

`switch` on a `String` is compiled in **two stages**: the JVM has no native string switch, so `javac` first switches on `String.hashCode()` (a `lookupswitch`/`tableswitch` on the int hash), then within each matching hash bucket does an `.equals()` check to guard against hash collisions, then maps to a synthetic int that drives the real branch. So a string switch is roughly "hash, then equals-confirm" — O(1)-ish, far better than a chain of `if (s.equals(...))`, but it still calls `equals` and will NPE if the selector is `null` (always null-check before a string switch, pre-Java-21).

`switch` on an `enum` compiles to a `tableswitch` on a generated **`$SwitchMap`** array that maps each constant's `ordinal()` to a dense 1..N index. This indirection array exists specifically so that recompiling the *enum* (which can renumber ordinals) doesn't silently break already-compiled *switch* classes — the map is regenerated per switching class.

```
switch(str):  hashCode() ──lookupswitch──> bucket ──.equals()──> case body
switch(enm):  ordinal()  ──$SwitchMap[]──> tableswitch ──> case body
```

Why it matters: enum switches are extremely fast (dense table jump) and are the reason you should switch on enums rather than int constants. And it explains a real footgun — comparing enums with `==` is correct and fast (they're singletons per the classloader), while a `null` enum selector in a `switch` throws `NullPointerException` *before* any case runs (older Java) — Java 21's pattern-matching switch finally lets you write `case null ->`.

#### Q39. [Theory] How does `try-with-resources` desugar, and what is exception suppression?

`try-with-resources` (Java 7) manages any `AutoCloseable`. The compiler rewrites it into a `try/finally` that calls `close()` in reverse order of acquisition and, crucially, handles the "exception during close masks the real exception" problem via **suppressed exceptions**:

```java
try (var a = open("a"); var b = open("b")) { use(a, b); }
// desugars (conceptually) to:
var a = open("a");
try {
    var b = open("b");
    try { use(a, b); }
    finally { b.close(); }
} finally { a.close(); }
```

The subtle part: if the body throws *and* `close()` also throws, plain `try/finally` would **lose the body's exception** (the `finally`'s exception wins and the original is discarded — one of the worst silent bugs in old Java). `try-with-resources` instead keeps the body's exception as the *primary* and attaches the close-time exception via `Throwable.addSuppressed()`, retrievable with `getSuppressed()`. Resources are closed in reverse declaration order (LIFO), matching construction dependencies.

This is why hand-rolled `try/finally { resource.close(); }` is discouraged: it's verbose, easy to get the null-check and ordering wrong, and silently swallows exceptions. The interview follow-up is often "what does a `return` inside `try` with a `finally` do?" — the `finally` runs after the return value is computed but before the method actually returns, and a `return`/`throw` *in the finally* overrides the try's outcome (another reason to avoid logic in `finally`).

#### Q40. [Theory] Explain the `Comparable` vs `Comparator` contracts and why a broken comparator throws "Comparison method violates its general contract!"

`Comparable<T>` defines a class's *natural ordering* via `compareTo` (one canonical order: `String`, `Integer`, `LocalDate`). `Comparator<T>` is an external, pluggable ordering you pass to `sort`/`TreeMap`/`PriorityQueue`, letting you sort the same type many ways without touching it. Both must implement a **total order**: it must be *antisymmetric* (`sgn(cmp(a,b)) == -sgn(cmp(b,a))`), *transitive* (`a>b && b>c ⇒ a>c`), and *consistent* (equal elements compare equal in both directions). It's also strongly recommended that `compareTo`/`compare` be **consistent with `equals`** — when `(a.compareTo(b)==0) == a.equals(b)` — otherwise sorted collections like `TreeSet`/`TreeMap` (which use `compareTo`, *not* `equals`, to judge duplicates) behave surprisingly.

The dreaded runtime exception `IllegalArgumentException: Comparison method violates its general contract!` comes from the **TimSort** algorithm (the JDK's `Arrays.sort`/`Collections.sort` for objects), which actively validates the contract and bails out when it detects an intransitive or inconsistent comparator — a real bug, not a JDK fault. The classic cause is subtraction-based comparison that overflows, or comparing only part of the object:

```java
// BROKEN: int subtraction overflows for large/negative values → non-transitive
Comparator<Integer> bad = (x, y) -> x - y;          // Integer.MIN - 1 wraps around
// CORRECT: use the built-in comparison, never subtraction
Comparator<Integer> good = Integer::compare;        // or Comparator.naturalOrder()
Comparator<Person> byAge = Comparator.comparingInt(Person::age)
                                     .thenComparing(Person::name);   // composable, safe
```

The takeaways: build comparators with `Comparator.comparing/thenComparing/reversed` (composable and overflow-safe), never with `a - b`, and ensure the ordering is total — partial or random comparators corrupt sorts and trees.

#### Q41. [Theory] What is the difference between `final`, `finally`, and `finalize`, and why is `finalize` deprecated in favor of `Cleaner`?

These three are unrelated despite the similar names. **`final`** is a modifier (Q6) — non-reassignable variable, non-overridable method, non-extensible class. **`finally`** is a block that always executes after `try` (barring `System.exit`/JVM crash), used for cleanup (Q39). **`finalize()`** was an `Object` method the GC *might* call before reclaiming an object, intended for native-resource cleanup.

`finalize()` is **deprecated for removal** (since Java 9) because it is fundamentally broken: there's **no guarantee it ever runs** (or runs promptly, or at all before JVM exit), it runs on an unspecified finalizer thread with unbounded latency, an exception in it is swallowed, it can *resurrect* the object (re-create a strong reference), and it adds a GC penalty by making finalizable objects survive an extra collection cycle. It's also a security hole (the "finalizer attack" — a malformed object's finalizer running after a constructor throws).

```java
// Modern replacement: java.lang.ref.Cleaner — runs cleanup on a dedicated thread,
// triggered when the object becomes phantom-reachable. The cleanup action must NOT
// hold a strong reference back to the object, or it can never be collected.
class Resource implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    private final Cleaner.Cleanable cleanable;
    Resource(long nativeHandle) {
        // state captured in a static inner Runnable, not a lambda capturing `this`
        this.cleanable = CLEANER.register(this, new CleanupTask(nativeHandle));
    }
    public void close() { cleanable.clean(); }   // deterministic, preferred path
    private record CleanupTask(long handle) implements Runnable {
        public void run() { /* free native handle */ }
    }
}
```

The modern doctrine (Effective Java Item 8): **prefer `AutoCloseable` + try-with-resources for deterministic cleanup**, and use `Cleaner` (or `PhantomReference`) only as a *safety net* for native resources — never as the primary mechanism, because its timing is non-deterministic.

#### Q42. [Practical] Walk through how a lambda is actually compiled — why isn't it just an anonymous class?

A lambda is **not** desugared into an anonymous inner class (which would generate a separate `.class` file per lambda and pay a load/allocate cost). Instead, `javac` emits an **`invokedynamic`** bytecode at the lambda's use site, plus a private synthetic method holding the lambda body. The first time that `invokedynamic` runs, the JVM calls a **bootstrap method**, `LambdaMetafactory.metafactory`, which *spins up* (at runtime) a class implementing the functional interface and returns a `CallSite` that's then linked permanently. Subsequent executions are a direct, cheap call.

```
lambda site ──invokedynamic──> [first call] LambdaMetafactory bootstrap ──> generated class
                               [later calls] linked CallSite ──> direct invoke
```

The payoff of this indirection: (1) the *strategy* for implementing lambdas is decided by the runtime, not frozen into bytecode, so the JDK can change/optimize it without recompiling your code; (2) **stateless** lambdas (those that capture nothing, like `x -> x * 2`) are linked to a **singleton** instance — they don't allocate per use, unlike anonymous classes; (3) capturing lambdas only allocate when they actually capture variables. There are real behavioral differences from anonymous classes too: `this` inside a lambda refers to the *enclosing* instance (lambdas have no own `this`), and a lambda has no separate scope, so it can't shadow enclosing locals. This is why lambdas are generally lighter than anonymous classes, and why `LambdaMetafactory` shows up in stack traces and warm-up profiling.

#### Q43. [Theory] What is the difference between intermediate and terminal stream operations, and what does "lazy evaluation" actually buy you?

A `Stream` pipeline is **lazy**: *intermediate* operations (`map`, `filter`, `sorted`, `peek`, `limit`) only build up a description of the computation and return a new stream — **nothing executes** until a *terminal* operation (`collect`, `forEach`, `reduce`, `count`, `findFirst`, `anyMatch`) is invoked. This laziness enables two important optimizations: **fusion** (multiple stages run in a single pass over each element rather than materializing intermediate collections) and **short-circuiting** (operations like `limit`, `findFirst`, `anyMatch` stop pulling from the source as soon as the result is determined).

```java
List<String> result = names.stream()
    .filter(n -> { System.out.println("filter " + n); return n.length() > 3; })
    .map(n -> { System.out.println("map " + n); return n.toUpperCase(); })
    .limit(1)          // short-circuits!
    .toList();
// Prints filter/map for elements only until ONE passes — not the whole list.
// Element-at-a-time: filter(a)->map(a)->emit, stop. No full intermediate lists.
```

The practical consequences interviewers probe: (1) a pipeline with no terminal op does literally nothing — a common "why isn't my `peek`/`map` running?" bug; (2) streams are **single-use** — once consumed, re-using one throws `IllegalStateException`; (3) **don't mutate shared state from lambdas** (side effects break parallelism and laziness reasoning — that's what `collect`/`reduce` are for); (4) infinite streams (`Stream.iterate`, `Stream.generate`) only work because of laziness plus a short-circuit like `limit`. The mental model is *pull-based, element-at-a-time, on demand* — not "transform the whole list, then the next whole list," which is what a chain of `for` loops with temporary `ArrayList`s would do.

### 🟠 Advanced — extended

#### Q44. [Theory] How does `ConcurrentHashMap` achieve thread-safety without a global lock, and how does it differ from `Hashtable` and `Collections.synchronizedMap`?

`Hashtable` and `Collections.synchronizedMap` serialize **every** operation on a single lock — correct but a scalability bottleneck, because readers block writers and each other. `ConcurrentHashMap` (CHM) instead allows highly concurrent access. In Java 7 it used **segment-based lock striping** (a fixed number of sub-locks). Java 8 rewrote it: there are **no segments**; instead it locks at the granularity of an individual **bucket (the first node of a bin)** using `synchronized` on that node, and uses **CAS** (`compareAndSet` via `Unsafe`/`VarHandle`) for the common lock-free cases — inserting into an empty bin, and updating the size counter (which uses a `LongAdder`-style striped counter to avoid contention). Reads are **completely lock-free** (fields are `volatile`, giving visibility without locking).

```
put(k,v):
  bin empty?  → CAS a new node in (no lock)
  bin occupied? → synchronized(firstNode) { walk list / red-black tree, insert/update }
  resize?     → cooperative: multiple threads help transfer bins concurrently
```

Key semantic differences and gotchas: CHM **disallows `null` keys and values** (so a `null` from `get` unambiguously means "absent" — there's no `containsKey` race), whereas `HashMap` allows them. Its iterators are **weakly consistent** (never throw `ConcurrentModificationException`; reflect some-but-maybe-not-all concurrent updates). Bulk methods like `computeIfAbsent`, `compute`, and `merge` are **atomic per key** — but the mapping function runs while holding the bin lock, so it must be short and **must not** re-enter the same map (deadlock/`IllegalState`). And aggregate methods like `size()` are **approximate/snapshot**, not a linearization point. The interview signal is articulating *why* per-bin locking + CAS + volatile reads scales (no single hot lock), and the correctness rules around null and atomic compute methods.

#### Q45. [Theory] Explain the ForkJoinPool and work-stealing, and why parallel streams can be dangerous in production.

`ForkJoinPool` is a work-stealing thread pool designed for divide-and-conquer recursion. Each worker has its **own double-ended queue (deque)**; it pushes/pops its own subtasks LIFO (cache-friendly, recently-created tasks first), and when idle it **steals** from the *other* end (FIFO) of a busy worker's deque. This minimizes contention (workers mostly touch their own deque) and keeps all cores busy without a central queue bottleneck. `fork()` schedules a subtask; `join()` waits — and critically, a joining worker doesn't just block, it can execute other pending tasks while waiting.

```
worker A deque: [t1 t2 t3]   <- A pops t3 (LIFO, hot in cache)
                  ^ idle worker B steals t1 from the FAR end (FIFO)
```

The production danger with **parallel streams** (`stream().parallel()`): they run on the **shared, JVM-wide `ForkJoinPool.commonPool()`** by default. That pool has only `(#cores − 1)` threads, so one careless parallel stream doing **blocking I/O** can starve *every other* parallel stream and `CompletableFuture.*Async` in the whole JVM, including library code you don't control. Worse, blocking inside fork/join wastes a precious worker. Other traps: parallelism only pays off for **CPU-bound work on large datasets with cheap, stateless, associative operations and a splittable source** (`ArrayList`/arrays split well; `LinkedList`/`Iterator`-based sources split terribly); ordering-sensitive ops (`findFirst`, `forEachOrdered`) add coordination cost; and shared mutable state corrupts results.

```java
// Don't blindly .parallel(). If you must parallelize blocking work, isolate the pool:
ForkJoinPool custom = new ForkJoinPool(8);
custom.submit(() -> list.parallelStream().forEach(this::callExternalService)).get();
// Better for blocking I/O on Java 21: virtual threads, not parallel streams.
```

The rule I give: parallel streams for *CPU-bound, large, splittable, side-effect-free* pipelines after measuring; never for I/O (use virtual threads or a dedicated executor), and never assume the common pool is yours alone.

#### Q46. [Theory] What are the JMM guarantees for `final` fields, and how do they enable safe publication of immutable objects?

Beyond `volatile`'s visibility rules, the JMM gives `final` fields a **special freeze guarantee**: when a constructor finishes, all `final` fields are *frozen*, and any thread that obtains a reference to the object **through a properly published reference** is guaranteed to see the correctly-constructed values of those `final` fields — *without any synchronization*. This is precisely the property that makes immutable objects (Q9) safe to share across threads freely: a `record` or a class with all-`final` fields can be handed to other threads with no locks, no `volatile`, and they'll never see a partially-constructed object.

```java
class Holder {
    private final int x;            // frozen at end of constructor
    Holder(int x) { this.x = x; }   // other threads see x correctly IF the Holder
}                                   // reference itself is safely published
```

The crucial caveat is **safe publication**: the `final`-field guarantee only holds if the object reference doesn't *escape during construction* (e.g. registering `this` in a listener before the constructor returns leaks an unfrozen object) and if the reference is published safely (via a `volatile`/`final` field, a static initializer, a thread-safe collection, or through proper synchronization). A *non-final* field has **no** such guarantee — another thread might see its default value even after construction, the bug that breaks naive lazy initialization. This is also exactly why **double-checked locking requires `volatile`** on the instance field:

```java
class Lazy {
    private static volatile Lazy instance;        // volatile is mandatory
    static Lazy get() {
        if (instance == null)                     // first check, no lock
            synchronized (Lazy.class) {
                if (instance == null) instance = new Lazy();  // second check, locked
            }
        return instance;
    }
}
```

Without `volatile`, another thread could observe a non-null reference whose object fields aren't yet visible (the write to `instance` and the writes inside the constructor can be reordered) — the textbook broken-DCL bug. The deeper takeaway: `final` fields + safe publication is the *cheapest* correct concurrency tool there is, which is why "make it immutable" is the first concurrency advice.

#### Q47. [Theory] How does `CompletableFuture` chaining work, and what's the difference between `thenApply`/`thenCompose`/`thenCombine` and the `*Async` variants?

`CompletableFuture` models an async computation you can compose without blocking. The combinator family mirrors the `Stream`/`Optional` shapes: **`thenApply(fn)`** transforms the result synchronously (`T -> U`, like `map`); **`thenCompose(fn)`** flattens a *nested* future (`T -> CompletableFuture<U>`, like `flatMap`) — you use it when the next step is itself async, to avoid `CompletableFuture<CompletableFuture<U>>`; **`thenCombine(other, fn)`** joins **two independent** futures when both complete (like `zip`); **`thenAccept`/`thenRun`** are terminal-ish consumers. Error handling uses `exceptionally` (recover from a failure), `handle` (see result *or* exception), and `whenComplete` (side-effect, doesn't alter the result).

```java
CompletableFuture<Profile> profile =
    fetchUser(id)                              // CF<User>
        .thenCompose(u -> fetchOrders(u.id())) // flatMap: async step → CF<List<Order>>
        .thenCombine(fetchPrefs(id),           // zip with an independent CF<Prefs>
                     (orders, prefs) -> buildProfile(orders, prefs))
        .exceptionally(ex -> Profile.empty());  // recover
```

The **`*Async` suffix** controls *which thread* runs the continuation. The non-async form (`thenApply`) runs the callback on **whatever thread completed the previous stage** (or the caller's thread if already complete) — cheap, but it can accidentally run heavy work on, say, an I/O callback thread or the common pool. The `*Async` form (`thenApplyAsync`) runs the continuation on the **`ForkJoinPool.commonPool()`** by default, or on an `Executor` you supply — the safe choice when the continuation is blocking or CPU-heavy, or when you must control the thread. The interview-grade points: **always pass an explicit `Executor`** to `*Async` in production (don't share the common pool with parallel streams, Q45), `thenCompose` vs `thenApply` is the flatMap-vs-map distinction (using `thenApply` where you needed `thenCompose` yields a wrapped future), and `.join()`/`.get()` block — keep them at the very edge (or replace the whole pattern with structured concurrency + virtual threads on Java 21, Q18).

#### Q48. [Theory] How are non-static inner classes, static nested classes, and local/anonymous classes represented at the bytecode level, and what memory pitfall do inner classes create?

Java has four nested forms, and `javac` compiles each into a **separate top-level `.class` file** (e.g. `Outer$Inner.class`, `Outer$1.class` for an anonymous class). A **static nested class** is just a normal class namespaced inside another — it has *no* reference to an outer instance. A **non-static inner class** is different: the compiler injects a **synthetic field** holding a reference to the enclosing instance (`Outer this$0`) and a synthetic constructor parameter to set it — that's what lets `Inner` access `Outer`'s instance members. **Local** and **anonymous** classes additionally **capture** the effectively-final local variables they use, by copying them into synthetic fields at construction.

```
Outer$Inner.class  → has hidden field 'final Outer this$0;'   (strong ref to outer!)
Outer$StaticNested.class → no outer reference
Outer$1.class (anon) → this$0 + copies of captured locals as synthetic fields
```

The memory pitfall: that hidden `this$0` reference is a **strong reference to the entire enclosing object**. If an inner-class instance (a listener, a `Runnable`, a callback, a returned `Iterator`) outlives its outer object, it **pins the whole outer instance in memory** — a classic leak, especially when you hand a non-static inner `Runnable` to a long-lived executor or register an inner-class listener and never deregister it. The fix is to make the nested class **`static`** whenever it doesn't need outer state (Effective Java Item 24: *favor static member classes*), passing in only what it needs, optionally via a `WeakReference`. The same `this$0` capture is why a non-static inner class can't be instantiated without an outer instance (`outer.new Inner()`), and why anonymous-class-heavy code can quietly retain large object graphs.

#### Q49. [Theory] What does the JIT's tiered compilation do, and how do C1, C2, OSR, and deoptimization shape warm-up and steady-state performance?

The HotSpot JVM starts by **interpreting** bytecode (fast startup, slow execution), and **profiles** as it runs — counting method invocations and loop back-edges. **Tiered compilation** then promotes hot code through levels: the **C1 (client) compiler** compiles quickly with light optimizations and adds profiling counters; the **C2 (server) compiler** kicks in for the hottest methods with aggressive, expensive optimizations (inlining, escape analysis, loop unrolling, vectorization) using the profile C1 gathered. **On-Stack Replacement (OSR)** lets the JVM swap a *long-running loop* from interpreted to compiled code *mid-execution* without waiting for the method to be re-entered — important for hot loops in `main`. This staged approach balances startup latency against peak throughput.

```
[interpret + profile] → C1 (quick, +counters) → C2 (max opt, uses profile)
                                  ↑                      |
                          deoptimization  <──── speculation invalidated
                          (fall back to interpreter, recompile)
```

The flip side is **deoptimization**: C2 makes *speculative* bets from the profile — "this call site is monomorphic," "this branch is never taken," "this type is always X." If a bet later breaks (a new subclass appears, the cold branch fires), the JVM **deoptimizes**: discards the compiled code, falls back to the interpreter for those frames, and re-profiles/recompiles. The performance lessons that interviewers want: (1) **warm-up matters** — code is slow until hot, which wrecks naive benchmarks and the first requests after deploy (mitigated by JMH warm-up, by AOT/CDS, or Project Leyden's coming ahead-of-time work); (2) **megamorphic call sites** (many implementations behind one interface call) defeat inlining and devirtualization, so excessive polymorphism has a real cost; (3) keep hot methods **small** so they're inline candidates; (4) you cannot reason about performance from source or bytecode alone because the running code may be radically transformed and may de/re-optimize over time — *measure at steady state*.

#### Q50. [Theory] What is a `ThreadLocal`, how is it stored internally, and why does it leak on thread pools?

A `ThreadLocal<T>` gives each thread its **own independent copy** of a value — useful for non-thread-safe-but-reusable objects (`SimpleDateFormat`, formatters, per-request context, transaction handles). The key internal detail interviewers want: the value is **not** stored in the `ThreadLocal` object. Each `Thread` carries a `ThreadLocalMap` field; the `ThreadLocal` instance is the *key* into that per-thread map, and the value lives there. So lookup is "current thread → its map → keyed by this ThreadLocal." Entries use a **`WeakReference` to the `ThreadLocal` key** (so an unreachable `ThreadLocal` can be GC'd) — but the **value is a strong reference**.

```
Thread A ── ThreadLocalMap { [weakRef→TL1] = valueA1, [weakRef→TL2] = valueA2 }
Thread B ── ThreadLocalMap { [weakRef→TL1] = valueB1 }
            ▲ key is weak, VALUE is strong  ← source of the leak
```

The leak mechanism on **thread pools**: pooled threads live for the application's lifetime and are *reused* across thousands of tasks. If a task does `threadLocal.set(bigObject)` and never `remove()`s it, the value stays strongly reachable through the thread's `ThreadLocalMap` for as long as the thread lives — across all future tasks. Even if the `ThreadLocal` *key* becomes weakly unreachable and is collected, you get a **stale entry** (`null` key, live value) that is only cleared opportunistically on later map operations — so the value can linger indefinitely. The disciplines: **always `remove()` in a `finally`** (the request-scope pattern), never put unbounded/large state in a `ThreadLocal` on pooled threads, and prefer immutable shared objects (a `static final DateTimeFormatter`) over `ThreadLocal` workarounds. On Java 21, **`ScopedValue`** (preview) is the modern, leak-resistant, immutable replacement for the read-mostly request-context use case, and it cooperates cleanly with virtual threads.

#### Q51. [Practical] You see `OutOfMemoryError: Metaspace` (or `unable to create new native thread`). What's the root cause class and how do you investigate?

These are **non-heap** OOMs, which trips people who only think about `-Xmx`. **`OutOfMemoryError: Metaspace`** means class *metadata* exhausted Metaspace (native memory, replacing PermGen since Java 8). The dominant root cause is a **classloader leak**: classes are unloaded only when their *defining classloader* becomes unreachable, so anything that pins a classloader — repeated hot-redeploys in an app server, dynamic proxy/CGLIB/bytecode-generation in a loop, scripting engines, or a `ThreadLocal`/static in a *parent* loader referencing a class from a *child* loader — accumulates class metadata forever.

```bash
# Bound Metaspace so a leak fails fast instead of starving the host:
-XX:MaxMetaspaceSize=256m -XX:+HeapDumpOnOutOfMemoryError
# Watch class loading/unloading:
java -Xlog:class+load=info -Xlog:class+unload=info ...
jcmd <pid> VM.metaspace            # detailed Metaspace breakdown
jcmd <pid> GC.class_stats          # per-class metadata sizes (with -XX:+UnlockDiagnosticVMOptions)
```

To investigate: confirm class count is *growing without bound* (via `jcmd VM.metaspace` over time or JFR), take a heap dump, and in Eclipse MAT use **"duplicate classes"** and the classloader-explorer to find a classloader that should have died but is still GC-reachable — then trace the GC root pinning it (often a stray static, a thread, or a `ThreadLocal`).

**`OutOfMemoryError: unable to create new native thread`** is the *other* native OOM: each platform thread reserves ~1 MB of native stack (`-Xss`), so creating tens of thousands of OS threads exhausts native memory / OS limits (`ulimit -u`), even with plenty of Java heap free. Root cause is usually unbounded thread creation or a thread pool with no upper bound. The fixes are *structural* — bound the pool, find the leak (thread dump via `jstack`/`jcmd Thread.print` showing thousands of similarly-named idle threads), and on Java 21 switch I/O-bound thread-per-task designs to **virtual threads** (heap-allocated, not OS threads), which makes "millions of threads" a non-issue. The interview signal is knowing that `-Xmx` is irrelevant to both of these, and that the cure is finding what *retains* classloaders or *creates* threads, not enlarging a pool.

### 🔴 Expert — extended

#### Q52. [Theory] Walk through what happens between the `synchronized` keyword and the CPU — monitors, biased/thin/fat locking, and why `synchronized` can pin virtual threads.

`synchronized` compiles to `monitorenter`/`monitorexit` bytecode (or implicit enter/exit for synchronized methods via a flag), acquiring the object's **monitor**. The lock state lives in the object's **mark word** in its header. HotSpot historically used a lock-inflation ladder to keep the *uncontended* common case cheap: **biased locking** (the mark word records the owning thread; re-entry by that thread needs no atomic op at all) → **thin/lightweight locking** (a CAS installs a pointer to a stack-allocated lock record when a second thread appears but there's no actual contention) → **fat/heavyweight locking** (inflate to a real OS monitor with an OS-level wait queue once threads genuinely block). The point of the ladder is that most locks are never contended, so paying full OS-monitor cost every time would be wasteful.

```
mark word states:  [biased → thread id]  →  [thin → lock-record ptr (CAS)]  →  [inflated → ObjectMonitor*]
                    cheapest                  contended-but-brief                actual blocking (OS park)
```

A modern nuance to raise: **biased locking was deprecated and disabled by default (JDK 15) and removed**, because it became a net loss with cheap modern CAS and complicated the runtime — so today's path is essentially thin → fat. The **virtual-thread interaction** (Q17) is the hot topic: when a virtual thread enters a `synchronized` block and then blocks *inside* it (e.g. on I/O), in the original Java 21 implementation it **pins** to its carrier platform thread (the continuation can't unmount because the monitor is tied to the native stack frame), which can throttle throughput if many virtual threads pin on the same hot lock. The mitigations were "use `ReentrantLock` (a `java.util.concurrent` lock that doesn't pin) on hot paths," and later OpenJDK work (around JDK 24, JEP 491) reworked monitors so `synchronized` no longer pins in most cases. The expert signal is articulating the *uncontended-fast-path* design philosophy and the lock-inflation states, plus the concrete operational consequence for Loom.

#### Q53. [Theory] Compare strong, soft, weak, and phantom references and the reachability model — when does each get collected, and what is each actually for?

Java's `java.lang.ref` package exposes the GC's **reachability levels**, which determine collection order. An object is **strongly reachable** if reachable via any chain of ordinary references — never collected. **Softly reachable** (reachable only through `SoftReference`) — collected only when the JVM is under **memory pressure**, just before OOM; this makes `SoftReference` a *memory-sensitive cache* primitive (but a poor one in practice — the JVM clears them unpredictably and all at once, often hurting more than helping; prefer a real cache like Caffeine with explicit bounds). **Weakly reachable** (`WeakReference`) — collected at the **next GC** once no strong refs remain; the canonical use is *canonicalizing maps / metadata keyed by an object that should not be kept alive by the map itself* — exactly what `WeakHashMap` and the `ThreadLocalMap` keys (Q50) use. **Phantom-reachable** (`PhantomReference`) — the object is already finalized and unreachable; `get()` always returns `null`; you use it purely to receive a **post-mortem notification** (via a `ReferenceQueue`) that it's safe to release an associated native resource — the mechanism behind `Cleaner` (Q41), a safer replacement for `finalize`.

```
strong  → never collected
soft    → collected under memory pressure (≈ before OOM)        → memory-sensitive cache
weak    → collected at next GC when only weakly reachable       → WeakHashMap, ThreadLocal keys, listeners
phantom → enqueued after collection; get() == null              → native-resource cleanup (Cleaner)
       (reachability strength decreases top to bottom)
```

A `ReferenceQueue` is the shared plumbing: you register a `Soft`/`Weak`/`PhantomReference` with a queue, and the GC **enqueues** the reference object after clearing it, so a background thread can react (e.g. evict the now-dead cache entry, free the native handle). The expert-level points: (1) reachability is *transitive and per-strongest-path* — an object reachable both strongly and weakly is strongly reachable; (2) soft references are a frequent *anti-pattern* for caching because you cede eviction policy to the GC; (3) `WeakHashMap` keys are weak but **values are strong**, so a value referencing its key re-creates the leak; (4) phantom + `ReferenceQueue` is the only *deterministically-ordered, resurrection-proof* post-mortem hook, which is why `Cleaner` is built on it rather than on `finalize`.

#### Q54. [Theory] Explain IEEE-754 floating-point pitfalls in Java: why `0.1 + 0.2 != 0.3`, what `NaN` breaks, and when to use `BigDecimal` vs `strictfp`.

`float`/`double` are **IEEE-754 binary** floating point: they represent numbers as `sign × mantissa × 2^exponent`. Many terminating *decimal* fractions (0.1, 0.2, 0.3) have **no finite binary representation**, so they're stored as the nearest representable value with rounding error — which is why `0.1 + 0.2 == 0.30000000000000004` and the equality check fails. The lesson: **never use `==` on floating-point results**; compare within an epsilon, and **never use `double` for money** — use `BigDecimal` (arbitrary-precision decimal) or scaled integers (store cents as `long`). `BigDecimal` must be constructed from a `String` (`new BigDecimal("0.1")`), *not* a `double` (`new BigDecimal(0.1)` captures the binary error), and you must specify a `RoundingMode` for division or it throws `ArithmeticException` on non-terminating quotients.

```java
0.1 + 0.2 == 0.3;                                 // false
Math.abs((0.1 + 0.2) - 0.3) < 1e-9;               // true — epsilon comparison
new BigDecimal("0.1").add(new BigDecimal("0.2")); // 0.3 exactly
new BigDecimal(0.1);                              // 0.1000000000000000055511151231257827021181583404541015625  (trap!)
```

`NaN` ("not a number," e.g. `0.0/0.0`, `Math.sqrt(-1)`) has uniquely treacherous semantics: it is **not equal to anything, including itself** — `Double.NaN == Double.NaN` is `false`, and `NaN < x`, `NaN > x`, `NaN == x` are *all* false. This silently breaks sorting, `min`/`max`, and `equals`-based dedup; use `Double.isNaN(x)` to test, and know that `Double.compare`/`Double.equals` *do* treat `NaN` as equal-to-itself and order it as the largest value (so `TreeSet`/`Arrays.sort` are self-consistent even though raw `<`/`==` aren't). Also note `+0.0` and `-0.0` are `==`-equal but distinguishable via `Double.compare` / `Double.doubleToRawLongBits`. Finally, **`strictfp`**: pre-Java 17 it forced platform-independent, exactly-IEEE-754 intermediate results (otherwise the JIT could use wider 80-bit x87 registers, yielding tiny cross-platform differences); **JEP 306 (Java 17) made all float/double arithmetic strict by default**, so `strictfp` is now a no-op kept only for source compatibility. The expert signal: knowing *why* the representation fails (binary vs decimal), the full `NaN` ordering inconsistency, the `BigDecimal(double)` trap, and that `strictfp` became redundant in 17.

#### Q55. [Theory] What is the constant pool, and how do `ldc`, `invokedynamic`, and string/numeric constants flow from source to runtime?

Every `.class` file has a **constant pool** — a table of symbolic entries (UTF-8 strings, class/method/field references, integer/long/float/double/string constants, method handles, and `invokedynamic` "bootstrap" call-site descriptors). Bytecode instructions don't embed literals inline; they carry a **#index into the constant pool**. This indirection is what lets the JVM resolve symbolic references *lazily* (Q33) and share a single literal across many use sites. `ldc`/`ldc_w`/`ldc2_w` ("load constant") push a pooled constant (a `String`, boxed numeric, `Class`, or `MethodHandle`) onto the operand stack; small `int`s use compact instructions (`iconst_*`, `bipush`, `sipush`) and aren't pool entries.

```
source:  String s = "x";  →  ldc #5            // #5 = String "x" → interned into the pool (Q34)
         Object c = X.class; → ldc #7           // #7 = Class X
         x.foo();          →  invokevirtual #9  // #9 = Methodref Owner.foo:()V
         () -> ...         →  invokedynamic #11  // #11 = bootstrap (LambdaMetafactory)
```

At class load, each constant-pool entry starts *unresolved* and is resolved to a direct reference on first use (then cached) — the JVM's **constant-pool resolution** step. Two features lean heavily on it: **string literals** become interned pool entries (so identical literals across the class are the same object, Q34), and **`invokedynamic`** entries name a *bootstrap method* that the JVM calls once to link a dynamic call site — the foundation for **lambdas** (`LambdaMetafactory`, Q42), modern **string concatenation** (`StringConcatFactory`, Q35), and record `equals`/`hashCode`/`toString` (`ObjectMethods`). The expert points worth making: (1) `javac` performs **constant folding** so `static final int X = 2*3;` is inlined as the literal `6` at *use sites in other classes* — which is the dangerous "constant inlining" gotcha where changing a `public static final` constant requires recompiling *dependents*, not just the defining class; (2) the constant pool is the seam that makes lazy linking, interning, and `invokedynamic`'s pluggable linkage possible; (3) tools like `javap -v` dump the pool, which is how you actually verify what the compiler emitted.

#### Q56. [Theory] How does the modern GC physically organize the heap — generations, TLABs, write barriers, and why allocation is nearly free?

Most JVM GCs are **generational**, built on the **weak generational hypothesis**: most objects die young. The heap is split into a **young generation** (Eden + two survivor spaces, S0/S1) and an **old/tenured generation**. New objects allocate in **Eden**; a **minor GC** copies the few survivors into a survivor space (and ages them), and objects that survive enough collections are **promoted** to old gen. Old gen is collected less often by a more expensive **major/full GC**. This split is why young-gen collection is cheap: it scans only the small live set and the *copying* collector reclaims all of Eden at once.

The reason **allocation itself is nearly free** is the **TLAB (Thread-Local Allocation Buffer)**: each thread gets its own chunk of Eden, and allocating an object is just a **pointer bump** within that chunk (no synchronization, no free-list search) — comparable to stack allocation in cost. Only when a TLAB fills does the thread grab a new one (a rare synchronized op), or fall back to slow-path allocation. This bump-pointer design, combined with a *compacting* young collector, is why "Java allocation is slow" is a myth and why micro-optimizing away small short-lived objects is usually pointless (escape analysis, Q25, often elides them entirely).

```
Young gen:  [ Eden (TLAB | TLAB | TLAB ...) ] [ S0 ] [ S1 ]   ← minor GC: copy survivors, bump-alloc
Old gen:    [ long-lived / promoted objects ]                 ← major GC: mark-sweep-compact / concurrent
            cross-gen refs tracked by the WRITE BARRIER + card table / remembered set
```

The piece that makes generational collection *correct* is the **write barrier**: when application code stores a reference into a field (`a.f = b`), a tiny snippet of JIT-inserted code records *old→young* (and region-crossing) pointers in a **card table** / **remembered set**, so a minor GC can find roots into the young gen **without scanning the entire old gen**. Every modern collector pays this barrier cost: G1 uses it for its per-region remembered sets, and **ZGC/Shenandoah** use **load barriers** (on *reads*) plus colored pointers to relocate objects *concurrently* with the application (Q24), trading some throughput for sub-millisecond pauses. The expert-level synthesis: GC performance is dominated by **allocation rate** and **survivor/promotion behavior**, not heap size alone — so you tune by reducing allocation and right-sizing the young gen, and you reason about pauses in terms of *live-set size* (what's copied/marked), not total heap.

#### Q57. [Theory] Why is enum the right way to do singletons and type-safe constants, and how are enums implemented and made serialization-safe?

An `enum` compiles into a `final class extending java.lang.Enum`, with each constant as a `public static final` instance created in a static initializer — so the JVM's class-initialization lock (Q33) guarantees the constants are created **exactly once, lazily, and thread-safely**. Each constant can have its own body (constant-specific class bodies become anonymous subclasses), fields, and methods, which is why enums model not just values but *behavior* (the "strategy enum" pattern). Because there is exactly one instance per constant per classloader, `==` is the correct, fast comparison, and enums are ideal map keys (`EnumMap` is a dense array indexed by `ordinal()`, far faster than `HashMap`).

```java
enum Operation {
    PLUS("+")  { public int apply(int a, int b) { return a + b; } },
    TIMES("*") { public int apply(int a, int b) { return a * b; } };
    private final String symbol;
    Operation(String s) { this.symbol = s; }
    public abstract int apply(int a, int b);     // each constant supplies its own body
}
```

The **singleton** angle (Effective Java Item 3): a single-constant enum is the *best* singleton implementation because the JVM gives you three guarantees for free that hand-rolled singletons struggle with — **thread-safe lazy instantiation** (class-init lock), **reflection-proofing** (the JVM forbids reflective `Constructor.newInstance` on enums, throwing `IllegalArgumentException`, so an attacker can't fabricate a second instance — unlike a private-constructor singleton, which reflection can crack), and **serialization-safety**. On serialization: enums are special-cased — only the **name** is written, and deserialization resolves it via `Enum.valueOf`, so you can *never* get a duplicate instance from a round-trip (a normal `Serializable` singleton must implement `readResolve()` to avoid creating a second instance, and even then is fragile). The expert points: enums give compile-time exhaustiveness in `switch` (Q38), `values()`/`valueOf()`/`ordinal()`/`name()` for free, `EnumSet` (a bit-vector, extremely fast) and `EnumMap` for collections, and the *caution* that `ordinal()` is positional and brittle — never persist `ordinal()`, persist `name()`, because reordering constants silently changes ordinals.

#### Q58. [Theory] Distinguish method overriding, overloading, and hiding — and explain covariant return types and the role of bridge methods.

These three are constantly confused. **Overriding** is *runtime* polymorphism: a subclass provides a new implementation for an *instance* method with the same signature; dispatch is **dynamic** (the actual object type decides, via `invokevirtual`). **Overloading** is *compile-time*: same method name, *different parameter lists*; the compiler picks the most specific match **statically** from the declared (compile-time) types — which is why overload resolution can surprise you (a `null` argument or autoboxing can select an unexpected overload). **Hiding** applies to `static` methods and fields: a subclass `static` method with the same signature *hides* (doesn't override) the parent's; resolution is **static**, based on the reference type, not the object.

```java
class A { static String who() { return "A"; } String greet() { return "hi A"; } int x = 1; }
class B extends A { static String who() { return "B"; }  // HIDES
                    @Override String greet() { return "hi B"; }  // OVERRIDES
                    int x = 2; }                                  // HIDES the field
A a = new B();
a.greet();   // "hi B"  — overriding: dynamic dispatch on the object
a.who();     // "A"     — hiding: static dispatch on the reference type A
a.x;         // 1       — field access is NOT polymorphic, resolved by reference type
```

**Covariant return types** (since Java 5) relax overriding so an override may return a *subtype* of the parent's return type — essential for fluent APIs and `clone()`/builders (`Object.clone()` returns `Object`, but `MyType.clone()` can return `MyType`). The compiler implements this with a **bridge method**: because the JVM's overriding is signature-exact, `javac` generates a synthetic `Object greet()` bridge in `B` that forwards to the real `String greet()`, preserving polymorphism for callers using the erased/parent signature. **Bridge methods** also appear pervasively from **generics + erasure** (Q7): a `class StringBox implements Comparable<String>` gets a synthetic `compareTo(Object)` bridge that casts and calls `compareTo(String)`. The interview signal is knowing that fields and statics are *not* polymorphic (a frequent trap), that overload resolution is static and the *narrowest applicable* wins, and that bridge methods are the compiler's glue making covariant returns and erased generics interoperate with the JVM's exact-signature dispatch.

#### Q59. [Theory] Why is `Cloneable`/`clone()` considered broken, and what are the correct ways to copy an object?

`Cloneable` is one of the JDK's acknowledged design mistakes. It's a **marker interface with no `clone()` method** — `clone()` lives on `Object` as `protected native`, and `Cloneable` merely flips a switch so `Object.clone()` doesn't throw `CloneNotSupportedException`. So the interface doesn't advertise the capability it's supposed to. Worse, `Object.clone()` performs a **shallow, field-by-field copy that bypasses constructors** (no validation, no final-field initialization semantics), and it returns `Object`, so every implementer must cast (covariant returns mitigate this since Java 5). The truly nasty part is **shallow copy on mutable fields**: the clone shares references to the original's mutable internals (arrays, collections), so mutating one corrupts the other — you must manually deep-copy each mutable field, and `final` fields *can't* be reassigned in `clone()`, fighting immutability.

```java
// The "correct" but awkward Cloneable contract:
class Stack implements Cloneable {
    private Object[] elements; private int size;
    @Override public Stack clone() {
        try {
            Stack r = (Stack) super.clone();   // shallow copy
            r.elements = elements.clone();     // MUST deep-copy mutable fields
            return r;
        } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
    }
}
```

The modern guidance (Effective Java Item 13: *avoid `clone`*): prefer a **copy constructor** or **static copy factory** — `new ArrayList<>(other)`, `Map.copyOf(other)`, or your own `Foo(Foo other)`. They don't rely on a fragile extralinguistic mechanism, work with `final` fields, don't conflict with constructors, can convert between types (a `TreeSet` copy-constructed from a `HashSet`), and let you control shallow-vs-deep explicitly. For deep copies of object graphs, a copy constructor that recursively copies, or serialization-based deep clone (slow, and carries the serialization risks of Q29), are the options. **Records** sidestep all of this — being immutable value carriers, "copying with a change" is done with explicit `with`-style methods or the canonical constructor, no `clone()` needed. The expert points: `clone()` is shallow, constructor-bypassing, and `final`-hostile; copy constructors/factories are the idiomatic replacement; and arrays are the one place `clone()` is genuinely idiomatic (`arr.clone()` is the clean shallow array copy).

#### Q60. [Theory] How do varargs work under the hood, and what are the overload-ambiguity and heap-pollution traps?

A varargs parameter (`T... args`) is pure compiler sugar for a **trailing array parameter** (`T[] args`). At the call site, `javac` **allocates and populates an array** of the arguments, so `String.format("%s %s", a, b)` becomes `format("%s %s", new Object[]{a, b})`. This means (1) every varargs call may allocate an array (a hot-loop cost — which is why performance-sensitive APIs like `EnumSet.of` provide explicit fixed-arity overloads for the common small counts, falling back to varargs only beyond them), and (2) you can pass an existing array directly where varargs is expected.

```java
void log(String fmt, Object... args) { ... }
log("x=%d y=%d", 1, 2);          // compiler wraps: new Object[]{1, 2}
log("no args");                   // passes a zero-length array, NOT null
Object[] arr = {1, 2};
log("x=%d y=%d", arr);            // array passed straight through
log("x=%d", (Object) arr);        // force single-element: {arr}  ← disambiguation
```

The traps interviewers probe: **(1) overload ambiguity** — `null` passed to a varargs parameter is ambiguous (is it the array, or one null element?) and may need a cast; and a fixed-arity overload always beats a varargs one in resolution, which can route calls unexpectedly. **(2) `printf(Object...)` with a single array** prints the array as one argument unless cast — a classic surprise. **(3) Heap pollution + `@SafeVarargs`**: combining varargs with **generics** is unsound because you can't have an array of a parameterized type (Q36) — the compiler creates a `T[]` that's really an erased `Object[]`, so a generic varargs method can leak an ill-typed object and cause a delayed `ClassCastException`; the compiler warns ("possible heap pollution"). You annotate genuinely-safe methods with **`@SafeVarargs`** (only allowed on `static`, `final`, or `private` methods — ones that can't be overridden to break the guarantee) to suppress the warning, and you must ensure the method never **stores into** or **exposes** the varargs array. The signal is understanding varargs is an array, the allocation cost, and the generics-erasure unsoundness that `@SafeVarargs` documents.

#### Q61. [Theory] What is the difference between `Iterator`, `Iterable`, `ListIterator`, and `Spliterator`, and why was `Spliterator` introduced?

These are layers of the iteration abstraction. **`Iterable<T>`** is the "can be iterated" capability — one method, `iterator()` — and it's what the enhanced for-loop (Q37) consumes. **`Iterator<T>`** is the actual cursor: `hasNext()`, `next()`, and an optional `remove()` (the *only* safe way to mutate during iteration). **`ListIterator<T>`** extends it for `List`s with **bidirectional** traversal (`hasPrevious`/`previous`), index access (`nextIndex`), and in-place `set`/`add`. All three are inherently **sequential** — they hand out one element at a time with no notion of splitting.

**`Spliterator<T>`** (Java 8) was introduced specifically to enable **parallel** stream processing, which sequential iterators can't support. Its name = "splittable iterator": besides sequential traversal (`tryAdvance`) and bulk traversal (`forEachRemaining`), it offers **`trySplit()`** — partition the remaining elements into two halves so they can be processed on different threads (the basis of fork/join decomposition, Q45). It also exposes **characteristics** (`SIZED`, `ORDERED`, `SORTED`, `DISTINCT`, `IMMUTABLE`, `CONCURRENT`, `NONNULL`) and `estimateSize()`, which let the stream framework optimize: a `SIZED` + `SUBSIZED` source (array, `ArrayList`) splits cheaply into balanced halves, while an `Iterator`-based or `LinkedList` spliterator splits poorly, making `parallel()` ineffective.

```
Iterable  → iterator()                         (for-each entry point)
Iterator  → hasNext/next/remove                (sequential, one-way)
ListIterator → + previous/set/add/index        (bidirectional, List-only)
Spliterator → tryAdvance + trySplit + characteristics  (splittable → enables PARALLELISM)
```

The interview-grade insight: this is *why* not all sources parallelize well — a stream's performance under `parallel()` is governed by how cheaply and evenly its `Spliterator` splits and whether it knows its size. `Collection.spliterator()`, `Arrays.spliterator()`, and custom `Spliterators` are how you make your own data structures stream-friendly; implementing `trySplit` well (balanced, `SUBSIZED`) is what separates a parallelizable source from a sequential-only one.

#### Q62. [Theory] What exactly happens with `return`/`throw` inside `try`/`catch`/`finally`, and why should `finally` never `return`?

Control flow through `finally` has precise, often-surprising rules. The `finally` block runs **after** the `try`/`catch` computes its outcome but **before** that outcome is delivered to the caller. If `try` executes `return expr`, the **return value is fully evaluated and saved first**, then `finally` runs, then the saved value is returned — so mutating a local in `finally` does *not* change an already-returned primitive (it was copied), though mutating the *object* a returned reference points to *is* visible.

```java
int a() {
    int x = 1;
    try { return x; }          // value 1 is captured NOW
    finally { x = 99; }        // too late — does not affect the returned 1
}   // returns 1

int b() {
    try { return 1; }
    finally { return 2; }      // ABRUPT completion of finally OVERRIDES the try's return
}   // returns 2  — and silently swallows any exception the try would have thrown!
```

The dangerous case is an **abrupt completion in `finally`** — a `return`, `break`, `continue`, or `throw`. It **replaces and discards** whatever the `try`/`catch` was doing, including a pending exception: a `return` or `throw` in `finally` will **silently swallow an exception** propagating out of `try`, erasing the real failure (the JLS calls this the `finally` block completing abruptly). This is exactly the masking problem `try-with-resources` solved with suppressed exceptions (Q39). The discipline: **`finally` is for cleanup only — never `return`/`throw`/`break` from it.** If cleanup itself can throw, either swallow-and-log inside the `finally` or, better, use `AutoCloseable` + try-with-resources so the close exception is *suppressed* rather than masking. The expert signal is knowing the evaluate-then-finally-then-return ordering for values, and that abrupt `finally` completion is a JLS-defined override that hides exceptions — a real-world source of "the error just vanished" bugs.

#### Q63. [Theory] Compare daemon vs user threads, and explain JVM shutdown, shutdown hooks, and why finalizers/`finally` may not run.

Every thread is either a **user (non-daemon)** thread or a **daemon** thread. The rule that governs JVM lifetime: the JVM **exits when the last non-daemon thread terminates** (or when `System.exit`/`Runtime.halt` is called, or a fatal error occurs). Daemon threads — GC threads, the JIT compiler threads, the `Cleaner` thread, timer/heartbeat threads — exist to *serve* user threads and are **abruptly abandoned** at shutdown: they get no chance to finish, and critically **their `finally` blocks do not run**. You set `thread.setDaemon(true)` *before* `start()` (it inherits the creator's daemon status by default); a thread spawned by a daemon is a daemon.

```
JVM stays alive  ⇔ at least one non-daemon thread is running
JVM exits        → run shutdown hooks (in unspecified order, concurrently)
                 → daemon threads killed mid-execution; their finally/cleanup SKIPPED
Runtime.halt()   → immediate, NO hooks, NO cleanup — emergency stop only
```

**Shutdown hooks** (`Runtime.getRuntime().addShutdownHook(thread)`) are the JVM's orderly-shutdown mechanism: registered threads run when the JVM begins shutting down — on normal exit, `System.exit`, or external signals like `SIGTERM`/Ctrl-C (but **not** on `halt()` or a hard kill `SIGKILL`). They run **concurrently and in no guaranteed order**, must be **fast and deadlock-free** (the JVM is tearing down around them), and are where you flush logs, close pools, and persist state. The subtle consequences interviewers want: (1) a forgotten **non-daemon** thread (a thread pool without `shutdown()`, a leaked `Timer`) **prevents the JVM from exiting** — a common "my app won't stop" bug; conversely (2) putting essential cleanup in a **daemon** thread's `finally` is unreliable because that block may never execute. The doctrine: use daemons only for truly disposable background work, always bound and `shutdown()` your executors, do critical cleanup in shutdown hooks or try-with-resources on user threads, and never rely on finalizers (Q41) at shutdown at all.

#### Q64. [Theory] What is the Java Platform Module System (JPMS), what problems does it solve, and why did the ecosystem adopt it so slowly?

JPMS (Project Jigsaw, Java 9) adds a layer *above* packages: a **module** is a named unit declared in `module-info.java` that explicitly states what it **`exports`** (which packages are part of its public API), what it **`requires`** (its module dependencies), and optionally `provides`/`uses` (the `ServiceLoader` mechanism) and `opens` (deep reflective access). The problems it targets are the chronic pains of the flat classpath: **"JAR hell"** (no notion of dependency graph; duplicate/missing/conflicting JARs discovered only at runtime as `NoClassDefFoundError`), **lack of strong encapsulation** (anything `public` was globally accessible, so "internal" packages like `sun.*`/`com.sun.*` were used everywhere despite being unsupported), and a **monolithic, unscalable JDK** (you shipped the whole rt.jar even for a tiny app).

```
module-info.java:
  module com.acme.service {
      requires com.acme.core;            // explicit dependency (no classpath guessing)
      requires transitive java.sql;      // re-export to my consumers
      exports com.acme.service.api;      // public API package
      opens   com.acme.service.dto;      // deep reflection (Jackson/Hibernate) allowed
      provides PaymentProvider with StripeProvider;   // ServiceLoader
  }
```

The payoffs: **reliable configuration** (the module graph is validated at startup — missing/duplicate modules fail *fast*, not deep into a run), **strong encapsulation** (non-exported packages are *inaccessible*, even via reflection unless `opens`, which is what finally locked down `sun.misc.Unsafe` and JDK internals), and **`jlink`** — building a **custom runtime image** containing only the modules your app needs, shrinking footprint dramatically (big for containers). The slow adoption (the part that shows real-world awareness): the entire ecosystem was built on open reflection and the classpath — **Spring, Hibernate, Jackson, Lombok, mocking frameworks** all reflect into your classes, so strong encapsulation *broke* them, requiring `opens`/`--add-opens` (Q27). The **automatic module** + **unnamed module** escape hatches let classpath apps keep working on Java 9+ *without* modularizing, so most applications run on the modular JDK while never writing a `module-info.java` — they get the modular *runtime* benefits without paying the migration cost. The expert framing: JPMS succeeded *inside the JDK* (clean modularization, `jlink`, sealed internals) but is *optional and lightly adopted in application code*, and the reflective-access friction is exactly why the Java-8→17+ migration's #1 task is fixing `--add-opens` (Q27).

#### Q65. [Theory] How does `Object.hashCode()` work (identity hash, the `equals`/`hashCode` link), and what makes a *good* hash distribution in `HashMap`?

`Object.hashCode()` returns an `int` used to bucket objects in hash-based collections. The default `Object` implementation is the **identity hash code** — *not* the memory address (a common myth; the JVM may move objects during GC). It's computed lazily on first request and **stored in the object header's mark word** (Q52), so it's stable for the object's lifetime even across relocation; modern JVMs derive it from a per-thread PRNG (`-XX:hashCode=`). The `equals`/`hashCode` contract (Q1) is the load-bearing rule: **equal objects must have equal hash codes**, or hash collections silently break — but the converse is allowed, so unequal objects *may* collide.

Inside `HashMap`, the key's `hashCode()` is **not** used directly. The map applies a **spreading function** — `h ^ (h >>> 16)` — that XORs the high 16 bits down into the low bits, then indexes the bucket with `(n - 1) & hash` (a fast mask, since capacity `n` is always a power of two). This spreading matters because the low-order bits dominate the bucket index, and many real hash codes vary mostly in their *high* bits (e.g. `Float.floatToIntBits`, or hash codes built by left-shifting); without spreading, those keys would all collide in a few buckets, degrading the map from O(1) toward O(n) (or O(log n) after treeification, Q21).

```
key.hashCode() = h
spread:  h ^= (h >>> 16)          // mix high bits into low bits
bucket:  index = (capacity - 1) & h   // power-of-two mask, not modulo
```

What makes a **good** `hashCode`: it should (1) be **consistent with `equals`** (use exactly the fields used in `equals`, no more), (2) **distribute uniformly** so different objects rarely collide — the `Objects.hash(...)` / `31 * h + field` (`String`'s polynomial) pattern achieves this with the odd prime 31 (cheap as `(h << 5) - h`, and odd to avoid information loss on overflow), (3) be **fast** (it's on every map operation), and (4) **never depend on mutable fields used as keys** — mutating a key after insertion makes it unfindable (it hashes to a different bucket), a classic "lost entry" bug. The anti-patterns: a constant `hashCode` (legal but turns the map into a linked list / tree — O(n)/O(log n)), and using too few fields so distinct logical objects collide. The expert signal: identity hash is header-stored and GC-stable (not the address), `HashMap` *spreads* before masking and relies on power-of-two capacity, and poor distribution — not the data structure — is usually why a "fast" `HashMap` is slow.

## 🧩 Extended Questions — Set 2: Practical, Operational & Troubleshooting

### 🟢 Basic — extended

#### Q66. [Practical] A teammate reports `NullPointerException` with no message in production. How do helpful NPE messages (JEP 358) help, and how do you turn them on?

The classic `NullPointerException: null` on a line like `a.getB().getC().getD()` is maddening because the line has three dereferences and the stack trace tells you nothing about *which* one was null. **Helpful NullPointerExceptions** (JEP 358, shipped in Java 14 and **on by default since Java 15**) fix this: the JVM reconstructs the failing expression from the bytecode and tells you exactly which variable or call returned null.

```
// Before (Java 8):
Exception in thread "main" java.lang.NullPointerException
        at com.acme.Order.total(Order.java:42)

// After (Java 15+, default):
Exception in thread "main" java.lang.NullPointerException:
        Cannot invoke "Item.price()" because the return value of
        "java.util.List.get(int)" is null
        at com.acme.Order.total(Order.java:42)
```

On Java 14 you opt in with `-XX:+ShowCodeDetailsInExceptionMessages`; from 15 on it's automatic and you'd only disable it if you were worried about leaking variable names into logs that reach untrusted eyes (rarely a real concern). The practical workflow: read the *because* clause — it names the exact sub-expression. The deeper lesson for an interview is that this is a *diagnosis* feature, not a fix — the real remedy is designing the null out (return `Optional` or an empty collection, validate at boundaries with `Objects.requireNonNull(x, "msg")` so the NPE fires at the *source* with a clear message rather than three frames later).

#### Q67. [Practical] What is the difference between `-Xmx`, `-Xms`, `-Xss`, and `-XX:MaxMetaspaceSize`, and how would you size them for a typical microservice?

These are the four heap/stack/metaspace knobs you tune most. `-Xmx` is the **maximum heap** (the cap on object storage); `-Xms` is the **initial/minimum heap**. `-Xss` is the **per-thread stack size** (default ~512 KB–1 MB; controls how deep recursion can go before `StackOverflowError`, and multiplied across thousands of threads it's real memory). `-XX:MaxMetaspaceSize` caps **Metaspace** (class metadata — *not* on the heap; native memory). Crucially, none of these is the *total* process footprint: that's heap + Metaspace + thread stacks + code cache + GC structures + direct/native buffers.

```bash
# Typical Spring Boot microservice in a 2 GB container:
java -Xms1g -Xmx1g \           # set Xms == Xmx to avoid heap-resize pauses + fragmentation
     -Xss512k \                 # smaller stacks → more threads fit
     -XX:MaxMetaspaceSize=256m \
     -XX:MaxDirectMemorySize=256m \
     -jar app.jar
```

The senior move is **`-Xms == -Xmx`** for server apps: it pre-commits the heap so the JVM never pauses to grow it under load, and it surfaces memory problems at startup instead of at peak traffic. Leave headroom *outside* the heap — a 2 GB container with `-Xmx2g` will get OOM-killed by the kernel because the JVM's non-heap usage pushes the RSS over the limit. The modern, container-aware alternative is **`-XX:MaxRAMPercentage`** (e.g. `=75.0`) which sizes the heap as a fraction of the *detected container limit* rather than a hardcoded number, so the same image works across pod sizes.

#### Q68. [Practical] How do you capture and read a thread dump to diagnose a "the app is hung / stuck" report?

A thread dump is a point-in-time snapshot of every thread's stack and state — the first tool for "hung," "slow," or "100% CPU" symptoms. Capture it without restarting the process:

```bash
jstack <pid>                       # prints to stdout (preferred)
jcmd <pid> Thread.print             # modern equivalent, more reliable
kill -3 <pid>                       # sends SIGQUIT → dump goes to the app's stdout/console
# Take 3 dumps ~5s apart so you can see what's MOVING vs STUCK.
```

Read it by thread **state**: `RUNNABLE` threads burning CPU (look for the same stack across all three dumps = a hot loop or a blocking native call), `BLOCKED` threads waiting on a monitor (`- waiting to lock <0x...>` — find who *holds* that lock: `- locked <0x...>`), and `WAITING`/`TIMED_WAITING` threads (usually fine — idle pool threads, `park`). The JVM **automatically detects and prints deadlocks** at the bottom of the dump (`Found one Java-level deadlock`), giving you the cycle for free.

```
"http-nio-8080-exec-3" #34 BLOCKED
   java.lang.Thread.State: BLOCKED (on object monitor)
        at com.acme.Ledger.debit(Ledger.java:88)
        - waiting to lock <0x000000076ab1> (a com.acme.Account)   ← wants this
        - locked <0x000000076ab2> (a com.acme.Account)            ← holds this
```

The diagnostic pattern: many threads `BLOCKED` on one monitor → lock contention (one slow critical section serializing everyone); many `RUNNABLE` in the same app frame → a hot loop or runaway regex; many `WAITING` on a connection-pool `getConnection` → exhausted DB/HTTP pool (the real bug is usually downstream, not in Java). With virtual threads (Java 21) use `jcmd <pid> Thread.dump_to_file -format=json <file>` to dump millions of virtual threads, which the legacy `jstack` text format can't handle gracefully.

### 🟡 Intermediate — extended

#### Q69. [Practical] Walk through diagnosing a sudden CPU spike to 100% in a running JVM, step by step.

The reliable, OS-level recipe correlates a hot **OS thread** to a Java stack — no guessing. On Linux:

```bash
# 1. Find the JVM process burning CPU:
top                                  # note the PID, say 4242
# 2. Find the hottest THREAD inside it (per-thread CPU):
top -H -p 4242                       # note the native thread id (LWP/TID), say 4271
# 3. Convert the decimal TID to hex:
printf '%x\n' 4271                   # -> 10af
# 4. Take a thread dump and grep for that nid:
jstack 4242 | grep -A30 'nid=0x10af'
```

The `nid` in the thread dump is the OS thread id in hex, so you land directly on the offending stack. Repeat across two or three dumps to confirm it's *the same* code path, not a transient. Common culprits: an infinite/tight loop, the **Java 7 `HashMap` resize cycle** (concurrent `put` → spinning `get`, see Q21), catastrophic regex backtracking on attacker-controlled input (ReDoS), a busy-wait/spin instead of a proper `wait`/blocking call, or — deceptively — **GC itself** (if the hot threads are `GC Thread#N`, the problem is allocation pressure, not your code). For a lower-overhead, sampling approach in production, attach **async-profiler** (`./profiler.sh -d 30 -f flame.html <pid>`) or start a **JFR** recording; a flame graph shows the hot path proportionally without the manual TID dance.

#### Q70. [Practical] How do you read a GC log, and what numbers tell you the GC is healthy vs in trouble?

Turn on unified GC logging (Java 9+ syntax) and look at *occupancy after collection*, *pause times*, and *frequency*:

```bash
-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=5,filesize=20m
```

```
[2.471s] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 512M->48M(1024M) 6.231ms
                                                            ^^^^  ^^^       ^^^^^^^
                                                  before -> after (heap)   pause
```

The health signals: **(1) Does old-gen occupancy after a Full/Mixed GC keep climbing across hours?** If post-collection live set trends up, that's a *memory leak* signature (Q23) — healthy apps plateau. **(2) Pause times** — are they within your SLO (G1 tries to hit `-XX:MaxGCPauseMillis`, default 200ms)? Rising pauses mean a growing live set or fragmentation. **(3) GC frequency / allocation rate** — collecting every few seconds means high allocation churn; the fix is usually fewer allocations (object reuse, primitive streams) rather than a bigger heap. **(4) "Full GC" events** on G1/ZGC are alarm bells — they mean concurrent collection couldn't keep up (allocation outran the collector), causing a long stop-the-world fallback. The **"GC overhead limit exceeded"** `OutOfMemoryError` means the JVM spent >98% of time in GC reclaiming <2% of heap — effectively a leak or undersized heap. The mature read: don't tune flags blindly — a high *allocation rate* is the root cause of most GC pain, and that's a code problem.

#### Q71. [Practical] A `for` loop was rewritten as a `parallelStream()` and now production is slower and occasionally wrong. What happened?

`parallelStream()` looks like a free speedup but has three production traps that this scenario hits. **First, the common pool**: parallel streams run on the *shared* `ForkJoinPool.commonPool()`, sized to `#cores - 1`. A long-running or blocking parallel stream (e.g. one doing I/O per element) **monopolizes the pool for the entire JVM**, starving every other parallel stream and `CompletableFuture` that defaults to it — one bad stream degrades unrelated subsystems. Parallel streams are for **CPU-bound, splittable, large** workloads only; never put blocking I/O in them.

**Second, "slower"**: parallelism has fixed overhead (splitting the spliterator, forking tasks, merging results). For small collections or cheap per-element work, that overhead dwarfs any gain — and `LinkedList`, `Iterator`-based, or `HashMap`-keyset sources split poorly (uneven work distribution), so you pay the cost without the benefit. `ArrayList`, arrays, and `IntStream.range` split cleanly; most else doesn't.

**Third, "wrong"**: the stream pipeline almost certainly has a side effect that isn't thread-safe.

```java
List<Result> out = new ArrayList<>();              // NOT thread-safe
items.parallelStream().forEach(i -> out.add(f(i))); // RACE: lost/corrupted writes, even ArrayIndexOutOfBounds

// Correct: no shared mutable state — collect into the result:
List<Result> out = items.parallelStream().map(this::f).collect(Collectors.toList());
```

The fix here is almost always to revert to the sequential stream or plain loop unless you can *prove* (with JMH, under realistic load) the workload is large, CPU-bound, and stateless. If you genuinely need bounded parallelism with custom sizing, submit the parallel stream inside your *own* `ForkJoinPool` (`myPool.submit(() -> stream.parallel()...).get()`) so it doesn't touch the common pool — but on Java 21, a virtual-thread executor with structured concurrency is usually the cleaner answer for I/O fan-out.

#### Q72. [Practical] Logs show garbled characters like `Ã©` instead of `é`, and a CSV that parsed fine on your laptop fails on the Linux server. What's the root cause and the durable fix?

This is the **platform-default-charset bug**: APIs like `new String(bytes)`, `new FileReader(file)`, `String.getBytes()`, `PrintStream`, and `InputStreamReader` *without an explicit charset* use `Charset.defaultCharset()`, which historically depended on the OS locale (`Cp1252` on a Windows dev box, `UTF-8` or `ANSI_X3.4` on a server). So a file written as UTF-8 gets read as Windows-1252 — `é` (UTF-8 bytes `C3 A9`) is decoded as two Latin-1 chars `Ã©` (mojibake). It "works on my machine" precisely because both ends happened to default to the same charset there.

```java
// FRAGILE — encoding depends on the machine's locale:
String s = new String(bytes);
Files.readAllLines(path);                    // pre-18 used default charset

// CORRECT — always specify the charset explicitly:
String s = new String(bytes, StandardCharsets.UTF_8);
Files.readString(path, StandardCharsets.UTF_8);
new InputStreamReader(in, StandardCharsets.UTF_8);
```

**JEP 400 (Java 18) made UTF-8 the default charset everywhere**, which eliminates most of this class of bug for new code — but you still hit it on Java 8/11/17 services and when reading files produced by other systems. The durable fixes: (1) **always pass an explicit `Charset`** to every reader/writer/`getBytes`/`new String` — make it a lint/review rule; (2) as a defensive runtime measure on older JVMs, set `-Dfile.encoding=UTF-8` and `-Dsun.jnu.encoding=UTF-8` (the latter governs file *names*); (3) standardize on UTF-8 end to end. The interview signal is knowing the default charset was machine-dependent before 18, that `file.encoding` is a band-aid not a fix, and that explicit charsets are the only reliable answer.

#### Q73. [Practical] A scheduled job fires at the wrong hour twice a year, and timestamps stored in the DB are off by hours for some users. How do you fix date/time handling?

These are the two canonical time bugs, and both come from conflating *instants* with *local time* and from using the broken legacy API. The "wrong hour twice a year" bug is **daylight saving time**: a job scheduled as "every day at 02:30 local" either runs twice or skips on DST transition days, and arithmetic like `instant.plus(24, HOURS)` is **not** the same as "same wall-clock time tomorrow" when a DST boundary sits in between. The "off by hours" bug is storing a `LocalDateTime` (which has *no zone*) and later interpreting it in a different zone, or using `java.util.Date`/`Calendar`/`SimpleDateFormat` (mutable, zone-confused, and `SimpleDateFormat` is not thread-safe — a classic concurrent-corruption source).

```java
// Machine timestamps → always store an INSTANT (UTC), zone-agnostic:
Instant now = Instant.now();                 // persist this; DB column TIMESTAMP WITH TIME ZONE

// "Same wall time tomorrow" must use zoned arithmetic, NOT plus(24h):
ZonedDateTime next = ZonedDateTime.now(ZoneId.of("Europe/Paris"))
        .plusDays(1)                          // DST-aware: keeps 02:30 local correctly
        .withHour(2).withMinute(30);

// Format with the IMMUTABLE, thread-safe formatter (never SimpleDateFormat):
DateTimeFormatter.ISO_INSTANT.format(now);
```

The durable rules: **store and transmit instants in UTC** (`Instant`, or UTC `OffsetDateTime`), convert to the user's `ZoneId` only at the display edge; use `ZonedDateTime` arithmetic for any "wall clock" recurrence so DST is handled by the `java.time` rules engine (backed by the IANA tz database); keep the JVM/container tz data current (`tzdb` updates ship with JDK releases — stale tz data causes its own off-by-an-hour bugs); and ban `Date`/`Calendar`/`SimpleDateFormat` from new code. For scheduling specifically, prefer a scheduler that's explicitly DST-aware (Quartz with a defined time zone) rather than naive `Timer`/fixed-delay millisecond math.

#### Q74. [Coding] Your service intermittently throws `Too many open files` / leaks DB connections. Show the resource-handling bug and the fix.

The symptom — file-descriptor exhaustion or a connection pool that drains and never recovers — almost always traces to a resource closed on the happy path but **leaked on the exception path**, or closed in the wrong order. The pre-Java-7 idiom is error-prone; manual `finally` blocks routinely get this wrong (closing in the wrong order, or the close itself throwing and masking the original exception).

```java
// LEAK: if process() throws, conn/stmt/rs are never closed → pool exhaustion under load.
Connection conn = dataSource.getConnection();
PreparedStatement st = conn.prepareStatement(SQL);
ResultSet rs = st.executeQuery();
process(rs);                 // throws → fd/connection leaked
rs.close(); st.close(); conn.close();
```

```java
// FIX: try-with-resources closes in REVERSE order, even on exception, and
// suppresses (does not mask) the primary exception. (See Q39 for the desugaring.)
try (Connection conn = dataSource.getConnection();
     PreparedStatement st = conn.prepareStatement(SQL)) {
    st.setLong(1, userId);
    try (ResultSet rs = st.executeQuery()) {
        process(rs);
    }
}   // rs, then st, then conn closed automatically — guaranteed
```

The investigation tooling matters as much as the fix: confirm the leak with `lsof -p <pid> | wc -l` (count fds) or `jcmd <pid> VM.native_memory` and the pool's own metrics (HikariCP exposes `active`/`idle`/`pending` and will log `Connection leak detection` if you set `leakDetectionThreshold`). The structural lessons: **every `AutoCloseable` belongs in try-with-resources**; never share a `Connection` across threads; size the pool deliberately (a pool that's *too big* can be worse — it exhausts the DB's own connection limit); and raise the OS `ulimit -n` only after ruling out an actual leak, because raising the limit on a real leak just delays the crash. A leak that only appears "under load" or "after a downstream timeout" is the tell that the close is on a path your tests don't exercise.

#### Q75. [Practical] You changed `equals()`/`hashCode()` on a class used as a map key (or made a key field mutable). What breaks in production and how do you catch it?

Two related disasters. **(1) Mutating a key after it's in a `HashMap`/`HashSet`**: the entry was filed in a bucket based on the *old* `hashCode`; after mutation the key hashes to a *different* bucket, so `map.get(key)` and `map.containsKey(key)` return null/false even though the entry is physically still in the map — a "lost entry" that's invisible until someone notices data vanishing. **(2) Changing the `equals`/`hashCode` *definition*** (adding/removing a field) across a deploy when the keys are persisted/serialized/cached: entries written by the old code can no longer be found by the new code, silently doubling cache sizes or losing lookups.

```java
// TRAP: id is part of equals/hashCode, then someone "fixes" the id:
Map<Account, BigDecimal> balances = new HashMap<>();
Account a = new Account(1);
balances.put(a, TEN);
a.setId(2);                       // mutates a hashCode-relevant field
balances.get(a);                  // null — entry is orphaned in the old bucket
balances.size();                  // still 1, but unreachable by key
```

The fixes are structural: **make map/set keys immutable** (records are perfect — final fields, auto-correct `equals`/`hashCode`), or if a field must change, *remove the entry, mutate, re-insert*. For the cross-deploy case, never let `hashCode`/`equals` semantics drift for persisted or distributed keys — and remember `hashCode` is explicitly **not** guaranteed stable across JVM runs for things like enums' default or identity hashes, so never serialize a hash. Catch it in code review (flag any setter on a class used as a key, and any change to an `equals`/`hashCode` method) and with tests that round-trip keys through the actual collection. This connects to Q1 and Q65: the `equals`/`hashCode` contract isn't academic — violating it produces *silent* data loss, the worst kind of bug.

#### Q76. [Practical] How do you decide which collection or stream operation allocates, and how would you reduce GC pressure in a hot path?

Allocation rate — not heap size — drives GC frequency, so in a genuinely hot path you reason about what each operation allocates. **Boxing** is the biggest hidden cost: `Map<Integer,Long>`, `List<Integer>`, and `stream().reduce()` over wrappers allocate a wrapper object per value; `Stream<Integer>` boxes where `IntStream` would not. **Iterators** allocate (every enhanced-for over a `Collection` creates an `Iterator` object — usually fine, but in a million-times-per-second loop it shows up). **Streams** allocate the pipeline objects and lambdas-capturing-state; **lambdas that capture** allocate a closure object, while non-capturing lambdas are cached. Defensive copies, `String` concatenation in loops (Q35), and `Collectors.toList()` (resizing) all churn.

```java
// Allocation-heavy hot loop (boxing + stream overhead per call):
long sum = list.stream().map(x -> x * 2).reduce(0, Integer::sum);   // boxes every element

// Lean: primitive stream — no boxing; or a plain indexed loop for the hottest paths:
long sum = arr.length == 0 ? 0 : IntStream.of(arr).mapToLong(x -> x * 2L).sum();
for (int i = 0, n = arr.length; i < n; i++) sum += arr[i] * 2L;     // zero allocation
```

The pragmatic stance: **measure first** (JFR's "Allocation" event or `-Xlog:gc*` allocation rate tells you if it even matters), and only then optimize the proven hot spot — most code should stay readable with streams. When it *does* matter: use primitive collections (`int[]`, `IntStream`, or a library like Eclipse Collections / fastutil for `IntList`/`Long2ObjectMap`), pre-size collections (`new ArrayList<>(expectedSize)`, `new HashMap<>(n, 0.75f)`) to avoid resize-copy churn, reuse buffers/`StringBuilder`, and prefer iteration over stream creation in the tightest loops. Beware premature optimization — the JIT's escape analysis (Q25) already elides many short-lived allocations, so "this allocates" is a hypothesis to verify, not a law.

#### Q77. [Practical] `OutOfMemoryError: Direct buffer memory` (or growing off-heap/RSS while heap looks fine) — how do you diagnose off-heap memory?

This is the "heap is healthy but the process RSS keeps growing / gets OOM-killed" puzzle, and the answer is that the heap is only one of several memory regions. **Direct `ByteBuffer`s** (`ByteBuffer.allocateDirect`, used heavily by NIO, Netty, and many DB/HTTP clients) live in *native* memory, capped by `-XX:MaxDirectMemorySize` (defaults to `-Xmx`), and are only freed when their backing objects are GC'd via a `Cleaner` — so a slow GC or a buffer pool that never releases leaks native memory while the heap looks fine. Other off-heap consumers: **Metaspace** (class metadata — leaks via classloader churn, Q51), **thread stacks** (`#threads × -Xss`), the **JIT code cache**, GC bookkeeping, and native libraries (mapped files, JNI).

```bash
# Turn on Native Memory Tracking (small overhead) and read the breakdown:
java -XX:NativeMemoryTracking=summary -jar app.jar
jcmd <pid> VM.native_memory summary           # categories: Java Heap, Class, Thread,
                                              # Code, GC, Internal, Other...
jcmd <pid> VM.native_memory summary.diff      # delta vs a baseline → spot the grower
```

The workflow: rule out the heap with GC logs, then `VM.native_memory summary` to see *which* native category is growing. If it's "Internal"/direct buffers, look for unbounded buffer pools (cap `-XX:MaxDirectMemorySize` so you fail *fast* with a clear error instead of getting silently OOM-killed by the kernel), Netty's `PooledByteBufAllocator` leak detector (`-Dio.netty.leakDetection.level=paranoid`), and unclosed channels. If it's "Class"/Metaspace, you have a classloader leak (Q51). The container angle: the kernel OOM-killer (exit 137 / `dmesg` "Killed process") doesn't care about `-Xmx`; it kills on total RSS, so leave non-heap headroom and prefer `-XX:MaxRAMPercentage` (Q67) so the JVM accounts for the whole budget, not just the heap.

### 🟠 Advanced — extended

#### Q78. [Practical] How do you capture, configure, and read a Java Flight Recorder (JFR) profile in production, and why is it the preferred profiler?

JFR is a built-in, **always-can-be-on, ~1% overhead** event recorder baked into the JVM (free and open since Java 11), which makes it the production profiler of choice — unlike sampling profilers that require attaching agents or restarts, JFR can run continuously and you grab the last N minutes when an incident happens.

```bash
# Start a time-boxed recording on a running process:
jcmd <pid> JFR.start name=diag settings=profile duration=120s filename=diag.jfr
jcmd <pid> JFR.dump name=diag filename=now.jfr      # snapshot an ongoing recording
jcmd <pid> JFR.stop name=diag

# Or run a continuous in-memory ring buffer and dump on demand / on OOM:
java -XX:StartFlightRecording=disk=true,maxage=10m,settings=profile -jar app.jar
```

Analyze the `.jfr` in **JDK Mission Control (JMC)** or `jfr print`/`jfr summary` on the CLI. What it captures that a thread dump can't: **allocation profiling** (which call sites allocate the most → drives GC pressure, Q76), **CPU hot methods** (sampled flame graph without the manual TID dance of Q69), **lock contention and monitor blocked time**, **GC pauses with causes**, **I/O and socket latency**, and **exception/error rates**. The two built-in settings are `default` (~1% overhead, safe for prod) and `profile` (more detail, slightly more overhead). The senior practice: ship every service with a continuous JFR ring buffer so that when an incident fires you already have the evidence — diagnosing a transient spike *after* it's gone is otherwise nearly impossible. Pair it with async-profiler when you need native/kernel frames (JFR is JVM-only).

#### Q79. [Practical] A service is fast after warm-up but its first thousand requests are slow and a canary deploy briefly spikes latency. Explain and mitigate JIT warm-up.

This is **JIT warm-up** (Q49): the JVM starts in the interpreter, profiles execution, then C1- and C2-compiles hot methods. Until a method is compiled and inlined, it runs interpreted — often **10–50× slower** — so the first requests after every deploy, restart, or scale-out event pay this tax, which is exactly when a canary or rolling deploy routes real traffic to a cold JVM and p99 spikes. It's also why naive benchmarks lie (Q25 — always JMH) and why autoscaling that spins up cold pods under a load spike can make a latency problem *worse* before it gets better.

Mitigations, roughly in order of leverage:

```bash
# 1. WARM IT BEFORE TRAFFIC: replay synthetic representative requests at startup,
#    in the readiness probe, so the pod only goes "ready" after the hot paths compile.
# 2. AppCDS / CDS — share & pre-load class metadata to cut class-loading time:
java -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=app.jsa -jar app.jar
# 3. Project Leyden / CRaC (checkpoint-restore) where available: restore an
#    already-warmed JVM image so it starts hot.
# 4. Tiered compilation is on by default; -XX:TieredStopAtLevel=1 trades peak
#    throughput for FAST warm-up (good for short-lived CLIs/functions, NOT servers).
```

The operational fixes that matter most: a **warm-up routine wired into the readiness/health check** (so load balancers don't send traffic to a cold instance), **slow-start / surge control** in the load balancer or service mesh to ramp traffic onto fresh pods, and **smaller, more frequent deploys with proper canary weighting** so warm-up cost is amortized. For workloads where warm-up dominates total runtime (serverless, CLIs, batch shells), consider **GraalVM native-image** (AOT-compiled, no warm-up, but no peak-throughput JIT and different GC/reflection trade-offs) — it's the opposite end of the trade-off curve from a long-lived, JIT-optimized server.

#### Q80. [Practical] Your Java app runs fine on a VM but gets OOM-killed or uses the wrong number of threads inside a container. What are the container-awareness pitfalls?

Pre-JDK-10, the JVM read the *host's* CPU and memory (`/proc/cpuinfo`, total RAM), not the container's cgroup limits — so a JVM in a 2-core, 1 GB pod on a 64-core, 256 GB host would size its GC threads, common `ForkJoinPool`, and default heap as if it owned the whole box. That meant absurd thread counts (`availableProcessors()` returned 64) and a default heap (`1/4` of *host* RAM = 64 GB) far larger than the cgroup limit → instant kernel OOM-kill (exit 137). This was the #1 "works on the VM, dies in Kubernetes" bug.

```bash
# Modern JDKs (10+, fully solid in 11/17/21) are cgroup-aware by default:
#   - Runtime.availableProcessors() honors the CPU quota
#   - default heap honors the memory limit
# Verify what the JVM actually sees inside the container:
java -Xlog:os+container=trace -version          # prints detected cgroup limits
jcmd <pid> VM.flags | grep -i maxheap
```

The durable practices: **set the heap as a percentage of the container limit**, not a hardcoded number, so the same image works across pod sizes — `-XX:MaxRAMPercentage=75.0` (leaving 25% for Metaspace, stacks, direct buffers, and the kernel, per Q77); confirm CPU detection (`-XX:ActiveProcessorCount=N` to override if the cgroup *quota* vs *shares* distinction misleads the JVM — fractional CPU limits like `0.5` round to 1 and can starve GC threads); and remember **cgroup v1 vs v2** differences caused detection bugs in older minor versions, so pin a recent JDK. Also size connection pools and `Executors.newFixedThreadPool(availableProcessors())` *after* verifying the JVM sees the right CPU count, or you'll under- or over-provision threads. The interview signal: know that `-Xmx2g` in a 2 GB container is wrong (no non-heap headroom), and that percentage-based sizing plus verifying detected limits is the correct posture.

#### Q81. [Practical] You inherit `NoSuchMethodError`/`NoClassDefFoundError`/`LinkageError` at runtime on code that compiled fine. How do you resolve "classpath/dependency hell"?

These errors are the signature of a **compile-time vs runtime version mismatch**: the code compiled against one version of a class but a *different* version is on the runtime classpath. `NoSuchMethodError` means "the method existed when I compiled but isn't in the class that actually loaded" — almost always **two versions of the same library** on the classpath and the loader picked the wrong one (a transitive dependency dragged in an older/newer copy). `NoClassDefFoundError` means the class was present at compile time but failed to load at runtime (missing jar, or its static initializer threw — distinct from `ClassNotFoundException`, which is a reflective lookup miss). `LinkageError`/`ClassCastException: X cannot be cast to X` (same name!) means **the same class loaded by two different classloaders**.

```bash
# See the actual resolved dependency tree and spot conflicts:
mvn dependency:tree -Dverbose -Dincludes=com.google.guava   # Maven
./gradlew dependencies --configuration runtimeClasspath     # Gradle
./gradlew dependencyInsight --dependency guava              # who pulled it & why

# Find which jar a class actually loads from at runtime:
java -verbose:class -jar app.jar | grep -i 'SomeClass'
jcmd <pid> VM.class_hierarchy SomeClass
```

The resolution toolkit: **`mvn dependency:tree`/`gradle dependencies`** to find the duplicate, then pin one version via `<dependencyManagement>` / Gradle resolution strategy, or `<exclusion>` the transitive offender; use a **BOM** (Spring Boot's `dependencyManagement` BOM) to align versions across the graph; run the **Maven Enforcer plugin** (`requireUpperBoundDeps`/`dependencyConvergence`) to *fail the build* on conflicts instead of discovering them in prod. For truly intractable conflicts (two libs each *needing* incompatible versions), **shade/relocate** one (Maven Shade `relocate`) so it lives under a renamed package. The classloader-duplicate variant shows up in app servers/plugins/OSGi — the fix is understanding which loader owns the class and not putting the same jar in two layers. The prevention mindset: lock the dependency graph, fail builds on convergence violations, and treat "it compiled" as no guarantee about what loads.

#### Q82. [Practical] How do you find and resolve a deadlock, and how would you prevent them by design?

A deadlock is a cycle of threads each holding a lock the next one needs. **Detection is free**: the JVM scans for monitor cycles and prints `Found one Java-level deadlock` at the bottom of every thread dump (Q68), naming the threads, the locks each holds, and the lock each wants — so the diagnosis is "take a thread dump, read the deadlock section." `jcmd <pid> Thread.print` or JConsole's "Detect Deadlock" button surface it instantly; what they *can't* show is a **livelock** (threads busy but making no progress) or a **`ReentrantLock` deadlock via `tryLock` mis-use** without timeouts.

```
Found one Java-level deadlock:
"T1": waiting to lock <0xA> (Account), which is held by "T2"
"T2": waiting to lock <0xB> (Account), which is held by "T1"     ← the cycle
```

```java
// CAUSE: two threads lock the same pair in opposite order.
void transfer(Account from, Account to, BigDecimal amt) {
    synchronized (from) { synchronized (to) { ... } }   // T1: A then B; T2: B then A → deadlock
}
// FIX 1 — global lock ordering: always acquire by a stable, total order (e.g. account id):
Account first  = from.id() < to.id() ? from : to;
Account second = from.id() < to.id() ? to   : from;
synchronized (first) { synchronized (second) { ... } }   // no cycle possible
```

Prevention by design, in order of preference: **(1) don't hold multiple locks** — restructure so only one lock is held at a time, or use a single coarser lock if contention allows. **(2) Lock ordering** — impose a global total order on lock acquisition (the canonical fix above) so a cycle is impossible. **(3) `tryLock` with timeout** (`ReentrantLock.tryLock(t, unit)`) — back off and retry instead of blocking forever, breaking potential cycles at the cost of livelock risk and retry logic. **(4) Higher-level constructs** — `java.util.concurrent` (`ConcurrentHashMap`, `BlockingQueue`, `Semaphore`, `StampedLock`) and immutable/message-passing designs sidestep explicit locking entirely. On Java 21, prefer `ReentrantLock` over `synchronized` in hot paths anyway, since `synchronized` can pin virtual threads (Q17/Q52). The expert framing: deadlocks are a *design* defect (inconsistent lock ordering), not just a runtime accident — the fix lives in the locking discipline, not in catching the symptom.

#### Q83. [Practical] After upgrading a library, behavior changed even though your code didn't. How do `--add-opens`, `--add-exports`, and the strong encapsulation of JPMS cause runtime breakage, and how do you fix it?

Since the module system (JPMS, Java 9, Q64) and especially since **Java 16 (JEP 396) made strong encapsulation the default** and **Java 17 removed the `--illegal-access` escape hatch**, reflective access into JDK *internals* (`sun.*`, `jdk.internal.*`, and non-exported `java.*` package members) is **blocked at runtime** with `InaccessibleObjectException` rather than merely warned about. Frameworks that reflectively poke JDK internals — older Hibernate, Lombok, Mockito/ByteBuddy, serialization libs, Spring (in spots), and anything calling `setAccessible(true)` on JDK classes — break on 16/17+ even though your application code is untouched. This is the most common "runtime upgrade broke everything" surprise (Q27).

```bash
# Symptom:
# java.lang.reflect.InaccessibleObjectException: Unable to make field private ...
#   accessible: module java.base does not "opens java.lang" to unnamed module

# Bridge (temporary): open/export a package to the reflecting code at launch:
java --add-opens java.base/java.lang=ALL-UNNAMED \      # deep reflection (setAccessible)
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-exports java.base/sun.nio.ch=ALL-UNNAMED \    # compile/link-time API access
     -jar app.jar
```

The distinction matters in interviews: **`--add-exports`** makes a package's *public* types visible across the module boundary (access at the API level); **`--add-opens`** additionally permits *deep reflection* (`setAccessible(true)` on private members) — the one frameworks usually need. These flags are a **migration bridge, not a destination**: the right fix is upgrading the offending library to a JPMS-aware version that no longer reaches into internals, and removing the flags once you can. Bake required flags into the manifest (`Add-Opens:` header) or a JVM args file (`@args.txt`, or `JDK_JAVA_OPTIONS`) so they're versioned and visible, never silently in someone's shell. The principle: strong encapsulation exists so the JDK can evolve internals safely — every `--add-opens` is technical debt you're explicitly tracking.

### 🔴 Expert — extended

#### Q84. [Practical] Latency has a fat p99/p99.9 tail even though p50 is great and CPU is low. Walk through diagnosing tail latency in a JVM service.

A great median with a fat tail is the signature of **intermittent, correlated stalls** rather than slow code — the work is fast *except* when something periodically freezes it. The prime suspects, roughly in order: **(1) GC pauses** — even "concurrent" collectors have stop-the-world phases; if p99.9 spikes line up with GC events in the logs (`-Xlog:gc*` with timestamps), you have GC-induced tail latency. The fix is fewer/shorter pauses: switch to **Generational ZGC** (Q24) for sub-millisecond pauses, or cut allocation rate (Q76). **(2) JIT (de)compilation** and **safepoints** — *every* thread must reach a safepoint for GC, biased-lock revocation, or deopt, so one slow-to-reach thread (a long counted loop, a huge array copy) stalls *all* threads ("time to safepoint"); diagnose with `-Xlog:safepoint` and look at "time to reach safepoint" vs "at safepoint."

```bash
-Xlog:safepoint                                   # spot long "time to safepoint" stalls
-Xlog:gc*:time                                    # correlate pause timestamps with latency spikes
# JFR is ideal here — it timestamps GC pauses, safepoints, lock contention, and
# I/O latency so you can overlay them on your latency histogram:
jcmd <pid> JFR.start settings=profile duration=300s filename=tail.jfr
```

**(3) Lock contention** — a critical section that's usually uncontended but occasionally has a thread queue behind it (JFR "Java Monitor Blocked" events, or many `BLOCKED` threads in a dump, Q68). **(4) Connection/thread-pool queuing** — when a downstream dependency slows, requests queue for a pool slot; the tail is *queue wait*, not service time (instrument pool `pending`/`active`). **(5) Off-JVM causes** — noisy neighbors, CPU throttling under cgroup CPU quota (the container is `throttled` per `/sys/fs/cgroup/.../cpu.stat`), page faults, or network retransmits. The disciplined approach: **measure with a latency histogram** (HdrHistogram, not averages — averages hide tails), then **correlate the spikes against GC/safepoint/lock/queue timelines from JFR**. The expert insight is that p99.9 is dominated by *coordinated omission* and *stop-the-world events*, so you hunt for the periodic freeze, not for a generally-slow path — and "low CPU" actively points away from a hot loop and toward stalls (GC, locks, I/O wait, safepoints).

#### Q85. [Practical] You must roll out a JVM/GC tuning change to a 500-instance fleet with strict SLOs. How do you do it safely and prove it worked?

This is a *change-management* question as much as a JVM one, and the senior answer is: **never tune blind, change one variable at a time, and prove the result with production-representative data.** Most GC "tuning" makes things worse because it's cargo-culted; the correct baseline is usually to *not* set most flags and let G1/ZGC's adaptive ergonomics work, then change only what a measured problem demands.

The rollout sequence:

```
1. ESTABLISH BASELINE: capture current p50/p99/p99.9, throughput, GC pause
   distribution, allocation rate, and CPU/RSS over a representative window
   (HdrHistogram + JFR + GC logs). You can't prove improvement without it.
2. HYPOTHESIS, ONE CHANGE: e.g. "switch G1 -> Generational ZGC to cut p99.9 GC tail."
   Change ONE thing; never bundle five flags.
3. LOAD TEST in a staging replica with production-shaped traffic (shadow/replayed
   traffic beats synthetic). Watch for regressions in throughput/footprint, not just
   the target metric.
4. CANARY: roll to 1% -> 5% -> 25% with automated metric gates; compare canary vs
   control on the SAME traffic. Auto-rollback on SLO breach.
5. SOAK: run canary for hours/days — GC and leak effects are slow; a 10-minute test
   misses old-gen drift (Q70) and Metaspace creep.
6. FLEET ROLLOUT with the same gates; keep the previous config one flag-flip away.
```

The discipline points an interviewer listens for: **A/B against a control group on identical live traffic** (so you isolate the change from traffic variation), **reversibility** (config as code, one-step rollback, feature-flagged), **soak time** (leaks and old-gen drift only appear over hours), and **measuring the trade-offs you didn't target** — lowering pause times via ZGC can cost throughput and RSS (Q24), so you verify those didn't regress past their own SLOs. And the humility: capture *before* numbers, because "it feels faster" is not evidence — the same JMH-vs-vibes lesson from Q25 applied at fleet scale.

#### Q86. [Practical] How would you set up a `-XX:+HeapDumpOnOutOfMemoryError` workflow and safely analyze a multi-gigabyte heap dump from production without taking the service down again?

The goal is to get the *evidence* of an OOM automatically, without a human SSH-ing in during the incident, and to analyze it offline so you don't repeat the outage. The capture side is a launch-time policy every service should have:

```bash
java -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/var/dumps/heap-%p.hprof \    # %p = pid; ensure the volume has room!
     -XX:+ExitOnOutOfMemoryError \                   # let the orchestrator restart a clean process
     -XX:OnOutOfMemoryError="/opt/bin/upload-dump.sh %p" \   # ship the dump off-box
     -jar app.jar
# On demand (no OOM needed), live, without killing the process:
jcmd <pid> GC.heap_dump /var/dumps/live.hprof        # -all to include unreachable objects
jmap -dump:live,format=b,file=heap.hprof <pid>
```

The operational subtleties that separate a senior answer: a heap dump is roughly **heap-size large** and writing it **stops the world for seconds to minutes**, so on an already-struggling box it can extend the outage — which is exactly why `-XX:+ExitOnOutOfMemoryError` (restart clean) plus an *async upload* of the dump is safer than expecting an operator to react, and why you provision dump-disk space ahead of time (a full disk means no dump = no evidence). For analysis, **never open a multi-GB `.hprof` on your laptop** — load it on a beefy box with **Eclipse MAT** (run MAT headless `ParseHeapDump.sh` to pre-build indexes), and go straight to the **Leak Suspects report** and the **dominator tree**: the dominator tree shows which few objects *retain* the most memory (retained ≠ shallow size), and "Path to GC Roots (excluding weak/soft)" tells you *why* the suspect can't be collected — the actual leak (Q23): an unbounded cache, a static `Map`, a `ThreadLocal` on a pool thread (Q50), or a listener never deregistered. Cross-check with the JFR allocation profile (Q78) from before the crash to see *what was allocating*, and the GC log (Q70) to confirm the old-gen-keeps-rising leak signature. The full loop: automatic capture → off-box upload → offline MAT analysis → fix the root structure → add a bounded-cache/leak-detection regression test, so the next deploy can't reintroduce it.

#### Q87. [Practical] A library you depend on uses `sun.misc.Unsafe` (or you have native/`Cleaner`-managed off-heap state). It's being removed — how do you assess exposure and migrate?

`sun.misc.Unsafe` powered a generation of high-performance Java (off-heap allocation, `compareAndSwap`, direct field access, fences, `park`/`unpark`) — Netty, many serializers, off-heap caches, and lock-free libraries lean on it. The JDK has been **methodically removing it**: most memory-access methods are deprecated for removal, the JVM now prints **runtime warnings** when `Unsafe` memory methods are called (Java 23+), and the endgame is full removal. Because `Unsafe` can corrupt the heap and bypass all safety checks, depending on it is now a tracked liability, not a clever optimization.

The migration map to *supported* replacements:

```
Unsafe use                          ->  Modern, supported API
-----------------------------------     -------------------------------------------
compareAndSwap / getAndAdd / fences ->  VarHandle (Java 9) — CAS, atomics, fence/acquire/release
off-heap allocateMemory/freeMemory  ->  Foreign Memory API: Arena + MemorySegment (Q26, final in 22)
direct field get/put (deep access)  ->  VarHandle via MethodHandles.privateLookupIn / records
ByteBuffer-style off-heap I/O       ->  MemorySegment, or DirectByteBuffer with explicit Arena scope
park/unpark, thread internals       ->  LockSupport (already the public wrapper)
```

The assessment workflow: find the exposure — `jdeps --jdk-internals app.jar` (and on each dependency jar) reports every use of `Unsafe` and other JDK internals, including transitively; that's your inventory. For *your* code, migrate directly to **`VarHandle`** (for atomics/CAS/fences — it's faster-or-equal and bounds-checked) and the **Foreign Function & Memory API** (`Arena` + `MemorySegment`, Q26) for off-heap, which gives deterministic, scoped, bounds-checked native memory with no `Cleaner` reliance and no heap-corruption risk. For *library* exposure you can't edit, the lever is upgrading to versions that have already migrated (Netty, etc. have been doing this) — track it in the same dependency-hygiene process as Q81. The interview signal: know that `Unsafe`'s replacements are *already shipped and supported* (`VarHandle` since 9, FFM final in 22), that `jdeps --jdk-internals` is how you find exposure, and that this is a security and forward-compatibility obligation (Q26/Q83), not optional polish — migrating now is cheaper than being stranded on an old JDK when removal lands.

#### Q88. [Practical] Design the observability and on-call playbook for a JVM service so the *next* production incident is diagnosable in minutes, not hours. What do you instrument and pre-stage?

The thesis: incidents are won or lost *before* they happen, by what you pre-stage — most JVM debugging pain is "the spike is gone and we have no evidence." A senior answer is a layered, always-on evidence pipeline plus a runbook that maps symptoms to the exact tool.

```
ALWAYS-ON (pre-staged so evidence exists when it matters):
  - JFR continuous ring buffer: -XX:StartFlightRecording=disk=true,maxage=30m,
    settings=profile  → last 30 min of CPU/alloc/lock/GC/IO always dumpable (Q78)
  - Heap-dump-on-OOM + auto off-box upload + ExitOnOutOfMemoryError (Q86)
  - Unified GC logging to rotating files (Q70); -Xlog:safepoint for tail issues (Q84)
  - JVM + app metrics exported (Micrometer/JMX -> Prometheus): heap by gen, GC pause
    count/time, allocation rate, thread count, pool active/idle/pending, Metaspace,
    direct-buffer bytes, fd count, p50/p99/p99.9 LATENCY HISTOGRAMS (HdrHistogram)
  - Distributed tracing (OpenTelemetry) so a slow request shows WHICH downstream hop
  - Structured logs with trace/span ids; NPE detail messages on (Q66)

RUNBOOK (symptom -> first action):
  high CPU        -> top -H + jstack nid (Q69)  OR dump JFR ring -> flame graph
  hung/slow       -> 3x thread dumps 5s apart (Q68); check deadlock section (Q82)
  OOM heap        -> auto heap dump -> MAT dominator tree / leak suspects (Q86,Q23)
  OOM off-heap    -> jcmd VM.native_memory summary.diff (Q77)
  fat p99.9 tail  -> correlate GC/safepoint/lock timeline in JFR (Q84)
  memory creep    -> GC log old-gen-after-FullGC trend (Q70,Q23)
```

The principles an interviewer is listening for: **you cannot debug a transient after it's gone**, so the JFR ring buffer + auto heap dump + retained metrics history are non-negotiable — they turn "reproduce it" into "read the recording." **Measure latency as a histogram, not an average** (averages hide the tail that pages you, Q84). **Map symptoms to tools in a runbook** so the on-call engineer at 3 a.m. doesn't have to *remember* the `top -H` → hex-nid → `jstack` dance under stress. **Keep overhead low** (JFR `default`/`profile` settings are ~1–2%, safe to always run). And close the loop: **every incident adds a regression test and a runbook entry** so the same failure can't recur silently. This ties the whole operational set together — Q68/Q69 (dumps), Q70/Q77 (memory), Q78/Q84 (profiling and tail latency), Q86 (heap dumps) — into a system where the answer to "what happened?" is already on disk.

#### Q89. [Coding] Double-checked locking and a lazily-initialized singleton silently return a half-constructed object under load. Show the broken code, the JMM bug, and the correct fixes.

This is a canonical concurrency-correctness incident: a lazy singleton works in tests, then under real concurrency some threads observe a *non-null but not-yet-fully-initialized* object — fields read as zero/null even though the object "exists." The root cause is the Java Memory Model (Q16): without proper synchronization, the *write that publishes the reference* can become visible **before** the writes that initialize the object's fields (the constructor's stores can be reordered relative to the reference assignment), so another thread sees the reference and dereferences a partially-built object.

```java
// BROKEN double-checked locking (the classic pre-Java-5-thinking bug):
class Config {
    private static Config instance;                 // NOT volatile  <-- the bug
    static Config get() {
        if (instance == null) {                     // 1st check (no lock)
            synchronized (Config.class) {
                if (instance == null)               // 2nd check (locked)
                    instance = new Config();        // (a) allocate (b) init fields (c) publish ref
            }                                        // (c) may be reordered BEFORE (b) -> half-built
        }
        return instance;
    }
}
```

```java
// FIX 1 — volatile makes DCL correct (Java 5+): the volatile write of `instance`
// happens-before any subsequent volatile read, so initialization is fully visible.
private static volatile Config instance;

// FIX 2 — initialization-on-demand HOLDER idiom: lazy, lock-free, no volatile needed.
// The classloader guarantees Holder initializes exactly once, thread-safely (Q33).
class Config {
    private Config() {}
    private static class Holder { static final Config INSTANCE = new Config(); }
    static Config get() { return Holder.INSTANCE; }   // Holder loads on first call
}

// FIX 3 — enum singleton: simplest, serialization- and reflection-safe (Q57).
enum Config { INSTANCE; /* methods */ }
```

The interview depth here: explain *why* `volatile` fixes it (it forbids the reorder of the field-init writes past the publishing write and establishes a happens-before edge), and *why* the **holder idiom is usually preferable** — it gets lazy initialization with zero synchronization overhead on the hot path by leaning on the JVM's guaranteed-once, lock-protected class initialization (Q33), avoiding the subtlety of remembering `volatile` at all. And the meta-lesson connecting to Q16: "works in tests, fails under load" is the fingerprint of a missing happens-before edge — the bug isn't in the logic, it's in the *visibility/ordering* the JMM does not promise you for free.

#### Q90. [Practical] An interviewer asks you to defend a real production tuning decision you'd push back on: "just set `-Xmx` higher" to fix frequent GC, or "switch to ZGC" to fix high CPU. Why are these often wrong?

The skill being tested is resisting cargo-cult tuning and reasoning from *root cause* to *remedy*. **"Bump `-Xmx` to fix frequent GC"** is usually wrong because GC *frequency* is driven by **allocation rate**, not heap size — a bigger young gen means each GC reclaims more and they happen less often, but if the app allocates 2 GB/s, you've only delayed the inevitable and *lengthened* each pause (more live data to scan/copy), often trading frequent-short for rare-long pauses that blow your p99.9 (Q84). A bigger heap also doesn't fix a *leak* — old-gen-after-Full-GC still climbs (Q70), you just postpone the OOM. The correct move is to confirm with GC logs/JFR whether it's allocation churn (fix the code — primitive streams, object reuse, fewer defensive copies, Q76) or a leak (heap dump → MAT, Q86), and only resize if the live set genuinely needs more room.

**"Switch to ZGC to fix high CPU"** conflates two different problems. ZGC targets *pause time*, not *CPU*; it is concurrent, which means it does GC work *on CPU alongside your application*, so it can actually *increase* CPU usage and reduce peak throughput versus G1/Parallel (the barrier overhead, Q24). If your high CPU is a hot loop, a regex, or `GC Thread`s thrashing from allocation pressure (diagnose via `top -H` + nid, Q69, or a JFR flame graph), switching collectors won't touch the root cause — and if it's throughput-bound batch work, ZGC is the wrong tool entirely (Parallel GC maximizes throughput). 

The disciplined answer to both: **measure the root cause first** (GC log occupancy trend, allocation rate, flame graph, latency histogram), state the actual problem, then pick the *minimal* change that addresses it and **A/B it against a control** (Q85). Push back with: "What does the data say is the bottleneck?" — frequent GC, long GC, high CPU, and OOM are four different problems with four different fixes, and the most common senior failure mode is applying a remedy for one to a symptom of another. The right default is often to change *nothing* in the JVM and fix the *code* or the *cache bound* instead.

### 🟡 Intermediate — extended (continued)

#### Q91. [Practical] How do you make a CPU-bound vs an I/O-bound workload use the right concurrency primitive in Java 21, and how do you size pools?

The fork in the road is whether threads spend their time *computing* or *waiting*, because that dictates both the primitive and the sizing. **CPU-bound** work (parsing, compression, math, in-memory transforms) is limited by cores: more threads than cores just adds context-switch overhead and cache thrash, so the right pool is a **fixed pool sized ≈ number of cores** (`Runtime.getRuntime().availableProcessors()`, after verifying container detection per Q80), and platform threads are correct here — virtual threads give *no* speedup for CPU-bound work since there are still only N cores (Q17). **I/O-bound** work (calling other services, DBs, disk) spends most of its time blocked, so the historical answer was a large thread pool (wasteful — each platform thread costs ~1 MB and blocking ties it up) or reactive code (fast but viral and hard to debug); on Java 21 the answer is **virtual threads** — one per task, blocking-style code, scaling to millions of concurrent waits cheaply.

```java
// CPU-bound: bounded by cores. Don't oversubscribe.
int cores = Runtime.getRuntime().availableProcessors();
ExecutorService cpu = Executors.newFixedThreadPool(cores);

// I/O-bound on Java 21: one virtual thread per task — do NOT pool them.
try (var io = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = userIds.stream()
        .map(id -> io.submit(() -> remoteService.fetch(id)))   // each blocks independently
        .toList();
    for (var f : futures) results.add(f.get());
}   // executor close() waits for all tasks
```

The classic pre-21 sizing formula for a *bounded* I/O pool is **`threads ≈ cores × (1 + waitTime/computeTime)`** (Little's Law in disguise) — a task that's 90% waiting wants ~10× cores — but the modern point is you mostly stop sizing I/O pools at all and let virtual threads expand to demand, while bounding *the real scarce resource* downstream (a `Semaphore` or the connection pool size, so you don't stampede the database with a million concurrent calls). Two senior caveats on virtual threads: **don't pool them** (creating one is cheap; pooling defeats the model and reintroduces the bottleneck), and watch for **pinning** on `synchronized` over blocking calls (use `ReentrantLock`, Q17/Q52). Mixed workloads get *separate* pools — never let a CPU-bound task and a blocking I/O task share an executor, or one starves the other (the same lesson as the parallel-stream common-pool trap in Q71).

#### Q92. [Practical] Production throws `ConcurrentModificationException` intermittently during iteration. What exactly causes it, why is it intermittent, and what are the correct fixes?

`ConcurrentModificationException` (CME) is thrown by the **fail-fast iterators** of the non-concurrent collections (`ArrayList`, `HashMap`, etc., Q37) when the collection is *structurally modified* (add/remove) during iteration through any path other than the iterator's own `remove()`. Mechanically, the collection keeps a `modCount`; the iterator snapshots it as `expectedModCount` at creation and checks `modCount == expectedModCount` on every `next()`/`hasNext()`, throwing if they diverge. The name is misleading: it fires even **single-threaded** (removing from a list inside an enhanced-for over it), and in the *multi*-threaded case it's a *best-effort* detector — it's "intermittent" because whether the check happens to catch the concurrent write depends on timing, so the real danger is the times it *doesn't* throw and silently corrupts state instead.

```java
// BUG (single-threaded): structural modification during for-each -> CME (or worse, silent skip)
for (Order o : orders) {
    if (o.isCancelled()) orders.remove(o);          // throws CME on next iteration
}

// FIX A — Iterator.remove() (the only safe removal during iteration):
for (Iterator<Order> it = orders.iterator(); it.hasNext(); )
    if (it.next().isCancelled()) it.remove();

// FIX B — removeIf (cleanest, internally uses the safe path):
orders.removeIf(Order::isCancelled);

// FIX C — iterate a copy if you must mutate the original during the loop:
for (Order o : List.copyOf(orders)) if (o.isCancelled()) orders.remove(o);
```

For the genuinely **concurrent** case (multiple threads), the fix is not the above but using a thread-safe collection with the right iteration semantics: **`ConcurrentHashMap`** (weakly-consistent iterators that never throw CME and reflect some-but-not-necessarily-all concurrent updates), **`CopyOnWriteArrayList`** (snapshot iterators — perfect for read-heavy, rarely-written listener lists; the iterator sees the list as it was at creation), or explicit synchronization around the *entire* iteration (`synchronized(list){ for(...) }` on a `Collections.synchronizedList`, since the wrapper only synchronizes individual calls, not compound iterate-and-modify). The interview depth: CME is a *fail-fast bug detector*, not a thread-safety mechanism — relying on it to "protect" concurrent access is itself the bug, and the right answer depends on whether the modification is your own single-threaded logic (use `Iterator.remove`/`removeIf`) or true concurrency (use a concurrent collection chosen for the read/write ratio).

#### Q93. [Practical] Stream pipelines in a hot service are showing up in profiles. What are the real performance traps and stateful-operation pitfalls of `Stream`, and when do you drop back to loops?

Streams are excellent for readability and parallelism but they are *not* free, and in a genuinely hot path several traps surface in a profile (Q78). **Boxing** is the headline cost (Q76): `Stream<Integer>`/`map`/`reduce` over wrappers allocate per element — use `IntStream`/`LongStream`/`DoubleStream` and the `*ToInt`/`mapToLong` adapters to stay primitive. **Pipeline + lambda allocation**: each stream creates pipeline objects, and *capturing* lambdas allocate a closure per invocation, so a stream built millions of times per second has real overhead a plain loop doesn't. **Short-circuiting that isn't**: `filter(...).findFirst()` is lazy and stops early, but `sorted()`, `distinct()`, and `collect(toList())` are **stateful/terminal buffering** operations that must materialize the whole stream — putting `sorted()` before a `limit(k)` sorts *everything* (O(n log n)) instead of keeping a bounded heap (Q22), a common accidental O(n log n).

```java
// TRAP: sorts ALL n elements to take the top 10 — O(n log n) + full buffer:
list.stream().sorted(comparing(X::score).reversed()).limit(10).toList();

// Better for k << n: bounded min-heap (Q22) keeps it O(n log k), no full sort/buffer.

// TRAP: stateful lambda in a stream — breaks laziness & parallelism, racy if parallel:
int[] running = {0};
list.stream().map(x -> running[0] += x).toList();   // side-effecting map: forbidden

// Hot path: a plain indexed loop allocates nothing and the JIT loves it:
long sum = 0; for (int i = 0, n = a.length; i < n; i++) sum += a[i];
```

The senior judgment: **keep streams for clarity in the 95% of code that isn't hot**, and only rewrite a stream as a loop when a *profiler* (JFR allocation/CPU, Q78) shows that specific pipeline is a bottleneck — never on a hunch (Q25). The non-negotiable correctness rules regardless of performance: stream lambdas must be **non-interfering** (don't mutate the source during the pipeline — same hazard as Q92) and **stateless** (a `map`/`filter` that mutates external state breaks under reordering and explodes under `parallel()`, Q71); the `forEach` terminal makes *no ordering guarantee* in parallel (use `forEachOrdered` if you need it, at a cost). Also avoid `peek` for anything but debugging (the JIT may elide it), and prefer `Stream.toList()` (Java 16+, returns an unmodifiable list, fewer surprises) over `collect(toList())` when you don't need a specific collection type.

#### Q94. [Coding] `Collectors.toMap` throws `IllegalStateException: Duplicate key` in production but not in tests. Why, and how do you fix it correctly?

This is one of the most common stream-to-production surprises: `Collectors.toMap(keyFn, valueFn)` with the **two-argument** form throws `IllegalStateException: Duplicate key` the instant two elements map to the same key — and tests usually use tiny, hand-picked, unique-keyed fixtures, so the collision only appears against real data that has duplicates you didn't anticipate. The exception message even includes the two clashing *values*, which can leak data into logs.

```java
// FRAGILE: explodes the first time two users share an email:
Map<String, User> byEmail = users.stream()
    .collect(Collectors.toMap(User::email, u -> u));   // IllegalStateException on duplicate

// FIX: supply a MERGE function deciding which value wins on collision:
Map<String, User> byEmail = users.stream()
    .collect(Collectors.toMap(User::email, u -> u,
             (existing, replacement) -> existing));     // keep first; or pick newest, merge, etc.

// If you actually want all values per key, you wanted groupingBy, not toMap:
Map<String, List<User>> byEmail = users.stream()
    .collect(Collectors.groupingBy(User::email));
```

There are two more sharp edges worth naming in an interview. First, the **null trap**: `Collectors.toMap` is backed by `HashMap` but its merge logic calls `Map.merge`, which throws `NullPointerException` if the *value* function returns null (you can't store a null value through it) — surprising if you're used to `HashMap.put(k, null)` working. Second, **ordering and map type**: the standard collector gives you a `HashMap` (no order guarantee); use the four-arg form with a `LinkedHashMap::new` or `TreeMap::new` supplier when you need insertion or sorted order. The deeper lesson is that `toMap`'s two-arg form encodes an *assumption of uniqueness* that your data may violate — so either prove keys are unique (and document it) or always pass a merge function and decide the conflict policy deliberately, rather than letting a runtime exception decide it for you under load.

#### Q95. [Practical] An endpoint hangs and pegs a CPU core when a user submits a particular string. You suspect catastrophic regex backtracking (ReDoS). How do you confirm and fix it?

Catastrophic backtracking (ReDoS — Regular-expression Denial of Service) happens when a regex with **nested or overlapping quantifiers** is fed input that *almost* matches: the engine explores an exponential number of ways to partition the string before concluding "no match," so a 30-character input can take seconds or minutes and pin a core at 100%. It's a real availability vulnerability because the trigger string is small and the regex often comes from validation on *user-controlled* input. The classic offenders are patterns like `(a+)+$`, `(a|a)*`, or `(\w+\s?)*$` — anything where one quantified group can match the same text multiple ways.

```java
// VULNERABLE: nested quantifier -> exponential backtracking on "aaaa...!" (no match):
Pattern p = Pattern.compile("(a+)+$");
p.matcher("aaaaaaaaaaaaaaaaaaaaaaaa!").matches();   // hangs, CPU pegged

// CONFIRM: a thread dump (Q68/Q69) shows the hot thread parked in
//   java.util.regex.Pattern$... .match(...)  for seconds across multiple dumps.
```

Confirmation follows the CPU-spike playbook (Q69): `top -H` to the hot thread, then a thread dump that lands inside `java.util.regex.Pattern` — `match`/`Curly`/`GroupTail` frames are the fingerprint, and the *same* regex frame across successive dumps confirms it's not transient. The fixes, in order: **(1) rewrite the regex to remove ambiguity** — make quantifiers non-overlapping, anchor properly, and use **possessive quantifiers** (`a++`, `(a+)+` → `a+`) or **atomic groups** `(?>...)` which forbid the backtracking that causes the blowup. **(2) Don't use regex at all** for things like email/URL "validation" — a simple parser or a vetted library is both safer and faster. **(3) Bound the work**: cap input length *before* matching, and for genuinely untrusted patterns run the match on a separate thread with a timeout/interrupt (the regex engine checks `Thread.interrupted()` at backtracking points in modern JDKs, so an interrupt can abort it). The systemic prevention: treat every regex applied to external input as a potential DoS, lint for nested quantifiers, and never let users supply the *pattern* itself. This is the operational cousin of the hash-collision DoS in Q21 — untrusted input meeting a superlinear algorithm.

#### Q96. [Practical] Money calculations are off by a cent and audits fail. Why is `double` wrong for currency, and how do you use `BigDecimal` correctly in production?

`double`/`float` are IEEE-754 binary floating-point (Q54), and most decimal fractions — including `0.1`, `0.20`, `0.07` — have **no exact binary representation**, so `0.1 + 0.2 == 0.30000000000000004` and a series of monetary operations accumulates rounding error that surfaces as "off by a cent" reconciliation failures and failed audits. For money you need *exact decimal arithmetic with explicit, controlled rounding*, which is `BigDecimal` (or storing integer minor units — cents/satoshis — as `long`).

```java
// WRONG: binary rounding error accumulates and fails the audit:
double total = 0.0; for (int i = 0; i < 10; i++) total += 0.1;  // 0.9999999999999999

// RIGHT: BigDecimal — but construct from STRING, never from a double:
BigDecimal price = new BigDecimal("0.10");     // exact
BigDecimal bad   = new BigDecimal(0.10);       // 0.1000000000000000055511151... (inherits the double error!)
BigDecimal total = BigDecimal.ZERO;
for (int i = 0; i < 10; i++) total = total.add(price);   // exactly 1.00

// Division MUST specify scale + RoundingMode or it throws ArithmeticException on non-terminating results:
BigDecimal share = total.divide(new BigDecimal("3"), 2, RoundingMode.HALF_EVEN);  // 0.33
```

The production rules that separate a correct answer from a dangerous one: **(1) construct `BigDecimal` from a `String` (or `valueOf`), never from a `double`** — `new BigDecimal(0.1)` faithfully reproduces the double's error, defeating the whole point. **(2) Always pass a `scale` and `RoundingMode` to `divide`** — the no-arg/no-rounding form throws `ArithmeticException` on a non-terminating decimal (e.g. `1/3`), and **`RoundingMode.HALF_EVEN`** (banker's rounding) is the finance default because it avoids the upward bias of `HALF_UP` over many operations. **(3) Mind `equals` vs `compareTo`**: `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` is **false** because `equals` compares *scale* too, so always use `compareTo() == 0` for numeric equality. **(4) `BigDecimal` is immutable** — every operation returns a new instance, and forgetting to assign the result (`total.add(x);` with no reassignment) is a classic silent bug. Also map the DB column to `DECIMAL/NUMERIC` (not `FLOAT`) and carry currency alongside the amount. The interview signal is knowing *why* `double` fails (binary representation, Q54), the string-constructor and division-rounding rules, and the `equals`/`compareTo` scale trap — the details that make `BigDecimal` actually correct rather than merely "the money type."

#### Q97. [Practical] A wrong or noisy log level is hurting you in production — either flooding disk and slowing the app, or hiding the one line you need. How do you reason about logging in a Java service?

Logging is an operational system, not an afterthought, and two failure modes dominate. **Too much logging**: `DEBUG`/`TRACE` left on in production, or logging inside a hot loop, generates I/O and string-building that measurably slows the service and fills disk (a full disk then takes the service *down* — and breaks the heap-dump capture of Q86). The subtle cost is **eager argument construction**: `log.debug("state=" + expensiveToString())` builds the string *even when DEBUG is disabled*, because the argument is evaluated before the call. **Too little / wrong logging**: the one stack trace you need was swallowed (`catch (Exception e) {}`) or logged at the wrong level so it's filtered out, leaving an incident undiagnosable.

```java
// BAD: string concatenation runs even if DEBUG is off (wasted CPU/allocation):
log.debug("payload=" + serialize(payload));

// GOOD: SLF4J parameterized form — serialize() runs ONLY if DEBUG is enabled:
log.debug("payload={}", payload);                  // toString deferred to the framework
// Or guard an expensive computation explicitly:
if (log.isDebugEnabled()) log.debug("payload={}", expensiveDump());

// Always log the exception OBJECT (last arg), never just its message — you lose the stack trace:
catch (IOException e) { log.error("failed to read {}", path, e); }   // e as trailing arg = full trace
```

The principles an interviewer listens for: **log to an interface (SLF4J), bind one implementation (Logback/Log4j2), and use parameterized messages** so you never pay formatting cost for a disabled level and never accidentally concatenate. **Make levels mean something** — `ERROR` = an operator must act, `WARN` = recoverable anomaly, `INFO` = business milestones, `DEBUG`/`TRACE` = off in prod, toggleable at runtime (Spring Boot Actuator / JMX / log config reload) so you can raise verbosity on a misbehaving instance *without a redeploy*. **Structured (JSON) logging with a correlation/trace id** (tie into the tracing from Q88) so you can grep one request across services. And the security/operational guardrails: **never log secrets, tokens, PII, or full payloads** (a compliance and breach risk), use **async appenders** so logging I/O doesn't block request threads, and configure **size/time-based rotation with retention** so logs can't fill the disk. Two infamous reminders that logging is part of your attack and reliability surface: the **Log4Shell** (CVE-2021-44228) RCE came from a logging library interpolating attacker-controlled input, and a blocking synchronous appender on a slow disk can serialize your whole request path. The mature stance: logging is configuration-as-code, level-disciplined, parameterized, structured, secret-free, and rotated.

#### Q98. [Practical] You need to attach to a running production JVM to investigate — what's in the diagnostic toolbox, and how do you do it with minimal risk?

The JDK ships a complete *attach-to-a-live-process* toolbox, and the senior skill is knowing which tool answers which question with the **least overhead and risk** — you rarely need a restart or a debugger. The unifying entry point is **`jcmd`**, which dispatches dozens of diagnostic commands to a running PID without any pre-configured agent:

```bash
jcmd <pid> help                       # list everything this JVM supports
jcmd <pid> VM.flags                   # effective JVM flags (what's ACTUALLY set, incl. ergonomics)
jcmd <pid> VM.system_properties       # -D properties, classpath, java.version
jcmd <pid> VM.uptime / VM.command_line
jcmd <pid> Thread.print               # thread dump / deadlocks (Q68)
jcmd <pid> GC.heap_info               # live heap usage by generation
jcmd <pid> GC.class_histogram         # object counts by class -> quick leak triage (cheap)
jcmd <pid> GC.heap_dump <file>        # full heap dump (Q86) — heavy, STW
jcmd <pid> VM.native_memory summary   # off-heap breakdown (needs NMT enabled, Q77)
jcmd <pid> JFR.start ... / JFR.dump   # flight recording (Q78)
# Companions: jps (list JVMs+args), jstat -gcutil <pid> 1s (live GC %),
#             jstack (dumps), jmap (heap), jhsdb (deep introspection).
```

The risk-awareness is the differentiator. Light, safe-to-run-anytime: `VM.flags`, `VM.system_properties`, `Thread.print`, `GC.heap_info`, `jstat -gcutil` (per-region GC percentages, great for watching a live problem). Medium: `GC.class_histogram` (walks the heap — brief pause). **Heavy / stop-the-world** (only with intent): `GC.heap_dump` and `jmap -dump` freeze the process for seconds-to-minutes proportional to heap size (Q86), so on a struggling box they can *extend* the outage — prefer dumping the **JFR ring buffer** you pre-staged (Q88) which is ~1% overhead. For interactive **remote debugging** you'd launch with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` and attach a debugger — but `suspend=n` is mandatory in prod (so the JVM doesn't wait for a debugger at startup), JDWP is **unauthenticated** so it must be bound to localhost/behind a tunnel (an open JDWP port is remote code execution), and breakpoints stall request threads, so debuggers are a last resort behind profiling. The takeaway: reach for `jcmd`/`jstat`/JFR (attach, observe, low overhead) first; reserve heap dumps and live debuggers for when the lighter tools have already narrowed it down, and treat the diagnostic ports themselves as a security surface.

#### Q99. [Coding] `List.of(...)`/`Arrays.asList(...)`/`Collectors.toList()` results blow up with `UnsupportedOperationException` or `NullPointerException`. What are the immutability and null gotchas of factory collections?

Java's collection factories have *different and easily-confused* mutability and null semantics, and the bugs surface as runtime exceptions far from the construction site. **`List.of()`/`Set.of()`/`Map.of()` (Java 9)** create **truly immutable** collections — any mutator (`add`, `set`, `remove`, `clear`) throws `UnsupportedOperationException`, **null elements/keys/values throw `NullPointerException`**, and `Set.of`/`Map.of` reject **duplicate** elements/keys with `IllegalArgumentException` at construction. **`Arrays.asList(...)`** is a different beast: it's a *fixed-size* view backed by the array — you *can* `set(i, x)` (and it writes through to the array!) but `add`/`remove` throw `UnsupportedOperationException`, and it *does* permit nulls. **`Collections.unmodifiableList(x)`** is only an unmodifiable *wrapper* — mutating the *underlying* list still shows through.

```java
List<String> a = List.of("x", "y");
a.add("z");                       // UnsupportedOperationException — immutable
List.of("x", null);               // NullPointerException — no nulls allowed

List<Integer> b = Arrays.asList(1, 2, 3);
b.set(0, 9);                      // OK — writes through to the backing array
b.add(4);                         // UnsupportedOperationException — fixed size

List<String> c = stream.collect(Collectors.toList());  // mutability NOT guaranteed by spec
List<String> d = stream.toList();                        // Java 16+: explicitly UNMODIFIABLE

// Need a mutable copy of an immutable/fixed list? Wrap it:
List<String> mutable = new ArrayList<>(List.of("x", "y"));   // now add/remove work
```

The traps that bite in production: (1) returning a `List.of(...)` or `stream.toList()` from a method whose caller then tries to `add` to it — the caller crashes far from your code, so **document the mutability of what you return** (returning immutable is good defensive design, but be explicit). (2) `Stream.collect(Collectors.toList())` makes **no mutability guarantee** (today it's an `ArrayList`, but the spec doesn't promise it), so don't rely on mutating it; if you need mutability use `collect(Collectors.toCollection(ArrayList::new))`, and if you want guaranteed-unmodifiable use `stream.toList()` (Java 16+). (3) `Arrays.asList(primitiveArray)` — `Arrays.asList(new int[]{1,2,3})` returns a `List<int[]>` of size **1** (the `int[]` is treated as one Object), a notorious silent bug; use `IntStream.of(...).boxed().toList()` or pass a `Integer[]`. (4) The **null-hostility of `List.of`** is usually a *feature* (it surfaces nulls early), but migrating old code from `Arrays.asList`/`new ArrayList<>()` (which tolerate nulls) to `List.of` can introduce NPEs on previously-working data. The interview signal: knowing that "immutable factory" (`of`), "fixed-size array view" (`Arrays.asList`), "unmodifiable wrapper" (`Collections.unmodifiableList`), and "unspecified vs guaranteed-unmodifiable collector" (`Collectors.toList` vs `Stream.toList`) are four distinct contracts — and matching the right one to whether the caller needs to mutate.

## 🧩 Extended Questions — Set 3: Coding, Design & Expert Scenarios

### 🟢 Basic — extended

#### Q100. [Coding] Given an array of integers and a target, return the indices of the two numbers that add up to the target (Two Sum).

**Problem:** Classic warm-up. The naive answer is a double loop (`O(n²)`); the signal an interviewer wants is recognizing that a `HashMap` turns the inner search into an `O(1)` lookup, trading space for time.

```java
static int[] twoSum(int[] nums, int target) {
    Map<Integer, Integer> seen = new HashMap<>();      // value -> index
    for (int i = 0; i < nums.length; i++) {
        int need = target - nums[i];
        Integer j = seen.get(need);                    // have we already seen the complement?
        if (j != null) return new int[]{j, i};         // found before adding self -> distinct indices
        seen.put(nums[i], i);                          // record AFTER the check
    }
    throw new IllegalArgumentException("no pair sums to " + target);
}
```

**Complexity:** `O(n)` time, `O(n)` space — a single pass. The two subtle correctness points are (1) **check before you put**, so an element can't pair with itself, and (2) using a `HashMap` value→index rather than sorting, because sorting (`O(n log n)`) destroys the original indices you must return. **Edge cases:** duplicate values that form the pair (`[3,3]`, target 6 — works because we record indices, not just presence), negative numbers and overflow (`target - nums[i]` can overflow `int` for extreme inputs — use `long` arithmetic if the constraints allow `Integer.MIN/MAX`), and no-solution (decide whether to return `null`, an empty array, or throw — be explicit). If the array were *sorted* and you needed `O(1)` space, the two-pointer technique (one index from each end, move inward by comparing the sum to the target) is the better answer — worth mentioning to show you adapt the approach to the constraints.

#### Q101. [Coding] Determine whether two strings are anagrams of each other.

**Problem:** Two strings are anagrams if one is a rearrangement of the other (same multiset of characters). There are two idiomatic approaches, and naming both — plus their trade-offs — is the point.

```java
// Approach 1 — frequency count: O(n) time, O(1) extra space for a fixed alphabet.
static boolean isAnagram(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) return false;  // fast reject
    int[] counts = new int[26];                     // assumes lowercase a-z
    for (int i = 0; i < a.length(); i++) {
        counts[a.charAt(i) - 'a']++;
        counts[b.charAt(i) - 'a']--;
    }
    for (int c : counts) if (c != 0) return false;  // every increment must be cancelled
    return true;
}

// Approach 2 — sort both and compare: O(n log n), but trivially handles ANY charset.
static boolean isAnagramSort(String a, String b) {
    if (a.length() != b.length()) return false;
    char[] x = a.toCharArray(), y = b.toCharArray();
    Arrays.sort(x); Arrays.sort(y);
    return Arrays.equals(x, y);
}
```

**Complexity:** the counting approach is `O(n)` time and `O(1)` space *for a bounded alphabet* (a 26-entry array); the sorting approach is `O(n log n)` but works for any character set without assuming the alphabet. **Edge cases that separate a strong answer:** the **length pre-check** is a cheap early-out and a correctness guard; **Unicode** breaks the `int[26]` assumption — for full Unicode use a `Map<Integer,Integer>` keyed by **code point** (`a.codePoints()`), not `char`, because emoji and non-BMP characters are surrogate *pairs* and `charAt` would split them; and you should clarify whether case and whitespace matter (`"Listen"` vs `"Silent"`) before coding. The interviewer is checking whether you reach for the `O(n)` counting trick *and* recognize the hidden Unicode/charset assumptions baked into `- 'a'`.

#### Q102. [Coding] Implement a Stack using two Queues (or a Queue using two Stacks). Explain the amortized cost.

**Problem:** A classic data-structure-composition exercise. It tests whether you understand the LIFO/FIFO duality and can reason about *amortized* cost, not just worst-case.

```java
// Queue implemented with two Stacks: push is O(1); pop/peek is amortized O(1).
class MyQueue<T> {
    private final Deque<T> in  = new ArrayDeque<>();   // newest on top
    private final Deque<T> out = new ArrayDeque<>();   // oldest on top

    void enqueue(T x) { in.push(x); }                  // always O(1)

    T dequeue() {
        shiftIfNeeded();
        return out.pop();
    }
    T peek() {
        shiftIfNeeded();
        return out.peek();
    }
    private void shiftIfNeeded() {
        if (out.isEmpty()) {                           // only refill when 'out' is drained
            while (!in.isEmpty()) out.push(in.pop());  // reverses order -> FIFO
        }
    }
    boolean isEmpty() { return in.isEmpty() && out.isEmpty(); }
}
```

**Complexity / the key insight:** `enqueue` is always `O(1)`. A single `dequeue` can be `O(n)` (when it triggers the transfer), but each element is moved from `in` to `out` **at most once over its lifetime**, so the *amortized* cost per operation is `O(1)` — this is the whole point of the question, and citing "amortized" (with the per-element-moved-once argument) is what distinguishes a senior answer. **Why `ArrayDeque` not `Stack`/`LinkedList`:** `java.util.Stack` is the legacy synchronized `Vector` subclass (slow, and exposes index access that violates stack semantics), and `LinkedList` allocates a node per element; `ArrayDeque` is the recommended stack/queue in modern Java (Q4). **Edge cases:** popping/peeking an empty queue (throw or return a sentinel — `ArrayDeque.pop()` throws `NoSuchElementException`), and the crucial bug to avoid is re-shifting on *every* `dequeue` (transferring back and forth), which would make it `O(n)` per op — you only transfer when `out` is empty.

### 🟡 Intermediate — extended

#### Q103. [Coding] Merge a list of overlapping intervals into the minimal set of non-overlapping intervals.

**Problem:** Given `int[][] intervals` like `[[1,3],[2,6],[8,10],[15,18]]`, merge all overlapping ones → `[[1,6],[8,10],[15,18]]`. This is a staple that tests the "sort, then sweep" pattern and careful boundary reasoning.

```java
static int[][] merge(int[][] intervals) {
    if (intervals.length <= 1) return intervals;
    // 1. Sort by start. This is what makes a single linear sweep correct.
    Arrays.sort(intervals, Comparator.comparingInt(a -> a[0]));

    List<int[]> merged = new ArrayList<>();
    int[] current = intervals[0].clone();              // clone so we don't mutate the input
    for (int i = 1; i < intervals.length; i++) {
        int[] next = intervals[i];
        if (next[0] <= current[1]) {                   // overlap (touching counts as overlap)
            current[1] = Math.max(current[1], next[1]); // extend the end; next may be fully inside
        } else {
            merged.add(current);                       // disjoint -> commit current, start new
            current = next.clone();
        }
    }
    merged.add(current);                               // don't forget the last one
    return merged.toArray(new int[0][]);
}
```

**Complexity:** `O(n log n)` dominated by the sort; the sweep is `O(n)`, `O(n)` output space. **The reasoning that matters:** sorting by start guarantees that when you reach an interval, any interval it could merge with has already been seen — so a single pass with one "current" accumulator suffices, no nested comparison. **Edge cases the interviewer probes:** the `<=` vs `<` decision (do touching intervals `[1,2]` and `[2,3]` merge? — depends on whether endpoints are inclusive; state your assumption), an interval fully contained in the current one (`[1,10]` then `[2,3]` — `Math.max` on the end handles it correctly), the final `merged.add(current)` after the loop (a classic off-by-one omission), and not mutating the caller's arrays (the `.clone()`). This pattern (sort then linear sweep) generalizes to meeting-room scheduling, calendar booking, and the "insert interval" variants.

#### Q104. [Coding] Implement a thread-safe bounded blocking buffer (producer–consumer) using `wait`/`notify`, then with `BlockingQueue`.

**Problem:** Multiple producers add items, multiple consumers remove them; producers block when the buffer is full, consumers block when it's empty. This is *the* classic concurrency exercise and tests correct guarded-wait discipline.

```java
// Low-level version — demonstrates the guarded-wait idiom (what BlockingQueue does for you).
class BoundedBuffer<T> {
    private final Queue<T> q = new ArrayDeque<>();
    private final int capacity;
    BoundedBuffer(int capacity) { this.capacity = capacity; }

    public synchronized void put(T item) throws InterruptedException {
        while (q.size() == capacity) wait();   // WHILE, not if: re-check after wakeup (spurious + races)
        q.add(item);
        notifyAll();                           // wake any consumer blocked on empty
    }
    public synchronized T take() throws InterruptedException {
        while (q.isEmpty()) wait();             // guarded wait on the empty condition
        T item = q.remove();
        notifyAll();                            // wake any producer blocked on full
        return item;
    }
}
```

```java
// Production version — let the library do it correctly:
BlockingQueue<Task> queue = new ArrayBlockingQueue<>(1000);   // bounded -> back-pressure
// producer:  queue.put(task);     // blocks when full
// consumer:  Task t = queue.take(); // blocks when empty
```

**The non-negotiable correctness points:** (1) **always wait in a `while` loop, never an `if`** — a thread can wake from `wait()` *spuriously* (allowed by the JLS) or because another thread won the race and re-filled/drained the buffer, so you must re-test the condition before proceeding; this is the single most common bug in this exercise. (2) `wait()`/`notify()` must be called **holding the monitor** (`synchronized`), and `wait()` *releases* the lock while parked and re-acquires it on wake. (3) Use **`notifyAll()` not `notify()`** when multiple distinct conditions share one monitor (producers waiting on "full" and consumers on "empty"), or you can wake the wrong waiter and deadlock. **Why `BlockingQueue` is the real answer:** it encapsulates all of this, and `ArrayBlockingQueue`/`LinkedBlockingQueue` give you **bounded back-pressure** — the bound is the whole point, because an unbounded queue just moves an overload from "blocked producers" to "OutOfMemoryError." For finer control you'd use a `ReentrantLock` with two separate `Condition`s (`notFull`, `notEmpty`) so you can `signal` exactly the right waiter — strictly better than the single-monitor `notifyAll` version.

#### Q105. [Coding] Design and implement the Builder pattern for an immutable object with required and optional fields, including validation.

**Problem:** A class with many fields (some required, some optional) makes telescoping constructors unreadable and a setter-based bean mutable. The Builder pattern (Effective Java Item 2) gives readable, validated construction of an *immutable* result.

```java
public final class HttpRequest {
    private final String url;                 // required
    private final String method;              // optional, defaulted
    private final Map<String, String> headers;// optional
    private final int timeoutMs;              // optional, defaulted

    private HttpRequest(Builder b) {          // private: only the Builder constructs
        this.url = b.url;
        this.method = b.method;
        this.headers = Map.copyOf(b.headers); // defensive immutable copy
        this.timeoutMs = b.timeoutMs;
    }

    public static Builder builder(String url) { return new Builder(url); }

    public static final class Builder {
        private final String url;                       // required -> constructor arg
        private String method = "GET";                  // sensible defaults
        private final Map<String, String> headers = new HashMap<>();
        private int timeoutMs = 30_000;

        private Builder(String url) {
            this.url = Objects.requireNonNull(url, "url");   // validate required up front
        }
        public Builder method(String m) { this.method = Objects.requireNonNull(m); return this; }
        public Builder header(String k, String v) { headers.put(k, v); return this; }  // fluent
        public Builder timeoutMs(int t) {
            if (t <= 0) throw new IllegalArgumentException("timeout must be > 0");
            this.timeoutMs = t; return this;
        }
        public HttpRequest build() {                    // single validation/construction point
            if (!url.startsWith("http")) throw new IllegalStateException("invalid url: " + url);
            return new HttpRequest(this);
        }
    }
}
// Usage: HttpRequest.builder("https://x").method("POST").header("Accept","json").timeoutMs(5000).build();
```

**Why this design:** required fields go through the builder's constructor (so you *can't* forget them — compile-time enforcement), optionals get defaults and fluent setters returning `this` for chaining, and `build()` is the *single* place cross-field validation runs and the immutable instance is created. The result is **immutable** (all `final`, defensive `Map.copyOf`, no public setters) so it's thread-safe and shareable (Q9, Q46). **When to use vs alternatives:** a **record** is the better choice when you have a handful of fields with no defaults/optionals and want zero boilerplate — but records don't natively express "optional with default" or staged required-vs-optional construction, so the Builder still earns its keep for objects with many optional fields, complex validation, or where you want to validate *across* fields in one place. **Trade-offs:** more boilerplate than a constructor (mitigated by IDE generation or Lombok `@Builder`), and a tiny allocation for the builder; the payoff is readability at the call site and a single, testable validation gate.

#### Q106. [Coding] Write a correct `equals()`/`hashCode()` pair for a class with inheritance, and explain why symmetry breaks naively.

**Problem:** Implementing `equals` across a class hierarchy is a famous trap (Effective Java Item 10): the obvious `instanceof`-based approach silently breaks the **symmetry** contract when a subclass adds a value-significant field.

```java
class Point {
    final int x, y;
    Point(int x, int y) { this.x = x; this.y = y; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;  // getClass(), NOT instanceof
        Point p = (Point) o;
        return x == p.x && y == p.y;
    }
    @Override public int hashCode() { return Objects.hash(x, y); }
}
```

**Why `getClass()` instead of `instanceof`:** suppose `ColorPoint extends Point` adds a `color` field and uses `instanceof Point` in its `equals`. Then a `Point p = (2,3)` and a `ColorPoint cp = (2,3,RED)` give `p.equals(cp) == true` (Point ignores color) but `cp.equals(p) == false` (ColorPoint checks color and `p` has none) — **asymmetric**, which corrupts `List.contains`, `Set` membership, and map lookups in ways that depend on argument order. Using `getClass() != o.getClass()` makes a `Point` and a `ColorPoint` *never* equal, restoring symmetry — at the cost of breaking the Liskov substitution principle (a `ColorPoint` is no longer `equals`-comparable to a `Point`).

**The deeper resolution:** there is *no* fully satisfactory way to add a value field to a subclass and preserve the `equals` contract with the superclass — this is a fundamental tension. The recommended escape (Item 10) is **"favor composition over inheritance"**: make `ColorPoint` *contain* a `Point` rather than extend it, and expose an `asPoint()` view, so the two types are simply not in an `equals` relationship. **Modern note:** `record` types sidestep the whole problem — they're implicitly `final` (no subclassing to break symmetry) and generate a `getClass`-based `equals`, which is exactly why records are the preferred value-type mechanism. Always pair `equals` with a consistent `hashCode` over the *same fields* (`Objects.hash`), or hash collections break (Q1, Q65).

#### Q107. [Coding] Implement a generic, immutable, type-safe `Either<L, R>` (or `Result<T, E>`) and explain why you'd use it over exceptions.

**Problem:** Model a computation that yields *either* a success value *or* a typed error, without throwing. This tests generics, sealed types, functional composition, and API design judgment.

```java
public sealed interface Result<T, E> permits Result.Ok, Result.Err {
    record Ok<T, E>(T value)  implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}

    static <T, E> Result<T, E> ok(T v)  { return new Ok<>(v); }
    static <T, E> Result<T, E> err(E e) { return new Err<>(e); }

    // map transforms the success; errors pass through untouched (functor)
    default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Ok<T, E> ok   -> Result.ok(f.apply(ok.value()));
            case Err<T, E> err -> Result.err(err.error());
        };  // exhaustive: sealed -> no default needed (Q15, Q19)
    }
    // flatMap chains another fallible step (monad) without nesting Result<Result<..>>
    default <U> Result<U, E> flatMap(Function<? super T, Result<U, E>> f) {
        return switch (this) {
            case Ok<T, E> ok   -> f.apply(ok.value());
            case Err<T, E> err -> Result.err(err.error());
        };
    }
    default T orElse(T fallback) {
        return this instanceof Ok<T, E> ok ? ok.value() : fallback;
    }
}
// Usage:
Result<Integer, String> r = parse("42").flatMap(this::validate).map(x -> x * 2);
```

**Why prefer this over exceptions:** the error becomes **part of the type signature**, so callers *cannot* ignore it (unlike an unchecked exception that silently propagates), yet it composes cleanly through `map`/`flatMap` — unlike checked exceptions, which don't compose with streams/lambdas (Q3) and force `try/catch` boilerplate at every step. It also avoids the *performance* cost of exceptions (filling in a stack trace is expensive) on hot, expectedly-failing paths like parsing and validation. **When NOT to use it:** for genuinely *exceptional*, unrecoverable conditions (programming bugs, I/O failures the caller can't handle) — exceptions are the right tool there; `Result` is for *expected* domain failures that are part of normal control flow. The sealed-interface-plus-records encoding is the idiomatic Java-21 way (a closed sum type, Q15/Q19) and gives compiler-checked exhaustiveness in the `switch`. This is the pattern behind Rust's `Result`, Scala's `Either`, and Vavr's `Either`/`Try` — interviewers like seeing that you can build it from Java's algebraic-data-type primitives and that you know its *appropriate* scope.

### 🟠 Advanced — extended

#### Q108. [Coding] Implement a non-blocking, thread-safe counter and a lock-free stack using `AtomicInteger`/`AtomicReference` CAS. Explain the ABA problem.

**Problem:** Build concurrent structures *without locks*, using compare-and-swap (CAS), and demonstrate you understand the retry loop and the ABA hazard.

```java
// Lock-free Treiber stack: push/pop via CAS on the head reference.
class LockFreeStack<T> {
    private static final class Node<T> { final T value; final Node<T> next;
        Node(T v, Node<T> n) { value = v; next = n; } }
    private final AtomicReference<Node<T>> head = new AtomicReference<>();

    void push(T value) {
        Node<T> newHead;
        Node<T> oldHead;
        do {
            oldHead = head.get();                       // read current head
            newHead = new Node<>(value, oldHead);       // link new node to it
        } while (!head.compareAndSet(oldHead, newHead)); // retry if someone else changed head
    }
    T pop() {
        Node<T> oldHead, newHead;
        do {
            oldHead = head.get();
            if (oldHead == null) return null;           // empty
            newHead = oldHead.next;
        } while (!head.compareAndSet(oldHead, newHead));
        return oldHead.value;
    }
}
```

**How CAS works and why the loop:** `compareAndSet(expected, new)` atomically sets the value to `new` *only if* it currently equals `expected`, returning `false` otherwise — it compiles to a single CPU instruction (`LOCK CMPXCHG` on x86) with no OS-level blocking. The `do/while` is the **optimistic retry**: read, compute a new value, attempt to swap, and loop if a concurrent thread beat you. This is *non-blocking* (a stalled thread never blocks others) and scales better than locks under moderate contention — but under *heavy* contention the retries waste CPU (consider `LongAdder` for hot counters, Q31). 

**The ABA problem** is the classic gotcha: a thread reads head = A, gets descheduled; meanwhile other threads pop A, pop B, and push A *back* (reusing the node). The first thread's `compareAndSet(A, ...)` now *succeeds* because the reference is "A" again — but the stack's internals changed underneath it, potentially corrupting the structure. CAS only checks the *reference*, not whether it was modified-and-restored. The fix is **`AtomicStampedReference`** (a reference + a monotonically-incrementing version stamp, so A-with-stamp-1 ≠ A-with-stamp-3) or `AtomicMarkableReference`. In garbage-collected Java the node-reuse form of ABA is less common than in C/C++ (the GC keeps the popped node alive so it won't be recycled while referenced), but it absolutely bites with object pools, recycled sentinels, or counter-style state — which is why naming ABA and the stamped-reference fix is the senior signal here.

#### Q109. [Coding] Implement a generic object pool with proper lifecycle and thread-safety. When is pooling worth it (and when is it an anti-pattern)?

**Problem:** Build a bounded pool that hands out reusable, expensive-to-create objects (DB connections, large buffers, parsers) and reclaims them. This tests resource lifecycle, blocking semantics, and *judgment* about when pooling helps.

```java
class ObjectPool<T> implements AutoCloseable {
    private final BlockingQueue<T> pool;
    private final Supplier<T> factory;
    private final Consumer<T> destroyer;            // called on shutdown / invalid objects

    ObjectPool(int size, Supplier<T> factory, Consumer<T> destroyer) {
        this.factory = factory; this.destroyer = destroyer;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) pool.add(factory.get());  // pre-create (eager)
    }
    T borrow(long timeout, TimeUnit unit) throws InterruptedException {
        T obj = pool.poll(timeout, unit);           // blocks up to timeout when exhausted
        if (obj == null) throw new IllegalStateException("pool exhausted");
        return obj;
    }
    void release(T obj) {
        if (obj == null) return;
        if (!pool.offer(obj)) destroyer.accept(obj); // pool full (double-release?) -> destroy
    }
    @Override public void close() {                 // drain and destroy every pooled object
        List<T> remaining = new ArrayList<>();
        pool.drainTo(remaining);
        remaining.forEach(destroyer);
    }
}
// Correct usage forces return even on exception:
T conn = pool.borrow(5, TimeUnit.SECONDS);
try { use(conn); } finally { pool.release(conn); }   // ALWAYS release in finally
```

**Design choices and why:** a bounded `BlockingQueue` gives both the size cap and the blocking-on-exhaustion semantics for free (the same primitive as Q104), with a **timeout** on `borrow` so a leak/overload surfaces as a fast, clear failure instead of an indefinite hang. The `finally`-release discipline is essential — a borrowed object not returned is a *pool leak* that eventually exhausts capacity (the in-app analogue of the connection leak in Q74), which is why production pools (HikariCP) add **leak detection** (warn if an object is held longer than a threshold) and **validation** (test an object before handing it out, discard-and-recreate if broken). 

**When pooling is worth it:** when object *creation* is genuinely expensive relative to use — TCP/TLS handshakes (DB and HTTP connections), large direct buffers, thread stacks. **When it's an anti-pattern:** for cheap-to-create objects, pooling is *slower* and adds bug surface — modern JVM allocation is a pointer bump in a TLAB (Q56) and escape analysis often elides short-lived objects entirely (Q25), so pooling ordinary objects fights the GC and the JIT rather than helping. The infamous example is **never pool virtual threads** (Q17/Q91) — they're cheap continuations, and pooling them reintroduces the very bottleneck they eliminate. The senior framing: pool the *scarce, expensive* resource (connections), measure before pooling anything else, and always pair a pool with leak detection and a borrow timeout.

#### Q110. [Coding] Define a custom annotation and process it at runtime with reflection (and contrast with annotation processing at compile time).

**Problem:** Create an annotation that marks methods to be timed, then a reflective wrapper that measures and logs their execution. This tests `RetentionPolicy`, `Target`, reflection mechanics, and awareness of the compile-time alternative.

```java
import java.lang.annotation.*;
import java.lang.reflect.*;

@Retention(RetentionPolicy.RUNTIME)        // MUST be RUNTIME to read via reflection
@Target(ElementType.METHOD)                 // only valid on methods
public @interface Timed {
    String label() default "";              // annotation elements: constant-only types
}

// A dynamic proxy that times any @Timed method on an interface-based service.
@SuppressWarnings("unchecked")
static <T> T timed(Class<T> iface, T target) {
    return (T) Proxy.newProxyInstance(
        iface.getClassLoader(), new Class<?>[]{iface},
        (proxy, method, args) -> {
            // Look up the annotation on the IMPLEMENTATION method, not the interface method:
            Method impl = target.getClass().getMethod(method.getName(), method.getParameterTypes());
            Timed t = impl.getAnnotation(Timed.class);
            if (t == null) return method.invoke(target, args);
            long start = System.nanoTime();
            try { return method.invoke(target, args); }
            finally {
                long us = (System.nanoTime() - start) / 1_000;
                System.out.printf("%s took %d us%n", t.label().isEmpty() ? method.getName() : t.label(), us);
            }
        });
}
```

**The mechanics that matter:** `@Retention(RUNTIME)` is mandatory — `SOURCE` retention (like `@Override`) is discarded by `javac`, and `CLASS` (the default) is in the bytecode but not loaded into the reflection API, so only `RUNTIME` annotations are visible to `getAnnotation`. Reflection here uses a **JDK dynamic proxy** (`Proxy.newProxyInstance`) to intercept calls — note the subtle bug it forces you to handle: annotations are looked up on the concrete *implementation* method, because interface methods don't carry the impl's annotations, and `InvocationHandler` exceptions arrive wrapped in `InvocationTargetException` (unwrap `getCause()` in real code). 

**Reflection vs compile-time processing — the trade-off interviewers want:** runtime reflection (this approach, and what Spring/Jackson/JUnit do) is flexible and dynamic but pays a **runtime cost** (reflective dispatch is slower than direct calls, though much improved by `MethodHandle`s) and defers errors to runtime. **Annotation processing** (`javax.annotation.processing.Processor`, run by `javac`) instead reads annotations at *compile time* and generates source/bytecode (this is how Lombok, MapStruct, Dagger, and Micronaut work) — zero runtime reflection cost, errors caught at compile time, and friendlier to GraalVM native-image (which struggles with reflection). The senior answer names both, picks reflection for dynamic/framework glue and compile-time processing for performance-critical or native-image-targeted code, and flags that heavy reflective frameworks are exactly what makes the Java-17 strong-encapsulation migration painful (Q83).

#### Q111. [Coding] Design and implement a retry mechanism with exponential backoff and jitter. What failure modes does naive retry create?

**Problem:** Wrap a flaky operation (a remote call) so transient failures are retried with increasing delays. This tests resilience-engineering judgment as much as code — naive retry is actively dangerous.

```java
static <T> T retry(Callable<T> op, int maxAttempts, long baseMs, long maxMs,
                   Predicate<Exception> retryable) throws Exception {
    long backoff = baseMs;
    Exception last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return op.call();
        } catch (Exception e) {
            last = e;
            if (!retryable.test(e) || attempt == maxAttempts) throw e;  // don't retry non-transient
            // full jitter: sleep a RANDOM amount in [0, backoff] to de-correlate clients
            long sleep = ThreadLocalRandom.current().nextLong(Math.min(maxMs, backoff) + 1);
            try { Thread.sleep(sleep); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();          // restore interrupt status
                throw ie;                                    // honor cancellation
            }
            backoff = Math.min(maxMs, backoff * 2);          // exponential, capped
        }
    }
    throw last;
}
```

**The failure modes naive retry creates — this is the real content of the question:** (1) **Retry storms / thundering herd** — if a dependency hiccups and thousands of clients all retry on the *same* fixed schedule, they synchronize into coordinated waves that hammer the recovering service and prevent it from recovering. **Jitter** (randomizing the delay) de-correlates clients; "full jitter" (random in `[0, backoff]`) is the AWS-recommended default. (2) **Retrying non-idempotent operations** — blindly retrying a `POST`/payment/charge can double-execute; you must only retry **idempotent** operations (or carry an idempotency key). (3) **Retrying non-transient errors** — retrying a `400 Bad Request` or `NullPointerException` is pointless and just adds latency; the `retryable` predicate must distinguish transient (timeout, `503`, connection reset) from permanent failures. (4) **Amplifying overload** — retries multiply load *exactly when the system is already struggling*, so retry must be paired with a **circuit breaker** (stop retrying entirely once failure rate crosses a threshold, e.g. Resilience4j) and a **total deadline/budget** so retries don't stack with upstream retries into exponential request amplification across a call chain.

**Other correctness points:** the `maxMs` cap prevents unbounded backoff; restoring the interrupt flag (`Thread.currentThread().interrupt()`) and propagating `InterruptedException` is essential so the retry is *cancellable* (silently swallowing interrupt is a classic bug). In production you'd use **Resilience4j** or Spring Retry rather than hand-rolling, and combine retry + backoff + jitter + circuit breaker + bulkhead + timeout as a layered resilience policy. The interviewer is checking whether you know that retry without jitter, idempotency awareness, and a circuit breaker is a *liability* that turns a brief blip into a cascading outage.

#### Q112. [Coding] Implement a thread-safe memoization cache for an expensive pure function, avoiding the double-computation race.

**Problem:** Cache the results of a slow, pure function keyed by argument, safe under concurrent access, *without* computing the same key twice when multiple threads request it simultaneously. This is a famous *Java Concurrency in Practice* exercise (Chapter 5).

```java
class Memoizer<K, V> {
    private final ConcurrentHashMap<K, Future<V>> cache = new ConcurrentHashMap<>();
    private final Function<K, V> compute;
    Memoizer(Function<K, V> compute) { this.compute = compute; }

    V get(K key) throws InterruptedException, ExecutionException {
        Future<V> f = cache.get(key);
        if (f == null) {
            FutureTask<V> task = new FutureTask<>(() -> compute.apply(key));
            f = cache.putIfAbsent(key, task);          // atomic: only ONE task wins per key
            if (f == null) { f = task; task.run(); }    // we won -> run the computation
        }
        try {
            return f.get();                             // everyone blocks on the SAME Future
        } catch (CancellationException e) {
            cache.remove(key, f);                       // don't cache a failed/cancelled result
            throw new IllegalStateException(e);
        }
    }
}
```

**The race this defeats:** the naive `if (cache.containsKey) return cache.get; else { V v = compute(); cache.put; }` has a check-then-act gap — two threads both miss, both compute the expensive function, and you've done the work twice (or more). Even `computeIfAbsent` *can* serialize correctly, but the **`Future`-based** approach is the canonical JCiP answer because it caches the *in-progress computation*, not just the finished value: the first thread inserts a `FutureTask` via the atomic `putIfAbsent` and runs it; every other thread that arrives mid-computation gets the *same* `Future` and **blocks on `f.get()`** rather than starting a duplicate computation. So the function runs **exactly once per key** even under a stampede. 

**Subtleties that separate a strong answer:** (1) **don't cache failures** — if the computation throws, you must `cache.remove(key, f)` so a transient error isn't memoized forever (the conditional two-arg `remove` ensures you only remove *your* failed future). (2) On modern Java, **`computeIfAbsent`** on a `ConcurrentHashMap` is simpler and also runs the mapping function at most once per key — *but* the function executes while holding the bin lock (Q44), so it must be short and must not re-enter the same map (deadlock); the `Future` approach avoids holding any map lock during the expensive computation, which is why it's preferred for genuinely slow functions. (3) For a production cache you'd reach for **Caffeine** (`AsyncLoadingCache`), which gives this single-flight guarantee plus eviction, refresh, and stats — never an unbounded raw map (Q23). The interview signal: recognizing the check-then-act race, knowing the `Future`-caching trick guarantees single computation, and not caching exceptions.

### 🔴 Expert — extended

#### Q113. [Coding] Implement a custom `Spliterator` so a non-collection source can be streamed *and parallelized* effectively.

**Problem:** Make a custom data source (say, a numeric range, or a tree) usable as a `Stream` that *actually parallelizes*. The naive `Spliterators.spliteratorUnknownSize(iterator, 0)` produces a stream that can't split, so `parallel()` does nothing (Q61) — the point is implementing `trySplit` correctly.

```java
// A splittable spliterator over a long range [start, end). Splits in half repeatedly,
// so the fork/join framework (Q45) can distribute work evenly across cores.
class RangeSpliterator implements Spliterator.OfLong {
    private long current;
    private final long end;
    private static final long THRESHOLD = 1024;     // stop splitting below this -> avoid over-fork

    RangeSpliterator(long start, long end) { this.current = start; this.end = end; }

    @Override public boolean tryAdvance(java.util.function.LongConsumer action) {
        if (current < end) { action.accept(current++); return true; }
        return false;
    }
    @Override public Spliterator.OfLong trySplit() {
        long remaining = end - current;
        if (remaining < THRESHOLD) return null;     // null = "don't split me further"
        long mid = current + remaining / 2;
        long lo = current;
        current = mid;                              // THIS spliterator keeps the upper half
        return new RangeSpliterator(lo, mid);       // return the lower half as a new spliterator
    }
    @Override public long estimateSize() { return end - current; }
    @Override public int characteristics() {
        return ORDERED | SIZED | SUBSIZED | NONNULL | IMMUTABLE | DISTINCT;
    }
}
// Stream it, in parallel:
LongStream s = StreamSupport.longStream(new RangeSpliterator(0, 10_000_000), /*parallel*/ true);
long sum = s.sum();
```

**What makes this parallelize well:** `trySplit` must hand back *roughly half* the remaining elements as a new spliterator while the current one retains the other half — balanced splits are what let the fork/join framework build a balanced task tree and keep all cores busy (Q45). The **characteristics** are load-bearing: `SIZED` + `SUBSIZED` (the size is known *and* both halves of a split are also exactly sized) let the framework pre-allocate result arrays and split cheaply; `IMMUTABLE` tells it the source won't change concurrently (so it needn't copy); `ORDERED`/`DISTINCT`/`NONNULL` enable downstream optimizations (e.g. `distinct()` becomes a no-op, `forEachOrdered` knows the order). A spliterator that returns `null` from `trySplit` (the `Iterator`-based default) or reports no useful characteristics produces a stream that *runs sequentially even with `parallel()`* — the silent reason "I added `.parallel()` and nothing got faster."

**The expert points:** (1) the `THRESHOLD` prevents over-splitting into tiny tasks whose fork/join overhead exceeds their work — too-fine splitting is as bad as no splitting. (2) For a *tree* source you'd split by handing one subtree to the new spliterator and keeping the rest, but you can't report `SIZED` (you don't know the count cheaply), so the framework splits more conservatively — which is exactly why `ArrayList`/arrays parallelize beautifully and `LinkedList`/`HashSet`/IO-backed sources don't (Q45, Q71). (3) This is precisely how `Arrays.spliterator`, `IntStream.range`, and `Collection.spliterator` are built, and implementing one is how you make *your* domain structure a first-class, parallelizable stream source. The signal is understanding that parallelism quality is a *property of the spliterator's split behavior and characteristics*, not of calling `.parallel()`.

#### Q114. [Coding] Design a high-throughput, bounded in-memory event bus (publish/subscribe) and walk through the concurrency and back-pressure decisions.

**Problem:** Build an in-process pub/sub bus where publishers emit events and multiple subscribers consume them, decoupled, with bounded memory and no lost events under load. This is an open-ended *design + implement* exercise probing concurrency primitives, back-pressure, and failure isolation.

```java
class EventBus<E> implements AutoCloseable {
    // One bounded queue per subscriber -> slow subscribers can't block fast ones or the publisher
    private final Map<Subscriber<E>, BlockingQueue<E>> subs = new ConcurrentHashMap<>();
    private final ExecutorService delivery = Executors.newVirtualThreadPerTaskExecutor(); // Java 21
    private volatile boolean running = true;

    interface Subscriber<E> { void onEvent(E e); }

    void subscribe(Subscriber<E> s, int queueCapacity) {
        BlockingQueue<E> q = new ArrayBlockingQueue<>(queueCapacity);
        subs.put(s, q);
        delivery.submit(() -> {                      // each subscriber drained on its own task
            while (running || !q.isEmpty()) {
                try {
                    E e = q.poll(100, TimeUnit.MILLISECONDS);
                    if (e != null) {
                        try { s.onEvent(e); }
                        catch (Exception ex) { /* isolate: a bad subscriber must not kill the bus */ }
                    }
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        });
    }
    // Back-pressure policy is the key design decision (see discussion):
    void publish(E event) {
        for (var q : subs.values()) {
            if (!q.offer(event)) {                   // non-blocking: queue full -> apply policy
                // policy options: DROP (here), block publisher, or evict-oldest:
                q.poll(); q.offer(event);            // evict-oldest (drop the stalest, keep newest)
            }
        }
    }
    @Override public void close() { running = false; delivery.shutdown(); }
}
```

**The decisions an interviewer wants you to reason through:** (1) **Per-subscriber queues, not one shared queue** — this isolates a slow subscriber so it can't stall fast ones or the publisher (the head-of-line-blocking problem); the cost is more memory and per-subscriber ordering only. (2) **Back-pressure policy** is *the* core design choice and there is no free lunch: **block the publisher** (`q.put`) gives lossless delivery but lets one slow subscriber stall the producer (back-pressure all the way up — correct for must-not-lose events); **drop newest** (`offer` returns false, discard) protects throughput but loses events; **evict-oldest** (shown) favors freshness (good for telemetry/price ticks where stale data is worthless). You must state the policy explicitly because it's a correctness/SLA decision, not an implementation detail. (3) **Failure isolation** — a subscriber that throws must be caught so it can't kill the delivery loop or other subscribers. (4) **Threading** — on Java 21, one **virtual thread per subscriber** (Q17/Q91) is ideal because delivery is mostly I/O/handler-bound and you may have many subscribers; a fixed platform-thread pool would either over-subscribe or head-of-line-block.

**The expert framing:** this is the in-memory shadow of a real message broker, and the honest senior answer flags the limits — an in-process bus loses everything on crash (no durability), provides no cross-process delivery, and its "bounded" guarantee is only as good as the back-pressure policy. For at-least-once delivery, ordering guarantees, or durability you'd reach for the **`java.util.concurrent.Flow`** API (the JDK's standard Reactive Streams interfaces, which standardize *back-pressure* via `Subscription.request(n)`), or an external broker (Kafka/Pulsar). The bounded queues, explicit overflow policy, subscriber isolation, and virtual-thread delivery are exactly the trade-offs (latency vs loss vs memory vs coupling) that distinguish a thought-through design from "just use a `List` of listeners."

#### Q115. [Coding] Walk through implementing `compareTo`/a `Comparator` for a complex sort (multi-key, nulls-last, stable), and explain stability and the TimSort contract.

**Problem:** Sort a list of records by multiple keys with mixed directions and null handling — e.g. employees by department ascending, then salary *descending*, then name ascending, with null departments sorted last. This tests `Comparator` composition, stability, and the contract that TimSort enforces (related to but distinct from Q40, which covered the *contract violation exception*; here the focus is *building* a correct complex comparator and stability).

```java
record Employee(String name, String dept, double salary) {}

Comparator<Employee> order =
    Comparator.comparing(Employee::dept,
                         Comparator.nullsLast(Comparator.naturalOrder()))   // nulls sort last
              .thenComparing(Comparator.comparingDouble(Employee::salary).reversed()) // salary DESC
              .thenComparing(Employee::name);                               // tiebreak: name ASC

employees.sort(order);   // List.sort -> TimSort, which is STABLE
```

**Why composition beats a hand-written `compare`:** the fluent `comparing/thenComparing/reversed/nullsLast` builders are **overflow-safe** (never `a - b`, Q40), read declaratively in priority order, and compose without the nested-`if`/`return` ladder that's so easy to get wrong (forgetting a tiebreaker, inverting a sign). Note the subtlety that **`reversed()` on a composed comparator reverses the *entire* preceding chain**, so to reverse only one key you apply `.reversed()` to *that key's* comparator (as above: `comparingDouble(...).reversed()` inside the `thenComparing`), not to the whole thing — a classic bug.

**Stability and the TimSort contract — the expert content:** a sort is **stable** if it preserves the relative order of elements that compare *equal*. `Collections.sort`/`List.sort`/`Arrays.sort(Object[])` use **TimSort** (a hybrid merge/insertion sort), which is **guaranteed stable** — this is why multi-key sorting *by successive stable sorts* works and why a tiebreaker chain produces deterministic output. (In contrast, `Arrays.sort(int[])` on primitives uses a dual-pivot quicksort, which is *not* stable — but stability is meaningless for primitives since equal ints are indistinguishable.) TimSort also **actively validates the comparator's contract** and throws `IllegalArgumentException: Comparison method violates its general contract!` if your comparator isn't a consistent total order (Q40) — so a complex multi-key comparator that's intransitive (e.g. mixing a partial order with a custom rule) will blow up at runtime; the composed-builder approach is contract-safe by construction. The senior points: build comparators by composition (safe, readable, priority-ordered), know that `reversed()` reverses the whole chain so scope it per-key, handle nulls explicitly with `nullsFirst/nullsLast` (the natural comparator NPEs on null), and rely on TimSort's *guaranteed stability* for layered/secondary sorting while respecting its contract enforcement.

#### Q116. [Coding] Implement a fixed-window and a sliding-window rate limiter, and explain why the token bucket is usually the better design.

**Problem:** Limit a caller to N requests per time window. The interviewer wants you to implement the simple windowed approaches, expose their flaws, and contrast with token/leaky bucket (complementing Q30, which designed a *distributed token-bucket*; here the focus is the *algorithmic comparison* of limiter strategies).

```java
// Fixed window: count requests per discrete window. Simple, but allows 2x burst at the seam.
class FixedWindowLimiter {
    private final int limit; private final long windowMs;
    private long windowStart = System.currentTimeMillis();
    private int count = 0;
    FixedWindowLimiter(int limit, long windowMs) { this.limit = limit; this.windowMs = windowMs; }

    synchronized boolean allow() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= windowMs) { windowStart = now; count = 0; } // new window resets
        if (count < limit) { count++; return true; }
        return false;
    }
}

// Token bucket: refill tokens at a steady rate up to a cap; each request spends one.
class TokenBucket {
    private final double ratePerMs;       // tokens added per millisecond
    private final double capacity;        // max burst
    private double tokens;
    private long lastRefillNanos = System.nanoTime();
    TokenBucket(double tokensPerSecond, double capacity) {
        this.ratePerMs = tokensPerSecond / 1000.0; this.capacity = capacity; this.tokens = capacity;
    }
    synchronized boolean allow() {
        long now = System.nanoTime();
        double elapsedMs = (now - lastRefillNanos) / 1_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedMs * ratePerMs);  // lazy refill on access
        lastRefillNanos = now;
        if (tokens >= 1.0) { tokens -= 1.0; return true; }
        return false;
    }
}
```

**The comparison — the substance of the answer:**

| Algorithm | Burst behavior | Memory | Flaw / strength |
|-----------|----------------|--------|-----------------|
| **Fixed window** | Allows **2× burst at the window boundary** (N at end of window 1 + N at start of window 2) | O(1) | Simplest; the seam burst is its fatal flaw for strict limits |
| **Sliding log** | Exact: track every request timestamp, count those within the trailing window | **O(N) per key** — unbounded memory under load | Most accurate, but memory cost makes it impractical at scale |
| **Sliding window counter** | Approximates the sliding window by weighting the previous window's count | O(1) | Good accuracy/memory trade-off; common in API gateways |
| **Token bucket** | Allows controlled bursts up to `capacity`, then steady rate | O(1) | Smooth, tunable burst, refills lazily — the usual default |
| **Leaky bucket** | No burst — drains at a *constant* rate (queue) | O(1)+queue | Enforces a strictly smooth output rate; adds latency |

**Why token bucket usually wins:** it's `O(1)` memory per key, refills **lazily on access** (no background timer thread per key — critical at millions of keys, Q30), and exposes the *two* knobs you actually want independently: the steady **rate** and the allowed **burst** (`capacity`). Fixed window's boundary-burst means a "100/min" limit can admit 200 requests in a 2-second span straddling the reset — unacceptable for protecting a fragile downstream. The sliding *log* is exact but its per-key memory grows with traffic (a DoS vector itself). **Production caveats:** all the single-node versions above are per-instance, so in a fleet you either accept N×limit aggregate or centralize state in Redis with an atomic Lua check-decrement (Q30); guard against **clock issues** (use a monotonic `System.nanoTime()` for elapsed, not wall-clock `currentTimeMillis()` which can jump backward on NTP correction — note the deliberate use of `nanoTime` in the token bucket), integer/double precision in the refill math, and evict idle buckets so the key map doesn't leak (Q23). The expert signal is knowing the boundary-burst flaw of fixed window, the memory flaw of the sliding log, and why token bucket's lazy refill + rate/burst separation makes it the pragmatic default.

#### Q117. [Coding] Implement a deep-copy of an arbitrary object graph (including cycles) and discuss the approaches and their pitfalls.

**Problem:** Produce a fully independent copy of a mutable object graph that may contain **shared references and cycles** (e.g. a graph/DAG, a doubly-linked structure, parent↔child back-references). Naive recursion infinite-loops on cycles and duplicates shared nodes — handling both is the test.

```java
// Cycle- and sharing-correct deep copy via an identity map of original -> copy.
static <T> T deepCopy(T root) {
    return copy(root, new IdentityHashMap<>());
}
@SuppressWarnings("unchecked")
private static <T> T copy(T obj, Map<Object, Object> seen) {
    if (obj == null) return null;
    if (seen.containsKey(obj)) return (T) seen.get(obj);   // already copied -> reuse (breaks cycles!)
    if (isImmutable(obj)) return obj;                      // String/Integer/etc.: share safely

    if (obj instanceof Object[] arr) {
        Object[] dup = arr.clone();                        // shallow first, to register before recursing
        seen.put(obj, dup);                                // REGISTER BEFORE recursing (cycle safety)
        for (int i = 0; i < dup.length; i++) dup[i] = copy(dup[i], seen);
        return (T) dup;
    }
    // ... similar handling for Collections/Maps, then reflective field copy for POJOs:
    // T dup = allocate(obj.getClass());  seen.put(obj, dup);  for each field: set(dup, copy(get(obj)));
    throw new UnsupportedOperationException("custom copy needed for " + obj.getClass());
}
```

**The two essential correctness ideas:** (1) An **`IdentityHashMap` of original→copy** (identity, not `equals`, because two logically-equal-but-distinct nodes must each get their own copy) — before recursing into a node's children, you **register the copy in the map first**, so when a cycle (or a second path to a shared node) leads back, you return the *already-created* copy instead of recursing forever or making a duplicate. This single map solves *both* cycles and structure-sharing. (2) **Don't copy immutables** — `String`, boxed numbers, `LocalDate`, records-of-immutables can be safely shared, which avoids pointless allocation and is *required* for things like interned strings.

**The approaches and their pitfalls — the discussion the interviewer wants:**
- **Manual copy constructors** (recursive, per type): fastest and explicit, but tedious and must each handle cycles via the shared map; the recommended approach for a *known* graph (Q59 — copy constructors over `clone`).
- **`Object.clone()`**: shallow by default and `final`-hostile (Q59); deep-cloning a graph with it is error-prone and doesn't handle cycles for free — avoid.
- **Serialization round-trip** (`ObjectOutputStream` → `ObjectInputStream`, or JSON via Jackson/Gson, or `SerializationUtils.clone`): the *simplest* general deep copy and it **handles cycles automatically** (Java serialization tracks back-references), but it's **slow** (reflection + stream overhead), requires everything to be `Serializable`, and **carries the security risk of native serialization** (Q29 — gadget-chain RCE if any untrusted data is involved); JSON-based cloning is safer but loses cycles and type fidelity unless configured.
- **Reflection-based generic deep copy** (sketched above): works for arbitrary POJOs but is fragile across `final` fields, `transient`, JDK internals locked by strong encapsulation (Q83), and is slow.

**The senior takeaway:** there is no perfect general deep copy — prefer **designing with immutability** so deep copies are *unnecessary* (immutable graphs can be shared freely, Q9/Q46), and when you genuinely need one, use **explicit copy constructors with a shared identity map** for known types, reserving serialization-based cloning for throwaway/non-security-sensitive cases while being aware of its cost and the deserialization attack surface.

#### Q118. [Behavioral] Tell me about a time you debugged a severe, intermittent production issue under pressure. How did you approach it? (STAR)

Frame this with **STAR** and make the *method* the star, because at senior/staff level the interviewer is assessing disciplined, hypothesis-driven debugging under pressure — not heroics or luck.

*Situation:* a payments service intermittently returned elevated latency and occasional `503`s for a few minutes, a handful of times a day, with no obvious pattern; p50 was healthy, so dashboards looked "mostly fine" while customers complained. *Task:* as the senior engineer on call I had to find and fix the root cause without a reliable reproduction and without taking the service down further. *Action:* I resisted the urge to "just restart it" or bump heap (the cargo-cult reflexes, Q90) and instead worked the evidence systematically. First I changed the lens from averages to a **latency histogram** (HdrHistogram), which revealed a fat p99.9 tail — the signature of periodic *stalls*, not slow code (Q84). Because the spikes were transient and gone before I could attach a profiler, I leaned on the **always-on JFR ring buffer** we'd pre-staged (Q88) and dumped the last 30 minutes after a spike. Overlaying the JFR timeline showed the latency spikes correlated precisely with **GC pauses** that lined up with a burst of allocation — and the allocation profile pointed at an **unbounded cache** that grew until a long mixed GC stalled every request thread (Q23/Q70). I confirmed the leak signature in the GC log (old-gen-after-collection trending up), took a heap dump, and used MAT's dominator tree to find the offending `Map`. *Result:* I replaced the raw map with a bounded Caffeine cache with eviction, the p99.9 tail collapsed, and I added a regression test asserting the cache stays bounded plus a runbook entry mapping "fat tail + GC correlation" to the diagnosis. 

The lessons I emphasize when telling this: **measure the right metric** (histogram, not average — averages hide the tail that pages you), **pre-stage evidence** so a transient is diagnosable after the fact rather than "please reproduce it," **form and test one hypothesis at a time** instead of changing five things, and **close the loop** with a test and a runbook so the same failure can't silently recur. The anti-pattern I explicitly call out is the pressure-driven "restart and hope" or "throw more memory at it," which masks symptoms and destroys the evidence you need.

#### Q119. [Behavioral] Describe a time you had to balance technical debt against delivery pressure, or push back on a deadline for engineering quality. (STAR)

Use **STAR**, and pitch it at the level being assessed: *judgment about when debt is acceptable versus dangerous*, and the ability to make that trade-off **explicit and data-backed** with stakeholders rather than either caving or stonewalling.

*Situation:* a team was about to ship a high-traffic feature on a hard external deadline, and a code review surfaced that the implementation used `double` for monetary aggregation and `SimpleDateFormat` shared across request threads — two latent correctness bombs (Q96, Q12/Q73). The pressure was real: marketing had committed the date publicly. *Task:* as the reviewer/tech lead I had to decide which issues were *blocking* (ship-stoppers) versus *acceptable, tracked debt*, and bring the team and the product owner along rather than unilaterally blocking the release. *Action:* I separated the issues by **blast radius and reversibility**. The `double`-for-money bug was **non-negotiable**: it produces silent, un-auditable financial errors that are nearly impossible to unwind after the fact and would fail compliance, so I blocked on switching to `BigDecimal` with `HALF_EVEN` rounding (Q96) — and I quantified it with a concrete example (`0.1 + 0.2 != 0.3` accumulating to a reconciliation failure) so it wasn't an opinion. The thread-unsafe `SimpleDateFormat` was a genuine concurrency corruption risk under load, also cheap to fix (swap to a `static final DateTimeFormatter`), so it stayed blocking too — but it was a one-line change, so fixing it cost nothing against the deadline. Other items the reviewer in me *wanted* (broader test coverage refactors, extracting a service) I explicitly **deferred as tracked tickets** with owners and a follow-up sprint commitment, documenting the decision so it wasn't silently forgotten. I wrote a short risk note distinguishing "correctness/security debt we fix now" from "design debt we schedule," and walked the product owner through it. *Result:* we shipped on time with the two correctness bugs fixed, the deferred work got real tickets and was completed the following sprint, and the product owner trusted the engineering pushback *because it came with a clear severity rationale and a plan*, not a blanket "we need more time."

The principle I emphasize: **not all tech debt is equal** — correctness and security debt that's silent, hard to reverse, or compliance-relevant is blocking; design/cleanliness debt is usually schedulable. The senior skill is making that distinction *explicitly and with evidence*, fixing the cheap-and-critical items immediately, and converting the rest into tracked, owned work — so "pushing back on the deadline" becomes a credible, narrow, justified ask rather than a reflexive demand for more time.

### 🟢 Basic — extended (continued)

#### Q120. [Coding] Count the frequency of each word in a sentence and return the most frequent one, handling ties deterministically.

**Problem:** Given a sentence, tokenize it, count word frequencies (case-insensitively), and return the most frequent word — breaking ties alphabetically so the result is deterministic. This warm-up tests `Map` idioms and the often-overlooked tie-breaking requirement.

```java
static String mostFrequentWord(String sentence) {
    if (sentence == null || sentence.isBlank()) return null;
    Map<String, Integer> freq = new HashMap<>();
    for (String w : sentence.toLowerCase().split("\\W+")) {  // split on non-word chars
        if (!w.isEmpty()) freq.merge(w, 1, Integer::sum);    // merge = "put or increment"
    }
    return freq.entrySet().stream()
        .max(Comparator.<Map.Entry<String,Integer>>comparingInt(Map.Entry::getValue)
                       .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
        .map(Map.Entry::getKey)
        .orElse(null);
}
```

**Why the details matter:** `Map.merge(key, 1, Integer::sum)` is the clean one-liner for the count-or-increment pattern — it avoids the verbose `getOrDefault`+`put` and the null-check dance, and it's the idiom interviewers want to see (it's also atomic on `ConcurrentHashMap`). The **tie-breaking** is the part candidates forget: `max` by frequency alone gives an *arbitrary* winner among ties (whichever the stream happens to encounter), which makes the function non-deterministic and untestable; chaining `.thenComparing(key, reverseOrder())` makes ties resolve to the alphabetically-first word deterministically (reverse order because `max` picks the largest, so we invert to make "a" beat "z"). **Edge cases:** null/blank input, punctuation and multiple spaces (the `\\W+` split with the empty-token guard handles leading delimiters that produce a leading empty string), case-folding (`toLowerCase` so "The" and "the" count together — clarify whether that's desired), and Unicode word boundaries (`\\W` is ASCII-centric; for full Unicode use `Pattern.UNICODE_CHARACTER_CLASS` or `BreakIterator`). The interviewer is checking the `merge` idiom and whether you make ties deterministic rather than leaving them to chance.

### 🟡 Intermediate — extended (continued)

#### Q121. [Coding] Implement a generic, fluent, type-safe builder-style validator chain (a mini fluent API) and explain method-chaining return-type design.

**Problem:** Build a small fluent validation API — `Validator.of(value).notNull().satisfies(predicate, msg).get()` — that accumulates checks and either returns the validated value or throws with a clear message. This tests generics, fluent-interface design, and the self-referential return-type question.

```java
public final class Validator<T> {
    private final T value;
    private final String name;
    private Validator(T value, String name) { this.value = value; this.name = name; }

    public static <T> Validator<T> of(T value, String name) { return new Validator<>(value, name); }

    public Validator<T> notNull() {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return this;                                   // return self -> chaining
    }
    public Validator<T> satisfies(Predicate<? super T> rule, String message) {
        if (value != null && !rule.test(value)) throw new IllegalArgumentException(name + ": " + message);
        return this;
    }
    public <R> Validator<R> map(Function<? super T, ? extends R> f, String mappedName) {
        return new Validator<>(value == null ? null : f.apply(value), mappedName);
    }
    public T get() { return value; }                   // terminal: extract the validated value
}
// Usage:
String email = Validator.of(input, "email")
    .notNull()
    .satisfies(s -> s.contains("@"), "must contain @")
    .satisfies(s -> s.length() <= 254, "too long")
    .get();
```

**Fluent-API design decisions the interviewer probes:** (1) **each intermediate method returns `this`** (or a new `Validator`) so calls chain — the hallmark of a fluent interface; a terminal method (`get`) ends the chain and extracts the result, exactly mirroring stream intermediate-vs-terminal operations (Q43). (2) **The self-type problem with inheritance**: if `Validator` were subclassed (e.g. `StringValidator extends Validator<String>` adding `.email()`), naively returning `Validator<T>` from `notNull()` would *downcast away* the subtype, breaking the chain (`.notNull().email()` won't compile). The classic fixes are the **recursive generic bound** (`class Builder<B extends Builder<B>>` with `@SuppressWarnings("unchecked") return (B) this;` — the "curiously recurring template pattern") or an abstract `self()` method each subclass overrides; this is a well-known interview deep-cut for fluent builders. (3) **Immutability and reuse**: returning `this` makes the validator single-use/mutable-feeling; returning a *new* instance (as `map` does) keeps it functional and reusable. **Trade-offs:** fluent APIs read beautifully and self-document, but they complicate inheritance (the self-type issue), can obscure where an exception originates in a long chain, and are harder to debug step-by-step than imperative code. The senior signal is naming the recursive-generic-bound trick for subclassable fluent builders and the intermediate-vs-terminal structure.

#### Q122. [Coding] Flatten a nested list/tree structure of arbitrary depth, both recursively and iteratively. Why prefer the iterative version in production?

**Problem:** Given a nested structure (e.g. `List<Object>` where elements are either values or further lists, or an n-ary tree), produce a flat list of all leaf values. This tests recursion, the recursion-to-iteration transformation, and stack-depth awareness.

```java
// Recursive — clean, but bounded by the call stack depth.
static void flattenRec(List<?> nested, List<Object> out) {
    for (Object e : nested) {
        if (e instanceof List<?> sub) flattenRec(sub, out);   // recurse into sublists
        else out.add(e);
    }
}

// Iterative — uses an explicit stack (a Deque), so depth is bounded by the HEAP, not the call stack.
static List<Object> flattenIter(List<?> nested) {
    List<Object> out = new ArrayList<>();
    Deque<Iterator<?>> stack = new ArrayDeque<>();
    stack.push(nested.iterator());
    while (!stack.isEmpty()) {
        Iterator<?> it = stack.peek();
        if (!it.hasNext()) { stack.pop(); continue; }         // exhausted this level -> pop
        Object e = it.next();
        if (e instanceof List<?> sub) stack.push(sub.iterator());  // descend
        else out.add(e);
    }
    return out;
}
```

**Why the iterative version is preferred for production / untrusted depth:** the recursive version consumes one JVM **call-stack frame per level of nesting**, so a deeply nested structure (e.g. 50,000 levels deep, or an adversarially nested JSON payload) throws **`StackOverflowError`** — and `StackOverflowError` is an `Error`, often *unrecoverable* and capable of leaving locks or state half-updated when it unwinds. The iterative version moves the "stack" onto the **heap** (an explicit `Deque`), so its depth limit is your (much larger) heap, not the fixed `-Xss` thread-stack size (Q67, default ~512 KB–1 MB). This is the canonical reason to convert recursion to iteration: **defending against `StackOverflowError` on attacker- or data-controlled depth** (deeply nested XML/JSON is a real DoS vector — many parsers cap nesting depth for exactly this reason). 

**The nuances worth raising:** (1) Java does **not** guarantee tail-call optimization (unlike Scala/functional JVMs), so even tail-recursive code still consumes stack — you cannot rely on the JIT to flatten recursion. (2) The recursive version is more readable and fine for *bounded, trusted* depth (a 3-level config tree); choose based on whether depth is bounded. (3) The iterative version preserves **left-to-right leaf order** here because `ArrayDeque` as a stack with `peek`/`next` on the current iterator processes each level in order — getting the order right (vs accidentally reversing) is a common iterative-conversion bug. The interview signal: recognizing that "recursion depth = stack depth = `StackOverflowError` risk," that Java lacks TCO, and that explicit-stack iteration trades elegance for robustness against unbounded input.

#### Q123. [Coding] Parse and evaluate a simple arithmetic expression with operator precedence (e.g. `"3 + 4 * 2 - 1"`). Describe the approach.

**Problem:** Evaluate an infix arithmetic string respecting precedence (`*`/`/` before `+`/`-`) without using `eval`/scripting. This is a classic that tests the two-stack (shunting-yard-style) algorithm or recursive-descent parsing.

```java
// Two-stack evaluator (Dijkstra's shunting-yard, evaluated in place): O(n), handles precedence.
static int eval(String expr) {
    Deque<Integer> nums = new ArrayDeque<>();
    Deque<Character> ops = new ArrayDeque<>();
    int i = 0, n = expr.length();
    while (i < n) {
        char c = expr.charAt(i);
        if (Character.isDigit(c)) {
            int num = 0;
            while (i < n && Character.isDigit(expr.charAt(i)))   // multi-digit number
                num = num * 10 + (expr.charAt(i++) - '0');
            nums.push(num);
            continue;
        }
        if (c == ' ') { i++; continue; }
        // operator: first apply all already-stacked ops of >= precedence (left-assoc)
        while (!ops.isEmpty() && precedence(ops.peek()) >= precedence(c)) applyTop(nums, ops);
        ops.push(c);
        i++;
    }
    while (!ops.isEmpty()) applyTop(nums, ops);                  // drain remaining ops
    return nums.pop();
}
static int precedence(char op) { return (op == '*' || op == '/') ? 2 : 1; }
static void applyTop(Deque<Integer> nums, Deque<Character> ops) {
    int b = nums.pop(), a = nums.pop(); char op = ops.pop();
    nums.push(switch (op) {
        case '+' -> a + b; case '-' -> a - b;
        case '*' -> a * b; case '/' -> a / b;
        default -> throw new IllegalArgumentException("bad op " + op);
    });
}
```

**The core idea — operator precedence via the stacks:** when you encounter an operator, you first **apply any operators already on the stack with greater-or-equal precedence** before pushing the new one. That single rule is what makes `3 + 4 * 2` evaluate the `*` first (when `-`/end arrives, `*` is applied before `+`) — precedence falls out of the "apply higher-or-equal-precedence ops first" comparison, and using `>=` (not `>`) gives **left-associativity** for same-precedence operators (`8 - 3 - 2 = 3`, not `7`). The two-stack approach is `O(n)` time and space. The **alternative is recursive-descent parsing** — a grammar `expr := term (('+'|'-') term)*`, `term := factor (('*'|'/') factor)*`, `factor := number | '(' expr ')'` — where precedence is encoded in the *grammar structure* (lower-precedence rules call higher-precedence rules); it's cleaner for extending to parentheses, unary minus, functions, and right-associative operators like exponentiation, and it's what real parsers/compilers use. **Edge cases and robustness:** multi-digit numbers (the inner digit loop), whitespace, parentheses (push `(` and apply until the matching `)`), division by zero (`ArithmeticException`), unary minus, and *malformed input* (interviewers love feeding `"3 + + 4"` or `")"`). The senior framing: name both the shunting-yard two-stack method and recursive descent, explain that precedence/associativity is the crux, and note that for anything beyond a toy you'd use a parser generator or a vetted expression library rather than hand-rolling — and never `ScriptEngine.eval` on untrusted input (code-injection risk).

#### Q124. [Coding] Detect whether a linked list (or object reference chain) has a cycle, in O(1) space. Explain Floyd's algorithm.

**Problem:** Given the head of a singly-linked list, determine whether it contains a cycle — and ideally find the cycle's start — using constant extra space. This is the canonical "two-pointer" / Floyd's tortoise-and-hare exercise.

```java
static class Node { int val; Node next; Node(int v) { val = v; } }

// Floyd's cycle detection: O(n) time, O(1) space.
static boolean hasCycle(Node head) {
    Node slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;          // moves 1 step
        fast = fast.next.next;     // moves 2 steps
        if (slow == fast) return true;   // they meet INSIDE a cycle
    }
    return false;                  // fast reached the end -> no cycle
}

// Find the START of the cycle (the elegant part):
static Node cycleStart(Node head) {
    Node slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next; fast = fast.next.next;
        if (slow == fast) {                       // meeting point found
            Node p = head;
            while (p != slow) { p = p.next; slow = slow.next; }  // both move 1 step
            return p;                             // they meet at the cycle's start
        }
    }
    return null;
}
```

**Why it works (the insight interviewers want):** two pointers move at different speeds — `slow` one node per step, `fast` two. If there's no cycle, `fast` runs off the end (`null`) and you return false. If there *is* a cycle, `fast` enters it first and "laps" `slow`; because the speed difference is exactly 1, `fast` closes the gap by one node each step and is *guaranteed* to land on `slow` eventually (they can't "jump over" each other with a unit gap). That guarantee is the non-obvious part — a faster ratio (3×) might skip past. **Finding the cycle start** uses a number-theory fact: if the meeting point is `k` nodes into the cycle and the cycle has length `L`, the distance from `head` to the cycle start equals the distance from the meeting point to the cycle start (mod `L`), so resetting one pointer to `head` and advancing both at the *same* speed makes them meet exactly at the entry — clean `O(1)` space.

**The space trade-off and generalization:** the obvious alternative is a **`HashSet<Node>` of visited nodes** — `O(n)` time but `O(n)` space; Floyd's achieves the same in `O(1)` space, which is the whole point of the question. This pattern generalizes beyond linked lists: it detects cycles in any "successor function" — `f(x) = next(x)` — including **functional iteration** (`x → f(x)`, used to find cycles in pseudo-random sequences, hash chains, and Pollard's rho factorization), and it's relevant in Java for detecting **reference cycles** that would defeat naive recursion (the deep-copy and flatten exercises, Q117/Q122, both must handle cycles — there an identity set is usually clearer, but Floyd's is the `O(1)`-space tool when you only need *detection*). The senior signal is articulating *why* the unit-speed-gap guarantees a meeting and knowing the `HashSet` time/space alternative.

### 🟠 Advanced — extended (continued)

#### Q125. [Coding] Implement a `ReadWriteLock`-protected cache and explain when a read-write lock helps versus hurts (and what `StampedLock` adds).

**Problem:** Build a cache that allows many concurrent readers but exclusive writers, and reason about when that's actually faster than a plain lock. This tests `ReadWriteLock`, lock-upgrade hazards, and the optimistic-read capability of `StampedLock`.

```java
class RWCache<K, V> {
    private final Map<K, V> map = new HashMap<>();          // guarded by the lock
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    V get(K key) {
        lock.readLock().lock();                              // many readers concurrently
        try { return map.get(key); }
        finally { lock.readLock().unlock(); }
    }
    void put(K key, V value) {
        lock.writeLock().lock();                             // exclusive
        try { map.put(key, value); }
        finally { lock.writeLock().unlock(); }
    }
    V computeIfAbsent(K key, Function<K, V> f) {
        lock.readLock().lock();
        try { V v = map.get(key); if (v != null) return v; } // fast path under read lock
        finally { lock.readLock().unlock(); }
        lock.writeLock().lock();                              // MUST release read before acquiring write
        try {
            return map.computeIfAbsent(key, f);              // re-check under write lock (another thread may have inserted)
        } finally { lock.writeLock().unlock(); }
    }
}
```

**When a read-write lock helps vs hurts:** `ReentrantReadWriteLock` allows unlimited *concurrent readers* but gives writers exclusive access, so it shines for **read-heavy, write-rare** data where the protected operation is **non-trivially long** (the concurrency among readers outweighs the lock's bookkeeping). It *hurts* when (1) the critical section is **very short** — the read-write lock's internal accounting (reader counts, fairness) costs more than a plain `synchronized`/`ReentrantLock` would for a quick `map.get`; (2) writes are **frequent** — writers block all readers and vice-versa, so contention degrades to worse-than-a-simple-lock; and (3) the obvious footgun: a `ReentrantReadWriteLock` **does not support lock upgrade** (acquiring the write lock while holding the read lock **deadlocks**), so the `computeIfAbsent` above must *release* the read lock before taking the write lock and then *re-check* (another thread may have inserted in the gap) — a classic mistake. For a concurrent cache, **`ConcurrentHashMap` (Q44) is almost always the better answer** than rolling your own RW-locked map, because its per-bin locking + lock-free reads scale better than a single RW lock and avoid the upgrade hazard entirely.

**What `StampedLock` (Java 8) adds:** it provides an **optimistic read** mode — `tryOptimisticRead()` returns a stamp *without acquiring any lock*, you read the fields, then `validate(stamp)` checks whether a writer intervened; if not, you got a lock-free read, and only if validation fails do you fall back to a real read lock. For read-dominated data this beats `ReentrantReadWriteLock` because the common case touches *no* lock state at all. `StampedLock` also supports lock *conversion* (`tryConvertToWriteLock`), which RW-lock lacks. The caveats: `StampedLock` is **not reentrant** (re-locking the same lock deadlocks), and its optimistic reads must read into locals and validate *before* using them (reading a torn/inconsistent value mid-write is possible until you validate). The expert framing: read-write locks pay off only for genuinely read-heavy, longer critical sections; for short ops they lose to a plain lock; for a *map* specifically prefer `ConcurrentHashMap`; and `StampedLock`'s optimistic mode is the high-performance option when you can write the validate-then-use discipline correctly.

#### Q126. [Coding] Implement a bounded LRU cache that is fully thread-safe and explain why `LinkedHashMap`'s access-order mode is not safe for concurrent reads.

**Problem:** Build an LRU cache safe for concurrent `get`/`put`. This deliberately revisits the LRU mechanics of Q10 but pivots to the *concurrency* angle — the subtle "reads mutate state" hazard that makes naive synchronization wrong and a `ConcurrentHashMap` alone insufficient.

```java
// Simple correct version: synchronize a LinkedHashMap in access-order. Correct but coarse-grained.
class SyncLRU<K, V> {
    private final LinkedHashMap<K, V> map;
    SyncLRU(int capacity) {
        map = new LinkedHashMap<>(16, 0.75f, true) {        // access-order = true
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> e) { return size() > capacity; }
        };
    }
    // BOTH get and put must be synchronized — because in access-order mode, get() MUTATES the map.
    synchronized V get(K key) { return map.get(key); }
    synchronized void put(K key, V value) { map.put(key, value); }
}
```

**Why `LinkedHashMap` access-order reads are not concurrency-safe — the crux:** in access-order mode, **`get()` structurally modifies the list** by moving the accessed entry to the most-recently-used end. This means a "read" is actually a *write* to the internal linked list, so concurrent `get`s from multiple threads race on the same pointers and can corrupt the list or throw `ConcurrentModificationException` — even though intuitively "reads should be safe to parallelize." This is why you **cannot** wrap an access-order `LinkedHashMap` in `Collections.synchronizedMap` and expect concurrent reads to be safe (the synchronized wrapper synchronizes each call, which *does* protect it — but a `ReadWriteLock` allowing concurrent readers would be *wrong* here, because the readers mutate). You must serialize *every* operation including `get` (as above), which makes the cache a single-lock bottleneck.

**The production answer — don't hand-roll a concurrent LRU:** for real concurrency you want **Caffeine** (or Guava's `Cache`), which achieves high-throughput LRU/LFU eviction *without* a global lock by decoupling reads from eviction bookkeeping — reads are recorded in per-thread **ring buffers** and the access order is reconstructed asynchronously (the "TinyLFU" admission policy), so concurrent reads don't contend on a shared list at all. That's the architectural insight: the reason a naive concurrent LRU is slow (every read mutates shared recency state) is *exactly* what Caffeine engineers away by batching/deferring the recency updates. If you must build one, the scalable approach is a `ConcurrentHashMap` for the entries plus an *approximate*, lock-free recency mechanism (sampling, second-chance/CLOCK, or striped LRU lists) — accepting that perfect LRU ordering under concurrency inherently requires synchronizing reads, so production caches deliberately approximate. The expert signal: knowing that LRU's "touch on read" makes reads into writes (so RW-locks and lock-free reads don't trivially apply), and that the way out is *approximating* recency to decouple reads from the eviction structure — which is precisely Caffeine's design.

#### Q127. [Coding] Implement a `CountDownLatch`-style and a `CyclicBarrier`-style coordinator from scratch, and contrast the `java.util.concurrent` synchronizers.

**Problem:** Coordinate a set of threads so that (a) some threads wait until N events complete (latch), or (b) a fixed set of threads all rendezvous at a barrier before any proceeds. Building simplified versions tests `wait`/`notify` and AQS awareness, and the comparison tests breadth across `java.util.concurrent`.

```java
// CountDownLatch from scratch: threads await() until count reaches zero. ONE-SHOT.
class SimpleLatch {
    private int count;
    SimpleLatch(int count) { this.count = count; }
    synchronized void countDown() {
        if (count > 0 && --count == 0) notifyAll();   // release ALL waiters when it hits zero
    }
    synchronized void await() throws InterruptedException {
        while (count > 0) wait();                       // guarded wait (while-loop, Q104)
    }
}

// CyclicBarrier from scratch: N threads rendezvous, then the barrier RESETS for reuse.
class SimpleBarrier {
    private final int parties;
    private int waiting = 0;
    private int generation = 0;                         // distinguishes barrier "cycles"
    SimpleBarrier(int parties) { this.parties = parties; }
    synchronized void await() throws InterruptedException {
        int gen = generation;
        if (++waiting == parties) {                     // last party trips the barrier
            waiting = 0; generation++; notifyAll();      // open the gate AND start a new generation
        } else {
            while (gen == generation) wait();            // wait until THIS generation is tripped
        }
    }
}
```

**The defining difference — one-shot vs cyclic:** a **`CountDownLatch` is single-use** — once the count hits zero it stays open forever; you can't reset it (the `generation` concept doesn't exist). A **`CyclicBarrier` is reusable** — after all parties arrive it automatically *resets* for the next round, which is why the from-scratch version needs the **`generation` counter**: it lets a thread tell "the barrier I'm waiting on" apart from "a fresh cycle that already started," preventing a thread from being stranded waiting on an already-tripped generation. Also note the *symmetry difference*: a latch has distinct roles (some threads `countDown`, others `await`), while a barrier is symmetric (every party calls `await` and all are mutual).

| Synchronizer | Purpose | Reusable? | Key feature |
|--------------|---------|-----------|-------------|
| `CountDownLatch` | wait for N events to complete | **No** (one-shot) | distinct counter-down vs await roles |
| `CyclicBarrier` | N threads rendezvous repeatedly | **Yes** (auto-reset) | optional barrier action run on trip; breaks all on timeout/interrupt |
| `Semaphore` | limit concurrent access to N permits | Yes | `acquire`/`release`; counting permits, fairness option |
| `Phaser` | dynamic, multi-phase barrier | Yes | parties can register/deregister between phases |
| `Exchanger` | two threads swap objects | Yes | pairwise hand-off |

**The production points:** the real `java.util.concurrent` synchronizers are built on **AbstractQueuedSynchronizer (AQS)** — a framework providing a FIFO wait queue, atomic state via CAS, and `acquire`/`release` semantics — so they're far more efficient and feature-rich (timeouts, interruptibility, fairness) than a `wait`/`notify` hand-roll; you'd *never* ship the from-scratch versions, but building them demonstrates you understand the guarded-wait discipline and the one-shot-vs-cyclic distinction. The selection guidance: `CountDownLatch` for "start N workers, main thread waits for all to finish" or "hold workers until a start signal"; `CyclicBarrier` for iterative parallel algorithms where all threads must finish phase K before any starts K+1; `Phaser` when the number of parties changes between phases; `Semaphore` for resource-permit limiting (Q91's "bound the scarce resource"). On Java 21, for fan-out/fan-in you'd often reach for **structured concurrency** (Q18) instead of a latch, since it scopes and joins subtasks automatically.

#### Q128. [Coding] Implement a simple dependency-injection container (resolve and wire singletons by type) using reflection, and discuss the trade-offs versus compile-time DI.

**Problem:** Build a minimal IoC container that, given a class, instantiates it and recursively resolves its constructor dependencies as singletons. This tests reflection, the dependency graph, and the runtime-vs-compile-time DI debate (Spring vs Dagger/Micronaut).

```java
class MiniContainer {
    private final Map<Class<?>, Object> singletons = new ConcurrentHashMap<>();
    private final Set<Class<?>> resolving = ConcurrentHashMap.newKeySet();  // cycle guard

    @SuppressWarnings("unchecked")
    <T> T resolve(Class<T> type) {
        Object existing = singletons.get(type);
        if (existing != null) return (T) existing;          // return the cached singleton
        if (!resolving.add(type))                            // already on the current resolution path?
            throw new IllegalStateException("circular dependency involving " + type.getName());
        try {
            Constructor<?> ctor = pickConstructor(type);     // e.g. the @Inject one, or the longest
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++)
                args[i] = resolve(paramTypes[i]);            // recursively resolve dependencies
            Object instance = ctor.newInstance(args);
            Object prev = singletons.putIfAbsent(type, instance);  // race-safe publish
            return (T) (prev != null ? prev : instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot construct " + type, e);
        } finally {
            resolving.remove(type);
        }
    }
    private Constructor<?> pickConstructor(Class<?> type) throws NoSuchMethodException {
        // simplest policy: a single public constructor; real containers honor @Inject / @Autowired
        Constructor<?>[] ctors = type.getConstructors();
        if (ctors.length != 1) throw new IllegalStateException("ambiguous constructors for " + type);
        return ctors[0];
    }
}
```

**The mechanics:** the container does **constructor injection** by reflection — pick a constructor, recursively `resolve` each parameter type (depth-first through the dependency graph), instantiate, and **cache as a singleton**. The two essential pieces of robustness are (1) the **circular-dependency guard** (a `resolving` set tracking the current resolution path — without it, `A→B→A` recurses until `StackOverflowError`, Q122) which detects the cycle and fails with a clear message, and (2) **thread-safe singleton publication** (`putIfAbsent` so two threads resolving the same type concurrently don't create two instances and don't publish a half-built object, echoing the singleton/visibility concerns of Q89). This is, in miniature, what Spring's `BeanFactory` does — though Spring adds scopes, lifecycle callbacks, AOP proxies, qualifier resolution, and field/setter injection.

**Runtime reflection DI vs compile-time DI — the trade-off the interviewer wants:** *runtime* containers (this approach, **Spring**) resolve the graph when the app starts by reflecting over classes and annotations (Q110). Pros: maximally flexible (conditional beans, runtime configuration, dynamic proxies for AOP/transactions), and the dominant ecosystem. Cons: **slower startup** (reflective scanning of the whole classpath), **errors surface at runtime** (a missing or ambiguous dependency blows up on boot, not at compile time), higher memory, and **poor fit for GraalVM native-image** (reflection must be explicitly registered). *Compile-time* DI (**Dagger**, **Micronaut**, **Quarkus**) instead generates the wiring code at build time via annotation processors (Q110): the dependency graph is validated **at compile time** (a missing binding fails the build), there's **near-zero reflection** so startup is fast and native-image-friendly, and the generated code is debuggable. Cons: less runtime dynamism, longer build times, and you can't reconfigure the graph without recompiling. The senior framing: Spring/runtime-reflection DI optimizes for *flexibility and ecosystem*; Dagger/Micronaut/compile-time DI optimizes for *startup speed, fail-fast validation, and native-image* — which is exactly why the serverless/native-image trend (Q79) has pushed compile-time DI into prominence. And note: a hand-rolled container is a teaching exercise — never reinvent Spring, but understanding constructor injection + singleton caching + cycle detection demystifies what the framework does.

### 🔴 Expert — extended (continued)

#### Q129. [Coding] Implement a non-blocking, lock-free single-producer/single-consumer (SPSC) ring buffer and explain the memory-ordering and false-sharing concerns.

**Problem:** Build a fixed-size ring buffer for one producer thread and one consumer thread that needs *no locks* — the highest-performance hand-off primitive, used in low-latency systems (the LMAX Disruptor is the famous example). This tests memory ordering, the power-of-two trick, and microarchitectural awareness.

```java
class SpscRingBuffer<T> {
    private final T[] buffer;
    private final int mask;                          // capacity - 1, for fast modulo
    // head (consumer) and tail (producer) are volatile for cross-thread visibility:
    private volatile long head = 0;                  // next index to READ
    private volatile long tail = 0;                  // next index to WRITE

    @SuppressWarnings("unchecked")
    SpscRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1) throw new IllegalArgumentException("capacity must be power of 2");
        buffer = (T[]) new Object[capacity];
        mask = capacity - 1;
    }
    // PRODUCER thread only:
    boolean offer(T item) {
        long t = tail;
        if (t - head >= buffer.length) return false; // full (read head once; volatile read)
        buffer[(int) (t & mask)] = item;             // write slot...
        tail = t + 1;                                // ...THEN publish via volatile write (release)
        return true;
    }
    // CONSUMER thread only:
    T poll() {
        long h = head;
        if (h >= tail) return null;                  // empty (volatile read of tail = acquire)
        T item = buffer[(int) (h & mask)];           // read slot AFTER seeing tail advanced
        buffer[(int) (h & mask)] = null;             // null out to allow GC
        head = h + 1;                                // publish consumption
        return item;
    }
}
```

**The memory-ordering correctness (the heart of the question):** there are no locks, so correctness rests entirely on the **`volatile` head/tail** establishing happens-before edges (Q16/Q46). The producer writes the data into the slot and *then* does a `volatile` write to `tail`; the consumer does a `volatile` read of `tail` and only proceeds if it has advanced. The JMM guarantees the `volatile` write of `tail` **happens-before** the consumer's `volatile` read that observes it, which transitively makes the *slot write* visible to the consumer before it reads the slot — this is the **release/acquire** pattern, the same one underpinning safe publication. Reversing the order (publish `tail` before writing the slot) would let the consumer read a stale/empty slot — the textbook ordering bug. Single-producer/single-consumer is what makes it lock-free: exactly one thread writes `tail` and one writes `head`, so there's no CAS contention at all, just `volatile` reads/writes.

**Performance and microarchitecture — the expert layer:** (1) **power-of-two capacity** lets `index & mask` replace the expensive `%` modulo (the same trick `HashMap` uses, Q65). (2) **False sharing** (Q31) is the killer here: `head` and `tail` are almost certainly on the **same 64-byte cache line**, so the producer writing `tail` and the consumer writing `head` invalidate each other's cache line on every operation, ping-ponging it between cores and destroying throughput *even though they touch different variables*. The fix is **padding** `head` and `tail` onto separate cache lines (via `@jdk.internal.vm.annotation.Contended`, or manual long-padding fields) — this is exactly what the LMAX Disruptor and JCTools' SPSC queues do, and it's the reason a "correct" lock-free buffer can still be slow. (3) A further optimization caches the opposite index locally to avoid a `volatile` read on every call (the producer caches `head`, refreshing only when it appears full). The senior signal: explaining that lock-freedom here comes from the SPSC restriction + `volatile` release/acquire ordering (not CAS), that the publish-after-write ordering is load-bearing for correctness, and that **false sharing between head and tail** is the dominant performance concern requiring cache-line padding — the difference between a textbook ring buffer and a Disruptor-grade one.

#### Q130. [Coding] Design a generic, thread-safe `@Memoize`-style caching layer with TTL, max-size eviction, and refresh-ahead — then explain why you'd use Caffeine instead.

**Problem:** Design (interface + the core mechanics) a production-grade caching layer: bounded size with eviction, per-entry time-to-live, and async refresh-ahead so hot entries never serve stale data. This is a *design* exercise probing eviction policy, expiry, concurrency, and the build-vs-buy judgment.

```java
interface Cache<K, V> {
    V get(K key, Function<K, V> loader);     // load-through on miss (single-flight, Q112)
    void put(K key, V value);
    void invalidate(K key);
    CacheStats stats();                       // hit rate, evictions, load time — observability is mandatory
}

// Core mechanics sketch (what a real cache must coordinate):
//   entries:    ConcurrentHashMap<K, Entry<V>>  where Entry holds value + writeNanos + accessNanos
//   single-flight load:  CompletableFuture<V> per key so a stampede triggers ONE load (Q112)
//   TTL expiry: on read, if now - writeNanos > ttl -> treat as miss (expire-after-write)
//   max-size eviction: when size > max, evict per policy (LRU / LFU / TinyLFU)
//   refresh-ahead: if now - writeNanos > refreshAfter (< ttl) -> serve current value,
//                  trigger ASYNC reload in the background so the next reader gets fresh data
```

**The design decisions and their trade-offs:** (1) **Eviction policy** — pure **LRU** is simple but vulnerable to scans (a one-time bulk read evicts your hot set); **LFU** tracks frequency but needs aging to forget old popularity; **TinyLFU/W-TinyLFU** (Caffeine's policy) combines a frequency sketch with a small LRU admission window and consistently beats both on real workloads. The choice is a hit-rate-vs-complexity trade-off. (2) **Expiry semantics** must be explicit: *expire-after-write* (TTL from insertion — good for data with a known freshness window) vs *expire-after-access* (idle eviction — good for session-like data) — they answer different questions and you often want both. (3) **Refresh-ahead** is the subtle one: rather than expire-then-block-the-next-reader on a slow reload (a latency spike + potential stampede), you reload *asynchronously* shortly *before* expiry, serving the slightly-stale value meanwhile — trading a tiny staleness window for eliminated tail-latency on cache misses. (4) **Single-flight loading** (Q112) so a cache miss on a hot key under load triggers exactly one loader call, not thousands. (5) **Bounded size is non-negotiable** — an unbounded cache is just a memory leak with extra steps (Q23).

**Why use Caffeine instead of building this — the honest senior answer:** every one of the above is genuinely hard to get right *and fast* concurrently. **Caffeine** (the successor to Guava Cache, by Ben Manes) provides all of it: W-TinyLFU eviction with near-optimal hit rates, `expireAfterWrite`/`expireAfterAccess`/`refreshAfterWrite`, async/`CompletableFuture`-based `AsyncLoadingCache` with built-in single-flight, weak/soft reference options, and detailed `stats()` — and crucially it does the recency/frequency bookkeeping **off the read path** (per-thread ring buffers replayed asynchronously, Q126) so reads don't contend, which a hand-rolled cache that updates shared LRU state on every `get` cannot match. Building your own means reinventing concurrent eviction, getting the expiry/refresh races right, and *still* being slower. The legitimate reasons to build are: a genuinely novel eviction policy, a hard no-dependencies constraint, or a learning exercise. The expert framing: name the eviction-policy spectrum (LRU→LFU→TinyLFU) and *why* TinyLFU wins, distinguish the expiry modes, explain refresh-ahead's latency benefit, insist on bounded size + stats + single-flight, and conclude that the correct production move is Caffeine — demonstrating both that you *understand* the mechanics and that you know not to reinvent them.

#### Q131. [Coding] Implement a backpressure-aware producer/consumer pipeline using the `java.util.concurrent.Flow` (Reactive Streams) API, and explain what backpressure actually solves.

**Problem:** Wire a publisher to a subscriber where the *consumer* controls the rate, so a fast producer cannot overwhelm a slow consumer or exhaust memory. This tests the JDK's standardized Reactive Streams interfaces (`Flow`, Java 9) and the demand-driven backpressure model.

```java
import java.util.concurrent.Flow.*;
import java.util.concurrent.SubmissionPublisher;

// A subscriber that pulls one item at a time, requesting more only after processing.
class SlowSubscriber<T> implements Subscriber<T> {
    private Subscription subscription;
    @Override public void onSubscribe(Subscription s) {
        this.subscription = s;
        s.request(1);                          // DEMAND: ask for exactly one item to start
    }
    @Override public void onNext(T item) {
        process(item);                         // do slow work...
        subscription.request(1);               // ...THEN ask for the next -> consumer-paced
    }
    @Override public void onError(Throwable t) { t.printStackTrace(); }
    @Override public void onComplete() { System.out.println("done"); }
    private void process(T item) { /* slow I/O or CPU */ }
}

// Producer side: SubmissionPublisher buffers and respects each subscriber's demand.
try (SubmissionPublisher<Integer> pub = new SubmissionPublisher<>()) {
    pub.subscribe(new SlowSubscriber<>());
    for (int i = 0; i < 1_000_000; i++) pub.submit(i);   // submit() BLOCKS when the buffer is full
}   // close() waits for delivery to drain
```

**What backpressure actually solves — the core concept:** in a naive push model, a fast producer hands items to a slow consumer as fast as it can, and the excess must go *somewhere* — an unbounded queue (→ `OutOfMemoryError`, Q23/Q104), a bounded queue that drops data, or a blocked producer. **Backpressure** makes the *consumer signal its capacity* via `Subscription.request(n)` (the "demand"): the publisher may only emit up to the outstanding demand, so the consumer's rate governs the producer's rate end-to-end, bounding memory *by design* without an arbitrary queue size. This is the fundamental contribution of **Reactive Streams** (the spec standardized in the JDK as `java.util.concurrent.Flow` — four nested interfaces `Publisher`/`Subscriber`/`Subscription`/`Processor`), and it's why "reactive" is really "*backpressured async streams*," not just "callbacks." In the code, the subscriber requests `1`, processes, then requests `1` more — strict one-at-a-time pacing; a faster subscriber might `request(64)` and refill in batches to balance throughput against latency.

**The trade-offs and the modern context — the expert layer:** `SubmissionPublisher` is the JDK's reference publisher; it has its own bounded buffer and `submit()` *blocks* the producer when that buffer fills against a slow subscriber's demand — which *is* backpressure propagating upstream. Real applications use **Project Reactor** (`Flux`/`Mono`, Spring WebFlux) or **RxJava**, which implement `Flow` and add a rich operator vocabulary (`map`, `flatMap`, `buffer`, `onBackpressureDrop/Buffer/Latest` strategies for when you *can't* slow the source, like UI events or market data). The crucial caveat connecting to the rest of this guide: on **Java 21, virtual threads (Q17) make blocking, backpressured code far simpler** — a thread-per-request blocking pipeline with a `BlockingQueue` (Q104) gives you natural backpressure (the bounded queue blocks the producer) with *readable, debuggable* synchronous code, without the viral `Flux`/`Mono` types, hard stack traces, and steep learning curve of reactive (the exact trade-off from the behavioral decision in Q28). So the senior framing is: reactive/`Flow` is the right tool when you need *non-blocking* end-to-end streaming with sophisticated overflow strategies (high-fan-out gateways, streaming transforms), but for most I/O-bound request/response services on Java 21, **virtual threads + bounded queues deliver the same backpressure benefit with far lower complexity** — backpressure is the goal, and reactive is only one way to achieve it.

## ✅ Key Takeaways

- **Master the contracts:** `equals`/`hashCode` consistency, the `Comparable`/`Comparator` total order, and immutability rules underpin correct collections and concurrency. Records enforce them for free.
- **Generics are compile-time only** (type erasure). Internalize PECS for flexible APIs and use type tokens when you need runtime type info.
- **Default to immutable + `ArrayList`/`HashMap`/`ArrayDeque`;** reach for `ConcurrentHashMap`, `TreeMap`, or specialized structures only when the access pattern demands it.
- **The JMM is about visibility and ordering, not just locks.** `volatile` gives visibility, not atomicity; happens-before is the mental model.
- **Java 21 changes idioms:** records + sealed types + pattern matching enable exhaustive, data-oriented code; virtual threads make blocking-style code scale for I/O-bound services.
- **Performance is empirical:** the JIT (inlining, escape analysis, deopt) and GC choice (G1 vs Generational ZGC) dominate real behavior — measure with JMH and production load, never by reading source.
- **Security lives in the details:** avoid Java serialization, bound caches and untrusted-key maps, and migrate off `Unsafe` toward the Panama FFM API.

## ⚠️ Common Pitfalls

- Overriding `equals()` without `hashCode()` (or using a mutable field in either) — corrupts hash collections.
- Using `Optional` as a field or method parameter, or calling `.get()` without a presence check; using `orElse()` where `orElseGet()` is needed (eager vs lazy).
- Assuming `HashMap`/`SimpleDateFormat`/`ArrayList` are thread-safe — they are not; classic source of corruption under load.
- Forgetting `volatile` on a stop-flag or double-checked-locking field, leading to invisible writes or reordering bugs.
- Catching `Exception`/`Throwable` broadly and swallowing it; or using checked exceptions inside streams/lambdas where they don't compose.
- Pinning virtual threads with `synchronized` on hot I/O paths, or pooling virtual threads (defeats their purpose).
- Leaking memory via unbounded caches, never-removed `ThreadLocal`s on pooled threads, and ever-growing static collections.
- Micro-benchmarking with raw timing loops that the JIT optimizes away — always use JMH.
- Deserializing untrusted data with native Java serialization (RCE risk) instead of schema-based formats with input filtering.

## 📚 Further Reading

- *Effective Java*, 3rd ed. — Joshua Bloch (the canonical idioms; equals/hashCode, generics, serialization, concurrency).
- *Java Concurrency in Practice* — Brian Goetz et al. (the JMM, happens-before, safe publication; still the reference).
- *Java Performance*, 2nd ed. — Scott Oaks (GC tuning, JIT, profiling for production).
- The Java Language Specification & JEP index ([openjdk.org/jeps](https://openjdk.org/jeps/0)) — authoritative source for records (JEP 395), sealed classes (409), pattern matching for switch (441), virtual threads (444), structured concurrency (453), sequenced collections (431), Generational ZGC (439).
- *Modern Java in Action* — Urma, Fusco, Mycroft (lambdas, streams, CompletableFuture, reactive).
- Inside Java ([inside.java](https://inside.java)) and the Java Almanac ([javaalmanac.io](https://javaalmanac.io)) — version-by-version feature and API diffs.

---

### Appendix: Version-by-Version Feature Comparison

| Feature / Capability        | Java 8 (LTS)        | Java 11 (LTS)            | Java 17 (LTS)                 | Java 21 (LTS)                          |
|-----------------------------|---------------------|--------------------------|-------------------------------|----------------------------------------|
| Release year                | 2014                | 2018                     | 2021                          | 2023                                   |
| Lambdas / Streams / Optional| ✅ introduced       | ✅                       | ✅                            | ✅                                     |
| `java.time` (JSR-310)       | ✅                  | ✅                       | ✅                            | ✅                                     |
| Default/static iface methods| ✅                  | ✅                       | ✅                            | ✅                                     |
| Modules (JPMS)              | ❌                  | ✅ (since 9)             | ✅                            | ✅                                     |
| `var` (local inference)     | ❌                  | ✅ (since 10)            | ✅                            | ✅                                     |
| Built-in `HttpClient`       | ❌                  | ✅ standardized          | ✅                            | ✅                                     |
| New `String` methods (`strip`,`lines`,`repeat`)| ❌     | ✅                       | ✅                            | ✅                                     |
| Text blocks                 | ❌                  | ❌                       | ✅ (since 15)                 | ✅                                     |
| Records                     | ❌                  | ❌                       | ✅                            | ✅ (+ record patterns)                 |
| Sealed classes              | ❌                  | ❌                       | ✅                            | ✅                                     |
| Switch expressions          | ❌                  | ❌                       | ✅ (since 14)                 | ✅                                     |
| Pattern matching `instanceof`| ❌                 | ❌                       | ✅ (since 16)                 | ✅                                     |
| Pattern matching for `switch`| ❌                 | ❌                       | preview                       | ✅ final                               |
| Virtual threads (Loom)      | ❌                  | ❌                       | ❌                            | ✅ final                               |
| Structured concurrency      | ❌                  | ❌                       | ❌                            | ⚙️ preview                             |
| Sequenced collections       | ❌                  | ❌                       | ❌                            | ✅                                     |
| Default GC                  | Parallel            | G1                       | G1                            | G1 (Generational ZGC available)        |
| ZGC                         | ❌                  | experimental (15 prod)   | ✅ production                 | ✅ generational                        |
