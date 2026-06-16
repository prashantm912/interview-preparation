# TypeScript Interview Preparation

A staff-level, exhaustive guide to TypeScript for software-engineering interviews. It covers the structural type system, generics, narrowing, mapped/conditional types, declaration files, tooling, and the deep design trade-offs that distinguish senior engineers. Knowledge is current through 2026 (TypeScript 5.x, with notes on the `tsgo`/TypeScript 7 native compiler preview).

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

### Q1. [Theory] What is TypeScript and what problem does it solve over plain JavaScript?

TypeScript is a statically typed **superset** of JavaScript that compiles (transpiles) down to plain JavaScript. Every valid `.js` file is valid TypeScript, so adoption can be incremental. The core value is catching a whole class of errors at *compile time* rather than runtime: typos in property names, passing the wrong argument type, calling methods that do not exist, and forgetting to handle `null`/`undefined`. Beyond error-catching, the type information powers editor tooling—autocomplete, inline documentation, safe refactoring (rename symbol), and "go to definition." Crucially, TypeScript types are **erased** at build time: they have zero runtime cost and produce no runtime checks. This is a deliberate design choice that keeps output lean but means you cannot rely on types for runtime validation—you still need libraries like Zod or manual guards at trust boundaries (API responses, user input).

### Q2. [Theory] What is structural typing and how does it differ from nominal typing?

TypeScript uses **structural typing** ("duck typing"): two types are compatible if their *shapes* match, regardless of their declared names. If a value has all the members a target type requires, it is assignable—even if it was never declared to implement that type. This contrasts with **nominal typing** (Java, C#) where compatibility depends on the explicit name/declaration of the type.

```typescript
interface Point { x: number; y: number; }
function printPoint(p: Point) { console.log(p.x, p.y); }

const vec = { x: 1, y: 2, z: 3 };  // not declared as Point
printPoint(vec);  // OK — structurally compatible (has x and y)
```

The trade-off: structural typing is flexible and matches JavaScript's dynamic nature, but it can let semantically different types mix (e.g. a `Meters` and a `Feet` both being `number`). Engineers simulate nominal typing with **branded types** when that distinction matters (covered later).

### Q3. [Theory] What is the difference between `any`, `unknown`, `never`, and `void`?

These four are frequently confused:

- **`any`** disables type checking entirely—it is assignable to and from everything. It is an escape hatch that defeats the purpose of TypeScript; overuse erodes safety silently because errors propagate without warning.
- **`unknown`** is the *type-safe* counterpart of `any`. You can assign anything *to* `unknown`, but you cannot *use* it (call, index, assign to a narrower type) until you narrow it with a type guard. It forces you to prove the type before using it.
- **`never`** is the type with no values—it represents code that never returns (a function that always throws or loops forever) or an impossible branch. It is essential for exhaustiveness checking.
- **`void`** is the absence of a return value; a function returning `void` may still return `undefined`. Unlike `never`, the function *does* complete.

```typescript
let a: any = 5; a.foo.bar;          // compiles (dangerous)
let u: unknown = 5; // u.toFixed();  // ERROR until narrowed
if (typeof u === "number") u.toFixed(); // OK
function fail(): never { throw new Error(); }
```

### Q4. [Practical] When would you use an `interface` versus a `type` alias?

Both can describe object shapes and are largely interchangeable, but each has unique abilities. **Use `interface`** for public object/class contracts that may need extension or **declaration merging** (multiple `interface` declarations with the same name merge automatically—critical for augmenting third-party types or the global scope). **Use `type`** when you need unions, intersections, tuples, mapped types, conditional types, or to alias a primitive/function signature—things `interface` cannot express.

```typescript
interface User { id: string; }
interface User { name: string; }      // merges → { id, name }

type Status = "active" | "banned";    // union — interface can't do this
type Pair = [number, number];         // tuple — interface can't do this
```

A common team convention: prefer `interface` for objects/classes (better error messages, extensibility) and `type` for everything else. In production, consistency matters more than the micro-differences—pick one rule and lint it.

### Q5. [Coding] Write a generic `identity` function and a generic `firstElement` with proper constraints.

**Problem:** Create reusable functions whose return type tracks the input type, avoiding `any`.

```typescript
// Identity: T flows from argument to return type
function identity<T>(value: T): T {
  return value;
}
const n = identity(42);        // n: number
const s = identity("hello");   // s: string

// firstElement: works on any array, returns element type or undefined
function firstElement<T>(arr: readonly T[]): T | undefined {
  return arr.length > 0 ? arr[0] : undefined;
}
const first = firstElement([1, 2, 3]);     // number | undefined

// Constrained generic: only objects that HAVE a length property
function longest<T extends { length: number }>(a: T, b: T): T {
  return a.length >= b.length ? a : b;
}
longest([1, 2], [1, 2, 3]);   // OK
longest("ab", "abc");          // OK
// longest(10, 20);            // ERROR: number has no length
```

- **Why generics over `any`:** the relationship between input and output is preserved, so callers keep full type safety and autocomplete.
- **Edge cases:** `firstElement([])` returns `undefined`; the `readonly T[]` signature means it also accepts mutable arrays (readonly is a wider input type).
- **Complexity:** all are **O(1)** time and space (no iteration beyond an index access).

### Q6. [Theory] What does `strict` mode in `tsconfig.json` enable, and why turn it on?

`"strict": true` is a meta-flag enabling a family of checks: `strictNullChecks` (the most impactful—`null`/`undefined` are no longer assignable to every type), `noImplicitAny` (variables/params without inferable types error instead of silently becoming `any`), `strictFunctionTypes`, `strictBindCallApply`, `strictPropertyInitialization`, `alwaysStrict`, and `useUnknownInCatchVariables`. Turning it on is the single highest-leverage configuration decision: `strictNullChecks` alone eliminates the most common JavaScript runtime crash (`Cannot read property of undefined`). New projects should always start strict; legacy migrations enable flags incrementally to keep the error count manageable.

### Q7. [Practical] How do enums differ from `const` objects, and which should you prefer?

A numeric `enum` generates a runtime object with reverse mappings (`Color[0] === "Red"`), adding bundle size and a sometimes-surprising bidirectional map. A `const enum` is fully inlined at compile time (zero runtime footprint) but breaks under isolated-module bundlers (esbuild, SWC) and is incompatible with `isolatedModules`. Modern guidance (and many style guides) favors a **`const` object with `as const` plus a derived union type**, which is tree-shakeable, transparent in output, and works everywhere:

```typescript
const Color = { Red: "red", Green: "green", Blue: "blue" } as const;
type Color = typeof Color[keyof typeof Color]; // "red" | "green" | "blue"
function paint(c: Color) {}
paint(Color.Red);
paint("green"); // also valid — it's just a string union
```

TypeScript 5.0 also added stable string enums and the option to keep enums when you genuinely want a named namespace. Rule of thumb: reach for `const` objects unless you specifically need an enum's nominal/namespaced behavior.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Explain type narrowing and the main narrowing techniques.

**Narrowing** is how TypeScript refines a broad type (like a union) to a more specific one within a code branch, using runtime checks the compiler understands. The main techniques:

- **`typeof` guards** for primitives: `typeof x === "string"`.
- **`instanceof`** for class instances: `err instanceof TypeError`.
- **`in` operator** for property presence: `"swim" in animal`.
- **Truthiness narrowing**: `if (value)` removes `null`/`undefined`/`""`/`0` (beware falsy traps with `0` and `""`).
- **Equality narrowing**: `x === y` narrows both.
- **Discriminated unions** via a literal tag (the most robust pattern, below).
- **User-defined type guards** (`function isCat(a): a is Cat`).
- **Assertion functions** (`function assert(c): asserts c`).

The compiler builds a **control-flow graph** and tracks the narrowed type of each variable per branch. This is why reassigning a variable can widen it back, and why narrowing on object properties is fragile across function calls (the compiler assumes a call could mutate state).

### Q9. [Coding] Implement a discriminated union with an exhaustive `switch` that fails the build if a case is missed.

**Problem:** Model shapes and compute area; guarantee at compile time that every variant is handled.

```typescript
type Shape =
  | { kind: "circle"; radius: number }
  | { kind: "square"; side: number }
  | { kind: "rectangle"; width: number; height: number };

function area(shape: Shape): number {
  switch (shape.kind) {
    case "circle":
      return Math.PI * shape.radius ** 2;   // narrowed to circle
    case "square":
      return shape.side ** 2;
    case "rectangle":
      return shape.width * shape.height;
    default:
      // If a new variant is added but not handled, `shape` is NOT `never`
      // here, so this assignment errors at COMPILE time — exhaustiveness check.
      const _exhaustive: never = shape;
      return _exhaustive;
  }
}
```

```
        Shape (union)
        /     |       \
   circle   square   rectangle      <- discriminant: "kind"
     |        |          |
  radius    side    width,height    <- per-variant fields
                |
            switch(kind)            <- compiler narrows each branch
                |
           default: never           <- adding a 4th variant breaks build
```

- **Why this matters:** when someone adds `{ kind: "triangle" }` later, the `never` assignment fails and the build points them straight at the missing case—turning a runtime bug into a compile error.
- **Edge cases:** negative dimensions are not caught by types (runtime validation needed); the discriminant must be a literal type, not a general `string`.
- **Complexity:** **O(1)** per call.

### Q10. [Theory] Explain the built-in utility types `Partial`, `Required`, `Pick`, `Omit`, `Record`, and `ReturnType`.

These are mapped/conditional types shipped in `lib.d.ts`:

- **`Partial<T>`** makes all properties optional—ideal for update/patch payloads.
- **`Required<T>`** makes all properties required (removes `?`).
- **`Pick<T, K>`** selects a subset of keys: `Pick<User, "id" | "name">`.
- **`Omit<T, K>`** removes keys: `Omit<User, "password">`—great for stripping sensitive fields from API responses.
- **`Record<K, V>`** builds an object type with keys `K` and values `V`: `Record<string, number>` or `Record<"a" | "b", Config>`.
- **`ReturnType<F>`** extracts a function's return type via the `infer` keyword: `ReturnType<typeof createUser>`.

```typescript
interface User { id: string; name: string; password: string; }
type UserPatch  = Partial<User>;              // all optional
type PublicUser = Omit<User, "password">;     // { id, name }
type UserMap    = Record<string, User>;
type Created    = ReturnType<typeof createUser>;
function createUser() { return { id: "1", name: "A" }; }
```

Knowing these prevents reinventing types and keeps definitions DRY—when `User` changes, derived types update automatically.

### Q11. [Coding] Write a custom `DeepReadonly<T>` mapped type and a `DeepPartial<T>`.

**Problem:** The built-in `Readonly`/`Partial` are shallow. Make them recursive for nested objects.

```typescript
type DeepReadonly<T> =
  T extends (infer U)[] ? ReadonlyArray<DeepReadonly<U>> :
  T extends Function   ? T :
  T extends object     ? { readonly [K in keyof T]: DeepReadonly<T[K]> } :
  T;

type DeepPartial<T> =
  T extends (infer U)[] ? DeepPartial<U>[] :
  T extends Function   ? T :
  T extends object     ? { [K in keyof T]?: DeepPartial<T[K]> } :
  T;

interface Config {
  server: { host: string; ports: number[] };
  debug: boolean;
}
const frozen: DeepReadonly<Config> = getConfig();
// frozen.server.host = "x";   // ERROR — deeply readonly
const patch: DeepPartial<Config> = { server: { host: "localhost" } }; // OK
```

- **Approach:** a conditional type recurses through arrays, skips functions (mapping over a function strips its callability), and rebuilds objects with the modifier applied at each level.
- **Edge cases:** functions and primitives are passed through untouched; `Map`/`Set`/`Date` are treated as objects and would get their methods marked readonly—real-world versions add branches to exclude those built-ins.
- **Complexity:** these are *compile-time* type computations—no runtime cost; the type-checker work is proportional to the nesting depth (and can blow up the instantiation-depth limit on very deep types).

### Q12. [Theory] What are conditional types and the `infer` keyword?

A **conditional type** has the form `T extends U ? X : Y`—it selects a branch based on assignability, computed by the type-checker. Combined with **`infer`**, you can *extract* a type from within another type by introducing a fresh type variable in the `extends` clause. This is how `ReturnType`, `Parameters`, `Awaited`, and `InstanceType` are built.

```typescript
type ElementType<T> = T extends (infer E)[] ? E : T;
type A = ElementType<string[]>;   // string
type B = ElementType<number>;     // number (no array → fallback)

type MyAwaited<T> = T extends Promise<infer R> ? R : T;
type C = MyAwaited<Promise<number>>;  // number
```

Conditional types over a **naked type parameter** distribute over unions: `T extends U ? ... : ...` applied to `A | B` evaluates as `(A extends U ? ...) | (B extends U ? ...)`. Wrapping in a tuple `[T] extends [U]` disables that distribution—an essential trick when you want union-as-whole semantics.

### Q13. [Practical] How do `keyof` and `typeof` work together to derive types from runtime values?

`typeof` (in *type* position) captures the inferred type of a value; `keyof` produces a union of an object type's keys. Combined, they let you derive types from a single source of truth—the runtime data—so types never drift from values.

```typescript
const ROUTES = {
  home: "/",
  profile: "/profile",
  settings: "/settings",
} as const;

type RouteName = keyof typeof ROUTES;          // "home" | "profile" | "settings"
type RoutePath = typeof ROUTES[RouteName];     // "/" | "/profile" | "/settings"

function navigate(name: RouteName) { /* ... */ }
navigate("home");      // autocompletes & validated
// navigate("about");  // ERROR
```

The `as const` is what makes the values literal types instead of `string`. This pattern is everywhere in production—config objects, action-type maps, design tokens, feature-flag registries—because adding a key to the object instantly extends the type with zero duplication.

### Q14. [Practical] You're consuming an untyped third-party JS library. How do you add types?

Approach in order of preference:

1. **Check DefinitelyTyped**: `npm i -D @types/the-library`. Most popular packages have community types.
2. **If none exist**, write a **declaration file** (`.d.ts`). Start minimal—type only the API surface you actually use, using `declare module`:

```typescript
// types/cool-lib.d.ts
declare module "cool-lib" {
  export interface Options { retries?: number; timeout?: number; }
  export function connect(url: string, opts?: Options): Promise<Client>;
  export interface Client { send(msg: string): void; close(): void; }
}
```

3. **Wire it in**: ensure `tsconfig.json` includes the `types` directory (`typeRoots` or just having it under `include`).
4. **Escape hatch**: in a crunch, `declare module "cool-lib";` types the whole module as `any`—fast but unsafe; track it as tech debt.

**Trade-offs:** hand-written `.d.ts` files can drift from the real library on upgrades (no compile-time link between them and the JS). For libraries central to your app, contributing types upstream to DefinitelyTyped or pushing the maintainer to ship types is the durable fix. **Production reality:** I cap ad-hoc `any` declarations behind an ESLint rule and a `// TODO(types)` comment so they surface in review.

### Q15. [Coding] Implement a type-safe event emitter using mapped types and generics.

**Problem:** Build an emitter where `on`/`emit` enforce that the payload matches the event name.

```typescript
type EventMap = {
  login: { userId: string };
  logout: { reason: string };
  error: { code: number; message: string };
};

class TypedEmitter<E extends Record<string, unknown>> {
  private handlers: { [K in keyof E]?: Array<(payload: E[K]) => void> } = {};

  on<K extends keyof E>(event: K, fn: (payload: E[K]) => void): void {
    (this.handlers[event] ??= []).push(fn);
  }

  emit<K extends keyof E>(event: K, payload: E[K]): void {
    this.handlers[event]?.forEach((fn) => fn(payload));
  }
}

const bus = new TypedEmitter<EventMap>();
bus.on("login", (p) => console.log(p.userId));   // p inferred as { userId }
bus.emit("login", { userId: "42" });              // OK
// bus.emit("login", { reason: "x" });            // ERROR — wrong payload
// bus.on("unknown", () => {});                    // ERROR — no such event
```

- **Why:** the mapped type `{ [K in keyof E]?: ... }` ties each event name to its exact payload type, eliminating an entire class of "emitted the wrong shape" bugs that string-based emitters allow.
- **Edge cases:** unsubscribing (return an `off` function), once-handlers, and async handlers are natural extensions; `??=` lazily initializes the handler array.
- **Complexity:** `on` is **O(1)**; `emit` is **O(h)** in the number of handlers for that event.

### Q16. [Theory] What is declaration merging and where is it useful?

**Declaration merging** is the compiler combining two or more declarations with the same name into a single definition. Interfaces merge their members; namespaces merge; and a namespace can merge with a function or class to add static-like members. The most common real-world use is **augmenting existing types** you do not own:

```typescript
// Add a property to Express's Request without forking @types/express
declare global {
  namespace Express {
    interface Request { user?: { id: string; roles: string[] }; }
  }
}
export {}; // make this a module
```

This is how middleware ecosystems (Express, Fastify), Vue's `globalProperties`, and the global `Window` get extended cleanly. The catch: merging is implicit and can cause confusing "where did this member come from?" moments; keep augmentations in clearly named `*.d.ts` files.

### Q17. [Theory] Explain function type variance: parameter bivariance, contravariance, and `strictFunctionTypes`.

Variance governs when one function type is assignable to another. Sound typing requires **return types to be covariant** (a function returning `Dog` can stand in where one returning `Animal` is expected) and **parameter types to be contravariant** (a handler accepting `Animal` can stand in where one accepting `Dog` is expected, since it handles more). TypeScript enforces parameter contravariance for *function-typed* parameters under `strictFunctionTypes`. However, **method parameters remain bivariant** (deliberately unsound) for ergonomic reasons—largely so that arrays and event handlers feel natural. This is one of TypeScript's pragmatic soundness compromises. Knowing it explains otherwise-baffling assignability results and is a classic senior interview probe.

```typescript
type Handler = (e: Event) => void;
let h: Handler = (e: MouseEvent) => {}; // ERROR under strictFunctionTypes
                                        // (MouseEvent param is narrower)
```

---

## 🟠 Advanced (8–12 yrs)

### Q18. [Theory] What does it mean that TypeScript's type system is unsound, and where does it leak?

A **sound** type system guarantees that if it accepts a program, no type error occurs at runtime. TypeScript deliberately trades some soundness for usability and JavaScript compatibility. Known unsound holes include: **type assertions** (`as`) which the compiler trusts blindly; **`any`**, which poisons checking; **method/array parameter bivariance**; **mutable covariant arrays** (`Dog[]` assignable to `Animal[]`, then you push a `Cat`); **index-signature access** returning the value type even when the key is absent (unless `noUncheckedIndexedAccess`); and **`Object.keys` returning `string[]`** rather than `(keyof T)[]` (intentional, because objects can have extra keys at runtime). The team's stated goal is being *practically* correct, not *provably* sound. The senior takeaway: treat the type system as a powerful linter and a documentation/tooling engine—and put real **runtime validation** at every trust boundary, because types vanish at runtime.

### Q19. [Coding] Implement a fully type-safe `get` for nested object paths (dot-path autocomplete).

**Problem:** `get(obj, "a.b.c")` should autocomplete valid paths and return the precisely typed value.

```typescript
// Build the union of all valid dot-paths
type Path<T> = T extends object
  ? { [K in keyof T & string]:
        T[K] extends object ? `${K}` | `${K}.${Path<T[K]>}` : `${K}`;
    }[keyof T & string]
  : never;

// Resolve the value type at a given path
type PathValue<T, P extends string> =
  P extends `${infer K}.${infer Rest}`
    ? K extends keyof T ? PathValue<T[K], Rest> : never
    : P extends keyof T ? T[P] : never;

function get<T, P extends Path<T>>(obj: T, path: P): PathValue<T, P> {
  return path.split(".").reduce((acc: any, key) => acc?.[key], obj);
}

const data = { user: { profile: { name: "Ada", age: 36 } }, active: true };
const name = get(data, "user.profile.name"); // typed as string
const age  = get(data, "user.profile.age");  // typed as number
// get(data, "user.profile.xyz");             // ERROR — invalid path
```

- **Approach:** **template literal types** recursively assemble the legal path union; a second recursive conditional type walks the same path to compute the value type via `infer`.
- **Edge cases:** arrays/optional chaining need extra branches; very deep objects can hit the recursion-depth limit; runtime `reduce` returns `undefined` for missing intermediate keys even though the type claims otherwise (a soundness gap—pair with runtime guards).
- **Complexity:** runtime is **O(d)** in path depth; the type computation is also depth-bounded and pure compile-time.

### Q20. [Theory] Explain template literal types and key remapping in mapped types. Give a real use.

**Template literal types** let you build string literal types from other types using the same backtick syntax as JS templates, with `Uppercase`/`Lowercase`/`Capitalize`/`Uncapitalize` intrinsics. **Key remapping** (`as` clause inside a mapped type) lets you rename or filter keys while mapping. Together they enable generating one shape from another at the type level. A canonical use is deriving event-handler props or getter/setter names:

```typescript
type Getters<T> = {
  [K in keyof T & string as `get${Capitalize<K>}`]: () => T[K];
};

interface State { count: number; name: string; }
type StateGetters = Getters<State>;
// { getCount: () => number; getName: () => string; }
```

Filtering with `as` + `never` removes keys (e.g. strip all function-typed members). This powers libraries like type-safe ORMs, form builders, and CSS-in-JS prop generators. The risk is "type astronomy": clever types that are unreadable and slow to compile—use them where they pay off (public API ergonomics), not everywhere.

### Q21. [Practical] How do you implement nominal typing in TypeScript, and when is it worth it?

Structural typing happily mixes a `UserId` and an `OrderId` if both are `string`. **Branded (opaque) types** simulate nominal typing by intersecting a base type with a phantom marker that exists only at the type level:

```typescript
declare const brand: unique symbol;
type Brand<T, B> = T & { readonly [brand]: B };

type UserId  = Brand<string, "UserId">;
type OrderId = Brand<string, "OrderId">;

const asUserId = (s: string) => s as UserId; // smart constructor / validator

function fetchUser(id: UserId) {}
const uid = asUserId("u_123");
fetchUser(uid);            // OK
// fetchUser("u_123");     // ERROR — raw string not a UserId
// fetchUser(orderId);     // ERROR — different brand
```

**When worth it:** money/units (cents vs dollars), entity IDs, validated/sanitized strings (e.g. `Email`, `SafeHtml`), and security boundaries where mixing two `string`s causes real bugs. **Trade-off:** ceremony—you need constructor/validator functions and casts at creation points. I reserve brands for genuinely confusable primitives in critical paths (payments, auth) rather than blanketing the codebase.

### Q22. [Practical] A large monorepo's `tsc` takes 4 minutes and the editor lags. How do you diagnose and fix it?

```
                build / type-check too slow
                          |
        +-----------------+------------------+
        |                 |                  |
   measure first     project layout     type complexity
   --extendedDiag    project refs       expensive types
   --generateTrace   incremental        deep conditionals
   --diagnostics     skipLibCheck       large unions
```

**Diagnose:** run `tsc --extendedDiagnostics` (counts of files, types, instantiations, time per phase) and `--generateTrace traceDir` to load into a profiler and find the costliest type instantiations. Watch the **Instantiations** count—runaway recursive/conditional types are the usual culprit.

**Fixes, in order of leverage:**
1. **Project references** (`composite: true` + `tsc -b`) so packages build incrementally and the editor only re-checks touched projects.
2. **`incremental: true`** with a `.tsbuildinfo` cache.
3. **`skipLibCheck: true`** to skip type-checking `node_modules` `.d.ts` (huge win in big dependency trees; small soundness cost).
4. **Simplify expensive types**: replace deep recursive conditional types with explicit interfaces; break giant unions; add explicit return-type annotations on exported functions so the checker need not infer across the boundary.
5. Decouple **type-checking from transpilation**: use esbuild/SWC/Babel for emit (fast, types-erased) and run `tsc --noEmit` for checking in CI/parallel.
6. Looking ahead: the **native `tsgo` compiler (TypeScript 7)**, written in Go, targets ~10x faster checking—evaluate it in preview for the heaviest repos.

**Production outcome:** on a real monorepo, enabling project references + `skipLibCheck` + esbuild transpile took type-aware editor latency from seconds to sub-second and CI checks from minutes to under a minute.

### Q23. [Theory] Compare TypeScript decorators (legacy `experimentalDecorators`) with the TC39 Stage 3 / TS 5.0 standard decorators.

Legacy decorators (the `experimentalDecorators` flag, used heavily by Angular, NestJS, TypeORM) follow an older proposal and rely on `reflect-metadata` plus `emitDecoratorMetadata` to capture design-time types at runtime. The **TC39 standard decorators**, shipped in **TypeScript 5.0**, follow the finalized Stage 3 spec: a different signature (each decorator receives the value and a `context` object with `kind`, `name`, `addInitializer`, `access`), no built-in parameter decorators yet, and no metadata emission by default (the separate decorator-metadata proposal and `Symbol.metadata` cover that). They are not interchangeable—you choose one mode per project via `experimentalDecorators`. Most enterprise frameworks still ship on the legacy mode as of 2026, so migrating a NestJS/Angular app is non-trivial. New library code without a framework dependency should target the standard form for future-proofing.

```typescript
// TS 5.0 standard decorator: logs every method call
function logged<T, A extends any[], R>(
  fn: (this: T, ...args: A) => R,
  ctx: ClassMethodDecoratorContext
) {
  return function (this: T, ...args: A): R {
    console.log(`calling ${String(ctx.name)}`);
    return fn.apply(this, args);
  };
}
class Service { @logged greet(name: string) { return `hi ${name}`; } }
```

### Q24. [Coding] Build a type-safe builder pattern that prevents `build()` until all required fields are set.

**Problem:** A fluent builder where calling `build()` before required fields are provided is a *compile* error.

```typescript
interface Query { table: string; where: string; limit: number; }

class QueryBuilder<Set extends keyof Query = never> {
  private parts: Partial<Query> = {};

  from(table: string): QueryBuilder<Set | "table"> {
    this.parts.table = table;
    return this as any;
  }
  where(cond: string): QueryBuilder<Set | "where"> {
    this.parts.where = cond;
    return this as any;
  }
  limit(n: number): QueryBuilder<Set | "limit"> {
    this.parts.limit = n;
    return this as any;
  }
  // build() only callable once "table" | "where" are in Set
  build(this: QueryBuilder<"table" | "where">): Query {
    return this.parts as Query;
  }
}

new QueryBuilder().from("users").where("age > 18").build(); // OK
// new QueryBuilder().from("users").build();
//   ERROR: 'this' context requires "where" to be set
```

- **Approach:** the generic `Set` accumulates which fields have been provided via union widening; `build`'s **`this` parameter** constrains the allowed state. The compiler tracks progress through the chain.
- **Edge cases:** the `as any` casts are an internal implementation detail hidden from callers; `limit` is optional here so it is not in `build`'s requirement; order-independence works because we union, not sequence.
- **Complexity:** **O(1)** per chained call; all enforcement is compile-time.

### Q25. [Practical] How do you safely validate external data (API responses) given that types are erased at runtime?

Types provide **zero** runtime guarantees—an API can return anything and a bare `as ApiResponse` cast is a lie the compiler believes. The robust pattern is a **runtime schema validator** that *infers* the static type, so the type and the validation share one source of truth:

```typescript
import { z } from "zod";

const UserSchema = z.object({
  id: z.string().uuid(),
  email: z.string().email(),
  roles: z.array(z.enum(["admin", "user"])),
});
type User = z.infer<typeof UserSchema>; // derived — never drifts

async function fetchUser(id: string): Promise<User> {
  const res = await fetch(`/api/users/${id}`);
  return UserSchema.parse(await res.json()); // throws on bad shape
}
```

**Trade-offs:** validators add runtime cost and bundle size; you validate at *boundaries* (network, localStorage, env vars, message queues, user input) not internally. **Security angle:** this is also a defense layer—it rejects malformed/malicious payloads before they reach business logic, and `z.string().email()` etc. enforce content constraints. Alternatives: Valibot (smaller bundle), io-ts, ArkType, or TypeBox (JSON-Schema-backed). The anti-pattern to call out in review: trusting `JSON.parse(...) as User`.

---

## 🔴 Expert (15+ yrs)

### Q26. [Theory] When is it correct to *not* use TypeScript, or to deliberately weaken the types?

A staff engineer judges where typing pays for itself. Cases to weaken or skip: **prototypes/spikes** where iteration speed dominates and the API is in flux; **highly dynamic metaprogramming** (plugin systems, generic serializers) where modeling every case produces unreadable "type astronomy" that costs more than the bugs it prevents; **performance-critical type computations** that balloon compile times; and **interop glue** where a narrow `any`/`unknown` with a runtime guard is clearer than a baroque generic. The skill is *localizing* unsafety: a single well-commented `any` behind a validated boundary is fine; pervasive implicit `any` is rot. I also weigh team maturity—aggressive conditional-type wizardry that only one person can maintain is an organizational risk, not a flex. The goal is *net* productivity and reliability, not a soundness trophy.

### Q27. [Theory] Explain higher-kinded types, why TypeScript lacks them, and how libraries simulate them.

A **higher-kinded type (HKT)** abstracts over type constructors themselves—e.g. writing `Functor<F>` where `F` could be `Array`, `Promise`, or `Option`, so you can define `map` once for all of them. Languages like Haskell and Scala support this; TypeScript's generics only abstract over concrete types, not over `F<_>` (you cannot write `<F<_>>`). The fp-ts ecosystem simulated HKTs with a "**defunctionalization**" trick: a global interface `URItoKind<A>` maps string tags ("URIs") to concrete types, and `Kind<F, A>` indexes into it. It works but is verbose, produces brutal error messages, and was a major reason fp-ts/Effect-TS reworked their APIs toward a more ergonomic style (Effect largely abandoned the HKT-heavy surface for everyday users). The interview signal: understanding *why* the limitation exists (TypeScript's design priorities and the gradual-typing model) and the cost/benefit of the simulation.

### Q28. [Practical] You're rolling out TypeScript across a 500k-line JavaScript codebase with 80 engineers. Design the migration.

```
Phase 0  Tooling      allowJs + checkJs(opt), tsc --noEmit in CI (non-blocking)
   |
Phase 1  Foothold     rename leaf utils .js->.ts, NO strict yet, "any" allowed
   |
Phase 2  Boundaries   type the shared libs / API clients first (max leverage)
   |
Phase 3  Strictness   per-file/per-dir: noImplicitAny -> strictNullChecks ...
   |
Phase 4  Enforce      make CI blocking; ratchet error budget down over time
```

**Strategy:** never a big-bang rewrite. Enable `allowJs` so `.ts` and `.js` coexist. Start with **leaf modules and shared libraries** (typing a util used by 200 files yields the most safety per hour). Introduce strictness **incrementally and per-directory**—turn on `noImplicitAny`, stabilize, then `strictNullChecks` (the hardest, most valuable flag). Use an **error budget that only ratchets down**: a CI check that fails if total `tsc` errors *increase*, so new code is clean while legacy is paid down opportunistically (tools like `betterer` or a custom baseline do this). Codemods (`ts-migrate` from Airbnb) bootstrap annotations. **Org dimension:** pair training, a shared `tsconfig` base package, an ESLint config banning new `any`, and design-doc reviews for shared type contracts. **Metrics:** track `any` density and `strict` coverage over time. The behavioral subtext—this is as much change management as engineering.

### Q29. [Behavioral] Tell me about a time you pushed back on a complex typing approach in code review.

Use a **STAR** structure. *Situation:* a teammate built a deeply recursive conditional-type system to validate SQL query strings at compile time—elegant, but it added seconds to editor latency and only they could modify it. *Task:* I owned the platform's developer-experience and had to weigh safety vs. maintainability. *Action:* rather than rejecting outright, I quantified the cost (`--extendedDiagnostics` showed a 6x instantiation spike) and proposed an alternative—a small runtime query builder with ordinary generics that caught 90% of the same bugs at a fraction of the complexity. I framed it as "what will the team be able to maintain in a year?" and brought data, not opinion. *Result:* we adopted the simpler approach; the author later thanked me when onboarding got easier. The lesson I emphasize: cleverness in types is a liability if the team can't maintain it; senior engineers optimize for the system's long-term health, and bring evidence to design disagreements rather than asserting taste.

### Q30. [Theory] How does declaration-file (`.d.ts`) generation interact with library publishing, and what pitfalls bite library authors?

When publishing a library you ship `.d.ts` files (via `"declaration": true` and `"types"`/`"exports"` in `package.json`). Pitfalls a staff library author must navigate: **the dual ESM/CJS problem**—you may need separate `.d.ts` and `.d.cts` types under `exports` conditions or consumers get wrong module resolution. **Leaking private/transitive types**—generated declarations can reference internal or `node_modules` types that consumers can't resolve; `--isolatedDeclarations` (TS 5.5) enforces explicit annotations so declarations can be generated per-file (and very fast) without cross-file inference. **TypeScript version skew**—features used in your `.d.ts` may not parse in consumers' older TS; libraries either set a minimum or use `typesVersions` to ship variants. **Portability**—the "The inferred type cannot be named" error appears when an inferred return type references an unexported type; the fix is exporting it or annotating explicitly. **`@arethetypeswrong` (`attw`)** is the de-facto CI check for catching these resolution problems before release. Getting this wrong breaks every downstream consumer, so library DX is a distinct discipline from app development.

### Q31. [Coding] Implement a `Result<T, E>` type and helpers for railway-oriented error handling without exceptions.

**Problem:** Model fallible operations explicitly so callers must handle errors, with full type inference—an alternative to throwing.

```typescript
type Ok<T>  = { readonly ok: true;  readonly value: T };
type Err<E> = { readonly ok: false; readonly error: E };
type Result<T, E> = Ok<T> | Err<E>;

const ok  = <T>(value: T): Ok<T>   => ({ ok: true, value });
const err = <E>(error: E): Err<E>  => ({ ok: false, error });

// Chain: only runs fn if previous step succeeded (railway pattern)
function map<T, U, E>(r: Result<T, E>, fn: (v: T) => U): Result<U, E> {
  return r.ok ? ok(fn(r.value)) : r;
}
function flatMap<T, U, E>(
  r: Result<T, E>,
  fn: (v: T) => Result<U, E>
): Result<U, E> {
  return r.ok ? fn(r.value) : r;
}

function parseAge(s: string): Result<number, string> {
  const n = Number(s);
  if (Number.isNaN(n)) return err("not a number");
  if (n < 0)          return err("negative age");
  return ok(n);
}

const outcome = flatMap(parseAge("33"), (age) =>
  age >= 18 ? ok(`adult:${age}`) : err("minor")
);
if (outcome.ok) console.log(outcome.value); // discriminated-union narrows
else            console.error(outcome.error);
```

- **Approach:** a discriminated union on `ok` forces callers to check before accessing `value` or `error`—the compiler will not let you read `value` on the error branch.
- **Why over exceptions:** errors are part of the type signature (visible, exhaustively handled), and there is no invisible control-flow jump; ideal for predictable domain errors. Throwing still fits truly exceptional/unrecoverable cases.
- **Edge cases:** consider an `unwrapOr(default)` helper, async variants (`Promise<Result<...>>`), and combining many results (`all`).
- **Complexity:** all helpers are **O(1)**; this is the foundation of libraries like neverthrow and Effect's typed errors.

### Q32. [Theory] What soundness and performance trade-offs come with `noUncheckedIndexedAccess`, and would you enable it?

`noUncheckedIndexedAccess` closes a notorious unsound hole: by default `arr[i]` and `record[key]` are typed as the value type even when the index might not exist, so `const x: number = arr[999]` compiles but is `undefined` at runtime. With the flag, indexed access yields `T | undefined`, forcing a check. **Pros:** eliminates a real and common crash source; aligns the types with runtime reality. **Cons:** it is noisy—every loop body, every map lookup now sees `| undefined`, which can spawn nullish-coalescing and non-null-assertion (`!`) clutter, and it does not narrow inside `for` loops where you "know" the index is valid. It is *not* part of `strict` precisely because of the friction. **My call:** enable it in new, greenfield strict codebases and in security/data-integrity-sensitive modules; for large legacy migrations, defer it until after `strictNullChecks` is fully adopted, because the combined error volume can stall the effort. The decision is about *where* the value (caught bugs) outweighs the *cost* (annotation churn and reviewer fatigue)—the recurring theme of staff-level TypeScript judgment.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q33. [Theory] What is literal-type *widening*, and why does `let x = "a"` differ from `const x = "a"`?

When TypeScript infers a type from an initializer, it applies **widening**: a literal value's type is broadened to its general primitive unless something pins it to the literal. With `const x = "a"`, the binding can never be reassigned, so the compiler safely infers the *literal* type `"a"`. With `let x = "a"`, the variable is mutable, so the compiler infers the *widened* type `string`—otherwise you could never assign a different string. The same logic governs object properties: `{ x: "a" }` has property type `string` because object properties are mutable by default. This is why config objects need `as const` to keep literal types.

```typescript
const a = "hello";        // type: "hello"  (literal)
let   b = "hello";        // type: string   (widened)
const obj = { mode: "dark" };  // mode: string (widened — property is mutable)
const frozen = { mode: "dark" } as const; // mode: "dark" (readonly → literal kept)
```

The mechanism interacts with **fresh literal types**. A newly written literal is "fresh" and subject to excess-property checks and widening; once assigned to a variable it loses freshness. Understanding widening explains a frequent confusion: why a function expecting `"dark" | "light"` rejects a `let` variable that "obviously" holds `"dark"`—the variable's *type* is `string`, not the value it currently holds. The fixes are `as const`, an explicit annotation (`let b: "dark" | "light" = "dark"`), or a `const` binding. Widening is a deliberate ergonomic trade-off: it keeps everyday `let` mutation working without forcing annotations everywhere, at the cost of occasionally needing to opt back into precision.

#### Q34. [Theory] What is the difference between a type *annotation* and a type *assertion*, and why is `as` dangerous?

A type **annotation** (`const x: Foo = value`) asks the compiler to *check* that `value` is assignable to `Foo`—it is a verified claim. A type **assertion** (`value as Foo` or the legacy `<Foo>value`) *overrides* the compiler's inference and tells it "trust me, treat this as `Foo`"—no real check happens beyond a loose sanity guard. Assertions are an escape hatch: they let you assert a *more specific* or *less specific* type, but TypeScript blocks assertions between types it considers wholly unrelated (you must double-assert through `unknown` to force those, which is a loud red flag in review).

```typescript
const a: number = "5";        // ERROR — annotation is checked
const b = "5" as unknown as number;  // compiles — double assertion, a lie
const el = document.querySelector(".btn") as HTMLButtonElement; // legit-ish
```

The danger is that an assertion is a *compile-time-only* promise with **zero runtime enforcement**. `JSON.parse(raw) as User` compiles happily and then crashes downstream when the field you trusted is missing. Assertions are appropriate when *you* genuinely have information the compiler lacks—e.g. you know a DOM query returns a button because of surrounding markup—but they should be rare, localized, and ideally replaced by a real narrowing guard or runtime validator. The senior framing: an annotation makes the compiler your ally (it verifies), while an assertion makes it your accomplice (it believes). The `satisfies` operator (covered later) often gives you what people reach for `as` to do, but *with* checking.

#### Q35. [Theory] Why are TypeScript's types fully erased, and what are the consequences compared to languages with reified generics?

TypeScript types exist only at compile time; the emitter strips every annotation, interface, type alias, and generic parameter, producing plain JavaScript with no trace of the type layer. This is a foundational design choice driven by the goal of being a *transpiler to idiomatic JS* with zero runtime dependency—the output should look like hand-written JavaScript and impose no performance or bundle-size penalty. Contrast this with languages that **reify** generics or types at runtime: Java keeps some metadata (though it also erases generics), and C#/.NET fully reifies generics so you can do `typeof(T)` and `new T()` at runtime.

```typescript
function make<T>(): T { /* cannot do: new T(); typeof T */ throw 0; }
// At runtime there is no `T`. You must pass a constructor explicitly:
function makeReal<T>(ctor: new () => T): T { return new ctor(); }
```

The consequences are pervasive. You **cannot** branch on a type parameter (`if (T === string)`), instantiate a generic (`new T()`), or check `instanceof SomeInterface`—interfaces don't exist at runtime. Reflection-style metadata requires opt-in tooling (`reflect-metadata` with decorators) or passing runtime witnesses (a Zod schema, a class constructor, a discriminant tag). This is *why* discriminated unions need a literal `kind` field, *why* validation libraries pair a runtime schema with `z.infer`, and *why* dependency-injection frameworks lean on decorator metadata. The upside: erasure makes TypeScript a thin, predictable layer that never surprises you at runtime with type-system machinery—but it forces you to engineer runtime type information yourself wherever you need it.

### 🟡 Intermediate — extended

#### Q36. [Theory] Explain the `satisfies` operator (TS 4.9). How does it differ from a type annotation and from `as`?

`satisfies` lets you check that an expression conforms to a type **without widening the expression to that type**—you keep the precise inferred type while still getting validation. With a plain annotation `const x: T = ...`, the variable's type *becomes* `T`, discarding the more specific inferred shape. With `as T`, you get an unchecked override. `satisfies` is the missing third option: validate against `T`, but let `x` retain its narrow inferred type for downstream use.

```typescript
type Config = Record<string, string | number>;

// Annotation: validated, but loses per-key literal types
const a: Config = { port: 8080, host: "local" };
// a.port is string | number — too wide

// satisfies: validated AND keeps precise types
const b = { port: 8080, host: "local" } satisfies Config;
// b.port is number, b.host is string — exact

// as: NO validation — typo slips through
const c = { prot: 8080 } as Config; // compiles despite the typo
```

The key internal behavior: `satisfies` runs the assignability check (so a typo or wrong value type errors, unlike `as`) but does not change the static type of the expression (unlike an annotation). This makes it ideal for configuration objects, route tables, and palette maps where you want both "this must conform to the contract" and "but I still want to index into the exact keys and get literal types." A common pattern combines them: `const PALETTE = { ... } as const satisfies Record<string, string>`—`as const` freezes literals, `satisfies` validates the contract, and you keep everything. The rule of thumb: prefer `satisfies` over `as` whenever you want a constraint without sacrificing inference.

#### Q37. [Theory] Walk through how the compiler infers type arguments at a generic call site. What is contextual typing and inference priority?

When you call `identity(42)` without explicit type arguments, the compiler performs **type-argument inference**: it matches each argument against the corresponding parameter type that mentions the type parameter, collects *candidates* for each type variable, and then chooses a result. For a parameter `value: T` and argument `42`, the candidate for `T` is `number` (after widening, unless the parameter or context pins a literal). When a type variable appears in multiple positions, the compiler gathers all candidates and computes a "best common type"—often the union, or for return-position inference it may use a different priority.

```typescript
function pick<T>(a: T, b: T): T { return a; }
const r = pick("x", 1);        // T inferred as string | number
const arr = [1, "a", true];    // best common type: (string|number|boolean)[]

// Contextual typing flows the EXPECTED type INTO the expression:
const fn: (n: number) => number = x => x * 2; // x is number (from context)
[1,2,3].map(n => n.toFixed());  // n: number, inferred from map's signature
```

**Contextual typing** is the reverse direction: instead of inferring outward from an argument, the compiler pushes an *expected* type inward to give an un-annotated expression (like an arrow function's parameters) a type. That is why `.map(n => ...)` knows `n` is a number without annotation. Inference has a **priority system**: return-type positions and explicit annotations can outrank naive argument candidates, and the compiler prefers more specific candidates in certain positions. Edge cases that trip people up: inference can "fail" and fall back to the constraint (or `unknown`/`{}`) when nothing pins a variable; passing a generic function as an argument may defer inference; and recent versions improved inference for higher-order functions. Knowing this explains why adding an explicit type argument, reordering parameters, or annotating a callback sometimes unblocks an otherwise-confusing inference error.

#### Q38. [Theory] What is `const` type parameter (TS 5.0), and what problem does it solve?

A `const` type parameter—`function f<const T>(x: T)`—instructs the compiler to infer the **most specific (literal/readonly) type** for `T` at the call site, *as if the argument were written with `as const`*, without the caller having to add `as const` themselves. Normally inference widens: passing an array literal infers `string[]`, and object literals get widened property types. The `const` modifier flips that default for that parameter, preserving literal types and tuple-ness.

```typescript
function makeRoute<const T extends readonly string[]>(parts: T): T { return parts; }
const r = makeRoute(["users", "profile"]);
// Without const modifier: r is string[]
// With    const modifier: r is readonly ["users", "profile"]

declare function defineConfig<const T>(c: T): T;
const cfg = defineConfig({ mode: "dark", retries: 3 });
// cfg.mode is "dark" (literal), not string
```

This solves a long-standing library-author pain: you wanted callers to get precise literal types from helpers like `defineConfig`, route builders, or state machines, but you had to *document* "remember to add `as const`," which everyone forgot. The `const` modifier moves that burden to the API definition, so the ergonomics are correct by default. Caveats: it only deepens inference where it can—`const` parameters do not make *mutable* destinations readonly, and the modifier has no effect if the parameter's constraint or usage forces widening anyway. It is a precision tool for the inference engine, not a runtime guarantee (like everything else, it erases). It pairs conceptually with `satisfies` and `as const` as the three levers for controlling literal-vs-widened inference.

#### Q39. [Theory] Compare `import type` / `export type`, `isolatedModules`, and `verbatimModuleSyntax`. Why do they exist?

These features all address the gap between TypeScript's whole-program type knowledge and **single-file transpilers** (Babel, esbuild, SWC) that compile one file at a time with no cross-file type information. A single-file transpiler cannot know whether `import { Foo } from "./x"` refers to a *value* (must emit a real import) or a *type* (must be erased)—it never reads `./x`. If it guesses wrong, it either drops a needed runtime import or emits an import of something that doesn't exist at runtime.

```typescript
import type { User } from "./types";   // guaranteed erased — types only
import { type User, createUser } from "./mod"; // inline: User erased, createUser kept
export type { Config };                 // type-only re-export

// isolatedModules makes the compiler ERROR on patterns a single-file
// transpiler can't safely handle (e.g. re-exporting a type without `type`).
```

`import type` / `export type` are **explicit markers** that an import is type-only, so any transpiler can erase it safely. **`isolatedModules: true`** makes `tsc` *enforce* that your code is compatible with single-file transpilation—it flags constructs (like `const enum`, or re-exporting an ambiguous name) that would break under Babel/esbuild. **`verbatimModuleSyntax`** (TS 5.0, superseding the older `importsNotUsedAsValues`/`preserveValueImports`) tells the compiler to emit imports/exports *exactly as written*, erasing only those marked `type`—giving deterministic, transpiler-friendly output and forcing teams to be explicit. The "why": the modern toolchain splits **type-checking** (`tsc --noEmit`) from **emit** (esbuild/SWC for speed), and these flags make that split safe by removing the ambiguity a fast transpiler can't resolve on its own. Enabling `verbatimModuleSyntax` is now standard advice for new projects using a bundler.

#### Q40. [Theory] How does `target` and `lib` configuration cause down-leveling, and what are the runtime risks of mismatched settings?

`target` controls which ECMAScript version `tsc` *emits*: a lower target (`ES2015`, `ES5`) causes **down-leveling**—the compiler rewrites newer syntax (async/await, optional chaining, class fields, spread) into equivalent older code, sometimes pulling in helper functions (`__awaiter`, `__spreadArray`). `lib` controls which **type declarations** for built-in APIs are available (e.g. `ES2022.Array` to type `Array.prototype.at`, or `DOM` for browser globals). The two are independent but related: by default `lib` is derived from `target`, but you can override it.

```jsonc
{
  "compilerOptions": {
    "target": "ES2017",        // emit: async/await kept, but ?? down-leveled
    "lib": ["ES2022", "DOM"],  // types: assume ES2022 APIs exist at runtime
    "downlevelIteration": true // correct for-of/spread over iterables to ES5
  }
}
```

The dangerous mismatch is when **`lib` promises runtime APIs that the actual environment lacks**. Setting `lib: ["ES2022"]` while deploying to an old Node or browser means `array.at(-1)` *type-checks* but throws `TypeError: at is not a function` at runtime—because down-leveling only transforms *syntax*, never *library methods* (it does not polyfill `Array.prototype.at`, `Object.fromEntries`, `Promise.allSettled`, etc.). So `target`/syntax is handled by the compiler, but missing *runtime methods* require a separate polyfill (core-js) loaded before your code. A second subtlety: down-leveling class fields and `this` semantics changed with `useDefineForClassFields` (which flips to spec-compliant `[[Define]]` semantics at `ES2022`+ targets) and can silently alter behavior of getters/setters and decorators. The senior takeaway: `target` and `lib` encode assumptions about the deployment environment—set them to match your *lowest* supported runtime, and add polyfills for any API your `lib` enables but your runtime predates.

#### Q41. [Practical] Explain Node's `moduleResolution` strategies (`node10`/`node`, `node16`/`nodenext`, `bundler`). Why did `node16`/`nodenext` change the rules?

`moduleResolution` tells TypeScript how to turn `import "x"` into a file on disk, and it must *mirror* whatever your actual runtime/bundler does or you get phantom "works in tsc, breaks at runtime" failures. The legacy **`node`/`node10`** strategy emulates classic CommonJS resolution: try `.ts`/`.d.ts`, then `index`, walk up `node_modules`, ignore `package.json` `exports`, and allow extensionless relative imports. It does **not** understand the `exports`/`imports` fields or ESM/CJS conditions, so it mis-resolves modern dual-package libraries.

```jsonc
// ESM project on Node 16+
{ "compilerOptions": { "module": "nodenext", "moduleResolution": "nodenext" } }
```
```typescript
// Under nodenext, relative ESM imports REQUIRE the extension:
import { helper } from "./util.js";  // note .js even though the file is util.ts
```

**`node16`/`nodenext`** were introduced to model Node's *real* dual ESM/CJS algorithm: they read the package's `"type"` field and `"exports"` conditions, distinguish `import` vs `require` resolution, and—critically—**require explicit file extensions** in ESM relative imports (and you write `.js`, the *output* name, even though the source is `.ts`, because the import path is preserved verbatim into emit). This surprises people but is correct: Node ESM mandates extensions, so TypeScript stopped pretending otherwise. **`bundler`** (TS 5.0) is for esbuild/Vite/webpack: it understands `exports` conditions like `node16` but *relaxes* the extension requirement and other Node-specific rules, because the bundler resolves modules, not Node. Choosing wrong is a classic footgun—`node` resolution on a library that ships only `exports` will fail to find subpath imports, and `nodenext` on a bundler project will nag about extensions you don't need. Match `moduleResolution` to the tool that actually loads your code, and use `attw` to verify published packages resolve correctly under every consumer condition.

#### Q42. [Theory] What is the difference between `interface` extension and type `&` intersection at the type-system level, and why can they behave differently on conflicts?

Both `interface B extends A` and `type B = A & C` combine members, but the compiler handles them differently. **Interface extension** performs an eager assignability check: if a derived interface declares a property whose type *conflicts* with (is not assignable to) the base, you get an immediate error at the declaration. **Intersection** is lazier and more mechanical: it computes the intersection of the member types, and for conflicting primitive properties the result is the intersection of those types—which is often `never`—silently, without an error at the definition site.

```typescript
interface A { x: number }
interface B extends A { x: string } // ERROR: incompatibly overrides x

type C = { x: number } & { x: string }; // NO error here...
type X = C["x"];                          // X is `never` (number & string)
const c: C = { x: 1 as any };             // and C is effectively unconstructable
```

This difference flows from their roles. Interfaces are designed as *nominal-ish extensible contracts*, so the compiler eagerly validates the hierarchy and produces clearer error messages and better caching. Intersections are an *algebraic operation* on types—`A & B` means "has all members of both," and when two object types share a key the resulting key type is the intersection of the two value types, computed structurally and on demand. There are also performance and tooling consequences: the team's guidance is that `interface extends` is generally **faster to type-check and caches better** than large intersection chains, because extension creates a flat resolved type while intersections may be repeatedly re-evaluated. So beyond expressiveness, prefer `interface extends` for object hierarchies (clear conflict errors, performance) and reserve `&` for genuinely algebraic compositions (mixing in a mapped type, combining unions of objects) where you understand the conflict semantics.

### 🟠 Advanced — extended

#### Q43. [Theory] Explain the internals of distributive conditional types. Why does `T extends U ? X : Y` "loop" over a union, and how do you stop it?

A conditional type distributes over a union **only when the checked type is a "naked" (bare) type parameter**. When you write `type F<T> = T extends U ? X : Y` and instantiate `F<A | B | C>`, the compiler does not test the whole union against `U`; instead it splits the union and maps the conditional over each member, then unions the results: `F<A> | F<B> | F<C>`. This is the distribution rule, and it is the mechanism behind utility types like `Exclude` and `Extract`. The key trigger is *syntactic*: the type parameter must appear alone on the left of `extends`.

```typescript
type ToArray<T> = T extends any ? T[] : never;
type R = ToArray<string | number>; // string[] | number[]  (distributed!)

// Wrap in a 1-tuple to make T "non-naked" → distribution is DISABLED:
type ToArrayNoDist<T> = [T] extends [any] ? T[] : never;
type R2 = ToArrayNoDist<string | number>; // (string | number)[]
```

The classic gotcha is that `T extends never ? ... : ...` distributing over `never` (the empty union) produces `never` for the *whole* expression, which is why some utility types special-case it. Distribution is enormously useful—filtering union members (`Exclude<T, U> = T extends U ? never : T`), mapping each member, building lookup unions—but it bites when you intended whole-union semantics (e.g. checking "is this entire type assignable to X"). The canonical fix is **wrapping both sides in a tuple `[T] extends [U]`**, which makes `T` non-naked so the compiler tests the union as a single unit. Internally this matters for performance too: distributing over a 50-member union runs the conditional 50 times, so an accidental distribution inside a hot generic can balloon instantiation counts. Recognizing whether you *want* distribution and controlling it with the tuple-wrap trick is a hallmark of someone who understands the type evaluator, not just memorized utility types.

#### Q44. [Theory] How are variadic tuple types and labeled tuples implemented, and what new patterns do they enable (e.g. typing `curry`/`bind`)?

**Variadic tuple types** (TS 4.0) let a tuple type include a spread of *another* tuple type variable: `[A, ...T, B]` where `T` is a generic tuple. Internally this lets the compiler express "this tuple is some known elements plus an unknown middle/tail," and crucially it can *infer* the spread part. Combined with `infer` in conditional types and rest parameters typed as tuples (since function parameter lists are modeled as tuples), this unlocks precise typing for variadic, higher-order function combinators that were previously impossible without dozens of hand-written overloads.

```typescript
// Concatenate two tuples, preserving element types & positions
type Concat<A extends unknown[], B extends unknown[]> = [...A, ...B];
type R = Concat<[1, 2], [3, 4]>; // [1, 2, 3, 4]

// Type-safe partial application: peel known args, infer the rest
function partial<A extends unknown[], B extends unknown[], R>(
  fn: (...args: [...A, ...B]) => R,
  ...head: A
): (...rest: B) => R {
  return (...rest: B) => fn(...head, ...rest);
}
const add = (a: number, b: number, c: number) => a + b + c;
const add5 = partial(add, 5);     // (b: number, c: number) => number
const r2 = add5(10, 20);          // 35, fully typed
```

**Labeled tuple elements** (`[start: number, end: number]`) add names that surface in tooltips and parameter hints without changing the type—purely a DX/readability feature that makes rest-parameter signatures self-documenting. The implementation models function parameter lists *as* tuples, so `Parameters<F>` returns a tuple and you can transform it: prepend, append, slice, or map arguments at the type level. This is what makes truly type-safe `curry`, `bind`, `pipe`, `compose`, and decorator wrappers possible—each manipulates the argument tuple with spreads and `infer`. The cost is again compile-time: deeply recursive tuple manipulation (e.g. a fully general `curry` that peels one argument at a time) can hit the recursion-depth limit and slow checking, so production combinators usually cap arity or accept the whole tuple at once rather than recursing element-by-element.

#### Q45. [Theory] What are the compiler's recursion and instantiation limits (the "type instantiation is excessively deep" error), and how does the evaluator avoid infinite loops?

TypeScript's type system is **Turing-complete**, so to stay decidable in practice the checker imposes guard rails. The most visible is the recursive type-instantiation depth limit (commonly hit around 50 levels of nested generic instantiation, surfacing as *"Type instantiation is excessively deep and possibly infinite"*). There is also a separate limit on tail-recursive conditional types (raised to ~1000 with the tail-call optimization added in TS 4.5) and internal caps on the size of unions/intersections and the total instantiation count before the checker bails. These exist because a self-referential conditional or mapped type could otherwise expand forever.

```typescript
// Naive recursion hits the depth limit on long inputs:
type Length<T extends any[], C extends any[] = []> =
  T extends [any, ...infer Rest] ? Length<Rest, [...C, any]> : C["length"];

// TS 4.5 tail-call optimization: a conditional type that returns ANOTHER
// instantiation of itself in tail position is evaluated iteratively, not
// by stacking frames — so this counts much deeper before erroring.
```

Two mechanisms keep evaluation finite. First, **caching/memoization**: the checker caches the result of instantiating a type with given arguments, so structurally identical instantiations are not recomputed, which both speeds things up and breaks some cycles. Second, **tail-recursion elimination** (TS 4.5) for conditional types: when a conditional type's true/false branch is itself a single instantiation of a conditional type in tail position, the evaluator iterates instead of recursing, dramatically raising the practical depth and avoiding stack-style blow-ups. When you *do* hit the limit, the fixes are: restructure to tail-recursive form, reduce per-step work (avoid building large accumulator tuples), cap depth explicitly, or—pragmatically—replace the type-level computation with a simpler/wider type. The deeper lesson for staff engineers: because the system is Turing-complete, "can I express this in types?" is rarely the real question; "will it compile fast enough and stay maintainable?" is. Type-level programming should earn its keep at API boundaries, not become a sport.

#### Q46. [Theory] Explain TypeScript's assignability algorithm for object types and how it handles recursive/cyclic types without looping.

Assignability ("is `S` assignable to `T`?") for object types is fundamentally **structural and recursive**: for each member required by `T`, the corresponding member of `S` must exist and its type must be assignable (with parameters checked contravariantly, returns covariantly, and considering optionality/readonly). For unions, every constituent of the source must be assignable to the target; for the target being a union, the source must match at least one constituent. The check is *relational*, not nominal—names never matter. The subtlety arises with **recursive types** (`type Tree = { value: number; children: Tree[] }` or two mutually referential interfaces): a naive structural walk would recurse forever.

```typescript
interface Node { next: Node | null; value: number }
interface Other { next: Other | null; value: number }
const n: Node = {} as Other;  // OK — structurally identical, despite recursion
```

The compiler avoids infinite recursion with a **relationship cache and an in-progress assumption stack**: when it begins comparing a pair of types `(S, T)`, it records that comparison as "in progress." If, while recursing into their members, it encounters the *same* pair again (the cycle point), it **optimistically assumes the relationship holds**—co-inductive reasoning—rather than recursing again. If no contradicting evidence appears elsewhere in the comparison, the assumption stands and the types are deemed compatible; the result is then cached for reuse. This is why two independently declared but structurally identical recursive types are mutually assignable, and why deeply nested comparisons are fast on repeat (the cache). Two practical consequences: (1) extremely large or deeply generic object comparisons can still be slow because each *distinct* pair is real work, which is a common cause of editor lag—reducing union breadth and adding explicit annotations cuts the comparison count; and (2) the optimistic-cycle assumption is sound for the equality/subtype cases it targets but is part of why pathological hand-crafted types can occasionally produce surprising "these are assignable?!" results. Understanding the cache-and-assume model demystifies both the performance profile and the edge-case behavior of the checker.

#### Q47. [Theory] What changed with `useDefineForClassFields`, and why can class fields behave differently across `target` versions?

`useDefineForClassFields` controls *how* class field initializers are emitted, reflecting a real semantic split in JavaScript's history. The original TypeScript behavior emitted field initializers as simple **assignments** in the constructor (`this.x = value`)—"set" semantics. The finalized ECMAScript class-fields spec instead uses **`Object.defineProperty`** semantics (`[[Define]]`)—each field is *defined* on the instance, which runs even for fields without initializers (creating them as `undefined`) and does *not* trigger inherited setters. TypeScript flipped its default to the spec-compliant `define` behavior, and this flag is **automatically `true` when `target` is `ES2022` or higher** (and `false` for lower targets), which is why the *same source* can behave differently depending on `target`.

```typescript
class Base { get name() { return "base"; } set name(v: string) { console.log("set", v); } }
class Derived extends Base {
  name = "derived"; // define semantics: SHADOWS the accessor (no "set" log)
}                   // set  semantics: CALLS Base's setter (logs "set derived")
```

The breaking scenarios are concrete: (1) a subclass field with the same name as a base-class accessor now **shadows** the accessor instead of invoking its setter—a behavior change that broke some inheritance patterns; (2) fields declared but not initialized are now explicitly set to `undefined`, which can **clobber** values a parent constructor assigned via `this`; and (3) frameworks relying on the old "assignment into a decorator-defined property" pattern (older Angular/MobX setups) needed adjustments. This intersects with `strictPropertyInitialization` (which requires definite assignment of declared fields) and with `declare`-modifier fields (used to say "this field is defined elsewhere, don't emit it"). The senior takeaway: class-field semantics are one of the few places where TypeScript's *runtime* output meaningfully changed, it is `target`-coupled, and migrating a codebase up to `ES2022` requires auditing inheritance and uninitialized fields rather than assuming a pure no-op.

#### Q48. [Theory] How does excess property checking work, why does it only fire on "fresh" object literals, and how do people accidentally defeat it?

Structural typing says an object with *extra* properties is still assignable to a type that needs *fewer*—so strictly, `{ x: 1, y: 2 }` is assignable to `{ x: number }`. That would make typos in object literals (e.g. writing `colour` instead of `color`) silently pass. To catch this common mistake, TypeScript adds **excess property checking**: when a **fresh object literal** is assigned to (or passed as) a type that doesn't declare a given property, it errors—even though pure structural rules would allow it. The check is intentionally scoped to *fresh* literals because that is where typos originate; once the value is stored in a variable, it loses freshness and reverts to permissive structural rules.

```typescript
interface Opts { color?: string; width?: number }
function render(o: Opts) {}

render({ color: "red", colur: 1 }); // ERROR — excess prop 'colur' (fresh literal)

const tmp = { color: "red", colur: 1 };
render(tmp);                         // OK — `tmp` is not fresh; check skipped

render({ color: "red" } as Opts);    // OK — assertion suppresses the check too
```

The "freshness" concept is the internal mechanism: a literal type is *fresh* at its point of creation and the compiler attaches a flag that enables excess-property checking; assigning it to a variable, spreading it, or asserting it strips that flag. This explains the otherwise-mysterious inconsistency where inlining a literal errors but extracting it to a variable does not. People *accidentally* defeat the check by (a) introducing an intermediate variable, (b) using `as` (which is precisely why `satisfies` is preferred—`satisfies` *keeps* the check), or (c) having an index signature on the target type, which legitimizes any extra key. The correct mental model: excess property checking is a *lint-like heuristic layered on top of* structural typing, not a core typing rule—it is best-effort typo protection, and `satisfies` plus avoiding stray `as` keeps it working where it matters most.

#### Q49. [Practical] Explain how `this` typing works in TypeScript: `this` parameters, `ThisType<T>`, polymorphic `this`, and arrow-function capture.

JavaScript's `this` is dynamically bound by *call site*, which is a perennial source of bugs. TypeScript models `this` at the type level in several complementary ways. First, a function can declare a **fake first parameter named `this`** that is erased at emit but tells the checker what `this` must be when the function is called—so calling it with the wrong receiver, or passing the unbound method as a callback, errors.

```typescript
function handler(this: HTMLButtonElement, e: Event) { this.disabled = true; }
button.addEventListener("click", handler); // OK — this is the button
const f = handler; f(new Event("x"));      // ERROR — `this` would be wrong

class Box<T> { value!: T;
  clone(): this { return new (this.constructor as any)(); } // polymorphic `this`
}                                                            // subclasses keep their type
```

**Polymorphic `this`** (the `this` *type*) refers to "the type of the current instance, including subclasses," which is what makes fluent builder chains and `clone()`-style methods return the *derived* type rather than the base. **`ThisType<T>`** is a special marker interface (recognized intrinsically by the compiler, with no runtime effect) used in object-literal/mixin patterns to declare what `this` is *inside* methods—Vue's Options API and various "define methods on a context" APIs rely on it so that `this.someComputed` is typed correctly within method bodies. Finally, **arrow functions capture `this` lexically** and therefore *cannot* declare a `this` parameter; the compiler knows an arrow's `this` is the enclosing scope's, which is exactly why class fields assigned arrow functions (`onClick = () => this.x`) sidestep the unbound-method problem. The practical guidance: prefer arrow-function class fields for handlers passed as callbacks (lexical `this`, no binding bugs), use `this` parameters to document/enforce receivers on standalone functions and to forbid unsafe unbinding, reach for polymorphic `this` in fluent/inheritable APIs, and treat `ThisType<T>` as the tool for context-object/mixin libraries. Note that `strictBindCallApply` additionally type-checks `.bind`/`.call`/`.apply` against the real signature, closing another `this`-related hole.

### 🔴 Expert — extended

#### Q50. [Theory] Explain optional-variance annotations (`in`/`out`, TS 4.7). When are they needed and what do they buy you?

By default TypeScript **infers** the variance of a generic type parameter structurally—from how the parameter is used in the type's members (output positions → covariant, input positions → contravariant, both → invariant). This inference is correct but can be *expensive*: to decide whether `Box<Dog>` is assignable to `Box<Animal>`, the checker may have to structurally compare the full expansions. The **optional variance annotations** `out T` (covariant), `in T` (contravariant), and `in out T` (invariant) let you *declare* the intended variance explicitly. This serves two purposes: a **performance optimization** (the checker can short-circuit using the annotation instead of expanding the structure) and a **correctness assertion** (the compiler verifies your annotation matches actual usage and errors if you, say, mark something `out` but use it in an input position).

```typescript
interface Producer<out T> { get(): T; }          // covariant: Producer<Dog> ⊆ Producer<Animal>
interface Consumer<in T>  { set(v: T): void; }    // contravariant: Consumer<Animal> ⊆ Consumer<Dog>
interface Box<in out T>   { get(): T; set(v: T): void; } // invariant

// Compiler ERROR if annotation contradicts usage:
interface Bad<out T> { set(v: T): void; } // ERROR: T used in contravariant position
```

They are **needed** mainly in two situations: (1) large recursive generic types where variance inference is a measurable compile-time cost—annotating breaks the expensive structural recursion; and (2) as *documentation/guard rails* on a public generic API, so a future edit that accidentally changes variance (e.g. adding a setter to a producer) fails the build instead of silently altering assignability for every consumer. They are emphatically **not** something to sprinkle everywhere—inference is right by default, and incorrect annotations are caught, but redundant ones add noise. The nuance interviewers probe: variance annotations *describe and verify* variance, they do not *override* soundness—you cannot use `out` to make an actually-invariant type behave covariantly; the compiler rejects the lie. So the honest framing is "a performance hint plus a correctness contract," analogous to declaring `final`/`readonly` intent, not a mechanism to bend the type relation.

#### Q51. [Theory] Why is `Object.keys(obj)` typed as `string[]` and not `(keyof T)[]`, and what is the principled reason rather than a bug?

It looks like an oversight that `Object.keys({ a: 1, b: 2 })` returns `string[]` instead of `("a" | "b")[]`, but it is a *deliberate, principled* decision rooted in how structural typing interacts with runtime reality. Because TypeScript is structural, a value typed as `T` may at runtime have **more** properties than `T` declares—any supertype-shaped object is assignable. Consider passing `{ a: 1, b: 2, c: 3 }` to a function expecting `{ a: number; b: number }`: it is accepted (excess properties are fine once it is not a fresh literal), and inside that function `Object.keys` would return `["a","b","c"]` at runtime. If the signature claimed `(keyof T)[]` = `("a"|"b")[]`, the type would be a **lie**—you would then index back into a type that doesn't include `"c"` and get unsound results.

```typescript
interface Pt { x: number; y: number }
function f(p: Pt) {
  for (const k of Object.keys(p)) { /* k: string — honest */ }
  // If it were (keyof Pt)[], then p[k] would be number, but at runtime
  // `p` might be { x, y, z } and k could be "z" → unsound.
}
const big = { x: 1, y: 2, z: 3 };
f(big); // legal — extra "z" exists at runtime
```

The same reasoning explains why `for...in` gives `string` keys and why `Object.entries` is loosely typed. The "honest pessimism" of `string[]` reflects the open-world assumption: an object type is a *lower bound* on what's present, not an exact description. When you *know* the object is exact (a closed record you constructed), the idiomatic workaround is a typed helper—`Object.keys(obj) as (keyof typeof obj)[]`—accepting the localized assertion because you control the object's provenance, or a branded "exact" type. Some teams ship an `objectKeys<T>(o: T): (keyof T)[]` wrapper to centralize that assertion. The interview signal is recognizing this is the *correct* consequence of structural + open-world typing, not a defect—and being able to articulate exactly when it's safe to override.

#### Q52. [Theory] Compare TypeScript's type system with Flow and with sound-by-design gradual systems. What does "gradual typing" actually mean here?

**Gradual typing** is the academic framing for a system that lets typed and untyped code coexist, with a designated "dynamic" type (`any` in TypeScript) acting as the seam between the checked and unchecked worlds. The defining property in the literature is the *gradual guarantee*: adding or removing type annotations should not change a program's runtime behavior—and crucially, fully-typed regions are checked while interactions with `any` are permitted without ceremony. TypeScript embodies a *pragmatic*, **erasure-based** flavor of this: `any` is the dynamic type, types vanish at runtime, and the design explicitly prioritizes JavaScript compatibility and developer ergonomics over soundness (so it omits the runtime "casts/contracts" that academically-sound gradual systems insert at typed/untyped boundaries).

| Dimension | TypeScript | Flow | "Sound" gradual (academic) |
|---|---|---|---|
| Dynamic type | `any` (permissive) | `any` (permissive) + `mixed` (safe) | dynamic with runtime contracts |
| Soundness | intentionally unsound | closer to sound; stricter inference | sound by construction |
| Runtime checks | none (full erasure) | none (erasure) | inserts casts at boundaries |
| Inference | local + contextual | aggressive whole-program-ish | varies |
| Ecosystem | dominant; DefinitelyTyped | shrinking; Meta-internal | research / niche |

**Flow** (Meta) made different trade-offs: stronger, more aggressive inference and a more cautious stance toward soundness (e.g. `mixed` as a *safe* top type you must refine, analogous to TypeScript's later `unknown`; Flow had this earlier). Flow tended to catch some bugs TypeScript's pragmatism allows, but its whole-program inference could be slower and its error messages harder, and—decisively—the **ecosystem** consolidated around TypeScript (tooling, `@types`, editor support, hiring), so Flow's industry footprint collapsed even within projects that once championed it. The deeper point for a staff candidate: TypeScript is *not* trying to be sound; it occupies a sweet spot on the soundness-vs-adoption curve, accepting documented unsound holes (`any`, assertions, bivariance, covariant mutable arrays) in exchange for being a frictionless layer over real-world JavaScript. "Why did TypeScript win over Flow?" is partly technical (incrementality, tooling, declaration ecosystem) and substantially socio-technical (Microsoft's editor integration and the network effect of `@types`)—understanding both halves signals maturity beyond syntax.

#### Q53. [Theory] How do user-defined type guards, assertion functions, and the inferred type predicates of TS 5.5 differ in their effect on control-flow narrowing?

All three feed TypeScript's **control-flow analysis (CFA)**, but they inject information differently. A **user-defined type guard** is a function whose return type is a *type predicate* `x is T`; when it returns `true`, CFA narrows the argument to `T` in the consequent branch (and to the complement in the `else`). It is a *boolean-returning* probe. An **assertion function** declares `asserts x is T` (or `asserts condition`); it has no meaningful return value—instead, *if it returns at all* (doesn't throw), CFA narrows `x` to `T` for the **rest of the scope** after the call, modeling `invariant`/`assert`-style code.

```typescript
function isString(x: unknown): x is string { return typeof x === "string"; }
function assertString(x: unknown): asserts x is string { if (typeof x !== "string") throw new Error(); }

declare const v: unknown;
if (isString(v)) v.toUpperCase();      // narrowed inside the branch
assertString(v); v.toUpperCase();      // narrowed for the rest of the scope

// TS 5.5: this predicate is INFERRED — no explicit `x is string` needed:
const strs = ["a", null, "b"].filter(x => x !== null); // (string)[]  not (string|null)[]
```

The critical safety caveat shared by guards and assertion functions: the predicate is an **unchecked promise**. The compiler does *not* verify that `isString`'s body actually proves `string`-ness—you could write `return true` and lie, and CFA would trust it, reintroducing unsoundness exactly like an `as` cast (just hidden behind a function). **TS 5.5's inferred type predicates** close a long-standing ergonomic gap: when a function's body is a simple boolean expression that the compiler can *prove* corresponds to a narrowing (e.g. `x => x !== null` or `x => typeof x === "string"`), TypeScript now *infers* the `x is T` predicate automatically—so `.filter(Boolean)`-style and custom-filter callbacks finally narrow the result type without a hand-written predicate. The distinction matters in design: use a **guard** when you want a reusable boolean check that narrows per-branch; use an **assertion function** when failure should halt execution and you want narrowing to persist afterward (validation at boundaries, invariants); and lean on **inferred predicates** for inline callbacks where writing an explicit predicate was previously the only option. The expert nuance is articulating that hand-written guards/assertions are *trusted, not verified*—so they belong at carefully reviewed trust boundaries, ideally backed by the same runtime check the type claims.

#### Q54. [Theory] What is `--isolatedDeclarations` (TS 5.5) and why does it represent a strategic shift in how the compiler thinks about declaration emit?

Generating `.d.ts` files normally requires the *full* type-checker, because an exported function with an inferred return type forces the compiler to perform cross-file inference to figure out what type to write into the declaration. That coupling is a scalability bottleneck: declaration emit for one file can depend on type-checking an arbitrarily large graph of other files, so it cannot be parallelized or done per-file in isolation. **`isolatedDeclarations: true`** changes the contract: it *requires* that every exported symbol has enough **explicit annotations** that its declaration can be produced by looking at that **single file alone**, with no cross-file inference. If an export's type can't be determined locally, the compiler errors and tells you to add an annotation.

```typescript
// With isolatedDeclarations, this ERRORS — return type must be explicit:
export function build() { return { id: 1, name: "x" }; }
// Fix:
export function build(): { id: number; name: string } { return { id: 1, name: "x" }; }
```

The strategic significance is about **build architecture and the future toolchain**, not day-to-day app code. Once declarations are guaranteed file-local, `.d.ts` generation can be done by *third-party tools* (esbuild, SWC, the Go-based `tsgo`) **without reimplementing TypeScript's full type-checker**, and it can be **parallelized and cached per file**—a massive win for large monorepos where declaration emit was a serial bottleneck. It also makes `.d.ts` output more robust (no accidental leaking of unnameable/inferred types, fewer "the inferred type cannot be named" surprises). The cost is annotation burden—library authors must explicitly type their public surface—so it is opt-in and aimed primarily at **libraries and large internal packages**, not application leaf code. It fits a clear trajectory in the TypeScript team's thinking: decompose the monolithic compiler so that fast, language-agnostic tools can handle the mechanical parts (transpile, declaration emit) while `tsc` focuses on checking—the same philosophy behind `verbatimModuleSyntax`, the type/transpile split, and ultimately the native `tsgo`/TS 7 rewrite. Recognizing `isolatedDeclarations` as *infrastructure for a faster, more modular ecosystem* rather than a mere lint rule is the staff-level read.

#### Q55. [Theory] Explain how `unique symbol` and `const`-asserted symbols enable nominal-ish keys, and how this differs from string brands.

A `symbol` is a primitive guaranteed unique at runtime, but for the *type system* to treat two symbol-keyed declarations as distinct, you need **`unique symbol`**—a special type that can only be the type of a `const` (or `readonly static`) binding initialized to a `Symbol()`/`Symbol.for()` call. Each `unique symbol` is its own singleton *type*, so it can serve as a distinct, collision-proof property key and as a phantom marker. This is the mechanism behind robust **branded types**: rather than branding with a string literal (which two unrelated brands could in principle share), you brand with a `unique symbol` so the marker is unforgeable.

```typescript
declare const brand: unique symbol;          // its own type, distinct from any other
type UserId = string & { readonly [brand]: "UserId" };

const sym: unique symbol = Symbol("k");       // OK — const + Symbol()
// let bad: unique symbol = Symbol();         // ERROR — must be const

interface Registry { [sym]: number; }         // symbol-keyed, non-enumerable-friendly key
```

Compared to plain **string brands** (`T & { __brand: "UserId" }`), the `unique symbol` approach has three advantages: (1) the key cannot collide with an accidentally identical string literal elsewhere, since the symbol's *type identity* is unique, not its textual name; (2) symbol-keyed properties don't appear in normal `Object.keys`/JSON serialization, so the brand stays invisible at runtime and doesn't pollute the object's enumerable surface; and (3) it composes with `unique symbol` keys in interfaces for genuinely private-ish or framework-reserved slots. The trade-offs: `unique symbol` declarations are more verbose, the `const`-only restriction is strict (you cannot infer `unique symbol` for a `let` or a parameter), and for *most* branding use-cases a string brand is simpler and sufficient. The expert-level point is understanding *why* `unique symbol` exists—the type system needs a way to mint a fresh, un-aliasable nominal token in a structural world—and choosing it specifically when brand forgery or enumerability actually matter (security tokens, capability keys, framework-internal markers), while reaching for the cheaper string brand for ordinary ID confusion.

#### Q56. [Theory] How does TypeScript model `readonly` (arrays, properties, `ReadonlyArray`, `as const`), and why is `readonly` shallow and non-sound for aliasing?

TypeScript's `readonly` is a **compile-time-only** modifier that restricts *write* operations through a given reference; it does nothing at runtime (the underlying object is fully mutable) and it is **shallow**—`readonly` on a property prevents reassigning that property, not mutating the object the property points to. There are several related constructs: `readonly` on object properties, the `ReadonlyArray<T>` / `readonly T[]` type (exposes only non-mutating array methods, no `push`/`splice`/index-assignment), `Readonly<T>` (the shallow mapped utility), and `as const` (which makes every property `readonly` *and* narrows to literal types, recursively for nested literals).

```typescript
interface P { readonly id: string; tags: string[] }
const p: P = { id: "1", tags: [] };
// p.id = "2";          // ERROR — readonly property
p.tags.push("x");        // OK — readonly is SHALLOW; the array isn't readonly

const ro: readonly number[] = [1, 2, 3];
const rw: number[] = ro; // ERROR — readonly[] is NOT assignable to mutable[]
const widen: number[] = [1, 2]; const alias: readonly number[] = widen;
alias[0]; widen.push(4); // alias is "readonly" but the data mutated via `widen`
```

The crucial soundness gap is **aliasing**: `readonly` constrains writes *through one reference*, but it does not guarantee the data is immutable, because another mutable alias to the same object can still change it. Assigning a `readonly T[]` to a mutable `T[]` is *blocked* (that direction is unsound—you'd gain write access to something promised read-only), but the reverse (mutable → readonly) is *allowed*, and that allowance is exactly what creates the aliasing hole: the readonly view can observe mutations performed via the original mutable reference. So `readonly` communicates **intent and prevents accidental local mutation**, and it improves variance (readonly arrays are covariant-safe in a way mutable arrays are not), but it is *not* a deep-immutability or thread-safety guarantee. For genuine immutability you need either runtime `Object.freeze` (shallow, runtime-enforced), a recursive `DeepReadonly` type for compile-time depth, or persistent-data-structure libraries (Immer/Immutable.js) for enforced structural sharing. The senior framing: `readonly` is a *write-protection annotation on a reference*, not a property of the value—understanding that distinction explains both why it's shallow and why aliasing defeats it.

#### Q57. [Practical] Compare typing JavaScript via JSDoc (`checkJs`) against authoring `.ts`. When is JSDoc the right call, and what are its limits?

TypeScript can type-check plain `.js` files using **JSDoc annotations** when `allowJs` and `checkJs` are enabled (or per-file `// @ts-check`). The compiler understands a rich JSDoc dialect—`@type`, `@param`, `@returns`, `@typedef`, `@template` (generics), `@satisfies`, `@import`—and applies the *same* type system and `strict` flags it would to `.ts`. The output is just JavaScript (no transpile step needed), which is the whole appeal: you get most of TypeScript's checking without changing your build, file extensions, or shipping a compiler into the pipeline.

```javascript
// @ts-check
/** @typedef {{ id: string, name: string }} User */
/**
 * @param {User} user
 * @returns {string}
 */
function greet(user) { return `hi ${user.name}`; }
/** @type {User} */
const u = { id: "1", name: "Ada" };
greet(u); // fully checked, in a .js file
```

JSDoc typing is the **right call** when: you have a large existing `.js` codebase and want safety *before* committing to a migration; you ship a **build-tool-free** package (some libraries deliberately avoid a transpile step and still want to emit `.d.ts` via `tsc` from JSDoc); or you're in an environment (certain Node-native or Deno-adjacent setups, or "just run the file" scripts) where avoiding compilation is valuable. Notably, Node's experimental type-stripping and the broader "run TS-ish code directly" trend make annotation-light approaches more attractive. The **limits** are real: JSDoc is far more verbose for complex generics, conditional types, mapped types, and template-literal types (some advanced type-level programming is impractical or impossible to express); editor refactors and error messages are slightly less ergonomic; and certain TS-only syntax (parameter properties, `enum`, `namespace`, satisfies-in-all-positions historically) has no clean JSDoc equivalent. The pragmatic verdict: JSDoc is an excellent *gateway* and a legitimate *destination* for simple-to-moderate libraries and incremental migrations, but for applications with heavy type-level logic, the density and tooling advantages of `.ts` win—which is why most teams use JSDoc as a migration bridge (`checkJs` to surface errors early) and then convert hot files to `.ts` as they touch them.

#### Q58. [Theory] Why is the native `tsgo` (TypeScript 7) compiler being written in Go, and what does porting a structural, inference-heavy type-checker to a new language risk and require?

The TypeScript team announced a **native port of the compiler to Go** (codenamed `tsgo`, targeting the "TypeScript 7" line, with the existing TS 5.x JavaScript implementation continuing as "6.x" during transition), aiming for roughly an order-of-magnitude improvement in cold check time, memory, and editor responsiveness on large codebases. The bottleneck being addressed is fundamental: the current checker runs on a JavaScript engine, is single-threaded for the core checking work, and pays GC and megamorphic-dispatch costs that dominate on million-line monorepos—no amount of micro-optimization in JS closes a 10x gap. Go was chosen for pragmatic reasons the team stated: it offers **native compilation, real shared-memory concurrency** (so phases like file parsing and parts of checking can be parallelized), good control over memory layout, and—importantly—a structure close enough to the existing heavily-mutable, graph-based compiler that the port could be a *faithful translation* rather than a from-scratch redesign (which would risk years of subtle behavior drift). A from-scratch rewrite or a borrow-checker-heavy language (Rust) was judged riskier given the compiler's pervasive shared mutable state (symbol tables, the relationship cache, node graphs).

```bash
# Preview today (names/flags evolving): the native CLI and LSP
npx @typescript/native-preview --version
tsgo --noEmit            # native type-check, drop-in-ish for `tsc --noEmit`
# Editor: a Go-based language server replaces the TS-server process
```

The risks and requirements are exactly what a staff engineer should anticipate. **Behavioral fidelity** is paramount—any divergence in assignability, inference, or error output across millions of existing projects is a regression, so the port is validated against an enormous corpus and must reproduce even quirky edge cases. **Ecosystem coupling**: tools that *import* the TypeScript API in-process (ESLint's typed rules via `typescript-eslint`, ts-loader, ts-jest, API-based codegen) cannot simply call a Go binary; the team must provide a stable interop story (likely an out-of-process protocol/API), and this is one of the harder migration problems. **Feature freeze pressure**: during the port, new language features risk having to be implemented twice. And the **type system semantics themselves don't change**—`tsgo` is a faster *implementation* of the same structural, gradually-typed, intentionally-unsound system, not a new type theory; the soundness holes and inference rules persist by design. The interview signal is understanding that this is an *engineering* bet about performance and concurrency under faithful-behavior constraints—why Go over Rust or a clean rewrite, what breaks (in-process API consumers), and what explicitly does *not* change (the language semantics)—rather than treating "rewrite in Go" as a magic speed button.

#### Q59. [Theory] What is the difference between `void` and `undefined` as types, and why can a function typed `() => void` return a value?

`undefined` is a type with exactly one value: `undefined`. `void` is a type expressing the *absence of a meaningful return value*, and TypeScript gives it a special, deliberately permissive rule in **callback/contextual positions**: a function type whose return type is `void` is satisfied by a function that returns *anything*, and the returned value is simply ignored by the type system. This is **not** the case for `undefined`—a `() => undefined` callback must return `undefined` (or nothing). The `void`-return lenience exists for a concrete ergonomic reason that shows up constantly with array methods and callbacks.

```typescript
type VoidFn = () => void;
const f: VoidFn = () => 42;   // OK — returned 42 is ignored by the type
const r = f();                // r: void (you can't usefully consume it)

const src = [1, 2, 3]; const dst: number[] = [];
src.forEach(n => dst.push(n)); // push returns number; forEach wants void-cb → fine

type UndefFn = () => undefined;
// const g: UndefFn = () => 42; // ERROR — 42 is not undefined
```

The motivating case: `Array.prototype.forEach` expects a callback of type `(x) => void`. People routinely write `arr.forEach(x => list.push(x))`, where `push` returns a `number`. If `void` callbacks had to return literally nothing, this idiom—and countless others passing a value-returning function where the caller ignores the result—would error spuriously, forcing noise like `{ list.push(x); }`. So the rule "`void`-returning callback type accepts a function returning any type" is a targeted soundness relaxation that makes higher-order JavaScript pleasant. The subtle gotchas: (1) a *direct* function declaration annotated `(): void` may still not `return value;` explicitly in some configurations—the lenience is specifically about *assignability of function types* in contextual positions, not about writing an explicit `return expr` in a `void`-annotated body; (2) because a `void` *value* is essentially opaque, you should not try to consume the result of a `void` call; and (3) this interacts with the `no-misused-promises`/floating-promise lint rules, since an `async` function returns `Promise<void>` and passing it where a `() => void` is expected can silently swallow rejections. Understanding `void` as "I don't care what you return" versus `undefined` as "the value is literally `undefined`" is the crisp distinction interviewers want, along with the `forEach`/callback rationale.

#### Q60. [Theory] How does numeric/string `enum` actually compile, and what runtime and type quirks (reverse mapping, non-nominal string enums) bite people?

An `enum` is one of the few TypeScript constructs that **emits runtime code**, which is why it behaves unlike the rest of the (erased) type system. A numeric enum compiles to an IIFE that builds a *bidirectional* object: forward `Name → number` *and* a **reverse mapping** `number → Name`. A string enum builds only the forward mapping (no reverse, because the strings aren't guaranteed unique-invertible). This runtime footprint and the reverse map are the source of several quirks.

```typescript
enum Dir { Up, Down }            // numeric
// compiles ~to: { Up:0, Down:1, "0":"Up", "1":"Down" }  ← reverse mapping
Dir[0];                          // "Up"  (reverse lookup exists)
Object.keys(Dir).length;         // 4, not 2 (forward + reverse entries)

enum Color { Red = "RED", Green = "GREEN" } // string enum: forward only
// Color["RED"] is undefined — no reverse map

const x: Dir = 5;                // ALLOWED for numeric enums! (any number assignable)
const c: Color = "RED";          // ERROR — string enums are NOMINAL
```

The type-system quirks compound the runtime ones. **Numeric enums are not nominal**: any arbitrary `number` is assignable to a numeric enum type (`const d: Dir = 99` compiles), so they provide weak safety—a long-standing footgun. **String enums, by contrast, are nominal**: a raw string `"RED"` is *not* assignable to `Color` even if the values match, which is stronger but also surprising relative to TypeScript's otherwise-structural nature (string enums are one of the rare nominal constructs in the language). Add the `isolatedModules`/bundler incompatibility of `const enum` (it relies on whole-program inlining, which single-file transpilers can't do, so esbuild/Babel either error or need special handling), the bundle-size cost of the emitted object, and the iteration confusion from reverse-mapped keys, and you get the modern consensus: prefer **`as const` objects + derived union types** (covered earlier), which are erasable, tree-shakeable, structurally typed, and free of reverse-mapping surprises. Reach for `enum` only when you specifically want its named-namespace runtime object or are matching an existing framework convention. The interview depth is being able to *predict the emitted shape* and explain *why* numeric-vs-string enums differ in nominality and reverse mapping.

#### Q61. [Theory] Explain the order of operations in a mapped type with modifiers, key remapping, and homomorphic behavior. Why does `Partial<T>` preserve `readonly` but `Record<K,V>` doesn't?

A mapped type `{ [K in Keys]: ValueType }` iterates a union `Keys` and produces a property per member. Several features layer on top, and their interaction has subtle internals. **Modifiers** add or strip `readonly` and `?` using `+`/`-` prefixes (`-readonly`, `-?` remove; bare `readonly`/`?` add). **Key remapping** via `as` (`[K in Keys as NewKey]`) renames or—when the new key is `never`—*filters out* keys. The order conceptually is: enumerate keys → apply `as` remap (possibly dropping) → compute value type → apply modifiers.

```typescript
type Mutable<T>      = { -readonly [K in keyof T]: T[K] };       // strip readonly
type Optional<T>     = { [K in keyof T]+?: T[K] };               // add optional
type NoFns<T>        = { [K in keyof T as T[K] extends Function ? never : K]: T[K] };

interface Src { readonly id: string; name?: string }
type P = Partial<Src>;  // { readonly id?: string; name?: string } — readonly KEPT
```

The deep concept is **homomorphic mapped types**: when the mapped type has the exact form `{ [K in keyof T]: ... }` (mapping over `keyof T` of a *generic* `T`), the compiler treats it as *homomorphic*—it **preserves the original modifiers** (`readonly`, `?`) of `T`'s properties and even preserves tuple/array-ness. That is why `Partial<T>` (defined `{ [K in keyof T]?: T[K] }`) keeps each property's existing `readonly` and only *adds* optionality, why `Readonly<T>` keeps optionality, and why `Pick<T, K>` (also homomorphic) preserves modifiers. By contrast, **`Record<K, V>`** is defined as `{ [P in K]: V }` where `K` is an *arbitrary* key union, **not** `keyof` some source object—so it is **not** homomorphic, has no source modifiers to copy, and produces plain required, writable properties. This homomorphic/non-homomorphic distinction also governs whether mapping over an array yields an array (homomorphic) or a plain object, and whether `as`-remapping (which makes the mapping non-homomorphic over the original keys) still carries modifiers. Knowing this explains otherwise-mysterious behavior: "why did my custom mapped type lose `readonly`?"—almost always because it mapped over a remapped or non-`keyof T` key set and thus dropped out of the homomorphic fast path.

#### Q62. [Theory] What is the `infer` keyword doing internally, and how do constraints (`infer R extends string`) and multiple `infer` sites interact?

`infer` introduces a **fresh type variable inside the `extends` clause of a conditional type**, instructing the checker to *solve* for that variable by structurally matching the checked type against the pattern—type-level pattern matching / unification. When you write `T extends Promise<infer R> ? R : never` and apply it to `Promise<number>`, the checker unifies `Promise<R>` with `Promise<number>`, binds `R = number`, and selects the true branch. The variable is scoped to that conditional type and only usable in its true branch.

```typescript
type Awaited1<T> = T extends Promise<infer R> ? R : T;
type Tail<T extends any[]> = T extends [any, ...infer Rest] ? Rest : [];

// Constrained infer (TS 4.7): only matches if the inferred type fits the bound
type FirstString<T> = T extends [infer H extends string, ...any] ? H : never;
type A = FirstString<["x", 1]>; // "x"
type B = FirstString<[1, 2]>;   // never — head isn't a string

// Multiple infer of the SAME name in covariant positions → UNION;
// in contravariant (parameter) positions → INTERSECTION:
type Cov<T> = T extends { a: infer U; b: infer U } ? U : never;       // union
type Con<T> = T extends { f: (x: infer U) => void; g: (x: infer U) => void } ? U : never; // intersection
```

Two advanced behaviors are frequent interview probes. **Constrained `infer`** (`infer R extends string`, TS 4.7) lets the pattern match *conditionally on a bound*: the branch only succeeds if the inferred type satisfies the constraint, and it also lets the compiler narrow the inferred type (useful for, e.g., inferring a numeric-string and treating it as a number). **Multiple `infer` with the same name** resolves by variance position: when the same `infer U` appears in multiple **covariant** (output) positions, the results are **unioned**; when it appears in multiple **contravariant** (parameter/input) positions, they are **intersected**—mirroring the variance rules of the underlying positions. This is the same machinery that makes `Parameters`/`ReturnType`/`ConstructorParameters` work and that powers function-overload and tuple manipulation tricks. The internal mental model—`infer` = "create a hole and unify the pattern against the real type, respecting variance"—lets you reason about why a given extraction returns a union vs. an intersection, and why constrained `infer` sometimes makes a previously-failing match succeed (or vice versa).

#### Q63. [Practical] Compare strategies for typing a function with many input/output shape relationships: overloads vs. conditional return types vs. generic discriminated arguments. What are the trade-offs?

A common API need is "the return type depends on the arguments." TypeScript offers three idioms, each with distinct internals and ergonomics. **Function overloads** declare multiple call signatures followed by a single permissive implementation signature; the *implementation* signature is invisible to callers, and the compiler picks the **first matching** overload top-to-bottom. **Conditional return types** put a single generic signature whose return is a conditional type computed from a type parameter. **Discriminated generic arguments** make the input itself a discriminated union and use generics/overloads to map each discriminant to its result.

```typescript
// 1) Overloads — great for a small, fixed set of distinct shapes
function parse(x: string): string[];
function parse(x: number): number;
function parse(x: string | number): string[] | number {  // impl (hidden)
  return typeof x === "string" ? x.split("") : x * 2;
}

// 2) Conditional return type — scales to a relationship, not a fixed list
function wrap<T>(x: T): T extends string ? string[] : T[] {
  return (typeof x === "string" ? x.split("") : [x]) as any; // impl needs a cast
}

// 3) Discriminated argument
type Req = { kind: "user"; id: string } | { kind: "post"; slug: string };
```

The trade-offs are concrete. **Overloads** give the *cleanest signatures and best error messages* and are ideal when there's a small, enumerable set of unrelated input→output mappings; but they don't compose well (no relationship between cases), the implementation body is **unchecked against the public overloads** (a notorious unsoundness—your impl can violate the overloads and the compiler won't catch it inside the body), and adding cases is O(n) hand-written signatures. **Conditional return types** express a genuine *relationship* that scales over an open domain and compose with generics, but they almost always force a `return ... as any`/assertion in the implementation (the checker can't verify the body satisfies the conditional), the inference can be fragile, and error messages degrade. **Discriminated arguments** keep everything in the value domain and narrow naturally, but push complexity onto callers (they must supply the discriminant). Practical guidance: prefer **overloads** for a handful of fixed shapes (DOM `createElement`-style APIs), reach for **conditional return types** only when the mapping is parametric/open-ended and the ergonomics justify the implementation cast, and favor **discriminated unions** when the variants are data the caller already has. In all three, remember the recurring caveat—the *implementation* is trusted, not verified against the public types—so back the public contract with tests at the boundary.

#### Q64. [Theory] How does TypeScript type asynchronous code: the `Awaited<T>` type, recursive thenable unwrapping, and why `await` on a non-promise is allowed?

`async`/`await` is typed around a few coordinated rules. An `async` function *always* has return type `Promise<T>` regardless of whether you write `return value` or `return Promise.resolve(value)`—the compiler normalizes both, and importantly it **flattens nested promises** so `async () => Promise.resolve(1)` is `Promise<number>`, not `Promise<Promise<number>>`, matching the JS runtime which never produces a promise-of-a-promise. The `await` expression's type is computed by the **`Awaited<T>`** utility (made intrinsic and recursive in TS 4.5), which models exactly the runtime's recursive "thenable unwrapping": it peels `Promise`/thenable layers until it reaches a non-thenable.

```typescript
type A = Awaited<Promise<Promise<number>>>;     // number (recursively unwrapped)
type B = Awaited<number>;                       // number (await on non-promise is fine)
type C = Awaited<Promise<string> | boolean>;    // string | boolean (distributes)

async function f(): Promise<number> { return Promise.resolve(1); } // not Promise<Promise<number>>
const v = await Promise.resolve(42);            // v: number
const w = await 42;                             // ALLOWED — w: 42 (await on a plain value)
```

The recursive unwrapping in `Awaited<T>` is implemented as a conditional type that checks whether `T` is a thenable (`{ then(onfulfilled): ... }`), and if so recurses on the value passed to `onfulfilled`—precisely mirroring how the ECMAScript `await`/`Promise` resolution procedure adopts the state of a returned thenable. This is *why* you cannot accidentally end up with `Promise<Promise<T>>` in well-typed code and why `Promise.all`/`Promise.resolve` types collapse layers. **`await` on a non-promise is deliberately allowed** because the JS spec permits it (`await 42` simply schedules a microtask and yields `42`), so forbidding it would diverge from runtime semantics and break generic code that may or may not receive a promise; `Awaited<number>` is just `number`. The practical gotchas this knowledge prevents: forgetting that `await` on a value still introduces a microtask boundary (ordering implications); the `no-floating-promises` lint concern when a `Promise` is created but not awaited (types alone won't flag it—the value is "used" as a value); and the `useUnknownInCatchVariables`/`strict` interaction where a rejected awaited promise surfaces as an `unknown` in `catch`, forcing you to narrow the error. Being able to explain `Awaited`'s recursive definition and tie it to the runtime resolution algorithm is the depth interviewers look for.

#### Q65. [Theory] What is the difference between `{}`, `object`, `Object`, and `unknown` as types, and why is `{}` such a famous footgun?

These four "broad" types are constantly confused, and the differences are precise. **`{}`** means "any non-`null`, non-`undefined` value"—*not* "an empty object." Because virtually everything except `null`/`undefined` (numbers, strings, booleans, functions, arrays, objects) has *zero required properties*, all of them satisfy `{}`. **`object`** (lowercase) means "any non-primitive"—objects, arrays, functions—but *excludes* `number`, `string`, `boolean`, `symbol`, `bigint`, `null`, `undefined`. **`Object`** (capital, the global interface) describes anything with `Object.prototype` members (`toString`, `hasOwnProperty`, …), which—because primitives are boxed—accepts nearly everything except `null`/`undefined`, behaving almost like `{}` and is essentially never what you want. **`unknown`** is the proper top type: it accepts *everything* (including `null`/`undefined`) but lets you do *nothing* with it until you narrow.

```typescript
let a: {}      = 42;        // OK — number satisfies {} (not null/undefined)
let b: {}      = "str";     // OK
// let c: {}   = null;      // ERROR — null/undefined excluded
let d: object  = 42;        // ERROR — number is a primitive
let d2: object = { x: 1 };  // OK
let e: unknown = null;      // OK — unknown accepts literally everything
// e.toFixed();             // ERROR — must narrow first (this is the point)
```

`{}` is a famous footgun precisely because developers *read* it as "an empty object literal type" and expect it to reject primitives or to mean "an object with no useful properties," when it actually means "almost anything." Annotating a parameter `{}` to mean "some object" silently accepts `42`, `"foo"`, and `true`; using `{}` as a generic default or constraint (`<T = {}>`, `<T extends {}>`) lets primitives through and is why some lint rules (e.g. `@typescript-eslint/no-empty-object-type`, formerly `ban-types`) flag bare `{}`. The correct choices: use **`unknown`** for "I don't know the type yet, force me to check" (the safe top type—almost always what you want at boundaries), **`object`** for "any non-primitive," **`Record<string, unknown>`** for "an object with arbitrary string keys," and a precise interface when you actually know the shape. The crisp summary interviewers want: `{}` ≈ "non-nullish anything," `object` = "non-primitive," `Object` ≈ `{}` (avoid), `unknown` = "true top, must narrow"—and `{}` should almost never be written deliberately.

#### Q66. [Practical] When generic inference produces a surprising or too-wide type, what knobs do you have to steer it, and how do you decide between them?

Inference "going wrong" is rarely a compiler bug—it's the engine applying widening, best-common-type, and contextual rules in a way that doesn't match your intent. A staff engineer keeps a toolbox of *steering knobs* and knows the cost of each. The levers, roughly from least to most intrusive: **explicit type arguments** at the call site (`f<string>(x)`) override inference entirely; **`as const`** on the argument pins literals and tuple-ness; a **`const` type parameter** (`<const T>`) bakes that pinning into the API so callers don't need `as const`; **`satisfies`** validates against a contract without widening the variable; **explicit return-type annotations** on functions stop the checker from inferring (and re-inferring) across the boundary and also speed up checking; **generic defaults** (`<T = Foo>`) supply a fallback when inference can't determine `T`; and **constraints** (`<T extends Bar>`) bound the candidate set.

```typescript
function tuple<T extends unknown[]>(...xs: T): T { return xs; }
const a = tuple(1, "x");                 // (string | number)[]  — widened
const b = tuple(1, "x") as const;        // error-prone / awkward on a call

function tup2<const T extends unknown[]>(...xs: T): T { return xs; }
const c = tup2(1, "x");                  // readonly [1, "x"] — pinned by API

type Cfg = Record<string, number>;
const cfg = { a: 1, b: 2 } satisfies Cfg; // validated, keys/values stay literal
```

The decision framework: if *you* the caller want precision once, reach for an **explicit type argument** or **`as const`**; if you're the **library author** and want callers to get precision *by default*, encode it with a **`const` type parameter** (and document constraints)—pushing the fix into the API is almost always better than asking every caller to remember `as const`. Use **`satisfies`** whenever the goal is "conform to a shape but keep my exact inferred type." Add **explicit return types** on exported functions both to control inference *and* for compile performance and stable `.d.ts` output (and they're mandatory under `isolatedDeclarations`). Use **defaults** for optional type parameters and **constraints** to both restrict and *enable* member access inside the generic body. The anti-patterns to avoid: papering over a bad inference with `as any` (loses all safety) or adding gratuitous explicit type arguments everywhere (defeats inference's value and creates churn). The senior signal is treating inference as a *steerable* system with a cost-ordered set of interventions—and preferring fixes that live in the API definition over fixes every call site must repeat.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q67. [Practical] What is the difference between `@ts-ignore`, `@ts-expect-error`, and `@ts-nocheck`, and which should you reach for?

All three are comment directives that suppress type errors, but they differ in scope and—critically—in whether they *rot silently*. **`// @ts-ignore`** suppresses any error on the *next line* and says nothing if there turns out to be no error. **`// @ts-expect-error`** also suppresses the next line's error, but it *expects* one: if the line later stops erroring (because you or a dependency fixed the underlying issue), `@ts-expect-error` itself becomes an error ("Unused '@ts-expect-error' directive"). **`// @ts-nocheck`** at the top of a file disables type-checking for the *entire file* (and `// @ts-check` does the inverse in a `.js` file).

```typescript
// @ts-expect-error — known bad arg until the upstream type is fixed
doThing(42);          // if doThing later accepts number, THIS line flags as stale

// @ts-ignore
doThing(42);          // suppresses forever; if it stops erroring, you never know
```

The practical guidance is to **always prefer `@ts-expect-error`** because it is self-cleaning: it forces a review the moment the suppression is no longer needed, so suppressions don't accumulate as invisible debt. `@ts-ignore` is appropriate only in rare cases where the line legitimately *might or might not* error across environments (e.g. conditional on TS version), where an "expect" would itself be flaky. `@ts-nocheck` is a blunt instrument reserved for generated files or a temporary migration escape hatch—never sprinkle it on hand-written code. A strong team convention is to lint-ban `@ts-ignore` entirely (`@typescript-eslint/ban-ts-comment` with `ts-expect-error: allow-with-description`), require a trailing explanation after every directive, and grep for them periodically. The reason this matters in production: a stale `@ts-ignore` on a line whose API later changed shape will silently hide a *new, real* bug, whereas `@ts-expect-error` would have surfaced it the moment the original error disappeared.

#### Q68. [Practical] What do `noEmit`, `emitDeclarationOnly`, and `noEmitOnError` do, and how do they fit a modern build pipeline?

These three emit-control flags reflect the modern split between *type-checking* and *transpilation*. **`noEmit: true`** tells `tsc` to type-check but produce **no output files**—you run it purely as a verifier. This is the standard setup when a faster tool (esbuild, SWC, Babel, Vite, or the bundler) does the actual JS emit, and `tsc --noEmit` runs in CI and editor as the source of type truth. **`emitDeclarationOnly: true`** emits *only* `.d.ts` files and no `.js`—used by libraries whose JavaScript is produced by a bundler but whose *types* still come from `tsc` (the one tool that can correctly generate declarations). **`noEmitOnError: true`** makes `tsc` refuse to write any output if there are type errors, so you never ship JS built from broken types.

```jsonc
// App: tsc only checks, esbuild emits
{ "compilerOptions": { "noEmit": true, "strict": true } }

// Library: bundler emits JS, tsc emits ONLY the types
{ "compilerOptions": { "emitDeclarationOnly": true, "declaration": true, "outDir": "dist" } }
```

The pipeline rationale is performance and separation of concerns. Type-checking is the slow, single-threaded part of TypeScript; transpilation (just erasing types and down-leveling syntax) is embarrassingly parallelizable and tools like esbuild do it 20–100x faster because they *don't type-check at all*. So the productive arrangement is: esbuild/SWC for instant dev rebuilds and production bundles, `tsc --noEmit` for correctness in a parallel CI job and the editor. The trap to avoid is letting the transpiler be your only gate—esbuild will happily emit code with type errors because it never checks, so without a `tsc --noEmit` gate (or `noEmitOnError` if `tsc` *is* your emitter) type errors reach production. For libraries, `emitDeclarationOnly` plus a bundler is the common shape, increasingly paired with `isolatedDeclarations` to make even the `.d.ts` step fast and parallel.

#### Q69. [Practical] You set `"strict": true` but a value is still typed `any`. Where does `any` sneak in despite strict mode, and how do you catch it?

`strict` enables `noImplicitAny`, which stops the compiler from *silently* defaulting un-inferable values to `any`—but it does **not** ban `any` itself. Several `any` sources survive strict mode entirely: **explicit `any`** that someone wrote; **`any` from untyped third-party libraries** (a dependency whose types are `any` or missing); **`any` flowing in from `JSON.parse`** (typed `any` by `lib.d.ts`), `fetch().json()` (`Promise<any>`), `document.querySelector` casts, and the catch variable historically; **`any` produced by type assertions** (`x as any`); and **`any` that propagates**—once a value is `any`, every expression derived from it is also `any`, often far from the original source. Strict mode catches *implicit* `any` at declaration sites, not these.

```typescript
const data = JSON.parse(raw);   // data: any — strict does NOT flag this
data.user.name.length;          // all any, no errors, crashes at runtime

function f(lib: UntypedThing) { return lib.doStuff(); } // return: any, silently
```

To actually find and stop `any`, you layer **lint rules on top of `strict`**: `@typescript-eslint/no-explicit-any` (bans the literal keyword), `@typescript-eslint/no-unsafe-assignment` / `no-unsafe-member-access` / `no-unsafe-call` / `no-unsafe-return` / `no-unsafe-argument` (the "no-unsafe-\*" family, which require *type-aware* linting via `parserOptions.project` and catch `any` *propagating* through your code even when the keyword never appears), and `no-explicit-any`'s cousin for assertions. These typed rules are what flag `JSON.parse(raw).user`—the keyword `any` is nowhere in your code, but the value is `any` and the rule sees it. The senior framing: `strict` is necessary but not sufficient; `any` is a *value-flow* problem, and you need the type-aware ESLint rules (which run the type-checker and inspect inferred types) plus discipline at the boundaries (validate `JSON.parse`/`fetch` output into a real type) to keep `any` from leaking in and metastasizing.

#### Q70. [Theory] What are ambient declarations (`declare`), and how do `declare global`, triple-slash directives, and `.d.ts`-only files relate?

The **`declare`** keyword introduces an *ambient declaration*: it tells the compiler "a value/type/module with this shape exists at runtime, but I'm not defining it here—don't emit anything for it." It is how you describe things that exist in the runtime environment but have no TypeScript source: globals injected by a script tag (`declare const VERSION: string`), Node/browser globals, or the API surface of a plain-JS library. Ambient declarations live in `.d.ts` files (which contain *only* declarations and emit no JS) or inside `declare` blocks in `.ts` files.

```typescript
// globals.d.ts — describe runtime-injected globals
declare const __BUILD_HASH__: string;          // ambient global value
declare function gtag(...args: unknown[]): void;

declare global {                                // augment the GLOBAL scope from a module
  interface Window { dataLayer: unknown[]; }
}
export {};                                      // makes the file a module so `declare global` is required

/// <reference types="node" />                  // triple-slash: pull in another declaration set
```

The pieces fit together as the *declaration layer* of the language. A **`.d.ts` file** is pure types—no implementation—used to describe ambient runtime shapes or to ship a library's public types. **`declare global { ... }`** is used *inside a module* (a file with an `import`/`export`) to reach back out and augment the global scope—needed because once a file has top-level `import`/`export` it is a module and its `declare`s are module-scoped, not global, so `declare global` is the explicit opt-out (this is why you often see a lone `export {};` to force module status). **Triple-slash directives** (`/// <reference path="..." />`, `/// <reference types="..." />`, `/// <reference lib="..." />`) are the *pre-modules* mechanism for one declaration file to depend on another or to pull in a `@types` package or a built-in lib; they're now mostly superseded by `import`/`tsconfig` `types`, but you still see `/// <reference types="node" />` in published `.d.ts` files. The mental model: `declare` = "trust me, this exists at runtime"—it shifts the burden of truth onto you, which is exactly why hand-written ambient declarations can drift from reality and why generated declarations (from real source) are safer.

### 🟡 Intermediate — extended

#### Q71. [Practical] Your editor and your CI `tsc` disagree—one shows an error the other doesn't. What causes "works in editor, fails in build" (and vice versa)?

This classic discrepancy almost always traces to the editor and the CLI **resolving different TypeScript versions, different `tsconfig` files, or different file sets**. VS Code ships its own bundled TypeScript and uses it by default; your `package.json` may pin a different version under `node_modules`. If the editor uses TS 5.4's bundled build while CI runs the workspace's 5.6, a feature, inference change, or new error in one version won't appear in the other. The fix is to make the editor use the workspace version ("TypeScript: Select TypeScript Version" → "Use Workspace Version", or `"typescript.tsdk": "node_modules/typescript/lib"` in `.vscode/settings.json`) so everyone—and CI—runs the same compiler.

```jsonc
// .vscode/settings.json — force the editor to use the project's TS, not its bundled one
{ "typescript.tsdk": "node_modules/typescript/lib" }
```

```bash
npx tsc --version          # what the CLI uses
# Command palette → "TypeScript: Select TypeScript Version" shows the editor's
```

Other common causes: (1) **different `tsconfig`**—the editor picks the nearest `tsconfig.json` to the open file, while CI may run `tsc -p tsconfig.build.json` with stricter flags or a different `include`; reconcile by ensuring the build config extends the editor's base. (2) **`include`/`exclude` drift**—a file open in the editor might not be in the build's program at all (so the editor checks it with default options while the build ignores it, or vice versa). (3) **stale editor program**—the TS language server caches; after big `node_modules` or config changes, "TypeScript: Restart TS Server" resolves phantom errors. (4) **`skipLibCheck` mismatch**—if CI checks lib types and the editor effectively doesn't (or different `@types` are installed locally vs. CI's clean install), `.d.ts` errors appear in only one place. (5) **case-sensitivity**—a macOS/Windows dev imports `./Foo` that resolves on a case-insensitive FS but fails on Linux CI (mitigate with `forceConsistentCasingInFileNames`, which is on under `strict`). The debugging discipline: pin and align the TS version first, confirm both run the *same* `tsconfig`, then reproduce CI locally with the exact `tsc -p ...` command CI uses rather than relying on the editor's implicit project.

#### Q72. [Practical] How do `baseUrl` and `paths` work, why don't path aliases work at runtime by default, and how do you make them work everywhere?

`paths` (with `baseUrl`, though TS 5.0 allows `paths` without `baseUrl`) defines **module-resolution aliases** for the *type-checker*: you map an import specifier pattern to one or more on-disk locations so you can write `import { x } from "@/utils"` instead of `../../../utils`. The crucial, frequently-painful fact is that **`paths` only affects how `tsc` resolves types—it does NOT rewrite the emitted import strings**. TypeScript leaves `import "@/utils"` verbatim in the output JS, so at runtime Node (or the browser) has no idea what `@/utils` means and throws "Cannot find module".

```jsonc
// tsconfig.json
{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"], "@lib": ["src/lib/index.ts"] }
  }
}
```

```typescript
import { format } from "@/utils/format"; // resolves for tsc; runtime needs help
```

So `paths` must be **mirrored in every tool that actually runs or bundles your code**. Bundlers (webpack `resolve.alias`, Vite `resolve.alias`, esbuild alias plugin) need the same map; test runners need it (Jest `moduleNameMapper`, Vitest inherits Vite's); and for *running compiled output under Node* you need a runtime resolver (`tsconfig-paths` / `tsc-alias` to rewrite the emitted paths, or Node's own `imports`/subpath-imports via `package.json` `"imports": { "#lib/*": "./src/lib/*" }`, which Node *does* understand at runtime). The most robust modern approach for libraries is often to use Node's native **subpath imports (`#`-prefixed)** in `package.json`, which both TypeScript and Node honor, instead of TS-only `paths`. The trade-off: `paths` gives clean imports and is purely a DX nicety, but it introduces a *configuration-duplication* hazard—the aliases live in `tsconfig`, the bundler config, *and* the test config, and they silently drift. Many teams keep a single source (e.g. generate the bundler/jest alias map from `tsconfig.paths`) to avoid the "works in `tsc`, fails at runtime/test" trap, which is exactly the symptom of forgetting that `paths` is a compile-time-only fiction.

#### Q73. [Theory] Explain the two declaration spaces in TypeScript (value space vs. type space) and how a single name can live in both.

TypeScript has **two independent namespaces**: the **value space** (things that exist at runtime—variables, functions, classes, enum members) and the **type space** (things that exist only at compile time—interfaces, type aliases, type parameters). A given identifier can be declared in *one or both*. This is why `class Foo` and `enum Bar` create entries in **both** spaces (a class is a runtime constructor *and* an instance type; an enum is a runtime object *and* a type), while `interface` and `type` live *only* in type space, and `const`/`let`/`function` live *only* in value space.

```typescript
class Point { x = 0; y = 0; }
const p: Point = new Point();   // first Point = TYPE; second Point = VALUE (ctor)

interface Color { r: number }   // type space only
const Color = { red: "#f00" };  // value space only — SAME NAME, no conflict

type T = typeof Color;          // `typeof` bridges value → type
type Inst = InstanceType<typeof Point>; // typeof Point (value) → its type
```

Understanding which space a name lives in explains a lot of otherwise-baffling behavior. **`typeof` (in a type position)** is the bridge *from* value space *to* type space: it takes a runtime value and yields its type, which is why `keyof typeof someObject` works. The operators are space-aware: `keyof` operates on types, `typeof` (type-level) reads from values, and in `extends` clauses an identifier is interpreted in type space. The same-name coexistence is intentional and useful—a class merges with an interface (declaration merging) to add instance members; a function merges with a namespace to add "static" properties; an enum merges with a namespace. It also produces the classic errors: "`'Foo' refers to a value, but is being used as a type here`" (you wrote a `const` where a type was expected—fix with `typeof Foo`) and "`'Foo' only refers to a type, but is being used as a value here`" (you used an `interface`/`type` at runtime, which is impossible because it's erased). The senior insight: keeping the two spaces straight is the key to reading TypeScript's errors precisely and to knowing when you need `typeof` to cross from a runtime value into the type world.

#### Q74. [Practical] How do you correctly type the `catch` clause variable, and what changed with `useUnknownInCatchVariables`?

In JavaScript you can `throw` *anything*—a string, a number, `null`, an object, not just `Error`. Historically TypeScript typed the `catch (e)` variable as **`any`**, which let you write `e.message` with no error and then crash when someone threw a string. The **`useUnknownInCatchVariables`** flag (enabled by `strict` since TS 4.4) changes the default catch type to **`unknown`**, forcing you to *narrow* before touching the error—an honest reflection of the fact that you cannot know what was thrown.

```typescript
try {
  doRisky();
} catch (e) {                       // e: unknown (under strict)
  // e.message;                      // ERROR — must narrow first
  if (e instanceof Error) {
    console.error(e.message);        // OK — narrowed to Error
  } else {
    console.error("non-Error thrown:", e);
  }
}

// You may also annotate explicitly (only `unknown` or `any` are allowed):
try {} catch (e: unknown) {}
```

The correct pattern is to **narrow with `instanceof Error`** (or a custom guard) and have an explicit fallback for the non-`Error` case—because real-world code, libraries, and even some runtimes throw non-`Error` values (rejected promises with strings, framework sentinels, `DOMException`). A common helper is a `toError(e: unknown): Error` normalizer that wraps non-errors in `new Error(String(e))` so downstream code has a consistent shape. Two subtleties: (1) you can only annotate the catch variable as `unknown` or `any`—not `Error`—because TypeScript can't *guarantee* the thrown value is an `Error`, so it won't let you assert it via annotation (you must narrow). (2) With `async`/`await`, a rejected promise surfaces through the same `catch`, so the `unknown` discipline applies to async error handling too. The takeaway: `useUnknownInCatchVariables` is one of the most valuable strict sub-flags precisely because "`e.message` on a non-Error" is a real production crash; treating the caught value as `unknown` and narrowing is the safe, honest pattern.

#### Q75. [Theory] What is the difference between `tsc` type-checking and tools that merely "strip types" (Babel, esbuild, SWC, Node's `--experimental-strip-types`)? What can go wrong relying only on stripping?

`tsc` does two jobs: **type-checking** (the slow, whole-program semantic analysis) and **emit** (turning `.ts` into `.js`). Type-strippers—Babel's `@babel/preset-typescript`, esbuild, SWC, and Node's built-in `--experimental-strip-types` / `--experimental-transform-types`—do **only the second job, per file, without ever checking types**. They parse a single file, delete the type annotations, transform any TS-specific syntax, and emit JS. They are dramatically faster precisely *because* they never build the cross-file type graph or run assignability checks. This means a type-stripper will **happily emit code with type errors**: a wrong argument type, a missing property, a misused generic—none of it stops the build.

```typescript
function add(a: number, b: number): number { return a + b; }
add("1", "2");   // esbuild/Babel/Node: emits fine. tsc --noEmit: ERROR.
```

The things that go wrong relying *only* on stripping fall into two buckets. First, **no safety**: type errors reach production because nothing checks them—so you must run `tsc --noEmit` (or `tsgo`) somewhere (CI, pre-commit, editor) as the actual gate. Second, **single-file transpilers can't handle constructs that need whole-program knowledge**, which is exactly what `isolatedModules` exists to flag: `const enum` (needs inlining across files), certain re-exports of types without `import type`, and namespace/`enum` merging edge cases. Node's native type-stripping goes further and *only erases*—it does **not transform**—so by default it rejects TS-specific *runtime* syntax like `enum`, `namespace`, parameter properties (`constructor(private x: number)`), and legacy decorators unless you opt into the heavier transform mode; the philosophy is "erasable types only, output stays valid JS." The practical architecture this dictates: pick a fast stripper for emit/dev, set `isolatedModules: true` (and ideally `verbatimModuleSyntax: true`) so `tsc` warns about anything the stripper can't safely handle, write **erasable** TypeScript (prefer `as const` objects over `enum`, avoid `namespace`), and keep `tsc --noEmit` as the non-negotiable correctness check. The mental model: strippers make TypeScript *run fast*; only `tsc` makes it *correct*—you need both, doing their separate jobs.

#### Q76. [Practical] How do you type React component props, generic components, and `children` idiomatically, and what are the common typing mistakes?

Typing React in TypeScript centers on a few idioms. A component is a function whose first argument is a **props object**, typed with an `interface`/`type`; you generally type props *directly* on the parameter rather than using `React.FC`, because `React.FC` historically forced an implicit `children` prop, complicated generics, and broke `defaultProps` inference. For `children`, use `React.ReactNode` (the widest "anything renderable" type) rather than `JSX.Element` (too narrow—rejects strings, arrays, `null`).

```typescript
interface ButtonProps {
  label: string;
  onClick: (e: React.MouseEvent<HTMLButtonElement>) => void;
  children?: React.ReactNode;        // explicit, not implied
}
function Button({ label, onClick, children }: ButtonProps) {
  return <button onClick={onClick}>{label}{children}</button>;
}

// Generic component: the type parameter flows through props
interface ListProps<T> { items: T[]; render: (item: T) => React.ReactNode; }
function List<T>({ items, render }: ListProps<T>) {
  return <>{items.map((it, i) => <div key={i}>{render(it)}</div>)}</>;
}
<List items={[1, 2, 3]} render={(n) => n.toFixed()} />; // T inferred as number
```

The common mistakes: (1) using `React.FC` and then fighting its forced `children` and poor generic ergonomics—modern guidance is to annotate the props parameter directly. (2) Typing `children` as `JSX.Element` (breaks on text/arrays) instead of `React.ReactNode`. (3) Typing event handlers with bare `Event` instead of the *specific* synthetic event (`React.ChangeEvent<HTMLInputElement>`, `React.MouseEvent<HTMLButtonElement>`)—the specific type gives you `e.target.value` typed correctly. (4) Mistyping `useRef`: `useRef<HTMLDivElement>(null)` yields `RefObject<HTMLDivElement>` (read-only `.current`, correct for DOM refs) versus `useRef<number>(0)` for a mutable value (`MutableRefObject`)—conflating them causes "Cannot assign to 'current'" errors. (5) Forgetting that `useState` infers from the initial value, so `useState(null)` infers `null` and rejects later real values—you need `useState<User | null>(null)`. For generic components, note JSX syntax can make `<T>` ambiguous in `.tsx` files (the parser may read `<T>` as a JSX tag), so you write `<T,>` (trailing comma) or `<T extends unknown>` to disambiguate. The senior signal is knowing *why* to avoid `React.FC`, reaching for `React.ReactNode` for children, and using the precise synthetic-event and ref types so the DOM-facing surface stays fully typed.

### 🟠 Advanced — extended

#### Q77. [Practical] A production incident: a `@types/*` version bump silently broke types across the monorepo. How do `@types` packages, transitive type dependencies, and version conflicts cause this, and how do you prevent it?

`@types/*` packages (from DefinitelyTyped) are **versioned independently of the libraries they describe** and are *runtime-invisible*—they only affect compilation. Several failure modes flow from this. First, **transitive `@types` duplication**: two of your dependencies can each pull a *different* major of `@types/node` or `@types/react`, and depending on hoisting you end up with conflicting global declarations (two `process` globals, two `React` namespaces) that produce baffling "Subsequent property declarations must have the same type" or "Duplicate identifier" errors. Second, **a `@types` patch bump can tighten a type** (DefinitelyTyped fixes are not "breaking" by semver convention but absolutely break *your* code), so an unpinned `^` range silently upgrades on a fresh `npm install`/CI and a green build turns red with no code change of yours.

```jsonc
// package.json — pin types exactly and dedupe the big globals
{
  "devDependencies": { "@types/node": "20.11.30", "@types/react": "18.2.79" },
  "overrides": { "@types/react": "18.2.79" }   // force a single version everywhere
}
```

```bash
npm ls @types/react           # find every version & who pulled it in
npm dedupe                    # collapse duplicates where ranges allow
```

Prevention is layered. **Pin `@types/*` exactly** (no `^`) or use a lockfile you trust and **`overrides`/`resolutions`** to force a single version of the global-defining types (`@types/node`, `@types/react`) across the whole tree—duplicate *global* declarations are the worst offenders because they merge across the program. **Match the `@types` major to the runtime/library major** (using `@types/node@20` while running Node 18, or `@types/react@18` with React 17, injects APIs that don't exist or signatures that don't match). Run **`npm ls @types/...`** during the incident to see who introduced the rogue version. Consider libraries that **bundle their own types** (no separate `@types` needed) to shrink this surface. And gate upgrades with a CI `tsc --noEmit` on a clean, locked install so a transitive `@types` drift fails *that* PR rather than randomly later. The staff-level read: `@types` are an *implicit, transitive, separately-versioned* dependency layer that the lockfile doesn't fully tame for globals—treat the big global type packages as singletons you pin and override, and never let `^` ranges on `@types` ride into CI unpinned.

#### Q78. [Theory] Explain the "dual package hazard" and how ESM/CJS interop, `esModuleInterop`, and `__esModule` affect both runtime behavior and types.

The **dual package hazard** arises when a library ships *both* a CommonJS and an ESM build and a dependency graph ends up loading **both copies** of the same package—because Node resolves the CJS `require` path and the ESM `import` path to *different files*. You then get two separate module instances: two copies of a class (so `instanceof` fails across them), two separate module-level singletons/caches, and duplicated state. This is a *runtime* correctness bug that types alone can't see, and it's endemic to libraries mid-migration that expose both formats under `exports` conditions.

```jsonc
// package.json conditional exports — the surface where the hazard lives
{
  "exports": {
    ".": {
      "import": { "types": "./dist/index.d.ts",  "default": "./dist/index.mjs" },
      "require": { "types": "./dist/index.d.cts", "default": "./dist/index.cjs" }
    }
  }
}
```

On the **types side**, the parallel problem is wrong `.d.ts` resolution. Under `node16`/`nodenext` module resolution, TypeScript follows the *same* `exports` conditions Node does, so an ESM consumer must get `import`-condition types and a CJS consumer the `require`-condition types; if a library ships only one `.d.ts` (or mislabels conditions), consumers get types that don't match the code they actually load—the exact class of bug `@arethetypeswrong` (`attw`) was built to catch. On the **interop side**, **`esModuleInterop: true`** changes how `tsc` emits CJS↔ESM glue: it lets you write `import express from "express"` (default import) against a CJS module that uses `module.exports =`, by emitting an interop helper that checks the `__esModule` marker and synthesizes a default. The `__esModule` boolean is the runtime flag transpilers stamp onto CJS output to signal "this was originally ES modules," so importers know whether `module.exports` *is* the default or *has* a `.default`. Without `esModuleInterop`, you'd be forced into `import * as express` and namespace-call gymnastics that don't match how the package is meant to be consumed. The practical guidance: prefer **single-format ESM-only** publishing when you can to *eliminate* the dual hazard; if you must dual-publish, keep package-level state out of module scope (or share it via a separate state package), provide correct per-condition `.d.ts` (`.d.ts` + `.d.cts`), enable `esModuleInterop` (and `allowSyntheticDefaultImports` for type-only checking), and **verify the published artifact with `attw`** so you catch resolution mismatches before consumers do.

#### Q79. [Practical] How do you debug a confusing type error that produces a giant, deeply-nested message? What tools and techniques shrink it to something actionable?

Massive type errors—pages of nested `Type 'X' is not assignable to type 'Y'` with deeply expanded generic types—are usually one small mismatch buried inside a large structural comparison. The first technique is **read the error bottom-up / inside-out**: TypeScript reports the outermost relation first and then drills into *why*; the *last*, most-indented line is typically the actual culprit ("Types of property 'foo' are incompatible: 'string' is not assignable to 'number'"). Don't try to parse the whole tree—jump to the deepest leaf.

```typescript
// Force the compiler to print a type explicitly so you can SEE what it inferred:
type Expand<T> = T extends infer O ? { [K in keyof O]: O[K] } : never;
type Debug = Expand<TheConfusingType>;   // hover shows the resolved shape

// @ts-expect-error to confirm WHERE the error originates, then bisect.
```

The toolbox, in order: (1) **Hover and "Expand" helpers**—an `Expand<T>`/`Prettify<T>` mapped type forces the checker to resolve aliases and intersections into a flat, readable object so a tooltip shows the real shape instead of `A & B & Omit<...>`. (2) **Annotate intermediate steps**—assign the value to a `const x: ExpectedType = ...` at the point you *think* the type is right; the error now fires at that line with a smaller, local comparison instead of propagating to a distant call site. (3) **`tsc --pretty` and error codes**—every error has a `TSxxxx` code; searching the code finds canonical explanations, and `--pretty` (default) gives the caret/context view. (4) **Bisect the union/generic**—if the error involves a big union or generic, temporarily replace parts with `any`/`unknown` or narrow the generic to one case to find which constituent breaks. (5) **`--noErrorTruncation`** so the compiler stops abbreviating types with `...` and shows the full type (verbose but necessary when the relevant detail is in the truncated part). (6) **`--generateTrace` / TypeScript's "type trace"** and editor "Go to type definition" to see where a type originates. (7) For inference confusion, add an **explicit type argument** to see whether the problem is inference vs. an actual incompatibility. The meta-skill: a giant error is a *structural diff* the compiler is narrating; your job is to localize it—shrink the program (annotate, bisect, pin generics) until the error is small, fix the leaf, then remove the scaffolding. Production teams also wrap genuinely-useful-but-ugly public generics in named helper types so consumer errors say `BadConfig` instead of a 40-line expanded intersection.

#### Q80. [Theory] How does TypeScript model getters/setters with different types (TS 5.1), index signatures (string vs number vs symbol vs template), and the interaction with `noImplicitAny` on index access?

TypeScript can model several object-access patterns precisely. **Divergent get/set types** (TS 5.1): a property can have a *getter* that returns one type and a *setter* that accepts a *wider* type, modeling real APIs (e.g. a CSS property you set with `string | number` but always read back as `string`). Before 5.1 the get and set types had to match; now they can differ as long as the get type is assignable to the set type.

```typescript
interface Style {
  get width(): string;          // read: always a string
  set width(value: string | number); // write: accept number OR string
}

// Index signatures: key kind matters
interface Dict {
  [key: string]: number;        // string index
  [key: `data-${string}`]: number; // template-literal index (TS 4.4)
}
const d: { [k: number]: string } = ["a", "b"]; // numeric index ~ array-like
```

**Index signatures** come in flavors with distinct semantics. A **string index signature** `[k: string]: V` says *any* string key maps to `V`—and it constrains all named properties to also be assignable to `V`. A **number index signature** `[k: number]: V` models array-like access; since JS object keys are really strings, TypeScript requires the number index's value type to be assignable to the string index's if both exist. **Symbol** and **template-literal index signatures** (`[k: \`data-${string}\`]: V`, TS 4.4) let you type pattern-restricted keys—useful for `data-*`/`aria-*` attribute bags or prefixed config keys. The critical interaction is with **`noUncheckedIndexedAccess`** (and the older `noImplicitAny` on index access): by default, reading `dict["missing"]` is typed as `V` even though it's `undefined` at runtime—an unsound hole. With `noUncheckedIndexedAccess`, that access becomes `V | undefined`, forcing a check, because the index signature is a *promise about shape*, not a guarantee the key exists. There's also a `noImplicitAny` angle: indexing an object that has *no* index signature with a dynamic string (`obj[someStr]`) errors under strict settings because the result would implicitly be `any`—the fix is to add an index signature, use `keyof`-typed keys, or a typed `Record`. The senior framing: index signatures describe *open-world* key spaces (any matching key *might* exist), so the honest typing requires `| undefined` on read; divergent get/set types and template-literal indexes are precision tools for matching real-world APIs (DOM, attribute maps) without falling back to `any`.

#### Q81. [Practical] How would you architect shared types across a TypeScript monorepo—project references vs. path aliases vs. a published internal package—and what are the trade-offs?

In a monorepo, multiple packages need shared types (DTOs, domain models, API contracts). There are three main architectures, and the choice drives build speed, correctness, and editor behavior. **(A) Project references** (`composite: true` + `references` + `tsc -b`): each package is its own TS *project* with a built `.d.ts` + `.tsbuildinfo`, and downstream packages consume the *built declarations* of upstream ones. `tsc -b` builds them in dependency order, incrementally, only rebuilding what changed. **(B) Path aliases** (`paths` mapping `@app/shared` → `../shared/src`): a single big program where imports resolve straight to *source*, no build step between packages. **(C) A published internal package** (versioned, via a private registry or workspace protocol): `shared-types` is a real dependency with a version, built and installed like any npm package.

```jsonc
// (A) Project references — consumer tsconfig
{
  "compilerOptions": { "composite": true },
  "references": [{ "path": "../shared" }]   // build & type via shared's emitted .d.ts
}
```

The trade-offs: **Project references** give the best *scalability and correctness*—incremental builds, enforced dependency boundaries (you can't import something a package doesn't declare a reference to), and the editor only re-checks touched projects, so big repos stay responsive; the cost is configuration complexity, you must *build* upstream packages before downstream type-checks see changes (a stale `.d.ts` shows old types), and "go to definition" lands on `.d.ts` not source unless `declarationMap` is on. **Path aliases to source** are *simplest* and give instant cross-package changes with "go to definition" into real source and no build ordering—but they put *everything in one giant program*, so type-checking is slower at scale, there's no boundary enforcement (any package can import any file), and the aliases must be mirrored in the bundler/test config (the runtime-resolution problem from the `paths` question). **A published internal package** gives the cleanest boundaries and versioning (great when teams need to consume *stable* contracts and tolerate a publish step) but the slowest iteration loop (build → publish/link → install) and version-skew headaches. The pragmatic pattern most large repos converge on: **workspaces (pnpm/npm/yarn) + project references with `declarationMap` for go-to-source**, often with `tsc -b` in CI and a fast transpiler for dev—getting incrementality and boundaries while keeping editor navigation usable. Reserve pure `paths`-to-source for smaller repos where simplicity beats scale, and reserve published packages for contracts shared *across* repos, not within one.

#### Q82. [Theory] Compare runtime-validation/type-derivation libraries: Zod vs. class-validator/class-transformer vs. io-ts/Effect Schema vs. TypeBox. What design axes distinguish them?

These libraries all bridge TypeScript's compile-time types and runtime reality, but they make different bets on the **direction of truth** and the **programming style**. The key axis is *schema-first vs. type-first vs. class-first*. **Zod** (and Valibot, ArkType) are **schema-first**: you write a runtime schema and *derive* the static type via `z.infer<typeof S>`, so the validator is the single source of truth and the type can never drift. **class-validator/class-transformer** are **class-first**: you decorate class properties (`@IsEmail()`, `@IsInt()`) and validate instances—popular in NestJS because it fits the decorator/DI/OOP model and integrates with DTOs, but it relies on `experimentalDecorators` + `reflect-metadata`, validates *class instances* (not plain objects without transformation), and the type and the decorators are declared separately (mild drift risk).

```typescript
// Zod: schema-first, type derived
const User = z.object({ id: z.string().uuid(), age: z.number().int() });
type User = z.infer<typeof User>;          // derived — cannot drift

// class-validator: class-first, decorators are the rules
class UserDto { @IsUUID() id!: string; @IsInt() age!: number; }

// TypeBox: schema-first AND emits JSON Schema
const T = Type.Object({ id: Type.String(), age: Type.Integer() });
```

Further axes: **(1) JSON-Schema compatibility**—**TypeBox** is designed so its schemas *are* JSON Schema (great for OpenAPI/Fastify/AJV pipelines where you need the schema in a standard format and ultra-fast AJV-compiled validation), whereas Zod's schema is bespoke (though `zod-to-json-schema` exists). **(2) Functional purity & composability**—**io-ts** (and **Effect Schema**, its spiritual successor in the Effect ecosystem) take an **fp-first** approach: codecs are composable, decoding returns an `Either`/`Effect` instead of throwing, and they integrate with typed-error railway programming—powerful and principled but with a steeper learning curve and historically rougher error messages. **(3) Bundle size & performance**—Valibot and ArkType emphasize tree-shakeable, smaller bundles and (ArkType) near-native validation speed; Zod prioritizes ergonomics and DX over minimal size; TypeBox + AJV wins raw throughput by compiling validators. **(4) Transform/coercion & error UX**—Zod has rich `.transform()`, `.refine()`, coercion, and good default error objects; class-validator's errors are structured for HTTP APIs. The decision framework: **Zod** as the default for app-level boundary validation with derived types and great DX; **class-validator** when you're in NestJS/decorator-heavy DTO land; **TypeBox** when you need JSON-Schema/OpenAPI interop and maximum validation speed; **Effect Schema/io-ts** when you've adopted functional, typed-error architecture and want decoding to compose with the rest of an `Effect` program. The unifying principle to articulate: prefer **schema-first derivation** (`infer` the type from the validator) wherever possible so there is exactly one source of truth—the rest is choosing the style and interop story that matches your stack.

### 🔴 Expert — extended

#### Q83. [Theory] What does it mean that TypeScript's type system is Turing-complete, and what are the practical and theoretical consequences for tooling and the team?

That TypeScript's type system is **Turing-complete** means you can, in principle, encode arbitrary computation purely in types—people have built type-level parsers, a SQL query validator, arithmetic, even a chess move validator, all evaluated by the type-checker at compile time using recursive conditional types, mapped types, and template-literal types as the "instructions." Theoretically this implies the type-checking problem is, in the general case, **undecidable**—there exist type programs for which the checker cannot determine in bounded time whether they terminate—which is *precisely why* the compiler imposes the practical guardrails discussed earlier (instantiation-depth limits ~50, tail-recursion caps ~1000, and the "Type instantiation is excessively deep and possibly infinite" error). The limits are not arbitrary timidity; they're the compiler refusing to chase a potentially non-terminating computation.

```typescript
// Type-level addition via tuple length — computation in the type system itself
type Tuple<N extends number, A extends unknown[] = []> =
  A["length"] extends N ? A : Tuple<N, [...A, unknown]>;
type Add<A extends number, B extends number> =
  [...Tuple<A>, ...Tuple<B>]["length"] extends infer R ? R : never;
type Seven = Add<3, 4>; // 7 — arithmetic done entirely at compile time
```

The practical consequences shape engineering decisions. **(1) Compile time is a resource you can blow.** Because the type system computes, a clever recursive type can turn a sub-second check into a multi-second one, and unlike runtime code this cost is paid by *every developer's editor and every CI run*, forever. **(2) "Can I express this in types?" is almost never the real constraint—"should I?" is.** Since the system is Turing-complete, the answer to "is it possible" is usually yes; the staff-level judgment is whether the type-level computation pays for itself versus a runtime check or a simpler/wider type. **(3) Maintainability and bus-factor.** Type-level programs are write-once-read-never for most teams—debugging a recursive conditional type is brutal (no debugger, opaque errors), so a baroque type system becomes an organizational single-point-of-failure. **(4) Tooling fragility.** Deeply recursive types interact badly with editor responsiveness, `tsserver` memory, and incremental builds. The mature posture: treat Turing-completeness as a *capability to be rationed*, deploy type-level computation only at high-leverage *library API boundaries* where it buys real safety/ergonomics for many consumers, cap recursion explicitly, measure with `--extendedDiagnostics`, and otherwise prefer the boring, fast, legible type—because the goal is a system the whole team can maintain at speed, not a proof that the type checker can compute Fibonacci.

#### Q84. [Practical] How do you author and maintain *function overloads* safely given that the implementation signature is unchecked, and what alternatives reduce that risk?

Function overloads let callers see several precise call signatures while a single, hidden *implementation signature* does the work. The notorious hazard is that **the implementation body is type-checked against the implementation signature, not against the public overloads**—so the compiler will *not* catch a body that returns the wrong type for a given overload. You can declare `f(x: string): number` and have the body return a `string` for the string case, and TypeScript stays silent because the permissive implementation signature (`string | number => string | number`) is satisfied.

```typescript
function len(x: string): number;
function len(x: unknown[]): number;
function len(x: string | unknown[]): number {
  // BUG the compiler won't catch: returns string for the array case
  return Array.isArray(x) ? (x as any).join("") : x.length; // join() returns string!
}
const n = len([1, 2]); // typed as number, but is actually a string at runtime
```

Safe-authoring discipline: **(1) Order overloads most-specific first**—the compiler picks the *first* matching signature top-to-bottom, so a too-general signature placed first will shadow more specific ones. **(2) Keep the implementation signature as a faithful superset** and *narrow inside the body* with real runtime checks (`typeof`, `Array.isArray`) so each branch genuinely produces the type the corresponding overload promises. **(3) Back every overload with a unit test** that asserts both the runtime value *and* (via `expectTypeOf`/`tsd`/`@ts-expect-error`) the *type* at each call shape—because the type contract is exactly what the compiler isn't verifying. **(4) Avoid `as any` in the body** where possible; each cast is a place the overload promise can silently break. The alternatives that reduce or eliminate the risk: **(a) discriminated-union parameters** keep everything in the value domain so the compiler *does* check each branch ("if `kind === 'a'` then narrow and the return is checked"); **(b) a single generic with a conditional return type** expresses a *relationship* rather than a hand-maintained list (still needs an implementation cast, but scales and composes); **(c) separate, differently-named functions** (`parseString`, `parseArray`) when the cases are truly unrelated—often the simplest, most honest design, trading the unified name for full checking. The senior framing: overloads are an *ergonomics layer with a soundness hole at the implementation*; use them for small fixed sets of genuinely distinct shapes (DOM `createElement`-style), guard them with type-level tests, and prefer discriminated unions when you want the compiler to actually verify the body.

#### Q85. [Theory] What is the difference between covariant and invariant positions when *mutability* is involved, and why is `Array<Dog>` assignable to `Array<Animal>` even though it's unsound?

Variance is about when `Container<Sub>` is assignable to `Container<Super>`. A type is **covariant** in `T` if `Container<Dog>` ⊆ `Container<Animal>` (read-only/output positions), **contravariant** if the relationship flips (input/parameter positions), and **invariant** if neither direction is safe (T appears in *both* input and output, e.g. a mutable container). A *truly sound* mutable collection should be **invariant**: if you could treat a `Cell<Dog>` as a `Cell<Animal>`, you could *write* a `Cat` into it through the `Animal` view and then *read* it back as a `Dog`—a type hole.

```typescript
const dogs: Dog[] = [new Dog()];
const animals: Animal[] = dogs;   // ALLOWED by TS — arrays are covariant
animals.push(new Cat());          // type-checks (Cat is an Animal)...
dogs[1].bark();                   // ...but now `dogs` contains a Cat → runtime error
```

TypeScript **deliberately makes arrays covariant** (`Dog[]` assignable to `Animal[]`) even though, as shown, `push` makes that unsound. This is one of the documented, intentional soundness compromises: requiring array invariance would reject an enormous amount of natural, almost-always-safe JavaScript (passing a `Dog[]` to a function that only *reads* `Animal`s), so the team traded soundness for ergonomics, on the bet that most array usage is read-mostly. The principled escape is **`ReadonlyArray<T>` / `readonly T[]`**, which exposes *only* output (covariant) operations—no `push`, no index assignment—so its covariance is actually *sound*: you can safely treat a `readonly Dog[]` as a `readonly Animal[]` because neither view can write a `Cat`. This is exactly why typing function parameters as `readonly T[]` is good practice: it both communicates "I won't mutate this" and makes the covariant assignment legitimately safe. The same reasoning explains **method-parameter bivariance** (another intentional unsound relaxation for ergonomics) and why `strictFunctionTypes` enforces contravariance for *function-typed* parameters but not methods. The expert articulation: mutability is what forces invariance for soundness; TypeScript chooses covariant mutable arrays as a pragmatic, documented unsound hole, and `readonly` is the tool that recovers *sound* covariance—so reach for `readonly` parameters not just for immutability hygiene but because they make variance honest.

#### Q86. [Practical] How do you measure and fix slow editor/IDE responsiveness specifically (as opposed to batch `tsc`), and what server-side knobs and diagnostics exist?

Editor latency and batch `tsc` time are related but *not* the same problem: the editor runs a long-lived **`tsserver`** process that maintains an in-memory program, responds to per-keystroke requests (completions, hovers, diagnostics, quick info), and must stay responsive *incrementally*, whereas `tsc` is a cold, whole-program batch. So you diagnose them with different tools. For the editor, the primary instrument is **TypeScript's server log and the "TypeScript: Open TS Server Log" / `tsserver` performance trace**, plus the built-in command to report the **server's reported timings** and the editor's own profiler.

```bash
# Batch profiling (still useful as a baseline of type cost)
tsc --extendedDiagnostics            # counts: files, types, instantiations, memory, phase times
tsc --generateTrace ./trace          # load trace/*.json into a profiler (chrome://tracing / @typescript/analyze-trace)
npx @typescript/analyze-trace ./trace  # auto-flags the hottest types/files
```

```jsonc
// .vscode/settings.json — server-side knobs that affect responsiveness
{
  "typescript.tsserver.maxTsServerMemory": 8192,   // raise heap before it GC-thrashes
  "typescript.tsserver.experimental.enableProjectDiagnostics": false,
  "typescript.disableAutomaticTypeAcquisition": true // stop ATA fetching @types for JS
}
```

The fixes, editor-focused: **(1) `@typescript/analyze-trace`** on a `--generateTrace` output is the single best tool—it names the specific *types* and *files* whose instantiation dominates, turning "the editor is slow" into "this recursive conditional type in `api.ts` costs 4s." **(2) Reduce program size per project** via **project references** so the server only loads and re-checks the project you're editing, not the whole monorepo—this is usually the biggest editor win because the server's working set shrinks. **(3) `skipLibCheck`** so the server isn't re-checking massive `node_modules` `.d.ts` on every program build. **(4) Add explicit return types on exported functions** so the server doesn't re-infer across boundaries on each edit (inference is recomputed far more often in an interactive session than in a single batch run). **(5) Simplify hot types**—the same recursive/conditional/large-union types that slow `tsc` slow the server *more* because they're re-evaluated interactively; flatten with `interface extends`, cap recursion, break giant unions. **(6) Raise `maxTsServerMemory`** if the trace shows GC thrash, and disable **Automatic Type Acquisition** if you're in a JS project where the server keeps fetching `@types`. **(7) "Restart TS Server"** clears a corrupted incremental state when latency spikes after big dependency changes. The senior distinction to make explicit: batch `tsc` cares about total instantiations once; the editor cares about *incremental re-check cost per keystroke and server memory*, so the highest-leverage editor fixes are the ones that **shrink the per-edit working set** (project references, smaller programs) and **avoid repeated re-inference** (explicit return types), validated with the trace analyzer rather than guessed at.

#### Q87. [Theory] Explain how `tsconfig.json` `extends`, the config resolution order, and option inheritance work—including the gotchas with relative paths and arrays.

`tsconfig.json` supports **`extends`** to inherit from a base config (a file path, or since TS 5.0 an **array** of bases and a bare **npm package** specifier like `"@tsconfig/node20/tsconfig.json"`). The resolved configuration is a **shallow merge**: the child's `compilerOptions` are merged *over* the base's on a per-key basis (child keys win), but there is **no deep merge**—if both define `compilerOptions.paths`, the child's `paths` object *replaces* the base's entirely rather than merging entry-by-entry. Top-level array fields like `include`, `exclude`, and `files` are **not inherited additively** in the way people expect: if the child specifies `include`, it *overrides* the base's `include` wholesale; if it omits them, it inherits the base's.

```jsonc
// base.tsconfig.json
{ "compilerOptions": { "strict": true, "paths": { "@app/*": ["src/*"] }, "outDir": "dist" } }

// tsconfig.json
{
  "extends": ["@tsconfig/node20/tsconfig.json", "./base.tsconfig.json"], // array: later wins
  "compilerOptions": {
    "paths": { "@lib/*": ["lib/*"] }   // REPLACES base paths entirely — @app/* is LOST
  },
  "include": ["src"]                    // overrides any inherited include
}
```

The gotchas that bite teams: **(1) Relative paths in the base resolve relative to the *base* file, except `outDir`/`rootDir`/`paths`/`baseUrl` and other path-like options which historically resolved relative to the config that *defines* them**—the rules around `baseUrl`/`paths` inheritance are a frequent source of "alias works in one package but not another," so prefer defining `paths`/`baseUrl` in the leaf config or use package-relative bases carefully. **(2) Array merge surprises**—because `extends` shallow-merges, redefining `paths` or `lib` in a child *discards* the base's version; if you want to *add* to `paths`, you must repeat the inherited entries (there's no spread). **(3) `references` is not inherited** the way `compilerOptions` is, so each project must declare its own. **(4) `files`/`include`/`exclude` precedence**—`files` is an explicit list; `include`/`exclude` are globs; if `files` is present it takes precedence and `include` is ignored for that explicit set; `exclude` only filters `include`, not `files`. The robust pattern most teams use: a small published **base config** (the `@tsconfig/*` "bases" packages or an internal `tsconfig.base.json`) holding `strict` and target/module defaults, then per-package leaf configs that set *only* their `paths`, `outDir`, `include`, and `references`—keeping path-like options in the leaf to avoid the relative-resolution and shallow-merge traps. The senior point: `extends` is *shallow* and *path-sensitive*, so treat inherited `paths`/`lib`/`include` as "replace, not merge," and centralize only the non-path, truly-global options in the base.

#### Q88. [Theory] What are the soundness and ergonomic implications of `strictNullChecks` being *off* vs. *on*, and what subtle behaviors change beyond "null is now an error"?

With **`strictNullChecks: false`** (the pre-strict default), `null` and `undefined` are members of *every* type—`string` actually means `string | null | undefined`—so they're silently assignable everywhere and the compiler never warns about a possibly-missing value. Turning it **on** removes `null`/`undefined` from all other types, making them their own distinct types you must opt into (`string | null`) and *narrow* before use. This single flag eliminates the most common JavaScript runtime crash class ("Cannot read properties of undefined"), which is why it's the highest-value member of `strict`.

```typescript
// strictNullChecks: false
const name: string = null;     // OK (silently) — null inhabits every type
function f(s: string) { s.toUpperCase(); }
f(null);                       // compiles, crashes at runtime

// strictNullChecks: true
const name2: string = null;    // ERROR — null is not assignable to string
function g(s: string | null) { s.toUpperCase(); }   // ERROR — narrow first
```

The *subtle* behaviors that change beyond the obvious assignability error are what distinguish a deep answer: **(1) Optional properties and parameters** (`x?: T`) only carry `undefined` meaning under strict checks; with the flag off, the `?` is nearly cosmetic because `undefined` was assignable anyway. **(2) The non-null assertion `!`** and optional chaining `?.` / nullish coalescing `??` only do useful *type* work under strict—`obj?.prop` narrows away `undefined` meaningfully only when `undefined` is actually in the type. **(3) Control-flow narrowing for null** (`if (x) {...}`, `if (x != null)`) becomes load-bearing: the compiler now *tracks* nullability through branches, so the narrowing toolbox matters far more. **(4) Function return inference**—a function that sometimes returns nothing infers `T | undefined` only under strict; off, it just infers `T`, hiding the gap. **(5) Array/index access** combines with `noUncheckedIndexedAccess` to surface `| undefined`. **(6) Definite assignment**—`strictPropertyInitialization` (which *requires* `strictNullChecks`) starts demanding class fields be initialized or marked `!`/optional, and the `let x!: T` definite-assignment assertion only exists to serve this. The migration implication: flipping `strictNullChecks` on a legacy codebase typically produces the *largest* error spike of any strict flag because nullability infects everything transitively, which is why teams enable it last and per-directory with a ratcheting budget. The expert framing: `strictNullChecks` doesn't just "make null an error"—it changes the *meaning of every type* (removing the implicit `| null | undefined`), which retroactively activates optional markers, narrowing, `?.`/`??`/`!`, return inference, and property-initialization checking; off, much of TypeScript's null-safety machinery is inert, so the flag is the difference between TypeScript-as-real-null-safety and TypeScript-as-optional-decoration.

#### Q89. [Practical] How do you correctly type a higher-order function that wraps another function while preserving its full signature (e.g. a `memoize`, `withLogging`, or `debounce` decorator)?

The goal is a wrapper that accepts *any* function and returns a function with the **same parameter types, same return type, and ideally the same `this`**—without collapsing everything to `(...args: any[]) => any`. The modern idiom uses a single generic constrained to a function type and **variadic tuple types** to capture the parameter list as a tuple and the return type separately, so the wrapper is transparent to callers.

```typescript
function withLogging<A extends unknown[], R>(
  fn: (...args: A) => R,
  label: string
): (...args: A) => R {
  return (...args: A): R => {
    console.log(`${label} called with`, args);
    const result = fn(...args);
    console.log(`${label} returned`, result);
    return result;
  };
}

const add = (a: number, b: number) => a + b;
const loggedAdd = withLogging(add, "add"); // (a: number, b: number) => number
loggedAdd(2, 3);          // fully typed, 5
// loggedAdd("x", 3);     // ERROR — preserved signature rejects it
```

Several refinements separate a correct answer from a naive one. **(1) Preserving `this`**: if the wrapped function relies on a receiver (a class method), thread it through with a `this`-typed signature: `<T, A extends unknown[], R>(fn: (this: T, ...args: A) => R)` returning `(this: T, ...args: A) => R`, so `withLogging(obj.method)` keeps the correct `this`. **(2) `memoize` and changing the return type**: a memoizer keeps the *same* signature, but a wrapper like `promisify` *transforms* it—`(...args: A) => R` becomes `(...args: A) => Promise<R>`; variadic tuples make that transformation typeable. **(3) `debounce`/`throttle`**: these usually *drop* the return value (the call is deferred), so the wrapped type becomes `(...args: A) => void`—and you must decide whether to expose a `cancel()`/`flush()` by intersecting the returned function type with an object (`((...args: A) => void) & { cancel(): void }`). **(4) Overloaded inputs**: a function with *multiple* overloads is hard to wrap generically—TypeScript infers only the *last* overload signature through a generic, a known limitation, so wrapping heavily-overloaded functions may require explicit per-overload typing or accepting the lossy inference. **(5) Avoid `Function` and `any[]`**: typing the parameter as `Function` or the args as `any[]` *erases* the caller's signature (you lose arity and types), defeating the purpose. The senior framing: a transparent wrapper is `<A extends unknown[], R>(fn: (...args: A) => R) => (...args: A) => R` with `this` threaded when needed; reach for variadic tuples to *capture* the signature, transform `R`/the arg tuple when the wrapper changes behavior (promisify, debounce), and be aware that overload-heavy functions and `this`-binding are the two places this pattern needs extra care.

#### Q90. [Theory] What is the difference between *declaration* and *definite assignment* assertions, `!` operators, and how do `strictPropertyInitialization` and the `declare` field modifier interact?

The exclamation mark `!` wears *three* distinct hats in TypeScript, and conflating them is a common confusion. **(1) The non-null assertion operator** (`value!`) is a *postfix expression* operator that strips `null`/`undefined` from a value's type at a use site—`document.getElementById("x")!.focus()`—a compile-time-only promise that the value isn't nullish, with zero runtime check. **(2) The definite assignment assertion on a variable** (`let x!: number`) tells the compiler "I *will* assign this before use, trust me," suppressing the "used before assigned" error for a `let` that's initialized indirectly. **(3) The definite assignment assertion on a class field** (`name!: string`) tells the compiler the field *will* be initialized by some mechanism it can't see (a framework, a DI container, a `useEffect`, an ORM), suppressing `strictPropertyInitialization`.

```typescript
class Service {
  config!: Config;          // (3) definite assignment: injected later, don't complain
  declare readonly id: string; // `declare` field: described but NO runtime emit
  name: string;             // strictPropertyInitialization: ERROR unless set in ctor
  constructor() { this.name = "x"; } // satisfies the initialization check
}
const el = document.querySelector(".btn")!; // (1) non-null assertion at a use site
let port!: number;          // (2) definite assignment on a local
setup(); port.toFixed();    // trusts that setup() assigned it
```

The interactions: **`strictPropertyInitialization`** (which requires `strictNullChecks`) enforces that every declared, non-optional class field is *definitely assigned* in the constructor (or has an initializer); the field-level `!` is the explicit escape hatch for the legitimate case where assignment happens *outside* the constructor (Angular `@Input()`, NestJS injection, TypeORM columns)—you're trading the safety check for "I take responsibility." The **`declare` field modifier** is different and often confused with `!`: `declare name: string` says "this field's *type* exists, but emit **no** runtime code for it"—used when a base class or framework already creates the property (so emitting `this.name = undefined` via `useDefineForClassFields` would *clobber* the inherited value), and in mixin/decorator scenarios. So `!` *suppresses a check while still emitting/using the field*, whereas `declare` *removes the emit entirely*. The danger common to all the `!` forms is that they're **unverified promises**—a `value!` that's actually `null`, a `field!: T` never initialized, or a `let x!: T` whose `setup()` didn't run, all compile and then crash at runtime exactly like an `as` cast. The senior guidance: prefer real initialization, optional types, or narrowing over `!`; reserve the field-level `!` for genuine framework-injection patterns and document *who* assigns it; reach for `declare` specifically to avoid the `useDefineForClassFields` clobbering problem when a property is defined elsewhere; and treat every `!` as a localized, reviewed soundness hole, not a quick way to silence the compiler.

#### Q91. [Practical] How do you safely type environment variables and configuration, given `process.env` is `Record<string, string | undefined>` and config is loaded at runtime?

`process.env` is typed (via `@types/node`) as `Record<string, string | undefined>`: every key is *possibly* `undefined` and *always* a string (numbers/booleans don't exist—`PORT` is `"3000"`, not `3000`). This produces two real bugs: reading `process.env.MISSING` gives `string | undefined` that you forget to handle, and treating `process.env.PORT` as a number without parsing. The naive "fix" of augmenting `NodeJS.ProcessEnv` to claim keys are non-optional strings is actively *harmful*—it's a lie (the var may genuinely be absent at runtime) that re-introduces the crash the type system should prevent.

```typescript
// ANTI-PATTERN: declaring env vars as always-present strings (a runtime lie)
declare global { namespace NodeJS { interface ProcessEnv { PORT: string } } }

// CORRECT: validate + coerce once at startup, derive the typed config
import { z } from "zod";
const EnvSchema = z.object({
  PORT: z.coerce.number().int().positive().default(3000), // string → number, validated
  NODE_ENV: z.enum(["development", "production", "test"]),
  DATABASE_URL: z.string().url(),
  FEATURE_X: z.coerce.boolean().default(false),
});
export const config = EnvSchema.parse(process.env); // throws at boot if misconfigured
// config.PORT is number, config.NODE_ENV is the union — fully typed & validated
```

The robust pattern is to **validate and coerce `process.env` exactly once at process startup** through a schema (Zod/Valibot/TypeBox/envalid/`@t3-oss/env`), producing a typed, frozen `config` object that the rest of the app imports—never reading `process.env` scattered throughout the codebase. This gives several wins: **(1) fail-fast**—a missing or malformed required variable crashes the process *at boot* with a clear message, not deep in a request handler hours later; **(2) coercion**—`z.coerce.number()`/`.boolean()` turn the string env into the real runtime types your code expects; **(3) a single source of truth**—the schema *is* the documentation of required config, and `z.infer` gives the type for free; **(4) defaults and refinement**—optional vars get defaults, and cross-field rules (`refine`) catch invalid combinations. The trade-offs and refinements: keep secrets out of the type that's logged; for *client-side* env (Vite/Next public vars) the build inlines `import.meta.env`/`process.env.NEXT_PUBLIC_*` so validation must run at build time and you must avoid leaking server secrets into the client bundle (tools like `@t3-oss/env` enforce a server/client split); and prefer crashing on missing config over silent `?? defaultValue` fallbacks for *required* infrastructure values, because a silent fallback (e.g. defaulting `DATABASE_URL`) can point production at the wrong system. The senior framing: never *augment* `ProcessEnv` to pretend variables exist—that's an `as`-cast in disguise; instead treat env as untrusted external input, validate it at the boundary (startup), coerce to real types, and expose one typed config object so the `string | undefined` reality is handled exactly once.

#### Q92. [Theory] Explain branded/opaque types in depth: the runtime-zero-cost guarantee, smart constructors, and how branding composes with validation libraries. Where does the technique break down?

A **branded (opaque/nominal) type** intersects a structural base type with a *phantom* marker that exists only at the type level—`type Email = string & { readonly __brand: "Email" }`—so a raw `string` is *not* assignable to `Email` even though they're structurally compatible, recovering nominal distinctions (`UserId` vs `OrderId`, `Cents` vs `Dollars`, `Validated<T>` vs raw input) in TypeScript's otherwise-structural world. The phantom field never exists at runtime (you never actually write `__brand`), so branding is **zero runtime cost**—it's pure compile-time bookkeeping that erases completely.

```typescript
declare const brand: unique symbol;
type Brand<T, B extends string> = T & { readonly [brand]: B };
type Email = Brand<string, "Email">;

// Smart constructor: the ONLY way to mint an Email — pairs validation with the brand
function toEmail(raw: string): Email {
  if (!/^[^@]+@[^@]+$/.test(raw)) throw new Error("invalid email");
  return raw as Email;            // the single, justified assertion, gated by a real check
}
function send(to: Email) {}
send(toEmail("a@b.com"));         // OK — provably validated
// send("a@b.com");               // ERROR — raw string isn't branded
```

The technique's *power* comes from the **smart-constructor pattern**: because a value can only acquire the brand by passing through a function that *also* performs the runtime validation, the brand becomes a **type-level proof that validation happened**. A function typed `(to: Email) => void` can then *assume* the email is well-formed without re-checking, pushing validation to the boundary and encoding "this string has been sanitized/validated" into the type—this is exactly how you model `SafeHtml` (XSS-sanitized), `SqlSafe`, `PositiveInt`, or `NonEmptyString`, turning "did someone remember to validate?" from a runtime hope into a compile-time guarantee. It **composes beautifully with validation libraries**: Zod's `.brand<"Email">()` produces a schema whose inferred type *is* branded, so `z.infer` gives you `Email` and the only way to get one is `EmailSchema.parse(raw)`—unifying the runtime check and the brand in one declaration with no manual `as`. Where it **breaks down**: (1) **serialization boundaries**—a branded value `JSON.stringify`'d and re-parsed comes back as a raw `string`, so the brand doesn't survive network/storage round-trips; you must re-validate on the way back in (which is correct, but means brands aren't "sticky"). (2) **Assertion leakage**—the smart constructor still uses one `as`, so if that constructor's validation is wrong or someone bypasses it with their own cast, the proof is hollow; brands are only as trustworthy as the single chokepoint that mints them, which must be reviewed and tested. (3) **Ceremony and ergonomics**—every creation point needs a constructor, generic code may need to be brand-aware, and over-branding ordinary values adds friction without payoff. (4) **No runtime distinction**—two brands are identical at runtime, so you can't `instanceof`/reflect on them or recover the brand after erasure. The staff-level judgment: brand *genuinely confusable* primitives in *critical paths* (money, IDs, auth tokens, sanitized strings), implement the brand via a validating smart constructor (or a library's `.brand()`) so it doubles as a validation proof, re-validate at every deserialization boundary, and *don't* blanket the codebase—reserve the ceremony for where mixing two structurally-identical values causes real, costly bugs.

#### Q93. [Theory] How does control-flow analysis handle aliasing, closures, and the "narrowing is lost across function calls" problem—and what are `const`-based and discriminant-preserving workarounds?

TypeScript's **control-flow analysis (CFA)** narrows a variable's type within a branch based on guards it can see, but it operates on *syntactic references* and makes **conservative assumptions about what it can't track**. Three classic places narrowing is *lost*: **(1) across function calls**, **(2) through aliasing/destructuring into separate variables**, and **(3) inside closures (callbacks) over mutable bindings**. The unifying reason is *soundness under possible mutation*: between the guard and the use, anything the compiler can't prove is unchanged might have changed, so it discards the narrowing rather than risk a false guarantee.

```typescript
function f(obj: { value: string | null }) {
  if (obj.value !== null) {
    obj.value.toUpperCase();   // OK — narrowed here
    doSomething();             // an arbitrary call...
    obj.value.toUpperCase();   // STILL OK actually — but:
    obj.value.toUpperCase = (() => {}) as any; // property narrowing is fragile
  }
}

let x: string | null = getMaybe();
if (x !== null) {
  setTimeout(() => x.toUpperCase()); // ERROR — closure over a `let`; x might be reassigned
}

const y: string | null = getMaybe();
if (y !== null) {
  setTimeout(() => y.toUpperCase()); // OK — `const` can't be reassigned, narrowing persists
}
```

The behaviors precisely: **(a) `let` captured in a closure loses narrowing** because the binding is mutable—between the guard and the deferred callback execution, `x` could be reassigned, so CFA refuses to assume it's still non-null inside the closure. **(b) `const` captured in a closure *keeps* narrowing**, because a `const` can never be reassigned, so the compiler safely propagates the narrowed type into the callback—this is the single most important workaround: **assign to a `const` before the closure** (`const v = x; if (v !== null) setTimeout(() => v.toUpperCase())`). **(c) Property narrowing (`obj.prop`) is more fragile than local narrowing**, especially across calls, because the compiler assumes a function call *could* mutate `obj.prop` (it can't prove otherwise for a non-`readonly`, non-`const` property)—the fix is to **copy the property into a local `const`** (`const v = obj.value; if (v !== null) { ...v... }`), which CFA tracks reliably because the local can't be touched by the call. **(d) Destructuring** (`const { value } = obj`) similarly captures a stable local that narrows cleanly. The **discriminant-preserving** angle: for discriminated unions, narrowing on `obj.kind` is preserved better when the discriminant is `readonly` and accessed directly, but storing the whole object in a `const` and switching on `obj.kind` is the robust form; re-reading a mutable discriminant after a call can lose the narrowing. The expert summary: CFA narrowing is **scoped to provably-stable references**—prefer `const` over `let`, copy narrowed *properties* into `const` locals before calls or closures, use `readonly` to signal immutability (which lets CFA trust property narrowing further), and remember that the "lost narrowing" you hit in callbacks and after function calls is not a bug but CFA correctly refusing to assume a mutable thing stayed put.

#### Q94. [Practical] You need to incrementally adopt the native `tsgo` (TypeScript 7) compiler in a large existing project. What's the rollout plan, what breaks, and what do you keep on `tsc`?

The native compiler (`tsgo`, the TypeScript 7 line, distributed in preview as `@typescript/native-preview`) is a **faithful Go reimplementation of the same type system**, targeting ~10x faster checking and editor responsiveness—but during the transition the existing JavaScript-based TS 5.x continues as the stable "6.x" line, and `tsgo` is *preview-quality* and *not yet feature-complete*. So adoption is about *running it alongside* `tsc`, not replacing it wholesale. The guiding principle: because `tsgo` doesn't change language semantics, you can use it as a **faster second checker** that must agree with `tsc`, and graduate workloads to it as confidence grows.

```bash
npm i -D @typescript/native-preview
npx tsgo --version
# Run BOTH in CI and diff: tsgo for speed, tsc as the source of truth
npx tsgo --noEmit;  echo "tsgo: $?"
npx tsc   --noEmit; echo "tsc:   $?"
```

A staged rollout: **Phase 0 — measure & shadow.** Add `tsgo --noEmit` as a *non-blocking* parallel CI job next to the authoritative `tsc --noEmit`; compare error counts and messages. Any *divergence* (an error one reports and the other doesn't) is a bug to file against the preview, and `tsc` stays the gate. **Phase 1 — dev/editor opt-in.** Let interested engineers use the Go-based language server locally for the responsiveness win, while CI still relies on `tsc`—the editor experience is where the speedup is most felt and the risk is lowest (no production artifact depends on it). **Phase 2 — promote in CI** once `tsgo` reproduces `tsc`'s results on your corpus for a sustained period: make `tsgo` the *primary* fast check and keep `tsc` as a periodic/full verification. **Phase 3 — adopt as default** when the line is declared stable and feature-complete for your TS version. What **breaks / blocks** you and must stay on `tsc`: **(1) in-process API consumers**—anything that `import`s the `typescript` package programmatically (typescript-eslint's typed rules, `ts-jest`, `ts-loader`, ts-morph, API-based codegen) can't call a Go binary, so until a stable interop/LSP-style protocol ships, *typed linting and TS-API tooling keep using the JS `tsc`*. (2) **Not-yet-implemented features/flags**—preview gaps mean some options or edge behaviors may be missing; verify your exact `tsconfig` is supported. (3) **Emit**—if you rely on `tsc` for emit or `.d.ts` generation, validate `tsgo`'s output matches (and note the broader trend of pushing emit/declarations to other tools via `isolatedDeclarations`). The senior framing: treat `tsgo` adoption as a *risk-managed migration of a checker*, not a semantics change—shadow it against `tsc` in CI, capture the editor win early, hold `tsc` as the source of truth until divergence is zero, and explicitly keep typed-ESLint and any in-process TS-API tooling on the JavaScript compiler until the interop story lands. The thing that does *not* change—and the reassuring point to a nervous team—is that your *types and their meaning* are identical; only the speed and the toolchain plumbing differ.

#### Q95. [Theory] What is the difference between *assignability*, *comparability*, and *identity* of types, and where do `===`-style equality checks, `as` casts, and switch/`satisfies` each rely on a different relation?

TypeScript uses several *distinct* relations between types, and conflating them explains many "why does/doesn't this compile?" puzzles. **Assignability** (`S` assignable to `T`) is the workhorse, used for assignments, argument passing, and return values—it's directional and *lenient* in specific spots (`any` to/from anything, the `void`-callback rule, excess-property exceptions once non-fresh). **Comparability** is a *looser, bidirectional* relation used by **type assertions (`as`)** and by `==`/`===` *type-checking*: `as` is allowed when the two types are comparable—i.e., one is assignable to the other *in either direction*—which is why `x as Y` works for narrowing *or* widening but is blocked between wholly unrelated types (you need `as unknown as Y` to force those). **Identity** (structural equivalence) asks whether two types are mutually assignable—the same type up to structure—used when deciding if a redeclaration matches, for caching, and conceptually when reasoning about whether two aliases denote the same type.

```typescript
const x: "a" | "b" = "a";
if (x === "c") {}            // ERROR — "c" not comparable to "a" | "b" (no overlap)
const n = x as number;       // ERROR — string-literal & number not comparable
const n2 = x as unknown as number; // OK — double-assert through the top type

type Cfg = Record<string, number>;
const c = { a: 1 } satisfies Cfg;  // ASSIGNABILITY check, but type stays { a: number }
const c2: Cfg = { a: 1 };          // ASSIGNABILITY check, type WIDENS to Cfg
```

Where each relation surfaces: **`===`/`!==` against a literal** uses comparability—TypeScript flags `x === "c"` when `x: "a" | "b"` because the literal types can't overlap, catching impossible comparisons (this is the "This comparison appears to be unintentional" error). **`as` casts** use comparability (assignable *either* direction), which is *looser* than assignability—that's precisely why `as` is dangerous: it permits widening *and* narrowing the compiler can't verify, and the double-`unknown` trick exists to escape even comparability's guardrail. **A `switch`/discriminated-union narrowing** relies on assignability + the literal-overlap logic to refine the union per case. **`satisfies`** runs an **assignability** check (so it catches typos and wrong value types, unlike `as`) but—crucially—*does not change the static type* of the expression, whereas an **annotation** runs the same assignability check *and widens* the variable to the annotated type. So `satisfies Cfg` and `: Cfg` perform the *same* check but differ in their effect on the resulting type. The expert payoff: understanding that **`as` is gated by the looser comparability relation while assignments/`satisfies` use the stricter assignability relation** is exactly *why* `satisfies` is the safe replacement for `as` (it validates without the loose either-direction escape and without widening), why impossible `===` comparisons are caught, and why forcing an unrelated cast requires routing through `unknown` to defeat comparability entirely.

#### Q96. [Practical] How do you write *type-level tests* and guard against type regressions in a library's public API, and why are runtime tests insufficient?

Runtime tests verify *behavior* but say nothing about the *types* a library exposes—and for a typed library, the type surface *is* a public contract that can regress invisibly: a refactor can widen a return type from `User` to `User | undefined`, turn a tuple into an array, make a generic stop inferring, or break `.d.ts` resolution—none of which any runtime test catches, yet all of which break downstream consumers' *compilation*. So a serious library needs **type-level tests** as first-class CI artifacts alongside unit tests.

```typescript
// Using expectTypeOf (vitest) / tsd / @ts-expect-error patterns
import { expectTypeOf } from "vitest";

expectTypeOf(parse("x")).toEqualTypeOf<string[]>();        // exact return type
expectTypeOf(parse).parameter(0).toEqualTypeOf<string>();  // parameter type
expectTypeOf<User>().toHaveProperty("id").toBeString();

// Negative tests: assert that a misuse is a COMPILE error
// @ts-expect-error — passing a number must NOT be allowed
parse(42);   // if this STOPS erroring, @ts-expect-error itself fails → regression caught
```

The tooling landscape: **`tsd`** (runs `tsc` over `*.test-d.ts` files with `expectType`/`expectError` assertions, the classic library choice), **`expect-type` / vitest's `expectTypeOf`** (fluent type assertions runnable in your normal test runner, with `toEqualTypeOf` for *exact* equality vs `toMatchTypeOf` for assignability), and the humble **`@ts-expect-error`** comment for *negative* tests—asserting that a wrong usage *must* fail to compile, which self-cleans (it errors if the misuse ever becomes valid). Beyond per-API assertions, the library-author CI matrix should include: **(1) `tsc --noEmit` under `strict`** as the baseline; **(2) `@arethetypeswrong` (`attw`)** on the *packed* artifact to verify ESM/CJS `.d.ts` resolution under every consumer condition (catches the dual-package/exports-mismatch failures consumers would otherwise hit); **(3) testing against multiple TypeScript versions** (a matrix of supported TS releases, since a `.d.ts` using a new feature breaks consumers on older TS, and inference changes across versions can shift inferred types); and **(4) `--isolatedDeclarations`** if you want to guarantee declarations stay file-local and fast. The reason this is non-negotiable for libraries: a published type regression is **silently breaking** for everyone downstream—their build goes red on *your* release with no code change of theirs—so the type surface deserves the same regression protection as runtime behavior. The senior framing: treat *types as API*, test them explicitly with exact-equality assertions and negative `@ts-expect-error`/`expectError` cases, verify the *published* resolution with `attw`, and run a TS-version matrix—because runtime tests prove the code *works*, while only type-level tests prove the *contract you ship* hasn't drifted.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q97. [Coding] Write a type-safe `pick` and `omit` runtime function that mirror the built-in `Pick`/`Omit` utility types.

**Problem:** The built-in `Pick<T, K>` / `Omit<T, K>` are *type-only*. Build runtime functions whose return types match those utilities exactly, so the value and its type stay in sync.

```typescript
function pick<T extends object, K extends keyof T>(obj: T, keys: K[]): Pick<T, K> {
  const out = {} as Pick<T, K>;
  for (const k of keys) {
    if (k in obj) out[k] = obj[k];
  }
  return out;
}

function omit<T extends object, K extends keyof T>(obj: T, keys: K[]): Omit<T, K> {
  const set = new Set<keyof T>(keys);
  const out = {} as Record<keyof T, unknown>;
  for (const k of Object.keys(obj) as (keyof T)[]) {
    if (!set.has(k)) out[k] = obj[k];
  }
  return out as Omit<T, K>;
}

interface User { id: string; name: string; password: string }
const u: User = { id: "1", name: "Ada", password: "secret" };
const pub = omit(u, ["password"]);     // type: Omit<User, "password"> = { id, name }
const cred = pick(u, ["id", "password"]); // type: { id: string; password: string }
// pick(u, ["nope"]);                  // ERROR — "nope" is not a key of User
```

The `K extends keyof T` constraint is what makes the call site reject typos and non-existent keys, and the return-type annotation propagates the narrowed shape to callers so they keep autocomplete on exactly the surviving keys. Note the two unavoidable internal casts: `{} as Pick<T, K>` (the accumulator starts empty but we *promise* to fill it) and `Object.keys(obj) as (keyof T)[]` (the deliberate `string[]` honesty hole, safe here because we built the loop over the object's own keys).

The trade-off versus a library like lodash is that lodash's `omit` is loosely typed (returns a wide partial), so writing your own thin wrapper buys precise types for a few lines of code. **Edge cases:** `omit` only strips *own* enumerable keys (inherited/symbol keys are not touched—`Object.keys` ignores them); `pick` silently skips keys that are absent at runtime even though the type claims they exist, so for optional-but-present semantics you'd guard with `in`. **Complexity:** `pick` is O(k) in the number of keys; `omit` is O(n) in the object's key count.

#### Q98. [Coding] Implement a `clamp`, `range`, and `groupBy` utility set with precise generic signatures.

**Problem:** Show that even small utilities benefit from generics—`groupBy` in particular should return a `Record` keyed by whatever the key-selector produces.

```typescript
function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function range(start: number, end: number, step = 1): number[] {
  if (step <= 0) throw new RangeError("step must be > 0");
  const out: number[] = [];
  for (let i = start; i < end; i += step) out.push(i);
  return out;
}

function groupBy<T, K extends PropertyKey>(
  items: readonly T[],
  keyFn: (item: T) => K
): Record<K, T[]> {
  const out = {} as Record<K, T[]>;
  for (const item of items) {
    const key = keyFn(item);
    (out[key] ??= []).push(item);
  }
  return out;
}

const people = [
  { name: "Ada", team: "infra" },
  { name: "Bjarne", team: "lang" },
  { name: "Grace", team: "infra" },
] as const;
const byTeam = groupBy(people, (p) => p.team);
// byTeam: Record<"infra" | "lang", readonly {...}[]>  — keys are the literal union
```

The interesting signature is `groupBy`: constraining `K extends PropertyKey` (= `string | number | symbol`) lets the key-selector return anything usable as an object key, and `Record<K, T[]>` ties the output keys to exactly that union. When the input is `as const` and the selector returns a literal property, `K` narrows to the precise union of group names rather than the wide `string`—so consumers can index `byTeam.infra` with confidence.

**Trade-off / soundness note:** `Record<K, T[]>` claims *every* `K` is present, but at runtime a group is only created when at least one item maps to it. If the key union is broader than the data (e.g. `K` is `string`), indexing a missing group returns `undefined` while the type says `T[]`—exactly the `noUncheckedIndexedAccess` hole. For data-driven keys this is usually fine; if you need honesty, type the return as `Partial<Record<K, T[]>>`. **Complexity:** `groupBy` is O(n); `range` is O((end−start)/step).

#### Q99. [Coding] Write a `debounce` function in TypeScript that preserves the wrapped function's parameter types and supports `cancel`/`flush`.

**Problem:** A naive `debounce(fn: Function)` throws away the argument types. Preserve them with a generic over the parameter tuple, and expose control methods.

```typescript
interface Debounced<A extends unknown[]> {
  (...args: A): void;
  cancel(): void;
  flush(): void;
}

function debounce<A extends unknown[]>(
  fn: (...args: A) => void,
  waitMs: number
): Debounced<A> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let lastArgs: A | undefined;

  const debounced = (...args: A): void => {
    lastArgs = args;
    clearTimeout(timer);
    timer = setTimeout(() => {
      timer = undefined;
      const a = lastArgs!;
      lastArgs = undefined;
      fn(...a);
    }, waitMs);
  };

  debounced.cancel = (): void => {
    clearTimeout(timer);
    timer = undefined;
    lastArgs = undefined;
  };
  debounced.flush = (): void => {
    if (timer !== undefined && lastArgs) {
      clearTimeout(timer);
      timer = undefined;
      const a = lastArgs;
      lastArgs = undefined;
      fn(...a);
    }
  };
  return debounced;
}

const onResize = debounce((w: number, h: number) => console.log(w, h), 200);
onResize(800, 600);     // typed — extra/missing args are compile errors
// onResize("x");       // ERROR — wrong argument type
onResize.cancel();
```

The key technique is the **variadic tuple type parameter `A extends unknown[]`**: it captures the entire argument list as a tuple, so `(...args: A)` on both the input and the returned function keeps the signature intact—no `any`, no overloads. The return type is an *intersection-shaped* callable interface (`Debounced<A>`) so the function also carries `cancel`/`flush` as properties, which is why we attach them to the closure object rather than returning a bare arrow.

Using `ReturnType<typeof setTimeout>` instead of `number` is a portability detail: in the DOM `setTimeout` returns `number`, but under `@types/node` it returns a `Timeout` object—deriving the type makes the same code compile in both environments. **Edge cases:** this is a *trailing-edge* debounce (fires after the quiet period); leading-edge or `maxWait` behavior needs more state; the `lastArgs!` non-null assertion is safe because the timer only fires after `lastArgs` is set. **Complexity:** O(1) per call.

### 🟡 Intermediate — extended

#### Q100. [Coding] Implement a `Pipe`/`pipe` function with full type inference across an arbitrary chain of unary functions.

**Problem:** `pipe(value, f, g, h)` should type-check that each function's output feeds the next, and infer the final return type—without writing dozens of overloads by hand.

```typescript
// Overload-based pipe (the production-proven approach): explicit arities
function pipe<A, B>(a: A, ab: (a: A) => B): B;
function pipe<A, B, C>(a: A, ab: (a: A) => B, bc: (b: B) => C): C;
function pipe<A, B, C, D>(
  a: A, ab: (a: A) => B, bc: (b: B) => C, cd: (c: C) => D
): D;
function pipe(value: unknown, ...fns: Array<(x: unknown) => unknown>): unknown {
  return fns.reduce((acc, fn) => fn(acc), value);
}

const result = pipe(
  "  42 ",
  (s: string) => s.trim(),
  (s: string) => Number(s),
  (n: number) => n * 2
); // result: number = 84
```

A purely *generic, variadic* version is also possible with a recursive conditional type that threads the output of each function into the input slot of the next:

```typescript
type PipeChain<T extends readonly unknown[], In> =
  T extends readonly [(arg: In) => infer Out, ...infer Rest]
    ? Rest extends readonly [(arg: Out) => unknown, ...unknown[]]
      ? PipeChain<Rest, Out>
      : Out
    : In;
```

The honest trade-off: the **overload approach** gives crisp errors ("argument of type X is not assignable to parameter of type Y at step 3") and is what fp-ts, RxJS, and Effect actually ship—usually pre-generating ~20 arities. The **recursive-conditional approach** is more elegant and unbounded, but its error messages degrade into walls of inference noise when a link in the chain is mistyped, and deep chains risk the instantiation-depth limit. For a public API, overloads win on diagnostics; for internal code where you control the inputs, the recursive type is fine. **Complexity:** runtime is O(n) in the number of functions; the type computation is linear in chain length but happens at compile time.

#### Q101. [Coding] Build a typed `memoize` with a customizable cache key, supporting both single-arg and multi-arg functions.

**Problem:** Memoize an expensive pure function while preserving its exact signature and allowing the caller to supply a key-resolver for non-primitive arguments.

```typescript
function memoize<A extends unknown[], R>(
  fn: (...args: A) => R,
  keyResolver: (...args: A) => string = (...args) => JSON.stringify(args)
): (...args: A) => R {
  const cache = new Map<string, R>();
  return (...args: A): R => {
    const key = keyResolver(...args);
    const hit = cache.get(key);
    if (hit !== undefined || cache.has(key)) return hit as R;
    const value = fn(...args);
    cache.set(key, value);
    return value;
  };
}

const slowAdd = (a: number, b: number): number => {
  for (let i = 0; i < 1e6; i++); // simulate work
  return a + b;
};
const fastAdd = memoize(slowAdd);
fastAdd(2, 3); // computes
fastAdd(2, 3); // cached
// fastAdd("a"); // ERROR — preserves slowAdd's (number, number) signature
```

The signature `<A extends unknown[], R>(fn: (...args: A) => R) => (...args: A) => R` is the canonical shape for *any* signature-preserving wrapper: the returned function is indistinguishable from the original to callers. The subtle correctness detail is the **`hit !== undefined || cache.has(key)`** check: a naive `if (cache.has(key)) return cache.get(key)!` is correct but `if (hit) ...` would *recompute* whenever the cached value is falsy (`0`, `""`, `false`, `null`)—a classic memoization bug. Distinguishing "absent" from "present-but-falsy" requires `Map.has`.

**Trade-offs and edge cases:** the default `JSON.stringify` key resolver is order-sensitive for object arguments and silently collapses `undefined`/functions/`Symbol`s—hence the injectable `keyResolver`. For object-identity keys you'd swap to a `WeakMap` (which also fixes the unbounded-memory problem since entries are GC'd). This implementation never evicts, so it leaks for unbounded argument domains—production versions add an LRU bound. It also assumes `fn` is *pure*; memoizing an effectful or time-dependent function is a bug. **Complexity:** O(1) average lookup plus the cost of `keyResolver`.

#### Q102. [Coding] Implement a recursive `DeepPartial` that correctly excludes `Date`, `Map`, `Set`, and arrays from being mangled.

**Problem:** A naive `DeepPartial<T>` treats `Date`/`Map`/`Set` as plain objects and recurses into their internal members, producing useless types. Write a production-grade version.

```typescript
type Primitive = string | number | boolean | bigint | symbol | null | undefined;
type Builtin = Primitive | Date | RegExp | ((...args: any[]) => any);

type DeepPartial<T> =
  T extends Builtin ? T :
  T extends Map<infer K, infer V> ? Map<DeepPartial<K>, DeepPartial<V>> :
  T extends ReadonlyMap<infer K, infer V> ? ReadonlyMap<DeepPartial<K>, DeepPartial<V>> :
  T extends Set<infer U> ? Set<DeepPartial<U>> :
  T extends ReadonlySet<infer U> ? ReadonlySet<DeepPartial<U>> :
  T extends Array<infer U> ? Array<DeepPartial<U>> :
  T extends ReadonlyArray<infer U> ? ReadonlyArray<DeepPartial<U>> :
  T extends object ? { [K in keyof T]?: DeepPartial<T[K]> } :
  T;

interface Config {
  createdAt: Date;
  flags: Map<string, boolean>;
  tags: string[];
  nested: { host: string; port: number };
}
type PartialConfig = DeepPartial<Config>;
const patch: PartialConfig = { nested: { host: "localhost" } }; // OK
// createdAt stays `Date`, not a partial of Date's methods
```

The ordering of the conditional branches is load-bearing: **specific built-ins must be tested before the generic `object` branch**, because `Date`, `Map`, etc. all satisfy `extends object`. If the `object` branch came first it would map over `Date`'s prototype methods (`getTime`, etc.) and produce a nonsensical `{ getTime?: ... }`. The `Builtin` alias short-circuits the common leaf cases (primitives, `Date`, `RegExp`, functions) up front, which also *improves compile performance* by avoiding deep recursion into those.

**Why this matters in practice:** patch/PATCH payloads, test fixtures, and config overrides all want "any subset of a nested structure," and the naive version silently corrupts the moment a `Date` or collection appears—failures that show up as baffling type errors deep in call sites. **Edge cases:** mapping over a `Map`'s key type is debatable (you usually don't want partial *keys*); some libraries keep keys exact. This is also where instantiation-depth limits bite on very deep models—prefer flatter domain types or cap recursion. **Complexity:** pure compile-time, proportional to nesting depth.

#### Q103. [Coding] Write a `createStore` (minimal Redux-style) with fully inferred state and action types from a reducer.

**Problem:** Design a tiny state container where the dispatched action types are inferred from the reducer's action union, and `getState` returns the precise state type.

```typescript
type Reducer<S, A> = (state: S, action: A) => S;
type Listener = () => void;

interface Store<S, A> {
  getState(): S;
  dispatch(action: A): void;
  subscribe(listener: Listener): () => void;
}

function createStore<S, A>(reducer: Reducer<S, A>, initial: S): Store<S, A> {
  let state = initial;
  const listeners = new Set<Listener>();
  return {
    getState: () => state,
    dispatch: (action) => {
      state = reducer(state, action);
      listeners.forEach((l) => l());
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener); // unsubscribe
    },
  };
}

interface CounterState { count: number }
type CounterAction =
  | { type: "increment"; by: number }
  | { type: "reset" };

const store = createStore<CounterState, CounterAction>(
  (state, action) => {
    switch (action.type) {
      case "increment": return { count: state.count + action.by };
      case "reset":     return { count: 0 };
      default: { const _x: never = action; return state; }
    }
  },
  { count: 0 }
);

store.dispatch({ type: "increment", by: 5 }); // OK, payload checked
// store.dispatch({ type: "decrement" });      // ERROR — not in the action union
const n: number = store.getState().count;      // state fully typed
```

The two generics `S` and `A` flow from the `reducer` and `initial` arguments, so callers never re-annotate at the use site—`getState` returns `S` and `dispatch` only accepts the action union `A`. Inside the reducer, the **discriminated union on `type` plus a `never` exhaustiveness default** guarantees that adding `{ type: "decrement" }` to `CounterAction` without handling it breaks the build. The `subscribe` method returns its own unsubscribe closure, which is the idiomatic pattern (cleaner than a separate `unsubscribe(listener)` that requires re-passing the reference).

**Trade-offs:** this is intentionally minimal—real stores add middleware, action creators, and `combineReducers`. A common ergonomic upgrade is inferring `A` from the *reducer's action creators* (so you don't hand-write the union twice), which modern toolkits do via builder APIs. **Edge cases:** `subscribe` during a dispatch (re-entrancy) and listener mutation mid-iteration are real concerns—copying the listener set before iterating, or using an array snapshot, hardens it. **Complexity:** `dispatch` is O(L) in listener count; `getState`/`subscribe` are O(1).

#### Q104. [Coding] Implement a type-safe `assertNever` exhaustiveness helper and show three places it changes the design.

**Problem:** Centralize exhaustiveness checking into a reusable helper and demonstrate it across a switch, an `if/else` ladder, and a mapped lookup.

```typescript
function assertNever(value: never, message = "Unexpected variant"): never {
  throw new Error(`${message}: ${JSON.stringify(value)}`);
}

type Event =
  | { kind: "click"; x: number; y: number }
  | { kind: "key"; code: string }
  | { kind: "scroll"; delta: number };

// 1) switch — default branch
function handleSwitch(e: Event): string {
  switch (e.kind) {
    case "click":  return `click ${e.x},${e.y}`;
    case "key":    return `key ${e.code}`;
    case "scroll": return `scroll ${e.delta}`;
    default:       return assertNever(e); // build breaks if a variant is added
  }
}

// 2) if/else ladder — final else
function handleLadder(e: Event): string {
  if (e.kind === "click") return "c";
  else if (e.kind === "key") return "k";
  else if (e.kind === "scroll") return "s";
  else return assertNever(e);
}

// 3) lookup map — compiler enforces every key is present
const labels: Record<Event["kind"], string> = {
  click: "Click",
  key: "Key",
  scroll: "Scroll", // removing any line is a compile error
};
```

`assertNever` works because its parameter is typed `never`: the value reaching it must have been narrowed away to the empty type. If every union member is handled, the `default`/`else` branch is unreachable and `e` *is* `never`, so the call type-checks. The moment someone adds `{ kind: "drag"; ... }`, that branch sees `e: { kind: "drag"; ... }`, which is **not** assignable to `never`, and the build fails pointing exactly at the unhandled location. Returning `never` (it throws) also lets it satisfy the function's declared return type without a redundant `return`.

The third pattern—`Record<Event["kind"], string>`—shows exhaustiveness *without a function*: the index type `Event["kind"]` extracts the discriminant union, and `Record` requires every key, so an incomplete map is a compile error. The design lesson is that exhaustiveness can be enforced structurally (the `Record`) or via control flow (`assertNever`); the former is better for static lookup tables, the latter for branching logic with side effects. **Edge case:** `assertNever` *also* throws at runtime as a defensive backstop, which matters when untyped data sneaks past the types (deserialized JSON with an unexpected `kind`).

#### Q105. [Coding] Write a generic `retry` wrapper with typed options, exponential backoff, and a typed predicate for which errors are retryable.

**Problem:** Build `retry(fn, options)` for async operations that preserves `fn`'s return type, backs off exponentially, and only retries errors the caller deems transient.

```typescript
interface RetryOptions {
  retries: number;
  baseDelayMs: number;
  maxDelayMs?: number;
  shouldRetry?: (error: unknown, attempt: number) => boolean;
  onRetry?: (error: unknown, attempt: number) => void;
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

async function retry<T>(
  fn: () => Promise<T>,
  options: RetryOptions
): Promise<T> {
  const { retries, baseDelayMs, maxDelayMs = 30_000 } = options;
  const shouldRetry = options.shouldRetry ?? (() => true);
  let lastError: unknown;

  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error;
      if (attempt === retries || !shouldRetry(error, attempt)) break;
      const delay = Math.min(baseDelayMs * 2 ** attempt, maxDelayMs);
      const jitter = Math.random() * delay * 0.1;
      options.onRetry?.(error, attempt);
      await sleep(delay + jitter);
    }
  }
  throw lastError;
}

// Usage: only retry 5xx-style failures, give up on 4xx
const data = await retry(() => fetchJson<{ items: string[] }>("/api"), {
  retries: 4,
  baseDelayMs: 200,
  shouldRetry: (e) => e instanceof HttpError && e.status >= 500,
  onRetry: (_e, n) => console.warn(`retry #${n + 1}`),
});
declare function fetchJson<T>(url: string): Promise<T>;
declare class HttpError extends Error { status: number }
```

The generic `<T>` flows from `fn`'s `Promise<T>` to the wrapper's `Promise<T>`, so `data` above is typed `{ items: string[] }` with zero annotation at the call site. Typing the *error* as `unknown` in `shouldRetry` (matching `useUnknownInCatchVariables`) forces the caller to narrow before inspecting it—`e instanceof HttpError && e.status >= 500`—which is exactly the safe pattern; the alternative `any` would let `e.status` compile even when `e` is a string.

The behavioral subtleties are what separate a toy from a real retry: **(1) jitter** (the `Math.random()` term) prevents thundering-herd retry storms where many clients retry in lockstep; **(2) `maxDelayMs`** caps exponential growth so attempt 10 doesn't sleep for 17 minutes; **(3) the `shouldRetry` predicate** is essential—blindly retrying a `400 Bad Request` wastes time and can be harmful for non-idempotent operations. **Edge cases:** retrying non-idempotent writes risks duplicate side effects (pair with idempotency keys); `retries: 0` means a single attempt. **Complexity:** up to `retries + 1` invocations; total wall-clock bounded by the geometric sum of delays.

#### Q106. [Coding] Implement a `Mutable<T>` and a `DeepMutable<T>` (the inverse of `Readonly`/`DeepReadonly`), and explain a real use.

**Problem:** Strip `readonly` modifiers—shallow and deep. This is needed when you receive a `readonly` value (e.g. from `as const` or an immutable API) but must build a mutable working copy.

```typescript
// Shallow: the `-readonly` modifier removes the readonly flag
type Mutable<T> = { -readonly [K in keyof T]: T[K] };

// Deep: recurse, handling arrays/tuples and skipping functions
type DeepMutable<T> =
  T extends (...args: any[]) => any ? T :
  T extends ReadonlyArray<infer U> ? DeepMutable<U>[] :
  T extends object ? { -readonly [K in keyof T]: DeepMutable<T[K]> } :
  T;

const config = {
  name: "app",
  servers: ["a", "b"],
  limits: { rps: 100 },
} as const;
// typeof config is deeply readonly with literal types

type Editable = DeepMutable<typeof config>;
// { name: string; servers: string[]; limits: { rps: number } }
const draft: Editable = structuredClone(config) as Editable;
draft.limits.rps = 200;     // OK — readonly stripped
draft.servers.push("c");    // OK — array is now mutable
```

The mechanism is the **`-readonly` mapping modifier**: just as `+readonly` (or bare `readonly`) *adds* the flag, `-readonly` *removes* it. (There's a symmetric `-?` that removes optionality, used by the built-in `Required<T>`.) The deep version recurses with the same branch-ordering discipline as `DeepReadonly`/`DeepPartial`—functions pass through untouched, arrays become mutable `T[]`, and objects rebuild with the modifier stripped at every level.

Notice that `DeepMutable<typeof config>` also *widens the literal types back* (`"app"` → `string`, the readonly tuple → `string[]`)—because mapping `T[K]` through the recursion goes through the object branch but the leaf primitives stay as whatever they were; in practice `as const` literals get a wider type only where the array/tuple branch rebuilds them as `T[]`. **Real use:** taking a frozen default config or a Redux `readonly` state slice and producing a writable draft to mutate before re-freezing—conceptually what Immer does at runtime, expressed at the type level. **Edge cases:** `DeepMutable` does not actually unfreeze a runtime-`Object.freeze`d object (it's type-only); you still need a real clone (`structuredClone`) to get a mutable runtime value, as shown.

### 🟠 Advanced — extended

#### Q107. [Coding] Build a compile-time `Split` and `Join` on string literal types, then a `CamelToSnake` converter.

**Problem:** Manipulate string *types* (not values) with template literal types and recursion—the foundation of typed routers, ORMs, and casing converters.

```typescript
// Split "a.b.c" by "." into ["a","b","c"] at the type level
type Split<S extends string, D extends string> =
  S extends `${infer Head}${D}${infer Tail}`
    ? [Head, ...Split<Tail, D>]
    : [S];

// Join ["a","b","c"] with "." into "a.b.c"
type Join<T extends readonly string[], D extends string> =
  T extends readonly [infer F extends string, ...infer R extends string[]]
    ? R extends [] ? F : `${F}${D}${Join<R, D>}`
    : "";

type P = Split<"user.profile.name", ".">; // ["user","profile","name"]
type J = Join<["a","b","c"], "/">;         // "a/b/c"

// camelCase -> snake_case at the type level
type CamelToSnake<S extends string> =
  S extends `${infer Head}${infer Tail}`
    ? Head extends Uppercase<Head>
      ? Head extends Lowercase<Head>       // digits/symbols are both upper & lower
        ? `${Head}${CamelToSnake<Tail>}`
        : `_${Lowercase<Head>}${CamelToSnake<Tail>}`
      : `${Head}${CamelToSnake<Tail>}`
    : S;

type S1 = CamelToSnake<"getUserName">;     // "get_user_name"
type S2 = CamelToSnake<"parseHTML">;       // "parse_h_t_m_l"
```

`Split` recurses by matching a `Head`, the delimiter `D`, and a `Tail`, accumulating `Head` into a tuple until no delimiter remains. `Join` is the dual: it walks the tuple, and the `R extends []` base case avoids a trailing delimiter. The clever bit in `CamelToSnake` is detecting an uppercase character *without* `RegExp` (unavailable in types): a character is uppercase iff `Head extends Uppercase<Head>`, but digits and `_` are equal to *both* their upper- and lowercase forms, so the inner `Head extends Lowercase<Head>` check distinguishes a real letter from a non-letter—only real uppercase letters get the `_` prefix.

**Why this is more than a parlor trick:** these primitives underpin real libraries—Prisma/Drizzle map snake_case DB columns to camelCase fields, typed routers split path patterns into params (`/users/:id` → `{ id: string }`), and i18n libraries validate interpolation keys. **The honest cost:** per-character recursion is O(n) instantiations *per string*, and a module full of these on long strings is a measurable compile-time tax—`parseHTML`'s acronym blow-up (`parse_h_t_m_l`) also shows these naive converters are semantically imperfect. Production libraries cap usage or precompute. **Complexity:** O(length) type instantiations; pure compile-time.

#### Q108. [Coding] Implement a fully-typed finite state machine where invalid transitions are compile errors.

**Problem:** Model a traffic light / order lifecycle so that calling `transition(state, event)` only permits events legal in the current state, and returns the correctly-typed next state.

```typescript
type State = "idle" | "loading" | "success" | "error";
type Event = "FETCH" | "RESOLVE" | "REJECT" | "RETRY" | "RESET";

// The transition table as a type: per state, which events map to which next state
interface Transitions {
  idle:    { FETCH: "loading" };
  loading: { RESOLVE: "success"; REJECT: "error" };
  success: { RESET: "idle" };
  error:   { RETRY: "loading"; RESET: "idle" };
}

// Events legal from a given state = keys of its transition entry
type EventsFor<S extends State> = keyof Transitions[S] & Event;
// The resulting next state for a (state, event) pair
type Next<S extends State, E extends EventsFor<S>> = Transitions[S][E];

declare function transition<S extends State, E extends EventsFor<S>>(
  state: S,
  event: E
): Next<S, E>;

const a = transition("idle", "FETCH");      // a: "loading"
const b = transition("loading", "RESOLVE"); // b: "success"
// transition("idle", "RESOLVE");           // ERROR — RESOLVE illegal from idle
// transition("success", "REJECT");         // ERROR — not a valid event for success
```

The design encodes the **transition table as a type** (`Transitions`), then derives two helper types from it: `EventsFor<S>` extracts exactly the events legal in state `S` (the keys of that state's entry), and `Next<S, E>` looks up the resulting state. Because `transition`'s `E` is constrained to `EventsFor<S>`, the compiler rejects any event not present in the current state's table—turning an entire class of "illegal transition" runtime bugs into compile errors—and the return type `Next<S, E>` is the *specific* next state, not the wide `State` union, so chained transitions keep narrowing.

This is essentially what XState provides at scale, but the bare-types version is instructive: the single source of truth is the `Transitions` interface, and adding a new edge (e.g. `loading: { ..., CANCEL: "idle" }`) instantly makes `transition("loading", "CANCEL")` legal everywhere with zero other changes. **Edge cases:** a runtime implementation must still *store* the transition table as a value (types are erased), so production code pairs this type with a `const transitions = {...} satisfies Transitions` runtime object; and modeling *guards* or *side effects per transition* needs more machinery. **Complexity:** all enforcement is compile-time; the runtime lookup is O(1).

#### Q109. [Coding] Write a `TupleToUnion`, `UnionToTuple`, and `UnionToIntersection` and explain why `UnionToTuple` is fragile.

**Problem:** Convert between unions, tuples, and intersections at the type level—classic advanced exercises that expose how the type evaluator treats unions.

```typescript
// Trivial direction: tuple -> union
type TupleToUnion<T extends readonly unknown[]> = T[number];
type U1 = TupleToUnion<["a", "b", "c"]>; // "a" | "b" | "c"

// Union -> intersection, via contravariant inference
type UnionToIntersection<U> =
  (U extends any ? (x: U) => void : never) extends (x: infer I) => void
    ? I : never;
type I1 = UnionToIntersection<{ a: 1 } | { b: 2 }>; // { a: 1 } & { b: 2 }

// Union -> tuple (ORDER IS NOT GUARANTEED)
type LastOf<U> =
  UnionToIntersection<U extends any ? () => U : never> extends () => infer R ? R : never;
type UnionToTuple<U, Acc extends unknown[] = []> =
  [U] extends [never] ? Acc
    : UnionToTuple<Exclude<U, LastOf<U>>, [LastOf<U>, ...Acc]>;
type T1 = UnionToTuple<"a" | "b" | "c">; // ["a","b","c"] — but order is an impl detail!
```

`TupleToUnion` is a one-liner because indexing a tuple by `number` yields the union of its element types. **`UnionToIntersection`** is the famous trick: distributing `U` into a *function parameter position* (`(x: U) => void`) and then `infer`-ing that parameter exploits **parameter contravariance**—multiple function types are only assignable to a single inferred parameter if that parameter is the *intersection* of all the distributed types, so the compiler is forced to synthesize the intersection.

**`UnionToTuple` is the one to flag in an interview as a code smell.** It works by repeatedly extracting the "last" union member (via the same contravariant function trick to collapse the union to one element) and `Exclude`-ing it, recursing until `never`. The fragility is fundamental: **unions are unordered sets in TypeScript's model**, so "the last member" has no specified meaning—the resulting tuple order is an *unspecified implementation detail* that has changed between compiler versions, and the operation is O(n²) in instantiations (re-deriving `LastOf` each step). The senior takeaway is knowing *when not to use this*: relying on union order is building on sand; if you need ordered data, start from a tuple (`as const`) and derive the union with `T[number]`, never the reverse. These appear constantly in `type-challenges` but rarely belong in production type code.

#### Q110. [Coding] Implement a type-safe SQL-ish query result mapper: given a `SELECT` column list as a tuple of keys, infer the row shape.

**Problem:** `select(table, ["id", "email"])` should return rows typed as `Pick<Row, "id" | "email">[]`, validating that every requested column exists on the table's row type.

```typescript
interface Tables {
  users:  { id: number; email: string; name: string; createdAt: Date };
  orders: { id: number; userId: number; total: number };
}

type TableName = keyof Tables;
type Row<T extends TableName> = Tables[T];

declare function select<
  T extends TableName,
  C extends readonly (keyof Row<T>)[]
>(
  table: T,
  columns: C
): Pick<Row<T>, C[number]>[];

const rows = select("users", ["id", "email"]);
// rows: Pick<{...}, "id" | "email">[]  ==  { id: number; email: string }[]
const r0 = rows[0]!;
r0.email;        // string — typed
// r0.name;      // ERROR — not selected
// select("users", ["id", "nope"]);   // ERROR — "nope" is not a column
// select("posts", ["id"]);           // ERROR — no such table
```

The generic chain does the work: `T extends TableName` validates the table name against the known schema; `C extends readonly (keyof Row<T>)[]` constrains the column list to *actual columns of that specific table*, so a typo or a column from a different table is rejected; and the return type `Pick<Row<T>, C[number]>[]` projects the row down to exactly the selected columns by turning the tuple `C` into a union via `C[number]`. The result is end-to-end safety: the selected-column projection flows into the consumer, so accessing an unselected field is a compile error—mirroring what query builders like Kysely and Drizzle deliver.

The schema-as-a-type (`Tables`) is the single source of truth; in a real system it would be *generated* from the database (Kysely's codegen, Prisma's schema) so the types can never drift from the actual DB. **Edge cases and limits:** joins, aliases (`SELECT a AS b`), aggregates (`COUNT(*)`), and nullable columns from `LEFT JOIN` all require substantially more type machinery (typically more template-literal parsing or builder chains); this example covers the single-table projection core. **The soundness caveat** is the usual one: the *runtime* must actually return only those columns—the types describe intent, but a buggy query layer returning extra/missing fields wouldn't be caught. **Complexity:** all compile-time; runtime is whatever the driver does.

#### Q111. [Coding] Write a `createSelector`-style memoized selector with typed input selectors and a typed combiner.

**Problem:** Reselect's `createSelector` takes N input selectors and a combiner; the combiner's parameters must match each input selector's return type, in order. Type this precisely.

```typescript
type Selector<State, R> = (state: State) => R;

function createSelector<State, Inputs extends readonly unknown[], Result>(
  inputs: { [K in keyof Inputs]: Selector<State, Inputs[K]> },
  combiner: (...args: Inputs) => Result
): Selector<State, Result> {
  let lastInputs: Inputs | undefined;
  let lastResult: Result;
  return (state: State): Result => {
    const values = inputs.map((sel) => sel(state)) as unknown as Inputs;
    const changed =
      !lastInputs || values.some((v, i) => !Object.is(v, lastInputs![i]));
    if (changed) {
      lastResult = combiner(...values);
      lastInputs = values;
    }
    return lastResult;
  };
}

interface AppState { items: { price: number }[]; taxRate: number }

const selectTotal = createSelector(
  [
    (s: AppState) => s.items,    // Inputs[0] = { price: number }[]
    (s: AppState) => s.taxRate,  // Inputs[1] = number
  ],
  (items, taxRate) =>            // items, taxRate are correctly typed, in order
    items.reduce((sum, i) => sum + i.price, 0) * (1 + taxRate)
);
const total: number = selectTotal({ items: [{ price: 10 }], taxRate: 0.1 });
```

The signature's centerpiece is the **mapped tuple type** `{ [K in keyof Inputs]: Selector<State, Inputs[K]> }`: mapping over the *keys of a tuple* (`keyof Inputs` includes the numeric indices) produces another tuple of the same arity, where each position is a selector returning `Inputs[K]`. That ties input selector #0's return type to combiner parameter #0, and so on—so if you reorder the inputs or the combiner's params, it's a compile error. The combiner's `(...args: Inputs)` spreads the same tuple into its parameter list.

This is exactly how Reselect achieves its typing, and it demonstrates a pattern that pure generics over `unknown[]` can't: **positional correspondence between two tuples**. The memoization is reference-equality (`Object.is`) on the *inputs*, which is why selectors must return stable references (the classic Reselect footgun—returning a fresh object/array each call defeats memoization). **Edge cases:** the `as unknown as Inputs` cast is unavoidable because `.map` widens to a plain array and erases the tuple structure—an internal implementation detail hidden from callers; deep equality or a custom equality function are common extensions. **Complexity:** O(n) input comparison per call, O(1) when cached.

#### Q112. [Coding] Implement a `safeFetch` that returns a discriminated `Result` and narrows the JSON body with a runtime validator.

**Problem:** Combine the `Result<T,E>` pattern, runtime validation, and proper error typing into a production-grade `fetch` wrapper—no thrown exceptions for expected failures.

```typescript
type Ok<T>  = { ok: true;  value: T };
type Err<E> = { ok: false; error: E };
type Result<T, E> = Ok<T> | Err<E>;

type FetchError =
  | { type: "network"; cause: unknown }
  | { type: "http"; status: number; body: string }
  | { type: "parse"; issues: string };

interface Validator<T> { parse(input: unknown): T } // Zod-compatible shape

async function safeFetch<T>(
  url: string,
  schema: Validator<T>,
  init?: RequestInit
): Promise<Result<T, FetchError>> {
  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (cause) {
    return { ok: false, error: { type: "network", cause } };
  }
  if (!res.ok) {
    return { ok: false, error: { type: "http", status: res.status, body: await res.text() } };
  }
  try {
    const json: unknown = await res.json();
    return { ok: true, value: schema.parse(json) }; // throws on bad shape
  } catch (e) {
    return { ok: false, error: { type: "parse", issues: String(e) } };
  }
}

// Usage with a Zod schema (z.ZodType satisfies Validator<T>):
const result = await safeFetch("/api/user", UserSchema);
if (result.ok) {
  result.value;             // fully typed & runtime-validated User
} else {
  switch (result.error.type) {
    case "network": /* offline / DNS */ break;
    case "http":    console.error(result.error.status); break;
    case "parse":   /* server contract drift */ break;
  }
}
declare const UserSchema: Validator<{ id: string; name: string }>;
```

This design makes **all three failure modes part of the type**: network failure (fetch rejects), HTTP error status (4xx/5xx), and schema mismatch (the server returned the wrong shape). The caller cannot accidentally use `value` without first checking `result.ok`—the discriminated union forbids it—and once in the error branch, the `FetchError` union forces handling each category, often with different UX (retry vs. log vs. alert). Crucially, the body is `await res.json()` typed as `unknown` and only becomes `T` *after* `schema.parse`, so there is no `as User` lie: the type and the runtime check share one source of truth.

The `Validator<T>` interface (just `parse(unknown): T`) decouples the wrapper from Zod specifically—any library matching that shape works, which is good API hygiene. **Why Result over throwing:** expected failures (a 404, offline) are *control flow*, not exceptions; modeling them as values means the compiler enforces handling and there's no invisible `throw` jumping past callers. **Edge cases:** distinguishing a JSON-parse error from a schema-validation error (both land in the `try`) may warrant separate branches; aborting via `AbortSignal` is a common addition. **Complexity:** O(1) plus network and validation cost.

### 🔴 Expert — extended

#### Q113. [Coding] Implement `ObjectEntries`/`fromEntries` that preserve key-value correspondence, and explain the soundness limits.

**Problem:** The built-in `Object.entries` returns `[string, T][]` and `Object.fromEntries` returns a wide record. Write typed wrappers that preserve the precise key→value mapping for *closed* objects.

```typescript
// Typed entries: tuple union of [key, value-at-key]
type Entries<T> = { [K in keyof T]: [K, T[K]] }[keyof T];

function objectEntries<T extends object>(obj: T): Entries<T>[] {
  return Object.entries(obj) as Entries<T>[];
}

// Typed fromEntries: rebuild the record from a tuple-union of entries
function objectFromEntries<const E extends readonly (readonly [PropertyKey, unknown])[]>(
  entries: E
): { [K in E[number][0]]: Extract<E[number], readonly [K, unknown]>[1] } {
  return Object.fromEntries(entries) as any;
}

const point = { x: 1, y: "two", z: true };
for (const [k, v] of objectEntries(point)) {
  // k: "x" | "y" | "z";  v: number | string | boolean — correlated per entry
}
const rebuilt = objectFromEntries([["a", 1], ["b", "hi"]] as const);
// rebuilt: { a: 1; b: "hi" }
```

`Entries<T>` is the precise type: mapping each key `K` to the tuple `[K, T[K]]` and then indexing by `[keyof T]` produces the *union of per-key tuples*—so when you destructure `[k, v]`, `k` and `v` are correlated as a discriminated pair rather than two independent unions. `objectFromEntries` runs the inverse with a `const` type parameter (to keep the entries' literal types and tuple-ness) and a mapped type that, for each key in the union, `Extract`s the matching entry to recover its value type.

**The soundness caveat is the whole point of the question.** `objectEntries` is *unsound for the same reason `Object.keys` is typed `string[]`*: a value typed `T` may, at runtime, structurally carry **extra** properties not in `T` (any subtype is assignable), so the real entries array could contain keys outside `keyof T`—yet the typed wrapper claims it won't. That's why the standard library deliberately *doesn't* type it this way. The wrapper is therefore only safe when you **own and closed-construct** the object (a literal you just built, a validated record), and you accept the localized `as Entries<T>[]` assertion as a promise you're upholding. The expert framing: this is the same open-world tension as `Object.keys`—the precise type is a *convenience for known-exact objects*, not a guarantee, and shipping it as a team helper means documenting "only for objects you control." **Complexity:** O(n) in property count; the typing is compile-time.

#### Q114. [Coding] Build a `Trie`/prefix-autocomplete typed so that `add`ed words constrain what `has` accepts—then discuss why full type-level tracking is impractical.

**Problem:** Design a generic container API and reason about the boundary between what TypeScript's types *can* track and what belongs at runtime.

```typescript
class Trie {
  private root: TrieNode = { children: new Map(), isEnd: false };

  add(word: string): this {
    let node = this.root;
    for (const ch of word) {
      let next = node.children.get(ch);
      if (!next) { next = { children: new Map(), isEnd: false }; node.children.set(ch, next); }
      node = next;
    }
    node.isEnd = true;
    return this; // polymorphic `this` for chaining + subclassing
  }

  has(word: string): boolean { return this.find(word)?.isEnd ?? false; }

  startsWith(prefix: string): string[] {
    const node = this.find(prefix);
    if (!node) return [];
    const out: string[] = [];
    this.collect(node, prefix, out);
    return out;
  }

  private find(s: string): TrieNode | undefined {
    let node: TrieNode | undefined = this.root;
    for (const ch of s) { node = node?.children.get(ch); if (!node) return undefined; }
    return node;
  }
  private collect(node: TrieNode, prefix: string, out: string[]): void {
    if (node.isEnd) out.push(prefix);
    for (const [ch, child] of node.children) this.collect(child, prefix + ch, out);
  }
}
interface TrieNode { children: Map<string, TrieNode>; isEnd: boolean }

const t = new Trie().add("cat").add("car").add("dog");
t.startsWith("ca"); // ["cat", "car"]
t.has("cat");       // true
```

The runtime structure is standard, but the *type-system* lesson is the interesting part. You *could* imagine tracking the exact set of added words at the type level—making `add<W extends string>(w: W)` accumulate `W` into a phantom union so `has` only accepts known words. That's expressible (a generic `Trie<Words extends string = never>` whose `add` returns `Trie<Words | W>`), but it is **almost always the wrong call**: the words are *runtime data* (read from a file, user input, a dictionary), not literals known at compile time, so the type-level set would be `string` in every real scenario and the machinery would be pure overhead plus brutal error messages. This is the recurring staff-level judgment—**just because the type system *can* express something doesn't mean it should.**

The right typing here is modest and correct: `add` returns `this` (polymorphic, so subclasses chain and `extends Trie` keeps the derived type), `find` returns `TrieNode | undefined` to force the missing-prefix check, and `has`/`startsWith` return plain `boolean`/`string[]`. **Where types *do* earn their keep:** the `TrieNode` interface documents the invariant, `private` enforces encapsulation, and `?.` + `?? false` handle the absent-node path soundly. The interview signal is articulating the dividing line: **types are for the *contract and structure that's known statically*; the *contents* of a runtime container belong at runtime.** **Complexity:** `add`/`has` are O(L) in word length; `startsWith` is O(L + size of subtree).

#### Q115. [Coding] Implement a type-level `Add`/`Subtract` on small natural numbers using tuple-length arithmetic, and justify when (not) to do arithmetic in types.

**Problem:** TypeScript has no numeric type-level operators, but you can do bounded arithmetic via tuple lengths. Implement it and reason about the limits.

```typescript
// Build a tuple of length N
type BuildTuple<N extends number, Acc extends unknown[] = []> =
  Acc["length"] extends N ? Acc : BuildTuple<N, [...Acc, unknown]>;

// Add: concatenate two length-N tuples, read the resulting length
type Add<A extends number, B extends number> =
  [...BuildTuple<A>, ...BuildTuple<B>]["length"] extends infer N
    ? N extends number ? N : never
    : never;

// Subtract: peel B elements off a length-A tuple, read remaining length
type Subtract<A extends number, B extends number> =
  BuildTuple<A> extends [...BuildTuple<B>, ...infer Rest] ? Rest["length"] : never;

type S = Add<3, 4>;       // 7
type D = Subtract<10, 4>; // 6
// type X = Add<500, 600>; // ERROR — exceeds the tuple-length/recursion limits
```

The trick is that a tuple type *carries its length as a literal number* via the `["length"]` property, and tuple **spread** concatenates lengths. So addition is "build two tuples, spread them together, read the combined length," and subtraction is "build the larger tuple, pattern-match-remove the smaller one's worth of elements, read what's left." The recursive `BuildTuple` accumulates `unknown` elements until `Acc["length"] extends N`.

This is a genuine demonstration that the type system is **Turing-complete**, and it underlies real type-level utilities (computing the arity of a curried function, indexing into fixed-size tuples, bounded loop counters in template-literal parsers). **But the practical limits are severe and the honest answer is "rarely do this."** Tuple construction is bounded by the recursion-instantiation depth, so numbers above a few thousand error with *"Type instantiation is excessively deep,"* every operation re-builds tuples from scratch (no memoized number ops), and the compile-time cost is real—a module doing type-level math noticeably slows the checker and the editor. There are no negatives, no fractions, and overflow is a hard wall, not wraparound. The staff-level framing mirrors Q45/Q83: type-level arithmetic is a fascinating proof of expressive power and occasionally indispensable for a *small bounded* parser or arity calculation, but for anything beyond tiny numbers it's "type astronomy"—the cost in compile time and unreadability vastly exceeds the benefit, and the work belongs at runtime where JS has actual integers.

#### Q116. [Coding] Write a `Spread`/`Merge` type that mimics object-spread semantics at the type level (later keys win, optionals handled).

**Problem:** `{ ...a, ...b }` at runtime: `b`'s keys override `a`'s, and an *optional* key in `b` doesn't fully erase `a`'s value. Model this precisely as a type—naive `A & B` gets it wrong.

```typescript
// Keys present (and required) in B override A entirely.
// Keys optional in B union with A's value (since b.key might be undefined).
type OptionalKeys<T> = { [K in keyof T]-?: undefined extends T[K] ? K : never }[keyof T];
type RequiredKeys<T> = Exclude<keyof T, OptionalKeys<T>>;

type Merge<A, B> = {
  // keys only in A
  [K in Exclude<keyof A, keyof B>]: A[K];
} & {
  // required keys in B fully override
  [K in RequiredKeys<B>]: B[K];
} & {
  // optional keys in B: A's value (if any) survives the undefined case
  [K in OptionalKeys<B>]: K extends keyof A ? A[K] | Exclude<B[K], undefined> : B[K];
};

// Flatten the intersection into a single readable object type
type Flatten<T> = { [K in keyof T]: T[K] } & {};

type R = Flatten<Merge<{ a: number; b: string }, { b?: boolean; c: null }>>;
// { a: number; b: string | boolean; c: null }
```

A plain intersection `A & B` is wrong because for a shared key it produces `A[K] & B[K]` (e.g. `string & boolean` = `never`), whereas spread *replaces*: `b`'s value wins. The accurate model splits B's keys into **required** (which fully override A's value) and **optional** (where, because `b.key` could be `undefined` at runtime, the merged value is `A[K] | (B[K] without undefined)`)—exactly mirroring runtime behavior where `{ ...a, ...b }` with `b.key === undefined` still overwrites but to `undefined`, while a *missing* optional key leaves `a`'s value. The `OptionalKeys`/`RequiredKeys` helpers use `-?` (strip optionality so the mapped check sees the real value type) and test `undefined extends T[K]`.

The `Flatten<T> = { [K in keyof T]: T[K] } & {}` idiom is a widely-used trick to collapse a multi-part intersection into one flat object type so tooltips show `{ a; b; c }` instead of `A & B & C`—a real DX win in libraries. **Why it matters:** state-management libraries, config mergers, and component-prop spreaders need this to type `{ ...defaults, ...overrides }` correctly; getting it wrong yields `never` properties that surface as confusing downstream errors. **Edge cases:** index signatures, `readonly` propagation, and deep merge each add complexity—this models *shallow* spread, which is what the spread operator actually does. **Complexity:** compile-time, proportional to key count.

#### Q117. [Theory] Explain how TypeScript handles correlated unions and the "expression is not callable / not assignable to never" error when indexing a union with a generic key.

A **correlated union** is a pattern where two or more members of a type vary *together*—e.g. a config where `type: "number"` always pairs with `validate: (n: number) => boolean`, and `type: "string"` with `validate: (s: string) => boolean`. When you write generic code that indexes into such a union with a *variable* key `K`, TypeScript frequently produces the infamous errors *"This expression is not callable. Each member of the union type ... has signatures, but none of those signatures are compatible"* or assignments failing with *"not assignable to never."* The root cause: when `K` is a type parameter, the compiler computes the member type as the **union of all possibilities** and then, for a *write* or *call*, requires assignability to the **intersection** of those possibilities—which collapses to `never` for genuinely incompatible members. It cannot prove that the `type` discriminant and the `validate` function refer to the *same* union member, because it loses the correlation once it generalizes over `K`.

```typescript
type Field =
  | { type: "number"; value: number; format: (v: number) => string }
  | { type: "string"; value: string; format: (v: string) => string };

function render(f: Field): string {
  // f.format(f.value) — works because `f` is narrowed by control flow per-branch
  switch (f.type) {
    case "number": return f.format(f.value); // OK — narrowed to the number member
    case "string": return f.format(f.value); // OK
  }
}

// But generic indexing breaks the correlation:
function bad<K extends Field["type"]>(items: Record<K, Field>, k: K) {
  const f = items[k];
  // f.format(f.value); // ERROR — compiler sees format: ((number)=>...) | ((string)=>...)
  //                    // and value: number | string, can't prove they correlate
}
```

The fix landed conceptually with TS 3.2's improvements and is documented in the team's "correlated union types" guidance (microsoft/TypeScript#47109): **refactor the union into a single mapped/indexed type keyed by the discriminant**, so the correlation is encoded structurally rather than as a flat union. Concretely, define `type FieldMap = { number: { value: number; format: (v: number) => string }; string: {...} }` and write the generic function over `FieldMap[K]`, so indexing preserves the link between `value` and `format`. Alternatively, **narrow before generalizing** (do the discriminant `switch` first, as in `render`), or—pragmatically—use a localized helper with an internal `as` once you've manually verified the correlation. The expert insight is naming *why* it happens: generalizing over a key turns a *correlated* (per-member) relationship into an *independent* product of unions, and the read-vs-write asymmetry (union for reads, intersection for writes) is what surfaces the `never`. Recognizing the pattern immediately—"this is a correlated union; I need to key it by the discriminant"—is a strong senior signal because the raw error message points nowhere near the real cause.

#### Q118. [Coding] Implement a `Lazy<T>` / once-evaluated value and a typed `Memo` decorator using TS 5.0 standard decorators.

**Problem:** Build a lazy value that computes once on first access, plus a class getter decorator (`@memo`) that caches a computed property—using the *standard* (Stage 3) decorator API, not the legacy one.

```typescript
class Lazy<T> {
  private computed = false;
  private value!: T;
  constructor(private readonly factory: () => T) {}
  get(): T {
    if (!this.computed) { this.value = this.factory(); this.computed = true; }
    return this.value;
  }
}

const expensive = new Lazy(() => {
  console.log("computing...");
  return Array.from({ length: 1000 }, (_, i) => i * i);
});
expensive.get(); // logs once
expensive.get(); // cached, no log

// Standard (TS 5.0) accessor/getter decorator that memoizes a getter's result
function memo<This, Return>(
  target: (this: This) => Return,
  context: ClassGetterDecoratorContext<This, Return>
) {
  const cache = new WeakMap<object, Return>();
  return function (this: This): Return {
    const key = this as object;
    if (!cache.has(key)) cache.set(key, target.call(this));
    return cache.get(key)!;
  };
}

class Report {
  constructor(private rows: number[]) {}
  @memo get total(): number {
    console.log("summing...");
    return this.rows.reduce((a, b) => a + b, 0);
  }
}
const rep = new Report([1, 2, 3]);
rep.total; // logs "summing..." once
rep.total; // cached
```

`Lazy<T>` is the simplest expression of deferred-once evaluation: the `computed` flag distinguishes "not yet run" from "ran and produced a falsy/undefined value" (the same falsy-trap discipline as memoization), and `value!` uses a definite-assignment assertion because the field is set lazily rather than in the constructor. It's `O(1)` after first access and is the building block for lazy module initialization and expensive singletons.

The `@memo` decorator showcases the **TS 5.0 standard decorator signature**: a getter decorator receives `(target, context)` where `target` is the original getter function and `context` is a `ClassGetterDecoratorContext` carrying `kind: "getter"`, `name`, `access`, and `addInitializer`. It returns a *replacement* getter. Using a `WeakMap` keyed by the instance (`this`) means each instance memoizes independently *and* entries are garbage-collected with the instance—avoiding the leak a per-class `Map` would cause. This is deliberately different from the legacy `experimentalDecorators` API (which used property descriptors and `reflect-metadata`); the standard form needs no `emitDecoratorMetadata` and is the future-proof choice for framework-free libraries. **Edge cases:** memoizing a getter assumes the underlying data is immutable for the instance's lifetime—if `rows` can change, the cache is stale; `addInitializer` would let you reset on construction. **Complexity:** first access pays the getter's cost; subsequent accesses are O(1).

#### Q119. [Behavioral] Tell me about a time you had to make a judgment call between maximal type safety and team velocity on a high-stakes project. (Senior/Staff, STAR)

Use a **STAR** structure and let the answer reveal *engineering judgment under organizational constraints*, not just TypeScript knowledge.

*Situation:* We were six weeks from launching a payments-adjacent feature across three teams (~25 engineers, mixed TS experience). A staff engineer on an adjacent team had prototyped the money-handling layer with an elaborate branded-type and phantom-unit system (`Cents`, `Dollars`, `Currency<C>`, compile-time currency-mismatch prevention via conditional types) that was genuinely impressive and caught a real class of bugs—but it required every call site to thread generic currency parameters and use smart constructors, and three teams were already struggling against it in review.

*Task:* As the tech lead accountable for both *correctness* (this is money—mixing cents and dollars or USD and EUR is a real incident) and *the deadline*, I had to decide how much of that safety to keep, and own the consequences either way.

*Action:* I refused to frame it as "safe vs. fast" and instead separated the *threat model* from the *mechanism*. I identified the two failure modes that actually mattered—(1) unit confusion (cents vs. dollars) and (2) currency mixing—and asked which the *types* must prevent versus which a *runtime guard + test* could cover acceptably. I kept a **single branded `Money` type** (`{ amount: bigint; currency: Currency }` with `bigint` minor units, so cents-vs-dollars is structurally impossible and rounding is exact) and a small set of smart constructors and arithmetic helpers that *runtime-check* currency equality and throw, backed by exhaustive unit tests. I dropped the compile-time currency-generic threading entirely. I wrote a one-page design note with the trade-off explicit—"we accept that *currency mismatch* is caught at runtime+test rather than compile time, because the generic threading cost three teams measurable velocity and produced unreadable errors; we do *not* compromise on unit safety"—and reviewed it with the prototype's author so it was a *shared* decision, not a veto. I brought the `--extendedDiagnostics` numbers showing the generic version's compile/editor cost and the review-comment volume as evidence.

*Result:* We shipped on time; the `Money` type caught two real unit bugs in the following quarter (the runtime currency guard caught one mismatch in a test, before prod). Onboarding to the payments code dropped from "ask the one expert" to self-serve. The prototype author later reused the `bigint`-minor-units pattern elsewhere and told me the framing—*"which failures must types prevent, and which can runtime+tests own?"*—changed how they scoped type-safety effort. **The lesson I emphasize:** at staff level, type-safety decisions are *risk-allocation* decisions across compile-time, runtime, and tests, made against an explicit threat model and the team's ability to maintain the result—and the durable move is converting a contentious technical preference into a documented, evidence-backed, *shared* decision rather than winning the argument.

#### Q120. [Coding] Implement a `DeepPick` that selects nested fields via dot-paths and reconstructs a pruned object type.

**Problem:** Given `DeepPick<T, "user.name" | "settings.theme">`, produce a type containing only those nested paths—the structural inverse of the `get`-by-path exercise (Q19), used by GraphQL-style field selection.

```typescript
// Split a dot-path into head/tail and rebuild the nested structure
type DeepPick<T, P extends string> =
  UnionToIntersection<
    P extends `${infer Head}.${infer Rest}`
      ? Head extends keyof T
        ? { [K in Head]: DeepPick<T[Head], Rest> }
        : never
      : P extends keyof T
        ? { [K in P]: T[P] }
        : never
  >;

type UnionToIntersection<U> =
  (U extends any ? (x: U) => void : never) extends (x: infer I) => void ? I : never;

interface Source {
  user: { id: string; name: string; email: string };
  settings: { theme: "dark" | "light"; lang: string };
  meta: { createdAt: Date };
}

type Picked = DeepPick<Source, "user.name" | "settings.theme">;
// { user: { name: string } } & { settings: { theme: "dark" | "light" } }
//   → effectively { user: { name: string }; settings: { theme: "dark" | "light" } }
```

The type distributes over the *union of paths* (each path string is processed independently because `P` is a naked parameter), and for each path recursively peels the head segment, rebuilding `{ [head]: DeepPick<T[head], rest> }` until it hits a leaf key. The catch is that distributing produces a *union* of single-branch objects (`{ user: ... } | { settings: ... }`), but we want them *merged* into one object—hence wrapping the whole thing in **`UnionToIntersection`** (from Q109) to fold `{user} | {settings}` into `{user} & {settings}`. This is the structural dual of Q19's `PathValue`: Q19 *reads* one value at a path; `DeepPick` *constructs* a pruned shape from many paths.

This pattern is the type-level engine behind GraphQL field selection, sparse-fieldset REST APIs (`?fields=user.name,settings.theme`), and projection in typed ODMs—where the response type must reflect exactly the requested subset so callers can't read fields they didn't ask for. **Edge cases and limits:** arrays-in-path, optional intermediate keys, and overlapping paths at the same branch (`"user.name" | "user.email"` must merge *within* `user`, which the intersection handles but can produce harder-to-read tooltips—add a `Flatten` helper) all add complexity; very deep paths hit recursion limits. **The soundness note** is identical to all path-based types: the *runtime* projection function must actually prune to those fields, or the type over-promises. **Complexity:** compile-time, proportional to path count × path depth.

#### Q121. [Coding] Build a typed dependency-injection container where `resolve(token)` returns the registered type, with no `any`.

**Problem:** Implement a minimal IoC container so that registering a factory under a token, then `resolve(token)`, returns the *exact* type the factory produces—the typing core of NestJS/InversifyJS, without decorators.

```typescript
// A token carries its resolved type as a phantom parameter
interface Token<T> { readonly key: symbol; readonly _type?: T }
function token<T>(description: string): Token<T> {
  return { key: Symbol(description) };
}

class Container {
  private factories = new Map<symbol, (c: Container) => unknown>();
  private singletons = new Map<symbol, unknown>();

  register<T>(token: Token<T>, factory: (c: Container) => T): this {
    this.factories.set(token.key, factory as (c: Container) => unknown);
    return this;
  }

  resolve<T>(token: Token<T>): T {
    if (this.singletons.has(token.key)) return this.singletons.get(token.key) as T;
    const factory = this.factories.get(token.key);
    if (!factory) throw new Error(`No provider for ${String(token.key)}`);
    const instance = factory(this) as T;
    this.singletons.set(token.key, instance);
    return instance;
  }
}

// Define typed tokens
interface Logger { log(msg: string): void }
interface Db { query(sql: string): unknown[] }
const LoggerToken = token<Logger>("Logger");
const DbToken = token<Db>("Db");

const container = new Container()
  .register(LoggerToken, () => ({ log: (m) => console.log(m) }))
  .register(DbToken, (c) => {
    const logger = c.resolve(LoggerToken); // resolve dependency, typed as Logger
    return { query: (sql) => { logger.log(sql); return []; } };
  });

const db = container.resolve(DbToken); // db: Db — fully typed, no `any`
db.query("SELECT 1");
// container.resolve(token<number>("x")); // would throw at runtime: no provider
```

The keystone is the **phantom-typed `Token<T>`**: the token is a runtime `symbol` (for map lookup and guaranteed uniqueness) carrying `T` only at the type level via the optional `_type?: T` field, which never exists at runtime. `register<T>` constrains the factory to return exactly `T`, and `resolve<T>` reads `T` back off the token—so `container.resolve(DbToken)` is typed `Db` with no annotation and no `any` leaking to consumers. The internal `Map<symbol, unknown>` plus localized `as T` casts are the unavoidable bridge between the erased type layer and the runtime registry; they're hidden behind the public generic API, so callers stay fully safe.

This is exactly how type-safe DI works without decorator metadata (Awilix, tsyringe's token mode, and the manual side of NestJS): the symbol provides runtime identity, the phantom type provides compile-time safety, and `register` ties them together. **Trade-offs vs. decorator-based DI:** this is fully explicit (no `reflect-metadata`, no `emitDecoratorMetadata`, works under any transpiler and `isolatedModules`), at the cost of manually declaring tokens rather than auto-wiring from constructor parameter types. **Edge cases:** circular dependencies (A needs B needs A) deadlock the lazy singleton resolution and need detection; this caches as singletons—transient/scoped lifetimes need extra bookkeeping; resolving an unregistered token fails at *runtime*, not compile time, because tokens are values, not the type graph. **Complexity:** `resolve` is O(1) amortized (singleton cache) plus the factory's own cost.

#### Q122. [Theory] Explain how `infer` with constraints (`infer R extends X`), multiple `infer` sites, and `infer` in template literals interact, and the subtleties of inference in covariant vs contravariant positions.

`infer` introduces a fresh type variable inside a conditional type's `extends` clause, but its behavior has several layers that distinguish a deep understanding from rote utility-type usage. **Constrained inference** (`infer R extends string`, TS 4.7+) lets you both *extract* and *bound* in one step: the conditional only takes the true branch if the inferred type also satisfies the constraint, and—critically—within a template-literal context, `infer N extends number` will *coerce* the matched string to the `number` type rather than leaving it as a string literal. This is what makes type-level parsing of numeric strings possible.

```typescript
// Constrained infer coerces a string-literal match to number:
type ParseInt<S extends string> = S extends `${infer N extends number}` ? N : never;
type Five = ParseInt<"5">; // 5 (a number literal), not "5"

// Multiple infer sites at the SAME variable union the candidates (covariant position):
type Returns<T> = T extends { a: () => infer R; b: () => infer R } ? R : never;
type R1 = Returns<{ a: () => string; b: () => number }>; // string | number

// The SAME variable in CONTRAVARIANT (parameter) position intersects candidates:
type Params<T> = T extends { a: (x: infer P) => void; b: (x: infer P) => void } ? P : never;
type P1 = Params<{ a: (x: string) => void; b: (x: number) => void }>; // string & number = never
```

The deepest subtlety is **how multiple `infer` sites for the same variable combine, and it depends on variance**: when the variable appears in **covariant (output/return) positions**, the compiler takes the **union** of the candidates; when it appears in **contravariant (parameter) positions**, it takes the **intersection**. This mirrors the soundness rules—a value that must satisfy *both* output positions can be either (union), while a function parameter that must accept *both* call shapes must accept their intersection. It is exactly the same machinery that powers `UnionToIntersection` (Q109): force a type into parameter position and inference intersects it. There are further wrinkles: `infer` in a *rest* position of a tuple (`[infer Head, ...infer Tail]`) drives variadic-tuple recursion; `infer` inside template literals is *greedy/non-greedy* depending on what follows (a trailing fixed delimiter makes it match minimally); and an unconstrained `infer` that the compiler can't pin falls back to `unknown`. The expert framing: `infer` isn't just "extract a type"—it's a *unification* mechanism whose result is governed by variance, and knowing that covariant-union / contravariant-intersection rule explains otherwise-baffling results like "why did my inferred parameter type become `never`?" (the answer: you inferred the same variable from two incompatible contravariant positions, and their intersection is empty).

## ✅ Key Takeaways

- TypeScript is structurally typed and its types are **fully erased at runtime**—use it as a compile-time linter + tooling engine, and add runtime validation (Zod et al.) at every trust boundary.
- Turn on **`strict`** from day one on new projects; migrate legacy code one flag and one directory at a time with a ratcheting error budget.
- Prefer `interface` for extensible object/class contracts and `type` for unions, tuples, mapped, and conditional types—then enforce one convention via lint.
- Master the **narrowing toolbox** (`typeof`, `instanceof`, `in`, discriminated unions, user guards) and use `never`-based **exhaustiveness checks** so adding a variant breaks the build.
- Generics + utility types (`Partial`/`Pick`/`Omit`/`Record`/`ReturnType`) + mapped/conditional types + `infer` + template literals let you derive types from one source of truth and eliminate duplication.
- The type system is intentionally **unsound** (assertions, `any`, bivariant methods, unchecked index access); senior engineers know exactly where the holes are and contain them.
- Choose decorators deliberately: legacy `experimentalDecorators` for Angular/NestJS today vs. TC39 standard decorators (TS 5.0+) for new framework-free code.
- Optimize big-repo builds with **project references**, `incremental`, `skipLibCheck`, type/transpile separation (esbuild + `tsc --noEmit`), and watch for the native `tsgo`/TS 7 compiler.

## ⚠️ Common Pitfalls

- Casting untyped data with `as SomeType` and trusting it—this lies to the compiler and crashes at runtime; validate instead.
- Sprinkling `any` (or implicit `any`) which silently disables checking and propagates; use `unknown` + narrowing.
- Forgetting `as const`, so config values widen to `string`/`number` and `keyof typeof` derivations break.
- Truthiness narrowing bugs: `if (count)` skips the valid value `0`; `if (name)` skips `""`.
- Assuming `Object.keys(obj)` is `(keyof T)[]` (it is `string[]`) and that index access can't be `undefined`.
- "Type astronomy": recursive conditional types so clever they explode compile time and no teammate can maintain them.
- Const enums and numeric enums breaking under `isolatedModules`/esbuild; prefer `as const` objects.
- Shipping a library without checking ESM/CJS `.d.ts` resolution (`attw`) and leaking unexported/internal types.
- Relying on non-null assertions (`!`) to silence errors instead of proving the value exists.

## 📚 Further Reading

- **Official TypeScript Handbook** — typescriptlang.org/docs/handbook (the canonical, continuously updated reference).
- **Effective TypeScript** by Dan Vanderkam (2nd ed.) — 83+ specific, battle-tested items; the best single book for leveling up.
- **Programming TypeScript** by Boris Cherny (O'Reilly) — strong on the type system's theory and design rationale.
- **Type Challenges** (github.com/type-challenges/type-challenges) — graded exercises for mapped/conditional/template-literal type mastery.
- **Total TypeScript** by Matt Pocock (totaltypescript.com) — advanced patterns, generics, and wizardry workshops.
- **TypeScript Deep Dive** by Basarat Ali Syed (basarat.gitbook.io/typescript) — free, thorough community handbook.
- **Are The Types Wrong? (`attw`)** + the TypeScript release notes per version — essential for library authors and staying current through TS 5.x and the upcoming native compiler.
