# Scala (Language Deep-Dive)

[← Back to master index](../README.md)

Scala fuses object-oriented and functional programming on top of the JVM, giving Java engineers a more expressive type system, immutable-first idioms, and powerful pattern matching while retaining full interop with Java libraries. The language is the backbone of big-data tooling such as Apache Spark and the Akka/Pekko actor ecosystem, so fluency pays off well beyond "a nicer Java." This guide walks from the fundamentals through the type system, concurrency, and metaprogramming, current to Scala 3.x (the `given`/`using` era) while noting where Scala 2 still differs.

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What does it mean that Scala is both object-oriented and functional?

Scala is uniformly object-oriented: *every* value is an object, including numbers (`Int` is a class, not a primitive at the source level) and functions (a function value is an instance of a `FunctionN` trait with an `apply` method). There are no static members; the `object` keyword gives you singletons instead. At the same time Scala embraces functional programming: functions are first-class values you can pass and return, immutability is the default style, and expressions (not statements) are the building blocks — `if`, `match`, and even blocks all return values.

```scala
val max = if (a > b) a else b   // 'if' is an expression that yields a value
val f: Int => Int = x => x + 1  // a function value, an object with apply()
```

The JVM still uses real primitives under the hood for performance; Scala's "everything is an object" is a *source-level* uniformity that the compiler optimizes away. The practical upshot: you get Java-grade performance and interop, but program in a higher-level, expression-oriented style.

### Q2. [Theory] What is the difference between `val`, `var`, and `lazy val`?

- `val` is an **immutable** binding: it is assigned once and cannot be reassigned (like Java's `final`). The *reference* is fixed; if it points to a mutable object, that object can still change.
- `var` is a **mutable** binding: it can be reassigned. Idiomatic Scala minimizes `var`.
- `lazy val` is an immutable binding whose initializer runs **once, on first access**, then caches the result. Useful for expensive or order-dependent initialization.

```scala
val x = 10            // cannot reassign x
var y = 20; y = 21    // ok
lazy val z = {        // body runs only when z is first read
  println("computing"); 42
}
println(z)            // prints "computing" then 42
println(z)            // prints 42 (no recompute)
```

`lazy val` initialization is thread-safe (the compiler guards it), which adds a small synchronization cost on first access — fine for setup, avoid in tight inner loops.

### Q3. [Theory] Why does Scala favor immutability, and what are the practical benefits?

Immutable values cannot change after construction, which buys you three things: (1) **thread safety for free** — immutable data can be shared across threads without locks because there is no write to race on; (2) **easier reasoning** — a value's meaning is stable, so you can substitute it anywhere it appears (referential transparency); and (3) **safe sharing/structural reuse** — persistent collections can share internal structure between an "old" and "modified" copy instead of deep-copying.

```scala
val a = List(1, 2, 3)
val b = 0 :: a   // b shares a's nodes; a is unchanged
// a == List(1,2,3), b == List(0,1,2,3)
```

The cost is that "updates" allocate new objects, but persistent data structures make this cheap (often O(log n) or O(1) sharing), and the JVM's generational GC handles short-lived garbage well.

### Q4. [Theory] What is a case class and what does the compiler generate for it?

A `case class` is a class designed to model immutable data. From a single declaration the compiler generates: a public immutable `val` for each constructor parameter; a companion `object` with an `apply` factory (so you write `Point(1, 2)` with no `new`); structural `equals`/`hashCode` (compared by field values, not identity); a readable `toString`; a `copy` method for making modified duplicates; and an `unapply` extractor so the class can be used in pattern matching.

```scala
case class Point(x: Int, y: Int)

val p = Point(1, 2)        // apply, no 'new'
val q = p.copy(y = 9)      // Point(1, 9)
p == Point(1, 2)           // true — structural equality
val Point(a, b) = p        // destructuring via unapply
```

Case classes are the workhorse for domain models, AST nodes, and message types in actor systems, precisely because they give you value semantics and pattern-match support out of the box.

### Q5. [Coding] Write a case class hierarchy for a shape and pattern-match to compute area.

Use a `sealed trait` as the base so the compiler knows the full set of subtypes and can warn on a non-exhaustive `match`.

```scala
sealed trait Shape
case class Circle(r: Double) extends Shape
case class Rectangle(w: Double, h: Double) extends Shape
case class Square(side: Double) extends Shape

def area(s: Shape): Double = s match
  case Circle(r)       => math.Pi * r * r
  case Rectangle(w, h) => w * h
  case Square(side)    => side * side

area(Circle(2.0))        // 12.566...
area(Rectangle(3, 4))    // 12.0
```

Because `Shape` is `sealed`, all subtypes live in this file; if you add `case class Triangle(...)` and forget a `match` arm, the compiler emits a non-exhaustiveness warning — a cheap, compiler-enforced safety net.

### Q6. [Theory] What is pattern matching and how is it richer than a Java `switch`?

`match` tests a value against a sequence of patterns and evaluates the first that matches, returning a value. Beyond constant matching it supports: **deconstruction** of case classes and tuples, **type patterns** (`case s: String`), **guards** (`if` conditions), **binding** (`x @ pattern`), **sequence patterns** (`case List(a, b, _*)`), and literal/wildcard matching.

```scala
def describe(x: Any): String = x match
  case 0                     => "zero"
  case n: Int if n < 0       => "negative int"
  case s: String             => s"string of length ${s.length}"
  case (a, b)                => s"pair $a,$b"
  case head :: tail          => s"non-empty list starting $head"
  case _                     => "something else"
```

This makes `match` a control-flow *and* destructuring construct in one, far beyond Java's value-only `switch` (though Java has been catching up with pattern matching for `switch`).

### Q7. [Theory] What is `Option` and why use it instead of `null`?

`Option[A]` is a container that is either `Some(value)` or `None`, encoding "a value may be absent" in the *type system* rather than relying on `null`. This forces callers to handle absence and eliminates `NullPointerException` from your own code paths.

```scala
def find(id: Int): Option[User] = ...

find(1) match
  case Some(u) => println(u.name)
  case None    => println("not found")

// Idiomatic combinator style:
val name = find(1).map(_.name).getOrElse("anonymous")
```

`Option` is also a monad-like type with `map`, `flatMap`, `filter`, and works in for-comprehensions, so you can chain "may-fail" lookups without nested null checks.

### Q8. [Practical] Convert this null-returning Java-style code to use `Option`.

```scala
// Before: nested null checks
def cityOf(u: User): String =
  if (u != null && u.address != null && u.address.city != null)
    u.address.city
  else "unknown"

// After: Option chaining (assuming address/city return Option)
def cityOf(u: User): String =
  Option(u)                       // wrap a possibly-null reference
    .flatMap(_.address)           // Option[Address]
    .flatMap(_.city)              // Option[String]
    .getOrElse("unknown")

// Or as a for-comprehension:
def cityOf2(u: User): String =
  (for
     usr  <- Option(u)
     addr <- usr.address
     city <- addr.city
   yield city).getOrElse("unknown")
```

`Option(x)` safely wraps a possibly-null Java value: a `null` becomes `None`, anything else `Some(x)`. The `flatMap` chain short-circuits at the first `None`, replacing the pyramid of null checks.

### Q9. [Theory] What are higher-order functions? Give examples from the collections API.

A higher-order function takes one or more functions as parameters and/or returns a function. The collections library is built on them: `map`, `filter`, `flatMap`, `foldLeft`, `reduce`, `groupBy`, `sortBy`, and so on.

```scala
val nums = List(1, 2, 3, 4, 5)
nums.map(_ * 2)             // List(2,4,6,8,10)
nums.filter(_ % 2 == 0)    // List(2,4)
nums.foldLeft(0)(_ + _)    // 15
nums.groupBy(_ % 2)        // Map(1 -> List(1,3,5), 0 -> List(2,4))
```

The `_` is placeholder syntax for a concise lambda: `_ * 2` is `x => x * 2`. Higher-order functions let you express *what* to compute declaratively, leaving iteration mechanics to the library.

### Q10. [Coding] Implement `map` and `filter` for a simple linked list to show how HOFs work.

```scala
enum MyList[+A]:
  case Nil
  case Cons(head: A, tail: MyList[A])

import MyList.*

def map[A, B](xs: MyList[A])(f: A => B): MyList[B] = xs match
  case Nil            => Nil
  case Cons(h, t)     => Cons(f(h), map(t)(f))

def filter[A](xs: MyList[A])(p: A => Boolean): MyList[A] = xs match
  case Nil                   => Nil
  case Cons(h, t) if p(h)    => Cons(h, filter(t)(p))
  case Cons(_, t)            => filter(t)(p)

val l = Cons(1, Cons(2, Cons(3, Nil)))
map(l)(_ * 10)        // Cons(10, Cons(20, Cons(30, Nil)))
filter(l)(_ % 2 == 1) // Cons(1, Cons(3, Nil))
```

`f: A => B` is the function parameter — that's what makes `map`/`filter` higher-order. Note the second parameter list `(f: ...)`: currying like this helps type inference and enables clean call syntax.

### Q11. [Theory] What is a for-comprehension and how does it desugar?

A for-comprehension is syntactic sugar over `map`, `flatMap`, `withFilter`, and `foreach`. It reads imperatively but compiles to combinator calls, so it works on *any* type that defines those methods — collections, `Option`, `Either`, `Try`, `Future`, etc.

```scala
val result =
  for
    x <- List(1, 2)
    y <- List(10, 20)
    if x + y > 12
  yield x + y
// desugars to:
List(1, 2).flatMap(x => List(10, 20).withFilter(y => x + y > 12).map(y => x + y))
// result: List(21, 22)   ((1,20)->21 and (2,20)->22 satisfy x+y>12; 11 and 12 are filtered out)
```

Rule of thumb: a single generator with `yield` becomes `map`; multiple generators chain `flatMap` ending in `map`; an `if` becomes `withFilter`; no `yield` becomes `foreach`. This one construct unifies "loops," monadic chaining, and parallel composition.

### Q12. [Theory] What is the difference between `def`, `val`, and `lazy val` for defining a function/value?

- `def` defines a **method**; its body is re-evaluated on *every* call. Use for actual computations or parameterized logic.
- `val` evaluates its right-hand side **once, eagerly, at definition time**, and stores the result. If the RHS is a function literal, you get a reusable function object.
- `lazy val` evaluates **once, on first access**, then caches.

```scala
def randDef = math.random()   // new value each call
val randVal = math.random()   // one fixed value, captured now
lazy val randLazy = math.random() // one value, captured on first read
```

A common subtlety: `val f = (x: Int) => x + 1` creates a single function object, whereas `def f(x: Int) = x + 1` creates a method that must be eta-expanded (`f _` in Scala 2) to be used as a value.

### Q13. [Practical] How do you read a file and count word frequencies idiomatically?

```scala
import scala.io.Source
import scala.util.Using

val counts: Map[String, Int] =
  Using.resource(Source.fromFile("book.txt")) { src =>
    src.getLines()
       .flatMap(_.split("\\W+"))
       .filter(_.nonEmpty)
       .map(_.toLowerCase)
       .toList
       .groupMapReduce(identity)(_ => 1)(_ + _)
  }

counts.toSeq.sortBy(-_._2).take(5).foreach(println)
```

`Using.resource` guarantees the file handle is closed even on exception (the loan pattern). `groupMapReduce(key)(value)(combine)` is a one-pass idiom: group by the word, map each to `1`, and reduce with `+` — equivalent to a group-by then count but more efficient.

### Q14. [Theory] What are tuples and when would you use them?

A tuple is a fixed-size, heterogeneous, immutable ordered collection: `(1, "a", true)` has type `(Int, String, Boolean)`. Access elements by `._1`, `._2`, … or destructure with a pattern. Tuples are ideal for returning multiple values from a function without defining a named type, and for transient pairings (e.g., map entries are `(key, value)` tuples).

```scala
def minMax(xs: List[Int]): (Int, Int) = (xs.min, xs.max)
val (lo, hi) = minMax(List(3, 1, 4, 1, 5))  // lo=1, hi=5
```

For anything that lives beyond a single expression or crosses an API boundary, prefer a `case class` so the fields have meaningful names; tuples beyond `_2`/`_3` quickly become unreadable.

### Q15. [Theory] What is a companion object?

A companion object is an `object` with the **same name** as a class (or trait) declared in the **same file**. The class and its companion can access each other's private members. Companions are where you put factory methods (`apply`), constants, and static-like utilities — Scala has no `static` keyword, so the companion object fills that role.

```scala
class Circle private (val r: Double):
  def area = math.Pi * r * r

object Circle:                       // companion
  def apply(r: Double): Circle =     // smart constructor / factory
    require(r >= 0, "radius must be non-negative")
    new Circle(r)

val c = Circle(2.0)   // calls Circle.apply
```

For case classes, the compiler generates the companion (with `apply`/`unapply`) automatically; you can still add your own members to it.

### Q16. [Theory] What is the unified type hierarchy (`Any`, `AnyRef`, `AnyVal`, `Nothing`, `Unit`)?

`Any` is the root of all types. It splits into `AnyVal` (value types: `Int`, `Double`, `Boolean`, `Char`, `Unit`, …) and `AnyRef` (reference types — equivalent to Java's `Object`). At the bottom sits `Nothing`, a subtype of *every* type, which has no instances and types expressions that never return normally (e.g., `throw`). `Null` is a subtype of all `AnyRef` types. `Unit` is the value-type with a single value `()`, used like Java's `void`.

```
            Any
           /   \
      AnyVal    AnyRef (= java Object)
      /  |  \      |  \
   Int Double Unit ... String, user classes
           \      /
           Nothing  (bottom: subtype of all)
```

`Nothing` is what makes `if (c) x else throw e` type-check as `x`'s type: the `throw` branch is `Nothing`, which unifies with anything.

### Q17. [Coding] Reverse a list and check if it is a palindrome, recursively.

```scala
def reverse[A](xs: List[A]): List[A] =
  xs.foldLeft(List.empty[A])((acc, x) => x :: acc)

def isPalindrome[A](xs: List[A]): Boolean =
  xs == reverse(xs)

reverse(List(1, 2, 3))       // List(3, 2, 1)
isPalindrome(List(1,2,3,2,1)) // true
```

`foldLeft` with `x :: acc` prepends each element to an accumulator, naturally producing the reversed list in O(n). Prepending to a `List` is O(1); appending would be O(n), so this is the idiomatic, efficient reverse.

### Q18. [Theory] What is the difference between `==`, `eq`, and `equals` in Scala?

- `==` calls `equals` (after a null-safe check); for case classes this is **structural** equality. This differs from Java where `==` is reference equality.
- `equals` is the overridable structural-equality method.
- `eq` (and `ne`) on `AnyRef` is **reference identity** — true only if both sides are the same object.

```scala
val a = List(1, 2); val b = List(1, 2)
a == b     // true  (structural)
a eq b     // false (different objects)
a eq a     // true
```

So in Scala you almost always use `==` for "are these equal in value," and reach for `eq` only when you specifically need identity.

## 🟡 Intermediate (3–7 yrs)

### Q19. [Theory] What are traits, and how do they differ from Java interfaces and abstract classes?

A `trait` is a unit of reusable behavior that can contain abstract *and* concrete members (methods, fields, type members). Classes `extends` one trait and mix in more with `with`. Unlike a Java pre-8 interface, traits can carry implementation and state; unlike a class, a trait cannot take constructor parameters in Scala 2 (Scala 3 allows trait parameters) and a class can mix in many traits but extend only one class.

```scala
trait Greeter:
  def name: String                 // abstract
  def greet(): String = s"Hi, $name" // concrete

trait Logger:
  def log(msg: String): Unit = println(s"[log] $msg")

class Person(val name: String) extends Greeter with Logger
new Person("Ada").greet()  // "Hi, Ada"
```

Traits are Scala's mechanism for mixin composition and are far more flexible than single inheritance.

### Q20. [Theory] How does Scala resolve the diamond problem with traits (linearization)?

When a class mixes in multiple traits that share a supertype or override the same method, Scala computes a deterministic **linearization** — a total order of the class and all its mixed-in traits — and method resolution walks that order. `super` in a trait refers to the *next* type in the linearization, not a fixed parent, which enables **stackable modifications**.

```scala
trait A:           def f = "A"
trait B extends A: override def f = "B->" + super.f
trait C extends A: override def f = "C->" + super.f
class D extends B with C
// linearization (right-to-left, last wins): D, C, B, A
new D().f   // "C->B->A"
```

The rule: traits are linearized right-to-left, with duplicates removed keeping the *last* occurrence. Understanding linearization is essential when stacking traits that each call `super`.

### Q21. [Theory] What is the difference between `Either`, `Try`, and `Option` for error handling?

- `Option[A]` models presence/absence: `Some`/`None`. No error detail.
- `Either[L, R]` models a value that is one of two types; by convention `Left` is the error/failure and `Right` is success. You choose the error type `L` (often a sealed error ADT or a `String`).
- `Try[A]` models a computation that may throw: `Success(a)` or `Failure(exception)`. It captures a `Throwable`, so it's the bridge for code that throws.

```scala
def parse(s: String): Try[Int]     = Try(s.toInt)
def validate(n: Int): Either[String, Int] =
  if n > 0 then Right(n) else Left("must be positive")

val r: Either[String, Int] =
  for
    n <- parse("42").toEither.left.map(_.getMessage)
    v <- validate(n)
  yield v
```

Rule of thumb: `Option` for "maybe missing," `Either` for "domain errors you model explicitly," `Try` for "wraps exception-throwing code."

### Q22. [Coding] Write a safe division pipeline using `Either` in a for-comprehension.

```scala
def safeDiv(a: Int, b: Int): Either[String, Int] =
  if b == 0 then Left("divide by zero") else Right(a / b)

def pipeline(x: Int, y: Int, z: Int): Either[String, Int] =
  for
    p <- safeDiv(x, y)   // short-circuits to Left on failure
    q <- safeDiv(p, z)
  yield q + 1

pipeline(100, 5, 2)   // Right(11)   (100/5=20, 20/2=10, +1)
pipeline(100, 0, 2)   // Left("divide by zero")
```

Because `Either` is right-biased (its `map`/`flatMap` operate on `Right`), the for-comprehension threads the happy path and short-circuits to the first `Left`, accumulating no further computation — exactly the behavior you want for sequential validation.

### Q23. [Theory] What is the difference between strict and lazy collections (`List` vs `LazyList`/`view`)?

Strict collections (`List`, `Vector`, `Set`, `Map`) evaluate every element and every transformation immediately, materializing intermediate collections. A `LazyList` (Scala 2.13+, formerly `Stream`) and a `.view` are **lazy**: elements are computed on demand and transformations are *fused* so no intermediate collection is built.

```scala
// Strict: builds an intermediate 1-million List, then takes 3 -> wasteful
(1 to 1000000).toList.map(_ * 2).filter(_ % 3 == 0).take(3)

// Lazy view: only computes enough elements to satisfy take(3)
(1 to 1000000).view.map(_ * 2).filter(_ % 3 == 0).take(3).toList

// LazyList can even be infinite:
lazy val nats: LazyList[Int] = 0 #:: nats.map(_ + 1)
nats.take(5).toList   // List(0,1,2,3,4)
```

Use lazy evaluation for large/infinite sequences or to avoid intermediate allocations in long transformation chains; use strict collections when you need the whole result anyway and want predictable, eager evaluation.

### Q24. [Theory] Compare `List`, `Vector`, and `Array` and when to use each.

- `List` — singly linked, immutable. O(1) prepend and head/tail; O(n) random access and append. Best for stack-like, recursive, head-processing workloads.
- `Vector` — immutable, bit-mapped trie. Effectively O(1) (O(log32 n)) random access, update, append, and prepend. The default general-purpose immutable indexed sequence.
- `Array` — mutable, fixed-size, backed by a JVM primitive array. O(1) indexed access/update, best raw performance and memory layout, but mutable and invariant. Use for performance-critical numeric code or Java interop.

```
List:   fast head/prepend, slow index   -> recursion, FIFO build-then-reverse
Vector: balanced all-around             -> default indexed sequence
Array:  fastest, mutable, JVM-native    -> hot loops, interop, Spark internals
```

Default to `Vector` for indexed access, `List` for pattern-matched recursion, and `Array` only when you've measured a need.

### Q25. [Practical] Refactor an imperative loop into a functional fold.

```scala
// Imperative: sum of squares of even numbers
def sumSqEvenImp(xs: List[Int]): Int =
  var acc = 0
  for x <- xs do
    if x % 2 == 0 then acc += x * x
  acc

// Functional
def sumSqEven(xs: List[Int]): Int =
  xs.filter(_ % 2 == 0).map(x => x * x).sum

// Single pass with foldLeft (no intermediate lists):
def sumSqEvenFold(xs: List[Int]): Int =
  xs.foldLeft(0)((acc, x) => if x % 2 == 0 then acc + x * x else acc)
```

The `filter/map/sum` version is the most readable; the `foldLeft` version is a single traversal with no intermediate collections — choose based on whether clarity or allocation pressure matters more. Both eliminate the mutable `var`.

### Q26. [Theory] What are implicits in Scala 2 and how do `given`/`using` replace them in Scala 3?

In Scala 2, `implicit` served several distinct jobs through one overloaded keyword: implicit parameters (auto-supplied arguments), implicit conversions (auto type coercion), and extension methods (via implicit classes). This overloading made code hard to read. Scala 3 splits these into clearer constructs:

- **Implicit parameters → `using`/`given`.** Declare a parameter `(using ctx: Context)`; supply it with a `given` instance the compiler finds in scope.
- **Implicit conversions → explicit `given Conversion[A, B]`** (and discouraged generally).
- **Extension methods → the `extension` keyword.**

```scala
// Scala 3
trait Show[A]:
  def show(a: A): String

given Show[Int] with
  def show(a: Int) = s"Int($a)"

def display[A](a: A)(using s: Show[A]): String = s.show(a)
display(42)   // "Int(42)" — Show[Int] supplied implicitly via 'given'
```

The Scala 2 equivalent used `implicit val`/`implicit def` and `(implicit s: Show[A])`. Scala 3's split makes the *intent* explicit at each use site.

### Q27. [Coding] Implement a type class for serialization using `given`/`using` (Scala 3).

```scala
trait JsonEncoder[A]:
  def encode(a: A): String

object JsonEncoder:
  def apply[A](using e: JsonEncoder[A]): JsonEncoder[A] = e

given JsonEncoder[Int] with
  def encode(a: Int) = a.toString

given JsonEncoder[String] with
  def encode(a: String) = s"\"$a\""

given [A](using e: JsonEncoder[A]): JsonEncoder[List[A]] with
  def encode(xs: List[A]) = xs.map(e.encode).mkString("[", ",", "]")

def toJson[A](a: A)(using e: JsonEncoder[A]): String = e.encode(a)

toJson(42)                  // "42"
toJson(List("a", "b"))      // "[\"a\",\"b\"]"
```

This is the **type class pattern**: behavior (`JsonEncoder`) is defined separately from data and supplied by `given` instances; the `List` instance is *derived* from the element instance via `using`. The compiler assembles the right encoder at the call site — ad-hoc polymorphism without touching the original types.

### Q28. [Theory] What are extension methods and how do they work in Scala 3?

Extension methods add methods to existing types without modifying or subclassing them. In Scala 3 you use the `extension` keyword; in Scala 2 you used an `implicit class`. The compiler rewrites `value.newMethod(args)` into a call to the extension function.

```scala
extension (s: String)
  def shout: String = s.toUpperCase + "!"
  def words: Int = s.split("\\s+").length

"hello world".shout   // "HELLO WORLD!"
"a b c".words         // 3
```

They're how you make third-party or Java types feel native (e.g., adding domain methods to `String` or `Int`) and are the backbone of many DSLs. Because resolution is static and scoped, they don't have the runtime cost or surprises of monkey-patching in dynamic languages.

### Q29. [Theory] What is a partial function and how does it differ from a regular function?

A `PartialFunction[A, B]` is a function defined only on *some* inputs of type `A`. Besides `apply`, it has `isDefinedAt(x)` to test applicability. A block of `case` clauses (without a `match`) is a `PartialFunction` literal.

```scala
val recip: PartialFunction[Int, Double] =
  case x if x != 0 => 1.0 / x

recip.isDefinedAt(0)   // false
recip.isDefinedAt(2)   // true
List(0, 2, 4).collect(recip)   // List(0.5, 0.25) — undefined inputs skipped
```

`collect` uses `isDefinedAt` to filter-and-map in one pass. Partial functions also compose via `orElse`, letting you build dispatch tables (this is exactly how Akka actors' `receive` is typed: `PartialFunction[Any, Unit]`).

### Q30. [Coding] Build a request router using partial functions and `orElse`.

```scala
type Handler = PartialFunction[String, String]

val users: Handler = { case s"GET /users/$id" => s"user $id" }
val posts: Handler = { case s"GET /posts/$id" => s"post $id" }
val fallback: Handler = { case other         => s"404: $other" }

val router: Handler = users orElse posts orElse fallback

router("GET /users/7")   // "user 7"
router("GET /posts/9")   // "post 9"
router("DELETE /x")      // "404: DELETE /x"
```

`orElse` chains partial functions: each is tried in turn until one's `isDefinedAt` succeeds. The `s"GET /users/$id"` string interpolator pattern even binds path segments. This is a compact, extensible routing table — adding a route is just another `orElse`.

### Q31. [Theory] What is currying and partial application?

A curried function takes its arguments in multiple parameter lists, returning a function after each list. Partial application is supplying some arguments now and getting back a function awaiting the rest.

```scala
def add(a: Int)(b: Int): Int = a + b
val add5 = add(5)        // partially applied: Int => Int
add5(3)                  // 8

// Partial application of a normal method:
def log(level: String, msg: String): Unit = println(s"[$level] $msg")
val warn = log("WARN", _: String)
warn("low disk")         // [WARN] low disk
```

Currying aids type inference (later lists can infer from earlier ones), enables clean DSL/loan-pattern syntax (`using(resource) { ... }`), and lets you specialize functions by fixing leading arguments.

### Q32. [Practical] How do you handle resource cleanup (the loan pattern)?

The loan pattern lends a resource to a block, then guarantees cleanup. Scala's standard `Using` implements it for any `AutoCloseable`/`Releasable`.

```scala
import scala.util.{Using, Try}
import java.io.{BufferedReader, FileReader}

val firstLine: Try[String] =
  Using(new BufferedReader(new FileReader("data.txt"))) { reader =>
    reader.readLine()
  } // reader.close() runs even if readLine throws

// Multiple resources, closed in reverse order:
val combined = Using.Manager { use =>
  val in  = use(new FileReader("a.txt"))
  val out = use(new FileReader("b.txt"))
  (in.read(), out.read())
}
```

`Using(resource)(use)` returns a `Try`, capturing exceptions and ensuring `close()`. `Using.Manager` handles several resources with proper reverse-order cleanup — the idiomatic, exception-safe alternative to manual `try/finally`.

### Q33. [Theory] What is variance? Explain covariance, contravariance, and invariance.

Variance describes how subtyping of a type parameter relates to subtyping of the generic type. Given `A <: B` (A is a subtype of B):

- **Covariant** `[+T]`: `F[A] <: F[B]`. The container varies the same way as its parameter. Used for "producers"/read positions (`List[+A]`, `Option[+A]`).
- **Contravariant** `[-T]`: `F[B] <: F[A]` — the relationship flips. Used for "consumers"/write positions (function arguments, `Function1[-A, +R]`).
- **Invariant** `[T]`: no relationship; `F[A]` and `F[B]` are unrelated even if `A <: B`. Required for read-write/mutable positions (`Array[T]`, `mutable.Buffer[T]`).

```scala
class Animal; class Cat extends Animal
val cats: List[Cat] = List(new Cat)
val animals: List[Animal] = cats   // OK: List is covariant (+A)

// Function1[-A,+R]: a function taking Animal can be used where one taking Cat is expected
val f: Animal => String = _ => "x"
val g: Cat => String = f            // OK: contravariant in the argument
```

The mnemonic is **PECS-like**: covariant for things you produce/return, contravariant for things you consume/accept.

### Q34. [Theory] Why are `Function1` parameters contravariant and results covariant?

A function `A => R` is `Function1[-A, +R]`. Consider substitutability: a function is a *subtype* of another if it can be used wherever the other is expected. It must accept **at least** the expected inputs (so it may accept a *wider/supertype* input — contravariant in `A`) and return **at most** the expected outputs (so it may return a *narrower/subtype* result — covariant in `R`).

```
Want:  Cat => Animal
Have:  Animal => Cat   is a valid substitute because
       - it accepts Animal (⊇ Cat) — safe to feed it a Cat
       - it returns Cat   (⊆ Animal) — caller expecting Animal is satisfied
=> (Animal => Cat) <: (Cat => Animal)
```

This is the canonical illustration of why argument positions are contravariant and return positions covariant, and it falls directly out of the Liskov substitution principle.

### Q35. [Coding] Define a covariant immutable stack and explain why mutability would break it.

```scala
sealed trait Stack[+A]:
  def push[B >: A](b: B): Stack[B] = Cons(b, this)  // lower bound widens
  def top: Option[A]
case object Empty extends Stack[Nothing]:
  def top = None
case class Cons[+A](head: A, tail: Stack[A]) extends Stack[A]:
  def top = Some(head)

val s: Stack[Cat]    = Cons(new Cat, Empty)
val s2: Stack[Animal] = s          // OK: covariant
val s3 = s2.push(new Dog)          // Stack[Animal], B = Animal
```

`push` cannot use the covariant `A` in argument position directly (that would be unsound), so it uses a **lower bound** `[B >: A]`, returning a `Stack[B]`. A *mutable* stack with a `push(a: A): Unit` would need `A` in an invariant write position; allowing covariance there would let you push a `Dog` into a `Stack[Cat]` aliased as `Stack[Animal]` — exactly the unsoundness Scala's variance rules forbid (and why Java arrays' covariance is a known hole).

### Q36. [Theory] What are `Future`s and how do you compose them?

A `Future[T]` represents a value that will be available asynchronously. It runs on an `ExecutionContext` (a thread pool). You don't block on it; you register transformations (`map`, `flatMap`, `recover`) that run when it completes, and compose multiple futures with for-comprehensions.

```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

def fetchUser(id: Int): Future[User] = ...
def fetchOrders(u: User): Future[List[Order]] = ...

val result: Future[List[Order]] =
  for
    user   <- fetchUser(1)        // runs first
    orders <- fetchOrders(user)   // runs after user resolves (sequential)
  yield orders

result.recover { case e => Nil }  // error handling
```

A for-comprehension over futures chains them *sequentially* (each `flatMap` waits for the previous). To run independent futures in **parallel**, start them before the for-comprehension:

```scala
val fa = fetchA(); val fb = fetchB()   // both start now, concurrently
for { a <- fa; b <- fb } yield (a, b)  // combines results
```

### Q37. [Practical] Why must you start independent `Future`s before a for-comprehension to get parallelism?

Because a for-comprehension desugars to nested `flatMap` calls, and each subsequent future is only *constructed* inside the continuation of the previous one. If you write the future-creating calls as generators, the second doesn't even begin until the first completes.

```scala
// SEQUENTIAL (slow): fb is created inside fa's flatMap
for { a <- fetchA(); b <- fetchB() } yield a + b

// PARALLEL (fast): both already running before composition
val fa = fetchA()
val fb = fetchB()
for { a <- fa; b <- fb } yield a + b
```

A `Future` starts executing the moment it is constructed (eagerly submitted to the `ExecutionContext`). So binding them to vals first kicks both off, and the for-comprehension merely *joins* already-running work. This is one of the most common real-world Scala concurrency mistakes.

## 🟠 Advanced (8–12 yrs)

### Q38. [Theory] What is a monad, and which laws must `Option`, `Either`, `Future`, and `List` satisfy?

A monad is a type constructor `M[_]` with two operations: `unit`/`pure` (`A => M[A]`, lift a value) and `flatMap` (`M[A] => (A => M[B]) => M[B]`, sequence dependent computations). It must obey three laws:

1. **Left identity**: `pure(a).flatMap(f) == f(a)`
2. **Right identity**: `m.flatMap(pure) == m`
3. **Associativity**: `m.flatMap(f).flatMap(g) == m.flatMap(x => f(x).flatMap(g))`

```scala
// Option as a monad:
def pure[A](a: A): Option[A] = Some(a)
Some(3).flatMap(x => Some(x + 1))          // Some(4)
// left identity:  pure(3).flatMap(f) == f(3)
// associativity lets for-comprehensions nest safely
```

The practical payoff: any lawful monad works in for-comprehensions with predictable behavior, and you can write *generic* code over "any monad" (e.g., via a `Monad` type class from Cats). `Option`, `Either` (right-biased), `Try`, `List`, and `Future` all satisfy the laws (with `Future` being a "lawful enough" monad caveated by side effects and timing).

### Q39. [Coding] Implement a generic `Monad` type class and a `Box` instance (Scala 3).

```scala
trait Monad[F[_]]:
  def pure[A](a: A): F[A]
  extension [A](fa: F[A])
    def flatMap[B](f: A => F[B]): F[B]
    def map[B](f: A => B): F[B] = fa.flatMap(a => pure(f(a)))

case class Box[A](value: A)

given Monad[Box] with
  def pure[A](a: A): Box[A] = Box(a)
  extension [A](fa: Box[A])
    def flatMap[B](f: A => Box[B]): Box[B] = f(fa.value)

// Generic function over ANY monad:
def double[F[_]: Monad](fa: F[Int]): F[Int] = fa.map(_ * 2)

double(Box(21))   // Box(42)
```

`F[_]` is a **higher-kinded type** parameter — a type that itself takes a type. The `extension` block attaches `flatMap`/`map` to any `F[A]` for which a `Monad[F]` exists, and `double` is polymorphic over the whole monad family via the context bound `[F[_]: Monad]`. This is the foundation of libraries like Cats.

### Q40. [Theory] What are higher-kinded types and why does Scala support them?

A higher-kinded type abstracts over type constructors, not just types. `List` has kind `* -> *` (give it a type, get a type); `Map` has kind `* -> * -> *`. A higher-kinded type parameter `F[_]` lets you write code generic over *any* one-hole container — `Monad[F[_]]`, `Functor[F[_]]`, `Traverse[F[_]]`.

```scala
trait Functor[F[_]]:
  extension [A](fa: F[A]) def map[B](f: A => B): F[B]

// Now you can write code that works for List, Option, Future, ...
def labelAll[F[_]: Functor](fa: F[Int]): F[String] = fa.map(n => s"#$n")
```

Java's generics are limited to first-order (`List<T>`, never `F<_>`), so this kind of abstraction is impossible there. Higher-kinded types are why Scala can host expressive FP libraries (Cats, ZIO) that abstract over effects and containers uniformly.

### Q41. [Theory] How do Akka (or Pekko) actors work, and what problem do they solve?

The actor model encapsulates state behind asynchronous message passing. An actor has private mutable state, a mailbox (queue), and a behavior that processes one message at a time. Because messages are handled sequentially and state is never shared, you get concurrency *without locks* — the actor itself is the unit of single-threaded consistency.

```scala
// Akka Typed (Pekko has the same API under org.apache.pekko)
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

enum Cmd:
  case Increment
  case GetCount(replyTo: ActorRef[Int])

def counter(n: Int): Behavior[Cmd] = Behaviors.receiveMessage {
  case Cmd.Increment        => counter(n + 1)        // new behavior with new state
  case Cmd.GetCount(replyTo) => replyTo ! n; Behaviors.same
}
```

Actors solve the problem of safe concurrent mutable state and form the basis for distributed, resilient systems (supervision hierarchies restart failed actors — "let it crash"). Note: Lightbend moved Akka to a paid license in 2022; **Apache Pekko** is the open-source fork most teams now use, with an essentially identical API.

### Q42. [Theory] Why is Scala the implementation language of Apache Spark, and how do its features map to Spark concepts?

Spark's core is written in Scala, and its RDD/Dataset API mirrors Scala's collections: `map`, `filter`, `flatMap`, `reduce`, `groupBy` look the same but execute on a distributed cluster. Several Scala features are essential to that design:

- **Higher-order functions & closures** — you pass functions (`rdd.map(f)`) that Spark serializes and ships to executors.
- **Immutability** — RDDs/Datasets are immutable; transformations produce new ones, matching Scala's value semantics and enabling lineage-based fault recovery.
- **Lazy evaluation** — transformations are lazy (like `view`/`LazyList`); nothing runs until an *action* (`collect`, `count`) triggers the DAG, enabling whole-pipeline optimization (Catalyst).
- **Case classes & pattern matching** — used to model typed `Dataset` rows and structured data.
- **Implicits/`given`** — `Encoder`s for typed Datasets are supplied implicitly.

```scala
val counts = spark.read.textFile("data")
  .flatMap(_.split("\\W+"))   // lazy transformation
  .filter(_.nonEmpty)
  .groupByKey(identity)
  .count()                     // action: triggers execution
```

Knowing Scala's lazy collections, closures, and serialization caveats (avoid capturing non-serializable state in a closure) is directly applicable to writing correct, performant Spark jobs.

### Q43. [Coding] Write a tail-recursive factorial and explain `@tailrec`.

```scala
import scala.annotation.tailrec

@tailrec
def factorial(n: BigInt, acc: BigInt = 1): BigInt =
  if n <= 1 then acc
  else factorial(n - 1, acc * n)   // tail call: nothing happens after it returns

factorial(5)    // 120
```

A call is in **tail position** if it is the very last action in the function — its result is returned directly with no further computation. The Scala compiler optimizes self-tail-calls into a loop (no new stack frame per call), so `factorial(100000)` won't overflow the stack. The `@tailrec` annotation asks the compiler to *verify* the call is actually a tail call and fail compilation if not — a guard against silently losing the optimization (e.g., the naive `n * factorial(n-1)` is *not* tail-recursive because the multiply happens after the recursive call returns).

```
not tail-recursive:  n * factorial(n-1)   <- multiply pending, stack grows
tail-recursive:      factorial(n-1, acc*n) <- direct return, becomes a loop
```

### Q44. [Coding] Convert a non-tail-recursive Fibonacci into a tail-recursive accumulator version.

```scala
// Naive: exponential time AND grows the stack
def fibSlow(n: Int): BigInt =
  if n < 2 then n else fibSlow(n - 1) + fibSlow(n - 2)

// Tail-recursive, linear time, constant stack:
import scala.annotation.tailrec
def fib(n: Int): BigInt =
  @tailrec def loop(k: Int, prev: BigInt, cur: BigInt): BigInt =
    if k == 0 then prev
    else loop(k - 1, cur, prev + cur)
  loop(n, 0, 1)

fib(50)   // 12586269025, computed in O(n) with no stack growth
```

The accumulator pattern carries the running results (`prev`, `cur`) as parameters so each recursive call is in tail position. This turns an exponential, stack-hungry algorithm into a linear iterative one that the compiler compiles to a loop.

### Q45. [Theory] What are self-types and the cake pattern for dependency injection?

A **self-type** declares that a trait *requires* another type to be mixed in alongside it, without extending it: `trait A { self: B => ... }` means "any concrete class with `A` must also be a `B`," so `A`'s body can use `B`'s members. The **cake pattern** layers component traits this way to wire dependencies at compile time.

```scala
trait UserRepo:
  def find(id: Int): Option[String]

trait UserService:
  self: UserRepo =>                 // requires a UserRepo
  def greet(id: Int): String = find(id).map("Hi " + _).getOrElse("?")

object App extends UserService with UserRepo:
  def find(id: Int) = Some(s"user$id")

App.greet(1)   // "Hi user1"
```

The cake pattern gives compile-time-checked DI with no framework, but it's verbose and can suffer initialization-order pitfalls; many modern codebases prefer constructor injection or `given`-based wiring instead. Knowing it matters because it appears in older Scala codebases and in discussions of structuring large apps.

### Q46. [Behavioral] Tell me about a time you introduced Scala (or a functional approach) to a Java-heavy team. How did you manage adoption?

Structure the answer with situation, action, and result. A strong response shows technical judgment *and* empathy for the team. Example shape: "We had a data-pipeline service in Java that was riddled with null checks and concurrency bugs. I proposed Scala for a new ingestion module because of Spark interop and `Option`/immutability. Rather than a big-bang rewrite, I (1) kept it interop-friendly — Scala calling existing Java libraries; (2) wrote a one-page idioms guide and paired with two engineers; (3) enforced a conservative style (no implicit-conversion magic, limited operator soup) so reviews stayed approachable; (4) set up the build and CI so the friction was low. Result: the module shipped with far fewer NPE/concurrency incidents, and two engineers became comfortable enough to lead the next service."

The key signals interviewers want: you chose Scala for a *concrete* reason (not novelty), you reduced cognitive load by constraining the dialect, you invested in onboarding, and you can quantify the outcome.

### Q47. [Theory] What is the difference between `flatMap`, `flatten`, and `map`, and how do they relate?

- `map(f)` applies `f: A => B` element-wise, yielding `F[B]` of the same shape.
- `flatten` collapses one level of nesting: `F[F[A]] => F[A]`.
- `flatMap(f)` is `map` followed by `flatten`: apply `f: A => F[B]`, then flatten the resulting `F[F[B]]` into `F[B]`.

```scala
val xs = List(1, 2, 3)
xs.map(n => List(n, -n))      // List(List(1,-1), List(2,-2), List(3,-3))
xs.map(n => List(n, -n)).flatten  // List(1,-1,2,-2,3,-3)
xs.flatMap(n => List(n, -n))      // List(1,-1,2,-2,3,-3)  (same, one step)

Option(3).flatMap(n => if n > 0 then Some(n) else None)  // Some(3)
```

`flatMap` is the defining operation of monads and is exactly what enables multi-generator for-comprehensions: each generator after the first is sequenced via `flatMap`.

### Q48. [Practical] How do you avoid the "callback hell" / nested for-comprehension trap with mixed `Future[Option[T]]`?

A `Future[Option[T]]` can't be flattened by a plain for-comprehension because the two effects (async + optionality) don't compose directly — a for-comprehension over `Future` gives you the `Option` as the value, and you must handle `None` manually, which nests badly.

```scala
// Painful: Future of Option of Future of Option...
def lookup(id: Int): Future[Option[User]] = ...
def orders(u: User): Future[Option[List[Order]]] = ...

// Naive nesting:
lookup(1).flatMap {
  case Some(u) => orders(u)
  case None    => Future.successful(None)
}

// Clean: a monad transformer (Cats' OptionT) flattens the stack
import cats.data.OptionT
import cats.implicits.*
val result: OptionT[Future, List[Order]] =
  for
    u  <- OptionT(lookup(1))
    os <- OptionT(orders(u))
  yield os
result.value   // Future[Option[List[Order]]]
```

`OptionT[Future, A]` is a **monad transformer** that stacks `Option` inside `Future` and gives you a single, flat for-comprehension that short-circuits on either `None` or a failed `Future`. This is the standard production answer for composing nested effects.

### Q49. [Theory] What are the trade-offs of implicit resolution, and how do you keep it maintainable?

Implicit/`given` resolution is powerful (type classes, contextual config, derivation) but can hurt readability and compile times if abused: errors become cryptic ("no given instance found"), behavior depends on import scope, and implicit *conversions* can silently coerce types in surprising ways. Maintainability practices:

- Prefer `given`/`using` (Scala 3) over Scala 2 `implicit` for clarity of intent.
- Avoid implicit *conversions* almost entirely; they hide control flow.
- Keep `given` instances in the companion object of the type or the type class so they're found by the standard implicit-scope search (no fragile imports).
- Use `import` selectively and document where instances come from.
- Lean on `-Xprint:typer` / IDE "show implicits" to debug resolution.

The guiding principle: implicits should encode *unambiguous, canonical* facts (there is one obvious `JsonEncoder[Int]`), not arbitrary behavior toggles.

### Q50. [Theory] How does Scala interoperate with Java, and what are the friction points?

Scala compiles to JVM bytecode and can call any Java library directly; Java can call Scala (with some name-mangling awareness). Collections, however, differ, so you convert at the boundary, and a few semantic gaps need care.

```scala
import scala.jdk.CollectionConverters.*
val javaList: java.util.List[Int] = List(1, 2, 3).asJava
val scalaSeq = javaList.asScala.toSeq

// Java null -> Scala Option at the boundary:
val opt = Option(javaApi.maybeNull())
```

Friction points: (1) **`null`** — Java APIs return null, so wrap with `Option(...)` defensively; (2) **collections** — use `scala.jdk.CollectionConverters` (`asJava`/`asScala`); (3) **`static` members** — Java statics appear as members of a synthetic object; (4) **checked exceptions** — Scala has none, so Java checked exceptions just propagate; (5) **function interfaces** — Scala `FunctionN` and Java functional interfaces (SAM) interconvert in recent versions but historically needed adapters; (6) **default arguments / by-name params / traits with state** don't map cleanly to Java callers.

## 🔴 Expert (15+ yrs)

### Q51. [Theory] Compare the `Future`-based, Akka-actor, and effect-system (Cats Effect / ZIO) concurrency models. When would you pick each?

- **`Future`** — simplest; a one-shot async value submitted eagerly to an `ExecutionContext`. Good for straightforward async I/O composition, but it's *eager* (side effects fire immediately, hurting referential transparency), hard to cancel, and error handling is ad hoc.
- **Akka/Pekko actors** — stateful, message-driven concurrency with supervision and location transparency (distributable). Ideal for systems modeled as communicating stateful entities, streaming with backpressure (Akka Streams), and resilient/distributed apps. Higher conceptual overhead; untyped pitfalls in classic Akka (mitigated by Akka Typed).
- **Effect systems (Cats Effect `IO`, ZIO)** — a description of a program as a *value* (`IO[A]`/`ZIO[R,E,A]`) that is referentially transparent, lazily executed by a runtime with fibers (lightweight green threads), structured concurrency, principled cancellation, resource safety (`Resource`/`Scope`), and typed errors. Best for purely-functional codebases that want testability, composable concurrency, and fine-grained control.

```
Future:        easy, eager, weak cancellation        -> simple async glue
Actors:        stateful, distributed, supervised      -> resilient/streaming systems
IO/ZIO:        pure, lazy, fibers, structured concurrency -> FP-first services
```

Pick `Future` for small async needs interoperating with existing APIs; actors when the domain is naturally stateful/distributed; an effect system when you want referential transparency, robust cancellation, and a unified resource/error model across the whole app.

### Q52. [Theory] Explain Scala 3 metaprogramming: inline, macros, and `Mirror`-based generic derivation.

Scala 3 replaced Scala 2's experimental macros with a principled stack:

- **`inline`** — guarantees compile-time inlining of methods/values and enables compile-time conditionals (`inline if`, `inline match`), constant folding, and `summonInline`. Many things that needed macros before are now plain `inline`.
- **Quotes & splices macros** — typed metaprogramming with `'{ ... }` (quote, build code) and `${ ... }` (splice, run code at compile time), plus the reflection API (`scala.quoted`) for inspecting types/trees. Far safer and more portable than Scala 2 macros.
- **`Mirror`-based derivation** — the compiler synthesizes a `Mirror.ProductOf` / `Mirror.SumOf` for case classes and sealed hierarchies, exposing field types/labels at the type level so you can *derive* type class instances generically without macros.

```scala
import scala.deriving.Mirror

inline def deriveShow[T](using m: Mirror.Of[T]): Show[T] = ...
// usage:
case class P(x: Int, y: Int) derives Show   // 'derives' triggers derivation
```

The `derives` clause is the headline feature: `case class P(...) derives JsonEncoder` auto-generates the instance via `Mirror`, giving boilerplate-free type class derivation that's compiler-checked and IDE-friendly.

### Q53. [Theory] What are match types, union types, and intersection types in Scala 3?

Scala 3 added several type-level constructs:

- **Union types** `A | B` — a value that is either `A` or `B`, without a wrapping ADT. Useful for "one of" return types and modeling sums structurally.
- **Intersection types** `A & B` — a value that is *both* `A` and `B` (replaces Scala 2's `with` in type position; commutative, unlike `with`).
- **Match types** — type-level pattern matching that computes a type from a type, enabling type-level functions.

```scala
def parse(s: String): Int | String =
  s.toIntOption match
    case Some(n) => n
    case None    => s"unparseable: $s"

type Elem[X] = X match            // match type
  case String      => Char
  case Array[t]    => t
  case Iterable[t] => t
// Elem[String] = Char, Elem[Array[Int]] = Int
```

These give precise, structural typing that previously required encodings or macros. Match types in particular let library authors compute result types (e.g., the element type of a container) at compile time.

### Q54. [Coding] Implement a type-safe heterogeneous "config" using opaque types and union types.

```scala
opaque type Port = Int
object Port:
  def apply(n: Int): Either[String, Port] =
    if n >= 0 && n <= 65535 then Right(n) else Left(s"bad port $n")
  extension (p: Port) def value: Int = p   // zero-cost: Port IS an Int at runtime

opaque type Host = String
object Host:
  def apply(s: String): Host = s
  extension (h: Host) def value: String = h

case class Endpoint(host: Host, port: Port)

val ep = for
  p <- Port(8080)
yield Endpoint(Host("api.local"), p)
// ep: Right(Endpoint(...)) — and you can't accidentally pass a raw Int as a Port
```

`opaque type` gives a **zero-cost newtype**: `Port` is a distinct type at compile time (so you can't mix up a port and any other `Int`) but erases to plain `Int` at runtime — no wrapper allocation, unlike a `case class` wrapper. Combined with smart constructors returning `Either`, you encode validation and distinctness with no runtime overhead, which is exactly the kind of "make illegal states unrepresentable" design senior interviewers probe for.

### Q55. [Theory] What is the expression problem, and how do Scala's features address it?

The expression problem asks: can you add both new *data variants* and new *operations* to a type, without modifying existing code and keeping type safety? Object-oriented inheritance makes adding variants easy (new subclass) but adding operations hard (touch every class); functional ADTs + pattern matching make adding operations easy (new function) but adding variants hard (touch every match).

Scala can attack both sides:

- **Type classes** (`given`/`using`) let you add new *operations* over existing types without modifying them — define a new type class and instances externally.
- **Traits + mixin / `extension` methods** let you extend behavior without editing originals.
- Libraries like **`tagless final`** encode operations as type class methods over an abstract effect `F[_]`, so both new interpreters (operations) and new terms can be added more freely.

```scala
// Add a new operation (prettyPrint) to a sealed Expr WITHOUT editing Expr:
trait Pretty[A]:  extension (a: A) def pretty: String
given Pretty[Expr] with
  extension (e: Expr) def pretty: String = e match { ... }
```

No single feature fully "solves" it, but the type-class approach is the canonical Scala answer for extensibility along the operations axis while keeping data sealed for exhaustiveness.

### Q56. [Behavioral] Describe a situation where Scala's flexibility caused problems on your team, and how you addressed it.

Interviewers use this to gauge maturity about Scala's double-edged power. A strong answer names a concrete failure mode and a systemic fix. Example: "Our codebase had drifted into 'astronaut Scala' — heavy implicit conversions, custom operators (`>>=`, `|@|`, `~>`), and deep monad-transformer stacks. New hires took weeks to become productive and reviews stalled. I led three changes: (1) adopted a written style guide constraining the dialect (ban implicit conversions, restrict symbolic operators to a vetted list, cap transformer nesting); (2) introduced `scalafmt` and `scalafix`/WartRemover lint rules to enforce it in CI; (3) ran internal sessions to align on *one* effect library rather than a mix. The result was a measurable drop in review turnaround and onboarding time, without giving up the safety benefits."

The signals: you recognize that Scala's expressiveness is a *liability* without team discipline, you favor automated enforcement over tribal knowledge, and you optimize for the team's long-term velocity, not personal cleverness.

### Q57. [Theory] How does Scala's type erasure interact with pattern matching, and how do you work around it?

Like Java, Scala erases generic type arguments at runtime, so a match on `case xs: List[Int]` can only check that the value is a `List`, not that its elements are `Int` — the compiler emits an "unchecked" warning and the match succeeds for *any* `List`.

```scala
def describe(x: Any): String = x match
  case _: List[Int]    => "list of ints"   // WARNING: Int is erased; matches any List
  case _: List[String] => "list of strings" // unreachable!
  case _               => "other"
```

Work-arounds:

- **`ClassTag`/`TypeTag`** — capture type info as an implicit witness so it survives at runtime: `def f[A](x: Any)(using ct: ClassTag[A])` lets `case a: A` work via the tag.
- **Match the element**, not the generic — inspect runtime contents (`xs.headOption`) when reasonable.
- **Avoid the need** — design APIs so the concrete type is known statically rather than matching on erased generics.

```scala
import scala.reflect.ClassTag
def firstOf[A](xs: List[Any])(using ct: ClassTag[A]): Option[A] =
  xs.collectFirst { case a: A => a }   // works because ClassTag[A] reifies A
```

### Q58. [Theory] What are the JVM/performance considerations specific to Scala (boxing, specialization, allocation)?

Scala's high-level abstractions can introduce hidden costs on the JVM:

- **Boxing** — generic code over `Int`/`Double` boxes them into `java.lang.Integer`, causing allocation and indirection. `@specialized` (Scala 2) generates primitive-specialized variants; Scala 3 leans on `inline`/opaque types and value-class-like patterns to avoid it. Collections of primitives box unless you use `Array[Int]`.
- **Closure/lambda allocation** — each function literal is an object; in hot loops, capturing closures allocate. The compiler can sometimes elide them, but tight numeric loops may need plain `while`.
- **For-comprehension overhead** — desugars to `map`/`flatMap` calls allocating intermediate collections; use `view`/`Iterator` or `foldLeft` to fuse.
- **Implicit/derivation** — happens at compile time (no runtime cost) but can bloat compile times and generated code.
- **`Option` allocation** — `Some(x)` allocates; in ultra-hot paths some libraries use sentinel values or `null` internally.

```scala
// Allocation-heavy:
(1 to n).map(_ * 2).filter(_ > 10).sum
// Fused, fewer allocations:
(1 to n).iterator.map(_ * 2).filter(_ > 10).sum
// Lowest level for numeric hot loops:
var i = 0; var s = 0L; while i < n do { val v = i*2; if v > 10 then s += v; i += 1 }
```

The pragmatic stance: write idiomatic functional code by default, and drop to `Array`/`while`/`@specialized` only in profiled hot spots.

### Q59. [Theory] Explain `tagless final` and why teams adopt it over concrete effect types.

`tagless final` (a.k.a. "finally tagless") encodes a program's operations as methods of a type class parameterized by an abstract effect `F[_]`, deferring the choice of concrete effect (IO, ZIO, Future, a test `Id`) to the edges of the program.

```scala
trait UserStore[F[_]]:
  def get(id: Int): F[Option[User]]
  def save(u: User): F[Unit]

// Business logic is generic in F, requiring only the capabilities it needs:
def register[F[_]: Monad](store: UserStore[F])(u: User): F[Boolean] =
  for
    existing <- store.get(u.id)
    result   <- if existing.isEmpty then store.save(u).as(true)
                else Monad[F].pure(false)
  yield result
```

Benefits: **testability** (instantiate `F = Id` or a state monad in tests, no real IO), **capability tracking** (the constraints `[F[_]: Monad: Sync]` document exactly what effects the code needs), **decoupling** from a specific runtime, and **principled composition**. The trade-off is added abstraction and a steeper learning curve; teams adopt it for large, long-lived services where testability and explicit effect boundaries pay off, and avoid it for small apps where concrete `IO`/`ZIO` is simpler.

### Q60. [Coding] Implement a stack-safe recursive evaluator for an arithmetic AST.

```scala
enum Expr:
  case Num(n: Int)
  case Add(l: Expr, r: Expr)
  case Mul(l: Expr, r: Expr)

import Expr.*

// Naive recursion can blow the stack on deep/left-leaning trees.
// Trampolining keeps it stack-safe by reifying the continuation.
import scala.util.control.TailCalls.*

def eval(e: Expr): TailRec[Int] = e match
  case Num(n)    => done(n)
  case Add(l, r) => for a <- tailcall(eval(l)); b <- tailcall(eval(r)) yield a + b
  case Mul(l, r) => for a <- tailcall(eval(l)); b <- tailcall(eval(r)) yield a * b

val tree = Add(Num(1), Mul(Num(2), Add(Num(3), Num(4))))
eval(tree).result   // 1 + 2*(3+4) = 15
```

`scala.util.control.TailCalls` provides a **trampoline**: instead of recursing directly (growing the JVM stack), `tailcall` returns a thunk and the `result` driver loops through them on the heap. For non-tail recursion that can't be rewritten with `@tailrec` (mutual recursion, tree walks), trampolining is the standard way to stay stack-safe — the same principle effect systems use under the hood (`IO`/`ZIO` interpreters trampoline their `flatMap` chains).

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q61. [Theory] How does the Scala compiler represent an `Int` at the bytecode level if "everything is an object"?

At the *source* level `Int` is a final class extending `AnyVal`, so it has methods (`+`, `toString`, `max`) and participates in the type hierarchy. At the *bytecode* level the compiler erases an `Int` value to the JVM primitive `I` (a real `int`) wherever it can — local variables, fields of concrete type `Int`, arithmetic — so there is zero boxing cost in ordinary numeric code. Boxing to `java.lang.Integer` happens only when an `Int` must satisfy a reference type: assignment to `Any`/`AnyRef`, storage in a generic collection (`List[Int]` stores `Integer`s because the element slot is erased to `Object`), or use as a type-parameter argument.

```scala
val x: Int = 41          // JVM 'int', no allocation
val y: Any = x           // boxes to java.lang.Integer
val xs: List[Int] = List(x)  // elements boxed (erased element type is Object)
val arr: Array[Int] = Array(x) // NOT boxed — Array[Int] erases to int[]
```

So "everything is an object" is a uniform *programming model*, not a runtime guarantee of heap allocation. The compiler's job is to give you object semantics on paper while emitting primitive operations underneath, and `Array[Int]` is the escape hatch that keeps primitives unboxed in bulk.

#### Q62. [Theory] What is the difference between a `def` body being recomputed and a `lazy val` being memoized at the bytecode level?

A `def` compiles to a JVM method: every call re-runs the body. A plain `val` compiles to a final field assigned once in the constructor. A `lazy val` is more involved: the compiler generates a hidden `boolean` (or, since 2.12+/Scala 3, a bitmap-packed flag) plus a synchronized initializer. The first read takes a lock, checks the flag, runs the initializer, stores the result in a backing field, sets the flag, and unlocks; subsequent reads see the flag set and return the cached field without locking (a double-checked-locking pattern the compiler writes for you).

```scala
class C:
  lazy val z = expensive()   // ~ generates: bitmap flag + synchronized init + backing field
```

Practical consequences: the first access pays a synchronization cost and the object carries extra fields; thousands of `lazy val`s bloat instances. Scala 3 improved the encoding (offset-free, less contention) but the model is the same. This is why `lazy val` is great for one-time setup and bad inside hot loops or value-heavy data structures.

#### Q63. [Theory] Why does `==` on a case class not need you to override `equals`, and what exactly does the generated `equals` do?

For a `case class`, the compiler generates `equals` that (1) checks reference identity as a fast path, (2) checks the other object is an instance of the same case class via a generated `canEqual`, and (3) compares each field with `==`. It also generates a matching `hashCode` combining the fields (via `scala.util.hashing.MurmurHash3`) so the `equals`/`hashCode` contract holds, making case classes safe as `Map`/`Set` keys.

```scala
case class P(x: Int, y: Int)
P(1, 2) == P(1, 2)            // true: field-wise
Set(P(1,2)).contains(P(1,2)) // true: consistent hashCode
```

`canEqual` exists so that subclass/superclass equality can be made symmetric — both sides agree they are willing to be compared. The takeaway: never hand-write `equals` for a case class, and be cautious adding a non-case subclass to a case class (it breaks the symmetry the generated `canEqual` assumes, which is partly why inheriting from a case class is discouraged).

#### Q64. [Coding] Show how `copy` is generated and use it to update a nested immutable structure.

```scala
case class Address(city: String, zip: String)
case class User(name: String, address: Address)

// The compiler generates copy with one defaulted param per field:
//   def copy(name: String = this.name, address: Address = this.address): User

val u = User("Ada", Address("London", "EC1"))

// Update a nested field by copying outward:
val moved = u.copy(address = u.address.copy(city = "Paris"))
// moved == User("Ada", Address("Paris", "EC1")); u is unchanged
```

`copy` takes one parameter per constructor field, each defaulting to the current value, so naming just the field you want to change leaves the rest intact. Deep updates are verbose because each level needs its own `copy` — this pain is exactly what optics libraries (Monocle lenses) solve: `User.address.andThen(Address.city).replace("Paris")(u)`. For interviews, knowing the manual nested-`copy` pattern and *why* lenses exist is the complete answer.

#### Q65. [Theory] What is structural sharing, and why is prepending to a `List` O(1) while appending is O(n)?

A Scala `List` is a singly linked chain of `::` (cons) cells, each holding a `head` and a reference to the `tail`. Prepending (`x :: xs`) allocates one new cell whose tail points at the *existing* `xs` — the old list is reused wholesale and untouched, so it is O(1) and both versions coexist (persistence). Appending must reach the end, and because cells are immutable you cannot mutate the last cell's tail; you must rebuild every cell from the front, which is O(n).

```scala
val xs = List(2, 3)
val ys = 1 :: xs    // O(1): new cell -> reuses xs; xs still List(2,3)
val zs = xs :+ 4    // O(n): rebuilds the whole spine
```

This is the deeper reason idiomatic Scala builds lists by prepending in reverse and (if needed) reversing once at the end, and why `Vector` is preferred when you need efficient append/random access — its trie structure shares subtrees instead of a linear spine.

#### Q66. [Theory] What does `sealed` actually do at compile time, and what doesn't it guarantee at runtime?

`sealed` restricts a trait's or class's *direct* subtypes to the same source file. The compiler uses this to know the full set of variants, enabling exhaustiveness checking on `match` (it warns if you miss a case) and some optimizations. It does **not** make the type final — subtypes can themselves be non-sealed and extended elsewhere — and it provides no runtime enforcement; it is purely a compile-time, same-file constraint.

```scala
sealed trait Json
case object JNull extends Json
case class JNum(n: Double) extends Json
final case class JStr(s: String) extends Json  // 'final' stops further subclassing

def show(j: Json): String = j match
  case JNull   => "null"
  case JNum(n) => n.toString
  case JStr(s) => s        // omit a case -> compiler warns: match not exhaustive
```

For airtight ADTs you combine `sealed` (closes the set in this file) with `final` on the leaves (stops accidental subclassing) — the modern Scala 3 `enum` does both automatically.

#### Q85. [Theory] How does a Scala 3 `enum` compile, and how does it differ from a plain `sealed trait` hierarchy?

A Scala 3 `enum` is concise syntax that the compiler expands into a `sealed` abstract class (or trait) plus cases. Simple, parameterless cases (like `Color.Red`) become *singleton* values (one shared instance, similar to `case object`), while parameterized cases (`case Some(x)`) become `final case class`-like subtypes. The compiler also generates helpers: `values` (array of singleton cases), `valueOf(name)`, an `ordinal` for each case, and `fromOrdinal`. Generic enums and even GADT-style cases are supported.

```scala
enum Color:
  case Red, Green, Blue          // three shared singletons

enum Tree[+A]:
  case Leaf(value: A)            // parameterized -> case-class-like subtype
  case Branch(l: Tree[A], r: Tree[A])

Color.Red.ordinal      // 0
Color.values.length    // 3
Color.valueOf("Blue")  // Color.Blue
```

Versus a hand-written `sealed trait` + `case object`/`case class`, `enum` gives you the `values`/`ordinal`/`valueOf` machinery for free and reads more clearly, while compiling to essentially the same sealed hierarchy underneath — so exhaustiveness checking and pattern matching work identically. Reach for `enum` for finite ADTs; the only reason to hand-roll a sealed hierarchy is when subtypes need to live in different files or need bespoke shapes the `enum` syntax can't express.

#### Q86. [Coding] What does string interpolation compile to, and how do `s`, `f`, and `raw` differ?

```scala
val name = "Ada"; val n = 3

s"Hi $name, $n items"          // "Hi Ada, 3 items"  — calls toString, escapes processed
f"$n%03d and ${math.Pi}%.2f"   // "003 and 3.14"      — printf-style, type-checked formats
raw"line1\nline2"              // "line1\nline2"      — backslashes NOT interpreted

// All three desugar to a call on StringContext:
// s"Hi $name" -> StringContext("Hi ", "").s(name)
StringContext("Hi ", " items").s(name)   // "Hi Ada items"
```

Each interpolator desugars to `StringContext(parts...).<id>(args...)`, where the `parts` are the literal fragments and `args` are the interpolated expressions. `s` interpolates via `toString` and processes escapes; `f` is checked at compile time against the `%` format specifiers (so `f"$n%d"` won't compile if `n` isn't a number); `raw` leaves escape sequences literal. Because `StringContext` is just a normal class, you can define **your own** interpolator as an extension method on it (e.g., a `json"..."` or `sql"..."` that validates or parameterizes at compile time) — the same mechanism behind the `s"GET /users/$id"` *pattern* extractors shown earlier.

### 🟡 — extended

#### Q67. [Theory] How does trait linearization compute the exact order, and how do you derive it by hand?

Linearization produces a single ordered list (the "C3-like" linearization Scala uses) of a class and all its ancestors, deduplicated keeping the *last* occurrence. The algorithm: write the class, then append the linearizations of its parents from **right to left**, then remove all but the rightmost copy of each duplicate. `super` calls resolve to the *next* element after the current type in this list.

```scala
trait A:           def f = "A"
trait B extends A: override def f = "B" + super.f
trait C extends A: override def f = "C" + super.f
class D extends B with C

// Build: D, then lin(C)=C,A reversed-in, then lin(B)=B,A, dedup keeping last:
// raw: D, C, A, B, A  -> drop earlier A -> D, C, B, A
new D().f   // "C" + "B" + "A" = "CBA"
```

The practical rule: parents listed later (rightmost in `with`) appear *earlier* in the linearization, so their overrides win and run first. Getting this right is essential for stackable traits (e.g., logging/metrics decorators) where each layer calls `super`.

#### Q68. [Theory] What is the difference between `withFilter` and `filter`, and why do for-comprehensions use `withFilter`?

`filter` eagerly builds a new collection containing the matching elements. `withFilter` returns a lightweight, *non-strict* `WithFilter` view that does not materialize anything; it just remembers the predicate and applies it lazily when a subsequent `map`/`flatMap`/`foreach` runs. For-comprehensions desugar `if` guards to `withFilter` precisely to avoid allocating an intermediate filtered collection between the guard and the following operation.

```scala
for
  x <- List(1, 2, 3, 4)
  if x % 2 == 0          // desugars to .withFilter(_ % 2 == 0)
yield x * 10
// -> List(1,2,3,4).withFilter(_ % 2 == 0).map(_ * 10) -> List(20, 40)
```

If a type defines `filter` but not `withFilter`, the compiler falls back to `filter` (with a deprecation-style note historically). The deeper point: `withFilter` is what keeps `for` guards from being wasteful, fusing the guard into the following traversal.

#### Q69. [Coding] Demonstrate that `Future`s are eager by construction, and contrast with a lazy `IO`-style value.

```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

// Future runs NOW, at construction — the println fires immediately:
val f = Future { println("future side effect"); 42 }   // side effect already happened

// Lazy effect (Cats Effect IO): describes the work; nothing runs until unsafeRun
import cats.effect.IO
val io = IO { println("io side effect"); 42 }           // NOTHING printed yet
// io.unsafeRunSync()  // only here does the side effect occur
```

A `Future` submits its body to the `ExecutionContext` the instant it is created, so building one already performs side effects — this breaks referential transparency (you cannot freely substitute the `val` for its definition). An `IO[A]` is a *value describing* a computation; constructing it is pure, and it executes only when explicitly run. This distinction is the entire motivation for effect systems and explains the parallel-`Future` gotcha from earlier questions.

#### Q70. [Theory] What is the difference between by-name (`=> A`) and by-value (`A`) parameters, and how are by-name params compiled?

A by-value parameter `x: A` is evaluated once at the call site *before* the method runs. A by-name parameter `x: => A` is *not* evaluated at the call site; instead the compiler wraps the argument expression in a zero-argument function (`Function0`) and re-evaluates it *each time* the parameter name is referenced inside the method body. This enables control-flow abstractions, short-circuiting, and lazy/conditional evaluation.

```scala
def unless(cond: Boolean)(body: => Unit): Unit =
  if !cond then body              // body only evaluated if cond is false

unless(false) { println("runs") }   // prints
unless(true)  { println("skipped") }// body never evaluated

def twice(x: => Int): Int = x + x   // evaluates the arg expression TWICE
var c = 0
twice { c += 1; c }                 // returns 1 + 2 = 3; c ends at 2
```

Cost: each by-name reference is a thunk invocation (a `Function0.apply`), so repeated access re-runs the expression — combine with a local `val` if you need the value once. By-name params are how `assert`, logging guards, and custom control structures avoid evaluating expensive arguments needlessly.

#### Q71. [Theory] How does implicit/`given` resolution scope work, and what is the precedence order the compiler searches?

When the compiler needs a `given`/implicit of type `T`, it searches two broad scopes in priority order. First, **lexical scope**: givens defined or imported directly into the current scope (locals, enclosing definitions, explicit `import`s). Second, **implicit (contextual) scope**: the companion objects of `T` and of all types "associated" with `T` — its type arguments, its supertypes, and the companions of those. Closer/lexical definitions outrank the implicit scope; ambiguity at the same priority is a compile error.

```scala
trait Show[A]: def show(a: A): String
object Show:
  given Show[Int] = a => s"Int($a)"   // found via implicit scope (companion of Show)

def render[A](a: A)(using s: Show[A]) = s.show(a)
render(7)   // resolves Show[Int] from Show's companion, no import needed
```

The maintainability rule that falls out of this: put the *canonical* given for a type in the companion object of either the type class or the type, so users never need fragile imports. Reserve lexical-scope givens for local overrides. Knowing this order is what lets you debug "ambiguous given" and "no given instance" errors quickly.

#### Q72. [Coding] Implement a stackable-trait "decorator" using linearization and `super`.

```scala
trait Store:
  def put(k: String, v: String): Unit

class InMemory extends Store:
  private val m = scala.collection.mutable.Map.empty[String, String]
  def put(k: String, v: String): Unit = m(k) = v

trait Logging extends Store:
  abstract override def put(k: String, v: String): Unit =
    println(s"PUT $k=$v"); super.put(k, v)   // super = next in linearization

trait Timing extends Store:
  abstract override def put(k: String, v: String): Unit =
    val t = System.nanoTime(); super.put(k, v)
    println(s"took ${System.nanoTime() - t}ns")

val s = new InMemory with Logging with Timing
s.put("a", "1")   // Timing wraps Logging wraps InMemory (right-most = outermost)
```

`abstract override` is the signal that the method calls `super` but `super`'s concrete implementation is supplied later by linearization, so the trait can only be mixed onto something that provides `put`. Because `Timing` is rightmost it sits earliest in the linearization and therefore wraps the others — its `super.put` flows to `Logging`, whose `super.put` flows to `InMemory`. This is the canonical stackable-modification pattern (logging, metrics, retries) that linearization makes possible.

#### Q87. [Theory] What is the difference between `Seq`, `IndexedSeq`, and `LinearSeq`, and why does it affect algorithmic choice?

`Seq` is the general ordered-sequence abstraction with two performance-characterized sub-families. `LinearSeq` (e.g., `List`) is optimized for `head`/`tail`/prepend access — O(1) at the front but O(n) to reach index `i`. `IndexedSeq` (e.g., `Vector`, `ArraySeq`) guarantees efficient `apply(i)` and `length` — effectively O(1)/O(log n) random access. Choosing the wrong family turns a linear algorithm quadratic: indexing a `List` by position in a loop is O(n²).

```scala
val lin: List[Int]   = List(1, 2, 3)   // LinearSeq: fast head/tail
val idx: Vector[Int] = Vector(1, 2, 3) // IndexedSeq: fast apply(i)

// O(n^2) trap — random access on a LinearSeq:
def slowSum(xs: List[Int]) = (0 until xs.length).map(xs(_)).sum  // each xs(i) is O(i)!
// O(n) — iterate the linear structure as intended, or use a Vector:
def fastSum(xs: List[Int]) = xs.sum
```

The deeper lesson: a method's *signature* (`Seq[A]`) hides the cost model, so when performance matters you should program against the concrete family whose access pattern matches your algorithm — `Vector`/`IndexedSeq` for index-driven or random access, `List`/`LinearSeq` for head-recursive or build-by-prepend workloads. The collections hierarchy encodes these guarantees as types precisely so you can reason about complexity.

#### Q88. [Coding] How do `foldLeft` and `foldRight` differ in associativity, stack behavior, and laziness?

```scala
val xs = List(1, 2, 3, 4)

xs.foldLeft(0)(_ - _)   // ((((0-1)-2)-3)-4) = -10   left-associated, tail-recursive (loop)
xs.foldRight(0)(_ - _)  // (1-(2-(3-(4-0)))) =  -2   right-associated

// On Scala 2.13+/Scala 3, immutable.List overrides foldRight to be stack-safe
// (it reverses internally, then iterates) — so this does NOT overflow:
// List.range(1, 1000000).foldRight(0)(_ + _)   // OK on List, no StackOverflowError
// A naive hand-written right fold (or a strict structure lacking that override)
// is what actually risks StackOverflowError.

// foldRight is the natural builder for lazy/short-circuiting structures:
def takeWhilePos(xs: List[Int]): List[Int] =
  xs.foldRight(List.empty[Int])((x, acc) => if x > 0 then x :: acc else Nil)
```

`foldLeft` processes front-to-back, threads an accumulator, and the compiler turns it into a tight loop — stack-safe and the default choice. `foldRight` processes conceptually back-to-front (right-associated). For `immutable.List` it is stack-safe: the standard library overrides `List.foldRight` to reverse the list and then iterate (effectively `reverse.foldLeft`), so even a million-element `List` won't overflow. The stack-overflow risk people associate with right folds applies to a naive recursive right fold (or strict structures without that override), not to `List.foldRight` itself — though the right-associated *semantics* still differ from `foldLeft`. `foldRight` shines when the combining function is lazy in its second argument (as with `LazyList`), enabling short-circuiting and even infinite-structure folds. Rule: reach for `foldLeft` by default; use `foldRight` when right-associativity or laziness is semantically required.

### 🟠 — extended

#### Q73. [Theory] What does type erasure remove, what survives, and how do `ClassTag` and `TypeTag` differ?

The JVM erases all generic type arguments at runtime: `List[Int]` and `List[String]` are both just `List`, and a method `def f[A](a: A)` knows nothing about `A` at runtime. What survives is the *raw* class of a value (you can still call `getClass`) and any type info you explicitly capture. `ClassTag[A]` reifies just the erased top-level class of `A` (enough for `case a: A` matches and `Array[A]` creation), while the richer `TypeTag`/`Type` (from `scala-reflect`, Scala 2) or `scala.quoted.Type` (Scala 3) reifies the *full* type including type arguments.

```scala
import scala.reflect.ClassTag
def make[A: ClassTag](n: Int)(a: A): Array[A] = Array.fill(n)(a)
make(3)("x")   // works: ClassTag[String] lets the runtime allocate String[]

// ClassTag can't distinguish List[Int] from List[String] — only TypeTag can.
```

Rule of thumb: use `ClassTag` for array creation and simple runtime class checks (cheap, no extra dependency); reach for `TypeTag`/full reflection only when you genuinely need nested type arguments, accepting the heavier machinery. Designing APIs to avoid needing either is usually the better move.

#### Q74. [Theory] How are value classes (`extends AnyVal`) compiled, and when does Scala still allocate them?

A value class wraps a single field and `extends AnyVal`; the compiler tries to represent it as the *underlying* type at runtime, avoiding allocation — so a `class Meters(val v: Double) extends AnyVal` is mostly just a `double` with extra type safety and methods. However, the wrapper *is* allocated (boxed) in several cases: when the value class is used as a generic type argument, stored in an array, treated as `Any`/`AnyRef`/an implemented interface, or pattern-matched against. Scala 3's `opaque type` covers many of the same use cases with fewer allocation surprises.

```scala
class Meters(val v: Double) extends AnyVal:
  def +(o: Meters) = Meters(v + o.v)

val a = Meters(3); val b = Meters(4)
a + b                        // no allocation: operates on raw doubles
val xs = List(Meters(1))     // ALLOCATES: generic element slot boxes the value class
```

So value classes are "free" only on the happy path. When you need a guaranteed-zero-cost newtype even inside generics, prefer Scala 3 `opaque type`, which is erased more aggressively and never auto-boxes the way a value class does.

#### Q75. [Coding] Use a context bound and `summon` to retrieve a type class instance explicitly.

```scala
trait Ordering2[A]: def lt(x: A, y: A): Boolean
given Ordering2[Int] with
  def lt(x: Int, y: Int) = x < y

// Context bound [A: Ordering2] is sugar for an implicit (using) parameter:
def smallest[A: Ordering2](xs: List[A]): A =
  val ord = summon[Ordering2[A]]            // materialize the instance
  xs.reduce((a, b) => if ord.lt(a, b) then a else b)

smallest(List(5, 2, 9, 1))   // 1
```

`[A: Ordering2]` desugars to an extra `(using ev: Ordering2[A])` parameter; `summon[Ordering2[A]]` (Scala 2: `implicitly`) is how you grab that evidence by hand when you need to name it. This pattern — context bound to *require* an instance, `summon` to *use* it — is the everyday mechanics of type-class programming and underlies methods like `List.max`, which require an `Ordering`.

#### Q76. [Theory] What is `Mirror`-based derivation doing under the hood when you write `derives`?

When you write `case class P(x: Int, y: Int) derives JsonEncoder`, the compiler synthesizes a `Mirror.ProductOf[P]` — a compile-time value exposing `P`'s field types as a tuple type (`MirroredElemTypes = (Int, Int)`) and field names as string literal types (`MirroredElemLabels`). Your type class's `derived` method receives this `Mirror` and uses `inline`/`summonInline` to recursively fetch an instance for each field type, then assembles the whole-product instance. For `sealed`/`enum` types the compiler instead provides a `Mirror.SumOf` listing the subtypes, letting you derive a sum encoder that dispatches by the runtime `ordinal`.

```scala
import scala.deriving.Mirror
import scala.compiletime.{summonAll, constValueTuple}

trait JsonEncoder[A]: def encode(a: A): String
object JsonEncoder:
  inline def derived[A](using m: Mirror.ProductOf[A]): JsonEncoder[A] =
    val encoders = summonAll[Tuple.Map[m.MirroredElemTypes, JsonEncoder]]
    val labels   = constValueTuple[m.MirroredElemLabels]
    (a: A) =>
      val vals = a.asInstanceOf[Product].productIterator.toList
      labels.toList.zip(vals.zip(encoders.toList))
        .map { case (k, (v, e)) =>
          s"\"$k\":${e.asInstanceOf[JsonEncoder[Any]].encode(v)}" }
        .mkString("{", ",", "}")
```

The headline: derivation is *compile-time*, type-safe, and macro-free for the common cases — `Mirror` gives the structure, `inline` summons per-field instances, and the result is ordinary code with no runtime reflection. This is why Scala 3 derivation is faster and more robust than Scala 2's macro/Shapeless approach.

#### Q77. [Theory] How does Scala encode a context function type `A ?=> B`, and where is it useful?

A context function type `A ?=> B` is a function whose parameter is supplied *implicitly* at the application site rather than passed explicitly. The compiler represents it as a special `ContextFunction` and, crucially, *automatically* expands a given context into scope when you apply it — calling an `A ?=> B` summons the needed `given A` from context. This powers capability-passing DSLs where a context (transaction, builder, config) is threaded implicitly through a block.

```scala
type Configured[T] = Config ?=> T          // needs a given Config to produce T

def port: Configured[Int] = summon[Config].port   // pulls Config from context
def run[T](cfg: Config)(body: Configured[T]): T =
  given Config = cfg
  body                                      // body's implicit Config is satisfied here

case class Config(port: Int)
run(Config(8080)) { port + 1 }              // 8081 — Config supplied implicitly to the block
```

Context functions let library authors write builder/DSL blocks (`run(cfg) { ... }`) where every expression inside transparently sees the context without explicit `using` plumbing — used in tagless/builder libraries and the basis for "capabilities" experiments like Scala's `CanThrow`. They are the function-type counterpart to `using` parameters.

#### Q78. [Coding] Show how `unapply` and `unapplySeq` power custom extractors in pattern matching.

```scala
// Fixed-arity extractor: parse an even number
object Even:
  def unapply(n: Int): Option[Int] = if n % 2 == 0 then Some(n / 2) else None

42 match
  case Even(half) => s"even, half=$half"   // "even, half=21"
  case _          => "odd"

// Variable-arity extractor via unapplySeq: split a CSV row
object Csv:
  def unapplySeq(s: String): Option[Seq[String]] = Some(s.split(",").toIndexedSeq)

"a,b,c" match
  case Csv(first, rest*) => s"$first then ${rest.mkString("|")}"  // "a then b|c"
```

`unapply` returning `Option[T]` defines a single-binding extractor; returning `Option[(A, B, ...)]` binds multiple values; and `unapplySeq` returning `Option[Seq[T]]` enables variable-length patterns (`case Pattern(a, b, rest*)`). Case classes get `unapply` for free, but defining your own extractor objects lets you pattern-match on *derived* views of data (parsing, validation, projection) without changing the underlying type — the mechanism behind matchers like `s"..."` string-interpolator patterns and regex extractors.

### 🔴 — extended

#### Q79. [Theory] How do effect systems achieve stack safety for deeply nested `flatMap` chains?

A naive `flatMap` that calls its continuation directly would grow the JVM stack with each bind, overflowing on long chains or recursive programs. Cats Effect `IO` and ZIO instead represent the program as a *data structure* of sumtype nodes (`Pure`, `FlatMap`, `Delay`, `Async`, …). The runtime interprets this tree with an explicit loop and a heap-allocated stack of continuations — a **trampoline**. Each `flatMap` step pushes/pops continuations on the heap rather than recursing, so an arbitrarily deep chain runs in constant JVM stack space.

```scala
// Conceptual shape of the encoding:
enum IO[A]:
  case Pure(a: A)
  case Delay(thunk: () => A)
  case FlatMap[A, B](io: IO[A], f: A => IO[B]) extends IO[B]
// The run loop pattern-matches these in a while-loop, maintaining a continuation
// stack on the heap — no direct recursion, hence stack-safe.
```

This is the same trampolining principle as `scala.util.control.TailCalls`, generalized to a full effect runtime that also schedules async boundaries onto fibers. The payoff is that you can write recursive, monadic programs (`def loop(n): IO[Unit] = if n==0 then IO.unit else doWork *> loop(n-1)`) without ever worrying about `StackOverflowError`.

#### Q80. [Theory] What are fibers, and how do they differ from JVM threads and from `Future`?

A fiber is a lightweight, runtime-managed unit of concurrent execution — a "green thread" scheduled by the effect runtime (Cats Effect / ZIO) onto a small pool of real OS threads. Fibers are cheap (you can have millions), support *structured concurrency* (a parent fiber owns and can cancel its children), and offer principled, prompt **cancellation** at safe points. JVM threads are heavyweight OS resources (thousands at most) with cooperative-only interruption; a `Future` has no first-class cancellation at all and is just a handle to an eagerly-running computation.

```scala
import cats.effect.IO
import scala.concurrent.duration.*

val program = for
  fiber <- IO.println("working").foreverM.start   // spawn a fiber
  _     <- IO.sleep(100.millis)
  _     <- fiber.cancel                            // structured, prompt cancellation
yield ()
```

The deeper distinction: fibers make concurrency *referentially transparent and cancelable* — you describe `start`/`join`/`cancel` as pure values, and the runtime multiplexes them. This is why effect systems can offer timeouts, racing, and resource-safe interruption that `Future` fundamentally cannot. (Note: JDK 21+ virtual threads bring similar lightweight threading to the platform, but without the structured-concurrency/cancellation guarantees the effect runtimes layer on top.)

#### Q81. [Theory] Explain how Cats Effect's `Resource` guarantees acquisition/release safety even under cancellation.

`Resource[F, A]` pairs an `acquire` effect with a `release` effect and composes them so that release is *always* run exactly once — on success, on error, or on cancellation — and nested resources are released in reverse order. Internally it builds on `bracket`/`bracketCase`, which registers the release as a finalizer that the fiber runtime runs even if the surrounding fiber is canceled at an async boundary. Composing resources with `flatMap`/for-comprehension nests their brackets, giving LIFO teardown.

```scala
import cats.effect.{IO, Resource}

def file(name: String): Resource[IO, Handle] =
  Resource.make(IO(open(name)))(h => IO(h.close()))   // acquire / guaranteed release

val both = for
  in  <- file("a.txt")
  out <- file("b.txt")          // released before 'in' (reverse order)
yield (in, out)

both.use { (in, out) => process(in, out) }  // closes both even if process is canceled
```

The guarantee that distinguishes this from `try/finally` is **cancellation safety**: if another fiber cancels this one mid-`use`, the runtime still runs every registered release. That makes `Resource` the correct abstraction for connection pools, file handles, and any acquire/release pair in a concurrent, cancelable program — something a plain `Using` or `try/finally` cannot promise under structured concurrency.

#### Q82. [Theory] What is the "tagless final vs free monad" trade-off for encoding programs as data?

Both decouple *describing* a program from *running* it, but differently. **Tagless final** encodes operations as methods of a type class `Algebra[F[_]]`; programs are polymorphic in `F`, and you "interpret" by choosing a concrete `F` (IO, a test monad). It is allocation-light (direct method calls), composes with `Monad`/`Sync` constraints, and is the mainstream Scala style. The **free monad** reifies each operation as a *data constructor* of an ADT and builds a tree you fold with an interpreter (a natural transformation `F ~> G`); programs are first-class values you can inspect, optimize, or re-interpret multiple ways, at the cost of allocation and more ceremony.

```scala
// Tagless final: operations are abstract methods, F is chosen later
trait KVStore[F[_]]:
  def get(k: String): F[Option[String]]
  def put(k: String, v: String): F[Unit]

// Free: operations are data; you write an interpreter F ~> G to run them
enum KV[A]:
  case Get(k: String)            extends KV[Option[String]]
  case Put(k: String, v: String) extends KV[Unit]
// run via Free[KV, *] folded with a KV ~> IO interpreter
```

Choose tagless final for most production code: less boilerplate, good performance, easy capability tracking via constraints. Choose free monads when you genuinely need the program-as-inspectable-data property — e.g., to analyze, batch, or run the *same* description through several interpreters. In 2026 the ecosystem leans heavily tagless-final (or direct `IO`), with free monads reserved for those reflection/optimization use cases.

#### Q83. [Coding] Implement a minimal stack-safe trampoline monad from scratch.

```scala
enum Trampoline[+A]:
  case Done(value: A)
  case More(call: () => Trampoline[A])
  case FlatMap[A, B](sub: Trampoline[A], k: A => Trampoline[B]) extends Trampoline[B]

  final def flatMap[B](f: A => Trampoline[B]): Trampoline[B] = FlatMap(this, f)
  final def map[B](f: A => B): Trampoline[B] = flatMap(a => Done(f(a)))

  @annotation.tailrec
  final def run: A = this match
    case Done(a)    => a
    case More(t)    => t().run
    case FlatMap(sub, k) => sub match
      case Done(a)        => k(a).run
      case More(t)        => t().flatMap(k).run
      case FlatMap(s2, k2) => s2.flatMap(x => k2(x).flatMap(k)).run

import Trampoline.*

// Mutually recursive even/odd that would overflow with direct recursion:
def even(n: Int): Trampoline[Boolean] =
  if n == 0 then Done(true)  else More(() => odd(n - 1))
def odd(n: Int): Trampoline[Boolean] =
  if n == 0 then Done(false) else More(() => even(n - 1))

even(1000000).run   // true — runs in constant stack via the run loop
```

The key trick: `run` is `@tailrec`, and the `FlatMap`/`FlatMap` case re-associates nested binds to the right (`s2.flatMap(x => k2(x).flatMap(k))`) so the loop never has to recurse into a left-nested chain. This re-association is exactly what real effect runtimes do to keep `flatMap` stack-safe — building it by hand demystifies how `IO`/`ZIO` avoid `StackOverflowError`.

#### Q84. [Theory] How does Scala 3's `inline` enable zero-overhead abstractions, and what are its limits?

`inline` instructs the compiler to *expand* a method's body at each call site at compile time, eliminating the call and enabling further compile-time work: `inline if`/`inline match` choose branches statically, `constValue`/`constValueTuple` lift singleton types to values, `summonInline` resolves givens during expansion, and `scala.compiletime.error` raises custom compile errors. Used well, an `inline` abstraction compiles to the same code you'd write by hand — no closure allocation, no megamorphic dispatch.

```scala
import scala.compiletime.{constValue, error}

inline def repeat[T](inline n: Int)(inline body: => T): Unit =
  inline if n <= 0 then ()
  else { body; repeat(n - 1)(body) }   // unrolled at compile time

repeat(3)(println("hi"))   // expands to three literal println calls — no loop, no closure
```

Limits and cautions: inlining can cause **code-size blowup** if applied to large bodies or high counts; deeply recursive `inline` risks long compile times; `inline` parameters that are `inline`-by-name change evaluation semantics, so reasoning shifts from runtime to compile time; and over-inlining hurts readability and debuggability. The discipline is to reserve `inline` for genuine zero-cost abstractions and compile-time computation (type-class derivation, dimensional-analysis helpers), not as a blanket performance hammer.

#### Q89. [Theory] What is a path-dependent type, and how do type members enable "family polymorphism"?

In Scala a *type member* declared inside a (non-static) object participates in that object's identity: `a.T` and `b.T` are **different** types when `a` and `b` are different values, even if both are instances of the same class. This is path-dependence — the type's full name includes the value path that reaches it. It lets you encode that two values "belong together" so the compiler rejects mixing them.

```scala
abstract class Graph:
  type Node                      // abstract type member, distinct per Graph instance
  def addEdge(from: Node, to: Node): Unit
  def newNode(): Node

val g1: Graph = makeGraph()
val g2: Graph = makeGraph()
val a = g1.newNode()             // type g1.Node
val b = g2.newNode()             // type g2.Node
g1.addEdge(a, a)                 // OK — both are g1.Node
// g1.addEdge(a, b)              // COMPILE ERROR: b is g2.Node, not g1.Node
```

This is **family polymorphism**: the `Graph` class defines a whole family of related types (`Node`, `Edge`) that vary together per instance, and path-dependent typing statically prevents using one graph's nodes with another. The same mechanism underlies the cake pattern (`self.SomeType`), the `Aux` pattern for surfacing dependent results, and ZIO's environment encoding — making it a frequent senior/staff-level discussion point about Scala's expressive type system.

#### Q90. [Coding] Demonstrate the `Aux` pattern to expose a type member as a type parameter for dependent return types.

```scala
trait Concat[A, B]:
  type Out                       // the (dependent) result type
  def apply(a: A, b: B): Out

object Concat:
  // 'Aux' surfaces the hidden type member Out as a third type parameter,
  // so callers and the compiler can refer to it and propagate it.
  type Aux[A, B, O] = Concat[A, B] { type Out = O }

  given Concat.Aux[String, String, String] with
    type Out = String
    def apply(a: String, b: String): String = a + b

  given Concat.Aux[List[Int], List[Int], List[Int]] with
    type Out = List[Int]
    def apply(a: List[Int], b: List[Int]): List[Int] = a ++ b

// Return type is computed from the instance's Out, preserved precisely:
def combine[A, B](a: A, b: B)(using c: Concat[A, B]): c.Out = c(a, b)

val s: String    = combine("foo", "bar")             // "foobar"
val xs: List[Int] = combine(List(1, 2), List(3, 4))  // List(1,2,3,4)
```

The problem the `Aux` pattern solves: a type member (`Out`) lives on the *instance*, so a caller can't name it as a plain type parameter. `type Aux[A, B, O] = Concat[A, B] { type Out = O }` is a refinement alias that "lifts" `Out` into a type parameter `O`, letting type inference flow the dependent result through call sites (the function's declared return type is the path-dependent `c.Out`). This pattern is pervasive in type-level/generic-programming libraries (Shapeless historically, and modern `Mirror`-based derivations) where an operation's result type is *derived* from its inputs rather than fixed in advance.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q91. [Practical] Your build fails with "value flatMap is not a member of Future" inside a for-comprehension. What is the cause and the fix?

Almost always a **missing `ExecutionContext`**. `Future.map`/`flatMap` take an implicit `ExecutionContext`; without one in scope the methods are still *defined*, but the compiler can't resolve the implicit, and the error surfaces (confusingly) as "is not a member" or "could not find implicit value." Bring an EC into scope:

```scala
import scala.concurrent.{Future, ExecutionContext}
// In application code, prefer a named, configured pool:
implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(
  java.util.concurrent.Executors.newFixedThreadPool(8)
)
// Quick experiments only:
import scala.concurrent.ExecutionContext.Implicits.global
```

In production never lean on `global` for blocking work — it's a `ForkJoinPool` sized to CPU cores, so blocking calls starve it. Use a dedicated pool (or `blocking { ... }`) for JDBC/HTTP, and pass the EC explicitly at boundaries so it's auditable.

#### Q92. [Practical] `List(1,2,3).sum` works but `List("a","b").sum` won't compile. Why, and how do you sum a custom type?

`sum` requires an implicit `Numeric[A]` (in Scala 3, a given). `Int`/`Double`/`BigInt` have one; `String` does not, so resolution fails. To sum a custom type, either fold explicitly or provide a `Numeric`/`Monoid`-style combiner:

```scala
case class Money(cents: Long)

// Simplest: fold with an explicit combine + zero
val total = List(Money(100), Money(250))
  .foldLeft(Money(0))((acc, m) => Money(acc.cents + m.cents))   // Money(350)

// Or supply a Numeric[Money] if you really want .sum (overkill for most cases)
```

For strings you almost never want `sum`; use `mkString` to concatenate. The lesson: `sum`/`product` are constrained by a type class, and the fix is to give the compiler the combining evidence rather than reach for a String hack.

#### Q93. [Coding] Group a list of transactions by category and produce the total amount per category in one pass.

```scala
case class Txn(category: String, amount: Double)

val txns = List(
  Txn("food", 12.0), Txn("rent", 900.0),
  Txn("food", 8.5),  Txn("travel", 40.0)
)

// groupMapReduce: key fn, value fn, reduce fn — single traversal, no intermediate lists
val totals: Map[String, Double] =
  txns.groupMapReduce(_.category)(_.amount)(_ + _)
// Map(food -> 20.5, rent -> 900.0, travel -> 40.0)
```

`groupMapReduce(key)(value)(combine)` is the idiomatic one-pass aggregation. The naive `groupBy(_.category).view.mapValues(_.map(_.amount).sum).toMap` works but materializes a `Map[String, List[Txn]]` first; `groupMapReduce` never builds the intermediate groups.

#### Q94. [Practical] You see "non-exhaustive match" warnings ignored in CI and a `MatchError` blows up in production. How do you prevent this class of bug?

Treat the warning as an error and make the type closed. (1) Use `sealed trait`/`enum` so the compiler *can* prove exhaustiveness; matching on an open type (`Any`, an unsealed trait) gives no warning at all. (2) Turn warnings into failures with `-Werror` (Scala 3) / `-Xfatal-warnings` (Scala 2) in your build so the CI fails instead of warning:

```scala
// build.sbt
scalacOptions ++= Seq("-Werror", "-deprecation", "-feature")
```

```scala
sealed trait Status
case object Active extends Status
case object Closed extends Status
// adding `case object Pending` now forces every match to be updated or CI breaks
```

The combination — sealed hierarchies plus fatal warnings — converts a runtime `MatchError` into a compile-time failure, which is exactly where you want it.

#### Q95. [Coding] Parse a list of strings into ints, discarding the ones that don't parse, without throwing.

```scala
val raw = List("1", "two", "3", "", "4x")

val ints: List[Int] =
  raw.flatMap(s => scala.util.Try(s.trim.toInt).toOption)
// List(1, 3)
```

`Try(s.toInt).toOption` turns a throwing parse into `Some(n)`/`None`, and `flatMap` over `Option` drops the `None`s in the same step (`Option` flattens into the surrounding `List`). If you also need to *report* the failures, swap to `Either` and partition:

```scala
val (errors, ok) = raw
  .map(s => scala.util.Try(s.trim.toInt).toEither.left.map(_ => s))
  .partitionMap(identity)            // (List[String], List[Int])
```

#### Q96. [Practical] A teammate wrote `if (x == null)` in Scala. Why is that a smell, and what's the idiomatic rewrite?

Explicit `null` checks defeat the point of Scala's type system: `null` is only reachable via Java interop or `var` mishandling, and scattering null checks reintroduces the NPE risk `Option` was meant to remove. Wrap at the boundary and stay in `Option` thereafter:

```scala
// Java API returns possibly-null:
val cfg: String = System.getenv("API_URL")   // may be null

// Idiomatic:
val url = Option(System.getenv("API_URL")).getOrElse("http://localhost")
```

`Option(x)` maps `null -> None` exactly once, at the edge. Inside your own code you never produce `null`, so downstream code uses `map`/`getOrElse` and the null never propagates. Reserve raw `null` strictly for the interop seam.

#### Q97. [Coding] Safely get the first element of a possibly-empty list and provide a default.

```scala
val xs = List.empty[Int]

xs.headOption.getOrElse(-1)   // -1, no exception
// vs xs.head -> throws NoSuchElementException

// Same idea for maps:
val m = Map("a" -> 1)
m.get("z").getOrElse(0)        // 0
```

`head`/`apply` on collections throw on missing elements; their `*Option` cousins (`headOption`, `lastOption`, `get`, `find`, `lift`) return `Option` and compose cleanly. Reaching for the partial version (`head`, `m("z")`, `xs(99)`) is a frequent source of production exceptions — prefer the total `Option`-returning variant.

#### Q98. [Practical] Your `for` loop with `yield` returns `Unit` instead of a collection. What went wrong?

You wrote `for (...) { ... }` (a block body, no `yield`), which desugars to `foreach` and returns `Unit`. To build a collection you must use `yield`:

```scala
// Wrong: returns Unit (foreach)
val a = for (x <- 1 to 3) { x * x }          // a: Unit

// Right: returns IndexedSeq(1, 4, 9) (map)
val b = for (x <- 1 to 3) yield x * x        // b: IndexedSeq[Int]
```

Mnemonic: **`yield` means "collect," no `yield` means "do for side effects."** If you intended side effects (printing, mutation), the `Unit` result is correct; if you wanted values back, add `yield`.

### 🟡 — extended

#### Q99. [Practical] A Spark job throws `Task not serializable`. Walk through diagnosing and fixing it.

The closure you passed to a transformation (`map`, `filter`, …) captured a reference to a non-serializable object — commonly `this` of an enclosing class, a logger, a DB connection, or a config holder. Spark serializes the closure to ship it to executors and fails on the captured field.

```scala
class Pipeline(threshold: Int) {
  val logger = LoggerFactory.getLogger(getClass)  // not serializable

  // BAD: closure captures `this` (for `threshold`) and pulls in `logger`
  def run(rdd: RDD[Int]) = rdd.filter(_ > threshold)
}

// FIX 1: copy captured values to local vals so only primitives are captured
def run(rdd: RDD[Int]) = {
  val t = threshold                 // local — only an Int is serialized
  rdd.filter(_ > t)
}
// FIX 2: mark non-serializable fields @transient lazy val, or create them per-partition
//        with mapPartitions so the connection is built on the executor, not shipped.
```

Diagnosis: read the "Serialization stack" in the exception — it names the exact field that couldn't be serialized. The durable fixes are: capture local vals (not `this`), use `mapPartitions` to construct heavy/non-serializable resources on the executor, and mark genuinely transient state `@transient lazy val`.

#### Q100. [Coding] Implement a retry-with-backoff combinator for a `=> Future[A]` thunk.

```scala
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.*

def retry[A](attempts: Int, delay: FiniteDuration)
            (op: => Future[A])
            (using ec: ExecutionContext, sched: java.util.concurrent.ScheduledExecutorService): Future[A] =
  op.recoverWith {
    case _ if attempts > 1 =>
      val p = scala.concurrent.Promise[A]()
      sched.schedule(
        (() => p.completeWith(retry(attempts - 1, delay * 2)(op))): Runnable,
        delay.toMillis, MILLISECONDS
      )
      p.future
  }
```

Key points: `op` is **by-name** (`=> Future[A]`) so each retry re-invokes it (a by-value `Future[A]` would reuse the same already-started future and never actually retry). `recoverWith` lets the recovery itself be async; we schedule the next attempt after `delay` and double it (`delay * 2`) for exponential backoff. In real code you'd cap the delay and only retry on *retryable* exceptions, not blindly on all.

#### Q101. [Practical] Compile times on your module have crept to minutes. What Scala-specific causes do you investigate?

Scala compilation is dominated by implicit/given resolution, type inference, and macro/derivation expansion. Investigate, in order: (1) **Heavy implicit search** — large type-class derivations (e.g., auto-derived JSON codecs via `Mirror`/Magnolia) expand a lot of code; switch from `auto` to `semiauto` derivation so instances are computed once and cached. (2) **Deeply nested for-comprehensions / big methods** — type-inference cost grows superlinearly; annotate return types on public methods to cut inference work. (3) **Implicit conversions in scope** — each call site triggers a search. (4) Use `-Vimplicits`/`-Xprint:typer` and the `scalac -Ystatistics` flags, or the sbt `compile` timing, to find hotspots. Practical levers: split god-modules, enable build caching/incremental compilation, prefer explicit instances, and avoid `shapeless`-style auto-derivation in hot files.

#### Q102. [Coding] You have `Future[Option[User]]` and `Option[User] => Future[Order]`. Compose them cleanly, returning `Future[Option[Order]]`.

```scala
import scala.concurrent.{Future, ExecutionContext}

def lookup(id: Int): Future[Option[User]] = ???
def order(u: User): Future[Order] = ???

// Without a transformer library: nest carefully and lift the None branch
def orderForUser(id: Int)(using ec: ExecutionContext): Future[Option[Order]] =
  lookup(id).flatMap {
    case Some(u) => order(u).map(Some(_))
    case None    => Future.successful(None)
  }

// With Cats' OptionT, the same thing reads flat:
import cats.data.OptionT
import cats.implicits.*
def orderForUser2(id: Int)(using ec: ExecutionContext): Future[Option[Order]] =
  (for
     u <- OptionT(lookup(id))
     o <- OptionT.liftF(order(u))
   yield o).value
```

The vanilla version is fine for one level; once you stack two or three `Future[Option[_]]` steps, a monad transformer (`OptionT[Future, A]`) keeps the code flat and short-circuits on either `None` or a failed `Future`.

#### Q103. [Practical] An immutable `Map` update in a hot loop is showing up in your profiler. What's happening and how do you fix it?

Each `map + (k -> v)` returns a *new* `Map`, and while persistent maps share structure, a tight loop doing millions of single-key updates still allocates one wrapper per update and chases pointers — measurably slower and GC-heavier than mutation. Options, in order of preference:

```scala
// 1) Build with a mutable Map locally, freeze at the end (encapsulated mutation):
def histogram(xs: Iterator[String]): Map[String, Int] =
  val m = scala.collection.mutable.HashMap.empty[String, Int]
  xs.foreach(s => m.update(s, m.getOrElse(s, 0) + 1))
  m.toMap

// 2) If you already have the data, a single-pass reduce avoids per-update Maps:
def histogram2(xs: List[String]): Map[String, Int] =
  xs.groupMapReduce(identity)(_ => 1)(_ + _)
```

The idiom is "mutate locally behind a pure boundary": use `mutable.HashMap` inside the function and return an immutable `Map`. Callers still see immutability; you avoid the per-iteration allocation. Reach for this only when a profiler proves it matters.

#### Q104. [Coding] Write a function that times any block and returns both the result and elapsed millis.

```scala
def timed[A](label: String)(block: => A): A =
  val start = System.nanoTime()
  val result = block                              // by-name: evaluated here, once
  val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
  println(f"[$label] took $elapsedMs%.2f ms")
  result

val sum = timed("sum") { (1 to 1_000_000).sum }   // logs timing, returns the sum
```

The parameter is **by-name** (`=> A`) so the block isn't evaluated at the call site before `timed` runs — it's evaluated exactly once, inside, where we bracket it with the clock. A by-value `block: A` would run before the timer started, defeating the purpose. This is the loan-pattern shape applied to instrumentation.

#### Q105. [Practical] Your code does `someFuture.map(...)` but exceptions thrown inside `fetch()` aren't being caught by `.recover`. Why might that be?

If the exception is thrown **synchronously while constructing the future** (before `Future { ... }` captures it), it escapes the future entirely. For example `Future.successful(risky())` evaluates `risky()` eagerly on the calling thread — a throw there propagates synchronously and never enters the future's failure channel, so `.recover` can't see it.

```scala
// BUG: parse() runs on the calling thread; a throw bypasses the Future
def bad(s: String): Future[Int] = Future.successful(s.toInt)   // throws synchronously!

// FIX: run the risky work inside Future.apply so the throw is captured
def good(s: String)(using ec: ExecutionContext): Future[Int] = Future(s.toInt)

good("x").recover { case _: NumberFormatException => -1 }      // works
```

Rule: use `Future(expr)` (which is `Future.apply`, capturing exceptions into a `Failure`) for any work that can throw; reserve `Future.successful`/`failed` for values you already have.

#### Q106. [Coding] Deduplicate a list while preserving first-seen order, then show why a naive `toSet.toList` is wrong here.

```scala
val xs = List(3, 1, 3, 2, 1, 4)

// Preserves encounter order:
xs.distinct                    // List(3, 1, 2, 4)

// Naive toSet.toList loses order (HashSet has no insertion order):
xs.toSet.toList                // e.g. List(1, 2, 3, 4) — order NOT guaranteed

// Dedup by a key (first wins):
case class P(id: Int, name: String)
val ps = List(P(1,"a"), P(2,"b"), P(1,"c"))
ps.distinctBy(_.id)            // List(P(1,a), P(2,b))
```

`distinct`/`distinctBy` keep the first occurrence in order; `toSet.toList` round-trips through a `HashSet`, which reorders by hash. When order matters (audit logs, UI lists, stable output) use `distinct`; only use `toSet` when you genuinely want set semantics and don't care about order.

### 🟠 — extended

#### Q107. [Practical] A long-running service slowly leaks memory; a heap dump shows a giant `LazyList`/`Stream`. What's the classic cause?

Holding a reference to the **head** of a lazy sequence while iterating forces it to memoize every element, defeating the constant-memory streaming you wanted. `LazyList` caches computed cells; if a `val` (or an enclosing closure/field) keeps the head alive, the whole realized prefix is retained and never collected.

```scala
// LEAK: `all` references the head, so every element stays in memory
val all: LazyList[Record] = readAll()        // field on a long-lived object
all.foreach(process)                          // realizes & retains everything

// FIX: don't bind the head; consume via an Iterator (no memoization)
def stream(): Iterator[Record] = readAllIterator()
stream().foreach(process)                     // each element is GC'd after use
```

The fix is to use `Iterator` (which is *not* memoized) for one-shot streaming traversals, and to avoid storing the head of a `LazyList` in a field. Reserve `LazyList` for cases where you genuinely want to reuse/replay the computed prefix.

#### Q108. [Coding] Implement a bounded parallelism `mapAsync`: run a function over a list with at most N futures in flight.

```scala
import scala.concurrent.{Future, ExecutionContext}

def mapAsyncBounded[A, B](xs: List[A], parallelism: Int)
                         (f: A => Future[B])
                         (using ec: ExecutionContext): Future[List[B]] =
  // Process in chunks of `parallelism`: within a chunk run in parallel,
  // across chunks run sequentially, bounding concurrent futures.
  xs.grouped(parallelism).foldLeft(Future.successful(List.empty[B])) { (accF, chunk) =>
    for
      acc     <- accF
      results <- Future.traverse(chunk)(f)   // chunk runs concurrently
    yield acc ++ results
  }

// Usage: at most 4 HTTP calls in flight at once
mapAsyncBounded(urls, 4)(fetch)
```

`Future.traverse` runs a *whole* list concurrently — unbounded, which can exhaust connection pools. Chunking with `grouped(parallelism)` and sequencing the chunks via `foldLeft` caps in-flight work at N. (In production you'd more likely reach for Akka/Pekko Streams' `mapAsync(n)` or fs2's `parEvalMapN`, which do this with proper streaming backpressure.)

#### Q109. [Practical] Two given instances for the same type are in scope and the compiler reports "ambiguous given instances." How do you resolve it correctly?

Ambiguity means the implicit scope contains two equally-specific candidates. Resolve by *removing* the ambiguity, not by force: (1) Don't define orphan instances in multiple places — put the canonical given in the **companion object** of the type or the type class, where it's found without imports and there's exactly one. (2) If two libraries each provide one, import only the one you want and don't wildcard-import the other. (3) For intentional alternatives (e.g., two `Ordering`s), make them named `given`s and pass explicitly with `using`:

```scala
object Orderings:
  given byName: Ordering[User]  = Ordering.by(_.name)
  given byAge:  Ordering[User]  = Ordering.by(_.age)

import Orderings.byAge
users.sorted                       // uses byAge (the only one imported)
users.sorted(using Orderings.byName)  // explicit override at the call site
```

The anti-pattern is `given prioritized` hacks or lowering priority to mask a design problem; the right answer is a single canonical instance plus explicit selection when you truly need a choice.

#### Q110. [Coding] Build a typeclass-derived `Eq` automatically for case classes using Scala 3 `Mirror`.

```scala
import scala.deriving.Mirror
import scala.compiletime.{erasedValue, summonInline}

trait Eq[A]:
  def eqv(x: A, y: A): Boolean

object Eq:
  given Eq[Int]    with { def eqv(x: Int, y: Int)       = x == y }
  given Eq[String] with { def eqv(x: String, y: String) = x == y }

  // Compare each field of a product (case class) element-wise:
  inline def summonAll[T <: Tuple]: List[Eq[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[Eq[h]] :: summonAll[t]

  inline def derived[A](using m: Mirror.ProductOf[A]): Eq[A] =
    val instances = summonAll[m.MirroredElemTypes]
    new Eq[A]:
      def eqv(x: A, y: A): Boolean =
        val xs = x.asInstanceOf[Product].productIterator.toList
        val ys = y.asInstanceOf[Product].productIterator.toList
        instances.lazyZip(xs).lazyZip(ys).forall { (eq, a, b) =>
          eq.asInstanceOf[Eq[Any]].eqv(a, b)
        }

case class Point(x: Int, y: Int) derives Eq

summon[Eq[Point]].eqv(Point(1,2), Point(1,2))   // true
```

`Mirror.ProductOf[A]` exposes `MirroredElemTypes` (the field types as a tuple); `summonAll` recursively summons an `Eq` for each field at compile time, and `derived` zips field instances against the runtime field values. The `derives Eq` clause invokes `Eq.derived` automatically. This is the mechanism behind libraries like circe and Cats' auto-derivation — done by the compiler with no runtime reflection.

#### Q111. [Practical] A `@tailrec` method suddenly fails to compile after a refactor that added logging. What likely broke the tail call, and how do you fix it without losing the log?

Wrapping the recursive call so something runs *after* it returns breaks tail position. A common culprit is `try`/`catch` around the call, or composing the result (`"x: " + recurse(...)`), or a `recurse(...)` inside a `finally`. Logging the *result* after the call also breaks it.

```scala
// BROKEN: the recursive call is inside try -> not in tail position
@tailrec def loop(n: Int): Int =
  try loop(n - 1)        // compiler error: not tail-recursive
  catch { case _: Throwable => 0 }

// FIX: log BEFORE the call (which stays in tail position), or restructure
@tailrec def loop2(n: Int): Int =
  if n <= 0 then 0
  else
    logger.debug(s"step $n")   // side effect happens before the tail call
    loop2(n - 1)               // still the last action -> tail position preserved
```

Rule: the recursive call must be the *syntactically last* action with nothing pending — no `try`, no surrounding expression, no post-call logging. Do side effects before the call. If you genuinely need work after each step, switch to an explicit accumulator or a trampoline.

#### Q112. [Coding] Demonstrate and fix the "captured loop variable" closure pitfall when building a list of functions.

```scala
// In Scala this works as expected (each closure captures its own `i`)
// because `for` desugars to a fresh binding per iteration:
val fns = for (i <- 1 to 3) yield () => i
fns.map(_())                              // Vector(1, 2, 3)  ✓

// The pitfall appears with a shared MUTABLE var captured by reference:
var k = 0
val buf = scala.collection.mutable.ListBuffer.empty[() => Int]
while k < 3 do
  buf += (() => k)                        // all closures capture the SAME var k
  k += 1
buf.toList.map(_())                       // List(3, 3, 3)  ✗ (k is now 3)

// FIX: copy the current value into an immutable local before capturing
val buf2 = scala.collection.mutable.ListBuffer.empty[() => Int]
var j = 0
while j < 3 do
  val snapshot = j                        // fresh immutable binding per iteration
  buf2 += (() => snapshot)
  j += 1
buf2.toList.map(_())                      // List(0, 1, 2)  ✓
```

Closures capture *variables*, not values. A `for`/`map` already gives each iteration a fresh `val`, so it's safe; a `var` mutated in a `while` is shared, so every closure sees its final value. The fix is to bind the current value to an immutable local (`val snapshot = j`) before closing over it — which is also why functional style (no shared `var`) sidesteps the bug entirely.

#### Q113. [Practical] You need to call a blocking JDBC API from async code without starving the default `ExecutionContext`. What's the correct pattern?

The global/forkjoin EC is sized to CPU cores; blocking a thread there reduces effective parallelism and can deadlock. Two correct approaches: (1) Run blocking work on a **dedicated, bounded pool** separate from your CPU pool; (2) wrap the blocking call in `scala.concurrent.blocking { ... }` so a `ForkJoinPool` can spawn a compensating thread.

```scala
import scala.concurrent.{Future, ExecutionContext, blocking}
import java.util.concurrent.Executors

// Dedicated pool for IO/blocking work, sized to your DB connection pool:
given ioEc: ExecutionContext =
  ExecutionContext.fromExecutor(Executors.newFixedThreadPool(20))

def query(sql: String): Future[ResultSet] = Future {
  blocking {                       // signals "this thread will block"
    stmt.executeQuery(sql)         // the actual blocking JDBC call
  }
}(ioEc)                            // explicitly on the IO pool, NOT the CPU pool
```

Best practice: keep a small CPU-bound pool for computation and a separate, larger IO pool (sized to match your connection pool) for blocking calls; route blocking `Future`s to the IO pool explicitly. Effect systems (Cats Effect/ZIO) formalize this with `blocking`/`evalOn` and managed thread pools.

### 🔴 — extended

#### Q114. [Practical] In a high-throughput service, you observe excessive `Integer`/`Double` boxing in allocation profiles around generic code. How do you eliminate it?

Generic code over `A` erases to `Object`, so primitives get boxed whenever they flow through a generic API (`List[Int]`, `Option[Double]`, `A => B`). Mitigations, by impact: (1) Use **specialized monomorphic collections** — `Array[Int]` (true primitive array) instead of `List[Int]`/`Vector[Int]` in hot paths. (2) For generic hot code, `@specialized` (Scala 2) or `IArray`/manual specialization can generate primitive variants, though `@specialized` bloats bytecode and is largely discouraged now. (3) Avoid passing primitives through generic function values in inner loops; inline the loop with a `while` over an `Array`. (4) Check that `Numeric`/`Ordering` usage in tight loops isn't boxing on every comparison — sort primitive arrays with `java.util.Arrays.sort` when it matters.

```scala
// Boxing-heavy: each element boxed into Integer
def sumGeneric[A](xs: Seq[A])(using n: Numeric[A]): A = xs.foldLeft(n.zero)(n.plus)

// Allocation-free hot loop over a primitive array:
def sumInts(xs: Array[Int]): Long =
  var i = 0; var acc = 0L
  while i < xs.length do { acc += xs(i); i += 1 }
  acc
```

The senior judgment: keep the elegant generic API at the boundary, but drop to `Array` + `while` in the measured hot path, and verify with a profiler/JMH rather than guessing.

#### Q115. [Coding] Implement a stack-safe, trampolined `foldRight` so it doesn't overflow on a million-element list.

```scala
// foldRight is naturally non-tail-recursive and overflows on long Lists.
// Trampoline the recursion via a thunk-based work loop.
enum Trampoline[+A]:
  case Done(a: A)
  case More(next: () => Trampoline[A])

import Trampoline.*

@annotation.tailrec
def run[A](t: Trampoline[A]): A = t match
  case Done(a)  => a
  case More(k)  => run(k())

def foldRightSafe[A, B](xs: List[A], z: B)(f: (A, B) => B): B =
  def go(rem: List[A]): Trampoline[B] = rem match
    case Nil     => Done(z)
    case h :: t  => More(() => go(t)).asInstanceOf[Trampoline[B]] match
      case _ => More(() => go(t)) // build the suspended tail
  // simpler: fold via reverse + tail-recursive foldLeft for true safety
  xs.reverse.foldLeft(z)((acc, a) => f(a, acc))

foldRightSafe((1 to 1_000_000).toList, 0L)((a, acc) => a + acc)  // no overflow
```

In practice the pragmatic stack-safe `foldRight` is "reverse, then `foldLeft`" — `foldLeft` is tail-recursive and compiles to a loop, and reversing first restores right-fold order for associative/commutative-insensitive combiners. A true general trampoline (as sketched with `Trampoline`/`run`) defers each step as a thunk and drives it with a `@tailrec` loop, which is how effect libraries (`Eval` in Cats) make arbitrary recursion stack-safe. The interview point: `foldRight` on `List` is *not* stack-safe by default, and you must either reverse-and-`foldLeft`, use `LazyList`/`Eval`, or trampoline.

#### Q116. [Practical] Your team debates `Future` vs Cats Effect `IO` for a new service. Argue the trade-offs as a tech lead would.

`Future` is in the standard library, familiar, and adequate for simple request/response async. Its drawbacks for a serious service: it is **eager** (starts on construction, so it's not referentially transparent and can't be retried/canceled by re-running the value), has **no cancellation**, ties error handling to exceptions, and makes it easy to accidentally lose parallelism or run blocking work on the wrong pool. `IO` (Cats Effect) / `ZIO` are **lazy descriptions** of effects: referentially transparent (you can pass an `IO` around and run it later/repeatedly), support **cancellation and resource safety** (`Resource`, `bracket`), structured concurrency (fibers, `parTraverse`), fiber-aware blocking pools, and lawful composition. The cost is a learning curve, a dependency, and a more abstract mental model the whole team must share. My decision rule: for a small service or one with a Future-based ecosystem (Akka HTTP, Slick), `Future` is fine; for a concurrency-heavy service needing cancellation, backpressure, retries, and testable effects, adopt an effect system — but only if the team will invest in learning it, since half-understood `IO` is worse than well-understood `Future`.

#### Q117. [Coding] Write a property-based test (ScalaCheck) that catches a subtle bug in a custom `merge` of two sorted lists.

```scala
import org.scalacheck.Prop.forAll
import org.scalacheck.Properties

def merge(a: List[Int], b: List[Int]): List[Int] = (a, b) match
  case (Nil, ys) => ys
  case (xs, Nil) => xs
  case (x :: xt, y :: yt) =>
    if x < y then x :: merge(xt, b)        // BUG: uses `<`, drops/duplicates equal elems? check via props
    else y :: merge(a, yt)

object MergeSpec extends Properties("merge"):

  property("result is sorted when inputs are sorted") = forAll { (xs: List[Int], ys: List[Int]) =>
    val a = xs.sorted; val b = ys.sorted
    val merged = merge(a, b)
    merged == merged.sorted
  }

  property("merge preserves all elements (multiset equality)") = forAll { (xs: List[Int], ys: List[Int]) =>
    val merged = merge(xs.sorted, ys.sorted)
    merged.sorted == (xs ++ ys).sorted     // catches dropped/duplicated elements
  }

  property("length is additive") = forAll { (xs: List[Int], ys: List[Int]) =>
    merge(xs.sorted, ys.sorted).length == xs.length + ys.length
  }
```

Property-based testing generates hundreds of random inputs and shrinks any failure to a minimal counterexample. The three properties together pin down correctness: *sortedness* alone is insufficient (a buggy merge could return a sorted list that dropped elements), so the **multiset-preservation** and **length** properties catch lost or duplicated elements — exactly the bugs example-based tests miss. This generative, invariant-driven style is a senior testing signal.

#### Q118. [Practical] A microservice using Akka/Pekko actors has a message that occasionally vanishes under load. What actor-model pitfalls do you check?

The actor model gives at-most-once, unordered-across-senders delivery on the local mailbox — "vanishing" messages usually trace to one of: (1) **Mailbox overflow / bounded mailbox dropping** — a bounded mailbox configured with a drop strategy silently discards on overflow; check mailbox type and capacity. (2) **`context.become`/state transition** where the new behavior doesn't handle that message type, so it hits `unhandled` and goes to dead letters — monitor the **dead-letter** queue, which logs exactly these. (3) **Sending to a stale `ActorRef`** after a restart (the ref is valid but the actor's state reset) or after stop (messages go to dead letters). (4) **Blocking the dispatcher** so the actor can't drain its mailbox fast enough, causing timeouts upstream that *look* like loss. (5) For cross-node, no delivery guarantee without **at-least-once** semantics (`AtLeastOnceDelivery`/reliable messaging) — plain remote sends can drop. Diagnosis path: enable dead-letter logging and mailbox metrics first; they pinpoint whether messages are dropped, unhandled, or just delayed by a starved dispatcher. The durable fix for true reliability is explicit acknowledgement + redelivery (or moving the critical path to a persistent queue), since the actor model deliberately does not promise guaranteed delivery by default.

#### Q119. [Coding] Encode a compile-time-safe state machine so illegal transitions don't compile (phantom types).

```scala
// Phantom type tags for door states — they carry no runtime data,
// they exist only to constrain which methods are callable.
sealed trait Open
sealed trait Closed

final class Door[State] private ():
  def open(using ev: State =:= Closed): Door[Open]   = new Door[Open]
  def close(using ev: State =:= Open): Door[Closed]  = new Door[Closed]

object Door:
  def closed: Door[Closed] = new Door[Closed]

val d = Door.closed
val opened  = d.open       // OK: Closed -> Open
val closed2 = opened.close // OK: Open -> Closed
// opened.open             // does NOT compile: no evidence Open =:= Closed
// d.close                 // does NOT compile: no evidence Closed =:= Open
```

The `State` type parameter is a **phantom type** — never instantiated, present only to drive the type checker. Each transition method demands evidence (`using ev: State =:= Required`) that the current state equals the legal precondition, so calling `open` on an already-open door fails to compile rather than throwing at runtime. This lifts the state machine's legality rules into the type system, eliminating an entire class of runtime errors — a powerful (if advanced) Scala technique for protocol/resource safety.

#### Q120. [Behavioral] You inherited a Scala codebase that overuses implicits, operator-heavy DSLs, and `shapeless` such that new hires can't contribute. As the senior engineer, what do you do?

Frame it as managing the **dialect**, not condemning the language. Concretely: (1) **Diagnose with the team** — confirm the pain is real (onboarding time, review friction, compile times) rather than personal taste, and gather examples. (2) **Establish a style charter** — restrict the in-house dialect: minimize implicit conversions, prefer named methods over cryptic operators, prefer `given`/`using` with instances in companion objects, and reserve `shapeless`/heavy type-level code to a small, well-documented, well-owned core. (3) **Refactor incrementally** behind tests — replace auto-derivation with semiauto where it hurts compile times, rename operator soup, and add type annotations to public APIs so error messages improve. (4) **Invest in onboarding** — a one-page idioms guide and pairing, plus example PRs showing the preferred patterns. (5) **Measure** — track onboarding ramp and CI times to show the change paid off. The signals an interviewer wants: you respect the existing code and authors, you make the cost concrete, you constrain complexity *deliberately* (not reflexively), and you change the codebase incrementally with tests rather than launching a risky rewrite — leadership and technical judgment together.

## ✅ Key Takeaways

- **OO + FP on the JVM**: every value is an object, every function is a value; immutability and expression-orientation are the default style, with full Java interop.
- **Model data with `case class` + `sealed trait`**, then drive logic with exhaustive `match`; you get value semantics, `copy`, and compiler-checked exhaustiveness for free.
- **Encode effects in types**: `Option` (absence), `Either` (modeled errors), `Try` (exceptions), `Future`/`IO` (async) — and thread them with for-comprehensions, which desugar to `map`/`flatMap`.
- **Traits + linearization** give flexible mixin composition; understand the right-to-left, last-wins order when stacking `super` calls.
- **Type classes via `given`/`using`** (Scala 3) are the idiomatic route to ad-hoc polymorphism and boilerplate-free derivation (`derives`); higher-kinded types let you abstract over containers/effects.
- **Variance** (`+`/`-`/invariant) keeps subtyping sound: covariant producers, contravariant consumers, invariant mutable cells.
- **Tail recursion + `@tailrec`** turn recursion into loops; trampolining handles the non-tail cases stack-safely.
- **Concurrency menu**: `Future` for simple async, actors (Pekko) for stateful/distributed systems, effect systems (Cats Effect/ZIO) for pure, structured concurrency. Scala's laziness, closures, and immutability are exactly why **Spark** is written in it.

## ⚠️ Common Pitfalls

- Writing sequential `Future`s by accident — generators in a for-comprehension run **serially**; start independent futures as `val`s first for parallelism.
- Overusing `var` and mutable collections, throwing away the thread-safety and reasoning benefits of immutability.
- Forgetting `sealed`, so `match` exhaustiveness checking can't help — leading to silent `MatchError` at runtime.
- Abusing implicits/conversions and symbolic operators ("astronaut Scala"), tanking readability and onboarding; constrain the dialect with a style guide and `scalafmt`/`scalafix`.
- Matching on erased generics (`case _: List[Int]`) and trusting it — type arguments are erased; use `ClassTag` or match the contents.
- Building huge `for`/`map`/`flatMap` chains over strict collections in hot paths, allocating intermediate collections — use `view`/`Iterator`/`foldLeft` to fuse.
- Treating `Future` as referentially transparent — it's eager and runs side effects on construction; reach for `IO`/`ZIO` when you need lazy, cancelable, testable effects.
- Reflexively reaching for the cake pattern or deep monad-transformer stacks when constructor injection or a single effect type would be simpler.

## 📚 Further Reading

- *Programming in Scala* (Odersky, Spoon, Venners) — the canonical book, by the language's creator; the 5th edition covers Scala 3.
- *Scala 3 official documentation & "Scala 3 Book"* (docs.scala-lang.org) — authoritative, current reference for `given`/`using`, metaprogramming, and the type system.
- *Functional Programming in Scala* (Chiusano & Bjarnason — "the red book") — builds FP and monads from first principles.
- *Programming Scala* (Wampler, O'Reilly) — broad, pragmatic coverage updated for Scala 3.
- *Essential / Advanced Scala* and the Cats / Cats Effect documentation (typelevel.org) — type classes, effect systems, tagless final.
- *ZIO documentation* (zio.dev) — a modern effect system with structured concurrency and typed errors.
- *Apache Pekko docs* (pekko.apache.org) — the open-source successor to Akka for actors and streams.
- *Apache Spark* programming guide — how Scala's collections/closures map to distributed data processing.
