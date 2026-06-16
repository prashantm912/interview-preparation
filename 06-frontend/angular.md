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
