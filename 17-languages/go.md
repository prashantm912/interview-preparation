# Go (Language Deep-Dive)

[← Back to master index](../README.md)

Go is a statically typed, compiled language built around simple concurrency primitives (goroutines and channels), a fast non-generational concurrent garbage collector, and a deliberately small feature set. This deep-dive covers the runtime (GMP scheduler, memory model, GC, escape analysis), the concurrency toolkit (channels, `sync`, `context`), and the language surface (interfaces, slices, maps, generics, error handling) at a depth suitable for interviews from junior through staff level. All content is current to Go 1.24+ (2026).

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a goroutine, and how does it differ from an OS thread?

A goroutine is a lightweight, runtime-managed unit of concurrent execution. You start one with the `go` keyword. The key differences from an OS thread:

- **Cost**: A goroutine starts with a small stack (~2 KB) that grows and shrinks on demand. OS threads typically reserve 1–8 MB of stack. You can run millions of goroutines; you cannot run millions of OS threads.
- **Scheduling**: Goroutines are multiplexed onto a small pool of OS threads by the Go runtime scheduler (the GMP model), not by the kernel. Context switches between goroutines are cheap user-space operations.
- **Blocking**: When a goroutine blocks on a channel, mutex, or network I/O, the runtime can park it and run another goroutine on the same OS thread, so blocking one goroutine does not consume a kernel thread.

```go
go doWork()      // launches doWork concurrently; the caller continues immediately
```

A goroutine is not a promise or a future — `go f()` discards `f`'s return value. To get results back, use channels.

### Q2. [Theory] What is the difference between a buffered and an unbuffered channel?

A channel is a typed conduit for sending values between goroutines.

- **Unbuffered** (`make(chan int)`): capacity 0. A send blocks until another goroutine is ready to receive, and vice versa. The send and receive *rendezvous* — they happen at the same logical moment, giving you a synchronization guarantee.
- **Buffered** (`make(chan int, 3)`): capacity N. A send blocks only when the buffer is full; a receive blocks only when the buffer is empty. It decouples sender and receiver up to N items.

```go
unbuf := make(chan int)    // send blocks until a receiver appears
buf   := make(chan int, 2) // two sends can proceed before any receive
```

```
Unbuffered:  sender ──(must rendezvous)──► receiver
Buffered(2): sender ─► [ _ ][ _ ] ─► receiver   (sender free until full)
```

Use unbuffered channels when you need a handshake; use buffered channels to smooth bursts or to allow a known number of sends to proceed without a receiver.

### Q3. [Practical] How do you wait for a group of goroutines to finish?

Use `sync.WaitGroup`. Call `Add` before launching, `Done` when each finishes (via `defer`), and `Wait` to block until the counter reaches zero.

```go
var wg sync.WaitGroup
for _, url := range urls {
    wg.Add(1)
    go func(u string) {
        defer wg.Done()
        fetch(u)
    }(u)
}
wg.Wait() // blocks until all goroutines call Done
```

Two classic mistakes: calling `wg.Add` *inside* the goroutine (it races with `Wait`), and — in Go versions before 1.22 — capturing the loop variable by reference. In Go 1.22+ each loop iteration has its own variable, so `go func() { fetch(url) }()` is safe, but passing the value as an argument (as above) is always correct.

### Q4. [Theory] Explain the difference between an array and a slice in Go.

An **array** has a fixed length that is part of its type: `[3]int` and `[4]int` are different types. Arrays are value types — assigning or passing one copies all elements.

A **slice** is a lightweight descriptor over a backing array. It is a three-word header: a pointer to the backing array, a length, and a capacity.

```
slice header:  ┌─────────┬─────┬──────────┐
               │  ptr →  │ len │   cap    │
               └─────────┴─────┴──────────┘
                    │
                    ▼
backing array: [ a ][ b ][ c ][ d ][ _ ][ _ ]
                 └───── len ─────┘
                 └────────── cap ──────────┘
```

Because a slice holds a pointer, copying a slice header is cheap and two slices can share the same backing array — mutating an element through one is visible through the other. Slices are what you use 99% of the time; arrays appear mostly as the backing store, in fixed-size buffers, or as map/struct keys.

### Q5. [Coding] What does `append` do when the slice runs out of capacity?

When `len == cap`, `append` allocates a new, larger backing array, copies the existing elements, and returns a slice pointing at the new array. If there is spare capacity, it writes in place and returns a slice sharing the original backing array.

```go
s := make([]int, 0, 2) // len 0, cap 2
s = append(s, 1)       // len 1, cap 2 — in place
s = append(s, 2)       // len 2, cap 2 — in place
s = append(s, 3)       // len 3, cap 4 — REALLOCATED, new backing array
```

This is why you must always reassign the result: `s = append(s, x)`. A subtle bug:

```go
a := []int{1, 2, 3}
b := a[:2]            // shares backing array with a, cap 3
b = append(b, 99)    // cap available → overwrites a[2] in place!
// a is now [1 2 99]
```

Growth is amortized O(1): the runtime roughly doubles capacity for small slices (and grows ~1.25x for large ones), so N appends cost O(N) total.

### Q6. [Theory] How do interfaces work in Go? What does "implicit implementation" mean?

A Go interface is a set of method signatures. A type satisfies an interface simply by having those methods — there is no `implements` keyword and no explicit declaration. This is structural (duck) typing resolved at compile time.

```go
type Stringer interface {
    String() string
}

type Point struct{ X, Y int }

func (p Point) String() string { // Point now satisfies Stringer, implicitly
    return fmt.Sprintf("(%d,%d)", p.X, p.Y)
}
```

The benefit is decoupling: a package can define an interface for what it *needs* without the implementing type ever importing it. The convention "accept interfaces, return structs" follows from this — define small interfaces at the consumer side.

Under the hood, an interface value is two words: a pointer to a type descriptor (the *itable*, mapping the interface methods to the concrete type's methods) and a pointer to the underlying data.

### Q7. [Theory] What is the zero value, and why does it matter in Go?

Every type in Go has a **zero value** that variables take when declared without an explicit initializer. There is no "undefined" or garbage memory.

- Numeric types → `0`
- `bool` → `false`
- `string` → `""`
- Pointers, slices, maps, channels, functions, interfaces → `nil`
- Structs → each field set to its own zero value

Good Go API design makes the zero value useful. `sync.Mutex`, `bytes.Buffer`, and `sync.WaitGroup` are all ready to use with no constructor. A `nil` slice behaves like an empty slice for `len`, `range`, and `append`. Designing types so the zero value is meaningful eliminates a whole class of "you forgot to call New" bugs.

### Q8. [Practical] How do you handle errors in Go? Why no exceptions?

Go treats errors as ordinary values. Functions that can fail return an `error` as the last result, and the caller checks it explicitly.

```go
f, err := os.Open("config.yaml")
if err != nil {
    return fmt.Errorf("loading config: %w", err)
}
defer f.Close()
```

`error` is just an interface with one method: `Error() string`. There is no implicit unwinding — control flow stays visible and local. The philosophy is that errors are expected and should be handled where they occur, not caught far away. `panic`/`recover` exists but is reserved for truly unrecoverable situations (programmer bugs, impossible states), not routine error handling. The `%w` verb *wraps* an error so callers can later inspect the chain with `errors.Is`/`errors.As`.

### Q9. [Coding] How do you use a map in Go, and how do you check whether a key exists?

```go
m := make(map[string]int)
m["alice"] = 30

// The two-value "comma ok" form distinguishes "present with zero value"
// from "absent".
v, ok := m["bob"]   // v == 0, ok == false
if ok {
    fmt.Println(v)
}

delete(m, "alice")  // safe even if key absent
```

Key points: a `nil` map can be read (returns the zero value) but **writing to a nil map panics**, so always `make` it first. Map iteration order is randomized deliberately — never rely on it. Map elements are not addressable, so you cannot do `&m[k]` or `m[k].field = x` for struct values; reassign the whole value instead.

### Q10. [Theory] What does `defer` do, and in what order do deferred calls run?

`defer` schedules a function call to run when the surrounding function returns — whether normally or via a panic. Deferred calls run in **last-in, first-out** order.

```go
func demo() {
    defer fmt.Println("1")
    defer fmt.Println("2")
    defer fmt.Println("3")
}
// prints: 3 2 1
```

It is the idiomatic way to pair acquire/release: `f.Close()`, `mu.Unlock()`, `wg.Done()`. Two gotchas: arguments are evaluated *when the defer statement executes*, not when the call runs (`defer fmt.Println(i)` captures `i`'s current value); and a deferred closure can read and modify named return values, which enables patterns like converting a panic into an error.

### Q11. [Practical] How do you read from multiple channels at once?

Use `select`. It blocks until one of its cases can proceed; if several are ready, it picks one at random.

```go
select {
case msg := <-ch1:
    fmt.Println("from ch1:", msg)
case ch2 <- value:
    fmt.Println("sent to ch2")
case <-time.After(time.Second):
    fmt.Println("timeout")
default:
    fmt.Println("nothing ready right now") // makes select non-blocking
}
```

A `default` case makes `select` non-blocking — it runs immediately if no other case is ready. Combined with `time.After`, `select` is how you implement timeouts. Combined with a `done` channel, it is how you implement cancellation.

### Q12. [Theory] What happens when you close a channel? How do you detect a closed channel?

Closing a channel signals that no more values will be sent. After `close(ch)`:

- Receivers continue to receive any buffered values, then receive the zero value immediately and indefinitely.
- The two-value receive form reports closure: `v, ok := <-ch` — `ok` is `false` once the channel is drained and closed.
- A `range` over a channel ends cleanly when the channel is closed.

```go
ch := make(chan int, 2)
ch <- 1; ch <- 2
close(ch)
for v := range ch { fmt.Println(v) } // prints 1, 2 then loop exits
```

Rules that prevent panics: **only the sender should close** a channel, never the receiver; **sending on a closed channel panics**; and **closing an already-closed channel panics**. Closing is a broadcast — every receiver observes it — which makes a closed channel an excellent "done" signal to fan out cancellation to many goroutines.

### Q13. [Theory] What is the difference between value receivers and pointer receivers?

A method can be declared on a value (`func (p Point)`) or a pointer (`func (p *Point)`).

- A **value receiver** operates on a copy; mutations don't affect the original.
- A **pointer receiver** can mutate the original and avoids copying large structs.

```go
func (c Counter) GetValue() int { return c.n }   // value: read-only view
func (c *Counter) Increment()    { c.n++ }        // pointer: mutates caller
```

Guidelines: use a pointer receiver if the method mutates the receiver, if the struct is large, or if any method in the set needs a pointer (keep the method set consistent). Note: only `*T` satisfies an interface if a method has a pointer receiver; a plain `T` value will not satisfy it because its method set excludes pointer-receiver methods.

### Q14. [Coding] Write a function that uses a channel to compute results concurrently.

A simple fan-out/fan-in: square numbers in parallel and collect the results.

```go
func squareAll(nums []int) []int {
    results := make(chan int, len(nums))
    var wg sync.WaitGroup
    for _, n := range nums {
        wg.Add(1)
        go func(x int) {
            defer wg.Done()
            results <- x * x
        }(n)
    }
    // Close the channel once all workers are done, so range terminates.
    go func() { wg.Wait(); close(results) }()

    var out []int
    for r := range results {
        out = append(out, r)
    }
    return out
}
```

The closer goroutine pattern — `go func(){ wg.Wait(); close(results) }()` — is the canonical way to close a results channel exactly when the last producer finishes, letting the consumer use a clean `range`.

### Q15. [Theory] What are Go modules and what do go.mod and go.sum do?

A **module** is a versioned collection of packages and the unit of dependency management. It is defined by a `go.mod` file at its root.

- `go.mod` declares the module path, the Go version, and the direct/indirect dependency requirements with their semantic versions.
- `go.sum` records cryptographic checksums of each dependency's content, so builds are verifiable and tamper-evident.

```
module github.com/me/app

go 1.24

require (
    github.com/google/uuid v1.6.0
    golang.org/x/sync v0.10.0 // indirect
)
```

Common commands: `go mod init <path>` creates a module, `go get pkg@version` adds/updates a dependency, `go mod tidy` adds missing and removes unused requirements, and `go mod download` fetches them. Modules use **minimal version selection** (MVS): the build picks the lowest version that satisfies all requirements, which makes builds reproducible.

### Q16. [Practical] How do you make a goroutine stop when its work is no longer needed?

Pass a cancellation signal in. The idiomatic mechanism is the `context` package; before contexts, people used a `done` channel.

```go
func worker(ctx context.Context, jobs <-chan int) {
    for {
        select {
        case <-ctx.Done():
            return // canceled or deadline exceeded
        case j := <-jobs:
            process(j)
        }
    }
}

ctx, cancel := context.WithCancel(context.Background())
go worker(ctx, jobs)
// ...later...
cancel() // every goroutine watching ctx.Done() unblocks and returns
```

Goroutines cannot be force-killed from outside; they must cooperatively check for cancellation. Always arrange for `cancel` to be called (often via `defer cancel()`) to release resources, even when the operation completes normally.

### Q17. [Theory] What is the empty interface, and what replaced it in modern Go?

The empty interface `interface{}` has no methods, so *every* type satisfies it — it can hold a value of any type. Since Go 1.18 it has an alias, `any`, which is now the idiomatic spelling.

```go
func describe(v any) {
    fmt.Printf("%T: %v\n", v, v)
}
describe(42)        // int: 42
describe("hello")   // string: hello
```

`any` is how you write heterogeneous containers (`[]any`), generic-ish JSON decoding (`map[string]any`), and `fmt.Println`-style variadics. The cost is that you lose static typing — to use the underlying value you must do a type assertion or type switch. Since generics arrived in 1.18, many uses of `any` (typed containers, generic algorithms) are better served by type parameters, which keep type safety. Reserve `any` for genuinely dynamic data.

### Q18. [Coding] How do you extract the concrete value from an interface? Show a type assertion and a type switch.

A **type assertion** retrieves the concrete value, with the comma-ok form to avoid panics:

```go
var v any = "hello"
s, ok := v.(string)  // s == "hello", ok == true
if ok { fmt.Println(len(s)) }

n, ok := v.(int)     // n == 0, ok == false (no panic because of comma-ok)
```

A **type switch** branches on the dynamic type:

```go
func stringify(v any) string {
    switch x := v.(type) {
    case nil:
        return "nil"
    case int:
        return strconv.Itoa(x)
    case string:
        return x
    case fmt.Stringer:
        return x.String()
    default:
        return fmt.Sprintf("%v", x)
    }
}
```

A bare assertion without the `ok` (`v.(int)`) panics if the type doesn't match — always use the comma-ok form unless you are certain.

## 🟡 Intermediate (3–7 yrs)

### Q19. [Theory] Explain the GMP scheduler model in Go.

The Go runtime scheduler maps goroutines onto OS threads using three entities:

- **G (goroutine)** — a goroutine: its stack, instruction pointer, and state.
- **M (machine)** — an OS thread. Ms actually execute code.
- **P (processor)** — a logical processor / scheduling context. A P holds a local run queue of runnable Gs and the resources needed to run Go code. The number of Ps is `GOMAXPROCS` (default = number of CPU cores).

```
        P0                P1
   ┌──────────┐      ┌──────────┐
   │ runq: G G│      │ runq: G  │
   └────┬─────┘      └────┬─────┘
        │ M0              │ M1     (Ms run on real CPUs)
   ┌─────────────────────────────┐
   │  global run queue:  G G G    │
   └─────────────────────────────┘
```

An M must hold a P to run Go code. The scheduler does **work stealing**: an idle P steals half the Gs from another P's run queue, keeping cores busy. When a goroutine makes a blocking syscall, the M can detach from its P so another M can pick up the P and keep running other goroutines (this is the *handoff*). The scheduler is also **preemptive** (since Go 1.14, via asynchronous signal-based preemption) so a tight loop with no function calls can still be preempted, preventing one goroutine from starving the rest.

### Q20. [Theory] What is the Go memory model, and what is a "happens-before" relationship?

The Go memory model specifies when a read of a variable in one goroutine is guaranteed to observe a write from another. The core concept is **happens-before**: if event A happens-before event B, then the effects of A are visible to B. Without a happens-before relationship, concurrent reads and writes to the same memory are a **data race** with undefined behavior.

Synchronization primitives establish happens-before edges:

- A send on a channel happens-before the corresponding receive completes.
- A `close` happens-before a receive that returns the zero value due to closure.
- An unlock of a `sync.Mutex` happens-before the next lock.
- `sync.Once.Do(f)` — the single call to `f` happens-before any `Do` returns.
- The completion of `go f()` setup happens-before the goroutine starts; a goroutine's exit does *not* synchronize with anything unless you add a channel/WaitGroup.

The practical rule: never share mutable data between goroutines without a synchronization primitive (channel, mutex, atomic). "Share memory by communicating; don't communicate by sharing memory."

### Q21. [Theory] When would you use a Mutex versus a channel?

Both coordinate access to shared state, but they suit different shapes of problem.

**Use a `Mutex`** when you have shared state (a counter, a cache, a map) that multiple goroutines read and write, and you simply need mutual exclusion around it. It is lower overhead and clearer for "protect this struct."

```go
type Cache struct {
    mu sync.Mutex
    m  map[string]string
}
func (c *Cache) Get(k string) string {
    c.mu.Lock()
    defer c.mu.Unlock()
    return c.m[k]
}
```

**Use channels** when you are passing ownership of data between goroutines, orchestrating a pipeline, distributing work, or signaling events. Channels model the *flow* of data; mutexes model the *protection* of data. A common heuristic: if you find yourself locking around the entire body of every method, a mutex is right; if data moves through stages, channels are right. `sync.RWMutex` is a variant allowing many concurrent readers but exclusive writers — worthwhile only when reads vastly dominate writes and the critical section is non-trivial.

### Q22. [Coding] Implement a thread-safe singleton initialization with sync.Once.

`sync.Once` guarantees a function runs exactly once, even under concurrent calls, and establishes happens-before so all callers see the fully constructed result.

```go
var (
    once     sync.Once
    instance *DB
)

func GetDB() *DB {
    once.Do(func() {
        instance = connectToDatabase() // runs exactly once
    })
    return instance
}
```

`once.Do` blocks concurrent callers until the first invocation completes, so no caller ever sees a half-initialized `instance`. Since Go 1.21 there are also `sync.OnceFunc`, `sync.OnceValue`, and `sync.OnceValues` helpers that wrap this pattern more ergonomically:

```go
var GetDB = sync.OnceValue(func() *DB { return connectToDatabase() })
```

### Q23. [Theory] What is the context package for, and what are its main constructors?

`context.Context` carries deadlines, cancellation signals, and request-scoped values across API boundaries and goroutines. It is the standard way to propagate "stop now" through a call tree.

- `context.Background()` — the root, never canceled; use in `main`, init, and tests.
- `context.TODO()` — placeholder when you haven't wired context through yet.
- `context.WithCancel(parent)` — returns a child plus a `cancel` func.
- `context.WithTimeout(parent, d)` / `context.WithDeadline(parent, t)` — auto-cancel after a duration / at a time.
- `context.WithValue(parent, k, v)` — attaches a request-scoped value (use sparingly, for cross-cutting data like a request ID, never for optional parameters).

```go
ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
defer cancel()
result, err := db.QueryContext(ctx, query)
```

Conventions: pass `ctx` as the first parameter, named `ctx`; never store a context in a struct; always call `cancel` (via `defer`) to free resources. Cancellation propagates down: canceling a parent cancels all descendants.

### Q24. [Coding] How do you detect and fix a data race? Use the race detector.

Run any build or test with the `-race` flag to instrument memory accesses and report races at runtime:

```
go test -race ./...
go run -race main.go
```

Consider this racy code — two goroutines write `counter` without synchronization:

```go
var counter int
for i := 0; i < 1000; i++ {
    go func() { counter++ }() // DATA RACE: concurrent unsynchronized writes
}
```

Fixes — use an atomic or a mutex:

```go
var counter atomic.Int64
for i := 0; i < 1000; i++ {
    go func() { counter.Add(1) }() // race-free
}
```

The race detector has no false positives — if it reports a race, there is one — but it only finds races that actually execute, so run it under realistic load and in CI. It adds ~5–10x CPU and ~2x memory overhead, so it's a testing tool, not a production setting.

### Q25. [Theory] Explain struct embedding and how method promotion works.

Embedding places one type inside another without a field name, giving composition with automatic delegation. The embedded type's exported fields and methods are **promoted** to the outer type.

```go
type Logger struct{ prefix string }
func (l Logger) Log(msg string) { fmt.Println(l.prefix, msg) }

type Server struct {
    Logger          // embedded (no field name)
    addr string
}

s := Server{Logger: Logger{prefix: "[srv]"}, addr: ":8080"}
s.Log("started") // promoted: calls s.Logger.Log("started")
```

Embedding is *not* inheritance — there is no subtype polymorphism and no virtual dispatch. It is delegation: `s.Log` is shorthand for `s.Logger.Log`. If the outer type defines a method with the same name, it shadows the promoted one. Embedding an interface in a struct is a useful trick for partial implementations and for satisfying large interfaces while overriding only a few methods. A famous example is `sync.Mutex` embedded directly in a struct so the struct gains `Lock`/`Unlock`.

### Q26. [Theory] How do errors.Is and errors.As work, and when do you use each?

Both inspect a wrapped error chain (errors built with `%w`).

- **`errors.Is(err, target)`** reports whether any error in the chain *is* a specific sentinel value. Use it to test against known sentinel errors like `io.EOF` or `sql.ErrNoRows`.
- **`errors.As(err, &target)`** finds the first error in the chain that matches a *type* and assigns it to `target`, letting you read its fields.

```go
if errors.Is(err, sql.ErrNoRows) {
    return nil // not found is fine
}

var pathErr *os.PathError
if errors.As(err, &pathErr) {
    log.Printf("operation %q failed on %q", pathErr.Op, pathErr.Path)
}
```

Wrapping with `fmt.Errorf("...: %w", err)` preserves the chain so these functions can walk it. Use `%w` to wrap; use `%v` when you deliberately want to *obscure* the underlying error (break the chain). A custom error type can also implement `Is` or `Unwrap` (including `Unwrap() []error` for multi-error joins via `errors.Join`).

### Q27. [Coding] How do you implement a custom error type and wrap errors?

```go
type ValidationError struct {
    Field string
    Msg   string
}

func (e *ValidationError) Error() string {
    return fmt.Sprintf("validation failed on %s: %s", e.Field, e.Msg)
}

func validate(age int) error {
    if age < 0 {
        return &ValidationError{Field: "age", Msg: "must be non-negative"}
    }
    return nil
}

func handle() error {
    if err := validate(-1); err != nil {
        return fmt.Errorf("handling request: %w", err) // wrap, preserving chain
    }
    return nil
}
```

The caller can recover the typed error:

```go
err := handle()
var ve *ValidationError
if errors.As(err, &ve) {
    fmt.Println("bad field:", ve.Field) // "age"
}
```

Use a pointer receiver for custom errors so the type, not a value copy, flows through `errors.As`. Sentinel errors (`var ErrNotFound = errors.New("not found")`) are the lighter-weight alternative when you don't need extra fields.

### Q28. [Practical] How do generics work in Go, and when should you use them?

Generics (Go 1.18+) let you write functions and types parameterized by type, with **constraints** describing what operations the type must support.

```go
type Number interface {
    ~int | ~int64 | ~float64 // ~ means "any type whose underlying type is this"
}

func Sum[T Number](xs []T) T {
    var total T
    for _, x := range xs {
        total += x
    }
    return total
}

Sum([]int{1, 2, 3})        // T inferred as int → 6
Sum([]float64{1.5, 2.5})   // T inferred as float64 → 4.0
```

The `constraints` come from the `comparable` built-in, the `golang.org/x/exp/constraints` (and now stdlib `cmp`) helpers, or custom interface unions. The `~` token includes named types with the given underlying type. Use generics for genuinely type-agnostic algorithms and containers (a `Set[T]`, a `Map`/`Filter`/`Reduce`, a generic LRU cache). **Don't** reach for them when a plain interface, a concrete type, or simple code reads more clearly — Go's culture still favors simplicity over premature abstraction.

### Q29. [Theory] What is escape analysis and how does it affect performance?

Escape analysis is a compile-time process that decides whether a value can live on the goroutine's **stack** or must be allocated on the **heap**. A value "escapes to the heap" when the compiler cannot prove its lifetime is bounded by the function — e.g. you return a pointer to a local, store it in a longer-lived structure, or pass it somewhere that captures it.

```go
func stackAlloc() int {
    x := 42        // stays on stack, freed when function returns
    return x
}

func heapAlloc() *int {
    x := 42        // x escapes — its address outlives the function
    return &x      // → heap allocation
}
```

Stack allocation is essentially free (just moving the stack pointer) and needs no GC work; heap allocation costs an allocation and adds pressure to the garbage collector. Inspect decisions with `go build -gcflags='-m'`, which prints "escapes to heap" / "does not escape". Practical implications: avoid unnecessarily returning pointers to small values, beware that passing values through `interface{}`/`any` often forces an escape, and don't manually optimize without profiling — the compiler is good at this.

### Q30. [Coding] Implement a worker pool with a fixed number of goroutines.

A worker pool bounds concurrency: a fixed set of goroutines pull jobs from a channel.

```go
func workerPool(jobs <-chan int, numWorkers int) <-chan int {
    results := make(chan int)
    var wg sync.WaitGroup

    for i := 0; i < numWorkers; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            for j := range jobs { // exits when jobs is closed
                results <- j * j
            }
        }()
    }

    go func() { wg.Wait(); close(results) }() // close results when all done
    return results
}

// usage
jobs := make(chan int)
out := workerPool(jobs, 4)
go func() {
    for i := 1; i <= 100; i++ { jobs <- i }
    close(jobs) // signals workers to finish
}()
for r := range out {
    fmt.Println(r)
}
```

`numWorkers` caps how many jobs run at once, which protects downstream resources (DB connections, file handles). Closing `jobs` is the signal for workers to drain and exit; the closer goroutine then closes `results`.

### Q31. [Theory] What is a nil interface, and why can a non-nil interface holding a nil pointer be surprising?

An interface value is nil only when *both* its type word and value word are nil. A classic gotcha:

```go
func doWork() error {
    var p *MyError = nil
    // ... p stays nil ...
    return p // returns an interface with type=*MyError, value=nil
}

err := doWork()
if err != nil {
    // THIS BRANCH RUNS — err is not nil!
    fmt.Println("got error") // surprising
}
```

Even though the underlying pointer is nil, the returned `error` interface has a non-nil *type* (`*MyError`), so `err != nil` is true. The fix is to return a literal `nil` (an untyped nil) when there is no error, rather than a nil-valued concrete pointer:

```go
func doWork() error {
    return nil // correct: interface is truly nil
}
```

This is one of Go's most notorious pitfalls. The rule: don't declare a typed `var err *MyError` and return it on the success path — return `nil` directly.

### Q32. [Theory] How does Go's garbage collector work at a high level?

Go uses a **concurrent, tri-color, mark-and-sweep** garbage collector designed for low latency over raw throughput. It is non-generational and non-compacting.

- **Tri-color marking**: objects are white (candidate for collection), grey (reachable but children not yet scanned), or black (reachable and scanned). The GC marks from roots, moving objects white→grey→black, mostly concurrently with the running program.
- **Write barrier**: while the GC runs concurrently with mutators, a write barrier intercepts pointer writes so the GC doesn't miss objects that get re-linked mid-cycle (maintaining the tri-color invariant).
- **Sweep**: reclaims white (unreachable) objects, returning memory for reuse.

Stop-the-world pauses are kept to sub-millisecond — only short pauses at the start/end of a cycle for setup; marking and sweeping run concurrently. The GC is paced to start when the heap grows by `GOGC` percent (default 100, i.e. when the heap doubles). Since Go 1.19 you can also set a soft memory limit with `GOMEMLIMIT` to cap total heap, useful in containers. Tuning levers: raise `GOGC` to trade memory for less frequent GC, or reduce allocations (the most effective lever) by reusing buffers, using `sync.Pool`, and avoiding unnecessary escapes.

### Q33. [Practical] How do you propagate a timeout through a call chain using context?

Create a context with a deadline at the boundary, then thread it through every function and downstream call. Each layer respects `ctx.Done()`.

```go
func handler(w http.ResponseWriter, r *http.Request) {
    ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
    defer cancel()

    user, err := fetchUser(ctx, id) // ctx flows down
    if err != nil {
        http.Error(w, err.Error(), http.StatusGatewayTimeout)
        return
    }
    json.NewEncoder(w).Encode(user)
}

func fetchUser(ctx context.Context, id string) (*User, error) {
    // QueryContext aborts if ctx's deadline passes or it is canceled
    row := db.QueryRowContext(ctx, "SELECT ... WHERE id = ?", id)
    // ...
}
```

The deadline is absolute and shared: if the handler's 3 seconds elapse, *every* downstream operation using that context (DB queries, HTTP calls) is canceled simultaneously. Children can shorten but never extend a parent's deadline. Always `defer cancel()` even on the timeout path to release the timer.

### Q34. [Coding] Implement a pipeline of channel stages (generator → transform → sink).

Pipelines chain stages, each a goroutine consuming from one channel and producing to the next.

```go
func gen(nums ...int) <-chan int {
    out := make(chan int)
    go func() {
        defer close(out)
        for _, n := range nums { out <- n }
    }()
    return out
}

func square(in <-chan int) <-chan int {
    out := make(chan int)
    go func() {
        defer close(out)
        for n := range in { out <- n * n }
    }()
    return out
}

func main() {
    for v := range square(gen(2, 3, 4)) {
        fmt.Println(v) // 4, 9, 16
    }
}
```

Each stage owns its output channel and closes it (via `defer close(out)`) when its input is exhausted — closure cascades down the pipeline, terminating each `range`. For cancellation, add a `ctx context.Context` parameter and `select` on `ctx.Done()` inside each stage so an early consumer exit doesn't leak the upstream goroutines.

### Q35. [Theory] What's the difference between concurrency and parallelism in Go?

**Concurrency** is structuring a program as independently executing pieces (goroutines) that *can* make progress in overlapping time windows. **Parallelism** is *actually* running multiple pieces at the same instant on multiple CPU cores. As Rob Pike put it: "Concurrency is about dealing with lots of things at once; parallelism is about doing lots of things at once."

A Go program with thousands of goroutines is concurrent even on a single core — the scheduler interleaves them. Whether they run in parallel depends on `GOMAXPROCS` and available cores: with `GOMAXPROCS=1`, goroutines run concurrently but never in parallel. Concurrency is a design property; parallelism is an execution property. Good concurrent design (decomposing into goroutines + channels) lets the runtime exploit whatever parallelism the hardware offers, without you writing thread-management code.

### Q36. [Practical] How do you avoid goroutine leaks?

A goroutine leaks when it blocks forever on a channel send/receive that no one will service, so it never returns and its stack is never reclaimed. Common causes and fixes:

- **Blocked send with no receiver**: a producer sends to an unbuffered channel but the consumer exited early. Fix with `select { case ch <- v: case <-ctx.Done(): return }`.
- **Forgotten `cancel`**: a `context.WithCancel`/`WithTimeout` whose `cancel` is never called leaks the internal goroutine/timer. Always `defer cancel()`.
- **Range over a never-closed channel**: ensure the sender closes the channel.
- **Unbuffered result channel from a finished operation**: e.g. a goroutine that times out leaves a producer blocked on send — give the channel a buffer of 1 so the send always succeeds.

```go
func search(ctx context.Context) (Result, error) {
    ch := make(chan Result, 1) // buffer 1 so the producer never blocks
    go func() { ch <- doSearch() }()
    select {
    case r := <-ch:
        return r, nil
    case <-ctx.Done():
        return Result{}, ctx.Err() // producer can still send into the buffer, no leak
    }
}
```

Detect leaks with `runtime.NumGoroutine()` in tests, the `go.uber.org/goleak` package, or pprof's goroutine profile.

## 🟠 Advanced (8–12 yrs)

### Q37. [Theory] Explain how the runtime handles a blocking syscall versus a blocking channel operation.

The two block differently because one leaves Go's scheduler and one stays inside it.

- **Blocking channel op / mutex**: stays in user space. The goroutine (G) is parked and removed from its P's run queue; the M immediately picks another runnable G. No OS thread is consumed by the waiting goroutine. When the channel becomes ready, the G is made runnable again. This is cheap and is why millions of goroutines can wait concurrently.
- **Blocking syscall** (e.g. a raw `read` on a file): the M enters the kernel and cannot run Go code. The runtime detaches the P from that M (handoff) so another M can grab the P and keep executing other goroutines. When the syscall returns, the M tries to reacquire a P; if none is free, its G is put on the global run queue and the M parks.
- **Network I/O** is special: it goes through the **netpoller** (epoll/kqueue/IOCP). A blocking network read parks the goroutine and registers the fd with the poller — no M is blocked. When the fd is ready, the poller makes the goroutine runnable. So network I/O behaves like a channel block, not a syscall block, which is why Go servers scale to huge connection counts with few threads.

```
channel block: G parked, M reused           → 0 extra threads
network I/O:    G parked via netpoller, M reused → 0 extra threads
file/CGo syscall: M blocks in kernel, P handed off → may spin up a new M
```

### Q38. [Theory] How does Go grow goroutine stacks, and what are the implications?

Goroutines start with a small (~2 KB since Go 1.4) contiguous stack. The compiler inserts a **stack-growth check** (a prologue) at the start of most functions. When a call would overflow the current stack, the runtime:

1. Allocates a new, larger stack (typically double the size).
2. **Copies** the existing stack contents to the new region.
3. Adjusts all pointers that point into the stack (the runtime knows the stack maps via precise pointer metadata).
4. Resumes execution on the new stack.

Stacks can also **shrink** during GC if a goroutine is using much less than it has. Implications:

- Deep recursion is fine until you hit the max stack size (1 GB on 64-bit by default), at which point the program panics with "stack overflow."
- Because stacks move, you must never assume a stack address is stable; the runtime fixes up Go pointers, but raw addresses passed to C (via CGo) must be heap-allocated or pinned (`runtime.Pinner`).
- Stack copying is why tight recursive or deeply nested code can have surprising allocation/copy costs the first time it grows; subsequent calls reuse the grown stack.

### Q39. [Theory] What is false sharing, and how do you mitigate it in Go?

False sharing happens when two goroutines on different cores update *different* variables that happen to live on the **same CPU cache line** (typically 64 bytes). The cache-coherence protocol invalidates the whole line on every write, so the cores ping-pong the line back and forth, destroying the benefit of per-core data even though there is no logical contention.

```go
// Bad: counters[0] and counters[1] likely share a cache line
type counters struct {
    a int64
    b int64
}
```

Mitigate by **padding** so each hot variable occupies its own cache line:

```go
type paddedCounter struct {
    value int64
    _     [56]byte // pad to 64 bytes (8 + 56), isolating the cache line
}
```

You see this in high-performance sharded counters, per-P data structures, and lock-free queues. Measure first — padding wastes memory, so only pad fields you have profiled as hot and contended. The runtime itself pads per-P structures for this reason. Note alignment also matters: 64-bit atomic fields must be 64-bit aligned on 32-bit platforms (put them first in the struct or use `atomic.Int64`, which guarantees alignment).

### Q40. [Coding] Implement a concurrency-limited fan-out using a semaphore channel.

A buffered channel makes a clean counting semaphore: send to acquire a slot, receive to release.

```go
func processAll(ctx context.Context, items []Item, limit int) error {
    sem := make(chan struct{}, limit) // capacity = max in-flight
    g, ctx := errgroup.WithContext(ctx)

    for _, it := range items {
        it := it
        // Acquire a slot (blocks if `limit` are already running).
        select {
        case sem <- struct{}{}:
        case <-ctx.Done():
            return ctx.Err()
        }
        g.Go(func() error {
            defer func() { <-sem }() // release the slot
            return process(ctx, it)
        })
    }
    return g.Wait()
}
```

`errgroup.Group` (from `golang.org/x/sync/errgroup`) gives you fan-out with first-error propagation and context cancellation: when any task returns an error, the group's context is canceled so the others can bail out. The `sem` channel caps concurrency at `limit`, protecting downstream resources. Since Go's `x/sync/semaphore` also provides a weighted semaphore, prefer it when slots have different weights.

### Q41. [Theory] How would you reduce GC pressure in a high-throughput service?

The most effective lever is **allocating less**, since the GC's work is proportional to live heap and allocation rate.

- **Reuse buffers** with `sync.Pool` for short-lived objects that are allocated and discarded frequently (e.g. per-request buffers, serialization scratch space). Pool entries can be reclaimed by the GC, so it's a cache, not a guaranteed pool.
- **Preallocate slices/maps** with a capacity hint (`make([]T, 0, n)`) to avoid repeated growth-and-copy.
- **Avoid escapes**: keep values on the stack; check with `-gcflags=-m`. Watch for `interface{}`/`any` boxing and closures that capture by reference.
- **Prefer value types over pointers** for small structs in hot paths to reduce heap objects and pointer-chasing the GC must scan.
- **Batch and pass slices**, not element-by-element through channels, to cut per-item overhead.
- **Tune `GOGC`** upward (e.g. 200–400) to run GC less often when you have memory headroom, or set `GOMEMLIMIT` to cap heap in a container while letting `GOGC` float.

```go
var bufPool = sync.Pool{New: func() any { return new(bytes.Buffer) }}

func handle() {
    buf := bufPool.Get().(*bytes.Buffer)
    buf.Reset()
    defer bufPool.Put(buf)
    // ... use buf ...
}
```

Always profile with pprof (`alloc_space`, `inuse_space`) and benchmark with `-benchmem` to confirm allocations dropped before and after.

### Q42. [Practical] How do you profile a Go program for CPU, memory, and contention?

Go ships first-class profiling via `runtime/pprof` and `net/http/pprof`.

```go
import _ "net/http/pprof" // registers handlers on the default mux
go func() { log.Println(http.ListenAndServe("localhost:6060", nil)) }()
```

Then collect and analyze:

```
# CPU profile for 30s
go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30
# heap (memory) profile
go tool pprof http://localhost:6060/debug/pprof/heap
# blocking profile (channel/mutex waits) — must enable first
# goroutine + mutex
go tool pprof http://localhost:6060/debug/pprof/mutex
```

Inside pprof use `top`, `list <func>`, and `web` (flame/call graph). For benchmarks: `go test -bench=. -benchmem -cpuprofile cpu.out -memprofile mem.out`. To capture contention you must enable sampling: `runtime.SetBlockProfileRate(n)` for channel/sync blocking and `runtime.SetMutexProfileFraction(n)` for mutex contention. The **execution tracer** (`go tool trace`, via `runtime/trace` or `/debug/pprof/trace`) is the tool for scheduler-level questions — goroutine latency, GC pauses, syscall blocking, and per-P timelines.

### Q43. [Theory] Explain the cost model of `select` and channel operations under contention.

Channel operations are not free; under contention they touch a per-channel lock.

- Each channel has an internal mutex (`hchan.lock`). Send, receive, and `select` acquire it briefly to manipulate the buffer and the sender/receiver wait queues (`sudog` structures). Highly contended channels serialize on that lock and can become a bottleneck — visible as runtime lock contention in profiles.
- An **unbuffered** send/receive that rendezvous performs a direct hand-off (the value is copied straight from sender to receiver stack) — efficient, but requires both parties present.
- A **`select` with N cases** must, in the blocking path, lock all involved channels (in a fixed address order to avoid deadlock), enqueue the goroutine on each, and on wake-up dequeue from the others. So a wide `select` has overhead proportional to its case count, paid on every block.
- A `select` with a `default` is cheaper when something is ready — it's a non-blocking poll that avoids the enqueue/dequeue dance.

Practical guidance: for very hot paths, batching (sending slices), sharding channels, or replacing a channel with an atomic/lock-free structure can outperform a single contended channel. But channels remain the right default for clarity; only reach for these optimizations with profiler evidence.

### Q44. [Behavioral] Tell me about a time you debugged a difficult concurrency bug in production.

(Structure the answer with situation, the investigation, the fix, and what you institutionalized.) A strong answer demonstrates methodical reasoning rather than guess-and-check.

A good template: "We had intermittent data corruption in a caching layer that only appeared under high load and never in tests. I first reproduced it by running the integration suite with `-race`, which immediately flagged a concurrent map write — two request handlers mutating a shared `map[string]*Entry` without locking, because someone had added a write path to what was originally a read-only cache. The race detector pinpointed the exact lines and goroutines. The fix was to guard the map with an `sync.RWMutex` (reads dominated, so RWMutex over plain Mutex), and I verified by running the suite with `-race` in a loop and under load. To prevent recurrence I added `-race` to our CI test stage and a `go.uber.org/goleak` check, and I documented the cache's concurrency contract in a doc comment. The broader lesson: races that 'can't happen' usually mean an invariant changed silently — encode concurrency invariants in code (e.g. unexported fields + accessor methods) and in CI, not in tribal knowledge."

What interviewers look for: you used the right tools (`-race`, pprof, tracer), you reasoned about the happens-before relationship, you chose the appropriate primitive with justification, and you closed the loop with prevention.

### Q45. [Theory] What are the trade-offs of `sync.RWMutex` versus `sync.Mutex`, and when does RWMutex hurt?

`RWMutex` allows many concurrent readers or one writer. It seems strictly better for read-heavy workloads, but it has real costs:

- **Higher overhead per operation**: `RLock`/`RUnlock` do more bookkeeping (a reader count plus a writer-pending flag) than a plain `Lock`/`Unlock`. For short critical sections, the extra atomic operations can make `RWMutex` *slower* than `Mutex` even with concurrent reads.
- **Writer starvation avoidance adds latency**: Go's `RWMutex` blocks new readers once a writer is waiting (to prevent writer starvation), so a steady stream of readers doesn't indefinitely block a writer — but this coordination costs cache-line traffic.
- **Cache-line contention**: the reader counter is a shared atomic that every reader writes, so on many cores `RLock` itself becomes a contention point (the readers ping-pong the counter's cache line).

Rule of thumb: use `RWMutex` only when reads vastly outnumber writes **and** the critical section is long enough that parallel reads actually save time. For tiny critical sections (read one field), a plain `Mutex`, an `atomic` value, or a copy-on-write `atomic.Pointer` is usually faster. Always benchmark with realistic reader/writer ratios.

### Q46. [Coding] Implement a copy-on-write configuration holder using atomic.Pointer.

For read-mostly state that is replaced wholesale (not mutated in place), `atomic.Pointer[T]` gives lock-free reads.

```go
type Config struct {
    Timeout time.Duration
    Hosts   []string
}

type ConfigStore struct {
    cur atomic.Pointer[Config]
}

func NewConfigStore(initial *Config) *ConfigStore {
    s := &ConfigStore{}
    s.cur.Store(initial)
    return s
}

// Load is lock-free and safe for any number of concurrent readers.
func (s *ConfigStore) Load() *Config {
    return s.cur.Load()
}

// Update swaps in a brand-new Config; readers never see a partial state.
func (s *ConfigStore) Update(next *Config) {
    s.cur.Store(next) // atomic publish
}
```

Readers call `Load()` with zero locking and zero contention beyond reading a pointer. Crucially, the `*Config` is treated as **immutable** once published — to change config you build a new `Config` and `Store` it, never mutate the one readers hold. This pattern (also expressible with `atomic.Value`) outperforms `RWMutex` for hot read paths like feature flags, routing tables, and config. The happens-before guarantee of the atomic store-then-load ensures readers see a fully constructed `Config`.

### Q47. [Theory] How does Go achieve preemption of long-running goroutines?

Before Go 1.14, the scheduler was **cooperative**: a goroutine yielded only at function-call boundaries (where the stack-growth prologue lives) and at blocking operations. A tight loop with no function calls — e.g. `for { i++ }` — could monopolize its M, starving other goroutines and even delaying GC (which needs to stop the world).

Go 1.14 introduced **asynchronous preemption**. The runtime's `sysmon` (system monitor) thread notices a goroutine that has run too long (~10 ms) and sends the M an OS signal (`SIGURG` on Unix). The signal handler interrupts the goroutine at a *safe point* — the runtime uses precise stack maps to ensure it can safely capture the goroutine's state — parks it, and lets the scheduler run something else. This made formerly-pathological loops preemptible and dramatically reduced GC-related tail latencies.

```
sysmon: "G has run 10ms" → send SIGURG to its M
M's signal handler: at a safe point, snapshot G's registers/stack → reschedule
```

Implications: you almost never need to insert manual `runtime.Gosched()` calls anymore. Exceptions remain — non-preemptible regions (e.g. inside the runtime, or code without safe points like some hand-written assembly) — but ordinary Go code is now fairly preemptible.

### Q48. [Practical] How do you design a graceful shutdown for a Go service?

Graceful shutdown stops accepting new work, drains in-flight work, and releases resources within a deadline.

```go
func main() {
    srv := &http.Server{Addr: ":8080", Handler: mux}

    go func() {
        if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            log.Fatal(err)
        }
    }()

    // Wait for SIGINT/SIGTERM (k8s sends SIGTERM before killing the pod).
    ctx, stop := signal.NotifyContext(context.Background(),
        syscall.SIGINT, syscall.SIGTERM)
    defer stop()
    <-ctx.Done()

    // Give in-flight requests a bounded time to finish.
    shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
    defer cancel()
    if err := srv.Shutdown(shutdownCtx); err != nil {
        log.Printf("graceful shutdown failed: %v", err)
        srv.Close() // force-close remaining connections
    }
    // Then close DB pools, flush metrics, drain queues, etc.
}
```

Key elements: `signal.NotifyContext` (Go 1.16+) turns OS signals into a cancelable context; `srv.Shutdown` stops accepting connections and waits for active requests; a bounded `shutdownCtx` prevents hanging forever; and you propagate the shutdown context into background workers so they stop too. Order matters — stop ingress first, then drain, then close downstream connections.

## 🔴 Expert (15+ yrs)

### Q49. [Theory] Walk through the full lifecycle of a GC cycle in modern Go, including pacing.

A Go GC cycle (concurrent mark-and-sweep) proceeds in phases:

1. **Sweep termination** (brief STW): finish sweeping any spans left from the previous cycle so the heap is in a known state.
2. **Mark setup / STW pause #1**: enable the write barrier, scan stacks (stacks are scanned with the goroutine briefly paused), and seed the work queue with roots (globals, stacks). This pause is sub-millisecond.
3. **Concurrent mark**: mutators and GC run together. The GC drains the grey-object work queue, blackening reachable objects. The **write barrier** records any pointer the mutator installs into an already-scanned (black) object so nothing reachable is missed (preserving the strong tri-color invariant via a hybrid Yuasa/Dijkstra barrier). Mutator goroutines do **mark assist** — if a goroutine allocates faster than the GC marks, it's conscripted to do proportional marking work, applying back-pressure so the heap can't outrun the collector.
4. **Mark termination** (brief STW pause #2): drain remaining work, disable the write barrier, compute the next cycle's trigger.
5. **Concurrent sweep**: reclaim white objects lazily as memory is requested, returning spans to the allocator; unused memory is eventually returned to the OS by the background scavenger.

**Pacing**: the GC's goal is to finish marking just before the heap reaches the target size. The target is set by `GOGC` (default 100 → heap may grow to 2x live set) or capped by `GOMEMLIMIT`. The pacer estimates marking progress versus allocation rate and starts the cycle early enough — and dials mark-assist — so the heap lands near target without a long STW. The two STW pauses are tiny and roughly constant; total pause time is decoupled from heap size, which is the whole point of this design (latency over throughput).

### Q50. [Theory] How does CGo interact with the scheduler and GC, and what are the costs?

Calling C from Go (CGo) crosses a boundary with real runtime consequences:

- **Scheduler**: a CGo call is treated like a syscall — the goroutine's M enters C code and detaches its P so other goroutines keep running. But there's fixed overhead per call (saving/restoring state, switching to the M's system stack, the P handoff machinery) — on the order of tens of nanoseconds plus, making fine-grained CGo calls in a hot loop expensive. Batch work across the boundary.
- **Stacks**: C runs on the M's larger system stack, not the goroutine's growable stack, because C code can't tolerate Go's stack copying.
- **GC and pointers**: the **cgo pointer-passing rules** forbid C from holding a Go pointer past the call's return (the GC can't see or track it, and stacks can move). To pass Go memory into C that must outlive the call, you must pin it (`runtime.Pinner`) or copy it to C-allocated memory (`C.malloc`). Violations are caught at runtime by `cgocheck` and panic.
- **Blocking C calls**: a C call that blocks for a long time keeps its M occupied and can force the runtime to spawn additional Ms (up to the thread limit), so blocking CGo doesn't scale like native Go I/O.

Net: CGo is the right tool for reusing mature C libraries, but it taxes the scheduler, defeats some Go tooling (race detector coverage, escape analysis, profiling fidelity), complicates cross-compilation, and adds latency. The idiom "the first rule of CGo is don't use CGo" reflects preferring pure-Go implementations when feasible.

### Q51. [Coding] Implement a lock-free single-producer/single-consumer guarantee or a generic typed sync.Pool wrapper.

A type-safe `Pool[T]` wrapper that eliminates the `any` casts and resets objects on return:

```go
type Pool[T any] struct {
    pool  sync.Pool
    reset func(*T)
}

func NewPool[T any](newFn func() *T, reset func(*T)) *Pool[T] {
    return &Pool[T]{
        pool:  sync.Pool{New: func() any { return newFn() }},
        reset: reset,
    }
}

func (p *Pool[T]) Get() *T {
    return p.pool.Get().(*T)
}

func (p *Pool[T]) Put(x *T) {
    if p.reset != nil {
        p.reset(x) // clear state so no data leaks between users
    }
    p.pool.Put(x)
}

// usage
var bufPool = NewPool(
    func() *bytes.Buffer { return new(bytes.Buffer) },
    func(b *bytes.Buffer) { b.Reset() },
)
```

Subtleties an expert should call out: `sync.Pool` is per-P internally (lock-free fast path via the P-local cache, with steal from other Ps), which is what makes it scale; its contents are cleared at the start of each GC, so it's a cache of transient objects, *not* a resource pool for connections; you must `Reset` returned objects to avoid leaking the previous user's data; and storing pointers (not values) avoids boxing on every `Put`. Putting back an object you still hold a reference to is a classic use-after-free-style bug.

### Q52. [Theory] How would you diagnose and resolve high tail latency (p99) in a Go service?

High p99 with healthy p50 usually points to *occasional* stalls, not steady slowness. A systematic approach:

- **Rule out GC pauses**: check `GODEBUG=gctrace=1` output and the execution tracer (`go tool trace`) for STW durations and mark-assist time. If GC is the culprit, reduce allocation rate, raise `GOGC`/set `GOMEMLIMIT`, or pool buffers. Mark-assist showing up means allocation is outrunning the collector.
- **Scheduler latency**: the tracer shows goroutine "runnable but not running" time. Causes: too few Ps (`GOMAXPROCS` wrong in a container — set it to the CPU *limit*, e.g. via `automaxprocs`), a non-preemptible hot loop hogging a P, or thundering-herd wakeups on a single contended channel.
- **Lock/channel contention**: enable the block and mutex profiles; a hot `Mutex` or channel serializes requests and creates a long tail. Shard the lock, switch to `atomic`/copy-on-write, or reduce critical-section size.
- **Tail amplification from fan-out**: if a request fans out to N backends, p99 of the request is dominated by the slowest of N — use hedged requests, per-call timeouts via context, and backup requests.
- **Syscall/CGo stalls and netpoller**: blocking CGo or DNS lookups (the cgo resolver) can spike tails; prefer the pure-Go resolver and bound external calls.
- **Noisy neighbors / cgroup throttling**: CPU throttling in Kubernetes manifests as periodic latency spikes — check `container_cpu_cfs_throttled` metrics and right-size requests/limits and `GOMAXPROCS`.

The throughline: use the tracer and the right profiles to attribute the stall to GC, scheduler, contention, or downstream — then apply the matching fix and re-measure p99 under representative load.

### Q53. [Theory] What are the guarantees and pitfalls of the sync/atomic package and memory ordering in Go?

`sync/atomic` provides atomic loads, stores, add, swap, and compare-and-swap on integers and pointers, plus the typed wrappers (`atomic.Int64`, `atomic.Pointer[T]`, `atomic.Bool`) added in Go 1.19.

Guarantees and subtleties:

- **Atomicity**: operations are indivisible — no torn reads/writes — and the typed wrappers guarantee proper alignment (important on 32-bit platforms, where a misaligned 64-bit atomic panics; with the old function API you must place 64-bit fields first in the struct).
- **Memory ordering**: Go's memory model (formalized in Go 1.19) states that atomics behave as **sequentially consistent** with respect to each other. So an atomic store followed by an atomic load establishes happens-before, like the copy-on-write config pattern relies on. You do *not* get fine-grained acquire/release control as in C++ — Go deliberately offers only the SC model to keep things simple and safe.
- **Pitfall — mixing atomic and non-atomic access**: if one goroutine writes a variable atomically and another reads it non-atomically (or vice versa), that's a data race with undefined behavior. All access to an atomically-managed variable must be atomic.
- **Pitfall — atomics are not transactions**: a CAS loop is needed for read-modify-write of compound state; `atomic.AddInt64` is fine for a counter, but "increment if below limit" needs a CAS retry loop.
- **Pitfall — false confidence on structs**: atomics protect a single word. Publishing a multi-field struct requires publishing a *pointer* to an immutable struct (`atomic.Pointer`), not atomically updating fields one by one.

```go
for {
    old := counter.Load()
    if old >= limit { return false }
    if counter.CompareAndSwap(old, old+1) { return true } // retry on contention
}
```

### Q54. [Behavioral] How do you make architectural decisions about adopting Go (or a new Go feature like generics) across a large team?

A senior/staff answer balances technical merit with organizational reality and shows you've owned such decisions.

A strong narrative: "When generics landed, there was pressure to 'genericize everything.' I framed the decision around concrete criteria rather than novelty: adopt generics where they remove real duplication and preserve type safety that `interface{}` was throwing away — typed containers, our `Result[T]` and pagination helpers — and explicitly *not* in places where a concrete type or a small interface read more clearly. I wrote a short internal guideline with examples of good and bad uses, seeded a few exemplar PRs, and added linters. For adopting Go itself on a new service, I evaluated it against the team's existing skills, the operational story (single static binary, great observability tooling, cheap concurrency for our I/O-bound workload), library maturity for our needs, and hiring. I ran a time-boxed spike to de-risk the unknowns (our gRPC and DB drivers), presented trade-offs honestly including where Go is weaker (error-handling verbosity, lack of sum types, generics ergonomics), and got buy-in by letting the team build a non-critical service first."

What interviewers assess: you make decisions from first principles and data, you weigh team capability and maintainability (not just 'is it cool'), you de-risk with spikes, you document and create guardrails (linters, exemplars), and you're candid about trade-offs rather than evangelistic.

### Q55. [Theory] Explain how Go's netpoller integrates with the scheduler to achieve scalable I/O.

The **netpoller** is the runtime's bridge between blocking-style Go network code and the OS's non-blocking, event-driven I/O (epoll on Linux, kqueue on BSD/macOS, IOCP on Windows). It is what lets a Go server handle hundreds of thousands of connections with a handful of threads while you write straightforward blocking-looking code.

How it works:

1. When you call `conn.Read` and no data is available, the runtime puts the underlying fd into non-blocking mode and registers it with the platform poller. The goroutine is **parked** (set to `Gwaiting`) and removed from its P — but, crucially, **no M is blocked**. The M is free to run other goroutines.
2. A dedicated mechanism polls the OS for ready fds. The scheduler calls `netpoll()` at strategic points (in `sysmon`, during scheduling, before idling a P) to retrieve the list of fds whose I/O is now ready.
3. Each ready fd's parked goroutine is made runnable again and placed on a run queue, where some P/M picks it up and the `Read` returns. From the goroutine's perspective, `Read` simply blocked and resumed.

```
conn.Read (no data) ─► register fd with epoll, park G, free the M
                         ...M runs other goroutines...
epoll says fd ready ─► netpoll() returns it ─► G becomes runnable ─► Read returns
```

This is why network I/O scales like a channel block, not a syscall block (contrast with file I/O and CGo, which can actually occupy an M). The design unifies "millions of cheap goroutines" with "few OS threads": blocking semantics for the programmer, event-driven efficiency under the hood. The cost is that the netpoller adds a small amount of scheduling latency and that truly synchronous OS interfaces (regular files, some DNS paths) bypass it and can still consume threads.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q56. [Theory] What is the in-memory layout of a Go string, and why are strings immutable?

A `string` is a two-word header: a pointer to the underlying byte array and a length. There is no capacity and no null terminator.

```
string header: ┌─────────┬─────┐
               │  ptr →  │ len │
               └─────────┴─────┘
                    │
                    ▼
bytes:         [ h ][ e ][ l ][ l ][ o ]
```

Strings are **immutable**: you cannot index-assign (`s[0] = 'H'` is a compile error). Immutability is what makes strings cheap to copy (copy two words, share the bytes), safe to use as map keys, and safe to pass around concurrently without synchronization. Because the backing bytes never change, multiple strings (and slices of the same literal) can share storage. Converting `[]byte(s)` or `string(b)` normally **copies** the bytes precisely to preserve this immutability — though the compiler elides the copy in proven-safe cases like `string(b)` used only as a transient map-lookup key. A string is UTF-8 encoded by convention but is really just an immutable byte sequence; `len(s)` is the byte count, not the rune count.

#### Q57. [Theory] What is the difference between a rune and a byte, and how does `range` over a string behave?

A `byte` is an alias for `uint8` (one 8-bit byte); a `rune` is an alias for `int32` and represents a single Unicode code point. Since Go source and strings are UTF-8, a code point occupies 1–4 bytes.

```go
s := "héllo" // 'é' is two bytes in UTF-8
fmt.Println(len(s))                    // 6 (bytes)
fmt.Println(utf8.RuneCountInString(s)) // 5 (runes)

for i, r := range s {
    fmt.Printf("%d:%c ", i, r) // i jumps by the byte width of each rune
}
// 0:h 1:é 3:l 4:l 5:o   (index 2 is skipped — 'é' spans bytes 1-2)
```

Indexing a string (`s[i]`) yields a **byte**, not a rune. Ranging over a string decodes UTF-8 and yields `(byteIndex, rune)` pairs — the index is the starting byte offset of each rune, so it advances by 1–4 each step. An invalid UTF-8 byte yields the replacement rune `U+FFFD` with width 1. To iterate bytes instead, use a C-style `for i := 0; i < len(s); i++` loop.

#### Q58. [Coding] How do you reverse a string correctly in Go, accounting for Unicode?

A naive byte reversal corrupts multibyte runes. Reverse the slice of **runes** instead.

```go
func reverse(s string) string {
    r := []rune(s)          // decode UTF-8 into code points
    for i, j := 0, len(r)-1; i < j; i, j = i+1, j-1 {
        r[i], r[j] = r[j], r[i]
    }
    return string(r)        // re-encode to UTF-8
}

// reverse("héllo") == "olléh"   (byte reversal would mangle 'é')
```

This handles code points correctly, but note it still won't perfectly reverse strings containing **grapheme clusters** (e.g. an emoji with a skin-tone modifier, or a combining accent), since one user-perceived character can be several runes. For full grapheme-correct reversal you need a segmentation library (`golang.org/x/text/unicode/norm` plus grapheme iteration). For interview purposes, the rune-slice approach is the expected answer; mentioning the grapheme caveat signals depth.

#### Q59. [Theory] What is the difference between `new(T)` and `make(T)`?

Both allocate, but they serve different purposes and return different things.

- `new(T)` allocates zeroed storage for a `T` and returns a `*T` (a pointer). It works for any type. The result is a pointer to a zero value.
- `make(T, ...)` is **only** for slices, maps, and channels. It initializes their internal data structures (slice header + backing array, map hash table, channel buffer) and returns a `T` (not a pointer), ready to use.

```go
p := new(int)            // *int pointing to 0
s := make([]int, 0, 10)  // initialized slice, len 0 cap 10
m := make(map[string]int) // initialized, writable map
ch := make(chan int, 4)   // initialized buffered channel
```

`new([]int)` returns a `*[]int` pointing at a **nil** slice — almost never what you want, because writing to it would require dereferencing and the slice still needs `make`. The mnemonic: use `make` for the three built-in reference types, `new` (or just `&T{}`) for everything else. In practice idiomatic Go rarely uses `new`; composite literals like `&Config{}` are clearer.

#### Q60. [Practical] What's the difference between `nil` slice and an empty slice, and when does it matter?

A `nil` slice (`var s []int`) has a nil backing-array pointer, length 0, and capacity 0. An empty slice (`s := []int{}` or `make([]int, 0)`) has a non-nil pointer to a zero-length array.

```go
var a []int        // nil slice:   a == nil is true
b := []int{}       // empty slice: b == nil is false
fmt.Println(len(a), len(b)) // 0 0 — both behave identically for len/range/append
```

For almost everything they behave the same: `len`, `cap`, `range`, and `append` all work on a nil slice. The difference matters in two places:

- **Equality to nil**: `a == nil` is true for the nil slice, false for the empty one. Don't use this to test emptiness — always use `len(s) == 0`.
- **JSON marshaling**: a nil slice marshals to `null`, an empty slice marshals to `[]`. This is a frequent API-contract surprise — if clients expect `[]`, initialize with `make([]T, 0)` or `[]T{}`.

Idiomatically, prefer returning a nil slice for "no results" and rely on `len`; only force an empty slice when the serialization distinction matters.

#### Q61. [Theory] How does `iota` work in const blocks, and what are common patterns?

`iota` is a per-`const`-block counter that starts at 0 and increments by one for each `ConstSpec` (line) in the block. It resets to 0 at the start of each new `const` block.

```go
const (
    A = iota // 0
    B        // 1 (expression repeated implicitly)
    C        // 2
)

// Bit flags via shifting
const (
    Read    = 1 << iota // 1  (iota=0)
    Write               // 2  (iota=1)
    Execute             // 4  (iota=2)
)

// Skip values with the blank identifier, and scale units
const (
    _  = iota             // ignore 0
    KB = 1 << (10 * iota) // 1<<10
    MB                    // 1<<20
    GB                    // 1<<30
)
```

`iota` increments once per line even if it isn't used on that line, which is why the "repeat the previous expression" rule lets `B` and `C` continue the sequence. It is the idiomatic way to define enumerations and bit-flag sets. A common refinement is to define a named type (`type Weekday int`) and a `String()` method (often generated by `stringer`) so the enum prints meaningfully.

#### Q62. [Practical] What does the blank identifier `_` do, and what are its idiomatic uses?

The blank identifier `_` is a write-only placeholder that discards a value. It satisfies the rule that every assigned/declared value must be used, without keeping the value around.

```go
_, err := fmt.Println("hi")      // discard the byte count
for _, v := range items { ... }  // ignore the index
x, _ := strconv.Atoi(s)          // ignore the error (do this carefully!)

import _ "github.com/lib/pq"     // import for side effects (registers a driver)

var _ io.Reader = (*MyType)(nil) // compile-time interface satisfaction check
```

Idiomatic uses: ignoring unwanted multiple-return values, ignoring loop indices/keys, **blank imports** that run a package's `init` for registration side effects (database drivers, image format decoders, `net/http/pprof`), and the **interface assertion idiom** `var _ Interface = (*T)(nil)` that fails compilation if `*T` stops satisfying the interface. Discarding errors with `_` is a code smell unless you genuinely cannot act on them — linters flag it.

#### Q63. [Theory] How does Go's `switch` differ from C's, including fallthrough and expressionless switch?

Go's `switch` has three differences from C that shape idiomatic code:

- **No implicit fallthrough**: each case breaks automatically. You opt into falling into the next case with an explicit `fallthrough` statement (which is rare).
- **Expressionless switch**: `switch { case cond: }` with no tag acts as a cleaner `if/else if` chain — each case is a boolean condition.
- **Multiple values per case** and any comparable type, not just integers.

```go
switch {
case score >= 90:
    grade = "A"
case score >= 80:
    grade = "B"
default:
    grade = "F"
}

switch day {
case "Sat", "Sun":      // multiple values
    return "weekend"
case "Mon":
    fallthrough          // explicitly continue into the next case
case "Tue":
    return "early week"
}
```

There's also the initialized form `switch x := f(); x { ... }` scoping `x` to the switch, and the **type switch** (`switch v.(type)`) for branching on dynamic type. Because fallthrough is opt-in, Go avoids the classic C bug of forgetting a `break`.

### 🟡 — extended

#### Q64. [Theory] What is the internal structure of a Go map, and how are collisions and growth handled?

A Go map is a hash table implemented as an array of **buckets** (`bmap`), each holding up to 8 key/value pairs plus the top 8 bits of each key's hash (`tophash`) for fast probing. The `hmap` header stores the bucket array pointer, the count, and a `B` value (there are `2^B` buckets).

- **Lookup**: hash the key, use the low `B` bits to pick a bucket, then linearly scan the bucket's 8 slots comparing `tophash` bytes first (cheap) before full key comparison. Collisions within a bucket are handled by these 8 slots; if a bucket fills, an **overflow bucket** is chained.
- **Growth**: when the load factor exceeds ~6.5 entries/bucket (or there are too many overflow buckets), the map doubles its bucket count and **incrementally** rehashes — each subsequent write migrates a couple of old buckets (`evacuate`) so there's no single giant stall. During growth, lookups check both old and new bucket arrays.
- **Randomized iteration**: iteration starts at a random bucket and offset, deliberately preventing code from depending on order.

Keys must be comparable (`==`). Map values are not addressable because growth can move them. Since Go 1.24 the runtime map implementation was replaced with a **Swiss Tables** design (from the dlang/Abseil lineage) using SIMD-friendly control bytes and better cache behavior, improving performance while keeping the same semantics.

#### Q65. [Theory] Explain how Go's type assertion and interface dispatch work at the machine level.

An interface value is two words: an `itab` pointer (or `*_type` for the empty interface) and a data pointer.

- For a **non-empty interface** (`io.Reader`), the first word is an `*itab` — a small structure that caches the concrete type's `*_type` plus a method table: an array of function pointers for exactly the interface's methods, in interface-declaration order. A **method call** through the interface (`r.Read(p)`) loads the function pointer from a fixed offset in the itab and calls it — one indirect call, no search.
- For the **empty interface** (`any`), the first word is just the concrete `*_type`; there are no methods to dispatch.
- A **type assertion** `v.(T)` compares the interface's stored type descriptor against `T`'s descriptor (a pointer/identity comparison for concrete `T`, or an itab lookup for interface `T`). The comma-ok form returns the result of that comparison instead of panicking.

```go
var r io.Reader = os.Stdin
n, _ := r.Read(buf) // indirect call via itab's Read slot
f, ok := r.(*os.File) // type assertion: compare itab._type to *os.File
```

itabs are generated lazily and **cached** in a global hash table keyed by (interface type, concrete type), so the first assertion/conversion for a pair builds the itab and subsequent ones are fast. This is why interface dispatch is cheap but not free — there's a pointer indirection compared to a static call, which is why the compiler inlines and devirtualizes when it can prove the concrete type.

#### Q66. [Coding] Implement a generic `Map`, `Filter`, and `Reduce` and discuss why Go's stdlib took so long to add them.

```go
func Map[T, U any](in []T, f func(T) U) []U {
    out := make([]U, len(in))
    for i, v := range in {
        out[i] = f(v)
    }
    return out
}

func Filter[T any](in []T, keep func(T) bool) []T {
    out := in[:0:0] // new zero-len slice, don't alias input
    for _, v := range in {
        if keep(v) {
            out = append(out, v)
        }
    }
    return out
}

func Reduce[T, U any](in []T, init U, f func(U, T) U) U {
    acc := init
    for _, v := range in {
        acc = f(acc, v)
    }
    return acc
}

// usage
nums := []int{1, 2, 3, 4}
evens := Filter(nums, func(n int) bool { return n%2 == 0 }) // [2 4]
doubled := Map(nums, func(n int) int { return n * 2 })       // [2 4 6 8]
sum := Reduce(nums, 0, func(a, n int) int { return a + n })  // 10
```

Go resisted these for years because the design philosophy favors explicit loops over hidden allocation and indirection, and because pre-generics versions would have needed `interface{}` and reflection (slow, untyped). With generics (1.18) the stdlib added the `slices` and `maps` packages (1.21) with `slices.Sort`, `slices.Index`, `slices.Contains`, `maps.Keys`, etc., and Go 1.23 added **range-over-function iterators** (`iter.Seq`), enabling lazy `Map`/`Filter` pipelines via `slices.Values`/`slices.Collect` without materializing intermediate slices. Idiomatic Go still often prefers a plain `for` loop when it's clearer than a functional chain.

#### Q67. [Theory] How do closures capture variables, and what are the allocation consequences?

A closure captures variables **by reference**, not by value — it closes over the variable itself, so it sees later mutations and can modify the variable. When a captured variable's lifetime can exceed the enclosing function (because the closure escapes), the compiler moves that variable to the heap.

```go
func counter() func() int {
    n := 0                  // n escapes to the heap — the returned closure outlives counter()
    return func() int {
        n++                 // mutates the captured variable
        return n
    }
}
c := counter()
c(); c() // returns 1, then 2 — same n
```

Each captured variable that escapes becomes a heap allocation; the closure itself is a small struct (a function pointer plus pointers to the captured variables). Implications:

- Capturing a large variable or many variables in a hot-path closure adds GC pressure — check with `-gcflags=-m`.
- The pre-Go-1.22 loop-variable bug stemmed directly from by-reference capture: all closures in a loop shared one variable. Go 1.22 made each iteration's loop variable distinct, fixing the most common manifestation.
- If a closure does *not* escape (used and discarded within the function), escape analysis can keep its captures on the stack — so not every closure allocates.

#### Q68. [Practical] How does `defer` perform in modern Go, and when should you avoid it?

`defer` used to carry a real cost (each deferred call allocated a `_defer` record on the heap). Since **Go 1.14**, the compiler implements **open-coded defers**: for the common case (a small, statically known number of defers not inside a loop), the deferred calls are inlined directly into the function exit path with almost zero overhead — comparable to a manual call. A bitmask tracks which defers are active for the panic path.

```go
func f() {
    mu.Lock()
    defer mu.Unlock() // open-coded: nearly free in Go 1.14+
    // ...
}
```

When the optimization does **not** apply, defer falls back to the slower heap-allocated path:

- Defers **inside a loop** (the count isn't statically known) — a `defer` in a `for` loop also has a correctness trap: it accumulates and only runs at function return, so file handles can pile up. Extract the loop body into a function so each `defer` fires per iteration.
- More than 8 defers in a function, or defers in functions that also call `recover` in non-trivial ways.

Practical guidance: use `defer` freely for cleanup in normal functions — readability wins and the cost is negligible. Only consider removing it from extremely hot, tight functions after profiling shows the deferred-call path matters, and never put `defer` in a loop expecting per-iteration cleanup.

#### Q69. [Theory] What is the difference between `panic`/`recover` and how does `recover` actually work?

`panic` unwinds the goroutine's stack, running deferred functions along the way, until it either reaches the top (crashing the program with a stack trace) or a deferred function calls `recover`.

`recover` stops the unwinding **only when called directly inside a deferred function**. It returns the value passed to `panic` (or `nil` if there's no active panic), and execution resumes in the deferring function after the deferred call returns.

```go
func safeDiv(a, b int) (result int, err error) {
    defer func() {
        if r := recover(); r != nil {
            err = fmt.Errorf("recovered: %v", r) // convert panic to error
        }
    }()
    return a / b, nil // panics on b == 0
}
```

Key rules and subtleties:

- `recover` only works in a function invoked by `defer`; calling it directly (not in a deferred func) returns `nil` and does nothing.
- A panic in one goroutine **cannot** be recovered by another goroutine — each goroutine's stack unwinds independently, and an unrecovered panic in *any* goroutine crashes the whole process. So you must `recover` inside the goroutine that might panic (e.g. a `defer recover()` at the top of each worker).
- Recovering should be reserved for boundaries (a request handler, a worker loop, a plugin call) where you want to contain a crash; it is not a general error-handling mechanism.
- Re-panicking (`panic(r)` after inspecting) propagates the original; `runtime.Error` panics (nil deref, index out of range) can also be recovered but usually indicate bugs.

#### Q70. [Coding] Implement a middleware chain in Go using closures.

HTTP middleware is idiomatically a function that wraps an `http.Handler` and returns a new one — closures compose them.

```go
type Middleware func(http.Handler) http.Handler

func Logging(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        start := time.Now()
        next.ServeHTTP(w, r)
        log.Printf("%s %s %v", r.Method, r.URL.Path, time.Since(start))
    })
}

func Auth(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        if r.Header.Get("Authorization") == "" {
            http.Error(w, "unauthorized", http.StatusUnauthorized)
            return
        }
        next.ServeHTTP(w, r)
    })
}

// Chain applies middlewares so the first listed runs outermost.
func Chain(h http.Handler, mws ...Middleware) http.Handler {
    for i := len(mws) - 1; i >= 0; i-- {
        h = mws[i](h)
    }
    return h
}

// usage: Logging wraps Auth wraps the real handler
handler := Chain(finalHandler, Logging, Auth)
```

Iterating the middlewares in reverse so the first one in the slice ends up outermost is the conventional ordering. Each middleware is a closure capturing `next`, forming a linked call chain. This pattern underlies routers like chi and gorilla/mux, and the same closure-composition idea applies to gRPC interceptors and any decorator-style pipeline.

#### Q71. [Theory] How does Go's compilation and linking model work, and why are binaries statically linked?

Go uses its own toolchain (`gc` compiler + linker), not LLVM/GCC by default. The build flow:

- Each **package** compiles to an intermediate object; the compiler does escape analysis, inlining, and (since 1.20+) basic PGO-driven optimization. Compilation is fast partly because of the strict dependency model — a package's compiled export data summarizes its API so dependents don't reparse its source.
- The **linker** combines packages plus the runtime into a single executable. By default, pure-Go programs are **statically linked** — the runtime, GC, scheduler, and all dependencies are baked into one self-contained binary with no external `.so` dependencies. This is why a Go binary "just runs" when copied to another machine of the same OS/arch and why minimal/`scratch` Docker images work.
- **CGo changes this**: importing C code makes the binary dynamically link against libc (glibc), so it's no longer fully static unless you build against musl or pass linker flags. `CGO_ENABLED=0` forces pure-Go and a static binary (also switching to the pure-Go DNS resolver).

Cross-compilation is trivial: set `GOOS` and `GOARCH` (e.g. `GOOS=linux GOARCH=arm64 go build`) and no cross-toolchain is needed for pure-Go code, because the standard library is reimplemented per platform in Go/assembly. CGo breaks this simplicity by requiring a C cross-toolchain. The trade-offs of static linking are larger binaries and no shared-library security patching, accepted in exchange for deployment simplicity.

#### Q72. [Practical] How do build tags and conditional compilation work in Go?

Build constraints let you include or exclude files per platform, architecture, or custom condition. There are two mechanisms:

- **File-name suffixes**: a file named `cache_linux.go` compiles only on Linux; `cache_amd64.go` only on amd64; `cache_linux_arm64.go` only on that pair. The toolchain recognizes `_GOOS`, `_GOARCH`, and `_GOOS_GOARCH` suffixes automatically.
- **`//go:build` lines**: a constraint comment at the top of the file (before the package clause, followed by a blank line), using boolean expressions.

```go
//go:build linux && amd64

package mypkg
```

```go
//go:build integration

package mypkg // only built when you run: go test -tags=integration
```

The modern `//go:build` syntax (Go 1.17+) replaced the older `// +build` form and supports `&&`, `||`, `!`, and parentheses. Common uses: platform-specific implementations (with a fallback file constrained to the other platforms), opt-in integration/e2e tests behind a custom tag, excluding code from certain Go versions (`//go:build go1.21`), and `//go:build ignore` to exclude a file from normal builds (used for generator scripts run via `go run`). `go vet` validates that constraints are well-formed and that platform-specific files still parse.

### 🟠 — extended

#### Q73. [Theory] Explain Go's memory allocator (mcache/mcentral/mheap and size classes).

Go's allocator is a **thread-caching, size-class** allocator inspired by TCMalloc, designed so most allocations are lock-free.

- **Size classes**: small objects (≤32 KB) are rounded up to one of ~70 fixed **size classes** (8, 16, 24, 32, ... bytes). Objects of the same class come from **spans** — runs of pages dedicated to one size class — which minimizes fragmentation and lets the allocator find a free slot with a bitmap.
- **mcache** (per-P): each P has its own cache of spans, one per size class. Allocating a small object grabs a free slot from the P's mcache with **no lock** — this is the fast path and why allocation scales across cores.
- **mcentral** (per size class, shared): when a P's mcache runs out of a given class, it refills from the central list for that class, taking a lock briefly.
- **mheap** (global): manages the whole heap as pages, hands spans to mcentrals, and requests memory from the OS in large arenas. Large objects (>32 KB) are allocated straight from the mheap.

```
allocation: mcache (per-P, lock-free) ─miss→ mcentral (locked) ─miss→ mheap (global) ─miss→ OS
```

Tiny objects (<16 bytes, pointer-free, like small strings/ints) are **combined** into a single allocation slot to reduce overhead (the "tiny allocator"). This tiered design is why allocation is cheap and concurrent, and it ties into the GC: spans track which slots are free, and sweeping reclaims slots at span granularity. The **scavenger** returns unused pages to the OS over time (madvise), influenced by `GOMEMLIMIT`.

#### Q74. [Theory] How does the write barrier maintain the tri-color invariant, and what kind of barrier does Go use?

During concurrent marking, the mutator can move pointers around faster than the GC scans, risking a missed reachable object. The danger condition is: a **black** object (already scanned, won't be rescanned) ends up holding the only pointer to a **white** object (not yet marked), while the grey object that previously referenced that white object drops it. Without intervention the white object would be wrongly collected.

The **strong tri-color invariant** forbids any black→white pointer. Go enforces a weaker but sufficient guarantee using a **hybrid write barrier** (since Go 1.8) that combines Dijkstra-style (shade the new referent) and Yuasa-style (shade the old referent) barriers. On a pointer write `*slot = ptr`, the barrier shades both the **old** value being overwritten and the **new** value being stored, marking them grey:

```
// conceptually, on every heap pointer write during marking:
writePointer(slot, ptr):
    shade(*slot) // Yuasa: protect the overwritten pointer
    shade(ptr)   // Dijkstra: protect the newly installed pointer
    *slot = ptr
```

The payoff of the hybrid barrier is that Go no longer needs to **re-scan stacks** with the world stopped at the end of marking (stacks, once scanned and blackened, stay valid because the barrier catches relevant writes) — this is the key change that drove STW pauses down to sub-millisecond. The barrier only applies to heap pointer writes; stack writes are unbarriered, which is why stacks are scanned atomically. This cost (a few instructions per pointer write during a GC cycle) is the price of concurrent collection.

#### Q75. [Coding] Implement a bounded, deadline-aware retry with exponential backoff and jitter.

```go
func RetryWithBackoff(ctx context.Context, maxAttempts int, base time.Duration, op func() error) error {
    var lastErr error
    for attempt := 0; attempt < maxAttempts; attempt++ {
        if err := op(); err == nil {
            return nil
        } else {
            lastErr = err
            if !isRetryable(err) {
                return err // don't retry permanent failures
            }
        }
        if attempt == maxAttempts-1 {
            break // no sleep after the final attempt
        }
        // Exponential backoff with full jitter: random in [0, base*2^attempt].
        backoff := base * time.Duration(1<<attempt)
        sleep := time.Duration(rand.Int63n(int64(backoff)))

        select {
        case <-time.After(sleep):
        case <-ctx.Done():
            return fmt.Errorf("retry aborted: %w (last error: %v)", ctx.Err(), lastErr)
        }
    }
    return fmt.Errorf("exhausted %d attempts: %w", maxAttempts, lastErr)
}
```

Key design points an interviewer wants to hear: **jitter** prevents synchronized retry storms (the thundering herd) when many clients fail at once — "full jitter" (random in `[0, cap]`) is empirically among the best strategies; the **context** makes the whole retry loop cancelable and deadline-aware so you don't sleep past a caller's timeout; **retryability classification** avoids retrying permanent errors (4xx, validation) while retrying transient ones (timeouts, 503); and you should cap the maximum backoff to avoid unbounded waits. Production systems often add a **circuit breaker** in front to stop hammering a downstream that is clearly down.

#### Q76. [Theory] What is `GODEBUG` and which settings matter most for diagnosing runtime behavior?

`GODEBUG` is an environment variable of `name=value` pairs that toggles runtime diagnostics and (since Go 1.21) controls backward-compatibility behavior of language/runtime changes. It requires no recompilation.

Diagnostic settings worth knowing:

- `gctrace=1` — prints a line per GC cycle: heap sizes, STW pause durations, wall/CPU time, and mark-assist. The first tool for GC tuning.
- `schedtrace=1000` (+ `scheddetail=1`) — emits scheduler state every 1000 ms: number of Ps/Ms, run-queue lengths, idle threads. Useful for diagnosing scheduling starvation.
- `madvdontneed=1`, `scavtrace=1` — observe how memory is returned to the OS.
- `inittrace=1` — prints timing of each package's `init`, to find slow startup.
- `allocfreetrace=1` — extremely verbose per-allocation trace (debugging only).
- `cgocheck=2` — stricter checking of the cgo pointer-passing rules.

Since Go 1.21, `GODEBUG` also gates **compatibility**: when the toolchain changes a default behavior (e.g. `http2server`, `panicnil`, `randautoseed`, `tlsmaxrsasize`), the old behavior remains reachable via a `GODEBUG` setting whose default is keyed to the `go` directive version in `go.mod`. This is the GODEBUG "compatibility" mechanism that lets you upgrade Go without surprise behavior changes — set the relevant `GODEBUG` to opt back into old behavior while you migrate, and `runtime/metrics` exposes `/godebug/non-default-behavior/*` counters so you can see if you depend on a deprecated default.

#### Q77. [Theory] How do you reason about and prevent deadlocks in Go, and how does the runtime detect them?

A deadlock is a set of goroutines each waiting on the others, making no progress. Common Go-specific shapes:

- **All goroutines blocked on channels**: every goroutine is waiting to send/receive and none can proceed. The Go runtime detects the **global** case — when *all* goroutines are asleep — and panics with `fatal error: all goroutines are asleep - deadlock!`. Crucially it does **not** detect partial deadlocks where some goroutines still run.
- **Lock ordering cycles**: goroutine A locks `mu1` then `mu2`; goroutine B locks `mu2` then `mu1`. The classic fix is a **global lock ordering** — always acquire locks in a consistent order.
- **Self-deadlock**: locking a non-reentrant `sync.Mutex` twice in the same goroutine (Go mutexes are not reentrant by design).
- **Channel + lock interplay**: holding a lock while sending on a channel that the receiver needs the same lock to drain.

```go
// Lock-ordering fix: always lock accounts in a canonical order (e.g. by ID).
func transfer(a, b *Account, amt int) {
    first, second := a, b
    if a.id > b.id {
        first, second = b, a // consistent ordering breaks the cycle
    }
    first.mu.Lock(); defer first.mu.Unlock()
    second.mu.Lock(); defer second.mu.Unlock()
    // ...
}
```

Prevention strategies: enforce lock ordering, minimize lock scope, prefer `select` with a `ctx.Done()`/timeout branch so a blocked channel op can bail, avoid holding locks across channel ops or blocking calls, and use the race detector and `go.uber.org/goleak`. The deadlock detector is a backstop, not a guarantee — partial deadlocks need profiling (a goroutine dump via `SIGQUIT` or pprof shows what every goroutine is blocked on).

#### Q78. [Practical] How does `go test` work under the hood, and what features (subtests, benchmarks, fuzzing, parallel) matter?

`go test` compiles the package together with its `_test.go` files into a temporary test binary, runs it, and reports results. Files ending `_test.go` are excluded from normal builds; `TestXxx(*testing.T)`, `BenchmarkXxx(*testing.B)`, `ExampleXxx`, and `FuzzXxx(*testing.F)` functions are discovered by signature.

Key features:

- **Subtests** (`t.Run(name, func)`) — table-driven tests with isolated names, individually runnable via `-run TestX/case_name`, with their own setup/teardown.
- **Parallel tests** (`t.Parallel()`) — marks a test to run concurrently with other parallel tests; the runner pauses it until all serial tests in the package finish, then runs the parallel set together. Watch the loop-variable trap in pre-1.22 table tests with `t.Parallel()`.
- **Benchmarks** — run the body `b.N` times, auto-scaling `b.N` until timing is statistically stable; use `b.ResetTimer`, `b.ReportAllocs`/`-benchmem` for allocations, and `b.RunParallel` for contention benchmarks. Go 1.24 added `b.Loop()` as a more accurate, less foot-gun-prone iteration mechanism that prevents the compiler from optimizing the body away.
- **Fuzzing** (`f.Fuzz`, Go 1.18+) — coverage-guided fuzzing that mutates a seed corpus to find crashing/inconsistent inputs; run with `go test -fuzz=FuzzX`.
- **Examples** — `Example` functions with `// Output:` comments double as compile-checked, runnable documentation.

Other essentials: `t.Cleanup` for deferred teardown, `t.Helper()` to fix line reporting in assertion helpers, `t.TempDir`, `testing.Short()` gated by `-short`, and `TestMain(m *testing.M)` for package-level setup. Coverage via `-cover`/`-coverprofile`, and the `-count=1` trick to defeat the test cache.

#### Q79. [Coding] Implement a generic, type-safe LRU cache.

```go
type entry[K comparable, V any] struct {
    key   K
    value V
}

type LRU[K comparable, V any] struct {
    mu       sync.Mutex
    capacity int
    ll       *list.List          // most-recent at front
    items    map[K]*list.Element // key → list node
}

func NewLRU[K comparable, V any](capacity int) *LRU[K, V] {
    return &LRU[K, V]{
        capacity: capacity,
        ll:       list.New(),
        items:    make(map[K]*list.Element, capacity),
    }
}

func (c *LRU[K, V]) Get(key K) (V, bool) {
    c.mu.Lock()
    defer c.mu.Unlock()
    if el, ok := c.items[key]; ok {
        c.ll.MoveToFront(el)             // mark as recently used
        return el.Value.(*entry[K, V]).value, true
    }
    var zero V
    return zero, false
}

func (c *LRU[K, V]) Put(key K, value V) {
    c.mu.Lock()
    defer c.mu.Unlock()
    if el, ok := c.items[key]; ok {
        c.ll.MoveToFront(el)
        el.Value.(*entry[K, V]).value = value
        return
    }
    el := c.ll.PushFront(&entry[K, V]{key, value})
    c.items[key] = el
    if c.ll.Len() > c.capacity {         // evict least-recently used
        oldest := c.ll.Back()
        if oldest != nil {
            c.ll.Remove(oldest)
            delete(c.items, oldest.Value.(*entry[K, V]).key)
        }
    }
}
```

The design pairs a hash map (O(1) lookup) with a doubly linked list (O(1) move-to-front and eviction-from-back), the canonical LRU structure. The generic type parameters `[K comparable, V any]` give compile-time type safety with no `interface{}` boxing on the API surface (the `list.Element.Value` is still `any` because `container/list` predates generics — a fully generic list would remove even that cast). The mutex makes it concurrency-safe; for higher throughput you'd shard by key hash to reduce lock contention, or use a library like `hashicorp/golang-lru`.

### 🔴 — extended

#### Q80. [Theory] Explain the formal Go memory model's treatment of atomics, mutexes, and the "happens-before" partial order in depth.

The Go memory model (significantly clarified in the Go 1.19 documentation) defines program behavior in terms of a partial order called **happens-before** over memory operations, plus the rule that a read `r` of a variable is allowed to observe a write `w` if `w` happens-before `r` and no other write happens-between them; otherwise the program has a **data race** and behavior is undefined for that access (Go limits the damage — no arbitrary memory corruption like C — but the value read is unspecified).

The synchronization primitives establish happens-before edges:

- **Channels** (the primary mechanism): a send happens-before the completion of the corresponding receive; the closing of a channel happens-before a receive that returns zero due to close; for an **unbuffered** channel, a receive happens-before the *completion of the send* (note the direction — this is what makes unbuffered channels a two-way handshake). For a buffered channel of capacity C, the *k*th receive happens-before the (*k*+C)th send completes.
- **Mutexes**: for `sync.Mutex`/`RWMutex`, the *n*th `Unlock` happens-before the (*n*+1)th `Lock` returns. For `RWMutex`, an `Unlock` happens-before any later `RLock` that follows it, and the matching `RUnlock` happens-before a subsequent `Lock`.
- **Atomics** (formalized in 1.19): atomic operations execute as if in a single **total order consistent with sequential consistency**, and a read that observes a write establishes happens-before from that write. Go offers only the SC model — no `memory_order_relaxed`/`acquire`/`release` knobs like C++ — a deliberate simplification.
- **Once, WaitGroup, etc.**: `once.Do(f)` — `f`'s return happens-before any `Do` call returns; `wg.Wait` returns after the happens-before edges from the matching `Done` calls.

The practical staff-level point: happens-before is a **partial** order, so without an explicit synchronizing edge between two goroutines' operations they are unordered and racy. You cannot rely on "obvious" timing, instruction order, or cache behavior — only the edges the model guarantees. Compiler and CPU reorderings are legal as long as they respect this order within a goroutine and the established cross-goroutine edges.

#### Q81. [Theory] How does the Go runtime implement goroutine preemption at safe points, and what are async preemption's limits?

Goroutine state must be captured precisely for the GC (which needs exact pointer maps) and for stack copying. Preemption can only happen at points where the runtime has accurate metadata:

- **Cooperative / synchronous preemption** happens at **safe points** the compiler emits: function prologues carry a stack-bound check (`morestack`) that doubles as a preemption check (the runtime sets the goroutine's stack guard to a poison value to force entry into the runtime), plus explicit checks at loop back-edges in some cases and at blocking operations.
- **Asynchronous preemption** (Go 1.14) handles loops with no calls. `sysmon` sends `SIGURG` to the target M; the signal handler runs in the context of the interrupted goroutine and checks whether the interrupted PC is at an **async-safe point** — a place where the compiler-generated **pointer maps and register maps** are valid for that exact instruction. The compiler emits this metadata for nearly all instructions, so most code is asynchronously preemptible. If the PC is *not* at a safe point (e.g. mid-way through writing a multi-word value, or in code lacking maps), the preemption is deferred and retried.

```
sysmon detects G ran >10ms ─► signal M with SIGURG
signal handler: is interrupted PC at an async-safe point (valid stack/reg maps)?
  yes ─► snapshot G, reschedule
  no  ─► leave a flag, retry later
```

Limits and exceptions: regions explicitly marked non-preemptible (inside the runtime, while holding certain runtime locks, during `//go:nosplit` functions, or in hand-written assembly without the right annotations) are not async-preemptible. Code that disables preemption can still delay GC's stop-the-world. This machinery is why Go 1.14 eliminated the old pathology where a tight `for {}` loop could hang GC and starve other goroutines indefinitely.

#### Q82. [Coding] Implement a goroutine-safe, generic, fan-in merge of N channels with cancellation.

```go
func Merge[T any](ctx context.Context, chans ...<-chan T) <-chan T {
    out := make(chan T)
    var wg sync.WaitGroup
    wg.Add(len(chans))

    for _, ch := range chans {
        go func(c <-chan T) {
            defer wg.Done()
            for {
                select {
                case v, ok := <-c:
                    if !ok {
                        return // this input is drained
                    }
                    // Forward, but stay cancelable so we never block forever.
                    select {
                    case out <- v:
                    case <-ctx.Done():
                        return
                    }
                case <-ctx.Done():
                    return
                }
            }
        }(ch)
    }

    // Close out exactly when every forwarder has exited (drain or cancel).
    go func() {
        wg.Wait()
        close(out)
    }()
    return out
}
```

This is the canonical fan-in, hardened for production: each input gets a forwarder goroutine; the **nested select on the send side** (`out <- v` vs `ctx.Done()`) is the crucial detail that prevents a goroutine leak when the consumer stops reading early — without it, a forwarder could block forever on `out <- v`. The `WaitGroup`-then-`close` closer goroutine guarantees `out` is closed exactly once, after all forwarders finish, so the consumer's `range` terminates cleanly. Because it's generic (`[T any]`), it works for any element type with no boxing. A staff-level addition: if inputs can be added dynamically you'd need a recursive/tree merge or a coordinating goroutine, since the channel set here is fixed at call time.

#### Q83. [Theory] What are the trade-offs and failure modes of `sync.Pool` under GC pressure and across Ps, and how is it implemented?

`sync.Pool` is a per-P free list designed to reduce allocation of transient objects, but its semantics surprise people because it's a **cache, not a pool**.

Implementation:

- Each P has a **private** slot (a single object, accessed with no synchronization) plus a **shared** lock-free deque. `Get` tries the P-local private slot, then the P-local shared deque, then **steals** from other Ps' shared deques; `Put` fills the private slot or pushes to the shared deque. This per-P structure is why it scales — the common path is lock-free and cache-local.
- A `victim cache` (added in Go 1.13) holds the previous GC cycle's pooled objects: at the start of each GC, the current pool is moved to the victim, and the victim from two cycles ago is dropped. This keeps objects alive for **one extra** GC cycle, smoothing the cliff where a GC used to empty the pool entirely.

Trade-offs and failure modes:

- **GC clears it**: pooled objects are reclaimable by the GC (after the victim grace period), so you cannot use `sync.Pool` to bound a resource like DB connections or file descriptors — those need a real pool (e.g. `database/sql`'s pool). Pool is only for memory you'd otherwise re-allocate.
- **Must reset state**: objects come back with whatever data the previous user left; failing to `Reset()` leaks data between requests (a security bug) — and over-sized buffers can balloon memory if you pool growable objects without bounding their size on `Put`.
- **No size control**: you can't cap the pool; under bursty load it can hold a lot of memory until the next GC.
- **Pin to P, not goroutine**: an object got on one P can be used on another after a goroutine migrates — fine for memory, but means there's no thread-affinity guarantee.

The expert summary: use it for short-lived, frequently allocated, **resettable** value buffers in hot paths (serialization scratch, `bytes.Buffer`, per-request structs), measure the allocation reduction with `-benchmem`, and never treat it as a lifetime-managed resource pool.

#### Q84. [Theory] How would you architect a low-latency Go service to minimize GC impact and tail latency, end to end?

A staff-level answer combines allocation discipline, runtime tuning, scheduling awareness, and observability — and is explicit about trade-offs.

**Allocation reduction (the primary lever)** — GC work is proportional to allocation rate and live heap, so cut both:

- Pool resettable buffers with `sync.Pool`; preallocate slices/maps with capacity hints; reuse request-scoped structs.
- Keep values on the stack — audit with `-gcflags=-m`; avoid `any`/`interface{}` boxing and reflection on hot paths; pass slices in batches rather than element-by-element through channels.
- Prefer value types and arrays of structs (SoA/AoS layout for cache locality) over pointer-heavy graphs, which also shrinks the pointer set the GC must scan.

**Runtime tuning** — set `GOMEMLIMIT` to a soft cap (especially in containers) so the GC has a hard ceiling, and tune `GOGC` (raise it for fewer, larger GCs when you have memory headroom, or set it lower / rely on `GOMEMLIMIT`). Set `GOMAXPROCS` to the cgroup CPU *limit* (use `automaxprocs`) so the scheduler isn't fooled by host core count, which otherwise causes throttling-induced tail spikes.

**Scheduling and contention** — shard hot locks/channels; replace read-mostly state with `atomic.Pointer` copy-on-write; bound concurrency with worker pools/semaphores so you don't oversubscribe downstreams; avoid non-preemptible hot loops; pin latency-critical work away from `init`-heavy startup.

**Tail-latency tactics** — per-call `context` deadlines; hedged/backup requests for fan-out where p99 = slowest-of-N; circuit breakers and load shedding; the pure-Go DNS resolver to avoid cgo stalls; and a netpoller-friendly I/O design.

**Observability and validation** — continuous profiling (pprof, `GODEBUG=gctrace=1`), the execution tracer for scheduler/GC attribution, `runtime/metrics` for GC-assist and pause metrics, and load tests that measure p99/p999 under representative traffic. The throughline: **measure, attribute the stall (GC vs scheduler vs contention vs downstream), apply the matching fix, and re-measure** — and accept the trade-offs (more memory for less GC, more code complexity for pooling) only where the profiler justifies them.

#### Q85. [Behavioral] Describe a time you had to push back on a premature optimization or an over-engineered Go design.

(Use situation, the tension, your reasoning, and the outcome — show judgment, not just opinion.)

A strong narrative: "A teammate proposed rewriting our request-handling hot path with lock-free `atomic` CAS loops and `sync.Pool` everywhere, arguing it would cut latency. I shared the goal but pushed back on doing it blind. First I asked for the data: a profile showed our p99 was dominated by a downstream database call and a contended `RWMutex` around a config map — *not* allocation. So the proposed CAS rewrite would have added significant complexity and a class of subtle concurrency bugs (CAS loops and pooling are notoriously easy to get wrong) while addressing maybe 5% of the latency. I proposed a smaller, targeted change instead: replace the `RWMutex` config with an `atomic.Pointer` copy-on-write (which *was* justified by the profile and is a well-understood pattern), add per-call context deadlines to the DB call, and pool only the one serialization buffer that profiling flagged. We measured a real p99 improvement, and we avoided shipping a hard-to-maintain lock-free data structure. I framed it not as 'no optimization' but as 'optimize what the profiler proves matters,' and I wrote up the before/after profiles so the decision was evidence-based and repeatable."

What interviewers assess: you value simplicity and maintainability, you insist on profiling before optimizing, you can tell the difference between a justified low-level optimization and cargo-culting, you handle disagreement constructively (acknowledging the goal, redirecting the method), and you close the loop with measurements and documentation. Citing Knuth's "premature optimization is the root of all evil" is fine, but demonstrating the *measure-first* discipline concretely is what lands.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q86. [Practical] Your program prints `fatal error: all goroutines are asleep - deadlock!` on startup. How do you diagnose it?

This fatal error means the runtime detected that **every** goroutine is blocked — no goroutine can make progress, so the program can never continue. It is not a panic you can `recover`; the runtime aborts.

The first move is to read the goroutine dump the runtime prints below the message — it lists each goroutine and exactly where it is blocked (`chan send`, `chan receive`, `sync.WaitGroup.Wait`, etc.). The most common startup causes:

- **Sending on an unbuffered channel with no receiver** (or vice versa) on the main goroutine, so `main` blocks and nothing else runs.
- **`wg.Wait()` with a counter that never reaches zero** because `wg.Add` was miscounted or a `Done` is missing.
- **A `range` over a channel that is never closed.**

```go
func main() {
    ch := make(chan int) // unbuffered
    ch <- 1              // blocks forever: no receiver → deadlock
    fmt.Println(<-ch)
}
```

The fix here is either a buffered channel, or doing the send/receive in separate goroutines. The diagnostic discipline: read the stack trace, find the blocked operation, and ask "who was supposed to be on the other end?" Note the detector only fires when *all* goroutines are asleep — a partial deadlock (some goroutines still running) won't trigger it and needs a goroutine profile (`SIGQUIT` or pprof) instead.

#### Q87. [Coding] A loop launches goroutines but the program exits before they run. What's wrong and how do you fix it?

When `main` returns, the program exits immediately and does not wait for other goroutines. Launched goroutines that haven't been scheduled simply never run.

```go
// Broken: main returns before goroutines execute
func main() {
    for i := 0; i < 5; i++ {
        go fmt.Println(i)
    }
    // main returns here → program exits, goroutines may never print
}
```

The fix is to synchronize on completion with a `sync.WaitGroup`:

```go
func main() {
    var wg sync.WaitGroup
    for i := 0; i < 5; i++ {
        wg.Add(1)
        go func(n int) {
            defer wg.Done()
            fmt.Println(n)
        }(i)
    }
    wg.Wait() // block until all goroutines finish
}
```

The lesson: launching a goroutine gives no completion guarantee — `go f()` is fire-and-forget. You must explicitly wait (WaitGroup, channel, or `errgroup`). Using `time.Sleep` to "wait" is a common anti-pattern: it's racy and either too short (misses work) or too long (wastes time). In Go 1.22+ the `func(n int)` parameter is technically unnecessary for correctness since each iteration gets its own `i`, but passing the value is still the clearest, version-proof style.

#### Q88. [Practical] Your JSON unmarshaling silently leaves struct fields empty. What are the usual causes?

`encoding/json` only sets fields it can see and match, so "empty fields after unmarshal" almost always comes from visibility or name mismatches:

- **Unexported fields**: `json` uses reflection and can only set **exported** (capitalized) fields. A lowercase `name` field is invisible to the decoder.
- **Name mismatch without a tag**: matching is case-insensitive but the JSON key must otherwise correspond. `{"user_name": ...}` won't fill a field named `UserName` unless you add a tag: `` `json:"user_name"` ``.
- **Decoding into a non-pointer**: `json.Unmarshal(data, v)` needs `v` to be a pointer; passing a value can't mutate the original.

```go
type User struct {
    Name  string `json:"name"`
    Email string `json:"email"`
    age   int    // unexported → NEVER populated by json
}

var u User
json.Unmarshal([]byte(`{"name":"Ada","email":"a@x.io","age":40}`), &u)
// u.Name="Ada", u.Email="a@x.io", u.age=0 (age stays zero)
```

To debug, use `json.NewDecoder(r)` with `dec.DisallowUnknownFields()` to surface keys that don't map to any field, and double-check tag spelling. For numbers landing in `any`, remember JSON numbers decode to `float64`, which surprises people expecting `int`.

#### Q89. [Coding] How do you set a timeout on an HTTP client request, and why is `http.DefaultClient` dangerous in production?

`http.DefaultClient` has **no timeout** — a slow or hung server can block your goroutine indefinitely, leaking goroutines and connections. Always set timeouts.

```go
// Per-request timeout via context (preferred — cancels the whole request):
ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
defer cancel()

req, _ := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
resp, err := http.DefaultClient.Do(req)
if err != nil {
    return fmt.Errorf("request failed: %w", err) // includes context deadline
}
defer resp.Body.Close()
```

Or configure a client with an overall timeout:

```go
client := &http.Client{
    Timeout: 10 * time.Second, // covers dial + redirects + reading the body
}
```

Two production must-dos beyond timeouts: **always `defer resp.Body.Close()`** (otherwise you leak the connection and prevent keep-alive reuse), and **drain the body** (`io.Copy(io.Discard, resp.Body)`) before closing if you won't read it fully, so the connection can be reused. For fine control, set a custom `http.Transport` with `DialContext` timeouts, `TLSHandshakeTimeout`, `ResponseHeaderTimeout`, and `IdleConnTimeout`. The `Client.Timeout` is a blunt overall cap; context deadlines are better because they propagate cancellation into the rest of your call tree.

#### Q90. [Practical] You see "too many open files" errors under load. What's the likely cause in Go and how do you fix it?

This OS error (`EMFILE`) means the process exceeded its file-descriptor limit. In Go services the usual culprit is **leaked connections or files** — almost always an unclosed `resp.Body`, `*os.File`, or `*sql.Rows`.

The biggest offender is forgetting `resp.Body.Close()` on HTTP responses: each unclosed body holds a connection (an fd) open, and under load they pile up until you hit the limit.

```go
resp, err := client.Get(url)
if err != nil { return err }
defer resp.Body.Close()              // REQUIRED: releases the fd/connection
io.Copy(io.Discard, resp.Body)       // drain so the connection can be reused
```

Diagnosis steps: check `lsof -p <pid>` or `/proc/<pid>/fd` to see what fds are open (lots of sockets to the same host points to leaked HTTP bodies); take a goroutine profile to find goroutines stuck on I/O; and grep for response/file/rows handling missing a `Close`. Fixes: ensure every `Close()` is deferred, set a bounded `http.Transport.MaxIdleConnsPerHost`, use a connection pool with limits for DBs (`db.SetMaxOpenConns`), and as a stopgap raise the ulimit (`ulimit -n`). The root cause is almost always a missing `Close`, not a too-low limit.

#### Q91. [Coding] How do you correctly read a file line by line, including very long lines?

Use `bufio.Scanner` for the common case, but know its default token-size limit (64 KB per line), which causes a silent `bufio.ErrTooLong` on long lines if you don't raise the buffer.

```go
f, err := os.Open("big.log")
if err != nil {
    return err
}
defer f.Close()

scanner := bufio.NewScanner(f)
// Raise the max token size for long lines (default is 64KB).
buf := make([]byte, 0, 64*1024)
scanner.Buffer(buf, 1024*1024) // allow lines up to 1MB

for scanner.Scan() {
    line := scanner.Text()
    process(line)
}
if err := scanner.Err(); err != nil { // MUST check — Scan() returning false hides errors
    return fmt.Errorf("scan: %w", err)
}
```

Critical gotchas: **always check `scanner.Err()`** after the loop, because `Scan()` returns `false` both at EOF and on error — without the check you silently swallow read errors. For truly unbounded lines, use `bufio.NewReader(f).ReadString('\n')` in a loop instead, which has no fixed cap. `scanner.Text()` allocates a string per line; use `scanner.Bytes()` (valid only until the next `Scan`) to avoid the allocation in hot paths.

#### Q92. [Practical] A teammate's code does `if err != nil { return err }` everywhere but errors lack context. How do you improve it?

Returning the bare `err` loses the call path, so a deep failure surfaces as a cryptic message (`open: no such file`) with no indication of *what operation* failed. Wrap errors with context using `%w`:

```go
// Before: opaque
if err != nil {
    return err
}

// After: each layer adds context, chain preserved for errors.Is/As
if err != nil {
    return fmt.Errorf("loading user %d config: %w", userID, err)
}
```

Guidelines for good error context:

- **Add what the caller can't already know** — the operation, key identifiers (IDs, paths) — but don't repeat what the wrapped error already says.
- **Use `%w` to wrap** so `errors.Is`/`errors.As` still work up the chain; use `%v` only when you intend to *hide* the underlying error.
- **Don't double-log** — log the error once at the top boundary, not at every layer, to avoid duplicate noise.
- **Start with lowercase, no trailing punctuation** (Go convention), since errors get composed: `"loading config: open file: ..."` reads cleanly.

The result is an error like `"handling request: loading user 42 config: open /etc/app.yaml: permission denied"` — every layer's context is preserved and the original cause is still inspectable. Tools like `pkg/errors` (now largely superseded by stdlib `%w`) added stack traces; for production you often wrap with structured fields via `slog` at the logging boundary.

#### Q93. [Coding] Show how to use `time.Ticker` correctly and the resource leak people hit with `time.Tick`.

`time.Tick` returns a channel but gives you **no way to stop it**, so the underlying ticker (and its goroutine/timer) leaks for the life of the program. Use `time.NewTicker` and `defer ticker.Stop()` instead.

```go
// Leak: the ticker can never be stopped or garbage-collected
for range time.Tick(time.Second) { ... } // OK only for the whole-program lifetime

// Correct: stoppable
ticker := time.NewTicker(time.Second)
defer ticker.Stop() // releases the ticker

for {
    select {
    case <-ticker.C:
        doPeriodicWork()
    case <-ctx.Done():
        return // ticker.Stop() runs via defer
    }
}
```

`time.Tick` is acceptable only in `main` or for tickers that genuinely live as long as the program (where the leak doesn't matter). Anywhere with a lifecycle — a request, a worker, anything cancelable — use `NewTicker` + `Stop`. The same applies to `time.After` inside a loop: each call creates a new timer that isn't collected until it fires, so a tight loop with `time.After` in a `select` can accumulate timers; use a single reset `time.Timer` if that's hot. Since Go 1.23, unreferenced `Timer`/`Ticker` channels became eligible for GC even without `Stop`, and `Reset`/`Stop` semantics were cleaned up — but explicit `Stop` remains the clear, portable practice.

### 🟡 — extended

#### Q94. [Coding] Write a function that runs N tasks concurrently but returns the first error and cancels the rest.

`golang.org/x/sync/errgroup` is purpose-built for this: it runs goroutines, captures the first non-nil error, and (with `WithContext`) cancels a shared context so the rest can stop early.

```go
func fetchAll(ctx context.Context, urls []string) ([]Result, error) {
    g, ctx := errgroup.WithContext(ctx)
    results := make([]Result, len(urls))

    for i, url := range urls {
        i, url := i, url // safe even pre-1.22; explicit is clear
        g.Go(func() error {
            r, err := fetch(ctx, url) // ctx is canceled if any sibling fails
            if err != nil {
                return fmt.Errorf("fetch %s: %w", url, err)
            }
            results[i] = r // distinct index per goroutine → no lock needed
            return nil
        })
    }

    if err := g.Wait(); err != nil { // returns the FIRST error encountered
        return nil, err
    }
    return results, nil
}
```

Why this is the idiomatic answer: each goroutine writes a **distinct slice index**, so there's no shared-write race and no mutex needed; `g.Wait()` blocks until all return and yields the first error; and the derived `ctx` is canceled the moment any task errors, so in-flight tasks watching `ctx.Done()` abort promptly instead of wasting work. You can also bound concurrency with `g.SetLimit(n)` (Go's errgroup added this) to avoid launching thousands of goroutines at once.

#### Q95. [Practical] A goroutine reads from a channel in a `for range`, but the loop never exits. What's the bug?

`for v := range ch` terminates **only when the channel is closed**. If the sender never closes it, the range blocks forever waiting for the next value, and the goroutine leaks.

```go
// Bug: producer never closes ch, so the consumer's range never ends
func process(ch <-chan int) {
    for v := range ch { // blocks forever after the last send
        handle(v)
    }
    // unreachable
}
```

Fixes, depending on ownership:

- **Sender closes when done** — the canonical fix. Only the sender should close, exactly once:
  ```go
  go func() {
      defer close(ch) // signals "no more values" → consumer's range ends
      for _, v := range data { ch <- v }
  }()
  ```
- **If you can't close** (e.g. the channel outlives the consumer), use `select` with a cancellation channel instead of `range`:
  ```go
  for {
      select {
      case v := <-ch:
          handle(v)
      case <-ctx.Done():
          return
      }
  }
  ```

The rule: a `range` over a channel is a contract that *someone closes it*. Forgetting that is one of the most common goroutine-leak sources. Remember the closing rules — only the sender closes, never the receiver, and never close twice.

#### Q96. [Coding] Implement a debounce function in Go.

Debouncing collapses a burst of rapid calls into a single delayed execution — the action fires only after activity stops for a quiet period. A `time.Timer` reset on each call implements it.

```go
func Debounce(d time.Duration, fn func()) func() {
    var mu sync.Mutex
    var timer *time.Timer
    return func() {
        mu.Lock()
        defer mu.Unlock()
        if timer != nil {
            timer.Stop() // cancel the pending fire
        }
        timer = time.AfterFunc(d, fn) // schedule fresh; only the last one survives
    }
}

// usage
save := Debounce(500*time.Millisecond, func() { fmt.Println("saved") })
save(); save(); save() // rapid calls → "saved" prints once, 500ms after the last call
```

Each invocation cancels the previously scheduled timer and starts a new one, so only a call followed by `d` of silence actually runs `fn`. The mutex makes it safe under concurrent callers. Contrast with **throttling**, which guarantees execution at most once per interval (fires on a schedule regardless of how many calls arrive). `time.AfterFunc` runs `fn` in its own goroutine, so if `fn` touches shared state it needs its own synchronization. For a cancelable version, capture the returned `*time.Timer` and expose a `Cancel()` that stops it.

#### Q97. [Practical] Your service's memory keeps growing and never returns to the OS. How do you investigate?

Steadily growing RSS in Go has a few distinct causes; distinguish a **real leak** (live heap growing) from **retained-but-idle memory** (Go holding freed pages) before fixing.

Investigation steps:

1. **Heap profile** — `go tool pprof http://.../debug/pprof/heap`, compare `inuse_space` over time. If `inuse_space` grows unbounded, you have a genuine leak — objects still reachable. Use `-base` to diff two snapshots and find the growing allocation site.
2. **Common leak sources**: an ever-growing map/slice used as a cache with no eviction; goroutines leaking (each holds its stack and captured vars — check `runtime.NumGoroutine()` and the goroutine profile); `time.Tick`/unclosed tickers; appended-to slices that retain a huge backing array via sub-slicing; values stuck in a `context.WithValue` chain or global registry.
3. **If `inuse_space` is flat but RSS is high**, the heap isn't leaking — Go may just be holding pages it hasn't returned. `GODEBUG=gctrace=1` and `runtime/metrics` show heap-released vs heap-idle. Set `GOMEMLIMIT` to cap total and encourage the scavenger to return memory.

```go
// Classic leak: a cache that only grows
var cache = map[string][]byte{}
func store(k string, v []byte) { cache[k] = v } // never evicts → unbounded growth
```

Fixes follow the cause: add LRU/TTL eviction to caches, fix goroutine leaks (cancellation + `Close`), copy small sub-slices to drop a large backing array (`append([]T(nil), s...)`), and set `GOMEMLIMIT`. The throughline is **profile `inuse_space` first** — don't tune the GC for what is actually an application-level retention bug.

#### Q98. [Coding] How do you safely update a shared map from multiple goroutines? Show three approaches.

A plain `map` is **not** safe for concurrent use — concurrent read+write triggers the runtime's `fatal error: concurrent map read and map write`. Three correct approaches:

```go
// 1. Mutex-guarded map — simplest, most flexible
type SafeMap struct {
    mu sync.RWMutex
    m  map[string]int
}
func (s *SafeMap) Get(k string) (int, bool) {
    s.mu.RLock(); defer s.mu.RUnlock()
    v, ok := s.m[k]
    return v, ok
}
func (s *SafeMap) Set(k string, v int) {
    s.mu.Lock(); defer s.mu.Unlock()
    s.m[k] = v
}

// 2. sync.Map — for specific access patterns
var sm sync.Map
sm.Store("key", 42)
v, ok := sm.Load("key")

// 3. Sharded map — for high contention, lock per shard
type ShardedMap struct { shards [256]struct {
    mu sync.RWMutex
    m  map[string]int
}}
```

Choosing: the **mutex-guarded map** is the right default — clear, flexible, and fast enough for most workloads (use `RWMutex` only if reads dominate *and* critical sections are non-trivial). **`sync.Map`** is optimized for two narrow patterns — keys written once then read many times, or disjoint key sets per goroutine — and is *slower* than a plain mutexed map for general read/write churn, so don't reach for it reflexively. **Sharding** (lock-striping by `hash(key) % N`) cuts contention when one mutex becomes a bottleneck under many cores. Always confirm with the race detector and benchmark the realistic access pattern.

#### Q99. [Practical] You need to limit how many requests per second your client sends to an API. How do you implement rate limiting?

Use `golang.org/x/time/rate`, a token-bucket limiter that's the standard Go answer for client-side rate limiting.

```go
// Allow 10 requests/second with bursts up to 20.
limiter := rate.NewLimiter(rate.Limit(10), 20)

func callAPI(ctx context.Context) error {
    // Wait blocks until a token is available or ctx is canceled.
    if err := limiter.Wait(ctx); err != nil {
        return fmt.Errorf("rate limit wait: %w", err)
    }
    return doRequest(ctx)
}
```

`rate.NewLimiter(r, b)` permits `r` events per second with a burst bucket of size `b`. Three ways to consume a token: `Wait(ctx)` blocks until one is free (best for clients that should throttle, not drop); `Allow()` returns `false` immediately if none is available (best for dropping/shedding); and `Reserve()` tells you how long to wait so you can schedule. The burst parameter lets short spikes through while bounding the sustained rate.

For a **server** limiting many clients, key a per-client limiter (e.g. a map of `clientID → *rate.Limiter` with eviction) and return HTTP 429 when `Allow()` is false. For distributed rate limiting across instances you need a shared store (Redis with a token-bucket/sliding-window script), since each process's in-memory limiter only governs its own traffic.

#### Q100. [Coding] How do you implement a context-aware sleep that can be canceled?

`time.Sleep` is **not** cancelable — it blocks the goroutine for the full duration regardless of context cancellation. To sleep but bail out early on cancellation, `select` on a timer and `ctx.Done()`.

```go
func sleepCtx(ctx context.Context, d time.Duration) error {
    t := time.NewTimer(d)
    defer t.Stop() // avoid leaking the timer if ctx fires first
    select {
    case <-t.C:
        return nil // slept the full duration
    case <-ctx.Done():
        return ctx.Err() // canceled or deadline exceeded → return early
    }
}
```

The `defer t.Stop()` matters: if `ctx.Done()` wins the race, the timer is still pending and would otherwise hold its resources until it fires. Using `time.After(d)` instead of an explicit `NewTimer` works but can't be stopped, so in a hot loop it leaks timers until they fire — `NewTimer` + `Stop` is the leak-free form. This pattern generalizes: **any blocking wait in a cancelable context should `select` on `ctx.Done()`**, so a canceled request doesn't keep goroutines parked. It's the building block for cancelable retries, backoff, and polling loops.

#### Q101. [Practical] After upgrading a dependency, `go build` fails with version conflicts. How do you resolve module issues?

Module build failures usually stem from inconsistent `require` directives or a stale graph. A systematic toolkit:

- **`go mod tidy`** — the first thing to run. It adds any missing requirements and removes unused ones, recomputing the graph so `go.mod`/`go.sum` are consistent with the actual imports.
- **`go mod graph`** — prints the full dependency graph so you can see who requires the conflicting version.
- **`go mod why <module>`** — explains why a module is in your build (which import chain pulls it in).
- **`go get pkg@version`** — pin or upgrade a specific dependency; `go get -u ./...` updates to latest minor/patch.
- **`replace` directive** — force a specific version or local path when a transitive dep is broken: `replace example.com/bad v1.2.0 => example.com/bad v1.2.1`.
- **`exclude`** — exclude a known-bad version so MVS picks another.

```
// go.mod
require example.com/lib v1.5.0
replace example.com/lib => example.com/lib v1.5.1 // temporary override
```

Remember Go uses **minimal version selection**: the build picks the *lowest* version satisfying all requirements, so a conflict means two deps require incompatible versions — `go mod graph` finds the culprit. For checksum mismatches (`SECURITY ERROR`), check `GOFLAGS`, `GONOSUMCHECK`/`GONOSUMDB`, or that `go.sum` wasn't corrupted; `go mod verify` validates the downloaded modules against `go.sum`. Major-version bumps (v2+) change the import path (`/v2`), a frequent upgrade gotcha.

### 🟠 — extended

#### Q102. [Coding] Implement a bounded concurrent map-reduce that processes a large slice with a worker pool and aggregates results.

```go
func MapReduce[T, R any](
    ctx context.Context,
    items []T,
    workers int,
    mapFn func(context.Context, T) (R, error),
    reduceFn func(R, R) R,
) (R, error) {
    g, ctx := errgroup.WithContext(ctx)
    g.SetLimit(workers) // cap concurrency

    var (
        mu  sync.Mutex
        acc R
    )
    for _, it := range items {
        it := it
        g.Go(func() error {
            r, err := mapFn(ctx, it)
            if err != nil {
                return err // cancels ctx, stops the rest
            }
            mu.Lock()
            acc = reduceFn(acc, r) // serialize the combine step
            mu.Unlock()
            return nil
        })
    }
    if err := g.Wait(); err != nil {
        var zero R
        return zero, err
    }
    return acc, nil
}

// usage: parallel sum of squares
total, err := MapReduce(ctx, nums, 8,
    func(_ context.Context, n int) (int, error) { return n * n, nil },
    func(a, b int) int { return a + b },
)
```

Design notes a senior should articulate: `g.SetLimit(workers)` bounds in-flight goroutines so a huge slice doesn't spawn a goroutine per element (which would blow up memory and scheduler pressure); the `mapFn` runs in parallel while the `reduceFn` is serialized under a mutex because reduction is the only shared-state step; and `errgroup`'s context cancellation means a single map failure aborts the rest promptly. If `reduceFn` is associative and commutative and the mutex becomes a bottleneck, switch to **per-worker partial accumulators** merged at the end, eliminating the hot lock entirely — a common refinement when the combine step is cheap and contended.

#### Q103. [Practical] A `-race` run is clean locally but the same code corrupts data in production. How is that possible?

The race detector has **no false positives** but plenty of false negatives: it only flags races on code paths that **actually execute during the run**. A clean local `-race` run just means *the races you exercised* weren't triggered — not that the code is race-free.

Why production differs:

- **Unexercised paths**: a race in an error branch, a rare timeout handler, or a code path only hit under specific input never ran in your local tests, so `-race` never saw it.
- **Concurrency level**: races often need a specific interleaving. Local tests with one or two goroutines may never produce the timing that production's hundreds of concurrent requests do.
- **Production-only data/config**: feature flags, larger datasets, or different request mixes activate code that local tests skip.

```go
// A race only on the cache-miss path — never hit if local tests always warm the cache
func (c *Cache) Get(k string) V {
    if v, ok := c.m[k]; ok { // local tests only ever hit this branch
        return v
    }
    c.m[k] = load(k) // RACE: unsynchronized write, only on miss
    return c.m[k]
}
```

The fix is process, not luck: run `-race` against **integration and load tests** that exercise realistic paths and concurrency, enable it in CI, run a canary with `-race` under real (sampled) traffic if you can afford the ~5-10x overhead, and add `go.uber.org/goleak`. Code review for the concurrency *contract* (which fields are shared, what guards them) catches what testing misses. The deeper lesson: absence of a `-race` report is not proof of correctness — it's proof you didn't trip a race *on the paths you ran*.

#### Q104. [Coding] Implement a circuit breaker in Go.

A circuit breaker stops calling a failing downstream to give it time to recover, failing fast instead of piling on. It has three states: **closed** (calls pass through), **open** (calls fail immediately), and **half-open** (a probe call tests recovery).

```go
type State int
const (Closed State = iota; Open; HalfOpen)

type CircuitBreaker struct {
    mu           sync.Mutex
    state        State
    failures     int
    threshold    int           // failures before opening
    resetTimeout time.Duration // how long to stay open
    openedAt     time.Time
}

func (cb *CircuitBreaker) Call(fn func() error) error {
    cb.mu.Lock()
    if cb.state == Open {
        if time.Since(cb.openedAt) > cb.resetTimeout {
            cb.state = HalfOpen // allow one probe
        } else {
            cb.mu.Unlock()
            return errors.New("circuit open: failing fast")
        }
    }
    cb.mu.Unlock()

    err := fn()

    cb.mu.Lock()
    defer cb.mu.Unlock()
    if err != nil {
        cb.failures++
        if cb.state == HalfOpen || cb.failures >= cb.threshold {
            cb.state = Open // trip (or re-trip on a failed probe)
            cb.openedAt = time.Now()
        }
        return err
    }
    // success
    cb.failures = 0
    cb.state = Closed
    return nil
}
```

Key behaviors to explain: in **closed** state, consecutive failures accumulate and trip the breaker to **open** at the threshold; in **open** state, calls fail instantly (protecting both you and the downstream) until `resetTimeout` elapses; then a single **half-open** probe either closes the breaker (success) or re-opens it (failure). Production breakers add a rolling-window failure *rate* (not just a raw count), a limited number of half-open probes, and metrics/alerts on state transitions. `sony/gobreaker` is the well-known library implementing exactly this. Pair it with retries+backoff and per-call timeouts for a complete resilience story.

#### Q105. [Practical] Goroutines spike to tens of thousands and the service slows down. How do you find and fix the leak?

A climbing goroutine count that never recedes is the signature of a **goroutine leak**: goroutines that block forever and are never collected. Each holds its stack and captured variables, so the count and memory grow until the service degrades.

Diagnosis:

1. **Confirm the trend**: expose `runtime.NumGoroutine()` as a metric; a monotonically rising count under steady load confirms a leak.
2. **Dump and group**: `go tool pprof http://.../debug/pprof/goroutine` — the profile groups goroutines by their stack, so thousands stuck at the *same* line (e.g. `chan receive` or `chan send` in one function) pinpoint the leak site instantly. `?debug=2` gives a full human-readable dump.
3. **Read what they're blocked on**: `chan send` → no receiver; `chan receive` → channel never closed/written; `sync.WaitGroup.Wait` → a missing `Done`; `select` with no ready case and no `ctx.Done()`.

```go
// Leak: producer blocks forever on send after the consumer times out and leaves
func query(ctx context.Context) (Result, error) {
    ch := make(chan Result) // UNBUFFERED
    go func() { ch <- doQuery() }() // blocks forever if select below picks ctx.Done()
    select {
    case r := <-ch:
        return r, nil
    case <-ctx.Done():
        return Result{}, ctx.Err() // consumer leaves; producer leaks
    }
}
```

The fix: `ch := make(chan Result, 1)` so the producer's send always succeeds even after the consumer has gone, or make the producer `select` on `ctx.Done()` for its send. General fixes: ensure every channel has a defined closer, every `cancel()` is called (`defer cancel()`), and every blocking op in a cancelable scope `select`s on `ctx.Done()`. Add `go.uber.org/goleak` in tests to catch leaks before they ship.

#### Q106. [Coding] How do you stream-process a large file or HTTP response without loading it all into memory?

Operate on `io.Reader`/`io.Writer` streams instead of reading everything into a `[]byte`. Process in chunks or line by line so memory stays bounded regardless of input size.

```go
// Stream-copy with bounded memory (io.Copy uses a 32KB internal buffer):
func download(ctx context.Context, url, dst string) error {
    req, _ := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
    resp, err := http.DefaultClient.Do(req)
    if err != nil { return err }
    defer resp.Body.Close()

    f, err := os.Create(dst)
    if err != nil { return err }
    defer f.Close()

    // Streams body → file in 32KB chunks; never holds the whole payload.
    if _, err := io.Copy(f, resp.Body); err != nil {
        return fmt.Errorf("streaming download: %w", err)
    }
    return nil
}

// Stream-transform line by line (e.g. count matching lines in a huge log):
func countMatches(r io.Reader, substr string) (int, error) {
    sc := bufio.NewScanner(r)
    n := 0
    for sc.Scan() {
        if strings.Contains(sc.Text(), substr) {
            n++
        }
    }
    return n, sc.Err()
}
```

The principle: anything modeled as `io.Reader` can be consumed incrementally. Use `io.Copy`/`io.CopyBuffer` for byte streaming, `bufio.Scanner`/`bufio.Reader` for line/token streaming, `json.NewDecoder(r).Decode` for streaming JSON (and `Decoder.More()` to stream a JSON array element by element), and `csv.NewReader(r).Read()` to process CSV rows one at a time. Avoid `io.ReadAll` (`ioutil.ReadAll`) on untrusted or large inputs — it buffers the entire thing and is both a memory hog and a denial-of-service vector. For bounded reads, wrap with `io.LimitReader`. This is how Go services handle multi-gigabyte payloads with megabytes of RAM.

#### Q107. [Practical] Your benchmark shows wildly inconsistent results between runs. How do you write reliable Go benchmarks?

Noisy benchmarks usually come from measurement mistakes, not real variance. A reliable benchmark controls what it measures and runs enough to be statistically stable.

```go
func BenchmarkParse(b *testing.B) {
    input := makeLargeInput() // setup — NOT timed yet
    b.ReportAllocs()          // report allocations
    b.ResetTimer()           // exclude setup from timing
    for i := 0; i < b.N; i++ {
        result := Parse(input)
        _ = result // use the result so the compiler can't elide the call
    }
}
```

Practices that fix inconsistency:

- **`b.ResetTimer()`** after setup so expensive fixtures don't pollute the measurement; **`b.StopTimer()`/`b.StartTimer()`** around per-iteration setup inside the loop.
- **Defeat dead-code elimination** — assign results to a package-level sink or use `b.Loop()` (Go 1.24), which is designed to keep the loop body from being optimized away and handles timer reset automatically.
- **Run multiple times and compare** — `go test -bench=. -count=10 -benchmem` and feed the output to `benchstat`, which reports the median and confidence interval so you don't eyeball noise.
- **Control the environment** — close other apps, disable CPU frequency scaling/turbo where possible, pin `GOMAXPROCS`, and run on a quiet machine; cloud VMs are notoriously noisy.

```go
// Go 1.24 style — fewer foot-guns:
func BenchmarkParse(b *testing.B) {
    input := makeLargeInput()
    for b.Loop() { // resets timer, prevents elision
        _ = Parse(input)
    }
}
```

The discipline: never trust a single run or a single iteration; use `-count` + `benchstat` for statistical rigor, `-benchmem` to catch allocation regressions, and `-cpuprofile` to explain *why* one version is faster. A 2% difference without `benchstat` confidence intervals is noise.

#### Q114. [Coding] Show how to time out a single database query and propagate cancellation correctly.

The `database/sql` package's `*Context` methods (`QueryContext`, `ExecContext`, `QueryRowContext`) abort the query when the context is canceled or its deadline passes — the driver sends a cancel to the server and returns an error.

```go
func getUser(ctx context.Context, db *sql.DB, id int64) (*User, error) {
    // Bound this specific query to 2 seconds, derived from the caller's ctx.
    ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
    defer cancel() // ALWAYS — releases the timer even on the happy path

    var u User
    err := db.QueryRowContext(ctx,
        "SELECT id, name, email FROM users WHERE id = $1", id,
    ).Scan(&u.id, &u.Name, &u.Email)

    switch {
    case errors.Is(err, sql.ErrNoRows):
        return nil, ErrNotFound // distinguish "no row" from a real failure
    case errors.Is(err, context.DeadlineExceeded):
        return nil, fmt.Errorf("query timed out: %w", err)
    case err != nil:
        return nil, fmt.Errorf("query user %d: %w", id, err)
    }
    return &u, nil
}
```

The important details: pass the **caller's** context as the parent so a canceled request also cancels the query (don't start from `context.Background()` mid-request, which severs cancellation); `defer cancel()` even when the query succeeds, or you leak the timer until it fires; and handle `sql.ErrNoRows` separately from errors via `errors.Is`, since a missing row usually isn't a failure. For `Query`/`Rows`, also `defer rows.Close()` and check `rows.Err()` after iterating. Pool tuning (`SetMaxOpenConns`, `SetConnMaxLifetime`) bounds connection usage so a slow downstream doesn't exhaust the pool — and a context timeout that fires while waiting for a free connection returns promptly instead of blocking.

#### Q115. [Practical] Logs from concurrent goroutines are interleaved and hard to trace per request. How do you fix observability?

Interleaved, contextless logs are a concurrency observability problem, not a logging-library problem. Two fixes: structured logging and request-scoped context propagation.

Use `log/slog` (stdlib since Go 1.21) for **structured** logs — key/value fields instead of free-form strings — so you can filter and correlate by field even when lines interleave:

```go
logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

// Attach a request ID once, then derive a per-request logger.
reqLogger := logger.With(
    slog.String("request_id", reqID),
    slog.Int64("user_id", userID),
)
reqLogger.Info("processing", "step", "validate")
// every line from this request carries request_id → grep/group by it
```

For cancellation-aware, cross-goroutine correlation, carry the logger (or just the request ID) in the `context`, and use `slog`'s context-aware methods so spawned goroutines inherit the fields:

```go
ctx = context.WithValue(ctx, loggerKey{}, reqLogger)
// in a downstream goroutine:
slog.InfoContext(ctx, "db query done", "rows", n)
```

The principles: a **request/trace ID** threaded through context lets you reconstruct one request's path across goroutines and services even when lines are interleaved by concurrency; **structured fields** make logs queryable; and you should **log once at boundaries** rather than at every layer to avoid duplicate noise. For distributed systems, integrate OpenTelemetry tracing (a trace+span ID in context, propagated via headers) so the correlation spans process boundaries — interleaving stops mattering because you filter by trace ID, not line order. A custom `slog.Handler` can automatically pull the trace ID out of context onto every record.

#### Q116. [Coding] Implement a fan-out/fan-in that processes a stream, preserves input order in the output, and supports cancellation.

A naive fan-out loses ordering because faster workers finish first. To keep output in input order while still processing concurrently, tag each item with its index (or use per-item result channels) and reassemble.

```go
func OrderedMap[T, R any](
    ctx context.Context, in []T, workers int, fn func(context.Context, T) (R, error),
) ([]R, error) {
    type job struct {
        idx int
        val T
    }
    jobs := make(chan job)
    out := make(chan struct {
        idx int
        res R
        err error
    })

    // Feed jobs.
    go func() {
        defer close(jobs)
        for i, v := range in {
            select {
            case jobs <- job{i, v}:
            case <-ctx.Done():
                return
            }
        }
    }()

    // Worker pool.
    var wg sync.WaitGroup
    wg.Add(workers)
    for w := 0; w < workers; w++ {
        go func() {
            defer wg.Done()
            for j := range jobs {
                r, err := fn(ctx, j.val)
                select {
                case out <- struct {
                    idx int
                    res R
                    err error
                }{j.idx, r, err}:
                case <-ctx.Done():
                    return
                }
            }
        }()
    }
    go func() { wg.Wait(); close(out) }()

    // Reassemble by index — placing each result at its original position.
    results := make([]R, len(in))
    var firstErr error
    for o := range out {
        if o.err != nil && firstErr == nil {
            firstErr = o.err
        }
        results[o.idx] = o.res
    }
    if firstErr != nil {
        return nil, firstErr
    }
    return results, nil
}
```

The technique that preserves order: **carry the original index through the pipeline** and write each result to `results[idx]`, so concurrency doesn't scramble output. Because each worker writes to a distinct index in the final slice via the single-reader reassembly loop, there's no data race on `results`. Cancellation is wired through every blocking op — the feeder's send, the workers' send, and (in a full implementation) by canceling `ctx` on the first error so workers stop early. The closer goroutine (`wg.Wait(); close(out)`) terminates the reassembly `range` cleanly. The trade-off: preserving order means the slowest item can hold a slot, but unlike a strictly sequential reorder buffer this still processes all items concurrently — you just pay O(n) memory for the result slice. For unbounded streams where you can't size the slice, use a reorder buffer keyed by a running sequence number that emits in order as contiguous results become available.

#### Q108. [Coding] Implement graceful shutdown that drains a worker pool and a background ticker on SIGTERM.

```go
func run() error {
    ctx, stop := signal.NotifyContext(context.Background(),
        syscall.SIGINT, syscall.SIGTERM)
    defer stop()

    jobs := make(chan Job, 100)
    var wg sync.WaitGroup

    // Worker pool
    for i := 0; i < 4; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            for {
                select {
                case j, ok := <-jobs:
                    if !ok {
                        return // jobs drained and closed → exit
                    }
                    process(j)
                case <-ctx.Done():
                    return // shutdown signal → stop pulling new jobs
                }
            }
        }()
    }

    // Background ticker
    wg.Add(1)
    go func() {
        defer wg.Done()
        ticker := time.NewTicker(time.Second)
        defer ticker.Stop()
        for {
            select {
            case <-ticker.C:
                emitMetrics()
            case <-ctx.Done():
                return
            }
        }
    }()

    <-ctx.Done()          // block until SIGTERM/SIGINT
    log.Println("shutting down...")

    // Optional: stop accepting new jobs, then let in-flight finish within a deadline.
    close(jobs)           // no more jobs; workers drain remaining buffered jobs

    done := make(chan struct{})
    go func() { wg.Wait(); close(done) }()
    select {
    case <-done:
        log.Println("clean shutdown")
    case <-time.After(30 * time.Second):
        log.Println("shutdown timed out; forcing exit")
    }
    return nil
}
```

The shape every grader looks for: `signal.NotifyContext` converts SIGTERM (what Kubernetes sends before killing a pod) into a cancelable context; every long-lived goroutine `select`s on `ctx.Done()` so they all unblock together; the ticker is stopped via `defer ticker.Stop()`; and a **bounded** wait (`time.After`) prevents shutdown from hanging forever if a worker is stuck. Order matters — stop intake first (`close(jobs)` / stop the HTTP listener), then drain in-flight work, then close downstream resources (DB pools, flush metrics). The deadline guarantees the process exits even if draining stalls.

### 🔴 — extended

#### Q109. [Coding] Implement a generic, concurrency-safe, expiring (TTL) cache with background eviction.

```go
type item[V any] struct {
    value   V
    expires time.Time
}

type TTLCache[K comparable, V any] struct {
    mu    sync.RWMutex
    items map[K]item[V]
    ttl   time.Duration
    stop  chan struct{}
}

func NewTTLCache[K comparable, V any](ttl, sweep time.Duration) *TTLCache[K, V] {
    c := &TTLCache[K, V]{
        items: make(map[K]item[V]),
        ttl:   ttl,
        stop:  make(chan struct{}),
    }
    go c.janitor(sweep) // background eviction
    return c
}

func (c *TTLCache[K, V]) Set(k K, v V) {
    c.mu.Lock()
    defer c.mu.Unlock()
    c.items[k] = item[V]{value: v, expires: time.Now().Add(c.ttl)}
}

func (c *TTLCache[K, V]) Get(k K) (V, bool) {
    c.mu.RLock()
    it, ok := c.items[k]
    c.mu.RUnlock()
    if !ok || time.Now().After(it.expires) {
        var zero V
        return zero, false // treat expired as absent (lazy expiry)
    }
    return it.value, true
}

func (c *TTLCache[K, V]) janitor(sweep time.Duration) {
    t := time.NewTicker(sweep)
    defer t.Stop()
    for {
        select {
        case <-t.C:
            now := time.Now()
            c.mu.Lock()
            for k, it := range c.items {
                if now.After(it.expires) {
                    delete(c.items, k) // active eviction frees memory
                }
            }
            c.mu.Unlock()
        case <-c.stop:
            return
        }
    }
}

func (c *TTLCache[K, V]) Close() { close(c.stop) } // stop the janitor goroutine
```

Expert points to surface: this combines **lazy expiry** (`Get` ignores expired entries) with **active eviction** (the janitor reclaims memory so expired-but-unread keys don't leak — lazy-only caches grow forever if keys stop being accessed). The `RWMutex` lets concurrent reads proceed; the janitor takes the write lock briefly per sweep. Crucially, `Close()` stops the janitor goroutine — without it, every cache instance leaks a goroutine (a classic finalizer/lifecycle bug). Refinements for production: a sharded lock to cut contention under load, a per-item TTL override, an eviction callback, and bounding the sweep cost on huge maps (sample a subset per tick rather than scanning all keys, as `groupcache`/Redis-style eviction does). For full-featured needs, `patrickmn/go-cache` or `ristretto` are the go-to libraries.

#### Q110. [Practical] A production goroutine occasionally panics and crashes the whole service. How do you contain panics at boundaries without hiding bugs?

A panic in **any** goroutine that isn't recovered crashes the entire process — and a panic cannot be recovered across goroutine boundaries, so a `recover` in `main` won't save a panic in a worker. The fix is a `recover` at each goroutine's top-level boundary, done carefully so you contain the blast radius without masking real bugs.

```go
// A safe-goroutine wrapper that recovers, logs with stack, and reports the panic.
func Go(name string, fn func()) {
    go func() {
        defer func() {
            if r := recover(); r != nil {
                buf := make([]byte, 64<<10)
                buf = buf[:runtime.Stack(buf, false)] // capture this goroutine's stack
                log.Printf("panic in %s: %v\n%s", name, r, buf)
                metrics.Inc("goroutine_panics", name)
                // optionally: report to Sentry, then decide whether to restart the worker
            }
        }()
        fn()
    }()
}
```

Where to put boundaries: the **top of each worker goroutine**, **each HTTP/gRPC request handler** (the stdlib `http.Server` already recovers per-request so one bad handler doesn't kill the server — but custom goroutines you spawn do *not* get this for free), **plugin or callback invocations**, and **message-queue consumers** (so one poison message doesn't crash the consumer).

The discipline that avoids hiding bugs: **always log the panic value and the stack** (`runtime.Stack`) and increment a metric/alert — a recovered panic is still a bug that must be visible and fixed, not silently swallowed. Don't wrap *every* function in recover (that hides logic errors and hurts performance); recover only at genuine isolation boundaries. After recovering, decide deliberately whether to restart the unit of work, drop it, or degrade — and never recover a panic that indicates corrupted invariants where continuing is unsafe; in those cases a controlled crash + restart (supervised by the orchestrator) is the correct behavior.

#### Q111. [Coding] Implement a weighted, fair work scheduler that pulls from multiple priority queues without starving low-priority work.

```go
type Task struct {
    Priority int // 0 = high, larger = lower
    Run      func()
}

// FairScheduler drains higher-priority queues preferentially but guarantees
// low-priority tasks make progress via a starvation counter.
type FairScheduler struct {
    queues   []chan Task // index = priority level
    maxSkips int         // after this many high-priority picks, force a low one
}

func NewFairScheduler(levels, buf, maxSkips int) *FairScheduler {
    qs := make([]chan Task, levels)
    for i := range qs {
        qs[i] = make(chan Task, buf)
    }
    return &FairScheduler{queues: qs, maxSkips: maxSkips}
}

func (s *FairScheduler) Submit(t Task) { s.queues[t.Priority] <- t }

func (s *FairScheduler) Run(ctx context.Context) {
    skips := make([]int, len(s.queues))
    for {
        // 1. If any lower-priority queue has been skipped too often, serve it first.
        for p := len(s.queues) - 1; p >= 1; p-- {
            if skips[p] >= s.maxSkips {
                select {
                case t := <-s.queues[p]:
                    skips[p] = 0
                    t.Run()
                default:
                }
            }
        }
        // 2. Otherwise prefer the highest-priority queue that has work.
        picked := false
        for p := 0; p < len(s.queues); p++ {
            select {
            case t := <-s.queues[p]:
                t.Run()
                for lower := p + 1; lower < len(s.queues); lower++ {
                    skips[lower]++ // every lower queue got skipped this round
                }
                picked = true
            default:
            }
            if picked {
                break
            }
        }
        // 3. Nothing ready: block until any queue has work or ctx cancels.
        if !picked {
            select {
            case <-ctx.Done():
                return
            case <-time.After(time.Millisecond): // re-poll
            }
        }
    }
}
```

The core idea an interviewer wants: a **naive priority scheduler starves low-priority work** — if high-priority tasks keep arriving, low ones never run. The fix is *aging* / a fairness guarantee: track how many times each lower queue has been skipped and, past a threshold (`maxSkips`), force-serve it even though higher-priority work is available. This bounds the worst-case latency of low-priority tasks while still favoring high-priority ones in the common case. Production-grade variants use **weighted fair queuing** (serve queues in proportion to weights, e.g. via a deficit round-robin counter) rather than a hard skip threshold, and replace the `time.After` re-poll with a proper "any-queue-ready" notification to avoid busy-waiting (e.g. a shared semaphore/condition or a merged `select` over all queues when the level count is small). The trade-off discussion — strict priority vs. fairness vs. busy-poll cost — is what distinguishes a staff answer.

#### Q112. [Practical] CPU usage is pinned at 100% but throughput is low. How do you find what's burning CPU, and what are the usual Go culprits?

High CPU with low useful throughput means cycles are going to overhead rather than work. The tool is the **CPU profile**; the skill is interpreting it.

```
go tool pprof http://localhost:6060/debug/pprof/profile?seconds=30
(pprof) top         # functions by self-CPU
(pprof) list <fn>   # line-level hotspots inside a function
(pprof) web         # flame graph of the call tree
```

Common Go culprits the profile reveals:

- **Lock contention / busy-spinning**: heavy time in `runtime.lock`/`sync.(*Mutex).Lock` or a hand-rolled spin loop. Goroutines burning CPU fighting over a hot mutex. Fix by sharding, shrinking critical sections, or going lock-free.
- **GC overhead**: large `runtime.gcBgMarkWorker`/`runtime.mallocgc`/`runtime.scanobject` time means a high allocation rate is making the GC do constant work (and mark-assist steals CPU from your goroutines). Confirm with `GODEBUG=gctrace=1`; fix by reducing allocations.
- **A tight non-preemptible or polling loop**: a `for {}` that spins, or a `select { default: }` busy-poll that never blocks, pegs a core doing nothing useful. Replace busy-polling with blocking channel ops.
- **`GOMAXPROCS` mismatch in containers**: Go sees the host's core count, not the cgroup CPU limit, so it spins up too many Ps and the kernel throttles you — high CPU accounting, low real progress. Set `GOMAXPROCS` to the CPU limit (`automaxprocs`).
- **Expensive per-call work**: reflection (`encoding/json` on hot paths), regex compilation inside a loop (compile once, reuse), `fmt`-based string building (use `strconv`/`strings.Builder`), or repeated `time.Now()`/syscalls.

The method: profile under load, read `top` for self-CPU, `list` the hot functions to find the exact lines, and check the flame graph for whether the time is in *your* code, in `runtime` (GC/scheduler/locks), or in syscalls. Then attribute (contention vs. GC vs. busy-loop vs. throttling) and apply the matching fix — and re-profile to confirm the hotspot moved.

#### Q113. [Coding] Implement a `singleflight`-style mechanism to collapse duplicate concurrent requests for the same key.

When many goroutines request the same expensive thing at once (a cache miss stampede), you want exactly **one** in-flight computation per key, with all callers sharing its result. This is `golang.org/x/sync/singleflight`; here's the core mechanism.

```go
type call[V any] struct {
    wg  sync.WaitGroup
    val V
    err error
}

type Group[K comparable, V any] struct {
    mu sync.Mutex
    m  map[K]*call[V]
}

func (g *Group[K, V]) Do(key K, fn func() (V, error)) (V, error) {
    g.mu.Lock()
    if g.m == nil {
        g.m = make(map[K]*call[V])
    }
    if c, ok := g.m[key]; ok { // a call for this key is already running
        g.mu.Unlock()
        c.wg.Wait()            // wait for it instead of duplicating work
        return c.val, c.err    // share its result
    }
    c := &call[V]{}
    c.wg.Add(1)
    g.m[key] = c               // register as the in-flight call
    g.mu.Unlock()

    c.val, c.err = fn()        // exactly one goroutine runs fn for this key
    c.wg.Done()

    g.mu.Lock()
    delete(g.m, key)           // allow future calls to recompute
    g.mu.Unlock()
    return c.val, c.err
}
```

Usage wraps an expensive load so a thundering herd of concurrent misses triggers a **single** backend call:

```go
var g Group[string, []byte]
data, err := g.Do(userID, func() ([]byte, error) {
    return loadFromDB(userID) // runs once even if 1000 goroutines call concurrently
})
```

What an expert flags: this collapses **concurrent** duplicates only — once `fn` finishes the key is deleted, so a later request recomputes (it's not a cache; pair it with one). Caveats: a slow `fn` makes *all* waiters wait on it, and if `fn` panics or hangs, every waiter is affected — the real `singleflight` adds panic propagation and a `DoChan` variant with a `Forget(key)` to abandon a stuck call. There's also a subtle correctness point: sharing one result means one caller's error/cancellation is shared by all, so contexts shouldn't be captured naively. It's the canonical fix for **cache stampede** / dogpile, often combined with a cache and request coalescing at the edge.

## ✅ Key Takeaways

- Goroutines + channels are cheap and central; the GMP scheduler multiplexes millions of goroutines onto a few OS threads with work stealing, handoff, and (since 1.14) async preemption.
- Synchronize via channels for *flow* of data and via `sync`/`atomic` for *protection* of data — and rely on the memory model's happens-before edges; never share mutable state without one.
- Slices are pointer/len/cap headers over a shared backing array — always reassign `append`'s result and beware aliasing; maps need `make` before writing and have randomized iteration order.
- Errors are values: wrap with `%w`, inspect with `errors.Is`/`errors.As`; reserve `panic`/`recover` for truly exceptional cases.
- The GC is a concurrent, low-latency tri-color mark-and-sweep tuned by `GOGC`/`GOMEMLIMIT`; reducing allocations (escape analysis, pooling, preallocation) is the most effective performance lever.
- Use `context` for cancellation and deadlines, always `defer cancel()`, and design for graceful shutdown and goroutine-leak avoidance.

## ⚠️ Common Pitfalls

- A non-nil interface holding a nil concrete pointer is `!= nil` — return literal `nil` on success paths.
- Forgetting to reassign `append`'s result, or unknowingly sharing a backing array between two slices and corrupting data.
- Writing to a nil map (panics), closing a channel from the receiver, closing twice, or sending on a closed channel (all panic).
- Goroutine leaks from blocked sends with no receiver, never-closed channels, or un-called `cancel` funcs; buffer result channels by 1 to avoid leaking a producer after a timeout.
- Capturing a loop variable by reference (pre-Go 1.22) or calling `wg.Add` inside the goroutine instead of before launch.
- Reaching for `RWMutex` or generics reflexively — both have costs; measure before optimizing, and prefer `atomic.Pointer`/copy-on-write for hot read-mostly state.
- Misusing `sync.Pool` as a connection pool (its contents are GC-cleared) or mixing atomic and non-atomic access to the same variable.

## 📚 Further Reading

- *The Go Programming Language* (Donovan & Kernighan) — the canonical book.
- The Go Memory Model — https://go.dev/ref/mem
- Effective Go and the official spec — https://go.dev/doc/effective_go, https://go.dev/ref/spec
- "Concurrency in Go" (Katherine Cox-Buday) — patterns, pipelines, and leak avoidance.
- Go runtime/scheduler design docs and the GMP/GC source under `src/runtime/`.
- "A Guide to the Go Garbage Collector" — https://go.dev/doc/gc-guide
- Dave Cheney's blog (practical Go, error handling, performance) — https://dave.cheney.net
