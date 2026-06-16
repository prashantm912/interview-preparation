# ExtJS (Sencha) — Interview Preparation Guide

ExtJS is a comprehensive JavaScript framework from Sencha for building data-intensive, desktop-style enterprise web applications. It bundles a class system, a rich component/widget library, a data package, and layout engine into one cohesive (and heavy) stack — still common in finance, telecom, government, and internal back-office tools.

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

### Q1. [Theory] What is ExtJS and what problem does it solve?

ExtJS is a full-stack front-end JavaScript framework (not just a UI library) maintained by Sencha (now part of IDERA). Unlike React or Vue, which deliberately ship only a view layer and let you assemble the rest, ExtJS ships *everything* in one box: a class/inheritance system, hundreds of pre-built UI widgets (grids, trees, charts, forms, windows), a layout engine, a data/ORM package, and theming via SASS. Its value proposition is that a team can build a complex, desktop-like single-page application — think a trading dashboard or an admin console with sortable, editable, virtually-scrolled grids — with minimal third-party glue. The trade-off is weight (large bundle), a steep proprietary learning curve, and a commercial license. It excels where data density and built-in component richness matter more than bundle size or a modern reactive ecosystem.

### Q2. [Theory] What is `Ext.define` and why doesn't ExtJS use native JavaScript `class`?

`Ext.define` is the cornerstone of the ExtJS class system. It was created in the ES5 era (pre-2015) when JavaScript had no native `class`, modules, or standardized inheritance. It provides single inheritance via `extend`, multiple inheritance via `mixins`, dependency declaration via `requires`, configuration via `config` (auto-generating getters/setters), and lifecycle hooks. ExtJS retains it for backward compatibility and because the framework's dynamic loader, config system, and `Ext.create` factory all depend on it. You *can* mix in ES6 syntax in ExtJS 6.5+/7.x, but the framework's own components are all defined with `Ext.define`.

```javascript
Ext.define('MyApp.model.User', {
    extend: 'Ext.data.Model',
    requires: ['Ext.data.field.Integer'],
    fields: [
        { name: 'id',   type: 'int' },
        { name: 'name', type: 'string' },
        { name: 'age',  type: 'int' }
    ]
});

// Instantiate via the factory (preferred over `new`)
var user = Ext.create('MyApp.model.User', { id: 1, name: 'Ada', age: 30 });
```

### Q3. [Theory] What is the difference between a Component and a Container?

A `Component` (`Ext.Component`) is the base building block — anything that renders to the DOM and participates in the component lifecycle (a button, a label, a text field). A `Container` (`Ext.container.Container`, which itself extends Component) is a Component that can *hold and lay out other components* via its `items` array and a `layout` configuration. Panels, windows, tab panels, and viewports are all containers. The mental model is a tree:

```
Viewport (Container, layout: 'border')
├── Panel  (region: 'north')
├── Panel  (region: 'west',   layout: 'accordion')
│   ├── TreePanel
│   └── FormPanel
└── GridPanel (region: 'center')
```

The container's `layout` decides *how* its child `items` are sized and positioned (the children don't manage their own placement).

### Q4. [Practical] How do you create a simple panel with a button and handle a click?

```javascript
Ext.create('Ext.panel.Panel', {
    title: 'Hello ExtJS',
    width: 300,
    height: 150,
    renderTo: Ext.getBody(),      // attach to <body>
    items: [{
        xtype: 'button',          // lazy instantiation via xtype
        text: 'Click me',
        handler: function (btn) {
            Ext.Msg.alert('Clicked', 'Button text: ' + btn.getText());
        }
    }]
});
```

`xtype` is a key concept: instead of `Ext.create(...)` for every child, you declare a string alias (`'button'`, `'grid'`, `'textfield'`) and ExtJS lazily instantiates the component only when the container renders. This is more memory-efficient and is the idiomatic way to declare nested config.

### Q5. [Theory] What is a Store and how does it relate to a Model and Proxy?

These three form the ExtJS data package — its client-side ORM:

- **Model** — defines the schema/shape of one record (fields, types, validations, associations).
- **Store** — an in-memory collection of Model instances; supports sorting, filtering, grouping, and paging, and is what UI components (grids, combos, lists) bind to.
- **Proxy** — the I/O layer that tells the Store how to load/save data (`ajax`, `rest`, `localstorage`, `memory`), including the URL, HTTP verbs, and a **Reader/Writer** to translate JSON/XML to and from Model instances.

```
[ UI: Grid/Combo ] ←binds→ [ Store ] ←uses→ [ Proxy ] ←uses→ [ Reader/Writer ]
                              │                  │
                         holds many        talks HTTP/localStorage
                              ▼
                          [ Model ]  (schema + validation)
```

---

## 🟡 Intermediate (3–7 yrs)

### Q6. [Theory] Explain mixins in the ExtJS class system. How do they differ from `extend`?

`extend` gives you *single* inheritance — a class has exactly one parent in its prototype chain. `mixins` give you *horizontal* composition: you can pull in behavior from multiple sources without an "is-a" relationship. A mixin is just a class whose methods get copied onto the target's prototype. This solves the diamond/multiple-inheritance problem JS prototypes can't natively express. Classic framework mixins are `Ext.util.Observable` (event firing) and `Ext.state.Stateful`. Conflicts resolve in declaration order, and the host class's own methods always win. Use `extend` for genuine type hierarchies (a `UserGrid` *is-a* `Grid`); use `mixins` for cross-cutting capabilities (this thing *can* be observable, draggable, or stateful).

```javascript
Ext.define('MyApp.mixin.Logger', {
    log: function (msg) { console.log('[' + this.$className + '] ' + msg); }
});

Ext.define('MyApp.view.Dashboard', {
    extend: 'Ext.panel.Panel',          // single inheritance
    mixins: { logger: 'MyApp.mixin.Logger' }, // horizontal composition
    initComponent: function () {
        this.callParent(arguments);     // call super, NOT this._super
        this.log('Dashboard initialized');
    }
});
```

### Q7. [Theory] Compare the classic MVC architecture with the newer MVVM (ViewController + ViewModel) in ExtJS.

ExtJS 4 introduced **MVC**: a central `Ext.app.Controller` used `refs` (selectors) to grab views and `control()` to wire event listeners globally. The problem was that controllers were *application-scoped singletons* — they didn't map cleanly to a specific view instance, leading to selector collisions and memory leaks when views opened/closed repeatedly.

ExtJS 5+ introduced **MVVM**, which is the modern recommendation:

- **ViewController** (`Ext.app.ViewController`) — lifecycle-bound *one-to-one* to a view instance. It holds event handlers and reference logic for that view and is automatically destroyed with it. No more global selector soup.
- **ViewModel** (`Ext.app.ViewModel`) — holds the data/state and exposes it via **two-way data binding**. Views declare `bind: { ... }` configs and a formula/data system keeps the UI in sync reactively.

```
        MVC (ExtJS 4)                    MVVM (ExtJS 5+)
   ┌──────────────────┐            ┌──────────────────────┐
   │  Controller      │            │  View                │
   │  (singleton)     │            │  ├─ controller: VC   │ 1:1 lifecycle
   │  refs + control()│            │  └─ viewModel: VM    │ data + binding
   └────────┬─────────┘            └─────────┬────────────┘
            │ global selectors               │ scoped to instance
            ▼                                 ▼
        many views                       this view only
```

```javascript
Ext.define('MyApp.view.user.UserController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.user',
    onSaveClick: function () {
        var record = this.getViewModel().get('currentUser');
        record.save();
    }
});

Ext.define('MyApp.view.user.UserModel', {
    extend: 'Ext.app.ViewModel',
    alias: 'viewmodel.user',
    data: { currentUser: null },
    formulas: {
        // computed/derived state
        canSave: function (get) { return !!get('currentUser.dirty'); }
    }
});

Ext.define('MyApp.view.user.UserForm', {
    extend: 'Ext.form.Panel',
    controller: 'user',
    viewModel: 'user',
    items: [
        { xtype: 'textfield', fieldLabel: 'Name', bind: '{currentUser.name}' }
    ],
    buttons: [
        { text: 'Save', listeners: { click: 'onSaveClick' }, bind: { disabled: '{!canSave}' } }
    ]
});
```

### Q8. [Practical] You need a paginated, server-side, filterable grid. How do you build it?

Scenario: a back-office "orders" screen with 500k rows. You cannot load everything client-side. Approach: use **remote** sorting/filtering/paging so the server does the heavy lifting and the Store fetches one page at a time.

```javascript
var store = Ext.create('Ext.data.Store', {
    model: 'MyApp.model.Order',
    pageSize: 50,
    remoteSort: true,
    remoteFilter: true,
    proxy: {
        type: 'ajax',
        url: '/api/orders',
        reader: { type: 'json', rootProperty: 'data', totalProperty: 'total' }
    },
    autoLoad: true
});

Ext.create('Ext.grid.Panel', {
    store: store,
    renderTo: Ext.getBody(),
    height: 500,
    columns: [
        { text: 'ID',     dataIndex: 'id',     width: 80 },
        { text: 'Customer', dataIndex: 'customer', flex: 1, filter: 'string' },
        { text: 'Total',  dataIndex: 'total',  xtype: 'numbercolumn', format: '$0,000.00' }
    ],
    plugins: { gridfilters: true },   // enables column filter menus
    bbar: { xtype: 'pagingtoolbar', store: store, displayInfo: true }
});
```

Trade-offs: remote operations mean a round-trip per sort/filter/page (more latency, less client memory). For a few thousand rows you might keep it client-side (`remoteSort: false`) for instant interaction. In production with 500k rows, remote is mandatory, and you'd pair it with the **buffered renderer** (`Ext.grid.plugin.BufferedRenderer`) so the DOM only holds visible rows — otherwise rendering tens of thousands of `<tr>` elements freezes the browser.

### Q9. [Coding] Implement a Store that loads users from a REST API, with a Model that validates email format, and log invalid records.

**Problem:** Define a `User` model with `id`, `name`, `email`; validate that email matches a basic pattern; load from `/api/users` and report which records fail validation.

```javascript
// 1. Model with field types and a validator
Ext.define('MyApp.model.User', {
    extend: 'Ext.data.Model',
    fields: [
        { name: 'id',    type: 'int' },
        { name: 'name',  type: 'string' },
        { name: 'email', type: 'string' }
    ],
    validators: {
        name:  'presence',
        email: { type: 'format', matcher: /^[^@\s]+@[^@\s]+\.[^@\s]+$/ }
    }
});

// 2. Store with a REST proxy
var store = Ext.create('Ext.data.Store', {
    model: 'MyApp.model.User',
    proxy: {
        type: 'rest',
        url: '/api/users',
        reader: { type: 'json', rootProperty: 'data' }
    }
});

// 3. Load and inspect validity
store.load(function (records, operation, success) {
    if (!success) {
        console.error('Load failed:', operation.getError());
        return;
    }
    records.forEach(function (rec) {
        var errors = rec.getValidation().getData(); // {field: true | message}
        Object.keys(errors).forEach(function (field) {
            if (errors[field] !== true) {
                console.warn('Record ' + rec.get('id') + ' invalid ' +
                    field + ': ' + errors[field]);
            }
        });
    });
});
```

**Edge cases:** empty response (`records` is `[]`, callback still fires with `success: true`); network failure (`success` is `false`, read `operation.getError()`); a record with `email` null — the regex won't match, so it's flagged. **Time complexity:** O(n × f) where n = records, f = fields validated — linear and fine for a page of data. **Space:** O(n) for the in-memory records.

### Q10. [Theory] How does the ExtJS event system work? What is `Ext.util.Observable`?

Events in ExtJS are powered by the `Ext.util.Observable` mixin, which nearly every component includes. A component declares it *can fire* events and others *subscribe* with `on()` / `addListener()` (or the declarative `listeners` config). Key features: **buffering/throttling** of handlers, **single** (fire-once) listeners, **scope** control (what `this` is inside the handler), and **event domains** in MVC/MVVM where a ViewController's `control()` can listen via component-query selectors. Crucially, ExtJS uses **managed listeners** (`mon()`) — listeners registered through a component are auto-removed when that component is destroyed, which is the main defense against the framework's biggest source of memory leaks.

```javascript
var grid = Ext.create('Ext.grid.Panel', { /* ... */ });

grid.on('select', function (sel, record) {
    console.log('Selected:', record.get('name'));
}, this, { single: false, buffer: 200 }); // throttle to once per 200ms
```

### Q11. [Practical] How does theming work in ExtJS, and how would you create a custom corporate theme?

ExtJS theming is **build-time SASS compilation**, not runtime CSS-in-JS. Each theme is a package of SASS variables (`$base-color`, `$font-family`, component-level vars like `$panel-header-background-color`) that compile into a single CSS file via Sencha Cmd (`sencha app build`) or, in modern setups, the `@sencha/ext-` npm packages with the open-tooling webpack plugin. To build a corporate theme, you extend a base theme (`theme-triton`, `theme-material`, or `theme-graphite`), override the variables in your app's `sass/var/` directory, and rebuild. Because it's compiled, there's *no* runtime theme switching out of the box — supporting light/dark mode means shipping multiple compiled CSS bundles and swapping the `<link>`. This is a notable contrast to modern frameworks where CSS variables enable instant runtime theming.

```scss
// sass/var/all.scss — override base theme variables
$base-color: #1f3a5f;            // corporate navy drives the whole palette
$font-family: 'Inter', sans-serif;
$panel-header-background-color: $base-color;
$button-default-background-color: lighten($base-color, 10%);
```

### Q12. [Theory] What is component query (`Ext.ComponentQuery`) and why is it important in MVVM?

`Ext.ComponentQuery` is a CSS-selector-like engine for finding components in the live component tree (analogous to `querySelectorAll` for the DOM, but over ExtJS components). You can select by xtype (`'grid'`), by `itemId` (`'#saveButton'`), by config attribute (`'textfield[name=email]'`), and by hierarchy (`'panel > grid'`). In MVVM it's central to the ViewController's `control()` / `listen` block, which wires handlers to events on any component matching a selector *within that view's scope*. Using `itemId` + `lookupReference` / `lookup('refName')` (with `reference: 'myRef'` on the component) is preferred over global `Ext.getCmp('id')`, because hard global `id`s collide when a view is instantiated more than once.

```javascript
control: {
    'grid #refreshBtn': { click: 'onRefresh' },     // scoped, selector-based
    'textfield[name=search]': { change: 'onSearch' }
}
// Inside a ViewController:
this.lookup('userGrid');   // resolves component with reference:'userGrid'
```

---

## 🟠 Advanced (8–12 yrs)

### Q13. [Theory] ExtJS has two product editions — Classic and Modern. How do you decide, and what is the "universal app" architecture?

Sencha ships two distinct toolkits under one product:

- **Classic toolkit** — the mature, desktop-oriented widget set (dense grids, complex layouts, broad legacy browser support). This is what most enterprise apps were built on.
- **Modern toolkit** — touch-first, mobile-optimized, lighter components, evolved from the old Sencha Touch.

A **universal application** (introduced in ExtJS 6) lets you maintain *one* codebase that compiles to *both* toolkits, branching with a `toolkit`-aware build profile and shared logic. You put shared models/stores/controllers in `app/`, and toolkit-specific views in `classic/` and `modern/` folders. Sencha Cmd produces two builds; at runtime the right one is served based on the device profile.

```
my-app/
├── app/                 # shared: models, stores, view-controllers
│   ├── model/
│   └── store/
├── classic/             # desktop views (Classic toolkit)
│   └── src/view/
├── modern/              # mobile views (Modern toolkit)
│   └── src/view/
└── app.json             # declares both build profiles
```

Decision rule: pure internal desktop tool → Classic only. Field/mobile app → Modern only. One product serving both → universal, accepting the extra complexity of maintaining parallel view layers.

### Q14. [Practical] A long-running ExtJS app leaks memory — tabs opened and closed all day push the browser to 2GB+. How do you diagnose and fix it?

This is the canonical ExtJS production problem. Approach:

1. **Reproduce and measure** — Chrome DevTools → Memory → take a heap snapshot, exercise the open/close cycle 10×, snapshot again, and look at the "Detached" DOM nodes and retained `Ext.Component` instances. A leaking app shows component count climbing and never falling.
2. **Find the retainers.** The usual culprits:
   - Listeners attached with `on()` to a component that *outlives* the listener's owner (e.g., a global Store's `load` event referencing a view). Fix: use **`mon()`** (managed listeners) or remove in `destroy()`.
   - Components created but never added to a container — they're never auto-destroyed. Fix: ensure `autoDestroy` semantics or call `cmp.destroy()`.
   - Holding hard references in singletons/controllers (the classic MVC `refs` leak).
3. **Verify cleanup.** Override `destroy()` (or `onDestroy`) and confirm `Ext.ComponentManager.getCount()` returns to baseline after closing tabs.

```javascript
Ext.define('MyApp.view.ReportTab', {
    extend: 'Ext.panel.Panel',
    initComponent: function () {
        this.callParent(arguments);
        // managed: auto-removed when THIS panel is destroyed
        this.mon(MyApp.store.GlobalEvents, 'datachanged', this.onData, this);
    },
    onData: function () { /* ... */ },
    doDestroy: function () {           // ExtJS 6.5+ teardown hook
        Ext.destroy(this.myChart, this.myTimer);
        this.callParent();
    }
});
```

In production I'd also set tab panels to `closeAction: 'destroy'` (not `'hide'`, the default for some configs), so closing a tab actually tears down the component instead of keeping it in memory.

### Q15. [Theory] Explain the ExtJS layout lifecycle and the difference between `initComponent`, `render`, and `afterRender`. Why does layout thrash hurt performance?

ExtJS uses a **deferred, batched layout engine**. The lifecycle of a component is roughly:

```
constructor
  └─ initConfig (apply config, run setters)
  └─ initComponent()        // declare items, stores — DOM not yet created
beforeRender
  └─ render()               // DOM elements generated and inserted
afterRender()               // DOM exists; safe to measure/attach DOM events
  └─ LAYOUT RUN: measure → calculate → publish sizes (the layout context)
boxReady                    // first layout complete
```

The layout engine runs in a **context** that batches all size calculations and writes them to the DOM in one pass to avoid forced synchronous reflows. **Layout thrashing** happens when code interleaves DOM reads (`getHeight()`) and writes (`setWidth()`) inside loops, forcing the browser to recalc layout repeatedly, or when you call `doLayout()`/`updateLayout()` manually in a loop. The fix: wrap bulk changes in `Ext.suspendLayouts()` / `Ext.resumeLayouts(true)` so the engine flushes once. Never put expensive logic in resize/layout handlers without buffering. Knowing *where* DOM is and isn't available (it isn't in `initComponent`, it is in `afterRender`) prevents whole classes of bugs.

```javascript
Ext.suspendLayouts();
myPanel.add(manyItems);          // dozens of adds...
myPanel.setTitle('Updated');
Ext.resumeLayouts(true);         // single layout pass for everything
```

### Q16. [Coding] Implement a debounced live-search that filters a client-side Store as the user types, without hammering layout.

**Problem:** A text field should filter a grid's Store on `keyup`, but only after the user pauses typing (300ms), and filtering should be case-insensitive across two fields.

```javascript
Ext.define('MyApp.view.SearchableGridController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.searchablegrid',

    onSearchChange: function (field, newValue) {
        var store = this.lookup('itemsGrid').getStore();
        var term  = (newValue || '').toLowerCase();

        store.clearFilter(true);             // suspend events during clear
        if (term.length === 0) {
            store.filter();                  // re-apply (none) and refresh
            return;
        }
        store.filterBy(function (record) {   // custom predicate, client-side
            return record.get('name').toLowerCase().indexOf(term) > -1 ||
                   record.get('email').toLowerCase().indexOf(term) > -1;
        });
    }
});

Ext.define('MyApp.view.SearchableGrid', {
    extend: 'Ext.grid.Panel',
    controller: 'searchablegrid',
    reference: 'itemsGrid',
    tbar: [{
        xtype: 'textfield',
        emptyText: 'Search…',
        listeners: {
            change: {
                fn: 'onSearchChange',
                buffer: 300          // built-in debounce — fires 300ms after last keystroke
            }
        }
    }],
    columns: [
        { text: 'Name',  dataIndex: 'name',  flex: 1 },
        { text: 'Email', dataIndex: 'email', flex: 1 }
    ]
});
```

**Approaches compared:** A naive version listens on `keyup` and calls `store.filter()` every keystroke — on a 10k-row client store that re-runs the predicate and re-renders the grid on every character, causing jank. The `buffer: 300` listener config (or `Ext.Function.createBuffered`) coalesces keystrokes so filtering runs once per pause. **Time complexity:** each filter pass is O(n) over the store; debouncing reduces *how often* that O(n) pass runs from once-per-keystroke to once-per-pause. **Space:** O(1) extra. **Edge cases:** empty term must clear the filter (else the grid shows nothing); `null` field values would throw on `.toLowerCase()` — guard with `(record.get('name') || '')`.

### Q17. [Practical] Your team must integrate a modern React widget into a large legacy ExtJS app. How do you bridge them?

Real scenario: a 2014-era ExtJS 5 trading app needs a new React-based charting component the data-viz team built. Full rewrite is off the table. Approach — treat ExtJS as the shell and mount React into a host component:

1. Create an ExtJS component whose `afterRender` exposes a stable DOM node (`this.getEl().dom`).
2. Call `ReactDOM.createRoot(node).render(<Chart .../>)` in `afterRender`, and `root.unmount()` in `doDestroy` — wiring React's lifecycle to ExtJS's so you don't leak.
3. Communicate via props down (pass Store data as a plain array) and callbacks up (React calls an ExtJS-supplied function, which fires an ExtJS event the ViewController listens to). Keep the boundary thin and serializable.

```javascript
Ext.define('MyApp.view.ReactHost', {
    extend: 'Ext.Component',
    afterRender: function () {
        this.callParent(arguments);
        this.reactRoot = ReactDOM.createRoot(this.getEl().dom);
        this.renderReact();
    },
    setData: function (rows) { this._data = rows; this.renderReact(); },
    renderReact: function () {
        if (this.reactRoot) {
            this.reactRoot.render(
                React.createElement(MyChart, { data: this._data || [] })
            );
        }
    },
    doDestroy: function () {
        if (this.reactRoot) { this.reactRoot.unmount(); }
        this.callParent();
    }
});
```

Trade-offs: two frameworks, two bundles, doubled memory footprint, and event-model impedance (ExtJS Observable vs React props). It's a *strangler-fig* bridge, not an end state — useful to ship value while a larger migration proceeds incrementally.

### Q18. [Theory] What are the security considerations specific to ExtJS apps?

ExtJS isn't immune to standard web vulnerabilities, and a few patterns make it riskier: (1) **XSS via unescaped data** — many components (`html` config, `tpl`/`XTemplate`, grid cell renderers) inject strings as raw HTML. Always escape user-controlled data with `Ext.String.htmlEncode()` in renderers and templates; an XTemplate `{value}` is *not* auto-escaped the way React/Angular bindings are. (2) **`Ext.Loader` dynamic script loading** — the dev-mode loader fetches `.js` over the network by class name; ensure production builds are statically compiled so you're not loading arbitrary paths, and serve over HTTPS. (3) **CSRF** — Ajax proxies must send anti-CSRF tokens; configure them via `Ext.Ajax.defaultHeaders` or proxy `headers`. (4) **Sensitive data in stores** — client-side stores hold full datasets; do server-side authorization, never rely on client filtering for security. (5) **Outdated framework versions** — legacy ExtJS 4/5 apps may run on versions with known CVEs and EOL'd dependencies; track Sencha's security advisories and your license's support window.

```javascript
columns: [{
    text: 'Comment',
    dataIndex: 'comment',
    renderer: function (value) {
        return Ext.String.htmlEncode(value); // prevent stored XSS in the grid
    }
}]
```

---

## 🔴 Expert (15+ yrs)

### Q19. [Theory] You're advising leadership on whether to keep maintaining or migrate a 400-screen ExtJS 5 enterprise app. How do you frame the decision in 2026?

The decision is risk and economics, not technology fashion. Frame it along several axes:

- **Support/EOL risk** — Confirm the Sencha license tier and supported version window. Older majors lose security patches; running unsupported ExtJS on modern browsers risks rendering bugs and unpatched CVEs. This is often the forcing function.
- **Talent** — ExtJS developers are increasingly scarce and expensive; React/Vue/Angular skills are abundant. A codebase only a shrinking pool can maintain is a business continuity risk.
- **Total cost** — Per-developer Sencha licensing vs. open-source frameworks; weigh it against a multi-year migration cost (400 screens is *years*, not months).
- **Strategic fit** — If the app is in maintenance-only mode and stable, "if it isn't broken" may win; a freeze plus security backports can be cheaper than rewrite. If it's actively evolving with heavy feature demand, the velocity tax of ExtJS compounds.
- **Migration path** — Big-bang rewrite vs. **strangler-fig** incremental migration (new features in React mounted alongside ExtJS, screens peeled off one by one behind a shared shell/auth). Incremental almost always wins for risk at this scale.

The honest recommendation is usually: freeze net-new ExtJS, build a strangler-fig boundary, migrate highest-churn/highest-value screens first, and let low-traffic stable screens age out last.

### Q20. [Practical] Walk through a concrete strangler-fig migration of an ExtJS app to React. What's the sequencing and the riskiest part?

```
Phase 0  Inventory & instrument  → catalog 400 screens, add usage analytics
                                    (kill dead screens; don't migrate them)
Phase 1  Shared shell            → single SPA shell hosts both stacks;
                                    unified auth/routing/session
Phase 2  Adapter layer           → ExtJS↔React mount bridge (see Q17),
                                    shared data layer (REST/GraphQL client)
Phase 3  Migrate by value/churn  → highest-traffic + highest-change screens
                                    rebuilt in React, routed in via the shell
Phase 4  Decommission            → remove ExtJS bundle once last screen ports;
                                    drop the Sencha license
```

The riskiest part is the **shared state and routing boundary**, not the UI components. Two frameworks must agree on auth/session, browser history/URL, and a single source of truth for shared domain data — otherwise you get double-fetching, divergent caches, and back-button chaos. I'd centralize auth and the data client *outside* both frameworks (a plain TS module) so each consumes the same session and cache. A real-world case: large financial-services firms (and tools like internal trading/ops consoles) have run exactly this multi-year ExtJS→React strangler migration, keeping the legacy grid screens live while peeling off workflow screens, precisely because a flag-day rewrite of a regulated 400-screen app is unacceptable risk.

### Q21. [Theory] How would you set up a modern build, CI, and dependency strategy for an ExtJS 7.x app, moving away from Sencha Cmd?

Modern ExtJS (6.7+/7.x) supports **open tooling**: the framework is consumed as scoped npm packages (`@sencha/ext`, `@sencha/ext-classic`, `@sencha/ext-react`, etc.) via a private Sencha npm registry, and built with the `@sencha/ext-webpack-plugin` (or the Vite/Rollup community equivalents) instead of the older Java-based Sencha Cmd. This lets ExtJS slot into a standard Node/CI pipeline: `npm ci`, webpack production build, tree-shaking of unused components via the `requires`/`uses` metadata, SASS theme compilation as a build step, and source-mapped bundles. In CI you cache `node_modules`, run the build in a container with the registry credentials injected as a secret (never commit the Sencha token), run lint/unit tests (e.g. Jest against ViewController logic and Store logic), and publish a versioned artifact. The strategic win is that ExtJS stops being a special-snowflake build and becomes "just another npm dependency," which also eases the eventual React migration since the toolchain is already shared.

### Q22. [Behavioral] Tell me about a time you had to defend a deeply unpopular technical position — e.g., *not* rewriting a legacy ExtJS app.

Use STAR. **Situation:** A new VP wanted to greenlight a full React rewrite of a stable, 250-screen ExtJS back-office app because "ExtJS is dead." **Task:** As staff engineer, I owned the recommendation. **Action:** Rather than argue ideology, I built a one-page evidence case — usage analytics showing 60% of screens had <5 monthly users (rewrite-and-ship-nothing risk), a cost model comparing a 2.5-year rewrite (with feature freeze) against a freeze-plus-strangler approach, and a 6-week spike proving the React-in-ExtJS bridge worked. I presented options, not a veto, and named the conditions under which a full rewrite *would* be right. **Result:** Leadership chose the incremental path; we migrated the 12 highest-churn screens in two quarters and delivered new features the rewrite would have blocked for years. The lesson I emphasize: defending an unpopular position works only when you replace opinion with data and give decision-makers a framed choice — credibility comes from acknowledging the other side's valid concerns, not dismissing them.

### Q23. [Theory] What ExtJS-specific patterns and anti-patterns would you enforce in code review on a large team?

Enforce: (1) **ViewController + ViewModel over MVC controllers** — no application-scoped singleton controllers grabbing views by global id. (2) **`reference`/`lookup` and `itemId`, never `Ext.getCmp('hardId')`** — global ids collide and leak. (3) **Managed listeners (`mon`) and explicit `doDestroy` cleanup** for any non-child resource (charts, timers, global store listeners). (4) **`xtype` lazy config over eager `Ext.create`** in `items`. (5) **`Ext.suspendLayouts`/`resumeLayouts` around bulk DOM mutations.** (6) **`htmlEncode` in every renderer/template touching user data.** (7) **Remote sort/filter/page + BufferedRenderer for large grids.** Anti-patterns to reject: deep inheritance chains where a mixin fits better; storing UI state in the Model instead of the ViewModel; manual `doLayout()` calls; and "god ViewControllers" that should be split. On a large team I'd codify these in a lint ruleset (custom ESLint rules where possible) plus a PR checklist, because consistency in ExtJS's many "two ways to do it" choices is what keeps a big codebase navigable.

### Q24. [Practical] Performance: an ExtJS dashboard with 8 live-updating grids and 4 charts is sluggish during market hours. How do you make it real-time-capable?

Approach in layers: (1) **Transport** — replace per-grid Ajax polling with a single WebSocket (or SSE) feed; one connection, server pushes deltas. (2) **Apply deltas, don't reload stores** — use `store.loadData(rows, true)` append/merge or targeted `record.set()` so only changed cells re-render, instead of `store.load()` which rebuilds everything. (3) **Batch UI updates** — buffer incoming messages and flush on `requestAnimationFrame` (or a 100–250ms interval) inside `Ext.suspendLayouts()/resumeLayouts()`, so 200 ticks/sec become a handful of layout passes. (4) **Virtualize** — `BufferedRenderer` so only visible rows exist in the DOM. (5) **Throttle non-critical widgets** — charts re-render at most a few times per second; flash-on-change cell styling via CSS class toggles, not full re-renders. (6) **Profile** — DevTools Performance flamegraph to confirm time is in layout/paint vs. JS, and watch for forced reflows. The combination of WebSocket + delta updates + rAF-batched suspended layouts + virtualization is what turns a freezing dashboard into a smooth real-time one; the single biggest win is almost always *not* calling `store.load()` on every tick.

---

## ✅ Key Takeaways

- ExtJS is an all-in-one enterprise framework: class system (`Ext.define`, mixins), rich components, layout engine, data package, and SASS theming in one box.
- The **data package** (Model → Store → Proxy → Reader/Writer) is the client-side ORM; bind it to grids, combos, and lists.
- Prefer **MVVM** (ViewController + ViewModel + two-way binding) over the legacy MVC singleton controllers — it's lifecycle-scoped and leak-resistant.
- **Memory management** is ExtJS's defining production concern: use managed listeners (`mon`), `reference`/`lookup` over global ids, and explicit `doDestroy` cleanup.
- For large data: **remote** sort/filter/page plus **BufferedRenderer**; for real-time: WebSocket + delta updates + rAF-batched suspended layouts.
- Classic vs Modern toolkits target desktop vs touch; **universal apps** share one codebase across both.
- Migration off ExtJS is best done **incrementally (strangler-fig)** with a shared shell, shared auth, and a React/ExtJS mount bridge — rarely a big-bang rewrite.

## ⚠️ Common Pitfalls

- Using `Ext.getCmp('hardId')` and hard-coded `id`s — they collide and leak when a view instantiates more than once. Use `reference`/`itemId`.
- Forgetting to escape user data in renderers/`XTemplate` — `{value}` is **not** auto-escaped; stored XSS results. Use `Ext.String.htmlEncode`.
- Calling `store.load()` on every real-time tick instead of applying deltas — rebuilds and re-renders everything.
- Attaching listeners with `on()` to long-lived objects from short-lived views without cleanup — the #1 memory leak source.
- Manual `doLayout()`/`updateLayout()` calls in loops causing layout thrash — wrap bulk changes in `suspendLayouts`/`resumeLayouts`.
- Loading 100k+ rows client-side with no buffered renderer — freezes the browser.
- Putting business/UI state in the Model instead of the ViewModel, and building god ViewControllers.
- Running an EOL ExtJS major in production with unpatched CVEs and no support window.

## 📚 Further Reading

- [Sencha ExtJS Official Documentation](https://docs.sencha.com/extjs/) — API reference, guides, and toolkit docs (Classic & Modern).
- [Sencha ExtJS Guides: Class System, Data, MVVM](https://docs.sencha.com/extjs/7.8.0/guides/) — the canonical concept guides.
- *Ext JS in Action* by Jay Garcia (Manning) — still the clearest deep dive on the class system and components (covers earlier versions; concepts hold).
- *Learning Ext JS* (Packt) — practical, project-based introduction for newcomers.
- [Sencha Blog & Migration Resources](https://www.sencha.com/blog/) — open tooling, React bridge, and modernization articles.
- [Martin Fowler — StranglerFigApplication](https://martinfowler.com/bliki/StranglerFigApplication.html) — the foundational pattern for incremental legacy migration.
