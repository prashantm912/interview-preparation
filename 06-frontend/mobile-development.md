# Mobile Development

[← Back to master index](../README.md)

A tier-based interview guide to modern mobile development, spanning native (iOS with Swift/SwiftUI, Android with Kotlin/Jetpack Compose) and cross-platform stacks (React Native, Flutter). It covers app and activity lifecycles, the UI/main thread and offloading work, state management, navigation, offline storage, push notifications, performance (jank, startup, memory), battery and network constraints, App Store / Play Store deployment, the React Native New Architecture (Fabric/TurboModules/JSI), and Flutter's widget and rendering model. Content is current through 2026, reflecting Compose's stable status, SwiftUI's maturity, the default-on RN New Architecture, and Flutter's Impeller renderer.

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

### Q1. [Theory] What is the difference between native and cross-platform mobile development?

**Native development** means building separately for each platform using its first-party language, SDK, and toolchain:

- **iOS** — Swift (with SwiftUI or UIKit), built in Xcode, distributed via the App Store.
- **Android** — Kotlin (with Jetpack Compose or the View system), built in Android Studio/Gradle, distributed via the Play Store.

You write and maintain two codebases, but you get the best performance, full access to every platform API on day one, and the most "native-feeling" UI.

**Cross-platform development** shares one codebase across platforms:

- **React Native** — JavaScript/TypeScript + React; renders real native widgets.
- **Flutter** — Dart; draws its own UI on a canvas via the Skia/Impeller engine.

```
            Native                         Cross-platform
   ┌──────────┬──────────┐         ┌────────────────────────┐
   │  Swift   │  Kotlin  │         │   one codebase (JS/Dart)│
   │ SwiftUI  │ Compose  │         ├───────────┬────────────┤
   └────┬─────┴────┬─────┘         │    iOS    │  Android   │
       iOS       Android           └───────────┴────────────┘
```

The trade-off is the classic one: cross-platform gives you a smaller team, shared logic, and faster iteration; native gives you peak performance, immediate API access, and zero abstraction overhead. Most teams choose based on app complexity, performance needs, team skills, and how much truly platform-specific behavior they need.

### Q2. [Theory] Walk through the Android Activity lifecycle.

An `Activity` is a single screen. The OS drives it through lifecycle callbacks so you can acquire and release resources at the right moments:

```
 onCreate() ─▶ onStart() ─▶ onResume() ─▶ [RUNNING]
                  ▲             │
                  │             ▼
              onRestart() ◀─ onPause() ─▶ onStop() ─▶ onDestroy()
```

- **`onCreate`** — one-time setup (inflate/compose UI, bind ViewModel). Receives saved state.
- **`onStart`** — activity becomes visible.
- **`onResume`** — activity is in the foreground and interactive.
- **`onPause`** — losing focus (e.g. a dialog appears); should be fast, persist nothing heavy.
- **`onStop`** — no longer visible; release things you don't need while hidden.
- **`onDestroy`** — being finished or recreated (e.g. config change).

The key rule: the system can destroy a backgrounded activity to reclaim memory, so persist UI state via `onSaveInstanceState`/`SavedStateHandle`. With Jetpack Compose you still live inside an Activity, but most state lives in a `ViewModel` that survives configuration changes.

### Q3. [Theory] Describe the iOS app and view-controller lifecycle.

iOS has two layers. The **app lifecycle** (managed by the scene/`UIApplicationDelegate`) moves through states:

```
 Not running ─▶ Inactive ─▶ Active ─▶ Background ─▶ Suspended
```

- **Active** — in foreground, receiving events.
- **Inactive** — foreground but not receiving events (e.g. during an incoming call).
- **Background** — running briefly off-screen (finish tasks, save state).
- **Suspended** — in memory but not executing; the OS may terminate it.

The **view-controller lifecycle** (UIKit) is `viewDidLoad → viewWillAppear → viewDidAppear → viewWillDisappear → viewDidDisappear`. In **SwiftUI**, you instead use view modifiers like `.onAppear`/`.onDisappear` and the `@Environment(\.scenePhase)` value to react to `.active`/`.inactive`/`.background` transitions, since SwiftUI views are lightweight value types, not long-lived objects.

### Q4. [Theory] What is the "main thread" / "UI thread" and why must you not block it?

Both platforms render the UI on a single dedicated thread — the **main thread** on iOS, the **UI/main thread** on Android. All view updates, touch handling, and (mostly) layout happen there. The system tries to produce a new frame every refresh interval (16.6 ms at 60 Hz, ~8.3 ms at 120 Hz on ProMotion/high-refresh displays).

If you run slow work (network calls, disk I/O, heavy computation, JSON parsing) on the main thread, you miss the frame deadline. The UI freezes — this is **jank** — and on Android a sufficiently long block triggers an **ANR** ("Application Not Responding") dialog. The rule is simple: **keep the main thread for UI only**; push everything else to background threads/coroutines/queues and hop back to the main thread only to apply the result.

### Q5. [Practical] How do you move work off the main thread on Android and iOS?

On **Android** with Kotlin coroutines you switch dispatchers:

```kotlin
viewModelScope.launch {
    val user = withContext(Dispatchers.IO) {
        repository.fetchUser()   // network/disk off the main thread
    }
    _uiState.value = UiState.Loaded(user)  // back on main automatically
}
```

On **iOS** with Swift concurrency you use `async`/`await` and actors; UI updates run on the `MainActor`:

```swift
func loadUser() async {
    let user = try? await repository.fetchUser()   // off main
    await MainActor.run { self.user = user }        // back on main
}
```

The pattern is identical everywhere: do the heavy lifting on a background executor, then marshal the *result* back to the UI thread. In React Native, JS already runs off the main (native UI) thread, but you still avoid blocking the JS thread with synchronous loops.

### Q6. [Theory] What is Jetpack Compose and how does it differ from the View system?

**Jetpack Compose** is Android's modern **declarative** UI toolkit (stable and the recommended default in 2026). You describe *what* the UI should look like for a given state, and Compose figures out *how* to update it:

```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }
    Button(onClick = { count++ }) {
        Text("Clicked $count times")
    }
}
```

Differences from the classic **View** system:

- **Declarative vs imperative.** No `findViewById`, no manually mutating widgets; you change state and the UI **recomposes**.
- **No XML layouts.** UI is Kotlin code.
- **State-driven.** `remember`/`mutableStateOf` track state; reads of that state are tracked so only affected composables recompose.
- **Composition over inheritance.** You build with small composable functions instead of subclassing `View`.

SwiftUI is the direct iOS analogue — same declarative, state-driven philosophy.

### Q7. [Practical] What is state in SwiftUI, and how do `@State`, `@Binding`, and `@Observable` relate?

SwiftUI rebuilds a view's body whenever its state changes. The property wrappers declare *who owns* the state and *who can mutate it*:

```swift
@Observable        // modern macro (replaces ObservableObject)
class CartModel {
    var items: [Item] = []
}

struct CartView: View {
    @State private var model = CartModel()   // owns the source of truth
    var body: some View {
        QuantityField(count: $model.items.count)  // passes a binding down
    }
}

struct QuantityField: View {
    @Binding var count: Int   // two-way reference to parent-owned state
}
```

- **`@State`** — local, view-owned source of truth for a value (or, with `@Observable`, an owned model).
- **`@Binding`** — a *reference* to state owned elsewhere; lets a child read/write the parent's value (`$value` creates one).
- **`@Observable` / `@Environment`** — shared, observable model objects passed down explicitly or via the environment.

The mental model: a single source of truth, with views deriving from it and bindings threading mutation back up.

### Q8. [Theory] What local storage options exist on mobile, and when do you use each?

A rough ladder from lightweight to heavyweight:

| Need | Android | iOS | Cross-platform |
|------|---------|-----|----------------|
| A few key/value prefs | DataStore (Preferences) | `UserDefaults` | AsyncStorage / MMKV |
| Secrets / tokens | EncryptedSharedPrefs / Keystore | Keychain | Keychain/Keystore bridges |
| Structured/relational data | Room (SQLite) | SwiftData / Core Data | SQLite, WatermelonDB, Drift/Isar |
| Files / blobs | app file storage | app sandbox | filesystem APIs |

The rule of thumb: **small flags and prefs** go in key/value stores; **anything you query, relate, or page through** belongs in a database (Room/SwiftData wrap SQLite); **secrets** always go in the OS-backed Keychain/Keystore, never plain prefs; **large media** lives on the filesystem with only its path stored in the DB.

### Q9. [Theory] What is the difference between AsyncStorage, SQLite, and Keychain/Keystore?

- **AsyncStorage (RN) / key-value prefs** — an asynchronous, unencrypted key/value string store. Good for small, non-sensitive settings (theme, onboarding-seen flag). Not for large data or secrets.
- **SQLite / Room / SwiftData** — a real embedded relational database. Use it for structured data you query, sort, relate, or paginate (a list of cached orders, messages, etc.).
- **Keychain (iOS) / Keystore (Android)** — OS-backed **secure** storage backed by hardware where available. The *only* correct place for auth tokens, passwords, and encryption keys. Data here is encrypted at rest and protected by device credentials/biometrics.

Picking the wrong tier is a classic mistake — e.g. storing a JWT in AsyncStorage/`UserDefaults` (readable plaintext) instead of the Keychain.

### Q10. [Practical] How do push notifications work at a high level on iOS and Android?

Both use a platform push service as the delivery channel; your server never talks to the device directly.

```
 Your server ─▶ APNs (iOS) / FCM (Android) ─▶ device ─▶ your app
```

The flow:

1. **App requests permission** and registers for remote notifications.
2. The OS/push service returns a **device token** (APNs token, or FCM registration token).
3. The app sends that token to **your backend**, which stores it.
4. To notify a user, your backend calls **APNs** (iOS) or **FCM** (Android) with the token + payload.
5. The push service delivers it; the OS shows the notification (or wakes your app for a data/silent push).

In practice most teams use **Firebase Cloud Messaging (FCM)** for both platforms (FCM relays to APNs for iOS), so they have one server-side integration. Tokens can rotate, so you must refresh and re-upload them.

### Q11. [Practical] How do you request notification permission, and why is timing important?

On both platforms notifications are **opt-in** and the user can deny permanently:

```swift
// iOS
let center = UNUserNotificationCenter.current()
let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
```

```kotlin
// Android 13+ requires the runtime POST_NOTIFICATIONS permission
requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_CODE)
```

Since iOS 12+/Android 13+, notification permission is an explicit runtime prompt and a **one-shot** decision in the user's mind. Best practice is to **pre-prompt**: explain the value first ("Get notified when your order ships?") in your own UI, and only trigger the real system prompt once the user is likely to accept. Asking cold on first launch tanks your opt-in rate, and once denied you can only send the user to Settings.

### Q12. [Theory] What is "jank" and what commonly causes it?

**Jank** is visible stutter — the UI dropping frames so motion isn't smooth. It happens when the app fails to produce a frame within the display's budget (~16 ms at 60 Hz). Common causes:

- Heavy work on the **main/UI thread** (parsing, sorting, layout, big image decodes).
- **Overdraw** — painting the same pixels many times with stacked opaque layers.
- **Unbounded list rendering** — building all rows instead of recycling/virtualizing.
- **Excessive recomposition / re-render** — Compose recomposing or React re-rendering far more than necessary.
- **Synchronous I/O** during scroll or animation.

You diagnose it with the platform profilers (Android Studio's profiler / Perfetto, Xcode Instruments) and fix it by offloading work, virtualizing lists, reducing overdraw, and stabilizing state.

### Q13. [Theory] What does it mean to "lift state up" and why do it?

When two sibling components need the same data, you move ("lift") that state to their nearest common parent and pass it down. This gives you a **single source of truth** instead of two copies that can drift out of sync. In React Native:

```jsx
function Parent() {
  const [query, setQuery] = useState("");
  return (
    <>
      <SearchBox value={query} onChange={setQuery} />
      <Results query={query} />   {/* both read the same state */}
    </>
  );
}
```

The same principle applies in Compose (hoist state out of a composable into the caller/ViewModel) and SwiftUI (own the `@State` in a parent, pass `@Binding` down). Lifting state makes components reusable and "dumb," and concentrates mutation in one place — easier to reason about and test.

### Q14. [Practical] How do you handle navigation in a typical mobile app?

Each ecosystem has a canonical navigation library:

```kotlin
// Android — Navigation Compose
NavHost(navController, startDestination = "home") {
    composable("home") { HomeScreen(onOpen = { id -> navController.navigate("detail/$id") }) }
    composable("detail/{id}") { backStack ->
        DetailScreen(id = backStack.arguments?.getString("id"))
    }
}
```

```swift
// iOS — SwiftUI NavigationStack
NavigationStack(path: $path) {
    HomeView()
        .navigationDestination(for: Item.self) { item in DetailView(item: item) }
}
```

- **Android:** Navigation Compose with a `NavController`, typed routes, and a managed back stack.
- **iOS:** `NavigationStack` (push/pop, value-driven) plus sheets/full-screen covers.
- **React Native:** **React Navigation** (stack/tab/drawer navigators) — the de facto standard.
- **Flutter:** the `Navigator` plus a router package like **go_router** for declarative, deep-link-friendly routing.

The shared concepts are a **back stack**, **routes/destinations**, **passing arguments**, and **deep links** that map a URL to a screen.

### Q15. [Theory] What is a `ViewModel` and why is it useful?

A **ViewModel** holds and manages UI-related state and logic, kept separate from the UI itself. On Android, the Jetpack `ViewModel`:

- **Survives configuration changes** (rotation, dark-mode toggle) — it isn't destroyed and recreated with the Activity, so state and in-flight work persist.
- **Outlives recomposition**, giving the UI a stable owner for state.
- **Has a lifecycle-aware scope** (`viewModelScope`) that auto-cancels coroutines when cleared.

It's the "VM" in MVVM: the View observes immutable state exposed by the ViewModel (e.g. a `StateFlow`), and forwards user events to it. SwiftUI achieves the equivalent with an `@Observable`/`ObservableObject` model object owned by the view. The benefit is testable logic decoupled from the framework's view lifecycle.

### Q16. [Practical] How do you display a scrollable list efficiently?

Never render every row. Use the platform's **lazy/virtualized** list, which only builds the items currently on (or near) screen and recycles views as you scroll:

```kotlin
// Compose
LazyColumn {
    items(messages, key = { it.id }) { msg -> MessageRow(msg) }
}
```

```jsx
// React Native
<FlashList data={messages} keyExtractor={m => m.id} renderItem={({item}) => <MessageRow msg={item} />} />
```

- **Compose:** `LazyColumn`/`LazyRow` (provide stable `key`s).
- **SwiftUI:** `List` / `LazyVStack` inside a `ScrollView`.
- **React Native:** `FlatList` or the faster **FlashList** (avoid `.map()` inside a `ScrollView`).
- **Flutter:** `ListView.builder` (builds lazily) rather than `ListView(children: [...])`.

Always provide a **stable key/id** per item so the framework can correctly diff and recycle, and avoid heavy work inside the row builder.

---

## 🟡 Intermediate (3–7 yrs)

### Q17. [Theory] Explain the React Native architecture: the old bridge vs the New Architecture.

In the **old architecture**, JS and native ran as two worlds connected by an asynchronous **bridge** that serialized all messages to JSON and passed them in batches:

```
   JS thread  ◀──── JSON over async bridge ────▶  Native (UI) thread
```

Problems: every call was **async and serialized**, the bridge could become a bottleneck under heavy traffic (animations, big lists), and there was no way to call synchronously.

The **New Architecture** (default since RN 0.76, the standard in 2026) removes the bridge in favor of **JSI (JavaScript Interface)** — a lightweight C++ layer that lets JS hold references to native objects and call them **synchronously** when needed. Built on JSI:

- **TurboModules** — native modules loaded lazily and called via JSI (no JSON marshaling).
- **Fabric** — the new rendering system; a shared C++ core builds the UI tree, enabling concurrent React features and synchronous layout/measurement.
- **Codegen** — generates type-safe native interfaces from your TS specs.

The payoff is lower latency, less serialization overhead, synchronous calls where needed, and better interop with React 18+ concurrency.

### Q18. [Theory] What are TurboModules and Fabric, concretely?

- **TurboModules** are the New Architecture's native modules. Instead of all modules being eagerly initialized and reached over the JSON bridge, a TurboModule is **lazily loaded on first use** and invoked directly through **JSI**, so calls avoid serialization and can be synchronous. You declare a typed spec, and **Codegen** produces the native scaffolding.

- **Fabric** is the new **renderer**. The UI tree (the "shadow tree") is built and laid out in **shared C++**, and the host views are created on the native side from it. Because the tree lives in C++ and is reachable synchronously, Fabric supports **concurrent rendering** (React 18 features like `Suspense`/transitions), consistent measurement, and faster updates than the old async UIManager.

Together they replace the bridge: TurboModules for **native-to-JS function calls**, Fabric for **rendering the UI tree**.

### Q19. [Theory] How does Flutter render UI, and what is the widget tree?

Flutter does **not** use the platform's native widgets. Instead it ships its own **rendering engine** (historically Skia, now **Impeller** by default on iOS and Android in 2026) and draws every pixel itself on a canvas. This is why a Flutter app looks identical across platforms.

Internally there are three trees:

```
 Widget tree  ──▶  Element tree  ──▶  RenderObject tree
 (immutable     (mutable, holds    (layout + paint)
  config)        state, diffs)
```

- **Widgets** are immutable descriptions of part of the UI (cheap to recreate).
- **Elements** are the instantiated, mutable nodes that hold state and are reused across rebuilds (this diffing is what makes rebuilds cheap).
- **RenderObjects** do the actual layout, painting, and hit-testing.

When state changes you call `setState` (or use a state-management lib), Flutter rebuilds the widget tree, diffs it against the element tree, and only re-lays-out/repaints what changed.

### Q20. [Practical] Compare `StatelessWidget` and `StatefulWidget` in Flutter.

- **`StatelessWidget`** — has no mutable state; its appearance depends only on its constructor inputs. Use it for static/derived UI.
- **`StatefulWidget`** — pairs a widget with a `State` object that persists across rebuilds and can call `setState` to trigger a rebuild.

```dart
class Counter extends StatefulWidget {
  const Counter({super.key});
  @override
  State<Counter> createState() => _CounterState();
}

class _CounterState extends State<Counter> {
  int count = 0;
  @override
  Widget build(BuildContext context) {
    return TextButton(
      onPressed: () => setState(() => count++),  // schedules a rebuild
      child: Text('Count: $count'),
    );
  }
}
```

The widget is recreated on every build, but the `State` object is **retained** by the element, which is how local state survives rebuilds. Prefer `StatelessWidget` whenever a widget has no internal mutable state.

### Q21. [Theory] Compare state-management approaches across the ecosystems.

The same problem — share and react to state without prop-drilling or tangled mutation — recurs everywhere:

- **Android/Compose:** `ViewModel` exposing `StateFlow`/`MutableState`; unidirectional data flow (UDF) is the recommended pattern.
- **iOS/SwiftUI:** `@Observable` models, `@State`/`@Binding`, and `@Environment`. The Composable Architecture (TCA) for larger apps.
- **React Native:** Context for low-frequency global state; **Redux Toolkit**, **Zustand**, or **Jotai** for app state; **TanStack Query** for server state/caching.
- **Flutter:** `provider`/`Riverpod`, `Bloc`/`Cubit`, or `setState` for local.

The unifying idea is **unidirectional data flow**: state flows down into the UI, events flow up, and a single source of truth produces the next state. The mistake to avoid is scattering mutable copies of the same data across screens.

### Q22. [Practical] How do you implement unidirectional data flow (MVI-style) on Android?

You expose **immutable state** as a single object and accept **events** as the only way to change it:

```kotlin
data class UiState(val loading: Boolean = false, val items: List<Item> = emptyList())
sealed interface Event { object Refresh : Event }

class FeedViewModel(private val repo: Repo) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onEvent(e: Event) = when (e) {
        is Event.Refresh -> refresh()
    }
    private fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        val items = repo.load()
        _state.update { it.copy(loading = false, items = items) }
    }
}
```

```kotlin
@Composable
fun FeedScreen(vm: FeedViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    // render state; call vm.onEvent(Event.Refresh) on user action
}
```

State is a single immutable snapshot, the UI is a pure function of it, and every change goes through one funnel. This makes the flow predictable, testable, and easy to time-travel/debug.

### Q23. [Theory] What causes unnecessary recomposition in Compose and how do you avoid it?

Compose recomposes a composable when a `State` it **reads** changes. Excessive recomposition usually comes from:

- **Unstable parameters.** If a composable receives a type Compose can't prove stable (e.g. a `List` instead of `ImmutableList`, or a lambda capturing changing state), it can't skip.
- **Reading state too high.** Reading a frequently changing value in a parent recomposes the whole subtree.
- **New lambdas/objects each recomposition** breaking equality checks.

Mitigations:

- Pass **stable/immutable** types (`kotlinx.collections.immutable`, `@Immutable`/`@Stable` annotations).
- **Defer reads** — read state as low in the tree as possible, or via lambdas (`Modifier.offset { ... }`) so only layout/draw re-runs.
- Use `remember` for derived values and `derivedStateOf` for computed state.
- Use stable `key`s in lazy lists.

The compiler's strong-skipping mode (default in recent Compose) helps, but stability is still something you design for.

### Q24. [Practical] How do you avoid unnecessary re-renders in React Native?

React re-renders a component when its state or props change; you minimize wasted renders by stabilizing inputs and memoizing:

```jsx
const Row = React.memo(function Row({ item, onPress }) {
  return <Pressable onPress={() => onPress(item.id)}><Text>{item.title}</Text></Pressable>;
});

function List({ items }) {
  const onPress = useCallback((id) => navigate("Detail", { id }), []);
  const renderItem = useCallback(({ item }) => <Row item={item} onPress={onPress} />, [onPress]);
  return <FlashList data={items} renderItem={renderItem} keyExtractor={i => i.id} />;
}
```

- **`React.memo`** skips re-render when props are shallow-equal.
- **`useCallback`/`useMemo`** keep function/object identities stable so memoized children actually skip.
- Avoid creating inline objects/arrays/functions in `renderItem`.
- Keep state **local** so updates don't re-render unrelated subtrees.

React 19's compiler can auto-memoize, reducing manual `useMemo`/`useCallback`, but understanding identity stability remains essential.

### Q25. [Practical] How do you cache and sync remote data for offline support?

Treat the device as an **offline-first cache** with the network as a refresh source. A common pattern is a single-source-of-truth repository:

```kotlin
fun observeArticles(): Flow<List<Article>> = flow {
    emitAll(dao.observeAll())          // UI reads from DB always
}.also {
    viewModelScope.launch {
        runCatching { dao.upsertAll(api.fetchArticles()) } // refresh in background
    }
}
```

Principles:

- **The local DB (Room/SwiftData/SQLite) is the source of truth** the UI renders; network writes update the DB, and the UI observes the DB.
- **Writes** are stored locally (often marked "pending") and **replayed** when connectivity returns (queue/outbox pattern).
- Use **timestamps/ETags** and conflict resolution (last-write-wins or merge) on sync.
- On RN, **TanStack Query** (with persistence) or **WatermelonDB** handle much of this.

The user always sees *something* instantly, and the network just keeps it fresh.

### Q26. [Theory] How do silent / data push notifications differ from alert notifications, and what are their limits?

- **Alert (user-facing) notifications** carry a visible payload (title/body) and are shown by the OS even if your app is killed.
- **Silent / data pushes** carry no UI; they wake your app in the background to fetch data or update state (`content-available: 1` on APNs, a data-only message on FCM).

Critical limits:

- Both OSes **throttle and may delay or drop** background/silent pushes to save battery, especially when the app is force-quit (iOS) or under Doze/App Standby (Android). They are **best-effort, not guaranteed**.
- iOS limits silent push frequency and won't deliver them to a user-terminated app reliably.
- Background execution time after a wake is short (seconds).

So you use silent pushes to *opportunistically* refresh, never as a reliable transport for critical data — pair them with a foreground sync and pull-to-refresh fallback.

### Q27. [Theory] What is app startup time, and how do you measure and improve it?

Startup is split into:

- **Cold start** — process not running; the OS creates it, initializes the app, and draws the first frame. Slowest.
- **Warm start** — process alive but the activity/screen must be recreated.
- **Hot start** — app already in memory; just bring to foreground. Fastest.

You measure **time-to-initial-display (TTID)** and **time-to-full-display (TTFD)** with Android's `reportFullyDrawn`/Macrobenchmark or Xcode/MetricKit. Improvements:

- **Defer non-critical initialization** out of `Application.onCreate`/`didFinishLaunching` (lazy-init libraries, avoid heavy work and reflection).
- Use **App Startup** (Android) / Baseline Profiles to precompile hot paths.
- Avoid blocking the first frame on network/disk; show a skeleton.
- Trim the **dependency-injection graph** and synchronous work at launch.

Cold start is what app-store rankings and user perception care about most, so it's the priority.

### Q28. [Practical] How do you keep large scrolling lists smooth (deep dive)?

Beyond using a lazy list, smoothness depends on cheap row builds and stable data:

```jsx
// React Native + FlashList
<FlashList
  data={items}
  estimatedItemSize={72}            // lets FlashList pre-size
  keyExtractor={i => i.id}
  renderItem={renderItem}           // memoized, light component
  removeClippedSubviews
/>
```

Checklist:

- **Virtualize** (FlashList / `LazyColumn` / `ListView.builder`).
- **Stable keys** so recycling/diffing works.
- **Light row components** — no heavy computation, format data ahead of time.
- **Downsample and cache images** (Coil/Glide, SDWebImage, `cached_network_image`); never decode full-res images into small cells.
- **Avoid nested scroll/overdraw**; flatten the hierarchy.
- **Paginate** server data rather than loading thousands of rows.

Profile while scrolling (Perfetto, Instruments, Flutter DevTools) and watch for dropped frames and GC pauses.

### Q29. [Practical] How do you store and protect an auth token on mobile?

Never in plain prefs. Use the OS secure store:

```swift
// iOS Keychain
let query: [String: Any] = [
  kSecClass as String: kSecClassGenericPassword,
  kSecAttrAccount as String: "authToken",
  kSecValueData as String: token.data(using: .utf8)!,
  kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
]
SecItemAdd(query as CFDictionary, nil)
```

```kotlin
// Android — EncryptedSharedPreferences / Keystore-backed
val prefs = EncryptedSharedPreferences.create(/* MasterKey from Keystore */)
prefs.edit().putString("authToken", token).apply()
```

Practices:

- **Keychain (iOS)** / **Keystore-backed encrypted storage (Android)** for tokens and keys — hardware-backed where available.
- Choose an **accessibility class** so the token isn't readable when the device is locked if you don't need it then.
- Prefer **short-lived access tokens + refresh tokens**, and **biometric gating** (`LAContext`/`BiometricPrompt`) for sensitive actions.
- Clear secrets on logout; consider certificate pinning for the auth endpoint.

### Q30. [Theory] What constraints do battery and background limits impose, and how do you respect them?

Both OSes aggressively restrict background work to save battery:

- **Android: Doze & App Standby** batch and defer background work when the device is idle; background services are heavily limited; you can't run arbitrary long tasks.
- **iOS** suspends backgrounded apps quickly and only grants brief, scheduled background execution.

To work within this:

- Use **WorkManager** (Android) and **BGTaskScheduler** (iOS) for **deferrable, OS-scheduled** background work — they batch jobs into efficient windows and respect Doze/low-power mode.
- **Coalesce network** requests and use exponential backoff; don't poll on a tight timer.
- Respect **low-power mode** and metered connections.
- Use **push** to trigger work instead of polling.
- Minimize **wakelocks** and location/sensor use; release them promptly.

The principle: let the OS decide *when* your deferrable work runs, and make each wake do as much batched work as possible.

### Q31. [Theory] How does the App Store / Play Store deployment and review process work?

A high-level pipeline:

```
 build & sign ─▶ upload ─▶ (review) ─▶ staged/phased rollout ─▶ store
```

- **iOS / App Store:** archive in Xcode, sign with your distribution certificate + provisioning profile, upload to **App Store Connect** (often via TestFlight first). Apple performs a **human + automated review** (typically a day or two) checking guidelines, privacy, and functionality. You can do **phased releases**.
- **Android / Play Store:** build a signed **Android App Bundle (.aab)**, upload to the **Play Console**, use tracks (internal → closed → open → production) and **staged rollout** (e.g. 5% → 20% → 100%). Review is mostly automated and faster, with policy checks.

Both require store listings, screenshots, privacy disclosures (App Privacy "nutrition label" / Play Data Safety form), and content ratings. Rejections commonly cite privacy, broken functionality, misleading metadata, or guideline violations (e.g. payments).

### Q32. [Practical] What is code signing and provisioning, and why do builds fail on it?

Mobile binaries must be **cryptographically signed** to prove origin and integrity:

- **iOS:** you need a **signing certificate** (identity), a **provisioning profile** (ties an App ID + certificate + allowed devices + entitlements), and matching **entitlements/capabilities**. Mismatches (expired cert, wrong bundle ID, missing capability, device not in the profile) cause the classic "code signing error."
- **Android:** you sign the `.aab`/`.apk` with an **upload key**; Play uses **Play App Signing** to manage the actual app-signing key. Losing your key (without Play App Signing) means you can't update the app.

CI builds often fail here because certificates/profiles aren't on the build machine. Tools like **fastlane match** (iOS) centralize and rotate signing assets so CI and the team share a consistent, version-controlled set.

### Q32b. [Behavioral] Tell me about a time you had to ship a critical mobile fix quickly. (asked at this level too)

Frame it with the unique constraint of mobile: **you can't just redeploy a server — releases go through store review and users update on their own schedule.** A strong answer covers:

- **Assessment:** how you scoped the blast radius (crash-free rate from Crashlytics/Sentry, % of users affected, which OS/versions).
- **Mitigation first:** whether you could fix it **without a new binary** — a server-side feature flag, remote config kill-switch, or a backend change — to stop the bleeding immediately for *all* versions.
- **The release:** if a binary was required, how you expedited review (App Store "expedited review" request / Play priority), used a **staged rollout** to de-risk, and prepared a rollback (halt rollout).
- **Reach:** how you nudged users to update (in-app update prompt / forced-update gate for severe cases).
- **Follow-up:** the postmortem and the guardrail you added (better pre-release testing, a permanent kill-switch).

The interviewer is checking that you understand mobile's slow, irreversible release loop and design for it (flags, staged rollout, kill-switches) rather than treating it like web.

---

## 🟠 Advanced (8–12 yrs)

### Q33. [Theory] When would you choose React Native vs Flutter vs fully native? Walk through the decision.

There's no universal winner; you weigh several axes:

| Factor | Native | React Native | Flutter |
|--------|--------|--------------|---------|
| UI fidelity / platform feel | Best | Native widgets (good) | Custom-drawn (consistent, less "native") |
| Performance ceiling | Highest | High (New Arch) | High (compiled, own renderer) |
| Team skills | iOS+Android specialists | JS/React teams, web reuse | Dart (new to most) |
| Code sharing | None | High | Highest (incl. exact pixels) |
| Day-one API access | Immediate | Needs native modules sometimes | Needs plugins sometimes |
| Heavy graphics/animation | Native APIs | Reanimated/Skia | Excellent (own engine) |

Heuristics:

- **Native** when you need maximum performance/fidelity, deep platform integration (widgets, complex camera, AR), or already have strong iOS/Android teams.
- **React Native** when you have a React/web org, want to share logic/people across web and mobile, and your UI is mostly standard.
- **Flutter** when you want one team, pixel-perfect identical UI, custom design systems, and strong animations — accepting a less platform-idiomatic feel and the Dart learning curve.

The senior move is to make this an explicit trade-off discussion tied to *this* product, team, and timeline — not a religious preference.

### Q34. [Practical] How do you architect a large mobile app for testability and modularity?

Use a layered, modular architecture with unidirectional data flow:

```
 ┌──────────────┐   events   ┌──────────────┐   ┌──────────────┐
 │   UI layer   │ ─────────▶ │  domain /    │ ─▶│  data layer  │
 │ (Compose/    │ ◀───────── │  use cases   │ ◀─│ (repos,      │
 │  SwiftUI)    │   state    │  (pure)      │   │  DB, network)│
 └──────────────┘            └──────────────┘   └──────────────┘
```

- **UI layer** — declarative views observing immutable state; no business logic.
- **Domain layer** — pure use cases / interactors; framework-free, trivially unit-testable.
- **Data layer** — repositories abstracting network + local DB behind interfaces.
- **Dependency injection** (Hilt/Koin, swinject, get_it) to swap implementations for tests.
- **Feature modules** with clear boundaries (faster builds, parallel teams, enforced layering).

Tests split into **unit** (domain/repos), **UI/component** (Compose/SwiftUI/RTL tests), and **end-to-end** (Espresso/XCUITest/Maestro/Detox). Modularization plus DI is what makes a 10-person, multi-year app maintainable.

### Q35. [Theory] How do you find and fix memory issues and leaks on mobile?

Symptoms are rising memory, GC pressure/jank, and OOM kills. Approach:

- **Profile** with the heap tools (Android Studio Memory Profiler / `LeakCanary`, Xcode Instruments **Allocations** & **Leaks**, Flutter DevTools memory view).
- **Common Android leaks:** holding an `Activity`/`Context` in a static field, long-lived listeners/callbacks not unregistered, inner classes capturing the outer view, coroutines/Flows not scoped to lifecycle. `LeakCanary` auto-detects retained activities/fragments.
- **Common iOS leaks:** **retain cycles** between objects and closures — fix with `[weak self]`/`unowned`; delegates that should be `weak`.
- **Images:** large bitmaps dominate memory; downsample to view size and use an image cache with a bounded budget.
- **Cross-platform:** RN holding references in module-level state; Flutter not disposing controllers/streams (`dispose()`).

The discipline: scope objects to a lifecycle, break reference cycles, bound caches, and verify with a profiler rather than guessing.

### Q36. [Coding] Implement a debounced search that cancels stale requests (Kotlin Flow).

A debounced search waits for the user to stop typing, then runs the latest query and discards in-flight requests for superseded queries:

```kotlin
class SearchViewModel(private val repo: SearchRepo) : ViewModel() {
    private val query = MutableStateFlow("")

    val results: StateFlow<List<Item>> = query
        .debounce(300)                       // wait for a 300ms pause
        .filter { it.length >= 2 }
        .distinctUntilChanged()
        .flatMapLatest { q ->                // cancels the previous search
            flow { emit(repo.search(q)) }
                .catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onQueryChange(q: String) { query.value = q }
}
```

- **`debounce`** collapses rapid keystrokes into one search after a pause.
- **`flatMapLatest`** is the key: when a new query arrives, it **cancels** the previous inner flow (the stale network call), guaranteeing only the latest result wins — no out-of-order races.
- **`distinctUntilChanged`** avoids re-searching identical text.

The same pattern in RxSwift/Combine uses `debounce` + `flatMapLatest`/`switchToLatest`; in RN, TanStack Query with a debounced key. Complexity is dominated by the single live request; memory stays bounded since stale work is cancelled.

### Q37. [Coding] Implement an LRU image/memory cache.

A bounded cache that evicts the least-recently-used entry keeps memory predictable. A `LinkedHashMap` in access-order makes this O(1):

```kotlin
class LruCache<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true /* access order */) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > maxSize
    }
    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
}
```

```swift
// iOS: NSCache is already an LRU-ish, memory-pressure-aware cache
let cache = NSCache<NSString, UIImage>()
cache.countLimit = 100
cache.object(forKey: url as NSString)   // get
cache.setObject(image, forKey: url as NSString)   // put
```

- Access-order `LinkedHashMap` moves touched entries to the tail; `removeEldestEntry` evicts the head when over capacity. All ops are **O(1)**.
- For real image caching, prefer the framework caches (`NSCache`, Coil/Glide's memory+disk cache) which are **memory-pressure aware** and size by **byte cost**, not count.
- Size the cache by *bytes* (a fraction of available memory), not item count, since image sizes vary wildly.

### Q38. [Theory] Explain over-the-air (OTA) / CodePush updates and their limits.

OTA updates ship **JS/Dart bundle and asset** changes directly to installed apps without going through store review:

- **React Native:** historically Microsoft **CodePush**; in 2026 commonly **Expo Updates** / EAS Update. The app downloads a new JS bundle and assets and applies it on next launch.
- **Flutter:** Shorebird code push provides similar capability.

Limits and rules:

- You can update **only the interpreted/bundle layer** — **not** native code, native dependencies, or permissions. Anything requiring a native change still needs a store release.
- Both stores **forbid changing the app's purpose or bypassing review** via OTA; abusing it risks removal.
- You must guard against shipping a bundle **incompatible** with the installed native runtime (version-gate updates).

OTA is great for hotfixes and quick iterations on UI/logic, but it's not a way to escape the native release cycle entirely.

### Q39. [Theory] How do you design a robust offline-first sync engine with conflict resolution?

Model the device as a replica that must converge with the server:

```
 local writes ─▶ outbox (pending ops) ─▶ sync ─▶ server
       ▲                                   │
       └──────── pull + merge ◀────────────┘
```

Components:

- **Local source of truth** (DB) the UI always reads.
- **Outbox/queue** of mutations with client-generated **idempotency keys**, replayed with backoff when online.
- **Change tracking** via per-record version/`updatedAt`/vector clocks to detect conflicts.
- **Conflict policy:** last-write-wins (simple), field-level merge, or **CRDTs** for collaborative data that must merge automatically.
- **Tombstones** for deletes so they propagate.

Hard parts: clock skew (prefer server/logical clocks), partial sync and pagination, schema migration of local data, and surfacing/auto-resolving conflicts. The goal is **eventual consistency** with a UI that's always responsive and never blocks on the network.

### Q40. [Practical] How do you reduce app size, and why does it matter?

Smaller apps install/update more, especially on low-end devices and metered networks, and stores cap over-cellular download sizes.

- **Android App Bundle (.aab)** + Play dynamic delivery so each device downloads only its **density/ABI/language** split. Use **R8** to shrink/obfuscate and strip unused code (tree-shaking, resource shrinking).
- **iOS App Thinning** (slicing, on-demand resources, bitcode historically) delivers device-specific variants.
- **Audit assets** — vector drawables/SF Symbols over PNGs, compress images, drop unused resources and fonts.
- **Prune dependencies** — each SDK adds weight; remove unused ones.
- **On-demand / dynamic feature modules** for rarely used features.
- **Flutter:** `--split-debug-info`, tree-shaking icons, deferred components.

Measure with the App Bundle Explorer / Xcode size report and track size in CI to prevent regressions.

### Q41. [Coding] Implement an exponential-backoff retry with jitter for flaky mobile networks.

Mobile networks fail transiently; retrying immediately just hammers the server. Exponential backoff with jitter spreads retries out:

```swift
func withRetry<T>(maxAttempts: Int = 4, _ op: () async throws -> T) async throws -> T {
    var attempt = 0
    while true {
        do { return try await op() }
        catch {
            attempt += 1
            guard attempt < maxAttempts, isRetryable(error) else { throw error }
            let base = pow(2.0, Double(attempt))            // 2,4,8...
            let jitter = Double.random(in: 0...1)           // avoid thundering herd
            let delay = min(base + jitter, 30)              // cap the backoff
            try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
    }
}
```

- **Exponential growth** (2ⁿ) backs off fast so a struggling server recovers.
- **Jitter** randomizes delays so many clients don't retry in lockstep (the thundering-herd problem).
- **Cap** the maximum delay and the attempt count; only retry **idempotent/retryable** errors (timeouts, 5xx, no-connectivity) — never blindly retry a non-idempotent POST without an idempotency key.

Respect connectivity changes: rather than spinning, you can also **wait for the network** (`NWPathMonitor` / `ConnectivityManager`) and trigger the retry on reconnect.

### Q42. [Behavioral] Describe a time you made a significant architecture or platform decision for a mobile project.

The interviewer wants to see structured judgment under real constraints. A strong answer includes:

- **Context & options:** the decision (e.g. RN vs native, or adopting Compose, or introducing modularization) and the realistic alternatives.
- **Criteria:** the factors you weighed — team skills, performance needs, time-to-market, maintenance, hiring, risk — ideally with data (benchmarks, a spike/prototype, crash and performance metrics).
- **Stakeholders:** how you aligned engineering, product, and design, and surfaced trade-offs honestly (e.g. "Flutter gives us velocity but a less native feel; here's why that's acceptable for us").
- **Outcome & reflection:** what shipped, what the measured impact was, and what you'd revisit. Owning a wrong call and what you learned is a plus.

The point is demonstrating that you treat platform/architecture choices as **reversible-or-not trade-offs grounded in evidence**, not personal taste, and that you can carry an organization through the decision.

---

## 🔴 Expert (15+ yrs)

### Q43. [Theory] How would you design a cross-platform strategy for an organization with web, iOS, and Android?

At org scale the question is **where to share and where to specialize**, balancing velocity against fidelity and risk:

- **Share business logic, not necessarily UI.** Options: **Kotlin Multiplatform (KMP)** to share domain/data layers across iOS/Android (and even web/server) while keeping **native UI** (Compose + SwiftUI); or RN/Flutter to share UI too.
- **Decision drivers:** existing talent (a React org leans RN; a strong native org leans KMP), UI fidelity requirements, performance-critical surfaces, and the cost of maintaining native modules/plugins.
- **Hybrid is common:** a shared shell with **native islands** for performance- or platform-critical screens (camera, maps, AR), and cross-platform for the rest.
- **Platform investment:** internal design system, CI/CD, release tooling, and module APIs so feature teams move fast safely.

The expert framing is that this is a **portfolio decision with reversibility costs**: you architect clean boundaries (interfaces between shared logic and platform UI) so the organization can change its mind per-surface without a rewrite, and you tie the choice to hiring, retention, and roadmap — not just technology.

### Q44. [Theory] How do you build an effective mobile observability, crash, and performance monitoring practice?

You can't SSH into a phone, so production telemetry is everything:

- **Crash & error reporting:** Crashlytics / Sentry with **symbolicated** stack traces (upload dSYMs/ProGuard mappings); track **crash-free users/sessions** as a top-line SLO.
- **Performance:** capture **cold-start time, frozen/slow frames (jank), ANRs**, network latency, and memory via Firebase Performance / Android Vitals / MetricKit / Sentry. Watch **Android Vitals** and App Store metrics because they affect store ranking.
- **Real-user vs lab:** complement RUM with **Macrobenchmark/Baseline Profiles** and Instruments in CI to catch regressions before release.
- **Release health:** monitor crash rate **per app version** during **staged rollout**, and **auto-halt** the rollout on regression.
- **Privacy:** scrub PII, honor consent, and minimize data collected.

The expert focus is closing the loop: SLOs, alerting on release-health regressions, gating rollouts on metrics, and feeding findings back into the next release — treating the fleet of devices like a distributed system you operate.

### Q45. [Theory] What are the deep performance differences between Flutter's and React Native's rendering models?

They sit at opposite ends of the spectrum:

- **Flutter** owns the whole stack: Dart compiles **AOT to native code**, and the **Impeller** engine renders **every pixel itself** on the GPU, bypassing platform widgets. Consequences: extremely consistent cross-platform visuals and smooth custom animations, predictable frame pipeline, but a **larger binary** (ships the engine), and UI that isn't literally the OS's widgets (accessibility/feel must be matched deliberately).

- **React Native** renders **real native views**. With the **New Architecture**, **Fabric** builds the shadow tree in C++ and **JSI** enables synchronous native calls, so UI is genuinely native (free platform look, accessibility, and OS updates), and animations can run on the UI thread via **Reanimated** worklets. The cost historically was the JS↔native boundary, largely mitigated by JSI/Fabric.

```
 Flutter:  Dart(AOT) ─▶ Impeller engine ─▶ GPU  (own canvas)
 RN:       JS ⇄ JSI/C++(Fabric) ─▶ real native UIViews/Views
```

The expert point: choose based on whether you value **pixel-identical control and animation** (Flutter draws everything) or **true platform-native widgets and ecosystem** (RN composes them), and understand that both can now hit 60/120fps when used correctly — the bottlenecks are usually *your* main-thread/JS-thread work, not the framework.

### Q46. [Theory] How do you handle a fragmented device and OS landscape at scale (low-end Android, old iOS, varied screens)?

Fragmentation is the defining mobile-scale problem; you manage it deliberately:

- **Support policy:** define a **minimum OS/API level** and device tier you support, driven by real install-base data, and sunset old versions on a published schedule.
- **Tier your experience:** detect low-end devices (RAM, class) and **degrade gracefully** — fewer animations, smaller images, disabled expensive effects (`Build.VERSION`, `ProcessInfo`, device-class heuristics).
- **Test the matrix:** device farms (Firebase Test Lab, BrowserStack, real low-end devices) for the OS×device combinations that matter; don't test only on flagship hardware.
- **Adaptive UI:** support phones, foldables, tablets, and large screens with responsive layouts (window size classes, `WindowSizeClass`, SwiftUI size classes) — and handle **dynamic type/accessibility scaling**.
- **Feature gating:** ship features behind flags and roll out per-OS/per-device-tier; guard new APIs with availability checks.

The expert mindset: treat the install base as a **long-tail distribution** and engineer for the p90 device, not your dev phone — measured by real-world performance percentiles, not lab numbers.

### Q47. [Behavioral] How do you lead a mobile team through a large platform migration (e.g. Views→Compose, UIKit→SwiftUI, or RN New Architecture)?

This probes technical leadership on a risky, long-running change. A strong answer covers:

- **Justify the migration** with concrete pain (velocity, hiring, defect rate, framework EOL) and tie it to business value — not novelty.
- **Incremental, interop-driven strategy:** both Compose/Views and SwiftUI/UIKit **interoperate**, so you migrate **screen-by-screen or new-features-first**, never a big-bang rewrite. For the RN New Architecture, you stage library compatibility and enable it behind a flag.
- **De-risk:** a pilot module, clear coding guidelines, shared components, and **metrics** (crash rate, performance, build time) to prove no regression before scaling.
- **Enablement:** training, pairing, and documentation so the whole team levels up, plus a **lint/CI gate** preventing new code in the old paradigm.
- **Honest stakeholder communication:** realistic timelines, the cost of *not* migrating, and protecting feature delivery during the transition.

The signal is that you **manage risk and people**, sequence the work for continuous value, lean on interop to avoid freezes, and measure your way through rather than betting the roadmap on a rewrite.

---

## 🧩 Extended Questions — Set 1: Deeper theory & internals

### 🟢 — extended

#### Q48. [Theory] What actually is a "frame" in the rendering pipeline, and what stages does it pass through?

A **frame** is one complete image the GPU hands to the display at a vsync (vertical sync) boundary. The display refreshes at a fixed cadence — 60 Hz (~16.67 ms), 90/120 Hz on high-refresh panels (~11.1/8.33 ms) — and the OS schedules a render pass per refresh via a vsync signal.

The pipeline, roughly identical conceptually across platforms:

```
 vsync ─▶ input ─▶ animation ─▶ measure/layout ─▶ draw/record ─▶ rasterize ─▶ composite ─▶ display
```

- **Input** — touch/gesture events are dispatched.
- **Animation** — running animators advance their values for this frame's timestamp.
- **Measure & layout** — the view/element tree computes sizes and positions (Compose: measure → place; Flutter: layout on the RenderObject tree; iOS: Auto Layout pass).
- **Draw/record** — drawing commands are recorded into a display list (not yet pixels).
- **Rasterize** — the GPU turns the display list into pixels, often on a separate **raster/render thread** (Android's RenderThread, Flutter's raster thread).
- **Composite** — layers are combined by the system compositor (SurfaceFlinger on Android, Core Animation's render server on iOS).

The key insight: **layout and recording happen on the UI thread, rasterization usually does not.** A frame is "dropped" (jank) when the UI-thread work for that frame doesn't finish before the next vsync, so the compositor reuses the old frame. This is why measuring "main-thread time per frame against the budget" is the right mental model, not "FPS" in the abstract.

#### Q49. [Theory] What is the difference between vsync, the choreographer, and double/triple buffering?

These are the plumbing that turns "draw a frame" into "show a frame without tearing":

- **vsync** is the hardware signal marking the start of a display refresh interval. Drawing must be paced to it so a half-finished frame never reaches the screen (which would cause **tearing**).
- **The Choreographer** (Android) / **CADisplayLink** (iOS) / Flutter's `SchedulerBinding` is the per-vsync callback that wakes the UI thread to produce the next frame. You hook animations into it rather than using arbitrary timers, so your work aligns with the refresh cadence.
- **Buffering** decouples "the GPU is drawing buffer N" from "the display is scanning out buffer N−1":
  - **Double buffering** — one buffer being displayed, one being drawn. If the draw isn't ready at vsync, you stall and repeat the old frame.
  - **Triple buffering** — adds a third buffer so the CPU/GPU can keep working on a future frame even when one is queued, smoothing over occasional long frames at the cost of one extra frame of latency. Android added triple buffering specifically to reduce jank from a single slow frame cascading.

The practical takeaway: the system already paces you to vsync via the choreographer; your job is to finish UI-thread work inside the interval. Buffering hides *small* hiccups but cannot rescue *consistently* over-budget frames.

#### Q50. [Theory] What is "recomposition" precisely, and what is the difference between composition, layout, and draw phases in Compose?

Compose runs in **three phases** each frame, and understanding the split is what lets you optimize correctly:

1. **Composition** — *what* to show. Composable functions execute, emitting/updating nodes in the slot table. Recomposition is re-running (only) the composables whose observed `State` changed.
2. **Layout** — *where* and *how big*. Each node is measured (measure children, decide own size) then placed.
3. **Drawing** — *how it renders*. Each node records draw commands.

```
 Composition ─▶ Layout ─▶ Drawing
 (run @Composable)  (measure/place)  (record draw)
```

The crucial point: a state change does **not** necessarily trigger all three. If you read state in a way that only affects layout (e.g. `Modifier.offset { state.value }` — a lambda read) or only affects draw (`Modifier.drawBehind { }`, `graphicsLayer { }`), Compose can **skip composition** and re-run only layout or only draw. This "deferred read" technique is the single most important Compose performance tool: reading rapidly-changing values (scroll offset, animation progress) in the layout/draw phase instead of the composition phase avoids recomposing the whole subtree 60–120 times a second.

#### Q51. [Theory] How does SwiftUI decide what to re-render, and what is the role of identity and `@State`'s dependency graph?

SwiftUI builds a **dependency graph** (internally the "AttributeGraph") of your view's body. When a value a view *depends on* changes, SwiftUI invalidates exactly the affected nodes and re-evaluates their `body`, then **diffs** the resulting view values against the previous render to compute the minimal set of UIKit/AppKit-level changes.

Two concepts govern this:

- **Dependencies** — established when a view reads `@State`, `@Binding`, `@Observable` properties, `@Environment`, etc. With the `@Observable` macro, dependency tracking is **per-property and lazy**: a view that reads only `model.name` is *not* invalidated when `model.age` changes (a major improvement over `ObservableObject`, where any `@Published` change invalidated every observer).
- **Identity** — SwiftUI matches views across renders by **structural identity** (position in the view tree) or **explicit identity** (`.id(...)`, `ForEach` ids). If identity changes, SwiftUI treats it as a *new* view: it discards `@State`, runs `.onAppear`, and animates a transition rather than an update.

So "re-render" in SwiftUI is really: re-evaluate `body` for invalidated nodes → diff value trees → apply minimal mutations. Excess work comes from over-broad dependencies (reading a whole model when you need one field) or accidental identity changes (an unstable `.id`) that throw away state.

#### Q52. [Practical] Why does `key` matter in lazy lists, and what bug appears when you omit or misuse it?

A stable **key** tells the framework *which item is which* across data changes, so it can match old UI nodes to new data instead of reusing them by position.

```kotlin
// Compose — without keys, position-based reuse causes wrong state/animations
LazyColumn {
    items(messages, key = { it.id }) { msg ->   // stable id per item
        MessageRow(msg)
    }
}
```

```jsx
// React Native — keyExtractor plays the same role
<FlashList data={messages} keyExtractor={m => m.id} renderItem={renderRow} />
```

Without a stable key, the framework keys by **index/position**. The classic bugs:

- **State attaches to the wrong row.** If you delete the first item, every row shifts up by one index; a row's local state (a half-typed comment, an expanded/collapsed toggle, a running animation) "sticks" to the old position and now shows on the wrong item.
- **Reset/animation glitches.** Inserting at the top makes the framework think every item changed, causing unnecessary rebuilds or flickering.
- **Lost scroll position / focus** on data refresh.

Using `index` as a key is the anti-pattern — it's only safe for static, append-only, never-reordered lists. A real database id, UUID, or composite natural key is correct. The deeper principle: keys are about **identity over time**, which is what diffing algorithms need to be correct, not just fast.

#### Q53. [Theory] What is the difference between a cold start, the "splash → first frame" window, and Time To Initial Display?

These describe *layers* of the same launch experience and are often conflated:

- **Cold start** is the OS-level event: no process exists, so the system **forks/zygotes** (Android) or `exec`s a new process, loads your executable and frameworks, then begins app initialization. This includes work you don't control (process creation, library loading) plus work you do (`Application.onCreate` / `didFinishLaunching`).
- **Splash → first frame** is the window from process start until the **first frame your app draws** replaces the system's initial/splash window. Android shows a themed launch window automatically; the system swaps it for your content at the first frame.
- **Time To Initial Display (TTID)** = the moment that first frame is rendered. **Time To Full Display (TTFD)** = when the screen is *useful* (data loaded, placeholders replaced), which you signal with `reportFullyDrawn()` (Android) or a MetricKit/custom marker (iOS).

The distinction matters because optimizing the wrong layer wastes effort: shaving 50 ms off process/library load needs **Baseline Profiles / lazy library init**, whereas shrinking TTID→TTFD needs **skeleton UI and async data loading**. Users perceive TTFD; store rankings and Vitals weight cold-start TTID.

#### Q54. [Theory] What is the difference between `remember` and `rememberSaveable` in Compose, and when does each survive?

Both retain a value across recompositions, but they survive **different lifecycle events**:

- **`remember`** stores a value in the **composition** (the slot table). It survives **recomposition** — the composable re-running because state changed — but is **lost** when the composable leaves the composition *or* when the whole Activity is recreated (configuration change, process death).
- **`rememberSaveable`** additionally persists the value into the **saved-instance-state bundle**, so it survives **configuration changes** (rotation) and **system-initiated process death** (the app being killed in the background and restored). It works for types the Saver can serialize (primitives, `Parcelable`, or a custom `Saver`).

```kotlin
var draft by remember { mutableStateOf("") }          // lost on rotation
var draftKept by rememberSaveable { mutableStateOf("") } // survives rotation & process death
```

The rule of thumb: use **`remember`** for transient, cheap-to-recreate values (animation state, derived caches, a scroll position you don't mind resetting) and **`rememberSaveable`** for **user-visible input you must not lose** — a half-typed message, a selected tab, expanded state. The deeper point is that mobile UIs are destroyed and recreated constantly, so "where does this value live" is a correctness question, not just a performance one; for anything that should outlive the *screen* itself (not just the composable), the value belongs in a `ViewModel`/`SavedStateHandle`, not `remember`.

#### Q55. [Practical] Why are inline lambdas and unstable parameters a recomposition concern, and how do you keep composables skippable?

Compose can **skip** recomposing a composable only if all its parameters are **stable and unchanged** (`equals`-comparable to the previous values). A freshly allocated unstable object each recomposition breaks that check:

```kotlin
// Problematic: passing a plain List (unstable) makes Child unskippable
Child(items = mutableListOf(...), onClick = { viewModel.onClick(item.id) })

// Better: pass a stable/immutable type; let the compiler memoize the lambda
Child(items = items.toImmutableList(), onClick = { viewModel.onClick(item.id) })
```

Key points:

- The **Compose compiler already memoizes** lambdas that capture only stable values, so most inline lambdas are fine — the problem is lambdas capturing **unstable** captures, or non-lambda allocations (a `listOf(...)`, a new data object) created inline in the call.
- Passing an **unstable type** (a plain `List`, a class Compose can't prove `@Stable`) as a parameter makes the whole composable unskippable regardless of lambdas. Use `kotlinx.collections.immutable` types or `@Immutable`/`@Stable` annotations.
- `Modifier` chains compare structurally, so identical chains compare equal; the real cost is reading **changing state** inside a modifier in the composition phase rather than a `Modifier.layout`/`drawBehind` lambda.

The discipline: pass stable/immutable types, let the compiler memoize lambdas (don't fight it), and only manually `remember` a lambda when it captures something unstable you can key on. Verify with **layout-inspector recomposition counts** rather than guessing.

### 🟡 — extended

#### Q56. [Theory] How does JSI actually let JavaScript call C++/native synchronously, and why was that impossible over the old bridge?

The **old bridge** worked by message-passing: JS could not hold a pointer to a native object, so every call was serialized to a JSON string, queued, sent across an asynchronous boundary, deserialized, and executed later — with the result coming back the same way. Asynchrony and serialization were *structural*, not incidental.

**JSI (JavaScript Interface)** is a thin C++ API that the JS engine (Hermes, or JSC) implements. It exposes the engine's runtime so C++ can:

- Create **`HostObject`s** and **`HostFunction`s** — native C++ objects/functions exposed *as if they were JS values*. JS holds a real reference to them.
- Read/write JS values and **invoke** them directly, in the same thread, with no JSON in between.

Because JS now holds a handle to a native object and both live in the same address space reachable from C++, a JS call can **synchronously** enter C++ and return a value — like a normal function call. This is what makes TurboModules able to return values synchronously and Fabric able to do synchronous measurement.

```js
// Conceptually: a TurboModule method backed by a JSI HostFunction
const { width } = NativeDeviceInfo.getConstants(); // synchronous, no bridge
```

The trade-off is that synchronous native work now **blocks the JS thread**, so you still push genuinely heavy work to native threads/async APIs — JSI removes the *forced* asynchrony, it doesn't make blocking free.

#### Q57. [Theory] What is Hermes, and how do bytecode precompilation and the absence of a JIT affect mobile performance?

**Hermes** is the JavaScript engine purpose-built for React Native (default in the New Architecture). Its design choices are all about mobile constraints:

- **Ahead-of-time bytecode.** Instead of shipping JS source that the engine parses and compiles at startup, Hermes **precompiles your bundle to bytecode at build time**. On device there's no parse/compile step — the engine memory-maps the bytecode and starts executing. This dramatically cuts **TTI (time-to-interactive)** because parsing a large JS bundle was a major cold-start cost.
- **No JIT.** Hermes is an interpreter (with optimizations) and deliberately omits a Just-In-Time compiler. A JIT speeds up long-running hot loops but costs **memory** (compiled code caches), **startup** (warm-up), and runs afoul of iOS restrictions on executable memory. For typical app workloads — short bursts of UI logic, not number-crunching — skipping the JIT yields **lower memory and faster, more predictable startup**, which matters more on low-end phones.
- **Smaller footprint & GC tuned for mobile** (e.g. a GC that minimizes pause-induced jank).

The trade-off: CPU-bound JS (heavy computation, big crypto loops) can be slower than on a JIT engine like V8. The mitigation is the same as always — move heavy compute to native (a TurboModule) or to a worklet, and keep the JS thread for orchestration.

#### Q58. [Theory] What is the Reanimated "worklet," and why does running animations off the JS thread eliminate a class of jank?

In RN, gesture-driven and continuous animations are jank-prone if they depend on the **JS thread**, because any JS work (a network callback, a re-render) blocks the per-frame animation updates, and the JS↔UI hop adds latency.

**Reanimated** solves this with **worklets**: small JS functions marked (`'worklet'`, or auto-detected) that Reanimated extracts and runs on a **separate UI-thread JS runtime**, synchronized with the native render loop. Shared values (`useSharedValue`) live in memory accessible to both runtimes.

```js
const offset = useSharedValue(0);
const style = useAnimatedStyle(() => {
  'worklet';
  return { transform: [{ translateX: offset.value }] }; // runs on UI thread
});
const gesture = Gesture.Pan().onUpdate(e => { offset.value = e.translationX; });
```

Because the animation's per-frame math and the resulting style updates execute **on the UI thread in lockstep with vsync**, they continue smoothly even while the JS thread is busy. The JS thread is only involved to *set up* the animation and to receive occasional callbacks. This is the same architectural idea as Compose's deferred reads or Core Animation's render-server animations: keep the per-frame loop off the thread that does heavy, unpredictable work.

#### Q59. [Theory] How does Flutter's element-tree reconciliation work, and why is rebuilding the widget tree cheap?

Flutter separates **configuration** (widgets) from **instantiation** (elements). Every `build` returns a fresh, immutable widget tree — but Flutter does **not** rebuild the expensive parts. Instead it reconciles new widgets against the existing **element tree**:

For each position, Flutter compares the new widget to the element's current widget:

1. If `runtimeType` **and** `key` match → **reuse** the element (and its `State` and `RenderObject`); just update the element's widget reference and mark it for an update. This is the cheap path.
2. If they differ → **deactivate** the old element/subtree and **inflate** a new one.

```
 new Widget tree      existing Element tree
   (cheap, GC'd)  ──diff──▶  (retained, holds State + RenderObject)
```

Why this is cheap: widgets are tiny immutable value objects (just a config bag), so allocating thousands per frame is fine and they're quickly garbage-collected. The **expensive** objects — `State` (your mutable data) and `RenderObject` (layout/paint machinery, with cached layout) — live in the element tree and are **reused** when types/keys match. So `setState` rebuilding a large widget subtree mostly just re-diffs lightweight configs and re-runs layout/paint only where something actually changed. This is why the guidance is "make widgets small and `const` where possible" — `const` widgets are canonicalized and skip diffing entirely.

#### Q60. [Practical] What is `const` constructor canonicalization in Flutter and how does it cut rebuild cost?

A **`const` widget** is constructed at compile time and **canonicalized** — every `const Text('Hi')` with identical arguments is the *same object instance* in memory. This unlocks two optimizations:

```dart
// Reused identical instance; subtree skipped on rebuild
const Padding(
  padding: EdgeInsets.all(8),
  child: Text('Static label'),
)
```

- **Identity short-circuit in diffing.** During reconciliation, Flutter compares the old and new widget; if they're the *same instance* (`identical(old, new)`), it knows nothing changed and **skips rebuilding that entire subtree** — no element update, no layout, no paint.
- **Zero allocation per build.** A `const` widget in a `build` method isn't re-allocated each frame; the canonical instance is reused, reducing GC pressure.

The practical rule: mark every widget that doesn't depend on runtime state as `const`. A parent's `setState` then only rebuilds the dynamic branches, while large static subtrees (icons, labels, decorations) are skipped via identity. Linters (`prefer_const_constructors`) enforce this. It's a small habit with an outsized effect on rebuild cost in deep trees.

#### Q61. [Theory] How do `StateFlow`, `SharedFlow`, and `collectAsStateWithLifecycle` interact, and why use the lifecycle-aware collector?

These compose into the recommended Android UDF stack:

- **`StateFlow`** — a hot, **state-holder** flow: always has a current value, conflates (only the latest matters), and emits the current value to new collectors. Ideal for UI state.
- **`SharedFlow`** — a hot, configurable **event** flow: no required initial value, tunable replay/buffer. Used for one-shot events (navigation, snackbars) where you *don't* want a "current value" replayed on rotation.
- **`collectAsStateWithLifecycle`** — collects a flow as Compose `State`, but **only while the lifecycle is at least STARTED**, automatically stopping collection in the background and restarting on return.

```kotlin
val uiState by viewModel.state.collectAsStateWithLifecycle()
```

Why the lifecycle-aware collector matters: plain `collectAsState` keeps collecting even when the screen is in the background, which **wastes resources** and can keep **upstream work alive** (e.g. a `WhileSubscribed` flow stays "subscribed," holding a database cursor or network stream open). `collectAsStateWithLifecycle` ties collection to visibility, so backgrounding the app releases upstream resources and foregrounding resumes them — the correct default for screen-level state. For events, prefer `SharedFlow` consumed in a lifecycle-aware effect so an event isn't re-delivered after a config change.

#### Q62. [Practical] What is `derivedStateOf` for, and how is it different from just computing a value in a composable?

`derivedStateOf` creates a **derived state object** that only notifies readers when its *computed result* changes — not every time an input changes. Use it when you transform frequently-changing state into a value that changes **rarely**.

```kotlin
val listState = rememberLazyListState()
// Recomposes only when the boolean flips, not on every scroll pixel:
val showButton by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 0 }
}
```

Contrast with computing inline:

```kotlin
// BAD: reads firstVisibleItemIndex directly in composition →
// recomposes on every scroll frame even though the button visibility rarely changes
val showButton = listState.firstVisibleItemIndex > 0
```

The difference: the inline version makes the composable depend on `firstVisibleItemIndex`, a value that changes on essentially every scroll frame, so it recomposes 60–120×/second. `derivedStateOf` interposes a layer that recomputes the lambda on input change but **only invalidates readers when its output changes** (here, when the boolean actually flips). Use it when **many inputs map to few output changes**. Don't use it for simple 1:1 transforms (`remember(input) { transform(input) }` is cheaper) — `derivedStateOf` has overhead that only pays off when it filters out emissions.

### 🟠 — extended

#### Q63. [Theory] How does ART's AOT/JIT hybrid compilation and Baseline Profiles affect startup and steady-state performance?

Android's runtime (**ART**) uses a **hybrid** compilation strategy that evolved to balance install size, startup, and peak speed:

- At **install** time, apps aren't fully AOT-compiled (that bloats install size and storage). Code starts **interpreted**.
- A **JIT** compiles hot methods at runtime and records a **profile** of what's hot.
- During device idle/charging, **`dex2oat`** AOT-compiles those profiled hot methods to native code (**profile-guided optimization**), so subsequent runs are faster.

The problem: a freshly installed app has **no profile yet**, so the first several launches are slow (interpreted/JIT warm-up). **Baseline Profiles** fix this: you ship a profile (a list of hot methods/classes, generated via Macrobenchmark) **inside the APK/AAB**. ART AOT-compiles those methods at install time, so even the **first launch** runs precompiled hot paths. Cloud Profiles aggregate real-user profiles to extend this further.

Impact: Baseline Profiles routinely improve **cold start by 15–40%** and reduce **first-run jank** in scrolling, because the critical-path code is native from launch one instead of after the JIT warms up. The expert practice is to generate and verify them in CI and treat them as a shipping artifact, not an afterthought.

#### Q64. [Theory] What is Impeller, and what problem ("shader compilation jank") does it solve versus Skia?

**Impeller** is Flutter's rendering engine (default on iOS and Android by 2026), replacing the older **Skia** path for the hot path.

The core problem it solves is **shader compilation jank**. With Skia, GPU shaders for certain effects were compiled **lazily, at runtime, the first time** a given drawing operation appeared — and shader compilation is expensive (tens of ms), causing a **visible stutter** the first time you hit a new animation, transition, or effect. Workarounds (shader warm-up via SkSL precompilation) were fragile and device-specific.

Impeller's design eliminates this:

- It uses a **fixed, known set of shaders compiled at engine build time** (offline), so there's **no runtime shader compilation** on the hot path — the first run of an animation is as smooth as the hundredth.
- It targets modern GPU APIs directly — **Metal** on iOS, **Vulkan** on Android (with an OpenGL fallback) — rather than going through Skia's more general abstraction.
- It's built for Flutter's specific needs (predictable frame pipeline, efficient use of the GPU), giving more consistent frame times.

The trade-off was a multi-year migration and some feature/perf parity work, but the payoff is **predictable first-run smoothness** — exactly the kind of intermittent jank that was hardest to diagnose and most visible to users.

#### Q65. [Theory] How does Kotlin Multiplatform compile shared code to each target, and what is the boundary between shared and platform code?

**Kotlin Multiplatform (KMP)** lets you write shared logic once and compile it natively for each platform — *without* a shared UI layer (the common pattern: share domain/data, keep native UI).

The compilation targets differ per platform:

- **Android/JVM** — shared Kotlin compiles to **JVM bytecode**, used directly like any Kotlin/Java code.
- **iOS** — shared Kotlin compiles via **Kotlin/Native (LLVM)** to a native binary packaged as an **Objective-C/Swift-interoperable framework**. Swift code calls it like a normal framework (with some interop caveats around generics, suspend functions exposed as completion handlers, and memory model).
- **Other targets** — JS/Wasm, native desktop, server (JVM), etc.

The **expect/actual** mechanism defines the boundary: common code declares an `expect` API; each platform provides the `actual` implementation.

```kotlin
// commonMain
expect fun platformName(): String
// androidMain
actual fun platformName() = "Android ${Build.VERSION.SDK_INT}"
// iosMain
actual fun platformName() = UIDevice.currentDevice.systemName()
```

So the boundary is explicit and compiler-enforced: pure business logic, networking (Ktor), serialization, and persistence (SQLDelight) live in `commonMain`; anything needing platform APIs (Keychain vs Keystore, camera, exact UI) is provided per-platform via `actual` or stays in native UI code. The win is **one tested copy of business logic** with **fully native UI and zero UI-framework lock-in**.

#### Q66. [Coding] Implement a single-flight / request-deduplication cache so concurrent callers share one in-flight network call.

When several parts of the UI request the same resource simultaneously (e.g. a token refresh triggered by parallel requests), you want **one** network call shared by all callers, not N duplicates. This is the "single-flight" pattern.

```kotlin
class SingleFlight<K, V> {
    private val inFlight = mutableMapOf<K, Deferred<V>>()
    private val mutex = Mutex()

    suspend fun run(key: K, scope: CoroutineScope, block: suspend () -> V): V {
        val deferred = mutex.withLock {
            inFlight[key] ?: scope.async {
                try { block() } finally { mutex.withLock { inFlight.remove(key) } }
            }.also { inFlight[key] = it }
        }
        return deferred.await()   // all concurrent callers await the SAME job
    }
}

// Usage: parallel callers with the same key share one fetch
val flight = SingleFlight<String, User>()
suspend fun getUser(id: String) =
    flight.run(id, viewModelScope) { api.fetchUser(id) }
```

- The `mutex` guards the map so we atomically **check-or-create** the shared `Deferred`. Concurrent callers with the same key find the existing job and `await` it.
- The `finally` removes the entry once complete, so the **next** request after completion starts fresh (this is dedup of *in-flight* calls, not a result cache — combine with an LRU result cache for caching).
- This eliminates the classic **stampede** where a 401 triggers ten simultaneous token refreshes. The Swift/Combine analogue uses a shared publisher with `.share()`; TanStack Query dedupes by query key automatically.

#### Q67. [Theory] How do ProGuard/R8 shrinking, obfuscation, and stack-trace deobfuscation interact, and what breaks if you misconfigure them?

**R8** (the default, replacing ProGuard) does three things in one pass on release builds:

- **Shrinking (tree-shaking)** — removes unreachable classes, methods, and fields, computed from your entry points (manifest, keep rules).
- **Optimization** — inlining, class merging, dead-code elimination.
- **Obfuscation** — renames remaining symbols to short meaningless names (`a.b.c`) to shrink and deter reverse-engineering.

The catch: obfuscation **rewrites your stack traces**. A crash now reports `a.b()` instead of `PaymentService.charge()`. To make crashes readable, R8 emits a **mapping file** (`mapping.txt`) that you **upload to Crashlytics/Sentry** so they can **deobfuscate (retrace)** production stacks back to real names.

What breaks when misconfigured:

- **Reflection / serialization / DI** that looks up classes by name fails, because R8 renamed or removed the class (it can't see reflective access). Fix with `-keep` rules or annotations (`@Keep`, library-provided rules, `@Serializable`).
- **Losing or not uploading the mapping file** → permanently unreadable production stack traces (and you can't regenerate it — it's per-build).
- **Over-aggressive keep rules** → defeat shrinking, bloating the app.

The discipline: keep only what reflection truly needs, **archive every release's mapping file** (CI artifact) alongside the build, and verify deobfuscation works before a release reaches users. iOS has the analogous **dSYM** files for symbolicating Swift/C++ crash addresses.

#### Q68. [Theory] What is structured concurrency, and how do `CoroutineScope`/`viewModelScope` and Swift's `Task`/task groups prevent leaked work?

**Structured concurrency** is the principle that concurrent tasks form a **tree tied to a scope**: a scope doesn't complete until all its children complete, cancelling the scope cancels all children, and a child's failure propagates to the parent. This makes concurrency **lifetime-bounded** instead of a set of orphaned, fire-and-forget tasks.

On **Android/Kotlin**:

- Coroutines launch into a `CoroutineScope`. `viewModelScope` is **bound to the ViewModel's lifecycle** — when the ViewModel is cleared, the scope is cancelled, automatically cancelling every coroutine it launched (in-flight network calls, collectors). No manual teardown, no leaks.
- `coroutineScope { }`/`supervisorScope { }` create child scopes whose `await`/join waits for all children; an exception cancels siblings (or not, under a supervisor).

On **iOS/Swift**:

- A `Task` inherits and is bound to its context; `async let` and **task groups** (`withTaskGroup`) create child tasks the parent **awaits**, and cancelling the parent cancels children. SwiftUI's `.task { }` modifier ties a Task to the view's lifetime, cancelling it on disappear.

```swift
await withTaskGroup(of: Data.self) { group in
    for url in urls { group.addTask { try? await fetch(url) } }
    // scope won't exit until all child tasks finish or are cancelled
}
```

Why it matters on mobile: screens come and go constantly, so **unscoped** background work is the #1 source of leaks and wasted battery — a network call that finishes after its screen is gone, touching a dead ViewModel. Structured concurrency makes "cancel everything this screen started when it goes away" the **default**, not something you remember to wire up.

### 🔴 — extended

#### Q69. [Theory] How do the iOS and Android security models (sandboxing, hardware-backed keystores, Secure Enclave/StrongBox) actually protect secrets?

Both platforms layer OS sandboxing with **hardware-backed** key storage so that even a compromised app — or a rooted/jailbroken device — can't trivially exfiltrate keys.

- **Sandboxing.** Each app runs as its own UID (Android) / in its own container (iOS) with a private data directory other apps can't read. This is the first boundary: app A cannot read app B's files.
- **Hardware-backed key storage.** The sensitive bit is that the **private keys never live in app-readable memory**:
  - **iOS Secure Enclave** — a separate coprocessor with its own memory. Keys are generated **inside** it and **never leave**; your app gets a *handle* and asks the Enclave to sign/decrypt on its behalf. Even kernel compromise doesn't expose the raw key.
  - **Android Keystore + StrongBox** — keys are held by the **TEE (Trusted Execution Environment)** or a dedicated **StrongBox** secure element. `setUserAuthenticationRequired` can bind key use to a fresh biometric/PIN, and **key attestation** lets your server cryptographically verify the key is hardware-protected.
- **Biometric gating & access control.** Keychain accessibility classes and Keystore auth-binding ensure a key is only usable when the device is unlocked / the user just authenticated.

The expert framing: you're not "hiding" the secret in storage — you're arranging that **the secret is never extractable**, because the cryptographic operation happens inside hardware. The token or password you *do* store is protected by encryption keys rooted in that hardware. For highest assurance (payments, enterprise), combine hardware keys with **attestation** so the backend trusts only genuine, unrooted devices.

#### Q70. [Theory] How would you design a deterministic, idempotent sync protocol that survives partial failure, retries, and clock skew?

The goal is **eventual consistency** where every operation can be safely retried and applied at most once in effect, despite dropped connections mid-sync and unreliable client clocks.

Design pillars:

- **Client-generated idempotency keys.** Every mutation carries a UUID generated on the device *before* sending. The server records applied keys and **deduplicates** retries — so replaying the outbox after a timeout (where you don't know if the first attempt landed) is safe and applies the change exactly once.
- **Logical clocks, not wall clocks.** Don't trust device time for ordering (skew, manual changes, timezones). Use **server-assigned monotonic version numbers**, a **Hybrid Logical Clock**, or **vector clocks** so causality is well-defined regardless of device clock.
- **Cursor-based incremental pull.** The server hands out an opaque **sync cursor / change token**; the client persists it and resumes from exactly there, so an interrupted pull continues without re-fetching or skipping records. Each batch is committed transactionally with its cursor.
- **Transactional outbox + at-least-once delivery + idempotent apply = effectively exactly-once.** Writes go to a local outbox in the same DB transaction as the local state change; a sender drains it with backoff; idempotency keys make duplicate delivery harmless.
- **Conflict policy by data shape.** Last-write-wins (with logical timestamps) for independent fields; **field-level merge** for documents; **CRDTs** for collaborative data that must converge automatically without a server arbiter; **tombstones** so deletes propagate and don't resurrect.
- **Schema/version negotiation** so old clients and new servers agree on payload shape during staged rollout.

The hard-won insight: never assume a request that timed out failed. Build so that **"apply twice" equals "apply once"** and **"order by a number the server controls."** Then partial failure degrades to "retry later," and the system converges instead of corrupting.

#### Q71. [Theory] What are the deep trade-offs of the threading models — RN's multi-thread JS/UI split, Flutter's isolates, and native's single main thread with offload?

Each framework picks a different answer to "how do we keep the UI thread free while doing concurrent work," with distinct sharing/communication costs:

- **React Native** — historically a **JS thread** (your logic), a **native UI/main thread** (rendering, gestures), and worker threads. JS is **single-threaded**; you don't share mutable memory with native, you call across via JSI. Heavy JS still blocks the JS thread, so the model pushes you to native modules or Reanimated worklets (a *second* JS runtime on the UI thread). Trade-off: clear separation, but the JS thread is a single bottleneck for app logic.
- **Flutter** — Dart is single-threaded **per isolate**, with **no shared mutable memory** between isolates (the actor model). To parallelize CPU work you spawn an **isolate** and communicate via **message passing over ports** (copying or transferring data). Trade-off: no data races by construction and safe parallelism, but passing large objects has **copy cost**, and most apps run almost everything on the main isolate, offloading only heavy compute (`compute()`).
- **Native** — a single **main thread** for UI plus full OS threading (GCD/`Dispatcher`, threads, actors). You **can** share memory across threads — which means **real data races** and the need for synchronization (locks, actors, `@MainActor`, `Dispatchers`). Trade-off: maximum flexibility and performance, but you own correctness; modern tools (Swift actors, structured concurrency, Kotlin coroutines/`Mutex`) tame it.

The unifying expert view: all three protect the per-frame UI loop, but they differ on the **memory-sharing axis** — native shares memory (fast, dangerous), Flutter isolates share nothing (safe, copy cost), RN keeps JS single-threaded and crosses to native explicitly (clear boundary, single-thread bottleneck). Choosing or debugging performance means knowing **which thread/isolate your work runs on and what the cross-boundary cost is**.

#### Q72. [Coding] Implement a frame-budget-aware work scheduler that yields back to the main thread to avoid jank.

When you must process a large batch on the main thread (e.g. building a complex layout, processing items that can't move off-thread), do it in **chunks bounded by the frame budget**, yielding between chunks so input and rendering still happen. This keeps the UI responsive instead of freezing for one long block.

```kotlin
// Compose / Kotlin: process a large list in frame-budget-sized slices,
// yielding to let recomposition + input run between slices.
suspend fun processInBudget(
    items: List<Item>,
    budgetMs: Long = 8L,            // leave headroom in a 16ms (60Hz) frame
    onProcessed: (Item) -> Unit,
) {
    var sliceStart = System.nanoTime()
    for (item in items) {
        onProcessed(item)
        val elapsedMs = (System.nanoTime() - sliceStart) / 1_000_000
        if (elapsedMs >= budgetMs) {
            yield()                 // suspend: lets the frame render, checks cancellation
            sliceStart = System.nanoTime()
        }
    }
}
```

```swift
// Swift: cooperative chunking on the MainActor, yielding each frame slice.
@MainActor
func process(_ items: [Item], budget: Double = 0.008,
             handle: (Item) -> Void) async {
    var start = CACurrentMediaTime()
    for item in items {
        handle(item)
        if CACurrentMediaTime() - start >= budget {
            await Task.yield()      // give the run loop a frame, honor cancellation
            start = CACurrentMediaTime()
        }
    }
}
```

- The scheduler measures elapsed time per slice; once it nears the **frame budget** (~8 ms of a 16 ms frame, leaving room for the framework's own work), it **yields**, letting the next vsync render a frame and process input.
- `yield()` / `Task.yield()` also acts as a **cancellation checkpoint**, so if the screen goes away mid-processing, the structured-concurrency scope cancels the loop.
- This is the main-thread analogue of cooperative multitasking: when work genuinely can't leave the UI thread, **time-slice it against the frame budget** so perceived responsiveness is preserved. The real fix, where possible, is still to move the work off-thread entirely — but for inherently main-thread work, budgeted yielding is the tool.

#### Q73. [Theory] How do you reason about and measure end-to-end latency on mobile — input-to-pixel, including touch sampling, rendering, and display latency?

Perceived responsiveness is **end-to-end latency** from finger-down to the corresponding pixels lighting up, and it's a *pipeline* of contributions, not a single number:

- **Touch sampling latency** — the touchscreen samples at a rate (often 120 Hz+, sometimes higher than the display refresh). A touch can arrive up to one sampling interval before it's reported. High-refresh/high-touch-sample devices reduce this.
- **Input dispatch** — the OS routes the event to your app's UI thread; if that thread is busy, the event **queues** (this is where a janky app feels "laggy" even when finally drawn).
- **App processing + frame production** — your handler runs, state updates, and the next frame goes through measure/layout/draw on the UI thread (one frame budget).
- **Render/raster + composite** — the raster thread and system compositor turn it into pixels (often adds 1–2 frames due to buffering/triple buffering).
- **Display latency** — the panel scans out and the pixels physically change (panel response time, plus up to one refresh interval of scan-out).

So even a perfectly efficient app has **structural latency of a few frames** (input → produce → composite → scan-out). To measure it rigorously you don't trust intuition: use **high-speed camera capture** (record finger + screen at 240+ fps and count frames from touch to response), or platform tools — Android's **`FrameTimeline`/Perfetto** traces show per-frame deadlines and where a frame missed; iOS Instruments' **Core Animation/Hitches** instrument quantifies hitch time.

The expert practice: optimize the parts you control (**keep the UI thread free so events don't queue**, minimize frames-in-flight, use predictive/early touch APIs and high-refresh where it matters), set a **latency budget**, and verify with frame-level traces or camera measurement rather than FPS counters — because two apps at "60 fps" can have very different input-to-pixel latency depending on how many frames deep their pipeline runs.

#### Q74. [Theory] How do mobile GC and memory models differ (ART generational GC, Swift ARC, Dart GC), and how does that shape leak-avoidance strategy?

The runtimes reclaim memory by **fundamentally different mechanisms**, which changes *what* leaks and *how* you prevent it:

- **Android/ART — tracing, generational GC.** A collector periodically traces reachable objects from roots and frees the rest. Modern ART uses a **concurrent copying generational collector** that mostly avoids stop-the-world pauses, but GC still costs CPU and can cause **allocation jank** under churn. Leaks here are **reachability** problems: an object stays alive because *something* still references it (a static field holding a `Context`, a registered listener, a coroutine outliving its scope). You break leaks by **removing references** / scoping to a lifecycle.
- **iOS — ARC (Automatic Reference Counting), not GC.** The compiler inserts retain/release at compile time; an object is freed **immediately** when its strong reference count hits zero — deterministic, no GC pauses. The characteristic leak is a **retain cycle**: two objects (or an object and a closure) strongly reference each other, so neither's count reaches zero. You break cycles with `weak`/`unowned` references (notably `[weak self]` in closures and `weak` delegates).
- **Dart/Flutter — tracing generational GC** tuned for many short-lived widget allocations (the throwaway widget configs). Leaks come from **undisposed** resources: `StreamController`s, `AnimationController`s, listeners not removed in `dispose()`, or holding `BuildContext` past its lifetime.

The expert framing: the leak-avoidance *strategy follows the memory model*. Under tracing GC (ART/Dart) you think in **reachability and lifecycle scope** ("what still points at this?"); under ARC (iOS) you think in **ownership graphs and cycles** ("who owns whom, and where's the strong loop?"). Tooling matches: LeakCanary (retained-object detection) on Android, Instruments' Leaks/Allocations and the memory-graph debugger (cycle detection) on iOS, DevTools memory view on Flutter. Knowing the model tells you which question to ask first.

#### Q75. [Theory] What is the threading and identity model behind `@MainActor`, `nonisolated`, and Swift's data-race-safe concurrency, and how does it map to mobile UI safety?

Swift Concurrency makes **thread-safety part of the type system** via the **actor** model and `Sendable` checking, which directly enforces the "touch UI only on the main thread" rule at compile time:

- **`@MainActor`** is a global actor representing the **main thread**. Annotating a type, method, or property with `@MainActor` guarantees its code runs on the main thread; the compiler **inserts hops** (`await`) when you call into it from another context and **errors** if you try to access it synchronously from off-main. UI types (`View` bodies, `@Observable` view models that drive UI) are commonly `@MainActor`, so "update UI off the main thread" becomes a *compile error*, not a runtime crash.
- **Actors** serialize access to their mutable state: only one task touches an actor's state at a time, eliminating data races on that state without manual locks. A background actor (e.g. a cache or DB coordinator) protects its state while the `@MainActor` protects UI state.
- **`nonisolated`** opts a member out of the actor's isolation (e.g. a pure computed property or `Sendable` constant) so it can be accessed synchronously from anywhere — used to avoid needless hops for thread-safe members.
- **`Sendable`** marks types safe to cross actor/task boundaries; the compiler rejects passing non-`Sendable` mutable state between isolation domains, catching a whole class of races at build time.

```swift
@MainActor
@Observable final class ProfileViewModel {
    var name = ""                          // main-thread-isolated UI state
    nonisolated let id: UUID               // immutable, safe anywhere
    func load() async {
        let data = await repository.fetch() // repository may be its own actor
        name = data.name                    // guaranteed back on main; no manual dispatch
    }
}
```

The expert point: Swift turns the old, error-prone discipline ("remember to `DispatchQueue.main.async` before touching UIKit") into **compiler-enforced isolation**. Mobile UI safety stops being a convention you can forget and becomes a property the type checker verifies — the same shift Kotlin pursues with structured `Dispatchers` and `@MainThread`/lint, though Swift's actor isolation is stronger because it's in the type system.

#### Q76. [Coding] Implement a lifecycle-aware event bus that delivers one-shot events exactly once, even across configuration changes.

A subtle mobile bug: one-shot UI events (navigate, show snackbar) delivered via a replaying stream get **re-delivered after rotation/process restoration**, double-navigating. The fix is a channel that buffers while no one is collecting and delivers each event **exactly once** to the active, lifecycle-aware collector.

```kotlin
class EventBus<T> {
    // Channel (not StateFlow): events are consumed, not replayed.
    private val channel = Channel<T>(Channel.BUFFERED)
    val events: Flow<T> = channel.receiveAsFlow()   // each event delivered to ONE collector, once
    suspend fun send(event: T) = channel.send(event)
}

class NavViewModel : ViewModel() {
    private val bus = EventBus<NavEvent>()
    val events = bus.events
    fun openDetail(id: String) = viewModelScope.launch { bus.send(NavEvent.Detail(id)) }
}

@Composable
fun NavHostScreen(vm: NavViewModel, navController: NavController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        // Collect only while STARTED; buffered events resume on return, none lost or duplicated.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) { is NavEvent.Detail -> navController.navigate("detail/${event.id}") }
            }
        }
    }
}
```

- A **`Channel`** (consume-once) is correct here, unlike a `StateFlow`/`SharedFlow(replay>0)` which would **re-emit** the last event to a new collector after a config change — causing the double-navigation bug.
- **`repeatOnLifecycle(STARTED)`** cancels collection when the screen backgrounds and **restarts** it on return; events sent while backgrounded stay **buffered** in the channel and deliver once, in order, when collection resumes.
- The iOS analogue: a Combine `PassthroughSubject` (no replay) consumed in a `.task`/`.onReceive` tied to the view lifetime, or an async sequence consumed by a `@MainActor` task that SwiftUI cancels on disappear.

The principle: **state replays, events don't.** Modeling one-shot effects as a consumed stream — not as state — is what makes them survive recreation without duplicating.

#### Q77. [Theory] How does deep-link and app-link resolution work end to end, and what are the security and UX pitfalls (verified links, deferred deep linking)?

A deep link maps a URL to an in-app destination; doing it *securely* and *reliably* is more involved than registering a scheme:

- **Custom URL schemes (`myapp://…`)** are the oldest mechanism but are **insecure and unreliable**: any app can claim the same scheme (hijacking), and the OS may show a chooser. Use only as a fallback.
- **Verified links — Android App Links / iOS Universal Links** use a **real `https://` URL** plus a **server-hosted association file** that proves you own both the domain and the app:
  - Android: `assetlinks.json` at `/.well-known/` listing your package + signing-cert fingerprint; with `autoVerify`, the OS verifies it and opens your app **without a chooser**.
  - iOS: an **`apple-app-site-association` (AASA)** file at `/.well-known/`, plus the **Associated Domains** entitlement. The OS fetches and caches it to bind the domain to your app.
  This makes links **non-hijackable** (only the domain owner's app can claim them) and seamless (no disambiguation prompt), and the *same* URL works on web when the app isn't installed.
- **Resolution flow:** OS receives the URL → checks the verified association → if matched and app installed, routes to the app (Android: the intent/`NavDeepLink`; iOS: `onOpenURL`/`continueUserActivity`) → the app **parses and validates** the URL and navigates, often reconstructing a back stack so "up" behaves correctly.
- **Deferred deep linking:** if the app **isn't installed**, you want the user to land on the *intended* screen *after* installing. This needs a third-party/attribution mechanism (e.g. a service that records the intent pre-install and replays it on first launch), since neither store passes the original URL through install reliably.

Security/UX pitfalls:

- **Never trust deep-link parameters.** They're attacker-controllable input — **validate and authorize** (don't let `myapp://transfer?to=X&amount=Y` act without auth/confirmation; beware injection and open-redirect-style abuse).
- **Require auth/session checks** before honoring a link to a protected screen; deep links can bypass your normal navigation gates.
- **Handle the cold-start case** (link arrives before the app/session is ready — queue it until initialization completes) and **rebuild a sensible back stack** so users aren't stranded.
- **Keep the AASA/assetlinks file correct and reachable** (right cert fingerprint, no redirect, served as JSON) — a broken association silently downgrades to opening the website, a common "why won't my links open the app" bug.

The expert framing: treat deep links as an **untrusted public entry point into your app** — verified (domain-bound) for integrity and seamless UX, and **defensively validated/authorized** on arrival exactly like any other external input.

## 🧩 Extended Questions — Set 2: Practical scenarios, troubleshooting & coding

### 🟢 — extended

#### Q78. [Practical] Your app shows a blank white screen for 2 seconds on launch before content appears. How do you diagnose and fix it?

A blank/white launch window means the first useful frame is late — the system's launch window (or a plain background) is showing until your UI draws. Walk it methodically:

1. **Confirm it's cold start, not data.** Force-stop the app and relaunch (cold) vs. background-and-resume (warm/hot). If only cold start is slow, the cost is in process/library init or first-frame work; if every launch shows blank, it's likely synchronous data loading before drawing.
2. **Trace it.** On Android use a system trace (Perfetto / `adb shell am start -W` for total time, Macrobenchmark for TTID/TTFD). On iOS use Instruments' App Launch template or MetricKit launch metrics. Look at what runs between process start and first frame.
3. **Common culprits:** heavy work in `Application.onCreate` / `application(_:didFinishLaunching…)` (eager DI graph, analytics SDKs, database open, reading large prefs synchronously), a splash screen that *waits* on a network call, or decoding a big launch image.

Fixes: defer non-critical init (lazy-init SDKs, use Android's `App Startup` library / async init), draw a **skeleton or themed splash immediately** and load data after the first frame, and ship a **Baseline Profile** so hot paths are precompiled. The rule: never block the first frame on network or disk.

```kotlin
// Don't do blocking work here — it directly delays the first frame.
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // BAD: Analytics.init(this); db.openSync(); prefetchConfigBlocking()
        // GOOD: schedule non-critical init off the critical path
        AppInitializer.getInstance(this) // App Startup, lazy components
    }
}
```

#### Q79. [Practical] A user reports the app "freezes" when they tap a button, then resumes after a few seconds. What's almost certainly happening and how do you confirm it?

A freeze on tap is the textbook symptom of **blocking the main thread**: the tap handler is doing synchronous work (network, disk, JSON parsing, a big loop) on the UI thread, so no frames render until it finishes. On Android a long enough block triggers an **ANR**.

Confirm it:

- **Reproduce and profile.** Tap while recording a CPU/system trace (Android Studio profiler / Perfetto, Xcode Instruments Time Profiler). You'll see a long block on the main thread inside your handler.
- **Check for an ANR trace** (Android Vitals / `/data/anr/traces.txt` on a dev device) — it shows the main thread stuck in your method.
- **Look at the code path** the button triggers for any synchronous I/O.

Fix by moving the work off the main thread and only hopping back to update UI:

```kotlin
button.setOnClickListener {
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { repo.expensiveCall() } // off main
        renderResult(result) // back on main automatically
    }
}
```

The signal you give an interviewer: you treat "freeze on interaction" as a threading problem first, and you *prove* it with a trace rather than guessing.

#### Q80. [Practical] After rotating the device, the form the user was filling in is cleared. What went wrong and how do you fix it on each platform?

Rotation is a **configuration change**: on Android the Activity is destroyed and recreated by default, so any state held only in the view/composable (not saved) is lost. The form clearing means the state wasn't preserved across recreation.

Fixes:

- **Android (Compose):** use `rememberSaveable` for user input so it survives recreation and process death, or hoist the state into a `ViewModel` (survives config changes) — ideally with `SavedStateHandle` for process death too.
- **Android (Views):** override `onSaveInstanceState` / use `SavedStateHandle`, or give input views stable IDs so the framework restores them.
- **iOS:** SwiftUI views are value types recreated freely, but `@State`/`@StateObject`/your `@Observable` model persists across layout changes; rotation rarely clears state unless you key the view incorrectly. Watch for an unstable `.id(...)` that resets identity.

```kotlin
// Survives rotation AND background process death
var email by rememberSaveable { mutableStateOf("") }
TextField(value = email, onValueChange = { email = it })
```

The deeper lesson: on mobile, screens are destroyed and recreated constantly, so "where does this value live?" is a correctness decision — transient `remember` for throwaway state, `rememberSaveable`/`ViewModel` for anything the user would be upset to lose.

#### Q81. [Coding] Write a function that formats a "time ago" string (e.g. "3m", "2h", "5d") for a chat/feed list. Keep it cheap enough to call in a row builder.

Row builders run for every visible cell during scroll, so the formatter must be allocation-light and branch-cheap — no `Date` formatters with locale lookups per row.

```kotlin
// Kotlin — pure arithmetic, no allocation of heavy formatters
fun timeAgo(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val secs = (nowMillis - epochMillis) / 1000
    return when {
        secs < 60       -> "now"
        secs < 3600     -> "${secs / 60}m"
        secs < 86_400   -> "${secs / 3600}h"
        secs < 604_800  -> "${secs / 86_400}d"
        else            -> "${secs / 604_800}w"
    }
}
```

```swift
// Swift — same idea, integer math
func timeAgo(_ date: Date, now: Date = Date()) -> String {
    let s = Int(now.timeIntervalSince(date))
    switch s {
    case ..<60:      return "now"
    case ..<3600:    return "\(s / 60)m"
    case ..<86_400:  return "\(s / 3600)h"
    case ..<604_800: return "\(s / 86_400)d"
    default:         return "\(s / 604_800)w"
    }
}
```

Key points: precompute *nothing* heavy per row, avoid `DateFormatter`/`RelativeDateTimeFormatter` inside the hot loop (they're expensive and locale-bound — fine for a detail screen, not for 60fps scroll), and pass `now` in so the function is pure and unit-testable. For exact-minute updates you'd drive a single timer that refreshes the list, not a timer per row.

#### Q82. [Practical] Images in your list flicker and show the wrong picture briefly while scrolling. What's the bug and how do you fix it?

This is the classic **recycled-view image race**. The list recycles a row's view for a new item and kicks off an async image load; when the *previous* item's slower load finally returns, it writes its bitmap into the now-reused view — showing the wrong image until the correct one arrives.

Fixes:

- **Use a proper image library** (Coil/Glide on Android, SDWebImage/Kingfisher or SwiftUI `AsyncImage` on iOS, `cached_network_image` on Flutter). They **tag the target view with the request** and cancel/ignore stale completions automatically.
- If hand-rolling, **cancel the in-flight load** when the row is recycled and **verify the target still wants this URL** before setting the bitmap (associate the URL with the view and check on completion).
- Provide a **placeholder** and a **stable key** so recycling diffs correctly.

```kotlin
// Coil handles cancellation + correct target automatically
AsyncImage(
    model = ImageRequest.Builder(context).data(item.imageUrl).crossfade(true).build(),
    placeholder = painterResource(R.drawable.placeholder),
    contentDescription = null,
)
```

The principle: in a recycling list, **every async result must check it still belongs to the current binding** before mutating the view — otherwise stale completions corrupt the UI.

#### Q83. [Practical] Tapping a list item sometimes opens the wrong detail screen. How would you track this down?

Opening the *wrong* detail almost always means the row's click handler captured a **stale index or position** instead of the item's stable identity — a recycling/closure-capture bug.

How to track it down and fix:

- **Reproduce with logging:** log the item id at tap time vs. the id you navigate with. A mismatch confirms stale capture.
- **Don't navigate by position.** Capture the **item's stable id**, not `position`/`index`, in the handler. In a recycling adapter, read the id from the *bound* item (`getItem(holder.bindingAdapterPosition)`) at click time, or better, bind a click that closes over the immutable item id.
- **Provide stable keys** in the lazy list so item identity is consistent across data changes.

```kotlin
// Compose: closure captures the item's id, not its index — correct under reordering
LazyColumn {
    items(items, key = { it.id }) { item ->
        Row(Modifier.clickable { onOpen(item.id) }) { /* ... */ }
    }
}
```

The root cause is the same family as the image-flicker bug: **identity over time**. Anything that captures *position* breaks the moment the list reorders, inserts, or recycles; capture the stable id instead.

#### Q84. [Coding] Implement a simple validator for a sign-up form (email + password) that returns user-friendly errors. Show it wired to state.

Validation should be a **pure function of input** so it's testable and the UI just renders the result — not imperative `if` checks scattered in the view.

```kotlin
data class FormErrors(val email: String? = null, val password: String? = null) {
    val isValid get() = email == null && password == null
}

fun validate(email: String, password: String): FormErrors = FormErrors(
    email = when {
        email.isBlank()                       -> "Email is required"
        !email.contains("@") || !email.contains(".") -> "Enter a valid email"
        else -> null
    },
    password = when {
        password.length < 8                   -> "Use at least 8 characters"
        password.none { it.isDigit() }        -> "Include a number"
        else -> null
    },
)

// In the ViewModel/UI: derive errors from state, enable submit only when valid
val errors = validate(uiState.email, uiState.password)
SubmitButton(enabled = errors.isValid) { submit() }
```

```swift
// Swift equivalent — pure, returns optional messages
struct FormErrors { var email: String?; var password: String?
    var isValid: Bool { email == nil && password == nil } }

func validate(email: String, password: String) -> FormErrors {
    FormErrors(
        email: email.isEmpty ? "Email is required"
             : !(email.contains("@") && email.contains(".")) ? "Enter a valid email" : nil,
        password: password.count < 8 ? "Use at least 8 characters"
                : !password.contains(where: \.isNumber) ? "Include a number" : nil
    )
}
```

Notes: keep validation pure and centralized so it's unit-tested and reused; validate **on submit and on blur**, not on every keystroke (which feels hostile); and never rely on client validation for security — the server must re-validate. Use proper email rules server-side; the client check is just for fast, friendly feedback.

#### Q85. [Practical] The keyboard covers the text field the user is typing in. How do you fix this on each platform?

The on-screen keyboard ("IME"/soft keyboard) overlaps the focused input because the layout doesn't resize or scroll to keep it visible. Each platform has an idiomatic fix:

- **Android (Compose):** ensure `windowSoftInputMode`/edge-to-edge insets are handled — apply `Modifier.imePadding()` (and `.navigationBarsPadding()`) and place the field in a scrollable container so it scrolls above the keyboard. With `WindowCompat.setDecorFitsSystemWindows(window, false)`, you consume IME insets yourself.
- **iOS (SwiftUI):** put the field inside a `ScrollView`/`Form`; SwiftUI auto-scrolls to the focused field. Use `@FocusState` to manage focus and `.scrollDismissesKeyboard(.interactively)` for dismissal. For custom layouts, observe the keyboard frame and add bottom padding.
- **React Native:** wrap content in `KeyboardAvoidingView` (with the right `behavior` per platform) or use `react-native-keyboard-controller`; keep inputs inside a `ScrollView` with `keyboardShouldPersistTaps`.

```kotlin
Column(
    Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .imePadding()           // adds padding equal to the keyboard height
) { /* fields */ }
```

The principle is the same everywhere: react to the **keyboard inset** by padding/scrolling so the focused field and the submit button stay visible. Test on devices with different keyboard heights and with autofill bars.

#### Q103. [Coding] Write a debounced/throttled helper in plain JavaScript for a React Native search-as-you-type input, without external libraries.

You often need lightweight debounce/throttle for inputs and scroll handlers. Implement both clearly so you understand the difference.

```js
// Debounce: run fn only after `wait` ms have passed since the LAST call.
function debounce(fn, wait) {
  let timer;
  function debounced(...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), wait);
  }
  debounced.cancel = () => clearTimeout(timer);   // cancel on unmount
  return debounced;
}

// Throttle: run fn at most once per `interval` ms (leading edge).
function throttle(fn, interval) {
  let last = 0;
  return function (...args) {
    const now = Date.now();
    if (now - last >= interval) { last = now; fn.apply(this, args); }
  };
}

// Usage in a component:
const onSearch = useMemo(() => debounce(q => dispatchSearch(q), 300), []);
useEffect(() => () => onSearch.cancel(), [onSearch]); // clean up the pending timer
```

The distinction matters: **debounce** waits for a pause (right for search-as-you-type — you only want the final query), while **throttle** guarantees a steady rate (right for scroll/resize handlers where you want periodic updates, not just the last one). The mobile-specific care is **cancelling the pending timer on unmount** (the cleanup in `useEffect`) so a fired callback doesn't run against a torn-down component and warn about setting state on an unmounted component. In practice React's compiler or a hook like `useDebouncedValue` handles this, but knowing the primitive is essential.

### 🟡 — extended

#### Q86. [Practical] Your crash dashboard shows a spike in `NullPointerException` / `unexpectedly found nil` after a backend change. The app code didn't change. How do you respond?

A crash spike correlated with a *backend* change — not an app release — almost always means the app received a response shape it didn't expect (a field that became nullable, a renamed/removed key, an enum value the client doesn't know) and force-unwrapped or non-null-asserted it.

Response, in order:

1. **Stop the bleeding without a binary.** Since you can't ship an app fix instantly, the fastest mitigation is **reverting or guarding the backend change** (or feature-flagging it off) — it reaches all app versions immediately.
2. **Confirm the cause** from symbolicated stack traces: which model/parsing site is throwing, and which field is null.
3. **Harden the client** for the real fix: make parsing **tolerant** — default/optional fields, lenient JSON config (`ignoreUnknownKeys`, nullable types), and never force-unwrap server data.

```kotlin
@Serializable
data class Profile(
    val id: String,
    val name: String? = null,          // tolerate missing/null
    val tier: Tier = Tier.UNKNOWN,     // unknown enum → safe default, don't crash
)
// Json { ignoreUnknownKeys = true; coerceInputValues = true }
```

The lasting lesson: **treat every server payload as untrusted input.** Contract changes are inevitable; clients that crash on an added/removed/null field are brittle. Defensive deserialization plus a server-side kill switch is how you avoid forced app releases for backend-driven incidents.

#### Q87. [Coding] Implement a paginated list loader that fetches the next page when the user nears the bottom, with loading and error states.

Infinite scroll needs to detect "near the end," avoid duplicate fetches, and represent loading/error/end-of-list explicitly.

```kotlin
data class PageState(
    val items: List<Item> = emptyList(),
    val page: Int = 0,
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

class FeedViewModel(private val repo: Repo) : ViewModel() {
    private val _state = MutableStateFlow(PageState())
    val state = _state.asStateFlow()

    fun loadNextPage() {
        val s = _state.value
        if (s.isLoading || s.endReached) return            // guard against duplicate/over-fetch
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.fetchPage(s.page + 1) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            items = it.items + page.items,
                            page = it.page + 1,
                            isLoading = false,
                            endReached = page.items.isEmpty(),
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message) } }
        }
    }
}
```

```kotlin
// UI: trigger when the last visible item nears the end
val listState = rememberLazyListState()
LaunchedEffect(listState) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .collect { lastVisible ->
            if (lastVisible != null && lastVisible >= state.items.size - 5) vm.loadNextPage()
        }
}
```

The essentials: a single **isLoading/endReached guard** prevents duplicate and past-the-end fetches; trigger a few items *before* the bottom for a seamless feel; surface **error** with a retry affordance; and key items stably so appended pages don't disrupt scroll. TanStack Query's `useInfiniteQuery` (RN) or Paging 3 (Android) productionize this with caching and retries.

#### Q88. [Practical] Pull-to-refresh sometimes shows duplicate items or loses the user's scroll position. What causes each, and how do you fix them?

Two distinct bugs hide behind "refresh is glitchy":

- **Duplicates** happen when a refresh **appends** instead of **replacing/merging** by id, or when pagination and refresh race (a page load completes after a refresh). Fix by **deduplicating by stable id** (merge into a keyed map/set, or have the DB upsert by primary key) and by **cancelling in-flight pagination** when a refresh starts.
- **Lost scroll position** happens when the list's items are rebuilt with **new identities** (no stable keys) or the whole data list instance is replaced, so the framework can't map old rows to new and resets to top. Fix with **stable keys** and by updating items in place (DiffUtil / `LazyColumn` keys / `Identifiable`) rather than swapping the entire list.

```kotlin
// Merge-by-id keeps identity stable across refresh → no dupes, scroll preserved
fun merge(old: List<Item>, fresh: List<Item>): List<Item> {
    val byId = LinkedHashMap<String, Item>()
    fresh.forEach { byId[it.id] = it }      // newest wins
    old.forEach { byId.putIfAbsent(it.id, it) }
    return byId.values.toList()
}
```

The unifying cause is again **identity**: refresh must reconcile by stable id (replace/merge, not blind append) and the list must key by that id so diffing preserves position and avoids duplicates. Making the **local DB the source of truth** with `upsert` makes both bugs disappear, since the DB enforces uniqueness and the UI just observes it.

#### Q89. [Practical] A native module / TurboModule works in debug but crashes or is "undefined" in release. How do you debug this RN-specific class of bug?

Debug-vs-release divergence in RN usually comes from things that differ between the two build modes:

- **Native module not linked in release.** Autolinking or a manual link works in dev but the release build strips/misses it. Verify the module is registered, pods are installed (`pod install`), and (New Architecture) the **Codegen** spec is correct and regenerated.
- **Code stripped by minification/Proguard/R8.** Release shrinking removes classes referenced only via reflection/bridge. Add `-keep` rules for native module classes.
- **Dev-only APIs.** Code relying on the dev server, `__DEV__` branches, Flipper, or remote debugging behaves differently; remote JS debugging even changes timing/`Date` behavior.
- **Hermes vs JSC differences** or a bundle/runtime mismatch after an OTA update.

Debug approach: build the **release variant locally** (`--variant release` / Release scheme) so you can reproduce off the dev server, read **native logs** (`adb logcat` / Xcode device console) for the real native exception, and bisect by toggling the New Architecture flag / minification. The meta-lesson: **always test the release build before shipping** — "works in debug" proves almost nothing about the binary users get, because dev mode hides a different runtime, no minification, and the Metro server.

#### Q90. [Coding] Implement a typed wrapper that safely reads/writes the secure store (Keychain/Keystore) and never throws into the UI.

Secure-store APIs are low-level and error-prone; wrap them so callers get a clean, non-throwing surface and secrets never leak into logs.

```swift
// iOS — small typed Keychain wrapper
enum SecureStore {
    static func set(_ value: String, for key: String) -> Bool {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemDelete(query as CFDictionary)                 // overwrite semantics
        var add = query; add[kSecValueData as String] = data
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func get(_ key: String) -> String? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult static func delete(_ key: String) -> Bool {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                       kSecAttrAccount as String: key] as CFDictionary) == errSecSuccess
    }
}
```

Design notes: the API returns `Bool?`/optionals instead of throwing `OSStatus` into UI code; choose an **accessibility class** (`…ThisDeviceOnly` prevents the secret syncing to iCloud/other devices, and `AfterFirstUnlock` keeps background access working); **delete-then-add** gives clean overwrite semantics; and you must **never log the value**. On Android the analogous wrapper uses an EncryptedSharedPreferences/Keystore-backed store with the same non-throwing, typed surface, plus optional `setUserAuthenticationRequired` for biometric-gated keys.

#### Q91. [Practical] Network calls succeed on Wi-Fi but fail intermittently on cellular. What are the likely causes and how do you make the app resilient?

Cellular differs from Wi-Fi in ways that surface latent bugs: higher and variable latency, transient drops during handoff (cell tower / Wi-Fi↔cellular transitions), captive-portal/proxy interference, IPv6-only carriers, and stricter timeouts. Likely causes and fixes:

- **Too-tight timeouts.** A 5s timeout that's fine on Wi-Fi fails on a slow cell link. Use sensible, separate **connect vs read** timeouts and don't set them aggressively low.
- **No retry on transient failure.** Add **exponential backoff with jitter** for idempotent requests, and retry on reconnect rather than failing hard.
- **Assuming connectivity == reachability.** "Connected" doesn't mean the internet is reachable (captive portals). Validate with an actual request, not just the connectivity flag.
- **Large payloads.** Cellular makes big responses slow/expensive — paginate, compress (gzip/brotli), and respect metered-connection signals.
- **IPv6 / DNS issues** on some carriers — ensure your stack and CDN support IPv6.

```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
```

Make it resilient by designing **offline-first**: read from a local cache so transient drops don't show errors, queue writes in an outbox and replay on reconnect, and watch connectivity (`NWPathMonitor`/`ConnectivityManager`) to trigger sync. Always test on a throttled/lossy network (Network Link Conditioner / Android emulator network profiles), not just office Wi-Fi.

#### Q92. [Practical] A coworker's screen recomposes (Compose) or re-renders (React) far more than expected, causing scroll jank. Walk through how you'd find and fix the excess.

First **measure**, don't guess:

- **Compose:** enable the **Layout Inspector's recomposition counts** (or `Modifier.recomposeHighlighter`/composition tracing). Find composables with surprisingly high counts.
- **React:** use the **React DevTools Profiler** (highlight updates / flamegraph) to see which components render and why.

Then fix the usual causes:

- **State read too high.** A frequently-changing value (scroll offset, animation progress) read in a parent recomposes the whole subtree. **Defer the read** lower or into a lambda (`Modifier.offset { }`, `derivedStateOf`).
- **Unstable params / inline allocations.** Passing a plain `List`, or new lambdas/objects each pass, breaks skipping/memoization. Use immutable types; let the Compose compiler memoize, or `React.memo` + `useCallback`/`useMemo`.
- **Whole-object dependencies.** Reading an entire model when you need one field (SwiftUI/`@Observable` mitigates this; in React, select narrow slices).

```kotlin
// BAD: reading scroll offset here recomposes the list header every frame
val offset = listState.firstVisibleItemScrollOffset
// GOOD: defer to a layer that only re-runs draw/layout
Modifier.graphicsLayer { translationY = listState.firstVisibleItemScrollOffset.toFloat() }
```

The disciplined loop: **profile → identify the over-rendering node → stabilize its inputs or defer the state read → re-measure to confirm.** Both ecosystems reward the same instinct — keep rapidly-changing reads out of the composition/render phase.

#### Q104. [Practical] A deep link from an email opens the app to the home screen instead of the intended product page. How do you debug it?

A deep link silently falling back to the home screen (or the website) almost always means the OS **didn't route the URL to your app's destination** — either the link association failed, or your app received it but didn't navigate. Debug in order:

1. **Is the link even reaching the app?** Log the incoming URL at the entry point (`onNewIntent`/`NavDeepLink` on Android, `onOpenURL`/`continueUserActivity` on iOS). If nothing logs, the OS opened the browser instead — an **association problem**.
2. **Verify the association file.** Android **App Links** need `assetlinks.json` at `/.well-known/` with the correct package + **signing-cert SHA-256** (a wrong fingerprint after re-signing is the classic cause) and `autoVerify`; iOS **Universal Links** need a reachable **AASA** file at `/.well-known/` served as JSON with **no redirect**, plus the Associated Domains entitlement. Test verification status (`adb shell pm get-app-links`, or Apple's CDN-cached AASA).
3. **App received it but didn't navigate?** Then the **route parsing/back-stack reconstruction** is wrong — the path didn't match a destination pattern, a required arg was missing, or you navigated before the nav graph/session was ready (cold-start race). Queue the link until init completes and rebuild a sensible back stack.

The fixes map to the cause: broken association → fix the well-known file/fingerprint; cold-start race → buffer the URL until the navigator is ready; bad parsing → correct the route pattern and validate params. And always treat link params as **untrusted input** — authorize before honoring a link to a protected screen.

### 🟠 — extended

#### Q93. [Practical] You ship a release and the crash-free rate drops from 99.8% to 98.5% during staged rollout. What do you do, step by step?

Treat it as an incident with a clear playbook:

1. **Halt the rollout immediately.** In the Play Console / App Store Connect, pause the staged rollout so the regression stops reaching new users. This is the single highest-leverage action.
2. **Triage the crash.** In Crashlytics/Sentry, find the **top new crash** in this version (symbolicated via the uploaded mapping/dSYM), the % of sessions affected, and the OS/device/locale signature — many regressions are specific to one OS version or device class.
3. **Mitigate without a new binary if possible.** If the crash is gated by a **feature flag / remote config**, turn it off — that fixes *all* users on the bad version instantly. This is why risky features ship behind flags.
4. **Decide rollback vs roll-forward.** Stores don't let you "un-ship" a binary, but you can **halt** and keep the prior version dominant; if a code fix is needed, prepare an **expedited release** with a staged rollout and a tested fix.
5. **Communicate** crash rate, affected cohort, and ETA to stakeholders.
6. **Postmortem & guardrails:** add an **auto-halt rule** that pauses rollout on a crash-rate regression, a pre-release test that would have caught it, and a kill-switch for the offending feature.

The expert signal: you reach for **flags/kill-switches and rollout control** before a code fix, because mobile's release loop is slow and irreversible — you design releases to be *steerable* in production.

#### Q94. [Coding] Implement a token-refresh interceptor that refreshes once on 401 and retries the queued requests (single-flight refresh).

A naive "refresh on 401" floods the server with parallel refreshes when many requests 401 at once and can corrupt tokens. The correct design refreshes **once**, makes concurrent 401s wait for that single refresh, then retries.

```kotlin
class AuthAuthenticator(
    private val tokens: TokenStore,
    private val refreshApi: RefreshApi,
) : Authenticator {
    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val failedToken = response.request.header("Authorization")
        return runBlocking {
            mutex.withLock {
                val current = tokens.access()
                // Another request already refreshed → just use the new token.
                val fresh = if (current != null && "Bearer $current" != failedToken) {
                    current
                } else {
                    val new = runCatching { refreshApi.refresh(tokens.refresh()) }.getOrNull()
                        ?: return@withLock null      // refresh failed → force logout upstream
                    tokens.save(new); new.access
                }
                response.request.newBuilder()
                    .header("Authorization", "Bearer $fresh")
                    .build()
            }
        }
    }
}
```

Key mechanics: the **mutex serializes** refresh so only one runs; concurrent 401s entering the lock find that the token **already changed** (compare the token that failed vs. the current one) and skip a second refresh — that's the single-flight guarantee. OkHttp's `Authenticator` **automatically retries** the original request with the new token. On repeated refresh failure, clear tokens and route to login. The Swift/Combine analogue shares one refresh publisher with `.share()` and flatMaps queued requests onto it.

#### Q95. [Practical] Your app's memory grows steadily until it's killed (OOM) after navigating between screens many times. How do you hunt the leak?

Steady growth across navigation is a **leak**: each screen visit retains objects that should be freed. Hunt it systematically:

1. **Reproduce a tight loop:** navigate A→B→back, repeated, while recording memory (Android Studio Memory Profiler / **LeakCanary**, Xcode Instruments **Allocations + Leaks**, Flutter DevTools memory).
2. **Force GC and compare heaps.** Take a heap snapshot after each cycle; if instances of `BViewModel`/`BActivity`/`BViewController` accumulate (count rises by one per visit), that screen is being retained.
3. **Find the retaining reference.** Use the **reference/retention path** (LeakCanary's leak trace, Instruments' cycles, the memory-graph debugger) to see *what* still points at the dead screen.

Common culprits and fixes:

- **Android:** a `static`/singleton holding a `Context`/`Activity`/`View`; a listener/`BroadcastReceiver`/`Flow` collector registered but never removed; a coroutine launched on a non-lifecycle scope. Scope to `viewModelScope`/`repeatOnLifecycle`, unregister in `onStop`/`onDestroy`.
- **iOS:** a **retain cycle** — a closure capturing `self` strongly, a `delegate` that should be `weak`, a `Combine` subscription not stored/cancelled. Use `[weak self]`, `weak var delegate`, and store cancellables tied to the VC lifetime.
- **Flutter:** controllers/streams not `dispose()`d.

The discipline: prove the leak with a **rising-instance heap diff**, follow the **retention path** to the offending reference, then **scope or weaken** it — never "fix" by guessing.

#### Q96. [Coding] Implement an in-memory + disk two-tier cache with TTL for API responses.

A two-tier cache serves instantly from memory, falls back to disk, and refreshes from network — with a TTL so stale data is bounded.

```kotlin
class TwoTierCache<V>(
    private val memory: LruCache<String, Entry<V>> = LruCache(50),
    private val disk: DiskStore<Entry<V>>,
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry<V>(val value: V, val storedAt: Long)

    private fun fresh(e: Entry<V>?) = e != null && now() - e.storedAt < ttlMillis

    suspend fun get(key: String, fetch: suspend () -> V): V {
        memory.get(key)?.let { if (fresh(it)) return it.value }     // tier 1: RAM
        disk.read(key)?.let {                                       // tier 2: disk
            if (fresh(it)) { memory.put(key, it); return it.value }
        }
        val value = fetch()                                         // tier 3: network
        val entry = Entry(value, now())
        memory.put(key, entry); disk.write(key, entry)             // populate both tiers
        return value
    }
}
```

Design points: **memory first** (fastest, lost on process death), **disk second** (survives restarts), **network last**, writing back to both tiers on a miss. The **TTL** bounds staleness; for a better UX, return stale-but-present data immediately and refresh in the background (**stale-while-revalidate**) rather than blocking on the network. Size the memory tier by **byte cost** for images, evict LRU, and consider an ETag/`Last-Modified` conditional request so a refresh that hasn't changed returns `304` cheaply. Libraries like OkHttp's HTTP cache, Coil's memory+disk cache, or TanStack Query (with persistence) implement this pattern productionized.

#### Q97. [Practical] A specific Samsung/Pixel device (or one OS version) crashes while everything else is fine. How do you approach a device-specific bug?

Device/OS-specific crashes come from fragmentation: OEM customizations, a specific GPU/driver, an API behaving differently on one OS level, or a manufacturer-specific permission/background policy. Approach:

1. **Characterize the signature** from crash analytics: filter by device model, OS version, GPU, locale, and ABI. A crash isolated to one OEM/OS strongly hints at a platform-specific code path.
2. **Reproduce on that target.** Use a **device farm** (Firebase Test Lab, BrowserStack) or a physical unit — you often can't reproduce on a Pixel a bug that only hits a particular Samsung One UI build.
3. **Common buckets:** OEM-specific aggressive battery/background killing (Samsung/Xiaomi), GPU/driver bugs (hit by custom shaders/`RenderEffect`), `Vendor`-modified APIs, missing hardware features (no NFC/biometric), or an API available only above/below a certain level used without an availability check.
4. **Guard and degrade.** Wrap version-gated APIs in `Build.VERSION`/`#available` checks, feature-detect hardware, and provide a fallback path. Add a remote flag to disable the offending feature on affected models while you fix it.

The expert mindset: the install base is a **long-tail distribution**; you engineer for it by testing the device×OS matrix that matters, guarding platform-specific APIs, and keeping a remote kill-switch so one bad OEM combination doesn't require an emergency release for everyone.

#### Q105. [Coding] Implement an optimistic-update helper that applies a change immediately and rolls back on failure.

Optimistic UI makes mutations feel instant by updating local state *before* the server confirms, then reverting if the call fails. Encapsulate the snapshot/rollback so every mutation gets it consistently.

```kotlin
class OptimisticStore<S>(initial: S) {
    private val _state = MutableStateFlow(initial)
    val state = _state.asStateFlow()

    /** Apply `optimistic` now; run `commit`; revert to the snapshot if it throws. */
    suspend fun mutate(
        optimistic: (S) -> S,
        commit: suspend () -> Unit,
        reconcile: (S) -> S = { it },     // optional: fold in server truth on success
    ) {
        val snapshot = _state.value           // remember pre-change state
        _state.update(optimistic)             // 1. update UI immediately
        try {
            commit()                          // 2. send to server
            _state.update(reconcile)          // 3. apply authoritative result
        } catch (e: Throwable) {
            _state.value = snapshot           // 4. roll back on failure
            throw e                           // let the UI surface a "couldn't save" message
        }
    }
}

// Usage: toggle a "like" instantly, revert if the network call fails
store.mutate(
    optimistic = { it.copy(liked = true, likes = it.likes + 1) },
    commit = { api.like(postId) },
)
```

Design points: capture an **immutable snapshot** before mutating so rollback is exact; apply the optimistic change, then **reconcile** with the server's authoritative response on success (the server might return a corrected count). On failure, **restore the snapshot and signal the UI** so the user knows it didn't stick. Two cautions: concurrent optimistic mutations need care (queue them or reconcile by id so a rollback doesn't clobber an unrelated change), and pair optimistic writes with **idempotency keys** if you also retry them, so a retried "like" doesn't double-count. TanStack Query's `onMutate`/`onError`/`onSettled` implements exactly this lifecycle.

### 🔴 — extended

#### Q98. [Practical] Leadership asks you to cut p90 cold-start time by 30% across a large app. How do you run that as an initiative?

Treat it as a measured engineering program, not a one-off hack:

1. **Establish ground truth.** Instrument **p50/p90/p99 cold start** (TTID and TTFD) from real users (Firebase Performance / Android Vitals / MetricKit), segmented by device tier and OS — averages hide the low-end devices that dominate p90. Set the **30% target on p90 specifically**.
2. **Profile the critical path.** Use Macrobenchmark + system traces to attribute time: process/library load, `Application.onCreate`/`didFinishLaunching`, first-frame layout, and first-data fetch. Build a budget per phase.
3. **Attack the biggest line items:**
   - **Defer/lazy-init** SDKs and the DI graph off the launch path; remove synchronous I/O and reflection at startup.
   - Ship **Baseline Profiles** (and Cloud Profiles) so hot paths are AOT-compiled from first launch — often a double-digit % win alone.
   - Draw a **skeleton** immediately; load data after first frame; precompute/persist what the first screen needs.
   - Trim the app's startup dependency count; audit for accidental eager work (a logger that reads disk, an analytics SDK that blocks).
4. **Gate regressions in CI.** A Macrobenchmark in CI fails the build if cold start regresses, so the win doesn't erode.
5. **Roll out and verify on real users**, comparing p90 before/after by cohort.

The leadership signal: you make it **data-driven and durable** — target the right percentile, attribute time with traces, land the high-leverage fixes (Baseline Profiles, deferred init), and **institutionalize a CI guardrail** so the improvement sticks across many teams committing daily.

#### Q99. [Coding] Implement a generic "retry with backoff, jitter, timeout, and circuit breaker" policy for a resilient mobile network layer.

At scale you want one reusable resilience primitive that combines bounded retries, jitter, a per-call timeout, and a circuit breaker that stops hammering a failing dependency.

```kotlin
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openMillis: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var openedAt = 0L
    @Synchronized fun canRequest(): Boolean =
        if (failures < failureThreshold) true
        else now() - openedAt > openMillis            // half-open after cooldown
    @Synchronized fun onSuccess() { failures = 0 }
    @Synchronized fun onFailure() { if (++failures >= failureThreshold) openedAt = now() }
}

suspend fun <T> resilient(
    breaker: CircuitBreaker,
    maxAttempts: Int = 4,
    perCallTimeoutMs: Long = 10_000,
    isRetryable: (Throwable) -> Boolean = ::defaultRetryable,
    block: suspend () -> T,
): T {
    if (!breaker.canRequest()) throw CircuitOpenException()
    var attempt = 0
    while (true) {
        try {
            val result = withTimeout(perCallTimeoutMs) { block() }   // bound each attempt
            breaker.onSuccess(); return result
        } catch (e: Throwable) {
            breaker.onFailure()
            attempt++
            if (attempt >= maxAttempts || !isRetryable(e) || !breaker.canRequest()) throw e
            val backoff = (1L shl attempt) * 100                     // 200,400,800ms...
            val jitter = Random.nextLong(0, 100)
            delay(minOf(backoff + jitter, 5_000))                    // cap
        }
    }
}
```

Design rationale: the **circuit breaker** trips after repeated failures so the app stops sending doomed requests (saving battery and giving the backend room to recover), then **half-opens** after a cooldown to probe recovery. Each attempt is **timeout-bounded** so a hung socket doesn't stall forever. **Backoff + jitter** prevents synchronized retry storms across the fleet. Only **idempotent/retryable** errors retry; non-idempotent writes need an **idempotency key** before they can be safely retried. This single policy, applied via an OkHttp interceptor or a Swift middleware, gives the whole app consistent, well-behaved failure handling.

#### Q100. [Practical] Battery analytics show your app is a top drainer in the OS battery screen. How do you find and eliminate the drain?

Being flagged by the OS battery screen means the app is doing too much in the background, holding wakelocks, or over-using radio/GPS/CPU. Diagnose and fix:

1. **Attribute the drain.** Use **Battery Historian** / Android Vitals "excessive background wakeups" and "wakelocks," or **Xcode Energy Log / MetricKit** energy metrics. They break down CPU, network, location, and wake reasons.
2. **Common offenders:**
   - **Polling on a timer** (network or location) instead of push/scheduled work → switch to **push-triggered sync** and **WorkManager/BGTaskScheduler** (OS-batched, Doze-aware).
   - **Wakelocks held too long** or not released → scope them tightly; prefer the scheduler over manual wakelocks.
   - **High-accuracy location continuously** → use the coarsest accuracy and lowest frequency acceptable; stop updates when not needed; use geofencing/significant-location-change instead of continuous GPS.
   - **Chatty networking / no batching** → coalesce requests, back off, respect low-power and metered states.
   - **Hot CPU loops / busy animations off-screen** → pause work when backgrounded.
3. **Verify** with a before/after energy trace and watch Android Vitals' "battery" buckets over the next releases.

The expert framing: battery is a **shared resource the OS actively polices**; the durable fix is to let the **OS schedule deferrable work** (batched wake windows), trigger work by **push not polling**, minimize **radio/GPS/wakelock** time, and stop all non-essential work the moment the app backgrounds — then prove the improvement with energy telemetry, not intuition.

#### Q101. [Coding] Implement a thread-safe, lifecycle-scoped download manager that resumes interrupted downloads and reports progress.

A robust downloader must survive process death, resume partial transfers (HTTP range requests), report progress, and not leak when the screen goes away.

```kotlin
sealed interface DownloadState {
    data class Progress(val bytes: Long, val total: Long) : DownloadState
    data class Done(val file: File) : DownloadState
    data class Failed(val error: Throwable) : DownloadState
}

class Downloader(private val client: OkHttpClient) {
    fun download(url: String, target: File): Flow<DownloadState> = flow {
        val existing = if (target.exists()) target.length() else 0L     // resume point
        val request = Request.Builder().url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") } // ask for the rest
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                emit(DownloadState.Failed(IOException("HTTP ${resp.code}"))); return@flow
            }
            val body = resp.body ?: run { emit(DownloadState.Failed(IOException("empty"))); return@flow }
            val total = existing + (body.contentLength().takeIf { it > 0 } ?: -1L)
            val append = resp.code == 206
            FileOutputStream(target, append).use { out ->
                val buf = ByteArray(64 * 1024); var written = existing
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf); if (n == -1) break
                        out.write(buf, 0, n); written += n
                        emit(DownloadState.Progress(written, total))
                        currentCoroutineContext().ensureActive()         // honor cancellation
                    }
                }
            }
            emit(DownloadState.Done(target))
        }
    }.flowOn(Dispatchers.IO)
}

// Collected in a lifecycle-aware scope; cancelling the scope stops the download cleanly.
```

Key mechanics: a partially-written file's length is the **resume offset**, sent as a `Range` header; the server's **`206 Partial Content`** tells you to **append** rather than overwrite. Progress is emitted as a cold `Flow` so each collector drives its own download, and `ensureActive()`/structured concurrency makes **cancellation** (screen gone, user cancels) stop the loop and release the socket — no leak. For *guaranteed* completion across process death, hand the job to **WorkManager** (with `setForeground` for a notification) or iOS **`URLSession` background transfers**, which the OS continues even if the app is killed — the in-app `Flow` is for foreground UX, the background API for durability.

#### Q102. [Practical] Your team must support a brand-new foldable / large-screen form factor without forking the app. How do you architect adaptive UI to handle it sustainably?

Foldables, tablets, and desktop-class windows mean the UI can no longer assume a single phone-sized, fixed layout — it must **adapt to size, posture, and input** without a separate codebase:

- **Drive layout from window size classes, not device type.** Use **`WindowSizeClass`** (Compose/`WindowMetrics`) and SwiftUI **size classes**; branch on *available width/height* (compact / medium / expanded), so the same code handles a folded phone, an unfolded foldable, a tablet, and a resized window on desktop/ChromeOS.
- **Use canonical adaptive patterns:** list-detail (single pane on compact, two-pane on expanded), supporting-pane, and a navigation rail/drawer that becomes a bottom bar on compact. Material 3 adaptive / SwiftUI `NavigationSplitView` provide these.
- **Handle posture and continuity.** Respond to **folding features/hinges** (avoid placing content under the hinge; use tabletop posture for media), and **preserve state across fold/unfold and resize** (these are configuration changes — hoist state to `ViewModel`/`rememberSaveable`, never lose the user's place).
- **Support multi-window / drag-and-drop / keyboard & pointer** on large screens (resizable activities, no assumptions of exclusive full-screen, hover states).
- **Test the matrix** with foldable emulators/resizable previews and real devices.

The sustainable architecture keeps **one codebase with size-class-driven composition**: small, reusable panes assembled differently per window size, business logic untouched. The expert point: design for a **continuum of window sizes and postures** (the "responsive, not device-specific" mindset), preserve state across the frequent reconfigurations foldables cause, and verify across the size matrix — so a new form factor is a layout decision, not a fork.

#### Q106. [Practical] A flaky end-to-end UI test fails ~10% of the time in CI but passes locally. How do you stabilize the mobile test suite?

Flaky mobile E2E tests (Espresso/XCUITest/Maestro/Detox) erode trust and block merges. The root cause is almost always **timing and environment nondeterminism**, not "the test is cursed." Approach:

1. **Diagnose, don't retry-blindly.** Capture **screenshots/video and the view hierarchy on failure** in CI; a screenshot usually shows whether it failed mid-animation, on a dialog, or on real data.
2. **Eliminate sleeps; use idling/synchronization.** Replace `Thread.sleep` with proper waits — Espresso **IdlingResources** (or `ComposeTestRule.waitUntil`), XCUITest `waitForExistence`, Maestro's auto-waiting. Make the test wait on **app idleness**, not a fixed duration.
3. **Control nondeterminism.** Disable system **animations** in the test runner; **stub the network** (deterministic responses, no live backend); freeze time/clock; seed data via a test fixture so the test isn't racing a real API or a date.
4. **Isolate state.** Each test should start from a clean, known state (fresh install/launch args, cleared DB) so order dependence and leftover state can't cause intermittent failures.
5. **Quarantine and fix, track flake rate.** Move chronically flaky tests to a quarantine lane (so they don't block) but **track and burn down** the flake metric — never let "rerun until green" become the culture.

The expert framing: a flaky suite is a **reliability debt** that's as damaging as the bugs it's meant to catch. You stabilize by making tests **deterministic** (stubbed network, frozen clock, no animations, idling-based waits, isolated state) and by treating flakiness as a measured defect with an owner — because a suite people don't trust gets ignored, defeating its purpose.

#### Q107. [Coding] Implement a lightweight feature-flag / remote-config client with a safe default and cached last-known value.

Feature flags are how mobile teams ship dark, kill bad features without a release, and roll out gradually. A good client must **never block the UI**, always return a **safe default**, and survive offline by using the **last fetched value**.

```kotlin
class FeatureFlags(
    private val remote: RemoteConfigApi,
    private val cache: KeyValueStore,           // persists last-known values
    private val defaults: Map<String, Boolean>, // compiled-in safe defaults
) {
    private val memory = MutableStateFlow(loadCached())

    private fun loadCached(): Map<String, Boolean> =
        defaults + cache.readFlags()            // cache overrides defaults

    /** Synchronous, non-blocking read: memory → cache → compiled default. */
    fun isEnabled(key: String): Boolean =
        memory.value[key] ?: defaults[key] ?: false   // unknown flag defaults OFF

    /** Refresh in the background; never block the caller. Failures keep last-known values. */
    suspend fun refresh() {
        runCatching { remote.fetch() }
            .onSuccess { fresh ->
                cache.writeFlags(fresh)
                memory.value = defaults + fresh
            }
            // onFailure: do nothing — memory/cache already hold the last good config
    }
}

// Usage: gate a feature; safe even on first launch / offline
if (flags.isEnabled("new_checkout")) NewCheckout() else LegacyCheckout()
```

Design rationale: reads are **synchronous and pure** (memory → persisted cache → compiled default) so flag checks never stall a frame and a brand-new install still has *sane* behavior from the compiled defaults. Refresh happens **off the critical path**; if it fails (offline, server down) the app keeps the **last-known-good** config instead of reverting. An **unknown flag defaults to OFF** so a half-shipped feature can't accidentally enable itself. Production systems (Firebase Remote Config, LaunchDarkly, Statsig) add bucketing/targeting, but the safety contract is identical: **non-blocking reads, safe defaults, cached last-known value, and a kill-switch semantics** so you can disable a misbehaving feature for all versions instantly without a store release.

## ✅ Key Takeaways

- **Native vs cross-platform** is a trade-off, not a verdict: native maximizes performance/fidelity and day-one API access; React Native and Flutter maximize code/team sharing and iteration speed.
- The **main/UI thread is sacred** — render on it, do everything else (network, disk, compute) on background threads/coroutines/queues, then hop back to apply results.
- **Declarative, state-driven UI** (Compose, SwiftUI, React, Flutter widgets) plus **unidirectional data flow** and a single source of truth is the modern default everywhere.
- **Lifecycles matter:** acquire/release resources at the right callbacks, survive config changes with a `ViewModel`/observable model, and persist UI state.
- **Storage tiers:** key/value for prefs, SQLite/Room/SwiftData for structured data, **Keychain/Keystore for secrets** — never put tokens in plain prefs.
- **RN New Architecture** (JSI + TurboModules + Fabric) replaces the old async bridge; **Flutter** ships its own (Impeller) renderer and draws every pixel.
- **Performance** is about cold-start, jank (frame budget), and memory — virtualize lists, offload work, downsample images, and profile with the platform tools.
- **Respect battery/network limits** with WorkManager/BGTaskScheduler, backoff, and push-instead-of-poll; design **offline-first** with a local source of truth and a sync/outbox.
- **Releases are slow and irreversible** — design for store review, staged rollout, feature flags/kill-switches, and OTA only for the JS/bundle layer.

## ⚠️ Common Pitfalls

- **Blocking the main thread** with network/disk/heavy compute, causing jank or Android ANRs.
- **Rendering whole lists** with `.map()`/non-lazy containers instead of `FlatList`/`FlashList`/`LazyColumn`/`ListView.builder`.
- **Storing secrets in plain prefs/`UserDefaults`/AsyncStorage** instead of the Keychain/Keystore.
- **Treating silent/background push as guaranteed delivery** — the OS throttles and drops it; always have a foreground sync fallback.
- **Leaking memory** via retained `Context`/`Activity`, unbroken closure retain cycles (`[weak self]`), undisposed controllers/streams, and unbounded image caches.
- **Excessive recomposition / re-renders** from unstable params and inline lambdas/objects breaking equality checks.
- **Ignoring cold-start cost** by doing heavy work in `Application.onCreate`/`didFinishLaunching`.
- **Polling on a timer** instead of using OS-scheduled background work, draining battery and getting throttled by Doze.
- **Forgetting config-change/state restoration**, losing user input on rotation or process death.
- **Treating mobile releases like web deploys** — no instant rollback; failing to gate with staged rollout, flags, or kill-switches.
- **Assuming OTA can fix anything** — it can't update native code, dependencies, or permissions, and abusing it risks store removal.
- **Testing only on flagship devices**, missing jank/OOM on the low-end p90 device that dominates the install base.

## 📚 Further Reading

- **Android:** *Guide to app architecture*, *Jetpack Compose* docs, *Now in Android* sample app, *App startup*/*Baseline Profiles*, *WorkManager*, *Android Vitals*.
- **iOS:** Apple *SwiftUI* tutorials, *Human Interface Guidelines*, *Concurrency (async/await)* docs, *MetricKit*, *Instruments* user guide, *App Store Review Guidelines*.
- **React Native:** reactnative.dev — *The New Architecture*, *Performance*, *FlashList* (Shopify); *React Navigation*, *Reanimated*, **Expo / EAS** docs; *TanStack Query*.
- **Flutter:** flutter.dev — *Widget catalog*, *Rendering & layout*, *Impeller* engine docs, *Performance best practices*, *go_router*, **Riverpod**/**Bloc**.
- **Cross-cutting:** *Kotlin Multiplatform* docs; Jake Wharton / Chris Banes talks on Compose performance; *Crashlytics* / *Sentry* mobile guides; *LeakCanary* docs.
- Books: *Android Programming: The Big Nerd Ranch Guide*; *iOS Programming: The Big Nerd Ranch Guide*; *Flutter Apprentice* (Kodeco).
