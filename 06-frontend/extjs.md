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

## 🧩 Extended Questions — Set 1: Deep Theory & Internals

### 🟢 Basic — extended

#### Q25. [Theory] What is `xtype` under the hood, and how does it differ from an `alias`?

`xtype` is not a separate concept — it is just a *namespace* within the broader **alias** system. When you write `alias: 'widget.usergrid'` in an `Ext.define`, the `widget.` prefix registers that class with the component manager, and the part after the dot (`usergrid`) becomes its `xtype`. The shorthand `xtype: 'usergrid'` in a class definition is exactly equivalent to `alias: 'widget.usergrid'`. ExtJS uses the same alias machinery for several other namespaces: `store.` for stores, `proxy.` for proxies, `controller.` for ViewControllers, `viewmodel.` for ViewModels, and `plugin.` for plugins. So `xtype` is "the alias namespace reserved for components."

The reason this matters is **lazy instantiation**. When a container's `items` array contains plain config objects with an `xtype`, those objects are *not* component instances yet — they're just data. The container only calls `Ext.widget(xtype, config)` (which resolves the alias to a class and constructs it) when it actually renders, or when you call `getComponent`/`lookup`. This deferral is what lets you declare a 200-component view tree cheaply and pay the construction cost only for what's shown.

```javascript
Ext.define('MyApp.view.UserGrid', {
    extend: 'Ext.grid.Panel',
    alias: 'widget.usergrid'   // identical to writing  xtype: 'usergrid'
});

// All three are equivalent ways to construct it:
Ext.create('MyApp.view.UserGrid');
Ext.create('widget.usergrid');
Ext.widget('usergrid');   // alias-based factory, used internally for items[]
```

A subtle consequence: because xtypes share one global registry, two classes declaring the same `xtype` silently override each other — the last one defined wins. On a large team this is a real collision hazard, which is why app-specific xtypes are usually prefixed (`xtype: 'myapp-usergrid'`).

#### Q26. [Theory] Why does ExtJS recommend `Ext.create()` over the native `new` operator?

You *can* call `new MyApp.model.User()` and it will often work, but `Ext.create()` does several things the bare `new` cannot. First and most importantly, `Ext.create` integrates with the **dynamic class loader**: if the class hasn't been loaded yet (in dev mode, or via `Ext.Loader`), `Ext.create` can synchronously pull it in by name before constructing, whereas `new SomeClass()` throws `SomeClass is not defined` if the symbol isn't already on the page. Second, `Ext.create` accepts a **string class name or alias** (`Ext.create('widget.grid')`), enabling fully data-driven instantiation where the type is decided at runtime — impossible with `new`, which needs the constructor reference in hand.

Third, `Ext.create` respects the framework's **instantiation hooks** and any class-level overrides registered via `Ext.define(..., { override })`, ensuring the object you get back is the fully-decorated version the framework expects. Bare `new` bypasses some of the loader/override plumbing.

```javascript
// Works only if the class object is already in scope:
var a = new MyApp.model.User({ name: 'Ada' });

// Works even if MyApp.model.User must be loaded on demand,
// and lets the *type* be a runtime string:
var typeName = isAdmin ? 'MyApp.model.Admin' : 'MyApp.model.User';
var b = Ext.create(typeName, { name: 'Ada' });
```

In production builds where everything is statically compiled and present, the loader advantage disappears and `new` is marginally faster, but the team convention is still `Ext.create` for consistency and because the string-based form is what makes config-driven UIs possible.

#### Q27. [Theory] What is the `config` system, and what is the difference between `apply*` and `update*` methods?

When you declare a property inside the `config` block of an `Ext.define`, ExtJS auto-generates a getter (`getFoo`) and setter (`setFoo`) for it, plus two optional hooks you can implement: `applyFoo(newValue, oldValue)` and `updateFoo(newValue, oldValue)`. This is the framework's reactive-property mechanism and it predates (and is conceptually similar to) modern frameworks' computed setters. The distinction between the two hooks is precise and frequently asked:

- **`applyFoo`** runs *during* the set, **before** the value is stored. Its job is **transformation/coercion/validation** — whatever it returns becomes the stored value. If it returns `undefined`, the set is **aborted** and `update` never fires. This is where you'd normalize a raw config into an instance (e.g., turn a store config object into a real Store).
- **`updateFoo`** runs *after* the new value is committed, **only if the value actually changed** (compared to the old one). Its job is **side effects** — react to the new value: update the DOM, fire an event, refresh dependent components.

```javascript
Ext.define('MyApp.Thermometer', {
    config: { celsius: 0 },

    applyCelsius: function (value) {
        // coerce/validate; returning undefined would cancel the set
        return Math.max(-273.15, Number(value));
    },
    updateCelsius: function (newVal, oldVal) {
        // side effect — runs only when the value genuinely changed
        this.fireEvent('tempchange', newVal, oldVal);
    }
});
```

The ordering guarantee (apply → store → update-if-changed) is what makes the system predictable. A common bug is putting DOM updates in `apply` (they fire even on no-op sets and before the value is stored) or putting coercion in `update` (too late — the raw value is already committed). Getting this right is core to writing well-behaved custom components.

#### Q28. [Practical] What is the difference between `requires`, `uses`, and `mixins` in a class definition, and how do they affect the build?

All three declare dependencies, but with different timing and semantics, and the difference directly affects load order and bundle structure:

| Declaration | Loaded… | Creates inheritance? | Typical use |
|-------------|---------|----------------------|-------------|
| `extend`    | before this class is defined | yes (single, prototype chain) | the parent class |
| `mixins`    | before this class is defined | no (methods copied onto prototype) | cross-cutting behavior |
| `requires`  | before this class is defined | no | classes used *during* construction/`initComponent` |
| `uses`      | **after** this class is defined (deferred) | no | classes used later, lazily (e.g. a dialog opened on click) |

`requires` forces a hard dependency that must be present before your class body runs — use it for anything referenced at construction time (a field type, a store you instantiate in `initComponent`). `uses` is a *soft* dependency: it tells the loader "this class will eventually need that one, but it can load afterward," which avoids circular-dependency deadlocks and lets the loader defer non-critical code.

```javascript
Ext.define('MyApp.view.Dashboard', {
    extend: 'Ext.panel.Panel',
    mixins: ['MyApp.mixin.Logger'],         // behavior copied in
    requires: ['Ext.grid.Panel'],           // needed at render time
    uses: ['MyApp.view.SettingsDialog'],    // only opened later, on a click

    onSettingsClick: function () {
        // safe: 'uses' guaranteed it's loaded by the time the app runs
        Ext.create('MyApp.view.SettingsDialog').show();
    }
});
```

For the build, Sencha Cmd and the webpack plugin read this metadata to (a) topologically order classes in the bundle and (b) tree-shake: a class no `requires`/`uses`/`extend`/`alias` reference points to can be dropped from the production build. Getting `requires` vs `uses` wrong is the classic source of "X is not a constructor" runtime errors (missing `requires`) or unnecessarily large initial bundles / circular load failures (overusing `requires` where `uses` belongs).

### 🟡 Intermediate — extended

#### Q29. [Theory] How does `callParent` actually work, and when do you need `callSuper` instead?

`callParent(arguments)` is ExtJS's mechanism for invoking the superclass implementation of the method you're currently in — the equivalent of `super.method()` in ES6 classes, but implemented in ES5-compatible runtime machinery. When `Ext.define` builds a class, each method that overrides a parent method is wrapped so it knows its own `$owner` (the class it was defined on) and `$name`. `callParent` walks **up** from `$owner` to find the next implementation of `$name` in the prototype chain and calls it with the given args. You pass `arguments` (the magic array) to forward the original parameters unchanged, or an explicit array to alter them.

The catch — and the reason `callSuper` exists — is **overrides**. ExtJS lets you patch an existing class via `Ext.define('Foo', { override: 'Bar' })`, which *inserts* a method into Bar's chain at the same level. If that override calls `callParent`, it correctly skips back to the *original* Bar method (the one it replaced). But sometimes an override needs to skip not just to its own predecessor but past its entire level to the **true superclass**, bypassing other overrides at the same tier. That's `callSuper(arguments)` — it jumps straight to the parent class's implementation, ignoring same-level overrides.

```javascript
Ext.define('MyApp.view.Base', {
    extend: 'Ext.panel.Panel',
    initComponent: function () {
        this.title = this.title || 'Default';
        this.callParent(arguments);   // → Ext.panel.Panel#initComponent
    }
});

// An override that augments Base#initComponent:
Ext.define('MyApp.patch.BaseLogging', {
    override: 'MyApp.view.Base',
    initComponent: function () {
        console.log('creating', this.$className);
        this.callParent(arguments);   // → the ORIGINAL Base#initComponent
        // this.callSuper(arguments)   // would skip Base entirely → Panel
    }
});
```

99% of code uses `callParent`; `callSuper` is a niche tool for override authors who must deliberately bypass the very method they're overriding. Forgetting to call `callParent` at all in `initComponent`/`constructor` is a classic bug — the component never finishes initializing and renders broken.

#### Q30. [Theory] Explain how two-way data binding works internally in the ViewModel. What is the "stub" tree and the bind scheduler?

The ViewModel is not magic reactivity like Vue's proxies — it's an explicit **publish/subscribe graph with a batched scheduler**. When you write `bind: '{user.name}'` on a textfield, the ViewModel parses that descriptor and builds (or reuses) a node in its internal **stub tree** — a tree of `Ext.app.bind.Stub` objects mirroring the dotted data paths (`user` → `name`). Each stub tracks which **bindings** (consumers, like the textfield's value) depend on it. When data changes (`vm.set('user.name', 'Ada')` or a bound record's field updates), the affected stub is marked dirty and **schedules** itself with the ViewModel's scheduler rather than firing synchronously.

The scheduler flushes on the next tick (`Ext.app.bind.Scheduler`, driven by a microtask/animation-frame-like tick). On flush it does a single topologically-ordered pass: recompute dirty formulas, then notify each dependent binding exactly once with the new value. This batching is the whole point — if you set ten fields of a record in a loop, the bound grid/form updates **once**, not ten times, avoiding layout thrash. **Two-way** binding works because a *two-way* binding (like a form field's value) also registers itself as a publisher: when the user types, the field publishes back up through its stub, which writes to the underlying data and re-schedules anything else bound to the same path.

```
ViewModel data:  { user: { name: 'Ada', age: 30 } }

        Stub tree                         Scheduler queue (per tick)
        ─────────                         ──────────────────────────
        root
         └─ user            set('user.name','Bo')
             ├─ name  ●──────────────────────►  [ name stub dirty ]
             │   ├─ textfield.value  (2-way)         │ flush →
             │   └─ formula canSave                  ├─ recompute canSave
             └─ age                                  ├─ update textfield
                                                     └─ update Save.disabled
```

Two practical implications fall out of this design. (1) Binding is **asynchronous by one tick** — reading a bound widget's value *immediately* after `vm.set()` in the same synchronous block can see the old value; tests must let the scheduler flush. (2) Deeply nested or chatty formulas can make the flush pass expensive, so you keep formulas pure and cheap, and avoid binding huge object graphs you don't need.

#### Q31. [Theory] Compare `Ext.Template` and `Ext.XTemplate`. When is the extra machinery of XTemplate worth it?

`Ext.Template` is a simple string interpolator: you give it a string with `{token}` placeholders and an array of segments, and `apply(data)` substitutes values. It supports basic member access and **format functions** (`{price:usMoney}`, `{name:ellipsis(10)}`) via the `Ext.util.Format` methods, but it has **no control flow** — no loops, no conditionals. It's fast and adequate for fixed-shape output like a single record's display.

`Ext.XTemplate` is a superset that adds a small templating *language* compiled into a JS function the first time it runs: `<tpl for="...">` loops over arrays, `<tpl if="...">`/`<tpl elseif>`/`<tpl else>` conditionals, the special `{#}` (1-based index) and `{.}` (current item) tokens in loops, `{[ ...inline JS... ]}` for arbitrary expressions, and member functions you attach to the template. That compilation step means the first render has a small cost but subsequent renders are fast.

```javascript
var tpl = new Ext.XTemplate(
    '<ul>',
    '<tpl for="orders">',
        '<li class="{[ values.total > 1000 ? "big" : "small" ]}">',
            '#{#}: {customer} — {total:usMoney}',
            '<tpl if="rush"> (RUSH)</tpl>',
        '</li>',
    '</tpl>',
    '</ul>'
);
tpl.overwrite(el, { orders: [ { customer: 'Ada', total: 1500, rush: true } ] });
```

Use `Ext.Template` for trivial, loop-free substitution (a tooltip, a single label); reach for `XTemplate` whenever you iterate a collection or branch — which is most grid `tpl` columns, dataview item templates, and component `tpl` configs. The critical security caveat for both: `{value}` outputs raw HTML and is **not** auto-escaped. To prevent XSS you must use the `htmlEncode` format (`{comment:htmlEncode}`) or escape in a member function — exactly the pitfall covered in the security question, and a major behavioral difference from React/Angular where bindings escape by default.

#### Q32. [Practical] Compare the major ExtJS layout managers and explain how a layout decides child sizes.

A container's `layout` config selects an `Ext.layout.container.*` class whose single responsibility is to compute the size and position of the container's `items`. The children do **not** size themselves — they ask the layout. Picking the right layout is the difference between a responsive UI and one that constantly fights you.

| Layout      | Sizing model | Use when |
|-------------|--------------|----------|
| `fit`       | single child fills 100% of container | a panel wrapping one grid/form |
| `border`    | regions: north/south/east/west (fixed/collapsible) + center (fills rest) | app shell / viewport |
| `vbox`/`hbox` | flexbox-like: `flex` ratios + fixed sizes along one axis, `align` on cross axis | toolbars, stacked forms, proportional splits |
| `card`      | one child visible at a time (`activeItem`) | wizards, tab bodies |
| `column`    | percentage/pixel columns, wraps | dashboard tiles |
| `anchor`    | children sized relative to container (`anchor: '100% 50%'`) | older form layouts |
| `auto` (default) | children take natural size, container scrolls | simple stacking |

The mechanism: when a layout runs (in the batched **layout context** from Q15), it calls each child's size calculation, resolving `flex` ratios against the remaining space, honoring `minWidth`/`maxWidth` constraints, then publishes the computed `width`/`height` to each child in a single DOM write pass. `flex` is proportional ("take 2 shares of leftover space"), while a fixed `width` is reserved first. A frequent beginner mistake is nesting a `flex` child inside an `auto`/`fit` parent that has no defined height — the child has nothing to take a ratio *of*, so it collapses to zero height. The rule of thumb: a `vbox`/`hbox`/`border` chain must terminate in a container with a concrete size (a `Viewport`, or an explicit `height`), or the proportional math has no basis.

```javascript
{
    xtype: 'panel',
    layout: { type: 'vbox', align: 'stretch' },  // stretch = full cross-axis width
    items: [
        { xtype: 'toolbar', height: 40 },          // fixed
        { xtype: 'grid', flex: 3 },                // 3 shares of leftover height
        { xtype: 'panel', flex: 1 }                // 1 share
    ]
}
```

#### Q33. [Theory] How does `Ext.data.Store` filtering and sorting differ between local and remote modes, and what actually happens on the wire?

A Store can sort/filter in two fundamentally different places, controlled by `remoteSort` and `remoteFilter` (and `remoteGroup`). **Locally**, the Store keeps a master collection (`Ext.util.Collection`) plus a filtered/sorted *view*. Calling `store.sort('name')` or `store.filter('active', true)` runs the comparison/predicate in JavaScript over the in-memory records and rebuilds the view — instant, no server round-trip, but only correct if the *entire* dataset is already loaded. **Remotely**, the same calls instead trigger a reload: the Store serializes the sorters/filters into request parameters and the *server* returns the already-sorted/filtered page.

What goes on the wire is determined by the proxy's reader/writer and a few config knobs. By default an Ajax proxy sends sort/filter as JSON-encoded params:

```
GET /api/orders?page=1&start=0&limit=50
   &sort=[{"property":"total","direction":"DESC"}]
   &filter=[{"property":"status","value":"open","operator":"eq"}]
```

You can rename these via `sortParam`, `filterParam`, `directionParam`, `pageParam`, `startParam`, `limitParam`, or set `simpleSortMode: true` to flatten `sort` to a single field name plus a separate `dir` param (what many older REST backends expect).

The decisive trade-off is **correctness vs. latency**. Local sort/filter is wrong the moment the data is paged — if you only loaded page 1 of 10, a local filter only searches those 50 rows. Remote is correct for paged data but costs a round-trip per interaction and shifts the work to the server. The standard production setup for large grids is `remoteSort: true, remoteFilter: true, remoteGroup: true` with paging, so the Store is always a consistent window into a server-side result set; small fully-loaded lookup stores stay local for snappy UX.

#### Q34. [Theory] What is the difference between `Ext.Element`, `Ext.fly()`, and `Ext.get()`, and why does `Ext.fly` exist?

These are ExtJS's DOM-wrapper utilities, and the distinction is about **object allocation**. `Ext.dom.Element` (aliased `Ext.Element`) is a rich wrapper around a native DOM node providing cross-browser methods (`setStyle`, `addCls`, `on`, `getXY`, animations). `Ext.get(domOrId)` returns a **cached, persistent** `Ext.Element` instance for a node — it looks the node up, wraps it, and stores the wrapper in `Ext.cache` keyed by the element id so subsequent `Ext.get` calls on the same node return the *same* wrapper. That caching is what lets the element retain listeners and state, but it costs memory per cached element.

`Ext.fly(domOrId)` (and the related `Ext.dom.Element.fly`) returns a **shared flyweight** — a single reused `Element` instance that is repointed at whatever node you pass. It is *not* cached and *must not* be held onto across calls, because the very next `Ext.fly` call (anywhere in the framework, including internally) re-targets that same shared object to a different node. The payoff is zero allocation: in a hot loop touching thousands of nodes you avoid creating thousands of persistent wrappers.

```javascript
// Persistent — safe to keep, listeners survive, costs memory:
var el = Ext.get('header');
el.on('click', onClick);     // this wrapper lives on

// Flyweight — read-and-discard, NEVER store:
rows.forEach(function (dom) {
    Ext.fly(dom).addCls('processed');   // fine: used and dropped immediately
});
// var bad = Ext.fly(dom);  // BUG: 'bad' will be repointed by the next fly() call
```

The interview point is recognizing this as a **flyweight pattern** for performance: use `Ext.get` when the wrapper outlives the statement (you'll attach listeners or reference it later); use `Ext.fly` for transient, one-shot DOM operations in loops where allocation pressure matters. Misusing `Ext.fly` by storing the reference is a classic, hard-to-trace bug because the object silently mutates underneath you.

#### Q35. [Practical] Plugins, mixins, and subclassing all add behavior to a component. How do you choose between them?

These three extension mechanisms overlap but encode different relationships, and choosing well keeps a codebase clean. **Subclassing** (`extend`) creates a new *type* — use it when the thing genuinely *is a* specialized version that you'll instantiate as its own xtype (a `UserGrid` that is a `Grid` with fixed columns). **Mixins** add *reusable behavior* horizontally to many unrelated classes — use them for cross-cutting capabilities (`Observable`, `Bindable`, a custom `Logger`) that several different component types should share without an inheritance relationship. **Plugins** (`Ext.plugin.Abstract`) attach behavior to a *specific instance* at config time via the `plugins` array, and crucially can be **added/removed per instance** and applied to components you don't own.

| Mechanism | Relationship | Granularity | Best for |
|-----------|--------------|-------------|----------|
| `extend` (subclass) | is-a | per type | a genuinely new component type |
| `mixins` | can-do (compile-time) | per class | shared behavior across many classes |
| plugins | has-a (runtime) | per instance | optional/stackable instance features, on third-party components |

The decisive advantages of plugins are **composability and non-invasiveness**: you can stack several plugins on one grid (`gridfilters`, `cellediting`, `rowexpander`) and you can enhance a framework component (`Ext.grid.Panel`) without subclassing it. A plugin's `init(component)` hook receives the host instance, letting it wire listeners and add features, and its lifecycle is tied to that instance.

```javascript
Ext.define('MyApp.plugin.RowHighlight', {
    extend: 'Ext.plugin.Abstract',
    alias: 'plugin.rowhighlight',
    init: function (grid) {
        grid.on('select', function (sm, rec) {
            grid.getView().focusRow(rec);   // augment THIS grid instance
        });
    }
});

Ext.create('Ext.grid.Panel', {
    plugins: ['rowhighlight', 'gridfilters'],   // stack, per-instance, removable
    /* ... */
});
```

Rule of thumb: reach for a **plugin** first when the behavior is optional, stackable, or applies to a component you don't control; use a **mixin** when multiple of *your own* component types need the same capability; use **subclassing** only when you're truly minting a new reusable type.

### 🟠 Advanced — extended

#### Q36. [Theory] Explain the `Ext.Class` preprocessor/postprocessor pipeline. How does `Ext.define` turn a config object into a working class?

`Ext.define` does not build a class synchronously and immediately — it feeds your config through an ordered pipeline of **preprocessors** and **postprocessors** managed by `Ext.Class` and `Ext.ClassManager`. This indirection is what lets `Ext.define` support its declarative features (string-based `extend`, `mixins`, `config`, `requires`) that plain JS prototypes can't express. Understanding the pipeline explains a lot of otherwise-mysterious ordering behavior.

**Preprocessors** run (some asynchronously, to allow loading) before the class is registered, in roughly this order: `loader` (ensure `extend`/`requires` dependencies are present, possibly fetching them), `extend` (set up the prototype chain), `privates`, `statics`/`inheritableStatics`, `config` (generate getters/setters and apply/update plumbing), `mixins` (copy methods, respecting conflicts), and `alias`. Because the `loader` preprocessor can be async, `Ext.define` may **defer** finishing your class until its dependencies resolve — which is why a class with unmet `requires` doesn't error immediately but the dependent code waits. **Postprocessors** run after the class object exists: `alias`/`xtype` registration with `Ext.ClassManager`, `singleton` instantiation, `alternateClassName` aliasing, and finally invoking any `onClassCreated`/`callback` you passed.

```
Ext.define(name, data, callback)
        │
        ▼   PREPROCESSORS (may be async — wait on loader)
   loader → extend → statics → config → mixins → alias …
        │
        ▼   class object now built & registered
   POSTPROCESSORS
   alias/xtype reg → singleton → alternateClassName → onClassCreated
        │
        ▼
   callback(cls)   // your code can now safely use the class
```

Two practical consequences: (1) the optional **third-argument callback** to `Ext.define` fires only after the whole pipeline (including async dependency loading) completes, so it's the correct place to do work that needs the fully-built class. (2) You can register **custom preprocessors** (`Ext.Class.registerPreprocessor`) to extend the class system — advanced, but it's how features like the `config` system itself are layered in. Knowing this pipeline is what separates someone who *uses* `Ext.define` from someone who understands why dependency timing, override insertion, and config generation behave the way they do.

#### Q37. [Theory] What is the override mechanism (`Ext.define` with `override`), and how does it differ from subclassing? What are the risks?

An **override** is ExtJS's way of *modifying an existing class in place* rather than creating a descendant. You write `Ext.define('MyPatch', { override: 'Ext.grid.Panel', someMethod: function () { ... this.callParent(arguments); } })`, and the framework **merges** your members into the target class's prototype — every existing and future instance of `Ext.grid.Panel` now has your behavior. This is fundamentally different from subclassing: a subclass produces a *new* type that only affects instances you explicitly create as that subclass; an override mutates the *original* type and affects **all** instances, including those created internally by the framework.

The legitimate uses are (1) **patching framework bugs** before an official fix, (2) **applying app-wide policy** (e.g., overriding `Ext.data.proxy.Ajax` to always inject a CSRF header), and (3) **conditional/platform overrides** via the `compatibility` / `Ext.isIE`-style guards that the build can include or strip. Overrides also integrate with `callParent`/`callSuper` (Q29): an override's `callParent` reaches the *original* method it replaced, so you can wrap rather than fully replace.

```javascript
// App-wide policy: every Ajax request carries the CSRF token.
Ext.define('MyApp.override.AjaxProxy', {
    override: 'Ext.data.proxy.Ajax',
    buildRequest: function () {
        var request = this.callParent(arguments);   // original behavior first
        request.setHeaders(Ext.apply(request.getHeaders() || {}, {
            'X-CSRF-Token': MyApp.session.csrfToken
        }));
        return request;
    }
});
```

The risks are real and why code review should scrutinize overrides: they create **invisible global coupling** (a bug in an override of `Ext.Component` can break the entire app in ways grep won't reveal at the call site), they **fight framework upgrades** (your override of an internal method may break or become redundant when Sencha changes that method), and **multiple overrides of the same method** stack in load order, making behavior order-dependent and fragile. The discipline: override framework *internals* only as a last resort, prefer overriding documented public methods, keep each override tiny and well-commented with the reason and the version it targets, and revisit them on every framework upgrade.

#### Q38. [Theory] Explain `Ext.data.Session` and the difference between optimistic and pessimistic record management. What problem does a Session solve?

By default, ExtJS records are global singletons-ish per Store, and editing the same logical entity in two places can produce two divergent in-memory copies. `Ext.data.Session` solves this by acting as a **unit-of-work / identity map**: within a session, there is exactly **one** record instance per `(entityType, id)` pair. Bind a session to a ViewModel (or pass it to stores/associations) and any reference to user #5 — in a grid, in a form, via an association — resolves to the *same* record object, so an edit in one view is immediately consistent everywhere, and a single `session.getChanges()` collects every create/update/delete across all entities for one atomic save.

```javascript
var session = Ext.create('Ext.data.Session');

// Both lookups return the SAME instance (identity map):
var fromStore = session.getRecord('User', 5);
var fromAssoc = order.getUser();   // if order.userId === 5 → same object
fromStore.set('name', 'Ada');      // fromAssoc.get('name') is now 'Ada' too

// Collect ALL pending changes across every entity in one batch:
var batch = session.getSaveBatch();   // creates/updates/destroys, ordered
batch.start();                        // one coordinated server round-trip
```

The **optimistic vs. pessimistic** axis is about *when* you assume success and when you lock. ExtJS record saving is **optimistic** by default: the client mutates the record immediately (the UI updates via binding), *then* sends the change to the server; if the server rejects it, you must roll back (`record.reject()`) or reconcile. This gives a snappy UI but requires conflict handling — concurrent edits can collide, and you need server-side optimistic-concurrency tokens (version columns/ETags) to detect stale writes. A **pessimistic** approach would lock the entity (or disable editing) until the server confirms, trading responsiveness for safety; ExtJS doesn't enforce this but you implement it by gating edits on a server lock/acquire call.

The Session shines in complex editing screens — a master/detail form with associated records where the user makes many interdependent edits and saves once. Without it, you juggle multiple record copies and partial saves; with it, you get consistency, dirty-tracking across the whole graph, and one transactional `getSaveBatch()`. The cost is added memory (the session retains every touched record) and the need to manage session lifecycle (create per editing screen, destroy with it) — a forgotten session is itself a leak.

#### Q39. [Practical] How does the grid actually render rows, and how do `BufferedRenderer` and a buffered/virtual store differ? Why are both sometimes needed?

A `grid.Panel` renders through its **view** (`Ext.grid.View`), which for each visible record runs the column definitions to produce a `<tr>` of `<td>` cells, applying renderers, then writes that HTML into the table body. Naively, the view renders **one `<tr>` per record in the store** — so a store holding 100,000 records produces 100,000 rows in the DOM, which destroys layout/paint performance and memory long before the user could ever scroll to row 1,000. Two distinct mechanisms attack two distinct halves of this problem, and conflating them is a common interview stumble.

**`Ext.grid.plugin.BufferedRenderer`** virtualizes the **DOM**: it keeps only a window of rows (those visible plus a small over-scan buffer) as actual `<tr>` elements, recycling them as you scroll and adjusting spacer heights to preserve the scrollbar geometry. The *store* may still hold all the records in memory — BufferedRenderer just refuses to materialize them all into the DOM. **A buffered/virtual store** (configured via `buffered: true` historically, or a paged store with `leadingBufferZone`/`trailingBufferZone` prefetch in modern versions) virtualizes the **data**: it only keeps a window of *records* in memory and prefetches adjacent pages from the server as you scroll, discarding far-away pages.

```
                 DOM rows            Records in memory       Server fetch
BufferedRenderer  only visible±buffer  ALL (whatever's loaded)  n/a (rendering only)
Buffered store    (paired with above)  only a sliding window    prefetch pages on scroll
Both together     only visible±buffer  only a sliding window    prefetch on scroll
```

You need **BufferedRenderer alone** when the full dataset fits in memory (say 20k rows) but you don't want 20k DOM nodes — common and the default smart choice. You need **both** when the dataset is too large to even hold in memory (millions of rows): the buffered store keeps memory bounded by fetching pages on demand, *and* BufferedRenderer keeps the DOM bounded. The classic performance bug is enabling neither and loading a giant store into a plain grid — the browser freezes during render. A subtler one is enabling buffered rendering but then doing something that forces full materialization (like an unbuffered `expand`-all or summing a column client-side), which defeats the virtualization.

#### Q40. [Theory] What are the trade-offs between `Ext.Deferred`/Promises and the older callback/event style in ExtJS, and how does `Ext.Promise` relate to native Promises?

Early ExtJS was built almost entirely on **callbacks and events**: `store.load(callback)`, `Ext.Ajax.request({ success, failure })`, and Observable events. This is fine for single operations but produces "callback pyramids" and awkward error propagation when you must sequence several async steps (load A, then B that depends on A, then update UI, handle failure of any). ExtJS 6 introduced `Ext.Deferred` and `Ext.Promise` to bring **Promises/A+** semantics to the framework, allowing `.then()` chaining, composition (`Ext.Promise.all`), and centralized error handling via `.catch`.

`Ext.Promise` is a thin, framework-integrated implementation that conforms to the Promises/A+ spec and interoperates with native ES6 `Promise` (it will resolve/adopt native promises and vice-versa). The reason ExtJS shipped its own rather than just using native `Promise` was historical browser support (IE) and the desire to integrate with the framework's tick/scheduler and provide `Ext.Deferred` (the resolver-exposed form, equivalent to the deferred pattern) plus helpers. In modern ExtJS 7 on evergreen browsers you can largely use native promises and `async`/`await`, but the framework APIs that return promises return `Ext.Promise`, which is await-able.

```javascript
// Callback style — nesting and duplicated error handling:
store.load(function (recs, op, ok) {
    if (!ok) { showError(op.getError()); return; }
    detailStore.load(function (d, op2, ok2) {
        if (!ok2) { showError(op2.getError()); return; }
        render(recs, d);
    });
});

// Promise style — flat chain, single catch:
loadStore(store)
    .then(function (recs) { return Ext.Promise.all([recs, loadStore(detailStore)]); })
    .then(function (res) { render(res[0], res[1]); })
    .catch(showError);

// And in ExtJS 7 you can simply await framework promises:
async function refresh() {
    try {
        const [recs, det] = await Promise.all([loadStore(store), loadStore(detailStore)]);
        render(recs, det);
    } catch (e) { showError(e); }
}
```

The trade-off to articulate: promises give linear control flow, composition, and unified error handling, at the cost of being **one tick later** (microtask) than synchronous code and slightly heavier than a raw callback. Events (Observable) remain the right tool for **ongoing, multi-fire** notifications (a grid's `select`, a store's `datachanged`) — those are streams, not single resolutions, and don't fit the one-shot Promise model. The mature codebase uses promises for *operations* (a request, a save, a sequence) and events for *streams of state changes*.

#### Q41. [Practical] How does event delegation work in ExtJS, and how do you efficiently handle clicks across thousands of rendered elements (e.g., action icons in a grid)?

Attaching a separate DOM listener to every one of thousands of elements is both memory-heavy and a leak risk (each listener retains its closure and target). The efficient pattern, which ExtJS supports first-class, is **delegated event handling**: attach **one** listener to a stable ancestor element and use the `delegate` option (a selector) so the handler only fires when the event's target matches that selector, with the matched element handed to you. This is the same event-delegation principle as vanilla JS / jQuery, surfaced through `Ext.dom.Element#on`.

```javascript
// ONE listener on the grid's view element handles clicks on any .action-icon,
// regardless of how many rows are rendered or recycled by BufferedRenderer.
grid.getView().getEl().on('click', function (event, target) {
    var row    = Ext.fly(target).up('.x-grid-item');     // walk up to the row
    var record = grid.getView().getRecord(row);
    doAction(record);
}, this, { delegate: '.action-icon' });
```

For grids specifically, ExtJS gives you an even higher-level abstraction so you rarely hand-roll delegation: the **`actioncolumn`** renders clickable icons and routes each to a `handler` with the record already resolved, and the view fires semantic events like `cellclick`, `itemclick`, and `rowdblclick` that *already* use delegation under the hood and pass you the record/column/cell. Preferring these means you never attach per-row listeners at all.

```javascript
columns: [{
    xtype: 'actioncolumn',
    items: [
        { iconCls: 'x-fa fa-trash', tooltip: 'Delete',
          handler: function (view, rowIdx, colIdx, item, e, record) {
              view.getStore().remove(record);
          } }
    ]
}]
// Or listen once on the grid:
listeners: { cellclick: function (view, td, cellIdx, record) { /* ... */ } }
```

The interview points are: (1) delegation converts N listeners into 1, which matters enormously with virtualized/recycled rows where elements come and go — a per-element listener would have to be re-attached on every render, whereas the single ancestor listener survives; (2) it composes correctly with `BufferedRenderer` (the recycled `<tr>`s have no listeners of their own, so there's nothing to clean up); and (3) you resolve the record from the event target via the view (`getRecord`/the event args), never by stashing the record on the DOM node. Hand-attaching thousands of listeners is both a performance and a memory-leak anti-pattern that delegation eliminates.

#### Q42. [Theory] Selection models: contrast `RowModel`, `CellModel`, `CheckboxModel`, and `SpreadsheetModel`. Why is selection a pluggable concern?

Selection in an ExtJS grid is deliberately **decoupled** from the grid itself into a `selModel` (an `Ext.selection.Model` subclass), because what "selected" means is application-specific: a master/detail screen wants one selected *row*; a data-entry grid wants a selected *cell* to drive keyboard editing; a bulk-action screen wants *checkboxes* with multi-select; a data-analysis grid wants Excel-like *range* selection. Hard-coding one of these into the grid would force everyone into the same model, so it's pluggable via `selModel`/`selType`.

| Selection model | Unit of selection | Multi-select | Typical use |
|-----------------|-------------------|--------------|-------------|
| `RowModel` (default) | whole row(s) | yes (ctrl/shift) | master/detail, list selection |
| `CellModel`     | a single cell | no | cell-by-cell keyboard editing |
| `CheckboxModel` | rows via a checkbox column | yes | bulk actions, "select all" |
| `SpreadsheetModel` | rows, columns, **and** cell ranges | yes (drag ranges) | Excel-like grids, copy/paste ranges |

```javascript
Ext.create('Ext.grid.Panel', {
    selModel: {
        type: 'spreadsheet',     // range selection + clipboard
        rowSelect: true,
        columnSelect: true,
        checkboxSelect: true
    },
    plugins: { clipboard: true }, // pairs with spreadsheet for copy/paste
    /* ... */
});
```

The design lesson worth stating is that this is the **strategy pattern**: the grid delegates the "how is selection tracked and rendered" decision to an interchangeable object implementing a common interface (`select`, `deselect`, `getSelection`, selection-change events). That's why you can swap `selType: 'rowmodel'` for `'checkboxmodel'` with one config change and the rest of your code that listens to `selectionchange` keeps working. `SpreadsheetModel` is the most capable (it subsumes row, column, and range selection and integrates with the clipboard plugin) but also the heaviest; you pick the lightest model that satisfies the interaction the screen actually needs.

### 🔴 Expert — extended

#### Q43. [Theory] Walk through what happens from `Ext.application()` to a rendered Viewport. What is the full app bootstrap sequence?

The `Ext.application()` bootstrap is more involved than `new App()` and understanding it explains where to hook initialization and why certain things aren't available too early. The sequence, roughly:

```
Ext.application({ name, mainView, requires, ... })
  1. Ext.onReady fires        → DOM is parsed, framework core ready
  2. Loader resolves the app's `requires` + the Application class deps
  3. Application instance constructed
     ├─ profiles evaluated (which build/profile: classic vs modern, device)
     ├─ launch dependencies (`requires`, stores marked autoCreateViewport era)
  4. controllers (global Ext.app.Controller) instantiated → their init() runs
  5. launch() called          → app-level setup; create the mainView/Viewport
  6. mainView instantiated     → its ViewController.init(), ViewModel created
  7. Viewport renders          → layout context runs → boxReady
  8. controllers' onLaunch() runs (after views exist)
```

The two distinct controller hooks matter: a global controller's **`init()`** runs *before* the UI exists (set up routes, register events, request data), while **`onLaunch()`** runs *after* `launch()` has created the main views (safe to reference rendered components). The application's own **`launch()`** is where you typically create the `mainView` (or it's auto-created if you set `mainView: 'MyApp.view.main.Main'`). In MVVM apps, the main view's ViewController takes over instance-scoped wiring once the view is constructed.

Two expert-level points fall out. (1) **Routing** (`Ext.app.route`) is initialized during this sequence so that a deep-link URL (`#user/5`) can be dispatched once controllers are ready — which is why route handlers must tolerate being called very early in app life. (2) The **profile/toolkit decision** (Q13) is resolved at bootstrap: which compiled build (classic/modern) is served is decided before launch based on the device profile, so by the time `launch()` runs the correct toolkit's components are already the ones in scope. Knowing this timeline tells you exactly where to put auth checks (before `launch` creates protected views), data preloading (controller `init`), and DOM-dependent work (`onLaunch`/`boxReady`, never `init`).

#### Q44. [Theory] Compare the ExtJS data package to a modern client like Apollo/React-Query, and to Redux. Where does it lead and where does it lag in 2026?

ExtJS's data package (Model/Store/Proxy/Session) is a **client-side ORM with an identity map**, conceived years before today's data-fetching libraries, and comparing it sharpens what each paradigm optimizes for. ExtJS *leads* in built-in, batteries-included **collection operations**: a Store gives you sorting, multi-filtering, grouping, paging, aggregation, and dirty-tracking out of the box, tightly coupled to UI widgets via binding — you don't assemble these from primitives. The `Ext.data.Session` provides a real **unit of work** (identity map + transactional save batch) that React-Query/Apollo don't offer natively; you'd reach for a separate normalized cache and hand-rolled mutation batching.

Where it *lags* is the modern fetching/caching ergonomics that newer libraries treat as first-class. **React-Query/TanStack Query** centers on declarative server-state: automatic caching keyed by query, background refetching, stale-while-revalidate, request deduplication, retry/backoff, and window-focus refetch — ExtJS Stores have none of this automatically and you bolt it on. **Apollo** adds a normalized GraphQL cache with field-level reactivity and optimistic UI baked in; ExtJS proxies are REST/Ajax-centric (a GraphQL proxy is a custom job). **Redux** is a different axis — it's a predictable *application-state* container with time-travel debugging and a strict unidirectional flow; ExtJS's ViewModel binding is closer to MobX-style observable state than Redux's reducer discipline, trading auditability for less boilerplate.

| Concern | ExtJS data package | React-Query | Apollo | Redux |
|---------|--------------------|-------------|--------|-------|
| Built-in sort/filter/group/page | yes (Store) | no (you compute) | no | no |
| Identity map / unit of work | yes (Session) | partial (cache) | yes (normalized) | manual |
| Auto caching/refetch/dedupe | no | yes (core feature) | yes | no |
| UI binding | yes (two-way ViewModel) | hooks | hooks | selectors |
| Server protocol | REST/Ajax-first | transport-agnostic | GraphQL | agnostic |
| Devtools/time-travel | weak | good | good | excellent |

The honest 2026 read: for a *new* app you'd choose React-Query/Apollo because server-state caching, background revalidation, and the surrounding ecosystem are far ahead. For an *existing* ExtJS app, its integrated Store↔widget binding and Session are genuinely productive and not trivially replicated — which is exactly why migrations (Q20) often keep ExtJS grids on Stores while moving *fetching* concerns to a shared, framework-neutral data client that both stacks consume.

#### Q45. [Practical] How do you make a large ExtJS app accessible (WCAG/Section 508), and what are the framework-specific obstacles?

Accessibility in ExtJS is both helped and hindered by the framework abstracting the DOM. Modern ExtJS (6.0+) ships an **ARIA-compliant theme and accessibility package**: core components render appropriate `role`, `aria-*` attributes, and keyboard navigation (the `Ext.theme.triton`/`aria` package, focus management, and `FocusableContainer` behavior for toolbars/menus). The framework-specific obstacle is that because ExtJS generates deeply nested, non-semantic DOM (grids are `<div>`/`<table>` soup, not native `<select>`/`<input>` where you'd expect), accessibility depends almost entirely on the framework's ARIA implementation being correct and on you *not* breaking it with custom renderers and raw HTML.

The concrete checklist for a large app: (1) **Use the ARIA theme/build** and keep ExtJS current — older majors (4/5) have weak or no ARIA support and are effectively non-compliant. (2) **Provide labels**: every field needs a `fieldLabel` or `ariaLabel`; action-only icons (`actioncolumn`, tool buttons) need `tooltip`/`ariaLabel` because an icon `<div>` has no accessible name. (3) **Keyboard operability** — verify grids, menus, and dialogs are fully keyboard-navigable (arrow keys in grid, Esc closes windows, focus trapping in modal windows); ExtJS provides this but custom components must implement `FocusableContainer`/key maps. (4) **Custom renderers and `XTemplate` output must include ARIA and escape text** — raw `html` configs frequently strip semantics. (5) **Color contrast** in your SASS theme overrides (Q11) — a custom corporate palette can silently fail contrast ratios. (6) **Announce async changes** via live regions for things like grid reload or validation summaries, which sighted users see but screen-reader users miss.

```javascript
{ xtype: 'textfield', fieldLabel: 'Email',        // visible + programmatic label
  ariaLabel: 'Email address', allowBlank: false }

{ xtype: 'actioncolumn', items: [{
    iconCls: 'x-fa fa-trash',
    tooltip: 'Delete row',     // becomes the icon's accessible name
    ariaLabel: 'Delete row'
}] }
```

The hard truth to convey in an interview: ExtJS *can* meet WCAG 2.1 AA on modern versions with the ARIA package and disciplined labeling, but it's not automatic, custom components are where compliance silently breaks, and **automated scanners under-report** the dynamic, virtualized DOM — real assistive-technology testing (NVDA/JAWS/VoiceOver, keyboard-only) is mandatory, especially for virtualized grids where the buffered renderer means most rows aren't even in the DOM for a screen reader to see at any moment.

#### Q46. [Theory] How would you architect comprehensive testing for an ExtJS codebase — unit, component, and end-to-end? What's uniquely hard about testing ExtJS?

ExtJS testing splits cleanly along the MVVM seams, and the architecture should exploit that. **Unit tests** target the *logic* you can isolate from the DOM: ViewController handler methods, ViewModel formulas, Store/Model logic (validators, calculated fields, associations), and pure utility code. These run fast in Node/JSDOM or a headless browser with a framework like **Jest** or the Sencha-native **Siesta/Sencha Test** — the key is to write ViewControllers and Stores so their logic is callable without a rendered view (pass the record/store in, return a result), which keeps most logic DOM-free and quick to test.

**Component/integration tests** must run in a *real browser* (headless Chrome) because ExtJS's layout engine, rendering, and event system genuinely depend on a DOM with real measurements — you cannot fully fake `getHeight()`/layout in JSDOM. Here you instantiate a view, let the layout context and bind scheduler flush (Q30 — remember binding is async by a tick), then assert on rendered state via **`Ext.ComponentQuery`** rather than CSS/DOM selectors, because the generated DOM is volatile across versions while xtypes/`itemId`/`reference` are stable.

```javascript
// Component-level: drive via ComponentQuery + simulate via the event system.
var view = Ext.create('MyApp.view.user.UserForm', { renderTo: document.body });
var nameField = view.lookup('nameField');     // stable reference, not a CSS selector
nameField.setValue('Ada');

Ext.Function.defer(function () {               // let the bind scheduler flush
    var saveBtn = view.down('button[text=Save]');
    expect(saveBtn.isDisabled()).toBe(false);  // binding {!canSave} resolved
    view.destroy();                            // ALWAYS destroy — leak hygiene in tests too
}, 50);
```

**End-to-end tests** drive the deployed app. The uniquely hard part is **selector stability**: tools like Playwright/Selenium key off DOM, but ExtJS auto-generates ids (`button-1031`) that change run to run, and the DOM structure shifts across framework versions. The robust approach is to assign stable `itemId`/`reference`/`data-*` test hooks and target those, or use **Sencha Test's `ST.component()`** locators that query the *component* tree (the same ComponentQuery engine) rather than the DOM — far more resilient. Other ExtJS-specific testing challenges: the **async bind scheduler** (tests must wait a tick, not assert synchronously), **virtualized grids** (the row you want to assert on may not be in the DOM until you scroll it into view via the buffered renderer), and **component teardown** (every test must `destroy()` created components or the suite leaks and cross-contaminates — the same `mon`/`doDestroy` discipline from Q14 applies in tests). A pragmatic split: heavy unit coverage of ViewController/Store logic (fast, stable), a moderate band of headless component tests for binding/rendering, and a thin layer of E2E smoke tests on critical user journeys using component-aware locators.

#### Q47. [Theory] What is the difference between `statics`, `inheritableStatics`, and a `singleton` class in ExtJS?

These three address class-level (non-instance) concerns but with different inheritance and instantiation semantics. **`statics`** defines members on the *class constructor itself*, not the prototype — accessed as `MyClass.method()` and via `this.self.method()` from an instance. They are **not** inherited by subclasses' static namespace: a subclass does not automatically expose the parent's statics on its own constructor. **`inheritableStatics`** are the same idea but *are* propagated to subclass constructors, so `SubClass.sharedStatic` resolves. **`singleton: true`** is entirely different: it tells `Ext.define` to immediately *instantiate* the class once and replace the class reference with that single instance — you never call `Ext.create` on it, you use the object directly.

```javascript
Ext.define('MyApp.Money', {
    statics: {
        // utility tied to the type, not inherited by subclasses' statics
        format: function (cents) { return '$' + (cents / 100).toFixed(2); }
    },
    inheritableStatics: {
        // subclasses also get MySub.CURRENCY
        CURRENCY: 'USD'
    }
});
MyApp.Money.format(1599);   // "$15.99"  — called on the constructor

// A singleton: instantiated once, used as an object.
Ext.define('MyApp.Session', {
    singleton: true,
    token: null,
    isLoggedIn: function () { return !!this.token; }
});
MyApp.Session.token = 'abc';   // no Ext.create — it's already an instance
```

The interview distinction: use `statics` for type-bound helpers/constants that don't need to flow to subclasses (like a factory or a regex), `inheritableStatics` when a class hierarchy should share a static value/method down the chain, and `singleton` for app-wide services (a session holder, an event bus, a config registry). A common anti-pattern is using a `singleton` as a dumping ground for global mutable state that several views mutate — it works but recreates the global-coupling problems MVVM was meant to solve, and (like Q14) singletons holding references to views are a memory-leak vector.

#### Q48. [Theory] How does the Reader translate a server response into Model instances, and what do `rootProperty`, `totalProperty`, and `associationKey` control?

The **Reader** (`Ext.data.reader.Json` or `Xml`) is the proxy's inbound translator: it takes the raw HTTP response body and produces (a) an array of Model instances and (b) metadata like total count and success flag. The config knobs map directly to the *shape* of your backend's payload, and mismatches here are the single most common "the grid is empty even though the response looks fine" bug.

- **`rootProperty`** — the path (dot-notation supported) to the array of records inside the response. If your API returns `{ "data": [...] }`, set `rootProperty: 'data'`; if it returns a bare top-level array, leave it empty.
- **`totalProperty`** — where the *total* record count lives (for paging). With server paging the response returns one page of rows plus a total like `{ data: [...50 rows...], total: 5000 }` so the paging toolbar knows there are 100 pages.
- **`successProperty`** — a boolean flag the server sets to signal logical success vs. failure independent of HTTP status.
- **`messageProperty`** — where an error/info message lives, surfaced via `operation.getError()`.

```javascript
// Backend payload:
// { "ok": true, "result": { "items": [ {...}, {...} ] }, "count": 5000 }
reader: {
    type: 'json',
    rootProperty: 'result.items',   // dotted path into nested data
    totalProperty: 'count',
    successProperty: 'ok'
}
```

For **associations**, the Reader does nested materialization: if a `User` model `hasMany Orders`, and the response embeds orders inside each user, `associationKey` (or the association's configured name) tells the Reader where the child array sits in each parent record, and it constructs the child Model instances and links them so `user.orders()` returns a ready Store. This is powerful — one request hydrates a whole object graph — but it's also where over-fetching creeps in (embedding deep associations bloats payloads). The mechanism to internalize: the Reader is purely a *mapping* layer; it does not know or care about HTTP (that's the proxy) — it only transforms an already-fetched body into typed records, which is why the same Reader works for `ajax`, `rest`, and `memory` proxies alike.

#### Q49. [Practical] Compare the grid editing plugins: `CellEditing`, `RowEditing`, and `Ext.grid.plugin.RowWidget`/widget columns. How do you choose?

Editing in a grid is a plugin concern (like selection in Q42), and the three approaches encode different editing UX and have different validation/commit semantics. **`CellEditing`** turns a single cell into an editor (`Ext.grid.plugin.CellEditing`) on click/double-click; the user edits one value, tabs to the next cell, and each cell commits independently. **`RowEditing`** (`Ext.grid.plugin.RowEditing`) opens an inline editing *bar* across the whole row with all editable fields plus Update/Cancel buttons, committing the entire row atomically. **Widget columns / `RowWidget`** embed live always-on components (a slider, a progressbar, a button, even a mini-grid in `RowWidget`) into cells rather than edit-on-demand fields.

| Plugin | Edits | Commit unit | Validation feel | Best for |
|--------|-------|-------------|------------------|----------|
| `CellEditing` | one cell at a time | per cell | per-field, immediate | fast spreadsheet-like entry |
| `RowEditing` | whole row in a bar | per row (atomic) | row-level, on Update | record forms inline, related fields |
| widget column | always-on widget | via the widget's own binding | n/a (live control) | interactive controls, gauges, actions |
| `RowWidget` plugin | expandable detail under a row | n/a | n/a | master/detail without a separate panel |

```javascript
// Row editing: atomic per-record commit with built-in buttons.
Ext.create('Ext.grid.Panel', {
    plugins: { rowediting: { clicksToEdit: 1 } },
    columns: [
        { text: 'Name',  dataIndex: 'name',  editor: { allowBlank: false } },
        { text: 'Price', dataIndex: 'price', editor: { xtype: 'numberfield', minValue: 0 } }
    ]
});
```

The decision: choose **CellEditing** when users do rapid, cell-by-cell data entry (think Excel) and each field is independent; choose **RowEditing** when fields within a row are interdependent and you want one atomic commit + a clear Cancel (avoids half-edited records); choose **widget columns** when the cell should host a persistent interactive control rather than an editor; choose **RowWidget** for inline master/detail. A correctness note: `CellEditing`'s per-cell commit means a row can be left in a partially-valid intermediate state, so if your record has cross-field validation (end date after start date), `RowEditing` is safer because it validates and commits the whole record together. Both `CellEditing` and `RowEditing` integrate with Model `validators` (Q9), showing field errors inline before commit.

#### Q50. [Theory] How does ExtJS routing work, and how does it differ from React Router or Angular Router?

ExtJS routing (`Ext.app.route`) maps URL **hash** fragments to controller/ViewController methods, giving deep-linking and back/forward support in a single-page app. You declare a `routes` block in a Controller or ViewController; when the hash changes (`#user/5/edit`), ExtJS parses it against the registered route patterns and invokes the mapped **action method** with the captured tokens as arguments. It's built on `Ext.util.History` (which abstracts hashchange / pushState) and a `before`/action two-phase model that supports async guards.

```javascript
Ext.define('MyApp.view.main.MainController', {
    extend: 'Ext.app.ViewController',
    routes: {
        'user/:id': 'showUser',
        'user/:id/edit': {
            before: 'checkCanEdit',   // async guard runs first
            action: 'editUser'
        }
    },
    checkCanEdit: function (id, action) {
        canEdit(id).then(action.resume, action.stop);  // gate the route
    },
    showUser: function (id) { /* load + display user `id` */ },
    editUser: function (id) { /* open editor */ }
});
```

The key architectural difference from React Router / Angular Router is that ExtJS routing is **imperative and action-oriented**, not declarative/component-driven. In React Router you declare `<Route path>` elements and the router *renders the matching component tree* — the route IS the UI structure. In ExtJS, a route fires a *method* that imperatively does whatever you code (create a view, set a card layout's `activeItem`, load a store); there's no automatic "this URL renders that component." Angular's router sits in between with declarative route config plus guards/resolvers — ExtJS's `before` phase is the analog of an Angular guard/resolver, supporting async `resume`/`stop`. A second difference: ExtJS historically defaults to **hash-based** routing (`#...`) for broad legacy-browser support and zero server config, whereas modern SPA routers default to the HTML5 History API (clean paths) and need server fallback config. The consequence for migration (Q20): because ExtJS routes are method calls rather than component declarations, bridging them to a React Router shell means translating "hash → ExtJS action" into "path → React route," which is part of the shared-routing-boundary risk called out earlier.

#### Q51. [Theory] What does the `Ext.mixin.Bindable` mixin provide, and what is the role of `publishes` and `twoWayBindable`?

`Ext.mixin.Bindable` is the mixin that makes a component participate in the ViewModel binding system (Q30) on the *consuming* side — it gives components their `bind` config and the machinery to subscribe to ViewModel stubs and react when bound values change. Most user-facing components mix it in. The two configs that frequently confuse people are `publishes` and `twoWayBindable`, which govern the *outbound* direction — how a component pushes its own state *into* the ViewModel.

By default a component's config can be bound *inbound* (ViewModel → component) for free, but only specific configs are allowed to flow *outbound* (component → ViewModel). **`twoWayBindable`** is a list of config names that, when bound, become bidirectional — when the component changes that value (user types in a field), it writes back to the ViewModel. For a textfield, `value` is two-way bindable by default, which is why `bind: '{user.name}'` updates the record as the user types. **`publishes`** declares which of a component's configs are *published* to the ViewModel automatically so *other* bindings can depend on them — e.g., publishing a grid's `selection` so a detail panel can `bind: '{mainGrid.selection}'`.

```javascript
Ext.define('MyApp.field.Rating', {
    extend: 'Ext.form.field.Base',
    mixins: ['Ext.mixin.Bindable'],   // (Base already includes binding; shown for clarity)
    config: { rating: 0 },
    twoWayBindable: ['rating'],        // user changes flow back to the ViewModel
    publishes: ['rating']              // other bindings may depend on {thisField.rating}
});

// Elsewhere a panel reacts to the published value:
{ xtype: 'displayfield', bind: 'Stars: {ratingField.rating}' }
```

The reason this is gated rather than everything-two-way is **performance and intent**: if every config of every component were published and bidirectional, the bind scheduler (Q30) would track an enormous, mostly-useless dependency graph and you'd get accidental write-backs. By explicitly listing `twoWayBindable`/`publishes`, you opt specific configs into the reactive graph. When you build a *custom* component that should drive a ViewModel (a custom picker, a rating widget), declaring these is exactly what makes `bind` work in both directions — forgetting them is why "my custom field binds in but never writes back" bugs happen.

#### Q52. [Practical] Explain how drag-and-drop works in ExtJS and the components involved (`Ext.dd`, view drag/drop plugins). What are the moving parts?

ExtJS drag-and-drop is layered on a low-level `Ext.dd` package — a set of cooperating singletons and classes managed by `Ext.dd.DragDropManager` (DDM), the central registrar that tracks all drag sources and drop targets and routes mouse/touch events during a drag. The primitives are **`DragSource`/`DragZone`** (things you can pick up — a Zone manages *many* draggable items within one element, like grid rows), **`DropTarget`/`DropZone`** (things you can drop onto, again Zone = many sub-targets), and **drag "data"** (an arbitrary object the source attaches describing what's being dragged, which the target inspects to decide accept/reject).

For everyday cases you rarely touch `Ext.dd` directly — higher-level plugins wrap it: grids use `Ext.grid.plugin.DragDrop` (reorder rows or drag between grids), trees use `Ext.tree.plugin.TreeViewDragDrop` (the rich one — reparenting nodes, drop-between vs drop-on indicators), and dataviews have `Ext.view.plugin.DragDrop`.

```javascript
Ext.create('Ext.tree.Panel', {
    viewConfig: {
        plugins: {
            ptype: 'treeviewdragdrop',
            appendOnly: false,         // allow drop-between (reorder), not only into
            allowParentInserts: true
        }
    },
    listeners: {
        // validate/observe the drop via the zone's events:
        beforedrop: function (node, data, overModel, dropPos) {
            return overModel.get('acceptsChildren');  // reject if false
        }
    }
});
```

The moving parts to articulate: (1) the **DDM** is the conductor — it does hit-testing each mousemove to find which DropZone the cursor is over and asks that zone whether it accepts the current drag data; (2) **proxy element** — during the drag a ghost/proxy follows the cursor (the DDM positions it) so the original stays put until commit; (3) **accept/reject + position feedback** — zones return whether they accept and at what position (before/after/append), driving the visual drop indicators; (4) **the data contract** — because the source attaches a data object and the target reads it, you can drag between completely different components (a tree node onto a grid) as long as both agree on the data shape. The performance/leak note: DnD attaches global document-level listeners during a drag and cleans them up on drop/cancel; custom DragZones must be destroyed with their component (the `mon`/`doDestroy` discipline again) or they leak listeners into the DDM.

#### Q53. [Theory] When should you use a `Ext.view.View` (DataView) instead of a `Grid`, and how do they relate internally?

A `Grid` and a `DataView` (`Ext.view.View`, xtype `dataview`) are both **store-bound, template-driven list renderers**, and in fact a Grid's row rendering is conceptually a specialized DataView with a tabular template plus columns, headers, sorting UI, and selection chrome layered on. The distinction is about *output shape and freedom*: a Grid renders a fixed **tabular** layout (rows × typed columns with headers) and brings the whole apparatus of column resizing/sorting/filtering/editing; a DataView renders **arbitrary HTML per record** via an `itemTpl` (an `XTemplate`, Q31), giving you total layout freedom — a tile/card gallery, a thumbnail grid, a chat list, a custom widget repeated per record.

```javascript
Ext.create('Ext.view.View', {
    store: imagesStore,
    itemSelector: 'div.thumb',          // which generated nodes are "items"
    tpl: new Ext.XTemplate(             // arbitrary per-record HTML
        '<tpl for=".">',
            '<div class="thumb">',
                '<img src="{url:htmlEncode}"/><span>{title:htmlEncode}</span>',
            '</div>',
        '</tpl>'
    ),
    listeners: { itemclick: function (v, rec) { open(rec); } }
});
```

The decision rule: use a **Grid** when the data is naturally tabular and users need column-oriented operations (sort/filter by column, inline cell editing, column reorder/resize, aggregation) — i.e., a data table. Use a **DataView** when you need a *non-tabular* visual representation (cards, tiles, media gallery, custom layouts) and the per-item structure is richer than cells in a row. Internally both share the same foundations: a `store` for data, an `XTemplate`/renderer for markup, `itemSelector` semantics to map DOM nodes back to records, the same selection-model pluggability, and crucially the **same buffered-rendering / virtualization** support — so a DataView over 50k tiles needs `BufferedRenderer`-style virtualization just as a grid does (Q39). The relationship-internals point worth making: because the grid is "DataView + columns + chrome," features like selection, item events, and virtualization live in the shared base, which is why your knowledge transfers directly between the two.

#### Q54. [Theory] Why does ExtJS ship its own `Ext.Array`, `Ext.Object`, `Ext.String`, and `Ext.Function` utilities, and are they still relevant given modern JS?

These utility namespaces exist for the same historical reason as the rest of the framework: they were written in the **ES5 era** (and partly ES3-targeted) when the language lacked many array/object methods, cross-browser behavior diverged wildly (especially old IE), and there was no standard for things like debouncing or deep merging. `Ext.Array` predates or normalizes `map`/`filter`/`reduce`/`includes`; `Ext.Object` provides `merge` (deep), `each`, `toQueryString`; `Ext.String` provides `htmlEncode`/`htmlDecode`, `format`, `trim`, `ellipsis`, `leftPad`; `Ext.Function` provides `createBuffered` (debounce), `createThrottled`, `bind`, `createDelayed`, and `interceptAfter`. Many were the *only* cross-browser way to do these operations when the framework was written.

```javascript
// Still genuinely useful (no exact native equivalent):
var search = Ext.Function.createBuffered(doSearch, 300);   // debounce
var html   = Ext.String.htmlEncode(userInput);             // XSS-safe (Q31)
var merged = Ext.Object.merge({}, defaults, overrides);    // DEEP merge (not Object.assign)
var qs     = Ext.Object.toQueryString({ a: 1, b: [2, 3] });

// Largely superseded by native ES6+:
Ext.Array.map(arr, fn);     // → arr.map(fn)
Ext.Array.contains(arr, x); // → arr.includes(x)
Ext.String.trim(s);         // → s.trim()
```

The honest 2026 assessment: the **collection-iteration** helpers (`Ext.Array.map`, `each`, `contains`) are now redundant with native `Array.prototype` methods and you should prefer native for readability and engine optimization. But several remain *not* trivially replaceable and stay relevant: `Ext.Function.createBuffered`/`createThrottled` (debounce/throttle aren't in the language), `Ext.String.htmlEncode` (no native HTML-escape, and it's a security primitive), `Ext.Object.merge` (deep merge — `Object.assign`/spread are *shallow*), and `Ext.Object.toQueryString`. The pragmatic guidance for a team: use native methods for plain iteration and let the build tree-shake unused Ext utilities, but keep using the Ext helpers where they provide behavior the language still lacks — and never hand-roll HTML escaping when `Ext.String.htmlEncode` exists.

#### Q55. [Theory] What changed across the major ExtJS versions (3 → 4 → 5 → 6 → 7), and why does version matter so much for an interview?

Knowing the version arc matters because ExtJS APIs and architecture shifted substantially between majors, and a candidate who conflates them will give answers that are simply wrong for the version a team runs. The high-level arc:

| Version (era) | Defining changes |
|---------------|------------------|
| **Ext 3** (~2009) | Pre-`Ext.define` class system (`Ext.extend`); component-heavy but no formal MVC; `xtype` and the layout engine established. |
| **Ext 4** (2011) | New **dynamic class system** (`Ext.define`, mixins, loader, config), rewritten **data package** (Model/Store/Proxy), introduction of **MVC** (`Ext.app.Controller`), SASS theming. A near-total rewrite — code did not port cleanly from 3. |
| **Ext 5** (2014) | **MVVM**: `ViewController` + `ViewModel` + **two-way data binding**; routing (`Ext.app.route`); tablet/touch support improvements. MVC still supported but MVVM became the recommendation. |
| **Ext 6** (2015) | **Merger of Ext JS and Sencha Touch** into one product → **Classic + Modern toolkits** and **universal apps** (Q13); unified theming; absorbed Sencha Touch's mobile components. |
| **Ext 6.2–6.7 / 7.x** (2016–) | Pivot grid, modern-toolkit feature parity push, **open tooling** (npm packages + webpack plugin, Q21) moving away from Java Sencha Cmd, `@sencha/ext-react`/`ext-angular` bridges, ongoing ARIA/accessibility (Q45). |

The two most consequential dividing lines for interviews: (1) the **Ext 3 → 4** boundary, because the entire class system, data package, and app architecture were rewritten — almost nothing about Ext 3's `Ext.extend`/old data API transfers; and (2) the **Ext 4 → 5** boundary, because MVC (global singleton controllers) gave way to MVVM (instance-scoped ViewController + binding), which changes how you wire everything and is the single most common "the old code uses X but we should use Y" discussion on a real codebase (Q7, Q23). The practical takeaway to voice: always **establish the version first** — advice about ViewModels and binding is meaningless on an Ext 4 MVC app, the Modern toolkit doesn't exist before 6, and open tooling/npm packages assume 6.7+. Version literacy is itself a signal of real ExtJS experience versus surface familiarity.

#### Q56. [Practical] How do you internationalize (i18n) and localize an ExtJS app, including dates, numbers, and RTL?

ExtJS i18n has three distinct layers, and a robust setup addresses all of them. (1) **Framework-provided locale packs** — Sencha ships locale files (`ext-locale-*`) that translate built-in component strings (the paging toolbar's "Page X of Y", grid menu labels, date-picker month names, validation messages) and set locale-appropriate **date/number/currency formats** by overriding `Ext.util.Format` and `Ext.Date` defaults. You include the matching locale build for the user's language. (2) **Your application's own strings** — ExtJS doesn't impose a message-catalog system, so teams typically keep a per-locale bundle (often a generated JS object or JSON) and reference keys, swapping the bundle at build or load time.

```javascript
// App string bundle pattern (swap the file per locale at build/load):
Ext.define('MyApp.locale.Strings', {
    singleton: true,
    SAVE: 'Save', CANCEL: 'Cancel',
    GREETING: 'Hello, {0}'
});
// Usage with parameter substitution:
button.setText(MyApp.locale.Strings.SAVE);
var msg = Ext.String.format(MyApp.locale.Strings.GREETING, user.get('name'));

// Locale-aware formatting (driven by the loaded ext-locale pack):
Ext.util.Format.currency(1599.5);          // "$1,599.50" or "1.599,50 €" per locale
Ext.Date.format(new Date(), 'l, F j, Y');  // weekday/month names localized
```

(3) **RTL (right-to-left) layout** for Arabic/Hebrew — ExtJS supports RTL via the **`Ext.rtl.*`** mixins and an `rtl: true` config (historically a separate `theme-*-rtl` build or the `rtl` package). When enabled, the framework mirrors layouts, scrollbar sides, alignment, and icon positions. This is more than CSS `direction: rtl` because ExtJS computes pixel positions in JS (the layout engine, Q15/Q32), so RTL must be handled at the framework level, not just in stylesheets.

The framework-specific obstacles to mention: because formatting is centralized in `Ext.util.Format`/`Ext.Date`, changing locale at *runtime* isn't seamless — like theming (Q11), the cleanest approach is to load the correct locale build per session rather than re-localize a live app, and **already-rendered** components won't retranslate without re-creation. So the standard pattern is: detect locale at app bootstrap (Q43), load the matching framework locale pack + your string bundle, set RTL if needed, *then* launch the UI. Hard-coding user-facing strings in views (instead of going through the bundle) is the anti-pattern that makes a late i18n effort enormously expensive — enforce string-bundle usage in code review from day one.

#### Q57. [Theory] How does focus management work in ExtJS, and what is a `FocusableContainer`? Why does it matter beyond accessibility?

Focus management is the discipline of controlling *which element holds keyboard focus* and how focus moves — and ExtJS implements it centrally rather than leaving it to raw browser tab order, because its components render complex nested DOM where the naive `tabindex` flow would be unusable. The framework tracks the focused component, restores focus sensibly after operations (e.g., after a menu closes or a record is deleted), and routes keyboard navigation through component-aware logic.

The key concept is **`Ext.util.FocusableContainer`** (a mixin used by toolbars, button groups, menus, segmented buttons, etc.): it implements the **roving-tabindex** pattern. Instead of every child button being individually tab-stoppable (which would force a user to Tab through 15 toolbar buttons to leave the toolbar), the *container* is one tab stop, and **arrow keys** move focus among its children. So Tab enters the toolbar onto one button, arrows move within it, and Tab again leaves the entire toolbar. This is exactly the WAI-ARIA authoring-practice for toolbars/menubars, and it's why ExtJS toolbars feel navigable with a keyboard.

```javascript
// FocusableContainer behavior is built into toolbars; you can opt a custom
// container into it:
Ext.define('MyApp.view.IconBar', {
    extend: 'Ext.container.Container',
    mixins: ['Ext.util.FocusableContainer'],
    enableFocusableContainer: true,   // one tab stop; arrows move within
    layout: 'hbox',
    items: [ /* many buttons — arrow-navigable, single tab stop */ ]
});
```

Why it matters **beyond accessibility**: focus management is also about **correctness and UX in dynamic UIs**. When a grid row is deleted, where should focus go? When a modal window closes, focus must return to whatever opened it (focus *trapping* + *restoration*), or keyboard users get stranded and even mouse users lose context. ExtJS's focus manager handles "focus reversion" so closing a window or destroying the focused component moves focus to a sensible neighbor rather than letting it fall to `document.body` (which breaks keyboard flow and screen-reader context). For an interviewer, the depth signal is recognizing that (1) ExtJS deliberately overrides native tab order with roving tabindex via `FocusableContainer`, (2) modal windows trap and restore focus, and (3) virtualized grids (Q39) complicate this because the focused row's DOM may be recycled on scroll — the framework must track focus by *record/position*, not by the (transient) DOM node.

#### Q58. [Practical] How do you decide between a globally-defined (application) Store and a ViewModel-scoped/instance Store, and what are the consequences of each?

ExtJS lets you define a Store in two fundamentally different scopes, and choosing wrong causes either stale-data bugs or memory leaks. A **global/application Store** is defined once (often via `stores: [...]` on the Application, or as a `singleton`-like shared store) and lives for the app's lifetime — every view that references it by name shares the *same* instance and data. A **ViewModel-scoped (instance) Store** is declared inside a view's ViewModel `stores` block (or created in the ViewController) and is created *with* the view and destroyed *with* it, so each instance of the view gets its own store and data.

```javascript
// Instance-scoped: each view instance gets its own store, auto-destroyed with it.
Ext.define('MyApp.view.user.UserModel', {
    extend: 'Ext.app.ViewModel',
    alias: 'viewmodel.user',
    stores: {
        orders: {                         // bound to this view instance
            model: 'MyApp.model.Order',
            filters: [{ property: 'userId', value: '{currentUser.id}' }],  // reactive!
            autoLoad: true
        }
    }
});
// Bind a grid to it:  { xtype: 'grid', bind: { store: '{orders}' } }
```

| Aspect | Global/application store | ViewModel-scoped store |
|--------|--------------------------|------------------------|
| Lifetime | whole app | tied to view instance |
| Sharing | one instance, shared everywhere | isolated per view instance |
| Reactive filters/params | manual | can bind to ViewModel data (`'{currentUser.id}'`) |
| Memory | persists (intentional) | auto-destroyed with view (leak-safe) |
| Risk | stale data; cross-view coupling | duplicate loads if reused widely |

The decision rule: use a **global store** for genuinely shared, app-wide reference data that many screens read and that shouldn't reload constantly — lookup lists (countries, currencies), the current user's permissions, a shared notification feed. Use a **ViewModel-scoped store** for data that *belongs to a particular screen instance*, especially anything that should reload/refilter based on that instance's context (the orders for *the user this tab is showing*) — and critically, when you open the same view in multiple tabs, each needs its own data, which only an instance store provides. The consequences tie back to earlier themes: a global store referenced by transient views is a classic **leak** vector if those views attach listeners to it without `mon` (Q14), and it causes **cross-view interference** (one screen filters the shared store and another screen's grid suddenly shows filtered data). Conversely, instance stores integrate beautifully with binding — their `filters`/proxy params can reference ViewModel data (`'{currentUser.id}'`) so the store automatically reloads when the bound value changes, which is the idiomatic MVVM pattern and the main reason to prefer them for screen-specific data.

## 🧩 Extended Questions — Supplemental Set A: Practical & Theory

### 🟢 Basic — extended

#### Q59. [Practical] What is the `microloader` and `app.json`/`bootstrap.json`, and why does a production ExtJS app load differently from a dev build?

When you run an ExtJS app, the very first thing the browser executes is the **microloader** — a tiny bootstrap script (`Ext.microloader`) injected at the top of the page that reads a manifest and decides *what* to load and *how*. In development that manifest is `bootstrap.json`/`app.json` and the microloader pulls hundreds of individual `.js` and `.css` files on demand via `Ext.Loader` (great for debugging — each class is a separate file with its own source). In a production build, Sencha Cmd (or the webpack plugin) compiles everything into a couple of minified bundles (`app.js`, `app.css`) and rewrites the manifest so the microloader loads those concatenated artifacts instead. This is why "it works in dev but the production build is broken" bugs are so common — the two load paths are genuinely different.

`app.json` is the central project descriptor: it lists `requires` (packages/themes), the `js`/`css` arrays, build profiles (classic/modern), output paths, locale, and cache settings. The microloader also handles **cache busting** by appending content hashes and supports **AppCache/manifest** style offline behavior in older versions.

```json
{
  "name": "MyApp",
  "requires": ["font-awesome"],
  "js":  [{ "path": "app.js", "bundle": true }],
  "css": [{ "path": "${build.id}-all.css", "bundle": true }],
  "output": { "base": "build/${build.environment}" }
}
```

The interview point: a class that loads fine in dev (because the loader fetches it by name) can be **missing from the production bundle** if you never declared a `requires`/`uses` for it (Q28) — the compiler only bundles what the dependency graph references. So "undefined is not a constructor" only in production almost always means a missing dependency declaration the dev-mode loader was silently papering over.

#### Q60. [Theory] What is the difference between `hidden`, `collapsed`, and `destroyed`/removed for a component, and how does each affect the DOM and memory?

These three states look similar to a user but are completely different to the framework, and confusing them causes both bugs and leaks. **`hidden`** (`component.hide()`/`hidden: true`) keeps the component fully alive — its instance, its DOM, its listeners, and its store all still exist; it's just visually suppressed (typically `display:none`). Showing it again is instant because nothing was torn down. **`collapsed`** applies to panels with a header: the *body* is hidden/animated away but the panel and header remain, and the component is still fully instantiated. **`destroyed`** (`component.destroy()`) is terminal — the DOM is removed, listeners are unbound, child components are destroyed, and the instance is unusable; you'd have to create a new one.

The memory consequence is the crux. A `card` layout (Q32) only renders the active card but the inactive cards still *exist* in memory unless configured otherwise. A `tabpanel` whose tabs are hidden (not destroyed) on switch keeps every tab's full component tree and store in memory — which is exactly the leak pattern from Q14 when users open hundreds of tabs over a day.

```javascript
// Tab that is torn down (freed) when closed, not just hidden:
tabpanel.add({ title: 'Report', closable: true /* closeAction defaults vary */ });
tabpanel.setActiveTab(0);
// Force teardown on close for memory-heavy tabs:
tab.tab.on('click', null); // illustrative — prefer:
panel.closeAction = 'destroy';   // close → destroy (frees memory)  vs  'hide' (keeps it)
```

The decision: use `hide` for cheap, frequently-reopened, lightweight panels where re-render cost matters more than memory; use `destroy` (`closeAction: 'destroy'`) for heavy, rarely-revisited views (a big grid + charts) so closing actually frees memory. Knowing that *hidden ≠ freed* is the single most important fact behind ExtJS's long-session memory growth.

#### Q61. [Practical] How do you submit and validate an `Ext.form.Panel`, and what is the difference between a form's `submit()` and saving a bound record?

An `Ext.form.Panel` wraps a `Ext.form.Basic` (`getForm()`) that aggregates all child fields and provides form-level operations: `isValid()` (runs each field's validators and returns the aggregate), `getValues()` (a plain object of name→value), `markInvalid()`/`getErrors()`, and `submit()`. The classic flow validates client-side first, then submits:

```javascript
var form = formPanel.getForm();
if (form.isValid()) {
    form.submit({
        url: '/api/users',
        success: function (f, action) { Ext.Msg.alert('Saved', action.result.msg); },
        failure: function (f, action) {
            if (action.failureType === 'server') {
                f.markInvalid(action.result.errors);  // server returns field-level errors
            }
        }
    });
}
```

There are two distinct persistence philosophies and choosing between them is a real architectural decision. **`form.submit()`** is the *form-centric* path: it POSTs the field values directly (multipart for file uploads), and the server returns `{ success, errors }`. It's simple and great for stateless "fill this form and send it" screens. **Record-based saving** is the *data-package* path (Q5, Q38): the form is `loadRecord(record)`-bound, the user edits, you call `form.updateRecord(record)` then `record.save()` (going through the model's proxy/writer), which integrates with dirty-tracking, associations, and a `Session`.

The trade-off: form `submit()` is decoupled from the data package and fine for one-off forms, but it bypasses Stores, so a grid showing the same data won't update automatically. Record saving keeps the Store/grid/ViewModel in sync (the edited record is the *same* instance bound everywhere) and supports optimistic UI and batch saves — at the cost of more setup. On MVVM screens you almost always prefer record-based saving; `form.submit()` survives mainly in login screens, file uploads, and legacy code.

#### Q62. [Theory] What is a `combobox`'s `queryMode`, and how do `local` vs `remote` querying, `minChars`, `queryDelay`, and `typeAhead` interact?

A `combobox` is a text field bound to a Store that filters its dropdown as the user types. `queryMode` decides *where* that filtering happens, mirroring the store local/remote distinction (Q33). With **`queryMode: 'local'`** the entire option list is loaded once into the store and typing filters it **in memory** (instant, no network) — correct for small, bounded lists (countries, statuses). With **`queryMode: 'remote'`** each keystroke (subject to throttling) sends the typed text to the server as a query parameter and the server returns the matching subset — necessary for large or unbounded datasets (search 2M products) where loading everything client-side is impossible.

```javascript
{
    xtype: 'combobox',
    fieldLabel: 'Customer',
    store: customerStore,
    displayField: 'name',
    valueField: 'id',
    queryMode: 'remote',      // server filters
    minChars: 3,              // don't query until 3 chars typed
    queryDelay: 300,          // debounce keystrokes (ms)
    typeAhead: true,          // auto-complete the first match inline
    forceSelection: true,     // value must come from the list, no free text
    queryParam: 'q'           // server receives ?q=<typed>
}
```

The configs interact to control network chattiness and UX. `minChars` prevents firing a query on the first keystroke (a 1-char query against millions of rows is useless and expensive); `queryDelay` debounces so a fast typist sends one request per pause rather than per character (the same debounce idea as Q16); `typeAhead` improves perceived speed by completing the inline text. The decisive trade-offs: **local** gives zero-latency filtering but pays full load cost upfront and is wrong for huge lists; **remote** scales to any size but adds latency per query and requires server-side filtering. A subtle production bug is `queryMode: 'local'` on a store that only loaded one page of a remote dataset — the combo then "can't find" valid values that exist on the server but weren't loaded, identical in spirit to the local-filter-on-paged-data trap from Q33. `forceSelection` plus `valueField`/`displayField` ensures you persist the id while showing the label.

#### Q63. [Practical] How do you show a loading mask and handle errors uniformly across an app's Ajax calls?

Two UX concerns recur in every data-driven ExtJS app: telling the user "something is loading" and handling failures consistently. For masking, components support `setLoading(true)`/`mask()` and Stores can drive a `LoadMask` automatically. The idiomatic approach is to bind a mask to the store's load lifecycle so it appears on `beforeload` and clears on `load`:

```javascript
// Per-component mask tied to a store's load cycle:
grid.setLoading(true);
store.load({ callback: function () { grid.setLoading(false); } });

// Or a LoadMask bound to the store (auto show/hide):
new Ext.LoadMask({ target: grid, store: store, msg: 'Loading orders…' });
```

For *uniform* error handling, the wrong approach is duplicating `failure:` callbacks everywhere. The right approach centralizes it. `Ext.Ajax` is a singleton you can hook globally: listen to its `requestexception` event (or override the proxy, Q37) to catch every failed request in one place — show a toast, log to your telemetry, and handle 401 (redirect to login) / 403 / 5xx uniformly.

```javascript
Ext.Ajax.on('requestexception', function (conn, response, options) {
    if (response.status === 401) { MyApp.auth.redirectToLogin(); return; }
    if (response.status >= 500) { MyApp.telemetry.logError(response); }
    Ext.toast({ html: 'Request failed (' + response.status + ')', align: 't' });
});
```

The trade-off to articulate: a global handler keeps error UX consistent and prevents the "every developer handles failures slightly differently" sprawl, but you still want *local* handling for cases needing context (a form that should `markInvalid` specific fields, Q61). The pattern is **global for cross-cutting concerns** (auth redirects, telemetry, generic toasts) and **local for domain-specific recovery**. A common mistake is masking with `setLoading` but forgetting to clear it on failure — always clear the mask in a `callback`/`finally`-style path, not only on success, or the UI hangs masked after an error.

### 🟡 Intermediate — extended

#### Q64. [Theory] What is the `Writer` and how do `writeAllFields`, `allowSingle`, and the proxy's batch mechanism control what gets sent to the server on save?

The **Writer** (`Ext.data.writer.Json`/`Xml`) is the proxy's *outbound* translator — the mirror image of the Reader (Q48). When a Store syncs or a record saves, the Writer serializes the record(s) into the request body in the shape your backend expects, and several configs control exactly *what* and *how much* is sent, which matters for both correctness and payload size.

- **`writeAllFields`** — when `false` (the default in modern versions), the Writer sends **only the changed (dirty) fields** plus the id, producing a minimal PATCH-style payload; when `true`, it sends the *entire* record every time. Sending only dirty fields is smaller and lets the server do partial updates, but requires a backend that understands partial payloads.
- **`allowSingle`** — when `true` (default), a single-record operation sends a bare object `{...}`; when `false`, even one record is wrapped in an array `[{...}]`, which some bulk-oriented APIs require.
- **`rootProperty`/`writeRecordId`/`nameProperty`** — control envelope wrapping and id inclusion.

```javascript
proxy: {
    type: 'ajax',
    url: '/api/orders',
    api: {                       // distinct endpoints per CRUD verb
        create:  '/api/orders',
        read:    '/api/orders',
        update:  '/api/orders/update',
        destroy: '/api/orders/delete'
    },
    writer: { type: 'json', writeAllFields: false, allowSingle: false, rootProperty: 'records' }
}
```

The batch mechanism is the higher-level story: when you call `store.sync()` (or a `Session.getSaveBatch()`, Q38), the proxy groups all pending changes into an **`Ext.data.Batch`** of operations ordered create → update → destroy, and executes them — by default as separate requests per operation type, optionally batched into one. The trade-offs: `writeAllFields: false` minimizes bandwidth and enables true partial updates but can mask "this field was intentionally cleared" if the backend treats absent-vs-null differently; `writeAllFields: true` is simpler and unambiguous but heavier. For high-frequency saves (an editable grid syncing on every cell edit), dirty-only writes plus a batched sync dramatically cut traffic; for a backend that wants the full entity every time, you flip `writeAllFields` on. The Writer/Reader symmetry is the key mental model: Reader maps *in*, Writer maps *out*, and both are pure transformers decoupled from the proxy's transport.

#### Q65. [Practical] An editable grid must save changes to the server. Walk through dirty tracking, `commit`, `reject`, and `store.sync()` — and what happens if the server rejects one record in a batch.

Every ExtJS Model tracks its own modification state. When you `record.set('price', 10)`, the record becomes **dirty** (`record.dirty === true`) and the changed field's previous value is stashed in `record.modified`. The Store keeps a list of `getModifiedRecords()` (dirty), `getNewRecords()` (phantom — created client-side, no server id yet), and `getRemovedRecords()`. The grid typically shows a red dirty-marker on changed cells. Nothing has hit the server yet — this is purely client-side bookkeeping.

`store.sync()` is what persists: it asks the proxy to build a **batch** (Q64) of create/update/destroy operations from those three lists and sends them. On success per record, the framework calls **`record.commit()`** — which clears `dirty`, empties `modified`, removes the dirty marker, and fires `update` with the commit action. If you instead want to *discard* local edits, **`record.reject()`** restores each field from `modified` and clears dirty, reverting the UI.

```javascript
grid.getStore().sync({
    success: function (batch) { Ext.toast('All changes saved'); },
    failure: function (batch, options) {
        // batch.exceptions holds the operations that failed
        Ext.Array.each(batch.exceptions, function (op) {
            Ext.Array.each(op.getRecords(), function (rec) {
                rec.reject();                 // revert the rejected record's edits
                grid.getView().refreshNode(rec);
            });
        });
        Ext.Msg.alert('Partial failure', 'Some rows could not be saved.');
    }
});
```

The hard part — and the realistic interview scenario — is **partial batch failure**: you edited 5 rows, the server accepts 3 and rejects 2 (validation, concurrency conflict). ExtJS does *not* automatically roll back the whole batch; each operation succeeds or fails independently. The successfully-saved records get committed (clean), while the failed ones remain dirty and land in `batch.exceptions`. You must decide the recovery policy: re-show the failed rows as dirty for the user to fix and re-sync (most common), or `reject()` them to revert. This is the optimistic-concurrency reality from Q38 — to *detect* a conflict (someone else edited the row meanwhile) the server should use a version/ETag and return 409, which you surface as a "this record changed, reload" prompt. The anti-pattern is assuming `sync()` is atomic and not handling `batch.exceptions`, which leaves the grid showing some saved and some unsaved rows with no user feedback.

#### Q66. [Theory] Explain ExtJS model associations (`hasMany`, `belongsTo`, `hasOne`) and the difference between eager nested loading and lazy association loading.

Associations let Models express relationships so you can navigate an object graph instead of juggling foreign keys manually. **`belongsTo`** / **`hasOne`** define a to-one link (an `Order` belongsTo a `Customer`), generating a getter (`order.getCustomer()`). **`hasMany`** defines a to-many link (a `Customer` hasMany `Orders`), generating a method that returns a *Store* (`customer.orders()`). In modern ExtJS these are often declared via the `reference` field config on the child, which infers the inverse association automatically.

```javascript
Ext.define('MyApp.model.Order', {
    extend: 'Ext.data.Model',
    fields: [
        { name: 'id', type: 'int' },
        { name: 'customerId', reference: 'Customer' }   // belongsTo Customer (inferred)
    ]
});
// customer.orders()  → a Store of that customer's Orders (created on demand)
// order.getCustomer() → the parent Customer record
```

The crucial distinction is *when* the related data is fetched. **Eager nested loading**: the server embeds children inside the parent's JSON and the Reader (Q48) materializes the whole graph in one response — `customer.orders()` returns an already-populated store, no further request. This is one round-trip and great when you always need the children, but it bloats payloads and over-fetches when you often *don't* need them. **Lazy loading**: the parent comes alone, and `customer.orders().load()` (or the first access) fires a *separate* request to fetch children when actually needed — minimal initial payload, but an extra round-trip and the classic **N+1 problem** if you lazily load children for many parents in a loop.

```
Eager:  GET /customers/5  → { id:5, name:'Ada', orders:[{...},{...}] }   (1 request, may over-fetch)
Lazy:   GET /customers/5  → { id:5, name:'Ada' }
        GET /orders?customerId=5 → [{...},{...}]   (on demand; N+1 if looped)
```

The trade-off to voice: eager loading optimizes for "I always render the children" (a master view that always shows its details), lazy for "children are occasionally needed" (a list where you drill in rarely). The N+1 trap — rendering a grid of 50 customers and lazily loading each one's orders — produces 51 requests and is a common performance bug; the fix is either eager-embed, or a single batched request keyed by all the parent ids. Associations are powerful for code clarity (`order.getCustomer().get('name')`) but you must consciously choose the fetch strategy to match the access pattern, exactly as you would tune a server-side ORM.

#### Q67. [Practical] How do you implement client-side and server-side validation together, including async (remote) validation like "is this username taken?"

ExtJS validation has three layers and a robust form uses all of them. **Field-level (synchronous, client)**: every field config supports validators — `allowBlank`, `minLength`, `regex`, `vtype` (`email`, `url`), and on the field's *type* it runs immediately as the user types/blurs, giving instant feedback with no network. **Model-level validators** (Q9) live on the data layer so the *same* rules apply whether data enters via a form or a grid edit. **Server-side**: the authoritative check, because the client can never be trusted for security or uniqueness.

```javascript
{ xtype: 'textfield', name: 'email', vtype: 'email', allowBlank: false,
  msgTarget: 'under' }   // shows the error beneath the field
```

The interesting case is **async/remote validation** — "is this username available?" — which the client genuinely cannot answer. ExtJS doesn't have a single canonical async-validator config across all versions, so the pragmatic pattern is to fire a debounced check on `change`/`blur` and reflect the result with `markInvalid`/`clearInvalid`:

```javascript
onUsernameChange: function (field, value) {
    if (!value) { return; }
    Ext.Ajax.request({
        url: '/api/username-available',
        params: { username: value },
        success: function (resp) {
            var taken = Ext.decode(resp.responseText).taken;
            if (taken) { field.markInvalid('Username already taken'); }
            else       { field.clearInvalid(); }
        }
    });
},
// wire with a debounce so we don't hit the server per keystroke:
listeners: { change: { fn: 'onUsernameChange', buffer: 400 } }
```

The architecture point: client validation is for **UX speed** (catch obvious errors instantly, reduce server load), server validation is for **correctness and security** (it's the only validation that can't be bypassed by editing the DOM or hitting the API directly). They are complementary, not redundant — never skip server validation because the client "already checked." For async checks, you must (1) debounce to avoid hammering the server, (2) handle the race where the user keeps typing while a check is in flight (ignore stale responses), and (3) gate submit on the async result resolving, since `form.isValid()` is synchronous and won't wait for an in-flight remote check. The blunt failure mode is treating client validation as sufficient — it's the first line, never the last.

#### Q68. [Theory] What is the grid's `Grouping`/`GroupingSummary` feature versus the `Summary` feature, and how do they compute aggregates over remote, paged data?

ExtJS grids gain grouping and aggregation through **features** (`Ext.grid.feature.*`), which are distinct from plugins (Q35): a feature injects rendering/behavior into the grid *view* itself. **`Grouping`** collapses rows under group headers based on the store's `groupField` ("group orders by status"). **`GroupingSummary`** adds a summary row per group (sum/avg/count of a column within each group). **`Summary`** adds a single grand-summary row (a docked footer) aggregating the whole dataset/page.

```javascript
Ext.create('Ext.grid.Panel', {
    store: ordersStore,            // store has groupField: 'status'
    features: [
        { ftype: 'groupingsummary' },   // per-group subtotals
        { ftype: 'summary' }            // grand total footer
    ],
    columns: [
        { text: 'Status', dataIndex: 'status' },
        { text: 'Total', dataIndex: 'total', xtype: 'numbercolumn',
          summaryType: 'sum', summaryRenderer: function (v) { return 'Σ ' + v; } }
    ]
});
```

The deep issue is **how aggregates are computed when data is remote and paged**, which is where naive use breaks. By default these features compute summaries **client-side over the records currently in the store** — fine when the whole dataset is loaded, but *wrong* with server paging (Q33): if you only loaded page 1 of 100, a `summaryType: 'sum'` sums only those 50 rows, not all 5,000, silently displaying a misleading total. The fix is **`remoteRoot`** on the summary: configure the feature/store to read server-computed summary values from a property in the response (e.g., the server returns `{ data: [...], summary: { total: 1234567 } }` and the grand-total row shows the server's number), so aggregation reflects the *entire* result set, not just the loaded page.

```
Client summary (default):  sums only loaded rows  →  WRONG with paging
Remote summary (remoteRoot): server computes over full set, returns in payload  →  CORRECT
```

The trade-off and the interview signal: client-side summaries are zero-server-cost and instant but only correct for fully-loaded stores; remote summaries are correct for paged/large data but require the backend to compute and return them. A frequent production bug is a "grand total" that's quietly wrong because someone enabled the `Summary` feature on a remotely-paged grid without `remoteRoot` — the total changes as you page, which is the tell. Choose client summaries for small local stores, remote summaries the moment paging or large data enters the picture.

#### Q69. [Practical] How do you tune and debug ExtJS grid scroll/render performance? Walk through using DevTools to find the bottleneck.

A sluggish grid is the most common ExtJS performance complaint, and the methodical approach beats guessing. **First, characterize the symptom**: is it slow on *load* (initial render), on *scroll*, or on *update* (data changes)? Each points to a different cause. **Then profile** in Chrome DevTools → Performance: record while reproducing the jank, and read the flame chart. The tells are: long "Layout"/"Recalculate Style" bars (layout thrash, Q15), long "Scripting" in your renderers (expensive cell renderer functions), or "Paint"/"Composite" dominance (too many DOM nodes — virtualization missing).

```
Symptom            Likely cause                         Fix
─────────────────  ───────────────────────────────────  ───────────────────────────
slow initial load  rendering all rows (no virtualization) BufferedRenderer (Q39)
slow scroll        heavy per-cell renderers / too many    simplify renderer; reduce columns
                   columns; forced reflow per row
slow on update     store.load() rebuilds everything       targeted record.set / loadData merge
janky everywhere   layout thrash from manual doLayout     suspendLayouts/resumeLayouts (Q15)
memory climbs      hidden-not-destroyed tabs (Q60)        closeAction:'destroy', mon listeners
```

Concrete tactics: (1) confirm **`BufferedRenderer`** is active — check the DOM in Elements; if you see thousands of `<tr>` for a big store, virtualization is off. (2) **Audit renderers** — a renderer that does string formatting, regex, or DOM measurement runs *per visible cell on every refresh*; move expensive work out (precompute a field on the Model, or cache). (3) **Avoid forced synchronous layout** — calling `getHeight()`/`getWidth()` inside a renderer or listener interleaved with writes forces reflow per row (the DevTools "Forced reflow" warning flags this). (4) **Reduce column count and `flex` complexity** — many flex columns mean more layout math each pass. (5) **Batch data updates** with `Ext.suspendLayouts()` around bulk `add`/`remove`.

```javascript
console.time('render');
grid.getStore().loadData(bigArray);     // measure the actual render cost
grid.getView().on('refresh', function () { console.timeEnd('render'); }, { single: true });
// Count live components to catch leaks across an interaction:
console.log('components:', Ext.ComponentManager.getCount());
```

The decisive mindset: **measure before optimizing** — the bottleneck is usually one of (no virtualization), (expensive renderers), or (full reloads on update), and the flame chart tells you which. Throwing `BufferedRenderer` at a problem that's actually a heavy renderer wastes effort. The single highest-leverage fixes are virtualization for large stores and replacing `store.load()` with targeted updates for frequently-changing data (Q24).

### 🟠 Advanced — extended

#### Q70. [Theory] How does SASS theming compile in ExtJS, and what is "Fashion"? Can you achieve runtime theme switching despite the build-time model?

ExtJS theming (introduced conceptually in Q11) is build-time SASS, but the *how* has two distinct toolchains worth knowing. The original path is **Sencha Cmd + Ruby SASS/Compass**: your theme's `.scss` variables and rules compile to a static `.css` during `sencha app build`. The modern path is **Fashion** — Sencha's own SASS-compatible compiler written in JavaScript (runs on the JVM/Nashorn or Node) that replaced Ruby SASS for speed and, critically, supports **dynamic variables**. Fashion compiles the theme but can expose variables it can recompute, which is the foundation for the dev-mode "live theme tweaking" where you change a variable and see the UI update without a full rebuild.

```scss
// A theme variable file compiled by Fashion:
$base-color: dynamic(#1f3a5f);   // 'dynamic()' marks it as runtime-recomputable
$panel-header-background-color: dynamic($base-color);
```

The headline limitation from Q11 stands: production theming is **compiled CSS**, so you don't get free runtime theme switching the way CSS-custom-properties frameworks do. But there *are* practical ways to achieve runtime switching despite this:

1. **Ship multiple compiled themes** and swap the `<link>` href at runtime (`Ext.util.CSS` or just replacing the stylesheet element) — load `theme-dark-all.css` instead of `theme-light-all.css`. Simple, robust, but each theme is a full CSS download.
2. **Fashion live recompilation** (dev/limited prod) — Fashion can recompile dynamic variables in the browser to produce new CSS on the fly; powerful for theme editors but heavier and not typical for end-user production.
3. **CSS-variable overlay** — modern ExtJS themes increasingly emit CSS custom properties, so for *some* properties you can override at runtime via `:root` variables without recompiling.

```javascript
// Runtime theme swap by replacing the compiled stylesheet:
function setTheme(name) {
    var link = document.getElementById('theme-css');
    link.href = '/build/' + name + '/' + name + '-all.css';
}
setTheme('theme-dark');   // user toggles dark mode → swap the whole compiled theme
```

The trade-off to articulate: the compiled model gives **smaller, optimized, fully-resolved CSS** (no runtime computation cost) and predictable rendering, at the price of flexibility — adding a theme means a build, and runtime switching means shipping multiple bundles or accepting recompilation overhead. For a corporate app needing light/dark, the pragmatic answer is "build both themes, swap the link," not "make ExtJS behave like Tailwind." Knowing Fashion exists (and why it replaced Ruby SASS) plus the concrete runtime-switch workarounds is the advanced signal.

#### Q71. [Practical] You must debug a bug that only reproduces in the minified production build. What's your strategy?

Production-only bugs are uniquely painful because the dev and prod load paths differ (Q59) and the code is minified/concatenated. A disciplined strategy:

**1. Reproduce with source maps.** A proper Sencha Cmd / webpack production build can emit **source maps**; ensure they're generated and (privately) available so DevTools maps minified `app.js` back to original class files. Without maps you're reading `a.b=function(c){...}`. If maps aren't deployed publicly (for IP reasons), load them locally in DevTools or run a "production-like" build with maps enabled.

**2. Suspect the dependency graph first.** The most common prod-only failure is a **missing `requires`/`uses`** (Q28, Q59): in dev the loader fetches the class by name on demand so it "works," but the compiler didn't bundle it because nothing declared the dependency. Symptom: `Cannot read property 'x' of undefined` or `X is not a constructor` *only* in prod, on a code path that's lazily reached. Fix: add the missing `requires`/`uses` and rebuild.

**3. Suspect build-stripped code.** Production builds **strip** `Ext.log`, debug-only blocks, and assertions, and may run with different `Ext.enableGarbageCollector`/optimizer settings. Code relying on a side effect of a debug statement, or on a class that was tree-shaken because the graph didn't reference it, breaks only in prod.

**4. Diff the environments.** Compare dev vs prod for: framework build flavor (debug vs `-all` minified), locale pack loaded, base href / cache-busting hashes, and any `app.json` `production`-profile overrides. A different proxy `url` or a CDN base path is a frequent culprit.

```javascript
// Make prod failures legible: route Ext errors to telemetry with class context.
Ext.Error.handle = function (err) {
    MyApp.telemetry.logError({
        msg: err.msg, sourceClass: err.sourceClass, sourceMethod: err.sourceMethod,
        stack: (new Error()).stack
    });
    return true;   // prevent the default throw if you want graceful degradation
};
```

**5. Add observability rather than guessing.** Hook `Ext.Error.handle`, `Ext.Ajax` `requestexception` (Q63), and `window.onerror` to capture real production stack traces with class/method context. Often the prod-only bug is timing/async (the bind scheduler tick, Q30) or a race that minification's reordering exposes.

The strategic point: don't try to "read the minified code" — restore legibility with source maps, then check the *prod-specific* failure classes (dependency graph, stripped code, environment config, async timing) in that order, because those four account for the overwhelming majority of "works in dev, breaks in prod" ExtJS bugs. Build a production-with-source-maps target as a permanent debugging affordance.

#### Q72. [Theory] What is the architecture of `Ext.chart` (the charts package)? Explain series, axes, the drawing engine (`Ext.draw`), and SVG vs Canvas surfaces.

ExtJS charting sits on a layered drawing stack, and understanding the layers explains both its capabilities and its performance characteristics. At the bottom is **`Ext.draw`** — a vector-graphics abstraction with `Sprite`s (primitive shapes: paths, rects, text) rendered onto a **`Surface`**. The Surface has pluggable backends: **SVG** (`Ext.draw.engine.Svg`), **Canvas** (`Ext.draw.engine.Canvas`), and historically VML for ancient IE. `Ext.chart` builds on `Ext.draw`: a chart is a `Surface` of sprites organized into **series** and **axes**, bound to a **Store** (the same data package as everything else — Q5).

```
Ext.chart.CartesianChart (store-bound)
 ├─ axes:   [ NumericAxis (left), CategoryAxis (bottom) ]   ← scales + gridlines + labels
 ├─ series: [ BarSeries, LineSeries ]                       ← map records → sprites
 └─ Surface (Ext.draw)  →  engine: 'svg' | 'canvas'         ← actual pixels
        └─ Sprites (paths, text, markers)
```

```javascript
Ext.create('Ext.chart.CartesianChart', {
    store: salesStore,
    engine: 'Ext.draw.engine.Canvas',     // pick the surface backend
    axes: [
        { type: 'numeric',  position: 'left', fields: ['revenue'] },
        { type: 'category', position: 'bottom', fields: ['month'] }
    ],
    series: [{ type: 'bar', xField: 'month', yField: 'revenue' }]
});
```

The **SVG vs Canvas** choice is the key performance/quality trade-off. **SVG** is retained-mode: each shape is a DOM node, so it's crisp at any zoom, hit-testable for free (each bar is an element you can attach events to), and accessible-friendly — but it *degrades with element count*: a scatter plot of 50,000 points means 50,000 DOM nodes, which crushes the browser. **Canvas** is immediate-mode: everything is painted to one bitmap, so it scales to huge datasets cheaply and renders fast, but it's raster (blurry if scaled), hit-testing requires manual math (the chart computes which sprite you clicked), and it's less accessible. The rule: **SVG for low-to-moderate element counts where interactivity/crispness matter** (a dashboard bar chart), **Canvas for high-density data** (dense time series, large scatter). 

Two further architecture points: (1) because charts are **store-bound**, they update reactively when the store changes — the same delta-update discipline from Q24 applies (don't `store.load()` per tick; mutate records). (2) Charts participate in the layout engine (Q15), so resizing triggers a redraw; in dense Canvas charts you throttle resize redraws. The interview depth signal is naming the `Ext.draw` → `Surface` → engine layering and articulating *why* you'd pick Canvas over SVG (element count) rather than treating "the chart" as a black box.

#### Q73. [Practical] How do you author a custom layout, and when is that justified over composing existing layouts? Walk through the layout contract.

Writing a custom layout is rare and usually a smell — but knowing the contract demonstrates real understanding of the layout engine (Q15, Q32). A layout is a class extending an `Ext.layout.container.*` base (often `Ext.layout.container.Container` or `Auto`), and its job is to participate in the batched **layout context**: when the engine runs, it asks your layout to (a) declare what measurements it needs (children's natural sizes, the container's content size), (b) calculate child positions/sizes once those measurements are available, and (c) publish results — all without interleaving reads and writes (which would cause thrash).

```javascript
Ext.define('MyApp.layout.MasonryLayout', {
    extend: 'Ext.layout.container.Auto',
    alias: 'layout.masonry',

    // Called during the layout run; ownerContext is the batched context.
    calculate: function (ownerContext) {
        var items = ownerContext.childItems,
            colWidth = 200, cols = Math.floor(ownerContext.target.getWidth() / colWidth),
            colHeights = new Array(cols).fill(0);

        items.forEach(function (childCtx) {
            var col = colHeights.indexOf(Math.min.apply(null, colHeights));
            // publish position/size (writes are batched by the engine):
            childCtx.setProp('x', col * colWidth);
            childCtx.setProp('y', colHeights[col]);
            colHeights[col] += childCtx.getProp('height') + 8;
        });
        // signal completion so the engine can flush
        ownerContext.setContentSize(cols * colWidth, Math.max.apply(null, colHeights));
    }
});
```

The contract's subtlety is **deferred measurement**: inside `calculate` you don't synchronously call `el.getHeight()` and then write — you request props via the `ownerContext`/`childItems` API, and if a needed measurement isn't ready yet the engine *re-invokes* `calculate` after measuring (the layout may run in multiple passes until everything converges). This is the machinery that lets ExtJS batch all reads then all writes, avoiding the forced-reflow thrash from Q15. A custom layout that bypasses this (reading the DOM directly mid-calculation) reintroduces thrash and breaks the very guarantee the engine provides.

When is it justified? Almost never for standard arrangements — `vbox`/`hbox`/`border`/`column`/`anchor` compose to cover the vast majority of UIs, and a custom layout is more code to maintain and a likely performance footgun. It's justified only for a genuinely novel sizing algorithm the built-ins can't express (a true masonry/Pinterest layout, a radial arrangement, a force-directed graph layout) where composing existing layouts would require fighting them with absolute positioning anyway. The senior judgment to voice: **prefer composing existing layouts**; reach for a custom layout only when you've confirmed no combination of the built-ins expresses the requirement, and when you do, respect the read-then-write context contract or you'll lose the engine's batching benefit.

#### Q74. [Theory] How do you build a decoupled cross-view communication channel (event bus) in ExtJS, and what are the trade-offs versus ViewModel binding and the global Controller?

Sometimes two views with no parent/child relationship must communicate (a notification panel and a header badge; a filter bar and several independent grids). ExtJS offers several mechanisms, each with a different coupling profile, and choosing well prevents both spaghetti and leaks.

**1. `Ext.GlobalEvents`** — a framework-provided singleton `Observable`. Any code can `Ext.GlobalEvents.fireEvent('orderplaced', order)` and any other can `Ext.GlobalEvents.on('orderplaced', handler)`. This is the simplest app-wide event bus. **2. A custom singleton bus** — define your own `singleton: true` Observable for typed, app-specific events. **3. Shared ViewModel/Store binding** (Q58) — views bind to the same ViewModel data or global store, so changes propagate via the bind scheduler. **4. Global Controller `listen`** (Q7) — a controller subscribes to component events across the app via ComponentQuery selectors.

```javascript
// A typed application event bus:
Ext.define('MyApp.Bus', { singleton: true, mixins: ['Ext.mixin.Observable'],
    constructor: function () { this.mixins.observable.constructor.call(this); } });

// Publisher (anywhere):
MyApp.Bus.fireEvent('cart:changed', { count: 3 });

// Subscriber — MUST use managed listeners so it unbinds when the view dies:
Ext.define('MyApp.view.HeaderBadge', {
    extend: 'Ext.Component',
    initComponent: function () {
        this.callParent(arguments);
        this.mon(MyApp.Bus, 'cart:changed', this.onCartChanged, this); // mon, not on!
    },
    onCartChanged: function (data) { this.setHtml(data.count); }
});
```

The trade-offs are about **coupling and leak risk**. An event bus gives maximal decoupling — publisher and subscriber don't know each other — but that's a double edge: it's easy to lose track of who emits/handles what ("who fired `cart:changed`?"), and it's the *prime leak vector* (Q14) because a long-lived bus holds references to short-lived view handlers; you **must** subscribe with `mon`/managed listeners so the binding dies with the view, or use `Ext.GlobalEvents` which has some lifecycle integration. **ViewModel binding** is the most structured and leak-safe (scoped to the view, auto-destroyed) but only works when views can share a ViewModel/store and the communication is *state*, not transient *events*. The **global Controller** centralizes wiring but reintroduces the application-scoped-singleton problems MVVM was designed to avoid (Q7).

The decision rule: prefer **shared ViewModel/Store binding** for shared *state* (cart contents, selected entity) because it's reactive and leak-safe; use an **event bus (`Ext.GlobalEvents` or a typed singleton)** for genuine cross-cutting *events/notifications* that aren't naturally state ("user logged out," "background job finished") — always subscribed via `mon`. Reserve the global Controller for legacy MVC apps. The anti-pattern to call out: an event bus used as a substitute for proper state binding, subscribed with raw `on()`, which both obscures data flow and leaks every view that ever subscribed.

#### Q75. [Practical] How do you implement infinite scroll versus traditional paging in an ExtJS grid, and what are the failure modes of each at scale?

Two patterns let users traverse a large dataset, and they have different UX and different engineering pitfalls. **Traditional paging** uses a `pagingtoolbar` bound to a store with `pageSize`: the user clicks Next/Prev/page-number, the store fires a fresh `load` for that page, and the grid shows exactly that page (Q8). **Infinite scroll** (buffered/virtual store, Q39) presents one seemingly-endless scrollable grid; as the user scrolls, the store *prefetches* adjacent pages (`leadingBufferZone`/`trailingBufferZone`) and discards far-away ones, while `BufferedRenderer` keeps the DOM bounded.

```javascript
// Infinite scroll: buffered store + buffered renderer, no paging toolbar.
var store = Ext.create('Ext.data.Store', {
    model: 'MyApp.model.Order',
    pageSize: 200,
    leadingBufferZone: 300,    // prefetch this many rows ahead
    trailingBufferZone: 25,
    proxy: { type: 'ajax', url: '/api/orders',
             reader: { rootProperty: 'data', totalProperty: 'total' } },
    autoLoad: true
});
Ext.create('Ext.grid.Panel', { store: store, plugins: { bufferedrenderer: true } /* ... */ });
```

The failure modes differ and naming them is the senior signal. **Paging** fails on: (a) **unstable ordering** — if rows are inserted/deleted server-side between page loads, "page 2" shifts and the user sees duplicates or skips (the classic offset-pagination bug); the fix is **keyset/cursor pagination** (page by last-seen id, not numeric offset). (b) **Deep pages are slow** — `OFFSET 50000 LIMIT 50` forces the DB to scan and discard 50k rows; cursor pagination avoids this. **Infinite scroll** fails on: (a) **`totalProperty` accuracy** — the buffered store needs a reliable total to size the scrollbar; if the server can't cheaply count (expensive `COUNT(*)` on a filtered query), the scrollbar geometry is wrong. (b) **Prefetch storms** — fast scrolling fires many overlapping prefetch requests; the store must cancel/coalesce, and the server must handle the burst. (c) **Jump-to-position** — scrolling the thumb to 80% must fetch the right page instantly; latency there feels broken. (d) **Memory if discard is misconfigured** — if `purgePageCount` is off, it can retain all visited pages, defeating the point.

```
                 Paging                         Infinite scroll
UX               discrete pages, deterministic  continuous, modern feel
Server load      one query per click            bursts of prefetch on scroll
Pagination bug   offset shift on inserts        needs reliable total for scrollbar
Best DB strategy keyset/cursor pagination       keyset + cheap/approx count
Memory           bounded (one page)             bounded IF purge configured
```

The decision: **paging** suits report-style screens, deterministic navigation, and backends where a stable total/cursor is easy; **infinite scroll** suits exploratory browsing of large sets and feels modern but demands disciplined prefetch tuning, a reliable (or approximate) total, and cursor-based server pagination to avoid offset drift. The cross-cutting lesson: at scale the *server* pagination strategy (offset vs keyset) matters as much as the client widget, and both patterns require `BufferedRenderer` so the DOM never holds the full set.

#### Q76. [Theory] Explain the `stateful` system (`Ext.state.Provider`, `Ext.state.Stateful`). How do you persist and restore UI state like column widths and sort, and what are the pitfalls?

ExtJS can automatically persist a component's UI state — column order/width/visibility, sort, grouping, panel collapse, window position — across page reloads, via the **state system**. A `stateful: true` component with a unique `stateId` saves a slice of its state to a **`Ext.state.Provider`** (the storage backend) on relevant changes and restores it on render. The default provider is `Ext.state.CookieProvider`; the more common modern choice is `Ext.state.LocalStorageProvider` (bigger quota, not sent on every request like cookies).

```javascript
// Install a provider once at app bootstrap (Q43):
Ext.state.Manager.setProvider(Ext.create('Ext.state.LocalStorageProvider'));

Ext.create('Ext.grid.Panel', {
    stateful: true,
    stateId: 'ordersGrid',           // MUST be stable & unique
    // by default persists columns (width/order/hidden), sort, grouping
    stateEvents: ['columnresize', 'columnmove', 'sortchange'],  // when to save
    columns: [ /* ... */ ]
});
```

Under the hood, a stateful component implements `getState()` (returns the slice to persist) and `applyState()` (restores it on init), and saves on the configured `stateEvents`. You can override these to persist *custom* state (a filter, a selected tab) by extending what `getState()` returns.

The pitfalls are numerous and worth enumerating because they cause real, confusing bugs:

1. **`stateId` collisions/instability** — two grids with the same `stateId` overwrite each other's state, and a `stateId` that changes (or defaults to an auto-id, Q1's id problem) means state never restores or restores to the wrong component. Always assign a stable, unique `stateId`.
2. **Stale state after a redeploy** — if you ship a new version that *removes* a column, the persisted state may reference the old column layout and restore a broken/mismatched grid. You need a **state version** key so you can invalidate old state on schema changes.
3. **Storage limits & multi-tab** — CookieProvider has tiny quota and bloats every HTTP request; LocalStorage is per-origin and shared across tabs, so two tabs can stomp each other's state.
4. **Security/PII** — never persist sensitive data (filter values containing PII, tokens) into client storage via state.
5. **Restored state hiding data** — a user who hid a column or set a narrow filter has that *persisted*; on next visit data looks "missing," generating support tickets. Provide a "reset layout" affordance.

The trade-off to articulate: statefulness is a genuine UX win (users keep their preferred column layout) and is nearly free to enable, but it introduces a *hidden persistent input* to your UI that survives deploys and can desync from code. The discipline: stable unique `stateId`s, a state-version guard to invalidate on layout-affecting releases, LocalStorage over cookies, never persist sensitive values, and always give users a way to reset. Treating persisted state as untrusted, versioned input — exactly as you'd treat any cached client data — is the senior framing.

### 🔴 Expert — extended

#### Q77. [Practical] Design the real-time transport layer for an ExtJS trading dashboard: WebSocket reconnection, backpressure, and reconciling pushed deltas into Stores. What breaks at scale?

Building on Q24, the *transport and data-reconciliation* layer is where real-time dashboards actually fail, and designing it well is an expert topic. The architecture: a single WebSocket (not per-grid) carries server-pushed deltas; a framework-neutral connection manager owns the socket and hands parsed messages to a dispatcher that applies them to the right Stores.

**Reconnection** is non-negotiable — networks drop. Implement exponential backoff with jitter, and crucially a **resync-on-reconnect** protocol: when the socket reconnects you've *missed* deltas, so you can't just resume; you must either replay from a server-held sequence number (the client sends "last seq I saw," server replays the gap) or do a full snapshot reload of affected stores. Without this you get silently stale grids after every blip.

```javascript
Ext.define('MyApp.RealtimeConn', {
    singleton: true,
    connect: function () {
        this.ws = new WebSocket(this.url);
        this.ws.onmessage = (e) => this.onMessage(JSON.parse(e.data));
        this.ws.onclose   = () => this.scheduleReconnect();
        this.ws.onopen    = () => this.resync(this.lastSeq);   // replay gap
    },
    scheduleReconnect: function () {
        var delay = Math.min(30000, this.backoff *= 2) + Math.random() * 1000; // jitter
        this.reconnectTimer = Ext.defer(this.connect, delay, this);
    },
    onMessage: function (msg) {
        this.lastSeq = msg.seq;
        this.buffer.push(msg);              // don't apply per-message — batch (below)
        this.scheduleFlush();
    }
});
```

**Backpressure** is the scale-breaker. During market open the server may push thousands of ticks/second — far faster than the browser can render. You must **decouple ingest from render**: buffer incoming messages and flush on a `requestAnimationFrame`/interval (Q24), and apply **conflation** — if 50 updates for the same instrument arrive in one frame, apply only the *latest* (keyed by instrument id), discarding superseded ticks. Rendering every tick is both impossible and pointless (the human eye can't see 1000 fps). Inside the flush, wrap store mutations in `Ext.suspendLayouts()/resumeLayouts()` and use targeted `record.set()` / `store.loadData(rows, true)`, never `store.load()`.

```javascript
flush: function () {
    var byId = {};
    this.buffer.forEach(m => byId[m.id] = m);   // conflate: last write wins per id
    this.buffer.length = 0;
    Ext.suspendLayouts();
    Object.keys(byId).forEach(id => {
        var rec = ordersStore.getById(id);
        if (rec) { rec.set(byId[id].fields, { dirty: false }); }   // in-place update
        else     { ordersStore.add(byId[id].fields); }
    });
    Ext.resumeLayouts(true);
}
```

**What breaks at scale**, named explicitly: (1) **per-tick rendering** freezes the UI — fixed by rAF batching + conflation. (2) **`store.load()` on update** rebuilds the entire grid and loses scroll/selection — fixed by in-place `record.set`. (3) **missed deltas on reconnect** silently corrupt state — fixed by sequence-number resync. (4) **unbounded buffer growth** if the tab is backgrounded (rAF throttled) — cap the buffer and conflate aggressively, or pause subscriptions when hidden (Page Visibility API). (5) **listener leaks** — the connection singleton holds handlers for views; subscribe via `mon` (Q14) so closing a dashboard panel detaches it. (6) **memory** from BufferedRenderer not being on — a real-time grid *must* virtualize. The expert framing: real-time correctness is a *protocol* problem (sequencing, resync, conflation), not just a rendering-speed problem, and the data layer should live *outside* ExtJS (a neutral connection manager) so it survives an eventual migration (Q20) and can feed both ExtJS Stores and any new React components during a strangler migration.

#### Q78. [Theory] How does ExtJS handle browser-history and deep-linking edge cases — back/forward, refresh-on-deep-link, unsaved-changes guards — and how does this complicate a strangler migration?

Routing basics were covered in Q50; the *edge cases* are where production apps and migrations get hard. ExtJS routing rests on `Ext.util.History`, which abstracts hashchange (and pushState in modern setups). The subtle problems:

**1. Refresh / cold deep-link.** When a user bookmarks `#order/5/edit` and opens it fresh, the app boots from nothing — but the route may need data and components that don't exist yet at dispatch time. ExtJS dispatches routes *after* controllers initialize (Q43), but a deep route often must **lazily create** the view it targets (the order editor isn't open yet). Route handlers therefore must be **idempotent and self-sufficient**: create-or-focus the target view, load required data, *then* apply the route state — they can't assume the UI is already in any particular state.

**2. Back/forward producing inconsistent state.** Because ExtJS routes fire *methods* (imperative, Q50) rather than declaratively rendering a component tree, the back button replays a hash but your method must correctly *reverse* to that state — closing views the forward navigation opened, restoring selections. If a route handler only does forward setup ("open editor") without handling the reverse ("if we navigated back past this, close it"), back/forward drifts out of sync. This is the imperative-routing tax.

**3. Unsaved-changes guards.** Leaving a dirty form (Q65) via a route change or browser navigation must prompt. ExtJS's `before` route phase (Q50) handles in-app route changes — the guard calls `action.stop()` to block — but the *browser's own* back button / tab close needs `window.onbeforeunload`, which is separate and can't show a custom ExtJS dialog (browsers force a native prompt). So you need both: route `before` guards for in-app navigation and `beforeunload` for hard browser navigation.

```javascript
routes: {
    'order/:id/edit': { before: 'guardUnsaved', action: 'editOrder' }
},
guardUnsaved: function (id, action) {
    if (this.currentForm && this.currentForm.isDirty()) {
        Ext.Msg.confirm('Unsaved', 'Discard changes?', function (btn) {
            if (btn === 'yes') { action.resume(); } else { action.stop(); }
        });
    } else { action.resume(); }
}
```

**How this complicates a strangler migration (Q20):** when ExtJS and React coexist behind one shell, **history is a single shared resource** but two routers want to own it. If both ExtJS `Ext.util.History` and React Router listen to `popstate`/hashchange, a single back-button press can fire *both*, double-handling navigation. You must designate **one owner of the URL** — typically the shell's router (often the new React Router or a neutral router) — and have ExtJS routing driven *by* the shell (the shell tells ExtJS "show order 5") rather than ExtJS independently manipulating history. The hash-vs-pushState mismatch compounds it: legacy ExtJS uses `#routes` while modern React uses clean paths, so the shell must translate. And unsaved-changes guards must be unified — a React route guard and an ExtJS `before` guard must both consult a shared "is anything dirty" registry, or the user loses data navigating between a React screen and an ExtJS screen. The expert point: routing/history is precisely the **shared-boundary risk** flagged in Q20, and the resolution is a single authoritative router in the shell with both frameworks subordinated to it, plus a shared dirty-state registry for guards.

#### Q79. [Practical] Your ExtJS app must meet a Content Security Policy with no `unsafe-eval` and no inline scripts. What breaks, and how do you make ExtJS CSP-compliant?

A strict CSP (`script-src 'self'; no 'unsafe-eval'; no 'unsafe-inline'`) is increasingly mandated for security/compliance, and ExtJS has historically clashed with it because parts of the framework used dynamic code generation. Knowing exactly *what* breaks and the fix is an expert, practical topic.

**What breaks without `unsafe-eval`:** ExtJS historically used `new Function(...)`/`eval` in a few hot paths: (1) **`Ext.XTemplate`** compiled templates into JS functions via `new Function` (Q31) — the single biggest offender, since templates are everywhere (grids, dataviews). (2) The **bind/formula system** (Q30) compiled binding expressions and formulas. (3) Some **older microloader/class-system** internals. Under strict CSP these throw `EvalError`/CSP violations and the app fails to render.

**The fix — the CSP package and build mode.** Sencha shipped a **`Ext.CSP`-compatible build** (the `csp` package / "CSP-friendly" mode) in which the compiler **pre-compiles XTemplates and bind expressions at build time** into real functions in the bundle, so no runtime `new Function` is needed. You enable it in the build config and ensure all templates are statically analyzable (templates built from runtime-concatenated strings can't be pre-compiled — they must be declarative).

```json
// app.json (conceptual): opt into CSP-safe build behavior
{
  "requires": ["csp"],
  "production": { "loader": { "cache": true }, "csp": true }
}
```

Beyond the framework itself:

1. **No inline scripts/styles** — ExtJS injects some `<style>` at runtime (e.g., dynamic CSS via `Ext.util.CSS`, certain themes). With `style-src` locked down you need `'nonce-...'` on framework-generated style tags or to disable runtime style injection; serve all CSS from compiled files. The Fashion runtime recompilation (Q70) is incompatible with strict `style-src` and must be off in prod.
2. **`unsafe-eval` truly gone** — confirm no app code uses `Ext.decode` on untrusted input expecting eval (it uses `JSON.parse`, which is fine) and no third-party plugin sneaks in `eval`.
3. **Templates must be static** — refactor any `new Ext.XTemplate(someRuntimeString)` into declared templates so the build can pre-compile them; runtime-assembled templates are inherently CSP-hostile.
4. **Test with the policy on in CI** — run the app with the real CSP header and a violation reporter; CSP failures are runtime and easy to miss until a locked-down environment surfaces them.

```
CSP directive        ExtJS impact                  Mitigation
─────────────────    ────────────────────────────  ─────────────────────────────
no 'unsafe-eval'     XTemplate / bind compile fails  CSP build (pre-compile at build)
no 'unsafe-inline'   runtime <style>/<script> inject nonce or compiled CSS only; no Fashion runtime
script-src 'self'    external CDN scripts blocked    self-host framework + deps
```

The trade-offs and senior framing: a CSP build *removes runtime flexibility* — you lose dynamic runtime template construction and Fashion live recompilation — in exchange for a hard security guarantee that no attacker-injected string can become executable code (a strong defense-in-depth against XSS, complementing the `htmlEncode` discipline from Q18/Q31). For legacy ExtJS 4/5 apps, CSP support is weak-to-absent, which can be a *forcing function* for upgrade or migration (Q19). The pragmatic path: target a modern ExtJS, enable the CSP build, make every template/bind statically analyzable, self-host all assets, serve only compiled CSS, and verify with the policy enforced in CI — then a strict no-`unsafe-eval` CSP is achievable.

#### Q80. [Theory] Compare ExtJS's component model and change-detection to Angular's. Where do the philosophies converge and diverge, and what does that imply for a team choosing between them?

Both ExtJS and Angular are *opinionated, batteries-included* frameworks (unlike React/Vue's view-only core), so they're natural comparators, and a nuanced comparison signals architectural maturity. **Convergence:** both ship a full stack (components, DI-ish wiring, forms, routing, HTTP), both favor a strongly-structured app layout, both target large enterprise apps, both use a build/compile step, and both have a reactive data-binding system (ExtJS ViewModel/bind, Angular templates + signals/RxJS).

**Divergence in the component model:** ExtJS components are **JS-config objects** instantiated through a class system (`Ext.define`/`xtype`, Q2/Q25) that *generate* their DOM imperatively via the layout engine (Q15) — you rarely write HTML; the framework computes pixel sizes in JS. Angular components are **template-first**: you write HTML templates with declarative bindings, and the framework + browser handle layout via normal CSS. This is a deep philosophical split: ExtJS *owns* layout (powerful for dense desktop grids where pixel-precise sizing matters; heavy and proprietary), Angular *delegates* layout to CSS (lighter, web-standard, but you build complex grids from libraries).

**Divergence in change detection:** Angular historically used **Zone.js** to monkey-patch async APIs and trigger a top-down dirty-check of the component tree after every async event, evolving toward **signals** (fine-grained reactivity) in recent versions. ExtJS uses the **bind scheduler** (Q30) — an explicit publish/subscribe stub tree flushed per tick, closer to fine-grained reactivity than Angular's old zone-based whole-tree check. Neither is React's virtual-DOM diffing. The practical implication: ExtJS's binding is explicit (you declare `bind:`/`publishes`, Q51) and only tracks declared dependencies, while classic Angular's zone approach checked broadly (sometimes over-checking, hence `OnPush`).

```
                  ExtJS                          Angular
Component def      JS config + class system       TS class + HTML template
DOM/layout         framework computes (JS engine)  CSS / browser
Change detection   bind scheduler (explicit P/S)   Zone.js dirty-check → signals
DI                 loose (singletons, refs)        first-class hierarchical DI
Forms              data package + form panel        Reactive/Template forms
Ecosystem/2026     shrinking, commercial license    large, open-source, vibrant
```

**What it implies for a team choosing:** for a *new* data-dense desktop enterprise app, ExtJS's built-in grids/trees/charts and pixel-precise layout are genuinely productive out of the box, but you pay in bundle size, a shrinking talent pool, and a commercial license (Q19). Angular gives a comparable "everything included" experience with a vastly larger open-source ecosystem, abundant talent, web-standard layout, and modern reactivity (signals) — at the cost of building dense grids from third-party libraries (ag-Grid, etc.) rather than first-party. The honest 2026 recommendation mirrors Q19/Q44: **new projects almost never start on ExtJS** — if you want the opinionated full-stack experience, Angular (or React + libraries) wins on ecosystem, talent, and licensing; ExtJS's enduring edge is *only* its first-party dense-grid/charting richness on an *existing* codebase, which is exactly why the strategic conversation is migration, not greenfield adoption. The convergence (both opinionated full stacks) is what makes Angular the most natural migration target for an ExtJS team philosophically, even if React has more market momentum.

#### Q81. [Practical] How do you upload files in ExtJS, and what is the difference between the legacy iframe-based `fileuploadfield` and a modern XHR/`FormData` upload?

File upload is a place where ExtJS shows its age, and the two mechanisms have very different capabilities. The classic `Ext.form.field.File` (`xtype: 'filefield'`/`fileuploadfield`) wraps a native `<input type="file">` styled to look like an ExtJS field, and when you `form.submit()`, ExtJS detects file fields and routes the submit through a **hidden iframe** rather than a normal Ajax request — because the old `XMLHttpRequest` couldn't send file bodies. The server response must be returned as text the iframe can read (often wrapped so older browsers parse it), and you get **no progress events** and an awkward content-type contract.

```javascript
// Legacy iframe upload via form submit:
{ xtype: 'form', items: [{ xtype: 'filefield', name: 'doc', fieldLabel: 'File' }] };
form.getForm().submit({
    url: '/api/upload',
    waitMsg: 'Uploading…',                 // no real progress %, just a spinner
    success: function (f, action) { Ext.toast('Uploaded'); }
});
```

The modern approach bypasses the form machinery and uses the browser's **`FormData` + `XMLHttpRequest`/`fetch`**, which supports real upload progress, multiple files, drag-and-drop, and chunking:

```javascript
onDrop: function (fileList) {
    var fd = new FormData();
    Ext.Array.each(fileList, f => fd.append('files', f));
    var xhr = new XMLHttpRequest();
    xhr.upload.onprogress = e => this.lookup('bar').setValue(e.loaded / e.total);  // real %
    xhr.onload = () => Ext.toast('Done');
    xhr.open('POST', '/api/upload');
    xhr.send(fd);
}
```

The trade-offs: the legacy `filefield` is the path of least resistance if you're already in a form-submit flow and don't need progress — it "just works" with the data the form already gathers. But it can't show progress, struggles with large files, and the iframe response contract is brittle. The modern `FormData` path is what you want for any serious upload UX (progress bars, multi-file, drag-drop zones, resumable/chunked uploads for large files), at the cost of writing the XHR plumbing yourself and resolving records back into your Stores manually. The senior framing: on a modern browser there's no technical reason to use the iframe mechanism for new code — wrap a `FormData` XHR in a small component (or use the `actioncolumn`/dropzone, Q41) — and reserve `filefield` for trivial single-file form submits where its simplicity outweighs its limitations. Security-wise, file type/size validation on the client is UX-only; the server must re-validate and scan, exactly as with all client validation (Q67).

#### Q82. [Theory] What memory-leak categories are specific to ExtJS beyond DOM listeners, and how do you build leak detection into a long-running app?

Q14 covered the canonical listener leak; an expert answer enumerates the *full* taxonomy and how to *detect* leaks systematically rather than reactively. ExtJS-specific leak categories:

1. **Unmanaged listeners on long-lived publishers** — a short-lived view does `globalStore.on('load', ...)` or `Ext.GlobalEvents.on(...)` with raw `on()` (not `mon`, Q14/Q74); the long-lived object retains the dead view's handler closure forever.
2. **Components created but never added to a container** — they have no parent to auto-destroy them; `Ext.create('panel', {...})` without `renderTo`/`add` and without a `destroy()` is orphaned but referenced.
3. **`hidden`-not-`destroyed` accumulation** (Q60) — tab panels/cards keeping every view in memory; the classic all-day-session 2GB growth.
4. **Singletons / global stores holding view references** (Q47, Q74) — a singleton bus or config registry that stashes a component reference pins the whole component tree.
5. **Sessions not destroyed** (Q38) — an `Ext.data.Session` retains every touched record; a per-screen session not destroyed with the screen leaks the whole entity graph.
6. **Timers / intervals / deferred calls** — `Ext.interval`/`setInterval`/`Ext.defer` whose callback closes over a destroyed component; the timer keeps it alive.
7. **DnD zones and plugins** not torn down (Q52) — `DragZone`s register with the `DragDropManager` singleton and leak if not destroyed.
8. **Detached DOM held by JS** — keeping an `Ext.get()` (cached, Q34) reference to a node after its component is destroyed.

**Building detection in** (the proactive part interviewers want): instrument component counts and snapshot diffs as part of normal QA/automated testing, not just ad-hoc DevTools sessions.

```javascript
// 1. A baseline/after counter you can call around any open→close interaction:
MyApp.leakCheck = function (label, fn) {
    var before = Ext.ComponentManager.getCount();
    fn();                                   // do the interaction (open + close a tab)
    // let teardown settle, then compare:
    Ext.defer(function () {
        var after = Ext.ComponentManager.getCount();
        if (after > before) {
            console.warn('LEAK?', label, before, '→', after, '(+' + (after - before) + ')');
        }
    }, 100);
};

// 2. In a test suite, assert component count returns to baseline:
//    open the view 20×, close it, expect ComponentManager.getCount() ≈ baseline.

// 3. DevTools: Memory → 3-snapshot technique. Snapshot, run cycle 10×, snapshot,
//    force GC, snapshot; filter retained 'Ext.*' and 'Detached' nodes; the
//    retainer path names the offending listener/singleton.
```

The methodology to articulate: leaks are best caught **continuously**, not forensically — add a `getCount()` regression check to automated component tests (every test must `destroy()` its components, Q46, and the suite should fail if the count climbs across iterations), expose a dev-only leak counter around major open/close flows, and periodically run the 3-snapshot DevTools technique on the real app under a realistic all-day workload. The cultural point: because ExtJS makes leaking *easy* (hidden-not-destroyed, unmanaged listeners, singletons), the defense is *discipline encoded as tooling* — `mon` everywhere, `doDestroy` cleanup for every non-child resource, `closeAction:'destroy'` for heavy tabs, sessions destroyed with screens, and an automated component-count regression gate so a new leak fails CI rather than surfacing as a 2GB browser six months later.

#### Q83. [Practical] How do you set up dependency injection / inversion of control in an ExtJS app to make ViewControllers testable, given ExtJS has no first-class DI container?

Unlike Angular, ExtJS has **no built-in DI container**, which makes ViewControllers (Q7) tempting to fill with hard-coded dependencies (`Ext.Ajax.request`, `MyApp.store.Users`, `MyApp.Session`) that are impossible to mock in a unit test (Q46). The senior approach is to *impose* a lightweight IoC discipline so logic is testable.

**The core technique is dependency *lookup* through a seam you can override**, plus keeping ViewController methods *pure-ish* (take inputs, return outputs, push side effects to injectable services). A few practical patterns:

1. **Service singletons accessed via an indirection** — instead of `MyApp.api.UserService.load()` hard-referenced, resolve services through a registry you can swap in tests.

```javascript
// A tiny service registry (the "container"):
Ext.define('MyApp.Services', {
    singleton: true,
    map: {},
    register: function (name, impl) { this.map[name] = impl; },
    get: function (name) { return this.map[name]; }
});
MyApp.Services.register('userApi', MyApp.api.RealUserApi);

// ViewController depends on the abstraction, not the concretion:
Ext.define('MyApp.view.user.UserController', {
    extend: 'Ext.app.ViewController',
    onLoadUsers: function () {
        return MyApp.Services.get('userApi').loadUsers()      // injectable seam
            .then(users => this.getViewModel().set('users', users));
    }
});
```

2. **Constructor/config injection for tests** — give the controller a `config` slot for its collaborators so a test can pass a fake:

```javascript
// Test: inject a stub, call the method directly, assert — no DOM, no network.
MyApp.Services.register('userApi', { loadUsers: () => Promise.resolve([{ id: 1 }]) });
var ctrl = Ext.create('MyApp.view.user.UserController');
ctrl.getViewModel = () => fakeViewModel;          // stub the VM
return ctrl.onLoadUsers().then(() => expect(fakeViewModel.get('users').length).toBe(1));
```

3. **Override-based injection** (Q37) — for app-wide cross-cutting deps (the CSRF header, a logger), an `override` on the proxy/Ajax is effectively a DI substitution at the framework level.

The trade-offs and judgment: ExtJS's lack of a DI container means you either accept tightly-coupled ViewControllers (fast to write, painful to test, the default) or you *build* a thin seam (a service registry + dependency-via-config). The registry approach is lightweight and gets you the main benefit — **swappable collaborators for tests** — without a heavy framework. The discipline that makes it work is **keeping ViewController handlers thin**: they should orchestrate (call a service, set a ViewModel value) rather than embed business logic and direct I/O, so the orchestration is trivially testable with stubs and the real logic lives in injectable, independently-testable services (Q46). The anti-pattern to reject in review: a "god ViewController" (Q23) that directly news-up stores, calls `Ext.Ajax` inline, and reads/writes global singletons — it's untestable by construction. The framing: you're not bolting Angular DI onto ExtJS, you're introducing the *minimum* indirection (one registry, thin handlers) needed to invert the dependencies that otherwise make MVVM logic untestable.

#### Q84. [Theory] What is the `Ext.data.BufferedStore`/virtual store's page cache and prefetch behavior internally, and how do `purgePageCount`, `leadingBufferZone`, and `trailingBufferZone` tune memory vs. network?

Q39 established that a buffered store virtualizes *data*; the expert detail is *how* its page cache and prefetch actually work and how the tuning knobs trade memory against network. A buffered store maintains a **`PageMap`** — a sparse, LRU-style cache of pages (each `pageSize` records) keyed by page number. When the grid asks for records in a scroll range, the store checks the PageMap: cached pages are served instantly; missing pages trigger **prefetch** requests to the proxy. The store deliberately fetches *ahead of* and *behind* the visible window so scrolling feels seamless rather than stuttering on every page boundary.

The three knobs control the size of that cached/prefetched window:

- **`leadingBufferZone`** — how many rows *ahead* of the visible range to keep prefetched (in the scroll direction). Larger = smoother forward scrolling (pages are already loaded before you reach them) but more memory and more eager network.
- **`trailingBufferZone`** — how many rows *behind* the visible range to retain, so scrolling back up doesn't re-fetch. Usually smaller than leading (users scroll down more than up).
- **`purgePageCount`** — how many pages beyond the active zones to keep before discarding the oldest (LRU). `purgePageCount: 0` means **never purge** (cache grows unbounded — fast revisits but defeats the memory bound); a small value caps memory by evicting far-away pages, re-fetching them if the user scrolls back.

```javascript
Ext.create('Ext.data.BufferedStore', {
    model: 'MyApp.model.Row',
    pageSize: 100,
    leadingBufferZone: 200,    // ~2 pages prefetched ahead
    trailingBufferZone: 50,    // ~half a page retained behind
    purgePageCount: 5,         // keep ≤5 extra pages cached, then evict (LRU)
    proxy: { type: 'ajax', url: '/api/rows',
             reader: { rootProperty: 'data', totalProperty: 'total' } }
});
```

The internal flow on a scroll: (1) compute the new visible record range; (2) expand it by leading/trailing zones to a *desired* range; (3) determine which pages covering that range are missing from the PageMap; (4) issue prefetch requests for the missing pages (coalescing/cancelling stale ones during fast scrolls); (5) when results arrive, insert into the PageMap and notify the renderer; (6) if the PageMap now exceeds active zones + `purgePageCount`, evict the least-recently-used pages.

The tuning trade-off, stated crisply: **bigger buffer zones + higher `purgePageCount` = smoother UX, more memory, more network**; **smaller = leaner memory, but more re-fetching and possible scroll stutter**. The failure modes: `purgePageCount: 0` on a million-row dataset slowly leaks as the user explores (every visited page stays cached — the memory bound is gone, defeating the buffered store's purpose); zones too small means fast scrolling outruns prefetch and the grid shows loading gaps; zones too large means the initial scroll fires a burst of prefetch requests hammering the server. The required server contract is a **reliable `totalProperty`** (Q75) so the store can size the scroll range, and ideally cursor/keyset-stable paging so a page's contents don't shift between fetches. The expert signal is treating the buffered store as an **LRU page cache with read-ahead** and tuning its window to the dataset size, scroll patterns, and server cost — not leaving the defaults and hoping.

#### Q85. [Practical] How do you incrementally add TypeScript to an ExtJS codebase, and what tooling exists (`@sencha/ext` types, the toolkit's type generation)? What are the limits?

Adding TypeScript to a historically-`Ext.define` JavaScript codebase is a common modernization step (often a precursor to migration, Q21), and it's only *partially* clean because ExtJS's dynamic class system doesn't map naturally to TS's static type model. The practical landscape:

**Tooling.** Sencha ships (and the community maintains) **type definitions** for the framework — `@sencha/ext` and toolkit-specific `.d.ts` typings — and Sencha Cmd / the open tooling can **generate** typings from the framework's metadata so `Ext.grid.Panel`, configs, and methods are typed. For *your* classes, the challenge is that `Ext.define('MyApp.X', {...})` is a runtime call, not a TS `class`, so TS can't infer types from it directly. Two strategies: (a) **author new classes as ES6 `class`** extending the framework (modern ExtJS supports `ExtClass`-style ES6 class definitions with decorators/config), which TS understands; or (b) keep `Ext.define` but write **hand-maintained `.d.ts` ambient declarations** describing your classes' shapes for the rest of the codebase to consume.

```typescript
// Incremental adoption: type the seams, not the whole world at once.
// 1. Add @sencha/ext types so framework calls are checked.
// 2. Type your service layer (Q83) and Models — the most-reused, least-DOM code.
interface User { id: number; name: string; email: string; }

// 3. New ViewControllers as ES6 classes where supported:
class UserController /* extends Ext.app.ViewController */ {
    onSave(record: User): Promise<void> {
        return MyApp.Services.get('userApi').save(record);   // typed service boundary
    }
}
// 4. Configure tsconfig with allowJs + checkJs:false initially, tightening per-file.
```

The pragmatic sequencing: turn on TS with `allowJs: true` and **non-strict** (`checkJs: false`, `strict: false`) so existing `.js` keeps compiling, then **type the highest-value, lowest-DOM seams first** — Models/interfaces (the data shapes flowing everywhere), the service/API layer (Q83), and ViewModel data — because those give the most type-safety leverage with the least friction. Convert files to `.ts` incrementally, tightening `strict` per-directory.

The **limits** to be honest about: (1) **The dynamic config system fights TS** — auto-generated getters/setters (`getFoo`/`setFoo` from a `config` block, Q27) aren't visible to TS unless the typings declare them, so `Ext.define`-based classes need generated or hand-written declarations to be fully typed. (2) **xtype/alias string-based instantiation** (`Ext.create('widget.usergrid')`, Q25) is inherently untyped — TS can't check a string alias resolves to a real class. (3) **`bind` expressions** (`bind: '{user.name}'`, Q30) are strings parsed at runtime; TS gives no safety there. (4) **Overrides** (Q37) mutate classes at runtime in ways TS's static model can't track. So you get strong typing on *your application logic, data models, and service boundaries* and *framework API calls*, but the *declarative ExtJS configuration surface* (xtypes, binds, dynamic configs) remains largely string-typed. The senior framing: TS adoption in ExtJS is **high-value at the logic/data seams and weak at the declarative-config surface** — target the former, accept the latter, and treat it as paving the road for an eventual React/TS migration where the whole stack becomes statically typed. It's worth doing precisely because the typed service/model layer (Q83) is the part you'll carry across a migration.

#### Q86. [Practical] Production incident: after a deploy, users report the app loads a blank white screen but only on some machines. How do you triage an ExtJS-specific cause?

A post-deploy blank screen affecting *some* users is a classic caching/build problem, and ExtJS's microloader (Q59) makes a few causes especially likely. Triage methodically:

**1. Confirm it's caching, not code.** The "only some machines" signature screams **stale cached assets**. ExtJS production builds use content-hashed filenames and a manifest; if a user's browser/CDN/proxy serves an *old* `app.js` against the *new* `index.html`/manifest (or vice-versa), the microloader tries to load resources that no longer match and the app fails to boot — a white screen with console errors like a 404 on a hashed file or a class/version mismatch. Have an affected user **hard-reload** (Ctrl+Shift+R); if it fixes them, it's caching.

```javascript
// Diagnostic: log the loaded build/version at boot so you can see mismatches.
console.log('build:', Ext.manifest && Ext.manifest.id, 'app v', MyApp.VERSION);
window.addEventListener('error', e => MyApp.telemetry.boot('error', e.message), true);
```

**2. Check the cache-busting and headers.** The fix-forward is correct cache headers: `index.html` (and the manifest) must be served **no-cache / short-TTL** so the browser always re-checks, while the hashed bundles can be cached **immutable/long-TTL** (their name changes when content changes). A misconfigured CDN caching `index.html` aggressively is the usual root cause — the browser keeps an old HTML pointing at hashed files that the new deploy deleted. Verify with DevTools Network: are responses coming from disk cache? Is the manifest fresh?

**3. Rule out the ExtJS-specific failure classes** (overlap with Q71): a missing `requires`/`uses` that only breaks on a lazily-reached boot path; a locale/profile (classic vs modern, Q13) resolving wrong on certain devices; a CSP (Q79) violation on some corporate machines with stricter policies. Each shows distinct console errors — read them.

**4. Capture boot telemetry.** Because a white screen often means JS died *before* the UI rendered, wire `window.onerror` and `Ext.Error.handle` (Q71) to a beacon that fires *independently of the framework being up*, so you get the real error from affected users instead of guessing.

The remediation and prevention: **roll back or re-deploy with correct cache headers** (no-cache HTML/manifest, immutable hashed assets) to stop the bleeding, then prevent recurrence by (a) never serving `index.html`/manifest from long-lived cache, (b) keeping the *previous* build's hashed assets deployed for a grace window so in-flight sessions don't 404 mid-load, and (c) emitting a build/version stamp at boot so mismatches are visible in telemetry. The senior framing: an ExtJS blank-screen-on-some-machines is almost always a **deploy/caching coherency** problem between the HTML/manifest and the hashed bundles, not a code bug — the microloader's manifest-driven loading makes asset-version skew fatal, so the operational fix is disciplined cache-control, atomic deploys, and overlapping old-asset retention, with boot telemetry to confirm.

#### Q87. [Theory] How does `Ext.util.Collection` underpin Stores, and why does understanding it matter for sorting, filtering, and grouping performance?

Beneath every `Ext.data.Store` sits an `Ext.util.Collection` — the general-purpose ordered, keyed, observable collection that actually implements the sorting/filtering/grouping the Store exposes. Understanding it explains *why* certain Store operations are cheap or expensive and where to optimize. A `Collection` holds items in insertion order, maintains an **index by key** (the Model's `idProperty`) for O(1) `getByKey`, and supports **sorters**, **filters**, and **groupers** as first-class, *stacked* configurations that produce a derived view over the source items.

The performance-relevant internals: (1) sorting maintains a comparator built from the sorter stack and re-sorts when sorters change — O(n log n); (2) filtering runs each item through the filter predicate(s) to build a filtered view — O(n × f); (3) grouping partitions items into a `Collection` of group `Collection`s. Crucially, the Collection **batches and defers** recomputation: it supports `beginUpdate()`/`endUpdate()` so that adding 1,000 items doesn't re-sort and re-filter 1,000 times — it recomputes the derived view *once* at `endUpdate`. This is the data-layer analog of `suspendLayouts` (Q15) and the same idea as the bind scheduler's batching (Q30).

```javascript
// The Store delegates to its Collection; you can batch mutations to it:
store.suspendEvents();             // or store.getData().beginUpdate()
bigArray.forEach(r => store.add(r));   // without batching, each add re-sorts/refilters
store.resumeEvents();
store.getData().endUpdate();       // single recompute of sorted/filtered/grouped view
```

Why it matters in practice: (1) **adding records one at a time without batching** re-runs the sorter/filter pipeline per add — turning an O(n log n) bulk load into O(n² log n); the fix is `loadData`/bulk add or explicit `beginUpdate`/`endUpdate`. (2) **Multiple sorters/filters stack**, so an expensive filter predicate runs on every item on every change — keep predicates cheap (the same lesson as Q16's debounced filter and Q69's renderer audit). (3) Because the Collection is **keyed**, `getById` is O(1), but `indexOf` by object or position-based operations can be O(n) — prefer key-based lookups in hot paths. (4) Local sort/filter (Q33) is *the Collection working in memory*; remote sort/filter bypasses it (the server does the work and the Collection just holds the returned page).

The interview depth signal is recognizing the Store as a thin, observable, proxy-aware wrapper over `Ext.util.Collection`, and that **the Collection's batching (`beginUpdate`/`endUpdate`) and keyed indexing are the levers for data-layer performance** — bulk-load instead of incremental add, keep sorter/filter functions cheap, use key lookups, and let remote mode offload to the server when the dataset is too large for the in-memory Collection to sort/filter efficiently. It's the same recurring theme — *batch the expensive recompute, don't trigger it per item* — that runs through layout, binding, and rendering.

#### Q88. [Practical] How do you measure and reduce ExtJS bundle size and initial load time, given the framework's reputation for being heavy?

ExtJS's bundle weight is a real liability (Q1, Q19), and a senior engineer should know concrete levers to measure and shrink it rather than treating "ExtJS is big" as immutable. **Measure first**: produce a production build and analyze it — the open-tooling webpack build (Q21) supports `webpack-bundle-analyzer` to visualize which classes/packages dominate; with Sencha Cmd, inspect the build report and the generated `app.js`/`app.css` sizes. Establish real numbers (gzipped transfer size, parse/eval time, time-to-interactive) before optimizing, because perception ("it feels slow") and the actual bottleneck (often *parse/eval* of a large JS bundle, not download) frequently differ — check the DevTools Performance "Evaluate Script" time, not just network bytes.

The reduction levers, in rough order of impact:

1. **Build only what you use (tree-shaking via the dependency graph).** This is the biggest lever and depends on *accurate `requires`/`uses`* (Q28): the compiler bundles the transitive closure of referenced classes, so over-broad `requires` (or `requires: 'Ext.*'`) drags in the whole framework. Audit dependencies; reference specific classes, not wildcards. A grid-only app shouldn't ship the charts package.
2. **Pick the right toolkit and theme** (Q13, Q11) — don't bundle both Classic and Modern if you serve one; a leaner theme (and pruned SASS, not every component's styles) cuts the CSS.
3. **Code-split / lazy-load** — defer rarely-used screens with `uses` (soft deps) and dynamic loading so the *initial* bundle is the core app, and heavy features (a reporting module, a settings dialog) load on demand. The microloader/profile system supports splitting the build into a small initial payload plus on-demand packages.
4. **Compress and cache** — serve gzip/brotli (a large JS bundle compresses well), set immutable long-TTL on hashed assets (Q86), and use a CDN. Brotli on the bundle often halves transfer.
5. **Drop dead code and unused locales** — strip debug builds, include only the needed `ext-locale` packs (Q56), remove unreferenced custom classes.

```javascript
// Lazy-load a heavy feature so it's NOT in the initial bundle:
Ext.define('MyApp.view.main.MainController', {
    extend: 'Ext.app.ViewController',
    uses: ['MyApp.view.reports.ReportPanel'],   // soft dep → can be split out
    onOpenReports: function () {
        // loaded on demand the first time the user opens reports
        this.getView().add(Ext.create('MyApp.view.reports.ReportPanel'));
    }
});
```

The honest trade-offs and framing: ExtJS will *never* be as small as a minimal React/Preact app — the framework floor (class system, layout engine, component library) is inherently larger than a view-only library, and that's the architectural price of "batteries included" (Q1). But the difference between a naively-built ExtJS app and a tuned one is large: accurate dependency declarations (avoiding "include the whole framework"), single-toolkit/pruned-theme builds, code-splitting heavy modules behind `uses`, and brotli+immutable caching can cut both transfer size and — often more importantly — parse/eval time substantially. The senior point: **don't optimize on vibes** — analyze the actual bundle, confirm whether the bottleneck is download, parse, or layout-on-boot, and apply the matching lever, while setting realistic expectations that ExtJS's floor is higher than modern lightweight frameworks (a real input to the migration calculus in Q19). The single highest-leverage habit is disciplined `requires`/`uses` so the compiler can tree-shake — sloppy dependency declarations are why many ExtJS bundles are far larger than they need to be.

## 🧩 Extended Questions — Supplemental Set B: Coding & Expert

### 🟢 Basic — extended

#### Q89. [Coding] Build a reusable custom field component that combines a label, a text input, and a live character counter using `Ext.define`.

**Problem:** Create a `myapp-countedfield` that wraps a textfield and shows "N / max" remaining, turning red past the limit. It must be declarable via `xtype` and expose a `maxLength` config with proper apply/update behavior (Q27).

The idiomatic approach is to *compose* rather than subclass `textfield` directly — extend `Ext.form.FieldContainer` and put the field plus a display label inside it with an `hbox` layout. This keeps the input semantics intact while giving us a slot for the counter. We use the `config` system so `maxLength` gets an auto-generated setter, and an `updateMaxLength` hook to re-render the counter when the limit changes at runtime.

```javascript
Ext.define('MyApp.form.CountedField', {
    extend: 'Ext.form.FieldContainer',
    alias: 'widget.countedfield',          // == xtype: 'countedfield'
    layout: { type: 'hbox', align: 'middle' },

    config: { maxLength: 100 },            // generates get/setMaxLength

    initComponent: function () {
        var me = this;
        me.items = [
            { xtype: 'textfield', flex: 1, reference: 'input',
              enableKeyEvents: true,
              listeners: { change: 'onTextChange', scope: me } },
            { xtype: 'component', reference: 'counter', width: 70,
              style: 'text-align:right;padding-left:8px;color:#666' }
        ];
        me.callParent(arguments);          // MUST call super (Q29)
        me.refreshCounter('');             // initial paint
    },

    onTextChange: function (field, value) {
        this.refreshCounter(value || '');
        this.fireEvent('textchange', this, value);   // bubble for binding/listeners
    },

    updateMaxLength: function () {         // side effect when config changes (Q27)
        if (this.rendered) {
            this.refreshCounter(this.down('textfield').getValue() || '');
        }
    },

    refreshCounter: function (value) {
        var counter = this.lookup('counter'),
            max     = this.getMaxLength(),
            over    = value.length > max;
        counter.setHtml(Ext.String.htmlEncode(value.length + ' / ' + max)); // escape (Q18)
        counter.setStyle('color', over ? '#c00' : '#666');
    }
});

// Usage:
Ext.create('Ext.form.Panel', {
    renderTo: Ext.getBody(), width: 360, bodyPadding: 10,
    items: [{ xtype: 'countedfield', fieldLabel: 'Bio', maxLength: 20 }]
});
```

**Why this design:** extending `FieldContainer` (not `Component`) means the wrapper participates correctly in form layout, label alignment, and `getValues()` collection. Exposing `maxLength` through `config` gives runtime mutability for free, and firing a `textchange` event keeps the component reusable in a ViewModel-bound context. **Edge cases:** a `null` value would throw on `.length`, so we coerce with `value || ''`; if `maxLength` changes before render we guard on `this.rendered`. **Complexity:** each keystroke is O(1) for the counter (string length), with the field's own `change` debounce available via `buffer` if needed.

#### Q90. [Coding] Render a list of products as cards using `Ext.XTemplate` and a `DataView`, with conditional badges and safe escaping.

**Problem:** Show products bound to a Store as styled cards; flag `inStock === false` with an "Out of stock" badge, format price as currency, and never allow product names to inject HTML.

A `DataView` (`Ext.view.View`, see Q53) is the right tool when you want full visual control per record rather than a tabular grid. The template uses `<tpl for>` to iterate, `<tpl if>` for the badge, and the `htmlEncode` format to neutralize XSS — recall from Q31 that `{value}` is **not** auto-escaped.

```javascript
Ext.create('Ext.view.View', {
    renderTo: Ext.getBody(),
    store: productStore,
    itemSelector: 'div.product-card',      // which nodes are "items" (selectable)
    emptyText: '<div class="empty">No products</div>',
    tpl: new Ext.XTemplate(
        '<tpl for=".">',
            '<div class="product-card {[ values.inStock ? "" : "dimmed" ]}">',
                '<h3>{name:htmlEncode}</h3>',                 // ESCAPED user data
                '<span class="price">{price:usMoney}</span>', // formatted currency
                '<tpl if="!inStock">',
                    '<span class="badge">Out of stock</span>',
                '</tpl>',
                '<p>Rank #{#}</p>',                           // {#} = 1-based index
            '</div>',
        '</tpl>',
        {
            // member function usable inside the template as {[ this.disc(values) ]}
            disc: function (v) { return v.price > 100 ? 'PREMIUM' : ''; }
        }
    ),
    listeners: {
        itemclick: function (view, record) {
            Ext.Msg.alert('Selected', Ext.String.htmlEncode(record.get('name')));
        }
    }
});
```

**Why DataView over Grid:** a grid forces a row/column visual model; a DataView gives a free-form template, ideal for cards, tiles, or media lists, while still binding to a Store and supporting selection, `itemclick`, and `BufferedRenderer` for large sets. **Trade-offs:** you lose the grid's built-in sorting headers, column resize, and editing plugins — you'd rebuild any of those yourself. **Security:** every record field rendered into HTML must pass through `htmlEncode` (or an escaping member function); the `{[ ... ]}` inline-JS blocks are powerful but must never interpolate raw user strings into markup. The compiled template caches after first render, so re-renders are fast.

#### Q91. [Theory] What is the difference between `value`, `rawValue`, and `submitValue` on a form field, and how does `getValue()` differ from `getSubmitData()`?

ExtJS form fields maintain a deliberate separation between the *displayed* representation, the *typed/parsed* value, and what actually gets **submitted**, and conflating them causes subtle data bugs. The **`rawValue`** is the literal string in the DOM input (`getRawValue()`) — for a `datefield` that's `"06/16/2026"`. The **`value`** (`getValue()`) is the parsed, typed value the field considers canonical — for that same `datefield` it's a JavaScript `Date` object, because the field runs its `parseValue`/`rawToValue` conversion. The two diverge whenever a field has a display format distinct from its underlying type (date, number, combo with display vs value fields).

The **`submitValue`** layer controls what crosses the wire. A field can have `submitValue: false` to be excluded from `getSubmitData()` entirely (common for purely-visual fields), and components like `combobox` submit their `valueField` (the id) while *displaying* the `displayField` (the label). `getValue()` returns the typed in-memory value for *your code*; `getSubmitData()` returns a name→string map formatted for the server (dates serialized via `submitFormat`, combos as their value id), which is what `form.submit()` posts.

```javascript
var df = Ext.create('Ext.form.field.Date', {
    name: 'due', format: 'm/d/Y', submitFormat: 'Y-m-d',
    value: new Date(2026, 5, 16)
});
df.getRawValue();   // "06/16/2026"  — what the user sees
df.getValue();      // Date object   — typed, for your logic
df.getSubmitData();  // { due: "2026-06-16" } — server-formatted string

var combo = Ext.create('Ext.form.field.ComboBox', {
    valueField: 'id', displayField: 'name', value: 42
});
combo.getRawValue();    // "Ada Lovelace" (display)
combo.getValue();       // 42            (the value submitted)
```

The interview point: when you read a field for client-side logic use `getValue()` (you get a real `Date`/`Number`, not a string), but understand that the server sees `getSubmitData()`'s formatted output — so date format mismatches, `submitValue: false` fields silently dropped, and combos sending ids not labels are all explained by this three-layer model. A frequent bug is comparing `getRawValue()` strings when you meant typed `getValue()` comparison, or expecting the displayed combo text on the server when it actually receives the value id.

### 🟡 Intermediate — extended

#### Q92. [Coding] Implement a custom grid plugin that adds an undo/redo stack for cell edits.

**Problem:** Build a `plugin.celledithistory` that records every cell edit on a grid, exposes `undo()`/`redo()`, and wires Ctrl+Z / Ctrl+Y. It must be attachable to any editable grid without subclassing (Q35).

A plugin's `init(grid)` receives the host instance, so we hook the `CellEditing` plugin's `edit` event to capture before/after values, push onto an undo stack, and apply inverse operations on undo. We use managed listeners via the grid (`mon`) so everything tears down with the grid (Q14).

```javascript
Ext.define('MyApp.plugin.CellEditHistory', {
    extend: 'Ext.plugin.Abstract',
    alias: 'plugin.celledithistory',

    init: function (grid) {
        this.grid = grid;
        this.undoStack = [];
        this.redoStack = [];
        this.suspend = false;                 // ignore edits we cause ourselves

        // CellEditing fires 'edit' with context {record, field, value, originalValue}
        grid.on('edit', this.onEdit, this);
        grid.getView().on('refresh', function () {}, this); // (no-op hook point)

        // keyboard: scoped to the grid element
        grid.mon(grid.el, 'keydown', this.onKey, this, { delegated: false });
    },

    onEdit: function (editor, ctx) {
        if (this.suspend) { return; }
        if (ctx.value === ctx.originalValue) { return; } // no real change
        this.undoStack.push({
            recId: ctx.record.id, field: ctx.field,
            from: ctx.originalValue, to: ctx.value
        });
        this.redoStack.length = 0;            // a new edit invalidates redo
    },

    onKey: function (e) {
        if (e.ctrlKey && e.getKey() === e.Z) { e.preventDefault(); this.undo(); }
        else if (e.ctrlKey && e.getKey() === e.Y) { e.preventDefault(); this.redo(); }
    },

    apply: function (entry, useFrom) {
        var rec = this.grid.getStore().getById(entry.recId);
        if (!rec) { return; }                 // record may have been removed
        this.suspend = true;
        rec.set(entry.field, useFrom ? entry.from : entry.to);
        this.suspend = false;
    },

    undo: function () {
        var entry = this.undoStack.pop();
        if (!entry) { return; }
        this.apply(entry, true);              // restore 'from'
        this.redoStack.push(entry);
    },

    redo: function () {
        var entry = this.redoStack.pop();
        if (!entry) { return; }
        this.apply(entry, false);             // re-apply 'to'
        this.undoStack.push(entry);
    },

    destroy: function () {
        this.undoStack = this.redoStack = null;
        this.callParent();
    }
});

// Usage — stack it alongside cell editing, zero subclassing:
Ext.create('Ext.grid.Panel', {
    plugins: { celledithistory: true, cellediting: { clicksToEdit: 1 } },
    /* store, columns ... */
});
```

**Why a plugin:** the behavior is optional and stackable, applies to grids you may not own, and is removable per instance — exactly the plugin sweet spot (Q35). **The `suspend` flag is critical:** without it, programmatically calling `rec.set()` during undo would fire another `edit`-derived change and corrupt the stack (an infinite-ish feedback loop). **Edge cases:** undoing an edit on a since-deleted record must no-op (`getById` returns null); a fresh edit clears the redo stack (standard undo semantics). **Complexity:** push/pop are O(1); memory is O(number of edits), so for very long sessions you'd cap the stack depth (e.g., keep the last 100 entries).

#### Q93. [Coding] Implement a typed event bus as a singleton so unrelated views can communicate without coupling.

**Problem:** Decouple a "notifications" panel from the dozens of views that emit events. Build an application-wide event bus (Q74) with `publish`/`subscribe`, automatic cleanup tied to subscriber lifecycle, and a guard against typo'd event names.

A `singleton: true` class mixing in `Ext.util.Observable` gives a relay object. The key correctness concern is leak-free unsubscription: subscribers register via their own `mon()` so the listener auto-removes on the subscriber's destroy, rather than leaking a closure into the global singleton (the classic global-listener leak, Q14/Q82).

```javascript
Ext.define('MyApp.EventBus', {
    extend: 'Ext.mixin.Observable',
    singleton: true,                          // instantiated once, used directly

    // whitelist of valid events — fire of an unknown name throws in dev
    KNOWN: { 'user.created': true, 'order.updated': true, 'alert.raised': true },

    publish: function (name) {
        // <debug>
        if (!this.KNOWN[name]) {
            Ext.raise('EventBus: unknown event "' + name + '"'); // stripped in prod build
        }
        // </debug>
        this.fireEvent.apply(this, arguments);
    },

    // subscriber MUST be a component so we can tie cleanup to its lifecycle
    subscribe: function (subscriber, name, handler, scope) {
        subscriber.mon(this, name, handler, scope || subscriber);
    }
});

// Emitter (anywhere, e.g. a save handler):
MyApp.EventBus.publish('order.updated', orderRecord);

// Subscriber view — auto-unsubscribes when destroyed:
Ext.define('MyApp.view.NotificationsPanel', {
    extend: 'Ext.panel.Panel',
    initComponent: function () {
        this.callParent(arguments);
        MyApp.EventBus.subscribe(this, 'order.updated', this.onOrderUpdated, this);
        MyApp.EventBus.subscribe(this, 'alert.raised',  this.onAlert, this);
    },
    onOrderUpdated: function (order) { this.add({ xtype: 'component',
        html: Ext.String.htmlEncode('Order ' + order.get('id') + ' updated') }); },
    onAlert: function (msg) { /* ... */ }
});
```

**Why a bus over alternatives:** ViewModel binding (Q30) couples views that share a ViewModel; a global Controller (Q7) re-introduces the singleton-selector coupling MVVM tried to remove. A bus is right for *broadcast* across unrelated subtrees (a notification raised in module A consumed by panel B). **Trade-offs (Q74):** a bus is implicit coupling — it's easy to lose track of who listens to what, so the `KNOWN` whitelist plus the `<debug>`-wrapped `Ext.raise` (compiled out of production) catches typo'd event names early. **Leak safety:** routing subscription through the subscriber's `mon` is the whole trick — a naive `EventBus.on(...)` from a view would keep that view alive forever because the singleton outlives every view. **When to avoid:** for request/response or sequenced flows use promises (Q40), not a fire-and-forget bus.

#### Q94. [Theory] Explain how `Ext.Function.createBuffered`, `createThrottled`, and `createDelayed` differ, and where each fits in an event-heavy UI.

These three wrappers solve different rate-limiting problems and choosing the wrong one produces either dropped updates or jank. **`createBuffered(fn, ms)`** is a *debounce*: each call resets a timer, and `fn` runs only `ms` after the **last** call — so a burst of N rapid calls produces exactly **one** invocation after the burst ends. This is what you want for "act when the user pauses": live search (Q16), resize-settled relayout, or validating after typing stops. **`createThrottled(fn, ms)`** caps the *rate*: `fn` runs at most once per `ms` window during a continuous stream, giving periodic updates **while** activity continues — right for scroll position readouts, drag-move feedback, or a progress meter, where you want regular sampling, not a single trailing call.

**`createDelayed(fn, ms)`** simply postpones a single call by `ms` (a one-shot timer wrapper); it does not coalesce bursts. It's used for deferring work past the current execution frame (e.g., focusing a field after a layout settles). The mental model:

```
calls:      | | | |       | |              (rapid burst, then quiet)
buffered:                  └─►fn            (one call, after last + ms)
throttled:  └─►fn  └─►fn   └─►fn            (periodic, max 1 per window)
delayed:    └────────►fn                    (each call → its own delayed fn)
```

```javascript
// Debounce: filter only after the user stops typing (Q16 alternative form)
field.on('change', Ext.Function.createBuffered(function (f, v) {
    store.filter('name', v);
}, 300));

// Throttle: update a "scrolled to %" readout smoothly but cheaply
view.getScrollable().on('scroll', Ext.Function.createThrottled(function () {
    statusBar.setText(Math.round(scrollPct()) + '%');
}, 100));
```

The interview nuance: the equivalent of `buffer:` in a `listeners` config is `createBuffered` (debounce), while `interval:` on a `task`/`TaskRunner` is closer to throttling. Misusing throttle where you need debounce (e.g., firing a server search every 100ms *during* typing instead of once after) hammers the backend; misusing debounce where you need throttle (e.g., only updating a drag indicator after the drag fully stops) makes the UI feel dead. Both ultimately reduce how often an O(n) handler runs, but with opposite timing semantics.

#### Q95. [Coding] Build a master-detail screen with two-way binding: selecting a row in a grid populates a bound form, and edits flow back live.

**Problem:** A grid of users; clicking a row shows an editable form bound to the selected record; typing in the form updates the grid cell live; a Save button is enabled only when the record is dirty. Use MVVM (Q7) — no manual `getValue`/`setValue` plumbing.

The trick is binding the grid's selection into the ViewModel (`selection` binding) and then binding the form fields to that record's fields. Two-way field bindings publish edits straight back into the record, so the grid cell updates through the same record instance — no copy, no sync code.

```javascript
Ext.define('MyApp.view.UserManager', {
    extend: 'Ext.panel.Panel',
    layout: 'border',
    viewModel: {
        data:  { selectedUser: null },
        formulas: {
            hasDirty: function (get) { var u = get('selectedUser'); return !!(u && u.dirty); }
        }
    },
    controller: 'usermanager',
    items: [
        {
            region: 'center', xtype: 'grid', reference: 'userGrid',
            store: { fields: ['name', 'email'], data: [
                { name: 'Ada', email: 'ada@x.com' }, { name: 'Bo', email: 'bo@x.com' }
            ]},
            bind: { selection: '{selectedUser}' },     // selected row → VM
            columns: [
                { text: 'Name',  dataIndex: 'name',  flex: 1 },
                { text: 'Email', dataIndex: 'email', flex: 1 }
            ]
        },
        {
            region: 'east', width: 300, xtype: 'form', bodyPadding: 10,
            bind: { disabled: '{!selectedUser}' },      // no selection → form off
            items: [
                { xtype: 'textfield', fieldLabel: 'Name',  bind: '{selectedUser.name}' },
                { xtype: 'textfield', fieldLabel: 'Email', bind: '{selectedUser.email}' }
            ],
            buttons: [
                { text: 'Save',   bind: { disabled: '{!hasDirty}' }, handler: 'onSave' },
                { text: 'Revert', bind: { disabled: '{!hasDirty}' }, handler: 'onRevert' }
            ]
        }
    ]
});

Ext.define('MyApp.view.UserManagerController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.usermanager',
    onSave: function () {
        var rec = this.getViewModel().get('selectedUser');
        rec.commit();        // mark clean; in real app: rec.save() to the server
    },
    onRevert: function () {
        this.getViewModel().get('selectedUser').reject();  // roll back dirty edits
    }
});
```

**Why this is the canonical MVVM pattern:** `bind: { selection: '{selectedUser}' }` makes the grid's selected record the single source of truth; the form fields bind two-way to that *same* record, so editing the form mutates the record and the grid cell re-renders automatically through the bind scheduler (Q30). The `hasDirty` formula reads `record.dirty` reactively to gate Save/Revert. **No manual sync** — that's the payoff versus old MVC where you'd `form.loadRecord()` then `form.updateRecord()` by hand and risk drift. **Edge cases:** the binding is async by a tick (Q30), so don't read the field value synchronously right after selection; `commit()` vs `save()` — `commit` only clears the dirty flag locally, a real app calls `record.save()` (or `store.sync()`) and commits on server success (Q65). Deselecting sets `selectedUser` to null, which disables the form via `{!selectedUser}`.

### 🟠 Advanced — extended

#### Q96. [Coding] Implement drag-and-drop reordering between two grids that share a model, with validation rejecting drops that exceed a target capacity.

**Problem:** Two grids ("Available" and "Assigned"); drag rows between them; the "Assigned" grid rejects a drop if it would exceed 5 rows; reordering within a grid is allowed. Use the grid's drag-drop plugin (Q52) and a `beforedrop` veto.

ExtJS grids expose drag-drop via `Ext.grid.plugin.DragDrop` (or the view's `viewConfig.plugins`). The capacity rule is enforced in the `beforedrop` event by returning `false`, and we register a shared `ddGroup` so both grids accept each other's rows.

```javascript
function makeGrid(title, ddGroup, store, capacity) {
    return {
        xtype: 'grid', title: title, flex: 1, store: store,
        columns: [{ text: 'Name', dataIndex: 'name', flex: 1 }],
        viewConfig: {
            plugins: {
                ptype: 'gridviewdragdrop',
                dragGroup: 'tasks', dropGroup: 'tasks'   // shared group = cross-grid DnD
            },
            listeners: {
                beforedrop: function (node, data, overModel, dropPos, dropFn) {
                    var targetStore = this.up('grid').getStore();
                    var incoming    = data.records.length;
                    if (capacity != null &&
                        targetStore.getCount() + incoming > capacity &&
                        data.view !== this) {                 // ignore intra-grid reorder
                        Ext.toast('Capacity ' + capacity + ' exceeded');
                        return false;                          // VETO the drop
                    }
                    return true;
                },
                drop: function (node, data) {
                    // records already moved between stores by the plugin;
                    // here you'd persist: data.records.forEach(r => r.save());
                }
            }
        }
    };
}

Ext.create('Ext.panel.Panel', {
    renderTo: Ext.getBody(), layout: 'hbox', width: 600, height: 400,
    defaults: { margin: 5 },
    items: [
        makeGrid('Available', 'tasks', availableStore, null),
        makeGrid('Assigned (max 5)', 'tasks', assignedStore, 5)
    ]
});
```

**Why `beforedrop` for validation:** it fires *before* the records are moved and its return value is a hard veto — returning `false` cancels the entire drop, leaving both stores untouched, which is exactly the transactional behavior you want (no half-applied moves). The `dragGroup`/`dropGroup` matching is what permits cross-grid drops; identical groups on both grids let either accept the other's rows. **The intra-grid check** (`data.view !== this`) lets you reorder *within* the full grid without tripping the capacity rule, since reordering doesn't change the count. **Edge cases:** multi-row drags (`data.records.length` may be > 1, so check total not +1); dropping onto the same grid; and persistence — the plugin mutates the stores but you must still `save()`/`sync()` to the server, ideally in the `drop` handler after the move succeeds. **Failure mode at scale:** with very large stores, the default HTML5/`Ext.dd` drag proxy can lag; you'd constrain drag to selected rows and avoid re-rendering both full grids on every drop.

#### Q97. [Coding] Write a custom `Ext.data.proxy` that talks to a GraphQL endpoint instead of REST.

**Problem:** ExtJS proxies are REST/Ajax-first (Q44). Implement a `proxy.graphql` so a Store can `load()` against a GraphQL API, mapping `operation` (page/sort/filter) into a GraphQL query and reading the nested result.

The clean approach is to extend `Ext.data.proxy.Ajax` (it already handles the XHR, reader, and operation plumbing) and override `buildRequest`/`doRequest` to POST a GraphQL query body, plus configure the reader's `rootProperty` to dig into `data.<queryName>`.

```javascript
Ext.define('MyApp.proxy.GraphQL', {
    extend: 'Ext.data.proxy.Ajax',
    alias: 'proxy.graphql',

    config: {
        query: null,            // the GraphQL query string with $page/$sort vars
        operationName: null,
        actionMethods: { read: 'POST' }   // GraphQL is POST
    },

    // Translate the ExtJS operation into a GraphQL POST body
    buildRequest: function (operation) {
        var request = this.callParent(arguments);
        var sorters = operation.getSorters() || [];
        var filters = operation.getFilters() || [];
        request.setJsonData({
            query: this.getQuery(),
            variables: {
                page:  operation.getPage(),
                limit: operation.getLimit(),
                sort:  sorters.map(function (s) {
                    return { field: s.getProperty(), dir: s.getDirection() };
                }),
                filter: filters.map(function (f) {
                    return { field: f.getProperty(), value: f.getValue() };
                })
            }
        });
        return request;
    }
});

// Reader digs into data.users.items / data.users.total
Ext.create('Ext.data.Store', {
    model: 'MyApp.model.User',
    pageSize: 25,
    remoteSort: true, remoteFilter: true,
    proxy: {
        type: 'graphql',
        url: '/graphql',
        query: 'query Users($page:Int,$limit:Int,$sort:[SortInput],$filter:[FilterInput]){' +
               '  users(page:$page,limit:$limit,sort:$sort,filter:$filter){' +
               '    items{ id name email } total } }',
        reader: {
            type: 'json',
            rootProperty: 'data.users.items',   // nested root (dot path supported)
            totalProperty: 'data.users.total'
        }
    },
    autoLoad: true
});
```

**Why extend `Ajax` not `Proxy`:** the Ajax proxy already implements request lifecycle, callback wiring, exception handling, and reader integration; we only need to reshape the request body, so overriding `buildRequest` is the minimal, robust change. The reader's dotted `rootProperty` (`data.users.items`) is the underused feature that makes GraphQL's nested envelope work without a custom reader. **Trade-offs:** this is read-focused; full CRUD needs `create`/`update`/`destroy` to emit GraphQL mutations, which means overriding the writer side too (or a separate mutation path), since one GraphQL operation per record-batch differs from REST's verb-per-endpoint model. GraphQL's strength — fetching exactly the fields the Model declares — could be exploited by generating the `items{ ... }` selection set from the Model's `fields`, eliminating over-fetching. **Failure modes:** GraphQL returns HTTP 200 even on errors (errors live in a top-level `errors` array), so a correct implementation must override the exception detection to inspect `response.errors` rather than trusting the status code — a classic gotcha when bolting GraphQL onto a REST-shaped client.

#### Q98. [Coding] Author a custom container layout from scratch — a "masonry" layout that packs items into the shortest column.

**Problem:** Build `layout.masonry` that arranges variable-height child items into N columns, always placing the next item under the currently-shortest column (Pinterest-style). Implement the layout contract (Q73).

A custom layout extends `Ext.layout.container.Container` and implements `calculate(ownerContext)`, reading each child's measured height from its `childContext` and writing absolute positions. We respect the batched layout context (Q15) — read all sizes, compute, then publish — to avoid thrashing.

```javascript
Ext.define('MyApp.layout.Masonry', {
    extend: 'Ext.layout.container.Auto',
    alias: 'layout.masonry',

    config: { columns: 3, gutter: 10 },

    beginLayout: function (ownerContext) {
        this.callParent(arguments);
        // ensure the owner element is positioned so children can be absolute
        this.owner.getTargetEl().setStyle('position', 'relative');
    },

    calculate: function (ownerContext) {
        var me      = this,
            cols    = me.getColumns(),
            gutter  = me.getGutter(),
            items   = ownerContext.childItems,
            ownerW  = ownerContext.getProp('contentWidth'),
            colW    = Math.floor((ownerW - gutter * (cols - 1)) / cols),
            heights = Ext.Array.fill(new Array(cols), 0),  // running col heights
            i, item, target, x, y;

        if (!Ext.isNumber(ownerW)) {       // width not measured yet → retry next cycle
            me.done = false;
            return;
        }

        for (i = 0; i < items.length; i++) {
            item   = items[i];
            target = heights.indexOf(Math.min.apply(Math, heights)); // shortest column
            x = target * (colW + gutter);
            y = heights[target];

            item.setProp('x', x);
            item.setProp('y', y);
            item.setWidth(colW);

            var h = item.getProp('height') ||
                    item.target.getHeight();   // measured child height
            heights[target] = y + h + gutter;
        }

        // publish the container's own content height (tallest column)
        ownerContext.setContentHeight(Math.max.apply(Math, heights) + me.owner.el.getPadding('tb'));
        me.done = true;
    }
});

// Usage:
Ext.create('Ext.panel.Panel', {
    renderTo: Ext.getBody(), width: 640, height: 500, scrollable: true,
    layout: { type: 'masonry', columns: 3, gutter: 12 },
    defaults: { xtype: 'component', style: 'background:#cde;position:absolute' },
    items: tiles   // each with its own natural height
});
```

**The layout contract (Q73):** `calculate` may be called **multiple times** in one layout run as dependent sizes resolve — that's why we set `me.done = false` and bail when `contentWidth` isn't measured yet; the context re-invokes us once the width is known. We read children via `ownerContext.childItems` (their `childContext` objects), use `getProp`/`setProp` to participate in the batched read/write rather than touching the DOM directly, and `setContentHeight` so the parent (and any scrollable) sizes correctly. **Why a custom layout is justified here:** no built-in layout (Q32) does shortest-column packing; `column` layout wraps in fixed order, not by height. **When NOT to:** if you can compose `column` + CSS you should — a custom layout is real maintenance burden and must be re-validated on framework upgrades. **Complexity:** O(items × columns) for the min-search per item; fine for hundreds of tiles, but for thousands you'd switch to a heap to find the shortest column in O(log cols).

#### Q99. [Coding] Implement async remote validation on a model field ("is this email already taken?") that integrates with form validity and binding.

**Problem:** As the user types an email, debounce-check the server for uniqueness; mark the field invalid with a server message if taken; ensure the form's `isValid()` and a bound Save button reflect the async result. (Q67 covers the concept; here is the full implementation.)

ExtJS field validation is synchronous by design, so async validation requires manually driving the field's valid/invalid state and integrating it with the form. We debounce the check, call the API, and use `markInvalid`/`clearInvalid` plus a custom validity flag the Save binding reads.

```javascript
Ext.define('MyApp.view.SignupController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.signup',

    onEmailChange: function (field, value) {
        var me = this;
        field.clearInvalid();
        me.setEmailChecked(field, null);                 // mark "unknown/pending"

        if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value)) { // sync format gate first
            return;
        }
        // debounce per-field so we hit the server once typing pauses
        clearTimeout(me._emailTimer);
        me._emailTimer = setTimeout(function () {
            me.checkEmail(field, value);
        }, 350);
    },

    checkEmail: function (field, value) {
        var me = this;
        field.setLoading ? field.setLoading(true) : null;
        Ext.Ajax.request({
            url: '/api/users/check-email',
            params: { email: value },
            success: function (resp) {
                var taken = Ext.decode(resp.responseText).taken;
                if (field.getValue() !== value) { return; } // stale: user typed more
                if (taken) {
                    field.markInvalid('That email is already registered');
                    me.setEmailChecked(field, false);
                } else {
                    field.clearInvalid();
                    me.setEmailChecked(field, true);
                }
            },
            failure: function () {
                me.setEmailChecked(field, null);            // unknown on network error
            }
        });
    },

    // publish async validity into the ViewModel so bindings can react
    setEmailChecked: function (field, ok) {
        this.getViewModel().set('emailOk', ok === true);
    },

    onSubmit: function () { /* form.submit() — gated by binding below */ }
});

Ext.define('MyApp.view.Signup', {
    extend: 'Ext.form.Panel',
    controller: 'signup',
    viewModel: { data: { emailOk: false } },
    items: [
        { xtype: 'textfield', name: 'email', fieldLabel: 'Email',
          listeners: { change: 'onEmailChange' } }
    ],
    buttons: [
        { text: 'Sign up', handler: 'onSubmit',
          bind: { disabled: '{!emailOk}' } }   // enabled only after server says OK
    ]
});
```

**Why manual driving:** field validators run synchronously and can't await a network call, so the pattern is: gate on the sync format check first (don't waste a request on `"abc"`), debounce (Q94) the server call, and reflect the async outcome via `markInvalid`/`clearInvalid` (visual) **and** a ViewModel flag (`emailOk`) that bindings consume. **The stale-response guard** (`field.getValue() !== value`) is essential: if the user keeps typing, an in-flight response for an older value must be discarded, or you get flicker and wrong validity — a race every async-validation implementation must handle. **Network failure** maps to "unknown," not "valid," so we keep Save disabled rather than letting a failed check pass silently. **Trade-off:** this couples the controller to validity state; an alternative is a reusable async-validator mixin so multiple fields share the debounce+race-guard logic instead of copy-pasting it.

#### Q100. [Coding] Build a widget column that renders a live progress bar plus an action button per grid row, bound to record data.

**Problem:** In a "deployments" grid, each row shows a progress bar reflecting `record.percent` and a "Cancel" button that fires an event the controller handles. Use `widgetcolumn` (Q49) so each cell hosts a real component bound to the row record.

`widgetcolumn` instantiates one widget per visible row and rebinds it as rows recycle under `BufferedRenderer` (Q39), so it's efficient even for large grids. The widget's `defaultBindProperty` (or an explicit `onWidgetAttach`) wires record data into the widget.

```javascript
Ext.create('Ext.grid.Panel', {
    renderTo: Ext.getBody(), height: 400, width: 600,
    controller: 'deployments',
    store: { fields: ['name', 'percent', 'state'], data: [
        { name: 'api',  percent: 0.4, state: 'running' },
        { name: 'web',  percent: 1.0, state: 'done' }
    ]},
    columns: [
        { text: 'Service', dataIndex: 'name', flex: 1 },
        {
            text: 'Progress', width: 200, xtype: 'widgetcolumn',
            dataIndex: 'percent',                 // feeds defaultBindProperty
            widget: { xtype: 'progressbarwidget', textTpl: '{percent:percent}' }
        },
        {
            text: 'Action', width: 100, xtype: 'widgetcolumn',
            // recycled per row; bind via onWidgetAttach for per-record wiring
            onWidgetAttach: function (col, widget, record) {
                widget.setDisabled(record.get('state') !== 'running');
                widget._record = record;          // stash for the handler
            },
            widget: {
                xtype: 'button', text: 'Cancel',
                handler: function (btn) {
                    var grid = btn.up('grid');
                    grid.getController().onCancel(btn._record);
                }
            }
        }
    ]
});

Ext.define('MyApp.view.DeploymentsController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.deployments',
    onCancel: function (record) {
        record.set('state', 'cancelled');         // updates the row; widgetcolumn rebinds
        Ext.toast('Cancelling ' + Ext.String.htmlEncode(record.get('name')));
    }
});
```

**Why `widgetcolumn` over a cell renderer:** a `renderer` can only return an HTML string — it cannot host a stateful, interactive component with its own events. `widgetcolumn` mounts a real ExtJS widget per row, so you get a true `progressbarwidget` and `button` with handlers, while the column **recycles** widgets across rows during virtualized scrolling rather than creating one per record (the efficiency point from Q39). **`onWidgetAttach` is the rebind hook:** because widgets are recycled, you must re-wire per-record state (here, disabling Cancel for non-running rows) every time a widget is attached to a new record — putting that logic in the static `widget` config alone would not update on recycle. **`defaultBindProperty`** is why the progress bar's `value` tracks `dataIndex: 'percent'` automatically. **Edge cases:** stashing `_record` is a pragmatic pattern, but in strict MVVM you'd resolve the record from the row context instead; updating `record.set` re-runs the bind and refreshes the bar/button without a full grid reload (critical for the live-update scenario from Q24).

#### Q101. [Practical] You inherited an ExtJS 4 MVC app with "god controllers" and global-id soup. Walk through refactoring one screen to MVVM safely without a rewrite.

The realistic constraint is that you can't pause feature work for a big rewrite (Q19/Q20), so you refactor **one screen at a time**, leaving the rest on MVC, and prove the new pattern before spreading it. Pick a self-contained, high-churn screen first — somewhere the leak/collision pain is real and the win is visible.

The sequence: (1) **Introduce a ViewController** for the screen's view and move that screen's handler methods off the global `Ext.app.Controller` into it, converting `control({ '#globalId': ... })` selectors into scoped `listeners`/`reference` lookups (Q12). (2) **Replace `Ext.getCmp('id')` and global `refs`** with `reference`/`lookup` so two instances of the screen no longer collide. (3) **Add a ViewModel** and migrate imperative `setValue`/`getValue`/`enable`/`disable` calls into declarative `bind` (Q95). (4) **Wire managed cleanup** — the ViewController auto-destroys with the view, eliminating the global-listener leak the old controller caused (Q14). (5) **Leave the old global controller in place** but strip the migrated screen's logic out of it, so other still-MVC screens keep working.

```javascript
// BEFORE (MVC): app-scoped singleton controller, global ids, leak-prone
Ext.define('Old.controller.Users', {
    extend: 'Ext.app.Controller',
    refs: [{ ref: 'userGrid', selector: '#userGrid' }],   // global selector
    init: function () {
        this.control({ '#saveBtn': { click: this.onSave } }); // global, never cleaned
    },
    onSave: function () { Ext.getCmp('userGrid').getStore().sync(); } // hard global id
});

// AFTER (MVVM): instance-scoped, reference-based, auto-cleaned
Ext.define('App.view.users.UsersController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.users',
    onSave: function () { this.lookup('userGrid').getStore().sync(); } // scoped lookup
});
// view.js: controller:'users', grid has reference:'userGrid',
//          Save button has listeners:{ click:'onSave' }
```

The key risk to manage is **partial migration coexistence**: while one screen is MVVM and others are MVC, a lingering global controller might still try to `control()` a selector that now matches the refactored view, double-handling events. Mitigate by making selectors specific (scope to the new view's xtype) and by adding analytics/logging to confirm the old handlers no longer fire for the migrated screen. I'd also add a component test (Q46) for the refactored screen *before* touching it, so I have a regression net. The honest framing for stakeholders: this is incremental de-risking — each migrated screen is independently shippable, the leak/collision class of bugs shrinks screen-by-screen, and the codebase trends toward the modern pattern without a flag-day rewrite. The anti-pattern to avoid is a half-migrated screen that has *both* a global controller handler and a ViewController handler for the same event.

#### Q102. [Coding] Implement a polling task that refreshes a store on an interval, pauses when the tab is hidden, and backs off on errors.

**Problem:** Keep a dashboard store fresh every 10s, but stop polling when the browser tab is hidden (save server load and battery), and exponentially back off if the server starts failing. Use `Ext.util.TaskManager`/`TaskRunner` rather than naked `setInterval`.

`TaskRunner` integrates with the framework lifecycle and gives clean start/stop, while the Page Visibility API gates polling. Error backoff prevents a failing backend from being hammered every 10s.

```javascript
Ext.define('MyApp.util.StorePoller', {
    config: { store: null, baseInterval: 10000, maxInterval: 120000 },

    constructor: function (cfg) {
        this.initConfig(cfg);
        this.interval = this.getBaseInterval();
        this.runner = new Ext.util.TaskRunner();
        this.bindVisibility();
        this.start();
    },

    start: function () {
        this.stop();
        this.task = this.runner.start({
            run: this.tick, scope: this,
            interval: this.interval
        });
    },

    stop: function () { if (this.task) { this.task.destroy(); this.task = null; } },

    tick: function () {
        var me = this;
        me.getStore().load({
            callback: function (recs, op, success) {
                if (success) {
                    if (me.interval !== me.getBaseInterval()) {  // recovered → reset
                        me.interval = me.getBaseInterval();
                        me.start();
                    }
                } else {
                    // exponential backoff, capped
                    me.interval = Math.min(me.interval * 2, me.getMaxInterval());
                    me.start();
                }
            }
        });
    },

    bindVisibility: function () {
        var me = this;
        me.visHandler = function () {
            if (document.hidden) { me.stop(); }
            else { me.tick(); me.start(); }   // immediate refresh on return, then resume
        };
        document.addEventListener('visibilitychange', me.visHandler);
    },

    destroy: function () {
        this.stop();
        this.runner.destroy();
        document.removeEventListener('visibilitychange', this.visHandler);
    }
});

// Usage — tie lifecycle to a view so it cleans up (Q14):
var poller = Ext.create('MyApp.util.StorePoller', { store: dashboardStore });
// ... view.on('destroy', function(){ poller.destroy(); });
```

**Why `TaskRunner` over `setInterval`:** it's cancelable as a unit, plays with ExtJS's timing, and avoids the orphaned-timer leak class (Q82) where a `setInterval` keeps firing after the view is gone, holding the whole closure alive. **Visibility gating** is the single biggest server-load win for dashboards left open all day — a hidden tab shouldn't poll at all; on return we do an immediate `tick()` so the user sees fresh data instantly rather than waiting a full interval. **Backoff correctness:** doubling capped at `maxInterval` prevents a transient outage from generating a thundering-herd of retries, and resetting to base on the first success restores responsiveness. **Edge cases:** overlapping requests if a `load` outlives the interval — guard with an `inFlight` flag if loads can exceed the interval; the immediate `tick()` on visibility-return must not double-fire with the restarted task (here `start()` schedules the *next* tick, and the manual `tick()` is the immediate one). **The destroy hook is mandatory** — a poller whose owning view is destroyed but which keeps polling is both a leak and a correctness bug (loading into a dead store).

### 🔴 Expert — extended

#### Q103. [Behavioral] Tell me about a time you led the team through a high-stakes ExtJS production incident under pressure. (STAR)

**Situation:** During quarterly close, our ExtJS 6 finance-ops console started intermittently freezing for a subset of users right after a Friday deploy — the exact worst window, because controllers needed it to reconcile numbers by Monday. Reports were vague ("it hangs"), reproduction was inconsistent, and leadership was escalating hourly.

**Task:** As the staff engineer on the team, I owned both the technical resolution and keeping a stressed group coordinated rather than thrashing. The pressure was to "just roll back," but the deploy also contained a compliance fix we couldn't lose, so a blind rollback wasn't free.

**Action:** I first imposed structure: one person owned comms/status updates so the rest of us weren't answering the same questions repeatedly, and I started an incident doc capturing every hypothesis and what ruled it in or out. We couldn't reproduce locally, so I had us pull a heap snapshot and a Performance trace from an *affected* user's machine via screen-share (the technique from Q14/Q69). The flamegraph showed the main thread pinned in layout — a grid with a newly-added client-side summary feature was being fed an unpaged store for certain large accounts, materializing tens of thousands of rows and thrashing layout (Q15/Q39). It only hit users whose accounts crossed that size threshold, which explained the "some machines" pattern (the same class of bug as Q86's triage). Rather than revert the whole deploy, I shipped a targeted hotfix: re-enable remote paging + `BufferedRenderer` on that grid and compute the summary server-side, preserving the compliance change.

**Result:** The hotfix went out within about three hours and the freezes stopped; we kept the compliance fix and met the Monday deadline. In the postmortem I pushed two durable changes: a CI guard that fails the build if a grid binds an unpaged store above a row threshold, and a "large-account" test fixture so this size-dependent class of bug gets caught pre-deploy. **The lesson I emphasize:** under pressure the instinct is to act fast on the loudest suggestion (full rollback), but the higher-leverage move was 30 minutes of disciplined measurement on a *real affected environment* to find the actual retainer — and separating incident-command roles so the team measured instead of thrashed. Calm structure plus evidence beat speed-without-diagnosis.

#### Q104. [Theory] How do you design a feature-flag and A/B-testing system in a compiled ExtJS app, given the build-time nature of the framework?

The central tension is that ExtJS is **compiled** (Q21/Q70) — SASS themes, the class graph, and the bundle are produced at build time — so the runtime flexibility feature flags assume must be layered carefully or you defeat tree-shaking and ship dead code. The design splits flags into two tiers by *what* they gate. **Runtime/behavioral flags** (show a button, change a default, route to a new handler) are easy: fetch a flags payload at bootstrap (during the application's `init`/`launch`, Q43), expose it via a singleton or seed it into the root ViewModel, and gate behavior with `bind: { hidden: '{!flags.newExport}' }` or branches in ViewController logic. These cost nothing structurally because both code paths already ship.

**Structural flags** (an entirely new screen or heavy module that shouldn't be in the initial bundle for users who won't see it) are harder, because if both versions are statically `requires`d they both get bundled. The technique is to make the gated module a **`uses` (soft) dependency** loaded on demand (Q28/Q88), so the flag check decides whether to `Ext.create` (and thus lazy-load) the module at all — the new variant stays out of the initial payload and only loads for users in the experiment. For deep theme/visual experiments, runtime CSS-variable swapping via Fashion (Q70) or shipping an alternate compiled CSS bundle behind the flag handles the build-time theming limitation.

```javascript
// Seed flags into the root ViewModel at launch so bindings can gate UI:
launch: function () {
    Ext.Ajax.request({ url: '/api/flags', success: function (r) {
        var flags = Ext.decode(r.responseText);
        Ext.create('MyApp.view.main.Main', {
            viewModel: { data: { flags: flags } }
        });
    }});
}

// Behavioral gate (both paths already in bundle):
{ xtype: 'button', text: 'New Export', bind: { hidden: '{!flags.newExport}' } }

// Structural gate (heavy module lazy-loaded only for the variant):
onOpenReports: function () {
    if (this.getViewModel().get('flags.reportsV2')) {
        Ext.require('MyApp.view.reports.ReportsV2', function () {   // soft, on-demand
            this.getView().add({ xtype: 'reports-v2' });
        }, this);
    } else { this.getView().add({ xtype: 'reports-v1' }); }
}
```

**The A/B measurement** layer is framework-neutral: tag the user's bucket server-side, emit analytics events on the gated interactions (an event bus, Q93, is handy for centralizing this), and correlate. **Key trade-offs:** behavioral flags are cheap but mean both variants ship (fine for small differences, wasteful for large ones); structural lazy-loading keeps the bundle lean but adds a load delay on first use and complicates testing (both paths need coverage, Q46). The failure mode to avoid is flag-gating with hard `requires` on both branches — you then ship *every* experiment to *every* user, which is exactly the bundle-bloat (Q88) the framework is already criticized for. Flags should also be **removable**: stale flags rot, so pair each with an expiry/cleanup ticket.

#### Q105. [Coding] Implement an optimistic-update pattern with rollback for a record save, including server-side optimistic-concurrency (version/ETag) conflict handling.

**Problem:** When the user edits and saves a record, update the UI immediately (optimistic, Q38), send to the server, and if the server rejects due to a stale version (someone else edited concurrently), roll back and surface a conflict — don't silently overwrite. This is the correctness backbone of any multi-user ExtJS editing screen.

The pattern: keep the pre-edit snapshot, apply the edit optimistically (binding already did this), send with the record's `version`, and on a 409 Conflict either reject (rollback) or offer a merge. We use `record.save()` with explicit success/failure handling.

```javascript
Ext.define('MyApp.view.OrderEditController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.orderedit',

    onSave: function () {
        var rec = this.getViewModel().get('order');
        if (!rec.dirty) { return; }

        // snapshot the previous values so we can roll back precisely
        var previous = Ext.clone(rec.getData());      // shallow copy of all fields
        var changed  = rec.getChanges();              // only the modified fields

        rec.save({
            // the proxy writer should include 'version' so the server can compare
            success: function (record, op) {
                record.commit();                       // clear dirty; server accepted
                Ext.toast('Saved');
            },
            failure: function (record, op) {
                var resp = op.getResponse();
                if (resp && resp.status === 409) {
                    // CONFLICT: server's version != ours → don't clobber
                    record.reject();                   // roll back to last committed state
                    var server = Ext.decode(resp.responseText).current;
                    this.showConflict(record, changed, server);
                } else {
                    record.reject();                   // generic failure → rollback
                    Ext.Msg.alert('Save failed', 'Please retry.');
                }
            },
            scope: this
        });
    },

    showConflict: function (record, myChanges, serverData) {
        var me = this;
        Ext.Msg.show({
            title: 'Edit conflict',
            message: 'This order was changed by someone else. ' +
                     'Keep your changes (overwrite) or load theirs?',
            buttons: Ext.Msg.YESNO,
            buttonText: { yes: 'Overwrite', no: 'Load theirs' },
            fn: function (btn) {
                if (btn === 'yes') {
                    record.set('version', serverData.version);   // adopt new version
                    record.set(myChanges);                       // re-apply my edits
                    me.onSave();                                  // retry with fresh version
                } else {
                    record.set(serverData);                      // take server's values
                    record.commit();
                }
            }
        });
    }
});
```

**Why optimistic + rollback:** the UI updated instantly through binding (Q95) the moment the user typed, so the perceived latency is zero; the network round-trip happens behind that. If it succeeds, `commit()` finalizes; if it fails, `reject()` restores the last committed snapshot — ExtJS records track this for free via their modified/phantom state, which is why `reject()` is exact rather than us manually restoring fields. **The concurrency token is the crux:** without sending and checking a `version`/ETag, two users editing the same order produce a last-write-wins silent data loss — the server *must* compare versions and return 409 on mismatch, and the client *must* treat 409 as a first-class outcome, not a generic error. **The merge UX** (overwrite vs. take-theirs) is a product decision, but the engineering invariant is: never auto-overwrite a stale write. **Edge cases:** the retry-after-overwrite path must adopt the server's new version first (or it'll 409 again immediately); a network failure (no response) is *not* a conflict and shouldn't offer merge — distinguish `409` from connection errors. This ties Q38's optimistic theory to a concrete, race-safe implementation.

#### Q106. [Theory] How would you implement micro-frontends with ExtJS, where an ExtJS app and other-framework apps are composed at runtime? What are the hard problems?

Micro-frontends compose independently-deployed apps into one experience, and ExtJS is an awkward but workable participant because it's a heavyweight, all-in-one framework (Q1) that assumes it owns the page. The viable architectures are: (1) **route-based composition** — a thin shell (often a tiny non-ExtJS router) owns the URL and mounts whole apps per route into a container, with ExtJS owning entire routes rather than fragments; (2) **Web Components as the boundary** — wrap the ExtJS app (or specific ExtJS views) in a custom element so other frameworks embed it as `<extjs-orders>`, which gives Shadow DOM-ish isolation; and (3) **the mount-bridge** approach (Q17) where ExtJS hosts foreign widgets or vice-versa via lifecycle hooks. Route-based is the cleanest for ExtJS because it sidesteps the framework's page-ownership assumptions.

The hard problems are mostly *not* rendering — they're the shared concerns. **CSS isolation:** ExtJS injects a large global stylesheet with `x-` prefixed but still global selectors, which can bleed into or be clobbered by other apps; you need build-time CSS namespacing/scoping (a CSS prefix in the SASS build) or Shadow DOM, and ExtJS in Shadow DOM has historically had focus/measurement quirks because the layout engine measures against `document`. **Multiple framework runtimes:** loading ExtJS *and* React *and* Angular means several large runtimes on one page — the memory/parse cost (Q88) multiplies, so you lazy-load each micro-frontend's bundle only when its route activates. **Shared state, auth, and routing** (the same boundary risk as Q20): all apps must agree on session/auth and not fight over browser history; centralize these in a framework-neutral shell module that each app consumes, rather than letting ExtJS's router and another framework's router both try to own the URL.

```
                ┌─────────────── Shell (thin, framework-neutral) ───────────────┐
                │  owns: URL/history · auth/session · cross-app event bus        │
                └───────┬───────────────────┬───────────────────┬───────────────┘
            route /ops  ▼        route /rpt ▼        route /admin ▼
            ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
            │ ExtJS micro-fe │  │ React micro-fe │  │ Angular m-fe   │
            │ (own bundle,   │  │ (own bundle)   │  │ (own bundle)   │
            │  lazy-loaded)  │  │                │  │                │
            └────────────────┘  └────────────────┘  └────────────────┘
                CSS scoped         CSS modules         view encapsulation
```

The honest assessment: ExtJS is best as a **coarse-grained** participant (it owns whole routes/screens), not a fine-grained one (don't try to sprinkle tiny ExtJS widgets across a React page — the runtime cost and global-CSS friction aren't worth it). This architecture is, in practice, often the *transitional* shape of a strangler migration (Q20): the shell + route-based micro-frontends let new React/Angular routes ship alongside legacy ExtJS routes, peeling screens off one route at a time until the ExtJS bundle can finally be dropped. The dominant failure modes are CSS bleed, duplicated runtimes inflating load time, and split-brain auth/routing — all solvable but all *operational* rather than rendering problems, which is the point worth making in an interview.

#### Q107. [Coding] Build a server-side export-to-CSV that streams a large filtered grid's *current* sort/filter state to the backend and downloads the result.

**Problem:** A user has a 2M-row grid with remote sort/filter (Q33) and wants to export *exactly what they're looking at* (current filters + sort, not just the loaded page) to CSV. You cannot build the CSV client-side (the rows aren't loaded). Send the store's effective query to the server, which streams the file back.

The approach: extract the store's active sorters and filters in the same serialized form the proxy uses, hand them to the server via a hidden-form POST (so the browser handles the file download natively), and let the backend stream the CSV. We reuse the proxy's encoding so server-side filtering matches the grid exactly.

```javascript
Ext.define('MyApp.view.OrdersController', {
    extend: 'Ext.app.ViewController',
    alias: 'controller.orders',

    onExportCsv: function () {
        var store   = this.lookup('ordersGrid').getStore();
        var proxy   = store.getProxy();

        // Serialize current filters/sorters EXACTLY as the proxy would for load(),
        // so the export matches the on-screen result set.
        var params = {
            sort:   Ext.encode(store.getSorters().items.map(function (s) {
                        return { property: s.getProperty(), direction: s.getDirection() };
                    })),
            filter: Ext.encode(store.getFilters().items.map(function (f) {
                        return { property: f.getProperty(),
                                 value: f.getValue(), operator: f.getOperator() };
                    }))
        };

        // Native download via a transient form POST (handles big files; no XHR blob limit)
        this.submitDownload('/api/orders/export.csv', params);
    },

    submitDownload: function (url, params) {
        var form = Ext.getBody().createChild({
            tag: 'form', method: 'POST', action: url, cls: 'x-hidden',
            target: '_self'
        });
        Ext.Object.each(params, function (name, value) {
            form.createChild({ tag: 'input', type: 'hidden', name: name, value: value });
        });
        // include CSRF token (Q18)
        form.createChild({ tag: 'input', type: 'hidden',
            name: '_csrf', value: MyApp.session.csrfToken });
        form.dom.submit();
        Ext.defer(function () { form.destroy(); }, 1000);   // cleanup the transient form
    }
});
```

**Why server-side streaming, not client-side:** with 2M filtered rows the client never holds the full result set (it's remote-paged, Q39), so a client-built CSV is structurally impossible — only the server has the full data. The server applies the *same* sort/filter the grid used and streams the CSV (chunked transfer / row-by-row) so it never materializes 2M rows in server memory either. **Why a form POST over XHR:** an XHR-to-blob approach buffers the entire file in browser memory before triggering a download — fine for small exports, but a multi-hundred-MB CSV would OOM the tab; a native form POST lets the browser stream the response straight to disk via the `Content-Disposition: attachment` header. **The correctness crux:** the export filters must be encoded *identically* to how the proxy encodes them for `load()` (Q33), or the exported rows won't match what the user sees — reusing the store's sorters/filters guarantees parity. **Edge cases:** CSRF token must ride along (a state-changing-looking POST); the transient form is cleaned up after submit; for very long-running exports you'd switch to an async job pattern (kick off export → poll/notify → download link) rather than holding the request open, which is the better design above some size threshold.

#### Q108. [Theory] How does ExtJS handle right-to-left (RTL) layouts and bidirectional text internally, and what breaks when you bolt RTL onto an app not built for it?

RTL support in ExtJS is deeper than a CSS `direction: rtl` toggle because the framework's **layout engine computes positions in JavaScript** (Q15/Q73), so "left" and "right" are baked into measurement and box math, not just styling. ExtJS implements RTL at two coordinated levels. At the **CSS level**, the SASS build can produce a mirrored stylesheet (the `rtl` mixin generates flipped padding/margin/border/float rules), and components get an `x-rtl` body class. At the **JavaScript/layout level**, components and layouts have RTL-aware code paths: `Ext.rtl.*` overrides flip the meaning of box-model calculations, scroll-offset signs (RTL scrolling has inverted/odd `scrollLeft` semantics across browsers, which ExtJS normalizes), anchor/region resolution (a `border` layout's `west` becomes visually right), and column ordering. You enable it with `rtl: true` on the container/viewport (or app-wide), and the framework flips both the styling and the geometry.

What **breaks** when retrofitting RTL onto an app built LTR-only is almost always the **custom** layer the framework can't auto-mirror: (1) **hard-coded pixel offsets and `setStyle('left', ...)`** in custom components — these don't flip and end up mirrored-wrong; you must use logical positioning or branch on `this.getInherited().rtl`. (2) **Custom `XTemplate`/`html` markup** with inline `float:left` or directional CSS bypasses the RTL stylesheet generation. (3) **Custom layouts** (Q98) that compute x-coordinates assume LTR origin; they need explicit RTL handling. (4) **Icons and directional affordances** (arrows, chevrons, "next") aren't auto-mirrored — a "collapse to the left" tool points the wrong way. (5) **Mixed bidi content** (an Arabic label with an embedded LTR account number) needs Unicode bidi controls or it renders in confusing order. (6) **Charts/draw surfaces** (Q72) generally don't auto-mirror and need manual handling.

The practical guidance: RTL is far cheaper if designed in from the start (build the RTL theme, test in RTL early, never hard-code directional pixels). Retrofitting is a real project — you audit every custom renderer, layout, and inline style, switch directional CSS to logical properties or RTL-aware branches, mirror icons, and test with actual RTL locale data (not LTR text in an RTL frame, which hides bidi bugs). The interview insight is *why* it's hard in ExtJS specifically: because the JS layout engine owns geometry, RTL isn't a pure-CSS concern the way it can be in a DOM-native app — the framework has to mirror *math*, and your custom code that also does math is exactly what won't get mirrored for free.

#### Q109. [Coding] Implement a lazy-loading, expandable tree that fetches children on node expand and shows a per-node loading state.

**Problem:** A file-system-style tree with potentially millions of nodes; load only the root initially and fetch each node's children from the server the first time it's expanded. Handle load errors per node without breaking the rest of the tree. Use `Ext.data.TreeStore`.

A `TreeStore` with a proxy and nodes marked `leaf: false` lazily loads children: when a non-leaf node is expanded and hasn't loaded, the store requests its children using the node id as a parameter. We wire per-node error handling so a single failed branch doesn't poison the tree.

```javascript
var treeStore = Ext.create('Ext.data.TreeStore', {
    proxy: {
        type: 'ajax',
        url: '/api/fs/children',
        reader: { type: 'json', rootProperty: 'children' }
    },
    root: { id: 'root', text: 'Root', expanded: true },  // only root loaded up front
    // the proxy sends ?node=<expandedNodeId>; server returns that node's children
    nodeParam: 'node'
});

Ext.create('Ext.tree.Panel', {
    renderTo: Ext.getBody(), height: 500, width: 400,
    store: treeStore,
    rootVisible: false,
    listeners: {
        // per-node error handling without breaking the whole tree
        load: function (store, node, records, success) {
            if (!success && node) {
                node.set('text', node.get('text') + '  (load failed)');
                node.set('iconCls', 'x-fa fa-exclamation-triangle');
                node.collapse();
                node.set('loaded', false);   // allow retry on next expand
                Ext.toast('Failed to load ' + Ext.String.htmlEncode(node.get('text')));
            }
        },
        beforeitemexpand: function (node) {
            // optional: show a spinner row while children load
            if (!node.isLoaded()) { node.set('loading', true); }
        }
    }
});
```

Server response shape for a node expand:

```json
{
  "children": [
    { "id": "src",  "text": "src",        "leaf": false },
    { "id": "pkg",  "text": "package.json", "leaf": true },
    { "id": "rdme", "text": "README.md",  "leaf": true }
  ]
}
```

**Why lazy tree loading:** eagerly loading a million-node tree would blow memory and the DOM (the tree view is virtualized but the *store* would still hold every node). Marking nodes `leaf: false` tells the tree they're expandable; the `TreeStore` only requests a node's children the first time it expands (then caches via the node's `loaded` flag, so re-expanding doesn't refetch). **Per-node error isolation** is the expert detail: a naive implementation lets one failed branch throw and leave the tree in a half-state; instead we catch it in the `load` event scoped to the failing `node`, mark it visually, and reset `loaded` so the user can retry by expanding again — the rest of the tree is unaffected. **Edge cases:** a node that returns zero children should be shown as empty/leaf, not perpetually "loading"; deep auto-expand (deep-linking to a buried path) requires expanding ancestors in sequence, each triggering its own fetch; and very wide nodes (10k children under one folder) still need pagination or virtualization of *that* node's children. **Complexity:** each expand is one request returning O(direct children), so total network is proportional to nodes the user actually opens — the whole point of lazy loading.

#### Q110. [Practical] Your ExtJS app's automated test suite is flaky — tests pass locally but fail ~15% of the time in CI. How do you diagnose and stabilize it?

Flaky ExtJS tests almost always trace to the framework's **asynchronous and DOM-measurement nature** (Q46), and the diagnosis is to categorize *why* each flaky test fails rather than blindly adding sleeps. First, **make flakiness observable**: configure the runner to retry-and-report (not retry-and-hide) so you get a list of which tests fail intermittently, capture screenshots/DOM snapshots on failure, and run the suite in the CI environment repeatedly (e.g., 50x in a loop) to get reproducible failure rates per test — local-vs-CI divergence is itself a clue (CI is slower, more contended, often headless with different timing).

The common ExtJS-specific root causes, in order of frequency: (1) **Asserting before the bind scheduler/layout flushed** (Q30/Q46) — the test sets a value and immediately asserts on a bound widget, but binding is async by a tick; the fix is to wait on the *condition* (`waitsFor`/`waitForRender`, polling until the expectation holds) rather than a fixed `setTimeout`, which is the classic flaky anti-pattern that passes on a fast machine and fails on a slow CI box. (2) **Component leak/cross-contamination** — a prior test didn't `destroy()` its components, so a `ComponentQuery` matches a stale instance from an earlier test; the fix is a strict `afterEach` that destroys created components and asserts `Ext.ComponentManager.getCount()` returned to baseline (Q14). (3) **Shared global state** — a singleton store/EventBus (Q93) mutated by one test leaks into another; reset or rebuild it per test. (4) **Animation timing** — components mid-animation (window slide, panel collapse) aren't at final geometry when measured; disable animations in the test profile (`Ext.enableFx = false`). (5) **Virtualized rows not in the DOM** (Q39) — asserting on a grid row that BufferedRenderer hasn't materialized; scroll it into view first.

```javascript
// FLAKY: fixed delay races the bind scheduler and CI slowness
nameField.setValue('Ada');
setTimeout(function () { expect(saveBtn.isDisabled()).toBe(false); done(); }, 50);

// STABLE: wait for the CONDITION, with a generous timeout
nameField.setValue('Ada');
waitForCondition(function () { return !saveBtn.isDisabled(); }, 'save to enable', 2000)
    .then(done);

// STABLE teardown — prevents cross-test contamination
afterEach(function () {
    Ext.Array.each(createdViews, function (v) { v.destroy(); });
    createdViews.length = 0;
    expect(Ext.ComponentManager.getCount()).toBe(baselineCount);
});
```

The durable fixes are policy, not one-off patches: ban fixed-delay waits in favor of condition polling (a lint rule or code-review gate), enforce component teardown in `afterEach`, disable animations and set a deterministic clock where possible, and isolate global singletons per test. The framing for the team: flaky tests are worse than no tests because they erode trust and get ignored, so I treat a flaky test as a P2 bug with a root-cause requirement — "add a retry" is a last resort, not a fix. The ExtJS-specific lesson is that nearly all the flakiness comes from racing the framework's async bind/layout/animation cycles, so the systemic cure is *condition-based waiting* everywhere async rendering is involved.

#### Q111. [Theory] How would you architect offline support / PWA capability for an ExtJS app, including caching the bundle, queuing mutations, and conflict resolution on reconnect?

ExtJS predates the PWA era and has no built-in offline story, so offline is something you architect *around* the framework, in three layers. **Layer 1 — shell/asset caching:** register a **Service Worker** that caches the compiled ExtJS bundle, theme CSS, and the microloader manifest (Q59) so the app boots offline. Because ExtJS assets are content-hashed in a production build (Q86/Q88), a cache-first strategy with hashed URLs is safe (a new deploy produces new hashes → new cache entries), but you must handle the manifest/`bootstrap.json` carefully (network-first or versioned) so a stale manifest doesn't point at evicted chunks — the white-screen failure mode of Q86. **Layer 2 — data caching:** Stores need a persistence layer; use the `localstorage`/`memory` proxy or, better for volume, an IndexedDB-backed proxy so loaded records survive offline. The pattern is a read-through cache: online loads hydrate IndexedDB and the in-memory Store; offline loads read from IndexedDB.

**Layer 3 — the hard part: write queuing and conflict resolution.** Offline mutations (`record.set` + `save`) can't reach the server, so you queue them. Wrap the proxy (or use a custom one, Q97) so that when offline, `create/update/destroy` operations are appended to a durable **outbox** in IndexedDB instead of hitting the network, and the record is optimistically updated locally (Q105). On reconnect (a `navigator.onLine`/`online` event), replay the outbox in order. Replay is where **conflict resolution** bites: each queued mutation should carry the record's version/ETag (Q105) so the server can detect that the entity changed while you were offline; the server returns 409 for stale writes, and you apply a resolution policy — last-write-wins (simple, lossy), client-wins, server-wins, or interactive merge. For multi-step dependent mutations (create order → add line items), the outbox must preserve order and remap server-assigned ids (the offline-created order's temp id → real id) before replaying dependents.

```
ONLINE                              OFFLINE                         RECONNECT
─────────────────                   ──────────────────              ─────────────────────
Store.load() ──► server             Store.load() ──► IndexedDB      'online' event fires
   └─ cache to IndexedDB            record.save() ─┐                replay outbox in order:
record.save() ──► server                           ▼                 each op w/ version/ETag
   (optimistic + commit)            append to OUTBOX (IndexedDB)      ├─ 200 → commit, drop
                                    record optimistically updated     ├─ 409 → conflict policy
                                                                       └─ remap temp→real ids
```

The trade-offs to articulate: full offline is a **large** investment (a Service Worker, an IndexedDB proxy, an outbox with ordering and id-remapping, and a conflict policy) and is only worth it for genuinely field/disconnected use cases — for an internal desktop back-office app (the typical ExtJS deployment, Q1) it's usually over-engineering, and "graceful degradation" (detect offline, disable writes, show a banner) is the right, far cheaper answer. The framework-specific wrinkles are the hashed-bundle/manifest caching (so a deploy doesn't strand offline users on dead chunks) and that ExtJS's optimistic record model (Q38) maps *naturally* onto an outbox — the record already tracks dirty/modified state, so the queue is essentially persisting `getChanges()` plus a version token and replaying it. The dominant failure modes are stale manifests breaking the offline boot and naive last-write-wins silently destroying concurrent edits.

#### Q112. [Coding] Implement a reusable mixin that adds auto-save (debounced dirty-flush) behavior to any form or record-bound view.

**Problem:** Build `mixin.AutoSave` that any record-bound view can mix in to get: debounced auto-save when the bound record becomes dirty, suppression during programmatic loads, a "saving…/saved" status, and clean teardown. This combines the mixin mechanism (Q35), debouncing (Q94), and dirty tracking (Q65).

A mixin (copied onto the host's prototype, Q6) is the right tool because *many* unrelated views want this behavior without an inheritance relationship. The mixin hooks the host's lifecycle via `init`/`destroy` interception and listens to the bound record's `change` event.

```javascript
Ext.define('MyApp.mixin.AutoSave', {
    extend: 'Ext.Mixin',

    mixinConfig: {
        // run our setup/teardown around the host's, automatically
        after: { initComponent: 'initAutoSave' },
        before: { doDestroy: 'destroyAutoSave' }
    },

    config: { autoSaveDelay: 1500, autoSaveEnabled: true },

    initAutoSave: function () {
        // debounced flusher, created once per instance
        this.flushSave = Ext.Function.createBuffered(this.doAutoSave, this.getAutoSaveDelay(), this);
    },

    // host calls this when it binds/loads a record
    watchRecord: function (record) {
        if (this._record) { this._record.un('change', this.onRecChange, this); }
        this._record = record;
        if (record) { record.on('change', this.onRecChange, this); }
    },

    onRecChange: function (record, operation) {
        // suppress auto-save during programmatic loads (commit/reject set a flag)
        if (this._suspendSave || !this.getAutoSaveEnabled()) { return; }
        if (record.dirty) {
            this.setSaveStatus('pending');
            this.flushSave();              // debounced — fires after the user pauses
        }
    },

    doAutoSave: function () {
        var me = this, rec = me._record;
        if (!rec || !rec.dirty) { return; }
        me.setSaveStatus('saving');
        rec.save({
            success: function (r) { r.commit(); me.setSaveStatus('saved'); },
            failure: function ()  { me.setSaveStatus('error'); },   // keep dirty for retry
            scope: me
        });
    },

    setSaveStatus: function (state) {
        if (this.lookup && this.lookup('saveStatus')) {
            this.lookup('saveStatus').setHtml(state);
        }
    },

    destroyAutoSave: function () {
        if (this._record) { this._record.un('change', this.onRecChange, this); }
        this._record = null;
        // createBuffered's pending timer is GC'd with the instance; cancel if exposed
    }
});

// Usage — any view opts in:
Ext.define('MyApp.view.NoteEditor', {
    extend: 'Ext.form.Panel',
    mixins: ['MyApp.mixin.AutoSave'],
    items: [
        { xtype: 'textareafield', name: 'body', bind: '{note.body}' },
        { xtype: 'component', reference: 'saveStatus' }
    ],
    setNote: function (record) { this.watchRecord(record); }   // wire the record
});
```

**Why a mixin (Q35):** auto-save is a cross-cutting capability many view types want (note editor, settings form, profile) without sharing a base class — exactly the horizontal-composition case. `Ext.Mixin` with `mixinConfig.after/before` is the clean way to splice into the host's `initComponent`/`doDestroy` *automatically*, so the host doesn't have to remember to call our setup/teardown (a common mixin pitfall). **Debounce is essential:** saving on every keystroke would hammer the server; `createBuffered` (Q94) coalesces a typing burst into one save after the user pauses. **The suppress flag** prevents an auto-save firing when *we* programmatically load/commit a record (which also fires `change`) — without it, loading a record would immediately try to save it back. **Teardown** unsubscribes from the record's `change` event (a leak vector if the record outlives the view, Q82). **Edge cases:** a failed auto-save must *keep* the record dirty so the next change retries (don't commit on failure); rapid record switches must `un` the old record before watching the new one; and you'd typically debounce-flush on view `destroy`/blur too, so a pending edit isn't lost when the user navigates away mid-debounce.

#### Q113. [Theory] An auditor flags that your ExtJS app logs sensitive data and leaks PII into client-side stores and browser memory. How do you design a data-handling/privacy strategy?

This is a governance problem with ExtJS-specific surfaces, and the strategy spans *what data reaches the client*, *how long it lives*, and *what gets logged*. The foundational principle is **server-side minimization**: a Store holds whatever the API returns, in plain browser memory, fully inspectable via DevTools — so the first control is that endpoints must return only the fields the screen needs (don't ship SSNs, full card numbers, or internal flags to a grid that displays a name). Client filtering is **never** a security control (Q18): if the server sends 10k records and the UI filters to the user's 50, all 10k are in memory and on the wire — authorization must be server-side. Reader-level field selection (Q48) and DTO-shaped endpoints (or GraphQL field selection, Q97) keep PII off the client entirely where possible.

For PII that *must* reach the client, the controls are: (1) **lifetime** — destroy stores/records when the view closes (the same teardown discipline as Q14/Q82, but now a *privacy* requirement, not just a leak one), avoid long-lived application-scoped stores caching PII, and clear sensitive fields on logout (don't rely on the SPA "logout" just hiding views while the data sits in a singleton store). (2) **Logging hygiene** — ExtJS apps commonly log records/operations to the console or a telemetry service; a record's `getData()` dumped into a log or error report (Q86 white-screen triage often attaches state!) is a classic PII leak. Wrap logging so it **redacts** known-sensitive fields, never log full records, and scrub error-reporting payloads (operations, request params) before they leave the browser. (3) **Display masking** — render masked values (`****1234`) via renderers, keeping the unmasked value off the DOM where feasible. (4) **Transport** — HTTPS only, sensitive params in POST bodies not URLs (URLs land in server/proxy logs and browser history), and no PII in `localStorage`/`sessionStorage` (the `localstorage` proxy or `stateful` column-state persistence, Q76, can inadvertently persist data fields).

```javascript
// Redacting logger override — sensitive fields never hit logs/telemetry (Q37 override)
Ext.define('MyApp.override.SafeLog', {
    override: 'Ext.data.Model',
    SENSITIVE: { ssn: 1, cardNumber: 1, dob: 1 },
    getLogData: function () {                       // use this, never raw getData(), in logs
        var data = Ext.clone(this.getData()), me = this;
        Ext.Object.each(data, function (k) { if (me.SENSITIVE[k]) { data[k] = '***'; } });
        return data;
    }
});
// And a masking renderer for display:
{ text: 'Card', dataIndex: 'cardNumber', renderer: function (v) {
    return v ? '**** **** **** ' + Ext.String.htmlEncode(String(v).slice(-4)) : '';
}}
```

The cross-cutting framing: privacy in an SPA is mostly about **what you never send and what you don't retain**, because anything in a Store is, by definition, exfiltratable from the browser. The ExtJS-specific traps are long-lived singleton stores hoarding PII (the same anti-pattern that causes leaks, Q58/Q82), the `stateful`/`localstorage` persistence layers silently writing data to disk, and verbose record/operation logging that ships PII to telemetry. The remediation plan I'd present to the auditor: minimize at the API, enforce server-side authz, add a redacting log/telemetry layer (a small override so it's centralized, Q37), mask on display, destroy stores on view close and clear on logout, and add a test/lint gate that fails if raw `getData()`/records are passed to logging. This is governance enforced through a few centralized framework hooks rather than per-screen vigilance, which is the only way it holds on a 400-screen app.

#### Q114. [Coding] Implement a generic "are you sure?" unsaved-changes guard that blocks navigation/tab-close when any open view has a dirty record.

**Problem:** Across an app with tabbed screens and ExtJS routing (Q50/Q78), prevent the user from closing a tab, navigating via a route, or closing the browser when an open editor has unsaved (dirty) changes — with a confirm prompt. This is a cross-cutting concern that must work uniformly.

The design centralizes dirty-detection: a small registry tracks views that declare themselves "guardable," each exposing an `isDirty()` method; the guard intercepts tab close (`beforeclose`), route changes (`before` route action), and the native `beforeunload`.

```javascript
Ext.define('MyApp.NavGuard', {
    singleton: true,
    guarded: [],

    register: function (view) {
        this.guarded.push(view);
        view.on('destroy', function () { Ext.Array.remove(this.guarded, view); }, this);
    },

    anyDirty: function () {
        return Ext.Array.some(this.guarded, function (v) {
            return v.isDirty && v.isDirty();
        });
    },

    confirm: function (onProceed, onCancel) {
        if (!this.anyDirty()) { onProceed(); return; }
        Ext.Msg.confirm('Unsaved changes',
            'You have unsaved changes. Leave anyway?',
            function (btn) { btn === 'yes' ? onProceed() : (onCancel && onCancel()); });
    }
});

// 1) Native tab/browser close (synchronous — can only show the generic browser prompt)
window.addEventListener('beforeunload', function (e) {
    if (MyApp.NavGuard.anyDirty()) { e.preventDefault(); e.returnValue = ''; }
});

// 2) ExtJS tab close — veto via beforeclose, then prompt async
Ext.define('MyApp.view.EditorTab', {
    extend: 'Ext.panel.Panel',
    closable: true,
    viewModel: { data: { record: null } },
    initComponent: function () {
        this.callParent(arguments);
        MyApp.NavGuard.register(this);
    },
    isDirty: function () {
        var r = this.getViewModel().get('record');
        return !!(r && r.dirty);
    },
    listeners: {
        beforeclose: function (panel) {
            if (!panel.isDirty()) { return true; }      // allow close
            MyApp.NavGuard.confirm(function () {
                panel._forceClose = true; panel.close(); // proceed after confirm
            });
            return panel._forceClose === true;          // veto until confirmed
        }
    }
});

// 3) Route change guard (Q50) — block in-app navigation while dirty
Ext.define('MyApp.controller.Router', {
    extend: 'Ext.app.Controller',
    routes: { ':view': { action: 'onRoute', before: 'beforeRoute' } },
    beforeRoute: function (view, action) {
        MyApp.NavGuard.confirm(
            function () { action.resume(); },   // proceed with route
            function () { action.stop(); }      // cancel route, stay put
        );
    },
    onRoute: function (view) { /* activate the view */ }
});
```

**Why centralize via a registry:** dirty-guarding scattered across each view leads to inconsistent UX and missed cases; a single `NavGuard` singleton (Q93-style) gives one source of truth and one prompt implementation, and views just opt in by `register()`-ing and implementing `isDirty()`. **Three distinct interception points** because the framework can't unify them: ExtJS `beforeclose` (returnable veto, can prompt async then re-close with a force flag), the **route `before` guard** (Q50/Q78 — `action.resume()`/`action.stop()` is ExtJS routing's async gate, the right hook for in-app navigation), and the **native `beforeunload`** which is synchronous and browser-controlled — you *cannot* show a custom ExtJS dialog there, only trigger the browser's generic "leave site?" prompt by setting `returnValue`. **The async-veto dance** (`_forceClose` flag) is necessary because `beforeclose` returns synchronously but our confirm is async: first call vetoes and shows the prompt, the "yes" branch sets the flag and re-calls `close()`, which now passes. **Edge cases:** a record that's dirty but the user explicitly saved elsewhere; multiple dirty tabs (the prompt is "any dirty," you might enumerate which); and `beforeunload` deliberately can't be customized (anti-abuse browser policy), which is the honest limitation to state — you get a generic prompt on hard navigation, a rich one only on in-app navigation/tab-close.

#### Q115. [Theory] Compare ExtJS's `Ext.Viewport`/responsive config and the Modern toolkit's responsive features to CSS media queries and modern responsive design. How do you build a responsive ExtJS app?

ExtJS's responsiveness is **JavaScript-driven** rather than purely CSS-driven, which is both its power and its friction point versus modern CSS (flexbox/grid/container queries). Because the layout engine computes geometry in JS (Q15), ExtJS offers the **`responsive` plugin/mixin** (`Ext.plugin.Responsive`) where components declare config that changes based on named "responsive states" — orientation (`landscape`/`portrait`) and custom breakpoints evaluated against the viewport. Instead of CSS media queries toggling styles, you toggle *component configs* (hide a panel, switch a `border` region to a `card` layout, change column visibility) reactively when the viewport crosses a breakpoint. The Modern toolkit (Q13) is touch-first and leans harder on this, plus uses `Ext.Viewport` as a full-screen singleton managing the device viewport, safe-area insets, and orientation.

```javascript
// Component reconfigures itself across breakpoints — JS configs, not CSS
{
    xtype: 'panel', plugins: 'responsive',
    responsiveConfig: {
        'width < 768': {                 // phone/tablet
            region: 'north', height: 50, collapsed: true
        },
        'width >= 768': {                // desktop
            region: 'west', width: 250, collapsed: false
        }
    }
}

// App-wide breakpoint formulas
Ext.define('MyApp.view.Main', {
    extend: 'Ext.container.Viewport',
    layout: 'border',
    // child items use responsiveConfig to restructure the shell per size
});
```

The comparison: **CSS media/container queries** are declarative, run on the browser's optimized style engine, and reflow without JS — cheap and smooth. ExtJS's `responsiveConfig` runs **JS evaluation + a layout pass** on each breakpoint crossing, which is heavier and can jank if you reconfigure large subtrees, but it can do things CSS can't easily: *structurally* change the component tree (swap layouts, move a panel from a docked toolbar to a slide-out menu, change a grid into a list on mobile), because it manipulates components, not just styles. Modern CSS has closed much of the gap (container queries now allow component-level responsiveness CSS-side), so for *visual* responsiveness you should still prefer the SASS/CSS layer (it's cheaper), reserving `responsiveConfig` for *behavioral/structural* changes that genuinely need to alter component configuration.

The practical recipe for a responsive ExtJS app: (1) use `responsiveConfig` for **structural** shell changes (region→drawer, multi-pane→single-pane card stack) keyed off a small set of breakpoints; (2) push purely-visual adaptation into the SASS theme (Q11) and CSS where the framework allows; (3) on the Modern toolkit, lean on its touch-optimized components and `Ext.Viewport` rather than retrofitting Classic desktop grids onto phones; (4) consider the **universal app** structure (Q13) if desktop and mobile diverge enough that a shared responsive view becomes a tangle — at some point two toolkit-specific views are cleaner than one heavily-`responsiveConfig`'d view. The interview insight: ExtJS responsiveness trades CSS's cheap declarative reflow for JS's ability to restructure the component tree — use the right layer for the job, and don't reconfigure huge subtrees on every resize (debounce/throttle the handler, Q94) or you reintroduce the layout-thrash problem (Q15).

#### Q116. [Coding] Build a custom `VTypes` validator and a cross-field validator (confirm-password) that integrate with the form's validity.

**Problem:** Add a reusable `strongpassword` vtype (8+ chars, mixed case, a digit) and a confirm-password field that's invalid unless it matches the password field — both reflected in `form.isValid()` and a bound submit button. Cross-field validation is a recurring real requirement that the basic per-field validators (Q67) don't cover.

`Ext.form.field.VTypes` is ExtJS's pluggable validator registry: you add a named vtype with a test function and a default error text, then any field references it via `vtype:`. Cross-field validation needs a custom `validator` function that reaches the *other* field, plus re-validating the confirm field when the source field changes.

```javascript
// 1) Reusable vtype — registered once, usable on any field via vtype:'strongpassword'
Ext.apply(Ext.form.field.VTypes, {
    strongpassword: function (val) {
        return /[a-z]/.test(val) && /[A-Z]/.test(val) &&
               /\d/.test(val)    && val.length >= 8;
    },
    strongpasswordText:
        'Min 8 chars with upper, lower, and a number.'
});

Ext.define('MyApp.view.PasswordForm', {
    extend: 'Ext.form.Panel',
    bodyPadding: 12, width: 360, defaults: { anchor: '100%', allowBlank: false },
    referenceHolder: true,
    items: [
        { xtype: 'textfield', name: 'pwd', fieldLabel: 'Password',
          inputType: 'password', vtype: 'strongpassword', reference: 'pwd',
          // re-validate the confirm field whenever the source changes
          listeners: { change: function (f) {
              var c = f.up('form').lookup('confirm');
              if (c.getValue()) { c.validate(); }
          }}
        },
        { xtype: 'textfield', name: 'confirm', fieldLabel: 'Confirm',
          inputType: 'password', reference: 'confirm',
          // cross-field validator: compares against the other field
          validator: function (value) {
              var pwd = this.up('form').lookup('pwd').getValue();
              return value === pwd ? true : 'Passwords do not match';
          }
        }
    ],
    buttons: [{
        text: 'Set password',
        formBind: true,            // auto-disabled until the WHOLE form isValid()
        handler: function (btn) { btn.up('form').submit(); }
    }]
});
```

**Why a vtype for `strongpassword`:** it's a reusable, named rule registered once and applied declaratively (`vtype: 'strongpassword'`) across any form — cleaner than copy-pasting a regex into each field's `validator`. **Why a custom `validator` for confirm:** vtypes test a *single* value in isolation and can't see another field, so cross-field rules need a `validator` function that reaches the sibling via `this.up('form').lookup(...)`. **The re-validation listener is the non-obvious correctness fix:** if the user fills confirm first then edits the password, the confirm field's "match" verdict is now stale — ExtJS only re-validates a field when *it* changes, so we explicitly call `c.validate()` on the confirm field when the *source* password changes, otherwise the form can report valid while the fields visibly mismatch. **`formBind: true`** ties the button to `form.isValid()` so it enables only when every field (both the vtype and the cross-field rule) passes — the binding-equivalent (`bind:{disabled:'{!form.valid}'}`) also works in MVVM. **Edge cases:** empty confirm shouldn't show "mismatch" until the user types (the `allowBlank` and the `if (c.getValue())` guard handle that); and never rely on client validation for the *security* of password rules — the server must re-enforce them (Q18/Q67).

#### Q117. [Practical] How do you profile and eliminate a "slow initial render" where an ExtJS app shows a blank screen for 4 seconds before the first paint?

A 4-second blank precedes first paint, so the bottleneck is in **boot**, and the discipline (Q88) is to measure *which* boot phase before optimizing — blank-screen time decomposes into network (download the bundle), parse/eval (the JS engine compiling a large bundle), and the first layout pass (constructing and rendering the initial component tree). Open DevTools Performance, record a cold load, and read the flamegraph: a long solid "Evaluate Script" block before any paint means parse/eval dominates (a huge bundle); a long gap with network activity means download dominates; a burst of ExtJS layout/`initComponent`/`render` calls before paint means the *initial view tree is too heavy* to build synchronously. Also check the Network panel for the microloader sequence (Q59) — a serialized chain of dependency requests in dev-style loading would explain it (but a production build should be a single bundle, so seeing many sequential `.js` requests in prod is itself the bug).

The fixes map to the phase you found. **If parse/eval dominates:** shrink the bundle (Q88) — accurate `requires`/`uses` for tree-shaking, single toolkit/pruned theme, and **code-split** the initial route from heavy modules behind `uses` so the first paint doesn't wait on the reporting/charts code. **If download dominates:** brotli + immutable caching + CDN, and split the bundle so the critical-path payload is small. **If initial-tree construction dominates** (the most ExtJS-specific cause): the app is building a giant component tree before first paint — defer it. Render a **lightweight shell first** (just the viewport + a loading mask), then construct heavy views (grids, dashboards) *after* the first paint using `Ext.defer`/`requestAnimationFrame` or lazy tab body creation (`deferredRender: true`, the default for tab panels, ensures only the active tab's body builds). Stores set to `autoLoad` that block on data before render also stall first paint — render the grid empty with a load mask and let data arrive async, rather than gating the UI on the response.

```javascript
// Paint a minimal shell immediately, then build the heavy view next frame
Ext.application({
    name: 'MyApp',
    launch: function () {
        var vp = Ext.create('Ext.container.Viewport', {
            layout: 'fit',
            items: [{ xtype: 'component', html: '<div class="boot-spinner"></div>' }]
        });
        // let the browser paint the spinner, THEN construct the real (heavy) UI
        Ext.defer(function () {
            vp.removeAll();
            vp.add({ xtype: 'app-main' });   // grids/dashboards build now, post-paint
        }, 1);
    }
});

// Tabs: don't build inactive tab bodies up front (deferredRender defaults true)
{ xtype: 'tabpanel', deferredRender: true, items: [ /* only active tab renders */ ] }
```

The broader levers: ensure you're testing the **production** build (a dev build with the dynamic loader is *expected* to be slow and isn't representative), add a static HTML/CSS **splash** that paints from the index page *before* ExtJS even evaluates (so the user sees something at ~0ms instead of waiting for the framework), and consider whether `autoLoad` stores and synchronous heavy-view construction are blocking the path. The framing: "blank for 4 seconds" is almost always *either* a fat bundle's parse/eval *or* a too-heavy initial tree built synchronously — measure to tell which, then either shrink the bundle or defer non-critical construction past first paint. A static splash + lightweight shell + deferred heavy-view construction frequently turns a 4-second blank into a sub-second first paint with progressive fill-in, which *feels* dramatically faster even if total load is similar — perceived performance is the goal, and a blank screen is the worst possible perception.

#### Q118. [Theory] In 2026, a greenfield internal data-heavy admin tool needs to be built. Make the case for *and* against choosing ExtJS over React/Angular/Vue, and state your recommendation.

A staff-level answer resists dogma and frames this as fit-for-purpose, because the *data-heavy internal admin* niche is precisely where ExtJS's value proposition is least eroded (Q1) — yet 2026 ecosystem realities push hard the other way. **The case FOR ExtJS:** it ships a genuinely complete, batteries-included stack — dense editable grids with built-in remote sort/filter/page/group/summary, buffered rendering for millions of rows, a real client-side ORM with an identity-map Session (Q38/Q44), a layout engine for complex docked/bordered desktop UIs, and hundreds of pre-built widgets — so a small team can deliver a complex back-office tool *fast* without assembling and integrating a dozen libraries (a grid lib + a form lib + a data-fetching lib + a state lib + a layout solution). For the canonical "trading console / claims-processing / telecom-provisioning" screen with twenty dense grids, ExtJS's grid alone can outpace stitching together a React grid library plus TanStack Query plus a form library. If the team *already* has ExtJS expertise and existing apps, consistency is a real multiplier.

**The case AGAINST (and it's strong in 2026):** the ecosystem, hiring, and momentum overwhelmingly favor React/Angular/Vue. ExtJS developers are scarce and expensive (Q19); the commercial license is a recurring cost; the bundle is heavy (Q88) with a higher floor than any lightweight alternative; the proprietary class system and tooling are a steep, non-transferable learning curve; runtime theming and modern CSS ergonomics lag (Q70); and crucially you'd be **starting new technical debt** — building greenfield on a framework whose trajectory is maintenance-tier, knowing that a future team may face the very migration calculus we discuss in Q19/Q20. The data-fetching/caching ergonomics that React-Query/Apollo treat as first-class (Q44) you'd hand-roll. And the modern alternatives have closed much of the historical gap: mature, performant data-grid libraries (AG Grid, TanStack Table) now match ExtJS's grid for the dense/virtualized/editable use case that was once ExtJS's unique moat.

| Axis | ExtJS | React/Angular/Vue + grid lib |
|------|-------|------------------------------|
| Time-to-first-complex-grid | very fast (built-in) | fast (with AG Grid/TanStack) |
| Talent availability / cost | scarce, expensive | abundant, cheaper |
| Licensing | commercial | open-source (grid may be paid) |
| Bundle/perf floor | high (heavy) | lower, tunable |
| Data fetching/caching | hand-rolled | first-class (React-Query/Apollo) |
| Long-term trajectory | maintenance-tier | active, growing |
| Future-migration risk | you're creating it | minimal |

**My recommendation:** for a *greenfield* build in 2026, choose **React (or Angular for a large enterprise team) plus a mature data-grid library**, not ExtJS — the talent, ecosystem, licensing, and long-term-maintenance economics decisively outweigh ExtJS's "batteries-included" head start, especially since dedicated grid libraries have neutralized ExtJS's former grid advantage. The *narrow* exceptions where I'd still pick ExtJS: a team with deep existing ExtJS expertise and an existing ExtJS app suite (consistency + reuse), a hard requirement the team can satisfy fastest with ExtJS's specific built-ins under a tight deadline, or a regulated environment already standardized and supported on it. The honest staff-level framing: choosing ExtJS greenfield in 2026 means knowingly accepting future migration debt and a shrinking talent pool in exchange for a short-term integration head start — that trade rarely pencils out for a new build, which is exactly why the strangered legacy apps (Q19/Q20) are migrating *away* from it rather than new teams adopting it.

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
