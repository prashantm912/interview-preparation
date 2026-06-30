# Python (Language Deep-Dive)

[← Back to master index](../README.md)

A rigorous, interview-focused tour of the Python language for engineers who think in Java and want to reason about Python with the same precision. It covers the data model and dunder methods, mutability and identity, the GIL, generators and iterators, decorators and context managers, the typing system, dataclasses, memory management, the MRO and metaclasses, packaging, and the workhorse standard-library modules. Accurate and current to 2026 (CPython 3.12/3.13 era, including the experimental free-threaded build).

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

### Q1. [Theory] What is the difference between a list, a tuple, and a set?

All three are built-in container types, but they differ in mutability, ordering, and semantics:

- **`list`** — ordered, mutable, allows duplicates, indexable by position. Backed by a dynamic array (contiguous block of pointers), so indexing is `O(1)` and appends are amortized `O(1)`.
- **`tuple`** — ordered, **immutable**, allows duplicates. Because it is immutable and hashable (if its contents are hashable), it can be a dictionary key or set member. Tuples also carry semantic intent: a fixed-shape record like `(x, y)`.
- **`set`** — unordered, mutable, **no duplicates**, members must be hashable. Backed by a hash table, so membership testing (`in`) is `O(1)` average versus `O(n)` for a list.

```python
nums = [1, 2, 2, 3]      # list   -> [1, 2, 2, 3]
point = (10, 20)         # tuple  -> immutable pair
unique = {1, 2, 2, 3}    # set    -> {1, 2, 3}

10 in unique             # O(1) average
2 in nums                # O(n)
```

Rule of thumb: reach for a `tuple` when the collection is a fixed record that should not change, a `set` when you need uniqueness or fast membership, and a `list` for everything else ordered and mutable.

### Q2. [Theory] Explain mutable vs immutable types in Python and why it matters.

Every Python object has a type and an identity. **Immutable** objects (`int`, `float`, `str`, `bytes`, `tuple`, `frozenset`, `bool`) cannot be changed after creation — any "modification" produces a new object. **Mutable** objects (`list`, `dict`, `set`, most user-defined classes) can be changed in place.

This matters for three reasons:

1. **Hashability** — only immutable (more precisely, hashable) objects can be dict keys or set members. A `list` cannot be a key; a `tuple` of immutables can.
2. **Aliasing** — two names can refer to the same mutable object, so mutating through one name is visible through the other.
3. **Shared default state** — mutable defaults and class attributes can be unintentionally shared (see the default-mutable-argument pitfall).

```python
a = [1, 2, 3]
b = a              # b is an alias, not a copy
b.append(4)
print(a)           # [1, 2, 3, 4]  <- surprised? a and b are the same object

s = "hello"
s += " world"      # creates a NEW str; the original "hello" is unchanged
```

To break aliasing, copy explicitly: `b = a.copy()` (shallow) or `copy.deepcopy(a)` (deep).

### Q3. [Theory] What is the difference between `is` and `==`?

`==` calls the `__eq__` method and tests **value equality** ("do these represent the same value?"). `is` tests **identity** ("are these the very same object in memory?", i.e. `id(a) == id(b)`).

```python
a = [1, 2, 3]
b = [1, 2, 3]
a == b   # True  -> same contents
a is b   # False -> different objects

x = None
x is None    # True  -> the idiomatic, correct check for None
x == None    # works but discouraged; can be overridden by __eq__
```

Always use `is` (and `is not`) for singletons: `None`, `True`, `False`. A classic trap is small-integer and string interning: `a = 256; b = 256; a is b` is often `True` because CPython caches small ints (−5..256), but `a = 257; b = 257; a is b` may be `False`. **Never** rely on identity for value comparison of numbers or strings — that interning is an implementation detail.

### Q4. [Theory] What does it mean that Python is dynamically and strongly typed?

**Dynamically typed**: variables are just names bound to objects; the type lives on the object, not the name. You can rebind a name to a different type at runtime, and type checking of operations happens when the code runs, not at compile time.

**Strongly typed**: Python does not silently coerce unrelated types. `"3" + 5` raises `TypeError` rather than guessing whether you meant `8` or `"35"` (contrast with JavaScript's `"3" + 5 === "35"`). You must convert explicitly: `int("3") + 5` or `"3" + str(5)`.

```python
x = 42        # name x -> int object
x = "hello"   # same name -> now a str object; legal

"3" + 5       # TypeError: can only concatenate str (not "int") to str
```

So Python is dynamic (types resolved at runtime) but strong (no implicit cross-type coercion).

### Q5. [Coding] Reverse a string and a list. Show idiomatic approaches.

Strings are immutable, so you cannot reverse them in place; you build a new one. Lists can be reversed in place or via a copy.

```python
s = "interview"

# Idiomatic: extended slice with step -1
s[::-1]                 # 'weivretni'

# Explicit: reversed() returns an iterator
"".join(reversed(s))    # 'weivretni'

nums = [1, 2, 3, 4]
nums[::-1]              # [4, 3, 2, 1]  -> new list (copy)
nums.reverse()         # in place; nums is now [4, 3, 2, 1], returns None
list(reversed(nums))   # iterator -> list, leaves original intact
```

Slicing `[::-1]` is `O(n)` time and space. `list.reverse()` is `O(n)` time and `O(1)` extra space. Note `list.reverse()` returns `None` (it mutates), a frequent off-by-one beginner bug: `x = mylist.reverse()` sets `x = None`.

### Q6. [Theory] What are list comprehensions, and when should you use them?

A comprehension is a concise expression that builds a list (or set/dict) from an iterable, optionally filtering and transforming. It is usually faster and clearer than an equivalent explicit loop with `.append()` because the iteration runs in optimized C and avoids repeated attribute lookups.

```python
# [expression for item in iterable if condition]
squares = [n * n for n in range(10)]
evens   = [n for n in range(20) if n % 2 == 0]
pairs   = [(x, y) for x in range(3) for y in range(3) if x != y]

# set and dict comprehensions
unique_lengths = {len(w) for w in words}
index = {name: i for i, name in enumerate(names)}
```

Use comprehensions for simple map/filter logic. **Avoid** them when the body has side effects, multiple statements, deep nesting, or complex conditionals — at that point a plain `for` loop is more readable. Comprehensions also introduce their own scope in Python 3, so the loop variable does not leak into the enclosing scope.

### Q7. [Theory] What is the difference between a list comprehension and a generator expression?

Syntax differs by the brackets: `[...]` builds a **list** eagerly (all elements materialized in memory); `(...)` builds a **generator** lazily (elements produced one at a time on demand).

```python
nums = [n * n for n in range(1_000_000)]   # ~8 MB list, all in memory
gen  = (n * n for n in range(1_000_000))   # tiny object, computes on demand

sum(n * n for n in range(1_000_000))       # parens optional as sole arg; O(1) memory
```

Use a generator expression when you only iterate once and want to avoid building a large intermediate list (streaming, piping into `sum`/`any`/`max`, processing huge files). Use a list comprehension when you need to index, re-iterate, or take `len()`. A generator is single-pass and exhausts after one full iteration.

### Q8. [Coding] Count word frequencies in a string. Show the idiomatic stdlib way.

```python
from collections import Counter

text = "the cat sat on the mat the cat"
counts = Counter(text.split())
# Counter({'the': 3, 'cat': 2, 'sat': 1, 'on': 1, 'mat': 1})

counts.most_common(2)   # [('the', 3), ('cat', 2)]
counts["dog"]           # 0  -> missing keys default to 0, no KeyError
```

`Counter` is a `dict` subclass purpose-built for counting. The manual equivalent uses `dict.get` or `defaultdict(int)`:

```python
from collections import defaultdict
counts = defaultdict(int)
for word in text.split():
    counts[word] += 1
```

Both are `O(n)`. Prefer `Counter` for readability and its `most_common`, addition, and subtraction operations.

### Q9. [Theory] How do default function arguments work, and what is the mutable default argument pitfall?

Default argument values are evaluated **once**, at function definition time, and stored on the function object — not re-evaluated on each call. If the default is a mutable object, it persists and accumulates state across calls.

```python
def add_item(item, bucket=[]):     # BUG: one shared list for all calls
    bucket.append(item)
    return bucket

add_item(1)   # [1]
add_item(2)   # [1, 2]   <- not [2]! the same list survived
```

The fix is the `None` sentinel idiom:

```python
def add_item(item, bucket=None):
    if bucket is None:
        bucket = []          # fresh list every call
    bucket.append(item)
    return bucket
```

This is one of the most common Python interview "gotchas." The same evaluation-once rule applies to defaults like `datetime.now()` (frozen at import) and to class attributes.

### Q10. [Theory] Explain `*args` and `**kwargs`.

In a function signature, `*args` collects extra **positional** arguments into a tuple, and `**kwargs` collects extra **keyword** arguments into a dict. They let a function accept a variable number of arguments.

```python
def f(a, *args, **kwargs):
    print(a)        # first positional
    print(args)     # tuple of the rest of the positionals
    print(kwargs)   # dict of the keyword args

f(1, 2, 3, x=10, y=20)
# 1
# (2, 3)
# {'x': 10, 'y': 20}
```

At the **call site**, the same `*`/`**` operators do the inverse — **unpacking**:

```python
nums = [1, 2, 3]
print(*nums)                 # print(1, 2, 3)

config = {"sep": "-", "end": "!\n"}
print("a", "b", **config)    # print("a", "b", sep="-", end="!\n")
```

This is the foundation of generic wrappers and decorators, which forward `*args, **kwargs` to the wrapped callable.

### Q11. [Coding] Merge two dictionaries. Show the modern approaches.

```python
a = {"x": 1, "y": 2}
b = {"y": 3, "z": 4}

# Python 3.9+: the | operator (right side wins on conflicts)
merged = a | b              # {'x': 1, 'y': 3, 'z': 4}

# In-place merge with |=
a |= b                      # a is mutated

# Unpacking (works 3.5+)
merged = {**a, **b}         # {'x': 1, 'y': 3, 'z': 4}
```

On key conflicts, the **rightmost** value wins. All are shallow merges — nested dicts are shared by reference, not deep-copied. Before 3.9 the idiom was `{**a, **b}` or `dict(a, **b)`.

### Q12. [Theory] What does the `with` statement do, and why use it?

`with` manages a **context** — a resource that must be set up and reliably torn down, even on exceptions. The object must implement the context-manager protocol: `__enter__` (called on entry, its return value is bound by `as`) and `__exit__` (always called on exit, including via exception or `return`).

```python
with open("data.txt") as f:
    data = f.read()
# f.close() is called automatically here, even if read() raised
```

This is equivalent to a `try/finally` but far cleaner. The benefit is guaranteed cleanup: you never leak file handles, sockets, or locks because you forgot to close them or an exception jumped over your cleanup code. `with threading.Lock():` guarantees the lock is released; `with conn.cursor() as cur:` guarantees the cursor closes.

### Q13. [Theory] What is the difference between `append`, `extend`, and `insert` on a list?

- **`append(x)`** adds `x` as a **single element** at the end — `O(1)` amortized.
- **`extend(iterable)`** adds **each element** of the iterable to the end (concatenation) — `O(k)` for `k` new items.
- **`insert(i, x)`** inserts `x` before index `i`, shifting later elements right — `O(n)`.

```python
xs = [1, 2, 3]
xs.append([4, 5])   # [1, 2, 3, [4, 5]]   <- one element that is a list
xs = [1, 2, 3]
xs.extend([4, 5])   # [1, 2, 3, 4, 5]     <- two elements
xs.insert(0, 0)     # [0, 1, 2, 3, 4, 5]
```

The classic mistake is `append`ing a list when you meant `extend`, producing a nested list. For frequent inserts/pops at the front, use `collections.deque` (`O(1)` both ends) instead of a list (`O(n)` at the front).

### Q14. [Coding] Read a large file line by line without loading it into memory.

```python
def count_matching_lines(path, needle):
    count = 0
    with open(path, encoding="utf-8") as f:
        for line in f:          # file objects are lazy iterators of lines
            if needle in line:
                count += 1
    return count
```

Iterating a file object yields one line at a time and never holds the whole file in memory, so this is `O(1)` in memory regardless of file size. **Avoid** `f.readlines()` or `f.read().split("\n")` on large files — those materialize everything. Always specify `encoding` explicitly; relying on the platform default is a portability bug.

### Q15. [Theory] What is the difference between `range`, `enumerate`, and `zip`?

- **`range(start, stop, step)`** is a lazy, memory-light sequence of integers. `range(1_000_000)` stores only start/stop/step, not a million ints.
- **`enumerate(iterable, start=0)`** yields `(index, value)` pairs — the Pythonic alternative to manually maintaining a counter.
- **`zip(*iterables)`** pairs up elements from multiple iterables, stopping at the shortest. `zip(a, b)` yields `(a[0], b[0]), (a[1], b[1]), ...`.

```python
for i, name in enumerate(names, start=1):
    print(i, name)

for name, score in zip(names, scores):
    print(name, score)

# zip is its own inverse with * (unzip)
pairs = [(1, 'a'), (2, 'b')]
nums, letters = zip(*pairs)   # (1, 2), ('a', 'b')
```

`enumerate` and `zip` return lazy iterators in Python 3. Use `zip(a, b, strict=True)` (3.10+) to raise if the iterables differ in length, catching silent truncation bugs.

### Q16. [Theory] What is `None`, and how is it different from `0`, `False`, or an empty string?

`None` is the single instance of the `NoneType` — Python's "no value" / "absence" sentinel, analogous to Java's `null`. It is distinct from falsy values like `0`, `0.0`, `""`, `[]`, and `False`: all of those are *values* that happen to be falsy, while `None` represents *the absence of a value*.

```python
def find(xs, target):
    for x in xs:
        if x == target:
            return x
    return None         # explicit "not found"

result = find(data, key)
if result is None:      # correct: distinguishes "not found"
    ...
if not result:          # WRONG if 0 or "" is a valid found value
    ...
```

Always test for `None` with `is None`, not `== None` or truthiness, because a legitimate result of `0`, `""`, or `[]` is falsy but is **not** `None`.

### Q17. [Coding] Flatten a nested list one level deep, and arbitrarily deep.

```python
from itertools import chain

# One level deep
nested = [[1, 2], [3, 4], [5]]
flat = list(chain.from_iterable(nested))   # [1, 2, 3, 4, 5]
flat = [x for sub in nested for x in sub]  # comprehension equivalent

# Arbitrary depth — recursion
def flatten(seq):
    for item in seq:
        if isinstance(item, (list, tuple)):
            yield from flatten(item)
        else:
            yield item

list(flatten([1, [2, [3, [4]], 5]]))   # [1, 2, 3, 4, 5]
```

`chain.from_iterable` is the idiomatic, efficient one-level flatten (no intermediate lists). For arbitrary nesting, a recursive generator with `yield from` is clean and memory-friendly; watch for Python's recursion limit (~1000) on pathologically deep structures.

### Q18. [Theory] What is the difference between `__str__` and `__repr__`?

Both produce a string for an object, but for different audiences:

- **`__repr__`** is for developers/debugging — ideally an unambiguous representation that, when possible, could be pasted back to recreate the object. It is what you see in the REPL and in containers. `repr(obj)` calls it.
- **`__str__`** is for end users — a readable, "nice" representation. `str(obj)`, `print(obj)`, and f-strings call it. If `__str__` is undefined, Python falls back to `__repr__`.

```python
from datetime import datetime
d = datetime(2026, 6, 30)
str(d)    # '2026-06-30 00:00:00'   (readable)
repr(d)   # 'datetime.datetime(2026, 6, 30, 0, 0)'  (recreatable)
```

Rule: always implement `__repr__` (debugging is universal); implement `__str__` only when a distinct user-facing form is useful. A good `__repr__` for a class looks like `f"{type(self).__name__}(name={self.name!r}, age={self.age})"`.

### Q19. [Theory] How does Python's `for` loop actually work under the hood?

`for x in obj:` is sugar over the **iterator protocol**. Python calls `iter(obj)` to get an iterator, then repeatedly calls `next()` on it, binding each result to `x`, until `StopIteration` is raised, which the loop catches silently.

```
for x in obj:        iterator = iter(obj)        # calls obj.__iter__()
    body             while True:
                         try:
                             x = next(iterator)   # calls iterator.__next__()
                         except StopIteration:
                             break
                         body
```

Any object is **iterable** if it defines `__iter__` returning an **iterator** (an object with `__next__`). This protocol is why `for` works uniformly over lists, dicts, files, generators, and your own classes — they all expose the same two methods.

### Q20. [Practical] How do you create and use a virtual environment, and why?

A virtual environment is an isolated Python installation with its own `site-packages`, so each project pins its own dependency versions without polluting the system Python or other projects.

```bash
# Create (built-in venv module)
python -m venv .venv

# Activate
source .venv/bin/activate        # macOS / Linux
.venv\Scripts\activate           # Windows

# Install dependencies
pip install requests==2.32.0
pip freeze > requirements.txt    # snapshot exact versions
pip install -r requirements.txt  # reproduce elsewhere

deactivate                       # leave the venv
```

Without isolation, two projects needing different versions of the same library conflict, and you risk `sudo pip install` breaking system tools. In 2026, many teams use **`uv`** (a fast Rust-based installer/resolver) or `poetry`/`pdm` for lockfile-based, reproducible environments, but `venv` + `pip` is the universal baseline every Python developer must know.

### Q21. [Theory] What is the difference between a shallow copy and a deep copy?

A **shallow copy** creates a new outer container but the inner elements are still **shared references**. A **deep copy** recursively copies everything, so the result shares nothing with the original.

```python
import copy
original = [[1, 2], [3, 4]]

shallow = copy.copy(original)      # or original[:] / list(original)
shallow[0].append(99)
print(original)                    # [[1, 2, 99], [3, 4]]  <- leaked!

deep = copy.deepcopy(original)
deep[0].append(99)
print(original)                    # unchanged
```

Slicing (`xs[:]`), `list(xs)`, and `dict(d)` all make shallow copies. Use `copy.deepcopy` only when nested mutation isolation matters — it is slower and can choke on cyclic or unpicklable objects. For flat collections of immutables, shallow copy is sufficient and cheaper.

## 🟡 Intermediate (3–7 yrs)

### Q22. [Theory] What is the GIL, and what are its practical implications?

The **Global Interpreter Lock** is a mutex in CPython that allows only **one thread to execute Python bytecode at a time**. It exists because CPython's memory management (reference counting) is not thread-safe; the GIL makes refcount updates atomic without per-object locks.

Implications:

- **CPU-bound multithreading does not scale.** Two threads doing pure-Python computation run effectively serially — you get concurrency, not parallelism. Use **`multiprocessing`** (separate processes, each with its own GIL) or offload to C extensions/NumPy that release the GIL.
- **I/O-bound multithreading works well.** The GIL is released during blocking I/O (file, network, `time.sleep`), so threads overlap their waits. `threading` or `asyncio` is appropriate here.

```
CPU-bound  -> multiprocessing (true parallelism) or C extension
I/O-bound  -> threading or asyncio (overlap the waiting)
```

In 2026 this is nuanced: **PEP 703** introduced an experimental **free-threaded** CPython build (3.13+, "no-GIL") that removes the GIL for real multicore Python, and PEP 684 adds per-interpreter GILs. These are not yet the default, so the GIL remains the assumption for most production code. Note the GIL is a CPython implementation detail — Jython and IronPython never had it.

### Q23. [Theory] Explain generators and the `yield` keyword.

A **generator** is a function that uses `yield`. Calling it does not run the body; it returns a generator object. Each `next()` runs the function until the next `yield`, which produces a value and **suspends** the function with all its local state frozen. The next `next()` resumes right after that `yield`.

```python
def countdown(n):
    while n > 0:
        yield n          # produce n, suspend here
        n -= 1           # resumes here on the next next()

gen = countdown(3)
next(gen)   # 3
next(gen)   # 2
list(countdown(3))   # [3, 2, 1]
```

Generators give you **lazy evaluation** and **constant memory** for arbitrarily large (even infinite) sequences, because values are produced on demand rather than stored. They are also the basis of coroutines. `yield from subgen` delegates to a sub-generator, forwarding its values (and `send`/`throw`). A generator can also receive values via `.send()` and be closed via `.close()`.

### Q24. [Theory] What is a decorator, and how does it work?

A decorator is a callable that takes a function (or class) and returns a replacement, used to layer behavior — logging, timing, caching, access control — without editing the wrapped function. `@decorator` above a `def` is sugar for `func = decorator(func)`.

```python
import functools, time

def timed(func):
    @functools.wraps(func)            # preserves name, docstring, signature
    def wrapper(*args, **kwargs):
        start = time.perf_counter()
        try:
            return func(*args, **kwargs)
        finally:
            elapsed = time.perf_counter() - start
            print(f"{func.__name__} took {elapsed:.4f}s")
    return wrapper

@timed
def slow():
    time.sleep(0.1)
```

`@functools.wraps` is essential — without it the wrapper masks the original's `__name__`, `__doc__`, and signature, breaking introspection and other decorators. To pass arguments to a decorator you add a third layer (a decorator factory): `def repeat(n): def deco(func): ... return deco`.

### Q25. [Coding] Write a decorator that caches results (memoization).

```python
import functools

# The stdlib way — almost always what you want:
@functools.lru_cache(maxsize=128)
def fib(n):
    return n if n < 2 else fib(n - 1) + fib(n - 2)

fib(100)            # fast: each n computed once
fib.cache_info()    # CacheInfo(hits=..., misses=..., maxsize=128, currsize=...)
```

Implementing it by hand to show you understand the mechanism:

```python
def memoize(func):
    cache = {}
    @functools.wraps(func)
    def wrapper(*args):
        if args not in cache:        # args must be hashable
            cache[args] = func(*args)
        return cache[args]
    return wrapper
```

`lru_cache` turns the exponential naive Fibonacci into linear time by caching. Caveats: arguments must be **hashable** (no lists/dicts), the cache holds references and can grow memory (`maxsize=None` is unbounded), and it is per-process. Use `functools.cache` (3.9+) for an unbounded `lru_cache(maxsize=None)`.

### Q26. [Coding] Write a context manager two ways: a class and `contextlib`.

```python
# 1) Class implementing the protocol
class Timer:
    def __enter__(self):
        import time
        self.start = time.perf_counter()
        return self
    def __exit__(self, exc_type, exc_val, exc_tb):
        import time
        self.elapsed = time.perf_counter() - self.start
        return False        # False -> do not suppress exceptions

with Timer() as t:
    do_work()
print(t.elapsed)
```

```python
# 2) contextlib.contextmanager — generator-based, less boilerplate
from contextlib import contextmanager
import time

@contextmanager
def timer():
    start = time.perf_counter()
    try:
        yield                      # everything before yield = __enter__
    finally:
        print(time.perf_counter() - start)   # after yield = __exit__

with timer():
    do_work()
```

`__exit__` receives exception info; returning a truthy value **suppresses** the exception (use deliberately). The `finally` in the generator form guarantees cleanup even if the body raises. Use the generator decorator for simple cases; use a class when you need a reusable object with state or multiple methods.

### Q27. [Theory] Explain closures and the LEGB scope rule.

Python resolves names using **LEGB**, searched in order:

- **L**ocal — names assigned in the current function.
- **E**nclosing — names in any enclosing function's local scope (for nested functions).
- **G**lobal — module-level names.
- **B**uilt-in — names in the `builtins` module (`len`, `range`, ...).

A **closure** is a nested function that captures variables from its enclosing scope and keeps them alive after the outer function returns.

```python
def make_counter():
    count = 0
    def increment():
        nonlocal count      # rebind the enclosing variable
        count += 1
        return count
    return increment

c = make_counter()
c(); c()    # 1, then 2 — count persists in the closure
```

Key keywords: `global x` lets you rebind a module-level name; `nonlocal x` lets you rebind an enclosing (non-global) name. Without them, **assigning** to a name makes it local, which causes the classic `UnboundLocalError` when you read-then-assign a name you thought was global.

### Q28. [Coding] What does this loop-and-closure code print, and how do you fix it?

```python
funcs = [lambda: i for i in range(3)]
print([f() for f in funcs])     # [2, 2, 2]  — not [0, 1, 2]!
```

Each lambda closes over the **variable** `i`, not its value at creation time. All three share the same `i`, which is `2` after the loop ends. Closures capture by reference. Two fixes:

```python
# Fix 1: bind the current value as a default argument (evaluated at def time)
funcs = [lambda i=i: i for i in range(3)]
print([f() for f in funcs])     # [0, 1, 2]

# Fix 2: a factory that creates a fresh scope per iteration
def make(i):
    return lambda: i
funcs = [make(i) for i in range(3)]
```

This is a top interview discriminator. The same bug appears with threads/callbacks created in loops. Default-argument binding is the most common idiomatic fix.

### Q29. [Theory] What is `async`/`await`, and how does `asyncio` achieve concurrency?

`async def` defines a **coroutine**; `await` suspends it until an awaitable completes, yielding control back to the **event loop**, which can run other coroutines meanwhile. This is **cooperative, single-threaded concurrency**: one thread interleaves many I/O-bound tasks by switching at every `await`. There is no preemption — a coroutine runs until it voluntarily awaits.

```python
import asyncio

async def fetch(name, delay):
    await asyncio.sleep(delay)      # non-blocking; yields to the loop
    return f"{name} done"

async def main():
    # run concurrently, not sequentially
    results = await asyncio.gather(
        fetch("a", 1), fetch("b", 1), fetch("c", 1)
    )
    print(results)                  # ~1s total, not 3s

asyncio.run(main())
```

It shines for high-concurrency I/O (thousands of network connections) where threads would be too heavy. The cardinal rule: **never call blocking code** (`time.sleep`, blocking DB drivers, CPU-heavy loops) inside a coroutine — it stalls the whole loop. Offload blocking work with `await asyncio.to_thread(blocking_fn)` or `loop.run_in_executor`. async vs threads: async = many I/O tasks, explicit await points; threads = simpler integration with blocking libraries.

### Q30. [Theory] What are type hints, and does Python enforce them at runtime?

Type hints (PEP 484) annotate parameters, returns, and variables with expected types. Python **does not enforce them at runtime** — the interpreter ignores them for execution. They are documentation and tooling fuel: static checkers (**mypy**, **pyright**/Pylance), IDEs, and frameworks read them.

```python
from typing import Optional

def greet(name: str, times: int = 1) -> str:
    return f"Hello {name}! " * times

ages: dict[str, int] = {}
maybe: Optional[int] = None        # int | None
```

Modern syntax (3.9+/3.10+): use built-in generics `list[int]`, `dict[str, int]` (no need for `typing.List`), and `X | Y` unions instead of `Union`/`Optional`. Frameworks like **Pydantic** and **FastAPI** *do* use hints at runtime for validation, but that is library behavior, not the language. Run `mypy --strict` in CI to catch type errors before production.

### Q31. [Theory] What is a dataclass, and when would you use one?

`@dataclass` (PEP 557, 3.7+) auto-generates boilerplate — `__init__`, `__repr__`, `__eq__` — from class-level annotated fields, so you write data-holder classes declaratively.

```python
from dataclasses import dataclass, field

@dataclass(frozen=True, slots=True)
class Point:
    x: int
    y: int
    tags: list[str] = field(default_factory=list)   # avoids shared-mutable-default bug

p = Point(1, 2)
p2 = Point(1, 2)
p == p2          # True  -> generated __eq__ compares by value
# p.x = 5        -> FrozenInstanceError (frozen=True makes it immutable/hashable)
```

Key options: `frozen=True` makes instances immutable and hashable; `slots=True` (3.10+) cuts memory and speeds attribute access by skipping `__dict__`; `order=True` generates comparison operators; `field(default_factory=...)` is the correct way to default to a mutable. Use dataclasses for plain data records; for validation/serialization reach for Pydantic; for tiny immutable records, a `NamedTuple` may be enough.

### Q32. [Theory] What is a `namedtuple`, and how does it compare to a dataclass?

`collections.namedtuple` (and the typed `typing.NamedTuple`) creates a **tuple subclass** with named fields, giving you attribute access while remaining a lightweight, immutable, indexable, iterable tuple.

```python
from typing import NamedTuple

class Point(NamedTuple):
    x: int
    y: int

p = Point(1, 2)
p.x, p[0]        # 1, 1  -> both attribute and index access
px, py = p       # tuple unpacking works
p._replace(x=9)  # Point(x=9, y=2) -> new instance (immutable)
```

`namedtuple` vs `dataclass`:

| Aspect | namedtuple | dataclass |
|---|---|---|
| Mutability | always immutable | mutable (or `frozen=True`) |
| Underlying | tuple subclass | plain object |
| Indexing/unpacking | yes (it's a tuple) | no |
| Default values | limited | full, incl. factories |
| Memory | very low | low with `slots=True` |

Use a namedtuple for small immutable records that benefit from tuple behavior (return values, dict-light data). Use a dataclass when you need mutability, methods, or richer defaults.

### Q33. [Coding] Demonstrate the key `itertools` functions with examples.

```python
import itertools as it

# Infinite iterators
it.count(10, 2)                 # 10, 12, 14, ...   (take with islice)
it.cycle("AB")                  # A, B, A, B, ...
it.repeat(0, 3)                 # 0, 0, 0

# Combinatoric
list(it.permutations([1, 2, 3], 2))   # all ordered pairs
list(it.combinations([1, 2, 3], 2))   # [(1,2), (1,3), (2,3)]
list(it.product([0, 1], repeat=2))    # cartesian: [(0,0),(0,1),(1,0),(1,1)]

# Grouping / windowing
list(it.chain([1, 2], [3, 4]))                  # [1, 2, 3, 4]
list(it.islice(it.count(), 5))                  # first 5 of an infinite source
list(it.accumulate([1, 2, 3, 4]))               # running sum: [1, 3, 6, 10]
[k for k, g in it.groupby("aabbbc")]            # ['a', 'b', 'c'] (consecutive!)
list(it.batched("ABCDEFG", 3))                  # 3.12+: [('A','B','C'),('D','E','F'),('G',)]
```

`itertools` builds memory-efficient iterator pipelines. The key gotcha: `groupby` only groups **consecutive** equal keys, so you usually sort first. `islice` is how you safely take a finite prefix from an infinite iterator.

### Q34. [Coding] Show the most useful `functools` tools.

```python
import functools

# reduce — fold a sequence to a single value
functools.reduce(lambda acc, x: acc * x, [1, 2, 3, 4], 1)   # 24

# partial — pre-bind arguments to make a new callable
from functools import partial
int2 = partial(int, base=2)
int2("1010")                                                # 10

# cache / lru_cache — memoization
@functools.cache
def expensive(n): ...

# wraps — preserve metadata in decorators (see decorator question)

# singledispatch — function overloading by first-argument type
@functools.singledispatch
def describe(x): return f"generic {x!r}"
@describe.register
def _(x: int): return f"int {x}"
@describe.register
def _(x: list): return f"list of {len(x)}"

# cached_property — compute once per instance, then cache on the instance
class Data:
    @functools.cached_property
    def stats(self): ...   # heavy compute runs once
```

`partial` is great for adapting callbacks; `singledispatch` gives clean type-based dispatch without `isinstance` chains; `cached_property` memoizes per-instance lazily.

### Q35. [Coding] Show four high-value `collections` types.

```python
from collections import defaultdict, Counter, deque, OrderedDict

# defaultdict — auto-create missing values, no KeyError
groups = defaultdict(list)
for name, dept in people:
    groups[dept].append(name)      # no need to check/initialize the key

# Counter — multiset / frequency counting
Counter("mississippi").most_common(2)   # [('s', 4), ('i', 4)]

# deque — O(1) appends/pops at BOTH ends; ideal for queues, sliding windows
dq = deque(maxlen=3)               # bounded ring buffer
for x in range(5): dq.append(x)    # deque([2, 3, 4]) — old items drop off
dq.appendleft(0); dq.pop(); dq.popleft()

# OrderedDict — now rarely needed (dicts are insertion-ordered since 3.7),
# but it has move_to_end() and order-sensitive equality (useful for LRU).
```

`deque` is the right tool for BFS queues and fixed-size sliding windows (a list's `pop(0)` is `O(n)`; deque's `popleft` is `O(1)`). `defaultdict` and `Counter` eliminate the most common dict boilerplate.

### Q36. [Theory] How does exception handling work, and what is the `else`/`finally` structure?

```python
try:
    result = risky()
except (ValueError, KeyError) as e:    # catch specific types; tuple for multiple
    handle(e)
except Exception as e:                 # broader fallback
    log(e)
    raise                              # re-raise, preserving the traceback
else:
    use(result)                        # runs ONLY if no exception occurred
finally:
    cleanup()                          # ALWAYS runs (success, exception, or return)
```

- **`else`** runs only if the `try` block did not raise — use it to keep the `try` block minimal (only the line that can fail).
- **`finally`** always runs, ideal for cleanup (though context managers are usually cleaner).

Best practices: catch the **narrowest** exception type; never use a bare `except:` (it swallows `KeyboardInterrupt`/`SystemExit`); use `raise ... from e` to chain causes; and prefer EAFP ("easier to ask forgiveness than permission" — try and catch) over LBYL ("look before you leap") in idiomatic Python. Python 3.11+ adds **exception groups** (`except*`) for handling multiple concurrent errors from `asyncio.TaskGroup`.

### Q37. [Theory] What is the difference between `@staticmethod`, `@classmethod`, and an instance method?

- **Instance method** — first parameter is `self`, the instance. Can read/modify instance and class state.
- **`@classmethod`** — first parameter is `cls`, the class. Cannot touch instance state but can access/modify class state. Common use: **alternative constructors**.
- **`@staticmethod`** — no implicit first parameter. Just a function namespaced inside the class; cannot access `self` or `cls`. Use for logically related helpers.

```python
class Pizza:
    def __init__(self, toppings):
        self.toppings = toppings

    @classmethod
    def margherita(cls):              # alternative constructor
        return cls(["tomato", "mozzarella"])

    @staticmethod
    def is_valid_size(size):          # utility, no state needed
        return size in {"S", "M", "L"}

Pizza.margherita()        # cls -> works correctly even for subclasses
Pizza.is_valid_size("M")  # True
```

`classmethod` is preferred for factory methods because `cls` respects subclasses — `SubPizza.margherita()` returns a `SubPizza`. `staticmethod` signals "this needs neither instance nor class."

### Q38. [Theory] How are strings encoded? Explain `str` vs `bytes`.

In Python 3, **`str`** is a sequence of Unicode **code points** (text); **`bytes`** is a sequence of raw 8-bit **bytes** (binary). They are not interchangeable — you must explicitly convert across the boundary using an **encoding** (almost always UTF-8).

```python
text = "café"
data = text.encode("utf-8")     # str -> bytes: b'caf\xc3\xa9' (5 bytes, é is 2)
data.decode("utf-8")            # bytes -> str: 'café'

len(text)    # 4 code points
len(data)    # 5 bytes
```

Rules: **decode** bytes to str at your program's input boundary (reading files/network), work in `str` internally, **encode** back to bytes at output. Mixing them raises `TypeError`. The infamous `UnicodeDecodeError` happens when you decode bytes with the wrong codec. Always be explicit about encoding; never assume the platform default (which differs between Windows and Linux).

### Q39. [Practical] How do you profile and optimize slow Python code?

Measure first — never guess. A tiered approach:

```python
# 1) Coarse timing
import time
start = time.perf_counter(); work(); print(time.perf_counter() - start)

# 2) Microbenchmarks
import timeit
timeit.timeit("'-'.join(str(n) for n in range(100))", number=10000)

# 3) Function-level profiling (where is time spent?)
import cProfile
cProfile.run("main()", sort="cumulative")

# 4) Line-level (line_profiler) and memory (memray / tracemalloc)
```

Once you've found the hotspot, optimization options in rough order: use better algorithms/data structures (a `set` for membership, a `dict` for lookups); use built-ins and comprehensions (they run in C); vectorize with **NumPy** for numeric work; cache with `lru_cache`; move CPU-bound work to `multiprocessing` or a C/Cython/Rust (PyO3) extension; use `__slots__` to cut memory. The order matters: algorithmic wins dwarf micro-optimizations. And always re-measure after each change.

### Q40. [Coding] Implement an LRU cache from scratch (without `lru_cache`).

```python
from collections import OrderedDict

class LRUCache:
    def __init__(self, capacity: int):
        self.capacity = capacity
        self.cache: OrderedDict[int, int] = OrderedDict()

    def get(self, key: int) -> int:
        if key not in self.cache:
            return -1
        self.cache.move_to_end(key)        # mark most-recently used
        return self.cache[key]

    def put(self, key: int, value: int) -> None:
        if key in self.cache:
            self.cache.move_to_end(key)
        self.cache[key] = value
        if len(self.cache) > self.capacity:
            self.cache.popitem(last=False)  # evict least-recently used (front)
```

An `OrderedDict` gives `O(1)` `get`/`put` while tracking recency: `move_to_end` promotes a key, `popitem(last=False)` evicts the oldest. This is the canonical "implement LRU" answer and demonstrates why `OrderedDict` still earns its place despite ordinary dicts being ordered.

### Q41. [Theory] What is duck typing, and how does it relate to ABCs and Protocols?

**Duck typing**: "if it walks like a duck and quacks like a duck, it's a duck." Python cares about whether an object *supports the operations you use*, not its declared class. A function that calls `obj.read()` works with any object having a `read` method — a file, a `StringIO`, a socket.

```python
def total_length(items):
    return sum(len(x) for x in items)   # works for any objects supporting len()
```

Two ways to formalize the expected "shape":

- **ABCs** (`abc` module / `collections.abc`) — nominal: a class explicitly registers/subclasses and must implement abstract methods, enforced at instantiation.
- **Protocols** (`typing.Protocol`, PEP 544) — **structural** typing checked statically: any class with the right methods matches, no inheritance required. This is duck typing the type checker can verify.

```python
from typing import Protocol

class Readable(Protocol):
    def read(self) -> str: ...

def load(src: Readable) -> str:   # any object with read() type-checks
    return src.read()
```

Protocols are the modern, Pythonic way to type-annotate duck-typed interfaces.

### Q42. [Practical] How do you structure a Python package for distribution in 2026?

A modern, `pyproject.toml`-based layout (PEP 517/518/621), using the recommended **src layout**:

```
myproject/
├── pyproject.toml          # single source of metadata + build config
├── README.md
├── LICENSE
├── src/
│   └── mypackage/
│       ├── __init__.py
│       └── core.py
└── tests/
    └── test_core.py
```

```toml
[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[project]
name = "mypackage"
version = "1.0.0"
requires-python = ">=3.10"
dependencies = ["requests>=2.32"]
```

```bash
pip install build twine        # or use uv / hatch / poetry
python -m build                # produces wheel + sdist in dist/
twine upload dist/*            # publish to PyPI
```

Key points: `pyproject.toml` has replaced the legacy `setup.py`/`setup.cfg` as the single config file; the **src layout** prevents accidentally importing the package from the working directory instead of the installed version (catching packaging bugs early); build via PEP 517 backends (hatchling, flit, setuptools, poetry-core); and publish wheels (binary, fast install) plus an sdist (source fallback).

## 🟠 Advanced (8–12 yrs)

### Q43. [Theory] Explain Python's memory management: reference counting and the cyclic GC.

CPython uses **two** mechanisms working together:

1. **Reference counting** (primary). Every object has a refcount; assignments increment it, deletions/rebinds decrement it. When it hits zero the object is freed **immediately and deterministically**. This is why `with` blocks and `del` reclaim resources promptly.

2. **Cyclic garbage collector** (`gc` module, generational). Refcounting alone cannot reclaim **reference cycles** (`a.ref = b; b.ref = a`), because the counts never reach zero. The cyclic GC periodically finds and collects unreachable cycles using a generational mark-and-sweep over three generations (new objects collected most often, survivors promoted).

```python
import gc
a = []; a.append(a)        # a cycle: a references itself
del a                       # refcount of the list is still 1 (self-reference)
gc.collect()                # the cyclic GC reclaims it
```

Implications: most memory is freed instantly by refcounting (great locality, predictable); only cycles need the GC. Cycles delay reclamation and add GC pauses, so for hot paths you sometimes break cycles manually or use **`weakref`** (references that don't increment the count) for back-pointers. The free-threaded build (PEP 703) replaces naive refcounting with biased/deferred reference counting to stay thread-safe without a GIL.

### Q44. [Theory] What is the MRO, and how does C3 linearization resolve multiple inheritance?

The **Method Resolution Order** is the linear sequence of classes Python searches to resolve an attribute/method on an instance. With multiple inheritance, Python computes it via **C3 linearization**, which guarantees: a class precedes its parents, parents appear in the order listed, and the result is monotonic (consistent across the hierarchy).

The classic **diamond**:

```
      A
     / \
    B   C
     \ /
      D
```

```python
class A: pass
class B(A): pass
class C(A): pass
class D(B, C): pass

D.__mro__   # (D, B, C, A, object)
```

C3 ensures `A` appears **once**, after both `B` and `C`. This is what makes cooperative multiple inheritance work: each method calls `super()`, and `super()` follows the **MRO** (not the literal parent), so `D() -> B -> C -> A` is traversed exactly once. If the requested ordering is impossible (e.g., conflicting parent orders), Python raises `TypeError` at class-creation time. You can inspect it with `Cls.__mro__` or `Cls.mro()`.

### Q45. [Theory] How does `super()` really work, and why is it "next in MRO," not "the parent"?

`super()` does **not** mean "my parent class." It returns a proxy that dispatches to the **next class in the MRO** of the *instance's actual type*, starting after the current class. This is essential for cooperative multiple inheritance, where the "next" class depends on the runtime type, not the static hierarchy.

```python
class A:
    def __init__(self): print("A"); 
class B(A):
    def __init__(self): print("B"); super().__init__()
class C(A):
    def __init__(self): print("C"); super().__init__()
class D(B, C):
    def __init__(self): print("D"); super().__init__()

D()
# D, B, C, A   <- B's super() goes to C, not directly to A,
#                 because the MRO of D is [D, B, C, A].
```

If `B` had called `A.__init__(self)` directly instead of `super().__init__()`, `C` would be **skipped** and possibly initialized twice or not at all. The rule for correct cooperative classes: every method in the chain calls `super()`, and they share a compatible signature (often using `**kwargs` to forward unknown args). `super()` (no args, 3.x) auto-fills the current class and `self` from the call frame.

### Q46. [Theory] What is a metaclass, and when would you actually use one?

A metaclass is "the class of a class": just as an object is an instance of a class, a class is an instance of its metaclass (default `type`). Defining a class actually *calls* the metaclass, which controls class **creation** — letting you inspect, modify, or validate the class as it is built.

```python
class ValidatedMeta(type):
    def __new__(mcs, name, bases, namespace):
        cls = super().__new__(mcs, name, bases, namespace)
        if 'handle' not in namespace and bases:    # enforce a contract
            raise TypeError(f"{name} must define handle()")
        return cls

class Plugin(metaclass=ValidatedMeta):
    def handle(self): ...
```

Real uses: enforcing API contracts across a class hierarchy, auto-registering subclasses into a registry, ORM field declaration (Django models, SQLAlchemy), and API frameworks. **But** metaclasses are an advanced, rarely-needed tool — Tim Peters' rule: "if you wonder whether you need them, you don't." Modern alternatives cover most cases more simply: `__init_subclass__` (3.6+) for subclass hooks/registration, `__set_name__` for descriptors, class decorators for post-creation modification, and ABCs for interface enforcement. Reach for a metaclass only when you must intervene in class creation itself.

### Q47. [Theory] Explain descriptors and the `__get__`/`__set__`/`__delete__` protocol.

A **descriptor** is an object that customizes attribute access by implementing `__get__`, `__set__`, and/or `__delete__`. When such an object is a **class attribute**, Python routes attribute access through it. Descriptors are the machinery behind `property`, `classmethod`, `staticmethod`, `functools.cached_property`, and ORM fields.

```python
class Positive:
    def __set_name__(self, owner, name):
        self.name = f"_{name}"
    def __get__(self, obj, objtype=None):
        if obj is None: return self
        return getattr(obj, self.name)
    def __set__(self, obj, value):
        if value <= 0:
            raise ValueError("must be positive")
        setattr(obj, self.name, value)

class Account:
    balance = Positive()        # validated attribute

a = Account()
a.balance = 100     # routed through Positive.__set__
# a.balance = -5    -> ValueError
```

**Data descriptors** (define `__set__` or `__delete__`) take precedence over instance `__dict__`; **non-data descriptors** (only `__get__`) are overridden by instance attributes — this precedence is exactly why methods (non-data) can be shadowed but `property` (data) cannot. Descriptors are the right tool for reusable, validated, computed, or lazy attributes shared across many classes.

### Q48. [Coding] Implement a thread-safe singleton, and discuss whether you should.

```python
import threading

class Singleton:
    _instance = None
    _lock = threading.Lock()

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:                  # first check (no lock cost)
            with cls._lock:
                if cls._instance is None:          # double-checked under lock
                    cls._instance = super().__new__(cls)
        return cls._instance
```

The **double-checked locking** pattern avoids taking the lock on every instantiation after the first. The outer check is fast; only the rare uninitialized case acquires the lock, and the inner check guards against a race between the outer check and lock acquisition.

That said, in Python the singleton pattern is often unnecessary: a **module** is itself a singleton (imported once, cached in `sys.modules`), so module-level state or a module-level instance is the idiomatic approach. Prefer a module global or dependency injection over a singleton class; singletons complicate testing (global mutable state) and are usually a code smell carried over from languages without module-level state.

### Q49. [Theory] Compare `multiprocessing`, `threading`, and `asyncio`. When do you pick each?

```
                CPU-bound          I/O-bound (few)      I/O-bound (massive)
              ┌──────────────┬────────────────────┬──────────────────────┐
  Use         │ multiprocessing │ threading        │ asyncio              │
  Parallelism │ true (N cores)  │ none (GIL)       │ none (1 thread)      │
  Overhead    │ high (processes)│ medium (threads) │ low (coroutines)     │
  Sharing     │ IPC/pickle      │ shared memory    │ shared memory        │
              └──────────────┴────────────────────┴──────────────────────┘
```

- **`multiprocessing`** — separate processes, each with its own interpreter and GIL, so it achieves **true CPU parallelism**. Cost: process startup overhead and the need to pickle data across the boundary. Use for CPU-bound work (numeric crunching, image processing). `concurrent.futures.ProcessPoolExecutor` is the high-level interface.
- **`threading`** — real OS threads sharing memory, but the GIL serializes Python bytecode, so it helps only when threads spend time **waiting on I/O** (the GIL is released during blocking calls). Use for moderate I/O concurrency or integrating blocking libraries. Beware shared-state races — use locks.
- **`asyncio`** — single-threaded cooperative coroutines, lowest overhead, scales to **tens of thousands** of concurrent I/O operations. Use for high-concurrency network servers/clients. Requires async-aware libraries throughout the stack.

The free-threaded build (3.13+) may eventually let `threading` achieve CPU parallelism, changing this calculus.

### Q50. [Practical] How do you debug a memory leak in a long-running Python service?

"Leak" in Python usually means **unbounded growth** of reachable objects (you're still holding references), not classic C leaks. A systematic approach:

```python
import tracemalloc
tracemalloc.start()
snap1 = tracemalloc.take_snapshot()
# ... run the workload ...
snap2 = tracemalloc.take_snapshot()
for stat in snap2.compare_to(snap1, "lineno")[:10]:
    print(stat)            # top allocators by growth, with file:line
```

Steps: (1) confirm growth with `tracemalloc` snapshots or **memray**; (2) find what type is accumulating with `gc.get_objects()` / `objgraph.show_growth()`; (3) trace **who holds the references** with `objgraph.show_backrefs()`. Common culprits: an ever-growing global list/dict or cache (unbounded `lru_cache`), accumulated logging handlers, registered callbacks/observers never deregistered, closures capturing large objects, `__del__` methods preventing cyclic collection, and C-extension leaks. Fixes include bounding caches (`maxsize`), using `weakref` for back-references and observer registries, and explicitly clearing collections. Tune or inspect cyclic GC with the `gc` module, but the real fix is almost always removing the lingering reference.

### Q51. [Behavioral] Tell me about a time you made a significant performance or reliability improvement to a Python system.

Use **STAR**, grounded in Python specifics so it is credible:

- **Situation** — e.g., "A data-ingestion service processed nightly batches that grew to take 6+ hours, threatening the SLA."
- **Task** — "I owned cutting runtime by at least half without a rewrite."
- **Action** — "I profiled with cProfile and found 70% of time in a hot loop doing per-row DB lookups. I (1) replaced repeated `O(n)` list membership tests with a `set`, (2) batched the DB queries and cached results with `lru_cache`, and (3) moved the CPU-bound parsing to a `ProcessPoolExecutor` since the GIL was serializing it. I added a regression benchmark so we'd catch slowdowns in CI."
- **Result** — "Runtime dropped from 6 hours to ~40 minutes; I documented the profiling methodology so the team could repeat it."

What interviewers listen for: you **measured before optimizing**, you understood *why* Python was slow (GIL, `O(n)` lookups, I/O), you chose the right tool (set/dict, caching, multiprocessing), and you guarded the win with a benchmark. Show judgment about not over-engineering and about the maintainability trade-offs of each change.

### Q52. [Theory] What are `__slots__`, and what are the trade-offs?

By default every instance stores its attributes in a per-instance `__dict__`, which is flexible but memory-heavy. Declaring `__slots__` tells Python to allocate a fixed, array-like layout for the named attributes instead, **eliminating the per-instance `__dict__`**.

```python
class Point:
    __slots__ = ("x", "y")      # no __dict__; only x and y allowed
    def __init__(self, x, y):
        self.x, self.y = x, y

p = Point(1, 2)
# p.z = 3   -> AttributeError: 'Point' object has no attribute 'z'
```

Benefits: significantly **less memory** per instance (often 40–50% for small objects — important when you have millions) and slightly **faster attribute access**. Trade-offs/gotchas: you **cannot add new attributes** not in `__slots__`; no `__dict__` means some tools (and naive `__dict__`-based code) break; multiple inheritance with slots is finicky (only one parent may have a non-empty `__slots__` of overlapping names); and `weakref` needs `"__weakref__"` added to slots explicitly. Use `__slots__` for high-cardinality, fixed-shape objects; skip it for everyday classes where flexibility matters. `@dataclass(slots=True)` (3.10+) generates them for you.

### Q53. [Coding] Implement a retry decorator with exponential backoff.

```python
import functools, time, random

def retry(max_attempts=3, base_delay=0.5, exceptions=(Exception,)):
    def decorator(func):
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            for attempt in range(1, max_attempts + 1):
                try:
                    return func(*args, **kwargs)
                except exceptions as e:
                    if attempt == max_attempts:
                        raise                      # exhausted; propagate
                    delay = base_delay * (2 ** (attempt - 1))
                    delay += random.uniform(0, delay * 0.1)   # jitter
                    time.sleep(delay)
        return wrapper
    return decorator

@retry(max_attempts=5, base_delay=0.2, exceptions=(ConnectionError, TimeoutError))
def fetch(url): ...
```

This is a three-layer **decorator factory**: outer takes config, middle takes the function, inner is the wrapper. Backoff doubles each attempt (`0.2, 0.4, 0.8, ...`); **jitter** spreads retries so many clients don't retry in lockstep (the thundering-herd problem). Production-grade additions: catch only **retriable** exceptions (never retry a 400/validation error), cap the maximum delay, respect a total timeout/deadline, and log each retry. For async code, swap `time.sleep` for `await asyncio.sleep`.

### Q54. [Theory] Explain the difference between `__new__` and `__init__`.

`__new__` is the **constructor** — a static method that actually creates and returns the new instance. `__init__` is the **initializer** — it configures the already-created instance and must return `None`. The flow on `MyClass(args)` is: `__new__(cls, args)` allocates and returns the object, then `__init__(self, args)` initializes it.

```python
class Cached:
    _pool = {}
    def __new__(cls, key):
        if key in cls._pool:
            return cls._pool[key]        # return an existing instance
        obj = super().__new__(cls)
        cls._pool[key] = obj
        return obj
    def __init__(self, key):
        self.key = key                   # NOTE: runs even on cache hits!
```

You rarely override `__new__`. You need it to: subclass **immutable** types (`int`, `str`, `tuple` — you must set the value at creation, before `__init__`), implement instance caching/interning or singletons, or return an instance of a different class. A subtle gotcha shown above: if `__new__` returns a cached instance, `__init__` **still runs** on it, so re-initialization can clobber state — guard against it. If `__new__` returns an object that is not an instance of `cls`, `__init__` is skipped.

### Q55. [Practical] How do you write effective tests in Python, and what does pytest give you?

`pytest` is the de facto standard; it favors plain `assert` (with rich introspection on failure) over `unittest`'s `assertEqual` boilerplate.

```python
import pytest

# Plain assert — pytest rewrites it to show both sides on failure
def test_add():
    assert add(2, 3) == 5

# Fixtures — reusable setup/teardown via dependency injection
@pytest.fixture
def db():
    conn = connect()
    yield conn          # everything after yield is teardown
    conn.close()

def test_query(db):     # 'db' is injected by name
    assert db.count() == 0

# Parametrization — one test, many cases
@pytest.mark.parametrize("inp,expected", [(2, 4), (3, 9), (-1, 1)])
def test_square(inp, expected):
    assert square(inp) == expected
```

Key tooling: **fixtures** (composable, scoped setup via DI), **parametrize** (table-driven tests), **`monkeypatch`** and `unittest.mock` for isolating dependencies, `pytest.raises` for asserting exceptions, markers for selecting subsets, and `pytest-cov` for coverage. Best practices: test behavior not implementation, keep tests fast and isolated, mock at boundaries (I/O, network, time), and run in CI with coverage gates. Use `tox`/`nox` to test across Python versions.

## 🔴 Expert (15+ yrs)

### Q56. [Theory] Deep-dive: how does the free-threaded (no-GIL) CPython change the concurrency model, and what should teams watch for?

PEP 703 added an experimental **free-threaded** build (3.13+, with `--disable-gil`) that removes the Global Interpreter Lock so multiple threads can execute Python bytecode **truly in parallel** on multiple cores. To stay correct without the GIL, CPython adopted **biased reference counting** and deferred/immortal reference counting (common objects like small ints, `None`, interned strings become immortal, skipping refcount churn), plus internal locking on mutable containers.

What this changes:

- **CPU-bound threading becomes viable** — the long-standing "use multiprocessing for CPU work" advice relaxes when running on a free-threaded build.
- **Single-threaded overhead** — early builds carry some single-thread performance cost from the refcounting changes (being reduced version over version), and specializing adaptive interpreter optimizations had to be made thread-safe.

What teams must watch for: (1) **C extensions** must be rebuilt and declared free-thread-safe (`Py_mod_gil` slot) — many aren't yet, and importing a non-marked extension re-enables the GIL; (2) **latent data races** in code that "accidentally worked" because the GIL made many operations effectively atomic now surface; (3) the build is still **experimental and opt-in**, not the default, so production code should not assume it. The migration is gradual through the late 2020s; the safe stance in 2026 is to write thread-safe code (proper locking, no reliance on GIL atomicity) so it is correct under both builds.

### Q57. [Theory] Explain CPython's bytecode execution, the specializing adaptive interpreter, and what "optimization" means without a JIT.

CPython compiles source to **bytecode** (`.pyc`), a stack-based instruction set executed by the evaluation loop in `ceval.c`. Historically this loop was a straightforward switch over opcodes — portable but not fast.

Modern CPython (3.11+, "Faster CPython" project) added a **specializing adaptive interpreter** (PEP 659): at runtime, hot bytecode instructions are **specialized** in place. For example, a generic `BINARY_OP` that sees only ints gets rewritten to a fast `BINARY_OP_ADD_INT` that skips type dispatch; `LOAD_ATTR` specializes based on the observed object layout (akin to inline caches). If assumptions break (a different type shows up), it **de-optimizes** back to the generic form. Combined with **zero-cost exceptions** (no setup cost on the happy path), inlined Python-to-Python calls, and per-opcode caches, this yielded large speedups with no source changes.

```python
import dis
dis.dis(lambda x: x + 1)    # inspect the bytecode the VM runs
```

3.13+ also ships an experimental **JIT** (a copy-and-patch micro-op JIT) building on a "Tier 2" micro-op IR, still maturing. The takeaway for an expert: "optimization" in CPython has shifted from "rewrite in C" toward the interpreter optimizing itself; understanding specialization explains why type-stable, monomorphic hot loops run much faster than polymorphic ones, and why `dis` is a useful tool for reasoning about performance.

### Q58. [Practical] How do you design a Python codebase to remain maintainable and performant at large scale (millions of LOC, many teams)?

Several axes, each with concrete Python levers:

- **Type safety at scale** — enforce `mypy --strict` (or pyright) in CI; types are the only scalable defense against the dynamism that bites large dynamic codebases. Use `Protocol`s for interfaces, keep `Any` out, and gate merges on type checks.
- **Module boundaries & dependencies** — enforce a layered architecture and prevent import cycles (tools like `import-linter`); package by feature, expose narrow public APIs via `__all__`, and keep cross-team contracts explicit (shared models, versioned interfaces).
- **Performance hotspots** — profile-driven; isolate hot paths and push them to NumPy/Cython/Rust (PyO3) or services; keep hot loops type-stable to benefit from the specializing interpreter; bound all caches.
- **Dependency & build hygiene** — lockfiles (uv/poetry) for reproducibility, pinned and audited transitive deps, fast CI via caching, and a monorepo build tool (Bazel/Pants) once the scale demands it.
- **Testing & safety nets** — fast unit tests with pytest, contract tests at boundaries, property-based tests (Hypothesis) for tricky logic, and coverage gates.
- **Operational** — structured logging, observability, feature flags, and gradual rollout because Python's dynamism means some errors only appear at runtime.

The meta-point: Python optimizes for developer velocity, so at scale you must *deliberately* re-introduce the guardrails (types, boundaries, lockfiles, tests) that static, compiled ecosystems get partly for free — otherwise the dynamism that made you fast early becomes the thing that slows you down.

### Q59. [Behavioral] Describe a time you had to make a difficult technical decision about Python's limitations on a project.

Interviewers want to see that you can recognize where Python is the wrong tool and navigate that decision with engineering and people judgment. STAR example:

- **Situation** — "Our real-time pricing engine, written in Python, was missing latency targets under load; profiling showed the GIL serializing CPU-bound math and per-request GC pauses."
- **Task** — "Decide whether to keep optimizing Python, rewrite the hot path in another language, or re-architect — under a deadline and with a Python-only team."
- **Action** — "Rather than a risky full rewrite, I scoped the decision: I prototyped three options (NumPy vectorization, a Cython hot path, and a Rust/PyO3 extension), benchmarked each against the SLA, and weighed them against team skill and maintenance cost. NumPy got us 60% of the way with zero new language risk; the remaining hot kernel went to a small, well-tested Rust extension behind a clean Python API. I documented the boundary so 95% of the code stayed Python for velocity."
- **Result** — "We hit the latency target, kept the team productive in Python, and isolated the one piece that genuinely needed native performance — avoiding a months-long rewrite."

The signal: you didn't dogmatically defend or abandon Python; you made a **measured, scoped** decision, de-risked with prototypes and benchmarks, and considered team and maintenance realities, not just raw performance.

### Q60. [Theory] What are the subtle correctness traps in Python's data model that an expert must internalize?

A consolidated list of model-level sharp edges:

- **`__hash__`/`__eq__` contract** — if you override `__eq__` you must keep `__hash__` consistent (equal objects must hash equal). Defining `__eq__` sets `__hash__` to `None` (unhashable) unless you also define it; mutable objects used as keys break invariants if mutated after insertion.
- **`__del__` finalizers** — non-deterministic timing, can resurrect objects, historically interfered with cyclic GC, and exceptions in them are ignored. Prefer context managers or `weakref.finalize`.
- **Truthiness via `__bool__`/`__len__`** — a custom `__len__` returning 0 makes the object falsy; surprising for collection-like classes.
- **Operator dunder reflection** — `a + b` tries `a.__add__(b)` then `b.__radd__(a)`; subclass right-operands get priority. Getting `__eq__`/`__hash__`/`ordering` consistent is subtle; prefer `functools.total_ordering` or dataclasses.
- **Mutable default & class attributes** — shared across instances/calls (the canonical pitfall).
- **Identity vs equality and interning** — never reason about value via `is`.
- **Iterator exhaustion** — generators and `zip`/`map` are single-pass; re-iterating yields nothing.
- **Descriptor precedence** — data vs non-data descriptors interact with instance `__dict__` in non-obvious ways.
- **`super()` and MRO** — direct base calls break cooperative inheritance.

An expert keeps these in working memory because they cause bugs that pass code review and surface only in production edge cases.

### Q61. [Practical] How do you approach migrating a large legacy Python 2 / early Python 3 codebase to modern Python safely?

A staged, low-risk migration:

1. **Inventory & pin** — capture the current dependency tree with a lockfile and a comprehensive test baseline; you cannot migrate safely without tests, so backfill characterization tests for critical paths first.
2. **Get to a clean modern baseline** — if still on Python 2, use `2to3`/`futurize` and run on the latest 3.x that the deps support; remove `__future__` shims and `six`.
3. **Tighten incrementally** — add type hints module-by-module (start at boundaries and shared models), introduce `mypy` in CI in non-blocking mode, then ratchet to `--strict` per package. Use `pyupgrade` to modernize syntax (f-strings, built-in generics, `X | Y` unions) automatically.
4. **Modernize packaging** — move to `pyproject.toml`, adopt a lockfile-based tool (uv/poetry), and the src layout.
5. **Adopt new features deliberately** — dataclasses for records, `pathlib` over `os.path`, `asyncio` only where it pays off, structural pattern matching (`match`, 3.10+) where it clarifies.
6. **Roll out safely** — feature-flag and canary; Python's dynamism means some breakage is only caught at runtime, so observability and gradual rollout matter more than in static languages.

The governing principle: **never big-bang**. Migrate one module/version step at a time behind tests and CI gates, keeping the system shippable throughout, so risk is bounded and reversible at each step.

## 🧩 Extended Questions — Set 1: Deeper theory & internals

This set goes beneath the surface of the answers above — into the C-level object model, the bytecode the VM actually runs, how the dict and string implementations behave, what the import system and the compiler really do, and the corners of the data model that only bite at depth. Where the earlier questions told you *what* Python does, these ask *why* and *how it is implemented*.

### 🟢 — extended

#### Q62. [Theory] What is `PyObject`, and what does "everything is an object" mean at the C level?

In CPython, every value you manipulate is a `PyObject *` — a pointer to a C struct whose minimal header (`PyObject`) contains two fields: a **reference count** (`ob_refcnt`) and a **pointer to its type object** (`ob_type`). Variable-sized objects (lists, strings, ints) use `PyVarObject`, which adds an `ob_size` length field.

```c
typedef struct _object {
    Py_ssize_t ob_refcnt;      // reference count
    PyTypeObject *ob_type;     // the object's type
} PyObject;
```

"Everything is an object" is literally true: integers, functions, classes, modules, even `type` itself are heap-allocated `PyObject`s with a refcount and a type. A name (variable) is just a C pointer to one of these; assignment copies the pointer and bumps the refcount, never the value. This is why `id(x)` returns the memory address of the struct, why `sys.getsizeof(0)` is ~28 bytes (header overhead dominates a tiny int), and why there is no primitive/boxed distinction as in Java — there are no unboxed `int`s at the language level, only `PyObject`s. The type object (`ob_type`) carries the slot table (`tp_new`, `tp_hash`, `tp_richcompare`, ...) that the interpreter dispatches through, which is how a uniform C core supports arbitrary Python-level behavior.

#### Q63. [Theory] How is a Python `int` represented internally, and why is there no overflow?

CPython's `int` is an **arbitrary-precision** integer (a "bignum"). The struct `PyLongObject` stores the number as an array of "digits" (each digit is 30 bits on 64-bit builds, packed into 32-bit words) plus a sign encoded in the size field. A small value like `5` uses one digit; a 200-digit number just allocates more digits. Arithmetic that would overflow a fixed-width machine word instead grows the digit array, so `2 ** 1000` is exact.

```python
2 ** 100        # 1267650600228229401496703205376 — exact, no overflow
import sys
sys.getsizeof(0)      # ~28 bytes (header only, zero digits)
sys.getsizeof(2**30)  # larger — needs an extra digit
(10).bit_length()     # 4
```

CPython also caches **small integers** in range −5..256 as singletons (the "small int cache"), so those are shared objects (`a = 5; b = 5; a is b` is `True`). The trade-off versus a fixed-width `int64` is speed and memory: every operation goes through the bignum machinery and allocates objects, which is part of why pure-Python numeric loops are slow and why NumPy (fixed-width C arrays) exists. There is no `long`/`int` split as in Python 2 — they were unified in Python 3.

#### Q64. [Theory] Why are Python strings immutable, and what is the internal "flexible" string representation (PEP 393)?

Immutability buys three things: strings can be **hashable** (so they work as dict keys and set members with a cached hash), they can be **interned and shared** safely, and they are **thread-safe to read** without locking. Any "modification" (`s.upper()`, `s + t`) returns a new `str` object.

Internally, since **PEP 393** (Python 3.3) CPython uses a **compact, flexible representation**: each string picks the smallest character width that fits its largest code point — Latin-1 (1 byte/char), UCS-2 (2 bytes), or UCS-4 (4 bytes) — stored in one contiguous buffer.

```python
import sys
sys.getsizeof("a")       # small — 1 byte per char (Latin-1 kind)
sys.getsizeof("€")       # larger per-char — needs 2 bytes (UCS-2)
sys.getsizeof("😀")      # 4 bytes per char (UCS-4)
```

This means indexing is still O(1) (fixed width within a given string) while ASCII-heavy text stays memory-cheap. A consequence: building a string by repeated `+=` in a loop is O(n²) because each step allocates a new buffer and copies — use `"".join(parts)` (O(n)) or an `io.StringIO`. CPython does have a peephole optimization that sometimes makes in-place `+=` on a sole-reference string faster, but you should never rely on it.

#### Q65. [Theory] What does `id()` actually return, and why is `is` unreliable for value comparison?

`id(obj)` returns a number that is **unique and constant for the object's lifetime** — in CPython specifically the **memory address** of the `PyObject` struct. `x is y` is exactly `id(x) == id(y)`: it compares identity, not value.

It is unreliable for value comparison because two distinct objects with equal value have different ids, *and* because CPython's caching/interning makes some equal values accidentally share an id:

```python
a = 256; b = 256; a is b      # True  — small-int cache
a = 257; b = 257; a is b      # often False — outside the cache
a = "hi"; b = "hi"; a is b    # often True — compile-time interning
a = "h" * 1; b = "h"          # may or may not be interned
```

Because ids can also be **reused** after an object is freed, you cannot even rely on two ids taken at different times being meaningful. The rule: use `is`/`is not` only for the singletons `None`, `True`, `False` (and sentinel objects you create on purpose); use `==` for everything else.

#### Q66. [Coding] Show how the small-int cache and string interning are observable, and how to force interning.

```python
import sys

# Small-int cache: -5..256 are pre-created singletons
print(0 is 0)              # True
a, b = 256, 256
print(a is b)              # True
a, b = 257, 257
print(a is b)              # typically False (separate objects)

# String interning: compile-time literals that look like identifiers are interned
x = "hello"
y = "hello"
print(x is y)              # typically True

# Runtime-built strings are usually NOT interned automatically
s = "".join(["he", "llo"])
print(s is x)              # typically False
print(s == x)              # True — value equality is what matters

# Force interning to dedupe and speed up equality on hot keys
s2 = sys.intern("".join(["he", "llo"]))
x2 = sys.intern("hello")
print(s2 is x2)            # True after interning
```

`sys.intern` stores the string in a global table so all equal interned strings share one object; this can speed up dictionaries keyed by many repeated strings (identity check short-circuits the character-by-character compare) and save memory. None of this should change program semantics — it is purely an optimization, and the `is` results above are implementation details, not guarantees.

#### Q67. [Theory] How does a `dict` work internally, and what changed in the "compact dict" of Python 3.6+?

A `dict` is an **open-addressing hash table**. Each key's `hash()` selects a slot; on collision, CPython probes other slots using a perturbation sequence derived from the full hash until it finds the key or an empty slot. Lookups, inserts, and deletes are O(1) average, O(n) worst case (pathological collisions).

Since 3.6 (made a language guarantee in 3.7) dicts are **insertion-ordered** because of the **compact dict** redesign: the table is split into two arrays. A dense `entries` array stores `(hash, key, value)` triples in insertion order; a separate, sparse `indices` array of small integers maps hash slots to positions in the entries array.

```
indices:  [ _, 1, _, 0, _, 2, _, _ ]   # sparse, ints index into entries
entries:  [ (h0,k0,v0), (h1,k1,v1), (h2,k2,v2) ]   # dense, insertion order
```

Benefits: ~20–25% less memory (the sparse array holds small ints, not full pointer triples), and free ordering (iteration walks the dense array). This is why `OrderedDict` is now rarely needed. The cost of deletion is a tombstone in the entries array until a resize compacts it.

#### Q68. [Theory] What does it mean for an object to be hashable, and what is the `__hash__`/`__eq__` invariant?

An object is **hashable** if it has a `__hash__` returning an int that stays constant for the object's lifetime, and an `__eq__` for comparison. Hashability is required to be a `dict` key or `set` member because those structures locate items by hash bucket.

The binding invariant: **if `a == b` then `hash(a) == hash(b)`**. The converse need not hold (different objects may share a hash — a collision). Violating the invariant corrupts hash containers: an object placed in one bucket cannot be found by an equal object that hashes to a different bucket.

```python
class Bad:
    def __init__(self, v): self.v = v
    def __eq__(self, other): return self.v == other.v
    # forgot __hash__ -> Python sets __hash__ = None, instances unhashable

# defining __eq__ alone makes the class UNHASHABLE by default:
# {Bad(1)}  -> TypeError: unhashable type

class Good:
    def __init__(self, v): self.v = v
    def __eq__(self, other): return isinstance(other, Good) and self.v == other.v
    def __hash__(self): return hash(self.v)   # consistent with __eq__
```

Mutable objects are typically unhashable by design (their value-based hash would change as they mutate, breaking containers). This is why `list` and `dict` are unhashable while `tuple` and `frozenset` are hashable (if their contents are).

#### Q69. [Theory] What is bytecode, and what is the difference between a `.py`, a `.pyc`, and the `__pycache__` directory?

Source `.py` is text. When a **module** is imported, CPython compiles it to **bytecode** — a platform-independent instruction stream for the stack-based virtual machine — and caches the result as a `.pyc` file inside `__pycache__/` (named like `module.cpython-313.pyc`, tagged with the interpreter version). On subsequent imports, if the source's metadata is unchanged, Python loads the cached `.pyc` and skips recompilation.

```python
import dis
def add(a, b):
    return a + b
dis.dis(add)
#   RESUME / LOAD_FAST a / LOAD_FAST b / BINARY_OP + / RETURN_VALUE
```

Key facts: caching is purely a **startup optimization** — it does *not* compile to machine code or speed up execution. The top-level script you run directly is **not** cached (only imported modules are). Cache invalidation uses the source mtime+size by default, or a content hash (PEP 552 "hash-based pyc") for reproducible builds. Deleting `__pycache__` is always safe; it regenerates. `.pyc` is not meaningful obfuscation since it decompiles readily.

#### Q70. [Theory] What is the difference between a statement and an expression in Python, and why did the walrus operator matter?

An **expression** evaluates to a value (`a + b`, `f(x)`, `[i for i in xs]`). A **statement** performs an action and does not itself yield a value usable inline (`if`, `for`, `return`, plain assignment `x = 5`). Historically, assignment in Python was strictly a statement, so you could not assign inside an expression (unlike C's `if ((n = read()) > 0)`).

**PEP 572** added the **walrus operator** `:=` (3.8), an *assignment expression* that both binds a name and evaluates to the value, enabling assignment where only expressions are allowed:

```python
# Without walrus — call len twice or use a pre-loop line
while (chunk := file.read(8192)):
    process(chunk)

# In a comprehension — compute once, filter and reuse
results = [y for x in data if (y := f(x)) is not None]

# Avoid recomputation in a condition
if (n := len(a)) > 10:
    print(f"too long ({n})")
```

It matters because it removes a class of awkward workarounds (sentinel loops, recomputing a value used in both a condition and a body) while keeping the value-producing semantics that an expression context requires. Overuse hurts readability, so the idiom is reserved for the compute-test-reuse pattern.

### 🟡 — extended

#### Q71. [Theory] Walk through what the interpreter does for `a.b` — the full attribute lookup algorithm.

Reading `a.b` invokes `type(a).__getattribute__(a, "b")`, whose default implementation (`object.__getattribute__`) runs a precise sequence:

1. Walk `type(a).__mro__` looking for `"b"` in each class's `__dict__`. Remember if what is found is a **data descriptor** (defines `__set__` or `__delete__`).
2. If a **data descriptor** was found, call its `__get__(a, type(a))` and return — data descriptors win over instance state.
3. Otherwise look in the **instance** `a.__dict__["b"]` and return it if present.
4. Otherwise, if the class lookup found a **non-data descriptor** (only `__get__`, e.g. a function/method), call its `__get__`.
5. Otherwise, if the class lookup found a plain value, return it.
6. If nothing is found, call `type(a).__getattr__(a, "b")` if defined; else raise `AttributeError`.

```python
class C:
    x = 10                       # class attribute (plain)
    @property                    # data descriptor -> wins over instance dict
    def y(self): return 42
c = C()
c.__dict__["y"] = 99             # shadowed: property still wins
print(c.y)                       # 42, not 99
c.__dict__["x"] = 5              # plain class attr is shadowed by instance
print(c.x)                       # 5
```

This precedence (data descriptor > instance dict > non-data descriptor/class attr > `__getattr__`) is the single most important rule for explaining "magic" attribute behavior, method binding, and why `property` cannot be overridden by an instance attribute while a method can.

#### Q72. [Theory] Distinguish `__getattr__`, `__getattribute__`, `__setattr__`, and `__delattr__`.

- **`__getattribute__(self, name)`** — called on **every** attribute access, unconditionally. The default implements the lookup algorithm above. Override with care: a wrong implementation breaks everything, and you must delegate via `super().__getattribute__(name)` to avoid infinite recursion.
- **`__getattr__(self, name)`** — called **only as a fallback**, when normal lookup fails (raises `AttributeError`). This is the safe hook for lazy/virtual attributes, proxies, and `__getattr__`-based forwarding.
- **`__setattr__(self, name, value)`** — called on every `a.b = v`. Must use `super().__setattr__` or `self.__dict__[name] = value` internally to actually store, or it recurses forever.
- **`__delattr__(self, name)`** — called on `del a.b`.

```python
class Proxy:
    def __init__(self, target): object.__setattr__(self, "_t", target)
    def __getattr__(self, name):              # only for missing attrs
        return getattr(self._t, name)
    def __setattr__(self, name, value):
        setattr(self._t, name, value)         # forward all writes

# module-level __getattr__ (PEP 562) enables lazy submodule / deprecation hooks
def __getattr__(name):
    if name == "expensive":
        global expensive; expensive = compute(); return expensive
    raise AttributeError(name)
```

The practical rule: prefer `__getattr__` for adding fallback behavior (cheap, hard to break); reserve `__getattribute__` for the rare case where you must intercept *all* access, and always re-delegate to the base implementation.

#### Q73. [Coding] Demonstrate that default arguments and class bodies are evaluated once, at definition time — with the bytecode/timing evidence.

```python
import time

# Defaults are evaluated ONCE when the def executes, not per call:
def stamp(t=time.time()):       # time.time() runs now, at def time
    return t

print(stamp()); time.sleep(0.01); print(stamp())   # identical timestamps

# You can inspect the frozen defaults on the function object:
print(stamp.__defaults__)       # (<the single captured float>,)

# Class bodies also run once, top-to-bottom, at class-creation time:
class Registry:
    members = []                # one shared list (class attribute)
    print("class body running")  # prints exactly once, at definition

# The correct per-call fresh-value idiom:
def fresh(t=None):
    if t is None:
        t = time.time()         # evaluated each call
    return t
```

The mechanism: `def` compiles the defaults as part of building the function object and stores them in `func.__defaults__` (and `func.__kwdefaults__`); the class body executes as a code block whose resulting namespace becomes the class `__dict__`. Neither re-runs per call. This single fact explains the mutable-default pitfall, frozen `datetime.now()` defaults, and shared class-attribute state.

#### Q74. [Theory] How are bound methods created, and why is a function a non-data descriptor?

A function defined in a class body is stored as a plain function object in the class `__dict__`. Functions implement `__get__` (only `__get__`, so they are **non-data descriptors**). When you access `instance.method`, the attribute lookup finds the function in the class, sees it is a descriptor, and calls `func.__get__(instance, cls)`, which returns a **bound method** — a small object pairing the function with the instance.

```python
class C:
    def m(self): return self

c = C()
c.m                       # <bound method C.m of <C object>>
C.m                       # plain function (unbound; __get__ with obj=None returns self)
c.m.__self__ is c         # True — the bound instance
c.m.__func__              # the underlying function

# Because functions are NON-data descriptors, an instance attribute shadows them:
c.__dict__["m"] = lambda: "shadowed"
c.m()                     # "shadowed" — instance dict beats non-data descriptor
```

This is the whole mechanism behind `self`: there is no special "method" syntax: calling `c.m()` is `type(c).m.__get__(c, type(c))()`, and `__get__` injects `c` as the first argument. It also explains why a method can be shadowed by an instance attribute (non-data) while a `property` cannot (data descriptor).

#### Q75. [Theory] Explain how `import` works: the finder/loader machinery, `sys.modules`, and import caching.

`import x` runs the import system: (1) check `sys.modules` — if `x` is already there, bind it and stop (modules are imported **at most once** per process); (2) otherwise consult the **finders** on `sys.meta_path`, which search `sys.path` entries via path-based finders to produce a **module spec**; (3) the spec's **loader** creates the module object, inserts it into `sys.modules` *before* execution, then executes the module's code to populate its namespace.

```python
import sys
print("os" in sys.modules)          # True after first import anywhere
import os                            # second import is just a dict lookup
# del sys.modules["mymod"]          # forces re-import next time (used in reload)
import importlib
importlib.reload(os)                # re-execute a module's code in place
```

Key consequences: inserting the half-built module into `sys.modules` *before* executing it is what allows **circular imports** to partially work (each side sees a possibly-incomplete module). Module-level code runs exactly once, so it is the right place for one-time setup (and the reason a module is a natural singleton). Namespace packages (PEP 420) allow a package split across directories with no `__init__.py`. The expensive work is finding and executing on first import; everything after is an O(1) `sys.modules` hit.

#### Q76. [Coding] Show three idiomatic ways to make a class iterable, and explain the iterable-vs-iterator distinction.

An **iterable** can produce an iterator (`__iter__`); an **iterator** is the stateful cursor with `__next__` (and returns itself from `__iter__`). Conflating them causes the "can't re-iterate" bug.

```python
# 1) Iterable that returns a FRESH iterator each time (re-iterable) — generator method
class Deck:
    def __init__(self, cards): self.cards = cards
    def __iter__(self):
        yield from self.cards          # new generator per for-loop -> re-iterable

# 2) Self-iterator: __iter__ returns self, __next__ advances (single-pass!)
class Counter:
    def __init__(self, n): self.n = n; self.i = 0
    def __iter__(self): return self
    def __next__(self):
        if self.i >= self.n: raise StopIteration
        self.i += 1; return self.i

# 3) Old-style sequence protocol: __getitem__ with 0..N is auto-iterable
class Range5:
    def __getitem__(self, i):
        if i >= 5: raise IndexError
        return i

d = Deck([1, 2, 3])
list(d); list(d)        # [1,2,3] twice — re-iterable
c = Counter(3)
list(c); list(c)        # [1,2,3] then [] — exhausted after first pass
list(Range5())          # [0,1,2,3,4] via __getitem__ fallback
```

The trap: pattern (2) is single-pass; reusing it in two loops silently yields nothing the second time. Prefer pattern (1) (a generator method) when you want a collection that can be iterated repeatedly. Built-in `iter()` also supports the two-arg sentinel form `iter(callable, sentinel)`.

#### Q77. [Theory] What is the difference between `==` for floats and exact arithmetic, and why does `0.1 + 0.2 != 0.3`?

Python `float` is an IEEE-754 **double-precision binary** float. Most decimal fractions (like 0.1) have no exact binary representation, so they are stored as the nearest representable binary value. The tiny rounding errors accumulate, so `0.1 + 0.2` is `0.30000000000000004`, not exactly `0.3`.

```python
0.1 + 0.2 == 0.3                 # False
0.1 + 0.2                        # 0.30000000000000004

import math
math.isclose(0.1 + 0.2, 0.3)     # True — tolerance-based comparison

from decimal import Decimal
Decimal("0.1") + Decimal("0.2")  # Decimal('0.3') — exact decimal

from fractions import Fraction
Fraction(1, 3) + Fraction(2, 3)  # Fraction(1, 1) — exact rational
```

The fixes by use case: compare floats with `math.isclose` (relative+absolute tolerance), never `==`; use `decimal.Decimal` for money and anything needing exact decimal rounding (it is base-10 and lets you control precision/rounding mode); use `fractions.Fraction` for exact rational arithmetic. This is not a Python bug — it is how binary floating point works in every language; Python just exposes the exact value faithfully in `repr`.

#### Q78. [Theory] How do `sys.getrefcount`, `del`, and reference counting interact, and what are the pitfalls of reasoning about refcounts?

Every object tracks `ob_refcnt`. Binding a name, appending to a container, passing as an argument, etc., increment it; rebinding, `del name`, leaving a scope, or container removal decrement it. At zero, the object is deallocated immediately (and its own references are decremented, possibly cascading).

```python
import sys
x = object()
sys.getrefcount(x)        # e.g. 2 — note: the argument to getrefcount itself
                          # creates a temporary reference, inflating the count by 1
y = x
sys.getrefcount(x)        # one higher
del y                     # decrements; object stays alive (x still refs it)
```

`del name` does **not** delete the object — it removes one *binding* and decrements the refcount; the object dies only if that was the last reference. Pitfalls: (1) `getrefcount` always reads one higher than you expect because passing the object as an argument is itself a reference; (2) the interpreter holds hidden references (the current frame, temporaries, caches, interned objects), so absolute counts are not portable or reliable; (3) refcounting alone cannot reclaim cycles (hence the cyclic GC). Reason about *lifetime and reachability*, not exact counts.

### 🟠 — extended

#### Q79. [Theory] Explain the cyclic garbage collector's generational design and what makes an object "tracked."

Reference counting handles acyclic garbage instantly; the cyclic GC exists only to reclaim **unreachable reference cycles**. It tracks only **container** objects that can participate in cycles (lists, dicts, instances with `__dict__`, etc.) — atomic objects like `int`, `str`, and `float` cannot reference others, so they are never GC-tracked (`gc.is_tracked(0)` is `False`).

The collector is **generational** with three generations (0, 1, 2). Newly created tracked objects start in gen 0; survivors of a collection are **promoted** to the next generation, which is collected less often. The hypothesis (empirically true) is that most objects die young, so collecting gen 0 frequently and gen 2 rarely minimizes total work.

```python
import gc
gc.get_threshold()       # e.g. (700, 10, 10): allocations triggering each gen
gc.get_count()           # current per-generation counters
gc.collect()             # force a full collection; returns objects freed
gc.freeze()              # move surviving objects out of GC scrutiny (e.g. post-import,
                         # before fork) to reduce copy-on-write churn and pauses
```

The collection algorithm subtracts internal references from refcounts to find objects reachable only via cycles, then collects those. Objects with `__del__` historically complicated this (they went to `gc.garbage`); since 3.4 (PEP 442) finalizers in cycles are run safely. `gc.freeze()` (3.7+) is a real-world tool for forking servers (e.g. pre-fork web workers) to keep shared pages clean.

#### Q79b. [Practical] What is `weakref`, and what concrete problems does it solve?

A weak reference points to an object **without incrementing its refcount**, so it does not keep the object alive. When the referent is collected, the weakref returns `None` (or its callback fires). This solves the two classic over-retention problems: **caches that should not pin their entries** and **back-references that would create cycles**.

```python
import weakref

class Node:
    __slots__ = ("parent", "children", "__weakref__")  # slots need __weakref__
    def __init__(self):
        self.children = []
        self.parent = None

p = Node(); c = Node()
p.children.append(c)
c.parent = weakref.ref(p)        # back-ref doesn't create a strong cycle
c.parent()                       # -> p, or None after p is gone

# Caches keyed by object identity that auto-evict when the value dies:
cache = weakref.WeakValueDictionary()
# Run a cleanup callback when an object is finalized (better than __del__):
weakref.finalize(p, lambda: print("p was collected"))
```

Use cases: `WeakValueDictionary`/`WeakKeyDictionary` for caches and registries that must not leak, weak back-pointers in tree/graph structures to avoid cycles (reducing GC pressure), and observer registries that should not keep dead subscribers alive. Caveat: not every object supports weakrefs — types using `__slots__` must include `"__weakref__"`, and some built-ins (like `int`, `tuple`) cannot be weakly referenced.

#### Q80. [Theory] What is a code object, and what does a function object actually contain?

A **code object** (`func.__code__`, type `code`) is the immutable compiled form of a block: it holds the bytecode (`co_code`), constants (`co_consts`), names/locals (`co_varnames`, `co_names`), argument counts, flags (`co_flags` — e.g. is-generator, has-`*args`), and the line table for tracebacks. A **function object** is the runtime wrapper that binds a code object to its environment.

```python
def make_adder(n):
    def add(x): return x + n
    return add

f = make_adder(10)
f.__code__.co_varnames     # ('x',) — locals of add
f.__code__.co_freevars     # ('n',) — names captured from the enclosing scope
f.__closure__              # tuple of cell objects holding the captured values
f.__closure__[0].cell_contents   # 10 — the captured n
f.__defaults__             # default arg values
f.__globals__              # the module globals the function sees
```

The split matters: the **code object is shared and immutable** (the same `add` code object is reused for every call to `make_adder`), while each returned function gets its own **closure cells** capturing the specific `n`. Closures capture *cells* (mutable boxes), not values, which is exactly why the late-binding-loop pitfall happens — all lambdas share one cell. You can even build functions dynamically by constructing code objects, and `dis` and the `inspect` module read these attributes to introspect callables.

#### Q81. [Coding] Implement a class-based descriptor that demonstrates data vs non-data precedence empirically.

```python
class DataDesc:                      # has __set__  -> DATA descriptor
    def __set_name__(self, owner, name): self.name = name
    def __get__(self, obj, owner=None):
        if obj is None: return self
        return f"data-get:{obj.__dict__.get(self.name)}"
    def __set__(self, obj, value):
        obj.__dict__[self.name] = value

class NonDataDesc:                   # only __get__ -> NON-DATA descriptor
    def __get__(self, obj, owner=None): return "nondata-get"

class C:
    d = DataDesc()
    n = NonDataDesc()

c = C()
c.d = 42
print(c.d)                # 'data-get:42'  -> __get__ runs even though instance dict has it
c.__dict__["d"] = 99      # try to shadow the data descriptor
print(c.d)                # STILL 'data-get:99' via __get__ -> DATA descriptor wins

c.__dict__["n"] = "shadow"
print(c.n)                # 'shadow'  -> instance dict beats NON-DATA descriptor
```

The empirical result encodes the precedence rule: a **data descriptor** (defines `__set__`/`__delete__`) is consulted before the instance `__dict__`, so it cannot be shadowed — which is why `property`-managed attributes always run their getter. A **non-data descriptor** (only `__get__`) is consulted *after* the instance `__dict__`, so an instance attribute shadows it — which is why a normal method can be overridden by assigning an instance attribute of the same name. This single distinction underlies `property`, `classmethod`, `staticmethod`, `cached_property`, and method binding.

#### Q82. [Theory] How does `super()` find its class and instance with no arguments, and what is `__class__` cell magic?

`super()` with no args (Python 3) needs two things: the class in which the call textually appears, and the instance/type to dispatch on. It obtains the instance from the **first argument of the enclosing method** (read from the frame's locals), and it obtains the class from an implicit **`__class__` closure cell** that the compiler injects into any method that references `super` or `__class__`.

```python
class A:
    def who(self): return "A"
class B(A):
    def who(self):
        # The compiler created a __class__ cell == B for this method.
        print(__class__)          # <class '...B'> — the injected cell
        return super().who()      # super(__class__, self) implicitly

import dis
# dis.dis(B.who) shows LOAD_DEREF of __class__ feeding the super() call.
B().who()                         # 'A'
```

Because `__class__` is captured **lexically** (the class the method is *defined in*, fixed at compile time), `super()` correctly starts its MRO search *after that class*, even when `self` is a deeper subclass — which is what makes cooperative multiple inheritance work. A consequence: copying a method onto another class, or calling it outside its defining class body, breaks zero-arg `super()` because the `__class__` cell no longer matches; in those rare cases use the explicit `super(ThisClass, self)` form.

#### Q83. [Theory] What does `__init_subclass__` do, and why is it usually a better choice than a metaclass?

`__init_subclass__(cls, **kwargs)` (PEP 487, 3.6+) is an implicit classmethod called on the **parent** every time a **subclass** is created. It is the lightweight hook for the most common metaclass use cases — subclass registration, validation, and per-subclass configuration — without the conceptual and compositional cost of a custom metaclass.

```python
class Plugin:
    registry = {}
    def __init_subclass__(cls, /, *, key=None, **kwargs):
        super().__init_subclass__(**kwargs)
        if key is None:
            raise TypeError(f"{cls.__name__} must pass key=...")
        Plugin.registry[key] = cls          # auto-register every subclass

class CSV(Plugin, key="csv"): ...
class JSON(Plugin, key="json"): ...

print(Plugin.registry)   # {'csv': <CSV>, 'json': <JSON>}
```

Why prefer it: metaclasses **don't compose** (two base classes with different metaclasses cause a "metaclass conflict" `TypeError`), they are hard to read, and they intervene in *all* class machinery. `__init_subclass__` is ordinary inheritance, composes cleanly, and keyword arguments in the class header (`key="csv"`) flow straight to it. Together with `__set_name__` (which lets descriptors learn their attribute name) and class decorators (post-creation modification), the modern toolbox covers ~95% of what once required a metaclass. Reserve metaclasses for when you must alter the class **namespace or bases during creation** itself.

#### Q84. [Coding] Demonstrate generator `send()`, `throw()`, `close()`, and a coroutine-style accumulator.

```python
def averager():
    total = 0.0
    count = 0
    average = None
    while True:
        try:
            value = yield average      # value comes from .send(); average is yielded
        except StopAccumulating:        # injected via .throw()
            return average              # becomes StopIteration.value
        total += value
        count += 1
        average = total / count

class StopAccumulating(Exception): pass

avg = averager()
next(avg)                 # prime: advance to the first yield (yields None)
print(avg.send(10))       # 10.0  — send a value INTO the generator
print(avg.send(20))       # 15.0
print(avg.send(30))       # 20.0
try:
    avg.throw(StopAccumulating)   # raise an exception at the suspended yield
except StopIteration as e:
    print("final:", e.value)      # final: 20.0  (the return value)
avg.close()               # raise GeneratorExit inside; generator cleans up
```

The four control methods make a generator a **bidirectional coroutine**: `next()`/`send(None)` resumes and yields out; `send(x)` resumes with the `yield` expression evaluating to `x`; `throw(exc)` raises `exc` at the suspension point (for cancellation/signaling); `close()` raises `GeneratorExit` so `finally`/`with` blocks run cleanup. A generator must be **primed** (one `next()`) before the first `send`. A bare `return` in a generator sets `StopIteration.value`. This protocol is the historical foundation of `asyncio` (pre-`async`/`await` coroutines were built on `yield from` + `send`).

#### Q85. [Theory] What exactly does `yield from` do, beyond looping over a sub-iterable?

`yield from iterable` delegates the *entire* generator protocol to a subgenerator, not just the values. It (1) yields every value the subgenerator yields, (2) forwards values sent in via `.send()` into the subgenerator, (3) forwards exceptions thrown via `.throw()`, (4) propagates `close()`/`GeneratorExit`, and (5) **captures the subgenerator's return value** as the result of the `yield from` expression.

```python
def subgen():
    x = yield 1
    y = yield 2
    return x + y                 # return value surfaces to the delegator

def delegator():
    result = yield from subgen()  # transparent two-way pipe to subgen
    print("subgen returned", result)
    yield 99

g = delegator()
print(next(g))      # 1
print(g.send(10))   # 2  (x = 10)
print(g.send(20))   # prints "subgen returned 30", then yields 99
```

Compared to a manual `for v in subgen(): yield v`, the `for` loop loses send-forwarding, throw-forwarding, and the return value. `yield from` is what made composing generators (and pre-`async` coroutines) practical: a top-level driver could `send`/`throw` straight through nested layers. `await` in `async def` is the spiritual successor for the coroutine use case, while `yield from` remains the tool for ordinary generator composition.

### 🔴 — extended

#### Q86. [Theory] How do `frame` objects, the call stack, and `sys.setrecursionlimit` relate, and why is deep recursion costly in CPython?

Each function call pushes a **frame object** (`PyFrameObject`) holding the locals, the value stack, the instruction pointer (`f_lasti`), a back-pointer to the caller's frame (`f_back`), and a reference to the code object. The chain of `f_back` pointers *is* the Python call stack, which `traceback` and `inspect.stack()` walk.

```python
import sys
sys.getrecursionlimit()           # default ~1000
sys.setrecursionlimit(5000)       # raise it (carefully)

import inspect
def f(): return inspect.currentframe()
frame = f()
frame.f_locals, frame.f_code.co_name, frame.f_back   # introspect the frame
```

Recursion is costly because each level allocates a full Python frame (heavier than a C stack frame) and the recursion limit guards against overrunning the **C** stack and segfaulting the interpreter — the limit is a Python-level approximation of available C stack. CPython historically had **no tail-call optimization** (Guido explicitly rejected it, partly to preserve tracebacks), so deep recursion does not get flattened. Practical guidance: convert deep recursion to iteration with an explicit stack, or use generators; raising the limit risks a hard crash. (Recent CPython versions reduced per-frame overhead via "zombie"/lazy frame allocation and inlined Python-to-Python calls, but the model stands.)

#### Q87. [Theory] What is structural pattern matching (`match`/`case`) really doing under the hood (PEP 634)?

`match` (3.10+) is **not** a switch statement — it is destructuring pattern matching. The subject is tested against patterns that can bind names, check types, and recursively decompose structure. Class patterns use `__match_args__` to map positional sub-patterns to attributes; mapping and sequence patterns destructure dicts and sequences (but not strings/bytes, which are treated as atomic).

```python
from dataclasses import dataclass
@dataclass
class Point: x: int; y: int     # dataclass sets __match_args__ = ('x','y')

def classify(obj):
    match obj:
        case 0 | 1 | 2:                      # OR-pattern with literals
            return "small int"
        case [x, y, *rest]:                  # sequence pattern + capture
            return f"list starting {x},{y}, +{len(rest)}"
        case {"type": kind, **extra}:        # mapping pattern (partial match)
            return f"dict of {kind}, extras={extra}"
        case Point(x=0, y=yy):               # class pattern, keyword sub-pattern
            return f"on y-axis at {yy}"
        case Point(x, y) if x == y:          # positional via __match_args__ + guard
            return "on diagonal"
        case str() as s:                     # type pattern with capture
            return f"string {s!r}"
        case _:                              # wildcard (does NOT bind)
            return "other"
```

Semantics to internalize: patterns are tried top-to-bottom; a **capture pattern** (bare name) always matches and binds; `_` is the special non-binding wildcard; **guards** (`if`) add boolean conditions; class patterns check `isinstance` then sub-match; a literal pattern uses `==` (except `None`/`True`/`False` use `is`). It compiles to a sequence of type checks, attribute/index accesses, and binds — closer to ML-style matching than C's jump-table `switch`, and it shines for parsing ASTs, protocol messages, and nested data.

#### Q88. [Theory] Explain the operator dispatch protocol for `a + b`, including reflected and in-place variants.

For `a + b`, CPython consults the **type slots** `nb_add`, driven by the dunder protocol with a specific ordering rule:

1. Try `type(a).__add__(a, b)`. If it returns `NotImplemented`, fall through.
2. Try the **reflected** `type(b).__radd__(b, a)`.
3. If both return `NotImplemented`, raise `TypeError`.

The subtlety: if `type(b)` is a **subclass** of `type(a)` *and* overrides `__radd__`, the reflected method is tried **first**, so a subclass can customize operations with its base. In-place `a += b` first tries `__iadd__` (mutate in place, return self); if absent it falls back to `a = a + b`.

```python
class Vec:
    def __init__(self, v): self.v = v
    def __add__(self, other):
        if isinstance(other, Vec): return Vec(self.v + other.v)
        return NotImplemented            # let the OTHER side try
    __radd__ = __add__                   # makes sum([...], Vec(0)) and int+Vec work-ish
    def __iadd__(self, other):
        self.v += other.v; return self   # in-place mutation

a = Vec(1); b = Vec(2)
(a + b).v        # 3
before = id(a); a += b; id(a) == before   # True — __iadd__ mutated in place
```

Returning `NotImplemented` (the sentinel, distinct from raising `NotImplementedError`) is the cooperative signal that lets the other operand's reflected method take over — getting this wrong (raising instead of returning) breaks mixed-type arithmetic. This same three-step protocol governs all binary operators, with reflected partners `__rsub__`, `__rmul__`, etc.

#### Q89. [Coding] Implement a context manager that suppresses and a reentrant one, and explain `__exit__`'s return value.

```python
from contextlib import contextmanager

class suppress_errors:
    """Like contextlib.suppress: swallow listed exceptions."""
    def __init__(self, *exc_types): self.exc_types = exc_types
    def __enter__(self): return self
    def __exit__(self, exc_type, exc_val, exc_tb):
        # Returning True SUPPRESSES the exception; False/None lets it propagate.
        return exc_type is not None and issubclass(exc_type, self.exc_types)

with suppress_errors(ZeroDivisionError):
    1 / 0                       # swallowed; execution continues after the block
print("survived")

@contextmanager
def reentrant_section(name, _depth=[0]):
    _depth[0] += 1
    try:
        yield _depth[0]         # nesting level; works re-entered because each
                                # 'with' creates a fresh generator instance
    finally:
        _depth[0] -= 1

with reentrant_section("outer") as d1:
    with reentrant_section("inner") as d2:
        print(d1, d2)           # 1 2
```

The critical rule: `__exit__` returning a **truthy** value tells Python "I handled this exception — do not propagate it." Returning `False`/`None` (the safe default) lets the exception continue. This is how `contextlib.suppress` works and why an accidental `return True` in `__exit__` is a dangerous bug that silently eats errors. Note the generator form (`@contextmanager`) cannot suppress by returning a value — instead it suppresses by *catching* the exception around its `yield` and not re-raising; to propagate you simply let it bubble out of the `yield`.

#### Q90. [Theory] What are exception groups and `except*` (PEP 654), and why were they needed?

Before 3.11 an `except` clause could only handle **one** exception, but concurrent code (`asyncio.TaskGroup`, parallel workers) can fail with **several at once**. `ExceptionGroup` is a container holding multiple exceptions, and `except*` ("star except") matches and handles exceptions **by type within the group**, splitting it so each clause processes the matching subset while the rest propagate.

```python
def worker(n):
    if n % 2: raise ValueError(f"odd {n}")
    raise KeyError(f"even {n}")

try:
    raise ExceptionGroup("batch failed", [ValueError("a"), KeyError("b"), ValueError("c")])
except* ValueError as eg:
    print("values:", [str(e) for e in eg.exceptions])   # ['a', 'c']
except* KeyError as eg:
    print("keys:", [str(e) for e in eg.exceptions])     # ['b']
```

Semantics: each `except*` clause runs **at most once**, receiving a sub-group of all matching exceptions; unmatched exceptions re-raise as a residual group. You cannot mix `except` and `except*` in one `try`. This was driven by **`asyncio.TaskGroup`** (3.11), which collects all child-task failures into an `ExceptionGroup` rather than surfacing only the first — so structured-concurrency error handling needed a language construct to deal with "multiple errors happened." `traceback` and `ExceptionGroup.split`/`subgroup` give programmatic access for libraries.

#### Q91. [Theory] What are the typing constructs that shape modern Python APIs: `TypeVar`, `ParamSpec`, `Self`, `Protocol`, `overload`, and PEP 695 syntax?

The typing system grew a generics toolkit that static checkers (mypy/pyright) enforce while the runtime ignores it:

- **`TypeVar`** — a type variable for generic functions/classes (`def first[T](xs: list[T]) -> T`).
- **`ParamSpec`** (PEP 612) — captures an entire *parameter list* so decorators can preserve the wrapped signature precisely.
- **`Self`** (PEP 673) — the type "this same class," for fluent/builder methods and alternative constructors without a manual `TypeVar`.
- **`Protocol`** (PEP 544) — structural typing; matches by shape, not inheritance.
- **`@overload`** — multiple type signatures for one implementation (the runtime body is shared).
- **PEP 695 syntax** (3.12) — built-in generic syntax `class Stack[T]:` / `def f[T](...)` and `type Alias = ...`, removing explicit `TypeVar` declarations.

```python
from typing import Protocol, Self, overload, ParamSpec, TypeVar, Callable
import functools

class Comparable(Protocol):
    def __lt__(self, other: Self) -> bool: ...

def maximum[T: Comparable](xs: list[T]) -> T:     # PEP 695 generic + bound
    return max(xs)

class Query:
    def where(self, cond: str) -> Self:           # returns the same (sub)class type
        return self

P = ParamSpec("P"); R = TypeVar("R")
def logged(fn: Callable[P, R]) -> Callable[P, R]:  # preserves exact signature
    @functools.wraps(fn)
    def w(*a: P.args, **k: P.kwargs) -> R:
        return fn(*a, **k)
    return w

@overload
def get(x: int) -> str: ...
@overload
def get(x: str) -> int: ...
def get(x): ...                                    # single real implementation
```

The expert point: these constructs let you express precise, composable contracts (decorator-signature preservation via `ParamSpec`, structural interfaces via `Protocol`, self-returning builders via `Self`) that catch real bugs at check time. PEP 695 (3.12) and PEP 696 (type-parameter defaults, 3.13) modernized the syntax so generics read almost like other statically typed languages — while remaining zero-cost at runtime.

#### Q92. [Theory] Explain the difference between `__reduce__`, `__getstate__`/`__setstate__`, and how pickle reconstructs objects — including the security caveat.

`pickle` serializes an object graph to a byte stream and reconstructs it. By default it stores the object's `__class__` reference and its `__dict__` (or `__slots__`). The customization hooks, in order of granularity:

- **`__getstate__`/`__setstate__`** — return/restore the state dict; use to drop unpicklable fields (open sockets, locks, file handles) or to compute derived state on load.
- **`__reduce__`/`__reduce_ex__`** — the low-level hook: return a callable and its arguments that, when called, recreate the object (used for objects not reconstructible by the default mechanism, e.g. C-extension types).

```python
class Connection:
    def __init__(self, host): self.host = host; self._sock = open_socket(host)
    def __getstate__(self):
        state = self.__dict__.copy()
        del state["_sock"]            # sockets can't be pickled
        return state
    def __setstate__(self, state):
        self.__dict__.update(state)
        self._sock = open_socket(self.host)   # re-establish on unpickle
```

The reconstruction flow: pickle records a reduce-value (`callable, args, state, ...`); unpickling calls the callable to get a bare object, then applies the state via `__setstate__` (or updates `__dict__`). **Security**: unpickling is equivalent to executing arbitrary code — `__reduce__` can return `(os.system, ("rm -rf /",))`, which runs on load. **Never unpickle untrusted data.** For untrusted or cross-language data use `json`, or a schema-validated format (Protobuf, MessagePack with a schema, Pydantic). `pickle` is appropriate only for trusted, same-ecosystem data (caches, multiprocessing IPC).

#### Q93. [Practical] How does CPython's compilation pipeline turn source into running bytecode, and where can you intervene?

The pipeline has well-defined stages, each inspectable:

1. **Tokenize** — the source text becomes a token stream (`tokenize` module exposes this).
2. **Parse** — tokens become an **AST** (Abstract Syntax Tree). Since 3.9 CPython uses a **PEG parser** (PEP 617), replacing the old LL(1) grammar and enabling syntax that was previously hard to express. The `ast` module gives you the tree.
3. **Compile** — the AST is compiled to a **code object** of bytecode (with a symbol-table pass that decides each name's scope: local/global/cell/free).
4. **Execute** — the evaluation loop (`ceval.c`) runs the bytecode, with the specializing adaptive interpreter (PEP 659) rewriting hot opcodes at runtime.

```python
import ast, dis
src = "y = [n*2 for n in range(3)]"
tree = ast.parse(src)                 # stage 2: get the AST
print(ast.dump(tree, indent=2))       # inspect/transform structure
# Transform the AST programmatically:
class Doubler(ast.NodeTransformer):
    def visit_Constant(self, node):
        if isinstance(node.value, int): node.value *= 10
        return node
new = ast.fix_missing_locations(Doubler().visit(ast.parse(src)))
code = compile(new, "<ast>", "exec")  # stage 3: AST -> code object
dis.dis(code)                         # stage 4 input: see the bytecode
```

Where you intervene: **`ast`** for source-level metaprogramming (linters like flake8, formatters, macro-like transforms, code instrumentation); **`compile(source, filename, mode)`** to compile a string or AST yourself; **`dis`** to read the emitted bytecode for performance reasoning; import hooks (`sys.meta_path` finders) to transform modules at import time. Understanding the pipeline explains why syntax errors surface at parse time (before any code runs), why the PEG parser allowed features like the walrus and `match`, and how tools rewrite code safely at the AST level rather than with fragile regexes.

#### Q94. [Theory] What concurrency primitives does CPython expose for memory visibility, and which operations are actually atomic under the GIL?

Under the classic GIL, only **one** thread runs bytecode at a time, but a thread switch can occur between **bytecode instructions** (the interpreter checks for switches periodically — `sys.setswitchinterval`, default 5 ms). So an operation is effectively atomic only if it is a **single bytecode op** that doesn't call back into Python.

```python
import sys
sys.getswitchinterval()      # 0.005 — how often the eval loop considers switching

# Atomic-ish under the GIL (single C-level op on a built-in):
d[k] = v          # dict assignment
lst.append(x)     # list append
x = lst.pop()     # list pop
# NOT atomic — read-modify-write spans multiple ops; needs a lock:
counter += 1      # LOAD, add, STORE — a thread can switch mid-sequence
if k in d: del d[k]   # check-then-act race
```

The primitives for correctness: `threading.Lock`/`RLock` (mutual exclusion), `Semaphore`, `Event`, `Condition`, `Barrier`, and `queue.Queue` (the preferred thread-safe hand-off that avoids manual locking). The expert warning: **never rely on GIL atomicity for correctness** — (1) which ops are atomic is an undocumented implementation detail that varies by version and by the specializing interpreter; (2) compound operations (`+=`, check-then-act) are *not* atomic and need locks; (3) under the **free-threaded build (PEP 703)** the GIL no longer serializes anything, so code that "worked by accident" exhibits real data races. Write to the explicit-locking model so the code is correct under both builds.

#### Q95. [Theory] What are immortal objects (PEP 683) and deferred/biased reference counting, and why do they matter for free-threading?

To make refcounting work without a GIL (and to reduce refcount contention generally), CPython introduced several refcount innovations:

- **Immortal objects (PEP 683, 3.12)** — certain ubiquitous objects (`None`, `True`, `False`, small ints, interned strings, common type objects) are marked **immortal** via a sentinel refcount value. Their refcount is never modified, eliminating the constant inc/dec churn on the most-shared objects. This both speeds up code and removes cache-line contention that would otherwise be brutal when many threads touch `None` concurrently.
- **Biased reference counting** — splits each object's count into an **owner-thread local** count (fast, uncontended, modified without atomics) and a **shared** count (atomic, for cross-thread references). The thread that created an object handles the common case cheaply; only cross-thread sharing pays the atomic cost.
- **Deferred reference counting** — for objects frequently referenced from the interpreter's internal stack, refcount adjustments are deferred and reconciled, avoiding per-operation atomics on hot interpreter paths.

```python
import sys
sys.getrefcount(None)        # on 3.12+ free-threaded builds: a huge sentinel
                             # value indicating immortality, not a real count
```

Why it matters: naive refcounting under true multithreading would require an **atomic** increment/decrement on *every* reference operation, whose cache-coherency traffic would cripple multicore scaling — refcounting was the central technical obstacle PEP 703 had to solve. Immortalization (no writes to shared hot objects), biased counting (atomics only when actually shared), and deferral (skip interpreter-internal churn) together make GIL-free refcounting fast enough to be viable. The practical takeaway: `getrefcount` on immortal objects now returns meaningless sentinel values, and the long single-thread-performance debate around no-GIL is largely about the residual cost of these schemes — which has been shrinking version over version.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

Where Set 1 went down into the C-level internals, this set turns outward to the bench: the bugs you actually file tickets for, the production incidents you get paged for, and the small coding tasks an interviewer drops on you to see how you reason. Every question is grounded in a concrete scenario — a traceback you have to read, a leak you have to find, a snippet you have to fix or write — rather than a definition to recite.

### 🟢 — extended

#### Q96. [Coding] Given a list of dicts, group the records by a key and return the groups. Show the idiomatic stdlib way.

A grouping task is the single most common "warm-up" coding question. The right tool is `collections.defaultdict`, which removes the check-and-initialize boilerplate.

```python
from collections import defaultdict

records = [
    {"dept": "eng", "name": "ana"},
    {"dept": "sales", "name": "bo"},
    {"dept": "eng", "name": "cy"},
]

groups = defaultdict(list)
for r in records:
    groups[r["dept"]].append(r["name"])

dict(groups)   # {'eng': ['ana', 'cy'], 'sales': ['bo']}
```

If you must use `itertools.groupby` instead, remember it only groups **consecutive** equal keys, so you have to sort by the same key first — and it is almost always more code than `defaultdict` for this task:

```python
from itertools import groupby
records.sort(key=lambda r: r["dept"])             # required!
groups = {k: [r["name"] for r in g]
          for k, g in groupby(records, key=lambda r: r["dept"])}
```

Reach for `defaultdict` for unordered grouping; reach for `groupby` only when the data is already sorted and you want a lazy, streaming pass.

#### Q97. [Practical] You see `UnicodeDecodeError: 'utf-8' codec can't decode byte 0x... in position N`. How do you diagnose and fix it?

This error means you are decoding bytes that are not valid UTF-8 — almost always because the data was encoded with a different codec (Latin-1, Windows-1252) or is binary, not text. Diagnose by asking *where the bytes came from*:

1. **Wrong codec.** A file authored on Windows is often `cp1252`. Open with the correct encoding: `open(path, encoding="cp1252")`. If you do not know the encoding, sniff it with the `charset-normalizer` library.
2. **It is actually binary.** A PNG or gzip stream is not text at all — open in binary mode (`"rb"`) and do not decode.
3. **Mixed/garbage data you must tolerate.** Use an error handler so one bad byte does not crash the job:

```python
data.decode("utf-8", errors="replace")   # bad bytes -> U+FFFD '�'
data.decode("utf-8", errors="ignore")    # drop bad bytes silently
open(path, encoding="utf-8", errors="replace")
```

The deeper fix is to be explicit about encoding at every I/O boundary rather than relying on the platform default — the default differs between Windows (often `cp1252`) and Linux (`utf-8`), so code that works locally can blow up in production. Decode at input, work in `str`, encode at output.

#### Q98. [Coding] Write a function that safely gets a deeply nested value from a dict, returning a default if any level is missing.

```python
def deep_get(d, *keys, default=None):
    for key in keys:
        if isinstance(d, dict) and key in d:
            d = d[key]
        else:
            return default
    return d

config = {"db": {"primary": {"host": "localhost"}}}
deep_get(config, "db", "primary", "host")        # 'localhost'
deep_get(config, "db", "replica", "host", default="-")   # '-'
```

The point is to never let a missing intermediate key raise `KeyError` or `TypeError` (e.g. indexing into `None`). The `isinstance(d, dict)` guard also protects against a level being a non-dict value. This pattern shows up constantly when reading JSON API responses where fields are optional.

#### Q99. [Practical] Your script prints numbers but the output appears all at once at the end, or not at all, when piped to a file. Why?

This is **output buffering**. When stdout is connected to a terminal, Python line-buffers (you see each line immediately); when stdout is a pipe or file, it switches to **block buffering** (typically 8 KB), so output appears only when the buffer fills or the program exits. If the program crashes or is killed, buffered output is lost.

Fixes, in order of preference:

```python
print(line, flush=True)                 # flush this one call
import sys; sys.stdout.reconfigure(line_buffering=True)   # 3.7+, whole stream
```

```bash
python -u script.py        # unbuffered stdout/stderr for the whole process
PYTHONUNBUFFERED=1 python script.py
```

This bites people constantly with Docker logs and CI output, where stdout is a pipe — interleaved or missing logs are almost always buffering, not a logic bug. `print(..., flush=True)` or `PYTHONUNBUFFERED=1` is the standard fix for containerized apps.

#### Q100. [Coding] Remove duplicates from a list (a) when order does not matter and (b) when it must be preserved.

```python
items = [3, 1, 2, 1, 3, 4]

# (a) Order irrelevant — a set is O(n) and trivial
unique = list(set(items))            # order NOT guaranteed

# (b) Order preserved — dict keys are insertion-ordered since 3.7
unique = list(dict.fromkeys(items))  # [3, 1, 2, 4]  -> O(n), keeps first occurrence
```

`dict.fromkeys` is the idiomatic one-liner for order-preserving dedup and is both faster and clearer than the manual `seen = set()` loop:

```python
seen = set()
result = [x for x in items if not (x in seen or seen.add(x))]
```

The set-based approaches require elements to be **hashable**. For unhashable elements (lists, dicts), fall back to an `O(n²)` membership check against a running list, or dedup on a hashable key derived from each element.

#### Q101. [Practical] `pip install` succeeds but `import mypackage` raises `ModuleNotFoundError`. What are the usual causes?

The package installed, but the *running* interpreter is not the one you installed into. Walk through the common culprits:

1. **Wrong interpreter / environment.** You installed into one Python and ran another. Verify with `python -c "import sys; print(sys.executable)"` and `pip --version` (which Python it targets). The robust habit is `python -m pip install ...` so pip and the interpreter always match.
2. **Virtualenv not activated** (or your IDE is using a different interpreter than your shell).
3. **Distribution name ≠ import name.** You `pip install beautifulsoup4` but `import bs4`; `pip install PyYAML` but `import yaml`; `pip install scikit-learn` but `import sklearn`.
4. **Shadowing.** A local file named `mypackage.py` (or a folder) shadows the installed package because the current directory is first on `sys.path`.
5. **User vs system install** where `--user` site-packages is not on the active path.

Diagnose by printing `sys.executable` and `sys.path`, then confirm with `pip show mypackage` that it landed where that interpreter looks.

#### Q102. [Coding] Parse a date string and compute the number of days between two dates, handling timezones correctly.

```python
from datetime import datetime, timezone, timedelta

# Python 3.11+ parses most ISO-8601, including 'Z' and offsets
a = datetime.fromisoformat("2026-06-30T10:00:00+00:00")
b = datetime.fromisoformat("2026-07-15T22:30:00Z")   # 'Z' supported in 3.11+

delta = b - a
delta.days            # 15
delta.total_seconds() # full precision

# Make a naive datetime timezone-aware (do NOT just label it — convert)
naive = datetime(2026, 6, 30, 10, 0)
aware = naive.replace(tzinfo=timezone.utc)   # assert it is already UTC
```

Key rules: never subtract a **naive** datetime from an **aware** one — it raises `TypeError`. Store and compute in UTC; convert to local time only for display. Use the stdlib `zoneinfo` module (3.9+) for real IANA zones (`ZoneInfo("America/New_York")`), which handles DST correctly, rather than fixed offsets.

#### Q103. [Practical] A colleague's code does `except: pass`. Explain concretely what can go wrong and what to write instead.

A bare `except:` catches **everything**, including `KeyboardInterrupt` (so `Ctrl-C` no longer stops the program), `SystemExit` (so `sys.exit()` is swallowed), and `MemoryError`. With `pass` it also discards the error silently, so a real bug — a typo'd attribute, a failed network call, a `None` you didn't expect — vanishes with no log, no traceback, no signal that anything went wrong. This is how bugs hide for months.

Write the narrowest catch that handles a *recoverable* condition, and always record it:

```python
try:
    value = cache[key]
except KeyError:
    value = compute(key)           # specific, expected, handled

try:
    do_network_call()
except RequestException as e:
    logger.warning("call failed, retrying: %s", e)   # logged, not swallowed
```

If you genuinely must catch broadly (a top-level worker loop that must not die), catch `Exception` (not bare `except`, so `KeyboardInterrupt`/`SystemExit` still propagate) and **log with the traceback**: `logger.exception("unexpected error")`.

#### Q104. [Coding] Given two lists, produce a dict mapping the first to the second, and safely handle unequal lengths.

```python
keys = ["a", "b", "c"]
vals = [1, 2, 3]

mapping = dict(zip(keys, vals))          # {'a': 1, 'b': 2, 'c': 3}

# zip stops at the shortest — silent truncation if lengths differ!
dict(zip(["a", "b", "c"], [1]))          # {'a': 1}  -> b, c silently dropped

# Catch the mismatch explicitly (3.10+)
dict(zip(keys, vals, strict=True))       # raises ValueError if lengths differ

# Fill missing values instead of truncating
from itertools import zip_longest
dict(zip_longest(keys, vals, fillvalue=None))
```

The load-bearing lesson is that plain `zip` **silently truncates** to the shortest input — a classic source of "where did my data go" bugs. Use `strict=True` when the lengths *should* match (so a mismatch is a loud error), and `zip_longest` when one side is legitimately shorter.

### 🟡 — extended

#### Q105. [Practical] A long-running service's memory grows unbounded even though you `del` objects. Walk through how you'd find the leak.

In CPython, `del` only drops one reference; the object survives if anything else still references it. Hunt the lingering reference systematically:

1. **Snapshot allocations** with `tracemalloc` and diff over time to see *which lines* are growing:

```python
import tracemalloc
tracemalloc.start()
snap1 = tracemalloc.take_snapshot()
# ... run a work cycle ...
snap2 = tracemalloc.take_snapshot()
for stat in snap2.compare_to(snap1, "lineno")[:10]:
    print(stat)
```

2. **Count objects by type** with `gc.get_objects()` or the `objgraph` library to see what class is accumulating, then `objgraph.show_backrefs(obj)` to see *who* holds it.
3. **Look at the usual suspects:** an ever-growing module-level cache or list, an `lru_cache(maxsize=None)` keyed on unique values, registered callbacks/observers never unregistered, logging handlers accumulating, or closures/`functools.partial` capturing large objects.
4. **Reference cycles with `__del__`** historically pinned memory; modern GC handles most, but `gc.set_debug(gc.DEBUG_LEAK)` reveals uncollectable cycles.

The fix is usually to **bound** the unbounded thing (cap the cache, use `deque(maxlen=...)`, `WeakValueDictionary` for registries) or to break a cycle with `weakref`.

#### Q106. [Coding] Implement a retry helper with exponential backoff and jitter for flaky network calls.

```python
import random, time
import functools

def retry(exceptions, tries=4, base_delay=0.5, max_delay=30.0):
    def decorator(func):
        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            for attempt in range(tries):
                try:
                    return func(*args, **kwargs)
                except exceptions as e:
                    if attempt == tries - 1:
                        raise                       # exhausted; propagate
                    delay = min(max_delay, base_delay * 2 ** attempt)
                    delay = random.uniform(0, delay)   # full jitter
                    time.sleep(delay)
        return wrapper
    return decorator

@retry((ConnectionError, TimeoutError), tries=5)
def fetch(url): ...
```

Two production-grade details: **jitter** (randomizing the delay) prevents the "thundering herd" where many clients retry in lockstep and re-overload the service; and you should only retry **idempotent** or safe operations — blindly retrying a non-idempotent POST can double-charge a customer. For real systems, prefer a maintained library like `tenacity` over hand-rolling.

#### Q107. [Practical] Your unit test passes alone but fails when run with the full suite. What categories of bug cause this, and how do you fix them?

Order-dependent failures almost always mean **shared mutable state leaking between tests**:

- **Module-level / class-level mutable state** mutated by one test and observed by another. Fix: reset in fixtures, or avoid global state.
- **A mutable default argument or class attribute** accumulating across tests (the classic `def f(x, cache=[])` bug).
- **Monkeypatches or env changes not undone.** Use pytest's `monkeypatch` fixture (auto-reverts) instead of patching by hand.
- **Caches** (`lru_cache`, singletons, connection pools) carrying state across tests — call `.cache_clear()` in teardown.
- **Test interdependence** where one test relies on another having run first.

Diagnose by reproducing the order: `pytest -p no:randomly` to fix ordering, or `pytest --randomly-seed=N` (with `pytest-randomly`) to make order deterministic, then bisect. The cure is **test isolation**: each test sets up and tears down its own state via fixtures, touching no global state. Randomizing test order in CI surfaces these bugs early instead of letting them hide.

#### Q108. [Coding] You have a CPU-bound function over a big list. Show how to parallelize it correctly with a process pool.

```python
from concurrent.futures import ProcessPoolExecutor

def heavy(x):
    # pure-Python CPU work; threads wouldn't parallelize this (GIL)
    total = 0
    for i in range(x):
        total += i * i
    return total

def main():
    data = [10_000, 20_000, 30_000, 40_000]
    with ProcessPoolExecutor() as pool:
        results = list(pool.map(heavy, data))
    print(results)

if __name__ == "__main__":      # REQUIRED on Windows/spawn — guards re-import
    main()
```

Why a **process** pool, not threads: CPU-bound pure-Python work is serialized by the GIL, so threads give no speedup — separate processes each have their own interpreter and run truly in parallel. Caveats: the function and its arguments must be **picklable** (a top-level function, not a lambda or closure); there is per-task IPC overhead, so batch small tasks (use `chunksize`) rather than dispatching millions of tiny ones; and the `if __name__ == "__main__":` guard is mandatory under the `spawn` start method (Windows and, since 3.14, the default elsewhere) to avoid recursively spawning the whole program.

#### Q109. [Practical] A teammate's f-string logging is flagged in review: `logger.info(f"processing {expensive()}")`. Why, and what's the fix?

Two real problems. First, the **f-string is always evaluated**, even when the log level would discard the message — so `expensive()` runs on every call regardless of whether `INFO` is enabled, wasting work in production where logging is often at `WARNING`. The logging module's `%`-style deferred formatting only formats the string if the record is actually emitted:

```python
logger.info("processing %s", value)      # formatted only if INFO is enabled
```

Second, **structured/centralized logging** (Sentry, log aggregation) groups messages by their template. With `%`-args, every call shares the template `"processing %s"` and the variable is a separate field; with an f-string, each message is a unique string, so grouping and alerting break.

The fix is the lazy `%`-args form above. If the *argument itself* is expensive to compute, guard it: `if logger.isEnabledFor(logging.DEBUG): logger.debug("x=%s", expensive())`.

#### Q110. [Coding] Read a 10 GB CSV, transform each row, and write the output without exhausting memory.

```python
import csv

def transform_csv(in_path, out_path):
    with open(in_path, newline="", encoding="utf-8") as fin, \
         open(out_path, "w", newline="", encoding="utf-8") as fout:
        reader = csv.DictReader(fin)        # streams row by row
        writer = csv.DictWriter(fout, fieldnames=reader.fieldnames)
        writer.writeheader()
        for row in reader:                  # one row in memory at a time
            row["amount"] = f"{float(row['amount']) * 1.1:.2f}"
            writer.writerow(row)
```

The whole approach is **streaming**: `csv.DictReader` is a lazy iterator that holds a single row at a time, so memory is `O(rowsize)` regardless of file size. The anti-patterns to avoid are `list(reader)` or `pd.read_csv(...)` without `chunksize`, both of which load everything. Always pass `newline=""` to the file (the `csv` module handles line endings itself) and set `encoding` explicitly. For pandas at scale, use `pd.read_csv(path, chunksize=100_000)` to get an iterator of frames.

#### Q111. [Practical] After deploying, you get `RecursionError: maximum recursion depth exceeded`. How do you investigate and fix it?

`RecursionError` means the call stack hit the limit (default ~1000 frames). Two distinct causes:

1. **Genuine infinite/runaway recursion** — a base case that never triggers, or mutual recursion with no termination. The traceback shows the same frames repeating; read it to find the cycle. Fix the logic; do **not** just raise the limit.
2. **Legitimately deep recursion** on a large input (deep tree, long linked structure). Here the algorithm is correct but CPython doesn't do tail-call optimization, so each level costs a real C frame.

For (2), the right fix is usually to **convert recursion to iteration** with an explicit stack:

```python
def walk(root):
    stack = [root]
    while stack:
        node = stack.pop()
        process(node)
        stack.extend(node.children)
```

Raising `sys.setrecursionlimit(10_000)` is a last resort and risky — exceed the actual C stack and you get a hard segfault, not a clean exception. Prefer iteration, or increase the thread stack size deliberately if you must recurse deep.

#### Q112. [Coding] Validate that incoming JSON has the expected shape, returning clear errors. Show a lightweight approach and a robust one.

```python
import json

# Lightweight, no dependencies — explicit checks
def parse_user(raw: str) -> dict:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as e:
        raise ValueError(f"invalid JSON: {e}") from e
    if not isinstance(data, dict):
        raise ValueError("expected a JSON object")
    if not isinstance(data.get("name"), str):
        raise ValueError("'name' must be a string")
    if not isinstance(data.get("age"), int):
        raise ValueError("'age' must be an integer")
    return data
```

For anything beyond a couple of fields, use **Pydantic**, which turns validation into a declarative model and produces precise, field-level error messages:

```python
from pydantic import BaseModel, ValidationError

class User(BaseModel):
    name: str
    age: int

try:
    user = User.model_validate_json(raw)   # parses + validates in one step
except ValidationError as e:
    print(e.errors())   # structured list: which field, what went wrong
```

The principle is **validate at the boundary** (where untrusted data enters) and convert raw dicts into typed objects, so the rest of your code can trust the shape. Hand-rolled checks are fine for tiny payloads; Pydantic scales to nested, optional, and constrained fields.

#### Q113. [Practical] Two services share a module via a `requirements.txt` with unpinned versions, and a deploy broke because of a transitive dependency bump. How do you make builds reproducible?

The root cause is **unpinned (or loosely pinned) dependencies plus unpinned transitive deps**: `requests>=2` resolves to whatever is newest at build time, and even a pinned direct dep can pull a *different* transitive version. Reproducibility requires a **lockfile** that pins the entire resolved graph, including transitive packages and hashes.

Approaches in 2026:

- **`uv`** (or `pip-tools`): keep human-edited `requirements.in` / `pyproject.toml`, compile to a fully pinned `uv.lock` / `requirements.txt` with hashes, and `uv sync` / `pip install --require-hashes` in CI and prod.
- **Poetry / PDM**: `pyproject.toml` + `poetry.lock` serve the same role.

Commit the lockfile, install *only* from it in CI and production, and upgrade deliberately by regenerating the lock (then test). Hashes (`--require-hashes`) also defend against a compromised package being swapped on PyPI. The discipline: humans edit loose constraints, machines resolve and pin, and deploys install exclusively from the pinned, hashed lock.

### 🟠 — extended

#### Q114. [Coding] Implement a thread-safe, bounded in-memory cache with TTL expiry.

```python
import threading, time
from collections import OrderedDict

class TTLCache:
    def __init__(self, maxsize=1000, ttl=60.0):
        self.maxsize, self.ttl = maxsize, ttl
        self._data: OrderedDict[str, tuple[float, object]] = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key, default=None):
        with self._lock:
            item = self._data.get(key)
            if item is None:
                return default
            expires, value = item
            if time.monotonic() >= expires:
                del self._data[key]            # lazy expiry on read
                return default
            self._data.move_to_end(key)        # LRU recency
            return value

    def put(self, key, value):
        with self._lock:
            self._data[key] = (time.monotonic() + self.ttl, value)
            self._data.move_to_end(key)
            while len(self._data) > self.maxsize:
                self._data.popitem(last=False)  # evict LRU
```

Three production details. Use `time.monotonic()`, not `time.time()`, so expiry is immune to wall-clock adjustments (NTP, DST). Hold the **lock** around the whole read-modify-write to avoid races between checking expiry and mutating the dict. Expiry here is **lazy** (checked on access); a busy cache with many never-read keys also needs a periodic sweep to actually free memory, otherwise dead entries linger until they happen to be touched.

#### Q115. [Practical] Profiling shows 60% of request time in a function that just builds objects in a loop. The algorithm is already optimal. What now?

When the algorithm is `O(n)` and you can't reduce `n`, you attack **per-operation constant cost** in CPython:

- **`__slots__`** on the class being instantiated in the hot loop — removes the per-instance `__dict__`, cutting both memory and attribute-access time, often 20–40% on allocation-heavy paths.
- **Hoist attribute and global lookups** out of the loop: `append = result.append` then call `append(x)` avoids re-resolving `result.append` every iteration. Each `.` is a dict lookup.
- **Replace the loop with a comprehension or `map`**, which runs the iteration in C.
- **Avoid redundant work in the body** — precompute invariants, avoid re-creating constant objects, prefer local variables (local access is faster than global/attribute).
- **Batch** if there's I/O hiding inside the loop.

If that's not enough, the function is a candidate for **vectorization with NumPy** (if numeric) or moving the hot loop into **Cython / a Rust extension via PyO3**. Always re-profile after each change — micro-optimizations are easy to get wrong, and the specializing adaptive interpreter (3.11+) may already handle some of them.

#### Q116. [Coding] Demonstrate a subtle bug where modifying a list while iterating it skips elements, and show three correct fixes.

```python
nums = [1, 2, 3, 4, 5, 6]

# BUG: removing during iteration shifts indices, skipping elements
for x in nums:
    if x % 2 == 0:
        nums.remove(x)
# nums == [1, 3, 5]  here it "works", but...
nums = [2, 2, 3]
for x in nums:
    if x == 2:
        nums.remove(x)
# nums == [2, 3]  -> one '2' survives! the iterator skipped it
```

The iterator advances by index while `remove` shifts later elements left, so the element now occupying the current index is never examined. Three correct fixes:

```python
# 1) Iterate a copy, mutate the original
for x in nums[:]:
    if x == 2: nums.remove(x)

# 2) Build a new list (usually clearest and fastest)
nums = [x for x in nums if x != 2]

# 3) Iterate backwards by index when in-place is required
for i in range(len(nums) - 1, -1, -1):
    if nums[i] == 2:
        del nums[i]
```

The general rule: **never add to or remove from a collection you are iterating**. Build a new collection (the comprehension), or iterate a snapshot. The same hazard applies to dicts and sets — mutating size during iteration raises `RuntimeError: dictionary changed size during iteration`.

#### Q117. [Practical] An `asyncio` service becomes unresponsive under load — latency spikes, the event loop seems frozen. How do you diagnose it?

A frozen event loop almost always means a coroutine **blocked the loop** — it ran synchronous, non-awaiting code for a long time, so no other task could make progress. The usual offenders:

- A **blocking call** inside a coroutine: `time.sleep`, a synchronous DB driver or `requests` call, a CPU-heavy loop, `open()/read()` of a big file.
- A coroutine doing heavy CPU work between `await` points.

Diagnose by enabling **debug mode** (`asyncio.run(main(), debug=True)` or `PYTHONASYNCIODEBUG=1`), which logs callbacks that take too long ("Executing ... took N seconds") and warns about coroutines that were never awaited. Add slow-callback logging via `loop.slow_callback_duration`. To find *where*, sample the stack with a profiler like `py-spy dump` (no code change, works on a live process) and look for a coroutine stuck in synchronous code.

The fix: offload blocking work with `await asyncio.to_thread(blocking_fn)` (I/O) or a `ProcessPoolExecutor` (CPU), and replace synchronous libraries with async ones (`aiohttp`/`httpx`, `asyncpg`). The cardinal asyncio rule — *never block the loop* — is the lens for the whole investigation.

#### Q118. [Coding] Write a generator-based pipeline that reads lines, filters, transforms, and aggregates — lazily.

```python
def read_lines(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            yield line.rstrip("\n")

def non_empty(lines):
    for line in lines:
        if line.strip():
            yield line

def parse_amounts(lines):
    for line in lines:
        try:
            yield float(line.split(",")[2])
        except (IndexError, ValueError):
            continue        # skip malformed rows

def total(path):
    lines  = read_lines(path)            # nothing read yet
    lines  = non_empty(lines)            # still lazy
    amounts = parse_amounts(lines)       # still lazy
    return sum(amounts)                  # NOW the pipeline runs, one line at a time
```

Each stage is a generator that pulls from the previous one on demand, so the whole pipeline processes **one line at a time** with `O(1)` memory regardless of file size — and the file is never fully materialized. This compositional, lazy style (the "generator pipeline" pattern) is how you process arbitrarily large or even infinite streams. Nothing executes until `sum` starts pulling, and the `with` in `read_lines` keeps the file open exactly as long as the generator is alive.

#### Q119. [Practical] You must monkeypatch a third-party function in tests, but the patch "doesn't take." What's the rule for where to patch?

The rule is **patch where the name is looked up, not where it is defined**. When `module_a` does `from utils import fetch`, it binds its *own* name `module_a.fetch` to the function object. Patching `utils.fetch` afterward does not change `module_a.fetch`, so the code under test still calls the original.

```python
# module_a.py
from utils import fetch
def run(): return fetch()

# test — WRONG: patches the wrong name
mock.patch("utils.fetch", return_value=42)      # module_a.fetch still original
# RIGHT: patch the name as module_a sees it
mock.patch("module_a.fetch", return_value=42)
```

If instead `module_a` does `import utils` and calls `utils.fetch()`, then patching `utils.fetch` *does* work, because the lookup goes through the `utils` module object at call time. The takeaway: trace how the name is imported and resolved in the module under test, and patch that fully-qualified path. This "patch where it's used" rule is the single most common `unittest.mock` confusion.

#### Q120. [Coding] Implement a rate limiter (token bucket) that's safe to call from multiple threads.

```python
import threading, time

class TokenBucket:
    def __init__(self, rate: float, capacity: float):
        self.rate = rate                 # tokens added per second
        self.capacity = capacity
        self.tokens = capacity
        self.timestamp = time.monotonic()
        self.lock = threading.Lock()

    def allow(self, cost: float = 1.0) -> bool:
        with self.lock:
            now = time.monotonic()
            elapsed = now - self.timestamp
            self.tokens = min(self.capacity, self.tokens + elapsed * self.rate)
            self.timestamp = now
            if self.tokens >= cost:
                self.tokens -= cost
                return True
            return False

limiter = TokenBucket(rate=5, capacity=10)   # 5/s sustained, burst of 10
if limiter.allow():
    do_request()
```

The bucket refills continuously at `rate` and caps at `capacity` (the allowed burst). Critical details: refill is computed **lazily** from elapsed time on each call (no background thread needed), `time.monotonic()` avoids wall-clock jumps, and the **lock** makes the read-refill-deduct sequence atomic so two threads can't both spend the last token. A token bucket allows short bursts while bounding the long-run average — the standard choice over a fixed-window counter, which permits double-rate spikes at window boundaries.

#### Q121. [Practical] A datetime bug only happens for some users twice a year. What's almost certainly wrong, and how do you fix the class of bug?

Twice a year, for some users, is the signature of a **daylight saving time (DST) transition combined with naive datetimes or fixed UTC offsets**. Symptoms: a one-hour error, a nonexistent local time (spring-forward gap), or an ambiguous time (fall-back hour that occurs twice). Fixed offsets like `timezone(timedelta(hours=-5))` are wrong half the year because the offset itself changes with DST.

The fix is to use real **IANA time zones** via `zoneinfo`, which encodes the DST rules:

```python
from datetime import datetime
from zoneinfo import ZoneInfo

ny = ZoneInfo("America/New_York")
dt = datetime(2026, 11, 1, 1, 30, tzinfo=ny)   # the fall-back hour
dt.fold                                          # 0 or 1 disambiguates the repeat
```

Discipline that prevents the whole class: **store and compute in UTC** (timezone-aware), attach the zone only at the display boundary, never do arithmetic on naive datetimes, and use `zoneinfo` (or a maintained tz database) rather than hardcoded offsets. For "wall clock" scheduling ("9am local every day"), store the zone name, not an offset, so DST is recomputed correctly.

### 🔴 — extended

#### Q122. [Practical] Under the free-threaded (no-GIL) build, code that was correct on the GIL build now corrupts a shared list. Explain and fix.

On the GIL build, many compound operations *appeared* atomic because the GIL was only released at bytecode boundaries, so a single `list.append` couldn't be interleaved. Code relied on this accidental atomicity without locks. Under free-threaded CPython (PEP 703), the GIL no longer serializes bytecode, so two threads can interleave a read-modify-write and corrupt shared state or lose updates.

```python
# Looks atomic; ISN'T a safe critical section across threads
counter = 0
def bump():
    global counter
    counter += 1          # LOAD, ADD, STORE — interleavable -> lost updates
```

The fix is to do explicit synchronization that was always *technically* required but masked by the GIL:

```python
import threading
lock = threading.Lock()
def bump():
    global counter
    with lock:
        counter += 1
```

The broader lesson: the GIL was never a substitute for proper locking, only an accident that hid the absence of it. Migrating to free-threading means auditing shared mutable state and adding real synchronization (`Lock`, `queue.Queue`, atomics, or immutable/thread-local designs). Individual built-in operations like a single `list.append` remain internally safe (CPython locks the object), but **sequences** of operations and check-then-act patterns do not, and never did.

#### Q123. [Practical] A pickle-based cache silently returns stale or wrong objects after a class refactor, and a security review flags pickle entirely. Address both.

Two independent problems. **Correctness:** pickle stores the qualified class path and reconstructs by importing it and restoring `__dict__` without calling `__init__`. After you rename/move the class, add a field, or change `__init__` invariants, unpickling old data yields objects that import-fail, miss new attributes, or violate invariants the constructor used to enforce. Fixes: version your payloads and implement `__setstate__` to migrate old state, or — better — stop pickling cross-version data and use an explicit, schema'd format (JSON, Protobuf, msgpack) where you control compatibility.

**Security:** `pickle.loads` can execute arbitrary code during reconstruction (via `__reduce__`/`__setstate__`), so unpickling **untrusted** data is a remote-code-execution hole. There is no safe `pickle.loads` for hostile input.

```python
# NEVER do this on data from a client, queue, or cache an attacker can reach
obj = pickle.loads(untrusted_bytes)   # arbitrary code execution
```

The remediation: restrict pickle to fully trusted, same-version, internal data; for anything crossing a trust boundary use a data-only format and validate it (e.g. Pydantic). If pickle is unavoidable across versions, pin the version, sign the payloads (HMAC) so they can't be tampered with, and gate deserialization behind that signature check.

#### Q124. [Coding] Build a context manager that temporarily patches multiple attributes and guarantees restoration even on error.

```python
from contextlib import contextmanager

@contextmanager
def patched(obj, **overrides):
    sentinel = object()
    saved = {name: getattr(obj, name, sentinel) for name in overrides}
    for name, value in overrides.items():
        setattr(obj, name, value)
    try:
        yield obj
    finally:
        for name, old in saved.items():
            if old is sentinel:
                delattr(obj, name)        # attribute didn't exist before
            else:
                setattr(obj, name, old)   # restore prior value

class Cfg: timeout = 30
with patched(Cfg, timeout=1, retries=5):
    assert Cfg.timeout == 1 and Cfg.retries == 5
assert Cfg.timeout == 30 and not hasattr(Cfg, "retries")
```

The load-bearing parts: a **sentinel** distinguishes "attribute was absent" from "attribute was `None`/falsy," so restoration correctly *deletes* attributes that didn't exist rather than leaving a stale value; and the **`finally`** guarantees restoration even if the body raises. This is the pattern `unittest.mock.patch` and pytest's `monkeypatch` generalize — understanding it explains why those tools restore state reliably and why save-then-restore must handle the "did not exist" case explicitly.

#### Q125. [Practical] You must make a CPU-bound hot path 10x faster, and pure-Python tuning has plateaued. Lay out the escalation path and the trade-offs.

When you've exhausted algorithmic wins, `__slots__`, lookup hoisting, and the adaptive interpreter, the escalation ladder is roughly:

1. **Vectorize with NumPy** — if the work is numeric over arrays, rewriting loops as array ops moves the inner loop into C and is often the biggest single win for the least code. Trade-off: requires reshaping the problem into arrays.
2. **Numba `@njit`** — JIT-compiles numeric Python to machine code with a decorator, releasing the GIL. Low effort; trade-off: only numeric/array code, adds a heavy dependency and warm-up cost.
3. **Cython** — annotate types and compile to C. More effort and a build step, but works for general code and integrates with C libraries.
4. **A Rust extension via PyO3 / maturin** (or C/C++) — the modern choice for a self-contained, memory-safe native module that can release the GIL and even parallelize internally. Highest effort and a toolchain, but the best ceiling and maintainability for a critical kernel.
5. **Multiprocessing** if the work is embarrassingly parallel and you'd rather scale across cores than rewrite in a native language — trade-off: IPC/pickling overhead and process management.
6. **The free-threaded build** to get real thread parallelism for pure-Python CPU work without IPC — promising in 2026 but still maturing, with single-thread overhead and C-extension compatibility to verify.

The meta-point an interviewer wants: **profile to find the true hot kernel, then push only that kernel down a level**, keeping the surrounding code in ergonomic Python. You rarely rewrite the whole service — you isolate the 5% that matters and accelerate it, weighing each step's added build/deploy complexity against the measured speedup.

#### Q126. [Coding] Implement `__init_subclass__`-based plugin registration that validates subclasses at definition time.

```python
class Plugin:
    registry: dict[str, type] = {}

    def __init_subclass__(cls, *, name=None, **kwargs):
        super().__init_subclass__(**kwargs)
        if name is None:
            raise TypeError(f"{cls.__name__} must pass name= in class definition")
        if name in Plugin.registry:
            raise ValueError(f"duplicate plugin name: {name!r}")
        if not callable(getattr(cls, "run", None)):
            raise TypeError(f"{cls.__name__} must define run()")
        Plugin.registry[name] = cls

class CsvPlugin(Plugin, name="csv"):
    def run(self): ...

# class Bad(Plugin, name="csv"): ...   -> ValueError at DEFINITION time
# class NoRun(Plugin, name="x"): ...   -> TypeError: must define run()
```

`__init_subclass__` runs **once per subclass, at class-creation time**, so misconfigured plugins fail at import rather than at first use — the errors surface during deployment, not in production traffic. It accepts class-keyword arguments (`name="csv"`), giving a clean declarative API. This is the modern, lighter-weight alternative to a metaclass: you get definition-time validation and auto-registration without the conceptual weight and inheritance-conflict pitfalls of `type` subclassing. Reach for a metaclass only when you must control the *creation* of the class object itself (e.g. rewrite the namespace); for "do something when a subclass is defined," `__init_subclass__` is the right tool.

#### Q127. [Practical] A Python service has acceptable median latency but a terrible p99 that spikes periodically. The CPU profile looks flat. What systemic causes do you suspect?

Periodic p99 spikes with a flat CPU profile point to **pauses and contention** rather than slow code — the work isn't expensive, something is *stalling* it intermittently:

- **GC pauses.** The cyclic collector runs generationally; a service that churns many container/cyclic objects triggers periodic stop-the-world-ish collections. Diagnose with `gc.callbacks`/`gc.get_stats()`; mitigate by reducing allocations, tuning thresholds, or `gc.freeze()` after startup so long-lived objects aren't rescanned. In extreme cases, disabling GC and managing cycles manually.
- **Lock / GIL contention** under bursts — threads queueing on a lock or on the GIL show up as latency, not CPU. Look at wait time, not just CPU time.
- **Connection-pool exhaustion** — requests blocking to acquire a DB/HTTP connection when the pool is undersized for the burst.
- **Periodic background work** — cache refreshes, metric flushes, log rotation, or a cron-like task contending with request handling.
- **Memory pressure / swapping**, or allocator behavior (consider `jemalloc`/`mimalloc` via `LD_PRELOAD` for fragmentation).
- **Upstream/downstream tail latency** propagating in (a slow dependency at its own p99).

The investigative move is to switch from CPU profiling to **latency/wait analysis**: capture stacks *during* a spike with `py-spy` (it samples a live process without instrumentation), correlate spikes with GC events and pool metrics, and look at time spent waiting rather than computing. Tail latency is usually a systems problem — pauses, queuing, contention — not an algorithmic one.

#### Q128. [Coding] Demonstrate a descriptor-based typed/validated attribute, and explain why a plain `@property` per field doesn't scale.

```python
class Typed:
    def __init__(self, expected_type, *, validator=None):
        self.expected_type = expected_type
        self.validator = validator

    def __set_name__(self, owner, name):
        self.private = f"_{name}"      # the descriptor learns its own attr name

    def __get__(self, obj, objtype=None):
        if obj is None:
            return self
        return getattr(obj, self.private)

    def __set__(self, obj, value):
        if not isinstance(value, self.expected_type):
            raise TypeError(f"{self.private[1:]} must be {self.expected_type.__name__}")
        if self.validator and not self.validator(value):
            raise ValueError(f"invalid value for {self.private[1:]}: {value!r}")
        setattr(obj, self.private, value)

class Account:
    balance = Typed(int, validator=lambda v: v >= 0)
    owner   = Typed(str)

a = Account()
a.balance = 100        # ok
# a.balance = -5       -> ValueError
# a.balance = "x"      -> TypeError
```

A `@property` couples the validation logic to one specific attribute, so N validated fields means N nearly identical property triples — pure duplication. A **descriptor** is a reusable validation policy *object*: define it once, declare it as a class attribute per field, and `__set_name__` (3.6+) lets each instance of the descriptor learn the attribute name it manages, so there's no manual wiring. This is exactly how libraries like Django's model fields, SQLAlchemy columns, and Pydantic-style validation work under the hood — descriptors are the mechanism that makes declarative, per-field behavior reusable across many fields and classes.

#### Q129. [Behavioral] Tell me about a time a "harmless" Python idiom caused a production incident, and what you changed afterward.

Strong answers name a concrete, specifically-Python trap and show systemic follow-up, not just a one-line fix. A representative example: a service accumulated memory and OOM-killed nightly. The cause was a module-level `functools.lru_cache(maxsize=None)` on a function keyed by a per-request ID — every unique request added a permanent entry, an unbounded cache masquerading as a harmless optimization. The immediate fix was a bounded `maxsize` plus a TTL; the real fix was systemic.

What a senior engineer emphasizes is the *system* change, not the patch: I added a memory-growth alert and a canary that runs a load soak before promotion, so unbounded growth is caught pre-production; I wrote a lint rule flagging `lru_cache(maxsize=None)` on functions with high-cardinality arguments; and I ran a brown-bag on the specific CPython footguns — mutable defaults, unbounded caches, `except: pass`, retaining references in long-lived structures — because these are *language-level* traps that code review keeps missing. The narrative arc interviewers reward: detected via observability, root-caused to a specific Python semantic, fixed the instance, then closed the *class* of bug with tooling and shared knowledge so it can't recur silently. Owning the gap honestly ("the idiom was technically wrong and the GIL/refcounting hid it") signals the seniority to reason about why the idiom was dangerous in the first place.

## ✅ Key Takeaways

- **Everything is an object**, and the data model (dunder methods) is the universal interface — understanding `__eq__`/`__hash__`, `__iter__`, `__enter__`/`__exit__`, and descriptors explains most of Python's "magic."
- **Mutability, identity, and aliasing** are the root of the most common bugs: use `is` only for singletons, beware mutable defaults and shared references, and know shallow vs deep copy.
- **The GIL** means CPython threads don't parallelize CPU work — pick `multiprocessing` for CPU-bound, `threading`/`asyncio` for I/O-bound. The free-threaded build is changing this, but gradually.
- **Generators and iterators** give lazy, constant-memory pipelines; comprehensions and `itertools`/`functools`/`collections` make idiomatic Python concise and fast.
- **Type hints + mypy/pyright, dataclasses, and modern packaging** (`pyproject.toml`, lockfiles, src layout) are how you keep dynamic Python maintainable at scale.
- **Memory** is reclaimed deterministically by reference counting, with a cyclic GC for reference cycles; `weakref` and bounded caches prevent unbounded growth.

## ⚠️ Common Pitfalls

- **Mutable default arguments** (`def f(x, acc=[])`) — share state across calls; use the `None` sentinel.
- **Late-binding closures in loops** (`[lambda: i for i in range(3)]` → all `2`) — bind with a default arg.
- **`is` vs `==`** — using `is` for value comparison; relying on int/str interning.
- **`list.reverse()`/`list.sort()` return `None`** — they mutate in place; `x = lst.sort()` gives `None`.
- **Modifying a list/dict while iterating it** — raises `RuntimeError` or skips elements; iterate a copy or build a new collection.
- **Catching too broadly** (bare `except:`) — swallows `KeyboardInterrupt`/`SystemExit` and hides bugs.
- **Blocking calls inside `async`** — `time.sleep` or sync DB drivers stall the entire event loop.
- **Forgetting `functools.wraps`** in decorators — clobbers the wrapped function's name, docstring, and signature.
- **Unbounded `lru_cache`/global caches** — silent memory growth in long-running services.
- **Mixing `str` and `bytes`** — `TypeError`/`UnicodeDecodeError`; decode at input, encode at output, always specify encoding.

## 📚 Further Reading

- *Fluent Python*, 2nd ed. (Luciano Ramalho) — the definitive deep dive into the data model, sequences, and idiomatic Python.
- *Effective Python*, 2nd ed. (Brett Slatkin) — 90 actionable best-practice items.
- *CPython Internals* (Anthony Shaw) — how the interpreter, compiler, and GC actually work.
- The official Python docs: the Language Reference (data model), `typing`, `asyncio`, `collections`, `itertools`, and `functools` module docs.
- PEPs worth reading: PEP 8 (style), PEP 484/544 (typing/protocols), PEP 557 (dataclasses), PEP 703 (free-threading), PEP 659 (specializing interpreter), PEP 621 (project metadata).
- The "Faster CPython" project notes and Python release "What's New" pages for each version (3.11–3.13) to stay current.
