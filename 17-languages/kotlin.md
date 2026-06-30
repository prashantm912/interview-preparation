# Kotlin (Language Deep-Dive)

[← Back to master index](../README.md)

Kotlin is a statically typed, JVM-first language from JetBrains that interoperates seamlessly with Java while eliminating whole classes of bugs through null safety, immutability-by-convention, and expression-oriented syntax. For Java engineers, the leap is mostly additive — every Java library still works — but idiomatic Kotlin leans on features Java lacks: coroutines for structured concurrency, extension functions, sealed hierarchies with exhaustive `when`, and data classes. This guide walks the language from fundamentals through expert-level coroutine internals and interop edge cases, current to Kotlin 2.x (K2 compiler) in 2026.

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is the difference between `val` and `var`?

`val` declares a read-only (immutable) reference — it can be assigned exactly once. `var` declares a mutable reference that can be reassigned. The distinction is about the *reference*, not deep immutability of the object it points to.

```kotlin
val name = "Ada"      // cannot reassign name
// name = "Grace"     // compile error

var count = 0
count = 1             // OK

val list = mutableListOf(1, 2)
list.add(3)           // OK — the reference is final, the object is mutable
// list = mutableListOf() // compile error — can't rebind a val
```

Idiomatic Kotlin prefers `val` by default and reaches for `var` only when reassignment is genuinely needed. This makes code easier to reason about and is friendlier to concurrency. Note that `val` is roughly Java's `final`, but it does not imply the underlying object is immutable — `val list` above can still mutate its contents.

### Q2. [Theory] Explain Kotlin's null safety: `?`, `?.`, `?:`, and `!!`.

Kotlin separates nullable and non-nullable types at the type level. `String` can never hold `null`; `String?` can. The compiler then forces you to handle the nullable case, eliminating most `NullPointerException`s.

- **`?` (nullable type)** — `val s: String?` declares that `s` may be `null`.
- **`?.` (safe call)** — `s?.length` returns the length if `s` is non-null, otherwise `null`. The whole expression has type `Int?`.
- **`?:` (Elvis operator)** — `s?.length ?: 0` returns `0` when the left side is null.
- **`!!` (not-null assertion)** — `s!!.length` throws `NullPointerException` if `s` is null. It is an escape hatch; overuse defeats the point of null safety.

```kotlin
fun describe(s: String?): String {
    val len = s?.length ?: -1          // safe call + Elvis
    return "length=$len"
}

describe(null)     // "length=-1"
describe("hi")     // "length=2"
```

```
 s: String?
   │
   ├── s?.length ──► Int?   (null if s is null)
   ├── s?.length ?: 0 ──► Int  (0 if s is null)
   └── s!!.length ──► Int  (throws if s is null)
```

Treat `!!` as a code smell to be justified, not a default.

### Q3. [Theory] What is a data class and what does it generate for you?

A `data class` is a class meant to hold data; the compiler synthesizes boilerplate from the properties declared in the primary constructor:

- `equals()` / `hashCode()` based on those properties
- `toString()` of the form `User(name=Ada, age=36)`
- `componentN()` functions enabling destructuring
- `copy(...)` for creating modified clones

```kotlin
data class User(val name: String, val age: Int)

val a = User("Ada", 36)
val b = a.copy(age = 37)              // copy with one field changed
val (name, age) = a                  // destructuring via componentN()
println(a == User("Ada", 36))        // true — structural equality
```

Caveats: only properties in the *primary constructor* participate in the generated methods. A data class cannot be `abstract`, `open`, `sealed`, or `inner`. Prefer keeping data classes' properties `val` so equality and hashing stay stable (mutable keys in a `HashMap` are a classic bug).

### Q4. [Practical] How do you write a `when` expression, and how does it differ from Java's `switch`?

`when` is Kotlin's multi-branch construct. Unlike Java's `switch`, it is an *expression* (returns a value), needs no `break`, matches arbitrary conditions (not just constants), and can be checked for exhaustiveness.

```kotlin
fun classify(x: Any): String = when (x) {
    0 -> "zero"
    in 1..9 -> "small"               // ranges
    is String -> "string of len ${x.length}"  // type test + smart cast
    else -> "other"
}

// subject-less form: arbitrary boolean conditions
fun grade(score: Int): Char = when {
    score >= 90 -> 'A'
    score >= 80 -> 'B'
    else -> 'F'
}
```

When used as an expression, `when` must be exhaustive — over a sealed type or enum the compiler can verify all cases are covered, letting you omit `else`. That exhaustiveness is one of Kotlin's strongest correctness tools.

### Q5. [Theory] What are smart casts and when do they not apply?

After you check a value's type or nullability, the compiler automatically narrows (smart-casts) it within that scope, so you don't re-cast manually.

```kotlin
fun length(x: Any?): Int {
    if (x is String) {
        return x.length             // x smart-cast to String here
    }
    if (x != null) {
        // x smart-cast to Any (non-null)
    }
    return 0
}
```

Smart casts require the compiler to prove the value can't change between the check and the use. They do **not** apply to:

- `var` properties (could be reassigned by another thread/path),
- `open`/custom-getter properties (the getter could return different values each call),
- mutable properties from another module.

For those, capture into a local `val` first, or use `?.let { ... }`. The K2 compiler widened smart-cast support, but the fundamental "must be provably stable" rule remains.

### Q6. [Theory] How does Kotlin distinguish `==` from `===`?

`==` is *structural* equality — it compiles to a null-safe call to `equals()`. `===` is *referential* equality — it checks whether two references point to the same object instance.

```kotlin
val a = User("Ada", 36)
val b = User("Ada", 36)
println(a == b)    // true  — equals() compares fields (data class)
println(a === b)   // false — different instances
```

`a == b` is shorthand for `a?.equals(b) ?: (b === null)`, so it never NPEs even when `a` is null. This is the opposite of Java, where `==` on objects is reference comparison and `.equals()` is structural — a common source of Java bugs that Kotlin's defaults invert toward safety.

### Q7. [Practical] Show string templates and how to write multi-line strings.

String templates embed expressions with `$name` or `${expression}`. Raw (triple-quoted) strings preserve newlines and don't process escapes.

```kotlin
val name = "Ada"
val n = 3
println("Hello $name, you have ${n * 2} messages")

val json = """
    {
      "user": "$name",
      "count": $n
    }
""".trimIndent()                     // trimIndent removes common leading whitespace
```

`trimIndent()` and `trimMargin("|")` clean up the indentation that source formatting introduces. Raw strings are ideal for regexes, JSON, and SQL because backslashes need no escaping.

### Q8. [Theory] What are nullable types' implications for Java interop (platform types)?

When Kotlin calls Java, the Java type system carries no nullability information, so Kotlin treats incoming Java types as **platform types**, written `String!` in diagnostics. A platform type can be used as either nullable or non-nullable — the compiler trusts you and inserts no null checks at the boundary.

```kotlin
// Java: public String getName() { ... }
val name: String = javaObj.name      // allowed; NPE at runtime if it was null
val safe: String? = javaObj.name     // safer: treat as nullable
```

This is a deliberate ergonomics/safety tradeoff: platform types avoid forcing `?` on every Java return value, but they reintroduce NPE risk. Mitigate it by honoring JSR-305 / JetBrains `@Nullable`/`@NotNull` annotations on the Java side — Kotlin reads them and enforces nullability accordingly.

### Q9. [Practical] How do you declare functions, default arguments, and named arguments?

```kotlin
fun greet(name: String, greeting: String = "Hello", excited: Boolean = false): String {
    val mark = if (excited) "!" else "."
    return "$greeting, $name$mark"
}

greet("Ada")                                 // "Hello, Ada."
greet("Ada", excited = true)                 // named arg skips middle default
greet(greeting = "Hi", name = "Grace")       // order-independent with names

// single-expression function
fun square(x: Int) = x * x
```

Default arguments often eliminate the need for overloads. Named arguments improve call-site readability and let you skip earlier defaults. For Java callers who can't use defaults, annotate with `@JvmOverloads` to generate overloads.

### Q10. [Theory] Explain Kotlin's basic type hierarchy: `Any`, `Unit`, `Nothing`.

- **`Any`** is the root of the non-nullable type hierarchy (analogous to Java's `Object`, but without `wait`/`notify`). The true top type including nulls is `Any?`.
- **`Unit`** is the type with a single value, returned by functions that "return nothing meaningful" — analogous to `void`, but it's a real type, so it can be used as a generic argument.
- **`Nothing`** is the bottom type: it has no instances and signals "this never returns normally" (e.g., a function that always throws). `Nothing` is a subtype of every type, which is why `throw` and `TODO()` can appear in any expression position.

```kotlin
fun fail(msg: String): Nothing = throw IllegalStateException(msg)

val x: Int = readMaybe() ?: fail("missing")  // fail() returns Nothing, fits any type
```

### Q11. [Practical] How do you create and iterate collections, and what's the read-only vs mutable distinction?

Kotlin's standard collection *interfaces* are read-only (`List`, `Set`, `Map`); the `Mutable*` variants add modification methods. Read-only is not the same as immutable — it's a view that doesn't expose mutators.

```kotlin
val ro: List<Int> = listOf(1, 2, 3)          // read-only
val mut: MutableList<Int> = mutableListOf(1) // mutable
mut.add(2)

val map = mapOf("a" to 1, "b" to 2)          // 'to' builds a Pair
for ((k, v) in map) println("$k=$v")         // destructuring in loop

val doubled = ro.map { it * 2 }              // [2, 4, 6]
val evens = ro.filter { it % 2 == 0 }        // [2]
```

`mapOf`/`listOf` return read-only views; the underlying object may still be mutable through another reference, so "read-only" guarantees the *interface* won't mutate, not deep immutability.

### Q12. [Theory] What are extension functions and how do they work under the hood?

An extension function adds a method to a type you don't own, without inheritance or modifying the class.

```kotlin
fun String.shout(): String = this.uppercase() + "!"

println("hi".shout())   // "HI!"
```

Under the hood, extensions are **statically resolved** and compile to static methods that take the receiver as the first parameter — roughly `StringExtKt.shout(this)`. Consequences:

- They are **not** polymorphic: the function called depends on the *declared* (static) type of the receiver, not its runtime type.
- They cannot access `private` members of the receiver type.
- A member function always wins over an extension with the same signature.

They're ideal for utility methods and fluent DSLs without subclassing.

### Q13. [Practical] Write an extension property and explain why it can't have backing state.

```kotlin
val String.lastChar: Char
    get() = this[length - 1]

val List<Int>.secondOrNull: Int?
    get() = if (size >= 2) this[1] else null

println("Kotlin".lastChar)   // 'n'
```

Extension properties cannot have a backing field — there's nowhere to store per-instance state, since the type isn't actually modified. Therefore they must define a custom getter (and optional setter) computing the value from the receiver. They're syntactic sugar over an extension function with property syntax.

### Q14. [Theory] What is the difference between `if`/`when` as statements vs expressions in Kotlin?

In Kotlin, `if`, `when`, and `try` are *expressions* — they yield values — whereas in Java they're statements. This removes the need for the ternary operator (Kotlin has none).

```kotlin
val max = if (a > b) a else b        // replaces Java's a > b ? a : b

val sign = when {
    n > 0 -> "+"
    n < 0 -> "-"
    else  -> "0"
}

val parsed = try { s.toInt() } catch (e: NumberFormatException) { 0 }
```

When used as an expression, `if` must have an `else` branch, and `when` must be exhaustive. The value of each branch is its last expression.

### Q15. [Practical] How do ranges and the `in` operator work?

Ranges create sequences of comparable values; `in` checks membership and `..`, `until`, `downTo`, `step` build them.

```kotlin
for (i in 1..5) print(i)          // 12345 (inclusive)
for (i in 1..<5) print(i)         // 1234 (open-ended, replaces 'until')
for (i in 5 downTo 1) print(i)    // 54321
for (i in 0..10 step 2) print(i)  // 0246810

val ok = 'c' in 'a'..'z'          // true
val outside = 42 !in 0..9         // true
```

`in` also drives `when` branches and works for any type with a `contains` operator (including collections and strings). The `..<` operator (open range) is the modern, clearer replacement for `until`.

## 🟡 Intermediate (3–7 yrs)

### Q16. [Theory] Explain the five scope functions: `let`, `run`, `with`, `apply`, `also`. How do you choose?

Scope functions execute a block on an object. They differ along two axes: how the object is referenced inside (`it` vs `this`) and what the block returns (the object itself vs the lambda result).

| Function | Receiver as | Returns        | Typical use |
|----------|-------------|----------------|-------------|
| `let`    | `it`        | lambda result  | null-safe transform on `x?.let { ... }` |
| `run`    | `this`      | lambda result  | compute a value with the object in scope |
| `with`   | `this` (arg)| lambda result  | group calls on an object (not an extension) |
| `apply`  | `this`      | the object     | configure/build an object, return it |
| `also`   | `it`        | the object     | side effects (logging, validation) in a chain |

```kotlin
val u = User("Ada", 36).apply {
    // 'this' is the User; configure it
}.also {
    println("created $it")           // side effect, returns the User
}

val len: Int? = nullableStr?.let { it.trim().length }   // transform if non-null

val msg = with(StringBuilder()) {
    append("a"); append("b")
    toString()                       // returns lambda result
}
```

Rule of thumb: `apply`/`also` return the object (good for builders and chains); `let`/`run`/`with` return the lambda result (good for transforms). Use `it`-receiver forms (`let`, `also`) when you want to rename the parameter or nest scopes.

### Q17. [Theory] What are higher-order functions, lambdas, and function types?

A higher-order function takes functions as parameters or returns them. Function types are written `(A, B) -> R`. A lambda is `{ args -> body }`; if it has one parameter it's implicitly named `it`.

```kotlin
fun <T, R> transform(items: List<T>, f: (T) -> R): List<R> =
    items.map(f)

val lengths = transform(listOf("a", "bb")) { it.length }  // [1, 2]

// returning a function (closure over 'base')
fun adder(base: Int): (Int) -> Int = { x -> x + base }
val add10 = adder(10)
println(add10(5))                                          // 15
```

If a lambda is the last argument, it can go outside the parentheses (trailing-lambda syntax) — the basis of Kotlin DSLs. Lambdas capture variables from their enclosing scope (closures), and unlike Java, they can capture and mutate `var`s.

### Q18. [Theory] What does `inline` do for higher-order functions, and what are `noinline`/`crossinline`?

Marking a function `inline` tells the compiler to copy its body — and the bodies of its lambda parameters — directly into each call site. This eliminates the function-object allocation and call overhead that lambdas otherwise incur, and it enables non-local returns from lambdas.

```kotlin
inline fun <T> measure(block: () -> T): T {
    val start = System.nanoTime()
    val result = block()             // body inlined here
    println("took ${System.nanoTime() - start} ns")
    return result
}
```

- **`noinline`** — applied to a specific lambda parameter to *exclude* it from inlining (needed if you store it in a variable or pass it on).
- **`crossinline`** — keeps the lambda inlined but forbids non-local returns from it (required when the lambda is invoked from another execution context, e.g., a nested object).

Use `inline` for small functions taking lambdas; inlining large functions bloats bytecode. The biggest payoff is for hot, lambda-heavy utilities and for `reified` type parameters.

### Q19. [Theory] What is a `reified` type parameter and what does it enable?

Normally generic type arguments are erased at runtime (JVM type erasure), so you can't write `T::class` or `x is T`. Inside an `inline` function, a type parameter marked `reified` is substituted at the call site, making the actual type available at runtime.

```kotlin
inline fun <reified T> Any?.asTypeOrNull(): T? = this as? T

inline fun <reified T> Gson.fromJson(json: String): T =
    fromJson(json, T::class.java)    // T::class available thanks to reified

val n: Int? = (42 as Any).asTypeOrNull<Int>()   // 42
```

`reified` only works with `inline` functions because reification is realized by inlining the concrete type at each call site. It removes the need to pass `Class<T>` tokens around (a common Java pattern).

### Q20. [Theory] Explain sealed classes and sealed interfaces and why they pair with `when`.

A `sealed` type restricts its subclasses to those declared in the same module (and, in modern Kotlin, the same package). The compiler knows the complete set of subtypes, so it can verify a `when` is exhaustive without an `else` branch.

```kotlin
sealed interface Result<out T>
data class Ok<T>(val value: T) : Result<T>
data class Err(val message: String) : Result<Nothing>

fun <T> handle(r: Result<T>): String = when (r) {
    is Ok  -> "ok: ${r.value}"       // smart-cast to Ok
    is Err -> "err: ${r.message}"
    // no else needed — compiler knows all cases
}
```

Sealed hierarchies model closed sets of states (results, UI states, AST nodes) far better than enums (which can't carry per-case data) or open inheritance (which can't be checked exhaustively). Adding a new subtype turns every non-exhaustive `when` into a compile error — a feature, not a bug.

### Q21. [Theory] What is delegation with `by`, both for interfaces and properties?

Kotlin has first-class delegation. **Class delegation** implements an interface by forwarding to another object:

```kotlin
interface Logger { fun log(msg: String) }
class ConsoleLogger : Logger { override fun log(msg: String) = println(msg) }

class Service(logger: Logger) : Logger by logger   // forwards Logger methods
```

**Property delegation** routes a property's get/set through a delegate that implements `getValue`/`setValue`:

```kotlin
class Config {
    val dbUrl: String by lazy { loadFromEnv("DB_URL") }   // computed once, thread-safe
    var name: String by Delegates.observable("init") { _, old, new ->
        println("name: $old -> $new")
    }
}
```

Common standard delegates: `lazy` (lazy init), `Delegates.observable`/`vetoable`, and `map`-backed properties. Delegation favors composition over inheritance and removes forwarding boilerplate.

### Q22. [Practical] How does `lazy` work and what are its thread-safety modes?

`by lazy { ... }` computes a value on first access and caches it. By default it's `SYNCHRONIZED` — thread-safe, the initializer runs at most once even under contention.

```kotlin
val expensive: Heavy by lazy { Heavy() }                // SYNCHRONIZED (default)
val perThreadish by lazy(LazyThreadSafetyMode.PUBLICATION) { compute() }
val singleThread by lazy(LazyThreadSafetyMode.NONE) { compute() }   // no locking
```

- **`SYNCHRONIZED`** — locks so only one thread initializes. Safe default.
- **`PUBLICATION`** — multiple threads may run the initializer concurrently, but the first result published wins; all callers see the same value.
- **`NONE`** — no synchronization; fastest, but only safe if you guarantee single-threaded access.

Pick `NONE` only when you know access is single-threaded (e.g., UI thread).

### Q23. [Practical] What does a `companion object` do, and how is it different from Java `static`?

Kotlin has no `static` members. Instead, a `companion object` is a singleton tied to the class; its members are accessed via the class name.

```kotlin
class User private constructor(val name: String) {
    companion object Factory {
        const val MAX_NAME = 50                  // compile-time constant
        fun create(name: String): User = User(name.take(MAX_NAME))
    }
}

val u = User.create("Ada")        // looks static-like
```

The companion is a real object: it can implement interfaces, hold state, and be extended. For Java interop, mark members `@JvmStatic` to expose them as true statics, and use `const val` for compile-time constants (vs `val`, which generates a getter). There can be only one companion object per class.

### Q24. [Practical] Compare collections vs sequences. When does `Sequence` win?

Collection operations (`map`, `filter`, …) are **eager**: each step builds a new intermediate list. `Sequence` operations are **lazy**: elements flow through the whole chain one at a time, and intermediate collections are never materialized.

```kotlin
// eager: builds 2 intermediate lists for 1M elements
val a = (1..1_000_000).map { it * 2 }.filter { it % 3 == 0 }.first()

// lazy: pulls just enough elements to satisfy first()
val b = (1..1_000_000).asSequence()
    .map { it * 2 }
    .filter { it % 3 == 0 }
    .first()
```

```
List chain:    [src] → map → [list] → filter → [list] → first
Sequence:      src → (map → filter) per element → first  (short-circuits early)
```

Use sequences for large/infinite data, long operation chains, or when you'll short-circuit (`first`, `take`, `find`). For small collections, eager is often faster (no iterator/lambda overhead per step). Sequences don't help if you consume the whole thing into a list anyway.

### Q25. [Practical] What is a coroutine, and how does `suspend` differ from a blocking call?

A coroutine is a suspendable computation — a lightweight unit of work that can pause and resume without blocking a thread. A `suspend` function can suspend its execution at a suspension point, releasing the thread to do other work, and resume later (possibly on a different thread).

```kotlin
suspend fun fetchUser(id: Int): User {
    delay(100)                       // suspends, does NOT block the thread
    return api.get(id)               // another suspend call
}

fun main() = runBlocking {
    val user = fetchUser(1)          // looks sequential, runs without blocking
    println(user)
}
```

The key difference: `Thread.sleep(100)` parks the underlying OS thread (expensive, limited resource); `delay(100)` suspends only the coroutine, freeing the thread. Millions of coroutines can multiplex over a small thread pool. `suspend` functions can only be called from other `suspend` functions or a coroutine builder.

### Q26. [Theory] How does the compiler implement `suspend` (continuations / CPS)?

The compiler transforms each `suspend` function via **continuation-passing style (CPS)**. It adds a hidden `Continuation` parameter and rewrites the body into a state machine: each suspension point becomes a state, and local variables that survive across suspensions are stored as fields.

```
suspend fun foo() { a(); val x = bar(); b(x) }

becomes (conceptually):

fun foo(cont: Continuation): Any? {
    switch (cont.label) {
        case 0: a(); cont.label = 1; return bar(cont)   // may return COROUTINE_SUSPENDED
        case 1: val x = cont.result; b(x); return Unit
    }
}
```

When a suspension point can't complete immediately, the function returns the sentinel `COROUTINE_SUSPENDED`; when the awaited work finishes, the continuation's `resumeWith` re-invokes the state machine at the saved label. This is why coroutines need no extra threads — suspension is just an early return plus a resumable closure.

### Q27. [Theory] What is structured concurrency and what problem does it solve?

Structured concurrency ties the lifetime of coroutines to a `CoroutineScope`, forming a parent-child hierarchy. A scope does not complete until all its children complete; cancelling the scope cancels all children; and a failure in one child (by default) cancels its siblings and propagates to the parent.

```kotlin
suspend fun loadDashboard() = coroutineScope {      // creates a scope
    val user = async { fetchUser() }
    val feed = async { fetchFeed() }
    Dashboard(user.await(), feed.await())
}   // returns only after BOTH children finish; if one throws, the other is cancelled
```

This solves the leaks and orphaned tasks endemic to unstructured threads/futures: no coroutine outlives its scope, cancellation propagates automatically, and errors can't vanish silently. `coroutineScope` (and `supervisorScope`) and `viewModelScope`/`lifecycleScope` on Android are the practical entry points.

### Q28. [Practical] Compare `launch`, `async`, and the dispatchers.

`launch` starts a coroutine that returns a `Job` (fire-and-forget, no result). `async` returns a `Deferred<T>` whose `await()` yields a result and propagates exceptions.

```kotlin
val job = scope.launch { doWork() }              // Job, no result
val deferred = scope.async { compute() }         // Deferred<Int>
val result = deferred.await()                     // get value / rethrow

// run two things concurrently
val (a, b) = coroutineScope {
    val da = async { fetchA() }
    val db = async { fetchB() }
    da.await() to db.await()
}
```

Dispatchers decide which thread(s) run the coroutine:

- **`Dispatchers.Default`** — CPU-bound work, pool sized to cores.
- **`Dispatchers.IO`** — blocking I/O (network, disk), large elastic pool.
- **`Dispatchers.Main`** — UI thread (Android/JavaFX).
- **`Dispatchers.Unconfined`** — starts in caller thread, resumes wherever; rarely used.

Switch with `withContext(Dispatchers.IO) { ... }`. Don't run blocking calls on `Default` or `Main`.

### Q29. [Practical] How does coroutine cancellation work, and what is cooperative cancellation?

Cancellation is **cooperative**: cancelling a `Job` sets its state to cancelling, but the coroutine actually stops only at the next suspension point or cancellation check. All `kotlinx.coroutines` suspend functions (`delay`, `withContext`, `yield`) check for cancellation and throw `CancellationException`.

```kotlin
val job = launch {
    repeat(1000) { i ->
        ensureActive()               // or check isActive / call yield()
        heavyCpuStep(i)              // pure CPU loop won't auto-cancel
    }
}
delay(50)
job.cancelAndJoin()
```

Pitfalls: a tight CPU loop with no suspension point ignores cancellation — insert `ensureActive()`/`yield()`. Don't swallow `CancellationException` in a broad `catch (e: Exception)`; rethrow it, or use `try/finally` (with `withContext(NonCancellable)` for cleanup that must run).

### Q30. [Theory] What is `Flow`, and what's the difference between cold and hot streams?

`Flow<T>` is Kotlin's asynchronous stream — an ordered sequence of values produced over time, with coroutine-based backpressure. Flows are **cold** by default: the producer block runs anew for each collector and only while it's being collected. Nothing happens until `collect`.

```kotlin
fun numbers(): Flow<Int> = flow {           // cold — runs per collector
    for (i in 1..3) { delay(100); emit(i) }
}

suspend fun use() {
    numbers().map { it * 2 }.collect { println(it) }   // triggers production
}
```

**Hot** streams (`StateFlow`, `SharedFlow`) exist and emit independently of collectors:

- **`StateFlow`** — always holds a current value, conflated, ideal for UI state. New collectors immediately get the latest value.
- **`SharedFlow`** — broadcasts to multiple collectors with a configurable replay buffer; good for events.

```
Cold (flow):   each collector ──► its own producer run
Hot (StateFlow): one producer ──► current value ──► all collectors share it
```

Rule: cold for one-shot/per-collector pipelines; hot for shared state and event broadcasting.

### Q31. [Practical] Show common `Flow` operators and how to switch context safely.

```kotlin
val result = flow {
    emit(fetchPage(1)); emit(fetchPage(2))
}
    .map { it.parse() }
    .filter { it.isValid }
    .flowOn(Dispatchers.IO)              // upstream runs on IO
    .catch { e -> emit(fallback) }       // handle upstream errors
    .onEach { log(it) }
    .toList()                            // terminal operator
```

Key rules:

- Use **`flowOn`** to change the dispatcher of *upstream* operators; never call `withContext` inside `flow { }` to switch threads — it violates context preservation and throws.
- **`catch`** only sees exceptions from upstream; put it after the operators it should guard.
- Terminal operators (`collect`, `toList`, `first`, `reduce`) are `suspend` and start the flow.
- For combining: `combine`, `zip`, `flatMapLatest`, `flatMapMerge`.

### Q32. [Practical] How do you call Java from Kotlin and Kotlin from Java? Note the friction points.

**Kotlin → Java** is nearly transparent: Java classes, generics, and SAM interfaces all work. Java getters/setters appear as properties (`obj.name`). The main concern is platform types (nullability — see Q8).

**Java → Kotlin** needs awareness of how Kotlin features map:

```kotlin
class Greeter {
    companion object { @JvmStatic fun hi() = "hi" }   // else Greeter.Companion.hi()
    @JvmOverloads fun greet(name: String, loud: Boolean = false) { /*...*/ }
}

@JvmField val SHARED = 42                  // expose as a plain field, no getter
```

Friction points and the annotations that fix them:

- `@JvmStatic` — expose companion/object members as real statics.
- `@JvmOverloads` — generate overloads for default parameters (Java has none).
- `@JvmField` — expose a property as a public field rather than getter/setter.
- `@JvmName` — rename for Java when Kotlin names clash or aren't valid Java.
- `@Throws` — declare checked exceptions so Java callers can `catch` them.
- Top-level functions in `Foo.kt` live in a `FooKt` class from Java's view.

### Q33. [Theory] What are `object` declarations and `object` expressions?

An `object` declaration creates a thread-safe, lazily initialized **singleton**:

```kotlin
object Registry {                      // single instance, created on first use
    private val items = mutableListOf<String>()
    fun add(x: String) { items += x }
}
Registry.add("a")
```

An `object` expression creates an **anonymous object** (Kotlin's version of an anonymous inner class), useful for ad-hoc implementations:

```kotlin
val listener = object : ClickListener {
    override fun onClick() { println("clicked") }
}
```

Unlike Java anonymous classes, an object expression can implement multiple interfaces and access (and modify) captured `var`s. Object declarations are the idiomatic singleton, replacing the error-prone Java double-checked-locking pattern.

### Q34. [Practical] How do you handle exceptions idiomatically, including with `runCatching`?

Kotlin has **no checked exceptions** — any function may throw without declaring it. `try` is an expression. The standard library offers `runCatching` returning a `Result<T>`:

```kotlin
val n: Int = try { s.toInt() } catch (e: NumberFormatException) { 0 }

val result: Result<User> = runCatching { api.fetch(id) }
result
    .map { it.name }
    .getOrElse { e -> "unknown (${e.message})" }
```

Caution: because there are no checked exceptions, Java callers won't know to catch them — annotate with `@Throws` at the boundary. And `runCatching` catches `Throwable`, which means it also swallows `CancellationException` inside coroutines — avoid it in coroutine code or rethrow `CancellationException` explicitly.

### Q35. [Theory] What are inline (value) classes and when do they help?

An inline (value) class wraps a single value to add type safety with (usually) **no runtime allocation** — the wrapper is erased and the underlying value is used directly where possible.

```kotlin
@JvmInline
value class UserId(val raw: Long)
@JvmInline
value class Cents(val amount: Long)

fun charge(id: UserId, money: Cents) { /* ... */ }
// charge(Cents(100), UserId(5))   // compile error — types don't mix
```

This prevents "primitive obsession" bugs where `Long id` and `Long amount` get swapped, while avoiding boxing overhead. The wrapper is boxed only when used as a nullable, a generic type argument, or where a supertype is expected. Value classes must have exactly one `val` property in the primary constructor and no `init`-state beyond it.

## 🟠 Advanced (8–12 yrs)

### Q36. [Theory] Explain variance: `out`, `in`, and use-site projections (`star`).

Kotlin handles generic variance declaratively at the *declaration site*:

- **`out T` (covariant)** — `T` appears only in *output* positions; `Producer<Cat>` is a subtype of `Producer<Animal>`. Think `List<out T>`.
- **`in T` (contravariant)** — `T` appears only in *input* positions; `Comparator<Animal>` is a subtype of `Comparator<Cat>`.

```kotlin
interface Producer<out T> { fun produce(): T }
interface Consumer<in T> { fun consume(item: T) }

val animals: Producer<Animal> = object : Producer<Cat> { ... }   // OK via 'out'
```

Where a class isn't declared variant, you can apply **use-site variance** (projections), equivalent to Java wildcards:

```kotlin
fun copy(from: Array<out Any>, to: Array<Any>) { ... }   // Array<out Any> ~ Array<? extends Any>
```

A **star projection** `List<*>` means "list of something unknown" — you can read as `Any?` but can't safely write. The mnemonic is PECS: Producer-`out`, Consumer-`in`.

### Q37. [Theory] What is the structured-concurrency exception model? Contrast `Job` vs `SupervisorJob` and `CoroutineExceptionHandler`.

In a regular `Job` hierarchy, a child's uncaught failure cancels its parent and therefore all siblings — failures propagate *up and down*. A `SupervisorJob` (or `supervisorScope`) breaks the downward propagation: a child failure does **not** cancel siblings or the supervisor.

```kotlin
supervisorScope {
    launch { failingTask() }          // fails alone
    launch { otherTask() }            // keeps running
}
```

Exception handling rules:

- `async` defers the exception until `await()`; `launch` throws into the hierarchy immediately.
- A `CoroutineExceptionHandler` is the last resort for *uncaught* exceptions in `launch`-style coroutines at the root of a scope. It does nothing for `async` (the exception belongs to `await`) and nothing for non-root coroutines (the exception propagates to the parent first).
- `CancellationException` is special — it's treated as normal cancellation, not a failure, and is not reported to the handler.

### Q38. [Practical] Implement a thread-safe `StateFlow`-based view model and explain the lifecycle.

```kotlin
sealed interface UiState {
    data object Loading : UiState
    data class Loaded(val users: List<User>) : UiState
    data class Failed(val message: String) : UiState
}

class UserViewModel(private val repo: UserRepo) {
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()   // expose read-only

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun load() {
        scope.launch {
            _state.value = UiState.Loading
            _state.value = runCatching { repo.fetchAll() }
                .fold(
                    onSuccess = { UiState.Loaded(it) },
                    onFailure = { UiState.Failed(it.message ?: "error") }
                )
        }
    }

    fun clear() = scope.cancel()      // structured teardown
}
```

`StateFlow` always has a current value, conflates rapid updates, and re-emits the latest to new collectors — ideal for UI. Exposing `asStateFlow()` prevents callers from mutating state. The `SupervisorJob` keeps one failed task from tearing down the whole scope; `clear()` cancels all in-flight work (mirrors `viewModelScope` on Android).

### Q39. [Theory] How do `equals`/`hashCode`/`compareTo` interplay, and what are the data-class pitfalls?

Generated `equals`/`hashCode` use only primary-constructor properties; properties declared in the class body are ignored. Mutating a property that participates in `hashCode` after inserting the object into a hash-based collection corrupts the collection (the object lands in the wrong bucket).

```kotlin
data class Key(var id: Int)          // mutable -> dangerous as a map key
val map = hashMapOf(Key(1) to "a")
val k = Key(1)
k.id = 2                              // if k were the key, lookups would break
```

Best practices: keep data-class properties `val`; if you need a custom `compareTo`, implement `Comparable` explicitly (data classes don't generate it); be aware that inheritance + `equals` is subtle, which is one reason data classes can't be `open`. For ordering, prefer `compareBy { it.field }` builders over hand-written comparators.

### Q40. [Coding] Write a coroutine-based bounded producer/consumer using `Channel`. Discuss backpressure.

```kotlin
suspend fun pipeline() = coroutineScope {
    val channel = Channel<Int>(capacity = 4)   // bounded buffer -> backpressure

    // producer
    launch {
        for (i in 1..100) channel.send(i)       // suspends when buffer is full
        channel.close()
    }

    // consumers (fan-out)
    repeat(3) { id ->
        launch {
            for (item in channel) {              // suspends when empty
                process(id, item)
            }
        }
    }
}
```

```
producer ──send──► [ buffer cap=4 ] ──receive──► consumers (×3)
            (suspends when full)        (suspend when empty)
```

A bounded `Channel` provides natural backpressure: the producer suspends when the buffer is full, so a fast producer can't overwhelm slow consumers or exhaust memory. Choose capacity deliberately — `RENDEZVOUS` (0) for tight coupling, `BUFFERED`/`UNLIMITED` only when you understand the memory tradeoff. `close()` lets consumers' `for` loops terminate cleanly.

### Q41. [Theory] How do you build a type-safe DSL in Kotlin, and what does `@DslMarker` do?

Kotlin DSLs combine lambdas with receiver (`A.() -> Unit`), extension functions, and operator overloading. A lambda with receiver lets the block call methods on an implicit `this`.

```kotlin
class HtmlBuilder {
    private val sb = StringBuilder()
    fun text(s: String) { sb.append(s) }
    fun build() = sb.toString()
}

fun html(block: HtmlBuilder.() -> Unit): String =
    HtmlBuilder().apply(block).build()

val page = html {
    text("Hello")            // 'this' is HtmlBuilder
}
```

`@DslMarker` solves the *nested-receiver leak*: without it, inner blocks can implicitly call outer receivers' methods, producing nonsensical structures. Annotating the builder types with a marker annotation restricts each block to its nearest receiver, requiring an explicit qualifier to reach outer scopes. This is how `kotlinx.html`, Gradle Kotlin DSL, and Compose enforce structural correctness.

### Q42. [Behavioral] Your team is migrating a large Java service to Kotlin incrementally. How do you plan and de-risk it?

I'd frame it as a gradual, file-by-file migration rather than a big-bang rewrite, leveraging Kotlin's bidirectional Java interop so both languages coexist in the same module and build. Concretely:

1. **Enable Kotlin in the build** with mixed-source compilation; ensure CI runs both. No behavior change yet — just prove the toolchain.
2. **Start at the leaves** — convert low-risk utility classes and new code first, using the IDE's Java→Kotlin converter, then clean up the output (the converter is literal, not idiomatic).
3. **Guard the boundary**: add JetBrains/JSR-305 nullability annotations on remaining Java so Kotlin sees real nullability instead of platform types, catching NPEs at compile time.
4. **Codify interop rules** (`@JvmStatic`, `@JvmOverloads`, `@Throws`) in a short team guide so Java callers aren't surprised.
5. **Lean on tests**: keep the existing test suite green at every step; conversion shouldn't change behavior, so a passing suite is the safety net.
6. **Sequence by value**: prioritize modules where null-safety and coroutines pay off (concurrency-heavy or NPE-prone code).

I'd measure success by defect rates and developer velocity, and explicitly avoid converting stable, rarely touched code just for purity. The behavioral key is managing expectations: incremental migration trades a longer timeline for continuous deliverability and low risk.

### Q43. [Theory] What is the contract/effect system (`contract { }`), and why does it exist?

Kotlin's experimental contracts let a function tell the compiler about effects it guarantees, improving smart-casting and definite-assignment analysis across call boundaries that the compiler otherwise can't see through.

```kotlin
@OptIn(ExperimentalContracts::class)
fun require(condition: Boolean) {
    contract { returns() implies condition }   // if we return, condition is true
    if (!condition) throw IllegalArgumentException()
}

fun process(s: String?) {
    require(s != null)
    println(s.length)         // smart-cast to non-null thanks to the contract
}
```

Common contract clauses: `returns(value) implies (condition)` and `callsInPlace(block, InvocationKind.EXACTLY_ONCE)` (which lets a lambda safely initialize a `val`). The standard library uses contracts so functions like `requireNotNull`, `checkNotNull`, and `isNullOrEmpty` participate in smart casts. They exist because the compiler can't otherwise reason about a function's internal control flow.

### Q44. [Practical] How does `Flow` backpressure work, and how do `buffer`, `conflate`, and `collectLatest` differ?

A bare `Flow` is sequential: the collector processes each emission before the producer resumes, so a slow collector slows the producer (implicit backpressure). The operators tune this:

```kotlin
flow { ... }
    .buffer(capacity = 10)     // producer runs ahead into a buffer; both run concurrently
    .conflate()                // drop intermediate values; collector sees only the latest
    .collect { slow(it) }

flow { ... }.collectLatest { v ->
    process(v)                 // cancelled and restarted if a newer value arrives
}
```

- **`buffer`** — decouples producer and collector via a channel; no values dropped (until buffer policy says so). Throughput up, memory up.
- **`conflate`** — keeps only the most recent value when the collector lags; lossy but bounded. Good for UI state.
- **`collectLatest` / `mapLatest`** — cancels the in-flight block when a new value arrives. Good for "only the latest query matters" (search-as-you-type).

Choose based on whether stale values are acceptable (conflate/Latest) or must all be processed (buffer).

### Q45. [Theory] Explain how Kotlin generics map to the JVM, including erasure and `reified` workarounds.

On the JVM, Kotlin generics are **erased** at runtime just like Java's — `List<String>` and `List<Int>` share one `List` class, and you can't write `x is List<String>` (only `x is List<*>`). Reasons this matters:

- You can't overload solely on generic type argument; signatures collide after erasure.
- You can't instantiate `T` or call `T::class` in an ordinary generic function.

Workarounds:

```kotlin
inline fun <reified T> filterIsInstance(list: List<*>): List<T> =
    list.filterNot { it !is T }.map { it as T }   // reified keeps T at call site

fun <T> create(clazz: Class<T>): T = clazz.getDeclaredConstructor().newInstance()  // pass a token
```

`reified` (Q19) is the idiomatic escape via inlining. Where inlining isn't viable, pass an explicit `Class<T>`/`KClass<T>` token, exactly as Java does. Declaration-site variance (Q36) is a compile-time-only concept and likewise leaves no runtime trace.

### Q46. [Practical] Compare error modeling strategies: exceptions vs sealed `Result` vs Arrow `Either`. When would you choose each?

```kotlin
// 1) Exceptions: simplest, but invisible in signatures (no checked exceptions)
fun parse(s: String): Int = s.toInt()        // may throw

// 2) Sealed result: explicit, exhaustive handling, domain-specific errors
sealed interface ParseResult
data class Parsed(val value: Int) : ParseResult
data class Invalid(val input: String) : ParseResult

fun parse2(s: String): ParseResult =
    s.toIntOrNull()?.let(::Parsed) ?: Invalid(s)

// 3) kotlin.Result for one error channel; or Arrow Either<E, A> for typed errors
fun parse3(s: String): Result<Int> = runCatching { s.toInt() }
```

Guidance: use **exceptions** for truly exceptional, unrecoverable conditions and at boundaries (they cost nothing on the happy path). Use a **sealed result type** when the failure is part of the domain and you want the compiler to force handling of each case. Use **`kotlin.Result`/Arrow `Either`** when composing pipelines where errors should flow as values (functional style). Avoid `kotlin.Result` as a public return type for libraries and inside coroutines (it can swallow `CancellationException`).

## 🔴 Expert (15+ yrs)

### Q47. [Theory] Describe the K2 compiler frontend and what changed versus the old frontend.

K2 is the rewritten compiler frontend (stable since Kotlin 2.0) built around a new **FIR** (Frontend Intermediate Representation). The old frontend interleaved resolution, type inference, and diagnostics over the PSI in a way that grew brittle and slow; K2 separates phases over a unified semantic tree.

Key improvements:

- **Performance** — substantially faster analysis and compilation, especially on large codebases, due to a cleaner phased architecture.
- **More consistent type inference and smart casts** — previously inconsistent corner cases (smart casts through `&&`, more flow-sensitive narrowing) now work uniformly.
- **A shared frontend across platforms** (JVM, JS, Native, Wasm) — fewer platform-specific discrepancies and a single place to add language features.
- **Better IDE responsiveness** — the same FIR powers the IDE analysis.

Migration impact: mostly transparent, but K2's stricter, more correct analysis surfaced latent issues (e.g., previously-allowed unsound smart casts) that some code relied on, requiring fixes.

### Q48. [Theory] How are `suspend` functions represented at the bytecode/ABI level, and why does that matter for libraries?

A `suspend fun foo(x: Int): R` compiles to a JVM method `foo(int, Continuation): Object` — an extra `Continuation` parameter is appended and the return type is widened to `Object` (to allow returning the `COROUTINE_SUSPENDED` sentinel). The state machine (Q26) lives in a generated `ContinuationImpl` subclass per suspend function.

```
Kotlin:  suspend fun foo(x: Int): String
JVM:     Object foo(int x, Continuation<? super String> cont)
```

Why it matters for library authors:

- The continuation in the ABI means **changing a function to/from `suspend` is a binary-incompatible change** — callers compiled against the old signature break.
- Java can't call `suspend` functions directly (it would have to construct a `Continuation`); expose blocking or future-returning bridges (`future { }`, `runBlocking`) for Java consumers.
- Adding/removing/reordering parameters on suspend functions has the same ABI considerations as normal functions, plus the continuation slot.

### Q49. [Theory] How does Kotlin Multiplatform's `expect`/`actual` mechanism work, and what are its constraints?

KMP shares code in a `common` source set that declares platform-agnostic APIs with `expect`, while each target provides an `actual` implementation.

```kotlin
// commonMain
expect fun platformName(): String
expect class Uuid { fun asString(): String }

// jvmMain
actual fun platformName(): String = "JVM"
// nativeMain
actual fun platformName(): String = "Native"
```

Constraints and notes:

- Every `expect` declaration must have a matching `actual` in *each* target's source set, with a compatible signature.
- `actual typealias` lets a platform map an `expect class` onto an existing platform type (e.g., `actual typealias Uuid = java.util.UUID` on JVM).
- The compiler checks expect/actual matching at compile time; mismatches fail the build.
- Modern KMP increasingly favors plain interfaces + dependency injection or `@OptIn`-gated APIs over `expect`/`actual` for flexibility, but `expect`/`actual` remains the core mechanism for platform-specific glue. Coroutines and many stdlib pieces are themselves multiplatform.

### Q50. [Coding] Implement a custom property delegate (`getValue`/`setValue`) and a `provideDelegate`. What's the use case?

```kotlin
class TrimmedString(initial: String) {
    private var value = initial.trim()
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: String) {
        value = newValue.trim()        // enforce invariant on every write
    }
}

class Form {
    var name: String by TrimmedString("  Ada  ")
}

// provideDelegate: customize creation, e.g., validate property name once
class Logged<T>(private var v: T) {
    operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): Logged<T> {
        require(prop.name.isNotBlank())
        return this
    }
    operator fun getValue(thisRef: Any?, prop: KProperty<*>) = v
    operator fun setValue(thisRef: Any?, prop: KProperty<*>, value: T) {
        println("${prop.name}: $v -> $value"); v = value
    }
}
```

`getValue`/`setValue` intercept reads/writes — useful for validation, lazy/observable semantics, mapping to a backing store (DB, `SharedPreferences`, JSON), or dependency injection. `provideDelegate` runs once at property creation, letting you inspect the `KProperty` (name, annotations) and choose or validate the delegate before it's installed — frameworks use it to wire properties by name.

### Q51. [Theory] What are the performance and allocation characteristics engineers must understand for hot paths?

Several Kotlin conveniences have allocation costs to watch on hot paths:

- **Boxing** — `Int?`, generics over primitives, and value classes used nullably/generically box to `java.lang.Integer`. Prefer non-nullable primitives and primitive-specialized arrays (`IntArray`).
- **Lambdas** — a non-`inline` higher-order function allocates a function object (and captured variables) per call; mark small hot utilities `inline`.
- **Sequences vs lists** — sequences add per-element iterator/lambda indirection; for small collections, eager operations are faster.
- **`for (i in 0..n)`** over `IntRange` is optimized to a counting loop (no `Iterator` allocation), but `(0..n).map { }` allocates.
- **Coroutine overhead** — each suspend frame creates a continuation object; fine for I/O concurrency, but don't wrap trivial CPU work in coroutines.
- **`@JvmStatic`/`const`** avoid extra indirection for hot constants.

The discipline is the same as Java's: measure with a profiler/JMH, then strip allocations from the inner loop. Kotlin's abstractions are mostly zero- or low-cost, but "mostly" is where production incidents hide.

### Q52. [Behavioral] You discover a critical concurrency bug in a coroutine-heavy production service. Walk me through how you handle it.

First, stabilize: assess blast radius (error rates, affected users), and if it's actively causing damage, mitigate immediately — roll back the suspect deploy or feature-flag the path off — before root-causing. Buying time beats a hasty fix.

Then diagnose methodically. Concurrency bugs in coroutines usually fall into a few buckets, and I'd reason about which: a swallowed `CancellationException` (broad `catch (e: Exception)` breaking structured cancellation), shared mutable state mutated without confinement or a mutex, a blocking call on `Dispatchers.Default`/`Main` starving the pool, or a leaked scope outliving its lifecycle. I'd reproduce in a controlled environment, ideally with a focused test using `runTest` and a virtual time scheduler so the race is deterministic, not flaky.

For the fix I favor making illegal states unrepresentable: confine shared state to a single coroutine or guard it with `Mutex`/`Channel` rather than ad-hoc locks; ensure `CancellationException` is always rethrown; verify dispatcher choices. I'd add a regression test that fails on the old code.

Afterward, a blameless postmortem: what made this class of bug possible, and what guardrail (lint rule banning broad catches in coroutines, a code-review checklist, a structured-concurrency convention) prevents the next one. The behavioral signal is calm prioritization — mitigate, diagnose, fix with a test, then prevent systemically — without blaming individuals.

### Q53. [Theory] How do `inline` classes, `@JvmInline`, and the upcoming/valhalla story interact, and what are the boxing rules precisely?

A `@JvmInline value class` is represented at runtime by its single underlying value wherever the static type permits — no wrapper object is allocated. But it **is boxed** (a real wrapper instance materializes) in these cases:

- Used as a **nullable** type (`UserId?`) — needs a reference to represent null.
- Used as a **generic type argument** (`List<UserId>`) — erasure requires a reference.
- Assigned to a **supertype/interface** the value class implements.
- Used where reflection or `Any` is expected.

```kotlin
@JvmInline value class UserId(val raw: Long)
fun f(id: UserId) {}             // 'id' passed as a primitive long — unboxed
fun g(id: UserId?) {}            // boxed (nullability)
val xs: List<UserId> = listOf(UserId(1))   // boxed (generics)
```

Mangling: to prevent JVM signature clashes (two functions `f(UserId)` and `f(Long)` would collide after unboxing), the compiler **name-mangles** functions taking value classes, which is why they're awkward to call from Java. Project Valhalla's value objects aim to make this a JVM-native concept, potentially eliminating boxing in the generics case and changing this calculus — but as of 2026, the Kotlin compiler's erasure-plus-mangling scheme is what ships.

### Q54. [Coding] Implement a structured-concurrency-correct timeout-with-fallback that never leaks coroutines.

```kotlin
suspend fun fetchWithFallback(): Data = coroutineScope {
    try {
        withTimeout(2_000) {              // cancels the block on timeout
            primary.fetch()              // any suspend call here is cancelled cooperatively
        }
    } catch (e: TimeoutCancellationException) {
        // withTimeout throws this subclass; it is safe to catch (unlike plain CancellationException)
        fallback.fetch()
    }
}

// Concurrent race: first success wins, loser is cancelled, none leak
suspend fun fastest(): Data = coroutineScope {
    select {
        async { sourceA.fetch() }.onAwait { it }
        async { sourceB.fetch() }.onAwait { it }
    }.also { coroutineContext.cancelChildren() }   // cancel the loser
}
```

Key correctness points:

- `withTimeout` throws `TimeoutCancellationException` (a `CancellationException` subtype) and cancels its block; catching *this specific* subclass for fallback is safe, whereas catching the bare `CancellationException` would break structured cancellation.
- Wrapping in `coroutineScope` guarantees no child outlives the function — even on timeout or exception, all children are cancelled and joined before returning.
- For races, `select` + cancelling the losers prevents leaked in-flight work.
- Never use `GlobalScope` for this — it detaches from structured concurrency and leaks.

### Q55. [Theory] How would you design a public Kotlin library API for both Kotlin and Java consumers, with binary-compatibility in mind?

I'd treat the ABI as a contract and design for two audiences:

**For Java ergonomics:** annotate the surface — `@JvmStatic` on companion factory methods, `@JvmOverloads` for default parameters, `@JvmField` for constants, `@JvmName` to avoid name clashes, `@Throws` so checked-exception semantics survive. Avoid exposing `suspend` functions, `Flow`, `Result`, and value classes directly to Java; provide blocking/`CompletableFuture` bridges and plain types at the Java boundary.

**For binary compatibility:** Kotlin features that are ABI-fragile need care — adding a parameter (even with a default) changes the JVM signature, turning a `suspend` function non-suspend (or vice versa) is breaking, and reordering data-class properties changes `componentN`/`copy`. I'd:

- Run **binary-compatibility-validator** in CI to catch ABI breaks before release.
- Mark unstable APIs with an `@RequiresOptIn` annotation so consumers opt in knowingly.
- Prefer interfaces over data classes in the public API (data classes leak `copy`/`componentN` into the ABI and can't evolve cleanly).
- Use `@Deprecated(level = HIDDEN/ERROR)` with replacement bridges to evolve without breaking old binaries.

The overarching principle: every public symbol is a promise, so keep the surface small, explicit, and validated by tooling rather than discipline alone.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q56. [Theory] What is the difference between a top-level `fun main()` and the entry point's relationship to the `MainKt` class on the JVM?

Kotlin has no notion of a "main class" the way Java does. A top-level `fun main()` in a file `App.kt` is compiled into a `public static void main(String[])` method on a synthesized class named `AppKt` (file name + `Kt` suffix). The JVM still requires a class with a static `main` to start, so the compiler manufactures one.

```kotlin
// File: App.kt
fun main(args: Array<String>) {
    println("args: ${args.joinToString()}")
}
// Runnable as: java -cp out AppKt
```

You can override the generated class name with a file-level annotation:

```kotlin
@file:JvmName("Application")
fun main() { }   // now the entry class is "Application", launched as: java Application
```

Kotlin 1.3+ also allows a parameterless `main()`. The takeaway is that "top-level" is a Kotlin-source convenience — at the bytecode level everything is still a static method on a class, and `@file:JvmName` controls that class's name, which matters for build scripts and manifests.

#### Q57. [Theory] How does Kotlin represent `Int` vs `Int?` at the bytecode level, and why does that distinction cost nothing for non-nullable primitives?

A non-nullable `Int` compiles to the JVM primitive `int` — no object, no allocation, stored directly in a stack slot or local. A nullable `Int?` cannot be a primitive because primitives have no way to represent `null`, so it compiles to the boxed reference type `java.lang.Integer`.

```kotlin
val a: Int = 5        // JVM: int  (primitive, on the stack)
val b: Int? = 5       // JVM: Integer (boxed object on the heap)
val c: Int? = null    // JVM: Integer reference = null
```

This is why nullable primitives carry a hidden allocation cost on hot paths: every `Int?` value (outside the small Integer cache of -128..127) is a heap object. The same applies to `Long?`, `Double?`, `Boolean?`, etc. For collections, `List<Int>` always boxes (generics erase to `Object`), whereas `IntArray` stays a primitive `int[]`. The mental model: nullability and generics force boxing; plain non-nullable primitive locals stay unboxed and free.

#### Q58. [Practical] What does the `?.let { }` idiom compile to, and how is it different from a plain null check with an `if`?

`x?.let { block }` evaluates `x` once, and if it is non-null, invokes the lambda with `x` as `it`; otherwise the whole expression is `null`. It is most valuable when `x` is a mutable `var` or a property where a smart cast would not apply, because `let` captures the value into a stable parameter.

```kotlin
var name: String? = compute()

// Smart cast may fail on a mutable/var or open property:
if (name != null) { println(name.length) }   // can error: "smart cast impossible"

// let captures into a stable 'it', always works:
name?.let { println(it.length) }              // 'it' is a local val, immune to reassignment
```

For a simple local `val`, both forms are equivalent and the `if` is often clearer. For nullable `var`s, properties with custom getters, or values from another module, `?.let` is the robust idiom because it snapshots the value. Because `let` is `inline`, there is no lambda-allocation penalty. Avoid deeply nesting multiple `?.let` blocks — that is a sign you should restructure with early returns or `?:`.

#### Q59. [Theory] What is the `Comparable`/`Comparator` story in Kotlin, and how do `compareBy`, `thenBy`, and `reversed` compose?

`Comparable<T>` defines natural ordering via `compareTo`; `Comparator<T>` is an external ordering strategy. Kotlin's stdlib gives you builder functions that compose comparators declaratively instead of hand-writing `compareTo` chains.

```kotlin
data class Person(val last: String, val first: String, val age: Int)

val byNameThenAge = compareBy<Person>({ it.last }, { it.first })
    .thenByDescending { it.age }

val sorted = people.sortedWith(byNameThenAge)

// nullsFirst / nullsLast wrap a comparator to handle nulls explicitly:
val safe = compareBy<Person?>(nullsLast(byNameThenAge)) { it }
```

`compareBy { selector }` builds a comparator from a key; `thenBy`/`thenByDescending` add tie-breakers; `reversed()` flips direction; `nullsFirst`/`nullsLast` decorate any comparator to place nulls deterministically. These compose left-to-right, mirroring SQL `ORDER BY a, b DESC`. Implementing `Comparable` directly is for a type's single natural order; comparators are for the many context-specific orderings.

#### Q60. [Practical] How do `takeIf` and `takeUnless` work, and where do they read better than an `if`?

`takeIf { predicate }` returns the receiver if the predicate holds, else `null`; `takeUnless` is the inverse. They turn a condition into a nullable value you can chain with `?.` and `?:`.

```kotlin
val even = number.takeIf { it % 2 == 0 }          // Int? — null if odd
val nonBlank = input.trim().takeIf { it.isNotEmpty() } ?: "default"

// Useful in a fluent chain where you want to bail to null mid-pipeline:
val config = loadFile()
    .takeIf { it.exists() }
    ?.readText()
    ?.let(::parse)
```

They read best when you want to express "use this value only if it satisfies a condition, otherwise treat it as absent" within an expression. For control flow with side effects or multiple statements, a plain `if` is clearer. Both are `inline`, so there is no allocation. A common pitfall: `takeIf` on an expensive-to-compute receiver still computes the receiver first — it only gates the result, not the computation.

#### Q61. [Theory] What is the difference between `toString()` generated by a data class and a manually-overridden one, and when does the generated form bite you?

A data class generates `toString()` listing every primary-constructor property as `ClassName(prop1=value1, prop2=value2)`. This is convenient for logging and debugging, but it has two sharp edges.

```kotlin
data class Credentials(val user: String, val password: String)
println(Credentials("ada", "s3cr3t"))   // Credentials(user=ada, password=s3cr3t) — LEAKS the secret
```

First, it can leak sensitive fields (passwords, tokens, PII) straight into logs. The fix is to override `toString()` or wrap secrets in a value class with a redacting `toString`. Second, properties declared in the class *body* (not the constructor) are excluded, so the generated string can omit state you care about, surprising readers. For domain types that travel into logs, prefer an explicit `toString()` that redacts sensitive fields and includes only what is safe and useful.

#### Q62. [Practical] How do destructuring declarations work, what are their limits, and how do you opt out of unused components?

Destructuring assigns multiple variables from a single object by calling `component1()`, `component2()`, etc. Data classes and `Pair`/`Triple`/`Map.Entry` provide these automatically; for other classes you declare `operator fun componentN()`.

```kotlin
data class Point(val x: Int, val y: Int)
val (x, y) = Point(3, 4)

for ((key, value) in map) { /* ... */ }

val (_, second, _) = Triple(1, 2, 3)   // underscore skips unused components
```

Limits and pitfalls: destructuring is **positional**, not by name — reordering a data class's constructor properties silently changes what each variable binds to, a real refactoring hazard. It does not work on arbitrary classes without `componentN` operators, and it cannot partially destructure by name. Use `_` to skip components you do not need (this avoids computing/binding them and documents intent). Reserve destructuring for small, stable structures where positional meaning is obvious.

#### Q63. [Theory] What exactly is the receiver in an extension function, and how does shadowing between member and extension play out?

In `fun String.shout()`, the `String` before the dot is the **extension receiver**, bound to `this` inside the body and passed as a hidden first parameter in the compiled static method. Resolution of which function runs is decided at compile time from the receiver's **static** type.

```kotlin
open class A
class B : A()
fun A.who() = "A-ext"
fun B.who() = "B-ext"

val ref: A = B()
println(ref.who())   // "A-ext" — resolved on declared type A, NOT runtime type B
```

When a class has a *member* function with the same name and signature as an extension, the **member always wins** — the extension is silently shadowed and never called. This is by design (a class owner should not have behavior overridden by external extensions), but it can surprise you when a library later adds a member that masks your extension. The rule to remember: extensions are statically dispatched and lose to members; never rely on them for polymorphism.

#### Q64. [Practical] How do `associate`, `associateBy`, `groupBy`, and `partition` differ, and what do they return?

These are stdlib transforms that reshape collections into maps or grouped structures, and confusing them is a common interview slip.

```kotlin
val users = listOf(User("Ada", 36), User("Grace", 36), User("Linus", 54))

users.associateBy { it.name }              // Map<String, User>  key = name, value = whole element
users.associate { it.name to it.age }      // Map<String, Int>   you supply both key and value
users.groupBy { it.age }                   // Map<Int, List<User>>  buckets elements by key
users.associateWith { it.age }             // Map<User, Int>     element is key, you supply value
val (adults, minors) = users.partition { it.age >= 18 }  // Pair<List, List>
```

Key distinctions: `associateBy` keeps the last element on key collisions (one value per key), whereas `groupBy` collects **all** elements per key into a list. `associate` lets you build both key and value; `associateWith` keeps elements as keys. `partition` splits into a `Pair` of (matching, non-matching). Choosing `associateBy` when you meant `groupBy` silently drops duplicates — a classic data-loss bug.

#### Q65. [Theory] What is the `Unit`-returning lambda conversion, and why can you omit `return` of `Unit` and the `else` of a statement `when`?

`Unit` is a real singleton type with one value, `Unit`. A lambda whose expected type returns `Unit` will accept a body whose last expression is *anything* — the compiler coerces it to `Unit` (the "Unit conversion"). This is why callback lambdas do not need an explicit `Unit` return.

```kotlin
fun onEach(action: (Int) -> Unit) { /* ... */ }
onEach { println(it) }        // println returns Unit; fine
onEach { it.toString() }      // returns String, coerced to Unit — allowed

// As a STATEMENT, when needs no else:
when (x) { 1 -> doA(); 2 -> doB() }   // OK as statement; result discarded
// As an EXPRESSION, it must be exhaustive:
val r = when (x) { 1 -> "a"; 2 -> "b"; else -> "?" }  // else required
```

The dividing line is statement vs expression. Used for its side effects (statement position), `when` discards its value and needs no `else`; used to produce a value (expression position), it must cover all cases. `Unit` being a genuine type (not Java's `void` keyword) is what lets `(T) -> Unit` be a normal generic function type.

### 🟡 — extended

#### Q66. [Theory] How does the compiler decide a smart cast is "stable," and what changed with K2's data-flow analysis?

A smart cast is permitted only when the compiler can prove the value cannot change between the type/null check and its use — the property must be **stable**. The stability rules: local `val`s are always stable; `val`s with no custom getter from the same module are stable; `var`s, `open`/custom-getter properties, and properties from other modules are *not* stable (their value could differ on the next read).

```kotlin
class Box(val ro: String?, var rw: String?) {
    val open: String? get() = ro
}

fun f(b: Box) {
    if (b.ro != null) b.ro.length        // OK — stable val
    if (b.rw != null) b.rw.length        // ERROR — var, could change
    val snap = b.rw
    if (snap != null) snap.length        // OK — captured into local val
}
```

K2 rebuilt this on a unified FIR with stronger flow-sensitive analysis: it now narrows across `&&`/`||`, through `when` subjects, after `return`/`throw` in branches, and following bound-smart-cast through certain calls with contracts. The "must be provably stable" principle is unchanged, but K2 proves stability in more places the old frontend gave up on, reducing spurious "smart cast impossible" errors.

#### Q67. [Theory] What is the dispatch difference between `open fun`, `final fun`, and extension functions in terms of the JVM `invokevirtual`/`invokestatic` instructions?

Kotlin methods are `final` by default, which changes the bytecode the JVM emits. A `final` member can be dispatched without a vtable lookup; an `open` member requires virtual dispatch; an extension function is a static method entirely.

- **`open fun`** → emitted as a virtual method; calls use `invokevirtual`, resolved on the runtime type (true polymorphism).
- **`final fun`** (the default) → still typically `invokevirtual` on the JVM (the JVM has no "final call" opcode for instance methods), but `final` lets the JIT devirtualize and inline aggressively because no override is possible.
- **extension fun** → compiled to a `static` method taking the receiver as the first argument; calls use `invokestatic`, no dispatch on runtime type at all.

```kotlin
class C { fun a() {} ; open fun b() {} }   // a: final, b: virtual
fun C.c() {}                               // static method Ck.c(C)
```

The practical consequence: defaulting to `final` makes JIT optimization (inlining, devirtualization) easier and is a performance reason Kotlin chose closed-by-default. Extensions, being static, never participate in overriding — reinforcing why they are not polymorphic (Q63).

#### Q68. [Practical] How does `sequence { }` with `yield`/`yieldAll` work internally, and how is it related to coroutines?

`sequence { }` builds a lazy `Sequence` using a **restricted suspending lambda**. `yield(value)` and `yieldAll(iterable)` are `suspend` functions that suspend the builder until the consumer pulls the next element — it is coroutine machinery repurposed for synchronous, single-threaded lazy generation.

```kotlin
val fibs = sequence {
    var a = 0; var b = 1
    while (true) {
        yield(a)                 // suspends until next() is called
        val next = a + b; a = b; b = next
    }
}
println(fibs.take(8).toList())   // [0, 1, 1, 2, 3, 5, 8, 13]
```

The builder runs on a `SequenceScope` marked `@RestrictsSuspension`, which forbids calling arbitrary `suspend` functions (like `delay` or network calls) inside — only `yield`/`yieldAll` are allowed. This keeps the sequence purely synchronous: there is no dispatcher, no thread switch, just a state machine that pauses at each `yield`. It is the cleanest way to express lazy, potentially infinite sequences (Fibonacci, pagination, tree traversal) without manually writing an `Iterator`.

#### Q69. [Theory] What is the difference between `coroutineContext`, a `CoroutineDispatcher`, and a `CoroutineScope`, and how do `+` and element keys work?

A `CoroutineContext` is an indexed set of elements (a map keyed by `CoroutineContext.Key`), where each element is itself a context. A `CoroutineDispatcher`, a `Job`, a `CoroutineName`, and a `CoroutineExceptionHandler` are all context *elements*. A `CoroutineScope` is just a holder of a `CoroutineContext` (conventionally one containing a `Job`).

```kotlin
val ctx = Dispatchers.IO + CoroutineName("worker") + SupervisorJob()
//        ^dispatcher       ^name element            ^job element
//        combined with operator+ into one CoroutineContext

withContext(Dispatchers.Default + CoroutineName("cpu")) { /* ... */ }

// Retrieve an element by its key:
val name = coroutineContext[CoroutineName]?.name
```

The `+` operator merges contexts; if both sides have an element with the same key, the right-hand one wins (so `Dispatchers.IO + Dispatchers.Default` yields `Default`). Each element type has a companion `Key` used for lookup (`coroutineContext[Job]`, `coroutineContext[CoroutineDispatcher]`). Understanding context as a typed, mergeable map demystifies why `launch(Dispatchers.IO + name)` works and how children inherit and override their parent's context.

#### Q70. [Practical] How does `withContext` differ from `launch`/`async` for switching dispatchers, and why is it the right tool for sequential context changes?

`withContext(ctx) { block }` is a `suspend` function that runs `block` in the given context, **suspends the caller until block completes**, and returns its result. It does not start a concurrent coroutine — it is the sequential way to switch dispatchers within an already-running coroutine.

```kotlin
suspend fun loadAndRender() {
    val data = withContext(Dispatchers.IO) { readFromDisk() }   // switch to IO, await result
    withContext(Dispatchers.Main) { render(data) }              // switch back to Main
}
```

Contrast with `async`: `async { }.await()` also returns a value but spins up a child coroutine (with its own `Job`), which is overhead and conceptual noise when you only want to *move* work to another thread, not run it concurrently. Use `withContext` for "do this part on a different dispatcher, then continue"; use `async` only when you genuinely want concurrency (two things running at once, awaited later). `withContext` also correctly propagates cancellation and exceptions inline, making it the safe default for dispatcher hops.

#### Q71. [Coding] Implement a `Mutex`-guarded shared counter and explain why `Mutex` is preferred over `synchronized` in coroutines.

```kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SafeCounter {
    private val mutex = Mutex()
    private var count = 0

    suspend fun increment() {
        mutex.withLock { count++ }    // suspends (does not block) if held
    }

    fun get() = count
}

suspend fun demo() = coroutineScope {
    val counter = SafeCounter()
    repeat(1000) { launch { counter.increment() } }
    // coroutineScope waits for all children
    // counter.get() == 1000 deterministically
}
```

`Mutex.withLock` is a `suspend` function: when the lock is held, the coroutine **suspends** and frees the thread for other work, whereas a JVM `synchronized` block **blocks** the OS thread. Blocking inside a coroutine defeats the lightweight-concurrency model and can deadlock a small dispatcher pool if every thread is parked waiting on a lock. `Mutex` is also non-reentrant by design (re-locking from the same coroutine deadlocks), which discourages the re-entrant patterns that hide bugs. For pure state, prefer confining mutation to a single coroutine or an actor over locking at all; reach for `Mutex` only when shared mutable state across coroutines is unavoidable.

#### Q72. [Theory] What is the difference between `Flow.flatMapConcat`, `flatMapMerge`, and `flatMapLatest`?

All three flatten a `Flow<Flow<T>>` (each upstream value produces an inner flow) but differ in how they handle concurrency and overlap:

```kotlin
upstream
    .flatMapConcat { id -> fetchDetails(id) }   // one inner flow at a time, in order
    .flatMapMerge(concurrency = 4) { id -> fetchDetails(id) }  // up to 4 inner flows concurrently, interleaved
    .flatMapLatest { id -> fetchDetails(id) }   // cancel previous inner flow when a new id arrives
```

- **`flatMapConcat`** — sequential: fully collects each inner flow before starting the next. Preserves order, no concurrency.
- **`flatMapMerge`** — concurrent: runs several inner flows at once (bounded by `concurrency`), emissions interleave, order not preserved.
- **`flatMapLatest`** — cancels the currently-running inner flow as soon as a new upstream value appears; only the latest matters (search-as-you-type, latest-config-wins).

Choosing the wrong one causes subtle bugs: `flatMapConcat` where you wanted parallelism is slow; `flatMapMerge` where order matters scrambles results; `flatMapLatest` where you needed every result drops in-flight work.

#### Q73. [Theory] How do `StateFlow` and `SharedFlow` differ in equality conflation, replay, and initial value, and when do you choose each?

Both are hot flows, but their buffering semantics differ in ways that matter for correctness:

- **`StateFlow`** — always has a current value (requires an initial value), replay = 1, and **conflates by equality**: setting `.value` to something `equals()` the current value emits nothing. New collectors immediately receive the latest value. Ideal for state that has a single current truth (UI screen state).
- **`SharedFlow`** — no initial value required, configurable `replay` (0..N), configurable `extraBufferCapacity` and `onBufferOverflow`, and does **not** conflate by equality — every `emit` is delivered. Ideal for events (navigation, snackbars, one-shot signals) where duplicates and order matter.

```kotlin
val state = MutableStateFlow(0)
state.value = 0; state.value = 0   // emits at most once (equal values conflated)

val events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 16)
events.emit(Click); events.emit(Click)   // both delivered to active collectors
```

A frequent bug: using `StateFlow` for events causes lost or merged events (two identical events conflate to one, and a re-subscribing collector replays the last event spuriously). Rule: `StateFlow` for state, `SharedFlow(replay = 0)` for events.

#### Q74. [Practical] What does `coroutineScope { }` guarantee versus `supervisorScope { }`, and how does each treat a child failure?

Both create a new scope and suspend until all children complete, but they differ in failure propagation:

- **`coroutineScope`** — if any child throws (uncaught), the scope cancels all other children and rethrows the exception to the caller. All-or-nothing: one failure fails the whole block.
- **`supervisorScope`** — a child's failure is isolated; it does **not** cancel siblings or the scope. Each child must handle its own failure (e.g., `launch` with a `CoroutineExceptionHandler`, or `async`'s exception surfaced at `await`).

```kotlin
suspend fun allOrNothing() = coroutineScope {
    launch { failFast() }      // failure cancels the sibling below
    launch { slowWork() }      // cancelled when sibling fails
}

suspend fun independent() = supervisorScope {
    launch(handler) { mightFail() }   // fails alone
    launch { keepsRunning() }         // unaffected
}
```

Choose `coroutineScope` when the children form one logical unit of work that should fail together (fetch user AND feed for a dashboard — if one fails the result is useless). Choose `supervisorScope` when children are independent and partial success is acceptable (refresh several widgets; one failing should not blank the others). Both still wait for all children, so neither leaks coroutines.

#### Q75. [Theory] How does `runBlocking` differ from `coroutineScope`, and why is `runBlocking` dangerous in production async code?

`runBlocking` is a **bridge** from the blocking world into coroutines: it starts a coroutine and **blocks the current thread** until it (and its children) complete, running an event loop on that thread. `coroutineScope` is a pure `suspend` function that suspends without blocking any thread.

```kotlin
fun main() = runBlocking {        // OK — top of main / tests, blocking the main thread is fine
    val r = fetchData()
}

suspend fun service(): Data = coroutineScope {   // correct inside suspend code — no thread blocked
    val a = async { fetchA() }; val b = async { fetchB() }
    combine(a.await(), b.await())
}
```

The danger: calling `runBlocking` from inside an already-coroutine context (e.g., inside a `suspend` function, a `Flow`, or a request handler on a dispatcher) **blocks a pooled thread**, defeating the whole point of suspension and risking pool starvation or deadlock if that pool is small. `runBlocking` belongs only at the boundary between blocking and suspending worlds: `main`, tests (`runTest` is even better), and `@Test` methods. Inside coroutine code, use `coroutineScope`/`withContext` instead.

#### Q76. [Practical] How do you write a deterministic coroutine test with `runTest` and virtual time, and what does the test scheduler buy you?

`runTest` (from `kotlinx-coroutines-test`) runs the test body in a coroutine backed by a `TestCoroutineScheduler` that uses **virtual time**: `delay` calls are skipped instantly by advancing a virtual clock rather than waiting wall-clock time. This makes time-dependent tests fast and deterministic.

```kotlin
@Test
fun retriesThenSucceeds() = runTest {
    val repo = FlakyRepo(failTimes = 2)
    val result = async { repo.fetchWithRetry() }   // internally delays between retries

    advanceUntilIdle()        // run all pending coroutines / skip all delays
    assertEquals(Data("ok"), result.await())
    assertEquals(0, currentTime % 1)   // currentTime reflects virtual elapsed ms
}
```

The scheduler lets you control time explicitly: `advanceTimeBy(ms)` skips a fixed duration, `advanceUntilIdle()` runs everything until no work remains, and `runCurrent()` runs only what is currently scheduled. This turns inherently racy, time-based logic (retries with backoff, timeouts, debouncing) into deterministic assertions. Use `StandardTestDispatcher` for explicit stepping and `UnconfinedTestDispatcher` when you want eager execution; inject the test dispatcher into production code rather than hard-coding `Dispatchers.IO` so tests can substitute it.

### 🟠 — extended

#### Q77. [Theory] How does declaration-site variance get encoded into JVM bytecode given that the JVM has no variance, and what is the role of `@JvmSuppressWildcards`?

The JVM's generics support only **use-site** wildcards (`? extends`, `? super`), not declaration-site variance. So Kotlin translates an `out`/`in` declaration into wildcards on the *generated Java-facing signatures*. A Kotlin `List<out T>`-style covariant parameter appears to Java callers as `List<? extends T>`; an `in` (contravariant) one as `? super T`.

```kotlin
class Box<out T>(val value: T)
fun produce(): Box<String> = Box("x")
// Java sees: Box<String> produce()  (out at use sites becomes ? extends String where relevant)

fun consume(items: List<String>) {}   // Java sees List<? extends String> by default for 'out'-projected uses
```

This wildcard generation can produce awkward Java signatures (`? extends`) where you wanted an exact type. `@JvmSuppressWildcards` strips the wildcard (forcing the invariant `List<String>`), and `@JvmWildcard` forces one where Kotlin would not emit it. Library authors targeting Java consumers use these to control the generated API shape. The deeper point: Kotlin's clean declaration-site variance is a *source-level* feature compiled down to the JVM's use-site wildcard model.

#### Q78. [Theory] What is the precise semantics of `lateinit`, when can you use it, and how does `::prop.isInitialized` work?

`lateinit var` declares a non-null property whose initialization is deferred — you promise to assign it before first read, and the compiler omits the usual "must be initialized" requirement. Accessing it before assignment throws `UninitializedPropertyAccessException` (not NPE).

```kotlin
class Service {
    private lateinit var client: HttpClient
    fun init() { client = HttpClient() }
    fun use() {
        if (::client.isInitialized) client.send()   // guard against early access
    }
}
```

Constraints: `lateinit` works only on `var`s, only on non-null **reference** types (not primitives like `Int` — they have no "uninitialized" sentinel), not in the primary constructor, and not with a custom getter/setter. Under the hood it is backed by a nullable field that is `null` until assigned; `isInitialized` (accessed via a property reference `::client`) checks that field for null. Use it for dependency-injected or framework-instantiated fields (Android views, Spring beans) where constructor injection is impossible. Prefer `by lazy` when the value can be computed on demand, and a nullable type when "not set" is a legitimate runtime state rather than a programming error.

#### Q79. [Coding] Implement a reusable `retry` with exponential backoff and jitter as a `suspend` function, respecting cancellation.

```kotlin
import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

suspend fun <T> retry(
    maxAttempts: Int = 5,
    initialDelay: Duration = 100.milliseconds,
    maxDelay: Duration = 5_000.milliseconds,
    factor: Double = 2.0,
    retryOn: (Throwable) -> Boolean = { it !is CancellationException },
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(maxAttempts - 1) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e                       // never swallow cancellation
        } catch (e: Throwable) {
            if (!retryOn(e)) throw e
        }
        val jitter = Random.nextLong(currentDelay.inWholeMilliseconds / 2 + 1)
        delay(currentDelay.inWholeMilliseconds + jitter)   // suspends, cancellable
        currentDelay = (currentDelay * factor).coerceAtMost(maxDelay)
    }
    return block()                        // last attempt, exception propagates
}
```

Correctness points: `CancellationException` is caught and rethrown explicitly so the retry loop never defeats structured cancellation. `delay` is the suspension point, so cancellation propagates during the backoff wait. **Jitter** randomizes the delay to avoid the thundering-herd problem where many clients retry in lockstep after an outage. `coerceAtMost(maxDelay)` caps unbounded exponential growth. The final attempt is outside the loop so its exception surfaces to the caller rather than being retried-then-swallowed.

#### Q80. [Theory] How does the `CoroutineExceptionHandler` interact with `launch`, `async`, and the `Job` hierarchy, and where does it actually fire?

A `CoroutineExceptionHandler` is a context element invoked as a **last resort** for uncaught exceptions, but only under specific conditions that trip people up:

- It fires only for **`launch`**-style (root) coroutines whose exception is otherwise uncaught — and only when installed in the **scope/root** coroutine, not a nested child (a child's exception propagates to its parent first; the handler at the child level is ignored).
- It does **nothing for `async`**: an `async`'s exception is encapsulated in the `Deferred` and re-thrown at `await()`. Installing a handler on an `async` has no effect.
- It does **nothing for `CancellationException`**, which is treated as normal cooperative cancellation, not a failure.

```kotlin
val handler = CoroutineExceptionHandler { _, e -> log.error("uncaught", e) }
val scope = CoroutineScope(SupervisorJob() + handler)

scope.launch { throw RuntimeException("boom") }   // handler FIRES (root launch, uncaught)
scope.launch {
    launch { throw RuntimeException("boom") }      // propagates to parent; handler at scope fires
}
val d = scope.async { throw RuntimeException("x") } // handler does NOT fire; thrown at d.await()
```

The mental model: exceptions flow *up* the `Job` tree; the handler only catches what reaches an uncancelled root without being a `CancellationException`. For per-call error handling, use `try/catch` around `await()` or inside the coroutine — the handler is for global, unrecoverable logging/crash reporting, akin to an uncaught-exception handler for threads.

#### Q81. [Theory] What is context preservation in `Flow`, why does calling `withContext` inside `flow { }` throw, and how does `flowOn` solve it?

A `Flow` enforces **context preservation**: the coroutine context in which `emit` is called must be the same as the context in which `collect` was invoked. This invariant guarantees that emissions are confined and predictable. Calling `withContext(otherDispatcher) { emit(x) }` inside a `flow { }` builder emits from a *different* context than the collector, violating the invariant — the framework detects this and throws `IllegalStateException: Flow invariant is violated`.

```kotlin
// WRONG — emits from a different context than the collector:
flow {
    withContext(Dispatchers.IO) { emit(readFile()) }   // throws at runtime
}

// RIGHT — flowOn changes the UPSTREAM context transparently:
flow {
    emit(readFile())            // runs on IO because of flowOn below
}.flowOn(Dispatchers.IO)
    .collect { render(it) }     // collector stays on its own (e.g., Main) context
```

`flowOn(dispatcher)` works by inserting a channel between upstream and downstream: everything *upstream* of `flowOn` runs on the given dispatcher, and the values are handed across the channel to the downstream collector on its original context. This preserves the invariant (the producer never directly emits into the collector's context) while still moving the heavy work off the collector's thread. The rule: never switch context with `withContext` around `emit`; always use `flowOn` for upstream dispatching.

#### Q82. [Theory] How do inline functions enable non-local returns, and what is the difference between a labeled return and a non-local return in a lambda?

Because an `inline` function's lambda body is copied into the call site, a bare `return` inside that lambda returns from the **enclosing function**, not just the lambda — a **non-local return**. This is impossible for non-inline lambdas (the lambda is a separate function object), where `return` is forbidden and you must use a labeled return.

```kotlin
fun find(list: List<Int>, target: Int): Boolean {
    list.forEach {                 // forEach is inline
        if (it == target) return true   // non-local: returns from find()
    }
    return false
}

fun example(list: List<Int>) {
    list.forEach {
        if (it == 0) return@forEach    // labeled: returns only from the lambda (continue-like)
        process(it)
    }
}
```

A non-local `return` exits the outer function entirely; a `return@label` exits only the lambda (behaving like `continue` in a loop). Non-local returns are only possible inside `inline` function lambdas — that is one of inlining's main motivations. `crossinline` (Q18) is the modifier that *forbids* non-local returns even on an inlined lambda, needed when the lambda is called from a nested context where a non-local return would be unsound.

#### Q83. [Practical] How does `buildList`/`buildMap`/`buildSet` work, and why are they preferable to creating a `MutableList` and returning it?

The `buildList { }` family gives you a scoped mutable builder and returns a **read-only** snapshot, combining the convenience of mutation with the safety of an immutable result.

```kotlin
val items: List<Int> = buildList {
    add(1)
    addAll(listOf(2, 3))
    if (condition) add(4)
}   // returns a read-only List; the builder is no longer accessible
```

Why prefer it over `val l = mutableListOf<Int>(); ...; return l`:

- The returned type is `List` (read-only), so callers cannot mutate it, and you do not accidentally leak the mutable reference.
- The mutable builder is **scoped** to the lambda — after `buildList` returns, no one holds a mutable view, preventing the "someone kept the MutableList and mutated it later" class of bugs.
- It is implemented efficiently (the builder is the underlying list, frozen on return — no defensive copy in the common case).

There is an overload taking an initial capacity (`buildList(capacity) { }`) to pre-size the backing array for known sizes. Use these builders whenever you construct a collection conditionally or in a loop and want to expose it immutably.

#### Q84. [Theory] What is the difference between `typealias`, `inline value class`, and a plain wrapper class for adding domain meaning to a primitive?

All three attach a name to an underlying type, but they differ fundamentally in type safety and runtime cost:

```kotlin
typealias UserId = Long              // (1) just an alias — NO new type
@JvmInline value class OrderId(val raw: Long)   // (2) new type, usually no allocation
class AccountId(val raw: Long)       // (3) new type, always a heap object
```

- **`typealias`** — purely a source-level synonym. `UserId` *is* `Long`; you can pass a raw `Long` anywhere a `UserId` is expected and vice versa. It improves readability but provides **zero** type safety (mixing up two `Long`-based aliases compiles fine).
- **`inline value class`** — a genuine distinct type the compiler enforces (you cannot pass an `OrderId` where a `Long` is expected), but represented as the underlying `Long` at runtime in most positions (Q53), so no allocation on the happy path.
- **plain wrapper class** — also a distinct enforced type, but **always** allocates a heap object and adds an indirection, with full flexibility (multiple fields, inheritance, `init` logic).

The decision: use `typealias` only for readability of complex types (e.g., `typealias Handler = (Event) -> Unit`), never for safety; use a `value class` for cheap, type-safe domain primitives (IDs, money, units); use a plain class when you need more than one field or behavior that value classes forbid.

#### Q85. [Theory] How does Kotlin compile a `when` over a sealed hierarchy versus over an `enum` versus over arbitrary conditions, and what are the exhaustiveness guarantees?

The bytecode and exhaustiveness story differs by subject type:

- **`enum` subject** — compiled to an efficient `tableswitch`/`lookupswitch` on the enum's ordinal (via a synthetic `$VALUES` mapping array), and the compiler can verify all constants are covered for an expression `when`, allowing omission of `else`.
- **`sealed` subject** — compiled to a chain of `instanceof` (`is`) checks; the compiler knows the closed set of subtypes (same module/package) and verifies exhaustiveness, so no `else` is needed. Adding a subtype makes every non-exhaustive expression `when` a compile error.
- **arbitrary conditions** (subject-less `when` or `when` over `Any`) — compiled to sequential `if/else if` branches; the compiler **cannot** prove exhaustiveness, so an expression `when` requires an explicit `else`.

```kotlin
sealed interface S; object A : S; object B : S
fun f(s: S) = when (s) { A -> 1; B -> 2 }   // exhaustive, no else, instanceof chain

enum class E { X, Y }
fun g(e: E) = when (e) { E.X -> 1; E.Y -> 2 }  // exhaustive, tableswitch on ordinal
```

The key guarantee is **compile-time exhaustiveness** for closed sets (sealed + enum): the compiler is your safety net that every case is handled, and growth of the hierarchy forces you to revisit every match. For open conditions the burden is on you (`else`), because the compiler cannot enumerate the possibilities.

#### Q86. [Practical] What are the rules and pitfalls of operator overloading in Kotlin, and how do `plus`, `compareTo`, `get`/`set`, and `invoke` map to syntax?

Kotlin maps a fixed set of operator symbols to specially-named member/extension functions marked `operator`. You cannot invent new operators; you implement the predefined ones.

```kotlin
data class Vec(val x: Int, val y: Int) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)        // a + b
    operator fun get(i: Int) = if (i == 0) x else y         // a[i]
    operator fun compareTo(o: Vec) = (x*x+y*y).compareTo(o.x*o.x+o.y*o.y)  // a < b, a >= b
    operator fun invoke() = "($x, $y)"                       // a()
}
// a += b desugars to a = a.plus(b) (or a.plusAssign(b) if defined)
```

Mappings: `+` → `plus`, `-` → `minus`, `*` → `times`, `[]` → `get`/`set`, `()` → `invoke`, `in` → `contains`, `..` → `rangeTo`, `<`/`>`/`<=`/`>=` → `compareTo` (one function drives all four), `==` → `equals`, `+=` → `plusAssign` (falls back to `plus` + reassign). Pitfalls: overloading should preserve intuitive meaning (do not make `+` subtract); `compareTo` must be consistent with `equals` to avoid sorted-collection bugs; `plusAssign` on a `val` of a mutable type vs `plus` reassigning a `var` can silently pick different paths. Use operator overloading sparingly and only where the symbol's conventional meaning genuinely fits the domain (vectors, money, durations, matrices).

### 🔴 — extended

#### Q87. [Theory] How does the Kotlin compiler lower a `data class` to bytecode, and which methods can you override versus which are always generated?

For a `data class`, the compiler generates `equals`, `hashCode`, `toString`, `componentN()` (one per primary-constructor property), and `copy()`. The crucial nuance is which of these you can supply yourself:

- **`equals`/`hashCode`/`toString`** — you *may* declare them explicitly; if you do, the compiler does **not** generate that one and uses yours. (This lets you redact a secret in `toString` while keeping generated `equals`.)
- **`componentN`/`copy`** — always generated; you **cannot** provide custom versions, and they are derived strictly from the primary-constructor properties.

```kotlin
data class Token(val id: Long, val secret: String) {
    override fun toString() = "Token(id=$id, secret=***)"   // honored; equals/hashCode still generated
    // override fun copy(...) — NOT allowed
}
```

Other lowering facts: a data class is `final` (cannot be `open`), cannot be `abstract`/`sealed`/`inner`, must have at least one primary-constructor parameter, and those parameters must be `val`/`var`. `copy` is generated with defaults for every property, enabling `x.copy(field = new)`. Because `copy` and `componentN` are part of the generated ABI, **reordering or inserting constructor properties is a binary-incompatible change** — a key reason to avoid data classes in public library APIs (Q55).

#### Q88. [Theory] What is the memory model and happens-before story for coroutines across dispatcher hops, and what guarantees do you actually get?

Coroutines run on JVM threads, so they inherit the **Java Memory Model (JMM)**. A coroutine is not magically thread-safe; what coroutines provide is **happens-before edges at suspension/resumption boundaries**. When a coroutine suspends and later resumes (possibly on a different thread via `withContext` or dispatcher hop), the coroutines library establishes a happens-before relationship so writes made before the suspension are visible after the resumption — you do not need explicit synchronization for *your own* coroutine's sequential state across a dispatcher switch.

```kotlin
suspend fun pipeline() {
    var local = compute()                 // write on thread A
    withContext(Dispatchers.IO) {
        use(local)                        // safe to read on thread B — happens-before via withContext
    }
}
```

What you do **not** get: protection for state **shared** between concurrently-running coroutines. If two coroutines mutate the same `var` simultaneously (e.g., both `launch`ed on a multi-threaded dispatcher), you have a classic data race and must use a `Mutex`, confinement, atomics, or `StateFlow`/`Channel`. The mental model: sequential coroutine code across hops is safe (the library inserts the memory barriers); concurrent access to shared mutable state is exactly as unsafe as with threads. Single-threaded dispatchers (`Dispatchers.Main`, a `newSingleThreadContext`, or `limitedParallelism(1)`) sidestep races by serializing execution.

#### Q89. [Coding] Implement an `actor`-style state owner using a `Channel` to serialize access without locks, and explain why this beats a `Mutex`.

```kotlin
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

sealed interface CounterMsg
data class Inc(val by: Int) : CounterMsg
data class Get(val response: CompletableDeferred<Int>) : CounterMsg

fun CoroutineScope.counterActor(): SendChannel<CounterMsg> {
    val channel = Channel<CounterMsg>()        // mailbox
    launch {
        var state = 0                          // confined to THIS coroutine — no sharing
        for (msg in channel) {                 // processes one message at a time
            when (msg) {
                is Inc -> state += msg.by
                is Get -> msg.response.complete(state)
            }
        }
    }
    return channel
}

suspend fun demo() = coroutineScope {
    val actor = counterActor()
    repeat(1000) { launch { actor.send(Inc(1)) } }
    val reply = CompletableDeferred<Int>()
    actor.send(Get(reply))
    println(reply.await())                     // 1000, no locks
}
```

Why this beats a `Mutex`: the mutable `state` is **confined** to a single coroutine and never shared, so there is *no* shared mutable state to protect — the race condition is structurally eliminated rather than guarded after the fact. Messages are processed sequentially by the single consumer, giving serialized access without any thread blocking or lock contention; senders simply suspend on a full channel (natural backpressure). This "share by communicating" model (CSP-style, like Go channels) is more composable and deadlock-resistant than fine-grained locking, where lock ordering bugs and forgotten unlocks lurk. The tradeoff is the indirection of message types and the request/response plumbing (`CompletableDeferred`) for queries.

#### Q90. [Theory] How does `Dispatchers.IO` relate to `Dispatchers.Default`, what is `limitedParallelism`, and why does sharing a thread pool matter?

`Dispatchers.Default` and `Dispatchers.IO` are backed by the **same shared thread pool** (a single global pool of worker threads), but they apply different *parallelism limits* as views over it. `Default` is capped at the number of CPU cores (good for CPU-bound work — more threads than cores just causes context-switch thrash). `IO` permits a much larger parallelism (default 64 or more) because blocking I/O threads spend most time waiting, so over-subscription is beneficial.

```kotlin
withContext(Dispatchers.Default) { computeHeavy() }   // ≤ cores threads
withContext(Dispatchers.IO) { blockingRead() }        // up to 64+ threads
val db = Dispatchers.IO.limitedParallelism(8)         // a private view capped at 8 IO slots
withContext(db) { query() }                            // never uses more than 8 of IO's threads
```

`limitedParallelism(n)` creates a **view** over the underlying dispatcher that runs at most `n` coroutines concurrently — ideal for bounding access to a constrained resource (a JDBC connection pool of size 8, a rate-limited API). Because `IO` and `Default` share threads, switching between them is cheap (often no actual thread handoff — the coroutine continues on the same worker if the limit allows). The design point: one shared pool with parallelism views avoids the wasteful thread duplication of having separate fixed pools, while `limitedParallelism` gives you fine-grained backpressure on specific resources without spinning up bespoke executors.

#### Q91. [Theory] What is `@RequiresOptIn` (the opt-in / experimental API mechanism), how does it propagate, and how does it differ from `@Deprecated`?

`@RequiresOptIn` is the mechanism behind Kotlin's experimental/unstable API markers (like `@ExperimentalCoroutinesApi`, `@ExperimentalContracts`). You define a marker annotation meta-annotated with `@RequiresOptIn`, apply it to an unstable API, and consumers must **explicitly acknowledge** the instability either with `@OptIn(Marker::class)` at the use site or by propagating the marker onto their own declaration.

```kotlin
@RequiresOptIn(message = "This API is experimental and may change", level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalApi

@ExperimentalApi
fun newThing() { }

@OptIn(ExperimentalApi::class)        // acknowledge at use site
fun caller() = newThing()

@ExperimentalApi                      // OR propagate the requirement to callers
fun stillExperimental() = newThing()
```

Propagation is the key feature: opting in is a deliberate, traceable choice, and the requirement either stops at a use site (`@OptIn`) or virally propagates (re-annotating) so the instability is visible up the call chain. It differs from `@Deprecated` in intent and direction: `@Deprecated` marks APIs going *away* (with `WARNING`/`ERROR`/`HIDDEN` levels and a `ReplaceWith` migration), signaling "stop using this," whereas `@RequiresOptIn` marks APIs *arriving* but not yet stable, signaling "use this only if you accept it may change." Both are compile-time contracts that let library authors evolve a surface safely; opt-in protects new APIs, deprecation retires old ones.

#### Q92. [Coding] Implement a cold `Flow` that wraps a callback-based API using `callbackFlow`, with correct resource cleanup and backpressure.

```kotlin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

interface LocationApi {
    fun register(listener: (Location) -> Unit): Subscription
}
class Subscription { fun cancel() {} }

fun LocationApi.locations(): Flow<Location> = callbackFlow {
    val subscription = register { loc ->
        trySend(loc)            // non-suspending offer into the channel; respects buffer
            .onFailure { /* buffer full — drop or log per policy */ }
    }
    awaitClose {                // runs when the flow collector cancels or completes
        subscription.cancel()   // CRITICAL: unregister to avoid leaking the callback
    }
}.buffer(capacity = 64)         // tune backpressure for a fast callback source
```

Why `callbackFlow` and not `flow { }`: a `flow { }` builder is for sequential `suspend`-based producers and forbids emitting from another thread/callback (it would violate context preservation, Q81). `callbackFlow` (and its sibling `channelFlow`) is built on a `Channel`, so it **permits emissions from arbitrary threads/callbacks** via the thread-safe `trySend`/`send`. The two correctness essentials: `awaitClose { }` is **mandatory** — it suspends until the flow is cancelled and is where you unregister the callback (omitting it leaks the subscription and throws `IllegalStateException` if the builder returns early). `trySend` is non-suspending and returns a result you must handle (the buffer may be full); pair it with a `buffer`/conflation policy to define backpressure behavior for a producer you cannot slow down. This pattern is the canonical bridge from imperative listener APIs (sensors, websockets, Android callbacks) into the `Flow` world.

#### Q93. [Theory] How do `inline` functions interact with reified type parameters at the bytecode level, and why can't you reify in a non-inline function or store a reified type?

A `reified` type parameter only works inside an `inline` function because reification is implemented by **substituting the concrete type at each call site during inlining**. There is no runtime type token passed around — instead, the compiler copies the function body into the caller and replaces `T` with the actual type argument as a literal class reference in the generated bytecode.

```kotlin
inline fun <reified T> typeName(): String = T::class.java.name

val n = typeName<String>()
// At the call site, the compiler emits effectively: "java.lang.String"
// — T::class.java becomes a literal String.class load, NOT a generic lookup
```

This explains the constraints:

- **Non-inline functions cannot reify** — without inlining there is no call site to substitute into; the type would be erased to `Object` at runtime as usual.
- **You cannot store or pass a reified `T` to a non-inline function** as a type parameter — the concreteness exists only at the inlined call site; once you cross into a non-inlined boundary, erasure resumes. (You can pass `T::class`/`T::class.java` as an ordinary value, though.)
- **Recursion with a reified parameter is restricted** — a function cannot inline-recurse with a varying reified type because each level would need a fresh substitution.

Reification is therefore a compile-time trick that trades code duplication (the body is inlined per call) for runtime type availability, letting `filterIsInstance<T>()`, `fromJson<T>()`, and `T::class` work despite JVM erasure. It is the idiomatic escape hatch for the no-`Class<T>`-token APIs Java forces.

#### Q94. [Theory] What changes does the K2 compiler introduce for plugin authors and the IR backend, and how does the FIR-to-IR pipeline differ from the old PSI-based flow?

The old Kotlin frontend resolved code directly over the **PSI** (the IDE's syntax tree), interleaving name resolution, type inference, and diagnostics in a way that was hard to extend and inconsistent across platforms. K2 introduces a clean multi-phase pipeline: source → **PSI/light tree** → **FIR** (Frontend IR, a semantic tree with fully-resolved types and references) → **IR** (backend Intermediate Representation, shared across JVM/JS/Native/Wasm) → platform bytecode.

For plugin and tooling authors this matters concretely:

- **FIR is the new extension surface** — compiler plugins (serialization, Compose, Parcelize, all-open, no-arg) migrate from the legacy frontend APIs to FIR-based extension points (`FirExtension`), which are more principled but require rewrites.
- **A single shared IR backend** means a feature or plugin behaves consistently across all targets, eliminating the per-platform divergence of the old backends.
- **Phased, resolved FIR** gives plugins fully type-resolved information at well-defined phases, rather than the racy on-demand resolution of PSI, making analysis more robust.
- **IDE and CLI share the same FIR analysis**, so a behavior seen in the IDE matches the command-line compiler — fewer "compiles in IDE but not Gradle" mismatches.

The practical migration impact (current to 2026, K2 stable since 2.0): most application code compiles unchanged, but compiler-plugin authors and metaprogramming-heavy projects had to update to the FIR/IR APIs, and some unsound smart casts the old frontend permitted now correctly error. The architecture is the foundation for faster compilation and consistent cross-platform semantics going forward.

#### Q95. [Behavioral] You inherit a Kotlin codebase riddled with `!!`, `GlobalScope`, and `runBlocking` inside request handlers. How do you prioritize and drive remediation?

I would treat this as a risk-reduction program, not a stylistic crusade, and sequence by blast radius. First I triage: `runBlocking` inside request handlers and `GlobalScope` are *correctness and stability* hazards (thread-pool starvation, leaked coroutines that outlive requests, swallowed cancellation), whereas `!!` is mostly a *latent NPE* risk. The two former classes get priority because they cause outages, not just exceptions.

Concretely: I would add lint/Detekt rules to *stop the bleeding* — fail CI on new `GlobalScope`, `runBlocking` in non-boundary code, and broad `catch (e: Exception)` that does not rethrow `CancellationException` — so the problem cannot grow while we fix the backlog. Then I would burn down the existing offenders by hot-path first: handlers and high-traffic paths where pool starvation or leaks actually hurt, replacing `GlobalScope` with a lifecycle-bound scope (request/`viewModelScope`-equivalent), and `runBlocking` with proper `suspend` propagation or `coroutineScope`. For `!!`, I would let the lint surface them and fix opportunistically when touching a file, prioritizing those in null-prone boundaries (deserialization, Java interop), rather than a risky mass rewrite.

I would protect every change with the existing test suite plus targeted `runTest`-based tests for the concurrency fixes, and I would measure: error rates, thread-pool saturation metrics, and latency before/after to prove the structured-concurrency fixes actually moved the needle. The behavioral signal is prioritizing by production impact, preventing regression with automation before manual cleanup, and validating with data — plus framing it to stakeholders as reliability work with measurable payoff, not a purity exercise, so it earns the time it needs.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q96. [Practical] You wrote `val list = listOf(1, 2, 3)` and now you need to add an element, but `list.add(4)` doesn't compile. What's happening and how do you fix it?

`listOf` returns a read-only `List<Int>` — the interface exposes no `add`. This is the most common beginner stumble coming from Java's `ArrayList`. You have three clean options depending on intent:

```kotlin
val ro = listOf(1, 2, 3)

// 1) You actually want immutability + a new list: use the plus operator
val grown = ro + 4                      // [1, 2, 3, 4] — new list, ro unchanged

// 2) You genuinely need an in-place mutable collection
val mut = mutableListOf(1, 2, 3)
mut.add(4)                              // OK

// 3) Build immutably then freeze the result
val built = buildList { addAll(ro); add(4) }   // read-only result
```

The idiomatic default is option 1 or 3: prefer immutable data and produce a new value. Reach for `mutableListOf` only when you truly need accumulation in place (e.g., inside a tight loop). A frequent follow-up bug: declaring `var ro = listOf(...)` and reassigning with `ro = ro + x` in a loop — that allocates a new list each iteration; use a `MutableList` or `buildList` for that case.

#### Q97. [Practical] Your `for ((key, value) in map)` loop compiles, but a teammate's `for ((a, b) in someList)` does not. Why does destructuring work for one and not the other?

Destructuring relies on `component1()`, `component2()`, … operator functions existing on the element type. `Map.Entry` provides `component1()` (key) and `component2()` (value), so iterating a map yields destructurable entries. A plain `List<SomeType>` destructures its *elements*, so it only works if each element itself has `componentN()` — e.g., a `Pair`, a `data class`, or a `List`/`Array` index-destructuring is **not** automatic.

```kotlin
val map = mapOf("a" to 1)
for ((k, v) in map) { }                 // OK: Map.Entry has component1/2

val pairs = listOf("a" to 1, "b" to 2)
for ((k, v) in pairs) { }               // OK: Pair has component1/2

val rows = listOf(listOf(1, 2), listOf(3, 4))
// for ((x, y) in rows) { }             // does NOT compile: List has no component1
for (row in rows) { val (x, y) = row }  // works: now destructuring an indexable... no
```

Correction on the last case: `List` does **not** define `componentN`, so `val (x, y) = row` fails too. Only `Pair`, `Triple`, data classes, arrays, and `Map.Entry` carry the components. If your list holds raw lists, index them (`row[0]`, `row[1]`) or map to a data class first.

#### Q98. [Coding] Given a list of words, write idiomatic Kotlin to return a map from each word's length to the list of words of that length, sorted by length.

```kotlin
fun groupByLength(words: List<String>): Map<Int, List<String>> =
    words.groupBy { it.length }          // Map<Int, List<String>>
         .toSortedMap()                  // sort keys ascending

fun main() {
    val out = groupByLength(listOf("a", "bb", "cc", "ddd"))
    println(out)   // {1=[a], 2=[bb, cc], 3=[ddd]}
}
```

`groupBy { keySelector }` is the single-call idiom — it builds the multimap directly, far cleaner than manually `getOrPut(key) { mutableListOf() }.add(...)`. `toSortedMap()` returns a `TreeMap`-backed view ordered by key. If you needed the *count* per length rather than the words, `groupingBy { it.length }.eachCount()` is the allocation-light path (it avoids materializing the intermediate lists). The distinction between `groupBy` (eager, builds lists) and `groupingBy` (lazy, folds) is a common interview probe.

#### Q99. [Practical] A `NullPointerException` is thrown from a line that has no `!!` and no obvious nullable. Where do you look first?

The usual culprit is a **platform type** crossing the Java boundary (Q8): a Java method returned `null`, Kotlin treated it as non-null because the Java side lacked `@Nullable`, and the NPE fired on first use — possibly several lines later when the value is dereferenced. Diagnostic checklist:

1. Identify any Java call on or above the failing line; its return is a platform type unless annotated.
2. Look for `lateinit var` accessed before initialization — that throws `UninitializedPropertyAccessException`, a close cousin.
3. Check uninitialized members referenced from an `open` function called in a constructor (the subclass field isn't set yet).
4. Look for `as` casts and generic erasure — a bad cast surfaces as `ClassCastException`, not NPE, but reflection/`Gson`-style deserialization can inject `null` into a non-null field, deferring the NPE.

The fix at the boundary is to type the Java result explicitly as nullable (`val x: Foo? = javaCall()`) and handle it, or add JSR-305/JetBrains annotations to the Java side so the compiler enforces it. For the deserialization case, configure the framework to respect Kotlin nullability (e.g., the Jackson Kotlin module).

#### Q100. [Practical] How do you safely convert a `String` to an `Int` when the input might be malformed, and what are the three idioms ranked by preference?

```kotlin
val s = "42x"

// 1) Best when "invalid" is expected/normal: returns null, no exception, no cost
val a: Int? = s.toIntOrNull()
val withDefault = s.toIntOrNull() ?: 0

// 2) When you want to branch and carry the error
val result: Int = s.toIntOrNull() ?: run { log("bad input: $s"); -1 }

// 3) When malformed input is truly exceptional (e.g., trusted internal data)
val c: Int = try { s.toInt() } catch (e: NumberFormatException) { 0 }
```

Prefer `toIntOrNull()` for any user-facing or external input: it expresses "this may legitimately fail" in the type, costs nothing on the failure path (no exception allocation/stack capture), and composes with `?:`. Reserve `toInt()` + `catch` for cases where a malformed value indicates a bug worth a stack trace. Throwing exceptions for routine validation is both slower and noisier in logs.

#### Q101. [Coding] Write a function that returns the second-largest distinct value in a list of integers, or null if there isn't one.

```kotlin
fun secondLargest(nums: List<Int>): Int? =
    nums.distinct()                      // drop duplicates
        .sortedDescending()              // largest first
        .getOrNull(1)                    // null-safe index access

fun main() {
    println(secondLargest(listOf(5, 5, 3, 9, 9)))  // 5
    println(secondLargest(listOf(7)))              // null
    println(secondLargest(emptyList()))            // null
}
```

`getOrNull(1)` is the key idiom — it replaces a manual `if (list.size >= 2) list[1] else null` and never throws `IndexOutOfBoundsException`. For large lists where a full sort is wasteful, a single pass tracking the top two distinct values is O(n) versus O(n log n); but the readable version above is correct and usually fast enough. Note `distinct()` preserves first-occurrence order and is needed so `[9, 9]` doesn't report 9 twice.

#### Q102. [Practical] Your `when` expression compiles fine today, but after a colleague adds a new subtype to a sealed interface it still compiles — yet behaves wrong at runtime. What went wrong and how do you prevent it?

Almost certainly the original `when` had an `else` branch. With `else`, the compiler can no longer flag the missing case, so the new subtype silently falls into `else`. The whole point of sealed hierarchies (Q20) is exhaustiveness checking, and a catch-all `else` opts out of it.

```kotlin
sealed interface Event
data object Click : Event
data object Scroll : Event
// later someone adds: data object Hover : Event

fun handle(e: Event) = when (e) {
    is Click -> "click"
    is Scroll -> "scroll"
    else -> "unknown"      // ❌ Hover silently lands here forever
}
```

Remove the `else` and enumerate each case. When `Hover` is added, *every* exhaustive `when` over `Event` becomes a compile error, giving you a compiler-generated TODO list of places to update. Reserve `else` for genuinely open types (`Any`, `Int`) or when a default truly is correct. When you do need a default but still want exhaustiveness pressure, some teams use a small helper or rely on lint rules that warn on `else` over sealed types.

#### Q103. [Practical] You see `Comparison of incompatible enums` or your `if (status == "ACTIVE")` never matches a value you're sure is "ACTIVE". What are the likely string-comparison traps?

For strings, `==` is structural (calls `equals`) so it compares content, not reference — that part is safe in Kotlin (unlike Java's `==`). The usual real-world traps are:

1. **Whitespace/case** — the value is `"ACTIVE "` or `"active"`. Use `s.trim().equals("ACTIVE", ignoreCase = true)` or normalize on input.
2. **Comparing an enum to a string** — `status == "ACTIVE"` where `status` is an enum always fails; use `status == Status.ACTIVE` or `status.name == "ACTIVE"`.
3. **Non-printing characters** — BOM, non-breaking spaces from copy-paste or external feeds. Log `s.map { it.code }` to see the actual code points.
4. **Locale-sensitive uppercasing** — `uppercase()` without a locale can mangle e.g. Turkish `i`. Use `uppercase(Locale.ROOT)` for protocol/identifier comparisons.

```kotlin
val normalized = raw.trim()
if (normalized.equals("ACTIVE", ignoreCase = true)) { /* ... */ }
```

The discipline is to normalize at the boundary (trim + canonical case with `Locale.ROOT`) so downstream comparisons are trivial.

#### Q104. [Coding] Write idiomatic Kotlin to read a list of "key=value" lines and produce a `Map<String, String>`, skipping blank lines and lines without `=`.

```kotlin
fun parseConfig(lines: List<String>): Map<String, String> =
    lines.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && '=' in it }
        .map { line ->
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .toMap()

fun main() {
    val cfg = parseConfig(listOf("a = 1", "", "broken", "b=2 "))
    println(cfg)   // {a=1, b=2}
}
```

Splitting on the *first* `=` via `indexOf` (rather than `split("=")`) correctly handles values that themselves contain `=` (like base64 padding or URLs). `asSequence()` keeps it single-pass for large files. `to` builds the `Pair`, and `toMap()` collects. If duplicate keys appear, `toMap()` keeps the last — call out that behavior, since silently overwriting earlier keys is sometimes a bug.

### 🟡 — extended

#### Q105. [Practical] A coroutine in your service "hangs forever" and never times out. You're using `withTimeout`. What are the likely causes?

`withTimeout` enforces its deadline by throwing `TimeoutCancellationException` at the **next suspension point or cancellation check**. If the coroutine is doing non-cooperative work, the timeout can't fire:

1. **Blocking call instead of suspending** — `Thread.sleep`, a synchronous JDBC/HTTP call, or `socket.read()` doesn't suspend, so cancellation is never observed. Wrap such calls in `withContext(Dispatchers.IO)` *and* ensure the underlying API supports interruption, or use `runInterruptible { blockingCall() }` so cancellation maps to thread interrupt.
2. **Tight CPU loop with no suspension** — insert `ensureActive()`/`yield()` (Q29).
3. **Swallowed cancellation** — a broad `catch (e: Exception)` caught the `TimeoutCancellationException` (which is a `CancellationException`) and continued. Rethrow `CancellationException`.
4. **`NonCancellable` misuse** — code wrapped in `withContext(NonCancellable)` for cleanup is intentionally uncancellable; if real work is in there, it ignores the timeout.

```kotlin
val r = withTimeoutOrNull(2_000) {
    runInterruptible(Dispatchers.IO) { legacyBlockingCall() }  // interruptible
}
if (r == null) log("timed out")
```

`withTimeoutOrNull` returns `null` instead of throwing, which is often cleaner for fallbacks.

#### Q106. [Coding] Implement a `suspend` function that fetches from a primary source but falls back to a cache if the primary takes longer than 500ms, without leaking the slower call.

```kotlin
suspend fun fetchWithFallback(
    primary: suspend () -> Data,
    cache: suspend () -> Data
): Data = coroutineScope {
    val primaryJob = async { primary() }
    val result = withTimeoutOrNull(500) { primaryJob.await() }
    if (result != null) {
        result
    } else {
        primaryJob.cancel()              // stop the slow primary, no leak
        cache()
    }
}
```

The `coroutineScope` ties both calls to a single lifetime, so if anything throws or the caller is cancelled, `primaryJob` is cancelled too — no orphaned coroutine. `withTimeoutOrNull` gives a clean `null` on timeout instead of an exception. Explicitly cancelling `primaryJob` on the fallback path stops the in-flight primary work rather than letting it run to completion in the background. A subtle correctness point: because we're inside `coroutineScope`, the function won't return until child coroutines settle, so cancellation propagates deterministically.

#### Q107. [Practical] Your `Flow` collector receives values out of order or drops some. What operator misuse typically causes this, and how do you fix it?

Out-of-order or dropped values almost always come from concurrency-introducing operators:

- **`flatMapMerge`** runs inner flows concurrently and interleaves their emissions — order is not preserved. Use `flatMapConcat` when order matters (it processes inner flows sequentially) at the cost of parallelism.
- **`buffer` with a small or `DROP_OLDEST`/`DROP_LATEST` overflow policy** intentionally drops. Check the `onBufferOverflow` setting.
- **`conflate`** drops intermediate values by design (Q44) — fine for UI state, wrong for an event log.
- **`collectLatest`/`mapLatest`** cancels the previous block when a new value arrives, so slow processing silently loses earlier items.
- **`SharedFlow` with `replay = 0` and no buffer** drops emissions that occur while no collector is active, or when collectors are slower than `tryEmit` with `DROP` overflow.

```kotlin
// ordered, sequential:
upstream.flatMapConcat { id -> fetch(id) }.collect { ... }

// every event must survive a slow collector:
events.buffer(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND).collect { ... }
```

The diagnostic question is always: do I need *every* value (use suspending backpressure / `flatMapConcat`) or just the *latest* (conflate/Latest is fine)?

#### Q108. [Coding] Write a coroutine that retries a flaky suspending call up to 3 times with a fixed delay, but immediately gives up on a non-retryable exception.

```kotlin
suspend fun <T> retryRetryable(
    attempts: Int = 3,
    delayMs: Long = 200,
    isRetryable: (Throwable) -> Boolean,
    block: suspend () -> T
): T {
    repeat(attempts - 1) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e                       // never swallow cancellation
        } catch (e: Throwable) {
            if (!isRetryable(e)) throw e  // give up immediately on non-retryable
            delay(delayMs)
        }
    }
    return block()                        // last attempt: let exception propagate
}

// usage
val data = retryRetryable(isRetryable = { it is IOException }) { api.fetch() }
```

Three things make this production-correct: (1) `CancellationException` is rethrown *before* the generic catch so structured-concurrency cancellation isn't broken; (2) the predicate distinguishes transient failures (network) from permanent ones (4xx, parse errors) so you don't waste retries; (3) the final attempt is outside the loop so its exception propagates rather than being swallowed by a retry that never happens. `delay` is cancellable, so a cancelled scope aborts mid-backoff cleanly.

#### Q109. [Practical] You have `data class Money(val cents: Long, val currency: String)` and equality is misbehaving in a `HashSet`. Walk through the debugging.

Data-class `equals`/`hashCode` cover all primary-constructor properties, so two `Money` values are equal iff both `cents` and `currency` match. Misbehavior usually traces to one of:

1. **Mutation after insertion** — if a property were `var` and you mutated it post-insert, the object's bucket no longer matches its hash (Q39). Fix: keep them `val` (they already are here, good).
2. **A property *not* in the primary constructor** — if `currency` were a body property (`class Money(val cents: Long) { var currency = ... }`), it would be excluded from equality, so `Money(100)` "USD" would equal `Money(100)` "EUR". Move it into the primary constructor.
3. **Normalization** — `"usd"` vs `"USD"`, or unnormalized currency strings, produce distinct keys. Normalize in an `init` or, better, use an enum/value class for currency.
4. **Floating point** — if amounts were `Double`, `0.1 + 0.2 != 0.3` and `NaN != NaN`. Storing integer `cents` (as here) is exactly the right fix.

```kotlin
@JvmInline value class Currency(val code: String)   // type-safe, normalize on construction
data class Money(val cents: Long, val currency: Currency)
```

The structured lesson: equality bugs are usually "wrong fields in the constructor," "mutable key," or "unnormalized value" — check those three first.

#### Q110. [Practical] A `lazy` property occasionally returns a half-initialized object under load. What's likely wrong and how do you fix it?

`by lazy { }` defaults to `LazyThreadSafetyMode.SYNCHRONIZED`, which is safe — the initializer runs once under a lock. If you're seeing partial initialization, the most likely cause is that someone changed the mode to `NONE` or `PUBLICATION` for "performance":

- **`NONE`** does no synchronization at all; under concurrent first-access, multiple threads can run the initializer and observe partially constructed state. Only valid when access is provably single-threaded.
- **`PUBLICATION`** allows concurrent initializer runs but publishes the first result; if the initializer has side effects (registering listeners, incrementing counters), those side effects can happen more than once.

```kotlin
// problematic if accessed from multiple threads:
val conn by lazy(LazyThreadSafetyMode.NONE) { openConnection() }

// safe default — leave it:
val conn by lazy { openConnection() }
```

Fix: revert to the default `SYNCHRONIZED` unless you can prove single-threaded access. A second, subtler cause is the initializer *itself* publishing `this` (escaping a partially built object) — e.g., registering the object in a global map before construction completes. That's a constructor-escape bug independent of `lazy`'s mode.

#### Q111. [Coding] Implement a thread-safe memoization wrapper for a pure suspending function using a `Mutex` and a map.

```kotlin
class SuspendMemoizer<K, V>(private val compute: suspend (K) -> V) {
    private val cache = mutableMapOf<K, V>()
    private val mutex = Mutex()

    suspend fun get(key: K): V {
        cache[key]?.let { return it }            // fast path: no lock if already cached
        return mutex.withLock {
            cache[key]?.let { return it }        // double-check inside the lock
            compute(key).also { cache[key] = it }
        }
    }
}

// usage
val memo = SuspendMemoizer<Int, String> { id -> api.fetchName(id) }
```

`Mutex.withLock` is the coroutine-aware analog of `synchronized` — it *suspends* the coroutine instead of blocking the thread (Q71), so it's safe to hold across `compute(key)`'s own suspension points. The double-checked pattern keeps the common cached-hit path lock-free. Caveat: this serializes all *distinct* keys behind one mutex, and two callers for the same uncached key won't share the in-flight computation (the second waits, then finds it cached). For per-key in-flight sharing you'd store `Deferred<V>` values instead of `V` and `await()` them — a deeper variant worth mentioning.

#### Q112. [Practical] In a unit test, your coroutine code "passes" but you suspect it isn't actually exercising the async path. How do you make coroutine tests deterministic?

Use `kotlinx-coroutines-test`'s `runTest`, which installs a `TestCoroutineScheduler` with **virtual time** (Q76). Real `delay`s are skipped instantly but still ordered correctly, so a 30-second backoff test runs in milliseconds while preserving sequencing.

```kotlin
@Test
fun retriesThenSucceeds() = runTest {
    var calls = 0
    val result = retryRetryable(isRetryable = { true }) {
        if (++calls < 3) throw IOException() else "ok"
    }
    assertEquals("ok", result)
    assertEquals(3, calls)
    // virtual time: the two 200ms delays cost ~0ms of wall clock
}
```

Key tools: inject a dispatcher (`TestDispatcher`) rather than hardcoding `Dispatchers.IO`/`Main` so the test controls scheduling; use `advanceUntilIdle()` / `advanceTimeBy()` to drive time explicitly; replace `Dispatchers.Main` with `Dispatchers.setMain(testDispatcher)` in setup. The anti-pattern that hides bugs is sprinkling `runBlocking { delay(realMs) }` and real dispatchers in tests — those are slow, flaky, and don't prove ordering. If a test passes without `runTest`, suspect it's not reaching the suspending code at all.

#### Q113. [Practical] Your app leaks memory; profiling points at coroutines that never complete. What structural mistakes cause coroutine leaks?

Coroutine leaks come from breaking structured concurrency:

1. **`GlobalScope.launch`** — these coroutines have application lifetime; nothing cancels them when the screen/request ends. Replace with a lifecycle-bound scope (`viewModelScope`, a request-scoped `CoroutineScope` cancelled in cleanup).
2. **A custom `CoroutineScope` you create but never `cancel()`** — e.g., `CoroutineScope(Dispatchers.IO)` stored in a singleton; its children outlive everything. Always pair creation with a cancellation in the owner's teardown.
3. **Hot flow collectors without lifecycle awareness** — collecting a `StateFlow` in a `launch` that's never cancelled keeps the collector (and captured references) alive. On Android, use `repeatOnLifecycle`.
4. **`callbackFlow`/`channelFlow` that never calls `awaitClose`/closes the channel** — the underlying callback registration leaks.
5. **Detached `async` whose `Deferred` is never awaited and whose scope outlives the work** — exceptions and the coroutine both linger.

```kotlin
// leak:
GlobalScope.launch { while (true) { poll(); delay(1000) } }

// fixed: scope cancelled with the component
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
fun start() = scope.launch { while (isActive) { poll(); delay(1000) } }
fun close() = scope.cancel()
```

The rule: every coroutine must have an owner whose lifecycle cancels it. If you can't name what cancels a coroutine, it's a leak waiting to happen.

#### Q114. [Coding] Write an extension function `Iterable<T>.sumOfBy(selector)` problem: actually, show how to compute the average word length and the longest word in a single pass over a list.

```kotlin
data class WordStats(val count: Int, val totalLength: Int, val longest: String) {
    val averageLength: Double get() = if (count == 0) 0.0 else totalLength.toDouble() / count
}

fun List<String>.stats(): WordStats =
    fold(WordStats(0, 0, "")) { acc, w ->
        WordStats(
            count = acc.count + 1,
            totalLength = acc.totalLength + w.length,
            longest = if (w.length > acc.longest.length) w else acc.longest
        )
    }

fun main() {
    val s = listOf("a", "bbbb", "cc").stats()
    println("%.2f".format(s.averageLength))  // 2.33
    println(s.longest)                        // bbbb
}
```

`fold` carries an accumulator through one traversal, computing count, total length, and longest simultaneously — versus three separate passes (`size`, `sumOf`, `maxByOrNull`). For three small stats the multi-pass version is more readable and the right call in practice; the single-pass `fold` matters when the source is a `Sequence`/`Flow` you can only iterate once, or when traversal is expensive. This question tests whether the candidate knows *when* the micro-optimization is justified (single-consumption sources) versus premature.

### 🟠 — extended

#### Q115. [Practical] Production logs show `JobCancellationException` being logged as errors, polluting your alerting. What's the correct handling discipline?

`CancellationException` (and its subtype `JobCancellationException`) is **not an error** — it's the normal signal that a coroutine was cancelled (timeout, scope teardown, `collectLatest` restart). Logging it as an error is a classic false-positive source. The discipline:

1. Never catch it in a broad handler and log it. If you catch `Throwable`/`Exception` for cleanup, rethrow `CancellationException` first:

```kotlin
try {
    doWork()
} catch (e: CancellationException) {
    throw e                          // propagate cancellation untouched
} catch (e: Exception) {
    log.error("real failure", e)     // only genuine errors reach here
}
```

2. In a `CoroutineExceptionHandler`, cancellation never arrives anyway (it's filtered), so handlers are safe — the problem is almost always a hand-rolled try/catch.
3. `runCatching` is dangerous here because it catches `Throwable` including cancellation; avoid it in coroutine bodies, or follow it with `.onFailure { if (it is CancellationException) throw it }`.

The structural fix is a single shared "safe execute" helper that encodes the rethrow-cancellation rule so individual call sites can't get it wrong.

#### Q116. [Coding] Implement a rate limiter that allows at most N suspending operations to run concurrently, using a `Semaphore`.

```kotlin
class ConcurrencyLimiter(permits: Int) {
    private val semaphore = Semaphore(permits)

    suspend fun <T> run(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}

// usage: cap downstream calls at 10 concurrent
suspend fun fetchAll(ids: List<Int>, api: Api): List<Data> = coroutineScope {
    val limiter = ConcurrencyLimiter(10)
    ids.map { id ->
        async { limiter.run { api.fetch(id) } }
    }.awaitAll()
}
```

`kotlinx.coroutines.sync.Semaphore.withPermit` suspends (does not block a thread) when no permit is available, so you can launch 10,000 `async` jobs but only 10 hit the downstream at once — protecting it from overload without thread starvation. This is the idiomatic way to bound fan-out concurrency. Contrast with `Dispatchers.IO.limitedParallelism(10)` (Q90), which bounds *threads* not *logical operations*; the semaphore is the right tool when you want to limit concurrency to a specific resource regardless of dispatcher. `awaitAll()` collects results and propagates the first failure, cancelling siblings via structured concurrency.

#### Q117. [Practical] A `StateFlow` collector in your UI isn't receiving an update even though you set `_state.value`. What conflation/equality subtlety bit you?

`StateFlow` conflates by **value equality**: it only emits when the new value is `!=` the current one (it calls `equals`). If you set a value that is `equals` to the previous, no emission occurs — even if it's a different instance.

```kotlin
private val _state = MutableStateFlow(listOf(1, 2, 3))
_state.value = listOf(1, 2, 3)   // no emission: structurally equal to current
```

Two common traps:

1. **Mutating in place then reassigning the same reference** — `_state.value.add(x); _state.value = _state.value` emits nothing because it's the same instance (and equal to itself). You must create a *new, non-equal* value: `_state.update { it + x }`.
2. **A data class where the "changed" field isn't in the primary constructor** — equality ignores it (Q39), so the update looks identical to `StateFlow`. Move the field into the constructor.

The fix is to always produce a new value that differs by `equals`, using `update { }` for atomic read-modify-write (which also avoids lost updates under concurrency). If you genuinely need to emit equal values (e.g., re-trigger an effect), `StateFlow` is the wrong type — use a `SharedFlow` with appropriate replay, which doesn't conflate by equality.

#### Q118. [Coding] Implement a debounce for a search-as-you-type feature using `Flow`, cancelling stale in-flight searches.

```kotlin
fun searchResults(queries: Flow<String>, search: suspend (String) -> List<Result>): Flow<List<Result>> =
    queries
        .debounce(300)                 // wait for typing to pause
        .filter { it.length >= 2 }     // ignore trivially short queries
        .distinctUntilChanged()        // skip if query didn't actually change
        .mapLatest { q -> search(q) }  // cancel previous search when a newer query arrives
```

Two operators do the heavy lifting: `debounce(300)` emits a value only after 300ms of silence, collapsing bursts of keystrokes; `mapLatest` cancels the in-flight `search(q)` when a newer query arrives, so a slow response for "ka" can't overwrite results for "kat". `distinctUntilChanged` avoids redundant work when debounce passes an unchanged value. This is the canonical reactive search pipeline — the interview signal is knowing that `mapLatest` (not `map` or `flatMapMerge`) is what guarantees only the latest query's results survive, preventing the classic "results flicker to a stale query" race.

#### Q119. [Practical] Your service intermittently exhausts its thread pool under load. The code uses `Dispatchers.IO` heavily. How do you diagnose and bound the blast radius?

`Dispatchers.IO` has a large but finite elastic pool (default cap 64 threads, sharing the `Default` pool's threads). Exhaustion typically means too much concurrent *blocking* work parked on those threads. Diagnosis and remediation:

1. **Confirm the symptom** — thread dumps showing many threads in `IO` named pool blocked on socket/JDBC reads; rising latency with stable CPU.
2. **Bound per-resource concurrency** with `limitedParallelism` so one slow dependency can't consume the whole pool:

```kotlin
val dbDispatcher = Dispatchers.IO.limitedParallelism(16)   // dedicated slice for DB
suspend fun query() = withContext(dbDispatcher) { jdbc.query(...) }
```

3. **Don't block on `Default`** — CPU-bound dispatcher has only `cores` threads; one blocking call there starves all CPU work.
4. **Prefer truly async clients** — a non-blocking HTTP/DB driver doesn't hold a thread during I/O at all, so it scales far past 64 concurrent calls.
5. **Add a `Semaphore`** (Q116) to cap logical concurrency independent of the pool.

`limitedParallelism` is the key 2026-era tool: it carves an isolated view of the shared pool so each downstream gets a fixed budget, turning "global starvation" into "this one dependency throttles itself."

#### Q120. [Coding] Write a function that processes a large file's lines in parallel batches, preserving bounded memory, using `Flow`.

```kotlin
fun processFile(path: Path, batchSize: Int, parallelism: Int, handle: suspend (List<String>) -> Unit): Flow<Unit> =
    path.toFile().bufferedReader().lineSequence().asFlow()  // lazy, line-by-line
        .chunked(batchSize)                                  // group into batches (kotlinx 1.9+)
        .flatMapMerge(concurrency = parallelism) { batch ->
            flow { handle(batch); emit(Unit) }
        }
        .flowOn(Dispatchers.IO)

// driver
suspend fun run() {
    processFile(Path.of("big.log"), batchSize = 500, parallelism = 8) { batch ->
        batch.forEach { /* parse + index */ }
    }.collect()
}
```

Memory stays bounded because `lineSequence().asFlow()` never materializes the whole file, `chunked` holds at most one batch per in-flight stream, and `flatMapMerge(concurrency = 8)` caps how many batches process at once (so peak memory ≈ 8 × batchSize lines). `flowOn(Dispatchers.IO)` moves both the blocking read and the handling off the caller's thread. The tradeoff to state aloud: `flatMapMerge` interleaves and does not preserve batch order — fine for indexing, wrong if output order matters (use `flatMapConcat` or carry an index to reorder).

#### Q121. [Practical] After upgrading the Kotlin compiler to K2 (2.x), some reflection/annotation-processing code broke. What categories of breakage should you expect and check?

K2 is a frontend rewrite (Q47/Q94); most breakages cluster around tooling and edge-case semantics, not core language:

1. **Annotation processors (kapt)** — kapt runs against a stub; with K2 the recommended path is **KSP** (Kotlin Symbol Processing) or kapt in K2-compatibility mode. Libraries like Dagger/Room may need version bumps. Verify each processor declares K2 support.
2. **Compiler plugins** — they consume the new FIR/IR pipeline; an outdated plugin (serialization, Compose, all-open) can fail or miscompile. Match plugin versions to the compiler.
3. **Tightened semantics** — K2 closed soundness holes: some code that compiled by accident (certain smart-cast edge cases, ambiguous overloads, unsound generic projections) now errors. These are usually latent bugs surfaced, not regressions.
4. **Reflection on synthetic members** — generated names or nullability metadata can shift slightly; code string-matching on member names is fragile.

The remediation playbook: bump all compiler plugins and KSP/kapt-based libraries first, enable K2 in a branch, read the new diagnostics (they're often more precise), and treat newly-surfaced errors as real defects. Validate with the full test suite before rollout.

#### Q122. [Coding] Implement a `suspend` cache-aside helper that coalesces concurrent requests for the same key into one in-flight computation.

```kotlin
class CoalescingCache<K, V>(private val scope: CoroutineScope, private val load: suspend (K) -> V) {
    private val inFlight = mutableMapOf<K, Deferred<V>>()
    private val mutex = Mutex()

    suspend fun get(key: K): V {
        val deferred = mutex.withLock {
            inFlight.getOrPut(key) {
                scope.async { load(key) }    // one computation per key
            }
        }
        return try {
            deferred.await()                  // all callers for this key share it
        } finally {
            mutex.withLock { inFlight.remove(key, deferred) }
        }
    }
}
```

Unlike Q111's memoizer, this *shares the in-flight `Deferred`* so 100 concurrent callers for the same cold key trigger exactly one `load`, and all await the same result — a thundering-herd guard. The `Mutex` only guards the tiny map mutation, not the load itself, so distinct keys proceed concurrently. Cleanup removes the entry once settled so failures aren't cached forever (here it's cache-aside, not a persistent cache; add a result store for that). The subtlety to flag: `load` runs in `scope`, so if `scope` is cancelled mid-load every awaiter sees `CancellationException` — desired for request-scoped work, but be deliberate about which scope owns the computation.

#### Q123. [Behavioral] A senior engineer insists on using `runBlocking` inside a Spring MVC controller "because it's simpler." How do you handle the disagreement?

I'd separate the legitimate kernel from the risk. `runBlocking` *does* bridge suspend code to a blocking world, and in a thread-per-request MVC stack the request thread is going to block anyway — so on the surface his point isn't crazy. The danger is specific and I'd make it concrete rather than dogmatic: `runBlocking` blocks the *calling* thread until completion, and if the suspend code inside launches coroutines on a shared dispatcher, you can deadlock or starve under load; it also re-introduces the very thread-blocking coroutines exist to avoid, capping throughput at pool size.

My approach: first, agree on what we're optimizing — request throughput and tail latency under load, not lines of code. Then I'd propose the cleaner alternatives that are barely more complex: Spring's native support for `suspend` controller methods (the framework drives the coroutine), or returning a reactive type. If we must bridge, I'd push for it only at the true boundary, on a dedicated dispatcher, never in shared business logic. I'd back it with a small load test comparing `runBlocking` controllers vs suspend controllers at realistic concurrency — data resolves this faster than debate. If he's still unconvinced and it's low-traffic, I'd let it go and document the constraint; picking this hill on a rarely-hit endpoint isn't worth the relationship cost. The behavioral signal is steel-manning his view, converting opinion to a measurable claim, and scaling my insistence to the actual blast radius.

### 🔴 — extended

#### Q124. [Practical] Under heavy GC pressure, profiling shows massive allocation from a hot Kotlin loop using lambdas and boxed primitives. How do you systematically cut allocations?

Walk the usual Kotlin allocation sources in a hot path and eliminate each:

1. **Lambda/SAM object allocation** — a non-`inline` higher-order function allocates a function object per call (or captures). Mark small hot utilities `inline` (Q18) so the lambda body is inlined with zero allocation.
2. **Autoboxing of primitives** — generics erase to `Object`, so `List<Int>`, `Sequence<Int>`, and nullable `Int?` box each element. Use specialized arrays (`IntArray`, `LongArray`) and primitive-specialized ops; avoid `Int?` in inner loops.
3. **Intermediate collections** — eager `map`/`filter` chains build a list per step (Q24). Convert to a `Sequence`, or fuse into one pass; better, for numeric work use `IntArray` with index loops.
4. **`Iterator` allocation** — `for (x in list)` allocates an iterator; in the very hottest loops an index loop over an array avoids it.
5. **Varargs / spread** — `f(*array)` copies the array.
6. **Value classes boxing** — an `@JvmInline value class` boxes when used as a generic arg, nullable, or where a supertype is expected (Q53); keep it unboxed by using the concrete type.

```kotlin
// before: boxes, allocates lambdas + intermediate list
val sum = list.map { it * 2 }.filter { it > 0 }.sum()
// after: primitive array, single pass, no boxing, inlined lambda
var s = 0L
for (i in arr.indices) { val v = arr[i] * 2; if (v > 0) s += v }
```

The method is measure-first (allocation profiler / async-profiler `alloc`), fix the top contributor, remeasure — not blanket micro-optimization. Most code never needs this; reserve it for proven hot paths.

#### Q125. [Coding] Implement a backpressure-aware bridge from a coroutine `Flow` to a Reactive Streams `Publisher`, and explain the demand handshake.

```kotlin
// kotlinx-coroutines-reactive provides the canonical bridge:
fun <T : Any> Flow<T>.toPublisher(): Publisher<T> = this.asPublisher()

// and the reverse, honoring Reactive Streams demand:
fun <T : Any> Publisher<T>.toFlow(): Flow<T> = this.asFlow()

// conceptually, what asPublisher does on subscribe:
fun <T : Any> Flow<T>.asPublisherManual(): Publisher<T> = Publisher { subscriber ->
    val job = SupervisorJob()
    subscriber.onSubscribe(object : Subscription {
        private val requested = atomic(0L)
        override fun request(n: Long) {
            // increment demand; the collecting coroutine emits only up to demand,
            // suspending (backpressure) when demand is exhausted
            requested.addAndGet(n)
        }
        override fun cancel() = job.cancel()
    })
}
```

The demand handshake is the core: Reactive Streams is *pull-push* — the subscriber calls `request(n)` to grant demand, and the publisher may emit at most `n` items before waiting for more. `asPublisher` maps this onto coroutine backpressure: the flow collector suspends when granted demand is exhausted and resumes when `request` adds more, so a fast `Flow` producer can't overrun a slow subscriber. Conversely `asFlow` turns `request`/`onNext` into suspension-based pull. The key correctness points: honor cancellation (subscriber `cancel` → cancel the collecting job), never emit beyond outstanding demand, and serialize `onNext`/`onComplete`/`onError` per the spec. In practice always use the library's `asPublisher`/`asFlow` — hand-rolling the spec's 30+ rules is error-prone; the manual sketch is to demonstrate understanding, not for production.

#### Q126. [Coding] Diagnose and fix this deadlock: a `runBlocking` body calls `withContext(singleThreadDispatcher)` which itself dispatches back to the same single thread.

```kotlin
// BUGGY: deadlocks
val single = newSingleThreadContext("worker")
fun buggy() = runBlocking(single) {            // runBlocking occupies the one thread...
    withContext(single) { computeA() }         // ...needs the same thread to run -> deadlock
    val d = async(single) { computeB() }        // queued behind the blocked thread
    d.await()                                   // never completes
}

// FIXED: don't block the same single thread you need to dispatch onto
fun fixed(): Pair<Int, Int> {
    val single = newSingleThreadContext("worker")
    return single.use {                          // close the dispatcher when done
        runBlocking {                            // block the CALLER thread, not 'single'
            val a = withContext(single) { computeA() }   // 'single' is free to run this
            val b = async(single) { computeB() }
            a to b.await()
        }
    }
}
```

The deadlock: `runBlocking(single)` runs its event loop *on* the single worker thread and blocks it waiting for children; but those children (`withContext(single)`, `async(single)`) need that same thread to execute, and it's blocked — circular wait. The fix is to never use `runBlocking` on the very dispatcher its body must dispatch onto. Run `runBlocking` on a *different* thread (the caller's), letting `single` stay free to service the dispatched work. General rules: confine a single-thread dispatcher's blocking to outside that thread; prefer not to mix `runBlocking` with a confined dispatcher at all; and `use {}` the dispatcher (it owns a real thread that must be closed) to avoid a thread leak — itself a common second bug in this pattern.

#### Q127. [Theory] An ABI/binary-compatibility break is reported after you added a parameter with a default value to a public library function. Why did it break, and how do you evolve the API safely?

Default values don't change a function's *Kotlin* source compatibility, but they do affect *binary* (ABI) compatibility in ways that bite library authors. When you call a function with defaults, the compiler emits a call to a synthetic `$default` bridge that takes a bitmask of which args were supplied. Adding or reordering default parameters changes the generated `$default` signature and the bitmask layout, so code compiled against the old version can throw `NoSuchMethodError` against the new artifact — a binary break without a source break.

Safe evolution rules for a public Kotlin library:

1. **Don't reorder or insert default parameters in the middle** — append new optional parameters at the end, and never repurpose an existing one.
2. **Consider overloads instead of defaults** at the very public boundary, or annotate with `@JvmOverloads` so the generated overload surface is explicit and stable for Java callers.
3. **Use a binary-compatibility validator** (the `binary-compatibility-validator` Gradle plugin) that snapshots the public ABI into a `.api` dump and fails CI on unintended changes — this catches exactly this class of break before release.
4. **Mark unstable surface** with `@RequiresOptIn` (Q91) so you retain freedom to change it.
5. For removals/renames, deprecate with `@Deprecated(... ReplaceWith ...)` across a release, then `HIDDEN`, then remove — never break in one step.

The deeper point: source compatibility, binary compatibility, and behavioral compatibility are three distinct contracts, and library authors must reason about the ABI (the compiled signatures and bridges) explicitly, because the language's call-site conveniences (defaults, inline, value classes) all lower to bytecode shapes that can shift underneath consumers.

## ✅ Key Takeaways

- Prefer `val` and non-nullable types by default; reach for `var`, `?`, and especially `!!` only with justification — the type system is your first line of defense against NPEs.
- Sealed hierarchies + exhaustive `when` model closed sets of states and turn "forgot a case" into a compile error; data classes give you equality/`copy`/destructuring for free.
- Coroutines provide cheap concurrency via suspension (not threads); **structured concurrency** (`coroutineScope`, scopes, cancellation) is what keeps that concurrency leak-free.
- `Flow` is the async stream: cold by default, with `StateFlow`/`SharedFlow` as hot variants for shared state and events.
- `inline` + `reified` defeat type erasure and remove lambda overhead; extension functions, scope functions, and delegation (`by`) cut boilerplate idiomatically.
- Kotlin and Java interop is bidirectional and near-seamless, but platform types and the JVM annotations (`@JvmStatic`, `@JvmOverloads`, `@JvmField`, `@Throws`) are the friction points to manage.

## ⚠️ Common Pitfalls

- Overusing `!!` — it reintroduces exactly the NPEs the type system prevents.
- Swallowing `CancellationException` with a broad `catch (e: Exception)` (or `runCatching`) inside coroutines, silently breaking structured cancellation.
- Running blocking I/O on `Dispatchers.Default`/`Main`, starving the pool; use `Dispatchers.IO` or `withContext`.
- Treating read-only collection interfaces (`List`) as deeply immutable — the backing object can still be mutated elsewhere.
- Mutable (`var`) properties as hash-map keys or in `equals`/`hashCode` of data classes, corrupting hash-based collections.
- Forgetting that extension functions are statically dispatched (not polymorphic) and resolved on the declared type.
- Using `withContext` to switch threads *inside* `flow { }` (violates context preservation) — use `flowOn` instead.
- Leaking coroutines via `GlobalScope` instead of a lifecycle-bound scope.

## 📚 Further Reading

- *Kotlin in Action, 2nd Edition* — Isakova, Elizarov, Aigner, Jemerov (covers coroutines and modern Kotlin).
- Official Kotlin language documentation and coroutines guide at kotlinlang.org.
- *Kotlin Coroutines: Deep Dive* — Marcin Moskała.
- KEEP (Kotlin Evolution and Enhancement Process) repository for language-design rationale.
- *Effective Kotlin* — Marcin Moskała, for idioms and best practices.
- JetBrains talks on the K2 compiler and Kotlin Multiplatform for current-state internals.
