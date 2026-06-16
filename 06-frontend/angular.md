# Angular (incl. v17–21) — Interview Preparation Guide

A deep, authoritative interview guide for Angular spanning fundamentals through the modern reactive era: standalone components, signals, zoneless change detection, the new built-in control flow, `@defer`, and SSR with hydration. Knowledge is current through Angular 21 (2026).

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

### Q1. [Theory] What is Angular and how does it differ from AngularJS and from libraries like React?

Angular (2+) is a full, opinionated **framework** built on TypeScript, maintained by Google, that ships routing, forms, HTTP, dependency injection, and a CLI out of the box. AngularJS (1.x) was a separate, now end-of-life framework using `$scope`, dirty-checking digest cycles, and directives like `ng-controller`; modern Angular shares only the name and was a complete rewrite. The key difference from React is scope: React is a view **library** that leaves routing, DI, and state management to the ecosystem, whereas Angular is "batteries-included" and prescribes structure. Angular's distinctive pillars are **dependency injection**, **decorators/metadata** (`@Component`, `@Injectable`), and ahead-of-time (AOT) template compilation. The trade-off: Angular has a steeper learning curve but enforces consistency across large teams, which is why it dominates in enterprise. Since v17 the framework has modernized aggressively with signals and standalone APIs, narrowing the boilerplate gap with React.

### Q2. [Theory] What is a component and what are its three core building blocks?

A component is the fundamental UI unit in Angular: a class decorated with `@Component` that controls a patch of screen called a view. Its three core pieces are the **template** (HTML defining structure, with Angular binding syntax), the **class** (TypeScript holding state and behavior), and the **metadata** (the decorator config: `selector`, `template`/`templateUrl`, `styles`, `imports`). Components form a tree rooted at the bootstrap component, and they communicate via `@Input()`/`@Output()` (or the newer `input()`/`output()` signal-based functions in v17.1+). A component is really just a directive with a template, which is why directives and components share much of the same lifecycle and DI machinery.

### Q3. [Coding] Show a minimal standalone component with input, output, and two-way binding.

**Problem:** Build a counter component that accepts an initial value, emits when the count changes, and supports `[(value)]` two-way binding — using modern signal APIs (Angular 17.1+).

```typescript
import { Component, model, output, computed } from '@angular/core';

@Component({
  selector: 'app-counter',
  standalone: true, // default and implicit since v19
  template: `
    <button (click)="dec()">-</button>
    <span>{{ value() }}</span>
    <button (click)="inc()">+</button>
    <small>doubled: {{ doubled() }}</small>
  `,
})
export class CounterComponent {
  // model() creates a writable signal that also wires two-way [(value)] binding
  value = model<number>(0);
  changed = output<number>();

  doubled = computed(() => this.value() * 2);

  inc() { this.value.update(v => v + 1); this.changed.emit(this.value()); }
  dec() { this.value.update(v => v - 1); this.changed.emit(this.value()); }
}
```

Usage in a parent: `<app-counter [(value)]="count" (changed)="onChange($event)" />`.

**Edge cases:** `model()` requires no separate `@Output` named `valueChange` — it generates one automatically. If you only need one-way input, use `input()`; if it must be provided, use `input.required<number>()`. **Complexity:** all operations are O(1).

### Q4. [Theory] Explain data binding types in Angular.

Angular has four binding flavors. **Interpolation** `{{ expr }}` renders a value into text. **Property binding** `[prop]="expr"` sets a DOM property or component input one-way (parent → child). **Event binding** `(event)="handler($event)"` flows data the other way (child/DOM → parent). **Two-way binding** `[(ngModel)]="x"` or `[(value)]="x"` is syntactic sugar combining a property binding and an event binding (`[x]` + `(xChange)`) — the so-called "banana in a box." Property binding binds to **properties**, not attributes; `[disabled]="false"` correctly removes the disabled state, whereas the HTML attribute `disabled="false"` would still disable the element. Understanding that bindings target the live DOM property is a frequent source of bugs for newcomers.

### Q5. [Practical] When would you use a service, and how do you create and inject one?

You use a service for any logic that isn't tied to a single view: HTTP calls, shared state, business rules, logging, caching. Putting this in services keeps components thin and testable. You create one with `@Injectable({ providedIn: 'root' })`, which registers it as an application-wide singleton and makes it **tree-shakable** (removed from the bundle if never injected).

```typescript
@Injectable({ providedIn: 'root' })
export class UserService {
  private http = inject(HttpClient);
  getUser(id: string) { return this.http.get<User>(`/api/users/${id}`); }
}

// In a component:
export class ProfileComponent {
  private users = inject(UserService);
}
```

In production I prefer the `inject()` function over constructor injection because it works in field initializers, composes well with helper functions, and is required for things like `takeUntilDestroyed()`. The `providedIn: 'root'` pattern is preferred over registering in `providers` arrays unless you specifically need a scoped, non-singleton instance.

### Q6. [Theory] What are lifecycle hooks and what is the order of the common ones?

Lifecycle hooks are methods Angular calls at defined moments in a component/directive's life so you can run setup, react to changes, and clean up. The common order on creation is:

```
constructor
  → ngOnChanges (if it has @Inputs; runs before ngOnInit and on each input change)
  → ngOnInit (once, after first ngOnChanges — do initialization here)
  → ngDoCheck
  → ngAfterContentInit / ngAfterContentChecked  (projected <ng-content>)
  → ngAfterViewInit / ngAfterViewChecked        (own + child views ready)
  ... (DoCheck / After*Checked repeat on every change-detection cycle) ...
  → ngOnDestroy (cleanup: unsubscribe, clear timers)
```

`ngOnInit` is where you do initialization (inputs are available, unlike in the constructor). `ngAfterViewInit` is the first point `@ViewChild` references are populated. `ngOnDestroy` is critical for preventing memory leaks. In modern Angular, `effect()` and `afterRenderEffect()` plus `DestroyRef` increasingly replace manual hook plumbing for reactive code.

### Q7. [Theory] What is the difference between `ngOnChanges` and `ngDoCheck`?

`ngOnChanges` fires only when a **reference-typed `@Input()` binding changes by reference** (or a primitive changes by value), and it receives a `SimpleChanges` map describing previous/current values. `ngDoCheck` fires on **every** change-detection run regardless of inputs, giving you a hook to implement custom dirty-checking — for example, detecting a mutation inside an array that didn't change its reference. The catch is that `ngDoCheck` runs extremely often, so any work there must be cheap or you will tank performance. With signals and `OnPush`, the need for hand-rolled `ngDoCheck` checking has largely disappeared.

---

## 🟡 Intermediate (3–7 yrs)

### Q8. [Theory] Standalone components vs NgModules — what changed and why?

Historically every component, directive, and pipe had to be declared in an `NgModule`, and modules also grouped providers and controlled what was compiled together. This created ceremony: a new component meant editing a module, and the "which module declares this?" problem confused newcomers. **Standalone components** (stable in v15, the default in v17, and effectively the only path going forward with `NgModule` deprecation underway) let a component declare its own dependencies via an `imports` array, eliminating feature modules for most apps.

```typescript
@Component({
  selector: 'app-orders',
  imports: [CommonModule, RouterLink, OrderRowComponent], // direct dependencies
  template: `...`,
})
export class OrdersComponent {}
```

You bootstrap with `bootstrapApplication(AppComponent, { providers: [...] })` instead of an `AppModule`, and configure features with provider functions like `provideRouter()`, `provideHttpClient()`, and `provideAnimationsAsync()`. The trade-offs: standalone reduces boilerplate, improves lazy-loading granularity (you can lazy-load a single component), and clarifies dependencies; the cost is that very large shared `imports` lists can become repetitive, often mitigated by grouping into an exported array of common imports. As of v19 `standalone: true` is implied and the flag is omitted.

### Q9. [Theory] Explain Angular's hierarchical dependency injection and resolution order.

Angular has two parallel injector hierarchies: the **module/environment injector** tree (rooted at the root environment injector, extended by `provideX()` and lazy-loaded route providers) and the **element/node injector** tree (one per DOM element that has a component or directive, mirroring the component tree). When you request a dependency, Angular walks **up the element-injector tree first**, then crosses into the environment-injector tree, until it finds a provider or reaches the `NullInjector`, which throws `NullInjectorError`.

```
ElementInjector (this component)
   ↑  (not found)
ElementInjector (parent component)
   ↑
... up to root ElementInjector
   ↑  (cross over)
Route/Lazy EnvironmentInjector
   ↑
Root EnvironmentInjector ── providedIn: 'root'
   ↑
NullInjector  → throws if still not found
```

You influence resolution with `@Self()` (only this node), `@SkipSelf()` (start at parent), `@Optional()` (return `null` instead of throwing), and `@Host()` (stop at the host element). This hierarchy is what lets you provide a different instance of a service per route or per component subtree — e.g., a `FormState` service scoped to one wizard.

### Q10. [Theory] What is change detection, and how does Zone.js differ from zoneless?

Change detection is the process of synchronizing component state with the DOM. Classically Angular relied on **Zone.js**, which monkey-patches async APIs (`setTimeout`, `addEventListener`, `Promise`, XHR) so that whenever any async task completes, Angular knows "something might have changed" and runs change detection from the root down, checking every binding. This is convenient (you mutate a field and the view updates) but wasteful: it re-checks the whole tree even when nothing relevant changed, and Zone.js adds ~70–100KB and patches global APIs.

**Zoneless** (developer preview in v18, increasingly production-ready through v19–v21 via `provideZonelessChangeDetection()`) removes Zone.js entirely. Instead, Angular schedules change detection only when it's explicitly told something changed: a signal read in a template updates, an `async` pipe emits, an event fires, or you call `ChangeDetectorRef.markForCheck()`. This is faster, smaller, improves interop with non-Angular libraries, and produces cleaner stack traces. The migration requirement is that your code must signal changes properly (use signals, `async` pipe, or `markForCheck`) rather than relying on Zone.js to "notice" mutations.

```
Zone.js:    any async task ──► CD runs on WHOLE tree (then OnPush prunes)
Zoneless:   signal change / event / async pipe ──► CD scheduled, runs only dirty paths
```

### Q11. [Theory] How does the `OnPush` change detection strategy work?

With the default strategy Angular checks every component on every CD cycle. With `ChangeDetectionStrategy.OnPush`, a component is checked only when one of these happens: (1) an `@Input()` reference changes, (2) an event originates from the component or a child, (3) an `async` pipe in its template emits, or (4) it's explicitly marked dirty via `markForCheck()`, or (5) a signal read in its template changes. This skips entire subtrees, dramatically reducing work in large apps. The discipline `OnPush` enforces is **immutability**: you must replace objects/arrays rather than mutate them, because a mutation that keeps the same reference won't trigger detection. Signals integrate naturally with `OnPush` because reading a signal in a template registers a dependency that marks the component dirty when the signal changes — which is why signal-based components are effectively "`OnPush` done right."

### Q12. [Theory] What are signals, and how have they matured from v16 to v21?

Signals are Angular's fine-grained reactivity primitive: a wrapper around a value that tracks who reads it and notifies dependents when it changes. The core API is `signal()` (writable), `computed()` (derived, memoized, lazy), and `effect()` (runs side effects when dependencies change).

```typescript
const count = signal(0);
const double = computed(() => count() * 2);   // recomputes only when count changes
effect(() => console.log('count is', count())); // re-runs on change
count.set(5);
count.update(c => c + 1);
```

Maturation timeline:
- **v16**: signals introduced (developer preview) — `signal`, `computed`, `effect`.
- **v17**: signals stable; new control flow and `@defer` built to leverage them; signals integrated with change detection so reading a signal in a template marks the component for check.
- **v17.1–v17.2**: signal-based `input()` and the `signal()`-backed `model()` for two-way binding.
- **v17.3 / v18**: `output()` function, signal-based queries (`viewChild()`, `contentChild()`), `toSignal`/`toObservable` interop in `@angular/core/rxjs-interop`.
- **v19**: `linkedSignal()` (a writable signal that resets when a source changes) and `resource()`/`rxResource()` for async data as signals.
- **v20–v21**: signals are the recommended default for component state; `resource` APIs stabilize, signal forms experiments mature, and zoneless leans heavily on signals as the primary change-detection trigger.

The payoff is **fine-grained, glitch-free, pull-based reactivity** that, combined with zoneless, lets Angular update only the exact DOM bindings that depend on a changed signal.

### Q13. [Coding] Compare a counter implemented with RxJS BehaviorSubject vs signals.

**Problem:** Implement shared counter state both ways and discuss when each fits.

```typescript
// --- RxJS approach ---
@Injectable({ providedIn: 'root' })
export class RxCounter {
  private _count = new BehaviorSubject(0);
  count$ = this._count.asObservable();
  inc() { this._count.next(this._count.value + 1); }
}
// Template: {{ count$ | async }}  — async pipe handles subscribe/unsubscribe

// --- Signals approach ---
@Injectable({ providedIn: 'root' })
export class SignalCounter {
  private _count = signal(0);
  count = this._count.asReadonly();
  doubled = computed(() => this._count() * 2);
  inc() { this._count.update(c => c + 1); }
}
// Template: {{ count() }}  — no subscription, no pipe
```

**When to use which:** Signals win for **synchronous UI state** (form values, toggles, derived view data) — simpler, no subscription leaks, no `async` pipe, integrate with zoneless CD. RxJS wins for **asynchronous event streams and time-based composition**: debounced search, websockets, retry/backoff, combining multiple HTTP calls, cancellation. The idiomatic v18+ pattern bridges them with `toSignal(stream$)` and `toObservable(sig)`. **Complexity:** both are O(1) per update; signals avoid the hidden subscription-management cost.

### Q14. [Coding] Implement a debounced type-ahead search with RxJS.

**Problem:** As the user types, query an API after they pause, cancel stale requests, and avoid duplicate consecutive queries.

```typescript
@Component({
  selector: 'app-search',
  imports: [ReactiveFormsModule, AsyncPipe],
  template: `
    <input [formControl]="query" placeholder="Search..." />
    @for (r of results$ | async; track r.id) { <div>{{ r.name }}</div> }
  `,
})
export class SearchComponent {
  private api = inject(SearchService);
  query = new FormControl('', { nonNullable: true });

  results$ = this.query.valueChanges.pipe(
    debounceTime(300),                 // wait for a 300ms pause
    distinctUntilChanged(),            // skip if value unchanged
    filter(q => q.length >= 2),        // ignore short queries
    switchMap(q => this.api.search(q).pipe(
      catchError(() => of([]))         // keep the stream alive on error
    )),                                // switchMap cancels the previous in-flight call
  );
}
```

**Why each operator matters:** `debounceTime` reduces request volume; `distinctUntilChanged` prevents redundant calls; `switchMap` is the key — it **unsubscribes the previous inner observable**, so a slow earlier response can't overwrite a newer one (a real race-condition bug if you used `mergeMap`). `catchError` returning `of([])` prevents one failed request from terminating the entire stream. **Edge cases:** empty input, network errors, and out-of-order responses are all handled. **Time:** O(1) per keystroke for stream setup; network dominates.

### Q15. [Theory] Reactive forms vs template-driven forms — trade-offs?

**Template-driven forms** (`FormsModule`, `[(ngModel)]`) put the model in the template; Angular creates the form controls implicitly. They're quick for simple forms but harder to unit test, validate dynamically, and reason about because logic lives in HTML. **Reactive forms** (`ReactiveFormsModule`, `FormControl`/`FormGroup`/`FormArray`) define the model explicitly in the component as an immutable, observable tree.

| Aspect | Template-driven | Reactive |
|---|---|---|
| Source of truth | Template | Component class |
| Validation | Directives in HTML | Validator functions, composable |
| Dynamic controls | Awkward | `FormArray`, easy |
| Testability | Requires DOM | Pure, sync, easy |
| Async/observable | Manual | `valueChanges`, `statusChanges` |

For anything beyond a trivial form I use reactive forms in production: explicit typing (typed forms became default in v14), straightforward dynamic add/remove of fields, and `valueChanges` streams that compose with RxJS. Angular is also developing **signal-based forms** (experimental in v20–v21) that recast form state as signals, which may become the third option going forward.

### Q16. [Coding] Build a reactive form with custom and cross-field async validation.

**Problem:** Sign-up form where password and confirm must match (cross-field) and username availability is checked async.

```typescript
export class SignupComponent {
  private fb = inject(NonNullableFormBuilder);
  private api = inject(UserService);

  form = this.fb.group({
    username: ['', { validators: [Validators.required], asyncValidators: [this.usernameTaken()] }],
    password: ['', [Validators.required, Validators.minLength(8)]],
    confirm: ['', [Validators.required]],
  }, { validators: [this.matchPasswords] });

  // Cross-field (group-level) validator
  private matchPasswords(group: AbstractControl): ValidationErrors | null {
    const pw = group.get('password')?.value;
    const cf = group.get('confirm')?.value;
    return pw === cf ? null : { mismatch: true };
  }

  // Async validator (returns Observable<ValidationErrors|null>)
  private usernameTaken(): AsyncValidatorFn {
    return (ctrl) => ctrl.valueChanges.pipe(
      debounceTime(300),
      switchMap(v => this.api.isTaken(v)),
      map(taken => (taken ? { taken: true } : null)),
      first(), // async validators must complete
    );
  }

  submit() {
    if (this.form.invalid) return;
    const value = this.form.getRawValue(); // fully typed, no nulls
    // ...send value
  }
}
```

**Edge cases:** async validator must **complete** (`first()`), otherwise the control stays `PENDING` forever; group-level validators set errors on the group, so the template reads `form.errors?.['mismatch']`; `getRawValue()` includes disabled controls. **Complexity:** O(1) per validation aside from the network call.

### Q17. [Theory] Explain the new built-in control flow (`@if`, `@for`, `@switch`) vs structural directives.

Angular 17 introduced **block-based control flow** as a built-in template feature, replacing `*ngIf`, `*ngFor`, and `*ngSwitch` for most uses. The new syntax is part of the compiler (not a directive), so it needs no imports, is more ergonomic, and is significantly faster — the team reported up to ~90% faster runtime for the new `@for` and better tree-shaking.

```html
@if (user(); as u) {
  <p>Hello {{ u.name }}</p>
} @else if (loading()) {
  <spinner />
} @else {
  <p>No user</p>
}

@for (item of items(); track item.id) {       <!-- track is MANDATORY -->
  <li>{{ item.name }}</li>
} @empty {
  <li>Nothing here</li>
}

@switch (status()) {
  @case ('active') { <active-badge /> }
  @default { <inactive-badge /> }
}
```

Key differences: `track` is **required** in `@for` (it was optional `trackBy` before) which prevents a common performance footgun; there's a built-in `@empty` block; and `@if` supports `as` aliasing without the `*ngIf="x as y"` trick. The old `*ng*` directives still work and aren't removed, but `@`-syntax is the recommended default and there's an automated migration (`ng generate @angular/core:control-flow`).

### Q18. [Theory] What is `@defer` and what triggers does it support?

`@defer` (stable in v17) is declarative **lazy loading at the template level**. It splits its contents (and the dependencies used only inside it) into a separate chunk that loads on a trigger, improving initial bundle size and Core Web Vitals (LCP, TBT) without manual route splitting.

```html
@defer (on viewport; prefetch on idle) {
  <heavy-chart [data]="data()" />
} @placeholder (minimum 500ms) {
  <chart-skeleton />
} @loading (after 100ms; minimum 1s) {
  <spinner />
} @error {
  <p>Failed to load chart.</p>
}
```

Triggers: `on idle` (default), `on viewport` (IntersectionObserver), `on interaction`, `on hover`, `on timer(5s)`, `on immediate`, or `when <condition>`. You can `prefetch` independently of rendering (e.g., prefetch on idle but render on viewport). The four blocks — `@placeholder`, `@loading`, `@error` — give a complete UX story. Crucially, dependencies used **only** inside the `@defer` block are excluded from the main bundle, so `@defer` is one of the highest-leverage performance tools in modern Angular and works hand-in-hand with SSR/hydration.

### Q19. [Practical] How do you implement lazy-loaded routes and route guards in a standalone app?

Lazy loading defers loading a route's code until navigation, shrinking the initial bundle. With standalone you `loadComponent` for a single component or `loadChildren` for a set of routes.

```typescript
export const routes: Routes = [
  { path: '', component: HomeComponent },
  {
    path: 'admin',
    canActivate: [authGuard],
    loadComponent: () => import('./admin/admin.component').then(m => m.AdminComponent),
    providers: [AdminScopedService], // scoped EnvironmentInjector for this route
  },
  {
    path: 'reports',
    loadChildren: () => import('./reports/routes').then(m => m.REPORTS_ROUTES),
  },
];

// Functional guard (the modern style; class-based guards are deprecated)
export const authGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.isLoggedIn() ? true : router.createUrlTree(['/login'], {
    queryParams: { returnUrl: state.url },
  });
};
```

**Production notes:** prefer **functional guards** (`CanActivateFn`, `CanMatchFn`) over class guards — they're injectable via `inject()` and tree-shakable. Use `CanMatch` instead of `CanActivate` when you want the lazy chunk to not even download for unauthorized users, or to swap routes by condition. Pair lazy routes with `withPreloading(PreloadAllModules)` or a custom selective-preload strategy to prefetch likely-next routes after initial render. Return `UrlTree` from guards to redirect declaratively rather than calling `router.navigate()` as a side effect.

### Q20. [Practical] How do you prevent memory leaks from subscriptions?

Unmanaged subscriptions are the classic Angular leak: a component is destroyed but its subscription keeps the component alive and keeps running. Strategies, best to worst:

1. **`async` pipe** — let the template subscribe and auto-unsubscribe. Always prefer this for template-bound streams.
2. **`takeUntilDestroyed()`** (v16+) — `obs$.pipe(takeUntilDestroyed())` in an injection context, or pass a `DestroyRef`. Cleanest manual option.
3. **Signals / `toSignal()`** — convert the stream to a signal; cleanup is automatic.
4. **`takeUntil(this.destroy$)`** — the older pattern with a `Subject` completed in `ngOnDestroy`.

```typescript
export class WidgetComponent {
  private destroyRef = inject(DestroyRef);
  ngOnInit() {
    this.service.poll$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(v => this.handle(v));
  }
}
```

In production I standardize on `async` pipe + `takeUntilDestroyed` and lint against bare `.subscribe()` calls. Finite streams like a single `HttpClient.get()` complete on their own, but adding `takeUntilDestroyed` is still safer because cancellation also aborts the in-flight request.

### Q21. [Practical] A list of 10,000 rows scrolls sluggishly. How do you diagnose and fix it?

**Diagnose:** open Angular DevTools profiler to see which components re-render and how long CD takes; check whether the component is default CD (re-checking everything) and whether `@for` has a stable `track`. **Common causes:** no `track` (DOM nodes recreated on every change), heavy work in template expressions or getters (called every CD), default CD on a large tree, and rendering all 10k nodes at once.

**Fixes, layered:**
- Add a stable `track item.id` to `@for` so Angular reuses DOM nodes on updates.
- Switch the component (and ancestors) to `OnPush`, or adopt signals so only dependent bindings update.
- **Virtual scrolling** with `cdk-virtual-scroll-viewport` so only visible rows are in the DOM — the single biggest win for huge lists.
- Move expensive computations out of templates into `computed()` signals or memoized pure pipes; never call methods that do work directly in the template.
- Use `@defer (on viewport)` for off-screen heavy widgets.

```html
<cdk-virtual-scroll-viewport itemSize="48" class="h-96">
  @for (row of rows(); track row.id) {
    <div class="row" [style.height.px]="48">{{ row.name }}</div>
  }
</cdk-virtual-scroll-viewport>
```

In production, virtual scroll + `OnPush`/signals + stable `track` typically takes a janky 10k-row list to smooth 60fps.

---

## 🟠 Advanced (8–12 yrs)

### Q22. [Theory] Explain SSR and hydration in modern Angular, including non-destructive and incremental hydration.

Server-Side Rendering (SSR) renders the initial HTML on the server (`@angular/ssr`, configured via `provideClientHydration()` and `ng add @angular/ssr`) so users see content fast and crawlers get real HTML — improving FCP/LCP and SEO. The challenge is the client takeover. The old SSR **destroyed** the server DOM and re-rendered from scratch, causing a visible flicker and discarding server work.

**Non-destructive hydration** (stable in v17) instead **reuses** the server-rendered DOM: Angular walks the existing nodes, attaches event listeners and bindings, and only patches mismatches — no flicker, no re-render. **Incremental hydration** (developer preview in v19, maturing through v20–v21) goes further: it pairs with `@defer` so a component's JavaScript hydrates only when triggered (`on viewport`, `on interaction`, etc.), keeping initial JS minimal while the server HTML stays interactive-on-demand.

```typescript
bootstrapApplication(AppComponent, {
  providers: [provideClientHydration(withIncrementalHydration())],
});
```

```html
@defer (hydrate on viewport) {
  <comments-panel />   <!-- server-rendered, JS hydrates only when scrolled into view -->
}
```

**Gotchas:** avoid direct DOM manipulation that diverges from the server output (causes hydration mismatch warnings/errors); guard browser-only APIs (`window`, `localStorage`) with `isPlatformBrowser()` or `afterNextRender()`; ensure data fetched on the server is transferred to the client (via `TransferState`/`HttpClient` transfer cache with `withHttpTransferCacheOptions`) so it isn't re-fetched and cause mismatches.

### Q23. [Theory] What are injection tokens, multi-providers, and when do you use `useFactory`/`useExisting`?

When the thing you inject isn't a class (a config object, a string, a function, an interface), you can't use the type as a DI key, so you create an `InjectionToken<T>`. Providers map a token to how Angular creates the value: `useClass`, `useValue`, `useExisting` (alias an existing token — same instance), and `useFactory` (compute the value, optionally with `deps`).

```typescript
export const API_URL = new InjectionToken<string>('API_URL');
export const FEATURE = new InjectionToken<Feature[]>('FEATURE');

providers: [
  { provide: API_URL, useValue: 'https://api.example.com' },
  // multi: true builds an ARRAY by aggregating all providers for the token
  { provide: FEATURE, useValue: featureA, multi: true },
  { provide: FEATURE, useValue: featureB, multi: true },
  { provide: Logger, useFactory: (cfg: Config) => cfg.prod ? new RemoteLogger() : new ConsoleLogger(), deps: [Config] },
  { provide: AbstractAuth, useExisting: ConcreteAuth }, // both resolve to the same instance
];
```

**Multi-providers** are the extension mechanism behind `HTTP_INTERCEPTORS`, `NG_VALIDATORS`, and router features — many parties contribute to one array. `useExisting` vs `useClass` matters: `useClass` creates a **second** instance, `useExisting` aliases the **same** one. `useFactory` is for runtime decisions (env-based, feature flags). These are bread-and-butter for building extensible libraries.

### Q24. [Coding] Write an HTTP interceptor (functional) that adds auth, retries, and handles 401 refresh.

**Problem:** Attach a bearer token, retry transient failures, and transparently refresh the token on 401 then replay the request.

```typescript
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.accessToken();

  const authed = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authed).pipe(
    retry({ count: 2, delay: (err, n) => timer(Math.pow(2, n) * 300) }), // exp backoff
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && token) {
        return auth.refresh().pipe(           // refresh() returns Observable<string>
          switchMap(newToken =>
            next(req.clone({ setHeaders: { Authorization: `Bearer ${newToken}` } }))
          ),
          catchError(e => { auth.logout(); return throwError(() => e); }),
        );
      }
      return throwError(() => err);
    }),
  );
};

// Registration (functional interceptors, the modern way):
provideHttpClient(withInterceptors([authInterceptor]));
```

**Edge cases & security:** avoid a **refresh stampede** — if many requests 401 simultaneously, share a single in-flight refresh (e.g., a cached `refresh$` with `shareReplay(1)`) instead of N refresh calls. Don't retry non-idempotent POSTs blindly. Don't store tokens in `localStorage` if you can use httpOnly cookies (XSS exfiltration risk); if you must, accept the trade-off and tighten CSP. **Complexity:** O(1) per request plus retries.

### Q25. [Coding] Implement a typed, cancelable async data loader using the signals `resource` API (v19+).

**Problem:** Load a user by a reactive id, auto-reload when the id changes, expose loading/error/value as signals, and cancel stale requests.

```typescript
export class ProfileComponent {
  private http = inject(HttpClient);
  userId = input.required<string>();

  // rxResource bridges an Observable loader into signal-based resource state.
  userRes = rxResource({
    request: () => ({ id: this.userId() }),          // re-runs loader when this changes
    loader: ({ request, abortSignal }) =>            // abortSignal cancels stale calls
      this.http.get<User>(`/api/users/${request.id}`),
  });

  // Derived view state, all signals:
  user = computed(() => this.userRes.value());
  isLoading = computed(() => this.userRes.isLoading());
  error = computed(() => this.userRes.error());
}
```

```html
@if (isLoading()) { <spinner /> }
@else if (error()) { <p>Failed: {{ error() }}</p> }
@else if (user(); as u) { <h1>{{ u.name }}</h1> }
```

**Why this is the modern pattern:** `resource`/`rxResource` collapses the classic loading/error/data boilerplate into reactive signals, auto-cancels superseded requests, and integrates with zoneless CD. **Edge cases:** the `request` function returning `undefined` skips loading; `reload()` forces a refetch; errors are captured as a signal rather than throwing. Before v19 you'd hand-roll this with `BehaviorSubject` + `switchMap` + three subjects. **Complexity:** O(1) state transitions; network dominates.

### Q26. [Theory] How do `@ViewChild`/`@ContentChild` differ, and what changed with signal queries?

`@ViewChild`/`@ViewChildren` query elements/components/directives in the component's **own template (its view)**, available after `ngAfterViewInit`. `@ContentChild`/`@ContentChildren` query nodes **projected into the component via `<ng-content>`** (the consumer's markup), available after `ngAfterContentInit`. The mental model: "view" = what the component declares; "content" = what's handed to it from outside.

Signal-based queries (v17.2+) replace the decorators with functions that return signals, eliminating lifecycle timing pitfalls:

```typescript
// decorator (older)
@ViewChild('chart') chart!: ChartComponent;

// signal query (modern) — reactive, typed, no undefined-before-init surprises
chart = viewChild<ChartComponent>('chart');         // Signal<ChartComponent | undefined>
chartReq = viewChild.required<ChartComponent>('chart');
rows = viewChildren(RowComponent);                  // Signal<readonly RowComponent[]>
```

Signal queries are read like any signal (`this.chart()`), update reactively, and compose in `computed`/`effect`, so you no longer guess whether the reference is populated yet — you react when it becomes available.

### Q27. [Practical] Your bundle is 2.5MB and TTI is poor. Walk through an optimization plan.

**Measure first:** `ng build --stats-json` + `source-map-explorer` or `webpack-bundle-analyzer` to see what's actually big; Lighthouse/WebPageTest for TTI, LCP, TBT.

**Then attack in order of leverage:**
1. **Route-level lazy loading** — ensure feature areas are `loadComponent`/`loadChildren`, not eagerly imported.
2. **`@defer` heavy, below-the-fold widgets** (charts, editors, maps) with `on viewport`/`on interaction` so their deps leave the main chunk.
3. **Audit dependencies** — replace a 300KB date/charting lib with a lighter one or native APIs (`Intl`); ensure libraries are tree-shakable (ESM, side-effect-free).
4. **SSR + hydration** to improve perceived load and LCP; incremental hydration to cut hydration JS.
5. **`OnPush`/signals + zoneless** to drop Zone.js (~70–100KB) and reduce runtime CD cost.
6. **Optimize assets** — images (`NgOptimizedImage`, which enforces lazy loading, priority hints, and srcset), fonts, and enable build-time optimizations (already default with esbuild/Vite in v17+).
7. **Preload strategy** for likely-next routes so navigation feels instant without bloating first load.

The biggest single wins in modern Angular are usually `@defer` + dropping a heavy dependency + dropping Zone.js. I'd set a CI **bundle budget** (`budgets` in `angular.json`) so regressions fail the build.

### Q28. [Theory] Explain `ChangeDetectorRef` methods and when `detach()`/`reattach()` is appropriate.

`ChangeDetectorRef` gives manual control over a component's CD. `markForCheck()` marks the component and its ancestors dirty so the next CD cycle checks them (the right tool with `OnPush`). `detectChanges()` runs CD on this component and its children **immediately and synchronously**. `detach()` removes the component from the CD tree entirely so it's never auto-checked; `reattach()` restores it. `checkNoChanges()` (dev) throws if a binding changed during CD, catching the `ExpressionChangedAfterItHasBeenCheckedError`.

`detach()`/`reattach()` is appropriate for extreme cases: a component bound to a very high-frequency stream (e.g., 60Hz sensor data or a live chart) where you want to throttle rendering — detach it, then on a controlled cadence call `detectChanges()` (or briefly `reattach()`). This is a sharp tool: a detached component won't update at all unless you drive it, so it's easy to introduce stale-view bugs. With signals + zoneless, the need for manual detach largely evaporates because only dependent bindings recompute.

---

## 🔴 Expert (15+ yrs)

### Q29. [Theory] Design a state-management strategy for a large Angular app in 2026. When do you reach for NgRx vs signal-based stores?

There's no single right answer; the strategy should match coupling and complexity. My layered default:

```
Local component state            → signals (signal/computed) — most state lives here
Shared feature state             → a signal-based service store (signals in @Injectable)
Cross-cutting / complex domain   → NgRx (or NgRx SignalStore) when you need:
   - time-travel debugging / strict auditability
   - many features mutating shared state with traceable actions
   - large team needing enforced unidirectional discipline
Server cache state               → resource/rxResource or a query lib pattern
```

In 2026 I'd avoid reaching for full NgRx Store/Effects by default — for most apps, **signal-based service stores** (a class exposing `readonly` signals + `computed` selectors + methods that `set`/`update`) cover the need with a fraction of the boilerplate, and **NgRx SignalStore** offers a structured middle ground (signals + a familiar store shape + entity management) without the action/reducer ceremony. I reserve classic NgRx (actions/reducers/effects) for genuinely complex, audited domains — finance, large multi-team apps — where the explicit event log and devtools time-travel earn their cost. The key principle: **don't globalize state that's actually local.** Over-centralizing state into a global store is the most common architectural mistake I see, creating coupling and re-render pressure that signals were designed to avoid.

### Q30. [Theory] You're migrating a 500k-LOC AngularJS/Angular hybrid (or NgModule-heavy v14 app) to standalone + signals + zoneless. How do you sequence it safely?

Big-bang rewrites of large apps almost always fail; I'd run an **incremental, ship-while-you-migrate** strategy:

1. **Upgrade the framework version first**, one major at a time, using `ng update` and the automated migrations — never skip majors. Get to a recent version (v17+) before changing architecture.
2. **Standalone migration** with the official schematic (`ng generate @angular/core:standalone`) run in its three phases (convert declarations → remove unnecessary modules → bootstrap). NgModules and standalone interoperate, so this can be done feature-by-feature.
3. **Control-flow migration** (`@angular/core:control-flow`) and `inject()` migration — purely mechanical, automated, low risk.
4. **Signals adoption** opportunistically: new code uses signals; convert hot components to signal inputs/queries; bridge RxJS with `toSignal`. This is gradual and doesn't gate other work.
5. **Zoneless last** — it's the riskiest because it exposes any code relying on Zone.js to "notice" changes. Enable it behind a flag, run the full E2E suite, and fix `ExpressionChanged`/missing-CD issues by ensuring every state change goes through signals, `async` pipe, or `markForCheck()`.

Throughout: maintain a strong **E2E + visual-regression** safety net, enforce **bundle and a11y budgets** in CI, and use **feature flags** so partially migrated features ship. I'd track migration as a measurable backlog (percent standalone, percent OnPush/signal) rather than a frozen branch. This mirrors how large orgs (e.g., Google's own monorepo) migrate — continuously, behind tooling, never with a "big rewrite."

### Q31. [Theory] What are the security responsibilities of an Angular app, and what does the framework give you vs what you must own?

Angular provides strong **XSS protection by default**: it treats all interpolated values as untrusted and **contextually sanitizes** HTML, style, and URL bindings, escaping or stripping dangerous content. `[innerHTML]` is sanitized; only `bypassSecurityTrust*` (which you should treat as a code smell requiring review) disables it. Angular also supports **Trusted Types** to harden against DOM XSS sinks. What you still own:

- **Server-side validation/authz** — client guards are UX, not security; never trust the client.
- **CSRF/XSRF** — Angular's `HttpClient` reads an XSRF cookie and sends a header (`withXsrfConfiguration`), but the server must set and verify the token.
- **Content Security Policy** — you must define a CSP; v16+ supports CSP-friendly inline-style nonces.
- **Auth token handling** — `localStorage` tokens are XSS-exfiltratable; prefer httpOnly, SameSite cookies.
- **`bypassSecurityTrust*` usage** — every call is a potential XSS hole; audit and minimize.
- **Dependency supply chain** — `npm audit`, lockfile integrity, vetting third-party components that render HTML.
- **SSR data leakage** — ensure server-only secrets/user data aren't serialized into the transferred state.

The mental model: Angular makes the **safe path the default** for template rendering, but architecture-level security (authz, CSP, transport, secrets) is entirely your responsibility.

### Q32. [Practical] A production app intermittently throws `ExpressionChangedAfterItHasBeenCheckedError` only in dev. Diagnose and resolve.

This dev-only error means a value bound in the template **changed during** the change-detection pass, between the check and Angular's verification pass (`checkNoChanges`, which runs only in dev). It signals that CD isn't stable in a single pass — a value mutated as a side effect of rendering. Common causes: a parent reads a child value that the child mutates in `ngAfterViewInit`; a getter in the template returns a new object/array each call; an `effect()` or subscription synchronously updates state that's already been rendered this tick.

**Diagnosis:** read the error's before/after values to identify the binding, then find what mutates it during the same tick. **Resolutions, by case:**
- If a child updates a parent-bound value in `ngAfterViewInit`, defer it: `afterNextRender()` (v16+) or schedule on a microtask/`queueMicrotask`, so it lands in the next CD cycle.
- If a template getter returns a fresh reference each call, memoize it (move to a `computed()` signal or precompute a field) so the reference is stable.
- If an `effect` writes a signal read in the template synchronously, restructure to `computed()` (derived, not a write) — effects should not feed values back into the render synchronously.

The proper fix is making CD **idempotent within a tick**, not suppressing the check. Signals largely prevent this class of bug because `computed` values are pure and pull-based; an `effect` that writes state read in the same view is the remaining trap to design out.

### Q33. [Behavioral] Tell me about a time you made a costly architectural decision in an Angular project. What did you learn?

(Structure with STAR.) **Situation/Task:** On a large dashboard product we standardized early on global NgRx for *all* state, including trivial local UI toggles. **Action:** As the app grew, every minor interaction required an action, reducer, selector, and effect — onboarding slowed, PRs ballooned, and a single global store caused unnecessary re-renders. I led a review that introduced a **state classification rubric** (local → component signals; shared feature → signal service store; complex audited domain → NgRx) and we incrementally pulled local UI state out of the global store. **Result:** PR size for UI work dropped sharply, change-detection pressure eased, and new engineers ramped faster. **Lesson:** match the tool to the *coupling* of the state, not to a blanket "best practice"; over-centralization is as harmful as no structure. Good staff-level judgment is recognizing when a once-correct decision (NgRx was reasonable in 2019) should evolve as the framework (signals) and the app change — and being willing to lead a measured, reversible migration rather than defending the original call.

### Q34. [Theory] Compare Angular's signals to React's model and Solid/Vue reactivity. What design decisions did the Angular team make and why?

Angular signals are **pull-based, glitch-free, fine-grained** reactivity, conceptually close to **SolidJS** and Vue's `ref`/`computed`: reading a signal in a reactive context auto-tracks the dependency; `computed` is lazy and memoized; updates propagate only to actual dependents. This contrasts with **React**, which is push/re-render based: a `setState` re-runs the component function and reconciles a virtual DOM, with `useMemo`/`useCallback` as opt-in memoization the developer must manage. Angular deliberately chose signals (over adopting a VDOM) because it already compiles templates AOT and binds directly to the DOM — fine-grained signals let it update **only the specific bindings** that depend on a changed signal, with no VDOM diffing, which pairs perfectly with **zoneless** CD. Design choices worth noting: Angular signals are **synchronous and write-batched within a tick**, `effect`s run in Angular's scheduling (not synchronously on every write) to avoid feedback storms, and `computed` is **equality-aware** (custom `equal` fn) to stop propagation when the value is unchanged. The strategic bet is that signals + zoneless + the new control flow let Angular shed Zone.js and approach Solid-class runtime performance while keeping its enterprise structure and DI — a different point in the design space than React's "just re-render and reconcile."

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q35. [Theory] What is the difference between an attribute and a property, and why does Angular bind to properties?

This trips up almost everyone moving from plain HTML to Angular. **Attributes** are defined in the HTML markup and exist in the static document; the browser reads them once at parse time to **initialize** the corresponding DOM property. **Properties** live on the live DOM object in the JavaScript heap and represent the element's *current* state. After initialization, the two can diverge: typing into an `<input value="hi">` updates the `value` *property* to the new text, but the `value` *attribute* still reads `"hi"`. Angular's `[x]="expr"` binding sets the **property**, not the attribute, because the property is the source of truth for runtime state.

This is why `[disabled]="false"` works correctly (it sets the boolean `disabled` property to `false`), whereas the raw attribute `disabled="false"` would still disable the element — in HTML, the mere *presence* of a boolean attribute means true, and `"false"` is just a truthy string. Some things genuinely have no DOM property (ARIA attributes, SVG attributes, `colspan`), so Angular provides `[attr.aria-label]="x"` to bind the attribute directly.

```html
<input [value]="name" />            <!-- sets the .value PROPERTY -->
<td [attr.colspan]="span"></td>     <!-- no .colspan property exists; bind the ATTRIBUTE -->
<button [disabled]="!canSave">Save</button>  <!-- boolean property: false removes the state -->
```

The practical rule: bind to a property by default (`[prop]`); fall back to `[attr.x]` only when the element exposes no matching property. Understanding this distinction also explains why server-rendered HTML (attributes) and the hydrated client state (properties) must be kept consistent to avoid mismatches.

#### Q36. [Theory] Why does Angular use TypeScript decorators and metadata, and what actually happens to `@Component` at build time?

Decorators like `@Component`, `@Injectable`, and `@Input` are not runtime magic that executes on every render — they are **compile-time metadata carriers**. Angular's compiler (`ngc`/the Ivy compiler) reads the decorator's configuration and **generates static instructions**: a component decorator is lowered into a `ɵcmp` static field on the class containing a compiled template function, the selector, the change-detection strategy, and the dependency list. The decorator's job is to *describe* the class so the AOT compiler can transform the template string into imperative DOM-creation code ahead of time.

This is fundamentally different from a runtime-interpreted approach. Because the template is compiled to JavaScript at build time (AOT), the framework ships **no template parser/interpreter** to the browser, templates are type-checked against the component class, and errors surface at build rather than at runtime. The metadata also feeds tree-shaking: unused providers and dead code can be eliminated because the relationships are statically known.

```typescript
// What you write:
@Component({ selector: 'app-x', template: '<p>{{ name }}</p>' })
export class XComponent { name = 'hi'; }

// Conceptually what the compiler emits (simplified):
class XComponent {
  name = 'hi';
  static ɵcmp = defineComponent({
    selectors: [['app-x']],
    template: (rf, ctx) => {           // compiled render function
      if (rf & 1) { elementStart(0, 'p'); text(1); elementEnd(); }
      if (rf & 2) { textInterpolate(ctx.name); }
    },
  });
}
```

The takeaway for interviews: Angular's decorators are a *static configuration* mechanism consumed by the compiler, which is why Angular needs the `@angular/compiler-cli`, why `reflect-metadata` was historically needed for DI types, and why the template is fast — it is precompiled DOM code, not interpreted strings.

#### Q37. [Theory] What is the difference between `constructor` and `ngOnInit`, and why shouldn't you do real work in the constructor?

The `constructor` is a plain TypeScript/JavaScript feature that runs when the class is **instantiated**, before Angular has done any of its framework-level wiring. At constructor time the component's `@Input()` bindings have **not** been set yet, host bindings aren't resolved, and child views don't exist. `ngOnInit` is an Angular **lifecycle hook** that the framework calls *after* it has run the first change-detection pass on the component, meaning inputs are populated and the component is "live."

The reason to keep the constructor lean is correctness and testability, not just style. If you call a service in the constructor that depends on an input value, you'll read `undefined`; if you kick off side effects there, you couple object construction to framework lifecycle, which makes the class harder to unit-test in isolation. The constructor's legitimate job is **dependency injection and field initialization** — accepting injected dependencies (or, more modern, calling `inject()`), nothing more.

```typescript
export class OrderComponent implements OnInit {
  @Input() orderId!: string;
  private api = inject(OrderService);     // OK: DI in field initializer (injection context)

  constructor() {
    // BAD: this.orderId is undefined here
    // this.api.load(this.orderId);
  }

  ngOnInit() {
    this.api.load(this.orderId);          // GOOD: inputs are available
  }
}
```

A subtlety worth mentioning: the `inject()` function only works inside an **injection context**, which includes constructors and field initializers but *not* `ngOnInit`. So you inject in the constructor/fields and *use* those dependencies in `ngOnInit`. With signal inputs (`input()`), you can also react to inputs via `computed`/`effect` instead of `ngOnInit`, which sidesteps the timing question entirely.

#### Q38. [Theory] What is the `async` pipe doing internally, and why is it preferable to manual subscription?

The `async` pipe is an **impure pipe** that wraps subscription management and change detection into a single declarative token. Internally it holds a reference to the current `Observable`/`Promise`, subscribes on first use, stores the latest emitted value, and — critically — calls `ChangeDetectorRef.markForCheck()` whenever a new value arrives so the view updates even under `OnPush`. When the component is destroyed, the pipe's `ngOnDestroy` unsubscribes automatically. If the bound observable *reference* changes, the pipe unsubscribes from the old one and subscribes to the new one.

This solves three problems at once that manual `.subscribe()` leaves to you: **unsubscription** (no leak), **change-detection notification** (the markForCheck that makes `OnPush`/zoneless work), and **reference-swap handling**. Manual subscription in a component requires you to store the value, manage teardown in `ngOnDestroy`, and — under `OnPush` — remember to call `markForCheck()` yourself.

```html
<!-- Declarative: subscribe, render, unsubscribe, markForCheck — all handled -->
@if (user$ | async; as user) {
  <h1>{{ user.name }}</h1>
}
```

The trade-off to know: because it's impure, the pipe's transform is invoked on **every** change-detection check (the framework can't memoize an impure pipe), but the *subscription* is created only once — re-running the transform with the same observable reference is cheap. A common pitfall is using `| async` multiple times on the same source, which creates multiple subscriptions; the fix is `@if (data$ | async; as data)` to subscribe once and alias the value, or `shareReplay` on the source.

### 🟡 Intermediate — extended

#### Q39. [Theory] Explain how Zone.js monkey-patches the browser and the difference between the Angular zone and the root zone.

Zone.js works by **replacing global asynchronous APIs with wrappers** at startup: it overrides `setTimeout`, `setInterval`, `Promise.then`, `addEventListener`, `XMLHttpRequest`, `requestAnimationFrame`, and dozens of others so that each async callback is associated with the zone that scheduled it. A "zone" is essentially an execution context that persists across async boundaries, with hooks like `onInvokeTask`, `onScheduleTask`, and `onHasTask`. Angular creates a dedicated zone called **NgZone** (the "Angular zone") and subscribes to its `onMicrotaskEmpty` event; whenever the microtask queue drains after async work, NgZone signals Angular to run change detection from the root.

The key operational distinction is between running **inside** vs **outside** the Angular zone. Code that runs inside NgZone triggers change detection when its async tasks complete; code run via `ngZone.runOutsideAngular(...)` executes in the parent/root zone, so its `setTimeout`/scroll/animation callbacks do **not** trigger CD. This is the standard escape hatch for high-frequency work (mouse-move handlers, `requestAnimationFrame` loops, third-party libraries) that would otherwise cause a storm of unnecessary CD cycles.

```typescript
export class CanvasComponent {
  private zone = inject(NgZone);

  startAnimation() {
    // Runs the 60fps loop OUTSIDE Angular so it doesn't trigger CD every frame
    this.zone.runOutsideAngular(() => {
      const loop = () => { this.draw(); requestAnimationFrame(loop); };
      requestAnimationFrame(loop);
    });
  }

  onResult(value: string) {
    // Re-enter the Angular zone when you DO want the view to update
    this.zone.run(() => { this.result = value; });
  }
}
```

Understanding this explains both the cost of Zone.js (it patches globals and runs CD broadly) and why zoneless is appealing: zoneless removes the patching entirely and replaces "notice all async, then check the whole tree" with "explicit notification via signals/events/markForCheck," eliminating the `runOutsideAngular` dance for most cases.

#### Q40. [Theory] What is `ng-template`, `ng-container`, and `ng-content`, and how do they differ semantically?

These three look superficially similar but serve distinct roles. **`<ng-template>`** defines a block of DOM that is **not rendered by default** — it's a blueprint Angular instantiates on demand. Structural directives desugar into it: `*ngIf="x"` becomes an `<ng-template [ngIf]="x">`. You can grab a `TemplateRef` from it and render it with `ngTemplateOutlet`, pass it as a parameter, or instantiate it programmatically via `ViewContainerRef.createEmbeddedView()`. It is the foundation of lazy/conditional rendering.

**`<ng-container>`** is a **logical grouping element that produces no DOM node**. It lets you apply a structural directive or group siblings without introducing a wrapper `<div>` that would break CSS grid/flex layouts or table semantics. It renders nothing itself — only its children appear.

**`<ng-content>`** is the **content projection** slot: it marks where children passed *into* a component from its parent should appear, analogous to Web Components' `<slot>`. It does not create or instantiate anything; it relocates already-created DOM from the consumer into the component's view, which is why projected content's lifecycle ties to `ngAfterContentInit`, not `ngAfterViewInit`.

```html
<!-- ng-container: group without a wrapper element -->
<ng-container *ngIf="loaded">
  <td>{{ a }}</td><td>{{ b }}</td>     <!-- valid table row; no stray <div> -->
</ng-container>

<!-- ng-template: defined but not rendered until referenced -->
<ng-template #empty><p>No data</p></ng-template>
<div *ngIf="rows.length; else empty">...</div>

<!-- ng-content: project consumer's markup into a card component -->
<!-- card.component.html -->
<div class="card"><ng-content select="[header]"></ng-content><ng-content></ng-content></div>
```

| Element | Renders DOM? | Purpose | Lifecycle anchor |
|---|---|---|---|
| `<ng-template>` | No (until instantiated) | Deferred/conditional template blueprint | created on demand |
| `<ng-container>` | No wrapper | Group/host a directive without extra element | n/a |
| `<ng-content>` | Projects existing DOM | Slot for parent-supplied content | `ngAfterContentInit` |

#### Q41. [Theory] How does `trackBy`/`track` actually work internally, and what goes wrong without it?

When a list re-renders, Angular's differ (`IterableDiffer`) needs to map each item in the new array to a DOM view it created previously. By default it tracks by **object identity** (reference). If your data layer returns *new object instances* each time (a common result of mapping an HTTP response or recreating an immutable array), every item is seen as "new," so Angular **destroys every existing DOM node and recreates the entire list** — discarding component state, losing input focus, restarting animations, and re-running expensive child initialization.

`track` (the `@for` block, mandatory since v17) and the older `trackBy` give the differ a **stable identity key** — typically a primary key like `item.id`. Now Angular matches new items to old views by that key: unchanged items keep their DOM and component instances, only the actually-changed/added/removed nodes are touched, and reordering moves existing nodes rather than rebuilding them. This is the difference between O(n) DOM churn on every update and surgical, minimal mutation.

```html
@for (user of users(); track user.id) {     <!-- stable key: reuse views across updates -->
  <user-card [user]="user" />
} @empty { <p>No users</p> }
```

The subtle footgun is choosing a **bad key**. Tracking by `$index` defeats the purpose when items can be reordered or inserted in the middle (index 2 now points to a different item, so Angular reuses the wrong view and you get visual glitches or wrong state). Tracking by the whole object (`track user`) is identical to the default reference behavior and helps nothing. The correct key is a value that is **stable and unique per logical item** across renders — almost always a domain id. Angular 17 made `track` mandatory specifically because forgetting it was the single most common list-performance bug.

#### Q42. [Theory] What is the difference between pure and impure pipes, and how does it affect change detection and performance?

A **pure pipe** (the default) is treated by Angular as a referentially-transparent function: its `transform` is re-invoked **only when its input reference (or a primitive input value) changes**. Angular memoizes the last result, so during change detection it compares the new args to the old ones and skips the computation if they're identical. This makes pure pipes cheap and safe to use liberally for formatting and derivation.

An **impure pipe** (`pure: false`) is re-invoked on **every change-detection cycle**, regardless of whether inputs changed, because Angular assumes its output may depend on external/mutable state it can't track. `AsyncPipe` and the legacy `JsonPipe`/`SlicePipe`-on-mutated-arrays are impure for this reason. The performance hazard is real: an impure pipe applied to a list, inside a component checked frequently, runs its body hundreds of times per second.

```typescript
@Pipe({ name: 'fullName' })                 // pure by default
export class FullNamePipe implements PipeTransform {
  transform(u: User) { return `${u.first} ${u.last}`; }  // re-runs only if `u` reference changes
}

@Pipe({ name: 'filter', pure: false })      // impure: runs every CD cycle
export class FilterPipe implements PipeTransform {
  transform(items: Item[], term: string) { return items.filter(i => i.name.includes(term)); }
}
```

The classic anti-pattern is an **impure `filter`/`sort` pipe over a large array** — it recomputes the entire filtered list on every CD tick. The trade-off discussion an interviewer wants: pure pipes require you to *replace* inputs immutably to trigger re-evaluation (which aligns with `OnPush`/signals), while impure pipes "just work" with mutation at a steep performance cost. The modern preference is to precompute derived collections in a `computed()` signal (recalculated only when dependencies change) rather than reach for an impure pipe.

#### Q43. [Theory] Explain the host element, host bindings, and the difference between `:host` and `:host-context`.

Every component instance is attached to a **host element** in the DOM — the element matching its selector. The component can bind to and listen on that element from the class using `@HostBinding` (set a property/attribute/class/style on the host) and `@HostListener` (subscribe to an event on the host), or via the `host` metadata object. This is how a component styles or reacts to its own outer element without needing a wrapper — for example, toggling a CSS class on itself based on state, or responding to clicks anywhere on the component.

```typescript
@Component({
  selector: 'app-toggle',
  host: { '[class.active]': 'isOn()', '(click)': 'flip()' },  // host metadata form
  template: `<ng-content />`,
})
export class ToggleComponent {
  isOn = signal(false);
  @HostBinding('attr.aria-pressed') get pressed() { return this.isOn(); }  // decorator form
  @HostListener('keydown.enter') onEnter() { this.flip(); }
  flip() { this.isOn.update(v => !v); }
}
```

On the styling side, **`:host`** targets the component's own host element from within its (encapsulated) stylesheet — it's the only way to style the element the component lives in, since normal selectors only reach into the template. **`:host-context(selector)`** styles the host element *conditionally based on an ancestor* somewhere up the DOM tree — e.g., apply dark styling only when an ancestor has `.theme-dark`. The distinction matters because of view encapsulation: without `:host`/`:host-context`, a component's scoped styles cannot reach its own boundary or react to outside context.

```css
:host { display: block; border: 1px solid #ccc; }      /* the component's own element */
:host(.active) { border-color: green; }                /* host when it has .active */
:host-context(.theme-dark) { background: #111; }        /* host, when any ancestor is .theme-dark */
```

#### Q44. [Theory] How do the three `ViewEncapsulation` modes work, and what are their trade-offs?

Angular scopes component CSS so styles don't leak between components, and `ViewEncapsulation` controls *how*. **`Emulated`** (the default) does not use real Shadow DOM; instead the compiler **rewrites your selectors at build time** by adding a unique per-component attribute (e.g., `_ngcontent-abc-1`) to both the component's elements and its CSS rules. So `.title` becomes `.title[_ngcontent-abc-1]`. This emulates scoping with plain CSS, works everywhere, and still lets global styles cascade in — but it's *emulation*, so a determined global selector can still reach in, and the attribute-rewriting adds specificity quirks.

**`ShadowDom`** uses the browser's native Shadow DOM: the component's template and styles live in a real shadow root, giving **true encapsulation** — outside styles cannot penetrate (except inherited properties and CSS custom properties), and inside styles cannot leak out. The trade-off is strictness: global stylesheets and many third-party CSS frameworks won't reach inside, theming must go through CSS variables, and you lose some flexibility. **`None`** disables encapsulation entirely — the component's styles become **global**, which is occasionally useful for a deliberately global theme component but dangerous because its rules now affect the whole app.

```typescript
@Component({
  selector: 'app-card',
  encapsulation: ViewEncapsulation.Emulated,   // default; attribute-based scoping
  styles: [`.title { font-weight: 700; }`],     // compiled to .title[_ngcontent-xyz]
})
export class CardComponent {}
```

| Mode | Mechanism | Outside styles reach in? | Styles leak out? | Use when |
|---|---|---|---|---|
| `Emulated` | Build-time attribute rewriting | Partially (global cascade) | No (scoped) | Default; best balance |
| `ShadowDom` | Native shadow root | No (except inherited/vars) | No | Strict isolation, web-component output |
| `None` | No scoping | Yes | Yes (global) | Intentional global styles only |

The historical footnote worth knowing: there was a `Native` mode and `::ng-deep` (the deprecated successor to `/deep/` and `>>>`) used to pierce encapsulation; `::ng-deep` still works but is deprecated, and the modern approach to cross-component theming is **CSS custom properties**, which pass through all encapsulation modes cleanly.

#### Q45. [Theory] What is the difference between `Subject`, `BehaviorSubject`, `ReplaySubject`, and `AsyncSubject`?

All four are RxJS **Subjects** — objects that are simultaneously an `Observable` (you can subscribe) and an `Observer` (you can `next`/`error`/`complete`), making them multicast. The difference is entirely in **what a late subscriber receives**. A plain **`Subject`** has no memory: subscribers get only values emitted *after* they subscribe; anything emitted before is missed. This models pure event streams (clicks, "save pressed") where past events are irrelevant.

A **`BehaviorSubject`** requires an initial value and always remembers the **latest** value, replaying it immediately to any new subscriber. This is the canonical choice for **state** ("the current selected tab," "current user") because state always has a current value and new consumers need it right away. A **`ReplaySubject(n)`** buffers the last `n` values (optionally within a time window) and replays all of them to new subscribers — useful for "the last 5 notifications" or caching recent history. An **`AsyncSubject`** emits **only the final value, and only on completion**, discarding everything else — rarely used directly, but it's conceptually what an HTTP request resembles (one result at the end).

```typescript
const s = new Subject<number>();           s.next(1); s.subscribe(v => log(v)); // gets nothing
const b = new BehaviorSubject<number>(0);  b.next(1); b.subscribe(v => log(v)); // gets 1 (latest)
const r = new ReplaySubject<number>(2);    r.next(1); r.next(2); r.next(3);
r.subscribe(v => log(v));                  // gets 2, 3 (last 2)
const a = new AsyncSubject<number>();      a.next(1); a.next(2); a.complete();
a.subscribe(v => log(v));                  // gets 2 (final, on complete)
```

| Subject | Initial value | Replays to late subscribers | Typical use |
|---|---|---|---|
| `Subject` | none | nothing | Event streams |
| `BehaviorSubject` | required | latest value | Current state |
| `ReplaySubject(n)` | none | last `n` (or windowed) | Recent history / cache |
| `AsyncSubject` | none | final value on complete | Single deferred result |

For interviews, the load-bearing insight is that `BehaviorSubject` models *state* (always has a current value) while a plain `Subject` models *events* (fire-and-forget) — and in modern Angular a `BehaviorSubject` used purely for synchronous state is increasingly replaced by a writable `signal`, which gives the same "always has a current value, read it synchronously" semantics without subscription management.

#### Q46. [Theory] What is the difference between `switchMap`, `mergeMap`, `concatMap`, and `exhaustMap`, and how do you choose?

These four higher-order mapping operators all project each source value into an inner observable and flatten the results, but they differ in **how they handle concurrency and overlap** — and choosing wrong causes real bugs (race conditions, dropped requests, memory growth). **`switchMap`** cancels the previous inner observable when a new source value arrives, keeping only the latest. This is correct for **type-ahead search and navigation** where a stale in-flight response must never overwrite a newer one. **`mergeMap`** (a.k.a. `flatMap`) subscribes to all inner observables **concurrently** and merges their outputs in completion order — correct for **independent parallel work** (firing N analytics calls) but dangerous for ordered or cancelable work because results can interleave and nothing is canceled.

**`concatMap`** queues inner observables and runs them **strictly one at a time, in order**, waiting for each to complete — correct when **order matters and overlap is unsafe**, e.g., sequential writes that must not race. **`exhaustMap`** **ignores new source values while an inner observable is still active** — correct for **preventing duplicate submissions**, e.g., a login button that shouldn't fire a second request while the first is pending.

```typescript
// switchMap: cancel stale — search box
query$.pipe(switchMap(q => api.search(q)));        // only the latest query's results survive

// mergeMap: all in parallel — fire-and-forget
clicks$.pipe(mergeMap(() => api.logEvent()));      // concurrent; order not guaranteed

// concatMap: strict order — sequential saves
edits$.pipe(concatMap(e => api.save(e)));          // each save completes before the next starts

// exhaustMap: drop while busy — guard double submit
submit$.pipe(exhaustMap(() => api.login(form)));   // ignores clicks while login in flight
```

| Operator | On new value while inner active | Choose for |
|---|---|---|
| `switchMap` | Cancel previous, switch to new | Search, navigation, "latest wins" |
| `mergeMap` | Run both concurrently | Independent parallel work |
| `concatMap` | Queue, run after current finishes | Ordered, non-overlapping writes |
| `exhaustMap` | Ignore the new one | Prevent duplicate submits |

The most important real-world failure mode: using `mergeMap` for a search box. Because it doesn't cancel, a slow response to an old query can arrive *after* a fast response to a new query, leaving the wrong results on screen — the exact race `switchMap` exists to prevent.

#### Q47. [Practical] Explain hot vs cold observables and the role of multicasting operators like `share`/`shareReplay`.

A **cold** observable creates its producer **per subscription** — each subscriber gets an independent execution from the start. `HttpClient.get()` is cold: every `.subscribe()` fires a **new HTTP request**. This surprises people who `| async` the same request observable in three template spots and see three network calls. A **hot** observable shares a single producer among all subscribers, so they observe the same in-flight values; DOM events, `Subject`s, and WebSocket streams are hot — subscribing late means you miss earlier emissions.

The operators `share()` and `shareReplay()` **convert cold to hot via multicasting**: they put a `Subject` between the source and the subscribers, so one underlying execution is shared. `share()` keeps the source alive while there is at least one subscriber and (by default) tears down when the count hits zero. `shareReplay({ bufferSize: 1, refCount: true })` additionally **replays the last value(s)** to late subscribers — the idiomatic way to cache an HTTP result so multiple consumers share one request and latecomers still get the cached response.

```typescript
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private http = inject(HttpClient);

  // Cold by nature; shareReplay makes it a shared, cached single request.
  readonly config$ = this.http.get<Config>('/api/config').pipe(
    shareReplay({ bufferSize: 1, refCount: true }),
  );
}
```

The trade-off and footgun: `shareReplay({ refCount: false })` (or the legacy `shareReplay(1)`) **never unsubscribes from the source** even after all subscribers leave, which keeps a subscription — and any retained values — alive indefinitely, a subtle leak. Use `refCount: true` unless you deliberately want a permanent cache. In zoneless/signals land, much of this caching is increasingly handled by `resource`/`rxResource` or signal stores, but understanding hot/cold and multicasting remains essential for diagnosing "why is my API called N times?"

### 🟠 Advanced — extended

#### Q48. [Theory] Walk through Angular's bootstrap sequence from `main.ts` to the first rendered frame.

Bootstrap begins in `main.ts` with `bootstrapApplication(AppComponent, appConfig)`. Angular first creates the **root `EnvironmentInjector`** by merging the platform providers with the providers from your `ApplicationConfig` (the `provideX()` functions) — this is where `provideRouter`, `provideHttpClient`, `provideClientHydration`, and zone configuration register. It then creates the **root component's element injector**, instantiates the root component, and runs the **first change-detection pass**, which executes the compiled template render function to create the initial DOM. After the view stabilizes, `ApplicationRef` marks the app as bootstrapped and the framework becomes responsive to future CD triggers.

```typescript
// main.ts
bootstrapApplication(AppComponent, {
  providers: [
    provideZonelessChangeDetection(),   // or provideZoneChangeDetection()
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideClientHydration(withIncrementalHydration()),
  ],
});
```

```
main.ts: bootstrapApplication
  → create PlatformInjector
  → create root EnvironmentInjector (merge ApplicationConfig providers)
  → APP_INITIALIZER / provideAppInitializer run (await async init)
  → create AppComponent ElementInjector + instance
  → first CD pass: run compiled template → create DOM
  → (SSR) hydration: reuse/attach to server DOM instead of recreating
  → router resolves initial URL → activates route components
  → ApplicationRef.tick loop becomes the steady-state CD driver
```

Two phases deserve emphasis. **`APP_INITIALIZER`/`provideAppInitializer`** lets you run async work (load runtime config, feature flags, auth bootstrap) *before* the app renders — Angular awaits the returned promises before the first tick, which is the correct place to ensure config is present app-wide. And under SSR, the first CD pass doesn't create fresh DOM; **hydration** reuses the server-rendered markup, attaching listeners and bindings to existing nodes. Knowing this sequence explains why provider configuration is global-by-default, why initializers exist, and where hydration slots in.

#### Q49. [Theory] How does Angular compile templates — what changed from View Engine to Ivy, and what is locality?

Before Angular 9, the **View Engine** compiler produced `.metadata.json` and `.ngfactory` files and relied on a *global* compilation model: to compile a component, the compiler often needed metadata about the entire module graph, which forced libraries to ship in a special "Angular Package Format" and made compilation slower and less tree-shakable. **Ivy** (default since v9) replaced this with a **locality**-based model: each component, directive, and pipe is compiled **independently**, using only the information available in its own decorator and imports. The compiler emits the `ɵcmp`/`ɵdir`/`ɵpipe` static definitions directly onto the class, with no separate factory files.

**Locality** is the load-bearing concept. Because a component can be compiled knowing only its own metadata (its template, its `imports`), libraries can be published as **plain compiled JavaScript** — consumers no longer recompile library source, and tree-shaking works because references are explicit static function calls (`elementStart`, `property`, `textInterpolate`) that the bundler can drop if unused. This is what enabled smaller bundles, faster incremental builds, better debugging (real component instances and stack traces), dynamic component creation without factories, and ultimately the standalone + signals architecture.

```
View Engine (pre-v9)            Ivy (v9+)
-----------------               ---------
global compilation              local compilation (per-declaration)
.ngfactory + .metadata.json     ɵcmp static field on the class
recompile libs from source      ship plain compiled JS
coarse tree-shaking             instruction-based, fine tree-shaking
ComponentFactoryResolver        ViewContainerRef.createComponent(Class)
```

For interviews, connect locality to *why modern Angular looks the way it does*: standalone components (a component declares its own deps) are a natural fit for local compilation; signals integrate because the instruction set can subscribe specific bindings to specific signals; and the removal of `ComponentFactoryResolver`/`entryComponents` is a direct consequence of Ivy no longer needing factories.

#### Q50. [Theory] What is `forwardRef` and why is it occasionally necessary in DI?

`forwardRef` exists to solve a **temporal ordering problem** in JavaScript: when you reference a class in a decorator or provider before that class has been **defined** in the module's evaluation order, the reference is `undefined` at decoration time. This happens with circular dependencies (two services/components that reference each other) and with classes used in a provider above their own declaration. `forwardRef(() => SomeClass)` wraps the reference in a function so Angular resolves it **lazily** — at injection time rather than at class-definition time — by which point the class exists.

The most common practical case is implementing `ControlValueAccessor`: a component registers itself as an `NG_VALUE_ACCESSOR` provider in its own `@Component` metadata, but the class isn't defined yet when the decorator runs, so you must forward-reference it.

```typescript
@Component({
  selector: 'app-rating',
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => RatingComponent),  // class not yet defined here
      multi: true,
    },
  ],
  template: `...`,
})
export class RatingComponent implements ControlValueAccessor { /* ... */ }
```

The deeper point an interviewer probes: `forwardRef` is a **symptom**, and frequent need for it often signals a circular dependency worth refactoring (extract a shared interface/token, invert the dependency). It's legitimate and unavoidable for the `ControlValueAccessor` self-registration pattern, but elsewhere it's a code smell. Note also that with the `inject()` function and modern patterns, some historical `forwardRef` usages in constructor parameter decorators have become rarer, though the self-referential provider case remains.

#### Q51. [Theory] How does the `Router` work internally — explain the navigation lifecycle and guard/resolver execution order.

A router navigation is a **pipeline** that transforms a URL into an activated component tree, and it's fully observable via the `Router.events` stream. When navigation starts (`NavigationStart`), the router parses the URL into a `UrlTree`, then **recognizes** which route configuration matches, building a future `RouterStateSnapshot`. It then runs the guard pipeline in a defined order, executes resolvers, and only if everything passes does it **activate** the matched components and update the address bar; otherwise it cancels (`NavigationCancel`) or errors (`NavigationError`).

The guard execution order is precise and worth memorizing: **`CanDeactivate`** (on the components being left) runs first — you can't navigate away from a dirty form without confirming. Then **`CanActivateChild`** and **`CanActivate`** run from the **top route down**. **`CanMatch`** is special: it runs during *route matching*, before the route is even selected, so a failed `CanMatch` makes the router try the next matching route and — crucially — the lazy chunk is **never downloaded**. After guards pass, **resolvers** run to prefetch data, the router waits for them, then activates.

```
NavigationStart
  → parse URL → recognize routes (CanMatch decides matching; can skip lazy download)
  → CanDeactivate (leaving components)
  → CanActivateChild / CanActivate (top → down)
  → Resolvers run (router waits for data)
  → GuardsCheckEnd → ResolveEnd
  → activate components, update URL
  → NavigationEnd   (or NavigationCancel / NavigationError)
```

```typescript
export const adminGuard: CanMatchFn = (route, segments) => {
  const auth = inject(AuthService);
  return auth.isAdmin() ? true : inject(Router).createUrlTree(['/forbidden']);
};
// CanMatch on a lazy route: unauthorized users never even download the admin chunk.
```

The practical distinction interviewers want: use **`CanMatch`** (not `CanActivate`) when you want to *prevent the lazy bundle from loading* for unauthorized users or to choose between alternative route definitions by condition; use `CanActivate` when the chunk should load but access is gated. Resolvers should be used sparingly — they *block* navigation until data arrives, which can make the app feel sluggish; often it's better to navigate immediately and load data reactively (with a skeleton) via `resource`/`rxResource`.

#### Q52. [Theory] How does `effect()` scheduling work, and why are effects discouraged for deriving state?

`effect()` registers a side-effecting reactive function that re-runs when any signal it reads changes. The critical internal detail is that effects are **not** synchronous: writing to a signal does not immediately run dependent effects. Instead, Angular **schedules** them, and they flush as part of the framework's change-detection/scheduling, typically batched so that multiple synchronous signal writes in the same tick coalesce into **one** effect run. Effects also run an **initial** time to capture their dependencies, and they're tied to the injection context's `DestroyRef`, so they auto-clean-up on destroy. By default writing signals *inside* an effect is disallowed (to prevent feedback loops) unless you opt in with `allowSignalWrites`.

The reason to avoid effects for **deriving state** is both architectural and mechanical. Derivation should be **pull-based and pure** — `computed()` recomputes lazily only when read and only when dependencies actually changed, is memoized, has no ordering hazards, and never triggers extra change detection. An effect that reads signal A and writes signal B to "derive" B is push-based, runs as a scheduled side effect, can cause `ExpressionChangedAfterItHasBeenCheckedError` if B is read in the same view's render, and creates an imperative data flow that's hard to reason about. Effects are for **synchronizing with the non-reactive world**: logging, manual DOM/canvas updates, `localStorage` persistence, integrating a third-party imperative library.

```typescript
export class ChartComponent {
  data = input.required<number[]>();

  // GOOD: derivation is a computed — pure, memoized, pull-based
  max = computed(() => Math.max(...this.data()));

  constructor() {
    // GOOD: effect bridges to the imperative world (a non-Angular chart lib)
    effect(() => this.chartLib.render(this.data()));

    // BAD smell: using an effect to derive a signal (use computed instead)
    // effect(() => this.maxSig.set(Math.max(...this.data())), { allowSignalWrites: true });
  }
}
```

The interview-level summary: `computed` for **derived values**, `effect` for **side effects at the system boundary**. Reaching for `effect` + `set` to compute state is the single most common signals anti-pattern, and the framework's discouragement of signal writes in effects is a deliberate guardrail against it.

#### Q53. [Theory] Explain glitch-free propagation in signals and how it differs from naive observer patterns.

A "glitch" is a transient, **inconsistent intermediate value** observed during reactive propagation. Consider `a` and `b = computed(() => a() + 1)` and `c = computed(() => a() + b())`. In a naive push-based observer system, updating `a` might notify `c` *before* `b` has recomputed, so `c` briefly reads a stale `b` and produces a wrong intermediate result before correcting — a glitch. Angular's signal graph is engineered to be **glitch-free**: a single update to `a` never lets any consumer observe a partially-updated graph.

It achieves this with a **push-pull, versioned, lazy** algorithm. On *write*, signals **push** a lightweight "dirty/maybe-dirty" notification down the dependency graph (marking dependents stale) but do **not** eagerly recompute. On *read*, `computed` values **pull**: a memoized computed checks whether any of its dependencies' **versions** actually changed; if so it recomputes, otherwise it returns the cached value. Because recomputation is demand-driven and topologically consistent at read time, by the time `c` is read, `b` is guaranteed already up to date — no stale intermediate is ever exposed. Equality checks (`computed`'s `equal`, default `Object.is`) further **stop propagation** when a recomputed value is unchanged, avoiding needless downstream work.

```
write a.set(2):
  a  ──push "dirty"──►  b (maybe-dirty)
   └──push "dirty"──►  c (maybe-dirty)

read c():
  c needs a() and b()
   → a is clean (version 5)
   → b is maybe-dirty → pull b → recompute (now consistent) 
   → c computes with the *current* a and b  → no glitch
```

This contrasts with `BehaviorSubject` + `combineLatest`, which is **push-based and eager**: `combineLatest` can emit intermediate combinations as each source fires, producing exactly the glitchy double-emission signals avoid. The design also explains why signals are **synchronous to read** (you call `c()` and get the consistent value now) yet cheap (no recompute unless a real dependency version changed) — a combination that pure push systems struggle to provide and a key reason Angular chose this model to drive zoneless change detection.

#### Q54. [Theory] What is `ApplicationRef.tick()`, and how does the CD scheduler decide when to run under zoneless?

`ApplicationRef.tick()` is the entry point that runs **one full change-detection pass over all attached views** (all root components and their trees). In the Zone.js model, NgZone's `onMicrotaskEmpty` event triggers `tick()` automatically after async tasks settle — that's the "magic" that updates the view after a `setTimeout` or HTTP response. Each `tick()` traverses the component tree, and `OnPush`/dirty-marking prunes which components are actually re-evaluated, but the *scheduling* of when to tick is driven by Zone.

Under **zoneless** (`provideZonelessChangeDetection()`), there is no Zone to signal "async settled." Instead Angular installs a **CD scheduler** that is notified explicitly through well-defined hooks: a signal read in a template that later changes calls into the scheduler; `ChangeDetectorRef.markForCheck()`, the `AsyncPipe`, host/template event listeners, and attaching/detaching views all notify it. The scheduler then **coalesces** these notifications and schedules a single tick on a microtask/`requestAnimationFrame`-like boundary, so many state changes in one synchronous block produce **one** CD pass rather than many. This is the same coalescing you can opt into even with zones via `provideZoneChangeDetection({ eventCoalescing: true })`.

```typescript
provideZonelessChangeDetection();   // no Zone.js; explicit notifications drive the scheduler

// Conceptually, what notifies the zoneless scheduler:
//  - a changed signal that was read in a template
//  - ChangeDetectorRef.markForCheck()
//  - AsyncPipe receiving a value
//  - a (click)/(input)/host event firing
//  - ViewContainerRef attach/detach
// → scheduler coalesces them → schedules ONE ApplicationRef.tick()
```

The crucial behavioral consequence: under zoneless, a state mutation that **doesn't go through one of these channels** (e.g., mutating a plain field from a `setTimeout` and relying on Zone to "notice") will **not** schedule a tick, so the view won't update. This is exactly why the zoneless migration requirement is "route every state change through signals, the async pipe, or `markForCheck()`" — there is no longer a global async interceptor catching changes for you.

#### Q55. [Theory] How do `providedIn: 'root'` vs `'platform'` vs `'any'` vs component-level providers differ in instance scoping?

The `providedIn` value (and component/route `providers` arrays) determines **which injector owns the instance**, which in turn determines **how many instances exist and who shares them**. `providedIn: 'root'` registers the service in the **root environment injector**, producing one **application-wide singleton** shared by every component and lazy module — the default and right choice for most stateless or globally-shared services, and it's tree-shakable (dropped if never injected).

`providedIn: 'platform'` registers in the **platform injector**, which sits *above* the application injector and is shared across **multiple Angular applications bootstrapped on the same page** (a micro-frontend or multi-app scenario). `providedIn: 'any'` is the subtle one: it creates **one instance per injector that requests it** — specifically, the root injector and *each lazy-loaded environment injector* get their **own** instance. This matters when a service holds module-scoped state and you don't want a lazy feature to share the root's instance. Finally, listing a service in a **component's `providers` array** creates a new instance **per component instance** (scoped to that element injector and its descendants) — used for per-instance state like a form/wizard state service, or a component-scoped data store.

```typescript
@Injectable({ providedIn: 'root' })      // ONE app-wide singleton
@Injectable({ providedIn: 'platform' })  // shared across multiple bootstrapped apps
@Injectable({ providedIn: 'any' })       // one per (root + each lazy) environment injector

@Component({
  selector: 'app-wizard',
  providers: [WizardStateService],       // a fresh instance per <app-wizard> instance
})
export class WizardComponent {}
```

| Scope | Instances | Shared by | Typical use |
|---|---|---|---|
| `'root'` | 1 | Entire app + all lazy modules | Default singleton services |
| `'platform'` | 1 | All apps on the page | Micro-frontends / multi-bootstrap |
| `'any'` | 1 per environment injector | Each lazy boundary independently | Module-isolated state |
| component `providers` | 1 per component instance | That component subtree | Per-instance/scoped state |

The interview trap is assuming all `@Injectable` services are global singletons — they are only when `providedIn: 'root'`. Misusing component-level `providers` for what should be a singleton silently creates multiple instances (and duplicated state); conversely, putting per-wizard state in `'root'` leaks one wizard's state into the next.

### 🔴 Expert — extended

#### Q56. [Theory] Reconcile "signals are synchronous and pull-based" with "effects are async and push-scheduled." Why this hybrid, and what bugs does each choice prevent?

This apparent contradiction is a deliberate, carefully reasoned split between the **read path** and the **reaction path**. The read path — `signal()`/`computed()` — is **synchronous and pull-based** because a template (or any consumer) needs to read a **consistent, current value right now** during rendering. Pull-based reads with version checking give you that: `c()` always returns the correct, glitch-free value computed from up-to-date dependencies, with no waiting and no stale intermediate. Making reads async would be catastrophic — templates would render placeholder/old values and require an extra tick to settle.

The reaction path — `effect()` — is **asynchronous and scheduled** because side effects must be **batched and ordered relative to change detection**. If effects ran synchronously on every `set()`, then writing three signals in a loop would fire dependent effects three times (wasteful), and an effect that touched the DOM could run *before* the rest of the state settled, producing inconsistent side effects and feedback storms. Scheduling lets Angular coalesce many writes in a tick into a single effect run and place that run at a safe point in the CD cycle.

```typescript
const a = signal(0), b = signal(0);
const sum = computed(() => a() + b());     // pull: read sum() anytime → always consistent

effect(() => console.log('sum=', sum()));  // scheduled: runs ONCE after both writes below

a.set(1);
b.set(2);
// synchronous reads here already see sum() === 3 (pull-based, current)
// the effect logs "sum= 3" once, after the tick — NOT "1" then "3"
```

The bugs each choice prevents are different. Synchronous pull reads prevent **rendering stale/glitchy values** and the need for `async`-pipe-style waiting on local state. Async scheduled effects prevent **redundant side-effect execution, feedback loops, and effect-vs-render ordering hazards** (a major source of `ExpressionChangedAfterItHasBeenCheckedError` in the old world). The hybrid is the whole point: you get instantaneous consistent *values* for the view, and disciplined, batched *side effects* for the world outside — and the framework's prohibition on writing signals inside effects (without opt-in) is the guardrail that keeps these two paths from contaminating each other.

#### Q57. [Theory] Compare Angular's DI container to a "service locator" and to React's context/props model. Why did Angular bet on hierarchical DI, and what are the costs?

Angular's DI is a **hierarchical, constructor/field-injection container** with two interleaved injector trees (environment and element injectors). It is *not* a service locator in the anti-pattern sense: dependencies are **declared** (as constructor params or `inject()` calls) and **resolved by the framework**, so a class's needs are explicit and statically analyzable, rather than the class reaching into a global registry by string key (the service-locator smell that hides dependencies and defeats testing). The hierarchy means resolution walks from the requesting element injector up to the root environment injector, which is what enables **scoped overrides**: provide a different `Logger` for one route subtree, a per-component `FormState`, or a mock in a test by overriding at the right level.

Compared to **React**, the contrast is philosophical. React passes dependencies **explicitly through props** or via **Context** (a value broadcast down a subtree). Context is closest to DI but is *value-broadcast*, not *type-keyed instance resolution* — there's no hierarchical "walk up until found by token with `@Optional/@SkipSelf` semantics," no automatic singleton lifecycle, and no tree-shakable provider registration. React deliberately keeps wiring explicit and minimal; Angular deliberately centralizes it so large teams get consistent, overridable, testable dependency graphs without prop-drilling.

```typescript
// Angular: declared dependency, resolved by hierarchy, overridable per subtree
@Component({ providers: [{ provide: Logger, useClass: RouteLogger }] })  // scoped override
export class ReportsComponent { private log = inject(Logger); }          // resolved up the tree
```

Angular bet on hierarchical DI because its target — **large, long-lived enterprise apps with many teams** — benefits enormously from: testability (swap implementations by overriding a token), extensibility (multi-providers for `HTTP_INTERCEPTORS`, validators, router features), and scoping (route/component instances). The costs are real and worth naming: a **conceptual learning curve** (`@Self/@SkipSelf/@Optional/@Host`, the two injector trees, `providedIn` semantics), **runtime resolution cost** (small, but nonzero vs passing a value), and the possibility of **accidental multiple instances** when scoping is misunderstood. The honest staff-level take: DI is a high-leverage feature for big apps and overkill for tiny ones — its value scales with team size and app longevity, which is precisely Angular's niche.

#### Q58. [Theory] Why did Angular adopt signals when it already had RxJS, and what is the long-term coexistence story?

RxJS is a **general-purpose asynchronous stream** library — superb for events over time, composition, cancellation, backpressure, and time-based operators. But Angular used it for two *different* jobs: modeling async streams (its strength) **and** holding synchronous component state (a poor fit). Using `BehaviorSubject` + `async` pipe for a simple counter forces subscription semantics, lifecycle management, the `async` pipe's impurity, and — importantly — couples reactivity to Zone.js, because a stream emission only updates the view if Zone notices it or you `markForCheck`. RxJS also can't power **fine-grained, glitch-free, synchronous-read** change detection: it's push-based and can emit glitchy intermediates, and reading "the current value" synchronously is awkward (`getValue()` exists only on `BehaviorSubject` and is discouraged).

Signals were adopted to own the **synchronous state + fine-grained reactivity** job that RxJS did badly, and specifically to enable **zoneless change detection**: because a signal knows exactly which template bindings read it, Angular can update *only those bindings* when it changes, with no Zone and no whole-tree traversal. Signals are synchronous to read, glitch-free, memoized, and need no subscription cleanup — everything you want for view state.

```typescript
// State → signals; async streams → RxJS; bridge at the boundary.
filter = signal('');                                   // synchronous UI state
results = toSignal(                                     // async stream → signal for the template
  toObservable(this.filter).pipe(
    debounceTime(300),
    switchMap(q => this.api.search(q)),                 // RxJS: debounce + cancel (its strength)
  ),
  { initialValue: [] },
);
```

The long-term coexistence story is **not replacement but division of labor**, bridged by `@angular/core/rxjs-interop` (`toSignal`, `toObservable`, `rxResource`). Signals become the default for component/derived state and the change-detection driver; RxJS remains the tool for genuinely asynchronous, time-based, multi-source composition (websockets, debounced search, retry/backoff, complex cancellation). The team has been explicit that RxJS is **not** going away — it's being repositioned from "do everything" to "do async streams," with signals handling state. The mature mental model: reach for a signal first for state; reach for RxJS when you're composing events *over time*; convert at the seam.

#### Q59. [Theory] What exactly causes a hydration mismatch, what does Angular do when it detects one, and how does incremental hydration change the risk profile?

A hydration mismatch occurs when the DOM structure the **server rendered** differs from what the **client would render** for the same state, so when Angular walks the server DOM to attach bindings/listeners, the nodes it expects don't line up with what's there. Common causes: **browser-only APIs running during SSR** producing different output (reading `window.innerWidth`, `Date.now()`, `Math.random()`, locale/timezone differences between server and client), **direct DOM manipulation** (via `ElementRef.nativeElement`, third-party libraries, or `innerHTML`) that the server didn't produce, **invalid HTML nesting** that the browser silently "fixes" on the client (e.g., a `<div>` inside a `<p>`, or block elements inside `<table>` that the parser relocates), and **non-deterministic data** ordering.

When Angular detects a mismatch during hydration, it logs a hydration error (with the offending component/node in dev) and **falls back to destructive re-rendering of that subtree** — it discards the server DOM for that region and re-renders from scratch on the client. This defeats the purpose of hydration for that area (you get the flicker and lost work non-destructive hydration was meant to eliminate) and can hurt performance and CLS. The fixes: guard browser-only code with `isPlatformBrowser()`/`afterNextRender()` (which runs only on the client, after render), avoid manual DOM writes during initial render, keep HTML valid, and ensure server-fetched data is **transferred** to the client (`TransferState` / `withHttpTransferCacheOptions`) so the client renders identical output instead of refetching and diverging.

```typescript
export class WidthAwareComponent {
  width = signal(0);
  constructor() {
    // Runs only in the browser, after the first render — safe from SSR mismatch
    afterNextRender(() => this.width.set(window.innerWidth));
  }
}
```

**Incremental hydration** (`withIncrementalHydration()` + `@defer (hydrate on …)`, dev preview v19) changes the risk profile in two directions. Positively, blocks that aren't hydrated yet stay as **inert server HTML** and only attach JS on their trigger, shrinking initial hydration JS and limiting where mismatches can occur at boot. But it adds new considerations: the server must render the deferred content (it's *dehydrated*, not absent), the trigger boundary must be correct, and you must ensure the state at hydration time still matches the server output. Net: incremental hydration reduces *upfront* hydration cost and mismatch surface at load, but you must reason about hydration *per `@defer` block* rather than once for the whole app.

#### Q60. [Practical] Two services depend on each other and you get a circular-dependency / `NG0200` error. Diagnose the root cause and lay out resolution options with trade-offs.

A circular DI dependency means injector A needs B to construct, and B needs A to construct, so neither can be instantiated first — Angular detects the cycle and throws (historically the cyclic-dependency error; `NG0200` is the related "circular dependency in DI" family). At runtime under zoneless you may also see `NG0200` for a `ChangeDetectorRef`-during-construction issue, but the classic case is two `@Injectable`s referencing each other in their constructors. The first diagnostic step is to read the dependency chain in the error (Angular prints `A -> B -> A`) and confirm whether the cycle is at **construction time** (fatal) or merely a logical coupling.

The resolution options, roughly best to worst:

```typescript
// Root cause: AuthService needs UserService and vice versa at construction.

// Option 1 (best): break the cycle by extracting shared logic/state into a third service.
@Injectable({ providedIn: 'root' })
class SessionStore { token = signal<string|null>(null); }   // both depend on this; no cycle

// Option 2: defer one injection so it's resolved lazily, not at construction.
@Injectable({ providedIn: 'root' })
class AuthService {
  private injector = inject(Injector);
  private get users() { return this.injector.get(UserService); }  // resolved on first use
}

// Option 3: forwardRef — works, but only masks a true construction-time cycle.
constructor(@Inject(forwardRef(() => UserService)) private users: UserService) {}
```

| Approach | Fixes the design? | When appropriate |
|---|---|---|
| Extract shared service/state (invert dependency) | Yes — removes the cycle | Almost always the right fix |
| Lazy `inject(Injector).get(...)` on demand | Partially — cycle remains, just deferred | When the call is genuinely late and refactor is costly |
| `forwardRef` | No — masks it | Self-referential providers (`ControlValueAccessor`); last resort otherwise |

The staff-level point: a circular dependency is a **design signal**, not just an error to silence. The durable fix is to find the **shared concern** the two services are fighting over and extract it into a third unit they both depend on (dependency inversion), turning a cycle into a tree. `forwardRef` and lazy `Injector.get` make the error go away but leave the tangle in place, so they're appropriate only for genuinely unavoidable cases (like the self-registering `NG_VALUE_ACCESSOR` pattern) or as a temporary bridge while you refactor.

#### Q61. [Theory] Explain content projection internals: single-slot vs multi-slot, conditional projection, and why projected content's change detection belongs to the declaring component.

Content projection moves DOM the **consumer** wrote into a slot inside the **component's** template. With a bare `<ng-content>` you get **single-slot** projection — all projected children land there. With `select="..."` you get **multi-slot** projection: each `<ng-content select="[header]">` captures the projected nodes matching that CSS selector, and a final selector-less `<ng-content>` catches the rest. The matching happens **once at compile/instantiation**, statically, against the projected nodes — it is not a live filter, which is why you can't dynamically re-route already-projected content by changing data.

```html
<!-- panel.component.html : multi-slot -->
<header><ng-content select="[panel-title]"></ng-content></header>
<section><ng-content></ng-content></section>            <!-- default catch-all -->

<!-- consumer -->
<app-panel>
  <h2 panel-title>Settings</h2>     <!-- → header slot -->
  <p>Body content here.</p>          <!-- → default slot -->
</app-panel>
```

A subtle but heavily-tested point is **who owns the change detection** of projected content. The projected nodes are **created by the consumer (the parent component)**, so they belong to the parent's **logical view** even though they are physically displayed inside the child. Their bindings are checked as part of the **declaring (parent) component's** change detection, and they see the **parent's** context, not the child's. This is also why projected content's lifecycle anchors at `ngAfterContentInit`/`ngAfterContentChecked` (it's "content" handed in) rather than `ngAfterViewInit` (the child's "own view"). The practical consequence: if the parent is `OnPush` and its projected bindings depend on parent state, they update on the parent's CD; the child can't force re-check of content it didn't create.

For **conditional projection**, a naive `@if` *around* an `<ng-content>` is dangerous because content is projected once — toggling it can destroy and not re-create projected children as you'd expect, and you lose their state. The modern idiom is to capture the projected content as a `TemplateRef` (with `ngTemplateOutlet`) or, in newer Angular, use the content-projection patterns that explicitly control instantiation, so you decide when the template is materialized rather than relying on static projection plus conditional wrappers. Understanding that projection is a **static relocation of consumer-owned DOM**, not a dynamic re-render, explains nearly every surprising behavior people hit with `<ng-content>`.

#### Q62. [Theory] How does `@defer` interact with the dependency graph and the build, and what determines whether a symbol ends up in the deferred chunk?

`@defer` is not merely a runtime "delay rendering" — it instructs the **compiler and bundler** to split code. During compilation, Angular analyzes which components, directives, and pipes are used **only inside** the `@defer` block (and the transitive dependencies reachable solely through them) and emits them into a **separate lazy chunk** loaded via dynamic `import()` on the trigger. The decisive rule is **exclusive use**: a symbol goes into the deferred chunk only if it is *not* also referenced from the eager part of the template (or elsewhere in the eager graph). If a heavy charting component is used both in a `@defer` block and in a non-deferred part of the same template, it stays in the main bundle and the defer saves nothing.

```html
@defer (on viewport; prefetch on idle) {
  <heavy-chart [data]="data()" />     <!-- HeavyChart used ONLY here → its own chunk -->
} @placeholder { <chart-skeleton /> } <!-- placeholder deps stay EAGER (shown immediately) -->
@loading { <spinner /> }
@error { <p>Failed.</p> }
```

This has concrete implications for how you author deferrable blocks. The **`@placeholder`** (and `@loading`/`@error`) content is shown *before* the deferred chunk loads, so its dependencies are **eager by design** — keep them lightweight. To maximize savings, ensure deferred-only components are **not imported eagerly** anywhere in the eager graph (a stray import in the component's `imports` array used by eager template parts can pull them back in). The **`prefetch`** trigger is decoupled from the **render** trigger precisely so you can download the chunk early (`prefetch on idle`) while only rendering on demand (`on viewport`), trading a bit of bandwidth for instant display when the trigger fires.

```
Compile/bundle view:
  main chunk  ── eager template, placeholder/loading/error deps, shared symbols
  defer chunk ── HeavyChart + deps reachable ONLY through the @defer block
                  loaded via import() when the trigger fires (or prefetch)
```

The expert nuance is the tension with **shared dependencies**: aggressive use of a common component across many places keeps it eager (it can't be exclusively-deferred), so realizing `@defer`'s bundle wins sometimes requires deliberately *not* sharing a heavy component, or isolating the heavy variant. And under SSR with **incremental hydration**, the same `@defer` block additionally governs *when JavaScript hydrates*, so a single construct now influences three things at once — initial bundle composition, client lazy-loading, and hydration timing — which is why understanding its compile-time semantics matters more than its surface syntax.

#### Q63. [Theory] Why is `ExpressionChangedAfterItHasBeenCheckedError` a dev-only check, and what does its existence reveal about Angular's unidirectional data flow guarantee?

Angular enforces a **single-pass, top-down (unidirectional) change-detection** model: in production, each CD cycle traverses the component tree once, updating bindings parent-to-child, and **assumes** that by the time it finishes a component, that component's bindings are stable for the cycle. This assumption is what makes CD fast and predictable — there's no fixed-point iteration re-checking until values settle. To *verify* the assumption holds, Angular runs a **second, read-only verification pass in development only** (`checkNoChanges`): it re-reads every binding and throws `ExpressionChangedAfterItHasBeenCheckedError` if any value differs from what was just rendered. In production this verification pass is stripped, both for performance and because — if your code is correct — it would never fire.

The error therefore reveals a **violation of the unidirectional guarantee**: something mutated bound state *as a side effect of rendering*, within the same tick, *after* Angular already checked it. The canonical trigger is a child component changing a value during `ngAfterViewInit`/`ngAfterContentInit` that a parent already rendered (data flowed *back up* during the same pass — the opposite of unidirectional), or a template getter that returns a fresh object each call (so the "before" and "after" differ by reference even with equal contents), or an `effect`/subscription synchronously writing state already rendered this tick.

```typescript
// Reveals back-flow during the same tick:
export class Child implements AfterViewInit {
  @Output() ready = new EventEmitter<number>();
  ngAfterViewInit() { this.ready.emit(42); }   // parent already rendered → throws in dev
}

// Fix: push the change to the NEXT tick (respect unidirectional flow)
ngAfterViewInit() { afterNextRender(() => this.ready.emit(42)); }
// or restructure so the value is derived (computed), not pushed back up.
```

The deep insight an interviewer wants: the error is **not a bug to suppress** — it's Angular telling you your data flow isn't actually unidirectional and your view isn't a pure function of state at this tick. The correct response is to make CD **idempotent within a tick** (stable references via `computed`, defer back-propagation to the next cycle with `afterNextRender`, or eliminate the upward write). Signals largely design this class of bug away because `computed` values are pure and pull-based, and the framework's discouragement of signal writes inside effects removes the most common synchronous-write-back path. The very existence of a dev-only checker underscores that Angular trades a fixed-point/multi-pass model (slower, "always converges") for a single-pass model (fast, but requires *you* to keep rendering side-effect-free).

#### Q64. [Theory] Compare `linkedSignal`, `computed`, and a writable `signal` initialized from an input. When is each correct, and what bug does `linkedSignal` solve?

These three address overlapping-but-distinct needs around **derived-yet-editable** state. A plain `computed()` is **read-only and always reflects its sources** — you cannot `set()` it, and it has no independent state. A writable `signal()` is **fully independent** — you control its value entirely, but it does **not** react to any source. The awkward middle case, which historically forced ugly workarounds, is: *"I want a value that is **derived from a source by default** but can be **locally overridden**, and that **resets** when the source meaningfully changes."* That's exactly `linkedSignal()` (stable in v19).

The classic bug `linkedSignal` solves: a select component shows a list of `options` (a source) and tracks the user's `selected` choice. If you make `selected` a plain writable signal initialized once from `options`, it **won't reset** when `options` changes — the user could be left with a `selected` value no longer in the new list (a stale selection bug). If you make it a `computed`, the user can't change it at all. `linkedSignal` gives both: it's writable (user can pick) *and* it recomputes/resets from the source when the source changes.

```typescript
options = input.required<Option[]>();

// computed: derived, NOT writable — user can't override
defaultChoice = computed(() => this.options()[0]);

// plain signal from input: writable, but does NOT reset when options change → stale-selection bug
// selected = signal(this.options()[0]);   // initialized once; goes stale

// linkedSignal: writable AND resets when the source changes — correct for editable-derived state
selected = linkedSignal({
  source: this.options,
  computation: (opts, prev) =>
    opts.find(o => o.id === prev?.value?.id) ?? opts[0],   // keep choice if still valid, else reset
});

choose(o: Option) { this.selected.set(o); }   // user override allowed
```

| Primitive | Writable? | Reacts to source? | Resets on source change? | Use for |
|---|---|---|---|---|
| `computed()` | No | Yes | n/a (always derived) | Pure derived values |
| writable `signal()` | Yes | No | No | Fully independent state |
| `linkedSignal()` | Yes | Yes (as default) | Yes | Editable state with a reactive default |

The expert framing: `linkedSignal` formalizes the **"derived default with local override and source-driven reset"** pattern that previously required an `effect` writing a signal (an anti-pattern) or manual `ngOnChanges` resets. Its `computation` receiving the **previous value** is the key feature — it lets you implement "preserve the user's choice if it's still valid, otherwise fall back," which is the real-world requirement that neither `computed` nor a plain signal can express cleanly.

#### Q65. [Practical] You must integrate a heavy non-Angular library (e.g., a canvas charting lib or a Web Component) that mutates the DOM directly. How do you do it cleanly under OnPush/zoneless without breaking CD or hydration?

The core tension is that such a library lives **outside** Angular's reactive world: it mutates the DOM imperatively, schedules its own animation frames, and knows nothing about signals or change detection. Three concerns must be handled: (1) keeping its high-frequency work **off** Angular's change detection, (2) **driving** Angular updates only when the library produces results you care about, and (3) not breaking **SSR/hydration**, since the library typically needs a real browser DOM and would diverge from server output.

The clean approach: instantiate and tear down the library in **browser-only, post-render hooks** (`afterNextRender`/`afterRenderEffect`, or guard with `isPlatformBrowser`), run its internal loops **outside the Angular zone** if zones are present (a no-op concern under zoneless, but `runOutsideAngular` is still the safe pattern when you can't assume zoneless), feed it Angular state via an **`effect`** (the legitimate use of effects — bridging to the imperative world), and **re-enter** Angular (or set a signal) only when the library emits something the view needs. Always clean up in `DestroyRef`.

```typescript
@Component({ selector: 'app-chart', template: `<canvas #cv></canvas>`,
  changeDetection: ChangeDetectionStrategy.OnPush })
export class ChartComponent {
  private cv = viewChild.required<ElementRef<HTMLCanvasElement>>('cv');
  data = input.required<Point[]>();
  pointHovered = output<Point>();
  private chart?: HeavyChart;

  constructor() {
    const destroyRef = inject(DestroyRef);

    // Browser-only init: avoids SSR/hydration mismatch (no canvas on the server)
    afterNextRender(() => {
      this.chart = new HeavyChart(this.cv().nativeElement);
      // Library callback re-enters Angular by writing through an output/signal:
      this.chart.onHover(p => this.pointHovered.emit(p));   // event → schedules CD (zoneless-safe)
      destroyRef.onDestroy(() => this.chart?.destroy());    // clean teardown — no leak
    });

    // Bridge reactive state → imperative library; effect re-runs only when data() changes
    effect(() => { this.chart?.setData(this.data()); });
  }
}
```

Key decisions and trade-offs: using **`afterNextRender`** (not `ngAfterViewInit`) guarantees the code runs only in the browser and after the DOM exists, which is what keeps hydration intact — the server renders an empty `<canvas>` and the client attaches the library afterward, with no mismatch. Using an **`effect` to push data into the library** is the textbook-correct effect use (side effect at the system boundary), as opposed to using effects to derive state. Routing the library's callbacks back through an **`output()`/signal write** is what schedules change detection under zoneless — a raw DOM callback mutating a plain field would *not* trigger a tick. And `runOutsideAngular` (when zones exist) prevents the library's `requestAnimationFrame` loop from triggering CD 60 times a second. The anti-patterns to call out: instantiating in the constructor (breaks SSR), mutating component fields from library callbacks without a signal/output (no CD under zoneless), and forgetting teardown (leaks the canvas/listeners).

#### Q66. [Theory] How does typed reactive forms (v14+) achieve type inference, and where do its type guarantees still break down?

Typed forms infer the **value shape** from the control structure you construct. A `FormControl<string>` carries its value type as a generic; a `FormGroup` infers an object type from its controls map; a `FormArray<FormControl<T>>` infers `T[]`. The builder (`FormBuilder`/`NonNullableFormBuilder`) propagates these generics so that `form.value`, `form.getRawValue()`, `form.get('field')`, and `valueChanges` are all statically typed without manual annotation. This was a major v14 win — pre-v14, every form was effectively `any`, so typos in control names and wrong value assumptions were runtime surprises.

```typescript
const form = inject(NonNullableFormBuilder).group({
  name: ['', Validators.required],          // FormControl<string>
  age: [0],                                  // FormControl<number>
  tags: inject(FormBuilder).array<string>([]),
});
form.value;            // { name?: string; age?: number; tags?: string[] }  ← note: PARTIAL
form.getRawValue();    // { name: string; age: number; tags: string[] }     ← complete
form.get('nmae');      // ← compile error: 'nmae' not a key
```

Where the guarantees **break down**, and why interviewers probe this: First, **`.value` is a `Partial`** (deep-partial), because controls can be **disabled**, and a disabled control is *excluded* from `value`. So `form.value.name` is `string | undefined` even though `name` is required — you must use `getRawValue()` to get the complete, non-optional shape. Second, **`form.get('a.b.c')` with a dotted string path returns `AbstractControl | null` (untyped)** — the deep-path overload loses the specific control type, so nested access via string paths isn't type-safe; you regain safety by navigating control-by-control or restructuring. Third, **nullability**: a plain `FormControl('x')` is **nullable** (`string | null`) because `reset()` can set it to `null`; only `nonNullable: true` (or `NonNullableFormBuilder`) gives you a non-null `string`. Fourth, **`patchValue`/`setValue`** are typed but `patchValue` accepts partials, so a typo in an *optional* nested patch can slip through.

The expert summary: typed forms give you **construction-time and key-name safety** and correct value types for the *enabled, raw* shape — a huge improvement — but the **disabled-control partiality** and the **dotted-path `get()` escape hatch** are the two places the type system intentionally can't track runtime reality. Production discipline is to standardize on `NonNullableFormBuilder` + `getRawValue()` for submission, and to avoid dotted `get()` paths in favor of typed navigation. This is also part of *why* signal-based forms are being explored — to express form state and validity as signals with cleaner, more uniform typing than the `AbstractControl` hierarchy can offer.

#### Q67. [Theory] Explain the trade-offs of micro-frontends with Angular: Module Federation vs Web Components vs route-level composition, and the `platform` injector's role.

Micro-frontends split a large app into independently-deployable pieces owned by different teams. With Angular there are three dominant integration strategies, each at a different point on the **isolation vs sharing** spectrum. **Module Federation** (Webpack/`@angular-architects/module-federation`, or the native esbuild equivalents emerging in recent versions) loads remote Angular code at runtime and **shares singleton dependencies** (Angular itself, RxJS) across remotes via a shared scope. This gives the best DX and smallest combined payload (one copy of Angular) but tightly couples version compatibility — a remote built against a different Angular major can break sharing, and version skew is the chronic operational pain.

**Web Components** (`@angular/elements`) compile an Angular component into a custom element with a Shadow-DOM boundary, so each micro-frontend is **framework-agnostic and strongly isolated** — a React or Vue shell can mount it, styles don't leak, and teams can use different framework versions. The cost is **duplication** (each element bundles its own Angular unless you go to lengths to share) and a thinner integration contract (attributes/events/properties only, no shared DI). **Route-level composition** (a shell app lazy-loading remote routes) is the simplest: the shell owns routing and each team owns a route subtree; it works well when everything is *one* Angular version and deployment cadence can tolerate the shell coordinating, but it's the least independently-deployable.

| Strategy | Isolation | Dependency sharing | Cross-framework | Main risk |
|---|---|---|---|---|
| Module Federation | Medium | Shared singletons | Hard | Version skew breaks sharing |
| `@angular/elements` (Web Components) | Strong (Shadow DOM) | None (duplicated) | Easy | Bundle duplication |
| Route-level composition | Low | Full (same app) | No | Not truly independent deploy |

The **`platform` injector** is the under-discussed enabler here. When multiple Angular applications are bootstrapped on the **same page** (common in micro-frontends, especially with Web Components or multiple `bootstrapApplication` calls), services provided in `'platform'` live in the injector **above each application's root**, so they are **shared across all the apps** on that page — useful for a single cross-app event bus, shared auth state, or a common notification service, without each app spinning up its own instance. Conversely, `'root'`-provided services are **per-application**, so two micro-frontends each get their *own* "root singleton" — a frequent source of confusion when teams expect a "singleton" to be shared and find duplicated state. The staff-level guidance: default to **Module Federation with carefully pinned shared singletons** for Angular-only orgs that can coordinate versions, reach for **`@angular/elements`** when you need framework-agnostic isolation at the cost of duplication, and use the **`platform` injector deliberately** for the small set of genuinely cross-app singletons — while keeping most state app-scoped to preserve independence.

#### Q68. [Practical] You're designing a reusable component library to be consumed across many Angular apps and versions. What internals-level decisions matter most, and why?

A library is a long-lived API contract, so the decisions that matter are the ones that are **expensive to change later** and the ones that interact with consumers' build/DI/CD machinery. First, **packaging and compilation**: ship in the **Angular Package Format (APF)** via `ng-packagr`, as **partial-Ivy compiled** code (not full AOT, not raw TS) so the consumer's compiler finalizes compilation against *their* Angular version — this is what makes a library forward-compatible across consumer versions, and it's a direct consequence of Ivy's locality. Mark the package **side-effect-free** (`"sideEffects": false`) and keep modules pure ESM so consumers can tree-shake unused components; a single side-effectful top-level statement defeats tree-shaking for the whole entry point.

Second, **API surface and DI ergonomics**: prefer **standalone components** (consumers just add them to `imports`, no `NgModule` ceremony) and expose configuration via a **`provideX()` function returning `EnvironmentProviders`** plus an `InjectionToken`, mirroring how first-party Angular features are configured — this is far more ergonomic and tree-shakable than a `forRoot()` module. Design **inputs/outputs as the contract** (favor signal `input()`/`output()` and `model()` for two-way), and avoid leaking internal services into the public API. Make services `providedIn: 'root'` only when a true app-wide singleton is intended; otherwise let the consumer scope them.

```typescript
// Tree-shakable, version-friendly library configuration (mirrors provideRouter style)
export const DATATABLE_CONFIG = new InjectionToken<DataTableConfig>('DATATABLE_CONFIG');

export function provideDataTable(config: Partial<DataTableConfig> = {}): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: DATATABLE_CONFIG, useValue: { pageSize: 20, ...config } },
  ]);
}

@Component({ selector: 'lib-table', /* standalone implied */ template: `...` })
export class TableComponent {
  rows = input.required<readonly unknown[]>();   // signal input = stable, typed contract
  rowSelect = output<unknown>();
}
```

Third, the **internals-level concerns that bite consumers**: (a) **change-detection neutrality** — make components `OnPush` and signal-driven so they behave correctly in both zoneful and **zoneless** consumer apps (a library that relies on Zone.js to notice mutations will silently break under a zoneless consumer); (b) **SSR/hydration safety** — never touch `window`/`document` at module top level or in constructors; guard with `isPlatformBrowser`/`afterNextRender`, since consumers may render you on the server; (c) **style encapsulation strategy** — default `Emulated` but expose theming via **CSS custom properties** (which pierce all encapsulation modes) rather than `::ng-deep` hooks that lock you to internal DOM structure; (d) **peer dependencies** — declare Angular/RxJS as `peerDependencies` with a permissive-but-honest range so the consumer dedupes a single copy (avoiding the "two Angulars" DI breakage). The unifying principle: a library author must assume the consumer's app could be any recent **version**, **zoneless or not**, **SSR or not**, and **build-optimized** — so every decision (APF/partial compilation, standalone + `provideX`, OnPush/signals, platform-safe init, peer deps) is about **not making assumptions that the consumer's environment can violate**. Getting these wrong doesn't fail at *your* build time — it fails mysteriously in *consumers'* apps, which is the worst kind of bug to support.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q69. [Practical] What does `ng serve` actually do, and how does it differ from `ng build` and a production deployment?

`ng serve` runs the **development server** — since v17 this is the esbuild/Vite-based dev server — which compiles your app **in memory** (nothing is written to `dist/`), serves it from `localhost:4200`, and watches files for **hot rebuilds** with live reload. It deliberately skips heavy production optimizations (full minification, aggressive tree-shaking, output hashing) to keep rebuilds fast, and it injects a websocket client for live-reload. It is a developer convenience, never something you ship.

`ng build` produces the **deployable artifact** in `dist/`. By default `ng build` now uses the production configuration: it runs **AOT compilation**, minifies and tree-shakes, applies **content-hashed filenames** (`main.<hash>.js`) for long-term cacheability, inlines critical CSS, and enforces any **budgets** declared in `angular.json`. The output is a set of static files (`index.html`, JS/CSS chunks, assets) plus, if SSR is enabled, a Node server bundle.

The deployment distinction people miss: the `dist/browser` folder is **static files** — they go behind a CDN or static host (S3+CloudFront, Nginx, Netlify). For a pure SPA you must configure the host to **rewrite unknown paths to `index.html`** (so deep links like `/orders/42` don't 404), because routing is client-side. With SSR you instead run the Node server (`dist/server/server.mjs`) behind a reverse proxy. Confusing the dev server with the build output — e.g., trying to "deploy `ng serve`" — is a classic beginner mistake; the dev server is single-process, unoptimized, and not hardened for production traffic.

```bash
ng build                              # prod build → dist/<app>/browser (+ /server for SSR)
ng build --configuration development  # unoptimized, source maps, no hashing
ng serve --port 4200 --open           # in-memory dev server, live reload — never deploy this
```

#### Q70. [Practical] How are `environment.ts` files and build configurations used to manage per-environment settings, and what should never go in them?

Angular's classic pattern is `environment.ts` (default/dev) and `environment.prod.ts`, swapped at build time via **`fileReplacements`** in the `configurations` block of `angular.json`. When you build with `--configuration production`, the bundler literally substitutes the prod file for the base one, so `import { environment } from './environments/environment'` resolves to the right values per build. This is a **compile-time** mechanism — the chosen file is baked into the bundle; there is no runtime switching.

```jsonc
// angular.json (excerpt)
"configurations": {
  "production": {
    "fileReplacements": [
      { "replace": "src/environments/environment.ts",
        "with": "src/environments/environment.prod.ts" }
    ],
    "optimization": true, "outputHashing": "all", "sourceMap": false
  }
}
```

The critical caveat: **anything in an `environment.ts` ends up in the client bundle and is fully visible** to any user (view-source / devtools). So API base URLs, feature-flag defaults, and public keys are fine; **secrets, private API keys, and credentials are not** — they must live server-side. A second pitfall is that compile-time replacement means you need a **separate build per environment** (dev/staging/prod), which complicates "build once, promote the same artifact" pipelines. Teams that want one artifact across environments instead fetch a **runtime config** (e.g., a small `/assets/config.json` or an `APP_INITIALIZER` hitting `/api/config`) so the same `dist` runs everywhere and only the fetched config differs. The modern Angular CLI also leans toward fewer hardcoded environment files, encouraging runtime configuration for values that genuinely vary at deploy time.

#### Q71. [Practical] How do you write a basic unit test for a standalone component with `TestBed`, and what does `ComponentFixture` give you?

Angular's testing stack is **Jasmine + Karma** historically, with **Jest** and the experimental **Vitest**-based runner gaining ground in v17+. `TestBed` is the testing module compiler: it creates a throwaway Angular environment where you configure providers and imports, then create components — it's the test-time analog of `bootstrapApplication`. For a standalone component you put the component itself in `imports` (not `declarations`), and override or stub its real dependencies with test providers.

```typescript
describe('GreetingComponent', () => {
  let fixture: ComponentFixture<GreetingComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GreetingComponent],                       // standalone → import it
      providers: [{ provide: UserService, useValue: { name: () => 'Ada' } }],
    }).compileComponents();
    fixture = TestBed.createComponent(GreetingComponent);
  });

  it('renders the user name', () => {
    fixture.componentRef.setInput('salutation', 'Hello'); // set a signal/decorator input
    fixture.detectChanges();                              // run change detection
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Hello, Ada');
  });
});
```

`ComponentFixture` is the harness around the created component: `fixture.componentInstance` accesses the class, `fixture.nativeElement`/`fixture.debugElement` access the rendered DOM, `fixture.detectChanges()` triggers a change-detection pass (you must call it manually in tests — there's no Zone auto-tick by default in this context), and `fixture.componentRef.setInput()` sets inputs the supported way (don't poke signal inputs directly). For async behavior use `fakeAsync` + `tick()` to control virtual time, or `await fixture.whenStable()`. The key mental model an interviewer wants: tests should drive the component through its **public surface** (inputs, rendered output, emitted outputs) rather than reaching into private internals, and `detectChanges()` is the explicit "render now" you control — which also makes tests deterministic.

#### Q72. [Theory] What is the difference between `HttpClient`'s observable-of-one-value model and a typical promise-based fetch, and why does Angular use observables here?

`HttpClient` returns a **cold `Observable` that emits exactly once and completes**, rather than a `Promise`. Functionally a single-value observable resembles a promise, but the observable model buys Angular several things a promise can't: **cancellation** (unsubscribing aborts the in-flight XHR/fetch — essential for `switchMap`-based search where stale requests must die), **composition with RxJS operators** (`retry`, `timeout`, `debounceTime`, `mergeMap` to chain calls), **lazy execution** (the request fires only on subscribe, so you can build a request pipeline without triggering it), and **interceptor chaining** that is itself observable-based.

The cold/once semantics have a concrete consequence: every `subscribe()` (or every `| async`) on the *same* request observable triggers a **new HTTP call**. People are surprised when binding `data$ = http.get(...)` and using `| async` in three places fires three requests; the fix is `shareReplay({ bufferSize: 1, refCount: true })` or assigning the *result* rather than the request. Conversely, a finite single-value observable **completes on its own**, so you don't strictly need to unsubscribe an `HttpClient.get()` to avoid a leak — but adding `takeUntilDestroyed()` is still better because it also **cancels** the request if the component is destroyed mid-flight.

```typescript
const sub = this.http.get<User>('/api/me').subscribe(u => this.user = u);
// Later, if the component dies before the response: sub.unsubscribe() ABORTS the request.
```

The design rationale to articulate: Angular standardized on observables for HTTP so that the *entire* async surface of the framework (HTTP, forms `valueChanges`, router events, events) shares one composable abstraction with first-class cancellation — something the promise model, which is uncancelable and eager, fundamentally cannot offer. In v18+ you can bridge to signals with `toSignal(http.get(...))` when you want HTTP state read synchronously in a template.

### 🟡 Intermediate — extended

#### Q73. [Practical] Walk through diagnosing a production "blank white screen" (app boots in dev but renders nothing in prod). What are the usual culprits?

A blank screen in prod that works in dev almost always means the app **threw during bootstrap or first render**, and because there's no obvious error UI, you see white. The disciplined first move is to **open the browser console and Network tab on the prod build** — not to guess. The console usually shows the real cause; the Network tab reveals whether the JS chunks even loaded.

The recurring culprits, roughly in order: (1) **Wrong `base href` / asset path** — the app is served from a subpath (`/app/`) but built with `--base-href /`, so `main.<hash>.js` 404s and nothing executes; fix with `ng build --base-href /app/` and correct deploy paths. (2) **Server not rewriting to `index.html`** — a deep-link refresh 404s because the host isn't configured for SPA fallback. (3) **A runtime error that only surfaces under AOT/production optimization** — e.g., code that relied on dev-only behavior, a provider missing in the prod path, or a `@defer`/lazy chunk failing to load. (4) **Hydration mismatch under SSR** that escalates to a thrown error. (5) **Environment misconfig** — a prod `environment.ts` pointing at an unreachable API, causing an unhandled error in an `APP_INITIALIZER` that blocks bootstrap.

```bash
# Reproduce prod locally instead of debugging on the server blind:
ng build --configuration production
npx http-server dist/<app>/browser -p 8080   # serve the real artifact
# Then open devtools: console errors + failed network requests pinpoint it fast.
```

The systematic approach beats guessing: reproduce the **production build locally** (`ng build` then serve `dist`), watch the console, and confirm chunk loading. A global `ErrorHandler` that logs to a monitoring service (Sentry/Datadog) is the production-grade safety net — it captures the bootstrap exception that the white screen is hiding so you're not flying blind on real incidents. The meta-lesson: "works in dev" and "works in prod" diverge precisely because the dev server skips AOT-strictness, optimization, base-href, and SPA-fallback concerns — so always validate against the actual built artifact.

#### Q74. [Practical] How do you configure and use Angular DevTools and source maps to profile a slow change-detection problem?

**Angular DevTools** (the official browser extension) is the primary instrument. Its **Profiler** records change-detection cycles and shows, per CD pass, **which components were checked and how long each took**, as a flame/bar chart. You record an interaction (typing, scrolling), then look for components that (a) are checked far more often than expected, or (b) take disproportionately long per check. The **Component Explorer** shows the live component tree, each component's current inputs/state, and — critically — its **change-detection strategy**, so you can spot a large `Default`-CD subtree that should be `OnPush`.

The workflow for a real slowdown: open the Profiler, record while reproducing the jank, then read the chart. Repeated full-tree checks on every keystroke point to a missing `OnPush`/signals or Zone-triggered storms; one component dominating the time points to expensive template expressions (method calls, getters doing work) or a missing `track` recreating DOM. You cross-check expensive template work by noting functions invoked every cycle — the fix is moving them to `computed()` or memoized pure pipes. For production builds you typically disable source maps, but you can build with `--source-map` (or a `staging` config) to get **readable component names and stack traces** in the profiler instead of minified gibberish.

```bash
ng build --configuration production --source-map  # readable names for prod-like profiling
```

Complementary tools: the **browser's own Performance panel** shows long tasks and where scripting time goes (useful to confirm CD vs paint vs network), and `enableProfiling`/`ng.profiler.timeChangeDetection()` (exposed via `window.ng` in dev) runs a quick CD timing benchmark. The interview point is **measure before optimizing**: the DevTools Profiler tells you *which* component and *how often*, turning "the app feels slow" into "this `OnPush`-less grid re-checks 4,000 cells every keystroke" — a specific, fixable diagnosis rather than scattershot tuning.

#### Q75. [Theory] What changed when Angular moved its build pipeline from Webpack to esbuild/Vite (the application builder), and what are the practical implications?

Through v16 the Angular CLI used a **Webpack**-based builder (`@angular-devkit/build-angular:browser`). Starting v16 (developer preview) and becoming the **default in v17**, Angular introduced the **application builder** (`@angular/build:application`) powered by **esbuild** for production bundling and **Vite** for the dev server. esbuild is a Go-based bundler that is dramatically faster than Webpack; Vite provides native-ESM-based dev serving with on-demand transformation, so the dev server starts almost instantly and rebuilds are near-real-time.

The practical implications are mostly speed and a few behavioral shifts. **Cold builds and incremental rebuilds are several times faster**, and `ng serve` startup drops from many seconds to near-instant because Vite doesn't pre-bundle the whole app. The application builder also unified the browser and SSR/server build into one builder (hence "application"), improving SSR ergonomics, and it produces ESM output that tree-shakes well. The behavioral changes to watch: **custom Webpack configs no longer apply** — projects that used `@angular-builders/custom-webpack` or `ngx-build-plus` to inject Webpack plugins must migrate, since esbuild has a different (and more limited) plugin model; some **polyfill and CommonJS-interop** edge cases behave differently (esbuild warns on CommonJS dependencies that defeat tree-shaking); and Module Federation tooling had to be re-implemented for esbuild.

```jsonc
// angular.json — the modern application builder
"architect": {
  "build": { "builder": "@angular/build:application",
    "options": { "browser": "src/main.ts", "server": "src/main.server.ts", "ssr": { "entry": "src/server.ts" } } }
}
```

The interview-level takeaway: this was an **infrastructure modernization**, not a feature change — the framework API is unchanged, but build/dev-server performance improved by a large factor, which matters enormously for developer feedback loops on big apps. The migration cost falls on teams with **custom Webpack pipelines**; for everyone else `ng update` flips the builder and things just get faster. Knowing this also frames why advice to "eject Webpack and add a plugin" is now usually the wrong answer.

#### Q76. [Practical] How do you implement internationalization (i18n) in Angular, and what is the trade-off between built-in `@angular/localize` and runtime libraries like ngx-translate?

Angular ships a **build-time** i18n system via `@angular/localize`. You mark translatable text with the `i18n` attribute (and `$localize` tagged template in TS), run `ng extract-i18n` to produce a translation source file (XLIFF/XLB/ARB), translators fill in each locale, and you **build one bundle per locale** (`localize` in `angular.json`). The text is **baked into each locale's bundle at compile time**, so there's zero runtime translation cost and the output is optimal — but you ship N separate builds and switching language means loading a different bundle (typically served under `/en/`, `/fr/` paths).

```html
<h1 i18n="@@homeTitle">Welcome</h1>            <!-- extracted with a stable id -->
<p i18n>{count, plural, =0 {No items} one {1 item} other {{{count}} items}}</p>  <!-- ICU -->
```

The trade-off versus a **runtime** library like **ngx-translate** (or Transloco): runtime libraries load JSON translation files at runtime and switch language **without rebuilding or reloading** — one bundle, instant in-app language switching, and translations editable without a rebuild. The cost is a **runtime translation pipeline** (a pipe/directive doing lookups on every render, a small perf and bundle overhead) and weaker compile-time guarantees. The decision rubric: choose **built-in `@angular/localize`** when locales are known at build time, SEO per-locale URLs matter, and you want maximal runtime performance (it's also the first-party, future-aligned path and supports SSR per-locale well); choose a **runtime library** when users must switch language live in-session, translations change frequently without redeploys, or you need a single artifact for all locales.

A few internals worth knowing for the interview: `@angular/localize` supports **ICU expressions** for plurals/genders, and stable **custom message ids** (`@@id`) prevent translations breaking when source text is reworded. Built-in i18n historically required a build per locale (no live switch); the team has explored runtime-friendlier i18n, but as of v17–v21 the canonical first-party approach remains compile-time per-locale builds, with runtime libraries filling the "switch language without reload" niche.

#### Q77. [Theory] What is `NgOptimizedImage`, what problems does it solve, and what does it enforce?

`NgOptimizedImage` (the `ngSrc` directive, stable since v15) is Angular's answer to the fact that **images are usually the largest LCP element** and the most common Core Web Vitals problem. You swap `src` for `ngSrc`, and the directive applies a bundle of performance best practices automatically: it sets correct **`loading` (lazy by default, eager for priority)**, generates a **`srcset`** for responsive resolutions, enforces **width/height** to reserve layout space (preventing Cumulative Layout Shift), adds **`fetchpriority`** hints, and can emit a **preload `<link>`** for the priority (above-the-fold/LCP) image.

```html
<img ngSrc="/assets/hero.jpg" width="1200" height="600" priority />   <!-- LCP image: eager + preload -->
<img ngSrc="/assets/thumb.jpg" width="160" height="160" />            <!-- lazy by default -->
```

What it **enforces** (and why it's strict): the directive **requires `width` and `height`** (or `fill`) — and will throw if missing — because an image without intrinsic dimensions causes layout shift; this is opinionated on purpose. It **warns** if a `priority` image isn't preloaded, if an image is oversized for its rendered box (wasted bytes), or if you use a raw `src` alongside it. With a configured **image loader** (`provideImgixLoader`, Cloudinary, ImageKit, or a custom loader), it rewrites URLs to a CDN that resizes/optimizes on the fly, so `srcset` entries point at appropriately-sized variants.

The trade-off and interview nuance: the strictness (mandatory dimensions, one `priority` image expected per viewport for the LCP) is friction that pays off in measurable LCP/CLS improvements — it encodes the rules teams otherwise forget. The main gotcha is **`fill` mode** for images whose dimensions you don't know (CSS-sized containers): you set `fill` and let CSS `object-fit` control sizing, trading the explicit-dimensions guarantee for flexibility. Used correctly with a CDN loader and a single `priority` LCP image, `NgOptimizedImage` is one of the cheapest high-impact performance wins available, which is why it's worth knowing as a default rather than a niche tool.

#### Q78. [Practical] How do you correctly clean up resources beyond subscriptions — timers, event listeners, web sockets — in a modern Angular component?

Subscriptions are the famous leak, but components also accumulate **timers (`setInterval`), manual `addEventListener` handlers, IntersectionObservers/ResizeObservers, WebSocket connections, and third-party library instances** that all hold references (and keep doing work) after the component is destroyed. The modern, uniform tool is **`DestroyRef`** + `inject(DestroyRef).onDestroy(cleanupFn)`, which registers teardown without needing to implement `ngOnDestroy` and works in any injection context — including helper functions and field initializers.

```typescript
export class LiveTickerComponent {
  private destroyRef = inject(DestroyRef);
  price = signal(0);

  constructor() {
    const id = setInterval(() => this.poll(), 1000);
    const ws = new WebSocket('wss://example/feed');
    const onResize = () => this.recompute();
    window.addEventListener('resize', onResize);

    this.destroyRef.onDestroy(() => {     // one place, guaranteed cleanup
      clearInterval(id);
      ws.close();
      window.removeEventListener('resize', onResize);
    });
  }
}
```

The principles an interviewer is checking: (1) **every imperative resource you create, you must release** — Angular only manages what flows through its own constructs (template subscriptions via `async`, `effect`/signal cleanup, view destruction). (2) Prefer Angular-native constructs that **self-clean**: `effect()` and `afterRenderEffect()` are torn down automatically; `takeUntilDestroyed()` ties an observable to the component lifetime; a signal-driven approach often removes the manual timer/listener entirely. (3) For things with no Angular wrapper (raw WebSocket, a charting lib), **`DestroyRef.onDestroy`** is the clean hook, superior to scattering cleanup across `ngOnDestroy` because it co-locates setup and teardown.

The subtle one is **SSR/zoneless interplay**: `setInterval` started during SSR would run on the server (guard browser-only resources with `afterNextRender`/`isPlatformBrowser`), and a timer that mutates a plain field under zoneless won't trigger CD — route the update through a signal. The unifying rule: treat the component as owning a set of resources with a lifecycle, and bind each resource's teardown to `DestroyRef` at the point of creation so nothing is forgotten.

### 🟠 Advanced — extended

#### Q79. [Practical] Design a global, production-grade error-handling strategy: `ErrorHandler`, HTTP error interceptor, and what to surface to users vs log.

A robust strategy separates **three layers of errors** and handles each at the right seam. (1) **Uncaught runtime/template errors** are caught by a custom **`ErrorHandler`** — a single class you provide that overrides `handleError`; this is your last line of defense and the right place to forward to a monitoring service (Sentry/Datadog) with context (route, user id, release version). (2) **HTTP errors** are best handled in an **HTTP error interceptor** so every request gets consistent treatment — distinguishing transient/retryable (5xx, network) from client (4xx) and auth (401/403) errors. (3) **Domain/expected errors** (validation, "not found") should be handled locally where the component can render a meaningful state, not bubbled to the global handler.

```typescript
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  private monitor = inject(MonitoringService);
  private zone = inject(NgZone);
  handleError(error: unknown): void {
    this.monitor.capture(error);                       // log with context
    this.zone.run(() => this.showToast('Something went wrong.'));  // generic user message
    console.error(error);                              // keep dev visibility
  }
}

export const httpErrorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    retry({ count: 2, delay: (e, n) => e instanceof HttpErrorResponse && e.status >= 500
      ? timer(2 ** n * 300) : throwError(() => e) }),   // retry only transient
    catchError((e: HttpErrorResponse) => {
      if (e.status === 0) inject(ToastService).error('Network unavailable.');
      else if (e.status === 403) inject(Router).navigate(['/forbidden']);
      return throwError(() => e);                        // rethrow so callers can still react
    }),
  );

providers: [{ provide: ErrorHandler, useClass: GlobalErrorHandler },
            provideHttpClient(withInterceptors([httpErrorInterceptor]))];
```

The judgment calls interviewers probe: **what to surface vs log.** Users should see **generic, non-technical messages** ("We couldn't load your orders — try again") plus an action (retry); they should **never** see stack traces, server messages, or anything that leaks internals (security and UX both). The full error — stack, request id, user context, release — goes to **logging/monitoring**, ideally with a correlation id so frontend and backend logs join up. Other production concerns: **don't retry non-idempotent requests** blindly; **rethrow** after handling so component-level logic can still react (don't swallow); attach a **release/version** to logs so you can correlate spikes with deploys; and run the toast inside `NgZone.run()` (or via a signal) so UI updates from the `ErrorHandler` actually render. The anti-pattern is a single `try/catch`-everywhere sprinkle with `alert()` — inconsistent, leaky, and unobservable.

#### Q80. [Theory] What are `APP_INITIALIZER` / `provideAppInitializer` and `ENVIRONMENT_INITIALIZER` for, and what are the risks of putting too much in them?

`APP_INITIALIZER` (and its modern functional form `provideAppInitializer`) registers work that Angular **runs and awaits before the application bootstraps and renders the first view**. If the factory returns a `Promise` or `Observable`, Angular blocks until it settles. This is the correct place for **must-have-before-render** setup: fetching runtime configuration (so the same artifact runs in any environment), loading feature flags, initializing auth/session, or warming a critical cache. Because it runs in the root injection context, it can `inject()` services freely.

```typescript
bootstrapApplication(AppComponent, {
  providers: [
    provideAppInitializer(() => {
      const config = inject(ConfigService);
      return firstValueFrom(config.load());   // app waits for /api/config before first render
    }),
  ],
});
```

`ENVIRONMENT_INITIALIZER` is different: it's a multi-provider that runs a function **when an environment injector is created** (root, and each lazy-loaded route injector), used to perform setup tied to injector creation rather than gating bootstrap. Libraries use it to register themselves once their providers are present.

The risk an interviewer wants you to name is **bootstrap latency**: every `APP_INITIALIZER` is on the **critical path to first paint**, so each network call you add there delays *everything* the user sees — a slow `/config` endpoint turns into a slow white screen with no UI to mask it. The discipline: keep initializers to the **minimum truly required before render**, parallelize independent calls (`forkJoin`/`Promise.all`) rather than awaiting them serially, set **timeouts/fallbacks** so a flaky config endpoint degrades gracefully instead of hanging bootstrap forever, and push everything non-essential to **after** render (lazy load, `afterNextRender`, or just fetch reactively with a skeleton). A second risk is **error handling**: an unhandled rejection in an initializer aborts bootstrap (the blank-screen scenario), so initializers must catch and decide whether to proceed with defaults. The rule of thumb: `APP_INITIALIZER` is for the small set of things the app genuinely cannot render correctly without — anything else belongs later.

#### Q81. [Practical] Your app's main bundle keeps growing despite lazy routes. How do you find what's bloating it, and what are common hidden causes?

First, **measure with a real budget and an analyzer**, not intuition. Build with stats and feed them to `source-map-explorer` or `esbuild`'s metafile visualizer to see, byte-by-byte, what's in the **initial** chunk. Set a CI **bundle budget** in `angular.json` so regressions fail the build rather than being discovered months later.

```bash
ng build --configuration production --stats-json
npx source-map-explorer dist/<app>/browser/main.*.js   # what's actually in the main chunk
```

```jsonc
"budgets": [
  { "type": "initial", "maximumWarning": "500kb", "maximumError": "1mb" },
  { "type": "anyComponentStyle", "maximumWarning": "4kb" }
]
```

The common **hidden** causes (the ones that survive having lazy routes): (1) **A heavy library imported in an eagerly-loaded component or a shared service** — e.g., `moment`, a full charting/PDF lib, or `lodash` imported as the whole package (`import _ from 'lodash'`) instead of `import debounce from 'lodash-es/debounce'`; the eager import pins it to the main chunk regardless of lazy routes. (2) **Barrel files** (`index.ts` re-exporting everything) that defeat tree-shaking by creating import paths that pull in unrelated code. (3) **A component used both eagerly and inside a `@defer` block** — exclusive-use is required for code to land in the defer chunk, so any eager reference drags it back to main. (4) **Non-tree-shakable dependencies** shipped as CommonJS or with side effects (esbuild warns about CJS); a single side-effectful dependency can prevent elimination. (5) **Eagerly imported polyfills or locale data** (all `@angular/common/locales` instead of the ones you use). (6) **Large inline assets** — fonts/SVGs base64-inlined past the asset budget.

The fix sequence: identify the biggest contributors from the analyzer, then **move heavy features behind `@defer` or lazy routes**, **replace or slim heavy deps** (native `Intl` over date libs, modular imports), **fix barrel/wildcard imports**, and verify nothing eager references deferred-only components. The strategic point: lazy *routes* only help if the heavy code is **reachable solely** through those routes — bundle bloat that resists route-splitting almost always traces to an eager import path you didn't realize existed, which the analyzer makes visible immediately.

#### Q82. [Theory] Explain how `Renderer2` works and why direct `nativeElement` manipulation is discouraged, especially with SSR and security.

`Renderer2` is Angular's **abstraction over DOM manipulation** — `createElement`, `setAttribute`, `addClass`, `listen`, `setStyle`, etc. — that routes all DOM operations through the active **rendering backend** rather than touching the browser DOM directly. The reason this indirection exists is that Angular doesn't always render to a live browser DOM: under **SSR** the renderer targets a server-side DOM emulation, and historically Web Worker / NativeScript renderers targeted non-DOM environments. Code that grabs `elementRef.nativeElement` and calls `.innerHTML =` or `.setAttribute()` directly assumes a real browser DOM, which **breaks on the server** (no `document`) and bypasses framework bookkeeping.

```typescript
export class HighlightDirective {
  private el = inject(ElementRef);
  private r = inject(Renderer2);
  constructor() {
    // Portable + SSR-safe + records nothing dangerous:
    this.r.addClass(this.el.nativeElement, 'highlight');
    this.r.setStyle(this.el.nativeElement, 'outline', '2px solid gold');
    // AVOID: this.el.nativeElement.innerHTML = userInput;  // XSS + SSR-unsafe
  }
}
```

There are three distinct reasons to prefer `Renderer2` (or, better, declarative bindings) over raw `nativeElement`. **Portability/SSR:** `Renderer2` operations work whether the target is the browser or the server DOM, so the same code renders correctly on both — direct `nativeElement` access during SSR throws or no-ops, and is a frequent **hydration-mismatch** source. **Security:** writing `nativeElement.innerHTML = userInput` **bypasses Angular's sanitizer**, reintroducing the XSS vulnerability that property/interpolation bindings and `[innerHTML]` (which sanitizes) protect against — `Renderer2` doesn't auto-sanitize either, but the discipline of going through bindings/sanitizer is the safe path. **Correctness with encapsulation:** `Renderer2` is aware of view-encapsulation attributes, so classes/styles it adds respect scoping.

The nuance for senior candidates: the truly preferred approach is usually **neither** — use **template bindings** (`[class.x]`, `[style.y]`, `[attr.z]`, event bindings, `[innerHTML]` with sanitization) which are declarative, sanitized, and SSR-safe by construction. `Renderer2` is the right tool when you genuinely need imperative DOM work in a **directive** (the canonical case) and must stay portable. Reach for raw `nativeElement` only for read-only measurements or integrating a library, and then guard with `afterNextRender`/`isPlatformBrowser`. The hierarchy: **bindings > `Renderer2` > raw `nativeElement`**, with the last being a smell that should make you check SSR and XSS implications.

#### Q83. [Practical] How do you set up route-based code splitting plus a smart preloading strategy so navigation feels instant without bloating first load?

The two levers are **lazy loading** (split route code into separate chunks loaded on navigation) and **preloading** (fetch likely-next chunks *after* the initial render, during idle time, so the chunk is already cached when the user navigates). Lazy loading alone makes the first navigation to a route incur a network fetch (a perceptible delay); preloading hides that delay by warming chunks in the background without adding to the initial bundle.

Out of the box you can `withPreloading(PreloadAllModules)` to preload **every** lazy route after bootstrap — simple, and fine for small/medium apps, but it can waste bandwidth on routes the user never visits. The production-grade approach is a **custom `PreloadingStrategy`** that preloads selectively, e.g., only routes flagged `data: { preload: true }`, or based on heuristics (only on fast connections via `navigator.connection`, only routes linked from the current page).

```typescript
@Injectable({ providedIn: 'root' })
export class SelectivePreload implements PreloadingStrategy {
  preload(route: Route, load: () => Observable<unknown>): Observable<unknown> {
    const fast = (navigator as any).connection?.effectiveType !== 'slow-2g';
    return route.data?.['preload'] && fast ? load() : of(null);
  }
}

provideRouter(routes, withPreloading(SelectivePreload));
// route: { path: 'reports', data: { preload: true }, loadChildren: () => import('./reports/routes')... }
```

For even finer control, Angular supports **`@defer (prefetch on …)`** at the component level and **link-hover prefetch** patterns — start fetching a route's chunk when the user *hovers* a nav link (`on hover`/`on interaction`), so by the time they click, it's loaded. The trade-off framing: `PreloadAllModules` trades bandwidth for simplicity and near-instant navigation everywhere; selective/connection-aware preloading trades a little complexity for not punishing mobile/metered users. The architecture rule: keep the **initial bundle minimal** (only the shell + first route), then **preload likely-next routes opportunistically** — never eagerly import a feature just to avoid the navigation delay, because that defeats the split. Measure with the Network tab that initial load stays small and that subsequent navigations hit cached chunks (no spinner) to confirm the strategy is working.

#### Q84. [Theory] What is the difference between `ViewContainerRef`, `TemplateRef`, and `ComponentRef`, and how do you render components/templates dynamically in Ivy?

These three are the primitives behind **dynamic, imperative rendering** — creating views at runtime rather than declaring them statically in a template. **`TemplateRef`** is a handle to a block of template (an `<ng-template>` or the implicit template of a structural directive) that has been parsed but **not yet instantiated** — it's a blueprint. **`ViewContainerRef`** is an **insertion point** in the DOM — a location where you can create and attach views (embedded views from templates, or host views from components). **`ComponentRef`** is the handle you get back after dynamically creating a component: it exposes the `instance`, the `location`, `setInput()`, and `destroy()`.

```typescript
export class DynamicHostComponent {
  private vcr = inject(ViewContainerRef);
  private tmpl = viewChild.required<TemplateRef<unknown>>('row');

  renderTemplate(ctx: { $implicit: string }) {
    this.vcr.createEmbeddedView(this.tmpl(), ctx);     // instantiate a TemplateRef
  }

  renderComponent() {
    const ref: ComponentRef<WidgetComponent> = this.vcr.createComponent(WidgetComponent); // Ivy: pass the class
    ref.setInput('title', 'Hello');                    // set inputs the supported way
    ref.instance.ready.subscribe(/* ... */);
    // later: ref.destroy();
  }
}
```

The Ivy-era change worth highlighting: pre-Ivy you needed a **`ComponentFactoryResolver`** and `entryComponents` to dynamically create components — clunky boilerplate driven by the global-compilation model. With **Ivy's locality**, `ViewContainerRef.createComponent(ComponentClass)` takes the **class directly**, `ComponentFactoryResolver` is deprecated, and `entryComponents` is gone — a direct consequence of components carrying their own `ɵcmp` definition. This is why modern dynamic rendering is so much simpler.

When to use these: dynamic component creation powers **modals/dialogs, dynamically-typed dashboards/CMS widgets, and rendering components from configuration** where the component type is known only at runtime. `TemplateRef` + `ViewContainerRef` powers **custom structural directives** (you receive both via DI and call `createEmbeddedView`) and patterns like `ngTemplateOutlet`. The mental model: `TemplateRef` = "what to render," `ViewContainerRef` = "where to render it," `ComponentRef` = "the live thing you rendered, including how to update inputs and tear it down." Forgetting to `destroy()` a dynamically-created `ComponentRef` (or clear the `ViewContainerRef`) is a real leak, since Angular won't auto-clean views you created imperatively.

#### Q85. [Practical] How do you handle authentication state and route protection end-to-end in a standalone app, including token refresh and SSR considerations?

End-to-end auth has four cooperating pieces. (1) An **`AuthService`** holds session state — ideally as **signals** (`isLoggedIn = computed(() => !!this.token())`) so guards, the UI, and interceptors all read a single reactive source. (2) **Functional route guards** (`CanActivateFn`/`CanMatchFn`) gate navigation; use `CanMatch` on lazy routes so an unauthorized user never even downloads the protected chunk, and return a `UrlTree` to redirect declaratively with a `returnUrl`. (3) An **HTTP interceptor** attaches the bearer token and handles **401 → refresh → replay**, sharing a single in-flight refresh to avoid a stampede. (4) **App init** restores the session (from a cookie/refresh token) via `APP_INITIALIZER` before the first guard runs, so a page refresh doesn't bounce a logged-in user to login.

```typescript
@Injectable({ providedIn: 'root' })
export class AuthService {
  private platformId = inject(PLATFORM_ID);
  token = signal<string | null>(null);
  isLoggedIn = computed(() => !!this.token());
  private refresh$?: Observable<string>;

  refresh(): Observable<string> {                 // share one in-flight refresh (no stampede)
    return (this.refresh$ ??= this.http.post<{ t: string }>('/api/refresh', {}).pipe(
      map(r => r.t), tap(t => this.token.set(t)),
      finalize(() => (this.refresh$ = undefined)), shareReplay(1),
    ));
  }
}

export const authMatch: CanMatchFn = (r, segs) => {
  const auth = inject(AuthService), router = inject(Router);
  return auth.isLoggedIn() ? true
    : router.createUrlTree(['/login'], { queryParams: { returnUrl: '/' + segs.join('/') } });
};
```

The **SSR considerations** are the part that distinguishes a senior answer. Tokens stored in **`localStorage` are unavailable on the server** (no `window`), so guards that read `localStorage` during SSR see "logged out" and may render/redirect incorrectly, causing **hydration mismatches** when the client then sees "logged in." The robust pattern is **httpOnly, SameSite cookies**: the cookie is sent with the SSR request automatically, so the server can render the authenticated view, and the client hydrates to the same state — no mismatch, and the token is not XSS-exfiltratable. If you must use in-memory/`localStorage` tokens, guard browser-only access with `isPlatformBrowser`, and avoid auth-dependent rendering differences between server and client. Security points to articulate regardless of SSR: prefer **short-lived access tokens + httpOnly refresh cookie**, treat client guards as **UX only** (the server must enforce authorization on every API call), set **CSP** and XSRF protection, and never trust the client's claim of identity. The unifying principle: make auth state a single reactive source, gate lazily with `CanMatch`, refresh transparently and non-stampeding, and design the **token storage** around your SSR and XSS threat model rather than defaulting to `localStorage`.

### 🔴 Expert — extended

#### Q86. [Theory] Deeply compare `OnPush` + immutability against signal-based components for change detection. Are signal components effectively "OnPush", and where do they differ?

Both models share the same goal — **avoid re-checking components whose inputs/state didn't change** — but reach it differently. `OnPush` operates at **component granularity**: a component is checked only when an input *reference* changes, an event fires within it, an `async` pipe emits, or it's `markForCheck`'d. The discipline it demands is **immutability** — you must replace objects/arrays so the reference comparison detects change; a mutation that preserves the reference is invisible and the view goes stale. So `OnPush` correctness depends on the *developer* maintaining immutable update patterns, and even when a component *is* checked, Angular re-evaluates **all of that component's bindings**.

Signal-based components push the granularity finer. Reading a signal in a template **registers that specific binding as a dependent of that signal**; when the signal changes, Angular knows precisely which component(s) — and conceptually which bindings — depend on it, and marks them dirty. This is why signal components are often described as "**`OnPush` done right**": a signal component effectively behaves as `OnPush` (it isn't checked unless something it depends on changes), but the dirtiness is driven by **actual signal reads** rather than by reference-equality on inputs, removing the immutability footgun — `signal.update(arr => [...arr, x])` or even a `.set` of a new value correctly and automatically marks dependents, and you can't "forget to markForCheck."

```typescript
// OnPush: correctness hinges on immutable replacement + the right CD strategy set.
@Component({ changeDetection: ChangeDetectionStrategy.OnPush, template: `{{ user.name }}` })
class A { @Input() user!: User; }   // mutating user.name in place → NOT detected

// Signal: dependency is the signal read itself; change is tracked automatically.
@Component({ template: `{{ user().name }}` })
class B { user = input.required<User>(); }  // setting a new user() value → B marked dirty precisely
```

Where they still differ today is worth being precise about: as of v17–v21, signal change detection marks the **component** dirty (then the component's view is checked), so it isn't yet *per-binding* surgical DOM updating in the way SolidJS is — that finer-grained "update only the one text node" capability is on the roadmap as signal integration deepens and zoneless matures. Also, **inputs**: a signal `input()` integrates with this dependency tracking, whereas a classic `@Input` under `OnPush` still relies on reference change. And a signal component **still benefits from `OnPush`** being set (and in zoneless the distinction blurs further). The expert summary: signal components give you `OnPush`-equivalent skipping **without** the immutability/`markForCheck` discipline and with **finer dependency knowledge**, which is why the framework is steering everyone toward signals as the default — but the current implementation marks components dirty rather than performing per-node DOM patching, so the "fully fine-grained DOM" promise is partially realized and still evolving.

#### Q87. [Theory] What actually happens during AOT compilation and template type-checking (including `strictTemplates`), and what classes of bugs does it catch at build time?

**AOT (Ahead-Of-Time) compilation** transforms your templates and decorators into JavaScript **at build time**, so the browser receives compiled render instructions rather than template strings plus a runtime template compiler. Concretely, the Angular compiler parses each template, resolves the directives/pipes/components in scope (from `imports`), and emits the `ɵcmp` definition with a compiled `template` function (the `elementStart`/`property`/`textInterpolate` instruction stream). Because compilation happens before shipping, there's **no template parser in the bundle** (smaller, faster startup), templates are **type-checked against the component class**, and many errors surface as **build failures** instead of runtime exceptions.

**Template type-checking** is the part that catches the most bugs. With `fullTemplateTypeCheck` and especially **`strictTemplates`** (in `angular.compilerOptions` in `tsconfig`), the compiler type-checks template expressions as if they were TypeScript: it verifies that a property you interpolate **exists on the component**, that an `@Input` binding's **type matches** the target's declared input type, that an event handler's `$event` is the **correct type**, that pipes receive **correctly-typed arguments**, and that template reference variables and `@for`/`@if` aliases are typed. It catches: typos in property/method names, binding a `string` to a `number` input, calling a method with wrong argument types, using a pipe that isn't imported, null/undefined access in expressions (with strict null checks), and referencing a directive input that doesn't exist.

```jsonc
// tsconfig.json
"angularCompilerOptions": {
  "strictTemplates": true,            // full template type-checking
  "strictInjectionParameters": true,
  "strictInputAccessModifiers": true  // respects private/readonly on inputs
}
```

```html
<!-- With strictTemplates these are BUILD errors, not runtime surprises: -->
{{ usre.name }}                  <!-- 'usre' does not exist on component -->
<app-x [count]="'five'" />       <!-- string not assignable to input count: number -->
{{ value | currency:true }}      <!-- wrong arg type to currency pipe -->
```

The trade-off and senior nuance: `strictTemplates` can be **noisy to enable on a legacy codebase** (it surfaces years of latent type sloppiness at once), so teams often enable it incrementally. It also occasionally requires **explicit typing help** — e.g., `$any()` as a deliberate escape hatch, or typing `@for` loop variables — and interacts with strict null checks so optional chaining in templates becomes necessary. But the payoff is large: it moves an entire class of "renders wrong / throws at runtime in some edge state" bugs to **compile time**, where they're cheap to fix, and it makes refactors safe (rename a property and every template referencing it fails the build). The interview-level point: AOT isn't just a performance feature — combined with `strictTemplates` it turns templates into **type-checked code**, which is one of Angular's strongest correctness guarantees versus runtime-interpreted-template frameworks.

#### Q88. [Practical] You're rolling out zoneless to a large existing app and the QA suite surfaces views that no longer update. Give a systematic methodology to find and fix every missed update.

Under zoneless, the only things that schedule change detection are **signal changes read in a template, template/host event listeners, the `AsyncPipe`, and explicit `markForCheck()`** (plus view attach/detach). A view "no longer updating" means some state mutation happened through **none** of those channels — previously Zone.js noticed the async task and ran CD anyway. So the methodology is to **find every state mutation that doesn't flow through a CD-notifying channel** and route it through one.

A systematic sweep, in order: (1) **Inventory the symptoms** — catalog exactly which views go stale and what triggers the stale state (a `setTimeout`, a WebSocket message, a third-party callback, a manually-subscribed observable, an `addEventListener` outside Angular). The trigger almost always reveals the channel that's missing. (2) **Grep for the usual offenders**: bare `.subscribe()` that assigns to a **plain field** (not a signal) and is read in a template; `setTimeout`/`setInterval` callbacks mutating plain fields; `addEventListener` handlers (especially added outside Angular or in non-template code); promise `.then()` updating fields; and third-party library callbacks. (3) **For each, convert the mutated state to a signal** (so the read in the template auto-schedules CD) — this is the cleanest fix and aligns with the framework direction. Where a signal isn't practical, **wrap the source with `AsyncPipe`** in the template, or as a last resort inject `ChangeDetectorRef` and call `markForCheck()` after the mutation.

```typescript
// BEFORE (relied on Zone to notice): stale under zoneless
ws.onmessage = e => { this.lastMsg = JSON.parse(e.data); };   // plain field, no CD scheduled

// AFTER: signal read in template auto-schedules CD under zoneless
lastMsg = signal<Msg | null>(null);
ngOnInit() { this.ws.onmessage = e => this.lastMsg.set(JSON.parse(e.data)); }
// or, if you must keep a field: this.cdr.markForCheck() after the mutation.
```

To make this **exhaustive rather than whack-a-mole**: enable zoneless **behind a flag** and run the **full E2E + visual-regression suite** (the QA suite that surfaced the issue) so every screen is exercised; add a **lint rule** banning bare `.subscribe()` assignments and `setTimeout`-mutating-fields patterns; and prefer **`async` pipe + signals** as the standardized state path so new code can't reintroduce the bug. Two subtler traps to check: **`@Output`/`EventEmitter` consumers** — emitting still works, but the *handler's* mutations must themselves be signal/markForCheck-driven; and **`effect()` that performs DOM work** expecting Zone to tick afterward — restructure so the state itself is a signal. The meta-point for the interview: zoneless doesn't change *what* updates the view, it removes the **safety net that hid improper updates**, so the migration is fundamentally an exercise in making **every state change explicit** — which is also why it produces a cleaner, more predictable app once complete. Track progress as "screens verified under zoneless" rather than treating it as a single switch.

#### Q89. [Theory] Explain the security model around `DomSanitizer`, `bypassSecurityTrust*`, and Trusted Types. When is bypassing legitimate, and how do you minimize the blast radius?

Angular's default posture is that **all values rendered into templates are untrusted** and **contextually sanitized**. The `DomSanitizer` sanitizes against the **security context** of the binding: HTML (`[innerHTML]`), style, URL (`[href]`, `[src]`), and resource-URL (`<script src>`, `<iframe src>`) each have different rules — e.g., `[innerHTML]` strips `<script>`, event-handler attributes, and `javascript:` URLs; URL sanitization blocks `javascript:` schemes. This is why interpolation and standard bindings are safe by default and you rarely think about XSS for ordinary rendering — Angular escapes/sanitizes for you based on **where** the value lands.

`bypassSecurityTrust*` (`bypassSecurityTrustHtml`, `…Url`, `…ResourceUrl`, `…Style`, `…Script`) explicitly tells Angular "**I vouch for this value, do not sanitize it**," returning a `SafeValue` that the binding will render verbatim. It exists for legitimate cases where sanitization would break required functionality: rendering **trusted, server-controlled rich HTML** (a CMS body you control and have sanitized server-side), embedding a **known-safe iframe** (a YouTube embed URL you constructed), or applying a **computed style** the sanitizer over-strips. But every call is a **potential XSS hole** — if user-controlled data ever reaches a `bypassSecurityTrust*`, you've reintroduced exactly the vulnerability the framework prevents.

```typescript
// Legitimate: a fixed, trusted embed URL you construct from a validated id.
trustEmbed = (id: string): SafeResourceUrl =>
  this.sanitizer.bypassSecurityTrustResourceUrl(`https://www.youtube.com/embed/${encodeURIComponent(id)}`);
// DANGEROUS: never do this with arbitrary user input.
// this.sanitizer.bypassSecurityTrustHtml(userSuppliedHtml);  // XSS
```

**Trusted Types** is a browser security feature Angular supports (CSP `require-trusted-types-for 'script'`) that makes **DOM XSS sinks (like `innerHTML`, `script.src`) refuse raw strings** — they accept only typed `TrustedHTML`/`TrustedScriptURL` objects produced by an approved policy. This turns "we hope nobody assigned a raw string to a dangerous sink" into a **browser-enforced invariant**, dramatically shrinking the DOM-XSS attack surface; Angular's sanitizer integrates as a Trusted Types policy. To **minimize blast radius** of any necessary bypass: (1) **sanitize at the source** (server-side, with a hardened sanitizer like DOMPurify) and treat the bypass as marking already-sanitized content; (2) **never** pass user-controlled data into a bypass without re-sanitizing; (3) **centralize** bypass calls in a small, audited, reviewed module rather than scattering them — so security review is finite; (4) **constrain inputs** (validate/encode the id you interpolate into a resource URL); (5) **enable Trusted Types + a strict CSP** so even a mistake is caught by the browser; and (6) treat each `bypassSecurityTrust*` as a **code smell requiring justification** in review. The expert framing: Angular makes the safe path the default and gives you an explicit, greppable escape hatch — the security discipline is keeping those escapes **few, audited, fed only trusted/sanitized data, and backstopped by Trusted Types/CSP** so a single mistake doesn't become an exploit.

#### Q90. [Practical] A memory leak in a long-lived single-page app grows the heap over hours of use. Lay out a methodology to find and fix it.

A growing heap in an SPA that's never reloaded means **objects are being retained that should be released** as the user navigates and components are destroyed. The methodology is **heap-snapshot diffing**, not guessing. In Chrome DevTools **Memory** panel: take a baseline heap snapshot, perform a **repeatable cycle** (navigate into a feature and back out, or open/close a modal) several times, force GC, then take a second snapshot and use **"Comparison"** to see what *grew*. If component instances, DOM nodes, or subscriptions accumulate across cycles that should have torn down, you've found a leak. The **"Detached DOM tree"** and retained-size views show DOM that's no longer attached but still referenced (a classic listener leak).

The recurring Angular causes, mapped to fixes: (1) **Unmanaged subscriptions** — a `.subscribe()` to a long-lived/`shareReplay` source or a `Subject` that outlives the component keeps the component (and its DOM) alive; fix with `async` pipe or `takeUntilDestroyed()`. (2) **`shareReplay({ refCount: false })`** (or legacy `shareReplay(1)`) — never unsubscribes from the source, retaining buffered values and the subscription forever; switch to `refCount: true` unless a permanent cache is intended. (3) **Event listeners / observers** added via `addEventListener`, `IntersectionObserver`, `ResizeObserver` on `window`/`document` and not removed in `DestroyRef.onDestroy`. (4) **Timers** (`setInterval`) not cleared. (5) **Dynamically created `ComponentRef`s** not `destroy()`'d (and `ViewContainerRef`s not cleared). (6) **A global/`root` service holding references** to per-component callbacks/state (e.g., a notification service that pushes component methods into an array and never prunes). (7) **Closures captured by long-lived singletons** that close over a component or large object.

```typescript
// Confirm with a programmatic check during dev:
// take snapshot → navigate A→B→A ×10 → GC → snapshot → diff "ComponentX" instance count.

// Common fix pattern:
private destroyRef = inject(DestroyRef);
ngOnInit() {
  this.longLived$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(...);  // ties to lifecycle
  const obs = new ResizeObserver(() => this.measure());
  obs.observe(this.host);
  this.destroyRef.onDestroy(() => obs.disconnect());   // release the observer + its callback
}
```

The systematic loop is **isolate → reproduce → snapshot-diff → identify retainer → fix → re-verify**: once the comparison shows which class grows, click an instance and read its **retaining path** (the chain of references keeping it alive) — that path *names the leak* (e.g., "retained by `NotificationService.listeners`"). Fix the retainer, then re-run the same cycle and confirm the heap returns to baseline after GC. To prevent recurrence: standardize on `async`/`takeUntilDestroyed`, `refCount: true` on `shareReplay`, `DestroyRef.onDestroy` for every imperative resource, and lint against bare `.subscribe()`. The expert distinction interviewers want: a leak is identified by **what retains the object** (the snapshot's retaining path), not by where you *think* it is — disciplined heap diffing turns "the app gets slow after an hour" into "this `root`-scoped service holds destroyed components via an un-pruned array," which is a precise, verifiable fix.

#### Q91. [Theory] Discuss the architectural trade-offs of NgRx SignalStore versus classic NgRx (Store/Effects/Reducers) versus a hand-rolled signal service. When does each earn its complexity?

These three sit on a **structure-vs-boilerplate spectrum**, and the right choice is a function of **state complexity, team size, and auditability needs** — not fashion. **Classic NgRx** (actions, reducers, selectors, effects) imposes a strict **unidirectional, event-sourced** discipline: every state change is a dispatched action, reducers are pure, side effects live in effects, and the Redux DevTools give you **time-travel debugging and a complete, replayable action log**. This earns its substantial boilerplate when you have **many features mutating shared state**, a **large team** that needs enforced conventions, or **audit/traceability** requirements (finance, compliance) where "what changed this and why" must be reconstructable. For a simple app it's pure overhead — four files to toggle a boolean.

**NgRx SignalStore** is the middle ground introduced to fit the signals era: a store is defined functionally (`signalStore(withState(...), withComputed(...), withMethods(...), withEntities(...))`), exposing state as **signals** and selectors as **computed**, with methods replacing the action/reducer ceremony. You get a **structured, composable, feature-organized store** (with first-class entity management and `rxMethod` for async) at a fraction of classic NgRx's boilerplate, and it integrates natively with `OnPush`/zoneless. What you **give up** versus classic NgRx is the explicit **global action log and time-travel** — SignalStore's mutations are method calls, not replayable events, so deep auditability is weaker (though more debuggable than ad-hoc services).

```typescript
export const CartStore = signalStore(
  { providedIn: 'root' },
  withState<{ items: Item[] }>({ items: [] }),
  withComputed(({ items }) => ({ total: computed(() => items().reduce((s, i) => s + i.price, 0)) })),
  withMethods((store) => ({
    add: (i: Item) => patchState(store, s => ({ items: [...s.items, i] })),
  })),
);
```

A **hand-rolled signal service** is just an `@Injectable` exposing `readonly` signals + `computed` selectors + plain methods that `set`/`update`. It has **near-zero ceremony**, is trivially testable, and is the right default for **local or single-feature state** — most state in a modern app. Its limits: no enforced conventions (every dev invents their own shape, which fragments a large codebase), no built-in entity helpers, no devtools integration, and discipline must be maintained by code review rather than structure. The decision rubric to articulate: **local/feature state → hand-rolled signal service** (cheapest, sufficient for the majority); **structured cross-feature state needing consistency and entity management without audit logging → SignalStore** (best modern default for "real" shared state); **complex, multi-team, audited, time-travel-required domains → classic NgRx** (the boilerplate buys traceability you genuinely need). The anti-pattern at every level is **over-reaching** — classic NgRx for trivial UI state is the most common, costly mistake, while a sprawl of unstructured signal services across a 50-engineer codebase is the opposite failure. Match the **machinery to the coupling and governance needs** of the state, and prefer the lightest option that still meets the auditability and consistency bar.

#### Q92. [Theory] What is the relationship between Core Web Vitals (LCP, CLS, INP) and specific Angular features? Map each metric to the Angular tools that move it.

Core Web Vitals are user-centric performance metrics, and a senior Angular engineer should be able to map each to **concrete framework levers**, because "make it faster" is too vague to act on. **LCP (Largest Contentful Paint)** measures when the largest above-the-fold element renders — usually a hero image or headline. Angular tools that move it: **SSR + hydration** (server-rendered HTML paints content before JS executes, slashing LCP), **`NgOptimizedImage` with `priority`** (preloads and prioritizes the LCP image, sets `fetchpriority`), **`@defer`** to keep below-the-fold heavy components out of the initial bundle so the LCP element isn't delayed by parsing unrelated JS, and **route-level lazy loading** to shrink the initial chunk.

**CLS (Cumulative Layout Shift)** measures unexpected layout movement — content jumping as things load. Angular tools: **`NgOptimizedImage`'s mandatory width/height** (reserves space so images don't shove content down when they load), **skeleton/placeholder via `@defer (@placeholder)`** sized to match the eventual content so the swap doesn't shift layout, and avoiding **late-injected content** (ads, banners) without reserved space. Hydration also matters: a **hydration mismatch that triggers destructive re-render** causes a visible reflow/shift, so non-destructive hydration protects CLS.

**INP (Interaction to Next Paint)** — which replaced FID as a Core Web Vital — measures responsiveness: how quickly the UI updates after user interaction, capturing **main-thread blocking** during interactions. This is where Angular's CD model matters most: **`OnPush`/signals** reduce the work per interaction (only dependent bindings recompute instead of the whole tree), **zoneless** removes broad Zone-triggered CD and coalesces updates, **virtual scrolling** keeps interaction handlers from touching thousands of nodes, **`runOutsideAngular`** keeps high-frequency handlers off CD, and moving heavy template work into `computed()` (instead of methods called every cycle) cuts the scripting that blocks the next paint.

```
LCP  ← SSR+hydration, NgOptimizedImage(priority), @defer (below-fold), lazy routes, smaller initial bundle
CLS  ← NgOptimizedImage(width/height), sized @placeholder skeletons, non-destructive hydration, reserve space
INP  ← OnPush/signals, zoneless (coalesced CD), virtual scroll, runOutsideAngular, computed over template methods
```

The expert nuance: these metrics can **trade off**, so you optimize holistically. Aggressive `@defer`/lazy-loading improves LCP and initial INP but can hurt a later interaction if the chunk loads on click (mitigate with `prefetch on idle`/hover). SSR improves LCP but adds **hydration cost** that can hurt INP until hydration completes — which is exactly why **incremental hydration** exists (hydrate on interaction/viewport so you don't pay for JS the user hasn't reached). The methodology: measure each vital with Lighthouse/field data (CrUX), identify the **dominant** one, apply the mapped Angular lever, and re-measure — because the framework gives you a *specific* tool for each metric rather than a generic "performance mode," and knowing the mapping is what turns a CWV report into an actionable Angular work plan.

#### Q93. [Practical] Set up a production-grade CI/CD pipeline for a large Angular app. What stages, gates, and Angular-specific checks matter, and why?

A production Angular pipeline is a **sequence of fast-failing gates** ordered cheapest-first so feedback is quick and bad changes never reach prod. The stages, with the Angular-specific reasoning: (1) **Install + cache** — `npm ci` against the lockfile (reproducible installs; never `npm install` in CI), with `node_modules`/Nx-or-Angular build cache restored to keep builds fast. (2) **Lint + format** — ESLint (with `@angular-eslint`) and Prettier, plus **architecture lint** rules (ban bare `.subscribe()`, enforce `OnPush`, restrict cross-feature imports) so style and known footguns fail fast. (3) **Type-check / strict templates** — build with `strictTemplates` so template type errors are caught here, not at runtime. (4) **Unit tests** — run headless (Karma/Chrome-headless, Jest, or the Vitest runner) with **coverage thresholds** as a gate. (5) **Production build** — `ng build --configuration production`, which also enforces **bundle budgets** (a regression past the budget *fails the build* — the single most effective guard against creeping bundle bloat). (6) **E2E** — Playwright/Cypress against the built artifact (not the dev server) so you test what ships. (7) **Deploy** — to staging, smoke-test, then promote.

```yaml
# Illustrative pipeline (GitHub Actions-style)
steps:
  - run: npm ci
  - run: npm run lint
  - run: npx ng build --configuration production   # AOT + strictTemplates + budgets enforced
  - run: npx ng test --watch=false --browsers=ChromeHeadless --code-coverage
  - run: npx playwright test                         # E2E against the built artifact
  - run: # deploy dist/<app>/browser to staging, smoke test, then promote
```

The **Angular-specific gates** that distinguish a good pipeline: **bundle budgets in `angular.json`** (fail on size regressions — catch the accidental `import _ from 'lodash'` before it ships); **`strictTemplates`** in the build (turns template bugs into build failures); **source maps for staging** so monitoring/Sentry can de-minify production errors (upload them as a deploy step, then strip from the public artifact); **per-environment builds or runtime config** handled explicitly (don't bake prod secrets — they're public); and for SSR apps, **building and testing the server bundle** too. Other production concerns to mention: **immutable, content-hashed artifacts** promoted across environments (build once where possible, or at least pin versions); **a11y checks** (axe in E2E) and **Lighthouse CI** with CWV thresholds as optional gates; **dependency/security scanning** (`npm audit`, lockfile integrity, SCA) since the supply chain is part of frontend security; and **canary/blue-green or feature-flagged** deploys so a bad release is reversible. The strategic framing: each gate maps to a **class of failure Angular apps actually hit** — budgets→bundle bloat, strict templates→runtime template errors, E2E-on-artifact→"works in dev not prod," source-map upload→debuggable prod incidents — so the pipeline isn't generic CI boilerplate but a targeted defense against the specific ways Angular apps regress.

#### Q94. [Theory] How does the Angular Animations system work under the hood, what is its cost, and when should you prefer pure CSS or the Web Animations API instead?

Angular's animation system (`@angular/animations`, configured with `provideAnimationsAsync()` since v17) lets you define **state-machine-style** animations in TypeScript metadata: `trigger`, `state`, `transition`, `style`, `animate`, plus `query`/`stagger` for orchestrating child elements. Under the hood it builds on the **Web Animations API (WAAPI)** where available, driving the actual interpolation through the browser's animation engine, while Angular manages the **state transitions** (e.g., `void => *` for enter, `* => void` for leave) tied to the component/element lifecycle — which is what makes **enter/leave** animations (an element animating as `@if`/`@for` adds or removes it) possible, something pure CSS can't easily do because the element is gone before a CSS transition can run on removal.

The **cost** has two parts. First, a **bundle cost**: including the animations package and `provideAnimationsAsync()` adds runtime code — though `provideAnimationsAsync()` (vs the older eager `provideAnimations()`) **lazy-loads** the animation engine so it doesn't bloat the initial bundle. Second, a **runtime cost**: Angular's animation orchestration runs JS to compute and drive transitions, and historically animation callbacks interacted with Zone.js/CD. For simple hover/fade effects this overhead is unjustified.

```typescript
// Angular animations shine for lifecycle (enter/leave) + orchestration:
trigger('listAnim', [
  transition('* => *', [
    query(':enter', [style({ opacity: 0 }), stagger(50, animate('200ms', style({ opacity: 1 })))], { optional: true }),
  ]),
]);
```

The decision rubric for when to **prefer alternatives**: use **pure CSS transitions/animations** for the common case — hovers, simple fades, spinners, state toggles you can express with a class — because they run **entirely on the compositor/GPU off the main thread** (for `transform`/`opacity`), add **zero JS**, and are the cheapest possible option; reach for Angular animations only when you need **enter/leave tied to structural changes**, **complex multi-element orchestration** (`query`/`stagger`), or **programmatic control** synchronized with component state. Use the **raw Web Animations API** (`element.animate(...)`) directly when you need **imperative, JS-driven** animation (physics, gesture-following) without Angular's state-machine model and without pulling in the animations package — typically inside `afterNextRender`/`runOutsideAngular` so it doesn't trigger CD. The performance principle to articulate: animate **`transform` and `opacity`** (compositor-only, no layout/paint) regardless of which system you choose, avoid animating layout-triggering properties (`width`, `top`), and pick the **lowest-overhead tool that expresses the animation** — CSS first, Angular animations for lifecycle/orchestration, WAAPI for imperative needs. The interview-level insight is recognizing that Angular's animation system's *unique* value is **enter/leave + orchestration tied to the component lifecycle**, and for everything else CSS is lighter and faster — so reaching for `@angular/animations` to do a hover fade is over-engineering.

#### Q95. [Practical] How do you make a complex Angular app accessible (a11y), and what does the framework give you versus what you must build and test yourself?

Accessibility in Angular is mostly **your responsibility plus CDK helpers** — the framework doesn't make an app accessible automatically, but the **Angular CDK `a11y` package** provides the hard primitives. What the CDK gives you: **`FocusTrap`** (`cdkTrapFocus`) for keeping keyboard focus inside modals/dialogs; **`LiveAnnouncer`** for programmatically announcing dynamic changes to screen readers via ARIA live regions (e.g., "5 results loaded"); **`FocusMonitor`** to track *how* an element was focused (mouse vs keyboard) so you can show focus rings only for keyboard users; **`cdkAriaLive`** directives; and **Angular Material** components that ship with correct ARIA roles, keyboard interaction, and focus management already implemented (a major reason to use Material for complex widgets rather than rolling your own).

```typescript
export class ResultsComponent {
  private live = inject(LiveAnnouncer);
  onLoaded(n: number) { this.live.announce(`${n} results loaded`, 'polite'); }  // SR feedback
}
```
```html
<div cdkTrapFocus>                 <!-- focus stays in the dialog -->
  <h2 id="dlgTitle">Edit profile</h2>
  <button mat-button (click)="close()">Close</button>
</div>
```

What **you** must build and own: **semantic HTML** (use `<button>`, `<nav>`, `<main>`, real headings — not `<div (click)>`), **ARIA attributes** via `[attr.aria-*]` bindings tied to component state (`[attr.aria-expanded]="open()"`, `[attr.aria-label]`), **keyboard interaction** (`(keydown.enter)`, `(keydown.escape)`, arrow-key navigation in custom widgets, logical tab order), **visible focus indicators**, **color contrast**, **route-change focus management** (move focus to the new view's heading and announce navigation, since SPA route changes don't reload the page or move focus the way a full navigation would — a commonly-missed SPA a11y gap), and **form accessibility** (associated `<label>`s, `aria-describedby` for errors, `aria-invalid`). The SPA-specific traps to call out: route changes that **don't manage focus or announce**, dynamically-loaded (`@defer`/lazy) content that appears without announcement, and custom components missing keyboard support.

On **testing**: the framework gives you no a11y guarantees, so you must verify — integrate **axe-core** (e.g., `@axe-core/playwright` or jest-axe) into E2E/unit tests as a **CI gate** to catch missing labels, contrast, and ARIA misuse automatically; run the **CDK component harnesses** which interact via accessible semantics (forcing you toward accessible markup); and supplement automated checks with **manual keyboard-only and screen-reader (NVDA/VoiceOver) passes**, since automation catches maybe half of real issues (it can't judge whether focus order or announcements *make sense*). The expert framing: Angular/CDK hands you the **mechanical primitives** (focus trap, live announcer, focus monitor, accessible Material widgets) that are genuinely hard to build correctly, but **semantics, ARIA wiring, keyboard support, route-focus management, and contrast are application concerns** you implement and must **test with automated axe gates plus manual AT verification** — treating a11y as a CI-enforced requirement rather than a manual afterthought is what separates a compliant product from one that fails an audit.

#### Q96. [Theory] Explain the full picture of how `@defer` + incremental hydration changes the SSR mental model versus traditional "hydrate everything" SSR. What new failure modes appear?

Traditional SSR hydrates the **entire** application: the server renders all the HTML, ships it, and then the client **downloads and executes the JavaScript for the whole app** and hydrates every component at once (non-destructive hydration since v17 reuses the DOM, but it still must load and run all the component code to attach listeners/bindings). The mental model is "render once on server, hydrate once on client" — and its cost is that **TTI/INP suffers** because the user can see content (good LCP) but can't interact until a potentially large JS bundle has parsed and hydrated (a long "uncanny valley" where the page looks ready but isn't).

**`@defer (hydrate on …)` + incremental hydration** (`withIncrementalHydration()`, dev preview v19) breaks hydration into **per-block, on-demand** units. The server still renders the deferred content's HTML (it's **dehydrated**, present but with JS not yet attached), but the client **does not hydrate that block — or download its JavaScript — until its trigger fires** (`on viewport`, `on interaction`, `on idle`, etc.). So a comments panel below the fold ships as **static, server-rendered, inert HTML** that's visible immediately, and only hydrates (loads its JS, attaches listeners) when the user scrolls to it or interacts. This collapses the initial hydration JS to just the **above-the-fold/interactive-now** parts, directly improving **INP and TTI** while keeping the SSR LCP/SEO benefits.

```html
@defer (hydrate on interaction) {
  <comments-panel />        <!-- server-rendered & visible immediately; JS loads on first interaction -->
} @placeholder {
  <comments-panel />        <!-- in incremental hydration, the SSR'd content IS the dehydrated block -->
}
```

```
Traditional SSR:   server HTML  →  download ALL JS  →  hydrate WHOLE app  →  interactive
Incremental:       server HTML  →  download CORE JS  →  hydrate shell      →  interactive (shell)
                                   ↳ each @defer block hydrates on its trigger (JS loaded then)
```

The **new failure modes** to reason about: (1) **Per-block state consistency** — because a block is dehydrated and hydrates *later*, the application state at hydration time must still match the server-rendered output, or you get a **per-block hydration mismatch**; you now reason about hydration **N times** (once per deferred block) instead of once, widening where mismatches can occur. (2) **Interaction before hydration** — a user can click a button in a dehydrated block before its JS loads; Angular queues/replays the triggering interaction, but you must ensure the experience degrades gracefully and the trigger is appropriate (use `hydrate on interaction` for interactive regions so the very act of interacting loads them). (3) **SEO/content correctness** — deferred content **must** be server-rendered (it is, with incremental hydration) so crawlers and no-JS users still see it; misconfiguring a block as not-server-rendered would hide content. (4) **Trigger design errors** — choosing `hydrate on viewport` for something users interact with without scrolling, or `on idle` for critical interactive UI, creates a region that *looks* ready but responds late. (5) **Nested defer/hydration boundaries** complicate reasoning about what's loaded when. The expert summary: incremental hydration shifts the model from a **single global hydrate** to a **graph of independently-hydrating islands**, trading a one-time large hydration cost for **deferred, trigger-driven hydration** that dramatically improves interactivity — but in exchange you must now design and verify hydration **per block** (state consistency, interaction-before-hydration, trigger appropriateness, server-rendering of deferred content), which is a genuinely new and more granular set of concerns than "hydrate everything once."

#### Q97. [Practical] A WebSocket-backed real-time dashboard occasionally shows stale or out-of-order data and degrades over time. Diagnose the likely Angular/RxJS causes and design a robust solution.

Real-time dashboards expose several distinct failure classes, and the symptoms (stale, out-of-order, degrading) each point at a different cause. **Stale data** usually means the view isn't updating on new messages — under **zoneless** this is the classic "mutated a plain field from `ws.onmessage` so no CD was scheduled," or under `OnPush` a mutation without `markForCheck`/signal. **Out-of-order data** points at **concurrency mishandling**: if each message triggers an async enrichment call flattened with `mergeMap`, responses can arrive out of order and overwrite newer data — the same race `switchMap` exists to prevent (use `switchMap` when only the latest matters, or sequence with `concatMap` when order is required). **Degrading over time** is almost always a **leak or unbounded buffer**: a `ReplaySubject`/`shareReplay` with no bound accumulating every message, a subscription per reconnect that never unsubscribes (so after N reconnects you have N live subscriptions all updating state), or an ever-growing array in the component/store that's never windowed.

The robust design ties the socket to a **single, lifecycle-bound, reconnecting stream** feeding **signals**, with explicit concurrency and bounded buffers:

```typescript
@Injectable({ providedIn: 'root' })
export class FeedService {
  private destroyRef = inject(DestroyRef);
  latest = signal<Tick | null>(null);
  private socket$ = webSocket<Tick>('wss://example/feed');

  stream$ = this.socket$.pipe(
    retry({ delay: (_e, n) => timer(Math.min(2 ** n * 500, 15_000)) }),  // reconnect w/ backoff cap
    takeUntilDestroyed(this.destroyRef),                                  // one subscription, cleaned up
  );

  start() {
    this.stream$.subscribe(t => this.latest.set(t));   // signal write → CD scheduled (zoneless-safe)
  }
}
// Enrichment with correct concurrency (latest-wins):
// this.stream$.pipe(switchMap(t => this.enrich(t)))   // not mergeMap → no out-of-order overwrite
```

The robustness measures to articulate: (1) **One subscription, lifecycle-bound** via `takeUntilDestroyed` so reconnects don't stack subscriptions (the degradation cause); a single `retry` with **capped exponential backoff** handles drops without hammering the server. (2) **Drive the view through signals** (or `async` pipe) so updates schedule CD under zoneless/`OnPush` — never mutate plain fields from `onmessage`. (3) **Choose the right flattening operator** for any per-message async work — `switchMap` for latest-wins, `concatMap` for strict order — to eliminate out-of-order overwrites. (4) **Bound everything**: window the displayed history (`scan` keeping the last N, or a ring buffer), avoid unbounded `ReplaySubject`, and use `shareReplay({ bufferSize: 1, refCount: true })` if multiplexing one socket to many consumers so a single connection is shared and torn down when unused. (5) **Throttle/sample high-frequency feeds** (`sampleTime`/`throttleTime`/`auditTime`) so a 1000-msg/sec feed doesn't trigger 1000 CD passes — coalesce to render at a sustainable rate (and run pure rendering work `runOutsideAngular` if using a canvas). (6) **Handle the connection lifecycle explicitly**: detect disconnects, show a "reconnecting" state, and **resynchronize** on reconnect (request a fresh snapshot rather than assuming the delta stream is continuous, since messages during the outage are lost). The expert framing: real-time bugs are rarely "the data is wrong" — they're **CD not firing (stale), wrong concurrency operator (out-of-order), or accumulated subscriptions/buffers (degradation)**, so the durable solution is a **single reconnecting stream → signals**, with deliberate operator choice, bounded buffers, throttling, and explicit reconnect resynchronization — turning an unbounded, leaky, race-prone pipeline into a lifecycle-managed, back-pressure-aware one.

#### Q98. [Theory] Make the case for and against adopting Angular (vs React/Vue) for a new 2026 greenfield project. What does a balanced staff-level recommendation look like?

A credible staff-level answer resists tribalism and frames the choice around **team, longevity, and problem shape** rather than benchmarks. **The case for Angular in 2026:** it is a **batteries-included framework** — routing, forms, HTTP, DI, i18n, SSR, testing, and a CLI are first-party, versioned together, and upgraded via `ng update` with **automated migrations**, which is enormously valuable for **large, long-lived, multi-team enterprise apps** where consistency and a managed upgrade path matter more than ecosystem flexibility. Its **opinionated structure** reduces bikeshedding and onboarding friction across big teams; **TypeScript-first** with `strictTemplates` gives strong compile-time safety; **hierarchical DI** scales dependency management and testability; and the modern era (**signals, zoneless, standalone, `@defer`, incremental hydration**) has closed most of the historical gap on boilerplate and runtime performance while keeping the enterprise strengths. For domains like **finance, healthcare, internal platforms, and large SaaS** with multi-year horizons and rotating teams, these are decisive advantages.

**The case against / for React or Vue:** **React** has the **largest ecosystem and talent pool**, maximal **flexibility** (pick your own router/state/data libs), and dominates startups, content sites, and React Native mobile — its "library not framework" nature is a feature when you want to assemble a bespoke stack or move fast with abundant hiring. **Vue** offers a **gentler learning curve** and excellent DX, strong for small-to-medium teams and progressive enhancement. Angular's costs are real: a **steeper learning curve** (DI, RxJS, the framework's concept count), a **smaller talent pool** than React, **more ceremony** for tiny apps (though much reduced since v17), and an ecosystem that's first-party-rich but third-party-thinner than React's. For a **small team, a content/marketing site, a startup optimizing for hiring speed, or a project wanting a React Native path**, Angular is often the wrong fit.

```
Choose Angular when:                         Choose React/Vue when:
- large/multi-team, multi-year app           - small team / startup, fast iteration
- want batteries-included + managed upgrades - want ecosystem flexibility / bespoke stack
- enterprise domain, consistency > freedom   - React Native / huge talent pool needed (React)
- strong typing + DI + SSR out of the box    - gentle curve / progressive adoption (Vue)
```

A **balanced recommendation** sounds like: *"For this greenfield, the deciding factors are team size and time horizon, not raw performance — all three are fast enough in 2026. If this is a long-lived enterprise platform with multiple teams who value consistency and a first-party, auto-migrated upgrade path, Angular's opinionated, batteries-included model and strong typing/DI/SSR pay off over years and reduce coordination cost. If we're a small team optimizing for hiring, ecosystem flexibility, or a shared React Native codebase, React is the pragmatic pick; Vue if we want the gentlest curve. I'd also weigh existing team expertise heavily — a team fluent in one framework will out-deliver a 'better on paper' choice they're learning."* The mark of seniority is naming the **trade-off axes** (structure vs flexibility, team scale, longevity, hiring, existing skills), acknowledging that **modern Angular has neutralized the old 'too much boilerplate / too slow' criticisms**, and making a **contingent recommendation tied to this project's specifics** rather than declaring a universal winner — because the honest truth is the right answer is situational, and a staff engineer's value is in correctly reading the situation.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q99. [Coding] Implement a custom attribute directive that highlights an element on hover, with a configurable color input.

**Problem:** Build `appHighlight` that changes the host element's background on `mouseenter` and reverts on `mouseleave`, where the color is configurable via an input and falls back to a default.

Attribute directives are the idiomatic way to add behavior to existing elements without wrapping them. The key building blocks are `ElementRef` to reach the host element, `@HostListener` (or the `host` metadata) to react to DOM events, and an `input()` for configuration. Using `Renderer2` instead of touching `nativeElement.style` directly keeps the directive **SSR-safe** and **abstracted from the rendering platform** — important because the same directive may run in a server or web-worker context where `nativeElement.style` does not exist.

```typescript
import { Directive, ElementRef, Renderer2, inject, input, HostListener } from '@angular/core';

@Directive({ selector: '[appHighlight]' })   // standalone implied (v19+)
export class HighlightDirective {
  // Aliasing lets consumers write [appHighlight]="'gold'" as the primary input.
  color = input('yellow', { alias: 'appHighlight' });

  private el = inject(ElementRef<HTMLElement>);
  private renderer = inject(Renderer2);

  @HostListener('mouseenter') onEnter() { this.set(this.color()); }
  @HostListener('mouseleave') onLeave() { this.set(null); }

  private set(bg: string | null) {
    // Renderer2 is platform-agnostic and SSR-safe vs el.nativeElement.style.background = ...
    this.renderer.setStyle(this.el.nativeElement, 'background-color', bg ?? '');
  }
}
```

Usage: `<p [appHighlight]="'#ffd'">Hover me</p>` or simply `<p appHighlight>` for the default. **Edge cases:** an empty/falsy color should revert cleanly (handled by passing `''`); if you mutate styles you set, prefer `removeStyle` over setting empty strings to avoid leaving an empty `style` attribute. **Why a directive over a CSS `:hover`** — you'd use a directive when the behavior is dynamic, data-driven, or needs to emit events; pure visual hover should stay in CSS. **Complexity:** O(1) per event.

#### Q100. [Coding] Write a custom pipe that formats a number of bytes into a human-readable string (KB/MB/GB), and explain why it should be pure.

**Problem:** `{{ file.size | fileSize }}` should render `1536` as `1.5 KB`, `1048576` as `1 MB`, with an optional precision argument.

A pure pipe is the right tool because byte-formatting is a **referentially transparent** transformation: the same input always yields the same output, so Angular can memoize it and only recompute when the input value changes. Making it impure would cause it to recompute on every change-detection cycle for no benefit — a needless cost when the pipe appears in a long list.

```typescript
import { Pipe, PipeTransform } from '@angular/core';

@Pipe({ name: 'fileSize' })   // pure by default
export class FileSizePipe implements PipeTransform {
  private static readonly UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];

  transform(bytes: number | null | undefined, precision = 1): string {
    if (bytes == null || isNaN(bytes) || bytes < 0) return '';
    if (bytes === 0) return '0 B';
    const i = Math.min(
      Math.floor(Math.log(bytes) / Math.log(1024)),
      FileSizePipe.UNITS.length - 1,
    );
    const value = bytes / Math.pow(1024, i);
    // Trim trailing .0 for whole numbers (1.0 MB -> 1 MB)
    return `${parseFloat(value.toFixed(precision))} ${FileSizePipe.UNITS[i]}`;
  }
}
```

**Edge cases handled:** null/undefined/NaN (return empty), zero (special-cased to avoid `log(0) = -Infinity`), negative values, and overflow beyond petabytes (clamped to the largest unit). **Why this matters in interviews:** the `log`-based unit selection avoids a chain of `if/else` and is the elegant solution; handling `bytes === 0` separately is the most-missed edge case. The precision argument is part of the pipe's identity for memoization — Angular re-runs the pipe if either `bytes` or `precision` changes. **Complexity:** O(1).

#### Q101. [Coding] Build a component that uses `@if`/`@for`/`@switch` together to render a typed UI state machine (loading / error / empty / data).

**Problem:** A list view must clearly render four mutually exclusive states. Show how the new built-in control flow expresses this cleanly with signals.

Modeling view state as a single discriminated value (rather than three independent booleans like `isLoading`, `hasError`, `isEmpty`) prevents impossible states (e.g., loading *and* error simultaneously) and makes the template a direct projection of one variable. `@switch` over a status signal is the most readable expression of this; `@for` with `@empty` handles the data-vs-empty distinction in one construct.

```typescript
type ViewState<T> =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; items: T[] };

@Component({
  selector: 'app-orders',
  template: `
    @switch (state().kind) {
      @case ('loading') { <app-spinner /> }
      @case ('error')   { <p class="err">{{ errorMessage() }}</p>
                          <button (click)="reload()">Retry</button> }
      @case ('ready') {
        @for (o of items(); track o.id) {
          <div class="row">{{ o.label }}</div>
        } @empty {
          <p>No orders yet.</p>
        }
      }
    }
  `,
})
export class OrdersComponent {
  state = signal<ViewState<Order>>({ kind: 'loading' });

  // Narrowing helpers keep the template free of unsafe casts.
  items = computed(() => { const s = this.state(); return s.kind === 'ready' ? s.items : []; });
  errorMessage = computed(() => { const s = this.state(); return s.kind === 'error' ? s.message : ''; });

  reload() { this.state.set({ kind: 'loading' }); /* re-trigger fetch */ }
}
```

**Why this design:** the discriminated union makes invalid combinations unrepresentable, `@switch` reads top-to-bottom like the state diagram, and `@empty` removes the classic `@if (items.length === 0)` branch. **Edge case:** TypeScript can't narrow `state().kind` *inside* the template across the `@case` boundary, so I expose narrowed `computed` accessors (`items()`, `errorMessage()`) instead of casting in the template. **Complexity:** O(n) to render n rows; `track o.id` keeps updates surgical.

### 🟡 Intermediate — extended

#### Q102. [Coding] Implement a `ControlValueAccessor` so a custom rating component works with reactive forms and `[(ngModel)]`.

**Problem:** Build a star-rating component usable as `<app-rating formControlName="score" />` that participates fully in Angular forms (value, validation, disabled state, touched).

A `ControlValueAccessor` (CVA) is the bridge between Angular's forms API and a custom UI control. The four methods are the contract: `writeValue` (forms → component, set the displayed value), `registerOnChange` (component → forms, call when the user changes the value), `registerOnTouched` (notify blur for `touched` state), and `setDisabledState` (reflect `disable()`). The self-registration via `NG_VALUE_ACCESSOR` needs `forwardRef` because the class isn't defined yet when the decorator evaluates.

```typescript
@Component({
  selector: 'app-rating',
  template: `
    @for (star of stars; track star) {
      <span (click)="rate(star)" [class.filled]="star <= value"
            [style.opacity]="disabled ? 0.5 : 1" [style.cursor]="disabled ? 'default' : 'pointer'"
            (blur)="onTouched()" tabindex="0">★</span>
    }
  `,
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => RatingComponent), multi: true },
  ],
})
export class RatingComponent implements ControlValueAccessor {
  readonly stars = [1, 2, 3, 4, 5];
  value = 0;
  disabled = false;

  private onChange: (v: number) => void = () => {};
  onTouched: () => void = () => {};

  rate(n: number) {
    if (this.disabled) return;
    this.value = n;
    this.onChange(n);   // push to the FormControl
    this.onTouched();
  }

  // --- CVA contract ---
  writeValue(v: number): void { this.value = v ?? 0; }
  registerOnChange(fn: (v: number) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(isDisabled: boolean): void { this.disabled = isDisabled; }
}
```

**Edge cases:** `writeValue(null)` must be handled (default to 0) since `reset()` passes null; `setDisabledState` must actually block interaction (the early return in `rate`); call `onTouched()` on blur so validation messages appear at the right time. **Note for OnPush:** if the component is `OnPush`, `writeValue` from a programmatic `setValue` may need `markForCheck()`. **Complexity:** O(1) per interaction. The CVA pattern is what makes any custom control a first-class form citizen — without it, `formControlName` throws "No value accessor."

#### Q103. [Coding] Build a dynamic reactive form with `FormArray` — add/remove line items and compute a live total.

**Problem:** An invoice form needs a variable number of line items (description, qty, price), the ability to add/remove rows, and a reactive grand total that updates as values change.

`FormArray` is the construct for a variable-length list of controls. Each line item is a `FormGroup`; the array holds them. The live total is best derived as a **signal** off the array's `valueChanges` via `toSignal`, so the template reads it synchronously and it recomputes only when values change — avoiding a method call in the template that would run every CD cycle.

```typescript
export class InvoiceComponent {
  private fb = inject(NonNullableFormBuilder);

  form = this.fb.group({
    customer: ['', Validators.required],
    items: this.fb.array<FormGroup>([this.newItem()]),
  });

  get items(): FormArray { return this.form.controls.items; }

  private newItem(): FormGroup {
    return this.fb.group({
      description: ['', Validators.required],
      qty: [1, [Validators.required, Validators.min(1)]],
      price: [0, [Validators.required, Validators.min(0)]],
    });
  }

  addItem()    { this.items.push(this.newItem()); }
  removeItem(i: number) { this.items.removeAt(i); }

  // Live total as a signal — recomputes only on value changes, read synchronously in template.
  total = toSignal(
    this.items.valueChanges.pipe(
      startWith(this.items.value),
      map(items => items.reduce((sum, it: any) => sum + it.qty * it.price, 0)),
    ),
    { initialValue: 0 },
  );
}
```

```html
<form [formGroup]="form">
  <input formControlName="customer" placeholder="Customer" />
  <div formArrayName="items">
    @for (item of items.controls; track item; let i = $index) {
      <div [formGroupName]="i">
        <input formControlName="description" />
        <input type="number" formControlName="qty" />
        <input type="number" formControlName="price" />
        <button type="button" (click)="removeItem(i)" [disabled]="items.length === 1">✕</button>
      </div>
    }
  </div>
  <button type="button" (click)="addItem()">+ Add line</button>
  <strong>Total: {{ total() | currency }}</strong>
</form>
```

**Edge cases:** prevent removing the last row (`[disabled]="items.length === 1"`); `track item` (the control reference) is stable across reorders so inputs keep focus; `startWith` seeds the total before the first edit. **Pitfall:** binding `[formGroupName]="i"` requires the surrounding `formArrayName="items"`. **Complexity:** total reduction is O(n) per change; with hundreds of rows, consider memoizing per-row subtotals. This is the canonical "Angular can do dynamic forms cleanly" demonstration interviewers look for.

#### Q104. [Coding] Convert an RxJS-based search service to the signals world using `toSignal`/`toObservable`, keeping debounce and cancellation.

**Problem:** You have a signal `query` driven by an input, and need debounced, cancelable HTTP search whose results are exposed as a signal — bridging the two reactive systems correctly.

The idiomatic v18+ pattern is "state in signals, async composition in RxJS, bridge at the seam." `toObservable(query)` turns the signal into a stream you can debounce and `switchMap`; `toSignal(...)` turns the result stream back into a signal for the template. This keeps the *composition* (debounce, cancel) in RxJS — where it's expressed naturally — while the *state* lives in signals.

```typescript
export class SearchComponent {
  private api = inject(SearchService);
  query = signal('');

  results = toSignal(
    toObservable(this.query).pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q =>
        q.length < 2
          ? of([] as Result[])                       // short query → empty, no request
          : this.api.search(q).pipe(catchError(() => of([]))),  // switchMap cancels stale calls
      ),
    ),
    { initialValue: [] as Result[] },
  );

  isSearching = computed(() => this.query().length >= 2);

  onInput(value: string) { this.query.set(value); }
}
```

```html
<input (input)="onInput($any($event.target).value)" placeholder="Search…" />
@for (r of results(); track r.id) { <div>{{ r.name }}</div> }
@empty { @if (isSearching()) { <p>No matches</p> } }
```

**Why `toSignal` over manual subscription:** `toSignal` handles subscription and teardown automatically (tied to the injection context's `DestroyRef`), so there's no leak and no `async` pipe. **Edge cases:** `toObservable` must run in an injection context (field initializer is fine); `switchMap` cancels in-flight requests on a new query (the race-condition fix); `catchError` keeps the stream alive. **Trade-off:** `toSignal` drops the observable's error onto a thrown read by default — wrap with `catchError` (as here) or use `{ rejectErrors: true }` deliberately. **Complexity:** O(1) per keystroke for stream wiring; network dominates.

#### Q105. [Coding] Implement virtual scrolling for a 50,000-row table using the CDK, and explain when `itemSize` autosizing is needed.

**Problem:** Render a 50k-row dataset smoothly. Show the CDK virtual scroll setup and discuss fixed vs variable row heights.

Virtual scrolling renders only the rows currently in (and just around) the viewport, recycling DOM nodes as you scroll — turning O(n) DOM nodes into O(visible). The CDK's `cdk-virtual-scroll-viewport` with `*cdkVirtualFor` (or the `@for`-compatible approach) is the standard tool. For uniform row heights you give a fixed `itemSize`; for variable heights you need the autosize strategy (`AutoSizeVirtualScrollStrategy` from `@angular/cdk-experimental`), which measures rendered items but is heavier and less precise.

```typescript
@Component({
  selector: 'app-big-table',
  imports: [ScrollingModule],
  template: `
    <cdk-virtual-scroll-viewport itemSize="40" class="viewport">
      <table>
        <tr *cdkVirtualFor="let row of rows; trackBy: trackById" [style.height.px]="40">
          <td>{{ row.id }}</td><td>{{ row.name }}</td><td>{{ row.email }}</td>
        </tr>
      </table>
    </cdk-virtual-scroll-viewport>
  `,
  styles: [`.viewport { height: 600px; } tr { display: table; width: 100%; table-layout: fixed; }`],
})
export class BigTableComponent {
  rows = Array.from({ length: 50_000 }, (_, i) => ({ id: i, name: `User ${i}`, email: `u${i}@x.io` }));
  trackById = (_: number, r: { id: number }) => r.id;
}
```

**Critical details:** `itemSize` **must match the real rendered height** — a mismatch causes scrollbar drift and blank gaps. Tables need `table-layout: fixed` and per-row `display: table` so each virtual row is independently sized. **When you need autosize:** rows with wrapping text or variable content where a fixed height is impossible — but it costs extra measurement passes and can jitter, so prefer fixed heights (truncate/ellipsis) when you can. **Alternative for grids:** the CDK also supports horizontal orientation. **Complexity:** rendering is O(visible) ≈ O(15–30 rows) regardless of dataset size — the entire point. Pair with `OnPush` and stable `trackBy` for the smoothest result.

#### Q106. [Coding] Write a custom RxJS operator (a reusable pipeable function) — e.g., `retryWithBackoff` — and explain the operator factory pattern.

**Problem:** Create a reusable operator that retries a source observable with exponential backoff and jitter, capped at a max number of attempts, usable as `source$.pipe(retryWithBackoff({ maxRetries: 3 }))`.

A custom operator is just a **function that returns an `OperatorFunction<T, R>`** — i.e., a function taking a source `Observable<T>` and returning an `Observable<R>`. This "operator factory" pattern lets you parameterize behavior and compose your operator in any `.pipe()` chain exactly like built-in operators, which is far cleaner than copy-pasting the same `retry` config everywhere.

```typescript
import { Observable, timer, throwError } from 'rxjs';
import { retry } from 'rxjs/operators';

interface BackoffOptions { maxRetries?: number; baseMs?: number; capMs?: number; }

export function retryWithBackoff<T>(
  { maxRetries = 3, baseMs = 300, capMs = 10_000 }: BackoffOptions = {},
) {
  return (source$: Observable<T>): Observable<T> =>
    source$.pipe(
      retry({
        count: maxRetries,
        delay: (error, attempt) => {
          // Don't retry client errors (4xx) — only transient/server failures.
          if (error?.status >= 400 && error?.status < 500) return throwError(() => error);
          const expo = Math.min(capMs, baseMs * 2 ** (attempt - 1));
          const jitter = Math.random() * expo * 0.3;     // avoid thundering herd
          return timer(expo + jitter);
        },
      }),
    );
}

// Usage — composes like any built-in operator:
this.http.get<Data>('/api/data').pipe(retryWithBackoff({ maxRetries: 4, baseMs: 500 }));
```

**Why this design:** the generic `<T>` preserves type flow; default options make the call site terse; **jitter** prevents synchronized retries from many clients hammering the server in lockstep; and **not retrying 4xx** is the load-bearing correctness rule — retrying a 400/401 is pointless and can amplify an outage. **Edge cases:** the `delay` callback returning `throwError` aborts retrying for non-transient errors; capping `expo` prevents absurd waits. **Composability test:** because it returns an `OperatorFunction`, it works inside `switchMap`, after `map`, anywhere. **Complexity:** O(maxRetries) worst case. Building operators this way is a strong signal of RxJS fluency.

### 🟠 Advanced — extended

#### Q107. [Coding] Dynamically create and destroy a component at runtime in Ivy (no `ComponentFactoryResolver`). Show passing inputs and wiring outputs.

**Problem:** Render an alert/toast component imperatively from a service, set its inputs, subscribe to its outputs, and destroy it cleanly — the modern Ivy way.

Since Ivy, `ComponentFactoryResolver`/`entryComponents` are gone. You create components directly via a `ViewContainerRef.createComponent(ComponentClass)` (in a host context) or `createComponent()` from `@angular/core` with an `EnvironmentInjector` (for app-level overlays). The returned `ComponentRef` exposes `setInput()` for inputs, `.instance` for outputs, and `.destroy()` for teardown.

```typescript
@Injectable({ providedIn: 'root' })
export class ToastService {
  private appRef = inject(ApplicationRef);
  private envInjector = inject(EnvironmentInjector);

  show(message: string, type: 'info' | 'error' = 'info'): void {
    // Create the component detached from any template:
    const ref: ComponentRef<ToastComponent> = createComponent(ToastComponent, {
      environmentInjector: this.envInjector,
    });

    // Set inputs the supported way (works with signal inputs and decorator inputs):
    ref.setInput('message', message);
    ref.setInput('type', type);

    // Wire an output — instance.closed is an OutputEmitterRef/EventEmitter:
    const sub = ref.instance.closed.subscribe(() => cleanup());

    // Attach to the application's change detection and to the DOM:
    this.appRef.attachView(ref.hostView);
    document.body.appendChild((ref.hostView as EmbeddedViewRef<unknown>).rootNodes[0] as Node);

    const cleanup = () => {
      sub.unsubscribe();
      this.appRef.detachView(ref.hostView);
      ref.destroy();                 // disposes the component, runs ngOnDestroy
    };
    setTimeout(cleanup, 4000);       // auto-dismiss
  }
}
```

**Critical steps people miss:** `attachView` is required so the dynamically-created component participates in change detection (otherwise its bindings never update); you must manually append `rootNodes[0]` to the DOM since there's no template host; and `destroy()` plus `detachView` plus `unsubscribe` together prevent leaks. **`setInput` vs poking fields:** `setInput` properly triggers `ngOnChanges`/signal input updates and works under `OnPush`; assigning `ref.instance.message = x` bypasses that. **Edge cases:** guard `document` usage for SSR; avoid double-cleanup (the timer + the close event can race — a `destroyed` flag fixes it). **Complexity:** O(1). This is the foundation of overlay/modal/toast libraries.

#### Q108. [Coding] Implement a fully-typed signal store service (NgRx-SignalStore-style by hand) with selectors, updaters, and async loading.

**Problem:** Build a lightweight store for a todo feature using only signals — readonly state, computed selectors, mutating methods, and an async load — without pulling in NgRx.

A signal-based service store is the 2026 default for shared feature state: a class exposing `readonly` signals (so consumers can't mutate state directly), `computed` selectors, and methods that perform controlled `update`/`set`. It gives you the unidirectional discipline of a store with a fraction of NgRx's boilerplate, integrates with `OnPush`/zoneless natively, and is trivially testable (it's just a class).

```typescript
interface Todo { id: number; title: string; done: boolean; }

@Injectable({ providedIn: 'root' })
export class TodoStore {
  private http = inject(HttpClient);

  // --- private writable state ---
  private _todos = signal<Todo[]>([]);
  private _loading = signal(false);
  private _filter = signal<'all' | 'active' | 'done'>('all');

  // --- public readonly state ---
  readonly todos = this._todos.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly filter = this._filter.asReadonly();

  // --- computed selectors (memoized, glitch-free) ---
  readonly visible = computed(() => {
    const f = this._filter();
    return this._todos().filter(t => f === 'all' || (f === 'done') === t.done);
  });
  readonly remaining = computed(() => this._todos().filter(t => !t.done).length);

  // --- updaters (immutable replacements) ---
  setFilter(f: 'all' | 'active' | 'done') { this._filter.set(f); }
  toggle(id: number) {
    this._todos.update(list => list.map(t => t.id === id ? { ...t, done: !t.done } : t));
  }
  add(title: string) {
    this._todos.update(list => [...list, { id: Date.now(), title, done: false }]);
  }

  // --- async loading ---
  async load() {
    this._loading.set(true);
    try {
      const data = await firstValueFrom(this.http.get<Todo[]>('/api/todos'));
      this._todos.set(data);
    } finally {
      this._loading.set(false);   // always clears, even on error
    }
  }
}
```

**Why this shape:** `asReadonly()` enforces that mutation only happens through the store's methods (unidirectional flow); `computed` selectors recompute only when their dependencies actually change and never produce glitches; `update` with spread (`[...list]`, `{...t}`) keeps state immutable so even `OnPush` consumers and signal reads behave correctly. **Edge cases:** the `finally` block guarantees `loading` resets on error; using `Date.now()` for ids is a demo shortcut (use server ids in production). **When to graduate to NgRx SignalStore:** when you want `withEntities`, `rxMethod`, or DevTools time-travel — but for most features this hand-rolled store is sufficient and clearer. **Complexity:** selectors are O(n) over the list, recomputed lazily.

#### Q109. [Coding] Build a custom structural directive (`*appRepeat` or `*appUnless`) and explain the microsyntax and context object.

**Problem:** Implement `*appUnless="condition"` (the inverse of `*ngIf`) and `*appRepeat="n"` that stamps a template n times with an index — showing how structural directives use `TemplateRef`, `ViewContainerRef`, and context.

A structural directive is just a directive that injects a `TemplateRef` (the `<ng-template>` the `*` desugars into) and a `ViewContainerRef` (where to stamp views), then creates or clears embedded views. The `*x="expr"` syntax is **microsyntax sugar** for `<ng-template [x]="expr">`. A context object passed to `createEmbeddedView` is what powers `let`-variables like `let i = index`.

```typescript
@Directive({ selector: '[appUnless]' })
export class UnlessDirective {
  private tpl = inject(TemplateRef<unknown>);
  private vcr = inject(ViewContainerRef);
  private hasView = false;

  @Input() set appUnless(condition: boolean) {
    if (!condition && !this.hasView) {
      this.vcr.createEmbeddedView(this.tpl);   // show
      this.hasView = true;
    } else if (condition && this.hasView) {
      this.vcr.clear();                         // hide
      this.hasView = false;
    }
  }
}

@Directive({ selector: '[appRepeat]' })
export class RepeatDirective {
  private tpl = inject(TemplateRef<{ $implicit: number; index: number }>);
  private vcr = inject(ViewContainerRef);

  @Input() set appRepeat(count: number) {
    this.vcr.clear();
    for (let i = 0; i < count; i++) {
      // $implicit is the value bound to `let x`; named keys back `let i = index`.
      this.vcr.createEmbeddedView(this.tpl, { $implicit: i, index: i });
    }
  }
}
```

```html
<p *appUnless="isLoggedIn">Please log in.</p>
<div *appRepeat="3; let i = index">Row {{ i }}</div>   <!-- stamps 3 divs -->
```

**Key concepts to articulate:** `$implicit` is the default context value (`let x` binds to it); other context keys back `let alias = key`; clearing and recreating is the naive approach (the real `@for` reuses views via `track` for performance). **Edge cases:** guard against re-creating an existing view (the `hasView` flag) so toggling doesn't stack duplicate views; handle `count` changing downward. **Why this still matters with built-in control flow:** `@if`/`@for` cover the common cases, but genuinely custom stamping logic (a permission-gated repeater, a windowing directive) still warrants a structural directive. **Complexity:** O(count) to stamp. Understanding the desugaring is what separates "I use `*ngIf`" from "I understand how it works."

#### Q110. [Coding] Implement an `afterRenderEffect`/`afterNextRender`-based integration with a non-Angular layout library that must measure the DOM. Avoid hydration and CD pitfalls.

**Problem:** A masonry/grid library needs to measure element widths after render and re-layout when data changes. Integrate it so it runs only in the browser, doesn't trigger CD storms, and survives SSR.

DOM-measuring libraries are a poster child for `afterRender`/`afterNextRender` (v16+) and the newer `afterRenderEffect` (v19+). These hooks run **only in the browser** and **after Angular has written the DOM**, which is exactly when measurements are valid and is safe for SSR (they're skipped on the server). They also avoid the `ExpressionChangedAfterItHasBeenCheckedError` you'd get from measuring in `ngAfterViewInit` and writing back state.

```typescript
@Component({
  selector: 'app-masonry',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #grid class="grid">
    @for (item of items(); track item.id) { <div class="cell">{{ item.label }}</div> }
  </div>`,
})
export class MasonryComponent {
  private grid = viewChild.required<ElementRef<HTMLElement>>('grid');
  items = input.required<Item[]>();
  private layout?: MasonryLib;

  constructor() {
    // Runs once, browser-only, after first paint — safe place to instantiate.
    afterNextRender(() => {
      this.layout = new MasonryLib(this.grid().nativeElement);
      inject(DestroyRef).onDestroy(() => this.layout?.destroy());
    });

    // afterRenderEffect: re-runs in the browser after render whenever items() changes,
    // reading layout-relevant signals and re-measuring without triggering CD writes.
    afterRenderEffect(() => {
      this.items();                          // track dependency
      this.layout?.reflow();                 // imperative re-measure + position
    });
  }
}
```

**Why each hook:** `afterNextRender` for one-time browser-only init (DOM exists, no SSR crash); `afterRenderEffect` for reactive post-render work that depends on signals — it runs in the render phase, after the DOM is committed, so measurements are accurate and it doesn't feed back into the same CD pass. **Pitfalls avoided:** instantiating in the constructor or `ngOnInit` would crash on the server (no DOM); measuring in `ngAfterViewChecked` and setting a signal would loop or throw `ExpressionChanged`; forgetting `destroy()` leaks the library's observers. **Edge cases:** if the library uses `ResizeObserver`, register and disconnect it in the same `DestroyRef` cleanup. **Complexity:** the reflow is library-dependent (often O(n)); it runs only on actual data changes thanks to the effect's dependency tracking.

#### Q111. [Coding] Write an interceptor + service that deduplicates concurrent identical GET requests and caches responses with TTL.

**Problem:** Multiple components request `/api/config` at once on load; you want a single network call shared by all, plus a short-lived cache so a refresh within the TTL is served from memory.

This is the "request dedup + cache" pattern. In-flight dedup uses `shareReplay` so concurrent subscribers share one HTTP execution; the TTL cache stores the shared observable keyed by URL and invalidates after a window. Doing this in a functional interceptor makes it transparent to all callers.

```typescript
interface CacheEntry { obs$: Observable<HttpEvent<unknown>>; expires: number; }

export function cachingInterceptor(): HttpInterceptorFn {
  const cache = new Map<string, CacheEntry>();
  const TTL_MS = 30_000;

  return (req, next) => {
    if (req.method !== 'GET') return next(req);     // only cache idempotent GETs

    const key = req.urlWithParams;
    const now = Date.now();
    const hit = cache.get(key);
    if (hit && hit.expires > now) return hit.obs$;  // serve from cache / share in-flight

    const shared$ = next(req).pipe(
      shareReplay({ bufferSize: 1, refCount: false }),  // dedup concurrent + replay to latecomers
    );
    cache.set(key, { obs$: shared$, expires: now + TTL_MS });

    // On error, evict immediately so a failure isn't cached for the full TTL.
    return shared$.pipe(catchError(err => { cache.delete(key); return throwError(() => err); }));
  };
}

// Registration:
provideHttpClient(withInterceptors([cachingInterceptor()]));
```

**Design rationale:** caching only GETs respects HTTP semantics (never cache POST/PUT); `shareReplay({ bufferSize: 1 })` means N concurrent subscribers trigger **one** request and all get the result, and a late subscriber within TTL gets the replayed value; evicting on error prevents poisoning the cache with a transient failure. **Trade-off — `refCount`:** `refCount: false` keeps the cached value alive for the TTL even with no current subscribers (the desired caching behavior); `refCount: true` would tear down when subscribers drop, defeating the cache. **Edge cases:** the cache `Map` grows unbounded — add an eviction sweep or `Map` size cap for long-lived apps; consider varying the key by relevant headers if responses depend on them. **Complexity:** O(1) lookup. For richer needs (stale-while-revalidate, invalidation), Angular's built-in `withHttpTransferCacheOptions` or a query library is the next step.

#### Q112. [Coding] Implement keyboard-accessible drag-and-drop reordering of a list using the Angular CDK.

**Problem:** Build a reorderable list with `@angular/cdk/drag-drop` that updates a signal-backed array and remains operable. Show the handler and explain `moveItemInArray`.

The CDK's `DragDropModule` provides `cdkDropList`/`cdkDrag` directives and the `moveItemInArray` helper. The clean pattern is to keep the source of truth in a signal and apply the reorder immutably in the `cdkDropListDropped` handler. The CDK handles the pointer mechanics, drop zones, and placeholder; you handle the data mutation.

```typescript
@Component({
  selector: 'app-reorder',
  imports: [DragDropModule],
  template: `
    <div cdkDropList (cdkDropListDropped)="drop($event)" class="list">
      @for (task of tasks(); track task.id) {
        <div cdkDrag class="item">
          <span cdkDragHandle aria-label="Drag to reorder">⠿</span>
          {{ task.title }}
          <div class="placeholder" *cdkDragPlaceholder></div>
        </div>
      }
    </div>
  `,
})
export class ReorderComponent {
  tasks = signal<Task[]>([
    { id: 1, title: 'Design' }, { id: 2, title: 'Build' }, { id: 3, title: 'Ship' },
  ]);

  drop(event: CdkDragDrop<Task[]>) {
    if (event.previousIndex === event.currentIndex) return;
    this.tasks.update(list => {
      const copy = [...list];                          // immutable update
      moveItemInArray(copy, event.previousIndex, event.currentIndex);
      return copy;
    });
  }
}
```

**Why immutable update:** mutating the array in place (`moveItemInArray` on the live signal value) wouldn't change the reference, so `OnPush`/signal consumers might not see the change reliably — copying then setting guarantees a new reference. **Accessibility note:** drag-and-drop is inherently mouse-centric; the CDK provides the `cdkDragHandle` and ARIA hooks, but full keyboard reordering requires you to also offer move-up/move-down buttons (calling the same reorder logic) for keyboard/screen-reader users — DnD alone is not accessible. **Edge cases:** no-op when `previousIndex === currentIndex`; for transferring between lists use `transferArrayItem`; `track task.id` keeps animations smooth. **Complexity:** `moveItemInArray` is O(n) for the splice. This question tests both CDK fluency and the rarely-mentioned a11y caveat.

### 🔴 Expert — extended

#### Q113. [Coding] Implement a finite state machine for a multi-step checkout wizard using signals, with guarded transitions and derived UI.

**Problem:** Model a checkout flow (cart → shipping → payment → review → done) as an explicit FSM where transitions are guarded (can't reach payment without valid shipping), the current step and allowed actions are derived signals, and back/forward navigation is safe.

Modeling a wizard as an explicit FSM (rather than ad-hoc booleans and `currentStep++`) eliminates illegal states and makes the allowed transitions auditable. Signals are an ideal substrate: the state is a writable signal, transitions are methods that validate before setting, and the UI (which step to show, whether "Next" is enabled) is a set of `computed` derivations.

```typescript
type Step = 'cart' | 'shipping' | 'payment' | 'review' | 'done';

const TRANSITIONS: Record<Step, Step[]> = {
  cart:     ['shipping'],
  shipping: ['cart', 'payment'],
  payment:  ['shipping', 'review'],
  review:   ['payment', 'done'],
  done:     [],
};

@Injectable()
export class CheckoutMachine {
  private _step = signal<Step>('cart');
  private _data = signal<Partial<CheckoutData>>({});

  readonly step = this._step.asReadonly();
  readonly data = this._data.asReadonly();

  // Derived UI state
  readonly canGoNext = computed(() => this.validate(this._step()));
  readonly nextSteps = computed(() => TRANSITIONS[this._step()]);
  readonly progress = computed(() =>
    (['cart', 'shipping', 'payment', 'review', 'done'].indexOf(this._step()) / 4) * 100);

  patch(d: Partial<CheckoutData>) { this._data.update(v => ({ ...v, ...d })); }

  go(target: Step): boolean {
    const allowed = TRANSITIONS[this._step()].includes(target);
    // Guard: forward transitions require the current step to be valid.
    const forward = target !== 'cart' && target !== 'shipping' || this._step() < target;
    if (!allowed) return false;
    if (this.isForward(target) && !this.validate(this._step())) return false;
    this._step.set(target);
    return true;
  }

  private isForward(target: Step): boolean {
    const order: Step[] = ['cart', 'shipping', 'payment', 'review', 'done'];
    return order.indexOf(target) > order.indexOf(this._step());
  }

  private validate(step: Step): boolean {
    const d = this._data();
    switch (step) {
      case 'cart':     return (d.items?.length ?? 0) > 0;
      case 'shipping': return !!d.address;
      case 'payment':  return !!d.paymentToken;
      default:         return true;
    }
  }
}
```

**Why this is robust:** the `TRANSITIONS` table is the single source of truth for legal moves — you cannot jump from `cart` to `payment`; `go()` returns `false` on illegal/guard-failed transitions so the caller can show feedback; backward navigation is always allowed (you can revisit shipping) but forward requires validation. **Derived UI** (`canGoNext`, `progress`) is pure `computed`, so the template never recomputes guard logic manually. **Edge cases:** providing the machine at the **component level** (`@Injectable()` + component `providers`) scopes one machine per wizard instance — using `providedIn: 'root'` would leak one user's checkout into the next. **Complexity:** O(1) transitions, O(steps) for progress. This demonstrates senior-level state modeling, not just signal syntax.

#### Q114. [Coding] Build a typed, generic, reusable data-table component with sortable columns and signal-driven sorting/pagination.

**Problem:** Design `<app-table [rows] [columns]>` that is generic over the row type `T`, supports clicking a column header to sort, and paginates — all with signals and strong typing.

A generic component (`class TableComponent<T>`) gives compile-time safety to consumers: column keys are constrained to `keyof T`, and cell rendering is type-checked. Sorting and pagination state live as signals; the displayed slice is a `computed` chain (sort → page), recomputed only when inputs or state change. This is a realistic "design a reusable library component" exercise.

```typescript
interface Column<T> { key: keyof T; label: string; sortable?: boolean; }

@Component({
  selector: 'app-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <table>
      <thead><tr>
        @for (col of columns(); track col.key) {
          <th (click)="col.sortable && toggleSort(col.key)"
              [class.sortable]="col.sortable">
            {{ col.label }}
            @if (sortKey() === col.key) { <span>{{ sortDir() === 'asc' ? '▲' : '▼' }}</span> }
          </th>
        }
      </tr></thead>
      <tbody>
        @for (row of pageRows(); track trackRow(row)) {
          <tr>@for (col of columns(); track col.key) { <td>{{ row[col.key] }}</td> }</tr>
        }
      </tbody>
    </table>
    <button (click)="prev()" [disabled]="page() === 0">‹</button>
    <span>{{ page() + 1 }} / {{ totalPages() }}</span>
    <button (click)="next()" [disabled]="page() >= totalPages() - 1">›</button>
  `,
})
export class TableComponent<T extends { id: string | number }> {
  rows = input.required<readonly T[]>();
  columns = input.required<readonly Column<T>[]>();
  pageSize = input(10);

  sortKey = signal<keyof T | null>(null);
  sortDir = signal<'asc' | 'desc'>('asc');
  page = signal(0);

  private sorted = computed(() => {
    const key = this.sortKey();
    const data = [...this.rows()];
    if (!key) return data;
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    return data.sort((a, b) => (a[key] < b[key] ? -1 : a[key] > b[key] ? 1 : 0) * dir);
  });

  totalPages = computed(() => Math.max(1, Math.ceil(this.rows().length / this.pageSize())));
  pageRows = computed(() => {
    const start = this.page() * this.pageSize();
    return this.sorted().slice(start, start + this.pageSize());
  });

  toggleSort(key: keyof T) {
    if (this.sortKey() === key) this.sortDir.update(d => (d === 'asc' ? 'desc' : 'asc'));
    else { this.sortKey.set(key); this.sortDir.set('asc'); }
    this.page.set(0);     // reset to first page on re-sort
  }
  trackRow = (r: T) => r.id;
  next() { this.page.update(p => Math.min(p + 1, this.totalPages() - 1)); }
  prev() { this.page.update(p => Math.max(0, p - 1)); }
}
```

**Why this design:** the generic constraint `T extends { id }` guarantees a stable `track`; `Column<T>` ties `key` to `keyof T` so a typo (`key: 'naem'`) is a compile error; the `sorted` → `pageRows` computed chain is glitch-free and memoized. **Edge cases:** `[...this.rows()]` before sorting avoids mutating the input array (mutating it would break `OnPush` callers and is a side effect in a `computed`); resetting `page` on sort prevents landing on an out-of-range page; `totalPages` floors at 1 for empty data. **Trade-off:** in-memory sort/paginate suits modest datasets; for thousands of rows or server data, accept sort/page as outputs and let the parent fetch. **Complexity:** sort is O(n log n) recomputed only on relevant changes; slice is O(pageSize). Generic + signals + OnPush is the modern reusable-component blueprint.

#### Q115. [Coding] Diagnose and fix a subtle `shareReplay` memory leak in a "smart cache" service that retains stale data forever.

**Problem:** A service caches per-id HTTP results with `shareReplay(1)`. Over a long session the heap grows and old entities never get collected. Show the buggy code, explain the leak, and fix it.

The bug is the classic `shareReplay(1)` (legacy signature) / `shareReplay({ refCount: false })` trap combined with an unbounded cache `Map`. `shareReplay` with `refCount: false` **subscribes to the source and never unsubscribes**, holding the last emitted value (and the upstream subscription) alive forever — even after every consumer has unsubscribed. Stored in a `Map` keyed by id, every entity ever fetched is retained for the life of the app.

```typescript
// --- BUGGY ---
@Injectable({ providedIn: 'root' })
export class EntityCache {
  private http = inject(HttpClient);
  private cache = new Map<string, Observable<Entity>>();

  get(id: string): Observable<Entity> {
    if (!this.cache.has(id)) {
      this.cache.set(id,
        this.http.get<Entity>(`/api/e/${id}`).pipe(shareReplay(1)));  // ← leaks: refCount false, never evicted
    }
    return this.cache.get(id)!;
  }
}
```

The leak has two compounding causes: (1) `shareReplay(1)` keeps an active subscription to the (completed) HTTP source and pins the cached value; (2) the `Map` grows without bound and nothing ever removes entries. Even though `HttpClient` completes, the replayed buffer and the entry in the `Map` are retained.

```typescript
// --- FIXED ---
@Injectable({ providedIn: 'root' })
export class EntityCache {
  private http = inject(HttpClient);
  private cache = new Map<string, { obs$: Observable<Entity>; expires: number }>();
  private readonly TTL = 60_000;
  private readonly MAX = 200;

  get(id: string): Observable<Entity> {
    const now = Date.now();
    const hit = this.cache.get(id);
    if (hit && hit.expires > now) return hit.obs$;

    const obs$ = this.http.get<Entity>(`/api/e/${id}`).pipe(
      shareReplay({ bufferSize: 1, refCount: true }),   // tears down when no subscribers
    );
    this.evictIfNeeded();
    this.cache.set(id, { obs$, expires: now + this.TTL });
    return obs$;
  }

  private evictIfNeeded() {
    const now = Date.now();
    for (const [k, v] of this.cache) if (v.expires <= now) this.cache.delete(k);   // TTL sweep
    if (this.cache.size >= this.MAX) {                                              // LRU-ish cap
      const oldest = this.cache.keys().next().value;
      if (oldest) this.cache.delete(oldest);
    }
  }
}
```

**The two-part fix:** `refCount: true` makes `shareReplay` unsubscribe from the source when the subscriber count hits zero (no dangling subscription); the **TTL + size cap** bounds the `Map` so the cache can't grow forever. **Trade-off:** `refCount: true` means if all subscribers leave and a new one arrives after the buffer is gone, the request re-fires — usually acceptable, and the TTL governs freshness anyway. **Diagnosis method:** Chrome heap snapshots taken over time show growing retained `Subscriber`/`ReplaySubject` instances and `Map` entries; comparing snapshots pinpoints the retainer chain back to `shareReplay`. **Complexity:** O(cache size) on the periodic sweep. This is one of the most common real-world Angular leaks and a favorite expert question.

#### Q116. [Coding] Write deterministic tests for signal state, a debounced RxJS pipeline (`fakeAsync`/`tick`), and a component output.

**Problem:** Show how to test (a) a computed signal updating when its source changes, (b) a debounced search emitting only after the debounce window, and (c) a component emitting an output — all deterministically.

Testing modern Angular means three distinct techniques: signals are **synchronous**, so you `set()` and assert immediately (no `whenStable`); time-based RxJS needs `fakeAsync` + `tick()` to control virtual time deterministically; outputs are tested by subscribing before the action and asserting the emitted value. Determinism is the goal — no real timers, no flaky waits.

```typescript
// (a) Signal + computed — synchronous, no fixture needed for pure logic
it('recomputes total when items change', () => {
  const store = new TodoStore();          // plain class
  store.add('a'); store.add('b');
  expect(store.remaining()).toBe(2);
  store.toggle([...store.todos()][0].id);
  expect(store.remaining()).toBe(1);      // computed updated synchronously
});

// (b) Debounced pipeline — fakeAsync controls virtual time
it('emits search results only after debounce', fakeAsync(() => {
  const api = { search: jasmine.createSpy().and.returnValue(of([{ id: 1, name: 'x' }])) };
  const query$ = new Subject<string>();
  const results: any[] = [];
  query$.pipe(debounceTime(300), switchMap(q => api.search(q))).subscribe(r => results.push(r));

  query$.next('ab');
  tick(299);                 // before the window: nothing yet
  expect(api.search).not.toHaveBeenCalled();
  tick(1);                   // crosses 300ms boundary
  expect(api.search).toHaveBeenCalledOnceWith('ab');
  expect(results.length).toBe(1);
}));

// (c) Component output — subscribe before triggering
it('emits changed when incremented', () => {
  const fixture = TestBed.createComponent(CounterComponent);
  const emitted: number[] = [];
  fixture.componentInstance.changed.subscribe(v => emitted.push(v));
  fixture.componentInstance.inc();
  expect(emitted).toEqual([1]);
});
```

**Why each technique:** signals are pull-based and synchronous, so testing them is just call-and-assert — no change detection needed for the logic itself, which makes signal stores delightfully testable. `fakeAsync`/`tick` replaces real timers with a virtual clock, so `debounceTime(300)` is tested precisely at the 299ms/300ms boundary with zero flakiness (never use real `setTimeout` in tests). Outputs (`OutputEmitterRef` or `EventEmitter`) are observables — subscribe first, act, assert. **Edge cases:** `fakeAsync` throws if a timer remains pending at the end — call `flush()` or `discardPeriodicTasks()` for intervals; for signal-in-component tests that read in a template, call `fixture.detectChanges()` and assert on the DOM. **Complexity:** trivial; the value is determinism. Demonstrating all three signals deep testing competence.

#### Q117. [Coding] Implement an optimistic-update pattern with rollback for a "like" button, handling concurrent clicks and server failure.

**Problem:** Clicking "like" should update the UI immediately (optimistic), send the request, and **roll back** if the server fails — while correctly handling rapid double-clicks and a server that returns the authoritative count.

Optimistic updates improve perceived performance but require disciplined rollback and concurrency handling. The pattern: capture the previous state, apply the optimistic change immediately, fire the request, and on error restore the captured state. Rapid clicks need either debouncing/`exhaustMap` (ignore while in flight) or last-write-wins reconciliation with the server's authoritative value.

```typescript
@Component({
  selector: 'app-like',
  template: `
    <button (click)="toggle()" [disabled]="pending()">
      {{ liked() ? '♥' : '♡' }} {{ count() }}
    </button>
  `,
})
export class LikeComponent {
  private api = inject(LikeService);
  postId = input.required<string>();

  liked = signal(false);
  count = signal(0);
  pending = signal(false);

  toggle() {
    if (this.pending()) return;                  // guard concurrent clicks (or use exhaustMap)
    const prevLiked = this.liked();
    const prevCount = this.count();

    // 1) Optimistic update — instant UI feedback
    this.liked.set(!prevLiked);
    this.count.update(c => c + (prevLiked ? -1 : 1));
    this.pending.set(true);

    // 2) Persist; reconcile or roll back
    this.api.setLike(this.postId(), this.liked())
      .pipe(takeUntilDestroyed(inject(DestroyRef)), finalize(() => this.pending.set(false)))
      .subscribe({
        next: (authoritative) => this.count.set(authoritative.count),  // trust server count
        error: () => {                                                  // 3) rollback
          this.liked.set(prevLiked);
          this.count.set(prevCount);
        },
      });
  }
}
```

**Design decisions:** the `pending` guard (or `exhaustMap` in a stream version) prevents a second click from firing before the first resolves, which would corrupt the count; capturing `prevLiked`/`prevCount` enables exact rollback; trusting the server's `authoritative.count` on success reconciles any drift (e.g., other users liked concurrently); `finalize` guarantees `pending` clears on both success and error. **Edge cases:** if you allow queued toggles instead of blocking, use `switchMap` so only the latest intent wins, and reconcile with the server's final value; network timeout should be treated as error (rollback) but consider a retry. **Why rollback matters:** without it, a failed request leaves the UI lying about state — the cardinal sin of optimistic UIs. **Complexity:** O(1). This tests real product-engineering judgment, not just framework API.

#### Q118. [Coding] Write a route resolver vs reactive-load comparison, then implement a non-blocking pattern with `rxResource` and a skeleton.

**Problem:** Compare blocking resolvers against navigating immediately and loading reactively, then implement the non-blocking approach so the route activates instantly and shows a skeleton while data streams in.

Resolvers **block navigation** until data arrives — the URL doesn't change and the user stares at the old page (or nothing) during the fetch, which feels sluggish on slow networks. The modern preference is to navigate immediately and load data reactively in the component, showing a skeleton, so the app feels instant and the loading state is explicit. `rxResource` (v19+) makes this clean: it derives a request from route params and exposes loading/value/error as signals.

```typescript
// --- Blocking resolver (the thing to often avoid) ---
export const userResolver: ResolveFn<User> = (route) =>
  inject(UserService).getUser(route.paramMap.get('id')!);   // navigation waits for this

// --- Preferred: navigate now, load reactively in the component ---
@Component({
  selector: 'app-profile',
  template: `
    @if (userRes.isLoading()) { <app-skeleton /> }
    @else if (userRes.error()) { <p>Couldn't load. <button (click)="userRes.reload()">Retry</button></p> }
    @else if (userRes.value(); as u) { <h1>{{ u.name }}</h1><p>{{ u.bio }}</p> }
  `,
})
export class ProfileComponent {
  private http = inject(HttpClient);
  private route = inject(ActivatedRoute);

  // Route param as a signal, then resource auto-reloads when it changes.
  private id = toSignal(this.route.paramMap.pipe(map(p => p.get('id')!)), { initialValue: '' });

  userRes = rxResource({
    request: () => ({ id: this.id() }),
    loader: ({ request }) => this.http.get<User>(`/api/users/${request.id}`),
  });
}
```

**Trade-off analysis:** resolvers are justified when the page is **meaningless without the data** (e.g., you must 404 before showing anything, or SEO needs full server-rendered content) — there a brief block is correct, and `runGuardsAndResolvers` controls re-resolution. For everything else, reactive loading wins on perceived performance: the route transition is instant, the skeleton communicates progress, errors are handled in-context with retry, and `rxResource` auto-cancels the stale request when the id changes (navigating between profiles). **Edge cases:** seed `id` with `initialValue` so the resource doesn't fire with `undefined`; `reload()` gives a retry affordance; for SSR, a resolver may still be preferable so the server renders complete HTML. **Complexity:** O(1) state transitions. The senior insight is *when* to block vs not — not just how to call each API.

#### Q119. [Behavioral] Tell me about a time you led the team through a high-risk Angular framework upgrade or architectural migration under business pressure. (STAR)

(Structure with STAR; this targets staff-level judgment, stakeholder management, and risk control.)

**Situation:** We owned a 350k-LOC Angular v12, NgModule-heavy trading dashboard. We were three majors behind, blocked from adopting signals and `@defer`, and a critical security advisory in a transitive dependency could only be cleanly resolved by upgrading the framework. Leadership wanted it done but the product roadmap had zero slack, and a previous "big-bang upgrade" attempt by another team had been rolled back after a week of production incidents.

**Task:** I was asked to lead the upgrade to a modern version (v17+) and the standalone/signals migration **without freezing feature work and without a repeat of the failed big-bang**. The implicit task was as much about **de-risking and earning trust** as about the technical work.

**Action:** I refused the big-bang and proposed an **incremental, ship-while-you-migrate** plan. (1) Upgraded **one major at a time** using `ng update` and the official migrations, never skipping, with the full E2E and visual-regression suite gating each step. (2) Ran the **standalone schematic** feature-by-feature behind the fact that NgModules and standalone interoperate, so partially-migrated code kept shipping. (3) Adopted **control-flow and `inject()` migrations** (purely mechanical, low risk) broadly. (4) Introduced **signals opportunistically** — new code only, plus hot components — bridging RxJS with `toSignal`. (5) Deliberately deferred **zoneless** to last, behind a feature flag, because it's the riskiest. I tracked the migration as a **measurable backlog** (percent standalone, percent OnPush/signal) on a dashboard leadership could see, added **bundle and a11y budgets** in CI to prevent regressions, and instituted a rule that every migration PR had to be independently revertable. I also negotiated a **10% time allocation** rather than a freeze, framing it to stakeholders as "continuous insurance" rather than a stop-the-world cost.

**Result:** We reached the target version in about four months with **zero migration-caused production incidents** (vs the prior rollback), shipped features the entire time, resolved the security advisory, and unlocked signals/`@defer` — a later `@defer` pass cut initial bundle ~28%. The visible dashboard turned a scary, open-ended migration into a predictable burn-down that leadership trusted.

**Lesson:** For large, high-stakes migrations the technical strategy (incremental, automated, reversible, measured) and the **stakeholder strategy** (reframe cost as insurance, make progress visible, build trust after a prior failure) are equally important. The staff-level move was *refusing the big-bang* even under pressure to "just do it fast," because the failure mode of speed here was far more expensive than the slower, safer path — and being able to articulate that trade-off to non-engineers is what made it acceptable.

#### Q120. [Coding] Implement a type-safe event-bus / message service with discriminated-union events and signal-based subscription.

**Problem:** Build a cross-component event bus where event types are statically checked, subscribers only receive events they care about, and consumers can read the latest event of a type as a signal.

A type-safe bus uses a **discriminated union** for events and generics to narrow by `type`, so `on('user.login')` yields a strongly-typed payload and a typo is a compile error. Backing it with RxJS for the stream and offering a `toSignal` view gives both push (subscribe) and pull (read latest) ergonomics. This is a realistic alternative to over-globalizing state in NgRx for genuinely event-shaped cross-cutting concerns.

```typescript
// 1) Strongly-typed event catalog (discriminated union)
type AppEvent =
  | { type: 'user.login'; userId: string }
  | { type: 'cart.add'; productId: string; qty: number }
  | { type: 'toast'; level: 'info' | 'error'; message: string };

type EventOf<K extends AppEvent['type']> = Extract<AppEvent, { type: K }>;

@Injectable({ providedIn: 'root' })
export class EventBus {
  private subject = new Subject<AppEvent>();

  emit(event: AppEvent): void { this.subject.next(event); }   // payload type-checked at call site

  // Push API: stream filtered & narrowed to a single event type.
  on<K extends AppEvent['type']>(type: K): Observable<EventOf<K>> {
    return this.subject.pipe(
      filter((e): e is EventOf<K> => e.type === type),   // type guard narrows the union
    );
  }

  // Pull API: latest event of a type as a signal (undefined until first emit).
  signalOf<K extends AppEvent['type']>(type: K): Signal<EventOf<K> | undefined> {
    return toSignal(this.on(type), { initialValue: undefined });
  }
}

// Usage — fully typed:
bus.emit({ type: 'cart.add', productId: 'p1', qty: 2 });     // ✓
// bus.emit({ type: 'cart.add', productId: 'p1' });          // ✗ compile error: qty missing
bus.on('user.login').subscribe(e => console.log(e.userId));  // e is { type:'user.login'; userId:string }
const lastToast = bus.signalOf('toast');                     // Signal<{level;message}|undefined>
```

**Why this design:** the discriminated union makes `emit` reject malformed events at compile time; the `EventOf<K>` mapped type plus the `filter` type guard means `on('user.login')` is typed precisely, so `e.userId` is available with no cast; offering both an Observable (`on`) and a Signal (`signalOf`) lets consumers pick push or pull. **Caveats / when not to use:** an event bus can become a **hidden-coupling dumping ground** — overusing it recreates the "everything talks to everything" problem DI and explicit inputs/outputs solve cleanly; reserve it for genuinely cross-cutting, decoupled notifications (toasts, analytics, session events), not for parent-child data flow (use inputs/outputs/signals there). **Edge cases:** `Subject` (not `BehaviorSubject`) means late subscribers miss past events — `signalOf` mitigates by caching the latest; consider `takeUntilDestroyed` on subscribers. **Complexity:** O(subscribers) per emit. This tests advanced TypeScript *and* architectural restraint.

#### Q121. [Coding] Implement SSR-safe `localStorage`-backed persistence for a signal, with hydration-mismatch avoidance.

**Problem:** Create a `persistedSignal(key, default)` helper that reads/writes `localStorage`, works under SSR (no `localStorage` on the server), and never causes a hydration mismatch.

The hazard: `localStorage` doesn't exist on the server, and reading it during initial render would make the server output differ from the client (mismatch). The SSR-safe pattern is to **initialize with the default value on both server and client first render** (identical output → no mismatch), then hydrate the persisted value **after first render in the browser only** via `afterNextRender`, and persist subsequent changes with an `effect`.

```typescript
export function persistedSignal<T>(key: string, initial: T): WritableSignal<T> {
  const sig = signal<T>(initial);
  const platformId = inject(PLATFORM_ID);

  if (isPlatformBrowser(platformId)) {
    // Read persisted value AFTER first render → server & client first paint match (no mismatch).
    afterNextRender(() => {
      try {
        const raw = localStorage.getItem(key);
        if (raw !== null) sig.set(JSON.parse(raw) as T);
      } catch { /* ignore corrupt/blocked storage */ }
    });

    // Persist on every change (browser only).
    effect(() => {
      try { localStorage.setItem(key, JSON.stringify(sig())); }
      catch { /* quota / private-mode: fail silently */ }
    });
  }

  return sig;
}

// Usage in a component (must run in an injection context):
export class ThemeService {
  theme = persistedSignal<'light' | 'dark'>('theme', 'light');
  toggle() { this.theme.update(t => (t === 'dark' ? 'light' : 'dark')); }
}
```

**Why this avoids mismatch:** the signal starts at `initial` on the server **and** on the client's first render, so the hydrated DOM matches the server DOM exactly; only *after* `afterNextRender` (client-only, post-hydration) does it swap to the persisted value, which Angular treats as a normal subsequent update, not a hydration discrepancy. **Edge cases:** wrap `localStorage` access in try/catch (Safari private mode throws, quota can be exceeded, JSON can be corrupt); guard with `isPlatformBrowser` so the server path is a no-op; must be called in an injection context (field initializer / constructor) for `inject` and `effect` to work. **Subtle UX note:** there *will* be a one-frame flash from default → persisted value after hydration (e.g., a brief light theme before dark) — acceptable for most cases, or mitigated with a server-side cookie read if the flash is unacceptable. **Complexity:** O(1) per read/write. This is a precise test of hydration understanding combined with signals and SSR-safe coding.

#### Q122. [Coding] Profile and fix a component whose template calls a method on every change detection. Show the before/after and quantify the win.

**Problem:** A dashboard's template calls `getFormattedRows()` and `isHighlighted(row)` directly; the page janks. Diagnose why and refactor to eliminate per-CD work, quantifying the improvement.

Calling a method in a template (`{{ getFormattedRows() }}`) means Angular **re-invokes it on every change-detection cycle**, because it can't know whether the result changed — and under default CD or frequent events that's dozens to hundreds of calls per second. If the method does any non-trivial work (mapping, filtering, formatting), it dominates the frame budget. The fix is to **derive the value once** in a `computed()` signal (recomputed only when dependencies change) and reference it as `value()` — not `value` invocation of a method.

```typescript
// --- BEFORE: method calls run every CD cycle ---
@Component({
  template: `
    @for (row of getFormattedRows(); track row.id) {       <!-- called EVERY CD -->
      <tr [class.hot]="isHighlighted(row)">{{ row.label }}</tr>   <!-- called per-row, every CD -->
    }`,
})
export class DashboardBefore {
  rows: Row[] = [];
  threshold = 100;
  getFormattedRows() { return this.rows.map(r => ({ ...r, label: format(r) })); }  // O(n) every CD
  isHighlighted(r: Row) { return r.value > this.threshold; }
}

// --- AFTER: derive once with computed; recomputes only when inputs change ---
@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (row of formattedRows(); track row.id) {          <!-- signal read, memoized -->
      <tr [class.hot]="row.hot">{{ row.label }}</tr>        <!-- precomputed flag -->
    }`,
})
export class DashboardAfter {
  rows = signal<Row[]>([]);
  threshold = signal(100);

  formattedRows = computed(() =>
    this.rows().map(r => ({ ...r, label: format(r), hot: r.value > this.threshold() })),
  );   // runs only when rows() or threshold() change
}
```

**Quantifying the win:** suppose 2,000 rows and a default-CD app that ticks ~30×/sec during interaction. Before: `getFormattedRows` runs 30×/sec × 2,000 = 60,000 `format` calls/sec, plus `isHighlighted` per row — easily blowing the 16ms frame budget and causing visible jank. After: `formattedRows` recomputes **only when `rows` or `threshold` actually change** (e.g., once on data load), so steady-state per-frame cost for this is ~0; the precomputed `hot` flag removes the per-row method call entirely. In Angular DevTools the component's per-CD time drops from tens of milliseconds to sub-millisecond. **Edge cases:** ensure `format` is pure; if `threshold` comes from an input, make it a signal input so the `computed` tracks it. **Rule to state:** *never invoke a method or non-trivial getter in a template* — use `computed`/signals or a pure pipe. **Complexity:** unchanged per actual recompute (O(n)), but the *frequency* drops from "every CD" to "every real change," which is the entire win.

#### Q123. [Coding] Implement a generic `@Input` transform and a required signal input with a custom transform/validator, and explain when transforms beat setters.

**Problem:** Accept an input that may arrive as a string or number from the DOM but should be stored as a number; also coerce a boolean attribute. Show signal-input transforms and contrast with the old setter approach.

Input transforms (decorator inputs since v16.1, and `transform` on signal `input()`) let you **normalize an input value at the binding boundary** without a getter/setter pair. This is cleaner than a setter because it keeps the value a plain field/signal (no backing field), runs only when the input changes, and is declarative. Angular ships `booleanAttribute` and `numberAttribute` transforms for the common DOM-coercion cases.

```typescript
import { Component, input, booleanAttribute, numberAttribute } from '@angular/core';

@Component({ selector: 'app-stepper', template: `<span>{{ step() }} / {{ max() }}</span>` })
export class StepperComponent {
  // numberAttribute: "5" (attribute string) → 5 (number)
  max = input(10, { transform: numberAttribute });

  // booleanAttribute: presence-as-true semantics, like native disabled
  disabled = input(false, { transform: booleanAttribute });

  // Required input with a CUSTOM transform/validator (clamp to >= 0)
  step = input.required<number, number | string>({
    transform: (v) => {
      const n = typeof v === 'string' ? parseFloat(v) : v;
      return isNaN(n) ? 0 : Math.max(0, Math.floor(n));   // normalize + validate
    },
  });
}
```

```html
<app-stepper max="20" disabled [step]="rawStep" />   <!-- "20"→20, presence→true, rawStep normalized -->
```

**Why transforms beat setters:** the old pattern was a private backing field plus a getter/setter that coerced in the setter — verbose, easy to get wrong (forgetting to store, or doing side effects in the setter), and it forced the value to be a class property with manual change tracking. A `transform` is a **pure function applied at the boundary**, the stored value stays a simple field/signal, and for signal inputs it composes with `computed`/`effect` reactively. **Note the dual generic** `input.required<number, number | string>`: the first type is the *stored/read* type, the second is the *accepted* input type — so the template can bind a string while consumers read a number. **Edge cases:** transforms must be **pure** (no side effects — they can run during CD); for `booleanAttribute`, `disabled="false"` becomes `true` because presence wins (matching native semantics), so don't pass string `"false"` expecting falsy. **Complexity:** O(1) per input change. Transforms are the modern, declarative answer to "normalize this input."

#### Q124. [Coding] Build a directive that lazy-loads and renders content only when it scrolls into view using `IntersectionObserver`, and compare to `@defer (on viewport)`.

**Problem:** Implement `*appLazyView` that defers rendering its template until the host scrolls into the viewport, using `IntersectionObserver`, with cleanup. Then explain when you'd use this versus the built-in `@defer (on viewport)`.

This is a structural directive that holds its `TemplateRef` un-rendered, observes a sentinel element with `IntersectionObserver`, and stamps the view on first intersection. It's a good demonstration of combining structural directives, browser APIs, SSR safety, and cleanup — and a useful contrast with `@defer`, which does the same *plus* code-splitting at the bundle level.

```typescript
@Directive({ selector: '[appLazyView]' })
export class LazyViewDirective implements OnInit {
  private tpl = inject(TemplateRef<unknown>);
  private vcr = inject(ViewContainerRef);
  private platformId = inject(PLATFORM_ID);
  private destroyRef = inject(DestroyRef);
  private observer?: IntersectionObserver;

  ngOnInit() {
    if (!isPlatformBrowser(this.platformId)) {
      this.vcr.createEmbeddedView(this.tpl);   // SSR: render eagerly so content is in server HTML
      return;
    }
    // Create a zero-size anchor to observe (the view isn't in the DOM yet).
    const anchor = this.vcr.createComponent(SentinelComponent).location.nativeElement as Element;
    this.observer = new IntersectionObserver((entries) => {
      if (entries.some(e => e.isIntersecting)) {
        this.vcr.clear();                       // remove the sentinel
        this.vcr.createEmbeddedView(this.tpl);  // render the real content
        this.observer?.disconnect();            // one-shot
      }
    }, { rootMargin: '200px' });                // prefetch a bit before fully visible
    this.observer.observe(anchor);
    this.destroyRef.onDestroy(() => this.observer?.disconnect());   // cleanup — no leak
  }
}
```

```html
<div *appLazyView>
  <expensive-widget [data]="data" />     <!-- not rendered until scrolled near viewport -->
</div>
```

**Comparison to `@defer (on viewport)`:** the directive only **delays rendering** — the `expensive-widget` code is still in the main bundle, so it saves render/CD work but **not download size**. `@defer (on viewport)` does both: it delays rendering *and* splits the deferred dependencies into a separate lazy chunk loaded on the trigger, so it reduces the initial JS payload too — strictly more powerful for performance. **So when use the directive?** when you need custom intersection logic the built-in triggers don't express (custom `rootMargin`/thresholds, conditional logic, integration with a specific library), or pre-v17 codebases. **Otherwise prefer `@defer`.** **Edge cases:** render eagerly under SSR (otherwise the content never appears in server HTML and hurts SEO); `rootMargin` for pre-loading; one-shot `disconnect` to avoid re-stamping; cleanup via `DestroyRef`. **Complexity:** O(1) per observed element. The interview value is knowing the directive *exists as a technique* but that `@defer` is the better default.

#### Q125. [Coding] Implement coordinated parallel data loading with partial-failure handling (`forkJoin` vs `combineLatest`), and surface per-source errors.

**Problem:** A dashboard needs three independent API calls (profile, stats, notifications) loaded in parallel. If one fails, the others should still render. Show the correct operator choice and per-source error isolation.

For **one-shot parallel loads that all complete**, `forkJoin` is the right operator — it waits for all sources to **complete** and emits their last values as a tuple/object. The trap: `forkJoin` **fails as a whole if any source errors** (and never emits if any source doesn't complete). To get partial-failure tolerance, each source must `catchError` to a sentinel so its failure doesn't poison the join. `combineLatest` is wrong here because it emits on *every* source emission and never naturally completes for HTTP — use it for live, continuously-updating combinations, not one-shot loads.

```typescript
interface DashboardData {
  profile: Profile | null;
  stats: Stats | null;
  notifications: Notification[];
}

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private http = inject(HttpClient);

  load(): Observable<DashboardData> {
    return forkJoin({
      // Each source isolates its own failure → partial failure doesn't kill the dashboard.
      profile: this.http.get<Profile>('/api/profile').pipe(catchError(() => of(null))),
      stats: this.http.get<Stats>('/api/stats').pipe(catchError(() => of(null))),
      notifications: this.http.get<Notification[]>('/api/notifications').pipe(catchError(() => of([]))),
    });
  }
}
```

```typescript
// Component — expose as a resource/signal and render what succeeded:
export class DashboardComponent {
  private svc = inject(DashboardService);
  data = toSignal(this.svc.load(), { initialValue: null });
}
```

```html
@if (data(); as d) {
  @if (d.profile) { <app-profile [profile]="d.profile" /> } @else { <p>Profile unavailable</p> }
  @if (d.stats)   { <app-stats [stats]="d.stats" /> }       @else { <p>Stats unavailable</p> }
  <app-notifications [items]="d.notifications" />
}
```

**Why this design:** `forkJoin` gives a single combined emission when all calls finish — ideal for a one-shot dashboard load — and the **per-source `catchError`** is the load-bearing detail: without it, one failing endpoint would reject the whole `forkJoin` and blank the entire dashboard. Returning typed sentinels (`null`, `[]`) lets the template render exactly the sections that succeeded. **`forkJoin` vs `combineLatest` vs `merge`:** `forkJoin` = wait-for-all-complete (one-shot loads); `combineLatest` = re-emit on any change (live dashboards/derived state); `merge` = interleave emissions (independent event streams). **Edge cases:** `forkJoin` **never emits** if any source completes without emitting — ensure each emits at least once (HTTP does); add `timeout()` per source if a hung endpoint shouldn't block the others. **Complexity:** O(1) coordination; bounded by the slowest call. Choosing the right combination operator and isolating failures is a frequent senior RxJS discriminator.

#### Q126. [Coding] Implement a custom `CanDeactivate` guard for unsaved form changes that works with both class and functional components.

**Problem:** Prevent navigating away from a form with unsaved edits, prompting the user to confirm. Implement a reusable functional `CanDeactivate` guard and the component contract it relies on.

`CanDeactivate` is the guard that runs when leaving a route — the ideal place for "you have unsaved changes" prompts. The clean, reusable pattern is a **generic functional guard** plus a small interface the component implements (`canDeactivate(): boolean | Observable<boolean>`), so the guard is decoupled from any specific component and works across the app.

```typescript
// 1) Component contract
export interface CanComponentDeactivate {
  canDeactivate(): boolean | Observable<boolean> | Promise<boolean>;
}

// 2) Reusable functional guard
export const unsavedChangesGuard: CanDeactivateFn<CanComponentDeactivate> = (component) => {
  // Defensive: components that don't implement the contract are always allowed to leave.
  return component.canDeactivate ? component.canDeactivate() : true;
};

// 3) A form component implementing the contract
@Component({ selector: 'app-edit', template: `<form [formGroup]="form">…</form>` })
export class EditComponent implements CanComponentDeactivate {
  private dialog = inject(ConfirmDialogService);
  form = inject(NonNullableFormBuilder).group({ name: [''], email: [''] });

  canDeactivate(): boolean | Observable<boolean> {
    if (this.form.pristine) return true;                 // nothing to lose → allow
    // Dirty → ask. Return the dialog's Observable<boolean>; router waits for it.
    return this.dialog.confirm('Discard unsaved changes?');
  }
}

// 4) Route wiring
const routes: Routes = [
  { path: 'edit/:id', component: EditComponent, canDeactivate: [unsavedChangesGuard] },
];
```

**Why this design:** the functional guard with a generic `CanDeactivateFn<T>` is the modern, tree-shakable style (class-based guards are deprecated); delegating the *decision* to the component (`canDeactivate()`) keeps domain knowledge — "is this form dirty?" — where it belongs, and the guard stays generic and reusable across many forms. Returning an `Observable<boolean>` lets the router **await an async confirmation dialog** before allowing or blocking navigation. **Edge cases:** use `form.pristine`/`form.dirty` (Angular tracks this automatically) rather than manual diffing; the defensive `component.canDeactivate ? … : true` lets you attach the guard to routes whose components may not all implement the contract; for the browser's native unload (closing the tab), you also need a `beforeunload` listener — `CanDeactivate` only covers in-app navigation. **Complexity:** O(1). This tests router lifecycle knowledge plus clean, reusable guard design.

#### Q127. [Coding] Write a comprehensive cross-field + async reactive validator that depends on a sibling control and an HTTP check, with proper PENDING handling.

**Problem:** A "transfer amount" must be ≤ the account balance (cross-field, where balance is another control) **and** the recipient account must be verified server-side (async). Implement both, handle the PENDING state correctly, and avoid validating on every keystroke.

This combines a **group-level synchronous validator** (cross-field comparison) with an **async validator** on a single control (HTTP). The subtle requirements: async validators must **complete** (or the control stays `PENDING` forever), must be **debounced** to avoid hammering the server, and the UI must distinguish PENDING from VALID/INVALID so the submit button isn't enabled mid-check.

```typescript
export class TransferComponent {
  private fb = inject(NonNullableFormBuilder);
  private api = inject(AccountService);

  form = this.fb.group(
    {
      balance: [{ value: 1000, disabled: true }],         // shown, not edited
      amount: [0, [Validators.required, Validators.min(0.01)]],
      recipient: ['', { validators: [Validators.required], asyncValidators: [this.recipientExists()] }],
    },
    { validators: [this.amountWithinBalance] },            // cross-field on the group
  );

  // Cross-field (group-level) sync validator
  private amountWithinBalance(group: AbstractControl): ValidationErrors | null {
    const balance = group.get('balance')?.value ?? 0;
    const amount = group.get('amount')?.value ?? 0;
    return amount > balance ? { exceedsBalance: { balance, amount } } : null;
  }

  // Async validator: debounced HTTP existence check that COMPLETES
  private recipientExists(): AsyncValidatorFn {
    return (ctrl: AbstractControl): Observable<ValidationErrors | null> =>
      timer(400).pipe(                                    // debounce without re-subscribing to valueChanges
        switchMap(() => this.api.verifyAccount(ctrl.value)),
        map(ok => (ok ? null : { recipientNotFound: true })),
        catchError(() => of({ recipientCheckFailed: true })),  // surface check failure as an error
        first(),                                          // MUST complete or control stays PENDING
      );
  }

  // Submit only when fully valid AND not still checking
  readonly canSubmit = toSignal(
    this.form.statusChanges.pipe(map(s => s === 'VALID')),
    { initialValue: false },
  );
}
```

```html
<form [formGroup]="form">
  <input type="number" formControlName="amount" />
  <input formControlName="recipient" />
  @if (form.controls.recipient.pending) { <span>Checking recipient…</span> }
  @if (form.errors?.['exceedsBalance']) { <span>Amount exceeds balance.</span> }
  @if (form.controls.recipient.errors?.['recipientNotFound']) { <span>No such account.</span> }
  <button [disabled]="!canSubmit()">Transfer</button>
</form>
```

**Critical correctness points:** the async validator uses `timer(400)` **inside** the validator (debounce) and `first()` to guarantee completion — the single most common async-validator bug is a non-completing observable that pins the control in `PENDING`, leaving submit perpetually disabled. The cross-field validator lives on the **group** (so it can read both controls) and sets the error on the group, hence the template reads `form.errors?.['exceedsBalance']`. The submit button keys off `statusChanges === 'VALID'`, which is `false` while any async check is `PENDING` — so the user can't submit mid-verification. **Edge cases:** `catchError` converts a failed HTTP check into an explicit error (don't silently pass); `getRawValue()` is needed at submit to include the disabled `balance`; consider `updateOn: 'blur'` for the recipient to validate less aggressively. **Complexity:** O(1) plus one network call per settled edit. This is the most complete forms-validation scenario interviewers pose.

#### Q128. [Coding] Implement a reusable, typed `effect`-based persistence/sync utility and explain the one legitimate case for writing signals inside it.

**Problem:** Build a utility that mirrors a signal to an external system (e.g., syncs a filter signal to the URL query params) using `effect`, and discuss why this is a legitimate effect use while "deriving state" is not.

`effect()` is for **synchronizing reactive state with the non-reactive world** — exactly this case: when a signal changes, push it to the URL. This is legitimate because it's a *side effect at the system boundary* (the Router/`history` API), not a derivation that should be a `computed`. The utility encapsulates the pattern so it's reusable and so the (rare, deliberate) signal-write-back is contained and justified.

```typescript
@Injectable({ providedIn: 'root' })
export class UrlSyncService {
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  /** Two-way sync a signal with a URL query param. Returns the signal for convenience. */
  syncQueryParam<T>(key: string, sig: WritableSignal<T>, codec = JSON): WritableSignal<T> {
    // 1) Hydrate FROM url once (the legitimate "write a signal inside reactive setup")
    const initial = this.route.snapshot.queryParamMap.get(key);
    if (initial !== null) {
      try { sig.set(codec.parse(initial) as T); } catch { /* keep current value */ }
    }

    // 2) Sync signal → url. This is a SIDE EFFECT (navigation), the correct use of effect.
    effect(() => {
      const value = sig();
      this.router.navigate([], {
        queryParams: { [key]: value == null ? null : codec.stringify(value) },
        queryParamsHandling: 'merge',
        replaceUrl: true,        // don't spam browser history on every keystroke
      });
    });

    return sig;
  }
}

// Usage:
export class ProductListComponent {
  private urlSync = inject(UrlSyncService);
  search = this.urlSync.syncQueryParam('q', signal(''));      // ?q=… stays in sync with `search`
  page   = this.urlSync.syncQueryParam('page', signal(1));
}
```

**Why this is a legitimate `effect`:** the effect's job is to **produce a side effect outside the reactive graph** — updating the URL/browser history — which is precisely the sanctioned role of `effect` (alongside logging, manual DOM, `localStorage`, third-party libs). It is *not* deriving a signal from another signal; it's pushing reactive state into an imperative system. The one signal *write* (`sig.set(initial)` during hydration) is acceptable because it happens **once, during setup, before the effect tracks dependencies**, not inside the effect — so it doesn't create the feedback loop that `allowSignalWrites` guards against. **Contrast with the anti-pattern:** using `effect(() => derived.set(compute(a())))` to *derive* `derived` from `a` is wrong — that should be `computed(() => compute(a()))`, which is pure, memoized, glitch-free, and triggers no extra change detection. **Edge cases:** `replaceUrl: true` avoids polluting history; `queryParamsHandling: 'merge'` preserves other params; wrap codec parse in try/catch for malformed URLs; the effect auto-cleans on `DestroyRef`. **Complexity:** O(1) per change. The deep insight tested: *effects are for the system boundary, computed is for derivation* — and recognizing which side of that line a problem falls on is the mark of signals mastery.

## ✅ Key Takeaways

- **Standalone is the present and future:** no NgModules for new code; bootstrap with `bootstrapApplication` and `provideX()` functions. `standalone: true` is implied from v19.
- **Signals are the default reactivity primitive** (v16→v21): `signal`/`computed`/`effect`, plus `input()`, `model()`, `output()`, signal queries, and `resource`/`rxResource`. They integrate with `OnPush` and power zoneless CD.
- **Zoneless is the trajectory:** drop Zone.js, signal changes/events/async pipe drive CD. Smaller bundles, faster, better stack traces — but your code must signal changes explicitly.
- **New control flow (`@if`/`@for`/`@switch`)** is faster and the recommended default; `track` is mandatory in `@for`. **`@defer`** is the highest-leverage template-level lazy loading tool.
- **Reactive forms** for anything non-trivial; functional guards/interceptors over class-based; `inject()` over constructor injection for composability.
- **SSR with non-destructive (v17) and incremental (v19+) hydration** improves LCP/SEO without flicker; guard browser-only APIs and transfer server data to avoid mismatches.
- **DI is hierarchical** (element + environment injectors); master `@Self/@SkipSelf/@Optional/@Host`, injection tokens, and multi-providers for extensible libraries.
- **Match state tooling to coupling:** signals for local/feature state, NgRx (or SignalStore) only for complex, audited, cross-cutting domains.

## ⚠️ Common Pitfalls

- **Forgetting `track` in `@for`** (or using `trackBy` wrong) — recreates DOM nodes and destroys list performance.
- **Mutating objects/arrays under `OnPush` or with signals** — reference doesn't change, so the view doesn't update. Replace, don't mutate.
- **Leaking subscriptions** — bare `.subscribe()` without `async` pipe or `takeUntilDestroyed()` keeps components alive and running.
- **Calling methods/getters that do work directly in templates** — they run on every CD cycle; use `computed()` or memoized pure pipes.
- **Relying on Zone.js to notice mutations** — breaks under zoneless; always go through signals, `async` pipe, or `markForCheck()`.
- **`ExpressionChangedAfterItHasBeenCheckedError`** — mutating bound state during CD; fix with `afterNextRender`/stable references, not by suppressing.
- **Over-using `bypassSecurityTrust*`** — each call is a potential XSS hole; storing JWTs in `localStorage` invites XSS exfiltration.
- **Hydration mismatches** from direct DOM manipulation or unguarded `window`/`localStorage` during SSR.
- **Globalizing local state** into NgRx — boilerplate explosion and needless re-render pressure.
- **Async validators that never complete** — the control stays `PENDING` forever; always `first()`/`take(1)`.

## 📚 Further Reading

- [Angular Official Documentation](https://angular.dev) — the modern docs (angular.dev), covering signals, control flow, `@defer`, zoneless, and SSR.
- [RxJS Documentation](https://rxjs.dev) — operators, marble diagrams, and reactive patterns.
- [NgRx Documentation](https://ngrx.io) — Store, Effects, and the newer SignalStore.
- *Angular Development with TypeScript* (Yakov Fain & Anton Moiseev) — solid framework fundamentals.
- *Reactive Programming with Angular and RxJS / Reactive Patterns with RxJS for Angular* (Lamis Chebbi) — applied RxJS in Angular.
- [Angular Blog](https://blog.angular.dev) and [Update Guide](https://angular.dev/update-guide) — version-by-version migration steps and release notes for v17–v21.
