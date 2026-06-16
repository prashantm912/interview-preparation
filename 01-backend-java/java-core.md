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
