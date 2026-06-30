# Design Patterns & SOLID

[← Back to master index](../README.md)

A deep, interview-focused guide to object-oriented design for Java engineers: the SOLID principles, the full Gang of Four (GoF) catalogue (creational, structural, behavioral), dependency injection, idiomatic Java realizations, and the anti-patterns interviewers probe to see whether you reach for patterns *thoughtfully* rather than reflexively. Examples use modern Java (records, sealed types, lambdas, `var`) current to Java 21+/2026, and call out where the JDK and Spring already implement each pattern so you can recognize them in real code.

## Table of Contents

- [🟢 Basic (0–2 yrs)](#-basic-02-yrs)
- [🟡 Intermediate (3–7 yrs)](#-intermediate-37-yrs)
- [🟠 Advanced (8–12 yrs)](#-advanced-812-yrs)
- [🔴 Expert (15+ yrs)](#-expert-15-yrs)
- [✅ Key Takeaways](#-key-takeaways)
- [⚠️ Common Pitfalls](#-common-pitfalls)
- [📚 Further Reading](#-further-reading)

---

## 🟢 Basic (0–2 yrs)

### Q1. [Theory] What is a design pattern, and what are the three GoF categories?

A **design pattern** is a named, reusable solution to a recurring design problem in a given context. It is not code you copy-paste; it is a *template* describing how classes and objects collaborate, plus the trade-offs of doing so. Patterns give teams a shared vocabulary — saying "use a Strategy here" communicates structure and intent in two words.

The Gang of Four (Gamma, Helm, Johnson, Vlissides, 1994) grouped 23 patterns into three categories by *purpose*:

- **Creational** — how objects are created (Singleton, Factory Method, Abstract Factory, Builder, Prototype). They decouple a client from the concrete classes it instantiates.
- **Structural** — how objects are composed into larger structures (Adapter, Decorator, Proxy, Facade, Composite, Bridge, Flyweight).
- **Behavioral** — how objects communicate and distribute responsibility (Strategy, Observer, Template Method, Command, State, Chain of Responsibility, Iterator, Visitor, Mediator, Memento, Interpreter).

The meta-point interviewers want: patterns are tools for managing *change* and *coupling*, not goals in themselves.

### Q2. [Theory] What does SOLID stand for and why does it matter?

SOLID is five object-oriented design principles (coined by Robert C. Martin) that lead to code that is easier to change, test, and understand:

- **S — Single Responsibility Principle (SRP):** a class should have one reason to change.
- **O — Open/Closed Principle (OCP):** open for extension, closed for modification.
- **L — Liskov Substitution Principle (LSP):** subtypes must be usable wherever their base type is expected.
- **I — Interface Segregation Principle (ISP):** prefer many small client-specific interfaces over one fat one.
- **D — Dependency Inversion Principle (DIP):** depend on abstractions, not concretions.

They matter because they directly reduce the *cost of change*: SRP and ISP limit the blast radius of an edit, OCP and DIP let you add features without rewriting tested code, and LSP keeps polymorphism honest. Most GoF patterns are concrete ways to satisfy one or more SOLID principles.

### Q3. [Practical] Explain the Single Responsibility Principle with a before/after example.

A class with one *reason to change*. If a class both computes business logic and formats output and persists data, three unrelated stakeholders can force edits to it.

```java
// ❌ Violates SRP: report generation, formatting, and emailing in one class
class ReportManager {
    String generate(SalesData d) { /* business logic */ return "..."; }
    String toHtml(String report) { /* presentation */ return "<html>...</html>"; }
    void email(String html, String to) { /* delivery */ }
}

// ✅ One responsibility each — each can change independently and be tested alone
record Report(String content) {}
class ReportGenerator { Report generate(SalesData d) { /* ... */ return new Report("..."); } }
class HtmlReportRenderer { String render(Report r) { return "<html>" + r.content() + "</html>"; } }
class EmailSender { void send(String body, String to) { /* ... */ } }
```

Now a change to the HTML layout touches only `HtmlReportRenderer`. SRP also improves testability — you unit-test the generator without a mail server.

### Q4. [Practical] Demonstrate the Open/Closed Principle.

You should be able to add new behavior by *adding* code, not editing existing, tested code. A `switch` over a type that grows with every new case is the classic OCP smell.

```java
// ❌ Every new shape forces editing this method (closed for extension)
double area(Shape s) {
    if (s instanceof Circle c)    return Math.PI * c.r() * c.r();
    if (s instanceof Square sq)   return sq.side() * sq.side();
    // ...edit here for every new shape
    throw new IllegalArgumentException();
}

// ✅ Polymorphism: add a class, don't touch existing code
sealed interface Shape permits Circle, Square {
    double area();
}
record Circle(double r) implements Shape { public double area() { return Math.PI * r * r; } }
record Square(double side) implements Shape { public double area() { return side * side; } }
```

Adding `Triangle` means writing a new `record` — `area()` callers stay untouched. (Note: with *sealed* hierarchies, an exhaustive `switch` is itself OCP-friendly because the compiler forces you to handle new cases — a modern nuance worth raising.)

### Q5. [Theory] Explain the Liskov Substitution Principle. Give a classic violation.

LSP: if `S` is a subtype of `T`, objects of `T` may be replaced with objects of `S` **without breaking correctness**. Subclasses must honor the base type's contract — same or weaker preconditions, same or stronger postconditions, no surprising exceptions.

The canonical violation is **Rectangle/Square**: a `Square` "is-a" `Rectangle` in math, but if `Square extends Rectangle` and overrides `setWidth` to also set height, then code written against `Rectangle` (`r.setWidth(5); r.setHeight(4); assert area == 20`) breaks for a `Square`. The fix is to not force the inheritance — model them as independent types, or make them immutable. A practical Java tell: throwing `UnsupportedOperationException` from an overridden method (as some `List` implementations do for `add`) is an LSP violation that callers can't see at compile time.

### Q6. [Theory] Explain Interface Segregation and Dependency Inversion.

**ISP:** clients should not be forced to depend on methods they don't use. A fat `Machine { print(); scan(); fax(); }` forces a simple printer to implement `scan`/`fax` (often by throwing). Split into `Printer`, `Scanner`, `Fax` so each device implements only what it supports.

**DIP:** high-level modules and low-level modules should both depend on **abstractions**, and abstractions should not depend on details.

```java
// ❌ High-level OrderService depends directly on a concrete MySqlOrderRepo
// ✅ Depend on an interface; the concrete impl is injected
interface OrderRepository { void save(Order o); }
class MySqlOrderRepository implements OrderRepository { public void save(Order o) { /* ... */ } }

class OrderService {
    private final OrderRepository repo;        // abstraction, not concretion
    OrderService(OrderRepository repo) { this.repo = repo; }   // injected
}
```

DIP is the principle that *makes dependency injection valuable* — it lets you swap the database, mock it in tests, or add a caching decorator without touching `OrderService`.

### Q7. [Coding] Implement a thread-safe Singleton. What are the common ways?

Singleton ensures one instance and a global access point. The pitfalls are thread safety and lazy vs eager initialization.

```java
// 1. Eager (simplest, thread-safe via class loading) — use if construction is cheap
class Eager {
    private static final Eager INSTANCE = new Eager();
    private Eager() {}
    public static Eager getInstance() { return INSTANCE; }
}

// 2. Lazy holder idiom (thread-safe + lazy, no synchronization cost) — the idiomatic choice
class Holder {
    private Holder() {}
    private static class H { static final Holder INSTANCE = new Holder(); }  // loaded on first use
    public static Holder getInstance() { return H.INSTANCE; }
}

// 3. Enum singleton (Effective Java's recommendation) — serialization & reflection safe
enum Config { INSTANCE; public String get(String k) { return "..."; } }
```

The **enum** approach (Joshua Bloch, *Effective Java*) is the most robust: the JVM guarantees a single instance even under serialization and reflection attacks. The **lazy holder** is preferred when you need lazy init with low overhead. Avoid hand-rolled double-checked locking unless you remember the `volatile` keyword — getting it wrong creates subtle race bugs.

### Q8. [Coding] Implement double-checked locking correctly. Why is `volatile` required?

```java
class DclSingleton {
    private static volatile DclSingleton instance;   // volatile is mandatory
    private DclSingleton() {}
    public static DclSingleton getInstance() {
        if (instance == null) {                       // 1st check: no lock, fast path
            synchronized (DclSingleton.class) {
                if (instance == null) {               // 2nd check: under lock
                    instance = new DclSingleton();
                }
            }
        }
        return instance;
    }
}
```

`new DclSingleton()` is **not atomic** — it (a) allocates memory, (b) runs the constructor, (c) assigns the reference. Without `volatile`, the JVM/CPU may reorder (a)→(c)→(b), so another thread on the fast path could see a non-null but *not-yet-constructed* object. `volatile` forbids that reordering and establishes a happens-before edge, guaranteeing a fully initialized object is visible. Because this is easy to get wrong, prefer the lazy-holder idiom (Q7) which gets the same lazy thread-safety for free via class-initialization semantics.

### Q9. [Practical] What is the Factory Method pattern and when do you use it?

Factory Method defines an interface for creating an object but lets subclasses (or a method) decide which concrete class to instantiate. It decouples client code from `new ConcreteClass()`.

```java
interface Notification { void send(String msg); }
class EmailNotification implements Notification { public void send(String m) { /* ... */ } }
class SmsNotification   implements Notification { public void send(String m) { /* ... */ } }

class NotificationFactory {
    Notification create(String channel) {
        return switch (channel) {
            case "email" -> new EmailNotification();
            case "sms"   -> new SmsNotification();
            default -> throw new IllegalArgumentException("Unknown channel: " + channel);
        };
    }
}
```

Use it when the exact type isn't known until runtime (driven by config, user input, or environment), or when you want to centralize and name construction logic. In the JDK you see it in `Calendar.getInstance()`, `NumberFormat.getInstance()`, and `LocalDate.of(...)`-style static factories.

### Q10. [Practical] What problem does the Builder pattern solve? Show it.

Builder solves the **telescoping constructor** problem — many parameters, especially optional ones, where positional arguments become unreadable and error-prone (`new Pizza(12, true, false, true, ...)`).

```java
public class Pizza {
    private final int size;
    private final boolean cheese, pepperoni, mushroom;

    private Pizza(Builder b) {
        this.size = b.size; this.cheese = b.cheese;
        this.pepperoni = b.pepperoni; this.mushroom = b.mushroom;
    }
    public static Builder builder(int size) { return new Builder(size); }

    public static class Builder {
        private final int size;                 // required
        private boolean cheese, pepperoni, mushroom;  // optional
        Builder(int size) { this.size = size; }
        public Builder cheese()    { this.cheese = true; return this; }
        public Builder pepperoni() { this.pepperoni = true; return this; }
        public Pizza build()       { return new Pizza(this); }   // validation goes here
    }
}
// Usage — readable, immutable result:
Pizza p = Pizza.builder(12).cheese().pepperoni().build();
```

It shines for immutable objects with many optional fields. The fluent chain is self-documenting and `build()` is a natural place for invariant validation. The JDK uses it in `StringBuilder`, `Stream.Builder`, and `HttpRequest.newBuilder()`.

### Q11. [Theory] Builder vs a Java record / constructor — when is Builder overkill?

A `record` with a canonical constructor already gives you immutability and concise syntax. If you have **few fields, all required**, a record or plain constructor is simpler and a Builder is ceremony for nothing:

```java
record Point(int x, int y) {}   // no Builder needed
```

Reach for Builder when there are many *optional* parameters, you need step-wise validation, or you want to prevent invalid intermediate states. For records specifically, a common modern pattern is to keep the record but add a small builder only if optionality justifies it. The interview signal: don't apply Builder reflexively — it's overkill when the simpler tool already expresses the intent.

### Q12. [Theory] What is the Adapter pattern? Give a real example.

Adapter converts the interface of a class into another interface clients expect — it makes incompatible interfaces work together, like a power-plug adapter. You use it when integrating a third-party or legacy class whose API you can't change but whose interface doesn't match your code.

```java
interface PaymentProcessor { void pay(int cents); }          // what your app expects

class StripeApi { void charge(double dollars) { /* vendor */ } }  // can't modify

class StripeAdapter implements PaymentProcessor {            // bridges the two
    private final StripeApi stripe = new StripeApi();
    public void pay(int cents) { stripe.charge(cents / 100.0); }
}
```

In the JDK, `java.util.Arrays.asList(...)` adapts an array to a `List`, and `InputStreamReader` adapts a byte `InputStream` to a character `Reader`.

### Q13. [Theory] What is the Decorator pattern and how does it differ from inheritance?

Decorator attaches additional responsibilities to an object **dynamically**, by wrapping it in another object that implements the same interface. Instead of creating a subclass for every combination of features (an explosion), you compose wrappers at runtime.

```
new BufferedReader(new InputStreamReader(new FileInputStream(file)))
   wraps ───────────▶ wraps ──────────────▶ wraps the raw bytes
```

Each layer adds behavior (buffering, decoding) while presenting the same `Reader`/`InputStream` interface. Versus inheritance: inheritance is static and chosen at compile time; decoration is dynamic and composable. The JDK's `java.io` streams are the textbook example; `Collections.unmodifiableList`/`synchronizedList` are decorators too.

### Q14. [Theory] What is the Strategy pattern? How do lambdas change it in Java?

Strategy defines a family of interchangeable algorithms, encapsulates each, and makes them swappable at runtime. The classic form has an interface plus concrete classes:

```java
interface DiscountStrategy { double apply(double price); }

class NoDiscount     implements DiscountStrategy { public double apply(double p) { return p; } }
class TenPercentOff  implements DiscountStrategy { public double apply(double p) { return p * 0.9; } }

class Checkout {
    private DiscountStrategy strategy;
    void setStrategy(DiscountStrategy s) { this.strategy = s; }
    double total(double price) { return strategy.apply(price); }
}
```

In modern Java, a strategy is just a function, so a **lambda** replaces the boilerplate class:

```java
Checkout c = new Checkout();
c.setStrategy(p -> p * 0.9);                     // lambda IS the strategy
```

Any single-method (`@FunctionalInterface`) strategy can be a lambda or method reference. `Comparator` passed to `Collections.sort` is Strategy in the JDK. This is a great interview talking point: lambdas didn't kill the pattern — they made it nearly free.

### Q15. [Theory] What is the Observer pattern? Where is it in the JDK/Spring?

Observer defines a one-to-many dependency: when one object (the *subject*) changes state, all its dependents (*observers*) are notified automatically. It underpins event-driven and reactive systems.

```java
interface Observer { void update(String event); }

class Subject {
    private final List<Observer> observers = new ArrayList<>();
    void subscribe(Observer o) { observers.add(o); }
    void publish(String event) { observers.forEach(o -> o.update(event)); }
}
```

In the JDK, the legacy `java.util.Observer/Observable` was **deprecated in Java 9** (too limited, not generic); modern code uses `PropertyChangeListener`, `java.util.concurrent.Flow` (reactive streams), or libraries like Project Reactor/RxJava. In Spring, `ApplicationEvent`/`@EventListener` is Observer. Swing/JavaFX listeners are Observer too.

### Q16. [Theory] What is the Template Method pattern?

Template Method defines the **skeleton** of an algorithm in a base method, deferring specific steps to subclasses. The overall flow is fixed; the variable steps are overridden.

```java
abstract class DataImporter {
    public final void importData() {   // template method — final so flow can't change
        var raw = read();
        var clean = validate(raw);
        save(clean);
    }
    protected abstract String read();          // step the subclass fills in
    protected abstract String validate(String r);
    protected abstract void save(String r);
}
```

Marking the template method `final` enforces the invariant order. The JDK's `AbstractList`, `HttpServlet` (`doGet`/`doPost` filled in by you while `service()` orchestrates), and `java.util.AbstractMap` use it. Contrast with Strategy: Template Method varies steps via *inheritance*; Strategy varies the whole algorithm via *composition*.

### Q17. [Practical] What is a Facade and why use one?

A Facade provides a simple, unified interface to a complex subsystem, hiding its many moving parts behind one entry point.

```java
class VideoConverterFacade {                       // one simple call
    String convert(String filename, String format) {
        var file = new VideoFile(filename);
        var codec = new CodecFactory().extract(file);
        var buffer = new BitrateReader().read(file, codec);
        var result = new AudioMixer().fix(buffer);
        return result;                             // client never sees these classes
    }
}
```

Clients call `convert(...)` without knowing about codecs, buffers, or mixers. Facades reduce coupling between clients and subsystems and are a natural API boundary. Spring's `JdbcTemplate` is a facade over the verbose JDBC API; SLF4J is a facade over logging backends.

### Q18. [Theory] What is the Iterator pattern? How does Java implement it?

Iterator provides a way to traverse a collection's elements sequentially without exposing its internal representation. Java builds it into the language:

```java
List<String> items = List.of("a", "b", "c");
Iterator<String> it = items.iterator();
while (it.hasNext()) { String s = it.next(); }
// for-each is syntactic sugar over Iterable/Iterator:
for (String s : items) { /* ... */ }
```

Any class implementing `Iterable<T>` (one method, `iterator()`) works in a for-each loop. `Iterator` also supports safe `remove()` during iteration, and `ListIterator` adds bidirectional traversal. This is the most "invisible" pattern because the JDK made it a language feature.

### Q19. [Practical] What is Dependency Injection and what problem does it solve?

DI is a technique where an object receives its dependencies from the outside (constructor, setter, or field) instead of creating them itself. It is the practical realization of the Dependency Inversion Principle.

```java
// ❌ Hard-wired dependency — can't test without a real database
class OrderService {
    private final OrderRepository repo = new MySqlOrderRepository();
}

// ✅ Injected — swap implementations, inject a mock in tests
class OrderService {
    private final OrderRepository repo;
    OrderService(OrderRepository repo) { this.repo = repo; }   // constructor injection
}
```

DI solves tight coupling and makes code testable, configurable, and reusable. A *DI container* (Spring, Guice, CDI) automates wiring, but DI as a principle works with plain constructors. Prefer **constructor injection** — it makes dependencies explicit, supports `final` fields (immutability), and fails fast if a dependency is missing.

### Q20. [Theory] What is the Prototype pattern?

Prototype creates new objects by **cloning** an existing instance rather than instantiating from scratch — useful when construction is expensive or the configuration is easier to copy than rebuild.

```java
interface Prototype<T> { T copy(); }

class Document implements Prototype<Document> {
    private final List<String> sections;
    Document(List<String> s) { this.sections = new ArrayList<>(s); }
    public Document copy() { return new Document(this.sections); }  // copy constructor style
}
```

Java's built-in `Cloneable`/`Object.clone()` is the historical mechanism, but it's widely considered broken (shallow by default, awkward checked exceptions, no constructor invoked) — *Effective Java* recommends **copy constructors or copy factory methods** instead, as shown. Spring's prototype *scope* is a different concept (a new bean per request), not this pattern.

### Q21. [Practical] When should you NOT use a design pattern?

When the pattern adds more indirection than the problem warrants. Signs of over-engineering:

- A Factory/Strategy with exactly **one** implementation that will never grow — just call the constructor.
- A Singleton used merely as a global variable to avoid passing parameters (hidden coupling, hard to test).
- A Builder for a 2-field, all-required object — a constructor or record is clearer.
- Wrapping everything in interfaces "for flexibility" you don't need (YAGNI).

Patterns trade simplicity for flexibility; if you don't need the flexibility, you've paid the cost for nothing. The senior instinct is to start simple and *refactor toward* a pattern when a second variation actually appears. Interviewers love candidates who can say "I wouldn't use a pattern here, and here's why."

### Q22. [Theory] What is the Proxy pattern? Name its variants.

A Proxy is a surrogate that controls access to another object, presenting the same interface. The client can't tell it's talking to a proxy. Common variants:

- **Virtual proxy** — lazy-loads an expensive object on first use (e.g., Hibernate lazy-loaded entities).
- **Protection proxy** — enforces access control / security checks.
- **Remote proxy** — represents an object in another address space (RMI stubs).
- **Smart/caching proxy** — adds caching, reference counting, or logging around calls.

```java
interface Image { void display(); }
class RealImage implements Image {
    RealImage(String f) { /* expensive load */ }
    public void display() { /* ... */ }
}
class ImageProxy implements Image {                 // virtual proxy
    private final String file; private RealImage real;
    ImageProxy(String f) { this.file = f; }
    public void display() {
        if (real == null) real = new RealImage(file);  // load on demand
        real.display();
    }
}
```

Proxy is the foundation of Spring AOP (CGLIB/JDK dynamic proxies for `@Transactional`, `@Cacheable`) and Hibernate lazy loading.

---

## 🟡 Intermediate (3–7 yrs)

### Q23. [Theory] Decorator vs Proxy vs Adapter — they all wrap. How do they differ in *intent*?

All three wrap another object behind the same or a compatible interface, so they look structurally similar. Intent is the differentiator:

| Pattern    | Interface vs wrappee | Primary intent                                  |
|------------|----------------------|-------------------------------------------------|
| Adapter    | **Different** → target | Convert an incompatible interface               |
| Decorator  | **Same**, recursively | Add responsibilities, stackable at runtime      |
| Proxy      | **Same**             | Control access (lazy, security, remote, cache)  |

Adapter changes the interface; Decorator adds behavior and is designed to be stacked; Proxy keeps behavior the same but governs *when/whether* the real call happens. A quick tell: if you can wrap multiple times to accumulate features, it's a Decorator; if there's exactly one wrapper that gatekeeps, it's a Proxy; if the method names/shape changed to fit a client, it's an Adapter.

### Q24. [Theory] Abstract Factory vs Factory Method — what's the difference?

**Factory Method** is a single method that creates one product, with subclasses choosing the concrete type. **Abstract Factory** is an object that creates **families of related products** that are meant to be used together, guaranteeing compatibility across the family.

```java
// Abstract Factory: a family of UI widgets per OS
interface GuiFactory { Button button(); Checkbox checkbox(); }

class WinFactory implements GuiFactory {
    public Button button() { return new WinButton(); }
    public Checkbox checkbox() { return new WinCheckbox(); }
}
class MacFactory implements GuiFactory {
    public Button button() { return new MacButton(); }
    public Checkbox checkbox() { return new MacCheckbox(); }
}
```

Pick the factory once (`new WinFactory()`) and every widget you create is consistent (no Mac checkbox in a Windows app). Rule of thumb: Factory Method = one product, varies by subclass; Abstract Factory = a kit of products that vary together. Abstract Factory is often *implemented using* multiple Factory Methods.

### Q25. [Coding] Implement the Chain of Responsibility pattern.

Chain of Responsibility passes a request along a chain of handlers; each decides to handle it or pass it on. It decouples sender from receiver and lets you reorder/insert handlers freely.

```java
abstract class Handler {
    protected Handler next;
    Handler linkWith(Handler next) { this.next = next; return next; }
    abstract boolean handle(Request r);
    protected boolean passToNext(Request r) { return next == null || next.handle(r); }
}

class AuthHandler extends Handler {
    boolean handle(Request r) {
        if (!r.isAuthenticated()) return false;     // stop the chain
        return passToNext(r);
    }
}
class RateLimitHandler extends Handler {
    boolean handle(Request r) {
        if (r.exceedsLimit()) return false;
        return passToNext(r);
    }
}
// Wiring: auth -> rateLimit -> ... ; each link is independent
Handler chain = new AuthHandler();
chain.linkWith(new RateLimitHandler());
```

This is exactly how **Servlet filters**, Spring Security's filter chain, and middleware pipelines work. Each handler has one responsibility (SRP), and you extend behavior by adding links (OCP).

### Q26. [Coding] Implement the State pattern. How does it differ from a big switch?

State lets an object alter its behavior when its internal state changes — it *appears to change class*. Each state is a class encapsulating the behavior and the legal transitions, replacing sprawling conditionals.

```java
interface OrderState { OrderState next(); String status(); }

class Created  implements OrderState { public OrderState next() { return new Paid(); }    public String status() { return "CREATED"; } }
class Paid     implements OrderState { public OrderState next() { return new Shipped(); } public String status() { return "PAID"; } }
class Shipped  implements OrderState { public OrderState next() { return new Shipped(); } public String status() { return "SHIPPED"; } }

class Order {
    private OrderState state = new Created();
    void advance() { state = state.next(); }     // transition logic lives in the states
    String status() { return state.status(); }
}
```

Versus a big `switch (status)`: the switch centralizes *and scatters* transition logic across every method, growing unmaintainable. State distributes behavior into cohesive classes and makes illegal transitions structurally hard. The trade-off is more classes — justified when transitions are complex; overkill for two trivial states (where an `enum` with behavior suffices).

### Q27. [Coding] Implement the Command pattern. What does it enable?

Command encapsulates a request as an object, letting you parameterize, queue, log, and undo operations. The invoker holds commands without knowing the concrete action.

```java
interface Command { void execute(); void undo(); }

class Light { void on() {} void off() {} }

class LightOnCommand implements Command {
    private final Light light;
    LightOnCommand(Light l) { this.light = l; }
    public void execute() { light.on(); }
    public void undo()    { light.off(); }
}

class RemoteControl {                    // invoker
    private final Deque<Command> history = new ArrayDeque<>();
    void press(Command c) { c.execute(); history.push(c); }
    void undoLast() { if (!history.isEmpty()) history.pop().undo(); }
}
```

Command enables **undo/redo** (keep a history stack), **queuing/scheduling** (a job is a Command), **transactional** behavior, and **macros** (a composite of commands). `Runnable` is essentially a parameterless Command; `java.util.concurrent` executors run Command-like tasks.

### Q28. [Theory] What is the Composite pattern?

Composite composes objects into **tree** structures and lets clients treat individual objects (leaves) and compositions (branches) **uniformly** through a common interface.

```java
interface FileNode { long size(); }
record File(String name, long bytes) implements FileNode {
    public long size() { return bytes; }
}
class Directory implements FileNode {
    private final List<FileNode> children = new ArrayList<>();
    void add(FileNode n) { children.add(n); }
    public long size() { return children.stream().mapToLong(FileNode::size).sum(); }  // recurse
}
```

A client calls `size()` without caring whether it's a file or a directory — recursion happens transparently. Use it for any part-whole hierarchy: filesystems, UI component trees, org charts, DOM/XML. The trade-off is that a uniform interface can make leaf-vs-branch-only operations awkward.

### Q29. [Theory] What is the Bridge pattern and when is it useful?

Bridge **decouples an abstraction from its implementation** so the two can vary independently. It prevents the *Cartesian product* class explosion you get when two dimensions of variation are combined via inheritance.

```
            Abstraction (Shape)            Implementation (Renderer)
                  │                                 │
        ┌─────────┴─────────┐            ┌──────────┴──────────┐
     Circle              Square      VectorRenderer        RasterRenderer
        └──── has-a (bridge) ─────────────────┘
```

```java
interface Renderer { void drawCircle(double r); }     // implementation side
class VectorRenderer implements Renderer { public void drawCircle(double r) {} }

abstract class Shape {                                 // abstraction side
    protected final Renderer renderer;                 // the "bridge"
    Shape(Renderer r) { this.renderer = r; }
    abstract void draw();
}
class Circle extends Shape {
    private final double r;
    Circle(Renderer ren, double r) { super(ren); this.r = r; }
    void draw() { renderer.drawCircle(r); }
}
```

Without Bridge you'd need `VectorCircle`, `RasterCircle`, `VectorSquare`, ... (N×M classes). With Bridge you have N + M. JDBC's API/driver split is conceptually a Bridge.

### Q30. [Theory] What is the Flyweight pattern?

Flyweight minimizes memory by **sharing** as much state as possible among many similar objects. It splits state into **intrinsic** (shared, immutable — stored in the flyweight) and **extrinsic** (context-specific — passed in by the client).

```java
// Intrinsic state (glyph shape) is shared; position is extrinsic, passed at draw time
record Glyph(char c, Font font) {}                 // shared, cached
class GlyphFactory {
    private final Map<String, Glyph> cache = new HashMap<>();
    Glyph get(char c, Font f) {
        return cache.computeIfAbsent(c + f.name(), k -> new Glyph(c, f));
    }
}
```

The JVM already does this: `Integer.valueOf` caches −128..127, and `String` literals are interned in a shared pool — both are flyweights. Use it when you have huge numbers of objects that share repeating immutable state (characters in a document, tiles in a game map).

### Q31. [Theory] Strategy vs State — they look identical. What's the real difference?

Both have a context delegating to an interface with interchangeable implementations, so the UML is nearly the same. The difference is **intent and who controls the transition**:

- **Strategy:** the algorithm is chosen by the **client** and usually stays fixed for the operation; strategies are independent and unaware of each other (e.g., a sort comparator).
- **State:** the object transitions between states **itself**, often in response to events, and states *know about each other* (each defines the next state). The context's behavior changes over its lifecycle.

Mnemonic: Strategy = "how do I do this *one* thing" (interchangeable algorithm); State = "what am I *right now*, and what can I become" (lifecycle). If transitions live inside the implementations, it's State; if the implementations are independent and externally selected, it's Strategy.

### Q32. [Practical] How does Spring use design patterns? Name several with where.

Spring is a catalogue of patterns in production:

- **Singleton** — default bean scope (one instance per container).
- **Factory** — `BeanFactory`/`ApplicationContext` create beans; `FactoryBean` is an explicit factory.
- **Proxy** — AOP for `@Transactional`, `@Cacheable`, `@Async` (JDK dynamic proxies or CGLIB).
- **Template Method** — `JdbcTemplate`, `RestTemplate`, `TransactionTemplate` (fixed flow, your callback fills the gap).
- **Strategy** — `Resource` loaders, `PlatformTransactionManager` implementations.
- **Observer** — `ApplicationEvent` + `@EventListener`.
- **Adapter** — `HandlerAdapter` in Spring MVC adapts varied controller styles.
- **Decorator** — `BeanPostProcessor`, transactional/caching wrappers.
- **Front Controller** — `DispatcherServlet`.

Recognizing these helps you read the framework and debug it (e.g., knowing `@Transactional` is a proxy explains why self-invocation bypasses it).

### Q33. [Practical] Why does Spring `@Transactional` fail on self-invocation? Which pattern explains it?

Because `@Transactional` is implemented with the **Proxy** pattern. Spring wraps your bean in a proxy that starts/commits the transaction *around* the call. When an external caller invokes the method, the call goes through the proxy and the advice fires. But an **internal** call (`this.otherMethod()`) goes straight to the raw object, bypassing the proxy entirely — so no transaction starts.

```java
@Service
class BillingService {
    @Transactional public void outer() { inner(); }     // calls this.inner()
    @Transactional public void inner() { /* runs in outer's tx, NOT a new one */ }
}
```

Here `inner()`'s own `@Transactional` (e.g., `REQUIRES_NEW`) is ignored because the call never traverses the proxy. Fixes: split into two beans, self-inject the proxy, use `AopContext.currentProxy()`, or switch to AspectJ load-time weaving (which modifies the bytecode itself, no proxy needed). This is a top-5 Spring interview question.

### Q34. [Theory] What is the Mediator pattern?

Mediator centralizes complex communications between a set of objects so they don't refer to each other directly. Instead of an N×N web of references, each *colleague* talks only to the mediator, which coordinates.

```
Without mediator:  A↔B, A↔C, B↔C, ...   (tightly coupled mesh)
With mediator:     A→M, B→M, C→M         (M coordinates; colleagues decoupled)
```

Example: a chat room where users send to the room (mediator), which relays to others; a dialog where changing one field enables/disables others via a controller. It reduces coupling but the mediator can grow into a God Object if it accumulates too much logic — a real risk to mention. Spring MVC controllers and the `DispatcherServlet` play a mediating role.

### Q35. [Coding] Implement Observer using a Java functional interface.

Modern Java lets the observer be a lambda since the listener interface is single-method.

```java
class EventBus<T> {
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();  // thread-safe
    public AutoCloseable subscribe(Consumer<T> l) {
        listeners.add(l);
        return () -> listeners.remove(l);          // return an unsubscribe handle
    }
    public void publish(T event) { listeners.forEach(l -> l.accept(event)); }
}

// Usage:
EventBus<String> bus = new EventBus<>();
var sub = bus.subscribe(e -> System.out.println("Got: " + e));   // lambda observer
bus.publish("order-created");
sub.close();                                       // unsubscribe to avoid leaks
```

Two production-grade touches worth calling out: `CopyOnWriteArrayList` so publishing while listeners mutate is safe, and returning an **unsubscribe handle** so listeners don't leak (a real bug in long-lived observers). For full backpressure/reactive semantics you'd use `java.util.concurrent.Flow` or Reactor.

### Q36. [Practical] What is the Null Object pattern and when does it help?

Null Object provides a do-nothing object that implements the expected interface, so callers don't need `null` checks. It replaces scattered `if (x != null)` with polymorphism.

```java
interface Logger { void log(String msg); }
class ConsoleLogger implements Logger { public void log(String m) { System.out.println(m); } }
class NullLogger    implements Logger { public void log(String m) { /* intentionally nothing */ } }

class Service {
    private final Logger logger;
    Service(Logger logger) { this.logger = (logger != null) ? logger : new NullLogger(); }
    void run() { logger.log("running"); }   // never null-checks
}
```

It improves readability and removes a class of NPEs. Caveat: don't hide *errors* with it — a Null Object that silently swallows a missing required dependency can mask bugs. In Java, `Optional`, `Collections.emptyList()`, and `Stream.empty()` are null-object-flavored.

### Q37. [Theory] What is a God Object / God Class anti-pattern and how do you fix it?

A God Object is a class that knows or does too much — it centralizes a huge amount of state and behavior, violating SRP. Symptoms: thousands of lines, dozens of fields, many unrelated methods, and *everything* depends on it. It's hard to test, change, and reason about; a small edit risks breaking unrelated features.

Fixes:
- **Extract classes** by responsibility (SRP) — pull cohesive groups of fields+methods into their own types.
- Apply **ISP** to split fat interfaces it implements.
- Introduce **Facade** only at a genuine boundary, not as another dumping ground.
- Use **composition** to delegate concerns (e.g., a `OrderService` delegating to `PricingCalculator`, `InventoryChecker`, `PaymentGateway`).

The root cause is usually accreted features with no refactoring discipline. The interview signal: you recognize it, and you fix it by *decomposing*, not by adding a manager-of-managers.

### Q38. [Theory] Singleton is called an anti-pattern by some. Why, and what are alternatives?

Singleton is widely criticized because:

- It's **global mutable state** in disguise — hidden, hard-to-trace coupling. Any code can reach `Foo.getInstance()`, so dependencies aren't visible in signatures.
- It **hurts testability** — you can't easily substitute a mock; tests share state and leak across each other.
- It often **violates SRP** (manages its own lifecycle *and* business logic) and DIP (callers depend on a concrete class).
- It causes problems with concurrency and class loaders.

Alternative: **dependency injection** with a single-instance scope. Let the DI container guarantee one instance (Spring's default singleton scope) and *inject* it as an interface. You keep "one instance" while gaining testability, explicit dependencies, and the ability to swap implementations. The rule: you rarely need the *pattern*; you usually want *single instance lifecycle*, which DI provides cleanly.

### Q39. [Practical] How do constructor, setter, and field injection differ, and which is best?

```java
// Constructor (recommended): final fields, immutable, fails fast, testable without container
class A { private final B b; A(B b) { this.b = b; } }

// Setter: good for OPTIONAL or reconfigurable dependencies
class A { private B b; void setB(B b) { this.b = b; } }

// Field: convenient but discouraged
class A { @Autowired private B b; }   // can't be final, hides deps, needs reflection to test
```

**Constructor injection** is best for mandatory dependencies: fields are `final` (immutable, thread-safe publication), the object is always fully constructed, missing dependencies fail at startup, and you can `new A(mockB)` in a plain unit test. **Setter** suits genuinely optional dependencies. **Field injection** is convenient but discouraged — it hides the dependency count (encouraging God classes), can't use `final`, couples you to the container, and needs reflection to test. Spring and IDEs both warn against field injection.

### Q40. [Theory] What is the difference between the Template Method and Strategy for varying behavior?

Both let behavior vary, but through opposite mechanisms:

- **Template Method** uses **inheritance**: a base class fixes the algorithm skeleton and subclasses override *steps*. The variation points are hooks; the overall structure is locked. Compile-time binding.
- **Strategy** uses **composition**: the context holds a reference to a strategy object and delegates the *whole* algorithm to it. Swappable at runtime.

Prefer Strategy when you want runtime flexibility, want to avoid a deep inheritance hierarchy, or the varying behavior is a cross-cutting algorithm (it composes better and avoids the fragile base-class problem). Prefer Template Method when there's a genuinely fixed sequence with a few customizable steps and inheritance is natural (e.g., a framework lifecycle hook). Modern code leans toward Strategy + lambdas to favor composition over inheritance.

### Q41. [Coding] Implement a generic Builder for an immutable object with validation.

```java
public final class User {
    private final String email;        // required
    private final String name;         // required
    private final int age;             // optional, defaulted
    private final boolean active;

    private User(Builder b) {
        this.email = b.email; this.name = b.name; this.age = b.age; this.active = b.active;
    }
    public static Builder builder(String email, String name) { return new Builder(email, name); }

    public static final class Builder {
        private final String email, name;
        private int age = 0;
        private boolean active = true;
        private Builder(String email, String name) { this.email = email; this.name = name; }
        public Builder age(int age) { this.age = age; return this; }
        public Builder active(boolean a) { this.active = a; return this; }
        public User build() {
            if (email == null || !email.contains("@")) throw new IllegalArgumentException("bad email");
            if (age < 0) throw new IllegalArgumentException("age < 0");
            return new User(this);     // invariants validated before construction
        }
    }
}
```

Key points: required fields are constructor args of the builder (you can't forget them), optionals are fluent setters with sensible defaults, the product is immutable (`final` fields, no setters), and `build()` is the single validation gate so an invalid `User` can never exist.

### Q42. [Theory] What does "favor composition over inheritance" mean?

It means prefer assembling behavior by *holding* other objects (has-a) over deriving from a base class (is-a). Inheritance is powerful but has downsides: it's the tightest coupling in OO (subclass depends on the base's implementation details — the *fragile base class* problem), it's static (fixed at compile time), it can violate LSP, and Java allows only single inheritance.

Composition (often via Strategy, Decorator, Bridge) is more flexible: you can swap collaborators at runtime, combine behaviors freely, and the relationship is through a stable interface rather than implementation. *Effective Java* Item 18 ("Favor composition over inheritance") gives the canonical argument — a `ForwardingList` decorator instead of extending `ArrayList`. Use inheritance only for genuine *is-a* with a stable contract designed for extension; otherwise compose.

### Q43. [Practical] What is a `FactoryBean` in Spring and how does it relate to the Factory pattern?

A `FactoryBean<T>` is a Spring interface for beans that themselves act as factories producing other beans. When the container encounters a `FactoryBean`, it doesn't expose the factory itself by default — it calls `getObject()` and exposes the *produced* object under that bean name.

```java
class ConnectionFactoryBean implements FactoryBean<Connection> {
    public Connection getObject() { /* complex creation logic */ return openConnection(); }
    public Class<?> getObjectType() { return Connection.class; }
    public boolean isSingleton() { return true; }
}
```

It's the Factory pattern formalized into the bean lifecycle, used when a bean's construction is too complex for a constructor or `@Bean` method (e.g., `SqlSessionFactoryBean` in MyBatis, `LocalContainerEntityManagerFactoryBean` in JPA). To get the factory itself rather than its product, prefix the name with `&` (`&myFactoryBean`).

### Q44. [Theory] How do design patterns relate to SOLID? Give concrete mappings.

Patterns are largely *recipes* for honoring SOLID:

- **Strategy / Bridge** → OCP & DIP: add behavior via new classes, depend on an abstraction.
- **Decorator** → OCP & SRP: extend behavior without modifying the class; each decorator one concern.
- **Factory / Abstract Factory** → DIP: clients depend on product interfaces, not concrete classes.
- **Adapter** → DIP & OCP: integrate new implementations behind an expected interface.
- **Template Method** → OCP: vary steps without editing the skeleton.
- **Observer / Mediator** → low coupling (supports SRP by separating concerns).
- **Composite / Iterator** → LSP-friendly uniform treatment of a hierarchy.

The takeaway: don't memorize patterns in isolation; understand which SOLID pressure each one relieves, and you can derive the right pattern from the smell.

---

## 🟠 Advanced (8–12 yrs)

### Q45. [Theory] When does the Visitor pattern earn its complexity, and what's its big drawback?

Visitor lets you add new *operations* to a stable object structure without modifying the element classes — it externalizes the operation into a visitor and uses **double dispatch** (`element.accept(visitor)` then `visitor.visit(this)`) to pick the right method based on both the element and the visitor types.

```java
interface Node { <R> R accept(Visitor<R> v); }
record NumberNode(double v) implements Node { public <R> R accept(Visitor<R> vis) { return vis.visit(this); } }
record AddNode(Node l, Node r) implements Node { public <R> R accept(Visitor<R> vis) { return vis.visit(this); } }

interface Visitor<R> { R visit(NumberNode n); R visit(AddNode n); }
class EvalVisitor implements Visitor<Double> {
    public Double visit(NumberNode n) { return n.v(); }
    public Double visit(AddNode n)    { return n.l().accept(this) + n.r().accept(this); }
}
```

It earns its keep for **stable structures with many operations** (AST evaluation, type checking, pretty-printing, code generation in compilers). The big drawback is the **expression problem**: adding a new *element* type forces editing every visitor — Visitor optimizes for adding operations at the cost of adding types. In modern Java, **sealed interfaces + pattern-matching `switch`** often replace Visitor entirely, giving exhaustiveness checks without the double-dispatch boilerplate — a strong point to raise.

### Q46. [Coding] Replace the Visitor pattern with sealed types and pattern matching. Show both.

Java 21's sealed hierarchies plus pattern-matching `switch` provide compiler-checked exhaustiveness, so you can add operations as plain functions and add types safely (the compiler flags every switch that must change).

```java
sealed interface Expr permits Num, Add, Mul {}
record Num(double v) implements Expr {}
record Add(Expr l, Expr r) implements Expr {}
record Mul(Expr l, Expr r) implements Expr {}

// New operation = a new method, no accept()/visitor boilerplate:
static double eval(Expr e) {
    return switch (e) {                       // exhaustive: compiler errors if a case is missing
        case Num n -> n.v();
        case Add a -> eval(a.l()) + eval(a.r());
        case Mul m -> eval(m.l()) * eval(m.r());
    };
}
```

Compared to classic Visitor (Q45), this is less code, type-safe, and the exhaustiveness check catches forgotten cases at compile time. The trade-off flips: adding a new `Expr` type now forces every `switch` to be updated — but the compiler *tells you where*, which is exactly the safety Visitor lacked. Use sealed+switch by default in modern Java; reach for classic Visitor only when you can't control the element types or need runtime-pluggable visitors.

### Q47. [Theory] How do JDK dynamic proxies differ from CGLIB, and why does Spring choose between them?

Both implement the Proxy pattern for AOP, but differently:

- **JDK dynamic proxies** (`java.lang.reflect.Proxy`) generate a proxy class at runtime that implements one or more **interfaces**. The target must implement an interface; the proxy can only intercept interface-declared methods. Pure JDK, no extra dependency.
- **CGLIB** generates a **subclass** of the target at runtime (bytecode manipulation), so it works on classes without interfaces. But it can't proxy `final` classes or `final`/`private` methods (it overrides to intercept), and historically needed a default constructor.

Spring's rule: if the target implements interfaces it uses JDK proxies by default; otherwise CGLIB. You can force CGLIB with `proxyTargetClass=true` (Spring Boot defaults to this for `@Configuration` and often AOP since 2.x). The practical consequences candidates must know: a `@Transactional`/`@Cacheable` method must be `public` and non-`final`, and injecting a concrete class works under CGLIB but injecting against the interface is cleaner under JDK proxies.

### Q48. [Theory] What is the Service Locator pattern and why is DI usually preferred?

Service Locator is a registry you ask for dependencies at the point of use: `var repo = ServiceLocator.get(OrderRepository.class)`. It centralizes lookup but the consuming class still *pulls* its dependencies.

```java
class OrderService {
    private final OrderRepository repo = ServiceLocator.get(OrderRepository.class);  // pull
}
```

Compared with DI (dependencies *pushed* in via constructor), Service Locator hides dependencies: they don't appear in the constructor signature, so you can't tell what a class needs without reading its body, and tests must configure the global locator. It also couples every class to the locator (a DIP/SRP smell). DI is preferred because dependencies are *explicit and injected*, making code self-documenting and trivially testable. Service Locator still has niches — plugin systems, places where you genuinely need dynamic, late-bound lookup, or legacy code without a container — but it's a fallback, not a default.

### Q49. [Coding] Implement a thread-safe object pool (a creational-flavored pattern). Note the trade-offs.

Object Pool reuses expensive-to-create objects (DB connections, threads) instead of creating/destroying them per use.

```java
class ObjectPool<T> {
    private final BlockingQueue<T> pool;
    private final Supplier<T> factory;

    ObjectPool(int size, Supplier<T> factory) {
        this.factory = factory;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) pool.offer(factory.get());
    }
    T borrow() throws InterruptedException { return pool.take(); }   // blocks if empty
    void release(T obj) { pool.offer(obj); }                         // return to pool
}
```

Trade-offs interviewers probe: pooling helps only when creation cost ≫ pooling overhead (true for connections/threads, usually *false* for plain objects — pooling ordinary objects fights the JVM's fast allocation and good GC and is a known anti-pattern). You must reset borrowed objects' state to avoid leakage between users, handle starvation/timeouts, and validate stale entries. In practice use a battle-tested pool (HikariCP for JDBC, the JDK's `ThreadPoolExecutor`) rather than hand-rolling. Mentioning "don't pool cheap objects" signals real experience.

### Q50. [Behavioral] Tell me about a time you removed a design pattern or refactored an over-engineered abstraction. How did you decide?

Strong answers follow a situation→reasoning→outcome arc. Example shape: *"We had an `AbstractFactory` + `Strategy` layering producing a single implementation each — three interfaces and five classes for behavior that never varied. New engineers spent a day tracing a one-line operation. I confirmed via git history and product roadmap that no second variant was planned, then collapsed it to a single concrete class behind one small interface kept only for testing. I did it incrementally behind tests, deleting one layer per PR so reviews stayed small and reversible."*

What interviewers listen for: you treat patterns as *trade-offs*, not virtues; you gathered evidence (usage, roadmap, tests) before deleting; you reduced indirection that wasn't paying for itself (YAGNI); and you did it safely (tests, incremental PRs). The anti-signal is dogmatism in either direction — "patterns are always good" or "abstractions are always bad." The senior view: add abstraction when a *second* real case arrives, and remove it when the case that justified it is gone.

### Q51. [Theory] What is the "expression problem" and how do different patterns trade off on it?

The expression problem is the difficulty of extending a system along *two* axes — adding new **types** (data variants) and new **operations** — without modifying existing code and while keeping type safety.

```
                Add new TYPE easily       Add new OPERATION easily
OO / Visitor    Visitor: ❌ (edit all)    Visitor: ✅ (new visitor)
                Subclass: ✅              Subclass: ❌ (edit each class)
sealed+switch   ❌ (compiler-guided)      ✅ (new function)
```

Classic OO polymorphism makes adding *types* easy (new subclass) but adding *operations* hard (touch every class). Visitor flips it: easy to add operations, hard to add types. Sealed interfaces + pattern matching behave like Visitor (easy operations) but give compiler-guided safety when you do add a type. No single approach wins both axes in mainstream languages without extra machinery (type classes, multimethods). The senior insight: choose based on which axis *actually changes more* in your domain — if types are stable and operations grow (compilers, ASTs), Visitor/sealed-switch; if operations are stable and types grow, plain polymorphism.

### Q52. [Theory] How does the Decorator pattern enable cross-cutting concerns, and how is it different from AOP?

Decorator wraps an object to add a concern (logging, caching, retry, metrics) while preserving the interface, so callers are unaffected and decorators **stack**:

```java
Repository repo = new MetricsRepo(new CachingRepo(new RetryingRepo(new JdbcRepo())));
```

This is explicit, type-safe, and visible at the wiring site — you can see exactly what's applied and in what order. **AOP** achieves the same cross-cutting goal but *declaratively and implicitly*: an aspect with a pointcut weaves advice into many targets at once (`@Around` on every `@Repository` method) without listing them. The trade-off: Decorator is explicit and local (easy to trace, verbose to apply broadly); AOP is concise and broad (one aspect covers hundreds of methods, but the behavior is "invisible" at the call site and can surprise people). Spring AOP is literally implemented *with* dynamic proxies (a Proxy/Decorator mechanism). Choose Decorator for a handful of explicit wrappings; AOP when the concern is genuinely cross-cutting across many types.

### Q53. [Coding] Implement an undo/redo system. Which patterns combine?

Undo/redo combines **Command** (each action is a reversible object) with **Memento** (snapshot state when reversal can't be computed) and two stacks.

```java
interface Command { void execute(); void undo(); }

class Editor {
    private final StringBuilder text = new StringBuilder();
    private final Deque<Command> undo = new ArrayDeque<>();
    private final Deque<Command> redo = new ArrayDeque<>();

    void run(Command c) { c.execute(); undo.push(c); redo.clear(); }   // new action clears redo
    void undo() { if (!undo.isEmpty()) { var c = undo.pop(); c.undo(); redo.push(c); } }
    void redo() { if (!redo.isEmpty()) { var c = redo.pop(); c.execute(); undo.push(c); } }

    class InsertText implements Command {                 // a concrete command
        private final String s; private final int pos;
        InsertText(String s, int pos) { this.s = s; this.pos = pos; }
        public void execute() { text.insert(pos, s); }
        public void undo()    { text.delete(pos, pos + s.length()); }
    }
}
```

Command works when each action is *invertible* programmatically. When it isn't (a complex transform), use **Memento**: before executing, capture a snapshot of the affected state and restore it on undo. Real editors mix both — invertible commands for cheap operations, mementos for expensive/irreversible ones — and cap history size to bound memory.

### Q54. [Theory] What patterns underpin reactive/streaming APIs like `java.util.concurrent.Flow` and Reactor?

Reactive streams compose several classic patterns:

- **Observer** — `Publisher`/`Subscriber` is Observer with backpressure added (the subscriber requests N items via `Subscription.request(n)`, solving the fast-producer/slow-consumer problem the naive Observer ignores).
- **Iterator (pull) vs Observer (push)** — reactive streams are a *hybrid*: push, but flow-controlled by pull-style demand.
- **Decorator** — operators (`map`, `filter`, `buffer`) wrap a publisher to add behavior, stacking just like `java.io` streams.
- **Builder/Factory** — `Flux.just(...)`, `Mono.from(...)` are factory methods; pipelines are assembled fluently.
- **Template Method / Strategy** — schedulers and operators parameterize *where* and *how* work runs.

The key evolution over plain Observer is **backpressure**: `Flow` (JEP-266, Java 9, the Reactive Streams spec in the JDK) formalizes demand signaling so an overwhelmed consumer can throttle the producer. Knowing reactive streams = "Observer + backpressure + Decorator operators" is the kind of synthesis senior interviews reward.

### Q55. [Theory] How do you choose between inheritance-based and composition-based patterns at scale?

At scale the decision is governed by *change frequency* and *coupling cost*:

- Use **composition** (Strategy, Decorator, Bridge, DI) when behavior varies independently, must be swappable at runtime, or combines in many ways — composition avoids the combinatorial subclass explosion and the fragile base-class problem, and keeps coupling at a stable interface.
- Use **inheritance** (Template Method) only for a genuine, stable *is-a* relationship with a small, well-defined set of override points, where the base class is explicitly designed and documented for extension.

The scaling failure mode of inheritance is deep, wide hierarchies where a base-class change ripples unpredictably and LSP gets violated. The scaling failure mode of composition is "too many tiny objects" and indirection that obscures flow. The senior rule of thumb: model the *vocabulary* with interfaces, assemble *behavior* with composition, and reserve inheritance for framework lifecycle hooks. *Effective Java* Item 18/19 (favor composition; design and document for inheritance or prohibit it) is the canonical reference.

### Q56. [Practical] How would you design a plugin/extension system using patterns?

A plugin system typically layers several patterns:

- **Strategy/Abstract Factory** — each plugin implements a known interface (the extension point); the host depends only on that abstraction (DIP).
- **Service Locator / ServiceLoader** — Java's `java.util.ServiceLoader` discovers implementations via `META-INF/services`, decoupling the host from concrete plugin classes.
- **Factory** — the host instantiates discovered plugins.
- **Observer** — plugins subscribe to host lifecycle events.
- **Decorator/Chain of Responsibility** — plugins wrap or chain to extend a pipeline.

```java
// Discovery via ServiceLoader (no compile-time dependency on impls):
ServiceLoader<PaymentPlugin> plugins = ServiceLoader.load(PaymentPlugin.class);
for (PaymentPlugin p : plugins) registry.register(p.id(), p);
```

The design tenets: a narrow, versioned extension-point interface (ISP), discovery that doesn't hard-code implementations, and isolation (classloaders/sandboxing) so a bad plugin can't crash the host. Spring Boot's auto-configuration and the JDBC `DriverManager` are real systems built this way.

---

## 🔴 Expert (15+ yrs)

### Q57. [Theory] At the deepest level, what single idea unifies Strategy, State, Command, and Template Method?

They are all answers to one question: **how do you make behavior a first-class, varying thing while keeping the surrounding code stable?** Each parameterizes behavior, differing only in *what varies* and *how it's bound*:

- **Strategy** — vary *which algorithm*, bound by the client via composition; strategies are peers.
- **State** — vary *which algorithm*, but the object rebinds *itself* over its lifecycle; states know their successors.
- **Command** — *reify* the invocation itself into an object so it can be stored, queued, logged, undone.
- **Template Method** — fix the algorithm *shape*, vary the *steps*, bound by inheritance.

The unifying abstraction is "encapsulate the part that changes." In a language with first-class functions, three of these collapse toward "pass a function": a Strategy is a lambda, a Command is a captured closure, a State transition is a function returning the next function. Template Method is the outlier because it binds via inheritance rather than a value. The expert framing: GoF behavioral patterns are largely *workarounds for the absence of first-class functions* in 1994 C++/Java — which is why modern functional features make many of them nearly invisible, while the *intent* (isolate what varies) remains permanent.

### Q58. [Theory] Critique the GoF catalogue from a 2026 perspective. Which patterns are obsolete, which endure?

A nuanced expert critique:

- **Largely absorbed by the language/JDK:** Iterator (built into `Iterable`/for-each), Singleton (replaced by DI scopes; the pattern is mostly an anti-pattern now), legacy Observer (deprecated; superseded by reactive streams/`Flow`), Command/Strategy (often just lambdas/method references), Prototype (copy constructors > `Cloneable`).
- **Reshaped by modern features:** Visitor → sealed interfaces + pattern-matching `switch`; some Factory uses → static factory methods and records.
- **Enduring and load-bearing:** Decorator (still the cleanest stackable cross-cutting tool, all over `java.io`/reactive operators), Adapter (eternal — integration never stops), Facade (API boundaries), Proxy (the engine of AOP/lazy loading/remoting), Builder (immutable objects with many fields), Composite (trees), Strategy/Observer as *concepts* even when realized as functions.

The meta-critique: GoF over-emphasized class-based, single-dispatch, no-first-class-function design typical of mid-90s C++. The *vocabulary* endures and is invaluable for communication; many *implementations* are now one-liners. The expert position: teach patterns as a shared language and a catalogue of forces/trade-offs, not as code to transcribe — and always ask whether a language feature already solves it.

### Q59. [Theory] How do patterns map onto Domain-Driven Design and hexagonal architecture?

At an architectural scale, GoF patterns become tactical tools inside larger structural patterns:

- **Hexagonal / Ports & Adapters** is the Adapter pattern elevated to architecture: the domain defines *ports* (interfaces), and infrastructure provides *adapters* (DB, REST, messaging). DIP is the governing principle — the domain depends on nothing outward.
- **Repository** (a DDD building block) is a Facade + Strategy over persistence, hiding the data store behind a domain-shaped interface.
- **Factory** in DDD constructs aggregates enforcing invariants (a richer Factory/Builder).
- **Domain events** are Observer at the bounded-context boundary.
- **Specification** is Strategy for composable business rules.
- **Strategy/Policy** encapsulates varying domain policies; **State** models aggregate lifecycles.

The synthesis interviewers want: GoF patterns are the *grammar*; DDD/hexagonal are the *architecture* that arranges them so the domain stays pure and infrastructure stays replaceable. Dependency Inversion is the thread connecting all of it — ports/adapters, repositories, and DI containers all exist to keep high-level policy independent of low-level detail.

### Q60. [Behavioral] How do you introduce design-pattern discipline on a team without turning it into cargo-culting?

The risk is two failure modes: under-design (spaghetti) and over-design (pattern soup). A mature approach balances them:

- **Teach the forces, not the templates.** Frame patterns by the *problem and trade-off* ("we need to vary X at runtime without editing Y") so people reach for them by intent, not by name-matching.
- **Make refactoring the default path.** Encourage starting simple and refactoring *toward* a pattern when a second real case appears (rule of three), rather than speculative abstraction. Codify YAGNI.
- **Use a shared vocabulary in reviews and design docs** so "let's use a Strategy here" is a precise, quick communication — that's the genuine payoff of the catalogue.
- **Guard against cargo-culting in code review:** ask "what change does this abstraction make cheaper, and is that change actually expected?" An abstraction with one implementation and no roadmap is a red flag.
- **Lead by example and pairing, not mandates.** A style guide that says "always use a factory" produces cargo-culting; mentoring on trade-offs produces judgment.

The signal of seniority: you optimize for *judgment and shared language*, treat patterns as trade-offs to be justified, and you're equally willing to add or remove abstraction based on evidence about how the system actually changes.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q61. [Theory] Why is the lazy-holder (initialization-on-demand holder) Singleton idiom thread-safe without any synchronized keyword?

Because it leans entirely on the **JVM class-initialization contract** (JLS §12.4). The inner holder class `H` is not loaded or initialized when the outer class loads — class initialization is *lazy* and happens only on first *active use* (here, the first read of `H.INSTANCE`). The JVM guarantees that class initialization is performed **exactly once** and is **synchronized internally**: the very first thread to trigger initialization acquires an initialization lock on the `Class` object, runs the static initializer, and any other thread that arrives concurrently *blocks* on that lock until initialization completes, then sees the fully published result.

```java
class Holder {
    private Holder() {}
    private static class H { static final Holder INSTANCE = new Holder(); }  // initialized on first getInstance()
    public static Holder getInstance() { return H.INSTANCE; }
}
```

So you get laziness (the holder loads only when needed) *and* thread safety (the classloader serializes initialization) *and* visibility (initialization establishes a happens-before edge to every thread that subsequently reads the field) — all for free, with zero runtime locking on the hot path after the first call. This is strictly better than double-checked locking because there's no `volatile` read on every access and nothing to get subtly wrong.

#### Q62. [Theory] What exactly does the `final` keyword on a Template Method's skeleton method buy you, in terms of LSP?

Marking the template method `final` *prevents subclasses from overriding the invariant* — the fixed order of steps that defines the algorithm. This is an LSP safeguard: the base class publishes a contract ("read, then validate, then save, in that order"), and `final` makes it structurally impossible for a subclass to silently break that postcondition by reordering or skipping steps. Subclasses can only fill in the *designated* extension points (the abstract/hook methods), so a `DataImporter` subclass can never produce a sequence that violates what callers of `importData()` rely on.

```java
abstract class DataImporter {
    public final void importData() { var r = read(); var c = validate(r); save(c); }  // order is law
    protected abstract String read();
    protected abstract String validate(String r);
    protected abstract void save(String r);
}
```

Without `final`, a subclass could override `importData()` and, say, skip `validate()`, breaking every client's expectation — a classic LSP violation that's invisible at the call site. `final` turns "please don't override this" from a comment into a compiler-enforced invariant.

#### Q63. [Theory] How does `Integer.valueOf` implement Flyweight, and what's the cache range and the famous gotcha?

`Integer.valueOf(int)` returns *shared, cached* `Integer` instances for a small range instead of allocating a new object each time — the textbook Flyweight, where the intrinsic state (the boxed value) is shared. The cache covers **−128 to 127** by default (the high bound is tunable via `-XX:AutoBoxCacheMax` or `-Djava.lang.Integer.IntegerCache.high`). Autoboxing routes through `valueOf`, so boxed ints in that range are interned.

```java
Integer a = 127, b = 127;     // both from the cache → same object
Integer c = 128, d = 128;     // outside cache → two new objects
System.out.println(a == b);   // true  (reference equality, cached)
System.out.println(c == d);   // false (different instances!)
System.out.println(c.equals(d)); // true (always use equals for value comparison)
```

The gotcha is the `==` trap: comparing boxed `Integer`s with `==` accidentally "works" for small values (same cached reference) and silently breaks above 127. The lesson — never use `==` on boxed primitives; use `.equals()` or unbox. `Long`, `Short`, `Byte`, and `Character` have analogous caches; `Boolean` caches both values; `Float`/`Double` do not cache.

#### Q64. [Practical] When you wrap a stream in multiple `java.io` decorators, in what order are bytes processed and why does decorator order matter?

Data flows **outermost-in on the way you call, innermost-out on the way bytes actually move**. When you write through `new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f)))`, your bytes hit the `BufferedOutputStream` first (it accumulates them), then the buffer flushes into `GZIPOutputStream` (which compresses), whose output lands in `FileOutputStream` (which hits disk). Each decorator presents the same `OutputStream` interface but transforms the bytes as they pass through.

```java
// Order changes correctness AND performance:
try (var out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) { ... }
// vs
try (var out = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) { ... }
```

Order matters for two reasons. **Correctness/semantics:** wrapping `Buffered` *outside* `GZIP` buffers raw bytes before compression; wrapping it *inside* (between GZIP and file) buffers the already-compressed bytes before disk. **Performance:** you want buffering to reduce the number of syscalls to the slow device, so `BufferedOutputStream` closest to `FileOutputStream` minimizes disk writes. The decorator chain is composable precisely because each layer only knows about the `OutputStream` it wraps, not the whole stack.

#### Q65. [Theory] In the classic Strategy class form versus a lambda, what actually gets created at the bytecode level, and does that matter?

A named strategy *class* compiles to a real class file; instantiating it allocates an object. A **lambda** does *not* compile to an anonymous-class file the way pre-Java-8 anonymous classes did — `javac` emits an `invokedynamic` instruction bootstrapped by `LambdaMetafactory`, and the actual implementation class is **spun up at runtime** on first execution, then linked and cached.

```java
DiscountStrategy s1 = new TenPercentOff();   // ordinary object of a compiled class
DiscountStrategy s2 = p -> p * 0.9;          // invokedynamic → LambdaMetafactory at runtime
```

Why it matters in interviews: (1) a **stateless/non-capturing** lambda is effectively a singleton — the JVM can reuse one instance across all evaluations, so there's no per-use allocation, making lambda-as-Strategy essentially free. (2) A **capturing** lambda (closing over local variables) may allocate a small object to hold the captured state. (3) The `invokedynamic` approach means lambdas don't bloat the jar with a class-per-lambda and let the runtime choose the most efficient representation. The practical upshot: "lambdas made Strategy nearly free" is literally true for non-capturing strategies.

#### Q66. [Practical] Why is `CopyOnWriteArrayList` the right backing store for an Observer registry, and when is it the wrong choice?

It's right when **reads (notifications) vastly outnumber writes (subscribe/unsubscribe)** and you iterate the listener list while it may be concurrently mutated. `CopyOnWriteArrayList` snapshots the array on every mutation, so iteration uses a stable, immutable snapshot and never throws `ConcurrentModificationException` — exactly the situation where a listener's callback subscribes or unsubscribes *during* a `publish()` walk.

```java
private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
public void publish(T e) { listeners.forEach(l -> l.accept(e)); }  // safe even if a callback unsubscribes
```

It's the **wrong** choice when subscriptions churn frequently or the listener set is large, because every `add`/`remove` copies the entire underlying array — O(n) per mutation and a burst of garbage. In a high-churn scenario (e.g., thousands of short-lived subscriptions per second), a `ConcurrentHashMap`-backed set or an explicit lock with a normal list is better. The rule: COW for read-mostly observer lists; reconsider for write-heavy ones.

#### Q67. [Theory] The for-each loop "is" the Iterator pattern — what bytecode does the compiler actually generate for it?

For an `Iterable`, the enhanced for-loop is pure syntactic sugar that `javac` desugars into an explicit `Iterator` call sequence — there is no special bytecode for for-each itself.

```java
for (String s : items) { use(s); }
// desugars to:
for (Iterator<String> it = items.iterator(); it.hasNext(); ) {
    String s = it.next();
    use(s);
}
```

So the compiler emits `iterator()`, then a loop calling `hasNext()` and `next()`. (For arrays it desugars differently — to an index-counted loop, no Iterator object, since arrays aren't `Iterable`.) Consequences worth knowing: you can't call `it.remove()` from a for-each because the iterator is hidden, which is *why* mutating a collection during for-each throws `ConcurrentModificationException` (the iterator's `modCount` check fires) — you must use an explicit `Iterator` and its `remove()` to delete safely. The pattern is so fundamental the language built it into the grammar.

### 🟡 — extended

#### Q68. [Theory] Explain the fail-fast iterator mechanism (`modCount`) and how it relates to the Iterator pattern's contract.

Java's `Iterator` implementations for the standard mutable collections are **fail-fast**: they detect *structural* modification of the collection during iteration and throw `ConcurrentModificationException` rather than risk returning corrupt or undefined results. The mechanism is a counter, `modCount`, on the collection, incremented on every structural change (add/remove that changes size). When you create an iterator it snapshots that value into `expectedModCount`; on each `next()`/`remove()` it calls `checkForComodification()` comparing the two.

```java
final void checkForComodification() {       // ArrayList.Itr, paraphrased
    if (modCount != expectedModCount) throw new ConcurrentModificationException();
}
```

Key nuances: (1) it's **best-effort**, not a guarantee — `modCount` isn't volatile, so it's documented as a debugging aid, not a correctness contract for concurrency. (2) It fires even single-threaded if you mutate the collection directly (not via the iterator) mid-loop. (3) The iterator's *own* `remove()` updates `expectedModCount`, so it's the only safe way to delete during iteration. (4) Concurrent collections (`CopyOnWriteArrayList`, `ConcurrentHashMap`) instead offer **weakly consistent** iterators that never throw but may or may not reflect concurrent updates. This is the Iterator pattern's "traverse without exposing internals" contract, hardened against misuse.

#### Q69. [Theory] How does `java.lang.reflect.Proxy` actually create a JDK dynamic proxy at runtime, and what are the constraints this imposes?

`Proxy.newProxyInstance(classLoader, interfaces, invocationHandler)` **generates a brand-new class at runtime** (named like `$Proxy0`) that implements the supplied interfaces. For every interface method, the generated class's implementation simply packages the method and args and calls `handler.invoke(proxy, method, args)`. The bytecode is synthesized in memory by the JVM's proxy generator and loaded by the given classloader.

```java
PaymentProcessor p = (PaymentProcessor) Proxy.newProxyInstance(
    cl, new Class<?>[]{ PaymentProcessor.class },
    (proxy, method, args) -> { /* advice */ return method.invoke(realTarget, args); });
```

Constraints this design forces: (1) **interface-only** — the generated `$Proxy0` already extends `java.lang.reflect.Proxy`, and Java has single inheritance, so it can implement interfaces but cannot subclass your concrete class (that's why Spring falls back to CGLIB for class-based proxying). (2) Only **interface-declared** methods are intercepted; a method that exists only on the concrete class is invisible to the proxy. (3) `equals`/`hashCode`/`toString` are routed through `invoke` too, so the handler must handle them sensibly. (4) Every call pays a reflective `Method.invoke` cost unless cached/optimized. Understanding this explains both AOP's interface bias and why `@Transactional` only works on interface (or public) methods.

#### Q70. [Coding] Implement a generic, reflective decorator using a JDK dynamic proxy that logs every call. What's the catch?

```java
import java.lang.reflect.*;

class LoggingProxy {
    @SuppressWarnings("unchecked")
    static <T> T wrap(T target, Class<T> iface) {
        return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{ iface },
            (proxy, method, args) -> {
                long t0 = System.nanoTime();
                try {
                    Object result = method.invoke(target, args);   // delegate to real object
                    System.out.printf("%s took %d ns%n", method.getName(), System.nanoTime() - t0);
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();   // unwrap so callers see the real exception
                }
            });
    }
}
// Usage:
PaymentProcessor p = LoggingProxy.wrap(new RealProcessor(), PaymentProcessor.class);
```

The catch (and the interview gold): (1) it works **only against an interface** — JDK proxies can't wrap a concrete class with no interface. (2) You must **unwrap `InvocationTargetException`** (via `getCause()`), otherwise the real exception gets buried inside a reflection wrapper and breaks caller exception handling. (3) **Self-invocation is invisible** — if `target` calls its own methods internally, those calls don't go through the proxy (the same reason Spring `@Transactional` self-invocation fails). (4) Reflective `invoke` is slower than a direct call, though the JIT optimizes hot paths. This is essentially how Spring AOP's interface-based advice works under the hood.

#### Q71. [Theory] Why is `Cloneable`/`Object.clone()` considered broken, mechanically, and what exactly goes wrong with deep structures?

`Cloneable` is broken at the design level: (1) it's a **marker interface with no `clone()` method** — the method is `protected` on `Object`, so implementing `Cloneable` merely changes `Object.clone()`'s behavior from "throw `CloneNotSupportedException`" to "do a field-by-field copy." The contract lives on the wrong type. (2) `Object.clone()` is a **shallow copy** — it copies field *values*, so reference fields are shared between original and clone. For a mutable nested structure that's a bug: mutating the clone's list mutates the original's.

```java
class Stack implements Cloneable {
    private Object[] elements;   // shared after a naive super.clone()!
    @Override public Stack clone() {
        try {
            Stack copy = (Stack) super.clone();
            copy.elements = elements.clone();   // must deep-copy mutable internals by hand
            return copy;
        } catch (CloneNotSupportedException e) { throw new AssertionError(); }
    }
}
```

(3) `clone()` **doesn't invoke a constructor**, so invariants enforced in the constructor are bypassed and `final` fields can't be reassigned (you can't fix shallow `final` reference fields). (4) The checked `CloneNotSupportedException` is noise. *Effective Java* Item 13's conclusion: prefer a **copy constructor** (`new Stack(original)`) or **copy factory** (`Stack.copyOf(original)`) — they invoke real construction, can deep-copy explicitly, work with `final` fields, and don't rely on the broken machinery.

#### Q72. [Practical] How does CGLIB create a proxy, and why can't it proxy `final` classes or `final`/`private` methods?

CGLIB (and Spring's bundled, repackaged Objenesis/ASM-based proxying) creates a proxy by **generating a runtime subclass** of the target and **overriding each non-final method** to insert the interceptor (a `MethodInterceptor`). Because interception is implemented via *method overriding*, the rules of Java overriding dictate the limits:

- **`final` class** → cannot be subclassed at all → CGLIB can't proxy it.
- **`final` method** → cannot be overridden → that method runs un-proxied (advice silently skipped).
- **`private` method** → not inherited/overridable → never intercepted.
- **`static` method** → belongs to the class, not overridable → not intercepted.

```java
@Service final class Audit { @Transactional public void run() {} }  // CGLIB can't proxy → tx advice won't apply
```

Historically CGLIB also needed instantiation without calling the real constructor (solved via Objenesis, which bypasses constructors using JVM-internal allocation). The practical consequences candidates must internalize: for Spring AOP to work on a class without an interface, the bean must be non-`final` and the advised methods `public` (or at least non-`final`, non-`private`). This is the class-based mirror of the JDK-proxy interface constraint in Q69.

#### Q73. [Theory] Distinguish push-based Observer from pull-based Iterator, and explain precisely what backpressure adds in `Flow`.

**Pull (Iterator):** the *consumer* drives — it calls `next()` whenever it's ready, so flow control is inherent (a slow consumer simply pulls slower; the producer waits). **Push (classic Observer):** the *producer* drives — `subject.publish()` shoves events at observers regardless of whether they can keep up. Push gives low latency and decoupling but has a fatal flaw at scale: a **fast producer overwhelms a slow consumer**, causing unbounded buffering, OOM, or dropped events. Classic GoF Observer has no answer to this.

**Backpressure** (Reactive Streams / `java.util.concurrent.Flow`, JEP 266) merges the two: it's *push*, but the consumer signals **demand** upstream via `Subscription.request(n)`. The producer may emit at most `n` more items until the subscriber requests more.

```java
public void onSubscribe(Flow.Subscription s) { this.s = s; s.request(1); }  // I'll take one at a time
public void onNext(T item) { process(item); s.request(1); }                 // ask for the next only when ready
```

So backpressure adds a **bounded, consumer-governed feedback channel** to the Observer pattern, converting "producer floods consumer" into "consumer paces producer." That single addition — demand signaling — is the conceptual difference between `java.util.Observer` (deprecated, no flow control) and modern reactive streams.

#### Q74. [Theory] What is double dispatch, why does Visitor need it, and how do single-dispatch languages like Java force the `accept`/`visit` dance?

**Dispatch** = choosing which method body runs. Java does **single dispatch**: the runtime picks the method based on the *one* receiver's dynamic type (`a.foo()` uses `a`'s actual type), but argument types are resolved *statically* at compile time. **Double dispatch** means selecting a method based on the runtime types of **two** objects — here, both the element and the visitor. Visitor needs it because you want `visit` to vary by *both* the concrete element type (Num/Add) and the concrete visitor type (Eval/Print), and one virtual call can only resolve one of those.

The `accept`/`visit` two-step manufactures double dispatch out of two single dispatches:

```java
double r = expr.accept(visitor);   // dispatch #1: resolves expr's runtime type → e.g. AddNode.accept
// inside AddNode.accept(v):  return v.visit(this);
//                            dispatch #2: 'this' is now statically AddNode → picks visit(AddNode)
```

The first virtual call resolves the *element* type (calls the right `accept`); inside that override, `this` is statically known to be `AddNode`, so the *second* call `v.visit(this)` resolves the *visitor* type while statically binding the correct `visit(AddNode)` overload. Two single dispatches chained = double dispatch. Languages with multimethods (Clojure, Julia) express this directly; Java's single dispatch is precisely why Visitor exists as boilerplate — and why sealed types + pattern-matching `switch`, which match on runtime type directly, can replace it (Q46).

#### Q75. [Coding] Show how a non-capturing lambda Strategy is reused as a singleton by the JVM, and contrast with a capturing one.

```java
interface Pricer { double price(double base); }

class Demo {
    static Pricer noCapture() { return b -> b * 1.1; }            // captures nothing
    static Pricer capture(double rate) { return b -> b * rate; }  // captures 'rate'

    public static void main(String[] a) {
        // Non-capturing: the JVM is free to return the SAME instance each time.
        System.out.println(noCapture() == noCapture());   // commonly true (one shared instance)

        // Capturing: each call closes over a different 'rate' → distinct instances.
        System.out.println(capture(1.1) == capture(1.2)); // false (different captured state)
    }
}
```

A **non-capturing** lambda has no per-instance state, so `LambdaMetafactory` can — and the HotSpot implementation does — cache a single instance and hand it out repeatedly; the strategy is effectively a stateless singleton with zero allocation on the hot path. A **capturing** lambda must hold its captured values, so a new object is allocated per distinct capture (the metafactory generates an implementation with fields for the captured variables). The interview takeaway: prefer non-capturing strategies/predicates in hot loops (e.g., reuse a constant `Comparator`/`Predicate`) to avoid needless allocation; the difference is invisible in source but real in the profiler. (Note: `==` on lambdas is unspecified by the JLS — this demonstrates the *implementation's* behavior, not a guarantee to rely on.)

### 🟠 — extended

#### Q76. [Theory] Spring proxies and `this` self-invocation: trace the exact call path and explain every fix mechanically.

When Spring creates an AOP proxy for bean `B`, what gets injected into other beans is the **proxy `P`**, not the raw target `B`. An external call `someBean.outer()` goes `caller → P.outer() → [advice fires] → B.outer()`. Inside `B.outer()`, a call to `inner()` is compiled as `this.inner()` where `this` is the **raw `B`**, not `P` — so the call goes `B.outer → B.inner` directly, **never re-entering the proxy**, so `inner()`'s advice (its `@Transactional`, `@Cacheable`, etc.) is skipped entirely.

```java
@Service class S {
    @Transactional public void outer() { inner(); }            // this.inner() → no proxy → no new tx
    @Transactional(propagation = REQUIRES_NEW) public void inner() {}
}
```

The fixes, mechanically:
- **Split into two beans** — now `inner()` lives on a different bean `T`, injected as proxy `P_T`; `outer()` calls `injectedT.inner()` which *is* the proxy. The advice fires because the call crosses a proxy boundary.
- **Self-injection** — inject the bean into itself (`@Autowired private S self;`); Spring injects the proxy `P`, so `self.inner()` traverses it.
- **`AopContext.currentProxy()`** — requires `exposeProxy = true`; retrieves the current proxy from a thread-local and you call `((S) AopContext.currentProxy()).inner()`.
- **AspectJ load-time/compile-time weaving** — weaves the advice *into the bytecode of `B` itself*, so there's no separate proxy object at all; even `this.inner()` carries the woven advice. This is the only fix that makes self-invocation "just work."

The root cause is uniform: proxy-based AOP intercepts at the *object boundary*, and `this` calls don't cross it. AspectJ moves interception into the method body, eliminating the boundary.

#### Q77. [Theory] How do sealed interfaces give the compiler exhaustiveness checking, and what bytecode/typing machinery enforces it?

A `sealed` interface declares a **closed, compiler-known set of permitted subtypes** (`permits Num, Add, Mul`, or inferred from the same compilation unit). That permits-list is recorded in the class file as a **`PermittedSubclasses` attribute**, so the compiler (and the JVM at load time) can enumerate *every* possible concrete type of an `Expr`. Because the universe of subtypes is finite and known, a pattern-matching `switch` over a sealed type can be checked for **exhaustiveness**: if your `switch` covers all permitted types, the compiler proves no case is missing and lets you omit `default`; if you forget one, it's a **compile error**.

```java
sealed interface Expr permits Num, Add, Mul {}
static double eval(Expr e) {
    return switch (e) {           // no default needed — compiler knows the full set
        case Num n -> n.v();
        case Add a -> eval(a.l()) + eval(a.r());
        case Mul m -> eval(m.l()) * eval(m.r());
    };  // add a 4th permitted type → this switch fails to compile until you handle it
}
```

The machinery: `permits` + `PermittedSubclasses` gives a *finite type universe*; the JVM enforces at load time that only listed types may implement the interface (a rogue subtype fails verification); and `javac`'s exhaustiveness analysis uses that finite set to decide completeness. This is precisely the safety Visitor lacks — adding an element type to a Visitor silently compiles but misbehaves, whereas adding a permitted subtype *breaks the build at every non-exhaustive switch*, pointing you at every site to update (Q46, Q51).

#### Q78. [Coding] Implement a Memento that captures and restores state without violating encapsulation. How do you keep the snapshot opaque?

The point of Memento is that the **caretaker** (who holds snapshots) cannot read or tamper with their contents — only the **originator** can create and consume them. In Java you enforce this with a *private inner class* memento and a *narrow* public marker type the caretaker sees.

```java
class Document {
    private String content = "";

    // Opaque to the outside: only Document can read its fields.
    public sealed interface Snapshot permits Memento {}        // marker the caretaker holds
    private record Memento(String content) implements Snapshot {} // private state, inaccessible outside

    public Snapshot save() { return new Memento(content); }     // originator creates
    public void restore(Snapshot s) { this.content = ((Memento) s).content; } // originator consumes
    public void type(String s) { content += s; }
}

class History {                                  // caretaker
    private final Deque<Document.Snapshot> stack = new ArrayDeque<>();
    void push(Document.Snapshot s) { stack.push(s); }   // holds opaque tokens, can't inspect them
    Document.Snapshot pop() { return stack.pop(); }
}
```

Encapsulation holds because `Memento` is `private` (the caretaker can't access `content`) and the only public surface is the empty `Snapshot` marker — the caretaker can *store and return* snapshots but cannot *read* or *forge* them. Modern touches: a `record` makes the snapshot immutable (so a held memento can't drift), and a `sealed` marker prevents outsiders from supplying a bogus `Snapshot` to `restore`. The trade-off interviewers probe: mementos cost memory proportional to snapshot size × history depth, so real systems cap history or store diffs/commands instead for cheap-to-invert operations.

#### Q79. [Theory] Why does the JVM's class-loading lock (the initialization lock) make eager and lazy-holder Singletons immune to the partially-constructed-object hazard that plagues DCL?

Double-checked locking's hazard is that `instance = new T()` is **three non-atomic steps** (allocate, construct, publish reference), and absent `volatile`, the compiler/CPU may publish the reference *before* the constructor finishes, letting another thread on the lock-free fast path observe a non-null but half-built object. Class initialization sidesteps this entirely because of the **JLS §12.4 initialization protocol**, which the JVM implements with a per-class *initialization lock* and a state machine.

The protocol guarantees: when thread A is initializing class `C`, it holds `C`'s init lock and progresses through states (`verified → being-initialized → initialized`). Any thread B that triggers `C`'s initialization while A is mid-flight **blocks on the init lock** until A reaches the `initialized` state. Releasing that lock (and the state transition) establishes a **happens-before** edge: everything A's static initializer did — *including the full construction of the singleton and the write to the static field* — is guaranteed visible to B *before* B is allowed to read the field. So B can never see a partially constructed instance; the field is published only after construction completes, with proper memory ordering, enforced by the runtime rather than by `volatile`.

That's why lazy-holder (Q61) and eager init are bulletproof without any explicit synchronization or `volatile`: the classloader's init lock provides exactly the atomic-publish-with-happens-before guarantee that DCL has to reconstruct manually (and fragilely) with `volatile`.

#### Q80. [Theory] How does the Decorator pattern, applied as reactive operators, preserve laziness and where does each operator's behavior actually run?

In Reactor/RxJava, calling `flux.map(f).filter(p).buffer(10)` does **not** execute anything — each operator is a **Decorator that wraps the upstream `Publisher`** and returns a new `Publisher`, building an *assembly-time* chain of wrappers (just like `new Buffered(new Gzip(new File(...)))` builds a wrapper stack without moving bytes). Nothing runs until you **subscribe**; subscription walks the decorator chain from the bottom subscriber up to the source, wiring `onNext`/`request` callbacks. This is "assembly time vs subscription time vs runtime."

```java
Flux<Integer> pipeline = source.map(x -> x * 2)   // wraps source; no work yet
                               .filter(x -> x > 0); // wraps the map-publisher; still no work
pipeline.subscribe(System.out::println);          // NOW the chain executes, element by element
```

Each operator's transform runs **inline on whichever thread is currently emitting** as elements flow through — `map`'s function executes on the emitting thread at the moment an `onNext` propagates through that decorator. To move work across threads you insert *scheduling* decorators: `subscribeOn` (Strategy choosing the thread the *source* runs on, set once near subscription) and `publishOn` (switches the thread for *downstream* operators from that point on). So the operator chain is a Decorator stack that's lazy until subscribed, executes per-element as data threads through each wrapper, and uses `subscribeOn`/`publishOn` as Strategy plug-ins to decide *where* each segment runs (Q54).

#### Q81. [Practical] A team's codebase uses Service Locator pervasively. What concrete testing and architectural problems will they hit, and how does that trace back to the pattern's mechanics?

Service Locator has each class **pull** dependencies from a global registry (`Locator.get(X.class)`) instead of receiving them. Mechanically, the dependency edge is hidden inside the method/constructor body and routed through global state, which produces a predictable cluster of problems:

- **Tests need global setup/teardown** — because the dependency is fetched from a static locator, every test must populate and reset that locator; forget the reset and **tests leak state into each other** (order-dependent flakiness). You can't just `new Service(mock)`.
- **Hidden dependencies / no compile-time contract** — a class's true dependencies don't appear in its constructor signature, so you discover them only by reading the body or hitting a runtime "not registered" failure. This invites God classes (Q37) because adding a dependency is invisible and frictionless.
- **Tight coupling to the locator** (DIP/SRP smell) — every class now depends on the locator type, so the locator becomes a chokepoint and a single point of failure; swapping DI frameworks touches every class.
- **Runtime, not startup, failure** — missing wiring throws when the code path executes, not at construction, so misconfiguration can lurk until a rare branch runs (constructor DI fails fast at startup instead).

The fix is to invert the flow: **push** dependencies via constructor injection so edges are explicit, `final`, fast-failing, and `new Service(mock)`-testable (Q48, Q39). Service Locator legitimately survives only where late-bound, dynamic lookup is intrinsic (plugin discovery, `ServiceLoader`), not as the default wiring mechanism.

#### Q82. [Theory] Builder with a fluent API risks an "incomplete build" — how do staged/step builders use the type system to make missing required fields a compile error?

A plain Builder validates required fields only at runtime in `build()`. A **staged (step) builder** encodes the construction sequence in the **type system** so that the compiler refuses to let you call `build()` until every required field is set — each setter returns a *different interface* representing "the next required step," and only the final stage's interface exposes `build()`.

```java
public final class Connection {
    public interface HostStep { PortStep host(String h); }       // must set host first
    public interface PortStep { BuildStep port(int p); }         // then port
    public interface BuildStep { BuildStep ssl(boolean s); Connection build(); } // optionals + build

    public static HostStep builder() { return new Steps(); }

    private static final class Steps implements HostStep, PortStep, BuildStep {
        private String host; private int port; private boolean ssl;
        public PortStep host(String h) { this.host = h; return this; }
        public BuildStep port(int p)   { this.port = p; return this; }
        public BuildStep ssl(boolean s){ this.ssl = s; return this; }
        public Connection build()      { return new Connection(host, port, ssl); }
    }
    private Connection(String h, int p, boolean s) { /* ... */ }
}
// Compiles only in the right order; build() isn't even visible until host+port are set:
Connection c = Connection.builder().host("db").port(5432).ssl(true).build();
```

The trick: `builder()` returns `HostStep`, whose only method is `host(...)` returning `PortStep`, whose only method is `port(...)` returning `BuildStep` (which finally exposes `build()`). Required steps are *unskippable* because the intermediate interfaces don't offer any other method, and you literally **cannot** call `build()` before reaching `BuildStep` — the failure moves from runtime to **compile time**. The cost is more boilerplate (one interface per required field), so it's reserved for APIs where misuse is expensive or the construction order genuinely matters.

#### Q83. [Coding] Implement a thread-safe lazy initialization that's also exception-safe (a failed first attempt must allow a later retry, not cache the failure). Which pattern variant is this?

Naive lazy holders cache the *result* of the first call — but if construction can fail transiently (network, I/O), you don't want to permanently cache a failure or a half-built object. This is a lazy-init variant that must be **idempotent on success and retryable on failure**.

```java
class LazyResource {
    private volatile Resource resource;             // volatile for safe publication
    private final Object lock = new Object();

    Resource get() throws IOException {
        Resource r = resource;                       // 1st read, no lock (fast path once set)
        if (r == null) {
            synchronized (lock) {
                r = resource;                        // 2nd read, under lock
                if (r == null) {
                    r = createCanFail();             // if THIS throws, 'resource' stays null
                    resource = r;                    // publish only on success
                }
            }
        }
        return r;
    }
    private Resource createCanFail() throws IOException { /* may throw → no caching of failure */ }
}
```

Exception safety comes from assigning `resource` **only after** `createCanFail()** returns normally — if it throws, the field stays `null`, the lock is released by the `synchronized` block's implicit `finally`, and the *next* caller re-enters the slow path and retries. `volatile` provides safe publication (the same reason DCL needs it, Q8). Note you can't use the lazy-holder idiom (Q61) here, because class initialization runs the static initializer **once** and *caches the `ExceptionInInitializerError`* — a failed holder init poisons the class forever with `NoClassDefFoundError` on subsequent access. So when construction is fallible-and-retryable, fall back to correct DCL; when it's reliable, prefer the holder. (The JDK's `LazyInitializer` in some libraries and Guava's `Suppliers.memoize` formalize the reliable case.)

#### Q89. [Theory] How does `ThreadPoolExecutor` realize the Command pattern, and what is the "reified task" doing internally that a raw method call cannot?

`ThreadPoolExecutor` is the Command pattern at industrial scale: a submitted `Runnable`/`Callable` is a **reified invocation** — the work is packaged as an *object* that can be queued, scheduled, transferred between threads, retried, cancelled, and have its lifecycle observed. A raw method call can do none of this because it's bound to the calling thread's stack at the call site; turning it into a Command object *decouples submission from execution* in both time and thread.

```java
Future<Integer> f = executor.submit(() -> compute());  // the Callable IS the Command, reified as a task
// internally: wrapped in a FutureTask (Command + Memento of result/exception + state machine)
f.cancel(true);                                          // cancel the queued/running command
Integer r = f.get();                                     // retrieve the captured result later
```

Internally the executor wraps your `Callable` in a **`FutureTask`**, which is where the "reified" power lives: it holds a small **state machine** (NEW → COMPLETING → NORMAL/EXCEPTIONAL/CANCELLED), *captures the result or thrown exception* (a Memento-like snapshot so a different thread can read it later via `get()`), and manages the handoff so the producing worker thread and the consuming caller thread synchronize safely. The work queue (`BlockingQueue<Runnable>`) is exactly the "queue of commands" the Command pattern enables; the worker threads are invokers that pull and `run()` commands without knowing their concrete action. This is why "a job is a Command" (Q27) is literally true in `java.util.concurrent` — and why features like cancellation, scheduling (`ScheduledThreadPoolExecutor`), and result retrieval are *only* possible because the invocation was made a first-class object.

### 🔴 — extended

#### Q84. [Theory] Tie together the memory-model guarantees behind every "safe singleton": final-field semantics, class-init locks, and volatile. Why does each technique work?

Every correct singleton ultimately relies on the **Java Memory Model (JSR-133)** to guarantee that a reader sees a *fully constructed* instance. There are three distinct JMM mechanisms, and each idiom rides a different one:

1. **`final`-field semantics (eager + immutable singletons).** JSR-133 guarantees that when an object is constructed, all its `final` fields are *frozen* at the end of the constructor, and any thread that reads a reference to that object (without a data race on the reference itself) is guaranteed to see the correctly-initialized `final` fields. An eager `private static final INSTANCE = new T()` benefits from this plus class-init ordering — the static field is written during class initialization.

2. **Class-initialization lock (lazy-holder + eager).** As in Q79, JLS §12.4 makes class init run under a per-class lock with a happens-before edge from "init completes" to "any thread reads a static field of that class." This atomically publishes the singleton with correct ordering, no `volatile` needed.

3. **`volatile` (double-checked locking).** When you publish *outside* class init (a plain instance field set under a lock but read on a lock-free fast path), only `volatile` provides the happens-before/anti-reordering guarantee: a `volatile` write happens-before every subsequent `volatile` read of the same field, so the fast-path reader either sees `null` (and takes the slow path) or sees a fully-constructed object — never a half-built one.

The unifying idea: a singleton is correct iff there is a **happens-before edge from the constructor's completion to every read of the instance reference.** `final` fields, the class-init lock, and `volatile` are three different JMM tools for establishing that edge. The enum singleton (Q7) inherits #2 (it's a static field initialized during class init) *plus* serialization/reflection safety from the language's enum guarantees. Knowing *which JMM mechanism each idiom uses* is the deepest level of the singleton question.

#### Q85. [Theory] Patterns are "workarounds for missing language features." Defend or refute this thesis rigorously, with a taxonomy.

The thesis is **partly true and famously associated with Peter Norvig's observation** that 16 of the 23 GoF patterns are simplified or invisible in languages with richer features (first-class functions, multimethods, macros, dynamic typing). A rigorous taxonomy:

- **Patterns that are largely language-feature gaps (thesis holds):** *Strategy/Command* (collapse to first-class functions/closures), *Iterator* (built into `for-each`/generators), *Template Method* (higher-order functions parameterizing steps), *Visitor* (multimethods or pattern matching over sealed/ADT types), *Prototype* (copy constructors / structural typing), *Singleton* (modules/DI scopes). In Java 21, lambdas + sealed types + records dissolve much of the boilerplate (Q57, Q58, Q46).

- **Patterns that are genuine design ideas, not feature gaps (thesis fails):** *Decorator* (composable, stackable cross-cutting wrapping is a structural choice no language feature obviates — it endures in `java.io` and reactive operators), *Adapter* (interface mismatch is an *integration reality*, not a language deficiency — it never disappears), *Facade* (deliberate API boundary design), *Proxy* (access control / laziness / remoting is an architectural concern), *Composite* (modeling part-whole trees), *Bridge* (decoupling two independent variation axes). These describe *relationships and intent* that survive any language.

- **The deeper truth:** even where a feature absorbs the *mechanism*, the **intent and trade-off vocabulary persist**. "Use a Strategy here" still communicates *encapsulate-the-varying-algorithm* even when the implementation is a one-line lambda. So patterns are simultaneously (a) sometimes scaffolding for missing features *and* (b) a durable shared language of design forces.

**Verdict:** refute the thesis as a blanket claim — it conflates *implementation boilerplate* (which features can erase) with *design intent and structural relationships* (which they cannot). The expert position: language features change *how cheaply* a pattern is realized, not *whether the underlying design pressure exists*. The catalogue's lasting value is as a vocabulary of forces and trade-offs, which is feature-independent.

#### Q86. [Theory] Map the GoF patterns onto the "encapsulate what varies" axis and explain why this single principle generates most of them.

GoF's own foundational principle is **"identify the aspect that varies and separate it from what stays the same."** Nearly every pattern is an instance of choosing *what* varies and *how it's encapsulated*:

| What varies | Encapsulation mechanism | Pattern(s) |
|-------------|------------------------|------------|
| The algorithm | object held by composition | Strategy |
| The algorithm, over a lifecycle | self-rebinding state objects | State |
| The reified request | a Command object | Command |
| Steps of a fixed flow | subclass overrides (inheritance) | Template Method |
| Which class to instantiate | a creation method/object | Factory Method, Abstract Factory |
| Construction process | a Builder | Builder |
| Added responsibilities | recursive same-interface wrapper | Decorator |
| Access/visibility/timing of a call | a surrogate | Proxy |
| The interface itself | a converter | Adapter |
| New operations on a structure | an external visitor (double dispatch) | Visitor |
| How objects are traversed | an Iterator | Iterator |
| Who-talks-to-whom | a central coordinator | Mediator |

The reason one principle spawns so many patterns: **"what varies" can be almost anything** — an algorithm, a type, a step, an interface, a responsibility, a relationship — and for each kind of varying thing there's a natural encapsulation boundary (a value, an object, a subclass, a wrapper). The patterns differ along two further axes GoF named: *creational/structural/behavioral* (what kind of thing is varying) and *class vs object* scope (bound by inheritance at compile time vs composition at runtime). Once you internalize "encapsulate the variation, then decide whether to bind it by inheritance or composition," you can *derive* the right pattern from a code smell rather than memorize 23 — which is exactly the senior reasoning interviewers reward (Q44, Q57).

#### Q87. [Theory] Explain the "robustness/exhaustiveness trade-off" between the Visitor pattern and pattern matching as a manifestation of the expression problem at the type-system level.

The expression problem (Q51) asks: can you extend a system along *both* the **type axis** (new data variants) and the **operation axis** (new operations) without editing existing code *and* with full static type safety? Visitor and sealed+`switch` sit at opposite corners, and the type system makes the trade-off *provable*, not merely stylistic:

- **Visitor** makes the **operation axis open**: a new operation = a new `Visitor` implementation, *zero edits* to element classes, fully type-checked. But the **type axis is closed**: adding an element type forces adding a `visit(NewType)` method to the `Visitor` interface and **every** existing visitor — and crucially, the compiler *does* catch this (interface method missing), so Visitor is type-safe on both axes; its cost is *editing churn* across all visitors when a type is added.

- **Sealed + pattern-matching `switch`** makes the **operation axis open** *more cheaply* (a new operation = a new top-level function, no `accept`/`visit` ceremony) and gives **compiler-guided** type extension: adding a permitted subtype makes every non-exhaustive `switch` a **compile error** pointing you at each site to fix (Q77). So it's *also* type-safe on both axes, with less boilerplate.

The precise trade-off: both approaches are statically safe and both require touching all "operations" (visitors / switches) when you add a type — neither truly solves the expression problem (which needs type classes, traits with default methods, or multimethods to add *both* without editing existing code). Visitor optimizes for **runtime-pluggable operations** and works when you *can't modify the element types* (third-party hierarchy). Sealed+`switch` optimizes for **conciseness and compiler-driven safety** and works when you *control* the sealed hierarchy. The decision rule from the type-system view: choose the axis you expect to grow — if *operations* proliferate over a stable closed type set you control, sealed+`switch`; if you need to add operations to types you *don't* control or want operations pluggable at runtime, Visitor. The expression problem guarantees *something* must give; these patterns just choose *which* edit-cost you pay.

#### Q88. [Behavioral] You inherit a service with a deep inheritance hierarchy (6 levels) realized via Template Method, and a new requirement needs runtime-swappable behavior the hierarchy can't express. Walk through how you'd evolve it, balancing risk and team buy-in.

A strong answer is situation → diagnosis → incremental strategy → de-risking → outcome, demonstrating you weaponize patterns as *trade-offs* and migrate safely:

- **Diagnosis.** The hierarchy binds variation by *inheritance* (compile-time, single-axis), but the new requirement is *runtime* selection — a fundamental mismatch (Q40, Q55). Deep Template Method hierarchies also carry fragile-base-class risk: a change to level-2 ripples to all leaves, and LSP violations hide in overrides. So this isn't "add another subclass"; it's a signal to shift that axis of variation from inheritance to **composition (Strategy)**.

- **Incremental strategy (not a big-bang rewrite).** (1) Characterize the *actual* variation points the hierarchy expresses and which one needs to become runtime-swappable. (2) Extract that varying step into a Strategy interface; have the existing base class delegate the step to an injected strategy with a default that reproduces current behavior — so existing subclasses keep working unchanged (the *Strangler* approach). (3) Migrate leaves one at a time behind tests, replacing an override with a strategy implementation, deleting a hierarchy level per PR so reviews stay small and reversible (echoing Q50's discipline). (4) Collapse the now-thin hierarchy once leaves are converted.

- **De-risking.** Lean on tests as the safety net — characterization tests first if coverage is thin; feature-flag the new strategy-selection path; keep each PR independently shippable and revertible. Verify behavior parity at each step rather than trusting the refactor.

- **Team buy-in.** Frame it by the *force*, not the pattern name: "we need to vary this behavior at runtime, which inheritance structurally can't do — here's the smallest change that buys that." Pair on the first migration, show the before/after testability win, and let the team see reviews shrink. Avoid mandating "replace all inheritance"; convert only the axis the requirement demands.

- **What interviewers listen for.** You diagnosed *which axis of variation* was mismatched (not "inheritance bad"), you chose composition for a *concrete* reason (runtime swap), you migrated *incrementally behind tests* rather than a risky rewrite, and you secured buy-in through evidence and pairing rather than dogma. The anti-signal is either "rewrite it all as Strategy" (over-eager) or "just add a 7th subclass" (ignoring the structural mismatch). The senior move is the *minimal, reversible* shift of exactly the variation that needs to move, justified by the requirement and proven by tests.

#### Q90. [Theory] At the deepest level, what is the relationship between Dependency Inversion, the Hollywood Principle ("don't call us, we'll call you"), and Inversion of Control — and how do framework-versus-library boundaries fall out of it?

These three are concentric refinements of a single idea: **who depends on / calls whom**, inverted from the naive direction. Pin down each precisely:

- **Dependency Inversion Principle (DIP)** is a *compile-time, source-dependency* statement: high-level policy and low-level detail both depend on **abstractions**, and the abstraction does not depend on the detail. It inverts the *direction of the source-code dependency arrow* — without it, a high-level module would `import` and reference a concrete low-level class.

- **Inversion of Control (IoC)** is the *runtime, control-flow* generalization: the *framework*, not your code, owns the main loop and decides *when* your code runs. Your code no longer drives the program; it's *driven*. DI (dependency injection) is one specific kind of IoC — inverting *who supplies dependencies* (pushed in, not pulled/`new`-ed). Other kinds invert *who invokes behavior* (callbacks, template methods, event handlers, lifecycle hooks).

- **The Hollywood Principle** ("don't call us, we'll call you") is the *colloquial name* for IoC's control-flow inversion from the component author's seat: you register/implement a contract and the framework calls *you* at the right moment, rather than you calling the framework's main routine.

How the **library-vs-framework** distinction falls directly out of this: with a **library**, *you* are in control — you call its functions when you want (`Collections.sort(list)`). With a **framework**, *control is inverted* — the framework owns the lifecycle and calls *your* code through the extension points it defines (`HttpServlet.doGet`, Spring `@EventListener`, a JUnit `@Test` method, a React component's render). That is *exactly* the Hollywood Principle, and the patterns that realize it are the ones that invert control: **Template Method** (the framework's skeleton calls your overridden steps), **Observer/callbacks** (the framework notifies you), **Strategy/DI** (the container supplies and invokes your implementation), **Chain of Responsibility/filters** (the framework drives the pipeline through your handler).

The unifying thread connecting *all* of it — DIP, IoC, Hollywood, ports-and-adapters/hexagonal (Q59), the DI container, Template Method, Observer — is the same inversion: **make high-level policy independent of, and the controller of, low-level detail**, by depending on abstractions (DIP) and surrendering the calling/lifecycle control to a coordinator (IoC) that calls back into your code (Hollywood). A framework *is* "a body of code that inverts control via the Hollywood Principle"; a library is one that doesn't. Recognizing this is what lets you read any framework — you immediately look for *where it calls you back* and *which abstraction it depends on instead of your concrete class*.

---

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q91. [Practical] Your `getInstance()` is returning two different Singleton instances in production. What are the three most common root causes?

A Singleton that isn't singular almost always traces to one of these:

1. **Two class loaders.** A class is "one type" only within a single `ClassLoader`. If the same `Config` class is loaded by both the web app's loader and the container's shared loader (e.g., the JAR sits both in `WEB-INF/lib` and on the server's lib path), you get two `Config.class` objects, each with its own `static INSTANCE`. This is the classic cause in app servers and OSGi.
2. **Serialization.** Deserializing a Singleton creates a *new* object unless you implement `readResolve()` to return the canonical instance. (The `enum` singleton is immune — the JVM handles this for you.)
3. **Reflection.** `constructor.setAccessible(true); constructor.newInstance()` bypasses a `private` constructor. Defend by throwing from the constructor if `INSTANCE != null`, or — again — use an `enum`.

```java
// Hardening a class-based singleton against serialization + reflection
class Config implements Serializable {
    private static final Config INSTANCE = new Config();
    private Config() {
        if (INSTANCE != null) throw new IllegalStateException("Use getInstance()"); // anti-reflection
    }
    public static Config getInstance() { return INSTANCE; }
    private Object readResolve() { return INSTANCE; } // anti-serialization
}
```

The interview signal: you know that "one instance" is scoped to *one class loader*, and that `enum` (Effective Java Item 3) sidesteps all three at once.

#### Q92. [Practical] A teammate wrote `if (obj == null) obj = ...` lazy init without `volatile` and "it works on my machine." How do you explain the latent bug?

It works *until it doesn't* — a textbook visibility/reordering race that almost never reproduces on a single-core dev laptop or under light load. The write `obj = new Foo()` is three steps (allocate, construct, publish reference). Without a happens-before edge, another thread can observe the *published reference before the constructor's writes are visible*, handing it a half-built object. Symptoms in production: intermittent `NullPointerException` on a field that "can't be null," or fields holding default values (`0`/`null`) momentarily.

How to explain it convincingly: it's not a logic bug you can find by reading the method — it's a **Java Memory Model** guarantee that's *missing*. The fix menu, cheapest first:

- **Lazy-holder idiom** (Q61) — no `volatile`, no `synchronized`, JVM class-init lock does the work. Preferred.
- `volatile` + double-checked locking (Q8) if you genuinely need instance-level (not static) laziness.
- `synchronized` on the whole getter — correct but contended.

The meta-lesson for the teammate: "works on my machine" is meaningless for concurrency bugs because the *legal* reorderings the JMM permits are rarely the ones your CPU/JIT actually performs under low load.

#### Q93. [Coding] Given a sprawling `if/else if` chain dispatching on a `String type`, refactor it to be Open/Closed using a registry of strategies.

The `if/else` (or `switch`) grows every time a new type appears — closed for extension. Replace it with a `Map` from key to behavior, populated by registration. New types register themselves; the dispatcher never changes.

```java
@FunctionalInterface
interface Discount { double apply(double price); }

class DiscountRegistry {
    private final Map<String, Discount> strategies = new ConcurrentHashMap<>();
    private final Discount fallback = price -> price; // Null-Object default

    public void register(String code, Discount d) { strategies.put(code, d); }

    public double price(String code, double amount) {
        return strategies.getOrDefault(code, fallback).apply(amount);
    }
}

// Registration — adding "BLACKFRIDAY" never touches the dispatcher:
var reg = new DiscountRegistry();
reg.register("NONE",   p -> p);
reg.register("TEN",    p -> p * 0.9);
reg.register("HALF",   p -> p * 0.5);
double total = reg.price("TEN", 100); // 90.0
```

In Spring, this collapses even further: declare each strategy as a bean implementing `Discount`, give the interface a `String code()`, and inject `Map<String, Discount>` or `List<Discount>` — Spring auto-collects every implementation. Talking point: the registry trades a compile-time-exhaustive `switch` for runtime extensibility; if you *want* exhaustiveness, a `sealed` interface + `switch` is the OCP-friendly alternative (Q4).

#### Q94. [Practical] You see `new BufferedReader(new InputStreamReader(new FileInputStream(f)))` everywhere. A junior asks "why three objects to read a file?" Explain in terms of Decorator.

Each layer is a Decorator adding one concern while preserving the stream interface, so they compose:

- `FileInputStream` — the raw source: hands you **bytes** from disk.
- `InputStreamReader` — a *decoding* decorator: turns bytes into **characters** using a charset (specify it! `new InputStreamReader(in, UTF_8)` — the no-charset constructor uses the platform default and is a portability bug).
- `BufferedReader` — a *buffering* decorator: reads big chunks instead of one char at a time (huge I/O win) and adds `readLine()`.

The power is that each is independent and reusable: swap `FileInputStream` for a `SocketInputStream` and the other two layers are unchanged; drop `BufferedReader` if you don't need line reading. That's exactly why Decorator beats a class explosion (`BufferedFileCharReader`, `BufferedSocketCharReader`, ...). Modern note: for files, `Files.newBufferedReader(path, UTF_8)` wraps all three correctly in one call — prefer it.

#### Q95. [Practical] An interviewer hands you a class with a 9-parameter constructor and asks you to "make this safer." What's your move and why?

This is the telescoping-constructor smell — positional `new Account(true, false, 0, null, true, ...)` is unreadable and one swapped boolean is a silent bug the compiler can't catch. My move is a **Builder** (Q10), and the *reasons* matter more than reciting the pattern:

- **Named, order-independent arguments** via fluent setters — `.active(true).overdraftAllowed(false)` can't be transposed.
- **Immutability** — the target keeps `final` fields, set once in the private constructor.
- **Centralized validation** — `build()` is the single choke point to enforce invariants (e.g., "premium accounts require a tax ID") and fail fast before a half-valid object escapes.

I'd also mention the alternatives so it doesn't look reflexive: if the params are *all required and few*, a `record` is simpler; if many parameters are the same type (`boolean`, `boolean`, `int`, `int`), that itself argues for small value types (a `Money`, an `AccountFlags` record) to make transposition a compile error. The senior answer fixes the *type design*, not just the constructor ergonomics.

#### Q96. [Coding] Write a Null Object so a service never null-checks its (optional) metrics collector.

```java
interface Metrics { void increment(String counter); void timing(String key, long ms); }

enum NoOpMetrics implements Metrics {           // enum = free singleton, no allocation
    INSTANCE;
    public void increment(String counter) { /* intentionally nothing */ }
    public void timing(String key, long ms) { /* intentionally nothing */ }
}

class PaymentService {
    private final Metrics metrics;
    // Caller may pass null; we normalize to the Null Object exactly once
    PaymentService(Metrics metrics) {
        this.metrics = (metrics != null) ? metrics : NoOpMetrics.INSTANCE;
    }
    void pay() {
        metrics.increment("payments.attempted");   // never a null check
        // ... business logic ...
    }
}
```

The whole point is that the *normalization happens once* at the boundary, and the body stays clean and branch-free. Caveat worth stating: a Null Object is right for *truly optional, fire-and-forget* collaborators (logging, metrics). It is *wrong* for a mandatory dependency — silently no-op-ing a missing `PaymentGateway` would hide a real configuration error rather than fail fast.

#### Q97. [Practical] You need to swap a comparator at runtime to re-sort a UI table by different columns. Which pattern, and what's the idiomatic Java?

That's **Strategy** — the comparison algorithm is the interchangeable strategy, and `Comparator` is the JDK's built-in Strategy interface. Idiomatically you don't write classes; you compose comparators with lambdas and the `Comparator` combinators:

```java
Map<String, Comparator<Employee>> sorters = Map.of(
    "name",   Comparator.comparing(Employee::name),
    "salary", Comparator.comparingDouble(Employee::salary).reversed(),
    "hire",   Comparator.comparing(Employee::hireDate)
                        .thenComparing(Employee::name)        // tie-break
);

void sortBy(List<Employee> rows, String column) {
    rows.sort(sorters.getOrDefault(column, Comparator.comparing(Employee::name)));
}
```

Talking points: `Comparator.comparing(...).thenComparing(...)` is itself a composition of Strategy objects; `.reversed()` is a decorator over a comparator; and passing the comparator into `sort` is Strategy injection. This is the cleanest "patterns are nearly free in modern Java" demonstration.

### 🟡 — extended

#### Q98. [Practical] After adding `@Cacheable` to a method, you observe it still hits the database every call. Walk through the pattern-level diagnosis.

`@Cacheable` works by **Proxy** (Q33), so the failure modes are all "the proxy was bypassed or never created":

1. **Self-invocation.** The cached method is called via `this.method()` from another method in the *same bean*. The internal call never traverses the proxy, so no caching advice fires. Fix: call it from another bean, self-inject the proxy, or use AspectJ weaving.
2. **Method not `public` / class is `final` / method is `final`.** Spring AOP proxies can't intercept these (JDK proxies need an interface; CGLIB subclasses and can't override `final`). The annotation is silently ignored.
3. **No `@EnableCaching`** or no `CacheManager` bean — the proxy infrastructure was never wired, so `@Cacheable` is inert.
4. **Key mismatch.** A non-deterministic key (e.g., includes a timestamp or a mutable object with bad `equals/hashCode`) means every call computes a *new* key → perpetual miss. Cache "works," lookups never match.
5. **Wrong proxy mode** (`proxyTargetClass`) or the bean is referenced before AOP wraps it.

The mental model to state: "annotation-driven behavior in Spring is almost always a proxy; if it doesn't fire, ask *did the call go through the proxy, and could the proxy intercept this method shape*." That single question diagnoses `@Transactional`, `@Async`, `@Retryable`, and `@Cacheable` alike.

#### Q99. [Coding] Implement a Decorator that adds retry-with-backoff to any `Supplier`, without the caller knowing.

```java
class RetryingSupplier<T> implements Supplier<T> {     // same interface as the wrappee
    private final Supplier<T> delegate;
    private final int maxAttempts;
    private final long baseDelayMs;

    RetryingSupplier(Supplier<T> delegate, int maxAttempts, long baseDelayMs) {
        this.delegate = delegate; this.maxAttempts = maxAttempts; this.baseDelayMs = baseDelayMs;
    }

    @Override public T get() {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.get();
            } catch (RuntimeException e) {
                last = e;
                if (attempt == maxAttempts) break;
                try {
                    Thread.sleep(baseDelayMs * (1L << (attempt - 1))); // exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during retry", ie);
                }
            }
        }
        throw new RuntimeException("Failed after " + maxAttempts + " attempts", last);
    }
}

// Usage — the caller still just sees a Supplier<Response>:
Supplier<Response> resilient = new RetryingSupplier<>(() -> httpClient.call(req), 4, 100);
Response r = resilient.get();
```

Because the decorator *is* a `Supplier<T>`, it's transparent and stackable — wrap it again with a timing or circuit-breaker decorator. Note the `InterruptedException` handling: restore the interrupt flag, never swallow it. (In real code you'd reach for Resilience4j, which is this exact decorator idea, productionized.)

#### Q100. [Practical] Two teams modeled order lifecycle differently: one used a giant `switch(status)`, the other used the State pattern. The State version is now hard to extend with a new "PartiallyRefunded" status. What went wrong, and what's the trade-off you'd explain?

Neither approach is free; the State team hit State's *real* cost. Adding a status to a `switch`-based design means editing every method that switches (error-prone, but localized to those methods). Adding a status to a State-pattern design means a new class **plus** revisiting every existing state's transition methods to decide whether they can now transition to `PartiallyRefunded` — the transition graph is distributed across classes, so a new node touches many of them.

What "went wrong" is a mismatch of pattern to volatility:

- **State** pays off when *behavior per state* is rich and the set of states is relatively stable — it localizes each state's behavior and makes illegal transitions structurally hard.
- A **`switch` / enum-with-behavior** is cheaper when states are many/volatile but behavior is thin, because the transition table stays in one readable place.

The pragmatic middle ground I'd propose: an explicit **transition table** (a `Map<State, Set<State>>` or a small state-machine library like Spring StateMachine) so the *graph* is data in one place, while per-state behavior can still be objects. The interview signal is recognizing that "use the State pattern" is not automatically the senior choice — the cost is the distributed transition logic.

#### Q101. [Coding] Implement a Builder that makes "you forgot to set a required field" a *compile* error (a staged / step builder).

A normal builder defers required-field validation to `build()` (a runtime exception). A staged builder uses the type system so each required step returns a *different interface*, and only the final stage exposes `build()`.

```java
public final class Connection {
    private final String host; private final int port; private final boolean tls;
    private Connection(String host, int port, boolean tls) { this.host=host; this.port=port; this.tls=tls; }

    public interface HostStep { PortStep host(String host); }   // must call host() first
    public interface PortStep { BuildStep port(int port); }      // then port()
    public interface BuildStep {                                 // now optional + build()
        BuildStep tls(boolean enabled);
        Connection build();
    }

    public static HostStep builder() { return new Steps(); }

    private static final class Steps implements HostStep, PortStep, BuildStep {
        private String host; private int port; private boolean tls;
        public PortStep host(String h) { this.host = h; return this; }
        public BuildStep port(int p)   { this.port = p; return this; }
        public BuildStep tls(boolean e){ this.tls = e; return this; }
        public Connection build()      { return new Connection(host, port, tls); }
    }
}

// Compiles only if host() then port() are called; build() isn't even visible before that:
Connection c = Connection.builder().host("db").port(5432).tls(true).build();
```

You literally cannot write `Connection.builder().build()` — `builder()` returns `HostStep`, which has no `build()`. The cost is verbosity (one interface per required field), so reserve it for builders where a forgotten required field is genuinely dangerous; otherwise a plain builder with `build()`-time validation is fine.

#### Q102. [Practical] Your `Comparator`-based sort throws `IllegalArgumentException: Comparison method violates its general contract!`. What does this have to do with the Strategy pattern's contract, and how do you fix it?

The Strategy you injected (`Comparator`) has a *contract* — it must impose a **total order**: antisymmetric, transitive, and consistent. `Collections.sort`/`Arrays.sort` use TimSort, which *detects* contract violations and throws rather than silently corrupting data. Common causes:

- Comparing by subtraction with overflow: `(a, b) -> a.value - b.value` breaks when the difference overflows `int`. Use `Integer.compare(a.value, b.value)`.
- Non-transitive logic (e.g., "treat equal if within 0.1" — fuzzy equality isn't transitive).
- A mutable sort key changing *during* the sort.
- `null`-handling inconsistencies.

```java
// ❌ overflow → intransitive comparator → contract violation
Comparator<Item> bad = (a, b) -> a.price() - b.price();
// ✅ correct, overflow-safe
Comparator<Item> good = Comparator.comparingInt(Item::price);
```

The pattern-level point: a Strategy implementation must satisfy the *contract its interface advertises*. The JDK enforcing it here is a gift — it surfaces a latent bug instead of producing a quietly mis-sorted list.

#### Q103. [Practical] You're integrating a third-party SDK whose method signatures keep changing across versions, breaking your build each upgrade. Which pattern insulates you, and how do you apply it?

**Adapter** (often paired with an internal **Facade**). Define *your own* interface expressing what your app needs, and write an adapter that translates to the vendor SDK. The vendor's churn is then confined to the adapter; the rest of your codebase depends only on your stable abstraction (Dependency Inversion).

```java
// Your app's stable contract — owned by you, never changes when the SDK does:
interface SmsGateway { SendResult send(String to, String body); }

// Adapter isolates the vendor; only THIS file changes on SDK upgrade:
class TwilioSmsAdapter implements SmsGateway {
    private final TwilioClient client;     // vendor type, quarantined here
    public SendResult send(String to, String body) {
        var msg = client.messages().create(to, FROM, body);  // vendor API shape
        return new SendResult(msg.getSid(), msg.getStatus().equals("queued"));
    }
}
```

Benefits beyond version churn: you can swap vendors (write `VonageSmsAdapter`), mock `SmsGateway` in tests without the network, and keep vendor types out of your domain model. The cost is one indirection layer — cheap insurance against a dependency you don't control. This is the "anti-corruption layer" from DDD, realized as Adapter.

#### Q104. [Coding] Implement Chain of Responsibility for request validation where each handler can *enrich* the request, then short-circuit on the first failure.

```java
record ValidationResult(boolean ok, String error) {
    static ValidationResult pass() { return new ValidationResult(true, null); }
    static ValidationResult fail(String e) { return new ValidationResult(false, e); }
}

@FunctionalInterface
interface Validator { ValidationResult validate(OrderRequest req); }

class ValidationChain {
    private final List<Validator> validators = new ArrayList<>();
    ValidationChain add(Validator v) { validators.add(v); return this; }

    ValidationResult run(OrderRequest req) {
        for (Validator v : validators) {
            ValidationResult r = v.validate(req);
            if (!r.ok()) return r;          // short-circuit on first failure
        }
        return ValidationResult.pass();
    }
}

// Each link is independent, reorderable, individually testable:
var chain = new ValidationChain()
    .add(req -> req.items().isEmpty() ? ValidationResult.fail("empty cart") : ValidationResult.pass())
    .add(req -> req.total() <= 0       ? ValidationResult.fail("bad total")  : ValidationResult.pass())
    .add(req -> !req.address().valid() ? ValidationResult.fail("bad address"): ValidationResult.pass());

ValidationResult result = chain.run(order);
```

Using a functional-interface chain (a `List<Validator>`) instead of the classic linked-`next` form is the modern, more testable variant: each validator is a pure function, the chain owns ordering, and adding a rule is a one-liner (OCP). This is structurally how Servlet/Spring-Security filter chains work.

#### Q105. [Practical] A code reviewer says your factory "is just a switch and adds no value." When is that criticism right, and when is it wrong?

The criticism is **right** when the factory has exactly one caller, one product type that will never grow, and the construction is trivial (`return new Foo()`). Then the factory is pure ceremony hiding a constructor — delete it; it adds indirection without buying decoupling or extensibility. YAGNI applies.

It's **wrong** — and the factory earns its keep — when any of these hold:

- **Non-trivial construction logic** centralized in one place (validation, defaulting, choosing among subtypes, wiring collaborators) that would otherwise be duplicated at every call site.
- **The concrete type is chosen at runtime** from config/input — the `switch` *is* the value, because callers shouldn't know the concrete classes.
- **You want a seam** to vary creation in tests or swap implementations (DIP).
- The set of products **will grow**, and centralizing the dispatch means new types touch one file.

The mature answer names the test: "does this factory decouple callers from concrete types, or centralize real logic? If yes, keep it; if it's a one-product passthrough, the reviewer's right and I'll inline it." Recognizing *when not* to use a pattern is itself the senior signal (Q21).

### 🟠 — extended

#### Q106. [Practical] In a high-throughput service, profiling shows GC pressure from millions of short-lived identical config objects. Which pattern addresses it, and what's the subtle correctness requirement?

**Flyweight** — share immutable instances instead of allocating duplicates, slashing allocation and GC churn. The subtle, non-negotiable requirement: the shared (intrinsic) state must be **immutable**, because it's now visible to many concurrent clients. If a shared flyweight were mutable, one client's mutation would corrupt every other client holding the same reference — a brutal heisenbug.

```java
record Currency(String code, int fractionDigits) {}     // immutable → safe to share

final class CurrencyCache {
    private static final Map<String, Currency> CACHE = new ConcurrentHashMap<>();
    static Currency of(String code) {
        return CACHE.computeIfAbsent(code, c -> new Currency(c, 2));  // one instance per code
    }
}
```

Watch-outs to mention: (1) an unbounded flyweight cache is a memory leak if the key space is large/unbounded — bound it or use weak references; (2) the win is real only when the duplicated state is genuinely large/numerous — for a handful of objects the cache overhead isn't worth it. The JVM does exactly this for `Integer.valueOf(-128..127)` and interned `String`s (Q63).

#### Q107. [Coding] Implement an exception-safe, thread-safe lazy initializer where a *failed* first attempt is retried, not cached as a permanent failure.

Naive double-checked locking caches whatever the first call produced — including a broken half-state if construction threw. You want: success memoized forever, failure transparent so the next caller retries.

```java
class RetryableLazy<T> {
    private final Supplier<T> factory;
    private volatile T value;                 // published safely once set
    private final Object lock = new Object();

    RetryableLazy(Supplier<T> factory) { this.factory = factory; }

    T get() {
        T result = value;                     // fast path: already initialized
        if (result != null) return result;
        synchronized (lock) {
            if (value == null) {              // re-check under lock
                value = factory.get();        // if this throws, value stays null → next call retries
            }
            return value;
        }
    }
}
```

The key insight: if `factory.get()` throws, the exception propagates *and* `value` is still `null`, so the failure is **not** cached — the next caller re-enters the critical section and tries again. Contrast with `ConcurrentHashMap.computeIfAbsent`, which also doesn't cache exceptions, but can deadlock or behave oddly if the mapping function touches the same map. This pattern is "lazy init with retry semantics," the variant most production caches actually want. (Guava's `Suppliers.memoize` caches the *value* but, notably, also does not memoize a thrown exception.)

#### Q108. [Practical] You add `@Transactional(propagation = REQUIRES_NEW)` to an inner method to get an independent transaction, but it keeps joining the outer transaction. Diagnose at the pattern level and list every fix.

This is the proxy self-invocation problem (Q33/Q76), and the symptom — `REQUIRES_NEW` ignored — is its signature. `@Transactional` is realized by a **Proxy** that wraps the bean; the transactional advice runs only when a call *crosses the proxy boundary*. An internal `this.inner()` call goes directly object→object, never through the proxy, so the new-transaction advice never fires and `inner()` silently runs in the outer transaction.

Every mechanical fix, with its trade-off:

1. **Move `inner()` into a separate bean** and inject it. Cleanest; the call now crosses a proxy. Usually the right answer.
2. **Self-inject the proxy** (`@Autowired private MyService self;` or `@Resource`) and call `self.inner()`. Works, but `self`-referencing is a code smell some teams ban.
3. **`((MyService) AopContext.currentProxy()).inner()`** — requires `@EnableAspectJAutoProxy(exposeProxy = true)`. Explicit but ties code to Spring AOP internals.
4. **AspectJ load-time/compile-time weaving** — weaves the advice into the bytecode itself, so there *is* no proxy and self-invocation works. Most powerful, heaviest to set up.

The thing to articulate: this isn't a bug in Spring — it's an inherent limitation of *proxy-based* AOP, and every fix either routes the call through a proxy or eliminates the proxy via weaving.

#### Q109. [Coding] Implement a Composite + Visitor combination to compute multiple aggregates (total size AND file count) over a filesystem tree in one traversal, without bloating the node classes.

Putting `totalSize()`, `count()`, `deepestPath()`, ... directly on the node interface violates OCP (every new aggregate edits every node). **Visitor** externalizes the operations; **Composite** gives the tree to walk.

```java
sealed interface FsNode permits FsFile, FsDir {
    <R> R accept(FsVisitor<R> v);
}
record FsFile(String name, long bytes) implements FsNode {
    public <R> R accept(FsVisitor<R> v) { return v.visitFile(this); }
}
record FsDir(String name, List<FsNode> children) implements FsNode {
    public <R> R accept(FsVisitor<R> v) { return v.visitDir(this); }
}

interface FsVisitor<R> { R visitFile(FsFile f); R visitDir(FsDir d); }

// One visitor, one traversal, computes both aggregates:
class StatsVisitor implements FsVisitor<long[]> {     // [totalBytes, fileCount]
    public long[] visitFile(FsFile f) { return new long[]{ f.bytes(), 1 }; }
    public long[] visitDir(FsDir d) {
        long bytes = 0, files = 0;
        for (FsNode c : d.children()) { long[] r = c.accept(this); bytes += r[0]; files += r[1]; }
        return new long[]{ bytes, files };
    }
}
```

Adding a new aggregation (e.g., count by extension) means writing a new `FsVisitor` — the node classes never change. The trade-off Visitor always carries: adding a new *node type* forces editing every visitor (the expression problem, Q51/Q87). Modern alternative worth naming: since the hierarchy is `sealed`, a `switch` with pattern matching gives the same externalization *with* compiler-checked exhaustiveness and less boilerplate (Q46) — choose Visitor when the type set is stable and operations proliferate; choose sealed+switch when you value exhaustiveness and the operations are few.

#### Q110. [Practical] A legacy module exposes a 30-method "God interface" and your new client needs only 3 of them. What patterns do you reach for, and how do you keep the seam testable?

Two complementary moves:

1. **Interface Segregation via a narrow role interface.** Define a 3-method interface (`OrderLookup`) expressing exactly what the client needs. The client depends only on that. This is ISP applied retroactively.
2. **Adapter** to bridge the narrow interface to the fat legacy type. The adapter implements `OrderLookup` by delegating to the relevant 3 methods of the God interface, quarantining the legacy dependency in one place.

```java
interface OrderLookup {                       // narrow, client-specific (ISP)
    Order byId(String id);
    List<Order> byCustomer(String c);
    boolean exists(String id);
}

class LegacyOrderAdapter implements OrderLookup {   // Adapter to the 30-method God interface
    private final LegacyOrderManager legacy;
    LegacyOrderAdapter(LegacyOrderManager legacy) { this.legacy = legacy; }
    public Order byId(String id)            { return legacy.fetchOrderRecord(id).toOrder(); }
    public List<Order> byCustomer(String c) { return legacy.queryByCust(c); }
    public boolean exists(String id)        { return legacy.recordExists(id); }
}
```

Testability is the payoff: your client now depends on `OrderLookup`, which you trivially mock/stub — no need to satisfy 30 methods or stand up the legacy module. The God interface stays out of your tests entirely. This pairing (narrow port + adapter) is the hexagonal-architecture / anti-corruption-layer move (Q59).

#### Q111. [Theory] Under heavy concurrency, your Observer registry shows listeners occasionally missing events or throwing `ConcurrentModificationException`. Walk through the trade-offs of the fixes.

The CME means you're iterating the listener list while another thread mutates it (subscribe/unsubscribe) — a violation of the iterator's fail-fast contract (Q68). The fix options, with their precise trade-offs:

- **`CopyOnWriteArrayList`** — iteration snapshots the array, so publishing is lock-free and never throws CME. Cost: every `subscribe`/`unsubscribe` copies the whole array (O(n)). *Right when reads/publishes vastly outnumber subscription changes* — the usual case. Subtlety: an event published "during" a subscribe may or may not see the new listener (the snapshot was taken before the add), which is acceptable for most event buses but means *no strict ordering guarantee* between subscribe and publish.
- **Synchronized iteration** (lock around publish) — strict and simple, but serializes all publishing and risks holding a lock while running listener callbacks (a listener that blocks or re-enters can deadlock). Generally worse.
- **Copy-then-notify** — `for (var l : new ArrayList<>(listeners))` under a brief lock to copy, then notify outside the lock. Avoids holding a lock during callbacks (good) but allocates per publish.

"Missing events" specifically usually comes from notifying *outside* any synchronization while subscriptions change, or from a listener added after the snapshot. The senior framing: pick based on the read/write ratio and *never run untrusted listener callbacks while holding your lock* — that's how Observer registries deadlock. For real backpressure and ordering guarantees, move to `java.util.concurrent.Flow`/Reactor rather than hand-rolling.

#### Q112. [Coding] Implement an undo/redo stack using the Command pattern, and explain why Memento is often needed alongside it.

```java
interface Command { void execute(); void undo(); }

class TextDocument { final StringBuilder text = new StringBuilder(); }

class InsertCommand implements Command {
    private final TextDocument doc; private final int pos; private final String str;
    InsertCommand(TextDocument doc, int pos, String str) { this.doc=doc; this.pos=pos; this.str=str; }
    public void execute() { doc.text.insert(pos, str); }
    public void undo()    { doc.text.delete(pos, pos + str.length()); }   // inverse op
}

class History {
    private final Deque<Command> undo = new ArrayDeque<>();
    private final Deque<Command> redo = new ArrayDeque<>();
    void run(Command c) { c.execute(); undo.push(c); redo.clear(); }   // new action invalidates redo
    void undo() { if (!undo.isEmpty()) { Command c = undo.pop(); c.undo(); redo.push(c); } }
    void redo() { if (!redo.isEmpty()) { Command c = redo.pop(); c.execute(); undo.push(c); } }
}
```

Command works cleanly here because each edit knows its own **inverse** (`undo()` deletes what `execute()` inserted). **Memento** becomes necessary when an operation *isn't* cleanly invertible — e.g., a "format paragraph" that loses information, or a complex transform where computing the inverse is harder than snapshotting. Then `undo()` restores a saved Memento (an opaque snapshot of prior state) instead of reversing the operation. Rule of thumb: invertible, cheap operations → pure Command undo; lossy or expensive-to-invert operations → Command-that-captures-a-Memento. Real editors mix both, and coalesce small commands (typing) into one undo unit.

#### Q113. [Practical] CGLIB proxying suddenly fails after a teammate marks a service class `final` for "immutability." Explain the mechanics and the fix.

Spring AOP has two proxy strategies (Q47, Q72): **JDK dynamic proxies** (require the target to implement an interface; proxy implements the same interface) and **CGLIB** (subclasses the target class at runtime, overriding methods to insert advice). When a class has no interface, Spring falls back to CGLIB. CGLIB works by generating a *subclass* — and you **cannot subclass a `final` class**, nor override `final` or `private` methods. So marking the service `final` makes CGLIB unable to create the proxy → startup failure (or, for `final` *methods*, the advice is silently skipped).

Fixes, in order of preference:

1. **Don't make Spring-managed beans `final`.** Immutability of a service object isn't meaningful anyway — it's a stateless singleton; reserve `final` for value objects/DTOs.
2. **Extract an interface** and let Spring use a JDK dynamic proxy (the interface, not the class, is what's proxied — the impl can stay `final`). This also improves testability and DIP.
3. **AspectJ weaving** — modifies bytecode directly, no subclass needed, so it can advise `final` classes; heavier setup.

The mechanical takeaway: "proxy by subclassing" and "`final` forbids subclassing" are in direct conflict — that's the whole story.

### 🔴 — extended

#### Q114. [Practical] You're tasked with eliminating a 6-level Template Method inheritance hierarchy that a new requirement (runtime-swappable behavior) can't express. Lay out a low-risk, incremental migration to Strategy/composition.

Template Method binds variation at compile time via inheritance; a requirement to swap behavior *at runtime* fundamentally can't be met by which subclass you instantiated. The target is composition (Strategy), but a big-bang rewrite of a 6-level hierarchy is reckless. Incremental, test-guarded plan:

1. **Characterize behavior with tests first.** Pin the existing hierarchy's observable behavior under a test harness (golden/approval tests) so any refactor is verifiably behavior-preserving.
2. **Identify the true variation points** — the `abstract`/overridden steps. These become Strategy interfaces. The fixed skeleton stays, but its steps become *injected collaborators* instead of overridden methods.
3. **Strangler-style, one step at a time.** Convert one overridden step to a constructor-injected strategy, defaulting to a strategy that *delegates to the old subclass method* so nothing changes yet. Ship. Repeat per step.
4. **Collapse the hierarchy** once all steps are strategies: the 6 levels become one class composed of N strategies, selected at runtime.
5. **Delete dead subclasses** only after each is proven unused.

Risk balancing and **team buy-in**: frame it as enabling the new requirement (concrete value), not "I dislike inheritance"; keep each step independently shippable and reversible; pair-review the behavior-preservation tests. The senior signal is that you treat it as a *sequence of safe, verifiable refactors* (composition replacing inheritance, Q42/Q55), not a rewrite — and that you got the team to agree on the destination before moving.

#### Q115. [Coding] Build a small, type-safe internal DSL using a fluent Builder + method chaining where the chain enforces a valid grammar via return types.

Encoding a grammar in return types means illegal sequences won't compile — the same idea as a staged builder (Q101), scaled to a query DSL.

```java
// Grammar: SELECT cols FROM table [WHERE cond] [ORDER BY col]  — enforced by the type each step returns
final class Query {
    private final String sql;
    private Query(String sql) { this.sql = sql; }
    public String sql() { return sql; }

    public static SelectStep select(String... cols) { return new Builder(String.join(", ", cols)); }

    public interface SelectStep { FromStep from(String table); }
    public interface FromStep   { WhereStep where(String cond); OrderStep orderBy(String c); Query build(); }
    public interface WhereStep  { OrderStep orderBy(String c); Query build(); }
    public interface OrderStep  { Query build(); }

    private static final class Builder implements SelectStep, FromStep, WhereStep, OrderStep {
        private final StringBuilder sb = new StringBuilder("SELECT ");
        Builder(String cols) { sb.append(cols); }
        public FromStep from(String t)     { sb.append(" FROM ").append(t); return this; }
        public WhereStep where(String c)   { sb.append(" WHERE ").append(c); return this; }
        public OrderStep orderBy(String c) { sb.append(" ORDER BY ").append(c); return this; }
        public Query build()               { return new Query(sb.toString()); }
    }
}

// Legal; illegal orderings (where() before from(), two where()s) won't compile:
Query q = Query.select("id", "name").from("users").where("age > 18").orderBy("name").build();
```

The interface-per-state design makes the *grammar* a compile-time invariant: `select(...).where(...)` doesn't compile because `SelectStep` exposes only `from`. This is the State/staged-builder idea applied to language design, and it's how libraries like jOOQ achieve type-safe SQL. The cost is interface proliferation; justified for a public DSL where misuse must be caught at compile time.

#### Q116. [Theory] Argue rigorously when a "pattern" is actually a symptom of a missing language feature, using three concrete Java examples and their modern replacements.

The thesis (Q85): many GoF patterns are *workarounds for what the host language can't express directly*; as the language gains the feature, the boilerplate evaporates while the underlying *intent* remains. The discipline is separating the two. Three concrete cases:

1. **Iterator → language-level `for-each`/`Iterable`.** The Iterator pattern is a workaround for languages lacking built-in traversal abstraction. Java absorbed it: `Iterable` + the enhanced `for` loop *is* the pattern, made invisible (Q67). The intent (decoupled traversal) survives; the hand-written `hasNext/next` boilerplate is gone.
2. **Strategy (class-per-algorithm) → first-class functions (lambdas).** Strategy is a workaround for the absence of functions-as-values: you wrap a method in an object to pass it around. With lambdas/method references, a `Comparator` *is* the strategy with no class (Q65). Intent (interchangeable algorithm) survives; the SAM-class boilerplate is gone.
3. **Visitor → sum types + exhaustive pattern matching.** Visitor's `accept`/`visit` double-dispatch (Q74) is a workaround for single dispatch *and* the lack of pattern matching over a closed type set. `sealed` interfaces + `switch` pattern matching express "operation over a fixed set of variants" directly, with compiler-checked exhaustiveness (Q46/Q87). Intent (externalized operations over a type hierarchy) survives; the visitor scaffolding is gone.

The rigorous taxonomy: a pattern is "a missing-feature symptom" when the language can later make it *syntactically free without changing its semantics* (Iterator, Strategy, Visitor, even Singleton vs. a module/`enum`). It is *not* merely a missing feature when it encodes a genuine **runtime structural decision** the type system can't make for you — e.g., Decorator's *runtime stacking* of behaviors, Proxy's *runtime access control*, Composite's *recursive object graph*, Mediator's *coordination topology*. Those persist regardless of language power because they're about object-graph shape and runtime composition, not syntax. The senior conclusion: don't cargo-cult the boilerplate form of a pattern your language has subsumed, but don't dismiss patterns whose value is structural/runtime — know which bucket each falls in.

#### Q117. [Practical] Design the pattern architecture for a payment-processing pipeline that must support multiple gateways, retries, idempotency, fraud checks, and audit logging — and justify each pattern against a simpler alternative.

I'd compose several patterns, each earning its place against the "just write it inline" alternative:

- **Strategy / Abstract Factory for gateways.** Each gateway (Stripe, Adyen, PayPal) is a `PaymentGateway` strategy; a factory selects one per currency/region. *Vs. a switch:* the strategy set grows and each gateway has rich, independent behavior — a registry of strategies (Q93) keeps the dispatcher closed for modification.
- **Adapter per gateway.** Each gateway's vendor SDK is quarantined behind the `PaymentGateway` port (Q103), so vendor churn and vendor types don't leak into the domain.
- **Chain of Responsibility for the pipeline** (fraud check → idempotency guard → authorize → capture → audit). *Vs. a linear method:* handlers are independently testable, reorderable, and insertable (a new "sanctions check" is one link), and each short-circuits on failure (Q104).
- **Decorator for cross-cutting concerns** — wrap any `PaymentGateway` with `RetryingGateway` (backoff, Q99), `IdempotentGateway` (dedupe by idempotency key), `AuditingGateway` (log every call). *Vs. baking retries/audit into each gateway:* decorators keep each concern in one reusable, stackable place and keep gateways focused.
- **Command for the unit of work** — a `PaymentCommand` is reified so it can be queued, retried, persisted to an outbox, and replayed (Q89/Q112). *Vs. a method call:* you need durability and async/at-least-once semantics that a raw call can't provide.
- **Idempotency via a Memento-flavored stored result** keyed by idempotency key — a replay returns the saved outcome instead of re-charging.
- **Observer/events for audit + downstream** — `PaymentSucceeded`/`PaymentFailed` events fan out to ledger, notifications, analytics without the pipeline knowing the consumers (Q35).

The justification discipline matters more than the list: I'd *start* with the smallest thing (one gateway, inline pipeline) and introduce each pattern only when a real second variation or non-functional requirement (durability, vendor-swap, cross-cutting retry) appears — refactoring *toward* the pattern (Q21/Q60), not front-loading all of them. The architecture is the *destination*; for a single-gateway MVP most of it is over-engineering.

#### Q118. [Theory] Reconcile "depend on abstractions" (DIP) with the cost of premature abstraction. Give a principled rule for when an interface earns its existence.

DIP says depend on abstractions; YAGNI says don't build abstractions you don't need. They collide because *every* interface is an abstraction with a real cost: indirection, an extra file, harder navigation ("go to definition" lands on the interface), and the cognitive tax of one-implementation interfaces that pretend at flexibility they don't have. The reconciliation is recognizing that **DIP is about dependency *direction*, not interface *count*** — you only need an abstraction where you actually want to invert a dependency.

A principled rule: an interface earns its existence when at least one is true —

1. **Multiple real implementations exist or are imminent** (not hypothetical) — genuine polymorphism.
2. **It's a seam you must cross for testing** — an external dependency (DB, network, clock, message bus) you need to substitute with a fake. The test *is* the second implementation.
3. **It's an architectural boundary** (a port in hexagonal architecture, a published module API) where you deliberately decouple two sides that evolve independently or are owned by different teams.
4. **It hides volatile detail behind stable policy** — the abstraction shields high-level code from something that genuinely churns (a vendor SDK, Q103).

If none hold — a one-implementation interface with no test seam and no boundary — it's premature abstraction; inline the concrete class and **extract the interface later** when the second reason appears (it's a cheap, mechanical refactor). The senior nuance: "program to an interface" doesn't mean "wrap every class in an interface"; it means *depend on an abstraction at the points where you need substitutability or a boundary*. Counting your interfaces against those four justifications is the discipline that keeps DIP from degenerating into interface-itis.

#### Q119. [Coding] Implement a generic, reflective Proxy (JDK dynamic proxy) that adds method-level caching to *any* interface, and state precisely what it can't do.

```java
class CachingProxy {
    @SuppressWarnings("unchecked")
    static <T> T wrap(T target, Class<T> iface) {
        Map<List<Object>, Object> cache = new ConcurrentHashMap<>();
        return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{ iface },                       // proxy implements the interface
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {   // don't cache equals/hashCode/toString
                    return method.invoke(target, args);
                }
                List<Object> key = new ArrayList<>();
                key.add(method.getName());
                if (args != null) key.addAll(Arrays.asList(args));
                Object cached = cache.get(key);
                if (cached != null) return cached;
                try {
                    Object result = method.invoke(target, args);    // delegate on miss
                    cache.put(key, result);
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();                              // unwrap the real exception
                }
            });
    }
}

// Usage — caches results of any method on the Pricing interface:
Pricing cached = CachingProxy.wrap(new RealPricing(), Pricing.class);
```

What it **cannot** do, precisely:

- **Only works through an interface.** `Proxy.newProxyInstance` produces a class implementing the given *interfaces*; it cannot proxy a concrete class with no interface (that needs CGLIB/ByteBuddy subclassing, Q72).
- **Self-invocation isn't intercepted.** If a method on `target` calls another of its own methods via `this`, that internal call bypasses the proxy — exactly Spring's `@Transactional`/`@Cacheable` limitation (Q76, Q108).
- **The cache key is fragile.** It relies on the arguments' `equals`/`hashCode`; mutable args or args with default identity equality break it. And caching mutable return values shares aliased state across callers.
- **No invalidation/eviction** — unbounded cache is a leak; a real impl needs bounding/TTL.
- **`Object` methods** must be special-cased (done above) or you'll cache/mis-handle `equals`/`hashCode`/`toString`.

This *is*, mechanically, how Spring's JDK-proxy AOP and many caching libraries work — and articulating its boundaries is what separates "I can call `Proxy.newProxyInstance`" from "I understand why proxy-based frameworks have the limitations they do."

#### Q120. [Behavioral] A senior engineer on your team rejects all design patterns as "enterprise Java cargo cult." A junior over-applies them, wrapping everything in factories and interfaces. How do you align the team on a sane middle ground?

I'd treat this as a calibration problem, not a debate to win — both extremes raise the cost of change, just in opposite directions.

With the **pattern-skeptic** senior, I'd agree with the *valid kernel* of their critique (most enterprise pattern-abuse is real; a `AbstractSingletonProxyFactoryBean` is a punchline for a reason) and reframe patterns as **vocabulary and trade-off catalogue**, not mandatory ceremony. I'd point at code we *already* rely on — `Comparator` is Strategy, `java.io` is Decorator, `@Transactional` is Proxy — to show patterns aren't an enterprise affectation; they're how the JDK and Spring are built, and naming them speeds our design conversations. The ask is small: use the *names* when they clarify intent, drop the *boilerplate* the language has subsumed.

With the **over-applying** junior, I'd establish the "**start simple, refactor toward a pattern when a second variation actually appears**" rule (Q21/Q60), and make it concrete in review: a factory with one product, an interface with one implementation and no test seam, a builder for a 2-field record — these get a gentle "what does this abstraction buy us *today*?" The four-question test from Q118 (multiple impls? test seam? boundary? volatile detail?) becomes a shared, objective checklist so it's not me-versus-them taste.

Mechanism over edict: I'd add a short "patterns: when and when not" section to our engineering guide, seed it with examples from *our* codebase (both good uses and removed over-abstractions), and let code review enforce it consistently rather than relying on either senior's gut. The goal is a team that reaches for patterns *thoughtfully* — recognizing them when reading frameworks, applying them when a real second axis of change shows up, and deleting them when they're indirection without payoff. Aligning on the *decision rule* defuses both the cynicism and the cargo-culting, because now it's about evidence of change, not authority or fashion.

---

## ✅ Key Takeaways

- Patterns are a shared *vocabulary* and a catalogue of *trade-offs* for managing change and coupling — not code to transcribe or goals in themselves.
- SOLID is the foundation; most GoF patterns are concrete recipes for honoring one or more SOLID principles (Strategy→OCP/DIP, Decorator→OCP/SRP, Factory→DIP, etc.).
- Prefer **composition over inheritance** and **constructor dependency injection**; they yield testable, swappable, loosely coupled designs.
- Modern Java collapses several patterns into language features: lambdas ≈ Strategy/Command, sealed types + `switch` ≈ Visitor, records ≈ Builder for simple cases, `Flow` ≈ Observer + backpressure.
- Recognize patterns in the wild: `java.io` (Decorator), `JdbcTemplate` (Template Method/Facade), `@Transactional` (Proxy), `ApplicationEvent` (Observer), `ServiceLoader` (plugin/Factory).
- Know when *not* to use a pattern: a single implementation, speculative flexibility, or ceremony that out-weighs the problem signals over-engineering.

## ⚠️ Common Pitfalls

- **Pattern for its own sake** — adding a Factory/Strategy with one implementation, or a Builder for a 2-field object, is indirection without payoff.
- **Singleton as a global variable** — hidden coupling, shared mutable state, untestable code; use a DI singleton scope instead.
- **God Object / God Mediator** — centralizing too much responsibility violates SRP; decompose by responsibility rather than adding a manager-of-managers.
- **Double-checked locking without `volatile`** — exposes partially constructed objects; prefer the lazy-holder or enum singleton.
- **Misusing inheritance** — deep hierarchies, LSP violations (Rectangle/Square, `UnsupportedOperationException`), fragile base classes; favor composition.
- **Forgetting the proxy boundary** — Spring `@Transactional`/`@Cacheable` self-invocation silently does nothing because the call bypasses the proxy.
- **Pooling cheap objects** — object pools help only when creation cost ≫ pooling overhead; pooling ordinary objects fights the JVM and leaks state.
- **`Cloneable`/`clone()`** for copies — broken by design; use copy constructors or factory methods.

## 📚 Further Reading

- *Design Patterns: Elements of Reusable Object-Oriented Software* — Gamma, Helm, Johnson, Vlissides (the original GoF; read for the forces and trade-offs, not the C++).
- *Effective Java, 3rd Edition* — Joshua Bloch (Items 1–5 on factories/DI, 17–22 on inheritance/composition/interfaces, 89 on enum singletons).
- *Head First Design Patterns, 2nd Edition* — Freeman & Robson (approachable, Java-based, updated for Java 8+ lambdas).
- *Refactoring, 2nd Edition* — Martin Fowler (refactoring *toward* patterns; pairs naturally with this material).
- [Refactoring.Guru — Design Patterns](https://refactoring.guru/design-patterns) (clear diagrams and language-specific examples).
- *Clean Architecture* — Robert C. Martin (SOLID at the architectural scale; DIP, ports & adapters).
- [Java Language Updates — sealed classes & pattern matching](https://docs.oracle.com/en/java/javase/21/language/) (modern replacements for Visitor and verbose conditionals).
