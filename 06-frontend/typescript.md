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
