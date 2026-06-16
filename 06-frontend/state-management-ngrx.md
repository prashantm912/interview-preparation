# State Management (NgRx & Beyond)

A staff-level deep dive into the Redux pattern, the full NgRx ecosystem (Store, Effects, Selectors, Entity, ComponentStore, SignalStore), and how it stacks up against Redux Toolkit, Zustand, and Pinia. The guide is biased toward the hard part: deciding *when not* to reach for a global store.

[← Back to master index](../README.md)

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

### Q1. [Theory] What problem does the Redux pattern solve, and what are its three core principles?

The Redux pattern exists to make state changes **predictable** in applications where many components read and mutate shared data. Without it you get "prop drilling," scattered mutable objects, and bugs where nobody knows *who* changed *what* or *when*. Redux centralizes that into one observable flow.

The three canonical principles are:

1. **Single source of truth** — the whole application state lives in one immutable object tree (the *store*).
2. **State is read-only** — the only way to change it is to *dispatch an action*, a plain object describing "what happened."
3. **Changes are made with pure functions** — *reducers* take the previous state plus an action and return a *new* state object, never mutating the old one.

The payoff is traceability: every state transition is a serializable action, so you get time-travel debugging, easy logging, and deterministic tests. The cost is ceremony — for trivial state this is overkill, which is the central trade-off the rest of this guide keeps returning to.

```
┌──────────┐  dispatch   ┌──────────┐  (state, action)  ┌──────────┐
│Component │ ──────────► │  Action  │ ────────────────► │ Reducer  │
└────┬─────┘             └──────────┘                   └────┬─────┘
     │                                                       │ returns
     │  select (Observable / Signal)                         ▼ new state
     │ ◄──────────────────────────────────────────────  ┌──────────┐
     └─────────────────────────────────────────────────►│  Store   │
                                                         └──────────┘
```

### Q2. [Theory] What is NgRx and how do its building blocks map onto the Redux pattern?

NgRx is the de-facto Redux implementation for Angular, built on RxJS Observables. It keeps the Redux mental model but adapts it to Angular's reactive, dependency-injection world. The pieces are:

- **Store** — an RxJS `Observable` of the global state plus a `dispatch()` method.
- **Actions** — typed events created with `createAction` (or `createActionGroup` in NgRx 13+).
- **Reducers** — pure functions built with `createReducer`/`on` that produce new state.
- **Selectors** — memoized pure functions (`createSelector`) that derive slices of state.
- **Effects** — RxJS-based handlers for side effects (HTTP, navigation, web sockets) that listen to actions and dispatch new ones.
- **Entity** — an adapter (`@ngrx/entity`) for managing normalized collections efficiently.

The key difference from plain Redux is that NgRx leans heavily on RxJS for the read path (selectors return Observables) and the side-effect path (Effects are streams). As of NgRx 17+, selectors and the store also integrate with Angular Signals via `store.selectSignal()`.

### Q3. [Coding] Write a simple counter reducer and the actions it handles using `createReducer`.

**Problem:** Implement increment, decrement, and reset for a counter feature with strongly typed actions.

```typescript
import { createAction, createReducer, on, props } from '@ngrx/store';

// --- Actions ---
export const increment = createAction('[Counter] Increment');
export const decrement = createAction('[Counter] Decrement');
export const reset = createAction('[Counter] Reset');
export const setBy = createAction('[Counter] Set By', props<{ amount: number }>());

// --- State ---
export interface CounterState {
  count: number;
}
const initialState: CounterState = { count: 0 };

// --- Reducer (pure, returns NEW objects) ---
export const counterReducer = createReducer(
  initialState,
  on(increment, (state) => ({ ...state, count: state.count + 1 })),
  on(decrement, (state) => ({ ...state, count: state.count - 1 })),
  on(reset, () => initialState),
  on(setBy, (state, { amount }) => ({ ...state, count: state.count + amount })),
);
```

**Why the spread (`...state`)?** Reducers must be pure and immutable. Returning a brand-new object lets the store use reference equality (`===`) to detect changes cheaply.

- **Time complexity:** O(1) per action.
- **Space complexity:** O(1) extra (one new top-level object; nested values are shared by reference).
- **Edge cases:** `setBy` with a negative or zero amount works fine; guard against integer overflow only if counts can grow unbounded.

### Q4. [Theory] What is the difference between global (store) state and component (local) state? Give an example of each.

**Global state** is data needed by *multiple, unrelated* parts of the app or that must survive route changes: the authenticated user, a shopping cart, feature flags, cached lookup tables. **Component (local) state** is ephemeral UI data scoped to one component: whether a dropdown is open, the current value of a search input before submit, an accordion's expanded panel.

The rule of thumb: *put state in the store only when it is shared, long-lived, or needs to be debugged/replayed.* A toggle that dies with the component should never touch the global store — doing so adds three files (action, reducer, selector) to manage a boolean. In Angular you express local state with plain component fields, Signals (`signal()`), or `@ngrx/component-store` for more structure.

### Q5. [Practical] Your `selector` returns the same data but the component re-renders on every store change. What's wrong and how do you fix it?

The classic cause is selecting the **whole state** or building a **new object/array inside the selector projector on every call**, so reference equality fails and Angular's change detection (or `async` pipe) treats it as new. For example, `state => ({ name: state.name })` returns a fresh object each time.

The fix is to use `createSelector`, which **memoizes**: it caches the last inputs and last output and returns the *same reference* when inputs are unchanged. Select narrow slices and let `createSelector` compose them, so the expensive projection only re-runs when a real input changes. In production I also verify the component uses `ChangeDetectionStrategy.OnPush` and the `async` pipe (or `selectSignal`) so unchanged references skip rendering entirely.

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] How do memoized selectors work internally, and why does memoization matter for performance?

`createSelector` builds a selector from input selectors plus a *projector* function. Internally it keeps a one-entry cache: the array of last input results and the last computed output. On each call it runs the input selectors; if **every** input result is reference-equal to the cached one, it skips the projector and returns the cached output. Otherwise it recomputes and updates the cache.

```
inputs change?  ──no──► return cached output  (O(n) ref checks, no recompute)
       │ yes
       ▼
   run projector, cache new inputs + output
```

This matters because selectors often do real work — filtering, sorting, joining entities, computing totals. Without memoization that work runs on *every* dispatched action, even unrelated ones, which on a busy store can be hundreds of times per second. Memoization turns it into "compute only when inputs actually changed." The one-entry cache has a sharp edge: a selector called with *different arguments alternately* (via `props`) thrashes the cache. For that, use a **selector factory** (`createSelector` returned from a function) so each consumer gets its own cache instance.

### Q7. [Coding] Implement a parameterized selector that returns a single todo by id without cache-thrashing.

**Problem:** `selectTodoById` is called from many components with different ids. A naive memoized selector with one cache slot would thrash.

```typescript
import { createSelector, createFeatureSelector } from '@ngrx/store';
import { Dictionary } from '@ngrx/entity';

interface Todo { id: string; title: string; done: boolean; }
interface TodosState { entities: Dictionary<Todo>; ids: string[]; }

const selectTodosFeature = createFeatureSelector<TodosState>('todos');
const selectEntities = createSelector(selectTodosFeature, (s) => s.entities);

// FACTORY: each call site gets its OWN memoized selector instance.
export const selectTodoById = (id: string) =>
  createSelector(selectEntities, (entities) => entities[id]);

// Usage in a component:
// readonly todo$ = this.store.select(selectTodoById(this.id));
```

**Approach comparison:**

- **Brute force:** `store.select(s => s.todos.entities[id])` — works but no memoization; projector runs on every action.
- **Single memoized selector with props:** one cache slot → thrashes when ids differ across simultaneous subscribers.
- **Factory (above):** O(1) lookup, isolated cache per id. Optimal for this access pattern.

- **Time:** O(1) dictionary lookup per recompute. **Space:** O(number of distinct call sites) for the cache instances.
- **Edge cases:** missing id returns `undefined` — type the return as `Todo | undefined` and handle it in the template. Remember to clean up factory selectors if you create thousands dynamically (rare).

### Q8. [Theory] What are NgRx Effects, why must they live outside reducers, and what is the contract they must honor?

Effects handle **side effects** — anything impure or async: HTTP calls, router navigation, `localStorage`, web sockets, toasts. Reducers must stay pure (same input → same output, no I/O), so side effects cannot live there. An Effect is an injectable class where each effect is an RxJS pipeline that **listens to a stream of actions** (`Actions`), does work, and typically **maps to a new action** that a reducer then handles.

```
Action dispatched ──► Effects (listen via ofType)
                          │ HTTP / async work
                          ▼
                     success/failure Action ──► Reducer ──► Store
```

The contract: an effect that emits actions (`createEffect(() => ...)`) **must not let its source stream complete or error out**, or it silently stops listening. So always catch errors *inside* the inner observable (e.g. `catchError` within `switchMap`), returning a failure action rather than letting the outer stream die. Effects that don't dispatch (logging, navigation) declare `{ dispatch: false }`.

### Q9. [Coding] Write an Effect that loads users over HTTP, including correct error handling and the right flattening operator.

**Problem:** On `loadUsers`, fetch from an API and dispatch `loadUsersSuccess` or `loadUsersFailure`. Don't let errors kill the effect.

```typescript
import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { UserApi } from './user.api';
import { UserActions } from './user.actions';

@Injectable()
export class UserEffects {
  private actions$ = inject(Actions);
  private api = inject(UserApi);

  loadUsers$ = createEffect(() =>
    this.actions$.pipe(
      ofType(UserActions.loadUsers),
      switchMap(() =>
        this.api.getAll().pipe(
          map((users) => UserActions.loadUsersSuccess({ users })),
          // catchError is INSIDE switchMap so the outer stream survives:
          catchError((error) =>
            of(UserActions.loadUsersFailure({ error: error.message })),
          ),
        ),
      ),
    ),
  );
}
```

**Choosing the flattening operator (a common interview trap):**

- `switchMap` — cancels the previous request; ideal for "load latest" (typeahead, refresh). Used here.
- `concatMap` — queues requests in order; use for writes that must not race (sequential saves).
- `mergeMap` — runs all in parallel; use when order doesn't matter and you want max throughput.
- `exhaustMap` — ignores new triggers while one is in flight; perfect for login button spam.

- **Edge cases:** network timeout → `loadUsersFailure`; double-clicks → `switchMap` cancels the stale call; if the API can return 200 with an error envelope, branch inside `map`.

### Q10. [Practical] When would you reach for `@ngrx/entity`, and what does it buy you?

Use `@ngrx/entity` whenever you manage a **collection of records keyed by id** — users, products, messages. It provides an `EntityAdapter` that stores data in a **normalized shape**: `{ ids: string[], entities: { [id]: T } }`. This gives O(1) lookup/update/remove by id instead of O(n) array scans, and it ships pre-built, immutable CRUD reducers (`addOne`, `upsertMany`, `updateOne`, `removeOne`) plus default selectors (`selectAll`, `selectEntities`, `selectTotal`).

```typescript
import { createEntityAdapter, EntityState } from '@ngrx/entity';

export interface User { id: string; name: string; }
export interface UsersState extends EntityState<User> {
  loading: boolean;
}

export const adapter = createEntityAdapter<User>({
  sortComparer: (a, b) => a.name.localeCompare(b.name), // optional auto-sort
});

export const initialState: UsersState = adapter.getInitialState({ loading: false });

// In the reducer:
// on(loadUsersSuccess, (state, { users }) => adapter.setAll(users, { ...state, loading: false }))
```

In production it eliminates a whole class of hand-rolled immutable array bugs (duplicate inserts, off-by-one splices) and keeps update logic uniform. The trade-off: your UI usually wants arrays, so you pair it with the adapter's `getSelectors()` to expose `selectAll`.

### Q11. [Theory] Explain the Facade pattern in NgRx. What are its benefits and its critics' main objection?

A **Facade** is an injectable service that wraps the store, exposing intent-revealing methods and observables so components never import actions or selectors directly:

```typescript
@Injectable({ providedIn: 'root' })
export class UsersFacade {
  private store = inject(Store);
  readonly users$ = this.store.select(selectAllUsers);
  readonly loading$ = this.store.select(selectUsersLoading);

  load() { this.store.dispatch(UserActions.loadUsers()); }
  add(user: User) { this.store.dispatch(UserActions.addUser({ user })); }
}
```

**Benefits:** components depend on a small, stable API; you can swap the *implementation* (NgRx → SignalStore → plain service) without touching components; testing components becomes trivial (mock the facade); it hides NgRx boilerplate from feature developers.

**The objection:** facades can become a leaky god-service that re-introduces the indirection Redux was meant to remove, and they tempt teams to add NgRx *everywhere* (since "the facade hides it") when local state would do. The honest answer in an interview: facades are great for large teams and library boundaries, optional and sometimes over-engineering for small apps.

### Q12. [Coding] Show how `@ngrx/component-store` manages local component state with an updater and an effect.

**Problem:** A paginated list component needs local state (items, page, loading) that doesn't belong in the global store.

```typescript
import { Injectable, inject } from '@angular/core';
import { ComponentStore } from '@ngrx/component-store';
import { exhaustMap, tap, switchMap } from 'rxjs/operators';
import { Observable } from 'rxjs';

interface ListState { items: string[]; page: number; loading: boolean; }

@Injectable() // provided at the COMPONENT level, dies with the component
export class ListStore extends ComponentStore<ListState> {
  private api = inject(ItemApi);
  constructor() { super({ items: [], page: 1, loading: false }); }

  // SELECTORS (memoized signals/observables)
  readonly items$ = this.select((s) => s.items);
  readonly loading$ = this.select((s) => s.loading);

  // UPDATER (synchronous, pure state transition)
  readonly setLoading = this.updater((state, loading: boolean) => ({ ...state, loading }));

  // EFFECT (async, scoped to the component)
  readonly loadPage = this.effect((page$: Observable<number>) =>
    page$.pipe(
      tap(() => this.setLoading(true)),
      switchMap((page) =>
        this.api.getPage(page).pipe(
          tap({
            next: (items) => this.patchState({ items, page, loading: false }),
            error: () => this.setLoading(false),
          }),
        ),
      ),
    ),
  );
}
```

**Why ComponentStore over the global store here:** state is local, lifecycle-bound, and benefits from RxJS structure without the global-store ceremony (no actions/feature registration). It auto-cleans subscriptions on destroy.

- **Edge cases:** rapid page clicks → `switchMap` cancels stale loads; provide the store in the component's `providers` array so each instance is isolated.

### Q13. [Theory] What is the new NgRx SignalStore (NgRx 17/18+) and how does it differ from the classic Store and ComponentStore?

`@ngrx/signals` (stable from NgRx 17, matured through 18–19) is a **Signal-based** state container that drops RxJS as the primary primitive. You build a store with `signalStore()`, composing **features**: `withState`, `withComputed` (memoized derived signals), `withMethods` (updaters and async logic), and `withHooks` (lifecycle). State is exposed as Angular Signals, so reads are synchronous and integrate directly with the template and `computed()`.

Differences:

- **Classic Store:** global, Redux-style, action/reducer/effect/selector files, RxJS Observables. Best for large apps needing the full audit trail and time-travel.
- **ComponentStore:** local, RxJS-based, less boilerplate, no global actions.
- **SignalStore:** can be global (`providedIn: 'root'`) or local; **Signal-first**, far less ceremony, optional `rxMethod` for RxJS interop, and an optional `withEntities` feature mirroring `@ngrx/entity`. It is the direction NgRx is steering new code as Angular goes Signal-first.

```typescript
import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { computed } from '@angular/core';

export const CounterStore = signalStore(
  { providedIn: 'root' },
  withState({ count: 0 }),
  withComputed(({ count }) => ({ double: computed(() => count() * 2) })),
  withMethods((store) => ({
    increment: () => patchState(store, (s) => ({ count: s.count + 1 })),
    reset: () => patchState(store, { count: 0 }),
  })),
);
```

### Q14. [Practical] A teammate wants to put every form field and toggle into the global NgRx store "for consistency." How do you respond?

This is textbook over-engineering. I'd push back with a concrete cost/benefit: each piece of state in the global store typically means an action, a reducer case, a selector, and often an effect — multiplied across the team's mental overhead. For a transient checkbox, that's a large fixed cost for zero benefit, and it pollutes the store with noise that makes time-travel debugging *harder*, not easier.

My production heuristic, which I'd share as a team guideline:

```
Is the state shared across unrelated components, OR
must it survive navigation, OR
do you need to replay/debug/persist it?
   │
   ├── YES → global store (NgRx Store or root SignalStore)
   └── NO  → local: Signals / component fields / ComponentStore
```

I'd propose ComponentStore or plain Signals for local UI state, reserve the global store for genuinely shared domain state (auth, cart, cached entities), and add a lint rule or PR checklist so "consistency" means *consistent decision-making*, not "everything global." Consistency in the *decision rule* beats uniformity in the *storage location*.

### Q15. [Theory] Compare NgRx with Redux Toolkit, Zustand, and Pinia. When does each shine?

All four implement variations of centralized state, but with very different ergonomics:

| Library | Ecosystem | Paradigm | Boilerplate | Side effects | Best for |
|--------|-----------|----------|-------------|--------------|----------|
| **NgRx Store** | Angular | Redux + RxJS | High | Effects (RxJS) | Large Angular apps, strict audit trail |
| **NgRx SignalStore** | Angular | Signals | Low | `rxMethod`/methods | Modern Angular, Signal-first |
| **Redux Toolkit (RTK)** | React (any) | Redux + Immer | Medium | Thunks / RTK Query | React apps wanting Redux with less ceremony |
| **Zustand** | React | Hook-based store | Very low | Inside actions | React apps wanting minimal, fast, unopinionated state |
| **Pinia** | Vue | Reactive store | Low | Actions (async) | Vue 3, official Vuex successor |

Key insight: **Redux Toolkit** uses Immer so you write "mutating" code that produces immutable updates, slashing reducer boilerplate; **RTK Query** is its data-fetching/caching layer (analogous to combining Effects + Entity + cache). **Zustand** drops actions/reducers entirely — you mutate a store via `set()` inside hooks, near-zero boilerplate, but you trade away enforced structure and the action audit trail. **Pinia** is Vue's reactive store: stores are composables with `state`, `getters`, `actions`, deeply integrated with Vue reactivity. NgRx is the most structured/opinionated of the group — that structure is an asset on a 50-engineer codebase and a liability on a weekend project.

---

## 🟠 Advanced (8–12 yrs)

### Q16. [Practical] Walk through how you'd unit-test a reducer, a selector, and an effect. What does each test actually verify?

The three NgRx pieces have very different test shapes because their responsibilities differ.

**Reducer** — pure function, so test it as one: feed `(state, action)` and assert the new state. No mocks, no async.

```typescript
it('increments count', () => {
  const result = counterReducer({ count: 1 }, increment());
  expect(result).toEqual({ count: 2 });
  expect(result).not.toBe; // new reference, immutability preserved
});
```

**Selector** — pure projection; call `.projector(...)` directly with fake inputs (skips the store entirely) to test the logic, and optionally test memoization via `.release()`.

```typescript
it('computes completed count', () => {
  expect(selectCompletedCount.projector([{ done: true }, { done: false }])).toBe(1);
});
```

**Effect** — async + RxJS; use `provideMockActions` to feed an actions stream and a spied service, then assert the output action. Marble tests (`TestScheduler`) verify timing/cancellation.

```typescript
it('dispatches success on load', (done) => {
  actions$ = of(UserActions.loadUsers());
  api.getAll.and.returnValue(of([{ id: '1', name: 'A' }]));
  effects.loadUsers$.subscribe((action) => {
    expect(action.type).toBe('[Users] Load Success');
    done();
  });
});
```

The reducer test verifies *state math*, the selector test verifies *derivation*, the effect test verifies *orchestration and error handling*. I keep them separate so a failure points to the exact layer.

### Q17. [Coding] Implement an effect with optimistic updates and rollback on failure.

**Problem:** Toggling a todo's "done" flag should update the UI instantly (optimistic), then reconcile with the server and roll back on error.

```typescript
import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { concatMap, map, catchError } from 'rxjs/operators';
import { of } from 'rxjs';

@Injectable()
export class TodoEffects {
  private actions$ = inject(Actions);
  private api = inject(TodoApi);

  // The reducer applies `toggleTodo` immediately (optimistic),
  // and reverts on `toggleTodoFailure`.
  saveToggle$ = createEffect(() =>
    this.actions$.pipe(
      ofType(TodoActions.toggleTodo),
      // concatMap: persist toggles IN ORDER so rapid flips don't race.
      concatMap(({ id, previousDone }) =>
        this.api.toggle(id).pipe(
          map(() => TodoActions.toggleTodoSuccess({ id })),
          catchError(() =>
            of(TodoActions.toggleTodoFailure({ id, previousDone })),
          ),
        ),
      ),
    ),
  );
}
```

The reducer flips the flag on `toggleTodo` and restores `previousDone` on `toggleTodoFailure`. The action carries `previousDone` precisely so the rollback is deterministic.

- **Approach trade-offs:** *Pessimistic* (wait for server, then update) is simpler and always consistent but feels laggy; *optimistic* (above) is snappy but needs careful rollback and ordering.
- **Why `concatMap`:** prevents two in-flight toggles from resolving out of order and leaving stale state. **Time:** O(1) per toggle. **Edge cases:** offline → failure → rollback + user toast; conflicting server state → refetch the entity.

### Q18. [Theory] How does NgRx handle feature state, lazy loading, and store composition in a large modular app?

NgRx composes state from **feature slices**. The root provides the store (`provideStore({})` or `StoreModule.forRoot`), and each feature registers its slice with `provideState(featureKey, reducer)` / `StoreModule.forFeature`. When a feature is **lazy-loaded**, its reducers and effects are registered *only when the route loads*, so the store grows dynamically — initial bundle and initial state stay lean.

```
Root Store (auth, router)
   ├── eager feature: layout
   └── lazy route "/orders" loads ──► provideState('orders', ordersReducer)
                                  └──► provideEffects(OrderEffects)
```

`createFeature` (NgRx 12.2+) bundles a feature's reducer plus auto-generated selectors into one object, cutting boilerplate and keeping the key in sync. The subtle risks: a selector in feature A must never assume feature B's slice is loaded (it may be `undefined` pre-lazy-load — guard with the feature selector); and effects registered per-feature must be idempotent if the module can re-enter. Meta-reducers (logging, hydration, reset-on-logout) wrap the entire reducer pipeline and run on every action across all features.

### Q19. [Practical] Describe a real-world case where adopting (or removing) a global store materially changed an application.

A concrete pattern I've seen repeatedly: a fintech dashboard team adopted NgRx for *everything* in year one — including modal open/closed flags and form drafts. By the time the app had ~40 feature areas, the store had hundreds of actions, the Redux DevTools timeline was unusable (thousands of UI-noise actions per session), and onboarding a new engineer took a week just to learn the action taxonomy. The fix was a deliberate migration: keep NgRx for **shared domain state** (positions, market data via web-socket effects, auth/permissions) and move all local UI state to ComponentStore and Signals. Action volume dropped ~80%, DevTools became a genuine debugging tool again, and feature velocity improved because most features no longer touched the global store at all.

The general lesson — echoed across large React (RTK) and Vue (Pinia) codebases — is that the *value* of a global store is concentrated in a minority of genuinely-shared, long-lived, audited state. Putting everything in it dilutes the signal and amplifies the cost. The teams that succeed treat the store as a *scarce resource*, not a default container.

### Q20. [Theory] What are the security implications of client-side state stores, and how do you mitigate them?

Client-side state is **fully visible and mutable by the user** — anything in the store can be read via DevTools and tampered with. The implications:

1. **Never trust store-derived authorization.** Hiding a button via a `roles` selector is UX, not security; the server must re-authorize every mutation. Treating client state as a security boundary is a classic vulnerability.
2. **Don't persist secrets.** Hydrating the store from `localStorage` (a common meta-reducer) writes whatever you put there to disk in plaintext — never store JWTs/refresh tokens there if XSS is a concern; prefer `httpOnly` cookies. Persisted PII is also a privacy/GDPR liability.
3. **XSS amplifies state risk.** If an attacker runs script in your origin, they read the entire store. Mitigate with CSP, output encoding, and minimizing sensitive data in state.
4. **Action payloads can leak via logging meta-reducers** — scrub passwords/tokens before logging actions to a monitoring backend.

Mitigations: keep secrets out of the store, encrypt/limit persisted slices, sanitize logged actions, set a `clearState` meta-reducer on logout so the next user can't read the previous session, and always enforce authz server-side.

### Q21. [Coding] Write a meta-reducer that resets the entire store on logout and hydrates from localStorage on startup.

**Problem:** On logout, wipe all feature state (security). On app start, rehydrate a whitelisted slice from `localStorage`.

```typescript
import { ActionReducer, MetaReducer, INIT, UPDATE } from '@ngrx/store';
import { AuthActions } from './auth.actions';

// 1) Reset-on-logout: replace state with undefined so each reducer
//    falls back to its initialState.
export function clearStateOnLogout(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action) => {
    if (action.type === AuthActions.logout.type) {
      state = undefined; // forces every feature reducer to re-init
    }
    return reducer(state, action);
  };
}

// 2) Hydration: load whitelisted slice on INIT/UPDATE, save on every action.
const HYDRATE_KEY = 'app_state_v1';
const WHITELIST = ['preferences']; // NEVER include auth tokens / secrets

export function hydrationMetaReducer(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action) => {
    if ((action.type === INIT || action.type === UPDATE) && !state) {
      try {
        const saved = localStorage.getItem(HYDRATE_KEY);
        if (saved) state = JSON.parse(saved);
      } catch { /* corrupt storage → ignore, start clean */ }
    }
    const nextState = reducer(state, action);
    const toPersist = WHITELIST.reduce((acc, k) => ({ ...acc, [k]: nextState[k] }), {});
    localStorage.setItem(HYDRATE_KEY, JSON.stringify(toPersist));
    return nextState;
  };
}

export const metaReducers: MetaReducer[] = [clearStateOnLogout, hydrationMetaReducer];
```

- **Order matters:** logout-clear runs before hydration-save, so logout persists an empty whitelist.
- **Edge cases:** corrupt JSON → caught, app starts clean; storage quota exceeded → wrap `setItem` in try/catch; schema version bump → change `HYDRATE_KEY` to invalidate old shapes. **Security:** the whitelist explicitly excludes tokens.

### Q22. [Practical] How do you migrate an NgRx Store-based feature to SignalStore incrementally without a big-bang rewrite?

I treat it as a strangler-fig migration behind the Facade boundary. The steps:

1. **Introduce a facade** (if not present) so components depend on an interface, not on NgRx internals.
2. **Pick a leaf feature** with self-contained state and few cross-feature selectors.
3. **Build a `signalStore` mirror** of that feature's state/computed/methods; for async, use `rxMethod` so existing RxJS service calls drop in.
4. **Bridge interop:** SignalStore can read existing Store selectors via `toSignal(store.select(...))`, and the old store can read Signals via `toObservable`. This lets the two coexist during transition.
5. **Swap the facade's implementation** to delegate to the SignalStore; components are untouched.
6. **Delete the old slice** (actions/reducer/effects) once nothing references it, and remove it from the feature registration.

Trade-offs I flag to the team: SignalStore loses the global Redux DevTools action timeline for that feature (mitigated by `withDevtools` from `@ngrx/signals` tooling), and mixing paradigms temporarily increases cognitive load. I'd do it feature-by-feature, keep genuinely-global audited state (auth) on the classic Store longest, and gate each migration behind passing tests on the facade contract.

---

## 🔴 Expert (15+ yrs)

### Q23. [Behavioral] Your org is standardizing front-end state management across React, Angular, and Vue teams. How do you lead that decision?

I'd resist the temptation to mandate one *library* (impossible across three frameworks) and instead standardize *principles and a decision rule*. Concretely: I'd convene the senior ICs from each stack, write an ADR that defines (a) what qualifies as global state, (b) the default for local state per framework (Signals/ComponentStore for Angular, hooks/Zustand for React, composables/Pinia for Vue), and (c) cross-cutting requirements — typed actions or methods, memoized derivations, side-effects isolated from state mutation, and a logout/reset story.

The behavioral core is *aligning autonomy with consistency*: each team keeps its idiomatic tool (NgRx/RTK/Pinia) but conforms to shared contracts so engineers can move between teams and code reviews share a vocabulary. I'd pilot the ADR on one feature per stack, gather real friction data, then ratify. I'd explicitly call out that mandating uniformity where it doesn't pay (forcing Redux on a tiny Vue widget) erodes trust — the goal is *consistent judgment*, measurable via reduced state-related defects and faster cross-team onboarding, not identical import statements.

### Q24. [Theory] At very large scale, what are the failure modes of the Redux/NgRx pattern, and what architectural alternatives exist?

The pattern's failure modes at scale are well documented:

1. **Action explosion** — thousands of action types create a taxonomy nobody fully knows; mitigated by `createActionGroup`, strict naming conventions, and *not* storing UI state globally.
2. **Selector graphs that recompute too much** — deep selector trees where one hot input invalidates large subtrees; mitigated by careful slicing, selector factories, and profiling.
3. **Effect orchestration sprawl** — complex multi-step async flows become tangled RxJS; mitigated by extracting flows into ComponentStore/SignalStore or modeling them as explicit state machines (XState), which give exhaustive, visualizable transitions Redux reducers don't.
4. **Single-store contention in micro-frontends** — independently deployed MFEs can't share one store cleanly; the answer is *per-MFE stores* with an event bus or shared-kernel for the small truly-shared slice.

Alternatives/complements worth naming: **state machines (XState)** for complex workflows; **server-state libraries (RTK Query, TanStack Query, Apollo)** that treat server data as cache rather than hand-managed store state — often eliminating the majority of "loading/error/data" boilerplate; and **Signal-based local stores** for the long tail. The expert position is that "global store vs not" is a false binary: mature apps run a *portfolio* — server-cache library for remote data, a small global store for shared client state, and Signals/local stores for UI.

### Q25. [Theory] Defend or refute: "Memoized selectors make derived state free." What subtle costs remain?

It's a useful approximation but false as stated. Memoization makes *repeated reads with unchanged inputs* cheap, but real costs remain:

1. **Cache invalidation cost** — every dispatch still runs the input selectors and does reference comparisons across the selector graph; a wide graph isn't free even when nothing recomputes.
2. **First/changed computation** — the projector still runs whenever inputs change, and for expensive joins/sorts on large collections that can be a frame-budget problem; memoization doesn't reduce that, it just avoids *redundant* runs.
3. **Single-slot cache thrashing** — parameterized selectors without factories recompute every alternate call, silently negating memoization.
4. **Reference-equality fragility** — if an upstream reducer returns a new array/object that is *value-equal but reference-different* (e.g. `[...arr]` with no change), every downstream selector invalidates needlessly. The discipline of *not creating new references when nothing changed* (which `@ngrx/entity` and Immer enforce) is what actually makes selectors cheap.

So the precise claim is: memoized selectors make *redundant recomputation* free, conditional on stable upstream references and appropriate cache cardinality. Treating them as unconditionally free leads to the classic "why is my list janky" performance bug.

### Q26. [Practical] You inherit an app where Redux DevTools shows 5,000+ actions in a 2-minute session and the UI is janky. Diagnose and remediate.

This is a diagnosable performance/architecture smell, and I'd work it methodically:

```
1. Categorize the 5,000 actions in DevTools:
   ├── Mostly UI noise (mousemove/scroll/keystroke dispatched to store)?  → over-globalization
   ├── Effect feedback loops (action A → effect → action A)?              → recursion bug
   └── High-frequency domain events (websocket ticks)?                    → batching needed
2. Profile with Angular DevTools / React Profiler:
   └── Which selectors recompute per action? Which components re-render?
```

Likely findings and fixes: (a) **UI state in the global store** — migrate keystrokes/scroll to local Signals/ComponentStore so they never dispatch. (b) **Selectors creating new references** — fix projectors to preserve references, add factories for parameterized ones. (c) **Missing OnPush / wrong async usage** — ensure components are `OnPush` and read via `async`/`selectSignal`. (d) **High-frequency streams** — batch websocket updates (buffer over an animation frame) before dispatching, or move them to a dedicated store. (e) **Effect loops** — break recursion with `ofType` discipline.

The strategic remediation mirrors Q19: shrink the store to genuinely-shared state and quantify the win (action count, dropped frames, INP). I'd land it incrementally with before/after metrics so the team sees the payoff and adopts the guideline going forward.

### Q27. [Behavioral] A senior engineer insists NgRx is "always the right choice for Angular." How do you handle the disagreement productively?

I'd treat it as a chance to surface shared principles rather than win an argument. First I'd steelman their view: NgRx genuinely shines for large apps needing audit trails, time-travel, and team-scale structure, and a strong default reduces bikeshedding. Then I'd reframe from "which tool" to "what does *this* feature need," and propose we evaluate against the decision rule (shared? long-lived? needs replay/debug?). I'd bring data — e.g. the Q26-style action-volume and onboarding-cost evidence — because concrete numbers de-personalize the debate.

Practically, I'd suggest a low-stakes experiment: build one upcoming local-state feature with Signals/ComponentStore and one shared-state feature with NgRx, then review velocity, test ergonomics, and DevTools usefulness together. The behavioral goal is to model that senior disagreements are resolved with evidence and small reversible experiments, not seniority — and to leave the engineer feeling heard, since they'll be more likely to champion the nuanced guideline than a mandate imposed over their objection.

### Q28. [Theory] How do Angular Signals change the long-term calculus for NgRx, and where is the ecosystem heading by 2026?

Signals fundamentally shift Angular toward fine-grained, synchronous reactivity, which erodes two historical justifications for NgRx: the need for RxJS plumbing to push state into templates, and the lack of a built-in primitive for shared reactive state. With Signals (`computed`, `effect`, `linkedSignal`, and the `signalStore`), much local and even moderately-shared state can be expressed without the Redux ceremony, with synchronous reads and automatic dependency tracking.

By 2026 the practical landscape: **NgRx SignalStore is the recommended starting point** for new state in modern Angular, with classic Store reserved for apps that specifically need the Redux action log, meta-reducers, and mature DevTools time-travel. RxJS doesn't disappear — it remains the right tool for *event streams and complex async* (websockets, debounced search, cancellation), bridged via `rxMethod`/`toSignal`/`toObservable`. Server state increasingly moves to dedicated cache libraries. The throughline for an architect: the question is no longer "NgRx or not" but "compose the right primitive per concern — Signals for reactive state, RxJS for streams, a small global store for shared/audited state, a cache library for server data." NgRx itself is repositioning as a *suite* of these primitives rather than a single Redux store.

---

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q29. [Theory] Why must actions be plain, serializable objects, and what breaks if you put a class instance, function, or `Date` in a payload?

The Redux pattern treats an action as a **value-typed description of an event** — literally "this happened, here is the data." For that to support the features Redux is famous for (time-travel, replay, persistence, sending the action log to a logging backend), every action must be **serializable**: it must survive `JSON.parse(JSON.stringify(action))` without losing meaning. A plain object of primitives, arrays, and nested plain objects round-trips perfectly; a class instance, a function, a `Map`, a `Symbol`, or a `Date` does not.

The concrete breakage is subtle because it usually "works" in development. A `Date` becomes an ISO *string* after serialization, so a reducer that calls `action.payload.getTime()` throws after a DevTools replay or a rehydration from storage. A class instance loses its prototype — methods vanish and `instanceof` checks fail. A function in a payload is dropped entirely by `JSON.stringify`, so DevTools time-travel silently produces a different state than the live run did. These are heisenbugs: present only on the replay path, invisible in the happy path.

NgRx ships **runtime checks** to catch exactly this. `strictActionSerializability` and `strictStateSerializability` (enabled in dev via `provideStore({}, { runtimeChecks: {...} })`) throw the moment a non-serializable value enters an action or the state tree, turning a silent replay bug into a loud development-time error.

```typescript
provideStore(reducers, {
  runtimeChecks: {
    strictStateImmutability: true,        // freeze state, throw on mutation
    strictActionImmutability: true,       // freeze actions
    strictStateSerializability: true,     // no Date/class/function in state
    strictActionSerializability: true,    // no Date/class/function in actions
    strictActionWithinNgZone: true,       // actions dispatched inside NgZone
    strictActionTypeUniqueness: true,     // no duplicate action type strings
  },
});
```

The practical rule: store epoch milliseconds (`number`) not `Date`, store DTOs (plain interfaces) not domain class instances, and reconstruct rich objects in selectors or at the component edge. The store holds *data*; behavior lives in code.

#### Q30. [Theory] What does `createAction` actually return, and why is `MyAction.type` a string while `MyAction()` is an object?

`createAction('[X] Y', props<P>())` returns an **action creator** — a function that, when called, produces a plain action object `{ type: '[X] Y', ...payload }`. Crucially, NgRx attaches the type string to the creator function itself as a static `.type` property, so the same symbol serves two roles: call it to *make* an action (`increment()`), read `.type` to *identify* one (`ofType(increment)` reads `increment.type` under the hood).

This dual nature is why you pass the *creator* to `ofType` and `on`, not a string. `ofType(loadUsers)` is type-safe and refactor-safe: rename the creator and TypeScript follows; the string literal is centralized in exactly one place. Compare the pre-`createAction` era where teams hand-wrote `export const LOAD_USERS = '[Users] Load'` constants plus separate class definitions — three places to keep in sync, and a typo in a string was a runtime no-op.

```typescript
export const setBy = createAction('[Counter] Set By', props<{ amount: number }>());

setBy.type;            // '[Counter] Set By'   (the discriminant string)
setBy({ amount: 5 });  // { type: '[Counter] Set By', amount: 5 }  (the action)

// props<T>() is a phantom: it returns nothing at runtime, it only carries the
// payload TYPE so createAction can infer the creator's parameter signature.
```

`props<T>()` is the elegant part: it produces no runtime value (it's a type-only marker), existing purely so TypeScript can infer that `setBy` takes `{ amount: number }`. The payload is **spread onto the action**, not nested under `payload` — a deliberate departure from classic Redux's `{ type, payload }` convention, chosen so reducers destructure naturally (`on(setBy, (s, { amount }) => ...)`).

#### Q31. [Theory] The NgRx `Store` "is an Observable." What does that mean concretely, and what is it observing under the hood?

`Store<T>` extends RxJS `Observable<T>`, so you can `subscribe` to it directly and it emits the *entire* state object on every change. Internally the store is backed by a **`BehaviorSubject`-like state container** (`State`, which wraps a `BehaviorSubject` seeded with the initial state). A `BehaviorSubject` is the right primitive because it (a) holds a current value and (b) replays that current value to every new subscriber immediately — which is exactly the semantics you want: a component that subscribes late still gets the present state, not just future changes.

The dispatch loop works like this: `dispatch(action)` pushes the action into an internal **`ActionsSubject`** (also a `BehaviorSubject`-style stream of actions). The `State` service `scan`s over that action stream, running the root reducer `(state, action) => newState`, and pushes each new state into its `BehaviorSubject`. `store.select(fn)` is then just `this.pipe(map(fn), distinctUntilChanged())`.

```
dispatch(action)
   │
   ▼
ActionsSubject (stream of actions)  ──also feeds──►  Effects (ofType)
   │
   ▼  scan((state, action) => reducer(state, action), initialState)
State BehaviorSubject  ── emits newState ──►  Store (Observable<T>)
                                                 │ select(fn) = map(fn) + distinctUntilChanged()
                                                 ▼
                                            Component
```

Two consequences fall out of this design. First, `select` applies `distinctUntilChanged` with reference equality by default, which is *why* immutable updates matter: an unchanged reference is filtered out and never reaches the component. Second, because actions flow through a single subject that both the reducer pipeline and the effects subscribe to, **reducers run before effects** for a given action — the state is already updated by the time an effect sees the action, which is the basis of patterns like reading post-reduction state in an effect via `concatLatestFrom`.

#### Q32. [Theory] In a `createReducer` with multiple `on()` handlers for the same action, what happens — and does handler order matter?

`createReducer` builds a lookup from action type to a list of handler functions. When an action arrives, it finds **all** `on()` entries registered for that action's type and **folds them left-to-right**: the first handler receives the incoming state, its output becomes the input to the second, and so on. So multiple handlers for one action *all run*, composed in registration order — they do not "shadow" each other the way a `switch` `case` would (where you'd hit one branch and `break`).

This is occasionally useful (split unrelated concerns for one action across handlers) but more often a footgun if you assumed switch-like exclusivity. Order matters precisely because it is a fold: if handler A sets `count: 0` and handler B does `count: count + 1`, the result depends on which is registered first.

```typescript
const reducer = createReducer(
  { count: 0, touched: false },
  on(bump, (s) => ({ ...s, count: s.count + 1 })),   // runs first
  on(bump, (s) => ({ ...s, touched: true })),         // then this, on the result of the first
);
// dispatch(bump()) → { count: 1, touched: true }
```

Internally NgRx (since v12+) precomputes a `Map<string, ReducerTypes[]>` so dispatch is an O(1) map lookup plus an O(k) fold over the k handlers for that type, rather than scanning every `on()` clause for every action. This is a meaningful improvement over a naive long `switch`, which is O(number of cases) per action. The takeaway for an interview: handlers are *additive and ordered*, not mutually exclusive, and the reducer must still return a new object from each handler to preserve immutability through the fold.

### 🟡 Intermediate — extended

#### Q33. [Theory] How does `ofType` work internally, and why is it type-safe across multiple action types?

`ofType` is a custom RxJS operator that filters the `Actions` stream by action type string. Conceptually it is `filter(action => allowedTypes.includes(action.type))`, but with two refinements. First, it reads the `.type` static off each action creator you pass, so `ofType(loadUsers, refreshUsers)` filters for either type. Second — the clever part — it uses **TypeScript overloads and discriminated unions** so the *output* observable is typed as the union of exactly those action types, narrowing the action inside the pipe to a type whose payload you can safely destructure.

```typescript
loadOrRefresh$ = createEffect(() =>
  this.actions$.pipe(
    ofType(UserActions.loadUsers, UserActions.refreshUsers),
    // here `action` is typed as (loadUsers | refreshUsers), the narrowed union
    switchMap((action) => /* ... */),
  ),
);
```

The reason this matters internally is the `type` field acting as a **discriminant**. NgRx actions form a discriminated union over the literal `type` string. When `ofType` filters by those literals, TypeScript can prove that, downstream of the filter, the action *must* be one of the listed creators' outputs — so accessing a prop that exists on those (and not on other actions) compiles without casts. Lose the literal type (e.g. widen `type` to `string`) and the narrowing collapses, which is one more reason actions use `as const`-style literal types via `createAction`.

A common gotcha: because `ofType` only filters, the action stream it draws from (`Actions`) is a **hot, shared, never-completing** subject. Your effect's inner observable must terminate or be cancelled (via `switchMap`/`takeUntil`), but the outer `actions$.pipe(ofType(...))` should run for the app's lifetime — which is why letting it error is fatal (Q8).

#### Q34. [Theory] Compare `select(fn)` with `createSelector`-based selectors. Why prefer the latter beyond memoization?

`store.select(s => s.users.list)` (the inline-function form) and `store.select(selectUserList)` (a `createSelector` selector) both produce an `Observable` of a slice, and both get `distinctUntilChanged` applied. The headline difference is memoization — the inline projector runs on *every* state emission (every action), whereas the composed selector runs its projector only when its inputs change. But there are three further reasons to prefer composed selectors that interviewers like to probe.

**Composition and reuse.** `createSelector` selectors are first-class values you compose into graphs: `selectActiveUsers` built from `selectUserList` and `selectFilter`. Recomputation is shared — if ten components read selectors derived from `selectUserList`, the underlying slice extraction runs once per change, not ten times. Inline functions can't share work.

**Testability.** A composed selector exposes `.projector(...)`, letting you unit-test the derivation logic with fake inputs and zero store wiring (Q16). An inline arrow buried in a component is untestable in isolation.

**Decoupling from state shape.** Selectors are the single place that knows the state *structure*. If you re-normalize `users.list` into an entity adapter, you change one selector; every consumer of `selectUserList` is untouched. Inline `s => s.users.list` scattered across components hard-codes the shape everywhere, turning a refactor into a find-and-replace.

```
                       ┌── selectUserList ──┐
selectUsersFeature ───►│                    ├──► selectActiveUsers ──► component
                       └── selectFilter ────┘
                         (each input extracted once; projector memoized)
```

The mental model: `createSelector` is the **read-side query layer** of your store, analogous to a database view — declarative, composable, cached, and the only thing coupled to physical layout.

#### Q35. [Theory] Explain `concatLatestFrom` and why it exists when RxJS already has `withLatestFrom`.

`concatLatestFrom` (from `@ngrx/operators`, previously `@ngrx/effects`) does what `withLatestFrom` does — pairs each source emission with the latest value from one or more other observables (typically `store.select(...)`) — but it **lazily evaluates** the other observables. `withLatestFrom` subscribes to its inputs *eagerly*, the moment the effect is created, even if no source action has fired yet. `concatLatestFrom` defers that subscription until a source value actually arrives, by wrapping the selectors in a factory and using `concatMap` semantics under the hood.

```typescript
import { concatLatestFrom } from '@ngrx/operators';

save$ = createEffect(() =>
  this.actions$.pipe(
    ofType(CartActions.checkout),
    // selector only evaluated when `checkout` fires, with POST-reduction state:
    concatLatestFrom(() => this.store.select(selectCartItems)),
    switchMap(([action, items]) => this.api.checkout(items).pipe(/* ... */)),
  ),
);
```

Why the laziness matters: eagerly subscribing a store selector at effect-construction time can compute derived state before it is needed, and — more importantly — it can create **subtle ordering and performance issues** in large effect classes where dozens of effects each hold an eager subscription to expensive selectors. Deferring means the selector graph is only pulled when an action genuinely demands it. There is also a correctness nuance tied to Q31: because reducers run before effects, by the time `concatLatestFrom` reads `selectCartItems` in response to `checkout`, the state already reflects any reduction that `checkout` triggered — you get the *post-action* snapshot, which is almost always what an effect wants.

The trade-off to mention: `concatLatestFrom` uses concat-style sequencing for evaluating the extra streams, so it is marginally heavier than the raw `withLatestFrom`. For one or two selectors that cost is irrelevant; the laziness and ordering guarantees are worth it, which is why NgRx documents it as the preferred pattern inside effects.

#### Q36. [Theory] What is the lifecycle of an Effect? Explain `ROOT_EFFECTS_INIT`, `OnInitEffects`, and `OnRunEffects`.

An Effect class is instantiated by Angular's DI when its `provideEffects` registration is processed — at root (`provideEffects(RootEffects)` in `ApplicationConfig`) or lazily when a feature route loads. At that point NgRx **subscribes to every property created with `createEffect`** (those without `{ dispatch: false }` have their emissions piped back into `store.dispatch`). The effect's outer observable then lives for the lifetime of the providing injector — root effects for the whole app, feature effects until the feature is destroyed.

NgRx exposes three lifecycle hooks. `ROOT_EFFECTS_INIT` is an **action** dispatched once, after all root effects have been registered — perfect for kicking off app-startup loads (`ofType(ROOT_EFFECTS_INIT)` → dispatch `loadConfig()`). `OnInitEffects` is an **interface**: implement `ngrxOnInitEffects(): Action` on the effects class and NgRx dispatches the returned action automatically when that class initializes — the per-feature equivalent of the root init action.

```typescript
@Injectable()
export class ConfigEffects implements OnInitEffects {
  // Dispatched automatically when THIS effects class is registered:
  ngrxOnInitEffects(): Action {
    return ConfigActions.bootstrap();
  }

  init$ = createEffect(() =>
    this.actions$.pipe(
      ofType(ROOT_EFFECTS_INIT),               // app-wide "all root effects ready"
      map(() => ConfigActions.loadConfig()),
    ),
  );
}
```

`OnRunEffects` is the advanced hook: implement `ngrxOnRunEffects(resolvedEffects$)` to wrap or gate *when* the class's effects actually run — e.g. only run effects between a `userAuthenticated` action and a `userLoggedOut` action, using `takeUntil`/`repeat`. This is how you pause an entire effects class without manual `takeUntil` plumbing in each effect. Understanding these hooks distinguishes someone who has only written CRUD effects from someone who has orchestrated app bootstrap and conditional effect activation.

#### Q37. [Theory] Functional effects vs class-based effects (NgRx 15+). What changed and what are the trade-offs?

NgRx 15 introduced **functional effects** — effects declared as standalone functions registered in `provideEffects`, using `inject()` for dependencies instead of a class with a constructor. The motivation tracks Angular's broader move to standalone, tree-shakable, function-first APIs.

```typescript
// Functional effect (NgRx 15+): no class, dependencies via inject()
export const loadUsers = createEffect(
  (actions$ = inject(Actions), api = inject(UserApi)) =>
    actions$.pipe(
      ofType(UserActions.loadUsers),
      switchMap(() =>
        api.getAll().pipe(
          map((users) => UserActions.loadUsersSuccess({ users })),
          catchError((e) => of(UserActions.loadUsersFailure({ error: e.message }))),
        ),
      ),
    ),
  { functional: true },
);

// Registered as a map of named effects:
// provideEffects({ loadUsers, saveUser, /* ... */ })
```

| Aspect | Class-based effect | Functional effect |
|--------|-------------------|-------------------|
| Definition | `@Injectable()` class, `createEffect` fields | exported function with `{ functional: true }` |
| Dependencies | constructor or `inject()` fields | `inject()` as default parameter values |
| Registration | `provideEffects(MyEffects)` | `provideEffects({ effectA, effectB })` |
| Tree-shaking | whole class registered | individual effects, more granular |
| Lifecycle hooks | `OnInitEffects`/`OnRunEffects` available | not available (no class to implement on) |
| Testing | instantiate class with mocks | call function with injected mocks via `TestBed` |

The trade-off: functional effects are lighter and more granular (you register exactly the effects you want, improving tree-shaking and making "one effect per file" natural), but they **lose the class lifecycle hooks** (`OnInitEffects`, `OnRunEffects`) and the natural grouping a class provides. The pragmatic guidance: functional effects for the common case, fall back to a class when you need lifecycle hooks or want a cohesive grouping with shared private helpers. Both styles can coexist in the same app.

#### Q38. [Theory] What problem does `createActionGroup` (NgRx 13+) solve, and what is "good action hygiene"?

Before `createActionGroup` you wrote each action with its own `createAction` call and an explicit `[Source] Event` string. Across a large feature this produced dozens of near-identical lines and made it easy to drift from the naming convention. `createActionGroup` declares a whole family of related actions in one block, deriving the type strings from a shared `source` and human-readable event names.

```typescript
export const UsersApiActions = createActionGroup({
  source: 'Users API',
  events: {
    'Load Users Success': props<{ users: User[] }>(),
    'Load Users Failure': props<{ error: string }>(),
  },
});
// UsersApiActions.loadUsersSuccess.type === '[Users API] Load Users Success'
```

Note the event keys are written as readable phrases and become camelCase creators, while the `type` strings stay in the `[Source] Event` convention — enforcing consistency mechanically rather than by code review.

This connects to **good action hygiene**, a concept the NgRx team explicitly promotes. The principle: actions should be **unique events that describe what happened, named after the source that raised them**, not commands named after the reducer mutation they cause. So you write `[Login Page] Login Submitted` and `[Auth API] Login Succeeded`, not a generic, reused `[Auth] Set User` dispatched from five places. The benefits are a self-documenting DevTools timeline (you can read the user's journey from the action log), reducers/effects that listen to *specific sources*, and the ability to have many actions converge on one reducer case (via multiple `on(...)` entries) without conflating their origins. The anti-pattern — "action reuse," where one generic action is dispatched everywhere — destroys the audit trail that is the entire reason to adopt the Redux pattern. Action groups make hygienic naming the path of least resistance.

#### Q39. [Practical] How does NgRx implement time-travel debugging, and what are the exact constraints that make it possible?

Time-travel in the Redux DevTools works because of one structural property: **state is a pure function of the initial state plus the ordered sequence of actions**, `state_n = reduce(state_0, [a_1, a_2, ..., a_n])`. The DevTools instrumentation (wired via `provideStoreDevtools()`) records every dispatched action. To "travel" to step k, it simply **re-runs the reducer from the initial state over the first k actions**, producing the exact state that existed then. Stepping backward isn't an undo log of diffs — it's a deterministic recomputation.

```
actions:   a1   a2   a3   a4   a5   (recorded by DevTools instrument)
states:  s0─►s1─►s2─►s3─►s4─►s5
                    ▲
        jump to s3 = reduce(s0, [a1,a2,a3])   ← recomputed, not stored-and-restored
```

This is why the three Redux principles are not stylistic preferences but **hard preconditions** for the feature:

1. **Pure reducers** — if a reducer did I/O or read `Date.now()`, replaying `a1..ak` would not reproduce the original `sk`; the timeline would lie.
2. **Serializable actions and state** (Q29) — DevTools serializes the action log to display, persist, and import/export it; a `Date` or class instance corrupts the replayed state.
3. **Immutability** — recomputation relies on each reducer step producing a fresh object; in-place mutation would make `s0` already equal to `s5`, so replaying would start from the wrong baseline.

The practical constraints to call out: **effects are not replayed** (they're side effects — DevTools replays the *action stream* through reducers only, so re-traveling won't refire HTTP calls, which is correct), and high action volume degrades the tool (Q26). `provideStoreDevtools({ maxAge: 50, autoPause: true })` caps the retained history and pauses recording when the extension window is closed, both to bound memory. SignalStore-based state needs `withDevtools()` from the signals tooling to participate, because it doesn't flow through the central action stream.

### 🟠 Advanced — extended

#### Q40. [Theory] Explain `combineReducers` / reducer composition. How is the global reducer assembled and why does each slice only see its own state?

The global store is a tree, but there is no single hand-written root reducer. NgRx assembles one via **reducer composition**: `combineReducers` (used internally by `provideStore`/`provideState`) takes a map of `{ featureKey: featureReducer }` and produces a single root reducer that, for each incoming action, calls **every** slice reducer with **only its own sub-slice of state**, then reassembles the results into a new root object.

```
rootReducer(state, action) =>
  {
    auth:   authReducer(state.auth, action),     // sees only state.auth
    cart:   cartReducer(state.cart, action),     // sees only state.cart
    router: routerReducer(state.router, action),
  }
// Each slice is isolated: cartReducer cannot read or write state.auth.
```

This composition is the structural backbone of the pattern's scalability. Two properties fall out. First, **every reducer sees every action** — there is no routing of actions to "their" reducer; an action dispatched anywhere is offered to all slices, which is precisely how one user-event action can update several slices at once (e.g. `logout` clears `auth`, `cart`, and `preferences` if each listens). Second, **slice isolation** means a feature reducer is a self-contained pure function over its own state, so features compose without coupling — you can lazy-load a slice and splice it into the tree at runtime (`provideState`), and removing a feature can't break others' reducers.

The cost of "every reducer sees every action" is the recompute concern: with N slices, each dispatch invokes N reducer functions. In practice each is a cheap `switch`/map-lookup that returns the same reference for actions it doesn't handle (so `combineReducers` returns the *same* root object when nothing changed, preserving top-level reference equality and short-circuiting `distinctUntilChanged`). This is why a reducer must return `state` unchanged (same reference) for actions it ignores — returning a shallow clone on every action would defeat the optimization across the whole tree.

#### Q41. [Theory] How do NgRx runtime checks enforce immutability at runtime, and what is the performance/production trade-off?

`strictStateImmutability` and `strictActionImmutability` are implemented with **`Object.freeze`**. When enabled, NgRx deep-freezes the state object after each reduction and freezes every dispatched action. A frozen object throws in strict mode (and silently fails otherwise — but Angular runs in strict mode) when any code attempts to assign to a property. So an accidental in-reducer mutation like `state.count++` or `action.items.push(x)` throws synchronously *at the mutation site*, instead of producing the insidious "stale because reference didn't change" bug far away.

```typescript
// With strictStateImmutability on, this THROWS instead of silently corrupting:
on(addItem, (state, { item }) => {
  state.items.push(item);   // ❌ TypeError: Cannot add property, object is not extensible
  return state;             //    (and would have broken memoization anyway)
});
// Correct:
on(addItem, (state, { item }) => ({ ...state, items: [...state.items, item] }));  // ✅
```

The trade-off, and the reason these are **dev-only by default**, is cost: deep-freezing the entire state tree on every dispatch is O(size of state) per action, which on a large tree dispatched at high frequency is real overhead. NgRx therefore intends them as **development-time guards** — you enable them in dev to catch mutation bugs early, and they impose no cost in production where you simply don't enable them (or use `isDevMode()` gating). This mirrors how Immer-based RTK gets the same guarantee differently: Immer wraps draft state in a `Proxy` and produces an immutable result, shifting the enforcement from "freeze and detect violations" to "make mutation syntactically safe."

The deeper point for an interview: immutability in NgRx is a *convention enforced by tooling*, not by the language. The runtime checks exist because the entire performance model — `distinctUntilChanged`, selector memoization, OnPush change detection — is built on reference equality, and reference equality is only meaningful if you never mutate. The checks turn a silent, distant performance/correctness bug into a loud, local error during development.

#### Q42. [Theory] Compare the reactivity model of the classic Store (RxJS) with SignalStore (Signals). What is "glitch-free" and push vs pull?

The classic Store is **push-based** via RxJS: a state change *pushes* through the observable graph — `select` emits, mapped, filtered by `distinctUntilChanged`, into subscribers (the `async` pipe). Subscribers are eagerly notified the instant upstream emits. SignalStore is built on Angular Signals, which use a **push-then-pull (lazy)** model: writing a signal *marks* dependents dirty (push), but `computed` values are only *recomputed when read* (pull), and only if a dependency actually changed. The template reads signals during change detection, pulling the current values.

The consequence is **glitch-freedom**. A "glitch" is a transient, inconsistent intermediate value observed by a consumer. Consider `c = computed(() => a() + b())` where a single update changes both `a` and `b`. With naive push propagation (a hazard in some reactive systems and easy to hit with poorly composed RxJS using `combineLatest`), a consumer might briefly see `c` computed from the new `a` but the old `b` — an inconsistent intermediate. Angular Signals guarantee `c` is never observed in that half-updated state: because `computed` is pull-based and recomputes once, on read, after all writes settle, you only ever see consistent snapshots.

```
RxJS combineLatest(a$, b$):  a emits → combineLatest fires (new a, OLD b)  ← glitch
                             b emits → combineLatest fires (new a, new b)
Signals computed(a()+b()):   write a, write b → mark dirty
                             read c → compute once with (new a, new b)     ← glitch-free
```

| Dimension | Classic Store (RxJS) | SignalStore (Signals) |
|-----------|---------------------|----------------------|
| Propagation | push (eager) | push-mark + pull (lazy read) |
| Glitches | possible with `combineLatest` fan-in | structurally glitch-free |
| Read semantics | subscribe, async | synchronous `store.value()` |
| Equality | `distinctUntilChanged` (ref) | per-signal `equal` fn (ref default) |
| Async | native (streams, cancellation) | needs `rxMethod`/`toSignal` bridge |
| Memory | manual unsubscribe / `async` pipe | auto-tracked, no subscription leak |

The architect's read: Signals are superior for *synchronous derived state and template binding* (no subscription management, glitch-free, synchronous reads), while RxJS remains superior for *event streams over time* (debounce, cancellation, retry, websockets). NgRx SignalStore embraces this by being Signal-first with `rxMethod` as the RxJS bridge — you compose each primitive where it is strongest.

#### Q43. [Theory] In SignalStore, `patchState` replaces state. How is mutation detected, and what does the per-signal equality function control?

`patchState(store, partial | updaterFn)` computes the next state by **shallow-merging** the partial (or applying the updater) onto the current state, then writing it back via the store's underlying writable signal. Like the classic store, SignalStore relies on **immutable updates plus reference equality** — `patchState` produces a new state object, the root signal's value reference changes, and dependents are marked dirty. If you mutate the existing state object instead of returning a new one, the reference is unchanged and dependents never re-evaluate. SignalStore ships a dev-mode guard (analogous to runtime checks) that warns/throws on direct state mutation.

```typescript
withMethods((store) => ({
  // ✅ new object → reference changes → computed signals re-pull
  add: (item: Item) => patchState(store, (s) => ({ items: [...s.items, item] })),
  // ❌ mutates in place → same reference → dependents never update (dev guard catches this)
  addWrong: (item: Item) => patchState(store, (s) => { s.items.push(item); return s; }),
}));
```

The per-signal **equality function** is the second control surface and a subtler point. Every signal (and `computed`) has an `equal` comparator, defaulting to `Object.is` (reference equality for objects). After a write, Angular compares the new value to the old with `equal`; if they're "equal," **dependents are not notified at all** — the change is swallowed. This is the Signals analog of `distinctUntilChanged`. You can supply a custom `equal` (e.g. a deep/structural comparator for a small value object) so that a `patchState` producing a *reference-different but value-equal* object does **not** wake dependents — directly addressing the "new reference, no real change" invalidation problem from Q25, but at the signal level.

The trade-off: a custom deep-equality `equal` runs on every write to that signal, so it's only worthwhile for small objects where avoided downstream recomputation outweighs the comparison cost. For large collections, keep the default reference equality and ensure upstream code doesn't manufacture new references gratuitously — the same discipline as the classic store, just enforced through a different mechanism.

#### Q44. [Theory] What exactly is `rxMethod` in `@ngrx/signals`, and how does it bridge the imperative Signal world with RxJS?

`rxMethod` creates a **reusable, side-effecting reactive method** inside a SignalStore that is backed by an RxJS pipeline but callable like a plain function. It returns a function you can invoke with a static value, a Signal, or an Observable; internally it pushes that input into a source `Subject`, runs your RxJS operator chain (where you put `switchMap`, `debounceTime`, `catchError`, etc.), and **manages the subscription's lifetime automatically** — tied to the store's injector, so it cleans up when the store is destroyed.

```typescript
withMethods((store, api = inject(UserApi)) => ({
  loadUsers: rxMethod<string>(            // accepts value | Signal<string> | Observable<string>
    pipe(
      debounceTime(300),
      distinctUntilChanged(),
      tap(() => patchState(store, { loading: true })),
      switchMap((query) =>
        api.search(query).pipe(
          tapResponse({
            next: (users) => patchState(store, { users, loading: false }),
            error: () => patchState(store, { loading: false }),
          }),
        ),
      ),
    ),
  ),
}));

// Caller can pass a Signal — the method re-runs whenever the signal changes:
// store.loadUsers(this.searchTermSignal);
```

The bridge is bidirectional and is the point. **Signal → RxJS:** pass a Signal as the argument and `rxMethod` uses `toObservable` to convert it, so the pipeline re-runs whenever that signal changes — declarative reactive data loading without a manual subscription. **RxJS → Signal:** inside the pipeline you call `patchState`, writing results back into the store's signals. So `rxMethod` is precisely the seam where the time-domain power of RxJS (debounce, cancellation, retry) meets the synchronous, glitch-free read model of Signals.

This is what lets a SignalStore drop into an existing NgRx codebase incrementally (Q22): your existing RxJS-based API services and operators (`switchMap`, `concatMap`, `tapResponse`) work unchanged inside `rxMethod`, while the *consumption* side becomes Signals. The contract mirrors the classic effects contract — terminate or cancel inner observables and catch errors inside the flattening operator (`tapResponse` is the SignalStore-friendly way to handle next/error without breaking the outer stream).

#### Q45. [Theory] `withEntities` (`@ngrx/signals/entities`) vs `@ngrx/entity` — same concept, what's actually different?

Both solve normalized-collection management with the identical underlying data model — `{ ids, entityMap }` (SignalStore) mirroring `@ngrx/entity`'s `{ ids, entities }` — and both give O(1) keyed CRUD plus default selectors. They are the same *idea* deliberately ported to the Signals world. The differences are mechanical and ergonomic rather than conceptual.

```typescript
import { signalStore, withState } from '@ngrx/signals';
import { withEntities, setAllEntities, addEntity } from '@ngrx/signals/entities';

export const UsersStore = signalStore(
  withEntities<User>(),                       // adds entities(), entityMap(), ids() signals
  withMethods((store, api = inject(UserApi)) => ({
    load: rxMethod<void>(pipe(switchMap(() =>
      api.getAll().pipe(tap((users) =>
        patchState(store, setAllEntities(users)),   // updater fn, not adapter method
      )))))
  })),
);
// Reads are SIGNALS: store.entities(), store.ids(), store.entityMap()
```

| Aspect | `@ngrx/entity` | `@ngrx/signals/entities` (`withEntities`) |
|--------|----------------|-------------------------------------------|
| Lives in | classic Store reducers | SignalStore feature |
| Updates | `adapter.addOne(...)` returning new state | `patchState(store, addEntity(...))` updater fns |
| Reads | selectors → Observables | signals (`entities()`, `ids()`) |
| Adapter object | `createEntityAdapter()` instance | none — standalone updater functions |
| Multiple collections | one adapter per slice, manual nesting | named collections via `{ collection: 'x' }` config |
| Selector wiring | `adapter.getSelectors()` boilerplate | auto-exposed signals |

Two practical distinctions matter most. First, **no adapter instance**: `withEntities` provides free-standing updater functions (`setAllEntities`, `addEntity`, `updateEntity`, `removeEntity`) you pass to `patchState`, rather than an adapter object whose methods you call — less ceremony, more tree-shakable. Second, **named collections are first-class**: a single SignalStore can hold multiple entity collections (`withEntities({ entity: type<Book>(), collection: 'books' })`) producing prefixed signals (`booksEntities()`), whereas with classic `@ngrx/entity` you typically dedicate one adapter per feature slice and compose them manually. The `sortComparer`/`selectId` customization concepts carry over directly. Net: identical normalization theory, Signals-native surface, less boilerplate, better multi-collection ergonomics.

#### Q46. [Theory] ComponentStore: how do `updater`, `effect`, and `select` differ in their reactivity contracts, and why does `effect` accept an Observable?

ComponentStore exposes three primitives with deliberately distinct contracts. **`updater`** is the synchronous, pure state-transition primitive — `updater((state, value) => newState)` — analogous to a reducer case. It returns a function you call imperatively; each call synchronously computes and commits new state. **`select`** is the read side — `select(projector)` returns a memoized, `distinctUntilChanged`-filtered Observable (or Signal via `selectSignal`) of derived state, equivalent to a NgRx selector but scoped to the component instance. **`effect`** is the async/side-effect primitive, and its signature is the interesting one.

```typescript
// effect takes an Observable of triggers and returns an Observable;
// you wire the trigger stream THROUGH operators, you do not call it once.
readonly load = this.effect((id$: Observable<string>) =>
  id$.pipe(
    switchMap((id) => this.api.get(id).pipe(
      tapResponse(
        (data) => this.patchState({ data }),     // commit via patchState/updater
        (err) => this.patchState({ error: err }),
      ),
    )),
  ),
);
// Calling this.load('42') pushes '42' into id$. Calling this.load(this.id$)
// SUBSCRIBES the effect to an entire stream of ids.
```

`effect` accepts an Observable because it models a **long-lived reactive process**, not a one-shot call. The function you pass receives the *stream of all invocations* (`id$`), so you place your flattening operator (`switchMap`/`concatMap`/`exhaustMap`) once, governing how concurrent triggers interleave — exactly the cancellation/queueing concern from Q9, but scoped locally. The returned function is overloaded: pass a value and it emits that value into the trigger subject; pass an *Observable* and the effect subscribes to it, so the effect re-runs for every emission of that source. This is what lets a ComponentStore effect react to, say, a route-param Observable for its entire lifetime.

The unifying contract: `updater` and `patchState` are the **only** ways to write state (keeping writes synchronous and traceable); `effect` orchestrates async work and *delegates the actual write* back to `updater`/`patchState`; `select` is pure read. ComponentStore auto-unsubscribes all three when the host component is destroyed (it implements `OnDestroy`), which is the lifecycle guarantee that makes it safe for local, component-scoped state without manual teardown.

#### Q47. [Practical] Why does normalization (the `{ ids, entities }` shape) matter beyond O(1) lookups? Explain the deeper data-modeling argument.

The performance story — O(1) keyed access and update versus O(n) array scans — is the headline, but the deeper reason normalization is the *correct* default for collections in a store is about **a single source of truth for each record**. In a normalized store every entity exists exactly once, keyed by id; everything else references it *by id*. An array-of-objects (denormalized) shape inevitably duplicates a record when the same user appears in a "team members" list and a "recent activity" list — and now you have two copies that can drift out of sync, the classic cache-coherence bug.

```
DENORMALIZED (duplication, drift):           NORMALIZED (single source of truth):
teams: [{ id:1, members:[{id:9,name:"Ann"}]}]   users:  { 9: { id:9, name:"Ann" } }
recent: [{ id:9, name:"Ann (stale)" }]          teams:  { 1: { memberIds: [9] } }
        ▲ two copies, one update misses one      recent: [9]
                                                         ▲ all reference user 9 once
```

This maps directly onto database normalization theory. Denormalized client state has the same anomalies relational normalization eliminates: **update anomalies** (must update N copies, miss one → inconsistency), **insertion/deletion anomalies** (deleting a team shouldn't delete the user record it embedded). Normalizing to `{ ids, entities }` plus id-references means a `updateUser` touches one place and *every* view that references that id reflects the change automatically through selectors — which is the whole point of a single store: one update, globally consistent reads.

The cost, and the trade-off to state honestly: **the UI almost always wants denormalized, joined shapes** (a team *with* its hydrated members), so you push the join into **memoized selectors** — `selectTeamWithMembers` combines the `teams` and `users` slices. This is the right place for the join because it is computed, cached, and reference-stable. So the architecture is: **normalize on write (storage), denormalize on read (selectors)** — store data flat and canonical, project it into view shapes lazily and memoized. `@ngrx/entity` and `withEntities` exist precisely to make the normalized-write side cheap and bug-free.

### 🔴 Expert — extended

#### Q48. [Theory] Is NgRx a state machine? Compare the reducer model with explicit FSMs (XState) and explain what each can and cannot prevent.

A reducer is *a* transition function `(state, action) => state`, which superficially resembles a finite state machine's transition function `(state, event) => state`. But there is a decisive difference: a classic NgRx reducer has an **unbounded, implicit state space** — `state` is an arbitrary data object, and *any* action is accepted in *any* state, with the reducer free to compute any next state. An explicit FSM has a **finite, enumerated set of named states** and declares, per state, *which* events are even legal and where they lead. The reducer says "given whatever we have, compute the next data"; the FSM says "we are in state `loading`, and in `loading` only `success` and `failure` are valid transitions."

```
Reducer (implicit):  any action accepted in any state; illegal combinations are
                     representable and must be guarded by hand (if/else in the reducer).

FSM (explicit):      states { idle, loading, loaded, error }; transitions declared:
   idle --FETCH--> loading
   loading --SUCCESS--> loaded
   loading --FAILURE--> error
   (sending FETCH while loading is IMPOSSIBLE — not just discouraged)
```

The practical consequence is what each *prevents*. The reducer model makes **impossible states representable**: you can end up with `{ loading: true, error: 'x', data: [...] }` simultaneously because three independent booleans/fields aren't mutually constrained — and the bug is a missing guard, not a structural impossibility. An FSM makes those states **unrepresentable**: `loading`, `error`, and `loaded` are mutually exclusive named states, so the type system and the machine reject the contradiction. This is why XState shines for **complex multi-step workflows** (checkout, multi-page wizards, retry/timeout/auth flows) — the transition table is exhaustive, statically analyzable, and visualizable.

The synthesis an architect should articulate: NgRx and XState are not competitors but operate at different granularities. You can model a *feature's process* as an XState machine and let NgRx hold the *application state* the machine reads/writes — or encode FSM discipline inside reducers by modeling state as a **discriminated union** (`type State = {status:'idle'} | {status:'loading'} | {status:'loaded'; data:T} | {status:'error'; error:E}`) instead of a flat bag of optional fields. That discriminated-union technique gives you most of the FSM's "impossible states are unrepresentable" benefit within plain NgRx, and is the single most underused way to make reducers correct by construction.

#### Q49. [Theory] Server state vs client state: why do RTK Query / TanStack Query argue that putting fetched data in a normalized store is often an anti-pattern?

The core argument distinguishes two fundamentally different kinds of state. **Client state** is *owned* by the client — UI toggles, form drafts, a wizard's current step, selected filters. The client is the source of truth; it can be mutated freely and is authoritative. **Server state** is *borrowed* — it lives on the server, the client holds a **cache** of it, and that cache is shared, asynchronous, and **can become stale at any moment** because other clients (or background jobs) mutate the server independently. Treating borrowed data as if you own it is the category error.

When you hand-manage server data in NgRx, you re-implement a cache badly. Every entity needs `loading`/`error`/`lastFetched` bookkeeping, you write effects for fetch-on-mount, you hand-roll deduplication of in-flight requests, cache invalidation, refetch-on-focus, retry, and pagination merging — hundreds of lines of "loading/error/data" boilerplate that is generic and easy to get subtly wrong (stale-while-revalidate races, double fetches, no garbage collection of unused data). Server-state libraries (**RTK Query**, **TanStack Query**, **Apollo**) treat the data as a **cache keyed by query**, and provide staleness, deduplication, background refetch, and GC as first-class concerns.

```
Hand-rolled in NgRx:  action loadX → effect fetch → success/failure actions
                      → reducer sets {data, loading, error} → selectors
                      → manual: dedupe, invalidate, refetch, retry, GC  ← you own all this

Query library:        useQuery(['x', id], fetchX)
                      → caching, dedupe, stale time, refetch-on-focus,
                        GC of unused keys, retry  ← provided
```

The nuanced expert position (consistent with Q24/Q28): **use a query/cache library for server data; reserve the store for genuine client state and the small slice of server-derived data that needs cross-feature sharing or local mutation.** NgRx hasn't ignored this — its **`@ngrx/operators` + Entity** combo and the emerging patterns around SignalStore-with-resource address parts of it, and Angular's own `resource()`/`httpResource()` primitives are converging on the same "server data is a reactive cache" model. The anti-pattern isn't "server data in a store" absolutely; it's *hand-managing the cache lifecycle in reducers/effects* when a dedicated cache abstraction exists. The litmus test: if most of your store and effects are loading/error/data plumbing for remote reads, you're rebuilding a query library.

#### Q50. [Theory] How would you design state management for a micro-frontend architecture where each MFE may use a different framework? What are the hard constraints?

The hard constraint is **isolation**: independently deployed, independently versioned MFEs cannot share a single in-memory store object without coupling their release cycles and risking version skew (MFE-A ships a state-shape change, MFE-B compiled against the old shape breaks). So the foundational rule is **one store per MFE** — each owns its internal state in whatever it likes (NgRx, RTK, Pinia, vanilla Signals). The store is an *implementation detail* behind the MFE boundary and never crosses it directly.

For the genuinely-shared sliver (authenticated user, theme, feature flags, a cross-app cart), you need a framework-agnostic integration mechanism. Three patterns, in increasing coupling:

```
1. Event bus (loosest):  shared CustomEvent / RxJS Subject on window or a tiny
   shared lib. MFEs publish/subscribe events ("user.loggedIn"). No shared state shape,
   only an event contract. Each MFE mirrors what it needs into its own store.

2. Shared-kernel store:  a tiny, separately-versioned package exposing a minimal
   observable store (e.g. a Signal or BehaviorSubject) for the few truly-global atoms.
   Framework-agnostic API (subscribe/getSnapshot/setState). MFEs depend on its CONTRACT.

3. Host-owned state passed down (tightest): the shell app owns shared state and passes
   it into MFEs as inputs/props/events. Clear ownership, but couples MFEs to the shell.
```

The design principles to articulate: (a) **contracts over shared objects** — MFEs agree on an *event/message schema* or a *minimal store API* with semver discipline, never on a concrete NgRx state tree; (b) **each MFE projects shared signals into its own store** so its components stay idiomatic and it can survive the shared channel being absent (graceful degradation); (c) **server state should be cached per-MFE** via query libraries rather than shared, because sharing a cache across independently-deployed apps reintroduces coupling and cache-invalidation hazards; (d) **the shared kernel must be tiny** — every atom you put in it is a coordination cost across teams, so it holds only what is unavoidably global (identity, theme), mirroring the "store as a scarce resource" lesson from Q19 but at the org/architecture level.

The expert framing: micro-frontends turn the global-vs-local decision into a *global-vs-local-vs-shared-kernel* decision, and the same discipline applies — the shared channel is the most expensive place to put state, so it must be the smallest. Module Federation can technically share a store singleton, but doing so trades the entire independent-deployability benefit MFEs exist to provide; most mature architectures choose an event bus or thin shared-kernel and keep stores private.

#### Q51. [Theory] Walk through the precise sequence of events from `store.dispatch(action)` to a component's view updating. Where can it go wrong?

Tracing the full path forces an understanding of how the layers compose. The ordered sequence:

```
1. store.dispatch(action)
2.   → action pushed into ActionsSubject (the central action stream)
3.   → State service's scan runs the ROOT reducer (combineReducers) over (currentState, action)
4.        → every slice reducer called with its sub-state; each returns new-or-same reference
5.   → if root state reference changed, State BehaviorSubject emits the new state
6.   → in PARALLEL, Effects subscribed to ActionsSubject receive the action (AFTER reducers, step 3)
7.   → store.select(selector) pipelines: input selectors run, projector runs IF inputs changed,
        distinctUntilChanged filters by reference → emits to subscribers only on real change
8.   → async pipe (or selectSignal) receives the value, calls markForCheck()
9.   → Angular change detection runs (NgZone schedules a tick); OnPush components whose
        bindings changed re-render; the new value paints
```

The ordering facts that matter: **reducers run before effects** for the same action (step 3 before step 6), so an effect can read post-reduction state (Q35); and **selectors are pull-evaluated lazily** off the state emission with memoization + `distinctUntilChanged` gating propagation (step 7).

Where it breaks, mapped to steps:

- **Step 4 — mutation:** a reducer mutates instead of returning a new object → root reference unchanged → step 5 doesn't emit → **view never updates** despite "state changing." (Caught by `strictStateImmutability`, Q41.)
- **Step 5/7 — gratuitous new references:** a reducer returns a fresh object/array when nothing changed → `distinctUntilChanged` doesn't filter → projector reruns → unnecessary re-renders (Q25).
- **Step 6 — dead effect:** `catchError` outside the flattening operator killed the outer stream earlier → the action arrives but no effect responds → **no HTTP, silent failure** (Q8).
- **Step 8/9 — missing async/OnPush:** reading state with a one-time subscribe assigned to a field, or no `markForCheck`, under OnPush → value updates in memory but **CD never runs** → stale view. Or the value is mutated outside Angular's zone (e.g. a websocket callback) → `markForCheck` never schedules a tick (mitigated by `strictActionWithinNgZone` / zoneless signals).
- **Step 7 — selector returns undefined:** a feature-state selector reads a lazy slice not yet loaded → `undefined` → downstream projector throws (Q18).

Being able to name the layer for a given symptom ("view doesn't update on dispatch") is the difference between guessing and debugging: mutation kills it at step 5, missing OnPush wiring kills it at step 9, and a dead effect kills the *side effect* at step 6 while the reducer path still works.

#### Q52. [Practical] Your team must support SSR (Angular Universal) with NgRx. What changes about state, hydration, and effects, and what are the pitfalls?

SSR runs your reducers and effects **on the server** to produce an initial HTML render, then the browser **rehydrates** — and several store assumptions that hold on the client break on the server. The central issues are state transfer, effect behavior, and platform-specific APIs.

**State transfer / avoiding double-fetch.** On the server you dispatch loads, effects fetch data, the store fills, and Angular renders HTML. Without transfer, the browser boots with an *empty* store and refetches everything — flicker, wasted requests, and a hydration mismatch. The fix is to serialize the server's final store state into the HTML (via `TransferState` / `provideClientHydration`) and **seed the client store from it** (a hydration meta-reducer reading `TransferState` on `INIT`), so the client starts already-populated and skips the refetch.

```typescript
// Server fills the store → serialize to TransferState → client meta-reducer seeds from it.
export function ssrHydrationMetaReducer(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action) => {
    if (action.type === INIT && !state && isPlatformBrowser(platformId)) {
      const transferred = transferState.get(STORE_KEY, null);   // set on server
      if (transferred) state = transferred;
    }
    return reducer(state, action);
  };
}
```

**Effect pitfalls on the server.** Effects that touch **browser-only APIs** (`localStorage`, `window`, `document`, `IntersectionObserver`) crash or no-op on the server — guard them with `isPlatformBrowser`. Effects with **infinite or long-lived streams** (websocket subscriptions, `interval`, `fromEvent`) must *not* run during SSR: the server render waits for pending work, and a never-completing stream can hang the render or leak. Run only the bootstrap fetch effects on the server and defer realtime/persistence effects to the browser (platform-guarded, or via `isPlatformServer` early-return). Also, effects that fetch must **complete before render** for their data to appear in HTML — Angular waits on the app to stabilize, so the request must be a finite HTTP call, not a stream.

**Other pitfalls to name:** the **localStorage hydration meta-reducer (Q21) must be browser-guarded** or it throws on the server; **`Date.now()`/random in reducers** produce server/client mismatches and hydration errors (another reason for purity, Q39); and **per-request state isolation** — on the server the app is bootstrapped per request, so the store must not be a true cross-request singleton holding one user's data into another's render (Angular's per-request platform instance handles this, but custom global caches outside DI can leak between users — a serious security bug). The summary: SSR makes the store's purity and platform-neutrality non-negotiable, turns hydration from a nicety into a correctness requirement, and forces a clean split between bootstrap effects (run on server) and browser-only effects (deferred).

#### Q53. [Theory] Explain selector memoization edge cases: the single-slot cache, `release()`, custom equality, and why `createSelectorFactory` exists.

Q6 established the one-entry cache; the expert-level edges are about its *limits* and the escape hatches. The default `createSelector` memoizer (`defaultMemoize`) caches **exactly one** `(lastInputs, lastOutput)` pair and compares inputs with reference equality (`isEqual` defaulting to `===`). This produces several behaviors worth understanding precisely.

**Single-slot thrashing (revisited with mechanism).** Because there is one slot, a parameterized selector queried alternately with id `A`, then `B`, then `A` recomputes every single time: the cached inputs for `A` are overwritten by the `B` call, so the next `A` call misses. The factory pattern (Q7) gives each call site its own selector instance, hence its own slot. The deeper fix for *truly* dynamic keys is a multi-slot memoizer.

**`release()`** clears a selector's cache, dropping references to the last input and output. It matters for **memory** (a selector closing over a large computed array holds it alive indefinitely) and for **tests** (reset memoization between cases to assert recomputation). You rarely call it in app code, but on a selector that memoized a huge structure no longer needed, releasing frees it.

```typescript
import { createSelectorFactory, defaultMemoize, resultMemoize } from '@ngrx/store';

// Custom equality on the RESULT: treat value-equal arrays as unchanged so downstream
// selectors/OnPush don't invalidate on a reference-different-but-equal recompute.
export const selectActiveIds = createSelectorFactory(
  (projector) => resultMemoize(projector, deepEqual),  // compare outputs deeply
)(selectUsers, (users) => users.filter(u => u.active).map(u => u.id));
```

**`createSelectorFactory` and custom equality** are the real power tool. `createSelector` is just `createSelectorFactory(defaultMemoize)`. By passing your own memoize function you control (a) **input equality** — e.g. structural comparison so value-equal-but-reference-different inputs don't trigger recompute (mitigating the Q25/Q43 "new reference, no real change" problem), (b) **result equality** via `resultMemoize` — so even when the projector reruns, a value-equal *output* keeps the previous reference, preventing downstream invalidation cascades, and (c) **multi-entry caching** — supply a memoizer that caches N entries keyed by argument, eliminating thrashing for a bounded set of dynamic keys without per-call-site factories.

The trade-off is the recurring one: custom deep-equality runs a comparison on every evaluation, so it pays off only when the avoided downstream work (recomputation, re-renders) exceeds the comparison cost — typically true for small derived values feeding wide selector subtrees, false for large collections where reference equality plus disciplined upstream updates is cheaper. Knowing that `createSelector` is a thin wrapper over a pluggable memoizer — and that the cache is one slot of *reference*-compared inputs by default — is what lets you diagnose and fix the non-obvious "my selector recomputes constantly" and "my view re-renders on value-equal data" bugs.

#### Q54. [Theory] Actions as "events" vs "commands": why does NgRx insist on event-style actions, and what is the dispatched-from-many-reduced-by-many model?

There are two philosophies for what an action *is*. A **command** action names an imperative instruction: `[Cart] Add Item` reads like "do this to the state," with a one-to-one mapping to a reducer mutation. An **event** action names something that *already happened*, attributed to its source: `[Product Page] Add To Cart Button Clicked`. NgRx (and Redux best practice) strongly favors the event style, and the distinction is not pedantic — it changes the entire shape of the data flow.

The event model enables a **many-to-many relationship between actions and the things that handle them**. One event action can be reduced by *several* reducers (the click updates the cart slice, increments an analytics counter slice, and closes a modal slice) and consumed by *several* effects (persist the cart, fire a telemetry beacon), all independently subscribing to that one event. Conversely, several different events can converge on one reducer case via multiple `on(...)` clauses. This fan-out/fan-in is impossible with command actions, because a command implies a single owner executing a single instruction.

```
Command model (1:1, tight):     [Cart] Add Item  ──►  cart reducer only

Event model (many:many, loose):
  [Product Page] Add To Cart ──┬──► cart reducer (add line)
                               ├──► analytics reducer (increment)
                               ├──► ui reducer (close modal)
                               ├──► persistEffect (save to API)
                               └──► telemetryEffect (beacon)
```

The payoff is **decoupling and a readable audit trail**: the dispatcher (a component) doesn't know or care who reacts; it only announces what happened. New features can react to an existing event without the original code changing — the Open/Closed principle expressed in the action stream. The DevTools timeline becomes a literal narrative of user intent ("clicked add to cart → cart updated → saved") rather than a list of internal mutations. The command style, by contrast, recreates RPC-over-the-store: the component effectively calls a method, the indirection buys nothing, and action reuse creeps in (the same `[Cart] Set` dispatched from five places), destroying the ability to tell *why* state changed. This is the theoretical foundation under "good action hygiene" (Q38).

#### Q55. [Theory] How does `@ngrx/router-store` integrate Angular routing into the store, and why is router-as-state useful?

`@ngrx/router-store` connects the Angular Router to the NgRx store so that **navigation becomes part of the action stream and the router's state becomes a selectable store slice**. On each navigation it dispatches actions (`routerNavigation`, `routerNavigated`, `routerCancel`, `routerError`) and reduces a serialized snapshot of the router state (URL, params, query params, route data) into a `router` feature slice. You register it with `provideRouterStore()` and a `routerReducer`, then read route data through selectors like `selectRouteParams`, `selectQueryParams`, `selectRouteData`.

```typescript
// Read the :id route param as a memoized, composable selector:
export const selectSelectedUserId = createSelector(
  selectRouteParams,
  (params) => params['id'],
);
// Compose it with a feature selector to derive the currently-routed entity:
export const selectCurrentUser = createSelector(
  selectUserEntities, selectSelectedUserId,
  (entities, id) => (id ? entities[id] : undefined),
);
```

Why this is useful comes down to **treating the URL as a first-class piece of derived state**. The router holds genuinely shared, navigation-surviving state (which entity is selected, current filters in query params) that other parts of the app legitimately need — and pulling it into selectors lets you *compose route state with feature state* memoized, instead of injecting `ActivatedRoute` everywhere and managing subscriptions by hand. Effects can react to navigation (`ofType(routerNavigatedAction)` → load the entity for the new route), which centralizes "fetch-on-navigate" logic out of component `ngOnInit`s.

The trade-offs to flag: the router snapshot must be **serializable** (Q29), so router-store uses a custom serializer (`MinimalRouterStateSerializer` is recommended) to strip non-serializable router internals and keep the slice small — the default full serializer can bloat state and DevTools. And navigation actions add to action volume, so on a nav-heavy app this contributes to the Q26 noise problem. The pattern shines when route-derived state is read in many places or drives data loading; it's overkill if a component just needs its own param once (use `ActivatedRoute` directly there).

#### Q56. [Practical] When does a hand-rolled `BehaviorSubject`/Signal "store-in-a-service" suffice, and at exactly what point does it justify adopting NgRx?

The minimal viable store is a service wrapping a `BehaviorSubject` (or, post-Angular-16, a `signal`), exposing a read-only stream and mutation methods. For a surprising amount of shared state this is genuinely sufficient and *correct* — adopting NgRx over it is the over-engineering the whole guide warns about.

```typescript
@Injectable({ providedIn: 'root' })
export class CartService {
  private state = signal<CartState>({ items: [], total: 0 });
  readonly items = computed(() => this.state().items);   // read-only derived
  add(item: Item) { this.state.update(s => ({ ...s, items: [...s.items, item] })); }
}
```

The honest comparison: the DIY service gives you shared, reactive, single-source-of-truth state with near-zero ceremony. What it does **not** give you — and what marks the threshold to NgRx — is the bundle of cross-cutting concerns that get expensive to hand-roll once you need *several* of them at once:

| Need | DIY service | NgRx provides |
|------|-------------|---------------|
| Shared reactive state | ✅ trivial | ✅ |
| Derived/memoized state | manual `computed` / `distinctUntilChanged` | ✅ memoized selectors, composition |
| Time-travel / replay debugging | ❌ build it yourself | ✅ DevTools, free |
| Serializable audit trail of *why* state changed | ❌ | ✅ action log |
| Decoupled many-to-many event handling | ❌ method calls couple caller↔store | ✅ action stream |
| Structured async orchestration (cancel/queue/retry) | manual RxJS in the service | ✅ Effects with operator discipline |
| Enforced immutability / mutation guards | ❌ | ✅ runtime checks |
| Team-scale conventions for consistency | ❌ each service is bespoke | ✅ uniform structure |

The decision rule I apply: stay with the DIY service while state is shared but **simple, locally-owned, and orchestrated by one or two methods**. Reach for NgRx when you accumulate *several* of: complex async flows needing cancellation/ordering, a need to debug *how* state arrived at its value, many independent consumers reacting to the same events, or a large team needing a consistent pattern across dozens of features. The mistake in both directions is real — NgRx for a three-field toggle service is bloat; a sprawl of bespoke `BehaviorSubject` services with hand-rolled effects and no audit trail is the under-engineering that NgRx's structure exists to cure. The tipping point is *concern accumulation*, not state size.

#### Q57. [Theory] What is `provideStore`/standalone NgRx vs the `StoreModule` NgModule API, and why did NgRx move to functional providers?

NgRx historically configured the store via NgModules: `StoreModule.forRoot(reducers, { metaReducers })` at the app root and `StoreModule.forFeature(key, reducer)` in feature modules, with `EffectsModule.forRoot/forFeature` for effects. NgRx 14.3+ introduced **standalone, functional provider APIs** — `provideStore(reducers)`, `provideState(feature)`, `provideEffects(effects)`, `provideStoreDevtools()`, `provideRouterStore()` — registered in `ApplicationConfig.providers` or a route's `providers`, matching Angular's standalone-components direction (no NgModules required).

```typescript
// main.ts — standalone bootstrap
bootstrapApplication(AppComponent, {
  providers: [
    provideStore(rootReducers, { metaReducers }),
    provideEffects(RootEffects),
    provideStoreDevtools({ maxAge: 50, autoPause: true }),
    provideRouterStore(),
  ],
});

// lazy route — feature state/effects scoped to the route
export const routes: Routes = [{
  path: 'orders',
  providers: [provideState(ordersFeature), provideEffects(OrderEffects)],
  loadComponent: () => import('./orders.component'),
}];
```

The move matters for more than syntax. **Lazy feature state via route `providers`** is cleaner and more granular than module-based `forFeature` — state and effects register exactly when the route activates and tie to that route's injector, improving tree-shaking and making the lazy-loading story (Q18) explicit at the route level rather than buried in a feature module. Functional providers also compose better with the rest of standalone Angular (`provideHttpClient`, `provideRouter`) and remove the `NgModule` boilerplate that existed only to wire DI.

The trade-offs to mention: the two APIs are interoperable (you can migrate incrementally — an app can use `StoreModule.forRoot` while a new lazy route uses `provideState`), so there's no big-bang requirement. The NgModule API isn't deprecated-and-removed, but new NgRx docs and tooling lead with the standalone providers, and Angular itself is steering apps toward standalone-by-default — so greenfield code should use `provideStore`/`provideState`/`provideEffects`, and this is the expected answer for "how do you set up NgRx today."

#### Q58. [Theory] Why is choosing the wrong flattening operator a memory/correctness bug and not just a style choice? Analyze `mergeMap` and `concatMap` failure modes precisely.

The flattening operator inside an effect (or `rxMethod`/ComponentStore effect) governs how *concurrent* triggers map to inner subscriptions, and the wrong choice produces concrete bugs, not stylistic awkwardness. The four operators differ in exactly one dimension — what they do with a new source emission while a previous inner observable is still alive — and that single behavior has correctness and resource consequences.

```
                 prev inner still running, new trigger arrives:
switchMap   →  UNSUBSCRIBE (cancel) the previous, start new        (latest-wins)
concatMap   →  QUEUE the new; run after previous completes          (ordered, serial)
mergeMap    →  start new IN PARALLEL, keep previous                 (concurrent)
exhaustMap  →  IGNORE the new while previous runs                   (drop-extras)
```

**`mergeMap` failure mode — unbounded concurrency / memory.** Because `mergeMap` keeps *every* inner subscription alive concurrently, a high-frequency trigger (a websocket tick, a rapidly-firing user action, or — catastrophically — an effect feedback loop) spawns inner observables faster than they complete. Each holds resources (an open HTTP request, retained closure state); they accumulate without bound. The symptom is climbing memory and a flood of in-flight requests that can DOS your own backend. `mergeMap` is correct *only* when you know triggers are bounded/infrequent and order doesn't matter; it's the dangerous default precisely because it "works" under light load and fails under stress.

**`concatMap` failure mode — unbounded queue / latency.** `concatMap` is safe for ordering but its queue is *also* unbounded: if triggers arrive faster than the inner observable completes (a slow save endpoint hit by rapid edits), the backlog grows without limit, and the user sees ever-increasing latency as their newest action waits behind a long queue of stale ones. The state eventually reflects old work. For "only the latest matters" (typeahead, refresh) `concatMap`'s ordering guarantee is actively wrong — you process and pay for requests whose results you immediately discard; `switchMap`'s cancellation is correct there.

**The correctness frame.** `switchMap` on a write that must not be lost (a save) is a *data-loss bug* — a second save cancels the first mid-flight, and the first write never lands. `concatMap` or `mergeMap` on a "load latest" read leaks stale results that can overwrite fresh state (a race where an earlier, slower request resolves after a later one). So the mapping is: **reads where only the latest matters → `switchMap`** (cancel stale); **writes that must all happen and in order → `concatMap`** (no loss, serialized); **independent parallel work, bounded volume → `mergeMap`**; **idempotent triggers where extras are noise (button spam, refresh-while-refreshing) → `exhaustMap`** (drop duplicates). Treating this as a style choice is how subtle race conditions and memory leaks ship to production; it is one of the highest-signal NgRx interview questions for exactly that reason.

#### Q59. [Theory] Explain custom SignalStore features (`signalStoreFeature`) and how they enable composition and reuse that the classic Store cannot.

`@ngrx/signals` exposes `signalStoreFeature`, which lets you package a reusable bundle of `withState`/`withComputed`/`withMethods`/`withHooks` into a **named, parameterizable feature** that any store can plug in. This is a genuinely different composition model from the classic Store: where NgRx Store composes *state slices* (independent reducers combined into a tree), SignalStore composes *behaviors* — cross-cutting capabilities that add state, derived signals, and methods together as a unit.

```typescript
import { signalStoreFeature, withState, withMethods, patchState, type } from '@ngrx/signals';

// Reusable feature: any store gains call-status tracking + helper methods.
export function withCallState() {
  return signalStoreFeature(
    withState<{ callState: 'init' | 'loading' | 'loaded' | { error: string } }>({ callState: 'init' }),
    withComputed(({ callState }) => ({
      loading: computed(() => callState() === 'loading'),
      error: computed(() => {
        const s = callState();
        return typeof s === 'object' ? s.error : null;
      }),
    })),
    withMethods((store) => ({
      setLoading: () => patchState(store, { callState: 'loading' }),
      setLoaded:  () => patchState(store, { callState: 'loaded' }),
      setError: (error: string) => patchState(store, { callState: { error } }),
    })),
  );
}

// Compose into any store — even multiple features stack:
export const UsersStore = signalStore(
  withEntities<User>(),
  withCallState(),        // reused across UsersStore, OrdersStore, etc.
  withMethods(/* ... */),
);
```

The power is **horizontal, mixin-style reuse of behavior**, which the classic Store structurally cannot do. In NgRx Store, cross-cutting patterns like "loading/error status" or "selected-id tracking" are re-implemented per feature (an action set, reducer cases, selectors each time) or factored awkwardly via shared action creators and higher-order reducers. `signalStoreFeature` makes them composable units: `withCallState()`, `withSelectedEntity()`, `withLogging()`, `withPagination()` become a library of behaviors you snap together, with full type inference flowing through the composition (later features see the state/methods added by earlier ones). `type<T>()` lets a feature declare *required input state* so it can be generic over the host store's shape.

The trade-off and caution: deep feature stacks can make it hard to trace where a given signal or method originates (the "where did this come from" problem mixins always have), and ordering matters because each feature sees the accumulated context of those before it. But for teams, this is exactly the reuse mechanism that was missing — it turns repeated boilerplate (the Nth loading-flag implementation) into a single tested feature, and it is a core reason the NgRx team positions SignalStore as the forward-looking API rather than a thinner Store.

#### Q60. [Theory] How do Angular's newer reactive primitives — `linkedSignal`, `resource`, `effect`, `untracked` — relate to and reshape NgRx patterns?

Angular 17–19 added reactive primitives that overlap with jobs NgRx historically owned, and an architect should know where each fits. **`computed`** is pure derived state (the Signals analog of a selector). **`effect`** runs side effects when its signal dependencies change — *not* the same as an NgRx Effect (which reacts to *actions* and is meant to dispatch); Angular's `effect` reacts to *signals* and is for synchronizing with the non-reactive world (logging, DOM, third-party libs), explicitly not for state mutation. **`untracked`** reads a signal without registering it as a dependency, the escape hatch for "use this value but don't re-run when it changes."

**`linkedSignal`** (stable in v19) is writable derived state: it computes from a source like `computed`, but you can also imperatively override it, and it *resets* to the computed value when the source changes. This solves a pattern NgRx handled clumsily — "default this from server data, but let the user edit it, and re-default when new data arrives." Previously that meant a reducer case plus an effect resetting on load; `linkedSignal` expresses it in one primitive.

```typescript
// linkedSignal: selection defaults to first item, user can change it,
// resets when the list reloads — previously a reducer + reset-effect dance.
readonly items = this.store.items;                       // Signal<Item[]>
readonly selectedId = linkedSignal(() => this.items()[0]?.id ?? null);
choose(id: string) { this.selectedId.set(id); }          // override
```

**`resource`/`rxResource`** (experimental → maturing) is the biggest reshape: an async primitive that loads data from a reactive request signal, exposing `value`/`status`/`error` signals and handling cancellation when the request changes. It is Angular's native answer to the *server-state* problem (Q49) — a built-in reactive cache for async reads, directly overlapping with what `loadX → effect → success/failure → reducer` boilerplate did manually. As `resource`/`httpResource` mature, the canonical "fetch data into the store" effect shrinks dramatically.

The synthesis: these primitives **absorb the simple cases NgRx used to justify itself with**. Pure derivation → `computed`; default-but-editable state → `linkedSignal`; loading remote data → `resource`. What remains distinctly NgRx territory is the *Redux value proposition* — a serializable action audit trail, time-travel, meta-reducers, and decoupled many-to-many event handling for genuinely-shared, debuggable application state. The forward-looking position (consistent with Q28): compose framework primitives for local/derived/async-read concerns and reserve NgRx (increasingly SignalStore) for the shared, audited state where the action log earns its cost. NgRx SignalStore is explicitly being built to interoperate with these primitives rather than wrap or replace them.

#### Q61. [Practical] A websocket pushes 200 price-tick messages per second into NgRx and the app drops frames. Walk through the architectural fixes from least to most invasive.

This is a throughput problem where the *rate of state change* outpaces the render budget (one frame ≈ 16.6ms at 60fps), and the fix is to **decouple ingest rate from render rate**. I'd work from cheapest to most structural, measuring at each step.

```
1. BATCH at the source effect: buffer ticks over an animation frame, dispatch ONE action.
2. Throttle/sample the READ path so the view updates at most ~30–60fps regardless of ingest.
3. Move high-frequency state OUT of the global store into a dedicated store/Signal.
4. If still bound, virtualize rendering and/or move parsing off the main thread.
```

**1 — Batch on ingest (least invasive).** The naive effect dispatches one action per message → 200 dispatches/sec, each running the whole reducer tree + selector graph + change detection. Buffer the websocket stream over an animation frame (or a small time window) and dispatch a *single* `pricesUpdated({ batch })` action carrying many ticks. This collapses 200 reducer/CD cycles into ~60, the single biggest win, and also de-noises DevTools (Q26).

```typescript
prices$ = createEffect(() =>
  this.ws.ticks$.pipe(
    bufferTime(16),                       // collect a frame's worth
    filter((batch) => batch.length > 0),
    map((batch) => PriceActions.pricesUpdated({ batch })),  // ONE action per frame
  ),
);
// Reducer applies the whole batch immutably in one pass (entity upsertMany).
```

**2 — Decouple the read path.** Even with batched writes, a hot selector feeding many components can over-render. Apply `throttleTime`/`auditTime` (or `sampleTime`) on the consumed observable, or read via a signal whose updates are coalesced, so the *view* refreshes at a human-perceptible rate independent of how often state changes underneath.

**3 — Dedicated store for the firehose.** High-frequency, ephemeral market data arguably shouldn't live in the audited global store at all (it's not something you replay or time-travel meaningfully). Move it to a `ComponentStore`/`SignalStore` or even a plain `BehaviorSubject` scoped to the trading view, keeping the global store for the slower-moving shared state. This removes 60 dispatches/sec of noise from the global action stream entirely and isolates the perf-sensitive path.

**4 — Rendering and threading (most invasive).** If the bottleneck is now *rendering* thousands of rows rather than state churn, virtualize the list (CDK virtual scroll) so only visible rows render, ensure `OnPush` + `trackBy`/track so unchanged rows skip, and consider zoneless change detection. If message *parsing/normalization* is the cost, move it to a Web Worker and post batched results to the main thread.

The architectural through-line: the global store is not designed to be a 200Hz pipe, and forcing it to be one fights both its perf model (reducer-tree + CD per dispatch) and its purpose (auditable discrete events). The right answer batches at ingest, decouples the render cadence, and pushes the firehose to a fit-for-purpose container — mirroring the recurring lesson that you compose the right primitive per concern rather than routing everything through one store.

#### Q62. [Theory] What is `@ngrx/data`, where does it sit relative to Store/Effects/Entity, and why is it both powerful and a sharp tool?

`@ngrx/data` is a **convention-over-configuration abstraction layer built on top of Store, Effects, and Entity** that eliminates the per-entity CRUD boilerplate. Instead of writing actions, reducers, effects, selectors, and an entity adapter for every collection, you declare your entity metadata once, and `@ngrx/data` *generates* the entire NgRx machinery — action sets, entity reducers, default HTTP effects (`GET/POST/PUT/DELETE` against REST conventions), and selectors — exposed through an `EntityCollectionService` per entity type.

```typescript
// Declare metadata once:
const entityMetadata: EntityMetadataMap = { User: {}, Product: {} };

// Use a generated service — no hand-written actions/reducers/effects/selectors:
@Injectable({ providedIn: 'root' })
export class UserService extends EntityCollectionServiceBase<User> {
  constructor(factory: EntityCollectionServiceElementsFactory) {
    super('User', factory);
  }
}
// Component: this.userService.getAll();  this.userService.add(user);
//            this.userService.entities$  // Observable<User[]>, already wired
```

It sits at the **highest level of the NgRx CRUD stack**: Entity normalizes a collection; Store/Effects/Selectors wire one collection's state and HTTP; `@ngrx/data` generates *all of that* for *all* collections from metadata. For an app that is mostly standard REST CRUD over many entities, it deletes hundreds of lines of repetitive, mechanically-identical code and enforces uniformity for free — that is its power.

The "sharp tool" caveat is about the leverage cutting both ways. `@ngrx/data`'s conventions assume **REST-shaped endpoints and standard CRUD semantics**; the moment your API deviates (non-standard URLs, composite keys, custom server filtering, optimistic-update nuances, GraphQL, non-CRUD operations), you fight the abstraction — overriding the generated data service, customizing the `HttpUrlGenerator`, or dropping to raw Store/Effects for those cases, at which point the magic becomes opacity that's harder to debug than explicit code. It also obscures the action stream (generic generated actions reduce DevTools readability versus hygienic hand-written events, Q38/Q54) and steepens the learning curve for engineers who must occasionally reach beneath it. The honest interview answer: `@ngrx/data` is excellent for large apps dominated by conventional CRUD where uniformity and velocity dominate, and a poor fit for apps with idiosyncratic APIs or where most operations aren't plain CRUD — it trades explicit control for generated convention, which is exactly the trade you want until you don't.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q63. [Practical] You dispatched an action but "nothing happened" — no state change, no effect, no error. Give the checklist you run, in order.

This is the single most common beginner complaint, and almost every cause is mundane. The discipline is to walk the dispatch path (Q51) from the outside in, confirming each hop with the Redux DevTools rather than guessing. The first question is always **did the action even reach the store?** Open DevTools → the action appears in the timeline if it was dispatched. If it is *absent*, the bug is upstream of NgRx: the dispatch line never ran (a guard returned early, the click handler isn't wired), or you dispatched an action *object literal* instead of calling the creator (`store.dispatch(loadUsers)` passing the function instead of `store.dispatch(loadUsers())`).

If the action **is** in the timeline but state didn't change, the next checks in order:

```
1. Action in DevTools?           NO → dispatch never executed; wiring/guard bug.
2. State diff shows nothing?     → reducer didn't handle it. Causes:
     a. Type-string mismatch: handwritten string ≠ creator's .type (typo/case).
     b. on() registered for a DIFFERENT creator than the one dispatched.
     c. Reducer not registered for that feature key (forgot provideState).
3. State changed but view didn't? → read-path bug (OnPush + no async, see Q51 step 9).
4. Effect didn't run?            → effect class not in provideEffects, OR the outer
                                    stream died earlier (catchError outside switchMap, Q8),
                                    OR ofType lists the wrong creator.
```

The highest-leverage habit is to **always pass action creators, never strings**, to both `dispatch` indirectly and to `on`/`ofType` — that makes the type-mismatch class of bug a compile error instead of a silent no-op. The second habit is to verify the feature reducer is actually registered (a lazy feature whose `provideState` lives in a module/route that never loaded will silently swallow every action targeting it). I keep this checklist physically near new engineers because the failure is almost never NgRx itself; it's a missing registration, a passed-function-not-called, or a read-path OnPush gap.

#### Q64. [Practical] How do you wire up the Redux DevTools in a real Angular app, and what should be different between dev and prod configuration?

`provideStoreDevtools()` (standalone) or `StoreDevtoolsModule.instrument()` connects the store to the browser extension. The non-negotiable production concern is that the DevTools instrumentation **records every action and retains state history in memory** — leaving it fully enabled in production is a memory cost, a minor performance tax (it serializes actions), and an information-disclosure surface (anyone can open the extension and read your entire client state). So the config must differ by environment.

```typescript
import { isDevMode } from '@angular/core';
import { provideStoreDevtools } from '@ngrx/store-devtools';

provideStoreDevtools({
  maxAge: 50,                 // retain only the last 50 actions (bounds memory)
  logOnly: !isDevMode(),      // PROD: read-only, no time-travel/dispatch from the panel
  autoPause: true,            // stop recording while the extension window is closed
  trace: false,               // stack traces per action — expensive, dev-debugging only
  connectInZone: true,        // NgRx 17+: keep DevTools callbacks inside NgZone
});
```

The key knobs: `maxAge` caps history so a long session doesn't grow unbounded (the default 25–50 is fine; raise only when actively debugging a long flow). `logOnly: true` disables the *dispatch-from-panel* and time-travel features, which you want off in production so the panel can't be used to inject actions, while still letting you connect for read-only diagnosis if needed. `autoPause` is a cheap, large win — it stops the instrumentation from doing work when nobody is looking at the panel. `trace` captures a stack trace for every action (so you can jump to the dispatch site) but is genuinely expensive, so it stays off except during a focused debugging session.

The pragmatic stance many teams take is to **not register DevTools at all in production builds** (gate the provider behind `isDevMode()` or strip it via environment files), accepting that you lose production diagnosis in exchange for zero overhead and zero disclosure. The middle ground — `logOnly` + `maxAge` — keeps a read-only window for support engineers while bounding the cost. Either is defensible; shipping the full read-write instrumentation with unbounded history is not.

#### Q65. [Theory] What is the difference between `props<T>()` and the older `createAction` with a custom props factory, and when do you still need `createAction('type', (x) => ({...}))`?

`props<{ user: User }>()` is the declarative, common case: it says "this action carries a payload of this exact shape, spread onto the action object," and NgRx infers the creator's call signature from it. It does no runtime work — it's a phantom type marker (Q30). For the overwhelming majority of actions, `props<T>()` is all you need, and reaching past it is a smell worth pausing on.

The **function form** — `createAction('[X] Y', (a: number, b: number) => ({ sum: a + b }))` — exists for the cases where the *creator's arguments* don't map one-to-one onto the *payload shape*. The factory receives the arguments you want the call site to pass and returns the props object. You need it when you want to (a) accept positional or differently-named arguments and transform them, (b) compute or default a field at dispatch time, or (c) attach a value that callers shouldn't have to pass explicitly.

```typescript
// props<T>(): caller must pass exactly the payload shape.
export const addItem = createAction('[Cart] Add', props<{ id: string; qty: number }>());
addItem({ id: 'a', qty: 2 });

// Function form: ergonomic call signature ≠ stored payload; transform/compute here.
export const addItemAt = createAction(
  '[Cart] Add At',
  (id: string, qty = 1) => ({ id, qty, addedAt: Date.now() }),  // default + computed field
);
addItemAt('a');            // → { type:'[Cart] Add At', id:'a', qty:1, addedAt: 169... }
```

The caution that ties back to purity (Q29/Q39): putting `Date.now()` or `Math.random()` *inside the action creator* is far less harmful than putting it in a reducer, because the value is captured *once* at dispatch and then stored as a plain serializable number — the reducer stays pure and replay still reproduces the same state from the recorded action. So the function form is actually the *correct* place to capture non-deterministic values you want in state, precisely because it freezes them into the serializable action rather than recomputing them during reduction. Still, prefer `props<T>()` by default; reach for the factory only when the call ergonomics genuinely warrant it, because the factory hides a little logic that reviewers must now read.

#### Q66. [Practical] Walk through scaffolding a new feature with the NgRx schematics. What do they generate and why use them?

The `@ngrx/schematics` (run via `ng generate`) produce the boilerplate for actions, reducers, effects, selectors, entities, and feature registration in the conventional file layout. The value is **consistency and speed**: every feature lands in the same shape, with the same naming, so engineers moving between features (and code reviewers) face a uniform structure rather than each developer's personal interpretation of "where do selectors go."

```bash
# One-time: make NgRx schematics the default collection.
ng config cli.schematicCollections '["@ngrx/schematics"]'

# Generate a fully-wired feature (reducer + actions + selectors via createFeature):
ng generate feature Users --module users.module.ts --api

# Generate just the pieces:
ng generate action users/User --api          # success/failure trio for an HTTP flow
ng generate effect users/User --module users.module.ts
ng generate entity users/User --module users.module.ts   # entity adapter + state
```

The `--api` flag is the most useful: it scaffolds the **load / loadSuccess / loadFailure** action trio and the matching effect skeleton with the correct `switchMap` + `catchError`-inside pattern (Q8/Q9), so the most error-prone hand-written part — the effect contract — starts correct. `ng generate feature` with modern NgRx emits a `createFeature` block (Q18) so the feature key, reducer, and selectors stay in sync automatically.

The honest trade-off to mention: schematics encode *one team's idea* of structure, and they generate the *full* classic-Store ceremony, which is exactly the boilerplate you might not want for a feature that should be a SignalStore or ComponentStore. So I use schematics to enforce consistency on genuinely-global, classic-Store features, and I do *not* reflexively scaffold a global feature for state that should be local — the schematic makes the global path frictionless, which can quietly push teams toward over-globalizing (Q14). They are an accelerator for the cases you've already decided belong in the global store, not a substitute for that decision.

### 🟡 Intermediate — extended

#### Q67. [Practical] How do you implement undo/redo on top of NgRx, and what are the two fundamentally different approaches?

Undo/redo is a feature the Redux pattern is unusually well-suited to, because state is already "a value you can snapshot." There are two architecturally distinct implementations, and choosing wrong creates either a memory problem or a correctness problem.

**Approach 1 — snapshot history via a meta-reducer.** Wrap the root (or a feature) reducer in a meta-reducer that maintains `{ past: State[], present: State, future: State[] }`. On every "undoable" action, push the current `present` onto `past` and set the new state as `present`; on `Undo`, pop `past` into `present` and push the old present onto `future`. This is the `redux-undo` model and is dead simple, but it stores *entire state snapshots*, so memory grows with history depth × state size.

```typescript
interface History<T> { past: T[]; present: T; future: T[]; }

export function undoable<T>(reducer: ActionReducer<T>): ActionReducer<History<T>> {
  return (state, action) => {
    state ??= { past: [], present: reducer(undefined, action), future: [] };
    switch (action.type) {
      case '[History] Undo': {
        if (!state.past.length) return state;
        const previous = state.past[state.past.length - 1];
        return { past: state.past.slice(0, -1), present: previous, future: [state.present, ...state.future] };
      }
      case '[History] Redo': {
        if (!state.future.length) return state;
        const next = state.future[0];
        return { past: [...state.past, state.present], present: next, future: state.future.slice(1) };
      }
      default: {
        const present = reducer(state.present, action);
        if (present === state.present) return state;     // no-op actions don't pollute history
        return { past: [...state.past, state.present], present, future: [] };  // new action clears redo
      }
    }
  };
}
```

**Approach 2 — command/inverse-command (the Command pattern).** Instead of snapshots, record each *action plus its inverse* (or enough info to invert it): an `addItem` undo is `removeItem`; an `updateField` records the previous value. Undo applies the inverse command. This uses memory proportional to the *number of operations*, not state size, so it scales to large state — at the cost of having to define an inverse for every undoable action, which is real work and a source of bugs if an inverse is wrong.

The trade-off, and the production answer: **snapshot history for small/medium state and a bounded history depth** (cap `past.length`, drop the oldest) — it's trivially correct and memory is fine when state is small. **Command/inverse for large state or deep history** (a document editor, a canvas) where snapshotting megabytes per keystroke is untenable. A subtlety in both: decide *which* actions are undoable (you rarely want a network-load to be an undo step), filter no-op state changes out of history (the `present === state.present` guard above), and remember that a *new* action after an undo must clear the redo `future` — the classic branch-discard behavior users expect.

#### Q68. [Practical] State must stay in sync across multiple browser tabs of the same app. How do you implement and bound this?

Each tab is a separate JS runtime with its own store instance, so "shared state across tabs" requires an explicit cross-tab transport. The standard mechanisms are the **`BroadcastChannel` API** (modern, purpose-built) or the **`storage` event** on `localStorage` (older, broader support). You wire a small effect/service that *publishes* selected state changes to the channel and *listens* for changes from other tabs, dispatching a local "sync" action that rehydrates the affected slice.

```typescript
@Injectable({ providedIn: 'root' })
export class TabSyncEffects {
  private store = inject(Store);
  private channel = new BroadcastChannel('app-state-sync');

  // Publish whitelisted slice changes to other tabs.
  publish$ = createEffect(() =>
    this.store.select(selectSyncedSlice).pipe(
      // distinctUntilChanged so we only broadcast real changes
      tap((slice) => this.channel.postMessage({ slice, origin: TAB_ID })),
    ), { dispatch: false });

  // Receive from other tabs → dispatch a local hydrate action.
  receive$ = createEffect(() =>
    fromEvent<MessageEvent>(this.channel, 'message').pipe(
      filter((e) => e.data.origin !== TAB_ID),           // ignore our own echoes
      map((e) => SyncActions.hydrateFromTab({ slice: e.data.slice })),
    ));
}
```

The design decisions that make this safe rather than a source of infinite loops and bloat: (a) **echo prevention** — tag each message with the originating tab id and ignore your own (otherwise tab A's broadcast triggers tab B's broadcast which triggers tab A, an echo storm); (b) **whitelist the synced slice** — never broadcast the whole store; sync only what *must* be coherent across tabs (auth/logout, theme, a cart), exactly the "store as scarce resource" discipline applied to the sync channel; (c) **idempotent hydration** — the receiving reducer should set state, not apply deltas, so a missed or out-of-order message self-corrects on the next sync.

The two highest-value real uses are **logout propagation** (log out in one tab → every tab clears state and redirects, a genuine security requirement) and **token refresh** (one tab refreshes the auth token, others pick it up rather than racing to refresh). For high-frequency state, *don't* sync across tabs — the channel becomes a firehose and tabs fight. `BroadcastChannel` is preferred when support allows because it's an explicit pub/sub that doesn't touch storage; the `localStorage` `storage`-event approach is the fallback and has the side benefit of also persisting, but couples sync to persistence. Bound it by syncing the smallest possible slice, debouncing, and treating cross-tab messages as untrusted input (validate shape before hydrating).

#### Q69. [Practical] An effect must retry a failed HTTP call with backoff but give up after N attempts and surface a failure. Write it and explain the operator placement.

The reflex to reach for is `retry`, but a naive `retry(3)` retries *immediately* (hammering a struggling server) and, placed wrong, can resubscribe the *outer* action stream. The correct tool is `retry({ count, delay })` (or the older `retryWhen`) applied to the **inner** observable — the HTTP call — with exponential backoff, and a `catchError` *after* the retries are exhausted to convert the final failure into a failure action.

```typescript
import { retry, timer, throwError, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';

loadReport$ = createEffect(() =>
  this.actions$.pipe(
    ofType(ReportActions.load),
    switchMap(({ id }) =>
      this.api.getReport(id).pipe(
        // Retry only the HTTP call; exponential backoff; cap attempts.
        retry({
          count: 3,
          delay: (error, attempt) => {
            // Don't retry client errors (4xx) — only transient ones (5xx/network).
            if (error.status >= 400 && error.status < 500) return throwError(() => error);
            return timer(Math.min(1000 * 2 ** attempt, 8000));   // 2s, 4s, 8s cap
          },
        }),
        map((report) => ReportActions.loadSuccess({ report })),
        // After retries are exhausted, this runs once with the final error → failure action.
        catchError((error) => of(ReportActions.loadFailure({ error: error.message }))),
      ),
    ),
  ),
);
```

The operator-placement reasoning is the crux. `retry` and `catchError` live **inside** the `switchMap`, operating on the HTTP observable, for two reasons: first, this keeps the *outer* `actions$` stream alive (Q8) — if `catchError` were outside `switchMap`, the first exhausted failure would complete/error the outer stream and the effect would stop listening forever; second, retry semantics should apply per-request, not to the action stream. The `delay` function is where backoff and *retry-worthiness* live: retrying a 404 or 400 is pointless (the request is malformed or the resource is gone), so I rethrow client errors immediately and only back off on 5xx/network errors. Capping the delay (`Math.min(..., 8000)`) prevents an absurd wait on later attempts.

The trade-off to articulate: retries improve resilience against transient failures but **multiply load on a failing backend** and *delay* the user-visible failure (3 backoff attempts can add ~14s before the error shows). So I bound attempts tightly, never retry non-idempotent writes blindly (a retried POST can double-charge — use idempotency keys or `exhaustMap`/dedup instead), and ensure the eventual `loadFailure` drives a real UI affordance (toast + retry button) rather than a silent spinner-forever. Resilience is a budget, not a free win.

#### Q70. [Theory] What is the "feature state contract" between selectors and reducers, and how do feature selectors handle the lazy-loaded-slice-is-undefined problem?

A feature selector created with `createFeatureSelector<FeatureState>('users')` does one thing: it reads `state['users']` off the root object. The implicit *contract* is that something registered a reducer under exactly that string key. When the feature is eagerly loaded, the key exists from app start. But for a **lazy-loaded feature**, the slice does not exist in the root state until the route activates and `provideState('users', reducer)` runs — so any selector that reads `state.users.something` *before* that point gets `state.users === undefined` and throws on the property access (Q18/Q51).

```typescript
const selectUsersState = createFeatureSelector<UsersState>('users');

// FRAGILE: throws if a non-users feature reads this before 'users' is loaded.
export const selectUserList = createSelector(selectUsersState, (s) => s.list);

// DEFENSIVE: guard for the not-yet-loaded window.
export const selectUserListSafe = createSelector(
  selectUsersState,
  (s) => s?.list ?? [],            // undefined slice → sensible default
);
```

The contract has three practical clauses. First, **a feature's own selectors are safe** — by the time a `users`-feature component renders, the `users` reducer is registered, so within-feature selectors needn't guard. The danger is **cross-feature reads**: a selector in feature A that composes feature B's slice can run while B is unloaded. Those must guard (`?.` + default) or be structured so they only run after B is guaranteed present. Second, `createFeature` (Q18) tightens the contract by co-locating the key and reducer and generating the feature selector, eliminating the "selector points at a key no reducer registered" typo class. Third, **selectors must never assume load order** of lazy features, because that order is determined by routing/user navigation, which is non-deterministic.

The deeper point for an interview: this is the seam where NgRx's dynamic, runtime-composed state tree (Q40) meets the static assumptions of memoized selectors. The store is *assembled* as features load, so a selector is a *query against a tree whose shape changes over time*. The mitigation is defensive defaults at feature boundaries and `createFeature` to keep keys honest — and architecturally, not having feature A depend on feature B's slice at all if you can avoid it, because that cross-feature coupling is exactly what makes lazy loading fragile.

#### Q71. [Practical] You're seeing a slow memory leak in a long-lived Angular app using NgRx. How do you confirm it's subscription-related and fix it?

Memory leaks in NgRx apps are overwhelmingly **un-disposed subscriptions** to store selectors (or effects/streams) in components that get created and destroyed repeatedly (a list of rows, a modal opened many times). Each `store.select(...).subscribe()` that isn't torn down keeps the subscriber — and everything its closure references, often the whole component — alive past `ngOnDestroy`. I confirm and fix this methodically rather than guessing.

```
1. Reproduce: repeatedly open/close the suspect component (or navigate in/out of a route).
2. Chrome DevTools → Memory → take heap snapshot, repeat the action N times, snapshot again.
3. Compare snapshots: detached DOM nodes and a growing count of the component class
   instance = leaked components held alive (classic subscription retention).
4. In the retainer tree, the path usually runs through a Subscription / Subscriber
   back to the store's source subject → confirms a never-unsubscribed select().
```

The fixes, in order of preference: (a) **use the `async` pipe** — it subscribes and, crucially, *unsubscribes automatically* when the component is destroyed, so the leak class cannot occur; this alone eliminates most leaks and is why "select to an observable field + async pipe" beats "subscribe in ngOnInit + assign to a field." (b) **`takeUntilDestroyed()`** (Angular 16+) for the cases where you genuinely must subscribe imperatively — it ties the subscription to the injection context / a `DestroyRef` and tears down on destroy. (c) The older `takeUntil(this.destroy$)` pattern with a `Subject` completed in `ngOnDestroy`, for pre-16 code.

```typescript
// LEAK: manual subscribe, never torn down → component retained forever.
ngOnInit() { this.store.select(selectUser).subscribe((u) => (this.user = u)); }

// FIX A (preferred): no manual subscription at all.
readonly user$ = this.store.select(selectUser);     // template: {{ (user$ | async)?.name }}

// FIX B: must subscribe imperatively → tie to lifecycle.
private destroyRef = inject(DestroyRef);
ngOnInit() {
  this.store.select(selectUser).pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe((u) => (this.user = u));
}
```

Two NgRx-specific notes. **Effects rarely leak** if registered via `provideEffects` because NgRx manages their subscription for the providing injector's lifetime — but a `createEffect(..., { dispatch: false })` that you *manually* subscribe to instead of returning will leak. And **ComponentStore/SignalStore auto-clean** their internal subscriptions on destroy, which is a real argument for them in component-scoped code. The strategic prevention is a lint rule banning `.subscribe(` in components without `takeUntilDestroyed`/`async`, plus code-review vigilance — leaks are cheap to prevent and expensive to hunt, and a heap snapshot is the only way to *prove* you fixed it rather than hoping.

#### Q72. [Theory] Explain "shallow" vs "deep" state shape and the immutability cost of deeply nested state. Why is a flat/normalized shape easier to update correctly?

Reducers must return new objects without mutating the old ones (Q41), and the cost of that obligation scales with **nesting depth**, because immutable update requires spreading a *new object at every level along the path to the change*. To update one field three levels deep, you must clone three objects — and getting any level wrong (forgetting a spread) either mutates shared state or drops sibling data.

```typescript
// DEEP shape: updating one comment's `liked` flag is error-prone and verbose.
interface State { posts: { [id: string]: { comments: { [id: string]: Comment } } }; }
on(likeComment, (state, { postId, commentId }) => ({
  ...state,
  posts: {
    ...state.posts,
    [postId]: {
      ...state.posts[postId],
      comments: {
        ...state.posts[postId].comments,
        [commentId]: { ...state.posts[postId].comments[commentId], liked: true },
      },
    },
  },
}));   // four nested spreads; miss one and you mutate or lose siblings.

// FLAT/NORMALIZED shape: comments are their own keyed collection.
interface State2 { comments: { [id: string]: Comment }; }   // postId is a field ON the comment
on(likeComment, (state, { commentId }) => ({
  ...state,
  comments: { ...state.comments, [commentId]: { ...state.comments[commentId], liked: true } },
}));   // one level; trivial and hard to get wrong.
```

The flat/normalized shape (Q47) collapses the update to a single level, which is why `@ngrx/entity` and `withEntities` exist — they manage a flat keyed map so every update is one shallow merge regardless of how the data relates. Beyond correctness, deep nesting also *hurts memoization*: a change deep in the tree forces new object references all the way up the path, so selectors keyed on intermediate levels invalidate even when their *visible* data didn't change, causing extra recomputation and re-renders.

The design rule that follows: **model store state as a set of flat, normalized collections plus relationships expressed by id**, and reconstruct nested/joined view shapes in memoized selectors (normalize on write, denormalize on read). Reserve deep nesting for state that is genuinely a small fixed tree (a settings object) where the depth is shallow and stable. If you find a reducer with four-deep spreads, that's a signal the state is modeled as the *API's* nested response rather than as *normalized application state* — and the fix is to flatten the shape, not to reach for Immer to make the deep update less painful (though Immer *does* make deep updates safe if you genuinely can't flatten, by letting you write `draft.posts[id].comments[cid].liked = true` and producing the immutable result for you).

### 🟠 Advanced — extended

#### Q73. [Practical] Design the testing strategy for a non-trivial effect with retry, debounce, and cancellation. Show a marble test and explain when marbles beat subscription tests.

For a *simple* effect (one action in, one action out) a subscription-based test (`actions$ = of(action); effect$.subscribe(assert)`) is fine and readable (Q16). But the moment an effect involves **time** — `debounceTime`, `retry` with backoff, `switchMap` cancellation, `throttleTime` — subscription tests become flaky or impossible because they can't control the virtual clock. **Marble tests** with RxJS `TestScheduler` are the correct tool: they let you place emissions on a virtual timeline, run the effect, and assert both the *values* and their *exact timing*, deterministically and instantly (no real waiting).

```typescript
import { TestScheduler } from 'rxjs/testing';
import { provideMockActions } from '@ngrx/effects/testing';

it('debounces search and cancels stale requests', () => {
  const scheduler = new TestScheduler((actual, expected) => expect(actual).toEqual(expected));
  scheduler.run(({ hot, cold, expectObservable }) => {
    // two searches 20ms apart; debounce(30) should drop the first.
    const actions$ = hot('-a 19ms b', {
      a: SearchActions.query({ term: 'ng' }),
      b: SearchActions.query({ term: 'ngrx' }),
    });
    const apiResponse = cold('5ms (r|)', { r: ['result'] });
    api.search.and.returnValue(apiResponse);

    const effect = createSearchEffect(actions$, api);     // factory wiring actions$ + mock
    // Only the SECOND term survives the debounce; success emitted after API latency.
    expectObservable(effect).toBe('51ms s', { s: SearchActions.success({ results: ['result'] }) });
  });
});
```

The marble syntax encodes time: `-` is a frame, `19ms` advances the virtual clock precisely, `(r|)` groups a value with completion. The test proves the *temporal* contract — that `debounceTime` drops the rapid first keystroke, that `switchMap` would cancel an in-flight request when a new term arrives — which no amount of subscription testing can verify reliably.

The strategy I apply: **subscription/`firstValueFrom` tests for time-agnostic effects** (load → success/failure, mapping logic) because they're more readable and most reviewers grok them faster; **marble tests reserved for time/concurrency behavior** — debounce windows, retry backoff timing, cancellation, throttle — where the *timing is the spec*. I also test the **error path** explicitly (API returns an error → assert the failure action *and* that the outer stream is still alive by feeding a second action), because the most damaging effect bug (Q8) is the silently-dead stream, and a happy-path-only test will never catch it. Marbles are powerful but have a real readability cost, so I don't reach for them when a plain subscription test suffices — matching the tool to whether *time* is part of what I'm asserting.

#### Q74. [Theory] How does the NgRx store interact with Angular change detection and `NgZone`, and what changes under zoneless / signal-based change detection?

In a Zone.js app, the chain is: an async event (HTTP response, websocket, timer) fires inside `NgZone`'s monkey-patched async API, which schedules a **change-detection tick** after the callback. When that callback dispatches an NgRx action, the reducer runs, the store emits, the `async` pipe receives the new value and calls `markForCheck()`, and the already-scheduled tick then re-renders the OnPush components whose bindings changed. The critical dependency is that **the dispatch happens inside `NgZone`** — if it happens *outside* (e.g. a third-party websocket library that escaped the zone, or code in `runOutsideAngular`), the store still updates but **no tick is scheduled**, so the view goes stale despite correct state. NgRx's `strictActionWithinNgZone` runtime check exists precisely to catch this in development.

```
[inside NgZone]   ws.onmessage → dispatch → store emits → async pipe markForCheck()
                  → NgZone schedules tick → OnPush CD runs → view updates ✅

[outside NgZone]  ws.onmessage → dispatch → store emits → async pipe markForCheck()
                  → (no tick scheduled because the event was outside the zone) → view STALE ❌
```

Under **zoneless change detection** (`provideZonelessChangeDetection()`, stable in Angular 20) the model changes fundamentally: there is no global monkey-patching and no app-wide tick triggered by every async event. Instead, change detection is driven by **signals** and a small set of explicit notifications. A signal write (including a SignalStore `patchState`) marks dependents dirty and schedules CD for exactly those components; the `async` pipe still calls `markForCheck()` which now *directly* schedules the targeted CD. The "dispatch outside the zone → stale view" failure mode largely disappears for signal-driven reads, because there's no zone to be outside of — what matters is whether a *signal* was written or a `markForCheck` issued.

The practical implications for an NgRx app going zoneless: **SignalStore and `selectSignal()` are first-class** because signal reads integrate natively with zoneless CD, whereas classic `store.select(...)` Observables must flow through the `async` pipe (which issues the `markForCheck` that drives CD) — bare manual `.subscribe()` + field assignment that "worked" under Zone.js (because *something else* triggered a global tick) will now fail to update the view, surfacing latent read-path bugs. The architect's takeaway: zoneless removes the implicit "any async event re-renders everything" safety net, which is great for performance but means **state reads must be expressed as signals or async-piped observables** so the framework knows precisely what to re-render — and it nudges NgRx adopters further toward SignalStore as the path of least friction.

#### Q75. [Practical] Your reducer needs to update state based on the *previous* state of a different feature slice. Why can't a reducer do this, and what are the correct patterns?

A feature reducer is a pure function over **its own slice only** — `combineReducers` calls each slice reducer with `state[thatKey]`, so `cartReducer` literally never receives `state.auth` (Q40). This isolation is deliberate and load-bearing: it's what makes features composable and lazy-loadable. So a reducer *structurally cannot* read another slice; the question is how to express "update cart based on the current user's tier" correctly.

There are three patterns, in order of preference. **Pattern 1 — carry the needed data in the action.** The component or effect that has access to both slices reads them and includes the cross-slice value in the action payload, so each reducer still only touches its own slice from data handed to it.

```typescript
// Effect reads BOTH slices (via concatLatestFrom, Q35) and enriches the action.
applyDiscount$ = createEffect(() =>
  this.actions$.pipe(
    ofType(CartActions.checkout),
    concatLatestFrom(() => this.store.select(selectUserTier)),   // post-reduction read
    map(([, tier]) => CartActions.applyTierDiscount({ tier })),  // cart reducer gets tier handed in
  ),
);
```

**Pattern 2 — derive the cross-slice combination in a selector**, not in the reducer at all. If "cart total *with* the user's discount" is *derived* state rather than *stored* state, it belongs in a memoized selector that composes both slices (`createSelector(selectCartItems, selectUserTier, (items, tier) => ...)`). This is the right answer surprisingly often: the thing you wanted to "store based on another slice" is usually a derivation that shouldn't be stored at all (it can't drift if it's computed).

**Pattern 3 — a meta-reducer or top-level reducer that sees the whole state**, reserved for genuinely cross-cutting cases (logout clearing everything, Q21). A meta-reducer wraps the root reducer and *does* see the full tree, so it's the sanctioned place for logic that must observe multiple slices — but it's a blunt instrument and overusing it reintroduces the coupling slice-isolation was protecting you from.

The principle to articulate: the reducer's inability to read other slices is a *feature*, not a limitation — it forces you to decide whether the cross-slice need is **derived** (→ selector), **event-driven** (→ effect enriches the action), or **truly global** (→ meta-reducer). The wrong instinct is to flatten everything into one giant slice so the reducer "can see it all," which throws away the isolation that makes the store maintainable at scale. Most "I need another slice in my reducer" turns out to be Pattern 2 in disguise.

#### Q76. [Theory] What is `createFeature`, what exactly does it generate, and what are its limits (e.g. nested selectors, extra selectors)?

`createFeature` (NgRx 12.2+, matured since) bundles a feature's **name, reducer, and auto-generated selectors** into a single object, so you define the key once and get a `selectXxxState` feature selector plus one selector *per top-level state property* for free, with the feature key guaranteed consistent between the reducer registration and the selectors.

```typescript
export const usersFeature = createFeature({
  name: 'users',
  reducer: createReducer(
    initialState,
    on(UserActions.loadSuccess, (s, { users }) => ({ ...s, users, loading: false })),
  ),
  // optional: extra selectors composed from the generated ones
  extraSelectors: ({ selectUsers, selectFilter }) => ({
    selectFilteredUsers: createSelector(selectUsers, selectFilter,
      (users, filter) => users.filter((u) => u.name.includes(filter))),
  }),
});

// Generated for free: usersFeature.selectUsersState (the feature selector),
// usersFeature.selectUsers, usersFeature.selectLoading, usersFeature.selectFilter
// (one per top-level key), plus usersFeature.name and usersFeature.reducer.
// Register with: provideState(usersFeature)
```

What it generates precisely: `name`, `reducer`, the feature selector `select{Name}State`, and a `select{Key}` for **each top-level property** of the state interface. `extraSelectors` is the escape hatch for derived selectors that compose those auto-generated ones. The payoff is eliminating the most boilerplate-heavy, typo-prone file (a long list of `createSelector(featureSelector, s => s.x)` one-liners) and the "feature key string drifted between reducer and selector" bug class.

The limits worth naming so you don't oversell it: (a) it **only generates selectors for top-level keys** — nested state needs `extraSelectors` or manual selectors, which is fine and even desirable since you should be flattening state anyway (Q72); (b) **it doesn't help with `@ngrx/entity`'s `getSelectors()`** automatically — you still wire the adapter's selectors, typically inside `extraSelectors`; (c) it's a *classic-Store* construct, so it has no bearing on SignalStore code; and (d) the auto-generated selector *names* are derived mechanically from property names, so renaming a state property renames its selector (a refactor ripple, though TypeScript catches the breakage). Net: `createFeature` is the modern default for classic-Store features — strictly less boilerplate with no real downside — and the only judgment call is putting derived/entity selectors in `extraSelectors` versus a separate selectors file when they grow large.

#### Q77. [Practical] In a large Nx monorepo, how do you enforce architectural boundaries so feature libraries can't reach into each other's NgRx state?

The risk in a monorepo is **state coupling across feature boundaries**: feature library A imports feature B's selectors/actions/state directly, so B can't evolve its store shape without breaking A, and lazy-loading guarantees are violated (Q70). Nx's primary tool is **module boundary lint rules** (`@nx/enforce-module-boundaries`) driven by **project tags** — you tag libraries by *type* and *scope*, then declare which tags may depend on which, and the linter fails the build on a violating import.

```json
// In each project.json / tags config: classify libraries.
// libs/orders/feature → tags: ["scope:orders", "type:feature"]
// libs/orders/state    → tags: ["scope:orders", "type:state"]
// libs/shared/data     → tags: ["scope:shared", "type:state"]

// .eslintrc — enforce-module-boundaries:
{
  "depConstraints": [
    { "sourceTag": "type:feature", "onlyDependOnLibsWithTags": ["type:state", "type:ui", "type:util"] },
    { "sourceTag": "scope:orders", "onlyDependOnLibsWithTags": ["scope:orders", "scope:shared"] },
    { "sourceTag": "type:state",   "onlyDependOnLibsWithTags": ["type:util", "type:data"] }
  ]
}
```

Layered on top of the lint rules, the architectural conventions that actually keep state private: (a) **each feature's NgRx slice lives in its own `state`/`data-access` library and exposes only a facade** (Q11) through the library's public `index.ts` barrel — actions, reducers, and *internal* selectors are simply *not exported*, so other libs physically cannot import them even if they tried; the facade's methods/observables are the only public surface. (b) **`scope:` tags prevent cross-feature dependencies** — `scope:orders` can depend on `scope:shared` but not `scope:billing`, so feature stores can't entangle. (c) **truly-shared state lives in a `scope:shared` data-access lib** that every feature may depend on, which is the monorepo expression of "store as a scarce resource" (Q19) — the shared store is small and explicitly shared, not reached-into ad hoc.

The enforcement is what makes it real: lint rules run in CI and **fail the build** on a boundary violation, so the architecture is mechanically defended rather than relying on reviewer memory. The trade-off is the up-front discipline of tagging every library and resisting the temptation to export "just this one selector" across a boundary (which is how coupling starts). Nx also gives you `nx graph` to *see* the dependency graph, so you can audit whether feature stores are actually isolated. The principle mirrors the micro-frontend answer (Q50) at a smaller scale: state is private behind a facade boundary, cross-boundary needs go through an explicit shared library or an event/contract, and the build enforces it.

#### Q78. [Theory] Explain `withHooks` in SignalStore and the lifecycle problem it solves. How does it compare to ngOnInit/ngOnDestroy and effect lifecycle hooks?

`withHooks` adds **lifecycle behavior to a SignalStore itself**, independent of any component — `onInit` runs when the store is instantiated (when its provider is first injected), and `onDestroy` runs when the store's injector is torn down. This matters because a SignalStore can be `providedIn: 'root'` (lives for the app) or provided at the component/route level (lives with that scope), and you often need *the store* to kick off work or clean up on its own schedule, not piggy-backed on some component's `ngOnInit`.

```typescript
export const UsersStore = signalStore(
  withState({ users: [] as User[], loading: false }),
  withMethods((store, api = inject(UserApi)) => ({
    load: rxMethod<void>(pipe(switchMap(() => api.getAll().pipe(
      tapResponse({ next: (users) => patchState(store, { users }), error: () => {} })))))
  })),
  withHooks({
    onInit(store) {
      store.load();                          // auto-load when the store comes alive
    },
    onDestroy(store) {
      // release resources, close a channel, etc. (rxMethod subscriptions auto-clean)
    },
  }),
);
```

The lifecycle problem it solves: before `withHooks`, "load data when this state container is created" had to live in a *component's* `ngOnInit`, coupling the store's initialization to a particular consumer — and if two components used the store, you'd either double-trigger or have to guard. With `withHooks`, the store owns its own bootstrap: `onInit` fires **once** when the store is created regardless of how many components inject it, which is exactly the right semantics for "load the data this store is responsible for." It's the SignalStore analog of the classic Store's `OnInitEffects`/`ROOT_EFFECTS_INIT` (Q36) — a way for a state container to dispatch its own startup work — but expressed directly on the store rather than on a separate effects class.

Comparisons to draw: versus **component `ngOnInit`**, `withHooks.onInit` is *store-scoped*, not *component-scoped*, so it doesn't fire per-consumer and isn't lost when you refactor which component "owns" the load. Versus **`OnInitEffects`**, it's the same idea in the Signal world but you call methods/`patchState` directly instead of dispatching an action. Versus **ComponentStore** (which uses constructor + `OnDestroy`), `withHooks` is the declarative, composable equivalent that participates in `signalStoreFeature` composition (Q59) — a reusable feature can bring its *own* `onInit`/`onDestroy` behavior, and the hooks compose. The caution: `onInit` has access to the injection context, so you *can* `inject()` inside it, but keep it lean — heavy synchronous work in `onInit` delays whatever first injected the store; prefer kicking off async loads (which return immediately) over blocking computation.

### 🔴 Expert — extended

#### Q79. [Practical] A production incident: after a deploy, some users see a blank screen and a console error `Cannot read properties of undefined`. The store hydrates from localStorage. Diagnose and design the fix.

This is a **state schema migration** failure, one of the most common production incidents for any app that persists store state (Q21). The chain: a previous version of the app persisted state shape `v1` to a user's `localStorage`; the new deploy expects shape `v2` (a renamed field, a new required nested object, an entity restructure); on startup the hydration meta-reducer blindly loads the stale `v1` blob as the initial state; a selector or reducer reaches for a `v2` field that doesn't exist in the `v1` data → `undefined` → throw → blank screen. The tell is that it only hits **returning users** (those with persisted state) and *not* new users or incognito sessions — a classic "works on my machine / works for QA's fresh profile" incident.

```typescript
const STORAGE_KEY = 'app_state';
const CURRENT_VERSION = 3;

interface PersistedEnvelope { version: number; state: unknown; }

export function versionedHydration(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action) => {
    if (action.type === INIT && !state) {
      try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) {
          const env = JSON.parse(raw) as PersistedEnvelope;
          state = migrate(env.version, env.state);   // run migrations v(n) → v(n+1) → ...
        }
      } catch {
        localStorage.removeItem(STORAGE_KEY);          // corrupt/unmigratable → start clean
      }
    }
    const next = reducer(state, action);
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: CURRENT_VERSION, state: next }));
    return next;
  };
}

function migrate(from: number, state: any): any {
  if (from < 2) state = { ...state, preferences: { theme: 'light', ...state.preferences } };  // v1→v2
  if (from < 3) state = { ...state, users: normalizeUsers(state.users) };                      // v2→v3
  return state;
}
```

The immediate incident response is **stop the bleeding**: ship a hotfix that wraps hydration in try/catch and, on *any* parse/shape error, **discards the persisted state and starts clean** (the user loses persisted preferences but the app loads). That converts a hard crash into a soft, recoverable degradation. The error must also be *swallowed at the read path* — selectors that read persisted slices should use defensive defaults (`?? initial`) so a partial mismatch can't blank the screen.

The durable fix is a **versioned persistence envelope with explicit migrations**: store `{ version, state }`, and on hydration run an ordered chain of pure migration functions from the persisted version up to the current one (the same model as a database migration runner). This makes schema evolution a first-class, tested concern rather than a latent landmine. The process lessons I'd push: (1) **never persist without a version stamp** — the lack of one is the root cause; (2) **test the upgrade path**, not just fresh installs — seed `localStorage` with the *previous* release's blob in an integration test and assert the new build boots; (3) **whitelist a minimal persisted slice** (Q21) so there's less surface to migrate and less to break; and (4) treat persisted client state as **untrusted input** — it can be stale, corrupt, or hand-edited, so the hydration boundary must validate and degrade gracefully, exactly like an API response.

#### Q80. [Theory] Critically evaluate the claim "NgRx adds no runtime overhead because reducers are just functions." Quantify where the real costs live.

The claim is a half-truth that papers over four distinct, measurable cost centers. Reducers *are* cheap pure functions, but the *system* around them does real per-dispatch work, and at scale that work is the difference between a smooth app and a janky one. Quantifying honestly:

**1 — Per-dispatch reducer fan-out.** `combineReducers` invokes **every** slice reducer on **every** action (Q40). With N feature slices and a dispatch rate of D actions/sec, that's N×D reducer calls/sec. Each is cheap (a map-lookup + same-reference return for unhandled actions), so this is usually negligible — *unless* a reducer does real work for actions it shouldn't, or N is large and D is high (the websocket-firehose case, Q61). The cost is "small × frequency," which is fine until frequency spikes.

**2 — Selector graph evaluation.** On every state emission, every *subscribed* selector runs its input selectors and does reference comparisons across the graph (Q25). Memoization saves the *projector* recomputation but not the *comparison* traversal. A wide selector graph with hundreds of active subscriptions does hundreds of comparison passes per dispatch — cheap individually, but it's O(active selectors) per action, and it's the cost people forget when they say "memoization makes it free."

**3 — Change detection.** Each emission that survives `distinctUntilChanged` triggers `markForCheck` and participates in a CD tick. The cost here is *Angular's*, not NgRx's, but NgRx's dispatch frequency *drives* it — over-globalizing UI state (Q14) inflates dispatch count and thus CD frequency, which is the dominant real-world cost in over-globalized apps.

**4 — Dev-mode runtime checks.** `strictStateImmutability` deep-freezes the state tree every dispatch — O(state size) per action (Q41). This is *real* overhead but **dev-only**, so it doesn't ship; the trap is accidentally leaving it on in production config.

```
Cost per dispatch ≈  Σ(slice reducers: ~O(1) each, N total)        ← usually negligible
                   + Σ(active selectors: input-run + ref-compare)   ← O(active selectors)
                   + (if emitted) markForCheck + CD tick            ← Angular's cost, freq-driven
                   + (dev only) Object.freeze(stateTree)            ← O(state size), not in prod
```

The honest synthesis: NgRx's overhead is **low per dispatch but multiplied by dispatch frequency and selector breadth**, so the lever that actually matters is *not dispatching for things that don't warrant it*. An app that dispatches discrete domain events at human cadence pays an overhead so small it's unmeasurable; an app that routes mousemove/scroll/keystroke through the store and has a sprawling selector graph pays meaningfully and feels it. So the claim should be restated: NgRx adds *negligible overhead when used as intended* (discrete, shared, audited state) and *measurable overhead when abused* (high-frequency UI state in the global store) — which is why the architectural discipline (global vs local, batching the firehose) is also the performance discipline. The bundle-size cost (`@ngrx/store` + `effects` + `entity` is a real but modest KB add) is a separate, one-time consideration distinct from the per-dispatch runtime cost analyzed here.

#### Q81. [Theory] Compare optimistic-update strategies under concurrency: last-write-wins, version/ETag conflict detection, and CRDT-style merge. Where does each fit in an NgRx app?

Optimistic updates (Q17) get genuinely hard when **multiple actors mutate the same record concurrently** — two of the user's own tabs, the user plus a background job, or multiple users. The optimistic local write can collide with a server state that changed underneath it, and how you resolve that collision is an architectural choice with three tiers.

**Last-write-wins (LWW).** The simplest: the optimistic update applies locally, the write goes to the server, and whoever wrote last clobbers the other. In NgRx this is the basic optimistic effect — apply on dispatch, reconcile on success, roll back on failure. It's correct *only* when conflicts are rare and lost updates are acceptable (toggling your own UI preference). Its failure mode is **silent data loss**: actor A's edit vanishes when actor B's later write overwrites it, and nobody is told.

**Version/ETag conflict detection (optimistic concurrency control).** Each record carries a version (or ETag); the write includes the version the client *based its edit on*; the server rejects with `409 Conflict` if the server's version moved on. This is the right default for collaborative or multi-actor data. In NgRx, the action carries the base version, the effect sends it, and a `409` becomes a distinct `conflict` action — *not* a generic failure — that the reducer/UI handles by refetching the current record and either auto-rebasing the edit or prompting the user.

```typescript
saveDoc$ = createEffect(() =>
  this.actions$.pipe(
    ofType(DocActions.save),
    concatMap(({ doc }) =>                                   // ordered: don't race own writes
      this.api.save(doc.id, doc, { ifMatch: doc.version }).pipe(   // send base version (ETag)
        map((saved) => DocActions.saveSuccess({ doc: saved })),    // server returns NEW version
        catchError((err) =>
          err.status === 409
            ? of(DocActions.saveConflict({ id: doc.id }))          // distinct conflict path
            : of(DocActions.saveFailure({ id: doc.id, previous: doc })),  // rollback path
        ),
      ),
    ),
  ),
);
```

**CRDT / operational-transform merge.** For real-time collaborative editing (multiple cursors in one document) you stop thinking in "records with versions" and model state as a **conflict-free replicated data type** (or use OT), where concurrent edits *merge deterministically* without a central conflict. This lives mostly *outside* the NgRx reducer — a CRDT library (Yjs/Automerge) owns the merge, and NgRx holds the *materialized* current value the CRDT produces, plus UI/presence state. Putting CRDT merge logic *in* reducers is a category error; the store mirrors the CRDT's output.

The fit decision: **LWW** for single-user, low-contention, loss-tolerant state (preferences, your own toggles). **Version/ETag** for any data where a lost update is a real bug — the workhorse for business apps, and the one most teams under-implement (they ship LWW and discover silent data loss in production). **CRDT/OT** only for genuine real-time co-editing, where it's essential and the store steps back to being a projection. The recurring NgRx-specific lesson: model the **conflict as a first-class action** (`saveConflict`), not a generic `saveFailure`, because the *response* to a conflict (refetch + rebase or prompt) is fundamentally different from the response to a network error (retry) — collapsing them into one failure path is how conflict handling silently degrades to LWW.

#### Q82. [Practical] You must instrument NgRx for production observability — surface state-transition errors, slow effects, and anomalous action volume to your monitoring stack. Design it.

Production observability for a store means answering three questions from telemetry alone: *did a reducer/effect throw?*, *which effects are slow?*, and *is action volume anomalous (a feedback loop, a stuck retry)?* The clean implementation is a combination of a **logging/metrics meta-reducer** for the action stream and a **dedicated monitoring effect**, both feeding your APM (Datadog/Sentry/etc.), with strict PII discipline.

```typescript
// 1) Meta-reducer: catch reducer throws + sample action volume, with PII scrubbing.
export function observabilityMetaReducer(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action) => {
    const start = performance.now();
    try {
      const next = reducer(state, action);
      const dur = performance.now() - start;
      metrics.histogram('ngrx.reduce.ms', dur, { actionType: action.type });
      counter.increment('ngrx.action', { type: action.type });          // for volume anomaly alerts
      return next;
    } catch (err) {
      // A throwing reducer would otherwise crash silently into a broken store.
      monitoring.captureException(err, { actionType: action.type, scrubbed: scrub(action) });
      return state;                                                       // fail safe: keep last good state
    }
  };
}

// 2) Effect: measure effect latency + report effect errors (don't let monitoring kill the effect).
slowEffectMonitor$ = createEffect(() =>
  this.actions$.pipe(
    ofType(OrderActions.submit),
    concatMap((a) => {
      const t0 = performance.now();
      return this.api.submit(a.order).pipe(
        map((r) => { metrics.histogram('effect.submit.ms', performance.now() - t0); return OrderActions.submitSuccess({ r }); }),
        catchError((e) => { monitoring.captureException(e, { effect: 'submit' }); return of(OrderActions.submitFailure({ e })); }),
      );
    }),
  ),
);
```

The design decisions that make this production-grade rather than a liability: **(a) PII scrubbing is mandatory** — action payloads routinely contain emails, tokens, and personal data (Q20), so the logging path runs every action through a `scrub()` allowlist/denylist before it leaves the browser; shipping raw actions to a third-party APM is a compliance breach waiting to happen. **(b) Sampling and aggregation, not per-action shipping** — emitting a network call per action would itself be a firehose; you increment in-process counters/histograms and flush aggregates periodically, and you *sample* (e.g. log full action detail for 1% or only for error cases). **(c) The meta-reducer must be fail-safe** — wrapping the reducer in try/catch so a throwing reducer reports the exception *and returns the last good state* rather than corrupting the store; an unhandled reducer throw otherwise leaves the store in an undefined condition. **(d) Anomaly detection on volume** — the per-type action counter feeds an alert: a sudden spike in one action type is the signature of an effect feedback loop (Q26) or a stuck retry storm (Q69), and catching it from metrics beats waiting for a user report.

The observability targets to wire to alerts: **reducer/effect exception rate** (any nonzero is investigate-worthy), **effect p95 latency** per critical flow (a slow checkout effect is a revenue problem), and **action-volume-per-minute by type** (anomaly = loop/leak). The architectural note: keep the *audit* concern (DevTools, Q64) separate from the *observability* concern (production APM) — DevTools is for developer debugging and shouldn't run full in prod, while this lightweight, scrubbed, sampled meta-reducer/effect telemetry is what actually runs in production. The whole thing is itself state-adjacent code, so it must obey the same purity/PII rules it's monitoring.

#### Q83. [Theory] Argue both sides: "A facade per feature is essential architecture" vs "facades are unnecessary indirection." Then give your nuanced position.

This is a genuine, unsettled debate in the Angular/NgRx community, and a senior answer steelmans both sides before landing.

**The case FOR facades (essential architecture).** A facade (Q11) gives components a small, intent-revealing, stable API (`usersFacade.load()`, `usersFacade.users$`) and hides *which* state technology backs it. The benefits compound at scale: components never import actions/selectors, so the NgRx surface area each feature developer must learn shrinks; the implementation is swappable (NgRx → SignalStore → plain service) **without touching a single component** (Q22), which is the entire migration story; testing components becomes mocking one service instead of stubbing a store; and in a monorepo the facade is the natural **public boundary** of a state library (Q77), letting you keep actions/selectors un-exported and private. For large teams, the facade is the seam that decouples "how state works" from "how features consume it."

**The case AGAINST facades (unnecessary indirection).** Critics (including some on the NgRx team historically) argue the facade *re-hides* the very thing NgRx made explicit. The Redux pattern's value is the *visible, traceable* action dispatch; a facade collapses `store.dispatch(loadUsers())` into `facade.load()`, obscuring *which action fires* and tempting a single facade method to dispatch several actions or grow into a god-service that re-introduces the imperative, coupled indirection Redux removed. It's also *more code* (every feature now has a facade layer to maintain) and it lowers the friction of adding NgRx everywhere ("the facade hides the boilerplate"), nudging teams toward over-globalizing (Q14). For a small app, the facade is pure ceremony over a store that's already a fine API.

**My nuanced position.** The facade is a *boundary tool*, and its value scales with the size of the boundary it guards. I'd adopt facades where there's a real boundary to protect: **library/monorepo public surfaces** (the facade *is* the library's API and enables privacy + swappability), **large teams** (consistency and a shallow learning curve outweigh the extra layer), and **planned migrations** (the facade is what lets you swap Store↔SignalStore invisibly). I'd *skip* facades where there's no boundary worth the cost: **small apps**, **a single team that knows NgRx**, and especially **anywhere the facade would just be a 1:1 passthrough** to dispatch/select with no added value. The anti-pattern to actively prevent regardless is the **god-facade**: one facade per *feature* with cohesive methods is fine; one facade that fronts the *entire app* and accumulates every concern recreates the problem. So: facades earn their place at architectural boundaries and migrations, are optional inside a cohesive team's feature, and become harmful when they grow past a single feature's scope — consistent with the guide's recurring theme that NgRx machinery (stores, facades, the lot) is a *scarce resource* deployed where the payoff is real, not a default applied uniformly.

#### Q84. [Practical] Lay out a concrete, staged plan to introduce NgRx into a 200k-line Angular app that currently uses ad-hoc services with `BehaviorSubject`s. What are the sequencing risks?

A big-bang rewrite of state management in a 200k-line app is how you spend six months and ship regressions; the only sane approach is **incremental, value-first adoption** that proves the pattern on real pain before spreading it. The staging:

```
Stage 0 — Decide IF (not just how). Audit the BehaviorSubject services: which actually
          hurt? (no audit trail for a hard-to-debug flow, tangled async, shared state
          mutated from many places). If nothing hurts, the right amount of NgRx is zero.

Stage 1 — Pick ONE high-pain, genuinely-shared feature (auth/session or a complex
          multi-step async flow). Build it in NgRx behind a FACADE matching the existing
          service's public API, so consumers are untouched.

Stage 2 — Add DevTools + runtime checks (dev) on just that slice. Demonstrate the win:
          time-travel on the painful flow, visible action audit. Get team buy-in with
          evidence, not assertion.

Stage 3 — Establish conventions: schematics, action hygiene (Q38), the global-vs-local
          decision rule (Q14), lint rules. Write the ADR so adoption is consistent.

Stage 4 — Migrate OUTWARD only to features that meet the bar (shared, long-lived,
          debug/replay value). Leave simple BehaviorSubject services ALONE — coexistence
          is the goal, not uniformity.

Stage 5 — For server-heavy features, evaluate a query library / SignalStore-with-resource
          INSTEAD of classic Store (Q49), so you don't pour CRUD boilerplate into NgRx.
```

The sequencing risks to call out explicitly. **Risk 1 — adopting everywhere because you can.** The biggest failure mode is treating Stage 4 as "migrate everything," recreating the over-globalized mess (Q19/Q26) in a new technology. The facade in Stage 1 *lowers* the friction of adding NgRx, which cuts both ways — guard it with the decision rule. **Risk 2 — two state paradigms confusing the team.** During migration you have BehaviorSubject services *and* NgRx coexisting; without a clear "which do I use for new code" rule, engineers cargo-cult inconsistently. The facade boundary and a documented decision rule contain this. **Risk 3 — choosing classic Store for server state.** A 200k app has lots of CRUD; if you reflexively model it all as actions/effects/reducers you'll write thousands of lines of loading/error/data plumbing — Stage 5 exists to steer server data to a cache library or SignalStore-resource instead. **Risk 4 — no rollback story.** Because every migrated feature sits behind a facade matching the old API, you can revert a feature's facade implementation to the old service if NgRx underperforms for it — keep that escape hatch and don't delete the old service until the new path is proven in production.

The meta-point I'd put in the ADR: the goal is **not "the app uses NgRx," it's "each feature uses the right state tool"** — NgRx for the shared/audited/complex-async features where it earns its cost, BehaviorSubject/Signal services for the simple shared state that doesn't, query libraries for server data, ComponentStore/SignalStore for local. Measure success by *reduced state-related defects and faster debugging on the painful flows*, not by NgRx adoption percentage. An adoption that *shrinks* over-engineered state and *adds* structure only where it pays is a success; one measured by "how much is now in the store" is the failure restated.

#### Q85. [Theory] Reducers must be pure, yet real apps need IDs, timestamps, and random values in state. Enumerate every correct place to inject non-determinism and the trade-offs of each.

This is a foundational purity question, and the precise answer is: a reducer must be `(state, action) => state` with *no* observation of the outside world — no `Date.now()`, `Math.random()`, `crypto.randomUUID()`, `localStorage`, no I/O. Yet state legitimately needs a created-at timestamp, a generated client id, a correlation id. The resolution is that non-determinism must be **captured before the reducer and carried in the action**, so the reducer remains a pure function of its inputs and replay reproduces identical state (Q39). There are exactly three correct injection points, in order of preference:

**1 — In the action creator's factory function (Q65).** `createAction('[X] Add', () => ({ id: crypto.randomUUID(), at: Date.now() }))` captures the value *once* at dispatch and stores it as a plain serializable primitive in the recorded action. This is usually the best place for "values that should be frozen at the moment of the event" because the value lives in the audit log and replay reuses the *recorded* value, not a fresh one.

```typescript
// ✅ Non-determinism captured at dispatch, frozen into the serialized action.
export const addNote = createAction('[Notes] Add',
  (text: string) => ({ id: crypto.randomUUID(), text, createdAt: Date.now() }));
// Reducer is pure: it only reads what the action carries.
on(addNote, (s, { id, text, createdAt }) => ({ ...s, entities: { ...s.entities, [id]: { id, text, createdAt } } }));
```

**2 — In an effect.** When the value comes from an async/impure source (a server-assigned id, a value from a service, the current authenticated user), the effect reads it and dispatches a *success* action carrying the resolved value. This is mandatory for anything that *can't* be known synchronously at dispatch (server ids) and is the right place for impure dependencies the action creator shouldn't reach into.

**3 — In the component / dispatcher, passed as an action arg.** The component computes or obtains the value and includes it in the action payload. Functionally similar to (1) but the logic lives at the call site; prefer (1) when the value is intrinsic to the action's meaning so every dispatcher gets it consistently, and prefer (3) when the value is contextual to that specific call site.

The trade-offs: **action-creator capture (1)** keeps the value in the audit trail and makes replay deterministic, but it means the creator does a tiny bit of work and you must remember it captures *once* (re-dispatching the same creator generates a *new* id, which is correct). **Effect (2)** is the only option for truly async/server-sourced values and keeps even the impure *acquisition* out of reducers, at the cost of an extra action round-trip. **Component (3)** is flexible but risks inconsistency if multiple call sites must produce the value the same way. The anti-pattern that violates all three is **`Date.now()` inside the reducer**, which seems harmless until DevTools replay or SSR (Q52) produces a *different* state than the live run (the replayed reducer computes a new timestamp), turning time-travel into a liar and breaking SSR hydration. The unifying rule: the reducer is a *deterministic projector of recorded events*; any window onto the nondeterministic world must be opened *before* the action exists and its result *frozen into* the action — which is precisely what makes the entire replay/audit/SSR value proposition hold.

#### Q86. [Practical] Profile and fix a concrete jank: scrolling a 5,000-row NgRx-backed grid drops to 12fps, but the data isn't even changing during the scroll. Find the culprit chain.

"Data isn't changing but it janks during scroll" immediately points away from state churn and toward **the read/render path doing work it shouldn't on every change-detection tick** — and scrolling, especially under Zone.js, fires a *lot* of CD ticks (scroll is a zone-patched event). The profiling sequence isolates which of three suspects is firing:

```
1. Angular DevTools Profiler → record a scroll → which components re-render per tick,
   and how long does CD take? A 5,000-row grid re-checking every row = the smoking gun.
2. Are selectors RECOMPUTING during scroll? Add a counter/log in the projector. If a
   selector reruns on scroll, an upstream input reference is changing (or it's an
   inline non-memoized projector, Q34).
3. Is the template binding to a selector that returns a NEW array each CD? e.g.
   `*ngFor="let r of (rows$ | async)"` where the async pipe re-subscribes, or a getter
   that filters/maps inline in the template.
```

The culprit chain is almost always one (or a stack) of these, and each has a precise fix:

- **No virtualization** — rendering and change-detecting all 5,000 rows when ~30 are visible. Fix: **CDK virtual scroll** (`cdk-virtual-scroll-viewport`) so only visible rows exist in the DOM and participate in CD. This is usually the dominant win for a 5k grid.
- **Missing `trackBy` / `track`** — without it, any list re-emission makes Angular tear down and rebuild all rows. Fix: `trackBy: (i, row) => row.id` so identity is stable and unchanged rows are skipped.
- **Inline work in the template** — `*ngFor="let r of rows | filterPipe"` with an *impure* pipe, or a method call `rows()` that returns a new array each CD, re-runs on every tick (and scroll = many ticks). Fix: move derivation into a **memoized selector** (Q34) so the array reference is stable; never filter/sort/map inline in the template.
- **Not OnPush** — a default-CD component re-checks on every global tick; under scroll that's brutal. Fix: `ChangeDetectionStrategy.OnPush` so the grid only re-checks when its inputs (the stable selector reference) actually change — which during a pure scroll is *never*, so CD should do almost nothing.
- **Selector returning a fresh reference** — if `selectVisibleRows` does `.filter()`/`.map()` and an unrelated state change upstream produces a new input reference, the projector reruns and yields a new array, invalidating the whole grid even though visible data is identical (Q25). Fix: stabilize upstream references and/or `resultMemoize` with structural equality (Q53).

The diagnostic discipline is what matters: the symptom "janks on scroll without data change" *rules out* reducer/dispatch cost and *rules in* the render path, so I don't waste time optimizing the store. The fix stack — **virtualize, `track`, OnPush, memoized selectors, stable references** — is ordered by typical impact (virtualization first for large lists). The recurring lesson from Q51/Q61: a store-backed view that janks is usually a *change-detection and reference-stability* problem at the read edge, not a state problem — the store can be perfectly efficient while the template throws the savings away by manufacturing new references or re-checking thousands of rows per tick.

#### Q87. [Theory] Reconcile two NgRx maxims that seem to conflict: "actions are events, dispatch many fine-grained ones" and "high action volume causes performance and DevTools problems." Where is the real boundary?

These maxims only *appear* to conflict because they're answers to different questions — *what an action should represent* (semantics) versus *how often you should dispatch* (frequency) — and conflating them produces bad architecture in both directions. The reconciliation is that the "events, not commands" principle (Q54) is about **granularity of meaning**, while the "high volume is a problem" warning (Q26) is about **frequency of occurrence**, and these are orthogonal axes.

```
                    LOW frequency                 HIGH frequency
                ┌──────────────────────────┬──────────────────────────────┐
 FINE-grained   │ ✅ ideal: rich, readable  │ ⚠️ the trap: per-keystroke /  │
 (event-style)  │ audit trail of discrete   │ per-tick events flood DevTools │
                │ user intents              │ and CD (Q26/Q61) — BATCH them  │
                ├──────────────────────────┼──────────────────────────────┤
 COARSE/reused  │ ⚠️ anti-pattern: generic  │ ⚠️ worst: high-freq AND        │
 (command-style)│ reused actions destroy    │ semantically opaque           │
                │ the audit trail (Q38)     │                               │
                └──────────────────────────┴──────────────────────────────┘
```

The real boundary is this: **dispatch a fine-grained event for every *discrete, meaningful thing that happened* — but a continuous stream of physical events (mousemove, scroll, every keystroke, every websocket tick) is not a series of meaningful discrete events; it's a *signal*, and signals should be *conditioned before they become actions*.** A user typing "ngrx" is not five meaningful events worth five entries in the audit trail; the *meaningful* event is "user searched for ngrx," which is the debounced result. So you condition the high-frequency source at the edge — `debounceTime` on input, `bufferTime`/`auditTime` on a tick stream (Q61) — and dispatch the *one meaningful action* the stream resolves to.

The synthesis a staff engineer should articulate: the two maxims agree once you separate "event semantics" from "raw input frequency." Fine-grained *events* are good and impose negligible cost at human cadence (a user generates maybe a few actions per second of genuine intent). The volume problem comes from **mistaking raw high-frequency input for events** and piping each tick/keystroke/scroll straight into `dispatch` — that's not "many fine-grained events," it's "failing to condition a signal." So the rule is: **be fine-grained about *meaning*, be coarse about *raw frequency*** — debounce/buffer/throttle the firehose down to its meaningful discrete events at the boundary, then dispatch those as rich, hygienic, event-style actions. The audit trail stays a readable narrative of intent (the maxim's goal) precisely *because* you didn't pollute it with sub-perceptual physical noise (the warning's concern). They were never in tension; they jointly define where to put the conditioning layer.

#### Q88. [Practical] A selector that joins three large entity collections (orders × customers × products) recomputes on every unrelated dispatch and tanks a dashboard. Diagnose and give the layered fix.

A heavyweight join selector recomputing on *unrelated* dispatches is a textbook memoization-failure-plus-cost-of-recompute problem (Q25), and on large collections the projector itself (the join) is genuinely expensive, so the fix is two-pronged: **stop recomputing when nothing relevant changed**, and **make the necessary recompute cheaper**. First, diagnose *why* it recomputes when it shouldn't.

```
1. Instrument: log inside the projector. Recomputes on dispatches that touch NONE of
   orders/customers/products? → an input selector's reference is changing spuriously.
2. Common causes:
   a. An input selector returns a NEW reference each time (e.g. selects `selectAll`
      from entity but a reducer rebuilds the array, or an upstream maps/filters).
   b. The join selector takes a PARAMETER and isn't a factory → single-slot thrash (Q7).
   c. An input is the WHOLE feature state object, which changes on any feature action.
```

The layered fix, cheapest to most structural:

- **Narrow the inputs.** The join should depend on the *entity maps* it actually reads (`selectOrderEntities`, `selectCustomerEntities`, `selectProductEntities`), not on whole feature-state objects. If an input is `selectOrdersState` (the whole slice), it changes whenever *any* orders action fires (including `loading` flag flips), needlessly invalidating the join. Depending on the narrowest stable inputs is the single highest-leverage change.
- **Ensure upstream reference stability.** If a reducer returns a new array/map when nothing changed, every downstream selector invalidates (Q25). `@ngrx/entity` preserves references for unaffected entities; hand-rolled reducers often don't. Fix the reducer to return the *same* reference for unaffected data.
- **Two-stage the selector.** Don't do the full O(orders × lookups) join in one projector that also depends on a frequently-changing input. Split it: a memoized selector that joins orders→customers→products (depends only on the three maps), and a *separate* cheap selector that applies the dashboard's volatile filters/sort on the already-joined result. Now the expensive join recomputes only when an entity map changes, while filter/sort changes hit only the cheap second stage.
- **Result memoization for downstream stability.** Wrap the join with `resultMemoize`/structural equality (Q53) so that even when it *does* recompute, a value-equal result keeps the previous reference and doesn't cascade re-renders to every dashboard widget.
- **Reduce the work itself.** If the join is inherently O(n×m), precompute relationships at *write* time (store `order.customerId` and do O(1) map lookups rather than scanning), paginate/virtualize so you only join the *visible* rows (Q86), or move the join server-side and store the pre-joined view if the dashboard always needs it joined.

The architectural lesson is the normalize-on-write / denormalize-on-read principle (Q47) taken to its performance conclusion: the join *belongs* in a selector (it's derived, should be cached, must stay reference-stable), but a join selector is only as good as the **stability and narrowness of its inputs** and the **cost of its projector**. The recompute-on-unrelated-dispatch symptom is almost always a too-broad or unstable input; the tanks-the-dashboard symptom is the projector's intrinsic cost — and you need both fixes, because narrowing inputs without two-staging still pays the full join cost on every legitimate entity change, and two-staging without narrowing inputs still recomputes spuriously. Profiling tells you which is dominant so you fix in the right order.

#### Q89. [Theory] Explain `tapResponse` and why `@ngrx/operators` extracted it. What subtle bug does it prevent that a hand-written `tap` + `catchError` can introduce?

`tapResponse` (now in `@ngrx/operators`, originally part of ComponentStore) is a small operator that wraps the **next / error / complete** handling of an inner observable and — critically — **guarantees the error handler runs but does not let the error propagate to and kill the outer stream**, while also catching any error *thrown inside your next handler*. It exists because the hand-rolled equivalent has a subtle, recurring bug that violates the effects contract (Q8).

```typescript
import { tapResponse } from '@ngrx/operators';

// With tapResponse: next/error handled, outer stream protected, errors in `next` caught.
load$ = createEffect(() => this.actions$.pipe(
  ofType(Actions.load),
  switchMap(() => this.api.get().pipe(
    tapResponse({
      next: (data) => this.store.dispatch(Actions.success({ data })),
      error: (e: HttpErrorResponse) => this.store.dispatch(Actions.failure({ e })),
    }),
  )),
), { dispatch: false });
```

The subtle bug it prevents: consider the hand-written version `inner$.pipe(tap(data => doSomething(data)), catchError(e => of(failure())))`. If `doSomething` — the **`next`/`tap` handler** — *throws* (a null-deref while processing the response, a bug in the success path), that thrown error propagates *into the same pipeline* and is caught by the `catchError`, which then dispatches a **`failure` action for what was actually a successful HTTP response**. You get a misleading failure (the request succeeded; your *handling* of it threw), and worse, the real exception in your success-path code is silently swallowed and masquerades as a network/server failure — a genuinely confusing production bug where "the API is failing" is reported but the API is fine.

`tapResponse` separates the concerns correctly: it runs your `next` handler in a way where an exception *thrown by `next`* is reported (it doesn't get conflated with a source error and re-routed to your `error` handler as a fake failure), and the `error` handler fires *only* for actual errors emitted by the source observable. It also ensures the **outer stream survives** regardless — the operator absorbs the error so the effect keeps listening (the Q8 contract) without you having to remember to place `catchError` inside the flattening operator and reason about what it catches. The extraction to `@ngrx/operators` made it usable from *both* classic effects and SignalStore `rxMethod`/ComponentStore effects, since all three share the identical "handle the response without killing the long-lived stream, and don't let success-path exceptions impersonate failures" need. The takeaway for an interview: `tapResponse` isn't just sugar — it fixes the specific failure mode where a bug in your *success handling* gets reported as an API failure, which a naive `tap` + `catchError` actively introduces.

#### Q90. [Practical] How do you contract-test the boundary between a feature's store and its consumers so refactoring the store shape can't silently break the UI?

The risk is that a store refactor (renaming a state field, restructuring an entity, changing a selector's return shape) compiles fine but breaks consumers at runtime, or — worse — *seems* fine because TypeScript followed the rename but the *behavioral* contract (what data the UI gets, in what shape, with what defaults) silently changed. The defense is to treat the **facade/selector surface as a contract** and test *that surface* independently of the store internals, so the internals are free to change as long as the contract holds.

```typescript
// 1) The CONTRACT: what consumers are allowed to depend on (the facade's public shape).
export interface UsersFacadeContract {
  users$: Observable<UserVM[]>;          // view-model shape, NOT raw entity shape
  loading$: Observable<boolean>;
  load(): void;
  select(id: string): void;
}

// 2) Contract test: exercises the REAL store through the facade, asserts the OUTPUT shape
//    and behavior — agnostic to how state is structured internally.
describe('UsersFacade contract', () => {
  it('emits view-models after load, regardless of internal entity shape', async () => {
    TestBed.configureTestingModule({ providers: [provideStore({ users: usersReducer }), UsersFacade] });
    const facade = TestBed.inject(UsersFacade);
    // simulate the load success path (the facade/effect/reducer pipeline) ...
    const vms = await firstValueFrom(facade.users$);
    expect(vms[0]).toEqual(jasmine.objectContaining({ id: jasmine.any(String), displayName: jasmine.any(String) }));
  });
});
```

The layered strategy: **(a) the facade exposes view-models, not raw entities** — consumers depend on `UserVM` (`{ id, displayName }`), and the *selector* maps the internal entity shape to the VM. Now you can re-normalize, rename internal fields, or swap `@ngrx/entity` for `withEntities` and, as long as the selector still produces the same VM, *every consumer is unaffected and the contract test stays green*. **(b) Selector tests assert the projected output shape** (`.projector(...)`, Q16) — these are the unit-level contract on derivation logic. **(c) Facade contract tests** exercise the real reducer/effect/selector pipeline and assert the *emitted shape and behavior* the UI relies on, deliberately *not* asserting internal state structure. **(d) Optionally, consumer-driven contract tests** — if multiple teams consume a shared store library, each consumer contributes a test of what *it* depends on, and the store library's CI runs all of them, so the store team learns immediately if a refactor breaks any consumer (the Pact-style model applied internally).

The principle is **test the boundary, not the internals**: tests that assert internal state shape (`expect(state.users.entities[id].firstName)...`) *couple your tests to the implementation*, so a legitimate refactor breaks dozens of tests for no behavioral reason — the tests become a refactoring *tax* rather than a safety net. Tests that assert the *facade/selector output contract* let internals churn freely while guaranteeing consumers still get what they need. This is also what makes the Store↔SignalStore migration (Q22/Q83) safe: the facade contract test is the *invariant* you hold green while swapping the entire implementation underneath. The discipline to enforce in review: consumers (components) may only import the facade/VM types, never raw selectors/actions/state types (enforced by barrel exports and lint, Q77) — that import boundary is what makes the contract real rather than aspirational.

#### Q91. [Theory] When does modeling state as a discriminated union beat independent boolean/optional flags, and how does it eliminate "impossible states"? Show the refactor.

The most common avoidable bug class in store state is **independent flags that can combine into states the domain forbids**. A "loading data" feature modeled as `{ loading: boolean; error: string | null; data: T | null }` has 2 × 2 × 2 = 8 representable combinations, but only ~4 are *valid* — `{ loading: true, error: 'x', data: [...] }` is meaningless yet representable, and the bug is then "a missing guard" rather than "a structural impossibility" (Q48). A **discriminated union** keyed on a `status` literal makes only the valid states representable, so impossible states become *unconstructable* and the compiler enforces correct access.

```typescript
// ❌ Flag soup: 8 combinations, most invalid; every consumer must guard all three flags.
interface BadState { loading: boolean; error: string | null; data: User[] | null; }
// nothing stops { loading: true, error: 'boom', data: [...] } — three contradictory truths.

// ✅ Discriminated union: only the 4 legal states exist; data is present iff loaded.
type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'loaded'; data: User[] }            // data EXISTS only here, typed non-null
  | { status: 'error'; error: string };           // error EXISTS only here

// Reducer transitions between whole states, not flag flips:
on(load,        ()        => ({ status: 'loading' as const })),
on(loadSuccess, (_, { data })  => ({ status: 'loaded' as const, data })),
on(loadFailure, (_, { error }) => ({ status: 'error' as const, error })),

// Consumer: the compiler FORCES handling each case and only lets you read `data` when loaded.
switch (state.status) {
  case 'loaded': return state.data;       // ✅ data is User[], guaranteed present
  case 'error':  return state.error;      // ✅ error is string, guaranteed present
  // case 'loaded': state.error            // ❌ compile error — no `error` on the loaded variant
}
```

Why this beats flags concretely: (a) **impossible states are unrepresentable** — you cannot construct `loaded` *and* `error` simultaneously, so an entire class of "stale spinner while showing data" and "showing data while also showing an error" bugs cannot occur; (b) **data presence is tied to the state that owns it** — `data` is non-nullable inside `loaded`, eliminating the `data!.map(...)` non-null assertions and the `data?.` defensive checks that litter flag-based code, because the type system *proves* data exists in the only state where you read it; (c) **exhaustiveness checking** — a `switch` on `status` with TypeScript's `never` check forces consumers to handle every state, so adding a new state (`'refreshing'`) surfaces every place that must now handle it as a compile error rather than a runtime surprise; (d) it encodes a **finite state machine** inside plain NgRx (Q48) — the reducer's `on` clauses *are* the transition table — getting most of XState's correctness-by-construction without the dependency.

The trade-offs to state honestly: the union is slightly more verbose to *write* than three flags, and *transitions* must replace the whole state object rather than flipping one field (which is actually a feature — it forces you to think about what the new whole-state is). It's less ergonomic when states genuinely *do* have orthogonal independent dimensions (e.g. `isLoading` and `isEditing` that legitimately coexist) — there, separate flags or *nested* unions are right, because forcing truly-independent concerns into one union creates a combinatorial explosion of variants. The judgment: use a discriminated union whenever the flags are **mutually exclusive or have dependency relationships** (the classic loading/error/data triad always is) — which is most "status" modeling — and keep independent flags only for genuinely orthogonal booleans. Q48 called the discriminated-union technique "the single most underused way to make reducers correct by construction," and this is the mechanism: shrink the representable state space down to exactly the legal states, and the compiler does the guarding you'd otherwise hand-write and forget.

#### Q92. [Practical] Your CTO asks for a one-page rubric so any engineer can decide, in five minutes, what state tool to use for a new piece of state. Produce it and justify the cut points.

The goal is a decision aid that encodes the entire guide's judgment into something a mid-level engineer can apply without a meeting — turning "what does this feature *need*" (the recurring question across Q14/Q24/Q56/Q84) into a deterministic flowchart with named cut points. Here is the rubric I'd publish.

```
STATE DECISION RUBRIC  (answer top-down; first match wins)

Q1. Is it SERVER data (a cache of something the backend owns)?
    └─ YES → use a SERVER-STATE / CACHE library (TanStack/RTK Query, Apollo,
             or Angular resource()/httpResource / SignalStore-with-resource).
             Do NOT hand-roll loading/error/data in NgRx.        [cut: ownership]

Q2. Is it purely LOCAL to one component and dies with it?
    └─ YES → Signals (signal/computed) or component fields.
             Reach for ComponentStore only if it has COMPLEX async
             (cancellation/debounce) that benefits from RxJS structure. [cut: lifetime+complexity]

Q3. Is it SHARED across unrelated components OR must survive navigation?
    ├─ NO  → (you shouldn't be here) → back to Q2, keep it local.
    └─ YES → continue to Q4.                                       [cut: sharing]

Q4. Of these, do you need ≥2:  time-travel/replay debugging •
    serializable audit trail of WHY state changed •
    many independent consumers reacting to the same events •
    complex multi-step async needing cancel/queue/retry •
    team-scale uniform conventions across many features?
    ├─ NO  → a SHARED SERVICE with a Signal/BehaviorSubject is enough.
    │        (single source of truth, near-zero ceremony)          [cut: concern accumulation]
    └─ YES → use NgRx. Prefer SIGNALSTORE for new code; classic STORE
             when you specifically need meta-reducers + DevTools time-travel
             + the mature Redux action log.                        [cut: Redux value proposition]

ALWAYS, regardless of choice:
  • High-frequency source (scroll/tick/keystroke)? CONDITION it (debounce/buffer)
    before it becomes state — never pipe raw input into the store. (Q61/Q87)
  • Mutually-exclusive status flags? Model as a DISCRIMINATED UNION. (Q91)
  • Secrets/PII? Keep out of the store; never persist to localStorage. (Q20)
```

Justifying the cut points, because a rubric without rationale gets ignored or misapplied:

**Cut 1 — ownership (server vs client).** This is *first* because it's the highest-leverage and most-violated decision (Q49): hand-managing server data in NgRx is the largest source of needless boilerplate in real apps. Asking "does the backend own this?" up front diverts the majority of "state" (which is usually cached server data) away from the store entirely. **Cut 2 — lifetime + complexity.** Local, short-lived state should never touch a shared container (Q14); the only nuance is that *complex async* local state earns ComponentStore over plain Signals, so the cut is lifetime *and* async complexity, not lifetime alone. **Cut 3 — sharing.** The gate to *any* shared container — if it's not shared and doesn't survive navigation, it failed Q2 and shouldn't be here. **Cut 4 — concern accumulation.** The crux and the most nuanced: shared state alone does *not* justify NgRx (a Signal service suffices, Q56); NgRx earns its ceremony only when you need *several* of its cross-cutting features at once. Requiring **≥2** of the listed concerns is the deliberate cut point — it prevents reaching for NgRx for "it's shared" (over-engineering) while ensuring genuinely complex/audited shared state gets the structure it needs. The "always" footer captures the cross-cutting rules that apply *whatever* the storage choice, because they're correctness/security issues independent of the tool.

The meta-justification I'd give the CTO: this rubric optimizes for **consistent *judgment*, not uniform *tooling*** (Q14/Q23) — two engineers applying it to the same state reach the same answer, which is the actual goal of "standardization," while still routing each piece of state to the tool that fits. The single number that makes it work is the **≥2 concerns** threshold at Cut 4: it's the codified expression of "the store is a scarce resource" (Q19), turning the entire guide's recurring lesson into a five-minute, defensible decision any engineer can make alone.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q93. [Coding] Write the three action creators for an async "load profile" flow and the matching `createActionGroup` version. Explain the type/error shape.

**Problem:** Every async read needs a trigger, a success, and a failure action. Show both the long-form `createAction` style and the `createActionGroup` style, and type the error correctly so failure handling is uniform.

```typescript
import { createAction, props, createActionGroup, emptyProps } from '@ngrx/store';

// --- Long form (one createAction per event) ---
export const loadProfile        = createAction('[Profile] Load');
export const loadProfileSuccess = createAction('[Profile] Load Success', props<{ profile: Profile }>());
export const loadProfileFailure = createAction('[Profile] Load Failure', props<{ error: string }>());

// --- createActionGroup form (NgRx 13+): same three events, less noise ---
export const ProfileActions = createActionGroup({
  source: 'Profile',
  events: {
    // keys become camelCased creators; the string becomes the human-readable type.
    'Load': emptyProps(),
    'Load Success': props<{ profile: Profile }>(),
    'Load Failure': props<{ error: string }>(),
  },
});
// ProfileActions.load()           -> { type: '[Profile] Load' }
// ProfileActions.loadSuccess({…}) -> { type: '[Profile] Load Success', profile }
```

The **error shape is the load-bearing decision**. Use a serializable primitive (`string`) or a small plain DTO (`{ message: string; code?: number }`) — never the raw `HttpErrorResponse` or an `Error` instance, because those are non-serializable (Q29) and will trip `strictActionSerializability` and break DevTools replay. Normalize the error at the boundary (in the effect's `catchError`) into the DTO, so every failure reducer and every error-toast selector reads the same field.

The `[Source] Event` naming is not decoration: the `source` prefix makes actions greppable and groups them in the DevTools timeline, and the three-event triad (trigger/success/failure) is the canonical shape that `@ngrx/entity` selectors, loading-flag reducers, and retry effects all assume. Deviating from it (e.g. one action that "loads and sets") is the most common reason a feature later fights the framework.

#### Q94. [Coding] Implement a loading-state reducer that tracks `idle | loading | loaded | error` as a discriminated union instead of booleans. Show the reducer and a derived selector.

**Problem:** A `loading: boolean` + `error: string | null` pair allows impossible states (`loading: true, error: 'x'`). Model the request status as one field so impossible states are unrepresentable (the Q91 idea, applied to async).

```typescript
import { createReducer, on } from '@ngrx/store';
import { ProfileActions } from './profile.actions';

type RequestStatus =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'loaded'; profile: Profile }
  | { kind: 'error'; message: string };

interface ProfileState { status: RequestStatus; }
const initialState: ProfileState = { status: { kind: 'idle' } };

export const profileReducer = createReducer(
  initialState,
  on(ProfileActions.load,        () => ({ status: { kind: 'loading' } as const })),
  on(ProfileActions.loadSuccess, (_s, { profile }) => ({ status: { kind: 'loaded', profile } as const })),
  on(ProfileActions.loadFailure, (_s, { error })   => ({ status: { kind: 'error', message: error } as const })),
);

// Selector: the template never has to juggle two flags — it switches on `kind`.
export const selectProfileVm = createSelector(
  (s: { profile: ProfileState }) => s.profile.status,
  (status) => status,   // template: @switch (vm.kind) { @case ('loading') … }
);
```

The win is correctness, not aesthetics. With booleans, the reducer author must remember to *clear* `error` on every `load` and clear `loading` on success/failure — a missed clear leaves a stale flag, which is exactly the "spinner that never stops" / "stale error banner" bug. With the union, each transition *replaces* the whole status, so there is no flag to forget. TypeScript's exhaustiveness checking on `kind` also turns a forgotten case in the template or a downstream selector into a compile error.

The trade-off is ergonomic: accessing `profile` requires narrowing (`status.kind === 'loaded'`), which is slightly more verbose than `state.profile`. That verbosity is the point — it forces consumers to handle the not-yet-loaded states instead of dereferencing `undefined`. For trivial features two booleans are fine; the union pays off the moment the status drives non-trivial UI branching.

#### Q95. [Coding] Wire a complete minimal feature with standalone APIs: register the store, a feature reducer, and effects in `bootstrapApplication` / lazy route. Show the exact provider calls.

**Problem:** A junior asks "where does NgRx actually get plugged in with the standalone (no-NgModule) APIs?" Show root registration plus a lazily-registered feature.

```typescript
// main.ts — root bootstrap
import { bootstrapApplication } from '@angular/platform-browser';
import { provideStore } from '@ngrx/store';
import { provideEffects } from '@ngrx/effects';
import { provideStoreDevtools } from '@ngrx/store-devtools';
import { isDevMode } from '@angular/core';

bootstrapApplication(AppComponent, {
  providers: [
    provideStore(),                       // empty root — features register themselves
    provideEffects(),                     // root effects container
    provideStoreDevtools({ logOnly: !isDevMode(), maxAge: 50 }),
  ],
});

// orders.routes.ts — lazy feature route registers ITS OWN slice + effects
import { Routes } from '@angular/router';
import { provideState } from '@ngrx/store';
import { provideEffects } from '@ngrx/effects';
import { ordersFeature } from './orders.feature';   // a createFeature() result
import { OrderEffects } from './order.effects';

export const ORDERS_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(ordersFeature),        // key + reducer bundled by createFeature
      provideEffects(OrderEffects),       // effects scoped to this lazy chunk
    ],
    loadComponent: () => import('./orders.component').then((m) => m.OrdersComponent),
  },
];
```

The mental model: `provideStore()` at root creates the *empty* store and the reducer registry; `provideState()` at a route (or root) *adds a slice* to it. Putting `provideState`/`provideEffects` in a lazy route's `providers` is what makes the slice and its effects load *with the chunk* — the initial bundle stays lean and the slice simply doesn't exist until the route activates (which is why feature selectors must guard against `undefined`, Q70).

A common mistake is calling `provideState`/`provideEffects` in *both* root and the lazy route, or importing the feature eagerly somewhere, which loads it at startup and defeats lazy loading. The other mistake is forgetting `provideEffects(OrderEffects)` entirely — the reducer works, state changes, but no HTTP fires, producing the "dispatched but nothing happened" effect-branch from Q63. Keep root `provideStore()` empty in greenfield apps and let every feature own its own registration; that locality is what keeps a 50-feature app's startup comprehensible.

### 🟡 Intermediate — extended

#### Q96. [Coding] Implement a typeahead search effect: debounce input, cancel stale requests, ignore short queries, and never let the stream die. Then write a marble test for the cancellation.

**Problem:** A search box dispatches `search({ term })` on every keystroke. Build the effect with correct operator placement, and prove cancellation with a marble test.

```typescript
import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { of } from 'rxjs';
import { debounceTime, distinctUntilChanged, filter, map, switchMap, catchError } from 'rxjs/operators';

@Injectable()
export class SearchEffects {
  private actions$ = inject(Actions);
  private api = inject(SearchApi);

  search$ = createEffect(() =>
    this.actions$.pipe(
      ofType(SearchActions.search),
      map((a) => a.term.trim()),
      filter((term) => term.length >= 3),       // ignore noise queries
      debounceTime(250),                         // wait for the user to pause
      distinctUntilChanged(),                    // skip identical consecutive terms
      switchMap((term) =>                        // CANCEL the in-flight call on a new term
        this.api.query(term).pipe(
          map((results) => SearchActions.searchSuccess({ results })),
          catchError((e) => of(SearchActions.searchFailure({ error: e.message }))), // inside switchMap
        ),
      ),
    ),
  );
}
```

```typescript
// Marble test: a second term arrives before the first response → first is cancelled.
import { TestScheduler } from 'rxjs/testing';

it('cancels the stale request when a new term arrives', () => {
  const scheduler = new TestScheduler((a, b) => expect(a).toEqual(b));
  scheduler.run(({ hot, cold, expectObservable }) => {
    const actions$ = hot('-a 250ms -b|', {
      a: SearchActions.search({ term: 'ang' }),
      b: SearchActions.search({ term: 'angular' }),
    });
    // api.query returns a 100ms-delayed cold observable per call:
    const api = { query: () => cold('100ms (r|)', { r: ['x'] }) };
    const effects = new SearchEffects(actions$ as any, api as any);
    // Only the LAST term's success emits; the first never completes its inner stream.
    expectObservable(effects.search$).toBe('...', { /* one success after the second term */ });
  });
});
```

The operator *order* is the whole answer. `filter`/`debounceTime`/`distinctUntilChanged` live on the outer stream so they throttle *triggers*; `switchMap` is what provides cancellation by unsubscribing the previous inner HTTP observable when a new term arrives. `catchError` sits *inside* `switchMap` so a failed query produces a `searchFailure` action without killing the outer subscription (Q8) — if it were outside, the first network error would permanently disable search. Marble tests are the right tool here precisely because the bug class — "stale response overwrites fresh results" — is a *timing* bug that a subscription test with `of(...)` cannot express; only virtual time lets you assert that the slow first call is cancelled by the fast second.

#### Q97. [Coding] Implement an entity adapter feature end-to-end: state, reducer with `setAll`/`upsertOne`/`removeOne`/`updateOne`, and the exposed array selectors. Call out the `Update<T>` shape.

**Problem:** Build a `messages` collection with `@ngrx/entity` so the interviewer can see the full CRUD wiring, including the partial-update `Update<T>` type that trips people up.

```typescript
import { createEntityAdapter, EntityState, Update } from '@ngrx/entity';
import { createReducer, on, createFeatureSelector, createSelector } from '@ngrx/store';

export interface Message { id: string; body: string; read: boolean; ts: number; }
export interface MessagesState extends EntityState<Message> { selectedId: string | null; }

export const adapter = createEntityAdapter<Message>({
  selectId: (m) => m.id,                       // explicit: default is `entity.id`
  sortComparer: (a, b) => b.ts - a.ts,         // newest first; affects selectAll order
});

const initialState: MessagesState = adapter.getInitialState({ selectedId: null });

export const messagesReducer = createReducer(
  initialState,
  on(MsgActions.loadSuccess,  (s, { messages }) => adapter.setAll(messages, s)),
  on(MsgActions.received,     (s, { message })  => adapter.upsertOne(message, s)),
  on(MsgActions.markRead,     (s, { id }) =>
    // updateOne takes Update<T> = { id; changes: Partial<T> } — NOT a full entity.
    adapter.updateOne({ id, changes: { read: true } } as Update<Message>, s)),
  on(MsgActions.delete,       (s, { id }) => adapter.removeOne(id, s)),
);

// Default selectors operate on the ENTITY substate; compose from the feature selector.
const selectFeature = createFeatureSelector<MessagesState>('messages');
const { selectAll, selectEntities, selectTotal } = adapter.getSelectors(selectFeature);
export const selectAllMessages = selectAll;                       // Message[] (sorted)
export const selectUnreadCount = createSelector(selectAll, (ms) => ms.filter((m) => !m.read).length);
```

Two facts separate people who have used `@ngrx/entity` from those who have only read about it. First, `updateOne`/`updateMany` take `Update<T>` — `{ id, changes: Partial<T> }` — *not* a whole entity; passing a full object as `changes` works but `upsertOne` is the right call when you have the complete record. Second, `getSelectors()` must be given the *feature* selector, because the adapter's selectors expect the `{ ids, entities }` substate, not the global root; wiring them against the root state is a frequent "selectAll returns undefined" bug.

The `sortComparer` is a quiet performance and correctness lever: with it set, `selectAll` returns entities pre-sorted and re-sorts on every insert, so the UI never sorts in the template (which would break memoization by producing new arrays). Omit it and `selectAll` follows insertion order. Either is valid, but mixing — sorting again in a component selector — both wastes work and risks the new-reference-every-call trap from Q5. Let the adapter own ordering.

#### Q98. [Coding] Convert a classic Store feature to a SignalStore equivalent, including `withEntities`, a computed, and an `rxMethod` for the async load. Show both so the diff is visible.

**Problem:** Show the same "users with a loading flag and a search filter" feature implemented as a SignalStore, so a team mid-migration (Q22) can see the shape they are moving toward.

```typescript
import { computed } from '@angular/core';
import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { withEntities, setAllEntities, setEntity } from '@ngrx/signals/entities';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap } from 'rxjs';
import { tapResponse } from '@ngrx/operators';

export const UsersStore = signalStore(
  { providedIn: 'root' },
  withEntities<User>(),                                  // adds entityMap/ids/entities signals
  withState({ loading: false, filter: '' }),
  withComputed(({ entities, filter }) => ({
    filtered: computed(() =>
      entities().filter((u) => u.name.toLowerCase().includes(filter().toLowerCase()))),
    total: computed(() => entities().length),
  })),
  withMethods((store, api = inject(UserApi)) => ({
    setFilter: (filter: string) => patchState(store, { filter }),
    // rxMethod: an imperative-callable method backed by an RxJS pipeline.
    load: rxMethod<void>(
      pipe(
        tap(() => patchState(store, { loading: true })),
        switchMap(() =>
          api.getAll().pipe(
            tapResponse({
              next: (users) => patchState(store, setAllEntities(users), { loading: false }),
              error: () => patchState(store, { loading: false }),
            }),
          ),
        ),
      ),
    ),
  })),
);
// Component: store = inject(UsersStore); store.load(); list = store.filtered; // a Signal, read in template
```

The structural differences worth narrating: there are **no actions, no reducer file, no selector file, and no separate effects class** — `withState`/`withComputed`/`withMethods` collapse those four concerns into one store, and reads are *synchronous Signals* (`store.filtered()`), so the template needs no `async` pipe. `withEntities` supplies the same normalized `{ ids, entities }` model as `@ngrx/entity` with signal-shaped updater helpers (`setAllEntities`, `setEntity`, `updateEntity`).

`rxMethod` is the bridge that makes async tractable without leaving the Signal world: it returns a function you call imperatively (`store.load()`), but internally it's a long-lived RxJS pipeline, so you keep `switchMap` cancellation, `debounceTime`, and `tapResponse` (Q89). The honest trade-off versus the classic Store: you lose the global Redux DevTools *action timeline* for this feature (there are no actions to log; `withDevtools` from the signals tooling gives a state-snapshot view instead) and the cross-feature audit trail. For a self-contained feature that is a clear win; for genuinely-audited shared domain state (auth, money movement) the classic Store's action log can still justify keeping it.

#### Q99. [Coding] Write a unit test for a SignalStore (no TestBed): instantiate it, call a method, and assert on the computed signal. Then show the TestBed variant for the DI-backed version.

**Problem:** SignalStores are testable two ways. Show the lightweight functional test and the TestBed test for the `providedIn: 'root'` case with an injected API.

```typescript
import { TestBed } from '@angular/core/testing';
import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { computed } from '@angular/core';

const CartStore = signalStore(
  { providedIn: 'root' },
  withState({ items: [] as { price: number }[] }),
  withComputed(({ items }) => ({ total: computed(() => items().reduce((a, i) => a + i.price, 0)) })),
  withMethods((store) => ({ add: (price: number) => patchState(store, (s) => ({ items: [...s.items, { price }] })) })),
);

// --- Variant A: TestBed (works for providedIn:'root' or local providers) ---
describe('CartStore (TestBed)', () => {
  it('recomputes total when an item is added', () => {
    const store = TestBed.inject(CartStore);
    expect(store.total()).toBe(0);
    store.add(10);
    store.add(5);
    expect(store.total()).toBe(15);   // computed signal updated synchronously
  });
});

// --- Variant B: inject a fake dependency via TestBed providers ---
describe('UsersStore with fake API', () => {
  it('loads users', () => {
    TestBed.configureTestingModule({
      providers: [{ provide: UserApi, useValue: { getAll: () => of([{ id: '1', name: 'A' }]) } }],
    });
    const store = TestBed.inject(UsersStore);
    store.load();
    expect(store.total()).toBe(1);    // rxMethod ran synchronously with `of(...)`
  });
});
```

SignalStore testing is dramatically lighter than classic Store testing because there is no action stream to mock and no `provideMockActions`. The reads are synchronous signals, so you call a method and immediately assert the computed value — no `subscribe`/`done`, no marble harness for the simple cases. For a store with no `inject()` dependencies you can even `new`-up the underlying class, but `TestBed.inject` is the robust path because it satisfies the injection context that `inject()` inside `withMethods` requires.

The subtlety: `rxMethod` pipelines are *asynchronous* if the inner observable is asynchronous. With a synchronous `of(...)` fake (above) the assertion can be immediate; with `debounceTime` or an HTTP fake that schedules, you must advance time (`fakeAsync` + `tick`, or `TestScheduler`) before asserting, exactly as with effects (Q96). The win is that for the common synchronous-fake case the test is three lines and reads like the production code, which is a real ergonomic argument for SignalStore in test-heavy codebases.

#### Q100. [Coding] Implement a "select entity by route param" pattern that stays memoized as the route changes, using `@ngrx/router-store` selectors.

**Problem:** A detail page reads `:orderId` from the route and must show that order from the entity store, recomputing only when the param or the order actually changes — without a parameterized-selector factory per id.

```typescript
import { createSelector } from '@ngrx/store';
import { getRouterSelectors } from '@ngrx/router-store';

// Router selectors come from router-store; selectRouteParam reads :orderId reactively.
const { selectRouteParam } = getRouterSelectors();
const selectOrderId = selectRouteParam('orderId');          // Signal/Observable of the current param

const { selectEntities } = orderAdapter.getSelectors(selectOrdersFeature);

// Compose param + entities. Memoized: recomputes only when id OR entities change.
export const selectSelectedOrder = createSelector(
  selectEntities,
  selectOrderId,
  (entities, id) => (id ? entities[id] : undefined),
);
```

This is the idiomatic alternative to the selector-factory-per-id pattern (Q7). Because the *current* route param flows through the store via `@ngrx/router-store`, a single statically-defined `createSelector` stays correctly memoized: it recomputes when the entities dictionary changes *or* when the param changes, and returns the same reference otherwise. There is no per-id cache to manage and no thrashing, because at any moment there is exactly one "current" `orderId`.

The prerequisite is wiring `provideRouterStore()` (or `StoreRouterConnectingModule`) so navigation is serialized into the store as router state — that is what makes `selectRouteParam` a *store* selector rather than an imperative `ActivatedRoute.snapshot` read. The architectural payoff (Q55) is that "which order is selected" becomes derivable state with no extra action or reducer: navigation *is* the state change. The pitfall to flag: choose a router *serializer* that keeps router state minimal (the default full serializer can put large, frequently-changing router objects in the store and dispatch a router action on every navigation), and never duplicate the param into a separate `selectedId` slice — that creates two sources of truth that drift.

#### Q101. [Coding] Implement cross-tab state sync via the `storage` event so a logout in one tab logs out all tabs. Show the effect and the guard against echo loops.

**Problem:** When the user logs out (or a token changes) in one browser tab, every other open tab of the app must react. Implement it on top of NgRx without an infinite write/read loop (the Q68 idea, coded).

```typescript
import { Injectable, inject, NgZone } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { fromEvent } from 'rxjs';
import { filter, map, tap } from 'rxjs/operators';

const SYNC_KEY = 'auth_sync';

@Injectable()
export class TabSyncEffects {
  private actions$ = inject(Actions);
  private zone = inject(NgZone);

  // 1) WRITE: when THIS tab logs out, broadcast by touching localStorage.
  broadcastLogout$ = createEffect(() =>
    this.actions$.pipe(
      ofType(AuthActions.logout),
      tap(() => localStorage.setItem(SYNC_KEY, JSON.stringify({ type: 'logout', at: Date.now() }))),
    ),
    { dispatch: false },                                     // side-effect only
  );

  // 2) READ: the `storage` event fires only in OTHER tabs, never the writer — no echo.
  receiveLogout$ = createEffect(() =>
    fromEvent<StorageEvent>(window, 'storage').pipe(
      filter((e) => e.key === SYNC_KEY && e.newValue != null),
      // storage events arrive outside Angular's zone — re-enter so CD/effects run:
      tap(() => this.zone.run(() => {})),
      map(() => AuthActions.logoutFromOtherTab()),           // a DISTINCT action, not `logout`
    ),
  );
}
```

The echo-loop guard is structural, and it relies on a browser guarantee people forget: the `storage` event fires in **every other tab of the same origin but never in the tab that wrote the value**. That means the writer (`broadcastLogout$`) cannot hear its own write, so there is no infinite loop *by construction*. The second safeguard is dispatching a *distinct* action (`logoutFromOtherTab`) on receipt rather than re-dispatching `logout` — even if a future code path made the writer hear itself, the receiver's action would not re-trigger the broadcaster's `ofType(logout)`.

Two production details matter. `storage` events are delivered **outside Angular's NgZone**, so without the `zone.run` re-entry the resulting state change may not trigger change detection (a "logged out in state but UI still shows the app" bug under zone-based CD); under zoneless/signal CD this is unnecessary but harmless. And only *whitelisted, non-secret* signals should travel this channel — broadcasting a "logout happened" marker is fine; broadcasting tokens through `localStorage` would re-introduce the secret-persistence risk from Q20. For richer multi-message sync, `BroadcastChannel` is a cleaner API than the `storage` event, but the echo-free property and the zone caveat are identical.

### 🟠 Advanced — extended

#### Q102. [Coding] Implement a saga-style multi-step effect: `checkout` must reserve inventory, charge payment, then create the order — rolling back earlier steps if a later one fails. Show the compensation logic.

**Problem:** A checkout is three sequential remote calls where step 2 failing must release the step-1 reservation, and step 3 failing must both refund step 2 and release step 1. Model this as an effect with explicit compensation.

```typescript
import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { of, EMPTY } from 'rxjs';
import { catchError, concatMap, map, switchMap } from 'rxjs/operators';

@Injectable()
export class CheckoutEffects {
  private actions$ = inject(Actions);
  private inv = inject(InventoryApi);
  private pay = inject(PaymentApi);
  private ord = inject(OrderApi);

  checkout$ = createEffect(() =>
    this.actions$.pipe(
      ofType(CheckoutActions.checkout),
      // exhaustMap: ignore double-clicks on "Place order" while one is in flight.
      switchMap(({ cart }) =>
        this.inv.reserve(cart).pipe(
          concatMap((reservation) =>
            this.pay.charge(cart.total).pipe(
              concatMap((payment) =>
                this.ord.create(cart, reservation.id, payment.id).pipe(
                  map((order) => CheckoutActions.checkoutSuccess({ order })),
                  // step 3 failed → compensate steps 2 and 1, then surface failure:
                  catchError((e) => {
                    this.pay.refund(payment.id).subscribe();
                    this.inv.release(reservation.id).subscribe();
                    return of(CheckoutActions.checkoutFailure({ step: 'order', error: e.message }));
                  }),
                ),
              ),
              // step 2 failed → compensate step 1 only:
              catchError((e) => {
                this.inv.release(reservation.id).subscribe();
                return of(CheckoutActions.checkoutFailure({ step: 'payment', error: e.message }));
              }),
            ),
          ),
          // step 1 failed → nothing to compensate:
          catchError((e) => of(CheckoutActions.checkoutFailure({ step: 'reserve', error: e.message }))),
        ),
      ),
    ),
  );
}
```

This is a **saga / compensating-transaction** pattern: there is no distributed transaction across three services, so consistency is achieved by *undoing* completed steps when a later step fails. The nesting is deliberate — each `catchError` sits at the level where it knows exactly which prior steps succeeded and therefore which compensations to run. A flat pipeline could not know, at the point of failure, whether payment had already been charged.

The honest critique an interviewer is listening for: this nested-`catchError` form is *correct but hard to read and hard to test*, and the fire-and-forget `.subscribe()` compensations swallow their own failures (what if the refund also fails?). Beyond a few steps, this is the canonical case for **extracting the workflow into an explicit state machine** (XState, Q48) where each state and transition — including the "refunding" and "releasing" compensation states — is named, visualizable, and exhaustively tested, with retries on the compensation calls themselves. NgRx effects *can* express sagas, but the moment compensation logic appears, "should this be a state machine?" is the right architectural question to raise, not to suppress.

#### Q103. [Coding] Write a DevTools `actionSanitizer` and `stateSanitizer` that scrub auth tokens and PII before they reach the extension. Explain why this is necessary even in dev.

**Problem:** Login actions carry a password; the user slice holds a JWT and an email. These must never appear in the Redux DevTools timeline (shoulder-surfing, screen-share leaks, support-tool exposure). Implement sanitizers.

```typescript
import { provideStoreDevtools } from '@ngrx/store-devtools';
import { AuthActions } from './auth.actions';

const REDACTED = '***REDACTED***';

provideStoreDevtools({
  maxAge: 50,
  // Runs on a COPY before DevTools serializes the action — never mutate the real action.
  actionSanitizer: (action: any) => {
    if (action.type === AuthActions.login.type) {
      return { ...action, password: REDACTED };
    }
    return action;
  },
  // Runs on a COPY of state before it is sent to the extension.
  stateSanitizer: (state: any) => {
    if (!state?.auth) return state;
    return {
      ...state,
      auth: { ...state.auth, token: state.auth.token ? REDACTED : null, refreshToken: REDACTED },
      user: state.user ? { ...state.user, email: maskEmail(state.user.email) } : state.user,
    };
  },
});

function maskEmail(e?: string) {
  if (!e) return e;
  const [name, domain] = e.split('@');
  return `${name[0]}***@${domain}`;
}
```

The sanitizers run only on the path *into* DevTools — they produce a redacted *copy* for the extension and do not alter the real action or state, so behavior is unchanged while the timeline is safe. The reason this matters even in development is that DevTools sessions routinely leak: engineers screen-share with the panel open, paste exported state into bug reports, and support engineers run `logOnly` instrumentation in staging that mirrors production data. A JWT visible in the timeline is a live credential; a list of customer emails is a privacy incident regardless of environment.

This complements rather than replaces the deeper rule (Q20): the *real* fix is to keep secrets out of the store entirely (tokens in `httpOnly` cookies). But payloads like a login password necessarily pass *through* an action on their way to an effect, and some PII legitimately lives in state for the UI — the sanitizer is the defense for exactly those unavoidable cases. The two pitfalls: mutating the original object inside the sanitizer (corrupts real state — always spread/copy), and forgetting that `actionSanitizer` must handle *every* sensitive action type, which argues for a small allowlist/denylist table rather than ad-hoc `if`s as the action surface grows.

#### Q104. [Coding] Implement a custom `signalStoreFeature` that adds reusable `setLoading`/`setError`/loading-state to any store. Show it composed into two different stores.

**Problem:** Several SignalStores repeat the same `loading`/`error` boilerplate. Extract it into a reusable, type-safe `signalStoreFeature` (the composition power classic Store lacks, Q59).

```typescript
import { computed } from '@angular/core';
import { signalStoreFeature, withState, withComputed, withMethods, patchState, type } from '@ngrx/signals';

// A reusable feature: any store can `withRequestStatus()` and gain these signals/methods.
export function withRequestStatus() {
  return signalStoreFeature(
    withState({ loading: false, error: null as string | null }),
    withComputed(({ loading, error }) => ({
      isError: computed(() => error() !== null),
      isIdle: computed(() => !loading() && error() === null),
    })),
    withMethods((store) => ({
      setLoading: () => patchState(store, { loading: true, error: null }),
      setLoaded:  () => patchState(store, { loading: false, error: null }),
      setError:   (error: string) => patchState(store, { loading: false, error }),
    })),
  );
}

// Composed into two unrelated stores — zero duplication:
export const UsersStore = signalStore(
  withEntities<User>(),
  withRequestStatus(),                          // <-- reused
  withMethods((store, api = inject(UserApi)) => ({
    load: rxMethod<void>(pipe(
      tap(() => store.setLoading()),
      switchMap(() => api.getAll().pipe(tapResponse({
        next: (u) => { patchState(store, setAllEntities(u)); store.setLoaded(); },
        error: (e: Error) => store.setError(e.message),
      }))),
    )),
  })),
);

export const ReportStore = signalStore(
  withState({ rows: [] as Row[] }),
  withRequestStatus(),                          // <-- same feature, different store
);
```

`signalStoreFeature` is the unit of *reuse* in the SignalStore world, and it is something the classic Store fundamentally cannot do cleanly. With actions/reducers, sharing loading-flag logic across features means either duplicating `on(load,…)` cases in each reducer or hand-rolling a higher-order reducer — both clumsy. A `signalStoreFeature` packages state + computed + methods together and is *composed by value* into any store, with full type inference flowing through, so `UsersStore.isError()` and `ReportStore.isError()` both exist and are typed without a line of duplicated code.

The advanced edge: features can declare *input* requirements (via the `type<...>()` helper and the `withFeature` input pattern) so a feature can depend on state another feature provides, and the compiler enforces the composition order. This is genuinely powerful — cross-cutting concerns (request status, pagination, selection, undo) become a *library of features* you assemble per store. The risk to flag is over-abstraction: a feature that tries to be universal accretes config flags and becomes harder to read than the duplication it replaced. The sweet spot is small, single-purpose features (status, selection) that are obviously reusable across many stores.

#### Q105. [Coding] Implement undo/redo for a drawing app as a meta-reducer using the command/snapshot hybrid. Show the meta-reducer and the `undo`/`redo` actions.

**Problem:** A canvas editor needs undo/redo over shape edits. Implement it generically with a meta-reducer that snapshots state, bounded to N levels (the Q67 idea, coded).

```typescript
import { ActionReducer, MetaReducer } from '@ngrx/store';
import { HistoryActions } from './history.actions';

interface Historied<S> { past: S[]; present: S; future: S[]; }
const LIMIT = 50;

export function undoRedo<S>(reducer: ActionReducer<S>): ActionReducer<any> {
  let history: Historied<S> | undefined;

  return (state: any, action) => {
    history ??= { past: [], present: reducer(undefined, action), future: [] };

    switch (action.type) {
      case HistoryActions.undo.type: {
        if (!history.past.length) return history.present;
        const previous = history.past[history.past.length - 1];
        history = {
          past: history.past.slice(0, -1),
          present: previous,
          future: [history.present, ...history.future],
        };
        return history.present;
      }
      case HistoryActions.redo.type: {
        if (!history.future.length) return history.present;
        const next = history.future[0];
        history = { past: [...history.past, history.present], present: next, future: history.future.slice(1) };
        return history.present;
      }
      default: {
        const newPresent = reducer(history.present, action);
        if (newPresent === history.present) return history.present;   // no-op: don't record
        history = {
          past: [...history.past, history.present].slice(-LIMIT),     // bound memory
          present: newPresent,
          future: [],                                                 // any new edit clears redo
        };
        return history.present;
      }
    }
  };
}
```

This is the **snapshot** approach: keep `past`/`present`/`future` stacks of whole state objects, and undo/redo simply shuffle between them. It is trivially correct and works for *any* reducer because it treats state as opaque, which is its great virtue. The two design decisions that make it production-safe are the `slice(-LIMIT)` cap on `past` (snapshots of large state are the memory risk, so history must be bounded) and the `newPresent === history.present` short-circuit, which avoids recording undo entries for actions that didn't actually change state (otherwise "undo" would appear to do nothing several times).

The alternative is the **command** approach: store the actions (commands) and their inverses, and undo by applying the inverse. Commands use far less memory (an action is tiny versus a full state snapshot) and give a semantic history ("undo add-rectangle"), but they require every action to have a correct inverse — which is the hard, bug-prone part, and impossible for non-invertible operations without also snapshotting. The pragmatic production choice is the hybrid hinted here: snapshot for safety, bounded for memory, and reserve command-style inverses only for the hot path where snapshot memory is prohibitive. Note this meta-reducer is *stateful* (closure over `history`), which is a deliberate, well-contained exception to purity that DevTools time-travel will not understand — a trade-off worth stating aloud.

#### Q106. [Coding] Write a marble-based test that proves an effect using `mergeMap` corrupts ordering for sequential writes, then the `concatMap` fix. Make the bug observable.

**Problem:** A "rename" effect uses `mergeMap`; two quick renames can land out of order, leaving the wrong name persisted. Demonstrate the bug and the fix with virtual time.

```typescript
import { TestScheduler } from 'rxjs/testing';
import { mergeMap, concatMap, map } from 'rxjs/operators';
import { ofType, Actions, createEffect } from '@ngrx/effects';

// The server is artificially slow for the FIRST rename and fast for the SECOND.
function makeApi(scheduler: TestScheduler) {
  return {
    rename: (name: string) =>
      name === 'first'
        ? scheduler.createColdObservable('30ms (r|)', { r: name })   // slow
        : scheduler.createColdObservable('5ms (r|)',  { r: name }),  // fast
  };
}

it('mergeMap lets the slow first write resolve AFTER the fast second → wrong final order', () => {
  const scheduler = new TestScheduler((a, b) => expect(a).toEqual(b));
  scheduler.run(({ hot, expectObservable }) => {
    const api = makeApi(scheduler);
    const actions$ = hot('-a-b|', { a: A.rename({ name: 'first' }), b: A.rename({ name: 'second' }) });
    const buggy$ = actions$.pipe(ofType(A.rename),
      mergeMap(({ name }) => api.rename(name).pipe(map((n) => A.renameDone({ name: n })))));
    // 'second' completes first (5ms), then 'first' (30ms) — DONE order is second, FIRST. Bug.
    expectObservable(buggy$).toBe('--- 2ms d 24ms e|',
      { d: A.renameDone({ name: 'second' }), e: A.renameDone({ name: 'first' }) });
  });
});

it('concatMap serializes writes → done order matches dispatch order', () => {
  const scheduler = new TestScheduler((a, b) => expect(a).toEqual(b));
  scheduler.run(({ hot, expectObservable }) => {
    const api = makeApi(scheduler);
    const actions$ = hot('-a-b|', { a: A.rename({ name: 'first' }), b: A.rename({ name: 'second' }) });
    const fixed$ = actions$.pipe(ofType(A.rename),
      concatMap(({ name }) => api.rename(name).pipe(map((n) => A.renameDone({ name: n })))));
    // concatMap waits for 'first' (30ms) before starting 'second' — order preserved.
    expectObservable(fixed$).toBe('31ms d 5ms (e|)',
      { d: A.renameDone({ name: 'first' }), e: A.renameDone({ name: 'second' }) });
  });
});
```

This makes the Q58 argument *executable*: the choice of flattening operator is a correctness decision, not a style one. With `mergeMap` both requests run concurrently, so the faster `second` resolves before the slower `first`, and the last `renameDone` to arrive carries `'first'` — the wrong final value persists. The bug is invisible in any test that uses `of(...)` (synchronous, no overlap) and invisible in manual testing on a fast network; it only manifests under real latency variance, which is exactly why a marble test with engineered timing is the right tool.

`concatMap` fixes it by queuing: it does not subscribe to the second inner observable until the first completes, so `renameDone` order always matches dispatch order. The cost is throughput — writes are serialized, so a burst is as slow as its sum. The general rule the test encodes: for **writes whose order is semantically meaningful** (renames, status toggles, balance adjustments) use `concatMap`; reserve `mergeMap` for **independent, order-irrelevant** work (firing analytics, prefetching unrelated resources). A team that cannot articulate which of their effects are order-sensitive has latent corruption bugs waiting for a slow day on the network.

#### Q107. [Coding] Implement a polling effect that starts on one action, stops on another, and pauses when the tab is hidden — without leaking the interval. Show the operator structure.

**Problem:** A live dashboard should poll an endpoint every 5s while a route is active and the tab is visible, and stop cleanly on navigation away. Build it so the timer never leaks.

```typescript
import { Injectable, inject } from '@angular/core';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { timer, fromEvent, merge, of } from 'rxjs';
import { switchMap, takeUntil, map, catchError, filter, startWith } from 'rxjs/operators';

@Injectable()
export class DashboardEffects {
  private actions$ = inject(Actions);
  private api = inject(MetricsApi);

  poll$ = createEffect(() =>
    this.actions$.pipe(
      ofType(DashActions.startPolling),
      switchMap(() => {
        // visibility$ emits true when the tab is visible, false when hidden.
        const visible$ = fromEvent(document, 'visibilitychange').pipe(
          map(() => !document.hidden),
          startWith(!document.hidden),
        );
        return visible$.pipe(
          // restart/cancel the timer whenever visibility flips:
          switchMap((isVisible) =>
            isVisible
              ? timer(0, 5000).pipe(
                  switchMap(() => this.api.getMetrics().pipe(
                    map((m) => DashActions.metricsLoaded({ m })),
                    catchError((e) => of(DashActions.metricsFailed({ error: e.message }))),
                  )),
                )
              : of(),                                  // hidden → emit nothing, timer stopped
          ),
          // STOP everything when stopPolling is dispatched (e.g. route deactivate):
          takeUntil(this.actions$.pipe(ofType(DashActions.stopPolling))),
        );
      }),
    ),
  );
}
```

The leak-prevention is entirely in the operator topology. The outer `switchMap(startPolling)` means a second `startPolling` cancels the first poller (no duplicate timers). `takeUntil(stopPolling$)` is the clean shutdown: when the user navigates away and a route guard or `ngOnDestroy` dispatches `stopPolling`, the entire inner subscription — timer and any in-flight HTTP — is torn down. The visibility `switchMap` stops the timer when the tab is hidden (saving battery and server load) and resumes on return; using `switchMap` here, not `filter`, is what actually *unsubscribes* the timer rather than merely dropping its emissions.

The classic bug this avoids is the orphaned interval: starting a `setInterval` (or an un-`takeUntil`'d `timer`) in an effect that nothing ever stops, so polling continues after the user leaves the page, multiplying every time they revisit (N visits → N concurrent pollers hammering the API). The two details interviewers probe: `catchError` is *inside* the per-tick `switchMap` so one failed poll doesn't kill the whole poller (Q8), and `stopPolling` must actually be dispatched on route exit — wiring it to a `CanDeactivate` guard or the component's destroy hook is the part teams forget, leaving the effect technically correct but never told to stop.

#### Q108. [Coding] Two reducers must react to the *same* action to keep two slices consistent (e.g. `userDeleted` clears the user slice and removes their posts). Show this and contrast with an effect-based approach.

**Problem:** When `userDeleted({ id })` fires, both the `users` slice and the `posts` slice must update. Show the shared-action pattern and explain why it beats an effect here.

```typescript
// users.reducer.ts — owns the users slice
export const usersReducer = createReducer(
  usersInitial,
  on(UserActions.userDeleted, (s, { id }) => usersAdapter.removeOne(id, s)),
);

// posts.reducer.ts — a DIFFERENT slice reacts to the SAME action
export const postsReducer = createReducer(
  postsInitial,
  on(UserActions.userDeleted, (s, { id }) => {
    // remove every post authored by the deleted user
    const orphanIds = Object.values(s.entities).filter((p) => p!.authorId === id).map((p) => p!.id);
    return postsAdapter.removeMany(orphanIds, s);
  }),
);
```

This is the **"dispatched from many, reduced by many"** model (Q54) and it is the *correct* tool when the reaction is a **pure, synchronous state transformation** in another slice. NgRx runs *every* registered reducer for every action, so one `userDeleted` action atomically updates both slices in a single state transition — there is no intermediate state where the user is gone but their posts remain, and no extra action in the timeline. It is purely synchronous, fully time-travel-compatible, and trivially testable per reducer.

Contrast the effect approach: an effect listens for `userDeleted`, then dispatches a *second* action `removePostsForUser({ id })`. That is the *wrong* choice here because it introduces an avoidable intermediate state (between the two actions the store is inconsistent), doubles the action volume, and adds an async hop for what is pure computation — all to express "react to an action," which reducers already do natively. The rule: if reacting means **transforming state you already have**, react in the reducer of the affected slice; reserve effects for reactions that need **I/O or data you don't have** (e.g. `userDeleted` → call an audit-log API, or fetch replacement data). Conflating these is a frequent source of action-volume bloat and consistency bugs.

#### Q109. [Coding] Build a typed, mockable facade with a SignalStore behind it, and write the component test that mocks the facade. Show why the facade makes the test trivial.

**Problem:** Demonstrate the facade boundary (Q11/Q83) with a modern SignalStore implementation and prove the testing payoff: a component test that never touches NgRx.

```typescript
// users.facade.ts — the stable interface components depend on
import { Injectable, inject, Signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class UsersFacade {
  private store = inject(UsersStore);            // SignalStore behind the curtain
  readonly users: Signal<User[]>   = this.store.entities;
  readonly loading: Signal<boolean> = this.store.loading;
  readonly error: Signal<string | null> = this.store.error;
  load(): void { this.store.load(); }
  setFilter(f: string): void { this.store.setFilter(f); }
}

// users.component.ts — depends ONLY on the facade
@Component({ /* template uses facade.users(), facade.loading() */ })
export class UsersComponent {
  protected facade = inject(UsersFacade);
  ngOnInit() { this.facade.load(); }
}

// users.component.spec.ts — mock the facade; zero NgRx in the test
import { signal } from '@angular/core';
describe('UsersComponent', () => {
  it('calls load on init and renders users', () => {
    const fake: Partial<UsersFacade> = {
      users: signal([{ id: '1', name: 'Ada' }]) as any,
      loading: signal(false) as any,
      load: jasmine.createSpy('load'),
    };
    TestBed.configureTestingModule({
      imports: [UsersComponent],
      providers: [{ provide: UsersFacade, useValue: fake }],
    });
    const fixture = TestBed.createComponent(UsersComponent);
    fixture.detectChanges();
    expect(fake.load).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Ada');
  });
});
```

The facade earns its keep in two ways here. First, the component test mocks **one small interface** with plain signals — there is no store to configure, no actions to dispatch, no `provideMockStore` with an initial-state object that must mirror the real shape. The test states exactly what the component needs (`users`, `loading`, `load`) and asserts behavior, which is faster to write and far less brittle than a test coupled to the store's internal structure. Second, because the component imports neither `UsersStore` nor any action, the team can migrate the implementation behind the facade (classic Store → SignalStore, as shown) without touching a single component or component test — the exact incremental-migration boundary from Q22.

The counter-argument (Q83) still applies and is worth voicing: this facade is a thin, justified pass-through. The failure mode is when facades stop being thin — accreting business logic, orchestrating multiple stores, becoming the god-service that re-introduces indirection. The discipline is that a facade should expose *intent* (`load()`, `setFilter()`) and *projections* (signals), and contain *no logic* of its own; the moment a facade method does more than dispatch/delegate, that logic belongs in the store (a method/effect), not the facade. Kept thin, the facade is one of the highest-leverage testability boundaries in an Angular codebase; allowed to grow, it is the thing the next architect rips out.

#### Q110. [Coding] Implement a custom selector with a non-default equality and a bounded cache using `createSelectorFactory` + `defaultMemoize`. When is this the right tool?

**Problem:** A selector returns a freshly-built array on every recompute, and an upstream slice occasionally emits a *value-equal but reference-different* object, causing needless downstream invalidation. Use a custom memoization with structural equality, bounded appropriately.

```typescript
import { createSelectorFactory, defaultMemoize, resultMemoize } from '@ngrx/store';

// Shallow-array equality: treat two arrays with the same elements (by ref) as equal.
function shallowArrayEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
  return a.every((x, i) => x === b[i]);
}

// createSelectorFactory lets us choose the memoize fn AND the output-equality check.
const createArrayEqualSelector = createSelectorFactory((projector) =>
  defaultMemoize(projector, /* isArgumentEqual */ undefined, /* isResultEqual */ shallowArrayEqual),
);

export const selectVisibleIds = createArrayEqualSelector(
  selectAllItems,
  selectFilter,
  (items: Item[], filter: string) =>
    items.filter((i) => i.tags.includes(filter)).map((i) => i.id),   // new array each run...
);
// ...but if the resulting id list is element-wise unchanged, the SAME reference is returned,
// so OnPush components and downstream selectors do NOT re-render.
```

`createSelectorFactory` is the escape hatch beneath `createSelector`: `createSelector` is just `createSelectorFactory(defaultMemoize)`. By supplying your own memoize function you control two distinct equality checks — *input* equality (when to recompute) and *result* equality (whether the new output should be treated as "changed" for downstream consumers). The case above uses the **result equality** lever: the projector legitimately builds a new array each run, but `shallowArrayEqual` lets the memoizer return the *previous* reference when the contents are element-wise identical, stopping a re-render cascade. `resultMemoize(projector, isEqual)` is a convenience for exactly this output-equality scenario.

This is a sharp, rarely-needed tool, and reaching for it is usually a signal to fix the upstream first. If a reducer is emitting value-equal-but-reference-different objects (the Q5/Q25 anti-pattern), the *right* fix is to stop creating those references — `@ngrx/entity` and Immer do this for you. Custom result-equality is appropriate when the new reference is *unavoidable* (a projector that genuinely must build a derived collection, and where structural comparison is cheaper than the downstream re-render it prevents). The cost to weigh: the equality function now runs on every recompute, so for huge arrays a deep/structural check can cost more than the render it saves — measure before adopting, and prefer narrowing the selector's inputs over custom equality whenever that is possible.

### 🔴 Expert — extended

#### Q111. [Coding] Design and implement a generic "createAsyncResource" SignalStore feature that any feature can use for load/refresh/error/stale-time, mimicking TanStack Query semantics on top of NgRx signals.

**Problem:** Teams keep hand-rolling load/loading/error/refresh for every server read (Q49). Build one reusable `withAsyncResource` feature that encapsulates fetch, status, error, and a stale-time guard so callers write almost nothing.

```typescript
import { computed, inject, ProviderToken } from '@angular/core';
import { signalStoreFeature, withState, withComputed, withMethods, patchState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { pipe, switchMap, tap, of, Observable } from 'rxjs';
import { tapResponse } from '@ngrx/operators';

interface AsyncState<T> {
  data: T | null; loading: boolean; error: string | null; fetchedAt: number | null;
}

export function withAsyncResource<T>(
  fetcher: () => Observable<T>,            // how to load
  opts: { staleMs?: number } = {},
) {
  const staleMs = opts.staleMs ?? 30_000;
  return signalStoreFeature(
    withState<AsyncState<T>>({ data: null, loading: false, error: null, fetchedAt: null }),
    withComputed(({ fetchedAt }) => ({
      isStale: computed(() => fetchedAt() === null || Date.now() - fetchedAt()! > staleMs),
    })),
    withMethods((store) => ({
      // load(): skip the network if data is fresh; force(): always refetch.
      _run: rxMethod<{ force: boolean }>(pipe(
        switchMap(({ force }) => {
          if (!force && !store.isStale()) return of(null);          // fresh cache hit
          patchState(store, { loading: true, error: null });
          return fetcher().pipe(tapResponse({
            next: (data) => patchState(store, { data, loading: false, fetchedAt: Date.now() }),
            error: (e: Error) => patchState(store, { loading: false, error: e.message }),
          }));
        }),
      )),
      load: function () { (this as any)._run({ force: false }); },
      refresh: function () { (this as any)._run({ force: true }); },
    })),
  );
}

// Usage: an entire server-read feature in four lines.
export const DashboardStore = signalStore(
  { providedIn: 'root' },
  withAsyncResource<Metrics>(() => inject(MetricsApi).get(), { staleMs: 10_000 }),
);
// component: store.load();  store.refresh();  vm = store.data;  busy = store.loading;
```

This is the architecturally interesting answer to "should server data live in the store?" (Q49): instead of hand-managing loading/error/data per feature, you encode the *caching policy* once — stale-time, dedup-on-fresh, explicit refresh — and reuse it. The `isStale` computed plus the `force` flag give you the core of what TanStack Query/RTK Query provide (don't refetch fresh data; allow manual invalidation) without pulling in a separate library, and it composes with `withEntities`, `withRequestStatus`, and the rest because it's just a `signalStoreFeature` (Q104).

The expert framing is knowing its limits. This homegrown resource handles single-key caching, but a real query library also does **request deduplication across components, background refetch on focus/reconnect, query-key-based cache invalidation, pagination/infinite-scroll, and garbage collection of unused cache entries** — re-implementing all of that is a project, not a feature, and is exactly the "you've built a worse TanStack Query" trap. So the recommendation I'd give: use `withAsyncResource` for the *long tail* of simple reads where a full query library is overkill, and reach for `@ngrx/data`, RTK Query (React), or TanStack Query when server-state management is a *first-class* concern of the app. The value of building it once is also pedagogical — it makes the team articulate their caching policy explicitly instead of scattering ad-hoc `if (!loaded) load()` checks across components.

#### Q112. [Coding] Implement a state-shape *migration* meta-reducer so a persisted store from app v1 hydrates safely into v3. Show versioned migrations and the fallback.

**Problem:** The store is persisted to `localStorage` (Q21). Across releases the shape changed (v1→v2 renamed a field, v2→v3 added normalization). A returning user has v1 data on disk. Migrate it forward or discard it — never crash.

```typescript
import { ActionReducer, INIT, UPDATE } from '@ngrx/store';

const STORAGE_KEY = 'app_state';
const CURRENT_VERSION = 3;

type Migration = (state: any) => any;
const migrations: Record<number, Migration> = {
  // 1 -> 2: rename `username` to `displayName`
  1: (s) => ({ ...s, user: { ...s.user, displayName: s.user.username, username: undefined }, _v: 2 }),
  // 2 -> 3: normalize the `todos` array into { ids, entities }
  2: (s) => {
    const arr: any[] = s.todos ?? [];
    const entities = Object.fromEntries(arr.map((t) => [t.id, t]));
    return { ...s, todos: { ids: arr.map((t) => t.id), entities }, _v: 3 };
  },
};

function migrate(state: any): any | undefined {
  let v: number = state?._v ?? 1;
  let current = state;
  try {
    while (v < CURRENT_VERSION) {
      const step = migrations[v];
      if (!step) return undefined;            // no path → discard, start clean
      current = step(current);
      v = current._v;
    }
    return current;
  } catch {
    return undefined;                         // any migration error → discard, never crash
  }
}

export function persistenceMetaReducer(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action) => {
    if ((action.type === INIT || action.type === UPDATE) && !state) {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        try { state = migrate(JSON.parse(raw)); }      // migrate may return undefined → fresh init
        catch { state = undefined; }
      }
    }
    const next = reducer(state, action);
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...next, _v: CURRENT_VERSION }));
    return next;
  };
}
```

The non-negotiable principle is that **persisted state is untrusted input from a past version of your own code**, and it is the single most common cause of the "blank screen after deploy" incident (Q79): v3 selectors assume `state.todos.entities`, the user has a v1 array on disk, a selector dereferences `undefined`, and the app white-screens for exactly the users loyal enough to have old data. The migration chain solves this by running ordered, single-step transforms (`1→2→3`) keyed by a `_v` stamp, so any starting version walks forward to current.

The two design decisions that make it *safe* rather than merely clever: every fallible path (`JSON.parse`, a missing migration, a throwing migration) returns `undefined`, which makes every feature reducer fall back to its `initialState` — a clean, correct app rather than a crash. And migrations are append-only and never edited once shipped, because they run against data written by old binaries that you can no longer change. The alternative cheaper strategy — bump `STORAGE_KEY` on every breaking change to invalidate old data wholesale — is valid when the persisted slice is disposable (UI preferences), but for slices users care about (drafts, offline edits) forward-migration preserves their data, which is the whole point of persisting it. I'd also cap and validate the parsed object's size/shape before trusting it, since `localStorage` is attacker-writable under XSS.

#### Q113. [Coding] Demonstrate a subtle effect bug: an effect dispatches an action it also listens to, causing an infinite loop. Show the reproduction, how DevTools reveals it, and three correct fixes.

**Problem:** An effect normalizes data on `dataReceived` and dispatches `dataReceived` with the normalized payload "to reuse the reducer." It loops forever. Reproduce and fix.

```typescript
// THE BUG: ofType and the dispatched action are the SAME type → self-feeding loop.
buggy$ = createEffect(() =>
  this.actions$.pipe(
    ofType(DataActions.dataReceived),
    map(({ raw }) => DataActions.dataReceived({ raw: normalize(raw) })),   // re-dispatches itself!
  ),
);
// DevTools timeline: dataReceived, dataReceived, dataReceived… thousands/sec, tab freezes.
```

```typescript
// FIX 1 — distinct action types (the right design): listen to one, emit another.
fixed1$ = createEffect(() =>
  this.actions$.pipe(
    ofType(DataActions.dataReceived),                       // trigger
    map(({ raw }) => DataActions.dataNormalized({ data: normalize(raw) })),  // different type
  ),
);

// FIX 2 — don't use an effect at all: normalization is PURE, so do it in the reducer.
on(DataActions.dataReceived, (s, { raw }) => ({ ...s, data: normalize(raw) }));

// FIX 3 — guard against re-entry when types genuinely must match (rare): only act on un-normalized.
fixed3$ = createEffect(() =>
  this.actions$.pipe(
    ofType(DataActions.dataReceived),
    filter(({ normalized }) => !normalized),                // skip already-processed payloads
    map(({ raw }) => DataActions.dataReceived({ raw: normalize(raw), normalized: true })),
  ),
);
```

The loop arises because NgRx effects subscribe to the *global* action stream and re-emit into it: any effect whose `ofType` includes a type it can also dispatch is a feedback loop, and because dispatch is synchronous-ish and unbounded it freezes the tab in milliseconds. DevTools makes the diagnosis instant — the timeline shows the same action type repeating thousands of times per second, and with `trace: true` you can see every emission originates from the same effect. This is the recursion branch of the Q63 checklist and the Q24 "effect orchestration sprawl" failure mode in its purest form.

The ranked fixes encode a design principle. **Fix 1** (distinct trigger and result action types) is almost always correct and reflects the canonical NgRx grammar: effects map *cause* actions to *effect* actions. **Fix 2** is the deeper insight — if the work is *pure* (normalization, derivation), it never belonged in an effect at all; reducers transform state, effects do I/O (Q108). Reaching for an effect to "reuse the reducer" is the smell that produced the bug. **Fix 3** (a re-entry guard via a flag on the payload) is a last resort for the rare case where the same action type is genuinely required (e.g. a recursive workflow), and it must be paired with a hard recursion bound in code review, because a guard that's even slightly wrong reintroduces the freeze. The meta-lesson: the existence of this bug usually means an effect is doing a reducer's job.

#### Q114. [Behavioral] (STAR) Tell me about a time you led a state-management decision that you later discovered was wrong, and how you handled the reversal.

**Situation.** At a previous company I was the staff engineer who'd championed adopting NgRx as the default for a new B2B analytics product. Six months in, with eight engineers building features, our sprint velocity had quietly cratered and two engineers had privately told me they dreaded touching state. The Redux DevTools timeline was dominated by UI-noise actions, and a "simple" filter-panel feature had taken a senior engineer three days largely fighting store boilerplate.

**Task.** As the person who'd made the original call, I had two jobs that were in tension: honestly diagnose whether my decision was wrong, and — if it was — lead a correction without either defending my ego or whiplashing the team into a second expensive migration. I owned both the technical reversal and the trust cost of having steered the team into the problem.

**Action.** I started by gathering evidence rather than opinions: I categorized two weeks of DevTools action logs (roughly 70% were transient UI state that never needed to be global), timed three recent features against their store-boilerplate overhead, and ran a short retro where I explicitly invited criticism of *my* original decision. The data was unambiguous — we'd globalized state that should have been local. I wrote an ADR superseding my earlier one, with a decision rubric (shared? long-lived? needs replay? — the Q14/Q92 rule) and a clear default: Signals/ComponentStore for local UI state, NgRx reserved for genuinely shared domain state. Crucially, I did *not* mandate a big-bang rewrite. We adopted the rubric for all *new* state immediately, and migrated existing features opportunistically behind a facade boundary (Q22) only when we were already touching them, so the correction rode along with normal work instead of becoming a project. I also publicly named that the original call was mine and wrong, because the team needed to see that reversing a decision on evidence was safe behavior, not a failure.

**Result.** Within two sprints, new-feature state was almost entirely local and velocity recovered measurably — the filter-panel-style features dropped from days to hours. Action volume in DevTools fell enough that it became a usable debugging tool again. The less obvious result mattered more: by owning the reversal openly and grounding it in data, the team became markedly more willing to challenge architectural defaults, including mine, which is exactly the culture you want. The lasting lesson I carry into every state decision now is that *the cost of a global store is concentrated and easy to underweight at the start*, so the right default is to make the store earn each piece of state — and that a leader's willingness to reverse their own call on evidence is worth more than the appearance of having been right.

#### Q115. [Coding] Implement a selector that joins three entity collections efficiently (orders × customers × products) and explain why naive composition tanks a dashboard. Then give the fix.

**Problem:** A dashboard row needs the order plus its customer name and each line's product name. A naive selector recomputes the full join on every unrelated dispatch (Q88). Build the layered, memoized version.

```typescript
import { createSelector } from '@ngrx/store';

// Layer 1: raw dictionaries (cheap selects; change only when their slice changes).
const selectOrderEntities    = orderAdapter.getSelectors(selectOrdersFeature).selectEntities;
const selectCustomerEntities = custAdapter.getSelectors(selectCustomersFeature).selectEntities;
const selectProductEntities  = prodAdapter.getSelectors(selectProductsFeature).selectEntities;

// Layer 2: the join. Memoized on the THREE dictionaries — recomputes only when one changes,
// NOT on every dispatch. Build a view-model array keyed for the grid.
export const selectOrderRows = createSelector(
  selectOrderEntities, selectCustomerEntities, selectProductEntities,
  (orders, customers, products) =>
    Object.values(orders).map((o) => ({
      id: o!.id,
      total: o!.total,
      customerName: customers[o!.customerId]?.name ?? '—',
      lines: o!.lineItemIds.map((pid) => products[pid]?.name ?? '—'),
    })),
);

// Layer 3 (optional): per-row factory so a single expanded row recomputes alone.
export const selectOrderRow = (id: string) =>
  createSelector(selectOrderRows, (rows) => rows.find((r) => r.id === id));
```

The naive failure has two compounding causes. First, if the join is written against `selectAll` arrays or rebuilds intermediate arrays, any dispatch that produces a new array reference upstream invalidates the whole join even when the joined data is unchanged. Second, a single monolithic selector that maps *all* orders recomputes the entire join whenever *any* of the three slices changes — so editing one product re-derives every row of a 5,000-row grid, blowing the frame budget (Q88). On a busy dashboard with websocket updates this fires constantly.

The layered fix isolates change: selecting the *entity dictionaries* (not arrays) as inputs means the join's memoization holds across all dispatches that don't touch those three slices, and dictionaries change reference only when their slice actually mutates. The optional per-row factory (Layer 3) lets an expanded-detail view depend on one row's derivation rather than the whole array. If the join itself is still too expensive at scale, the next levers are: push the join server-side (return a denormalized read model — often the right call for a reporting dashboard, Q49), virtualize the grid so only visible rows are projected, or pre-index (e.g. a `Map` of customerId→orders) inside a memoized selector so each recompute is O(n) not O(n·m). The principle throughout: *memoize on the narrowest stable inputs, and don't derive what isn't visible.*

#### Q116. [Coding] Write a custom RxJS operator/effect helper that adds exponential backoff with jitter and a circuit-breaker to any HTTP effect. Show it applied.

**Problem:** Multiple effects need resilient retries. Build a reusable operator that retries with exponential backoff + jitter, gives up after N attempts, and trips a circuit breaker after repeated failures so a struggling backend isn't hammered.

```typescript
import { Observable, throwError, timer } from 'rxjs';
import { retry, tap } from 'rxjs/operators';

// Shared circuit-breaker state per endpoint key.
const breakers = new Map<string, { failures: number; openUntil: number }>();

export function resilient<T>(key: string, opts = { max: 4, baseMs: 300, threshold: 5, coolDownMs: 30_000 }) {
  return (source$: Observable<T>): Observable<T> => {
    const b = breakers.get(key) ?? { failures: 0, openUntil: 0 };
    breakers.set(key, b);

    return new Observable<T>((sub) => {
      if (Date.now() < b.openUntil) {                        // breaker OPEN → fail fast, no call
        sub.error(new Error(`circuit-open:${key}`));
        return;
      }
      return source$.pipe(
        retry({
          count: opts.max,
          delay: (err, attempt) => {
            if (err?.status && err.status < 500) return throwError(() => err); // don't retry 4xx
            const backoff = opts.baseMs * 2 ** (attempt - 1);                  // 300,600,1200…
            const jitter = Math.random() * backoff;                            // full jitter
            return timer(backoff + jitter);
          },
        }),
        tap({
          next: () => { b.failures = 0; b.openUntil = 0; },                    // success resets
          error: () => {
            if (++b.failures >= opts.threshold) b.openUntil = Date.now() + opts.coolDownMs; // trip
          },
        }),
      ).subscribe(sub);
    });
  };
}

// Applied inside an effect — placement is INSIDE the per-request inner observable:
load$ = createEffect(() => this.actions$.pipe(
  ofType(A.load),
  switchMap(() => this.api.get().pipe(
    resilient('metrics'),
    map((data) => A.loadSuccess({ data })),
    catchError((e) => of(A.loadFailure({ error: e.message }))),   // breaker-open also lands here
  )),
));
```

Three resilience ideas combine here. **Exponential backoff** (`base · 2^attempt`) spaces retries so a momentarily overloaded backend gets breathing room instead of a synchronized retry storm. **Jitter** (randomizing the delay) is the part teams omit and regret: without it, many clients that failed at the same instant retry at the same instant, re-creating the thundering herd the backoff was meant to prevent — full jitter decorrelates them. The **circuit breaker** is the macro-level protection: after N consecutive failures it "opens" and fails fast for a cooldown window, so a down dependency produces instant local failures (good UX, no hanging spinners) instead of every user's app retrying into a dead service and amplifying the outage.

The operator-placement detail is the same correctness rule as everywhere in NgRx effects: `resilient()` and `catchError` live *inside* the per-request `switchMap`, so retries and breaker logic scope to the individual call and a final failure becomes a `loadFailure` action without killing the outer effect (Q8/Q69). The trade-offs to state: the shared `breakers` map is module-global mutable state (deliberate, since a breaker is inherently cross-request), which means it's effectively a singleton and must be reset in tests; non-idempotent writes should *not* be retried blindly (a retried "charge card" can double-charge — gate retries to idempotent GETs or use idempotency keys); and `4xx` errors are explicitly not retried because they won't succeed on repeat. In a large app I'd push this concern down to an `HttpInterceptor` so it's uniform, and reserve the per-effect operator for cases needing per-call tuning.

#### Q117. [Coding] Implement a meta-reducer that records every action with timing and a redacted diff, then explain how you'd ship the captured trace with a bug report. Bound its overhead.

**Problem:** Support needs to reproduce user-reported bugs. Build a ring-buffer meta-reducer that captures the last N actions with timing and a shallow redacted state diff, exportable as a bug-report attachment — without leaking PII or degrading runtime.

```typescript
import { ActionReducer, MetaReducer } from '@ngrx/store';

interface TraceEntry { type: string; ms: number; changedKeys: string[]; at: number; }
const RING_SIZE = 100;
const ring: TraceEntry[] = [];
const SENSITIVE = new Set(['auth', 'user']);          // slices whose diffs we don't detail

export function traceMetaReducer(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action) => {
    const t0 = performance.now();
    const next = reducer(state, action);
    const ms = performance.now() - t0;

    // Shallow top-level diff only — O(top-level keys), not a deep walk.
    const changedKeys = state
      ? Object.keys(next).filter((k) => next[k] !== state[k])
              .map((k) => (SENSITIVE.has(k) ? `${k}(redacted)` : k))
      : Object.keys(next);

    ring.push({ type: action.type, ms: +ms.toFixed(2), changedKeys, at: Date.now() });
    if (ring.length > RING_SIZE) ring.shift();         // bounded memory
    return next;
  };
}

// Exposed for the "Report a bug" button — attach to the ticket, NEVER includes raw payloads/state.
export function exportTrace(): string {
  return JSON.stringify({ capturedAt: Date.now(), entries: ring }, null, 2);
}
```

The design balances diagnostic value against the two ways action-tracing goes wrong: overhead and leakage. Overhead is bounded by (a) a fixed-size **ring buffer** so memory never grows, and (b) a **shallow top-level diff** using the reference inequality that immutable reducers already guarantee — `next[k] !== state[k]` is O(number of top-level slices) and exploits the very immutability NgRx enforces, so it's nearly free, versus a deep structural diff which would itself become the performance problem on a busy store. Capturing *changed slice keys* plus *per-action timing* turns out to be enough to reconstruct most bugs (which actions ran, in what order, which slices they touched, and which action was slow) without recording payloads at all.

Leakage is handled by recording only *metadata* — action types, changed-key names, durations — and never raw payloads or state values, with sensitive slices reduced to a redacted marker. That makes the export safe to attach to a support ticket by default, which is the whole point: a user clicks "report a bug," the last 100 action types/timings/changed-slices travel with the ticket, and an engineer can replay the *sequence* against a test fixture. This complements DevTools sanitizers (Q103) and the production-observability design (Q82): DevTools is for live debugging, this trace is for *after-the-fact* reproduction from the field. If full payloads are genuinely needed to reproduce, that's a deliberate, consented, encrypted capture — not the default — because once raw payloads enter a trace you've recreated every PII and secret-leakage risk from Q20 in your ticketing system.

#### Q118. [Coding] Show how to test that a feature's *public selector contract* is stable, so a refactor of the internal state shape can't silently break consumers. Write the contract test.

**Problem:** A team wants freedom to refactor a feature's internal state shape (flatten, normalize, rename) without breaking the components that read it. Encode the boundary as a contract test (Q90).

```typescript
import { createReducer, on } from '@ngrx/store';

// The PUBLIC contract: consumers only ever use these selectors and these action creators.
// Internal shape (entities? array? nested?) is NOT part of the contract.
describe('users feature contract', () => {
  // Drive the feature only through PUBLIC actions; assert only PUBLIC selectors.
  function reduceAll(actions: any[]) {
    return actions.reduce((s, a) => usersReducer(s, a), usersReducer(undefined, { type: '@@init' }));
  }

  it('selectAllUsers returns added users in a stable order', () => {
    const state = reduceAll([
      UsersActions.addUser({ user: { id: '2', name: 'Bob' } }),
      UsersActions.addUser({ user: { id: '1', name: 'Ada' } }),
    ]);
    // Assert via the PUBLIC selector's .projector against the PUBLIC slice — never reach into shape.
    expect(selectAllUsers.projector(state).map((u) => u.name)).toEqual(['Ada', 'Bob']); // sorted
  });

  it('selectUserById returns undefined for a missing id (documented contract)', () => {
    const state = reduceAll([]);
    expect(selectUserById('nope').projector(state)).toBeUndefined();
  });

  it('selectUnreadCount counts unread only', () => {
    const state = reduceAll([
      UsersActions.addUser({ user: { id: '1', name: 'A', read: false } }),
      UsersActions.markRead({ id: '1' }),
    ]);
    expect(selectUnreadCount.projector(state)).toBe(0);
  });
});
```

The key discipline is that the test **drives the feature through public actions and asserts through public selectors only** — it never constructs the internal state object literally or reaches into `state.entities` / `state.ids`. That is precisely what makes it a *contract* test rather than an implementation test: it pins the observable behavior (given these actions, these selectors return these values) while leaving the team free to change *how* that behavior is implemented. If a developer later normalizes the slice, renames a field, or swaps in `@ngrx/entity`, these tests keep passing as long as the public selectors still return the same shapes — and they fail loudly the moment a refactor would have silently broken a consumer (e.g. `selectAllUsers` losing its sort, or `selectUserById` starting to throw instead of returning `undefined`).

This is the testing analogue of the facade boundary (Q11/Q109): the facade enforces the contract at runtime/DI, the contract test enforces it in CI. The two together let a feature evolve its internals confidently. The pitfalls to call out: tests that *build the state object by hand* (`{ entities: {...} }`) are anti-contract — they lock in the shape and break on every refactor, defeating the purpose, which is why driving through actions is mandatory. And the contract must explicitly cover the *edge* behaviors consumers rely on — missing-id returns `undefined`, empty-state returns `[]` not `null`, ordering guarantees — because those are exactly the implicit assumptions a shape refactor tends to violate. A contract that only tests the happy path gives false confidence.

#### Q119. [Coding] Implement a zoneless-compatible bridge so a legacy RxJS-observable store and a new SignalStore share one source of truth during migration. Show both directions.

**Problem:** Mid-migration (Q22), some components read the old `Store` (Observables) and some read the new SignalStore (Signals), but both must reflect the *same* selected-theme value. Build the two-way bridge and make it work under zoneless change detection.

```typescript
import { Injectable, inject, effect } from '@angular/core';
import { Store } from '@ngrx/store';
import { toSignal, toObservable } from '@angular/core/rxjs-interop';
import { signalStore, withState, withMethods, patchState, getState } from '@ngrx/signals';

// New SignalStore owns theme going forward.
export const ThemeStore = signalStore(
  { providedIn: 'root' },
  withState({ theme: 'light' as 'light' | 'dark' }),
  withMethods((store) => ({ setTheme: (theme: 'light' | 'dark') => patchState(store, { theme }) })),
);

@Injectable({ providedIn: 'root' })
export class ThemeBridge {
  private legacyStore = inject(Store);
  private signalStore = inject(ThemeStore);

  constructor() {
    // Direction 1: legacy Store selector -> Signal (old code dispatches, new code sees it).
    const legacyTheme = toSignal(this.legacyStore.select(selectLegacyTheme), { requireSync: true });
    effect(() => {
      const t = legacyTheme();
      if (t !== this.signalStore.theme()) this.signalStore.setTheme(t);
    });

    // Direction 2: Signal -> legacy Store (new code mutates, old code sees it).
    toObservable(this.signalStore.theme).subscribe((theme) => {
      this.legacyStore.dispatch(ThemeActions.setTheme({ theme }));
    });
  }
}
```

The bridge uses Angular's `rxjs-interop` as the bidirectional adapter: `toSignal` turns the legacy Observable selector into a Signal the new world can read, and `toObservable` turns the SignalStore's signal into an Observable the legacy world can dispatch from. The guards (`if (t !== current)` and dispatching a *distinct* set-theme action) are the same echo-loop prevention as the cross-tab case (Q101): without them, Direction 1 setting the signal would trigger Direction 2 dispatching, which would re-emit the selector and re-trigger Direction 1 — an infinite ping-pong. The reference-equality check breaks the cycle because once both sides hold the same value, neither propagation does anything.

Two correctness points matter under **zoneless** change detection, which is the realistic target for a 2026 migration. First, `toSignal`/`toObservable` are the *sanctioned* interop primitives precisely because they integrate with the reactive graph rather than relying on Zone.js to notice changes — under zoneless, a hand-rolled `subscribe(() => this.x = …)` would update a field that the template never re-checks, whereas a Signal write schedules CD correctly. Second, this bridge is intentionally *temporary scaffolding*: it makes two sources of truth *behave* as one during the transition, but two-way sync is inherently fragile (ordering, the echo guards, double-write storms), so the migration plan must include *deleting* the bridge once the legacy store no longer owns theme — leaving a permanent two-way bridge in place is a worse state than either store alone. I'd gate its removal on a grep proving no component still reads `selectLegacyTheme`.

#### Q120. [Coding] Implement a SignalStore `withComputed` that depends on *another injected store*, and explain the dependency/equality subtleties that cause stale or over-eager recomputation.

**Problem:** A `CartStore` needs a `discountedTotal` computed that depends on both its own items and a `UserStore`'s membership tier. Wire the cross-store computed correctly and explain the reactivity pitfalls.

```typescript
import { computed, inject } from '@angular/core';
import { signalStore, withState, withComputed, withMethods, patchState } from '@ngrx/signals';

export const CartStore = signalStore(
  { providedIn: 'root' },
  withState({ items: [] as { price: number }[] }),
  // withComputed can inject OTHER stores; the computed tracks signals from both.
  withComputed((store, userStore = inject(UserStore)) => ({
    subtotal: computed(() => store.items().reduce((a, i) => a + i.price, 0)),
    discountedTotal: computed(() => {
      const subtotal = store.items().reduce((a, i) => a + i.price, 0);
      const rate = userStore.tier() === 'gold' ? 0.2 : userStore.tier() === 'silver' ? 0.1 : 0;
      return +(subtotal * (1 - rate)).toFixed(2);     // recomputes when items() OR tier() changes
    }),
  })),
);
```

Cross-store computed values work because Angular signals track dependencies *dynamically at read time*: every signal `.read()` executed during the computed's body is registered as a dependency, so reading both `store.items()` and `userStore.tier()` makes the computed re-run when *either* changes — no manual subscription, no `combineLatest`. Injecting another store inside `withComputed` is supported because the feature factory runs in an injection context. This is dramatically simpler than the classic-Store equivalent (a `createSelector` would require both slices to live in the same store, or a cross-feature selector with all the lazy-loading caveats of Q70/Q75).

The subtleties that cause real bugs: **(1) Conditional reads create conditional dependencies.** If a computed reads `userStore.tier()` only inside an `if` branch, then when that branch isn't taken the computed *doesn't depend on tier this run*, and a later tier change won't recompute it until something else does — a classic stale-value trap. Read the signals you depend on unconditionally (or `untracked()` deliberately for ones you explicitly *don't* want to track). **(2) Equality controls over-eager recompute downstream.** `computed` uses `Object.is` by default, so if `discountedTotal` returned a new *object* each run it would notify consumers even when the number is unchanged; returning a primitive (as here) or supplying an `equal` fn prevents needless downstream renders — the same reference-stability discipline as classic selectors (Q25). **(3) Initialization order / circular deps.** Two stores whose computeds inject each other create a cycle that throws or produces `undefined` reads; cross-store dependencies must form a DAG, with the "lower" store (UserStore) unaware of the "higher" one (CartStore). Get those three right and cross-store derived state is both simpler and more robust than the Observable-era equivalent; get them wrong and you have intermittent staleness that's maddening to reproduce.

#### Q121. [Coding] A burst of 50 fine-grained actions from a single user gesture causes 50 change-detection passes and jank. Implement action batching that collapses them into one state notification. Show two approaches.

**Problem:** Importing a CSV dispatches `addRow` 50 times in a tight loop; each dispatch runs reducers and notifies subscribers, so the view re-renders 50 times in one frame. Reconcile the "dispatch many fine-grained actions" guidance (Q87) with this performance cliff.

```typescript
// --- Approach A: a single batch action carrying the whole payload (preferred) ---
// Don't dispatch 50 addRow; dispatch one addRows and let the adapter add them at once.
export const addRows = createAction('[Import] Add Rows', props<{ rows: Row[] }>());

on(addRows, (state, { rows }) => rowAdapter.addMany(rows, state));   // ONE reducer pass, ONE notify

// component:
// this.store.dispatch(addRows({ rows: parsedRows }));   // not a 50-iteration loop

// --- Approach B: a generic batching meta-reducer for when callers can't be changed ---
import { ActionReducer } from '@ngrx/store';

export const BATCH = '[Batch] Run';
export const batch = (actions: Action[]) => ({ type: BATCH, actions });

export function batchMetaReducer(reducer: ActionReducer<any>): ActionReducer<any> {
  return (state, action: any) => {
    if (action.type === BATCH) {
      // Fold every inner action through the reducer, emitting only the FINAL state once.
      return action.actions.reduce((acc: any, inner: Action) => reducer(acc, inner), state);
    }
    return reducer(state, action);
  };
}
// dispatch(batch([addRow(r1), addRow(r2), /* … */ addRow(r50)]))  → one notification
```

The root cause is that NgRx notifies subscribers **synchronously per dispatch**, so 50 dispatches mean 50 selector runs and (under zone-based CD) up to 50 change-detection cycles within one user gesture. Approach A is almost always the right fix and the one I'd push in review: model the *intent* correctly. A CSV import is semantically "add these rows," a single event, so it should be a single action with a batch payload and an adapter `addMany` — that preserves the event-style action model (the action still describes *what happened*) while collapsing to one reducer pass and one notification. The Q87 guidance to dispatch fine-grained actions never meant "dispatch the same action in a loop"; a loop is a code smell that an event is being mis-modeled as N events.

Approach B (a batching meta-reducer, the pattern `@ngrx/store` shipped historically and libraries like `ngrx-batch` provide) is the escape hatch for when the dispatchers are out of your control — third-party code, generated actions, or a refactor too large to do now. It wraps N real actions in one `BATCH` action, folds them through the reducer, and emits the final state a single time. The trade-offs to flag: batching muddies the DevTools timeline (the inner actions are hidden inside one entry unless you teach DevTools to expand them) and can hide ordering bugs, so it's a performance band-aid, not an architecture. Under **zoneless/signal** change detection the pressure is lower (CD is decoupled from dispatch and coalesced), but the *selector* recomputation cost per dispatch remains, so collapsing to one action is still the cleaner answer. The decision rule: fix the action modeling (A) whenever you own the dispatch site; reach for the batching meta-reducer (B) only when you genuinely can't.

#### Q122. [Coding] Write a micro-benchmark harness that proves a selector refactor (array-scan vs entity-dictionary lookup) actually improved performance, and discuss how to benchmark NgRx selectors honestly.

**Problem:** You refactored `selectUserById` from an `array.find` over `selectAll` to an O(1) dictionary lookup and claim it's faster. Prove it with a repeatable benchmark, and avoid the common ways selector benchmarks lie.

```typescript
// bench/selector.bench.ts — run with ts-node / vitest bench; isolate the projector logic.
import { performance } from 'node:perf_hooks';

function makeUsers(n: number) {
  const arr = Array.from({ length: n }, (_, i) => ({ id: String(i), name: `u${i}` }));
  const entities = Object.fromEntries(arr.map((u) => [u.id, u]));
  return { arr, entities };
}

function bench(label: string, fn: () => void, iters = 100_000) {
  fn(); fn(); fn();                                   // warm up the JIT before timing
  const t0 = performance.now();
  for (let i = 0; i < iters; i++) fn();
  const ms = performance.now() - t0;
  console.log(`${label}: ${(ms / iters * 1000).toFixed(3)} µs/op  (${iters} ops)`);
}

const N = 10_000;
const { arr, entities } = makeUsers(N);
const targetId = String(N - 1);                        // worst case for array.find: last element

// OLD: O(n) scan via selectAll projector.
bench('array.find (O(n))',   () => { arr.find((u) => u.id === targetId); });
// NEW: O(1) dictionary lookup via selectEntities projector.
bench('entities[id] (O(1))', () => { const _ = entities[targetId]; });

// Typical output (illustrative):
// array.find (O(n)):   ~4.200 µs/op
// entities[id] (O(1)): ~0.005 µs/op   → ~800x on the worst-case lookup at N=10k
```

Benchmarking selectors *honestly* means measuring the **projector logic in isolation**, not a full store. NgRx selectors are pure functions of their inputs, so you call `.projector(...)` (or, as here, extract the equivalent logic) with synthetic data and time many iterations — this removes change-detection, subscription, and DI noise that would otherwise dominate and mask the algorithmic difference you're trying to demonstrate. The three things that make such a benchmark trustworthy: **warm-up runs** before timing (V8's JIT needs iterations to optimize; timing cold gives garbage numbers), **a realistic worst case** (benchmarking `array.find` on the *first* element would falsely show parity — you must probe the last/missing element where O(n) actually hurts), and **per-op normalization** (report µs/op at a stated N, not total wall time, so the result is comparable across runs and machines).

The expert nuance is knowing *when the micro-benchmark is irrelevant*. A selector that's algorithmically O(n) but runs once per second on 200 items will never show up in a flame graph — the honest engineer benchmarks to *decide* (is this refactor worth doing?) and profiles the *real app* to *confirm the user-visible win* (Q86/Q88). A µs-level selector improvement is meaningless if the actual jank came from the projector returning a new array reference every call (causing re-renders, Q5) or from a missing `OnPush`; those are *correctness/reference* problems that a speed micro-benchmark won't surface at all. So the discipline is layered: micro-benchmark to validate the *algorithmic* claim cheaply and repeatably, then confirm with Angular DevTools / the Performance panel that selector recomputation and renders actually dropped in the running app under a representative interaction. Reporting only the 800x micro-number without the in-app profile is how teams "optimize" code paths that were never the bottleneck.

## ✅ Key Takeaways

- The Redux pattern buys **predictability and traceability** (single store, actions, pure reducers, immutability) at the cost of **ceremony** — use it where the payoff is real.
- NgRx maps Redux onto Angular/RxJS: **Store, Actions, Reducers, Selectors, Effects, Entity**; reducers stay pure, **side effects live in Effects**, derivations live in **memoized selectors**.
- **Choose flattening operators deliberately:** `switchMap` (cancel/latest), `concatMap` (ordered writes), `mergeMap` (parallel), `exhaustMap` (ignore-while-busy).
- **Global vs local is the central decision:** store global state only when it is *shared, long-lived, or needs replay/persistence*; everything else belongs in Signals, component fields, or ComponentStore.
- **SignalStore (NgRx 17+)** is the Signal-first future — low boilerplate, optional `rxMethod` for RxJS interop, `withEntities` for collections; **ComponentStore** remains great for RxJS-flavored local state.
- Across ecosystems: **RTK** (React, Immer + RTK Query), **Zustand** (minimal React), **Pinia** (Vue) — NgRx is the most structured; structure scales with team and app size.
- **Memoized selectors** eliminate *redundant* recomputation only when upstream references are stable and cache cardinality fits (use **factories** for parameterized selectors).
- Test by layer: reducers (pure state math), selectors (`.projector`), effects (`provideMockActions` + marbles).
- Treat the store as a **security-sensitive, untrusted** mirror: enforce authz server-side, keep secrets out, reset on logout.

## ⚠️ Common Pitfalls

- **Globalizing UI state** (toggles, form drafts, scroll position) — the #1 over-engineering smell; bloats DevTools and onboarding.
- **Mutating state in reducers** — breaks reference equality, memoization, and OnPush; always return new objects (or use Immer/Entity).
- **Letting an effect's outer stream die** — putting `catchError` *outside* `switchMap`/`mergeMap` silently kills the effect after the first error.
- **Parameterized selectors without factories** — single-slot cache thrashes and recomputes every alternate call.
- **Creating new references when nothing changed** (`[...arr]` blindly) — invalidates all downstream selectors and re-renders.
- **Treating client state as a security boundary** — hiding UI is not authorization; the server must re-check every mutation.
- **Persisting secrets/PII to `localStorage`** via hydration meta-reducers — XSS and compliance exposure.
- **Facade-as-god-service** — facades that grow into everything re-introduce the indirection Redux was meant to remove.
- **Hand-managing server state in the store** — loading/error/data boilerplate that RTK Query / TanStack Query / Apollo solve for free.
- **Effect feedback loops** — an effect dispatching an action that re-triggers itself; watch action volume in DevTools.

## 📚 Further Reading

- **NgRx Official Documentation** — `ngrx.io` — Store, Effects, Entity, ComponentStore, and the `@ngrx/signals` SignalStore guides (current through NgRx 18/19).
- **Redux Documentation & "Redux Essentials"** — `redux.js.org` — the canonical explanation of the pattern and Redux Toolkit best practices.
- *Architecting Angular Applications with Redux, RxJS, and NgRx* — Christoffer Noring — patterns for state at Angular scale.
- **Angular Signals Guide** — `angular.dev/guide/signals` — fine-grained reactivity, `computed`, `effect`, `linkedSignal`, and Signal interop.
- **Pinia Docs** (`pinia.vuejs.org`) and **Zustand Docs** (`github.com/pmndrs/zustand`) — for cross-framework comparison of store ergonomics.
- **XState Documentation** — `stately.ai/docs` — modeling complex async workflows as explicit, visualizable state machines alongside or instead of effects.
